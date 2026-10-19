package io.github.MVennn.GSAuxiliary.ui

import androidx.annotation.StringRes
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.core.ConfigKeys

/**
 * ## 功能分区归属标准
 *
 * 功能列表按 [FeatureSpec.categoryRes] 分组，**组内顺序 = [FEATURE_CATALOG] 里的出现顺序**
 * （`FeatureTab` 用 `groupBy` 分组，首次出现的分组排在最前）。目前八个分区：
 *
 * | 分区 | 一句话判据 | 归属范围（举例） |
 * |---|---|---|
 * | **登录** `category_login` | **只在「登录 / 会话」这件事上成立** —— 登录之后弹什么，与具体页面无关 | 隐藏新股申购提醒弹窗 … |
 * | **策略页** `category_strategy` | **改变「策略页」的交互行为** —— 点下去会发生什么、能做什么操作 | 点股票直达个股详情、策略页全选 / 反选股票、策略页三点菜单「筛选股票」、批量加入自选、策略列表长按菜单 … |
 * | **股票详情页** `category_stock_detail` | **改变「个股 / 指数详情页」的交互行为** —— 判据同「策略页」，只是页面换成详情页 | 底栏「智能盯盘」快捷入口、详情页快捷下单、K 线手势、盘口长按 … |
 * | **智能盯盘** `category_aistare` | **改的是「盯盘页面本身」** —— 智能盯盘页（H5）里的交互与内容 | 价格页「一键配置」按钮、盯盘页结构 … |
 * | **底栏** `category_dock` | **只改主界面底栏的结构** —— 少一格、换排布 | 底栏隐藏「理财」那一格、底栏入口排序、底栏换图标 … |
 * | **首页** `category_home` | **只改首页的结构** —— 隐藏 / 压缩首页上某一块 | 隐藏快捷入口下方的横幅、隐藏首页某个卡片 … |
 * | **行情市场** `category_market` | **只改行情市场页的结构** —— 隐藏 / 压缩该页的某一块 | 隐藏滚动横幅与快捷入口、板块显示单行、市场概览高度优化 … |
 * | **显示** `category_display` | **只改变「看到的样子」** —— 颜色、字号、图标、布局、动效；不改交互语义 | 自选股名称高亮、名称加粗 / 字号、涨跌幅色块、盘口数字着色、K 线指标叠加 … |
 *
 * ### 归类时怎么判断
 *
 * 依次问自己三个问题，**命中即停**：
 *
 * 1. **关掉它，用户的操作路径会不会变？** 会 → 该页面的分区（交互变了）。
 *    不会、只是「原来看不见的现在看得见」→ 显示。
 * 2. **它挂的钩子是「点击 / 跳转 / 数据流」还是「渲染」？** 前者 → 页面的分区；
 *    后者（`getView` / `setTextColor` 之类）→ 显示。
 * 3. **换个不叫「策略页」的页面，它照旧成立吗？** 成立 → 显示
 *    （显示类功能天然与业务页面解耦）；只在某个页面成立 → 该页面的分区。
 *
 * ### 页面级分区与「显示」怎么分
 *
 * 底栏 / 首页 / 行情市场这三个分区虽然改的也是布局，但它们**只对某一个页面成立**
 * —— 换个页面就不存在这个横幅、这个底栏格子。按判据 3，这类功能进**页面级分区**，
 * 不进通用的「显示」；反过来，「自选股名称高亮」换到任何列表页都成立，
 * 所以它留在「显示」。
 *
 * 一句话记法：**显示 = 通用的观感；底栏 / 首页 / 行情市场 = 只属于那一页的结构调整。**
 *
 * ### 交互类分区怎么选（策略页 / 股票详情页）
 *
 * 按**功能生效的那个页面**选，一个功能只进一个分区：
 *
 * - 只在「我的策略 → 策略详情」成立 → `category_strategy`；
 * - 只在「个股 / 指数 / 板块详情页」成立 → `category_stock_detail`；
 * - **两个页面都成立** → 归到「主战场」那一侧，并在功能说明里点明另一侧也生效
 *   （不为了一个功能同时挂两个分区）。
 *
 * 分区顺序按「上游 → 具体页面 → 通用外观」排：
 * 登录 → 策略页 → 股票详情页 → 底栏 → 首页 → 行情市场 → 显示。
 *
 * ### 边界与例外
 *
 * - **既改交互又改外观**（例如「策略页全选」顺带把选中行染个底色）→
 *   按**主要目的**归属：主要目的是「能一次选完」= 策略页。
 *   外观只是反馈，不因为带了一点染色就挪到显示。
 * - **新增分区之前先想清楚**：只有当一个功能**不属于任何一个已有分区**
 *   时才新开分区，并且要同时更新上表、`docs/EXTENDING.md` 的「分区怎么选」
 *   一节，以及 `values/strings.xml` / `values-en/strings.xml` 里的 `category_*`
 *   （引一个不存在的 `category_*` 会直接编译失败）。
 * - `category_aistare`（智能盯盘）与 `category_stock_detail`（股票详情页）的边界：
 *   `stock_bar_entry` 改的是**详情页底栏**（入口加在哪），归股票详情页；
 *   智能盯盘分区装的是**改盯盘页面本身**的功能（页面在盯盘流程内部，H5）。
 * - 分区名只是**展示文案**，落在 `R.string.category_*`；改名字不影响任何逻辑。
 *   分区名与功能名的起名规则见 `docs/NAMING.md`（**名称里不写「增强」「启用」**）。
 */

