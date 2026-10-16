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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.core.GLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
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
 * 结构（三段，卡片数量尽量少）：
 *   1. TabRow —— 日志级别筛选（全部 / 错误 / 警告 / 信息 / 调试）
 *   2. 日志卡片 —— 占满剩余高度；空态用图标 + 文案居中展示，
 *      非空时是等宽日志列表（级别色块 + 暗色时间戳 + 正文 + 异常堆栈缩进）
 *   3. 底部卡片 —— 操作条（刷新 / 复制 / 保存 / 清空，四等分）+
 *      细分隔线 + 页脚说明，合并在同一张卡里，避免「按钮一张卡、说明又一张卡」的碎块感
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

    // 新日志进来时滚到底部
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(visible.lastIndex) }
        }
    }

    fun copyAll() {
        val text = visible.joinToString("\n")
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
            // 但本页底部还有操作卡片 —— 它不能压在导航条下面。
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

        // --- 行数（贴在日志卡片上方，随筛选实时变化） ---
        Text(
            text = String.format(countFmt, visible.size),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )

        // --- 日志正文 ---
        Card(modifier = Modifier.weight(1f)) {
            if (visible.isEmpty()) {
                LogEmptyState()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    // 底部空白由外层 Column 让开导航条，这里不用再额外加
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    items(visible) { line ->
                        LogLine(line)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- 底部卡片：操作条 + 分隔线 + 页脚说明，合并在一张卡里 ---
        Card {
            Column {
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
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.log_action_save),
                        onClick = { saveLauncher.launch(defaultLogFileName()) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.log_action_clear),
                        onClick = {
                            GLog.clearBuffer()
                            lines = GLog.snapshot()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                // 细分隔线：把「操作」和「说明」在视觉上分开，但仍是同一张卡
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(0.5.dp)
                        .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)),
                )

                CardNote(stringResource(R.string.log_hint))
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** 日志为空时的占位：图标 + 文案，垂直水平居中，占满整张卡片 */
@Composable
private fun LogEmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = AppIcons.Info,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(32.dp),
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
 * 单行日志，按信息层次拆成三段：
 *   - 级别色块（E/W/I/D，彩色圆角小标签）
 *   - 时间戳（`MM-dd HH:mm:ss.SSS`，暗色小号等宽，弱化）
 *   - 正文 + 异常堆栈（堆栈行单独一行、同样弱化，不再和正文挤在一起）
 *
 * `weight(1f)` 在右侧的内容列上，级别色块固定宽度不参与换行。
 */
@Composable
private fun LogLine(line: String) {
    val level = levelOf(line)
    val (tag, tagColor) = levelTag(level)
    val parsed = parseLine(line)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(20.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(tagColor.copy(alpha = 0.18f))
                .padding(vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = tag,
                color = tagColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row {
                Text(
                    text = parsed.time,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = parsed.message,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            parsed.traceLines.forEach { traceLine ->
                Text(
                    text = traceLine,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 1.dp),
                )
            }
        }
    }
}

/** 单行日志解析结果：级别之后的「时间戳 / 正文 / 堆栈行」 */
private data class ParsedLine(val time: String, val message: String, val traceLines: List<String>)

/**
 * 解析 `[I] MM-dd HH:mm:ss.SSS msg\n<堆栈>` 的固定格式。
 *
 * 时间戳宽度固定为 18 字符（`MM-dd HH:mm:ss.SSS`），直接按偏移切；
 * 格式不符时原样放进 [message]，[time] 留空 —— 解析失败不影响展示。
 */
private fun parseLine(line: String): ParsedLine {
    val body = lineBody(line)
    // 时间戳固定长度："MM-dd HH:mm:ss.SSS".length == 19
    if (body.length <= 19) return ParsedLine("", body, emptyList())
    val time = body.take(19)
    val rest = body.substring(19).trimStart()
    val parts = rest.split('\n', limit = 2)
    val message = parts[0]
    val traceLines = if (parts.size == 2) parts[1].lines() else emptyList()
    return ParsedLine(time, message, traceLines)
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
