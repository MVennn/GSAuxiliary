package io.github.MVennn.GSAuxiliary.core.zxcopy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 五种输出格式、排序、元信息、空值策略 */
class ZxCopyFormatTest {

    private val rule = ZxCopyRuleStore.defaultData().defaultRule()!!

    private val meta = ZxCopyFormat.Meta(
        groupName = "全部",
        timestamp = 0L,
        count = 2,
        title = "自选分组",
    )

    private fun row(name: String?, code: String, zdf: Double = 0.0) = ZxCopyRow(
        values = buildMap {
            if (name != null) put("name", name)
            put("code", code)
            put("price", "10.00")
            put("changePct", if (zdf >= 0) "+${zdf}%" else "$zdf%")
        },
        sort = mapOf("changePct" to zdf),
    )

    @Test
    fun `默认文本是 名称 空格 代码 一行一只`() {
        val text = ZxCopyFormat.render(
            listOf(row("金山办公", "688111"), row("爱尔眼科", "300015")),
            rule, meta,
        )
        assertEquals("金山办公 688111\n爱尔眼科 300015", text)
    }

    @Test
    fun `名称缺失时只写代码`() {
        val text = ZxCopyFormat.render(listOf(row(null, "688111")), rule, meta)
        assertEquals("688111", text)
    }

    @Test
    fun `文本空值策略 dash 与 blank`() {
        val r = rule.copy(fields = listOf("name", "code", "price"), separator = " ")
        val missingPrice = ZxCopyRow(mapOf("name" to "金山办公", "code" to "688111"))
        assertEquals("金山办公 688111 -", ZxCopyFormat.render(listOf(missingPrice), r, meta))
        assertEquals(
            "金山办公 688111",
            ZxCopyFormat.render(listOf(missingPrice), r.copy(empty = ZxCopyRuleStore.EMPTY_BLANK), meta),
        )
    }

    @Test
    fun `Markdown 表格带表头并转义竖线`() {
        val r = rule.copy(
            fields = listOf("name", "code", "changePct"),
            labels = mapOf("name" to "名称", "code" to "代码", "changePct" to "涨幅"),
            format = ZxCopyRuleStore.FORMAT_MARKDOWN,
        )
        val text = ZxCopyFormat.render(
            listOf(row("金山|办公", "688111", 5.37)),
            r, meta,
        )
        assertTrue(text.contains("| 名称 | 代码 | 涨幅 |"))
        assertTrue(text.contains("| 金山\\|办公 | 688111 | +5.37% |"))
    }

    @Test
    fun `CSV 带 BOM 与表头 含逗号的字段加引号`() {
        val r = rule.copy(
            fields = listOf("name", "code"),
            labels = mapOf("name" to "名称", "code" to "代码"),
            format = ZxCopyRuleStore.FORMAT_CSV,
            header = true,
        )
        val text = ZxCopyFormat.render(listOf(row("金山,办公", "688111")), r, meta)
        assertTrue(text.startsWith("\uFEFF"))
        assertTrue(text.contains("名称,代码"))
        assertTrue(text.contains("\"金山,办公\",688111"))
    }

    @Test
    fun `JSON 键用字段 id`() {
        val r = rule.copy(
            fields = listOf("name", "code", "changePct"),
            format = ZxCopyRuleStore.FORMAT_JSON,
        )
        val text = ZxCopyFormat.render(listOf(row("金山办公", "688111", 5.37)), r, meta)
        assertTrue(text.contains("\"name\""))
        assertTrue(text.contains("\"code\": \"688111\""))
        assertTrue(text.contains("\"changePct\": \"+5.37%\""))
    }

    @Test
    fun `自定义模板替换占位符与序号`() {
        val r = rule.copy(
            fields = listOf("name", "code", "changePct"),
            format = ZxCopyRuleStore.FORMAT_TEMPLATE,
            template = "{index}. {name}({code}) {changePct}",
        )
        val text = ZxCopyFormat.render(
            listOf(row("金山办公", "688111", 5.37), row("爱尔眼科", "300015", 2.26)),
            r, meta,
        )
        assertEquals(
            "1. 金山办公(688111) +5.37%\n2. 爱尔眼科(300015) +2.26%",
            text,
        )
    }

    @Test
    fun `按涨幅降序与升序排序`() {
        val rows = listOf(
            row("A", "000001", 1.0),
            row("B", "000002", 5.0),
            row("C", "000003", -2.0),
        )
        val desc = ZxCopyFormat.render(
            rows, rule.copy(sort = ZxCopyRuleStore.SORT_ZDF_DESC), meta,
        )
        assertEquals("B 000002\nA 000001\nC 000003", desc)
        val asc = ZxCopyFormat.render(
            rows, rule.copy(sort = ZxCopyRuleStore.SORT_ZDF_ASC), meta,
        )
        assertEquals("C 000003\nA 000001\nB 000002", asc)
    }

    @Test
    fun `文本元信息包含分组 时间 数量`() {
        val r = rule.copy(includeGroup = true, includeTime = true, includeCount = true)
        val text = ZxCopyFormat.render(listOf(row("金山办公", "688111")), r, meta)
        assertTrue(text.startsWith("分组：全部\n时间："))
        assertTrue(text.contains("\n数量：2 只\n金山办公 688111"))
    }

    @Test
    fun `Markdown 元信息在表格之前`() {
        val r = rule.copy(
            fields = listOf("name", "code"),
            format = ZxCopyRuleStore.FORMAT_MARKDOWN,
            includeGroup = true,
            header = true,
            headerText = "我的自选",
        )
        val text = ZxCopyFormat.render(listOf(row("金山办公", "688111")), r, meta)
        assertTrue(text.startsWith("分组：全部\n\n我的自选\n\n| 名称 | 代码 |"))
    }

    @Test
    fun `预览样例覆盖所有字段且带排序值`() {
        val sample = ZxCopyFormat.sampleRows()
        assertEquals(3, sample.size)
        val values = sample.first().values
        for (spec in ZxCopyFieldCatalog.ALL) {
            assertTrue(values.containsKey(spec.id), "样例缺字段 ${spec.id}")
        }
        assertTrue(sample.first().sort.containsKey("changePct"))
    }
}
