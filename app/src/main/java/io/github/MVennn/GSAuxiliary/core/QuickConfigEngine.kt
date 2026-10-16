package io.github.MVennn.GSAuxiliary.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 实时快照：一个 `(最新, 昨收, 今开, 当日高, 当日低, 名称)` 的元组。
 *
 * 字段用 [BigDecimal] 承载（缺失 = null，由取数源决定给不给）；
 * [name] 用于 ST 判定（涨停幅度表）。
 */
data class QuoteSnapshot(
    val latest: BigDecimal?,
    val prevClose: BigDecimal?,
    val open: BigDecimal?,
    val dayHigh: BigDecimal?,
    val dayLow: BigDecimal?,
    val name: String?,
)

/** 一根日 K（不复权口径）；数值保持字符串原样，计算时再转 [BigDecimal] */
data class QuoteBar(
    val date: String,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
)

/**
 * 取值项计算（方案文档 §4.2 / §5.3 那两张表的实现）。
 *
 * **纯函数**：不碰网络、不碰 Android 框架类（除 `BigDecimal`），数据全部由
 * [Input] 注入 —— 取数在 `feature/aistare/quote/`，这里只管算。
 *
 * 口径（§5.3，别改）：
 *  - `highN`/`lowN` 用最高/最低价列；`highCloseN`/`lowCloseN` 用收盘价列；
 *  - `maN` 是简单算术平均，N 含今日；
 *  - 不复权；
 *  - 涨停/跌停 = `round(昨收 × (1±幅度), 2)`，幅度按代码前缀 + ST 判定；
 *  - 全程 [BigDecimal]，输出统一 `setScale(2, HALF_UP)`，绝不用 Double 乘。
 */
object QuickConfigEngine {

    /** 日 K 提供者：按需取最近 n 根（升序）；不足 n 根返回 null（该项判失败，不凑数） */
    fun interface KlineProvider {
        fun kline(n: Int): List<QuoteBar>?
    }

    /** 一次求值的全部输入 */
    data class Input(
        /** 股票代码（如 300045） */
        val code: String,
        /** 市场（1 = 深圳，2 = 上海，见方案文档 §1.4） */
        val market: String,
        val snapshot: QuoteSnapshot?,
        /** 宿主持仓成本价；无持仓 / 拿不到 = null（`cost` 项判失败） */
        val cost: BigDecimal?,
        val klines: KlineProvider,
    )

    // ------------------------------------------------------------ 对外入口

    /**
     * 算一个槽的值。任一取值项失败 → **整体失败返回 null**（方案文档 §3.2：
     * 取数失败不写值，绝不写半截或 0）。
     */
    fun evaluateSlot(slot: QuickConfigStore.Slot, input: Input): BigDecimal? {
        if (slot.terms.isEmpty()) return null
        val values = ArrayList<BigDecimal>(slot.terms.size)
        for (term in slot.terms) {
            val v = evaluateTerm(term, input) ?: return null
            values.add(v)
        }
        // 聚合：MAX / MIN / AVG（§4.1）。单值时三者等价。
        val scale = 2
        return when (slot.agg) {
            QuickConfigStore.AGG_MIN -> values.min()
            QuickConfigStore.AGG_AVG -> {
                var sum = BigDecimal.ZERO
                for (v in values) sum = sum.add(v)
                sum.divide(BigDecimal(values.size), scale, RoundingMode.HALF_UP)
            }
            else -> values.max()
        }
    }

    /**
     * 算一个方案的「涨到 / 跌到」，并做写入前的区间校验（§8：算错价格写进盯盘策略
     * 低概率但严重，宁可整体放弃）。
     *
     * 校验：`0.01 < 价 < 昨收 × 1.5`（有昨收时）且 **涨到 > 跌到**；越界返回 null。
     */
    fun evaluate(scheme: QuickConfigStore.Scheme, input: Input): Pair<BigDecimal, BigDecimal>? {
        val up = evaluateSlot(scheme.up, input) ?: return null
        val down = evaluateSlot(scheme.down, input) ?: return null
        if (up <= down) return null
        val prevClose = input.snapshot?.prevClose
        if (prevClose != null && prevClose.signum() > 0) {
            val ceiling = prevClose.multiply(BigDecimal("1.5")).setScale(2, RoundingMode.HALF_UP)
            if (up >= ceiling || down < BigDecimal("0.01")) return null
        }
        return up to down
    }

    // ------------------------------------------------------------ 单项

