package io.github.MVennn.GSAuxiliary.feature.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 股票类型判定（按代码前缀 / 市场号 / 名称）。
 *
 * ## 这条测试为什么重要
 *
 * 这套判定决定**「未开板次新 / ST股 / 创业板 / 科创板 / 北证A」这五行**的筛选结果，
 * 策略页与行情页**共用**（`feature/filter/` 是单一正本）。
 * 判错一个前缀 → 用户勾了「创业板」却看到科创板的票，或反过来。
 *
 * 「未开板次新」（`isXXLD`）那一项**依赖宿主行情数据字段**，不是代码前缀 ——
 * 探针 dump 拿到了字段名，但**运行时取值尚未核对**（见 `StockTypes` 的说明）。
 * 所以这一项**没有**前缀级的用例 —— 不能拿代码前缀假装验证它。
 */
class StockTypesTest {

    // ------------------------------------------------------------------ 前缀分类

    @Test
    fun `创业板 按 300 301 302 前缀`() {
        assertTrue(StockTypes.has(StockTypes.byCodeAndMarket("300045", "1", null), FilterRow.CYB))
        assertTrue(StockTypes.has(StockTypes.byCodeAndMarket("301666", "1", null), FilterRow.CYB))
        assertTrue(StockTypes.has(StockTypes.byCodeAndMarket("302132", "1", null), FilterRow.CYB))
    }

    @Test
    fun `科创板 按 688 689 前缀`() {
        assertTrue(StockTypes.has(StockTypes.byCodeAndMarket("688121", "2", null), FilterRow.KCB))
        assertTrue(StockTypes.has(StockTypes.byCodeAndMarket("689009", "2", null), FilterRow.KCB))
    }

    @Test
    fun `北证A 按市场号 5 或 43 83 87 88 920 前缀`() {
        assertTrue(StockTypes.has(StockTypes.byCodeAndMarket("920575", "5", null), FilterRow.BZA))
        assertTrue(StockTypes.has(StockTypes.byCodeAndMarket("430047", null, null), FilterRow.BZA))
        assertTrue(StockTypes.has(StockTypes.byCodeAndMarket("830799", null, null), FilterRow.BZA))
        assertTrue(StockTypes.has(StockTypes.byCodeAndMarket("871981", null, null), FilterRow.BZA))
        assertTrue(StockTypes.has(StockTypes.byCodeAndMarket("889999", null, null), FilterRow.BZA))
    }

    @Test
    fun `主板既不属于创业板也不属于科创板也不属于北证`() {
        val t = StockTypes.byCodeAndMarket("600000", "2", null)
        assertEquals(StockTypes.NONE, t, "600000 不该命中任何类型")
        val t2 = StockTypes.byCodeAndMarket("000001", "1", null)
        assertEquals(StockTypes.NONE, t2, "000001 不该命中任何类型")
    }

    @Test
    fun `ST 按名称判定 与其它类型可以同时成立`() {
        // *ST 卓然 = 科创板 + ST股（688121）
        val t = StockTypes.byCodeAndMarket("688121", "2", "*ST卓然")
        assertTrue(StockTypes.has(t, FilterRow.KCB), "*ST 卓然应属于科创板")
        assertTrue(StockTypes.has(t, FilterRow.ST), "*ST 卓然应属于 ST股")
    }

    @Test
    fun `名称里没有 ST 就不算 ST股`() {
        val t = StockTypes.byCodeAndMarket("300045", "1", "机器人")
        assertFalse(StockTypes.has(t, FilterRow.ST))
    }

    @Test
    fun `代码为空时只保留名称能判出的类型`() {
        val t = StockTypes.byCodeAndMarket(null, null, "某某ST")
        assertTrue(StockTypes.has(t, FilterRow.ST), "代码读不到时仍应能按名称判 ST")
        assertEquals(StockTypes.ST, t, "没有代码就不该有别的类型")
    }

    // ------------------------------------------------------------------ 合并形状

    @Test
    fun `代码点市场号 的形状 与分开传等价`() {
        val a = StockTypes.byCodeAndMarket("300045.1", null)
        val b = StockTypes.byCodeAndMarket("300045", "1", null)
        assertEquals(b, a, "「代码.市场」与分开传必须得出同一个结果")
    }

    @Test
    fun `代码点市场号 的形状 也能带名称判 ST`() {
        val a = StockTypes.byCodeAndMarket("688121.2", "*ST卓然")
        val b = StockTypes.byCodeAndMarket("688121", "2", "*ST卓然")
        assertEquals(b, a)
    }

