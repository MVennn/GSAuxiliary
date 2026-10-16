package io.github.MVennn.GSAuxiliary.core

import android.util.Log

/** 日志等级。数值越大越啰嗦，0 = 完全关闭。 */
object LogLevel {
    const val OFF = 0
    const val ERROR = 1
    const val INFO = 2
    const val DEBUG = 3
}

/**
 * 统一日志出口。
 *
 * 两个进程都用它：
 *  - **目标进程**：由 [GszxModule] 在启动时 [install] 一个把日志转发给
 *    `XposedInterface.log()` 的 sink。日志会写进 LSPosed 自己的日志文件
 *    （`/data/adb/lspd/log/modules_*.log`），**不在 logcat 里**。
 *  - **模块 App 进程**：没有 sink，直接走 `android.util.Log`（在 logcat 里）。
 *
 * ## 3.2.0：模块内的日志 UI 与等级配置整体移除
 *
 * 设置页的「日志」分组（日志输出等级 / 查看日志）和 `ui/LogViewer.kt` 一起删掉了，
 * 这里也跟着瘦身：
 *  - **不再有内存环形缓冲**（原来的 `buffer` / `snapshot()` / `clearBuffer()`）——
 *    它是专供查看页读的，页面没了就是纯占内存；
 *  - **等级不再由配置驱动**，只剩代码里的 [level]（默认 `INFO`）。
 *    临时想看 `GLog.d` 的调试日志，改这一处即可。
 *
 * 日志**输出本身一点没少**：目标进程照旧进 LSPosed 日志文件，模块 App 进程照旧进
 * logcat —— 排障入口从「模块内的查看页」换成这两个系统侧通道。
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

    /**
     * 日志阈值，见 [LogLevel]。
     *
     * 用户侧的「日志输出等级」开关已随设置页的日志分组删除（3.2.0），
     * 所以这是一个**只在代码里改**的值：默认 `INFO`（`GLog.d` 不输出），
     * 需要调试时改成 `DEBUG`。
     */
    @Volatile
    var level: Int = LogLevel.INFO

    /** 由目标进程入口注入框架日志出口 */
    fun install(s: Sink) {
        sink = s
    }

    private fun enabled(priority: Int): Boolean = when (level) {
        LogLevel.OFF -> false
        LogLevel.ERROR -> priority >= Log.ERROR
        LogLevel.INFO -> priority >= Log.INFO
        else -> true
    }

    private fun emit(priority: Int, msg: String, tr: Throwable?) {
        if (!enabled(priority)) return

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
