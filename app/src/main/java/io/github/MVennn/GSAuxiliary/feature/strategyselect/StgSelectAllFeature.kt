package io.github.MVennn.GSAuxiliary.feature.strategyselect

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.stgfilter.StgFilterBridge
import io.github.MVennn.GSAuxiliary.util.Density
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * 功能：策略详情页「多选栏」里，在「取消」按钮**左侧**注入一个「全选 / 反选」按钮，
 * 一键勾选 / 取消勾选该页全部股票。**LSPosed 运行时视图注入，不改目标 App 源码。**
 *
 * ## 实测契约（详见 [StgSelectAllTargets]）
 *
 *  - 那颗按钮是 `#tv_stg_add_zxg`：正常态文案「勾选加入自选股」，进入多选态后
 *    文案变「取消」（同一颗 View，只是改字）。
 *  - 它位于 ListView `#lvStks` 的 **header** 里一个横向 `LinearLayout` 中。
 *  - 每行的勾选框是 `#ct_item`（`CheckedTextView`）；**点击整行**即切换勾选，
 *    底部「已选 N」会同步 —— 所以「全选 / 反选」= 对各行触发一次行点击即可，
 *    走的是和用户手动点行**完全同一条**代码路径，无需知道宿主的选中数据模型。
 *
 * ## 文案随状态切换（全选 / 反选）
 *
 *  - 会重算文案的时机：① 进入多选态注入时；② 底部「已选 N」计数变化时
 *    （手动点行、宿主自己改选中都会更新它）；③ 本按钮被点击后。
 *  - 判定：**行状态**里勾上的行数 == 适配器总行数 → 文案「反选」（点击取消全部），
 *    否则「全选」（点击勾选全部）。行状态读框架的 checked states（与「确认添加」同一份表），
 *    不可用时才逐行现绑探针；**不读屏上那颗勾选框**（合成点击后它可能没被重画，2026-10-21）。
 *
 * ## 点完要核对（2026-10-22）
 *
 * 单轮跑完就收工的话，任何一次「没走到」的点击都会让行永远停在「没勾」上：
 * 运行中筛选结果变了导致快照作废、`getView` 造不出探针、宿主事后把表改回去……
 * 所以一次操作现在可能跑**多轮**：每轮结束后隔几帧重读 checked states，
 * 只补点「状态仍与目标不同」的行；没有任何缺口就立刻收工（通常第 2 轮就收工）。
 * 补点轮以 checked states 为准（那是我们自己刚写过的表）；**没有筛选**时若表与宿主计数
 * 分叉，说明宿主模型漏了行，退回探针按模型补。
 *
 * ⚠️ **补点救不了「宿主监听器静默忽略」**：`performItemClick` 在调到监听器之前就把
 * `mCheckStates` 按取反写好了，下一轮读表只会看到目标态。这类漏选只能靠真机日志与
 * 宿主行为去查（见 `docs/FEATURE-SELECT-ALL.md` §4.3）。
 *
 * ## 显示刷新为什么要连行根一起刷（2026-10-22）
 *
 * `AbsListView.performItemClick` 每次都会调 `updateOnScreenCheckedViews()`，把**行根视图**的
 * `activated` 按 `mCheckStates[适配器位置]` 重写。筛选生效时我们的 checked states 用的是
 * **全量位置**（宿主模型读这份），两套坐标错位 → 每次合成点击都会把一部分可见行刷回「没勾」。
 * 宿主若用 `duplicateParentState` / `state_activated` 画勾，光设 `#ct_item.isChecked` 修不回来。
 * 所以可见行的 `activated` 与 `isChecked` 一起按「全量下标 → 表」刷（见 [refreshVisibleChecked]）。
 *
 * ⚠️ **不要包裹 ListView 的 `OnItemClickListener`**（2026-10-20 真机定位）：
 * 宿主的多选状态写在 ListView 自己的 checked states 里，而 `AbsListView.performItemClick`
 * 是在调到监听器**之前**就用传入的 `position` 写这份表的 —— 筛选层的回映射只改
 * 「转发给监听器的 position」，改不到这份表；两个 wrapper 还会互相重包，叠成双重映射。
 * 所以全选不碰监听链：状态读 checked states，合成点击则把**全量下标**直接交给
 * `performItemClick`（见 [clickNext]）。
 *
 * ## 触发方式：给「取消」按钮挂 TextWatcher（而不是全局 Hook `TextView.setText`）
 *
 * 进入 / 退出多选态都会把该按钮文案在「勾选加入自选股」↔「取消」之间切换。
 * 直接 Hook `TextView.setText` 是全 App（尤其行情页实时报价）的**超热路径**，
 * 会带来卡顿风险；所以只盯这一颗按钮挂 `TextWatcher`，零全局开销。
 *
 * ## 「样式与取消按钮保持一致」怎么保证
 *
 * 「全选」按钮由「取消」按钮**克隆**而来：背景（含状态）、文字色、字号、字体、
 * 内边距、对齐、行数、最小尺寸、LayoutParams（含 margin）逐项复制。
 * 与「取消」之间的间距**只加在注入按钮自己的 `rightMargin` 上**，不改「取消」自身任何属性。
 */
class StgSelectAllFeature : Feature {

    override val id = "stg_select_all"
    override val title = "策略详情页多选全选"

    /** 「取消」按钮资源 id 缓存（0 = 还没解析过） */
    @Volatile private var cancelIdCache = 0

    @Volatile private var listIdCache = 0

    @Volatile private var checkboxIdCache = 0

    @Volatile private var countIdCache = 0

    /**
     * 是否有一轮「全选 / 反选」还在分帧跑。
     *
     * 必须防重入：一轮 50 行 ≈ 0.8 秒，期间再点一次会起第二条链，两轮各自按旧快照
     * 点同一批行 —— 同一行被取反两次 = 没变，又会出现「部分选不中」。
     */
    @Volatile private var running = false

    /**
     * 正在跑的那一轮挂在哪个列表上（弱引用）。
     *
     * 只用于区分「同一页宿主重设了锚点文案」与「换到了新页面」：
     * 前者不能取消（会把这轮后半程掐断），后者必须复位（上一页的 `postDelayed`
     * 可能永远不会执行，`running` 会卡在 true）。
     */
    @Volatile private var runList: java.lang.ref.WeakReference<ListView>? = null

    /** 运行令牌：关开关 / 退出多选态时自增，正在跑的链看到不匹配就停 */
    private val runSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 「取消」按钮 → 我们挂的 TextWatcher。
     *
     * 弱引用是防泄漏，但**光有弱引用不够** —— watcher 的闭包持有 [FeatureEnv]，
     * 闭包持有 env → env 持有 feature 实例 → 旧实例被宿主视图钉住不能回收。
     * 所以关开关时要把 watcher **摘掉**（见 [unwatch]），而不是只让它被 GC。
     */
    /**
     * 「取消」按钮 → 我们挂的 TextWatcher。
     *
     * ⚠️ **value 必须是弱引用**（规则 1.4）：watcher 的闭包捕获了 `btn`（也就是 key）——
     * value→key 的强引用会让弱键**形同虚设**，条目永不回收，
     * 每个策略详情页的「取消」按钮都泄漏一个 TextView（连带 Activity）。
     */
    private val watchers =
        Collections.synchronizedMap(
            WeakHashMap<TextView, WeakReference<TextWatcher>>(),
        )

    /**
     * 底部「已选 N」计数控件 → 我们挂的 TextWatcher + 它负责刷新文案的那颗按钮。
     *
     * 宿主每次改选中都会同步这颗控件，所以它是**与宿主选中模型无关**的观察点：
     * 不包裹 ListView 的行点击监听，也就不会和筛选的下标回映射抢同一条链。
     * value 里存按钮（不是 key 自己），不会形成 value→key 的强引用环。
     */
    private val countWatchers =
        Collections.synchronizedMap(WeakHashMap<TextView, CountWatch>())

