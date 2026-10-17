package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一级页面。
 *
 * 「液态玻璃」是一个**总开关**（[ConfigKeys.LIQUID_GLASS]），一次决定两处表面：
 *
 * | | 开（玻璃） | 关（纯色） |
 * |---|---|---|
 * | 顶栏 | [GlassTopBar]，模糊 + 半透明面色，内容从它下面穿过 | Miuix 默认 `SmallTopAppBar`，纯色 |
 * | 底栏 | [LiquidGlassDock]，悬浮胶囊，**下滑自动收起** | `MiuixDock`，贴边 |
 * | 内容顶部 | 铺到屏幕顶边，由各 Tab 用 [LocalTopInset] 补一段首屏空白 | 整体下移一个顶栏高度 |
 *
 * 底栏两种形态里，只有悬浮的那种会「挡内容」，所以自动收起只对它生效。
 *
 * ## 悬浮底栏为什么放在 `Scaffold` 的 content **里面**
 *
 * 自绘的 `DialogHost`（`ui/AppDialog.kt`）挂在 content 的 `Box` 里，是 content 的最后一个子节点。
 * 如果一个浮层挂在 `Scaffold` **外面**，它会排在 `Scaffold` 之后绘制 ——
 * 于是盖住 content 内的 Dock 与 `DialogHost`。所以 Dock 放进 content 的 Box 里，
 * 用 `align(BottomCenter)` 浮在底部，并排在 `DialogHost` 之前。
 * （`DialogHost` 自己必须是最后一个子节点，才能压住包括 Dock 在内的一切。）
 *
 * ## 底部占位与导航条沉浸
 *
 *  - Miuix 底栏：`bottomBar` 已经把自己高度算进 `padding`
 *  - 液态底栏：内容**不再**被整体上推，而是由各 Tab 在滚动内容末尾补一段
 *    等高的空白（[LocalDockInset]），这样列表能滚到底栏之下、玻璃才有东西可采样；
 *    同时**吃掉 Scaffold 给的底部 inset**，让内容一直铺到屏幕最底边，
 *    系统手势导航条区域就能透出内容（沉浸），而不是被主题背景色填成一条纯色带。
 *
 * ## 顶栏玻璃为什么需要内容下穿
 *
 * 玻璃的本质是「把身后的内容采样回来做模糊」。如果内容像以前那样整体下移一个
 * 顶栏高度，顶栏背后永远是空的，采样出来只是一块平色 —— 看着还是纯色块，
 * 白折腾。所以玻璃模式下内容铺到 y=0，首屏空白改由各 Tab 在滚动内容开头补
 * [LocalTopInset]，于是「首屏从顶栏下方开始」和「滚上去的内容从玻璃后面穿过」
 * 同时成立。
 */
