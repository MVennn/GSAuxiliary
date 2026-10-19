package io.github.MVennn.GSAuxiliary.feature.market

/**
 * 「行情页筛选面板增加涨跌与自选」的定位常量。
 *
 * ## 一、面板是谁的：宿主自己的 `FilterDiaglog`
 *
 * 行情页「沪深京A」左侧那颗漏斗点出来的面板**不是我们造的**（策略详情页的面板才是），
 * 是宿主的 `com.guosen.quotation.ui.dialog.FilterDiaglog`（真机日志：
 * `FilterDiaglog#show:206 <- HSMarketsFrag$9#onTabFliter:745`）。
 * 它用的布局与策略面板**同一个** `dialog_marketfliter`（id 常量见
 * `feature/filter/FilterPanelIds`），所以：
 *
 *  - 我们只往里**追加**一组行（`涨跌与自选` 三行），宿主原有的五行与它的适配器/监听
 *    一概不碰 —— 宿主点「确定」时仍然按它自己那套读勾选态、应用类型过滤；
 *  - 自己弹的面板（策略详情页那张）根视图上打了 [FilterPanelIds.TAG_OUR_PANEL]，
 *    注入前先看这个标记，避免把自己的面板也当成宿主的面板改一遍。
 *
 * ## 二、排行榜：`#rv_markets_hs` 的子项
 *
 * 实测（8.6.0 / vc271）：沪深京A排行榜的**每一行股票就是外层纵向 RecyclerView 的一个子项**
 * （不是单独一个列表），行内 id 复用自选股那套 `tv_item_zxg_*`：
 *
 * ```
 * RecyclerView #rv_markets_hs（整页纵向容器）
 *   …指数概览 / 市场概览 / 滚动横幅 / 快捷入口 / 板块…
 *   [沪深京A 版块头]  #iv_fliter  #tv_hs  #tv_hot_stk  #rg_market_hs_type
 *   FrameLayout（一行股票）
 *     LinearLayout #ll_zxg_container
 *       TextView #tv_item_zxg_name   名称（ST 判定看它）
 *       TextView #tv_item_zxg_code   6 位代码
 *       TextView #tv_item_price      最新价
 *       TextView #tv_item_zxg_data   涨跌幅（"+20.00%" / "--"）
 *       TextView #tv_item_zxg_zdf    行业板块（**名字叫 zdf，其实是行业**，实测）
 *     View #divider_sc_hq
 *   [查看更多]
 * ```
 *
 * 所以「筛掉某一行」= 把**那个子项**收起（高度归零 + GONE，`RecyclerView` 的
 * `LayoutManager` 不认 `GONE`，只设 GONE 会留一条等高空档）—— 见 [MarketFilterFeature]。
 */
object MarketFilterTargets {

    /** 宿主行情筛选面板的类名（真机实测；只用于「快速排除」与日志，判定仍以视图为准） */
    const val DIALOG_CLASS = "com.guosen.quotation.ui.dialog.FilterDiaglog"

    /** 排行榜所在的外层 RecyclerView */
    const val RANKING_LIST_ID = "rv_markets_hs"

    /** 排行榜行内的控件 id（三件套：代码 / 名称 / 涨跌幅） */
    const val RANKING_CODE_ID = "tv_item_zxg_code"
    const val RANKING_PERCENT_ID = "tv_item_zxg_data"

    /**
     * 最少要有几行才认为「排行榜已经在屏幕上了」。
     *
     * 一行都没有时**什么也不做**（下探用的行都是离屏的，收起它们没有意义，
     * 还会让宿主下次滚动时把我们的改动当成「行本来就该是 0 高」）。
     */
    const val RANKING_MIN_ROWS = 1
}
