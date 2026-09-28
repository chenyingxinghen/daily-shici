package com.example.daily_shici.ui.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.daily_shici.data.pack.PackPhase
import com.example.daily_shici.data.pack.PackProgress
import com.example.daily_shici.data.repository.PackRepository
import com.example.daily_shici.domain.model.ChineseDate
import com.example.daily_shici.domain.model.PackInfo
import com.example.daily_shici.ui.components.AccentLink
import com.example.daily_shici.ui.components.DetailTopBar
import com.example.daily_shici.ui.components.EmptyHint
import com.example.daily_shici.ui.components.EntryMeta
import com.example.daily_shici.ui.components.HairlineRule
import com.example.daily_shici.ui.components.QuietLink
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

data class PacksUiState(
    val loading: Boolean = true,
    val packs: List<PackInfo> = emptyList(),
    val offline: Boolean = false,
    val progress: PackProgress? = null,
    val message: String? = null,
    /** 等待确认下载的包。L2/L3 必须经过确认，见 [needsConfirmation]。 */
    val pending: PackInfo? = null,
)

@HiltViewModel
class PacksViewModel @Inject constructor(
    private val packRepository: PackRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PacksUiState())
    val state: StateFlow<PacksUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            packRepository.progress.collect { progress ->
                _state.value = _state.value.copy(
                    progress = progress,
                    message = if (progress?.phase == PackPhase.FAILED) progress.message else _state.value.message,
                )
                // 下载完成后刷新清单，让「已安装」状态立刻生效
                if (progress?.phase == PackPhase.DONE) refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val catalog = packRepository.catalog()
            _state.value = _state.value.copy(
                loading = false,
                packs = catalog.packs,
                offline = catalog.offline,
            )
        }
    }

    /**
     * 请求下载。**按 `tier` 分级确认**（`docs/01 §6.1`）：
     * L1（gzip < 3 MB）静默下载不打断用户；L2（3–20 MB）展示体积确认；
     * L3（> 20 MB）额外提示需要用 Wi-Fi。
     */
    fun requestInstall(pack: PackInfo) {
        if (pack.url.isBlank()) {
            _state.value = _state.value.copy(message = "该数据包缺少下载地址，服务端清单可能未就绪")
            return
        }
        if (needsConfirmation(pack)) {
            _state.value = _state.value.copy(pending = pack, message = null)
        } else {
            startInstall(pack)
        }
    }

    fun dismissPending() {
        _state.value = _state.value.copy(pending = null)
    }

    fun confirmPending() {
        _state.value.pending?.let(::startInstall)
        _state.value = _state.value.copy(pending = null)
    }

    private fun startInstall(pack: PackInfo) {
        viewModelScope.launch {
            _state.value = _state.value.copy(message = null)
            val result = packRepository.install(pack, pack.url, pack.sha256)
            _state.value = _state.value.copy(
                message = result.exceptionOrNull()?.message ?: "已安装 ${result.getOrDefault(0)} 首",
            )
            refresh()
        }
    }

    fun uninstall(pack: PackInfo) {
        viewModelScope.launch {
            packRepository.uninstall(pack.packId)
            refresh()
        }
    }

    fun cancel(packId: String) {
        viewModelScope.launch {
            packRepository.cancel(packId)
            refresh()
        }
    }
}

/** L2 / L3 需要用户确认；L0 内置与 L1 小包不需要。 */
fun needsConfirmation(pack: PackInfo): Boolean =
    pack.tier == "L2" || pack.tier == "L3"

/**
 * 数据包管理。设计稿没有这一页，沿用同一套语言：状态栏 → 顶栏 → 条目列表。
 *
 * 卸载包时会**保留收藏与历史**，对应内容变成「未下载」灰态，重装后自动恢复。
 */
