package io.github.MVennn.GSAuxiliary.core.zxcopy

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 「自选分组复制」的字段目录 —— 设置页勾选字段、目标进程读宿主字段、
 * 所有导出格式的列定义，都以这张表为唯一权威。
 *
 * ## 为什么放 `core/`
 *
 * 三个地方都要用：目标进程读 `StockInfo`、模块 App 的设置页、纯逻辑单测。
 * `ui/` 不能引用 `feature/`（架构红线 3），所以目录与格式化必须留在
 * `core/zxcopy/`；本文件只用 `java.math` 与纯 Kotlin，模块 App 与目标进程都能加载。
 *
 * ## 字段名从哪来
 *
 * 候选名来自 2026-10-22 探针对 `com.guosen.android.quotes.data.StockInfo` 的类 dump
 * （`zdf` / `zd` / `zs` / `zf` / `hsl` / `lb` / `money_ltsz` / `money_zsz` / `cjje` /
 * `cjsl` / `syl` / `sjl` / `mBelongHyName` / `jrkp` / `zrsp` / `highPrice` / `lowPrice` /
 * `zdf_3_day` …）。多候选按顺序取第一个非空；宿主升级改名时只需要往候选里加一项。
 *
 * ## 量纲
 *
 * ⚠️ `zdf` / `zd` / `zs` / `zf` / `hsl` / `lb` 的原始量纲尚未实测（12.39 还是 0.1239）。
 * [Spec.percentScale] 是唯一开关：若实测发现是小数比例，把对应字段的 `percentScale`
 * 改成 100.0 即可，不需要动格式化逻辑。
 */
object ZxCopyFieldCatalog {

    enum class Group { BASIC, QUOTE, CAP, RANGE, OTHER }

    enum class Type {
        TEXT,
        CODE,
        MARKET,
        PRICE,
        SIGNED_PRICE,
        PERCENT,
        SIGNED_PERCENT,
        MONEY,
        NUMBER,
        DATE,
    }

    /**
     * 一个字段的定义。
     *
     * @param candidates 宿主字段候选（按顺序取第一个非空）
     * @param alignRight 表格 / 图片里是否右对齐（数值列）
     * @param widthWeight 自动列宽的相对权重（名称列宽一点、代码列窄一点）
     * @param percentScale 百分比量纲：1.0 = 原始值已是百分数；100.0 = 原始值是 0.1239 这种比例
     */
    data class Spec(
        val id: String,
        val group: Group,
        val type: Type,
        val candidates: List<String>,
        val alignRight: Boolean = false,
        val widthWeight: Float = 1f,
        val percentScale: Double = 1.0,
        val builtinLabel: String,
    )

