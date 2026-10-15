package gsauxiliary.m.venn.feature.watchlist

import gsauxiliary.m.venn.core.GLog
import gsauxiliary.m.venn.util.Reflect
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

    fun init(cl: ClassLoader) {
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
        if (mapFailed) return false
        val cl = classLoader ?: return false
        try {
            var mgr = manager
            if (mgr == null) {
                val c = Reflect.loadClass(CLS_MANAGER, cl) ?: run {
                    mapFailed = true
                    return false
                }
                val inst = Reflect.findMethod(c, "instance") ?: run {
                    mapFailed = true
                    return false
                }
                mgr = inst.invoke(null) ?: run {
                    mapFailed = true
                    return false
                }
                manager = mgr
                stkGrpMap = Reflect.findField(mgr.javaClass, "m_stkGrpMap") ?: run {
                    mapFailed = true
                    return false
                }
            }
            val map = stkGrpMap?.get(mgr)
            return map is Map<*, *> && map.containsKey(codeAndMarket)
        } catch (t: Throwable) {
            mapFailed = true
            GLog.w("m_stkGrpMap 兜底也不可用", t)
            return false
        }
    }
}
