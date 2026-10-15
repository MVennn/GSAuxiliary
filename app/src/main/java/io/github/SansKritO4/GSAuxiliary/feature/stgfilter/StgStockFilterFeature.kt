package io.github.SansKritO4.GSAuxiliary.feature.stgfilter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.core.HookBag
import io.github.SansKritO4.GSAuxiliary.feature.Feature
import io.github.SansKritO4.GSAuxiliary.feature.FeatureEnv
import io.github.SansKritO4.GSAuxiliary.feature.watchlist.ZxWatchlist
import io.github.SansKritO4.GSAuxiliary.util.Reflect
import java.util.Collections
import java.util.WeakHashMap

/**
 * 功能：**策略详情页「筛选股票」**。
 *
 * ## 需求
 *
 * 1. 在右上角三点菜单（「修改名称」/「删除策略」）里、**「修改名称」下方**新增一项
 *    `筛选股票`；
 * 2. 点击弹出筛选页，页内提供 **涨幅 / 跌幅 / 自选** 三个可切换开关；
 * 3. 筛选状态**本地持久化**，关掉软件重开仍在；
 * 4. 筛选页**复用目标 App 自带的筛选组件 UI**（行情页「沪深京A」左侧漏斗那个），
 *    原「取消」按钮改成「重置」，「确定」不变。
 *
 * ## 三条链路的实现方式
 *
 * ### ① 菜单注入：挂 `PopupWindow` 的展示方法（**在 `proceed()` 之前**改内容）
 *
 * 三点菜单是 App 自己 `new` 的一个 `PopupWindow`（实测内容布局 `res/layout/pop_ai_stg.xml`
 * 里就两个 `TextView`：`#tv_edit_name` / `#tv_del_stg`）。它是**系统类**、App 里到处都在弹，
 * 所以判定条件是「内容视图里找得到 `#tv_del_stg`」。
 *
 * 时序很关键：**必须在原 `show*` 执行之前**把新项加进去 —— 一旦 `show` 过了，
 * `PopupWindow` 已按 2 项量过尺寸，这时再加第三项会被裁掉。
 * 所以这里 `.intercept { 先改内容; chain.proceed() }`，不是常见的 after 钩子。
 *
 * ### ② 筛选页：直接 inflate App 自己的布局（见 [StgFilterPanel]）
 *
 * ### ③ 行过滤：**换掉适配器的数据**（真正移除，不是藏起来）
 *
 * 早期版本是在 `getView` 之后把不匹配的行 `visibility = GONE` + 压成 1px。
 * **这条路走不通**，两个原因都实测过：
 *
 *  - 只设 `GONE` 不行：`ListView.layoutChildren` 直接对子 View 调
 *    `setupChild → child.measure()`（不走 `ViewGroup.measureChildren` 那套「跳过 GONE」的逻辑），
 *    被藏的行**照样占着 138px**，界面上一整块空白；
 *  - 压高度也只是治标：`lp.height = 0` 会掉进 `setupChild` 的
 *    `if (lpHeight > 0) EXACTLY else UNSPECIFIED` 的 **else 分支**、被当成"量自然高度"；
 *    用 1px 能压住，但宿主一刷新列表（重新 `setData` + `notifyDataSetChanged`），
 *    已经布局好的行不会被重新量，空白/漏筛就都冒出来了。
 *
 * 现在改成**直接换 `BaseQuickAdapter.data` 字段的内容**（实测字段名就叫 `data`，
 * 且 `getItem(int)` 就是从它取），再走宿主自己的 `notifyDataSetChanged()`：
 * ListView 眼里列表本来就"只有这些行"，**不存在占位空间**，翻页/滚动都是原生行为。
 *
 * 时序与幂等都做了处理（见 [applyFilter]）：
 *  - 挂 `android.widget.BaseAdapter.notifyDataSetChanged` 的**前置**钩子 ——
 *    宿主每次通知"数据变了"，我们**先把数据换成筛过的**，再让原方法继续，
 *    这样第一帧就是筛过的结果，没有闪烁；
 *  - 自己装进去的那个列表对象记在 [AdapterState.filtered] 里，靠 `===` 区分
 *    「宿主给的全量」与「我们装的子集」，避免反复包装；
 *  - 关掉功能 / 清空条件 → 把全量还回 `data` 字段并通知一次。
 *
 * ### 状态持久化
 *
 * 见 [StgFilterStore]：存**目标 App 自己的** `SharedPreferences`
 * （remote preferences 在目标进程是只读的，写不进去）。
 *
 * ## 三个开关的语义（**互斥类别**）
 *
 * 需求明确：**未勾选的类别、其对应股票一律不显示**。
 * 所以先把每只股票归到**唯一**一个类别，再看该类别的开关有没有勾：
 *
 * ```
 * 类别(股票) =
 *     自选   在自选股里（**最高优先**）
 *     涨幅   否则 zdf > 0
 *     跌幅   否则 zdf < 0
 *     平盘   其余（zdf == 0 或读不到）
 *
 * 显示(股票) = 没有任何开关勾选 ? true : 类别(股票) ∈ 已勾选的类别
 * ```
 *
 * 举例（都是需求里的例子与推论）：
 *  - 只勾「涨幅」→ 涨的要有、跌的不要、**自选里即使涨着也不要**（自选是另一个类别）；
 *  - 只勾「自选」→ 只有自选里的；
 *  - 勾「涨幅 + 自选」→ 涨的（非自选）+ 自选里的；
 *  - 三个都不勾 → 不筛（全显示）。
 *
 * ⚠️ **平盘股（zdf == 0）不属于任何类别**，所以只要开了筛选就看不到它 ——
 * 这是"未勾选的类别不显示"的直接推论（没有"平盘"这个开关）。
 */
