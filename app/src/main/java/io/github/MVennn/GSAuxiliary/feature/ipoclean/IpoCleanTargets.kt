package io.github.MVennn.GSAuxiliary.feature.ipoclean

/**
 * 「今日新股申购提醒」弹窗的定位常量。
 *
 * ## 实测链路（2026-10-16 · 金太阳 8.6.0）
 *
 * 探针打印出来的完整调用栈，从上到下：
 *
 * ```
 * MainAct#receiveBroadcast:943          ← 收到引擎广播
 *   MainBiz#getIPODialog:148            ← 业务层决定「要弹这个」
 *     EngineModel#setBizCommand:55
 *       MainAct#showMsgDialog:1282      ← 通用弹窗分发（别的提醒也走它，别动）
 *         MainAct#showSgbDialog:1315    ← 申购宝 / 新股专用那一支
 *           PopAlert#createIpoAlertDialog:911
 *             IpoAlertDialog.show()     ← 真正显示
 * ```
 *
 * 弹窗本身的类名是 `com.guosen.commonui.dialog.IpoAlertDialog`，
 * 继承链是 `IpoAlertDialog → CustomAlertDialog → Dialog`。
 *
 * ## 为什么拦截点选在 `Dialog.show`
 *
 * `IpoAlertDialog` **未必覆写** `show`，而本项目只能拿到 `declaredMethods`
 * （见 [io.github.MVennn.GSAuxiliary.util.Reflect.methodsNamed]），
 * 挂子类会随宿主改版静默失效。`android.app.Dialog#show` 是所有弹窗的**唯一汇聚点**，
 * 在那里按类名判一次，既不用猜覆写关系，也不受中间父类改动影响。
 *
 * 代价是它对全部 Dialog 生效 —— 所以判据必须收得很紧（见 [isIpoDialog]），
 * 宁可漏掉这一个弹窗，也绝不能误伤宿主其它对话框。
 */
object IpoCleanTargets {

    /** 弹窗类全名（实测） */
    const val CLS_IPO_ALERT_DIALOG = "com.guosen.commonui.dialog.IpoAlertDialog"

    /**
     * 弹窗类候选**全名** —— 宿主改版换包路径时往这里加。
     *
     * 只在 `Dialog.show` 的拦截链里做一次比对，命中即拦下。
     */
    val IPO_DIALOG_CLASSES = arrayOf(
        CLS_IPO_ALERT_DIALOG,
    )

    /**
     * 弹窗类候选**简名** —— 包路径变了、但类名没变时的兜底。
     *
     * 简名匹配比全名宽松，所以列表刻意只留「一眼就是新股弹窗」的名字，
     * 不写 `IpoDialog` 这种过于通用的写法也无妨：真被混淆成别的名字时，
     * 还有 [CLS_MAIN_ACT] 那条兜底。
     */
    val IPO_DIALOG_SIMPLE_NAMES = arrayOf(
        "IpoAlertDialog",
        "IpoRemindDialog",
        "NewStockAlertDialog",
    )

    /** 弹窗构造入口所在的类 */
    const val CLS_POP_ALERT = "com.guosen.commonui.dialog.PopAlert"

    /** 构造入口方法名候选（仅用于日志与排查，不直接挂） */
    val CREATE_METHODS = arrayOf(
        "createIpoAlertDialog",
        "createIpoDialog",
        "createNewStockDialog",
    )

    /** 宿主页面 */
    const val CLS_MAIN_ACT = "com.guosen.app.goldsun.ui.MainAct"

    /**
     * 宿主页面上的展示方法候选（第二道保险）。
     *
     * 这一支是「新股专用」的：同一层里还有通用的 `showMsgDialog`，
     * **不能挂它** —— 其它业务提醒也走那条路。
     */
    val SHOW_METHODS = arrayOf(
        "showSgbDialog",
        "showIpoAlertDialog",
    )

    /** Dialog 基类（拦截汇聚点） */
    const val CLS_DIALOG = "android.app.Dialog"

    /**
     * 这个类名是不是「今日新股申购提醒」弹窗。
     *
     * 全名与简名各判一次：宿主升级换包路径时简名兜底，换类名时全名兜底。
     */
    fun isIpoDialog(className: String): Boolean {
        if (IPO_DIALOG_CLASSES.any { it == className }) return true
        val simple = className.substringAfterLast('.')
        return IPO_DIALOG_SIMPLE_NAMES.any { it == simple }
    }
}
