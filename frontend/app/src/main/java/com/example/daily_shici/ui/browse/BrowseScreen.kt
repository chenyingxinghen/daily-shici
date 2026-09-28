package com.example.daily_shici.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.daily_shici.data.net.NetworkMonitor
import com.example.daily_shici.data.repository.FacetsRepository
import com.example.daily_shici.data.repository.PoemRepository
import com.example.daily_shici.domain.model.AppliedFilters
import com.example.daily_shici.domain.model.BrowseSource
import com.example.daily_shici.domain.model.FacetKey
import com.example.daily_shici.domain.model.FacetOption
import com.example.daily_shici.domain.model.Facets
import com.example.daily_shici.domain.model.ListResult
import com.example.daily_shici.domain.model.PoemSummary
import com.example.daily_shici.ui.components.AccentLink
import com.example.daily_shici.ui.components.BottomTab
import com.example.daily_shici.ui.components.EmptyHint
import com.example.daily_shici.ui.components.EntryMeta
import com.example.daily_shici.ui.components.FacetChooser
import com.example.daily_shici.ui.components.HairlineRule
import com.example.daily_shici.ui.components.MarkedText
import com.example.daily_shici.ui.components.ScreenTitle
import com.example.daily_shici.ui.components.ShiciTabScreen
import com.example.daily_shici.ui.components.VerticalGap
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val poemRepository: PoemRepository,
    private val facetsRepository: FacetsRepository,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _facetKey = MutableStateFlow(FacetKey.ALL)
    val facetKey: StateFlow<FacetKey> = _facetKey.asStateFlow()

    private val _filters = MutableStateFlow(AppliedFilters())
    val filters: StateFlow<AppliedFilters> = _filters.asStateFlow()

    private val _facets = MutableStateFlow(Facets())
    val facets: StateFlow<Facets> = _facets.asStateFlow()

    private val _empty = MutableStateFlow<ListResult?>(null)
    val empty: StateFlow<ListResult?> = _empty.asStateFlow()

    /**
     * 数据来源。默认**在线**（浏览页的意义就是发现全量内容），
     * 断网时落到已下载 —— 但用户仍可手动切回已下载，因为本地列表更快也更可控。
     */
    private val _source = MutableStateFlow(BrowseSource.ONLINE)
    val source: StateFlow<BrowseSource> = _source.asStateFlow()

    /**
     * 在线源首帧刷新失败时置位。说明「默认在线但连不上服务端」——
     * 此时不展示一片空白的报错，而是**回落到已下载**并在顶部给一条可重试的横幅。
     *
     * 这解决的是真机最常见的「在家能跑、出门连不上自家 DDNS 服务端」：
     * 服务端是公网可达的 FastAPI，但手机网络（移动数据 / 另一 WiFi）未必能路由到它，
     * `NetworkMonitor.online` 判断的是「有没有经校验的互联网」，不等于「到这个特定主机通」。
     */
    private val _onlineFailed = MutableStateFlow(false)
    val onlineFailed: StateFlow<Boolean> = _onlineFailed.asStateFlow()

    /**
     * 在线失败时的真实异常（类名 + message）。拿来直接显示在横幅上，
     * 省得用户去翻 logcat 才能告诉我到底是 `UnknownHostException` 还是 `SSLHandshakeException`。
     */
    private val _lastOnlineError = MutableStateFlow<Throwable?>(null)
    val lastOnlineError: StateFlow<Throwable?> = _lastOnlineError.asStateFlow()

    val online: StateFlow<Boolean> = networkMonitor.online
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 服务端全量条数，来自 `/facets`。取不到就是 0，此时头部的「全量 N 首」整段不显示。 */
    private val _remoteTotal = MutableStateFlow(0)
    val remoteTotal: StateFlow<Int> = _remoteTotal.asStateFlow()

    val downloadedCount: StateFlow<Int> = poemRepository.observeDownloadedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 筛选或来源变化 → 重建 PagingSource。`cachedIn` 保证旋转屏幕不重新查库。
     *
     * 两条路的 key 类型不同（本地 offset 是 Int，服务端游标是 String），
     * 所以是 `if/else` 两个 Pager 而不是一个 Pager 里二选一 ——
     * Paging 的 Key 类型必须在单个 Pager 内一致。
     */
    val pages: Flow<PagingData<PoemSummary>> = combine(_source, _filters) { source, filters ->
        source to filters
    }.flatMapLatest { (source, filters) ->
        val config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)
        if (source == BrowseSource.ONLINE) {
            Pager(config) { poemRepository.remotePagingSource(filters) }.flow
        } else {
            Pager(config) { poemRepository.localPagingSource(filters) }.flow
        }
    }.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            _facets.value = facetsRepository.facets()
            _remoteTotal.value = _facets.value.total
            // 启动时就断网的话，在线源每一页都会报错，不如直接落到已下载。
            if (!networkMonitor.isOnline()) _source.value = BrowseSource.LOCAL
        }
    }

    fun selectSource(source: BrowseSource) {
        if (source == BrowseSource.ONLINE && !online.value) return
        if (_source.value == source) return
        _source.value = source
        _empty.value = null
        // 手动切源即视为用户已重新决策，清掉「在线失败」横幅状态
        _onlineFailed.value = false
        _lastOnlineError.value = null
    }

    /**
     * 在线源首帧刷新抛异常时由屏幕调用。只处理「当前确实在在线源」的情况，
     * 避免把本地源的报错也误判成在线失败。
     */
    fun onOnlineFailed(error: Throwable?) {
        if (_source.value != BrowseSource.ONLINE) return
        _onlineFailed.value = true
        _lastOnlineError.value = error
        _source.value = BrowseSource.LOCAL
    }

    /** 横幅上的「重试在线」：清掉失败态再切回在线源，让 Paging 重新拉第一页。 */
    fun retryOnline() {
        if (!online.value) return
        _onlineFailed.value = false
        _lastOnlineError.value = null
        _source.value = BrowseSource.ONLINE
    }

    fun selectFacetKey(key: FacetKey) {
        _facetKey.value = key
        _empty.value = null
        // 切维度即清空取值：留着上一个维度的筛选会让用户看到「朝代=唐」却停在「体裁」页签。
        _filters.value = AppliedFilters()
    }

    /**
     * 选中某个取值。传 [FacetOption] 而不是字符串 ——
     * 在线查询要它的 `key`（`tang`），本地查询要它的 `label`（`唐`），
     * 只传字符串必然有一边查不到东西（见 [FacetOption] 注释）。
     */
    fun selectValue(option: FacetOption?) {
        _empty.value = null
        _filters.value = when (_facetKey.value) {
            FacetKey.ALL -> AppliedFilters()
            FacetKey.DYNASTY -> AppliedFilters(dynasty = option)
            FacetKey.GENRE -> AppliedFilters(genre = option)
            FacetKey.CIPAI -> AppliedFilters(cipai = option)
            FacetKey.COLLECTION -> AppliedFilters(collection = option)
        }
    }

    /** 第二行展示哪个维度的取值：停在「全部」时展示朝代，与设计稿一致。 */
    fun valuesFor(key: FacetKey) =
        _facets.value.optionsFor(if (key == FacetKey.ALL) FacetKey.DYNASTY else key)

    fun currentValue(key: FacetKey): FacetOption? = key.current(_filters.value)

    /**
     * 首帧加载完成为 0 条时判定三态，决定提示「没有」还是「未下载」。
     *
     * 在线源不需要这套判定：服务端结果就是全量，0 条就是「这个条件下没有收录」。
     */
    fun onFirstPageLoaded(loadedCount: Int) {
        if (loadedCount > 0) {
            _empty.value = null
            return
        }
        if (_source.value == BrowseSource.ONLINE) {
            _empty.value = ListResult.Empty(_filters.value)
            return
        }
        viewModelScope.launch { _empty.value = poemRepository.describeEmpty(_filters.value) }
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}

