package io.github.SansKritO4.GSAuxiliary.ui

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
import io.github.SansKritO4.GSAuxiliary.R
import io.github.SansKritO4.GSAuxiliary.app.ConfigBackup
import io.github.SansKritO4.GSAuxiliary.app.FrameworkBridge
import io.github.SansKritO4.GSAuxiliary.app.LauncherIcon
import io.github.SansKritO4.GSAuxiliary.app.ModulePreferences
import io.github.SansKritO4.GSAuxiliary.core.ConfigKeys
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.core.LogLevel
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tab 3 · 设置 —— **只放模块范围内的设置项**。
 *
 * 分组：
 *  - 界面：桌面图标
 *  - 材质设置：液态玻璃（总开关，+ 其下的玻璃光效）
 *  - 通用：深色模式、语言、可预测返回
 *  - 数据管理：备份 / 恢复 / 重置
 *  - 框架操作：热重载目标进程
 *  - 日志：输出等级、查看日志
 *
 * ## 选择类设置一律走自绘弹窗（2.8.0 起）
 *
 * 深色模式 / 语言 / 日志输出等级这三项，历史上用 Miuix 的
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
 *     治法：配置写入一律走 `ModulePreferences.editDeferred { }`
 *     （延迟到动画结束；连续选择只落最后一次），并且 `notifyChanged()` 一律 post。
 *  2. **`LocaleHelper.wrap()` 返回的 Context 不是 `ContextWrapper`。**
 *     `createConfigurationContext()` 产出的是 `ContextImpl`，`baseContext` 链是断的，
 *     androidx 沿 `ContextWrapper` 链找宿主时抛 `ClassCastException`。
 *     这条在 2.5.0 已修（见 [io.github.SansKritO4.GSAuxiliary.app.LocaleHelper] 的类注释）。
 *
 * 现在改成自绘弹窗之后，连第 1 条的触发面都不存在了 ——
 * 弹窗只是普通 Composition，关闭就是一次状态翻转，没有「库的收起动画」这回事。
 * 但 [ModulePreferences.editDeferred] 仍然保留：它同时挡住了「主题/语言整树重组」
 * 与「弹窗关闭动画」重叠的情况。
 */
