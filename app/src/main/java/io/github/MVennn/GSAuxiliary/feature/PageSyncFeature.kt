package io.github.MVennn.GSAuxiliary.feature

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.core.TargetApp
import io.github.MVennn.GSAuxiliary.util.Reflect
import io.github.MVennn.GSAuxiliary.util.ViewIds
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「在宿主页面上动视图」类功能的**公共调度骨架**。
 *
 * 两个使用方：`UiCleanFeature`（隐藏 / 压缩现成视图）与 `StockDetailBarFeature`（往底栏插一个入口）。
 * 它们形态不同，但要解决的是**同一件事**：
 *
 * ## 一、改视图的时机（两条腿都不够，得三条）
 *
 *  | 时机 | 靠不靠得住 |
 *  |---|---|
 *  | `Activity.onCreate` | ✗ 布局还没 inflate；而且此刻去拿 `window.decorView` 会把宿主的窗口装饰**提前定型**（宿主后面再 `requestWindowFeature` 就晚了），所以本类不碰它 |
 *  | `Activity.onResume` | ✗ 此刻 `decorView` **还没 attach 到窗口** —— `ActivityThread.handleResumeActivity` 是先调 `onResume`、再 `wm.addView(decor)`。整棵树 `isShown()` 全是 false、高度全是 0，**什么都认不出来** |
 *  | 只挂 `onResume` 一次 | ✗ 切 Tab、左右翻页、宿主自己重建 Fragment 时**都不会**再走 `onResume` —— 改过的界面被宿主重建后就再也回不来了 |
 *  | **`ViewTreeObserver.OnGlobalLayoutListener`** | ✓ 任何一次布局完成都会回调：首屏铺开、切 Tab、翻页、异步内容插入、宿主重建视图，全都覆盖。**它发生在这一帧画出来之前** |
 *  | **`ViewTreeObserver.OnPreDrawListener`** | ✓ 同一次遍历里更靠后（布局之后、绘制之前），用来回答「这一帧能不能画」 |
 *
 * 所以调度分三层：
 *
 * ```
 * Activity.onResume ─┬─→ 挂全局布局回调 + 挂首帧屏障（每个 decorView 只挂一次）
 *                    └─→ 立刻 sync()（从别的页面返回时不用等下一次布局）
 *
 * 全局布局回调 ──→ sync()（反复认不出来时按 layoutThrottleMs 限速，只延迟不丢弃；
 *                    上一轮有实际改动时按 actionCooldownMs 冷却，冷却期内直接跳过）
 *
 * 首帧屏障(OnPreDraw) ──→ 「自上一帧以来界面被动过」就 return false，这一帧不画
 * ```
 *
 * **为什么 `onResume` 挂得上监听器**：此刻 `decorView` 还没 attach，`View.getViewTreeObserver()`
 * 返回的是一份「游离」的 observer；`View.dispatchAttachedToWindow` 会把它**合并**进 attach 后
 * 那一份，所以监听器一个不丢，而且**必定早于第一次布局**。
 *
 * ## 二、幂等 + 自愈：把「宿主重建视图」当成常态
 *
 * [sync] 必须是**幂等**的，而且不能只判「我记不记得自己做过」——
 * 宿主的真实行为是**随时可能重建同一块视图**（切 Tab 重建 Fragment、翻页换形态、
 * 布局参数被重置），我们改过的东西会随着旧视图一起消失。
 *
 * 所以每个功能的 [sync] 都要按这个顺序想：
 *
 *  1. **认目标**（找不到就 `false`，等下一次布局）；
 *  2. **判现场是否还成立**：不是「我之前注入过」，而是「**我放进去的那个东西现在还在不在树上**」——
 *     只查自己的记账表会把「宿主把子视图清掉了、记账表还在」误判成「已经好了」，然后**永远不再修**；
 *  3. **不成立就重做**（顺手把过期的记账清掉），成立就**顺手把被宿主改回去的参数再压一次**；
 *  4. 真的动了视图 → [noteChange]（或让记账工具自己计数，见 [mutations]）。
 *
 * ## 三、首帧屏障：为什么「早点挂钩子」还不够
 *
 * 改得早只保证「改动来得早」，保证不了「用户看不见中间态」：
 *
 *  - 有的功能**一拍改不完** —— `DockCleanFeature.fillRow` 的四招状态机，
 *    每一招都得等下一次布局量过尺寸才知道有没有用，中间连着好几帧界面都处在「改到一半」；
 *  - 宿主的首屏也常常是**分批挂上来**的 —— 主壳先布局，Fragment 内容随后才 add；
 *  - 宿主重建视图时更是如此：新视图先原样画出来，我们再改，就是肉眼可见的一跳。
 *
 * `OnPreDrawListener` 恰好落在「布局完成、这一帧还没画」这个唯一可用的节点上：
 * 返回 `false` 就**取消这一帧的绘制**（`ViewRootImpl` 收到后会重排一次再画）：
 *
 * ```
 * 布局完成 ─► 布局回调改树 ─► 首帧屏障：动过就 return false（这一帧不画）
 *                                │
 *                                └─► 重新布局 ─► 再改树 ─► 没再动过 → 放行，画最终结果
 * ```
 *
 * 判据是 [mutations] 与「上一帧观察到的值」是否相等：相等 = 自上一帧以来没人动过界面 =
 * 现在画出来的就是最终结果。
 *
 * 拦帧预算**分两档**（这是真机逼出来的，别拍脑袋改）：
 *
 *  - **启动窗口内**（进这一页起 [startupHoldMs] 之内，启动图通常还盖着）：
 *    最多连拦 [maxHeldFrames] 帧 / [maxHoldWindowMs]。实测宿主布局一次要 100~350ms，
 *    而 `DockCleanFeature` 的四招状态机整体要 ~800ms —— 只有这段时间亏得起；
 *  - **启动窗口外**（用户在看着这一页）：最多 [steadyHeldFrames] 帧 / [steadyHoldWindowMs]。
 *    再多就宁可让他看见一次中间态，也不能让界面「卡住不动」。
 *  - 任何一档预算用尽都立刻放行，并进入 [holdMuteMs] 静默期。**绝不把界面冻住。**
 *
 * ## 四、预热：把「查 id」挪到启动图那会儿做
 *
 * `Resources.getIdentifier` 是按名字扫资源表，不便宜。本类在**每个页面 resume 时**
 * 就把本功能用到的 id 名解析掉（[idNames]，预热用 util 层的 `IdCache.warm`）——
 * 第一个 resume 的页面通常就是启动图，那一两秒主线程正好闲着，
 * 等真正要改界面时只剩哈希查表，首帧不背这个开销。
 *
 * ## 五、失配与「校准入口」
 *
 * [sync] 返回 `false` = 认不出目标控件。连续 [missScanThreshold] 次之后打**一条**警告 +
 * 一份**当前页面的视图树快照**（[ViewIds.dump]）。报告挂在布局回调而不是 `onResume`，
 * 是因为布局回调只在「页面真的在屏幕上、并且刚刚发生过布局」时触发，
 * 打出来的快照**就是用户正看着的那一页**。报告频率三重限制（同一 `decorView` 上）：
 * 间隔至少 [reportCooldownMs]、最多 [maxReports] 次、成功一次就清零。
 *
 * 那份快照就是**校准入口**：里面有真实的资源 id 名与屏幕坐标，
 * 照着往对应 `*Targets.kt` 的候选数组里加一项就能修好，不需要 adb。
 *
 * ## 六、关掉开关要立刻还原
 *
 * [onConfigChanged] 里 [restore] + 摘掉两个回调。这是「关开关立刻生效」的实现点。
 */
