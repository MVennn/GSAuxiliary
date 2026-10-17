package io.github.MVennn.GSAuxiliary.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ConfigBackup
import io.github.MVennn.GSAuxiliary.app.LauncherIcon
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.LogExporter
import io.github.MVennn.GSAuxiliary.core.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import io.github.MVennn.GSAuxiliary.ui.GsSwitchPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab 3 · 设置 —— **只放模块范围内的设置项**。
 *
 * 分组：
 *  - 界面：桌面图标
 *  - 材质设置：液态玻璃（总开关，+ 其下的玻璃光效）
 *  - 通用：深色模式、语言、可预测返回
 *  - 数据管理：备份 / 恢复 / 重置
 *  - 日志：日志等级、导出日志
 *
 * ## 选择类设置一律走自绘弹窗（2.8.0 起）
 *
 * 深色模式 / 语言 / 日志等级这三项，历史上用 Miuix 的
 * `OverlayDropdownPreference`，表现为**一点就闪退**，反复修了三个版本都没根治。
 * 现在全部改成「一行 [ArrowPreference]（右侧显示当前值）→ 点开自绘弹窗选」，
 * 弹窗实现见 [AppDialog]，材质跟随「液态玻璃」总开关。
 *
 * ## 为什么 Miuix 弹层治不干净
 *
 * 两条**互相独立**的根因，历史上一直只修其中一条：
 *
 *  1. **同步写配置撞上弹层自己的收起动画。** Miuix 的弹层在它自己的 `onClick` 里
 *     **同步开始收起动画**；此时立刻 `commit()` 会经由
 *     `OnSharedPreferenceChangeListener` 同步回调，把 Compose 状态在动画进行中改写，
 *     弹层入参（`selectedIndex`）中途变化 → 动画与重组打架 → 崩。
 *     治法：`ModulePreferences.editDeferred`（延迟到动画结束）+ `notifyChanged()`
 *     一律 post。3.2.x 弹层换成自绘之后，第 1 条换了形态但本质还在 ——
 *     所以所有写入都排在弹窗收起动画播完之后（`DialogController.dismiss { }`），
 *     动作真正执行时弹窗已经彻底关闭，可以同步写。
 *     （editDeferred 的 360ms 曾因此拖出「弹窗收回去了、副标题顿一会才切换」，
 *     已随 Miuix 弹层一起移除。）
 *  2. **`LocaleHelper.wrap()` 返回的 Context 不是 `ContextWrapper`。**
 *     `createConfigurationContext()` 产出的是 `ContextImpl`，`baseContext` 链是断的，
 *     androidx 沿 `ContextWrapper` 链找宿主时抛 `ClassCastException`。
 *     这条在 2.5.0 已修（见 [io.github.MVennn.GSAuxiliary.app.LocaleHelper] 的类注释）。
 *
 * 现在改成自绘弹窗之后，连第 1 条的触发面都不存在了 ——
 * 弹窗只是普通 Composition，关闭就是一次状态翻转，没有「库的收起动画」这回事。
 * 但「写入排在关闭动画之后」仍然保留（`DialogController.dismiss { }`），
 * 挡住「主题/语言整树重组」与弹窗关闭动画重叠的情况。
 */
