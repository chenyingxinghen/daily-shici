package com.example.daily_shici.data.remote

import com.example.daily_shici.data.remote.dto.AnnotationDto
import com.example.daily_shici.data.remote.dto.AuthorDto
import com.example.daily_shici.data.remote.dto.DailyBatchDto
import com.example.daily_shici.data.remote.dto.DailyResponseDto
import com.example.daily_shici.data.remote.dto.FacetsDto
import com.example.daily_shici.data.remote.dto.PacksResponseDto
import com.example.daily_shici.data.remote.dto.PoemDetailDto
import com.example.daily_shici.data.remote.dto.PoemListResponseDto
import com.example.daily_shici.data.remote.dto.SearchResponseDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 服务端接口。**与 `docs/02-API契约.md §1.2` 的 9 个端点一一对应**，不多不少。
 *
 * 两条约定：
 *
 * - **浏览类需求全部收敛到 [poems] 一张查询表** —— 「朝代=唐」「合集=唐诗三百首」
 *   「词牌=菩萨蛮」「作者=李白」都只是筛选参数，不是独立资源。
 * - **筛选取值传 key 而不是中文名**：`dynasty=tang`、`genre=shi`、`period=suitangwudai`。
 *   中文名只用于本地 Room 查询（离线包里就是中文名，见 docs/04 §2.2）。
 *   两者的对应关系由 [facets] 提供，客户端**不硬编码**。
 *
 * 所有调用都由仓库层包住，UI 不直接接触本接口 —— 「有网/无网」的分支必须集中在仓库层。
 */
interface ShiciApi {

    /** 每日一诗。`date` 支持过去 365 天与未来 90 天。 */
    @GET("daily")
    suspend fun daily(@Query("date") date: String? = null): DailyResponseDto

    /** 批量预取。**不要循环调用 [daily] 30 次**（docs/02 §3.1）。 */
    @GET("daily/batch")
    suspend fun dailyBatch(
        @Query("from") from: String? = null,
        @Query("days") days: Int = 30,
    ): DailyBatchDto

    /** 万能筛选列表。`sort` 默认 `weight`（精选发现优先于遍历）。 */
    @GET("poems")
    suspend fun poems(
        @Query("period") period: String? = null,
        @Query("dynasty") dynasty: String? = null,
        @Query("genre") genre: String? = null,
        @Query("cipai") cipai: String? = null,
        @Query("author_id") authorId: Long? = null,
        @Query("collection") collection: String? = null,
        @Query("form") form: String? = null,
        @Query("sort") sort: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20,
    ): PoemListResponseDto

    /** 详情。已收录时注疏字段非空（`docs/06`）。 */
    @GET("poems/{id}")
    suspend fun poem(@Path("id") poemId: Long): PoemDetailDto

    /**
     * 注疏（方式二）。**只读** —— 注疏是预先从网络导入的真人内容，
     * 「按需生成」那条路径随需求纠正已移除，所以没有对应的 POST。
     *
     * 未收录时返回 `status = "missing"`。此时 UI 应当引导用户去提问
     * （方式一，见 [AskStream]），而不是显示「加载失败」——
     * 83.4 万首里只有 7,388 首有注疏，没有注疏是常态。
     */
    @GET("poems/{id}/annotation")
    suspend fun annotation(@Path("id") poemId: Long): AnnotationDto

    /**
     * 随机漫游。接受与 [poems] 相同的筛选参数。
     *
     * ⚠️ 与 `poems/{id}` 的路径在服务端可能冲突，故服务端必须把 `random` 路由
     * 注册在 `{id}` **之前**，否则 `GET /poems/random` 会被当成 `poem_id=random`
     * 而返回 422。
     */
    @GET("poems/random")
    suspend fun random(
        @Query("period") period: String? = null,
        @Query("dynasty") dynasty: String? = null,
        @Query("genre") genre: String? = null,
        @Query("cipai") cipai: String? = null,
        @Query("author_id") authorId: Long? = null,
        @Query("collection") collection: String? = null,
        @Query("form") form: String? = null,
    ): PoemDetailDto

    /**
     * 全量搜索。在线时用它的结果**直接替换**本地结果，不做合并（打分体系不同）。
     *
     * @param scope `all` / `title` / `author` / `content`
     */
    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("scope") scope: String? = null,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20,
    ): SearchResponseDto

    /** 筛选器元数据。启动时拉一次并长期缓存（`Cache-Control: max-age=86400`）。 */
    @GET("facets")
    suspend fun facets(): FacetsDto

    /**
     * 作者信息。作品列表走 [poems] 的 `author_id`。
     *
     * ⚠️ **`author_id` 不可离线使用**（docs/02 §3.8）：它是服务端分配的，
     * 离线包里只有作者名。故本地导航键是**归一化作者名**。
     */
    @GET("authors/{id}")
    suspend fun author(@Path("id") authorId: Long): AuthorDto

    /** 离线包清单。**必须联网**：失败时退化为用本地已装记录渲染。 */
    @GET("packs")
    suspend fun packs(): PacksResponseDto
}