abstract class PageSyncFeature : Feature {

    /** 需要处理的页面（Activity 类名）；不在这张表里的页面一次都不跑 */
    protected abstract val pages: Array<String>

    /** 本功能的开关在配置里读哪个字段 */
    protected abstract fun switchedOn(cfg: ConfigSnapshot): Boolean

    /**
     * 在当前页面上把本功能的状态同步到位。
     *
     * 返回 `true` = 这一页已经处理好（**包括「这页本来就没有目标控件」与「等下一次布局再说」**）；
     * 返回 `false` = **认不出目标控件**，交给基类累计次数、限速、必要时打视图树快照。
     *
     * **幂等 + 自愈是硬要求**：见类注释第二节。
     */
    protected abstract fun sync(env: FeatureEnv, activity: Activity): Boolean

    /**
     * 累计改动次数（首帧屏障的判据）。
     *
     * 默认用基类自己的计数（自己动视图的功能什么都不用写，[noteChange] 即可）；
     * 用记账工具的（如 `ViewPatcher`）覆写成它的计数，省得两边对不上。
     */
    protected open val mutations: Int get() = selfChanges

    /** 关掉开关时还原现场（默认什么都不做） */
    protected open fun restore() {}

    /** 现在有没有需要还原的改动（决定关开关时要不要打那行日志） */
    protected open val hasChanges: Boolean get() = false

