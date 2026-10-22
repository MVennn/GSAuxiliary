package io.github.MVennn.GSAuxiliary.feature

import android.app.Activity
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.HookBag

/**
 * 一个可独立开关的功能模块。
 *
 * **扩展新功能的唯一入口**：新建一个类实现本接口，然后登记到
 * [FeatureManager.features] 即可，不需要动入口类、配置层或 UI 之外的任何代码。
 *
 * 约定：
 *  - [install] 里只做「挂钩子」，不做重活；所有钩子句柄都要塞进 [bag]，
 *    这样热重载 / 关闭功能时能干净卸载。
 *  - [onConfigChanged] 在**主线程**被调用（[FeatureManager] 负责切线程）。
 *  - 功能自己决定 [isEnabled]；返回 false 时不安装，但已装上的钩子不会被自动摘掉，
 *    所以功能内部仍要在钩子里判断开关（例如染色功能关掉时要主动还原原色）。
 */
interface Feature {

    /** 稳定标识，用于钩子 id 前缀、日志与调试通道 */
    val id: String

    /** 展示名（日志用） */
    val title: String

    /** 当前配置下是否启用 */
    fun isEnabled(cfg: ConfigSnapshot): Boolean

    /** 安装钩子。所有句柄必须放进 [bag]。 */
    fun install(env: FeatureEnv, bag: HookBag)

    /** 配置变化通知（主线程）。默认什么都不做。 */
    fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {}

    /**
     * 某个 Activity 正在销毁（主线程，在宿主的 `Activity.onDestroy` 之前分发）。
     *
     * **只用来释放按页面记账的引用**：弱表里的监听器 / 视图 / 适配器、进程级快照等。
     * 页面已经要没了，**不要在这里做「还原现场」**（改宿主视图、恢复开关状态）——
     * 那些只属于「功能关闭 / 热重载」，在销毁路径上做只会多出一次无意义甚至危险的视图操作。
     *
     * 为什么需要它：本项目的页面级记账大量使用 `WeakHashMap`，但值里只要有一个
     * 能反向走到 key 的强引用（listener 捕获 decorView、View 的 Context 是 Activity、
     * 代理持有原 adapter……），弱键就永远不会被回收。`onDestroy` 是唯一一个
     * 「这一页确定结束」的时机，在这里按页面身份删条目，比把每张表都改成弱引用安全
     * （弱引用簿记被提前回收会让注入直接停掉，历史上已经踩过）。
     */
    fun onActivityDestroyed(activity: Activity) {}
}
