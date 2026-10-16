package io.github.MVennn.GSAuxiliary.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.core.GLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「查看日志」页 —— 按 Miuix 规范排版。
 *
 * 结构（自上而下，两张卡）：
 *
 * | 区块 | 说明 |
 * |---|---|
 * | TabRow | 级别筛选（全部 / 错误 / 警告 / 信息 / 调试），Miuix 官方分段控件 |
 * | 日志卡 | 占满剩余高度：**表头（当前行数）+ 分隔线**固定，下面是列表 / 空态 |
 * | 操作卡 | 刷新 / 复制 / 保存 / 清空 四等分，一条分隔线之后接页脚说明 |
 *
 * 遵循项目里其它二级页的三条规则：
 *  - 页面底色 `surface`、卡片 `surfaceContainer`（两者不同色才有分组感，见 `docs/UI.md` §1.0）；
 *  - 内容不自己加 `navigationBarsPadding()`，只在整列底部让开 [LocalNavBarInset]；
 *  - 文字色一律取主题 token（`onSurface` / `onSurfaceVariantSummary`），不写死深浅色。
 *
 * 只展示 [GLog] 的内存环形缓冲 —— 也就是**模块 App 进程**自己的日志。
 * 目标进程的日志由 LSPosed 写到 `/data/adb/lspd/log/`，那个目录普通应用读不到，
 * 所以这里给的是「本进程视角」，并在页脚注明文件位置。
 */
