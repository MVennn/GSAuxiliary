package io.github.MVennn.GSAuxiliary.core.zxcopy

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「自选分组复制」的规则数据模型与 JSON 编解码。
 *
 * 与 `QuickConfigStore` 同一套思路：模型放 `core/`，模块 App 的设置页与目标进程
 * 共用；解析失败**绝不抛异常**，一律回落出厂规则；未知字段 / 坏规则丢弃但不连坐。
 *
 * 存储格式（v1，remote preferences 的 `zx_copy_rules` 键，JSON 字符串）：
 *
 * ```json
 * {
 *   "v": 1,
 *   "defaultId": "r1",
 *   "rules": [
 *     { "id": "r1", "name": "默认", "isDefault": true,
 *       "fields": ["name", "code"],
 *       "labels": { "name": "名称", "code": "代码" },
 *       "format": "text", "separator": " ", "rowSeparator": "\n",
 *       "header": false, "footer": false,
 *       "includeGroup": false, "includeTime": false, "includeCount": false,
 *       "sort": "screen", "empty": "dash", "action": "clipboard",
 *       "image": { ... }, "pdf": { ... }, "shareAfterExport": true }
 *   ]
 * }
 * ```
 *
 * `labels` 是**写入时按当前语言生成的字段标签**：目标进程没有模块资源上下文，
 * Markdown / CSV 表头与图片 / PDF 列头只能读这份存下来的文本。设置页每次写规则
 * 都会重新填一遍，所以切换语言后重新保存一次即可刷新。
 */
object ZxCopyRuleStore {

    // ------------------------------------------------------------ 常量

    const val FORMAT_TEXT = "text"
    const val FORMAT_MARKDOWN = "markdown"
    const val FORMAT_CSV = "csv"
    const val FORMAT_JSON = "json"
    const val FORMAT_TEMPLATE = "template"

    const val SORT_SCREEN = "screen"
    const val SORT_CODE = "code"
    const val SORT_NAME = "name"
    const val SORT_ZDF_DESC = "zdfDesc"
    const val SORT_ZDF_ASC = "zdfAsc"

    const val EMPTY_DASH = "dash"
    const val EMPTY_BLANK = "blank"

    const val ACTION_CLIPBOARD = "clipboard"
    const val ACTION_SHARE_TEXT = "shareText"
    const val ACTION_IMAGE = "image"
    const val ACTION_PDF = "pdf"

    const val IMAGE_SIMPLE = "simple"
    const val IMAGE_TABLE = "table"
    const val IMAGE_CARD = "card"

    private const val FORMAT_VERSION = 1

    private val FORMATS = setOf(FORMAT_TEXT, FORMAT_MARKDOWN, FORMAT_CSV, FORMAT_JSON, FORMAT_TEMPLATE)
    private val SORTS = setOf(SORT_SCREEN, SORT_CODE, SORT_NAME, SORT_ZDF_DESC, SORT_ZDF_ASC)
    private val EMPTIES = setOf(EMPTY_DASH, EMPTY_BLANK)
    private val ACTIONS = setOf(ACTION_CLIPBOARD, ACTION_SHARE_TEXT, ACTION_IMAGE, ACTION_PDF)
    private val IMAGE_PRESETS = setOf(IMAGE_SIMPLE, IMAGE_TABLE, IMAGE_CARD)

    // ------------------------------------------------------------ 数据模型

    data class ImageStyle(
        val preset: String = IMAGE_TABLE,
        val dark: Boolean = false,
        val showHeader: Boolean = true,
        val showGroup: Boolean = true,
        val showTime: Boolean = true,
        val showCount: Boolean = true,
        val showFooter: Boolean = true,
        val footerText: String = "",
        val fontSize: Int = 14,
        val rowsPerPage: Int = 30,
        val colorTrend: Boolean = true,
    )

    data class PdfStyle(
        val landscape: Boolean = false,
        val longPage: Boolean = false,
        val fontSize: Int = 10,
        val margin: Int = 36,
        val showHeader: Boolean = true,
        val showFooter: Boolean = true,
        val footerText: String = "",
        val pageNumbers: Boolean = true,
        val zebra: Boolean = true,
    )

    data class Rule(
        val id: String,
        val name: String,
        /** 列表页的一行预览（设置页写入时生成） */
        val preview: String,
        val isDefault: Boolean,
        /** 字段 id，有序 */
        val fields: List<String>,
        /** 字段 id → 当前语言标签（Markdown / CSV / 图片 / PDF 表头用） */
        val labels: Map<String, String>,
        val format: String,
        val separator: String,
        val rowSeparator: String,
        val template: String,
        val header: Boolean,
        val headerText: String,
        val footer: Boolean,
        val footerText: String,
        val includeGroup: Boolean,
        val includeTime: Boolean,
        val includeCount: Boolean,
        val sort: String,
        val empty: String,
        val action: String,
        val image: ImageStyle,
        val pdf: PdfStyle,
        val shareAfterExport: Boolean,
    )

