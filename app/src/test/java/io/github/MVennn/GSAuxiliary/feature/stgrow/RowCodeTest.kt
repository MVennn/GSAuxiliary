package io.github.MVennn.GSAuxiliary.feature.stgrow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RowCode] 三种口径的边界（2026-11-14 搭桥后新增）。
 *
 * 这里钉的是「口径本身」：宽松 / 推导 / 严格三套各自对哪些输入说 null。
 * 各自的老单测（CodeMarketPartsTest、StgSnapPlanTest、StgSelectAllPlanTest）继续跑，
 * 两边一起证明「抽出来之后行为没变」。
 */
class RowCodeTest {

    // ── 宽松：筛选 / 类型判定 ────────────────────────────────────────────

    @Test
    fun lenientSplitsOnDot() {
        assertEquals("300006" to "1", RowCode.splitLenient("300006.1"))
    }

    @Test
    fun lenientKeepsWholeStringAsCodeWhenNoDot() {
        // 历史事故点：这里必须返回「整串当代码」，不能 code = null
        assertEquals("300006" to null, RowCode.splitLenient("300006"))
    }

    @Test
    fun lenientLeadingDotIsNotASeparator() {
        // dot == 0 不算分隔符：整串（含点）当代码，市场未知
        assertEquals(".300006" to null, RowCode.splitLenient(".300006"))
    }

    @Test
    fun lenientEmptyAndNullAreBothNull() {
        assertEquals(null to null, RowCode.splitLenient(null))
        assertEquals(null to null, RowCode.splitLenient(""))
    }

    // ── 归一化 ─────────────────────────────────────────────────────────

    @Test
    fun codeOfStripsMarketAndSpaces() {
        assertEquals("600000", RowCode.codeOf("600000.1"))
        assertEquals("600000", RowCode.codeOf("  600000 "))
        assertEquals("", RowCode.codeOf(null))
    }

    // ── 推导：全选 / 勾选态 / 自选判定 ───────────────────────────────────

    @Test
    fun derivedKeepsGivenMarket() {
        assertEquals("600000" to "1", RowCode.splitDerived("600000.1"))
    }

    @Test
    fun derivedGuessesMarketFromCodePrefix() {
        assertEquals("600000" to "2", RowCode.splitDerived("600000"))
        assertEquals("300006" to "1", RowCode.splitDerived("300006"))
        assertEquals("920001" to "5", RowCode.splitDerived("920001"))
    }

    @Test
    fun derivedGivesUpWhenPrefixUnknown() {
        assertNull(RowCode.splitDerived("abc"))
        assertNull(RowCode.splitDerived(""))
        assertNull(RowCode.splitDerived(null))
    }

    // ── 严格：截图落盘 / 导出 ───────────────────────────────────────────

    @Test
    fun strictAcceptsStandardShape() {
        assertEquals("300006" to "1", RowCode.splitStrict("300006.1"))
        assertEquals("600000" to "2", RowCode.splitStrict("600000.2"))
        assertEquals("920001" to "5", RowCode.splitStrict("920001.5"))
    }

    @Test
    fun strictRejectsAnythingElse() {
        assertNull(RowCode.splitStrict("300006"))
        assertNull(RowCode.splitStrict("300006.1.2"))
        assertNull(RowCode.splitStrict("30006.1"))
        assertNull(RowCode.splitStrict("300006.3"))
        assertNull(RowCode.splitStrict(".300006"))
        assertNull(RowCode.splitStrict("300006."))
        assertNull(RowCode.splitStrict(null))
    }

    @Test
    fun validityHelpers() {
        assertEquals(true, RowCode.isCode("300006"))
        assertEquals(false, RowCode.isCode("30006"))
        assertEquals(false, RowCode.isCode(null))
        assertEquals(true, RowCode.isMarket("5"))
        assertEquals(false, RowCode.isMarket("3"))
        assertEquals(false, RowCode.isMarket(null))
    }
}
