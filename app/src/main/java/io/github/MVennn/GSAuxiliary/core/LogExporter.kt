package io.github.MVennn.GSAuxiliary.core

import android.os.Build
import io.github.MVennn.GSAuxiliary.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 「导出日志」的内容组装 —— 把**两个进程**的日志拼成一份纯文本。
 *
 * | 进程 | 来源 | 拿到的方式 |
 * |---|---|---|
 * | 模块 App | `filesDir/logs/gsauxiliary.log`（见 [LogStore]） | 直接读，不需要任何权限 |
 * | 目标进程 | LSPosed 的 `/data/adb/lspd/log/` | 先试直读；读不到再走 `su`（会弹一次授权） |
 *
 * 目标进程那段为什么要 root：模块跑在宿主 App 的进程里，它写日志只能经
 * `XposedInterface.log()` 交给 LSPosed，落到 `/data/adb/lspd/log/` ——
 * 那个目录普通应用连列目录都不允许。所以这里**优先直读**（少数设备上框架
 * 把权限放开了，那就完全不用 root），失败才尝试 `su`。
 *
 * 拿不到就如实写进导出文件（原因写在那一节的抬头），**不做静默降级** ——
 * 用户拿这份文件去排查时，最怕的是「看起来没日志」而不知道是被拦住了。
 *
 * 整段流程是阻塞 IO，必须在后台线程调用（设置页走 `Dispatchers.IO`）。
 * `su` 首次授权要等用户点弹窗，所以超时给得比常规命令宽。
 */
object LogExporter {

    /** 每个进程最多带多少行：够定位问题，又不至于导出个几十 MB 的文件 */
    private const val MaxLinesPerProcess = 3000

    /** LSPosed 的日志目录 */
    private const val LSPD_LOG_DIR = "/data/adb/lspd/log"

    /**
     * 读 LSPosed 日志的 root 命令。
     *
     * 一次 `su` 调用拿两个最新文件（`ls -t` 按修改时间排序，兼容
     * `modules.log` 与 `modules_<时间戳>.log` 两种命名），
     * 每个文件前打一行文件名，便于在导出文件里分辨来源。
     */
    private const val ROOT_CMD =
        "for f in \$(ls -t $LSPD_LOG_DIR/*.log 2>/dev/null | head -2); do " +
            "echo \"-----\$f\"; tail -n $MaxLinesPerProcess \"\$f\"; done"

    /** root 命令的超时：su 首次要弹授权框，等用户点一下 */
    private const val RootTimeoutMs = 20_000L

    /**
     * 导出结果。
     *
     * @param text 完整的文件内容
     * @param hasTarget 目标进程那段是否真的拿到了日志 —— 只用来决定提示语
     *   （拿不到也照样出文件，原因写在文件的对应小节里，见 [TargetLogs.note]）
     */
    data class Result(val text: String, val hasTarget: Boolean)

