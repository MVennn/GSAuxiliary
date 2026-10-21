package io.github.MVennn.GSAuxiliary.feature.strategyselect

/**
 * 「全选 / 反选」的方向判定与待点击行 —— 纯逻辑，便于单测。
 *
 * ## 方向判据
 *
 * **只要还有一行没勾上，这次就是「全选」**；全部已勾才是「反选」。
 *
 * 不能用底部「已选 N」当判据（`StgSelectAllFeature#toggleAll` 的注释里有完整原因）：
 *  - 筛选生效时，计数覆盖的是宿主的选中模型（含被筛掉的行），与列表里的行数不是一回事；
 *  - 计数与屏上勾选框可能不同步（合成点击后宿主不重画部分勾选框）。
 *
 * ## 为什么「已符合目标的行」不能再点
 *
 * 行点击是**取反**（`AbsListView.performItemClick` 按 position 取反写 checked states，
 * 宿主的行点击监听也按「当前是否已选」做增删）。对已经勾上的行再点一次 = 取消勾选。
 * 2026-10-21 真机现象「做过一次全选后，下次全选部分选不中」就是这么来的：
 * 上一轮把行勾上了，屏上却没画出来，下一轮读屏上状态以为没勾，又点了一次。
 */
internal object StgSelectAllPlan {

    /** 「这一行的状态从哪来」 */
    enum class StateSource {
        /** 宿主计数为 0：一行都没选中，不必逐行问 */
        NONE_CHECKED,

        /** 框架的 checked states（与「确认添加」读的是同一份表） */
        TABLE,

        /** 逐行现绑探针视图（checked states 不可用或与宿主计数矛盾） */
        PROBE,
    }

    /**
     * 状态来源的优先级。
     *
     * @param hostCount 底部「已选 N」（宿主维护的全量选中数；读不到传 -1）
     * @param tableChecked checked states 里勾着的行数；宿主不用多选模式时传 null
     * @param filtered 当前是否在筛选（有筛选时宿主计数还包含被筛掉的行，不能要求两者相等）
     */
    fun stateSource(hostCount: Int, tableChecked: Int?, filtered: Boolean): StateSource = when {
        // 计数为 0 优先于 checked states：换适配器 / 页面重建后，按 position 存的表
        // 可能留着上一份的残值；而宿主计数是它每次点击都会重写的。若反过来信表，
        // 会把「其实一行没选」判成已选，于是点「全选」变成对已勾行再点一次 = 取消。
        hostCount == 0 -> StateSource.NONE_CHECKED
        tableChecked == null -> StateSource.PROBE
        filtered || tableChecked == hostCount -> StateSource.TABLE
        else -> StateSource.PROBE
    }

    /** 这次点击要落到哪个状态：还有没勾上的行 → 全部勾上；全勾上了 → 全部取消 */
    fun wantChecked(before: BooleanArray): Boolean = before.any { !it }

    /** 需要点击的行下标（状态与目标不同的行），保序 */
    fun rowsToClick(before: BooleanArray, wantChecked: Boolean): IntArray {
        var n = 0
        for (b in before) if (b != wantChecked) n++
        val out = IntArray(n)
        var k = 0
        for (i in before.indices) if (before[i] != wantChecked) out[k++] = i
        return out
    }
}
