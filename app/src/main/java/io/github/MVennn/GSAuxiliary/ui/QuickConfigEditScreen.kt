package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.QuickConfigCatalog
import io.github.MVennn.GSAuxiliary.core.QuickConfigStore
import kotlinx.coroutines.flow.drop
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 方案编辑页（三级页，§6.3）：名称、涨到槽、跌到槽、提醒时效、设为默认、删除。
 *
 * **每改一下立即写回 remote preferences**（§6.3，与其它二级页一致），
 * 不做「保存」按钮。写入时顺带重生成方案的一行预览（列表页与 H5 选择层共用）。
 *
 * ## 排版（3.1.x 重排）
 *
 * 三个分组依次是「最新涨到 / 最新跌到 / 操作」，每组一张卡；提醒时效单独一张卡，
 * 不再给它一个与行标题同名的 `SmallTitle`（同一句话说两遍）。卡内一律走 Miuix 组件：
 *
 * | 内容 | 组件 | 为什么 |
 * |---|---|---|
 * | 名称 | `TextField`（`useLabelAsPlaceholder`） | 唯一需要输入的一行，单独成卡 |
 * | 聚合 / 提醒时效 | `ArrowPreference`（当前值进 `summary`） | 可点开选，箭头与设置页其它选择项一致 |
 * | 带周期的取值项 | `ArrowPreference`（周期进 `summary`） | 同上，点整行选周期 |
 * | 带数值的取值项 | `BasicComponent` + 行内 `TextField` | 行本身不跳转，行内只有一个输入框 |
 * | 删除一个取值项 | `IconButton`（`AppIcons.Close`） | 触控目标够大，不再是一颗裸字符 ✕ |
 * | 添加取值项 | `BasicComponent`（主色 + 加号） | 「这是动作」由颜色与图标示意 |
 *
 * ## 弹层锚点为什么每行一份
 *
 * 锚定菜单按触发控件的 `boundsInWindow()` 定位（见 ui/AppDialog.kt）。3.1.x 用一个
 * 共享变量接所有控件的回调，最后布局完的那个就是锚点 —— 点「聚合」弹出来的菜单会
 * 贴在「添加取值项」那行上。现在每个可弹菜单的控件自己 `remember` 一份。
 */
