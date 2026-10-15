package io.github.SansKritO4.GSAuxiliary.feature.market

import io.github.SansKritO4.GSAuxiliary.core.TargetApp

/**
 * 「行情市场精简」三个功能共用的定位常量。
 *
 * ## 行情市场页的纵向结构（自上而下）
 *
 * ```
 * ① 市场概览图      ← 指数 / 涨跌分布
 * ② 滚动横幅        ← 一条跑马灯快讯（本功能 1 要去掉）
 * ③ 快捷入口        ← 一排「图标 + 文字」格子（本功能 1 要去掉）
 * ④ 板块            ← 六个板块（本功能 2 压成三个）
 * ⑤ 市场概览        ← 带柱状图的统计区（本功能 3 整体压矮）
 * ```
 *
 * ## 页面门禁
 *
 * 行情市场是主界面 `MainAct` 里的一个 Tab（Fragment），
 * 与首页 / 底栏同一个 Activity。所以三个功能的 `pages` 都是 [PAGES]，
 * 真正的区分靠**视图是否可见**（[UiClean.visibleOnScreen]）——
 * `ViewPager` 里没显示的页签，`isShown` 是 false，不会被误伤。
 *
 * `MarketOverviewAct` 是清单里另一个「市场概览」页面，一并纳入候选，
 * 万一某些版本把行情市场做成了独立页也能覆盖。
 *
 * ## 三个功能的锚点各自独立
 *
 * 每个功能都有**自己的**候选 id 与结构判据，互不依赖 ——
 * 这样某一个认不出来不会连累另外两个。
 */
object MarketTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /** 承载行情市场的页面（候选；都来自 `AndroidManifest` 明文解析） */
    val PAGES = arrayOf(
        "com.guosen.app.goldsun.ui.MainAct",
        "com.guosen.quotation.ui.MarketOverviewAct",
    )

    // ------------------------------------------------------------ 功能 1：滚动横幅

    /** 滚动横幅（跑马灯）的条状容器（候选） */
    val MARQUEE_BAR_ID_CANDIDATES = arrayOf(
        "ll_marquee", "ll_notice", "ll_scroll_notice", "ll_hq_notice",
        "ll_market_notice", "ll_top_notice", "ll_roll_notice", "marquee_view",
        "rl_notice", "ll_news_notice", "ll_hq_marquee",
    )

    /**
     * 跑马灯文字的最小长度。
     *
     * 用 `ellipsize = MARQUEE` 当判据时，页面上会有不少「单行超长文案」也被
     * 设成跑马灯（比如板块名、股票名）。要求文案够长（默认 8 个字）能滤掉大部分 ——
     * 快讯标题一定比标签长。
     */
    const val MIN_MARQUEE_TEXT = 8

    /** 「条状容器」的高度上限（dp）：从跑马灯文字往上找，第一个不超过这个高度的祖先 */
    const val MARQUEE_BAR_MAX_HEIGHT_DP = 60

    // ------------------------------------------------------------ 快捷入口

    /** 快捷入口网格（候选） */
    val QUICK_ENTRY_ID_CANDIDATES = arrayOf(
        "gv_market_menu", "grid_market_menu", "ll_market_menu", "rv_market_menu",
        "ll_hq_menu", "gv_menu", "ll_quick_entry", "ll_market_quick",
        "grid_menu", "ll_hq_quick",
    )

    /** 至少这么多格才算「快捷入口」 */
    const val MIN_ENTRY_ITEMS = 4

    /** 快捷入口整块的高度上限（dp） */
    const val MAX_ENTRY_GRID_HEIGHT_DP = 240

    // ------------------------------------------------------------ 功能 2：板块

    /** 板块分组容器（候选） */
    val SECTOR_GROUP_ID_CANDIDATES = arrayOf(
        "ll_bk_list", "ll_sector_list", "ll_bk", "bk_list", "ll_hq_bk",
        "ll_market_bk", "gv_bk", "rv_bk", "rv_sector", "grid_bk",
    )

    /** 板块原本显示几项 */
    const val SECTOR_TOTAL = 6

    /** 压缩后保留几项 */
    const val SECTOR_KEEP = 3

    /** 板块项的宽度一致性容差（判定「六项平铺」用） */
    const val SECTOR_WIDTH_TOLERANCE = 1.3f

    // ------------------------------------------------------------ 功能 3：市场概览

    /** 市场概览分组容器（候选） */
    val OVERVIEW_GROUP_ID_CANDIDATES = arrayOf(
        "ll_market_overview", "ll_hq_overview", "ll_overview", "rl_overview",
        "ll_market_summary", "ll_index_overview", "ll_market_index",
        "ll_hq_summary", "fl_overview",
    )

    /**
     * 市场概览整体高度压缩到原来的多少。
     *
     * 0.6 是「明显变矮、但柱状图还看得清」的折中值。
     * 想调手感就改这一个数，不用动逻辑。
     */
    const val OVERVIEW_HEIGHT_FACTOR = 0.6f

    /** 柱状图视图的类名特征（小写匹配） */
    val CHART_CLASS_KEYWORDS = arrayOf(
        "chart", "bar", "histogram", "kline", "graph", "column", "trend",
    )

    /** 自绘图表视图的最小高度（dp）——比这矮的多半是分隔线 */
    const val CHART_MIN_HEIGHT_DP = 60
}
