package io.github.MVennn.GSAuxiliary.feature.strategyquote

import io.github.MVennn.GSAuxiliary.core.TargetApp

/**
 * 「策略详情页直达标准行情」的定位常量。
 *
 * **全部来自真机探针实测**（金太阳 8.6.0 / vc271，探针 v25），
 * 详见 `docs/FEATURE-DIRECT-QUOTE.md`。App 升级后改名只需往这些数组里加候选，
 * 不用重做一遍逆向。
 */
object StgDirectQuoteTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /** 点击来源：策略详情页（用它判定"这一跳是从哪来的"） */
    const val CLS_STG_DETAIL = "com.guosen.quotation.ui.aistk.AIStkStgDetailAct"

    /**
     * 引擎路由基类。目标 App 所有页面跳转都走它：
     * `startEngineAct(EngineIntent)` / `startEngineActForResult(EngineIntent, int)`。
     * 挂这里而不是挂策略页的 `onItemClick`，是为了**复用 App 自己构造好的 EngineIntent**，
     * 只改内容和 action，路由仍交给 App。
     */
    const val CLS_ENGINE_ACT = "com.guosen.engine.process.EngineAct"

    /**
     * 个股详情页接收的股票数据类。
     * 它自带 `IntentStockInfo(StockInfo)` 构造器 —— 策略页给的是 `StockInfo`，
     * 靠这个构造器做类型转换，不用自己拼字段。
     */
    const val CLS_INTENT_STOCK_INFO = "com.guosen.android.quotes.data.IntentStockInfo"

    /** 策略页 → 中间页（`AIStkDetailFragAct`）的 action：命中它才改写 */
    const val ACTION_TO_MIDDLE = "router_ai_stk_detail"

    /** 行情页 → 个股详情（`StockFragAct`）的 action：把它改写成这个 */
    const val ACTION_STOCK_DETAIL = "quotes.stockDetail"

    /** 挂载入口方法名候选（两个都要挂：前者内部会再调后者，改写是幂等的） */
    val LAUNCH_METHODS = arrayOf("startEngineAct", "startEngineActForResult")

    // ------------------------------------------------------------------ extras

    /** 承载股票数据的 Bundle 名 */
    const val KEY_ADAPTER = "adapter_key"

    /** Bundle 里「列表」条目名（策略侧给的是 ArrayList&lt;StockInfo&gt;，行情侧是 ArrayList&lt;IntentStockInfo&gt;） */
    const val KEY_STOCK_LIST = "stockList"

    /** Bundle 里「单只」条目名（策略侧给的单只 StockInfo） */
    const val KEY_STOCK_ITEM = "stockItem"

    /**
     * Bundle 里「单只」条目名（**行情侧契约**）。
     *
     * 中间页点「标准行情」跳个股详情时用的就是它：`adapter_key.stockInfo` = 单只
     * `IntentStockInfo`（无 `stockList`、无 `stockIndex`）→ 个股详情页 count=1。
     * 「左右滑动」关掉时按这条契约走。
     */
    const val KEY_STOCK_INFO = "stockInfo"

    /** 外层 extras：当前下标（行情侧有、策略侧没有 —— 少了它就是"不能滑动"的直接原因） */
    const val KEY_STOCK_INDEX = "stockIndex"

    // ------------------------------------------------------------------ 字段候选

    /** 「代码.市场」拼接串字段候选 */
    val CAM_FIELDS = arrayOf("codeAndMarket", "m_codeAndMarket")

    /** 兜底：代码 / 市场 两个字段分开取 */
    val CODE_FIELDS = arrayOf("m_stkCode", "mStkCode", "code", "mCode")
    val MARKET_FIELDS = arrayOf("m_stkMarket", "mStkMarket", "market", "mMarket")
}
