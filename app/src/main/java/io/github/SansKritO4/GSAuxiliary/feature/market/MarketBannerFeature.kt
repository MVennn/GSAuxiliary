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
 * 功能：**行情市场页——滚动横幅与快捷入口一起收起**。
 *
 * ## 做什么
 *
 * 把「市场概览」下面那条**滚动横幅**（热门专题轮播）、以及它下面那排**快捷入口**
 * 一起收起来，让板块直接顶上来。
 *
 * ## 与初版设想最大的不同：这两块是 `RecyclerView` 的子项
 *
 * 实测行情 tab 的 A股 页整体是一个横向 `RecyclerView`（`#rv_markets_hs`），
 * **每个版块都是它的一个子项**。`RecyclerView` 的 `LayoutManager` 不判
 * `visibility`，会把所有已挂上的子项照样测一遍、摆一遍 —— 所以只设 `GONE`
 * 会留下一条等高的空档，必须用
 * [io.github.SansKritO4.GSAuxiliary.feature.uiclean.ViewPatcher.collapse]
 * （高度归零 + `GONE`）连高度一起收掉。
 *
 * ## 怎么认（实测值见 [MarketTargets]）
 *
 *  1. **滚动横幅**：`#avf_hotpoint`（`AdapterViewFlipper`）——
 *     注意它是「热门专题轮播」，**不是跑马灯**。全树扫过，这一页里没有任何
 *     `ellipsize = MARQUEE` 的 `TextView`，所以「按跑马灯文字找」那条路在真机上
 *     永远认不出来。这里改成按 id + 「轮播容器（`ViewAnimator` 家族）且不高」的结构兜底。
 *  2. **快捷入口**：`#rv_entra`，并确认里面确实是 `#item_marktes_entra` 那种格子。
 *
 * 两块都认不出来时返回 `false`，交给基类重试；重试窗口用尽后基类会打一份
 * 当前页面的视图树快照，那就是校准入口。
 */
class MarketBannerFeature : UiCleanFeature() {

    override val id = "market_banner_hide"
    override val title = "行情市场隐藏滚动横幅与快捷入口"

    override val pages = MarketTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.marketBannerHide

    /** 预热用：本功能会查的全部 id 名（在进入任意页面时提前解析，见 [UiCleanFeature.idNames]） */
    override val idNames = MarketTargets.PAGE_ANCHOR_ID_CANDIDATES +
        MarketTargets.HOT_BAR_ID_CANDIDATES +
        MarketTargets.QUICK_ENTRY_ID_CANDIDATES +
        arrayOf(MarketTargets.QUICK_ENTRY_ITEM_ID)

    private var lastBar: WeakReference<View>? = null
    private var lastGrid: WeakReference<View>? = null

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        if (alreadyHidden(lastBar?.get()) && alreadyHidden(lastGrid?.get())) return true

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val ids = UiClean.idsFor(activity, MarketTargets.TARGET_PKG)

        // 页面门禁：不在行情市场页就什么都不做（也**不报告** —— 首页上也有轮播容器
        // 与入口网格，没有这道门禁会误伤）
        if (!MarketTargets.onMarketPage(root, ids)) return true

        var hit = 0
        findHotBar(root, ids)?.let { lastBar = collapseIt(it, "滚动横幅"); hit++ }
        findEntryGrid(root, ids)?.let { lastGrid = collapseIt(it, "快捷入口"); hit++ }

        // 在这一页却没认出来：可能还没铺开 —— 交给基类重试 / 打快照
        return hit > 0
    }

    // ------------------------------------------------------------------ 定位

    /** 滚动横幅：先按候选 id，再按「轮播容器」的结构兜底 */
    private fun findHotBar(root: View, ids: IdCache): View? {
        val byId = ids.firstOf(MarketTargets.HOT_BAR_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v != null && v.isShown) return v
        }
        return UiClean.find(root) { v ->
            v.isShown && isFlipper(v) &&
                UiClean.heightOf(v) in 1..UiClean.dp(v, MarketTargets.HOT_BAR_MAX_HEIGHT_DP)
        }
    }

    /** `ViewAnimator` 家族（`ViewFlipper` / `AdapterViewFlipper`）—— 轮播类容器的共同祖先 */
    private fun isFlipper(v: View): Boolean = hasSuper(v.javaClass, "android.widget.ViewAnimator")

    /**
     * 快捷入口：先按候选 id（并确认里面是行情页那排入口的格子），
     * 再按「可见 + 每格都是图标加文字 + 不高」的结构兜底。
     */
    private fun findEntryGrid(root: View, ids: IdCache): View? {
        val itemId = ids.of(MarketTargets.QUICK_ENTRY_ITEM_ID)

        val byId = ids.firstOf(MarketTargets.QUICK_ENTRY_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v is ViewGroup && v.isShown && isQuickEntryGrid(v, itemId)) return v
        }

        var hit: ViewGroup? = null
        UiClean.walk(root) { v ->
            if (hit != null) return@walk true
            if (v is ViewGroup && v.isShown && isEntryGrid(v) && isQuickEntryGrid(v, itemId)) {
                hit = v
                true
            } else {
                false
            }
        }
        return hit
    }

    /**
     * 确认「这就是行情页那排入口」。
     *
     * [itemId] 解析不到时一律放行 —— 宿主改版后不该因为一个辅助 id 失效就整个功能瘫掉。
     */
    private fun isQuickEntryGrid(grid: ViewGroup, itemId: Int): Boolean =
        itemId == 0 || grid.findViewById<View>(itemId) != null

    private fun isEntryGrid(g: ViewGroup): Boolean {
        val n = g.childCount
        if (n < MarketTargets.MIN_ENTRY_ITEMS) return false

        var entries = 0
        for (i in 0 until n) if (UiClean.looksLikeEntryItem(g.getChildAt(i))) entries++
        if (entries < n - 1) return false

        val h = UiClean.heightOf(g)
        if (h > 0 && h > UiClean.dp(g, MarketTargets.MAX_ENTRY_GRID_HEIGHT_DP)) return false
        return true
    }

    // ------------------------------------------------------------------ 收起

    /**
     * 收起 [v] 所在的**版块**（= 列表里的那一个子项），返回收起来的视图。
     *
     * 为什么收子项而不是收 [v] 自己：
     *
     *  - 版块的内边距都在子项上，只藏 [v] 会留下那段间距；
     *  - 子项才是 `RecyclerView` 的直接子视图，高度归零才真正「不占地方」。
     */
    private fun collapseIt(v: View, why: String): WeakReference<View> {
        val item = itemOf(v)
        patcher.collapse(item, "行情市场$why")
        return WeakReference(item)
    }

    /** [v] 所在的列表项：爬到「父级是列表」的那一层；爬不到就退回 [v] 自己 */
    private fun itemOf(v: View): View {
        var cur: View = v
        var guard = 0
        while (guard++ < 8) {
            val parent = cur.parent as? ViewGroup ?: return v
            if (isList(parent)) return cur
            cur = parent
        }
        return v
    }

    private fun isList(p: ViewGroup): Boolean {
        if (p is android.widget.AdapterView<*>) return true
        return hasSuper(p.javaClass, "androidx.recyclerview.widget.RecyclerView")
    }

    private fun hasSuper(cls: Class<*>, name: String): Boolean {
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && depth++ < 10) {
            if (c.name == name) return true
            c = c.superclass
        }
        return false
    }
}
