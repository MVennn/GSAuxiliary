package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 新建方案那一行的展开动画时长（也是「动画期间保留 justAdded 标记」的时长） */
private const val SchemeAppearMillis = 260

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.quick_section_schemes))
        Card(modifier = Modifier.cardPadding()) {
            data.schemes.forEachIndexed { index, scheme ->
                val row: @Composable () -> Unit = {
                    SchemeRow(
                        name = scheme.name,
                        preview = scheme.preview,
                        isDefault = scheme.id == data.defaultId,
                        menuTexts = menuTexts,
                        onClick = { onEdit(scheme.id) },
                        onMenu = { which ->
                            when (which) {
                                0 -> {
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
                                3 -> deleteScheme(scheme.id, data)
                            }
                        },
                    )
                }
                if (scheme.id == justAdded) {
                    // 新建的那一行从 0 高度展开 + 淡入，落位后把标记清掉
                    // （清掉之后这一行回到和别的行一样的普通分支，不再重播动画）。
                    var shown by remember(scheme.id) { mutableStateOf(false) }
                    LaunchedEffect(scheme.id) {
                        shown = true
                        delay(SchemeAppearMillis.toLong())
                        if (justAdded == scheme.id) justAdded = null
                    }
                    AnimatedVisibility(
                        visible = shown,
                        enter = expandVertically(tween(SchemeAppearMillis)) + fadeIn(tween(180)),
                    ) {
                        row()
                    }
                } else {
                    row()
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
    onClick: () -> Unit,
    onMenu: (Int) -> Unit,
) {
    var anchor by remember { mutableStateOf<Rect?>(null) }
    val dialogs = LocalDialogController.current

    ArrowPreference(
        title = name,
        summary = preview.ifEmpty { null },
        endActions = {
            if (isDefault) {
                // `align(CenterVertically)`：这一行的 Row 默认是 **Top** 对齐，
                // 徽标比旁边的 IconButton 矮一大截，不显式居中的话会贴在行的上沿。
                DefaultBadge(Modifier.align(Alignment.CenterVertically))
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
        },
        onClick = onClick,
    )
}

/** 「默认」小徽标（`modifier` 由调用方传，用它做行内垂直居中） */
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

/** 删除方案：只剩一个时忽略（§6.3「必须有默认方案可用」） */
private fun deleteScheme(id: String, data: QuickConfigStore.Data) {
    if (data.schemes.size <= 1) return
    val rest = data.schemes.filter { it.id != id }
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