    /** 一次计数观察：watcher + 要刷新文案的注入按钮 */
    private class CountWatch(val watcher: TextWatcher, val button: TextView)

    /** 「取消」按钮 → 我们注入的「全选 / 反选」按钮（弱引用） */
    private val injected =
        Collections.synchronizedMap(WeakHashMap<TextView, TextView>())

    /** ListView → 当前的注入按钮（摘按钮时据此清账；弱引用） */
    private val buttonByList =
        Collections.synchronizedMap(WeakHashMap<ListView, TextView>())

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.stgSelectAll

    // ------------------------------------------------------------------ 安装

    override fun install(env: FeatureEnv, bag: HookBag) {
        watchActivities(env, bag)
    }

    /** 挂 `Activity.onResume`，只对策略详情页做「挂 watcher + 同步」这件事 */
    private fun watchActivities(env: FeatureEnv, bag: HookBag) {
        val cls = Reflect.loadClass("android.app.Activity", env.classLoader)
        if (cls == null) {
            GLog.e("全选：找不到 android.app.Activity，功能不会生效")
            return
        }
        var hooked = 0
        for (m in Reflect.methodsNamed(cls, "onResume")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("stgselectall:onResume")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val self = chain.thisObject
                            if (self is Activity) onActivityResumed(env, self)
                        }.onFailure { GLog.w("全选：onResume 处理异常", it) }
                        result
                    }
            )
            hooked++
        }
        if (hooked == 0) GLog.e("全选：没挂上 Activity.onResume") else
            GLog.i("策略全选：已挂 $hooked 个 Activity.onResume")
    }

    private fun onActivityResumed(env: FeatureEnv, activity: Activity) {
        if (!StgSelectAllTargets.isWatchActivity(activity.javaClass.name)) return

        val cancelId = cancelButtonId(activity)
        if (cancelId == 0) {
            GLog.w("全选：找不到 ${StgSelectAllTargets.CANCEL_BUTTON_ID_CANDIDATES.joinToString()}")
            return
        }
        val btn = activity.findViewById<View>(cancelId) as? TextView ?: return

        ensureWatcher(env, btn)
        // 页面可能带着多选态回来（少见），进来先同步一次，保证注入状态与当前态一致
        sync(env, btn)
    }

    /** 给「取消」按钮挂上（幂等的）TextWatcher，观察多选态切换 */
    private fun ensureWatcher(env: FeatureEnv, btn: TextView) {
        if (watchers[btn]?.get() != null) return
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runCatching { sync(env, btn) }
                    .onFailure { GLog.w("全选：同步注入状态异常", it) }
            }
        }
        btn.addTextChangedListener(watcher)
        watchers[btn] = WeakReference(watcher)
        GLog.i("策略全选：已观察多选按钮的文案变化")
    }

    /**
     * 摘掉按钮上的 watcher。
     *
     * 关开关时必须调：watcher 的闭包持有 [FeatureEnv] → 持有旧 feature 实例，
     * 不摘的话旧实例被宿主的「取消」按钮钉住，既回收不掉，
     * 也会在按钮文案变化时继续跑一遍已废弃的逻辑。
     */
    private fun unwatch(btn: TextView) {
        val w = synchronized(watchers) { watchers.remove(btn) }?.get() ?: return
        runCatching { btn.removeTextChangedListener(w) }
            .onFailure { GLog.w("全选：摘掉文案监听失败", it) }
    }

    // ------------------------------------------------------------------ 核心

    /** 根据当前是否处于多选态，注入 / 移除「全选 / 反选」按钮 */
    private fun sync(env: FeatureEnv, btn: TextView) {
        val cfg = env.config
        if (!cfg.stgSelectAll) {
            // 功能被关掉：撤掉当前这颗锚点对应的按钮
            removeInjected(btn)
            return
        }
        val text = btn.text?.toString().orEmpty()
        if (text == StgSelectAllTargets.CANCEL_TEXT) {
            // 单选 / 多选模态的列表做不了「全选」（见 toggleAll 的守卫），不注入死按钮
            val mode = findListView(btn)?.choiceMode
            if (mode != null &&
                mode != ListView.CHOICE_MODE_MULTIPLE &&
                mode != ListView.CHOICE_MODE_NONE
            ) {
                GLog.w("全选：列表不是普通多选模式（choiceMode=$mode），不注入按钮")
                removeInjected(btn)
                return
            }
            injectSelectAll(env, btn)
        } else {
            removeInjected(btn)
        }
    }

    /** 幂等地把「全选 / 反选」按钮插到「取消」左侧 */
    private fun injectSelectAll(env: FeatureEnv, anchor: TextView) {
        // 进入多选态时复位「上一页残留的一轮」，但**不能误杀同一页正在跑的一轮**：
        // 宿主可能在一轮进行中重设锚点文案（`setText` 内容相同也会触发 afterTextChanged），
        // 那会把这一轮的后半程直接掐断（症状正是「只勾了前几行」）。
        val lvNow = findListView(anchor)
        val runningLv = runList?.get()
        if (running && (runningLv == null || runningLv !== lvNow)) cancelRun()
        val existing = injected[anchor]
        if (existing != null && existing.parent === anchor.parent) {
            // 已注入：只刷新一下文案（例如从别处切回多选态）
            findListView(anchor)?.let { lv ->
                buttonByList[lv] = existing
                ensureCountWatcher(env, lv, existing)
                updateLabel(env, lv, existing)
            }
            return
        }
        if (existing != null) removeInjected(anchor)   // 视图被换了，先清旧的

        val parent = anchor.parent as? ViewGroup ?: return
        val button = runCatching { buildSelectAll(env, anchor) }.getOrNull() ?: return

        val index = parent.indexOfChild(anchor)
        runCatching {
            if (index in 0..parent.childCount) {
                parent.addView(button, index)
            } else {
                parent.addView(button)
            }
        }.onFailure {
            GLog.w("全选：插入按钮失败", it)
            return
        }
        injected[anchor] = button

        val lv = findListView(anchor)
        if (lv != null) {
            buttonByList[lv] = button
            ensureCountWatcher(env, lv, button)
            updateLabel(env, lv, button)
        }
        GLog.i("策略全选：已在多选栏注入「全选」按钮")
    }

    /**
     * 观察底部「已选 N」计数：宿主改选中时它一定会变，用它刷新「全选 / 反选」文案。
     *
     * 为什么不包裹 ListView 的行点击监听（旧做法）：宿主的多选状态是 ListView 的
     * checked states，而 `AbsListView.performItemClick` 在调到监听器之前就用传入的
     * position 写过它；筛选层还有一层「子集下标 → 全量下标」的回映射 wrapper。
     * 两层 wrapper 谁包谁无法保证，重包后会变成双重映射（真机：全选点到全量前 N 只）。
     * 计数控件是宿主自己维护的，与选中模型、点击链都解耦。
     */
    private fun ensureCountWatcher(env: FeatureEnv, lv: ListView, button: TextView) {
        val id = countId(button)
        if (id == 0) {
            GLog.w("全选：#${StgSelectAllTargets.COUNT_ID_CANDIDATES.joinToString()} 解析失败，按钮文案不会跟随手动勾选")
            return
        }
        val tv = lv.rootView?.findViewById<View>(id) as? TextView ?: return
        synchronized(countWatchers) {
            if (countWatchers[tv]?.button === button) return
            countWatchers.remove(tv)?.let {
                runCatching { tv.removeTextChangedListener(it.watcher) }
            }
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    runCatching { updateLabel(env, lv, button) }
                        .onFailure { GLog.w("全选：刷新按钮文案异常", it) }
                }
            }
            tv.addTextChangedListener(watcher)
            countWatchers[tv] = CountWatch(watcher, button)
        }
    }

    /** 摘掉某颗按钮对应的计数 watcher（退出多选态 / 关开关 / 热重载时撤干净） */
    private fun removeCountWatcher(button: TextView) {
        synchronized(countWatchers) {
            val it = countWatchers.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.value.button !== button) continue
                runCatching { e.key.removeTextChangedListener(e.value.watcher) }
                    .onFailure { GLog.w("全选：摘掉计数监听失败", it) }
                it.remove()
            }
        }
    }

    /** 从「取消」按钮克隆出一颗样式一致、但**独立 id** 的「全选 / 反选」按钮 */
    private fun buildSelectAll(env: FeatureEnv, src: TextView): TextView {
        val btn = TextView(src.context)
        btn.text = StgSelectAllTargets.SELECT_ALL_TEXT
        copyStyle(src, btn)
        runCatching { btn.layoutParams = buildLayoutParams(src) }
        // 独立 id：避免与原「取消」按钮共用 NO_ID/同 id 造成 findViewById 歧义，
        // 也便于我们准确定位自己注入的这颗按钮
        runCatching { btn.id = View.generateViewId() }
        // minWidth =「全选」「反选」里较宽者所需宽度（文字宽 + 左右内边距），
        // 保证文案切换不抖动、也不会把「取消」挤出。
        // 用 paint 量文字而**不是**取 src.width —— 后者在「勾选加入自选股 → 取消」
        // 文案切换途中会拿到旧（5 字、更宽）文案的宽度，导致按钮被撑得过宽。
        runCatching {
            val paint = src.paint
            val textPx = maxOf(
                paint.measureText(StgSelectAllTargets.SELECT_ALL_TEXT),
                paint.measureText(StgSelectAllTargets.INVERT_TEXT),
            ).toInt()
            btn.minWidth = src.paddingLeft + src.paddingRight + textPx
        }

        btn.isClickable = true
        btn.isFocusable = true

        btn.setOnClickListener { v ->
            runCatching { toggleAll(env, v) }
                .onFailure { GLog.w("全选：勾选/取消全部股票异常", it) }
        }
        return btn
    }

    private fun removeInjected(anchor: TextView) {
        cancelRun()
        val btn = injected.remove(anchor) ?: return
        runCatching { (btn.parent as? ViewGroup)?.removeView(btn) }
        removeCountWatcher(btn)
        val lv = buttonByList.entries.firstOrNull { it.value === btn }?.key
        if (lv != null) buttonByList.remove(lv)
    }

    /** 某一次「读现场」的结果：适配器 / 总行数 / 映射 / 每一行的当前状态 */
    private class Shot(
        val adapter: android.widget.ListAdapter,
        val headerCount: Int,
        val total: Int,
        /** 子集下标 → 全量下标；**null = 没有筛选**（恒等映射） */
        val full: IntArray?,
        val before: BooleanArray,
        val hostCount: Int,
    ) {
        val checked: Int get() = before.count { it }
    }

    /**
     * 一次「全选 / 反选」的上下文。
     *
     * 与旧实现的差别：一次操作可能跑**多轮**（每轮重新快照、只点还不对的行）。
     * [load] 会把这一轮的现场整体换掉，所以运行中适配器 / 筛选结果 / 行数变了都只需要
     * 重开一轮，不用中止整次操作。
     */
    private class ToggleRun(
        val lv: ListView,
        val button: TextView?,
        val ctId: Int,
        val wantChecked: Boolean,
        val token: Int,
    ) {
        var adapter: android.widget.ListAdapter? = null
        var headerCount = 0
        var total = 0
        var full: IntArray? = null

        /** 本轮要点的行（子集下标，保序）—— 已符合目标状态的行不在里面 */
        var click: IntArray = IntArray(0)
        var cursor = 0

        /** 已经开始的轮数（1 = 首轮；[load] 里自增） */
        var round = 0

        /**
         * **真正点过行**的轮数。
         *
         * 与 [round] 分开计：适配器 / 筛选映射变化导致的「重开一轮」可能一行都没点，
         * 不能消耗 [MAX_ROUNDS] 的点击预算，否则宿主刷新几次就报「已达补点上限」。
         */
        var clickRounds = 0

        /** 本轮开始时宿主底部的「已选 N」（读不到 = -1），只用于日志 */
        var hostCount = -1

        /** 本轮 / 累计的点击失败次数（异常或没被宿主接住），只用于日志与显示刷新的保守判断 */
        var failedThisRound = 0
        var failedTotal = 0

        var finished = false

        fun load(shot: Shot, plan: IntArray) {
            adapter = shot.adapter
            headerCount = shot.headerCount
            total = shot.total
            full = shot.full
            click = plan
            cursor = 0
            hostCount = shot.hostCount
            failedThisRound = 0
            round++
        }
    }

    /**
     * 「全选 / 反选」的实际动作：
     *  - 未全选 → 让每只都勾上（勾选全部）；
     *  - 已全选 → 让每只都取消（取消全部）。
     *
     * 走宿主自己的 `OnItemClickListener`（`performItemClick`）—— 与用户手动点行完全同一条
     * 路径，宿主的选中模型与底部「已选 N」都会自然同步。
     *
     * **只点「状态与目标不同」的行**（2026-10-21 真机）。行点击是**取反**的：对已经勾上的行
     * 再点一次就是取消勾选。旧实现拿屏上那颗勾选框当判据，而合成点击后宿主不会重画**部分**
     * 可见勾选框（§4.1 的已知观感局限）→ 已勾行看起来没勾 → 下一轮又点一次 → 正好把它点掉，
     * 用户看到的就是「做过一次全选后，下次全选部分选不中」。
     *
     * 状态来源优先级（见 [StgSelectAllPlan.stateSource]）：
     *  1. 宿主计数为 0 → 一行都没选中，不必逐行问；
     *  2. 框架的 checked states（与「确认添加」读的是同一份表）→ O(行数)、不建视图；
     *  3. 只有它不可用 / 与宿主计数矛盾时才逐行现绑探针（`Adapter.getView(i, null, lv)`）。
     *
     * **必须覆盖屏外行**：屏外行没有已绑定的子 View，探针与合成点击都用现造的 View
     * （宿主用 `position` 定位，不依赖 View 是否在屏上）。
     *
     * **必须分帧**：宿主每次切换勾选后会刷新列表（`notifyDataSetChanged`），同一帧里继续拿
     * 旧的子 View 会拿到已失效的视图（真机实测「反选」只清一行的根因）。
     *
     * **点完还要核对**（2026-10-22）：一轮跑完后隔几帧重读 checked states，有缺口就补点
     * （见 [nextRound]）。宿主偶尔吞掉一次合成点击、或行情刷新让筛选结果变了，
     * 单轮收工都会留下「没勾到」的行。
     */
    private fun toggleAll(env: FeatureEnv, v: View) {
        if (running) {
            GLog.i("策略全选：上一轮还没跑完，忽略本次点击")
            return
        }
        // 先占住防重入标记：读状态本身可能耗时（探针档要建视图），
        // 这期间再点一次就会起第二条链。
        running = true
        var started = false
        try {
            val lv = findListView(v)
            if (lv == null) {
                GLog.w("全选：找不到 ${StgSelectAllTargets.LIST_ID_CANDIDATES.joinToString()} 列表")
                return
            }
            // 从这一刻起就算「这一轮挂在 lv 上」—— 读状态阶段也可能触发宿主重设文案，
            // 提前记下来，`injectSelectAll` 的作用域判断才不会把本轮误杀。
            runList = java.lang.ref.WeakReference(lv)
            val ctId = checkboxId(v)
            if (ctId == 0) {
                // 勾选框 id 解析不到就无法判断每行状态，盲目点击会把「全选」点成「反选」→ 保守放弃
                GLog.w("全选：#${StgSelectAllTargets.CHECKBOX_ID_CANDIDATES.joinToString()} 解析失败，放弃本次操作")
                return
            }
            val shot = takeShot(lv, ctId, retry = false) ?: return
            val wantChecked = StgSelectAllPlan.wantChecked(shot.before)
            val click = StgSelectAllPlan.rowsToClick(shot.before, wantChecked)
            val button = v as? TextView

            GLog.i(
                "策略全选：${if (wantChecked) "全选" else "反选"} 开始" +
                    "（共 ${shot.total} 只，已勾 ${shot.checked}，待点 ${click.size}）",
            )
            if (click.isEmpty()) {
                // 理论上不可达：wantChecked 由 before 决定（还有没勾上的行 → true），
                // 于是 before 全 true 时要点全部取消、全 false 时要点全部勾上，
                // click 在 total > 0 时永远非空。保留这一支只为兜住「未来改了判据」。
                updateLabel(env, lv, button ?: return)
                return
            }
            started = true
            val run = ToggleRun(lv, button, ctId, wantChecked, runSeq.incrementAndGet())
            run.load(shot, click)
            run.clickRounds = 1
            clickNext(env, run)
        } finally {
            // 任何早退 / 异常都不能把 running 留在 true（否则按钮从此失效）
            if (!started) {
                running = false
                runList = null
            }
        }
    }

    /**
     * 读一次现场：适配器、总行数、子集→全量映射、每一行的当前状态。
     *
     * 列表不是普通多选模式 / 映射拿不到时返回 null（并记原因）—— 这两种情况下
     * 盲目点击会把「全选」点成「反选」，或者点到别的股票上。
     *
     * @param retry 是不是补点轮：补点轮以 checked states 为准（首轮的保守判据见
     *        [StgSelectAllPlan.stateSource]）。
     */
    private fun takeShot(lv: ListView, ctId: Int, retry: Boolean): Shot? {
        val adapter = unwrapHeaderAdapter(lv.adapter)
        if (adapter == null) {
            GLog.w("全选：列表还没有适配器，放弃本次操作")
            return null
        }
        val total = adapter.count
        if (total <= 0) {
            GLog.w("全选：列表是空的（adapter=${adapter.javaClass.simpleName}），放弃本次操作")
            return null
        }
        if (lv.choiceMode == ListView.CHOICE_MODE_SINGLE ||
            lv.choiceMode == ListView.CHOICE_MODE_MULTIPLE_MODAL
        ) {
            // 单选：performItemClick 会清掉别的行；多选模态：action mode 活跃时
            // 框架根本不把点击派给宿主的 OnItemClickListener —— 两种都没法做「全选」
            GLog.w("全选：列表不是普通多选模式（choiceMode=${lv.choiceMode}），放弃本次操作")
            return null
        }

        // 子集 → 全量的映射在每一轮开始时快照一次：运行中筛选结果变了就重开一轮，
        // 绝不按旧下标点到别的股票上。
        val isProxy = isFilterProxy(adapter)
        val full = fullIndices(adapter, total)
        if (isProxy && full == null) {
            // 列表上是筛选代理，但桥上读不到下标表 —— 拿不到映射，点了就会点到别的股票
            GLog.w("全选：列表挂着筛选代理但读不到下标表，放弃本次操作")
            return null
        }
        val mapping = full ?: IntArray(total) { it }
        val hostCount = selectedCount(lv)
        val before = readRowStates(lv, adapter, ctId, total, hostCount, mapping, full != null, retry)
        return Shot(adapter, lv.headerViewsCount, total, full, before, hostCount)
    }

    /**
     * 列表上挂的是不是「筛选股票」的过滤代理。
     *
     * 两条腿（规则 1.2.1）：① 类名（由 proguard `-keepnames` 保住，跨热重载实例可用）；
     * ② 桥里的 owner 身份 —— 只要快照是**这个代理**发布的，就算类名被打穿也认得出来。
     */
    private fun isFilterProxy(a: Any?): Boolean =
        a != null && ("FilteredAdapter" in a.javaClass.name || StgFilterBridge.isFor(a))

    /**
     * 当前生效的「子集下标 → 全量下标」表；没有筛选时返回 null。
     *
     * 必须是**列表上这个代理**发布的快照，且长度与列表行数一致 —— 离开筛选页后
     * 快照可能还留在全局单例里，长度/归属对不上就返回 null（调用方会放弃本次全选），
     * 绝不拿上一页的下标表往这一页上套。
     */
    private fun fullIndices(adapter: Any?, total: Int): IntArray? {
        if (!isFilterProxy(adapter)) return null
        return StgFilterBridge.fullIndices(total, adapter)
    }

    /**
     * 每一行**点击前**的勾选态。
     *
     * 优先读框架的 checked states（宿主确认添加读的就是它，且 O(行数) 不建视图）；
     * 不可用或与宿主计数矛盾时逐行现绑探针。**不读屏上那颗勾选框** —— 它可能没被重画。
     *
     * @param retry 补点轮：以 checked states 为准（首轮的宿主计数在补点轮里可能滞后，
     *        拿「计数为 0」跳过逐行读会把已勾上的行再点一遍 → 反而全部取消）
     */
    private fun readRowStates(
        lv: ListView,
        adapter: android.widget.ListAdapter,
        ctId: Int,
        total: Int,
        hostCount: Int,
        full: IntArray,
        filtered: Boolean,
        retry: Boolean,
    ): BooleanArray {
        val table = tableRowStates(lv, total, full)
        val tableChecked = table?.count { it }
        return when (StgSelectAllPlan.stateSource(hostCount, tableChecked, filtered, retry)) {
            // 宿主计数是「全量选中数」：为 0 就表示没有任何行被选中
            StgSelectAllPlan.StateSource.NONE_CHECKED -> BooleanArray(total)
            StgSelectAllPlan.StateSource.TABLE -> table ?: BooleanArray(total)
            StgSelectAllPlan.StateSource.PROBE -> {
                if (table != null && !retry) {
                    GLog.w("全选：checked states（$tableChecked）与宿主计数（$hostCount）不一致，改用探针")
                }
                probeRowStates(lv, adapter, ctId, total, full)
            }
        }
    }

    /** 从框架的 checked states 读（全量坐标）；宿主不用 ListView 多选模式时返回 null */
    private fun tableRowStates(lv: ListView, total: Int, full: IntArray): BooleanArray? {
        if (lv.choiceMode == ListView.CHOICE_MODE_NONE) return null
        val header = lv.headerViewsCount
        val out = BooleanArray(total)
        for (i in 0 until total) out[i] = lv.isItemChecked(header + full[i])
        return out
    }

    /**
     * 逐行现绑探针视图读勾选态（`getView` 会按宿主模型绑定 `#ct_item`）。
     *
     * 只在 checked states 不可用 / 与宿主计数矛盾时走这条路 —— 每行都要新建一棵视图，
     * 长列表（200 只）是几百毫秒级的开销。
     */
    private fun probeRowStates(
        lv: ListView,
        adapter: android.widget.ListAdapter,
        ctId: Int,
        total: Int,
        full: IntArray,
    ): BooleanArray {
        val header = lv.headerViewsCount
        val out = BooleanArray(total)
        var missing = 0
        for (i in 0 until total) {
            val probe = runCatching { adapter.getView(i, null, lv) }.getOrNull()
            val ct = probe?.findViewById<View>(ctId) as? CheckedTextView
            if (ct == null) {
                missing++
                out[i] = lv.isItemChecked(header + full[i])
            } else {
                out[i] = ct.isChecked
            }
        }
        if (missing > 0) {
            GLog.w("全选：$missing 行拿不到探针勾选态，退回 checked states")
        }
        return out
    }

    /** 点 [run].click 里的下一行（含屏外），然后 `postDelayed` 到下一帧 */
    private fun clickNext(env: FeatureEnv, run: ToggleRun) {
        val lv = run.lv
        // 被取消（关开关 / 退出多选态 / 又点了一次）→ 立刻停
        if (!running || run.token != runSeq.get()) return
        if (run.cursor >= run.click.size) {
            // 本轮点完：先给宿主几帧把手上的 notifyDataSetChanged 落定，再重新读一遍
            // checked states —— 漏掉的行进下一轮，全部到位就收工。
            GLog.i(
                "策略全选：第 ${run.round} 轮结束（点了 ${run.cursor} 行，失败 ${run.failedThisRound}" +
                    "，宿主计数 ${run.hostCount} → ${selectedCount(lv)}）",
            )
            scheduleNextRound(env, run, ROUND_SETTLE_MS)
            return
        }
        // 页面被销毁后 `postDelayed` 的 runnable 可能永远不跑（running 会卡在 true），
        // 这里再兜一道：列表已不在窗口上就收尾。
        if (!lv.isAttachedToWindow) {
            finishRun(env, run, "列表已脱离窗口")
            return
        }
        val adapter = run.adapter
        if (adapter == null) {
            finishRun(env, run, "适配器丢了")
            return
        }
        // 运行期间宿主换了适配器 → **中止**（不重开）。换适配器可能意味着换了策略 / 换了一页，
        // 继续按原来的 wantChecked 点下去会把「全选」施加到另一批股票上。
        // 筛选结果变化不换适配器（代理就地 update），那一条走下面的下标校验重开一轮。
        if (unwrapHeaderAdapter(lv.adapter) !== adapter) {
            finishRun(env, run, "适配器在运行中换了（可能是别的页面），中止")
            return
        }
        // header 数变了（宿主加/删表头）→ 所有位置整体偏移，本轮快照作废：重开一轮
        if (lv.headerViewsCount != run.headerCount) {
            GLog.i("策略全选：header 数在运行中变了（${run.headerCount} → ${lv.headerViewsCount}），重新快照")
            scheduleNextRound(env, run, 0)
            return
        }
        val index = run.click[run.cursor]
        val full = run.full
        if (full != null) {
            // 归属/长度由桥校验：读不到（快照被换页覆盖）也按「变了」处理
            val now = StgFilterBridge.fullIndexAt(index, adapter)
            if (now == null || now != full[index]) {
                GLog.i("策略全选：筛选下标在运行中变了，重新快照（已完成 ${run.cursor}/${run.click.size}）")
                scheduleNextRound(env, run, 0)
                return
            }
        }

        // ⚠️ 「视图位置」与「数据位置」在筛选生效时是两套坐标（2026-10-20 真机）：
        // ListView 上挂的是过滤代理，`performItemClick` 的 position 是**子集下标**；
        // 而宿主的多选模型从 ListView 的 checked states 取值，框架又是用**传入的
        // position** 写这份表。所以合成点击必须直接把**全量下标**传进去：
        // 框架写对位置、宿主收到对位置，回映射层用 [StgFilterBridge.runWithRemapBypassed]
        // 原样放行（否则它会把全量下标再当子集下标映射一次）。
        val pos = run.headerCount + (full?.get(index) ?: index)   // 传给宿主的**全量**位置
        val proxyPos = run.headerCount + index                     // 列表里那一行的实际位置（子集）
        val childIdx = proxyPos - lv.firstVisiblePosition
        val visible = childIdx in 0 until lv.childCount
        // 可见行传真实子 View（宿主的点击处理会**就地**把这一行的勾选框打上）；
        // 屏外行现造探针 View（宿主用 position 定位，不看 View 是否在屏上）。
        val row: View? = if (visible) {
            lv.getChildAt(childIdx)
        } else {
            runCatching { adapter.getView(index, null, lv) }.getOrNull()
        }
        if (row == null) {
            noteFailure(run, "第 $index 行拿不到视图，跳过（下一轮再试）")
        } else {
            clickRow(lv, run, adapter, index, pos, row)
        }

        if (full != null) {
            // 每次合成点击都会把可见行的 `activated` 按**适配器位置**重写一遍，
            // 而筛选期间 checked states 用的是全量位置 —— 两套坐标错位，不补这一下
            // 屏幕会一部分勾、一部分没勾（见 [refreshVisibleChecked]）。
            refreshVisibleChecked(run)
        }

        run.cursor++
        // ⚠️ `View.postDelayed` 在视图脱窗时**不会**返回 false（它把 runnable 排进
        // RunQueue，等重新 attach 再执行），所以必须自己先判一次：脱窗就收尾，
        // 否则 `running` 会一直挂着，重新挂载后还会接着点。
        if (!lv.isAttachedToWindow) {
            finishRun(env, run, "列表已脱离窗口")
            return
        }
        val posted = lv.postDelayed(
            { runCatching { clickNext(env, run) } },
            TOGGLE_STEP_MS,
        )
        if (!posted) finishRun(env, run, "列表已脱离窗口")
    }

    /**
     * 记一次行点击失败：只在本轮**第一次**失败时打日志，其余只计数。
     *
     * 200 行 × 4 轮如果每行一条 WARN（异常还带堆栈）会把日志刷爆（规则 3.3 的去重口径）；
     * 轮末那条「点了 N 行，失败 M」已经能看出规模。
     */
    private fun noteFailure(run: ToggleRun, what: String, t: Throwable? = null) {
        run.failedThisRound++
        run.failedTotal++
        if (run.failedThisRound != 1) return
        if (t != null) {
            GLog.w("全选：$what（本轮后续同类失败只计数）", t)
        } else {
            GLog.w("全选：$what（本轮后续同类失败只计数）")
        }
    }

    /**
     * 对一行发一次合成点击。
     *
     * ⚠️ **不要用 `performItemClick` 的返回值判断「宿主有没有接住」**：普通多选模式下
     * `AbsListView.performItemClick` 在调到宿主监听器**之前**就已经把 `mCheckStates`
     * 取反写好、并且无条件 `handled = true`（框架源码 AbsListView.java:1221-1276）。
     * 也就是说「宿主监听器静默忽略」这件事从外部看不出来；下一轮读表时表里已经是目标态，
     * 补点不会重试它。补点能救的是「这一行根本没点到」或「宿主事后把表改了」，
     * 救不了「宿主自己不更新模型」—— 那只能靠真机日志 + 宿主自己的行为去查。
     *
     * 预置（`setItemChecked`）失败时不动表（它没写成功）；点击抛异常时**不回滚**：
     * 表已经被框架按取反写过了，回滚会把「宿主可能已经更新的模型」与表拆开，
     * 下一轮再点一次反而变成反向操作。
     */
    private fun clickRow(
        lv: ListView,
        run: ToggleRun,
        adapter: android.widget.ListAdapter,
        index: Int,
        pos: Int,
        row: View,
    ) {
        // id 用**过滤代理**的 `getItemId(index)`：它内部会换成全量条目的 id；
        // 而 `lv.getItemIdAtPosition(全量位置)` 在全量下标小于子集行数时会被
        // 代理再映射一次，拿到的是别的股票的 id。
        val id = runCatching { adapter.getItemId(index) }.getOrDefault(-1L)

        // `performItemClick` 是按 position **取反**写 checked states 的。先把这一格
        // 摆成「点击前」的值，取反后才一定落到目标（表里可能留着上一轮的残值）。
        // 用 `setItemChecked` 而不是直接改 mCheckStates：它会一并维护
        // mCheckedItemCount（宿主用它渲染「已选 N」）。筛选期间代理已关掉 stable id，
        // 所以没有 id 表要维护；放行标记仍带上，见下面。
        val prev = if (lv.choiceMode == ListView.CHOICE_MODE_MULTIPLE) {
            lv.isItemChecked(pos)
        } else {
            null
        }
        if (prev != null) {
            try {
                // `pos` 是全量坐标；筛选期间代理已关掉 stable id
                // （见 FilteredAdapter.hasStableIds），但放行标记仍要带上 ——
                // 若宿主自己覆写了 id 逻辑，不能让它再映射一次。
                StgFilterBridge.runWithRemapBypassed { lv.setItemChecked(pos, !run.wantChecked) }
            } catch (t: Throwable) {
                noteFailure(run, "第 $index 行预置勾选态失败，跳过（下一轮再试）", t)
                return
            }
        }
        try {
            var handled = false
            StgFilterBridge.runWithRemapBypassed {
                handled = lv.performItemClick(row, pos, id)
            }
            if (!handled) {
                // 普通多选模式下不可达（框架无条件 handled=true）；单选/模态已被守卫挡掉。
                // 保留这一支只为兜住框架行为变化，不做回滚（原因见函数注释）。
                noteFailure(run, "第 $index 行没有被宿主接住（performItemClick=false）")
            }
        } catch (t: Throwable) {
            noteFailure(run, "触发行点击失败 index=$index（下一轮按表核对）", t)
        }
    }

    /** 把「下一轮」排到稍后；列表已脱离窗口就直接收尾 */
    private fun scheduleNextRound(env: FeatureEnv, run: ToggleRun, delayMs: Long) {
        // 同 clickNext：`postDelayed` 在脱窗时会把 runnable 排进 RunQueue（返回 true），
        // 必须自己先判一次，否则重新挂载后会接着跑。
        if (!run.lv.isAttachedToWindow) {
            finishRun(env, run, "列表已脱离窗口")
            return
        }
        val posted = run.lv.postDelayed({ runCatching { nextRound(env, run) } }, delayMs)
        if (!posted) finishRun(env, run, "列表已脱离窗口")
    }

    /**
     * 本轮收尾后重读 checked states：还有行没到目标就再补一轮（上限 [MAX_ROUNDS] 轮）。
     *
     * 为什么值得多跑这一趟：宿主偶尔会吞掉某次合成点击（真机「点了全选，行数比计数少几只」），
     * 行情刷新也可能让筛选结果在运行中变化。每轮只点「状态仍与目标不同」的行，
     * 所以补点不会把已经勾上的行点掉；没有任何缺口时第 2 轮就会直接收工。
     * 没有 checked states（`CHOICE_MODE_NONE`）时无从核对，不做补点。
     */
    private fun nextRound(env: FeatureEnv, run: ToggleRun) {
        if (!running || run.token != runSeq.get()) return
        val lv = run.lv
        if (!lv.isAttachedToWindow) {
            finishRun(env, run, "列表已脱离窗口")
            return
        }
        if (lv.choiceMode == ListView.CHOICE_MODE_NONE) {
            // 没有 checked states：唯一能读的是探针，而探针与点击链对不上时会把
            // 已勾上的行反复点掉 —— 宁可不补。
            finishRun(env, run, null)
            return
        }
        // 注意：header 数变了不用在这里单独处理 —— takeShot 会按**当前** header 读状态，
        // run.load 也会把 headerCount 一起换掉。
        val shot = takeShot(lv, run.ctId, retry = true)
        if (shot == null) {
            finishRun(env, run, "重新读状态失败")
            return
        }
        val plan = StgSelectAllPlan.rowsToClick(shot.before, run.wantChecked)
        // 即使这一轮没有要补的行，也把现场换成本轮读到的 —— 收尾的显示刷新要按
        // **当前**适配器 / 筛选映射取值（运行中它们可能已经换过）。
        run.load(shot, plan)
        if (plan.isEmpty()) {
            finishRun(env, run, null)
            return
        }
        // 上限只卡「真正要再点一轮」的轮次；上面那次核对永远会做，
        // 所以不会出现「状态其实已经对了，却被上限挡掉、还记一条 WARN」。
        // 重开轮（适配器/筛选/header 变化）不点行，也就不消耗这个预算。
        if (run.clickRounds >= MAX_ROUNDS) {
            finishRun(
                env,
                run,
                "已达补点上限（已点 ${run.clickRounds} 轮，仍有 ${plan.size} 行没到位）",
            )
            return
        }
        run.clickRounds++
        GLog.i(
            "策略全选：第 ${run.round} 轮补点（共 ${run.total} 只，已勾 ${shot.checked}，" +
                "待补 ${plan.size}，点击轮 ${run.clickRounds}/$MAX_ROUNDS）",
        )
        clickNext(env, run)
    }

    /** 一轮正常结束 / 自行中止：清标记与列表引用（不动令牌 —— 这一轮已经自己停了） */
    private fun endRun() {
        running = false
        runList = null
    }

    /** 取消正在跑的一轮（关开关 / 退出多选态 / 热重载） */
    private fun cancelRun() {
        endRun()
        runSeq.incrementAndGet()
    }

    /**
     * 收尾：清标记、刷屏上可见行的勾选显示、刷新按钮文案。
     *
     * [reason] 非空表示中途放弃（列表脱窗 / 适配器没了 / 补点上限），只记日志，不算失败。
     */
    private fun finishRun(env: FeatureEnv, run: ToggleRun, reason: String?) {
        if (run.finished) return
        run.finished = true
        endRun()
        if (reason == null) {
            GLog.i(
                "策略全选：完成（${run.round} 轮 / 点击 ${run.clickRounds} 轮，共 ${run.total} 只，目标 " +
                    "${if (run.wantChecked) "全勾" else "全不勾"}" +
                    (if (run.failedTotal > 0) "，点击失败 ${run.failedTotal} 次" else "") + "）",
            )
        } else {
            GLog.w(
                "策略全选：中止（$reason；已开始 ${run.round} 轮 / 点击 ${run.clickRounds} 轮）",
            )
        }
        refreshVisibleChecked(run)
        refreshVisibleCheckedAfterLayout(run)
        run.button?.let { updateLabel(env, run.lv, it) }
    }

    /**
     * 收尾后再补一次显示刷新，落在**下一帧的 pre-draw**（布局之后、绘制之前）。
     *
     * 宿主的 `notifyDataSetChanged()` → 布局可能比我们收尾那一下晚（`ROUND_SETTLE_MS`
     * 是启发式，不保证布局已经跑完）；`ListView.setupChild()` 会在布局里按
     * `mCheckStates[适配器位置]` 重写行根 `activated`，把收尾刷的值盖掉。
     * pre-draw 是「这一帧布局已经完成」的确定性时点，刷完立刻绘制。
     *
     * 一次性监听：触发即摘（规则 1.1）。脱窗时 listener 会随 ViewTreeObserver 一起被回收。
     */
    private fun refreshVisibleCheckedAfterLayout(run: ToggleRun) {
        val lv = run.lv
        if (!lv.isAttachedToWindow) return
        val vto = lv.viewTreeObserver
        if (!vto.isAlive) return
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val now = lv.viewTreeObserver
                if (now.isAlive) now.removeOnPreDrawListener(this)
                if (run.token == runSeq.get()) refreshVisibleChecked(run)
                return true
            }
        }
        runCatching { vto.addOnPreDrawListener(listener) }
            .onFailure { GLog.w("全选：挂显示刷新失败", it) }
    }

    /**
     * 把**屏上可见**行的勾选显示刷成「checked states 里的真实状态」。
     *
     * 只改显示：不写 checked states、不碰宿主模型，所以「确认添加」收到的股票不受影响。
     *
     * 为什么要连行根的 `activated` 一起刷（2026-10-22）：
     * `AbsListView.performItemClick` 每次都会调 `updateOnScreenCheckedViews()`，把**行根视图**
     * 的 `activated` 按 `mCheckStates[适配器位置]` 重写。筛选生效时我们的 checked states 用的是
     * **全量位置**（宿主模型读这份表），两套坐标错位 → 每次合成点击都会把一部分可见行刷回
     * 「没勾」。宿主若用 `duplicateParentState` / `state_activated` 画勾，只设
     * `#ct_item.isChecked` 修不回来 —— 这正是「确实全选了，但中间有些股票不显示勾」的来源。
     * 所以这里按「行 → 全量下标 → 表」取真实状态，`activated` 与 `isChecked` 一起写 ——
     * 与框架自己给「已勾行」的语义完全一致。
     *
     * 没有任何 checked states 可读（`CHOICE_MODE_NONE`）时，只在一次失败都没有时按目标刷，
     * 免得画一个宿主模型里并不存在的勾。
     */
    private fun refreshVisibleChecked(run: ToggleRun) {
        val lv = run.lv
        if (!lv.isAttachedToWindow) return
        val adapter = run.adapter ?: return
        // 适配器换了：这一轮的映射/行数都已失效，不拿旧现场往新列表上画
        if (unwrapHeaderAdapter(lv.adapter) !== adapter) return
        val total = run.total
        if (total <= 0) return
        // 行数变了：本轮的映射/下标表整体作废，不拿旧现场往新列表上画
        if (adapter.count != total) return
        // header 数变了：所有位置整体偏移，本轮的映射作废
        if (lv.headerViewsCount != run.headerCount) return
        val full = run.full
        val header = run.headerCount
        val first = lv.firstVisiblePosition
        // 筛选下标表可能已经换过（行情刷新会重算筛选）：逐行核对，对不上就整趟不刷
        if (full != null) {
            for (i in 0 until lv.childCount) {
                val index = first + i - header
                if (index !in 0 until total) continue
                val now = StgFilterBridge.fullIndexAt(index, adapter)
                if (now == null || now != full[index]) return
            }
        }
        val multiple = lv.choiceMode == ListView.CHOICE_MODE_MULTIPLE ||
            lv.choiceMode == ListView.CHOICE_MODE_MULTIPLE_MODAL
        for (i in 0 until lv.childCount) {
            val index = first + i - header
            if (index !in 0 until total) continue
            val row = lv.getChildAt(i) ?: continue
            val checked = when {
                multiple -> {
                    val key = full?.get(index) ?: index
                    // ⚠️ 歧义行不画：手动点行写的是**适配器位置**（子集下标），
                    // 全选写的是**全量位置**；当 full[index] 恰好也是另一个子集行的下标时，
                    // 表里那个 key 可能是「那一行被手动勾上」而不是「这一行被全选勾上」。
                    // 读它会把别的行的选择画到这一行上 —— 宁可不画，交给宿主自己渲染。
                    if (full != null && key != index && key in 0 until total) continue
                    lv.isItemChecked(header + key)
                }
                // 没有 checked states 可读（CHOICE_MODE_NONE）：只在一次失败都没有时
                // 按目标刷 —— 否则屏幕会显示一个宿主模型里并不存在的勾
                run.failedTotal == 0 -> run.wantChecked
                else -> continue
            }
            runCatching {
                (row.findViewById<View>(run.ctId) as? CheckedTextView)?.isChecked = checked
                if (multiple) row.isActivated = checked
            }
        }
    }

    /**
     * 按当前选中状态刷新按钮文案：已全选 → 「反选」，否则 → 「全选」。
     *
     * 判据与 [toggleAll] 对齐（同一套 `stateSource` 优先级，不看屏上勾选框）：
     * 有 checked states 就用它（O(行数)、不建视图）；只有探针档为了文案不逐行建视图，
     * 退回宿主计数。两边判据不一致会出现「文案写着全选、点下去却在取消」
     * （筛选后宿主计数含被筛掉的行时）。
     */
    private fun updateLabel(env: FeatureEnv, lv: ListView, button: TextView) {
        val realAdapter = unwrapHeaderAdapter(lv.adapter) ?: return
        val total = realAdapter.count
        val filtered = fullIndices(realAdapter, total)
        val full = filtered ?: IntArray(total) { it }
        val hostCount = selectedCount(button)
        val tableChecked = if (total > 0) tableRowStates(lv, total, full)?.count { it } else null
        val source = StgSelectAllPlan.stateSource(hostCount, tableChecked, filtered != null)
        val allChecked = when {
            source == StgSelectAllPlan.StateSource.TABLE -> tableChecked == total
            // 计数读不到（-1）时别拿它比：`-1 == total` 恒 false，文案会永远写「全选」，
            // 而动作侧（PROBE）可能判成「反选」。表可用就退回表。
            hostCount < 0 && tableChecked != null -> tableChecked == total
            else -> hostCount == total
        }
        val label = if (total > 0 && allChecked) {
            StgSelectAllTargets.INVERT_TEXT
        } else {
            StgSelectAllTargets.SELECT_ALL_TEXT
        }
        runCatching {
            if (button.text?.toString() != label) button.text = label
        }
    }

    /** 读底部「已选 N」的计数（宿主维护，含屏外行）；读不到返回 -1 */
    private fun selectedCount(v: View): Int {
        val id = countId(v)
        if (id == 0) return -1
        val root = v.rootView ?: return -1
        val tv = root.findViewById<View>(id) as? TextView ?: return -1
        return runCatching { tv.text?.toString()?.trim()?.toIntOrNull() ?: -1 }.getOrDefault(-1)
    }

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        // 关掉功能时，把所有已注入的按钮撤掉
        if (cfg.stgSelectAll) return
        // 正在跑的一轮立刻停（否则按钮撤了它还会继续点行）
        cancelRun()
        val anchors = synchronized(injected) { injected.keys.toList() }
        for (a in anchors) removeInjected(a)
        // ⚠️ 按钮撤了还不够：watcher 也得摘（规则 1.1：install 落下的每一笔都要能撤掉）。
        // 原先只摘按钮 → 「取消」按钮**永久**挂着一个 watcher（闭包持有 env → 钉住旧实例）。
        val watched = synchronized(watchers) { watchers.keys.toList() }
        for (b in watched) unwatch(b)
        // 防御：正常路径上 removeInjected 已经逐颗摘过了；这里兜一遍，
        // 防止「按钮被宿主替换 / 已不在 injected 表里」留下的孤儿 watcher。
        val counts = synchronized(countWatchers) { countWatchers.entries.toList() }
        for ((tv, w) in counts) {
            runCatching { tv.removeTextChangedListener(w.watcher) }
        }
        synchronized(countWatchers) { countWatchers.clear() }
    }

    /**
     * 页面销毁：释放这一页的注入记账（**不还原宿主视图**）。
     *
     * `injected` / `buttonByList` 的值是 TextView（Context 就是 Activity），
     * `countWatchers` 的 watcher 闭包又持有 ListView —— 三条链都能反向走到弱表的 key，
     * 于是 WeakHashMap 的弱键等于失效。正常退出多选态时 `removeInjected` 会清掉，
     * 只有「多选态还开着就离开页面」会留下；这里按页面身份兜底删掉。
     */
    override fun onActivityDestroyed(activity: Activity) {
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return

        // 正在跑的一轮若挂在这一页的列表上，先停；它的 postDelayed 可能再也不执行
        val runningLv = runList?.get()
        if (runningLv != null && belongsToPage(runningLv, activity, decor)) cancelRun()

        // 注入按钮：只删账，不 removeView（Activity 马上带着视图一起死）。
        // 不走 removeInjected —— 它会无条件 cancelRun()，可能误杀另一页在途的一轮。
        val buttons = synchronized(injected) {
            val anchors = injected.keys.filter { belongsToPage(it, activity, decor) }
            anchors.mapNotNull { injected.remove(it) }
        }
        for (btn in buttons) {
            removeCountWatcher(btn)
            val lv = synchronized(buttonByList) {
                buttonByList.entries.firstOrNull { it.value === btn }?.key
            }
            if (lv != null) synchronized(buttonByList) { buttonByList.remove(lv) }
        }

        // 兜底：按钮被宿主替换 / 已不在 injected 表里的孤儿计数 watcher 与列表记账
        val counts = synchronized(countWatchers) {
            countWatchers.entries.filter { belongsToPage(it.key, activity, decor) }
        }
        for ((tv, w) in counts) {
            runCatching { tv.removeTextChangedListener(w.watcher) }
            synchronized(countWatchers) { countWatchers.remove(tv) }
        }
        val lists = synchronized(buttonByList) {
            buttonByList.keys.filter { belongsToPage(it, activity, decor) }
        }
        for (lv in lists) synchronized(buttonByList) { buttonByList.remove(lv) }
    }

    /** 这个视图是不是属于 [activity] 这一页（Context 或根视图身份，避免误伤别的页面） */
    private fun belongsToPage(v: View, activity: Activity, decor: View): Boolean =
        runCatching { v.context === activity || v.rootView === decor }.getOrDefault(false)

    // ------------------------------------------------------------ 样式克隆

    /** 把源按钮的可见样式逐项复制到目标按钮上，确保「样式一致」 */
    private fun copyStyle(src: TextView, dst: TextView) {
        runCatching {
            dst.setBackground(src.background?.constantState?.newDrawable()?.mutate())
        }
        runCatching { dst.backgroundTintList = src.backgroundTintList }
        runCatching { dst.setTextColor(src.textColors) }
        runCatching { src.typeface?.let { dst.typeface = it } }
        runCatching { dst.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.textSize) }
        runCatching { dst.textScaleX = src.textScaleX }
        runCatching { dst.letterSpacing = src.letterSpacing }
        runCatching {
            dst.setPadding(src.paddingLeft, src.paddingTop, src.paddingRight, src.paddingBottom)
        }
        runCatching {
            val d = src.compoundDrawables
            dst.setCompoundDrawables(d.getOrNull(0), d.getOrNull(1), d.getOrNull(2), d.getOrNull(3))
            dst.compoundDrawablePadding = src.compoundDrawablePadding
        }
        runCatching { dst.gravity = src.gravity }
        runCatching { dst.includeFontPadding = src.includeFontPadding }
        runCatching { dst.ellipsize = src.ellipsize }
        runCatching { dst.maxLines = src.maxLines }
        runCatching { dst.maxWidth = src.maxWidth }
        runCatching { dst.minWidth = src.minWidth }
        runCatching { dst.minHeight = src.minHeight }
        runCatching { dst.foreground = src.foreground?.constantState?.newDrawable()?.mutate() }
        runCatching { dst.setAllCaps(false) }
    }

    /**
     * 复制「取消」按钮的 LayoutParams（含 margin / weight / gravity），
     * 并**只在复制品自己的 rightMargin 上**加上与「取消」的间距 ——
     * 不改「取消」任何属性，保证「与取消样式一致」这条验收依然成立。
     */
    private fun buildLayoutParams(src: View): ViewGroup.LayoutParams {
        val lp = src.layoutParams
        val copy: ViewGroup.LayoutParams = when (lp) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp)
            is ViewGroup.LayoutParams -> ViewGroup.LayoutParams(lp)
            else -> ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        if (copy is ViewGroup.MarginLayoutParams) {
            copy.rightMargin = Density.dp(src, GAP_DP)
        }
        return copy
    }

    // ------------------------------------------------------------ 反射与查找

    /** 取出真正的业务适配器（ListView 带 header 时外面套了一层 HeaderViewListAdapter） */
    private fun unwrapHeaderAdapter(a: Any?): android.widget.ListAdapter? {
        if (a == null) return null
        if (a.javaClass.name == "android.widget.HeaderViewListAdapter") {
            return runCatching {
                a.javaClass.getMethod("getWrappedAdapter").invoke(a)
            }.getOrNull() as? android.widget.ListAdapter
        }
        return a as? android.widget.ListAdapter
    }

    private fun findListView(v: View): ListView? {
        val root = v.rootView ?: return null
        val id = listViewId(v)
        if (id == 0) return null
        return root.findViewById<View>(id) as? ListView
    }

    private fun cancelButtonId(activity: Activity): Int {
        var id = cancelIdCache
        if (id != 0) return id
        for (name in StgSelectAllTargets.CANCEL_BUTTON_ID_CANDIDATES) {
            val r = resolveId(activity.resources, name)
            if (r != 0) {
                id = r
                break
            }
        }
        if (id != 0) cancelIdCache = id
        return id
    }

    private fun listViewId(v: View): Int {
        var id = listIdCache
        if (id != 0) return id
        for (name in StgSelectAllTargets.LIST_ID_CANDIDATES) {
            val r = resolveId(v.resources, name)
            if (r != 0) {
                id = r
                break
            }
        }
        if (id != 0) listIdCache = id
        return id
    }

    private fun checkboxId(v: View): Int {
        var id = checkboxIdCache
        if (id != 0) return id
        for (name in StgSelectAllTargets.CHECKBOX_ID_CANDIDATES) {
            val r = resolveId(v.resources, name)
            if (r != 0) {
                id = r
                break
            }
        }
        if (id != 0) checkboxIdCache = id
        return id
    }

    private fun countId(v: View): Int {
        var id = countIdCache
        if (id != 0) return id
        for (name in StgSelectAllTargets.COUNT_ID_CANDIDATES) {
            val r = resolveId(v.resources, name)
            if (r != 0) {
                id = r
                break
            }
        }
        if (id != 0) countIdCache = id
        return id
    }

    private fun resolveId(res: android.content.res.Resources, name: String): Int =
        runCatching { res.getIdentifier(name, "id", StgSelectAllTargets.TARGET_PKG) }
            .getOrDefault(0)

    private companion object {
        /** 注入按钮与「取消」按钮之间的间距（dp） */
        const val GAP_DP = 16f

        /**
         * 逐行触发勾选时，每一行之间间隔的毫秒数。
         * 给宿主列表一帧时间完成刷新，避免同一帧里拿到失效的子 View。
         */
        const val TOGGLE_STEP_MS = 16L

        /**
         * 一轮点完后隔多久重新读 checked states。
         *
         * 宿主的行点击监听通常会 `notifyDataSetChanged()`，布局要一两帧才落定；
         * 读得太早会读到「还没写回」的中间态。48ms ≈ 3 帧，既够宿主落定，
         * 又短到用户感觉不出停顿。
         */
        const val ROUND_SETTLE_MS = 48L

        /**
         * 一次「全选 / 反选」最多**点几轮**（不含最后那次只核对的读）。
         *
         * 补点轮只点「状态仍与目标不同」的行，正常情况下第 2 轮就全部到位、直接收工；
         * 上限防的是两种病态：宿主连续吞点击、筛选结果一直在变 —— 不能让按钮永远占着
         * `running`。到上限时记一条 WARN（带剩余行数），交给用户自己再点一次。
         */
        const val MAX_ROUNDS = 4
    }
}
