package com.example.daily_shici.ui.roam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.daily_shici.data.net.NetworkMonitor
import com.example.daily_shici.data.repository.FacetsRepository
import com.example.daily_shici.data.repository.LibraryRepository
import com.example.daily_shici.data.repository.PoemRepository
import com.example.daily_shici.domain.model.AppliedFilters
import com.example.daily_shici.domain.model.FacetKey
import com.example.daily_shici.domain.model.FacetOption
import com.example.daily_shici.domain.model.Facets
import com.example.daily_shici.domain.model.PoemDetail
import com.example.daily_shici.ui.components.AccentLink
import com.example.daily_shici.ui.components.BackIcon
import com.example.daily_shici.ui.components.CenteredScrollColumn
import com.example.daily_shici.ui.components.FacetChooser
import com.example.daily_shici.ui.components.HairlineRule
import com.example.daily_shici.ui.components.HeartIcon
import com.example.daily_shici.ui.components.QuietLink
import com.example.daily_shici.ui.components.ScreenTitle
import com.example.daily_shici.ui.components.SectionLabel
import com.example.daily_shici.ui.components.Seal
import com.example.daily_shici.ui.components.SystemStatusBarInset
import com.example.daily_shici.ui.components.VerticalGap
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RoamUiState(
    val loading: Boolean = true,
    val poem: PoemDetail? = null,
    val isFavorite: Boolean = false,
    /** 取不到诗时的说明。与「真的没有」/「没下载」分开措辞，见 [RoamViewModel.next]。 */
    val message: String? = null,
)

@HiltViewModel
class RoamViewModel @Inject constructor(
    private val poemRepository: PoemRepository,
    private val facetsRepository: FacetsRepository,
    private val libraryRepository: LibraryRepository,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _facetKey = MutableStateFlow(FacetKey.ALL)
    val facetKey: StateFlow<FacetKey> = _facetKey.asStateFlow()

    private val _filters = MutableStateFlow(AppliedFilters())
    private val _facets = MutableStateFlow(Facets())
    val facets: StateFlow<Facets> = _facets.asStateFlow()

    private val _state = MutableStateFlow(RoamUiState())
    val state: StateFlow<RoamUiState> = _state.asStateFlow()

    val online: StateFlow<Boolean> = networkMonitor.online
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 本轮已看过的 id。只用于「换一首别又给同一首」，不持久化。 */
    private val seen = HashSet<Long>()

    init {
        viewModelScope.launch {
            _facets.value = facetsRepository.facets()
            next()
        }
    }

    /**
     * 在所选范围内随机取一首。
     *
     * 最多试 [MAX_ATTEMPTS] 次以避开已看过的 —— 范围很窄（比如某个只有 3 首的词牌）
     * 时必然重试到重复，此时**接受重复**而不是空转或报错。
     */
    fun next() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)

            var candidate: PoemDetail? = null
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                attempt++
                val poem = poemRepository.randomPoem(_filters.value) ?: break
                candidate = poem
                if (poem.poemId !in seen) break
            }

            if (candidate == null) {
                _state.value = RoamUiState(
                    loading = false,
                    message = if (online.value) "这个范围内还没有收录的诗"
                    else "这个范围内本地没有内容，联网后可漫游全量",
                )
                return@launch
            }

            seen += candidate.poemId
            // 漫游可以无限进行，seen 不能无限增长，否则几十首之后每次都要撞满重试
            if (seen.size > SEEN_MEMORY) seen.clear()

            val favorite = libraryRepository.observeIsFavorite(candidate.poemId).first()
            _state.value = RoamUiState(loading = false, poem = candidate, isFavorite = favorite)
        }
    }

    fun toggleFavorite() {
        val poemId = _state.value.poem?.poemId ?: return
        viewModelScope.launch {
            val nowFavorite = libraryRepository.toggleFavorite(poemId)
            _state.value = _state.value.copy(isFavorite = nowFavorite)
        }
    }

    /** 切维度即清空取值 —— 与浏览页同一规则（留着上一个维度的取值会让范围说不清）。 */
    fun selectFacetKey(key: FacetKey) {
        _facetKey.value = key
        _filters.value = AppliedFilters()
        seen.clear()
        next()
    }

    fun selectValue(option: FacetOption?) {
        _filters.value = when (_facetKey.value) {
            FacetKey.ALL -> AppliedFilters()
            FacetKey.DYNASTY -> AppliedFilters(dynasty = option)
            FacetKey.GENRE -> AppliedFilters(genre = option)
            FacetKey.CIPAI -> AppliedFilters(cipai = option)
            FacetKey.COLLECTION -> AppliedFilters(collection = option)
        }
        seen.clear()
        next()
    }

    fun valuesFor(key: FacetKey) =
        _facets.value.optionsFor(if (key == FacetKey.ALL) FacetKey.DYNASTY else key)

    fun currentValue(key: FacetKey): FacetOption? = key.current(_filters.value)

    companion object {
        /** 避开已看过诗的最大尝试次数。 */
        private const val MAX_ATTEMPTS = 5
        private const val SEEN_MEMORY = 200
    }
}

