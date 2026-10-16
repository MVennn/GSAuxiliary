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
 *  3. 把「桌面图标」开关同步到组件状态
 *
 * 3.2.0 起不再在这里应用「日志输出等级」—— 那项配置随设置页的日志分组一起删除，
 * 日志阈值回到 [GLog.level] 里的固定值（默认 INFO）。
 */
class GszxApp : Application() {

    override fun onCreate() {
        super.onCreate()

        ModulePreferences.init(this)

        FrameworkBridge.init()

        // 把「桌面图标」开关同步到组件状态，保证与配置一致（重装/升级后自愈）
        LauncherIcon.apply(
            this,
            ModulePreferences.getBoolean(ConfigKeys.DESKTOP_ICON, ConfigKeys.DEFAULT_DESKTOP_ICON),
        )

        GLog.i("模块 App 启动：配置仓库与服务监听就绪")
    }
}
