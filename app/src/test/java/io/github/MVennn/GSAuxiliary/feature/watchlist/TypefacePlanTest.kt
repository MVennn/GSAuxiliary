package io.github.MVennn.GSAuxiliary.feature.watchlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 「加粗 / 还原字重」的决策 —— **记原字重的时机**是这个功能最隐蔽的坑。
 *
 * ## 这条测试为什么重要
 *
 * RecyclerView 复用行视图：同一个 TextView 会被绑上不同的股票。
 * 绑定序列通常是「非自选 → 自选 → 非自选」：
 *
 *  - 第一次（非自选）→ 记下原字重；
 *  - 第二次（自选）→ 加粗；
 *  - 第三次（非自选）→ **还原**成第一次记的那个。
 *
 * 如果第二次加粗时**覆盖**了记录（记下了「加粗后的」那个字重），
 * 第三次还原就还原不回普通字重 → **那一行之后一直是粗的**。
 *
 * 症状在真机上表现为「某个股票名怎么一直是粗的」—— 肉眼能看出，
 * 但日志里什么都没有，而且只在滚动到特定位置时出现，极难复现。
 */
class TypefacePlanTest {

    private fun decide(
        current: Int?,
        recorded: Int?,
        wantBold: Boolean,
    ) = TypefacePlan.decide(current, recorded, wantBold)

    // ------------------------------------------------------------------ 加粗

    @Test
    fun `第一次加粗 要顺手记下原字重`() {
        val d = decide(current = TypefacePlan.STYLE_NORMAL, recorded = null, wantBold = true)
        assertIs<TypefacePlan.Decision.Set>(d)
        assertTrue(d.recordOriginal, "第一次加粗必须记下原字重，否则还原不回去")
        assertEquals(TypefacePlan.STYLE_BOLD, d.style)
    }

    @Test
    fun `已经加粗过 不能再覆盖原字重`() {
        // 第二次绑定同一行（已加粗）→ 原字重**不能**被「加粗后的」覆盖
        val d = decide(current = TypefacePlan.STYLE_NORMAL, recorded = TypefacePlan.STYLE_NORMAL, wantBold = true)
        assertIs<TypefacePlan.Decision.Set>(d)
        assertTrue(!d.recordOriginal, "已经有原字重了，不能再记一次（会记下加粗后的）")
    }

    @Test
    fun `当前已经是加粗态 绝不能把加粗后的记成原字重`() {
        // ⚠️ **这条是 2026-10-20 变异测试补上的**（原来没有，守卫有洞）：
        // 视图已经是**加粗后的**状态（RecyclerView 复用 / 热重载后新实例接手），
        // 这时如果把当前字重记成「原字重」，还原时就会撤不回普通字重 ——
        // 症状是「关掉加粗开关，那些名字还一直粗着」。
        val d = decide(current = TypefacePlan.STYLE_BOLD, recorded = null, wantBold = true)
        assertIs<TypefacePlan.Decision.Set>(d)
        assertTrue(
            !d.recordOriginal,
            "当前已经是加粗态，绝不能把它记成原字重 —— 否则还原时撤不回去",
        )
    }

    @Test
    fun `当前已经是加粗态 不用再设`() {
        val d = decide(current = TypefacePlan.STYLE_BOLD, recorded = TypefacePlan.STYLE_NORMAL, wantBold = true)
        assertIs<TypefacePlan.Decision.Set>(d)
        assertEquals(TypefacePlan.STYLE_BOLD, d.style)
        assertTrue(!d.recordOriginal, "已经是加粗态了，不能把它当成原字重记下来")
    }

    @Test
    fun `读不到当前字重时 不记也不设`() {
        val d = decide(current = null, recorded = null, wantBold = true)
        assertEquals(TypefacePlan.Decision.None, d, "读不到字重就什么都别做")
    }

    // ------------------------------------------------------------------ 还原

    @Test
    fun `还原用记下来的那个 而不是当前的`() {
        val d = decide(current = TypefacePlan.STYLE_BOLD, recorded = TypefacePlan.STYLE_NORMAL, wantBold = false)
        assertIs<TypefacePlan.Decision.Restore>(d)
        assertEquals(
            TypefacePlan.STYLE_NORMAL,
            d.style,
            "必须还原成**记下来的**那个（当前的已经是加粗后的了）",
        )
    }

    @Test
    fun `没记过原字重就没法还原 - 什么都不做`() {
        val d = decide(current = TypefacePlan.STYLE_BOLD, recorded = null, wantBold = false)
        assertEquals(
            TypefacePlan.Decision.None,
            d,
            "没记过原字重时不能瞎还原（那会把行设成某个随便的样式）",
        )
    }

    @Test
    fun `已经是原字重了 不用动`() {
        val d = decide(current = TypefacePlan.STYLE_NORMAL, recorded = TypefacePlan.STYLE_NORMAL, wantBold = false)
        assertEquals(TypefacePlan.Decision.None, d)
    }

    // ------------------------------------------------------------------ 完整序列

    @Test
    fun `完整序列 非自选→自选→非自选 还原回普通字重`() {
        // 第一次：非自选（不处理 → 没记过原字重）
        // 第二次：自选 → 加粗，**记下原字重**
        val second = decide(TypefacePlan.STYLE_NORMAL, null, wantBold = true)
        assertIs<TypefacePlan.Decision.Set>(second)
        assertTrue(second.recordOriginal)
        val recorded = TypefacePlan.STYLE_NORMAL   // 记下来了

        // 第三次：又是非自选 → 还原成 recorded
        val third = decide(TypefacePlan.STYLE_BOLD, recorded, wantBold = false)
        assertIs<TypefacePlan.Decision.Restore>(third)
        assertEquals(TypefacePlan.STYLE_NORMAL, third.style)
    }

    @Test
    fun `连续两次加粗 记的原字重不变`() {
        var recorded: Int? = null
        // 第一次
        var d = decide(TypefacePlan.STYLE_NORMAL, recorded, wantBold = true)
        assertIs<TypefacePlan.Decision.Set>(d)
        if (d.recordOriginal) recorded = TypefacePlan.STYLE_NORMAL
        // 第二次（视图还没换）
        d = decide(TypefacePlan.STYLE_BOLD, recorded, wantBold = true)
        assertIs<TypefacePlan.Decision.Set>(d)
        assertTrue(!d.recordOriginal, "第二次不能再记")
        assertEquals(TypefacePlan.STYLE_NORMAL, recorded, "记的原字重应当没变")
    }
}
