package io.github.MVennn.GSAuxiliary.feature.stgfilter

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.filter.Category
import io.github.MVennn.GSAuxiliary.feature.filter.FilterRow
import io.github.MVennn.GSAuxiliary.feature.filter.StockFilterStore
import io.github.MVennn.GSAuxiliary.feature.filter.CodeMarketParts
import io.github.MVennn.GSAuxiliary.feature.filter.StockFilters
import io.github.MVennn.GSAuxiliary.feature.filter.StockTypes
import io.github.MVennn.GSAuxiliary.feature.filter.describe
import io.github.MVennn.GSAuxiliary.feature.watchlist.ZxWatchlist
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method
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
 * 见 [StockFilterStore]：存**目标 App 自己的** `SharedPreferences`
 * （remote preferences 在目标进程是只读的，写不进去）。
 *
 * ## 两条维度、两组开关（2.18.0 起与行情页「沪深京A」漏斗合并成一套）
 *
 * 面板里的八行分两组，语义与落盘都收在 [StockFilters] 里：
 *
 * ```
 * 涨跌与自选（策略侧原有）          未勾选的类别不显示
 *   涨家股票 / 跌家股票 / 自选股票    自选优先、平盘不属于任何类别
 * 股票类型（行情页漏斗原有）         有一个类型没勾就排除
 *   创业板 / 科创板 / 北证A / 未开板次新 / ST股
 * ```
 *
 * 两只维度之间取**交集**；两组都不筛（方向全不勾 + 类型全勾）时列表恢复全量。
 * 类型怎么判（代码前缀 / 名称 / 行情数据字段）见 [StockTypes]。
 *
 * ## 首帧不能闪全量（2.18.0 修）
 *
 * 筛选生效时点进详情页，列表**第一帧就得是筛过的**。数据有两条到列表的路：
 *
 *  - 宿主 `data.clear(); data.addAll(...); notifyDataSetChanged()` —— 挂
 *    `BaseAdapter.notifyDataSetChanged` 的**前置**钩子，放行前先把过滤代理装好；
 *  - 宿主把**已经填好数据**的适配器直接 `setAdapter(...)` 挂上来 —— 这条原先没人管，
 *    列表先按全量布局一帧，等 `getView` 的兜底补筛（延后一帧）才变筛过的，
 *    肉眼就是「闪几帧未筛选的列表」（2026-10-19 用户报的现象）。
 *    现在挂了 `ListView.setAdapter` 的**后置**钩子：装完代理再让这一帧过去。
 *
 * 顺手还有个副作用是好的：`setAdapter` 在 `onCreate` 里发生时，
 * 这个钩子会把「当前在看的 Activity / 列表」提前认下来 —— 之前只有 `onResume`
 * 之后才认，宿主早于 `onResume` 发的通知一律够不着。
 *
 * ## 方向那三个开关的语义（**互斥类别**，2.16.1 起没变）
 *
 * 需求明确：**未勾选的类别、其对应股票一律不显示**。
 * 所以先把每只股票归到**唯一**一个类别，再看该类别的开关有没有勾
 * （判定实现在 [StockFilters.keepsDirection]，这里只留结论）：
 *
 * ```
 * 类别(股票) =
 *     自选股票  在自选股里（**最高优先**）
 *     涨家股票  否则 zdf > 0
 *     跌家股票  否则 zdf < 0
 *     平盘      其余（zdf == 0 或读不到）
 *
 * 显示(股票) = 没有任何方向开关勾选 ? 只看类型 : 类别(股票) ∈ 已勾选的类别
 * ```
 *
 * 举例（都是需求里的例子与推论）：
 *  - 只勾「涨家股票」→ 涨的要有、跌的不要、**自选里即使涨着也不要**（自选是另一个类别）；
 *  - 只勾「自选股票」→ 只有自选里的；
 *  - 勾「涨家股票 + 自选股票」→ 涨的（非自选）+ 自选里的；
 *  - 三个都不勾 → 这个维度不筛（类型维度照旧）。
 *
 * ⚠️ **平盘股（zdf == 0）不属于任何类别**，所以只要开了方向筛选就看不到它 ——
 * 这是"未勾选的类别不显示"的直接推论（没有"平盘"这个开关）。
 */
class StgStockFilterFeature : Feature {

    override val id = "stg_stock_filter"
    override val title = "策略详情筛选股票"

    /** 面板装配：三点菜单入口的注入 / 撤销与样式克隆，见 [StgFilterPanelBuild] */
    private val panelBuild = StgFilterPanelBuild(this)

    /** 缓存资源 id（0 = 还没解析过） */
    @Volatile private var percentIdCache = 0

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

    /**
     * 已经包过行点击回映射的 ListView → [ClickWrap]（用来避免重复包裹）。
     *
     * ⚠️ **必须连宿主的 original 一起存**（规则 1.1）：
     * 只存 wrapper 的话**撤不掉** —— 宿主的行点击上永久多一层转发；
     * 而热重载后新实例的表是空的 → 判据不成立 → **再包一层**，
     * N 次热重载 = N 层转发链（每层都还在跑 keptIndices 查表）。
     * （`StgSelectAllFeature` 那边早就改成 `Wrap(wrapper, original)` 了，
     *   这里是同一形状的问题，两边得一样。）
     */
    private val wrappedLists = Collections.synchronizedMap(
        WeakHashMap<ListView, ClickWrap>()
    )

    /** 一次行点击包装：我们的 wrapper + 宿主的原监听 */
    private class ClickWrap(
        val wrapper: AdapterView.OnItemClickListener,
        val original: AdapterView.OnItemClickListener?,
    )

    /** 策略详情页 ListView 的资源 id 缓存 */
    @Volatile private var listIdCache = 0

