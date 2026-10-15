package io.github.SansKritO4.GSAuxiliary.ui

import androidx.annotation.StringRes
import io.github.SansKritO4.GSAuxiliary.R
import io.github.SansKritO4.GSAuxiliary.core.ConfigKeys

/**
 * ## 功能分区归属标准
 *
 * 功能列表按 [FeatureSpec.categoryRes] 分组，**组内顺序 = [FEATURE_CATALOG] 里的出现顺序**
 * （`FeatureTab` 用 `groupBy` 分组，首次出现的分组排在最前）。目前三个分区：
 *
 * | 分区 | 一句话判据 | 归属范围（举例） |
 * |---|---|---|
 * | **策略页增强** `category_strategy` | **改变「策略页」的交互行为** —— 点下去会发生什么、能做什么操作 | 点股票直达个股详情、策略页全选 / 反选股票、策略页三点菜单「筛选股票」、批量加入自选、策略列表长按菜单 … |
 * | **股票详情页增强** `category_stock_detail` | **改变「个股 / 指数详情页」的交互行为** —— 判据同「策略页增强」，只是页面换成详情页 | 底栏「智能盯盘」快捷入口、详情页快捷下单、K 线手势、盘口长按 … |
 * | **显示增强** `category_display` | **只改变「看到的样子」** —— 颜色、字号、图标、布局、动效；不改交互语义 | 自选股名称高亮、名称加粗 / 字号、涨跌幅色块、盘口数字着色、K 线指标叠加 … |
 *
 * ### 归类时怎么判断
 *
 * 依次问自己三个问题，**命中即停**：
 *
 * 1. **关掉它，用户的操作路径会不会变？** 会 → 该页面的「增强」分区（交互变了）。
 *    不会、只是「原来看不见的现在看得见」→ 显示增强。
 * 2. **它挂的钩子是「点击 / 跳转 / 数据流」还是「渲染」？** 前者 → 页面的「增强」分区；
 *    后者（`getView` / `setTextColor` 之类）→ 显示增强。
 * 3. **换个不叫「策略页」的页面，它照旧成立吗？** 成立 → 显示增强
 *    （显示类功能天然与业务页面解耦）；只在某个页面成立 → 该页面的「增强」分区。
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
 * 分区顺序按「具体页面 → 通用外观」排：策略页 → 股票详情页 → 显示增强。
 *
 * ### 边界与例外
 *
 * - **既改交互又改外观**（例如「策略页全选」顺带把选中行染个底色）→
 *   按**主要目的**归属：主要目的是「能一次选完」= 策略页增强。
 *   外观只是反馈，不因为带了一点染色就挪到显示增强。
 * - **新增分区之前先想清楚**：只有当一个功能**不属于任何一个已有分区**
 *   时才新开分区，并且要同时更新上表、`docs/EXTENDING.md` 的「分区怎么选」
 *   一节，以及 `values/strings.xml` / `values-en/strings.xml` 里的 `category_*`
 *   （引一个不存在的 `category_*` 会直接编译失败）。
 * - 分区名只是**展示文案**，落在 `R.string.category_*`；改名字不影响任何逻辑。
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
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    /** 功能开关的标题 / 说明 */
    @StringRes val switchTitleRes: Int,
    @StringRes val switchSummaryRes: Int,
    /** 开关对应的配置键与默认值 */
    val enableKey: String,
    val defaultEnabled: Boolean,
    /** 二级菜单：标题、入口名、入口说明；为 null 表示该功能没有可调项 */
    @StringRes val detailTitleRes: Int? = null,
    @StringRes val detailEntryRes: Int? = null,
    @StringRes val detailEntrySummaryRes: Int? = null,
) {
    /** 是否有可调节的具体设置（决定是否出现「开启后展开的二级入口」） */
    val hasDetail: Boolean get() = detailTitleRes != null && detailEntryRes != null
}

/** 全部功能的展示元数据；顺序即界面顺序 */
internal val FEATURE_CATALOG: List<FeatureSpec> = listOf(
    // ---- 策略页增强：改变策略页的交互行为 ----
    FeatureSpec(
        id = "stg_direct_quote",
        categoryRes = R.string.category_strategy,
        titleRes = R.string.feature_stg_direct_title,
        summaryRes = R.string.feature_stg_direct_summary,
        switchTitleRes = R.string.feature_stg_direct_switch,
        switchSummaryRes = R.string.feature_stg_direct_switch_summary,
        enableKey = ConfigKeys.STG_DIRECT_QUOTE,
        defaultEnabled = ConfigKeys.DEFAULT_STG_DIRECT_QUOTE,
        detailTitleRes = R.string.detail_stg_direct_title,
        detailEntryRes = R.string.feature_stg_direct_detail_entry,
        detailEntrySummaryRes = R.string.feature_stg_direct_detail_entry_summary,
    ),

    FeatureSpec(
        id = "stg_select_all",
        categoryRes = R.string.category_strategy,
        titleRes = R.string.feature_select_all_title,
        summaryRes = R.string.feature_select_all_summary,
        switchTitleRes = R.string.feature_select_all_switch,
        switchSummaryRes = R.string.feature_select_all_switch_summary,
        enableKey = ConfigKeys.STG_SELECT_ALL,
        defaultEnabled = ConfigKeys.DEFAULT_STG_SELECT_ALL,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    FeatureSpec(
        id = "stg_stock_filter",
        categoryRes = R.string.category_strategy,
        titleRes = R.string.feature_stg_filter_title,
        summaryRes = R.string.feature_stg_filter_summary,
        switchTitleRes = R.string.feature_stg_filter_switch,
        switchSummaryRes = R.string.feature_stg_filter_switch_summary,
        enableKey = ConfigKeys.STG_STOCK_FILTER,
        defaultEnabled = ConfigKeys.DEFAULT_STG_STOCK_FILTER,
        // 三个筛选开关的编辑入口在**目标 App 自己的筛选面板**里（策略详情页三点菜单），
        // 不在模块设置里 —— 所以这里没有二级菜单。
    ),

    // ---- 股票详情页增强：改变「个股 / 指数详情页」的交互行为 ----
    FeatureSpec(
        id = "stock_bar_entry",
        categoryRes = R.string.category_stock_detail,
        titleRes = R.string.feature_stock_bar_title,
        summaryRes = R.string.feature_stock_bar_summary,
        switchTitleRes = R.string.feature_stock_bar_switch,
        switchSummaryRes = R.string.feature_stock_bar_switch_summary,
        enableKey = ConfigKeys.STOCK_BAR_ENTRY,
        defaultEnabled = ConfigKeys.DEFAULT_STOCK_BAR_ENTRY,
        // 无参数项：一级页只放开关，不展开二级入口（FeatureSpec.hasDetail = false）
    ),

    // ---- 显示增强：只改看到的样子 ----
    FeatureSpec(
        id = "watchlist_highlight",
        categoryRes = R.string.category_display,
        titleRes = R.string.feature_watchlist_title,
        summaryRes = R.string.feature_watchlist_summary,
        switchTitleRes = R.string.feature_watchlist_switch,
        switchSummaryRes = R.string.feature_watchlist_switch_summary,
        enableKey = ConfigKeys.ENABLED,
        defaultEnabled = ConfigKeys.DEFAULT_ENABLED,
        detailTitleRes = R.string.detail_color_title,
        detailEntryRes = R.string.feature_detail_entry,
        detailEntrySummaryRes = R.string.feature_detail_entry_summary,
    ),
)
