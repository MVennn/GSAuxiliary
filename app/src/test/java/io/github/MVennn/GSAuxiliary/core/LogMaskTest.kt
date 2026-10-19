package io.github.MVennn.GSAuxiliary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 日志打码（规则 3.1：INFO 及以上不得出现完整 6 位代码 / 含代码的 URL）。
 *
 * ## 这条测试为什么重要
 *
 * 目标进程的日志会经 LSPosed 落到 `/data/adb/lspd/log/`，用户求助时经常整段贴出来
 * 或导出 —— 完整代码等于把关注列表交出去。
 *
 * 这条红线**已经漏过两次**：
 * - 第三轮审查 H4：取数三源的日志写全码（已修）；
 * - 第四轮审查 B-1：「我的盯盘」item 的全部字段原值进 INFO 日志（已修）。
 *
 * 打码是纯函数，**最容易单测**，也最该被守住。
 */
class LogMaskTest {

    @Test
    fun `代码加市场 保留前两位与市场后缀`() {
        assertEquals("30****.1", LogMask.stock("300045.1"))
        assertEquals("60****.2", LogMask.stock("600000.2"))
        assertEquals("00****.1", LogMask.stock("000001.1"))
    }

    @Test
    fun `代码与市场分开传时与合并形状一致`() {
        assertEquals(LogMask.stock("300045.1"), LogMask.stock("300045", "1"))
    }

    @Test
    fun `裸代码 保留前两位`() {
        assertEquals("30****", LogMask.code("300045"))
        assertEquals("92****", LogMask.code("920575"))
    }

    @Test
    fun `拿不到合法代码时统一写已略 - 绝不退化成打印原串`() {
        // 这些形状拼不出「前两位 + 至少三位 + 市场后缀」→ 一律「代码已略」
        val inputs = listOf(null, "", "   ", "3", "30", "30004")
        for (input in inputs) {
            val out = LogMask.stock(input)
            assertEquals(
                "代码已略",
                out,
                "输入 ${input?.let { "\"$it\"" } ?: "null"} 的打码结果不对",
            )
            // 关键：原串一个字都不该漏出去
            if (!input.isNullOrBlank()) {
                assertFalse(out.contains(input.trim()), "打码结果里漏出了原串：$out")
            }
        }
        assertEquals("代码已略", LogMask.code(null))
        assertEquals("代码已略", LogMask.code(""))
        assertEquals("代码已略", LogMask.code("30"))
    }

    @Test
    fun `市场号取最后一段 - 多段的形状也不漏完整代码`() {
        // 「300045.1.2」：取最后一个点当分隔 → 「30****.2」。
        // 完整代码仍然没漏出去（这才是这条规则要保的东西）。
        val out = LogMask.stock("300045.1.2")
        assertFalse(Regex("\\b\\d{6}\\b").containsMatchIn(out), "打码结果里仍有完整六位码：$out")
    }

    @Test
    fun `两位代码也要挡住 - 不能只看长度是否够六位`() {
        // ⚠️ **这条是 2026-10-20 变异测试补上的**：
        // 「打码结果里不含六位码」那条断言**挡不住**两位的代码被放行 ——
        // 两位的码本来就不含六位数字。而 `dot <= 2` 这道判据正是按分隔点位置卡的，
        // 放行两位码的后果是「日志里出现 `30.1` 这样的原始串」——
        // 虽不是完整代码，但**信息量已经泄露**（深市 + 某个序号）。
        assertEquals("代码已略", LogMask.stock("30.1"), "两位的代码也要挡")
        assertEquals("代码已略", LogMask.code("30"), "两位的裸代码也要挡")
        assertEquals("代码已略", LogMask.stock("3.1"), "一位的也要挡")
    }

    @Test
    fun `打码结果里绝不出现完整的六位数字`() {
        val samples = listOf("300045.1", "600000.2", "000001.1", "688121.2", "920575.5")
        for (s in samples) {
            val out = LogMask.stock(s)
            assertFalse(
                Regex("\\b\\d{6}\\b").containsMatchIn(out),
                "打码结果里仍有完整六位码：$out",
            )
        }
        for (s in samples) {
            val out = LogMask.code(s.substringBefore('.'))
            assertFalse(
                Regex("\\b\\d{6}\\b").containsMatchIn(out),
                "打码结果里仍有完整六位码：$out",
            )
        }
    }

    @Test
    fun `打码后仍能看出是哪一只 - 保留前两位是有意为之`() {
        val out = LogMask.stock("300045.1")
        assertTrue(out.startsWith("30"), "应保留前两位：$out")
        assertTrue(out.endsWith(".1"), "应保留市场后缀：$out")
    }
}
