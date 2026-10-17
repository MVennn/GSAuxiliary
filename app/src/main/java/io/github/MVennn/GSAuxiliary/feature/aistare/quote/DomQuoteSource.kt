package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.QuoteBar
import io.github.MVennn.GSAuxiliary.core.QuoteSnapshot
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ③ 零成本兜底（§5.2）：从价格页自身的文本里抠最新价。
 *
 * 页面上写着「最新价:24.32 +9.45%」（DOM 是 H5，`uiautomator` 能看到，
 * 我们直接从 `document.body.innerText` 抓全文）。只能救 `latest`；
 * 昨收用 `latest / (1 + 涨跌幅)` 反推（有反推误差，仅当①②都挂时才用 ——
 * 顺序由 [QuoteSession] 保证）。
 *
 * `dayHigh` / `dayLow` / `open` 页面上没有 → null，用到它们的取值项判失败
 * （方案整体放弃 —— 这正是兜底通道该有的诚实）。
 */
class DomQuoteSource(private val pageText: String) : QuoteSource {

    override val name = "dom"

    /** 页面上只有最新价，没有 K 线 —— 日 K 请求直接排除它 */
    override val supportsKline = false

    override fun snapshot(code: String, market: String): QuoteSnapshot? {
        val m = REGEX.find(pageText) ?: run {
            GLog.w("一键配置取数：页面文本里找不到「最新价:…」")
            return null
        }
        val latest = m.groupValues[1].toBigDecimalOrNull() ?: return null
        if (latest.signum() <= 0) return null

        // 反推昨收：latest = prev × (1 + pct/100) → prev = latest / (1 + pct/100)
        val pctText = m.groupValues[2]
        val prevClose: BigDecimal? = pctText.toBigDecimalOrNull()?.let { pct ->
            runCatching {
                latest.divide(BigDecimal.ONE.add(pct.divide(BigDecimal(100), 6, RoundingMode.HALF_UP)), 2, RoundingMode.HALF_UP)
            }.getOrNull()
        }
        if (prevClose == null) {
            GLog.w("一键配置取数：页面涨跌幅反推昨收失败（$pctText）")
            return null
        }
        return QuoteSnapshot(
            latest = latest,
            prevClose = prevClose,
            open = null,
            dayHigh = null,
            dayLow = null,
            // 页面文本里没有名称（证券代码块是分开的 DOM），ST 判定做不了 → null
            name = null,
        )
    }

    override fun dailyKline(code: String, market: String, n: Int): List<QuoteBar>? = null

    companion object {
        /** 「最新价:24.32 +9.45%」—— 冒号全半角都兼容，百分号可带符号 */
        private val REGEX = Regex(
            pattern = "最新价[:：]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([+\\-]?[0-9]+(?:\\.[0-9]+)?)%",
        )
    }
}