/**
 * 浏览（设计稿 Screen 2）。
 *
 * 两层筛选：第一行选**维度**（全部 / 朝代 / 体裁 / 词牌 / 合集），第二行是该维度下的取值。
 * 设计稿把「全部」和「唐」同时画成选中态，这是两张状态叠在一起的产物；
 * 实际交互只能有一个当前取值，这里按「维度 → 取值」两级来实现。
 *
 * 顶部另有**数据来源切换**：在线全量（服务端 83 万首）与已下载（本地包）。
 * 两者差三个数量级，合并展示或静默选路都会让人以为「筛选坏了」。
 */
@Composable
fun BrowseScreen(
    onSelectTab: (BottomTab) -> Unit,
    onOpenPoem: (Long) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val facetKey by viewModel.facetKey.collectAsStateWithLifecycle()
    val empty by viewModel.empty.collectAsStateWithLifecycle()
    val downloadedCount by viewModel.downloadedCount.collectAsStateWithLifecycle()
    val remoteTotal by viewModel.remoteTotal.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val onlineFailed by viewModel.onlineFailed.collectAsStateWithLifecycle()
    val lastOnlineError by viewModel.lastOnlineError.collectAsStateWithLifecycle()

    val items = viewModel.pages.collectAsLazyPagingItems()
    val listState = rememberLazyListState()

    // Paging 的加载结果只在这里能拿到，用 snapshotFlow 观察首帧是否为空。
    LaunchedEffect(items) {
        snapshotFlow { items.itemCount to items.loadState.refresh }
            .collect { (count, refresh) ->
                if (refresh is LoadState.Error) {
                    // 在线源连不上 → 回落到已下载，横幅提示可重试（不阻断浏览）。
                    // 把真实异常带上去，横幅上直接显示原因，免得去翻 logcat。
                    viewModel.onOnlineFailed(refresh.error)
                    return@collect
                }
                if (refresh is LoadState.NotLoading && count == 0) {
                    viewModel.onFirstPageLoaded(0)
                } else if (count > 0) {
                    viewModel.onFirstPageLoaded(count)
                }
            }
    }

    ShiciTabScreen(selectedTab = BottomTab.BROWSE, onSelectTab = onSelectTab) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(ShiciDimens.BrowseContentGap),
            contentPadding = PaddingValues(bottom = ShiciDimens.ContentGap),
        ) {
            item(key = "header") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        ScreenTitle("浏览")
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(ShiciDimens.InlineRowGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BrowseSource.entries.forEach { option ->
                                val active = option == source
                                // 断网时「在线全量」置灰但仍要看得见 ——
                                // 藏起来用户会以为这个功能不存在。
                                val enabled = option != BrowseSource.ONLINE || online
                                MarkedText(
                                    text = option.label,
                                    color = when {
                                        !enabled -> ShiciColors.InkFaint
                                        active -> ShiciColors.Vermilion
                                        else -> ShiciColors.InkSoft
                                    },
                                    style = if (active) ShiciText.FilterActive else ShiciText.Filter,
                                    underlined = active,
                                    onClick = if (enabled) ({ viewModel.selectSource(option) }) else null,
                                )
                            }
                        }
                    }
                    VerticalGap(ShiciDimens.EntryGap)
                    Text(
                        text = when {
                            source == BrowseSource.ONLINE && remoteTotal > 0 ->
                                "全量 ${formatCount(remoteTotal)} 首 · 已下载 ${formatCount(downloadedCount)} 首"
                            source == BrowseSource.ONLINE -> "已下载 ${formatCount(downloadedCount)} 首"
                            else -> "已下载 ${formatCount(downloadedCount)} 首"
                        },
                        style = ShiciText.EntryMeta,
                        color = ShiciColors.InkFaint,
                    )
                }
            }

            stickyHeader(key = "filters") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ShiciColors.Paper),
                ) {
                    FacetChooser(
                        facetKey = facetKey,
                        values = viewModel.valuesFor(facetKey),
                        selected = viewModel.currentValue(facetKey),
                        onSelectKey = viewModel::selectFacetKey,
                        onSelectValue = viewModel::selectValue,
                    )
                    VerticalGap(ShiciDimens.BrowseContentGap)
                }
            }

            // 在线源首帧失败：已自动回落到「已下载」，这里给一条可重试的横幅，而不是一片空白。
            // 真机最常见的成因是手机网络连不到服务端（服务端本身在线且证书有效），
            // 但也可能是一个客户端 bug —— 横幅上把真实异常打出来，便于定位。
            if (onlineFailed && source == BrowseSource.LOCAL) {
                item(key = "online_failed") {
                    OnlineFailedBanner(
                        downloadedCount = downloadedCount,
                        online = online,
                        error = lastOnlineError,
                        onRetry = viewModel::retryOnline,
                    )
                }
            } else if (items.loadState.refresh is LoadState.Error) {
                // 非在线源（本地库）报错时的兜底重试入口
                item(key = "error") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(ShiciDimens.EntryGap),
                    ) {
                        Text(
                            text = "加载失败" + ((items.loadState.refresh as? LoadState.Error)?.error
                                ?.let { "：${it::class.simpleName}" } ?: ""),
                            style = ShiciText.EntryExcerpt,
                            color = ShiciColors.InkFaint,
                            textAlign = TextAlign.Center,
                        )
                        AccentLink(text = "重试", onClick = { items.retry() })
                    }
                }
            } else if (items.itemCount == 0 && empty != null) {
                item(key = "empty") {
                    EmptyHint(
                        when (val state = empty) {
                            is ListResult.NotDownloaded ->
                                if (state.packIds.isEmpty()) "本地还没有内容，去「我的 · 数据包管理」下载"
                                else "这部分内容还没下载，可在「我的 · 数据包管理」获取"
                            is ListResult.Empty -> "这个条件下还没有收录的诗"
                            else -> "没有更多了"
                        }
                    )
                }
            }

            items(
                count = items.itemCount,
                key = { index -> items.peek(index)?.poemId ?: index },
                contentType = { "poem" },
            ) { index ->
                val poem = items[index]
                if (poem != null) {
                    BrowseEntry(poem = poem, onClick = { onOpenPoem(poem.poemId) })
                }
            }
        }
    }
}

