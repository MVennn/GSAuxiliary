package io.github.MVennn.GSAuxiliary.feature.stgmerge

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [StrategyMergeTargets.countTier] 的纯 JVM 用例：家数三档（重数 / 缓存 / 累加）的取舍。
 *
 * 判定错了的代价是头部涨跌家数与列表对不上（真机 2026-11-13 抓到并集 778 只、
 * 头部却写「涨 581 / 跌 259」，因为 24 个成员的服务端家数被直接相加），所以这里逐条钉边界。
 */
class StrategyMergeCountsTest {

    private fun tier(mergedSize: Int, counted: Pair<Int, Int>?, sameAsCached: Boolean) =
        StrategyMergeTargets.countTier(mergedSize, counted, sameAsCached)

    @Test
    fun `重数覆盖满就用重数`() {
        assertEquals(StrategyMergeTargets.TIER_COUNT, tier(754, 535 to 211, sameAsCached = false))
    }

    @Test
    fun `重数优先于缓存沿用`() {
        // 并集一模一样，但缓存里存的是偏大的累加值 → 仍要用重数把它盖掉
        assertEquals(StrategyMergeTargets.TIER_COUNT, tier(754, 535 to 211, sameAsCached = true))
    }

    @Test
    fun `成员重叠时累加偏大也不采信累加`() {
        // 真机 11:32 那次：并集 778 只，成员累加 581+259=840，重数 535+211=746
        assertEquals(StrategyMergeTargets.TIER_COUNT, tier(778, 746 to 0, sameAsCached = false))
    }

    @Test
    fun `覆盖恰好八成仍算可信`() {
        assertEquals(StrategyMergeTargets.TIER_COUNT, tier(10, 5 to 3, sameAsCached = false))
    }

    @Test
    fun `覆盖不足八成且没有缓存就退回累加`() {
        assertEquals(StrategyMergeTargets.TIER_SUM, tier(10, 3 to 1, sameAsCached = false))
    }

    @Test
    fun `读不到涨跌幅但有缓存就沿上次的`() {
        assertEquals(StrategyMergeTargets.TIER_CACHE, tier(10, null, sameAsCached = true))
        assertEquals(StrategyMergeTargets.TIER_CACHE, tier(10, 2 to 0, sameAsCached = true))
    }

    @Test
    fun `空并集不采信重数`() {
        assertEquals(StrategyMergeTargets.TIER_SUM, tier(0, 0 to 0, sameAsCached = false))
        assertEquals(StrategyMergeTargets.TIER_CACHE, tier(0, null, sameAsCached = true))
    }
}