package io.github.MVennn.GSAuxiliary.feature.strategyselect

import io.github.MVennn.GSAuxiliary.core.TargetApp

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
     *
     * 用途已经收窄（2026-10-21）：它只做**快速路径**的旁证 —— 计数为 0 表示一行都没选中；
     * 以及 checked states 与它明显矛盾时，触发逐行探针。**不再单独作为「是否已全选」的判据**：
     * 筛选生效时它含被筛掉的行，且与屏上勾选框可能不同步（见
     * `StgSelectAllFeature#toggleAll` 与 `docs/FEATURE-SELECT-ALL.md` §4.2）。
     */
    val COUNT_ID_CANDIDATES = arrayOf(
        "tv_bottom_zxg_num",
    )

    /**
     * 底部「确认添加」按钮（宿主自己的 `TextView`）。
     *
     * 2026-10-28 真机：全选是逐行合成点击（`TOGGLE_STEP_MS = 40ms`），30 只就要一秒多；
     * 运行期间这颗按钮**没有任何闸门** —— 用户点早了，宿主只快照到「已经点过的那几行」，
     * 后面的行勾上了却加不进去（实测 72 只只进了 18 只）。所以运行期间由
     * `StgSelectAllFeature` 临时把它置灰锁住，跑完再恢复。
     */
    val CONFIRM_BUTTON_ID_CANDIDATES = arrayOf(
        "tv_bottom_add_zxg",
    )

    /** 需要「挂 TextWatcher 观察多选态切换」的页面（Activity 类名，实测） */
    val WATCH_ACTIVITIES = arrayOf(
        "com.guosen.quotation.ui.aistk.AIStkStgDetailAct",
    )

    /**
     * 宿主的多选模型：`AIStkStgDetailAct.checkCodes`（`List`，元素是股票代码）。
     *
     * 2026-10-23 探针实测（`PAGE[STG]` 实例字段 dump）。它才是「已选 N」与
     * 「确认添加」的真实来源；ListView 的 checked states 只是框架层的另一份表，
     * 两份表会因宿主静默忽略点击 / 刷新而分叉。读不到时功能退回表判据。
     */
    const val MODEL_FIELD = "checkCodes"

    /** 行数据对象里的股票代码字段（与「截图并添加」同一套候选） */
    val CODE_FIELDS = arrayOf("m_stkCode", "mStkCode", "code", "mCode")

    /**
     * 行数据对象里的「代码.市场」组合字段。
     *
     * 实测（2026-10-23）宿主条目只暴露 `m_codeAndMarket` 这类组合字段时，
     * 单看 [CODE_FIELDS] 会一行都取不到代码 → 宿主模型整趟不可用。
     */
    val CAM_FIELDS = arrayOf("codeAndMarket", "m_codeAndMarket")

    /** 多选态时该按钮的文案（命中即表示已进入多选态） */
    const val CANCEL_TEXT = "取消"

    /** 注入按钮在「未全选」时的文案：点击 = 勾选全部 */
    const val SELECT_ALL_TEXT = "全选"

    /** 注入按钮在「已全选」时的文案：点击 = 取消全部选中 */
    const val INVERT_TEXT = "反选"

    fun isWatchActivity(className: String): Boolean =
        WATCH_ACTIVITIES.any { it == className }
}