@Composable
internal fun LogViewerScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf(GLog.snapshot()) }
    var filterIndex by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val copiedMsg = stringResource(R.string.log_copied)
    val countFmt = stringResource(R.string.log_count)
    val savedFmt = stringResource(R.string.log_saved)
    val saveFailedFmt = stringResource(R.string.log_save_failed)

    val filters = logFilters()
    val labels = filters.map { stringResource(it.labelRes) }

    val visible = remember(lines, filterIndex) {
        val f = filters.getOrElse(filterIndex) { filters.first() }
        if (f.levels == null) lines else lines.filter { line -> levelOf(line) in f.levels }
    }

    // --- 跟着缓冲更新 ---
    //
    // 以前这一页是「进来那一刻的一张快照」，别的界面还在往 [GLog] 里写，页面却纹丝不动，
    // 看着像卡住了 —— 只能手动按刷新。这里在页面可见期间轮询（见 [LogPollMillis]）；
    // 内容没变就不写状态（`List` 相等判断），免得每秒无谓重组一遍列表。
    LaunchedEffect(Unit) {
        var last = lines
        while (true) {
            delay(LogPollMillis)
            val snap = GLog.snapshot()
            if (snap != last) {
                last = snap
                lines = snap
            }
        }
    }

    // --- 滚动策略 ---
    //
    // 旧实现只挂了一个 `LaunchedEffect(visible.size)` 无条件 `animateScrollToItem(last)`：
    // 只要行数一变就把人拽回底部，想往上翻几条历史会被一路拖走。
    // 改成「已经贴底才跟随」（logcat 类工具的通行做法）：用户往上翻历史时不打扰，
    // 本来就停在底部才自动跟到最新一条。
    LaunchedEffect(lines) {
        val last = visible.lastIndex
        if (last >= 0 && !listState.canScrollForward) {
            listState.animateScrollToItem(last)
        }
    }
    // 切筛选是换了一批数据，原来滚到哪儿都不作数了，统一落到最新一条。
    //
    // 第一次 `scrollToItem` 是按「估算行高」粗定位的（那会儿这些行还没量过，尤其是
    // 带堆栈的行会比估值高不少），等一帧让它把真实高度算出来，再校正一次 ——
    // 和标准转盘机制那里是同一个套路（先铺画面，下一帧再动状态）。
    LaunchedEffect(filterIndex) {
        val last = visible.lastIndex
        if (last >= 0) {
            listState.scrollToItem(last)
            withFrameNanos { }
            listState.scrollToItem(last)
        }
    }

    fun copyAll() {
        val text = visible.joinToString("\n")
        if (text.isEmpty()) return
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("gsauxiliary-log", text))
        }
        Toast.makeText(ctx, copiedMsg, Toast.LENGTH_SHORT).show()
    }

    // --- 保存：走 SAF，用户自己挑位置，不需要任何存储权限 ---
    // 文件写入是 IO，放到 Dispatchers.IO；Toast 回主线程再弹。
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = visible.joinToString("\n")
        if (text.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法打开所选文件")
                    String.format(savedFmt, uri.lastPathSegment ?: uri.toString())
                }.getOrElse { String.format(saveFailedFmt, it.message ?: it.javaClass.simpleName) }
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 水平留白给左右，**底部让开手势导航条高度**。
            //
            // 二级页的内容是铺到屏幕最底边的（否则导航条区域被填成一条纯色，不沉浸），
            // 但本页底部还有操作卡 —— 它不能压在导航条下面。
            // 所以整列往上抬一个导航条的高度：背景仍铺满（沉浸），内容全在导航条之上。
            .padding(
                start = CardHMargin,
                end = CardHMargin,
                bottom = LocalNavBarInset.current,
            ),
    ) {
        Spacer(Modifier.height(8.dp))

        // --- 级别筛选 ---
        TabRow(
            tabs = labels,
            selectedTabIndex = filterIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0)),
            onTabSelected = { filterIndex = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        // --- 日志正文 ---
        //
        // 卡内是「表头 + 列表」两段：表头固定不滚，行数一眼可见；
        // 原先行数是一行孤零零吊在卡片外面的小字，跟卡片内容对不齐。
        Card(modifier = Modifier.weight(1f)) {
            Column {
                LogListHeader(count = String.format(countFmt, visible.size))
                HorizontalDivider()
                if (visible.isEmpty()) {
                    LogEmptyState(modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        // 每行的左右留白由 [LogLine] 自己给，这里只补上下 —— 和表头同一把尺子
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        items(visible) { line ->
                            LogLine(line)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- 操作 + 说明，合成一张卡 ---
        //
        // 以前是「按钮一张卡、页脚说明又一张卡」，碎成两块；现在用一条分隔线在同一张卡里分出层次。
        // 没有内容可操作时对应的按钮直接置灰 —— 空列表上点「复制」只会弹一个没有意义的 Toast。
        Card {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = stringResource(R.string.log_action_refresh),
                    onClick = { lines = GLog.snapshot() },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.log_action_copy),
                    onClick = { copyAll() },
                    enabled = visible.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.log_action_save),
                    onClick = { saveLauncher.launch(defaultLogFileName()) },
                    enabled = visible.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.log_action_clear),
                    onClick = {
                        GLog.clearBuffer()
                        lines = GLog.snapshot()
                    },
                    // 只看某一个级别时，清空清的是整个缓冲 —— 缓冲空了才置灰
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider()
            CardNote(stringResource(R.string.log_hint))
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 跟随 [GLog] 缓冲的轮询间隔。
 *
 * 1 秒够快也不至于让 UI 忙起来 —— 列表相等时不写状态、不触发重组，
 * 真正的开销只有一次 `snapshot()` 的数组拷贝。
 */
private const val LogPollMillis = 1000L

/** 日志卡的表头：当前筛选下有多少行。固定不滚，翻列表时也能看见 */
@Composable
private fun LogListHeader(count: String) {
    Text(
        text = count,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/** 日志为空时的占位：图标 + 文案居中。以前是注脚文字贴在卡顶部，下面空一大片 */
@Composable
private fun LogEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.log_empty),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
            )
        }
    }
}

/**
 * 一条日志 —— 按信息强弱分成三层：
 *  - 级别胶囊（E/W/I/D，彩色圆角小块）
 *  - 时间戳：`MM-dd HH:mm:ss.SSS`，比正文更暗更小
 *  - 正文 + 异常堆栈（堆栈单独成行、再缩一层）
 *
 * 时间戳与正文走**同一个** [Text]（用 `SpanStyle` 上色），不是两个挨着的 Text：
 * 一个 Text 就不存在「两个控件基线对不齐」的问题，换行也自然接在正文首行下面。
 */
@Composable
private fun LogLine(line: String) {
    val level = levelOf(line)
    val (tag, tagColor) = levelTag(level)
    val timeColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val bodyColor = MiuixTheme.colorScheme.onSurface
    val parsed = parseLine(line)

    // 富文本只跟内容和两个颜色有关，主题切换时（颜色变了）才重建
    val body = remember(line, timeColor, bodyColor) {
        buildAnnotatedString {
            if (parsed.time.isNotEmpty()) {
                withStyle(
                    SpanStyle(color = timeColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                ) {
                    append(parsed.time)
                    append(' ')
                }
            }
            withStyle(
                SpanStyle(color = bodyColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            ) {
                append(parsed.message)
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LogLevelChip(tag = tag, color = tagColor)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = body,
                color = bodyColor,
                // 等宽 + 11sp 挤在一起会连成一片，拉开到 16sp 的行距才读得下去
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            parsed.traceLines.forEach { traceLine ->
                Text(
                    text = traceLine,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }
        }
    }
}

/**
 * 级别胶囊：固定 20×14dp 的彩色小块，颜色本身携带信息。
 *
 * 尺寸写死是故意的 —— 以前它跟着文字撑高，遇到「原样输出」的行就会比别人的高一点，
 * 一列胶囊参差不齐。`.padding(top)` 在 `.size()` 之前，让胶囊顶边和正文首行齐平。
 */
@Composable
private fun LogLevelChip(tag: String, color: Color) {
    Box(
        modifier = Modifier
            .padding(top = 1.dp)
            .size(width = 20.dp, height = 14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tag,
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 单行日志的解析结果：级别之后的「时间戳 / 正文 / 堆栈行」 */
private data class ParsedLine(val time: String, val message: String, val traceLines: List<String>)

/**
 * 时间戳 + 正文的分界正则，对应 [GLog.record] 写进去的 `MM-dd HH:mm:ss.SSS msg`。
 *
 * 按正则而不是按固定偏移切：格式对不上时整体落到 [ParsedLine.message]，
 * 时间戳留空 —— 解析失败不影响展示（旧实现写死 19 字符，实际只有 18 个，
 * 多啃一个空格不说，遇到不带时间戳的行还会把前面的内容吃掉）。
 */
private val LogTimePattern = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([\s\S]+)""")

private fun parseLine(line: String): ParsedLine {
    val body = lineBody(line)
    val match = LogTimePattern.matchEntire(body) ?: return ParsedLine("", body, emptyList())
    val time = match.groupValues[1]
    val rest = match.groupValues[2]
    val breakAt = rest.indexOf('\n')
    if (breakAt < 0) return ParsedLine(time, rest, emptyList())
    return ParsedLine(
        time = time,
        message = rest.substring(0, breakAt).trimEnd(),
        traceLines = rest.substring(breakAt + 1).lines().filter { it.isNotBlank() },
    )
}

/** 日志级别筛选定义 */
private data class LogFilter(val labelRes: Int, val levels: Set<Char>?)

@Composable
private fun logFilters(): List<LogFilter> = listOf(
    LogFilter(R.string.log_filter_all, null),
    LogFilter(R.string.log_filter_error, setOf('E', 'A')),
    LogFilter(R.string.log_filter_warn, setOf('W')),
    LogFilter(R.string.log_filter_info, setOf('I')),
    LogFilter(R.string.log_filter_debug, setOf('D', 'V')),
)

/** 从 `[I] MM-dd HH:mm:ss.SSS msg` 里取出级别字符 */
private fun levelOf(line: String): Char =
    if (line.length >= 3 && line[0] == '[') line[1] else '?'

/** 去掉前缀的 `[X] `，只留正文 */
private fun lineBody(line: String): String {
    val close = line.indexOf(']')
    return if (close in 0..2) line.substring(close + 1).trimStart() else line
}

/** 级别 → 标签与颜色 */
private fun levelTag(level: Char): Pair<String, Color> = when (level) {
    'E', 'A' -> "E" to Color(0xFFE64545)
    'W' -> "W" to Color(0xFFE09B2D)
    'I' -> "I" to Color(0xFF3482FF)
    'D', 'V' -> "D" to Color(0xFF1AAE6A)
    else -> "?" to Color(0xFF888888)
}

/**
 * 保存日志时的默认文件名：`gsauxiliary-log-20261016-224812.txt`。
 *
 * 前缀用应用名 `gsauxiliary-`（2.18.0 起由项目代号 `gszx-` 更名），和备份文件
 * `gsauxiliary-backup-…json` 保持一致；用户可以在 SAF 弹窗里随便改名改位置。
 */
private fun defaultLogFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "gsauxiliary-log-$stamp.txt"
}
