package com.example.daily_shici.data.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.daily_shici.data.local.dao.PoemDao
import com.example.daily_shici.data.repository.toSummary
import com.example.daily_shici.domain.model.AppliedFilters
import com.example.daily_shici.domain.model.PoemSummary

/**
 * 本地诗词列表的分页源。
 *
 * 手写而不是用 Room 的 `PagingSource` 返回值：因为列表有 5 种互斥的筛选形态，
 * 用 Room 的注解式分页要么写 5 个 Dao 方法各自返回 PagingSource，要么拼动态 SQL。
 * 这里一个类就覆盖了，且**排序方向集中在一处**——
 * `ORDER BY weight DESC, poemId ASC` 这条要求散落到多个查询里迟早会漏。
 *
 * ⚠️ **四种筛选必须全部落到 `when` 里**。早先只处理了 dynasty / genre，
 * 于是选中词牌或合集时静默落到 `pageAll` —— 用户看到的是「点了没反应」，
 * 而不是「筛不出东西」，后者至少还能提示。这类「漏一个分支 = 静默降级」
 * 的写法以后加维度时要格外当心。
 *
 * 输出统一为 [PoemSummary] 而不是 [com.example.daily_shici.data.local.entity.PoemEntity]：
 * 在线分页源只能产出 `PoemSummary`（服务端没有 weight/packId），
 * 两条路产出同一个类型，UI 层才不必为「本地还是在线」分叉。
 */
class PoemPagingSource(
    private val poemDao: PoemDao,
    private val filters: AppliedFilters,
) : PagingSource<Int, PoemSummary>() {

    override fun getRefreshKey(state: PagingState<Int, PoemSummary>): Int? {
        val anchor = state.anchorPosition ?: return null
        val page = state.closestPageToPosition(anchor) ?: return null
        return page.prevKey?.plus(state.config.pageSize) ?: page.nextKey?.minus(state.config.pageSize)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PoemSummary> {
        val offset = (params.key ?: 0).coerceAtLeast(0)
        // 先落到局部 val：localDynasty 是带自定义 getter 的计算属性，
        // 直接 `if (filters.localDynasty != null)` 无法通过智能转换。
        val dynasty = filters.localDynasty
        val genre = filters.localGenre
        val cipai = filters.localCipai
        val collection = filters.localCollection
        return try {
            // 注意用 **label（中文名）** 而不是 key：本地包里存的就是中文名
            // （docs/04 §2.2），Room 里只有 `dynasty = '唐'`。
            // 合集是唯一例外，它按 slug 查（见 AppliedFilters.localCollection）。
            val rows = when {
                dynasty != null -> poemDao.pageByDynasty(dynasty, params.loadSize, offset)
                genre != null -> poemDao.pageByGenre(genre, params.loadSize, offset)
                cipai != null -> poemDao.pageByCipai(cipai, params.loadSize, offset)
                collection != null -> poemDao.pageByCollection(collection, params.loadSize, offset)
                else -> poemDao.pageAll(params.loadSize, offset)
            }
            LoadResult.Page(
                data = rows.map { it.toSummary(isDownloaded = true) },
                prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                nextKey = if (rows.size < params.loadSize) null else offset + rows.size,
            )
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    override val jumpingSupported: Boolean get() = true
}
