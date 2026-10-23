package io.github.MVennn.GSAuxiliary.feature.strategyselect

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ---------------------------------------------------------- 补点轮

    @Test
    fun `补点轮宿主计数为 0 但表里有勾时按宿主模型核对`() {
        // 合成点击比宿主刷新快：计数还没跟上时若照旧判「一行没选」，
        // 会把整单选中的行再点一遍 → 全部取消（比不补还糟）。
        // 但也不能只信表：计数与表分叉可能是宿主模型真的漏了行。退回探针按模型判 ——
        // 模型其实已勾上时探针读到 true，不会再点；真漏了才会补。
        assertEquals(
            StgSelectAllPlan.StateSource.PROBE,
            StgSelectAllPlan.stateSource(hostCount = 0, tableChecked = 5, filtered = false, retry = true),
        )
    }

    @Test
    fun `补点轮没有表时退回探针`() {
        assertEquals(
            StgSelectAllPlan.StateSource.PROBE,
            StgSelectAllPlan.stateSource(hostCount = 0, tableChecked = null, filtered = false, retry = true),
        )
    }

    @Test
    fun `补点轮筛选生效时仍信表`() {
        assertEquals(
            StgSelectAllPlan.StateSource.TABLE,
            StgSelectAllPlan.stateSource(hostCount = 23, tableChecked = 19, filtered = true, retry = true),
        )
    }

    @Test
    fun `补点轮表与宿主计数不一致也信表`() {
        // 有筛选时宿主计数含被筛掉的行，对不上是常态；表是我们自己刚预置、刚点过的，
        // 确认添加读的正是这份表 —— 拿计数判会反复点同一批行。
        assertEquals(
            StgSelectAllPlan.StateSource.TABLE,
            StgSelectAllPlan.stateSource(hostCount = 23, tableChecked = 2, filtered = true, retry = true),
        )
    }

    @Test
    fun `补点轮没有筛选且表与计数分叉时按宿主模型补`() {
        // 没有筛选时两套坐标是同一套：表里 5 行勾着、宿主只说 2 行 —— 宿主模型漏了 3 行
        // （真机「宿主吞掉某次点击」）。表已经被 performItemClick 写成目标态，
        // 只信表就永远发现不了漏选，必须退回探针按宿主模型找。
        assertEquals(
            StgSelectAllPlan.StateSource.PROBE,
            StgSelectAllPlan.stateSource(hostCount = 2, tableChecked = 5, filtered = false, retry = true),
        )
    }

    // ---------------------------------------------------------- 宿主选中模型

    @Test
    fun `按宿主模型算行状态 忽略市场后缀`() {
        val states = StgSelectAllPlan.statesFromCodes(
            listOf("600000", "002368", "920575"),
            listOf("600000.1", "920575.5"),
        )
        assertNotNull(states)
        assertContentEquals(booleanArrayOf(true, false, true), states)
    }

    @Test
    fun `宿主模型里一只都没选时全部为 false`() {
        val states = StgSelectAllPlan.statesFromCodes(
            listOf("600000", "002368"),
            emptyList(),
        )
        assertNotNull(states)
        assertContentEquals(booleanArrayOf(false, false), states)
    }

    @Test
    fun `任何一行代码读不到就不拿模型判`() {
        // 读不到 ≠ 没选：拿模型判会把这一行当成没勾，点下去反而取消
        assertNull(
            StgSelectAllPlan.statesFromCodes(
                listOf("600000", null, "920575"),
                listOf("600000.1"),
            ),
        )
    }

    @Test
    fun `空列表返回空状态`() {
        assertContentEquals(
            BooleanArray(0),
            StgSelectAllPlan.statesFromCodes(emptyList(), listOf("600000")),
        )
    }

    @Test
    fun `重复代码按首次重复顺序列出`() {
        assertEquals(
            listOf("600000", "002368"),
            StgSelectAllPlan.duplicateCodes(
                listOf("600000.1", "600000.1", "002368", "002368.0", "920575"),
            ),
        )
    }

    @Test
    fun `代码形态识别`() {
        assertTrue(StgSelectAllPlan.isCodeLike("600000"))
        assertTrue(StgSelectAllPlan.isCodeLike("920575.5"))
        assertTrue(StgSelectAllPlan.isCodeLike("00700"))
        assertFalse(StgSelectAllPlan.isCodeLike("StockInfo{code=600000}"))
        assertFalse(StgSelectAllPlan.isCodeLike(""))
    }

    // ---------------------------------------------------------- 模型可用性（防反向点击）

    @Test
    fun `没有筛选时 计数大于零但模型空表视为不可用`() {
        // 两份数据分叉：拿空模型判会把已勾行全判成没勾，点一遍反而全取消
        assertNull(
            StgSelectAllPlan.statesFromCodes(
                listOf("600000"),
                emptyList(),
                filtered = false,
                hostCount = 1,
            ),
        )
    }

    @Test
    fun `没有筛选时 计数大于零但模型一只都对不上视为不可用`() {
        assertNull(
            StgSelectAllPlan.statesFromCodes(
                listOf("600000", "002368"),
                listOf("000001.0"),
                filtered = false,
                hostCount = 1,
            ),
        )
    }

    @Test
    fun `筛选生效时 空模型不拿计数判分叉`() {
        // 筛选时计数含被筛掉的行：模型为空只代表这一页没选中
        assertNotNull(
            StgSelectAllPlan.statesFromCodes(
                listOf("600000"),
                emptyList(),
                filtered = true,
                hostCount = 3,
            ),
        )
    }

    @Test
    fun `计数为 0 时模型空表仍可用`() {
        // 计数为 0 = 一行都没选，空模型与它一致；
        // 「计数 0 + 模型非空」的分叉由 takeShot 的首轮保护处理，不在这里。
        assertNotNull(
            StgSelectAllPlan.statesFromCodes(
                listOf("600000"),
                emptyList(),
                filtered = false,
                hostCount = 0,
            ),
        )
    }
}
