package io.github.MVennn.GSAuxiliary.ui

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
 * 智能选股 · 我的策略整合 → 编辑一个聚合组（三级页）。
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
 */
@Composable
internal fun StrategyMergeEditScreen(groupId: String, onDeleted: () -> Unit) {
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
                summary = stringResource(R.string.stg_merge_group_enable_summary),
            )
        }

        // ---- 成员 ----
        SmallTitle(stringResource(R.string.stg_merge_section_members))
        Card(modifier = Modifier.cardPadding()) {
            // 空表不画占位句：没有成员就只有下面那行「添加成员」
            group.members.forEachIndexed { index, member ->
                // key 用「名称 + stId」：stId 可以留空，两个只填名称的成员不能共用一个 key
                key(member.name + "|" + member.stId) {
                    MergeMemberRow(
                        member = member,
                        index = index,
                        count = group.members.size,
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
 * 其余成员（口径 / 手写 / 普通策略）没有可编辑字段，只有 ⋮ 里的上移、下移、删除。
 */
@Composable
private fun MergeMemberRow(
    member: MergeMember,
    index: Int,
    count: Int,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onRetarget: (String) -> Unit,
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
        onClick = if (bands.isEmpty()) null else ({ pickBand() }),
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
