package io.github.MVennn.GSAuxiliary.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.HostStgCatalog
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.MergeGroup
import io.github.MVennn.GSAuxiliary.core.MergeMember
import io.github.MVennn.GSAuxiliary.core.StrategyMergeStore
import io.github.MVennn.GSAuxiliary.feature.stgmerge.StrategyComboCatalog
import io.github.MVennn.GSAuxiliary.feature.stgmerge.StrategyLabelTable
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 智能选股 · 我的策略聚合 → 聚合组编辑（三级页）。
 *
 * 与「一键配置」的编辑页同构，三块：
 *
 * | 区域 | 内容 | 行为 |
 * |---|---|---|
 * | 聚合组 | 两行：组名（点行改名）+ 启用开关 | 开关一关，详情页就按宿主原策略显示 |
 * | 成员策略 | 一行一个成员：标题 = 策略名称，副标题 = 生效范围 | 行尾 ⋮：上移 / 下移 / 删除 |
 * | 底部 | 「添加成员」（主色 + AppIcons.Add） | 口径四条 + 手写名称，已加过的置灰 |
 *
 * 组名改的是本机配置里的显示名，不动宿主里的策略；成员以策略名称为识别键，
 * stId 由目标进程按名称自动补齐（空着也能用），组内名称不重复。
 * 成员少于 MergeMinMembers 个时，组名行副标题会写未生效。
 *
 * 数据与二级页共用 ConfigKeys.STG_MERGE_GROUPS 那一条 JSON，每次增删改后立即写回。
 *
 * @param groupId 要编辑的组 id；组被删掉（或配置被外部改没）时回调 [onDeleted] 退回二级页
 * @param condOnly 只画「组合维度」那一屏（四级页用的就是这个模式），不画组名 / 成员 / 导入段
 * @param condMemberKey 组合维度页要编辑的那条成员的名字（`cond:...`）；空串 = 新建一条
 * @param onOpenCond 点「新建组合」或某条组合成员时往第四层推进（带成员名，空串=新建）；
 *   为空则退回页内子视图（老行为，留作兜底）
 * @param onDone 四级页点「完成」或按返回时收掉自己（回三级页）
 */
