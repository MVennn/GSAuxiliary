package io.github.MVennn.GSAuxiliary.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.LocaleHelper
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块的设置界面（Miuix Compose）。
 *
 * 骨架：
 *   Scaffold(SmallTopAppBar) + 4 个 Tab 内容 + Dock（两种形态，见 [MiuixDock] / [LiquidGlassDock]）
 * 二级页面：功能详情、日志、免责声明，用一层极简路由（[Route]）切，不引入导航库。
 *
 * **布局分两层**（从下到上）：
 *   1. 一级首页 [HomeScaffold] —— 常驻，作为二级页下方的底衬（可预测返回时露出来）
 *   2. 二级页 [SubScreen] —— 按 [Animatable] 进度右滑进出，不裁剪、不闪白
 *
 * 系统栏（状态栏 / 手势导航条）的沉浸处理在 [SyncSystemBars]，理由见该函数。
 *
 * 弹层走 [AppDialog] 的**自绘** `DialogHost`（2.8.0 起），**不用** Miuix 的
 * `OverlayDropdownPreference` / `OverlayDialog` —— 后者在库内部的 `onClick` 里
 * 同步开始收起动画，与同步回写的配置状态打架会闪退，调用侧无法规避。
 * 详见 `ui/AppDialog.kt` 顶部注释与 `docs/UI.md` §1.3。
 */
class SettingsActivity : ComponentActivity() {

    /**
     * 语言设置在 `attachBaseContext` 生效，所以必须在这里包装 Context。
     * [LocaleHelper] 读的是 `ModulePreferences`，此时 `Application.onCreate` 已跑过，配置可用。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    /**
     * 系统栏一律全透明，让内容铺到屏幕物理边缘（沉浸）。
     *
     * 显式传 `SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)` 而不是用默认值，
     * 是为了把「底色透明」这件事写在调用点上，不依赖库的默认值。
     *
     * 但**光这样还不够**：`SystemBarStyle.auto` 的 `nightMode` 是 `MODE_NIGHT_AUTO`，
     * androidx 的 `EdgeToEdgeApi29.setUp` 遇到这个值会顺手执行
     * `window.navigationBarContrastEnforced = true`。系统随后就会在透明的导航栏上
     * 叠一层半透明遮罩 —— 这正是「手势条那一条是纯色填充」的来源。
     * 所以下面再明确关一次（主题层也关了一份，双保险）。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 「导航栏强制对比度」= 透明导航栏上的那层系统遮罩，必须关掉才叫沉浸
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        syncTaskDescription()
        setContent {
            AppTheme {
                MainScreen()
            }
        }
    }

    /**
     * 把「最近任务」里的图标 / 名称同步成**当前**的。
     *
     * 现象：换掉 launcher 图标之后，桌面已经显示新图标，但**切到后台（最近任务）**
     * 那一栏还是旧图标 —— 因为系统的最近任务快照用的是
     * `ActivityManager.TaskDescription`，它是系统按 Activity 首次创建时的信息缓存的，
     * 不会跟着 `mipmap` 资源变。
     *
     * 这里每次 `onCreate` 都用 `PackageManager` 现取的**应用图标位图**重建一次任务描述，
     * 覆盖掉那份缓存。（`TaskDescription(String, Bitmap)` 是 API 21+，minSdk 27 无碍。）
     */
    private fun syncTaskDescription() {
        val bitmap = runCatching {
            packageManager.getApplicationIcon(packageName).toBitmap()
        }.getOrNull() ?: return
        runCatching {
            setTaskDescription(ActivityManager.TaskDescription(getString(R.string.app_name), bitmap))
        }
    }
}

/** 主题切换时遮罩淡出的时长 */
private const val ThemeFadeMillis = 320

