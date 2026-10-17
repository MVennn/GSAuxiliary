package io.github.MVennn.GSAuxiliary.core

import java.math.BigDecimal

/**
 * 行情数据模型（实时快照 + 日 K）。
 *
 * 独立成文件的缘由：这两个数据类被 `QuickConfigEngine`（算价）与
 * `feature/aistare/quote/` 全部四个取数源共用，不属于算价逻辑本身，
 * 放在 [QuickConfigEngine] 里是「被谁第一次用就定义在谁家」的痕迹。
 */
// ── 实时快照 ──────────────────────────────────────────────────────────────

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

// ── 日 K ─────────────────────────────────────────────────────────────────

/** 一根日 K（不复权口径）；数值保持字符串原样，计算时再转 [BigDecimal] */
data class QuoteBar(
    val date: String,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
)
