package io.github.MVennn.GSAuxiliary.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「详情页底栏入口自定义」的数据模型与 JSON 编解码。
 *
 * **为什么在 `core/` 而不是 `feature/`**：与 [DockEntryStore] 同因 ——
 *  - 目标进程：注入底栏按钮时读它（`feature/stockbar/StockDetailBarFeature`）；
 *  - 模块进程：设置页读写那七行开关 —— 而 `ui/` 不能引用 `feature/`（架构红线 3）；
 *  - 本文件只用 `org.json` 与纯 Kotlin，两处共用零风险。
 *
 * 存储格式（v1，remote preferences 的 `stock_bar_items` 键，JSON 字符串）：
 *
 * ```json
 * { "v": 1, "items": ["aistare", "range"] }
 * ```
 *
 * `items` 是**要提到详情页底栏的面板项**，顺序无关（读进来就是列表）；[encode] 固定按
 * [KEYS] 的顺序输出，同一份配置写两次得到逐字相同的字符串，备份 diff 不会抖动。
 *
 * 容量：[MAX] 个。实测每项占 124px + 26px 间距，两个之后买卖按钮就被压到最小宽 ——
 * 再多就挤不下了，所以这一层直接截断，界面侧同步把剩下的行置灰。
 *
 * 迁移（[decode] 的 `legacyOn`），只回落、不回写：
 *  - 键**没写过**（空串 / null）→ 老版本布尔键 `stock_bar_entry` 为真时等价于
 *    `{"aistare"}`，老用户的「加智能盯盘入口」升级后表现不变；
 *  - 键写过（哪怕 `items` 是空数组）→ 一律以新键为准，老布尔键不再参与。
 *
 * 容错（降级原则：**宁可回落空列表，绝不崩**）：串坏 / 不是对象 / `v` 不是 1 → 空列表 +
 * 一条 warn；不认识的 key（未来版本删掉某一项、或手改配置写错）逐个丢弃；重复 key 去重。
 */
object StockBarItemStore {

    /** 「条件单」面板项 */
    const val KEY_CONDITION = "condition"

    /** 「智能盯盘」面板项 —— 老版本唯一能提的那一项 */
    const val KEY_AISTARE = "aistare"

    /** 「智能K线」面板项 */
    const val KEY_KLINE = "kline"

    /** 「产业链」面板项 */
    const val KEY_CHAIN = "chain"

    /** 「区间统计」面板项 */
    const val KEY_RANGE = "range"

    /** 「交易明细」面板项 */
    const val KEY_TRADES = "trades"

    /** 「股票PK」面板项 */
    const val KEY_PK = "pk"

    /**
     * 全部可提到详情页底栏的面板项，**顺序即界面行序、也是 [encode] 的输出顺序**。
     *
     * 顺序跟宿主「更多」面板一致（条件单 → 智能盯盘 → 智能K线 → 产业链 → 区间统计 →
     * 交易明细 → 股票PK），用户在面板与设置页两处看到的相对位置一样。
     */
    val KEYS: List<String> = listOf(
        KEY_CONDITION, KEY_AISTARE, KEY_KLINE, KEY_CHAIN, KEY_RANGE, KEY_TRADES, KEY_PK,
    )

    /** 详情页底栏最多同时放几个入口 —— 再多买卖按钮就没位置了（实测见类注释） */
    const val MAX = 2

    /** 当前数据结构版本；`stock_bar_items` 的 `v` 字段 */
    private const val FORMAT_VERSION = 1

    /**
     * 解析出「要提到详情页底栏的面板项」。
     *
     * @param raw 配置原文（可能为空串 / null / 坏 JSON）
     * @param legacyOn 老布尔键 `stock_bar_entry` 的值，只在 [raw] 为空时参与
     */
    fun decode(raw: String?, legacyOn: Boolean): List<String> {
        if (raw.isNullOrBlank()) return if (legacyOn) listOf(KEY_AISTARE) else emptyList()
        // 同一份快照的原文实例在进程里是稳定的（ConfigSnapshot 持有同一个 String），
        // 而目标进程每 800ms 一轮、界面每次重组一次 —— 用身份比较做零成本记忆。
        decodeMemo?.let { if (it.raw === raw) return it.items }
        val items = runCatching { decodeInternal(raw) }.getOrElse { t ->
            GLog.w("底栏入口项：配置解析失败，按「一个都不提」处理（长度 ${raw.length}）", t)
            emptyList()
        }
        decodeMemo = DecodeMemo(raw, items)
        return items
    }

    /** [decode] 的身份记忆：原文实例相同即认定结果相同 */
    private class DecodeMemo(val raw: String, val items: List<String>)

    @Volatile
    private var decodeMemo: DecodeMemo? = null

    private fun decodeInternal(raw: String): List<String> {
        val root = JSONObject(raw)
        // 版本闸门：v 缺失按当前版本处理（手写 / 老数据可能没这个字段），版本对不上就整体放弃。
        val version = root.optInt("v", FORMAT_VERSION)
        if (version != FORMAT_VERSION) {
            GLog.w("底栏入口项：配置版本 $version 与当前 $FORMAT_VERSION 不符，按「一个都不提」处理")
            return emptyList()
        }
        val array = root.optJSONArray("items") ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val key = array.optString(i).trim()
            if (key.isEmpty()) continue
            if (key !in KEYS) {
                // 未来版本删过项、或手改配置写错 —— 丢弃即可，不必吵
                GLog.w("底栏入口项：忽略不认识的面板项 key（$key）")
                continue
            }
            out.add(key)
            if (out.size >= MAX) break
        }
        return out.toList()
    }

    /**
     * 序列化成 v1 结构；顺序固定按 [KEYS]，不认识的 key 直接丢弃（写进去的必须读得回来）。
     */
    fun encode(items: Collection<String>): String {
        val array = JSONArray()
        var n = 0
        for (key in KEYS) {
            if (key !in items) continue
            array.put(key)
            if (++n >= MAX) break
        }
        return JSONObject().put("v", FORMAT_VERSION).put("items", array).toString()
    }

    /** 「一个都不提」的 v1 结构 —— 出厂值与「恢复只提智能盯盘」都用 [encode] 现算 */
    fun defaultJson(): String = encode(emptyList())
}
