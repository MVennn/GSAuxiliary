package io.github.MVennn.GSAuxiliary.feature

import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.libxposed.api.XposedInterface

/**
 * 功能运行环境。功能模块通过它拿到框架接口、宿主 classLoader 与当前配置，
 * 而不必自己去 `Class.forName` 或持有全局状态。
 */
class FeatureEnv(
    /** 现代 API 接口，挂钩子 / 打日志都走它 */
    val xposed: XposedInterface,
    /** 目标 App 的 classLoader（来自 `PackageReadyParam.getClassLoader()`） */
    val classLoader: ClassLoader,
) {
    /** 当前配置快照；配置变化时由 [FeatureManager] 整体替换 */
    @Volatile
    var config: ConfigSnapshot = ConfigSnapshot.DEFAULT
        internal set
}
