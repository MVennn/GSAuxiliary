package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * Tab 2 · 功能 —— 功能菜单，按分类用子标题隔开。
 *
 * 交互约定（所有可调节的功能都遵循）：
 *   打开某个功能的总开关 → 开关下方**展开**该功能的「XX设置」入口
 *   → 点击进入二级菜单，具体设置项都在二级菜单里。
 * 也就是说，一级页面只放「开关」，具体设置一律收进二级菜单。
 * （展开与否本身就说明了一切，页面上不再额外写提示语。）
 */
@Composable
internal fun FeatureTab(
    onOpenDetail: (FeatureSpec, Boolean) -> Unit,
) {
    val scrollState = rememberScrollState()
    val rememberedGroups = remember { FEATURE_CATALOG.groupBy { it.categoryRes } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        // 玻璃顶栏时内容铺到屏幕顶边，首屏空白在这里补（见 Glass.kt 的 LocalTopInset）。
        Spacer(Modifier.height(8.dp + LocalTopInset.current))

        // --- 按分类分组 ---
        // 目录是常量：分组只做一次，别每次重组都重算
        rememberedGroups.forEach { (categoryRes, specs) ->
            SmallTitle(stringResource(categoryRes))
            Card(modifier = Modifier.cardPadding()) {
                specs.forEach { spec ->
                    FeatureEntry(
                        spec = spec,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp + LocalDockInset.current))
    }
}

@Composable
private fun FeatureEntry(
    spec: FeatureSpec,
    onOpenDetail: (FeatureSpec, Boolean) -> Unit,
) {
    val enabled by rememberBoolPref(spec.enableKey, spec.defaultEnabled)

    GsSwitchPreference(
        checked = enabled,
        onCheckedChange = { v -> ModulePreferences.edit { putBoolean(spec.enableKey, v) } },
        title = stringResource(spec.switchTitleRes),
        summary = spec.switchSummaryRes?.let { stringResource(it) },
    )

    if (!spec.hasDetail) return

    // 展开 / 收起本身就有明确的视觉反馈，不再配文字提示
    AnimatedVisibility(
        visible = enabled,
        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
    ) {
        // 必须显式竖排：`AnimatedVisibility` 的 content 是**叠放**的，
        // 两个入口直接并列会糊在同一行（3.1.1 踩过）
        Column {
            BasicComponent(
                title = stringResource(spec.detailEntryRes!!),
                summary = spec.detailEntrySummaryRes?.let { stringResource(it) },
                endActions = { CenteredArrow() },
                onClick = { onOpenDetail(spec, false) },
            )
            // 第二个二级入口（「一键配置」的配置按钮设置 / 「自选分组复制」的复制按钮设置）
            spec.detailEntry2Res?.let { entry2 ->
                BasicComponent(
                    title = stringResource(entry2),
                    endActions = { CenteredArrow() },
                    onClick = { onOpenDetail(spec, true) },
                )
            }
        }
    }
}

/** `endActions` 默认 Top 对齐；包一层居中 Row，`›` 才和相邻行的 `⋮` 在一条中线 */
@Composable
private fun RowScope.CenteredArrow(enabled: Boolean = true) {
    Row(
        modifier = Modifier.align(Alignment.CenterVertically),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArrowRightIcon(enabled)
    }
}
