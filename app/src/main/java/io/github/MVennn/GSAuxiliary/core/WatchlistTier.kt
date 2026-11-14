package io.github.MVennn.GSAuxiliary.core

import java.math.BigDecimal

/**
 * 「涨 / 跌 / 平」三档的判定 —— 纯逻辑，不碰 Android，可单测。
 *
 * ## 为什么要单独一个文件
 *
 * 分档配色的**唯一依据**就是这一档：涨用涨的颜色、跌用跌的颜色、平盘用平盘的颜色。
 * 判定散在功能代码里就没法验证「`+2.34%` 真的是涨、`0.00%` 真的是平」这类边界，
 * 而判错一档的后果是**整列颜色反着来**（涨的显示成跌的色），比不着色更刺眼。
 *
 * ## 两个入口
 *
 *  - [ofZdf]：数据项上的涨跌幅字段（实测字段名 `zdf`，值多为 `BigDecimal`）；
 *  - [ofText]：行内那格文案（`+2.34%` / `-1.12%` / `0.00%` / `--`）。
 *
 * 两个入口都**用 `null` 表示「读不出来」**，而不是直接返回 [FLAT]：
 * 读不出与真的平盘是两件事 —— 调用方要先试字段、再试文案，只有两条路都读不出
 * 才落到 [FLAT]。若这里把「读不出」也返回 FLAT，字段缺失的行会被当成平盘，
 * 而文案明明写着 `+5.20%`。
 */
object WatchlistTier {

    /** 上涨 */
    const val UP = "up"

    /** 下跌 */
    const val DOWN = "down"

    /** 平盘（涨跌幅正好为 0，或三种来源都读不出） */
    const val FLAT = "flat"

    /** 三档的固定顺序：涨 → 跌 → 平（界面、编码、预览都按这个顺序） */
    val ALL: List<String> = listOf(UP, DOWN, FLAT)

    /**
     * 按涨跌幅字段判定。
     *
     * @param value 宿主数据项的字段值：`BigDecimal` / `Double` / `String`（`"2.34"` 或 `"+2.34%"`）
     * @return 档位；`null` = 这个值是空的或不是数字（调用方接着试行内文案）
     */
    fun ofZdf(value: Any?): String? = when (value) {
        null -> null
        is Number -> fromDecimal(decimalOf(value.toString()))
        is CharSequence -> ofText(value.toString())
        else -> runCatching { ofText(value.toString()) }.getOrNull()
    }

    /**
     * 按行内文案判定。
     *
     * 认得的写法：`+2.34%` / `-1.12%` / `0.00%` / `2.34%` / `1,234`（带千分位）。
     * 认不得的（`--` / 空串 / 非数字）返回 `null`。
     */
    fun ofText(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val cleaned = raw.removeSuffix("%").replace(",", "").trim()
        if (cleaned.isEmpty()) return null
        return fromDecimal(decimalOf(cleaned))
    }

    /** 字符串转十进制数；`NaN` / `Infinity` / 非数字一律 `null` */
    private fun decimalOf(text: String): BigDecimal? =
        runCatching { BigDecimal(text) }.getOrNull()

    private fun fromDecimal(value: BigDecimal?): String? = when {
        value == null -> null
        value.signum() > 0 -> UP
        value.signum() < 0 -> DOWN
        else -> FLAT
    }
}
