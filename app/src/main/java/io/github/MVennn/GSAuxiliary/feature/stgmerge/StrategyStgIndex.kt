package io.github.MVennn.GSAuxiliary.feature.stgmerge

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.MVennn.GSAuxiliary.core.GLog
import org.json.JSONObject

/**
 * 目标进程内的「策略名称 → stId」索引。
 *
 * ## 为什么需要它
 *
 * 聚合组要按 stId 去借宿主的取数通道，可 stId 是宿主内部的 15~19 位数字，用户在模块设置页
 * 里根本看不到、也没法抄。而策略**名称**是他天天在「我的策略」里看到的东西。
 *
 * 于是配置里允许成员只填名称，stId 由本索引在运行时补齐：
 *
 *  - 「我的策略」列表页一次性带出全部策略对象 → 整表 [remember]；
 *  - 策略详情页打开时再补一条（列表页可能还没轮到）；
 *  - 聚合判定时 [idOf] 反查。
 *
 * ## 为什么落盘
 *
 * 早先只活在内存里，结果真机撞上：从列表点进详情时列表页那一轮采集还没跑完、
 * 或者页面切换把内存状态让位给别的 Activity，索引就空了 —— 成员全是「只填名称」时
 * 一个也解析不出来，日志「成员名称还没在宿主里见过，本轮先跳过」，用户看到的就是
 * 「有时候聚合有时候不聚合」。
 *
 * 现在每次采集后把「名称 → stId」「stId → 标签」写进宿主自己的 SharedPreferences
 * （文件名 [PREFS_NAME]，不碰模块配置），下个页面/下次冷启动先读盘再用。
 */
object StrategyStgIndex {

    /** 宿主私有目录里的缓存文件名（与模块配置完全分开，卸载宿主即清） */
    private const val PREFS_NAME = "gsaux_stg_index"

    private const val KEY_INDEX = "index"

    /** 采集完不立刻写盘：列表页巡检每 800ms 跑一轮，攒一下再落 */
    private const val FLUSH_DELAY_MS = 500L

    /** 名称 → stId；同名策略以最后一次采集到的为准（宿主允许同名策略） */
    private val byName = HashMap<String, String>(32)

    /** stId → 标签名（`Strategy.tags` JSON 里的 labelName，按卡片顺序） */
    private val tagsById = HashMap<String, List<String>>(32)

    private var prefs: SharedPreferences? = null

    private var loaded = false

    private var dirty = false

    private val main = Handler(Looper.getMainLooper())

    private val flushTask = Runnable { flushNow() }

    /**
     * 绑定宿主 SharedPreferences 并首次读盘。
     *
     * 幂等：只有第一次真正读盘，之后的调用只保证 [prefs] 已就绪。
     * 读盘不会覆盖本轮已经采到的条目（宿主刚改名时以新名为准）。
     */
    @Synchronized
    fun bind(ctx: Context?) {
        if (prefs != null || ctx == null) return
        val app = ctx.applicationContext ?: ctx
        prefs = runCatching { app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()
        loadNow()
    }

    /** 读盘：只补内存里还没有的键 */
    private fun loadNow() {
        if (loaded) return
        loaded = true
        val raw = runCatching { prefs?.getString(KEY_INDEX, null) }.getOrNull() ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val names = root.optJSONObject("names") ?: JSONObject()
        var added = 0
        for (k in names.keys()) {
            val n = k.trim()
            val sid = names.optString(k).trim()
            if (n.isEmpty() || sid.isEmpty() || byName.containsKey(n)) continue
            byName[n] = sid
            added++
        }
        val tags = root.optJSONObject("tags") ?: JSONObject()
        for (k in tags.keys()) {
            val sid = k.trim()
            if (sid.isEmpty() || tagsById.containsKey(sid)) continue
            val arr = tags.optJSONArray(k) ?: continue
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.optString(i).trim()
                if (v.isNotEmpty()) list.add(v)
            }
            if (list.isNotEmpty()) tagsById[sid] = list
        }
        if (added > 0) GLog.i("策略聚合： 索引从磁盘恢复 $added 条（累计 ${byName.size}）")
    }

