package io.github.MVennn.GSAuxiliary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [BoundedTtlCache] 的三条硬性质：有界、短时、命中不重复求值。
 *
 * 这条缓存挡在自选判定这条反射路径前面
 * （[io.github.MVennn.GSAuxiliary.feature.watchlist.WatchlistMembership]）。
 * 搞错任何一条的后果都不是崩溃，而是「静默的不准」或「缓慢的内存增长」——
 * 两类都只能靠单测守，手点点不出来。
 */
class BoundedTtlCacheTest {

    @Test
    fun hitDoesNotReevaluate() {
        var now = 1_000L
        var calls = 0
        val c = BoundedTtlCache<String, Int>(maxEntries = 8, ttlMs = 2_000L) { now }
        fun q(k: String): Int = c.get(k) ?: run {
            calls++
            (calls * 10).also { c.put(k, it) }
        }

        assertEquals(10, q("a"))
        assertEquals(10, q("a"))
        assertEquals(1, calls, "有效期内同一个 key 只该求值一次")
    }

    @Test
    fun expiredEntryIsReevaluated() {
        var now = 1_000L
        var calls = 0
        val c = BoundedTtlCache<String, Int>(maxEntries = 8, ttlMs = 2_000L) { now }
        fun q(k: String): Int = c.get(k) ?: run {
            calls++
            (calls * 10).also { c.put(k, it) }
        }

        q("a")
        now += 2_001L
        assertEquals(20, q("a"), "过了有效期就该重新求值，缓存不能永久错")
        assertEquals(2, calls)
    }

    @Test
    fun exactTtlStillHits() {
        var now = 1_000L
        var calls = 0
        val c = BoundedTtlCache<String, Int>(maxEntries = 8, ttlMs = 2_000L) { now }
        fun q(k: String): Int = c.get(k) ?: run {
            calls++
            (calls * 10).also { c.put(k, it) }
        }

        q("a")
        now += 2_000L
        q("a")
        assertEquals(1, calls, "刚好等于 ttl 仍算命中（判据是大于才算过期）")
    }

    @Test
    fun sizeIsBounded() {
        var now = 0L
        val c = BoundedTtlCache<String, Int>(maxEntries = 3, ttlMs = 60_000L) { now }
        for (i in 1..50) {
            c.put("k$i", i)
            now++
        }
        assertEquals(3, c.size, "超过上限必须淘汰，不能无限涨")
    }

    @Test
    fun recentKeySurvivesEviction() {
        var now = 0L
        val c = BoundedTtlCache<String, Int>(maxEntries = 2, ttlMs = 60_000L) { now }
        c.put("a", 1)
        c.put("b", 2)
        assertEquals(1, c.get("a"), "读一次 a，让它变成最近使用")
        now++
        c.put("c", 3)
        assertEquals(1, c.get("a"), "a 刚用过，不该被淘汰")
        assertNull(c.get("b"), "b 最久没用，应该被淘汰")
    }

    @Test
    fun clearForcesReevaluation() {
        var now = 0L
        val c = BoundedTtlCache<String, Int>(maxEntries = 8, ttlMs = 60_000L) { now }
        c.put("a", 1)
        c.clear()
        assertEquals(0, c.size)
        assertNull(c.get("a"))
    }
}