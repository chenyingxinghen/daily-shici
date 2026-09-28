package com.example.daily_shici.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.daily_shici.data.net.NetworkMonitor
import com.example.daily_shici.data.prefs.SettingsRepository
import com.example.daily_shici.data.repository.SearchRepository
import com.example.daily_shici.domain.model.SearchHit
import com.example.daily_shici.domain.model.SearchSource
import com.example.daily_shici.ui.components.AccentLink
import com.example.daily_shici.ui.components.BottomTab
import com.example.daily_shici.ui.components.EmptyHint
import com.example.daily_shici.ui.components.EntryMeta
import com.example.daily_shici.ui.components.HairlineRule
import com.example.daily_shici.ui.components.HighlightedText
import com.example.daily_shici.ui.components.MarkedText
import com.example.daily_shici.ui.components.SearchIcon
import com.example.daily_shici.ui.components.SectionLabel
import com.example.daily_shici.ui.components.ShiciTabScreen
import com.example.daily_shici.ui.components.VerticalGap
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val source: SearchSource = SearchSource.LOCAL_ONLY,
    val notDownloadedCount: Int = 0,
    val recent: List<String> = emptyList(),
    val searching: Boolean = false,
    /**
     * 在线搜索**尝试过且失败**。
     *
     * ⚠️ 必须与 [source] 分开：早先只看 `source == LOCAL_ONLY` 就提示「当前离线」，
     * 于是服务端 5xx 与断网显示同一句话 —— 用户会去检查自己的网络，
     * 而真实原因在服务端。这两件事的行动指引完全不同。
     */
    val remoteFailed: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val settings: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** 供 UI 区分「断网」与「联网但请求失败」。 */
    val online: StateFlow<Boolean> = networkMonitor.online
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * 搜索的驱动流与 UI 状态**必须分开**：如果把 [StateFlow] 既当输入又当输出，
     * `collectLatest` 里的写回会立刻重新触发自己，形成自激循环。
     */
    private val queryStream = MutableStateFlow("")

    init {
        viewModelScope.launch {
            settings.recentQueries.collect { recent ->
                _state.value = _state.value.copy(recent = recent)
            }
        }
        viewModelScope.launch {
            queryStream.collectLatest { query ->
                if (query.isBlank()) {
                    _state.value = _state.value.copy(hits = emptyList(), searching = false)
                    return@collectLatest
                }

                // 第一级：本地 LIKE 立即出结果（10–50 ms），用户键入即有反馈
                val local = searchRepository.searchLocal(query)
                _state.value = _state.value.copy(
                    hits = local.hits,
                    source = SearchSource.LOCAL_ONLY,
                    notDownloadedCount = 0,
                    searching = true,
                )

                // 第二级：防抖后请求服务端全量，用完整结果替换（不合并两套打分体系）
                delay(SearchRepository.DEBOUNCE_MILLIS)
                runRemote(query)
            }
        }
    }

    private suspend fun runRemote(query: String) {
        // 断网就不必发请求：省一次必然超时/快速失败的连接，
        // 也让 UI 能如实说「当前离线」而不是「在线搜索未成功」。
        if (!networkMonitor.isOnline()) {
            _state.value = _state.value.copy(searching = false, remoteFailed = false)
            return
        }
        val remote = searchRepository.searchRemote(query)
        if (remote == null) {
            // 在线那一路没拿到结果：保留已显示的本地结果，并标记失败原因由 UI 区分
            _state.value = _state.value.copy(searching = false, remoteFailed = true)
            return
        }
        _state.value = _state.value.copy(
            hits = remote.hits,
            source = remote.source,
            notDownloadedCount = remote.notDownloadedCount,
            searching = false,
            remoteFailed = false,
        )
    }

    /** 在线搜索失败后的手动重试。不重跑本地（本地结果已经在屏幕上）。 */
    fun retry() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, remoteFailed = false)
            runRemote(query)
        }
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        queryStream.value = query
    }

    /** 点击热门词或历史词：当作提交处理，并落一次历史。 */
    fun submit(query: String = _state.value.query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        _state.value = _state.value.copy(query = trimmed)
        queryStream.value = trimmed
        viewModelScope.launch { settings.addRecentQuery(trimmed) }
    }

    fun clearQuery() {
        _state.value = _state.value.copy(query = "", hits = emptyList())
        queryStream.value = ""
    }

    fun clearHistory() {
        viewModelScope.launch { settings.clearRecentQueries() }
    }

    fun removeHistory(query: String) {
        viewModelScope.launch { settings.removeRecentQuery(query) }
    }

    fun historyFor(): List<String> = _state.value.recent

    companion object {
        /** 热门搜索：设计稿写死的 5 个词。 */
        val HOT_QUERIES = listOf("李白", "明月", "思乡", "边塞", "爱情")
    }
}

/**
 * 搜索（设计稿 Screen 4）。
 *
 * **有网时结果来自服务端全量**，未下载的命中会标注出来而不是被过滤掉 ——
 * 搜索是发现功能，完整性就是它的全部意义。
 */