    /** 布局回调的限速间隔（只在反复认不出来时生效，且只延迟不丢弃） */
    protected open val layoutThrottleMs: Long get() = 300L

    /**
     * 同步**有实际动作**后的冷却间隔（ms）。
     *
     * 审查 2026-10-17：布局回调在 RecyclerView 滚动、动画、Fragment 重建时是**高频**来源，
     * 而认出目标页之后每一轮回调都会全量跑一遍所有启用功能的 sync —— 多数功能有
     * alreadyHidden / stillAttached 快速通道，但快速通道失效（宿主重建视图后 id 直取失败）
     * 时会落到全树遍历（如 HomeBannerFeature 的 UiClean.walk 整棵 decor），没有兜底。
     * 所以「上一轮真的动过视图」就冷却 [actionCooldownMs]：冷却期内布局回调直接跳过；
     * 空闲轮不冷却，宿主之后真改了视图（快速通道失效、需要重扫描），冷却一过下一次布局就能响应。
     */
    protected open val actionCooldownMs: Long get() = 1500L

    /**
     * 这一轮之后**还有下一趟要跑**吗（多招状态机用，默认没有）。
     *
     * 返回 true 时 [actionCooldownMs] 让路 —— 布局回调照常进来，让状态机按「改一趟、
     * 量一趟」的节奏推进到收敛。只在没收敛时返回 true，收敛后必须回到 false，
     * 否则等于把冷取消掉（滚动期又会每帧全量重扫）。
     */
    protected open val needsFollowUp: Boolean get() = false

    /** 连续多少次布局回调都认不出来，才报告一次 */
    protected open val missScanThreshold: Int get() = 3

    /** 同一页面上两次报告的最小间隔 */
    protected open val reportCooldownMs: Long get() = 4000L

    /** 同一页面上最多报告几次 */
    protected open val maxReports: Int get() = 3

    /** 认不出目标控件时是否把视图树打进日志（校准用，默认开） */
    protected open val dumpOnMiss: Boolean get() = true

    /** 首帧屏障：**启动窗口**长度（ms）—— 页面起来后的这段时间里拦帧可以放宽（启动图还盖着） */
    protected open val startupHoldMs: Long get() = 1500L

    /** 首帧屏障：启动窗口内的连拦上限（帧）。够 `DockCleanFeature` 的四招状态机走完 */
    protected open val maxHeldFrames: Int get() = 12

    /** 首帧屏障：启动窗口内连拦的总时长上限（ms） */
    protected open val maxHoldWindowMs: Long get() = 1500L

    /**
     * 首帧屏障：**启动窗口外**（用户已经在看着这一页了）的连拦上限（帧）。
     *
     * 为什么两档：实测宿主的布局一次要 100~350ms（冷启动更慢），
     * 而精简类功能的「多招状态机」整体要 ~800ms —— 只有启动图盖着的那段时间才亏得起。
     * 用户盯着看的页面最多拦 [steadyHeldFrames] 帧（约 32ms），
     * 再多就宁可让他看见一次中间态，也不能让界面「卡住不动」。
     */
    protected open val steadyHeldFrames: Int get() = 2

    /** 首帧屏障：启动窗口外连拦的总时长上限（ms） */
    protected open val steadyHoldWindowMs: Long get() = 120L

    /** 首帧屏障：拦帧预算用尽后的静默期（ms）—— 这段时间内一律放行，避免把界面冻住 */
    protected open val holdMuteMs: Long get() = 800L

