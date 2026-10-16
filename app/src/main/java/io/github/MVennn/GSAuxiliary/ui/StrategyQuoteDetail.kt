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
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 「策略直达标准行情」的二级菜单。
 *
 * 一级页面（功能 Tab）只放总开关；这里只放**总开关之外**的参数：
 * 左右滑动 —— 是否把整张策略股票列表一起送进个股详情页。
 *
 * ## 为什么这里**没有**「直达」开关（2.11.2 删除）
 *
 * 历史上这里放过一个「直达标准行情」开关，它和一级页面的总开关是**同一个配置键**
 * （[ConfigKeys.STG_DIRECT_QUOTE]）—— 同一个开关在两层页面各画了一遍。
 * 后果是：用户在二级菜单里把它关掉时，一级页面的「XX设置」入口会**当场收起**，
 * 人被从二级菜单里"弹"回一级，看着像页面崩了；而且两级的状态必须处处同步，
 * 漏刷一处就是「上下两页开关对不上」。
 *
 * 这个入口本来就**只在总开关打开后**才展开（见 `FeatureTab.FeatureEntry`），
 * 也就是说能进到这里时「直达」必然是开的 —— 它在这里关不掉任何有用的东西。
 * 删掉之后：开关只在它该在的那一层出现，二级菜单里全是总开关之外的参数。
 *
 * 同理，下面「左右滑动」的 `enabled` 跟随 [ConfigKeys.STG_DIRECT_QUOTE] 这层保护
 * 在正常路径下**永远是 true**；保留它是为了兜住非常规路径（进程重建后路由被恢复、
 * 而配置已被重置）——那种情况下「不直达就没有个股详情页可谈滑动」，置灰比可点更诚实。
 */
@Composable
internal fun StrategyQuoteDetailScreen() {
    val scrollState = rememberScrollState()

    // 只为给「左右滑动」置灰而读，本页不再渲染它自己的开关（见上方注释）
    val direct by rememberBoolPref(ConfigKeys.STG_DIRECT_QUOTE, ConfigKeys.DEFAULT_STG_DIRECT_QUOTE)
    val swipe by rememberBoolPref(ConfigKeys.STG_QUOTE_SWIPE, ConfigKeys.DEFAULT_STG_QUOTE_SWIPE)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        // --- 左右滑动 ---
        // 开关不带副标题：「左右滑动切换股票」已经把行为说完，开 / 关两种状态的
        // 说明文字（「在个股详情页左右滑动…」「只显示当前这一只…」）都是标题的
        // 展开写法，卡片底的「与行情、自选列表点进去的效果一致」讲的是实现效果 ——
        // 按 docs/NAMING.md §3 的同一把尺子，一律不写（2.17.2 清理）。
        SmallTitle(stringResource(R.string.section_stg_swipe))
        Card(modifier = Modifier.cardPadding()) {
            SwitchPreference(
                checked = swipe,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.STG_QUOTE_SWIPE, v) }
                },
                title = stringResource(R.string.item_stg_quote_swipe),
                enabled = direct,
            )
        }

        // 手势导航条让位（同 WatchlistDetailScreen）
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
