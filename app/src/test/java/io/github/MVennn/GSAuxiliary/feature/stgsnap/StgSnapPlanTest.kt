package io.github.MVennn.GSAuxiliary.feature.stgsnap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 截图并添加的纯逻辑：代码/市场校验、去重、文件名、标题匹配、计数解析。 */
class StgSnapPlanTest {

    @Test
    fun `代码与市场校验`() {
        assertTrue(StgSnapPlan.isValidCode("003021"))
        assertFalse(StgSnapPlan.isValidCode("3021"))
        assertFalse(StgSnapPlan.isValidCode("0030210"))
        assertFalse(StgSnapPlan.isValidCode("abc021"))
        assertTrue(StgSnapPlan.isValidMarket("1"))
        assertTrue(StgSnapPlan.isValidMarket("2"))
        assertTrue(StgSnapPlan.isValidMarket("5"))
        assertFalse(StgSnapPlan.isValidMarket("3"))
        assertFalse(StgSnapPlan.isValidMarket(null))
    }

    @Test
    fun `codeAndMarket 拆分`() {
        assertEquals("003021" to "1", StgSnapPlan.splitCam("003021.1"))
        assertEquals("600519" to "2", StgSnapPlan.splitCam("600519.2"))
        assertEquals("920575" to "5", StgSnapPlan.splitCam("920575.5"))
        assertNull(StgSnapPlan.splitCam(null))
        assertNull(StgSnapPlan.splitCam("003021"))
        assertNull(StgSnapPlan.splitCam("003021.1.2"))
        assertNull(StgSnapPlan.splitCam("00302.1"))
        assertNull(StgSnapPlan.splitCam("003021.9"))
    }

    @Test
    fun `按代码去重且保持顺序`() {
        val items = listOf(
            SelectedStock("003021", "1", "兆威机电"),
            SelectedStock("603728", "2", "鸣志电器"),
            SelectedStock("003021", "1", "兆威机电"),
        )
        val out = StgSnapPlan.dedup(items)
        assertEquals(2, out.size)
        assertEquals(listOf("003021.1", "603728.2"), out.map { it.cam })
    }

    @Test
    fun `文件名清洗与编号补零`() {
        assertEquals("01_兆威机电_003021.png", StgSnapPlan.fileName(1, "兆威机电", "003021"))
        assertEquals("12_ABC_600519.png", StgSnapPlan.fileName(12, "A/B:C", "600519"))
        assertEquals("03_003021_003021.png", StgSnapPlan.fileName(3, "  ", "003021"))
        val long = "一".repeat(30)
        assertEquals("01_${"一".repeat(20)}_600519.png", StgSnapPlan.fileName(1, long, "600519"))
    }

    @Test
    fun `标题必须包含目标代码`() {
        assertTrue(StgSnapPlan.titleMatches("兆威机电 003021", "003021"))
        assertTrue(StgSnapPlan.titleMatches("兆威机电003021", "003021"))
        assertFalse(StgSnapPlan.titleMatches("鸣志电器 603728", "003021"))
        assertFalse(StgSnapPlan.titleMatches("", "003021"))
        assertFalse(StgSnapPlan.titleMatches(null, "003021"))
    }

    @Test
    fun `底部计数解析`() {
        assertEquals(2, StgSnapPlan.parseCount("2"))
        assertEquals(12, StgSnapPlan.parseCount(" 12 "))
        assertEquals(-1, StgSnapPlan.parseCount(""))
        assertEquals(-1, StgSnapPlan.parseCount(null))
    }
}
