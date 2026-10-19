package io.github.MVennn.GSAuxiliary.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 一键配置的**算价引擎** —— 写进宿主盯盘的价格就是它算出来的。
 *
 * ## 这条测试为什么重要
 *
 * 这是全项目**唯一直接决定资金决策**的地方：算错一个价，用户就按错价挂单。
 * 而它周围全是「看起来对但结果差一分」的口径坑：
 *
 * - `BigDecimal(double)` vs `BigDecimal.valueOf(double)` —— 0.95 的 double 是 0.9499999…，
 *   11.50 × 它 = 10.9249… → HALF_UP 进成 10.92，比正确值 10.93 **少一分**（对拍实测踩中）；
 * - 区间校验的倍数（1.5 倍 / 含前高前低时 5 倍）；
 * - 「最近前高 / 前低」是**最近的局部顶 / 底**，不是历史极值；
 * - 均线是**含今日**的简单算术平均。
 *
 * 这些全是「改了不会编译报错、只会在真机上悄悄算错」的坑 —— 单测是唯一的网。
 */
class QuickConfigEngineTest {

    // ------------------------------------------------------------------ 夹具

    private fun snapshot(
        latest: String = "11.50",
        prevClose: String = "10.00",
        open: String? = null,
        dayHigh: String? = null,
        dayLow: String? = null,
        limitUp: String? = null,
        limitDown: String? = null,
    ) = QuoteSnapshot(
        latest = BigDecimal(latest),
        prevClose = BigDecimal(prevClose),
        open = open?.let { BigDecimal(it) },
        dayHigh = dayHigh?.let { BigDecimal(it) },
        dayLow = dayLow?.let { BigDecimal(it) },
        name = "测试股",
        limitUp = limitUp?.let { BigDecimal(it) },
        limitDown = limitDown?.let { BigDecimal(it) },
        noPriceLimit = false,
    )

    private fun input(
        code: String = "300045",
        market: String = "1",
        snap: QuoteSnapshot? = snapshot(),
        cost: BigDecimal? = null,
        klines: QuickConfigEngine.KlineProvider = QuickConfigEngine.KlineProvider { null },
    ) = QuickConfigEngine.Input(
        code = code,
        market = market,
        snapshot = snap,
        cost = cost,
        klines = klines,
    )

    private fun term(id: String, vararg params: Pair<String, String>) =
        QuickConfigStore.Term(id, params.toMap())

    private fun slot(agg: String, vararg terms: QuickConfigStore.Term) =
        QuickConfigStore.Slot(agg, terms.toList())

    private fun scheme(
        up: QuickConfigStore.Slot,
        down: QuickConfigStore.Slot,
        id: String = "s1",
    ) = QuickConfigStore.Scheme(
        id = id,
        name = "测试方案",
        up = up,
        down = down,
        validity = null,
        isDefault = false,
        preview = "",
    )

    // ------------------------------------------------------------------ 基本取值

    @Test
    fun `实时项 逐项取到快照里的对应值`() {
        val snap = snapshot(
            latest = "11.50", prevClose = "10.00", open = "10.20",
            dayHigh = "11.80", dayLow = "9.90", limitUp = "11.00", limitDown = "9.00",
        )
        val inp = input(snap = snap)
        assertEquals(0, BigDecimal("11.50").compareTo(evalSlot(slot(QuickConfigStore.AGG_MAX, term("latest")), inp)))
        assertEquals(0, BigDecimal("10.00").compareTo(evalSlot(slot(QuickConfigStore.AGG_MAX, term("prevClose")), inp)))
        assertEquals(0, BigDecimal("10.20").compareTo(evalSlot(slot(QuickConfigStore.AGG_MAX, term("open")), inp)))
        assertEquals(0, BigDecimal("11.80").compareTo(evalSlot(slot(QuickConfigStore.AGG_MAX, term("dayHigh")), inp)))
        assertEquals(0, BigDecimal("9.90").compareTo(evalSlot(slot(QuickConfigStore.AGG_MAX, term("dayLow")), inp)))
        assertEquals(0, BigDecimal("11.00").compareTo(evalSlot(slot(QuickConfigStore.AGG_MAX, term("limitUp")), inp)))
        assertEquals(0, BigDecimal("9.00").compareTo(evalSlot(slot(QuickConfigStore.AGG_MAX, term("limitDown")), inp)))
    }

