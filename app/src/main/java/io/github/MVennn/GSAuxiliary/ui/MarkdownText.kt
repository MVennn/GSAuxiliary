package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一个**只覆盖本项目需要**的 Markdown 子集渲染器。
 *
 * 起因：免责声明正文是一整块长文本，全篇同字号同字重，读起来是一堵墙。
 * 改成 Markdown 后，标题层级 / 加粗 / 斜体把结构显性化。
 *
 * ## 支持的语法
 *
 * | 写法 | 效果 |
 * |---|---|
 * | `# 一级标题` | 20sp / Bold（文档标题） |
 * | `## 二级标题` | 16sp / SemiBold / **主色**，左侧带主色竖条 |
 * | `### 三级标题` | 15sp / Medium |
 * | `---` | 分隔线 |
 * | `- 列表项` | 无序列表（主色圆点） |
 * | `> 引用` | 斜体 + 次要色（用于结尾那句"继续使用即视为接受"） |
 * | 空行 | 段落分隔 |
 * | 行内 `**粗体**` / `*斜体*` | 行内强调 |
 *
 * ## 刻意不做的事
 *
 * 不引第三方 Markdown 库（体积 + 又会多一个依赖）。**不解析嵌套列表、表格、图片、链接** ——
 * 本项目的文档正文用不到；将来真要用，在这里加分支，别去引库。
 */
@Composable
internal fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    // 解析只在文案变化时做一次（长文本，别每帧重算）
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    val colors = MiuixTheme.colorScheme

    Column(modifier) {
        var first = true
        for (block in blocks) {
            // 第一个块不额外留上边距（卡片自己的 padding 已经给了）
            if (!first) {
                Spacer(Modifier.height(blockSpacingTop(block)))
            }
            first = false

            when (block) {
                is MdBlock.Heading -> HeadingBlock(block)

                is MdBlock.Paragraph -> Text(
                    text = parseInline(block.text),
                    color = colors.onSurfaceContainer,
                    fontSize = BodySize,
                    lineHeight = BodyLineHeight,
                    modifier = Modifier.fillMaxWidth(),
                )

                is MdBlock.Bullet -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    // 圆点用主色，让列表在长段落里能一眼扫到
                    Text(
                        text = "•",
                        color = colors.primary,
                        fontSize = BodySize,
                        lineHeight = BodyLineHeight,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = parseInline(block.text),
                        color = colors.onSurfaceContainer,
                        fontSize = BodySize,
                        lineHeight = BodyLineHeight,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Quote -> Text(
                    text = parseInline(block.text),
                    color = colors.onSurfaceSecondary,
                    fontSize = BodySize,
                    lineHeight = BodyLineHeight,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceContainerHighest.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )

                MdBlock.Divider -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.onSurfaceVariantSummary.copy(alpha = 0.5f)),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 排版常量

/** 正文 14sp —— 长文本比弹窗里的 15sp 再收一档，一行能多放两个字 */
private val BodySize = 14.sp

/** 行高 22sp：长中文段落必须给足行距，否则整块看起来是实心的 */
private val BodyLineHeight = 22.sp

private fun blockSpacingTop(block: MdBlock): Dp = when (block) {
    is MdBlock.Heading -> if (block.level == 1) 4.dp else 18.dp
    MdBlock.Divider -> 14.dp
    else -> 10.dp
}

/** 标题：层级靠「字号 + 字重 + 颜色」三重区分，不靠缩进 */
@Composable
private fun HeadingBlock(block: MdBlock.Heading) {
    val colors = MiuixTheme.colorScheme
    val size = when (block.level) {
        1 -> 20.sp
        2 -> 16.sp
        else -> 15.sp
    }
    val weight = when (block.level) {
        1 -> FontWeight.Bold
        2 -> FontWeight.SemiBold
        else -> FontWeight.Medium
    }
    val color = when (block.level) {
        1 -> colors.onSurfaceContainer
        // 二级标题用主色：长文里最能"分段"的一招，也最容易扫读
        2 -> colors.primary
        else -> colors.onSurfaceContainer
    }
    val text = parseInline(block.text)

    if (block.level == 2) {
        // 主色竖条 + 标题，形成明显的小节开头
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .padding(top = 3.dp, bottom = 3.dp)
                    .width(3.dp)
                    .height(size.value.dp * 1.1f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.primary),
            )
            Spacer(Modifier.width(8.dp))
            Text(text, color = color, fontSize = size, fontWeight = weight)
        }
    } else {
        Text(text, color = color, fontSize = size, fontWeight = weight)
    }
}

// ---------------------------------------------------------------- 解析

/** Markdown 块级元素 */
private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Divider : MdBlock
}

/** 把整篇文案切成块：空行分段，行首标记决定块类型 */
private fun parseMarkdown(src: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
    }

    for (raw in src.split('\n')) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flushParagraph()

            line == "---" || line == "***" -> {
                flushParagraph()
                blocks += MdBlock.Divider
            }

            line.startsWith("#") -> {
                flushParagraph()
                // 数一下有几个 #，后面必须跟空格才算标题
                val level = line.takeWhile { it == '#' }.length
                val text = line.drop(level).trim()
                if (text.isNotEmpty() && level in 1..3) {
                    blocks += MdBlock.Heading(level, text)
                } else {
                    blocks += MdBlock.Paragraph(line)
                }
            }

            line.startsWith("- ") || line.startsWith("* ") -> {
                flushParagraph()
                blocks += MdBlock.Bullet(line.drop(2).trim())
            }

            line.startsWith("> ") -> {
                flushParagraph()
                blocks += MdBlock.Quote(line.drop(2).trim())
            }

            // 段落内的软换行：原样接上（中文文案一般不会出现，英文长句可能）
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }
    flushParagraph()
    return blocks
}

/**
 * 行内标记 → `AnnotatedString`。
 *
 * 只认 `**粗体**`、`*斜体*`、`` `代码` `` 三种。遇到不成对的标记就**原样输出**，
 * 不吞字符 —— 法律文本里少一个字都是事故。
 */
private fun parseInline(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            src.startsWith("**", i) -> {
                val end = src.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(src.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(c); i++
                }
            }

            c == '*' -> {
                val end = src.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }

            c == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(c); i++
                }
            }

            else -> {
                append(c); i++
            }
        }
    }
}