/**
 * 主题 + 本地化。
 *
 *  - 主题设置：跟随系统 / 亮色 / 暗色（见 [rememberDarkTheme]）
 *  - 语言：**在 Compose 层重新提供 [LocalContext]**，语言一变 `stringResource` 立刻生效，
 *    因此不需要 `recreate()`。这一点很关键 —— 原先在 Miuix 下拉弹层的回调里
 *    直接 `recreate()`，会在弹层收起动画进行到一半时销毁 Activity，导致闪退。
 *    （`LocaleHelper.wrap` 返回的是保留了 `baseContext` 链的 `ContextWrapper`，
 *    不会打断 androidx 的宿主查找，详见该类注释。）
 *  - 深浅色切换：Miuix 的 `Colors` 有 53 个字段且 setter 是 `internal`，外部无法逐字段插值，
 *    所以改用「**旧配色整屏盖一层 → 淡出**」的方式做过渡。
 *
 * ## 闪屏是怎么来的、怎么修的（2.9.0）
 *
 * 现象：深色切浅色会「先变白 → 再闪黑 → 最后变白」，反向同理。
 * 根因是**配色与遮罩不在同一帧生效**，而且遮罩节点是条件挂载的：
 *
 *  1. `MiuixTheme(colors = if (dark) …)` 直接吃了**目标值**。用户一选「亮色」，
 *     整棵树当帧就变白 —— 这就是「先变白」。
 *  2. `LaunchedEffect` 里才把遮罩铺上旧色（黑），于是「再闪黑」。
 *  3. 遮罩写成 `if (veil != null) Box(...)`，**节点是临时插进树里的**，
 *     插进去那一帧还没被画出来 —— 所以新配色会先裸露一帧。
 *
 * 修法三条，缺一不可：
 *  - 新增 `shownDark`（**真正在渲染的**配色），`MiuixTheme` / [SyncSystemBars] /
 *    [LocalDarkTheme] 全部只认它；`dark` 降级成「目标值」，只在遮罩就位后才被采纳。
 *  - 遮罩节点**常驻**（`veilAlpha` 为 0 时完全透明，也不消费触摸），
 *    不再有条件挂载那一帧的空窗。
 *  - 换配色前先 `withFrameNanos { }` 等一帧，确保「旧色遮罩已经画出来」，
 *    再改 `shownDark` —— 于是「换色」和「遮罩」永远落在同一帧。
 */
@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = rememberDarkTheme()
    val language by rememberStringPref(ConfigKeys.LANGUAGE, ConfigKeys.DEFAULT_LANGUAGE)
    val base = LocalContext.current
    val localized = remember(base, language) { LocaleHelper.wrap(base) }

    // 缓存两套配色：既避免每次重组都重建 Colors，也让过渡遮罩能取到旧配色的背景色
    val lightColors = remember { lightColorScheme() }
    val darkColors = remember { darkColorScheme() }

    var shownDark by remember { mutableStateOf(dark) }
    var veilColor by remember { mutableStateOf(Color.Transparent) }
    val veilAlpha = remember { Animatable(0f) }

    LaunchedEffect(dark) {
        if (shownDark == dark) return@LaunchedEffect
        // 1) 先用**旧**配色的底色铺满（此刻新旧同色，肉眼看不出变化）
        veilColor = if (shownDark) darkColors.surface else lightColors.surface
        veilAlpha.snapTo(1f)
        // 2) 等这一帧画完再换配色，保证「换色」与「遮罩」同帧
        withFrameNanos { }
        shownDark = dark
        // 3) 遮罩淡出 —— 视觉上就是从旧配色平滑过渡到新配色
        veilAlpha.animateTo(0f, tween(ThemeFadeMillis))
    }

    MiuixTheme(colors = if (shownDark) darkColors else lightColors) {
        // 系统栏沉浸：手势条 / 状态栏区域必须让内容透出来，而不是被系统
        // 或窗口底色填成一条纯色带。详见 ui/SystemBars.kt。
        SyncSystemBars(dark = shownDark, background = MiuixTheme.colorScheme.surface)

        CompositionLocalProvider(
            LocalContext provides localized,
            // 液态玻璃的「面色」要看深浅色（黑色模式黑玻璃 / 白色模式白玻璃）。
            // 这里已经算过一次 `shownDark` 了，往下传，省得各处再订阅一遍主题配置。
            LocalDarkTheme provides shownDark,
        ) {
            Box(Modifier.fillMaxSize()) {
                content()
                // 过渡遮罩：**常驻节点**，靠 alpha 控制显隐。
                // 透明时既看不见、也不消费触摸（Box 本身不拦截指针事件）。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(veilColor.copy(alpha = veilAlpha.value)),
                )
            }
        }
    }
}

