package io.github.MVennn.GSAuxiliary.core.zxcopy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics

/**
 * 把规则渲染成 PNG 图片（简洁列表 / 表格 / 卡片三套预设）。
 *
 * ## 为什么是一个「分页器」而不是一次性返回所有图
 *
 * 206 只 × 30 行/张 = 7 张 1080×4000 的 ARGB 位图 ≈ 120MB。一次性全建出来会
 * 直接 OOM。所以调用方先建 [Renderer]，按 [pageCount] 逐页 [renderPage]，
 * **存一张、回收一张**。
 *
 * ## 三套预设
 *
 * - [ZxCopyRuleStore.IMAGE_SIMPLE]：无表格线，行间细分隔，最轻；
 * - [ZxCopyRuleStore.IMAGE_TABLE]：列头 + 斑马纹 + 竖分隔线；
 * - [ZxCopyRuleStore.IMAGE_CARD]：外圈圆角卡片 + 内边距，适合发微信。
 *
 * 颜色：亮色白底 / 深色黑底；涨幅、涨跌、涨速等列按正负染红绿（A 股习惯）。
 */
class ZxCopyImageRenderer(
    private val rows: List<ZxCopyRow>,
    private val rule: ZxCopyRuleStore.Rule,
    private val meta: ZxCopyFormat.Meta,
    metrics: DisplayMetrics,
    widthPx: Int = 1080,
) {

    private val style = rule.image
    private val palette = if (style.dark) DarkPalette else LightPalette

    private val textPx = (style.fontSize * metrics.scaledDensity).coerceAtLeast(12f)
    private val titlePx = textPx * 1.35f
    private val rowHeight = textPx * 1.9f
    private val padding = textPx * 1.2f
    private val cardPad = if (style.preset == ZxCopyRuleStore.IMAGE_CARD) textPx * 1.2f else 0f

    private val width = widthPx.coerceIn(480, 2160)
    private val contentLeft = padding + cardPad
    private val contentRight = width - padding - cardPad
    private val contentWidth = contentRight - contentLeft

    private val fields: List<String> =
        rule.fields.ifEmpty { listOf("name", "code") }.filter { ZxCopyFieldCatalog.byId(it) != null }
    private val specs = fields.map { ZxCopyFieldCatalog.byId(it) }
    private val labels = fields.map { ZxCopyFormat.label(rule, it) }
    private val colWidths = FloatArray(fields.size)
    private val colLefts = FloatArray(fields.size)

    private val title = meta.title ?: "自选分组"
    private val subtitle: String = buildList {
        if (style.showGroup && !meta.groupName.isNullOrBlank()) add(meta.groupName)
        if (style.showCount) add("${rows.size} 只")
        if (style.showTime) add(ZxCopyFormat.formatTime(meta.timestamp))
    }.joinToString(" · ")

    private val headerBlock: Float = if (style.showHeader) {
        titlePx * 1.5f + if (subtitle.isNotEmpty()) textPx * 1.5f else 0f
    } else {
        0f
    }
    private val tableHeader: Float =
        if (style.preset == ZxCopyRuleStore.IMAGE_TABLE) textPx * 1.9f else 0f
    private val footerBlock: Float = if (style.showFooter) textPx * 1.6f else 0f

    /** 一页最多几行（同时受用户设置与 4000px 高度上限约束） */
    val rowsPerPage: Int
    val pageCount: Int

    init {
        val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textPx
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val maxCell = FloatArray(fields.size)
        for (i in fields.indices) {
            maxCell[i] = measure.measureText(labels[i])
        }
        for (row in rows) {
            for (i in fields.indices) {
                val v = row.value(fields[i])
                if (v.isNotEmpty()) {
                    val w = measure.measureText(v)
                    if (w > maxCell[i]) maxCell[i] = w
                }
            }
        }
        val gap = textPx * 0.6f
        val desired = FloatArray(fields.size) { i -> maxCell[i] + gap * 2 }
        val weights = FloatArray(fields.size) { i ->
            (specs[i]?.widthWeight ?: 1f).coerceAtLeast(0.4f)
        }
        val totalDesired = desired.sum().coerceAtLeast(1f)
        if (totalDesired <= contentWidth) {
            val extra = contentWidth - totalDesired
            val totalWeight = weights.sum().coerceAtLeast(0.01f)
            for (i in desired.indices) colWidths[i] = desired[i] + extra * weights[i] / totalWeight
        } else {
            val scale = contentWidth / totalDesired
            var sum = 0f
            for (i in desired.indices) {
                colWidths[i] = (desired[i] * scale).coerceAtLeast(textPx * 2.2f)
                sum += colWidths[i]
            }
            if (sum > contentWidth) {
                val s2 = contentWidth / sum
                for (i in colWidths.indices) colWidths[i] *= s2
            }
        }
        var x = contentLeft
        for (i in colWidths.indices) {
            colLefts[i] = x
            x += colWidths[i]
        }

        val availableForRows = MAX_HEIGHT - padding * 2 - cardPad * 2 - headerBlock - tableHeader - footerBlock
        val maxRowsByHeight = (availableForRows / rowHeight).toInt().coerceAtLeast(1)
        rowsPerPage = style.rowsPerPage.coerceIn(5, 100).coerceAtMost(maxRowsByHeight)
        pageCount = if (rows.isEmpty()) 1 else ((rows.size + rowsPerPage - 1) / rowsPerPage)
    }

    fun renderPage(index: Int): Bitmap? {
        if (index < 0 || index >= pageCount) return null
        val from = index * rowsPerPage
        val to = (from + rowsPerPage).coerceAtMost(rows.size)
        val count = (to - from).coerceAtLeast(0)
        val height = (
            padding * 2 + cardPad * 2 + headerBlock + tableHeader +
                count * rowHeight + footerBlock
            ).toInt().coerceIn(200, MAX_HEIGHT.toInt())

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(palette.bg)

        val cardRadius = textPx * 0.9f
        if (style.preset == ZxCopyRuleStore.IMAGE_CARD) {
            val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.card }
            c.drawRoundRect(
                RectF(padding, padding, width - padding, height - padding),
                cardRadius, cardRadius, card,
            )
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.text
            textSize = titlePx
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.sub
            textSize = textPx * 0.9f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.sub
            textSize = textPx * 0.88f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.text
            textSize = textPx
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.sub
            textSize = textPx * 0.82f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.divider
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        var headerTop = padding + cardPad
        if (style.showHeader) {
            c.drawText(title, contentLeft, headerTop + titlePx, titlePaint)
            if (subtitle.isNotEmpty()) {
                c.drawText(
                    subtitle,
                    contentLeft,
                    headerTop + titlePx * 1.35f + textPx * 1.1f,
                    subPaint,
                )
            }
            headerTop += headerBlock
        }

        if (style.preset == ZxCopyRuleStore.IMAGE_TABLE) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.headerBg }
            c.drawRect(contentLeft, headerTop, contentRight, headerTop + tableHeader, bg)
            val baseline = headerTop + tableHeader * 0.66f
            for (i in fields.indices) {
                drawCell(
                    c, headerPaint, labels[i], i,
                    baseline, right = specs[i]?.alignRight == true,
                )
            }
            c.drawLine(contentLeft, headerTop + tableHeader, contentRight, headerTop + tableHeader, linePaint)
            headerTop += tableHeader
        }

        val tableTop = headerTop
        for (r in 0 until count) {
            val row = rows[from + r]
            val top = tableTop + r * rowHeight
            if (style.preset != ZxCopyRuleStore.IMAGE_SIMPLE && r % 2 == 1) {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.rowAlt }
                c.drawRect(contentLeft, top, contentRight, top + rowHeight, bg)
            }
            val baseline = top + rowHeight * 0.62f
            for (i in fields.indices) {
                val fid = fields[i]
                val raw = row.value(fid)
                val text = when {
                    raw.isNotEmpty() -> raw
                    rule.empty == ZxCopyRuleStore.EMPTY_DASH -> "-"
                    else -> ""
                }
                if (text.isEmpty()) continue
                cellPaint.color = if (style.colorTrend && fid in TREND_FIELDS) {
                    when (trendOf(row, fid, text)) {
                        1 -> palette.positive
                        -1 -> palette.negative
                        else -> palette.text
                    }
                } else {
                    palette.text
                }
                drawCell(
                    c, cellPaint, text, i, baseline,
                    right = specs[i]?.alignRight == true,
                )
            }
            if (style.preset == ZxCopyRuleStore.IMAGE_TABLE) {
                c.drawLine(contentLeft, top + rowHeight, contentRight, top + rowHeight, linePaint)
            } else if (style.preset == ZxCopyRuleStore.IMAGE_SIMPLE) {
                c.drawLine(
                    contentLeft, top + rowHeight, contentRight, top + rowHeight,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.divider
                        strokeWidth = 1f
                        style = Paint.Style.STROKE
                        alpha = 90
                    },
                )
            }
        }

        if (style.preset == ZxCopyRuleStore.IMAGE_TABLE) {
            for (i in 1 until fields.size) {
                val x = colLefts[i]
                c.drawLine(x, tableTop - tableHeader, x, tableTop + count * rowHeight, linePaint)
            }
        }

        if (footerBlock > 0f) {
            val baseline = height - padding - cardPad - textPx * 0.45f
            if (style.showFooter && style.footerText.isNotBlank()) {
                c.drawText(style.footerText, contentLeft, baseline, footerPaint)
            }
            if (pageCount > 1) {
                val label = "${index + 1}/$pageCount"
                footerPaint.textAlign = Paint.Align.RIGHT
                c.drawText(label, contentRight, baseline, footerPaint)
                footerPaint.textAlign = Paint.Align.LEFT
            }
        }
        return bmp
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
        val gap = textPx * 0.6f
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

    private data class Palette(
        val bg: Int,
        val card: Int,
        val text: Int,
        val sub: Int,
        val headerBg: Int,
        val rowAlt: Int,
        val divider: Int,
        val positive: Int,
        val negative: Int,
    )

    private companion object {
        const val MAX_HEIGHT = 4000f

        val TREND_FIELDS = setOf(
            "change", "changePct", "speed",
            "zdf3", "zdf5", "zdf20", "zdf60", "zdfYear",
            "mainFund", "mainFundPct", "weiBi",
        )

        val LightPalette = Palette(
            bg = Color.WHITE,
            card = Color.WHITE,
            text = 0xFF212634.toInt(),
            sub = 0xFF6B7280.toInt(),
            headerBg = 0xFFF2F3F5.toInt(),
            rowAlt = 0xFFFAFAFA.toInt(),
            divider = 0xFFE5E7EB.toInt(),
            positive = 0xFFE53935.toInt(),
            negative = 0xFF00897B.toInt(),
        )

        val DarkPalette = Palette(
            bg = 0xFF121212.toInt(),
            card = 0xFF1E1E1E.toInt(),
            text = 0xFFEDEDED.toInt(),
            sub = 0xFFA0A0A0.toInt(),
            headerBg = 0xFF2A2A2A.toInt(),
            rowAlt = 0xFF232323.toInt(),
            divider = 0xFF333333.toInt(),
            positive = 0xFFFF6B6B.toInt(),
            negative = 0xFF4DB6AC.toInt(),
        )
    }
}
