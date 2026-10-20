package io.github.MVennn.GSAuxiliary.feature.market

import io.github.MVennn.GSAuxiliary.core.TargetApp
import android.view.View
import io.github.MVennn.GSAuxiliary.feature.uiclean.IdCache
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiClean
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「行情市场」三个功能共用的定位常量。
 *
 * ## 一、8.6.0 真机实测：行情 tab（市场 → A股）顶部长什么样
 *
 * 这一页整体是**一个横向 `RecyclerView`**（`#rv_markets_hs`，每个版块是一个子项，
 * 纵向排列），自上而下：
 *
 * ```
 * ConstraintLayout #container_a_stock                      ← 行情 tab 的 A股 页
 *   RadioGroup #rg_a_stock          沪深京 / 创业板 / 科创板 / 北交所
 *   LockViewPager #vp_a_stock       0,102-1080,1883
 *     PtrHQFrameLayout #ptr_markets_hs
 *       HorizontalRecyclerView #rv_markets_hs            ← 整页的纵向容器（每个版块 = 一个子项）
 *         [行1] LinearLayout  0,0-1080,229     指数概览
 *           RecyclerView #rv_index → LinearLayout #ll_content ×4（上证 / 深证 / 北证50 …）
 *         [行2] LinearLayout  0,229-1080,925    ★ 市场概览（696px）
 *           LinearLayout #line_content         标题「市场概览」+ 成交额
 *           LinearLayout                       三张指标卡 #ll_header_zdfb / _jlr / _dxp
 *           FrameLayout #fl_content   0,285-1080,696     图表区（411px）
 *             LinearLayout #ll_zdfb  0,0-1080,411        涨跌分布
 *               ZdfbChartView #zdfb_chart_view  0,0-1080,268   ← 柱状图（固定 268px）
 *               …涨 / 跌 比例条、涨停 / 跌停 行
 *             RelativeLayout #ll_dxpg    （大盘股图，GONE）
 *               DxpgChartView #dxpg_chart_view
 *             ConstraintLayout #ll_jlr   （净流入图，GONE）
 *               JlrChartView #jlr_chart_view
 *         [行3] LinearLayout  0,925-1080,1091   ★ 滚动横幅
 *           AdapterViewFlipper #avf_hotpoint  39,0-1041,140   ← 热门专题轮播（**不是**跑马灯）
 *         [行4] LinearLayout  0,1107-1080,1310  ★ 快捷入口
 *           RecyclerView #rv_entra  0,31-1080,166
 *             AppCompatTextView #item_marktes_entra ×5（股票PK / 主力建仓 / 百日新高 / 申购宝 / …）
 *         [行5] LinearLayout  0,1326-1080,1443  板块标题「板块」+「更多」
 *         [行6] LinearLayout  0,1443-1080,2002  ★ 板块
 *           RadioGroup #rg_bk              涨幅榜 / 涨速榜 / 成交额 / 连涨天 / 净流入
 *           LockViewPager #vp_bk_content  39,91-1041,559
 *             RecyclerView                        3 列 × 2 行 = 6 个板块
 *               BKHQOverviewItemView ×6（264×234 一格）
 *                 #tv_item_bkhq_overview_name / _zdf / _stock
 * ```
 *
 * ## 二、三条实测结论（直接决定实现）
 *
 *  1. **滚动横幅是「热门专题轮播」，不是跑马灯** ——
 *     它是一个 `AdapterViewFlipper`（`#avf_hotpoint`），页面里**没有**任何
 *     `ellipsize = MARQUEE` 的 `TextView`（已用视图标志位扫过全树确认）。
 *     所以「找跑马灯文字」那条路在真机上永远认不出来，只能按 id。
 *  2. **四个目标版块都是 `#rv_markets_hs` 的子项** —— 隐藏它们必须
 *     `ViewPatcher.collapse`（高度归零 + `GONE`），只设 `GONE` 会被 `RecyclerView`
 *     的 `LayoutManager` 当成普通子项照样测一遍，留下一条等高空档。
 *  3. **板块是 3 列 × 2 行共 6 格**（每格 264×234），「只留三项」= 保一整行，
 *     所以对 [SECTOR_VIEWPAGER_ID] 限高到 `SECTOR_KEEP / SECTOR_TOTAL` 即可。
 *
 * ## 三、页面门禁
 *
 * 行情市场是主界面 `MainAct` 里的一个 Tab（Fragment），与首页 / 底栏同一个 Activity。
 * 但 `MarketOverviewAct`（「市场概览」独立页）**整页是 H5** ——
 * native 层只有 `common_top` + `webView_market`，大盘走势与涨跌分布都在网页里，
 * 「按块压矮」无从下手（模块不注入脚本），所以它不在 [PAGES] 里。
 *
 * 三个功能的锚点各自独立：某一个认不出来不会连累另外两个。
 */
object MarketTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /** 承载行情市场的页面 */
    val PAGES = arrayOf(
        "com.guosen.app.goldsun.ui.MainAct",
    )

    /**
     * 「行情市场页现在在屏幕上吗」的锚点（候选）。
     *
     * 三个功能都只作用在**行情 tab 顶部那几个版块**上，而首页、自选页也在同一个
     * `MainAct` 里 —— 没有这道门禁，首页上那些长得像的控件会被误认成目标。
     * 首轮真机测试就踩了这个坑：`market_overview_compact` 在首页把
     * `AppBarLayout` / `CollapsingToolbarLayout` / 快捷入口网格 / 广告图
     * 一共 8 个视图当成「概览图表」压了一遍，还压到了 `DecorView` 上。
     *
     * 认不到任何锚点时按「不在这一页」处理 —— 宁可什么都不做。
     */
    val PAGE_ANCHOR_ID_CANDIDATES = arrayOf(
        "rv_markets_hs",
        "ptr_markets_hs",
        "container_a_stock",
    )

    /**
     * 行情市场页是否在屏幕上。
     *
     * ## 为什么不是「三个候选各一次 `findViewById`」（2026-10-19 优化）
     *
     * 这道门禁跑在**每次布局回调**上，而三个功能各查一遍 —— 不在行情页时
     * 三个候选全部 miss，一次 `findViewById` 未命中就是**整棵 decor 的遍历**，
     * 于是 3 功能 × 3 候选 = **9 次全树遍历/回调**（首页滚动时每帧都付）。
     * 现在改成：
     *
     *  - **快路径**：上次命中的锚点还在树上且可见 → 直接判「在」（0 次遍历）。
     *    锚点随宿主重建自然失效（`isAttachedToWindow`），不依赖我们的记账；
     *  - **慢路径**：**一趟遍历同时判三个候选 id**（按 id 命中才查 `isShown`，
     *    避免对每个节点走一遍父链）。
     *
     * 判据与原先等价：任一锚点 id 对应的可见视图存在 ⇔ 行情市场页在屏幕上。
     * 比原先**略宽**的一点是「同 id 有多个视图时逐个判可见」（原先只判
     * `findViewById` 返回的第一个）—— 分页器每页各带一份视图时，这更贴近事实。
     */
    fun onMarketPage(root: View, ids: IdCache): Boolean {
        val cached = synchronized(anchors) { anchors[root]?.get() }
        if (cached != null && cached.isAttachedToWindow && cached.isShown) return true

        val want = IntArray(PAGE_ANCHOR_ID_CANDIDATES.size)
        for (i in want.indices) want[i] = ids.of(PAGE_ANCHOR_ID_CANDIDATES[i])

        var hit: View? = null
        UiClean.walk(root) { v ->
            val id = v.id
            if (id != 0 && want.contains(id) && v.isShown) {
                hit = v
                true
            } else {
                false
            }
        }
        val found = hit
        if (found == null) {
            synchronized(anchors) { anchors.remove(root) }
            return false
        }
        synchronized(anchors) { anchors[root] = WeakReference(found) }
        return true
    }

    /** `decorView -> 上次命中的锚点`（三个功能共享；弱引用键 + 弱引用值，见规则 1.4） */
    private val anchors =
        Collections.synchronizedMap(WeakHashMap<View, WeakReference<View>>())

    /** 整页的纵向容器：每个版块是它的一个子项（隐藏版块时要连子项一起处理） */
    const val PAGE_LIST_ID = "rv_markets_hs"

    // ------------------------------------------------------------ 功能 0：指数概览

    /**
     * 指数概览容器（候选）。
     *
     * 8.6.0 实测：**行1**（`rv_markets_hs` 的第 0 个子项，0,0-1080,229）里的
     * `RecyclerView #rv_index` → `LinearLayout #ll_content` ×4（上证 / 深证 / 北证50 …）。
     *
     * ⚠️ **候选名放的是 `#rv_index`（内层容器），不是行1 本身** ——
     * 行1 没有 id，只能靠「父是列表」往上爬（见 `MarketIndexHideFeature.itemOf`）。
     *
     * ⚠️ **来源只有一处，且未经二次核实**（2026-10-20 记）：
     * 唯一的依据是 `docs/FEATURE-UI-CLEAN.md` §7.5 的真机实测表
     * （「行1 = 0,0-1080,229，`rv_index` → `ll_content` ×4」）。
     * 手头的探针 dump（`com.wb.probe`）**只覆盖了个股详情页的 K 线区**，
     * 不含行情页 —— 所以这几个 id **没能二次核实**。
     * 装机后第一眼看这里：若功能不生效，先确认宿主当前的 id 是不是还叫这俩。
     */
    val INDEX_OVERVIEW_ID_CANDIDATES = arrayOf(
        "rv_index",
        "ll_index", "ll_index_content", "rv_index_content", "ll_market_index",
    )

    /**
     * 指数概览里每一项的 id（确认「这就是指数概览」，不是别的横向列表）。
     *
     * ⚠️ **只当「辅助确认」，不当必要条件** —— 它是个**通用名**，
     * 宿主改版改掉它的概率不低。拿它当必要条件就等于整个功能废掉
     * （认不出来 → 静默失效）。所以判据是「命中**或**这一项本身有文案」。
     *
     * 宿主里另有一个 `ll_content_ma`（K 线区的均线），**没有**裸的 `ll_content` ——
     * 撞名的风险不大，但这条判断的依据同样只有实测表。
     */
    const val INDEX_ITEM_ID = "ll_content"

    /** 指数概览至少这么多项才算它（实测 4 项：上证 / 深证 / 北证50 / …） */
    const val MIN_INDEX_ITEMS = 3

    /** 指数概览整块的高度上限（dp）—— 实测 229px（416dpi 下约 88dp），留余量 */
    const val MAX_INDEX_OVERVIEW_HEIGHT_DP = 140

    // ------------------------------------------------------------ 功能 1：滚动横幅 + 快捷入口

    /**
     * 滚动横幅（热门专题轮播）容器（候选）。
     *
     * 8.6.0 实测 `#avf_hotpoint`（`AdapterViewFlipper`，39,0-1041,140）。
     */
    val HOT_BAR_ID_CANDIDATES = arrayOf(
        "avf_hotpoint",
        "ll_marquee", "ll_notice", "ll_scroll_notice", "avf_notice", "ll_hotpoint",
    )

    /**
     * 快捷入口网格（候选）。
     *
     * 8.6.0 实测 `#rv_entra`，每格是 `#item_marktes_entra`（`TextView` + `drawableTop`）。
     */
    val QUICK_ENTRY_ID_CANDIDATES = arrayOf(
        "rv_entra",
        "gv_market_menu", "grid_market_menu", "ll_market_menu", "rv_market_menu",
        "ll_hq_menu", "gv_menu", "ll_quick_entry", "ll_market_quick",
    )

    /** 快捷入口每一格的 id（用来确认「这就是行情页那排入口」，不是别的网格） */
    const val QUICK_ENTRY_ITEM_ID = "item_marktes_entra"

    /** 至少这么多格才算「快捷入口」 */
    const val MIN_ENTRY_ITEMS = 4

    /** 快捷入口整块的高度上限（dp）——实测整块 135px（416dpi 下约 52dp） */
    const val MAX_ENTRY_GRID_HEIGHT_DP = 240

    /**
     * 轮播条的高度上限（dp）：认「条」时要求容器不超过这个高度。
     * 实测 140px（约 54dp），留一点余量。
     */
    const val HOT_BAR_MAX_HEIGHT_DP = 80

    // ------------------------------------------------------------ 功能 2：板块

    /**
     * 板块列表（候选）。
     *
     * 8.6.0 实测 `#vp_bk_content`（`LockViewPager`，39,91-1041,559）——
     * 里面每页是一个 `RecyclerView`，3 列 × 2 行共 6 个 `BKHQOverviewItemView`。
     */
    val SECTOR_GROUP_ID_CANDIDATES = arrayOf(
        "vp_bk_content",
        "ll_bk_list", "ll_sector_list", "ll_bk", "bk_list", "rv_bk", "gv_bk",
    )

    /** 板块每一项的 id（确认网格用） */
    const val SECTOR_ITEM_ID = "tv_item_bkhq_overview_name"

    /** 板块原本显示几项 */
    const val SECTOR_TOTAL = 6

    /** 压缩后保留几项 */
    const val SECTOR_KEEP = 3

    /** 板块项的宽度一致性容差（判定「等宽平铺」用） */
    const val SECTOR_WIDTH_TOLERANCE = 1.3f

    // ------------------------------------------------------------ 功能 3：市场概览

    /**
     * 概览图表区（候选）。
     *
     * 8.6.0 实测 `#fl_content`（`FrameLayout`，0,285-1080,696）——
     * 三张图（涨跌分布 / 大盘股 / 净流入）轮流占位，只有一张是可见的。
     */
    const val OVERVIEW_CHART_AREA_ID = "fl_content"

    /**
     * 三张概览图各自的视图 id（候选，取可见的那一张）。
     *
     * 都是宿主自绘的图表控件：`ZdfbChartView` / `DxpgChartView` / `JlrChartView`，
     * 实测涨跌分布那张固定 268px 高。压矮就是压它们。
     */
    val OVERVIEW_CHART_ID_CANDIDATES = arrayOf(
        "zdfb_chart_view", "dxpg_chart_view", "jlr_chart_view",
    )

    /**
     * 概览图表区高度压缩到原来的多少。
     *
     * 0.6 是「明显变矮、柱状图还看得清」的折中值。
     * **只压图表那一份**：区块里的标题行与三张指标卡装着两三行文字，
     * 一起等比压会把字裁掉 —— 所以整体压矮的幅度来自图表区，不是整块等比。
     * 想调手感就改这一个数，不用动逻辑。
     */
    const val OVERVIEW_HEIGHT_FACTOR = 0.6f
}
