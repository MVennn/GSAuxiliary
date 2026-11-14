package io.github.MVennn.GSAuxiliary.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「策略合并」的聚合组数据模型与 JSON 编解码。
 *
 * **为什么在 `core/` 而不是 `feature/`**：这份模型两侧都要用 ——
 *  - 目标进程：合并逻辑读它（`feature/` 里的实现方）；
 *  - 模块进程：设置页读写组 —— 而 `ui/` 不能引用 `feature/`（会顺着 import 链把
 *    `io.github.libxposed.api` 类型带进模块 App 进程，架构红线 3）；
 *  - 本文件只用 `org.json` 与纯 Kotlin，两处共用零风险。
 *
 * 存储格式（v1，remote preferences 的 `stg_merge_groups` 键，JSON 字符串）：
 *
 * ```json
 * {
 *   "v": 1,
 *   "groups": [
 *     { "id": "g1", "name": "银行", "enabled": true,
 *       "members": [ { "stId": "600036", "name": "招商银行" } ] }
 *   ]
 * }
 * ```
 *
 * 字段顺序在 [StrategyMergeStore.encode] 里是**固定**的（`v` → `groups` →
 * `id` / `name` / `enabled` / `members` → `stId` / `name`）：同一份配置写两次得到
 * 逐字相同的字符串，备份 diff 不会因为键顺序抖动。
 *
 * 容错（降级原则：**宁可回落空配置，绝不崩**）：
 *  - 空串 / null / 整体 JSON 坏 / `v` 不是 1 → [MergeConfig] 空表 + 一条 warn，不抛异常；
 *  - 单个组的 `id` 为空（去空白后）→ 丢弃该组，其余保留；
 *  - 组 `id` 重复 → 只留第一次出现的（id 是 [MergeConfig.group] 的唯一句柄）；
 *  - 组 `name` 为空 → 回落「聚合组 N」（N = 它在结果里的序号）；
 *  - 成员 `name` 与 `stId` 都为空（去空白后）→ 丢弃该成员；同组按 `name` 去重
 *    （`name` 空时按 `stId`）→ 只留第一次 —— 成员以**名称**为识别键，`stId` 只是可选的
 *    加速 / 兼容字段（目标进程会用 `StrategyStgIndex` 按名称补齐）；
 *  - 成员 `name` 为空 → 回落成 `stId`（界面至少还能显示代码）；
 *  - 组数 > [StrategyMergeStore.MAX_GROUPS]、单组成员数 > [StrategyMergeStore.MAX_MEMBERS]
 *    → 截断多余的并记一条 warn（[StrategyMergeStore.encode] 同口径截断）。
 *
 * [StrategyMergeStore.encode] 写之前按**同一套归一规则**处理（trim 后名称与 `stId` 都空的组 /
 * 成员不写、组名空回落「聚合组 N」、成员名空回落 `stId`、`stId` 留空就照写空串）：保证
 * 「写进去的就是读得回来的」，否则界面上看着有 12 个成员，重启后少一个，还查不出原因。
 * `stId` 可留空是这个格式的一部分 —— 空串写进配置、读回来还是空串，不在往返里被静默丢掉。
 */
data class MergeMember(val stId: String, val name: String)

/**
 * 一个聚合组：组内成员被视作同一只股票。
 *
 * [enabled] 关掉的组**仍然留在配置里** —— 用户只是暂时不让它参与合并，
 * 编排本身（id / 名称 / 成员）不能丢。
 */
data class MergeGroup(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val members: List<MergeMember>,
)

/** 整份聚合组配置：v1 只有组列表，没有全局参数 */
data class MergeConfig(val groups: List<MergeGroup>) {
    /**
     * 按 id 取**生效中**的组。
     *
     * null 或空串 → 返回第一个 enabled 的组；id 命中但被禁用 → 返回 null。
     *
     * 另外两种情况一并定死（三个调用方共用同一口径）：
     *  - id **不命中**（组被删了、配置是旧的）→ 与「没指定」同款兜底，返回第一个
     *    enabled 的组 —— 否则删掉一个组会让整个功能静默失效；
     *  - 一个 enabled 的组都没有 → null（调用方按「无组可合并」处理）。
     */
    fun group(id: String?): MergeGroup? {
        if (id.isNullOrBlank()) return groups.firstOrNull { it.enabled }
        val hit = groups.firstOrNull { it.id == id }
        if (hit != null) return hit.takeIf { it.enabled }
        return groups.firstOrNull { it.enabled }
    }
}

