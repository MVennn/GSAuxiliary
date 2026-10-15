package gsauxiliary.m.venn.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import gsauxiliary.m.venn.core.GLog

/**
 * 桌面图标开关。
 *
 * 原理：桌面图标挂在 `LauncherAlias`（`activity-alias`）上，而不是 Activity 本体。
 *  - 关掉图标 = 禁用 alias，Activity 本体仍然可用（可由 LSPosed 页面或 adb 拉起）
 *  - 打开图标 = 把 alias 恢复成 manifest 默认状态
 *
 * 这样做的好处：切换图标**不会**把自己锁死在「连设置页都进不去」的状态，
 * 因为 SettingsActivity 本体始终是 enabled 的。
 */
object LauncherIcon {

    /** 与 AndroidManifest 里的 `activity-alias` 名字保持一致 */
    private const val ALIAS_CLASS = "gsauxiliary.m.venn.ui.LauncherAlias"

    /** 把开关状态同步到组件状态（App 启动时调一次，保证与配置一致） */
    fun apply(context: Context, visible: Boolean) {
        val pm = context.packageManager
        val alias = ComponentName(context.packageName, ALIAS_CLASS)
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        }.onFailure { GLog.w("切换桌面图标失败（visible=$visible）", it) }
    }

    /** 当前组件状态是否为「显示」 */
    fun isVisible(context: Context): Boolean {
        val pm = context.packageManager
        val alias = ComponentName(context.packageName, ALIAS_CLASS)
        return runCatching {
            pm.getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(true)
    }
}
