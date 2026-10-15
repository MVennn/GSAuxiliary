package io.github.SansKritO4.GSAuxiliary.feature.uiclean

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.core.HookBag
import io.github.SansKritO4.GSAuxiliary.core.TargetApp
import io.github.SansKritO4.GSAuxiliary.feature.Feature
import io.github.SansKritO4.GSAuxiliary.feature.FeatureEnv
import io.github.SansKritO4.GSAuxiliary.util.Reflect
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「界面精简」类功能的公共骨架。
 *
 * ## 一、什么时候去改界面（这是本类存在的全部理由）
 *
 * 精简类功能都有同一个难点：**目标视图什么时候出现**。
 *
 *  | 时机 | 靠不靠得住 |
 *  |---|---|
 *  | `Activity.onCreate` | ✗ 布局还没 inflate |
 *  | `Activity.onResume` | ✗ 主壳是 Fragment 容器，此刻当前页的视图往往还没挂上 |
 *  | 只挂 `onResume` 一次 | ✗ 用户在底栏切 Tab 时**不会**再走 `onResume`，切过去那一页永远是原样 |
 *  | **`ViewTreeObserver.OnGlobalLayoutListener`** | ✓ 任何一次布局完成都会回调 —— 首屏铺开、切 Tab、异步内容插入，全都覆盖 |
 *
 * 所以本类的策略是「**两条腿**」：
 *
 *  1. `Activity.onResume` → 挂上全局布局回调 + 立刻试一次（页面回来时最快生效）；
 *  2. 全局布局回调 → 节流后重试（覆盖切 Tab / 异步加载）。
 *
 * 两条腿都走同一个 [applyTo]，**幂等**是硬要求：已经处理过的视图要能一眼认出来并
 * 立刻返回（见 [alreadyHidden] / [stillAttached]），否则布局回调会在每一帧把
 * 整棵视图树走一遍。
 *
 * ## 二、重试、失配与「校准入口」
 *
 * [applyTo] 返回 `false` = **认不出目标控件**，分两条路处理：
 *
 *  - **`onResume` 那条链**：分帧重试 [retryAttempts] 次（页面异步挂载的等待窗口），
 *    失败只记一条 `debug` 日志 —— 此刻用户可能还没切到目标页，不该打扰他。
 *  - **布局回调那条链**：连续 [missScanThreshold] 次认不出来，
 *    才打**一条**警告 + 一份**当前页面的视图树快照**（[UiClean.dump]）。
 *
 * 为什么报告挂在布局回调而不是 `onResume`：布局回调只在「页面真的在屏幕上、
 * 并且刚刚发生过布局」时触发，所以它打出来的快照**就是用户正看着的那一页**。
 * 挂在 `onResume` 上的话，主壳里切 Tab 不会触发 `onResume`，
 * 打出来的很可能是另一个 Tab 的树，校准价值大打折扣。
 *
 * 报告频率做了三重限制（同一 `decorView` 上）：间隔至少 [reportCooldownMs]、
 * 最多 [maxReports] 次、成功一次就清零 —— 既能覆盖「用户先看错页再切过去」，
 * 又不会刷屏。
 *
 * 这份快照就是**校准入口**：里面有真实的资源 id 名与屏幕坐标，
 * 照着往 `*Targets.kt` 的候选数组里加一项就能修好，不需要 adb。
 *
 * ## 三、关掉开关要立刻还原
 *
 * [onConfigChanged] 里 [ViewPatcher.restoreAll] + 摘掉布局回调。
 * 这是「关开关立刻生效」的实现点（与其它功能一致）。
 */
abstract class UiCleanFeature : Feature {

    /** 需要处理的页面（Activity 类名）；不在这张表里的页面一次都不跑 */
    protected abstract val pages: Array<String>

    /** 本功能的开关在配置里读哪个字段 */
    protected abstract fun switchedOn(cfg: ConfigSnapshot): Boolean

    /**
     * 在当前页面上应用本功能。
     *
     * 返回 `true` = 处理完了（**包括「这页本来就没有目标控件」**）；
     * 返回 `false` = **认不出目标控件**，交给基类按上面的策略重试 / 报告。
     *
     * 幂等是硬要求：见类注释第一节。
     */
    protected abstract fun applyTo(env: FeatureEnv, activity: Activity): Boolean