/**
 * 功能目录（**展示用元数据**）。
 *
 * 为什么不直接用 `feature/FeatureManager.features`？
 * 那个文件引用了 `HookBag`，而 `HookBag` 引用了 `io.github.libxposed.api` 类型；
 * 设置界面跑在模块自己的进程，那个进程的 APK 里没有这些类（`compileOnly`），
 * 一旦引用就会 `NoClassDefFoundError`。详见 docs/EXTENDING.md 架构红线 3。
 *
 * 所以界面自己维护一份展示元数据。新增功能时，在 [FEATURE_CATALOG] 加一条，
 * 并在 `feature/FeatureManager.features` 登记对应的实现类。
 */
internal data class FeatureSpec(
    /** 与 `Feature.id` 一致，用于路由二级菜单 */
    val id: String,
    @StringRes val categoryRes: Int,
    /**
     * 功能开关的标题 / 说明 —— **用户唯一可见的功能名**，起名遵循 `docs/NAMING.md`：
     * 名称里不写「启用 / 增强」（开关本身就是启用），新增类元素写成「增加 + 内容 + 控件类型」。
     */
    @StringRes val switchTitleRes: Int,
    /**
     * 开关下方那一行说明 —— **可空**。
     *
     * 副标题的核心职责是**说清功能塞到了哪里**（底栏、右上角三点菜单、多选栏 …）
     * —— 标题只说「加了 / 隐藏了什么」，没说「在哪」；位置说不清的功能必须补。
     * 主标题连同分组已经把话说全时不要写（传 null，卡片就只显示标题）：
     * 「隐藏新股申购提醒」下面再写「新股申购提醒不再弹出」是同一句话说了两遍。
     * 一行说完、能不用逗号就不用 —— 详见 `docs/NAMING.md` §「副标题」。
     */
    @StringRes val switchSummaryRes: Int? = null,
    /** 开关对应的配置键与默认值 */
    val enableKey: String,
    val defaultEnabled: Boolean,
    /** 二级菜单：标题、入口名、入口说明；为 null 表示该功能没有可调项 */
    @StringRes val detailTitleRes: Int? = null,
    @StringRes val detailEntryRes: Int? = null,
    @StringRes val detailEntrySummaryRes: Int? = null,
    /**
     * **第二个**二级入口（可空）。目前只有「增加 配置 按钮」用到：
     * 一个功能下同时挂「方案设置」与「配置按钮设置」两个页面。
     */
    @StringRes val detailEntry2Res: Int? = null,
) {
    /** 是否有可调节的具体设置（决定是否出现「开启后展开的二级入口」） */
    val hasDetail: Boolean get() = detailTitleRes != null && detailEntryRes != null
}

