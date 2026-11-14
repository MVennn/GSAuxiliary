package io.github.MVennn.GSAuxiliary.feature.dock

import io.github.MVennn.GSAuxiliary.core.DockEntryStore
import io.github.MVennn.GSAuxiliary.core.TargetApp

/**
 * 「底栏」的定位常量。
 *
 * ## 一、8.6.0 真机实测的底栏长什么样
 *
 * ```
 * com.guosen.commonui.SingleGridLayout #fl_main_tab   0,2205-1080,2335   ← 整行 130px
 *   com.guosen.commonui.LottieTabView #cb_main_tab_home    0,0-180,130    首页
 *   ...                              #cb_main_tab_quotes 180,0-360,130    行情
 *   ...                              #cb_main_tab_zx     360,0-540,130    自选
 *   ...                              #cb_main_tab_trade  540,0-720,130    交易
 *   ...                              #cb_main_tab_money  720,0-900,130    理财 ← 要藏的那一格
 *   ...                              #cb_main_tab_mine   900,0-1080,130   账户
 * ```
 *
 * 每一格的内部结构是「图标 + 文字」：
 *
 * ```
 * LottieTabView
 *   LinearLayout                         内容容器
 *     FrameLayout
 *       ImageView                        图标（Lottie 动画视图与它叠着，默认 GONE）
 *     TextView                           文字（没有可用的资源 id）
 * ```
 *
 * 两条关键结论：
 *
 *  1. **根容器是宿主自己的 `SingleGridLayout`（等分栅格），不是 `LinearLayout`** ——
 *     所以「改 weight 均分」那条路在真机上根本走不到，去掉一格之后**要么**
 *     宿主自己按可见子项重算、**要么**留下空档，得量一下才知道（见
 *     `DockCleanFeature.fillRow`）。
 *  2. **文字 `TextView` 没有资源 id**，整格的 id 挂在外层 `LottieTabView` 上 ——
 *     所以「按文案找 → 上溯到直属于行的那一格」这一招是必需的，不能只靠 id。
 *
 * ## 二、六格与两级兜底
 *
 * 能藏哪几格由 [ENTRIES] 定（顺序与配置里的 key 顺序一致）；每一格都是两级兜底：
 *
 *  1. **那一格自己的候选 id**（[DockEntryTarget.idCandidates]）—— 命中直接藏，最快；
 *  2. **认行 + 按文案找**：先按 [DOCK_ID_CANDIDATES] 或结构判据（[BOTTOM_BAND_RATIO]、
 *     [MIN_ITEMS] / [MAX_ITEMS]、每格都是「图标 + 文字」）认出那一行，
 *     再在行里找文案为 [DockEntryTarget.label] 的格子。
 *
 * 为什么不按下标：不同账号 / 版本底栏项数不一样（有的没有「交易」、有的多一个「基金」），
 * 下标一错就藏错东西。
 *
 * [REMOVE_ITEM_ID_CANDIDATES] / [REMOVE_TEXT] 是升级前「只藏理财」那一版的常量，
 * 现在作为 [ENTRIES] 里「理财」那一格的数据继续用。
 *
 * ## 三、认不出来时
 *
 * 两条路都走不通时，模块会在日志里打一份**当前页面的视图树快照**
 * （见 `UiCleanFeature.maybeReport` / `UiClean.dump`），照着把真实 id 名
 * 加进候选数组即可，不需要 adb。完整步骤见 `docs/FEATURE-UI-CLEAN.md` §4。
 */
object DockCleanTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /**
     * 承载底栏的页面。
     *
     * `com.guosen.app.goldsun.ui.MainAct` 来自 `AndroidManifest` 明文解析
     * （见 `docs/reference/apk-analysis-report.md`），是**确定**的主界面类名，
     * 也已在 8.6.0 真机上核对过。
     */
    val PAGES = arrayOf(
        "com.guosen.app.goldsun.ui.MainAct",
    )

    /**
     * 底栏根容器（候选；拿不到就走结构判据）。
     *
     * `fl_main_tab` 是 8.6.0 实测到的名字，排在最前；其余是按命名习惯猜的兜底。
     */
    val DOCK_ID_CANDIDATES = arrayOf(
        "fl_main_tab",
        "ll_bottom", "ll_bottom_bar", "bottom_bar", "ll_main_bottom", "main_bottom",
        "ll_tab", "ll_tab_bar", "tab_bar", "bottom_nav", "ll_bottom_nav",
        "ll_main_tab", "bottom_tab", "ll_main_bottom_bar", "ll_navigation",
    )

    /**
     * 「理财」那一格自己的 id（候选）。
     *
     * 8.6.0 实测是 `cb_main_tab_money`，同排其余五格分别是
     * `cb_main_tab_home` / `_quotes` / `_zx` / `_trade` / `_mine`。
     * 命中就直接藏那一格，省掉「先认行、再按文案找」两步。
     *
     * 但**文案兜底必须留着**：不同账号 / 版本底栏项不一样，id 也会跟着变。
     */
    val REMOVE_ITEM_ID_CANDIDATES = arrayOf(
        "cb_main_tab_money",
    )

    /** 要移除的那一格文案 */
    const val REMOVE_TEXT = "理财"

    /**
     * 底栏里的一格。
     *
     * [key] 与 `core/DockEntryStore.kt` 的 `KEYS` 一一对应（配置里存的就是它），
     * [label] 是那一格的文案 —— **只在候选 id 全部落空时**用它找格子
     * （实测文字 `TextView` 没有资源 id）。
     */
    class DockEntryTarget(
        val key: String,
        val label: String,
        val idCandidates: Array<String>,
    )

    /**
     * 底栏默认的六格，顺序与 `DockEntryStore.KEYS` 一致（界面行序、编码顺序都靠它）。
     *
     * 六格的 id 都是 8.6.0 真机实测值；「理财」复用升级前的
     * [REMOVE_ITEM_ID_CANDIDATES]，好让旧开关的迁移路径与老代码走同一套定位。
     */
    val ENTRIES = arrayOf(
        DockEntryTarget(DockEntryStore.KEY_HOME, "首页", arrayOf("cb_main_tab_home")),
        DockEntryTarget(DockEntryStore.KEY_QUOTES, "行情", arrayOf("cb_main_tab_quotes")),
        DockEntryTarget(DockEntryStore.KEY_ZX, "自选", arrayOf("cb_main_tab_zx")),
        DockEntryTarget(DockEntryStore.KEY_TRADE, "交易", arrayOf("cb_main_tab_trade")),
        DockEntryTarget(DockEntryStore.KEY_MONEY, REMOVE_TEXT, REMOVE_ITEM_ID_CANDIDATES),
        DockEntryTarget(DockEntryStore.KEY_MINE, "账户", arrayOf("cb_main_tab_mine")),
    )

    /** 按 key 取文案（日志用；认不出来就原样打 key） */
    fun labelOf(key: String): String = ENTRIES.firstOrNull { it.key == key }?.label ?: key

    /** 一行至少 / 至多几个格子才算底栏 */
    const val MIN_ITEMS = 3
    const val MAX_ITEMS = 6

    /**
     * 底栏整行的高度上限（dp）——比这高的不是底栏。
     *
     * 8.6.0 实测整行 130px；本机 416dpi（`density = 2.6`）下约 50dp，离上限还很远。
     */
    const val MAX_BAR_HEIGHT_DP = 110

    /**
     * 底栏的顶边必须落在屏幕高度的这个比例以下。
     *
     * 8.6.0 实测：`decorView` 高 2400、底栏顶边 2205 → 0.919，判据留了足够余量。
     */
    const val BOTTOM_BAND_RATIO = 0.75f

    /** 其余格子是否重新「均分铺满」（见 `DockCleanFeature.fillRow`） */
    const val REFLOW_SIBLINGS = true
}
