package io.github.MVennn.GSAuxiliary.core.zxcopy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 字段目录：代码规范化、数值格式化、市值换算、排序值 */
class ZxCopyFieldCatalogTest {

    private fun spec(id: String) = ZxCopyFieldCatalog.byId(id)!!

    @Test
    fun `代码只取 6 位数字`() {
        assertEquals("688111", ZxCopyFieldCatalog.normalizeCode("688111.2"))
        assertEquals("688111", ZxCopyFieldCatalog.normalizeCode("SH688111"))
        assertEquals("000001", ZxCopyFieldCatalog.normalizeCode(" 000001.SZ "))
        assertNull(ZxCopyFieldCatalog.normalizeCode("1234567"))
        assertNull(ZxCopyFieldCatalog.normalizeCode("ABCDEF"))
    }

    @Test
    fun `价格与涨跌保留两位小数并带符号`() {
        assertEquals("249.59", ZxCopyFieldCatalog.format(spec("price"), "249.589"))
        assertEquals("+23.60", ZxCopyFieldCatalog.format(spec("change"), "23.6"))
        assertEquals("-1.20", ZxCopyFieldCatalog.format(spec("change"), "-1.2"))
    }

    @Test
    fun `涨幅带百分号 正数带加号`() {
        assertEquals("+5.37%", ZxCopyFieldCatalog.format(spec("changePct"), "5.37"))
        assertEquals("-2.10%", ZxCopyFieldCatalog.format(spec("changePct"), "-2.1"))
        assertEquals("5.37%", ZxCopyFieldCatalog.format(spec("amplitude"), "5.37"))
    }

    @Test
    fun `市值已是显示串就原样用 纯数字才换算`() {
        assertEquals("1158.13亿", ZxCopyFieldCatalog.format(spec("floatCap"), "1158.13亿"))
        assertEquals("12.34万", ZxCopyFieldCatalog.format(spec("amount"), "123400"))
        assertEquals("1.00亿", ZxCopyFieldCatalog.format(spec("floatCap"), "100000000"))
    }

    @Test
    fun `市场号映射 深 沪 北`() {
        assertEquals("深", ZxCopyFieldCatalog.format(spec("market"), "1"))
        assertEquals("沪", ZxCopyFieldCatalog.format(spec("market"), "2"))
        assertEquals("北", ZxCopyFieldCatalog.format(spec("market"), "5"))
        assertEquals("9", ZxCopyFieldCatalog.format(spec("market"), "9"))
    }

    @Test
    fun `排序值 百分比与亿 万都能解析`() {
        assertEquals(5.37, ZxCopyFieldCatalog.sortValue(spec("changePct"), "5.37")!!, 0.0001)
        assertEquals(
            115813000000.0,
            ZxCopyFieldCatalog.sortValue(spec("floatCap"), "1158.13亿")!!,
            1.0,
        )
        assertEquals(123400.0, ZxCopyFieldCatalog.sortValue(spec("amount"), "12.34万")!!, 0.01)
        assertNull(ZxCopyFieldCatalog.sortValue(spec("code"), "688111"))
    }

    @Test
    fun `空值返回 null`() {
        assertNull(ZxCopyFieldCatalog.format(spec("price"), null))
        assertNull(ZxCopyFieldCatalog.format(spec("price"), "  "))
    }

    @Test
    fun `字段数组顺序与目录一致`() {
        // 设置页的 string-array 按这个顺序取名字；顺序变了 UI 会串名字
        assertEquals("name", ZxCopyFieldCatalog.ALL.first().id)
        assertEquals("code", ZxCopyFieldCatalog.ALL[1].id)
        assertEquals("changePct", ZxCopyFieldCatalog.ALL[5].id)
        assertEquals("weiBi", ZxCopyFieldCatalog.ALL.last().id)
    }
}
