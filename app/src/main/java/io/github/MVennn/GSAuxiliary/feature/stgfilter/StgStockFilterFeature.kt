package io.github.MVennn.GSAuxiliary.feature.stgfilter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.watchlist.ZxWatchlist
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.util.Collections
import java.util.WeakHashMap

/**
 * 功能：**策略详情页「筛选股票」**。
 *
 * ## 需求
 *
 * 1. 在右上角三点菜单（「修改名称」/「删除策略」）里、**「修改名称」下方**新增一项
 *    `筛选股票`；
 * 2. 点击弹出筛选页，页内提供 **涨家股票 / 跌家股票 / 自选股票** 三个可切换开关；
 * 3. 筛选状态**本地持久化**，关掉软件重开仍在；
 * 4. 筛选页**复用目标 App 自带的筛选组件 UI**（行情页「沪深京A」左侧漏斗那个），
 *    原「取消」按钮改成「重置」，「确定」不变；
 * 5. 左上角那颗「策略个股N只」改成 **`策略个股 保留数/总数`**（宿主只写全量，不会跟着筛）；
 * 6. 筛到**一只不剩**时，把宿主自己的**无股票页面**
 *    （`res/layout/empty_ai_stk.xml` —— 一个带图标的 `TextView`，文案「未选出符合条件的股票」）
 *    摆到**股票行**那块；策略名称 / 涨家数 / 标签 / 「策略个股N只」这些在列表 header 里的东西
 *    一行都不动。
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
 * ### ③ 行过滤：**给 ListView 套一层过滤代理**（真正移除，不是藏起来）
 *
 * 「藏行」这条路走不通，两个原因都实测过：
 *
 *  - 只设 `GONE` 不行：`ListView.layoutChildren` 直接对子 View 调
 *    `setupChild → child.measure()`（不走 `ViewGroup.measureChildren` 那套「跳过 GONE」的逻辑），
 *    被藏的行**照样占着 138px**，界面上一整块空白；
 *  - 压高度也只是治标：`lp.height = 0` 会掉进 `setupChild` 的
 *    `if (lpHeight > 0) EXACTLY else UNSPECIFIED` 的 **else 分支**、被当成"量自然高度"；
 *    用 1px 能压住，但宿主一刷新列表（重新 `setData` + `notifyDataSetChanged`），
 *    已经布局好的行不会被重新量，空白/漏筛就都冒出来了。
 *
 * 「直接换 `data`」也不行 —— 理由见 [FilteredAdapter] 的注释：宿主 `onItemClick`
 * 拿 adapter 的 `getCount()` 做范围校验、却用它自己那份全量取值，一旦把 `data`
 * 换成子集，**全量下标 ≥ 筛选后行数的那些行就永远点不动**（筛得越狠，点不动的越多）。
 *
 * 现在改成给 ListView 套一层 [FilteredAdapter]：自己报子集的行数，
 * `getView / getItem` 用全量下标转调原适配器。宿主持有的原适配器**一个字节都不动**，
 * 它的 `getCount()` 仍是全量，校验与取值照旧；行点击再由 wrapper 把
 * 「子集下标 → 全量下标」映射回去（见 [ensureClickRemap]）。
 *
 * 时序与幂等都在 [applyFilter] 里处理：
 *  - 挂 `android.widget.BaseAdapter.notifyDataSetChanged` 的**前置**钩子 ——
 *    宿主说"数据变了"时先把代理的下标表刷到位，再放行；
 *  - 结果与上一轮完全一致、代理也还挂在列表上时**什么都不做**
 *    （宿主行情刷新很勤，别再给它多加一次"数据变了"）；
 *  - 关掉功能 / 清空条件 → 把 ListView 换回原适配器。
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
 *     自选股票  在自选股里（**最高优先**）
 *     涨家股票  否则 zdf > 0
 *     跌家股票  否则 zdf < 0
 *     平盘      其余（zdf == 0 或读不到）
 *
 * 显示(股票) = 没有任何开关勾选 ? true : 类别(股票) ∈ 已勾选的类别
 * ```
 *
 * 举例（都是需求里的例子与推论）：
 *  - 只勾「涨家股票」→ 涨的要有、跌的不要、**自选里即使涨着也不要**（自选是另一个类别）；
 *  - 只勾「自选股票」→ 只有自选里的；
 *  - 勾「涨家股票 + 自选股票」→ 涨的（非自选）+ 自选里的；
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

    /**
     * 当前停在策略详情页的 Activity。
     *
     * 审查 2026-10-17 高-3：原来是 `@Volatile` 强引用且从不置空 —— 热重载后旧实例
     * 被整体替换、没人再读它，却把离开已久的 Activity（连同整棵视图树）钉在内存里。
     * 改成弱引用（与 [trackedList] 同款写法），页面销毁 / 实例废弃后自然释放。
     */
    private val trackedActivityRef =
        java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<Activity>>()

    private fun trackedActivity(): Activity? = trackedActivityRef.get()?.get()

    /** 三点菜单窗口被我们改过的原始高度，撤销时还原 */
    private val popupOriginalHeight = Collections.synchronizedMap(WeakHashMap<PopupWindow, Int>())

    /** 已经包过行点击回映射的 ListView → 我们装的 wrapper（用来避免重复包裹） */
    private val wrappedLists = Collections.synchronizedMap(
        WeakHashMap<ListView, AdapterView.OnItemClickListener>()
    )

    /** 策略详情页 ListView 的资源 id 缓存 */
    @Volatile private var listIdCache = 0

    /** 左上角「策略个股N只」那颗计数的资源 id 缓存 */
    @Volatile private var resNumIdCache = 0

    /** 我们摆上去的那块空态视图（宿主 `empty_ai_stk` 的实例），弱引用，页面重建后自然失效 */
    @Volatile private var emptyViewRef: java.lang.ref.WeakReference<View>? = null

    /**
     * 进空态时被我们压成「只剩头部」的 ListView → 它**原来**的 LayoutParams。
     *
     * 退出空态时要原样还回去（`height=0dp + weight=1`），否则列表就再也长不回来了。
     */
    private val lvOriginalLp = Collections.synchronizedMap(
        WeakHashMap<ListView, LinearLayout.LayoutParams>()
    )

    /** 进空态时被我们从列表上摘下来的「说明及风险提示」 */
    private val detachedChrome = Collections.synchronizedMap(WeakHashMap<ListView, DetachedChrome>())

    /** 上面那个 ListView 本体的弱引用缓存 —— `ensureClickRemap` 每帧都可能被调到，别每次都 findViewById */
    private val trackedList = java.util.concurrent.atomic.AtomicReference<java.lang.ref.WeakReference<ListView>>()

    /** `AdapterView.mOnItemClickListener` 那个 Field，反射取一次就留着 */
    @Volatile private var clickListenerField: java.lang.reflect.Field? = null

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

    /**
     * 我们写进适配器的那份内容 + 与它**配套**的下标表。
     *
     * 两者必须当成一个整体替换 —— 分开赋值的话，点击回映射可能读到
     * 「新数据 + 旧下标」，算出来的下标就成了别的股票。
     */
    private class Snapshot(val kept: ArrayList<Any>, val keptIndices: IntArray)

    private class AdapterState {
        /** 当前筛选结果 + 下标表（点击回映射用）；没筛就为 null */
        @Volatile var snap: Snapshot? = null

        /** 装在 ListView 上的过滤代理；null = 当前没有筛选 */
        @Volatile var proxy: FilteredAdapter? = null
    }

    /**
     * 「只显示筛选结果」的适配器代理。
     *
     * ## 为什么不直接改 `AIStkAdapter.data`（上一版的做法）
     *
     * 宿主 `AIStkStgDetailAct.onItemClick` 是**拿 adapter 的 `getCount()` 做范围校验、
     * 却用它自己那份全量列表取值**的。真机日志坐实：筛选只剩 1 只时点第一行，
     * 我们把 `position` 映射成全量下标 2 转发过去，宿主回调**进来了、也没抛异常、
     * 就是什么都没发生** —— `2-1=1` 恰好卡在 `getCount()=1` 的边界上，被它的范围检查挡掉。
     *
     * 一旦把 `data` 换成子集，这两套语义就打架：**凡是全量下标 ≥ 筛选后行数的行，
     * 永远点不动**。这正是「筛选后下方一大片点不了、且数量随策略股票数变化」的来源 ——
     * 筛得越狠，点不动的行越多。
     *
     * 现在**不碰宿主的数据**（`getCount()` 仍是全量，宿主的校验照过），
     * 只给 ListView 套一层代理：自己报子集的行数，`getView / getItem` 用全量下标转调原适配器。
     * 行点击监听那层照旧做下标回映射，宿主拿到的仍是他熟悉的那套语义。
     */
    private class FilteredAdapter(
        private val base: android.widget.ListAdapter,
        rows: IntArray,
    ) : android.widget.BaseAdapter() {

        /** 被代理的原适配器 —— 外面认它、`stateOf` 也拿它当 key */
        val target: android.widget.ListAdapter get() = base

        @Volatile private var indices: IntArray = rows

        /** 筛选结果变了就地更新（不重建代理，省掉一次 setAdapter 的滚动位置重置） */
        fun update(newRows: IntArray) {
            indices = newRows
        }

        override fun getCount(): Int = indices.size

        /**
         * update() 与宿主下一次 notifyDataSetChanged 之间，ListView 可能仍按旧 count
         * 发起 getView/getItem，position 越界会直接把宿主打崩（审查 2026-10-17）。
         * 越界时回退用原始 position 转调 base：base 是全量列表，position 必在其范围内。
         */
        private fun mapped(position: Int): Int = indices.getOrNull(position) ?: position

        override fun getItem(position: Int): Any? = base.getItem(mapped(position))

        override fun getItemId(position: Int): Long = base.getItemId(mapped(position))

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            base.getView(mapped(position), convertView, parent)

        // 观察者注册一律转发给原适配器 —— ListView 的 observer 是注册在我们身上的，
        // 只有转发过去，宿主 `base.notifyDataSetChanged()` 才能刷到列表。
        override fun registerDataSetObserver(observer: android.database.DataSetObserver) {
            base.registerDataSetObserver(observer)
        }

        override fun unregisterDataSetObserver(observer: android.database.DataSetObserver) {
            base.unregisterDataSetObserver(observer)
        }

        override fun hasStableIds(): Boolean = base.hasStableIds()

        override fun areAllItemsEnabled(): Boolean = base.areAllItemsEnabled()

        override fun isEnabled(position: Int): Boolean = base.isEnabled(mapped(position))

        override fun isEmpty(): Boolean = indices.isEmpty()
    }

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.stgStockFilter

    // ------------------------------------------------------------------ 跨实例识别

    /**
     * 是不是我们（**任意热重载实例**）装的过滤代理。
     *
     * 审查 2026-10-17 高-3：热重载后新旧实例的 [FilteredAdapter] 分属**不同
     * ClassLoader 下的同名类**，`is FilteredAdapter` 只认得自己这一份，旧实例留在
     * ListView 上的代理就成了「无主」的 —— 还原逻辑全部走不到。这里按类名判断，
     * 跨实例、跨 ClassLoader 都有效。
     */
    private fun isFilteredAdapter(a: Any?): Boolean =
        a != null && "FilteredAdapter" in a.javaClass.name

    /**
     * 取出过滤代理包着的原适配器；不是代理就原样返回。
     *
     * 兼容旧实例的代理：那种代理不是当前 ClassLoader 里的 [FilteredAdapter]，
     * 只能反射调 `getTarget()`（Kotlin 属性生成的公开方法，热重载前后签名一致）。
     * 反射失败返回 null，调用方按「现场认不出来」安全退避。
     */
    private fun unwrapProxy(a: Any): Any? {
        if (!isFilteredAdapter(a)) return a
        return runCatching { a.javaClass.getMethod("getTarget").invoke(a) }.getOrNull()
    }

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
        runCatching { normalizeMenuItemHeights(parent) }

        GLog.i("筛选股票：已在三点菜单「修改名称」下方注入「$MENU_ITEM_TEXT」")
    }

    /** 把菜单里每个文字项的权重去掉、高度固定成 `原设计总高 / 原项数` */
    private fun normalizeMenuItemHeights(parent: ViewGroup) {
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
        GLog.i("筛选股票：菜单项高度已归一（${parent.childCount} 项 × ${itemPx}px）")
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

        // ② 行点击回映射要**常驻**：宿主随时可能在刷新流程里重新
        //    `setOnItemClickListener(...)`，把我们的 wrapper 顶掉 ——
        //    之后点击就按「子集下标」解释，点开的是别的股票。
        //    ensureClickRemap 自己幂等，Field 与 ListView 都有缓存，这里开销很小。
        runCatching { ensureClickRemap() }

        // ③ 兜底补一次：条件生效、但 ListView 上没挂着我们的过滤代理。
        //    宿主可能自己 setAdapter 过一次把代理顶掉，也可能只 setAdapter 不发通知 ——
        //    两种情况都得在下一次绑定时把代理装回去。
        if (!isFilterActive(env, adapter)) return
        val st = stateOf(adapter)
        val ctx = row?.context ?: trackedActivity() ?: return
        val act = findActivity(ctx) ?: trackedActivity() ?: return
        val lv = trackedList.get()?.get() ?: findListView(act) ?: return
        if (st.proxy != null && unwrapHeaderAdapter(lv.adapter) === st.proxy) return
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
        val ctx = trackedActivity() ?: return false
        return filtersBy(ctx).active
    }

    /**
     * 核心：把适配器的 `data` **原地**改成「筛过的子集」，或者把全量还回去。
     *
     * ## 为什么要「原地」改，而不是换一个新列表进去
     *
     * 早期版本是 `dataField.set(adapter, 新的ArrayList)` —— 每轮都换新对象。
     * 两个后果，真机上都撞到了：
     *
     *  1. **ListView 每帧作废全部可见行重绑**。宿主行情刷新本来就勤（每秒数次），
     *     我们又给它加了一层「数据对象变了」，于是列表一直处在重建中；
     *     `AbsListView.onTouchEvent` 的 ACTION_UP 分支里 `if (!mDataChanged)`
     *     会把落在这个窗口里的点击**直接丢掉** —— 表现就是「有些行点不动」，
     *     而且越靠下的行越明显（ListView 是从上往下填的，上部每帧来得及绑完）。
     *  2. **`data` 与宿主手里那份脱节**。宿主若原地 `clear() + addAll()`，
     *     它改的是「字段里现在这个对象」，而字段早被我们换成了别的 —— 两边各说各话。
     *
     * 所以现在一律原地 `clear() + addAll()`，`data` 永远是宿主那个实例。
     *
     * ## 怎么判断「当前 data 是我们写的」还是「宿主刚给的全量」
     *
     * 按**内容**比（逐项 `===`），而不是对象引用。宿主原地改写会让「引用不变、内容全变」，
     * 只看引用会认错（这正是之前 `st.full` 被污染成空的根因）。
     *
     * ## 宿主给空列表时不要覆盖全量
     *
     * 「先 clear 再等网络回来 addAll」是很常见的写法：那一下 `notifyDataSetChanged`
     * 我们看到的就是空列表。要是拿它当"新全量"，之后筛选永远算出 0 只，
     * 列表会被刷成一片空白（真机上就是这样把详情页刷成一直转圈的）。
     *
     * @param notify true 时由我们主动通知列表刷新（面板点「确定 / 重置」、兜底补筛）；
     *               false 表示当前正处于宿主 `notifyDataSetChanged` 的**前置**钩子里，
     *               原方法马上会通知，不用也不要重复通知。
     */
    private fun applyFilter(env: FeatureEnv, adapter: Any, notify: Boolean) {
        val ctx = trackedActivity() ?: return
        val lv = trackedList.get()?.get() ?: findListView(ctx) ?: return
        val base = adapter as? android.widget.ListAdapter ?: return

        // 保险：只给「当前列表在用的那个适配器」装代理。
        // 宿主换过适配器 / 页面重建之后，旧实例的 notify 与 getView 回调仍会打到我们身上，
        // 照着旧实例装代理会把列表的数据源换成过期内容（见 [liveAdapter]）。
        // 列表还没有适配器时（live == null）放行 —— 那是首次安装的场景。
        // 列表上挂着的是我们的过滤代理就先拆包（热重载后可能是旧实例的，按类名认）。
        unwrapHeaderAdapter(lv.adapter)?.let { live ->
            val liveBase = unwrapProxy(live) ?: return
            if (liveBase !== adapter) return
        }

        val st = stateOf(adapter)

        // 宿主的全量数据：**只读，一个字节都不改**（理由见 [FilteredAdapter] 的注释）
        val full = readData(adapter) ?: return
        val active = env.config.stgStockFilter && filtersBy(ctx).active

        // 功能关掉 / 三个都不勾 / 数据还没到 → 把 ListView 换回原适配器
        if (!active || full.isEmpty()) {
            // 数据还没到（`full` 空）时什么都不动 —— 那多半是宿主「先 clear 再等回包」的中间态，
            // 照着它把计数写成 0 只、把空态亮出来，都会在下一次刷新时闪一下
            if (full.isNotEmpty()) {
                updateResNum(ctx, full.size, full.size, active = false)
                updateEmptyView(ctx, empty = false)
            }
            // 审查 2026-10-17 高-3：还原判据不能只看本实例的记账表（`st.proxy`）——
            // 热重载后 ListView 上可能还挂着**旧实例**装的 FilteredAdapter，新实例的
            // 记账表是空的，只查表就永远走不到 `lv.adapter = base`，筛选残留不清。
            // 这里补一个「现场」判据：列表上（拆掉 header 包装后）只要还挂着我们的
            // 过滤代理（任意实例的，按类名认），就还原成全量适配器。
            val liveOnList = unwrapHeaderAdapter(lv.adapter)
            if (st.proxy != null || isFilteredAdapter(liveOnList)) {
                runCatching { lv.adapter = base }
                    .onFailure { GLog.w("筛选股票：还原适配器失败", it) }
                st.proxy = null
                st.snap = null
                StgFilterBridge.publish(null)
                lastSummary = null
                GLog.i("筛选股票：筛选未生效，已还原全量（${full.size} 只）")
            }
            return
        }

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

        val rows = keptIdx.toIntArray()
        val snap = st.snap
        val proxy = st.proxy

        // 左上角计数与空态：宿主只管全量、也不认识我们的筛选，所以每轮都要照结果重写一遍。
        // 放在「结果没变就早退」之前 —— 宿主可能刚把计数刷回「策略个股15只」。
        // 宿主若在同一次刷新里晚于我们写这颗控件，延后一帧再拽一次（只在确实改过时才排）
        if (updateResNum(ctx, keep.size, full.size, active = true)) {
            runCatching { lv.post { updateResNum(ctx, keep.size, full.size, active = true) } }
        }
        updateEmptyView(ctx, keep.isEmpty())

        // 结果与上一轮一致、代理也还挂在列表上 → 什么都别动。
        // 宿主行情刷新很勤，这里放过就等于不给 ListView 多加一次「数据变了」。
        if (proxy != null && snap != null && sameList(keep, snap.kept) &&
            unwrapHeaderAdapter(lv.adapter) === proxy
        ) {
            StgFilterBridge.publish(keep)
            return
        }

        // 下标表与结果一起替换（点击回映射读的是这一份，不能出现「新结果 + 旧下标」）
        st.snap = Snapshot(keep, rows)

        if (proxy == null) {
            val p = FilteredAdapter(base, rows)
            st.proxy = p
            runCatching { lv.adapter = p }
                .onFailure {
                    GLog.w("筛选股票：装过滤代理失败", it)
                    st.proxy = null
                }
        } else {
            proxy.update(rows)
            if (unwrapHeaderAdapter(lv.adapter) !== proxy) {
                // 宿主自己 setAdapter 过一次 → 代理被顶掉，重装
                runCatching { lv.adapter = proxy }
                    .onFailure { GLog.w("筛选股票：重装过滤代理失败", it) }
            }
        }

        StgFilterBridge.publish(keep)
        if (notify) notifyAdapter(adapter)

        val summary = "全量 ${full.size} → 保留 ${keep.size}（涨 $up / 跌 $down / 自选 $zx / 平盘 $flat）"
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

    // ------------------------------------------------------------------ 左上角计数 / 空态

    /**
     * 左上角那颗 `#tv_stg_res_num`。
     *
     * 宿主写的永远是**全量**（「策略个股15只」），筛完也不会变 —— 屏幕上只剩 3 行、
     * 计数却还写着 15 只，看着就是没筛。这里改成 `策略个股 保留数/总数`，
     * 筛选取消时再按宿主原来的格式（「策略个股N只」）还原回去。
     *
     * @param active true = 筛选生效，写成 `策略个股 保留数/总数`；
     *               false = 不筛，按宿主格式还原成 `策略个股总数只`。
     *               **只在当前文案确实是我们改过的那个格式时才动手**，
     *               免得把宿主自己写的文案覆盖掉。
     * @return 真的改写了才返回 true —— 调用方据此决定要不要延后一帧再复查一次
     *         （宿主可能在我们之后又把计数刷回全量）。
     */
    private fun updateResNum(act: Activity, kept: Int, total: Int, active: Boolean): Boolean {
        val id = resNumId(act)
        if (id == 0) return false
        val tv = runCatching { act.findViewById<View>(id) as? TextView }.getOrNull() ?: return false
        val cur = tv.text?.toString().orEmpty()
        if (!active && !OUR_RES_NUM.matches(cur)) return false
        val want = if (active) "策略个股 $kept/$total" else "策略个股${total}只"
        if (cur == want) return false
        return runCatching { tv.text = want }
            .onSuccess { GLog.i("筛选股票：左上角计数 → $want") }
            .onFailure { GLog.w("筛选股票：改左上角计数失败", it) }
            .isSuccess
    }

    /** 左上角计数控件的 id 解析（解析一次就缓存；找不到返回 0） */
    private fun resNumId(act: Activity): Int {
        var id = resNumIdCache
        if (id == 0) {
            id = act.resources.viewIdOrZero(*StgStockFilterTargets.RES_NUM_ID_CANDIDATES)
            if (id != 0) resNumIdCache = id
        }
        return id
    }

    /**
     * 筛到一只不剩时，把**宿主自己的**「未选出符合条件的股票」摆到列表**下面那块**；否则收起来。
     *
     * ## 用的就是宿主的那个布局
     *
     * `res/layout/empty_ai_stk.xml`（证据见 [StgStockFilterTargets.LAYOUT_EMPTY]）——
     * 所以字号、灰字颜色、图标、上内边距全与宿主一致，换肤 / 深色模式自动跟随。
     * 不自己画一个：自己画的迟早会和宿主对不上。
     *
     * ## 只让出「股票行」那块，头部一行都不动
     *
     * 详情页的根是**竖直 `LinearLayout`**，`#lvStks` 是 `height=0dp + weight=1` ——
     * 列表**永远占满剩余空间**，与它里面有几行无关。所以空态想有立足之地，只能先动列表。
     * 但**不能把整个列表收起来**：策略名称 / 涨家数 / 标签 / 「策略个股N只 · 勾选加入自选股」
     * 这些全在列表的 **header** 里，收掉列表等于把整页的头都抹了。
     *
     * 做法是把列表压成「只剩 header」：`height` 改 `WRAP_CONTENT`、`weight` 改 0，
     * 空态视图接管它让出来的那一块（`height=0dp + weight=1`）。
     * 列表自己的 LayoutParams 存一份在 [lvOriginalLp]，退出空态时原样还原。
     *
     * 列表的 `visibility` **一概不碰** —— 那是宿主的，我们只借它的高度。
     */
    private fun updateEmptyView(act: Activity, empty: Boolean) {
        val lv = trackedList.get()?.get() ?: findListView(act)
        if (lv == null) {
            GLog.w("筛选股票：找不到列表，空态没法切（empty=$empty）")
            return
        }

        // 收起来：没造过就直接走人 —— 别为了「隐藏」去 inflate 一块永远不会显示的东西
        if (!empty) {
            val v = emptyViewRef?.get() ?: return
            if (v.visibility == View.GONE) return
            runCatching {
                v.visibility = View.GONE
                restoreListChrome(act, lv)
                restoreListLp(lv)
            }
                .onSuccess { GLog.i("筛选股票：空态 → 隐藏（列标题 / 风险提示 / 列表高度全部还原）") }
                .onFailure { GLog.w("筛选股票：收起空态失败", it) }
            return
        }

        val v = ensureEmptyView(act, lv) ?: return
        val first = v.visibility != View.VISIBLE
        if (first) {
            // ⚠️ 顺序要紧：**先把配件摘掉，再压列表**。
            // 反过来会先渲染出一帧「列表只剩头部 + 名称/最新价/涨跌幅 + 风险提示」，
            // 下一帧才收掉 —— 那就是肉眼看到的「闪一下」。
            hideListChrome(act, lv)
            runCatching { shrinkListToHeader(lv); v.visibility = View.VISIBLE }
                .onSuccess { GLog.i("筛选股票：空态（未选出符合条件的股票）→ 显示（列表压成只剩头部）") }
                .onFailure { GLog.w("筛选股票：显示空态失败", it) }
            // 宿主刷新列表时会自己把列标题行的可见性重设回来（那是它的视图），
            // 所以延后一帧再收一次；之后每轮 `applyFilter` 也都会重新对齐
            runCatching { lv.post { hideListChrome(act, lv) } }
        } else {
            // 每轮都重新对齐一次：宿主刷新列表时会自己把列标题行显示回来，收一次不够
            hideListChrome(act, lv)
        }
    }

    /**
     * 空态时把列表上的两件「配件」一并收掉：**列标题行**与**说明及风险提示**。
     *
     * 没有股票行了，`名称 / 最新价 / 涨跌幅` 和风险提示摆在空态旁边只是噪音。
     * 宿主自己也是这么做的 —— `AIStkChooseDetailAct` 在 0 只股票时 `#ll_top` 就是 `GONE`。
     *
     * 两者的收法**不一样**：
     *  - 列标题行是 header 里的普通 View → 翻 `visibility` 就塌了（`LinearLayout` 会跳过 GONE）；
     *  - 风险提示是挂在 ListView 上的固定视图（footer）→ `GONE` **不塌陷**
     *    （见 `docs/FEATURE-STG-FILTER.md` 的「走过的弯路」），只能 `removeFooterView` 摘下来。
     *
     * 幂等，所以 `applyFilter` 每轮调它都安全 —— 这是必须的：**宿主刷新列表时会把列标题行
     * 显示回来**，只在「进空态」那一刻收一次，过一会儿它自己就冒出来了。
     */
    private fun hideListChrome(act: Activity, lv: ListView) {
        hideColTitle(act, lv)
        detachRiskFooter(lv)
    }

    /** ① 列标题行：普通 View，翻 `visibility` 即可。按**当前状态**判断，不记标志位 */
    private fun hideColTitle(act: Activity, lv: ListView) {
        runCatching {
            val id = act.resources.viewIdOrZero(StgStockFilterTargets.COL_TITLE_ID)
            val col = if (id != 0) lv.findViewById<View>(id) else null
            if (col == null || col.visibility == View.GONE) return
            col.visibility = View.GONE
            GLog.i("筛选股票：列标题行（名称 / 最新价 / 涨跌幅）已收起")
        }.onFailure { GLog.w("筛选股票：收列标题行失败", it) }
    }

    /** ② 风险提示：从列表上摘下来 */
    private fun detachRiskFooter(lv: ListView) {
        if (detachedChrome.containsKey(lv)) return
        val found = findRiskChrome(lv) ?: return
        val (extra, isFooter) = found
        // 先登记再动手：removeFooterView 会回调 onChanged → 重入 applyFilter，
        // 那时若还没登记，就会对着同一块再摘一次
        detachedChrome[lv] = DetachedChrome(extra, isFooter)
        val ok = runCatching {
            if (isFooter) lv.removeFooterView(extra) else lv.removeHeaderView(extra)
        }.getOrDefault(false)
        if (ok) {
            GLog.i("筛选股票：说明及风险提示已从列表上摘下（${if (isFooter) "footer" else "header"}）")
        } else {
            detachedChrome.remove(lv)
            GLog.w("筛选股票：风险提示摘不下来，留在列表上")
        }
    }

    /** [hideListChrome] 的对偶：把两件配件放回去 */
    private fun restoreListChrome(act: Activity, lv: ListView) {
        runCatching {
            val id = act.resources.viewIdOrZero(StgStockFilterTargets.COL_TITLE_ID)
            val col = if (id != 0) lv.findViewById<View>(id) else null
            if (col != null && col.visibility != View.VISIBLE) {
                col.visibility = View.VISIBLE
                GLog.i("筛选股票：列标题行已还原")
            }
        }.onFailure { GLog.w("筛选股票：还原列标题行失败", it) }

        runCatching {
            val d = detachedChrome.remove(lv) ?: return
            if (d.isFooter) lv.addFooterView(d.view) else lv.addHeaderView(d.view)
            GLog.i("筛选股票：说明及风险提示已挂回列表")
        }.onFailure { GLog.w("筛选股票：把风险提示挂回列表失败", it) }
    }

    /**
     * 找「说明及风险提示」那一块，连带它挂在列表的哪一头。
     *
     * **优先反射读 ListView 的固定视图表**，而不是遍历 `getChildAt`：
     * ListView 只把**已经布局过**的固定视图挂成子 View —— 进空态那一刻列表还铺着几十行，
     * footer 在屏外、压根不是子 View，遍历找不到。等它被布局出来再摘，肉眼就会看到它闪一下。
     * 反射拿得到就**能在列表被压扁之前**摘掉，一帧都不闪。
     *
     * `getChildAt` 那条留作兜底（反射失败时退化成「会闪一帧」）。
     */
    private fun findRiskChrome(lv: ListView): Pair<View, Boolean>? {
        for ((field, isFooter) in listOf("mFooterViewInfos" to true, "mHeaderViewInfos" to false)) {
            val f = Reflect.findField(lv.javaClass, field) ?: continue
            val infos = runCatching { f.get(lv) as? List<*> }.getOrNull() ?: continue
            for (info in infos) {
                // FixedViewInfo.view 是 public 字段
                val view = runCatching {
                    info?.javaClass?.getField("view")?.get(info) as? View
                }.getOrNull() ?: continue
                if (isRiskChrome(view)) return view to isFooter
            }
        }
        for (i in 0 until lv.childCount) {
            val c = lv.getChildAt(i)
            if (isRiskChrome(c)) return c to true
        }
        return null
    }

    /** 这块是不是「说明及风险提示」—— 它没有 id，只能按文案前缀认 */
    private fun isRiskChrome(v: View): Boolean =
        (v as? TextView)?.text?.toString()?.trimStart()
            ?.startsWith(StgStockFilterTargets.FOOTER_TEXT_HINT) == true

    /** 被我们摘下来的那块固定视图 + 它原来挂在哪一头 */
    private class DetachedChrome(val view: View, val isFooter: Boolean)

    /**
     * 拿到（必要时先 inflate 一个）宿主的空态视图，插在 `#lvStks` 后面。
     *
     * 幂等：还挂在同一个父容器上就直接复用 —— `applyFilter` 会被反复调到，
     * 每次重新 inflate 会堆出一摞空态。
     */
    private fun ensureEmptyView(act: Activity, lv: ListView): View? {
        val parent = lv.parent as? ViewGroup
        if (parent == null) {
            GLog.w("筛选股票：列表没有父容器，空态插不进去")
            return null
        }
        emptyViewRef?.get()?.let { if (it.parent === parent) return it }

        val layoutId = runCatching {
            act.resources.getIdentifier(
                StgStockFilterTargets.LAYOUT_EMPTY, "layout", StgStockFilterTargets.TARGET_PKG,
            )
        }.getOrDefault(0)
        if (layoutId == 0) {
            GLog.w(
                "筛选股票：找不到宿主的空态布局 ${StgStockFilterTargets.LAYOUT_EMPTY}" +
                        "（App 版本可能已变）—— 筛到 0 只时列表会是一片空白"
            )
            return null
        }

        val v = runCatching {
            LayoutInflater.from(act).inflate(layoutId, parent, false)
        }.getOrNull() ?: run {
            GLog.w("筛选股票：inflate 空态布局失败")
            return null
        }

        // 竖直 LinearLayout 里「0dp + weight=1」= 吃掉别人让出来的高度。
        // 父容器不是 LinearLayout 时退回列表自己的尺寸，至少不会塌成 0。
        val lp: ViewGroup.LayoutParams = if (parent is LinearLayout) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        } else {
            copyLayoutParams(lv)
        }
        val index = parent.indexOfChild(lv)
        runCatching { parent.addView(v, if (index >= 0) index + 1 else -1, lp) }
            .onFailure {
                GLog.w("筛选股票：空态插进列表旁边失败", it)
                return null
            }
        v.visibility = View.GONE
        emptyViewRef = java.lang.ref.WeakReference(v)
        GLog.i("筛选股票：已把宿主的空态布局摆到列表下面（${StgStockFilterTargets.LAYOUT_EMPTY}）")
        return v
    }

    /**
     * 把列表压成「只剩 header」：`height` → `WRAP_CONTENT`、`weight` → 0。
     *
     * 列表带 header 且一个 item 都没有时，量出来的高度就是 header 的高度 ——
     * 于是策略名称 / 涨家数 / 标签 / 策略个股那一整块原样留着，只有股票行让了出去。
     * 原来的 LayoutParams 先存一份，[restoreListLp] 负责还原。
     */
    private fun shrinkListToHeader(lv: ListView) {
        val lp = lv.layoutParams as? LinearLayout.LayoutParams ?: run {
            GLog.w("筛选股票：列表的 LayoutParams 不是 LinearLayout 的，空态只能叠在它上面")
            return
        }
        if (!lvOriginalLp.containsKey(lv)) {
            lvOriginalLp[lv] = LinearLayout.LayoutParams(lp)
        }
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.weight = 0f
        lv.layoutParams = lp
    }

    /** 还原列表自己的 LayoutParams（[shrinkListToHeader] 的对偶） */
    private fun restoreListLp(lv: ListView) {
        val saved = lvOriginalLp.remove(lv) ?: return
        runCatching { lv.layoutParams = saved }
            .onFailure { GLog.w("筛选股票：还原列表高度失败", it) }
    }

    /** 复制一份 LayoutParams（含 `LinearLayout` 的 weight / margin），别把原实例挂到两个 View 上 */
    private fun copyLayoutParams(src: View): ViewGroup.LayoutParams {
        val lp = src.layoutParams
        return when (lp) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp)
            is ViewGroup.LayoutParams -> ViewGroup.LayoutParams(lp)
            else -> ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    /** 同一个原因只记一次 —— `ensureClickRemap` 每帧都会被调到，不这样会刷屏 */
    @Volatile private var lastSkipReason: String? = null

    private fun noteSkip(reason: String) {
        if (lastSkipReason == reason) return
        lastSkipReason = reason
        GLog.w("筛选股票：暂时没装行点击回映射 —— $reason")
    }

    /**
     * 给 ListView 的行点击装一层「下标回映射」。
     *
     * ## 为什么必须做
     *
     * ListView 上挂的是我们的过滤代理，`position` 是**子集下标**；而宿主的点击处理
     * 是拿 `position` 去索引**它自己那份全量列表**（实测：不做映射时点列表第 1 行
     * 「华建集团」，打开的是全量第 1 只「莱美药业」）。
     * 所以要还原成「全量下标」再交给宿主 —— 用 [Snapshot.keptIndices] 换算。
     *
     * 做法是把宿主原来的 `OnItemClickListener`（`AdapterView.mOnItemClickListener`
     * 是隐藏 API，得反射取）包一层。没装筛选时下标表为空，等价于原样透传。
     */
    private fun ensureClickRemap() {
        val act = trackedActivity() ?: return noteSkip("没有 trackedActivity")
        val lv = findListView(act) ?: return noteSkip("找不到列表控件")
        var adapter = unwrapHeaderAdapter(lv.adapter) ?: return noteSkip("列表还没有 adapter")
        // 列表上可能是我们的过滤代理 —— 认它包着的那个原适配器
        // （热重载后可能是旧实例的代理，`is FilteredAdapter` 认不出，按类名拆包）
        adapter = unwrapProxy(adapter) ?: return noteSkip("过滤代理取不到原适配器")
        if (!StgStockFilterTargets.isTargetAdapter(adapter.javaClass.name)) {
            return noteSkip("adapter 不是目标：${adapter.javaClass.name}")
        }

        val existing = itemClickListener(lv)
        if (existing != null && wrappedLists[lv] === existing) return   // 已经包过

        if (existing == null) {
            // 宿主没走 ListView 的行点击（比如它自己给每行设了 OnClickListener），
            // 那我们包了也没人接 —— 记一条，排查「点了没反应」时先看它。
            GLog.w("筛选股票：ListView 上没有宿主自己的行点击监听，回映射无处可转发")
        }

        val wrapper = AdapterView.OnItemClickListener { parent, view, position, id ->
            var mapped = position
            runCatching {
                val header = lv.headerViewsCount
                // 每次都重新读：两次点击之间筛选结果可能已经换过，
                // 而 kept 与 keptIndices 是一起原子替换的，不会读到错位组合。
                val idx = stateOf(adapter).snap?.keptIndices?.getOrNull(position - header)
                if (idx != null) mapped = header + idx
            }.onFailure { GLog.w("筛选股票：点击下标回映射失败", it) }
            val sink = existing
            if (sink == null) {
                GLog.w("筛选股票：没有宿主的行点击监听可转发（position=$position）")
            } else {
                runCatching { sink.onItemClick(parent, view, mapped, id) }
                    .onFailure { GLog.w("筛选股票：转发行点击失败", it) }
            }
        }
        runCatching { lv.onItemClickListener = wrapper }
            .onSuccess { wrappedLists[lv] = wrapper }
            .onFailure { GLog.w("筛选股票：包裹行点击监听失败", it) }
    }

    /**
     * 拿策略详情页那个列表。
     *
     * 结果缓一份：`ensureClickRemap` 会被每次 `getView` 调到（用来发现宿主把我们的
     * 行点击监听顶掉的情况），每帧都走一遍 `findViewById` 太浪费。
     * 缓存的是弱引用，Activity 重建 / 视图被回收后自动失效。
     */
    private fun findListView(act: Activity): ListView? {
        trackedList.get()?.get()?.let { lv ->
            // 必须还挂在**当前这个** Activity 的窗口上 —— 页面重建之后旧列表可能仍是
            // attached 状态，拿它去操作等于对着一块已经不在屏幕上的列表干活。
            if (lv.isAttachedToWindow && lv.rootView === act.window?.decorView) return lv
        }
        val blockId = listViewId(act)
        if (blockId == 0) return null
        val lv = act.findViewById<View>(blockId) as? ListView ?: return null
        trackedList.set(java.lang.ref.WeakReference(lv))
        return lv
    }

    /** 读宿主原本挂在 ListView 上的 `OnItemClickListener`（`AdapterView.getOnItemClickListener` 是隐藏 API） */
    private fun itemClickListener(lv: ListView): AdapterView.OnItemClickListener? =
        runCatching {
            val f = clickListenerField ?: AdapterView::class.java
                .getDeclaredField("mOnItemClickListener")
                .also {
                    it.isAccessible = true
                    clickListenerField = it
                }
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

    /**
     * 当前策略详情列表**真正在用**的那个适配器（是我们的过滤代理就拆包取原适配器）。
     *
     * 为什么要这么取：`states` 是按适配器实例存的，页面重建 / 宿主换适配器之后，
     * 里面会同时留着**旧实例**。旧实例的 notify 与 getView 回调仍然会打到我们身上，
     * 要是照着它去给当前 ListView 装代理，等于把列表的数据源换成一份过期内容
     * （返回详情页时旧实例的数据多半已经被清空了）—— 表现就是**整列股票刷不出来**，
     * 而且因为 `WeakHashMap` 的遍历顺序不定，只在某些时候才撞上。
     */
    private fun liveAdapter(act: Activity): Any? {
        val lv = findListView(act) ?: return null
        val a = unwrapHeaderAdapter(lv.adapter) ?: return null
        // 列表上可能是我们的过滤代理（热重载后也可能是旧实例的）—— 拆包取原适配器
        return unwrapProxy(a)
    }

    private fun onActivityResumed(env: FeatureEnv, activity: Activity) {
        if (!StgStockFilterTargets.isWatchActivity(activity.javaClass.name)) return
        trackedActivityRef.set(java.lang.ref.WeakReference(activity))
        cachedFilters = null                       // 回到本页重新读一次筛选状态
        ensureClickRemap()
        // 只对齐「当前列表在用的那个适配器」，别去碰 states 里的历史实例
        val a = liveAdapter(activity) ?: return
        runCatching { applyFilter(env, a, notify = true) }
            .onFailure { GLog.w("筛选股票：onResume 对齐失败", it) }

        // 从个股页返回时，宿主通常还在重新拉数据：上面这次对齐拿到的多半是空列表
        // （于是什么代理都没装上）。数据到位后如果没走 notifyDataSetChanged
        // （宿主可能只刷新可见行），代理就永远装不回去，列表整列空着。
        // 所以延后再复查一次 —— 代理不在列表上就补装，装上了就什么都不做（幂等）。
        val handler = activity.window?.decorView?.handler ?: return
        handler.postDelayed({
            runCatching {
                val now = liveAdapter(activity) ?: return@runCatching
                applyFilter(env, now, notify = true)
                ensureClickRemap()
            }.onFailure { GLog.w("筛选股票：onResume 二次对齐失败", it) }
        }, RESUME_RECHECK_MS)
    }

    private fun onFilterApplied(env: FeatureEnv, activity: Activity, applied: StgFilters) {
        trackedActivityRef.set(java.lang.ref.WeakReference(activity))
        cachedFilters = applied
        lastSummary = null
        ensureClickRemap()
        GLog.i("筛选股票：筛选条件已生效（${StgFilterPanel.describe(applied)}）")
        val a = liveAdapter(activity) ?: return
        runCatching { applyFilter(env, a, notify = true) }
            .onFailure { GLog.w("筛选股票：应用筛选失败", it) }
    }

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        cachedFilters = null
        val act = trackedActivity() ?: return
        val a = liveAdapter(act) ?: return
        runCatching { applyFilter(env, a, notify = true) }
            .onFailure { GLog.w("筛选股票：配置变化后重算失败", it) }
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

        /** 我们自己写进 `#tv_stg_res_num` 的格式：「策略个股 3/15」 */
        val OUR_RES_NUM = Regex("""^策略个股 \d+/\d+$""")

        /**
         * `onResume` 之后延后多久再复查一次代理有没有装上。
         * 从个股页返回时宿主还在重新拉数据，得给它一点时间；已经装上就幂等早退。
         */
        const val RESUME_RECHECK_MS = 600L

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
