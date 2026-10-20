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
 */
object StgFilterBridge {

    /** 一次筛选的结果快照 */
    class Snapshot(
        /** 筛选后保留的条目，保序，与适配器里是同一批对象 */
        val kept: List<Any>,
        /** 保留项在宿主**全量列表**里的下标（与 [kept] 等长、同序） */
        val keptIndices: IntArray,
    )

    @Volatile private var current: Snapshot? = null

    /** 筛选侧调用：条件生效时传保留的条目与全量下标，不筛 / 功能关闭时传 null */
    fun publish(kept: List<Any>?, keptIndices: IntArray? = null) {
        current = if (kept.isNullOrEmpty() || keptIndices == null) {
            null
        } else {
            Snapshot(kept, keptIndices)
        }
    }

    /** 过滤代理的第 [proxyIndex] 行对应宿主全量列表的哪个下标；没筛 / 越界返回 null */
    fun fullIndexAt(proxyIndex: Int): Int? {
        val idx = current?.keptIndices ?: return null
        return if (proxyIndex in idx.indices) idx[proxyIndex] else null
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

        val ids = IdentityHashMap<Any, Boolean>(snap.kept.size * 2)
        for (e in snap.kept) ids[e] = true

        val out = ArrayList<Any>(snap.kept.size)
        for (e in source) if (e != null && ids.containsKey(e)) out.add(e)
        return out.ifEmpty { null }
    }
}