    @Test
    fun `固定价项 直接取参数值`() {
        val inp = input()
        val v = evalSlot(slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "12.34")), inp)
        assertEquals(0, BigDecimal("12.34").compareTo(v))
    }

    @Test
    fun `成本项 拿不到持仓时判失败`() {
        assertNull(evalSlot(slot(QuickConfigStore.AGG_MAX, term("cost")), input(cost = null)))
        val v = evalSlot(slot(QuickConfigStore.AGG_MAX, term("cost")), input(cost = BigDecimal("8.88")))
        assertEquals(0, BigDecimal("8.88").compareTo(v))
    }

    // ------------------------------------------------------------------ 幅度项（浮点口径）

    @Test
    fun `幅度项 用 valueOf 而不是 BigDecimal-double - 差一分就是错价`() {
        val inp = input(snap = snapshot(latest = "11.50", prevClose = "10.00"))
        // 跌 5%：11.50 × 0.95 = 10.925 → HALF_UP 两位 = 10.93
        // ⚠️ 若用 BigDecimal(0.95) 会算成 10.9249… → 10.92（少一分）
        val v = evalSlot(
            slot(QuickConfigStore.AGG_MAX, term("downPct", "pct" to "5")),
            inp,
        )
        assertNotNull(v)
        assertEquals(
            0,
            BigDecimal("10.93").compareTo(v),
            "跌 5% 必须是 10.93 —— 用 BigDecimal(double) 会算成 10.92",
        )
    }

    @Test
    fun `幅度项 涨跌幅越界判失败`() {
        val inp = input()
        // 幅度必须在 0.1% ~ 30% 之间
        assertNull(evalSlot(slot(QuickConfigStore.AGG_MAX, term("upPct", "pct" to "0.05")), inp))
        assertNull(evalSlot(slot(QuickConfigStore.AGG_MAX, term("upPct", "pct" to "30.1")), inp))
        assertNotNull(evalSlot(slot(QuickConfigStore.AGG_MAX, term("upPct", "pct" to "0.1")), inp))
        assertNotNull(evalSlot(slot(QuickConfigStore.AGG_MAX, term("upPct", "pct" to "30")), inp))
    }

    @Test
    fun `昨收幅度项 以昨收为基准 不是以现价`() {
        val inp = input(snap = snapshot(latest = "11.50", prevClose = "10.00"))
        val v = evalSlot(slot(QuickConfigStore.AGG_MAX, term("prevUpPct", "pct" to "10")), inp)
        // 昨收 10.00 × 1.10 = 11.00
        assertEquals(0, BigDecimal("11.00").compareTo(v))
    }

    // ------------------------------------------------------------------ 聚合

    @Test
    fun `多值槽 按聚合方式取 max min avg`() {
        val inp = input(snap = snapshot(latest = "11.50", prevClose = "10.00", dayHigh = "12.00", dayLow = "9.00"))
        val terms = arrayOf(term("dayHigh"), term("dayLow"))
        assertEquals(0, BigDecimal("12.00").compareTo(evalSlot(slot(QuickConfigStore.AGG_MAX, *terms), inp)))
        assertEquals(0, BigDecimal("9.00").compareTo(evalSlot(slot(QuickConfigStore.AGG_MIN, *terms), inp)))
        val avg = evalSlot(slot(QuickConfigStore.AGG_AVG, *terms), inp)
        assertEquals(0, BigDecimal("10.50").compareTo(avg))
    }

    // ------------------------------------------------------------------ 均线与区间

    @Test
    fun `均线是含今日的简单算术平均`() {
        val bars = listOf(
            QuoteBar("2026-10-01", "10.00", "10.00", "10.00", "10.00"),
            QuoteBar("2026-10-02", "11.00", "11.00", "11.00", "11.00"),
            QuoteBar("2026-10-03", "12.00", "12.00", "12.00", "12.00"),
        )
        val inp = input(klines = QuickConfigEngine.KlineProvider { bars })
        val v = evalSlot(slot(QuickConfigStore.AGG_MAX, term("maN", "period" to "3")), inp)
        assertEquals(0, BigDecimal("11.00").compareTo(v), "ma3 应是 (10+11+12)/3 = 11.00（含今日）")
    }

    @Test
    fun `K线根数不足时判失败 - 不凑数也不写零`() {
        val bars = listOf(
            QuoteBar("2026-10-01", "10.00", "10.00", "10.00", "10.00"),
        )
        val inp = input(klines = QuickConfigEngine.KlineProvider { bars })
        assertNull(
            evalSlot(slot(QuickConfigStore.AGG_MAX, term("highN", "period" to "30")), inp),
            "只有 1 根 K 线却要 30 根 —— 必须判失败，不能凑数",
        )
    }

    // ------------------------------------------------------------------ 涨跌停

    @Test
    fun `涨跌停幅度按代码前缀判定`() {
        // 主板 10%、创业/科创 20%、北交所 30%
        fun limitOf(code: String, market: String): BigDecimal? {
            val inp = input(code = code, market = market, snap = snapshot(latest = "10.00", prevClose = "10.00"))
            return evalSlot(slot(QuickConfigStore.AGG_MAX, term("limitUp")), inp)
        }
        // 主板 600000：10.00 × 1.10 = 11.00
        assertEquals(0, BigDecimal("11.00").compareTo(limitOf("600000", "2")))
        // 创业板 300045：10.00 × 1.20 = 12.00
        assertEquals(0, BigDecimal("12.00").compareTo(limitOf("300045", "1")))
        // 科创板 688121：10.00 × 1.20 = 12.00
        assertEquals(0, BigDecimal("12.00").compareTo(limitOf("688121", "2")))
    }

    @Test
    fun `指数没有涨跌停 判失败`() {
        val inp = input(code = "000001", market = "2", snap = snapshot(latest = "3000.00", prevClose = "3000.00"))
        assertNull(evalSlot(slot(QuickConfigStore.AGG_MAX, term("limitUp")), inp), "指数不该有涨跌停价")
    }

    // ------------------------------------------------------------------ 区间校验

    @Test
    fun `涨到不高于跌到时判失败`() {
        val inp = input(snap = snapshot(latest = "11.50", prevClose = "10.00"))
        val s = scheme(
            up = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "10.00")),
            down = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "12.00")),
        )
        assertNull(QuickConfigEngine.evaluate(s, inp), "涨到(10.00) ≤ 跌到(12.00) 必须判失败")
    }

    @Test
    fun `超出昨收1_5倍上限时判失败`() {
        val inp = input(snap = snapshot(latest = "11.50", prevClose = "10.00"))
        // 昨收 10.00 × 1.5 = 15.00，涨到 15.00 就该被拒
        val bad = scheme(
            up = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "15.00")),
            down = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "9.00")),
        )
        assertNull(QuickConfigEngine.evaluate(bad, inp), "涨到 = 昨收×1.5 应判失败")
        // 1.5 倍以内应当通过
        val ok = scheme(
            up = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "14.00")),
            down = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "9.00")),
        )
        assertNotNull(QuickConfigEngine.evaluate(ok, inp))
    }

    @Test
    fun `跌到0_01以下判失败`() {
        val inp = input(snap = snapshot(latest = "11.50", prevClose = "10.00"))
        val s = scheme(
            up = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "12.00")),
            down = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "0.00")),
        )
        assertNull(QuickConfigEngine.evaluate(s, inp), "跌到 0.00 是无效价，必须判失败")
    }

    @Test
    fun `含最近前高前低的方案 上限放宽到昨收5倍`() {
        // 腰斩股：昨收 10.00，「涨到前高」可能是 45 —— 用 1.5 倍会被误杀
        val bars = listOf(
            QuoteBar("2026-09-01", "40.00", "45.00", "39.00", "40.00"),
            QuoteBar("2026-09-02", "41.00", "46.00", "40.50", "41.00"),
            QuoteBar("2026-09-03", "42.00", "45.00", "41.00", "42.00"),
        )
        val inp = input(
            snap = snapshot(latest = "10.50", prevClose = "10.00", dayHigh = "10.80"),
            klines = QuickConfigEngine.KlineProvider { bars },
        )
        val s = scheme(
            up = slot(QuickConfigStore.AGG_MAX, term("historyHigh")),
            down = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "9.00")),
        )
        val r = QuickConfigEngine.evaluate(s, inp)
        assertNotNull(r, "含前高的方案上限应放宽到昨收×5 —— 用 1.5 倍会把合法的「涨到前高」误杀")
        assertTrue(r.first > BigDecimal("15.00"), "放宽后涨到价应能超过昨收×1.5")
    }

    @Test
    fun `没有昨收时 不做区间校验但仍要求涨到高于跌到`() {
        val inp = input(snap = null)
        val ok = scheme(
            up = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "12.00")),
            down = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "9.00")),
        )
        assertNotNull(QuickConfigEngine.evaluate(ok, inp), "没有昨收时不该做区间上限校验")
        val bad = scheme(
            up = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "8.00")),
            down = slot(QuickConfigStore.AGG_MAX, term("fixed", "value" to "9.00")),
        )
        assertNull(QuickConfigEngine.evaluate(bad, inp))
    }

    // ------------------------------------------------------------------ 工具

    private fun evalSlot(slot: QuickConfigStore.Slot, input: QuickConfigEngine.Input): BigDecimal? =
        QuickConfigEngine.evaluateSlot(slot, input)

    private fun BigDecimal.setScale2() = setScale(2, RoundingMode.HALF_UP)
}
