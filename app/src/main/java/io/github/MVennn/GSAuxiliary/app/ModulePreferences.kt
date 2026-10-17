package io.github.MVennn.GSAuxiliary.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.GLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * 模块 App 侧的配置仓库。
 *
 * 两个后端：
 *  - **REMOTE**：`XposedService.getRemotePreferences(group)`，由框架托管，
 *    写进去的值目标进程立刻能看到（并会被框架推送给被注入的进程）。
 *    这是正常状态下的唯一真实来源。
 *  - **LOCAL**：模块自己的 `SharedPreferences`。只在框架未连接时兜底
 *    （例如刚装好还没在 LSPosed 里勾选）。框架一连接，本地值会**一次性**迁移到远端。
 *
 * 迁移前的实现依赖「manifest 声明 xposedsharedprefs + 写盘后 chmod 0664」，
 * 那个方案在 API 102 下既无必要也不合法（现代模块不能调用 legacy Xposed API）。
 */
object ModulePreferences {

    enum class Backend { REMOTE, LOCAL, NONE }

    private var local: SharedPreferences? = null

    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var initialized = false

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** 变更通知统一切到主线程，UI 订阅方不必自己 post */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 待执行的延迟写入（只保留最后一次，见 [editDeferred]） */
    private val pendingEdit = AtomicReference<Runnable?>(null)

    /** 延迟写入的默认延迟：必须盖过 Miuix 弹层的收起动画（约 250~300ms） */
    private const val EditDeferredMillis = 360L

