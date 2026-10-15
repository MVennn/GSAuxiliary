package io.github.SansKritO4.GSAuxiliary.feature.dock

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.feature.FeatureEnv
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiClean
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiCleanFeature
import java.lang.ref.WeakReference

/**
 * 功能：**主界面底栏去掉「理财」**。
 *
 * ## 做什么
 *
 * 把底栏那一行里文案为「理财」的**那一格**设成 `GONE`，并把剩下的格子重新铺满整行。
 * 不改任何跳转逻辑、不碰任何数据 —— 只是那一格不再占位、不再显示。
 *
 * ## 怎么认（两级兜底）
 *
 *  1. **资源 id 候选**：`DockCleanTargets.DOCK_ID_CANDIDATES` 里命中哪个用哪个；
 *  2. **结构判据**：屏幕底部 25% 区域内、横向排布、3~6 格、每格「图标 + 文字」
 *     的那一行（见 [DockCleanTargets] 的注释）。
 *
 * 认到行之后**按文案**定位「理财」那一格 —— 不按下标，
 * 因为不同账号 / 版本底栏项数可能不同（有的没有「交易」，有的多一个「基金」）。
 *
 * ## 为什么不删掉整个 tab
 *
 * 底栏通常和一个 `ViewPager` 的索引绑死，删掉中间的 tab 会让索引错位
 * （点「交易」进「理财」页）。所以这里**只藏不删**：
 * 那一格从界面上消失，用户看不到也点不到，宿主自己的索引一个都没动。
 */
class DockCleanFeature : UiCleanFeature() {

    override val id = "dock_clean"
    override val title = "底栏隐藏理财项"

    override val pages = DockCleanTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.dockClean

    /** 上一次隐藏掉的那一格；用于幂等快速通道（布局回调会被高频触发） */
    private var lastHidden: WeakReference<View>? = null

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        if (alreadyHidden(lastHidden?.get())) return true

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val dock = findDock(root, activity) ?: return false

        val label = UiClean.findText(dock, DockCleanTargets.REMOVE_TEXT)
        if (label == null) {
            // 底栏里没有这一项（版本差异 / 已被隐藏但视图已回收）→ 本页无事可做
            GLog.i("$id：底栏里没有「${DockCleanTargets.REMOVE_TEXT}」，无需处理")
            return true
        }

        val item = directChildOf(dock, label) ?: label
        // 宽度必须在隐藏**之前**量：GONE 之后宽度就归零了
        val refWidth = item.width.takeIf { it > 0 } ?: 0

        if (patcher.hide(item, "底栏「${DockCleanTargets.REMOVE_TEXT}」")) {
            if (DockCleanTargets.REFLOW_SIBLINGS) reflow(dock, item, refWidth)
        }
        lastHidden = WeakReference(item)
        return true
    }

    // ------------------------------------------------------------------ 定位

    /** 先按候选 id 直取，拿不到再按「底部那一行」的结构判据找 */
    private fun findDock(root: View, activity: Activity): ViewGroup? {
        val ids = UiClean.idsFor(activity, DockCleanTargets.TARGET_PKG)
        val byId = ids.firstOf(DockCleanTargets.DOCK_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v is ViewGroup && v.isShown) return v
        }
        return findBottomRow(root)
    }

    /** 屏幕底部区域内、最靠下的那一行「入口格子」 */
    private fun findBottomRow(root: View): ViewGroup? {
        val rootH = UiClean.heightOf(root)
        if (rootH <= 0) return null
        var best: ViewGroup? = null
        var bestTop = -1
        UiClean.walk(root) { v ->
            if (v is ViewGroup && v.isShown && isTabRow(v)) {
                val r = UiClean.rectIn(root, v)
                if (r != null && r.top >= rootH * DockCleanTargets.BOTTOM_BAND_RATIO && r.top > bestTop) {
                    best = v
                    bestTop = r.top
                }
            }
            false
        }
        return best
    }

    /** 「底栏那一行」的判据：3~6 格、每格都是「图标 + 文字」、整行不高 */
    private fun isTabRow(g: ViewGroup): Boolean {
        val n = g.childCount
        if (n < DockCleanTargets.MIN_ITEMS || n > DockCleanTargets.MAX_ITEMS) return false

        val h = UiClean.heightOf(g)
        if (h > 0 && h > UiClean.dp(g, DockCleanTargets.MAX_BAR_HEIGHT_DP)) return false

        var entries = 0
        for (i in 0 until n) if (UiClean.looksLikeEntryItem(g.getChildAt(i))) entries++
        return entries >= n - 1
    }

    /** 从 [v] 上溯到「直属于 [row] 的那个子视图」 */
    private fun directChildOf(row: ViewGroup, v: View): View? {
        var cur: View = v
        var guard = 0
        while (guard++ < 12) {
            val p = cur.parent as? ViewGroup ?: return null
            if (p === row) return cur
            cur = p
        }
        return null
    }

    // ------------------------------------------------------------------ 铺满

    /**
     * 去掉一格之后，让同一行里**尺寸相近**的其它格子重新均分整行。
     *
     * 只在行是横向 `LinearLayout` 时做：这种情况下去掉一格会留下一条明显的空档；
     * 换成 `ConstraintLayout` 的话子项本来就在链上，`GONE` 之后宿主自己会重排，
     * 我们插手反而可能把链搞散（`StockDetailBarFeature` 踩过这个坑）。
     */
    private fun reflow(row: ViewGroup, hidden: View, hiddenWidth: Int) {
        if (row !is LinearLayout || row.orientation != LinearLayout.HORIZONTAL) return
        val peers = UiClean.peersOf(hidden, hiddenWidth)
        if (peers.isEmpty()) return
        var n = 0
        for (p in peers) if (patcher.stretch(p, "底栏其余入口")) n++
        if (n > 0) GLog.i("$id：底栏 $n 个入口已重新均分整行")
    }
}