@Composable
internal fun StrategyMergeEditScreen(
    groupId: String,
    onDeleted: () -> Unit,
    condOnly: Boolean = false,
    condMemberKey: String = "",
    onOpenCond: ((String) -> Unit)? = null,
    onDone: (() -> Unit)? = null,
) {
    val scrollState = rememberScrollState()

    val json by rememberStringPref(ConfigKeys.STG_MERGE_GROUPS, DefaultMergeGroupsJson)
    val config = remember(json) { StrategyMergeStore.decode(json) }
    val liveGroup = remember(json, groupId) {
        config.groups.firstOrNull { it.id == groupId }
    }
    // 退出动画期间配置里已经没有这个组了，兜住最后一帧，别让整页先空一下（深色下是黑屏闪）
    var lastGroup by remember(groupId) { mutableStateOf(liveGroup) }
    if (liveGroup != null) lastGroup = liveGroup
    val group = liveGroup ?: lastGroup
    val alive = liveGroup != null

    if (group == null) {
        LaunchedEffect(groupId) { onDeleted() }
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(24.dp))
        }
        return
    }

    /**
     * 只改当前这一个组，其余组原样保留。
     *
     * 每次都重新读一遍最新配置再写：编辑页停留期间若别处改了配置，
     * 用调用点捕获的旧快照写回会把那些改动抹掉。
     */
    fun update(transform: (MergeGroup) -> MergeGroup) {
        if (!alive) return
        val latest = StrategyMergeStore.decode(
            ModulePreferences.getString(ConfigKeys.STG_MERGE_GROUPS, DefaultMergeGroupsJson),
        )
        val index = latest.groups.indexOfFirst { it.id == groupId }
        if (index < 0) return
        val groups = latest.groups.toMutableList()
        groups[index] = transform(groups[index])
        writeMergeGroups(latest.copy(groups = groups))
    }

    val limitText = stringResource(
        R.string.stg_merge_member_limit,
        StrategyMergeStore.MAX_MEMBERS,
    )

    val renameAction = groupNameDialogAction(
        groups = config.groups,
        selfId = groupId,
        initial = group.name,
        title = stringResource(R.string.stg_merge_group_rename_title),
        message = stringResource(R.string.stg_merge_group_rename_message),
    ) { name -> update { it.copy(name = name) } }
    // stId 不进界面：留空就由目标进程按「策略名称」自动对上（见 StrategyStgIndex），
    // 判重也按名称 —— 名称才是用户能看见、能保证唯一的那个键。
    val dialogs = LocalDialogController.current

    // 菜单贴着行右缘弹出（与方案设置的「+ 添加取值项」同款），所以先把这一行的窗口坐标量下来
    var addAnchor by remember { mutableStateOf<Rect?>(null) }

    /**
     * 加一条标签成员：一条成员 = 宿主标签表里的一个标签，识别名 `label:<labelId>`。
     *
     * 取数时由目标进程现建一条只含这一个 labelId 的临时策略，取完即删 —— 与口径 / 自选条件
     * 成员同一条路（[StrategyComboCatalog.comboOfKey] 里认 `label:` 前缀）。
     * 为什么不做「自己填数字」：宿主接口只吃 labelId，档位是服务端切好的，填 15.5% 落不到请求上。
     *
     * 有档位的标签（「量比」「30分钟MACD」）先落成**待选档位**的占位成员，用户再在行尾 ⋮ 里
     * 选具体档 —— 父标签本身接不了数（实测 createStrategy 返回成功、成分股 0 只，
     * 见 [StrategyLabelTable.isParentOf]）。
     */
    fun addLabelMember(labelId: String) {
        val key = StrategyComboCatalog.LABEL_PREFIX + labelId
        update { current ->
            if (current.members.size >= StrategyMergeStore.MAX_MEMBERS) return@update current
            if (current.members.any { it.name == key }) return@update current
            current.copy(members = current.members + MergeMember(stId = "", name = key))
        }
    }

    /**
     * 「添加成员」直接铺开宿主标签全表：点一个标签就落一条成员，有档位的落成待选档位。
     *
     * 用户原话（2026-11-12）：「点添加成员的时候就直接进技术指标，技术指标点击以后就会新建一个成员，
     * 然后点那个成员右边三个点可以细分他的数值」。所以这里**不再**有中间菜单（自选条件 / 口径预设 /
     * 手写名称）。134 个标签平铺成一条长栏是找不动的，用 [ChoiceDialogSpec.groupHeaders] 按宿主自己的
     * 五个分组切段（技术指标在最前），与宿主「标签选股」面板同序。
     */
    fun openLabelGroups(controller: DialogController) {
        val items = ArrayList<String>()
        val headers = HashMap<Int, String>()
        val nodes = ArrayList<StrategyLabelTable.LabelNode>()
        for ((groupName, labels) in StrategyLabelTable.tree) {
            if (labels.isEmpty()) continue
            headers[items.size] = groupName
            for (node in labels) {
                nodes.add(node)
                val key = StrategyComboCatalog.LABEL_PREFIX + node.labelId
                val added = group.members.any { it.name == key } || node.bands.any { band ->
                    group.members.any { it.name == StrategyComboCatalog.LABEL_PREFIX + band.labelId }
                }
                items.add(
                    when {
                        added -> node.name + "  ✓"
                        node.hasBands -> node.name + "（" + node.bands.size + " 档）"
                        else -> node.name
                    },
                )
            }
        }
        controller.showChoice(
            ChoiceDialogSpec(
                items = items,
                selectedIndex = -1,
                onSelect = { i -> nodes.getOrNull(i)?.let { addLabelMember(it.labelId) } },
                anchor = addAnchor,
                groupHeaders = headers,
            ),
        )
    }

    /** 组里所有「组合成员」：一条 = 若干标签维度 × 各维若干档 */
    fun comboMembersOf(
        current: MergeGroup,
    ): List<Pair<MergeMember, StrategyComboCatalog.Condition>> =
        current.members.mapNotNull { m ->
            StrategyComboCatalog.conditionOfMember(m.name, m.stId)?.let { m to it }
        }

    /**
     * 组合维度页这一次要编辑的那条成员：按 [condMemberKey]（成员名）认，空串 = 新建。
     *
     * 一个组里可以有多条组合成员（「新建组合」就是再加一条），所以不能再拿「组里第一条」顶替，
     * 否则第二组、第三条都会被改到同一条上。
     */
    fun editingCondOf(current: MergeGroup): MergeMember? =
        if (condMemberKey.isEmpty()) {
            null
        } else {
            current.members.firstOrNull { it.name == condMemberKey }
        }

    /**
     * 组合维度草稿：进页面时把 [condMemberKey] 那条成员读出来，之后所有勾选只改这里。
     *
     * **不再边勾边写配置**（2026-11-12 用户报的「点一下组合成员就多出一条成员，还直接进了
     * 那条的编辑页」）：新建时点进来是空表，按「完成」才落一条；编辑时按「完成」才改，
     * 直接返回就是放弃。
     */
    var condDims by remember(groupId, condMemberKey) {
        mutableStateOf(
            editingCondOf(group)
                ?.let { StrategyComboCatalog.conditionOfMember(it.name, it.stId) }
                ?.dims
                .orEmpty(),
        )
    }

    /**
     * 是否在画「组合维度」那一屏。
     *
     * 正常编辑页永远是 false（组合成员是第四层路由，见 [onOpenCond]）；
     * 只有四级页把它初始化为 true，直接渲染那一屏。
     */
    var condEditing by remember(groupId) { mutableStateOf(condOnly) }

    /**
     * 某一维在宿主标签表里的**全部**档位 —— 选档菜单要列出全部，勾选态才表示这一维保留哪些。
     *
     * 没有档位的标签（「放量」）自成维度只有一档，这里返回它自己那一档。
     */
    fun allTiersOf(dim: StrategyComboCatalog.Dim): List<StrategyComboCatalog.Tier> {
        val bands = StrategyLabelTable.bandsOf(dim.id)
        if (bands.isEmpty()) return listOf(StrategyComboCatalog.Tier(dim.title, dim.id))
        return bands.map { StrategyComboCatalog.Tier(it.labelName, it.labelId) }
    }

    /**
     * 「完成」：把草稿落成一条成员（整张维度表 = 一条成员，展开成各维档位的笛卡尔积）。
     *
     * - 空表：编辑时删掉这条成员，新建时什么都不加；
     * - 与本组已有的组合成员同名：并成一条（保留原位置），不产生两条一模一样的 `cond:`；
     * - 新建且已到成员上限：配置不动（界面上限已经先拦过一次）。
     */
    fun commitCond(dims: List<StrategyComboCatalog.Dim>) {
        val key = if (dims.isEmpty()) {
            ""
        } else {
            StrategyComboCatalog.condKey(StrategyComboCatalog.Condition(dims))
        }
        GLog.i(
            "组合成员：完成 → " + (if (key.isEmpty()) "空表，删掉这条成员" else key) +
                "（" + (if (condMemberKey.isEmpty()) "新建" else "编辑 " + condMemberKey) + "）",
        )
        update { current ->
            val at = if (condMemberKey.isEmpty()) {
                -1
            } else {
                current.members.indexOfFirst { it.name == condMemberKey }
            }
            if (key.isEmpty()) {
                return@update if (at < 0) {
                    current
                } else {
                    current.copy(members = current.members.filterIndexed { i, _ -> i != at })
                }
            }
            val others = current.members.filterIndexed { i, _ -> i != at }
            when {
                others.any { it.name == key && it.stId.isBlank() } ->
                    if (at < 0) current else current.copy(members = others)
                at >= 0 -> {
                    val list = others.toMutableList()
                    list.add(minOf(at, list.size), MergeMember(stId = "", name = key))
                    current.copy(members = list)
                }
                others.size >= StrategyMergeStore.MAX_MEMBERS -> current
                else -> current.copy(members = others + MergeMember(stId = "", name = key))
            }
        }
    }

    /** 加一维：先只勾第一档，用户再在菜单里加档（默认全勾会一下子展开成几十条组合） */
    fun addDim(node: StrategyLabelTable.LabelNode) {
        val dims = condDims
        if (dims.any { it.id == node.labelId }) return
        val full = StrategyComboCatalog.dimOf(node)
        val dim = full.copy(tiers = full.tiers.take(1))
        GLog.i("组合成员：加维度「" + node.name + "」，共 " + full.count + " 档，先勾第一档")
        condDims = dims + dim
    }

    /** 删一维：只剩一维时不给删（空表展开不出组合，会变成一条点了没反应的死成员） */
    fun removeDim(dimIndex: Int) {
        val dims = condDims
        if (dims.size <= 1) {
            GLog.i("组合成员：只剩一个维度，不给删")
            return
        }
        val gone = dims.getOrNull(dimIndex) ?: return
        GLog.i("组合成员：删维度「" + gone.title + "」")
        condDims = dims.filterIndexed { i, _ -> i != dimIndex }
    }

    /** 勾 / 取消某一维的一档；最后一档不给取消（要整维去掉用维度行的 ⋮） */
    fun toggleDimTier(dimIndex: Int, tier: StrategyComboCatalog.Tier) {
        val dims = condDims.toMutableList()
        val dim = dims.getOrNull(dimIndex) ?: return
        val all = allTiersOf(dim)
        val on = dim.tiers.any { it.labelId == tier.labelId }
        val kept = if (on) {
            dim.tiers.filterNot { it.labelId == tier.labelId }
        } else {
            val merged = dim.tiers + tier
            // 按宿主档位顺序排，勾出来的顺序才不会跟着点击顺序乱跳
            all.mapNotNull { t -> merged.firstOrNull { it.labelId == t.labelId } }
        }
        if (kept.isEmpty()) {
            GLog.i("组合成员：维度「" + dim.title + "」只剩最后一档，不取消")
            return
        }
        GLog.i(
            "组合成员：维度「" + dim.title + "」" + (if (on) "取消" else "勾上") +
                "「" + tier.name + "」，剩 " + kept.size + " 档",
        )
        dims[dimIndex] = dim.copy(tiers = kept)
        condDims = dims
    }

    /** 选档菜单：只铺这一维的全部档位，点一行即勾 / 取消，没有确定按钮 */
    fun dimPickerSpec(dimIndex: Int, anchor: Rect?): MultiChoiceDialogSpec {
        val dim = condDims.getOrNull(dimIndex)
        val all = if (dim == null) emptyList() else allTiersOf(dim)
        val kept = dim?.tiers?.map { it.labelId }?.toSet() ?: emptySet()
        val section = MultiChoiceSection(
            title = dim?.title ?: "",
            items = all.map { it.name },
            selected = all.mapIndexedNotNull { i, t -> if (t.labelId in kept) i else null }.toSet(),
        )
        return MultiChoiceDialogSpec(
            sections = listOf(section),
            onToggle = { _, ii ->
                all.getOrNull(ii)?.let { toggleDimTier(dimIndex, it) }
                // 勾完立刻重建菜单：对勾跟着刚改的草稿走，菜单不关（一维要连着点几档）。
                // 重建只是换 spec 内容，弹层编号（DialogController.openSeq）不变，不会再闪一下。
                dialogs?.showMultiChoice(dimPickerSpec(dimIndex, anchor))
            },
            anchor = anchor,
        )
    }

    /** 维度选择菜单：宿主标签全表按五个分组切段，点一个标签就加一维 */
    fun openDimPicker(controller: DialogController, anchor: Rect?) {
        val have = condDims.mapTo(HashSet()) { it.id }
        val items = ArrayList<String>()
        val headers = HashMap<Int, String>()
        val nodes = ArrayList<StrategyLabelTable.LabelNode>()
        for ((groupName, labels) in StrategyLabelTable.tree) {
            if (labels.isEmpty()) continue
            headers[items.size] = groupName
            for (node in labels) {
                nodes.add(node)
                items.add(
                    when {
                        node.labelId in have -> node.name + "  ✓"
                        node.hasBands -> node.name + "（" + node.bands.size + " 档）"
                        else -> node.name
                    },
                )
            }
        }
        controller.showChoice(
            ChoiceDialogSpec(
                items = items,
                selectedIndex = -1,
                onSelect = { i -> nodes.getOrNull(i)?.let { addDim(it) } },
                anchor = anchor,
                groupHeaders = headers,
            ),
        )
    }


    /**
     * 把另一个聚合组的成员一次并进来（下面「从聚合组导入」那一段）。
     *
     * 按名称去重、按上限截断：目标组已经有的不重复加，装满 [StrategyMergeStore.MAX_MEMBERS] 就停。
     * 只复制成员，不动对方组本身。
     */
    fun importMembers(from: MergeGroup) {
        update { current ->
            if (current.id == from.id) return@update current
            val room = StrategyMergeStore.MAX_MEMBERS - current.members.size
            if (room <= 0) return@update current
            val seen = current.members.mapTo(HashSet()) { it.name.ifEmpty { it.stId } }
            val add = ArrayList<MergeMember>()
            for (m in from.members) {
                if (add.size >= room) break
                val key = m.name.ifEmpty { m.stId }
                if (key.isEmpty() || !seen.add(key)) continue
                add.add(m)
            }
            if (add.isEmpty()) current else current.copy(members = current.members + add)
        }
    }

    /**
     * 把一条宿主策略加成成员（「从金太阳导入」那段）。
     *
     * 与「手写策略名称」的唯一区别是这里**带着 stId**：stId 是宿主内部的策略编号，
     * 填上以后目标进程直接按编号取数，不用先等宿主列表页把「名称 → stId」索引喂满。
     * 去重同时看 stId 与名称：同一个策略换了名字再加一次也不会重复。
     */
    fun addHostMember(item: HostStgCatalog.Item) {
        update { current ->
            if (current.members.size >= StrategyMergeStore.MAX_MEMBERS) return@update current
            val dup = current.members.any { m ->
                (m.stId.isNotEmpty() && m.stId == item.stId) || m.name == item.name
            }
            if (dup) return@update current
            current.copy(members = current.members + MergeMember(stId = item.stId, name = item.name))
        }
    }

    /** 成员上下移：取出再插入，别用 `list[i] = list[j]` 那种会覆盖相邻项的写法 */
    fun moveMember(member: MergeMember, delta: Int) {
        update { current ->
            val index = current.members.indexOfFirst { it == member }
            val to = index + delta
            if (index < 0 || to !in current.members.indices) {
                current
            } else {
                val list = current.members.toMutableList()
                list.add(to, list.removeAt(index))
                current.copy(members = list)
            }
        }
    }

    // ---- 组合成员编辑：页内子视图 ----
    //
    // 为什么不放弹层（用户 2026-11-12 两次纠正）：多维要能反复加维、勾档，贴边菜单装不下；
    // 「方案设置」那种「一行一项、点一下立刻生效」的形态，在子页里才铺得开。
    if (condEditing) {
        val dims = condDims
        val total = if (dims.isEmpty()) 0 else StrategyComboCatalog.Condition(dims).comboCount
        val anchors = remember { HashMap<Int, Rect>() }
        var addDimAnchor by remember { mutableStateOf<Rect?>(null) }
        // 文案在组合期取好：点击回调里不是 @Composable 上下文，取不了资源
        val removeDimText = stringResource(R.string.stg_merge_cond_remove_dim)
        // 这一屏是独立的第四层：按返回键退回三级编辑页（不是回二级列表）。
        // 四级页由路由压栈时 onDone 负责出栈；万一没人接（老调用），就退回页内子视图。
        // 四级页（condOnly）只出栈、不改自己的渲染模式：退出动画期间这一屏还要继续画完，
        // 一旦把 condEditing 打回 false 就会闪一下组编辑页。
        // 返回 = 放弃：勾选只改草稿，只有下面「完成」才落成成员。
        BackHandler(enabled = true) {
            if (condOnly) onDone?.invoke() else condEditing = false
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            SmallTitle(stringResource(R.string.stg_merge_cond_title))
            Card(modifier = Modifier.cardPadding()) {
                dims.forEachIndexed { i, d ->
                    val all = allTiersOf(d)
                    BasicComponent(
                        title = d.title,
                        summary = stringResource(
                            R.string.stg_merge_cond_dim_summary,
                            d.bandText(all.size),
                            d.count,
                        ),
                        modifier = Modifier.onGloballyPositioned { anchors[i] = it.boundsInWindow() },
                        endActions = {
                            Row(
                                modifier = Modifier.align(Alignment.CenterVertically),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {
                                        val texts = arrayListOf(removeDimText)
                                        val disabled = if (dims.size <= 1) setOf(0) else emptySet()
                                        dialogs?.showChoice(
                                            ChoiceDialogSpec(
                                                items = texts,
                                                selectedIndex = -1,
                                                onSelect = { removeDim(i) },
                                                anchor = anchors[i],
                                                disabledIndices = disabled,
                                            ),
                                        )
                                    },
                                ) {
                                    TintedIcon(
                                        imageVector = AppIcons.MoreVert,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                }
                            }
                        },
                        // 点这一行选档位：只铺这一维的档位，勾几档就展开成几档
                        onClick = { dialogs?.showMultiChoice(dimPickerSpec(i, anchors[i])) },
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.stg_merge_cond_add),
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.primary,
                    ),
                    startAction = {
                        TintedIcon(imageVector = AppIcons.Add, tint = MiuixTheme.colorScheme.primary)
                    },
                    modifier = Modifier.onGloballyPositioned { addDimAnchor = it.boundsInWindow() },
                    onClick = { dialogs?.let { openDimPicker(it, addDimAnchor) } },
                )
            }

            SmallTitle(stringResource(R.string.stg_merge_cond_total_title))
            Card(modifier = Modifier.cardPadding()) {
                BasicComponent(
                    title = stringResource(R.string.stg_merge_cond_total, total),
                    onClick = null,
                )
                BasicComponent(
                    title = stringResource(R.string.stg_merge_cond_done),
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.primary,
                    ),
                    onClick = {
                        commitCond(dims)
                        if (condOnly) onDone?.invoke() else condEditing = false
                    },
                )
            }

            Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- 组名 + 启用开关 ----
        SmallTitle(stringResource(R.string.stg_merge_section_groups))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = group.name,
                summary = mergeGroupSummary(group),
                endActions = {
                    Row(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArrowRightIcon()
                    }
                },
                onClick = renameAction,
            )
            GsSwitchPreference(
                checked = group.enabled,
                onCheckedChange = { on -> update { it.copy(enabled = on) } },
                title = stringResource(R.string.stg_merge_group_enable_title),
                // 副标题按状态取串（DESIGN-SYSTEM 3.1 / 7.6）：开关行说的是「此刻在哪一档」，
                // 不是「关掉会怎样」——后者属于机制与后果，第 7.3 节已划为禁区。
                summary = stringResource(
                    if (group.enabled) R.string.stg_merge_group_enable_summary_on
                    else R.string.stg_merge_group_enable_summary_off,
                ),
            )
        }

        // ---- 成员 ----
        SmallTitle(stringResource(R.string.stg_merge_section_members))
        Card(modifier = Modifier.cardPadding()) {
            // 空表不画占位句：没有成员就只有下面那行「添加成员」
            group.members.forEachIndexed { index, member ->
                // key 用「名称 + stId」：stId 可以留空，两个只填名称的成员不能共用一个 key
                key(member.name + "|" + member.stId) {
                    // 组合成员（`cond:`）是可以在本页点开再改的：点这一行 = 编辑这条组合，
                    // 而页面底部那行「新建组合」永远是新建（用户 2026-11-12 明确的分工）。
                    val condOfRow = StrategyComboCatalog.conditionOfMember(member.name, member.stId)
                    MergeMemberRow(
                        member = member,
                        index = index,
                        count = group.members.size,
                        onEditCond = if (condOfRow != null && onOpenCond != null) {
                            ({ onOpenCond(member.name) })
                        } else {
                            null
                        },
                        onMove = { delta -> moveMember(member, delta) },
                        onDelete = {
                            update { current ->
                                current.copy(
                                    members = current.members.filterNot { it == member },
                                )
                            }
                        },
                        // 细分档位：把这条成员换成同一标签下的另一档。目标档已经是成员时
                        // 就只删这一条，免得组里出现两条一模一样的 `label:`。
                        onRetarget = { newLabelId ->
                            val key = StrategyComboCatalog.LABEL_PREFIX + newLabelId
                            update { current ->
                                val clash = current.members.any { it != member && it.name == key }
                                current.copy(
                                    members = if (clash) {
                                        current.members.filterNot { it == member }
                                    } else {
                                        current.members.map {
                                            if (it == member) it.copy(name = key) else it
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }

            // 到上限后不再弹层，只在副标题上说明原因
            val canAdd = group.members.size < StrategyMergeStore.MAX_MEMBERS
            BasicComponent(
                title = stringResource(R.string.stg_merge_member_add),
                summary = if (canAdd) null else limitText,
                titleColor = BasicComponentDefaults.titleColor(
                    color = MiuixTheme.colorScheme.primary,
                ),
                startAction = {
                    TintedIcon(imageVector = AppIcons.Add, tint = MiuixTheme.colorScheme.primary)
                },
                // 菜单要贴着这一行的右缘弹出（与方案设置的「+ 添加取值项」同款），
                // 所以得先把它在窗口里的位置量下来。
                modifier = Modifier.onGloballyPositioned { addAnchor = it.boundsInWindow() },
                // 直接进标签全表：不再先弹一层「加哪一类」，点标签就是加一条成员
                onClick = { if (canAdd) dialogs?.let { openLabelGroups(it) } },
            )

            // 新建组合：一条成员 = 若干标签维度的笛卡尔积（市值 × 量比、市值 × 创新高、任意几维）。
            // 宿主标签是交集语义，跨档范围只能拆成多条策略再由聚合并集 —— 这一行就是那条拆解入口。
            //
            // 这里是**新建**，不再兼作「编辑已有的那条」：编辑走成员行（点那一行进同一个页面，
            // 带的是那条成员的名字）。早先两者共用一个入口，于是点一下就凭空多出一条成员、
            // 还直接进了那条的编辑页（用户 2026-11-12 报的）。
            BasicComponent(
                title = stringResource(R.string.stg_merge_member_combo),
                // 到上限时副标题只留「为什么点不动」的状态说明；正常情况下不写，
                // 主标题「新建组合」加号图标已经说清点进去做什么（NAMING 规则四）。
                summary = if (canAdd) null else limitText,
                titleColor = BasicComponentDefaults.titleColor(
                    color = MiuixTheme.colorScheme.primary,
                ),
                startAction = {
                    TintedIcon(imageVector = AppIcons.Add, tint = MiuixTheme.colorScheme.primary)
                },
                onClick = {
                    if (canAdd) {
                        // 新建永远从空维度表开始（草稿按 condMemberKey 初始化，空串即空表）
                        if (onOpenCond != null) onOpenCond("") else condEditing = true
                    }
                },
            )
        }

        // ---- 从组合成员导入：别的组里那条笛卡尔积条件，点一行搬进本组 ----
        //
        // 与「从聚合组导入」分开：那里搬的是成员清单，这里只搬那一条组合条件本身，
        // 用途是「同一个 市值 × 创新高 组合，在这组和那组各用一次」。
        // 一个组可能有多条组合成员，所以这里是「别的组里每一条组合成员各一行」，
        // 不再只取每组的第一条（取第一条时第二条永远搬不过来）。
        val condSources = config.groups.filter { it.id != groupId }.flatMap { other ->
            comboMembersOf(other).map { pair -> Triple(other, pair.first, pair.second) }
        }
        if (condSources.isNotEmpty()) {
            SmallTitle(stringResource(R.string.stg_merge_section_cond_import))
            Card(modifier = Modifier.cardPadding()) {
                val condAtLimit = group.members.size >= StrategyMergeStore.MAX_MEMBERS
                condSources.forEach { pair ->
                    val other = pair.first
                    val member = pair.second
                    val c = pair.third
                    // 同一条组合（成员名逐字相同）搬过来才有意义：按名字判重，不按组
                    val taken = group.members.any { it.name == member.name && it.stId.isBlank() }
                    BasicComponent(
                        title = other.name,
                        summary = when {
                            taken -> stringResource(R.string.stg_merge_cond_import_taken)
                            condAtLimit -> limitText
                            else -> stringResource(
                                R.string.stg_merge_member_combo_detail,
                                StrategyComboCatalog.summaryText(c),
                                c.comboCount,
                            )
                        },
                        startAction = {
                            TintedIcon(
                                imageVector = AppIcons.Add,
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            if (!taken && !condAtLimit) {
                                update { current ->
                                    if (current.members.any { it.name == member.name }) {
                                        current
                                    } else {
                                        current.copy(members = current.members + member)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }

        // ---- 从金太阳导入：宿主「我的策略」里已经有的策略，点一行就加成成员 ----
        //
        // 数据不在这里读宿主：两个进程不同 UID，模块 App 读不到宿主的私有数据。宿主在
        // 「我的策略」列表页采集完后把清单广播过来，接收器落进本地缓存（见 app/HostStgCatalog）。
        // 页面停留期间每 2 秒重读一次缓存：用户去宿主转一圈再切回来，不用退页重进就能看到新清单。
        var hostSnapshot by remember { mutableStateOf(HostStgCatalog.Snapshot.EMPTY) }
        val appContext = LocalContext.current.applicationContext
        LaunchedEffect(Unit) {
            while (true) {
                hostSnapshot = HostStgCatalog.read(appContext)
                delay(2000)
            }
        }
        SmallTitle(stringResource(R.string.stg_merge_section_host))
        Card(modifier = Modifier.cardPadding()) {
            val hostItems = hostSnapshot.items
            // 到上限后不再可点，只在副标题上写原因（与上面「添加成员」一致）
            val hostAtLimit = group.members.size >= StrategyMergeStore.MAX_MEMBERS
            if (hostItems.isEmpty()) {
                // 还没同步过：直接说清该怎么办，别只显示一个空卡片
                BasicComponent(
                    title = stringResource(R.string.stg_merge_host_empty_title),
                    summary = stringResource(R.string.stg_merge_host_empty_summary),
                    onClick = null,
                )
            } else {
                // 第一行报同步时间：清单可能是几分钟前的，用户得能看出来
                BasicComponent(
                    title = stringResource(R.string.stg_merge_host_sync_title),
                    summary = stringResource(
                        R.string.stg_merge_host_sync,
                        hostSyncClock(hostSnapshot.at),
                        hostItems.size,
                    ),
                    onClick = null,
                )
                hostItems.forEach { item ->
                    // 去重同时看 stId 与名称：同一个策略改过名再加一次也不会重复
                    val taken = group.members.any { m ->
                        (m.stId.isNotEmpty() && m.stId == item.stId) || m.name == item.name
                    }
                    BasicComponent(
                        title = item.name,
                        summary = when {
                            taken -> stringResource(R.string.stg_merge_host_item_added)
                            hostAtLimit -> limitText
                            item.tags.isEmpty() -> stringResource(R.string.stg_merge_host_item_no_tag)
                            else -> item.tags.joinToString(" · ")
                        },
                        startAction = {
                            TintedIcon(
                                imageVector = AppIcons.Add,
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = { if (!taken && !hostAtLimit) addHostMember(item) },
                    )
                }
            }
        }

        // ---- 聚合组分类：另一个组的成员一次并进来，与上面的「成员分类」分开摆放 ----
        val others = config.groups.filter { it.id != groupId }
        if (others.isNotEmpty()) {
            SmallTitle(stringResource(R.string.stg_merge_section_import))
            Card(modifier = Modifier.cardPadding()) {
                val atLimit = group.members.size >= StrategyMergeStore.MAX_MEMBERS
                others.forEach { other ->
                    val have = other.members.count { m ->
                        val key = m.name.ifEmpty { m.stId }
                        group.members.any { it.name.ifEmpty { it.stId } == key }
                    }
                    BasicComponent(
                        title = other.name,
                        summary = when {
                            atLimit -> limitText
                            have == other.members.size ->
                                stringResource(R.string.stg_merge_import_none)
                            else -> stringResource(
                                R.string.stg_merge_import_summary,
                                other.members.size,
                                have,
                            )
                        },
                        startAction = {
                            TintedIcon(
                                imageVector = AppIcons.Add,
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = { if (!atLimit) importMembers(other) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/**
 * 广播时间戳 → 「12:34」。清单旧不旧用户得一眼看出来，所以只给时分不写日期：
 * 隔夜的旧清单反而一眼能看出「这不是今天的钟点」。
 */
private fun hostSyncClock(at: Long): String {
    if (at <= 0L) return "--:--"
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(at))
}

/**
 * 一行成员：标题 = 条件 / 标签名，副标题 = 生效范围，行尾 ⋮ 弹菜单。
 *
 * 菜单最多四项，第一项只有「有档位的标签」才有：
 *  - 细分档位：把 `label:<父labelId>` 换成 `label:<档位labelId>`（父标签接不了数，必须选一档）；
 *  - 上移 / 下移 / 删除。
 *
 * 副标题分四种：
 *  - 档位标签：写成「策略标签 · 行情面」；
 *  - 待选档位的父标签：写成「策略标签 · 行情面 · 点这一行选档位」；
 *  - 口径 / 自选条件成员：写成「流通市值50-500亿 · 量比1.5-4 · 12 条组合」；
 *  - 普通成员：有 stId 显示 stId，空的写「打开一次宿主后自动识别编号」。
 *
 * 带档位的成员（父标签、或已选档的标签）整行可点，点了直接弹档位菜单；
 * 组合成员（`cond:`）整行可点，点了进第四层的组合维度页改这条组合；
 * 其余成员（口径 / 手写 / 普通策略）没有可编辑字段，只有 ⋮ 里的上移、下移、删除。
 *
 * @param onEditCond 组合成员点行时的动作（进组合维度页，带这条成员的名字）；null = 不能点
 */
@Composable
private fun MergeMemberRow(
    member: MergeMember,
    index: Int,
    count: Int,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onRetarget: (String) -> Unit,
    onEditCond: (() -> Unit)? = null,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current

    // stId 可以留空：空着就说明这一条靠名称在目标进程里现认，副标题要说清楚，别显示空行
    val autoText = stringResource(R.string.stg_merge_member_auto)
    // 口径成员（「中盘 1.5-4」这类）不是宿主里的策略，它是一批组合：副标题直接报条件与条数，
    // 免得用户以为要在宿主里找一条叫这个名字的策略。
    val bucket = StrategyComboCatalog.bucketOfMember(member.name, member.stId)
    // 自选条件成员：名字是 `cond:50-70亿,70-100亿|1.5-2.0` 这种编码，标题换成给人看的一行
    val cond = StrategyComboCatalog.conditionOfMember(member.name, member.stId)
    // 宿主标签成员（`label:<labelId>`）：名字是编码，标题换成「量比1.5-2.0」这种人话
    val labelId = StrategyComboCatalog.labelIdOf(member.name)
    val labelEntry = labelId?.let { StrategyLabelTable.entryOf(it) }
    // 还没选档的父标签（`label:<父labelId>`）：有档位、但自己不接数 —— 菜单里多一项「细分档位」
    val labelNode = if (labelEntry == null) labelId?.let { StrategyLabelTable.nodeOf(it) } else null
    // 能换档的两种成员：还没选档的父标签（labelNode 自己），以及已经选了档的（回到它所属的父标签）
    val bandHost = labelNode
        ?: labelEntry?.parentId?.takeIf { it.isNotEmpty() }?.let { StrategyLabelTable.nodeOf(it) }
    val bands = bandHost?.bands.orEmpty()

    val title = when {
        labelEntry != null -> labelEntry.display
        labelNode != null -> labelNode.name
        cond != null -> StrategyComboCatalog.summaryText(cond)
        else -> member.name
    }
    val summary = when {
        labelEntry != null -> stringResource(
            R.string.stg_merge_member_label_detail,
            labelEntry.groupName,
        )
        labelNode != null -> stringResource(
            R.string.stg_merge_member_label_pending,
            StrategyLabelTable.groupOf(labelNode.labelId).orEmpty(),
        )
        bucket != null -> stringResource(
            R.string.stg_merge_member_combo_detail,
            StrategyComboCatalog.summaryText(bucket),
            bucket.comboCount,
        )
        cond != null -> stringResource(
            R.string.stg_merge_member_combo_detail,
            StrategyComboCatalog.summaryText(cond),
            cond.comboCount,
        )
        else -> member.stId.ifEmpty { autoText }
    }

    // 选档位不再放进 ⋮：点这一行本身就是选档（见下面的 onClick），菜单只留行操作
    val menuTexts = listOf(
        stringResource(R.string.stg_merge_menu_move_up),
        stringResource(R.string.stg_merge_menu_move_down),
        stringResource(R.string.common_delete),
    )
    val disabledIndices = buildSet {
        if (index == 0) add(0)
        if (index == count - 1) add(1)
    }

    /** 选档弹层：点一档就把这条成员换成那一档（父标签 → 具体档，或换到同一标签的另一档） */
    fun pickBand() {
        val keys = bands.map { StrategyComboCatalog.LABEL_PREFIX + it.labelId }
        val items = bands.mapIndexed { i, band ->
            if (member.name == keys[i]) band.labelName + "  ✓" else band.labelName
        }
        dialogs?.showChoice(
            ChoiceDialogSpec(
                items = items,
                selectedIndex = -1,
                onSelect = { i -> bands.getOrNull(i)?.let { onRetarget(it.labelId) } },
                anchor = anchor,
                header = bandHost?.name,
            ),
        )
    }

    BasicComponent(
        onClick = when {
            bands.isNotEmpty() -> ({ pickBand() })
            onEditCond != null -> onEditCond
            else -> null
        },
        title = title,
        summary = summary,
        endActions = {
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        dialogs?.showChoice(
                            ChoiceDialogSpec(
                                items = menuTexts,
                                selectedIndex = -1,
                                onSelect = { which ->
                                    when (which) {
                                        0 -> onMove(-1)
                                        1 -> onMove(1)
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
            }
        },
    )
}
