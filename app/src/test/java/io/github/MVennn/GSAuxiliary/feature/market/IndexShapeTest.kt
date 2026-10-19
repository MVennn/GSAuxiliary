package io.github.MVennn.GSAuxiliary.feature.market

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「指数概览」的结构判据 —— **认错版块的代价很大**。
 *
 * ## 这条测试为什么重要
 *
 * `MarketIndexHideFeature` 的兜底判据要回答「这个横向多格的列表是不是指数概览」。
 * 认错的两种后果：
 *
 *  - **认漏了** → 功能不生效（用户以为开关坏了）；
 *  - **认错了** → **收起了不该收的版块** —— 用户看到行情页少了东西，
 *    而这种错在真机上**只能靠肉眼比对才能发现**，日志里看不出来。
 *
 * 所以把判据的四个条件逐条钉死：每一条单独改动都该被测出来。
 */
class IndexShapeTest {

    private fun ok(
        childCount: Int = 4,
        labeledCount: Int = 4,
        entryItemCount: Int = 0,
        totalHeightDp: Int = 88,
    ) = IndexShape.looksLikeIndexOverview(
        childCount = childCount,
        labeledCount = labeledCount,
        entryItemCount = entryItemCount,
        totalHeightDp = totalHeightDp,
    )

    // ------------------------------------------------------------------ 认得对

    @Test
    fun `实测形状 认得出`() {
        // 指数概览实测：4 项（`#ll_content` ×4）、每项都有名字、整块 229px ≈ 88dp
        assertTrue(ok(), "实测形状（4 项 / 有文案 / 不是入口格 / 88dp）应当认得出")
    }

    @Test
    fun `量不出高度时也认得出 - 别因为量不到就否掉`() {
        assertTrue(ok(totalHeightDp = 0), "还没量出高度时不该直接否掉")
    }

    @Test
    fun `允许有一项没有文案 - 最后一格可能是纯图标`() {
        assertTrue(ok(childCount = 4, labeledCount = 3), "4 项里 3 项有文案应当认得出")
    }

    // ------------------------------------------------------------------ 认得错

    @Test
    fun `子项太少 认不出`() {
        assertFalse(ok(childCount = 2, labeledCount = 2), "只有 2 项不该认成指数概览")
    }

    @Test
    fun `过半没有文案 认不出`() {
        assertFalse(
            ok(childCount = 4, labeledCount = 2),
            "4 项里只有 2 项有文案 —— 那不是指数概览（指数名都在）",
        )
    }

    @Test
    fun `几乎每项都是图标加文字 认不出 - 那是快捷入口`() {
        // 快捷入口实测：`#rv_entra` 5 格，每格「图标 + 文字」
        assertFalse(
            ok(childCount = 5, labeledCount = 5, entryItemCount = 5),
            "每格都是「图标 + 文字」的是快捷入口，不该认成指数概览",
        )
    }

    @Test
    fun `整块太高 认不出 - 那是板块区那种大块`() {
        // 板块区实测 559px ≈ 215dp，指数概览只有 88dp
        assertFalse(ok(totalHeightDp = 215), "215dp 的大块不是指数概览")
    }

    @Test
    fun `高度正好在阈值上 认得出 - 阈值是上界不是恰好等于`() {
        assertTrue(
            ok(totalHeightDp = MarketTargets.MAX_INDEX_OVERVIEW_HEIGHT_DP),
            "高度等于阈值时应当认得出（阈值是「不超过」）",
        )
        assertFalse(
            ok(totalHeightDp = MarketTargets.MAX_INDEX_OVERVIEW_HEIGHT_DP + 1),
            "比阈值大 1dp 就不该认得出了",
        )
    }

    @Test
    fun `一个条件不满足就不能认 - 四条是与的关系`() {
        // 逐条把每个条件单独破坏，都该认不出
        assertFalse(ok(childCount = 1), "条件①：子项数")
        assertFalse(ok(childCount = 4, labeledCount = 0), "条件②：有文案的项数")
        assertFalse(ok(childCount = 4, entryItemCount = 4), "条件③：入口格数")
        assertFalse(ok(totalHeightDp = 999), "条件④：整块高度")
    }
}
