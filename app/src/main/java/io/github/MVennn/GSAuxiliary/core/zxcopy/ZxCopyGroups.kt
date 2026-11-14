package io.github.MVennn.GSAuxiliary.core.zxcopy

/**
 * 「自选分组复制」把规则绑到具体分组时用的稳定键。
 *
 * ## 为什么存键不存标题
 *
 * 目标进程认当前分组只能读宿主的 tab 文案（`#indicator_zx_group` 的 `#title_container`），
 * 那是宿主硬编码的中文，跟着 App 版本走；键是模块自己的常量，宿主改文案时只改
 * [HOST_TITLES] 一张表，已经写进用户配置的规则不受影响。
 *
 * ## 三个键对应自选页的三个 tab
 *
 * 真机实测（金太阳 8.6.0）：分组 tab 固定三个 —— 全部 / 自选 / 持仓股。
 * 「持仓股」不是真实分组（`getAllGroups()` 只回前两个），但它有自己的 tab，
 * 所以它照样能当绑定目标。
 */
object ZxCopyGroups {

    /** 第一个 tab：全部股票（跨分组汇总） */
    const val ALL = "all"

    /** 第二个 tab：自选分组本身 */
    const val SELF = "self"

    /** 第三个 tab：持仓股 */
    const val HOLDING = "holding"

    /** 顺序即 tab 顺序，规则 JSON 里也按这个顺序写 */
    val KEYS: List<String> = listOf(ALL, SELF, HOLDING)

    /** 宿主 tab 文案（写死在宿主里，不随模块语言变） */
    private val HOST_TITLES: Map<String, String> = mapOf(
        ALL to "全部",
        SELF to "自选",
        HOLDING to "持仓股",
    )

    /**
     * 宿主 tab 文案 → 键。
     *
     * 认不出就返回 null：自建分组、宿主改文案都走这条，调用方按「没绑定」处理，
     * 不能用位置序号兜底 —— 分组增删会挪位置，序号会指到别的分组上。
     */
    fun keyOfHostTitle(title: String?): String? {
        val text = title?.trim().orEmpty()
        if (text.isEmpty()) return null
        for (key in KEYS) {
            if (HOST_TITLES[key] == text) return key
        }
        return null
    }

    /** 去未知键、去重、按 tab 顺序排好 —— 读配置与写配置都过它，两侧结果一致 */
    fun normalize(groups: List<String>): List<String> =
        KEYS.filter { key -> groups.any { it == key } }
}