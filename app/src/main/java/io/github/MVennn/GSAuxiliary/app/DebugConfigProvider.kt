package io.github.MVennn.GSAuxiliary.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import io.github.MVennn.GSAuxiliary.core.ConfigKeys

/**
 * adb 调试通道（**仅供开发期自动化，不参与运行期配置读取**）。
 *
 * 运行期配置走 libxposed 的 remote preferences，这个 Provider 存在的唯一理由
 * 是让 `tools/` 下的脚本能不改一行代码就读写配置。
 * authority 在 AndroidManifest.xml 里声明为 `io.github.MVennn.GSAuxiliary.config`：
 *
 * ```bash
 * adb shell content query  --uri content://io.github.MVennn.GSAuxiliary.config/config
 * adb shell content update --uri content://io.github.MVennn.GSAuxiliary.config/config --bind enabled:i:0
 * ```
 *
 * 写入一律经由 [ModulePreferences]，所以框架已连接时写的就是远端值，
 * 目标进程立刻可见 —— 等价于在设置界面里点了一下。
 *
 * 安全：只接受 root(0) / shell(2000) 调用，其它 UID 一律 [SecurityException]。
 */
class DebugConfigProvider : ContentProvider() {

    companion object {
        const val COL_ENABLED = "enabled"
        const val COL_COLOR = "color"
        const val COL_SCOPE_STRATEGY = "scope_strategy"
        const val COL_LOG_LEVEL = "log_level"
        const val COL_BACKEND = "backend"
        const val COL_RAW = "raw"

        // 功能开关（与 ConfigKeys.FEATURE_KEYS 对应）：开发期自动化需要可脚本化地开关功能，
        // 否则每验证一个功能都要手工在设置界面点一遍。
        const val COL_STG_DIRECT = "stg_direct_quote"
        const val COL_STG_SWIPE = "stg_quote_swipe"
        const val COL_STG_SELECT_ALL = "stg_select_all"
        const val COL_STG_STOCK_FILTER = "stg_stock_filter"
        const val COL_STOCK_BAR_ENTRY = "stock_bar_entry"
        const val COL_IPO_DIALOG_CLEAN = "ipo_dialog_clean"
        const val COL_AISTARE_QUICK = "aistare_quick_enabled"
        const val COL_AISTARE_SCHEMES = "aistare_quick_config"
        const val COL_AISTARE_EMPTY_SAVE = "aistare_empty_save"
    }

    override fun onCreate(): Boolean {
        context?.let { ModulePreferences.init(it) }
        return true
    }