    /**
     * 组装导出内容。
     *
     * @param levelLabel 当前日志等级的**展示名**（由界面侧按 `R.array.log_level_labels`
     *   解析后传进来 —— 本对象在 `core` 里，不依赖任何资源）
     */
    fun build(levelLabel: String): Result {
        val app = GLog.readStore(MaxLinesPerProcess)
        val target = readTargetLogs()

        val text = buildString {
            appendLine("GSAuxiliary 日志导出")
            appendLine("生成时间：${stamp()}")
            appendLine("模块版本：${BuildConfig.VERSION_NAME}（versionCode ${BuildConfig.VERSION_CODE}）")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）/ ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
            appendLine("日志等级：$levelLabel")
            appendLine()
            appendLine("================ 模块 App 进程（本机落盘）================")
            appendLine("（最多保留 ${LogStore.MaxBytes / 1024} KB ×2，超出滚动覆盖）")
            appendLine(app.ifBlank { "（暂无日志）" })
            appendLine()
            appendLine("================ 目标进程（$LSPD_LOG_DIR）================")
            appendLine("（${target.note}）")
            appendLine(target.lines.ifBlank { "（无）" })
        }

        return Result(text = text, hasTarget = target.lines.isNotBlank())
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    // ------------------------------------------------------------------ 目标进程

    private data class TargetLogs(val lines: String, val note: String)

    /**
     * 取目标进程的日志。
     *
     * 顺序：**先直读、再 root**。直读成功但里面没有本模块的行时不再走 root ——
     * 同一个文件，root 读出来也一样，白白弹一次授权。
     */
    private fun readTargetLogs(): TargetLogs {
        val files = runCatching { findLogFiles() }.getOrNull()
        if (files != null) {
            val text = filterModuleLines(files.flatMap { tailLines(it, MaxLinesPerProcess) })
            return if (text.isBlank()) {
                TargetLogs("", "文件里没有本模块（${GLog.TAG}）的日志")
            } else {
                TargetLogs(text, "读取方式：直接读取 ${files.size} 个文件（无需 root）")
            }
        }

        val res = runShell(ROOT_CMD)
        if (res.timedOut) return TargetLogs("", "读取超时：root 授权可能没确认（${RootTimeoutMs / 1000} 秒）")
        // su 自己失败了（拒绝授权 / 没有 su）：此时回显往往是 "Permission denied" 之类的
        // 一行错误，按「没日志」报会把人带偏，所以按退出码直接归因
        if (res.exitCode != 0) {
            return TargetLogs("", "读取失败：未授予 root 权限或设备没有 su（exit=${res.exitCode}）")
        }

        val lines = res.output.lines()
        val moduleLines = lines.filter { it.contains(GLog.TAG) }
        if (moduleLines.isEmpty()) return TargetLogs("", "文件里没有本模块（${GLog.TAG}）的日志")

        // 输出里既有 `-----文件名` 标记，也有日志行：标记留全（便于分辨来源），日志行只留本模块的
        val kept = lines.filter { it.startsWith("-----") || it.contains(GLog.TAG) }.takeLast(MaxLinesPerProcess)
        return TargetLogs(kept.joinToString("\n"), "读取方式：root（su）读取 $LSPD_LOG_DIR")
    }

    /** 目录可读时列出 `*.log`，按修改时间倒序；不可读返回 null */
    private fun findLogFiles(): List<File>? {
        val dir = File(LSPD_LOG_DIR)
        val all = dir.listFiles() ?: return null
        val logs = all.filter { it.isFile && it.name.endsWith(".log") }
        return logs.sortedByDescending { it.lastModified() }.take(2).ifEmpty { null }
    }

    /** 从若干文件的行里挑出本模块的日志（LSPosed 日志里混着别的模块） */
    private fun filterModuleLines(lines: List<String>): String =
        lines.asSequence().filter { it.contains(GLog.TAG) }.toList().takeLast(MaxLinesPerProcess).joinToString("\n")

    /** 读文件尾部若干行 */
    private fun tailLines(file: File, maxLines: Int): List<String> {
        if (!file.isFile) return emptyList()
        val tail = ArrayDeque<String>()
        runCatching {
            file.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    tail.addLast(line)
                    if (tail.size > maxLines) tail.removeFirst()
                }
            }
        }
        return tail.toList()
    }

    // ------------------------------------------------------------------ 进程执行

    private data class ShellResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * 跑一条 `su -c` 命令。
     *
     * 输出必须在**等待之前**就并发读走：`tail` 几千行远超管道缓冲区，
     * 若先 `waitFor` 再读，su 会卡在写管道上永远不退出。
     */
    private fun runShell(command: String): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val out = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { seq -> seq.forEach { out.appendLine(it) } }
                }
            }
            reader.isDaemon = true
            reader.start()

            val finished = process.waitFor(RootTimeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroy() }
                reader.join(500)
                return ShellResult(-1, out.toString(), timedOut = true)
            }
            reader.join(1000)
            ShellResult(process.exitValue(), out.toString(), timedOut = false)
        } catch (t: Throwable) {
            // 设备上没有 su / 被安全策略拒绝
            GLog.w("执行 su 失败：${t.javaClass.simpleName}", t)
            ShellResult(-2, "", timedOut = false)
        }
    }
}
