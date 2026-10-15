package io.github.SansKritO4.GSAuxiliary.feature.dock

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.feature.FeatureEnv
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiClean
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiCleanFeature
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * 功能：**主界面底栏去掉「理财」那一格**。
 *
 * ## 做什么
 *
 * 把底栏那一行里文案为「理财」的**那一格**设成 `GONE`，并把剩下的格子重新铺满整行。
 * 不改任何跳转逻辑、不碰任何数据 —— 只是那一格不再占位、不再显示。
 *
 * ## 怎么认（真机实测见 [DockCleanTargets]）
 *
 *  1. **那一格自己的 id**：`cb_main_tab_money` 命中直接藏（8.6.0 实测）；
 *  2. **认行 + 按文案找**：先按 `fl_main_tab` 或结构判据认出底栏那一行，
 *     再在行里找文案为「理财」的格子，上溯到**直属于行**的那个子视图（实测是
 *     `LottieTabView`，文字 `TextView` 自己没有 id）。
 *
 * 不按下标：不同账号 / 版本底栏项数可能不同（有的没有「交易」，有的多一个「基金」）。
 *
 * ## 为什么不删掉整个 tab
 *
 * 底栏通常和一个 `ViewPager` 的索引绑死，删掉中间的 tab 会让索引错位
 * （点「交易」进「理财」页）。所以这里**只藏不删**：
 * 那一格从界面上消失，用户看不到也点不到，宿主自己的索引一个都没动。
 *
 * ## 「均分铺满」为什么不能直接改 weight
 *
 * 实测底栏根容器是宿主自己的 `com.guosen.commonui.SingleGridLayout`
 * —— 一个**等分栅格**，不是 `LinearLayout`，`weight` 那套根本不存在。
 * 去掉一格之后有两种可能：宿主按可见子项重算格子宽度（自己就铺满了），
 * 或者拿 `getChildCount()` 当列数、让 `GONE` 的那格继续占位（留一条空档）。
 * 两种都合理，**只有跑起来量一下才知道** —— 所以这里不猜，见 [fillRow]。
 */
class DockCleanFeature : UiCleanFeature() {

    override val id = "dock_clean"
    override val title = "底栏隐藏理财项"

    override val pages = DockCleanTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.dockClean

    /** 上一次隐藏掉的那一格；用于幂等快速通道 + 后续的均分补救 */
    private var lastHidden: WeakReference<View>? = null

    /** 均分补救的状态：还没试过 / 试过了（等下一帧验证）/ 不用再管了 */
    private enum class Reflow { TODO, APPLIED, SETTLED }

    private var reflow = Reflow.TODO

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        val hidden = lastHidden?.get()
        if (alreadyHidden(hidden)) {
            // 走到这里说明「隐藏之后又布局过一帧」：宿主要么自己把剩下的格子重排了，
            // 要么留了空档。量一次就能分辨，见 fillRow。
            if (reflow != Reflow.SETTLED) fillRow(hidden!!)
            return true
        }

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true

        // ① 那一格自己的 id（实测 cb_main_tab_money）—— 命中直接藏
        val ids = UiClean.idsFor(activity, DockCleanTargets.TARGET_PKG)
        val itemId = ids.firstOf(DockCleanTargets.REMOVE_ITEM_ID_CANDIDATES)
        if (itemId != 0) {
            val v = root.findViewById<View>(itemId)
            if (v != null && v.isShown) {
                hideItem(v, "id 命中")
                return true
            }
        }