    private fun ensurePrivileged() {
        val uid = Binder.getCallingUid()
        if (uid != 0 && uid != Process.SHELL_UID) {
            throw SecurityException("调试通道仅允许 root/shell 访问 (uid=$uid)")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        ensurePrivileged()

        val raw = ModulePreferences.all().entries.joinToString(";") { "${it.key}=${it.value}" }

        return MatrixCursor(
            arrayOf(
                COL_ENABLED, COL_COLOR, COL_SCOPE_STRATEGY,
                COL_LOG_LEVEL, COL_BACKEND, COL_RAW,
                COL_STG_DIRECT, COL_STG_SWIPE, COL_STG_SELECT_ALL, COL_STG_STOCK_FILTER,
                COL_STOCK_BAR_ENTRY, COL_IPO_DIALOG_CLEAN,
                COL_AISTARE_QUICK, COL_AISTARE_SCHEMES, COL_AISTARE_EMPTY_SAVE,
            )
        ).apply {
            addRow(
                arrayOf<Any>(
                    if (ModulePreferences.getBoolean(ConfigKeys.ENABLED, ConfigKeys.DEFAULT_ENABLED)) 1 else 0,
                    ModulePreferences.getInt(ConfigKeys.COLOR, ConfigKeys.DEFAULT_COLOR),
                    if (ModulePreferences.getBoolean(ConfigKeys.SCOPE_STRATEGY, ConfigKeys.DEFAULT_SCOPE_STRATEGY)) 1 else 0,
                    ModulePreferences.getInt(ConfigKeys.LOG_LEVEL, ConfigKeys.DEFAULT_LOG_LEVEL),
                    ModulePreferences.backend().name,
                    if (raw.isEmpty()) "(empty)" else raw,
                    if (ModulePreferences.getBoolean(ConfigKeys.STG_DIRECT_QUOTE, ConfigKeys.DEFAULT_STG_DIRECT_QUOTE)) 1 else 0,
                    if (ModulePreferences.getBoolean(ConfigKeys.STG_QUOTE_SWIPE, ConfigKeys.DEFAULT_STG_QUOTE_SWIPE)) 1 else 0,
                    if (ModulePreferences.getBoolean(ConfigKeys.STG_SELECT_ALL, ConfigKeys.DEFAULT_STG_SELECT_ALL)) 1 else 0,
                    if (ModulePreferences.getBoolean(ConfigKeys.STG_STOCK_FILTER, ConfigKeys.DEFAULT_STG_STOCK_FILTER)) 1 else 0,
                    if (ModulePreferences.getBoolean(ConfigKeys.STOCK_BAR_ENTRY, ConfigKeys.DEFAULT_STOCK_BAR_ENTRY)) 1 else 0,
                    if (ModulePreferences.getBoolean(ConfigKeys.IPO_DIALOG_CLEAN, ConfigKeys.DEFAULT_IPO_DIALOG_CLEAN)) 1 else 0,
                    if (ModulePreferences.getBoolean(ConfigKeys.AISTARE_QUICK_ENABLED, ConfigKeys.DEFAULT_AISTARE_QUICK_ENABLED)) 1 else 0,
                    ModulePreferences.getString(ConfigKeys.AISTARE_QUICK_CONFIG, ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG),
                    if (ModulePreferences.getBoolean(ConfigKeys.AISTARE_EMPTY_SAVE, ConfigKeys.DEFAULT_AISTARE_EMPTY_SAVE)) 1 else 0,
                )
            )
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        ensurePrivileged()
        if (values == null) return 0

        // getAsInteger 可能返回 null（值缺失/类型不对），直接 unboxing 会 NPE；
        // 计数只在实际发生写入时增加，异常时返回值才不会虚报
        fun intOf(col: String): Int? = if (values.containsKey(col)) values.getAsInteger(col) else null

        var n = 0
        ModulePreferences.edit {
            intOf(COL_ENABLED)?.let { putBoolean(ConfigKeys.ENABLED, it != 0); n++ }
            intOf(COL_COLOR)?.let { putInt(ConfigKeys.COLOR, it); n++ }
            intOf(COL_SCOPE_STRATEGY)?.let { putBoolean(ConfigKeys.SCOPE_STRATEGY, it != 0); n++ }
            intOf(COL_LOG_LEVEL)?.let { putInt(ConfigKeys.LOG_LEVEL, it); n++ }
            intOf(COL_STG_DIRECT)?.let { putBoolean(ConfigKeys.STG_DIRECT_QUOTE, it != 0); n++ }
            intOf(COL_STG_SWIPE)?.let { putBoolean(ConfigKeys.STG_QUOTE_SWIPE, it != 0); n++ }
            intOf(COL_STG_SELECT_ALL)?.let { putBoolean(ConfigKeys.STG_SELECT_ALL, it != 0); n++ }
            intOf(COL_STG_STOCK_FILTER)?.let { putBoolean(ConfigKeys.STG_STOCK_FILTER, it != 0); n++ }
            intOf(COL_STOCK_BAR_ENTRY)?.let { putBoolean(ConfigKeys.STOCK_BAR_ENTRY, it != 0); n++ }
            intOf(COL_IPO_DIALOG_CLEAN)?.let { putBoolean(ConfigKeys.IPO_DIALOG_CLEAN, it != 0); n++ }
            intOf(COL_AISTARE_QUICK)?.let { putBoolean(ConfigKeys.AISTARE_QUICK_ENABLED, it != 0); n++ }
            intOf(COL_AISTARE_EMPTY_SAVE)?.let { putBoolean(ConfigKeys.AISTARE_EMPTY_SAVE, it != 0); n++ }
            if (values.containsKey(COL_AISTARE_SCHEMES)) {
                putString(ConfigKeys.AISTARE_QUICK_CONFIG, values.getAsString(COL_AISTARE_SCHEMES))
                n++
            }
        }
        return n
    }
}
