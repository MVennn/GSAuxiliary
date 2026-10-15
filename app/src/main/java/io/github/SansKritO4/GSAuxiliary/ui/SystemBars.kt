package io.github.SansKritO4.GSAuxiliary.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 手势导航条（俗称「小白条」）的高度，由二级页外壳 `SubScreen` 下发。
 *
 * ## 为什么需要它
 *
 * 「沉浸」的定义是：**内容一直铺到屏幕物理最底边**，手势条区域透出的是内容本身，
 * 而不是被系统（或主题背景色）填成一条纯色带。
 *
 * 要做到这点，二级页的内容**不能**再让开 Scaffold 给的底部 inset —— 一让开，
 * 最后那 ~64px 就永远是空的，看着就是「小白条下面填了一块颜色」。
 *
 * 但内容铺到底之后，**最后一项不能压在导航条上**（会点不到）。于是：
 * 内容铺满（由 `SubScreen` 负责），各二级页在滚动内容末尾自己补
 * `24.dp + LocalNavBarInset.current` 的空白（与一级页用 [LocalDockInset] 让位同一个套路）。
 */
internal val LocalNavBarInset = compositionLocalOf<Dp> { 0.dp }

/**
 * 系统栏（状态栏 + 手势导航条）的「沉浸」处理。
 *
 * 目标：**系统栏全透明，内容一直铺到屏幕物理边缘**，也就是所谓「小白条沉浸」。
 *
 * 主题层（`values/themes.xml`）已经关掉了 `enforceNavigationBarContrast`，
 * 但主题属性只在**窗口创建时**读一次，而本项目的深浅色是用户可随时切换的
 * （还可能和系统相反），所以这里在 Compose 层再按「实际生效的深浅色」同步一次。
 *
 * ## 两个职责
 *
 * 1. **系统栏图标明暗**
 *    `enableEdgeToEdge()` 是按**系统**深色开关算出来的图标颜色，用户把 App
 *    强制成暗色而系统是亮色时就会反（白图标压在浅色页面上）。这里按
 *    `dark`（= [rememberDarkTheme] 的结果，已含用户设置）重设。
 *    注意 `windowLightNavigationBar` / `windowLightStatusBar` 这两个**主题属性**
 *    从 API 35 起被忽略，只有 `WindowInsetsControllerCompat` 这条路一直有效。
 *
 * 2. **窗口底色跟随主题**
 *    `windowBackground` 露在 Compose 内容之外的缝隙里（冷启动首帧、系统栏过渡
 *    动画、圆角裁剪）。XML 里的值只能按系统深色开关选，这里用 MiuixTheme 的
 *    真实背景色覆盖掉，保证任何情况下缝隙里的颜色都和页面一致。
 *
 * @param dark 当前**实际生效**的深色模式（不是系统的）
 * @param background 当前主题的背景色，一般传 `MiuixTheme.colorScheme.background`
 */
@Composable
internal fun SyncSystemBars(dark: Boolean, background: Color) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    val scrim = remember(background) { ColorDrawable(background.toArgb()) }

    SideEffect {
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        window.setBackgroundDrawable(scrim)
    }
}

/**
 * 沿 `ContextWrapper` 链上溯找宿主 Activity。
 *
 * 顺序很关键：`Activity` 本身**也是** `ContextWrapper`（`Activity` →
 * `ContextThemeWrapper` → `ContextWrapper`），所以必须先判 `is Activity`。
 *
 * 另一个坑：`SettingsActivity.attachBaseContext` 里被 [io.github.SansKritO4.GSAuxiliary.app.LocaleHelper]
 * 包了一层 `ContextWrapper`，链是 `LocalizedContextWrapper → Activity`，
 * 所以不能只判一层。
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