    val ALL: List<Spec> = listOf(
        // ---- 基础 ----
        Spec("name", Group.BASIC, Type.TEXT, ZxCopyHostFields.NAME_FIELDS.toList(), widthWeight = 1.7f, builtinLabel = "名称"),
        Spec("code", Group.BASIC, Type.CODE, ZxCopyHostFields.CODE_FIELDS.toList(), widthWeight = 0.9f, builtinLabel = "代码"),
        Spec("market", Group.BASIC, Type.MARKET, ZxCopyHostFields.MARKET_FIELDS.toList(), widthWeight = 0.6f, builtinLabel = "市场"),

        // ---- 行情 ----
        Spec("price", Group.QUOTE, Type.PRICE, ZxCopyHostFields.PRICE_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "最新价"),
        Spec("change", Group.QUOTE, Type.SIGNED_PRICE, ZxCopyHostFields.CHANGE_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "涨跌"),
        Spec("changePct", Group.QUOTE, Type.SIGNED_PERCENT, ZxCopyHostFields.CHANGE_PCT_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "涨幅"),
        Spec("speed", Group.QUOTE, Type.SIGNED_PERCENT, ZxCopyHostFields.SPEED_FIELDS.toList(), alignRight = true, widthWeight = 0.8f, builtinLabel = "涨速"),
        Spec("amplitude", Group.QUOTE, Type.PERCENT, ZxCopyHostFields.AMPLITUDE_FIELDS.toList(), alignRight = true, widthWeight = 0.8f, builtinLabel = "振幅"),
        Spec("turnover", Group.QUOTE, Type.PERCENT, ZxCopyHostFields.TURNOVER_FIELDS.toList(), alignRight = true, widthWeight = 0.8f, builtinLabel = "换手率"),
        Spec("volumeRatio", Group.QUOTE, Type.NUMBER, ZxCopyHostFields.VOLUME_RATIO_FIELDS.toList(), alignRight = true, widthWeight = 0.8f, builtinLabel = "量比"),
        Spec("amount", Group.QUOTE, Type.MONEY, ZxCopyHostFields.AMOUNT_FIELDS.toList(), alignRight = true, widthWeight = 1f, builtinLabel = "成交额"),
        Spec("volume", Group.QUOTE, Type.NUMBER, ZxCopyHostFields.VOLUME_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "成交量"),

        // ---- 市值与估值 ----
        Spec("floatCap", Group.CAP, Type.MONEY, ZxCopyHostFields.FLOAT_CAP_FIELDS.toList(), alignRight = true, widthWeight = 1f, builtinLabel = "流通市值"),
        Spec("totalCap", Group.CAP, Type.MONEY, ZxCopyHostFields.TOTAL_CAP_FIELDS.toList(), alignRight = true, widthWeight = 1f, builtinLabel = "总市值"),
        Spec("pe", Group.CAP, Type.NUMBER, ZxCopyHostFields.PE_FIELDS.toList(), alignRight = true, widthWeight = 0.8f, builtinLabel = "市盈率"),
        Spec("pb", Group.CAP, Type.NUMBER, ZxCopyHostFields.PB_FIELDS.toList(), alignRight = true, widthWeight = 0.8f, builtinLabel = "市净率"),
        Spec("dividend", Group.CAP, Type.TEXT, ZxCopyHostFields.DIVIDEND_FIELDS.toList(), alignRight = true, widthWeight = 0.8f, builtinLabel = "股息率"),

        // ---- 区间 ----
        Spec("open", Group.RANGE, Type.PRICE, ZxCopyHostFields.OPEN_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "今开"),
        Spec("prevClose", Group.RANGE, Type.PRICE, ZxCopyHostFields.PREV_CLOSE_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "昨收"),
        Spec("high", Group.RANGE, Type.PRICE, ZxCopyHostFields.HIGH_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "最高"),
        Spec("low", Group.RANGE, Type.PRICE, ZxCopyHostFields.LOW_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "最低"),
        Spec("avgPrice", Group.RANGE, Type.PRICE, ZxCopyHostFields.AVG_PRICE_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "均价"),
        Spec("zdf3", Group.RANGE, Type.SIGNED_PERCENT, ZxCopyHostFields.ZDF3_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "3日涨幅"),
        Spec("zdf5", Group.RANGE, Type.SIGNED_PERCENT, ZxCopyHostFields.ZDF5_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "5日涨幅"),
        Spec("zdf20", Group.RANGE, Type.SIGNED_PERCENT, ZxCopyHostFields.ZDF20_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "20日涨幅"),
        Spec("zdf60", Group.RANGE, Type.SIGNED_PERCENT, ZxCopyHostFields.ZDF60_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "60日涨幅"),
        Spec("zdfYear", Group.RANGE, Type.SIGNED_PERCENT, ZxCopyHostFields.ZDF_YEAR_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "年初至今"),

        // ---- 其他 ----
        Spec("industry", Group.OTHER, Type.TEXT, ZxCopyHostFields.INDUSTRY_FIELDS.toList(), widthWeight = 1.2f, builtinLabel = "行业"),
        Spec("label", Group.OTHER, Type.TEXT, ZxCopyHostFields.LABEL_FIELDS.toList(), widthWeight = 0.8f, builtinLabel = "标签"),
        Spec("addTime", Group.OTHER, Type.DATE, ZxCopyHostFields.ADD_TIME_FIELDS.toList(), widthWeight = 1.2f, builtinLabel = "加入时间"),
        Spec("priceDate", Group.OTHER, Type.DATE, ZxCopyHostFields.PRICE_DATE_FIELDS.toList(), widthWeight = 1.2f, builtinLabel = "行情时间"),
        Spec("mainFund", Group.OTHER, Type.MONEY, ZxCopyHostFields.MAIN_FUND_FIELDS.toList(), alignRight = true, widthWeight = 1f, builtinLabel = "主力净流入"),
        Spec("mainFundPct", Group.OTHER, Type.SIGNED_PERCENT, ZxCopyHostFields.MAIN_FUND_PCT_FIELDS.toList(), alignRight = true, widthWeight = 0.9f, builtinLabel = "主力净流入占比"),
        Spec("nowVol", Group.OTHER, Type.NUMBER, ZxCopyHostFields.NOW_VOL_FIELDS.toList(), alignRight = true, widthWeight = 0.8f, builtinLabel = "现量"),
        Spec("weiBi", Group.OTHER, Type.SIGNED_PERCENT, ZxCopyHostFields.WEI_BI_FIELDS.toList(), alignRight = true, widthWeight = 0.8f, builtinLabel = "委比"),
    )

    val GROUP_ORDER = listOf(Group.BASIC, Group.QUOTE, Group.CAP, Group.RANGE, Group.OTHER)

    private val byId: Map<String, Spec> = ALL.associateBy { it.id }

    fun byId(id: String): Spec? = byId[id]

    /** 在 [ALL] 里的下标（设置页按它取 `zx_copy_field_names` 数组里的本地化名称） */
    fun index(id: String): Int = ALL.indexOfFirst { it.id == id }

    /** 目标进程 / 旧配置没有 labels 时的兜底标签（中文；设置页写入时会给当前语言） */
    fun builtinLabel(id: String): String? = byId[id]?.builtinLabel

    // ------------------------------------------------------------------ 格式化

