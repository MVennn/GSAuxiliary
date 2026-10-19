package io.github.MVennn.GSAuxiliary.feature.filter

/**
 * 「代码.市场」串的切分 —— **纯逻辑**。
 *
 * ## 为什么要单独抽出来
 *
 * 2026-10-20 的一次复核里出了一个真问题：筛选那批「纯重构」把切分从
 * `StockTypes.byCodeAndMarket(cam, name)`（2 参重载）挪到了调用点，
 * 而**新写的切分与旧的口径差了一点**：
 *
 * ```
 * 旧：dot <= 0 → byCodeAndMarket(cam, null, name)   ← 整串当代码
 * 新：dot <= 0 → code = null                          ← 代码前缀判定整段失效
 * ```
 *
 * 后果：宿主 `codeAndMarket` 出现「只有代码、没有市场号」的中间态时
 * （`StockInfo` 是**构造后填充**的，部分填充是真实存在的形态），
 * 这只票的「创业板 / 科创板 / 北证A」归类丢失 → 类型筛选口径变松。
 *
 * 而当时的单测**没抓到** —— 因为它测的是 `StockTypes` 的 2 参重载，
 * **生产代码早就不走那条路了**（自己切的）。
 *
 * 所以现在把切分抽成**生产代码与测试共用**的一个函数，
 * 保证「测的就是跑的」。
 */
internal object CodeMarketParts {

    /**
     * 切分「代码.市场」。
     *
     * 口径与 [StockTypes.byCodeAndMarket] 的 2 参重载**逐字等价**：
     *
     *  - 有���（`dot > 0`）→ code = 点前、market = 点后；
     *  - **无点** → code = **整串**、market = null
     *    （不是 code = null —— 那样代码前缀判定会整段失效）。
     *
     * @return (code, market)；[cam] 为 null 时两个都是 null
     */
    fun split(cam: String?): Pair<String?, String?> {
        if (cam.isNullOrEmpty()) return null to null
        val dot = cam.indexOf('.')
        return if (dot > 0) {
            cam.substring(0, dot) to cam.substring(dot + 1)
        } else {
            cam to null
        }
    }
}
