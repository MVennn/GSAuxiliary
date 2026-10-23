package io.github.MVennn.GSAuxiliary.feature.strategyquote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 策略详情「直达标准行情」的下标映射。
 *
 * ## 这条测试为什么重要
 *
 * 行情侧要的是「点第 N 只 → 详情页翻到第 N 只」。正常路径靠对象引用（`===`）在转换时
 * 记下标，没问题；但引用没命中时的兜底路径历史上用的是**原始列表下标**去索引
 * **转换后的列表** —— 而 `toIntentStockInfo` 失败的元素会被跳过，两个列表不等长。
 *
 * 后果是「不崩，但进错股」：筛选生效、又有元素转换失败时，点第 N 只会打开另一只。
 * 这类坐标错位在本仓反复出现过（3.3.0 / 3.3.1 两轮都在修），所以映射逻辑单独拆出来
 * 用纯函数测，不再依赖反射与宿主对象。
 */
class DirectQuoteIndexTest {

    @Test
    fun `全部转换成功时下标与原始顺序一致`() {
        val map = DirectQuoteIndex.build(
            listOf(
                "600000.1" to true,
                "000001.2" to true,
                "300750.2" to true,
            )
        )
        assertEquals(0, map["600000.1"])
        assertEquals(1, map["000001.2"])
        assertEquals(2, map["300750.2"])
    }

    @Test
    fun `中间有元素转换失败时后续下标前移`() {
        // 原始第 2 项转换失败被跳过 → 原始第 3 项在 converted 里是 1
        val map = DirectQuoteIndex.build(
            listOf(
                "600000.1" to true,
                "000001.2" to false,
                "300750.2" to true,
            )
        )
        assertEquals(0, map["600000.1"])
        assertNull(map["000001.2"], "转换失败的项不应有 converted 下标")
        assertEquals(1, map["300750.2"], "跳过一项后，下一项应落在 1 而不是 2")
    }

    @Test
    fun `点击项本身转换失败时映射里没有它`() {
        val map = DirectQuoteIndex.build(
            listOf(
                "600000.1" to false,
                "000001.2" to true,
            )
        )
        assertNull(map["600000.1"])
        assertEquals(0, map["000001.2"])
    }

    @Test
    fun `空列表与空代码都不崩`() {
        assertTrue(DirectQuoteIndex.build(emptyList()).isEmpty())
        val map = DirectQuoteIndex.build(
            listOf(
                null to true,
                "600000.1" to true,
            )
        )
        assertEquals(1, map["600000.1"], "空代码项也占一个 converted 位置")
    }

    @Test
    fun `全部转换失败时映射为空`() {
        val map = DirectQuoteIndex.build(
            listOf(
                "600000.1" to false,
                "000001.2" to false,
            )
        )
        assertTrue(map.isEmpty())
    }
}
