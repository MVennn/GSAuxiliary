package io.github.MVennn.GSAuxiliary.feature.market

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.uiclean.IdCache
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiClean
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiCleanFeature
import java.lang.ref.WeakReference

/**
 * 功能：**行情市场页的「板块」由六项压到三项**。
 *
 * ## 做什么
 *
 * 板块区原本一屏铺六个，只保留最上面一行（三项），其余收起 —— 板块区整体变矮，
 * 下面的内容顺势上移。
 *
 * ## 真机实测（见 [MarketTargets]）：3 列 × 2 行，一格 264×234
 *
 * ```
 * [行6] 板块版块
 *   RadioGroup #rg_bk            涨幅榜 / 涨速榜 / 成交额 / 连涨天 / 净流入
 *   LockViewPager #vp_bk_content 39,91-1041,559（468px）
 *     RecyclerView               0,0-1002,468
 *       BKHQOverviewItemView ×6  (0,0) (368,0) (737,0) (0,234) (368,234) (737,234)
 * ```
 *
 * 六个格子是 3 列 2 行等分的，「留三项」= **留一整行** = 容器高度取一半，
 * 所以对 [MarketTargets.SECTOR_GROUP_ID_CANDIDATES] 的首个候选限高最稳
 * （系数取 `SECTOR_KEEP / SECTOR_TOTAL`）。
 *
 * **为什么不「藏后三项」**：实测容器是 `LockViewPager` + 内部 `RecyclerView`，
 * 而 `RecyclerView` 的子项设成 `GONE` 照样会被 `LayoutManager` 测一遍摆一遍，
 * 藏了也白藏、只会留空档。
 *
 * ## 兜底
 *
 * 候选 id 认不到时退回结构判据：找「子项数量 ≥ 6、宽度基本一致、每项都有文案、
 * 且**不是**图标加文字的入口网格」的容器（允许套一层包装容器）。
 */
class MarketSectorCompactFeature : UiCleanFeature() {

    override val id = "market_sector_compact"
    override val title = "行情市场板块显示单行"

    override val pages = MarketTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.marketSectorCompact

    /** 预热用：本功能会查的全部 id 名（在进入任意页面时提前解析，见 [UiCleanFeature.idNames]） */
    override val idNames = MarketTargets.PAGE_ANCHOR_ID_CANDIDATES +
        MarketTargets.SECTOR_GROUP_ID_CANDIDATES +
        arrayOf(MarketTargets.SECTOR_ITEM_ID)

    private var lastGroup: WeakReference<View>? = null

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        // 幂等：处理过的容器还在树上 = 已经压过（改的是容器高度，没有「被藏的那一个」可判）
        if (stillAttached(lastGroup?.get())) return true

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val ids = UiClean.idsFor(activity, MarketTargets.TARGET_PKG)

        // 页面门禁：不在行情市场页就什么都不做（也不报告）
        if (!MarketTargets.onMarketPage(root, ids)) return true

        val group = findSectorGroup(root, ids) ?: return false
        if (!compact(group)) return false      // 还没量出高度 → 下一帧再来，别打「已处理」标记

        lastGroup = WeakReference(group)
        return true
    }

    // ------------------------------------------------------------------ 定位

    private fun findSectorGroup(root: View, ids: IdCache): View? {
        val itemId = ids.of(MarketTargets.SECTOR_ITEM_ID)

        val byId = ids.firstOf(MarketTargets.SECTOR_GROUP_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v != null && v.isShown && isSectorGroup(v, itemId)) return v
        }

        return UiClean.find(root) { v ->
            v is ViewGroup && v.isShown && isSectorGroup(v, itemId)
        }
    }

    /**
     * 「板块区」的确认：先看实测的格子 id 在不在子树里（最可靠）；
     * 能解析到这个 id 但它不在子树里 → 明确不是板块区；
     * 解析不到 → 退回结构判据。
     */
    private fun isSectorGroup(v: View, itemId: Int): Boolean {
        if (itemId != 0) return v.findViewById<View>(itemId) != null
        val g = v as? ViewGroup ?: return false
        return gridOf(g) != null
    }

    /** 容器里的「六格网格」：直接子项，或单子项包装层里的下一个 */
    private fun gridOf(g: ViewGroup): ViewGroup? {
        var cur: ViewGroup = g
        var depth = 0
        while (depth++ < 3) {
            if (isUniformGrid(cur)) return cur
            if (cur.childCount != 1) return null
            cur = cur.getChildAt(0) as? ViewGroup ?: return null
        }
        return null
    }

    private fun isUniformGrid(g: ViewGroup): Boolean {
        val n = g.childCount
        if (n < MarketTargets.SECTOR_TOTAL) return false

        val widths = ArrayList<Int>(n)
        var labels = 0
        for (i in 0 until n) {
            val c = g.getChildAt(i)
            if (c.width > 0) widths.add(c.width)
            if (UiClean.firstLabel(c) != null) labels++
        }
        if (widths.size != n) return false
        if (labels < n - 1) return false

        // 入口网格（「图标 + 文字」）也是等宽的，排除掉
        var entries = 0
        for (i in 0 until n) if (UiClean.looksLikeEntryItem(g.getChildAt(i))) entries++
        if (entries >= n - 1) return false

        return widths.max() <= widths.min() * MarketTargets.SECTOR_WIDTH_TOLERANCE
    }

    // ------------------------------------------------------------------ 压缩

    /**
     * 压缩板块区。返回 `false` = 容器还没量出高度（**别打「已处理」标记**），
     * `true` = 处理完了。
     */
    private fun compact(group: View): Boolean {
        val h = UiClean.heightOf(group)
        if (h <= 0) return false

        val keepRatio = MarketTargets.SECTOR_KEEP.toFloat() / MarketTargets.SECTOR_TOTAL
        val target = (h * keepRatio).toInt().coerceAtLeast(1)
        patcher.setHeight(group, target, "板块区")
        GLog.i(
            "$id：板块区限高 ${h}px -> ${target}px" +
                "（保留 ${MarketTargets.SECTOR_KEEP}/${MarketTargets.SECTOR_TOTAL} 项）"
        )
        return true
    }
}
