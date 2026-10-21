package io.github.MVennn.GSAuxiliary.feature.stgfilter

import java.util.IdentityHashMap

/**
 * 「筛选结果」从 [StgStockFilterFeature] 流向「直达标准行情」的只读通道。
 *
 * ## 为什么需要它
 *
 * 「策略详情页筛选股票」与「策略详情直达标准行情 + 左右滑动」是两个独立功能：
 * 前者换掉列表适配器的数据，后者改写跳转 Intent 的 extras。
 * 但后者构造翻页列表时，读的是宿主塞进 `adapter_key.stockList` 的**全量** ——
 * 它完全不知道筛选的存在。于是筛完再点进个股详情，左右滑动会滑到
 * 本该被筛掉的股票（真机实测：筛「自选」后能一路滑到「华北制药」，一只跌 1.67%、
 * 不在自选里的股票）。
 *
 * 通道只放"结果"，不放行为：筛选侧发布，直达侧读取，两个功能互不依赖对方的实现。
 *
 * ## 元素身份
 *
 * [Snapshot.kept] 里是与适配器数据**同一批对象**（没有复制），所以匹配用 `===`
 * 而不是 `equals` —— 数据项是宿主自己的 model，不保证 `equals` 的语义。
 *
 * ## 快照必须绑定到「算出它的那个列表实例」（2026-10-21）
 *
 * 这个 object 是**进程级单例**：离开筛选页后快照会一直留着。只按元素身份匹配不够 ——
 * 宿主可能在多个策略页之间复用同一批 `StockInfo` 对象，于是上一页的筛选结果会被
 * 套到这一页上（直达行情的翻页列表被无故裁掉几只）。所以 [publish] 必须带上
 * 全量列表实例，[keepOnly] 只认「同一个实例或内容逐项 `===` 的副本」；对不上就当作「没筛」。
 * 同时记下**列表上的过滤代理**（owner），供 [fullIndexAt] / [fullIndices] 做归属校验。
 */
object StgFilterBridge {

    /** 一次筛选的结果快照 */
    class Snapshot(
        /** 筛选后保留的条目，保序，与适配器里是同一批对象 */
        val kept: List<Any>,
        /** 保留项在宿主**全量列表**里的下标（与 [kept] 等长、同序） */
        val keptIndices: IntArray,
        /** 这份结果是从哪个**全量列表实例**算出来的（身份绑定，防止快照串页） */
        val source: List<*>,
        /**
         * 装在这张列表上的过滤代理实例。
         *
         * 消费者（全选 / 自选高亮）手里只有代理，没有全量列表；用它做归属校验，
         * 比类名判据可靠（R8 打不穿、也不怕宿主有同名类）。
         */
        val owner: Any?,
    )

    @Volatile private var current: Snapshot? = null

    /**
     * 筛选侧调用：条件生效时传保留的条目、全量下标、**算这份结果用的全量列表**
     * 与**列表上那个过滤代理**；不筛 / 功能关闭时传 null。
     *
     * [source] 不是可选装饰：缺了它就只能按元素身份匹配，跨页会误筛。
     * 因此 kept 非空而 source 为空时按「没筛」处理（宁可退化成全量，也不套错页面）。
     */
    fun publish(
        kept: List<Any>?,
        keptIndices: IntArray? = null,
        source: List<*>? = null,
        owner: Any? = null,
    ) {
        current = if (kept.isNullOrEmpty() || keptIndices == null || source == null) {
            null
        } else {
            Snapshot(kept, keptIndices, source, owner)
        }
    }

    /** 当前是否有生效的筛选快照（用于调用方区分「没筛」与「快照不属于本页」） */
    fun hasSnapshot(): Boolean = current != null

    /** 这份快照是不是 [owner]（列表上那个过滤代理）算出来的 */
    fun isFor(owner: Any?): Boolean = owner != null && current?.owner === owner

    /**
     * 过滤代理的第 [proxyIndex] 行对应宿主全量列表的哪个下标；没筛 / 越界返回 null。
     *
     * 传了 [owner] 就要求快照确实由这个代理发布（消费者手里通常只有代理）——
     * 别的页面的快照一律不认，免得按别页的下标点行/染色。
     */
    fun fullIndexAt(proxyIndex: Int, owner: Any? = null): Int? {
        val snap = current ?: return null
        if (owner != null && snap.owner !== owner) return null
        val idx = snap.keptIndices
        return if (proxyIndex in idx.indices) idx[proxyIndex] else null
    }

    /**
     * 子集行数 → 全量下标的完整映射（[count] 行）。
     *
     * 供「全选」一次性快照用：**长度必须与列表上的行数一致**，任何一项缺失都返回 null。
     * 不能用 `?: it` 逐项兜底 —— 那会让同一轮点击里混用两套坐标（点到别的股票）。
     */
    fun fullIndices(count: Int, owner: Any? = null): IntArray? {
        val snap = current ?: return null
        if (owner != null && snap.owner !== owner) return null
        if (count <= 0 || snap.keptIndices.size != count) return null
        return snap.keptIndices.copyOf()
    }

    /**
     * 「合成行点击」放行标记。
     *
     * 筛选的回映射 wrapper 会在行点击时把「子集下标」换成「全量下标」再转发；
     * 但 `AbsListView.performItemClick` 在调到 wrapper 之前，已经用**原始位置**
     * 写过一次自己的 checked states（宿主的多选模型读的就是它）。
     * 所以「全选」这类**合成点击**必须直接把全量下标交给 `performItemClick`，
     * 并让 wrapper 原样放行 —— 否则框架记一份子集下标、宿主收到另一份全量下标，
     * 两边打架（真机表现：全选后界面勾着 A/B/C，确认添加的却是全量前 N 只）。
     */
    private val bypassRemap = ThreadLocal<Boolean>()

    fun isRemapBypassed(): Boolean = bypassRemap.get() == true

    fun runWithRemapBypassed(block: () -> Unit) {
        bypassRemap.set(true)
        try {
            block()
        } finally {
            bypassRemap.remove()
        }
    }

    /** 从宿主的全量列表里，按筛选结果挑出该留下的条目（保序） */
    fun keepOnly(source: List<*>?): List<Any>? {
        val snap = current ?: return null
        if (source.isNullOrEmpty()) return null
        // 只认「算出快照的那份列表」：同一个实例，或**内容逐项 `===`** 的副本。
        // 宿主构造 EngineIntent 时可能把列表复制一份（真机 2026-10-21：直接比实例会
        // 把正常路径也拒掉，翻页列表退回全量）；但换页后元素/顺序不同，仍然会被挡下。
        if (!sameContents(snap.source, source)) return null

        val ids = IdentityHashMap<Any, Boolean>(snap.kept.size * 2)
        for (e in snap.kept) ids[e] = true

        val out = ArrayList<Any>(snap.kept.size)
        for (e in source) if (e != null && ids.containsKey(e)) out.add(e)
        return out.ifEmpty { null }
    }

    /** 两个列表是不是「同一份内容」：长度相同且逐项 `===`（允许宿主复制列表实例） */
    private fun sameContents(a: List<*>, b: List<*>): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }
}
