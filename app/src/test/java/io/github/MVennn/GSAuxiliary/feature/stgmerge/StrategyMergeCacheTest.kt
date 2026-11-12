package io.github.MVennn.GSAuxiliary.feature.stgmerge

import java.lang.ref.WeakReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [StrategyMergeCache] 的纯 JVM 用例：新鲜度、覆盖、淘汰、弱引用被回收。
 *
 * 缓存错了的代价是「界面停在旧的并集」，所以这里逐条钉住边界。
 */
class StrategyMergeCacheTest {

    private fun snap(ids: List<String>, at: Long = 1_000L, up: Int = 0, down: Int = 0) =
        StrategyMergeCache.Snapshot(
            key = "k",
            ids = ids,
            ref = WeakReference(ids.map { Any() }),
            up = up,
            down = down,
            label = "聚合 · 组（${ids.size} 条）",
            tags = emptyList(),
            at = at,
        )

    @Test
    fun `有效期内的快照算新鲜`() {
        val s = snap(listOf("a", "b"), at = 1_000L)
        assertTrue(s.isFresh(1_000L, 10_000L))
        assertTrue(s.isFresh(11_000L, 10_000L))
    }

    @Test
    fun `超出有效期就不新鲜`() {
        val s = snap(listOf("a"), at = 1_000L)
        assertTrue(!s.isFresh(11_001L, 10_000L))
    }

    @Test
    fun `时间戳在未来按不新鲜处理`() {
        val s = snap(listOf("a"), at = 5_000L)
        assertTrue(!s.isFresh(1_000L, 10_000L), "时钟回拨时不能把旧结果当新结果")
    }

    @Test
    fun `put 后能取回同一批票`() {
        StrategyMergeCache.clear()
        val merged = listOf<Any>(Any(), Any())
        StrategyMergeCache.put("g1", merged, listOf("600000.1", "000001.2"), 7, 3, "聚合 · 组（2 条）", listOf("大盘"), now = 1_000L)
        val got = StrategyMergeCache.get("g1", now = 1_000L, ttlMs = 10_000L)
        assertNotNull(got)
        assertEquals(2, got.size)
        assertEquals(7, got.up)
        assertEquals(3, got.down)
        assertEquals(listOf("600000.1", "000001.2"), got.ids)
        assertEquals(2, got.merged()?.size)
    }

    @Test
    fun `同一个 key 后写的覆盖先写的`() {
        StrategyMergeCache.clear()
        StrategyMergeCache.put("g", listOf<Any>(Any()), listOf("a"), 1, 0, "L1", emptyList(), now = 1_000L)
        StrategyMergeCache.put("g", listOf<Any>(Any(), Any()), listOf("a", "b"), 2, 0, "L2", emptyList(), now = 2_000L)
        val got = StrategyMergeCache.get("g", now = 2_000L, ttlMs = 10_000L)
        assertNotNull(got)
        assertEquals(2, got.size)
        assertEquals("L2", got.label)
    }

    @Test
    fun `过期的缓存取不到并顺手删掉`() {
        StrategyMergeCache.clear()
        StrategyMergeCache.put("g", listOf<Any>(Any()), listOf("a"), 1, 0, "L", emptyList(), now = 1_000L)
        assertNull(StrategyMergeCache.get("g", now = 99_000L, ttlMs = 10_000L))
        assertEquals(0, StrategyMergeCache.size())
    }

    @Test
    fun `实例被回收就当没缓存`() {
        StrategyMergeCache.clear()
        var merged: List<Any>? = listOf(Any())
        StrategyMergeCache.put("g", merged!!, listOf("a"), 1, 0, "L", emptyList(), now = 1_000L)
        merged = null
        System.gc()
        // GC 不保证当场回收；这里只断言「取不到时不会抛，且返回 null 或有效快照」
        val got = StrategyMergeCache.get("g", now = 1_000L, ttlMs = 10_000L)
        if (got != null) assertNotNull(got.merged()) else assertEquals(0, StrategyMergeCache.size())
    }

    @Test
    fun `超过上限淘汰最久没用的那组`() {
        StrategyMergeCache.clear()
        for (i in 1..9) {
            StrategyMergeCache.put("g$i", listOf<Any>(Any()), listOf("a"), 1, 0, "L$i", emptyList(), now = 1_000L + i)
        }
        assertEquals(8, StrategyMergeCache.size())
        assertNull(StrategyMergeCache.get("g1", now = 1_010L, ttlMs = 10_000L), "最早那组应被淘汰")
        assertNotNull(StrategyMergeCache.get("g9", now = 1_010L, ttlMs = 10_000L))
    }

    @Test
    fun `sameAs 认同一批票且认顺序`() {
        val s = snap(listOf("a", "b"))
        assertTrue(s.sameAs(listOf("a", "b")))
        assertTrue(!s.sameAs(listOf("b", "a")), "顺序变了也算不同：界面顺序跟着它走")
        assertTrue(!s.sameAs(listOf("a")))
    }

}
