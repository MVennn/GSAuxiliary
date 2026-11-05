package io.github.MVennn.GSAuxiliary.feature.stgsnap

/**
 * 一页「等数据就绪」的计时器：**挂起的时长不计入超时**。
 *
 * 2026-11-05 起，连续截图允许用户切到别的窗口（小窗 / 分屏 / 别的 App），任务不中止，
 * 只是挂起等窗口回到可截状态。若仍按墙钟算超时，用户在别处待 10 秒回来，这一页就被
 * 判成「数据未就绪」跳过 —— 其实数据早就好了。所以超时按「窗口可截的活跃时间」累计：
 *
 * ```
 * start ──活跃──[pause]──挂起（不计时）──[resume]──活跃── expired
 * ```
 *
 * 纯逻辑，无 Android 依赖，单测见 `SnapPauseWindowTest`。
 */
internal class SnapPauseWindow(private val timeoutMs: Long) {

    /** 计时起点（`elapsedRealtime`）；-1 = 还没 start */
    private var startedAt = -1L

    /** 本次挂起起点；-1 = 当前不在挂起 */
    private var pausedSince = -1L

    /** 历史挂起累计时长 */
    private var pausedTotal = 0L

    val isPaused: Boolean get() = pausedSince >= 0

    fun start(now: Long) {
        startedAt = now
        pausedSince = -1L
        pausedTotal = 0L
    }

    /** 进入挂起；本来就是挂起态返回 false（调用方据此只打一次日志 / 只刷一次 UI） */
    fun pause(now: Long): Boolean {
        if (pausedSince >= 0) return false
        pausedSince = now
        return true
    }

    /** 结束挂起；返回本次挂起了多久（毫秒），本来没挂起返回 0 */
    fun resume(now: Long): Long {
        val since = pausedSince
        if (since < 0) return 0L
        val duration = (now - since).coerceAtLeast(0L)
        pausedTotal += duration
        pausedSince = -1L
        return duration
    }

    /** 已用掉的活跃时间（不含任何挂起段） */
    fun activeMs(now: Long): Long {
        if (startedAt < 0) return 0L
        val until = if (pausedSince >= 0) pausedSince else now
        return ((until - startedAt) - pausedTotal).coerceAtLeast(0L)
    }

    fun remainingMs(now: Long): Long = (timeoutMs - activeMs(now)).coerceAtLeast(0L)

    /** 挂起中永不过期：窗口回来还要接着截这一页 */
    fun expired(now: Long): Boolean = !isPaused && remainingMs(now) <= 0L
}
