package io.github.MVennn.GSAuxiliary.feature.zxcopy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 剪贴板文本的纯逻辑。
 *
 * 真机上「少一行 / 多一行 / 串市场」肉眼很难核对，这里把规则钉死：
 * 代码取 6 位、名称去空白、同一「代码.市场」只留第一次、名称缺失时只写代码。
 */
class ZxCopyPlanTest {

    @Test
    fun `正常条目格式为 名称 空格 代码`() {
        val text = ZxCopyPlan.format(
            listOf(
                ZxCopyPlan.Stock("金山办公", "688111", "2"),
                ZxCopyPlan.Stock("爱尔眼科", "300015", "1"),
            ),
        )
        assertEquals("金山办公 688111\n爱尔眼科 300015", text)
    }

    @Test
    fun `代码从 代码点市场 与带前后缀的文本里取 6 位`() {
        assertEquals("688111", ZxCopyPlan.normalizeCode("688111.2"))
        assertEquals("688111", ZxCopyPlan.normalizeCode("SH688111"))
        assertEquals("688111", ZxCopyPlan.normalizeCode(" 688111（两融）"))
        assertEquals("000001", ZxCopyPlan.normalizeCode("000001.SZ"))
    }

    @Test
    fun `不是 6 位数字的代码一律拒绝 绝不猜`() {
        assertNull(ZxCopyPlan.normalizeCode(null))
        assertNull(ZxCopyPlan.normalizeCode(""))
        assertNull(ZxCopyPlan.normalizeCode("12345"))
        assertNull(ZxCopyPlan.normalizeCode("1234567"))
        assertNull(ZxCopyPlan.normalizeCode("ABCDEF"))
    }

    @Test
    fun `名称去首尾与内部多余空白`() {
        assertEquals("金山办公", ZxCopyPlan.normalizeName("  金山办公 \n"))
        assertEquals("A B", ZxCopyPlan.normalizeName(" A   B "))
        assertNull(ZxCopyPlan.normalizeName("   "))
        assertNull(ZxCopyPlan.normalizeName(null))
    }

    @Test
    fun `名称缺失时只输出代码`() {
        val text = ZxCopyPlan.format(
            listOf(
                ZxCopyPlan.Stock(null, "688111", "2"),
                ZxCopyPlan.Stock("  ", "300015", "1"),
            ),
        )
        assertEquals("688111\n300015", text)
    }

    @Test
    fun `同一代码点市场只保留第一次出现`() {
        val text = ZxCopyPlan.format(
            listOf(
                ZxCopyPlan.Stock("金山办公", "688111", "2"),
                ZxCopyPlan.Stock("金山办公（重复）", "688111", "2"),
                ZxCopyPlan.Stock("平安银行", "000001", "1"),
                ZxCopyPlan.Stock("上证指数", "000001", "2"),
            ),
        )
        assertEquals("金山办公 688111\n平安银行 000001\n上证指数 000001", text)
    }

    @Test
    fun `空列表输出空串`() {
        assertEquals("", ZxCopyPlan.format(emptyList()))
    }

    @Test
    fun `没有市场号时按代码去重`() {
        val text = ZxCopyPlan.format(
            listOf(
                ZxCopyPlan.Stock("金山办公", "688111"),
                ZxCopyPlan.Stock("重复", "688111"),
            ),
        )
        assertEquals("金山办公 688111", text)
    }
}
