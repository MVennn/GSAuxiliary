package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.MVennn.GSAuxiliary.app.FrameworkBridge
import io.github.MVennn.GSAuxiliary.app.ModulePreferences
import io.github.MVennn.GSAuxiliary.core.ConfigKeys

/**
 * 界面层读取配置的统一入口。
 *
 * 界面不直接读写 `SharedPreferences`，全部经由 [ModulePreferences]：
 *  - 框架已连接时写的就是 remote preferences，目标进程立刻可见
 *  - 外部变化（adb 调试通道、另一处界面）会通过 `observe` 回流到 UI
 *  - 界面层完全不需要知道配置存在哪
 */

@Composable
internal fun rememberBoolPref(key: String, def: Boolean): MutableState<Boolean> {
    val st = remember(key) { mutableStateOf(ModulePreferences.getBoolean(key, def)) }
    DisposableEffect(key) {
        val listener = { st.value = ModulePreferences.getBoolean(key, def) }
        ModulePreferences.observe(listener)
        onDispose { ModulePreferences.unobserve(listener) }
    }
    return st
}

/**
 * 与 [rememberBoolPref] 相同，但**只读本机文件**，不走 remote preferences。
 *
 * 用于与目标进程无关的界面状态（免责声明同意标记）。理由见
 * [io.github.MVennn.GSAuxiliary.app.ModulePreferences.getLocalBoolean]。
 */
@Composable
internal fun rememberLocalBoolPref(key: String, def: Boolean): MutableState<Boolean> {
    val st = remember(key) { mutableStateOf(ModulePreferences.getLocalBoolean(key, def)) }
    DisposableEffect(key) {
        val listener = { st.value = ModulePreferences.getLocalBoolean(key, def) }
        ModulePreferences.observe(listener)
        onDispose { ModulePreferences.unobserve(listener) }
    }
    return st
}

@Composable
internal fun rememberIntPref(key: String, def: Int): MutableState<Int> {
    val st = remember(key) { mutableIntStateOf(ModulePreferences.getInt(key, def)) }
    DisposableEffect(key) {
        val listener = { st.value = ModulePreferences.getInt(key, def) }
        ModulePreferences.observe(listener)
        onDispose { ModulePreferences.unobserve(listener) }
    }
    return st
}

@Composable
internal fun rememberStringPref(key: String, def: String): MutableState<String> {
    val st = remember(key) { mutableStateOf(ModulePreferences.getString(key, def)) }
    DisposableEffect(key) {
        val listener = { st.value = ModulePreferences.getString(key, def) }
        ModulePreferences.observe(listener)
        onDispose { ModulePreferences.unobserve(listener) }
    }
    return st
}

/** 框架服务连接状态 */
@Composable
internal fun rememberConnected(): MutableState<Boolean> {
    val st = remember { mutableStateOf(FrameworkBridge.isConnected) }
    DisposableEffect(Unit) {
        val listener = FrameworkBridge.Listener { st.value = it != null }
        FrameworkBridge.addListener(listener)
        onDispose { FrameworkBridge.removeListener(listener) }
    }
    return st
}

/**
 * 当前是否用深色配色。
 *
 * 「深色模式」设置支持 跟随系统 / 亮色 / 暗色；只有「跟随系统」时才看系统主题。
 */
@Composable
internal fun rememberDarkTheme(): Boolean {
    val theme by rememberStringPref(ConfigKeys.THEME, ConfigKeys.DEFAULT_THEME)
    val systemDark = isSystemInDarkTheme()
    return when (theme) {
        ConfigKeys.THEME_DARK -> true
        ConfigKeys.THEME_LIGHT -> false
        else -> systemDark
    }
}

/** 「可预测返回」开关 */
@Composable
internal fun rememberPredictiveBack(): Boolean =
    rememberBoolPref(ConfigKeys.PREDICTIVE_BACK, ConfigKeys.DEFAULT_PREDICTIVE_BACK).value
