package io.github.MVennn.GSAuxiliary.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 取值项计算（方案文档 §4.2 / §5.3 那两张表的实现）。
 *
 * **纯函数**：不碰网络、不碰 Android 框架类（除 `BigDecimal`），数据全部由
 * [Input] 注入 —— 取数在 `feature/aistare/quote/`，这里只管算。
 *
 * 口径（§5.3，别改）：
 *  - `highN`/`lowN` 用最高/最低价列；`highCloseN`/`lowCloseN` 用收盘价列；
 *  - `historyHigh`/`historyLow` 是**从今天往回第一个没被后来超越的高点 / 低点**
 *    （「N 日新高 / 新低」的通用形态，N 不设上限、优先取最近的）；
 *  - `maN` 是简单算术平均，N 含今日；
 *  - 不复权；
 *  - 涨停/跌停：**优先用行情接口给的官方价**；接口没给时才用兜底表
 *    `round(昨收 × (1±幅度), 2)`（幅度只按板块前缀 —— 2026-07-06 起主板 ST
 *    已与普通股同为 10%，创业板/科创板 ST 同为 20%，ST 不再单列一档）；
 *  - 全程 [BigDecimal]，输出统一 `setScale(2, HALF_UP)`，绝不用 Double 乘。
 */
object QuickConfigEngine {

    /** 「前高 / 前低」要的日 K 根数（腾讯接口单次上限 2000 根 ≈ 8 年） */
    const val HISTORY_BARS = 2000

