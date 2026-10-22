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
 * 单层 UID 白名单是有意设计（不加 signature permission 的原因），详见
 * [ensurePrivileged] 的 KDoc（审查 2026-10-17 中-3）。
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
        const val COL_STG_SNAP_ADD = "stg_snap_add"
        const val COL_STG_STOCK_FILTER = "stg_stock_filter"
        const val COL_STOCK_BAR_ENTRY = "stock_bar_entry"
        const val COL_IPO_DIALOG_CLEAN = "ipo_dialog_clean"
        const val COL_AISTARE_QUICK = "aistare_quick_enabled"
        const val COL_AISTARE_SCHEMES = "aistare_quick_config"
        const val COL_AISTARE_EMPTY_SAVE = "aistare_empty_save"
        const val COL_AISTARE_SKIP_CONFIRM = "aistare_skip_save_confirm"
        const val COL_AISTARE_SAVE_BACK = "aistare_save_back_to_stock"
        const val COL_AISTARE_PICK_SCHEME = "aistare_pick_scheme"
        const val COL_AISTARE_CARD_DETAIL = "aistare_card_detail"
        const val COL_DOCK_CLEAN = "dock_clean"
        const val COL_HOME_BANNER_HIDE = "home_banner_hide"
        const val COL_MARKET_BANNER_HIDE = "market_banner_hide"
        const val COL_MARKET_SECTOR_COMPACT = "market_sector_compact"
        const val COL_MARKET_OVERVIEW_COMPACT = "market_overview_compact"
        const val COL_MARKET_STOCK_FILTER = "market_stock_filter"
        const val COL_MARKET_INDEX_HIDE = "market_index_hide"
        const val COL_NAV_BAR_BLEND = "nav_bar_blend"
        const val COL_WATCHLIST_BOLD = "watchlist_bold"
    }

    override fun onCreate(): Boolean {
        context?.let { ModulePreferences.init(it) }
        return true
    }

    /**
     * 访问控制：单层 UID 白名单（root=0 / shell=2000），是有意设计（审查 2026-10-17 中-3）。
     *
     * 防线模型：
     *  - Provider 本身 exported=true 且**不声明** android:permission ——
     *    自定义 permission（无论 normal/signature）都会把 adb shell（uid 2000，
     *    不具备本应用 signature）也挡掉，破坏 `adb shell content query/update`
     *    调试通道；manifest 层的攻击面由 lint 例外（tools:ignore）显式承认。
     *  - 唯一的防线就是这里的运行时 UID 白名单：query / update 入口都先过本方法，
     *    其它 UID（含普通应用）一律 SecurityException。
     *  - 暴露面收敛为「root / shell 可读写本模块配置」—— 与 adb 本身的能力
     *    等价（adb shell 本就能 run-as / 读写 debuggable 应用数据），不额外放大风险。
     *  - 该 Provider 仅供开发期自动化，不参与运行期配置读取；不再需要 adb
     *    调试时可直接整体下线。
     */
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

        // ⚠️ **列与值必须同源**（2026-10-20 审计时发现）：
        // 之前列数组与 `addRow` 的值是**两份手写的清单** —— 补了列忘了补值，
        // 就成了 28 列 vs 15 值 → `MatrixCursor.addRow` 直接抛
        // `IllegalArgumentException`（它要求长度严格相等）→ **整个调试通道读不了配置**。
        // 现在一份表出两处，结构上不可能再错位。
        val columns = arrayOf(
            COL_ENABLED, COL_COLOR, COL_SCOPE_STRATEGY,
            COL_LOG_LEVEL, COL_BACKEND, COL_RAW,
            COL_STG_DIRECT, COL_STG_SWIPE, COL_STG_SELECT_ALL, COL_STG_SNAP_ADD, COL_STG_STOCK_FILTER,
            COL_STOCK_BAR_ENTRY, COL_IPO_DIALOG_CLEAN,
            COL_AISTARE_QUICK, COL_AISTARE_SCHEMES, COL_AISTARE_EMPTY_SAVE,
            COL_AISTARE_SKIP_CONFIRM, COL_AISTARE_SAVE_BACK, COL_AISTARE_PICK_SCHEME,
            COL_AISTARE_CARD_DETAIL,
            COL_DOCK_CLEAN, COL_HOME_BANNER_HIDE,
            COL_MARKET_BANNER_HIDE, COL_MARKET_SECTOR_COMPACT,
            COL_MARKET_OVERVIEW_COMPACT, COL_MARKET_STOCK_FILTER, COL_MARKET_INDEX_HIDE,
            COL_NAV_BAR_BLEND, COL_WATCHLIST_BOLD,
        )
        val values = arrayOf<Any>(
            if (ModulePreferences.getBoolean(ConfigKeys.ENABLED, ConfigKeys.DEFAULT_ENABLED)) 1 else 0,
            ModulePreferences.getInt(ConfigKeys.COLOR, ConfigKeys.DEFAULT_COLOR),
            if (ModulePreferences.getBoolean(ConfigKeys.SCOPE_STRATEGY, ConfigKeys.DEFAULT_SCOPE_STRATEGY)) 1 else 0,
            ModulePreferences.getInt(ConfigKeys.LOG_LEVEL, ConfigKeys.DEFAULT_LOG_LEVEL),
            ModulePreferences.backend().name,
            if (raw.isEmpty()) "(empty)" else raw,
            if (ModulePreferences.getBoolean(ConfigKeys.STG_DIRECT_QUOTE, ConfigKeys.DEFAULT_STG_DIRECT_QUOTE)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.STG_QUOTE_SWIPE, ConfigKeys.DEFAULT_STG_QUOTE_SWIPE)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.STG_SELECT_ALL, ConfigKeys.DEFAULT_STG_SELECT_ALL)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.STG_SNAP_ADD, ConfigKeys.DEFAULT_STG_SNAP_ADD)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.STG_STOCK_FILTER, ConfigKeys.DEFAULT_STG_STOCK_FILTER)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.STOCK_BAR_ENTRY, ConfigKeys.DEFAULT_STOCK_BAR_ENTRY)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.IPO_DIALOG_CLEAN, ConfigKeys.DEFAULT_IPO_DIALOG_CLEAN)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.AISTARE_QUICK_ENABLED, ConfigKeys.DEFAULT_AISTARE_QUICK_ENABLED)) 1 else 0,
            ModulePreferences.getString(ConfigKeys.AISTARE_QUICK_CONFIG, ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG),
            if (ModulePreferences.getBoolean(ConfigKeys.AISTARE_EMPTY_SAVE, ConfigKeys.DEFAULT_AISTARE_EMPTY_SAVE)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.AISTARE_SKIP_SAVE_CONFIRM, ConfigKeys.DEFAULT_AISTARE_SKIP_SAVE_CONFIRM)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.AISTARE_SAVE_BACK_TO_STOCK, ConfigKeys.DEFAULT_AISTARE_SAVE_BACK_TO_STOCK)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.AISTARE_PICK_SCHEME, ConfigKeys.DEFAULT_AISTARE_PICK_SCHEME)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.AISTARE_CARD_DETAIL, ConfigKeys.DEFAULT_AISTARE_CARD_DETAIL)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.DOCK_CLEAN, ConfigKeys.DEFAULT_DOCK_CLEAN)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.HOME_BANNER_HIDE, ConfigKeys.DEFAULT_HOME_BANNER_HIDE)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.MARKET_BANNER_HIDE, ConfigKeys.DEFAULT_MARKET_BANNER_HIDE)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.MARKET_SECTOR_COMPACT, ConfigKeys.DEFAULT_MARKET_SECTOR_COMPACT)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.MARKET_OVERVIEW_COMPACT, ConfigKeys.DEFAULT_MARKET_OVERVIEW_COMPACT)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.MARKET_STOCK_FILTER, ConfigKeys.DEFAULT_MARKET_STOCK_FILTER)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.MARKET_INDEX_HIDE, ConfigKeys.DEFAULT_MARKET_INDEX_HIDE)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.NAV_BAR_BLEND, ConfigKeys.DEFAULT_NAV_BAR_BLEND)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.WATCHLIST_BOLD, ConfigKeys.DEFAULT_WATCHLIST_BOLD)) 1 else 0,
        )
        // 列与值必须等长 —— `MatrixCursor.addRow` 长度不等会抛 IllegalArgumentException
        if (columns.size != values.size) {
            throw IllegalStateException(
                "调试通道列数(${columns.size})与值数(${values.size})不等 —— 补的时候要两边一起补"
            )
        }
        return MatrixCursor(columns).apply { addRow(values) }
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
            intOf(COL_STG_SNAP_ADD)?.let { putBoolean(ConfigKeys.STG_SNAP_ADD, it != 0); n++ }
            intOf(COL_STG_STOCK_FILTER)?.let { putBoolean(ConfigKeys.STG_STOCK_FILTER, it != 0); n++ }
            intOf(COL_STOCK_BAR_ENTRY)?.let { putBoolean(ConfigKeys.STOCK_BAR_ENTRY, it != 0); n++ }
            intOf(COL_IPO_DIALOG_CLEAN)?.let { putBoolean(ConfigKeys.IPO_DIALOG_CLEAN, it != 0); n++ }
            intOf(COL_AISTARE_QUICK)?.let { putBoolean(ConfigKeys.AISTARE_QUICK_ENABLED, it != 0); n++ }
            intOf(COL_AISTARE_EMPTY_SAVE)?.let { putBoolean(ConfigKeys.AISTARE_EMPTY_SAVE, it != 0); n++ }
            intOf(COL_AISTARE_SKIP_CONFIRM)?.let { putBoolean(ConfigKeys.AISTARE_SKIP_SAVE_CONFIRM, it != 0); n++ }
            intOf(COL_AISTARE_SAVE_BACK)?.let { putBoolean(ConfigKeys.AISTARE_SAVE_BACK_TO_STOCK, it != 0); n++ }
            intOf(COL_AISTARE_PICK_SCHEME)?.let { putBoolean(ConfigKeys.AISTARE_PICK_SCHEME, it != 0); n++ }
            intOf(COL_AISTARE_CARD_DETAIL)?.let { putBoolean(ConfigKeys.AISTARE_CARD_DETAIL, it != 0); n++ }
            intOf(COL_DOCK_CLEAN)?.let { putBoolean(ConfigKeys.DOCK_CLEAN, it != 0); n++ }
            intOf(COL_HOME_BANNER_HIDE)?.let { putBoolean(ConfigKeys.HOME_BANNER_HIDE, it != 0); n++ }
            intOf(COL_MARKET_BANNER_HIDE)?.let { putBoolean(ConfigKeys.MARKET_BANNER_HIDE, it != 0); n++ }
            intOf(COL_MARKET_SECTOR_COMPACT)?.let { putBoolean(ConfigKeys.MARKET_SECTOR_COMPACT, it != 0); n++ }
            intOf(COL_MARKET_OVERVIEW_COMPACT)?.let { putBoolean(ConfigKeys.MARKET_OVERVIEW_COMPACT, it != 0); n++ }
            intOf(COL_MARKET_STOCK_FILTER)?.let { putBoolean(ConfigKeys.MARKET_STOCK_FILTER, it != 0); n++ }
            intOf(COL_MARKET_INDEX_HIDE)?.let { putBoolean(ConfigKeys.MARKET_INDEX_HIDE, it != 0); n++ }
            intOf(COL_NAV_BAR_BLEND)?.let { putBoolean(ConfigKeys.NAV_BAR_BLEND, it != 0); n++ }
            intOf(COL_WATCHLIST_BOLD)?.let { putBoolean(ConfigKeys.WATCHLIST_BOLD, it != 0); n++ }
            if (values.containsKey(COL_AISTARE_SCHEMES)) {
                putString(ConfigKeys.AISTARE_QUICK_CONFIG, values.getAsString(COL_AISTARE_SCHEMES))
                n++
            }
        }
        return n
    }
}
