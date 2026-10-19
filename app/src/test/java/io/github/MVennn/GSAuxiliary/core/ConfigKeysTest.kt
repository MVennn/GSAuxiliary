package io.github.MVennn.GSAuxiliary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 配置键的三处对齐。
 *
 * ## 这条测试为什么重要
 *
 * 配置键散在四个地方手工维护：`ConfigKeys`（定义）、`ConfigKeys.ALL_KEYS`（备份/重置范围）、
 * `ConfigKeys.FEATURE_KEYS` / `FEATURE_PARAM_KEYS`（重置时的两类归属）、
 * `ConfigKeys.SNAPSHOT_KEYS`（目标进程会读的键）。
 *
 * 任何一处漏加，用户就会遇到「开关在界面上、但功能不生效」或「重置模块没清干净」
 * 或「改了设置目标进程收不到」—— 而且**没有报错**。
 *
 * 历史上有过两次真事故：
 * - 「跳过保存确认」单独打开不生效（`isEnabled` 的 or 链漏一位 → **假开关**）；
 * - 第四轮审查 B-5：指纹碰撞让配置变更被静默丢弃。
 */
class ConfigKeysTest {

    @Test
    fun `全部键的出厂默认值都有 - 不能有键漏了默认值`() {
        val missing = ConfigKeys.ALL_KEYS.filterNot { ConfigKeys.DEFAULTS.containsKey(it) }
        assertTrue(missing.isEmpty(), "这些键没有出厂默认值：$missing")
    }

    @Test
    fun `默认值表里不能有多余的键 - 不能有键忘了登记进 ALL_KEYS`() {
        val extra = ConfigKeys.DEFAULTS.keys.filterNot { it in ConfigKeys.ALL_KEYS }
        assertTrue(extra.isEmpty(), "这些键有默认值但没登记进 ALL_KEYS（备份/重置会漏掉）：$extra")
    }

    @Test
    fun `功能键与参数键 不重叠 且 剩下的都是模块级设置`() {
        // 两类不能重叠（重置时会冲突：一边写 false、一边写默认值）
        val overlap = ConfigKeys.FEATURE_KEYS.filter { it in ConfigKeys.FEATURE_PARAM_KEYS }
        assertTrue(overlap.isEmpty(), "这些键同时属于两类（重置时会冲突）：$overlap")

        // 「重置模块」只清功能开关 + 功能参数，**不动**模块级设置
        // （桌面图标 / 液态玻璃 / 玻璃效果 / 语言 / 深色模式 / 可预测返回 / 日志等级）。
        // 剩下的键必须正好是这批 —— 多一个少一个都要查。
        val both = ConfigKeys.FEATURE_KEYS.toSet() + ConfigKeys.FEATURE_PARAM_KEYS.toSet()
        val moduleLevel = ConfigKeys.ALL_KEYS.filterNot { it in both }.toSet()
        val expected = setOf(
            ConfigKeys.DESKTOP_ICON, ConfigKeys.LIQUID_GLASS, ConfigKeys.GLASS_EFFECT,
            ConfigKeys.LANGUAGE, ConfigKeys.THEME, ConfigKeys.PREDICTIVE_BACK, ConfigKeys.LOG_LEVEL,
        )
        assertEquals(
            expected,
            moduleLevel,
            "既不是功能键也不是参数键的那批，应当正好是模块级设置（重置模块不动它们）",
        )
    }

    @Test
    fun `进快照的键必须都是已登记的键 - 不能有拼错的键名`() {
        val unknown = ConfigKeys.SNAPSHOT_KEYS.filterNot { it in ConfigKeys.ALL_KEYS }
        assertTrue(unknown.isEmpty(), "SNAPSHOT_KEYS 里有未登记的键：$unknown")
    }

