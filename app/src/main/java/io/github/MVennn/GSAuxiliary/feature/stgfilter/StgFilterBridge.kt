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
    )

    @Volatile private var current: Snapshot? = null

    /** 筛选侧调用：条件生效时传保留的条目，不筛 / 功能关闭时传 null */
    fun publish(kept: List<Any>?) {
        current = if (kept.isNullOrEmpty()) null else Snapshot(kept)
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
