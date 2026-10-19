package io.github.MVennn.GSAuxiliary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 自选股高亮的**预设色** —— 「原色判定」的唯一依据。
 *
 * ## 这条测试为什么重要
 *
 * `WatchlistFeature.originalColor` 的判定是「当前色是不是我们染上去的预设色」：
 * 是 → 那它不是宿主的原色；不是 → 那它就是原色，记下来。
 *
 * 判错的后果：
 *
 *  - **把宿主自己的颜色误判成预设色** → 还原时退回硬编码兜底色
 *    （`FALLBACK_NORMAL_COLOR = 0xFF212634`，深色模式下的深灰），
 *    **宿主自己的颜色被顶掉**；
 *  - **把预设色误判成原色** → 记下的是「我们染过的色」，
 *    还原时撤不回宿主原本的颜色。
 *
 * 而「原色」只在**第一次**见到这个 TextView 时才有意义 ——
 * 所以预设色表一旦与实际使用的高亮色脱节，还原就会开始出错。
 */
class WatchlistColorPresetsTest {

    @Test
    fun `预设色表不为空 且互不相同`() {
        assertTrue(ConfigKeys.PRESET_COLORS.isNotEmpty(), "预设色表不能为空")
        assertEquals(
            ConfigKeys.PRESET_COLORS.size,
            ConfigKeys.PRESET_COLORS.toSet().size,
            "预设色表里有重复的颜色 —— 判据会含糊",
        )
    }

    @Test
    fun `默认高亮色 在预设色表里`() {
        // 默认高亮色必须能被「这是我们染的」判定认出来，
        // 否则第一次记原色时会把高亮色当成宿主原色记下来。
        assertTrue(
            ConfigKeys.PRESET_COLORS.contains(ConfigKeys.DEFAULT_COLOR),
            "默认高亮色 ${ConfigKeys.DEFAULT_COLOR.toUInt().toString(16)} " +
                "不在预设色表里 —— 原色判定会认不出它",
        )
    }

    @Test
    fun `兜底色 不在预设色表里`() {
        // 兜底色是「认不出原色时用」的，它若在预设色表里，
        // 就会被判成「我们染的」而永远还原不回去。
        assertFalse(
            ConfigKeys.PRESET_COLORS.contains(ConfigKeys.FALLBACK_NORMAL_COLOR),
            "兜底色不能是预设色 —— 它是「还原不出来时用」的",
        )
    }

    @Test
    fun `预设色都是不透明的`() {
        // 半透明的预设色在深色模式下会糊成一片。
        for (c in ConfigKeys.PRESET_COLORS) {
            val alpha = (c ushr 24) and 0xFF
            assertEquals(0xFF, alpha, "预设色 ${c.toUInt().toString(16)} 不是不透明的")
        }
    }
}