    private fun evaluateTerm(term: QuickConfigStore.Term, input: Input): BigDecimal? {
        val p = term.param
        return when (term.source) {
            "latest" -> input.snapshot?.latest
            "prevClose" -> input.snapshot?.prevClose
            "open" -> input.snapshot?.open
            "dayHigh" -> input.snapshot?.dayHigh
            "dayLow" -> input.snapshot?.dayLow
            "limitUp" -> limitPrice(input, up = true)
            "limitDown" -> limitPrice(input, up = false)
            "cost" -> input.cost
            "highN" -> extreme(input, n(p), QuoteBar::high, max = true)
            "lowN" -> extreme(input, n(p), QuoteBar::low, max = false)
            "highCloseN" -> extreme(input, n(p), QuoteBar::close, max = true)
            "lowCloseN" -> extreme(input, n(p), QuoteBar::close, max = false)
            "maN" -> ma(input, n(p))
            "upPct" -> pctOf(input.snapshot?.latest, p, up = true)
            "downPct" -> pctOf(input.snapshot?.latest, p, up = false)
            "prevUpPct" -> pctOf(input.snapshot?.prevClose, p, up = true)
            "prevDownPct" -> pctOf(input.snapshot?.prevClose, p, up = false)
            "fixed" -> p[QuickConfigCatalog.PARAM_VALUE]?.toBigDecimalOrNull()
            else -> null // 目录外的 source：解析层已丢，这里再兜一层
        }?.setScale(2, RoundingMode.HALF_UP)
    }

    private fun n(p: Map<String, String>): Int = p[QuickConfigCatalog.PARAM_PERIOD]?.toIntOrNull() ?: 0

    // ------------------------------------------------------------ 各口径

    /** N 日极值：kline 不足 N 根判失败（§5.3「停牌 / 数据不足」，不用现有数据凑） */
    private fun extreme(
        input: Input,
        n: Int,
        column: (QuoteBar) -> String,
        max: Boolean,
    ): BigDecimal? {
        if (n <= 0) return null
        val bars = input.klines.kline(n) ?: return null
        if (bars.size < n) return null
        val last = bars.takeLast(n)
            .map { column(it).toBigDecimalOrNull() }
            .takeIf { list -> list.all { it != null } }
            ?.mapNotNull { it } ?: return null
        return if (max) last.max() else last.min()
    }

    /** MA N：最近 N 根收盘价的简单算术平均（不是 EMA，§5.3） */
    private fun ma(input: Input, n: Int): BigDecimal? {
        if (n <= 0) return null
        val bars = input.klines.kline(n) ?: return null
        if (bars.size < n) return null
        var sum = BigDecimal.ZERO
        for (bar in bars.takeLast(n)) {
            val c = bar.close.toBigDecimalOrNull() ?: return null
            sum = sum.add(c)
        }
        return sum.divide(BigDecimal(n), 2, RoundingMode.HALF_UP)
    }

    /** ±p%：基于最新价或昨收 */
    private fun pctOf(base: BigDecimal?, p: Map<String, String>, up: Boolean): BigDecimal? {
        base ?: return null
        val pct = p[QuickConfigCatalog.PARAM_PCT]?.toDoubleOrNull() ?: return null
        if (pct < 0.1 || pct > 30.0) return null
        val factor = BigDecimal(if (up) 1.0 + pct / 100.0 else 1.0 - pct / 100.0)
        return base.multiply(factor)
    }

    // ------------------------------------------------------------ 涨停 / 跌停

    /**
     * 涨停 / 跌停价：`round(昨收 × (1±幅度), 2)`。
     *
     * 幅度按代码前缀判定（方案文档 §4.2 的表），名字带 ST 降幅：
     *
     * | 板块 | 前缀 | 平常 | ST |
     * |---|---|---|---|
     * | 沪市主板 | 60 | 10% | 5% |
     * | 深市主板 | 00 | 10% | 5% |
     * | 创业板 | 30 | 20% | 12% |
     * | 科创板 | 688 | 20% | 12% |
     * | 北交所 | 4 / 8 | 30% | —（按 30%） |
     *
     * 指数没有涨跌停（沪市 000xxx、深市 399xxx、板块 880xxx）→ 判失败。
     */
    private fun limitPrice(input: Input, up: Boolean): BigDecimal? {
        val snapshot = input.snapshot ?: return null
        val prevClose = snapshot.prevClose ?: return null
        if (prevClose.signum() <= 0) return null
        val factor = limitFactor(input.code, input.market, snapshot.name) ?: return null
        val signed = if (up) BigDecimal.ONE.add(factor) else BigDecimal.ONE.subtract(factor)
        return prevClose.multiply(signed)
    }

    /** 返回涨跌幅幅度（如 0.10 = 10%）；指数 / 认不出返回 null */
    fun limitFactor(code: String, market: String, name: String?): BigDecimal? {
        // 指数：沪市 000 开头全是指数（沪市股票是 6 开头）；深市 399；板块 880
        if (market == "2" && code.startsWith("000")) return null
        if (code.startsWith("399") || code.startsWith("880")) return null

        val isSt = name?.contains("ST") == true
        val pct = when {
            code.startsWith("688") -> if (isSt) 12.0 else 20.0   // 科创板
            code.startsWith("30") -> if (isSt) 12.0 else 20.0    // 创业板
            code.startsWith("60") -> if (isSt) 5.0 else 10.0     // 沪市主板
            code.startsWith("00") -> if (isSt) 5.0 else 10.0     // 深市主板
            code.startsWith("4") || code.startsWith("8") -> 30.0 // 北交所（不细分 ST）
            else -> return null
        }
        return BigDecimal(pct).divide(BigDecimal(100), 4, RoundingMode.HALF_UP)
    }
}