class StgStockFilterFeature : Feature {

    override val id = "stg_stock_filter"
    override val title = "策略详情筛选股票"

    /** 缓存资源 id（0 = 还没解析过） */
    @Volatile private var menuAnchorIdCache = 0
    @Volatile private var menuEditNameIdCache = 0
    @Volatile private var percentIdCache = 0

    /** 筛选状态缓存：避免每次通知都读一遍 SharedPreferences */
    @Volatile private var cachedFilters: StgFilters? = null

    /** 当前停在策略详情页的 Activity */
    @Volatile private var trackedActivity: Activity? = null

    /** 三点菜单窗口被我们改过的原始高度，撤销时还原 */
    private val popupOriginalHeight = Collections.synchronizedMap(WeakHashMap<PopupWindow, Int>())

    /** 已经包过行点击回映射的 ListView → 我们装的 wrapper（用来避免重复包裹） */
    private val wrappedLists = Collections.synchronizedMap(
        WeakHashMap<ListView, AdapterView.OnItemClickListener>()
    )

    /** 策略详情页 ListView 的资源 id 缓存 */
    @Volatile private var listIdCache = 0

    /** 每个适配器的「全量 / 已装填子集」状态 */
    private val states = Collections.synchronizedMap(WeakHashMap<Any, AdapterState>())

    /**
     * 「代码.市场 → 涨跌幅」的兜底表。
     *
     * 只在数据项上的 `zdf` 读不到时才用 —— 行情推送是**原地刷行内文案**的，
     * 未必会写回数据项字段，所以顺手把行内 `#tv_item_stk_percent` 的文案记一份。
     */
    private val percentByCam = Collections.synchronizedMap(WeakHashMap<Any, MutableMap<String, Double>>())

    /** 上一次打印过的筛选结果，用来抑制重复日志 */
    @Volatile private var lastSummary: String? = null

    private class AdapterState {
        /** 宿主持有的全量列表 */
        var full: List<Any>? = null

        /** 我们装进 `data` 字段的那个子集（用来和"宿主给的全量"区分开） */
        var filtered: List<Any>? = null

        /** 子集每一项在 [full] 里的下标 —— 点击回映射要用 */
        var keptIndices: IntArray? = null
    }

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.stgStockFilter

    // ------------------------------------------------------------------ 安装

    override fun install(env: FeatureEnv, bag: HookBag) {
        // 「自选」判定复用与「自选高亮」完全相同的国信门面方法
        ZxWatchlist.init(env.classLoader)

        var hooked = 0
        hooked += hookPopupShow(env, bag)
        hooked += hookNotifyDataSetChanged(env, bag)
        hooked += hookGetView(env, bag)
        hooked += hookActivityResume(env, bag)

        if (hooked == 0) {
            GLog.e("筛选股票：没有任何钩子装上（宿主类名可能已变，用 tools/probe 重新定位）")
        }
    }

