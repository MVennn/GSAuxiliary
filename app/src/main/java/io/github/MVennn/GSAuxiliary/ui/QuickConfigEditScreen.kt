package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.QuickConfigCatalog
import io.github.MVennn.GSAuxiliary.core.QuickConfigStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 取值项增删动画时长（删除时以动画收完为准再写配置，不靠这个时长对齐） */
private const val TermAnimMillis = 200

/** 数字输入框的圆角：描边与 `TextField` 的 `cornerRadius` 必须用同一个值 */
private val NumberFieldCorner = 10.dp

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
 * | 聚合 / 提醒时效 | `BasicComponent`（当前值进 `summary` + 朝下箭头） | 可点开选，朝下表示原地弹窗 |
 * | 带周期的取值项 | `BasicComponent` + 行尾 `PeriodPill`（「30 日 ▾」胶囊） | 形态上就是个下拉选择，点整行弹周期目录 |
 * | 带数值的取值项 | `BasicComponent` + 行内 `TextField` | 行本身不跳转，行内只有一个输入框 |
 * | 删除一个取值项 | 40dp 触控区 + `AppIcons.Close`（14dp 细描边） | 视觉轻、目标够大；整簇垂直居中才与箭头齐 |
 * | 添加取值项 | `BasicComponent`（主色 + 加号） | 「这是动作」由颜色与图标示意 |
 *
 * ## 弹层锚点为什么每行一份
 *
 * 锚定菜单按触发控件的 `boundsInWindow()` 定位（见 ui/AppDialog.kt）。3.1.x 用一个
 * 共享变量接所有控件的回调，最后布局完的那个就是锚点 —— 点「聚合」弹出来的菜单会
 * 贴在「添加取值项」那行上。现在每个可弹菜单的控件自己 `remember` 一份。
 */
