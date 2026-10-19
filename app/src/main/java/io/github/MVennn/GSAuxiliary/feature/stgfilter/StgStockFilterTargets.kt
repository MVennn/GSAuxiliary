package io.github.MVennn.GSAuxiliary.feature.stgfilter

import io.github.MVennn.GSAuxiliary.core.TargetApp
import io.github.MVennn.GSAuxiliary.feature.filter.StockTypes

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

    // 面板布局 / 控件 id 不在这里了 —— 行情页那颗漏斗点出来的是**同一套布局**，
    // 常量已收进 `feature/filter/FilterPanelIds`（策略面板与行情面板共用一份，
    // 见 docs/FEATURE-STG-FILTER.md §四）。

    // ------------------------------------------------------------------ 策略股票列表

    /** 策略详情页承载股票行的 ListView（实测 id = `lvStks`，与多选全选功能同一个） */
    val LIST_ID_CANDIDATES = arrayOf("lvStks")

    /**
     * 策略详情页的布局文件（实测 `res/layout/act_ai_stk_stg_detail.xml`）。
     *
     * 根是竖直 `LinearLayout`，四个孩子：
     * `#common_top`（标题栏）/ `#loading`（加载遮罩，GONE）/ `#lvStks`（`0dp + weight=1`）/ `#ll_bottom`。
     *
     * ⚠️ **列表的 header 里装着整页的「头」** —— 策略名称、涨家数、标签、
     * 「策略个股N只 · 勾选加入自选股」全在里面（实测 header 高 640px）。
     * 所以任何操作都**不能把 `#lvStks` 收起来**，否则整页的头一起消失。
     */
    const val LAYOUT_DETAIL = "act_ai_stk_stg_detail"

    /** 行内「涨跌幅」TextView（如 `+9.99%` / `-1.45%`），用来判定涨 / 跌 */
    val PERCENT_ID_CANDIDATES = arrayOf("tv_item_stk_percent")

    /**
     * 列表的列标题行（`名称` / `最新价` / `涨跌幅`），实测 id = `ll_top`，
     * 在 ListView header 的**末尾**（`0,590-1080,640`，正好压在 header 下沿）。
     *
     * 宿主自己**在列表为空时也把它收起来** —— `AIStkChooseDetailAct`（选股详情，0 只股票）
     * 实测 `#ll_top` 为 `GONE`。我们跟着做。
     */
    const val COL_TITLE_ID = "ll_top"

    /**
     * 「说明及风险提示」那块 —— 它挂在 ListView 上（实测是 `#lvStks` 的第二个孩子、
     * 高 248px），而且**没有 id**，只能按文案前缀认。
     *
     * ⚠️ 它在 ListView 里属于固定视图（header / footer），`GONE` **不会塌陷**
     * （原因见 `docs/FEATURE-STG-FILTER.md` 的「走过的弯路」），
     * 所以要收掉只能 `removeFooterView` / `removeHeaderView` 摘下来。
     */
    const val FOOTER_TEXT_HINT = "说明及风险提示"

    /**
     * 左上角那颗「策略个股8只」计数（实测 id = `tv_stg_res_num`，在多选栏那一行）。
     *
     * 宿主只写**全量**、筛选后不会变，所以由模块改写为「策略个股 保留数/总数」。
     */
    val RES_NUM_ID_CANDIDATES = arrayOf("tv_stg_res_num")

    /**
     * 宿主自己的**无股票页面** —— 筛选后一只不剩时把它摆到**股票行**那块（列表 header 不动）。
     *
     * 资源名 `res/layout/empty_ai_stk.xml`，实测（`aapt2 dump xmltree`，8.6.0 vc271）内容就一个
     * `TextView`：
     *
     * ```xml
     * TextView  layout_width=match_parent  layout_height=match_parent
     *           gravity=center_horizontal
     *           paddingTop=23dp  paddingBottom=25dp
     *           text="未选出符合条件的股票"      ← 文案是**布局里内联的**，不在 resources.arsc 里
     *           drawableTop=@drawable/…（一个灰色空态图标）
     * ```
     *
     * 为什么能确定是它：全仓 3232 个 layout 里，只有这一个含「未选出符合条件的股票」这句话；
     * 而 `aapt2 dump resources` 里找不到任何引用它的布局 —— 说明它是**代码里 inflate** 的，
     * 所以它在策略详情页的视图树里查不到（那页的布局 `act_ai_stk_stg_detail.xml` 只有
     * `#common_top` / `#loading` / `#lvStks` / `#ll_bottom` 四块，压根没有空态）。
     */
    const val LAYOUT_EMPTY = "empty_ai_stk"

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

    /** 数据项里「股票名称」字段候选 —— 2.18.0 起判「ST股」用（名称含 ST） */
    val NAME_FIELDS = arrayOf("stockName", "m_stkName", "name", "m_name")

    /**
     * 数据项里的**类型布尔字段**（2.18.0 起「股票类型」筛选用）。
     *
     * 字段名来自探针 `dumpClassMembers(StockInfo)`（8.6.0 / vc271）：
     * `isCyb` / `isKCPlate` / `isXXLD`。前三者是布尔，值为 true 即认定该类型
     * （false 不代表「不是」，所以还要与代码前缀一起判 —— 见 `StockTypes`）。
     *
     * ⚠️ `isXXLD` 的语义（未开板次新）只做过**字段名**层面的确认，取值未核对；
     * 认不出就不把股票归到「未开板次新」，不会误伤别的股票。
     */
    val TYPE_FLAG_FIELDS = arrayOf(
        "isCyb" to StockTypes.CYB,
        "isKCPlate" to StockTypes.KCB,
        "isXXLD" to StockTypes.WKB,
    )

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
