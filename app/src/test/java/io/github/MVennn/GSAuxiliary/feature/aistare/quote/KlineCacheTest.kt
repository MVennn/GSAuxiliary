package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.QuoteBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [KlineCache] 的两条上限与命中判据（2026-11-14）。
 *
 * 时间用可变 [now] 喂进构造参数，不依赖 Thread.sleep —— 单测跑得快也不会偶发。
 */
class KlineCacheTest {

    private var now = 1_000L

    private fun bars(n: Int): List<QuoteBar> =
        List(n) { QuoteBar("2026-01-01", "1", "1", "1", "1") }

    private fun cache(maxEntries: Int = 16, maxBars: Int = 8_000) =
        KlineCache(maxEntries = maxEntries, maxBars = maxBars, clock = { now })

    @Test
    fun hitWithinTtlReturnsSameInstance() {
        val c = cache()
        val bars = bars(3)
        c.put("300006.1", 320, bars)
        now += KlineCache.DEFAULT_TTL_MS
        assertSame(bars, c.get("300006.1", 3))
    }

    @Test
    fun expiredEntryIsDroppedAndReloaded() {
        val c = cache()
        c.put("300006.1", 320, bars(4))
        now += KlineCache.DEFAULT_TTL_MS + 1
        assertNull(c.get("300006.1", 1))
        assertEquals(0, c.barCount)
        assertEquals(0, c.size)
    }

    @Test
    fun askedCountSatisfiesBiggerLaterRequest() {
        val c = cache()
        // 上次要了 2000 根，实际只回来 100 根（次新股）—— 后续要 1500 根不该再打接口
        c.put("688001.2", 2000, bars(100))
        val hit = c.get("688001.2", 1500)
        assertEquals(100, hit?.size)
    }

    @Test
    fun requestBeyondWhatWasAskedIsMiss() {
        val c = cache()
        // 上次问了 320 根、只回来 100 根（新股）——「问过这么多」本身就是有效信息
        c.put("688001.2", 320, bars(100))
        // 要 320 根仍算命中：再问一次也不会更多
        assertEquals(100, c.get("688001.2", 320)?.size)
        // 要得比上次问的还多，就必须重新打接口
        assertNull(c.get("688001.2", 401))
    }

    @Test
    fun evictsLeastRecentlyUsedBeyondEntryLimit() {
        val c = cache(maxEntries = 2)
        c.put("a", 320, bars(1))
        c.put("b", 320, bars(1))
        // 摸一下 a，让 b 变成「最久没用」
        assertEquals(1, c.get("a", 1)?.size)
        c.put("c", 320, bars(1))
        assertEquals(2, c.size)
        assertNull(c.get("b", 1))
        assertEquals(1, c.get("a", 1)?.size)
        assertEquals(1, c.get("c", 1)?.size)
    }

    @Test
    fun barsBudgetEvictsOldest() {
        val c = cache(maxEntries = 16, maxBars = 10)
        c.put("a", 320, bars(6))
        c.put("b", 320, bars(6))
        // 6 + 6 超了 10 根预算 → 最久没用的 a 被淘汰，只剩 b
        assertEquals(6, c.barCount)
        assertNull(c.get("a", 1))
        assertEquals(6, c.get("b", 1)?.size)
    }

    @Test
    fun replacingEntryReleasesItsOldBudget() {
        val c = cache(maxEntries = 16, maxBars = 10)
        c.put("a", 320, bars(6))
        c.put("a", 320, bars(4))
        assertEquals(4, c.barCount)
        assertEquals(1, c.size)
    }

    @Test
    fun clearResetsEntriesAndBudget() {
        val c = cache()
        c.put("a", 320, bars(5))
        c.clear()
        assertEquals(0, c.size)
        assertEquals(0, c.barCount)
        assertNull(c.get("a", 1))
    }
}
