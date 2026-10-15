package io.github.SansKritO4.GSAuxiliary.feature.home

import io.github.SansKritO4.GSAuxiliary.core.TargetApp

/**
 * 「首页」的定位常量。
 *
 * ## 目标
 *
 * 首页**快捷入口**（一排/两排「图标 + 文字」的小格子）**下方的那条横幅**。
 *
 * ## 怎么认（两级兜底）
 *
 *  1. **资源 id 候选**（[BANNER_ID_CANDIDATES]）——命中就直取，最快最准；
 *  2. **锚点 + 结构**：
 *     a. 先认「快捷入口网格」：可见、子项 ≥ [MIN_ENTRY_ITEMS]、
 *        每项都是「图标 + 文字」、整块不高（[MAX_ENTRY_GRID_HEIGHT_DP]）；
 *     b. 再从这个网格**往上爬 1~3 层**，每层取「网格后面第一个可见兄弟」，
 *        谁长得像横幅（[looksLikeBanner]）就藏谁。
 *
 * 「往上爬」是必须的：网格常常被包在一张卡片里，横幅是**卡片外面的下一个兄弟**，
 * 只认「网格的直接兄弟」会一个都找不到。
 *
 * ## 「横幅」的判据（[looksLikeBanner]）
 *
 *  - 高度落在 [BANNER_MIN_HEIGHT_DP] ~ [BANNER_MAX_HEIGHT_DP] 之间；
 *  - 宽度至少占父容器 60%；
 *  - 里面有图标（`ImageView`）；
 *  - 里面的**有效文案不超过 2 条**（横幅最多一个标题 + 一个按钮，
 *    文案一多就不是横幅而是内容区）。
 *
 * 这套判据是**保守**的：宁可认不出来（什么都不做），也不要把内容区藏掉。
 * 认不出来时模块会打一份视图树快照，照着往候选数组里加 id 即可。
 */
object HomeBannerTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /** 首页与行情页同属主界面 `MainAct`（靠 Tab 切换），所以页面门禁是同一个类 */
    val PAGES = arrayOf(
        "com.guosen.app.goldsun.ui.MainAct",
    )

    /** 横幅容器（候选；拿不到就走结构判据） */
    val BANNER_ID_CANDIDATES = arrayOf(
        "ll_home_banner", "iv_home_banner", "home_banner", "banner_home",
        "ll_banner", "iv_banner", "rl_banner", "fl_banner", "view_banner",
        "vp_banner", "banner_view", "ll_ad_banner", "ll_home_ad",
    )

    /** 快捷入口网格（候选） */
    val QUICK_ENTRY_ID_CANDIDATES = arrayOf(
        "gv_home_menu", "grid_home_menu", "rv_home_menu", "ll_home_menu",
        "gv_menu", "home_menu", "ll_quick_entry", "ll_home_quick",
        "grid_menu", "rv_home_quick_entry", "ll_quick_entries", "ll_home_entries",
    )

    /** 至少这么多格才算「快捷入口」 */
    const val MIN_ENTRY_ITEMS = 6

    /** 快捷入口整块的高度上限（dp）：比这高说明认到的是内容区，不是入口网格 */
    const val MAX_ENTRY_GRID_HEIGHT_DP = 240

    /** 从网格往上爬几层找「横幅」 */
    const val MAX_HOPS = 3

    /** 横幅的高度区间（dp） */
    const val BANNER_MIN_HEIGHT_DP = 40
    const val BANNER_MAX_HEIGHT_DP = 200

    /** 横幅里最多允许几条文案 */
    const val BANNER_MAX_LABELS = 2
}
