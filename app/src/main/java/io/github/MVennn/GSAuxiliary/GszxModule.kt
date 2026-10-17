package io.github.MVennn.GSAuxiliary

import android.content.Context
import android.os.Process
import io.github.MVennn.GSAuxiliary.core.ConfigSource
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.LogLevel
import io.github.MVennn.GSAuxiliary.core.TargetApp
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.FeatureManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * GSAuxiliary —— 现代 Xposed API（libxposed API 102）入口。
 *
 * 入口注册：`META-INF/xposed/java_init.list` 里的一行类名。
 *
 * 生命周期（框架按顺序回调）：
 *   [onModuleLoaded]    模块被载入某个进程，一次
 *   [onPackageReady]    某个包的 classLoader 就绪，可在此挂钩子
 *   [onHotReloading]    旧代码收到热重载请求
 *   [onHotReloaded]     新代码就绪，需要自己重装钩子
 *
 * 职责边界：本类**只做编排** —— 判定进程、装配配置通道、驱动 [FeatureManager]。
 * 具体功能一律落在 `feature/` 下，新增功能不需要改本文件。
 *
 * 注意：**不要在构造函数里做任何初始化**。框架要先把
 * `XposedInterfaceWrapper.attachFramework()` 注入进来，接口才可用，
 * 而那发生在 [onModuleLoaded] 之前、构造之后。
 */
class GszxModule : XposedModule() {

    private var configSource: ConfigSource? = null
    private var featureManager: FeatureManager? = null

    // ------------------------------------------------------------ 生命周期

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        // 把日志出口接到框架上（GLog 本身不引用 Xposed 类型，见其注释）
        GLog.install { priority, tag, msg, tr ->
            if (tr != null) log(priority, tag, msg, tr) else log(priority, tag, msg)
        }
        GLog.level = LogLevel.INFO

        val process = param.processName
        if (process.isEmpty() || !process.startsWith(TargetApp.PACKAGE)) {
            // API 102：明确告诉框架「本入口不再需要后续回调」，
            // 框架会释放对入口实例的引用，也不再调用 onPackageLoaded / onPackageReady 等。
            GLog.i("进程 [$process] 不在作用域内，detach")
            detach()
            return
        }

        GLog.i(
            "=== GSAuxiliary v${BuildConfig.VERSION_NAME} 载入 ===" +
                    " process=$process pid=${Process.myPid()}" +
                    " framework=${frameworkName} ${frameworkVersion}" +
                    " api=${apiVersion} caps=0x${java.lang.Long.toHexString(frameworkProperties)}"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TargetApp.PACKAGE) {
            GLog.d("跳过包 ${param.packageName}")
            return
        }
        install(param.classLoader)
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        // 本模块不持有自建线程、不注册 native 钩子、不保留指向宿主的全局引用，
        // 因此可以安全地允许热重载（模块 App 升级后目标 App 无需重启）。
        GLog.i("收到热重载请求，允许")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        // 默认实现只卸载旧钩子；这里显式卸载后再用新代码装一遍。
        GLog.i("热重载完成，重装钩子（旧钩子 ${param.oldHookHandles.size} 个）")

        // 先卸自己记录的句柄（HookBag），再用框架给的列表兜底
        featureManager?.uninstallAll()
        param.oldHookHandles.forEach { runCatching { it.unhook() } }

        featureManager = null
        configSource?.close()
        configSource = null

        val cl = currentAppClassLoader()
        if (cl == null) {
            GLog.e("热重载后拿不到宿主 classLoader，跳过重装（重启目标 App 即可恢复）")
            return
        }
        install(cl)
    }

    // ------------------------------------------------------------ 装配

    private fun install(classLoader: ClassLoader) {
        val xposed: XposedInterface = this

        val env = FeatureEnv(xposed, classLoader)

        val configSource = ConfigSource(xposed)
        val manager = FeatureManager(env)
        // 审查 2026-10-17 低-7：先绑 onChanged 再 open()。open() 内部注册监听并读
        // 首帧，若先 open 后赋值，「注册完成 → 赋值完成」之间推送到的变更会被
        // onChanged 为 null 静默吞掉（ConfigSource.kt 的 `onChanged?.invoke`）。
        configSource.onChanged = { cfg ->
            GLog.level = cfg.logLevel
            manager.onConfigChanged(cfg)
        }
        configSource.open()
        // 目标进程的日志等级跟着模块设置走（3.2.0 起由用户在「设置 → 日志」里调）；
        // 与 sink 刚装上时的默认阈值之间只隔一次配置读取，启动期日志不会漏。
        GLog.level = configSource.snapshot.logLevel
        env.config = configSource.snapshot

        // 配置变化由框架在 Binder 线程推送，FeatureManager 负责切主线程

        this.configSource = configSource
        this.featureManager = manager

        manager.installAll()
    }

    /**
     * 热重载后 `PackageReadyParam` 不会重放，所以自己从 ActivityThread 取宿主 classLoader。
     * 此时目标 App 已经起来，`currentApplication()` 必定可用。
     */
    private fun currentAppClassLoader(): ClassLoader? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val m = at.getDeclaredMethod("currentApplication")
        m.isAccessible = true
        (m.invoke(null) as? Context)?.classLoader
    }.getOrNull()
}
