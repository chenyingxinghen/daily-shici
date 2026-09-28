package com.example.daily_shici.data.repository

import androidx.paging.PagingSource
import com.example.daily_shici.data.local.dao.AnnotationDao
import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.local.dao.PackDao
import com.example.daily_shici.data.local.entity.PoemEntity
import com.example.daily_shici.data.net.NetworkMonitor
import com.example.daily_shici.data.paging.PoemPagingSource
import com.example.daily_shici.data.paging.RemotePoemPagingSource
import com.example.daily_shici.data.remote.AskEvent
import com.example.daily_shici.data.remote.AskStream
import com.example.daily_shici.data.remote.ShiciApi
import com.example.daily_shici.domain.model.Annotation
import com.example.daily_shici.domain.model.AppliedFilters
import com.example.daily_shici.domain.model.FacetKey
import com.example.daily_shici.domain.model.ListResult
import com.example.daily_shici.domain.model.PoemDetail
import com.example.daily_shici.domain.model.PoemSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.random.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 诗词读取。全部走「先本地，缺了再网络，网络结果写回本地」，
 * 但**诗词正文绝不因网络结果覆盖本地** —— 本地才是权威，它来自校验过 sha256 的包。
 */
@Singleton
class PoemRepository @Inject constructor(
    private val poemDao: PoemDao,
    private val packDao: PackDao,
    private val annotationDao: AnnotationDao,
    private val api: ShiciApi,
    private val askStream: AskStream,
    private val facetsRepository: FacetsRepository,
    private val networkMonitor: NetworkMonitor,
) {

    fun observeDownloadedCount(): Flow<Int> = poemDao.observeCount()

    /** 「已下载 N 首」旁边附上「数据包 M 个」的 M。 */
    fun observeInstalledPackCount(): Flow<Int> = packDao.observeInstalled().map { it.size }

    suspend fun downloadedCount(): Int = poemDao.countAll()

    suspend fun countByPack(packId: String): Int = poemDao.countByPack(packId)

    /**
     * 详情。本地命中即返回；否则拉网络（可读，只是没有离线副本）。
     * 注意两端都把 [PoemDetail.isDownloaded] 如实标出来，UI 才能给出正确提示。
     *
     * 注释走**本地缓存优先**（docs/06 §8）：它是服务端花一分钟 GPU 才产出的东西，
     * 每次开详情页都重取既浪费又不必要；而且离线时它得还在，
     * 否则「本地优先」这个产品定位就漏了一块。
     */
    suspend fun detail(poemId: Long): PoemDetail? {
        poemDao.findById(poemId)?.let { entity ->
            return entity.toDetail(
                collections = packDao.collectionsOf(poemId),
                annotation = annotationDao.find(poemId)?.toDomain(),
            )
        }
        if (!networkMonitor.isOnline()) {
            // 正文都没下载，但注释缓存里可能有（比如先在线看过、后来卸了包）——
            // 这种情况给不出完整的诗，只能返回 null，由 UI 提示「尚未下载」。
            return null
        }
        val remote = runCatching { api.poem(poemId) }.getOrNull() ?: return null
        val detail = remote.toDomain(isDownloaded = false)
        cacheAnnotation(poemId, detail.annotation)
        return detail
    }

    /**
     * 取注疏（**本地缓存优先，再问服务端**）。
     *
     * [detail] 已经在返回详情时顺带缓存过注疏，所以这里主要是给「详情已展示、
     * 但想确认服务端有没有新注疏」以及离线读缓存用。
     *
     * 返回 `null` 是寻常结果 —— 全量 83.4 万首里只有 7,388 首有注疏（`docs/06 §3.3`）。
     */
    suspend fun annotation(poemId: Long): Annotation? {
        annotationDao.find(poemId)?.toDomain()?.let { return it }
        if (!networkMonitor.isOnline()) return null
        val dto = runCatching { api.annotation(poemId) }.getOrNull() ?: return null
        val annotation = dto.toDomain()
        cacheAnnotation(poemId, annotation)
        return annotation
    }

    /**
     * 方式一：就某首诗提问，返回**流式事件**（`docs/06 §6`）。
     *
     * 不做任何本地缓存：问答是针对**某个具体问题**的回答，不是这首诗的系统注疏。
     * 把它当注疏缓存起来，会让「这首诗有没有注疏」变成一个含义模糊的状态。
     */
    fun ask(poemId: Long, question: String): Flow<AskEvent> = askStream.ask(poemId, question)

    /**
     * 把拿到的注疏写进本地缓存。
     *
     * **只写不删**：服务端返回空（未收录）时不动缓存 ——
     * 那多半是本地有旧结果而服务端因故查不到（库被清、版本回滚），
     * 此时删掉本地反而把用户已经能看到的东西弄没了。
     */
    private suspend fun cacheAnnotation(poemId: Long, annotation: Annotation?) {
        if (annotation == null || annotation.isEmpty) return
        runCatching { annotationDao.upsert(annotation.toEntity(poemId, System.currentTimeMillis())) }
    }

    /** 本地（已下载）分页源。 */
    fun localPagingSource(filters: AppliedFilters): PagingSource<Int, PoemSummary> =
        PoemPagingSource(poemDao, filters)

    /** 服务端全量分页源。断网时每一页都会 [androidx.paging.LoadState.Error]，由 UI 决定是否回落。 */
    fun remotePagingSource(filters: AppliedFilters): PagingSource<String, PoemSummary> =
        RemotePoemPagingSource(api, poemDao, filters)

    /**
     * 随机漫游取一首。**在线优先，离线落本地**。
     *
     * 在线拿到 id 后**先查本地**：本地那行带 `cipai` / `weight` 等入库时算好的字段，
     * 也带合集归属，比直接用网络 DTO 更完整；查不到才用网络结果（标为未下载）。
     */
    suspend fun randomPoem(filters: AppliedFilters): PoemDetail? {
        if (networkMonitor.isOnline()) {
            val remote = runCatching {
                api.random(
                    dynasty = filters.dynasty?.key?.takeIf { it.isNotBlank() },
                    genre = filters.genre?.key?.takeIf { it.isNotBlank() },
                    cipai = filters.cipai?.key?.takeIf { it.isNotBlank() },
                    collection = filters.collection?.key?.takeIf { it.isNotBlank() },
                )
            }.getOrNull()
            if (remote != null && remote.poemId != 0L) {
                poemDao.findById(remote.poemId)?.let { entity ->
                    return entity.toDetail(
                        collections = packDao.collectionsOf(remote.poemId),
                        annotation = annotationDao.find(remote.poemId)?.toDomain(),
                    )
                }
                cacheAnnotation(remote.poemId, remote.toAnnotation())
                return remote.toDomain(isDownloaded = false)
            }
        }
        return randomLocal(filters)
    }

    /**
     * 离线漫游：在筛选范围内随机取一首。
     *
     * 做法是 `count → 随机 offset → pageBy*(limit=1, offset)`，
     * 而不是 `ORDER BY RANDOM()` —— 后者在 2 万行上每摇一次都要全表排序，
     * 而这里两次查询都走得上索引。
     */
    private suspend fun randomLocal(filters: AppliedFilters): PoemDetail? {
        val dynasty = filters.localDynasty
        val genre = filters.localGenre
        val cipai = filters.localCipai
        val collection = filters.localCollection

        val count = when {
            dynasty != null -> poemDao.countByDynasty(dynasty)
            genre != null -> poemDao.countByGenre(genre)
            cipai != null -> poemDao.countByCipai(cipai)
            collection != null -> poemDao.countByCollection(collection)
            else -> poemDao.countAll()
        }
        if (count <= 0) return null

        val offset = Random.nextInt(count)
        val entity = when {
            dynasty != null -> poemDao.pageByDynasty(dynasty, 1, offset).firstOrNull()
            genre != null -> poemDao.pageByGenre(genre, 1, offset).firstOrNull()
            cipai != null -> poemDao.pageByCipai(cipai, 1, offset).firstOrNull()
            collection != null -> poemDao.pageByCollection(collection, 1, offset).firstOrNull()
            else -> poemDao.pageAll(1, offset).firstOrNull()
        } ?: return null

        return entity.toDetail(
            collections = packDao.collectionsOf(entity.poemId),
            annotation = annotationDao.find(entity.poemId)?.toDomain(),
        )
    }

    /** 标题/作者精确到单首的跳转（每日一诗「读全文」）。 */
    suspend fun summaryOf(poemId: Long): PoemEntity? = poemDao.findById(poemId)

    /**
     * 判定「列表为空」到底是哪一种空。
     *
     * 这条逻辑值得单独实现并测试：它是「用户以为 App 坏了」与
     * 「用户知道该下载什么」的分界线。判定依据是服务端统计的应有条数 ——
     * 若服务端说这里本该有内容而本地为 0，就是 `NotDownloaded` 而不是 `Empty`。
     *
     * 用 **label（中文名）** 去查应有条数：`facets_cache` 里同时存了 key 与 name，
     * 而这里要比的是「本地这个筛选条件下本该有多少条」，本地口径是中文名。
     */
    suspend fun describeEmpty(filters: AppliedFilters): ListResult {
        val (key, label) = when {
            filters.dynasty != null -> FacetKey.DYNASTY to filters.dynasty.label
            filters.genre != null -> FacetKey.GENRE to filters.genre.label
            filters.cipai != null -> FacetKey.CIPAI to filters.cipai.label
            filters.collection != null -> FacetKey.COLLECTION to filters.collection.label
            else -> FacetKey.ALL to null
        }
        val expected = facetsRepository.expectedCount(key, label)
        val localTotal = poemDao.countAll()

        return if (expected > localTotal) {
            ListResult.NotDownloaded(likelyMissingPacks())
        } else {
            ListResult.Empty(filters)
        }
    }

    /**
     * 缺内容时给出**候选**包而不是一个确定答案。
     *
     * 本地一首都没有 → 用户大概率还没装任何非内置包，把未装包列出来即可；
     * 已经装了包却仍缺 → 缺的可能是没装的另一个包，同样列出未装包。
     * 不做更细的猜测，因为「猜错这个包有你要的诗」比「让你自己看看清单」更糟。
     */
    private suspend fun likelyMissingPacks(): List<String> =
        runCatching {
            val installed = packDao.installed().map { it.packId }.toSet()
            api.packs().packs.map { it.packId }.filterNot { it in installed }
        }.getOrDefault(emptyList())

    /** 收藏/历史条目重装包后恢复「已下载」状态用的批量查询。 */
    suspend fun existingIds(ids: List<Long>): Set<Long> =
        if (ids.isEmpty()) emptySet() else poemDao.findByIds(ids).map { it.poemId }.toSet()
}