    private val changeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> notifyChanged() }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return

        val ctx = context.applicationContext
        val lp = ctx.getSharedPreferences(ConfigKeys.GROUP, Context.MODE_PRIVATE)
        local = lp
        lp.registerOnSharedPreferenceChangeListener(changeListener)

        // 框架服务连接/断开时切换后端
        FrameworkBridge.addListener { svc ->
            val rp = svc?.let { s ->
                runCatching { s.getRemotePreferences(ConfigKeys.GROUP) }.getOrNull()
            }
            if (rp !== remote) {
                remote?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(changeListener) } }
                rp?.registerOnSharedPreferenceChangeListener(changeListener)
                remote = rp
            }
            if (rp != null) {
                GLog.i("配置后端 = REMOTE（框架已连接）")
                migrateLocalToRemote(lp, rp)
            } else {
                GLog.w("配置后端 = LOCAL（框架未连接，改动暂存在本地）")
            }
            notifyChanged()
        }

        // 全部就绪后再置位：先置位后赋值的话，并发 init 会读到 null local
        initialized = true
    }

    /** 框架刚连上、远端还空着时，把本地暂存的值搬过去 */
    private fun migrateLocalToRemote(lp: SharedPreferences, rp: SharedPreferences) {
        val localAll = lp.all
        if (localAll.isEmpty()) return
        if (rp.all.isNotEmpty()) return

        runCatching {
            val e = rp.edit()
            for ((k, v) in localAll) {
                when (v) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is String -> e.putString(k, v)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        e.putStringSet(k, v as Set<String>)
                    }
                }
            }
            if (e.commit()) GLog.i("已把本地暂存的 ${localAll.size} 项配置迁移到 remote preferences")
        }.onFailure { GLog.w("迁移本地配置失败", it) }
    }

    fun backend(): Backend = when {
        remote != null -> Backend.REMOTE
        local != null -> Backend.LOCAL
        else -> Backend.NONE
    }

    fun backendLabel(): String = when (backend()) {
        Backend.REMOTE -> "框架 remote preferences"
        Backend.LOCAL -> "本地暂存（框架未连接）"
        Backend.NONE -> "未初始化"
    }

    private fun active(): SharedPreferences? = remote ?: local

    fun getBoolean(key: String, def: Boolean): Boolean = active()?.getBoolean(key, def) ?: def

    /**
     * 读**只属于本机**的界面状态 —— **永远走本地文件，不碰 remote preferences**。
     *
     * ## 为什么必须有这么一对方法
     *
     * [active] 的后端是**异步切换**的：
     *  1. 进程刚起来时框架还没连上 → `active()` = `local`；
     *  2. 框架连上后 → `active()` = `remote`（LSPosed 的远端存储）。
     *
     * 于是「启动第 1 帧读到的值」和「300ms 后读到的值」可能**不是一个地方的值**。
     * 免责声明同意状态踩的就是这个坑：首次读本地（没有这个键 → false）→ 弹窗弹出，
     * 随后远端接管（那里存着 true）→ 状态翻转 → 弹窗当场被收掉，
     * 表现就是「每次启动都闪一下就没」。
     *
     * 更要命的是语义：同意状态是**本机界面状态**，与目标进程的配置毫无关系。
     * 写进远端存储后，「清除应用数据」清不掉它 —— 和"数据被清除就该重新同意"正好相反。
     *
     * ## 用在哪
     *
     * 只用于与目标进程无关的界面状态（目前只有免责声明同意标记）。**配置项不要用**，
     * 那些必须经 `remote` 同步给目标进程。
     */
    fun getLocalBoolean(key: String, def: Boolean): Boolean = local?.getBoolean(key, def) ?: def

    /** 写本机专用状态（见 [getLocalBoolean]）。写本地文件同样会触发 [observe] 回调。 */
    fun setLocalBoolean(key: String, value: Boolean): Boolean = runCatching {
        local?.edit()?.putBoolean(key, value)?.commit() ?: false
    }.getOrElse { t ->
        GLog.w("写入本机状态失败: $key", t)
        false
    }

    fun getInt(key: String, def: Int): Int = active()?.getInt(key, def) ?: def

    fun getString(key: String, def: String): String = active()?.getString(key, def) ?: def

    fun all(): Map<String, *> = active()?.all ?: emptyMap<String, Any>()

    /** 只取 [ConfigKeys.ALL_KEYS] 范围内的配置（备份用，避免把无关键带出去） */
    fun snapshotConfig(): Map<String, Any?> {
        val all = all()
        return ConfigKeys.ALL_KEYS.associateWith { all[it] }
    }

    /** 批量写入（备份恢复 / 重置共用）。值为 null 表示删除该键，读取时回落到默认值。 */
    fun putAll(values: Map<String, Any?>): Boolean = edit {
        for ((k, v) in values) {
            when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
                is Float -> putFloat(k, v)
                is String -> putString(k, v)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    putStringSet(k, v as Set<String>)
                }

                null -> remove(k)
                else -> putString(k, v.toString())
            }
        }
    }

    /** 恢复出厂设置：把 [ConfigKeys.DEFAULTS] 整体写回，不残留旧键 */
    fun resetToDefaults(): Boolean = edit {
        clear()
        for ((k, v) in ConfigKeys.DEFAULTS) {
            when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is String -> putString(k, v)
            }
        }
    }

    /**
     * **「重置模块」：关闭全部功能开关，并把功能参数恢复默认。**
     *
     * 功能开关键仍然**显式写 `false`，而不是写回 [ConfigKeys.DEFAULTS]** ——
     * 2.11.2 起功能开关的出厂默认已经就是 `false`，两者结果相同，
     * 但显式写 false 表达的是「重置 = 关掉」这个语义，不跟着默认值联动：
     * 哪天某个功能又想默认开启，重置逻辑不必跟着改（改了一旦写回默认值，
     * 重置就等于没关，用户会以为重置失效 —— 这正是 2.3.1 修过的 bug）。
     *
     * 不动模块级设置（桌面图标 / 液态玻璃 / 光效 / 语言 / 深色模式 / 可预测返回 / 日志等级），
     * 也不退出设置页。
     */
    fun resetAllFeatures(): Boolean = edit { writeFeatureDefaults() }

    /**
     * 与 [resetAllFeatures] 完全相同，但推迟到弹层收起动画之后再落盘。
     *
     * 「重置模块」的二次确认走的是 Miuix `OverlayDialog`，同步写配置会在它自己
     * 的收起动画里触发重组 —— 和三个下拉是同一类崩溃，详见 [editDeferred]。
     */
    fun resetAllFeaturesDeferred() {
        editDeferred { writeFeatureDefaults() }
    }

    private fun SharedPreferences.Editor.writeFeatureDefaults() {
        for (k in ConfigKeys.FEATURE_KEYS) putBoolean(k, false)
        for (k in ConfigKeys.FEATURE_PARAM_KEYS) {
            when (val v = ConfigKeys.DEFAULTS[k]) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is String -> putString(k, v)
            }
        }
    }

    /** 写入配置，返回是否成功 */
    fun edit(block: SharedPreferences.Editor.() -> Unit): Boolean {
        val target = active() ?: return false
        return runCatching {
            val e = target.edit()
            e.block()
            e.commit()
        }.getOrElse { t ->
            GLog.w("写入配置失败", t)
            false
        }
    }

    /**
     * **延迟写入 —— 专门给 Miuix 弹层（`OverlayDropdownPreference` / `OverlayDialog`）用。**
     *
     * ## 为什么必须延迟（这是「三个下拉一点就闪退」的根因）
     *
     * Miuix 的弹层在它**自己的 `onClick` 里同步开始收起动画**。此时如果立刻
     * `commit()`，`OnSharedPreferenceChangeListener` 会同步回调 →
     * [observe] 的订阅方（`rememberXxxPref`）当场改写 Compose 状态 →
     * 弹层正在跑的收起动画里，它的入参（`selectedIndex` / `items`）被换掉，
     * 动画与重组打架 → 直接崩。
     *
     * 2.3.0 已经定位过这个缺陷并用延迟写入规避；2.5.0 把弹层换回 Miuix 官方组件时
     * 一并删掉了延迟，于是缺陷回归 —— 所以这里恢复它。
     *
     * ## 做法
     *
     * 推迟 [EditDeferredMillis]（盖过弹层的收起动画），期间连续选择**取消上一次**，
     * 只落最后一次。回调（非 Composable）里也能安全调用。
     */
    fun editDeferred(
        delayMillis: Long = EditDeferredMillis,
        block: SharedPreferences.Editor.() -> Unit,
    ) {
        pendingEdit.getAndSet(null)?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable { edit(block) }
        pendingEdit.set(task)
        mainHandler.postDelayed(task, delayMillis)
    }

    fun observe(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unobserve(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 通知所有订阅方「配置变了」。
     *
     * **一律 post 到主线程队列的下一次循环**，而不是「已经在主线程就直接跑」：
     * 后者会让状态更新发生在**触发写入的那个 UI 回调内部**（例如 Miuix 弹层正在
     * 收起动画中），重组与动画撞在一起。推迟一轮之后回调已经返回、弹层状态稳定。
     */
    private fun notifyChanged() {
        mainHandler.post {
            for (l in listeners) {
                runCatching { l() }.onFailure { GLog.w("配置变化回调异常", it) }
            }
        }
    }
}