    /**
     * 挂 `PopupWindow.showAsDropDown / showAtLocation` 的**全部重载**。
     *
     * ⚠️ 这里是**前置**改写（先改内容再 `proceed()`）—— 理由见类注释 ①。
     */
    private fun hookPopupShow(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass("android.widget.PopupWindow", env.classLoader)
        if (cls == null) {
            GLog.e("筛选股票：找不到 android.widget.PopupWindow")
            return 0
        }
        var n = 0
        for (name in POPUP_SHOW_METHODS) {
            for (m in Reflect.methodsNamed(cls, name)) {
                bag.add(
                    env.xposed.hook(m)
                        .setId("stgfilter:pop:$name:${m.parameterTypes.size}")
                        .intercept { chain ->
                            runCatching {
                                val self = chain.thisObject
                                if (self is PopupWindow) maybeInjectMenuItem(env, self)
                            }.onFailure { GLog.w("筛选股票：菜单注入异常", it) }
                            chain.proceed()
                        }
                )
                n++
            }
        }
        GLog.i("筛选股票：已挂三点菜单展示点 ×$n")
        return n
    }

    /**
     * 挂 `android.widget.BaseAdapter.notifyDataSetChanged`。
     *
     * `notifyDataSetChanged` 声明在**框架类 `BaseAdapter`** 上（`BaseQuickAdapter` 没覆写），
     * 所以只能挂框架类；用「调用者是不是目标适配器」来过滤。
     *
     * 前置改写：宿主说「数据变了」时，我们**先把 `data` 换成筛过的**再放行，
     * 这样接下来的布局第一帧就是筛过的结果，不会先闪一下全量。
     */
    private fun hookNotifyDataSetChanged(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass("android.widget.BaseAdapter", env.classLoader) ?: return 0
        val methods = Reflect.methodsNamed(cls, "notifyDataSetChanged")
        for (m in methods) {
            bag.add(
                env.xposed.hook(m)
                    .setId("stgfilter:notify")
                    .intercept { chain ->
                        runCatching {
                            val self = chain.thisObject
                            if (self != null && StgStockFilterTargets.isTargetAdapter(self.javaClass.name)) {
                                applyFilter(env, self, notify = false)
                            }
                        }.onFailure { GLog.w("筛选股票：notifyDataSetChanged 前置处理异常", it) }
                        chain.proceed()
                    }
            )
        }
        if (methods.isNotEmpty()) GLog.i("筛选股票：已挂 BaseAdapter.notifyDataSetChanged ×${methods.size}")
        return methods.size
    }

    /**
     * 挂列表行的 `getView`。
     *
     * 这里**不再改行视图**，只做两件事：
     *  1. 顺手把行内涨跌幅文案记进 [percentByCam]（数据项上 `zdf` 读不到时的兜底）；
     *  2. 兜底补一次筛选 —— 万一宿主只 `setAdapter` 不发通知，
     *     或者列表在筛选条件变化后被重建，也能在下一次绑定时把数据换过来（延后一帧，避免
     *     在布局过程中改数据）。
     */
    private fun hookGetView(env: FeatureEnv, bag: HookBag): Int {
        var n = 0
        for (clsName in listOf(StgStockFilterTargets.CLS_BASE_QUICK_ADAPTER) +
            StgStockFilterTargets.TARGET_ADAPTERS) {
            val cls = Reflect.loadClass(clsName, env.classLoader) ?: continue
            val methods = Reflect.methodsNamed(cls, "getView")
            for (m in methods) {
                bag.add(
                    env.xposed.hook(m)
                        .setId("stgfilter:getView:${cls.simpleName}:${m.parameterTypes.size}")
                        .intercept { chain ->
                            val result = chain.proceed()
                            runCatching { onGetView(env, chain.thisObject, chain.args, result) }
                                .onFailure { GLog.w("筛选股票：getView 后处理异常", it) }
                            result
                        }
                )
                n++
            }
            if (methods.isNotEmpty()) GLog.i("筛选股票：已挂 ${cls.simpleName}.getView ×${methods.size}")
        }
        return n
    }

