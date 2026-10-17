package io.github.MVennn.GSAuxiliary.feature.aistare

import io.github.MVennn.GSAuxiliary.core.TargetApp

/**
 * 「智能盯盘 → 我的盯盘」卡片直达个股详情的全部目标常量。
 *
 * **每一项都是真机实测**（2026-10-17，魅族 20 / Android 16 / 金太阳 8.6.0 vc271，
 * 依据 `uiautomator dump` 与模块运行时日志，结论原文见
 * `docs/FEATURE-AISTARE-CARD-DETAIL.md`）—— 不是推断。
 * App 升级后失效时：重跑探针 / 加日志，改这里的候选数组即可。
 */
object AiStareCardDetailTargets {

    // ------------------------------------------------------------ 页面判定

    /**
     * 「我的盯盘」所在页面。
     *
     * 实测：它是一个**原生** Activity（不是 H5），页面里上方是「今日提醒 / 我的盯盘」
     * 两个页签，下面是一个 `ListView`。顶栏标题「智能盯盘」，右上角「创建」。
     * 价格页（`AIStareWebView`）是另一个页面，两者互不影响。
     */
    const val ACTIVITY = "com.guosen.quotation.aistare.ui.AIStareIndexAct"

    /** 页面类名前缀（Activity 之外的弹层 / 子页面也归这一族） */
    const val ACTIVITY_PREFIX = "com.guosen.quotation.aistare.ui."

    /**
     * 承载盯盘卡片的 `ListView` 的资源 id。
     *
     * 实测 dump：
     * ```
     * [0,645][1080,1865] id=listMyStare class=ListView
     *   ├ layoutStock   (LinearLayout, clickable=true)  tvStkName / tvCodeMarket / ivStkArrow
     *   └ layoutContent (LinearLayout, clickable=false) tvDes / tvType / tvExpire / imgEdit / imgDel
     * ```
     */
    const val LIST_ID = "listMyStare"

    /**
     * 卡片本体（白色圆角卡片）：描述、类型、提醒时效、右下角编辑 / 删除。
     *
     * **只给它挂点击**：编辑 / 删除是两个自带 `clickable=true` 的 ImageView，
     * 子视图先消费点击，所以它们的行为一点都不会变（见 [EDIT_ICON_IDS]）。
     */
    const val CARD_ID = "layoutContent"

    /** 股票名 / 代码（卡片上方那一行，宿主本来就可点，我们不动它） */
    const val NAME_ID = "tvStkName"
    const val CODE_ID = "tvCodeMarket"

    /** 右下角「编辑方案」「删除」两个图标（必须保持原样） */
    val EDIT_ICON_IDS = arrayOf("imgEdit", "imgDel")

    // ------------------------------------------------------------ 数据字段

    /**
     * 只接管这一个 adapter（真机实测，2026-10-17）：
     * 「我的盯盘」页签的列表是 `AIStareMyStareAdapter`，item 数据类
     * `MyStareItem` 自带 `codeAndMarket`（`"688592.2"`）。同页的
     * 「今日提醒」页签是另一个 adapter（`AIStareTodayWarnAdapter`），
     * 卡片语义不同（点的是提醒不是股票），**不接管**。
     */
    val TARGET_ADAPTERS = arrayOf(
        "com.guosen.quotation.aistare.adapter.AIStareMyStareAdapter",
    )

    /**
     * 从 adapter 的 item 对象上取「代码.市场」。
     *
     * 结构同 `WatchlistTargets`：`cam` 优先（宿主自家约定 `000001.1`），
     * 拿不到再拆 code + market 两个字段自己拼。候选数组在真机日志里确认后固化。
     */
    val CAM_FIELDS = arrayOf("codeAndMarket", "m_codeAndMarket", "mCodeAndMarket", "cam")

    val CODE_FIELDS = arrayOf(
        "stockCode", "m_stockCode", "m_stkCode", "mStkCode", "stkCode", "code", "mCode",
    )

    val MARKET_FIELDS = arrayOf(
        "market", "m_market", "m_stkMarket", "mStkMarket", "stkMarket", "mMarket",
    )

    /** adapter 里数据列表的常见字段名（`getItem` 拿不到时兜底） */
    val DATA_FIELDS = arrayOf("mData", "mDataList", "mItems", "data", "mList", "list", "mDatas")

    // ------------------------------------------------------------ 跳转契约

    /** 个股详情页（与「一键配置」保存后拉起的是同一个页面） */
    const val STOCK_FRAG_CLASS = "com.guosen.quotation.ui.StockFragAct"
    const val CLS_INTENT_STOCK_INFO = "com.guosen.android.quotes.data.IntentStockInfo"
    const val ACTION_STOCK_DETAIL = "quotes.stockDetail"
    const val KEY_ADAPTER = "adapter_key"
    const val KEY_STOCK_INFO = "stockInfo"

    // ------------------------------------------------------------ 资源解析

    /**
     * 解析资源 id 用的包名。
     *
     * 宿主 `resources.getIdentifier(name, "id", pkg)` 必须用宿主自己的包名 ——
     * 用 [TargetApp.PACKAGE]（`com.guosen.android`）而不是 `com.guosen.quotation`
     * 的子包名，资源是在 app 模块统一编进去的。
     */
    const val RES_PKG = TargetApp.PACKAGE
}
