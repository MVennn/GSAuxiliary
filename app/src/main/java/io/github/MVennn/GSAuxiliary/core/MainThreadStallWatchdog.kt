package io.github.MVennn.GSAuxiliary.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 主线程卡死取证。
 *
 * 症状：聚合到最后一个成员后日志整段断掉、界面停在空态、宿主自己往服务器报了一次 ANR ——
 * 但没有任何异常栈，因为主线程不是抛错而是**被别的东西占住**（死循环 / 同步网络 / 锁）。
 *
 * 做法：主线程每 [BEAT_MS] 打一次心跳，另一个线程盯着心跳。心跳停超过 [STALL_MS] 就把
 * **主线程当前调用栈**整段打进日志（最多 [MAX_DUMPS] 次，避免刷屏），据此能直接看出卡在谁里面。
 *
 * 只在聚合轮次里开，轮次结束就停 —— 常态下零开销（一个 500ms 的 postDelayed）。
 */
object MainThreadStallWatchdog {
    private const val BEAT_MS = 500L
    private const val STALL_MS = 3000L
    private const val MAX_DUMPS = 6
    private const val MAX_FRAMES = 40

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var beatAt = 0L
    @Volatile private var running = false
    @Volatile private var reason = ""
    private var worker: Thread? = null

    private val beater = object : Runnable {
        override fun run() {
            beatAt = SystemClock.uptimeMillis()
            if (running) main.postDelayed(this, BEAT_MS)
        }
    }

    /** 开表。重复调用只重置计时，不会起第二个线程。 */
    @Synchronized
    fun start(tag: String) {
        reason = tag
        val first = !running
        running = true
        if (first) {
            beatAt = SystemClock.uptimeMillis()
            main.post(beater)
            val t = Thread({ loop() }, "GSZX-main-watch")
            t.isDaemon = true
            worker = t
            t.start()
        }
    }

    @Synchronized
    fun stop(tag: String) {
        if (!running) return
        running = false
        main.removeCallbacks(beater)
        worker = null
        GLog.d("主线程看门狗：停表（" + tag + "）")
    }

    private fun loop() {
        var dumps = 0
        while (running) {
            try {
                Thread.sleep(BEAT_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (!running) return
            val idle = SystemClock.uptimeMillis() - beatAt
            if (idle < STALL_MS) continue
            if (dumps >= MAX_DUMPS) continue
            dumps++
            dump(idle)
        }
    }

    private fun dump(idle: Long) {
        val sb = StringBuilder()
        sb.append("主线程已 ").append(idle).append("ms 没动（").append(reason).append("）栈：")
        val frames = try {
            Looper.getMainLooper().thread.stackTrace
        } catch (t: Throwable) {
            GLog.w("主线程看门狗：取栈失败", t)
            return
        }
        for (f in frames.take(MAX_FRAMES)) sb.append("\n    at ").append(f.toString())
        GLog.w(sb.toString())
    }
}
