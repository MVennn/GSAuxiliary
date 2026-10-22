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
    /** 自选股名称加粗（与「自选股高亮」是两个独立开关） */
    val watchlistBold: Boolean,
    val scopeStrategy: Boolean,
    /** 日志等级，见 [LogLevel]；模块 App 与目标进程共用同一个值 */
    val logLevel: Int,
    /** 策略详情页点股票直达个股详情（跳过中间页） */
    val stgDirectQuote: Boolean,
    /** 顺手把策略整张股票列表送进去，支持左右滑动换股 */
    val stgQuoteSwipe: Boolean,
    /** 策略详情页多选栏注入「全选」按钮（一键勾选全部股票） */
    val stgSelectAll: Boolean,
    /** 策略详情页多选态底部注入「截图并添加」（逐只截图后走宿主分组页） */
    val stgSnapAdd: Boolean,
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
    /** 行情市场顶部的「指数概览」整块收起 */
    val marketIndexHide: Boolean,
    /** 行情市场「市场概览」整体压矮，柱状图一并压缩 */
    val marketOverviewCompact: Boolean,
    /** 行情市场「沪深京A」筛选面板增加「涨跌与自选」三行，并按它们筛排行榜 */
    val marketStockFilter: Boolean,
    /** 手势导航条（小白条）区域填充与紧邻界面一致的颜色 */
    val navBarBlend: Boolean,
    /** 登录后不再弹出「今日新股申购提醒」弹窗 */
    val ipoDialogClean: Boolean,
    /** 智能盯盘价格页「一键配置」按钮的总开关 */
    val aistareQuickEnabled: Boolean,
    /** 「一键配置」的方案数据（JSON 字符串，结构见 QuickConfigStore） */
    val aiStareQuickConfig: String,
    /** 保存成功不弹确认弹窗（轻提示 + 自动返回） */
    val aiStareSkipSaveConfirm: Boolean,
    /** 价格页两个价都空时「保存」仍可点（= 取消该股盯盘） */
    val aiStareEmptySave: Boolean,
    /** 「我的盯盘」卡片点一下直达个股详情页 */
    val aiStareCardDetail: Boolean,
    /** 保存成功后退到个股详情页（false = 只回上一页） */
    val aiStareSaveBackToStock: Boolean,
    /** 多方案时弹「选择方案」层（false = 直接套默认方案） */
    val aiStarePickScheme: Boolean,
) {
    /**
     * 稳定指纹，用于「配置真的变了没」的判断。
     * 只在内容变化时改变，与对象身份无关。
     *
     * 用属性初始化器在构造期算好并缓存 —— 快照本身不可变，指纹永远不变。
     * `getView` 每行都会读一次它，绝不能做成计算型 getter
     * （[aiStareQuickConfig] 的 JSON 可达几十 KB，`String.hashCode` 是 O(n) 全扫）。
     *
     * ## 为什么是位掩码而不是加权和
     *
     * 原先是「每项乘一个互不相同的质数再相加」。乘数互不相同**防不住碰撞**：
     * 加法里两组不同的布尔组合可以凑成同一个和，例如
     * `2137 + 2221 == 2011 + 2347`（`{首页横幅, 行情横幅}` 与 `{底栏精简, 板块压缩}`），
     * `31 + 131 + 2741 == 2903`（`{总开关, 策略页, 新股弹窗}` 与 `{多方案选择}`）。
     * 撞上时 `ConfigSource` 的指纹比对会**判定「没变」**，这次配置变更就被静默丢弃
     * —— 用户改了开关但功能不变，而且**日志里一行都没有**。
     *
     * 单键切换永远是安全的（delta = ±该项的位），风险只在**多键同时写入**
     * （「重置模块」一次写 23 个键、恢复备份一次写 29 个键）。
     *
     * 改成「布尔项占低位、逐个异或」之后：**24 个布尔项之间不再有任何碰撞**
     * （不同布尔集合 → 不同位掩码 → 必然不同），数值项（颜色 / 日志等级 / 方案 JSON）
     * 再各自混一层异或。
     *
     * ⚠️ **位是有限的**：Int 有 32 位，布尔项占了位 0..23，**还剩 8 位**。
     * 再加布尔项时 `bit shl 1` 会在第 32 位溢出成 0 → **两个开关静默冲突**
     * （症状：改了其中一个，另一个也跟着变）。所以新增开关时，
     * 这里的个数说明与 `ConfigSnapshotFingerprintTest` 的清单**都要同步更新**。
     *
     * 碰撞概率：布尔集合之间是 0；布尔集合与数值项之间约 1/2²⁴
     * （数值项的异或值会落进低位，但已远低于原加权求和的「几千分之一」）。
     */
    val fingerprint: Int = run {
        var h = 0
        var bit = 1
        fun mix(b: Boolean) {
            if (b) h = h xor bit
            bit = bit shl 1
        }
        mix(enabled)
        mix(watchlistBold)
        mix(scopeStrategy)
        mix(stgDirectQuote)
        mix(stgQuoteSwipe)
        mix(stgSelectAll)
        mix(stgSnapAdd)
        mix(stgStockFilter)
        mix(stockBarEntry)
        mix(dockClean)
        mix(homeBannerHide)
        mix(marketBannerHide)
        mix(marketSectorCompact)
        mix(marketIndexHide)
        mix(marketOverviewCompact)
        mix(marketStockFilter)
        mix(navBarBlend)
        mix(ipoDialogClean)
        mix(aistareQuickEnabled)
        mix(aiStareSkipSaveConfirm)
        mix(aiStareEmptySave)
        mix(aiStareCardDetail)
        mix(aiStareSaveBackToStock)
        mix(aiStarePickScheme)
        // 数值项各自混一层：黄金比例常数（Fibonacci hashing 的标准做法）
        h = h xor (color * 0x9E3779B1.toInt())
        h = h xor (logLevel * 0x85EBCA6B.toInt())
        h = h xor aiStareQuickConfig.hashCode()
        h
    }

    companion object {
        val DEFAULT = ConfigSnapshot(
            enabled = ConfigKeys.DEFAULT_ENABLED,
            color = ConfigKeys.DEFAULT_COLOR,
            watchlistBold = ConfigKeys.DEFAULT_WATCHLIST_BOLD,
            scopeStrategy = ConfigKeys.DEFAULT_SCOPE_STRATEGY,
            logLevel = ConfigKeys.DEFAULT_LOG_LEVEL,
            stgDirectQuote = ConfigKeys.DEFAULT_STG_DIRECT_QUOTE,
            stgQuoteSwipe = ConfigKeys.DEFAULT_STG_QUOTE_SWIPE,
            stgSelectAll = ConfigKeys.DEFAULT_STG_SELECT_ALL,
            stgSnapAdd = ConfigKeys.DEFAULT_STG_SNAP_ADD,
            stgStockFilter = ConfigKeys.DEFAULT_STG_STOCK_FILTER,
            stockBarEntry = ConfigKeys.DEFAULT_STOCK_BAR_ENTRY,
            dockClean = ConfigKeys.DEFAULT_DOCK_CLEAN,
            homeBannerHide = ConfigKeys.DEFAULT_HOME_BANNER_HIDE,
            marketBannerHide = ConfigKeys.DEFAULT_MARKET_BANNER_HIDE,
            marketSectorCompact = ConfigKeys.DEFAULT_MARKET_SECTOR_COMPACT,
            marketIndexHide = ConfigKeys.DEFAULT_MARKET_INDEX_HIDE,
            marketOverviewCompact = ConfigKeys.DEFAULT_MARKET_OVERVIEW_COMPACT,
            marketStockFilter = ConfigKeys.DEFAULT_MARKET_STOCK_FILTER,
            navBarBlend = ConfigKeys.DEFAULT_NAV_BAR_BLEND,
            ipoDialogClean = ConfigKeys.DEFAULT_IPO_DIALOG_CLEAN,
            aistareQuickEnabled = ConfigKeys.DEFAULT_AISTARE_QUICK_ENABLED,
            aiStareQuickConfig = ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG,
            aiStareSkipSaveConfirm = ConfigKeys.DEFAULT_AISTARE_SKIP_SAVE_CONFIRM,
            aiStareEmptySave = ConfigKeys.DEFAULT_AISTARE_EMPTY_SAVE,
            aiStareCardDetail = ConfigKeys.DEFAULT_AISTARE_CARD_DETAIL,
            aiStareSaveBackToStock = ConfigKeys.DEFAULT_AISTARE_SAVE_BACK_TO_STOCK,
            aiStarePickScheme = ConfigKeys.DEFAULT_AISTARE_PICK_SCHEME,
        )
    }
}
