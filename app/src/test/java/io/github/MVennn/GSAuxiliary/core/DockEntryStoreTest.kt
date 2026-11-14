package io.github.MVennn.GSAuxiliary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * 底栏入口配置的**迁移口径**与编解码往返。
 *
 * 钉住四件事：
 *
 *  1. 键没写过（空串 / null）时，老布尔键 `dock_clean` 为真等价于藏理财一格 ——
 *     老用户升级后表现必须不变；
 *  2. 键写过（哪怕 hidden 是空数组）时**只认新键** —— 否则用户在新页面把六个开关
 *     全关掉，老开关还会把理财格偷回来；
 *  3. 坏 JSON / 版本不符一律回落「一个都不藏」+ warn，绝不抛异常（目标进程 800ms 一轮，
 *     抛一次就把宿主底栏的重排链路整个打断）；
 *  4. 同一份集合 encode 两次逐字相同（按 KEYS 顺序输出，备份 diff 不抖）。
 */
class DockEntryStoreTest {

    @Test
    fun 空配置_老开关开着等于藏理财一格() {
        assertEquals(setOf(DockEntryStore.KEY_MONEY), DockEntryStore.decode("", true))
        assertEquals(setOf(DockEntryStore.KEY_MONEY), DockEntryStore.decode(null, true))
        assertEquals(setOf(DockEntryStore.KEY_MONEY), DockEntryStore.decode("   ", true))
    }

    @Test
    fun 空配置_老开关关着一个都不藏() {
        assertEquals(emptySet(), DockEntryStore.decode("", false))
        assertEquals(emptySet(), DockEntryStore.decode(null, false))
    }

    @Test
    fun 写过的空数组_老开关不再参与() {
        // 用户在新页面把六行全关掉 → 写进去的是空数组，此时老开关必须失效
        val json = DockEntryStore.encode(emptySet())
        assertTrue(json.contains("\"hidden\":[]"), "空集合应编成空数组：$json")
        assertEquals(emptySet(), DockEntryStore.decode(json, true))
    }

    @Test
    fun 不认识的key被丢掉_认识的保留() {
        val json = "{\"v\":1,\"hidden\":[\"money\",\"home\",\"not_a_tab\"]}"
        assertEquals(
            setOf(DockEntryStore.KEY_MONEY, DockEntryStore.KEY_HOME),
            DockEntryStore.decode(json, false),
        )
    }

    @Test
    fun 重复key去重() {
        val json = "{\"v\":1,\"hidden\":[\"trade\",\"trade\"]}"
        assertEquals(setOf(DockEntryStore.KEY_TRADE), DockEntryStore.decode(json, false))
    }

    @Test
    fun 坏JSON回落空集() {
        assertEquals(emptySet(), DockEntryStore.decode("{\"v\":1,\"hidden\":", false))
        assertEquals(emptySet(), DockEntryStore.decode("[]", false))
        assertEquals(emptySet(), DockEntryStore.decode("null", false))
    }

    @Test
    fun 版本不符回落空集() {
        val json = "{\"v\":2,\"hidden\":[\"money\"]}"
        assertEquals(emptySet(), DockEntryStore.decode(json, false))
    }

    @Test
    fun 缺v字段按当前版本处理() {
        // 手改配置 / 更早的数据可能没有 v：不能因此整份作废
        val json = "{\"hidden\":[\"quotes\"]}"
        assertEquals(setOf(DockEntryStore.KEY_QUOTES), DockEntryStore.decode(json, false))
    }

    @Test
    fun encode按底栏顺序输出_输入顺序无关() {
        val a = DockEntryStore.encode(setOf(DockEntryStore.KEY_MINE, DockEntryStore.KEY_HOME))
        val b = DockEntryStore.encode(setOf(DockEntryStore.KEY_HOME, DockEntryStore.KEY_MINE))
        // 两个对象键的书写顺序取决于 org.json 实现（Android 用 LinkedHashMap、JVM 版用 HashMap），
        // 所以只断言结构，不逐字比整串
        assertEquals(a, b)
        val obj = JSONObject(a)
        assertEquals(1, obj.getInt("v"))
        val arr = obj.getJSONArray("hidden")
        assertEquals(2, arr.length())
        assertEquals("home", arr.getString(0))
        assertEquals("mine", arr.getString(1))
    }

    @Test
    fun 往返一致() {
        val hidden = setOf(
            DockEntryStore.KEY_ZX,
            DockEntryStore.KEY_MONEY,
            DockEntryStore.KEY_TRADE,
        )
        assertEquals(hidden, DockEntryStore.decode(DockEntryStore.encode(hidden), false))
        // 数组项按 KEYS 顺序（home, quotes, zx, trade, money, mine）输出，与传入集合的迭代顺序无关
        val arr = JSONObject(DockEntryStore.encode(hidden)).getJSONArray("hidden")
        assertEquals(listOf("zx", "trade", "money"), (0 until arr.length()).map { arr.getString(it) })
    }

    @Test
    fun 出厂默认串_解码为空集() {
        assertEquals(emptySet(), DockEntryStore.decode(DockEntryStore.defaultJson(), true))
    }

    @Test
    fun 六个键全覆盖() {
        assertEquals(6, DockEntryStore.KEYS.size)
        assertEquals(DockEntryStore.KEYS.size, DockEntryStore.KEYS.toSet().size, "KEYS 里有重复")
    }
}
