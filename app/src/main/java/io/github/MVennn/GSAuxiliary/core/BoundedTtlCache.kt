package io.github.MVennn.GSAuxiliary.core

/**
 * 有界 + 短时的缓存（2026-11-14 加）。
 *
 * ## 用途
 *
 * 「这只股票在不在自选」这类**要过反射、又不要求实时**的判定，列表一滑就是
 * 几百上千次。把结果按 key 记一小段时间，就把反射调用从「每行一次」降到
 * 「每次自选变化一次」。
 *
 * ## 三条硬性质（都有单测钉住）
 *
 *  1. **有界**：最多 [maxEntries] 条，超出按 LRU 淘汰 —— 不看用户点过多少只股票，
 *     内存不会涨；
 *  2. **短时**：[ttlMs] 之后同 key 必然重新求值，自选变化最多晚这么久才反映，
 *     **不会永久错**；
 *  3. **线程安全**：所有操作在内部锁里完成，取值（可能的反射）在锁外做，
 *     不把锁持有到调用方身上。
 *
 * 时钟注入 [clockMs] 是为了单测能直接拨表，不去 sleep。
 */
internal class BoundedTtlCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val clockMs: () -> Long,
) {

    private class Entry<V>(@JvmField val value: V, @JvmField val atMs: Long)

    private val lock = Any()

    /** accessOrder=true：读一次就算「最近用过」，淘汰时先丢最久没问的 */
    private val entries = object : LinkedHashMap<K, Entry<V>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean =
            size > maxEntries
    }

    /** 命中且未过期返回真值；过期视同没有（顺手删掉，别留着占位） */
    fun get(key: K): V? = synchronized(lock) {
        val e = entries[key]
        when {
            e == null -> null
            clockMs() - e.atMs > ttlMs -> {
                entries.remove(key)
                null
            }
            else -> e.value
        }
    }

    fun put(key: K, value: V) {
        synchronized(lock) { entries[key] = Entry(value, clockMs()) }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /** 仅供测试与诊断 */
    val size: Int get() = synchronized(lock) { entries.size }
}