@Composable
internal fun HomeScaffold(
    currentTab: Tab,
    onSelectTab: (Tab) -> Unit,
    onOpenFeatureDetail: (String) -> Unit,
    onOpenDisclaimer: () -> Unit,
    onOpenNotice: () -> Unit,
    /** 首次启动未同意免责声明时为 `true`：弹硬门槛弹窗 */
    showAgreement: Boolean,
    /** 用户点了「确定」（同意免责声明） */
    onAgreeDisclaimer: () -> Unit,
) {
    val liquidGlass by rememberBoolPref(ConfigKeys.LIQUID_GLASS, ConfigKeys.DEFAULT_LIQUID_GLASS)
    val glassEffect by rememberBoolPref(ConfigKeys.GLASS_EFFECT, ConfigKeys.DEFAULT_GLASS_EFFECT)
    // 页面底色 = `colorScheme.surface`（深色纯黑 / 浅色浅灰）。
    // **不要**用 `colorScheme.background`：Miuix 里它和 `Card` 的默认色
    // `surfaceContainer` 完全同色，页面一换卡片就整片消失（详见 MainScreen 的注释）。
    val background = MiuixTheme.colorScheme.surface

    // 两个「选择类」设置项（深色模式 / 语言）与重置确认
    // 都走自绘弹窗，宿主统一在这里渲染（见 ui/AppDialog.kt）。
    val dialogs = remember { DialogController() }

    // --- 首次启动的免责声明同意弹窗 ---
    //
    // 文案先全部取出来：弹窗的 spec 是普通对象，不能在 lambda 里调 stringResource。
    val agreementTitle = stringResource(R.string.agreement_title)
    val agreementMessage = stringResource(R.string.agreement_message)
    val agreementCheckPrefix = stringResource(R.string.agreement_check_prefix)
    val agreementCheckLink = stringResource(R.string.agreement_check_link)
    val agreementCheckSuffix = stringResource(R.string.agreement_check_suffix)
    val agreementConfirm = stringResource(R.string.agreement_confirm)

    LaunchedEffect(showAgreement) {
        if (showAgreement) {
            dialogs.showAgreement(
                AgreementDialogSpec(
                    title = agreementTitle,
                    message = agreementMessage,
                    checkPrefix = agreementCheckPrefix,
                    checkLinkText = agreementCheckLink,
                    checkSuffix = agreementCheckSuffix,
                    confirmText = agreementConfirm,
                    // 点链接：跳二级页看全文。这里**不动** showAgreement，
                    // route 一变它自己就为 false，弹窗随之收起。
                    onOpenDisclaimer = onOpenDisclaimer,
                    onConfirm = onAgreeDisclaimer,
                )
            )
        } else if (dialogs.spec is AgreementDialogSpec) {
            // 同意之后（或已经跳去看全文时）把弹窗收掉
            dialogs.dismiss()
        }
    }

    // --- 滑动自动收起：状态 + 把滚动事件喂给它的嵌套滚动连接 ---
    val dockScroll = rememberDockScrollState()
    val density = LocalDensity.current
    // 手指抖一下不该让底栏闪，所以要累计够一段同向滚动才翻转
    val hideThresholdPx = remember(density) { with(density) { DockHideThreshold.toPx() } }
    val scrollConnection = remember(dockScroll, hideThresholdPx) {
        dockScrollConnection(dockScroll, hideThresholdPx)
    }
    // 切板块 / 关掉玻璃时回到「不收起」—— 新板块从顶部开始，底栏本来就该在
    LaunchedEffect(currentTab, liquidGlass) { dockScroll.reset() }

    // 直接读属性即可：`hidden` 本身由 `mutableStateOf` 支撑，读它就订阅了变化
    val hidden = dockScroll.hidden

    // 点击内容区空白处时收起输入框焦点（键盘随之收起）。
    //
    // Compose 里 TextField 的焦点只有两条释放路径：被别的可聚焦组件抢走，或显式
    // `clearFocus()`。点在卡片 / 开关上没问题 —— 它们会消费点击；但点在列表
    // 空白、页面边缘这些「没人要」的位置，事件一路冒泡都无人消费，焦点就赖着
    // 不走 —— 表现为搜索框的高亮只能靠切板块才能摘掉。
    // 所以在内容容器上挂一个 tap 探测兜底：子级消费了点击（卡片 / 开关 /
    // 搜索框自己）就不会触发，只有真正点在空白处才清焦点。
    val focusManager = LocalFocusManager.current

    val hideFraction by animateFloatAsState(
        targetValue = if (hidden) 1f else 0f,
        animationSpec = tween(DockHideMillis),
        label = "dock-hide",
    )
    // 收起时要整条移出屏幕：胶囊高度 + 外边距 + 手势导航条（留足 56dp 够任何机型）
    val hideDistancePx = remember(density) {
        with(density) { (DockHeight + DockMargin + 56.dp).toPx() }
    }

    // 底栏形态切换的淡入淡出（声明上提到 backdrop 之前，见下方 needsBackdrop 的注释）
    val dockAlpha by animateFloatAsState(
        targetValue = if (liquidGlass) 1f else 0f,
        animationSpec = tween(220),
        label = "dock-alpha",
    )

    // 关于页整块 Hero 要吃到状态栏后面，顶栏留着会把渐变的头部齐齐截断 ——
    // 切到关于时顶栏向上滑出并淡出，切走时滑回来。
    //
    // ⚠️ 这条进度**只驱动顶栏自己**（位移 + 透明度），不要再拿去缩内容的顶部留白：
    // 留白是布局参数，一挂上动画，留白会在 220ms 里逐帧变化，于是切换板块时
    // 离场那一页会被持续重排 —— 关于页退出时整块内容被往下推，叠上横向滑动就是
    // 「从左上往左下消失」的斜向动作。内容的留白改成按板块取值（见下方
    // AnimatedContent 里的 tabTopInset），两页各用各的定值，谁都不重排。
    val topBarHide by animateFloatAsState(
        targetValue = if (currentTab == Tab.ABOUT) 1f else 0f,
        animationSpec = tween(TopBarHideMillis),
        label = "topbar-hide",
    )

    Box(Modifier.fillMaxSize()) {
        // 悬浮底栏与顶栏玻璃都要采样「内容」才能做出折射 / 模糊，所以把内容录进图层
        val backdrop = rememberLayerBackdrop {
            drawRect(background)
            drawContent()
        }

        // 只有玻璃消费方在场时才需要录图层（审查 2026-10-17）。
        // backdrop 的消费方只有玻璃形态的 GlassTopBar / LiquidGlassDock / 弹窗卡片，
        // 全部跟着 liquidGlass 开关走；liquidGlass=false 时挂着等于每帧多录一张
        // 全屏离屏图层却无人采样。写法与 SubScreen 的条件挂载（见下方）保持一致：
        // backdrop 实例始终创建、各消费方引用同一实例，只是不挂 `.layerBackdrop` 修饰符。
        // 条件额外包含 dockAlpha > 0.005f：关玻璃时底栏走淡出动画，动画期间
        // LiquidGlassDock 仍在采样，此时不能把图层提前摘掉，否则淡出瞬间采到空图层。
        val needsBackdrop = liquidGlass || dockAlpha > 0.005f

        Scaffold(
            // 显式指定容器色 = 页面底色。Miuix `Scaffold` 的默认值恰好就是 `surface`，
            // 这里写出来是为了把「页面底色 = surface」这条约定固定在调用点上。
            containerColor = background,
            topBar = {
                val title = stringResource(R.string.app_name)
                val subtitle = stringResource(currentTab.subtitleRes)
                // 搜索框 2.9.3 起移进「功能」列表顶部（随列表一起滚动），
                // 顶栏只剩标题 —— 理由见 FeatureTab 的注释。
                //
                // 关于页把整条顶栏往上滑出去（见上方 topBarHide）：Hero 要吃到
                // 状态栏后面，顶栏留着会把渐变头部截成一条直线。
                //
                // 这里**只做位移、不做透明度淡出**，而且要比栏高多滑一段（TopBarShadowOut）：
                //   - 投影画在栏体下边缘之外，只平移一个栏高它仍会露在屏幕顶端，
                //     多滑的这段正好把投影一起送出屏；
                //   - alpha < 1 会让这一层走离屏合成，离屏缓冲按栏体边界裁剪，
                //     边界之外的投影在动画全程被裁没、结束帧 alpha 回到 1 时一帧弹回
                //     （「投影 1 帧出现」的根因）。纯位移下投影每帧都随栏体平移。
                Box(
                    modifier = Modifier.graphicsLayer {
                        translationY = -(size.height + TopBarShadowOut.toPx()) * topBarHide
                    },
                ) {
                    if (liquidGlass) {
                        GlassTopBar(
                            backdrop = backdrop,
                            effect = glassEffect,
                            title = title,
                            subtitle = subtitle,
                        )
                    } else {
                        SmallTopAppBar(title = title, subtitle = subtitle)
                    }
                }
            },
            bottomBar = {
                // 两种形态之间做交叉淡入淡出，避免切换时生硬跳变
                AnimatedContent(
                    targetState = liquidGlass,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "dock-form",
                ) { liquid ->
                    if (!liquid) MiuixDock(currentTab, onSelectTab)
                }
            },
        ) { padding ->
            // 顶栏（含状态栏）占的高度。它只是**一个尺寸**，不再是动画值：
            // 哪个板块该让多少留白，由下方 AnimatedContent 按板块各取一份（见 tabTopInset）
            val topBarSpace = padding.calculateTopPadding()
            CompositionLocalProvider(
                // 设置页的选择项 / 重置确认都从自绘弹窗走，控制器在这里下发
                LocalDialogController provides dialogs,
                LocalDockInset provides if (liquidGlass) DockHeight + DockMargin else 0.dp,
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                // 液态底栏时不留底部 inset，内容铺到屏幕最底边（导航条沉浸）
                                bottom = if (liquidGlass) 0.dp else padding.calculateBottomPadding(),
                            )
                            // 只在玻璃消费方在场时录图层（见上方 needsBackdrop 的注释），
                            // 条件挂载与 SubScreen 的写法保持一致
                            .then(if (needsBackdrop) Modifier.layerBackdrop(backdrop) else Modifier)
                            // 只观测滚动方向，不消费增量（返回 Offset.Zero），滚动本身不受影响
                            .nestedScroll(scrollConnection)
                            // 点击空白处清掉输入框焦点（见上方 focusManager 处的注释）。
                            // 手指拖动滚动超过 touch slop 就不再是 tap，不影响列表滚动。
                            .pointerInput(focusManager) {
                                detectTapGestures(onTap = { focusManager.clearFocus() })
                            },
                    ) {
                        // 四个板块切换时淡入 + 轻微横移，避免生硬跳变
                        //
                        // 进场与离场**只做横向位移**，不动尺寸、不动布局参数：
                        // AnimatedContent 在切换期间会同时挂着新旧两页，任何跟着
                        // 全局动画走的东西（顶部留白、内边距）都会让离场那一页持续重排，
                        // 横向滑动就被叠成一条斜线（关于页退出时的老问题）。
                        AnimatedContent(
                            targetState = currentTab,
                            transitionSpec = {
                                val forward = targetState.ordinal > initialState.ordinal
                                val enter = slideInHorizontally(tween(260)) { if (forward) it / 8 else -it / 8 } +
                                        fadeIn(tween(220))
                                val exit = slideOutHorizontally(tween(260)) { if (forward) -it / 8 else it / 8 } +
                                        fadeOut(tween(160))
                                enter togetherWith exit
                            },
                            modifier = Modifier.fillMaxSize(),
                            label = "tab",
                        ) { tab ->
                            // 顶部留白按**板块**取定值，不跟顶栏收起进度联动：
                            //   - 关于页：0（Hero 整块铺到状态栏后面）
                            //   - 其余：整条顶栏的高度
                            // 两页各拿各的值，切换时谁都不会因为对页的状态而重排 ——
                            // 顶栏该上滑还是下滑，由它自己的动画负责（见 topBarHide）。
                            val tabTopInset = if (tab == Tab.ABOUT) 0.dp else topBarSpace
                            CompositionLocalProvider(
                                // 玻璃模式下内容铺到屏幕顶边，首屏空白交给各 Tab 自己补（见上方注释）
                                LocalTopInset provides if (liquidGlass) tabTopInset else 0.dp,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = if (liquidGlass) 0.dp else tabTopInset),
                                ) {
                                    when (tab) {
                                        Tab.MODULE -> ModuleTab()
                                        Tab.FEATURE -> FeatureTab(
                                            onOpenDetail = { spec, second ->
                                                onOpenFeatureDetail(if (second) spec.id + "#2" else spec.id)
                                            },
                                        )
                                        Tab.SETTINGS -> SettingsTab()
                                        Tab.ABOUT -> AboutTab(
                                            onOpenDisclaimer = onOpenDisclaimer,
                                            onOpenNotice = onOpenNotice,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 悬浮底栏：放在 content 内、弹窗宿主之下。
                    //
                    // ⚠️ `Modifier.align(...)` 必须挂在这里（外层 Box 的**直接子节点**上）。
                    // 曾经把它写在 AnimatedContent 的 content lambda 里，结果底栏被丢到
                    // 内容区左上角、被顶栏盖住 —— 看着就是「底栏消失了」。
                    // 原因：align 是 BoxScope 的 parent data，只有 Box 的直接子节点才会被读到；
                    // AnimatedContent 内部是自己的 Layout，既不读也不转发这份 parent data。
                    //
                    // 这里也刻意**不用** AnimatedContent：它的 SizeTransform 默认 clip = true，
                    // 会把长按气泡（画在胶囊上方 -38dp 处）切掉。用透明度过渡更稳。
                    // （dockAlpha 声明上提到了 needsBackdrop 之前，见其注释。）
                    if (dockAlpha > 0.005f) {
                        LiquidGlassDock(
                            backdrop = backdrop,
                            effect = glassEffect,
                            current = currentTab,
                            onSelect = onSelectTab,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .graphicsLayer {
                                    // 开关玻璃的淡入淡出 × 下滑收起的淡出，两个效果相乘
                                    alpha = dockAlpha * (1f - hideFraction)
                                    // 收起时整条滑到屏幕下方之外
                                    translationY = hideFraction * hideDistancePx
                                }
                                .navigationBarsPadding()
                                .padding(start = 24.dp, end = 24.dp, bottom = DockMargin),
                        )
                    }
                }
            }
        }

        // 弹窗宿主：挂在**最外层** Box 的最后一个子节点。
        //
        // ① 层级：压住顶栏、底栏与全部内容；
        // ② 坐标系：锚点是 `boundsInWindow()`（窗口坐标），最外层 Box 的原点就是
        //    window 原点；放进 `Scaffold` 的 content 里会被顶栏高度整体偏移。
        //
        // 两种弹窗（锚定菜单 / 贴底确认与同意）都自绘，理由见 ui/AppDialog.kt 头注。
        // （3.1.0 曾试过把贴底弹窗换成 Miuix `OverlayBottomSheet`，因观感与动画
        // 手感不合按用户要求回退为自绘；activity-compose ≥ 1.12 的升级保留 ——
        // 它顺带补上了 ViewTree NavigationEventDispatcherOwner 集成。）
        DialogHost(
            controller = dialogs,
            backdrop = backdrop,
            liquidGlass = liquidGlass,
            effect = glassEffect,
        )
    }
}

/**
 * 二级页面外壳：带返回按钮的顶栏 + 内容区（位移由上层图层负责）。
 *
 * ## 手势导航条（小白条）沉浸
 *
 * 内容区**只让开顶部**，底部 `0.dp` —— 一直铺到屏幕物理最底边，手势条区域透出内容。
 * 如果像以前那样也 `padding.calculateBottomPadding()`，最后那一小条就永远是空的，
 * 表现就是「小白条下面被填了一块纯色」，即不沉浸。
 *
 * 不让位的代价是「最后一项可能压在导航条上」，所以由外壳把导航条高度通过
 * [LocalNavBarInset] 下发，各二级页在滚动内容末尾补 `24.dp + LocalNavBarInset.current`
 * 的空白 —— 和一级页用 `LocalDockInset` 给悬浮底栏让位是同一个套路。
 *
 * 顶部仍让位，是因为顶栏用的是 `SmallTopAppBar`，它的高度里已经含了状态栏 inset。
 */
@Composable
internal fun SubScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 二级页自己的弹窗宿主（3.1.0 起）。
    // 一级页的 DialogController 在 HomeScaffold 里，二级页不在其内容树里拿不到 ——
    // 「一键配置」的方案菜单 / 删除确认需要弹层，所以每个二级页自带一份。
    val dialogs = remember { DialogController() }

    // 弹窗材质跟一级页同一个开关（3.1.1 修）：3.1.0 这里写死纯色，
    // 于是同一颗 ⋮ 菜单在一级页是玻璃、在二级页是灰卡片，两处观感对不上。
    // 玻璃要采样身后的画面，所以二级页也把内容录一个图层（与 HomeScaffold 同一套路）。
    val liquidGlass by rememberBoolPref(ConfigKeys.LIQUID_GLASS, ConfigKeys.DEFAULT_LIQUID_GLASS)
    val glassEffect by rememberBoolPref(ConfigKeys.GLASS_EFFECT, ConfigKeys.DEFAULT_GLASS_EFFECT)
    val background = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }

    // 点击内容区空白处清掉输入框焦点（3.1.1）。一级页在 HomeScaffold 里有一份同样的兜底，
    // 二级页当时漏了 —— 表现为「方案名称 / 数值输入框点开后，点别处光标还在闪」。
    // 卡片 / 开关 / 输入框自己会消费点击，只有真正点在空白处才会走到这里。
    val focusManager = LocalFocusManager.current

    // 二级页的内容只有**弹窗打开时**才需要被采进 backdrop 图层（玻璃卡片要采它身后的
    // 画面）。平时挂着等于每帧多录一张全屏图层 —— 弹窗关了就把这一层摘掉（3.1.1）。
    val needsBackdrop = dialogs.spec != null

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (needsBackdrop) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            Scaffold(
                // 与一级页同源，保证二级页与底衬颜色一致（见 HomeScaffold 的注释）
                containerColor = background,
                topBar = {
                    SmallTopAppBar(
                        title = title,
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = AppIcons.ArrowBack,
                                    contentDescription = stringResource(R.string.common_back),
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                CompositionLocalProvider(
                    LocalNavBarInset provides navBarInset,
                    LocalDialogController provides dialogs,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = padding.calculateTopPadding())
                            .pointerInput(focusManager) {
                                detectTapGestures(onTap = { focusManager.clearFocus() })
                            },
                    ) {
                        content()
                    }
                }
            }
        }

        // 锚定菜单（⋮ 动作 / 取值项选择 / 聚合与周期选择）与贴底确认弹窗：挂最外层，
        // 锚点是 boundsInWindow() 窗口坐标，放 Scaffold content 里会被顶栏偏移
        DialogHost(
            controller = dialogs,
            backdrop = backdrop,
            liquidGlass = liquidGlass,
            effect = glassEffect,
        )
    }
}

