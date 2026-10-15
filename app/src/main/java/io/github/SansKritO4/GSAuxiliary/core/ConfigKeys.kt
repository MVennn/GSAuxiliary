package io.github.SansKritO4.GSAuxiliary.core

/**
 * 配置键与默认值。
 *
 * **刻意不引用任何 Xposed / Android 框架类**：目标进程侧与模块 App 侧都引用它，
 * 保证模块 App 不会因为加载它而意外触发 Xposed 类的加载。
 *
 * 配置分两类：
 *  - **功能开关**（[ENABLED] / [COLOR] / [SCOPE_STRATEGY]）：目标进程要读，
 *    必须同步进 [ConfigSnapshot]，并通过 `ConfigSource.read()` 读取。
 *  - **模块设置**（[LOG_LEVEL] / [DESKTOP_ICON] / [LIQUID_GLASS] / [GLASS_EFFECT] /
 *    [LANGUAGE] / [THEME] / [PREDICTIVE_BACK]）：只有模块 App 自己的进程消费，
 *    **不进 ConfigSnapshot** —— 目标进程不需要知道底栏长什么样、桌面图标显不显示。
 *    （[LOG_LEVEL] 是例外：它同时被目标进程用于控制日志等级，已在 snapshot 中。）
 */
object ConfigKeys {

    /**
     * remote preferences 的分组名。
     *
     * 现代 API 的 `getRemotePreferences(group)` 与模块 App 侧的
     * `XposedService.getRemotePreferences(group)` 用同一个名字，
     * 框架负责在两个进程之间同步（含变更推送）。
     */
    const val GROUP = "config"

    // ---------------------------------------------------------------- 功能开关

    /** 总开关：自选股高亮 */
    const val ENABLED = "enabled"

    /** 高亮色（ARGB int） */
    const val COLOR = "color"

    /**
     * 是否已阅读并同意免责声明。
     *
     * 只用于模块自己的界面（首次启动的同意弹窗）。
     *
     * **三个「不进」**（都别改，改任何一个都会重现 bug）：
     *  - 不进 [ConfigSnapshot]：目标进程不关心这件事；
     *  - 不进 [ALL_KEYS] / [DEFAULTS]：它不是模块配置，不该被备份 / 恢复 / 重置带着走；
     *  - **不走 remote preferences**：必须用
     *    [io.github.SansKritO4.GSAuxiliary.app.ModulePreferences.getLocalBoolean] /
     *    [io.github.SansKritO4.GSAuxiliary.app.ModulePreferences.setLocalBoolean] 读写本机文件。
     *    走 remote 会因后端异步切换导致「启动先读到 false、随后翻成 true」，
     *    弹窗闪一下就没；而且「清除应用数据」也清不掉它。
     *
     * 它**不在** [FEATURE_KEYS] / [FEATURE_PARAM_KEYS] 里，所以「重置模块」
     * 不会把同意状态清掉（重置完还要再点一次同意太折腾）；
     * 卸载 / 重装 / 清除应用数据会清掉它，于是重新弹窗 —— 这正是期望行为。
     */
    const val DISCLAIMER_ACCEPTED = "disclaimer_accepted"

    /** 作用域开关：是否作用于「我的策略 → 策略详情」 */
    const val SCOPE_STRATEGY = "scope_strategy"

    /**
     * 策略详情页「直达标准行情」：在策略详情点股票，直接进个股详情页，
     * 不再经过中间页 `AIStkDetailFragAct`。
     */
    const val STG_DIRECT_QUOTE = "stg_direct_quote"

    /**
     * 「直达」时把整张策略股票列表一起送进个股详情页，从而支持左右滑动切换。
     * 关掉则退化为原版行为（只进单只、不可滑动）。
     */
    const val STG_QUOTE_SWIPE = "stg_quote_swipe"

    /**
     * 策略详情页「多选栏全选」：在「勾选加入自选股 → 取消」的多选栏里，
     * 于「取消」左侧注入一个「全选」按钮，一键勾选该页全部股票。
     *
     * 运行时视图注入实现，不改目标 App 源码；见
     * [io.github.SansKritO4.GSAuxiliary.feature.strategyselect.StgSelectAllFeature]。
     */
    const val STG_SELECT_ALL = "stg_select_all"

