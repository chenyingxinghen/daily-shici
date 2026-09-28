package com.example.daily_shici.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.example.daily_shici.domain.model.Annotation
import com.example.daily_shici.domain.model.AnnotationSource
import com.example.daily_shici.ui.components.HairlineRule
import com.example.daily_shici.ui.components.QuietLink
import com.example.daily_shici.ui.components.SectionLabel
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText

/**
 * 注疏区块（`docs/06`）—— **从网络导入的真人编辑内容**，不是模型生成的。
 *
 * ## 为什么这几块**左对齐**，而诗身居中
 *
 * 诗身居中是这个 App 的立轴式排版（原因见 `CenteredScrollColumn` 的注释）。
 * 但注疏是**散文**：左对齐有明确的起行线，长段落读起来才不费劲；
 * 居中的长段落每行起头位置都在变，眼睛要一直找。
 *
 * 这个例外不是疏漏，而是「诗」与「注」在版式上本来就该分层 ——
 * 与字体上「诗用衬线、注疏用无衬线」的分工是同一件事的两种表现。
 *
 * ## 空区块绝不渲染
 *
 * 各字段独立可空（上游可能只给了译文没给赏析），**没有内容就整块不出现**，
 * 包括它的标题。渲染一个空的「赏析」标题比不渲染更糟：用户会以为加载失败了。
 * 这是 `docs/02 §6.3` 写死的硬要求。区块列表由 [Annotation.sections] 给出，
 * 这里不写一串 `if` —— 加字段时只改那一处。
 */
@Composable
fun AnnotationSections(
    annotation: Annotation,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ShiciDimens.ContentGap),
    ) {
        HairlineRule()

        annotation.sections.forEach { (title, body) -> Section(title = title, body = body) }

        if (!annotation.citation.isNullOrBlank() || annotation.sources.isNotEmpty()) {
            Sources(annotation = annotation, onOpenSource = onOpenSource)
        }
    }
}

/** 单个注疏区块：小标题 + 左对齐正文。 */
@Composable
private fun Section(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ShiciDimens.EntryGap),
    ) {
        SectionLabel(title)
        Text(
            text = body,
            modifier = Modifier.fillMaxWidth(),
            style = ShiciText.AnnotationBody,
            color = ShiciColors.InkSoft,
            // 这行不能省：父容器是 CenterHorizontally，Text 只 fillMaxWidth 还不足以左对齐，
            // 文字本身仍按 textAlign 排 —— 不写就是居中的。
            textAlign = TextAlign.Start,
        )
    }
}

/**
 * 出处：文献引用 + 来源链接 + 数据源与许可。
 *
 * 它不是「补充信息」，而是这个功能的**可信度凭据**：注疏是别人的编辑成果，
 * 用户看到「引自《唐诗鉴赏辞典》」「古诗文网（CC0-1.0）」才能判断该不该信，
 * 也才有路径去读完整内容。所以要做成能点的链接 + 明确的许可标注，
 * 而不是一行灰色小字。
 */
@Composable
private fun Sources(annotation: Annotation, onOpenSource: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ShiciDimens.EntryGap),
    ) {
        SectionLabel("参考资料")
        annotation.citation?.takeIf { it.isNotBlank() }?.let { citation ->
            Text(
                text = citation,
                modifier = Modifier.fillMaxWidth(),
                style = ShiciText.EntryMeta,
                color = ShiciColors.InkFaint,
                textAlign = TextAlign.Start,
            )
        }
        // 最多 3 条：注疏的来源通常就一两个站点，多了是噪音
        annotation.sources.take(3).forEach { source ->
            if (source.url.isNotBlank()) {
                QuietLink(
                    text = source.title.ifBlank { source.url },
                    onClick = { onOpenSource(source.url) },
                )
            }
        }
    }
}

/**
 * 未收录时的提示。
 *
 * ⚠️ **文案不能是「加载失败」**：全量 83.4 万首里只有 7,388 首有注疏（0.88%），
 * 「没有注疏」是**常态**而不是异常。说成失败会让用户以为 App 坏了、反复重试。
 * 而且它必须**给出路** —— 指向下面的「问一问」（方式一），那才是任何一首诗
 * 都能得到解答的路径。
 */
@Composable
fun NoAnnotationHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ShiciDimens.EntryGap),
    ) {
        HairlineRule()
        Text(
            text = "这首诗暂无注疏，可以在下面直接提问",
            modifier = Modifier.fillMaxWidth(),
            style = ShiciText.EntryMeta,
            color = ShiciColors.InkFaint,
            textAlign = TextAlign.Center,
        )
    }
}

/** 供外部复用：把来源列表转成可点链接时过滤掉空 URL。 */
fun usableSources(sources: List<AnnotationSource>): List<AnnotationSource> =
    sources.filter { it.url.isNotBlank() }
