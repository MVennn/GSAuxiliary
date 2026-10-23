package io.github.MVennn.GSAuxiliary.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 极简路由：一级首页 + 五个二级页 + 一个三级页 */
internal sealed interface Route {
    data object Home : Route
    data object WatchlistDetail : Route
    data object StgDirectQuoteDetail : Route
    data object QuickConfig : Route
    /** 方案编辑页（三级）：带方案 id（方案被删时页面自己回落空态） */
    data class QuickConfigEdit(val schemeId: String) : Route
    /** 配置按钮设置：多方案时是否弹选择层 */
    data object QuickConfigButton : Route
    /** 自选分组复制的规则列表（二级） */
    data object ZxCopyRules : Route
    /** 复制按钮设置（二级）：长按动作菜单开关 */
    data object ZxCopyButton : Route
    /** 复制规则编辑页（三级）：带规则 id（规则被删时页面自己回落空态） */
    data class ZxCopyRuleEdit(val ruleId: String) : Route
    /** 保存后返回位置设置：保存成功退到哪一层 */
    data object AiStareSaveBehavior : Route
    data object Disclaimer : Route
    data object Notice : Route
}

/** 页面滑动动画时长 */
private const val RouteAnimMillis = 300

/**
 * [Route] 的存档器（转屏 / 分屏 / 进程被回收后重建靠它把「当前在哪一层」带回来）。
 *
 * 为什么不用 `rememberSaveable` 的自动存盘：`Route` 是 sealed interface，
 * `QuickConfigEdit` 还带一个 id，自动存盘只认 Parcelable / Serializable。
 * 编成一个短字符串最省事，也顺手把「新加路由忘了写存盘」这件事变成一个必须填的 when 分支。
 */
private object RouteSaver : Saver<Route, String> {
    override fun SaverScope.save(value: Route): String = when (value) {
        Route.Home -> "home"
        Route.QuickConfig -> "quick_config"
        is Route.QuickConfigEdit -> "quick_config_edit:" + value.schemeId
        Route.QuickConfigButton -> "quick_config_button"
        Route.ZxCopyRules -> "zx_copy_rules"
        Route.ZxCopyButton -> "zx_copy_button"
        is Route.ZxCopyRuleEdit -> "zx_copy_rule_edit:" + value.ruleId
        Route.AiStareSaveBehavior -> "aistare_save_behavior"
        Route.WatchlistDetail -> "watchlist_detail"
        Route.StgDirectQuoteDetail -> "stg_direct_quote_detail"
        Route.Disclaimer -> "disclaimer"
        Route.Notice -> "notice"
    }

    override fun restore(value: String): Route = when {
        value == "quick_config" -> Route.QuickConfig
        value.startsWith("quick_config_edit:") ->
            Route.QuickConfigEdit(value.removePrefix("quick_config_edit:"))
        value == "quick_config_button" -> Route.QuickConfigButton
        value == "zx_copy_rules" -> Route.ZxCopyRules
        value == "zx_copy_button" -> Route.ZxCopyButton
        value.startsWith("zx_copy_rule_edit:") ->
            Route.ZxCopyRuleEdit(value.removePrefix("zx_copy_rule_edit:"))
        value == "aistare_save_behavior" -> Route.AiStareSaveBehavior
        value == "watchlist_detail" -> Route.WatchlistDetail
        value == "stg_direct_quote_detail" -> Route.StgDirectQuoteDetail
        value == "disclaimer" -> Route.Disclaimer
        value == "notice" -> Route.Notice
        else -> Route.Home
    }
}

/**
 * 三级页 → 它压在下面的那个二级页；不是三级页返回 null。
 *
 * 现在有两个三级页（方案编辑 / 复制规则编辑），返回目标、二级层画哪一页、
 * 可预测返回推哪一层，都从这里取，别再写死 `Route.QuickConfig`。
 */
private fun editParent(route: Route): Route? = when (route) {
    is Route.QuickConfigEdit -> Route.QuickConfig
    is Route.ZxCopyRuleEdit -> Route.ZxCopyRules
    else -> null
}

