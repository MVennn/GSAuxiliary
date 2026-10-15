package gsauxiliary.m.venn.feature.stgfilter

import android.content.Context
import android.content.SharedPreferences
import gsauxiliary.m.venn.core.GLog

/**
 * 三个筛选开关的取值。
 *
 * 语义（[matchesDirection] / 组合规则见 `StgStockFilterFeature`）：
 *  - [up]   = 只显示上涨的（涨跌幅 > 0）
 *  - [down] = 只显示下跌的（涨跌幅 < 0）
 *  - [zx]   = 只显示已在自选里的
 *
 * 组合规则：`涨幅` 与 `跌幅` 是**同一组方向条件**（取并集：两个都开 = 不过滤方向），
 * 再与 `自选` 取交集。
 */
data class StgFilters(
    val up: Boolean = false,
    val down: Boolean = false,
    val zx: Boolean = false,
) {
    /** 三个都关 = 不做任何过滤 */
    val active: Boolean get() = up || down || zx

    val directionOn: Boolean get() = up || down

    companion object {
        val OFF = StgFilters()
    }
}

/**
 * 筛选状态的**本地持久化**。
 *
 * ## 为什么存目标 App 自己的 SharedPreferences，而不是模块配置
 *
 * 筛选面板跑在**目标进程**里，用户点的开关必须由目标进程落盘。
 * 而 libxposed 的 remote preferences 在目标进程里是**只读**的
 * （见 `core/ConfigSource.kt` 的说明与 `docs/HOOK-NOTES.md` §7），
 * 目标进程根本写不进去。
 *
 * 所以筛选状态只能存在**目标 App 自己的数据目录**里：
 * `getSharedPreferences("gsaux_stg_filter")` → `/data/data/com.guosen.android/shared_prefs/`。
 * 这既满足「软件关闭重启后仍能保留」，也不需要任何额外权限。
 *
 * 副作用（已知并接受）：清除目标 App 数据 / 卸载重装会一并清掉筛选状态，
 * 回到「三个都关」。这与「重置模块」不清它的行为一致 —— 它属于目标 App 侧的状态，
 * 不属于模块配置，所以**不进** `ConfigKeys.ALL_KEYS / DEFAULTS`。
 *
 * 读写都做了异常兜底：拿不到（极端情况下 Context 已死）就退回全关，绝不抛给宿主。
 */
object StgFilterStore {

    /** 目标 App 里的 prefs 文件名 */
    const val PREFS_NAME = "gsaux_stg_filter"

    private const val KEY_UP = "filter_up"
    private const val KEY_DOWN = "filter_down"
    private const val KEY_ZX = "filter_zx"

    /** 按 Context 缓存（弱化：只缓存第一个，够用 —— 目标进程里 Activity 都是同一个 App Context） */
    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences? {
        prefs?.let { return it }
        return runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }.onSuccess { prefs = it }
            .onFailure { GLog.w("筛选状态：打不开 prefs（$PREFS_NAME）", it) }
            .getOrNull()
    }

    fun load(ctx: Context): StgFilters {
        val p = prefs(ctx) ?: return StgFilters.OFF
        return runCatching {
            StgFilters(
                up = p.getBoolean(KEY_UP, false),
                down = p.getBoolean(KEY_DOWN, false),
                zx = p.getBoolean(KEY_ZX, false),
            )
        }.getOrDefault(StgFilters.OFF)
    }

    /** 落盘。返回 true 表示写入成功。 */
    fun save(ctx: Context, f: StgFilters): Boolean {
        val p = prefs(ctx) ?: return false
        return runCatching {
            p.edit().putBoolean(KEY_UP, f.up)
                .putBoolean(KEY_DOWN, f.down)
                .putBoolean(KEY_ZX, f.zx)
                .commit()
        }.onFailure { GLog.w("筛选状态：写盘失败", it) }.getOrDefault(false)
    }
}
