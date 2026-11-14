package io.github.MVennn.GSAuxiliary.feature.dock

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.DockEntryStore
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiClean
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiCleanFeature
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * 功能：**主界面底栏显示哪几个入口**。
 *
 * ## 做什么
 *
 * 按配置把底栏里不需要的格子（首页 / 行情 / 自选 / 交易 / 理财 / 账户）设成 `GONE`，
 * 再把剩下的格子重新铺满整行。不改任何跳转逻辑、不碰任何数据。
 *
 * 配置是一份 JSON（见 [DockEntryStore]），键 [io.github.MVennn.GSAuxiliary.core.ConfigKeys.DOCK_ENTRIES]。
 * 升级前的布尔开关 `dock_clean` 只当**迁移源**：新键还没写过（出厂空串）时，
 * 「开」等价于藏「理财」一格 —— 与升级前的行为逐像素一致。
 *
 * ## 怎么认（真机实测见 [DockCleanTargets]）
 *
 * 六格的候选 id 都在 [DockCleanTargets.ENTRIES] 里；id 解析不到（换版本、换皮肤）时
 * 退回「按文案找 → 上溯到直属于行的那个子视图」（实测是 `LottieTabView`，
 * 文字 `TextView` 自己没有 id）。不按下标：不同账号 / 版本底栏项数可能不同。
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
 * 它按「行宽 ÷ 子项数」自己算格子宽度，`layoutParams.width` 和 `margin` 都不吃
 * （2026-10-19 真机实测），所以藏掉若干格之后只有一招管用：
 * **把被藏的格子挪到子项末尾，再把整行按「子项总数 ÷ 可见格数」放大** —— 见 [fillRow]。
 *
 * 挪位只动**被藏起来**的那几格（`GONE`，点不到也画不出），可见格的相对顺序不变，
 * 于是宿主「第几个 tab 是第几页」的映射不受影响。
 */
class DockCleanFeature : UiCleanFeature() {

    override val id = "dock_clean"
    override val title = "底栏入口自定义"

    override val pages = DockCleanTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.dockClean

    /** 预热用：本功能会查的全部 id 名（在进入任意页面时提前解析，见 [UiCleanFeature.idNames]） */
    override val idNames =
        DockCleanTargets.DOCK_ID_CANDIDATES +
            DockCleanTargets.ENTRIES.flatMap { it.idCandidates.toList() }.toTypedArray()

    /** 上一趟真正藏起来的那些格子（按 key 拼成的签名），用来判断「配置变没变」 */
    private var appliedSig: String? = null

    /** 上一趟处理过的那一行：实例换了就说明宿主重建了底栏，宽度与状态机都要重来 */
    private var lastRow: WeakReference<View>? = null

    /** 重排的状态机；每改一手都要等下一帧量过才知道有没有用（见 [fillRow]） */
    private enum class Reflow { TODO, APPLIED, SETTLED }

    private var reflow = Reflow.TODO

    /** 上一趟下的手没生效：接受空档，不再试第二遍（否则每帧都改一遍布局） */
    private var reflowFailed = false

    /** 第一次量到的整行可用宽度（加宽之后行自身会变，所以要留住原值） */
    private var refRowW = 0

    /**
     * 均分没收敛就让限速闸让路（见 [PageSyncFeature.needsFollowUp]）。
     *
     * 底栏重排天生要连跑几趟：藏格 → 等宿主量一次 → 发现还留空档 → 挪位 + 加宽整行。
     * 每趟都靠下一次布局回调推进，而限速闸一延后就是 200ms 一拍 ——
     * 用户看到的是「格子没了、位置过一会儿才腾出来」。
     * （2026-10-19 起限速只延后、不丢弃，覆写这一条只影响手感，见 docs/FEATURE-UI-CLEAN.md 第 2.4 节。）
     */
    override val needsFollowUp: Boolean get() = reflow != Reflow.SETTLED