object StrategyMergeStore {

    /**
     * 组数上限。
     *
     * [decode] 与 [encode] 用**同一个数**（写进去的就是读得回来的）：
     * 只在解析侧截断的话，界面上会出现「加了第 21 个组，重启后它不见了」。
     */
    const val MAX_GROUPS = 20

    /**
     * 单组成员上限；同样 decode / encode 同口径。
     *
     * 12 → 16 是因为「中盘 1.5-4」这个口径本身就要 12 个组合（3 个市值档 × 4 个量比档），
     * 刚好卡死上限，用户想再补一条真策略就加不进去。16 留 4 个余量。
     */
    const val MAX_MEMBERS = 16

    /** 当前数据结构版本；`stg_merge_groups` 的 `v` 字段 */
    private const val FORMAT_VERSION = 1

    // ------------------------------------------------------------ 解析

    /**
     * 解析聚合组 JSON。
     *
     * **坏数据 / 版本不符 → [MergeConfig] 空表，永不抛异常** —— 调用点在目标进程的
     * 注入回调与设置页主线程，两边都不设防。
     */
    fun decode(raw: String?): MergeConfig {
        if (raw == null || raw.isBlank()) return MergeConfig(emptyList())
        // 同一份快照的原文实例在进程里是稳定的（ConfigSnapshot 持有同一个 String），
        // 而调用点每 800ms 一轮、每轮不止一次 —— 用身份比较做零成本记忆：
        // 只有换了新 String 才真的重新解析。
        decodeMemo?.let { if (it.raw === raw) return it.config }
        val cfg = runCatching { decodeInternal(raw) }.getOrElse { t ->
            GLog.w("策略合并：组数据解析失败，按空配置处理（长度 ${raw.length}）", t)
            MergeConfig(emptyList())
        }
        decodeMemo = DecodeMemo(raw, cfg)
        return cfg
    }

    /** [decode] 的身份记忆：原文实例相同即认定结果相同 */
    private class DecodeMemo(val raw: String, val config: MergeConfig)

    @Volatile
    private var decodeMemo: DecodeMemo? = null

    private fun decodeInternal(raw: String): MergeConfig {
        val root = JSONObject(raw)
        // 版本闸门：v 缺失时按当前版本处理（老数据 / 手写配置可能没这个字段），
        // 版本对不上就整体放弃 —— 结构未知时硬解析只会得到半份错的组，比空表更难查。
        val version = root.optInt("v", FORMAT_VERSION)
        if (version != FORMAT_VERSION) {
            GLog.w("策略合并：组数据版本 $version 与当前 $FORMAT_VERSION 不符，按空配置处理")
            return MergeConfig(emptyList())
        }
        val groupsJson = root.optJSONArray("groups") ?: return MergeConfig(emptyList())
        val groups = ArrayList<MergeGroup>()
        // 同一 id 只留第一份（与 QuickConfigStore 的 seenIds 同款做法）
        val seenIds = HashSet<String>()
        for (i in 0 until groupsJson.length()) {
            if (groups.size >= MAX_GROUPS) {
                GLog.w("策略合并：组数超过上限 $MAX_GROUPS，其余 ${groupsJson.length() - i} 组已截断")
                break
            }
            val groupJson = groupsJson.optJSONObject(i) ?: continue
            val group = decodeGroup(groupJson, groups.size + 1) ?: continue
            if (!seenIds.add(group.id)) {
                GLog.w("策略合并：组 id 重复（${group.id}），已丢弃后出现的那条")
                continue
            }
            groups.add(group)
        }
        return MergeConfig(groups)
    }