    /** 左上角「策略个股N只」那颗计数的资源 id 缓存 */
    @Volatile private var resNumIdCache = 0

    /** 列标题行（名称 / 最新价 / 涨跌幅）的资源 id 缓存 —— 收放空态时每轮都要查，见 [colTitleId] */
    @Volatile private var colTitleIdCache = 0

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

    // ------------------------------------------------------------------ 反射缓存（规则 2.1）

    /**
     * 反射解析结果槽：`null` 也要缓存，所以不能直接把结果塞进 map
     * （`WeakHashMap` 的 `get` 分不清「没有这个键」和「值是 null」）。
     */
    private class Slot<T>(val value: T?)

    /** 适配器类 → `getItem(int)`（没有也是结果，缓存哨兵） */
    private val getItemCache = Collections.synchronizedMap(WeakHashMap<Class<*>, Slot<Method>>())

    /** 适配器类 → `data` 字段 */
    private val dataFieldCache = Collections.synchronizedMap(WeakHashMap<Class<*>, Slot<Field>>())

    /** 任意类 → 拆包方法（`getWrappedAdapter` / `getTarget` 各一张表，按类缓存） */
    private val headerUnwrapCache = Collections.synchronizedMap(WeakHashMap<Class<*>, Slot<Method>>())
    private val proxyUnwrapCache = Collections.synchronizedMap(WeakHashMap<Class<*>, Slot<Method>>())

    /** 条目类 → 解析好的候选字段（按候选顺序；`null` 项 = 该类没有这个字段） */
    private class ItemFields(
        val cam: List<Field?>,
        val code: List<Field?>,
        val market: List<Field?>,
        val percent: List<Field?>,
        val name: List<Field?>,
        /** 「类型布尔字段 → 类型位」候选（2.18.0 起判创业板 / 科创板 / 未开板次新） */
        val typeFlags: List<Pair<Field?, Int>>,
    )

    private val itemFieldsCache = Collections.synchronizedMap(WeakHashMap<Class<*>, ItemFields>())

    /**
     * 条目类的候选字段**解析一次**。
     *
     * 这是规则 2.1 的直接要求：`Reflect.findField` 沿继承链逐个 `getDeclaredField` 试错、
     * 未命中靠抛异常（填栈），而 [codeAndMarket] / [percentOf] 在**每行**都要跑
     * （筛选生效时每次 `notifyDataSetChanged` 还要对全量重跑一遍）。按类缓存之后，
     * 每行的成本只剩「对已解析的 `Field` 做一次 `get`」，继承链不再重扫。
     *
     * 语义与原先的 `Reflect.readField(item, 候选…)` 一致：候选顺序不变、空值照样跳过，
     * 只是把「找字段」这一步提出循环。
     */
    private fun fieldsOf(cls: Class<*>): ItemFields = synchronized(itemFieldsCache) {
        itemFieldsCache.getOrPut(cls) {
            ItemFields(
                cam = StgStockFilterTargets.CAM_FIELDS.map { Reflect.findField(cls, it) },
                code = StgStockFilterTargets.CODE_FIELDS.map { Reflect.findField(cls, it) },
                market = StgStockFilterTargets.MARKET_FIELDS.map { Reflect.findField(cls, it) },
                percent = StgStockFilterTargets.PERCENT_FIELDS.map { Reflect.findField(cls, it) },
                name = StgStockFilterTargets.NAME_FIELDS.map { Reflect.findField(cls, it) },
                typeFlags = StgStockFilterTargets.TYPE_FLAG_FIELDS.map { (n, bit) ->
                    Reflect.findField(cls, n) to bit
                },
            )
        }
    }

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
         *
         * 走下标而不是 `getOrNull`：后者返回 `Int?`，**每行都要装箱一次**
         * （2026-10-19；这个方法在每次 `getView` / `getItem` 上）。
         */
        private fun mapped(position: Int): Int {
            val idx = indices
            return if (position >= 0 && position < idx.size) idx[position] else position
        }

        /**
         * `StgFilterBridge.runWithRemapBypassed` 里的 position **已经是全量下标**
         * （「全选」的合成点击、手动点击搬运 checked states 时）。
         *
         * 这两个方法必须一起放行：
         *  - `getItemId`：`AbsListView.setItemChecked(pos=全量)` 会用它维护
         *    `mCheckedIdStates`；再映射一次就会记成**另一只股票**的 id，
         *    stable-id 的列表下次布局会按 id 表把勾选复活到子集坐标上。
         *  - `getItem`：宿主若在点击回调里用 `parent.getItemAtPosition(全量下标)` 取值，
         *    再映射一次会读到别的股票。
         */
        override fun getItem(position: Int): Any? =
            if (StgFilterBridge.isRemapBypassed()) base.getItem(position) else base.getItem(mapped(position))

        override fun getItemId(position: Int): Long =
            if (StgFilterBridge.isRemapBypassed()) base.getItemId(position) else base.getItemId(mapped(position))

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