/** 免责声明完整文本（二级页内容） */
@Composable
internal fun DisclaimerScreen() {
    DisclaimerContent()
}

/** 第三方组件声明（二级页内容，读随 APK 分发的 NOTICE） */
@Composable
internal fun NoticeScreen() {
    NoticeContent()
}

// 悬浮底栏的尺寸常量：占位计算与 Dock 本体共用，避免两处写死对不上
internal val DockHeight = 64.dp
internal val DockMargin = 12.dp

/** 悬浮底栏「下滑收起」前需要累计的同向滚动量 —— 太小会因手指抖动反复闪 */
internal val DockHideThreshold = 24.dp

/** 悬浮底栏收起 / 放出的动画时长 */
private const val DockHideMillis = 240

/**
 * 关于页顶栏上滑收起的时长。
 *
 * 比板块切换的 260ms 略短一点：顶栏是「让路」，先走完一半再让内容顶上来，
 * 观感上就不会出现「顶栏还挂在上面、Hero 已经贴上去了」的错位。
 */
private const val TopBarHideMillis = 220

/**
 * 顶栏收起时额外多滑出的距离 —— 盖过玻璃投影在栏体下边缘之外的延伸
 * （`Shadow.Default` 半径量级），保证收起后连投影一起完全出屏、
 * 顶部不留一条残影。
 */
internal val TopBarShadowOut = 24.dp
