package io.github.MVennn.GSAuxiliary.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.LocaleHelper
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.QuickConfigStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * **布局分三层**（从下到上）：
 *   1. 一级首页 [HomeScaffold] —— 常驻，作为二级页下方的底衬（可预测返回时露出来）
 *   2. 二级页 [SubScreen] —— 按进度右滑进出，不裁剪、不闪白
 *   3. 三级页（目前只有方案编辑）—— 叠在二级页之上，**各自一条进度**，
 *      返回时只滑走三级页、露出底下那张二级页（详见 [MainScreen]）
 *
 * 系统栏（状态栏 / 手势导航条）的沉浸处理在 [SyncSystemBars]，理由见该函数。
 *
 * 弹层走 [AppDialog] 的**自绘** `DialogHost`（2.8.0 起，**不用** Miuix 的
 * `OverlayDropdownPreference` / `OverlayDialog` —— 后者在库内部的 `onClick` 里
 * 同步开始收起动画，与同步回写的配置状态打架会闪退，调用侧无法规避）。
 * 3.1.0 曾试过把贴底弹窗换成 Miuix `OverlayBottomSheet`（配 activity-compose
 * 1.12.4 修掉 ViewTree Owner 缺失的崩溃），因观感与动画手感不合按用户要求
 * 回退为自绘；activity 升级保留。
 * 详见 `ui/AppDialog.kt` 顶部注释与 `docs/UI.md` §1.3。
 *
 * 路由（[Route] / [MainScreen]）拆在同包的 `Routing.kt`；
 * 一级 / 二级页外壳（[HomeScaffold] / [SubScreen]）拆在 `Screens.kt`。
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
        // 「管理方案…」从宿主 H5 拉起时带 route extra 直达「一键配置」二级页（§6.1）
        val initialRoute = intent?.getStringExtra(QuickConfigStore.EXTRA_ROUTE)?.let { key ->
            when (key) {
                QuickConfigStore.ROUTE_QUICK_CONFIG -> Route.QuickConfig
                else -> null
            }
        }
        // 只消费一次：路由本身已经用 rememberSaveable 存档，重建时会从存档还原；
        // 留着 extra 会让「回到首页 → 转屏」又跳回配置页（审查第三轮）。
        intent?.removeExtra(QuickConfigStore.EXTRA_ROUTE)
        setContent {
            AppTheme {
                MainScreen(initialRoute = initialRoute ?: Route.Home)
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
        // 位图在进程内只解一次：每次 onCreate（含转屏）都去 getApplicationIcon().toBitmap()
        // 是一次「主线程读资源 + 位图化」，冷启动首帧能被它推后几十毫秒。
        taskIcon ?: run {
            Thread {
                val bitmap = runCatching {
                    packageManager.getApplicationIcon(packageName).toBitmap()
                }.getOrNull() ?: return@Thread
                taskIcon = bitmap
                runOnUiThread { applyTaskDescription(bitmap) }
            }.apply { isDaemon = true }.start()
            return
        }.let(::applyTaskDescription)
    }

    private fun applyTaskDescription(bitmap: android.graphics.Bitmap) {
        runCatching {
            setTaskDescription(ActivityManager.TaskDescription(getString(R.string.app_name), bitmap))
        }
    }

    private companion object {
        /** 启动器图标位图（进程内缓存；launcher alias 由系统换图标，位图本身不变） */
        @Volatile
        var taskIcon: android.graphics.Bitmap? = null
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
        try {
            if (shownDark == dark) return@LaunchedEffect
            // 1) 先用**旧**配色的底色铺满（此刻新旧同色，肉眼看不出变化）
            veilColor = if (shownDark) darkColors.surface else lightColors.surface
            veilAlpha.snapTo(1f)
            // 2) 等这一帧画完再换配色，保证「换色」与「遮罩」同帧
            withFrameNanos { }
            shownDark = dark
            // 3) 遮罩淡出 —— 视觉上就是从旧配色平滑过渡到新配色
            veilAlpha.animateTo(0f, tween(ThemeFadeMillis))
        } finally {
            // 遮罩**绝不能留在屏幕上**：`animateTo` 是一条可被取消的挂起调用，
            // 过渡途中若本效果被重启（连续切两次主题 / 重组取消），取消点会停在
            // 半透明处 —— 表现就是「切完主题整个界面被压暗，重启 App 才恢复」
            // （3.1.1 在真机上抓到）。`NonCancellable` 里补一次收尾，
            // 正常跑完时 value 已经是 0，这次 snapTo 是空操作。
            withContext(NonCancellable) {
                if (veilAlpha.value > 0f) veilAlpha.snapTo(0f)
            }
        }
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
