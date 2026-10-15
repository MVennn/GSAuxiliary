package gsauxiliary.m.venn.app

import android.app.Application
import gsauxiliary.m.venn.core.ConfigKeys
import gsauxiliary.m.venn.core.GLog

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
        FrameworkBridge.init()

        applyLogLevel()
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
