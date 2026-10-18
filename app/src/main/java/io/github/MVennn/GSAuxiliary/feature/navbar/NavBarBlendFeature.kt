package io.github.MVennn.GSAuxiliary.feature.navbar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * 功能：**手势导航条（小白条）区域填充与紧邻界面一致的颜色**。
 *
 * ## 做什么
 *
 * 屏幕最下面那一条（手势导航条）在金太阳里是**纯黑 / 纯白**的：
 * 宿主 targetSdk 30、全程非沉浸，那一块不属于内容区，由 `DecorView` 按
 * `window.navigationBarColor` 自己填，而宿主没设这个值 —— 于是落回主题
 * `windowBackground`，深色模式纯黑、浅色模式纯白，与上面一刀切。
 *
 * 本功能把那一块换成**从内容最后一行取下来的颜色**，让它融进界面。
 * **不做真沉浸**（不把内容铺到物理底边、不接管 inset）—— 那要动宿主的
 * 布局与 inset 处理，风险与收益完全不成比例。这里只改一个窗口属性。
 *
 * ## 三件事缺一不可（只改颜色是不够的）
 *
 * | # | 动作 | 不改会怎样 |
 * |---|---|---|
 * | 1 | 写 `window.navigationBarColor` | 那一块仍是主题底色 |
 * | 2 | 关掉 `navigationBarContrastEnforced`（API 29+） | 系统为保证金太阳的小横杠看得见，会在上面**叠一层半透明遮罩** —— 「观感生硬」的一半是这个（模块自己的设置界面在 2.5.0 踩过同一个坑，见 `docs/UI.md` §11.1） |
 * | 3 | 按填进去的颜色设横杠明暗 | 关掉遮罩后横杠画深色还是浅色全由 `appearanceLightNavigationBars` 决定，不设就会出现「白横杠压在白底上」 |
 *
 * ## 什么时候改
 *
 * 每个 `Activity` 有自己的窗口，所以**每个页面都要各来一遍**。
 * 沿用 `PageSyncFeature` 那套结论（见其类注释第一节）：
 *
 *  - `Activity.onResume` —— 此刻 `decorView` 还没 attach，**量不出尺寸**，
 *    所以它只负责「挂上布局回调 + 预热底色」；
 *  - `ViewTreeObserver.OnGlobalLayoutListener` —— **真正的落地点**。
 *    它发生在**这一帧画出来之前**，所以首帧就已经是最终颜色，用户看不到中间态；
 *  - `Activity.onConfigurationChanged` —— 深浅色切换 / 转屏时宿主不一定重建页面，
 *    这时要主动把「主题底色」与「已应用的颜色」一起作废重算。
 *
 * ## 取色带的位置是命门
 *
 * `decorView` 是**整窗高度**（导航条那一块也是它的孩子）。所以取色必须
 * 落在 `decor.height - navBarHeight` 那条线**之上**，否则取到的是
 * 「自己上一次填进去的颜色」，会自我循环、永远不变。
 * 见 [NavBarTint.bandOf] 与 `NavBarTargets` 类注释第二节。
 *
 * ## 关掉开关要立刻还原
 *
 * [onConfigChanged] 里恢复每个窗口原样的 `navigationBarColor` /
 * 强制对比度 / 横杠明暗，并摘掉布局回调。这是「关开关立刻生效」的实现点。
 */
class NavBarBlendFeature : Feature {

    override val id = "nav_bar_blend"
    override val title = "手势导航条同色"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 窗口（`decorView`）→ 该窗口的原值与记账 */
    private val states = Collections.synchronizedMap(WeakHashMap<View, WinState>())

    /** 窗口 → 已挂的全局布局回调 */
    private val watchers =
        Collections.synchronizedMap(WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>())

    /** 窗口 → 上次跑同步的时刻（节流） */
    private val lastRunAt = Collections.synchronizedMap(WeakHashMap<View, Long>())