@Composable
internal fun QuickConfigEditScreen(schemeId: String, onDeleted: () -> Unit) {
    val scrollState = rememberScrollState()

    val json by rememberStringPref(ConfigKeys.AISTARE_QUICK_CONFIG, ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG)
    val data = remember(json) { QuickConfigStore.decode(json) }
    val liveScheme = remember(json, schemeId) { data.schemes.firstOrNull { it.id == schemeId } }
    // 删掉方案之后这一层还要再滑走 300ms，而那段时间 `liveScheme` 已经是 null ——
    // 直接渲染空内容会让整页先空掉（深色主题下就是一片纯黑）再滑出去，用户看到的是
    // 「黑屏闪一下，才退回二级页」。留一份**最后非空**的快照：退出动画期间照常画原来
    // 那一页，滑走的是一张完整页面，二级页在下面等着。
    var lastScheme by remember(schemeId) { mutableStateOf(liveScheme) }
    if (liveScheme != null) lastScheme = liveScheme
    val scheme = liveScheme ?: lastScheme
    /** 这一页对应的方案还在不在 —— 不在时只许看，不许再写配置 */
    val alive = liveScheme != null

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
        // 从来没有过这个方案（例如在别处删掉后才进来）：安静回方案列表，
        // 不要停在一张空白的三级页上。
        LaunchedEffect(schemeId) { onDeleted() }
        Column(Modifier.fillMaxSize()) { Spacer(Modifier.height(24.dp)) }
        return
    }

    /** 在整份数据上改当前方案并写回（保持 isDefault 唯一） */
    fun update(transform: (QuickConfigStore.Scheme) -> QuickConfigStore.Scheme) {
        // 方案已经删了、页面正在滑走：这一帧的任何编辑都不该落库
        // （否则会根据一份已经没有它的数据重算默认方案）
        if (!alive) return
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
        // 自带一个分组标题（3.1.1 用户反馈：光一张裸卡夹在「最新跌到」卡后面，
        // 看着像跌到槽的一部分）。标题写「提醒」而不是「提醒时效」—— 行标题就是
        // 「提醒时效」，逐字重复等于同一句话说两遍。
        SmallTitle(stringResource(R.string.quick_section_alert))
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
            // 「设为默认」可以开、也可以关（3.1.1 用户反馈原来关不掉）。
            //  - 开着：把它设为默认，同时记住**被顶替的那个默认方案**；
            //  - 关掉：默认位置交回上一个默认方案；它已经不在了（被删/被改名）就取
            //    列表里第一个别的方案 —— 无论如何永远有一套默认（§6.3）。
            // 只剩一套方案时它就是默认、也没有别的可交，开关**置灰不可点**。
            SwitchPreference(
                checked = scheme.isDefault,
                enabled = alive && data.schemes.size > 1,
                onCheckedChange = { v ->
                    val target = if (v) {
                        lastDefaultId = data.defaultId.takeIf { it != schemeId }
                        schemeId
                    } else {
                        lastDefaultId
                            ?.takeIf { last -> last != schemeId && data.schemes.any { it.id == last } }
                            ?: data.schemes.firstOrNull { it.id != schemeId }?.id
                            ?: schemeId
                    }
                    val updated = data.schemes.map { it.copy(isDefault = it.id == target) }
                    writeSchemes(data.copy(defaultId = target, schemes = updated))
                },
                title = stringResource(R.string.quick_set_default),
            )
            val canDelete = data.schemes.size > 1
            // 弹窗文案在 composable 作用域先解析（lambda 里调不了 stringResource）
            val deleteTitle = stringResource(R.string.quick_delete_scheme)
            val deleteConfirm = stringResource(R.string.quick_delete_confirm)
            val confirmText = stringResource(R.string.action_confirm)
            val cancelText = stringResource(R.string.action_cancel)
            val dialogs = LocalDialogController.current
            BasicComponent(
                title = deleteTitle,
                summary = if (canDelete) null else stringResource(R.string.quick_delete_last_hint),
                enabled = canDelete && alive,
                endActions = { ArrowRightIcon(canDelete && alive) },
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
                                // 删完就回方案列表（3.1.1 用户反馈：原来停在空白的三级页）
                                onDeleted()
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
    // 刚添加的那一项：只为它播一次展开动画
    var justAddedKey by remember { mutableStateOf<String?>(null) }

    /** 取值项的行标识：删掉中间某一项后，剩下的项 index 会变，所以把 source 也带上 */
    fun keyOf(index: Int, term: QuickConfigStore.Term) = "$schemeId-$isUp-$index-${term.source}"

    SmallTitle(title)
    Card(modifier = Modifier.cardPadding()) {
        // 聚合（多值才有意义，但始终显示 —— 一看就知道多个取值项怎么合并）
        AggregateRow(
            agg = slot.agg,
            texts = aggTexts,
            onPick = { which -> onChanged(slot.copy(agg = aggValueOf(which))) },
        )

        slot.terms.forEachIndexed { index, term ->
            // 每一行按**行标识**记身份（`key`），别让下一行接替被删那行的节点 ——
            // 位置复用会让下面那行住进正在收起的那个 `AnimatedVisibility` 里，可见性
            // 一翻回 true 就把过渡反着播一遍（用户看到的「删一项，下面那项跳一下」）。
            // 与方案列表同一个根因，改法也一样。
            key(keyOf(index, term)) {
                TermRow(
                    rowKey = keyOf(index, term),
                    term = term,
                    name = termNames.getOrElse(QuickConfigCatalog.index(term.source)) { term.source },
                    // 一个槽至少留一个取值项：空槽写进 JSON 之后会被
                    // `QuickConfigStore.decodeSlot` 当成坏数据丢弃（连带整条方案），
                    // 于是三级页会「自己变空 / 跳走」—— 用户看到的就是删一下就崩了。
                    // 只剩一项时**整颗 ✕ 都不画**（不留灰按钮占位）。
                    deletable = slot.terms.size > 1,
                    justAdded = keyOf(index, term) == justAddedKey,
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
                    val added = slot.terms + QuickConfigStore.Term(picked.id, param)
                    onChanged(slot.copy(terms = added))
                    justAddedKey = keyOf(added.size - 1, added.last())
                }
            },
        )
    }

    // 动画播完把标记清掉，免得这一行以后被重组时又播一次
    LaunchedEffect(justAddedKey) {
        if (justAddedKey != null) {
            delay(TermAnimMillis.toLong())
            justAddedKey = null
        }
    }
}

/** 聚合方式：点整行弹选择（箭头朝下，表示原地弹窗而不是进下一级） */
@Composable
private fun AggregateRow(
    agg: String,
    texts: List<String>,
    onPick: (Int) -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current
    BasicComponent(
        title = stringResource(R.string.quick_field_agg),
        summary = texts[aggIndexOf(agg)],
        endActions = { DownArrowIcon() },
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
        onClick = {
            dialogs?.showChoice(
                ChoiceDialogSpec(items = texts, selectedIndex = aggIndexOf(agg), onSelect = onPick, anchor = anchor),
            )
        },
    )
}

/**
 * 一个取值项 —— 参数控件在右、**删除键永远是最右边那一个**。
 *
 * ## ✕ 为什么必须贴着行尾
 *
 * 3.1.1 之前，带周期的项用 `ArrowPreference`（Miuix 会在 `endActions` **之后**再补一颗
 * 朝右的箭头），于是那一行的 ✕ 被箭头挤到了左边，跟「涨停价」「最新价」这些行的 ✕
 * 差着一个箭头的宽度（用户看到的「X 不对齐」）。现在所有取值项行都用 `BasicComponent`：
 * 右端固定是「参数控件 → ✕」，✕ 一律落在同一列上。
 *
 * 周期的当前值 + 朝下的小箭头也放在这里（不再借用组件自带箭头）—— 朝下表示
 * 「点开就地选一个值」，而不是「进入下一级页面」。
 */
@Composable
private fun TermRow(
    rowKey: String,
    term: QuickConfigStore.Term,
    name: String,
    deletable: Boolean,
    justAdded: Boolean,
    onParam: (Map<String, String>) -> Unit,
    onDelete: () -> Unit,
) {
    val param = QuickConfigCatalog.byId(term.source)?.params?.firstOrNull()

    // 出现 / 消失共用一条可见性状态机。用 [MutableTransitionState] 而不是 `visible =`：
    // 删除要「收完再写配置」，而「收完了没有」只有 `currentState` 说得准 —— 靠
    // `delay(TermAnimMillis)` 对齐差一帧就会把节点提前移出组合，收起动画被截断。
    val visibleState = remember(rowKey) { MutableTransitionState(!justAdded) }
    // 新增动画：只有刚添加的那一行从 0 高度展开
    LaunchedEffect(rowKey) { if (justAdded) visibleState.targetState = true }

    // 删除动画：点了 ✕ **先收起来再写配置**（直接删是「凭空消失」，看不见过渡）
    var removing by remember(rowKey) { mutableStateOf(false) }
    LaunchedEffect(removing) {
        if (!removing) return@LaunchedEffect
        visibleState.targetState = false
        // 上限兜底：动画被系统关掉 / 状态没推进时也要把这一项删掉，别成「点了没反应」
        withTimeoutOrNull(TermAnimMillis.toLong() + 300) {
            snapshotFlow { visibleState.currentState }.first { !it }
        }
        onDelete()
    }

    val dialogs = LocalDialogController.current
    var anchor by remember { mutableStateOf<Rect?>(null) }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = expandVertically(tween(TermAnimMillis)) + fadeIn(tween(160)),
        exit = shrinkVertically(tween(TermAnimMillis)) + fadeOut(tween(140)),
    ) {
        when {
            // 周期：整行可点，右端是「30 日 ▾」胶囊（点开就地选周期）
            param != null && param.periodChoices.isNotEmpty() -> {
                val current = term.param[param.key] ?: param.periodChoices.first()
                val choices = param.periodChoices.map { stringResource(R.string.quick_param_period_fmt, it) }
                BasicComponent(
                    title = name,
                    endActions = {
                        // 整簇显式垂直居中：`endActions` 的 Row 默认 **Top** 对齐，不这样做
                        // 40dp 触控区的 ✕ 会比那颗细箭头低半格（用户看到的「× 与箭头
                        // 没对齐」）。居中后两者共用同一条中线。
                        Row(
                            modifier = Modifier.align(Alignment.CenterVertically),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PeriodPill(current)
                            if (deletable) RemoveTermButton { removing = true }
                        }
                    },
                    modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
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
                        Row(
                            modifier = Modifier.align(Alignment.CenterVertically),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NumberField(
                                key = rowKey,
                                param = param,
                                initial = term.param[param.key] ?: "",
                                onChanged = { text -> onParam(term.param + (param.key to text)) },
                            )
                            if (deletable) RemoveTermButton { removing = true }
                        }
                    },
                )
            }

            // 无参数项（也兜住以后新增的参数类型：至少名字和删除还在）
            else -> BasicComponent(
                title = name,
                endActions = {
                    if (deletable) {
                        RemoveTermButton(Modifier.align(Alignment.CenterVertically)) { removing = true }
                    }
                },
            )
        }
    }
}

