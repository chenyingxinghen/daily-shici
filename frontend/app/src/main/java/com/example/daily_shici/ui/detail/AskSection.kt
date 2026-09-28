package com.example.daily_shici.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.daily_shici.domain.model.AnnotationSource
import com.example.daily_shici.ui.components.AccentLink
import com.example.daily_shici.ui.components.HairlineRule
import com.example.daily_shici.ui.components.QuietLink
import com.example.daily_shici.ui.components.SectionLabel
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText

/** 一次问答在 UI 上的状态。 */
data class AskUiState(
    val question: String = "",
    val answer: String = "",
    /** **模型正在决定/执行检索**。这是工具调用模式下新增的一段等待 ——
     *  模型要先把「要不要搜、搜什么」想出来，用户这期间看不到字。 */
    val searching: Boolean = false,
    val sources: List<AnnotationSource> = emptyList(),
    val streaming: Boolean = false,
    /** 这轮**是否真的检索过资料**（模型可能选择凭原文作答）。 */
    val searched: Boolean = false,
    val error: String? = null,
) {
    val hasOutput: Boolean get() = answer.isNotBlank() || error != null
    val busy: Boolean get() = searching || streaming
}

/**
 * 「问一问」（方式一，`docs/06 §6`）。
 *
 * ## 为什么它必须在详情页，而不是另开一个聊天页
 *
 * 用户的疑问天然是**关于眼前这首诗**的。离开这首诗去一个空白聊天框里描述
 * 「就是刚才那首里有句什么什么」既费事又容易说错。问答的上下文就是这首诗，
 * 所以它属于详情页内部的折叠区，不需要独立的会话概念。
 *
 * ## 为什么要显式区分「正在查资料」与「正在作答」
 *
 * 检索约 2 s，之后模型才开始吐字。这段时间如果只显示一个空白的转圈，
 * 用户不知道系统在干什么，2 秒就会觉得卡。分两段文案（先「正在查资料…」
 * 再「正在作答…」）让等待有解释。
 *
 * ## 长回答的等待
 *
 * 本地 9B 模型实测约 8 tok/s（`docs/06 §6.2`），一段 200 字的回答要一分多钟。
 * 流式渲染让用户**看得见进展**，这是这个硬件下唯一能接受的交互方式 ——
 * 也正因为慢，回答区要实时增长而不是等全部说完再显示。
 */
@Composable
fun AskSection(
    state: AskUiState,
    enabled: Boolean,
    onQuestionChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ShiciDimens.EntryGap),
    ) {
        HairlineRule()
        SectionLabel("问一问")

        AskInput(
            value = state.question,
            enabled = enabled && !state.busy,
            onChange = onQuestionChange,
            onSubmit = onSubmit,
        )

        if (!enabled) {
            Hint("联网后可以向 AI 提问")
        } else if (state.searching) {
            // 工具调用模式下这里对应「模型正在决定要不要检索、检索什么」，
            // 比原来的「正在查资料」更准确 —— 检索可能还没发出去。
            Hint("正在思考要不要查资料…")
        } else if (state.streaming && state.answer.isBlank()) {
            Hint("正在查资料…")
        }

        state.error?.let { message ->
            Text(
                text = message,
                modifier = Modifier.fillMaxWidth(),
                style = ShiciText.EntryMeta,
                color = ShiciColors.Vermilion,
                textAlign = TextAlign.Start,
            )
        }

        if (state.answer.isNotBlank()) {
            Text(
                text = state.answer,
                modifier = Modifier.fillMaxWidth(),
                style = ShiciText.AnnotationBody,
                color = ShiciColors.Ink,
                textAlign = TextAlign.Start,
            )
            // 回答是否查证过，必须如实告知 —— 模型有权选择凭原文作答，
            // 但用户得知道这条答案是哪一种。混为一谈会让人误以为每条都查过。
            if (!state.streaming) {
                Hint(
                    if (state.searched) "已检索 ${state.sources.size} 条资料后作答"
                    else "未检索，依据原文与已有注疏作答"
                )
            }
        }

        if (state.sources.isNotEmpty()) {
            SourcesInline(state.sources, onOpenSource)
        }
    }
}

/**
 * 输入框：下划线式，与全局输入风格一致（`RuleStrong` 是设计稿里「输入域下划线」的令牌）。
 * 用 [BasicTextField] 而不是 Material 的 `OutlinedTextField` —— 后者自带一整套
 * Material 容器与浮动标签，和这个全无卡片、只有细线的排版体系格格不入。
 */
@Composable
private fun AskInput(
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                // 下划线颜色跟着焦点走，给一个「这里可以输入」的即时反馈 ——
                // 这个排版体系里没有边框和底色，焦点态只剩颜色这一种表达手段。
                .onFocusChanged { focused = it.isFocused },
            singleLine = true,
            textStyle = ShiciText.Placeholder.copy(color = ShiciColors.Ink),
            cursorBrush = SolidColor(ShiciColors.Vermilion),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = "问一个不懂的词或句…",
                        style = ShiciText.Placeholder,
                        color = ShiciColors.InkFaint,
                    )
                }
                inner()
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ShiciDimens.Hairline)
                .background(if (focused) ShiciColors.Vermilion else ShiciColors.RuleStrong),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (enabled && value.isNotBlank()) {
                AccentLink(text = "提问", onClick = onSubmit)
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = ShiciText.EntryMeta,
        color = ShiciColors.InkFaint,
        textAlign = TextAlign.Start,
    )
}

/** 回答用过哪几页。放在回答**之后** —— 它是对「这句话凭什么这么说」的交代。 */
@Composable
private fun SourcesInline(sources: List<AnnotationSource>, onOpenSource: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ShiciDimens.EntryGap),
    ) {
        SectionLabel("参考")
        usableSources(sources).take(4).forEach { source ->
            QuietLink(
                text = source.title.ifBlank { source.url },
                onClick = { onOpenSource(source.url) },
            )
        }
    }
}
