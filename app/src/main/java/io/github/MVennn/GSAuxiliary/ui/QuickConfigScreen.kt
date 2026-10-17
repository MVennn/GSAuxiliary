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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.QuickConfigCatalog
import io.github.MVennn.GSAuxiliary.core.QuickConfigStore
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * **上一个默认方案**的 id —— 只活在模块 App 进程里、不落盘（3.1.1）。
 *
 * 「设为默认」在编辑页是一个开关，用户把它**关掉**时得知道默认该交给谁：交回上一个
 * 默认方案最符合直觉（文档里叫「恢复之前的默认值」）。进程重启后这个记忆就没了，
 * 那种情况下退化成「列表里第一个别的方案」—— 无论哪条路，都保证永远有一套默认。
 */
internal var lastDefaultId: String? = null

/** 新建方案那一行的展开动画时长（也是「动画期间保留 justAdded 标记」的时长） */
private const val SchemeAppearMillis = 260

/** 删除方案那一行的收起动画时长（真正写配置以动画收完为准，不靠这个时长对齐） */
private const val SchemeRemoveMillis = 220

/**
 * 「一键配置」的方案列表页（二级页）。
 *
 * 方案就是「点一下配置按钮，涨到 / 跌到各填什么」的取值组合；这一页管增删改排序
 * 与设默认（§6.2）。**每改一下立即写回 remote preferences**（与其它二级页一致，
 * 不设「保存」按钮 —— 少一个状态机，也避免改完忘存）。
 *
 * 交互：
 *  - 点行 → 进编辑页（[QuickConfigEditScreen]），行尾的箭头由组件自带；
 *  - 行尾 ⋮ → 锚定菜单（设为默认 / 上移 / 下移 / 删除方案）；
 *  - 「新建方案」→ 反弹 ±5% 的新方案，**带一段展开动画**落进列表。
 *
 * ## 取值项清单与脚注都不摆在这一页
 *
 * 3.1.x 这一页底部有一张「取值项说明」卡，把 §4.2 目录里的 18 个取值项按分组
 * 全列了一遍 —— 六个组名 + 十八行，比上面的方案列表还长，而那些名字在编辑页
 * 「添加取值项」的弹层里本来就能看到，属于同一信息摆两处；后来补的那句口径脚注
 * 同样被要求撤掉。这一页现在只回答「有哪些方案」。
 */
