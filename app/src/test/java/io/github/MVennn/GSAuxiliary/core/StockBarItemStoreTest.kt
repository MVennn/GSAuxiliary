package io.github.MVennn.GSAuxiliary.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * 详情页底栏入口配置的**迁移口径**与编解码往返。
 *
 * 钉住四件事：
 *
 *  1. 键没写过（空串 / null）时，老布尔键 `stock_bar_entry` 为真等价于只提「智能盯盘」一项 ——
 *     老用户升级后底栏表现必须不变；
 *  2. 键写过（哪怕 items 是空数组）时**只认新键** —— 否则用户在新页面把七个开关
 *     全关掉，老开关还会把「智能盯盘」偷回来；
 *  3. 坏 JSON / 版本不符一律回落「一个都不提」+ warn，绝不抛异常（目标进程 800ms 一轮，
 *     抛一次就把宿主底栏的注入链路整个打断）；
 *  4. 容量上限 2 在读写两侧都截断，且同一份集合 encode 两次逐字相同（按 KEYS 顺序输出）。
 */
class StockBarItemStoreTest {

    /** 造一份 v1 结构（顺序照传入顺序，用来验「读的时候顺序无关、写的时候按 KEYS 重排」） */
    private fun raw(vararg items: String): String =
        "{\"v\":1,\"items\":[" + items.joinToString(",") { "\"" + it + "\"" } + "]}"

    @Test
    fun 空配置_老开关开着等于只提智能盯盘() {
        assertEquals(listOf(StockBarItemStore.KEY_AISTARE), StockBarItemStore.decode("", true))
        assertEquals(listOf(StockBarItemStore.KEY_AISTARE), StockBarItemStore.decode(null, true))
        assertEquals(listOf(StockBarItemStore.KEY_AISTARE), StockBarItemStore.decode("   ", true))
    }

    @Test
    fun 空配置_老开关关着一个都不提() {
        assertEquals(emptyList(), StockBarItemStore.decode("", false))
        assertEquals(emptyList(), StockBarItemStore.decode(null, false))
    }

    @Test
    fun 写过的空数组_老开关不再参与() {
        // 用户在新页面把七行全关掉 → 写进去的是空数组，此时老开关必须失效
        val json = StockBarItemStore.encode(emptyList())
        assertTrue(json.contains("\"items\":[]"), "空集合应编成空数组：$json")
        assertEquals(emptyList(), StockBarItemStore.decode(json, true))
    }

    @Test
    fun 不认识的key被丢掉_认识的保留() {
        assertEquals(
            listOf(StockBarItemStore.KEY_RANGE),
            StockBarItemStore.decode(raw("nope", StockBarItemStore.KEY_RANGE), false),
        )
    }

    @Test
    fun 重复key去重() {
        assertEquals(
            listOf(StockBarItemStore.KEY_PK),
            StockBarItemStore.decode(raw(StockBarItemStore.KEY_PK, StockBarItemStore.KEY_PK), false),
        )
    }

    @Test
    fun 坏JSON回落空列表() {
        assertEquals(emptyList(), StockBarItemStore.decode("{oops", false))
        assertEquals(emptyList(), StockBarItemStore.decode("[]", false))
        assertEquals(emptyList(), StockBarItemStore.decode("\"aistare\"", false))
    }

    @Test
    fun 版本不符回落空列表() {
        val v2 = "{\"v\":2,\"items\":[\"aistare\"]}"
        assertEquals(emptyList(), StockBarItemStore.decode(v2, false))
    }

    @Test
    fun 缺v字段按当前版本处理() {
        val noVersion = "{\"items\":[\"aistare\"]}"
        assertEquals(listOf(StockBarItemStore.KEY_AISTARE), StockBarItemStore.decode(noVersion, false))
    }

    @Test
    fun 超过容量只保留前两个() {
        val three = raw(
            StockBarItemStore.KEY_CONDITION,
            StockBarItemStore.KEY_AISTARE,
            StockBarItemStore.KEY_KLINE,
        )
        assertEquals(
            listOf(StockBarItemStore.KEY_CONDITION, StockBarItemStore.KEY_AISTARE),
            StockBarItemStore.decode(three, false),
        )
    }

    @Test
    fun encode按面板顺序输出_输入顺序无关() {
        // 传进去是倒序，写出来必须按 KEYS（面板）顺序，且容量截断后留下的是靠前那两项
        val a = StockBarItemStore.encode(
            listOf(StockBarItemStore.KEY_PK, StockBarItemStore.KEY_AISTARE),
        )
        val obj = JSONObject(a)
        assertEquals(1, obj.getInt("v"))
        val items = obj.getJSONArray("items")
        assertEquals(2, items.length())
        assertEquals(StockBarItemStore.KEY_AISTARE, items.getString(0))
        assertEquals(StockBarItemStore.KEY_PK, items.getString(1))
    }

    @Test
    fun 往返一致() {
        // 真实项里挑两个做往返，确认 decode(encode(x)) == x（顺序按 KEYS 归一）
        val picked = listOf(StockBarItemStore.KEY_RANGE, StockBarItemStore.KEY_TRADES)
        assertEquals(picked, StockBarItemStore.decode(StockBarItemStore.encode(picked), false))
    }

    @Test
    fun 出厂默认串_解码为空列表() {
        assertEquals(emptyList(), StockBarItemStore.decode(StockBarItemStore.defaultJson(), false))
    }
}