    data class Data(
        val defaultId: String,
        val rules: List<Rule>,
    ) {
        fun defaultRule(): Rule? =
            rules.firstOrNull { it.id == defaultId }
                ?: rules.firstOrNull { it.isDefault }
                ?: rules.firstOrNull()
    }

    // ------------------------------------------------------------ 出厂规则

    fun defaultData(): Data {
        val rule = Rule(
            id = "r1",
            name = "默认",
            preview = "",
            isDefault = true,
            fields = listOf("name", "code"),
            labels = mapOf("name" to "名称", "code" to "代码"),
            format = FORMAT_TEXT,
            separator = " ",
            rowSeparator = "\n",
            template = "",
            header = false,
            headerText = "",
            footer = false,
            footerText = "",
            includeGroup = false,
            includeTime = false,
            includeCount = false,
            sort = SORT_SCREEN,
            empty = EMPTY_DASH,
            action = ACTION_CLIPBOARD,
            image = ImageStyle(),
            pdf = PdfStyle(),
            shareAfterExport = true,
        )
        return Data(defaultId = rule.id, rules = listOf(rule))
    }

    /** 出厂 JSON（`ConfigKeys.DEFAULT_ZX_COPY_RULES` 用；解析一次就够） */
    fun defaultJson(): String = encode(defaultData())

    // ------------------------------------------------------------ 解析

    fun decode(json: String?): Data {
        if (json.isNullOrBlank()) return defaultData()
        return decodeOrNull(json) ?: defaultData()
    }

