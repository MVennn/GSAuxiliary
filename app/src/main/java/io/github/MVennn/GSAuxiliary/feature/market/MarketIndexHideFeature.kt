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
import io.github.MVennn.GSAuxiliary.util.Density
import java.lang.ref.WeakReference

/**
 * 功能：**行情市场页顶部的「指数概览」整块收起**。
 *
 * ## 做什么
 *
 * 行情 tab（市场 → A股）最上面那一行是**指数概览**（上证 / 深证 / 北证50 …），
 * 占了 229px。关掉它，下面的「市场概览」直接顶上，行情页一屏能多看一屏。
 *
 * ## 真机实测结构（见 [MarketTargets]）
 *
 * ```
 * HorizontalRecyclerView #rv_markets_hs          ← 整页的纵向容器（每个版块 = 一个子项）
 *   [行1] LinearLayout  0,0-1080,229            ← 指数概览（**本功能收的就是它**）
 *     RecyclerView #rv_index → LinearLayout #ll_content ×4（上证 / 深证 / 北证50 …）
 *   [行2] LinearLayout  0,229-1080,925          ★ 市场概览
 *   [行3] LinearLayout  0,925-1080,1091         ★ 滚动横幅
 *   [行4] LinearLayout  0,1107-1080,1310        ★ 快捷入口
 * ```
 *
 * ## 为什么收「行1」而不是收 `#rv_index`
 *
 * 两个原因：
 *
 *  1. **版块的内边距都在子项上** —— 只藏 `#rv_index` 会留下行1 那段间距；
 *  2. **子项才是 `RecyclerView` 的直接子视图** —— 它的 `LayoutManager` 不判
 *     `visibility`，会把所有已挂上的子项照样测一遍摆一遍，只设 `GONE` 照样占地方。
 *     必须用 [io.github.MVennn.GSAuxiliary.feature.uiclean.ViewPatcher.collapse]
 *     （高度归零 + `GONE`）连高度一起收掉。
 *
 * 行1 没有 id，所以从 `#rv_index` 往上爬到「父级是列表」的那一层（[itemOf]）。
 *
 * ## 怎么认（宿主改版后的兜底）
 *
 * 先按候选 id 找 `#rv_index`，并用 `#ll_content`（每一项的容器）确认
 * 「这确实是指数概览」；id 认不到时退回结构判据：找「子项 ≥ 3、每个子项里有文案、
 * 整块不高」的横向列表。
 *
 * ⚠️ **认不到就什么都不做**（返回 `false` 交给基类重试），不猜。
 */
class MarketIndexHideFeature : UiCleanFeature() {

    override val id = "market_index_hide"
    override val title = "行情市场隐藏指数概览"

    override val pages = MarketTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.marketIndexHide

    /** 预热用：本功能会查的全部 id 名（在进入任意页面时提前解析，见 [UiCleanFeature.idNames]） */
    override val idNames = MarketTargets.PAGE_ANCHOR_ID_CANDIDATES +
        MarketTargets.INDEX_OVERVIEW_ID_CANDIDATES +
        arrayOf(MarketTargets.INDEX_ITEM_ID)

    private var lastRow: WeakReference<View>? = null

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        // 幂等：收起的那一行还在树上 = 已经处理过
        if (alreadyHidden(lastRow?.get())) return true

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val ids = UiClean.idsFor(activity, MarketTargets.TARGET_PKG)

        // 页面门禁：不在行情市场页就什么都不做（也不报告）
        if (!MarketTargets.onMarketPage(root, ids)) return true

