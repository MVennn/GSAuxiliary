package io.github.MVennn.GSAuxiliary.core.zxcopy

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import java.io.ByteArrayOutputStream

/**
 * 把规则渲染成 PDF（默认 A4 竖版表格；可选横版 / 单页长图）。
 *
 * 与 [ZxCopyImageRenderer] 共用同一套列宽 / 正负染色规则，区别是：
 *  - 单位是 **pt**（`PdfDocument` 的页面坐标），文字是矢量、可打印；
 *  - 默认分页 A4，页脚有 `1/3` 页码；
 *  - 「单页长图」模式把整份内容放一页（高度上限 14400pt，超了自动退回分页）。
 */
class ZxCopyPdfRenderer(
    private val rows: List<ZxCopyRow>,
    private val rule: ZxCopyRuleStore.Rule,
    private val meta: ZxCopyFormat.Meta,
) {

    private val style = rule.pdf
    private val fields: List<String> =
        rule.fields.ifEmpty { listOf("name", "code") }.filter { ZxCopyFieldCatalog.byId(it) != null }
    private val specs = fields.map { ZxCopyFieldCatalog.byId(it) }
    private val labels = fields.map { ZxCopyFormat.label(rule, it) }

    private val titlePx = style.fontSize * 1.55f
    private val headerPx = style.fontSize * 0.95f
    private val cellPx = style.fontSize.toFloat()
    private val footerPx = style.fontSize * 0.8f
    private val rowHeight = style.fontSize * 1.9f
    private val margin = style.margin.toFloat()

    private val title = meta.title ?: "自选分组"
    private val subtitle: String = buildList {
        if (!meta.groupName.isNullOrBlank()) add(meta.groupName)
        add("${rows.size} 只")
        add(ZxCopyFormat.formatTime(meta.timestamp))
    }.joinToString(" · ")

    private val headerBlock: Float = if (style.showHeader) {
        titlePx * 1.6f + headerPx * 1.6f
    } else {
        0f
    }
    private val tableHeader = headerPx * 2.0f
    private val footerBlock: Float =
        if (style.showFooter || style.pageNumbers) footerPx * 2.2f else 0f

    private val portrait = !style.landscape
    private val pageW = if (portrait) 595f else 842f
    private val pageH = if (portrait) 842f else 595f

    private val contentW = pageW - margin * 2
    private val colWidths = FloatArray(fields.size)
    private val colLefts = FloatArray(fields.size)

    init {
        val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = cellPx
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val maxCell = FloatArray(fields.size)
        for (i in fields.indices) maxCell[i] = measure.measureText(labels[i])
        for (row in rows) {
            for (i in fields.indices) {
                val v = row.value(fields[i])
                if (v.isNotEmpty()) {
                    val w = measure.measureText(v)
                    if (w > maxCell[i]) maxCell[i] = w
                }
            }
        }
        val gap = cellPx * 0.6f
        val desired = FloatArray(fields.size) { i -> maxCell[i] + gap * 2 }
        val weights = FloatArray(fields.size) { i ->
            (specs[i]?.widthWeight ?: 1f).coerceAtLeast(0.4f)
        }
        val total = desired.sum().coerceAtLeast(1f)
        if (total <= contentW) {
            val extra = contentW - total
            val totalWeight = weights.sum().coerceAtLeast(0.01f)
            for (i in desired.indices) colWidths[i] = desired[i] + extra * weights[i] / totalWeight
        } else {
            val scale = contentW / total
            var sum = 0f
            for (i in desired.indices) {
                colWidths[i] = (desired[i] * scale).coerceAtLeast(cellPx * 2.2f)
                sum += colWidths[i]
            }
            if (sum > contentW) {
                val s2 = contentW / sum
                for (i in colWidths.indices) colWidths[i] *= s2
            }
        }
        var x = margin
        for (i in colWidths.indices) {
            colLefts[i] = x
            x += colWidths[i]
        }
    }

    private fun rowsPerPage(pageHeight: Float): Int {
        val available = pageHeight - margin * 2 - headerBlock - tableHeader - footerBlock
        return (available / rowHeight).toInt().coerceAtLeast(1)
    }

    fun render(): ByteArray {
        val doc = PdfDocument()
        try {
            val longHeight = margin * 2 + headerBlock + tableHeader + rows.size * rowHeight + footerBlock
            val useLong = style.longPage && longHeight <= MAX_PAGE_HEIGHT
            if (useLong) {
                drawPage(doc, 1, 1, 0, rows.size, pageW, longHeight, longPage = true)
            } else {
                val perPage = rowsPerPage(pageH)
                val pages = if (rows.isEmpty()) 1 else ((rows.size + perPage - 1) / perPage)
                for (p in 0 until pages) {
                    val from = p * perPage
                    val to = (from + perPage).coerceAtMost(rows.size)
                    drawPage(doc, p + 1, pages, from, to, pageW, pageH, longPage = false)
                }
            }
            return ByteArrayOutputStream().use { out ->
                doc.writeTo(out)
                out.toByteArray()
            }
        } finally {
            doc.close()
        }
    }

    private fun drawPage(
        doc: PdfDocument,
        pageNo: Int,
        pageCount: Int,
        from: Int,
        to: Int,
        w: Float,
        h: Float,
        longPage: Boolean,
    ) {
        val page = doc.startPage(PdfDocument.PageInfo.Builder(w.toInt(), h.toInt(), pageNo).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textSize = titlePx
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUB
            textSize = headerPx
        }
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUB
            textSize = headerPx
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            textSize = cellPx
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SUB
            textSize = footerPx
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DIVIDER
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
        }

        var y = margin
        if (style.showHeader) {
            c.drawText(title, margin, y + titlePx, titlePaint)
            c.drawText(subtitle, margin, y + titlePx + headerPx * 1.5f, subPaint)
            y += headerBlock
        }

        val headerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HEADER_BG }
        c.drawRect(margin, y, w - margin, y + tableHeader, headerBg)
        val headerBaseline = y + tableHeader * 0.65f
        for (i in fields.indices) {
            drawCell(c, headerPaint, labels[i], i, headerBaseline, specs[i]?.alignRight == true)
        }
        c.drawLine(margin, y + tableHeader, w - margin, y + tableHeader, linePaint)
        y += tableHeader

        val tableTop = y
        val count = (to - from).coerceAtLeast(0)
        for (r in 0 until count) {
            val row = rows[from + r]
            val top = tableTop + r * rowHeight
            if (style.zebra && r % 2 == 1) {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ZEBRA }
                c.drawRect(margin, top, w - margin, top + rowHeight, bg)
            }
            val baseline = top + rowHeight * 0.64f
            for (i in fields.indices) {
                val fid = fields[i]
                val raw = row.value(fid)
                val text = when {
                    raw.isNotEmpty() -> raw
                    rule.empty == ZxCopyRuleStore.EMPTY_DASH -> "-"
                    else -> ""
                }
                if (text.isEmpty()) continue
                cellPaint.color = if (fid in TREND_FIELDS) {
                    when (trendOf(row, fid, text)) {
                        1 -> POSITIVE
                        -1 -> NEGATIVE
                        else -> TEXT
                    }
                } else {
                    TEXT
                }
                drawCell(c, cellPaint, text, i, baseline, specs[i]?.alignRight == true)
            }
            c.drawLine(margin, top + rowHeight, w - margin, top + rowHeight, linePaint)
        }
        for (i in 1 until fields.size) {
            c.drawLine(colLefts[i], tableTop - tableHeader, colLefts[i], tableTop + count * rowHeight, linePaint)
        }

        if (footerBlock > 0f) {
            val baseline = h - margin - footerPx * 0.4f
            if (style.showFooter && style.footerText.isNotBlank()) {
                c.drawText(style.footerText, margin, baseline, footerPaint)
            }
            if (!longPage && style.pageNumbers && pageCount > 1) {
                footerPaint.textAlign = Paint.Align.RIGHT
                c.drawText("$pageNo/$pageCount", w - margin, baseline, footerPaint)
                footerPaint.textAlign = Paint.Align.LEFT
            }
        }
        doc.finishPage(page)
    }

    private fun drawCell(
        c: Canvas,
        paint: TextPaint,
        text: String,
        index: Int,
        baseline: Float,
        right: Boolean,
    ) {
        val left = colLefts[index]
        val w = colWidths[index]
        val gap = cellPx * 0.6f
        val max = (w - gap * 2).coerceAtLeast(1f)
        val draw = TextUtils.ellipsize(text, paint, max, TextUtils.TruncateAt.END).toString()
        if (right) {
            paint.textAlign = Paint.Align.RIGHT
            c.drawText(draw, left + w - gap, baseline, paint)
            paint.textAlign = Paint.Align.LEFT
        } else {
            paint.textAlign = Paint.Align.LEFT
            c.drawText(draw, left + gap, baseline, paint)
        }
    }

    private fun trendOf(row: ZxCopyRow, fieldId: String, text: String): Int {
        val n = row.sort[fieldId]
            ?: ZxCopyFieldCatalog.byId(fieldId)?.let { ZxCopyFieldCatalog.sortValue(it, text) }
            ?: return 0
        return when {
            n > 0 -> 1
            n < 0 -> -1
            else -> 0
        }
    }

    private companion object {
        const val MAX_PAGE_HEIGHT = 14400f
        const val TEXT = 0xFF212634.toInt()
        const val SUB = 0xFF6B7280.toInt()
        const val DIVIDER = 0xFFE5E7EB.toInt()
        const val HEADER_BG = 0xFFF2F3F5.toInt()
        const val ZEBRA = 0xFFFAFAFA.toInt()
        const val POSITIVE = 0xFFE53935.toInt()
        const val NEGATIVE = 0xFF00897B.toInt()
        val TREND_FIELDS = setOf(
            "change", "changePct", "speed",
            "zdf3", "zdf5", "zdf20", "zdf60", "zdfYear",
            "mainFund", "mainFundPct", "weiBi",
        )
    }
}
