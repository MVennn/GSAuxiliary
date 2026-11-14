package io.github.MVennn.GSAuxiliary.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.GLog
import java.util.concurrent.CopyOnWriteArrayList

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

    /**
     * 断连期间被改过的键（存在本地文件里）。
     *
     * 重连时只补「远端没有的键」会把断连期间的修改整体丢掉 —— 正常使用后
     * 几乎所有功能键都已存在于远端。这个集合记录「用户在 LOCAL 后端写过什么」，
     * 重连时这些键以本地值为准（本地较新），并区分删除（键已不存在 → 远端也删）。
     */
    private const val DIRTY_KEY = "__gszx_local_dirty"

    /**
     * 「本地兜底配置已经搬过一次」的一次性标记（2026-11-14）。
     *
     * ## 为什么需要
     *
     * [migrateLocalToRemote] 由 [FrameworkBridge] 的 service 回调驱动，而那个回调的触发方
     * 是**合并进本 APK 的 `io.github.libxposed.service.XposedProvider`**（exported，无
     * permission）。任何一个 App 都能 `contentResolver.call(...)` 递一个自己的 Binder
     * 冒充框架服务 —— 于是「框架刚连上」被伪造，本地全部偏好被灌进**攻击者**的
     * remote preferences 实现里。
     *
     * 真正的防线在 manifest 侧（给该 provider 加 signature 权限）；这里做的是纵深防御：
     * 迁移只发生一次，且只搬 [ConfigKeys.ALL_KEYS] 白名单内的键，不再把用户表里
     * 任意历史键（含调试残留）成批外送。
     */
    private const val MIGRATED_KEY = "__gszx_migrated"

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

    /**
     * 框架连上后，把本地暂存的配置同步进远端。
     *
     * ## 合并方向（逐 key）
     *
     *  - **远端已有的键一律不动**：远端是权威数据源。旧实现「远端非空即整体
     *    return」虽然也守住了这一点，但会把框架断连期间用户在本地改的开关
     *    整体丢掉 —— 重连后远端非空就直接 return，本地的新值永远迁不过去，
     *    还没有任何提示。现在改成逐 key 合并：只补写「远端没有的键」，
     *    远端已有的键保持原值，避免用本地旧值覆盖远端新值。
     *  - **首次连接（远端为空）时全部键都是缺失键**，效果等同旧版的整体迁移，
     *    「本地兜底值在首次连接时搬进远端」的语义不变。
     *
     * 本方法在 [FrameworkBridge] 的 service listener 回调里被调，写远端必须兜异常，
     * 不能让迁移失败把连接回调炸掉。
     */
    private fun migrateLocalToRemote(lp: SharedPreferences, rp: SharedPreferences) {
        val localAll = lp.all
        if (localAll.isEmpty()) return
        val remoteAll = runCatching { rp.all }.getOrNull() ?: return
        val dirty = (localAll[DIRTY_KEY] as? Set<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet()
        // 已经搬过一次就不再成批外送：只剩「断连期间用户真改过的键」这一条窄通道。
        val migrated = (localAll[MIGRATED_KEY] as? Boolean) == true
        val missing = if (migrated) {
            emptyMap<String, Any>()
        } else {
            localAll.filterKeys { it in ConfigKeys.ALL_KEYS && it !in remoteAll }
        }
        if (missing.isEmpty() && dirty.isEmpty()) return
        GLog.i(
            "框架重连：远端已有 ${remoteAll.size} 个键，本地暂存 ${missing.size} 个缺失键、" +
                "${dirty.size} 个断连期间改过的键"
        )
        var written = 0
        var removed = 0
        val ok = runCatching {
            val e = rp.edit()
            for ((k, v) in missing) {
                // `SharedPreferences.all` 的值不会是 null，但平台签名给的是 Any?
                if (v == null) continue
                putValue(e, k, v)
                written++
            }
            // 断连期间写过的键以本地为准：本地有值就覆盖远端，本地已删除就删远端。
            for (k in dirty) {
                if (k !in ConfigKeys.ALL_KEYS) continue
                val v = localAll[k]
                if (v == null) {
                    e.remove(k)
                    removed++
                } else {
                    putValue(e, k, v)
                    written++
                }
            }
            e.commit()
        }.getOrElse { t ->
            GLog.w("迁移本地配置失败", t)
            false
        }
        if (ok) {
            lp.edit().remove(DIRTY_KEY).putBoolean(MIGRATED_KEY, true).commit()
            GLog.i("已把本地配置同步到 remote preferences（写 $written 项、删 $removed 项）")
        }
    }

    /** 按类型写入一个偏好值。 */
    private fun putValue(e: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> e.putBoolean(key, value)
            is Int -> e.putInt(key, value)
            is Long -> e.putLong(key, value)
            is Float -> e.putFloat(key, value)
            is String -> e.putString(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                e.putStringSet(key, value as Set<String>)
            }
        }
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

    fun getBoolean(key: String, def: Boolean): Boolean = runCatching {
        active()?.getBoolean(key, def) ?: def
    }.getOrElse { t ->
        // 值类型被写错（备份手改 / 调试通道 typo）时 SharedPreferences 抛 CCE：
        // 不兜就会冒到设置页 UI 线程。回落默认值并留证据。
        GLog.w("配置项类型不符，回退默认：$key", t)
        def
    }

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

    /** 读**只属于本机**的整型状态（永远走本地文件，见 [getLocalBoolean]）。 */
    fun getLocalInt(key: String, def: Int): Int = local?.getInt(key, def) ?: def

    /** 写本机专用状态（见 [getLocalBoolean]）。 */
    fun setLocalInt(key: String, value: Int): Boolean = runCatching {
        local?.edit()?.putInt(key, value)?.commit() ?: false
    }.getOrElse { t ->
        GLog.w("写入本机状态失败: $key", t)
        false
    }

    /** 写本机专用状态（见 [getLocalBoolean]）。写本地文件同样会触发 [observe] 回调。 */
    fun setLocalBoolean(key: String, value: Boolean): Boolean = runCatching {
        local?.edit()?.putBoolean(key, value)?.commit() ?: false
    }.getOrElse { t ->
        GLog.w("写入本机状态失败: $key", t)
        false
    }

    fun getInt(key: String, def: Int): Int = runCatching {
        active()?.getInt(key, def) ?: def
    }.getOrElse { t ->
        GLog.w("配置项类型不符，回退默认：$key", t)
        def
    }

    fun getString(key: String, def: String): String = runCatching {
        active()?.getString(key, def) ?: def
    }.getOrElse { t ->
        GLog.w("配置项类型不符，回退默认：$key", t)
        def
    }

    fun all(): Map<String, *> = active()?.all ?: emptyMap<String, Any>()

    /** 只取 [ConfigKeys.ALL_KEYS] 范围内的配置（备份用，避免把无关键带出去） */
    fun snapshotConfig(): Map<String, Any?> {
        val all = all()
        return ConfigKeys.ALL_KEYS.associateWith { all[it] }
    }

    /**
     * 批量写入（备份恢复 / 重置共用）。值为 null 表示删除该键，读取时回落到默认值。
     *
     * **按 [ConfigKeys.DEFAULTS] 的期望类型归一后写入**（2026-11-14）：备份文件是手改得动的
     * JSON，把 `"enabled": "true"` 写成字符串就会落进 prefs；设置页随后 `getBoolean` 抛
     * ClassCastException，宿主侧 `ConfigSource.read()` 抛异常后**整份快照回落默认** ——
     * 22 个功能一起静默失效，用户只能清数据。归不了的类型直接丢弃并留日志，不让脏值落盘。
     * 不在 DEFAULTS 里的键原样按类型写入（调试通道 / 未来新增键不受影响）。
     */
    fun putAll(values: Map<String, Any?>): Boolean = edit {
        for ((k, raw) in values) {
            if (raw == null) {
                remove(k)
                continue
            }
            val def = ConfigKeys.DEFAULTS[k]
            val v = if (def == null) raw else coerce(k, def, raw)
            if (v == null) {
                GLog.w("配置项类型不符，已跳过：$k（期望 ${def?.javaClass?.simpleName}）")
                continue
            }
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

                else -> putString(k, v.toString())
            }
        }
    }

    /**
     * 把 [raw] 归一成 [def] 的类型；归不了返回 null。
     *
     * 只做「不会改变语义」的转换：字符串 `"true"` → true、数字串 → Int、
     * 数字 → 字符串。其它组合一律判失败（宁可丢这一项，也不写一个会让宿主抛异常的值）。
     */
    private fun coerce(key: String, def: Any, raw: Any): Any? = when (def) {
        is Boolean -> when (raw) {
            is Boolean -> raw
            is String -> raw.trim().lowercase().let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }

            is Number -> raw.toInt() != 0
            else -> null
        }

        is Int -> when (raw) {
            is Int -> raw
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }

        is Long -> when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }

        is String -> raw as? String ?: raw.takeIf { it !is Set<*> }?.toString()
        else -> null
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
     *
     * 调用方（重置确认弹窗的 onConfirm）由弹窗宿主排在收起动画播完之后，
     * 这里可以同步执行。
     */
    fun resetAllFeatures(): Boolean = edit { writeFeatureDefaults() }

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

    /**
     * 写入配置，返回是否成功。
     *
     * ## 时序要求
     *
     * 历史上这里还有一层 `editDeferred`（固定延迟 360ms 再落盘），防的是
     * Miuix `OverlayDropdownPreference` 的「一点就闪退」：库的弹层在自己的
     * `onClick` 里同步开始收起动画，此刻 `commit()` 会同步回调改写 Compose
     * 状态，动画与重组打架。弹层换成自绘（`AppDialog`）之后，所有写入都
     * 通过 `DialogController.dismiss { }` 排到收起动画播完之后执行，执行时
     * 弹窗已彻底关闭，同步写是安全的 —— 延迟层随之移除。
     *
     * 仍然不要在「弹窗动画进行中」的回调里调它（例如选项行的 onClick 本体），
     * 那会把旧问题请回来。
     */
    fun edit(block: SharedPreferences.Editor.() -> Unit): Boolean {
        val target = active() ?: return false
        // LOCAL 后端写入要在本地记下「哪些键被改过」，重连时才能把断连期间的
        // 修改（含删除）覆盖回远端，而不是被远端旧值静默吃掉。
        val before = if (target === local) runCatching { target.all.toMap() }.getOrNull() else null
        return runCatching {
            val e = target.edit()
            e.block()
            val ok = e.commit()
            if (ok && before != null) recordDirty(before, target)
            ok
        }.getOrElse { t ->
            GLog.w("写入配置失败", t)
            false
        }
    }

    /** 把本次写入改到的键并进本地 dirty 集合（删除的键也要记，重连时删远端）。 */
    private fun recordDirty(before: Map<String, *>, target: SharedPreferences) {
        runCatching {
            val after = target.all
            val changed = (before.keys + after.keys).filter { key ->
                key != DIRTY_KEY && before[key] != after[key]
            }
            if (changed.isEmpty()) return
            val previous = (target.getStringSet(DIRTY_KEY, null) ?: emptySet()).toMutableSet()
            previous.addAll(changed)
            target.edit().putStringSet(DIRTY_KEY, previous).commit()
        }.onFailure { GLog.w("记录本地改动失败", it) }
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