        val box = findIndexOverview(root, ids) ?: return false
        val row = itemOf(box)
        patcher.collapse(row, "指数概览")
        lastRow = WeakReference(row)
        GLog.i("$id：已收起指数概览（行情页顶部那一行）")
        return true
    }

    // ------------------------------------------------------------------ 定位

    /**
     * 指数概览的内层容器（`#rv_index`），不是它所在的行1。
     *
     * 找到的是内层容器，**收起的是行1**（见 [itemOf]）—— 内层只是定位用的锚点。
     */
    private fun findIndexOverview(root: View, ids: IdCache): View? {
        val itemId = ids.of(MarketTargets.INDEX_ITEM_ID)

        val byId = ids.firstOf(MarketTargets.INDEX_OVERVIEW_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            // ⚠️ **`#ll_content` 只当「辅助确认」，不当必要条件** ——
            // 宿主改版把它改名了的话，拿它当必要条件就等于**整个功能废掉**；
            // 而 `#rv_index` 已经是足够特异的锚点了（行情页只有这一处叫这个名字）。
            // 确认失败时**照样用**，只是多一层结构兜底在后面接着。
            if (v != null && v.isShown && (itemId == 0 || v.findViewById<View>(itemId) != null)) {
                return v
            }
            // 名字对上了但内容不像 → 再用结构判据确认一次
            if (v != null && v.isShown && looksLikeIndexList(v, itemId)) return v
        }

        // 兜底：结构判据 —— 横向的、子项够多、每项有文案、整块不高的列表
        //
        // ⚠️ **兜底范围必须收窄到「行情页那几行」**（2026-10-20 复核时发现的）：
        // 原来是在**整棵 decor** 里找第一个像的 —— 而行情页顶部那排
        // `RadioGroup #rg_a_stock`（沪深京 / 创业板 / 科创板 / 北交所）**恰好满足
        // 全部四条判据**（4 项、每项有文字、RadioButton 不是 ViewGroup 所以不像入口格、
        // 整块约 38dp 在上限内），而且它在 DFS 里**比 `#rv_index` 更靠前** ——
        // 于是会把「板块切换器」整排收起，用户在行情页少了那四个页签，
        // 而日志里只有一句「已收起指数概览」，**认错了也看不出来**。
        //
        // 现在兜底只在**「行情页那几行」里**找 —— 也就是实测的「行1」，
        // 范围小了一个数量级，认错的可能也小一个数量级。
        //
        // ⚠️ **兜底必须用「结构判据」而不是「按 id 找」**（2026-10-20 二次复核时发现）：
        // 兜底存在的意义**恰恰是覆盖 id 失效的场景**（宿主改名）——
        // 按 id 找的话，id 一失效（`byId == 0`）兜底也跟着死，
        // 于是「宿主升级 → 功能整个静默失效」，兜底等于没有。
        // 所以这里遍历那几行的**直接子项**，用结构判据认。
        //
        // 顺带把「板块切换器」也天然排除了：`#rg_a_stock` 在 `#rv_markets_hs` **之外**
        // （它是行情 tab 顶上的那排页签），根本不在「那几行」里。
        for (row in marketRows(root, ids)) {
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i) ?: continue
                if (child.isShown && looksLikeIndexList(child, itemId)) return child
            }
        }
        return null
    }

    /**
     * 行情页那几行（`#rv_markets_hs` 的直接子项）—— 实测「行1 = 指数概览」。
     *
     * 只在这个范围里找，认错的可能就小得多（见上面那条注释）。
     */
    private fun marketRows(root: View, ids: IdCache): List<ViewGroup> {
        val listId = ids.of(MarketTargets.PAGE_LIST_ID)
        if (listId == 0) return emptyList()
        val rv = root.findViewById<ViewGroup>(listId) ?: return emptyList()
        val out = ArrayList<ViewGroup>(rv.childCount)
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) as? ViewGroup ?: continue
            out.add(c)
        }
        return out
    }

    /**
     * 结构判据：「横向的、几个等高的项、每项都有文案、整块不高」的列表。
     *
     * 排除掉两种容易认错的东西：
     *  - **快捷入口网格**（也是横向多格，但每格是「图标 + 文字」）；
     *  - **板块区**（3 列 × 2 行的网格，项数更多且不是横向一排）。
     */
    private fun looksLikeIndexList(v: View, itemId: Int): Boolean {
        val g = v as? ViewGroup ?: return false
        val n = g.childCount

        var labels = 0
        for (i in 0 until n) {
            val c = g.getChildAt(i) ?: return false
            if (itemId != 0) {
                if (c.findViewById<View>(itemId) != null) labels++
            } else if (UiClean.firstLabel(c) != null) {
                labels++
            }
        }

        // 快捷入口（「图标 + 文字」）也是横向多格，排除掉
        var entries = 0
        for (i in 0 until n) if (UiClean.looksLikeEntryItem(g.getChildAt(i))) entries++

        // 判据本身是纯函数（[IndexShape]）—— 认错版块的代价很大（收起了不该收的东西），
        // 而这种错在真机上只能靠肉眼比对，所以把判据抽出来单测。
        val h = UiClean.heightOf(v)
        val heightDp = if (h > 0) Density.pxToDp(v, h) else 0
        return IndexShape.looksLikeIndexOverview(
            childCount = n,
            labeledCount = labels,
            entryItemCount = entries,
            totalHeightDp = heightDp,
        )
    }

    // ------------------------------------------------------------------ 定位行1

    /**
     * [v] 所在的**版块**（= 列表里的那一个子项），返回收起来的那个视图。
     *
     * 为什么收子项而不是收 [v] 自己：
     *
     *  - 版块的内边距都在子项上，只藏 [v] 会留下那段间距；
     *  - 子项才是 `RecyclerView` 的直接子视图，高度归零才真正「不占地方」。
     *
     * 爬不到（父级不是列表）就退回 [v] 自己 —— 宁可收得少一点，也别收错。
     */
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