    /**
     * 策略详情页「筛选股票」：在右上角三点菜单（「修改名称」/「删除策略」）里
     * 注入一个「筛选股票」项，点开一个**复用目标 App 自带筛选面板 UI** 的筛选页，
     * 提供「涨幅 / 跌幅 / 自选」三个开关，筛掉不匹配的股票行。
     *
     * 见 [io.github.SansKritO4.GSAuxiliary.feature.stgfilter.StgStockFilterFeature]。
     *
     * ⚠️ 三个开关的**取值本身不在这里** —— 它们由目标进程写入目标 App 自己的
     * `SharedPreferences`（`gsaux_stg_filter`）。原因：remote preferences 在目标进程里
     * 是**只读**的，筛选状态必须在目标进程里落盘，所以只能存在目标 App 侧。
     * 这里只留「功能总开关」。
     */
    const val STG_STOCK_FILTER = "stg_stock_filter"

    /**
     * 个股详情页底栏「智能盯盘」快捷入口：在「自选」与「更多」之间注入一个
     * 「智能盯盘」按钮，把原本埋在「更多」弹层里的那一项提到一级位置。
     *
     * 运行时视图注入实现，不改目标 App 源码；见
     * [io.github.SansKritO4.GSAuxiliary.feature.stockbar.StockDetailBarFeature]。
     */
    const val STOCK_BAR_ENTRY = "stock_bar_entry"

    /**
     * 底栏：主界面底栏不再显示「理财」那一格，其余入口重新均分整行。
     *
     * 运行时视图精简实现（只改可见性，不删 Tab、不动索引），见
     * [io.github.SansKritO4.GSAuxiliary.feature.dock.DockCleanFeature]。
     */
    const val DOCK_CLEAN = "dock_clean"

    /**
     * 首页：隐藏首页快捷入口下方的那条横幅。
     *
     * 见 [io.github.SansKritO4.GSAuxiliary.feature.home.HomeBannerFeature]。
     */
    const val HOME_BANNER_HIDE = "home_banner_hide"

    /**
     * 行情市场：隐藏「市场概览图」下方的滚动横幅（跑马灯快讯）与它下方的快捷入口。
     *
     * 见 [io.github.SansKritO4.GSAuxiliary.feature.market.MarketBannerFeature]。
     */
    const val MARKET_BANNER_HIDE = "market_banner_hide"

    /**
     * 行情市场：板块由六项压缩为三项。
     *
     * 见 [io.github.SansKritO4.GSAuxiliary.feature.market.MarketSectorCompactFeature]。
     */
    const val MARKET_SECTOR_COMPACT = "market_sector_compact"

    /**
     * 行情市场：市场概览区整体压矮，柱状图高度一并压缩。
     *
     * 见 [io.github.SansKritO4.GSAuxiliary.feature.market.MarketOverviewCompactFeature]。
     */
    const val MARKET_OVERVIEW_COMPACT = "market_overview_compact"

    // ---------------------------------------------------------------- 模块设置

    /** 日志等级，见 [LogLevel] */
    const val LOG_LEVEL = "log_level"

    /** 桌面图标开关：是否在桌面显示本模块的入口图标 */
    const val DESKTOP_ICON = "desktop_icon"

    /**
     * 液态玻璃总开关：true = 顶栏与悬浮底栏都是液态玻璃，false = 两处都是纯色。
     *
     * 2.7.0 之前这个开关只管底栏形态（叫「Dock 变换」），现在一并管顶栏材质，
     * 所以常量名跟着改了。**键值仍是 `dock_liquid`** —— 改键值会让老用户的
     * 配置失效（读不到就回落默认值），没必要为了好看破坏兼容。
     */
    const val LIQUID_GLASS = "dock_liquid"

    /**
     * 液态玻璃的光效：模糊 / 折射 / 投影 / 内阴影，顶栏与底栏共用同一个值。
     * 键值沿用 `dock_effect`，理由同上。
     */
    const val GLASS_EFFECT = "dock_effect"

    /** 界面语言，取值见 [LANGUAGE_VALUES] */
    const val LANGUAGE = "language"

    /** 深色模式，取值见 [THEME_VALUES] */
    const val THEME = "theme"

    /**
     * 可预测返回（Android 13+ Predictive Back）。
     * 关闭时二级页只用普通返回动画，不跟随手势实时预览。
     */
    const val PREDICTIVE_BACK = "predictive_back"

    // ---------------------------------------------------------------- 语言取值

    /** 跟随系统 */
    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_ZH = "zh"
    const val LANGUAGE_EN = "en"

    /** 语言下拉框的取值顺序（与 `R.array.language_labels` 一一对应） */
    val LANGUAGE_VALUES = arrayOf(LANGUAGE_SYSTEM, LANGUAGE_ZH, LANGUAGE_EN)

