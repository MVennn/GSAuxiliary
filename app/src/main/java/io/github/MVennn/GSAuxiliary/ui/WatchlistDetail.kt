package io.github.MVennn.GSAuxiliary.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.WatchlistColorStore
import io.github.MVennn.GSAuxiliary.core.WatchlistTier
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「自选股高亮」的二级菜单。
 *
 * 一级页面（功能 Tab）只放总开关；这里放该功能**全部可调节项**：
 * 作用域（是否也管策略详情页）、三档高亮颜色（涨 / 跌 / 平）、效果预览。
 *
 * 三档颜色写在**同一个**参数键 [ConfigKeys.WATCHLIST_COLORS] 里（JSON 一份）：
 * 三个选择行各自选一色，改完立刻落盘整份 JSON —— 不存「半份」配置，
 * 也就不会出现「涨跌改了、平盘还是老色」这种中间态。
 */
@Composable
internal fun WatchlistDetailScreen() {
    val scrollState = rememberScrollState()

    val enabled by rememberBoolPref(ConfigKeys.ENABLED, ConfigKeys.DEFAULT_ENABLED)
    val legacyColor by rememberIntPref(ConfigKeys.COLOR, ConfigKeys.DEFAULT_COLOR)
    val colorsRaw by rememberStringPref(
        ConfigKeys.WATCHLIST_COLORS, ConfigKeys.DEFAULT_WATCHLIST_COLORS,
    )
    val scopeStrategy by rememberBoolPref(ConfigKeys.SCOPE_STRATEGY, ConfigKeys.DEFAULT_SCOPE_STRATEGY)

    // stringArrayResource 给的是 Array<String>，选择项与行副标题都按 List 用，这里转一次
    val presetNames = stringArrayResource(R.array.preset_color_names).toList()

    // 界面每帧从同一份 JSON 解出来（decode 带身份 memo，同一串只解一次）：
    // 显示的就是目标进程会用的那一份，不存在「界面一套、生效一套」。
    val colors = remember(colorsRaw, legacyColor) {
        WatchlistColorStore.decode(colorsRaw, legacyColor)
    }

    fun pick(tier: String, value: Int) {
        val next = colors.toMutableMap()
        next[tier] = value
        ModulePreferences.edit {
            putString(ConfigKeys.WATCHLIST_COLORS, WatchlistColorStore.encode(next))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        // --- 作用于策略详情页 ---
        // 不放分组标题：页标题就是「高亮颜色设置」，再来一个同名 SmallTitle 是同一句
        // 话说两遍（2.17.2）。开关也不带副标题 —— 原来那行钩子目标
        // （AIStkStgDetailAct · tv_item_stk_name）是开发者才看得懂的类名 / 控件 ID，
        // 面向用户的界面不写这类信息，完整档案在 docs/ARCHITECTURE.md 与
        // docs/HOOK-NOTES.md。
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = scopeStrategy,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.SCOPE_STRATEGY, v) }
                },
                title = stringResource(R.string.item_scope_strategy),
                enabled = enabled,
            )
        }

        // --- 三档颜色 ---
        // 每一行都是「选择行」：当前色的名字进副标题、右侧朝下箭头、点开贴控件右下的
        // 单选菜单（规则见 docs/DESIGN-SYSTEM.md §3.5）。三行共用同一份预设色表，
        // 选完立刻落盘。
        SmallTitle(stringResource(R.string.section_highlight_color))
        Card(modifier = Modifier.cardPadding()) {
            TierColorRow(
                title = stringResource(R.string.item_color_up),
                tier = WatchlistTier.UP,
                current = colors[WatchlistTier.UP] ?: legacyColor,
                presetNames = presetNames,
                onPick = ::pick,
            )
            TierColorRow(
                title = stringResource(R.string.item_color_down),
                tier = WatchlistTier.DOWN,
                current = colors[WatchlistTier.DOWN] ?: legacyColor,
                presetNames = presetNames,
                onPick = ::pick,
            )
            TierColorRow(
                title = stringResource(R.string.item_color_flat),
                tier = WatchlistTier.FLAT,
                current = colors[WatchlistTier.FLAT] ?: legacyColor,
                presetNames = presetNames,
                onPick = ::pick,
            )
        }

        // --- 效果预览 ---
        // 预览本身已经说明了一切（三档色差摆在眼前 + 一行没加自选作对照），
        // 卡底不再放口径说明（2.17.2 删）。
        //
        // 示例名**必须是占位名**（示例股票甲 / 乙 / 丙 / 丁）：以前用的是真实个股
        // （莱美药业、七一二），用户在行情里能搜到，看着像模块在暗示某只票。
        SmallTitle(stringResource(R.string.section_preview))
        Card(modifier = Modifier.cardPadding()) {
            PreviewRow(
                name = stringResource(R.string.preview_sample_in),
                nameColor = Color(colors[WatchlistTier.UP] ?: legacyColor),
                change = PreviewChangeUp,
                changeColor = PreviewUpColor,
                tag = stringResource(R.string.preview_in_watchlist),
            )
            PreviewRow(
                name = stringResource(R.string.preview_sample_out),
                nameColor = Color(colors[WatchlistTier.DOWN] ?: legacyColor),
                change = PreviewChangeDown,
                changeColor = PreviewDownColor,
                tag = stringResource(R.string.preview_in_watchlist),
            )
            PreviewRow(
                name = stringResource(R.string.preview_sample_flat),
                nameColor = Color(colors[WatchlistTier.FLAT] ?: legacyColor),
                change = PreviewChangeFlat,
                // 平盘不给红绿：跟着主题的前景色走，深浅模式都不会看不见
                changeColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                tag = stringResource(R.string.preview_in_watchlist),
            )
            PreviewRow(
                name = stringResource(R.string.preview_sample_plain),
                // 「未加自选」必须跟随深浅模式 —— 用主题的 onBackground，
                // 而不是写死深色，否则深色模式下就是黑底黑字
                nameColor = MiuixTheme.colorScheme.onBackground,
                change = PreviewChangeUp,
                changeColor = PreviewUpColor,
                tag = stringResource(R.string.preview_not_in_watchlist),
            )
        }

        // 手势导航条让位：内容铺到屏幕最底边（沉浸），最后一项再让开导航条高度
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/**
 * 一档颜色的选择行：当前色名做副标题，点开贴控件右下的单选菜单。
 *
 * 锚点按**每一行自己**的 `boundsInWindow()` 记（三个独立变量，见 [TierColorRow] 内部）——
 * 三行共用一个变量时，点是第一行、菜单会从第三行弹出来（docs/DESIGN-SYSTEM.md §3.5）。
 */
