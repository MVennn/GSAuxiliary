package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 「自选分组复制 → 复制按钮设置」二级页。
 *
 * 只放**按钮本身**的行为：长按是否弹动作菜单。复制字段 / 格式 / 导出样式都在
 * 同级的「复制规则」页里 —— 两件事分开，避免规则页顶部再混一颗全局开关。
 */
@Composable
internal fun ZxCopyButtonScreen() {
    val scrollState = rememberScrollState()
    val menuEnabled by rememberBoolPref(ConfigKeys.ZX_COPY_MENU, ConfigKeys.DEFAULT_ZX_COPY_MENU)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.zx_copy_section_button))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = menuEnabled,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.ZX_COPY_MENU, v) }
                },
                title = stringResource(R.string.zx_copy_menu_switch),
                summary = stringResource(R.string.zx_copy_menu_switch_summary),
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
