package io.github.MVennn.GSAuxiliary.core

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/**
 * 目标进程侧的配置读取。
 *
 * 相比迁移前的三级兜底（XSharedPreferences → ContentProvider → 默认值）
 * 加 3 秒轮询，现代 API 提供了正规通道：
 *
 *  - [XposedInterface.getRemotePreferences] 由框架托管，跨进程可靠；
 *  - 返回的 `SharedPreferences` 在目标进程里是**只读**的，但**支持变更监听** ——
 *    配置一改，框架主动把新值推到这个进程，于是 3 秒轮询和「配置指纹比对」
 *    这两套兜底代码可以整体删掉。
 *
 * 若框架不支持 remote 能力（嵌入式实现），退回默认值并明确记日志，
 * 不做静默失败。
 */
class ConfigSource(private val xposed: XposedInterface) {

    @Volatile
    var snapshot: ConfigSnapshot = ConfigSnapshot.DEFAULT
        private set

    /** 配置通道描述，便于排查（会打进 LSPosed 日志） */
    private var channel: String = "(未初始化)"

    /** 配置变化回调；框架在 **Binder 线程**触发，实现方需自行切主线程 */
    @Volatile
    var onChanged: ((ConfigSnapshot) -> Unit)? = null

    private var prefs: SharedPreferences? = null

