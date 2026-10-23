package io.github.MVennn.GSAuxiliary.feature.stgsnap

import android.app.Activity
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.util.Reflect

/**
 * 「这一页的数据真的拉出来了没」。
 *
 * 判据（全部满足才允许截图）：
 *  1. 当前页可见标题 `#tv_top_stk_name_and_code` 里含目标 6 位代码；
 *  2. 可见的 `KXChartGroupView` 已有 K 线数据（`m_kxChartInfo.m_kxChartItems` 非空）。
 *
 * 第 2 条只在「日 K 页签」成立。进入任务时会先尝试点「日K」；如果点不到
 * （宿主换了控件），退回「标题命中 + 固定静置」，并记一条日志。
 *
 * 所有反射结果按类缓存（规则 2.1）；只在主线程调用。
 */
internal object SnapReadiness {

    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var kxClass: Class<*>? = null
    @Volatile private var kxInfoField: java.lang.reflect.Field? = null
    @Volatile private var kxItemsField: java.lang.reflect.Field? = null

    /** 上次反射解析失败的时刻（`elapsedRealtime`，0 = 没失败过）。 */
    @Volatile private var kxFailedAt = 0L

    /** 反射解析失败后的重试间隔。 */
    private const val KX_RETRY_MS = 30_000L

    @Volatile private var titleIdCache = 0
    @Volatile private var klineTabTextCache: String = StgSnapAddTargets.KLINE_TAB_TEXT

    fun reset(cl: ClassLoader) {
        if (cl === classLoader) return
        classLoader = cl
        kxClass = null
        kxInfoField = null
        kxItemsField = null
        kxFailedAt = 0L
        titleIdCache = 0
    }

    /** 可见标题是否就是目标股票 */
    fun titleMatches(activity: Activity, code: String): Boolean {
        val id = titleId(activity)
        if (id == 0) return false
        val root = activity.window?.decorView ?: return false
        return walk(root, 0) { v ->
            if (v.id == id && v is TextView && isVisible(v)) {
                StgSnapPlan.titleMatches(v.text, code)
            } else {
                false
            }
        }
    }

    /** 可见的日 K 图是否已经拿到数据 */
    fun klineReady(activity: Activity, env: FeatureEnv): Boolean {
        val cls = resolveKx(env) ?: return false
        val infoField = kxInfoField ?: return false
        val itemsField = kxItemsField ?: return false
        val root = activity.window?.decorView ?: return false
        return walk(root, 0) { v ->
            if (cls.isInstance(v) && isVisible(v)) {
                val info = runCatching { infoField.get(v) }.getOrNull()
                if (info != null) {
                    val items = runCatching { itemsField.get(info) }.getOrNull()
                    nonEmpty(items)
                } else {
                    false
                }
            } else {
                false
            }
        }
    }

    /**
     * 尝试把当前页切到「日K」。
     *
     * 找不到/点不动返回 false，调用方改用「标题命中 + 静置」的兜底判据。
     */
    fun forceKlineTab(activity: Activity): Boolean {
        val root = activity.window?.decorView ?: return false
        var found = false
        walk(root, 0) { v ->
            if (v is RadioButton && v.text?.toString() == klineTabTextCache && isVisible(v)) {
                found = true
                if (!v.isChecked) runCatching { v.performClick() }
                true
            } else {
                false
            }
        }
        if (!found) {
            GLog.w("截图并添加：找不到「日K」页签，改用标题+静置的就绪判据")
        }
        return found
    }

    private fun resolveKx(env: FeatureEnv): Class<*>? {
        if (env.classLoader !== classLoader) reset(env.classLoader)
        kxClass?.let { return it }
        // 永久熔断的代价太大：第一次进详情页太早、KXChartGroupView 还没加载时，
        // 整个进程都会退化成「标题 + 静置」，K 线就绪门禁形同虚设。改成退避重试。
        val now = SystemClock.elapsedRealtime()
        if (kxFailedAt != 0L && now - kxFailedAt < KX_RETRY_MS) return null
        val cls = Reflect.loadClass(StgSnapAddTargets.KX_GROUP_CLASS, env.classLoader)
        if (cls == null) {
            return failKx("截图并添加：找不到 ${StgSnapAddTargets.KX_GROUP_CLASS}")
        }
        val info = Reflect.findField(cls, StgSnapAddTargets.KX_INFO_FIELD)
        if (info == null) {
            return failKx("截图并添加：${StgSnapAddTargets.KX_GROUP_CLASS} 没有 ${StgSnapAddTargets.KX_INFO_FIELD}")
        }
        val infoCls = info.type
        val items = Reflect.findField(infoCls, StgSnapAddTargets.KX_ITEMS_FIELD)
        if (items == null) {
            return failKx("截图并添加：${infoCls.simpleName} 没有 ${StgSnapAddTargets.KX_ITEMS_FIELD}")
        }
        kxClass = cls
        kxInfoField = info
        kxItemsField = items
        kxFailedAt = 0L
        return cls
    }

    /**
     * 记一次解析失败并进入退避窗口。首次失败打 WARN（用户导出日志时能看见），
     * 窗口内的重复失败降 DEBUG，避免每页都刷一条。
     */
    private fun failKx(message: String): Class<*>? {
        val first = kxFailedAt == 0L
        kxFailedAt = SystemClock.elapsedRealtime()
        if (first) GLog.w(message) else GLog.d("$message（退避中，${KX_RETRY_MS / 1000} 秒后重试）")
        return null
    }

    private fun titleId(activity: Activity): Int {
        var id = titleIdCache
        if (id != 0) return id
        for (name in StgSnapAddTargets.PAGE_TITLE_ID_CANDIDATES) {
            id = runCatching {
                activity.resources.getIdentifier(name, "id", StgSnapAddTargets.TARGET_PKG)
            }.getOrDefault(0)
            if (id != 0) {
                titleIdCache = id
                return id
            }
        }
        return 0
    }

    private fun nonEmpty(value: Any?): Boolean = when (value) {
        null -> false
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        is Array<*> -> value.isNotEmpty()
        is IntArray -> value.isNotEmpty()
        is LongArray -> value.isNotEmpty()
        else -> true
    }

    private fun isVisible(v: View): Boolean {
        if (!v.isShown) return false
        val r = Rect()
        return v.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0
    }

    private fun walk(view: View, depth: Int, visit: (View) -> Boolean): Boolean {
        if (visit(view)) return true
        if (depth < 40 && view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (walk(view.getChildAt(i), depth + 1, visit)) return true
            }
        }
        return false
    }
}
