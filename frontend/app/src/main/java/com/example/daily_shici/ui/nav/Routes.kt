package com.example.daily_shici.ui.nav

/** 路由表。字面量集中在这里，避免各处硬编码字符串出现拼写漂移。 */
object Routes {
    const val DAILY = "daily"
    const val BROWSE = "browse"
    const val SEARCH = "search"
    const val MINE = "mine"

    /** 随机漫游。二级页面，从「每日」进入。 */
    const val ROAM = "roam"

    const val FAVORITES = "favorites"
    const val HISTORY = "history"
    const val PACKS = "packs"
    const val APPEARANCE = "appearance"
    const val SETTINGS = "settings"

    const val DETAIL_ARG_POEM_ID = "poemId"
    const val DETAIL_PATTERN = "detail/{$DETAIL_ARG_POEM_ID}"

    fun detail(poemId: Long): String = "detail/$poemId"

    /** 底部导航的四个 Tab 路由，其余为二级页面（隐藏底栏）。 */
    val tabRoutes = setOf(DAILY, BROWSE, SEARCH, MINE)
}
