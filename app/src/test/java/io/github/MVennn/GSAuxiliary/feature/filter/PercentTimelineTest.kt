package io.github.MVennn.GSAuxiliary.feature.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [PercentTimeline] 的裁决真值表。
 *
 * 这份测试守的是「涨转跌能不能被剔除」这条用户可见的行为 —— 它不是缓存，是**筛选结果**。
 * 任何一侧的优先级被改动，用户看到的都是「筛了没筛一样」，而且不会报错、不会打日志。
 */
class PercentTimelineTest {

    @Test
    fun `两侧都没有样本时返回 null`() {
        val tl = PercentTimeline()
        assertNull(tl.resolve("600000.SH", null, 1_000L))
    }

    @Test
    fun `只有字段样本时用字段值`() {
        val tl = PercentTimeline()
        assertEquals(2.5, tl.resolve("600000.SH", 2.5, 1_000L))
    }

    @Test
    fun `只有文案样本时用文案值`() {
        val tl = PercentTimeline()
        assertTrue(tl.observeText("600000.SH", -1.5, 1_000L))
        assertEquals(-1.5, tl.resolve("600000.SH", null, 1_200L))
    }

    @Test
    fun `文案晚于字段时以文案为准 - 这就是涨转跌能被剔除的依据`() {
        val tl = PercentTimeline()
        // 字段是加载时的快照：涨 3.0
        assertEquals(3.0, tl.resolve("600000.SH", 3.0, 1_000L))
        // 行情原地刷文案：变成跌 2.1
        assertTrue(tl.observeText("600000.SH", -2.1, 2_000L))
        // 字段没变（还是 3.0，时间戳不刷新）→ 听文案 → 判成跌家
        assertEquals(-2.1, tl.resolve("600000.SH", 3.0, 3_000L))
    }

    @Test
    fun `字段值真的变了就立刻回到字段 - 跌转涨能回补`() {
        val tl = PercentTimeline()
        tl.resolve("600000.SH", 3.0, 1_000L)
        tl.observeText("600000.SH", -2.1, 2_000L)
        // 宿主真刷新了数据项：字段变成 1.8 → 时间戳晚于文案 → 听字段
        assertEquals(1.8, tl.resolve("600000.SH", 1.8, 3_000L))
    }

    @Test
    fun `被筛掉的行文案停更 判定粘住不飘回来`() {
        val tl = PercentTimeline()
        tl.resolve("600000.SH", 3.0, 1_000L)
        tl.observeText("600000.SH", -2.1, 2_000L)
        // 这一行已经被筛掉、不再 bind，文案不再更新；字段依旧没变
        assertEquals(-2.1, tl.resolve("600000.SH", 3.0, 9_000L))
        assertEquals(-2.1, tl.resolve("600000.SH", 3.0, 20_000L))
    }

    @Test
    fun `时间戳平手时取文案 - 屏幕上显示的那份优先`() {
        val tl = PercentTimeline()
        tl.observeText("600000.SH", -1.0, 5_000L)
        assertEquals(-1.0, tl.resolve("600000.SH", 2.0, 5_000L))
    }

    @Test
    fun `文案没变时不触发重算`() {
        val tl = PercentTimeline()
        assertTrue(tl.observeText("600000.SH", 1.0, 1_000L), "第一次采集算变化")
        assertFalse(tl.observeText("600000.SH", 1.0, 1_500L), "值没变不该让整份重算")
        assertTrue(tl.observeText("600000.SH", 1.1, 2_000L), "值变了才算变化")
    }

    @Test
    fun `字段读不到时沿用上一次字段样本 不被清空`() {
        val tl = PercentTimeline()
        assertEquals(5.0, tl.resolve("600000.SH", 5.0, 1_000L))
        // 这一轮反射没读到值（对象换了 / 字段为空）：不能把已知的判定抹成 null
        assertEquals(5.0, tl.resolve("600000.SH", null, 2_000L))
    }

    @Test
    fun `reset 之后回到空状态`() {
        val tl = PercentTimeline()
        tl.resolve("600000.SH", 3.0, 1_000L)
        tl.observeText("600000.SH", -2.1, 2_000L)
        tl.reset()
        assertNull(tl.resolve("600000.SH", null, 3_000L))
        assertEquals(1.0, tl.resolve("600000.SH", 1.0, 4_000L), "reset 后字段样本重新开始记")
    }

    @Test
    fun `不同代码各记各的`() {
        val tl = PercentTimeline()
        tl.observeText("600000.SH", -1.0, 1_000L)
        tl.observeText("000001.SZ", 2.0, 1_000L)
        assertEquals(-1.0, tl.resolve("600000.SH", null, 2_000L))
        assertEquals(2.0, tl.resolve("000001.SZ", null, 2_000L))
    }
}