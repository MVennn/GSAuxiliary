package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.QuoteBar
import io.github.MVennn.GSAuxiliary.core.QuoteSnapshot
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.ref.WeakReference
import java.math.BigDecimal

/**
 * ① 宿主自己的行情数据（首选通道，§5.2）—— 反射实现。
 *
 * ## 现状（诚实说明）
 *
 * 探针（§10-4）确认了**数据结构**：实时快照在宿主
 * `com.guosen.android.quotes.data.StockInfo`（`stockPrice`/`zrsp`/`jrkp`/`highPrice`/
 * `lowPrice`/`stockName`，全 BigDecimal），日 K 在
 * `com.guosen.quotation.logicview.KXChartGroupView.m_kxChartInfo.m_kxChartItems`。
 * 但**「从目标进程的任意位置拿到填好数据的实例」这一步没有确认的入口**：
 * 价格页是独立的 H5 Activity（AIStareWebView），页面上没有行情实例；
 * 实例挂在个股详情页（StockFragAct）与 K 线视图里，且都是私有字段。
 *
 * 所以本通道只做一条**有校验的尽力路径**：缓存最近见到的 `StockInfo` 实例
 * （挂构造器捕获，价格页的入口恰好在个股详情页底栏，主路径下同一只股票），
 * 取数时代码对得上才用，对不上（直接从「我的盯盘」列表进价格页）返回 null 降级 ——
 * **宁可用公开行情，也不给错数据**。
 *
 * 待后续探针确认「实例权威入口」后，把入口方法加进 [INSTANCE_PROVIDERS] 即可，
 * 其余代码不用动。
 */
class HostQuoteSource : QuoteSource {

    override val name = "host"

    /**
     * **只能给实时快照**：日 K 入口未确认（§10-4）。
     *
     * 这条声明是 2026-10-17 取数稳定性修复的一部分：旧实现把它当「全能通道」
     * 锁定，导致默认方案（30 日最高/最低）在主路径上必然失败。
     */
    override val supportsKline = false

    /** 最近见到的 StockInfo 实例（弱引用，防泄漏）；挂构造器填充 */
    @Volatile
    private var lastStockInfo: WeakReference<Any>? = null

    /**
     * 实例权威入口的候选（占位）：探针确认后往这里加
     * 静态取数入口（如某个行情中心的单例方法）。
     */
    private val INSTANCE_PROVIDERS: List<(String, String) -> Any?> = emptyList()

    /**
     * 由 Feature 挂在 StockInfo 构造器上的钩子回调（记录最近实例）。
     *
     * 第一行先比「还是不是上次那个对象」：宿主行情刷新时构造很频繁，
     * 而这是**宿主自己的构造路径**，能省一次 `WeakReference` 分配就省（2026-10-19）。
     */
    fun noteInstance(obj: Any?) {
        if (obj == null) return
        if (lastStockInfo?.get() === obj) return
        lastStockInfo = WeakReference(obj)
    }

    override fun snapshot(code: String, market: String): QuoteSnapshot? {
        val info = lastStockInfo?.get()
        // 权威入口候选先走（当前为空列表 —— 探针确认后补）
        for (provider in INSTANCE_PROVIDERS) {
            val obj = runCatching { provider(code, market) }.getOrNull()
            if (obj != null) return readSnapshot(obj, code, market)
        }
        if (info == null) {
            GLog.d("一键配置取数：宿主通道无缓存的 StockInfo 实例")
            return null
        }
        return readSnapshot(info, code, market)
    }

    private fun readSnapshot(info: Any, code: String, market: String): QuoteSnapshot? {
        // 代码校验：缓存里的必须是当前这一只，否则宁降级也不给错数据
        val cam = Reflect.readField(info, *HostFields.HOST_FIELD_CODE_MARKET)
            as? String
        val expected = "$code.$market"
        if (cam == null) {
            // 代码字段读不到 = 没法证明缓存是当前这一只：宿主改版字段名时
            // 静默放行会把上一只股票的价格当成当前页（审查 2026-10-17），同「对不上」处理
            GLog.d("一键配置取数：宿主 StockInfo 代码字段读不到，无法校验归属，降级")
            return null
        }
        if (cam != expected) {
            GLog.d("一键配置取数：宿主缓存的股票（$cam）不是当前页（$expected），降级")
            return null
        }
        val latest = readDecimal(info, HostFields.HOST_FIELD_LATEST)
        val prevClose = readDecimal(info, HostFields.HOST_FIELD_PREV_CLOSE)
        if (latest == null || prevClose == null) {
            GLog.d("一键配置取数：宿主 StockInfo 关键字段缺失（未填充行情？）")
            return null
        }
        return QuoteSnapshot(
            latest = latest,
            prevClose = prevClose,
            open = readDecimal(info, HostFields.HOST_FIELD_OPEN),
            dayHigh = readDecimal(info, HostFields.HOST_FIELD_DAY_HIGH),
            dayLow = readDecimal(info, HostFields.HOST_FIELD_DAY_LOW),
            name = Reflect.readField(info, *HostFields.HOST_FIELD_NAME) as? String,
        )
    }

    override fun dailyKline(code: String, market: String, n: Int): List<QuoteBar>? {
        // 日 K 走 KXChartInfo 需要挂 K 线视图的数据流，入口未确认（§10-4）——
        // 首版不实现，让日 K 请求自然降级到公开行情。
        return null
    }

    private fun readDecimal(obj: Any, candidates: Array<String>): BigDecimal? {
        val v = Reflect.readField(obj, *candidates) ?: return null
        return when (v) {
            is BigDecimal -> v
            is Number -> BigDecimal(v.toString())
            is String -> v.toBigDecimalOrNull()
            else -> null
        }
    }

    companion object {
        const val HOST_STOCK_INFO = "com.guosen.android.quotes.data.StockInfo"
    }
}