/**
 * 三层叠放 + 两条独立的滑动进度。
 *
 * ## 为什么三级页要单独一层
 *
 * 3.1.x 只有「一级 / 二级」两层，三级页（方案编辑）直接顶掉二级页的内容 ——
 * 于是两条问题：
 *
 *  - **进三级页没有任何动画**（内容原地替换）；
 *  - **从三级页可预测返回时，动画看着像要回一级页**：手势把整层二级页往右推，
 *    底下露出的是首页，手势提交后又把方案列表推回来 —— 用户看到的是
 *    「退回首页 → 又跳回列表」，而不是「编辑页滑走、列表在下面等着」。
 *
 * 现在二级、三级各有一条进度（`subProgress` / `editProgress`）：
 *
 * | 动作 | 二级页 | 三级页 |
 * |---|---|---|
 * | 进二级页 | 0 → 1 | 保持 0 |
 * | 进三级页 | 保持 1 | 0 → 1 |
 * | 三级页返回 | 保持 1（顺带 0.94 → 1 回正） | 1 → 0 |
 * | 二级页返回 | 1 → 0 | 已为 0 |
 *
 * 可预测返回手势只动**当前最上面那一层**的进度，所以从三级页往回滑，
 * 滑走的是编辑页，露出来的是方案列表 —— 与系统的返回预期一致。
 */
