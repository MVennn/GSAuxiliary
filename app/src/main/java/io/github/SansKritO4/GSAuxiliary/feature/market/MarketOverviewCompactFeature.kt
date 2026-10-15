package io.github.SansKritO4.GSAuxiliary.feature.market

import android.app.Activity
import android.view.View
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
 * 「市场概览」那一块里的**图表区**整体压矮，里面的**柱状图**高度一并压缩。
 * 图表区变矮之后，下面的滚动横幅 / 快捷入口 / 板块顺势上移。
 *
 * ## 真机实测（见 [MarketTargets]）：只压图表那一份，不整块等比
 *
 * ```
 * [行2] 市场概览块（696px）
 *   ├ 标题行「市场概览 + 成交额」            111px
 *   ├ 三张指标卡（涨跌分布 / 主力净流入 / 大盘股>小盘股）  161px
 *   └ FrameLayout #fl_content  图表区         411px  ← 压这一块
 *       └ ZdfbChartView #zdfb_chart_view      268px  ← 柱状图，也压
 *          涨 / 跌 比例条 + 涨停 / 跌停 行       143px
 * ```
 *
 * 为什么**不**把整块等比压到 0.6：标题行与三张指标卡里是两三行文字
 * （「涨 430 跌 5108」这种），等比压只会把字裁掉一半，看着像坏了。
 * 所以压缩量全部来自图表区：
 *
 * ```
 * 图表区新高度 = 柱状图 × factor + 图表区其余部分（原样）
 * ```
 *
 * 这样柱状图明显变矮、比例条与涨跌停行不被裁，整块自然跟着矮一截。
 *
 * ## 「压哪些视图」
 *
 * 概览区里三张图（涨跌分布 / 大盘股 / 净流入）**轮流占位，同时只有一张可见**，
 * 各自是宿主自绘的图表控件，id 分别是 `zdfb_chart_view` / `dxpg_chart_view` /
 * `jlr_chart_view` —— 三张都压一遍，用户切到哪张都是矮的。
 * 认不到 id 时退回「类名带 chart / bar / trend 且够高的自绘控件」的结构兜底。
 */
class MarketOverviewCompactFeature : UiCleanFeature() {

    override val id = "market_overview_compact"
    override val title = "行情市场概览压矮"

    override val pages = MarketTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.marketOverviewCompact

    /** 已经压过的图表区（幂等标记：容器还在树上就算压过了） */
    private var lastArea: WeakReference<View>? = null

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        // 幂等：处理过的图表区还在树上 = 已经压过
        if (stillAttached(lastArea?.get())) return true

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val ids = UiClean.idsFor(activity, MarketTargets.TARGET_PKG)

        val area = findChartArea(root, ids) ?: return false
        if (!compact(area, ids)) return false        // 还没布局出来 → 下一帧再来，别打「已处理」标记

        lastArea = WeakReference(area)
        return true
    }

    // ------------------------------------------------------------------ 定位

    /** 图表区：先按 id，再退回「子树里有够高的自绘图表控件」的最内层容器 */
    private fun findChartArea(root: View, ids: IdCache): View? {
        val byId = ids.of(MarketTargets.OVERVIEW_CHART_AREA_ID)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v != null && v.isShown && chartsOf(v, ids).isNotEmpty()) return v
        }
        var best: View? = null
        UiClean.walk(root) { v ->
            if (v.isShown && chartsOf(v, ids).isNotEmpty()) {
                best = v
                true
            } else {
                false
            }
        }
        return best
    }

    /**
     * [v] 子树里的图表控件：先按候选 id 取，取不到再按类名 / 自绘 + 高度判。
     */
    private fun chartsOf(v: View, ids: IdCache): List<View> {
        val byId = MarketTargets.OVERVIEW_CHART_ID_CANDIDATES
            .map { ids.of(it) }
            .filter { it != 0 }
            .mapNotNull { v.findViewById<View>(it) }
            .filter { it.isShown }
        if (byId.isNotEmpty()) return byId

        return UiClean.findAll(v, { looksLikeChart(it) }, limit = 8)
    }

    /** 「自绘图表控件」的判据：类名带关键词，或者是够高够宽的非标准控件 */
    private fun looksLikeChart(v: View): Boolean {
        if (!v.isShown) return false
        val h = UiClean.heightOf(v)
        if (h < UiClean.dp(v, MarketTargets.CHART_MIN_HEIGHT_DP)) return false

        val name = v.javaClass.simpleName.lowercase()
        if (MarketTargets.CHART_CLASS_KEYWORDS.any { name.contains(it) }) return true

        // 非 android.widget.* / android.view.* 标准控件，且明显是横的长条 → 自绘图表
        val pkg = v.javaClass.name
        if (pkg.startsWith("android.widget.") || pkg.startsWith("android.view.")) return false
        return v.width >= h * 2
    }

    // ------------------------------------------------------------------ 压缩

    /**
     * 压缩图表区。返回 `false` = 还没量出高度（**别打「已处理」标记**），
     * `true` = 处理完了（含「认得出但形状不认识」这种放弃情形）。
     */
    private fun compact(area: View, ids: IdCache): Boolean {
        val areaH = UiClean.heightOf(area)
        if (areaH <= 0) return false

        val charts = chartsOf(area, ids).filter { UiClean.heightOf(it) > 0 }
        if (charts.isEmpty()) {
            GLog.i("$id：图表区里没找到量得出高度的图表控件，本页跳过")
            return true
        }

        var saved = 0
        for (c in charts) {
            val before = UiClean.heightOf(c)
            if (patcher.scaleHeight(c, MarketTargets.OVERVIEW_HEIGHT_FACTOR, "概览图表")) {
                saved += before - (before * MarketTargets.OVERVIEW_HEIGHT_FACTOR).toInt()
            }
        }

        // 图表区自身高度可能是写死的（不跟子项走），那就连着一起收：
        // 只收「图表那一份」，比例条 / 涨跌停行保持原样，避免把字裁掉
        val chartH = charts.sumOf { UiClean.heightOf(it) }
        val rest = (areaH - chartH).coerceAtLeast(0)
        val target = (chartH * MarketTargets.OVERVIEW_HEIGHT_FACTOR).toInt() + rest
        if (target in 1 until areaH) patcher.setHeight(area, target, "概览图表区")

        GLog.i("$id：概览图表区 ${areaH}px -> ${target}px（压了 ${charts.size} 张图，省 ${areaH - target}px）")
        return true
    }
}
