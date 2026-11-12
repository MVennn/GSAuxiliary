package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
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
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.MergeConfig
import io.github.MVennn.GSAuxiliary.core.MergeGroup
import io.github.MVennn.GSAuxiliary.core.StrategyMergeStore
import io.github.MVennn.GSAuxiliary.feature.stgmerge.StrategyComboCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 新建聚合组那一行的展开动画时长 */
private const val MergeGroupAppearMillis = 260

/** 删除聚合组那一行的收起动画时长 */
private const val MergeGroupRemoveMillis = 220

/**
 * 没写过配置时读到的默认值 —— 直接取 ConfigKeys 里的出厂值（空组列表的 v1 JSON）。
 *
 * 抽成常量的理由：二级页、三级页、功能页入口的状态句三处都要读同一条键，
 * 默认值各写一份，以后改格式必漏。
 */
internal const val DefaultMergeGroupsJson = ConfigKeys.DEFAULT_STG_MERGE_GROUPS

/**
 * 一个聚合组至少要有几个成员才真正算数。
 *
 * 只有 1 个成员的组等于没整合 —— 详情页上还是那条策略自己的股票，所以这是语义门槛，
 * 不是配置建议：设置页照它报状态，合并逻辑照它放行。若以后 core/StrategyMergeStore
 * 给出同名常量，改成引用它，别留两份。
 */
internal const val MergeMinMembers = 2

/**
 * 智能选股 · 我的策略整合 → 聚合组设置（二级页）。
 *
 * 与「一键配置」的方案列表页**同一套交互**（用户不用为两个功能学两种操作）：
 *
 * | 区域 | 内容 | 行为 |
 * |---|---|---|
 * | 组列表 | 一行一个组：标题 = 组名，副标题 = 成员数 + 状态句 | 点行进三级页编辑 |
 * | 行尾 | ⋮ + ›：⋮ 弹锚定菜单（重命名 / 上移 / 下移 / 启用或停用 / 复制 / 删除） | 菜单贴着被点的那颗按钮弹 |
 * | 底部 | 「新建聚合组」（主色 + AppIcons.Add） | 直接落一行「聚合组N」，不弹名字 |
 *
 * 新建不再问名字：方案页就是直接生成「新建方案N」再进去改，这里照抄 —— 每一步少一个
 * 弹层，用户点「新建」立刻看到那一行在列表里展开，再点进去改名字也是一样的两步。
 *
 * 数据只有 ConfigKeys.STG_MERGE_GROUPS 这一条 JSON（StrategyMergeStore 编解码），
 * 每次增删改后立即写回；总开关是另一条键 ConfigKeys.STG_MERGE，落在一级功能页上。
 */