/** 极简路由：一级首页 + 四个二级页 */
private sealed interface Route {
    data object Home : Route
    data object WatchlistDetail : Route
    data object StgDirectQuoteDetail : Route
    data object LogViewer : Route
    data object Disclaimer : Route
}

/** 一级 → 二级的滑动进度：0 = 只显示一级，1 = 二级完全覆盖 */
private const val RouteAnimMillis = 300

@Composable
private fun MainScreen() {
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(Tab.MODULE) }
    var route by remember { mutableStateOf<Route>(Route.Home) }

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
    // 退出动画期间 `route` 已经回到 Home，仍需知道「刚才那个二级页」是谁
    var lastSub by remember { mutableStateOf<Route>(Route.WatchlistDetail) }
    if (route != Route.Home) lastSub = route

    val predictiveBack = rememberPredictiveBack()
    val progress = remember { Animatable(if (route == Route.Home) 0f else 1f) }

    // 常规前进 / 返回：把进度动画到目标值
    LaunchedEffect(route) {
        progress.animateTo(if (route == Route.Home) 0f else 1f, tween(RouteAnimMillis))
    }

    if (predictiveBack) {
        PredictiveBackHandler(enabled = route != Route.Home) { flow ->
            try {
                // 手势跟手：进度直接映射成二级页的位移
                flow.collect { progress.snapTo(1f - it.progress) }
                route = Route.Home
            } catch (e: CancellationException) {
                // 手势取消：滑回去
                scope.launch { progress.animateTo(1f, tween(200)) }
                throw e
            }
        }
    } else {
        BackHandler(enabled = route != Route.Home) { route = Route.Home }
    }

    val p = progress.value
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
                        else -> Route.WatchlistDetail
                    }
                },
                onOpenLog = { route = Route.LogViewer },
                onOpenDisclaimer = { route = Route.Disclaimer },
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
                when (lastSub) {
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

                    Route.LogViewer -> SubScreen(
                        title = stringResource(R.string.log_title),
                        onBack = { route = Route.Home },
                    ) {
                        LogViewerScreen()
                    }

                    Route.Disclaimer -> SubScreen(
                        title = stringResource(R.string.disclaimer_detail_title),
                        onBack = { route = Route.Home },
                    ) {
                        DisclaimerScreen()
                    }

                    Route.Home -> Unit
                }
            }
        }
    }
}

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
private fun HomeScaffold(
    currentTab: Tab,
    onSelectTab: (Tab) -> Unit,
    onOpenFeatureDetail: (String) -> Unit,
    onOpenLog: () -> Unit,
    onOpenDisclaimer: () -> Unit,
    /** 首次启动未同意免责声明时为 `true`：弹硬门槛弹窗 */
    showAgreement: Boolean,
    /** 用户点了「同意」 */
    onAgreeDisclaimer: () -> Unit,
) {
    val liquidGlass by rememberBoolPref(ConfigKeys.LIQUID_GLASS, ConfigKeys.DEFAULT_LIQUID_GLASS)
    val glassEffect by rememberBoolPref(ConfigKeys.GLASS_EFFECT, ConfigKeys.DEFAULT_GLASS_EFFECT)
    // 页面底色 = `colorScheme.surface`（深色纯黑 / 浅色浅灰）。
    // **不要**用 `colorScheme.background`：Miuix 里它和 `Card` 的默认色
    // `surfaceContainer` 完全同色，页面一换卡片就整片消失（详见 MainScreen 的注释）。
    val background = MiuixTheme.colorScheme.surface

    // 功能页搜索框的状态提升到这里。
    //
    // 2.9.3 起搜索框渲染在**功能列表的顶部**（随列表一起滚动），但状态仍放在这里：
    // 切走再切回时关键词不会丢，过滤结果保持一致。
    val featureSearch = rememberTextFieldState()

    // 三个「选择类」设置项（深色模式 / 语言 / 日志等级）与重置确认
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

    Box(Modifier.fillMaxSize()) {
        // 悬浮底栏与顶栏玻璃都要采样「内容」才能做出折射 / 模糊，所以把内容录进图层
        val backdrop = rememberLayerBackdrop {
            drawRect(background)
            drawContent()
        }

        Scaffold(
            // 显式指定容器色 = 页面底色。Miuix `Scaffold` 的默认值恰好就是 `surface`，
            // 这里写出来是为了把「页面底色 = surface」这条约定固定在调用点上。
            containerColor = background,
            topBar = {
                val title = stringResource(R.string.app_name)
                val subtitle = stringResource(currentTab.subtitleRes)
                // 搜索框 2.9.3 起移进「功能」列表顶部（随列表一起滚动），
                // 顶栏只剩标题 —— 理由见 FeatureTab 的注释。
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
            CompositionLocalProvider(
                // 设置页的选择项 / 重置确认都从自绘弹窗走，控制器在这里下发
                LocalDialogController provides dialogs,
                LocalDockInset provides if (liquidGlass) DockHeight + DockMargin else 0.dp,
                // 玻璃模式下内容铺到屏幕顶边，首屏空白交给各 Tab 自己补（见上方注释）
                LocalTopInset provides if (liquidGlass) padding.calculateTopPadding() else 0.dp,
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                top = if (liquidGlass) 0.dp else padding.calculateTopPadding(),
                                // 液态底栏时不留底部 inset，内容铺到屏幕最底边（导航条沉浸）
                                bottom = if (liquidGlass) 0.dp else padding.calculateBottomPadding(),
                            )
                            .layerBackdrop(backdrop)
                            // 只观测滚动方向，不消费增量（返回 Offset.Zero），滚动本身不受影响
                            .nestedScroll(scrollConnection)
                            // 点击空白处清掉输入框焦点（见上方 focusManager 处的注释）。
                            // 手指拖动滚动超过 touch slop 就不再是 tap，不影响列表滚动。
                            .pointerInput(focusManager) {
                                detectTapGestures(onTap = { focusManager.clearFocus() })
                            },
                    ) {
                        // 四个板块切换时淡入 + 轻微横移，避免生硬跳变
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
                            when (tab) {
                                Tab.MODULE -> ModuleTab()
                                Tab.FEATURE -> FeatureTab(
                                    searchState = featureSearch,
                                    onOpenDetail = { spec -> onOpenFeatureDetail(spec.id) },
                                )
                                Tab.SETTINGS -> SettingsTab(onOpenLog = onOpenLog)
                                Tab.ABOUT -> AboutTab(onOpenDisclaimer = onOpenDisclaimer)
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
                    val dockAlpha by animateFloatAsState(
                        targetValue = if (liquidGlass) 1f else 0f,
                        animationSpec = tween(220),
                        label = "dock-alpha",
                    )
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
        // 不用 Miuix 的弹层（`MiuixPopupHost`）—— 理由见 ui/AppDialog.kt。
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
private fun SubScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Scaffold(
        // 与一级页同源，保证二级页与底衬颜色一致（见 HomeScaffold 的注释）
        containerColor = MiuixTheme.colorScheme.surface,
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
        CompositionLocalProvider(LocalNavBarInset provides navBarInset) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
            ) {
                content()
            }
        }
    }
}

/** 免责声明完整文本（二级页内容） */
@Composable
private fun DisclaimerScreen() {
    DisclaimerContent()
}

// 悬浮底栏的尺寸常量：占位计算与 Dock 本体共用，避免两处写死对不上
internal val DockHeight = 64.dp
internal val DockMargin = 12.dp

/** 悬浮底栏「下滑收起」前需要累计的同向滚动量 —— 太小会因手指抖动反复闪 */
internal val DockHideThreshold = 24.dp

/** 悬浮底栏收起 / 放出的动画时长 */
private const val DockHideMillis = 240
