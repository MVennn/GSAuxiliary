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
 * ⚠️ **不要包裹 ListView 的 `OnItemClickListener`**（2026-10-20 真机定位）：
 * 宿主的多选状态写在 ListView 自己的 checked states 里，而 `AbsListView.performItemClick`
 * 是在调到监听器**之前**就用传入的 `position` 写这份表的 —— 筛选层的回映射只改
 * 「转发给监听器的 position」，改不到这份表；两个 wrapper 还会互相重包，叠成双重映射。
 * 所以全选不碰监听链：状态读 checked states，合成点击则把**全量下标**直接交给
 * `performItemClick`（见 [stepToggle]）。
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

    /** 一次「全选 / 反选」的上下文（长参数表收进对象，顺便记录有没有行点击失败） */
    private class ToggleRun(
        val lv: ListView,
        val adapter: android.widget.ListAdapter,
        val button: TextView?,
        val ctId: Int,
        val headerCount: Int,
        val total: Int,
        /** 子集下标 → 全量下标的快照（运行中若变了就中止，免得点到别的股票） */
        val full: IntArray,
        /** 需要点击的行（子集下标，保序）—— 已符合目标状态的行不在里面 */
        val click: IntArray,
        val wantChecked: Boolean,
        val token: Int,
        /** 启动时是否有筛选映射（运行中用它判断快照是否还有效） */
        val filtered: Boolean,
    ) {
        var failed = false
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
     * 状态来源优先级：
     *  1. 宿主计数为 0 → 一行都没选中，不必逐行问；
     *  2. 框架的 checked states（与「确认添加」读的是同一份表）→ O(行数)、不建视图；
     *  3. 只有它不可用 / 与宿主计数矛盾时才逐行现绑探针（`Adapter.getView(i, null, lv)`）。
     *
     * **必须覆盖屏外行**：屏外行没有已绑定的子 View，探针与合成点击都用现造的 View
     * （宿主用 `position` 定位，不依赖 View 是否在屏上）。
     *
     * **必须分帧**：宿主每次切换勾选后会刷新列表（`notifyDataSetChanged`），同一帧里继续拿
     * 旧的子 View 会拿到已失效的视图（真机实测「反选」只清一行的根因）。
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
            val realAdapter = unwrapHeaderAdapter(lv.adapter) ?: return
            val ctId = checkboxId(v)
            if (ctId == 0) {
                // 勾选框 id 解析不到就无法判断每行状态，盲目点击会把「全选」点成「反选」→ 保守放弃
                GLog.w("全选：#${StgSelectAllTargets.CHECKBOX_ID_CANDIDATES.joinToString()} 解析失败，放弃本次操作")
                return
            }
            val total = realAdapter.count
            if (total <= 0) return
            if (lv.choiceMode == ListView.CHOICE_MODE_SINGLE ||
                lv.choiceMode == ListView.CHOICE_MODE_MULTIPLE_MODAL
            ) {
                // 单选：performItemClick 会清掉别的行；多选模态：action mode 活跃时
                // 框架根本不把点击派给宿主的 OnItemClickListener —— 两种都没法做「全选」
                GLog.w("全选：列表不是普通多选模式（choiceMode=${lv.choiceMode}），放弃本次操作")
                return
            }

            // 子集 → 全量的映射在**启动时**快照一次：运行中筛选结果变了就中止，
            // 免得后半程按旧下标点到别的股票上。
            val isProxy = isFilterProxy(realAdapter)
            val full = fullIndices(realAdapter, total)
            if (isProxy && full == null) {
                // 列表上是筛选代理，但桥上读不到下标表 —— 拿不到映射，点了就会点到别的股票
                GLog.w("全选：列表挂着筛选代理但读不到下标表，放弃本次操作")
                return
            }
            val mapping = full ?: IntArray(total) { it }
            val before =
                readRowStates(lv, realAdapter, ctId, total, selectedCount(v), mapping, full != null)
            val wantChecked = StgSelectAllPlan.wantChecked(before)
            val click = StgSelectAllPlan.rowsToClick(before, wantChecked)
            val button = v as? TextView

            GLog.i(
                "策略全选：${if (wantChecked) "全选" else "反选"} 开始" +
                    "（共 $total 只，已勾 ${before.count { it }}，待点 ${click.size}）",
            )
            if (click.isEmpty()) {
                updateLabel(env, lv, button ?: return)
                return
            }
            started = true
            stepToggle(
                env,
                ToggleRun(
                    lv, realAdapter, button, ctId, lv.headerViewsCount, total, mapping, click,
                    wantChecked, runSeq.incrementAndGet(), filtered = full != null,
                ),
                0,
            )
        } finally {
            // 任何早退 / 异常都不能把 running 留在 true（否则按钮从此失效）
            if (!started) {
                running = false
                runList = null
            }
        }
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
     */
    private fun readRowStates(
        lv: ListView,
        adapter: android.widget.ListAdapter,
        ctId: Int,
        total: Int,
        hostCount: Int,
        full: IntArray,
        filtered: Boolean,
    ): BooleanArray {
        val table = tableRowStates(lv, total, full)
        val tableChecked = table?.count { it }
        return when (StgSelectAllPlan.stateSource(hostCount, tableChecked, filtered)) {
            // 宿主计数是「全量选中数」：为 0 就表示没有任何行被选中
            StgSelectAllPlan.StateSource.NONE_CHECKED -> BooleanArray(total)
            StgSelectAllPlan.StateSource.TABLE -> table ?: BooleanArray(total)
            StgSelectAllPlan.StateSource.PROBE -> {
                if (table != null) {
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

    /** 处理 [run].click 里的第 [cursor] 行（含屏外），然后 `postDelayed` 到下一帧 */
    private fun stepToggle(env: FeatureEnv, run: ToggleRun, cursor: Int) {
        val lv = run.lv
        // 被取消（关开关 / 退出多选态 / 又点了一次）→ 立刻停
        if (!running || run.token != runSeq.get()) return
        if (cursor >= run.click.size) {
            endRun()
            if (!run.failed) repaintVisible(run)
            run.button?.let { updateLabel(env, lv, it) }
            return
        }

        val index = run.click[cursor]
        // 页面被销毁后 `postDelayed` 的 runnable 可能永远不跑（running 会卡在 true），
        // 这里再兜一道：列表已不在窗口上就收尾。
        if (!lv.isAttachedToWindow) {
            endRun()
            GLog.w("全选：列表已脱离窗口，中止（已完成 $cursor/${run.click.size}）")
            return
        }
        // 运行期间宿主换了适配器 / 筛选结果变了 → 快照下标已指向别的股票，中止
        if (unwrapHeaderAdapter(lv.adapter) !== run.adapter) {
            endRun()
            run.failed = true
            GLog.w("全选：列表适配器在运行中变了，中止（已完成 $cursor/${run.click.size}）")
            return
        }
        if (run.filtered) {
            // 归属/长度由桥校验：读不到（快照被换页覆盖）也按「变了」处理
            val now = StgFilterBridge.fullIndexAt(index, run.adapter)
            if (now == null || now != run.full[index]) {
                endRun()
                run.failed = true
                GLog.w("全选：筛选下标在运行中变了，中止（已完成 $cursor/${run.click.size}）")
                return
            }
        }

        runCatching {
            // ⚠️ 「视图位置」与「数据位置」在筛选生效时是两套坐标（2026-10-20 真机）：
            // ListView 上挂的是过滤代理，`performItemClick` 的 position 是**子集下标**；
            // 而宿主的多选模型从 ListView 的 checked states 取值，框架又是用**传入的
            // position** 写这份表的。所以合成点击必须直接把**全量下标**传进去：
            // 框架写对位置、宿主收到对位置，回映射层用 [StgFilterBridge.runWithRemapBypassed]
            // 原样放行（否则它会把全量下标再当子集下标映射一次）。
            val pos = run.headerCount + run.full[index]   // 传给宿主的**全量**位置
            val proxyPos = run.headerCount + index        // 列表里那一行的实际位置（子集）
            val childIdx = proxyPos - lv.firstVisiblePosition
            val visible = childIdx in 0 until lv.childCount
            // 可见行传真实子 View（宿主的点击处理会**就地**把这一行的勾选框打上）；
            // 屏外行现造探针 View（宿主用 position 定位，不看 View 是否在屏上）。
            val row: View? = if (visible) {
                lv.getChildAt(childIdx)
            } else {
                runCatching { run.adapter.getView(index, null, lv) }.getOrNull()
            }
            // id 用**过滤代理**的 `getItemId(index)`：它内部会换成全量条目的 id；
            // 而 `lv.getItemIdAtPosition(全量位置)` 在全量下标小于子集行数时会被
            // 代理再映射一次，拿到的是别的股票的 id。
            val id = run.adapter.getItemId(index)

            // `performItemClick` 是按 position **取反**写 checked states 的。先把这一格
            // 摆成「点击前」的值，取反后才一定落到目标（表里可能留着上一轮的残值）。
            // 用 `setItemChecked` 而不是直接改 mCheckStates：它会一并维护
            // mCheckedItemCount（宿主用它渲染「已选 N」）。筛选期间代理已关掉 stable id，
            // 所以没有 id 表要维护；放行标记仍带上，见下面。
            // 预置与点击放在同一个 try 里：预置自己抛异常也要能回滚。
            val prev = if (lv.choiceMode == ListView.CHOICE_MODE_MULTIPLE) {
                lv.isItemChecked(pos)
            } else {
                null
            }
            try {
                // `pos` 是全量坐标；筛选期间代理已关掉 stable id（见 FilteredAdapter.hasStableIds），
                // 但放行标记仍要带上 —— 若宿主自己覆写了 id 逻辑，不能让它再映射一次。
                if (prev != null) {
                    StgFilterBridge.runWithRemapBypassed { lv.setItemChecked(pos, !run.wantChecked) }
                }
                var handled = false
                StgFilterBridge.runWithRemapBypassed {
                    handled = lv.performItemClick(row, pos, id)
                }
                if (!handled) {
                    run.failed = true
                    GLog.w("全选：第 $index 行没有被宿主接住（performItemClick=false）")
                }
            } catch (t: Throwable) {
                // 失败回滚：不能把 checked states 留在与目标相反的状态
                if (prev != null) {
                    runCatching {
                        StgFilterBridge.runWithRemapBypassed { lv.setItemChecked(pos, prev) }
                    }
                }
                throw t
            }
        }.onFailure {
            run.failed = true
            GLog.w("全选：触发行点击失败 index=$index", it)
        }

        val posted = lv.postDelayed(
            { runCatching { stepToggle(env, run, cursor + 1) } },
            TOGGLE_STEP_MS,
        )
        if (!posted) {
            // 列表已脱离窗口：后续帧永远不会跑，必须把防重入标记清掉，
            // 否则按钮会从此静默失效
            endRun()
            GLog.w("全选：列表已脱离窗口，中止（已完成 ${cursor + 1}/${run.click.size}）")
        }
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
     * 全部点完后，把**屏上可见**的勾选框刷成最终状态。
     *
     * 只改显示：不写 checked states、不碰宿主模型，所以「确认添加」收到的股票不受影响。
     * 为什么需要：合成点击走 `performItemClick`，框架的 `updateOnScreenCheckedViews()`
     * 只处理行根视图（`Checkable`），而勾选框在行内部；宿主自己对部分行也没重画 ——
     * 于是出现「计数与确认页都对、屏幕上部分没勾」的观感问题（§4.1）。
     * 有任何一行点击失败就**不刷**（不画一个假的勾）。
     */
    private fun repaintVisible(run: ToggleRun) {
        val lv = run.lv
        val first = lv.firstVisiblePosition
        for (i in 0 until lv.childCount) {
            val index = first + i - run.headerCount
            if (index !in 0 until run.total) continue
            val ct = lv.getChildAt(i)?.findViewById<View>(run.ctId) as? CheckedTextView ?: continue
            runCatching { ct.isChecked = run.wantChecked }
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
    }
}
