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
import io.github.MVennn.GSAuxiliary.core.StockBarItemStore
import top.yukonga.miuix.kmp.basic.Card

/**
 * 「详情页底栏入口设置」二级页：把「更多」面板里的哪几项提到详情页底栏。
 *
 * 七个开关的**开 = 把那一项从「更多」里提到一级**，与一级开关的标题
 * 「自定义 详情页底栏 入口」一致；关掉一级开关就整条底栏恢复原样，
 * 所以这一页只管「提哪几项」，不再问「要不要提」。
 *
 * ## 三条硬规则（都在这里落地）
 *
 * - **最多两项**：每项实测 124 px + 间距 26 px，第三项就会把买 / 卖压到 96 px 以下
 *   （见 [io.github.MVennn.GSAuxiliary.feature.stockbar.StockDetailBarTargets] 头注）。
 *   已经挑满两项时，剩下几行整行置灰、副标题换成 `switch_stock_bar_summary_locked`。
 * - **每改一下立即写整份 JSON**，不设保存按钮：写的是
 *   [ConfigKeys.STOCK_BAR_ITEMS]（`{"v":1,"items":[...]}`），目标进程读到就重建入口。
 * - **写前重读最新值再合并**：这一页可能和另一处界面（adb 调试通道）同时改，
 *   拿旧的 `items` 直接覆盖会把对方那次改动吞掉。
 *
 * 迁移：老版本只有「智能盯盘入口」一个开关的 [ConfigKeys.STOCK_BAR_ENTRY]，新键还没写过时
 * 按它换算成 `["aistare"]`，与目标进程 `StockBarItemStore.decode(raw, legacyOn)` 同一口径 ——
 * 用户升级后打开这一页，看到的就是他现在实际看到的底栏。
 *
 * 全部关掉是合法状态：等于不外提，底栏回到原样，目标进程不注入任何按钮。
 */
@Composable
internal fun StockBarEntriesScreen() {
    val raw by rememberStringPref(ConfigKeys.STOCK_BAR_ITEMS, ConfigKeys.DEFAULT_STOCK_BAR_ITEMS)
    // 迁移源：新键空串时，老总开关开着算「已外提智能盯盘」
    val legacyOn by rememberBoolPref(ConfigKeys.STOCK_BAR_ENTRY, ConfigKeys.DEFAULT_STOCK_BAR_ENTRY)
    val items = remember(raw, legacyOn) { StockBarItemStore.decode(raw, legacyOn) }
    // 挑满两项后，没被挑中的行按不动（取消勾选永远可用，否则退不出来）
    val full = items.size >= StockBarItemStore.MAX

    // 读最新值 → 合并这一项的改动 → 整份写回
    fun toggle(key: String, pin: Boolean) {
        val latest = StockBarItemStore.decode(
            ModulePreferences.getString(ConfigKeys.STOCK_BAR_ITEMS, ConfigKeys.DEFAULT_STOCK_BAR_ITEMS),
            ModulePreferences.getBoolean(ConfigKeys.STOCK_BAR_ENTRY, ConfigKeys.DEFAULT_STOCK_BAR_ENTRY),
        ).toMutableList()
        if (pin) {
            if (key in latest) return
            // 兜底：任何情况下都不写超过容量上限的项
            if (latest.size >= StockBarItemStore.MAX) return
            latest.add(key)
        } else {
            latest.remove(key)
        }
        ModulePreferences.edit {
            putString(ConfigKeys.STOCK_BAR_ITEMS, StockBarItemStore.encode(latest))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            StockBarItemStore.KEYS.forEach { key ->
                val pinned = key in items
                val locked = !pinned && full
                GsSwitchPreference(
                    checked = pinned,
                    onCheckedChange = { v -> toggle(key, v) },
                    title = stringResource(stockBarEntryTitleRes(key)),
                    // 副标题只说当前状态（DESIGN-SYSTEM 3.1：开关行必须 _on / _off 两套）；
                    // 锁定行第三套，仍以「仍在更多里」开头，说的是「为什么这一行按不动」
                    summary = stringResource(
                        when {
                            pinned -> R.string.switch_stock_bar_summary_on
                            locked -> R.string.switch_stock_bar_summary_locked
                            else -> R.string.switch_stock_bar_summary_off
                        },
                    ),
                    enabled = !locked,
                )
            }
        }
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/** 七项的标题资源：键 → 「外提 X 入口」（顺序由 [StockBarItemStore.KEYS] 决定，与面板一致） */
@StringRes
private fun stockBarEntryTitleRes(key: String): Int = when (key) {
    StockBarItemStore.KEY_CONDITION -> R.string.switch_stock_bar_condition
    StockBarItemStore.KEY_AISTARE -> R.string.switch_stock_bar_aistare
    StockBarItemStore.KEY_KLINE -> R.string.switch_stock_bar_kline
    StockBarItemStore.KEY_CHAIN -> R.string.switch_stock_bar_chain
    StockBarItemStore.KEY_RANGE -> R.string.switch_stock_bar_range
    StockBarItemStore.KEY_TRADES -> R.string.switch_stock_bar_trades
    StockBarItemStore.KEY_PK -> R.string.switch_stock_bar_pk
    else -> R.string.switch_stock_bar_aistare
}