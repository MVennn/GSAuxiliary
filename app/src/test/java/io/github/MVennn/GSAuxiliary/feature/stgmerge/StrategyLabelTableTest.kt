package io.github.MVennn.GSAuxiliary.feature.stgmerge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 宿主标签全表的解析回归。
 *
 * 真机上出过一次「菜单弹出来是空的」：表体用制表符分隔，读取端却按 U+0001 切，
 * 于是 1119 行全被当成脏数据丢掉，菜单渲染出 0 行。这里把条数与分组钉死。
 */
class StrategyLabelTableTest {

    @Test
    fun `表体解析出全部条目`() {
        assertEquals(1119, StrategyLabelTable.entries.size)
    }

    @Test
    fun `五个分组都有内容`() {
        // 宿主 level1 有 6 组，其中「热门标签」是客户端按行情动态拼的，queryLabelList 不返回
        val tree = StrategyLabelTable.tree
        assertEquals(5, tree.size)
        for ((name, labels) in tree) {
            assertTrue(name.isNotEmpty(), "分组名不该为空")
            assertTrue(labels.isNotEmpty(), "分组 $name 下一个标签都没有")
        }
    }

    @Test
    fun `量比这一档的显示名与档位数`() {
        val entry = StrategyLabelTable.entryOf("1363924975269969920")
        assertNotNull(entry)
        assertEquals("1.5-2.0", entry.labelName)
        assertEquals("量比1.5-2.0", entry.display)
        val node = StrategyLabelTable.tree.flatMap { it.second }.firstOrNull { it.labelId == entry.parentId }
        assertNotNull(node)
        assertEquals("量比", node.name)
        assertEquals(8, node.bands.size)
    }

    @Test
    fun `没有父标签的条目是可直接点的标签`() {
        val entry = StrategyLabelTable.entries.firstOrNull { it.parentName.isEmpty() }
        assertNotNull(entry)
        assertEquals(entry.labelName, entry.display)
    }

    @Test
    fun `不认识的 labelId 原样回落`() {
        assertEquals("nope", StrategyLabelTable.displayOf("nope"))
        assertNull(StrategyLabelTable.entryOf("nope"))
    }

    @Test
    fun `标签成员的显示名走标签表`() {
        val key = StrategyComboCatalog.LABEL_PREFIX + "1363924975269969920"
        assertEquals("量比1.5-2.0", StrategyComboCatalog.displayName(key))
        assertEquals(listOf("量比1.5-2.0"), StrategyComboCatalog.tagNamesOfKey(key))
        val combo = StrategyComboCatalog.comboOfKey(key)
        assertNotNull(combo)
        assertEquals(listOf("1363924975269969920"), combo.labelIds)
    }

    @Test
    fun `标签成员按 1 条算进展开数`() {
        val key = StrategyComboCatalog.LABEL_PREFIX + "1363924975269969920"
        val members = listOf(
            io.github.MVennn.GSAuxiliary.core.MergeMember(stId = "", name = key),
            io.github.MVennn.GSAuxiliary.core.MergeMember(stId = "1", name = "量50"),
        )
        assertEquals(2, StrategyComboCatalog.expandedCount(members))
    }
}