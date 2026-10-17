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
    /** 用于 ST 判定（涨跌停兜底表已不依赖它，留作日志与未来口径用） */
    val name: String?,
    /**
     * 行情接口给的**官方涨跌停价**（腾讯字段 47/48、东财 f51/f52）。
     * 有值时引擎直接用 —— ST 幅度调整、新股规则这类变化不用跟着改表。
     * null = 本通道没给（降级到兜底表算）。
     */
    val limitUp: BigDecimal? = null,
    val limitDown: BigDecimal? = null,
    /**
     * 接口**明确**说该标的当前没有涨跌停（-1：新股上市首日、退市整理期、停牌）。
     * 与「没给」（[limitUp] 为 null 但可兜底表算）是两回事：明确没有就该判失败，
     * 而不是编一个名义涨停价出来。
     */
    val noPriceLimit: Boolean = false,
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
 *  - `historyHigh`/`historyLow` 是**现价上方最近 / 下方最近的波段高点 / 低点**（最近前高 / 前低）；
 *  - `maN` 是简单算术平均，N 含今日；
 *  - 不复权；
 *  - 涨停/跌停：**优先用行情接口给的官方价**；接口没给时才用兜底表
 *    `round(昨收 × (1±幅度), 2)`（幅度只按板块前缀 —— 2026-07-06 起主板 ST
 *    已与普通股同为 10%，创业板/科创板 ST 同为 20%，ST 不再单列一档）；
 *  - 全程 [BigDecimal]，输出统一 `setScale(2, HALF_UP)`，绝不用 Double 乘。
 */
object QuickConfigEngine {

    /**
     * 「前高 / 前低」的确认窗口：左右各看 [SWING_K] 根。
     *
     * 取 5 是跟用户对齐过的实测口径 —— 茅台（2026-10-17）用 5 根确认出来是
     * **1338.86（2026-09-04）**，正是他要的那个价位；3~4 根会把 9-23 的
     * 小反抽（1271.5）也算成波峰，5 根以上结果一致。
     */
    const val SWING_K = 5

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
     * 方案里含「历史前高 / 前低」时上限放宽到 `× 5` —— 历史价位本来就可能离现价很远。
     */
    fun evaluate(scheme: QuickConfigStore.Scheme, input: Input): Pair<BigDecimal, BigDecimal>? {
        val up = evaluateSlot(scheme.up, input) ?: return null
        val down = evaluateSlot(scheme.down, input) ?: return null
        if (up <= down) return null
        val prevClose = input.snapshot?.prevClose
        if (prevClose != null && prevClose.signum() > 0) {
            // 历史项可以离现价很远（茅台现价 1400、历史前高 2627 = 1.88 倍），
            // 用 1.5 倍会把**合法**的「涨到历史前高」判成算错；这类方案把上限放宽到 5 倍。
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
            "historyHigh" -> swingTerm(input, QuoteBar::high, max = true)
            "historyLow" -> swingTerm(input, QuoteBar::low, max = false)
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
     * 最近前高 / 前低：**现价上方最近的那个波段高点 / 下方最近的那个波段低点**。
     *
     * ## 为什么不是「历史最高」
     *
     * 茅台的历史最高是 2021 年的 2627.88，现价 1329 —— 拿它设「涨到」等于永远不触发。
     * 用户要的是「前面站着的、比我高的那个人里离我最近的那个」：现价 1329 时上方有
     * 1363.35、1568…，最近的是 **1338.86（2026-09-04）**。
     *
     * 反过来说，**低于现价的前高不算**：9-23 那个 1271.5 的小反抽虽然也是波段高点，
     * 但它在现价下面，涨到它毫无意义，直接跳过。
     *
     * ## 判定
     *
     * 1. 先找**已确认的波段高/低点**：左右各 [SWING_K] 根都不高于（不高于）/不低于它，
     *    含端点相等。右侧要 5 根，所以今天和最近 4 根天然不算「已经形成的峰」。
     * 2. 从最新一根**往回**扫，第一个既已确认、又满足「前高 > 现价 / 前低 < 现价」的
     *    就是答案 —— 也就是「时间上离现在最近、且站在我上方（下方）的那个」。
     *    注意**不是价位上最接近**：茅台现价 1329 时，上方还有一个更近的 1338.00
     *    （更早形成的），但用户认的是**上一个**波峰 1338.86。
     * 3. 现价上方（下方）一个都没有（比如正在创历史新高）→ 退回**最近的那个波段高点**
     *    （不看价位），仍比「历史极值」有意义；再没有就退回全部历史极值（不含今日）。
     *
     * 术语：这就是技术分析里的「最近阻力位 / 上方最近的前高」（nearest overhead
     * swing high）。断言「不管它后来跌到哪我都认那个价」才叫**锚定前高**，不是这里要的。
     */
    private fun swingTerm(
        input: Input,
        column: (QuoteBar) -> String,
        max: Boolean,
    ): BigDecimal? {
        val bars = input.klines.kline(HISTORY_BARS) ?: return null
        if (bars.size < 2) return null
        val values = bars.map { column(it).toBigDecimalOrNull() ?: return null }
        val k = SWING_K
        val latest = input.snapshot?.latest
        var fallback: BigDecimal? = null
        // 从**最新一根往前**扫：第一个「已确认 + 在现价上方（下方）」的波段点就是答案
        for (i in bars.size - 1 - k downTo k) {
            val self = values[i]
            val left = values.subList(i - k, i)
            val right = values.subList(i + 1, i + k + 1)
            val peak = if (max) {
                left.all { self >= it } && right.all { self >= it }
            } else {
                left.all { self <= it } && right.all { self <= it }
            }
            if (!peak) continue
            if (fallback == null) fallback = self
            // 现价未知时退化为「最近的那个波段点」
            if (latest == null) return self
            if (if (max) self > latest else self < latest) return self
        }
        // 上方 / 下方一个都没有（正在创历史新高 / 新低）：退回最近的那个波段点
        if (fallback != null) return fallback
        // 次新股 / 一路单边没回调：退回全部历史极值（剔掉今天那一根）
        val today = todayInShanghai()
        val history = bars.filter { it.date < today }.map { column(it).toBigDecimalOrNull() ?: return null }
        if (history.isEmpty()) return null
        return if (max) history.max() else history.min()
    }

    /** `yyyy-MM-dd`（Asia/Shanghai）；只用 java.time，不碰 Android 框架 */
    private fun todayInShanghai(): String =
        java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()

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
