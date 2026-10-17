package io.github.MVennn.GSAuxiliary.feature.stockbar

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.util.Density

/**
 * 「智能盯盘」入口的**取色 / 加宽测量**：从「更多」按钮上取样式与几何尺寸。
 *
 * 从 [StockDetailBarFeature] 拆出（注释随代码原样搬移）：
 * Feature 本体只留「注入按钮 + 触发面板」的流程，量尺寸、取样式这些
 * 纯函数集中在这一处，供注入（`buildEntry` / `buildInline`）调用。
 */
internal object StockDetailBarMeasure {

    /** 「更多」自己那个图标的尺寸 —— 我们的图标按它缩放，才能和同排按钮一样高 */
    fun refIconSize(moreText: TextView): Pair<Int, Int> {
        val d = runCatching { moreText.compoundDrawables.getOrNull(1) }.getOrNull()
        var w = d?.intrinsicWidth ?: 0
        var h = d?.intrinsicHeight ?: 0
        if (w <= 0) w = runCatching { d?.bounds?.width() ?: 0 }.getOrDefault(0)
        if (h <= 0) h = runCatching { d?.bounds?.height() ?: 0 }.getOrDefault(0)
        if (w <= 0 || h <= 0) {
            // 兜底：按 22dp 方图（与「更多」的 ••• 视觉相当）
            val px = Density.dp(moreText, FALLBACK_ICON_DP)
            w = px
            h = px
        }
        return w to h
    }

    /** 与「更多」之间的间距：优先沿用盒子原本的 margin，拿不到就用 10dp */
    fun gapPx(moreText: TextView): Int {
        val m = (moreText.parent as? View)?.layoutParams as? ViewGroup.MarginLayoutParams
        val fromMargin = m?.marginStart ?: 0
        if (fromMargin > 0) return fromMargin
        return Density.dp(moreText, GAP_DP)
    }

    /**
     * 复制「更多」按钮的可见样式。
     *
     * 与 `StgSelectAllFeature.copyStyle` 同思路：只复制可见属性，尺寸交给容器。
     */
    fun copyStyle(src: TextView, dst: TextView) {
        runCatching { dst.setTextColor(src.textColors) }
        runCatching { src.typeface?.let { dst.typeface = it } }
        runCatching { dst.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, src.textSize) }
        runCatching { dst.letterSpacing = src.letterSpacing }
        runCatching { dst.textScaleX = src.textScaleX }
        runCatching {
            dst.setPadding(src.paddingLeft, src.paddingTop, src.paddingRight, src.paddingBottom)
        }
        runCatching { dst.compoundDrawablePadding = src.compoundDrawablePadding }
        runCatching { dst.includeFontPadding = src.includeFontPadding }
        runCatching { dst.maxLines = src.maxLines }
        runCatching { dst.ellipsize = src.ellipsize }
        runCatching { dst.setBackground(src.background?.constantState?.newDrawable()?.mutate()) }
        runCatching { dst.backgroundTintList = src.backgroundTintList }
        runCatching { dst.foreground = src.foreground?.constantState?.newDrawable()?.mutate() }
        runCatching { dst.setAllCaps(false) }
    }

    /** 拿不到盒子 margin 时，新按钮与「更多」之间的兜底间距（dp） */
    private const val GAP_DP = 10f

    /** 量不到「更多」图标尺寸时的兜底边长（dp） */
    private const val FALLBACK_ICON_DP = 22f
}