    private fun decodeOrNull(json: String): Data? = runCatching {
        val root = JSONObject(json)
        val version = root.optInt("v", FORMAT_VERSION)
        if (version != FORMAT_VERSION) {
            // 不阻断：v1 结构已知；真要改结构时在这里加分支
        }
        val arr = root.optJSONArray("rules") ?: return@runCatching null
        val rules = ArrayList<Rule>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rule = decodeRule(o) ?: continue
            if (!seen.add(rule.id)) continue
            rules.add(rule)
        }
        if (rules.isEmpty()) return@runCatching null
        val defaultId = root.optString("defaultId")
            .takeIf { id -> rules.any { it.id == id } }
            ?: rules.first().id
        Data(defaultId, rules)
    }.getOrNull()

    private fun decodeRule(o: JSONObject): Rule? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val name = o.optString("name").takeIf { it.isNotEmpty() } ?: id

        val rawFields = o.optJSONArray("fields")
        val fields = ArrayList<String>()
        if (rawFields != null) {
            for (i in 0 until rawFields.length()) {
                val fid = rawFields.optString(i)
                if (ZxCopyFieldCatalog.byId(fid) != null && fid !in fields) fields.add(fid)
            }
        }
        if (fields.isEmpty()) {
            fields.add("name")
            fields.add("code")
        }

        val labels = HashMap<String, String>()
        val labelsJson = o.optJSONObject("labels")
        for (fid in fields) {
            val saved = labelsJson?.optString(fid)?.trim().orEmpty()
            labels[fid] = saved.takeIf { it.isNotEmpty() }
                ?: ZxCopyFieldCatalog.builtinLabel(fid)
                ?: fid
        }

        val format = o.optString("format").takeIf { it in FORMATS } ?: FORMAT_TEXT
        val sort = o.optString("sort").takeIf { it in SORTS } ?: SORT_SCREEN
        val empty = o.optString("empty").takeIf { it in EMPTIES } ?: EMPTY_DASH
        val action = o.optString("action").takeIf { it in ACTIONS } ?: ACTION_CLIPBOARD
        val separator = o.optString("separator", " ")
        val rowSeparator = o.optString("rowSeparator", "\n").takeIf { it.isNotEmpty() } ?: "\n"

        return Rule(
            id = id,
            name = name,
            preview = o.optString("preview", ""),
            isDefault = o.optBoolean("isDefault", false),
            fields = fields,
            labels = labels,
            format = format,
            separator = separator,
            rowSeparator = rowSeparator,
            template = o.optString("template", ""),
            header = o.optBoolean("header", false),
            headerText = o.optString("headerText", ""),
            footer = o.optBoolean("footer", false),
            footerText = o.optString("footerText", ""),
            includeGroup = o.optBoolean("includeGroup", false),
            includeTime = o.optBoolean("includeTime", false),
            includeCount = o.optBoolean("includeCount", false),
            sort = sort,
            empty = empty,
            action = action,
            image = decodeImage(o.optJSONObject("image")),
            pdf = decodePdf(o.optJSONObject("pdf")),
            shareAfterExport = o.optBoolean("shareAfterExport", true),
        )
    }

    private fun decodeImage(o: JSONObject?): ImageStyle {
        val d = ImageStyle()
        if (o == null) return d
        return ImageStyle(
            preset = o.optString("preset").takeIf { it in IMAGE_PRESETS } ?: d.preset,
            dark = o.optBoolean("dark", d.dark),
            showHeader = o.optBoolean("showHeader", d.showHeader),
            showGroup = o.optBoolean("showGroup", d.showGroup),
            showTime = o.optBoolean("showTime", d.showTime),
            showCount = o.optBoolean("showCount", d.showCount),
            showFooter = o.optBoolean("showFooter", d.showFooter),
            footerText = o.optString("footerText", d.footerText),
            fontSize = o.optInt("fontSize", d.fontSize).coerceIn(10, 28),
            rowsPerPage = o.optInt("rowsPerPage", d.rowsPerPage).coerceIn(5, 100),
            colorTrend = o.optBoolean("colorTrend", d.colorTrend),
        )
    }

    private fun decodePdf(o: JSONObject?): PdfStyle {
        val d = PdfStyle()
        if (o == null) return d
        return PdfStyle(
            landscape = o.optBoolean("landscape", d.landscape),
            longPage = o.optBoolean("longPage", d.longPage),
            fontSize = o.optInt("fontSize", d.fontSize).coerceIn(7, 20),
            margin = o.optInt("margin", d.margin).coerceIn(16, 96),
            showHeader = o.optBoolean("showHeader", d.showHeader),
            showFooter = o.optBoolean("showFooter", d.showFooter),
            footerText = o.optString("footerText", d.footerText),
            pageNumbers = o.optBoolean("pageNumbers", d.pageNumbers),
            zebra = o.optBoolean("zebra", d.zebra),
        )
    }

    // ------------------------------------------------------------ 序列化

    fun encode(data: Data): String {
        val root = JSONObject()
        root.put("v", FORMAT_VERSION)
        root.put("defaultId", data.defaultId)
        val arr = JSONArray()
        for (r in data.rules) arr.put(encodeRule(r))
        root.put("rules", arr)
        return root.toString()
    }

    private fun encodeRule(r: Rule): JSONObject {
        val o = JSONObject()
        o.put("id", r.id)
        o.put("name", r.name)
        o.put("preview", r.preview)
        o.put("isDefault", r.isDefault)
        o.put("fields", JSONArray(r.fields))
        val labels = JSONObject()
        for ((k, v) in r.labels) labels.put(k, v)
        o.put("labels", labels)
        o.put("format", r.format)
        o.put("separator", r.separator)
        o.put("rowSeparator", r.rowSeparator)
        o.put("template", r.template)
        o.put("header", r.header)
        o.put("headerText", r.headerText)
        o.put("footer", r.footer)
        o.put("footerText", r.footerText)
        o.put("includeGroup", r.includeGroup)
        o.put("includeTime", r.includeTime)
        o.put("includeCount", r.includeCount)
        o.put("sort", r.sort)
        o.put("empty", r.empty)
        o.put("action", r.action)
        o.put("image", encodeImage(r.image))
        o.put("pdf", encodePdf(r.pdf))
        o.put("shareAfterExport", r.shareAfterExport)
        return o
    }

    private fun encodeImage(s: ImageStyle): JSONObject = JSONObject().apply {
        put("preset", s.preset)
        put("dark", s.dark)
        put("showHeader", s.showHeader)
        put("showGroup", s.showGroup)
        put("showTime", s.showTime)
        put("showCount", s.showCount)
        put("showFooter", s.showFooter)
        put("footerText", s.footerText)
        put("fontSize", s.fontSize)
        put("rowsPerPage", s.rowsPerPage)
        put("colorTrend", s.colorTrend)
    }

    private fun encodePdf(s: PdfStyle): JSONObject = JSONObject().apply {
        put("landscape", s.landscape)
        put("longPage", s.longPage)
        put("fontSize", s.fontSize)
        put("margin", s.margin)
        put("showHeader", s.showHeader)
        put("showFooter", s.showFooter)
        put("footerText", s.footerText)
        put("pageNumbers", s.pageNumbers)
        put("zebra", s.zebra)
    }

    // ------------------------------------------------------------ 新建

    fun newId(existing: List<Rule>): String {
        var n = existing.size + 1
        val taken = existing.map { it.id }.toHashSet()
        while (true) {
            val id = "r$n"
            if (id !in taken) return id
            n++
        }
    }

    fun newName(existingNames: List<String>, prefix: String): String {
        val taken = existingNames.toHashSet()
        var n = 1
        while ("$prefix$n" in taken) n++
        return "$prefix$n"
    }

    /** 从一条现有规则复制出新规则（id / 名称 / 默认标记换掉，其余原样） */
    fun duplicate(source: Rule, id: String, name: String): Rule =
        source.copy(id = id, name = name, isDefault = false, preview = "")
}
