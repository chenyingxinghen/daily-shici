package com.example.daily_shici.data.repository

import com.example.daily_shici.data.local.dao.FacetsDao
import com.example.daily_shici.data.local.dao.PackDao
import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.local.entity.FacetsCacheEntity
import com.example.daily_shici.data.remote.ShiciApi
import com.example.daily_shici.data.remote.dto.FacetsDto
import com.example.daily_shici.domain.model.FacetKey
import com.example.daily_shici.domain.model.FacetOption
import com.example.daily_shici.domain.model.Facets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 筛选器元数据：本地缓存优先，无缓存才请求，成功后写回 `facets_cache`。
 *
 * 它同时承担第二职责：**提供「应有条数」**，用于区分「真的没有」与「该内容未下载」。
 * 这是「用户以为 App 坏了」与「用户知道该下载什么」的分界线。
 *
 * ⚠️ 缓存的是**服务端原始 payload**（而不是转好的领域模型）——
 * 领域模型里 `FacetKey` 的枚举可能随版本变化，缓存里存原始 JSON 才能在改模型后
 * 仍可解码，不然一次客户端升级就得强制清缓存。
 */
@Singleton
class FacetsRepository @Inject constructor(
    private val facetsDao: FacetsDao,
    private val poemDao: PoemDao,
    private val packDao: PackDao,
    private val api: ShiciApi,
    private val json: Json,
) {

    /** 当前 Facets：缓存命中就直接用，否则尝试网络，再否则退化为「用本地推断」。 */
    suspend fun facets(forceRefresh: Boolean = false): Facets {
        if (!forceRefresh) {
            cached()?.let { return it }
        }

        val remote = runCatching { api.facets() }.getOrNull()
        if (remote != null) {
            facetsDao.upsert(
                FacetsCacheEntity(
                    payloadJson = json.encodeToString(FacetsDto.serializer(), remote),
                    generatedAt = System.currentTimeMillis(),
                )
            )
            return remote.toDomain()
        }

        // 完全离线：用本地已下载内容推断可选项。
        // ⚠️ 此路径产出的选项**没有服务端 key**（key 退化为中文名），
        // 只能服务本地查询。一旦恢复联网，facets() 会重新从服务端取带 key 的版本。
        return localFallback()
    }

    /** 仅用于「未下载」判定的应有条数；取不到就返回 0，宁可不说也不要误报。 */
    suspend fun expectedCount(key: FacetKey, label: String?): Int =
        cached()?.expectedCount(key, label) ?: 0

    private suspend fun cached(): Facets? =
        facetsDao.get()
            ?.let { runCatching { decode(it.payloadJson) }.getOrNull() }
            ?.toDomain()

    /** 浏览页顶部的「已下载 N 首」旁边那行「服务端共 N 首」。 */
    suspend fun remoteTotal(): Int = cached()?.total ?: 0

    fun observeDownloadedCount(): Flow<Int> = poemDao.observeCount().map { it }

    /**
     * 完全离线时用本地已下载内容推断可选项。
     *
     * ⚠️ **四个维度都要推断**。早先只推断了朝代与体裁，于是离线时切到「词牌」/「合集」
     * 第二行是空的 —— 用户看到的是「这个分类坏了」，而真实原因只是没联网。
     * 词牌取 `poem.cipai` 的 distinct，合集取本地 `collection` 表（入库时已登记）。
     *
     * ⚠️ 此路径产出的选项**没有服务端 key**（key 退化为中文名 / slug），
     * 只能服务本地查询。一旦恢复联网，`facets()` 会重新从服务端取带 key 的版本。
     */
    private suspend fun localFallback(): Facets {
        val dynasties = poemDao.distinctDynasties()
        val genres = poemDao.distinctGenres()
        val cipais = poemDao.distinctCipais()
        val collections = runCatching { packDao.collections() }.getOrDefault(emptyList())
        return Facets(
            options = buildMap {
                if (dynasties.isNotEmpty()) {
                    put(FacetKey.DYNASTY, dynasties.map { FacetOption(key = it, label = it) })
                }
                if (genres.isNotEmpty()) {
                    put(FacetKey.GENRE, genres.map { FacetOption(key = it, label = it) })
                }
                if (cipais.isNotEmpty()) {
                    put(FacetKey.CIPAI, cipais.map { FacetOption(key = it, label = it) })
                }
                if (collections.isNotEmpty()) {
                    // 合集本地按 slug 查询，key 必须是 slug 而不是中文名
                    put(
                        FacetKey.COLLECTION,
                        collections.map { FacetOption(key = it.slug, label = it.name) },
                    )
                }
            },
            total = poemDao.countAll(),
            generatedAt = "",
        )
    }

    private fun decode(payload: String): FacetsDto =
        json.decodeFromString(FacetsDto.serializer(), payload)
}

/**
 * `/facets` → 领域模型。**key 与 name 都要留下**（docs/02 §3.7）：
 * `key` 供在线查询（`dynasty=tang`），`name` 供本地查询与展示（`dynasty='唐'`）。
 *
 * 契约里还有 `periods`（大期，顶部 Tab 用）。**客户端暂不映射它** ——
 * 设计稿的浏览页只有「全部/朝代/体裁/主题」一行，没有大期 Tab。
 * 若后续要补大期筛选，在此加一条 `FacetKey.PERIOD` 映射即可，`poem` 表的
 * `period` 列与 `pageByPeriod` 查询已经就位。
 */
fun FacetsDto.toDomain() = Facets(
    options = buildMap {
        if (dynasties.isNotEmpty()) {
            put(FacetKey.DYNASTY, dynasties.map { FacetOption.of(it.key, it.name, it.count) })
        }
        if (genres.isNotEmpty()) {
            put(FacetKey.GENRE, genres.map { FacetOption.of(it.key, it.name, it.count) })
        }
        if (cipais.isNotEmpty()) {
            put(FacetKey.CIPAI, cipais.map { FacetOption.of(it.key, it.name, it.count) })
        }
        if (collections.isNotEmpty()) {
            put(
                FacetKey.COLLECTION,
                collections.map { FacetOption.of(it.slug, it.name, it.count) },
            )
        }
    },
    // 契约的 facets 没有顶层 total；各朝代数之和即全量（一首诗恰属一个朝代）
    total = dynasties.sumOf { it.count },
    generatedAt = generatedAt,
)
