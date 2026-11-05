package io.github.MVennn.GSAuxiliary.feature.stgsnap

import io.github.MVennn.GSAuxiliary.core.TargetApp

/**
 * 「策略详情页 → 截图并添加」的宿主定位常量。
 *
 * 全部来自 2026-10-22 真机实测（魅族 20 / Android 16 / 金太阳 8.6.0 vc271）：
 *
 * ```
 * #ll_add_zxg_bottom
 *   ├─ "已选 " / #tv_bottom_zxg_num / " 只股票"
 *   └─ #tv_bottom_add_zxg "确认添加"
 *
 * StockFragAct
 *   └─ LockViewPager #vp_stocks
 *        └─ 当前页 #tv_top_stk_name_and_code
 *             └─ KXChartGroupView #chart_group_kx
 * ```
 *
 * App 升级后失效时，按 [io.github.MVennn.GSAuxiliary.feature.stgsnap] 各候选数组补名字，
 * 不要改实现逻辑。
 */
internal object StgSnapAddTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /** 策略详情页 */
    const val CLS_STG_DETAIL = "com.guosen.quotation.ui.aistk.AIStkStgDetailAct"

    /** 个股详情页 */
    const val CLS_STOCK_QUOTE = "com.guosen.quotation.ui.StockFragAct"

    // ------------------------------------------------------------ 策略页控件

    /** 多选态锚点：正常态「勾选加入自选股」，多选态「取消」 */
    val CANCEL_BUTTON_ID_CANDIDATES = arrayOf("tv_stg_add_zxg")

    /** 股票列表 */
    val LIST_ID_CANDIDATES = arrayOf("lvStks")

    /** 每行勾选框（checked states 不可用时的探针兜底） */
    val CHECKBOX_ID_CANDIDATES = arrayOf("ct_item")

    /** 底部已选计数（裸数字） */
    val BOTTOM_COUNT_ID_CANDIDATES = arrayOf("tv_bottom_zxg_num")

    /** 底部「确认添加」 */
    val CONFIRM_BUTTON_ID_CANDIDATES = arrayOf("tv_bottom_add_zxg")

    /** 进入多选态时锚点按钮的文案 */
    const val CANCEL_TEXT = "取消"

    /** 注入按钮文案与稳定 tag */
    const val BUTTON_TEXT = "截图并添加"
    const val BUTTON_TAG = "gsaux_snap_add_btn"

    // ------------------------------------------------------------ 详情页控件

    /** 详情页分页器 */
    val PAGER_ID_CANDIDATES = arrayOf("vp_stocks")

    /** 当前页标题「名称 代码」 */
    val PAGE_TITLE_ID_CANDIDATES = arrayOf("tv_top_stk_name_and_code")

    /** K 线图组与数据字段（日 K 就绪判据） */
    const val KX_GROUP_CLASS = "com.guosen.quotation.logicview.KXChartGroupView"
    const val KX_INFO_FIELD = "m_kxChartInfo"
    const val KX_ITEMS_FIELD = "m_kxChartItems"

    /** 强制切到日 K 的页签文案 */
    const val KLINE_TAB_TEXT = "日K"

    // ------------------------------------------------------------ Intent 契约

    const val ACTION_STOCK_DETAIL = "quotes.stockDetail"
    const val KEY_ADAPTER = "adapter_key"
    const val KEY_STOCK_LIST = "stockList"
    const val KEY_STOCK_INFO = "stockInfo"
    const val KEY_STOCK_INDEX = "stockIndex"
    const val CLS_INTENT_STOCK_INFO = "com.guosen.android.quotes.data.IntentStockInfo"

    // ------------------------------------------------------------ 数据字段

    val CAM_FIELDS = arrayOf("codeAndMarket", "m_codeAndMarket")
    val CODE_FIELDS = arrayOf("m_stkCode", "mStkCode", "code", "mCode")
    val MARKET_FIELDS = arrayOf("m_stkMarket", "mStkMarket", "market", "mMarket")
    val NAME_FIELDS = arrayOf("stockName", "m_stkName", "mStkName", "name")

    // ------------------------------------------------------------ 就绪与节奏

    /** 第一页等数据的超时（首次打开/首次拉 K 线更慢） */
    const val FIRST_READY_TIMEOUT_MS = 9000L

    /** 后续页等数据的超时 */
    const val READY_TIMEOUT_MS = 6000L

    /** 就绪轮询间隔（48ms ≈ 3 帧；只读视图状态，截图仍由就绪门禁决定） */
    const val READY_POLL_MS = 48L

    /**
     * 窗口不可截时（切到别的 app / 小窗收起 / 熄屏）的挂起轮询间隔。
     *
     * 挂起期间**不计就绪超时**，所以可以放宽到 200ms：每秒 5 次只读一个窗口可见性，
     * 比 48ms 省电，回来后的响应仍然在一帧左右。
     */
    const val PAUSE_POLL_MS = 200L

    /** 用户手动把页面滑走时，两次「拉回本页」之间的最小间隔 */
    const val PAGE_RESYNC_IDLE_MS = 400L

    /** 同一页最多拉回几次（用户在小窗里反复滑动时不跟人抢） */
    const val MAX_PAGE_RESYNC = 3

    /** 切页后先让 ViewPager 完成布局 */
    const val PAGE_SETTLE_MS = 180L

    /** 没有日 K 页签可点时，标题命中后再静置这么久才截图 */
    const val TITLE_SETTLE_MS = 900L

    /** 隐藏进度条后等这一小段时间再 PixelCopy，确保这一帧没有进度条 */
    const val CAPTURE_DELAY_MS = 48L

    /**
     * 同时「已截到、还没写完相册」的图片上限。
     *
     * 存盘与翻页并行：PixelCopy 拿到像素就切下一页。上限 2 保证宿主页瞬间就绪时
     * 位图也不会无限排队（每张 1080×2400 ARGB ≈ 10.4MB），满了就暂停翻页等存完一张。
     */
    const val MAX_INFLIGHT_SAVES = 2
}