        // ② 认底栏那一行，再按文案找那一格
        val dock = findDock(root, activity) ?: return false
        val label = UiClean.findText(dock, DockCleanTargets.REMOVE_TEXT)
        if (label == null) {
            // 底栏里没有这一项（版本差异 / 已被隐藏但视图已回收）→ 本页无事可做
            GLog.i("$id：底栏里没有「${DockCleanTargets.REMOVE_TEXT}」，无需处理")
            return true
        }
        hideItem(directChildOf(dock, label) ?: label, "按文案找到")
        return true
    }

    private fun hideItem(item: View, how: String) {
        patcher.hide(item, "底栏「${DockCleanTargets.REMOVE_TEXT}」格（$how）")
        lastHidden = WeakReference(item)
        reflow = Reflow.TODO
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

    // ------------------------------------------------------------------ 均分铺满

    /**
     * 去掉一格之后，把这一行重新铺满 —— **量到空档才动手**。
     *
     * 每次布局回调都会走到这里（[UiCleanFeature] 全局布局回调驱动），所以
     * 很自然地可以做「改完 → 下一帧再量一次」的验证，不会误判：
     *
     *  1. **已经铺满** → 宿主自己按可见子项重排过了，什么都不做（这是最好的一种，
     *     别去干扰它）；
     *  2. **还没量出来**（容器宽度为 0）→ 保持 [Reflow.TODO]，下一帧再来；
     *  3. **确实留了空档** → 试一次补救（见下），状态转 [Reflow.APPLIED]；
     *  4. **补救之后仍然留空档** → 说明宿主栅格不吃 `layoutParams`，
     *     记一条警告、保持原样退出（**不反复试**，免得每帧都改一遍布局）。
     *
     * 补救本身分两种版式，顺序即优先级：
     *
     *  - 横向 `LinearLayout` → 改 `weight` 均分（位置交给宿主自己算）；
     *  - 等分栅格 → 宽度改成均分值，**并且只有「位置本来就靠边距排」时才动边距** ——
     *    位置由布局自己算的栅格再叠一层边距会被推到两倍。
     *
     * 子项宽度不一致的行（分隔线、居中凸起按钮混在里面）**一律不碰**：
     * 改坏宿主排版比留一条空档严重得多。
     */
    private fun fillRow(hidden: View) {
        val row = hidden.parent as? ViewGroup
        if (row == null) {
            reflow = Reflow.SETTLED
            return
        }
        val containerW = row.width
        if (containerW <= 0) return                     // 还没量出来，下一帧再看

        val cells = (0 until row.childCount)
            .map { row.getChildAt(it) }
            .filter { it !== hidden && it.visibility == View.VISIBLE && it.width > 0 }
        if (cells.isEmpty()) {
            GLog.i("$id：底栏只剩被隐藏的那一格，跳过均分")
            reflow = Reflow.SETTLED
            return
        }

        val cell = containerW / cells.size
        val covered = cells.sumOf { it.width }

        // ① 已经是铺满的 → 宿主自己重排过了
        if (covered >= containerW - cell / 2) {
            GLog.i(
                if (reflow == Reflow.APPLIED) "$id：底栏已铺满（补救生效，${cells.size} 格）"
                else "$id：底栏已铺满（宿主自己重排，不用我们插手）"
            )
            reflow = Reflow.SETTLED
            return
        }

        // ④ 补救过了还是不行
        if (reflow == Reflow.APPLIED) {
            GLog.w(
                "$id：底栏去格后仍留 ${containerW - covered}px 空档，" +
                    "宿主栅格不吃 layoutParams —— 保持原样（不再重试）"
            )
            reflow = Reflow.SETTLED
            return
        }

        // 只处理「原本等宽平铺」这一种版式
        val w0 = cells.first().width
        if (cells.any { abs(it.width - w0) > w0 / 4 }) {
            GLog.i("$id：底栏格子宽度不一致（${cells.joinToString { it.width.toString() }}），不强行均分")
            reflow = Reflow.SETTLED
            return
        }

        // ② 横向 LinearLayout：改 weight 最稳，位置由宿主自己算
        if (row is LinearLayout && row.orientation == LinearLayout.HORIZONTAL) {
            var n = 0
            for (v in cells) if (patcher.stretch(v, "底栏其余入口")) n++
            if (n > 0) {
                GLog.i("$id：底栏留了 ${containerW - covered}px 空档，$n 个入口已按 weight 均分整行")
                reflow = Reflow.APPLIED
                return
            }
        }

        // ③ 等分栅格：宽度重算；位置靠边距排的才动边距
        val byMargin = cells.any {
            (it.layoutParams as? MarginLayoutParams)
                ?.let { lp -> lp.leftMargin != 0 || lp.marginStart != 0 } ?: false
        }
        var changed = 0
        for ((i, v) in cells.withIndex()) {
            if (patcher.setWidth(v, cell, "底栏入口")) changed++
            if (byMargin) patcher.setLeftMargin(v, i * cell, "底栏入口")
        }
        GLog.i(
            "$id：底栏留了 ${containerW - covered}px 空档，按" +
                "${if (byMargin) "宽度 + 左边距" else "宽度"}重排 ${cells.size} 格" +
                "（每格 ${cell}px，改了 $changed 处）"
        )
        reflow = Reflow.APPLIED
    }
}