    /** 记录一条「名称 → stId」，两端都非空才记 */
    @Synchronized
    fun remember(name: String?, stId: String?) {
        val n = name?.trim().orEmpty()
        val sid = stId?.trim().orEmpty()
        if (n.isEmpty() || sid.isEmpty()) return
        if (byName[n] == sid) return
        byName[n] = sid
        scheduleFlush()
    }

    /** 按名称查 stId；没采集到返回 null（调用方按「这个成员先跳过」处理） */
    @Synchronized
    fun idOf(name: String?): String? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        return byName[n]
    }

    /** 记下一条策略的标签（详情页的 m_tagList 只反映当前页策略，成员标签得靠这里） */
    @Synchronized
    fun rememberTags(stId: String?, tags: List<String>) {
        val sid = stId?.trim().orEmpty()
        if (sid.isEmpty() || tags.isEmpty()) return
        if (tagsById[sid] == tags) return
        tagsById[sid] = tags
        scheduleFlush()
    }

    /** 按 stId 取标签；没采集到返回空表（调用方按「这条成员没标签」处理） */
    @Synchronized
    fun tagsOf(stId: String?): List<String> {
        val sid = stId?.trim().orEmpty()
        if (sid.isEmpty()) return emptyList()
        return tagsById[sid] ?: emptyList()
    }

    /** 一条宿主策略：清单广播的最小单位（名称给人看、stId 用于取数、标签用于详情页汇总） */
    data class Entry(val stId: String, val name: String, val tags: List<String>)

    /**
     * 整表快照，按名称升序。
     *
     * 「从金太阳导入」拿它铺列表：名称给人认、stId 写进成员配置。
     * 只收名称与 stId 都非空的条目 —— 缺一半的条目写进配置也用不了。
     * 名称排序是为了界面顺序稳定：HashMap 的遍历顺序每次进程重启都可能变，
     * 用户会以为「导入列表自己在跳」。
     */
    @Synchronized
    fun entries(): List<Entry> =
        byName.map { (name, sid) -> Entry(sid, name, tagsById[sid].orEmpty()) }
            .sortedBy { it.name }

    /** 已知多少条（只用于日志） */
    @Synchronized
    fun size(): Int = byName.size

    /** 已登记的名称样本（只用于日志排查「为什么解析不出来」） */
    @Synchronized
    fun namesSnapshot(limit: Int = 12): String {
        if (byName.isEmpty()) return "空"
        val head = byName.keys.take(limit).joinToString("、")
        return if (byName.size > limit) head + "…" else head
    }

    /** 攒一下再写盘（列表页巡检很密，每次 remember 都写会抖） */
    private fun scheduleFlush() {
        if (prefs == null) return
        dirty = true
        main.removeCallbacks(flushTask)
        main.postDelayed(flushTask, FLUSH_DELAY_MS)
    }

    /** 立即落盘（页面销毁前调用，避免攒着的那一轮丢掉） */
    @Synchronized
    fun flushNow() {
        if (!dirty) return
        val p = prefs ?: return
        dirty = false
        val json = runCatching {
            val root = JSONObject()
            root.put("v", 1)
            val names = JSONObject()
            for ((k, v) in byName) names.put(k, v)
            root.put("names", names)
            val tags = JSONObject()
            for ((k, v) in tagsById) {
                val arr = org.json.JSONArray()
                for (t in v) arr.put(t)
                tags.put(k, arr)
            }
            root.put("tags", tags)
            root.toString()
        }.getOrNull() ?: return
        runCatching { p.edit().putString(KEY_INDEX, json).apply() }
    }

    /** 清空（功能关闭 / 卸载时调用） */
    @Synchronized
    fun clear() {
        byName.clear()
        tagsById.clear()
        dirty = true
        flushNow()
    }
}
