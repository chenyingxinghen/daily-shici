package com.example.daily_shici.data.paging

import android.util.Log
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.remote.ShiciApi
import com.example.daily_shici.data.repository.toDomain
import com.example.daily_shici.domain.model.AppliedFilters
import com.example.daily_shici.domain.model.PoemSummary

/**
 * 服务端全量列表的分页源（`GET /poems`）。
 *
 * 与本地源的两处结构差异：
 * - **key 是游标字符串**（keyset 分页）而不是 offset 整数。游标不透明，
 *   客户端只回传不解析（docs/02 §3.3）。因此没有上一页，`prevKey` 恒为 null。
 * - **筛选传 key 而不是中文名**（`dynasty=tang`、`cipai=浣溪沙`、`collection=tangshi-sanbai`）。
 *   本地那份是中文名，两者的对应关系由 `/facets` 提供，客户端不硬编码。
 *
 * 「未下载」在这里标注而不是过滤：在线浏览的意义就是看到本地没有的内容，
 * 把它过滤掉等于把在线浏览退化成本地浏览。
 */
class RemotePoemPagingSource(
    private val api: ShiciApi,
    private val poemDao: PoemDao,
    private val filters: AppliedFilters,
) : PagingSource<String, PoemSummary>() {

    /** keyset 分页无法倒着翻，只能从头刷新。 */
    override fun getRefreshKey(state: PagingState<String, PoemSummary>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, PoemSummary> {
        return try {
            val response = api.poems(
                // 在线筛选统一传 `/facets` 发布的 key：dynasty=tang / genre=shi /
                // cipai=浣溪沙 / collection=slug。朝代的 key 由服务端 `resolve_dynasty`
                // 还原成库内中文名（唐）再去查，与 collection 按 slug 解析是同一套路。
                dynasty = filters.dynasty?.key?.takeIf { it.isNotBlank() },
                genre = filters.genre?.key?.takeIf { it.isNotBlank() },
                cipai = filters.cipai?.key?.takeIf { it.isNotBlank() },
                collection = filters.collection?.key?.takeIf { it.isNotBlank() },
                cursor = params.key,
                limit = params.loadSize,
            )

            val ids = response.items.map { it.poemId }
            val downloadedIds =
                if (ids.isEmpty()) emptySet() else poemDao.findByIds(ids).map { it.poemId }.toSet()

            LoadResult.Page(
                data = response.items.map { dto ->
                    dto.toDomain(isDownloaded = dto.poemId in downloadedIds)
                },
                prevKey = null,
                // 空页 + 非空游标会让 Paging 继续往下翻，翻到没有为止 ——
                // 实际表现为「列表空白但一直在转」。空页就当到底。
                nextKey = response.nextCursor?.takeIf { response.items.isNotEmpty() },
            )
        } catch (error: Exception) {
            // 真机最常见的成因是「连不到服务端」（DNS / 路由 / TLS），而不是契约问题。
            // 打出来便于用 logcat 确认根因（如 java.net.UnknownHostException / SSLHandshakeException）。
            Log.e("RemotePoemPagingSource", "online load failed: ${error::class.simpleName}: ${error.message}", error)
            LoadResult.Error(error)
        }
    }
}
