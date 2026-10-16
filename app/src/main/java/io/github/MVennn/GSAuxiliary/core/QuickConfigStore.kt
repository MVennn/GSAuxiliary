package io.github.MVennn.GSAuxiliary.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「智能盯盘 → 价格页一键配置」的方案数据模型与 JSON 编解码。
 *
 * **为什么在 `core/` 而不是 `feature/aistare/`**：这套模型三个地方都要用 ——
 *  - 目标进程：功能读方案算价格（`feature/aistare/AiStareQuickConfigFeature`）；
 *  - 模块进程：设置页的方案列表 / 编辑页读写方案（`ui/QuickConfigScreen` 等）；
 *  - 而 `ui/` 不能引用 `feature/`（会顺着 import 链把 `io.github.libxposed.api`
 *    类型带进模块 App 进程，架构红线 3）。本文件只用 `org.json` 与纯 Kotlin，
 *    两处共用零风险。
 *
 * 存储格式（v1，remote preferences 的 `aistare_quick_config` 键，JSON 字符串）：
 *
 * ```json
 * {
 *   "v": 1,
 *   "defaultId": "s1",
 *   "schemes": [
 *     { "id": "s1", "name": "30日高低", "preview": "涨到 30日最高价 · 跌到 30日最低价",
 *       "validity": null, "isDefault": true,
 *       "up":   { "agg": "MAX", "terms": [ { "source": "highN", "param": { "period": "30" } } ] },
 *       "down": { "agg": "MIN", "terms": [ { "source": "lowN",  "param": { "period": "30" } } ] } }
 *   ]
 * }
 * ```
 *
 * 容错（解析失败的降级原则：**宁可回落默认，绝不崩**）：
 *  - 整体 JSON 坏 → 回落出厂方案 + warn 日志；
 *  - 单条方案坏（id 空 / 槽空）→ 丢弃该方案，其余保留；
 *  - 单个取值项的 `source` 不在目录里（App 升级加了新项、用户降级回来）→
 *    **丢弃该取值项而不是整条方案**；
 *  - 丢弃后槽空了才连坐整条方案；全部方案坏才回落默认。
 */
object QuickConfigStore {

    // ------------------------------------------------------------ H5 → 模块 App 的拉起契约

    /**
     * 「管理方案…」从宿主 H5 拉起模块 App 时的 Intent 契约
     * （设置页的 `SettingsActivity` 消费）：extra [EXTRA_ROUTE] 的值是
     * [ROUTE_QUICK_CONFIG] 时直达「一键配置」二级页。
     *
     * 放这里而不是 Feature / ui 的任何一侧 —— 两边都要用，而
     * **`ui/` 不能引用 `feature/`**（会把 `io.github.libxposed.api` 类型
     * 带进模块 App 进程，架构红线 3）；`core/` 是唯一的两处公共地。
     */
    const val EXTRA_ROUTE = "route"
    const val ROUTE_QUICK_CONFIG = "aistare_quick_config"

    // ------------------------------------------------------------ 数据模型

    /** 一个方案：涨 / 跌两个槽 + 可选的提醒时效 + 默认标记 */
    data class Scheme(
        val id: String,
        val name: String,
        /** 涨到槽 */
        val up: Slot,
        /** 跌到槽 */
        val down: Slot,
        /** 提醒时效：null = 不动宿主；[VALIDITY_ONCE] / [VALIDITY_LONG] 顺带设置 */
        val validity: String?,
        /** 默认方案（点击「配置」不弹层直接套用的那个；全局唯一） */
        val isDefault: Boolean,
        /**
         * 一行预览文案（「涨到 30日最高价 · 跌到 30日最低价」）。
         * 由设置页在**写入时**按当时的语言生成存进来 —— 目标进程没有模块的资源
         * 上下文，拼不了本地化文案；语言改了重新保存一次即刷新。
         */
        val preview: String,
    )

    /** 一个槽：有序的取值项 + 聚合方式 */
    data class Slot(
        /** 多个取值项的聚合方式：[AGG_MAX] / [AGG_MIN] / [AGG_AVG] */
        val agg: String,
        /** 取值项，有序（多值时第一个是展示用主项） */
        val terms: List<Term>,
    )

    /** 一个取值项：目录 id（[QuickConfigCatalog]）+ 参数 */
    data class Term(
        val source: String,
        val param: Map<String, String>,
    )

    /** 整份配置：方案列表 + 默认方案 id */
    data class Data(
        val defaultId: String,
        val schemes: List<Scheme>,
    ) {
        /** 默认方案（defaultId 失效时回落第一个） */
        val default: Scheme?
            get() = schemes.firstOrNull { it.id == defaultId && it.isDefault }
                ?: schemes.firstOrNull { it.isDefault }
                ?: schemes.firstOrNull()
    }

    // ------------------------------------------------------------ 常量

    const val AGG_MAX = "MAX"
    const val AGG_MIN = "MIN"
    const val AGG_AVG = "AVG"

    const val VALIDITY_ONCE = "once"
    const val VALIDITY_LONG = "long"

