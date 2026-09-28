package com.example.daily_shici.domain.model

/**
 * 浏览页的筛选维度。选的是**维度**，具体取值在下一行。
 *
 * ⚠️ **与设计稿的一处刻意差异**：设计稿第一行是 `全部 / 朝代 / 体裁 / 主题`。
 * 其中「主题」（山水/思乡/送别…）**在数据源与 API 契约里都不存在** ——
 * `docs/01 §5.5` 明确写了「上游无此数据，v1 不做」。而「词牌」是契约里有
 * （`/facets` 的 `cipais`，约 1000 条）、且被文档称为「高价值派生字段，
 * 能支撑按词牌浏览这一核心功能」的维度。
 *
 * 因此把「主题」换成「词牌」，并补上契约里的「合集」（选本浏览是核心入口，
 * `docs/01 §4.1` 特意为它保留了合集归属）。这是**用有数据的维度替换没数据的维度**，
 * 不是砍功能。
 */
enum class FacetKey(val label: String) {
    ALL("全部"),
    DYNASTY("朝代"),
    GENRE("体裁"),
    CIPAI("词牌"),
    COLLECTION("合集");

    /** 该维度在 [AppliedFilters] 上对应哪个字段。 */
    fun current(filters: AppliedFilters): FacetOption? = when (this) {
        ALL -> null
        DYNASTY -> filters.dynasty
        GENRE -> filters.genre
        CIPAI -> filters.cipai
        COLLECTION -> filters.collection
    }
}

/**
 * 一个可选项。**必须同时带 key 与 label** —— 这是本项目最容易踩的一处：
 *
 * - **在线查询用 `key`**：契约规定 `dynasty=tang`、`genre=shi`、`collection=tangshi-sanbai`；
 * - **本地查询用 `label`**：离线包里存的是中文名（`docs/04 §2.2`），
 *   Room 里只有 `dynasty = '唐'`。
 *
 * 只留一个的话，在线或离线必然有一边查不到东西。`docs/02 §3.8` 已为作者定过同样的规矩
 * （「本地导航键是作者名，`author_id` 仅用于在线」），朝代/体裁/词牌/合集同理。
 */
data class FacetOption(
    val key: String,
    val label: String,
    val count: Int = 0,
) {
    companion object {
        /** 从服务端 key/name 构造；两者缺一时互为兜底，避免出现空查询。 */
        fun of(key: String, name: String, count: Int = 0) = FacetOption(
            key = key.ifBlank { name },
            label = name.ifBlank { key },
            count = count,
        )
    }
}

/**
 * 筛选器元数据。来自 `GET /facets`，本地缓存于 `facets_cache` 表。
 *
 * 核心用途不是渲染列表（列表用本地 distinct 就够），而是提供**应有条数**：
 * 本地命中 0 条时，若这里有 >0，说明是「未下载」而不是「真的没有」。
 */
data class Facets(
    val options: Map<FacetKey, List<FacetOption>> = emptyMap(),
    /** 服务端全量条数。 */
    val total: Int = 0,
    val generatedAt: String = "",
) {
    fun optionsFor(key: FacetKey): List<FacetOption> = options[key].orEmpty()

    /** 按**本地名**（label）查应有条数。 */
    fun expectedCount(key: FacetKey, label: String?): Int {
        if (key == FacetKey.ALL || label == null) return total
        return optionsFor(key).firstOrNull { it.label == label }?.count ?: 0
    }

    /** 为某组筛选条件推算「本地本该有多少条」。用于 `ListResult` 的三态判定。 */
    fun expectedCountOf(filters: AppliedFilters): Int = when {
        filters.dynasty != null -> expectedCount(FacetKey.DYNASTY, filters.dynasty.label)
        filters.genre != null -> expectedCount(FacetKey.GENRE, filters.genre.label)
        filters.cipai != null -> expectedCount(FacetKey.CIPAI, filters.cipai.label)
        filters.collection != null -> expectedCount(FacetKey.COLLECTION, filters.collection.label)
        else -> total
    }

    val isEmpty: Boolean get() = options.isEmpty() && total == 0
}
