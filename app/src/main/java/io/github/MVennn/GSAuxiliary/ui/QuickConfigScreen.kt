package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.QuickConfigCatalog
import io.github.MVennn.GSAuxiliary.core.QuickConfigStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「一键配置」的方案列表页（二级页）。
 *
 * 方案就是「点一下配置按钮，涨到 / 跌到各填什么」的取值组合；这一页管增删改排序
 * 与设默认（§6.2）。**每改一下立即写回 remote preferences**（与其它二级页一致，
 * 不设「保存」按钮 —— 少一个状态机，也避免改完忘存）。
 *
 * 交互：
 *  - 点行 → 进编辑页（[QuickConfigEditScreen]）；
 *  - 行尾 ⋮ → 锚定菜单（设为默认 / 上移 / 下移 / 删除方案）；
 *  - 「＋ 新建方案」→ 反弹 ±5% 的空方案进编辑页（改名改项都顺手）；
 *  - 底部说明卡 = 取值项目录（§4.2 那张表的用户视角）。
 */
@Composable
internal fun QuickConfigScreen(onEdit: (String) -> Unit) {
    val scrollState = rememberScrollState()
    val dialogs = LocalDialogController.current

    // 配置 JSON → 结构化方案；term 名称（本地化）按下标取
    val json by rememberStringPref(ConfigKeys.AISTARE_QUICK_CONFIG, ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG)
    val data = remember(json) { QuickConfigStore.decode(json) }

    // ⋮ 菜单的锚点（锚定菜单从控件右中弹出，见 ui/AppDialog.kt）
    var menuAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

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
                    onAnchor = { menuAnchor = it },
                    onClick = { onEdit(scheme.id) },
                    onMenu = {
                        dialogs?.showChoice(
                            ChoiceDialogSpec(
                                items = menuTexts,
                                selectedIndex = -1, // 动作菜单，无选中态
                                onSelect = { which ->
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
                                anchor = menuAnchor,
                            ),
                        )
                    },
                )
            }
            // ＋ 新建方案
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val id = QuickConfigStore.newId(data.schemes)
                        writeSchemes(
                            data.copy(schemes = data.schemes + defaultNewScheme(id, newSchemeName)),
                        )
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.quick_new_scheme),
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 15.sp,
                )
            }
        }

        // ---- 取值项说明（§4.2 目录的用户视角）----
        SmallTitle(stringResource(R.string.quick_section_terms))
        Card(modifier = Modifier.cardPadding()) {
            val termNames = stringArrayResource(R.array.quick_term_names)
            QuickConfigCatalog.GROUP_ORDER.forEach { group ->
                val items = QuickConfigCatalog.ALL.filter { it.group == group }
                if (items.isEmpty()) return@forEach
                Text(
                    text = groupNameText(group),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp),
                )
                items.forEach { spec ->
                    Text(
                        text = termNames.getOrElse(QuickConfigCatalog.index(spec.id)) { spec.id },
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 6.dp),
                    )
                }
            }
            CardNote(stringResource(R.string.quick_terms_note))
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/** 一行方案：点击进编辑，⋮ 弹菜单 */
@Composable
private fun SchemeRow(
    name: String,
    preview: String,
    isDefault: Boolean,
    onAnchor: (androidx.compose.ui.geometry.Rect) -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (isDefault) {
                    Spacer(Modifier.width(8.dp))
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
            }
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        // ⋮ 菜单按钮（锚定菜单的触发控件）
        Box(
            modifier = Modifier
                .onGloballyPositioned { onAnchor(it.boundsInWindow()) }
                .clickable(onClick = onMenu)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "⋮",
                fontSize = 20.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
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

/** 分组名 → strings 资源 */
@Composable
private fun groupNameText(group: String): String = stringResource(
    when (group) {
        QuickConfigCatalog.GROUP_REALTIME -> R.string.quick_group_realtime
        QuickConfigCatalog.GROUP_POSITION -> R.string.quick_group_position
        QuickConfigCatalog.GROUP_RANGE -> R.string.quick_group_range
        QuickConfigCatalog.GROUP_MA -> R.string.quick_group_ma
        QuickConfigCatalog.GROUP_PCT -> R.string.quick_group_pct
        else -> R.string.quick_group_fixed
    },
)
