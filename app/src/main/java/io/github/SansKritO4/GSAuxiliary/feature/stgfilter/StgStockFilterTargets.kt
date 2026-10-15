package io.github.SansKritO4.GSAuxiliary.feature.stgfilter

import io.github.SansKritO4.GSAuxiliary.core.TargetApp

/**
 * 「策略详情页筛选股票」的定位常量。
 *
 * **全部来自真机实测**（金太阳 8.6.0 / vc271，魅族 20 / Android 16）。
 *
 * ## 三点菜单（实测）
 *
 * 右上角那个「⋮」弹的是一个**自定义 `PopupWindow`**，内容就是布局
 * `res/layout/pop_ai_stg.xml`：
 *
 * ```xml
 * LinearLayout(vertical, 103dp × 98dp)
 *   ├─ TextView #tv_edit_name  "修改名称"   (height=0dp weight=1)
 *   ├─ ImageView  (1dp 分隔线, 无 id)
 *   └─ TextView #tv_del_stg    "删除策略"   (height=0dp weight=1)
 * ```
 *
 * 实测 uiautomator 抓到的树：
 * ```
 * FrameLayout [812,204][1080,459]
 *   └─ LinearLayout [812,204][1080,459]
 *        ├─ TextView #tv_edit_name "修改名称" [815,225][1076,339]
 *        ├─ ImageView (分隔线)                [815,339][1076,342]
 *        └─ TextView #tv_del_stg   "删除策略" [815,342][1076,456]
 * ```
 *
 * ## 筛选面板（实测）
 *
 * 行情页「沪深京A」左侧那颗漏斗图标点出来的是一个 **Dialog**，内容布局是
 * `res/layout/dialog_marketfliter.xml`（原包名把 filter 拼成了 fliter，照抄不改）：
 *
 * ```xml
 * ConstraintLayout
 *   ├─ TextView #tv1  "沪深京A排行榜-股票类型"（标题）
 *   ├─ TextView #tv2  "请在下方勾选您想看的股票类型"（副标题）
 *   ├─ TextView #tv3  "沪深A主板"（分组头）
 *   ├─ ImageView（分组折叠箭头，无 id）
 *   ├─ View #line1（分隔线）
 *   ├─ RecyclerView #rv_markets   ← 行容器
 *   ├─ TextView #tv_cancel "取消"
 *   └─ TextView #tv_ok     "确定"
 * ```
 *
 * 行布局 `res/layout/item_market_filter.xml`：
 * ```xml
 * ConstraintLayout #itemroot (clickable, 45dp)
 *   ├─ TextView   #name
 *   ├─ CheckBox   #cb_checked (30dp)
 *   └─ View（0.5dp 分隔线）
 * ```
 *
 * 实测 uiautomator 抓到的树确认了这些 id 与坐标（面板窗口 [0,1245][1080,2335]，
 * 底部两颗按钮各占半宽 50dp 高）。
 */
object StgStockFilterTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /** 只对这一个页面做注入（Activity 类名，实测） */
    val WATCH_ACTIVITIES = arrayOf(
        "com.guosen.quotation.ui.aistk.AIStkStgDetailAct",
    )

    // ------------------------------------------------------------------ 三点菜单

    /**
     * 判定「这个 PopupWindow 是不是策略详情页的三点菜单」的锚点控件。
     *
     * 用「删除策略」而不是父容器：`PopupWindow` 是系统类，App 里到处都在弹，
     * 只有内容视图里**同时**找得到 `tv_del_stg` 与 `tv_edit_name` 的才是目标菜单。
     */
    const val MENU_ANCHOR_ID = "tv_del_stg"

    /** 菜单里「修改名称」——新项插在它**下方** */
    const val MENU_EDIT_NAME_ID = "tv_edit_name"

    /** 菜单里两个 TextView 的父容器（垂直 LinearLayout） */
    const val MENU_ITEM_TEXT = "修改名称"

    // ------------------------------------------------------------------ 筛选面板

    /** 面板布局名（注意原包拼写） */
    const val LAYOUT_FILTER_PANEL = "dialog_marketfliter"

    /** 面板里每一行的布局名 */
    const val LAYOUT_FILTER_ITEM = "item_market_filter"

    const val ID_PANEL_TITLE = "tv1"
    const val ID_PANEL_SUBTITLE = "tv2"
    const val ID_PANEL_SECTION = "tv3"
    const val ID_PANEL_LIST = "rv_markets"
    const val ID_PANEL_CANCEL = "tv_cancel"
    const val ID_PANEL_OK = "tv_ok"

    const val ID_ITEM_ROOT = "itemroot"
    const val ID_ITEM_NAME = "name"
    const val ID_ITEM_CHECK = "cb_checked"

    /** 面板窗口：原版从屏幕底部往上弹出，这里保持一致 */
    const val PANEL_DIM = 0.5f

    // ------------------------------------------------------------------ 策略股票列表

    /** 策略详情页承载股票行的 ListView（实测 id = `lvStks`，与多选全选功能同一个） */
    val LIST_ID_CANDIDATES = arrayOf("lvStks")

    /** 行内「涨跌幅」TextView（如 `+9.99%` / `-1.45%`），用来判定涨幅 / 跌幅 */
    val PERCENT_ID_CANDIDATES = arrayOf("tv_item_stk_percent")

    /** 策略详情页的股票适配器（双保险：挂基类 + 挂它自己） */
    val TARGET_ADAPTERS = arrayOf(
        "com.guosen.quotation.adapter.aistk.AIStkAdapter",
    )

    /** 国信自己的列表基类（与原「自选高亮」同一个挂点） */
    const val CLS_BASE_QUICK_ADAPTER = "com.guosen.commonui.adapter.BaseQuickAdapter"

    /** 数据项里「代码.市场」字段候选（与原「自选高亮」共用同一套） */
    val CAM_FIELDS = arrayOf("codeAndMarket", "m_codeAndMarket")
    val CODE_FIELDS = arrayOf("m_stkCode", "code", "mCode")
    val MARKET_FIELDS = arrayOf("m_stkMarket", "market", "mMarket")

    /** 列表数据项的类（实测：`AIStkAdapter#convert(BaseAdapterHelper, StockInfo)`） */
    const val CLS_STOCK_INFO = "com.guosen.android.quotes.data.StockInfo"

    /**
     * 数据项里「涨跌幅」字段候选。
     *
     * `zdf` 是探针 `dumpClassMembers` 打出来的**真实字段名**（`BigDecimal zdf`），
     * 其余是 App 升级后的候选兜底；都读不到再退回解析行内文案。
     */
    val PERCENT_FIELDS = arrayOf(
        "zdf", "m_stkZdf", "mStkZdf", "m_zdf", "m_stkPercent", "mPercent", "percent",
        "m_changePercent", "changePercent", "m_stkChangeRate", "m_stkZdfRate",
    )

    fun isWatchActivity(className: String): Boolean = WATCH_ACTIVITIES.any { it == className }

    fun isTargetAdapter(className: String): Boolean = TARGET_ADAPTERS.any { it == className }
}
