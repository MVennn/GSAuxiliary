package io.github.MVennn.GSAuxiliary.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「底栏入口自定义」的数据模型与 JSON 编解码。
 *
 * **为什么在 `core/` 而不是 `feature/`**：这份模型两侧都要用 ——
 *  - 目标进程：重排底栏时读它（`feature/dock/DockCleanFeature`）；
 *  - 模块进程：设置页读写那六行开关 —— 而 `ui/` 不能引用 `feature/`（会顺着 import 链把
 *    `io.github.libxposed.api` 类型带进模块 App 进程，架构红线 3）；
 *  - 本文件只用 `org.json` 与纯 Kotlin，两处共用零风险。
 *
 * 存储格式（v1，remote preferences 的 `dock_entries` 键，JSON 字符串）：
 *
 * ```json
 * { "v": 1, "hidden": ["money", "trade"] }
 * ```
 *
 * `hidden` 是**被隐藏的格子**，顺序无关（读进来就是 Set）；[encode] 固定按
 * [KEYS] 的顺序输出，同一份配置写两次得到逐字相同的字符串，备份 diff 不会抖动。
 *
 * 迁移（[decode] 的 `legacyHidden`），只回落、不回写：
 *  - 键**没写过**（空串 / null）→ 老版本布尔键 `dock_clean` 为真时等价于 `{"money"}`，
 *    老用户的「藏理财」升级后表现不变；
 *  - 键写过（哪怕 `hidden` 是空数组）→ 一律以新键为准，老布尔键不再参与。
 *
 * 容错（降级原则：**宁可回落空集合，绝不崩**）：串坏 / 不是对象 / `v` 不是 1 → 空集合 +
 * 一条 warn；不认识的 key（未来版本删掉某一格、或手改配置写错）逐个丢弃；重复 key 去重。
 */
object DockEntryStore {

    /** 首页格子 */
    const val KEY_HOME = "home"

    /** 行情格子 */
    const val KEY_QUOTES = "quotes"

    /** 自选格子 */
    const val KEY_ZX = "zx"

    /** 交易格子 */
    const val KEY_TRADE = "trade"

    /** 理财格子 —— 老版本「隐藏理财入口」唯一能藏的那一格 */
    const val KEY_MONEY = "money"

    /** 账户格子 */
    const val KEY_MINE = "mine"

    /**
     * 全部可隐藏的格子，**顺序即界面行序、也是 [encode] 的输出顺序**。
     *
     * 顺序跟 8.6.0 真机底栏一致（首页 → 行情 → 自选 → 交易 → 理财 → 账户），
     * 用户在两处看到的相对位置一样，不用重新找。
     */
    val KEYS: List<String> = listOf(KEY_HOME, KEY_QUOTES, KEY_ZX, KEY_TRADE, KEY_MONEY, KEY_MINE)

    /** 当前数据结构版本；`dock_entries` 的 `v` 字段 */
    private const val FORMAT_VERSION = 1

    /**
     * 解析出「被隐藏的格子集合」。
     *
     * @param raw 配置原文（可能为空串 / null / 坏 JSON）
     * @param legacyHidden 老布尔键 `dock_clean` 的值，只在 [raw] 为空时参与
     */
    fun decode(raw: String?, legacyHidden: Boolean): Set<String> {
        if (raw.isNullOrBlank()) return if (legacyHidden) setOf(KEY_MONEY) else emptySet()
        // 同一份快照的原文实例在进程里是稳定的（ConfigSnapshot 持有同一个 String），
        // 而目标进程每 800ms 一轮、界面每次重组一次 —— 用身份比较做零成本记忆。
        decodeMemo?.let { if (it.raw === raw) return it.hidden }
        val hidden = runCatching { decodeInternal(raw) }.getOrElse { t ->
            GLog.w("底栏入口：配置解析失败，按「一个都不藏」处理（长度 ${raw.length}）", t)
            emptySet()
        }
        decodeMemo = DecodeMemo(raw, hidden)
        return hidden
    }

    /** [decode] 的身份记忆：原文实例相同即认定结果相同 */
    private class DecodeMemo(val raw: String, val hidden: Set<String>)

    @Volatile
    private var decodeMemo: DecodeMemo? = null

    private fun decodeInternal(raw: String): Set<String> {
        val root = JSONObject(raw)
        // 版本闸门：v 缺失按当前版本处理（手写 / 老数据可能没这个字段），版本对不上就整体放弃。
        val version = root.optInt("v", FORMAT_VERSION)
        if (version != FORMAT_VERSION) {
            GLog.w("底栏入口：配置版本 $version 与当前 $FORMAT_VERSION 不符，按「一个都不藏」处理")
            return emptySet()
        }
        val array = root.optJSONArray("hidden") ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val key = array.optString(i).trim()
            if (key.isEmpty()) continue
            if (key !in KEYS) {
                // 未来版本删过格子、或手改配置写错 —— 丢弃即可，不必吵
                GLog.w("底栏入口：忽略不认识的格子 key（$key）")
                continue
            }
            out.add(key)
        }
        return out
    }

    /**
     * 序列化成 v1 结构；顺序固定按 [KEYS]，不认识的 key 直接丢弃（写进去的必须读得回来）。
     */
    fun encode(hidden: Set<String>): String {
        val array = JSONArray()
        for (key in KEYS) if (key in hidden) array.put(key)
        return JSONObject().put("v", FORMAT_VERSION).put("hidden", array).toString()
    }

    /** 「一个都不藏」的 v1 结构 —— 出厂值与「恢复全显示」都写它 */
    fun defaultJson(): String = encode(emptySet())
}
