package io.github.MVennn.GSAuxiliary.feature.strategyselect

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「全选 / 反选」的方向判定与待点击行。
 *
 * ## 为什么值得单测
 *
 * 这是 2026-10-21 真机缺陷的判据：行点击是**取反**的，对已经勾上的行再点一次
 * 就是取消勾选。所以「哪些行要点」必须只包含**状态与目标不同**的行。
 * 旧实现读屏上那颗勾选框（合成点击后可能没重画）来决定点不点，于是把已勾上的行
 * 又点了一遍 —— 用户看到的是「做过一次全选后，下次全选部分选不中」。
 */
class StgSelectAllPlanTest {

    @Test
    fun `一个都没勾时全选所有行`() {
        val before = booleanArrayOf(false, false, false)
        assertTrue(StgSelectAllPlan.wantChecked(before))
        assertContentEquals(intArrayOf(0, 1, 2), StgSelectAllPlan.rowsToClick(before, true))
    }

    @Test
    fun `全部已勾时取消所有行`() {
        val before = booleanArrayOf(true, true, true)
        assertFalse(StgSelectAllPlan.wantChecked(before))
        assertContentEquals(intArrayOf(0, 1, 2), StgSelectAllPlan.rowsToClick(before, false))
    }

    @Test
    fun `部分已勾时只点没勾的那几行`() {
        val before = booleanArrayOf(true, false, true, false)
        assertTrue(StgSelectAllPlan.wantChecked(before))
        assertContentEquals(intArrayOf(1, 3), StgSelectAllPlan.rowsToClick(before, true))
    }

    @Test
    fun `做过一次全选后 已勾的行不会再被点一次`() {
        // 回归：上一轮全选已经勾上 0/1/2，只有 3 因为宿主没重画看起来没勾。
        // 下一轮必须只点 3；点 0/1/2 会把它们取消（真机「部分选不中」）。
        val before = booleanArrayOf(true, true, true, false)
        val want = StgSelectAllPlan.wantChecked(before)
        assertTrue(want)
        assertContentEquals(intArrayOf(3), StgSelectAllPlan.rowsToClick(before, want))
    }

    @Test
    fun `空列表不会产生点击`() {
        val before = BooleanArray(0)
        assertFalse(StgSelectAllPlan.wantChecked(before))
        assertContentEquals(intArrayOf(), StgSelectAllPlan.rowsToClick(before, false))
    }

    // ---------------------------------------------------------- 状态来源

    @Test
    fun `宿主计数为 0 时不必逐行问`() {
        assertEquals(
            StgSelectAllPlan.StateSource.NONE_CHECKED,
            StgSelectAllPlan.stateSource(hostCount = 0, tableChecked = 3, filtered = false),
        )
    }

    @Test
    fun `没有 checked states 时退回探针`() {
        assertEquals(
            StgSelectAllPlan.StateSource.PROBE,
            StgSelectAllPlan.stateSource(hostCount = 5, tableChecked = null, filtered = false),
        )
    }

    @Test
    fun `没有筛选且表与宿主计数一致时信表`() {
        assertEquals(
            StgSelectAllPlan.StateSource.TABLE,
            StgSelectAllPlan.stateSource(hostCount = 5, tableChecked = 5, filtered = false),
        )
    }

    @Test
    fun `没有筛选但表与宿主计数不一致时改用探针`() {
        // 表可能被上一轮 / 宿主清空过；这时候盲目信表会把已勾的行点掉
        assertEquals(
            StgSelectAllPlan.StateSource.PROBE,
            StgSelectAllPlan.stateSource(hostCount = 5, tableChecked = 2, filtered = false),
        )
    }

    @Test
    fun `筛选生效时宿主计数含被筛掉的行 仍信表`() {
        // 有筛选时 表里勾选数 <= 宿主计数 是正常的（被筛掉的选中行不在表里）
        assertEquals(
            StgSelectAllPlan.StateSource.TABLE,
            StgSelectAllPlan.stateSource(hostCount = 23, tableChecked = 19, filtered = true),
        )
    }

    @Test
    fun `宿主计数读不到时按表的可用性决定`() {
        // -1 = 底部计数控件读不到，不能当成「一行都没选」
        assertEquals(
            StgSelectAllPlan.StateSource.PROBE,
            StgSelectAllPlan.stateSource(hostCount = -1, tableChecked = null, filtered = false),
        )
        assertEquals(
            StgSelectAllPlan.StateSource.PROBE,
            StgSelectAllPlan.stateSource(hostCount = -1, tableChecked = 3, filtered = false),
        )
        assertEquals(
            StgSelectAllPlan.StateSource.TABLE,
            StgSelectAllPlan.stateSource(hostCount = -1, tableChecked = 3, filtered = true),
        )
    }

    @Test
    fun `表里一个都没勾但宿主计数非零时改用探针`() {
        // 宿主模型里有选中行，表却是空的 —— 盲目信表会把每一行都点一次
        assertEquals(
            StgSelectAllPlan.StateSource.PROBE,
            StgSelectAllPlan.stateSource(hostCount = 5, tableChecked = 0, filtered = false),
        )
    }

    @Test
    fun `宿主计数为 0 时即使没有表也直接判定全部未选`() {
        assertEquals(
            StgSelectAllPlan.StateSource.NONE_CHECKED,
            StgSelectAllPlan.stateSource(hostCount = 0, tableChecked = null, filtered = true),
        )
    }
}
