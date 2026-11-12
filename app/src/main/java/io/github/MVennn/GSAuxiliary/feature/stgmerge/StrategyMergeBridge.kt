package io.github.MVennn.GSAuxiliary.feature.stgmerge

import java.lang.ref.WeakReference
import java.util.IdentityHashMap

/**
 * 「聚合结果」从 [StrategyMergeFeature] 流向其它功能的只读通道。
 *
 * ## 为什么需要它
 *
 * 聚合把策略详情页的列表换成了「组内多个策略的并集」，但别的功能仍按
 * `adapter.data` / 宿主自己那份列表取值：
 *
 *  - 「直达标准行情 + 左右滑动」翻页列表来自适配器数据，聚合后应该能滑到并集里的每一只，
 *    而不是滑到某个单策略的全集；
 *  - 「多选全选」、「截图并添加」按下标回读同一份列表，聚合后下标口径也变了。
 *
 * 通道只放"结果"，不放行为：聚合侧发布，消费侧读取，两边互不依赖对方的实现
 * （照 `feature/stgfilter/StgFilterBridge.kt` 的形态）。
 *
 * ## 元素身份
 *
 * [Snapshot.merged] 里放的是**宿主自己的** `StockInfo` 实例（没有包装、没有复制成自建类），
 * 所以匹配一律用 `===` 而不是 `equals` —— 数据项是宿主 model，不保证 `equals` 的语义。
 *
 * ## 快照必须绑定到「算出它的那一页」
 *
 * 这个 object 是**进程级单例**：离开策略页后快照会一直留着。宿主会在多个策略页之间复用
 * 同一批 `StockInfo` 对象，只按元素身份匹配会把上一页的聚合结果套到这一页上。
 * 所以 [publish] 必须带上 owner（页面 Activity 与写回适配器的那个列表实例），
 * [mergedFor] / [keepOnly] 只认这一页，对不上就当作「没有聚合」。
 *
 * ## 一律弱引用
 *
 * 单例里强引用页面 Activity 和宿主的 `StockInfo`，等于「离开策略页后整棵 View 树还被吊着」——
 * 用户看到的就是切来切去内存只涨不降。所以 `owner` / `merged` / `source` 全存 [WeakReference]：
 * 页面还在、适配器 data 还握着并集时照常读得到；真被 GC 收走就退化成「本页没有聚合」，
 * 消费侧按单策略处理，不会拿错数据。
 */
object StrategyMergeBridge {

    /** 一次聚合的结果快照 */
    class Snapshot internal constructor(
        /** 聚合后的并集，保序；元素是宿主自己的 `StockInfo` 实例 */
        merged: List<Any>,
        /** 这份结果写回适配器用的**列表实例**（身份绑定，防止快照串页） */
        source: List<*>,
        /** 算出这份结果的页面（Activity），消费者做归属校验用 */
        owner: Any?,
        /** 页面原本那条策略的 stId（聚合是在它的详情页上做的） */
        val pageStId: String,
        /** 参与聚合的全部成员 stId，含 [pageStId]，顺序即组内顺序 */
        val memberStIds: List<String>,
        /** 有成员超时 / 取数失败时为 true（界面照常渲染，只代表并集不完整） */
        val degraded: Boolean,
    ) {
        private val mergedRef = WeakReference<List<Any>>(merged)
        private val sourceRef = WeakReference<List<*>>(source)
        private val ownerRef = owner?.let { WeakReference<Any>(it) }

        /** 并集；被 GC 收走返回 null（调用方按「没聚合」处理） */
        val merged: List<Any>? get() = mergedRef.get()

        /** 写回适配器的列表实例；被回收返回 null */
        val source: List<*>? get() = sourceRef.get()

        /** 算出这份结果的页面；页面销毁后被回收则返回 null */
        val owner: Any? get() = ownerRef?.get()

        /** 并集实例还在不在 */
        fun isAlive(): Boolean = mergedRef.get() != null
    }

    @Volatile
    private var current: Snapshot? = null

    /**
     * 聚合侧调用：渲染成功后传并集、写回适配器的列表实例与页面；关功能 / 回滚时传 null。
     *
     * [source] 不是可选装饰：缺了它就只能按元素身份匹配，跨页会套错数据。
     * 因此 merged 非空而 source 为空时按「没有聚合」处理（宁可退化成单策略，也不套错页面）。
     */
    fun publish(
        merged: List<Any>?,
        source: List<*>?,
        owner: Any?,
        pageStId: String,
        memberStIds: List<String>,
        degraded: Boolean = false,
    ) {
        current = if (merged.isNullOrEmpty() || source == null) {
            null
        } else {
            Snapshot(merged, source, owner, pageStId, memberStIds, degraded)
        }
    }

    /** 清空快照（关功能 / 回滚宿主 / 页面销毁时调用） */
    fun clear() {
        current = null
    }

    /** 当前是否有生效的聚合快照（并集实例已被回收也算没有） */
    fun hasSnapshot(): Boolean = current?.isAlive() == true

    /** 当前快照（调试通道 / 消费侧自查用） */
    fun snapshot(): Snapshot? = current

    /** 这份快照是不是 [owner]（页面 Activity）算出来的 */
    fun isFor(owner: Any?): Boolean = owner != null && current?.owner === owner

    /** 本页的聚合并集；没聚合 / 快照不属于本页返回 null */
    fun mergedFor(owner: Any?): List<Any>? {
        val snap = current ?: return null
        if (owner != null && snap.owner !== owner) return null
        return snap.merged
    }

    /**
     * 从宿主手里的列表里，按聚合结果挑出该留下的条目（保序）。
     *
     * 消费侧（直达行情的翻页列表）拿到的是宿主复制过的一份列表，所以按「同一个实例，
     * 或内容逐项 `===` 的副本」认；对不上就返回 null，调用方按「没聚合」处理。
     */
    fun keepOnly(source: List<*>?): List<Any>? {
        val snap = current ?: return null
        val merged = snap.merged ?: return null
        val published = snap.source ?: return null
        if (source.isNullOrEmpty()) return null
        if (!sameContents(published, source)) return null

        val ids = IdentityHashMap<Any, Boolean>(merged.size * 2)
        for (e in merged) ids[e] = true

        val out = ArrayList<Any>(merged.size)
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