/** 全部功能的展示元数据；顺序即界面顺序 */
internal val FEATURE_CATALOG: List<FeatureSpec> = listOf(
    // ---- 登录：登录 / 会话相关的提醒与状态 ----
    FeatureSpec(
        id = "ipo_dialog_clean",
        categoryRes = R.string.category_login,
        switchTitleRes = R.string.feature_ipo_clean_switch,
        // 无副标题：标题已经说完（隐藏弹窗 = 不再弹）
        enableKey = ConfigKeys.IPO_DIALOG_CLEAN,
        defaultEnabled = ConfigKeys.DEFAULT_IPO_DIALOG_CLEAN,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    // ---- 策略页：改变策略页的交互行为 ----
    FeatureSpec(
        id = "stg_direct_quote",
        categoryRes = R.string.category_strategy,
        switchTitleRes = R.string.feature_stg_direct_switch,
        // 副标题说清交互路径：点了什么、跳到哪里
        switchSummaryRes = R.string.feature_stg_direct_switch_summary,
        enableKey = ConfigKeys.STG_DIRECT_QUOTE,
        defaultEnabled = ConfigKeys.DEFAULT_STG_DIRECT_QUOTE,
        detailTitleRes = R.string.detail_stg_direct_title,
        detailEntryRes = R.string.feature_stg_direct_detail_entry,
        // 无入口副标题：「滑动设置」已经说清进去做什么，不再列一遍里面的项目（docs/NAMING.md §3）
    ),

    FeatureSpec(
        id = "stg_select_all",
        categoryRes = R.string.category_strategy,
        switchTitleRes = R.string.feature_select_all_switch,
        // 副标题说位置：多选栏只有长按股票才会出现，「取消」左边是按钮的确切落点
        switchSummaryRes = R.string.feature_select_all_switch_summary,
        enableKey = ConfigKeys.STG_SELECT_ALL,
        defaultEnabled = ConfigKeys.DEFAULT_STG_SELECT_ALL,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    FeatureSpec(
        id = "stg_stock_filter",
        categoryRes = R.string.category_strategy,
        switchTitleRes = R.string.feature_stg_filter_switch,
        // 副标题说位置：三点菜单平时是收起的，不开副标题不知道加在了哪个菜单里
        switchSummaryRes = R.string.feature_stg_filter_switch_summary,
        enableKey = ConfigKeys.STG_STOCK_FILTER,
        defaultEnabled = ConfigKeys.DEFAULT_STG_STOCK_FILTER,
        // 三个筛选开关的编辑入口在**目标 App 自己的筛选面板**里（策略详情页三点菜单），
        // 不在模块设置里 —— 所以这里没有二级菜单。
    ),

    // ---- 股票详情页：改变「个股 / 指数详情页」的交互行为 ----
    FeatureSpec(
        id = "stock_bar_entry",
        categoryRes = R.string.category_stock_detail,
        switchTitleRes = R.string.feature_stock_bar_switch,
        // 副标题说位置：入口塞在底栏哪一格，开了开关去哪里看
        switchSummaryRes = R.string.feature_stock_bar_switch_summary,
        enableKey = ConfigKeys.STOCK_BAR_ENTRY,
        defaultEnabled = ConfigKeys.DEFAULT_STOCK_BAR_ENTRY,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    // ---- 智能盯盘：改盯盘页面本身（价格页是 H5，入口在详情页底栏不归这里）----
    FeatureSpec(
        id = "aistare_quick_config",
        categoryRes = R.string.category_aistare,
        switchTitleRes = R.string.feature_quick_config_switch,
        // 副标题说位置：按钮塞在盯盘价格页底部哪一格，不开副标题不知道
        switchSummaryRes = R.string.feature_quick_config_switch_summary,
        enableKey = ConfigKeys.AISTARE_QUICK_ENABLED,
        defaultEnabled = ConfigKeys.DEFAULT_AISTARE_QUICK_ENABLED,
        detailTitleRes = R.string.detail_quick_config_title,
        detailEntryRes = R.string.feature_quick_config_detail_entry,
        // 无入口副标题：「方案设置」已经说清进去做什么（原「为 价格 配置 增加自定义方案」是复述）
        detailEntry2Res = R.string.feature_quick_config_button_entry,
    ),

    // 「优化确定逻辑」：空数据也能保存（= 从我的盯盘删掉这条盯盘）
    FeatureSpec(
        id = "aistare_empty_save",
        categoryRes = R.string.category_aistare,
        switchTitleRes = R.string.feature_empty_save_switch,
        switchSummaryRes = R.string.feature_empty_save_switch_summary,
        enableKey = ConfigKeys.AISTARE_EMPTY_SAVE,
        defaultEnabled = ConfigKeys.DEFAULT_AISTARE_EMPTY_SAVE,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    // 保存成功之后的行为：免确认 + 返回位置（默认关闭，保持宿主原有交互）
    FeatureSpec(
        id = "aistare_skip_save_confirm",
        categoryRes = R.string.category_aistare,
        switchTitleRes = R.string.feature_skip_save_switch,
        switchSummaryRes = R.string.feature_skip_save_switch_summary,
        enableKey = ConfigKeys.AISTARE_SKIP_SAVE_CONFIRM,
        defaultEnabled = ConfigKeys.DEFAULT_AISTARE_SKIP_SAVE_CONFIRM,
        detailTitleRes = R.string.feature_skip_save_detail_title,
        detailEntryRes = R.string.feature_skip_save_detail_entry,
    ),

    // 盯盘数据卡片拓展：卡片从不可点变成可点，界面不加任何元素
    FeatureSpec(
        id = "aistare_card_detail",
        categoryRes = R.string.category_aistare,
        switchTitleRes = R.string.feature_card_detail_switch,
        // 副标题说清点了会怎样：标题只说「拓展」，不说落到哪个页面
        switchSummaryRes = R.string.feature_card_detail_switch_summary,
        enableKey = ConfigKeys.AISTARE_CARD_DETAIL,
        defaultEnabled = ConfigKeys.DEFAULT_AISTARE_CARD_DETAIL,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    // ---- 底栏：只改主界面底栏的结构 ----
    FeatureSpec(
        id = "dock_clean",
        categoryRes = R.string.category_dock,
        switchTitleRes = R.string.feature_dock_clean_switch,
        // 无副标题：原副标题「其余入口自动均分铺满整行」是布局后果，不是功能本身
        enableKey = ConfigKeys.DOCK_CLEAN,
        defaultEnabled = ConfigKeys.DEFAULT_DOCK_CLEAN,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    // ---- 首页：只改首页的结构 ----
    FeatureSpec(
        id = "home_banner_hide",
        categoryRes = R.string.category_home,
        switchTitleRes = R.string.feature_home_banner_switch,
        // 副标题说位置：首页横幅不止一条，得说清隐藏的是哪一条
        switchSummaryRes = R.string.feature_home_banner_switch_summary,
        enableKey = ConfigKeys.HOME_BANNER_HIDE,
        defaultEnabled = ConfigKeys.DEFAULT_HOME_BANNER_HIDE,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    // ---- 行情市场：只改行情市场页的结构 ----
    FeatureSpec(
        id = "market_banner_hide",
        categoryRes = R.string.category_market,
        switchTitleRes = R.string.feature_market_banner_switch,
        // 无副标题：原副标题「板块直接顶上来」是布局后果
        enableKey = ConfigKeys.MARKET_BANNER_HIDE,
        defaultEnabled = ConfigKeys.DEFAULT_MARKET_BANNER_HIDE,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    FeatureSpec(
        id = "market_sector_compact",
        categoryRes = R.string.category_market,
        switchTitleRes = R.string.feature_market_sector_switch,
        // 无副标题：原副标题「板块区整体变矮」是布局后果
        enableKey = ConfigKeys.MARKET_SECTOR_COMPACT,
        defaultEnabled = ConfigKeys.DEFAULT_MARKET_SECTOR_COMPACT,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    FeatureSpec(
        id = "market_overview_compact",
        categoryRes = R.string.category_market,
        switchTitleRes = R.string.feature_market_overview_switch,
        // 无副标题：「市场概览高度优化」已经说清了改的是什么
        enableKey = ConfigKeys.MARKET_OVERVIEW_COMPACT,
        defaultEnabled = ConfigKeys.DEFAULT_MARKET_OVERVIEW_COMPACT,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    // 「隐藏 指数概览」：行情页顶部那一行（上证 / 深证 / 北证50）整块收起
    FeatureSpec(
        id = "market_index_hide",
        categoryRes = R.string.category_market,
        switchTitleRes = R.string.feature_market_index_hide_switch,
        // 无副标题：「隐藏 指数概览」已经说清了藏的是什么
        enableKey = ConfigKeys.MARKET_INDEX_HIDE,
        defaultEnabled = ConfigKeys.DEFAULT_MARKET_INDEX_HIDE,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    // 「增加 涨跌与自选 筛选项」：宿主筛选面板里追加三行，并按其筛排行榜
    FeatureSpec(
        id = "market_stock_filter",
        categoryRes = R.string.category_market,
        switchTitleRes = R.string.feature_market_filter_switch,
        // 副标题说位置：那颗漏斗平时就是个图标，不写不知道「筛选项」加在了哪
        switchSummaryRes = R.string.feature_market_filter_switch_summary,
        enableKey = ConfigKeys.MARKET_STOCK_FILTER,
        defaultEnabled = ConfigKeys.DEFAULT_MARKET_STOCK_FILTER,
        // 三个筛选项的勾选在**目标 App 自己的筛选面板**里，不在模块设置里 —— 没有二级菜单
    ),

    // ---- 显示：只改看到的样子 ----
    FeatureSpec(
        id = "nav_bar_blend",
        categoryRes = R.string.category_display,
        switchTitleRes = R.string.feature_nav_bar_switch,
        // 副标题点明是哪根条、怎么来的（「小白条」是用户对底部手势条的叫法）
        switchSummaryRes = R.string.feature_nav_bar_switch_summary,
        enableKey = ConfigKeys.NAV_BAR_BLEND,
        defaultEnabled = ConfigKeys.DEFAULT_NAV_BAR_BLEND,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    FeatureSpec(
        id = "watchlist_highlight",
        categoryRes = R.string.category_display,
        switchTitleRes = R.string.feature_watchlist_switch,
        // 无副标题：原副标题「把已加入自选的股票名称显示为高亮色」就是标题的展开写法
        enableKey = ConfigKeys.ENABLED,
        defaultEnabled = ConfigKeys.DEFAULT_ENABLED,
        detailTitleRes = R.string.detail_color_title,
        detailEntryRes = R.string.feature_detail_entry,
        // 无入口副标题：「高亮颜色设置」已经说清进去做什么，不再列一遍里面的项目
    ),

    // 「自选股名称加粗」：**与上面那个高亮共用同一个 Feature**
    // （`WatchlistFeature` 承载两个开关）—— 所以 `FeatureManager` 里**不新增**一行，
    // 但 `isEnabled` 的 or 链必须同时覆盖 `ENABLED` 与 `WATCHLIST_BOLD` 两个位
    // （漏一个就是假开关，见 docs/EXTENDING.md §3 第 4 步）。
    FeatureSpec(
        id = "watchlist_bold",
        categoryRes = R.string.category_display,
        switchTitleRes = R.string.feature_watchlist_bold_switch,
        // 无副标题：与上面那个高亮是同一批行上的另一种强调方式
        enableKey = ConfigKeys.WATCHLIST_BOLD,
        defaultEnabled = ConfigKeys.DEFAULT_WATCHLIST_BOLD,
        // 无二级入口：加粗没有可调参数
    ),
)
