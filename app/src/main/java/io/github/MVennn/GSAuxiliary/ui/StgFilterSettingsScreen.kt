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

/**
 * 「筛选条件保存设置」二级页（策略详情页「筛选股票」展开出来的设置）。
 *
 * 只有一个开关：**按策略保存筛选条件**（配置键 `stg_filter_per_strategy`）。
 *
 *  - 关（默认）：所有策略共用一份筛选状态，和以前完全一样（历史行为）。
 *  - 开：每个策略（`stg:<策略 id>`）、每个聚合组（`grp:<组 id>`）各记一份 ——
 *    在 A 策略上勾「只看涨的」，切到 B 策略不会跟着变。
 *
 * 三个筛选开关本身**不在这里**：它们在目标 App 自己的筛选面板里（策略详情页三点菜单）。
 * 这一页只决定「按什么身份记这份勾选」，所以不复刻那三个开关。
 */
@Composable
internal fun StgFilterSettingsScreen() {
    val perStrategy by rememberBoolPref(
        ConfigKeys.STG_FILTER_PER_STRATEGY,
        ConfigKeys.DEFAULT_STG_FILTER_PER_STRATEGY,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = perStrategy,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.STG_FILTER_PER_STRATEGY, v) }
                },
                title = stringResource(R.string.switch_stg_filter_per_strategy),
                // 副标题按当前状态取不同字符串（DESIGN-SYSTEM 第 3.1 节：开关行描述当前状态，
                // 必须 _on / _off 两套）。标题只说「按策略」，聚合组那条只在这里出现。
                summary = stringResource(
                    if (perStrategy) {
                        R.string.switch_stg_filter_per_strategy_on
                    } else {
                        R.string.switch_stg_filter_per_strategy_off
                    }
                ),
            )
        }
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
