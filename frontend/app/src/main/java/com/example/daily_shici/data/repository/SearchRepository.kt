package com.example.daily_shici.data.repository

import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.net.NetworkMonitor
import com.example.daily_shici.data.remote.ShiciApi

import com.example.daily_shici.domain.model.HighlightRange
import com.example.daily_shici.domain.model.PoemSummary
import com.example.daily_shici.domain.model.SearchHit
import com.example.daily_shici.domain.model.SearchOutcome
import com.example.daily_shici.domain.model.SearchSource
import com.example.daily_shici.domain.search.SearchText
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 搜索。**有网走服务端，无网才用本地 LIKE。**
 *
 * 理由：服务端搜索覆盖全量，本地只是子集。在线时用服务端结果**直接替换**本地结果即可，
 * 无需合并 —— 两套打分体系合并排名只会引入复杂度而无收益。
 *
 * 这里有一处曾经犯过、记录以免重犯的错误：早期设计写的是「在线也搜本地」，
 * 理由是「调服务端会搜到未下载的诗，点进去没正文」。**该推理方向是反的** ——
 * 正确做法是返回这些结果并**标注**「未下载」，而不是不返回它们。
 * 搜索是发现功能，完整性就是它的全部意义。
 */
@Singleton
class SearchRepository @Inject constructor(
    private val poemDao: PoemDao,
    private val api: ShiciApi,
    private val networkMonitor: NetworkMonitor,
) {

    /** 自动选路：在线优先服务端，失败或离线回落本地。 */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): SearchOutcome {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return SearchOutcome(emptyList(), SearchSource.LOCAL_CACHE)

        if (networkMonitor.isOnline()) {
            searchRemote(trimmed, limit)?.let { return it }
        }
        return searchLocal(trimmed, limit)
    }

    /** 本地 LIKE：**立即**出结果（常见规模 10–50 ms），用于「边打边搜」的第一级反馈。 */
    suspend fun searchLocal(query: String, limit: Int = DEFAULT_LIMIT): SearchOutcome {
        val tiers = poemDao.searchTiers(query, limit)
        if (tiers.isEmpty()) return SearchOutcome(emptyList(), SearchSource.LOCAL_ONLY)

        val entities = poemDao.findByIds(tiers.map { it.poemId }).associateBy { it.poemId }
        val hits = tiers.mapNotNull { tier ->
            val entity = entities[tier.poemId] ?: return@mapNotNull null
            // 服务端 tiers 里 0=标题 1=作者 2=正文，与契约的 hit 取值对齐
            val field = when (tier.tier) {
                0 -> "title"
                1 -> "author"
                else -> "content"
            }
            // 本地路径没有「命中句 + 前后各一句」的上下文，退化用 excerpt 作为 snippet，
            // 并在其中标出命中位置。这与服务端只承诺**粗排序**一致（docs/02 §5.1）。
            SearchHit(
                poem = entity.toSummary(isDownloaded = true),
                snippet = entity.excerpt,
                highlights = SearchText.highlightsOf(entity.excerpt, query),
                hitField = field,
            )
        }
        return SearchOutcome(hits, SearchSource.LOCAL_ONLY)
    }

    /**
     * 服务端全量搜索。返回 null 表示请求失败（调用方应回落到本地）。
     * 未下载的命中**保留并标注**，同时给出「下载所属包」的入口。
     */
    suspend fun searchRemote(query: String, limit: Int = DEFAULT_LIMIT): SearchOutcome? {
        val response = runCatching { api.search(query, limit = limit) }.getOrNull() ?: return null

        val ids = response.items.map { it.poemId }
        val localIds = if (ids.isEmpty()) emptySet() else poemDao.findByIds(ids).map { it.poemId }.toSet()

        var notDownloaded = 0
        val hits = response.items.map { dto ->
            val downloaded = dto.poemId in localIds
            if (!downloaded) notDownloaded++
            SearchHit(
                poem = PoemSummary(
                    poemId = dto.poemId,
                    title = dto.title,
                    excerpt = dto.snippet,
                    author = dto.author,
                    dynasty = dto.dynasty,
                    isDownloaded = downloaded,
                ),
                snippet = dto.snippet,
                highlights = dto.highlights.toHighlightRanges(),
                hitField = dto.hit.ifBlank { "content" },
            )
        }
        return SearchOutcome(hits, SearchSource.REMOTE, notDownloadedCount = notDownloaded)
    }

    suspend fun isOnline(): Boolean = networkMonitor.isOnline()

    companion object {
        const val DEFAULT_LIMIT = 50

        /** 边打边搜的防抖时长：每个按键都打一次网络是不可接受的。 */
        const val DEBOUNCE_MILLIS = 300L
    }
}

/**
 * 契约的 `highlights` 是 `[[start, end], …]`，**按 Unicode 码点计**、左闭右开。
 *
 * 与客户端内部统一使用的那个**同一个数据结构**（[HighlightRange]），
 * 所以 UI 层对「高亮来自在线还是离线」无感知 —— 这正是把它定义成纯数据的好处。
 */
private fun List<List<Int>>.toHighlightRanges(): List<HighlightRange> =
    mapNotNull { pair ->
        if (pair.size < 2) return@mapNotNull null
        val start = pair[0]
        val end = pair[1]
        if (start < 0 || end < start) null else HighlightRange(start, end)
    }