@Composable
internal fun SettingsTab(onOpenLog: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val dialogs = LocalDialogController.current

    // 备份 / 恢复的结果提示（热重载的结果走 Toast，不占用这里）
    var actionResult by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // --- 预取带占位符的文案：回调（非 Composable）里没法再调 stringResource ---
    val backupSuccessFmt = stringResource(R.string.backup_success)
    val backupFailedFmt = stringResource(R.string.backup_failed)
    val restoreSuccessFmt = stringResource(R.string.restore_success)
    val restoreFailedFmt = stringResource(R.string.restore_failed)
    val resetDone = stringResource(R.string.reset_done)
    val backupWorking = stringResource(R.string.backup_working)
    val restoreWorking = stringResource(R.string.restore_working)

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

    val connected = rememberConnected()
    val hotReloadStrings = hotReloadTexts()

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
            SwitchPreference(
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
            SwitchPreference(
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
                SwitchPreference(
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
            // 写入走 editDeferred —— 主题变化会整树重组，必须等弹窗关闭之后再落盘。
            ArrowPreference(
                title = stringResource(R.string.item_theme),
                modifier = Modifier.onGloballyPositioned { themeAnchor = it.boundsInWindow() },
                summary = themeLabels.getOrElse(
                    ConfigKeys.THEME_VALUES.indexOf(theme).coerceAtLeast(0),
                ) { "" },
                onClick = {
                    dialogs?.showChoice(
                        ChoiceDialogSpec(
                            items = themeLabels.toList(),
                            selectedIndex = ConfigKeys.THEME_VALUES.indexOf(theme).coerceAtLeast(0),
                            anchor = themeAnchor,
                            onSelect = { idx ->
                                ConfigKeys.THEME_VALUES.getOrNull(idx)
                                    ?.takeIf { it != theme }
                                    ?.let { v -> ModulePreferences.editDeferred { putString(ConfigKeys.THEME, v) } }
                            },
                        ),
                    )
                },
            )
            ArrowPreference(
                title = stringResource(R.string.item_language),
                modifier = Modifier.onGloballyPositioned { languageAnchor = it.boundsInWindow() },
                summary = languageLabels.getOrElse(
                    ConfigKeys.LANGUAGE_VALUES.indexOf(language).coerceAtLeast(0),
                ) { "" },
                onClick = {
                    dialogs?.showChoice(
                        ChoiceDialogSpec(
                            items = languageLabels.toList(),
                            selectedIndex = ConfigKeys.LANGUAGE_VALUES.indexOf(language).coerceAtLeast(0),
                            anchor = languageAnchor,
                            onSelect = { idx ->
                                ConfigKeys.LANGUAGE_VALUES.getOrNull(idx)
                                    ?.takeIf { it != language }
                                    ?.let { v -> ModulePreferences.editDeferred { putString(ConfigKeys.LANGUAGE, v) } }
                            },
                        ),
                    )
                },
            )
            SwitchPreference(
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
            ArrowPreference(
                title = stringResource(R.string.item_backup),
                summary = if (busy) backupWorking else stringResource(R.string.item_backup_summary),
                enabled = !busy,
                onClick = { backupLauncher.launch(defaultBackupFileName()) },
            )
            ArrowPreference(
                title = stringResource(R.string.item_restore),
                summary = if (busy) restoreWorking else stringResource(R.string.item_restore_summary),
                enabled = !busy,
                onClick = { restoreLauncher.launch(arrayOf("*/*")) },
            )
            ArrowPreference(
                title = stringResource(R.string.item_reset),
                summary = stringResource(R.string.item_reset_summary),
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
                                // 延迟落盘：主题 / 语言这类模块级设置不受影响，
                                // 但功能开关变化会触发功能页重组，等弹窗关闭动画跑完更稳。
                                ModulePreferences.resetAllFeaturesDeferred()
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

        // --- 框架操作（热重载从这里进来，原在「模块」页） ---
        SmallTitle(stringResource(R.string.section_framework_ops))
        Card(modifier = Modifier.cardPadding()) {
            ArrowPreference(
                title = stringResource(R.string.action_hot_reload),
                summary = if (connected.value) {
                    stringResource(R.string.action_hot_reload_summary)
                } else {
                    stringResource(R.string.action_hot_reload_need_framework)
                },
                enabled = connected.value,
                onClick = {
                    // Binder 调用与等待都在 IO 线程，Toast 回到主线程再弹。
                    // （曾经直接在后台线程调 Toast，没有 Looper 会抛异常闪退。）
                    scope.launch {
                        // 每一次点击都必有反馈：成功 / 失败 / 超时 / 没目标进程 各有文案。
                        // （旧版只在「没有正在运行的目标进程」时提示，目标进程在后台时
                        //  点完毫无动静，用户以为是没生效 —— 见 CHANGELOG 2.12.1。）
                        Toast.makeText(ctx, requestHotReload(hotReloadStrings), Toast.LENGTH_LONG).show()
                    }
                },
            )
        }

        // --- 日志 ---
        SmallTitle(stringResource(R.string.section_log))
        Card(modifier = Modifier.cardPadding()) {
            ArrowPreference(
                title = stringResource(R.string.item_log_level),
                modifier = Modifier.onGloballyPositioned { logLevelAnchor = it.boundsInWindow() },
                summary = logLabels.getOrElse(
                    LogLevel.VALUES.indexOf(logLevel).coerceAtLeast(0),
                ) { "" },
                onClick = {
                    dialogs?.showChoice(
                        ChoiceDialogSpec(
                            items = logLabels.toList(),
                            selectedIndex = LogLevel.VALUES.indexOf(logLevel).coerceAtLeast(0),
                            anchor = logLevelAnchor,
                            onSelect = { idx ->
                                val value = LogLevel.VALUES.getOrNull(idx) ?: LogLevel.INFO
                                if (value != logLevel) {
                                    ModulePreferences.editDeferred { putInt(ConfigKeys.LOG_LEVEL, value) }
                                    GLog.level = value
                                }
                            },
                        ),
                    )
                },
            )
            ArrowPreference(
                title = stringResource(R.string.item_view_log),
                summary = stringResource(R.string.item_view_log_summary),
                onClick = onOpenLog,
            )
        }

        // 悬浮底栏的让位空白（见 LocalDockInset）
        Spacer(Modifier.height(24.dp + LocalDockInset.current))
    }
}

/** 备份文件名：英文 + 日期 + 时分秒，例如 `gszx-backup-20261014-215939.json` */
private fun defaultBackupFileName(): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "gszx-backup-$stamp.json"
}

/**
 * 热重载的提示文案。
 *
 * **点一次必有一条反馈**（2.12.1 起）。此前只有「没有正在运行的目标进程」会提示，
 * 成功 / 失败一律只写日志 —— 结果就是目标进程**在后台**（或一切正常）时点下去
 * 屏幕上毫无动静，用户根本没法判断到底重载了没有。
 *
 * 文案与 `HotReloadResult.Status` 一一对应（`SUCCEEDED` / `FAILED` / `UNSUPPORTED` /
 * `IN_PROGRESS` / `PROCESS_DIED`，见 libxposed service 的 `IXposedService.aidl`），
 * 另加一档「回调超时」——那是本地等不到回调，不是框架给的终态。
 */
private data class HotReloadTexts(
    val noFramework: String,
    val noTarget: String,
    val ok: String,
    val failed: String,
    val unsupported: String,
    val inProgress: String,
    val died: String,
    val timeout: String,
)

@Composable
private fun hotReloadTexts() = HotReloadTexts(
    noFramework = stringResource(R.string.action_hot_reload_need_framework),
    noTarget = stringResource(R.string.msg_no_running_target),
    ok = stringResource(R.string.msg_hot_reload_ok),
    failed = stringResource(R.string.msg_hot_reload_failed),
    unsupported = stringResource(R.string.msg_hot_reload_unsupported),
    inProgress = stringResource(R.string.msg_hot_reload_in_progress),
    died = stringResource(R.string.msg_hot_reload_died),
    timeout = stringResource(R.string.msg_hot_reload_timeout),
)

/**
 * 请求热重载（API 102 的服务侧能力），并返回一条**必不为空**的结果文案。
 *
 * **必须在 IO 线程执行**：binder 调用不能放主线程，回调要等（最多 8 秒）。
 *
 * 时序：`hotReloadModule` 是异步的，框架在目标进程换完代码后经
 * [XposedService.HotReloadCallback] 回一个终态；这里用 `CountDownLatch` 把它接回
 * 同步流程，`await` 到就报真实结果，等不到（8 秒）就报超时。
 *
 * @return 一定非空 —— 调用方无条件弹 Toast。
 */
private suspend fun requestHotReload(s: HotReloadTexts): String = withContext(Dispatchers.IO) {
    // 服务在「连接」与「点击」之间掉线是可能的（进程被杀 / 框架重启），
    // 此时控件虽然 disabled，仍要兜一句文案，别静默返回。
    val svc: XposedService = FrameworkBridge.service()
        ?: return@withContext s.noFramework

    try {
        val targets = svc.runningTargets
        if (targets.isEmpty()) return@withContext s.noTarget

        val target = targets.first()
        val latch = CountDownLatch(1)
        // 回调在 binder 线程，结果读在主线程 —— 用 AtomicReference 而不是裸 var：
        // 拿到的是明确的发生在 latch 之前的写，语义比依赖 happens-before 更直白。
        val result = AtomicReference<HotReloadResult?>(null)

        svc.hotReloadModule(
            target,
            null,
            object : XposedService.HotReloadCallback {
                override fun onHotReloadResult(t: HookedTarget, r: HotReloadResult) {
                    GLog.i("热重载 ${t.processName}(pid ${t.pid})：${r.status} ${r.message ?: ""}")
                    result.set(r)
                    latch.countDown()
                }
            },
        )

        if (!latch.await(8, TimeUnit.SECONDS)) {
            GLog.w("热重载 ${target.processName}(pid ${target.pid}) 超时：8 秒内没有回调")
            return@withContext s.timeout
        }

        when (result.get()?.status) {
            HotReloadResult.Status.SUCCEEDED -> s.ok
            HotReloadResult.Status.IN_PROGRESS -> s.inProgress
            HotReloadResult.Status.UNSUPPORTED -> s.unsupported
            HotReloadResult.Status.PROCESS_DIED -> s.died
            HotReloadResult.Status.FAILED -> s.failed
            // 兜底：`await` 已返回却仍没有结果（理论上不可达），或框架日后新增状态码。
            // 一律归到「失败」，绝不静默。
            else -> s.failed
        }
    } catch (t: Throwable) {
        GLog.w("热重载失败", t)
        s.failed
    }
}