/** 单条列表项：标题 → 摘要 → 署名，下方一条朱丝栏。 */
@Composable
private fun BrowseEntry(poem: PoemSummary, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ShiciDimens.EntryGap)) {
            Text(text = poem.title, style = ShiciText.EntryTitle, color = ShiciColors.Ink)
            Text(
                text = poem.excerpt,
                modifier = Modifier.fillMaxWidth(),
                style = ShiciText.EntryExcerpt,
                color = ShiciColors.InkSoft,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EntryMeta("${poem.dynasty} · ${poem.author}")
                // 在线浏览必然混着本地没有的诗，不标注的话点进去「没正文」会显得像 bug
                if (!poem.isDownloaded) {
                    Text(
                        text = "未下载",
                        style = ShiciText.EntryMeta,
                        color = ShiciColors.Vermilion,
                    )
                }
            }
        }
        VerticalGap(ShiciDimens.BrowseContentGap)
        HairlineRule()
    }
}

/** 834817 → 834,817。四位数以上的条数不分组几乎读不出来。 */
private fun formatCount(value: Int): String = "%,d".format(value)

/**
 * 在线源首帧失败后的顶部横幅。此时已自动回落到「已下载」，
 * 所以这里不挡内容，只告知原因并给一个回到在线的入口。
 *
 * [error] 是真实异常（类名 + message），直接打出来 —— 定位真机问题比翻 logcat 快得多：
 * `UnknownHostException`=DNS 解析不到；`SSLHandshakeException`=证书/TLS；
 * `SocketTimeoutException`=连得到但太慢/被墙；`HttpException`=服务端返回非 2xx。
 */
@Composable
private fun OnlineFailedBanner(
    downloadedCount: Int,
    online: Boolean,
    error: Throwable?,
    onRetry: () -> Unit,
) {
    val reason = error?.let { "${it::class.simpleName}${it.message?.let { m -> "：$m" } ?: ""}" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ShiciDimens.EntryGap),
    ) {
        Text(
            text = "在线加载失败，已显示已下载的 ${formatCount(downloadedCount)} 首",
            style = ShiciText.EntryExcerpt,
            color = ShiciColors.InkSoft,
        )
        if (reason != null) {
            Text(
                text = "原因：$reason",
                style = ShiciText.EntryMeta,
                color = ShiciColors.InkFaint,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ShiciDimens.InlineRowGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentLink(text = "重试在线", onClick = onRetry)
            if (!online) {
                Text(
                    text = "（当前网络不可用）",
                    style = ShiciText.EntryMeta,
                    color = ShiciColors.InkFaint,
                )
            }
        }
    }
}
