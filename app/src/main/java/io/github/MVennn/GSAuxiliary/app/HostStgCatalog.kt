package io.github.MVennn.GSAuxiliary.app

import android.content.Context
import io.github.MVennn.GSAuxiliary.core.StgCatalogBroadcast
import org.json.JSONObject

/**
 * 模块 App 侧的「金太阳策略清单」缓存。
 *
 * ## 数据从哪来
 *
 * 宿主进程（com.guosen.android）在「我的策略」列表页采集完后广播一份清单，
 * 由 [HostStgCatalogReceiver] 收下并调用 [store] 落到本模块自己的 SharedPreferences。
 * 编辑页「从金太阳导入」只读这里 —— 模块进程读不到宿主的私有数据，
 * LSPosed 的 remote preferences 又是给配置用的（不想把策略清单塞进去污染 33 个配置键）。
 *
 * ## 为什么单独一个 prefs 文件
 *
 * 清单是**别人给的展示数据**，不是配置：卸载宿主、清宿主数据、策略改名都会让它变旧。
 * 与 ModulePreferences 分开，才不会在「本地 → 远端」迁移里把它当成配置搬来搬去。
 */
object HostStgCatalog {

    private const val PREFS_NAME = "gsaux_host_catalog"

    private const val KEY_CATALOG = "catalog"

    /** 一条宿主策略：stId 是宿主内部编号，name 是用户在「我的策略」里看到的名字 */
    data class Item(val stId: String, val name: String, val tags: List<String>)

    /** 一次快照：at = 宿主广播的时间戳（毫秒），items 按名称升序 */
    data class Snapshot(val at: Long, val items: List<Item>) {
        companion object {
            val EMPTY = Snapshot(0L, emptyList())
        }
    }

    /** 读缓存；还没收到过广播时返回 [Snapshot.EMPTY] */
    fun read(context: Context?): Snapshot {
        val raw = runCatching {
            prefs(context)?.getString(KEY_CATALOG, null)
        }.getOrNull()
        return parse(raw)
    }

    /** 存一份广播载荷，返回解析出的条数（广播接收器打日志用） */
    fun store(context: Context?, payload: String?): Int {
        if (payload.isNullOrBlank()) return 0
        val snapshot = parse(payload)
        if (snapshot.items.isEmpty()) return 0
        runCatching {
            prefs(context)?.edit()?.putString(KEY_CATALOG, payload)?.apply()
        }.onFailure { return 0 }
        return snapshot.items.size
    }

    /**
     * 解析广播载荷（纯函数，便于单测）。
     *
     * 坏数据一律当空快照：清单是**可选的辅助数据**，为它把整页设置炸掉不值得。
     * id 或 name 为空的条目直接丢 —— 只有一半的条目写进成员配置也用不了。
     */
    fun parse(raw: String?): Snapshot {
        if (raw.isNullOrBlank()) return Snapshot.EMPTY
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return Snapshot.EMPTY
        if (root.optInt("v", 0) != StgCatalogBroadcast.VERSION) return Snapshot.EMPTY
        val at = root.optLong("at", 0L)
        val arr = root.optJSONArray("items") ?: return Snapshot(at, emptyList())
        val out = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val name = obj.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) continue
            val tagsArr = obj.optJSONArray("tags")
            val tags = ArrayList<String>()
            if (tagsArr != null) {
                for (k in 0 until tagsArr.length()) {
                    val t = tagsArr.optString(k).trim()
                    if (t.isNotEmpty()) tags.add(t)
                }
            }
            out.add(Item(id, name, tags))
        }
        return Snapshot(at, out)
    }

    private fun prefs(context: Context?) =
        context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