    // ---------------------------------------------------------------- 深色模式取值

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    /** 深色模式下拉框的取值顺序（与 `R.array.theme_labels` 一一对应） */
    val THEME_VALUES = arrayOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)

    /**
     * **功能开关一律默认关闭**（2.11.2 起）。
     *
     * 功能菜单里的「功能」是**侵入目标 App 行为**的增强，装完模块不等于用户同意
     * 改动他的行情页 —— 默认全关，由用户自己按需打开，是唯一说得通的默认状态。
     *
     * 与之相对，功能**二级菜单里的参数**（[SCOPE_STRATEGY] / [STG_QUOTE_SWIPE] / [COLOR]）
     * 仍然是「默认开启 / 默认值」：二级菜单只有在总开关打开后才进得去，
     * 此刻它里面的子项当然应该是「已经配好」的状态，不该让用户进去再逐个打开。
     *
     * 注意这**不是**「写回默认值」能不能关掉开关的问题 —— 见
     * [io.github.SansKritO4.GSAuxiliary.app.ModulePreferences.resetAllFeatures] 的注释，
     * 那里仍然显式写 `false`，不依赖本默认值。
     */
    const val DEFAULT_ENABLED = false

    // ---------------------------------------------------------------- 默认值

    /** 与原版行情页黄名完全一致 */
    const val DEFAULT_COLOR = 0xFFFF8000.toInt()

    /**
     * 作用域开关：是否作用于「我的策略 → 策略详情」。
     *
     * 二级菜单参数，默认开启（总开关关了看不出来，开关一开就该是全覆盖）。
     */
    const val DEFAULT_SCOPE_STRATEGY = true

    /**
     * 默认关闭（功能总开关，同 [DEFAULT_ENABLED]）。
     *
     * 它同时是二级菜单里「直达」那一项的键 —— 因为二级入口**只在总开关打开后**
     * 才出现，所以能进到二级菜单时此项必然是 `true`，二级里看到的就是「开启」，
     * 与「参数保持开启」的约定一致，无需额外处理。
     */
    const val DEFAULT_STG_DIRECT_QUOTE = false

    /** 二级菜单参数，默认开启：像行情页一样左右滑动换股 */
    const val DEFAULT_STG_QUOTE_SWIPE = true

    /**
     * 默认关闭（功能总开关，同 [DEFAULT_ENABLED]）。
     *
     * 与 [DEFAULT_STG_DIRECT_QUOTE] 同理：它是「全选」功能的总开关，
     * 出厂默认关，由用户自己按需打开。
     */
    const val DEFAULT_STG_SELECT_ALL = false

    /**
     * 默认关闭（功能总开关，同 [DEFAULT_ENABLED]）。
     *
     * 三个筛选开关不在这里：它们由目标进程写目标 App 自己的 prefs
     * （见 [STG_STOCK_FILTER]），`DEFAULTS` 里也就不该有它们的键。
     */
    const val DEFAULT_STG_STOCK_FILTER = false

    /**
     * 默认关闭（功能总开关，同 [DEFAULT_ENABLED]）。
     *
     * 它是「底栏智能盯盘入口」的总开关，出厂默认关，由用户自己按需打开。
     */
    const val DEFAULT_STOCK_BAR_ENTRY = false

    /**
     * 默认关闭（功能总开关，同 [DEFAULT_ENABLED]）。
     *
     * 以下五项是 2.15.0 新增的「界面精简」系列：底栏去「理财」、首页去横幅、
     * 行情市场去滚动横幅与快捷入口、板块六项压三项、市场概览压矮。
     * 出厂一律默认关，由用户自己按需打开。
     */
    const val DEFAULT_DOCK_CLEAN = false
    const val DEFAULT_HOME_BANNER_HIDE = false
    const val DEFAULT_MARKET_BANNER_HIDE = false
    const val DEFAULT_MARKET_SECTOR_COMPACT = false
    const val DEFAULT_MARKET_OVERVIEW_COMPACT = false

    const val DEFAULT_LOG_LEVEL = LogLevel.INFO
    const val DEFAULT_DESKTOP_ICON = true

    /** 默认开启液态玻璃（低版本系统自动降级为半透明胶囊 / 无模糊） */
    const val DEFAULT_LIQUID_GLASS = true

    /** 默认开启玻璃光效 */
    const val DEFAULT_GLASS_EFFECT = true
    const val DEFAULT_LANGUAGE = LANGUAGE_SYSTEM
    const val DEFAULT_THEME = THEME_SYSTEM

    /** 默认开启可预测返回（Android 13+ 跟手预览；低版本系统会自动退化为普通返回） */
    const val DEFAULT_PREDICTIVE_BACK = true

    /** 默认为「未同意」—— 首次启动必须弹出免责声明同意弹窗 */
    const val DEFAULT_DISCLAIMER_ACCEPTED = false

    // ---------------------------------------------------------------- 数据管理

    /** 会被备份 / 恢复 / 重置覆盖的全部配置键 */
    val ALL_KEYS = arrayOf(
        ENABLED, COLOR, SCOPE_STRATEGY, STG_DIRECT_QUOTE, STG_QUOTE_SWIPE, STG_SELECT_ALL,
        STG_STOCK_FILTER, STOCK_BAR_ENTRY,
        DOCK_CLEAN, HOME_BANNER_HIDE, MARKET_BANNER_HIDE,
        MARKET_SECTOR_COMPACT, MARKET_OVERVIEW_COMPACT,
        LOG_LEVEL, DESKTOP_ICON, LIQUID_GLASS, GLASS_EFFECT, LANGUAGE, THEME, PREDICTIVE_BACK,
    )

    /**
     * **功能开关键** —— 只属于「功能」这个范畴，不属于模块设置。
     *
     * 它们的出厂默认**全部是 `false`**（见 [DEFAULT_ENABLED]）。
     *
     * 「重置模块」只关闭这些开关（外加把功能参数恢复默认），
     * 不动桌面图标 / 底栏 / 语言 / 深色模式 / 日志等级这类模块级设置。
     */
    val FEATURE_KEYS =
        arrayOf(
            ENABLED, STG_DIRECT_QUOTE, STG_SELECT_ALL, STG_STOCK_FILTER, STOCK_BAR_ENTRY,
            DOCK_CLEAN, HOME_BANNER_HIDE, MARKET_BANNER_HIDE,
            MARKET_SECTOR_COMPACT, MARKET_OVERVIEW_COMPACT,
        )

    /**
     * 功能自己的参数（不属于开关，但「重置模块」一并恢复默认）。
     *
     * [STG_QUOTE_SWIPE] 归这里而不是 [FEATURE_KEYS]：它是「直达」这个功能下面的
     * 子选项（与 [SCOPE_STRATEGY] 同类），重置时应回到默认值 true 而不是被关掉。
     */
    val FEATURE_PARAM_KEYS = arrayOf(COLOR, SCOPE_STRATEGY, STG_QUOTE_SWIPE)

    /** 全部配置键的出厂默认值；备份文件的 `defaults` 与「重置模块」都以此为准 */
    val DEFAULTS: Map<String, Any> = mapOf(
        ENABLED to DEFAULT_ENABLED,
        COLOR to DEFAULT_COLOR,
        SCOPE_STRATEGY to DEFAULT_SCOPE_STRATEGY,
        STG_DIRECT_QUOTE to DEFAULT_STG_DIRECT_QUOTE,
        STG_QUOTE_SWIPE to DEFAULT_STG_QUOTE_SWIPE,
        STG_SELECT_ALL to DEFAULT_STG_SELECT_ALL,
        STG_STOCK_FILTER to DEFAULT_STG_STOCK_FILTER,
        STOCK_BAR_ENTRY to DEFAULT_STOCK_BAR_ENTRY,
        DOCK_CLEAN to DEFAULT_DOCK_CLEAN,
        HOME_BANNER_HIDE to DEFAULT_HOME_BANNER_HIDE,
        MARKET_BANNER_HIDE to DEFAULT_MARKET_BANNER_HIDE,
        MARKET_SECTOR_COMPACT to DEFAULT_MARKET_SECTOR_COMPACT,
        MARKET_OVERVIEW_COMPACT to DEFAULT_MARKET_OVERVIEW_COMPACT,
        LOG_LEVEL to DEFAULT_LOG_LEVEL,
        DESKTOP_ICON to DEFAULT_DESKTOP_ICON,
        LIQUID_GLASS to DEFAULT_LIQUID_GLASS,
        GLASS_EFFECT to DEFAULT_GLASS_EFFECT,
        LANGUAGE to DEFAULT_LANGUAGE,
        THEME to DEFAULT_THEME,
        PREDICTIVE_BACK to DEFAULT_PREDICTIVE_BACK,
    )

    // ---------------------------------------------------------------- 展示用

    /** 策略列表里名称的原始深色（原版 XML 默认值，用作兜底还原色） */
    const val FALLBACK_NORMAL_COLOR = 0xFF212634.toInt()

    /**
     * 可选高亮色（ARGB）。
     * 名称与说明是**展示文本**，放在 `R.array.preset_color_names` /
     * `R.array.preset_color_descriptions` 里以便本地化，顺序与本数组一一对应。
     */
    val PRESET_COLORS = intArrayOf(
        0xFFFF8000.toInt(),
        0xFFFFA000.toInt(),
        0xFFFF3B30.toInt(),
        0xFF3482FF.toInt(),
    )
}
