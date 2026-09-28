package com.example.daily_shici.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.daily_shici.ui.browse.BrowseScreen
import com.example.daily_shici.ui.components.BottomTab
import com.example.daily_shici.ui.daily.DailyScreen
import com.example.daily_shici.ui.detail.DetailScreen
import com.example.daily_shici.ui.library.LibraryMode
import com.example.daily_shici.ui.library.LibraryScreen
import com.example.daily_shici.ui.packs.PacksScreen
import com.example.daily_shici.ui.profile.ProfileScreen
import com.example.daily_shici.ui.roam.RoamScreen
import com.example.daily_shici.ui.search.SearchScreen
import com.example.daily_shici.ui.settings.AppearanceScreen
import com.example.daily_shici.ui.settings.SettingsScreen

/**
 * 应用导航图。
 *
 * 四个 Tab 用 `popUpTo(DAILY) + saveState/restoreState`，这样来回切 Tab 时
 * 各自的滚动位置与筛选条件都保留 —— 否则用户切回来发现筛选被重置，
 * 会认为是 bug。
 */
@Composable
fun ShiciNavHost(
    startPoemId: Long? = null,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.DAILY) {

        composable(Routes.DAILY) {
            DailyScreen(
                onSelectTab = { selectTab(navController, it) },
                onOpenPoem = { navController.navigateToDetail(it) },
                onRoam = { navController.navigate(Routes.ROAM) },
            )
        }

        composable(Routes.BROWSE) {
            BrowseScreen(
                onSelectTab = { selectTab(navController, it) },
                onOpenPoem = { navController.navigateToDetail(it) },
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onSelectTab = { selectTab(navController, it) },
                onOpenPoem = { navController.navigateToDetail(it) },
            )
        }

        composable(Routes.MINE) {
            ProfileScreen(
                onSelectTab = { selectTab(navController, it) },
                onOpenFavorites = { navController.navigate(Routes.FAVORITES) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenPacks = { navController.navigate(Routes.PACKS) },
                onOpenAppearance = { navController.navigate(Routes.APPEARANCE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.DETAIL_PATTERN) {
            DetailScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.ROAM) {
            RoamScreen(
                onBack = { navController.popBackStack() },
                onOpenPoem = { navController.navigateToDetail(it) },
            )
        }

        composable(Routes.FAVORITES) {
            LibraryScreen(
                mode = LibraryMode.FAVORITES,
                onBack = { navController.popBackStack() },
                onOpenPoem = { navController.navigateToDetail(it) },
                onOpenPacks = { navController.navigate(Routes.PACKS) },
            )
        }

        composable(Routes.HISTORY) {
            LibraryScreen(
                mode = LibraryMode.HISTORY,
                onBack = { navController.popBackStack() },
                onOpenPoem = { navController.navigateToDetail(it) },
                onOpenPacks = { navController.navigate(Routes.PACKS) },
            )
        }

        composable(Routes.PACKS) {
            PacksScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.APPEARANCE) {
            AppearanceScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }

    // 从通知进入：先落到「每日」再压详情，这样返回键能回到主界面而不是直接退出。
    LaunchedEffect(startPoemId) {
        if (startPoemId != null) navController.navigateToDetail(startPoemId)
    }
}

private fun selectTab(navController: NavHostController, tab: BottomTab) {
    val route = when (tab) {
        BottomTab.DAILY -> Routes.DAILY
        BottomTab.BROWSE -> Routes.BROWSE
        BottomTab.SEARCH -> Routes.SEARCH
        BottomTab.MINE -> Routes.MINE
    }
    navController.navigate(route) {
        popUpTo(Routes.DAILY) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.navigateToDetail(poemId: Long) {
    if (poemId <= 0L) return
    navigate(Routes.detail(poemId))
}
