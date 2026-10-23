package io.github.MVennn.GSAuxiliary.core.zxcopy

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则 → 文本的渲染（纯逻辑，可单测）。
 *
 * 支持 5 种格式：
 *  - [ZxCopyRuleStore.FORMAT_TEXT]：字段用 `separator` 连接，行用 `rowSeparator`；
 *  - [ZxCopyRuleStore.FORMAT_MARKDOWN]：GitHub 风格表格；
 *  - [ZxCopyRuleStore.FORMAT_CSV]：带 BOM、可选表头；
 *  - [ZxCopyRuleStore.FORMAT_JSON]：数组，键用字段 id；
 *  - [ZxCopyRuleStore.FORMAT_TEMPLATE]：逐行模板（`{name} {code} {changePct}`）。
 *
 * 元信息（分组名 / 时间 / 数量）只对 text / markdown / template 生效；
 * CSV / JSON 保持机器可解析，不塞头部与页脚。
 */
object ZxCopyFormat {

    data class Meta(
        val groupName: String?,
        val timestamp: Long,
        val count: Int,
        /** 图片 / PDF 的大标题；为 null 时用「自选分组」 */
        val title: String? = null,
    )

    /** 排序后的行（稳定排序；屏幕顺序原样返回） */
    fun sorted(rows: List<ZxCopyRow>, rule: ZxCopyRuleStore.Rule): List<ZxCopyRow> {
        return when (rule.sort) {
            ZxCopyRuleStore.SORT_CODE -> rows.sortedBy { it.value("code") }
            ZxCopyRuleStore.SORT_NAME -> rows.sortedBy { it.value("name") }
            ZxCopyRuleStore.SORT_ZDF_DESC -> rows.sortedWith(
                compareByDescending<ZxCopyRow> { it.sort["changePct"] ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.value("code") },
            )
            ZxCopyRuleStore.SORT_ZDF_ASC -> rows.sortedWith(
                compareBy<ZxCopyRow> { it.sort["changePct"] ?: Double.POSITIVE_INFINITY }
                    .thenBy { it.value("code") },
            )
            else -> rows
        }
    }

    fun render(
        rows: List<ZxCopyRow>,
        rule: ZxCopyRuleStore.Rule,
        meta: ZxCopyFormat.Meta,
    ): String {
        val sorted = sorted(rows, rule)
        return when (rule.format) {
            ZxCopyRuleStore.FORMAT_MARKDOWN -> markdown(sorted, rule, meta)
            ZxCopyRuleStore.FORMAT_CSV -> csv(sorted, rule)
            ZxCopyRuleStore.FORMAT_JSON -> json(sorted, rule)
            ZxCopyRuleStore.FORMAT_TEMPLATE -> template(sorted, rule, meta)
            else -> text(sorted, rule, meta)
        }
    }

    fun label(rule: ZxCopyRuleStore.Rule, fieldId: String): String =
        rule.labels[fieldId]?.takeIf { it.isNotBlank() }
            ?: ZxCopyFieldCatalog.builtinLabel(fieldId)
            ?: fieldId