    /** 资源 id 属于哪个包（预热与树快照都用它） */
    protected open val idPkg: String get() = TargetApp.PACKAGE

    /**
     * 本功能用到的**全部**资源 id 名（候选数组拼起来即可）。
     *
     * 只用于预热：在任意页面 resume 时提前解析，省掉首帧里的 `getIdentifier`。
     * 返回空数组就不预热（功能不靠资源 id 时）。
     */
    protected open val idNames: Array<String> get() = emptyArray()

    // ------------------------------------------------------------------ 状态

    /** 页面（`decorView`）→ 已挂的全局布局回调 */
    private val layoutWatchers =
        Collections.synchronizedMap(WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>())

    /** 页面（`decorView`）→ 已挂的首帧屏障 */
    private val paintGates =
        Collections.synchronizedMap(WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>())

    /** 页面 → 上一帧观察到的改动计数（首帧屏障的判据） */
    private val mutAtLastFrame = Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** 页面 → 已连拦帧数 / 第一次拦帧的时刻 / 静默期截止时刻 */
    private val heldFrames = Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val heldSince = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val holdMutedUntil = Collections.synchronizedMap(WeakHashMap<View, Long>())

    /** 页面 → 上次跑 [sync] 的时刻 / 上次认出来了没有 / 连续认不出来的次数 */
    private val lastRunAt = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val lastScanOk = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val missScans = Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** 页面 → 是否已有一次「延后同步」排队（限速时只延迟、不重复排队） */
    private val deferredScan = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /**
     * 页面 → 上一次同步**真动了视图**的时刻（[actionCooldownMs] 冷却的起点，审查 2026-10-17）。
     *
     * 判据是现成的：一轮 [sync] 前后 [mutations] 涨了（[noteChange] 或记账工具的计数），
     * 和首帧屏障「界面被动过」用的是同一个信号。只在有动作时写入 —— 空闲轮不冷却。
     */
    private val lastActionAt = Collections.synchronizedMap(WeakHashMap<View, Long>())

    /** 页面 → 上次报告时间 / 已报告次数 */
    private val lastReportAt = Collections.synchronizedMap(WeakHashMap<View, Long>())
    private val reportCounts = Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** 页面 → 进入本页的时刻（[startupHoldMs] 的起点，见 [onPreDraw]） */
    private val enteredAt = Collections.synchronizedMap(WeakHashMap<View, Long>())

    /** 资源 id 是否已经预热过（进程内一次就够，之后每个页面 resume 都不用再跑） */
    private var warmed = false

    /** 自己动视图的功能用它计改动次数；用记账工具的功能覆写 [mutations] 即可 */
    private var selfChanges = 0

    /** 真的改动了视图就调一次 —— 首帧屏障靠它知道「这一帧不能画」 */
    protected fun noteChange() {
        selfChanges++
    }

    // ------------------------------------------------------------------ Feature

    final override fun isEnabled(cfg: ConfigSnapshot): Boolean = switchedOn(cfg)

