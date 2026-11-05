package io.github.MVennn.GSAuxiliary.feature.stgsnap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「挂起时长不计入就绪超时」的判据。 */
class SnapPauseWindowTest {

    @Test
    fun `活跃时间走满才过期`() {
        val w = SnapPauseWindow(1000L)
        w.start(0)
        assertFalse(w.expired(999))
        assertTrue(w.expired(1000))
        assertEquals(0L, w.remainingMs(1200))
    }

    @Test
    fun `挂起期间永不过期`() {
        val w = SnapPauseWindow(1000L)
        w.start(0)
        assertTrue(w.pause(300))
        assertFalse(w.expired(60_000))
        assertTrue(w.isPaused)
        // 挂起中 remaining 冻结在挂起那一刻的值
        assertEquals(700L, w.remainingMs(60_000))
    }

    @Test
    fun `恢复后接着用剩余时间`() {
        val w = SnapPauseWindow(1000L)
        w.start(0)
        w.pause(300)
        assertEquals(10_000L, w.resume(10_300))
        assertFalse(w.isPaused)
        // 活跃 300 + 恢复后 699 = 999，还没到
        assertFalse(w.expired(10_999))
        assertTrue(w.expired(11_000))
    }

    @Test
    fun `重复 pause 只认第一次 resume 一次`() {
        val w = SnapPauseWindow(500L)
        w.start(0)
        assertTrue(w.pause(100))
        assertFalse(w.pause(200))
        assertEquals(100L, w.resume(200))
        assertEquals(0L, w.resume(300))
        assertEquals(100L, w.activeMs(200))
    }

    @Test
    fun `未 start 不报错`() {
        val w = SnapPauseWindow(500L)
        assertFalse(w.expired(10_000))
        assertEquals(0L, w.activeMs(10_000))
    }
}