    /** 单个组；`id` 为空（去空白后）返回 null —— 没有 id 的组选不中，留着只会误导 */
    private fun decodeGroup(o: JSONObject, ordinal: Int): MergeGroup? {
        val id = o.optString("id").trim().takeIf { it.isNotEmpty() } ?: return null
        // 「聚合组 N」的 N 用它在结果里的序号：显示名稳定，不会因为跳过坏组而串号
        val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: "聚合组 $ordinal"
        val membersJson = o.optJSONArray("members") ?: JSONArray()
        val members = ArrayList<MergeMember>()
        // 去重键用**名称**：名称才是成员的身份，stId 只是可选的加速 / 兼容字段
        // （界面只问名称，目标进程按名称从 StrategyStgIndex 现认）。名称空时才退回 stId ——
        // 此时 stId 必定非空，两者都空的那条在循环里先被跳过。
        val seenKeys = HashSet<String>()
        for (i in 0 until membersJson.length()) {
            if (members.size >= MAX_MEMBERS) {
                GLog.w("策略合并：组「$name」成员超过上限 $MAX_MEMBERS，其余 ${membersJson.length() - i} 只已截断")
                break
            }
            val m = membersJson.optJSONObject(i) ?: continue
            val stId = m.optString("stId").trim()
            val memberName = m.optString("name").trim().takeIf { it.isNotEmpty() } ?: stId
            // 名称与 stId 都空：手改数据 / 半截写入的常见产物，直接丢（不是错误，不刷 warn）。
            // 只空 stId 是正常情况（界面只问名称），必须留下 —— 丢它就会出现
            // 「刚添加的成员不进列表」：写盘丢掉 → 配置回流 → 列表看起来没变。
            if (memberName.isEmpty()) continue
            if (!seenKeys.add(memberName)) continue
            members.add(MergeMember(stId = stId, name = memberName))
        }
        // enabled 缺失按「启用」处理：老数据 / 手写配置通常只写 id + members，
        // 若按「关闭」处理，用户会看到组在、合并却不生效（症状最难查）。
        val enabled = o.optBoolean("enabled", true)
        return MergeGroup(id = id, name = name, enabled = enabled, members = members)
    }

    // ------------------------------------------------------------ 序列化

    /**
     * 序列化为 v1 JSON，字段顺序固定（见文件头注释）。
     *
     * 超限与 [decode] 同口径截断：当场丢掉 + 一条 warn，好过「写进去、读回来时消失」。
     */
    fun encode(config: MergeConfig): String {
        val root = JSONObject()
        root.put("v", FORMAT_VERSION)
        val groupsJson = JSONArray()
        var groupCount = 0
        for (g in config.groups) {
            if (groupCount >= MAX_GROUPS) {
                GLog.w("策略合并：encode 组数超过上限 $MAX_GROUPS，多余组未写入")
                break
            }
            // 与 decode 同口径归一：trim 后为空的组不写、名称与 stId 都空的成员不写，
            // 否则「写进去的」和「读回来的」不是同一份（成员会静默消失）
            val id = g.id.trim()
            if (id.isEmpty()) {
                GLog.w("策略合并：encode 跳过 id 为空的组，未写入")
                continue
            }
            groupCount++
            val name = g.name.trim().takeIf { it.isNotEmpty() } ?: "聚合组 $groupCount"
            val go = JSONObject()
            go.put("id", id)
            go.put("name", name)
            go.put("enabled", g.enabled)
            val membersJson = JSONArray()
            var memberCount = 0
            // 与 decode 同一套去重口径：组内按名称去重，名称空时按 stId
            val seenKeys = HashSet<String>()
            for (m in g.members) {
                if (memberCount >= MAX_MEMBERS) {
                    GLog.w("策略合并：encode 组「$name」成员超过上限 $MAX_MEMBERS，多余成员未写入")
                    break
                }
                val stId = m.stId.trim()
                // stId 可留空：界面只问策略名称，编号由目标进程按名称补齐（见 StrategyStgIndex）。
                // 名称与 stId 都空才不写（不是错误，不刷 warn）—— 只空 stId 的成员必须照样写出去，
                // 不然「点了确定 → 写盘丢掉 → observe 回流 → 列表没变」，成员看起来根本没加上。
                val memberName = m.name.trim().takeIf { it.isNotEmpty() } ?: stId
                if (memberName.isEmpty()) continue
                if (!seenKeys.add(memberName)) continue
                memberCount++
                val mo = JSONObject()
                mo.put("stId", stId)
                mo.put("name", memberName)
                membersJson.put(mo)
            }
            go.put("members", membersJson)
            groupsJson.put(go)
        }
        root.put("groups", groupsJson)
        return root.toString()
    }
}