    /**
     * 挂钩点选 `Activity.onResume`，**不**选 `onCreate`（理由见类注释第一节）。
     *
     * 注意它只负责「把监听器挂上 + 进来先同步一次」——**真正的持续生效靠布局回调**：
     * 切 Tab、翻页、宿主重建 Fragment 都不会走 `onResume`。
     *
     * 子类若还要挂别的钩子（弹层、窗口…），覆写本方法、**先调 `super.install(...)`**。
     */
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
        if (hooked == 0) GLog.e("$id：没挂上 Activity.onResume") else
            GLog.i("$id：已挂 $hooked 个 Activity.onResume")
    }

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        // 框架推送的配置变化已经在主线程分发到这里
        if (switchedOn(cfg)) return
        // 没有改动过就不出声，免得每次改配置都刷噪音
        if (!hasChanges) {
            detach()
            return
        }
        detach()
        GLog.i("$id：已关闭，界面恢复原样")
    }

    // ------------------------------------------------------------------ 调度

    private fun onPage(env: FeatureEnv, activity: Activity) {
        // 预热不分页面：第一个 resume 的页面（通常就是启动图）就把 id 名解析掉
        warmIds(activity)

        if (!pages.contains(activity.javaClass.name)) return
        if (!switchedOn(env.config)) {
            detach()
            return
        }
        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return

        attach(env, activity, root)
        // 页面可能已经布局过（从别的页面返回本页）→ 立刻试一次，不用等下一次布局
        runSync(env, activity, root, fromLayout = false)
    }

    /** 解析本功能用到的 id 名（幂等，解析结果有缓存） */
    private fun warmIds(activity: Activity) {
        val names = idNames
        if (names.isEmpty() || warmed) return
        runCatching {
            val hit = ViewIds.idsFor(activity, idPkg).warm(names)
            warmed = true
            GLog.d("$id：预热资源 id $hit/${names.size}（在 ${activity.javaClass.simpleName} 上做，省首帧的查表）")
        }.onFailure { GLog.w("$id：预热资源 id 失败（不影响功能）", it) }
    }

    /** 挂上两个回调（每个 `decorView` 只挂一次），并把本页的失配计数清零 */
    private fun attach(env: FeatureEnv, activity: Activity, root: View) {
        // 每次进入本页都重来一轮：上一次「认不出来」的账不该记到这一次
        synchronized(missScans) { missScans[root] = 0 }
        synchronized(lastScanOk) { lastScanOk.remove(root) }
        // 「启动窗口」的起点：进这一页的时刻（首帧屏障的宽档预算用它，见 onPreDraw）
        synchronized(enteredAt) { enteredAt[root] = SystemClock.uptimeMillis() }

        val ref = WeakReference(activity)

        if (!layoutWatchers.containsKey(root)) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                val a = ref.get() ?: return@OnGlobalLayoutListener
                runCatching { onLayout(env, a, root) }
                    .onFailure { GLog.w("$id：布局回调处理异常", it) }
            }
            layoutWatchers[root] = listener
            runCatching { root.viewTreeObserver.addOnGlobalLayoutListener(listener) }
                .onFailure {
                    layoutWatchers.remove(root)
                    GLog.w("$id：挂全局布局回调失败", it)
                }
        }

        if (!paintGates.containsKey(root)) {
            val gate = ViewTreeObserver.OnPreDrawListener {
                val a = ref.get() ?: return@OnPreDrawListener true
                runCatching { onPreDraw(env, a, root) }.getOrElse {
                    GLog.w("$id：首帧屏障处理异常", it)
                    true
                }
            }
            // 进入本页时以「当前状态」为已呈现 —— 页面刚要画，还没东西改过
            synchronized(mutAtLastFrame) { mutAtLastFrame[root] = mutations }
            paintGates[root] = gate
            runCatching { root.viewTreeObserver.addOnPreDrawListener(gate) }
                .onFailure {
                    paintGates.remove(root)
                    GLog.w("$id：挂首帧屏障失败", it)
                }
        }
    }

    /**
     * 布局回调：页面刚布局完（**在这一帧画出来之前**），正是改树的最佳时机。
     *
     * 两道闸（审查 2026-10-17 起是两道）：
     *
     *  - **动作冷却**：上一轮 sync 真动了视图后 [actionCooldownMs] 内直接跳过 ——
     *    滚动 / 动画期间的高频布局回调不值得每帧全量重扫；空闲轮不写冷却，
     *    宿主之后真改了视图，冷却一过下一次布局就能响应；
     *  - **认不出限速**：连续认不出来时按 [layoutThrottleMs] 限速，
     *    而且**只延后不丢弃** —— 布局回调是「页面变了」的唯一信号，
     *    直接丢掉就要白等下一次布局（宿主重建视图后可能再没有下一次布局，界面就永远停在原样）。
     */
    private fun onLayout(env: FeatureEnv, activity: Activity, root: View) {
        if (!switchedOn(env.config)) return
        if (activity.isFinishing || activity.isDestroyed) return

        val now = SystemClock.uptimeMillis()
        // 审查 2026-10-17：动作冷却 —— 只在「上一轮有实际动作」时生效（lastActionAt 才有值）
        //
        // 审查 2026-10-18：**有待收敛的下一趟时不冷却**。冷却的初衷是压掉滚动 / 动画期的
        // 高频回调，但它同时会掐死「多招状态机」——`DockCleanFeature` 的底栏重排要连跑
        // 几趟（改 weight → 量宽度 → 加宽整行），每趟都靠下一次布局回调推进；而它改完
        // 布局参数后宿主立刻重排，那次回调正好落在 1.5 秒冷却里被丢掉，底栏就停在中间态
        // （留空档 / 末格被推出屏幕）。所以由功能自己声明「还有下一趟」，冷却让路。
        val actedAt = synchronized(lastActionAt) { lastActionAt[root] ?: 0L }
        if (now - actedAt < actionCooldownMs && !needsFollowUp) {
            GLog.d("$id：${activity.javaClass.simpleName} 刚同步过且有改动，冷却期内跳过本次布局回调")
            return
        }
        val last = synchronized(lastRunAt) { lastRunAt[root] ?: 0L }
        val miss = synchronized(missScans) { missScans[root] ?: 0 }
        val missing = synchronized(lastScanOk) { lastScanOk[root] == false }
        if (missing && miss >= missScanThreshold) {
            val wait = layoutThrottleMs - (now - last)
            if (wait > 0) {
                deferSync(env, activity, root, wait)
                return
            }
        }
        runSync(env, activity, root, fromLayout = true)
    }

    /** 限速时把这次同步降级成「稍后再来」——**不丢** */
    private fun deferSync(env: FeatureEnv, activity: Activity, root: View, delayMs: Long) {
        val queued = synchronized(deferredScan) {
            if (deferredScan[root] == true) true else {
                deferredScan[root] = true
                false
            }
        }
        if (queued) return
        root.postDelayed({
            synchronized(deferredScan) { deferredScan[root] = false }
            if (!switchedOn(env.config)) return@postDelayed
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            runCatching { runSync(env, activity, root, fromLayout = true) }
                .onFailure { GLog.w("$id：延后同步失败", it) }
        }, delayMs)
    }

    /** 跑一次 [sync]，记下结果 */
    private fun runSync(env: FeatureEnv, activity: Activity, root: View, fromLayout: Boolean) {
        synchronized(lastRunAt) { lastRunAt[root] = SystemClock.uptimeMillis() }
        // 审查 2026-10-17：拿 sync 前后的改动计数差判断「这一轮有没有实际动作」——
        // 与首帧屏障判「界面被动过」是同一个信号（noteChange / 记账工具的计数）
        val mutBefore = mutations
        val done = runCatching { sync(env, activity) }.getOrElse {
            GLog.w("$id：同步失败", it)
            true
        }
        if (mutations != mutBefore) {
            synchronized(lastActionAt) { lastActionAt[root] = SystemClock.uptimeMillis() }
        }
        synchronized(lastScanOk) { lastScanOk[root] = done }
        if (done) {
            synchronized(missScans) { missScans[root] = 0 }
            return
        }
        val n = synchronized(missScans) {
            val v = (missScans[root] ?: 0) + 1
            missScans[root] = v
            v
        }
        // 报告只走布局回调这条链：它触发时页面一定在屏幕上，快照才是「用户正看着的那一页」
        if (fromLayout) {
            if (n >= missScanThreshold) maybeReport(activity, root)
        } else {
            // 入页时的那一次（页面可能还没 attach / 内容还没挂上来）—— 只记 debug，不打扰用户
            GLog.d("$id：${activity.javaClass.name} 上还没认出目标控件（第 $n 次，等布局回调）")
        }
    }

    /**
     * 首帧屏障：返回 `false` = 这一帧不画（宿主会重排一次再画）。
     *
     * 判据只有一个 —— **自上一帧以来界面被动过没有**：没动过 → 现在画出来的就是最终结果；
     * 动过 → 这一帧画的是「改到一半」的样子，拦掉。
     *
     * 拦帧有三重封顶（帧数 / 总时长 / 静默期），任何一条到顶都立刻放行，
     * 所以最坏情况只是「漏一次中间态」，不会卡住界面。
     */
    private fun onPreDraw(env: FeatureEnv, activity: Activity, root: View): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return true

        val mut = mutations
        val seen = synchronized(mutAtLastFrame) {
            val prev = mutAtLastFrame[root] ?: mut
            mutAtLastFrame[root] = mut
            prev
        }
        if (mut == seen || !switchedOn(env.config)) {
            // 这一帧可以画：连拦计数与「第一次拦帧的时刻」一起归零，
            // 否则下一个「改动的瞬间」会因为 since 是很久以前而立刻误判预算用尽
            synchronized(heldFrames) { heldFrames[root] = 0 }
            synchronized(heldSince) { heldSince.remove(root) }
            return true
        }

        val now = SystemClock.uptimeMillis()
        val muted = synchronized(holdMutedUntil) { (holdMutedUntil[root] ?: 0L) > now }
        val held = synchronized(heldFrames) { heldFrames[root] ?: 0 }
        val since = synchronized(heldSince) { heldSince[root] ?: now }

        if (muted) {
            synchronized(heldFrames) { heldFrames[root] = 0 }
            synchronized(heldSince) { heldSince.remove(root) }
            return true
        }
        // 两档预算：启动窗口内（启动图还盖着）宽一点，让「多招状态机」一次走完；
        // 窗口外用户正看着，最多拦 steadyHeldFrames 帧 —— 见各常量的注释。
        val entered = synchronized(enteredAt) { enteredAt[root] ?: now }
        val startup = now - entered <= startupHoldMs
        val frameBudget = if (startup) maxHeldFrames else steadyHeldFrames
        val timeBudget = if (startup) maxHoldWindowMs else steadyHoldWindowMs
        if (held >= frameBudget || now - since > timeBudget) {
            // 预算到头了：放行这一帧，并静默一段时间（宿主真在每帧改布局的话不能一直拦）
            synchronized(heldFrames) { heldFrames[root] = 0 }
            synchronized(heldSince) { heldSince.remove(root) }
            synchronized(holdMutedUntil) { holdMutedUntil[root] = now + holdMuteMs }
            GLog.w(
                "$id：首帧屏障连拦 $held 帧仍未稳定，先放行这一帧并静默 ${holdMuteMs}ms" +
                    "（界面已按当前状态显示；宿主可能在每帧改布局）"
            )
            return true
        }

        if (held == 0) synchronized(heldSince) { heldSince[root] = now }
        synchronized(heldFrames) { heldFrames[root] = held + 1 }
        GLog.d("$id：界面刚被改过，拦下第 ${held + 1} 帧不画（等布局稳定后一次画出来）")
        return false
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
        val ids = runCatching { ViewIds.idsFor(activity, idPkg) }.getOrNull()
        GLog.i(
            "$id：$page 视图树快照（照着往 *Targets.kt 的候选数组里加 id）\n" +
                ViewIds.dump(root, ids)
        )
    }

    /** 摘掉两个回调 + 还原现场（幂等） */
    protected fun detach() {
        val watchers = synchronized(layoutWatchers) {
            val l = layoutWatchers.entries.toList()
            layoutWatchers.clear()
            l
        }
        for ((v, l) in watchers) {
            runCatching { v.viewTreeObserver.removeOnGlobalLayoutListener(l) }
        }
        val gates = synchronized(paintGates) {
            val l = paintGates.entries.toList()
            paintGates.clear()
            l
        }
        for ((v, g) in gates) {
            runCatching { v.viewTreeObserver.removeOnPreDrawListener(g) }
        }
        synchronized(mutAtLastFrame) { mutAtLastFrame.clear() }
        synchronized(heldFrames) { heldFrames.clear() }
        synchronized(heldSince) { heldSince.clear() }
        synchronized(holdMutedUntil) { holdMutedUntil.clear() }
        synchronized(lastRunAt) { lastRunAt.clear() }
        synchronized(lastScanOk) { lastScanOk.clear() }
        synchronized(missScans) { missScans.clear() }
        synchronized(deferredScan) { deferredScan.clear() }
        synchronized(lastActionAt) { lastActionAt.clear() }
        synchronized(lastReportAt) { lastReportAt.clear() }
        synchronized(reportCounts) { reportCounts.clear() }
        synchronized(enteredAt) { enteredAt.clear() }
        restore()
    }
}
