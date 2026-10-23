package io.github.MVennn.GSAuxiliary.feature.zxcopy

import io.github.MVennn.GSAuxiliary.core.TargetApp

/**
 * 「自选分组复制」认宿主用的定位常量。
 *
 * 全部来自 2026-10-23 真机实测（魅族 20 / Android 16 / 金太阳 8.6.0 vc271），
 * 详见 `.work/notes/plan-zx-copy-2026-10-23.md`。
 *
 * ## 为什么按钮不能全局按 id 找
 *
 * `#iv_top_manager` 在自选页那棵树里有 **3 个实例**：
 *  - 自选页顶栏的铅笔（本次要换的那颗，在 `#common_top` 里）；
 *  - 行情页顶栏的同名图标（`GONE`）；
 *  - 自选分组行右侧的「≡」（在 `#ll_zx_groups` 里）。
 *
 * 所以定位顺序必须是：先用自选页独有的 `#indicator_zx` 认出页面 → 上溯到
 * `#common_top` → 再在它内部取 `#iv_top_manager`。见 [ZxGroupCopyFeature]。
 */
object ZxGroupCopyTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /** 自选页挂在主界面 Activity 里（底部 tab 切换不换 Activity） */
    val PAGES = arrayOf(
        "com.guosen.app.goldsun.ui.MainAct",
    )

    /** 自选页顶栏的「自选」MagicIndicator —— 本页独有，用来确认这是自选页 */
    const val ZX_INDICATOR_ID = "indicator_zx"

    /** 自选页顶栏容器（行情页也有同名 id，所以必须先认 [ZX_INDICATOR_ID]） */
    const val TOP_BAR_ID = "common_top"

    /** 搜索图标左边的铅笔「编辑」按钮；本次替换成复制按钮 */
    const val EDIT_BUTTON_ID = "iv_top_manager"

    /** 分组 tab 的 MagicIndicator（全部 / 自选 / 持仓股） */
    const val GROUP_INDICATOR_ID = "indicator_zx_group"

    /** MagicIndicator 里放标题的 LinearLayout（子项顺序 = 分组顺序） */
    const val TITLE_CONTAINER_ID = "title_container"

    /** 分组页的 ViewPager（每页一套股票列表） */
    const val PAGER_ID = "vp_zx_group"

    /** 每页里承载股票列表的自定义容器（**本身不是 ListView**，内部才是） */
    const val LIST_CONTAINER_ID = "stklv_zxg"

    /** 行内名称 / 代码 TextView（条目字段读不到时按行文本兜底） */
    const val NAME_ID = "tv_item_stk_name"
    const val CODE_ID = "tv_item_stk_code"

    /** 复制按钮的无障碍描述（只进目标进程，硬编码中文） */
    const val COPY_DESC = "复制分组股票"

    /** 剪贴板条目标签 */
    const val CLIP_LABEL = "自选分组"
}
