package com.example.daily_shici.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.daily_shici.data.pack.PackInstaller
import com.example.daily_shici.data.prefs.SettingsRepository
import com.example.daily_shici.data.repository.PackRepository
import com.example.daily_shici.ui.components.DetailTopBar
import com.example.daily_shici.ui.components.EntryMeta
import com.example.daily_shici.ui.components.HairlineRule
import com.example.daily_shici.ui.components.QuietLink
import com.example.daily_shici.ui.components.SystemStatusBarInset
import com.example.daily_shici.ui.components.ScreenTitle
import com.example.daily_shici.ui.components.VerticalGap
import com.example.daily_shici.ui.theme.SansSC
import com.example.daily_shici.ui.theme.SerifSC
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val nickname: String = "诗友",
    val notifyEnabled: Boolean = true,
    val notifyHour: Int = 8,
    val notifyMinute: Int = 0,
    val message: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val installer: PackInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val (hour, minute) = settings.notifyTime.first()
            _state.value = SettingsUiState(
                nickname = settings.nickname.first(),
                notifyEnabled = settings.notifyEnabled.first(),
                notifyHour = hour,
                notifyMinute = minute,
            )
        }
    }

    fun setNickname(name: String) {
        _state.value = _state.value.copy(nickname = name)
        viewModelScope.launch { settings.setNickname(name) }
    }

    fun setNotifyEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(notifyEnabled = enabled)
        viewModelScope.launch { settings.setNotifyEnabled(enabled) }
    }

    fun setNotifyTime(hour: Int, minute: Int) {
        _state.value = _state.value.copy(notifyHour = hour, notifyMinute = minute)
        viewModelScope.launch { settings.setNotifyTime(hour, minute) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            settings.clearRecentQueries()
            _state.value = _state.value.copy(message = "已清空搜索历史")
        }
    }

    /** 修复入口：内置包数据异常时，强制重导一次。seed 导入是幂等的。 */
    fun reimportBuiltins() {
        viewModelScope.launch {
            val result = runCatching { installer.importBuiltins(force = true) }
            _state.value = _state.value.copy(
                message = result.fold(
                    onSuccess = { "已重新导入内置数据包（$it 首）" },
                    onFailure = { it.message ?: "重导失败" },
                )
            )
        }
    }
}

/** 设置。设计稿只有入口行，页面本身沿用同一套语言。 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShiciColors.Paper),
    ) {
        SystemStatusBarInset()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = ShiciDimens.ContentPadding,
                    end = ShiciDimens.ContentPadding,
                    top = ShiciDimens.DetailContentPaddingVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(ShiciDimens.ProfileContentGap),
        ) {
            DetailTopBar(onBack = onBack)
            ScreenTitle("设置")

            SettingBlock(label = "昵称", hint = "仅本机显示，无账号体系") {
                BasicTextField(
                    value = state.nickname,
                    onValueChange = viewModel::setNickname,
                    singleLine = true,
                    textStyle = ShiciText.MenuLabel.copy(color = ShiciColors.Ink),
                    cursorBrush = SolidColor(ShiciColors.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.setNickname(state.nickname) }),
                )
            }
            HairlineRule()

            SettingBlock(
                label = "每日提醒",
                hint = "每天 %02d:%02d（系统可能有 ±15 分钟偏差）".format(state.notifyHour, state.notifyMinute),
            ) {
                Switch(
                    checked = state.notifyEnabled,
                    onCheckedChange = viewModel::setNotifyEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = ShiciColors.Paper,
                        checkedTrackColor = ShiciColors.Vermilion,
                    ),
                )
            }
            if (state.notifyEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ShiciDimens.InlineRowGap),
                ) {
                    listOf(7 to 30, 8 to 0, 12 to 0, 21 to 0).forEach { (hour, minute) ->
                        val active = state.notifyHour == hour && state.notifyMinute == minute
                        com.example.daily_shici.ui.components.MarkedText(
                            text = "%02d:%02d".format(hour, minute),
                            color = if (active) ShiciColors.Vermilion else ShiciColors.InkSoft,
                            style = if (active) ShiciText.FilterActive else ShiciText.Filter,
                            underlined = active,
                            onClick = { viewModel.setNotifyTime(hour, minute) },
                        )
                    }
                }
            }
            HairlineRule()

            SettingBlock(label = "搜索历史", hint = "只保存在本机") {
                QuietLink(text = "清空", onClick = viewModel::clearSearchHistory)
            }
            HairlineRule()

            SettingBlock(label = "内置数据包", hint = "数据异常时可重新导入（唐诗三百首 · 宋词三百首）") {
                QuietLink(text = "重新导入", onClick = viewModel::reimportBuiltins)
            }

            if (state.message != null) {
                EntryMeta(state.message.orEmpty())
            }
        }
    }
}

/**
 * 主题与排版。
 *
 * 设计稿只提供了「素纸朱栏」一套浅色方案，没有深色稿。这里如实呈现当前令牌，
 * 而不是假装有主题切换 —— 编造一套未设计过的深色配色的代价，
 * 比承认「还没设计」大得多。
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShiciColors.Paper),
    ) {
        SystemStatusBarInset()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = ShiciDimens.ContentPadding,
                    end = ShiciDimens.ContentPadding,
                    top = ShiciDimens.DetailContentPaddingVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(ShiciDimens.ContentGap),
        ) {
            DetailTopBar(onBack = onBack)
            ScreenTitle("主题与排版")

            Text(
                text = "当前主题：素纸朱栏",
                style = ShiciText.ProfileName,
                color = ShiciColors.Ink,
            )
            Text(
                text = "设计稿仅提供这一套浅色方案，深色模式尚未设计，因此不提供切换。",
                style = ShiciText.EntryMeta,
                color = ShiciColors.InkFaint,
            )

            HairlineRule()

            Text(text = "色板", style = ShiciText.SectionTitle, color = ShiciColors.Ink)
            SwatchRow("纸底 Paper", "#FAF5EB", ShiciColors.Paper)
            SwatchRow("墨 Ink", "#1A1A1A", ShiciColors.Ink)
            SwatchRow("朱红 Vermilion", "#C53A2A", ShiciColors.Vermilion)
            SwatchRow("次级文字 InkSoft", "#5C5449", ShiciColors.InkSoft)
            SwatchRow("三级文字 InkFaint", "#9A8F7C", ShiciColors.InkFaint)
            SwatchRow("朱丝栏 Rule", "#E5DDC8", ShiciColors.Rule)
            SwatchRow("加重细线 RuleStrong", "#D8CFBB", ShiciColors.RuleStrong)

            HairlineRule()

            Text(text = "字体", style = ShiciText.SectionTitle, color = ShiciColors.Ink)
            EntryMeta("诗词正文 · 衬线（设计稿指定 Noto Serif SC，系统以衬线族兜底）")
            EntryMeta("界面文字 · 无衬线（设计稿指定 Noto Sans SC，系统以无衬线族兜底）")
        }
    }
}

@Composable
private fun SettingBlock(
    label: String,
    hint: String,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ShiciDimens.MenuRowPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(text = label, style = ShiciText.MenuLabel, color = ShiciColors.Ink)
            EntryMeta(hint)
        }
        control()
    }
}

@Composable
private fun SwatchRow(name: String, hex: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ShiciDimens.MenuRowGap),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { },
        )
        Text(text = name, style = ShiciText.EntryExcerpt, color = ShiciColors.Ink)
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        EntryMeta(hex)
    }
}
