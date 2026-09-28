package com.example.daily_shici.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.daily_shici.data.repository.LibraryRepository
import com.example.daily_shici.domain.model.LibraryEntry
import com.example.daily_shici.ui.components.DetailTopBar
import com.example.daily_shici.ui.components.EmptyHint
import com.example.daily_shici.ui.components.EntryMeta
import com.example.daily_shici.ui.components.HairlineRule
import com.example.daily_shici.ui.components.SystemStatusBarInset
import com.example.daily_shici.ui.components.ScreenTitle
import com.example.daily_shici.ui.components.VerticalGap
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 收藏 / 阅读历史，共用一套排版，只是数据源与空态文案不同。 */
enum class LibraryMode(val title: String) {
    FAVORITES("收藏"),
    HISTORY("阅读历史"),
}

data class LibraryUiState(
    val mode: LibraryMode = LibraryMode.FAVORITES,
    val entries: List<LibraryEntry> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** 由界面在进入时告知当前模式（同一 ViewModel 服务两个路由）。 */
    fun observe(mode: LibraryMode) {
        _state.value = _state.value.copy(mode = mode)
        viewModelScope.launch {
            val source = when (mode) {
                LibraryMode.FAVORITES -> libraryRepository.favorites
                LibraryMode.HISTORY -> libraryRepository.history
            }
            source.collect { entries -> _state.value = _state.value.copy(entries = entries) }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { libraryRepository.clearHistory() }
    }

    fun removeOne(poemId: Long) {
        viewModelScope.launch {
            when (_state.value.mode) {
                // 收藏列表里「移除」= 取消收藏
                LibraryMode.FAVORITES -> libraryRepository.setFavorite(poemId, false)
                LibraryMode.HISTORY -> libraryRepository.removeHistory(poemId)
            }
        }
    }
}

/**
 * 收藏 / 阅读历史。
 *
 * 设计稿没有这一页，因此沿用同一套语言：
 * 状态栏 → 顶栏（返回 + 标题）→ 条目列表 → 朱丝栏分隔。
 *
 * 关键交互：**包被卸载后条目仍然保留**，只是变灰并标注「未下载」，
 * 提供「重新下载」入口 —— 绝不因为用户卸了包就悄悄清掉他的收藏。
 */
@Composable
fun LibraryScreen(
    mode: LibraryMode,
    onBack: () -> Unit,
    onOpenPoem: (Long) -> Unit,
    onOpenPacks: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(mode) { viewModel.observe(mode) }

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
                .padding(
                    start = ShiciDimens.ContentPadding,
                    end = ShiciDimens.ContentPadding,
                    top = ShiciDimens.DetailContentPaddingVertical,
                ),
        ) {
            DetailTopBar(onBack = onBack)
            VerticalGap(ShiciDimens.ContentGap)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                ScreenTitle(mode.title)
                if (mode == LibraryMode.HISTORY && state.entries.isNotEmpty()) {
                    Text(
                        text = "清空",
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = viewModel::clearHistory,
                        ),
                        style = ShiciText.EntryMeta,
                        color = ShiciColors.InkFaint,
                    )
                }
            }
            VerticalGap(ShiciDimens.ContentGap)

            if (state.entries.isEmpty()) {
                EmptyHint(
                    when (mode) {
                        LibraryMode.FAVORITES -> "还没有收藏。在诗里点一下心形或「收藏」即可"
                        LibraryMode.HISTORY -> "还没有阅读记录"
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(ShiciDimens.BrowseContentGap),
                ) {
                    items(state.entries, key = { it.poemId }) { entry ->
                        LibraryRow(
                            entry = entry,
                            onOpen = { onOpenPoem(entry.poemId) },
                            onRemove = { viewModel.removeOne(entry.poemId) },
                            onDownload = onOpenPacks,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    entry: LibraryEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onDownload: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // 未下载的条目仍然可点（有网时能拉详情），但视觉上明确降级
    val titleColor = if (entry.isDownloaded) ShiciColors.Ink else ShiciColors.InkFaint

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = { if (entry.isDownloaded) onOpen() else onDownload() },
            ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ShiciDimens.EntryGap)) {
            Text(text = entry.displayTitle, style = ShiciText.EntryTitle, color = titleColor)
            if (entry.isDownloaded) {
                Text(
                    text = entry.excerpt,
                    modifier = Modifier.fillMaxWidth(),
                    style = ShiciText.EntryExcerpt,
                    color = ShiciColors.InkSoft,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entry.isDownloaded) {
                        EntryMeta(entry.attribution)
                    } else {
                        Text(text = "未下载", style = ShiciText.EntryMeta, color = ShiciColors.Vermilion)
                        Text(
                            text = "· 去下载",
                            style = ShiciText.EntryMeta,
                            color = ShiciColors.Vermilion,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDownload,
                            ),
                        )
                    }
                }
                Text(
                    text = "移除",
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRemove,
                    ),
                    style = ShiciText.EntryMeta,
                    color = ShiciColors.InkFaint,
                )
            }
        }
        VerticalGap(ShiciDimens.BrowseContentGap)
        HairlineRule()
    }
}