@Composable
fun PacksScreen(
    onBack: () -> Unit,
    viewModel: PacksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

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
            ScreenTitle("数据包管理")
            VerticalGap(ShiciDimens.ActiveMarkGap)
            Text(
                text = if (state.offline) "当前离线，仅显示已安装的包" else "已下载的内容离线可读，未下载的只在联网时可见",
                style = ShiciText.EntryMeta,
                color = ShiciColors.InkFaint,
            )
            VerticalGap(ShiciDimens.ContentGap)

            if (state.packs.isEmpty() && !state.loading) {
                EmptyHint("暂时取不到数据包清单")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(ShiciDimens.BrowseContentGap),
                ) {
                    items(state.packs, key = { it.packId }) { pack ->
                        PackRow(
                            pack = pack,
                            progress = state.progress?.takeIf { it.packId == pack.packId },
                            onInstall = { viewModel.requestInstall(pack) },
                            onUninstall = { viewModel.uninstall(pack) },
                            onCancel = { viewModel.cancel(pack.packId) },
                        )
                    }
                    if (state.message != null) {
                        item(key = "message") {
                            Text(
                                text = state.message.orEmpty(),
                                style = ShiciText.EntryMeta,
                                color = ShiciColors.InkSoft,
                            )
                        }
                    }
                }
            }
        }
    }

    // L2/L3 的下载确认（docs/01 §6.1）：把体积与层级讲清楚，而不是直接开下。
    state.pending?.let { pack ->
        AlertDialog(
            onDismissRequest = viewModel::dismissPending,
            title = { Text(text = "下载「${pack.name}」？", color = ShiciColors.Ink) },
            text = {
                Text(
                    text = buildString {
                        append("${pack.poemCount} 首 · ")
                        append(ChineseDate.humanBytes(pack.bytesGzip))
                        append("\n层级：${tierLabel(pack.tier)}")
                        if (pack.tier == "L3") {
                            append("\n\n这是大包，建议在 Wi-Fi 下下载。")
                        }
                    },
                    color = ShiciColors.InkSoft,
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPending) {
                    Text(text = "开始下载", color = ShiciColors.Vermilion)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPending) {
                    Text(text = "取消", color = ShiciColors.InkFaint)
                }
            },
            containerColor = ShiciColors.Paper,
        )
    }
}

@Composable
private fun PackRow(
    pack: PackInfo,
    progress: PackProgress?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ShiciDimens.EntryGap)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = pack.name, style = ShiciText.EntryTitle, color = ShiciColors.Ink)
                if (pack.builtin) {
                    Text(text = "内置", style = ShiciText.EntryMeta, color = ShiciColors.InkFaint)
                }
            }
            if (pack.description.isNotBlank()) {
                Text(
                    text = pack.description,
                    modifier = Modifier.fillMaxWidth(),
                    style = ShiciText.EntryExcerpt,
                    color = ShiciColors.InkSoft,
                )
            }
            EntryMeta(
                listOfNotNull(
                    tierLabel(pack.tier),
                    "${pack.poemCount} 首",
                    ChineseDate.humanBytes(pack.bytesGzip).takeIf { pack.bytesGzip > 0 },
                    when {
                        progress != null && progress.phase != PackPhase.DONE &&
                            progress.phase != PackPhase.FAILED -> phaseText(progress)
                        pack.isInstalled -> "已安装"
                        pack.needsUpdate -> "可更新"
                        else -> null
                    },
                ).joinToString(" · ")
            )

            if (progress != null && progress.phase != PackPhase.DONE && progress.phase != PackPhase.FAILED) {
                VerticalGap(ShiciDimens.ActiveMarkGapTight)
                DownloadProgressBar(progress)
                VerticalGap(ShiciDimens.ActiveMarkGapTight)
                QuietLink(text = "取消下载", onClick = onCancel)
            }

            if (progress == null || progress.phase == PackPhase.DONE || progress.phase == PackPhase.FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(ShiciDimens.ActionLinksGap)) {
                    when {
                        pack.canUninstall -> QuietLink(text = "卸载", onClick = onUninstall)
                        pack.url.isNotBlank() -> AccentLink(
                            text = if (pack.needsUpdate) "更新" else "下载",
                            onClick = onInstall,
                        )
                        pack.builtin -> EntryMeta("随应用附带，不可卸载")
                    }
                }
            }
        }
        VerticalGap(ShiciDimens.BrowseContentGap)
        HairlineRule()
    }
}

/** 细长进度条：与朱丝栏同一厚度语言，不用 Material 的粗条。 */
@Composable
private fun DownloadProgressBar(progress: PackProgress) {
    val fraction = progress.fraction
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(ShiciColors.Rule),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(2.dp)
                .background(ShiciColors.Vermilion, RoundedCornerShape(1.dp)),
        )
    }
}

/**
 * 包层级的中文说明。`tier` 决定下载行为，必须让用户看得见 ——
 * 否则「为什么这个包要确认、那个不用」就没有解释。
 */
private fun tierLabel(tier: String): String = when (tier) {
    "L0" -> "内置"
    "L1" -> "小包"
    "L2" -> "中包"
    "L3" -> "大包"
    else -> tier
}

private fun phaseText(progress: PackProgress): String = when (progress.phase) {
    PackPhase.DOWNLOADING ->
        if (progress.totalBytes > 0) {
            "下载中 ${(progress.fraction * 100).toInt()}%"
        } else {
            "下载中 ${ChineseDate.humanBytes(progress.bytesRead)}"
        }
    PackPhase.VERIFYING -> "校验中"
    PackPhase.IMPORTING -> "入库中 ${progress.poemsImported}/${progress.expectedPoems}"
    PackPhase.DONE -> "完成"
    PackPhase.FAILED -> "失败"
}
