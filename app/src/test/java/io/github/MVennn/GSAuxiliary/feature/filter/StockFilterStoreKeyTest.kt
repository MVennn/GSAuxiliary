package io.github.MVennn.GSAuxiliary.feature.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 筛选状态的**落盘键**与**出厂值**。
 *
 * ## 这条测试为什么重要
 *
 * 策略详情页那颗面板与行情页那颗面板**共用同一份状态**
 * （`gsaux_stg_filter` 这个 prefs 文件 + 进程级缓存，见 [StockFilterStore] 的类注释）。
 * 键名一旦对不上，两边就各读各的 —— 表现为「在一个面板改了条件，另一个面板不跟着变」，
 * 而且**没有任何报错**（读不到就回落出厂值，静得很）。
 *
 * 出厂值同理：方向三项默认 `false`（= 不筛方向）、类型五项默认 `true`（= 全显示）——
 * 与宿主行情筛面板的出厂状态一致，用户第一次打开两个面板看到的都是「没在筛」。
 * 出厂值反了的话，用户一开面板就被筛掉一批股票。
 */
class StockFilterStoreKeyTest {

    @Test
    fun `每一行的落盘键都是 filter_加行标识`() {
        for (row in FilterRow.entries) {
            assertEquals(
                "filter_${row.key}",
                StockFilterStore.keyOf(row),
                "${row.key} 的落盘键不对",
            )
        }
    }

    @Test
    fun `八个键互不相同 - 否则两行会互相覆盖`() {
        val keys = FilterRow.entries.map { StockFilterStore.keyOf(it) }
        assertEquals(keys.size, keys.toSet().size, "有两个行用了同一个落盘键：$keys")
    }

    @Test
    fun `方向三项的出厂值是 false - 不筛方向`() {
        for (row in FilterRow.DIRECTION_ROWS) {
            assertEquals(
                false,
                StockFilterStore.defaultOf(row),
                "${row.label} 的出厂值应该是 false（不筛方向）",
            )
        }
    }

    @Test
    fun `类型五项的出厂值是 true - 全显示`() {
        for (row in FilterRow.TYPE_ROWS) {
            assertEquals(
                true,
                StockFilterStore.defaultOf(row),
                "${row.label} 的出厂值应该是 true（全显示，与宿主面板一致）",
            )
        }
    }

    @Test
    fun `出厂值合起来正好是不筛`() {
        // 八项都取出厂值 → 应该等于 StockFilters.OFF（不筛）
        var f = StockFilters.OFF
        for (row in FilterRow.entries) f = f.set(row, StockFilterStore.defaultOf(row))
        assertEquals(StockFilters.OFF, f, "八项出厂值合起来应该正好是「不筛」")
        assertTrue(!f.active, "出厂状态不该有任何筛选生效")
    }

    @Test
    fun `历史的三个键名没变 - 升级不能丢用户已有的设置`() {
        // 2.16.1 起的三个方向键一直叫这三个名字
        // （改了就等于把老用户已存的筛选状态丢掉）
        assertEquals("filter_up", StockFilterStore.keyOf(FilterRow.UP))
        assertEquals("filter_down", StockFilterStore.keyOf(FilterRow.DOWN))
        assertEquals("filter_zx", StockFilterStore.keyOf(FilterRow.ZX))
    }

    @Test
    fun `类型五项的键名固定`() {
        assertEquals("filter_cyb", StockFilterStore.keyOf(FilterRow.CYB))
        assertEquals("filter_kcb", StockFilterStore.keyOf(FilterRow.KCB))
        assertEquals("filter_bza", StockFilterStore.keyOf(FilterRow.BZA))
        assertEquals("filter_wkb", StockFilterStore.keyOf(FilterRow.WKB))
        assertEquals("filter_st", StockFilterStore.keyOf(FilterRow.ST))
    }
}
