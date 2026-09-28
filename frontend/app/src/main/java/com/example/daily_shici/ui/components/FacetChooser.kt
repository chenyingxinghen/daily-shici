package com.example.daily_shici.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.daily_shici.domain.model.FacetKey
import com.example.daily_shici.domain.model.FacetOption
import com.example.daily_shici.ui.theme.ShiciColors
import com.example.daily_shici.ui.theme.ShiciDimens
import com.example.daily_shici.ui.theme.ShiciText

/**
 * 「维度 → 取值」两层筛选条，浏览页与漫游页共用。
 *
 * 抽出来的理由不是省代码，而是**避免两处漂移**：漫游的范围必须和浏览的筛选
 * 语义完全一致（同样的维度、同样的「切维度即清空取值」），
 * 各写一份迟早会出现「浏览里词牌能选、漫游里选了不生效」。
 *
 * @param values 第二行展示的取值，由调用方按 [facetKey] 从 `Facets` 里取
 * @param selected 当前生效的取值
 */
@Composable
fun FacetChooser(
    facetKey: FacetKey,
    values: List<FacetOption>,
    selected: FacetOption?,
    onSelectKey: (FacetKey) -> Unit,
    onSelectValue: (FacetOption) -> Unit,
    modifier: Modifier = Modifier,
    maxValues: Int = MAX_FACET_VALUES,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 5 个维度（全部/朝代/体裁/词牌/合集）在 390dp 宽里放不下，
        // 故横向可滚 —— 比压缩间距或砍维度都好。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ShiciDimens.FilterRowGap),
        ) {
            FacetKey.entries.forEach { key ->
                val active = key == facetKey
                MarkedText(
                    text = key.label,
                    color = if (active) ShiciColors.Vermilion else ShiciColors.InkSoft,
                    style = if (active) ShiciText.FilterActive else ShiciText.Filter,
                    underlined = active,
                    onClick = { onSelectKey(key) },
                )
            }
        }

        VerticalGap(ShiciDimens.ContentGap)

        if (values.isEmpty()) {
            Text(
                text = "该分类暂无可选项",
                style = ShiciText.EntryMeta,
                color = ShiciColors.InkFaint,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(ShiciDimens.InlineRowGap),
            ) {
                values.take(maxValues).forEach { option ->
                    val active = option == selected
                    MarkedText(
                        text = option.label,
                        color = if (active) ShiciColors.Vermilion else ShiciColors.InkSoft,
                        style = if (active) ShiciText.DynastyActive else ShiciText.Dynasty,
                        underlined = active,
                        underlineThickness = ShiciDimens.UnderlineHeight,
                        onClick = {
                            // 停在「全部」时点取值 = 快切到朝代维度再选值
                            if (facetKey == FacetKey.ALL) onSelectKey(FacetKey.DYNASTY)
                            onSelectValue(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 取值 chip 的上限。词牌有 1057 个，全渲染既卡也没有意义 ——
 * `/facets` 按 count 降序返回，取前 40 个就是最常用的那些。
 */
private const val MAX_FACET_VALUES = 40
