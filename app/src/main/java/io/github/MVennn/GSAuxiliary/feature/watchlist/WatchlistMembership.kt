package io.github.MVennn.GSAuxiliary.feature.watchlist

import io.github.MVennn.GSAuxiliary.core.BoundedTtlCache

/**
 * 「这只股票在不在自选」的短时缓存（2026-11-14 加）。
 *
 * ## 为什么要有它
 *
 * 自选判定要过反射：`ZXWorkflow.isBelongByStock`（静态方法 invoke）或
 * `m_stkGrpMap.containsKey`。列表每次滑动、每次筛选、每行都要问一次 ——
 * [io.github.MVennn.GSAuxiliary.feature.stgfilter.StgStockFilterFeature] 一轮筛选就是
 * 几百上千次，`MarketFilterFeature` 同样。用户在同一个界面上看到的结果
 * 也不会在一秒内变来变去，所以按 `代码.市场` 做**有界短时缓存**：
 *
 *  - 命中即返回，反射调用从「每行一次」降到「每次自选变化一次」；
 *  - 最多 [MAX_ENTRIES] 条（LRU），随手点开多少只股票都不会涨；
 *  - 有效期 [TTL_MS]，自选变化最多晚这么久才反映到配色上 —— 不会永久错；
 *  - 列表回到前台时 [invalidate] 立即清空，正常路径看不出延迟。
 *
 * **不做跨进程或落盘**：宿主自选一变，本进程也不知道，只能靠时间与回调。
 * 缓存本体是纯逻辑的 [BoundedTtlCache]，有独立单测。
 */
internal object WatchlistMembership {

    /** 缓存有效期：自选变化最多晚这么久才反映到列表配色上 */
    private const val TTL_MS = 2_000L

    /** 上限：一只股票一条，512 条已覆盖正常自选池 */
    private const val MAX_ENTRIES = 512

    private val cache = BoundedTtlCache<String, Boolean>(
        maxEntries = MAX_ENTRIES,
        ttlMs = TTL_MS,
        // nanoTime 换毫秒：不依赖 android.os.SystemClock，纯逻辑可单测
        clockMs = { System.nanoTime() / 1_000_000L },
    )

    /**
     * `codeAndMarket`（`300006.1`）形状的判定，带缓存。
     *
     * 空值 / 拆不出市场的一律 `false`（与 [ZxWatchlist] 的口径一致）。
     */
    fun isInWatchlist(codeAndMarket: String?): Boolean {
        if (codeAndMarket.isNullOrEmpty()) return false
        cache.get(codeAndMarket)?.let { return it }
        val value = ZxWatchlist.isInWatchlist(codeAndMarket)
        cache.put(codeAndMarket, value)
        return value
    }

    /** 进入页面 / 配置变化时调用：让下一次判定重新问宿主 */
    fun invalidate() {
        cache.clear()
    }
}