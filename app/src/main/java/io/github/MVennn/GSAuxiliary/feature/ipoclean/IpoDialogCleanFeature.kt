package io.github.MVennn.GSAuxiliary.feature.ipoclean

import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.util.Reflect

/**
 * 功能：**登录后不再弹出「今日新股申购提醒」**。
 *
 * ## 拦在哪
 *
 * 两道，都不改宿主任何判断逻辑：
 *
 *  1. **`android.app.Dialog#show`（主）** —— 所有弹窗的汇聚点。
 *     拦下之前判一次类名（[IpoCleanTargets.isIpoDialog]），
 *     是那个弹窗就**不 proceed**，不是就原样放行。
 *  2. **`MainAct#showSgbDialog`（兜底）** —— 宿主页面自己那一支专用方法。
 *     万一哪天弹窗换了个类名、第一道判不中，这里还能把它堵在构造之前。
 *
 * 挡在 `Dialog.show` 而不是 `PopAlert.createIpoAlertDialog`，是因为后者
 * 只是「构造」：让构造返回 `null` 会把 NPE 甩回宿主手里，
 * 而让 `show` 不执行则干干净净 —— 对象建了、没显示，宿主的后续流程一切照旧。
 *
 * ## 关掉开关之后
 *
 * 钩子装上去就不会摘（见 [Feature] 的约定），所以**拦截条件里必须带上开关**，
 * 否则关掉功能后弹窗依然不出现。同理也不能提前 `return`。
 */
class IpoDialogCleanFeature : Feature {

    override val id = "ipo_dialog_clean"
    override val title = "新股申购提醒弹窗净化"

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.ipoDialogClean

    override fun install(env: FeatureEnv, bag: HookBag) {
        var hooked = 0
        hooked += hookDialogShow(env, bag)
        hooked += hookHostShowMethod(env, bag)

        if (hooked == 0) {
            GLog.e("$id：没有挂上任何钩子，功能不会生效（宿主类名可能已变，用 tools/probe 重新定位）")
        } else {
            GLog.i("$id：已挂 $hooked 个钩子")
        }
    }

    // ------------------------------------------------------------------ 拦截点

    /**
     * 主拦截点：`Dialog.show()`。
     *
     * 只挂**无参**那份就够 —— `Dialog` 全类只有 `show()` 一个显示入口，
     * 子类即使覆写也仍会经过这里（我们挂的是基类的 [java.lang.reflect.Method]，
     * LSPlant 会把整个继承链上的调用都带进来）。
     */
    private fun hookDialogShow(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass(IpoCleanTargets.CLS_DIALOG, env.classLoader)
        if (cls == null) {
            GLog.w("$id：找不到 ${IpoCleanTargets.CLS_DIALOG}，主拦截点未挂")
            return 0
        }

        var n = 0
        for (m in Reflect.methodsNamed(cls, "show")) {
            if (m.parameterTypes.isNotEmpty()) continue
            bag.add(
                env.xposed.hook(m)
                    .setId("$id:dlg-show")
                    .intercept { chain ->
                        val self = chain.thisObject
                        val name = self?.javaClass?.name
                        if (env.config.ipoDialogClean && name != null &&
                            IpoCleanTargets.isIpoDialog(name)
                        ) {
                            GLog.i("$id：已拦下新股申购提醒弹窗（$name）")
                            null
                        } else {
                            chain.proceed()
                        }
                    }
            )
            n++
        }
        if (n == 0) GLog.w("$id：${IpoCleanTargets.CLS_DIALOG} 上没有可挂的 show()")
        return n
    }

    /**
     * 兜底拦截点：`MainAct#showSgbDialog` 一类的**申购专用**展示方法。
     *
     * 挂它比挂 `Dialog.show` 更早 —— 弹窗连构造都不会发生。
     * 但它同时也是**更容易随版本改名**的那一个，所以只当兜底，
     * 认不出就静默跳过（主拦截点已经覆盖）。
     */
    private fun hookHostShowMethod(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass(IpoCleanTargets.CLS_MAIN_ACT, env.classLoader) ?: return 0

        var n = 0
        for (name in IpoCleanTargets.SHOW_METHODS) {
            for (m in Reflect.methodsNamed(cls, name)) {
                bag.add(
                    env.xposed.hook(m)
                        .setId("$id:host:$name")
                        .intercept { chain ->
                            if (env.config.ipoDialogClean) {
                                GLog.i("$id：已拦下宿主展示方法 $name")
                                null
                            } else {
                                chain.proceed()
                            }
                        }
                )
                n++
            }
        }
        return n
    }
}