@Composable
fun SearchScreen(
    onSelectTab: (BottomTab) -> Unit,
    onOpenPoem: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val interaction = remember { MutableInteractionSource() }

    ShiciTabScreen(selectedTab = BottomTab.SEARCH, onSelectTab = onSelectTab) {
        // 搜索框固定在顶部，其余内容滚动 —— 滚动时把输入框滚走是最招骂的交互。
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SearchIcon(size = ShiciDimens.IconSmall, color = ShiciColors.InkSoft)
                BasicTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = ShiciText.Placeholder.copy(color = ShiciColors.Ink),
                    cursorBrush = SolidColor(ShiciColors.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
                    decorationBox = { innerTextField ->
                        if (state.query.isEmpty()) {
                            Text(
                                text = "搜索诗词、作者、名句",
                                style = ShiciText.Placeholder,
                                color = ShiciColors.InkFaint,
                            )
                        }
                        innerTextField()
                    },
                )
            }
            VerticalGap(10.dp)
            HairlineRule(color = ShiciColors.RuleStrong)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(ShiciDimens.ContentGap),
            contentPadding = PaddingValues(
                top = ShiciDimens.ContentGap,
                bottom = ShiciDimens.ContentGap,
            ),
        ) {
            item(key = "hot-label") { SectionLabel("热门搜索") }
            item(key = "hot-row") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ShiciDimens.InlineRowGapLoose),
                ) {
                    SearchViewModel.HOT_QUERIES.forEach { hot ->
                        val active = hot == state.query
                        MarkedText(
                            text = hot,
                            color = if (active) ShiciColors.Vermilion else ShiciColors.InkSoft,
                            style = if (active) ShiciText.FilterActive else ShiciText.Filter,
                            underlined = active,
                            onClick = { viewModel.submit(hot) },
                        )
                    }
                }
            }

            if (state.recent.isNotEmpty()) {
                item(key = "history-label") { SectionLabel("搜索历史") }
                item(key = "history-row") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ShiciDimens.InlineRowGapLoose),
                    ) {
                        state.recent.take(5).forEach { item ->
                            MarkedText(
                                text = item,
                                color = ShiciColors.InkSoft,
                                style = ShiciText.Filter,
                                onClick = { viewModel.submit(item) },
                            )
                        }
                    }
                }
            }

            if (state.query.isNotBlank()) {
                item(key = "results-label") {
                    Text(
                        text = "搜索结果",
                        style = ShiciText.SectionTitle,
                        color = ShiciColors.Ink,
                    )
                }

                if (state.hits.isEmpty()) {
                    item(key = "no-result") {
                        EmptyHint(
                            when {
                                state.searching -> "搜索中…"
                                state.source == SearchSource.LOCAL_ONLY ->
                                    "仅在已下载内容中搜索，未找到匹配"
                                else -> "没有找到匹配的诗词"
                            }
                        )
                    }
                } else {
                    // 必须明示能力边界：搜不到不是 bug，是范围限制。
                    // 断网与「联网但请求失败」分开措辞 —— 前者用户无能为力，后者可以重试。
                    if (state.source == SearchSource.LOCAL_ONLY) {
                        item(key = "scope-hint") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(ShiciDimens.InlineRowGap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (online) "在线搜索未成功，以下是已下载内容中的结果"
                                    else "当前离线，仅在已下载内容中搜索",
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = ShiciText.EntryMeta,
                                    color = ShiciColors.InkFaint,
                                )
                                if (online && state.remoteFailed) {
                                    AccentLink(text = "重试", onClick = viewModel::retry)
                                }
                            }
                        }
                    } else if (state.searching) {
                        item(key = "searching-hint") {
                            Text(
                                text = "正在搜索全量…",
                                style = ShiciText.EntryMeta,
                                color = ShiciColors.InkFaint,
                            )
                        }
                    } else if (state.notDownloadedCount > 0) {
                        item(key = "not-downloaded-hint") {
                            Text(
                                text = "其中 ${state.notDownloadedCount} 首尚未下载，可在「我的 · 数据包管理」获取",
                                style = ShiciText.EntryMeta,
                                color = ShiciColors.InkFaint,
                            )
                        }
                    }

                    items(
                        count = state.hits.size,
                        key = { index -> state.hits[index].poem.poemId },
                    ) { index ->
                        val hit = state.hits[index]
                        SearchResultRow(hit = hit, onClick = { onOpenPoem(hit.poem.poemId) })
                    }
                }
            }
        }
    }
}

/**
 * 结果行：标题 → 带高亮的 `snippet` → 署名。
 *
 * ⚠️ 展示的是 **`snippet`（命中句及前后各一句）而不是 `excerpt`（正文首行）**。
 * 契约（`docs/02 §2.3`）专门返回 `snippet` 就是为了让用户一眼看到命中上下文 ——
 * 用户搜「落霞与孤鹜齐飞」时，首行往往根本不含这句。
 */
@Composable
private fun SearchResultRow(hit: SearchHit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = hit.poem.title, style = ShiciText.EntryTitle, color = ShiciColors.Ink)
            // 命中标题时打标，解释「为什么这条排前面」（粗排序是标题优先）
            if (hit.isTitleHit) {
                Text(text = "命中标题", style = ShiciText.EntryMeta, color = ShiciColors.Vermilion)
            }
        }

        HighlightedText(
            text = hit.snippet,
            ranges = hit.highlights,
            style = ShiciText.EntryExcerpt,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EntryMeta("${hit.poem.dynasty} · ${hit.poem.author}")
            if (!hit.poem.isDownloaded) {
                Text(text = "未下载", style = ShiciText.EntryMeta, color = ShiciColors.Vermilion)
            }
        }

        VerticalGap(ShiciDimens.EntryGap)
        HairlineRule()
    }
}
