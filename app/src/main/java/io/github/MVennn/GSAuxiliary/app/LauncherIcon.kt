package io.github.MVennn.GSAuxiliary.app

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import io.github.MVennn.GSAuxiliary.core.GLog

/**
 * 桌面图标开关。
 *
 * 原理：桌面图标挂在 LauncherAlias（activity-alias）上，而不是 Activity 本体。
 *  - 关掉图标 = 禁用 alias，Activity 本体仍可用（LSPosed 页面 / adb 拉起）
 *  - 打开图标 = 显式把 alias 置为 ENABLED
 *
 * ## 为什么用独立的本地镜像文件
 *
 * 覆盖安装会清掉 PackageManager.setComponentEnabledSetting 留下的组件启用记录，
 * alias 回落到 manifest 默认值。本次把 manifest 默认改成 android:enabled="false"，
 * 覆盖安装后天然回落到「隐藏」；是否显示由代码按用户偏好显式 ENABLED。
 * 这个「用户偏好」存在独立文件 launcher_icon_state 里：
 *
 *  - 独立文件（不是 ConfigKeys.GROUP）：写它不触发 ModulePreferences 的
 *    OnSharedPreferenceChangeListener / observe，不会被 migrateLocalToRemote
 *    当配置搬到远端，更不会被 resetToDefaults 的 clear() 抹掉；
 *  - 跨覆盖安装保留（SharedPreferences 不随 APK 覆盖清空）；
 *  - 不依赖框架 binder，进程刚起、remote 未就绪时也能立刻读到。
 *
 * 镜像键不存在（全新安装 / 老版本升级）时一律回落「隐藏」，并由 apply 内部的
 * writeLocal 把 false 固化写回，保证「关掉后别再冒出来」，后续 onCreate 与 UI
 * 读到一致值。alias 的唯一权威是本地镜像，DEFAULT_DESKTOP_ICON 不再驱动组件状态。
 *
 * 覆盖安装后由 PackageReplacedReceiver（ACTION_MY_PACKAGE_REPLACED）与
 * applyFromLocal 在 Application.onCreate 双保险重放；SettingsActivity 本体
 * 始终 enabled，不会把「进设置页」的入口切断。
 */
object LauncherIcon {

    /** 与 AndroidManifest 里的 activity-alias 名字保持一致 */
    private const val ALIAS_CLASS = "io.github.MVennn.GSAuxiliary.ui.LauncherAlias"

    /** 本地镜像文件名：独立于 ConfigKeys.GROUP，避免污染配置后端与快照语义 */
    private const val PREFS_NAME = "launcher_icon_state"

    /** 镜像里记录「用户是否要显示图标」的键 */
    private const val KEY_VISIBLE = "desktop_icon_local"

    private fun mirror(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读本地镜像；键不存在（全新安装 / 老版本升级）时一律回落「隐藏」。
     * 固化写回由 apply 内部的 writeLocal 完成（applyFromLocal 必经 apply），
     * 这里保持纯读，避免与 Compose 读取相互踩。
     */
    private fun readLocal(context: Context): Boolean = runCatching {
        mirror(context).getBoolean(KEY_VISIBLE, false)
    }.getOrDefault(false)

    /** 写本地镜像（独立文件，不触发 ModulePreferences.observe，见类注释）。 */
    private fun writeLocal(context: Context, visible: Boolean) {
        runCatching {
            mirror(context).edit().putBoolean(KEY_VISIBLE, visible).commit()
        }.onFailure { GLog.w("写入桌面图标镜像失败（visible=$visible）", it) }
    }

    /**
     * 把指定可见性同步到「镜像 + alias 组件」。
     * 先写镜像再切组件：任何调用点（设置开关 / onCreate / 升级广播）都不会漏写，
     * 回落路径顺带把 false 固化写回，后续启动 / 设置页读到的都是同一份值。
     * visible=true 必须显式 ENABLED —— manifest 默认已是 false，沿用 DEFAULT 会回落隐藏。
     */
    fun apply(context: Context, visible: Boolean) {
        writeLocal(context, visible)
        val pm = context.packageManager
        val alias = ComponentName(context.packageName, ALIAS_CLASS)
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        }.onFailure { GLog.w("切换桌面图标失败（visible=$visible）", it) }
    }

    /**
     * 从本地镜像重放（Application.onCreate / ACTION_MY_PACKAGE_REPLACED 共用）。
     * 幂等：只读镜像 + 写同值镜像 + 切组件，不碰 ConfigKeys.GROUP，不触发配置变更通知回路。
     */
    fun applyFromLocal(context: Context) {
        apply(context, readLocal(context))
    }

    /**
     * alias 是否显示 —— 以本地镜像为准。
     * manifest 默认 false 后 getComponentEnabledSetting 的 DEFAULT 会被「!= DISABLED」
     * 误判成显示，故不再读组件状态。
     */
    fun isVisible(context: Context): Boolean = readLocal(context)
}
