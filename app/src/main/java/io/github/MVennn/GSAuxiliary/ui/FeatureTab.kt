package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Tab 2 · 功能 —— 功能菜单，按分类用子标题隔开。
 *
 * ## 搜索框的位置（2.9.3 改动）
 *
 * 2.8.0 ~ 2.9.2 搜索框挂在**顶栏**上，好处是列表滚动时它不会滑走；
 * 但代价是它和「顶栏」绑死，切板块时得专门做一段进出动画，还要和板块本身的
 * 左右平移去对齐方向（2.9.1 的 `tabForward`、2.9.2 的竖向动画都是在打这个补丁）。
 *
 * 2.9.3 干脆把它**放回列表顶部**：作为列表的第一个元素，跟着列表一起滚动，
 * 于是——
 *  - 不再需要任何进出动画（它本来就只在「功能」板块存在，板块一切走就跟着走了）；
 *  - 不再需要和板块切换方向对齐，`tabForward` 那类参数彻底消失；
 *  - 顶栏回到「只有标题」，四个板块的顶栏完全一致，切换时视觉更稳。
 *
 * 状态仍然提升在 `HomeScaffold`（见那里的注释），所以切走再切回关键词不丢。
 *
 * 交互约定（所有可调节的功能都遵循）：
 *   打开某个功能的总开关 → 开关下方**展开**该功能的「XX设置」入口
 *   → 点击进入二级菜单，具体设置项都在二级菜单里。
 * 也就是说，一级页面只放「开关」，具体设置一律收进二级菜单。
 * （展开与否本身就说明了一切，页面上不再额外写提示语。）
 *
 * @param searchState 搜索框状态（提升在 `HomeScaffold`），关键词从它读出
 */
@Composable
internal fun FeatureTab(
    searchState: TextFieldState,
    onOpenDetail: (FeatureSpec, Boolean) -> Unit,
) {
    val scrollState = rememberScrollState()
    val query = searchState.text.toString()

    // 资源字符串必须先解析，才能参与搜索匹配
    val resolved = FEATURE_CATALOG.map { spec ->
        ResolvedFeature(
            spec = spec,
            title = stringResource(spec.titleRes),
            summary = stringResource(spec.summaryRes),
            category = stringResource(spec.categoryRes),
            switchTitle = stringResource(spec.switchTitleRes),
            switchSummary = spec.switchSummaryRes?.let { stringResource(it) },
        )
    }

    val filtered = if (query.isBlank()) {
        resolved
    } else {
        resolved.filter {
            it.title.contains(query, ignoreCase = true) ||
                    it.summary.contains(query, ignoreCase = true) ||
                    it.switchTitle.contains(query, ignoreCase = true) ||
                    it.switchSummary?.contains(query, ignoreCase = true) == true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        // 玻璃顶栏时内容铺到屏幕顶边，首屏空白在这里补（见 Glass.kt 的 LocalTopInset）。
        Spacer(Modifier.height(8.dp + LocalTopInset.current))

        // 搜索框 = 列表的第一个元素：跟着列表一起滚动，无任何进出动画
        FeatureSearchField(searchState)

        if (filtered.isEmpty()) {
            Card(modifier = Modifier.cardPadding()) {
                CardNote(stringResource(R.string.feature_empty))
            }
            Spacer(Modifier.height(24.dp + LocalDockInset.current))
            return@Column
        }

        // --- 按分类分组 ---
        filtered.groupBy { it.category }.forEach { (category, items) ->
            SmallTitle(category)
            Card(modifier = Modifier.cardPadding()) {
                items.forEach { feature ->
                    FeatureEntry(
                        feature = feature,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp + LocalDockInset.current))
    }
}

/**
 * 功能列表顶部的搜索框。
 *
 * **刻意不做任何进出动画**：它就是列表里的一行，出现 / 消失都由「所在板块是否
 * 可见」和「列表滚到哪里」自然决定 —— 任何额外的展开 / 滑入动画都会和列表滚动
 * 打架（历史包袱见本文件顶部注释）。
 *
 * ## 配色（2.9.4）
 *
 * 背景由 Miuix `TextField` 默认的 `secondaryContainer` 换成**卡片同色**
 * `surfaceContainer`（Card 的默认色：深色 #242424 / 浅色 #FFFFFF），
 * 搜索框读起来就是「列表里的一张卡」；
 * 图标与占位文字的对比度各提一档（`onSurfaceVariantSummary` / `onSecondaryContainer`
 * → `onSurfaceSecondary`，浅色 80% 黑、深色 80% 白），弱于已输入正文但清晰可辨。
 */
@Composable
private fun FeatureSearchField(state: TextFieldState) {
    TextField(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .cardPadding()
            .padding(bottom = 12.dp),
        label = stringResource(R.string.search_hint),
        useLabelAsPlaceholder = true,
        cornerRadius = 24.dp,
        colors = TextFieldDefaults.textFieldColors(
            // 与 Card 的默认色一致 —— 搜索框和功能卡片一色
            backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
            // 占位文字：原 onSecondaryContainer 偏灰（浅色 #A9A9A9），提一档
            labelColor = MiuixTheme.colorScheme.onSurfaceSecondary,
        ),
        leadingIcon = {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                // 与占位文字同一档；原 onSurfaceVariantSummary 只有 60% / 50% 透明
                tint = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
            )
        },
    )
}

/** 单个功能：开关 + （开启后展开的）二级菜单入口 */
@Composable
private fun FeatureEntry(
    feature: ResolvedFeature,
    onOpenDetail: (FeatureSpec, Boolean) -> Unit,
) {
    val spec = feature.spec
    val enabled by rememberBoolPref(spec.enableKey, spec.defaultEnabled)

    SwitchPreference(
        checked = enabled,
        onCheckedChange = { v -> ModulePreferences.edit { putBoolean(spec.enableKey, v) } },
        title = feature.switchTitle,
        summary = feature.switchSummary,
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
                endActions = { ArrowRightIcon() },
                onClick = { onOpenDetail(spec, false) },
            )
            // 第二个二级入口（目前只有「增加 配置 按钮」有）：同一个功能挂两个设置页
            spec.detailEntry2Res?.let { entry2 ->
                BasicComponent(
                    title = stringResource(entry2),
                    endActions = { ArrowRightIcon() },
                    onClick = { onOpenDetail(spec, true) },
                )
            }
        }
    }
}

/** 已解析成字符串的功能展示模型（搜索 / 渲染共用） */
private data class ResolvedFeature(
    val spec: FeatureSpec,
    val title: String,
    val summary: String,
    val category: String,
    val switchTitle: String,
    /** 可为 null —— 主标题已经说清功能时，卡片上就没有这一行（见 `docs/NAMING.md`） */
    val switchSummary: String?,
)