        /**
         * 过滤生效期间**一律不认 stable id**。
         *
         * 代理改变了 position 的语义（子集 ↔ 全量），而 `AbsListView` 的 id 表
         * （`mCheckedIdStates`）是按 **position** 存、在 `handleDataChanged()` 里用
         * `mAdapter.getItemId(position)` **反查**重建的。只要 id 表和 checked states
         * 用的不是同一套坐标，下一次布局 `confirmCheckedPositionsById()` 就会把勾选
         * 挪到别的行（或直接删掉并减计数）。与其维护两套坐标的 id 表，不如在筛选期间
         * 关掉 id 表：checked states 仍然照常工作，宿主的多选模型读的也是它。
         */
        override fun hasStableIds(): Boolean = false

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
     *
     * ⚠️ **这条判据在 release 包里曾经是失效的**（2026-10-19 真机定位）：模块自己
     * 也开 R8，`FilteredAdapter` 会被混淆成 `f2.m`，于是「类名里含 FilteredAdapter」
     * 认不出**自己刚装上去的**代理 —— 表现为「按『确定』筛选不生效、退出重进才生效」
     * （重进时列表上还是宿主的原适配器，那条路不需要认代理）。
     * 现在两条腿走路：本实例靠 [proxiedLists] **身份直判**（不依赖任何名字），
     * 跨实例才退回这里；类名与 `getTarget()` 由 proguard 的 `-keepnames` 保住
     * （见 `app/proguard-rules.pro`）。
     */
    private fun isFilteredAdapter(a: Any?): Boolean =
        a != null && "FilteredAdapter" in a.javaClass.name

    /**
     * 列表 → 我们装上去的过滤代理 + 它包着的原适配器。
     *
     * 身份直判的依据：代理是**我们自己**放进这个列表的，记账比类名可靠得多
     * （类名会被 R8 混淆，见 [isFilteredAdapter]）。弱引用键，列表回收后自然失效。
     */
    private class ProxyRecord(val proxy: Any, val target: Any)

    private val proxiedLists = Collections.synchronizedMap(WeakHashMap<ListView, ProxyRecord>())

    /** 这个 adapter 是不是我们装在这个列表上的过滤代理（身份判据，不依赖类名） */
    private fun isProxiedOnList(lv: ListView, a: Any?): Boolean =
        a != null && synchronized(proxiedLists) { proxiedLists[lv]?.proxy === a }

    /**
     * 列表上那个 adapter 若是我们装的过滤代理，返回它包着的原适配器；不是就原样返回。
     *
     * 先按**身份**判（本实例装的），认不出再退回 [unwrapProxy] 的类名拆包
     * （热重载后旧实例留在列表上的代理只能这么认）。
     */
    private fun unwrapOnList(lv: ListView, a: Any): Any? {
        val rec = synchronized(proxiedLists) { proxiedLists[lv] }
        if (rec != null && rec.proxy === a) return rec.target
        return unwrapProxy(a)
    }

    /**
     * 取出过滤代理包着的原适配器；不是代理就原样返回。
     *
     * 兼容旧实例的代理：那种代理不是当前 ClassLoader 里的 [FilteredAdapter]，
     * 只能反射调 `getTarget()`（Kotlin 属性生成的公开方法，热重载前后签名一致）。
     * 反射失败返回 null，调用方按「现场认不出来」安全退避。
     */
    private fun unwrapProxy(a: Any): Any? {
        if (!isFilteredAdapter(a)) return a
        val m = synchronized(proxyUnwrapCache) {
            proxyUnwrapCache.getOrPut(a.javaClass) { Slot(Reflect.findMethod(a.javaClass, "getTarget")) }
        }.value ?: return null
        return runCatching { m.invoke(a) }.getOrNull()
    }

    // ------------------------------------------------------------------ 安装

    override fun install(env: FeatureEnv, bag: HookBag) {
        // 「自选」判定复用与「自选高亮」完全相同的国信门面方法
        ZxWatchlist.init(env.classLoader)

        var hooked = 0
        hooked += hookPopupShow(env, bag)
        hooked += hookNotifyDataSetChanged(env, bag)
        hooked += hookGetView(env, bag)
        hooked += hookListViewSetAdapter(env, bag)
        hooked += hookActivityResume(env, bag)

        if (hooked == 0) {
            GLog.e("筛选股票：没有任何钩子装上（宿主类名可能已变，用 tools/probe 重新定位）")
        }
    }