    @Test
    fun `没有市场号时 整串当代码 - 代码前缀判定不能失效`() {
        // ⚠️ **这条是 2026-10-20 复核时补的**：
        // 「纯重构」那批把切分提前到了调用点，于是「无点」的情形从
        // 「整串当代码」变成了「code = null」→ **代码前缀判定整段失效**
        // （创业板 / 科创板 / 北证A 全部分不出来，只剩 ST 位）。
        //
        // 宿主的 `codeAndMarket` 出现「只有代码没市场号」的中间态是真实存在的
        // （`StockInfo` 是构造后填充的），所以这个口径必须钉死。
        //
        // ⚠️ **测的是 [CodeMarketParts]** —— 生产代码（`StgStockFilterFeature`）
        // 与本测试**共用**它。上一版测试只测 `StockTypes` 的 2 参重载，
        // 而生产代码早就不走那条路了 → **测试形同虚设**（真出问题时没红）。
        val (code, market) = CodeMarketParts.split("301666")
        assertEquals("301666", code, "没有市场号时整串都要当代码")
        assertNull(market, "没有市场号时 market 是 null")
        assertTrue(
            StockTypes.has(StockTypes.byCodeAndMarket(code, market, null), FilterRow.CYB),
            "只有代码没有市场号时，创业板的前缀判定仍然要生效",
        )

        val (kcbCode, kcbMarket) = CodeMarketParts.split("688121")
        assertTrue(
            StockTypes.has(StockTypes.byCodeAndMarket(kcbCode, kcbMarket, null), FilterRow.KCB),
            "只有代码没有市场号时，科创板的前缀判定仍然要生效",
        )

        val (bzaCode, bzaMarket) = CodeMarketParts.split("920575")
        assertTrue(
            StockTypes.has(StockTypes.byCodeAndMarket(bzaCode, bzaMarket, null), FilterRow.BZA),
            "只有代码没有市场号时，北证A的前缀判定仍然要生效",
        )
    }

    @Test
    fun `切分结果 直接断言 - 不能只比类型掩码`() {
        // ⚠️ **比类型掩码不够**（2026-10-20 三次复核时发现）：
        // `StockTypes.byCodeAndMarket` 的 market 只在「等于 5（北交所）」这一条上起作用，
        // 其余全靠代码前缀 → 13 个样本里**没有一个能区分「market 还在不在」** ——
        // 把 `split` 改成永远返回 `market = null`，比类型掩码的对拍**依然全绿**。
        //
        // 而 market 这一维是**行为相关**的：`isInWatchlist` 要求 market 非空才去查自选
        // （`StgStockFilterFeature` 里），market 丢了 = 自选判定失效。
        // 所以这里**直接断言切分结果**。
        val cases = listOf(
            "301666.1" to ("301666" to "1"),
            "301666" to ("301666" to null),
            "301666." to ("301666" to ""),
            ".1" to (".1" to null),          // 点在下标 0
            "a.b" to ("a" to "b"),
            "301666.1.2" to ("301666" to "1.2"),
            "" to (null to null),
            null to (null to null),
        )
        for ((cam, expected) in cases) {
            assertEquals(expected, CodeMarketParts.split(cam), "「$cam」的切分结果不对")
        }
    }

    @Test
    fun `切分口径 与 StockTypes 的 2 参重载 逐字等价`() {
        // 「生产代码切的」与「StockTypes 自己切的」必须一致 ——
        // 不一致就说明有一边的口径漂了（前车之鉴第 3 条）。
        val samples = listOf(
            "301666.1", "688121.2", "920575.5", "600000.2", "000001.1",
            "301666", "688121", "920575", "", "301666.",
            // ⚠️ 这三个是**边界**（`dot > 0` 与 `dot >= 0` 的差别恰好落在它们身上）：
            //   null  → 没有串；
            //   ".1"  → 点在下标 0；
            //   "a.b" → 非法代码（要确认两边都当普通字符串处理）。
            null, ".1", "a.b",
        )
        for (cam in samples) {
            val (code, market) = CodeMarketParts.split(cam)
            val byParts = StockTypes.byCodeAndMarket(code, market, null)
            val byWhole = StockTypes.byCodeAndMarket(cam, null)
            assertEquals(
                byWhole,
                byParts,
                "「$cam」的两种切法结果不同 —— 口径漂了",
            )
        }
    }

    // ------------------------------------------------------------------ 市场号推断

    @Test
    fun `市场号按代码前缀推断`() {
        assertEquals(StockTypes.MARKET_SH, StockTypes.marketOf("600000"))
        assertEquals(StockTypes.MARKET_SH, StockTypes.marketOf("688121"))
        assertEquals(StockTypes.MARKET_SZ, StockTypes.marketOf("000001"))
        assertEquals(StockTypes.MARKET_SZ, StockTypes.marketOf("300045"))
        assertEquals(StockTypes.MARKET_BJ, StockTypes.marketOf("920575"))
        assertEquals(StockTypes.MARKET_BJ, StockTypes.marketOf("430047"))
    }

    @Test
    fun `推不出市场号时返回 null - 宁可少筛不乱筛`() {
        // 长度不足 6 位
        assertNull(StockTypes.marketOf("30004"))
        assertNull(StockTypes.marketOf(""))
        assertNull(StockTypes.marketOf(null))
        // 前缀不认识
        assertNull(StockTypes.marketOf("777777"))
    }

    @Test
    fun `92 开头归北交所而不是沪市`() {
        // 沪 B 是 900 开头，北交所是 920 开头 —— 两个都以 9 开头，必须区分
        assertEquals(StockTypes.MARKET_BJ, StockTypes.marketOf("920575"))
        assertEquals(StockTypes.MARKET_SH, StockTypes.marketOf("900901"))
    }
}
