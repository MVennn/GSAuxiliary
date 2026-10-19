package io.github.MVennn.GSAuxiliary.feature.filter

import android.content.Context
import android.content.SharedPreferences
import io.github.MVennn.GSAuxiliary.core.GLog

/**
 * 筛选条件的**本地持久化** —— 策略详情页与行情页两个面板共用这一份。
 *
 * ## 为什么存目标 App 自己的 SharedPreferences，而不是模块配置
 *
 * 面板跑在**目标进程**里，用户点的开关必须由目标进程落盘。
 * 而 libxposed 的 remote preferences 在目标进程里是**只读**的
 * （见 `core/ConfigSource.kt` 的说明），目标进程根本写不进去。
 *
 * 所以只能存在**目标 App 自己的数据目录**里：
 * `getSharedPreferences("gsaux_stg_filter")` → `/data/data/com.guosen.android/shared_prefs/`。
 * 这既满足「软件关闭重启后仍能保留」，也不需要任何额外权限。
 *
 * 副作用（已知并接受）：清除目标 App 数据 / 卸载重装会一并清掉筛选状态，
 * 回到出厂（方向全不勾 + 类型全勾）。它与「重置模块」无关 —— 它属于目标 App 侧的状态，
 * 所以**不进** `ConfigKeys.ALL_KEYS / DEFAULTS`。
 *
 * ## 键与默认值
 *
 * 文件名沿用 2.16.1 起的 `gsaux_stg_filter`：老用户的方向三项原样读得回来；
 * 2.18.0 新增的五个类型键**缺省即 true**（= 不筛类型），老用户升级后行为不变。
 *
 * 读写都做了异常兜底：拿不到（极端情况下 Context 已死）就退回 [StockFilters.OFF]，绝不抛给宿主。
 */
object StockFilterStore {

    /** 目标 App 里的 prefs 文件名（沿用旧名，方向三项的历史取值不丢） */
    const val PREFS_NAME = "gsaux_stg_filter"

    /** 按 Context 缓存（目标进程里 Activity 都是同一个 App Context，够用） */
    @Volatile private var prefs: SharedPreferences? = null

    /**
     * 进程级的取值缓存 —— **两块面板共用一份**。
     *
     * 为什么要放这里而不是各功能自己缓存：策略详情页的面板与行情页的面板**写的是同一份状态**，
     * 各自缓存就会出现「在 A 面板改了条件，B 页面还在按旧条件筛」——
     * 谁改谁 [save]，[save] 顺手把缓存换掉，两边立刻看到同一个值。
     * （[invalidate] 只在「怀疑外部把 prefs 改了」时用，例如页面重新 resume、
     * 配置变化 —— 正常的面板改动静默走 [save]。）
     */
    @Volatile private var cached: StockFilters? = null

    private fun prefs(ctx: Context): SharedPreferences? {
        prefs?.let { return it }
        return runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.onSuccess { prefs = it }
            .onFailure { GLog.w("筛选状态：打不开 prefs（$PREFS_NAME）", it) }
            .getOrNull()
    }

    /** 面板里每一行的落盘键（`filter_<key>`，与 2.16.1 的三个键同名） */
    private fun keyOf(row: FilterRow) = "filter_${row.key}"

    /** 出厂值：方向 false、类型 true */
    private fun defaultOf(row: FilterRow) = row.isType

    fun load(ctx: Context): StockFilters {
        cached?.let { return it }
        val p = prefs(ctx) ?: return StockFilters.OFF
        return runCatching {
            var f = StockFilters.OFF
            for (row in FilterRow.entries) f = f.set(row, p.getBoolean(keyOf(row), defaultOf(row)))
            f
        }.getOrDefault(StockFilters.OFF).also { cached = it }
    }

    /** 作废缓存，下一次 [load] 重新读盘 */
    fun invalidate() {
        cached = null
    }

    /** 落盘。返回 true 表示写入成功。成功时顺带刷新进程级缓存。 */
    fun save(ctx: Context, f: StockFilters): Boolean {
        cached = f
        val p = prefs(ctx) ?: return false
        return runCatching {
            val e = p.edit()
            for (row in FilterRow.entries) e.putBoolean(keyOf(row), f[row])
            e.commit()
        }.onFailure { GLog.w("筛选状态：写盘失败", it) }.getOrDefault(false)
    }
}
