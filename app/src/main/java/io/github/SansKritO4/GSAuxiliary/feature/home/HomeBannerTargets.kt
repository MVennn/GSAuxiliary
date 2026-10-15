package io.github.SansKritO4.GSAuxiliary.feature.home

import io.github.SansKritO4.GSAuxiliary.core.TargetApp

/**
 * 「首页」的定位常量。
 *
 * ## 一、8.6.0 真机实测：首页那一块长什么样
 *
 * ```
 * CollapsingToolbarLayout #collapsing_layout
 *   LinearLayout #ll_top
 *     LinearLayout                          卡片
 *       RelativeLayout #rltopimg            （顶部背景图，两个 ImageView 都是 GONE）
 *       RecyclerView   #rv_home_func        0,0-1080,328     ← 快捷入口网格（5 列 × 2 行）
 *         FrameLayout                       RecyclerView 的每一格
 *           com.guosen.commonui.BubbleTextView #home_func_icon   0,0-216,150  自选 / 我的持仓 / …
 *           com.guosen.commonui.ShimmerView    #home_func_shimmer（占位骨架，GONE）
 *       ConstraintLayout #layout_banner     39,354-1041,549  ← 要藏的那条横幅，1002×195
 *         RecyclingViewPager #vp_ad              0,0-1002,195
 *           FrameLayout（第 1 页）
 *             AppCompatImageView #iv_item_ad
 *             AppCompatTextView  #tv_item_ad_title_top
 *             AppCompatTextView  #tv_item_ad_title_bottom
 *           FrameLayout（第 2 / 3 页，位置在容器右边外面）
 *         RecyclingCirclePageIndicator #indicator_ad   894,169-963,182
 * ```
 *
 * 三条实测结论，直接决定了下面的实现：
 *
 *  1. `#layout_banner` **就是**快捷入口正下方那条横幅，而且和 `#rv_home_func`
 *     **是同一个父容器的相邻兄弟** —— 所以「快捷入口当锚点、取它后面第一个像横幅的兄弟」
 *     这套结构判据在真机上是成立的（爬 0 层就够，[MAX_HOPS] 留的余量很足）。
 *  2. 快捷入口的每格是 **`BubbleTextView`（用 `drawableTop` 画图标，不是 `ImageView`）**
 *     —— `UiClean.hasIcon` 里那条「`TextView` 带 `compoundDrawables[1]`」的分支就是为它准备的。
 *  3. 广告位是 **`ViewPager`**，离屏那两页**同样是 `VISIBLE`**，里面的标题文案
 *     也会被 `findAll` 数到。所以 [BANNER_MAX_LABELS] 这个「文案不超过 2 条」的判据
 *     必须**只数落在横幅自己范围内的文案**（见 `HomeBannerFeature.looksLikeBanner`），
 *     否则真机上一定判不中。
 *
 * ## 二、怎么认（两级兜底）
 *
 *  1. **资源 id 候选**（[BANNER_ID_CANDIDATES]）—— 命中就直取，最快最准；
 *  2. **锚点 + 结构**：
 *     a. 先认「快捷入口网格」：可见、子项 ≥ [MIN_ENTRY_ITEMS]、
 *        每项都是「图标 + 文字」、整块不高（[MAX_ENTRY_GRID_HEIGHT_DP]）；
 *     b. 再从这个网格**往上爬 1~3 层**，每层取「网格后面第一个可见兄弟」，
 *        谁长得像横幅（`looksLikeBanner`）就藏谁。
 *
 * 「往上爬」是必须的：网格常常被包在一张卡片里，横幅是**卡片外面的下一个兄弟**，
 * 只认「网格的直接兄弟」会一个都找不到。
 *
 * ## 三、只有一个 id 分不出来：行情市场页复用了 `rv_home_func`
 *
 * 行情页的 ETF 子页也用 `#rv_home_func` 装「图标 + 文字」的快捷入口，
 * 但那一页的格子是 `#iv_home_func` + `#tv_home_func`（`ConstraintLayout`），
 * 和首页的 `#home_func_icon`（`BubbleTextView`）不是一回事。
 * 所以认网格时会用 [HOME_ENTRY_ITEM_ID] 再确认一次 —— 否则锚点法会在行情页
 * 命中它自己的广告位，把不属于本功能的东西藏掉。
 *
 * ## 四、横幅的判据
 *
 *  - 高度落在 [BANNER_MIN_HEIGHT_DP] ~ [BANNER_MAX_HEIGHT_DP] 之间
 *    （实测 195px，本机 416dpi 下约 75dp，区间中段）；
 *  - 宽度至少占父容器 60%（实测 1002 / 1080 = 92.8%）；
 *  - 里面有图标（`ImageView`）；
 *  - **范围内的**有效文案不超过 2 条（横幅最多一个标题 + 一个副标题 / 按钮，文案一多就是内容区）。
 *
 * 这套判据是**保守**的：宁可认不出来（什么都不做），也不要把内容区藏掉。
 * 认不出来时模块会打一份视图树快照，照着往候选数组里加 id 即可。
 */
object HomeBannerTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /**
     * 首页与行情页同属主界面 `MainAct`（靠 Tab 切换），所以页面门禁是同一个类。
     * 行情页不误伤靠的是 [HOME_ENTRY_ITEM_ID] 那道确认，见类注释第三节。
     */
    val PAGES = arrayOf(
        "com.guosen.app.goldsun.ui.MainAct",
    )

    /**
     * 横幅容器（候选；拿不到就走结构判据）。
     *
     * `layout_banner` 是 8.6.0 实测到的名字，排在最前；其余是按命名习惯猜的兜底。
     */
    val BANNER_ID_CANDIDATES = arrayOf(
        "layout_banner",
        "ll_home_banner", "iv_home_banner", "home_banner", "banner_home",
        "ll_banner", "iv_banner", "rl_banner", "fl_banner", "view_banner",
        "vp_banner", "banner_view", "ll_ad_banner", "ll_home_ad",
    )

    /**
     * 快捷入口网格（候选）。
     *
     * `rv_home_func` 是 8.6.0 实测到的名字（首页与行情页 ETF 子页共用），排在最前。
     */
    val QUICK_ENTRY_ID_CANDIDATES = arrayOf(
        "rv_home_func",
        "gv_home_menu", "grid_home_menu", "rv_home_menu", "ll_home_menu",
        "gv_menu", "home_menu", "ll_quick_entry", "ll_home_quick",
        "grid_menu", "rv_home_quick_entry", "ll_quick_entries", "ll_home_entries",
    )

    /**
     * 首页快捷入口**每一格**的 id。
     *
     * 用来把首页的入口网格和行情页 ETF 子页复用同名 `rv_home_func` 的那一个区分开
     * （那边是 `iv_home_func` + `tv_home_func`）。解析不到这个 id 时不做区分，
     * 照旧按结构判据走 —— 宿主改版后不能因为一个辅助 id 失效就整个功能瘫掉。
     */
    const val HOME_ENTRY_ITEM_ID = "home_func_icon"

    /** 至少这么多格才算「快捷入口」 */
    const val MIN_ENTRY_ITEMS = 6

    /**
     * 快捷入口整块的高度上限（dp）：比这高说明认到的是内容区，不是入口网格。
     *
     * 8.6.0 实测整块 328px，本机 416dpi 下约 126dp（5 列 × 2 行），离上限还有余量。
     */
    const val MAX_ENTRY_GRID_HEIGHT_DP = 240

    /** 从网格往上爬几层找「横幅」——实测只差一个兄弟，1 层就够，这里留足余量 */
    const val MAX_HOPS = 3

    /** 横幅的高度区间（dp）——实测约 75dp */
    const val BANNER_MIN_HEIGHT_DP = 40
    const val BANNER_MAX_HEIGHT_DP = 200

    /** 横幅**范围内**最多允许几条文案 */
    const val BANNER_MAX_LABELS = 2
}