@Composable
internal fun QuickConfigScreen(onEdit: (String) -> Unit) {
    val scrollState = rememberScrollState()

    // 配置 JSON → 结构化方案；term 名称（本地化）按下标取
    val json by rememberStringPref(ConfigKeys.AISTARE_QUICK_CONFIG, ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG)
    val data = remember(json) { QuickConfigStore.decode(json) }

    // 菜单文案在这里解析（composable 里才拿得到资源），动作在回调里按序号分发
    val menuTexts = listOf(
        stringResource(R.string.quick_menu_set_default),
        stringResource(R.string.quick_menu_move_up),
        stringResource(R.string.quick_menu_move_down),
        stringResource(R.string.quick_menu_delete),
    )
    val namePrefix = stringResource(R.string.quick_new_scheme_name_prefix)

    // 刚新建的那一行：只为它播一次展开动画（见下方 AnimatedVisibility 的注释）
    var justAdded by remember { mutableStateOf<String?>(null) }
    // 正在播「删除」动画的那一行：动画播完才真正写配置（见下方 LaunchedEffect）
    var removing by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.quick_section_schemes))
        Card(modifier = Modifier.cardPadding()) {
            data.schemes.forEachIndexed { index, scheme ->
                // 每一行按 **id** 记身份（`key`），不是按「第几个」。
                //
                // 少了这层 `key`，删掉中间一行后 Compose 会按位置复用节点：原来第 3 行
                // 的节点正好落进第 2 行那个 `AnimatedVisibility` 里，而那一个正从「可见」
                // 往「不可见」走 —— `visible` 一翻回 true，它就把自己剩下的过渡反着播一遍，
                // 也就是补一次展开动画。用户看到的就是「删掉的那行收起来，下面那行跟着
                // 跳一下」。按 id 记身份之后，第 3 行还是第 3 行，谁也接不了谁的位置。
                key(scheme.id) {
                    val row: @Composable () -> Unit = {
                        SchemeRow(
                            name = scheme.name,
                            preview = scheme.preview,
                            isDefault = scheme.id == data.defaultId,
                            menuTexts = menuTexts,
                            // 点了不会有任何变化的项置灰且不可点：已经是默认 / 已在第一位 /
                            // 已在最后一位 / 只剩这一套。原来这些项点了没反应，像菜单坏了。
                            disabledMenuIndices = buildSet {
                                if (scheme.id == data.defaultId) add(0)
                                if (index == 0) add(1)
                                if (index == data.schemes.size - 1) add(2)
                                if (data.schemes.size <= 1) add(3)
                            },
                            onClick = { onEdit(scheme.id) },
                            onMenu = { which ->
                                when (which) {
                                    0 -> {
                                        // 记下被顶替的那个默认方案：「设为默认」开关关掉时要交还给它
                                        lastDefaultId = data.defaultId.takeIf { it != scheme.id }
                                        val updated =
                                            data.schemes.map { it.copy(isDefault = it.id == scheme.id) }
                                        writeSchemes(data.copy(defaultId = scheme.id, schemes = updated))
                                    }
                                    // 上移 / 下移：把这一项从列表里**取出来再插到新位置**。
                                    // 3.1.x 写的是 `list[index-1] = data.schemes[index].also { list[index] = it }`
                                    // —— `also` 收到的是同一个对象，于是两个位置都变成「第二个方案」，
                                    // 第一个方案被覆盖掉（用户看到的就是「上移后第一个方案变成了第二个」）。
                                    1 -> if (index > 0) {
                                        val list = data.schemes.toMutableList()
                                        list.add(index - 1, list.removeAt(index))
                                        writeSchemes(data.copy(schemes = list))
                                    }
                                    2 -> if (index < data.schemes.size - 1) {
                                        val list = data.schemes.toMutableList()
                                        list.add(index + 1, list.removeAt(index))
                                        writeSchemes(data.copy(schemes = list))
                                    }
                                    // 删除：先让这一行收起来（动画），播完再写配置
                                    3 -> removing = scheme.id
                                }
                            },
                        )
                    }
                    // 每一行**都常驻一个 AnimatedVisibility**，出现 / 删除两种动画都由它播。
                    //
                    // 为什么不能按状态分三种分支各画一次：`AnimatedVisibility(visible = false)`
                    // 在**首次**挂载时是「直接不可见」，不会播退出动画 —— 分成
                    // 「normal / appearing / removing」三条分支的话，删除那一刻节点是新建的，
                    // 收缩动画根本不会跑（看着还是凭空消失）。常驻一个节点、只翻可见性
                    // 才有 true → false 这个过渡。
                    //
                    // 可见性用 [MutableTransitionState] 而不是 `visible =`：只有它能回答
                    // 「动画播完了没有」（`currentState` 在收尾那一帧翻到目标值）。删除的
                    // 时机就挂在 `currentState` 上，不再靠「睡 SchemeRemoveMillis 毫秒」——
                    // 那种对齐差一帧就会把节点提前移出组合，收缩动画被截断成「凭空消失」。
                    val visibleState = remember(scheme.id) {
                        MutableTransitionState(scheme.id != justAdded)
                    }
                    // 新建：false → true，播一次展开
                    LaunchedEffect(scheme.id) {
                        if (scheme.id == justAdded) {
                            visibleState.targetState = true
                            delay(SchemeAppearMillis.toLong())
                            if (justAdded == scheme.id) justAdded = null
                        }
                    }
                    // 删除：等这一行收完再写配置。
                    //
                    // 这个 effect 的 key 是 `Unit`（生命周期跟着行，跟着 `key(scheme.id)` 走），
                    // **不跟 `removing` 翻动** —— 否则连着删两行时，后一次请求会把前一次那个
                    // 还在等动画的协程取消掉，前一行就永远停在「已经收起来了，但还留在配置里」
                    // 的半死状态。
                    LaunchedEffect(Unit) {
                        snapshotFlow { removing == scheme.id }.first { it }
                        visibleState.targetState = false
                        // 上限兜底：动画被系统关掉 / 状态没推进时也要把方案删掉，
                        // 不能变成「点了删除没反应」
                        withTimeoutOrNull(SchemeRemoveMillis.toLong() + 300) {
                            snapshotFlow { visibleState.currentState }.first { !it }
                        }
                        deleteScheme(scheme.id)
                        removing = null
                    }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = expandVertically(tween(SchemeAppearMillis)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(SchemeRemoveMillis)) + fadeOut(tween(140)),
                    ) {
                        row()
                    }
                }
            }
            // 新建方案：与方案行同组，主色 + 加号图标说明「这是动作」
            BasicComponent(
                title = stringResource(R.string.quick_new_scheme),
                titleColor = BasicComponentDefaults.titleColor(
                    color = MiuixTheme.colorScheme.primary,
                ),
                startAction = {
                    TintedIcon(imageVector = AppIcons.Add, tint = MiuixTheme.colorScheme.primary)
                },
                endActions = { ArrowRightIcon() },
                onClick = {
                    val id = QuickConfigStore.newId(data.schemes)
                    val name = newSchemeName(data.schemes.map { it.name }, namePrefix)
                    writeSchemes(
                        data.copy(schemes = data.schemes + defaultNewScheme(id, name)),
                    )
                    justAdded = id
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/**
 * 一行方案：整行点击进编辑，行尾 ⋮ 弹菜单（组件自带右箭头示意「还有下一级」）。
 *
 * ## 锚点为什么是行内自己的状态
 *
 * 锚定菜单按 [Rect] 定位（见 ui/AppDialog.kt）。3.1.x 用一个共享变量接所有行的
 * `boundsInWindow`，谁最后布局完谁就是锚点 —— 点第一行的 ⋮，菜单会从**最后一行**
 * 的位置弹出来。所以每行各自 `remember` 一份锚点，菜单永远贴着被点的那颗按钮。
 */
@Composable
private fun SchemeRow(
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
            // 整簇显式垂直居中（4-项）。`endActions` 的 Row 默认 **Top** 对齐：
            // ⋮ 在自己的 IconButton 里居中、箭头却贴在顶边，两者中心
            // 差出十几 dp —— 上机就是「箭头对不上三个点」（实测 ⋮ 511.5 / › 477.0）。
            // 收进一个 `align(CenterVertically)` 的 Row，三者共用同一条中线。
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isDefault) {
                    DefaultBadge()
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(
                    onClick = {
                        dialogs?.showChoice(
                            ChoiceDialogSpec(
                                items = menuTexts,
                                selectedIndex = -1, // 动作菜单，无选中态
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
                // 右箭头放在最后：这一行点进去是**三级页**，朝右才是对的语义
                ArrowRightIcon()
            }
        },
        onClick = onClick,
    )
}

/** 「默认」小徽标（垂直居中由调用方那个 `align(CenterVertically)` 的 Row 统一负责） */
@Composable
private fun DefaultBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.quick_default_badge),
            color = MiuixTheme.colorScheme.primary,
            fontSize = 10.sp,
        )
    }
}

