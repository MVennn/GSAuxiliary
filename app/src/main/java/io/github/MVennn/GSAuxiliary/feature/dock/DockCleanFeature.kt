package io.github.MVennn.GSAuxiliary.feature.dock

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiClean
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiCleanFeature
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
 *
 * 首轮真机测试的答案比这两种都别扭：它**既不按可见子项重算，也不吃
 * `layoutParams.width`** —— 按「行宽 ÷ 子项数」自己算格子宽度，改格子宽度等于没改。
 * 所以 [fillRow] 里第三招是**把整行按 `子项总数 ÷ 可见子项数` 放大**，
 * 让栅格自己把每格算回原尺寸、五个可见格子正好铺满屏幕。
 */
class DockCleanFeature : UiCleanFeature() {

    override val id = "dock_clean"
    override val title = "底栏隐藏理财入口"

    override val pages = DockCleanTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.dockClean

    /** 预热用：本功能会查的全部 id 名（在进入任意页面时提前解析，见 [UiCleanFeature.idNames]） */
    override val idNames =
        DockCleanTargets.DOCK_ID_CANDIDATES + DockCleanTargets.REMOVE_ITEM_ID_CANDIDATES

    /** 上一次隐藏掉的那一格；用于幂等快速通道 + 后续的均分补救 */
    private var lastHidden: WeakReference<View>? = null

    /** 均分补救的状态机；每一步都要等下一帧量过才知道有没有用（见 [fillRow]） */
    private enum class Reflow { TODO, CELL_WIDTH, ROW_WIDTH, REORDER, SETTLED }

    private var reflow = Reflow.TODO

    /**
     * 均分状态机没收敛就让冷却让路（见 [PageSyncFeature.needsFollowUp]）。
     *
     * 底栏重排天生要连跑几趟：改 weight / 改格子宽 → 等宿主量一次 → 发现末格被挤出
     * 屏幕 → 再加宽整行。每趟都靠下一次布局回调推进，而 1.5 秒冷却正好把那次回调吃掉，
     * 结果底栏停在「留空档」或「末格在屏幕外」的中间态。
     */
    override val needsFollowUp: Boolean get() = reflow != Reflow.SETTLED

    /** 第一次量到的「整行可用宽度」（加宽之后行自身会变，所以要留住原值） */
    private var refRowW = 0

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
        refRowW = 0
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
     * 去掉一格之后，把这一行重新铺满 —— **量到空档才动手，动完下一帧再量**。
     *
     * 每次布局回调都会走到这里（见 [UiCleanFeature]），所以「改完 → 下一帧复查」
     * 天然成立，不会误判。三招按顺序试，每招都要等一帧才知道有没有用：
     *
     *  1. **横向 `LinearLayout`** → 改 `weight` 均分（位置交给宿主自己算）；
     *  2. **等分栅格 → 改每个格子的宽度**；
     *  3. **等分栅格 → 把整行加宽**。这一招是首轮真机测试逼出来的：宿主的
     *     `SingleGridLayout` 既不吃格子宽度、也不按可见子项重算 —— 它按
     *     `行宽 / 子项数` 自己算格子宽度，所以「少一格」只能靠**把行宽按
     *     `子项总数 ÷ 可见子项数` 放大**来抵消：1080 宽 6 格 → 把行放到 1296，
     *     每格就回到 216、五个可见格子正好铺满 1080，被藏的那一格落在屏幕外。
     *
     * 三招都不灵就**保持原样**并记一条警告（**不反复试**，免得每帧都改一遍布局）。
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
        if (row.width <= 0) return                       // 还没量出来，下一帧再看
        if (refRowW <= 0) refRowW = row.width            // 留住原始行宽

        val cells = (0 until row.childCount)
            .map { row.getChildAt(it) }
            .filter { it !== hidden && it.visibility == View.VISIBLE && it.width > 0 }
        if (cells.isEmpty()) {
            GLog.i("$id：底栏只剩被隐藏的那一格，跳过均分")
            reflow = Reflow.SETTLED
            return
        }

        // 「铺开多少」必须按**可见格子的宽度之和**算。
        // 别用「最左到最右的跨度」（`max(right) - min(left)`）——首轮真机测试就是
        // 栽在这上面：去掉中间那格之后，剩下的格子仍然从 0 铺到 1080，
        // 跨度是满的，可中间空着一格（宽 180）没被发现。
        val covered = cells.sumOf { it.width }
        val cell = refRowW / cells.size

        // 铺满 = 宽度够 **且** 没有格子被挤出可视宽度
        //（加宽整行那一招会把最后一格推到屏幕外，只看宽度是看不出来的）
        val inBounds = cells.maxOf { it.right } <= refRowW + cell / 4

