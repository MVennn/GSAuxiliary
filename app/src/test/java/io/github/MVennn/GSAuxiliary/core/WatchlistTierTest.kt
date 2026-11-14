package io.github.MVennn.GSAuxiliary.core

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 涨 / 跌 / 平三档判定的**边界**。
 *
 * 判错一档的后果不是「少染一个颜色」，而是整列颜色反着来（涨的显示成跌的色）——
 * 所以每个入口的四种输入（正 / 负 / 零 / 读不出）都要钉住。
 *
 * 关键约定：**读不出来返回 null，不是平盘**。否则字段缺失、文案还是 `--` 的那一行
 * 会被当成平盘，用户看到的是一列「平盘色」，而真相是「还不知道」。
 */
class WatchlistTierTest {

    @Test
    fun 三档顺序固定涨跌平() {
        assertEquals(listOf("up", "down", "flat"), WatchlistTier.ALL)
    }

    @Test
    fun 文案带百分号正数判涨() {
        assertEquals(WatchlistTier.UP, WatchlistTier.ofText("+2.34%"))
        assertEquals(WatchlistTier.UP, WatchlistTier.ofText("2.34%"))
    }

    @Test
    fun 文案带百分号负数判跌() {
        assertEquals(WatchlistTier.DOWN, WatchlistTier.ofText("-1.12%"))
        assertEquals(WatchlistTier.DOWN, WatchlistTier.ofText("-0.01%"))
    }

    @Test
    fun 文案零判平() {
        assertEquals(WatchlistTier.FLAT, WatchlistTier.ofText("0.00%"))
        assertEquals(WatchlistTier.FLAT, WatchlistTier.ofText("-0.00%"))
        assertEquals(WatchlistTier.FLAT, WatchlistTier.ofText("0"))
    }

    @Test
    fun 千分位与空白的写法也认() {
        assertEquals(WatchlistTier.UP, WatchlistTier.ofText(" 1,234 "))
        assertEquals(WatchlistTier.DOWN, WatchlistTier.ofText("-1,234.50%"))
    }

    @Test
    fun 读不出来的文案返回null而不是平盘() {
        assertNull(WatchlistTier.ofText(null))
        assertNull(WatchlistTier.ofText(""))
        assertNull(WatchlistTier.ofText("   "))
        assertNull(WatchlistTier.ofText("--"))
        assertNull(WatchlistTier.ofText("%"))
        assertNull(WatchlistTier.ofText("停牌"))
    }

    @Test
    fun 数值字段按正负判档() {
        assertEquals(WatchlistTier.UP, WatchlistTier.ofZdf(BigDecimal("2.34")))
        assertEquals(WatchlistTier.DOWN, WatchlistTier.ofZdf(BigDecimal("-0.12")))
        assertEquals(WatchlistTier.FLAT, WatchlistTier.ofZdf(BigDecimal("0.00")))
        assertEquals(WatchlistTier.UP, WatchlistTier.ofZdf(3))
        assertEquals(WatchlistTier.DOWN, WatchlistTier.ofZdf(-3.5))
    }

    @Test
    fun 字段值是字符串时走文案口径() {
        assertEquals(WatchlistTier.UP, WatchlistTier.ofZdf("+2.34%"))
        assertEquals(WatchlistTier.DOWN, WatchlistTier.ofZdf("-1.12%"))
        assertNull(WatchlistTier.ofZdf(""))
        assertNull(WatchlistTier.ofZdf(null))
    }

    @Test
    fun 字段不是数字时返回null() {
        assertNull(WatchlistTier.ofZdf(Any()))
    }
}