    /** `onResume` 那条链的重试次数与间隔 */
    protected open val retryAttempts: Int get() = 10
    protected open val retryIntervalMs: Long get() = 150L

    /** 布局回调的节流间隔：同一页面最多这么久跑一次 [applyTo] */
    protected open val layoutThrottleMs: Long get() = 300L

    /** 连续多少次布局回调都认不出来，才报告一次 */
    protected open val missScanThreshold: Int get() = 3

    /** 同一页面上两次报告的最小间隔 */
    protected open val reportCooldownMs: Long get() = 4000L

    /** 同一页面上最多报告几次（够覆盖「先看错页、再切到目标页」） */
    protected open val maxReports: Int get() = 3

    /** 认不出目标控件时是否把视图树打进日志（校准用，默认开） */
    protected open val dumpOnMiss: Boolean get() = true

    /** 本功能的补丁表；所有改动都过它，才能一键还原 */
    protected val patcher: ViewPatcher by lazy { ViewPatcher(id) }

    // ------------------------------------------------------------------ 状态

    /** 页面 → 已挂的全局布局回调 */
    private val watchers =
        Collections.synchronizedMap(WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>())

    /** 页面 → 上次跑 [applyTo] 的时间戳（节流用） */
    private val lastRunAt = Collections.synchronizedMap(WeakHashMap<View, Long>())

    /** 页面 → 连续认不出来的次数 */
    private val missScans = Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** 页面 → 上次报告时间 / 已报告次数 */
    private val lastReportAt = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val reportCounts = Collections.synchronizedMap(WeakHashMap<View, Int>())

    // ------------------------------------------------------------------ Feature

    final override fun isEnabled(cfg: ConfigSnapshot): Boolean = switchedOn(cfg)

