package io.github.MVennn.GSAuxiliary.feature

/**
 * 布局回调的**限速决策** —— 纯函数，不碰任何 Android 对象。
 *
 * ## 为什么要抽出来
 *
 * 限速这段逻辑是本项目**最贵的一条教训**的所在地（`docs/CODE-RULES.md` 规则 2.3）：
 *
 * > 限速 / 冷却把回调直接 `return` 掉 → 底栏重排停在中间态、详情页入口不出现。
 * > 3.2.0 的 1.5 秒动作冷却就是这么来的，2026-10-19 才改成「延后不丢弃」。
 *
 * 它的正确性只有两条：
 *
 *  1. **延后，绝不丢弃** —— 布局回调是「页面变了」的**唯一**信号，
 *     而宿主恰恰是在我们改完视图之后立刻布局一趟（新建的分页、重建的底栏、
 *     补挂上去的子视图都在那一趟里上来）。丢掉它，界面就停在这一轮的样子。
 *  2. **有待收敛时让路** —— 多趟状态机（底栏重排那种「改一趟、量一趟」）不能被
 *     冷却掐住，否则停在中间态。
 *
 * 这两条以前只有真机能验（看界面动不动）。抽成纯函数后**可以单测**，
 * 而且这两条恰好是**改起来最容易写错**的 —— 谁把 `Defer` 写成 `Drop`，
 * 就是把 BUG1 原样装回去。
 *
 * ## 决策表
 *
 * | 情况 | 决策 |
 * |---|---|
 * | 有待收敛的下一趟（`followUp`）+ 刚动过视图 | **Run** —— 冷却让路 |
 * | 有待收敛的下一趟 + 没刚动过 | Run |
 * | 刚动过视图（`actedAt` 在冷却窗口内） | **Defer(剩余窗口)** |
 * | 连续认不出（`miss >= 阈值`）且离上次跑不到节流窗口 | **Defer(剩余窗口)** |
 * | 其余 | **Run** |
 */
internal object SyncGate {

    /** 这一趟要怎么办 */
    sealed interface Decision {
        /** 现在就跑 */
        object Run : Decision

        /** 稍后再来（**不是丢弃**）—— [delayMs] 毫秒后补跑 */
        data class Defer(val delayMs: Long) : Decision
    }

    /**
     * 这一次布局回调该怎么处理。
     *
     * @param now            当前时刻（毫秒，单调时钟）
     * @param lastActionAt   上一轮 sync **真动了视图**的时刻（0 = 从没动过）
     * @param lastRunAt      上一轮 sync 的时刻（0 = 从没跑过）
     * @param missScans      连续「认不出目标控件」的次数
     * @param lastScanOk     上一轮是否认出了目标控件
     * @param followUp       是否有待收敛的下一趟（多趟状态机）
     * @param actionCooldownMs 动作冷却窗口
     * @param layoutThrottleMs 认不出时的节流窗口
     * @param missScanThreshold 连续认不出多少次才启用节流
     */
    fun decide(
        now: Long,
        lastActionAt: Long,
        lastRunAt: Long,
        missScans: Int,
        lastScanOk: Boolean,
        followUp: Boolean,
        actionCooldownMs: Long,
        layoutThrottleMs: Long,
        missScanThreshold: Int,
    ): Decision {
        // ① 动作限速：**有待收敛的下一趟就不进这道闸** ——
        //    多趟状态机要按「改一趟、量一趟」推进，掐住它就停在中间态（BUG1）。
        if (!followUp) {
            val actWait = actionCooldownMs - (now - lastActionAt)
            if (actWait > 0) return Decision.Defer(actWait)
        }

        // ② 认不出限速：连续认不出来才生效。
        //    ⚠️ 多趟状态机**也照常限速** —— 认不出时更该慢下来。
        if (!lastScanOk && missScans >= missScanThreshold) {
            val missWait = layoutThrottleMs - (now - lastRunAt)
            if (missWait > 0) return Decision.Defer(missWait)
        }

        return Decision.Run
    }
}
