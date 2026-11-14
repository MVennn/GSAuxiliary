package io.github.MVennn.GSAuxiliary.core

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
 *
 * ## 变更合流（2026-11-14）
 *
 * 一次「重置配置 / 恢复备份 / 切主题预设」会连写 30+ 个快照键，框架对**每个键**
 * 各推一条 Binder 通知。旧的实现每条通知都跑一趟 [read]：33 次 `getXxx` +
 * 构造 34 字段的快照 + 指纹里 3 个 JSON 的 O(n) `hashCode`，末尾还各 post 一次
 * 主线程 —— 恢复一次备份等于把同一份工作做 30 遍。
 *
 * 现在每条通知只做两件小事（置脏标记、排队一趟），真正的工作在
 * [DEBOUNCE_MS] 之后由专用线程跑**一次**：
 *
 *  - **延后不丢弃**（CODE-RULES §2.3）：窗口内不论来多少次通知，最后一趟一定跑；
 *    [drain] 在读的过程中若又有通知进来，脏标记会再置一次并重新排队。
 *  - 指纹没变就什么都不做（关掉的一个键又改回来，不打扰上层）。
 *  - [close] 会取消挂起的那一趟并关掉线程 —— 热重载时旧实例不许再回调。
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

    /* ---- 变更合流的状态（全部以 [drainLock] 或原子量为界） ---- */

    private val drainLock = Any()

    /** 单线程调度器：只在 [open] 之后存在，[close] 时关掉 */
    private var scheduler: ScheduledExecutorService? = null

    /** 已排队的那一趟（用于去重，不重复排） */
    private var scheduled: ScheduledFuture<*>? = null

    /** 有未消费的通知 */
    private val dirty = AtomicBoolean(false)

    /** 本轮窗口内合并了多少条键通知（只用于日志） */
    private val mergedKeys = AtomicInteger(0)

    /**
     * 本轮窗口内出现过的键名（去重、最多记 [MERGED_KEY_LOG_MAX] 个，只用于日志）。
     *
     * 只记「最后一条」在批量改配置时会误导：恢复备份一次写 30 个键，日志却只写
     * 最后一个键，看不出到底动了哪一片。这里按到达顺序留前几个，够定位就行。
     */
    private val mergedNames = ArrayDeque<String>()

    /** 最近是否来过一次框架整批推送（key=null），只用于日志 */
    @Volatile
    private var batchPush = false

    /** [close] 之后置 true：既挡新通知，也挡已排队的那一趟 */
    @Volatile
    private var closed = true

    private val changeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // 非快照键（主题 / 语言 / 玻璃 / 桌面图标 / 可预测返回）**目标进程不需要知道**
            // —— 它们走同一个 remote preferences 分组，改一下也会推一条通知过来。
            // 直接挡掉，连排队都不用。
            // key 为 null = 框架整批推送，那一包里什么键都可能有，不能挡。
            if (key != null && key !in ConfigKeys.SNAPSHOT_KEYS) return@OnSharedPreferenceChangeListener
            noteKey(key)
            mergedKeys.incrementAndGet()
            dirty.set(true)
            scheduleDrain()
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
            prefs = p
            // 先把调度器与状态准备好，再注册监听：注册与首帧读之间若来了一条通知，
            // 那一趟能正常排上（首帧读本身也会读到新值，不会丢状态）。
            scheduler = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "gszx-config-drain").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }
            closed = false
            p.registerOnSharedPreferenceChangeListener(changeListener)
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
        closed = true
        val p = prefs
        prefs = null
        if (p != null) {
            runCatching { p.unregisterOnSharedPreferenceChangeListener(changeListener) }
        }
        // 取消挂起的那一趟：旧实例的 onChanged 闭包指着旧的 FeatureManager，
        // 热重载后再跑一次就是对着已经卸掉的钩子做还原。
        synchronized(drainLock) {
            scheduled?.cancel(false)
            scheduled = null
        }
        scheduler?.shutdownNow()
        scheduler = null
        dirty.set(false)
        mergedKeys.set(0)
        synchronized(mergedNames) { mergedNames.clear() }
        batchPush = false
    }

    /** 记一个键名（去重、封顶），只用于日志 */
    private fun noteKey(key: String?) {
        if (key == null) {
            batchPush = true
            return
        }
        synchronized(mergedNames) {
            if (mergedNames.size < MERGED_KEY_LOG_MAX && !mergedNames.contains(key)) mergedNames.addLast(key)
        }
    }

    /** 取走本轮键名清单（并清空）：没有键名时退化成「整批推送」或「?」 */
    private fun takeMergedNames(): String = synchronized(mergedNames) {
        val text = when {
            mergedNames.isNotEmpty() -> mergedNames.joinToString(",")
            batchPush -> "整批推送"
            else -> "?"
        }
        mergedNames.clear()
        batchPush = false
        text
    }

    /** 排一趟（已有一趟在等就直接复用，不叠加） */
    private fun scheduleDrain() {
        synchronized(drainLock) {
            if (closed) return
            val exec = scheduler ?: return
            val waiting = scheduled
            if (waiting != null && !waiting.isDone) return
            scheduled = runCatching {
                exec.schedule({ drain() }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            }.getOrNull()
        }
    }

    /** 合并窗口到期：读一次、比一次、必要时回调一次 */
    private fun drain() {
        synchronized(drainLock) { scheduled = null }
        if (closed) return
        // 消费脏标记；读的过程中新来的通知会把脏标记再置一次并重新排队
        if (!dirty.compareAndSet(true, false)) return
        if (prefs == null) return

        val merged = mergedKeys.getAndSet(0)
        val names = takeMergedNames()
        val fresh = read()
        // read() 期间可能已经把实例关掉（热重载会 close 旧实例再 new 一个）：
        // 此时既不该改 snapshot，也不该回调 —— 旧的 onChanged 指向已被卸载的 FeatureManager，
        // 那一趟下去会往已 unhook 的现场写配置，还会把 GLog 级别改回去。
        if (closed) return
        val freshFingerprint = fresh.fingerprint
        if (freshFingerprint == snapshot.fingerprint) {
            GLog.d("配置变更合流 $merged 条（$names），指纹未变 $freshFingerprint")
            return
        }
        snapshot = fresh
        // 只打「哪个键变了 + 合流条数 + 指纹」，**不要整份快照**：快照里带着
        // `aiStareQuickConfig`（方案 JSON，KB 级），改一次开关就往 512KB
        // 的滚动日志里灌一条，历史很快被冲掉，真出问题时反而没上下文。
        GLog.i("配置已更新（$names，合流 $merged 条）指纹=$freshFingerprint")
        if (closed) return
        runCatching { onChanged?.invoke(fresh) }
            .onFailure { GLog.w("配置变化回调异常", it) }
    }

    private companion object {
        /** 合流窗口：人手操作的最小间隔也有几十毫秒，40ms 足够吃下连写，又不影响手感 */
        const val DEBOUNCE_MS = 40L

        /** 日志里最多列几个键名：再多也没人看，还占滚动日志的宽度 */
        const val MERGED_KEY_LOG_MAX = 4
    }

    private fun read(): ConfigSnapshot {
        val p = prefs ?: return ConfigSnapshot.DEFAULT
        return try {
            ConfigSnapshot(
                enabled = p.getBoolean(ConfigKeys.ENABLED, ConfigKeys.DEFAULT_ENABLED),
                color = p.getInt(ConfigKeys.COLOR, ConfigKeys.DEFAULT_COLOR),
                watchlistBold = p.getBoolean(ConfigKeys.WATCHLIST_BOLD, ConfigKeys.DEFAULT_WATCHLIST_BOLD),
                scopeStrategy = p.getBoolean(ConfigKeys.SCOPE_STRATEGY, ConfigKeys.DEFAULT_SCOPE_STRATEGY),
                logLevel = p.getInt(ConfigKeys.LOG_LEVEL, ConfigKeys.DEFAULT_LOG_LEVEL),
                stgDirectQuote = p.getBoolean(ConfigKeys.STG_DIRECT_QUOTE, ConfigKeys.DEFAULT_STG_DIRECT_QUOTE),
                stgQuoteSwipe = p.getBoolean(ConfigKeys.STG_QUOTE_SWIPE, ConfigKeys.DEFAULT_STG_QUOTE_SWIPE),
                stgSelectAll = p.getBoolean(ConfigKeys.STG_SELECT_ALL, ConfigKeys.DEFAULT_STG_SELECT_ALL),
                stgSnapAdd = p.getBoolean(ConfigKeys.STG_SNAP_ADD, ConfigKeys.DEFAULT_STG_SNAP_ADD),
                stgStockFilter = p.getBoolean(ConfigKeys.STG_STOCK_FILTER, ConfigKeys.DEFAULT_STG_STOCK_FILTER),
                stgFilterPerStrategy = p.getBoolean(
                    ConfigKeys.STG_FILTER_PER_STRATEGY, ConfigKeys.DEFAULT_STG_FILTER_PER_STRATEGY,
                ),
                stgMerge = p.getBoolean(ConfigKeys.STG_MERGE, ConfigKeys.DEFAULT_STG_MERGE),
                stgMergeGroups = p.getString(
                    ConfigKeys.STG_MERGE_GROUPS, ConfigKeys.DEFAULT_STG_MERGE_GROUPS,
                ) ?: ConfigKeys.DEFAULT_STG_MERGE_GROUPS,
                stockBarEntry = p.getBoolean(ConfigKeys.STOCK_BAR_ENTRY, ConfigKeys.DEFAULT_STOCK_BAR_ENTRY),
                dockClean = p.getBoolean(ConfigKeys.DOCK_CLEAN, ConfigKeys.DEFAULT_DOCK_CLEAN),
                dockEntries = p.getString(ConfigKeys.DOCK_ENTRIES, ConfigKeys.DEFAULT_DOCK_ENTRIES)
                    ?: ConfigKeys.DEFAULT_DOCK_ENTRIES,
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
                zxGroupCopy = p.getBoolean(
                    ConfigKeys.ZX_GROUP_COPY, ConfigKeys.DEFAULT_ZX_GROUP_COPY,
                ),
                zxCopyMenu = p.getBoolean(
                    ConfigKeys.ZX_COPY_MENU, ConfigKeys.DEFAULT_ZX_COPY_MENU,
                ),
                zxCopyRules = p.getString(
                    ConfigKeys.ZX_COPY_RULES, ConfigKeys.DEFAULT_ZX_COPY_RULES,
                ) ?: ConfigKeys.DEFAULT_ZX_COPY_RULES,
            )
        } catch (t: Throwable) {
            GLog.w("读取配置失败，使用默认值", t)
            ConfigSnapshot.DEFAULT
        }
    }
}