    private fun hookActivityResume(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass("android.app.Activity", env.classLoader) ?: return 0
        val methods = Reflect.methodsNamed(cls, "onResume")
        for (m in methods) {
            bag.add(
                env.xposed.hook(m)
                    .setId("stgfilter:onResume")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val self = chain.thisObject
                            if (self is Activity) onActivityResumed(env, self)
                        }.onFailure { GLog.w("筛选股票：onResume 处理异常", it) }
                        result
                    }
            )
        }
        return methods.size
    }

    // ------------------------------------------------------------------ 菜单注入

    private fun maybeInjectMenuItem(env: FeatureEnv, pop: PopupWindow) {
        val content = runCatching { pop.contentView }.getOrNull() as? ViewGroup ?: return
        val res = content.resources

        if (menuAnchorIdCache == 0) {
            menuAnchorIdCache = res.viewIdOrZero(StgStockFilterTargets.MENU_ANCHOR_ID)
        }
        val anchorId = menuAnchorIdCache
        if (anchorId == 0) return

        // 只有「删除策略」在里面的才是策略详情页的三点菜单；别的 PopupWindow 一律放过
        val anchor = runCatching { content.findViewById<View>(anchorId) }.getOrNull() ?: return
        val parent = anchor.parent as? ViewGroup ?: return

        if (!env.config.stgStockFilter) {
            removeInjected(pop, parent)
            return
        }
        if (parent.findViewWithTag<View>(TAG_ITEM) != null) return   // 幂等

        val anchorText = anchor as? TextView ?: return

        if (menuEditNameIdCache == 0) {
            menuEditNameIdCache = res.viewIdOrZero(StgStockFilterTargets.MENU_EDIT_NAME_ID)
        }
        val editName = menuEditNameIdCache.takeIf { it != 0 }
            ?.let { runCatching { content.findViewById<View>(it) }.getOrNull() }

        val activity = findActivity(content.context)
        if (activity == null) {
            GLog.w("筛选股票：三点菜单的 Context 里找不到 Activity，放弃注入")
            return
        }

        // 分隔线：原菜单里唯一的 ImageView，克隆一个（含背景色）
        val dividerSrc = (0 until parent.childCount)
            .map { parent.getChildAt(it) }
            .firstOrNull { it is ImageView }
        val divider = dividerSrc?.let { src ->
            runCatching {
                ImageView(src.context).apply {
                    background = src.background?.constantState?.newDrawable()?.mutate()
                    layoutParams = cloneParams(src)
                }
            }.getOrNull()
        }

        val item = TextView(parent.context).apply {
            text = MENU_ITEM_TEXT
            cloneTextStyle(anchorText, this)
            layoutParams = cloneParams(anchorText)
            id = View.generateViewId()
            tag = TAG_ITEM
            isClickable = true
            isFocusable = true
            setOnClickListener { v ->
                runCatching {
                    pop.dismiss()
                    if (!env.config.stgStockFilter) return@runCatching
                    val act = findActivity(v.context) ?: activity
                    StgFilterPanel.show(act, filtersBy(act)) { applied ->
                        onFilterApplied(env, act, applied)
                    }
                }.onFailure { GLog.w("筛选股票：打开筛选页失败", it) }
            }
        }

        // 插在「修改名称」**下方**
        val at = if (editName != null) parent.indexOfChild(editName) + 1 else parent.childCount
        runCatching {
            var idx = at.coerceIn(0, parent.childCount)
            divider?.let { parent.addView(it, idx); idx++ }
            parent.addView(item, idx.coerceAtMost(parent.childCount))
        }.onFailure {
            GLog.w("筛选股票：插入菜单项失败", it)
            return
        }

        // 两项 → 三项：把每一项从「height=0dp + weight=1」改成「固定高度、不参与权重」，
        // 再把容器与**弹窗窗口**的高度都放开 —— 否则三项会被压进原来那 98dp 里
        // （实测每项矮了 1/3，甚至第三项被整个裁掉）。
        //
        // 为什么不直接改容器高度：inflate(layout, null) 出来的根 View **没有 LayoutParams**
        // （交给 PopupWindow 时才会被赋上 MATCH_PARENT），拿不到可改的高度值。
        // 而它外面的 `PopupWindow` 是**用固定尺寸 new 出来的**（实测窗口高度恒为 98dp），
        // 所以还必须把 `pop.height` 改成 WRAP_CONTENT，让窗口按新内容重新量一次。
        runCatching {
            if (!popupOriginalHeight.containsKey(pop)) popupOriginalHeight[pop] = pop.height
            GLog.i("筛选股票：菜单窗口原尺寸 width=${pop.width} height=${pop.height}" +
                    "（-1=MATCH_PARENT, -2=WRAP_CONTENT）")
            pop.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }.onFailure { GLog.w("筛选股票：调整菜单窗口高度失败", it) }
        runCatching { normalizeMenuItemHeights(parent, MENU_BASE_ITEM_COUNT + 1) }

        GLog.i("筛选股票：已在三点菜单「修改名称」下方注入「$MENU_ITEM_TEXT」")
    }

    /** 把菜单里每个文字项的权重去掉、高度固定成 `原设计总高 / 原项数` */
    private fun normalizeMenuItemHeights(parent: ViewGroup, itemCount: Int) {
        val density = parent.resources.displayMetrics.density
        val itemPx = ((MENU_DESIGN_HEIGHT_DP / MENU_BASE_ITEM_COUNT) * density + 0.5f).toInt()
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i) as? TextView ?: continue
            val p = c.layoutParams as? LinearLayout.LayoutParams ?: continue
            p.weight = 0f
            p.height = itemPx
            c.layoutParams = p
        }
        val lp = parent.layoutParams ?: return
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        parent.layoutParams = lp
        GLog.i("筛选股票：菜单项高度已归一（${itemCount} 项 × ${itemPx}px）")
    }

    private fun restoreMenuItemHeights(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i) as? TextView ?: continue
            val p = c.layoutParams as? LinearLayout.LayoutParams ?: continue
            p.weight = 1f
            p.height = 0
            c.layoutParams = p
        }
    }

    private fun removeInjected(pop: PopupWindow, parent: ViewGroup) {
        val item = parent.findViewWithTag<View>(TAG_ITEM) ?: return
        val idx = parent.indexOfChild(item)
        runCatching { parent.removeView(item) }
        // 分隔线紧邻在菜单项前一个位置（注入时成对放入）
        if (idx - 1 >= 0) {
            val prev = parent.getChildAt(idx - 1)
            if (prev is ImageView) runCatching { parent.removeView(prev) }
        }
        runCatching { restoreMenuItemHeights(parent) }
        runCatching { popupOriginalHeight.remove(pop)?.let { pop.height = it } }
    }

    // ------------------------------------------------------------------ 数据交换

    private fun onGetView(env: FeatureEnv, adapter: Any?, args: List<Any?>, result: Any?) {
        if (adapter == null) return
        if (!StgStockFilterTargets.isTargetAdapter(adapter.javaClass.name)) return

        // ① 顺手记一份行内涨跌幅文案（数据项 zdf 读不到时的兜底）
        val row = result as? View
        if (row != null) recordRowPercent(adapter, args.getOrNull(0) as? Int ?: -1, row)

        // ② 兜底补筛：数据还是「宿主的全量」而条件又生效时，延后一帧换数据
        if (!isFilterActive(env, adapter)) return
        val cur = readData(adapter) ?: return
        val st = stateOf(adapter)
        if (cur === st.filtered) return
        val ctx = row?.context ?: trackedActivity ?: return
        val act = findActivity(ctx) ?: trackedActivity ?: return
        val handler = act.window?.decorView?.handler ?: return
        if (pendingRefilter) return
        pendingRefilter = true
        handler.post {
            pendingRefilter = false
            runCatching {
                ensureClickRemap()
                applyFilter(env, adapter, notify = true)
            }.onFailure { GLog.w("筛选股票：补一次筛选失败", it) }
        }
    }

    /** 条件生效（功能开 + 至少勾了一个）且数据不是我们装的 → 需要补筛 */
    private fun isFilterActive(env: FeatureEnv, adapter: Any): Boolean {
        if (!env.config.stgStockFilter) return false
        val ctx = trackedActivity ?: return false
        return filtersBy(ctx).active
    }

    /**
     * 核心：把适配器的 `data` 字段换成「筛过的子集」，或者把全量还原回去。
     *
     * @param notify true 时由我们主动通知列表刷新（面板点「确定 / 重置」、兜底补筛）；
     *               false 表示当前正处于宿主 `notifyDataSetChanged` 的**前置**钩子里，
     *               原方法马上会通知，不用也不要重复通知。
     */
    private fun applyFilter(env: FeatureEnv, adapter: Any, notify: Boolean) {
        val dataField = Reflect.findField(adapter.javaClass, DATA_FIELD) ?: run {
            GLog.w("筛选股票：适配器上没有 $DATA_FIELD 字段，无法换数据")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val current = runCatching { dataField.get(adapter) as? List<Any> }.getOrNull()
        val st = stateOf(adapter)

        val ctx = trackedActivity ?: return

        // 功能关掉 / 三个都不勾 → 还原全量
        if (!env.config.stgStockFilter || !filtersBy(ctx).active) {
            if (st.filtered != null && current === st.filtered) {
                st.full?.let { runCatching { dataField.set(adapter, it) } }
                if (notify) notifyAdapter(adapter)
            }
            st.filtered = null
            st.keptIndices = null
            lastSummary = null
            GLog.i("筛选股票：筛选未生效，已还原全量（${st.full?.size ?: 0} 只）")
            return
        }

        // 当前不是我们装的 → 这就是宿主最新一次给的「全量」
        if (current !== st.filtered) st.full = current
        val full = st.full ?: return

        val f = filtersBy(ctx)
        val keep = ArrayList<Any>(full.size)
        val keptIdx = ArrayList<Int>(full.size)
        var up = 0
        var down = 0
        var zx = 0
        var flat = 0
        for ((i, item) in full.withIndex()) {
            when (categoryOf(adapter, item)) {
                Cat.ZX -> {
                    zx++
                    if (f.zx) { keep.add(item); keptIdx.add(i) }
                }
                Cat.UP -> {
                    up++
                    if (f.up) { keep.add(item); keptIdx.add(i) }
                }
                Cat.DOWN -> {
                    down++
                    if (f.down) { keep.add(item); keptIdx.add(i) }
                }
                Cat.FLAT -> flat++          // 平盘不属于任何类别 → 开了筛选就不显示
            }
        }

        val summary = "全量 ${full.size} → 保留 ${keep.size}（涨 $up / 跌 $down / 自选 $zx / 平盘 $flat）"
        val changed = !sameList(st.filtered, keep)
        st.filtered = keep
        st.keptIndices = keptIdx.toIntArray()
        if (current !== keep) runCatching { dataField.set(adapter, keep) }
        if (notify && changed) notifyAdapter(adapter)

        if (summary != lastSummary) {
            lastSummary = summary
            GLog.i("筛选股票：$summary")
        }
    }

    private fun sameList(a: List<Any>?, b: List<Any>): Boolean {
        if (a == null || a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }

    /** 走宿主自己的 `notifyDataSetChanged()`（会再次进我们的前置钩子，幂等） */
    private fun notifyAdapter(adapter: Any) {
        Reflect.findMethod(adapter.javaClass, "notifyDataSetChanged")?.let { m ->
            runCatching { m.invoke(adapter) }
                .onFailure { GLog.w("筛选股票：notifyDataSetChanged 调用失败", it) }
        }
    }

    private fun readData(adapter: Any): List<Any>? {
        val f = Reflect.findField(adapter.javaClass, DATA_FIELD) ?: return null
        @Suppress("UNCHECKED_CAST")
        return runCatching { f.get(adapter) as? List<Any> }.getOrNull()
    }

    /**
     * 给 ListView 的行点击装一层「下标回映射」。
     *
     * ## 为什么必须做
     *
     * 换掉 `adapter.data` 之后，ListView 的 position 变成**子集下标**；但宿主自己的
     * 点击处理是拿 `position` 去索引**它自己那份全量列表**（实测：筛到只剩 6 只、
     * 点第一行「思特威-W」，结果打开的是全量第一只「新华文轩 601811」）。
     * 所以要把「子集下标」还原成「全量下标」再交给宿主 ——
     * 用 [AdapterState.keptIndices] 这张表换算。
     *
     * 做法是把宿主原来的 `OnItemClickListener`（`AdapterView.mOnItemClickListener`
     * 是隐藏 API，得反射取）包一层。没装筛选时 [AdapterState.keptIndices] 为空，
     * 等价于原样透传。
     */
    private fun ensureClickRemap() {
        val act = trackedActivity ?: return
        val listId = listViewId(act)
        if (listId == 0) return
        val lv = act.findViewById<View>(listId) as? ListView ?: return
        val adapter = unwrapHeaderAdapter(lv.adapter) ?: return
        if (!StgStockFilterTargets.isTargetAdapter(adapter.javaClass.name)) return

        val existing = itemClickListener(lv)
        if (existing != null && wrappedLists[lv] === existing) return   // 已经包过

        val wrapper = AdapterView.OnItemClickListener { parent, view, position, id ->
            var mapped = position
            runCatching {
                val header = lv.headerViewsCount
                val idx = stateOf(adapter).keptIndices?.getOrNull(position - header)
                if (idx != null) mapped = header + idx
            }.onFailure { GLog.w("筛选股票：点击下标回映射失败", it) }
            runCatching { existing?.onItemClick(parent, view, mapped, id) }
                .onFailure { GLog.w("筛选股票：转发行点击失败", it) }
        }
        runCatching { lv.onItemClickListener = wrapper }
            .onSuccess { wrappedLists[lv] = wrapper }
            .onFailure { GLog.w("筛选股票：包裹行点击监听失败", it) }
    }

    /** 读宿主原本挂在 ListView 上的 `OnItemClickListener`（`AdapterView.getOnItemClickListener` 是隐藏 API） */
    private fun itemClickListener(lv: ListView): AdapterView.OnItemClickListener? =
        runCatching {
            val f = AdapterView::class.java.getDeclaredField("mOnItemClickListener")
            f.isAccessible = true
            f.get(lv) as? AdapterView.OnItemClickListener
        }.getOrNull()

    /** 取出真正的业务适配器（ListView 带 header 时外面套了一层 HeaderViewListAdapter） */
    private fun unwrapHeaderAdapter(a: Any?): Any? {
        if (a == null) return null
        if (a.javaClass.name == "android.widget.HeaderViewListAdapter") {
            return runCatching {
                a.javaClass.getMethod("getWrappedAdapter").invoke(a)
            }.getOrNull()
        }
        return a
    }

    private fun listViewId(activity: Activity): Int {
        var id = listIdCache
        if (id != 0) return id
        id = activity.resources.viewIdOrZero(*StgStockFilterTargets.LIST_ID_CANDIDATES)
        if (id != 0) listIdCache = id
        return id
    }

    private fun stateOf(adapter: Any): AdapterState = synchronized(states) {
        states.getOrPut(adapter) { AdapterState() }
    }

    /** 记录「代码.市场 → 行内涨跌幅文案」，只在数据项字段缺失时当兜底 */
    private fun recordRowPercent(adapter: Any, position: Int, row: View) {
        if (position < 0) return
        runCatching {
            val item = itemAt(adapter, position) ?: return@runCatching
            val cam = codeAndMarket(item) ?: return@runCatching
            val pct = parsePercent(percentView(row)?.text?.toString()) ?: return@runCatching
            val map = synchronized(percentByCam) {
                percentByCam.getOrPut(adapter) { HashMap() }
            }
            synchronized(map) { map[cam] = pct }
        }
    }

    // ------------------------------------------------------------------ 类别判定

    private enum class Cat { UP, DOWN, ZX, FLAT }

    private fun categoryOf(adapter: Any, item: Any): Cat {
        val cam = codeAndMarket(item)
        if (cam != null) {
            val dot = cam.indexOf('.')
            if (dot > 0 && ZxWatchlist.isInWatchlist(cam.substring(0, dot), cam.substring(dot + 1))) {
                return Cat.ZX                              // 自选优先：在自选里就归「自选」
            }
        }
        val pct = percentOf(adapter, item, cam) ?: return Cat.FLAT
        return when {
            pct > 0.0 -> Cat.UP
            pct < 0.0 -> Cat.DOWN
            else -> Cat.FLAT
        }
    }

    /** 涨跌幅：先读数据项字段（实测 `zdf`，BigDecimal），再退回行内文案记录 */
    private fun percentOf(adapter: Any, item: Any, cam: String?): Double? {
        for (f in StgStockFilterTargets.PERCENT_FIELDS) {
            val v = Reflect.readField(item, f) ?: continue
            val d = when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (d != null) return d
        }
        if (cam != null) {
            val map = synchronized(percentByCam) { percentByCam[adapter] }
            if (map != null) synchronized(map) { return map[cam] }
        }
        return null
    }

    // ------------------------------------------------------------------ 页面回调

    private fun onActivityResumed(env: FeatureEnv, activity: Activity) {
        if (!StgStockFilterTargets.isWatchActivity(activity.javaClass.name)) return
        trackedActivity = activity
        cachedFilters = null                       // 回到本页重新读一次筛选状态
        ensureClickRemap()
        // 页面上的适配器可能还是旧数据，按最新条件对齐一次
        for (a in synchronized(states) { states.keys.toList() }) {
            runCatching { applyFilter(env, a, notify = true) }
                .onFailure { GLog.w("筛选股票：onResume 对齐失败", it) }
        }
    }

    private fun onFilterApplied(env: FeatureEnv, activity: Activity, applied: StgFilters) {
        trackedActivity = activity
        cachedFilters = applied
        lastSummary = null
        ensureClickRemap()
        GLog.i("筛选股票：筛选条件已生效（${StgFilterPanel.describe(applied)}）")
        for (a in synchronized(states) { states.keys.toList() }) {
            runCatching { applyFilter(env, a, notify = true) }
                .onFailure { GLog.w("筛选股票：应用筛选失败", it) }
        }
    }

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        cachedFilters = null
        for (a in synchronized(states) { states.keys.toList() }) {
            runCatching { applyFilter(env, a, notify = true) }
                .onFailure { GLog.w("筛选股票：配置变化后重算失败", it) }
        }
    }

    // ------------------------------------------------------------------ 取值与工具

    private fun filtersBy(ctx: Context): StgFilters =
        cachedFilters ?: StgFilterStore.load(ctx).also { cachedFilters = it }

    private fun percentView(row: View): TextView? {
        var id = percentIdCache
        if (id == 0) {
            id = row.resources.viewIdOrZero(*StgStockFilterTargets.PERCENT_ID_CANDIDATES)
            if (id == 0) return null
            percentIdCache = id
        }
        return runCatching { row.findViewById<View>(id) as? TextView }.getOrNull()
    }

    private fun itemAt(adapter: Any, position: Int): Any? {
        Reflect.findMethod(adapter.javaClass, "getItem", Int::class.javaPrimitiveType!!)?.let { m ->
            runCatching { m.invoke(adapter, position) }.getOrNull()?.let { return it }
        }
        return readData(adapter)?.let { pick(it, position) }
    }

    private fun pick(list: Any?, position: Int): Any? {
        if (list is List<*> && position >= 0 && position < list.size) return list[position]
        return null
    }

    private fun codeAndMarket(item: Any?): String? {
        if (item == null) return null
        for (f in StgStockFilterTargets.CAM_FIELDS) {
            val v = Reflect.readField(item, f) as? String
            if (!v.isNullOrEmpty()) return v
        }
        var code: String? = null
        for (f in StgStockFilterTargets.CODE_FIELDS) {
            val v = Reflect.readField(item, f) as? String
            if (!v.isNullOrEmpty()) {
                code = v
                break
            }
        }
        if (code == null) return null
        for (f in StgStockFilterTargets.MARKET_FIELDS) {
            val v = Reflect.readField(item, f) ?: continue
            val s = v.toString()
            if (s.isNotEmpty()) return "$code.$s"
        }
        return null
    }

    // ------------------------------------------------------------ 样式克隆

    private fun cloneTextStyle(src: TextView, dst: TextView) {
        runCatching { dst.setBackground(src.background?.constantState?.newDrawable()?.mutate()) }
        runCatching { dst.setTextColor(src.textColors) }
        runCatching { src.typeface?.let { dst.typeface = it } }
        runCatching { dst.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.textSize) }
        runCatching {
            dst.setPadding(src.paddingLeft, src.paddingTop, src.paddingRight, src.paddingBottom)
        }
        runCatching { dst.gravity = src.gravity }
        runCatching { dst.includeFontPadding = src.includeFontPadding }
        runCatching { dst.maxLines = src.maxLines }
        runCatching { dst.minWidth = src.minWidth }
        runCatching { dst.minHeight = src.minHeight }
        runCatching { dst.setAllCaps(false) }
    }

    private fun cloneParams(src: View): ViewGroup.LayoutParams {
        val lp = src.layoutParams
        return when (lp) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp)
            is ViewGroup.LayoutParams -> ViewGroup.LayoutParams(lp)
            else -> ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    @Volatile private var pendingRefilter = false

    private companion object {
        /** 菜单展示方法名（全重载都挂） */
        val POPUP_SHOW_METHODS = arrayOf("showAsDropDown", "showAtLocation")

        /** 注入项在菜单里的文字 */
        const val MENU_ITEM_TEXT = "筛选股票"

        /** 注入项的标记 tag —— 幂等判定与撤销都靠它 */
        const val TAG_ITEM = "gsaux_stg_filter_item"

        /** 原菜单布局 `pop_ai_stg.xml` 的设计高度（dp）：两项均分 */
        const val MENU_DESIGN_HEIGHT_DP = 98f

        /** 原菜单里的项数（「修改名称」+「删除策略」） */
        const val MENU_BASE_ITEM_COUNT = 2

        /** 列表数据字段名（实测 `BaseQuickAdapter` 上就叫 `data`） */
        const val DATA_FIELD = "data"

        /** `+9.99%` / `-1.45%` / `0.00%` / `--` */
        fun parsePercent(s: String?): Double? {
            val t = s?.trim()?.removeSuffix("%")?.replace("+", "") ?: return null
            if (t.isEmpty() || t == "--" || t == "-") return null
            return t.toDoubleOrNull()
        }

        /** 依次尝试候选 id 名，返回第一个解析到的（找不到返回 0） */
        fun Resources.viewIdOrZero(vararg names: String): Int {
            for (n in names) {
                val r = runCatching {
                    getIdentifier(n, "id", StgStockFilterTargets.TARGET_PKG)
                }.getOrDefault(0)
                if (r != 0) return r
            }
            return 0
        }

        fun findActivity(ctx: Context?): Activity? {
            var c: Context? = ctx
            var guard = 0
            while (c != null && guard++ < 10) {
                if (c is Activity) return c
                c = (c as? ContextWrapper)?.baseContext
            }
            return null
        }
    }
}
