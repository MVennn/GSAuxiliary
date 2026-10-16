package io.github.MVennn.GSAuxiliary.core

import android.util.Log

/** 日志等级。数值越大越啰嗦，0 = 完全关闭。 */
object LogLevel {
    const val OFF = 0
    const val ERROR = 1
    const val INFO = 2
    const val DEBUG = 3

    /** 下拉框取值顺序（展示名在 `R.array.log_level_labels`，与这里一一对应） */
    val VALUES = intArrayOf(OFF, ERROR, INFO, DEBUG)
}

/**
 * 统一日志出口。
 *
 * 两个进程都用它：
 *  - **目标进程**：由 [GszxModule] 在启动时 [install] 一个把日志转发给
 *    `XposedInterface.log()` 的 sink。日志会写进 LSPosed 自己的日志文件
 *    （`/data/adb/lspd/log/`），**不在 logcat 里**。
 *  - **模块 App 进程**：没有 sink，直接走 `android.util.Log`（在 logcat 里），
 *    另外**落盘**一份 —— 设置页「日志 → 导出日志」就是读它（见 [LogStore]）。
 *
 * ## 等级由配置驱动（3.2.0 定稿）
 *
 * [level] 的取值来自 `ConfigKeys.LOG_LEVEL`，两个进程各自应用：
 *  - 模块 App 进程：`GszxApp.onCreate` 里应用，配置一变立刻跟着改；
 *  - 目标进程：`GszxModule` 装配配置通道时按快照设置，之后由框架推送的
 *    配置变化回调持续同步 —— **不需要重启目标 App**。
 *
 * 设置页「关闭」档会顺带清掉落盘历史（[clearStore]）：等级只拦新日志，
 * 留着满屏旧日志会让人以为开关没生效。
 *
 * ## 为什么只有模块 App 进程落盘
 *
 * 目标进程跑在宿主 App 里，写不进模块的私有目录；它能用的只有框架给的
 * `XposedInterface.log()`，也就是 LSPosed 的日志文件（导出时经 root 读取，
 * 见 [LogExporter]）。
 *
 * ⚠️ 本类刻意**不引用任何 `io.github.libxposed.api` 类型**。
 * 那些类只在目标进程由框架提供，模块 App 的 APK 里没有；
 * 一旦在签名里出现，模块 App 进程加载本类时就可能 `NoClassDefFoundError`。
 * 所以框架接口通过纯 Java 函数式接口 [Sink] 注入，而不是直接持有。
 */
object GLog {

    const val TAG = "GSZXEnhance"

    /** 框架侧日志出口。目标进程启动时注入。 */
    fun interface Sink {
        fun log(priority: Int, tag: String, msg: String, tr: Throwable?)
    }

    @Volatile
    private var sink: Sink? = null

    /** 日志阈值，见 [LogLevel]；由配置驱动，默认 `INFO` */
    @Volatile
    var level: Int = LogLevel.INFO

    /** 落盘仓库；**只有模块 App 进程会挂上**，目标进程恒为 null */
    @Volatile
    private var store: LogStore? = null

    /** 由目标进程入口注入框架日志出口 */
    fun install(s: Sink) {
        sink = s
    }

    /** 由模块 App 进程挂上落盘仓库（见 [LogStore]） */
    internal fun attachStore(s: LogStore) {
        store = s
    }

    /** 读落盘日志的尾部若干行；没挂仓库（或还没写过）时返回空串 */
    fun readStore(maxLines: Int): String = store?.readTail(maxLines).orEmpty()

    /** 清掉落盘日志（等级切到「关闭」时调用；不影响 LSPosed 日志文件） */
    fun clearStore() {
        store?.clear()
    }

    private fun enabled(priority: Int): Boolean = when (level) {
        LogLevel.OFF -> false
        LogLevel.ERROR -> priority >= Log.ERROR
        LogLevel.INFO -> priority >= Log.INFO
        else -> true
    }

    private fun emit(priority: Int, msg: String, tr: Throwable?) {
        if (!enabled(priority)) return

        // 先落盘，再走各自进程的出口 —— 落盘是排队异步的，不会拖住调用方
        store?.append(priority, msg, tr)

        val s = sink
        if (s != null) {
            runCatching { s.log(priority, TAG, msg, tr) }
        } else {
            // 模块 App 进程没有框架接口，退回 logcat
            if (tr != null) {
                Log.println(priority, TAG, "$msg\n${Log.getStackTraceString(tr)}")
            } else {
                Log.println(priority, TAG, msg)
            }
        }
    }

    fun d(msg: String) = emit(Log.DEBUG, msg, null)
    fun i(msg: String) = emit(Log.INFO, msg, null)
    fun w(msg: String, tr: Throwable? = null) = emit(Log.WARN, msg, tr)
    fun e(msg: String, tr: Throwable? = null) = emit(Log.ERROR, msg, tr)
}
