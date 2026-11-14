package io.github.MVennn.GSAuxiliary.core

import org.json.JSONObject
import java.util.Locale

/**
 * 「自选股高亮」分档配色（涨 / 跌 / 平）的数据模型与 JSON 编解码。
 *
 * **为什么在 `core/` 而不是 `feature/`**：这份模型两侧都要用 ——
 *  - 目标进程：每行着色时按档位取色（`feature/watchlist/WatchlistFeature`）；
 *  - 模块进程：二级页读写那三行选择项 —— 而 `ui/` 不能引用 `feature/`（会顺着 import 链
 *    把 `io.github.libxposed.api` 类型带进模块 App 进程，架构红线 3）；
 *  - 本文件只用 `org.json` 与纯 Kotlin，两处共用零风险。
 *
 * 存储格式（v1，remote preferences 的 `watchlist_colors` 键，JSON 字符串）：
 *
 * ```json
 * { "v": 1, "up": "#FFFF8000", "down": "#FF3482FF", "flat": "#FF212634" }
 * ```
 *
 * ## 与老键 `color` 的关系（迁移口径）
 *
 * 老版本只有一个高亮色（`color`，Int）。本键**为空串时**，三档全部回落到那个老颜色 ——
 * 于是「什么都没配过」和「刚升级上来」的用户看到的颜色**与升级前完全一致**，
 * 不需要任何一次性迁移写盘。
 *
 * 只有用户真的在二级页改了某一档，才会写出本键；写出的是一份**完整**的三档表
 * （界面上那三行始终各自显示当前生效色），此后 `color` 不再参与。
 *
 * **读取侧回落、写入侧不迁移**：目标进程可能先于模块界面启动，任何「读到空就补写」
 * 都会在目标进程里产生写盘（它只有远端 prefs 的读权限，写会失败并刷日志）。
 */
object WatchlistColorStore {

    /** 三档顺序与 [WatchlistTier.ALL] 一致 */
    val TIERS: List<String> = WatchlistTier.ALL

    /** 当前数据结构版本；`watchlist_colors` 的 `v` 字段 */
    private const val FORMAT_VERSION = 1

    /**
     * 解析出「每一档用哪个颜色」。
     *
     * @param raw 配置原文（可能为空串 / null / 坏 JSON）
     * @param legacy 老键 `color` 的值，只在 [raw] 为空时参与（三档同色）
     * @return 三档齐全的表；读不出来时三档都是 [legacy]
     */
    fun decode(raw: String?, legacy: Int): Map<String, Int> {
        if (raw.isNullOrBlank()) return allOf(legacy)
        // 同一份快照的原文实例在进程里是稳定的（ConfigSnapshot 持有同一个 String），
        // 而 `getView` 每行都要按档取色 —— 用身份比较做零成本记忆。
        decodeMemo?.let { memo ->
            if (memo.raw === raw && memo.legacy == legacy) return memo.colors
        }
        val colors = runCatching { decodeInternal(raw, legacy) }.getOrElse { t ->
            GLog.w("自选高亮：分档配色解析失败，按三档同色处理（长度 ${raw.length}）", t)
            allOf(legacy)
        }
        decodeMemo = DecodeMemo(raw, legacy, colors)
        return colors
    }

    /** 某一档当前生效的颜色（读不出来就回落老颜色） */
    fun colorFor(raw: String?, legacy: Int, tier: String): Int =
        decode(raw, legacy)[tier] ?: legacy

    /** 三档同色 —— 出厂形态，也是老配置的等价形态 */
    fun allOf(color: Int): Map<String, Int> = LinkedHashMap<String, Int>(4).apply {
        for (tier in TIERS) put(tier, color)
    }

    /** 「几种不同的颜色」（1 = 三档同色，3 = 三档各不相同）；入口副标题现算用它 */
    fun distinctCount(raw: String?, legacy: Int): Int = decode(raw, legacy).values.toSet().size

    /**
     * 序列化成 v1 结构；顺序固定按 [TIERS]，缺的档**不写**（读取侧回落老颜色）。
     *
     * 调用方（二级页）永远传完整三档 —— 这样用户改一档之后，另外两档也一起被钉住，
     * 不会再随 `color` 变动。
     */
    fun encode(colors: Map<String, Int>): String {
        val obj = JSONObject().put("v", FORMAT_VERSION)
        for (tier in TIERS) {
            val value = colors[tier] ?: continue
            obj.put(tier, hexOf(value))
        }
        return obj.toString()
    }

    /** 三档同色的 v1 结构 —— 「恢复默认」类动作写它 */
    fun defaultJson(legacy: Int): String = encode(allOf(legacy))

    /** [decode] 的身份记忆：原文实例与老颜色都相同即认定结果相同 */
    private class DecodeMemo(val raw: String, val legacy: Int, val colors: Map<String, Int>)

    @Volatile
    private var decodeMemo: DecodeMemo? = null

    private fun decodeInternal(raw: String, legacy: Int): Map<String, Int> {
        val root = JSONObject(raw)
        // 版本闸门：v 缺失按当前版本处理（手写 / 老数据可能没这个字段），版本对不上整体放弃。
        val version = root.optInt("v", FORMAT_VERSION)
        if (version != FORMAT_VERSION) {
            GLog.w("自选高亮：分档配色版本 $version 与当前 $FORMAT_VERSION 不符，按三档同色处理")
            return allOf(legacy)
        }
        val out = LinkedHashMap<String, Int>(4)
        for (tier in TIERS) {
            out[tier] = parseColor(root.optString(tier, "")) ?: legacy
        }
        return out
    }

    /**
     * `#AARRGGBB` / `#RRGGBB` 转 ARGB int。
     *
     * **不用 `android.graphics.Color.parseColor`**：这个文件在模块进程与目标进程都要用，
     * 而单测（JVM）里 android.graphics 只有空壳实现 —— 走自己解析才能被单测覆盖，
     * 解析错了在真机上就是「选完颜色变成透明」。
     */
    private fun parseColor(text: String?): Int? {
        val body = text?.trim()?.removePrefix("#") ?: return null
        if (body.isEmpty()) return null
        val value = body.toLongOrNull(16) ?: return null
        return when (body.length) {
            6 -> (0xFF000000L or value).toInt()
            8 -> value.toInt()
            else -> null
        }
    }

    /** ARGB int 转 `#AARRGGBB`（`Locale.ROOT`：十六进制与区域设置无关） */
    private fun hexOf(argb: Int): String = String.format(Locale.ROOT, "#%08X", argb)
}
