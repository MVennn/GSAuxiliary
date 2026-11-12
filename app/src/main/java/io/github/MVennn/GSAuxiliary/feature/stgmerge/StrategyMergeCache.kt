package io.github.MVennn.GSAuxiliary.feature.stgmerge

import java.lang.ref.WeakReference

/**
 * 聚合结果的跨页面缓存。
 *
 * 为什么要有它：详情页每次进来都要「换 m_stg.id → 发宿主取数命令 → 轮询列表」把所有成员
 * 挨个取一遍。四个成员最少 4 秒，慢一点就有人超时，超时那轮照样写回残缺并集 ——
 * 真机上就是「策略个股」一会儿 212 只、一会儿 100 多只，涨跌家数跟着乱。
 *
 * 这里把上一轮**完整**的并集留一份在内存里：重进页面先秒套这份（用户第一眼就是完整的），
 * 后台再校验一遍，结果与缓存一致就不动界面，不一致才换。只数因此稳定。
 *
 * 只存 [WeakReference]：StockInfo 实例属于宿主，页面销毁后该由 GC 收走；
 * 弱引用被回收就当没缓存，退回重算（只是慢一次，不会给出错的数据）。
 */
internal object StrategyMergeCache {

    /** 一份并集快照：ids 是 codeAndMarket 顺序表，ref 指向宿主自己的 StockInfo 列表 */
    class Snapshot(
        val key: String,
        val ids: List<String>,
        val ref: WeakReference<List<Any>>,
        val up: Int,
        val down: Int,
        val label: String,
        val tags: List<String>,
        val at: Long,
    ) {
        val size: Int get() = ids.size

        /** 还活着的实例；被 GC 收走返回 null */
        fun merged(): List<Any>? = ref.get()

        /** 是否还在有效期（[at] 在未来按过期算，防止时钟回拨把旧结果当新结果） */
        fun isFresh(now: Long, ttlMs: Long): Boolean = now - at in 0..ttlMs

        /** 与另一份是不是同一批票（顺序也要一致：界面顺序跟着它走） */
        fun sameAs(otherIds: List<String>): Boolean = ids == otherIds
    }

    /** 最多留几组；一组 = 一个「组 id + 成员集合」，正常用户远用不到这么多 */
    private const val MAX_ENTRIES = 8

    private val map = LinkedHashMap<String, Snapshot>(4, 0.75f, true)

    fun put(
        key: String,
        merged: List<Any>,
        ids: List<String>,
        up: Int,
        down: Int,
        label: String,
        tags: List<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        synchronized(map) {
            map[key] = Snapshot(key, ids, WeakReference(merged), up, down, label, tags, now)
            // 访问序 LRU：超量淘汰最久没用的那组
            while (map.size > MAX_ENTRIES) {
                val eldest = map.keys.firstOrNull() ?: break
                map.remove(eldest)
            }
        }
    }

    /**
     * 取一份**新鲜且实例还活着**的缓存。
     *
     * 过期或被 GC 收走就地删掉并返回 null —— 调用方按「没有缓存」处理即可。
     */
    fun get(
        key: String,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = StrategyMergeTargets.SNAPSHOT_TTL_MS,
    ): Snapshot? {
        synchronized(map) {
            val s = map[key] ?: return null
            if (!s.isFresh(now, ttlMs) || s.merged() == null) {
                map.remove(key)
                return null
            }
            return s
        }
    }

    /** 只查有没有（不看新鲜度），给日志用 */
    fun size(): Int = synchronized(map) { map.size }

    fun clear() {
        synchronized(map) { map.clear() }
    }
}
