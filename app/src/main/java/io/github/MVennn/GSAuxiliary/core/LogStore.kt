package io.github.MVennn.GSAuxiliary.core

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 日志落盘仓库 —— **只有模块 App 进程用得上**。
 *
 * ## 为什么需要它
 *
 * `GLog` 的三个出口里，logcat 会被部分 ROM 屏蔽、内存缓冲重启即丢，
 * 都撑不起「用户点一下就能把日志交出来」这件事。落盘是唯一稳的：
 * 模块 App 的私有目录（`filesDir/logs`）随时可读，跨重启还在，
 * 导出时不需要任何权限。
 *
 * 目标进程**没有**这个仓库 —— 它跑在宿主 App 的进程里，
 * 写不进模块的私有目录（见 [GLog] 的类注释）。
 *
 * ## 形态：两个文件，滚动覆盖
 *
 * ```
 * filesDir/logs/gsauxiliary.log    当前写入
 * filesDir/logs/gsauxiliary.log.1  上一份（轮转时把当前这份改名过来，旧的直接删）
 * ```
 *
 * 单文件到 [MaxBytes] 就轮转，所以磁盘占用硬顶在 2×[MaxBytes]，
 * 导出时两份按顺序拼起来（最多 [MaxBytes]×2 的历史）。
 * 刻意不做「按天分文件」：日志只服务于排障，用户不会去管理文件，
 * 固定两个位置比一堆日期文件更好收拾（`清空` 也只需要删这两个）。
 *
 * ## 写入不阻塞调用方
 *
 * 所有写走一个单线程执行器（串行、daemon、低优先级）：`GLog` 会在
 * 任意线程、任意时机被调用，落盘 IO 不能挂在调用方的关键路径上。
 * 读 / 清空之前先 `flush()`，等队列跑完再动文件 —— 否则刚导出的日志
 * 会缺最后几条（用户往往就是为最后几条来的）。
 *
 * @param dir      落盘目录（通常是 `filesDir/logs`），不存在时自动创建
 * @param fileName 当前文件名，轮转文件固定是它加 `.1` 后缀
 * @param maxBytes 单文件上限
 */
internal class LogStore(
    private val dir: File,
    private val fileName: String = "gsauxiliary.log",
    private val maxBytes: Long = MaxBytes,
) {

    companion object {
        /** 单文件上限 512 KB —— 两份加起来 1 MB，纯文本足够放下几万行 */
        const val MaxBytes = 512L * 1024

        /** 每行前缀的时间戳格式；带完整日期，日志跨天也读得懂 */
        private const val TimePattern = "yyyy-MM-dd HH:mm:ss.SSS"

        /** 堆栈的续行缩进：让「一条日志」在纯文本里一眼能看出边界 */
        private const val TraceIndent = "    "
    }

    private val current = File(dir, fileName)
    private val previous = File(dir, "$fileName.1")

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gszx-log-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * 时间戳格式化器。
     *
     * `SimpleDateFormat` 不是线程安全的，而 [append] 会被任意线程调用 ——
     * 每个线程一份实例，避免为了格式化去抢锁。
     */
    private val timeFormat = ThreadLocal.withInitial { SimpleDateFormat(TimePattern, Locale.US) }

    /** 追加一条日志。立即返回，实际落盘在写入线程上排队 */
    fun append(priority: Int, msg: String, tr: Throwable?) {
        val stamped = buildString {
            append(timeFormat.get()?.format(Date()).orEmpty())
            append(' ').append('[').append(priorityChar(priority)).append(']').append(' ')
            append(msg)
            if (tr != null) append('\n').append(indentTrace(tr))
        }
        runCatching { writer.execute { runCatching { write(stamped) } } }
    }

    /**
     * 读取落盘日志的**尾部** [maxLines] 行（`.1` 在前、当前文件在后）。
     *
     * 调用方通常在 IO 线程上（导出），整份文件才 1 MB，直接按行读完再截尾
     * 反而简单可靠 —— 不需要 `RandomAccessFile` 那套从尾部倒着切的逻辑。
     */
    fun readTail(maxLines: Int): String {
        flush()
        val tail = ArrayDeque<String>()
        for (f in listOf(previous, current)) {
            if (!f.isFile) continue
            runCatching {
                f.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        tail.addLast(line)
                        if (tail.size > maxLines) tail.removeFirst()
                    }
                }
            }
        }
        return tail.joinToString("\n")
    }

    /** 清空两份文件（设置页把等级切到「关闭」时调用） */
    fun clear() {
        runCatching { writer.execute { runCatching { current.delete(); previous.delete() } } }
    }

    /** 停掉写入线程（进程退出前的收尾，正常运行时不需要调） */
    fun close() {
        runCatching { writer.shutdown() }
    }

    /** 等写入队列跑完，最多 [FlushTimeoutMs] 毫秒 */
    private fun flush() {
        runCatching { writer.submit { }.get(FlushTimeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun write(line: String) {
        if (!dir.isDirectory && !dir.mkdirs()) return
        val bytes = (line + '\n').toByteArray(Charsets.UTF_8)
        if (current.length() + bytes.size > maxBytes) rotate()
        FileOutputStream(current, true).use { it.write(bytes) }
    }

    /** 轮转：当前文件改名成 `.1`，旧的 `.1` 直接丢掉（只保留一份历史） */
    private fun rotate() {
        if (!current.exists()) return
        previous.delete()
        if (!current.renameTo(previous)) {
            // 改名失败（文件被占用等）：宁可丢掉历史，也不能让当前文件无限长下去
            Log.w(GLog.TAG, "日志轮转失败，清空当前文件：${current.absolutePath}")
            current.delete()
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

    private fun indentTrace(tr: Throwable): String =
        Log.getStackTraceString(tr).trimEnd().lines().joinToString("\n") { TraceIndent + it }
}

/** 等落盘队列跑完的上限；给足 2 秒，正常只有几毫秒 */
private const val FlushTimeoutMs = 2000L