        // ① 已经是铺满的 → 宿主自己重排过了（最好的一种，别去干扰它）
        if (covered >= refRowW - cell / 2 && inBounds) {
            val how = when (reflow) {
                Reflow.TODO -> "宿主自己重排，不用我们插手"
                Reflow.CELL_WIDTH -> "改格子宽度生效"
                Reflow.ROW_WIDTH -> "加宽整行 + 挪回末格生效"
                Reflow.REORDER -> "挪到末尾 + 加宽整行生效"
                Reflow.SETTLED -> "已铺满"
            }
            GLog.i("$id：底栏已铺满（$how，${cells.size} 格）")
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

        when (reflow) {
            Reflow.TODO -> {
                // ② 横向 LinearLayout：改 weight 最稳，位置由宿主自己算
                if (row is LinearLayout && row.orientation == LinearLayout.HORIZONTAL) {
                    var n = 0
                    for (v in cells) if (patcher.stretch(v, "底栏其余入口")) n++
                    if (n > 0) {
                        GLog.i("$id：底栏留了 ${refRowW - covered}px 空档，$n 个入口已按 weight 均分整行")
                        reflow = Reflow.CELL_WIDTH
                        return
                    }
                }
                // ③ 等分栅格：先试改格子宽度
                var changed = 0
                for (v in cells) if (patcher.setWidth(v, cell, "底栏入口")) changed++
                GLog.i("$id：底栏留了 ${refRowW - covered}px 空档，按宽度重排 ${cells.size} 格（每格 ${cell}px，改了 $changed 处）")
                reflow = Reflow.CELL_WIDTH
            }

            Reflow.CELL_WIDTH -> {
                // ④ 格子宽度被视觉宿主无视（它按行宽自己算）→ 把整行放大，
                //    再把被藏那格**后面**的格子用负左边距挪回来。
                //
                //    实测：`SingleGridLayout` 按「子项序号 × 新格宽」摆位，所以光加宽
                //    会让最后一格跑到屏幕外面（1080..1296）—— 必须配合负左边距。
                val total = row.childCount                       // 含被藏的那一格
                val widened = refRowW * total / cells.size
                val newCell = widened / total
                val hiddenIdx = row.indexOfChild(hidden)

                patcher.setWidth(row, widened, "底栏整行（补掉被藏的那一格）")
                var shifted = 0
                for (i in hiddenIdx + 1 until row.childCount) {
                    val c = row.getChildAt(i)
                    if (c.visibility != View.VISIBLE) continue
                    if (patcher.setLeftMargin(c, -newCell, "底栏被藏那格之后的入口")) shifted++
                }
                GLog.i(
                    "$id：底栏格子宽度被宿主栅格无视，改成把整行放大 ${refRowW}px -> ${widened}px" +
                        "（子项 $total 格、可见 ${cells.size} 格），并用负左边距挪回被推出去的那 $shifted 格"
                )
                reflow = Reflow.ROW_WIDTH
            }

            Reflow.ROW_WIDTH -> {
                // ⑤ 最后一招：把被藏那格挪到子项末尾，再整体加宽。
                //
                //    宿主栅格按「序号 × 行宽 ÷ 子项数」摆位，所以只要能让它看到的
                //    子项顺序变成「5 个可见 + 1 个被藏」，前 5 格就正好铺满屏幕。
                //    ⚠️ 动顺序可能影响宿主「tab 序号 → 页面」的映射，所以这一步
                //    必须验证点击再决定留不留（见 `docs/FEATURE-UI-CLEAN.md` §7.4）。
                patcher.restore(row)
                for (c in cells) patcher.restore(c)

                val moved = patcher.moveToEnd(hidden)
                val total = row.childCount
                val widened = refRowW * total / cells.size
                val before = if (moved) patcher.setWidth(row, widened, "底栏整行（补掉被藏的那一格）") else false
                GLog.i(
                    "$id：底栏格子宽度与边距都被宿主栅格无视，改成「把被藏那格挪到子项末尾 + 整行放大到 ${widened}px」" +
                        "（挪位 ${if (moved) "成功" else "失败"}，加宽 ${if (before) "已下" else "未下"}）"
                )
                reflow = Reflow.REORDER
            }

            Reflow.REORDER -> {
                // ⑥ 连挪位都不灵 → 全部退回，接受「留一条空档」
                patcher.restoreAll()
                lastHidden = WeakReference(hidden)
                GLog.w(
                    "$id：底栏去格后仍留 ${refRowW - covered}px 空档 —— 试过改格子宽度 / 加宽整行 / " +
                        "挪到末尾，宿主栅格都不吃，已退回原样（不再重试）"
                )
                reflow = Reflow.SETTLED
            }

            Reflow.SETTLED -> Unit
        }
    }
}
