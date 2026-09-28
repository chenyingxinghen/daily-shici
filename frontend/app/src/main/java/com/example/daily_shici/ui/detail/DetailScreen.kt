package com.example.daily_shici.ui.detail

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.daily_shici.data.net.NetworkMonitor
import com.example.daily_shici.data.remote.AskEvent
import com.example.daily_shici.data.repository.LibraryRepository
import com.example.daily_shici.data.repository.PoemRepository
import com.example.daily_shici.domain.model.PoemDetail
import com.example.daily_shici.ui.components.AccentLink
import com.example.daily_shici.ui.components.BookmarkIcon
import com.example.daily_shici.ui.components.CenteredScrollColumn
import com.example.daily_shici.ui.components.DetailTopBar
import com.example.daily_shici.ui.components.SystemStatusBarInset
import com.example.daily_shici.ui.components.EmptyHint
import com.example.daily_shici.ui.components.HairlineRule
import com.example.daily_shici.ui.components.QuietLink
import com.example.daily_shici.ui.components.ShareIcon
import com.example.daily_shici.ui.nav.Routes
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val loading: Boolean = true,
    val poem: PoemDetail? = null,
    val isFavorite: Boolean = false,
    /** 能否提问（离线时不能 —— 提问必然失败，不如直接告诉用户）。 */
    val canAsk: Boolean = false,
    val ask: AskUiState = AskUiState(),
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val poemRepository: PoemRepository,
    private val libraryRepository: LibraryRepository,
    private val networkMonitor: NetworkMonitor,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val poemId: Long = savedStateHandle.get<String>(Routes.DETAIL_ARG_POEM_ID)
        ?.toLongOrNull()
        ?: savedStateHandle.get<Long>(Routes.DETAIL_ARG_POEM_ID)
        ?: 0L

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** 当前这轮流式问答的收集任务。重新提问前要取消，否则两段回答会交错着显示。 */
    private var askJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val loaded = poemRepository.detail(poemId)
            val favorite = libraryRepository.observeIsFavorite(poemId).first()
            val online = networkMonitor.isOnline()

            // 详情里没带回注疏时，再去注疏端点问一次 —— 详情走的是本地缓存优先，
            // 而注疏可能刚在服务端补上（或本地缓存过期），值得单独确认一次。
            var poem = loaded
            if (loaded != null && loaded.annotation == null && online) {
                poemRepository.annotation(poemId)?.let { poem = loaded.copy(annotation = it) }
            }

            _state.value = _state.value.copy(
                loading = false,
                poem = poem,
                isFavorite = favorite,
                canAsk = online,
            )
            // 打开详情即记入阅读历史。
            if (poem != null) libraryRepository.markRead(poemId)
        }
    }

    fun onQuestionChange(text: String) {
        _state.value = _state.value.copy(ask = _state.value.ask.copy(question = text))
    }

    /**
     * 提问并**流式渲染回答**。
     *
     * 先把上一轮的状态清掉（除问题文本外），再开流 —— 否则新回答会接在旧回答后面，
     * 看起来像是同一个回复的续写。
     *
     * 事件处理刻意分三类：`Sources` 提前把来源显示出来（用户知道系统在查什么）、
     * `Delta` 追加文本（**唯一的持续动作**）、`Failed` 落到 error 而**不清空已收到的文本** ——
     * 中途断流时用户至少能看到已经答出来的部分。
     */
    fun ask() {
        val question = _state.value.ask.question.trim()
        if (question.isEmpty() || !_state.value.canAsk) return

        askJob?.cancel()
        _state.value = _state.value.copy(
            ask = AskUiState(question = question, searching = true, streaming = false),
        )

        askJob = viewModelScope.launch {
            poemRepository.ask(poemId, question).collect { event ->
                val current = _state.value.ask
                _state.value = _state.value.copy(
                    ask = when (event) {
                        is AskEvent.Sources -> current.copy(
                            sources = event.items,
                            searching = false,
                            streaming = true,
                        )
                        is AskEvent.Delta -> current.copy(
                            answer = current.answer + event.text,
                            searching = false,
                            streaming = true,
                        )
                        is AskEvent.Done -> current.copy(
                            searching = false,
                            streaming = false,
                            searched = event.searched,
                        )
                        is AskEvent.Failed -> current.copy(
                            searching = false,
                            streaming = false,
                            error = event.message,
                        )
                        AskEvent.Ignored -> current
                    },
                )
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val nowFavorite = libraryRepository.toggleFavorite(poemId)
            _state.value = _state.value.copy(isFavorite = nowFavorite)
        }
    }

    /** 分享/复制的文本：标题 + 署名 + 正文，纯文本便于粘贴到任何地方。 */
    fun shareText(): String {
        val poem = _state.value.poem ?: return ""
        return buildString {
            appendLine(poem.title)
            appendLine(poem.attribution)
            appendLine()
            append(poem.content)
        }
    }
}