/**
 * 漫游 —— 在所选范围内随机阅览。
 *
 * 与「浏览」是互补的两种发现方式：浏览是**有序遍历**（按精选权重翻页），
 * 漫游是**随机采样**。随机采样对 83 万首的语料尤其重要 ——
 * 排序前 200 首之后几乎没有人会翻到，而好诗未必都在头部。
 *
 * 范围选择器与浏览页共用 [FacetChooser]，保证「在浏览里能选的范围，漫游里一样能选」。
 */
@Composable
fun RoamScreen(
    onBack: () -> Unit,
    onOpenPoem: (Long) -> Unit,
    viewModel: RoamViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val facetKey by viewModel.facetKey.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val poem = state.poem

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShiciColors.Paper),
    ) {
        SystemStatusBarInset()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ShiciDimens.ContentPadding,
                    end = ShiciDimens.ContentPadding,
                    top = ShiciDimens.DetailContentPaddingVertical,
                    bottom = ShiciDimens.ContentGap,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackIcon(
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onBack() },
                size = ShiciDimens.IconLarge,
                color = ShiciColors.Ink,
            )
            ScreenTitle("漫游")
            // 与返回键同宽的占位：标题才能真正居中，而不是被 SpaceBetween 推偏
            Box(modifier = Modifier.size(ShiciDimens.IconLarge))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ShiciDimens.ContentPadding),
        ) {
            SectionLabel("漫游范围")
            VerticalGap(ShiciDimens.EntryGap)
            FacetChooser(
                facetKey = facetKey,
                values = viewModel.valuesFor(facetKey),
                selected = viewModel.currentValue(facetKey),
                onSelectKey = viewModel::selectFacetKey,
                onSelectValue = viewModel::selectValue,
            )
        }

        VerticalGap(ShiciDimens.ContentGap)
        HairlineRule()

        CenteredScrollColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = ShiciDimens.ContentPadding,
                end = ShiciDimens.ContentPadding,
                top = ShiciDimens.ContentGap,
                bottom = ShiciDimens.ContentGap,
            ),
            verticalGap = ShiciDimens.ContentGap,
        ) {
            when {
                state.loading -> Text(
                    text = "寻诗中…",
                    modifier = Modifier.fillMaxWidth(),
                    style = ShiciText.EntryExcerpt,
                    color = ShiciColors.InkFaint,
                    textAlign = TextAlign.Center,
                )

                poem == null -> Text(
                    text = state.message ?: "这个范围内还没有可读的诗",
                    modifier = Modifier.fillMaxWidth(),
                    style = ShiciText.EntryExcerpt,
                    color = ShiciColors.InkFaint,
                    textAlign = TextAlign.Center,
                )

                else -> {
                    Text(
                        text = poem.title,
                        modifier = Modifier.fillMaxWidth(),
                        style = ShiciText.DetailTitle,
                        color = ShiciColors.Ink,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = poem.attribution,
                        modifier = Modifier.fillMaxWidth(),
                        style = ShiciText.Author,
                        color = ShiciColors.InkSoft,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = poem.content,
                        modifier = Modifier.fillMaxWidth(),
                        style = ShiciText.DetailBody,
                        color = ShiciColors.Ink,
                        textAlign = TextAlign.Center,
                    )
                    Seal()
                    Text(
                        text = if (poem.isDownloaded) "已离线可读" else "在线内容，未下载",
                        style = ShiciText.EntryMeta,
                        color = ShiciColors.InkFaint,
                    )
                }
            }
        }

        HairlineRule()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = ShiciDimens.ContentPadding,
                    end = ShiciDimens.ContentPadding,
                    top = ShiciDimens.ContentGap,
                    bottom = ShiciDimens.DetailContentPaddingVertical,
                ),
            horizontalArrangement = Arrangement.spacedBy(
                ShiciDimens.DailyActionsGap,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentLink(text = "换一首", onClick = viewModel::next)
            if (poem != null) {
                QuietLink(text = "读全文", onClick = { onOpenPoem(poem.poemId) })
            }
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

        if (!online) {
            Text(
                text = "当前离线，仅在已下载内容中漫游",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = ShiciDimens.DetailContentPaddingVertical),
                style = ShiciText.EntryMeta,
                color = ShiciColors.InkFaint,
                textAlign = TextAlign.Center,
            )
        } else {
            VerticalGap(ShiciDimens.DetailContentPaddingVertical)
        }
    }
}
