package io.github.MVennn.GSAuxiliary.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
 *  - 「新建方案」→ 反弹 ±5% 的空方案，改名字与取值项进编辑页。
 *
 * ## 取值项清单为什么不摆在这一页
 *
 * 3.1.x 这一页底部有一张「取值项说明」卡，把 §4.2 目录里的 18 个取值项按分组
 * 全列了一遍 —— 六个组名 + 十八行，比上面的方案列表还长，而那些名字在编辑页
 * 「添加取值项」的弹层里本来就能看到，属于同一信息摆两处。现在只留一句口径脚注
 * （[R.string.quick_terms_note]）垫在方案卡底部：这一页只回答「有哪些方案」。
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
    val newSchemeName = stringResource(R.string.quick_new_scheme_default_name)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.quick_section_schemes))
        Card(modifier = Modifier.cardPadding()) {
            data.schemes.forEachIndexed { index, scheme ->
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
                            1 -> if (index > 0) {
                                val list = data.schemes.toMutableList()
                                list[index - 1] = data.schemes[index].also { list[index] = it }
                                writeSchemes(data.copy(schemes = list))
                            }
                            2 -> if (index < data.schemes.size - 1) {
                                val list = data.schemes.toMutableList()
                                list[index + 1] = data.schemes[index].also { list[index] = it }
                                writeSchemes(data.copy(schemes = list))
                            }
                            3 -> deleteScheme(scheme.id, data)
                        }
                    },
                )
            }
            // 新建方案：与方案行同组，主色 + 加号图标说明「这是动作」
            BasicComponent(
                title = stringResource(R.string.quick_new_scheme),
                titleColor = BasicComponentDefaults.titleColor(
                    color = MiuixTheme.colorScheme.primary,
                ),
                startAction = {
                    Icon(
                        imageVector = AppIcons.Add,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                    )
                },
                onClick = {
                    val id = QuickConfigStore.newId(data.schemes)
                    writeSchemes(
                        data.copy(schemes = data.schemes + defaultNewScheme(id, newSchemeName)),
                    )
                },
            )
            // 口径脚注：一句话说完「周期怎么算、取不到数会怎样」
            CardNote(stringResource(R.string.quick_terms_note))
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
                        ),
                    )
                },
                modifier = Modifier.onGloballyPositioned { anchor = it.boundsInWindow() },
            ) {
                Icon(
                    imageVector = AppIcons.MoreVert,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
        },
        onClick = onClick,
    )
}

/** 「默认」小徽标 */
@Composable
private fun DefaultBadge() {
    Box(
        modifier = Modifier
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