    final override fun install(env: FeatureEnv, bag: HookBag) {
        val cls = Reflect.loadClass("android.app.Activity", env.classLoader)
        if (cls == null) {
            GLog.e("$id：找不到 android.app.Activity，功能不会生效")
            return
        }
        var hooked = 0
        for (m in Reflect.methodsNamed(cls, "onResume")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("$id:onResume")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching { (chain.thisObject as? Activity)?.let { onPage(env, it) } }
                            .onFailure { GLog.w("$id：onResume 处理异常", it) }
                        result
                    }
            )
            hooked++
        }
        if (hooked == 0) GLog.e("$id：没挂上 Activity.onResume") else
            GLog.i("$id：已挂 $hooked 个 Activity.onResume")
    }

    final override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        // 框架推送的配置变化已经在主线程分发到这里
        if (switchedOn(cfg)) return
        // 没有改动过就不出声（restoreAll 自己有日志），免得每次改配置都刷 5 行噪音
        if (patcher.size == 0) {
            detachAll()
            return
        }
        detachAll()
        GLog.i("$id：已关闭，界面恢复原样")
    }

    // ------------------------------------------------------------------ 调度

    private fun onPage(env: FeatureEnv, activity: Activity) {
        if (!pages.contains(activity.javaClass.name)) return
        if (!switchedOn(env.config)) {
            detachAll()
            return
        }
        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return

        // 先挂回调：切 Tab / 异步内容插入都靠它兜住
        watchLayout(env, activity, root)

        // 再立刻试一次：从别的页面返回时不用等下一次布局
        retry(env, activity, root, 0)
    }

    /** `onResume` 链：分帧重试，失败只记 debug（此刻用户可能还没切到目标页） */
    private fun retry(env: FeatureEnv, activity: Activity, root: View, attempt: Int) {
        if (!switchedOn(env.config)) return
        if (activity.isFinishing || activity.isDestroyed) return

        if (runCatching { applyTo(env, activity) }.getOrElse { GLog.w("$id：应用失败", it); true }) {
            return
        }
        if (attempt >= retryAttempts) {
            GLog.d("$id：${activity.javaClass.name} 上重试 $retryAttempts 次还没认出来，等布局回调继续")
            return
        }
        root.postDelayed(
            { runCatching { retry(env, activity, root, attempt + 1) } },
            retryIntervalMs,
        )
    }

    /** 挂全局布局回调（每个 `decorView` 只挂一次） */
    private fun watchLayout(env: FeatureEnv, activity: Activity, root: View) {
        if (watchers.containsKey(root)) return
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!switchedOn(env.config)) return@OnGlobalLayoutListener
            if (activity.isFinishing || activity.isDestroyed) return@OnGlobalLayoutListener
            val now = SystemClock.uptimeMillis()
            val last = synchronized(lastRunAt) { lastRunAt[root] ?: 0L }
            if (now - last < layoutThrottleMs) return@OnGlobalLayoutListener
            synchronized(lastRunAt) { lastRunAt[root] = now }
            onLayoutScan(env, activity, root)
        }
        watchers[root] = listener
        runCatching { root.viewTreeObserver.addOnGlobalLayoutListener(listener) }
            .onFailure {
                watchers.remove(root)
                GLog.w("$id：挂全局布局回调失败", it)
            }
    }

    /** 布局回调里的扫描：认不出来就累计次数，够数才报告一次 */
    private fun onLayoutScan(env: FeatureEnv, activity: Activity, root: View) {
        val done = runCatching { applyTo(env, activity) }.getOrElse {
            GLog.w("$id：布局回调里应用失败", it)
            true
        }
        if (done) {
            synchronized(missScans) { missScans[root] = 0 }
            return
        }
        val n = synchronized(missScans) {
            val v = (missScans[root] ?: 0) + 1
            missScans[root] = v
            v
        }
        if (n >= missScanThreshold) maybeReport(activity, root)
    }

    /** 报告「认不出来」：限频 + 限量 + 附一份当前页面的视图树快照 */
    private fun maybeReport(activity: Activity, root: View) {
        val now = SystemClock.uptimeMillis()
        val last = synchronized(lastReportAt) { lastReportAt[root] ?: 0L }
        if (now - last < reportCooldownMs) return
        val count = synchronized(reportCounts) { reportCounts[root] ?: 0 }
        if (count >= maxReports) return

        synchronized(reportCounts) { reportCounts[root] = count + 1 }
        synchronized(lastReportAt) { lastReportAt[root] = now }
        synchronized(missScans) { missScans[root] = 0 }

        val page = activity.javaClass.name
        GLog.w(
            "$id：在 $page 上认不出目标控件（第 ${count + 1} 次报告）——" +
                "可能是宿主改版，也可能是当前不在目标页"
        )
        if (!dumpOnMiss) return
        val ids = runCatching { UiClean.idsFor(activity, TargetApp.PACKAGE) }.getOrNull()
        GLog.i(
            "$id：$page 视图树快照（照着往 *Targets.kt 的候选数组里加 id）\n" +
                UiClean.dump(root, ids)
        )
    }

    /** 摘掉所有布局回调 + 还原全部界面改动 */
    private fun detachAll() {
        val list = synchronized(watchers) {
            val l = watchers.entries.toList()
            watchers.clear()
            l
        }
        for ((v, l) in list) {
            runCatching { v.viewTreeObserver.removeOnGlobalLayoutListener(l) }
        }
        synchronized(lastRunAt) { lastRunAt.clear() }
        synchronized(missScans) { missScans.clear() }
        synchronized(lastReportAt) { lastReportAt.clear() }
        synchronized(reportCounts) { reportCounts.clear() }
        patcher.restoreAll()
    }

    // ------------------------------------------------------------------ 给子类用

    /**
     * 幂等快速通道（被隐藏的视图版）：目标视图**还在树上**且已经是 `GONE` ——
     * 说明本功能已经处理过，直接返回，不必再走一遍整棵视图树。
     *
     * 用 `isAttachedToWindow` 而不是只判 `visibility`：宿主重建 Fragment 后
     * 旧的视图对象仍然是 `GONE`，但它已经不在树上了，必须重新认一遍。
     */
    protected fun alreadyHidden(v: View?): Boolean =
        v != null && v.isAttachedToWindow && v.visibility == View.GONE

    /**
     * 幂等快速通道（容器版）：上次处理过的**容器**还在树上 → 本功能已经生效。
     *
     * 适用于「改的是容器的子项或容器自己的高度」这类功能 ——
     * 它们没有「被隐藏的那一个视图」可以拿来判，用容器本身当标记即可：
     * 宿主重建页面时旧容器会 `detach`，标记自动失效，于是重新处理一遍。
     */
    protected fun stillAttached(v: View?): Boolean = v != null && v.isAttachedToWindow
}
