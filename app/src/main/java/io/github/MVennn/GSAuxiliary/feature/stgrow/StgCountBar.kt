package io.github.MVennn.GSAuxiliary.feature.stgrow

import java.util.Locale

/**
 * 左上角 `#tv_stg_res_num` 那颗计数（「策略个股 N 只」）的**唯一文案口径**。
 *
 * ## 为什么要有这个对象（2026-11-14）
 *
 * 这颗计数有两处写点：
 *
 * - 筛选（`feature/stgfilter/StgStockFilterFeature.kt` 的 `updateResNum`）：写成 `策略个股 保留数/总数`；
 * - 聚合（`feature/stgmerge/StrategyMergeFeature.kt` 的 `writeMergedResNum`）：写成 `策略个股 合并只数只`，
 *   筛选生效时同样写 `保留数/总数`。
 *
 * 两边原先各自攥着一份格式串与正则，靠「逐字同格式」的注释维持一致。
 * 真机已经因为这类口径漂移出过事（2026-11-12 01:12：列表只剩 36 行，左上角还写着 212 只，
 * 见 `StrategyMergeFeature.kt` 的 `writeMergedResNum` 注释）。
 * 现在格式串、正则、拼装函数都收在这里，两边只调用、不再各写一份，
 * 谁改口径都只改这一个文件，另一侧自动跟上。
 *
 * ## 两种文案的区别是「谁写的」而不是「写什么」
 *
 * 宿主自己只会写 `策略个股N只`（N 是全量，可能带 `(11-11 19:40:22)` 这样的尾巴）；
 * `策略个股 X/Y` 只有本模块会写。所以还原时**必须**先判 [isOurs]：
 * 不是自己写的就别覆盖，免得把宿主刚刷新的文案吃掉。
 */
internal object StgCountBar {

    /** 宿主原生格式：「策略个股 N 只」，N 换成要显示的只数 */
    const val FORMAT_TOTAL = "策略个股%d只"

    /**
     * 本模块的「保留数/总数」格式。
     *
     * 刻意**不带尾部时间戳**：宿主那种 `策略个股47只(11-11 19:40:22)` 的尾巴在筛选文案里没有意义，
     * 而且 [OURS] 是严格全匹配，带尾巴就认不出是自己写的了。
     */
    const val FORMAT_KEPT = "策略个股 %d/%d"

    /** 我们自己写进去的样式：`策略个股 3/15`（严格全匹配，用于「是不是该还原」的判断） */
    val OURS: Regex = Regex("""^策略个股 \d+/\d+$""")

    /** 宿主原生样式：`策略个股47只`，第一捕获组是数字后面那段（时间戳之类的尾巴） */
    val HOST_TOTAL: Regex = Regex("""策略个股\d+只(.*)""")

    /** `策略个股N只` */
    fun total(n: Int): String = String.format(Locale.ROOT, FORMAT_TOTAL, n)

    /** `策略个股 保留数/总数` */
    fun kept(kept: Int, total: Int): String = String.format(Locale.ROOT, FORMAT_KEPT, kept, total)

    /** 这段文案是不是我们自己写的（否 = 宿主写的，还原时不许碰） */
    fun isOurs(text: String?): Boolean = text != null && OURS.matches(text)

    /** 宿主文案里数字之后那段尾巴（`(11-11 19:40:22)`）；不是宿主样式时返回空串 */
    fun hostTail(text: String?): String {
        if (text == null) return ""
        val m = HOST_TOTAL.find(text) ?: return ""
        return m.groupValues.getOrNull(1).orEmpty()
    }
}
