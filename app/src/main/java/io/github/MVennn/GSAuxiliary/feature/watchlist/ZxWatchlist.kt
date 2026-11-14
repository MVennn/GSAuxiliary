package io.github.MVennn.GSAuxiliary.feature.watchlist

import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 自选股判定。
 *
 * 逆向结论（真机 20/20 交叉验证）：
 *   `ZXWorkflow.isBelongByStock(code, market)` —— 国信自身的「是否在自选」门面方法
 *     内部 → `ZXManager.isBelongToAnyGroupByStock(code, market)`
 *     等价于 `ZXManager.m_stkGrpMap.containsKey("code.market")`
 *
 * 行情页的 `StockInfo.isZx` 就是靠它算出来的，所以这里直接复用，
 * 保证策略详情页的判定与行情页 100% 一致。
 *
 * 状态是进程级的（每个目标进程一份），[init] 会重置缓存，
 * 以便热重载后重新解析。
 */
object ZxWatchlist {

    private const val CLS_WORKFLOW = "com.guosen.android.zxstock.ZXWorkflow"
    private const val CLS_MANAGER = "com.guosen.android.zxstock.ZXManager"

    @Volatile private var classLoader: ClassLoader? = null

    @Volatile private var isBelong: Method? = null
    @Volatile private var isBelongFailed = false

    @Volatile private var manager: Any? = null
    @Volatile private var stkGrpMap: Field? = null
    @Volatile private var mapFailed = false

    /**
     * 绑定宿主的 `ClassLoader`。
     *
     * **幂等**（2026-10-19 起）：同一个 `ClassLoader` 上重复调用直接返回 ——
     * 本类被两个功能各自 `init` 一次（自选高亮 / 筛选股票），而重置会**击穿共享的
     * 反射缓存**：运行中打开另一个开关触发补装时，已解析好的 `Method` / `Field`
     * 会被清掉，下一次判定要重跑一次 `Class.forName` + 继承链搜索。
     * 换了 `ClassLoader`（热重载）才需要真的重解析，那时仍然照常清空。
     */
    fun init(cl: ClassLoader) {
        if (cl === classLoader) return
        classLoader = cl
        isBelong = null
        isBelongFailed = false
        manager = null
        stkGrpMap = null
        mapFailed = false
    }

    /**
     * @param code   6 位股票代码，如 `300006`
     * @param market 市场号，如 `1`
     */
    fun isInWatchlist(code: String?, market: String?): Boolean {
        if (code.isNullOrEmpty() || market.isNullOrEmpty()) return false
        byWorkflow(code, market)?.let { return it }
        return byStkGrpMap("$code.$market")
    }

    /**
     * 宿主的合并形状（`300006.1`）直接判定 —— 列表行里最常用的入口。
     *
     * 为什么不放在调用方做 `substring`：列表每行都要判一次，调用方拆、这里再拼
     * `"$code.$market"` 会把同一行拆了又拼，白造 3 个 String。这里拆一次、
     * 直接走 [byStkGrpMap] 的同一个 key 形状，只有 `byWorkflow` 可用时才付出拼接。
     */
    fun isInWatchlist(codeAndMarket: String?): Boolean {
        val parts = splitCodeAndMarket(codeAndMarket) ?: return false
        byWorkflow(parts.first, parts.second)?.let { return it }
        // 走到这里说明 byWorkflow 用不了，直接拿原串查表（不再拼 code.market）
        return byStkGrpMap(codeAndMarket!!)
    }

    /**
     * 把宿主的 `code.market` 拆成两段；拆不出（空、无点、点在最前/最后）返回 null。
     *
     * 抽成独立函数是为了能用单测钉住口径 —— 这处判错的后果是「该染的行不染」，
     * 界面上与「真的不在自选」一模一样，看不出来（`ZxWatchlistCamTest` 守着）。
     */
    internal fun splitCodeAndMarket(cam: String?): Pair<String, String>? {
        if (cam.isNullOrEmpty()) return null
        val dot = cam.indexOf('.')
        if (dot <= 0 || dot >= cam.length - 1) return null
        return cam.substring(0, dot) to cam.substring(dot + 1)
    }

