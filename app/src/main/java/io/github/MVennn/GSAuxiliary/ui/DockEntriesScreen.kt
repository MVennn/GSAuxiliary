package io.github.MVennn.GSAuxiliary.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.DockEntryStore
import top.yukonga.miuix.kmp.basic.Card

/**
 * 「底栏入口设置」二级页：底栏六格里显示哪几个。
 *
 * 六个开关的**开 = 隐藏那一格**，与一级开关的标题「自定义 底栏 入口」一致；
 * 关掉一级开关就整条底栏恢复原样，所以这一页只管「藏哪几个」，不再问「要不要藏」。
 *
 * ## 三条硬规则（都在这里落地）
 *
 * - **至少留一格**：已经藏了五格时，剩下那一行的开关置灰、副标题换成
 *   `switch_dock_entry_summary_locked` —— 六格全藏等于底栏消失，回不去任何页面。
 * - **每改一下立即写整份 JSON**，不设保存按钮：写的是
 *   [ConfigKeys.DOCK_ENTRIES]（`{"v":1,"hidden":[...]}`），目标进程读到就重排。
 * - **写前重读最新值再合并**：这一页可能和另一处界面（adb 调试通道）同时改，
 *   拿旧的 `hidden` 直接覆盖会把对方那次改动吞掉。
 *
 * 迁移：老版本只有「隐藏理财一格」的 [ConfigKeys.DOCK_CLEAN]，新键还没写过时按它换算，
 * 与目标进程 `DockEntryStore.decode(raw, legacyHidden)` 同一口径 —— 用户升级后打开这一页，
 * 看到的就是他现在实际看到的底栏。
 */
@Composable
internal fun DockEntriesScreen() {
    val raw by rememberStringPref(ConfigKeys.DOCK_ENTRIES, ConfigKeys.DEFAULT_DOCK_ENTRIES)
    // 迁移源：新键空串时，老总开关开着的那一格算「已隐藏」
    val legacyHidden by rememberBoolPref(ConfigKeys.DOCK_CLEAN, ConfigKeys.DEFAULT_DOCK_CLEAN)
    val hidden = remember(raw, legacyHidden) { DockEntryStore.decode(raw, legacyHidden) }
    // 只剩一格时那一格置灰：锁定的是「当前还显示着」的那一个，它一变就自动换到别的行
    val lockedKey = remember(hidden) {
        val keys = DockEntryStore.KEYS
        if (hidden.size >= keys.size - 1) keys.firstOrNull { it !in hidden } else null
    }

    // 读最新值 → 合并这一格的改动 → 整份写回
    fun toggle(key: String, hide: Boolean) {
        val latest = DockEntryStore.decode(
            ModulePreferences.getString(ConfigKeys.DOCK_ENTRIES, ConfigKeys.DEFAULT_DOCK_ENTRIES),
            ModulePreferences.getBoolean(ConfigKeys.DOCK_CLEAN, ConfigKeys.DEFAULT_DOCK_CLEAN),
        ).toMutableSet()
        if (hide) latest.add(key) else latest.remove(key)
        // 兜底：任何情况下都不写「六格全藏」
        if (latest.size >= DockEntryStore.KEYS.size) return
        ModulePreferences.edit {
            putString(ConfigKeys.DOCK_ENTRIES, DockEntryStore.encode(latest))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            DockEntryStore.KEYS.forEach { key ->
                val isHidden = key in hidden
                val locked = key == lockedKey
                GsSwitchPreference(
                    checked = isHidden,
                    onCheckedChange = { v -> toggle(key, v) },
                    title = stringResource(dockEntryTitleRes(key)),
                    // 副标题只说当前状态（DESIGN-SYSTEM 3.1：开关行必须 _on / _off 两套）；
                    // 锁定行第三套，仍以「显示中」开头，说的是「为什么这一行按不动」
                    summary = stringResource(
                        when {
                            locked -> R.string.switch_dock_entry_summary_locked
                            isHidden -> R.string.switch_dock_entry_summary_on
                            else -> R.string.switch_dock_entry_summary_off
                        },
                    ),
                    enabled = !locked,
                )
            }
        }
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/** 六格的标题资源：键 → 「隐藏 X 入口」（顺序由 [DockEntryStore.KEYS] 决定，与底栏一致） */
@StringRes
private fun dockEntryTitleRes(key: String): Int = when (key) {
    DockEntryStore.KEY_HOME -> R.string.switch_dock_entry_home
    DockEntryStore.KEY_QUOTES -> R.string.switch_dock_entry_quotes
    DockEntryStore.KEY_ZX -> R.string.switch_dock_entry_zx
    DockEntryStore.KEY_TRADE -> R.string.switch_dock_entry_trade
    DockEntryStore.KEY_MONEY -> R.string.switch_dock_entry_money
    DockEntryStore.KEY_MINE -> R.string.switch_dock_entry_mine
    else -> R.string.switch_dock_entry_home
}
