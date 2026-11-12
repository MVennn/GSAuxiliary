package io.github.MVennn.GSAuxiliary.feature.stgmerge

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.StgCatalogBroadcast
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把「宿主里有哪些策略」广播给模块 App（宿主进程侧，见 core/StgCatalogBroadcast 的类注释）。
 *
 * ## 什么时候发
 *
 * 只在「我的策略」列表页发，而且发的是**这一轮适配器里实际存在的条目**（[publish] 的 entries 参数），
 * 不是 [StrategyStgIndex] 的全量历史：索引只增不删，用户删掉的策略、早前用接口批量建的策略
 * 会一直留在索引里（真机症状：导入清单里冒出宿主列表根本没有的 T4 / TPMP50）。
 * 列表页每 800ms 巡检一轮，实际发送被两道节流拦住：
 *
 *  - 距上次发送不足 [MIN_INTERVAL_MS] 直接返回（巡检很密，不能每轮都发）；
 *  - 内容和上次一样且还没到 [RESEND_INTERVAL_MS]，也返回（清单基本不动）。
 *
 * 内容变了就立刻发 —— 用户新建 / 改名策略后回到模块设置页，看到的应当是新名字。
 *
 * ## 为什么不在这里落盘
 *
 * 宿主侧的 [StrategyStgIndex] 已经把同一份数据写进宿主私有 prefs 了，这里再写一遍是重复；
 * 接收端（模块 App）拿到后自己存。
 */
object StrategyStgCatalog {

    private const val LOG = "策略清单："

    /** 列表页巡检 800ms 一轮，清单几乎不动 —— 3 秒一次足够，也不会把广播刷爆 */
    private const val MIN_INTERVAL_MS = 3_000L

    /** 内容没变时最多每 30 秒补发一次：模块 App 可能刚装 / 刚清过数据，重发一次就能对上 */
    private const val RESEND_INTERVAL_MS = 30_000L

    private val lock = Any()

    /** 上次发出去的**条目部分**（不含时间戳）：时间戳每次都变，拿整份载荷比会永远「有变化」 */
    private var lastKey: String = ""

    private var lastAt: Long = 0L

    /**
     * 广播一次策略清单（宿主进程调用）。
     *
     * [entries] 必须是**这一轮列表页适配器里的条目**：[StrategyStgIndex] 只负责「名称 → stId」
     * 的历史解析，拿它当清单源会把已删除 / 接口建过又没了的策略一起导出去。
     *
     * 条目为空时不发：发一份空清单会把模块 App 里已有的缓存清掉，用户看到的是
     * 「打开一次宿主列表」这种提示 —— 而他明明刚打开过（首轮采集还没跑完）。
     */
    fun publish(
        ctx: Context?,
        entries: List<StrategyStgIndex.Entry>,
        entryLabels: Set<String> = emptySet(),
    ) {
        if (ctx == null) return
        val payload = build(entries, entryLabels) ?: return
        val now = SystemClock.elapsedRealtime()
        val changed: Boolean
        synchronized(lock) {
            if (now - lastAt < MIN_INTERVAL_MS) return
            changed = payload.key != lastKey
            if (!changed && now - lastAt < RESEND_INTERVAL_MS) return
            lastKey = payload.key
            lastAt = now
        }
        val app = ctx.applicationContext ?: ctx
        runCatching {
            val intent = Intent(StgCatalogBroadcast.ACTION_CATALOG)
            intent.setPackage(StgCatalogBroadcast.MODULE_PACKAGE)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            intent.putExtra(StgCatalogBroadcast.EXTRA_PAYLOAD, payload.json)
            intent.putExtra(StgCatalogBroadcast.EXTRA_SIG, StgCatalogBroadcast.SIG)
            app.sendBroadcast(intent)
            if (changed) {
                // 日志报实际发出去的条数（扣掉入口卡），与接收端「已收到 N 条」能对上
                GLog.i(LOG + "已广播宿主策略清单 " + payload.count + " 条")
            }
        }.onFailure { GLog.w(LOG + "广播失败", it) }
    }

    /**
     * 这个名称是不是我们自己写上去的入口卡名。
     *
     * 判据只有两条，对应 [StrategyMergeFeature] 里入口卡改名的两种写法：
     * 现行的「聚合 · <组名>（N 条）」前缀，以及旧版本留下的「（N 条）」后缀
     * （旧数据里还有不带条数的「聚合 · <组名>」，前缀那条一起拦下）。
     * 宿主里真有用户手动起成这样的策略才会误伤，代价可以接受：少一条导入项，不污染列表。
     */
    internal fun looksLikeEntryLabel(name: String): Boolean {
        val n = name.trim()
        if (n.startsWith("聚合 · ")) return true
        return Regex("（\\d+\\s*条）$").containsMatchIn(n)
    }

    /** 一份待发载荷：key 用于判「内容变了没有」，count 是实际发出去的条目数 */
    private class Payload(val key: String, val json: String, val count: Int)

    /** 组 payload；条目为空或编码失败返回 null（调用方按「这轮不发」处理） */
    private fun build(live: List<StrategyStgIndex.Entry>, entryLabels: Set<String>): Payload? {
        if (live.isEmpty()) return null
        return runCatching {
            val arr = JSONArray()
            var count = 0
            for (e in live) {
                // 我们自己改过名的入口卡不能进清单：它在宿主里叫「聚合 · 中盘 1.5-4（13 条）」，
                // 底下借的是别的策略的 stId，导进来既不是真策略、名字还会误导人。
                // 卡名去掉「（N 条）」以后，组名自己就以「聚合」开头的组（如「聚合组2」）
                // 卡名是裸组名，靠前缀/后缀正则认不出来，所以再按**配置里的组名**精确排除一次。
                if (looksLikeEntryLabel(e.name) || e.name.trim() in entryLabels) continue
                val item = JSONObject()
                item.put("id", e.stId)
                item.put("name", e.name)
                if (e.tags.isNotEmpty()) {
                    val tags = JSONArray()
                    for (t in e.tags) tags.put(t)
                    item.put("tags", tags)
                }
                arr.put(item)
                count++
            }
            if (count == 0) return null
            // key 只取条目部分：时间戳每轮都在变，用整份载荷判重会让节流失效（实测每 3 秒发一次）
            val key = arr.toString()
            val root = JSONObject()
            root.put("v", StgCatalogBroadcast.VERSION)
            root.put("at", System.currentTimeMillis())
            root.put("items", arr)
            Payload(key, root.toString(), count)
        }.getOrNull()
    }
}