    private val changeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // 非快照键（主题 / 语言 / 玻璃 / 桌面图标 / 可预测返回）**目标进程不需要知道**
            // —— 它们走同一个 remote preferences 分组，改一下也会推一条通知过来。
            // 直接挡掉，省掉一次「全量读 24 个远端键 + 构造快照 + 算指纹」
            // （指纹里还含 O(n) 的方案 JSON 哈希）。
            // key 为 null = 框架整批推送，那一包里什么键都可能有，不能挡。
            if (key != null && key !in ConfigKeys.SNAPSHOT_KEYS) return@OnSharedPreferenceChangeListener
            val fresh = read()
            // 指纹只取一次，避免比对两侧各算一遍
            val freshFingerprint = fresh.fingerprint
            if (freshFingerprint != snapshot.fingerprint) {
                snapshot = fresh
                // 只打「哪个键变了 + 指纹」，**不要整份快照**：快照里带着
                // `aiStareQuickConfig`（方案 JSON，KB 级），改一次开关就往 512KB
                // 的滚动日志里灌一条，历史很快被冲掉，真出问题时反而没上下文。
                GLog.i("配置已更新（${key ?: "?"}）指纹=$freshFingerprint")
                runCatching { onChanged?.invoke(fresh) }
                    .onFailure { GLog.w("配置变化回调异常", it) }
            }
        }

    /** 打开通道并读取首帧配置。可重复调用（热重载后重新打开）。 */
    fun open() {
        close()

        val caps = runCatching { xposed.frameworkProperties }.getOrDefault(0L)
        if (caps and XposedInterface.PROP_CAP_REMOTE == 0L) {
            channel = "框架未声明 remote preferences 能力 (PROP_CAP_REMOTE=0)"
            GLog.w(channel)
            snapshot = ConfigSnapshot.DEFAULT
            return
        }

        try {
            val p = xposed.getRemotePreferences(ConfigKeys.GROUP)
            p.registerOnSharedPreferenceChangeListener(changeListener)
            prefs = p
            snapshot = read()
            channel = "remote preferences"
            GLog.i("配置通道 = $channel（指纹=${snapshot.fingerprint}）")
        } catch (t: Throwable) {
            channel = "remote preferences 不可用: $t"
            GLog.w(channel, t)
            snapshot = ConfigSnapshot.DEFAULT
        }
    }

    fun close() {
        val p = prefs ?: return
        runCatching { p.unregisterOnSharedPreferenceChangeListener(changeListener) }
        prefs = null
    }

    private fun read(): ConfigSnapshot {
        val p = prefs ?: return ConfigSnapshot.DEFAULT
        return try {
            ConfigSnapshot(
                enabled = p.getBoolean(ConfigKeys.ENABLED, ConfigKeys.DEFAULT_ENABLED),
                color = p.getInt(ConfigKeys.COLOR, ConfigKeys.DEFAULT_COLOR),
                scopeStrategy = p.getBoolean(ConfigKeys.SCOPE_STRATEGY, ConfigKeys.DEFAULT_SCOPE_STRATEGY),
                logLevel = p.getInt(ConfigKeys.LOG_LEVEL, ConfigKeys.DEFAULT_LOG_LEVEL),
                stgDirectQuote = p.getBoolean(ConfigKeys.STG_DIRECT_QUOTE, ConfigKeys.DEFAULT_STG_DIRECT_QUOTE),
                stgQuoteSwipe = p.getBoolean(ConfigKeys.STG_QUOTE_SWIPE, ConfigKeys.DEFAULT_STG_QUOTE_SWIPE),
                stgSelectAll = p.getBoolean(ConfigKeys.STG_SELECT_ALL, ConfigKeys.DEFAULT_STG_SELECT_ALL),
                stgStockFilter = p.getBoolean(ConfigKeys.STG_STOCK_FILTER, ConfigKeys.DEFAULT_STG_STOCK_FILTER),
                stockBarEntry = p.getBoolean(ConfigKeys.STOCK_BAR_ENTRY, ConfigKeys.DEFAULT_STOCK_BAR_ENTRY),
                dockClean = p.getBoolean(ConfigKeys.DOCK_CLEAN, ConfigKeys.DEFAULT_DOCK_CLEAN),
                homeBannerHide = p.getBoolean(ConfigKeys.HOME_BANNER_HIDE, ConfigKeys.DEFAULT_HOME_BANNER_HIDE),
                marketBannerHide = p.getBoolean(ConfigKeys.MARKET_BANNER_HIDE, ConfigKeys.DEFAULT_MARKET_BANNER_HIDE),
                marketSectorCompact = p.getBoolean(ConfigKeys.MARKET_SECTOR_COMPACT, ConfigKeys.DEFAULT_MARKET_SECTOR_COMPACT),
                marketIndexHide = p.getBoolean(ConfigKeys.MARKET_INDEX_HIDE, ConfigKeys.DEFAULT_MARKET_INDEX_HIDE),
                marketOverviewCompact = p.getBoolean(ConfigKeys.MARKET_OVERVIEW_COMPACT, ConfigKeys.DEFAULT_MARKET_OVERVIEW_COMPACT),
                marketStockFilter = p.getBoolean(ConfigKeys.MARKET_STOCK_FILTER, ConfigKeys.DEFAULT_MARKET_STOCK_FILTER),
                navBarBlend = p.getBoolean(ConfigKeys.NAV_BAR_BLEND, ConfigKeys.DEFAULT_NAV_BAR_BLEND),
                ipoDialogClean = p.getBoolean(ConfigKeys.IPO_DIALOG_CLEAN, ConfigKeys.DEFAULT_IPO_DIALOG_CLEAN),
                aistareQuickEnabled = p.getBoolean(
                    ConfigKeys.AISTARE_QUICK_ENABLED, ConfigKeys.DEFAULT_AISTARE_QUICK_ENABLED,
                ),
                aiStareQuickConfig = p.getString(
                    ConfigKeys.AISTARE_QUICK_CONFIG, ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG,
                ) ?: ConfigKeys.DEFAULT_AISTARE_QUICK_CONFIG,
                aiStareSkipSaveConfirm = p.getBoolean(
                    ConfigKeys.AISTARE_SKIP_SAVE_CONFIRM, ConfigKeys.DEFAULT_AISTARE_SKIP_SAVE_CONFIRM,
                ),
                aiStareEmptySave = p.getBoolean(
                    ConfigKeys.AISTARE_EMPTY_SAVE, ConfigKeys.DEFAULT_AISTARE_EMPTY_SAVE,
                ),
                aiStareCardDetail = p.getBoolean(
                    ConfigKeys.AISTARE_CARD_DETAIL, ConfigKeys.DEFAULT_AISTARE_CARD_DETAIL,
                ),
                aiStareSaveBackToStock = p.getBoolean(
                    ConfigKeys.AISTARE_SAVE_BACK_TO_STOCK, ConfigKeys.DEFAULT_AISTARE_SAVE_BACK_TO_STOCK,
                ),
                aiStarePickScheme = p.getBoolean(
                    ConfigKeys.AISTARE_PICK_SCHEME, ConfigKeys.DEFAULT_AISTARE_PICK_SCHEME,
                ),
            )
        } catch (t: Throwable) {
            GLog.w("读取配置失败，使用默认值", t)
            ConfigSnapshot.DEFAULT
        }
    }
}
