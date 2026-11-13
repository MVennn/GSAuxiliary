package io.github.MVennn.GSAuxiliary.feature.stgrow

import io.github.MVennn.GSAuxiliary.feature.filter.CodeMarketParts
import java.lang.ref.WeakReference

/**
 * 策略详情页「这一页现在勾着哪几只」的跨功能通道。
 *
 * ## 为什么需要它
 *
 * `AIStkStgDetailAct` 的 `lvStks` 上叠了 6 个功能（筛选 / 聚合 / 全选 / 截图并添加 /
 * 自选高亮 / 直达行情）。其中三个会**改到勾选态**，而勾选态是宿主
 * `AbsListView.mCheckStates` 里按**适配器位置**存的：
 *
 * | 谁 | 什么时候改 | 改完通知谁 |
 * | --- | --- | --- |
 * | 筛选（`StgStockFilterFeature`） | 换/更新代理、行集合变了 | 只自己按代码搬一次 |
 * | 聚合（`StrategyMergeFeature`） | 每轮把并集写回 `adapter.data` + notify | **谁也没通知**（2026-11-13 前的洞） |
 * | 全选（`StgSelectAllFeature`） | 逐行 `setItemChecked` + `performItemClick` | 事后校正宿主模型 |
 *
 * 三边各自「按代码搬」都只看得见自己的快照，谁都看不见别人刚改完的结论 ——
 * 这就是「勾着 A/B/C、确认添加的却是别的票」这类问题的土壤。本 object 只做三件事，
 * 不做锁、不接管状态：
 *
 *  1. **键归一**：`代码@市场`（旧口径只取代码，同一代码在不同市场会互相认领）；
 *  2. **勾选快照**：谁把勾选搬完了就 [publish] 一份「此刻勾着哪几只」，后来者可以读；
 *  3. **行集合版本号**：[bumpRowEpoch] / [rowEpoch] 给批量循环一个「行集合换过了」的
 *     廉价判据（比逐项比 data 便宜，也不怕宿主原地改写）。
 *
 * ## 口径
 *
 * - 只发布**读得全**的快照；读不全一律 [publish] null（迁移错的勾选比不迁移更坏）；
 * - 快照带 [FRESH_MS] 时效，过期即视为没说过话 —— 屏幕上的勾选随时可能被用户手改；
 * - 持 [View] 弱引用，页面销毁即失效，不额外记账。
 */
object StgRowSelection {

    /**
     * 快照新鲜期。
     *
     * 取 8 s 与全选侧的捕获时效（`StgSelectAllFeature.captureFreshMs`）对齐：
     * 那边认定「8 s 内的屏幕勾选还算数」，这边就没有理由认更久的。
     */
    const val FRESH_MS = 8_000L

    /** 一次「勾着哪几只」的公开快照 */
    class Snapshot internal constructor(
        val keys: Set<String>,
        owner: Any,
        val at: Long,
        val epoch: Int,
    ) {
        private val ref = WeakReference(owner)

        /** 这份快照说的是哪个列表（通常就是那个 `ListView`；弱引用，页面销毁即失效） */
        val owner: Any? get() = ref.get()

        fun isFresh(now: Long = now()): Boolean = now - at <= FRESH_MS
    }

    /**
     * 当前时刻（毫秒，单调）。
     *
     * 用 [System.nanoTime] 而不是 `SystemClock.elapsedRealtime`：新鲜度只关心「多久以前」，
     * 而 nanoTime 在纯 JVM 单测里也能跑（这一层没有别的东西挡着被单测覆盖）。
     */
    private fun now(): Long = System.nanoTime() / 1_000_000L

    @Volatile private var current: Snapshot? = null

    /** 行集合版本号：只增不减，溢出回绕（判等只用，不做算术） */
    @Volatile private var rowsEpoch: Int = 0

    /**
     * 归一成 `代码@市场`。
     *
     * 市场号缺失时退化成纯代码 —— 宁可少一层区分，也不要造一个永远匹配不上的键。
     */
    fun keyOf(code: String?, market: String?): String? {
        val c = code?.trim().orEmpty()
        if (c.isEmpty()) return null
        val m = market?.trim().orEmpty()
        return if (m.isEmpty()) c else c + "@" + m
    }

    /**
     * `代码.市场` 串 → 键。
     *
     * 与筛选侧同一个切分口径（[CodeMarketParts.split]：无点 = 整串当代码），
     * 免得两边对「只有代码没有市场」这种中间态理解不一致。
     */
    fun keyOfCam(cam: String?): String? {
        val (code, market) = CodeMarketParts.split(cam)
        return keyOf(code, market)
    }

    /**
     * 发布「刚才搬完的结论」。
     *
     * @param owner 哪个列表（`null` 或空集合都等于**撤回**：让后来者别拿旧结论）
     * @param keys  勾着的行键；`null` / 空 → 撤回
     */
    fun publish(owner: Any?, keys: Set<String>?) {
        if (owner == null || keys == null || keys.isEmpty()) {
            current = null
            return
        }
        current = Snapshot(HashSet(keys), owner, now(), rowsEpoch)
    }

    /** 同一个列表上的新鲜快照；不是同一个列表 / 已过期 → null */
    fun freshOf(owner: Any?): Set<String>? {
        val s = current ?: return null
        if (owner == null) return null
        if (s.owner !== owner) return null
        if (!s.isFresh()) return null
        return s.keys
    }

    /** 最近一次快照（不管是谁的、不管新鲜度），排查日志用 */
    fun latest(): Snapshot? = current

    fun rowEpoch(): Int = rowsEpoch

    /** 行集合换过一轮；返回递增后的新值 */
    fun bumpRowEpoch(): Int {
        rowsEpoch = rowsEpoch + 1
        return rowsEpoch
    }

    /** 页面销毁 / 换策略时清干净（快照留着只会误导下一个页面） */
    fun clear() {
        current = null
    }
}