    /** 窗口 → 是否已排队一次「延后同步」（限速时只延迟、不重复排队） */
    private val deferred = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /** Activity 类名 → 这个页面最近一次落地的颜色与当时的深浅模式（进程级；结构档空手时首帧顶上） */
    private val lastColors = Collections.synchronizedMap(HashMap<String, Pair<Int, Boolean>>())

    /** 这个 Activity 当前处于深色模式吗（深浅切换后同页面的底色必然不同，缓存按此作废） */
    private fun isNight(activity: Activity): Boolean =
        (activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    // ---------------------------------------------------------------- Feature

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.navBarBlend

    override fun install(env: FeatureEnv, bag: HookBag) {
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
        for (m in Reflect.methodsNamed(cls, "onConfigurationChanged")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("$id:onConfigurationChanged")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching { (chain.thisObject as? Activity)?.let { onConfig(env, it) } }
                            .onFailure { GLog.w("$id：onConfigurationChanged 处理异常", it) }
                        result
                    }
            )
            hooked++
        }
        if (hooked == 0) GLog.e("$id：没挂上任何 Activity 回调") else
            GLog.i("$id：已挂 $hooked 个 Activity 回调")
    }

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        // 框架推送的配置变化已经在主线程分发到这里
        if (cfg.navBarBlend) return
        detach()
    }

    // ---------------------------------------------------------------- 调度

    private fun onPage(env: FeatureEnv, activity: Activity) {
        if (!isEnabled(env.config)) {
            detach()
            return
        }
        if (skip(activity)) return
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
        attach(env, activity, decor)
        // 页面可能已经布局过（从别的页面返回）→ 立刻试一次，不用等下一次布局
        runSync(env, activity, decor)
    }

    /** 主题 / 深浅色可能整个换了：作废缓存，下一帧重算 */
    private fun onConfig(env: FeatureEnv, activity: Activity) {
        if (!isEnabled(env.config)) return
        // 深浅切换后同一个页面的底色整体变了，跨深浅的缓存值首帧必然是错的 —— 全清。
        // 清掉之后新窗口首帧是宿主默认色（浅色下就是白，无感），像素校准 ~2s 内跟上。
        synchronized(lastColors) { lastColors.clear() }
        if (skip(activity)) return
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
        val st = synchronized(states) { states[decor] } ?: return
        st.base = null
        st.baseTried = false
        st.applied = null
        st.verified = null
        st.lastStructural = null
        runSync(env, activity, decor)
    }

    /**
     * 布局回调：页面刚布局完（**在这一帧画出来之前**），正是取色与上色的时机。
     *
     * 限速时**只延后、不丢弃** —— 布局回调是「界面变了」的唯一信号，
     * 直接丢掉就要白等下一次布局（深浅色切换后可能再没有下一次布局）。
     */
    private fun onLayout(env: FeatureEnv, activity: Activity, decor: View) {
        if (!isEnabled(env.config)) return
        if (skip(activity)) return
        val now = SystemClock.uptimeMillis()
        val last = synchronized(lastRunAt) { lastRunAt[decor] ?: 0L }
        val wait = NavBarTargets.LAYOUT_THROTTLE_MS - (now - last)
        if (wait > 0) {
            deferSync(env, activity, decor, wait)
            return
        }
        runSync(env, activity, decor)
    }

    private fun deferSync(env: FeatureEnv, activity: Activity, decor: View, delayMs: Long) {
        val queued = synchronized(deferred) {
            if (deferred[decor] == true) true else {
                deferred[decor] = true
                false
            }
        }
        if (queued) return
        decor.postDelayed({
            synchronized(deferred) { deferred[decor] = false }
            if (!isEnabled(env.config)) return@postDelayed
            if (skip(activity)) return@postDelayed
            runCatching { runSync(env, activity, decor) }
                .onFailure { GLog.w("$id：延后同步失败", it) }
        }, delayMs)
    }

    private fun runSync(env: FeatureEnv, activity: Activity, decor: View) {
        synchronized(lastRunAt) { lastRunAt[decor] = SystemClock.uptimeMillis() }
        val st = stateOf(decor, activity)

        // 主题底色一个窗口只解析一次（金太阳的 windowBackground 解析不出，
        // 别在每次布局回调里重复那趟 16×16 渲染）；深浅切换时随 base 一起作废
        if (!st.baseTried) {
            st.base = NavBarTint.windowBackground(activity)
            st.baseTried = true
        }
        val base = st.base
        if (base != null) {
            val c = NavBarTint.structural(decor, base)
            // 结构值自己变了 = 背景链动了，上一轮的像素可信值对不上新画面，作废
            if (c != st.lastStructural) st.verified = null
            st.lastStructural = c
            if (c != null) {
                val v = st.verified
                // 像素校准确认过的颜色不被结构值拉回去：结构合成对渐变 / 图片是
                // 盲区，盲区值反复覆盖校准值就是「横跳」。结构值自己变化时可信值
                // 已在上面作废，这里挡不住任何真实变化 —— 校准路径永远畅通。
                val stale = v != null && NavBarTint.distance(c, v) > NavBarTargets.PIXEL_DELTA
                if (!stale && apply(activity, st, c, "结构取色")) {
                    // 颜色真的变了：清掉校准冷却，下一次布局回调立刻校准，
                    // 不用干等 2 秒（错误颜色少停留一秒是一秒）
                    st.pixelAt = 0L
                }
            } else if (st.applied == null) {
                // 首帧就没底色（金太阳主题的 windowBackground 解析不出）——
                // 后续轮次已有像素 / 缓存在管，不打日志了（布局回调每 250ms 一轮）
                GLog.d("$id：${activity.javaClass.simpleName} 首帧无底色，走像素 / 缓存兜底")
                applyCached(activity, st)
            } else {
                applyCached(activity, st)
            }
        } else if (st.applied == null) {
            GLog.d("$id：${activity.javaClass.simpleName} 上解析不出主题底色")
            applyCached(activity, st)
        } else {
            applyCached(activity, st)
        }
        calibrate(activity, decor, st)
    }

    /**
     * 结构档空手时的首帧替补：这个页面**上一次**落地的颜色（进程级缓存）。
     *
     * 同一个 Activity 类的底色几乎总是恒定的 —— 二次进详情页时首帧直接
     * 同步铺上上次取到的色，不再黑着等 `PixelCopy`（真机量过：那要 ~2 秒）。
     * 真变了也不要紧：像素校准紧跟着会把它纠正（校准路径永远畅通）。
     */
    private fun applyCached(activity: Activity, st: WinState) {
        if (st.applied != null) return
        val cached = synchronized(lastColors) { lastColors[activity.javaClass.name] } ?: return
        // 深浅模式对不上就弃用：宿主深浅切换走 recreate，旧 Activity 的
        // onConfigurationChanged 不会打（钩子收不到），唯一可靠的判据是
        // 写入时的模式与现在一致
        if (cached.second != isNight(activity)) return
        apply(activity, st, cached.first, "上次取色")
    }

    // ---------------------------------------------------------------- 上色

    /** @return 是否真的改了窗口颜色（false = 与已应用的颜色相同，短路） */
    private fun apply(activity: Activity, st: WinState, color: Int, why: String): Boolean {
        if (st.applied == color) return false
        val prev = st.applied
        st.applied = color
        synchronized(lastColors) { lastColors[activity.javaClass.name] = color to isNight(activity) }
        // 三键导航时保留系统的强制对比度，保证那三个按键一定看得见
        val keepContrast = !NavBarTint.gestureNav(activity)
        NavBarTint.paint(activity, color, keepContrast)
        val from = if (prev != null) "（原 ${NavBarTint.hex(prev)}）" else ""
        GLog.i(
            "$id：$why ${NavBarTint.hex(color)}$from → ${activity.javaClass.simpleName}" +
                " 导航条已同色${if (keepContrast) "（三键导航，保留强制对比度）" else ""}"
        )
        return true
    }

    /**
     * 像素校准：用 `PixelCopy` 拷「内容最后一行」的真实像素。
     *
     * 它是**异步**的，而且按**连续失败**熔断（[NavBarTargets.PIXEL_MAX_FAILS]）——
     * 部分机型 / 没有 backing surface 的窗口上它恒失败，连续失败就认了，
     * 保留结构取色的结果，绝不无限重试；成功一次就把失败计数清零，
     * 健康窗口可以一直校准下去。
     *
     * 结果只在与当前颜色**肉眼有差别**（[NavBarTargets.PIXEL_DELTA]）时才落地，
     * 省掉无意义的窗口属性刷新；拿到的真值记进 [WinState.verified]，
     * 之后的结构合成值不许把它拉回去（见 [runSync]）。
     */
    private fun calibrate(activity: Activity, decor: View, st: WinState) {
        if (st.pixelFails >= NavBarTargets.PIXEL_MAX_FAILS) return
        val now = SystemClock.uptimeMillis()
        if (now - st.pixelAt < NavBarTargets.PIXEL_COOLDOWN_MS) return

        val band = NavBarTint.bandOf(decor)
        val w = runCatching { activity.window }.getOrNull()
        if (!band.valid || decor.width <= 0 || w == null) return

        st.pixelAt = now

        val bmp = runCatching {
            Bitmap.createBitmap(NavBarTargets.SAMPLE_W, 1, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return
        val src = Rect(0, band.top, decor.width, band.bottom)
        val ref = WeakReference(activity)
        try {
            PixelCopy.request(
                w, src, bmp,
                PixelCopy.OnPixelCopyFinishedListener { res ->
                    // 迟到的结果（开关已关、窗口已还原 / 记账已重建）一律丢弃，
                    // 否则会把还原过的窗口又上一次色
                    if (synchronized(states) { states[decor] !== st }) {
                        bmp.recycle()
                        return@OnPixelCopyFinishedListener
                    }
                    if (res != PixelCopy.SUCCESS) {
                        bmp.recycle()
                        // NO_DATA / SOURCE_INVALID 都是「窗口此刻拷不出」的瞬时态
                        //（首帧未渲染完、recreate、转屏必现）：马上重试大概率就成功，
                        // 不计失败、清冷却 —— 下一次布局回调立刻再拷。
                        // 白吃 2s 冷却会把这类场景的取色从 ~1s 拖到 ~4s（真机量过）。
                        if (res == PixelCopy.ERROR_SOURCE_NO_DATA ||
                            res == PixelCopy.ERROR_SOURCE_INVALID
                        ) {
                            st.pixelAt = 0L
                            GLog.d("$id：PixelCopy 源暂不可用（$res），下一轮立即重试")
                            if (st.transientRetries < NavBarTargets.PIXEL_TRANSIENT_MAX) {
                                st.transientRetries++
                                decor.postDelayed({
                                    if (synchronized(states) { states[decor] === st }) {
                                        runCatching { calibrate(activity, decor, st) }
                                    }
                                }, 300L)
                            }
                        } else {
                            st.pixelFails++
                            GLog.d(
                                "$id：PixelCopy 返回 $res（连续失败 ${st.pixelFails}），保留结构取色结果",
                            )
                        }
                        return@OnPixelCopyFinishedListener
                    }
                    val px = NavBarTint.average(bmp)
                    bmp.recycle()
                    if (Color.alpha(px) == 0) {
                        st.pixelFails++
                        return@OnPixelCopyFinishedListener
                    }
                    st.pixelFails = 0
                    st.transientRetries = 0
                    st.verified = px
                    val a = ref.get() ?: return@OnPixelCopyFinishedListener
                    val cur = st.applied
                    when {
                        cur == null -> apply(a, st, px, "像素取色")
                        NavBarTint.distance(px, cur) > NavBarTargets.PIXEL_DELTA ->
                            apply(a, st, px, "像素校准")
                        else -> Unit
                    }
                },
                mainHandler,
            )
        } catch (t: Throwable) {
            bmp.recycle()
            st.pixelFails++
            GLog.d("$id：PixelCopy 不可用（$t），保留结构取色结果")
        }
    }

    // ---------------------------------------------------------------- 挂载 / 记账

    /** 对话框式 / 悬浮窗口没有「屏幕底部导航条」这一说 */
    private fun skip(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return true
        return runCatching { activity.window?.isFloating }.getOrNull() ?: false
    }

    /** 挂上布局回调（每个 `decorView` 只挂一次） */
    private fun attach(env: FeatureEnv, activity: Activity, decor: View) {
        if (watchers.containsKey(decor)) return
        val ref = WeakReference(activity)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val a = ref.get() ?: return@OnGlobalLayoutListener
            runCatching { onLayout(env, a, decor) }
                .onFailure { GLog.w("$id：布局回调处理异常", it) }
        }
        watchers[decor] = listener
        runCatching { decor.viewTreeObserver.addOnGlobalLayoutListener(listener) }
            .onFailure {
                watchers.remove(decor)
                GLog.w("$id：挂全局布局回调失败", it)
            }
    }

    /** 取（并首次记录）该窗口的原值 */
    private fun stateOf(decor: View, activity: Activity): WinState =
        synchronized(states) {
            states[decor] ?: WinState(
                activity = WeakReference(activity),
                origColor = runCatching { activity.window?.navigationBarColor }.getOrNull(),
                origContrast = if (Build.VERSION.SDK_INT >= 29) {
                    runCatching { activity.window?.isNavigationBarContrastEnforced }.getOrNull()
                } else {
                    null
                },
                origLight = NavBarTint.navBarLightNow(activity),
            ).also { states[decor] = it }
        }

    // ---------------------------------------------------------------- 还原

    /** 摘掉全部回调 + 把改过的窗口还原原样（幂等） */
    private fun detach() {
        val ws = synchronized(watchers) {
            val l = watchers.entries.toList()
            watchers.clear()
            l
        }
        for ((v, l) in ws) runCatching { v.viewTreeObserver.removeOnGlobalLayoutListener(l) }

        val ss = synchronized(states) {
            val l = states.values.toList()
            states.clear()
            l
        }
        var n = 0
        for (st in ss) {
            if (st.applied == null) continue
            restore(st)
            n++
        }

        synchronized(lastRunAt) { lastRunAt.clear() }
        synchronized(deferred) { deferred.clear() }
        if (n > 0) GLog.i("$id：已关闭，$n 个窗口的导航条恢复原样")
    }

    private fun restore(st: WinState) {
        val a = st.activity.get() ?: return
        val w = runCatching { a.window }.getOrNull() ?: return
        st.origColor?.let { c -> runCatching { w.navigationBarColor = c } }
        if (Build.VERSION.SDK_INT >= 29) {
            st.origContrast?.let { v -> runCatching { w.isNavigationBarContrastEnforced = v } }
        }
        st.origLight?.let { v -> NavBarTint.setNavBarLight(a, v) }
        st.applied = null
    }

    /** 一个窗口的原值与记账 */
    private class WinState(
        val activity: WeakReference<Activity>,
        val origColor: Int?,
        val origContrast: Boolean?,
        val origLight: Boolean?,
        /** 本窗口解析出的主题底色（深浅色切换时作废） */
        var base: Int? = null,
        /** 主题底色是否已经试过解析（解析不出也记 —— 别每次布局回调都重跑一遍） */
        var baseTried: Boolean = false,
        /** 已经写进窗口的颜色；null = 还没写过 */
        var applied: Int? = null,
        /** 像素校准**连续失败**次数（成功即清零；到顶 = 这窗口 PixelCopy 恒失败，熔断） */
        var pixelFails: Int = 0,
        /** 「源暂不可用」的连续主动重试次数（成功即清零；到顶停手等布局回调） */
        var transientRetries: Int = 0,
        var pixelAt: Long = 0L,
        /** 像素校准拿到过的真值；结构合成值与它差得远时（结构盲区）不许覆盖它 */
        var verified: Int? = null,
        /** 上一次的结构合成值 —— 它变化说明背景链动了，[verified] 随之作废 */
        var lastStructural: Int? = null,
    )
}
