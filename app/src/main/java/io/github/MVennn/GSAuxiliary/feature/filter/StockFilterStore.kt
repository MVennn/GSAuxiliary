package io.github.MVennn.GSAuxiliary.feature.filter

import android.content.Context
import android.content.SharedPreferences
import io.github.MVennn.GSAuxiliary.core.GLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 筛选条件的**本地持久化** —— 策略详情页与行情页两个面板共用同一份实现。
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
 * ## 作用域（「按策略保存筛选条件」，见 `ConfigKeys.STG_FILTER_PER_STRATEGY`）
 *
 * [scope] 为空 = **全局那一份**：键名就是历史的 `filter_<行>`，一字不动 ——
 * 关着那个设置时行为与升级前完全一致，行情页那颗面板也永远走这条路。
 *
 * [scope] 非空（形如 `stg:<策略 id>` / `grp:<聚合组 id>`，见 [StockFilterScopes]）=
 * 该策略 / 该聚合组**自己的一份**：键名 `filter_<行>@<作用域>`。
 * 盘上没有这组键时按**出厂值**返回（= 不筛），**不回灌**全局那份 ——
 * 否则「某个策略本来没设过条件」会继承上一次在别的策略上设的条件，
 * 用户会觉得「刚点开就被筛掉了」。
 *
 * 缓存也按作用域分槽（[cached]），不同策略之间绝不串味。
 *
 * 读写都做了异常兜底：拿不到（极端情况下 Context 已死）就退回 [StockFilters.OFF]，绝不抛给宿主。
 */
object StockFilterStore {

    /** 目标 App 里的 prefs 文件名（沿用旧名，方向三项的历史取值不丢） */
    const val PREFS_NAME = "gsaux_stg_filter"

    /** 作用域键的分隔符：`filter_<行>@<作用域>` */
    internal const val SCOPE_MARK = "@"

    /** 按 Context 缓存（目标进程里 Activity 都是同一个 App Context，够用） */
    @Volatile private var prefs: SharedPreferences? = null

    /**
     * 进程级的取值缓存 —— **按作用域分槽**（槽位 `""` = 全局那一份）。
     *
     * 为什么要放这里而不是各功能自己缓存：策略详情页的面板与行情页的面板**写的是同一份状态**，
     * 各自缓存就会出现「在 A 面板改了条件，B 页面还在按旧条件筛」——
     * 谁改谁 [save]，[save] 顺手把缓存换掉，两边立刻看到同一个值。
     * （[invalidate] 只在「怀疑外部把 prefs 改了」时用，例如页面重新 resume、
     * 配置变化 —— 正常的面板改动静默走 [save]。）
     *
     * 用 [ConcurrentHashMap] 而不是 `HashMap` + 锁：写点只在面板点「确定 / 重置」时，
     * 读点在 getView 里（每行一次、主线程），并发读写同一槽的可能性真实存在
     * （宿主偶尔在自己的线程里刷适配器）。
     */
    private val cached = ConcurrentHashMap<String, StockFilters>()

    private fun prefs(ctx: Context): SharedPreferences? {
        prefs?.let { return it }
        return runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.onSuccess { prefs = it }
            .onFailure { GLog.w("筛选状态：打不开 prefs（$PREFS_NAME）", it) }
            .getOrNull()
    }

    /** 作用域 → 缓存槽位（`null` / 空白 = 全局那一份） */
    private fun slotOf(scope: String?): String = scope?.trim().orEmpty()

    /**
     * 面板里每一行的落盘键（`filter_<key>`，与 2.16.1 的三个键同名）。
     *
     * 抽成 `internal` 的顶层函数是为了能**单测** —— 键名错了的后果很隐蔽：
     * 策略页与行情页共用这一份状态（见上面的 `cached` 注释），
     * 键一旦对不上，两边就各读各的，表现为「在一个面板改了条件，另一个面板不跟着变」。
     */
    internal fun keyOf(row: FilterRow) = "filter_${row.key}"

    /**
     * 带作用域的落盘键：`filter_<key>@<作用域>`。
     *
     * [scope] 为 `null` / 空白时与 [keyOf] **完全一致** —— 认不出页面身份时宁可写全局那份，
     * 也不能写出一份谁也读不到的键（那等于「用户设了但没生效」，日志里还看不出问题）。
     */
    internal fun keyOf(row: FilterRow, scope: String?): String {
        val slot = slotOf(scope)
        return if (slot.isEmpty()) keyOf(row) else "filter_${row.key}$SCOPE_MARK$slot"
    }

    /** 出厂值：方向 false、类型 true */
    internal fun defaultOf(row: FilterRow) = row.isType

    /**
     * 读筛选状态。[scope] 见类注释：`null` = 全局那一份（历史行为）。
     *
     * 该作用域盘上还没有任何键时返回**出厂值**（= 不筛），不回落全局值。
     */
    fun load(ctx: Context, scope: String? = null): StockFilters {
        val slot = slotOf(scope)
        cached[slot]?.let { return it }
        val p = prefs(ctx) ?: return StockFilters.OFF
        return runCatching {
            var f = StockFilters.OFF
            for (row in FilterRow.entries) f = f.set(row, p.getBoolean(keyOf(row, slot), defaultOf(row)))
            f
        }.getOrDefault(StockFilters.OFF).also { cached[slot] = it }
    }

    /** 作废**全部作用域**的缓存，下一次 [load] 重新读盘 */
    fun invalidate() {
        cached.clear()
    }

    /**
     * 落盘。返回 true 表示写入成功。成功时顺带刷新该作用域的进程级缓存。
     *
     * [scope] 见类注释：`null` = 全局那一份（历史行为，行情页面板永远走这条）。
     */
    fun save(ctx: Context, f: StockFilters, scope: String? = null): Boolean {
        val slot = slotOf(scope)
        cached[slot] = f
        val p = prefs(ctx) ?: return false
        return runCatching {
            val e = p.edit()
            for (row in FilterRow.entries) e.putBoolean(keyOf(row, slot), f[row])
            e.commit()
        }.onFailure { GLog.w("筛选状态：写盘失败（${StockFilterScopes.describe(slot)}）", it) }
            .getOrDefault(false)
    }
}