    private val CODE = Regex("(?<!\\d)\\d{6}(?!\\d)")

    fun normalizeCode(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return CODE.find(s)?.value
    }

    /**
     * 原始值 → 显示文本；空值返回 null（调用方按规则的 `empty` 策略处理）。
     *
     * 解析失败时退回原始字符串的 trim —— 宁可显示宿主给的怪值，也不静默丢掉。
     */
    fun format(spec: Spec, raw: Any?): String? {
        if (raw == null) return null
        val text = rawText(raw)
        if (text.isEmpty() || text == "null") return null
        return when (spec.type) {
            Type.TEXT, Type.DATE -> text
            Type.CODE -> normalizeCode(text) ?: text
            Type.MARKET -> when (text) {
                "1" -> "深"
                "2" -> "沪"
                "5" -> "北"
                else -> text
            }
            Type.PRICE -> decimal(raw, 2, signed = false) ?: text
            Type.SIGNED_PRICE -> decimal(raw, 2, signed = true) ?: text
            Type.PERCENT -> percent(raw, spec.percentScale, signed = false) ?: text
            Type.SIGNED_PERCENT -> percent(raw, spec.percentScale, signed = true) ?: text
            Type.MONEY -> money(text)
            Type.NUMBER -> number(raw) ?: text
        }
    }

    /** 排序用数值；解析不出来返回 null（排序时排到最后） */
    fun sortValue(spec: Spec, raw: Any?): Double? {
        if (raw == null) return null
        return when (spec.type) {
            Type.PRICE, Type.SIGNED_PRICE, Type.NUMBER -> toDouble(raw)
            Type.PERCENT, Type.SIGNED_PERCENT -> toDouble(raw)?.times(spec.percentScale)
            Type.MONEY -> moneyValue(rawText(raw))
            Type.CODE, Type.TEXT, Type.DATE, Type.MARKET -> null
        }
    }

    private fun rawText(raw: Any): String = when (raw) {
        is String -> raw.trim()
        is BigDecimal -> raw.stripTrailingZeros().toPlainString()
        else -> raw.toString().trim()
    }

    private fun toDouble(raw: Any): Double? = when (raw) {
        is BigDecimal -> raw.toDouble()
        is Number -> raw.toDouble()
        is String -> raw.trim().replace(",", "").toDoubleOrNull()
        else -> raw.toString().trim().toDoubleOrNull()
    }

    private fun decimal(raw: Any, scale: Int, signed: Boolean): String? {
        val d = when (raw) {
            is BigDecimal -> raw
            is Number -> BigDecimal(raw.toString())
            is String -> raw.trim().replace(",", "").toBigDecimalOrNull()
            else -> raw.toString().trim().toBigDecimalOrNull()
        } ?: return null
        val v = d.setScale(scale, RoundingMode.HALF_UP)
        val body = v.toPlainString()
        return if (signed && v.signum() > 0) "+$body" else body
    }

    private fun percent(raw: Any, scale: Double, signed: Boolean): String? {
        val d = when (raw) {
            is BigDecimal -> raw
            is Number -> BigDecimal(raw.toString())
            is String -> raw.trim().replace(",", "").toBigDecimalOrNull()
            else -> raw.toString().trim().toBigDecimalOrNull()
        } ?: return null
        val v = d.multiply(BigDecimal(scale)).setScale(2, RoundingMode.HALF_UP)
        val body = v.toPlainString() + "%"
        return if (signed && v.signum() > 0) "+$body" else body
    }

    private fun number(raw: Any): String? {
        val d = when (raw) {
            is BigDecimal -> raw
            is Number -> BigDecimal(raw.toString())
            is String -> raw.trim().replace(",", "").toBigDecimalOrNull()
            else -> raw.toString().trim().toBigDecimalOrNull()
        } ?: return null
        return d.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    /** 已经是「1158.13亿」这种显示串就原样用；纯数字才换算成亿 / 万 */
    private fun money(text: String): String {
        if (text.contains('亿') || text.contains('万') || text.contains('元')) return text
        val d = text.replace(",", "").toBigDecimalOrNull() ?: return text
        val abs = d.abs()
        return when {
            abs >= BigDecimal("100000000") -> d.divide(BigDecimal("100000000"), 2, RoundingMode.HALF_UP).toPlainString() + "亿"
            abs >= BigDecimal("10000") -> d.divide(BigDecimal("10000"), 2, RoundingMode.HALF_UP).toPlainString() + "万"
            else -> d.setScale(2, RoundingMode.HALF_UP).toPlainString()
        }
    }

    private fun moneyValue(text: String): Double? {
        val s = text.trim().replace(",", "")
        if (s.isEmpty()) return null
        val unit = when {
            s.endsWith("亿") -> 1e8
            s.endsWith("万") -> 1e4
            else -> 1.0
        }
        val body = s.removeSuffix("亿").removeSuffix("万").removeSuffix("元").trim()
        return body.toDoubleOrNull()?.times(unit)
    }
}
