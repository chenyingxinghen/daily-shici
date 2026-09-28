package com.example.daily_shici.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.daily_shici.data.prefs.SettingsRepository
import com.example.daily_shici.data.repository.LibraryRepository
import com.example.daily_shici.data.repository.PoemRepository
import com.example.daily_shici.ui.components.BookmarkIcon
import com.example.daily_shici.ui.components.BottomTab
import com.example.daily_shici.ui.components.ClockIcon
import com.example.daily_shici.ui.components.GearIcon
import com.example.daily_shici.ui.components.HairlineRule
import com.example.daily_shici.ui.components.PackageIcon
import com.example.daily_shici.ui.components.PaletteIcon
import com.example.daily_shici.ui.components.ScreenTitle
import com.example.daily_shici.ui.components.ShiciTabScreen
import com.example.daily_shici.ui.components.VerticalGap
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val nickname: String = "诗友",
    val readCount: Int = 0,
    val favoriteCount: Int = 0,
    val packCount: Int = 0,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val poemRepository: PoemRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                settings.nickname,
                libraryRepository.historyCount,
                libraryRepository.favoriteCount,
                poemRepository.observeInstalledPackCount(),
            ) { nickname, read, favorite, packs ->
                ProfileUiState(nickname, read, favorite, packs)
            }.collect { _state.value = it }
        }
    }
}

/**
 * 我的（设计稿 Screen 5）。
 *
 * 无账号体系，所以这里没有头像、昵称编辑入口——「诗友」只是一个本地显示名。
 * 统计数字全部来自本地库的实时计数（已读 = 历史条数，不是「读过多少次」）。
 */
@Composable
fun ProfileScreen(
    onSelectTab: (BottomTab) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenPacks: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ShiciTabScreen(selectedTab = BottomTab.MINE, onSelectTab = onSelectTab) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ShiciDimens.ProfileContentGap),
        ) {
            ScreenTitle("我的")

            Text(
                text = state.nickname,
                style = ShiciText.ProfileName,
                color = ShiciColors.Ink,
            )
            Text(
                text = "已读 ${state.readCount} 首 · 收藏 ${state.favoriteCount} 首 · 数据包 ${state.packCount} 个",
                style = ShiciText.EntryMeta,
                color = ShiciColors.InkFaint,
            )

            MenuRow(
                icon = { color -> BookmarkIcon(size = ShiciDimens.IconMedium, color = color) },
                label = "收藏",
                trailing = state.favoriteCount.takeIf { it > 0 }?.toString(),
                onClick = onOpenFavorites,
            )
            HairlineRule()
            MenuRow(
                icon = { color -> ClockIcon(size = ShiciDimens.IconMedium, color = color) },
                label = "阅读历史",
                trailing = state.readCount.takeIf { it > 0 }?.toString(),
                onClick = onOpenHistory,
            )
            HairlineRule()
            MenuRow(
                icon = { color -> PackageIcon(size = ShiciDimens.IconMedium, color = color) },
                label = "数据包管理",
                trailing = "${state.packCount} 个",
                onClick = onOpenPacks,
            )
            HairlineRule()
            MenuRow(
                icon = { color -> PaletteIcon(size = ShiciDimens.IconMedium, color = color) },
                label = "主题与排版",
                onClick = onOpenAppearance,
            )
            HairlineRule()
            MenuRow(
                icon = { color -> GearIcon(size = ShiciDimens.IconMedium, color = color) },
                label = "设置",
                onClick = onOpenSettings,
            )
        }
    }
}

/**
 * 菜单行。设计稿：图标 20 + 间距 14 + 文字 15sp，上下内边距 12，
 * 行之间是朱丝栏细线，没有任何卡片底或圆角。
 */
@Composable
private fun MenuRow(
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = ShiciDimens.MenuRowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ShiciDimens.MenuRowGap),
    ) {
        icon(ShiciColors.Ink)
        Text(text = label, style = ShiciText.MenuLabel, color = ShiciColors.Ink)
        if (trailing != null) {
            // 用 weight(1f) 撑开再右对齐，而不是 fillMaxWidth ——
            // 后者会把 trailing 拉成整行宽，右对齐后虽然看着对，但点击热区会盖住整行。
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text(
                text = trailing,
                style = ShiciText.EntryMeta,
                color = ShiciColors.InkFaint,
            )
        }
    }
}