    /** 含历史项时的价格上限倍数（见 [evaluate] 的注释） */
    private val HISTORY_CEILING_FACTOR = BigDecimal("5")

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
     * 方案里含「最近前高 / 前低」时上限放宽到 `× 5` —— 波段点可能在深度回撤之前，
     * 离现价很远（这类标的本来就得给足空间）。
     */
    fun evaluate(scheme: QuickConfigStore.Scheme, input: Input): Pair<BigDecimal, BigDecimal>? {
        val up = evaluateSlot(scheme.up, input) ?: return null
        val down = evaluateSlot(scheme.down, input) ?: return null
        if (up <= down) return null
        val prevClose = input.snapshot?.prevClose
        if (prevClose != null && prevClose.signum() > 0) {
            // 「最近前高 / 前低」是回撤之前的波段点，可能远在现价另一侧（腰斩股尤其明显），
            // 用 1.5 倍会把**合法**的「涨到前高」判成算错；含这类项的方案放宽到 5 倍。
            val factor = if (hasHistoryTerm(scheme.up) || hasHistoryTerm(scheme.down)) {
                HISTORY_CEILING_FACTOR
            } else {
                BigDecimal("1.5")
            }
            val ceiling = prevClose.multiply(factor).setScale(2, RoundingMode.HALF_UP)
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
            "historyHigh" -> nearestTerm(input, QuoteBar::high, max = true)
            "historyLow" -> nearestTerm(input, QuoteBar::low, max = false)
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

    /** 方案里有没有「历史」类取值项（决定要不要放宽价格上限） */
    private fun hasHistoryTerm(slot: QuickConfigStore.Slot): Boolean =
        slot.terms.any { it.source == "historyHigh" || it.source == "historyLow" }

    /**
     * 最近前高 / 最近前低：**离今天最近的那个「顶」/「底」**（且尚未被今天突破）。
     *
     * ## 判定（2026-10-17 用户第三次校准后的定稿）
     *
     * 一个价位要算「前高」，必须同时满足：
     *  1. **它自己创了新高**：高点不低于前一天（`>=`，容忍双顶）；
     *  2. **之后回落**：高点不低于后一天 —— 即它是一个局部高点，不是下跌途中的反弹；
     *  3. **高于今日最高**：还没被今天突破（已破的价位不构成上方压力）。
     *
     * 前低三项全部反向（不高于前一天、不高于后一天、低于今日最低）。
     * 从最新一根往回扫，**第一个满足的就是答案** —— 时间上离今天最近，优先级最高。
     *
     * ## 三次口径都错在哪（别再改回去）
     *
     * - **全历史极值**：茅台给 2627.88（2021 年），「涨到」永远不触发。
     * - **左右各 5 根确认的波段峰**：左侧 5 根一卡，中芯国际 9-30 的 118.18 会被
     *   9-22 的 125.61 顶掉，结果给 125.61 —— 比最近的顶远了一大截。
     * - **「高于其后所有高点」**：中芯给 105.81，可 105.81 比前一天的 107.44 还低，
     *   只是下跌途中的反弹，不是顶。用户原话：「105.81 应该不算是创新高吧，
     *   如果真要推应该是 9-30 号的 118.18」。
     *
     * ## 实测（2026-10-17 收盘）
     *
     * | 股票 | 前高 | 日期 |
     * |---|---|---|
     * | 中芯国际 | **118.18** | 2026-09-30 |
     * | 贵州茅台 | **1338.86** | 2026-09-04 |
     * | 平安银行 | **11.89** | 2026-10-09 |
     *
     * ## 边界
     *
     * - 今天就是历史最高（上方一个顶都没有）→ **判失败**，不编低于现价的数；
     * - 数据不足 3 根（次新股 / 停牌）→ 失败；
     * - 不需要现价参与：基准用今日最高 / 最低（K 线最后一根）。
     */
    private fun nearestTerm(
        input: Input,
        column: (QuoteBar) -> String,
        max: Boolean,
    ): BigDecimal? {
        val bars = input.klines.kline(HISTORY_BARS) ?: return null
        if (bars.size < 3) return null
        val values = bars.map { column(it).toBigDecimalOrNull() ?: return null }
        // 今日那根是基准（「1 日新高」本身不算前高），也不作为候选
        val base = values.last()
        for (i in values.size - 2 downTo 1) {
            val v = values[i]
            val isPeak = if (max) {
                v >= values[i - 1] && v >= values[i + 1]
            } else {
                v <= values[i - 1] && v <= values[i + 1]
            }
            if (!isPeak) continue
            if (if (max) v > base else v < base) return v
        }
        return null
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
        // ⚠️ 必须 valueOf 而不是 BigDecimal(double)：0.95 的 double 是
        // 0.9499999…，11.50 × 它 = 10.9249… → HALF_UP 进成 10.92，比正确值
        // 10.93 少一分（对拍实测踩中）。valueOf 走 Double.toString 的最短表示，
        // "0.95" → 精确的 0.95。文档红线「绝不用 Double 乘」说的就是这个。
        val factor = BigDecimal.valueOf(if (up) 1.0 + pct / 100.0 else 1.0 - pct / 100.0)
        return base.multiply(factor)
    }

    // ------------------------------------------------------------ 涨停 / 跌停

    /**
     * 涨停 / 跌停价：`round(昨收 × (1±幅度), 2)`。
     *
     * 幅度按代码前缀判定，见 [limitFactor]（ST 已不是独立幅度档，
     * 旧口径的「主板 ST 5%、创业/科创 ST 12%」作废，别照旧表改回去）。
     *
     * 指数没有涨跌停（沪市 000xxx、深市 399xxx、板块 880xxx）→ 判失败。
     */
    private fun limitPrice(input: Input, up: Boolean): BigDecimal? {
        val snapshot = input.snapshot ?: return null
        val prevClose = snapshot.prevClose ?: return null
        if (prevClose.signum() <= 0) return null
        if (snapshot.noPriceLimit) return null
        (if (up) snapshot.limitUp else snapshot.limitDown)?.let { return it }
        val factor = limitFactor(input.code, input.market, snapshot.name) ?: return null
        val signed = if (up) BigDecimal.ONE.add(factor) else BigDecimal.ONE.subtract(factor)
        return prevClose.multiply(signed)
    }

    /**
     * 返回涨跌幅幅度（如 0.10 = 10%）；指数 / 认不出返回 null。
     *
     * ## ST 已经不是一条独立的幅度档（2026-10-17 真机对拍修正）
     *
     * 旧表写「主板 ST 5%、创业板/科创板 ST 12%」，与实盘全对不上：
     *  - **主板 ST 早已不是 5%**：沪深交易所 2026-07-06 起把主板风险警示股票
     *    涨跌幅由 5% 调整为 10%（与主板普通股一致，深交所官网有正式公告）；
     *  - **12% 从来不是涨跌幅** —— 那是「ST 股连续三日偏离 ±12%」的**异常波动**
     *    监控指标，当年写表时抄串了；创业板/科创板 ST 的涨跌幅一直是 20%
     *    （实测 *ST开元 300338：昨收 2.68，涨停 3.22 = +20%）。
     *
     * 所以这张兜底表不再按名称区分 ST（[name] 仅为签名兼容保留）。
     * 主路径的涨跌停价来自行情接口的官方字段（见 [limitPrice]），这张表只在
     * DOM 兜底通道才被用到。
     */
    fun limitFactor(code: String, market: String, name: String?): BigDecimal? {
        // 指数：沪市 000 开头全是指数（沪市股票是 6 开头）；深市 399；板块 880
        if (market == "2" && code.startsWith("000")) return null
        if (code.startsWith("399") || code.startsWith("880")) return null

        val pct = when {
            code.startsWith("68") -> 20.0                          // 科创板（688 / 689 CDR）
            code.startsWith("30") -> 20.0                          // 创业板
            code.startsWith("60") -> 10.0                          // 沪市主板
            code.startsWith("00") -> 10.0                          // 深市主板
            code.startsWith("920") || code.startsWith("4") ||
                code.startsWith("8") -> 30.0                       // 北交所
            else -> return null
        }
        return BigDecimal(pct).divide(BigDecimal(100), 4, RoundingMode.HALF_UP)
    }
}
