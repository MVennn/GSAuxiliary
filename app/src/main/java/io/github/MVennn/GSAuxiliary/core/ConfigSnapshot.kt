package io.github.MVennn.GSAuxiliary.core

/**
 * 配置的不可变快照。
 *
 * 功能模块只读快照，不直接碰 `SharedPreferences`：
 *  - 读取成本固定，不会在 `getView` 热路径里反复查表
 *  - 配置变化时整体替换，天然线程安全
 *  - 新增配置项只需改这里 + [ConfigKeys] + 设置界面
 */
data class ConfigSnapshot(
    val enabled: Boolean,
    val color: Int,
    val scopeStrategy: Boolean,
    val logLevel: Int,
    /** 策略详情页点股票直达个股详情（跳过中间页） */
    val stgDirectQuote: Boolean,
    /** 顺手把策略整张股票列表送进去，支持左右滑动换股 */
    val stgQuoteSwipe: Boolean,
    /** 策略详情页多选栏注入「全选」按钮（一键勾选全部股票） */
    val stgSelectAll: Boolean,
    /** 策略详情页三点菜单里注入「筛选股票」（涨幅 / 跌幅 / 自选 三个开关） */
    val stgStockFilter: Boolean,
    /** 个股详情页底栏在「自选」与「更多」之间注入「智能盯盘」快捷入口 */
    val stockBarEntry: Boolean,
    /** 主界面底栏不再显示「理财」那一格 */
    val dockClean: Boolean,
    /** 隐藏首页快捷入口下方的横幅 */
    val homeBannerHide: Boolean,
    /** 隐藏行情市场页的滚动横幅与它下方的快捷入口 */
    val marketBannerHide: Boolean,
    /** 行情市场板块由六项压缩为三项 */
    val marketSectorCompact: Boolean,
    /** 行情市场「市场概览」整体压矮，柱状图一并压缩 */
    val marketOverviewCompact: Boolean,
    /** 手势导航条（小白条）区域填充与紧邻界面一致的颜色 */
    val navBarBlend: Boolean,
    /** 登录后不再弹出「今日新股申购提醒」弹窗 */
    val ipoDialogClean: Boolean,
    /** 智能盯盘价格页「一键配置」按钮的总开关 */
    val aistareQuickEnabled: Boolean,
    /** 「一键配置」的方案数据（JSON 字符串，结构见 QuickConfigStore） */
    val aiStareQuickConfig: String,
) {
    /**
     * 稳定指纹，用于「配置真的变了没」的判断。
     * 只在内容变化时改变，与对象身份无关。
     */
    val fingerprint: Int
        get() = ((if (enabled) 1 else 0) * 31
                + color * 7
                + (if (scopeStrategy) 1 else 0) * 131
                + logLevel * 17
                + (if (stgDirectQuote) 1 else 0) * 977
                + (if (stgQuoteSwipe) 1 else 0) * 1231
                + (if (stgSelectAll) 1 else 0) * 1543
                + (if (stgStockFilter) 1 else 0) * 1789
                + (if (stockBarEntry) 1 else 0) * 1877
                + (if (dockClean) 1 else 0) * 2011
                + (if (homeBannerHide) 1 else 0) * 2137
                + (if (marketBannerHide) 1 else 0) * 2221
                + (if (marketSectorCompact) 1 else 0) * 2347
                + (if (marketOverviewCompact) 1 else 0) * 2467
                + (if (navBarBlend) 1 else 0) * 2671
                + (if (ipoDialogClean) 1 else 0) * 2741
                + (if (aistareQuickEnabled) 1 else 0) * 2851
                + aiStareQuickConfig.hashCode() * 2879)

    companion object {
        val DEFAULT = ConfigSnapshot(
            enabled = ConfigKeys.DEFAULT_ENABLED,
            color = ConfigKeys.DEFAULT_COLOR,
            scopeStrategy = ConfigKeys.DEFAULT_SCOPE_STRATEGY,
            logLevel = ConfigKeys.DEFAULT_LOG_LEVEL,
            stgDirectQuote = ConfigKeys.DEFAULT_STG_DIRECT_QUOTE,
            stgQuoteSwipe = ConfigKeys.DEFAULT_STG_QUOTE_SWIPE,
            stgSelectAll = ConfigKeys.DEFAULT_STG_SELECT_ALL,
            stgStockFilter = ConfigKeys.DEFAULT_STG_STOCK_FILTER,
            stockBarEntry = ConfigKeys.DEFAULT_STOCK_BAR_ENTRY,
            dockClean = ConfigKeys.DEFAULT_DOCK_CLEAN,
            homeBannerHide = ConfigKeys.DEFAULT_HOME_BANNER_HIDE,
            marketBannerHide = ConfigKeys.DEFAULT_MARKET_BANNER_HIDE,
            marketSectorCompact = ConfigKeys.DEFAULT_MARKET_SECTOR_COMPACT,
            marketOverviewCompact = ConfigKeys.DEFAULT_MARKET_OVERVIEW_COMPACT,
            navBarBlend = ConfigKeys.DEFAULT_NAV_BAR_BLEND,
            ipoDialogClean = ConfigKeys.DEFAULT_IPO_DIALOG_CLEAN,
            aistareQuickEnabled = ConfigKeys.DEFAULT_AISTARE_QUICK_ENABLED,
            aiStareQuickConfig = ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG,
        )
    }
}
