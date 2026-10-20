package io.github.MVennn.GSAuxiliary.feature.stgfilter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 筛选结果通道的下标表与「合成点击放行」标记。
 *
 * ## 为什么重要
 *
 * 筛选生效时，ListView 上的 position 是**子集下标**，而宿主的多选模型（以及
 * `AbsListView.performItemClick` 写下的 checked states）用的是**全量下标**。
 * 「全选」必须用 [StgFilterBridge.fullIndexAt] 把子集下标换回全量下标，
 * 再在 [StgFilterBridge.runWithRemapBypassed] 里调 `performItemClick`；
 * 映射错了 = 用户勾着 A/B/C，确认添加的却是另外三只（2026-10-20 真机事故）。
 */
class StgFilterBridgeTest {

    @Test
    fun `没有筛选结果时 fullIndexAt 返回 null`() {
        StgFilterBridge.publish(null)
        assertNull(StgFilterBridge.fullIndexAt(0))
    }

    @Test
    fun `fullIndexAt 按子集下标返回全量下标`() {
        StgFilterBridge.publish(listOf("a", "b", "c"), intArrayOf(9, 10, 16))
        assertEquals(9, StgFilterBridge.fullIndexAt(0))
        assertEquals(10, StgFilterBridge.fullIndexAt(1))
        assertEquals(16, StgFilterBridge.fullIndexAt(2))
    }

    @Test
    fun `fullIndexAt 越界返回 null`() {
        StgFilterBridge.publish(listOf("a"), intArrayOf(3))
        assertNull(StgFilterBridge.fullIndexAt(-1))
        assertNull(StgFilterBridge.fullIndexAt(1))
    }

    @Test
    fun `只发布 kept 没有下标表时视为没筛`() {
        StgFilterBridge.publish(listOf("a", "b"))
        assertNull(StgFilterBridge.fullIndexAt(0))
    }

    @Test
    fun `放行标记只在块内为真且异常时也会恢复`() {
        assertFalse(StgFilterBridge.isRemapBypassed())
        StgFilterBridge.runWithRemapBypassed {
            assertTrue(StgFilterBridge.isRemapBypassed())
        }
        assertFalse(StgFilterBridge.isRemapBypassed())

        runCatching {
            StgFilterBridge.runWithRemapBypassed { throw IllegalStateException("boom") }
        }
        assertFalse(StgFilterBridge.isRemapBypassed())
    }
}
