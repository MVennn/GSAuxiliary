package gsauxiliary.m.venn.feature.strategyselect

import gsauxiliary.m.venn.core.TargetApp

/**
 * 「策略详情页多选栏全选」的定位常量。
 *
 * **全部来自真机实测**（金太阳 8.6.0 / vc271，魅族 20 / Android 16），
 * 依据是 `uiautomator dump` 出的控件树，不是推断：
 *
 * ```
 * ListView #lvStks [0,230][1080,2205]
 *   └─ (header) LinearLayout [0,230][1080,820]
 *        └─ LinearLayout [0,648][1080,748]              ← 多选栏所在行
 *             ├─ TextView #tv_stg_res_num  "策略个股8只"
 *             ├─ TextView #tv_stg_res_time "(10-15 13:50:08)"
 *             └─ TextView #tv_stg_add_zxg  "取消"  [927,664][1041,732]   ← 目标按钮
 *   └─ (row) FrameLayout [0,820][1080,958]  ← 每只股票一行
 *        ├─ ImageView #iv_item_show_choose
 *        ├─ LinearLayout └─ TextView #tv_item_stk_name / #tv_item_stk_code
 *        ├─ TextView #tv_item_stk_price / #tv_item_stk_percent
 *        └─ CheckedTextView #ct_item  ← 勾选框（多选态可见）
 *
 * 底部条 LinearLayout #ll_bottom
 *   └─ LinearLayout #ll_add_zxg_bottom
 *        ├─ "已选 " / TextView #tv_bottom_zxg_num / " 只股票"
 *        └─ TextView #tv_bottom_add_zxg "确认添加"
 * ```
 *
 * 实测交互：多选态下**点击整行**即切换 `#ct_item` 的勾选，并同步底部「已选 N」。
 */
object StgSelectAllTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /** 多选栏里那颗按钮（正常态文案「勾选加入自选股」，多选态文案「取消」） */
    val CANCEL_BUTTON_ID_CANDIDATES = arrayOf(
        "tv_stg_add_zxg",
    )

    /** 承载股票列表的 ListView */
    val LIST_ID_CANDIDATES = arrayOf(
        "lvStks",
    )

    /** 每行的勾选框（`CheckedTextView`，多选态可见） */
    val CHECKBOX_ID_CANDIDATES = arrayOf(
        "ct_item",
    )

    /**
     * 底部「已选 N 只股票」里的**计数**控件（`TextView`，内容是裸数字）。
     * 宿主在每次勾选变化后自己维护它，所以「是否已全选」直接读它即可 ——
     * 比遍历可见行更准（含屏外行）也更省（不建 View）。
     */
    val COUNT_ID_CANDIDATES = arrayOf(
        "tv_bottom_zxg_num",
    )

    /** 需要「挂 TextWatcher 观察多选态切换」的页面（Activity 类名，实测） */
    val WATCH_ACTIVITIES = arrayOf(
        "com.guosen.quotation.ui.aistk.AIStkStgDetailAct",
    )

    /** 多选态时该按钮的文案（命中即表示已进入多选态） */
    const val CANCEL_TEXT = "取消"

    /** 注入按钮在「未全选」时的文案：点击 = 勾选全部 */
    const val SELECT_ALL_TEXT = "全选"

    /** 注入按钮在「已全选」时的文案：点击 = 取消全部选中 */
    const val INVERT_TEXT = "反选"

    fun isWatchActivity(className: String): Boolean =
        WATCH_ACTIVITIES.any { it == className }
}
