package io.github.MVennn.GSAuxiliary.app

import android.app.Application
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.LogStore
import java.io.File

/**
 * 模块自身的 Application（运行在模块自己的进程，**不是**被注入的目标进程）。
 *
 * 做四件事：
 *  1. 初始化配置仓库（本地兜底 + 等待框架服务）
 *  2. 应用「日志等级」，并挂上日志落盘仓库（「导出日志」读的就是它）
 *  3. 注册框架服务监听，拿到 binder 后把配置后端切到 remote preferences
 *  4. 按本地镜像重放「桌面图标」组件状态（覆盖安装后保持隐藏）
 *
 * 这一档配置（3.2.0 起重新由用户可调）与目标进程共用同一个键
 * `ConfigKeys.LOG_LEVEL`：这里管模块 App 进程，`GszxModule` 管目标进程。
 */
class GszxApp : Application() {

    override fun onCreate() {
        super.onCreate()

        ModulePreferences.init(this)

        // 日志等级必须在任何会产生日志的初始化之前应用（2.17.3）：
        // ModulePreferences.init() 经 FrameworkBridge.addListener 的状态回调会打
        // 「配置后端 = …」日志，若等级应用晚于它，「关闭」档拦不住启动期这几条，
        // 用户看到的日志就像不受开关控制。
        applyLogLevel()

        // 落盘仓库也要尽早挂上 —— 在这之后产生的日志才进得了「导出日志」
        GLog.attachStore(LogStore(File(filesDir, "logs")))

        FrameworkBridge.init()

        // 设置界面改等级后立即生效（配置变化统一切主线程分发）
        ModulePreferences.observe { applyLogLevel() }

        // 按本地镜像重放「桌面图标」组件状态（覆盖安装 / 老版本升级后保持隐藏）。
        // 不读 ModulePreferences / remote：独立镜像不依赖框架 binder，避开
        // LOCAL→REMOTE 切换的启动竞态；静态 receiver 未投递时由这里兜底；
        // 也刻意不挂进 observe，避免用 remote 旧值覆盖用户选择或形成通知回路。
        LauncherIcon.applyFromLocal(this)

        GLog.i("模块 App 启动：配置仓库与服务监听就绪")
    }

    private var lastAppliedLevel = Int.MIN_VALUE

    private fun applyLogLevel() {
        val level = ModulePreferences.getInt(ConfigKeys.LOG_LEVEL, ConfigKeys.DEFAULT_LOG_LEVEL)
        GLog.level = level
        // 冷启动时框架还没连上，active() 是本地文件 —— 把最近一次生效的等级
        // 缓存到本地，否则「关闭」档在启动窗口内拦不住日志。
        if (level != lastAppliedLevel) {
            lastAppliedLevel = level
            ModulePreferences.setLocalInt(ConfigKeys.LOG_LEVEL, level)
        }
    }
}
