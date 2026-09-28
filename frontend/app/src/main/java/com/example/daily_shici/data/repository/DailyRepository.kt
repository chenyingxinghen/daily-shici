package com.example.daily_shici.data.repository

import com.example.daily_shici.data.local.dao.AnnotationDao
import com.example.daily_shici.data.local.dao.DailyDao
import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.local.entity.DailyCacheEntity
import com.example.daily_shici.data.net.NetworkMonitor
import com.example.daily_shici.data.prefs.SettingsRepository
import com.example.daily_shici.data.remote.ShiciApi
import com.example.daily_shici.data.remote.dto.DailyItemDto
import com.example.daily_shici.data.remote.dto.DailyResponseDto
import com.example.daily_shici.domain.daily.DailyFallback
import com.example.daily_shici.domain.model.DailyPoem
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 每日一诗。**三级读取**，每一级都有明确的失败兜底，任何一级都不能让页面空白：
 *
 * 1. 有网且今天未请求过 → `GET /daily` → 写缓存 → 展示
 * 2. 无网或请求失败       → 读 `daily_cache[today]` → 展示（与在线体验一致）
 * 3. 缓存未命中（断网超水位）→ 本地兜底选诗 → 展示并标注「离线精选」
 *
 * 顺带负责把每日一诗的**注释**也写进注释缓存（docs/06 §8）：
 * 每日一诗是打开频次最高的那一首，若它的注释只活在这一次网络响应里，
 * 用户第二天断网再打开会发现注释没了 —— 而在线时明明有。
 * `daily_cache` 表本身不存注释（它已经有 7 列，再加就成宽表了），
 * 复用 `poem_annotation` 这张按 poemId 索引的表现在更自然。
 */
@Singleton
class DailyRepository @Inject constructor(
    private val dailyDao: DailyDao,
    private val poemDao: PoemDao,
    private val annotationDao: AnnotationDao,
    private val api: ShiciApi,
    private val networkMonitor: NetworkMonitor,
    private val settings: SettingsRepository,
) {

    suspend fun today(forceRefresh: Boolean = false): DailyPoem? {
        val date = LocalDate.now()
        val key = date.toString()

        // 第 1 级：在线
        val lastRequested = settings.lastDailyRequestDate.first()
        if (networkMonitor.isOnline() && (forceRefresh || lastRequested != key)) {
            val remote = runCatching { api.daily(key) }.getOrNull()
            if (remote != null && remote.poem.poemId != 0L) {
                dailyDao.upsertAll(listOf(remote.toCacheEntity()))
                cacheAnnotation(remote.poem)
                settings.setLastDailyRequestDate(key)
                runCatching { dailyDao.pruneBefore(date.minusDays(DailyFallback.CACHE_DAYS).toString()) }
                return remote.toDomain()
            }
        }

        // 第 2 级：缓存
        dailyDao.find(key)?.let { cached ->
            val cachedPoem = cached.toDomain()
            // 缓存行里没有注释，从注释缓存补齐 —— 否则「在线有注释、离线没注释」
            return cachedPoem.copy(
                poem = cachedPoem.poem.copy(
                    annotation = annotationDao.find(cachedPoem.poem.poemId)?.toDomain(),
                ),
            )
        }

        // 第 3 级：本地兜底（确定性选择，同一天多次打开必得同一首）
        val total = poemDao.countAll()
        if (total == 0) return null
        val offset = DailyFallback.offsetFor(date, total)
        val entity = poemDao.pickAtOffset(offset) ?: return null

        val fallback = DailyPoem(
            date = date,
            poem = entity.toDetail(annotation = annotationDao.find(entity.poemId)?.toDomain()),
            isOfflineFallback = true,
        )
        dailyDao.upsertAll(listOf(fallback.toCacheEntity()))
        return fallback
    }

    private suspend fun cacheAnnotation(poem: com.example.daily_shici.data.remote.dto.PoemDetailDto) {
        val annotation = poem.toAnnotation() ?: return
        runCatching {
            annotationDao.upsert(annotation.toEntity(poem.poemId, System.currentTimeMillis()))
        }
    }

    /**
     * 预取未来 30 天。**只补缺口** —— 从缓存最大日期 +1 开始请求，
     * 否则每天都会重复拉 30 天。
     *
     * 失败不重试到死：交给 WorkManager 的指数退避，网络恢复后系统会重跑。
     */
    suspend fun prefetch() {
        if (!networkMonitor.isOnline()) return
        val today = LocalDate.now()
        val lastCached = dailyDao.maxDate()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val days = DailyFallback.prefetchCount(today, lastCached)
        if (days <= 0) return

        val from = DailyFallback.prefetchStart(today, lastCached)
        val response = runCatching { api.dailyBatch(from.toString(), days) }.getOrNull() ?: return
        if (response.items.isEmpty()) return

        dailyDao.upsertAll(response.items.map { it.toCacheEntity() })
        // 预取来的 30 天里若已带注释（服务端已生成），一并缓存 ——
        // 这样断网一个月也读得到注释，与正文的离线策略对齐。
        runCatching {
            val now = System.currentTimeMillis()
            response.items.forEach { item ->
                item.poem.toAnnotation()?.let { annotationDao.upsert(it.toEntity(item.poem.poemId, now)) }
            }
        }
        dailyDao.pruneBefore(today.minusDays(DailyFallback.CACHE_DAYS).toString())
        settings.setLastDailyRequestDate(today.toString())
    }

    /** 通知点击后要跳到的那首诗。取不到就返回 null，不发通知。 */
    suspend fun todayPoemId(): Long? = today()?.poem?.poemId

    suspend fun cachedCount(): Int = dailyDao.count()
}

/**
 * `/daily` 与 `/daily/batch` 的条目结构一致（都是 `{date, poem}`），
 * 但 `/daily` 多一个 `pool_size`，故是两个 DTO。映射逻辑共用一份，避免两处漂移。
 */
fun DailyResponseDto.toCacheEntity() = toItem().toCacheEntity()

fun DailyItemDto.toCacheEntity() = DailyCacheEntity(
    date = date,
    poemId = poem.poemId,
    title = poem.title,
    author = poem.author.name,
    dynasty = poem.dynasty,
    content = poem.content,
    collectionsJson = poem.collections.takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
    isOfflineFallback = false,
)

fun DailyResponseDto.toDomain(): DailyPoem = toItem().toDomain()

fun DailyItemDto.toDomain(): DailyPoem = DailyPoem(
    date = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now()),
    poem = poem.toDomain(isDownloaded = true),
    isOfflineFallback = false,
)

/** `/daily` 去掉 `pool_size` 后与 `/daily/batch` 的条目同构，这里做一次归一。 */
private fun DailyResponseDto.toItem() = DailyItemDto(date = date, poem = poem)