@Composable
internal fun MainScreen(initialRoute: Route = Route.Home) {
    val scope = rememberCoroutineScope()

    // 这两项必须**跨 Activity 重建**存活：转屏 / 分屏 / 折叠屏都会重建 Activity，
    // 用 remember 的话「在三级编辑页转个屏」就直接回到概览首页，正在填的方案白填
    // （审查 3.2.0 后第三轮发现）。Tab 是 enum（Serializable），自动存盘即可。
    var currentTab by rememberSaveable { mutableStateOf(Tab.MODULE) }
    var route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf<Route>(initialRoute) }

    // 首次启动的免责声明同意门槛：没同意就必须先同意。
    //
    // 为什么条件是「未同意 **且** 在首页」：点弹窗里的链接会跳到免责声明二级页，
    // 那一页要能正常看；回到首页时条件重新成立，弹窗自然又出来 ——
    // 不用额外的"暂不显示"标志位，状态越少越不容易出错。
    // 用 local 版：同意状态是**本机界面状态**，不进 remote preferences ——
    // 否则后端异步切换会让它在启动时先 false 后 true，弹窗"闪一下就没"。
    // 详见 ModulePreferences.getLocalBoolean 的注释。
    val agreedDisclaimer by rememberLocalBoolPref(
        ConfigKeys.DISCLAIMER_ACCEPTED,
        ConfigKeys.DEFAULT_DISCLAIMER_ACCEPTED,
    )
    val showAgreement = !agreedDisclaimer && route == Route.Home

    // 「这两层此刻该画哪一页」在**组合期**就算出来，不放进 LaunchedEffect：
    //
    //  - 进入时当帧就位 —— 放 effect 里会晚一帧（先空一帧再开始滑，看着像「打开慢」）；
    //  - 退出动画期间 `route` 已经不是它了，但那一层还得继续画完 —— 靠这两个变量记住。
    //
    // （组合期写 state 会让这一帧多跑一遍组合，值是确定的，最多两遍就收敛。）
    val editRouteNow: Route? = route.takeIf { editParent(it) != null }
    val subRouteNow = editParent(route) ?: route
    var shownSub by remember { mutableStateOf(subRouteNow) }
    var shownEdit by remember { mutableStateOf(editRouteNow) }
    if (subRouteNow != Route.Home && subRouteNow != shownSub) shownSub = subRouteNow
    if (editRouteNow != null && editRouteNow != shownEdit) shownEdit = editRouteNow

    val predictiveBack = rememberPredictiveBack()
    // 二级 / 三级各自的滑动进度：0 = 完全滑出屏幕右侧，1 = 完全盖住下面那层
    val subProgress = remember { Animatable(if (route == Route.Home) 0f else 1f) }
    val editProgress = remember { Animatable(if (editParent(route) != null) 1f else 0f) }

    // 返回目标：三级页回它自己的二级页，其余二级页回首页
    fun backTarget(): Route = editParent(route) ?: Route.Home

    // 常规前进 / 返回：把对应层的进度动画到目标值
    LaunchedEffect(route) {
        when (route) {
            Route.Home -> {
                // 三级页（若在）先滑走，再滑走二级页
                if (shownEdit != null) {
                    editProgress.animateTo(0f, tween(RouteAnimMillis))
                    shownEdit = null
                }
                subProgress.animateTo(0f, tween(RouteAnimMillis))
            }

            is Route.QuickConfigEdit, is Route.ZxCopyRuleEdit -> {
                subProgress.animateTo(1f, tween(RouteAnimMillis))
                editProgress.animateTo(1f, tween(RouteAnimMillis))
            }

            else -> {
                // 从三级页回到二级页：只动三级页，二级页留在原地。
                //
                // 判据是「三级层还在不在」，**不是**「进度 > 0」—— 3.1.1 修的就是这个：
                // 入场动画刚起步（甚至还没开始跑）时按返回，进度还是 0，原来会直接把
                // 那一层销毁掉，表现就是「按返回时入场动画凭空消失（页面瞬没）」。
                if (shownEdit != null) {
                    editProgress.animateTo(0f, tween(RouteAnimMillis))
                    shownEdit = null
                }
                subProgress.animateTo(1f, tween(RouteAnimMillis))
            }
        }
    }

    if (predictiveBack) {
        PredictiveBackHandler(enabled = route != Route.Home) { flow ->
            // 手势只推动最上面那一层：三级页返回时底下是它自己的二级页，不是首页
            val onEdit = editParent(route) != null
            try {
                flow.collect { (if (onEdit) editProgress else subProgress).snapTo(1f - it.progress) }
                route = backTarget()
            } catch (e: CancellationException) {
                // 手势取消：滑回去
                scope.launch { (if (onEdit) editProgress else subProgress).animateTo(1f, tween(200)) }
                throw e
            }
        }
    } else {
        BackHandler(enabled = route != Route.Home) { route = backTarget() }
    }

    val p = subProgress.value
    val pe = editProgress.value
    // 页面底色 = `colorScheme.surface`，**不是** `background`。
    //
    // Miuix 的取值很反直觉（0.9.3 实测）：
    //   |        | background | surface   | surfaceContainer（Card 默认色） |
    //   |--------|------------|-----------|-------------------------------|
    //   | 深色   | #242424    | #000000   | #242424                       |
    //   | 浅色   | #FFFFFF    | #F7F7F7   | #FFFFFF                       |
    // 也就是说 `background` 和 `surfaceContainer` **完全同色**。页面一旦用
    // `background`，卡片就和页面糊成一片、整页看不出分组（2.7.0 的回归）。
    // 正确的页面底色是 `surface`：深色纯黑 / 浅色浅灰，卡片才会跳出来。
    // 第 0 层底色层与 Scaffold 的 containerColor 都用它，保证「缩下去以后露出来的
    // 那一圈」和页面本身是同一个颜色。
    val background = MiuixTheme.colorScheme.surface

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 第 0 层：底色层（不参与任何手势动画）----
        //
        // 可预测返回会把一级页整体缩到 0.94 并淡到 0.65，缩下去以后四周会露出这一层。
        // 它必须**在最底层、且不挂任何 graphicsLayer 变换**，颜色跟着深浅模式走
        // （`MiuixTheme.colorScheme.surface`，深色纯黑 / 浅色浅灰），
        // 否则露出的是窗口底色或别的面色，看着就是「四周一圈灰边」。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background),
        )

        // ---- 第 1 层：一级首页（常驻底衬）----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f - 0.06f * p
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.35f * p
                },
        ) {
            HomeScaffold(
                currentTab = currentTab,
                onSelectTab = { currentTab = it },
                onOpenFeatureDetail = { id ->
                    // 功能 id → 二级菜单。新增带二级菜单的功能时在这里加一行。
                    route = when (id) {
                        "stg_direct_quote" -> Route.StgDirectQuoteDetail
                        "aistare_quick_config" -> Route.QuickConfig
                        // 第二个入口：配置按钮自己的设置（多方案时是否弹选择层）
                        "aistare_quick_config#2" -> Route.QuickConfigButton
                        // 保存成功后：是否直接退回个股详情页
                        "aistare_skip_save_confirm" -> Route.AiStareSaveBehavior
                        "zx_group_copy" -> Route.ZxCopyRules
                        "zx_group_copy#2" -> Route.ZxCopyButton
                        else -> Route.WatchlistDetail
                    }
                },
                onOpenDisclaimer = { route = Route.Disclaimer },
                onOpenNotice = { route = Route.Notice },
                showAgreement = showAgreement,
                onAgreeDisclaimer = {
                    ModulePreferences.setLocalBoolean(ConfigKeys.DISCLAIMER_ACCEPTED, true)
                },
            )
        }

        // ---- 第 2 层：二级页（右滑进出）----
        if (route != Route.Home || p > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // p = 1 时完全覆盖（位移 0），p = 0 时滑到屏幕右侧之外
                    .graphicsLayer { translationX = size.width * (1f - p) }
                    .background(background),
            ) {
                // 二级页本体：被三级页盖住时缩一点（可预测返回时随手势回正）。
                // 缩放只在**这一层**，不能挂到外层 Box —— 否则三级页会跟着一起缩。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 只有三级页盖上来时（pe > 0）才挂缩放层：`graphicsLayer` 一旦挂上
                        // 就是一张全屏离屏图层，「打开三级页要画三张整屏」本来就不便宜，
                        // 平时这一层是恒等变换，白白多一层（3.1.1 打开速度优化）。
                        .then(
                            if (pe > 0.001f) {
                                Modifier.graphicsLayer {
                                    val s = 1f - 0.06f * pe
                                    scaleX = s
                                    scaleY = s
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    when (shownSub) {
                        Route.WatchlistDetail -> SubScreen(
                            title = stringResource(R.string.detail_color_title),
                            onBack = { route = Route.Home },
                        ) {
                            WatchlistDetailScreen()
                        }

                        Route.StgDirectQuoteDetail -> SubScreen(
                            title = stringResource(R.string.detail_stg_direct_title),
                            onBack = { route = Route.Home },
                        ) {
                            StrategyQuoteDetailScreen()
                        }

                        Route.QuickConfig -> SubScreen(
                            title = stringResource(R.string.detail_quick_config_title),
                            onBack = { route = Route.Home },
                        ) {
                            QuickConfigScreen(
                                onEdit = { schemeId -> route = Route.QuickConfigEdit(schemeId) },
                            )
                        }

                        Route.QuickConfigButton -> SubScreen(
                            title = stringResource(R.string.feature_quick_config_button_entry),
                            onBack = { route = Route.Home },
                        ) {
                            QuickConfigButtonScreen()
                        }

                        Route.ZxCopyRules -> SubScreen(
                            title = stringResource(R.string.detail_zx_copy_title),
                            onBack = { route = Route.Home },
                        ) {
                            ZxCopyRuleScreen(
                                onEdit = { ruleId -> route = Route.ZxCopyRuleEdit(ruleId) },
                            )
                        }

                        Route.ZxCopyButton -> SubScreen(
                            title = stringResource(R.string.detail_zx_copy_button_title),
                            onBack = { route = Route.Home },
                        ) {
                            ZxCopyButtonScreen()
                        }

                        Route.AiStareSaveBehavior -> SubScreen(
                            title = stringResource(R.string.feature_skip_save_detail_title),
                            onBack = { route = Route.Home },
                        ) {
                            AiStareSaveBehaviorScreen()
                        }

                        Route.Disclaimer -> SubScreen(
                            title = stringResource(R.string.disclaimer_detail_title),
                            onBack = { route = Route.Home },
                        ) {
                            DisclaimerScreen()
                        }

                        Route.Notice -> SubScreen(
                            title = stringResource(R.string.notice_detail_title),
                            onBack = { route = Route.Home },
                        ) {
                            NoticeScreen()
                        }

                        is Route.QuickConfigEdit -> Unit
                        is Route.ZxCopyRuleEdit -> Unit
                        Route.Home -> Unit
                    }
                }

                // ---- 第 3 层：三级页（叠在二级页之上，只自己滑）----
                val editRoute = shownEdit
                if (editRoute != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = size.width * (1f - pe) }
                            .background(background),
                    ) {
                        when (editRoute) {
                            is Route.QuickConfigEdit -> SubScreen(
                                title = stringResource(R.string.detail_quick_edit_title),
                                onBack = { route = Route.QuickConfig },
                            ) {
                                QuickConfigEditScreen(
                                    schemeId = editRoute.schemeId,
                                    // 方案被删掉就回列表，不停在空白的三级页
                                    onDeleted = { route = Route.QuickConfig },
                                )
                            }

                            is Route.ZxCopyRuleEdit -> SubScreen(
                                title = stringResource(R.string.detail_zx_copy_edit_title),
                                onBack = { route = Route.ZxCopyRules },
                            ) {
                                ZxCopyRuleEditScreen(
                                    ruleId = editRoute.ruleId,
                                    onDeleted = { route = Route.ZxCopyRules },
                                )
                            }

                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
