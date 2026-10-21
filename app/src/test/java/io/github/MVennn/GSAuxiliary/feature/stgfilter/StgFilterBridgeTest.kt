package io.github.MVennn.GSAuxiliary.feature.stgfilter

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 筛选结果通道的下标表、列表绑定与「合成点击放行」标记。
 *
 * ## 为什么重要
 *
 * 筛选生效时，ListView 上的 position 是**子集下标**，而宿主的多选模型（以及
 * `AbsListView.performItemClick` 写下的 checked states）用的是**全量下标**。
 * 「全选」必须用 [StgFilterBridge.fullIndexAt] 把子集下标换回全量下标，
 * 再在 [StgFilterBridge.runWithRemapBypassed] 里调 `performItemClick`；
 * 映射错了 = 用户勾着 A/B/C，确认添加的却是另外三只（2026-10-20 真机事故）。
 *
 * ## 为什么还要绑定列表实例与代理
 *
 * 这个 object 是进程级单例，快照会活过页面切换。只按元素身份匹配的话，
 * 宿主复用同一批 `StockInfo` 时，上一页的筛选结果会套到这一页的翻页列表上
 * （2026-10-21）。所以快照记下**算出它的那个全量列表**与**列表上的过滤代理**：
 * `keepOnly` 只认 `===` 的列表，`fullIndexAt` / `fullIndices` 可以按代理归属校验。
 */
class StgFilterBridgeTest {

    private val full = arrayListOf("a", "b", "c", "d")
    private val proxy = Any()

    @BeforeTest
    fun clear() {
        StgFilterBridge.publish(null)
    }

    @AfterTest
    fun tearDown() {
        StgFilterBridge.publish(null)
    }

    @Test
    fun `没有筛选结果时 fullIndexAt 返回 null`() {
        assertNull(StgFilterBridge.fullIndexAt(0))
    }

    @Test
    fun `fullIndexAt 按子集下标返回全量下标`() {
        StgFilterBridge.publish(listOf("a", "c"), intArrayOf(9, 16), full, proxy)
        assertEquals(9, StgFilterBridge.fullIndexAt(0))
        assertEquals(16, StgFilterBridge.fullIndexAt(1))
    }

    @Test
    fun `fullIndexAt 越界返回 null`() {
        StgFilterBridge.publish(listOf("a"), intArrayOf(3), full, proxy)
        assertNull(StgFilterBridge.fullIndexAt(-1))
        assertNull(StgFilterBridge.fullIndexAt(1))
    }

    @Test
    fun `传了 owner 时只认这个代理发布的快照`() {
        StgFilterBridge.publish(listOf("a", "c"), intArrayOf(0, 2), full, proxy)
        assertEquals(0, StgFilterBridge.fullIndexAt(0, proxy))
        assertTrue(StgFilterBridge.isFor(proxy))
        assertNull(StgFilterBridge.fullIndexAt(0, Any()))
        assertFalse(StgFilterBridge.isFor(Any()))
    }

    @Test
    fun `fullIndices 要求长度与列表行数一致`() {
        StgFilterBridge.publish(listOf("a", "c"), intArrayOf(0, 2), full, proxy)
        assertTrue(intArrayOf(0, 2).contentEquals(StgFilterBridge.fullIndices(2, proxy)))
        // 列表行数与快照不等 → 不认（混用两套坐标会点到别的股票）
        assertNull(StgFilterBridge.fullIndices(3, proxy))
        assertNull(StgFilterBridge.fullIndices(0, proxy))
        assertNull(StgFilterBridge.fullIndices(2, Any()))
    }

    @Test
    fun `只发布 kept 没有下标表时视为没筛`() {
        StgFilterBridge.publish(listOf("a", "b"), null, full)
        assertNull(StgFilterBridge.fullIndexAt(0))
        assertFalse(StgFilterBridge.hasSnapshot())
    }

    @Test
    fun `kept 非空但没有来源列表时视为没筛`() {
        // 没有 source 就无从判断这份结果属于哪个列表 —— 宁可退化成全量
        StgFilterBridge.publish(listOf("a"), intArrayOf(0))
        assertNull(StgFilterBridge.fullIndexAt(0))
        assertFalse(StgFilterBridge.hasSnapshot())
    }

    @Test
    fun `keepOnly 只认算出快照的同一个列表实例`() {
        StgFilterBridge.publish(listOf("a", "c"), intArrayOf(0, 2), full, proxy)
        assertTrue(StgFilterBridge.hasSnapshot())

        assertEquals(listOf<Any>("a", "c"), StgFilterBridge.keepOnly(full))
        // 内容相同、但不是同一个列表（换页 / 列表重建 / Bundle 副本）→ 不套用
        assertNull(StgFilterBridge.keepOnly(arrayListOf("a", "b", "c")))
        // 即使元素是同一批对象，只要列表实例不同也不套用
        val sameObjects = arrayListOf<Any>(full[0], full[1], full[2])
        assertNull(StgFilterBridge.keepOnly(sameObjects))
    }

    @Test
    fun `keepOnly 保持全量列表里的顺序`() {
        val source = listOf("c", "a", "b")
        StgFilterBridge.publish(listOf("a", "b"), intArrayOf(1, 2), source, proxy)
        assertEquals(listOf<Any>("a", "b"), StgFilterBridge.keepOnly(source))
    }

    @Test
    fun `keepOnly 对空列表返回 null`() {
        StgFilterBridge.publish(listOf("a"), intArrayOf(0), full, proxy)
        assertNull(StgFilterBridge.keepOnly(null))
        assertNull(StgFilterBridge.keepOnly(emptyList<Any>()))
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
