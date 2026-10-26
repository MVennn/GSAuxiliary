package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyRuleStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 新建规则那一行的展开动画时长 */
private const val CopyRuleAppearMillis = 260

/** 删除规则那一行的收起动画时长 */
private const val CopyRuleRemoveMillis = 220

/**
 * 「自选分组复制 → 复制规则」二级页。
 *
 * 与「一键配置」的方案列表页同构：规则列表 + 行尾 ⋮（设为默认 / 上移 / 下移 / 删除）
 * + 新建规则；每改一下立即写 `zx_copy_rules`。
 *
 * 页面顶部单独放一颗**长按动作菜单**开关（全局，不属于某条规则）。
 */
@Composable
internal fun ZxCopyRuleScreen(onEdit: (String) -> Unit) {
    val scrollState = rememberScrollState()

    val json by rememberStringPref(ConfigKeys.ZX_COPY_RULES, ConfigKeys.DEFAULT_ZX_COPY_RULES)
    val data = remember(json) { ZxCopyRuleStore.decode(json) }

    val fieldNames = stringArrayResource(R.array.zx_copy_field_names)
    val formatTexts = listOf(
        stringResource(R.string.zx_copy_format_text),
        stringResource(R.string.zx_copy_format_markdown),
        stringResource(R.string.zx_copy_format_csv),
        stringResource(R.string.zx_copy_format_json),
        stringResource(R.string.zx_copy_format_template),
    )
    val actionTexts = listOf(
        stringResource(R.string.zx_copy_action_clipboard),
        stringResource(R.string.zx_copy_action_share_text),
        stringResource(R.string.zx_copy_action_image),
        stringResource(R.string.zx_copy_action_pdf),
    )
    val menuTexts = listOf(
        stringResource(R.string.zx_copy_menu_set_default),
        stringResource(R.string.zx_copy_menu_move_up),
        stringResource(R.string.zx_copy_menu_move_down),
        stringResource(R.string.zx_copy_menu_delete),
    )
    val newPrefix = stringResource(R.string.zx_copy_new_rule_prefix)
    val moreItemsFormat = stringResource(R.string.preview_more_items)
    // 元信息前缀随规则 JSON 带去目标进程，必须在这里按当前语言取一次
    val metaLabels = mapOf(
        "group" to stringResource(R.string.meta_group_prefix),
        "time" to stringResource(R.string.meta_time_prefix),
        "count" to stringResource(R.string.meta_count_prefix),
        "countUnit" to stringResource(R.string.meta_count_unit),
    )

    var justAdded by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- 规则列表 ----
        SmallTitle(stringResource(R.string.zx_copy_section_rules))
        Card(modifier = Modifier.cardPadding()) {
            data.rules.forEachIndexed { index, rule ->
                key(rule.id) {
                    val preview = zxCopyPreview(rule, fieldNames, formatTexts, actionTexts, moreItemsFormat)
                    val row: @Composable () -> Unit = {
                        CopyRuleRow(
                            name = rule.name,
                            preview = preview,
                            isDefault = rule.id == data.defaultId,
                            menuTexts = menuTexts,
                            disabledMenuIndices = buildSet {
                                if (rule.id == data.defaultId) add(0)
                                if (index == 0) add(1)
                                if (index == data.rules.size - 1) add(2)
                                if (data.rules.size <= 1) add(3)
                            },
                            onClick = { onEdit(rule.id) },
                            onMenu = { which ->
                                when (which) {
                                    0 -> {
                                        lastZxCopyDefaultId = data.defaultId.takeIf { it != rule.id }
                                        val updated = data.rules.map {
                                            it.copy(isDefault = it.id == rule.id)
                                        }
                                        writeZxCopyRules(
                                            data.copy(defaultId = rule.id, rules = updated),
                                        )
                                    }
                                    1 -> if (index > 0) {
                                        val list = data.rules.toMutableList()
                                        list.add(index - 1, list.removeAt(index))
                                        writeZxCopyRules(data.copy(rules = list))
                                    }
                                    2 -> if (index < data.rules.size - 1) {
                                        val list = data.rules.toMutableList()
                                        list.add(index + 1, list.removeAt(index))
                                        writeZxCopyRules(data.copy(rules = list))
                                    }
                                    3 -> removing = rule.id
                                }
                            },
                        )
                    }
                    val visibleState = remember(rule.id) {
                        MutableTransitionState(rule.id != justAdded)
                    }
                    LaunchedEffect(rule.id) {
                        if (rule.id == justAdded) {
                            visibleState.targetState = true
                            delay(CopyRuleAppearMillis.toLong())
                            if (justAdded == rule.id) justAdded = null
                        }
                    }
                    LaunchedEffect(Unit) {
                        snapshotFlow { removing == rule.id }.first { it }
                        visibleState.targetState = false
                        withTimeoutOrNull(CopyRuleRemoveMillis.toLong() + 300) {
                            snapshotFlow { visibleState.currentState }.first { !it }
                        }
                        deleteCopyRule(rule.id)
                        removing = null
                    }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = expandVertically(tween(CopyRuleAppearMillis)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(CopyRuleRemoveMillis)) + fadeOut(tween(140)),
                    ) {
                        row()
                    }
                }
            }

            BasicComponent(
                title = stringResource(R.string.zx_copy_new_rule),
                titleColor = BasicComponentDefaults.titleColor(
                    color = MiuixTheme.colorScheme.primary,
                ),
                startAction = {
                    TintedIcon(imageVector = AppIcons.Add, tint = MiuixTheme.colorScheme.primary)
                },
                endActions = {
                    Row(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { ArrowRightIcon() }
                },
                onClick = {
                    val source = data.defaultRule() ?: data.rules.firstOrNull() ?: return@BasicComponent
                    val id = ZxCopyRuleStore.newId(data.rules)
                    val name = ZxCopyRuleStore.newName(data.rules.map { it.name }, newPrefix)
                    val rule = ZxCopyRuleStore.duplicate(source, id, name).copy(
                        metaLabels = metaLabels,
                        preview = zxCopyPreview(
                            source.copy(id = id, name = name),
                            fieldNames, formatTexts, actionTexts, moreItemsFormat,
                        ),
                    )
                    writeZxCopyRules(data.copy(rules = data.rules + rule))
                    justAdded = id
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/**
 * 一行规则：整行点击进编辑，行尾 ⋮ 弹菜单（组件自带右箭头示意「还有下一级」）。
 *
 * 锚点必须是**行内自己的状态**（与 QuickConfigScreen 同一个坑）：共享一个变量时，
 * 点第一行的 ⋮ 会从最后一行弹出来。
 */
@Composable
private fun CopyRuleRow(
    name: String,
    preview: String,
    isDefault: Boolean,
    menuTexts: List<String>,
    disabledMenuIndices: Set<Int>,
    onClick: () -> Unit,
    onMenu: (Int) -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current

    BasicComponent(
        title = name,
        summary = preview.ifEmpty { null },
        endActions = {
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isDefault) {
                    CopyDefaultBadge()
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(
                    onClick = {
                        dialogs?.showChoice(
                            ChoiceDialogSpec(
                                items = menuTexts,
                                selectedIndex = -1,
                                onSelect = onMenu,
                                anchor = anchor,
                                disabledIndices = disabledMenuIndices,
                            ),
                        )
                    },
                    modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
                ) {
                    TintedIcon(
                        imageVector = AppIcons.MoreVert,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                ArrowRightIcon()
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun CopyDefaultBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.zx_copy_default_badge),
            color = MiuixTheme.colorScheme.primary,
            fontSize = 10.sp,
        )
    }
}

/** 删除规则：只剩一条时忽略；重新读一次配置，避免用调用点捕获的旧快照 */
private fun deleteCopyRule(id: String) {
    val data = ZxCopyRuleStore.decode(
        ModulePreferences.getString(ConfigKeys.ZX_COPY_RULES, ConfigKeys.DEFAULT_ZX_COPY_RULES),
    )
    if (data.rules.size <= 1) return
    val rest = data.rules.filter { it.id != id }
    if (rest.isEmpty()) return
    val newDefault = data.defaultId.takeIf { def -> rest.any { it.id == def } } ?: rest.first().id
    writeZxCopyRules(
        ZxCopyRuleStore.Data(
            defaultId = newDefault,
            rules = rest.map { if (it.id == newDefault) it.copy(isDefault = true) else it },
        ),
    )
}
