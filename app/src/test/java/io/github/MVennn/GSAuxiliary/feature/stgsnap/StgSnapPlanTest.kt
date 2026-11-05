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
    fun `文件名是名称加代码`() {
        // 口径：`名称 代码.png`，不带序号、不用下划线（2026-11-05 改）
        assertEquals("青云科技 688316.png", StgSnapPlan.fileName("青云科技", "688316"))
        assertEquals("兆威机电 003021.png", StgSnapPlan.fileName("兆威机电", "003021"))
        // 路径非法字符清掉，名称内部的空格也清掉，只留名称与代码之间的那一个
        assertEquals("ABC 600519.png", StgSnapPlan.fileName("A/B:C", "600519"))
        assertEquals("ST股 600519.png", StgSnapPlan.fileName("ST 股", "600519"))
        // 空名 / 纯空格回退成代码，仍保持「名称 代码」形状
        assertEquals("003021 003021.png", StgSnapPlan.fileName("  ", "003021"))
        assertEquals("600519 600519.png", StgSnapPlan.fileName(null, "600519"))
        // 名称过长截到 20 字
        val long = "一".repeat(30)
        assertEquals("${"一".repeat(20)} 600519.png", StgSnapPlan.fileName(long, "600519"))
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
