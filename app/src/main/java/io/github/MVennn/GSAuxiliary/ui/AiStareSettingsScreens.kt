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
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 智能盯盘的两个小设置页（3.1.1 新增，都只有一个开关）。
 *
 * 为什么单独成一个文件：它们不属于「方案设置」那条链路（那条是三级路由 + 弹层），
 * 只是功能页展开出来的两个普通二级页，放在一起比塞进 QuickConfigScreen 好找。
 */

/**
 * 「配置按钮设置」二级页：多套方案时点「配置并保存」还弹不弹选择层。
 *
 * 关闭后直接用**默认方案**（`defaultId`），少一步选择；只有一套方案时本来就不弹，
 * 这个开关对那种情况没有影响。
 */
@Composable
internal fun QuickConfigButtonScreen() {
    val pick by rememberBoolPref(ConfigKeys.AISTARE_PICK_SCHEME, ConfigKeys.DEFAULT_AISTARE_PICK_SCHEME)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            SwitchPreference(
                checked = pick,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.AISTARE_PICK_SCHEME, v) }
                },
                title = stringResource(R.string.switch_pick_scheme),
            )
        }
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/**
 * 「保存后设置」二级页：保存成功之后退到哪一层。
 *
 * 只有「无需确定保存」打开时才会走到自动返回这条路上；开关关着时这一页不影响任何行为
 * （入口也只在那个功能开启后展开，见 FeatureTab 的 AnimatedVisibility）。
 */
@Composable
internal fun AiStareSaveBehaviorScreen() {
    val backToStock by rememberBoolPref(
        ConfigKeys.AISTARE_SAVE_BACK_TO_STOCK,
        ConfigKeys.DEFAULT_AISTARE_SAVE_BACK_TO_STOCK,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            SwitchPreference(
                checked = backToStock,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.AISTARE_SAVE_BACK_TO_STOCK, v) }
                },
                title = stringResource(R.string.switch_save_back_to_stock),
            )
        }
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