    /**
     * 挂 `android.widget.ListView#setAdapter` —— **首帧不闪全量的那一半**。
     *
     * 宿主把**已经填好数据**的适配器直接挂上来时（`onCreate` 里 `lvStks.adapter = ...`
     * 是常见写法），`notifyDataSetChanged` 那条路根本不会走：列表会先按全量布局一帧
     * （用户看到的就是「闪几帧未筛选的列表」），要等 `getView` 的兜底补筛
     * （延后一帧）才变成筛过的。
     *
     * 这里用**后置**钩子：`setAdapter` 正常跑完（ListView 换掉适配器、请求布局），
     * 紧接着把过滤代理装上去 —— 中间没有布局机会，第一帧就是筛过的。
     *
     * 顺带把「当前在看的 Activity / 列表」认下来：`setAdapter` 通常发生在
     * `onCreate`，比 `onResume` 早，等于给所有钩子提前打通了上下文
     * （原先只有 `onResume` 之后才认，宿主早于它发的通知一律够不着）。
     *
     * 只认 `Package` 里的候选列表类名（`isTargetAdapter`），不是目标适配器一律放过 ——
     * 这个钩子挂在框架类上，全 App 的 ListView 都会进来。
     */
    private fun hookListViewSetAdapter(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass("android.widget.ListView", env.classLoader) ?: return 0
        val methods = Reflect.methodsNamed(cls, "setAdapter")
        for (m in methods) {
            bag.add(
                env.xposed.hook(m)
                    .setId("stgfilter:setAdapter")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val self = chain.thisObject
                            val a = chain.getArg(0)
                            if (self is ListView && a != null) onAdapterSet(env, self, a)
                        }.onFailure { GLog.w("筛选股票：setAdapter 后处理异常", it) }
                        result
                    }
            )
        }
        if (methods.isNotEmpty()) GLog.i("筛选股票：已挂 ListView.setAdapter ×${methods.size}")
        return methods.size
    }

    /** `ListView.setAdapter` 的后处理：认页面 + 立刻把筛选追上去 */
    private fun onAdapterSet(env: FeatureEnv, lv: ListView, adapter: Any) {
        if (!env.config.stgStockFilter) return
        if (!StgStockFilterTargets.isTargetAdapter(adapter.javaClass.name)) return
        // 页面从列表自己的 Context 认（onCreate 里 setAdapter 时 onResume 还没到，
        // trackedActivity 多半还指着上一页 —— 拿它去操作等于对着别的页面干活）
        val act = findActivity(lv.context) ?: return
        if (!StgStockFilterTargets.isWatchActivity(act.javaClass.name)) return
        trackedActivityRef.set(java.lang.ref.WeakReference(act))
        runCatching {
            if (lv.isAttachedToWindow && lv.rootView === act.window?.decorView) {
                trackedList.set(java.lang.ref.WeakReference(lv))
            }
        }
        if (!filtersBy(act).active) return
        // notify=false：setAdapter 自己会请求一次布局，我们不必再插一次「数据变了」
        runCatching { applyFilter(env, adapter, notify = false) }
            .onFailure { GLog.w("筛选股票：setAdapter 后立即筛选失败", it) }
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
                                if (self is PopupWindow) panelBuild.maybeInjectMenuItem(env, self)
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
                            val self = chain.thisObject
                            // 先做最便宜的判据再取参数：这个钩子挂在**基类**上，
                            // 全 App 所有列表的每一行都会进来，非目标适配器不该付
                            // 「物化整条参数表」的钱（`chain.args` 返回一个 List，
                            // 而我们要的只是第 0 个参数 —— 2026-10-19）
                            if (self != null &&
                                StgStockFilterTargets.isTargetAdapter(self.javaClass.name)
                            ) {
                                runCatching {
                                    onGetView(env, self, (chain.getArg(0) as? Int) ?: -1, result)
                                }.onFailure { GLog.w("筛选股票：getView 后处理异常", it) }
                            }
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

    // ------------------------------------------------------------------ 数据交换

    private fun onGetView(env: FeatureEnv, adapter: Any, position: Int, result: Any?) {
        // ① 顺手记一份行内涨跌幅文案（数据项 zdf 读不到时的兜底）
        val row = result as? View
        if (row != null) recordRowPercent(adapter, position, row)

        // ② 行点击回映射要**常驻**：宿主随时可能在刷新流程里重新
        //    `setOnItemClickListener(...)`，把我们的 wrapper 顶掉 ——
        //    之后点击就按「子集下标」解释，点开的是别的股票。
        //    ensureClickRemap 自己幂等，Field 与 ListView 都有缓存，这里开销很小。
        //    ⚠️ 外层必须有日志：装不上时行点击会按子集下标解释 → **点错股票**，
        //    而症状对用户只是「点开的不对」，没有日志就完全查不出来
        //    （`docs/CODE-RULES.md` §3.3：每处早退都要能自证原因）。
        runCatching { ensureClickRemap() }
            .onFailure { GLog.w("筛选股票：行点击回映射安装失败（点行可能点错股票）", it) }

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
        val ctx = trackedActivity() ?: return noteApplySkip("没有 trackedActivity")
        val lv = trackedList.get()?.get() ?: findListView(ctx) ?: return noteApplySkip("找不到列表控件")
        val base = adapter as? android.widget.ListAdapter ?: return noteApplySkip("适配器不是 ListAdapter")

        // 保险：只给「当前列表在用的那个适配器」装代理。
        // 宿主换过适配器 / 页面重建之后，旧实例的 notify 与 getView 回调仍会打到我们身上，
        // 照着旧实例装代理会把列表的数据源换成过期内容（见 [liveAdapter]）。
        // 列表还没有适配器时（live == null）放行 —— 那是首次安装的场景。
        // 列表上挂着的是我们的过滤代理就先拆包（本实例按身份、热重载后按类名，见 [unwrapOnList]）。
        val liveOnList = unwrapHeaderAdapter(lv.adapter)
        if (liveOnList != null && liveOnList !== adapter) {
            val liveBase = unwrapOnList(lv, liveOnList)
                ?: return noteApplySkip("列表上挂着过滤代理但拆不出原适配器")
            if (liveBase !== adapter) {
                return noteApplySkip(
                    "列表在用的适配器与传入的不是同一个（列表上=${liveOnList.javaClass.name}）"
                )
            }
        }

        val st = stateOf(adapter)

        // 宿主的全量数据：**只读，一个字节都不改**（理由见 [FilteredAdapter] 的注释）
        val full = readData(adapter) ?: return noteApplySkip("读不到适配器的 data 字段（${adapter.javaClass.name}）")
        val active = env.config.stgStockFilter && filtersBy(ctx).active

        // 功能关掉 / 三个都不勾 / 数据还没到 → 把 ListView 换回原适配器
        if (!active || full.isEmpty()) {
            if (full.isEmpty()) noteApplySkip("宿主的 data 还是空的（先 clear 后回包的中间态）")
            else noteApplySkip("当前没有生效的筛选条件")
            // 数据还没到（`full` 空）时什么都不动 —— 那多半是宿主「先 clear 再等回包」的中间态，
            // 照着它把计数写成 0 只、把空态亮出来，都会在下一次刷新时闪一下
            if (full.isNotEmpty()) {
                updateResNum(ctx, full.size, full.size, active = false)
                updateEmptyView(ctx, empty = false)
            }
            // 审查 2026-10-17 高-3：还原判据不能只看本实例的记账表（`st.proxy`）——
            // 热重载后 ListView 上可能还挂着**旧实例**装的 FilteredAdapter，新实例的
            // 记账表是空的，只查表就永远走不到 `lv.adapter = base`，筛选残留不清。
            // 这里补两个「现场」判据：① 本实例的 [proxiedLists] 记账（身份，不依赖类名）；
            // ② 类名兜底（跨实例，见 [isFilteredAdapter]）。
            val liveOnList = unwrapHeaderAdapter(lv.adapter)
            if (st.proxy != null || isProxiedOnList(lv, liveOnList) || isFilteredAdapter(liveOnList)) {
                runCatching { lv.adapter = base }
                    .onFailure { GLog.w("筛选股票：还原适配器失败", it) }
                st.proxy = null
                st.snap = null
                synchronized(proxiedLists) { proxiedLists.remove(lv) }
                StgFilterBridge.publish(null)
                lastPublishSkip = null
                lastSummary = null
                GLog.i("筛选股票：筛选未生效，已还原全量（${full.size} 只）")
            } else if (StgFilterBridge.hasSnapshot()) {
                // 当前页没有代理，但桥里还留着上一页 / 列表重建前的快照。
                // 现在明确不筛 → 清掉，免得直达行情每次点击都判成「快照不属于当前列表」。
                StgFilterBridge.publish(null)
                lastPublishSkip = null
                GLog.i("筛选股票：筛选未生效，已清掉残留的下标表")
            }
            // 过滤代理已经不在列表上 → 行点击的回映射也没有存在意义，拆回宿主自己的监听
            // （规则 1.1；否则列表上永久多一层透传，热重载后还会再叠一层）。
            unwrapRowClick(lv)
            return
        }

        val f = filtersBy(ctx)
        val keep = ArrayList<Any>(full.size)
        val keptIdx = ArrayList<Int>(full.size)
        var up = 0
        var down = 0
        var zx = 0
        var flat = 0
        val typeHits = IntArray(FilterRow.TYPE_ROWS.size)
        for ((i, item) in full.withIndex()) {
            // 一只股票的两个维度一次算清：方向类别（自选优先 / 平盘独立）+ 类型位掩码
            //
            // ⚠️ **纯重构，判定一字未变**：把每项要算 3 次的 fieldsOf 合成 1 次、
            // 「代码.市场」只切一次（原来自选判定切一遍、类型判定又切一遍）、
            // 类型行的下标用预计算的序号（原���每项 5 次 `indexOf` 线性查找）。
            val fields = fieldsOf(item.javaClass)
            val cam = codeAndMarket(item, fields)
            // ⚠️ 切分走 [CodeMarketParts]（生产代码与单测**共用**）——
            // 口径必须与 `StockTypes.byCodeAndMarket(cam, name)`（2 参重载）逐字等价：
            //   · 有点 → code = 点前、market = 点后；
            //   · **无点 → 整串当 code、market = null**（不是 code = null！）——
            //     差这一点的后果是「代码前缀判定（创业板 / 科创板 / 北证A）整段失效」，
            //     宿主 `codeAndMarket` 出现「只有代码没市场号」的中间态时就会踩到
            //     （`StockInfo` 是构造后填充的，部分填充是真实存在的形态）。
            val (code, market) = CodeMarketParts.split(cam)
            val inZx = isInWatchlist(code, market)
            val pct = percentOf(adapter, item, cam, fields)
            when (StockFilters.categoryOf(inZx, pct)) {
                Category.ZX -> zx++
                Category.UP -> up++
                Category.DOWN -> down++
                Category.FLAT -> flat++
            }
            val types = typesOf(item, code, market, fields)
            for (row in FilterRow.TYPE_ROWS) {
                if (StockTypes.has(types, row)) typeHits[row.typeIndex]++
            }
            if (f.keepsDirection(inZx, pct) && f.keepsTypes(types)) {
                keep.add(item)
                keptIdx.add(i)
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
        // ⚠️ 必须连**下标表**一起比：宿主重排 / 前插 / 删除后保留项可能还是那几只，
        // 但 `rows` 变了；只比 `keep` 就会跳过 `proxy.update(rows)`，代理仍按旧下标
        // 渲染（屏幕上显示错股票），而桥却发布了新表。
        if (proxy != null && snap != null && sameList(keep, snap.kept) &&
            rows.contentEquals(snap.keptIndices) &&
            unwrapHeaderAdapter(lv.adapter) === proxy
        ) {
            lastPublishSkip = null
            StgFilterBridge.publish(keep, rows, full, proxy)
            return
        }

        // 下标表与结果一起替换（点击回映射读的是这一份，不能出现「新结果 + 旧下标」）
        st.snap = Snapshot(keep, rows)

        if (proxy == null) {
            val p = FilteredAdapter(base, rows)
            st.proxy = p
            runCatching {
                lv.adapter = p
                synchronized(proxiedLists) { proxiedLists[lv] = ProxyRecord(p, base) }
            }.onFailure {
                GLog.w("筛选股票：装过滤代理失败", it)
                st.proxy = null
                synchronized(proxiedLists) { proxiedLists.remove(lv) }
            }
        } else {
            proxy.update(rows)
            if (unwrapHeaderAdapter(lv.adapter) !== proxy) {
                // 宿主自己 setAdapter 过一次 → 代理被顶掉，重装
                runCatching {
                    lv.adapter = proxy
                    synchronized(proxiedLists) { proxiedLists[lv] = ProxyRecord(proxy, base) }
                }.onFailure { GLog.w("筛选股票：重装过滤代理失败", it) }
            }
        }

        // 只有过滤代理**确实挂在列表上**时才发布下标表。
        // 发布了下标表却没有代理时，列表上还是全量数据，而「全选」会按这张表
        // 把子集下标当全量下标用 —— 点到别的股票上（比不筛更糟）。
        if (st.proxy != null && unwrapHeaderAdapter(lv.adapter) === st.proxy) {
            lastPublishSkip = null
            StgFilterBridge.publish(keep, rows, full, st.proxy)
        } else {
            // 这一支不能静默：下游会退化成「全量」/「放弃全选」，日志里必须有原因
            val why = if (st.proxy == null) "过滤代理没装上" else "列表上挂的不是我们的代理"
            if (lastPublishSkip != why) {
                lastPublishSkip = why
                GLog.w("筛选股票：下标表未发布 —— $why（全选/直达行情按全量处理）")
            }
            // 代理没挂在列表上 → 列表上是全量数据，行点击**不能**再按子集下标回映射，
            // 否则手动点行会选中另一只股票（点击 wrapper 读的就是 st.snap）。
            st.snap = null
            StgFilterBridge.publish(null)
        }
        if (notify) notifyAdapter(adapter)

        val summary = buildString {
            append("全量 ${full.size} → 保留 ${keep.size}（涨 $up / 跌 $down / 自选 $zx / 平盘 $flat")
            for (row in FilterRow.TYPE_ROWS) {
                append(" / ").append(row.label).append(' ').append(typeHits[row.typeIndex])
            }
            append("）")
        }
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

    /** `data` 字段的解析结果也按适配器类缓存（[itemAt] 的兜底分支与 [readData] 都要用） */
    private fun readData(adapter: Any): List<Any>? {
        val f = synchronized(dataFieldCache) {
            dataFieldCache.getOrPut(adapter.javaClass) {
                Slot(Reflect.findField(adapter.javaClass, DATA_FIELD))
            }
        }.value ?: return null
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
            val id = colTitleId(act)
            val col = if (id != 0) lv.findViewById<View>(id) else null
            if (col == null || col.visibility == View.GONE) return
            col.visibility = View.GONE
            GLog.i("筛选股票：列标题行（名称 / 最新价 / 涨跌幅）已收起")
        }.onFailure { GLog.w("筛选股票：收列标题行失败", it) }
    }

    /** 列标题行的资源 id（解析一次就缓存；空态期间每轮都要用） */
    private fun colTitleId(act: Activity): Int {
        var id = colTitleIdCache
        if (id != 0) return id
        id = act.resources.viewIdOrZero(StgStockFilterTargets.COL_TITLE_ID)
        if (id != 0) colTitleIdCache = id
        return id
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
            val id = colTitleId(act)
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

    /** [applyFilter] 上一次的早退原因（同款去重） */
    @Volatile private var lastApplySkip: String? = null

    /** 「算出了结果但代理不在列表上」的上一次原因（同款去重；发布成功时清空） */
    @Volatile private var lastPublishSkip: String? = null

    private fun noteSkip(reason: String) {
        if (lastSkipReason == reason) return
        lastSkipReason = reason
        GLog.w("筛选股票：暂时没装行点击回映射 —— $reason")
    }

    /**
     * [applyFilter] 早退的原因（同一原因只记一条，口径同 [noteSkip]）。
     *
     * 为什么要记：这条链路上的早退**全部是静默的**，而用户看到的现象是
     * 「点了确定、列表没反应」—— 2026-10-19 排查这个现象时，光看日志无法分辨
     * 是「没找到列表」「读不到 data」还是「适配器不是同一个」，只能靠临时加日志重编。
     * 留下这条诊断后，用户导出的日志里直接就有原因。
     */
    private fun noteApplySkip(reason: String) {
        if (lastApplySkip == reason) return
        lastApplySkip = reason
        GLog.w("筛选股票：本轮筛选没生效 —— $reason")
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

        // ⚠️ **「已经包过」的判定提到最前**（纯重排，判定条件一字未改）：
        // 这条在**每一行**的 getView 里都会走一遍，而它原本在链尾 ——
        // 走到那里要付出：一次 header 拆包的反射调用 + 一次 synchronized 查表
        // + 一次类名比较 + 一次反射读字段。而 99% 的情况下它只是发现
        // 「监听器还是我们装的」然后原样返回。
        // ⚠️ **必须保留 `existing != null`**：若 existing 与 wrappedLists[lv] 都是 null，
        // `null === null` 为真 → 会**永远不再包裹** → 宿主点行没反应。
        val existing = itemClickListener(lv)
        val known = wrappedLists[lv]
        if (existing != null && known != null && known.wrapper === existing) return   // 已经包过

        var adapter = unwrapHeaderAdapter(lv.adapter) ?: return noteSkip("列表还没有 adapter")
        // 列表上可能是我们的过滤代理 —— 认它包着的那个原适配器
        // （本实例按 [proxiedLists] 身份判、热重载后按类名拆包，见 [unwrapOnList]）
        adapter = unwrapOnList(lv, adapter) ?: return noteSkip("过滤代理取不到原适配器")
        if (!StgStockFilterTargets.isTargetAdapter(adapter.javaClass.name)) {
            return noteSkip("adapter 不是目标：${adapter.javaClass.name}")
        }

        if (existing == null) {
            // 宿主没走 ListView 的行点击（比如它自己给每行设了 OnClickListener），
            // 那我们包了也没人接 —— 记一条，排查「点了没反应」时先看它。
            GLog.w("筛选股票：ListView 上没有宿主自己的行点击监听，回映射无处可转发")
        }

        val wrapper = AdapterView.OnItemClickListener { parent, view, position, id ->
            val sink = existing
            if (StgFilterBridge.isRemapBypassed()) {
                // 合成点击（「全选」）：调用方已经把 position 换算成全量下标（含 header），
                // `AbsListView.performItemClick` 也用它写过 checked states —— 两边已经一致，
                // 这里原样转发，**绝不能**再映射一次。
                if (sink == null) {
                    GLog.w("筛选股票：没有宿主的行点击监听可转发（position=$position）")
                } else {
                    runCatching { sink.onItemClick(parent, view, position, id) }
                        .onFailure { GLog.w("筛选股票：转发行点击失败", it) }
                }
            } else {
                var mapped = position
                runCatching {
                    // 每次点击都重新取「列表此刻在用的原适配器」：宿主可能换过适配器实例
                    // （进多选态 / 数据刷新都可能），捕获包装时的旧实例会读到**过期的 snap**，
                    // 映射就静默退化成「原样透传」—— 真机表现仍是点 A 选中 B。
                    val current = unwrapHeaderAdapter(lv.adapter)?.let { unwrapOnList(lv, it) } ?: adapter
                    val header = lv.headerViewsCount
                    // 每次都重新读：两次点击之间筛选结果可能已经换过，
                    // 而 kept 与 keptIndices 是一起原子替换的，不会读到错位组合。
                    val idx = stateOf(current).snap?.keptIndices?.getOrNull(position - header)
                    if (idx != null) mapped = header + idx
                    // 诊断「筛后点 A 选中 B」时先看这一行：position 是框架给的原始位置（含 header），
                    // header 是 ListView 此刻的 header 数，mapped 是转发给宿主/写 checked states 的位置。
                    // 三者对不上就说明坐标换算的前提变了（INFO 级、每次手动点行一条，不刷屏）。
                    GLog.i(
                        "筛选股票：行点击 position=$position header=$header " +
                            "subset=${position - header} mapped=$mapped " +
                            "adapter=${current.javaClass.simpleName}",
                    )
                    // ⚠️ **不要把 checked states 搬到 mapped（全量位置）**（2026-10-21 真机）：
                    // `AbsListView.performItemClick` 在调到我们之前，已经按**适配器位置**
                    // （header + 子集下标）取反写了 `mCheckStates`，而屏幕上的
                    // `CheckedTextView` 与 `mCheckedItemCount` 读的正是这一格。
                    // 搬到 `header + 全量下标` 会让勾选落到**下一行**（点第 3 行勾中第 4 行）。
                    // 宿主需要的是 `mapped`（全量位置）—— 只改转发给监听器的 position，
                    // 两边各用各的坐标；checked states 保持框架写下的适配器位置不动。
                }.onFailure { GLog.w("筛选股票：点击下标回映射失败", it) }
                if (sink == null) {
                    GLog.w("筛选股票：没有宿主的行点击监听可转发（position=$position）")
                } else {
                    // 从这里开始 position 已经是全量坐标：让代理的 getItem/getItemId
                    // 原样放行 —— 宿主若用 `parent.getItemAtPosition(mapped)` 取值，
                    // 再映射一次会读到别的股票。
                    StgFilterBridge.runWithRemapBypassed {
                        runCatching { sink.onItemClick(parent, view, mapped, id) }
                            .onFailure { GLog.w("筛选股票：转发行点击失败", it) }
                    }
                }
            }
        }
        runCatching { lv.onItemClickListener = wrapper }
            .onSuccess {
                wrappedLists[lv] = ClickWrap(wrapper, existing)
                GLog.i("筛选股票：行点击回映射已包上 listener=${existing?.javaClass?.name} header=${lv.headerViewsCount} adapter=${adapter.javaClass.name}")
            }
            .onFailure { GLog.w("筛选股票：包裹行点击监听失败", it) }
    }

    /**
     * **拆回**我们包过的行点击监听，把宿主自己的放回去（规则 1.1）。
     *
     * 关掉开关 / 筛选条件清空时调用 —— 不拆的话宿主列表上永久多一层转发。
     */
    private fun unwrapRowClick(lv: ListView) {
        val w = synchronized(wrappedLists) { wrappedLists.remove(lv) } ?: return
        runCatching { lv.onItemClickListener = w.original }
            .onFailure { GLog.w("筛选股票：还原行点击监听失败", it) }
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
            // 拆包方法的解析结果按类缓存（这条路径在每次 getView 上，见 onGetView）
            val m = synchronized(headerUnwrapCache) {
                headerUnwrapCache.getOrPut(a.javaClass) {
                    Slot(Reflect.findMethod(a.javaClass, "getWrappedAdapter"))
                }
            }.value ?: return null
            return runCatching { m.invoke(a) }.getOrNull()
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
        }.onFailure {
            // 记不上 → 这一行的涨跌幅按「平盘」归类 → 方向筛选会把它藏掉（or 反之）。
            // 症状是「筛选结果不对」，没有日志就无从下手（§3.3）。
            GLog.w("筛选股票：行内涨跌幅兜底记录失败（该行可能被当成平盘）", it)
        }
    }

    // ------------------------------------------------------------------ 类别 / 类型判定

    /** 在自选里吗（`code.market` 是宿主自己的口径，与「自选高亮」同源地判） */
    /**
     * 自选判定（`code` / `market` 已切好，不再重复 substring）。
     *
     * 切分口径与原先**一字不差**：分隔点必须在第一位之后、且后面还有内容
     * （原 `dot <= 0` 判 false；末尾那个点会让 market 为空串 → 下面判 false）。
     */
    private fun isInWatchlist(code: String?, market: String?): Boolean {
        if (code == null || code.isEmpty() || market == null || market.isEmpty()) return false
        return ZxWatchlist.isInWatchlist(code, market)
    }

    /**
     * 一只股票的类型位掩码（[StockTypes]）。
     *
     * 两条腿一起走：**数据项字段为 true 即认定**（`isCyb` / `isKCPlate` / `isXXLD`），
     * 再用「代码 + 市场 + 名称」兜底（策略页的数据源未必把这些字段填全，
     * 与 `isZx` 在策略页恒 false 是同一类问题）—— 见 [StockTypes] 的不确定点说明。
     *
     * 字段解析走 [fieldsOf] 的按类缓存，不进热路径的继承链扫描。
     */
    private fun typesOf(item: Any, code: String?, market: String?, fields: ItemFields): Int {
        var types = StockTypes.NONE
        for ((f, bit) in fields.typeFlags) {
            val v = f?.let { runCatching { it.get(item) }.getOrNull() } as? Boolean ?: continue
            if (v) types = types or bit
        }
        var name: String? = null
        for (f in fields.name) {
            val v = f?.let { runCatching { it.get(item) }.getOrNull() } as? String
            if (!v.isNullOrEmpty()) {
                name = v
                break
            }
        }
        return types or StockTypes.byCodeAndMarket(code, market, name)
    }

    /** 涨跌幅：先读数据项字段（实测 `zdf`，BigDecimal），再退回行内文案记录 */
    private fun percentOf(adapter: Any, item: Any, cam: String?, fields: ItemFields): Double? {
        for (f in fields.percent) {
            val v = f?.let { runCatching { it.get(item) }.getOrNull() } ?: continue
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
        // 列表上可能是我们的过滤代理（本实例按身份认、热重载后按类名认）—— 拆包取原适配器
        return unwrapOnList(lv, a)
    }

    private fun onActivityResumed(env: FeatureEnv, activity: Activity) {
        if (!StgStockFilterTargets.isWatchActivity(activity.javaClass.name)) return
        trackedActivityRef.set(java.lang.ref.WeakReference(activity))
        StockFilterStore.invalidate()              // 回到本页重新读一次筛选状态
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

    internal fun onFilterApplied(env: FeatureEnv, activity: Activity, applied: StockFilters) {
        trackedActivityRef.set(java.lang.ref.WeakReference(activity))
        lastSummary = null
        ensureClickRemap()
        GLog.i("筛选股票：筛选条件已生效（${applied.describe()}）")
        val a = liveAdapter(activity) ?: return noteApplySkip("拿不到当前列表在用的适配器")
        runCatching { applyFilter(env, a, notify = true) }
            .onFailure { GLog.w("筛选股票：应用筛选失败", it) }
    }

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        StockFilterStore.invalidate()
        val act = trackedActivity() ?: return
        // ⚠️ **关掉开关时把行点击的包装拆回去**（规则 1.1）：
        // 不拆的话宿主列表上永久多一层转发，而热重载后新实例还会再包一层。
        if (!cfg.stgStockFilter) {
            findListView(act)?.let { unwrapRowClick(it) }
        }
        val a = liveAdapter(act) ?: return
        runCatching { applyFilter(env, a, notify = true) }
            .onFailure { GLog.w("筛选股票：配置变化后重算失败", it) }
    }

    // ------------------------------------------------------------------ 取值与工具

    internal fun filtersBy(ctx: Context): StockFilters = StockFilterStore.load(ctx)

    private fun percentView(row: View): TextView? {
        var id = percentIdCache
        if (id == 0) {
            id = row.resources.viewIdOrZero(*StgStockFilterTargets.PERCENT_ID_CANDIDATES)
            if (id == 0) return null
            percentIdCache = id
        }
        return runCatching { row.findViewById<View>(id) as? TextView }.getOrNull()
    }

    /**
     * 第 [position] 行的数据项。
     *
     * `getItem(int)` 的解析结果按适配器类缓存（规则 2.1）—— 这条路径在**每行**
     * 都要走一次（[recordRowPercent]），原先每行都要沿继承链找一遍方法。
     */
    private fun itemAt(adapter: Any, position: Int): Any? {
        val m = synchronized(getItemCache) {
            getItemCache.getOrPut(adapter.javaClass) {
                Slot(Reflect.findMethod(adapter.javaClass, "getItem", Int::class.javaPrimitiveType!!))
            }
        }.value
        if (m != null) {
            runCatching { m.invoke(adapter, position) }.getOrNull()?.let { return it }
        }
        return readData(adapter)?.let { pick(it, position) }
    }

    private fun pick(list: Any?, position: Int): Any? {
        if (list is List<*> && position >= 0 && position < list.size) return list[position]
        return null
    }

    /**
     * 条目上的「代码.市场」（实测是 `cam`）。
     *
     * 每行都要跑（记兜底 + 判类别），所以候选字段走 [fieldsOf] 的按类缓存 ——
     * 原先每个候选名一次 `Reflect.readField`（= 一次继承链 `getDeclaredField` 试错），
     * 一趟最多 8 次，全部在 `getView` 的路径上。
     */
    private fun codeAndMarket(item: Any?): String? {
        if (item == null) return null
        return codeAndMarket(item, fieldsOf(item.javaClass))
    }

    /** [fields] 已解析好时用这个（同一趟里只解析一次） */
    private fun codeAndMarket(item: Any?, fields: ItemFields): String? {
        if (item == null) return null
        for (f in fields.cam) {
            val v = f?.let { runCatching { it.get(item) }.getOrNull() } as? String
            if (!v.isNullOrEmpty()) return v
        }
        var code: String? = null
        for (f in fields.code) {
            val v = f?.let { runCatching { it.get(item) }.getOrNull() } as? String
            if (!v.isNullOrEmpty()) {
                code = v
                break
            }
        }
        if (code == null) return null
        for (f in fields.market) {
            val v = f?.let { runCatching { it.get(item) }.getOrNull() } ?: continue
            val s = v.toString()
            if (s.isNotEmpty()) return "$code.$s"
        }
        return null
    }

    @Volatile private var pendingRefilter = false

    private companion object {
        /** 菜单展示方法名（全重载都挂） */
        val POPUP_SHOW_METHODS = arrayOf("showAsDropDown", "showAtLocation")

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
    }
}