@Composable
internal fun QuickConfigEditScreen(schemeId: String) {
    val scrollState = rememberScrollState()

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
            SchemeNameField(
                schemeId = scheme.id,
                initial = scheme.name,
                onRename = { text -> update { it.copy(name = text, preview = previewOf(it, termNames, upLabel, downLabel)) } },
            )
        }

        // ---- 两个槽 ----
        SlotEditor(
            title = stringResource(R.string.quick_slot_up),
            schemeId = scheme.id,
            isUp = true,
            slot = scheme.up,
            termNames = termNames,
            aggTexts = aggTexts,
            onChanged = { slot ->
                update { s ->
                    s.copy(up = slot, preview = previewOf(s.copy(up = slot), termNames, upLabel, downLabel))
                }
            },
        )
        SlotEditor(
            title = stringResource(R.string.quick_slot_down),
            schemeId = scheme.id,
            isUp = false,
            slot = scheme.down,
            termNames = termNames,
            aggTexts = aggTexts,
            onChanged = { slot ->
                update { s ->
                    s.copy(down = slot, preview = previewOf(s.copy(down = slot), termNames, upLabel, downLabel))
                }
            },
        )

        // ---- 提醒时效 ----
        // 不放 SmallTitle：行标题就叫「提醒时效」，分组标题再来一遍是同一句话说两次
        Card(modifier = Modifier.cardPadding()) {
            ValidityRow(
                validity = scheme.validity,
                texts = validityTexts,
                onPick = { which ->
                    val v = when (which) {
                        1 -> QuickConfigStore.VALIDITY_ONCE
                        2 -> QuickConfigStore.VALIDITY_LONG
                        else -> null
                    }
                    update { it.copy(validity = v) }
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
            val dialogs = LocalDialogController.current
            ArrowPreference(
                title = deleteTitle,
                summary = if (deletable) null else stringResource(R.string.quick_delete_last_hint),
                enabled = deletable,
                onClick = {
                    dialogs?.showConfirm(
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
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

// ------------------------------------------------------------ 名称

@Composable
private fun SchemeNameField(
    schemeId: String,
    initial: String,
    onRename: (String) -> Unit,
) {
    val state = remember(schemeId) { TextFieldState(initial) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .drop(1) // 初始值不回写（会把 preview 触发一次无谓重算）
            .collect { text -> if (text.isNotBlank()) onRename(text) }
    }
    TextField(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        label = stringResource(R.string.quick_field_name),
        useLabelAsPlaceholder = true,
        colors = TextFieldDefaults.textFieldColors(
            backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
        ),
    )
}

// ------------------------------------------------------------ 槽编辑

@Composable
private fun SlotEditor(
    title: String,
    schemeId: String,
    isUp: Boolean,
    slot: QuickConfigStore.Slot,
    termNames: Array<String>,
    aggTexts: List<String>,
    onChanged: (QuickConfigStore.Slot) -> Unit,
) {
    SmallTitle(title)
    Card(modifier = Modifier.cardPadding()) {
        // 聚合（多值才有意义，但始终显示 —— 一看就知道多个取值项怎么合并）
        AggregateRow(
            agg = slot.agg,
            texts = aggTexts,
            onPick = { which -> onChanged(slot.copy(agg = aggValueOf(which))) },
        )

        slot.terms.forEachIndexed { index, term ->
            TermRow(
                key = "$schemeId-$isUp-$index-${term.source}",
                term = term,
                name = termNames.getOrElse(QuickConfigCatalog.index(term.source)) { term.source },
                onParam = { newParam ->
                    onChanged(
                        slot.copy(
                            terms = slot.terms.mapIndexed { i, t ->
                                if (i == index) t.copy(param = newParam) else t
                            },
                        ),
                    )
                },
                onDelete = { onChanged(slot.copy(terms = slot.terms.filterIndexed { i, _ -> i != index })) },
            )
        }

        // 添加取值项（目录平铺，顺序 = 分组顺序）
        AddTermRow(
            names = QuickConfigCatalog.ALL.map { spec ->
                termNames.getOrElse(QuickConfigCatalog.index(spec.id)) { spec.id }
            },
            onPick = { which ->
                val picked = QuickConfigCatalog.ALL[which]
                // 已有同项就不再重复添加
                if (!slot.terms.any { it.source == picked.id }) {
                    val param = picked.params.associate { p ->
                        p.key to when {
                            p.periodChoices.isNotEmpty() -> p.periodChoices.first()
                            p.key == QuickConfigCatalog.PARAM_PCT -> "5"
                            else -> ""
                        }
                    }
                    onChanged(slot.copy(terms = slot.terms + QuickConfigStore.Term(picked.id, param)))
                }
            },
        )
    }
}

/** 聚合方式：点整行弹选择 */
@Composable
private fun AggregateRow(
    agg: String,
    texts: List<String>,
    onPick: (Int) -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current
    ArrowPreference(
        title = stringResource(R.string.quick_field_agg),
        summary = texts[aggIndexOf(agg)],
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
        onClick = {
            dialogs?.showChoice(
                ChoiceDialogSpec(items = texts, selectedIndex = aggIndexOf(agg), onSelect = onPick, anchor = anchor),
            )
        },
    )
}

/**
 * 一个取值项 —— 三种形态按参数类型分：
 *
 *  - 周期（`period`）：整行可点，点开选周期；
 *  - 百分比 / 固定价（`pct` / `value`）：行内一个数字输入框；
 *  - 无参数（如「最新价」）：只有名字和一颗删除。
 */
@Composable
private fun TermRow(
    key: String,
    term: QuickConfigStore.Term,
    name: String,
    onParam: (Map<String, String>) -> Unit,
    onDelete: () -> Unit,
) {
    val param = QuickConfigCatalog.byId(term.source)?.params?.firstOrNull()

    when {
        // 周期：整行是一个选择项，当前值进 summary
        param != null && param.periodChoices.isNotEmpty() -> {
            var anchor by remember { mutableStateOf<Rect?>(null) }
            val dialogs = LocalDialogController.current
            val current = term.param[param.key] ?: param.periodChoices.first()
            val choices = param.periodChoices.map { stringResource(R.string.quick_param_period_fmt, it) }
            ArrowPreference(
                title = name,
                summary = stringResource(R.string.quick_param_period_fmt, current),
                modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
                endActions = { RemoveTermButton(onDelete) },
                onClick = {
                    dialogs?.showChoice(
                        ChoiceDialogSpec(
                            items = choices,
                            selectedIndex = param.periodChoices.indexOf(current),
                            onSelect = { which -> onParam(term.param + (param.key to param.periodChoices[which])) },
                            anchor = anchor,
                        ),
                    )
                },
            )
        }

        // 百分比 / 固定价：行内一个数字输入框
        param != null && (
            param.key == QuickConfigCatalog.PARAM_PCT ||
                param.key == QuickConfigCatalog.PARAM_VALUE
            ) -> {
            BasicComponent(
                title = name,
                endActions = {
                    NumberField(
                        key = key,
                        param = param,
                        initial = term.param[param.key] ?: "",
                        onChanged = { text -> onParam(term.param + (param.key to text)) },
                    )
                    RemoveTermButton(onDelete)
                },
            )
        }

        // 无参数项（也兜住以后新增的参数类型：至少名字和删除还在）
        else -> BasicComponent(
            title = name,
            endActions = { RemoveTermButton(onDelete) },
        )
    }
}

/**
 * 行内的数字输入框。
 *
 * `insideMargin` 收窄到 10×6dp —— 默认值 16×16dp 会把 88dp 宽的框挤得只剩五十多 dp，
 * 数字和光标贴边。只接受**合法**的值才回写，与 3.1.x 一致（写半截会让方案解析失败）。
 */
@Composable
private fun NumberField(
    key: String,
    param: QuickConfigCatalog.ParamSpec,
    initial: String,
    onChanged: (String) -> Unit,
) {
    val state = remember(key) { TextFieldState(initial) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .drop(1)
            .collect { text ->
                val v = text.toDoubleOrNull()
                val valid = if (v == null) {
                    false
                } else if (param.key == QuickConfigCatalog.PARAM_PCT) {
                    v >= param.pctMin && v <= param.pctMax
                } else {
                    v > 0.0
                }
                if (valid) onChanged(text)
            }
    }
    TextField(
        state = state,
        modifier = Modifier.width(88.dp),
        label = if (param.key == QuickConfigCatalog.PARAM_PCT) "%" else "",
        useLabelAsPlaceholder = true,
        cornerRadius = 10.dp,
        insideMargin = DpSize(10.dp, 6.dp),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = TextFieldDefaults.textFieldColors(
            backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
        ),
    )
}

/** 删掉一个取值项 */
@Composable
private fun RemoveTermButton(onDelete: () -> Unit) {
    IconButton(onClick = onDelete) {
        Icon(
            imageVector = AppIcons.Close,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}

/** 添加取值项：主色 + 加号，点整行弹目录 */
@Composable
private fun AddTermRow(
    names: List<String>,
    onPick: (Int) -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current
    BasicComponent(
        title = stringResource(R.string.quick_add_term),
        titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.primary),
        startAction = {
            Icon(
                imageVector = AppIcons.Add,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
            )
        },
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
        onClick = {
            dialogs?.showChoice(
                ChoiceDialogSpec(items = names, selectedIndex = -1, onSelect = onPick, anchor = anchor),
            )
        },
    )
}

/** 提醒时效 */
@Composable
private fun ValidityRow(
    validity: String?,
    texts: List<String>,
    onPick: (Int) -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current
    ArrowPreference(
        title = stringResource(R.string.quick_field_validity),
        summary = texts[validityIndexOf(validity)],
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
        onClick = {
            dialogs?.showChoice(
                ChoiceDialogSpec(
                    items = texts,
                    selectedIndex = validityIndexOf(validity),
                    onSelect = onPick,
                    anchor = anchor,
                ),
            )
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
