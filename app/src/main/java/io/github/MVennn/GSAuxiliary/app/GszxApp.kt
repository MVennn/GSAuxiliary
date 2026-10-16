package io.github.MVennn.GSAuxiliary.app

import android.app.Application
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.GLog

/**
 * 模块自身的 Application（运行在模块自己的进程，**不是**被注入的目标进程）。
 *
 * 只做三件事：
 *  1. 初始化配置仓库（本地兜底 + 等待框架服务）
 *  2. 注册框架服务监听，拿到 binder 后把配置后端切到 remote preferences
 *  3. 把「日志输出等级」应用到模块 App 自己的日志出口
 *     —— 之前只有目标进程读了这项配置，模块 App 侧永远是 INFO
 */
class GszxApp : Application() {

    override fun onCreate() {
        super.onCreate()

        ModulePreferences.init(this)
        // 日志等级必须在任何会产生日志的初始化之前应用（2.17.3）：
        // ModulePreferences.init() 经 FrameworkBridge.addListener 的状态回调会打
        // 「配置后端 = …」日志，若等级应用晚于它，「关闭」档拦不住启动期这几条，
        // 用户看到的日志就像不受开关控制（见 2.17.3 CHANGELOG）。
        applyLogLevel()

        FrameworkBridge.init()

        // 设置界面改等级后立即生效（配置变化统一切主线程分发）
        ModulePreferences.observe { applyLogLevel() }

        // 把「桌面图标」开关同步到组件状态，保证与配置一致（重装/升级后自愈）
        LauncherIcon.apply(
            this,
            ModulePreferences.getBoolean(ConfigKeys.DESKTOP_ICON, ConfigKeys.DEFAULT_DESKTOP_ICON),
        )

        GLog.i("模块 App 启动：配置仓库与服务监听就绪")
    }

    private fun applyLogLevel() {
        GLog.level = ModulePreferences.getInt(ConfigKeys.LOG_LEVEL, ConfigKeys.DEFAULT_LOG_LEVEL)
    }
}