/**
 * 行内的数字输入框。
 *
 * `insideMargin` 收窄到 10×6dp —— 默认值 16×16dp 会把 88dp 宽的框挤得只剩五十多 dp，
 * 数字和光标贴边。只接受**合法**的值才回写，与 3.1.x 一致（写半截会让方案解析失败）。
 *
 * 外观上加了**一圈常驻描边**：框里的底色与卡片同色，不加边就看不出这里能点、能输。
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
        modifier = Modifier
            .width(88.dp)
            .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(NumberFieldCorner)),
        label = if (param.key == QuickConfigCatalog.PARAM_PCT) "%" else "",
        useLabelAsPlaceholder = true,
        cornerRadius = NumberFieldCorner,
        insideMargin = DpSize(10.dp, 6.dp),
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = TextFieldDefaults.textFieldColors(
            backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
        ),
    )
}

/**
 * 周期选择器 —— 行尾那颗「30 日 ▾」胶囊。
 *
 * 3.1.1 上机反馈「看着不像能选的东西」：原来是主色纯文字 + 一颗细箭头直接摆在行尾，
 * 跟一行普通说明没区别，「点哪里能弹目录」全靠猜。现在包成一颗**胶囊**（主色淡底、
 * 圆角、主色文字与箭头）：
 *
 *  - 形态上就是个下拉选择，与「点它会弹出一份目录」这件事对得上；
 *  - 箭头进胶囊、与文字同一条中线，不再是孤零零挂在右边的细箭头；
 *  - 取值仍是那 7 个周期、弹的还是同一个锚定菜单 —— **交互与数据一个没动**
 *    （热区仍是整行，点胶囊本身也在行内）。
 */
