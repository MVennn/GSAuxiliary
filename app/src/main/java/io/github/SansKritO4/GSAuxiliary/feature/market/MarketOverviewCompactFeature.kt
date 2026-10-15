package io.github.SansKritO4.GSAuxiliary.feature.market

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.feature.FeatureEnv
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.IdCache
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiClean
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiCleanFeature
import java.lang.ref.WeakReference

/**
 * 功能：**行情市场页的「市场概览」整体压矮，柱状图高度一并压缩**。
 *
 * ## 做什么
 *
 *  1. 找到市场概览分组容器，把高度压到原来的
 *     [MarketTargets.OVERVIEW_HEIGHT_FACTOR]（默认 0.6）；
 *  2. 再把它子树里**固定高度**的柱状图视图按同一比例压一遍。
 *
 * ## 为什么第 2 步要「只压固定高度的」
 *
 * 柱状图有两种写法：
 *
 *  - **自适应**（`match_parent` / `wrap_content`）：高度跟着容器走，
 *    容器压了它就跟着矮 —— 再压一次会**双重压缩**，图直接被压扁到看不清；
 *  - **固定高度**（`layoutParams.height > 0`）：不跟容器走，必须单独压。
 *
 * 所以这里用 `layoutParams.height > 0` 精确区分，各压一次、不重不漏。
 *
 * ## 怎么认「市场概览」
 *
 * 候选 id 优先；否则找**子树里有 ≥ 2 个柱状图视图**的最内层容器。
 *
 * 「柱状图视图」的判据（[isChartView]）：
 *  - 类名里带 `chart` / `bar` / `histogram` / `kline` / `graph` / `column` / `trend`；或
 *  - 是**自绘控件**（不是 `android.widget.*` / `android.view.*` 的标准控件），
 *    且高度 ≥ [MarketTargets.CHART_MIN_HEIGHT_DP]、宽度至少是高度的 2 倍。
 *
 * 第二条是给「宿主自己写的图表控件、类名里没有 chart」这种情况兜底的。
 */
class MarketOverviewCompactFeature : UiCleanFeature() {

    override val id = "market_overview_compact"
    override val title = "行情市场概览压缩"

    override val pages = MarketTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.marketOverviewCompact

    private var lastSection: WeakReference<View>? = null

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        // 幂等：处理过的容器还在树上 = 已经压过（改的是容器和子控件的高度）
        if (stillAttached(lastSection?.get())) return true

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val ids = UiClean.idsFor(activity, MarketTargets.TARGET_PKG)
        val section = findOverviewSection(root, ids) ?: return false

        // 认到了但还没布局（量不出高度）→ 返回 false 等下一帧，别把「已处理」标记打上去
        val changed = compressCharts(section) or
            patcher.scaleHeight(section, MarketTargets.OVERVIEW_HEIGHT_FACTOR, "市场概览")
        if (!changed) return false

        lastSection = WeakReference(section)
        return true
    }

    // ------------------------------------------------------------------ 定位

    private fun findOverviewSection(root: View, ids: IdCache): View? {
        val byId = ids.firstOf(MarketTargets.OVERVIEW_GROUP_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v != null && v.isShown) return v
        }

        // 子树里含 ≥ 2 个柱状图的最内层容器（前序覆盖 → 取最后一个 = 最深的那个）
        var best: View? = null
        UiClean.walk(root) { v ->
            if (v is ViewGroup && v.isShown &&
                UiClean.findAll(v, { isChartView(it) }, limit = 2).size >= 2
            ) {
                best = v
            }
            false
        }
        val hit = best
        if (hit != null) {
            GLog.i("$id：概览区命中 ${hit.javaClass.simpleName}（${(hit as? ViewGroup)?.childCount ?: 0} 个子项）")
        }
        return hit
    }

    // ------------------------------------------------------------------ 压缩

    /**
     * 只压**固定高度**的柱状图；自适应高度的交给容器一起压，避免双重压缩。
     *
     * 返回 true = 至少压到了东西。
     */
    private fun compressCharts(section: View): Boolean {
        val charts = UiClean.findAll(section, { isChartView(it) }, limit = 16)
        var n = 0
        for (c in charts) {
            val lp = c.layoutParams ?: continue
            if (lp.height > 0 && patcher.scaleHeight(c, MarketTargets.OVERVIEW_HEIGHT_FACTOR, "柱状图")) n++
        }
        if (charts.isNotEmpty()) GLog.i("$id：概览区柱状图 ${charts.size} 个，其中固定高度 $n 个已单独压缩")
        return n > 0
    }

    private fun isChartView(v: View): Boolean {
        if (v is ViewGroup) return false
        if (!v.isShown) return false

        val name = v.javaClass.name.lowercase()
        if (MarketTargets.CHART_CLASS_KEYWORDS.any { name.contains(it) }) return true

        // 标准控件不可能是图表
        if (name.startsWith("android.widget.") || name.startsWith("android.view.")) return false

        // 自绘控件：够高、够宽（柱状图都是扁的）
        val h = UiClean.heightOf(v)
        val w = v.width
        return h >= UiClean.dp(v, MarketTargets.CHART_MIN_HEIGHT_DP) && w >= h * 2
    }
}
