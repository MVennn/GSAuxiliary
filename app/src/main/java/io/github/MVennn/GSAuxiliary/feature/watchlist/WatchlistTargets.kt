package io.github.MVennn.GSAuxiliary.feature.watchlist

import io.github.MVennn.GSAuxiliary.core.TargetApp

/**
 * 目标 App 里与「自选高亮」相关的定位常量。
 *
 * 全部做成候选数组 —— App 升级后改名字，只需要往数组里加一项，
 * 不用重做一遍逆向。配合 `tools/probe/` 的运行时探针重新定位。
 */
object WatchlistTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /**
     * 国信自己的列表基类。
     *
     * 为什么挂它而不是 RecyclerView：策略详情页用阿里 vlayout，
     * 子适配器的 `bindViewHolder` 不经过常规路径；而 `BaseQuickAdapter.getView`
     * 只要列表要显示一行就一定被调用，且返回时行视图已构建完成。
     */
    const val CLS_BASE_QUICK_ADAPTER = "com.guosen.commonui.adapter.BaseQuickAdapter"

    /** 需要处理的目标适配器（策略详情页）。双保险，防止它自己覆写了 getView。 */
    val TARGET_ADAPTERS = arrayOf(
        "com.guosen.quotation.adapter.aistk.AIStkAdapter",
    )

    /** 名称 TextView 的 id（策略详情页实测） */
    val NAME_ID_CANDIDATES = arrayOf(
        "tv_item_stk_name",
    )

    /**
     * 行内「涨跌幅」那格的 id（策略详情页实测；「筛选股票」读的是同一格）。
     *
     * 分档配色按它与数据项上的涨跌幅字段判涨 / 跌 / 平 ——
     * 字段有时是空的（列表只给了文案），文案才是用户眼睛里看到的东西。
     */
    val CHANGE_ID_CANDIDATES = arrayOf(
        "tv_item_stk_percent",
    )

    /**
     * 数据项里「涨跌幅」字段的候选（`zdf` 来自 StockInfo dump，实测是 `BigDecimal`）。
     *
     * 按序取第一个非空；全都读不出来时按行内文案判定。
     */
    val ZDF_FIELDS = arrayOf(
        "zdf", "m_stkZdf", "mStkZdf", "m_zdf", "m_stkPercent", "mPercent", "percent",
    )

    /**
     * 策略详情页承载股票列表的 ListView（实测 id = `lvStks`）。
     *
     * 从个股详情页加/删自选返回后，行视图已存在、`getView` 不再被调用，
     * 需要在 `onResume` 时直接遍历它的可见子 View 重绘（见 [WatchlistFeature]）。
     */
    val LIST_ID_CANDIDATES = arrayOf(
        "lvStks",
    )

    /**
     * 需要「返回时强制重绘」的页面（Activity 类名，实测）。
     * 只对策略详情页做这件事 —— 它才是「点进个股 → 加自选 → 返回」的那一页。
     */
    val RESUME_REPAINT_ACTIVITIES = arrayOf(
        "com.guosen.quotation.ui.aistk.AIStkStgDetailAct",
    )

    /** 数据项里「代码.市场」字段候选 */
    val CAM_FIELDS = arrayOf(
        "codeAndMarket", "m_codeAndMarket",
    )

    /** 兜底：代码 + 市场两个字段 */
    val CODE_FIELDS = arrayOf("m_stkCode", "code", "mCode")
    val MARKET_FIELDS = arrayOf("m_stkMarket", "market", "mMarket")

    fun isTargetAdapter(className: String): Boolean =
        TARGET_ADAPTERS.any { it == className }

    /** 该 Activity 是否需要在 `onResume` 时强制重绘（策略详情页） */
    fun isResumeRepaintActivity(className: String): Boolean =
        RESUME_REPAINT_ACTIVITIES.any { it == className }
}