@Composable
private fun TierColorRow(
    title: String,
    tier: String,
    current: Int,
    presetNames: List<String>,
    onPick: (String, Int) -> Unit,
) {
    val dialogs = LocalDialogController.current
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val selectedIndex = ConfigKeys.PRESET_COLORS.indexOf(current)

    BasicComponent(
        title = title,
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
        summary = presetNames.getOrNull(selectedIndex) ?: "",
        // 朝下箭头：这一行是**原地弹窗**，不是进入下一级（见 DownArrowIcon）
        endActions = { DownArrowIcon() },
        onClick = {
            dialogs?.showChoice(
                ChoiceDialogSpec(
                    items = presetNames.toList(),
                    selectedIndex = selectedIndex.coerceAtLeast(0),
                    anchor = anchor,
                    onSelect = { idx ->
                        ConfigKeys.PRESET_COLORS.getOrNull(idx)?.let { onPick(tier, it) }
                    },
                ),
            )
        },
    )
}

// 预览用的涨跌幅示例。涨红跌绿（A 股约定），与目标 App 行情页一致；平盘走主题前景色。
private const val PreviewChangeUp = "+2.34%"
private const val PreviewChangeDown = "-1.12%"
private const val PreviewChangeFlat = "0.00%"
private val PreviewUpColor = Color(0xFFE64545)
private val PreviewDownColor = Color(0xFF1AAE6A)
