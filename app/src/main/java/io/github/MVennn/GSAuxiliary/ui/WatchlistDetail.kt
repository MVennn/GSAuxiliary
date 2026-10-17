package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「自选股高亮」的二级菜单。
 *
 * 一级页面（功能 Tab）只放总开关；这里放该功能**全部可调节项**：
 * 高亮颜色设置（作用域）、高亮颜色、效果预览。
 */
@Composable
internal fun WatchlistDetailScreen() {
    val scrollState = rememberScrollState()

    val enabled by rememberBoolPref(ConfigKeys.ENABLED, ConfigKeys.DEFAULT_ENABLED)
    val color by rememberIntPref(ConfigKeys.COLOR, ConfigKeys.DEFAULT_COLOR)
    val scopeStrategy by rememberBoolPref(ConfigKeys.SCOPE_STRATEGY, ConfigKeys.DEFAULT_SCOPE_STRATEGY)

    val presetNames = stringArrayResource(R.array.preset_color_names)

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
            SwitchPreference(
                checked = scopeStrategy,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.SCOPE_STRATEGY, v) }
                },
                title = stringResource(R.string.item_scope_strategy),
                enabled = enabled,
            )
        }

        // --- 高亮颜色 ---
        // 一个控件同时承担「看颜色」和「选颜色」：大圆色块 + 名称，
        // 选中描主色环 + 对勾；不再叠一份重复的 RadioButton 列表。
        // 卡底不放随选中色变化的说明行（2.17.2 删）—— 颜色名已经写在色块下面，
        // 说明行只是把同一件事再讲一遍，真机看着信息量为零。
        SmallTitle(stringResource(R.string.section_highlight_color))
        Card(modifier = Modifier.cardPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ConfigKeys.PRESET_COLORS.forEachIndexed { i, value ->
                    ColorPresetItem(
                        name = presetNames.getOrElse(i) { "" },
                        color = value,
                        selected = color == value,
                        onClick = { ModulePreferences.edit { putInt(ConfigKeys.COLOR, value) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // --- 效果预览 ---
        // 预览本身已经说明了一切（两个名字一高一低的色差摆在眼前），
        // 卡底不再放口径说明（2.17.2 删）。
        SmallTitle(stringResource(R.string.section_preview))
        Card(modifier = Modifier.cardPadding()) {
            PreviewRow(
                name = stringResource(R.string.preview_sample_in),
                nameColor = Color(color),
                change = PreviewChangeUp,
                changeColor = PreviewUpColor,
                tag = stringResource(R.string.preview_in_watchlist),
            )
            PreviewRow(
                name = stringResource(R.string.preview_sample_out),
                // 「未加自选」必须跟随深浅模式 —— 用主题的 onBackground，
                // 而不是写死深色，否则深色模式下就是黑底黑字
                nameColor = MiuixTheme.colorScheme.onBackground,
                change = PreviewChangeDown,
                changeColor = PreviewDownColor,
                tag = stringResource(R.string.preview_not_in_watchlist),
            )
        }

        // 手势导航条让位：内容铺到屏幕最底边（沉浸），最后一项再让开导航条高度
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

// 预览用的涨跌幅示例。涨红跌绿（A 股约定），与目标 App 行情页一致。
private const val PreviewChangeUp = "+2.34%"
private const val PreviewChangeDown = "-1.12%"
private val PreviewUpColor = Color(0xFFE64545)
private val PreviewDownColor = Color(0xFF1AAE6A)
