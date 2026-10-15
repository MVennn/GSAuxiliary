package io.github.SansKritO4.GSAuxiliary.core

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
                + (if (stockBarEntry) 1 else 0) * 1877)

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
        )
    }
}
