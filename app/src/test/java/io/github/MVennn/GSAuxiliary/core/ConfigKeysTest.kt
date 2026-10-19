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
        // ConfigSnapshot 有 24 个字段（含 aiStareQuickConfig 这个数据键）。
        // SNAPSHOT_KEYS 应当与之一一对应 —— 少一个 = 目标进程收不到那个键的变更。
        assertEquals(
            24,
            ConfigKeys.SNAPSHOT_KEYS.size,
            "SNAPSHOT_KEYS 的条数与 ConfigSnapshot 的字段数对不上",
        )
    }
}