    // ------------------------------------------------------------------ 入口

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        // 旧布尔开关只当迁移源：新键没写过（出厂空串）时「开」= 藏「理财」，与升级前一致
        val desired = DockEntryStore.decode(env.config.dockEntries, env.config.dockClean)

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val row = findDock(root, activity) ?: return hideByIds(root, activity, desired)
        return reconcile(row, activity, desired)
    }

    // ------------------------------------------------------------------ 落配置

    /**
     * 把「期望藏起来的集合」落到当前这一行上。
     *
     * 两个层次分开：
     *
     *  1. **藏 / 显**：配置变了就把本功能留下的改动整体退回再从干净底子重做一遍；
     *     配置没变就每趟补一次 hide（宿主重建视图、或我们自己退回重排，都可能把格子放回来）。
     *  2. **重排**：见 [fillRow]，只有「藏着的格子非空」时才有意义。
     */
    private fun reconcile(row: ViewGroup, activity: Activity, desired: Set<String>): Boolean {
        if (row.width <= 0) return false                  // 还没量出来 → 下一帧再来
        val cells = resolveCells(row, activity)
        if (cells.isEmpty()) {
            GLog.i("$id：底栏 ${row.childCount} 格里一格都没认出来，跳过")
            return true
        }

        if (lastRow?.get() !== row) {
            // 宿主重建了底栏（切页 / 转屏）：宽度和状态机都得从头量
            lastRow = WeakReference(row)
            refRowW = 0
            reflow = Reflow.TODO
            reflowFailed = false
            appliedSig = null
        }
        rememberRowWidth(row)

        // 至少留一格：界面侧已经拦住了，这里兜底手改配置 / 旧配置残留
        var hidden = LinkedHashSet(desired.filter { it in cells.keys })
        if (hidden.size >= cells.size) {
            GLog.w("$id：配置要求藏起全部 ${cells.size} 格，按「一个都不藏」处理（底栏不允许全空）")
            hidden = LinkedHashSet()
        }

        val sig = hidden.joinToString(",")
        if (sig != appliedSig) {
            // 配置变了（或第一次跑）：先把本功能留下的改动整体退回，再从干净底子重做一遍
            patcher.restoreAll()
            reflow = Reflow.TODO
            reflowFailed = false
            appliedSig = sig
            val missing = desired.filter { it !in cells.keys }
            if (missing.isNotEmpty()) {
                GLog.i("$id：底栏里没找到 ${missing.joinToString { DockCleanTargets.labelOf(it) }}，这几项跳过")
            }
            for (key in hidden) {
                val cell = cells[key] ?: continue
                patcher.hide(cell, why(key))
            }
            if (hidden.isNotEmpty()) GLog.i("$id：底栏藏起 ${hidden.size} 格（$sig）")
        } else {
            for (key in hidden) {
                val cell = cells[key] ?: continue
                patcher.hide(cell, why(key))         // 已经在 GONE 时 patcher 自己会跳过
            }
        }

        if (hidden.isEmpty()) {
            reflow = Reflow.SETTLED
            return true
        }
        fillRow(row, cells, hidden)
        return true
    }

    // ------------------------------------------------------------------ 定位

    /** 认不出整行时的退路：按六格自己的 id 直取，藏到一格就算处理过 */
    private fun hideByIds(root: View, activity: Activity, desired: Set<String>): Boolean {
        if (desired.isEmpty()) return true
        val ids = UiClean.idsFor(activity, DockCleanTargets.TARGET_PKG)
        var hit = 0
        for (entry in DockCleanTargets.ENTRIES) {
            if (entry.key !in desired) continue
            val id = ids.firstOf(entry.idCandidates)
            if (id == 0) continue
            val v = root.findViewById<View>(id) ?: continue
            if (patcher.hide(v, "${why(entry.key)}（按 id 直取）")) hit++
        }
        if (hit == 0) return false                       // 一格都没藏到 → 交给基类累计 / 打视图树快照
        GLog.i("$id：没认出底栏整行，按 id 直接藏了 $hit 格")
        return true
    }

    /** key → 直属于底栏那一行的格子视图（先 id，再文案） */
    private fun resolveCells(row: ViewGroup, activity: Activity): Map<String, View> {
        val ids = UiClean.idsFor(activity, DockCleanTargets.TARGET_PKG)
        val out = LinkedHashMap<String, View>(DockCleanTargets.ENTRIES.size)
        for (entry in DockCleanTargets.ENTRIES) {
            val id = ids.firstOf(entry.idCandidates)
            if (id != 0) {
                val hit = row.findViewById<View>(id)
                val cell = hit?.let { directChildOf(row, it) }
                if (cell != null) {
                    out[entry.key] = cell
                    continue
                }
            }
            // 文案兜底：文字 TextView 没有资源 id（见 DockCleanTargets 的视图树）
            val label = UiClean.findText(row, entry.label)
            val cell = label?.let { directChildOf(row, it) }
            if (cell != null) out[entry.key] = cell
        }
        return out
    }

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

    /**
     * 记下「原始行宽」。
     *
     * 加宽整行之后 `row.width` 就不再是原值了，所以只在**第一次见到这一行**时记；
     * 行实例换了（宿主重建底栏）重记，没藏任何格子时行宽就是屏宽、转屏也会跟上。
     */
    private fun rememberRowWidth(row: ViewGroup) {
        val w = row.width
        if (w <= 0) return
        if (refRowW <= 0) {
            refRowW = w
            return
        }
        if (reflow == Reflow.SETTLED && patcher.size == 0 && abs(w - refRowW) > refRowW / 20) refRowW = w
    }

    private fun why(key: String): String = "底栏「${DockCleanTargets.labelOf(key)}」格"

    // ------------------------------------------------------------------ 均分铺满

    /**
     * 藏掉若干格之后，把这一行重新铺满 —— **量到空档才动手，动完下一帧再量**。
     *
     * 「铺满」的判据是**逐格对位**：第 j 个可见格的左边界与宽度都要落在
     * 「第 j 格 × 每格宽」这一格槽位上（容差 1/8 格）。只比总宽度是不够的 ——
     * 藏掉中间那格之后，剩下的格子仍然从 0 铺到 1080，跨度是满的，可中间空着一格。
     *
     * 两招，每招都要等一帧才知道有没有用：
     *
     *  1. **横向 LinearLayout** → 改 weight 均分（位置交给宿主自己算）；
     *  2. **等分栅格**（8.6.0 实测就是 SingleGridLayout）→ 把被藏的格子挪到子项末尾，
     *     再把整行按「子项总数 ÷ 可见格数」放大。宿主按「行宽 ÷ 子项数」自己算格子宽度，
     *     所以 1080 宽 6 格藏掉 2 格 → 把行放到 1620，每格回到 270，
     *     4 个可见格正好铺满 1080，被藏的 2 格落在屏幕外。
     *
     * 都不灵就**退回重排改动、保住隐藏、接受空档**，并记一条警告（**不反复试**）。
     * 子项宽度不一致的行（分隔线、居中凸起按钮混在里面）**一律不碰**：
     * 改坏宿主排版比留一条空档严重得多。
     */
    private fun fillRow(row: ViewGroup, cells: Map<String, View>, hidden: Set<String>) {
        val visible = (0 until row.childCount)
            .map { row.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE }
        if (visible.isEmpty()) {
            GLog.w("$id：底栏连一个可见入口都没有，放弃重排")
            reflow = Reflow.SETTLED
            return
        }
        if (visible.any { it.width <= 0 }) return       // 还没量出来，下一帧再看
        val k = visible.size
        val newCell = refRowW / k
        if (newCell <= 0) {
            reflow = Reflow.SETTLED
            return
        }

        // ① 已经铺满 → 宿主自己重排过了（最好的一种，别去干扰它）
        val settled = visible.withIndex().all { (j, v) ->
            abs(v.left - j * newCell) <= newCell / 8 && abs(v.width - newCell) <= newCell / 8
        }
        if (settled) {
            if (reflow != Reflow.SETTLED) {
                val how = when (reflow) {
                    Reflow.TODO -> "宿主自己重排，不用我们插手"
                    Reflow.APPLIED -> "挪位 + 加宽整行生效"
                    Reflow.SETTLED -> "已铺满"
                }
                GLog.i("$id：底栏已铺满（$how，$k 格，每格 ${newCell}px）")
            }
            reflow = Reflow.SETTLED
            return
        }
        if (reflowFailed) return

        // 只处理「原本等宽平铺」这一种版式
        val w0 = visible.first().width
        if (visible.any { abs(it.width - w0) > w0 / 4 }) {
            GLog.i("$id：底栏格子宽度不一致（${visible.joinToString { it.width.toString() }}），不强行均分")
            reflow = Reflow.SETTLED
            return
        }

        when (reflow) {
            Reflow.TODO -> {
                // ② 横向 LinearLayout：改 weight 最稳，位置由宿主自己算
                if (row is LinearLayout && row.orientation == LinearLayout.HORIZONTAL) {
                    var stretched = 0
                    for (v in visible) if (patcher.stretch(v, "底栏入口")) stretched++
                    if (stretched > 0) {
                        GLog.i("$id：底栏 $k 个入口按 weight 均分整行")
                        reflow = Reflow.APPLIED
                        return
                    }
                }
                // ③ 等分栅格：被藏的挪到末尾 + 整行按 n ÷ k 放大
                val n = row.childCount
                var moved = 0
                for (key in hidden) {
                    val c = cells[key] ?: continue
                    if (patcher.moveToEnd(c)) moved++
                }
                val widened = refRowW * n / k
                patcher.setWidth(row, widened, "底栏整行（补掉藏起来的 ${hidden.size} 格）")
                GLog.i(
                    "$id：底栏 $n 格藏起 ${hidden.size} 格，整行 ${refRowW}px -> ${widened}px" +
                        "（每格 ${newCell}px，挪位 $moved 处）"
                )
                reflow = Reflow.APPLIED
            }

            Reflow.APPLIED -> {
                // ④ 上一趟下的手没生效 → 退回重排改动，接受「留一条空档」。
                //
                // 退回重排不能连「藏起来」一起撤（2026-10-19 修）：restoreAll
                // 会把格子还原成可见 —— 而下一趟 applyTo 又会重新藏，于是变成
                // 「藏 → 撤 → 再藏」的死循环：用户看到格子闪、开关等于没生效、日志一直刷。
                // 本职是把不需要的格子藏掉，重排只是补救，所以撤完重排再把隐藏补回来。
                patcher.restoreAll()
                for (key in hidden) {
                    val cell = cells[key] ?: continue
                    patcher.hide(cell, "${why(key)}（退回重排后保住隐藏）")
                }
                reflow = Reflow.SETTLED
                reflowFailed = true
                GLog.w(
                    "$id：底栏藏掉 ${hidden.size} 格后宿主栅格不吃重排（宽度 / 顺序都没变），" +
                        "已退回重排改动的原样；那 ${hidden.size} 格仍然隐藏，不再重试"
                )
            }

            Reflow.SETTLED -> Unit
        }
    }
}
