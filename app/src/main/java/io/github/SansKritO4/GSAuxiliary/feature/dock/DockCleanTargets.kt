package io.github.SansKritO4.GSAuxiliary.feature.dock

import io.github.SansKritO4.GSAuxiliary.core.TargetApp

/**
 * 「底栏精简」的定位常量。
 *
 * ## 一、底栏长什么样
 *
 * 主界面（`MainAct`）底部是一行 **图标在上、文字在下** 的格子，
 * 典型是「首页 / 行情 / 交易 / 理财 / 我的」五格。
 * 这一行有两个稳定特征，本功能就靠它们认：
 *
 *  1. 位置：整行的顶边落在屏幕**底部 25%** 以内（[BOTTOM_BAND_RATIO]）；
 *  2. 结构：一行 3~6 个格子，每格都是「有图标 + 有文字」（[UiClean.looksLikeEntryItem]）。
 *
 * 认到行之后，**按文案**找「理财」那一格，把**那一格**（而不是整行、
 * 也不是只有文字）设成 `GONE` —— 图标和点击区一起消失，剩下的格子重新铺满。
 *
 * ## 二、为什么不写死资源 id
 *
 * 宿主被梆梆加固，静态反编译拿不到 `R.id`。候选数组里列的是**按命名习惯猜的**
 * 常见名字，命中就直取（最快最准）；命中不了就走上面的结构判据。
 *
 * 两条路都走不通时，模块会在日志里打一份**当前页面的视图树快照**
 * （见 `UiCleanFeature.reportMiss` / `UiClean.dump`），照着把真实 id 名
 * 加进 [DOCK_ID_CANDIDATES] 即可，不需要 adb。
 */
object DockCleanTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /**
     * 承载底栏的页面。
     *
     * `com.guosen.app.goldsun.ui.MainAct` 来自 `AndroidManifest` 明文解析
     * （见 `docs/reference/apk-analysis-report.md`），是**确定**的主界面类名。
     */
    val PAGES = arrayOf(
        "com.guosen.app.goldsun.ui.MainAct",
    )

    /** 底栏根容器（候选；拿不到就走结构判据） */
    val DOCK_ID_CANDIDATES = arrayOf(
        "ll_bottom", "ll_bottom_bar", "bottom_bar", "ll_main_bottom", "main_bottom",
        "ll_tab", "ll_tab_bar", "tab_bar", "bottom_nav", "ll_bottom_nav",
        "ll_main_tab", "bottom_tab", "ll_main_bottom_bar", "ll_navigation",
    )

    /** 要移除的那一格文案 */
    const val REMOVE_TEXT = "理财"

    /** 一行至少 / 至多几个格子才算底栏 */
    const val MIN_ITEMS = 3
    const val MAX_ITEMS = 6

    /** 底栏整行的高度上限（dp）——比这高的不是底栏 */
    const val MAX_BAR_HEIGHT_DP = 110

    /** 底栏的顶边必须落在屏幕高度的这个比例以下 */
    const val BOTTOM_BAND_RATIO = 0.75f

    /** 其余格子是否重新「均分铺满」（行是横向 `LinearLayout` 时才做） */
    const val REFLOW_SIBLINGS = true
}
