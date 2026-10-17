package io.github.MVennn.GSAuxiliary.feature.aistare

/**
 * 「智能盯盘 → 价格页一键配置」的全部目标常量。
 *
 * **每一项都是真机实测**（2026-10-16，魅族 20 / Android 16 / 金太阳 8.6.0 vc271，
 * 依据 LSPosed 探针 `tools/probe` v33~v35 的运行时日志 + `uiautomator dump`，
 * 结论原文见 `docs/FEATURE-AISTARE-QUICK-CONFIG.md` §1 / §10）—— 不是推断。
 * App 升级后失效时：重跑探针，改这里的候选数组即可。
 */
object AiStareQuickConfigTargets {

    // ------------------------------------------------------------ 页面判定

    /**
     * 价格页 URL 的判定片段 —— **必须带页签号**。
     *
     * 实测（2026-10-17）：盯盘策略下的各个页签是**不同的路径**，不是同一页里切 tab：
     * `/earlywarning/setStrategy/1`（价格）、`/2`（涨跌幅）、`/12`（换手率）…
     * 早先只匹配 `/earlywarning/setStrategy` 前缀，于是「配置并保存」被注入到了
     * 涨跌幅 / 换手率这些还没适配取数的页面上。
     */
    const val PAGE_URL_PATH = "/earlywarning/setStrategy/1"

    /**
     * 是不是价格页。
     *
     * 光 `contains` 不够：`/setStrategy/12` 也包含 `/setStrategy/1`，所以页签号后面
     * 必须是 URL 结束 / `?` / `#` / `/` 之一。
     */
    fun isPricePage(url: String): Boolean {
        val at = url.indexOf(PAGE_URL_PATH)
        if (at < 0) return false
        val next = url.getOrNull(at + PAGE_URL_PATH.length)
        return next == null || next == '?' || next == '#' || next == '/'
    }

    /**
     * 价格页 Activity（它自己就是 H5 容器，H5 在其 `mWbContent` 字段上）。
     * 只用于日志与「管理方案…」回拉时的判定，注入不依赖它。
     */
    const val PAGE_ACTIVITY = "com.guosen.quotation.aistare.ui.AIStareWebView"

    // ------------------------------------------------------------ WebView 挂载点

    /**
     * 挂 `onPageFinished` 的 **WebViewClient 具体子类**（§10-2 实测）：
     * 宿主在 `HybirdWebView#init:337` 调 `setWebViewClient`，挂的是
     * `HybirdWebView$4`（extends 听云插桩基类 NBSWebViewClient），覆写了
     * `onPageFinished`。**钩基类拦不到子类覆写，必须钩这个子类本身。**
     *
     * 匿名内部类编号在宿主改版后可能位移 —— 所以做成前缀匹配 + 主挂点走
     * `setWebViewClient` 动态抓子类（不依赖编号），这里只做兜底候选。
     */
    const val WEBVIEW_CLIENT_PREFIX = "com.guosen.commonui.webview.HybirdWebView$"
    const val WEBVIEW_CLIENT_FALLBACK = "com.guosen.commonui.webview.HybirdWebView$4"

    /** 宿主命令桥（仅日志对照用；我们自建 `gsaux` 桥，不往宿主漏斗里发命令） */
    const val HYBRID_WEBVIEW = "com.guosen.commonui.webview.HybirdWebView"

    // ------------------------------------------------------------ 自建 JS 桥

    /**
     * 自挂的 JS 桥名。实测宿主**不禁用** `addJavascriptInterface`
     * （只 add 分析 SDK 的两个桥、只 remove `searchBoxJavaBridge_`，见 §10-3），
     * 自挂 `gsaux` 与宿主零冲突、不会被清理。宿主的命令走 `guosen://` scheme +
     * `onJsPrompt` 双通道，与我们无关 —— 我们不进宿主漏斗，`showCommandError`
     * 那条红线天然满足。
     */
    const val JS_BRIDGE_NAME = "gsaux"

    /** 注入 JS 的全局命名空间（`window.__gsaux`） */
    const val JS_NAMESPACE = "__gsaux"

    // ------------------------------------------------------------ 注入样式

    /**
     * 「配置」按钮底色 —— 个股详情页「买」按钮的底色（真机逐点采样，
     * 纯色无渐变；「卖」是 `#2BAF95`）。文字白色。
     */
    const val BUTTON_COLOR = "#FF3A3B"

    /**
     * 底部操作条三个子节点的 flex 基准。注入前「重置」「保存」各 540px，
     * 插第三个后必须三个都改 `flex:1 1 0`（各 360px），否则新按钮被挤出去
     * 或叠在「保存」上（方案文档 §3.1）。
     */
    const val FLEX_RULE = "flex:1 1 0"

    /**
     * 注入按钮上的文字。
     *
     * 2026-10-17 用户要求「点击以后会配置信息，配置完成以后再执行一次保存」，
     * 于是文案直接说清两件事：**配置并保存**（三个格子各 128 CSS px，五个字放得下）。
     */
    const val BUTTON_TEXT = "配置并保存"

    // ------------------------------------------------------------ 「保存」按钮的定位判据

    /**
     * 找「保存」用**文本 + 结构双判据**（§8 风险表）：文本是「保存」
     * 且其可点击容器在页面最底部 1/4 区域内。双判据都过才注入，
     * 找不到只记 warn 不注入（H5 改版时不打扰用户）。
     */
    const val SAVE_BUTTON_TEXT = "保存"
    const val RESET_BUTTON_TEXT = "重置"

    // ------------------------------------------------------------ 取数

    /**
     * 公开行情（兜底通道，§5.2 写死便于复核）：
     * 实时 —— 腾讯 `qt.gtimg.cn`，`~` 分隔文本，字段 3=最新 4=昨收 5=今开 33=最高 34=最低 1=名称；
     * 日 K —— 腾讯 `web.ifzq.gtimg.cn`，不复权口径（§5.3），`day` 数组 `[日期,开,收,高,低,量]`。
     * `market` 换算：1 → sz，2 → sh。
     */
    const val QT_SNAPSHOT_URL = "https://qt.gtimg.cn/q="
    const val QT_KLINE_URL =
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=%s,day,,,320,"

    fun marketToPrefix(market: String): String? = when (market) {
        "1" -> "sz"
        "2" -> "sh"
        else -> null
    }

    // ------------------------------------------------------------ 宿主行情（反射候选，§10-4）

    /**
     * 宿主实时行情载体 `StockInfo` 的字段候选（实测确认，BigDecimal 类型）：
     * 最新价 / 昨收 / 今开 / 当日高 / 当日低 / 名称。
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
     */
    const val HOST_KX_GROUP_VIEW = "com.guosen.quotation.logicview.KXChartGroupView"
    const val HOST_KX_INFO_FIELD = "m_kxChartInfo"
    const val HOST_KX_ITEMS_FIELD = "m_kxChartItems"
    const val HOST_KX_ITEM = "com.guosen.android.quotes.data.kx.KXChartItem"
}