    fun formatTime(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))

    // ------------------------------------------------------------------ text

    private fun text(
        rows: List<ZxCopyRow>,
        rule: ZxCopyRuleStore.Rule,
        meta: Meta,
    ): String {
        val lines = ArrayList<String>(rows.size + 4)
        lines.addAll(metaLines(rule, meta))
        if (rule.header && rule.headerText.isNotBlank()) lines.add(rule.headerText)
        for (row in rows) {
            val parts = ArrayList<String>(rule.fields.size)
            for (fid in rule.fields) {
                val v = row.value(fid)
                if (v.isEmpty()) {
                    // 名称缺失时只写代码（与第一阶段的输出一致），不要留下一个「- 688111」
                    if (fid == "name") continue
                    if (rule.empty == ZxCopyRuleStore.EMPTY_BLANK) continue
                    parts.add("-")
                } else {
                    parts.add(v)
                }
            }
            lines.add(parts.joinToString(rule.separator))
        }
        if (rule.footer && rule.footerText.isNotBlank()) lines.add(rule.footerText)
        return lines.joinToString(rule.rowSeparator)
    }

    // ------------------------------------------------------------------ markdown

    private fun markdown(
        rows: List<ZxCopyRow>,
        rule: ZxCopyRuleStore.Rule,
        meta: Meta,
    ): String {
        val out = StringBuilder()
        val metaText = metaLines(rule, meta)
        if (metaText.isNotEmpty()) {
            out.append(metaText.joinToString("\n"))
            out.append("\n\n")
        }
        if (rule.header && rule.headerText.isNotBlank()) {
            out.append(rule.headerText).append("\n\n")
        }
        if (rule.fields.isNotEmpty()) {
            out.append("| ")
            out.append(rule.fields.joinToString(" | ") { escapeMd(label(rule, it)) })
            out.append(" |\n| ")
            out.append(rule.fields.joinToString(" | ") { "---" })
            out.append(" |\n")
            for (row in rows) {
                out.append("| ")
                out.append(
                    rule.fields.joinToString(" | ") { fid ->
                        escapeMd(cell(row.value(fid), rule))
                    },
                )
                out.append(" |\n")
            }
        }
        if (rule.footer && rule.footerText.isNotBlank()) {
            out.append("\n").append(rule.footerText)
        }
        return out.toString().trimEnd()
    }

    private fun escapeMd(s: String): String =
        s.replace("|", "\\|").replace("\r", " ").replace("\n", " ")

    // ------------------------------------------------------------------ csv

    private fun csv(rows: List<ZxCopyRow>, rule: ZxCopyRuleStore.Rule): String {
        val out = StringBuilder()
        out.append('\uFEFF')
        if (rule.header && rule.fields.isNotEmpty()) {
            out.append(rule.fields.joinToString(",") { escapeCsv(label(rule, it)) }).append('\n')
        }
        for (row in rows) {
            out.append(
                rule.fields.joinToString(",") { fid -> escapeCsv(cell(row.value(fid), rule)) },
            ).append('\n')
        }
        return out.toString().trimEnd('\n')
    }

    private fun escapeCsv(s: String): String {
        val needsQuote = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val body = s.replace("\"", "\"\"")
        return if (needsQuote) "\"$body\"" else body
    }

    // ------------------------------------------------------------------ json

    private fun json(rows: List<ZxCopyRow>, rule: ZxCopyRuleStore.Rule): String {
        val arr = JSONArray()
        for (row in rows) {
            val o = JSONObject()
            for (fid in rule.fields) o.put(fid, cell(row.value(fid), rule))
            arr.put(o)
        }
        return arr.toString(2)
    }

    // ------------------------------------------------------------------ template

    private fun template(
        rows: List<ZxCopyRow>,
        rule: ZxCopyRuleStore.Rule,
        meta: Meta,
    ): String {
        val tpl = rule.template.takeIf { it.isNotBlank() }
            ?: return text(rows, rule, meta)
        val lines = ArrayList<String>(rows.size + 4)
        lines.addAll(metaLines(rule, meta))
        if (rule.header && rule.headerText.isNotBlank()) lines.add(rule.headerText)
        for ((index, row) in rows.withIndex()) {
            var line = tpl
            line = line.replace("{{", "\u0000").replace("}}", "\u0001")
            for (fid in rule.fields) {
                line = line.replace("{$fid}", cell(row.value(fid), rule))
            }
            line = line.replace("{index}", (index + 1).toString())
            line = line.replace("{group}", meta.groupName.orEmpty())
            line = line.replace("{time}", formatTime(meta.timestamp))
            line = line.replace("{count}", meta.count.toString())
            line = line.replace("\u0000", "{").replace("\u0001", "}")
            lines.add(line)
        }
        if (rule.footer && rule.footerText.isNotBlank()) lines.add(rule.footerText)
        return lines.joinToString(rule.rowSeparator)
    }

    // ------------------------------------------------------------------ 公共

    private fun metaLines(rule: ZxCopyRuleStore.Rule, meta: Meta): List<String> {
        val lines = ArrayList<String>(3)
        if (rule.includeGroup && !meta.groupName.isNullOrBlank()) {
            lines.add("分组：" + meta.groupName)
        }
        if (rule.includeTime) lines.add("时间：" + formatTime(meta.timestamp))
        if (rule.includeCount) lines.add("数量：" + meta.count + " 只")
        return lines
    }

    private fun cell(value: String, rule: ZxCopyRuleStore.Rule): String =
        if (value.isNotEmpty()) value
        else if (rule.empty == ZxCopyRuleStore.EMPTY_DASH) "-"
        else ""

    // ------------------------------------------------------------------ 预览样例

    /**
     * 设置页预览用的样例数据（3 只）。**不参与真机导出**，只让用户看清列与格式。
     *
     * 值与字段目录里的类型保持一致：百分比带 `%`、市值带「亿」、涨跌带正负号。
     */
    fun sampleRows(): List<ZxCopyRow> = listOf(
        sample(
            "金山办公", "688111", "249.59", "+23.60", "+5.37%", "+0.12%", "1158.13亿",
            "软件服务", 5.37, 249.59, 115813000000.0,
        ),
        sample(
            "爱尔眼科", "300015", "8.15", "+0.18", "+2.26%", "-0.05%", "647.06亿",
            "医疗服务", 2.26, 8.15, 64706000000.0,
        ),
        sample(
            "极米科技", "688696", "96.63", "+6.15", "+6.80%", "+0.31%", "67.66亿",
            "消费电子", 6.80, 96.63, 6766000000.0,
        ),
    )

    private fun sample(
        name: String,
        code: String,
        price: String,
        change: String,
        changePct: String,
        speed: String,
        floatCap: String,
        industry: String,
        zdf: Double,
        priceValue: Double,
        floatCapValue: Double,
    ): ZxCopyRow {
        val values = mapOf(
            "name" to name,
            "code" to code,
            "market" to "沪",
            "price" to price,
            "change" to change,
            "changePct" to changePct,
            "speed" to speed,
            "amplitude" to "6.20%",
            "turnover" to "3.45%",
            "volumeRatio" to "1.23",
            "floatCap" to floatCap,
            "totalCap" to "1200.00亿",
            "amount" to "12.34亿",
            "volume" to "123456",
            "pe" to "25.63",
            "pb" to "3.21",
            "dividend" to "1.20%",
            "industry" to industry,
            "open" to "230.00",
            "prevClose" to "226.00",
            "high" to "251.00",
            "low" to "229.00",
            "avgPrice" to "245.00",
            "zdf3" to "+8.20%",
            "zdf5" to "+10.10%",
            "zdf20" to "+15.30%",
            "zdf60" to "+20.50%",
            "zdfYear" to "+30.00%",
            "label" to "两融",
            "addTime" to "2026-10-23 09:50:04",
            "priceDate" to "2026-10-23 15:00:00",
            "mainFund" to "1.23亿",
            "mainFundPct" to "+5.60%",
            "nowVol" to "1234",
            "weiBi" to "+12.30%",
        )
        val sort = mapOf(
            "changePct" to zdf,
            "price" to priceValue,
            "floatCap" to floatCapValue,
        )
        return ZxCopyRow(values, sort)
    }
}
