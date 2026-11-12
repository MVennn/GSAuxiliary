package io.github.MVennn.GSAuxiliary.feature.filter

import android.app.Activity
import io.github.MVennn.GSAuxiliary.feature.stgmerge.StrategyMergeTargets
import java.util.WeakHashMap

/**
 * 「按策略保存筛选条件」的**作用域解析**：这一页该读写哪一份筛选状态。
 *
 * ## 两种作用域
 *
 * - 聚合页 → `grp:<聚合组 id>`（**组优先**）
 * - 普通策略页 → `stg:<m_stg.id>`
 * - 都认不出来（不在策略详情页 / 模型字段还没就绪）→ `null`，即全局那一份
 *
 * 落盘键与读写见 [StockFilterStore]。
 *
 * ## 为什么聚合组要由 [io.github.MVennn.GSAuxiliary.feature.stgmerge.StrategyMergeFeature] 登记
 *
 * 聚合期间宿主那边**根本看不出这是哪一组**：
 * `StrategyMergeFeature` 会把 `m_stg.id` 临时换成成员策略的 stId（并在结束时还原成
 * 本页原来的 stId）、把 `m_stg.name` 改写成「聚合 · 组名（N 条）」。
 * 所以「聚合页的稳定身份」只有组 id 一个来源 —— 由聚合侧在开跑前显式登记（[rememberMergePage]），
 * 筛选项这边只读登记表，绝不自己猜。
 *
 * 一旦本页登记成某个组，之前按策略解出来的 id 立刻作废（组身份接管本页）。
 *
 * ## 为什么要缓存按 Activity 解析出来的策略 id
 *
 * [StrategyMergeTargets.field] → `Reflect.readField` → `Reflect.findField` **每次都重新
 * `getDeclaredField` + `setAccessible`**（那里没有 Field 缓存）。而这个解析在
 * `onGetView` 的每行路径上（`StgStockFilterFeature.isFilterActive`）会被调一次 ——
 * 不缓存就是「每行三次反射」。用弱键表缓存：Activity 销毁即自动失效，不会漏内存。
 *
 * 拿不到 id 时**不缓存**（宁可多试几次），因为很可能只是页面模型还没就绪；
 * 缓存了空值会把「这一页以后也永远读全局」钉死。
 */
object StockFilterScopes {

    /** 策略页作用域前缀（后面接 `m_stg.id`） */
    const val PREFIX_STRATEGY = "stg:"

    /** 聚合页作用域前缀（后面接聚合组 id） */
    const val PREFIX_GROUP = "grp:"

    /** 聚合页登记项：组身份 + 组名（组名只给日志用） */
    data class MergePage(val groupId: String, val groupName: String)

    /** Activity → 它当时聚合的是哪一组（弱键，页面销毁自动失效） */
    private val mergePages = WeakHashMap<Activity, MergePage>()

    /** Activity → 解析出来的 `m_stg.id`（弱键，理由见类注释） */
    private val strategyIds = WeakHashMap<Activity, String>()

    /**
     * 登记「本页正在聚合 [groupId] 这一组」。
     *
     * 返回 true 表示登记内容**变了**（用于只打一条日志）。组 id 为空时不做任何事并返回 false
     * —— 认不出组就退回策略作用域，总比把一堆页面的筛选状态混进同一个空 id 槽强。
     */
    fun rememberMergePage(activity: Activity, groupId: String, groupName: String): Boolean {
        val id = groupId.trim()
        if (id.isEmpty()) return false
        val page = MergePage(id, groupName)
        synchronized(mergePages) {
            if (mergePages[activity] == page) return false
            mergePages[activity] = page
            // 组身份接管本页：之前按策略解出来的 id 一律作废
            synchronized(strategyIds) { strategyIds.remove(activity) }
            return true
        }
    }

    /** 撤销本页的聚合登记（聚合被还原 / 页面销毁时调） */
    fun forgetMergePage(activity: Activity) {
        synchronized(mergePages) { mergePages.remove(activity) }
    }

    /**
     * 丢掉本页缓存的策略 id（页面 resume 时调一次）。
     *
     * 宿主有可能把**同一个 Activity** 拿去做另一个策略的详情页（列表里连着点进去），
     * 那时缓存里的旧 id 会让两个策略共用同一份筛选状态。解析只有三次反射、一次 resume 一次，
     * 不心疼；但缓存绝不能留着跨策略复用。
     */
    fun forgetStrategyId(activity: Activity) {
        synchronized(strategyIds) { strategyIds.remove(activity) }
    }

    /** 本页登记的聚合组（没有则 null） */
    fun mergePageOf(activity: Activity?): MergePage? {
        val act = activity ?: return null
        return synchronized(mergePages) { mergePages[act] }
    }

    /**
     * 这一页的筛选作用域：`grp:<组 id>` / `stg:<策略 id>` / `null`（全局）。
     *
     * 判定顺序是**组优先**：聚合页上 `m_stg.id` 正被宿主改来改去，只有登记表可靠。
     */
    fun scopeOf(activity: Activity?): String? {
        val act = activity ?: return null
        mergePageOf(act)?.let { return PREFIX_GROUP + it.groupId }
        val stId = strategyIdOf(act) ?: return null
        return PREFIX_STRATEGY + stId
    }

    /** 本页策略的 `m_stg.id`（带缓存；拿不到返回 null） */
    fun strategyIdOf(activity: Activity?): String? {
        val act = activity ?: return null
        synchronized(strategyIds) { strategyIds[act]?.let { return it } }
        val id = readStrategyId(act) ?: return null
        synchronized(strategyIds) { strategyIds[act] = id }
        return id
    }

    /** Activity → `m_stkModel.m_stg.id`（宿主字段名见 [StrategyMergeTargets] 的候选数组） */
    private fun readStrategyId(activity: Activity): String? {
        val model = StrategyMergeTargets.field(activity, *StrategyMergeTargets.ACT_FIELD_MODEL) ?: return null
        val stg = StrategyMergeTargets.field(model, StrategyMergeTargets.MODEL_FIELD_STG) ?: return null
        val id = StrategyMergeTargets.field(stg, StrategyMergeTargets.STG_FIELD_ID) as? String ?: return null
        return id.trim().ifEmpty { null }
    }

    /** 日志用：把作用域 id 译成人话 */
    fun describe(scope: String?): String {
        val s = scope?.trim().orEmpty()
        return when {
            s.isEmpty() -> "全局（共用一份）"
            s.startsWith(PREFIX_GROUP) -> "聚合组 " + s.removePrefix(PREFIX_GROUP)
            s.startsWith(PREFIX_STRATEGY) -> "策略 " + s.removePrefix(PREFIX_STRATEGY)
            else -> s
        }
    }
}