@Composable
internal fun SettingsTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val dialogs = LocalDialogController.current

    // 备份 / 恢复的结果提示（热重载的结果走 Toast，不占用这里）
    var actionResult by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // 导出日志自己的忙标记：它可能挂起几秒等 root 授权，与备份/恢复互不影响
    var exportingLog by remember { mutableStateOf(false) }

    // --- 预取带占位符的文案：回调（非 Composable）里没法再调 stringResource ---
    val backupSuccessFmt = stringResource(R.string.backup_success)
    val backupFailedFmt = stringResource(R.string.backup_failed)
    val restoreSuccessFmt = stringResource(R.string.restore_success)
    val restoreFailedFmt = stringResource(R.string.restore_failed)
    val resetDone = stringResource(R.string.reset_done)
    val backupWorking = stringResource(R.string.backup_working)
    val restoreWorking = stringResource(R.string.restore_working)

    // 导出日志的结果提示（走 Toast —— 它会拉起 root 授权、耗时不确定，页面里不留状态）
    val logExporting = stringResource(R.string.log_exporting)
    val logExportDoneFmt = stringResource(R.string.log_export_done)
    val logExportPartialFmt = stringResource(R.string.log_export_partial)
    val logExportFailedFmt = stringResource(R.string.log_export_failed)

    // 重置对话框文案
    val resetTitle = stringResource(R.string.dialog_reset_title)
    val resetMessage = stringResource(R.string.dialog_reset_message)
    val confirmText = stringResource(R.string.action_confirm)
    val cancelText = stringResource(R.string.action_cancel)

    // 注意：三个选择类弹窗**不画标题**（2.9.2 起），所以这里不需要预取标题字符串。

    // 三个选择项的**触发控件位置**（窗口坐标）。弹窗是下拉菜单形态，要贴着控件弹，
    // 所以用 `onGloballyPositioned` 把每一行的矩形记下来，点击时带进 spec。
    var themeAnchor by remember { mutableStateOf<Rect?>(null) }
    var languageAnchor by remember { mutableStateOf<Rect?>(null) }
    var logLevelAnchor by remember { mutableStateOf<Rect?>(null) }

    val desktopIcon by rememberBoolPref(ConfigKeys.DESKTOP_ICON, ConfigKeys.DEFAULT_DESKTOP_ICON)
    val liquidGlass by rememberBoolPref(ConfigKeys.LIQUID_GLASS, ConfigKeys.DEFAULT_LIQUID_GLASS)
    val glassEffect by rememberBoolPref(ConfigKeys.GLASS_EFFECT, ConfigKeys.DEFAULT_GLASS_EFFECT)
    val theme by rememberStringPref(ConfigKeys.THEME, ConfigKeys.DEFAULT_THEME)
    val language by rememberStringPref(ConfigKeys.LANGUAGE, ConfigKeys.DEFAULT_LANGUAGE)
    val predictiveBack by rememberBoolPref(ConfigKeys.PREDICTIVE_BACK, ConfigKeys.DEFAULT_PREDICTIVE_BACK)
    val logLevel by rememberIntPref(ConfigKeys.LOG_LEVEL, ConfigKeys.DEFAULT_LOG_LEVEL)

    val themeLabels = stringArrayResource(R.array.theme_labels)
    val languageLabels = stringArrayResource(R.array.language_labels)
    val logLabels = stringArrayResource(R.array.log_level_labels)

    // --- 备份：走 SAF，不需要任何存储权限。IO 全部丢到后台线程，避免卡顿 ---
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        actionResult = ""
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val json = ConfigBackup.exportJson()
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法打开所选文件")
                    String.format(backupSuccessFmt, uri.lastPathSegment ?: uri.toString())
                }.getOrElse { String.format(backupFailedFmt, it.message ?: it.javaClass.simpleName) }
            }
            actionResult = msg
            busy = false
        }
    }

    // --- 恢复 ---
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        actionResult = ""
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val text = ctx.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: throw IllegalStateException("无法读取所选文件")
                    val count = ConfigBackup.importJson(text).getOrThrow()
                    String.format(restoreSuccessFmt, count)
                }.getOrElse { String.format(restoreFailedFmt, it.message ?: it.javaClass.simpleName) }
            }
            actionResult = msg
            busy = false
        }
    }

    // 供导出文件抬头使用：当前等级的展示名（core 层不依赖资源，由界面解析好传进去）
    val logLevelLabel = logLabels.getOrElse(
        LogLevel.VALUES.indexOf(logLevel).coerceAtLeast(0),
    ) { "" }

    // --- 导出日志：同样走 SAF，不需要任何存储权限 ---
    //
    // 与备份 / 恢复的差别在于**耗时不固定**：目标进程那一段可能要走 su（首次要等用户
    // 点授权弹窗，20 秒超时），所以它不进 `busy`（那是备份/恢复共用的），自己一个标记，
    // 结果也用 Toast 而不是页面里的结果卡 —— 用户此刻很可能已经切走了。
    val logExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportingLog = true
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    val result = LogExporter.build(logLevelLabel)
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(result.text.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("无法打开所选文件")
                    val name = uri.lastPathSegment ?: uri.toString()
                    // 目标进程那段拿不到也照样出文件（原因写在文件的那一节抬头里），
                    // 但提示里要带一句 —— 否则用户会以为「日志就这么多」
                    String.format(
                        if (result.hasTarget) logExportDoneFmt else logExportPartialFmt,
                        name,
                    )
                }.getOrElse { String.format(logExportFailedFmt, it.message ?: it.javaClass.simpleName) }
            }
            exportingLog = false
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        // 玻璃顶栏时内容铺到屏幕顶边，首屏空白在这里补（见 Glass.kt 的 LocalTopInset）
        Spacer(Modifier.height(8.dp + LocalTopInset.current))

        // --- 界面 ---
        SmallTitle(stringResource(R.string.section_interface))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = desktopIcon,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.DESKTOP_ICON, v) }
                    LauncherIcon.apply(ctx, v)
                },
                title = stringResource(R.string.item_desktop_icon),
                // 打开时不需要子标题（标题已经说清了），只有隐藏时才需要一句「去哪找回来」
                summary = if (desktopIcon) {
                    null
                } else {
                    stringResource(R.string.item_desktop_icon_summary_off)
                },
            )
        }

        // --- Dock 设置 ---
        //
        // 「液态玻璃」是**顶栏 + 悬浮底栏的总开关**，形态完全由它决定，所以单独成组，
        // 不和桌面图标混在「界面」里（原来它叫「Dock 变换」，只描述底栏，语义已经过时）。
        SmallTitle(stringResource(R.string.section_dock))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = liquidGlass,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.LIQUID_GLASS, v) }
                },
                title = stringResource(R.string.item_liquid_glass),
                // 开着的时候标题本身就说清了，不再重复；关掉时说明当前是纯色
                summary = if (liquidGlass) {
                    null
                } else {
                    stringResource(R.string.liquid_glass_off)
                },
            )
            // 只有液态玻璃才谈得上「光效」，所以在它开启时展开到下方。
            // 动画与功能页的二级入口保持一致（见 FeatureTab.FeatureEntry）。
            AnimatedVisibility(
                visible = liquidGlass,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                GsSwitchPreference(
                    checked = glassEffect,
                    onCheckedChange = { v ->
                        ModulePreferences.edit { putBoolean(ConfigKeys.GLASS_EFFECT, v) }
                    },
                    title = stringResource(R.string.item_glass_effect),
                    // 副标题跟随开关走（2.9.9）：开 = 完整版光效、关 = 简约版。
                    // 之前是一条静态说明（「完整的玻璃光效和简约版本」），读不出当前处于哪一档。
                    summary = stringResource(
                        if (glassEffect) {
                            R.string.item_glass_effect_summary_on
                        } else {
                            R.string.item_glass_effect_summary_off
                        }
                    ),
                )
            }
        }

        // --- 通用 ---
        SmallTitle(stringResource(R.string.section_general))
        Card(modifier = Modifier.cardPadding()) {
            // 主题设置：右侧直接显示当前值，点开弹窗选（弹窗贴在控件右下方）。
            // 写入排在弹窗收起动画之后（dismiss { }），执行时弹窗已关闭，可同步落盘 ——
            // 主题变化会整树重组，不能在动画进行中改写状态。
            BasicComponent(
                title = stringResource(R.string.item_theme),
                modifier = Modifier.onGloballyPositioned { themeAnchor = it.boundsInWindow() },
                summary = themeLabels.getOrElse(
                    ConfigKeys.THEME_VALUES.indexOf(theme).coerceAtLeast(0),
                ) { "" },
                // 朝下箭头：这一行是**原地弹窗**，不是进入下一级（见 DownArrowIcon）
                endActions = { DownArrowIcon() },
                onClick = {
                    dialogs?.showChoice(
                        ChoiceDialogSpec(
                            items = themeLabels.toList(),
                            selectedIndex = ConfigKeys.THEME_VALUES.indexOf(theme).coerceAtLeast(0),
                            anchor = themeAnchor,
                            onSelect = { idx ->
                                ConfigKeys.THEME_VALUES.getOrNull(idx)
                                    ?.takeIf { it != theme }
                                    ?.let { v -> ModulePreferences.edit { putString(ConfigKeys.THEME, v) } }
                            },
                        ),
                    )
                },
            )
            BasicComponent(
                title = stringResource(R.string.item_language),
                modifier = Modifier.onGloballyPositioned { languageAnchor = it.boundsInWindow() },
                summary = languageLabels.getOrElse(
                    ConfigKeys.LANGUAGE_VALUES.indexOf(language).coerceAtLeast(0),
                ) { "" },
                endActions = { DownArrowIcon() },
                onClick = {
                    dialogs?.showChoice(
                        ChoiceDialogSpec(
                            items = languageLabels.toList(),
                            selectedIndex = ConfigKeys.LANGUAGE_VALUES.indexOf(language).coerceAtLeast(0),
                            anchor = languageAnchor,
                            onSelect = { idx ->
                                ConfigKeys.LANGUAGE_VALUES.getOrNull(idx)
                                    ?.takeIf { it != language }
                                    ?.let { v -> ModulePreferences.edit { putString(ConfigKeys.LANGUAGE, v) } }
                            },
                        ),
                    )
                },
            )
            GsSwitchPreference(
                checked = predictiveBack,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.PREDICTIVE_BACK, v) }
                },
                title = stringResource(R.string.item_predictive_back),
                summary = stringResource(
                    if (predictiveBack) {
                        R.string.item_predictive_back_summary_on
                    } else {
                        R.string.item_predictive_back_summary_off
                    }
                ),
            )
        }

        // --- 数据管理 ---
        SmallTitle(stringResource(R.string.section_data))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.item_backup),
                // 平时不写副标题（「备份模块配置」已说清），只在导出中显示进度
                summary = if (busy) backupWorking else null,
                enabled = !busy,
                endActions = { ArrowRightIcon(!busy) },
                onClick = { backupLauncher.launch(defaultBackupFileName()) },
            )
            BasicComponent(
                title = stringResource(R.string.item_restore),
                // 同上：平时不写副标题，只在恢复中显示进度
                summary = if (busy) restoreWorking else null,
                enabled = !busy,
                endActions = { ArrowRightIcon(!busy) },
                onClick = { restoreLauncher.launch(arrayOf("*/*")) },
            )
            BasicComponent(
                title = stringResource(R.string.item_reset),
                summary = stringResource(R.string.item_reset_summary),
                endActions = { ArrowRightIcon() },
                onClick = {
                    dialogs?.showConfirm(
                        ConfirmDialogSpec(
                            title = resetTitle,
                            message = resetMessage,
                            confirmText = confirmText,
                            cancelText = cancelText,
                            onConfirm = {
                                // 关闭「全部」功能开关，并恢复功能参数。
                                // 功能开关默认值虽已是 false（2.11.2），这里仍显式写 false，
                                // 语义是「重置 = 关掉」，不依赖默认值。
                                // onConfirm 由弹窗宿主在收起动画播完后才执行（finishDismiss），
                                // 此时同步写配置不会再与动画抢重组。
                                ModulePreferences.resetAllFeatures()
                                Toast.makeText(ctx, resetDone, Toast.LENGTH_SHORT).show()
                            },
                        ),
                    )
                },
            )
        }

        if (actionResult.isNotEmpty()) {
            Card(modifier = Modifier.cardPadding()) {
                CardNote(actionResult)
            }
        }

        // --- 日志 ---
        //
        // 这一组曾经在 3.2.0 被整体删掉（当时的形态是「日志输出等级 + 查看日志」二级页），
        // 3.2.0 后期按用户要求以新形态装回来：**不再在模块里看日志，只导出**。
        //  - 日志等级：两进程共用（App 进程立即生效，目标进程由框架推送配置变化），
        //    所以它既是「设置」也是「排障开关」；
        //  - 导出日志：把两个进程的日志拼成一份文本交给用户（见 LogExporter），
        //    取代了原来那个只能在模块 App 进程里翻的查看页。
        SmallTitle(stringResource(R.string.section_log))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.item_log_level),
                modifier = Modifier.onGloballyPositioned { logLevelAnchor = it.boundsInWindow() },
                summary = logLabels.getOrElse(
                    LogLevel.VALUES.indexOf(logLevel).coerceAtLeast(0),
                ) { "" },
                endActions = { DownArrowIcon() },
                onClick = {
                    dialogs?.showChoice(
                        ChoiceDialogSpec(
                            items = logLabels.toList(),
                            selectedIndex = LogLevel.VALUES.indexOf(logLevel).coerceAtLeast(0),
                            anchor = logLevelAnchor,
                            onSelect = { idx ->
                                val value = LogLevel.VALUES.getOrNull(idx) ?: LogLevel.INFO
                                if (value != logLevel) {
                                    ModulePreferences.edit { putInt(ConfigKeys.LOG_LEVEL, value) }
                                    // 界面这一侧立即生效，不必等配置回流；目标进程等框架推送
                                    GLog.level = value
                                    // 选「关闭」时顺带清掉落盘历史（2.17.3 的同一套语义）：
                                    // 等级只拦新日志，不给清旧的就是「关了还有一堆日志」，
                                    // 看起来跟开关没生效一样。
                                    if (value == LogLevel.OFF) GLog.clearStore()
                                }
                            },
                        ),
                    )
                },
            )
            BasicComponent(
                title = stringResource(R.string.item_export_log),
                // 范围类的副标题保留：不写这一句，没人知道导出里是两个进程的日志（且会弹 root）
                summary = if (exportingLog) logExporting else stringResource(R.string.item_export_log_summary),
                enabled = !exportingLog,
                endActions = { ArrowRightIcon(!exportingLog) },
                onClick = { logExportLauncher.launch(defaultLogFileName()) },
            )
        }

        // --- 框架操作 ---
        //
        // 「热重载目标进程」已移除（2.18.0）：它只是个手动触发入口，日常用不上，
        // 还把 libxposed service 的类型（HotReloadResult / HookedTarget）拖进了
        // 界面层。目标进程的热重载能力本身保留（模块升级时框架自动走
        // onHotReloading / onHotReloaded，见 module.prop 的 autoHotReload）。

        // 悬浮底栏的让位空白（见 LocalDockInset）
        Spacer(Modifier.height(24.dp + LocalDockInset.current))
    }
}

/** 备份文件名：英文 + 日期 + 时分秒，例如 `gsauxiliary-backup-20261016-215939.json` */
private fun defaultBackupFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "gsauxiliary-backup-$stamp.json"
}

/** 导出日志的默认文件名，与备份同一套命名：`gsauxiliary-log-20261017-015939.txt` */
private fun defaultLogFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "gsauxiliary-log-$stamp.txt"
}