/**
 * 删除方案：只剩一个时忽略（§6.3「必须有默认方案可用」）。
 *
 * 这里**重新读一次配置**而不是用调用点捕获的 `data`：删除动作排在收起动画之后执行，
 * 那一瞬间拿到的快照必须是最新的。同时兜住「过滤后为空」的极端情况（老版本写坏的
 * 配置里同一 id 出现两次时会发生，`first()` 会直接抛 NoSuchElementException）。
 */
private fun deleteScheme(id: String) {
    val data = QuickConfigStore.decode(
        ModulePreferences.getString(ConfigKeys.AISTARE_QUICK_CONFIG, ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG),
    )
    if (data.schemes.size <= 1) return
    val rest = data.schemes.filter { it.id != id }
    if (rest.isEmpty()) return
    val newDefault = data.defaultId.takeIf { def -> rest.any { it.id == def } }
        ?: rest.first().id
    writeSchemes(
        QuickConfigStore.Data(
            defaultId = newDefault,
            schemes = rest.map { if (it.id == newDefault) it.copy(isDefault = true) else it },
        ),
    )
}

/** 方案写回 remote preferences（目标进程立刻可见） */
internal fun writeSchemes(data: QuickConfigStore.Data) {
    ModulePreferences.edit { putString(ConfigKeys.AISTARE_QUICK_CONFIG, QuickConfigStore.encode(data)) }
}

/**
 * 新建方案的默认名字：`前缀 + 序号`，序号取**第一个没被占用的**正整数。
 *
 * 删除中间某几个之后再加，会补进空出来的序号（1、3 存在 → 下一个是 2），
 * 不会出现两个同名方案；用户改名后也可能腾出序号，同样会被复用。
 */
private fun newSchemeName(existingNames: List<String>, prefix: String): String {
    val taken = existingNames.toHashSet()
    var n = 1
    while ("$prefix$n" in taken) n++
    return "$prefix$n"
}

/** 新建方案的出厂形态：反弹 ±5%（最快有意义的组合），不设默认 */
private fun defaultNewScheme(id: String, name: String): QuickConfigStore.Scheme =
    QuickConfigStore.Scheme(
        id = id,
        name = name,
        up = QuickConfigStore.Slot(
            agg = QuickConfigStore.AGG_MAX,
            terms = listOf(QuickConfigStore.Term("upPct", mapOf(QuickConfigCatalog.PARAM_PCT to "5"))),
        ),
        down = QuickConfigStore.Slot(
            agg = QuickConfigStore.AGG_MIN,
            terms = listOf(QuickConfigStore.Term("downPct", mapOf(QuickConfigCatalog.PARAM_PCT to "5"))),
        ),
        validity = null,
        isDefault = false,
        preview = "",
    )
