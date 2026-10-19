package io.github.MVennn.GSAuxiliary.feature.watchlist

/**
 * 「加粗 / 还原字重」的决策 —— **纯逻辑，不碰 Android 对象**。
 *
 * ## 为什么要抽出来
 *
 * 加粗这个功能有个很隐蔽的坑：**记原字重的时机**。
 *
 * RecyclerView 复用行视图，于是同一个 TextView 会被绑上不同的股票：
 *
 *  - 第一次绑到一只**非自选**股 → 记下它的原字重（正常）；
 *  - 第二次绑到一只**自选**股 → 要加粗（正常）；
 *  - 第三次又绑到非自选股 → 要**还原** —— 这时候必须还原成**第一次记的那个**，
 *    而不是「当前这个」（当前这个已经是加粗后的了）。
 *
 * 记晚了就记下了「加粗后的」那个字重 → 还原时撤不回去 → 整行一直粗着。
 *
 * 这段逻辑以前只能在真机上靠肉眼发现（某一行怎么一直是粗的），
 * 抽成纯函数之后就能测了。
 *
 * ## 为什么用「style 整数」而不是 `Typeface` 对象
 *
 * `Typeface` 是 Android 框架类 —— 单测的 JVM 环境里**没有** android.jar 的实现
 * （`android.jar` 里是抛 `Stub!` 的桩）。所以这里只用它的**语义位**：
 * `Typeface.NORMAL` / `Typeface.BOLD` / `Typeface.ITALIC` / `Typeface.BOLD_ITALIC`。
 *
 * 决策表：
 *
 * | 当前 style | 已记原 style | 要加粗 | 结果 |
 * |---|---|---|---|
 * | NORMAL | 无 | 是 | **先记原 style**，再设成 BOLD |
 * | NORMAL | 有 | 是 | 设成 BOLD（**不覆盖**已记的原 style） |
 * | BOLD | 有 | 是 | 不用再设（已经是加粗的） |
 * | 任意 | 无 | 否 | 不用动（没记过就无从还原） |
 * | 任意 | 有 | 否 | 还原成已记的那个，并清掉记录 |
 */
internal object TypefacePlan {

    /** `Typeface.NORMAL`（与 android.graphics.Typeface.NORMAL 相同，纯逻辑里不引框架类） */
    const val STYLE_NORMAL = 0

    /** `Typeface.BOLD`（与 android.graphics.Typeface.BOLD 相同） */
    const val STYLE_BOLD = 1

    /**
     * 这一趟该怎么处理字重。
     *
     * @param currentStyle  当前字重的 style（null = 读不到）
     * @param recordedStyle 已记录的原 style（null = 还没记过）
     * @param wantBold      要不要加粗
     */
    fun decide(
        currentStyle: Int?,
        recordedStyle: Int?,
        wantBold: Boolean,
    ): Decision {
        if (wantBold) {
            // ⚠️ **只在「还没记过」且「当前不是加粗态」时记原 style** ——
            // 记晚了就记下了加粗后的那个，还原时撤不回去。
            val shouldRecord = recordedStyle == null &&
                currentStyle != null &&
                currentStyle != STYLE_BOLD
            // 当前已经是加粗态 → 不用再设
            if (currentStyle == STYLE_BOLD) {
                return Decision.Set(STYLE_BOLD, recordOriginal = shouldRecord)
            }
            // ⚠️ **既没有当前字重、也没有记过的原字重 → 什么都不知道，别瞎设** ——
            // 设一个凭空造的字重比不加粗更糟（用户看到的是乱码样的粗体）。
            if (currentStyle == null && recordedStyle == null) return Decision.None
            return Decision.Set(STYLE_BOLD, recordOriginal = shouldRecord)
        }

        // 还原：只有记过原 style 才还原
        val orig = recordedStyle ?: return Decision.None
        if (currentStyle == orig) return Decision.None
        return Decision.Restore(orig)
    }

    /** 这一趟对字重要做什么 */
    sealed interface Decision {
        /** 不用动 */
        object None : Decision

        /** 设成这个 style；[recordOriginal] = true 表示要顺手把原 style 记下来 */
        data class Set(val style: Int, val recordOriginal: Boolean) : Decision

        /** 还原成这个 style（并清掉记录） */
        data class Restore(val style: Int) : Decision
    }
}