@Composable
private fun PeriodPill(period: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(start = 10.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.quick_param_period_fmt, period),
            color = MiuixTheme.colorScheme.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(4.dp))
        // 尺寸交给图标自己（10×6dp、1dp 笔画）。**不要**用 Modifier.size 缩它 ——
        // 一缩笔画跟着变细，就和其它行那颗 › 不是一套了（3.1.1 踩过的坑）。
        TintedIcon(
            imageVector = AppIcons.ArrowDown,
            tint = MiuixTheme.colorScheme.primary.copy(alpha = 0.8f),
        )
    }
}

/**
 * 删掉一个取值项（一个槽只剩一项时**整颗都不画**，见 [SlotEditor]）。
 *
 * 40dp 触控区里放一颗 14dp 的细描边叉（图标本身见 [AppIcons.Close]）：视觉上是一颗
 * 更轻的 ✕，手上仍是够大的目标。`modifier` 由调用方传 —— 单独成行时用它
 * `align(Alignment.CenterVertically)` 与相邻控件对齐。
 */
@Composable
private fun RemoveTermButton(
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onDelete),
        // 图标贴着右缘、再让出 2dp：16dp 的 ✕ 中心落在「距行右缘 10dp」这一列 ——
        // 与 20dp 宽、齐右缘的箭头**同一条中线**（用户反馈「✕ 对齐的是箭头左侧」：
        // 原来居中在 40dp 盒子里，中心比箭头左 10dp）。
        contentAlignment = Alignment.CenterEnd,
    ) {
        TintedIcon(
            imageVector = AppIcons.Close,
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            modifier = Modifier.padding(end = 2.dp),
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
            TintedIcon(imageVector = AppIcons.Add, tint = MiuixTheme.colorScheme.primary)
        },
        modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
        endActions = { ArrowRightIcon() },
        onClick = {
            dialogs?.showChoice(
                ChoiceDialogSpec(items = names, selectedIndex = -1, onSelect = onPick, anchor = anchor),
            )
        },
    )
}

/** 提醒时效：与其它弹窗类控件一致 —— 右端朝下箭头 */
@Composable
private fun ValidityRow(
    validity: String?,
    texts: List<String>,
    onPick: (Int) -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current
    BasicComponent(
        title = stringResource(R.string.quick_field_validity),
        summary = texts[validityIndexOf(validity)],
        endActions = { DownArrowIcon() },
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
