package gsauxiliary.m.venn.core

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
 *    （`/data/adb/lspd/log/modules_*.log`），**不在 logcat 里**。
 *  - **模块 App 进程**：没有 sink，直接走 `android.util.Log`（在 logcat 里），
 *    同时写进内存环形缓冲，供设置界面「查看日志」直接展示。
 *
 * ⚠️ 本类刻意**不引用任何 `io.github.libxposed.api` 类型**。
 * 那些类只在目标进程由框架提供，模块 App 的 APK 里没有；
 * 一旦在签名里出现，模块 App 进程加载本类时就可能 `NoClassDefFoundError`。
 * 所以框架接口通过纯 Java 函数式接口 [Sink] 注入，而不是直接持有。
 */
object GLog {

    const val TAG = "GSZXEnhance"

    /** 内存环形缓冲的最大行数，够排查问题又不至于吃内存 */
    private const val MAX_LINES = 500

    /** 框架侧日志出口。目标进程启动时注入。 */
    fun interface Sink {
        fun log(priority: Int, tag: String, msg: String, tr: Throwable?)
    }

    @Volatile
    private var sink: Sink? = null

    @Volatile
    var level: Int = LogLevel.INFO

    /** 模块 App 进程「查看日志」用的环形缓冲；目标进程也会写，但没人读，无害 */
    private val buffer = ArrayDeque<String>()

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

        record(priority, msg, tr)

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

    /** 写入内存环形缓冲（线程安全；超过上限丢最旧的） */
    private fun record(priority: Int, msg: String, tr: Throwable?) {
        val line = buildString {
            append('[').append(priorityChar(priority)).append("] ")
            append(timeStamp())
            append(' ')
            append(msg)
            if (tr != null) {
                append('\n').append(Log.getStackTraceString(tr).trimEnd())
            }
        }
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
    }

    private fun priorityChar(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }

    private fun timeStamp(): String {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        return fmt.format(java.util.Date())
    }

    /** 「查看日志」页读取当前缓冲快照（按时间正序） */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    /** 清空内存缓冲（不影响 LSPosed 日志文件） */
    fun clearBuffer() {
        synchronized(buffer) { buffer.clear() }
    }

    fun d(msg: String) = emit(Log.DEBUG, msg, null)
    fun i(msg: String) = emit(Log.INFO, msg, null)
    fun w(msg: String, tr: Throwable? = null) = emit(Log.WARN, msg, tr)
    fun e(msg: String, tr: Throwable? = null) = emit(Log.ERROR, msg, tr)
}