/**
 * 详情（设计稿 Screen 3）。
 *
 * 与每日一诗同为「无卡片」排版，差别在字号与行距：
 * 标题 26（30→26），正文 17/34（20/44）—— 因为详情页正文更长，需要更紧凑的行距。
 *
 * 注释 / 译文 / 赏析（`docs/06`）放在**操作链接之后**：它们是补充材料，
 * 而收藏/复制/分享是打开页面就要够得着的操作。把它们压在几百字赏析下面
 * 会让每次收藏都要滚一屏。
 */
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val poem = state.poem

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ShiciColors.Paper),
    ) {
        SystemStatusBarInset()

        // 顶栏**固定在顶部**，不进居中容器。
        // 早先把它当作 CenteredScrollColumn 的第一个子项，于是它跟着整块内容一起
        // 垂直居中 —— 短诗时返回/收藏/分享浮在屏幕中间，看起来就是「页面没顶格」。
        DetailTopBar(
            onBack = onBack,
            modifier = Modifier.padding(
                start = ShiciDimens.ContentPadding,
                end = ShiciDimens.ContentPadding,
                top = ShiciDimens.DetailContentPaddingVertical,
                bottom = ShiciDimens.DetailContentPaddingVertical,
            ),
        ) {
            // 两个图标**必须同尺寸**：24dp 书签配 22dp 分享（再给书签加 4dp top padding）
            // 会让它们的视觉重心差 6dp，一眼就能看出不齐平。
            // 已收藏时书签实心朱红，未收藏时空心墨色 —— 与设计稿一致。
            BookmarkIcon(
                size = ShiciDimens.IconLarge,
                color = if (state.isFavorite) ShiciColors.Vermilion else ShiciColors.Ink,
                filled = state.isFavorite,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = viewModel::toggleFavorite,
                ),
            )
            ShareIcon(
                size = ShiciDimens.IconLarge,
                color = ShiciColors.Ink,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { share(context, viewModel.shareText()) },
                ),
            )
        }

        CenteredScrollColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = ShiciDimens.ContentPadding,
                end = ShiciDimens.ContentPadding,
                top = 0.dp,
                bottom = ShiciDimens.DetailContentPaddingVertical,
            ),
            verticalGap = ShiciDimens.ContentGap,
        ) {
            if (poem == null) {
                if (!state.loading) EmptyHint("没能取到这首诗，可能尚未下载")
                return@CenteredScrollColumn
            }

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

            HairlineRule()

            Text(
                text = poem.content,
                modifier = Modifier.fillMaxWidth(),
                style = ShiciText.DetailBody,
                color = ShiciColors.Ink,
                textAlign = TextAlign.Center,
            )

            Text(
                text = noteOf(poem),
                modifier = Modifier.fillMaxWidth(),
                style = ShiciText.EntryMeta,
                color = ShiciColors.InkFaint,
                textAlign = TextAlign.Center,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    ShiciDimens.ActionLinksGap,
                    Alignment.CenterHorizontally,
                ),
            ) {
                if (state.isFavorite) {
                    // 已收藏 → 主链接变成「取消收藏」，但仍是同一位置的强调样式
                    AccentLink(text = "取消收藏", onClick = viewModel::toggleFavorite)
                } else {
                    AccentLink(text = "收藏", onClick = viewModel::toggleFavorite)
                }
                QuietLink(
                    text = "复制",
                    onClick = { clipboard.setText(AnnotatedString(viewModel.shareText())) },
                )
                QuietLink(
                    text = "分享",
                    onClick = { share(context, viewModel.shareText()) },
                )
            }

            // ---- 注疏（方式二，docs/06）----
            // 有就渲染；没有就给一句说明 + 引导到下面的「问一问」（方式一）。
            // **不允许**出现「有标题没内容」的区块，也**不允许**把「没有」说成「加载失败」：
            // 全量 83.4 万首里只有 7,388 首有注疏，没有是常态。
            poem.annotation?.let { annotation ->
                AnnotationSections(
                    annotation = annotation,
                    onOpenSource = { url -> openSource(context, url) },
                )
            } ?: NoAnnotationHint()

            // ---- 问一问（方式一，docs/06 §6）----
            AskSection(
                state = state.ask,
                enabled = state.canAsk,
                onQuestionChange = viewModel::onQuestionChange,
                onSubmit = viewModel::ask,
                onOpenSource = { url -> openSource(context, url) },
            )
        }
    }
}

/** 顶栏与底部链接共用同一条分享路径，避免两处各写一遍 Intent。 */
private fun share(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享这首诗"))
}

/**
 * 打开参考资料原页。
 *
 * 打不开（无浏览器、URL 非法）就静默忽略 —— 这是**次要功能**，
 * 为它弹一个错误对话框会打断用户读注释的主任务。
 */
private fun openSource(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** 底部说明行。设计稿是「收录于《李太白集》· 已离线可读」。 */
private fun noteOf(poem: PoemDetail): String {
    val collection = poem.collections.firstOrNull()?.let { "收录于《$it》" }
    val availability = if (poem.isDownloaded) "已离线可读" else "在线内容，未下载"
    return listOfNotNull(collection, availability).joinToString(" · ")
}