    /**
     * 自选代码集合（**忽略市场号**）；`null` = 这一刻读不出来。
     *
     * ## 为什么需要它
     *
     * [isInWatchlist] 要求 `market` 非空 —— 而宿主的 `StockInfo.codeAndMarket` 是
     * **构造后填充**的，存在「只有代码、没有市场号」的中间态（见
     * `feature/filter/CodeMarketParts.kt` 的类注释）。那一刻自选票会被判成「非自选」，
     * 勾「涨家」不勾「自选」时它按普通涨票留在列表里（2026-11-13 真机：754 只的
     * 聚合组里漏出 2 只自选，用户手动再筛一次才恢复正常）。
     *
     * 这里直接按 `m_stkGrpMap` 的键取点前的代码部分，**一整趟只读一次**：
     * 既躲开「缺市场号」这种中间态，也把每行一次跨类反射调用降为整屏一次。
     *
     * ## 为什么空表返回 null
     *
     * 空表可能是「真的没有自选」，也可能是「宿主还没把自选加载进来」。
     * 后者若当成「没有自选」，整页都会被判成非自选 —— 那个错误比「慢一点」严重得多。
     * 所以空表一律返回 `null`，让调用方回落逐只判定（与引入本方法前的行为一致）。
     *
     * @return 自选代码集合；`null` = 没绑定 / 反射失败 / 表为空 / 键里一个代码都没解析出来
     */
    fun snapshotCodes(): Set<String>? {
        val map = stkGrpMapOrNull() ?: return null
        if (map.isEmpty()) return null
        val out = HashSet<String>(map.size * 2)
        for (k in map.keys) {
            codeOfWatchlistKey(k as? String)?.let { out.add(it) }
        }
        return if (out.isEmpty()) null else out
    }

    /**
     * 自选表的键（形如 `300006.1`，也见过只填一半的 `300006`）→ 6 位代码。
     *
     * 与筛选侧同一个切分口径（`CodeMarketParts`：`dot > 0` 才切，否则整串当代码），
     * 抽出来是为了能被纯 JVM 单测钉死 —— [snapshotCodes] 本身要碰宿主类，测不了。
     */
    internal fun codeOfWatchlistKey(key: String?): String? {
        val s = key?.trim().orEmpty()
        if (s.isEmpty()) return null
        val dot = s.indexOf('.')
        return if (dot > 0) s.substring(0, dot) else s
    }

    /** 首选：调用国信自己的门面方法 */
    private fun byWorkflow(code: String, market: String): Boolean? {
        if (isBelongFailed) return null
        val cl = classLoader ?: return null
        try {
            var m = isBelong
            if (m == null) {
                val c = Reflect.loadClass(CLS_WORKFLOW, cl) ?: run {
                    isBelongFailed = true
                    return null
                }
                m = Reflect.findMethod(c, "isBelongByStock", String::class.java, String::class.java)
                    ?: run {
                        isBelongFailed = true
                        return null
                    }
                isBelong = m
            }
            val r = m.invoke(null, code, market)
            return r as? Boolean
        } catch (t: Throwable) {
            isBelongFailed = true
            GLog.w("ZXWorkflow.isBelongByStock 不可用，改用 m_stkGrpMap 兜底", t)
            return null
        }
    }

    /** 兜底：直接查 `ZXManager.m_stkGrpMap`（key 形如 `"300006.1"`） */
    private fun byStkGrpMap(codeAndMarket: String): Boolean {
        val map = stkGrpMapOrNull() ?: return false
        return map.containsKey(codeAndMarket)
    }

    /**
     * 解析 `ZXManager` 实例与它的 `m_stkGrpMap` 字段，返回那张表（`code.market` → 分组）。
     *
     * 解析结果与失败标记都留在本对象上：一趟筛选会问它几百次，不能每次都走
     * `Class.forName` + 继承链搜索；真正的失败（宿主改了类名/字段名）也只该记一次。
     */
    private fun stkGrpMapOrNull(): Map<*, *>? {
        if (mapFailed) return null
        val cl = classLoader ?: return null
        try {
            var mgr = manager
            if (mgr == null) {
                val c = Reflect.loadClass(CLS_MANAGER, cl) ?: run {
                    mapFailed = true
                    return null
                }
                val inst = Reflect.findMethod(c, "instance") ?: run {
                    mapFailed = true
                    return null
                }
                mgr = inst.invoke(null) ?: run {
                    mapFailed = true
                    return null
                }
                manager = mgr
                stkGrpMap = Reflect.findField(mgr.javaClass, "m_stkGrpMap") ?: run {
                    mapFailed = true
                    return null
                }
            }
            val f = stkGrpMap ?: return null
            return f.get(mgr) as? Map<*, *>
        } catch (t: Throwable) {
            mapFailed = true
            GLog.w("m_stkGrpMap 读取失败，自选判定退化为逐只询问", t)
            return null
        }
    }
}