    @Test
    fun `功能键的出厂默认一律是 false - 装完模块不代表用户同意被改`() {
        // 硬约定（2.11.2 起）：功能菜单里每一个开关的出厂默认都必须是关。
        // 「重置模块」也是显式写 false（见 ModulePreferences.writeFeatureDefaults）——
        // 不跟着默认值联动：哪天某个功能又想默认开启，重置逻辑不必跟着改。
        val notFalse = ConfigKeys.FEATURE_KEYS.filter { key ->
            ConfigKeys.DEFAULTS[key] != false
        }
        assertTrue(
            notFalse.isEmpty(),
            "这些功能开关的出厂默认不是 false：$notFalse",
        )
    }

    @Test
    fun `快照键的数量与快照字段数对得上`() {
        // ConfigSnapshot 的每个字段都要有一个对应的键进 SNAPSHOT_KEYS ——
        // 少一个 = 目标进程**收不到**那个键的变更（配置改了不生效，且没有日志）。
        //
        // ⚠️ 这里**不硬编码个数**：新增配置项时个数会变，硬编码会让这条测试
        // 变成「每次加键都要改测试」的负担 —— 真正的��变式是「两边相等」。
        // 快照的**配置字段** = 构造参数里除 fingerprint 外的那些。
        // 用源码解析（而不是反射）—— 免得为了读参数名再引 kotlin-reflect。
        // 快照的**配置字段** = 构造参数里除 fingerprint 外的那些。
        val snapshotFields = snapshotFieldNames()

        // 逐个字段对：**按「键常量名 ↔ 快照字段名」的正则映射**找对应。
        // 不用「驼峰化后相等」—— 命名风格并不完全一致
        // （键常量 `AISTARE_*` ↔ 字段 `aiStare*`，中间多一个大写 S），
        // 那种「猜命名规则」的写法在命名一改就会误报。
        // 这里只要求：**字段名去掉分隔符后**，出现在某个键常量名里（或反过来）。
        fun matches(field: String, constName: String): Boolean {
            val f = field.lowercase()
            val c = constName.lowercase().replace("_", "")
            return c == f || c.contains(f) || f.contains(c)
        }

        // 逐个字段对：每个字段都得有一个键在 SNAPSHOT_KEYS 里
        val missing = snapshotFields.filterNot { field ->
            ConfigKeys.SNAPSHOT_KEYS.any { matches(field, it) }
        }
        assertTrue(
            missing.isEmpty(),
            "这些快照字段没有对应的键进 SNAPSHOT_KEYS —— 目标进程收不到它们的变更：$missing",
        )

        // 反向：SNAPSHOT_KEYS 里不能有快照里没有的键（那是白登记）
        val extra = ConfigKeys.SNAPSHOT_KEYS.filter { key ->
            snapshotFields.none { matches(it, key) }
        }
        assertTrue(extra.isEmpty(), "SNAPSHOT_KEYS 里有快照中不存在的键：$extra")
    }

    /** 从 ConfigSnapshot.kt 的源码里读出构造参数名（配置字段） */
    private fun snapshotFieldNames(): Set<String> {
        val src = java.io.File("src/main/java/io/github/MVennn/GSAuxiliary/core/ConfigSnapshot.kt")
        assertTrue(src.exists(), "找不到 ConfigSnapshot.kt（测试的工作目录不对？）")
        // 逐行扫 `data class ConfigSnapshot(` 到对应 `) {` 之间的构造参数
        val names = LinkedHashSet<String>()
        var inside = false
        for (line in src.readLines()) {
            val t = line.trim()
            if (t.startsWith("data class ConfigSnapshot(")) {
                inside = true
                continue
            }
            if (inside) {
                if (t == ") {") break
                // `val 字段名: 类型,` —— 构造参数（缩进 4 空格）
                if (t.startsWith("val ") && t.contains(":")) {
                    names += t.removePrefix("val ").substringBefore(':').trim()
                }
            }
        }
        assertTrue(names.isNotEmpty(), "没能从 ConfigSnapshot.kt 读出字段（源码格式变了？）")
        // fingerprint 是算出来的，不是配置键
        return names - "fingerprint"
    }
}