@Composable
internal fun StrategyMergeScreen(onEdit: (String) -> Unit) {
    val scrollState = rememberScrollState()

    val json by rememberStringPref(ConfigKeys.STG_MERGE_GROUPS, DefaultMergeGroupsJson)
    val config = remember(json) { StrategyMergeStore.decode(json) }

    var justAdded by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }

    val limitText = stringResource(R.string.stg_merge_group_limit, StrategyMergeStore.MAX_GROUPS)
    val renameTitle = stringResource(R.string.stg_merge_group_rename_title)
    val renameMessage = stringResource(R.string.stg_merge_group_rename_message)
    val namePrefix = stringResource(R.string.stg_merge_group_new_name_prefix)
    val copySuffix = stringResource(R.string.stg_merge_group_copy_suffix)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.stg_merge_section_groups))
        Card(modifier = Modifier.cardPadding()) {
            // 空表不画占位句：没有组就是没有行，不写「先新建一个」这类引导文案
            config.groups.forEachIndexed { index, group ->
                key(group.id) {
                    // 改名、启停、排序都**重读一遍最新配置再写**（见 updateGroup / moveGroup）：
                    // 用这一帧捕获的 config 写回，会把弹层开着期间别处的改动抹掉。
                    val renameAction = groupNameDialogAction(
                        groups = config.groups,
                        selfId = group.id,
                        initial = group.name,
                        title = renameTitle,
                        message = renameMessage,
                    ) { name -> updateGroup(group.id) { it.copy(name = name) } }

                    val visibleState = remember(group.id) {
                        MutableTransitionState(group.id != justAdded)
                    }
                    LaunchedEffect(group.id) {
                        if (group.id == justAdded) {
                            visibleState.targetState = true
                            delay(MergeGroupAppearMillis.toLong())
                            if (justAdded == group.id) justAdded = null
                        }
                    }
                    LaunchedEffect(Unit) {
                        snapshotFlow { removing == group.id }.first { it }
                        visibleState.targetState = false
                        // 兜底：动画被系统关掉时 currentState 不推进，超时后照样真删
                        withTimeoutOrNull(MergeGroupRemoveMillis.toLong() + 300) {
                            snapshotFlow { visibleState.currentState }.first { !it }
                        }
                        deleteMergeGroup(group.id)
                        removing = null
                    }
                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = expandVertically(tween(MergeGroupAppearMillis)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(MergeGroupRemoveMillis)) + fadeOut(tween(140)),
                    ) {
                        MergeGroupRow(
                            group = group,
                            index = index,
                            count = config.groups.size,
                            onClick = { onEdit(group.id) },
                            onRename = renameAction,
                            onMove = { delta -> moveGroup(group.id, delta) },
                            onToggle = { updateGroup(group.id) { it.copy(enabled = !it.enabled) } },
                            canDuplicate = config.groups.size < StrategyMergeStore.MAX_GROUPS,
                            onDuplicate = {
                                // 复制出来的那一行也走「展开」动画，落点就在源组正下方
                                duplicateGroup(group.id, copySuffix)?.let { justAdded = it }
                            },
                            onDelete = { removing = group.id },
                        )
                    }
                }
            }

            // 到上限后不再落行，只在副标题上说明原因，点击无副作用
            val canAdd = config.groups.size < StrategyMergeStore.MAX_GROUPS
            BasicComponent(
                title = stringResource(R.string.stg_merge_group_new),
                summary = if (canAdd) null else limitText,
                titleColor = BasicComponentDefaults.titleColor(
                    color = MiuixTheme.colorScheme.primary,
                ),
                startAction = {
                    TintedIcon(imageVector = AppIcons.Add, tint = MiuixTheme.colorScheme.primary)
                },
                onClick = {
                    if (canAdd) {
                        val group = MergeGroup(
                            id = newGroupId(config.groups),
                            name = newGroupName(config.groups.map { it.name }, namePrefix),
                            enabled = true,
                            members = emptyList(),
                        )
                        writeMergeGroups(config.copy(groups = config.groups + group))
                        // 新行从收起态展开，避免整卡凭空长出一行
                        justAdded = group.id
                    }
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/**
 * 一行聚合组：整行进三级页编辑，行尾 ⋮ 弹菜单（重命名 / 上移 / 下移 / 启用或停用 / 复制 / 删除）。
 *
 * 副标题就是状态句（mergeGroupSummary）：成员数 + 生效 / 已停用 / 未生效，
 * 不进编辑页也知道这个组现在算不算数。
 *
 * ## 锚点为什么是行内自己的状态
 *
 * 锚定菜单按 [Rect] 定位（见 ui/AppDialog.kt）。用共享变量接所有行的 boundsInWindow 时，
 * 谁最后布局完谁就是锚点 —— 点第一行的 ⋮，菜单会从**最后一行**的位置弹出来。
 * 所以每行各自 remember 一份锚点，菜单永远贴着被点的那颗按钮。
 */
@Composable
private fun MergeGroupRow(
    group: MergeGroup,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onToggle: () -> Unit,
    canDuplicate: Boolean,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current
    val menuTexts = listOf(
        stringResource(R.string.stg_merge_menu_rename),
        stringResource(R.string.stg_merge_menu_move_up),
        stringResource(R.string.stg_merge_menu_move_down),
        if (group.enabled) stringResource(R.string.stg_merge_menu_disable)
        else stringResource(R.string.stg_merge_menu_enable),
        stringResource(R.string.stg_merge_menu_duplicate),
        stringResource(R.string.common_delete),
    )
    // 点了不会有任何变化的项置灰且不可点：已在第一位 / 已在最后一位 / 已到组数上限
    val disabledIndices = buildSet {
        if (index == 0) add(1)
        if (index == count - 1) add(2)
        if (!canDuplicate) add(4)
    }
    BasicComponent(
        title = group.name,
        summary = mergeGroupSummary(group),
        endActions = {
            // endActions 的 Row 默认 Top 对齐；不显式居中时 ⋮ 会比标题高半格
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        dialogs?.showChoice(
                            ChoiceDialogSpec(
                                items = menuTexts,
                                selectedIndex = -1, // 动作菜单，无选中态
                                onSelect = { which ->
                                    when (which) {
                                        0 -> onRename()
                                        1 -> onMove(-1)
                                        2 -> onMove(1)
                                        3 -> onToggle()
                                        4 -> onDuplicate()
                                        else -> onDelete()
                                    }
                                },
                                anchor = anchor,
                                disabledIndices = disabledIndices,
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

/**
 * 一个聚合组在目标进程里到底会排几条取数条目。
 *
 * 口径成员（「中盘 1.5-4」这类）本身不是一条宿主策略，它在取数时展开成
 * 「市值档 × 量比档」若干条（见 StrategyMergeFeature 组装 members 的那段），
 * 所以这里必须按展开后的条数算 —— 否则一个带 12 个组合的组会显示成「未生效」，
 * 而详情页其实已经聚合出来了。判据与目标进程保持一致（唯一实现在 Catalog）：
 *  - 填了 stId 的成员按普通成员算（用户真建了同名策略，优先按策略走）
 *  - 口径名认中文显示名与 ASCII key 两种写法
 *  - 普通成员组内按名称去重
 */
private fun mergeMemberCount(group: MergeGroup): Int {
    val expanded = StrategyComboCatalog.expandedCount(group.members)
    // 显示数取「展开条数」与「配置里的条数」的较大者：还没选档位的父标签（例如刚点进来的
    // 「30分钟MACD」）展不出条数，只看 expanded 会显示「0 个成员」而下面明明摆着一行，
    // 用户以为成员没加进去。生效门槛仍然只认 expanded（那种成员取不出股票）。
    return if (expanded > group.members.size) expanded else group.members.size
}

/**
 * 组行的状态句：「N 个成员 · 生效 / 已停用 / 未生效」。
 *
 * 生效门槛是 MergeMinMembers，按 [mergeMemberCount] 展开后的条数判：
 * 只有 1 条的组，详情页上还是那条策略自己的股票，
 * 所以在设置页就把「现在还算不上生效」说出来，别等用户去详情页发现没变化。
 */
@Composable
internal fun mergeGroupSummary(group: MergeGroup): String {
    val count = mergeMemberCount(group)
    // 门槛按「能取数的条数」判，不按显示数：待选档位的父标签展不出条数，
    // 拿显示数当门槛会让一个其实一条都取不到的组显示成「生效」。
    val usable = StrategyComboCatalog.expandedCount(group.members)
    val state = when {
        !group.enabled -> stringResource(R.string.stg_merge_group_state_off)
        usable < MergeMinMembers ->
            stringResource(R.string.stg_merge_group_state_pending, MergeMinMembers)
        else -> stringResource(R.string.stg_merge_group_state_on)
    }
    return stringResource(R.string.stg_merge_group_summary, count, state)
}

/**
 * 「组名」贴底输入弹层的点击动作（新建与改名共用）。
 *
 * 校验拦在弹层里（DESIGN-SYSTEM 5.6）：空串、与本机已有组重名都不许提交，弹层不关、红字提示。
 * 文案先在 composable 作用域取好 —— 弹层 lambda 里没有资源上下文。
 *
 * selfId 是改名时要排除的自己，新建传 null。
 */
@Composable
internal fun groupNameDialogAction(
    groups: List<MergeGroup>,
    selfId: String?,
    initial: String,
    title: String,
    message: String,
    onConfirm: (String) -> Unit,
): () -> Unit {
    val dialogs = LocalDialogController.current
    val hint = stringResource(R.string.stg_merge_group_name_hint)
    val confirmText = stringResource(R.string.action_confirm)
    val cancelText = stringResource(R.string.action_cancel)
    val emptyText = stringResource(R.string.stg_merge_group_name_required)
    val duplicateText = stringResource(R.string.stg_merge_group_name_duplicate)
    return {
        dialogs?.showTextInput(
            TextInputDialogSpec(
                title = title,
                message = message,
                hint = hint,
                initial = initial,
                confirmText = confirmText,
                cancelText = cancelText,
                validate = { input ->
                    val name = input.trim()
                    when {
                        name.isEmpty() -> emptyText
                        groups.any { it.id != selfId && it.name == name } -> duplicateText
                        else -> null
                    }
                },
                onConfirm = { input -> onConfirm(input.trim()) },
            ),
        )
    }
}

/**
 * 把整份聚合组配置写回 ConfigKeys.STG_MERGE_GROUPS。
 *
 * 读写都走这一条 JSON：二级页列表、三级页编辑、合并逻辑看到的永远是同一份；
 * 每次增删改后立即调用，不等「保存」按钮（本功能没有保存按钮）。
 */
internal fun writeMergeGroups(config: MergeConfig) {
    ModulePreferences.edit {
        putString(ConfigKeys.STG_MERGE_GROUPS, StrategyMergeStore.encode(config))
    }
}

/** 读一次**最新**配置（菜单动作都排在弹层关闭之后，不能拿调用点那一帧的快照写回） */
private fun readMergeConfig(): MergeConfig = StrategyMergeStore.decode(
    ModulePreferences.getString(ConfigKeys.STG_MERGE_GROUPS, DefaultMergeGroupsJson),
)

/** 改一个组：重读最新配置，只替换目标组那一项，其余原样保留 */
private fun updateGroup(groupId: String, transform: (MergeGroup) -> MergeGroup) {
    val latest = readMergeConfig()
    if (latest.groups.none { it.id == groupId }) return
    writeMergeGroups(
        latest.copy(groups = latest.groups.map { if (it.id == groupId) transform(it) else it }),
    )
}

/**
 * 上移 / 下移一个组：把这一项从列表里**取出来再插到新位置**。
 *
 * 不能写成 `list[index - 1] = list[index].also { list[index] = it }` —— `also` 收到的是
 * 同一个对象，两个位置都变成它，相邻那一条被覆盖掉（方案页当年就踩过这个坑）。
 */
private fun moveGroup(groupId: String, delta: Int) {
    val latest = readMergeConfig()
    val index = latest.groups.indexOfFirst { it.id == groupId }
    if (index < 0) return
    val to = index + delta
    if (to !in latest.groups.indices) return
    val list = latest.groups.toMutableList()
    list.add(to, list.removeAt(index))
    writeMergeGroups(latest.copy(groups = list))
}

/**
 * 删一个聚合组：重新读一次配置再写。
 *
 * 用调用点捕获的旧快照写回，会把期间别人的改动（比如刚添加的成员）一起覆盖掉，
 * 与「一键配置」的 deleteScheme 同一个理由。
 */
private fun deleteMergeGroup(groupId: String) {
    val latest = readMergeConfig()
    writeMergeGroups(latest.copy(groups = latest.groups.filterNot { it.id == groupId }))
}

/**
 * 复制一个聚合组：成员、成员顺序、启用状态原样带过来，名字加「副本」后缀。
 *
 * 三条约束：
 *  - 先重读最新配置再写（同 updateGroup / deleteMergeGroup 的理由，弹层开着期间别处可能改过）；
 *  - 副本插在源组**正下方**，不是追加到末尾 —— 连着建几个同类组时顺序才跟手上的思路对得上；
 *  - 组数到上限就不复制，返回 null。菜单项同时也被置灰，这里只是兜底。
 *
 * 返回新组 id（供调用点做展开动画），没复制则 null。
 */
private fun duplicateGroup(groupId: String, nameSuffix: String): String? {
    val latest = readMergeConfig()
    val src = latest.groups.firstOrNull { it.id == groupId } ?: return null
    if (latest.groups.size >= StrategyMergeStore.MAX_GROUPS) return null
    val copy = src.copy(
        id = newGroupId(latest.groups),
        name = copyGroupName(src.name, latest.groups.map { it.name }, nameSuffix),
        members = src.members.toList(),
    )
    val list = latest.groups.toMutableList()
    list.add(list.indexOfFirst { it.id == groupId } + 1, copy)
    writeMergeGroups(latest.copy(groups = list))
    return copy.id
}

/**
 * 复制出来的组名：「源名 + 后缀」，撞名就再补「 2」「 3」。
 *
 * 不套 [newGroupName] 那套「前缀 + 序号」：复制要让人一眼看出是从哪个组来的，
 * 顺序号反而把来源信息丢掉了。
 */
private fun copyGroupName(source: String, existingNames: List<String>, suffix: String): String {
    val taken = existingNames.toHashSet()
    val base = source + suffix
    if (base !in taken) return base
    var n = 2
    while ("$base $n" in taken) n++
    return "$base $n"
}

/**
 * 组 id：本机自增，取第一个没被占用的 groupN。
 *
 * id 只在本机配置里用（宿主侧按 stId 认策略），所以不要求全局唯一，只要求同一份配置里不重。
 */
private fun newGroupId(groups: List<MergeGroup>): String {
    val used = groups.mapTo(mutableSetOf()) { it.id }
    var n = groups.size + 1
    while ("group$n" in used) n++
    return "group$n"
}

/**
 * 新建组的默认名字：`前缀 + 序号`，序号取**第一个没被占用的**正整数。
 *
 * 与方案页的 newSchemeName 同一套规则：删掉中间几个之后再加，会补进空出来的序号
 * （1、3 存在 → 下一个是 2），不会出现两个同名组。
 */
private fun newGroupName(existingNames: List<String>, prefix: String): String {
    val taken = existingNames.toHashSet()
    var n = 1
    while ("$prefix$n" in taken) n++
    return "$prefix$n"
}