    private const val FORMAT_VERSION = 1

    // ------------------------------------------------------------ 解析

    /**
     * 解析方案 JSON。**任何失败都回落出厂方案**（[ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG]
     * 的结构），绝不返回空列表、绝不抛异常 —— 调用方（目标进程的注入回调）不设防。
     */
    fun decode(json: String?): Data {
        val fallback = decodeOrNull(ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG)
            ?: error("出厂方案 JSON 自身解析失败（不该发生）")
        if (json.isNullOrBlank()) return fallback
        val parsed = decodeOrNull(json)
        if (parsed != null) return parsed
        GLog.w("一键配置方案 JSON 解析失败，回落出厂方案")
        return fallback
    }

    /** 容错解析；完全解不出（无有效方案）返回 null */
    private fun decodeOrNull(json: String): Data? {
        return runCatching {
            val root = JSONObject(json)
            val schemesJson = root.optJSONArray("schemes") ?: return@runCatching null
            val schemes = ArrayList<Scheme>()
            for (i in 0 until schemesJson.length()) {
                decodeScheme(schemesJson.optJSONObject(i) ?: continue)?.let { schemes.add(it) }
            }
            if (schemes.isEmpty()) return@runCatching null
            // defaultId 失效（指向被丢弃的方案）时回落第一个 —— decode 的注释里说过
            val defaultId = root.optString("defaultId").takeIf { id ->
                schemes.any { it.id == id }
            } ?: schemes.first().id
            Data(defaultId, schemes)
        }.getOrNull()
    }

    /** 单条方案；槽空（全部取值项都被丢弃）时返回 null（整条连坐） */
    private fun decodeScheme(o: JSONObject): Scheme? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val name = o.optString("name").takeIf { it.isNotEmpty() } ?: id
        val up = decodeSlot(o.optJSONObject("up")) ?: return null
        val down = decodeSlot(o.optJSONObject("down")) ?: return null
        // validity 只认认识的两个值，其余（含缺省）一律 null = 不动宿主
        val validity = o.optString("validity", "").takeIf {
            it == VALIDITY_ONCE || it == VALIDITY_LONG
        }
        return Scheme(
            id = id,
            name = name,
            up = up,
            down = down,
            validity = validity,
            isDefault = o.optBoolean("isDefault", false),
            preview = o.optString("preview", ""),
        )
    }

    /** 单个槽：不认识的 source 丢弃该取值项；全丢光返回 null */
    private fun decodeSlot(o: JSONObject?): Slot? {
        o ?: return null
        val termsJson = o.optJSONArray("terms") ?: return null
        val terms = ArrayList<Term>()
        for (i in 0 until termsJson.length()) {
            val t = termsJson.optJSONObject(i) ?: continue
            val source = t.optString("source").takeIf { it.isNotEmpty() } ?: continue
            val spec = QuickConfigCatalog.byId(source) ?: continue // 未知取值项：丢弃，不连坐
            val paramJson = t.optJSONObject("param")
            val param = HashMap<String, String>()
            if (paramJson != null) {
                for (key in paramJson.keys()) param[key] = paramJson.optString(key)
            }
            // 必需参数缺失 → 该项算不出来，丢弃
            if (spec.params.any { p -> p.required && param[p.key].isNullOrBlank() }) continue
            terms.add(Term(source, param))
        }
        if (terms.isEmpty()) return null
        val agg = o.optString("agg").takeIf {
            it == AGG_MAX || it == AGG_MIN || it == AGG_AVG
        } ?: AGG_MAX
        return Slot(agg, terms)
    }

    // ------------------------------------------------------------ 序列化

    fun encode(data: Data): String {
        val root = JSONObject()
        root.put("v", FORMAT_VERSION)
        root.put("defaultId", data.defaultId)
        val arr = JSONArray()
        for (s in data.schemes) {
            val so = JSONObject()
            so.put("id", s.id)
            so.put("name", s.name)
            so.put("preview", s.preview)
            so.put("validity", s.validity ?: JSONObject.NULL)
            so.put("isDefault", s.isDefault)
            so.put("up", encodeSlot(s.up))
            so.put("down", encodeSlot(s.down))
            arr.put(so)
        }
        root.put("schemes", arr)
        return root.toString()
    }

    private fun encodeSlot(slot: Slot): JSONObject {
        val o = JSONObject()
        o.put("agg", slot.agg)
        val terms = JSONArray()
        for (t in slot.terms) {
            val to = JSONObject()
            to.put("source", t.source)
            val po = JSONObject()
            for ((k, v) in t.param) po.put(k, v)
            to.put("param", po)
            terms.put(to)
        }
        o.put("terms", terms)
        return o
    }

    /** 生成一个不与现有方案冲突的新方案 id */
    fun newId(existing: List<Scheme>): String {
        var n = existing.size + 1
        val taken = existing.map { it.id }.toHashSet()
        while (true) {
            val id = "s$n"
            if (id !in taken) return id
            n++
        }
    }
}
