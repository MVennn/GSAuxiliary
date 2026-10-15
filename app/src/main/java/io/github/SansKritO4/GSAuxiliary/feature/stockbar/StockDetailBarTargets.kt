package io.github.SansKritO4.GSAuxiliary.feature.stockbar

import io.github.SansKritO4.GSAuxiliary.core.TargetApp

/**
 * 「个股详情页底栏 · 智能盯盘快捷入口」的定位常量。
 *
 * **全部来自真机实测**（金太阳 8.6.0 / vc271，魅族 20 / Android 16 / 1080×2400 / density 416），
 * 依据是 `uiautomator dump` 的控件树 + LSPosed 运行时探针日志，不是推断。
 *
 * ## 一、底栏是「自适应」的，有两套形态
 *
 * ```
 * LinearLayout #stk_detial_bottom_bar [0,2205][1080,2335]
 *   ├─ ImageView （1px 分隔线）
 *   ├─ FrameLayout #ll_bottom_stk                     ← 个股形态；指数形态下 GONE
 *   │    └─ ConstraintLayout #ll_bottom_stk_normal     ★ 名字叫 ll_，其实是 ConstraintLayout
 *   │         ├─ FrameLayout #fl_buy           clk  链成员·带权重  └ TextView #tv_bottom_buy        "买"
 *   │         ├─ FrameLayout #fl_sell          clk  链成员·带权重  └ TextView #tv_bottom_sell       "卖"
 *   │         ├─ TextView    #tv_bottom_chedan     clk  链成员·固定宽  "撤单"
 *   │         ├─ TextView    #tv_bottom_zx         clk  链成员·固定宽  "+自选"
 *   │         ├─ FrameLayout #fl_more          链成员·固定宽
 *   │         │    └ TextView #iv_bottom_more      clk            "更多"
 *   │         ├─ FrameLayout #fl_bottom_buy_index clk 链成员（指数形态；个股形态下 GONE）
 *   │         ├─ TextView    #tv_bottom_index_change_zx clk 固定宽 "+自选"（指数形态）
 *   │         └─ FrameLayout #fl_bottom_index_more 固定宽（指数形态）
 *   │              └ TextView #iv_bottom_index_more  clk          "更多"
 *   └─ LinearLayout #ll_bottom_index                  ← 指数 / 板块形态；个股形态下 GONE
 * ```
 *
 * ⚠️ **两套形态的按钮在同一个 `ConstraintLayout` 里，靠 visibility 互斥**
 * （另一套是 `GONE`，所以 `uiautomator dump` 里看不到）。因此：
 *  - 找「更多」要**取当前显示的那个**（`isShown`）；
 *  - `uiautomator` 会把 `ConstraintLayout` 报成 `ViewGroup`，别信它的类名，
 *    以运行期日志打出来的类名为准。
 *
 * 所以**不能写死「买 / 卖」两个位置**，只能锚在两种形态都有的「更多」上。
 *
 * ## 二、实测：买 / 卖 是带权重的链成员，其余三个是固定宽
 *
 * 把屏宽从 1080 改到 900（`wm size 900x2400`）后重新 dump：
 *
 * | 控件 | 1080 宽 | 900 宽 | 结论 |
 * |---|---|---|---|
 * | `#fl_buy` | 321 px | 231 px | **带权重**（跟着链上剩余空间走） |
 * | `#fl_sell` | 320 px | 230 px | **带权重** |
 * | `#tv_bottom_chedan` | 96 px | 96 px | 固定宽（≈37dp） |
 * | `#tv_bottom_zx` | 96 px | 96 px | 固定宽 |
 * | `#fl_more` | 96 px | 96 px | 固定宽 |
 *
 * ⚠️ **「往这一行 addView 一个新按钮，买卖就会自动让位」是错的。**
 * `ConstraintLayout` 里**不带约束的子视图不参与链的测量**：实测直接 addView 后，
 * 新按钮被丢在容器左上角（`[0,2216][124,2326]`），买卖一个像素都没让。
 *
 * 「克隆约束 + 改接前后邻居」也能跑，但**极脆**：实测只要有一处约束没接上，
 * 整条链就散架（买卖被压成 0 宽、新按钮和「更多」叠在一起）。
 *
 * **最终采用：不碰任何约束，只把「更多」那个盒子（`#fl_more`）加宽。**
 * 它本身就是链上的固定宽成员，加宽 → 链上剩余空间变少 → 买卖按权重自动各让一半。
 * 新按钮就并排塞进这个盒子里，见 `StockDetailBarFeature.buildInline`。
 *
 * 实测数字（1080 宽屏）：买 321→246、卖 320→245、`fl_more` 96→246
 * （246 = 我们的 124 + 间距 26 + 原来的 96）。
 *
 * ## 三、「更多」面板是 PopupWindow（不是 Dialog）
 *
 * `dumpsys window` 实测：`Window{... PopupWindow:xxxx}`，frame `[0,1724][1080,2335]`。
 *
 * ```
 * GridView #gv_stk_bottom
 *   └─ item: FrameLayout(clk) └ TextView #tv_item_gv_menu "智能盯盘"
 * ```
 *
 * ⚠️ **「智能盯盘」的位置随页面类型变化**，必须按文字找：
 *  - 个股页 7 项：条件单 / **智能盯盘(1)** / 智能K线 / 产业链 / 区间统计 / 交易明细 / 股票PK
 *  - 指数页 2 项：**智能盯盘(0)** / 区间统计
 *
 * ## 四、「智能盯盘」的落点（探针实测）
 *
 * ```
 * ========== LAUNCH ==========
 * 来自页面: com.guosen.quotation.ui.StockFragAct
 *   action = router_aistare_webview
 *   extras: url = "earlywarning/?userCode=<账号>&stockCode=000001.2&categoryType=2"
 *           node_level = 4
 *   stack: EngineFrag#startEngineActForResult:399 <- StockDetailFrag$34#onItemClick:6289
 * ```
 *
 * `url` 里带 **`userCode`（账号维度）**，自己拼 URL 不可靠也不合规，
 * 所以本功能**复用宿主自己的点击路径**：点「更多」→ 拦到面板 → 触发那一项的
 * `performItemClick`。宿主怎么发起的，我们就怎么发起，零猜测。
 */
object StockDetailBarTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /**
     * 承载底栏的页面。
     *
     * 实测个股与指数是**同一个 Activity**（`StockFragAct`），靠底栏内部的
     * 两套形态切换。
     *
     * ⚠️ **但分页器是「每一页各带一份底栏」**：同一个资源 id、多个不同实例
     * （实测一次 3 页就有 3 份 `#stk_detial_bottom_bar`，各带自己的
     * `#bottom_index_container`）。所以「找底栏 / 找更多 / 找盒子」**必须限定在
     * 那一条底栏范围内**，别用 `decorView.findViewById` —— 它永远只返回 DFS 里的第一份，
     * 一翻页就认错页，见 `StockDetailBarFeature.findBars` / `findMoreText` 与
     * `docs/FEATURE-STOCK-BAR.md` §8.12。
     *
     * 这里是**注入的门禁**：不在这张表里的页面一次同步都不跑。之所以敢拿类名当门禁，
     * 是因为 `StockFragAct` 是唯一承载底栏的页面；App 升级若改了类名，注入会静默失效，
     * 此时应连同下面的资源 id 一起重新侦察（见 `docs/FEATURE-STOCK-BAR.md` §7）。
     */
    val WATCH_ACTIVITIES = arrayOf(
        "com.guosen.quotation.ui.StockFragAct",
    )

    /** 底栏根容器（两种形态共用同一个根） */
    val BOTTOM_BAR_ID_CANDIDATES = arrayOf(
        "stk_detial_bottom_bar",
    )

    /** 「更多」按钮（`TextView`，图标在上文字在下）—— 注入锚点 */
    val MORE_TEXT_ID_CANDIDATES = arrayOf(
        "iv_bottom_more",
        "iv_bottom_index_more",
    )

    /** 「更多」按钮外层的 `FrameLayout` —— 新按钮插在它**前面** */
    val MORE_BOX_ID_CANDIDATES = arrayOf(
        "fl_more",
        "fl_bottom_index_more",
    )

    /** 「+自选」按钮 —— 新按钮的起点约束接在它后面（两种形态各一个） */
    val ZX_ID_CANDIDATES = arrayOf(
        "tv_bottom_zx",
        "tv_bottom_index_change_zx",
    )

    /** 「更多」面板里的 `GridView` */
    val MENU_GRID_ID_CANDIDATES = arrayOf(
        "gv_stk_bottom",
    )

    /** 「更多」面板每一项的文字控件 */
    val MENU_ITEM_TEXT_ID_CANDIDATES = arrayOf(
        "tv_item_gv_menu",
    )

    /** 底栏上「更多」按钮的文案（按文字兜底定位用） */
    const val MORE_TEXT = "更多"

    /** 「更多」面板里要提到外面来的那一项（按文字定位，位置不固定） */
    const val MENU_ITEM_TEXT = "智能盯盘"

    /** 注入按钮的文案 */
    const val ENTRY_TEXT = "智能盯盘"

    /** 注入按钮的 tag，用于幂等判断（同一行里只留一个） */
    const val ENTRY_TAG = "gsaux.stockbar.entry"

    /**
     * 我们那个「横向容器」的 tag（装 `[我们的按钮] [更多]`）。
     *
     * 必须能认出来：注入之后「更多」的**直接父级就是它**，所以
     * 「找盒子」不能只取 `more.parent` —— 那会取到我们自己套的壳，
     * 于是记账表里查不到、一层层往里再套（真机实测套了 5 层，底栏直接被撑爆）。
     * 见 `StockDetailBarFeature.boxOf` / `holderAncestorOf`。
     */
    const val ENTRY_HOLDER_TAG = "gsaux.stockbar.holder"

    fun isWatchActivity(className: String): Boolean = WATCH_ACTIVITIES.any { it == className }
}
