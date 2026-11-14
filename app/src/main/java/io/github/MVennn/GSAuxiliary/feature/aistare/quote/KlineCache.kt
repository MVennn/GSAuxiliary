package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.QuoteBar

/**
 * 日 K 缓存：**真 LRU + 根数预算**（2026-11-14 重写）。
 *
 * 旧实现是两个行情通道各一张 `HashMap`，淘汰写成 `if (size >= 16) clear()`：
 * 满了就把 15 条热数据一起扔掉，下一个取值项再从头打一遍接口；而上限那头
 * 只按「只数」数，不按「根数」数 —— 一只「最近前高」要 2000 根，
 * 16 只就是 4 万根、约 8 MB 常驻（缓存跟着 fetcher 活到进程结束）。
 *
 * 现在两条线同时管：
 *  - [maxEntries]：最多缓存几只；
 *  - [maxBars]：最多缓存多少根 K 线（内存真正的量纲）。
 * 超了按「最久没用」逐条淘汰，不再清空；内存上限因此可算：
 * `maxBars × 每根约 260 B`（[QuoteBar] 五个 String）。
 *
 * 命中判据与旧实现一致（[get]）：TTL 内且「行数够 n 或上次要的就这么多」。
 * 线程安全：所有状态都在 [lock] 内，允许跨线程调用（取值在 IO 线程）。
 */
internal class KlineCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBars: Int = DEFAULT_MAX_BARS,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private class Entry(
        @JvmField val at: Long,
        @JvmField val asked: Int,
        @JvmField val bars: List<QuoteBar>,
    )

    private val lock = Any()

    /** accessOrder = true：任何一次 [get] / [put] 命中都会把该条移到尾部（最近使用） */
    private val entries = LinkedHashMap<String, Entry>(maxEntries * 2, 0.75f, true)

    /** 当前缓存里所有条目加起来的根数，淘汰与统计都用它，避免每次现算 */
    private var totalBars = 0

    /**
     * 取缓存：TTL 内且够用就返回，否则 null（过期条目顺手删掉，别留着占预算）。
     *
     * [n] 是这次调用**实际要的**根数：缓存里有 2000 根时，「要 320 根」的取值项
     * 可以直接切片用，不必再打一次接口。
     */
    fun get(key: String, n: Int): List<QuoteBar>? = synchronized(lock) {
        val e = entries[key] ?: return null
        if (clock() - e.at > ttlMs) {
            entries.remove(key)
            totalBars -= e.bars.size
            return null
        }
        if (e.bars.size >= n || e.asked >= n) e.bars else null
    }

    /** 放缓存：覆盖旧值时先把旧条目占的根数扣回来，再按两条上限从最久没用的开始淘汰 */
    fun put(key: String, asked: Int, bars: List<QuoteBar>) {
        synchronized(lock) {
            entries.remove(key)?.let { totalBars -= it.bars.size }
            entries[key] = Entry(clock(), asked, bars)
            totalBars += bars.size
            if (entries.size <= maxEntries && totalBars <= maxBars) return
            val it = entries.entries.iterator()
            while ((entries.size > maxEntries || totalBars > maxBars) && it.hasNext()) {
                val victim = it.next()
                totalBars -= victim.value.bars.size
                it.remove()
            }
        }
    }

    /** 清空（热重载 / 手动刷新行情时用） */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            totalBars = 0
        }
    }

    /** 现在缓存了几只 —— 只给测试与日志用 */
    val size: Int get() = synchronized(lock) { entries.size }

    /** 现在一共缓存了多少根 —— 只给测试与日志用 */
    val barCount: Int get() = synchronized(lock) { totalBars }

    companion object {
        /** 与旧实现同口径：5 分钟内不重复打接口 */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L
        const val DEFAULT_MAX_ENTRIES = 16
        /**
         * 8000 根 ≈ 2 MB/通道（每根约 260 B）。典型一轮盯盘是 16 只×320 根 = 5120 根，
         * 正常情况不触发淘汰；只有「最近前高 / 前低」这类要 2000 根的取值项才会挤掉别的条目。
         */
        const val DEFAULT_MAX_BARS = 8_000
    }
}
