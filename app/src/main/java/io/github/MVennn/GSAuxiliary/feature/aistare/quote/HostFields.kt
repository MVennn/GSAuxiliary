package io.github.MVennn.GSAuxiliary.feature.aistare.quote

/**
 * 宿主行情载体的反射候选（字段名 / 类名，§10-4）。
 *
 * **为何放这里**：这些候选只有本包的 [HostQuoteSource] 会用 —— 一键配置的
 * 页面判定 / 按钮注入 / 「保存」定位都不碰它们。原先挂在父包
 * `AiStareQuickConfigTargets` 上，导致 quote 包向上 import 父包
 * （子功能反向依赖父功能，方向不佳），2026-10-17 架构整理时**随注释整体挪入**
 * 本包（父包那份已删，非副本）。App 升级后失效时：重跑探针，改这里的候选即可。
 *
 * **每一项都是真机实测**（2026-10-16，魅族 20 / Android 16 / 金太阳 8.6.0 vc271，
 * 依据 LSPosed 探针 `tools/probe` v33~v35 的运行时日志 + `uiautomator dump`，
 * 结论原文见 `docs/FEATURE-AISTARE-QUICK-CONFIG.md` §10-4）—— 不是推断。
 */
object HostFields {

    /**
     * 宿主实时行情载体 `StockInfo` 的字段候选（实测确认，BigDecimal 类型）：
     * 最新价 / 昨收 / 今开 / 当日高 / 当日低 / 名称 / 代码+市场。
     */
    val HOST_FIELD_LATEST = arrayOf("stockPrice", "zxj", "m_stockPrice")
    val HOST_FIELD_PREV_CLOSE = arrayOf("zrsp", "m_zrsp")
    val HOST_FIELD_OPEN = arrayOf("jrkp", "m_jrkp")
    val HOST_FIELD_DAY_HIGH = arrayOf("highPrice", "m_highPrice")
    val HOST_FIELD_DAY_LOW = arrayOf("lowPrice", "m_lowPrice")
    val HOST_FIELD_NAME = arrayOf("stockName", "m_stkName")
    val HOST_FIELD_CODE_MARKET = arrayOf("codeAndMarket", "m_codeAndMarket")

    /**
     * 日 K 载体链（实测）：`KXChartGroupView` → `m_kxChartInfo`
     * （`com.guosen.android.quotes.data.kx.KXChartInfo`）→ `m_kxChartItems`
     * （List，元素 `KXChartItem` 的 `m_date/m_openPrice/m_closePrice/m_highPrice/m_lowPrice`）。
     *
     * 当前日 K 未走宿主通道（[HostQuoteSource.supportsKline] = false，实例入口
     * 未确认 §10-4），候选按实测原样保留，确认入口后即可接上。
     */
    const val HOST_KX_GROUP_VIEW = "com.guosen.quotation.logicview.KXChartGroupView"
    const val HOST_KX_INFO_FIELD = "m_kxChartInfo"
    const val HOST_KX_ITEMS_FIELD = "m_kxChartItems"
    const val HOST_KX_ITEM = "com.guosen.android.quotes.data.kx.KXChartItem"
}
