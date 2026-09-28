package com.example.daily_shici.ui.daily

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.daily_shici.data.repository.DailyRepository
import com.example.daily_shici.data.repository.LibraryRepository
import com.example.daily_shici.domain.model.DailyPoem
import com.example.daily_shici.ui.components.AccentLink
import com.example.daily_shici.ui.components.BottomTab
import com.example.daily_shici.ui.components.CenteredScrollColumn
import com.example.daily_shici.ui.components.EmptyHint
import com.example.daily_shici.ui.components.HeartIcon
import com.example.daily_shici.ui.components.QuietLink
import com.example.daily_shici.ui.components.Seal
import com.example.daily_shici.ui.components.ShiciTabScreen
import com.example.daily_shici.ui.components.VerticalGap
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

data class DailyUiState(
    val loading: Boolean = true,
    val daily: DailyPoem? = null,
    val isFavorite: Boolean = false,
)

@HiltViewModel
class DailyViewModel @Inject constructor(
    private val dailyRepository: DailyRepository,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DailyUiState())
    val state: StateFlow<DailyUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val daily = dailyRepository.today(forceRefresh)
            val favorite = daily?.poem?.poemId
                ?.let { libraryRepository.observeIsFavorite(it).first() }
                ?: false
            _state.value = DailyUiState(loading = false, daily = daily, isFavorite = favorite)

            // 打开即算读过，每日提醒据此不再打扰。
            daily?.poem?.poemId?.let { libraryRepository.markRead(it) }
        }
    }

    fun toggleFavorite() {
        val poemId = _state.value.daily?.poem?.poemId ?: return
        viewModelScope.launch {
            val nowFavorite = libraryRepository.toggleFavorite(poemId)
            _state.value = _state.value.copy(isFavorite = nowFavorite)
        }
    }

    fun refresh() = load(forceRefresh = true)
}

/**
 * 每日一诗（设计稿 Screen 1）。
 *
 * 与其余三个 Tab 的唯一布局差异：内容**垂直居中**而不是顶对齐 ——
 * 一首诗浮在纸面上，上下留白对等。设计稿的几何也印证了这点
 * （正文块的中心落在内容区中点）。
 */
@Composable
fun DailyScreen(
    onSelectTab: (BottomTab) -> Unit,
    onOpenPoem: (Long) -> Unit,
    onRoam: () -> Unit,
    viewModel: DailyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ShiciTabScreen(selectedTab = BottomTab.DAILY, onSelectTab = onSelectTab) {
        val daily = state.daily
        if (daily == null) {
            if (!state.loading) EmptyHint("还没有可读的诗，去「浏览」看看或下载一个数据包")
            return@ShiciTabScreen
        }

        CenteredScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
            verticalGap = ShiciDimens.ContentGap,
        ) {
            Text(
                text = daily.lunarLine,
                style = ShiciText.Date,
                color = ShiciColors.Vermilion,
                textAlign = TextAlign.Center,
            )

            Text(
                text = daily.poem.title,
                modifier = Modifier.fillMaxWidth(),
                style = ShiciText.DailyTitle,
                color = ShiciColors.Ink,
                textAlign = TextAlign.Center,
            )

            Text(
                text = daily.poem.attribution,
                modifier = Modifier.fillMaxWidth(),
                style = ShiciText.Author,
                color = ShiciColors.InkSoft,
                textAlign = TextAlign.Center,
            )

            Text(
                text = daily.poem.content,
                modifier = Modifier.fillMaxWidth(),
                style = ShiciText.DailyBody,
                color = ShiciColors.Ink,
                textAlign = TextAlign.Center,
            )

            Seal()

            // 离线兜底选诗需要如实告知来源，否则用户会以为「今天的诗怎么不是精选」
            if (daily.isOfflineFallback) {
                Text(text = "离线精选", style = ShiciText.EntryMeta, color = ShiciColors.InkFaint)
            }

            // 「读全文」是当日那首诗的入口，「漫游」是在所选范围内随机看更多 ——
            // 两者是不同意图，故一个强调一个弱化，而不是两个一样的强调链接。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    ShiciDimens.DailyActionsGap,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentLink(text = "读全文", onClick = { onOpenPoem(daily.poem.poemId) })
                QuietLink(text = "漫游", onClick = onRoam)
                HeartIcon(
                    size = ShiciDimens.IconLarge,
                    color = if (state.isFavorite) ShiciColors.Vermilion else ShiciColors.Ink,
                    filled = state.isFavorite,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = viewModel::toggleFavorite,
                    ),
                )
            }
        }
    }
}
