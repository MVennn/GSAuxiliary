package io.github.MVennn.GSAuxiliary.core.zxcopy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 规则 JSON：默认值、往返、坏数据降级、未知字段丢弃 */
class ZxCopyRuleStoreTest {

    @Test
    fun `出厂规则是 名称 代码 纯文本`() {
        val data = ZxCopyRuleStore.defaultData()
        val rule = data.defaultRule()
        assertNotNull(rule)
        assertEquals("r1", data.defaultId)
        assertEquals(listOf("name", "code"), rule.fields)
        assertEquals(ZxCopyRuleStore.FORMAT_TEXT, rule.format)
        assertEquals(" ", rule.separator)
        assertEquals(ZxCopyRuleStore.ACTION_CLIPBOARD, rule.action)
        assertTrue(rule.isDefault)
    }

    @Test
    fun `encode decode 往返保持规则`() {
        val source = ZxCopyRuleStore.defaultData().defaultRule()!!
            .copy(
                fields = listOf("name", "code", "changePct", "floatCap"),
                labels = mapOf(
                    "name" to "名称", "code" to "代码",
                    "changePct" to "涨幅", "floatCap" to "流通市值",
                ),
                format = ZxCopyRuleStore.FORMAT_MARKDOWN,
                header = true,
                includeGroup = true,
                sort = ZxCopyRuleStore.SORT_ZDF_DESC,
                action = ZxCopyRuleStore.ACTION_IMAGE,
                image = ZxCopyRuleStore.ImageStyle(
                    preset = ZxCopyRuleStore.IMAGE_CARD,
                    dark = true,
                    rowsPerPage = 50,
                ),
                pdf = ZxCopyRuleStore.PdfStyle(landscape = true, fontSize = 12),
            )
        val json = ZxCopyRuleStore.encode(
            ZxCopyRuleStore.Data(source.id, listOf(source)),
        )
        val back = ZxCopyRuleStore.decode(json).defaultRule()!!
        assertEquals(source.fields, back.fields)
        assertEquals(source.format, back.format)
        assertEquals(source.sort, back.sort)
        assertEquals(source.action, back.action)
        assertEquals(ZxCopyRuleStore.IMAGE_CARD, back.image.preset)
        assertTrue(back.image.dark)
        assertEquals(50, back.image.rowsPerPage)
        assertTrue(back.pdf.landscape)
        assertEquals(12, back.pdf.fontSize)
        assertEquals("涨幅", back.labels["changePct"])
    }

    @Test
    fun `坏 JSON 回落出厂规则`() {
        val data = ZxCopyRuleStore.decode("{not json")
        assertEquals("r1", data.defaultId)
        assertEquals(listOf("name", "code"), data.defaultRule()!!.fields)
    }

    @Test
    fun `未知字段丢弃 不连坐整条规则`() {
        val json = """
            {"v":1,"defaultId":"r1","rules":[
              {"id":"r1","name":"测试","fields":["name","no_such_field","code"],
               "format":"text","sort":"screen","empty":"dash","action":"clipboard"}
            ]}
        """.trimIndent()
        val rule = ZxCopyRuleStore.decode(json).defaultRule()!!
        assertEquals(listOf("name", "code"), rule.fields)
    }

    @Test
    fun `重复 id 只留第一条`() {
        val json = """
            {"v":1,"defaultId":"r1","rules":[
              {"id":"r1","name":"第一","fields":["name"],"format":"text"},
              {"id":"r1","name":"第二","fields":["code"],"format":"text"}
            ]}
        """.trimIndent()
        val data = ZxCopyRuleStore.decode(json)
        assertEquals(1, data.rules.size)
        assertEquals("第一", data.rules.first().name)
    }

    @Test
    fun `defaultId 失效回落第一条`() {
        val json = """
            {"v":1,"defaultId":"gone","rules":[
              {"id":"r1","name":"一","fields":["name"],"format":"text"},
              {"id":"r2","name":"二","fields":["code"],"format":"text"}
            ]}
        """.trimIndent()
        assertEquals("r1", ZxCopyRuleStore.decode(json).defaultId)
    }

    @Test
    fun `labels 缺失时用内置中文标签`() {
        val json = """
            {"v":1,"defaultId":"r1","rules":[
              {"id":"r1","name":"测试","fields":["name","changePct"],"format":"markdown"}
            ]}
        """.trimIndent()
        val rule = ZxCopyRuleStore.decode(json).defaultRule()!!
        assertEquals("名称", rule.labels["name"])
        assertEquals("涨幅", rule.labels["changePct"])
    }

    @Test
    fun `未知格式与动作回落安全默认`() {
        val json = """
            {"v":1,"defaultId":"r1","rules":[
              {"id":"r1","name":"测试","fields":["name"],"format":"xml",
               "sort":"magic","empty":"skip","action":"teleport"}
            ]}
        """.trimIndent()
        val rule = ZxCopyRuleStore.decode(json).defaultRule()!!
        assertEquals(ZxCopyRuleStore.FORMAT_TEXT, rule.format)
        assertEquals(ZxCopyRuleStore.SORT_SCREEN, rule.sort)
        assertEquals(ZxCopyRuleStore.EMPTY_DASH, rule.empty)
        assertEquals(ZxCopyRuleStore.ACTION_CLIPBOARD, rule.action)
    }

    @Test
    fun `newId 与 newName 不撞现有值`() {
        val rules = listOf(
            ZxCopyRuleStore.defaultData().defaultRule()!!,
            ZxCopyRuleStore.defaultData().defaultRule()!!.copy(id = "r3", name = "规则1"),
        )
        assertEquals("r4", ZxCopyRuleStore.newId(rules))
        assertEquals("规则2", ZxCopyRuleStore.newName(rules.map { it.name }, "规则"))
    }

    @Test
    fun `duplicate 清掉默认标记与预览`() {
        val source = ZxCopyRuleStore.defaultData().defaultRule()!!
            .copy(preview = "旧预览")
        val copy = ZxCopyRuleStore.duplicate(source, "r9", "副本")
        assertEquals("r9", copy.id)
        assertEquals("副本", copy.name)
        assertEquals("", copy.preview)
        assertTrue(!copy.isDefault)
        assertEquals(source.fields, copy.fields)
    }
}
