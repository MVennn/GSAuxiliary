package io.github.SansKritO4.GSAuxiliary.feature

import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.HookBag

/**
 * 一个可独立开关的功能模块。
 *
 * **扩展新功能的唯一入口**：新建一个类实现本接口，然后登记到
 * [FeatureManager.FEATURES] 即可，不需要动入口类、配置层或 UI 之外的任何代码。
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
}
