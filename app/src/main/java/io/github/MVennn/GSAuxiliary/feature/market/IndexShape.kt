package io.github.MVennn.GSAuxiliary.feature.market

/**
 * 「指数概览」的结构判据 —— **纯数据，不碰 View**。
 *
 * ## 为什么要抽出来
 *
 * `MarketIndexHideFeature` 的兜底判据要回答一个问题：
 * **「这个横向多格的列表，是不是指数概览？」**
 *
 * 认错的代价很大 —— 收起了不该收的版块（用户看到行情页少了东西），
 * 而这种错误**在真机上要靠肉眼比对才能发现**，日志里看不出来。
 *
 * 所以把判据的四个条件抽成纯函数，让「认得对 / 认得错」都能被测出来。
 *
 * ## 四个条件（与 [MarketIndexHideFeature] 的用法一一对应）
 *
 * 1. **子项够多**（≥ [MarketTargets.MIN_INDEX_ITEMS]）—— 指数概览实测 4 项；
 * 2. **几乎每项都有文案**（有文案的项数 ≥ 子项数 − 1）—— 指数、板块名都在；
 * 3. **不是「图标 + 文字」的入口网格** —— 快捷入口也是横向多格，但每格是图标加文字；
 * 4. **整块不高**（≤ [MarketTargets.MAX_INDEX_OVERVIEW_HEIGHT_DP] dp）——
 *    指数概览是顶上一小条（实测 229px ≈ 88dp），而板块区那种很高的一律排除。
 */
internal object IndexShape {

    /**
     * 这一组子项像不像「指数概览」。
     *
     * @param childCount        子项数
     * @param labeledCount      其中「有文案」的项数
     * @param entryItemCount    其中「图标 + 文字」形态的项数
     * @param groupItemCount    其中「本身是 ViewGroup」的项数
     * @param totalHeightDp     整块高度（dp）；0 = 还没量出来（不参与高度判断）
     */
    fun looksLikeIndexOverview(
        childCount: Int,
        labeledCount: Int,
        entryItemCount: Int,
        groupItemCount: Int,
        totalHeightDp: Int,
    ): Boolean {
        // ⓪ **每一项都得是 ViewGroup**
        //
        // ⚠️ 这条是 2026-10-20 三次复核时补的（兜底范围收窄之后引入的）：
        // 兜底范围收窄到「行情页那几行的直接子项」之后，
        // **行4 快捷入口**（`#rv_entra`，5 个 `TextView` 格子）与
        // **行6 板块页签**（`#rg_bk`，5 个 `RadioButton`）也落进了范围，
        // 而它们**满足下面全部四条判据** ——
        // 原因在条件③：快捷入口的格子是 `TextView`（**不是** ViewGroup），
        // 而 `looksLikeEntryItem` 的第一道判据就是 `v is ViewGroup` → 直接短路
        // → 入口格数恒为 0 → 条件③（排除入口网格）**永远不生效**。
        //
        // 指数概览的每一项实测是 `LinearLayout #ll_content`（**是** ViewGroup），
        // 所以这一条能一次挡掉上面两个（TextView 格子 / RadioButton）。
        if (groupItemCount < childCount) return false

        // ① 子项够多
        if (childCount < MarketTargets.MIN_INDEX_ITEMS) return false

        // ② 几乎每项都有文案（允许 1 项例外 —— 最后一格可能是纯图标）
        if (labeledCount < childCount - 1) return false

        // ③ 不是「图标 + 文字」的入口网格（快捷入口）
        if (entryItemCount >= childCount - 1) return false

        // ④ 整块不高（还没量出高度时不拦 —— 别因为量不到就否掉）
        if (totalHeightDp > 0 && totalHeightDp > MarketTargets.MAX_INDEX_OVERVIEW_HEIGHT_DP) {
            return false
        }

        return true
    }
}
