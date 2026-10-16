package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.QuickConfigCatalog
import io.github.MVennn.GSAuxiliary.core.QuickConfigStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.flow.drop

/**
 * 方案编辑页（§6.3）：名称、涨到槽、跌到槽、提醒时效、设为默认、删除。
 *
 * **每改一下立即写回 remote preferences**（§6.3，与其它二级页一致），
 * 不做「保存」按钮。写入时顺带重生成方案的一行预览（列表页与 H5 选择层共用）。
 *
 * 排版（2.18 起）：所有「点一下选一个」的行统一换成 Miuix [ArrowPreference] /
 * [BasicComponent] —— 标题、当前值（summary）、右箭头、按压高亮都交给组件，
 * 不再手写 Row + 裸文字伪按钮；取值项的删除换成真正的 [IconButton]（Close 图标）。
 * 「提醒时效」不再放同名 SmallTitle —— 行标题就是「提醒时效」，分组标题是同一句话。
 */
@Composable
internal fun QuickConfigEditScreen(schemeId: String) {
    val scrollState = rememberScrollState()
    val dialogs = LocalDialogController.current

    val json by rememberStringPref(ConfigKeys.AISTARE_QUICK_CONFIG, ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG)
    val data = remember(json) { QuickConfigStore.decode(json) }
    val scheme = remember(json, schemeId) { data.schemes.firstOrNull { it.id == schemeId } }

    val termNames = stringArrayResource(R.array.quick_term_names)
    val aggTexts = listOf(
        stringResource(R.string.quick_agg_max),
        stringResource(R.string.quick_agg_min),
        stringResource(R.string.quick_agg_avg),
    )
    val validityTexts = listOf(
        stringResource(R.string.quick_validity_keep),
        stringResource(R.string.quick_validity_once),
        stringResource(R.string.quick_validity_long),
    )
    val upLabel = stringResource(R.string.quick_slot_up_short)
    val downLabel = stringResource(R.string.quick_slot_down_short)
    val periodChoices = QuickConfigCatalog.ALL
        .first { it.id == "highN" }.params.first().periodChoices
    val periodTexts = periodChoices.map { stringResource(R.string.quick_param_period_fmt, it) }

    var anchor by remember { mutableStateOf<Rect?>(null) }

    if (scheme == null) {
        // 方案被并发删除（另一处界面 / adb 通道）：安静退出内容区即可，外层返回键照常
        Column(Modifier.fillMaxSize()) { Spacer(Modifier.height(24.dp)) }
        return
    }

    /** 在整份数据上改当前方案并写回（保持 isDefault 唯一） */
    fun update(transform: (QuickConfigStore.Scheme) -> QuickConfigStore.Scheme) {
        val updated = data.schemes.map { if (it.id == schemeId) transform(it) else it }
        val newDefaultId = data.defaultId.takeIf { id -> updated.any { it.id == id } }
            ?: updated.firstOrNull { it.isDefault }?.id
            ?: updated.first().id
        writeSchemes(
            QuickConfigStore.Data(
                defaultId = newDefaultId,
                schemes = updated.map { s ->
                    if (s.id == newDefaultId) {
                        s.copy(isDefault = true)
                    } else if (s.isDefault) {
                        s.copy(isDefault = false)
                    } else {
                        s
                    }
                },
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- 方案名称 ----
        Card(modifier = Modifier.cardPadding()) {
            val nameState = remember(scheme.id) { TextFieldState(scheme.name) }
            LaunchedEffect(nameState, scheme.id) {
                snapshotFlow { nameState.text.toString() }
                    .drop(1) // 初始值不回写（会把 preview 触发一次无谓重算）
                    .collect { text ->
                        if (text.isNotBlank()) update { it.copy(name = text, preview = previewOf(it, termNames, upLabel, downLabel)) }
                    }
            }
            TextField(
                state = nameState,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                label = stringResource(R.string.quick_field_name),
                useLabelAsPlaceholder = true,
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                ),
            )
        }

        // ---- 两个槽 ----
        SlotEditor(
            title = stringResource(R.string.quick_slot_up),
            scheme = scheme,
            isUp = true,
            termNames = termNames,
            aggTexts = aggTexts,
            periodTexts = periodTexts,
            anchor = anchor,
            anchorMod = { Modifier.onGloballyPositioned { anchor = it.boundsInWindow() } },
            dialogs = dialogs,
            onChanged = { slot -> update { s -> s.copy(up = slot, preview = previewOf(s.copy(up = slot), termNames, upLabel, downLabel)) } },
        )
        SlotEditor(
            title = stringResource(R.string.quick_slot_down),
            scheme = scheme,
            isUp = false,
            termNames = termNames,
            aggTexts = aggTexts,
            periodTexts = periodTexts,
            anchor = anchor,
            anchorMod = { Modifier.onGloballyPositioned { anchor = it.boundsInWindow() } },
            dialogs = dialogs,
            onChanged = { slot -> update { s -> s.copy(down = slot, preview = previewOf(s.copy(down = slot), termNames, upLabel, downLabel)) } },
        )

        // ---- 提醒时效 ----
        // 行标题已经说明一切，不再放同名 SmallTitle（同一信息只说一遍，2.18）
        Card(modifier = Modifier.cardPadding()) {
            ArrowPreference(
                title = stringResource(R.string.quick_field_validity),
                modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
                summary = validityTexts[validityIndexOf(scheme.validity)],
                onClick = {
                    dialogs?.showChoice(
                        ChoiceDialogSpec(
                            items = validityTexts,
                            selectedIndex = validityIndexOf(scheme.validity),
                            onSelect = { which ->
                                val v = when (which) {
                                    1 -> QuickConfigStore.VALIDITY_ONCE
                                    2 -> QuickConfigStore.VALIDITY_LONG
                                    else -> null
                                }
                                update { it.copy(validity = v) }
                            },
                            anchor = anchor,
                        ),
                    )
                },
            )
        }

        // ---- 设为默认 / 删除 ----
        SmallTitle(stringResource(R.string.quick_section_actions))
        Card(modifier = Modifier.cardPadding()) {
            SwitchPreference(
                checked = scheme.isDefault,
                onCheckedChange = { v ->
                    if (v) {
                        val updated = data.schemes.map { it.copy(isDefault = it.id == schemeId) }
                        writeSchemes(data.copy(defaultId = schemeId, schemes = updated))
                    }
                    // 取消默认：不响应 —— 必须有一个默认方案（§6.3），换默认走另一条的开关或 ⋮ 菜单
                },
                title = stringResource(R.string.quick_set_default),
            )
            val deletable = data.schemes.size > 1
            // 弹窗文案在 composable 作用域先解析（lambda 里调不了 stringResource）
            val deleteTitle = stringResource(R.string.quick_delete_scheme)
            val deleteConfirm = stringResource(R.string.quick_delete_confirm)
            val confirmText = stringResource(R.string.action_confirm)
            val cancelText = stringResource(R.string.action_cancel)
            ArrowPreference(
                title = stringResource(R.string.quick_delete_scheme),
                summary = if (deletable) null else stringResource(R.string.quick_delete_last_hint),
                onClick = {
                    if (deletable && dialogs != null) {
                        dialogs.showConfirm(
                            ConfirmDialogSpec(
                                title = deleteTitle,
                                message = deleteConfirm,
                                confirmText = confirmText,
                                cancelText = cancelText,
                                onConfirm = {
                                    val rest = data.schemes.filter { it.id != schemeId }
                                    val newDefault = data.defaultId.takeIf { id -> rest.any { it.id == id } }
                                        ?: rest.first().id
                                    writeSchemes(
                                        QuickConfigStore.Data(
                                            defaultId = newDefault,
                                            schemes = rest.map {
                                                if (it.id == newDefault) it.copy(isDefault = true) else it
                                            },
                                        ),
                                    )
                                },
                            ),
                        )
                    }
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

// ------------------------------------------------------------ 槽编辑

@Composable
private fun SlotEditor(
    title: String,
    scheme: QuickConfigStore.Scheme,
    isUp: Boolean,
    termNames: Array<String>,
    aggTexts: List<String>,
    periodTexts: List<String>,
    anchor: Rect?,
    anchorMod: () -> Modifier,
    dialogs: DialogController?,
    onChanged: (QuickConfigStore.Slot) -> Unit,
) {
    val slot = if (isUp) scheme.up else scheme.down
    val catalog = QuickConfigCatalog.ALL

    SmallTitle(title)
    Card(modifier = Modifier.cardPadding()) {
        // 聚合（多值才有意义，但始终显示 —— 一看就知道多个取值项怎么合并）：
        // Miuix ArrowPreference，当前值在 summary，行尾右箭头示意「点开有选择」
        ArrowPreference(
            title = stringResource(R.string.quick_field_agg),
            modifier = anchorMod(),
            summary = aggTexts[aggIndexOf(slot.agg)],
            onClick = {
                dialogs?.showChoice(
                    ChoiceDialogSpec(
                        items = aggTexts,
                        selectedIndex = aggIndexOf(slot.agg),
                        onSelect = { which ->
                            onChanged(slot.copy(agg = aggValueOf(which)))
                        },
                        anchor = anchor,
                    ),
                )
            },
        )

        slot.terms.forEachIndexed { index, term ->
            TermRow(
                term = term,
                termIndex = index,
                termNames = termNames,
                anchor = anchor,
                anchorMod = anchorMod,
                dialogs = dialogs,
                onTermChange = { newTerm ->
                    onChanged(
                        slot.copy(
                            terms = slot.terms.mapIndexed { i, t -> if (i == index) newTerm else t },
                        ),
                    )
                },
                onRemove = {
                    onChanged(slot.copy(terms = slot.terms.filterIndexed { i, _ -> i != index }))
                },
            )
        }

        // ＋ 添加取值项（目录平铺，顺序 = 分组顺序）：与列表页「新建方案」同款动作行
        BasicComponent(
            title = stringResource(R.string.quick_add_term),
            titleColor = BasicComponentDefaults.titleColor(
                color = MiuixTheme.colorScheme.primary,
                disabledColor = MiuixTheme.colorScheme.primary,
            ),
            startAction = {
                Icon(
                    imageVector = AppIcons.Add,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                )
            },
            modifier = anchorMod(),
            onClick = {
                dialogs?.showChoice(
                    ChoiceDialogSpec(
                        items = catalog.map { spec ->
                            val n: String = termNames.getOrElse(QuickConfigCatalog.index(spec.id)) { spec.id }
                            n
                        },
                        selectedIndex = -1,
                        onSelect = { which ->
                            val picked = catalog[which]
                            // 已有同项就不再重复添加
                            if (slot.terms.any { it.source == picked.id }) return@ChoiceDialogSpec
                            val param = picked.params.associate { p ->
                                p.key to when {
                                    p.periodChoices.isNotEmpty() -> p.periodChoices.first()
                                    p.key == QuickConfigCatalog.PARAM_PCT -> "5"
                                    else -> ""
                                }
                            }
                            onChanged(
                                slot.copy(terms = slot.terms + QuickConfigStore.Term(picked.id, param)),
                            )
                        },
                        anchor = anchor,
                    ),
                )
            },
        )
    }
}

/** 一个取值项：左侧名称，右侧参数控件 + 删除按钮（真正的 IconButton，触控目标够大） */
@Composable
private fun TermRow(
    term: QuickConfigStore.Term,
    termIndex: Int,
    termNames: Array<String>,
    anchor: Rect?,
    anchorMod: () -> Modifier,
    dialogs: DialogController?,
    onTermChange: (QuickConfigStore.Term) -> Unit,
    onRemove: () -> Unit,
) {
    val catalog = QuickConfigCatalog.ALL
    val spec = catalog.firstOrNull { it.id == term.source }
    val name = termNames.getOrElse(QuickConfigCatalog.index(term.source)) { term.source }

    BasicComponent(
        title = name,
        endActions = {
            // 参数控件（周期 = 弹层选择；百分比 / 固定价 = 数字输入）
            for (p in spec?.params ?: emptyList()) {
                when {
                    p.periodChoices.isNotEmpty() -> {
                        val current: String = term.param[p.key] ?: p.periodChoices.first()
                        val choiceTexts: List<String> = p.periodChoices.map {
                            stringResource(R.string.quick_param_period_fmt, it)
                        }
                        Text(
                            text = stringResource(R.string.quick_param_period_fmt, current),
                            color = MiuixTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            modifier = anchorMod()
                                .clickable {
                                    dialogs?.showChoice(
                                        ChoiceDialogSpec(
                                            items = choiceTexts,
                                            selectedIndex = p.periodChoices.indexOf(current),
                                            onSelect = { which ->
                                                val newParam = term.param.toMutableMap()
                                                newParam[p.key] = p.periodChoices[which]
                                                onTermChange(term.copy(param = newParam))
                                            },
                                            anchor = anchor,
                                        ),
                                    )
                                }
                                .padding(start = 8.dp),
                        )
                    }
                    p.key == QuickConfigCatalog.PARAM_PCT ||
                        p.key == QuickConfigCatalog.PARAM_VALUE -> {
                        val initial = term.param[p.key] ?: ""
                        val state = remember(term.source, termIndex) {
                            TextFieldState(initial)
                        }
                        LaunchedEffect(state) {
                            snapshotFlow { state.text.toString() }
                                .drop(1)
                                .collect { text ->
                                    val v = text.toDoubleOrNull()
                                    val valid = when (p.key) {
                                        QuickConfigCatalog.PARAM_PCT -> v != null && v >= p.pctMin && v <= p.pctMax
                                        else -> v != null && v > 0.0
                                    }
                                    if (valid) {
                                        val newParam = term.param.toMutableMap()
                                        newParam[p.key] = text
                                        onTermChange(term.copy(param = newParam))
                                    }
                                }
                        }
                        TextField(
                            state = state,
                            modifier = Modifier.width(96.dp).padding(start = 8.dp),
                            label = if (p.key == QuickConfigCatalog.PARAM_PCT) "%" else "",
                            useLabelAsPlaceholder = false,
                            cornerRadius = 10.dp,
                            colors = TextFieldDefaults.textFieldColors(
                                backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    }
                }
            }
            // 删除该项
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = MiuixIcons.Basic.Close,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
    )
}

// ------------------------------------------------------------ 小工具

private fun aggIndexOf(agg: String): Int = when (agg) {
    QuickConfigStore.AGG_MIN -> 1
    QuickConfigStore.AGG_AVG -> 2
    else -> 0
}

private fun aggValueOf(which: Int): String = when (which) {
    1 -> QuickConfigStore.AGG_MIN
    2 -> QuickConfigStore.AGG_AVG
    else -> QuickConfigStore.AGG_MAX
}

private fun validityIndexOf(validity: String?): Int = when (validity) {
    QuickConfigStore.VALIDITY_ONCE -> 1
    QuickConfigStore.VALIDITY_LONG -> 2
    else -> 0
}

/**
 * 一行预览（列表页与 H5 选择层共用）：「涨到 30日最高价 · 跌到 30日最低价」。
 * 多值时显示首项 + 「等 n 项」—— 首项是编辑页排最上面的主项（§4.1）。
 */
internal fun previewOf(
    scheme: QuickConfigStore.Scheme,
    termNames: Array<String>,
    upLabel: String,
    downLabel: String,
): String {
    fun slotText(slot: QuickConfigStore.Slot): String {
        val first = slot.terms.firstOrNull() ?: return "—"
        val name = termNames.getOrElse(QuickConfigCatalog.index(first.source)) { first.source }
        return if (slot.terms.size > 1) {
            stringResQuick(name, slot.terms.size)
        } else {
            name
        }
    }
    return upLabel + " " + slotText(scheme.up) + " · " + downLabel + " " + slotText(scheme.down)
}

/** 非 composable 上下文里的「X 等 n 项」拼接（目标进程也要用，不能取资源） */
private fun stringResQuick(name: String, count: Int): String = "$name 等${count}项"
