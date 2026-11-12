package io.github.MVennn.GSAuxiliary.feature.stgmerge

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ListView
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.core.MainThreadStallWatchdog
import io.github.MVennn.GSAuxiliary.core.MergeConfig
import io.github.MVennn.GSAuxiliary.core.MergeGroup
import io.github.MVennn.GSAuxiliary.core.MergeMember
import io.github.MVennn.GSAuxiliary.core.StrategyMergeStore
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.filter.StockFilterScopes
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.WeakHashMap

/** 日志前缀：判定 → 取数 → 渲染整条链路共用，`logcat | grep 策略聚合` 就能看全 */
private const val LOG = "策略聚合："

/**
 * 上一轮聚合结束到这个时长内的再次触发，按「宿主自己这一轮刷新」吃掉。
 *
 * 宿主在 `onResume` / 列表回填后会连调几次刷新，不设这个窗口会把刚渲染好的并集再取一遍
 * （8 秒一次，肉眼可见的闪）。窗口只在**同一个 key**（同一页同一组同一适配器）时生效。
 */
private const val SETTLE_MS = 600L

/** 宿主刷新后重新套回并集的最小间隔：太密会和宿主刷新互相踩成活锁 */
private const val REAPPLY_MIN_INTERVAL_MS = 500L

/**
 * 快路的**时间闸**：距离上一次完整慢路不到这么多毫秒，就走了快路直接收工。
 *
 * 以前是「连跳 3 次就强制走一次慢路」，计数闸在「一次通知风暴」里等于没闸 ——
 * 宿主一次「全选 / 反选」是逐行合成点击，一帧刷几百次 notify，前 3 次快路之后
 * 剩下的几百次全走慢路（十几次反射 + 标签补写 + 适配器重挂），
 * 用户感知到的就是「勾选一下卡两秒」。
 * 改成时间闸：同一帧里的几百次通知只算一次，稳态最多每 [FAST_SLOW_MS] 补一次慢路。
 * 看门狗那一路用 `force = true` 绕过它，保证「宿主悄悄重画头部」总有一个周期能纠回来。
 */
private const val FAST_SLOW_MS = 400L

/** 看门狗巡检间隔：宿主既会直接改适配器 data（不 notify），也会在我们写回后重写头部文本 */
private const val WATCH_INTERVAL_MS = 700L

/**
 * 列表页延迟多久去改聚合入口卡片。
 *
 * 宿主在 onResume 之后才把策略列表从网络/缓存填进适配器，太早去拿只会拿到空列表。
 * 700ms 是详情页探针里「列表已就绪」的经验值（v53 起用）。
 */
private const val LIST_DECORATE_DELAY_MS = 700L

/** 入口卡名字前缀里的「聚合」二字，组名自带时不再叠加 */
private const val MERGE_LABEL_HINT = "聚合"

/**
 * 成员 id 被宿主改回后，重新写 id + 发取数命令的最小间隔。
 *
 * 宿主的 autoRefresh 也按 m_stg.id 取数，它一刷新就把我们写的成员 id 改回去；不节流的话
 * 轮询每 120ms 就重发一次命令，等于对宿主服务器打请求风暴。600ms 一跳足够压住抢 id，
 * 又不会把成员取数的有效窗口切碎。
 */
private const val ID_RESEND_MIN_INTERVAL_MS = 600L

/** 列表页巡检间隔：宿主刷新/分页后一千毫秒内把聚合入口补回来，又不至于把整表 notify 得太勤 */
private const val LIST_WATCH_INTERVAL_MS = 800L

/** 每个口径组合的预算：建临时策略 → 宿主取数 → 删临时策略，实测 1.5~2.5 秒，给 3 秒 */
private const val COMBO_BUDGET_MS = 3000L

/**
 * 口径成员的「空列表」确认窗口。
 *
 * 空列表不改变列表签名，接着等也等不到变化。600ms 够宿主把一次真响应渲染完，
 * 又不至于让每个空组合都白等满 [StrategyMergeTargets.MEMBER_TIMEOUT_MS]。
 */
private const val COMBO_EMPTY_CONFIRM_MS = 600L

/**
 * 建 / 删临时策略的专用线程：两步都是网络请求，绝不能上主线程。
 *
 * 单线程即天然串行，正好对上「宿主名额只剩 2 个、必须取一个删一个」。
 */
private val comboExecutor: java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "stgmerge-combo").apply { isDaemon = true }
    }

/**
 * 头部涨跌家数的补写时机（毫秒）。
 *
 * 宿主渲染完聚合结果后还会再刷一轮自己的响应，把头部顶回单策略的数字（真机：应 56/8 却显示 16/1）。
 * 两个点各补一次：400ms 盖它这一轮，1200ms 兜慢一点的网络回包。
 */
private val HEADER_RETRY_DELAYS = longArrayOf(400L, 1200L, 2500L)

/**
 * 区间型标签：前缀 + 「数字-数字」+ 后缀。
 *
 * 真机标签原文：「流通市值70-100亿」（前缀=流通市值 / 区间=70-100 / 后缀=亿）、
 * 「量比1.5-2.0」（无后缀）、「创新高20交易日」（没有连字符，落进离散标签那一类）。
 */
private val TAG_RANGE_REGEX = Regex("^(.*?)(\\d+(?:\\.\\d+)?)-(\\d+(?:\\.\\d+)?)(.*)$")

/** 标签里的数字怎么写回文本：整数不带小数点，小数留最短有效位（1.50 → 1.5，2.00 → 2.0） */
private fun fmtTagNum(v: Double): String {
    if (!v.isFinite()) return v.toString()
    if (v == Math.floor(v)) return v.toLong().toString()
    val s = String.format(Locale.US, "%.2f", v).trimEnd('0')
    return if (s.endsWith('.')) s + "0" else s
}

/**
 * 把多个成员的标签合成一组（纯函数，便于单测）。
 *
 * 区间型按「同前缀 + 同后缀」取并集：流通市值70-100亿 + 流通市值100-500亿 → 流通市值70-500亿，
 * 量比1.5-2.0 + 量比2.0-2.5 → 量比1.5-2.5。离散型（没有区间的条件）去重后保留。
 * 输出顺序按各标签首次出现的顺序，区间标签占它第一次出现的位置。
 */
internal fun mergeTagNames(all: List<String>): List<String> {
    val order = ArrayList<String>()
    val range = LinkedHashMap<String, DoubleArray>()
    val meta = HashMap<String, Pair<String, String>>()
    for (raw in all) {
        val s = raw.trim()
        if (s.isEmpty()) continue
        val m = TAG_RANGE_REGEX.find(s)
        val lo = m?.groupValues?.get(2)?.toDoubleOrNull()
        val hi = m?.groupValues?.get(3)?.toDoubleOrNull()
        if (m != null && lo != null && hi != null) {
            val prefix = m.groupValues[1]
            val suffix = m.groupValues[4]
            val key = "R:$prefix|$suffix"
            val cur = range[key]
            if (cur == null) {
                range[key] = doubleArrayOf(lo, hi)
                meta[key] = prefix to suffix
                order.add(key)
            } else {
                cur[0] = minOf(cur[0], lo)
                cur[1] = maxOf(cur[1], hi)
            }
            continue
        }
        val key = "S:$s"
        if (!order.contains(key)) order.add(key)
    }
    val out = ArrayList<String>(order.size)
    for (k in order) {
        if (k.startsWith("R:")) {
            val r = range[k] ?: continue
            val mt = meta[k] ?: continue
            out.add(mt.first + fmtTagNum(r[0]) + "-" + fmtTagNum(r[1]) + mt.second)
        } else {
            out.add(k.removePrefix("S:"))
        }
    }
    return out
}

/**
 * 标签的数字区间前缀（「流通市值70-100亿」→「流通市值」）；不是区间型返回空串。
 *
 * 合并后的新标签没有自己的 tagId，只能借同前缀那条原元素复制一份，所以这里要能取回前缀。
 */
internal fun takeTagPrefix(name: String): String =
    TAG_RANGE_REGEX.find(name.trim())?.groupValues?.get(1)?.trim().orEmpty()

/** 列表页入口卡名字里的条数尾巴：「聚合 · 创新高20日（4 条）」里的「（4 条）」 */
private val ENTRY_LABEL_COUNT_REGEX = Regex("（\\s*\\d+\\s*条）")

/**
 * 列表页入口卡的显示名：「组名」→「聚合 · 组名」，纯函数便于单测。
 *
 * 组名自己就以「聚合」开头时不再叠前缀，否则会出现「聚合 · 聚合（聚合）」这种读不懂的名字。
 *
 * [memberCount] 已不再进卡名（2026-11-12 用户要求去掉「（N 条）」：那是实现细节，
 * 卡上一串数字既容易过期又占地方）。参数保留是因为调用点本来就算好了这个数，
 * 且详情页/设置页仍按同一个展开数判上限。
 */
@Suppress("UNUSED_PARAMETER")
internal fun entryLabelOf(groupName: String, memberCount: Int): String = entryLabelBare(groupName)

/**
 * 入口卡去掉条数尾巴的形式：「聚合 · 组名」。
 *
 * 详情页头部的策略名就写这个 —— 真机上堆成员名会变成「聚合 · 50e + 100e + 70e + …」，
 * 三行都放不下、还把涨跌家数挤下去。宿主会把详情页的名字带回列表卡片，所以列表巡检
 * 必须把「带条数」和「不带条数」两种写法都认成「这组已经改过了」，否则会挑另一张成员卡
 * 再改一遍，列表又从 1 张入口卡膨胀成 2 张、3 张。
 */
internal fun entryLabelBare(groupName: String): String {
    val gName = groupName.trim().ifEmpty { StrategyMergeTargets.MERGE_NAME_FALLBACK }
    return if (gName.startsWith(MERGE_LABEL_HINT)) gName else StrategyMergeTargets.MERGE_NAME_PREFIX + gName
}

/**
 * [entryLabelOf] 的逆运算：入口卡名字 → 可能的组名候选。
 *
 * 这个函数存在的唯一原因是**点入口卡进详情页**：宿主把卡上的 name 原样带到详情页，
 * 于是 pageName 是「聚合 · 创新高20日（4 条）」，而组成员名是「创新高20日」，
 * 两边永远对不上 —— 真机日志就是「跳过：没有包含当前策略的已启用聚合组（stId=1569199013536731136
 * name=聚合 · 创新高20日（4 条））」。带上「（N 条）」才算我们写的名字，普通策略名不会误伤。
 */
internal fun entryLabelGroupNames(label: String): List<String> {
    val s = label.trim()
    if (s.isEmpty()) return emptyList()
    val withCount = ENTRY_LABEL_COUNT_REGEX.containsMatchIn(s)
    val noCount = ENTRY_LABEL_COUNT_REGEX.replace(s, "").trim()
    if (noCount.isEmpty()) return emptyList()
    // 不带条数的写法只有「聚合 · 组名」才是我们写的（详情页头部）；普通策略名不许误伤
    if (!withCount && !noCount.startsWith(StrategyMergeTargets.MERGE_NAME_PREFIX)) return emptyList()
    val out = ArrayList<String>(2)
    out.add(noCount)
    if (noCount.startsWith(StrategyMergeTargets.MERGE_NAME_PREFIX)) {
        val stripped = noCount.removePrefix(StrategyMergeTargets.MERGE_NAME_PREFIX).trim()
        if (stripped.isNotEmpty()) out.add(stripped)
    }
    return out
}

/**
 * 把入口卡原有的 `tags` JSON 改写成合并后的标签（纯函数，便于单测）。
 *
 * 宿主列表卡片直接用 `tags` 字符串渲染 chips，所以合并只能在这一层做：
 *  - 目标标签有同名原元素 → 复制它（tagId / isValid 等字段原样保留，点标签的逻辑才不会错）；
 *  - 只有同前缀的区间原元素 → 复制它再把 labelName 换成合并后的区间文本；
 *  - 都没有 → 造一个只有 labelName 的元素（宿主读不到 tagId 时按空串处理）。
 *
 * 原 JSON 一个元素都解析不出来时返回 null，调用方按「标签保持原样」处理 ——
 * 写成空数组会让卡片下面一条标签都没有，比不合并更糟。
 */
internal fun mergeTagsJson(rawList: List<String>, want: List<String>): String? {
    if (want.isEmpty()) return null
    val byName = LinkedHashMap<String, org.json.JSONObject>()
    val byPrefix = LinkedHashMap<String, org.json.JSONObject>()
    for (raw in rawList) {
        val arr = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: continue
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("labelName").trim()
            if (n.isEmpty()) continue
            if (!byName.containsKey(n)) byName[n] = o
            val prefix = takeTagPrefix(n)
            if (prefix.isNotEmpty() && !byPrefix.containsKey(prefix)) byPrefix[prefix] = o
        }
    }
    if (byName.isEmpty()) return null
    val out = org.json.JSONArray()
    for (n in want) {
        val src = byName[n] ?: byPrefix[takeTagPrefix(n)]
        val copy = src?.let { runCatching { org.json.JSONObject(it.toString()) }.getOrNull() }
        val e = copy ?: org.json.JSONObject()
        runCatching { e.put("labelName", n) }
        out.put(e)
    }
    return out.toString()
}

/**
 * 目标进程聚合：策略详情页渲染「聚合组」的股票并集。
 *
 * ## 用户看到的
 *
 * 在「我的策略」里点进任意一条策略的详情页，如果这条策略属于某个已启用的聚合组，
 * 这一页的列表就换成组内**所有成员策略的股票并集**，头部策略名变成组名、涨跌家数变成合计、
 * 「策略个股 N 只」变成聚合只数。点返回退出这一页，宿主自己的页面不受影响。
 *
 * ## 为什么这么绕
 *
 * 宿主不会给「多个策略合起来看」的接口，取数只能借它自己的通道：把页面模型里的
 * `m_stg.id` 改成目标策略，再在主线程发它自己的取数命令，等它把 `m_stkList` 换掉。
 * 逐成员跑完再一次性写回适配器，因此中途出任何岔子都能原样还回去
 * （见 [releaseRollback]）。真机机制见 `docs/FEATURE-STRATEGY-MERGE.md` §2.5 / §10.5。
 *
 * ## 三条硬约束（都有实测教训）
 *
 * 1. **反射写操作一律在主线程**：工作线程 `notifyDataSetChanged()` 会抛
 *    `InvocationTargetException`（探针 v49），取数命令在工作线程发只有 1 次请求（v51）；
 * 2. **不去 `setAdapter`、不找 `setNewData`**：ListView 外层套着 `HeaderViewListAdapter`，
 *    换适配器会丢掉宿主的 Header；`setNewData` 这个方法在真机上不存在；
 * 3. **早退不改宿主**：开关关着、没有匹配的组、成员不足两个、字段取不到、反射异常 ——
 *    全部一行 INFO 走人，绝不半途改一半。
 */
class StrategyMergeFeature : Feature {

    override val id: String = "stg_merge"

    override val title: String = "策略详情聚合多组策略"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }


    /**
     * 页面 → 记账。
     *
     * 值是 [PageState]，它反过来**强引用**着 Activity（弱引用只用在适配器与并集上），
     * 所以 [onActivityDestroyed] 必须显式删条目 —— 不能指望 WeakHashMap 的 key 自己失效。
     */
    private val states = Collections.synchronizedMap(WeakHashMap<Activity, PageState>())

    /** 同一页同一原因只写一条 INFO：宿主 onResume 会反复触发，早退原因不该刷屏 */
    private val lastSkip = ThreadLocal<String>()

    /**
     * `ListView.setAdapter` 后置钩子的重入闸。
     *
     * 钩子里会「重新套并集 / 起一轮取数」，这两件事都可能再次 `lv.adapter = …`
     * （宿主刷新时是它自己换、换完我们的钩子又装回去），同一线程一进一出就成链。
     * 真机 2026-11-12 13:38 那次主线程卡死就是 stgfilter 的还原 → 我们的 setAdapter 钩子 →
     * 再还原的无限递归（栈见 docs/FEATURE-STRATEGY-MERGE.md §25）。这里只放**第一层**进来，
     * 嵌套层原样放行（外层已经处理过这一帧）。
     */
    private val setAdapterBusy = ThreadLocal<Boolean>()

    /**
     * 宿主最近一次**自己**塞进适配器的列表（拷贝）与它的代次。
     *
     * 聚合期间我们要把上一轮的完整并集顶在屏幕上：不然 24 个成员挨个渲染，
     * 用户看到的就是「完整的 787 只 → 宿主换成第一个成员的 12 只 → 61 只 …… 一路长回来」。
     * 顶出去的代价是适配器 `data` 不再等于宿主的响应，轮询就不能再拿 `data` 变没变判
     * 「这一格到了」。所以在适配器 notify 的那一刻先把宿主那份抄下来，轮询只认这份 + 代次。
     */
    private val hostPayload = ArrayList<Any?>()
    @Volatile private var hostPayloadGen = 0

    /** 我们自己写适配器 `data` 时置位：同一线程里 notify 会回头进钩子，别把自己的写入当成宿主响应 */
    private val ownAdapterWrite = ThreadLocal<Boolean>()

    /**
     * 「我的策略」列表页的巡检循环。
     *
     * 只靠 onResume 补一次不够：宿主下拉刷新 / 上拉分页会**整页重拉**策略（新对象、原生名），
     * 我们写进去的聚合入口名和合并标签当场被顶掉，用户看到的就是「一刷新聚合全没了」。
     */
    private val listWatches = Collections.synchronizedMap(WeakHashMap<Activity, Runnable>())

    /**
     * 我们已经写进列表页的聚合入口名（「聚合 · X（N 条）」）。
     *
     * 用途只有一个：把这些合成名从「整页策略采集」里排除掉 —— 它们是假的，既不该覆盖
     * 「成员原名 → stId」，也不该把合并后的标签当成原生标签记进索引。
     */
    private val entryLabels = Collections.synchronizedSet(HashSet<String>())

    /**
     * 组名 → 入口卡原来的身份（stId / 原名 / 原生标签）。
     *
     * 入口卡被我们改名成「聚合 · X（N 条）」以后，页面上就再也扫不出它是哪条策略了；
     * 而它本来就是宿主里的一条真策略。不记下来，「从金太阳导入」里就永久少这一条，
     * 用户会以为策略被删了（真机现象：组一启用，500e 从清单里消失）。
     */
    private val entryOriginals = Collections.synchronizedMap(HashMap<String, StrategyStgIndex.Entry>())


    /** 列表页巡检每 800ms 跑一次：采集结果没变化就别刷日志（否则一页一分钟上百行） */
    private var lastListStat = ""

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.stgMerge

    override fun install(env: FeatureEnv, bag: HookBag) {
        val resumed = hookActivityResume(env, bag)
        val setAdapter = hookListViewSetAdapter(env, bag)
        val showStg = hookShowStg(env, bag)
        val notify = hookAdapterNotify(env, bag)
        GLog.i("$LOG 已挂 onResume×$resumed、setAdapter×$setAdapter、showStg×$showStg、notify×$notify")
    }

    // ------------------------------------------------------------------ 钩子

    /**
     * `Activity.onResume` 后置钩子：宿主每个页面都会经过这里，非详情页直接返回（不写日志）。
     *
     * 照 `feature/stgfilter/StgStockFilterFeature.kt` 的写法：先 `proceed()` 让宿主自己跑完，
     * 再在 `runCatching` 里做我们的事，**永远不改变返回值、永远不抛异常**。
     */
    private fun hookActivityResume(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass("android.app.Activity", env.classLoader)
        if (cls == null) {
            GLog.w("$LOG 找不到 android.app.Activity，onResume 钩子未挂")
            return 0
        }
        var n = 0
        for (m in Reflect.methodsNamed(cls, "onResume")) {
            bag.add(
                env.xposed.hook(m).setId("$id:onResume:$n").intercept { chain ->
                    val result = chain.proceed()
                    runCatching { (chain.thisObject as? Activity)?.let { onActivityResumed(env, it) } }
                        .onFailure { GLog.w("$LOG onResume 后置处理失败", it) }
                    result
                },
            )
            n++
        }
        return n
    }

    /**
     * `ListView.setAdapter` 后置钩子：宿主在 `onCreate` 里就 `setAdapter`，比 `onResume` 更早。
     *
     * 这里只负责「起一次」，防重入由 [start] 自己兜（已聚合并集还在适配器上就直接跳过）。
     */
    private fun hookListViewSetAdapter(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass("android.widget.ListView", env.classLoader)
        if (cls == null) {
            GLog.w("$LOG 找不到 android.widget.ListView，setAdapter 钩子未挂")
            return 0
        }
        var n = 0
        for (m in Reflect.methodsNamed(cls, "setAdapter")) {
            bag.add(
                env.xposed.hook(m).setId("$id:setAdapter:$n").intercept { chain ->
                    val result = chain.proceed()
                    if (setAdapterBusy.get() == true) return@intercept result
                    setAdapterBusy.set(true)
                    try {
                        runCatching {
                            val lv = chain.thisObject as? ListView
                            val act = findActivity(lv?.context)
                            // 只认真正装股票的那个适配器：宿主会先挂 AIStkEmptyAdapter（空列表占位），
                            // 那时去聚合会把 StockInfo 塞进按 String 取数据的适配器，直接崩宿主。
                            val a = lv?.adapter?.let { StrategyMergeTargets.unwrapListAdapter(it) }
                            if (act != null && isDetailPage(act) && lv != null) {
                                if (a != null && StrategyMergeTargets.isPageAdapter(a.javaClass.name)) {
                                    val st = stateOf(act)
                                    synchronized(st) { st.lvRef = WeakReference<Any>(lv) }
                                    // 宿主自己刷新（实测 10~12 秒一次）会重挂适配器并塞回单策略那份数据，
                                    // 已经算好的并集就这么被顶掉了 —— 先试把它重新套回去，套不住才重取。
                                    if (!reapplyIfNeeded(act, a)) start(env, act, lv)
                                } else {
                                    // 宿主自己那条策略当前没有股票时会挂 AIStkEmptyAdapter（data 是 String
                                    // 提示语）。就这么放它挂着，我们写回的并集永远没人看见（真机 13:12 / 13:16
                                    // 那次用户看到的就是「0 只」）—— 当场把并集适配器装回去。
                                    reattachIfSwapped(act, lv)
                                }
                            }
                        }.onFailure { GLog.w("$LOG setAdapter 后置处理失败", it) }
                    } finally {
                        setAdapterBusy.set(false)
                    }
                    result
                },
            )
            n++
        }
        return n
    }

    /**
     * 详情页渲染入口 `showStg()` 后置钩子。
     *
     * 只挂 setAdapter 不够：实测宿主十来秒后是**直接给适配器喂新数据重画**，不走 setAdapter，
     * 页面就那么被顶回单策略（真机 2026-11-11 21:15:40 回落到 47 只 / 40-7 / 名字「聚合测试」）。
     * 每次重画后把并集再套一遍即可，幂等：data 首项还是同一实例就直接返回。
     */
    private fun hookShowStg(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass(StrategyMergeTargets.DETAIL_ACTIVITY, env.classLoader)
        if (cls == null) {
            GLog.w("$LOG 找不到详情页类，showStg 钩子未挂")
            return 0
        }
        var n = 0
        for (m in Reflect.methodsNamed(cls, "showStg")) {
            bag.add(
                env.xposed.hook(m).setId("$id:showStg:$n").intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        val act = chain.thisObject as? Activity ?: return@runCatching
                        val ad = findDetailAdapter(act) ?: return@runCatching
                        reapplyIfNeeded(act, ad)
                    }.onFailure { GLog.w("$LOG showStg 后置处理失败", it) }
                    result
                },
            )
            n++
        }
        return n
    }

    /**
     * 适配器刷新**前置**钩子：宿主把自己的单策略数据塞进 `data` 后调 `notifyDataSetChanged`，
     * 就在这一刻把 `data` 换成并集 —— 它接下来画出来的就是并集。
     *
     * 只靠 setAdapter 后置 / showStg 后置都会被它下一轮定时取数盖掉
     * （真机 2026-11-11 21:19:44 又一次回落到 47 只 / 40-7），所以必须抢在绘制之前。
     */
    private fun hookAdapterNotify(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass(StrategyMergeTargets.CLS_ADAPTER_NOTIFY, env.classLoader)
        if (cls == null) {
            GLog.w("$LOG 找不到适配器基类，notify 钩子未挂")
            return 0
        }
        var n = 0
        for (m in Reflect.methodsNamed(cls, "notifyDataSetChanged")) {
            bag.add(
                env.xposed.hook(m).setId("$id:adapterNotify:$n").intercept { chain ->
                    runCatching { patchBeforeNotify(chain.thisObject) }
                        .onFailure { GLog.w("$LOG notify 前置处理失败", it) }
                    chain.proceed()
                },
            )
            n++
        }
        return n
    }

    /**
     * `notifyDataSetChanged` 前把 data 换成并集（节流关掉：这一枪必须打中）。
     *
     * 顺序不能换：**先抄宿主那一帧**（取数窗口里这是唯一可信的「这一格到了」信号），
     * 再把上一轮的并集顶回 `data`，宿主的 notify 接着画出来的就是并集。
     * 抄完再顶，抄到的才不会是自己的写入。
     */
    private fun patchBeforeNotify(adapter: Any?) {
        if (adapter == null || !StrategyMergeTargets.isPageAdapter(adapter.javaClass.name)) return
        val act = currentDetailActivity() ?: return
        val state = stateOf(act)
        if (!isMerging(state) && state.mergedStrong == null) {
            reapplyIfNeeded(act, adapter, throttle = false)
            return
        }
        if (ownAdapterWrite.get() != true) captureHostPayload(state, adapter)
        runCatching { holdUnionWhileRunning(act, state, adapter) }
            .onFailure { GLog.w("$LOG 顶住并集失败", it) }
        reapplyIfNeeded(act, adapter, throttle = false)
    }

    /** 这一页正在跑一轮聚合（取数窗口） */
    private fun isMerging(state: PageState): Boolean = synchronized(state) { state.running }

    /**
     * 把宿主这一帧的列表抄进 [hostPayload] 并推进代次。
     *
     * 只在取数窗口里抄：平时抄了没人用，白拷 800 个元素。
     * 拷贝是必须的 —— 宿主下一帧会就地 `clear + addAll` 同一个 List 实例，
     * 不拷的话我们手里的「这一格的结果」会被改写成下一格的。
     */
    private fun captureHostPayload(state: PageState, adapter: Any) {
        if (!isMerging(state)) return
        val cur = StrategyMergeTargets.listField(adapter, StrategyMergeTargets.ADAPTER_FIELD_DATA) ?: return
        val copy = ArrayList<Any?>(cur.size)
        copy.addAll(cur)
        synchronized(hostPayload) {
            hostPayload.clear()
            hostPayload.addAll(copy)
            hostPayloadGen++
        }
        // 家数也要在同一时刻抄下来：这一步在「把自己的聚合值写回 model」之前，
        // 抄到的才是宿主自己的统计。晚一步抄到的就是我们上一帧写进去的聚合值。
        (synchronized(state) { state.run })?.captureHostStats()
    }

    private fun payloadGen(): Int = hostPayloadGen

    private fun payloadSnapshot(): List<*> = synchronized(hostPayload) { ArrayList<Any?>(hostPayload) }

    /**
     * 取数窗口里把上一轮的完整并集顶回适配器，并且只写 `data`、**不 notify**：
     * 宿主自己的 `notifyDataSetChanged` 紧接着就会画这一帧，多喊一次只会多一帧闪烁。
     *
     * 只在「宿主这份比并集小」时顶：宿主偶尔会给出比上一轮还大的列表（比如 500e 那天），
     * 那种情况没理由把它缩回来。
     */
    private fun holdUnionWhileRunning(act: Activity, state: PageState, adapter: Any) {
        if (!isMerging(state)) return
        val held = state.mergedStrong ?: return
        if (held.isEmpty()) return
        val cur = StrategyMergeTargets.listField(adapter, StrategyMergeTargets.ADAPTER_FIELD_DATA)
        if (cur != null && cur.size >= held.size) return
        val before = cur?.size ?: 0
        ownAdapterWrite.set(true)
        try {
            // 交副本：宿主刷新时会就地清空这份 list
            val copy = ArrayList<Any>(held.size)
            for (e in held) if (e != null) copy.add(e)
            val ok = StrategyMergeTargets.write(adapter, StrategyMergeTargets.ADAPTER_FIELD_DATA, copy)
            if (!ok) {
                GLog.w("$LOG 顶住并集失败：data 写不进去（适配器换了？）")
                return
            }
            // 头部跟着一起顶：列表是整份并集、家数却写着某个成员的，看着更乱
            (synchronized(state) { state.run })?.holdHeaderCounts()
            writeHeaderCounts(act, state.upSum, state.downSum, state)
            // 筛选代理（stgfilter 的 FilteredAdapter）在 notify 之前就把下标表算完了，
            // 我们这笔写晚了一步：代理仍按宿主那一格的行数显示。再喊一次 notify，
            // 代理的前置钩子会按新的全量重算下标表，屏幕才是整份并集（筛了就还是筛后的）。
            StrategyMergeTargets.notifyAdapter(adapter)
            // 计数必须在 notify 之后再写：`策略个股 X/总数` 里的 X 是从代理现算的可见行数读的，
            // 写在 notify 之前读到的是**上一格**的行数（真机 34/636 → 27/636 → 69/636 乱跳）。
            writeMergedResNum(act, state, held.size)
        } finally {
            ownAdapterWrite.set(false)
        }
        if (!state.holdNoted) {
            state.holdNoted = true
            GLog.i("$LOG 取数期间顶住上一轮并集 ${held.size} 只（宿主这一帧 $before 只，不缩）")
        }
    }

    /** 当前还活着、且已经聚合过的详情页（钩子里拿不到 Activity，只能按状态表反查） */
    private fun currentDetailActivity(): Activity? = synchronized(states) {
        for ((act, st) in states) {
            if (!act.isFinishing && !act.isDestroyed && isDetailPage(act) && st.applied) return act
        }
        null
    }

    /** 详情页里真正装股票的那个适配器（跳过 Header / 空列表占位） */
    /**
     * 一次性诊断：页面上每个 ListView、它挂的适配器、包裹后的内层适配器、各自 data 长度。
     *
     * 为的是回答「我们写 data=64 了，界面为什么还是宿主的 47」——如果列表用的实例跟我们写的
     * 不是同一个对象，写再多也没人看。
     */
    /** 标签条那个 RecyclerView（adapter 类名带 Tag）。 */
    private fun findTagRecyclerView(act: Activity): android.view.ViewGroup? {
        val root = act.window?.decorView ?: return null
        val lists = ArrayList<View>(16)
        collectLists(root, lists)
        for (v in lists) {
            if (v.javaClass.name != StrategyMergeTargets.CLS_RECYCLER_VIEW) continue
            val ad = runCatching { v.javaClass.getMethod("getAdapter").invoke(v) }.getOrNull() ?: continue
            if (ad.javaClass.name.contains("Tag")) return v as android.view.ViewGroup
        }
        return null
    }

    /** 标签条那个 RecyclerView：先吃缓存，没有再遍历视图树找（adapter 类名带 Tag）。 */
    private fun tagRecyclerView(act: Activity, state: PageState): android.view.ViewGroup? {
        (synchronized(state) { state.tagRvRef?.get() } as? android.view.ViewGroup)?.let {
            if (it.isAttachedToWindow) return it
        }
        val rv = findTagRecyclerView(act) ?: return null
        synchronized(state) { state.tagRvRef = WeakReference<Any>(rv) }
        return rv
    }

    /**
     * 换标签条适配器的数据。
     *
     * 宿主是 BaseQuickAdapter 系（setNewData(List) 是标准入口）；方法名对不上就写数据字段再 notify。
     * Tag 实例必须用宿主自己的类造 —— 换成我们自己的 String / 包装类，宿主渲染时强转就崩。
     */
    private fun setTagAdapterData(ad: Any, want: List<String>, sample: Any?): Boolean {
        val tags = ArrayList<Any>(want.size)
        for (n in want) {
            val t = StrategyMergeTargets.newTag(n, sample)
            if (t == null) {
                GLog.i("$LOG 造不出宿主的 Tag 实例（sample=${sample?.javaClass?.name}），标签条保持原样")
                return false
            }
            tags.add(t)
        }
        val setter = ad.javaClass.methods.firstOrNull { m ->
            m.name in StrategyMergeTargets.TAG_ADAPTER_SET_METHODS &&
                m.parameterTypes.size == 1 &&
                List::class.java.isAssignableFrom(m.parameterTypes[0])
        }
        if (setter != null && runCatching { setter.invoke(ad, tags); true }.getOrDefault(false)) {
            GLog.i("$LOG 标签条走适配器方法 ${ad.javaClass.simpleName}.setter { ${setter.name}(List) }")
            return true
        }
        for (name in StrategyMergeTargets.TAG_ADAPTER_DATA_FIELDS) {
            val fld = Reflect.findField(ad.javaClass, name) ?: continue
            if (runCatching { fld.set(ad, tags); true }.getOrDefault(false)) {
                StrategyMergeTargets.notifyAdapter(ad)
                GLog.i("$LOG 标签条走数据字段 ${ad.javaClass.simpleName}.{ ${name} } + notify")
                return true
            }
        }
        return false
    }

    /**
     * 把合并后的标签写进页面标签条。
     *
     * 优先换 adapter 数据（能应付标签数量变化），失败才退化成逐条改 TextView（条数相同时有效）。
     * 宿主每 6~10 秒按「自己那条策略」重画标签，所以这函数也跟着看门狗一起跑。
     */
    private fun applyTagTexts(act: Activity, state: PageState): Boolean {
        val want = state.tagTexts ?: return false
        if (want.isEmpty()) return false
        val rv = tagRecyclerView(act, state) ?: return false
        val cur = ArrayList<String>(rv.childCount)
        for (i in 0 until rv.childCount) {
            val tv = rv.getChildAt(i) as? TextView ?: continue
            if (tv.visibility != View.VISIBLE) continue
            cur.add(tv.text?.toString()?.trim() ?: "")
        }
        if (cur == want) return true
        val ad = runCatching { rv.javaClass.getMethod("getAdapter").invoke(rv) }.getOrNull()
        if (ad != null && setTagAdapterData(ad, want, state.tagSample)) {
            GLog.i("$LOG 标签条已换成聚合标签：${want.joinToString(" + ")}")
            return true
        }
        if (rv.childCount == want.size) {
            for (i in 0 until rv.childCount) {
                (rv.getChildAt(i) as? TextView)?.text = want[i]
            }
            GLog.i("$LOG 标签条按文本逐条写成：${want.joinToString(" + ")}")
            return true
        }
        return false
    }

    /**
     * 浅拷一份宿主策略对象（列表适配器里的每一行就是一个策略 bean）。
     *
     * 纯条件组在列表页没有成员卡可用，以前是**借**用户一张真策略改名 —— 用户的原策略当场从列表里消失。
     * 现在复制一份、只改复制品那一个字段，插进 data 里多出一张卡，原来的一个字都不动。
     * 拿不到无参构造或复制失败就返回 null，调用方按「这一轮不建入口」处理，不会把页面搞坏。
     */
    private fun cloneCard(src: Any): Any? {
        val cls = src.javaClass
        val inst = runCatching {
            cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.getOrNull() ?: return null
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                if (!runCatching { f.isAccessible = true }.isSuccess) continue
                val v = runCatching { f.get(src) }.getOrNull() ?: continue
                runCatching { f.set(inst, v) }
            }
            c = c.superclass
        }
        return inst
    }

    private fun logListAdapters(act: Activity, tag: String) {
        val lists = ArrayList<View>(4)
        collectLists(act.window?.decorView, lists)
        for ((i, v) in lists.withIndex()) {
            val la = adapterOf(v)
            val inner = StrategyMergeTargets.unwrapListAdapter(la)
            val data = StrategyMergeTargets.listField(
                inner,
                StrategyMergeTargets.ADAPTER_FIELD_DATA,
            )
            val cnt = (v as? android.widget.ListView)?.let { runCatching { it.count }.getOrNull() } ?: -1
            GLog.i(
                "$LOG [$tag] 列表#$i ${v.javaClass.simpleName}@" +
                    "${System.identityHashCode(v)} lvAdapter=${la?.javaClass?.simpleName}@" +
                    "${la?.let { System.identityHashCode(it) }} inner=${inner?.javaClass?.simpleName}@" +
                    "${inner?.let { System.identityHashCode(it) }} data=${data?.size ?: -1} count=$cnt",
            )
        }
    }

    /**
     * 适配器接线探针（临时）：把每个 ListView 的适配器、数据字段身份、model 股票列表身份都打出来。
     *
     * 目的是查「谁把自己的股票列表交给了 AIStkEmptyAdapter」——它 convert 第一行就把 item 强转 String，
     * 拿到 StockInfo 就 ClassCastException 崩宿主。身份码相同 = 同一个 List 实例被两个适配器共用。
     */
    private fun logWiring(act: Activity, tag: String) {
        val model = StrategyMergeTargets.field(act, *StrategyMergeTargets.ACT_FIELD_MODEL)
        val stk = StrategyMergeTargets.listField(model, StrategyMergeTargets.MODEL_FIELD_STK_LIST)
        GLog.i(
            "$LOG [wire:$tag] model.m_stkList@${System.identityHashCode(stk)} " +
                "size=${stk?.size ?: -1} first=${stk?.firstOrNull()?.javaClass?.simpleName}",
        )
        val lists = ArrayList<View>(4)
        collectLists(act.window?.decorView, lists)
        for ((i, v) in lists.withIndex()) {
            val la = adapterOf(v)
            if (la == null) continue
            val inner = StrategyMergeTargets.unwrapListAdapter(la)
            val d = StrategyMergeTargets.listField(inner, StrategyMergeTargets.ADAPTER_FIELD_DATA)
            GLog.i(
                "$LOG [wire:$tag] 列表#$i ${v.javaClass.simpleName} " +
                    "lv=${la.javaClass.simpleName}@${System.identityHashCode(la)} " +
                    "inner=${inner?.javaClass?.simpleName}@${inner?.let { System.identityHashCode(it) }} " +
                    "data@${System.identityHashCode(d)} size=${d?.size ?: -1} " +
                    "first=${d?.firstOrNull()?.javaClass?.simpleName}",
            )
        }
    }

    /** 详情页里真正装股票的那个适配器（跳过 Header / 空列表占位） */
    private fun findDetailAdapter(act: Activity): Any? {
        val lists = ArrayList<View>(4)
        collectLists(act.window?.decorView, lists)
        for (v in lists) {
            val a = adapterOf(v) ?: continue
            if (StrategyMergeTargets.isPageAdapter(a.javaClass.name)) return a
        }
        return null
    }

    /**
     * 宿主把列表换成**空占位适配器**之后，把并集那个适配器装回去。
     *
     * 为什么必须装回去：`AIStkEmptyAdapter` 的 data 是 String 提示语，往里塞 StockInfo 会
     * ClassCastException；而宿主在「自己那条策略当前没有股票」时就这么换 —— 真机 2026-11-12
     * 13:12 / 13:16 两次都是这个形态：`[wire:finish-before] 列表#0 lv=AIStkEmptyAdapter data size=1 first=String`。
     * 我们随后写的是那个**已经不在列表上**的适配器，界面永远「暂无数据」，用户看到的就是 0 只。
     *
     * 装回去要赶在同一帧布局前（本函数全程同步、主线程），界面不会闪过「暂无数据」。
     *
     * @param only 只查这一个列表（setAdapter 钩子里能直接拿到它）；null = 先按快照、再扫视图树
     * @return true = 真的装回去了
     */
    private fun reattachIfSwapped(act: Activity, only: ListView? = null): Boolean {
        val state = stateOf(act)
        if (!state.applied) return false
        val merged = state.mergedStrong
        if (merged.isNullOrEmpty()) return false
        val ours = synchronized(state) { state.adapterRef?.get() }
            ?.takeIf { StrategyMergeTargets.isPageAdapter(it.javaClass.name) } ?: return false
        val lists = ArrayList<ListView>(4)
        if (only != null) {
            lists.add(only)
        } else {
            val cached = synchronized(state) { state.lvRef?.get() } as? ListView
            if (cached != null && cached.isAttachedToWindow) {
                lists.add(cached)
            } else {
                val all = ArrayList<View>(4)
                collectLists(act.window?.decorView, all)
                for (v in all) if (v is ListView) lists.add(v)
            }
        }
        for (lv in lists) {
            val cur = adapterOf(lv) ?: continue
            val inner = StrategyMergeTargets.unwrapListAdapter(cur)
            if (inner === ours) continue
            if (inner?.javaClass?.name != StrategyMergeTargets.CLS_EMPTY_ADAPTER) continue
            val ok = runCatching {
                lv.adapter = ours as android.widget.ListAdapter
                true
            }.getOrDefault(false)
            if (!ok) continue
            synchronized(state) { state.lvRef = WeakReference<Any>(lv) }
            GLog.i("$LOG 宿主把列表换成了空占位适配器，已把并集适配器装回去（${merged.size} 只）")
            StrategyMergeTargets.notifyAdapter(ours)
            return true
        }
        return false
    }

    /**
     * 宿主刷新后把内存里那份并集重新写回适配器。
     *
     * 不重新取数：取数要打网络，而且宿主的刷新周期比取数快，会互相踩成活锁。
     *
     * @return true = 这一页已经由我们接管（调用方别再起新一轮）
     */
    /**
     * 看门狗：宿主会**不 notify 直接改适配器 data**（真机 21:31:36 那次：我们写回 64 只、它随后改成 47
     * 却不通知列表，界面就停在旧的 47 行），也会在我们写回之后重写头部文本。定时巡检把它纠回来。
     */
    private fun startWatch(act: Activity, state: PageState) {
        stopWatch(state)
        val runnable = object : Runnable {
            override fun run() {
                if (!state.applied || act.isFinishing || act.isDestroyed) return
                runCatching {
                    val adapter = synchronized(state) { state.adapterRef?.get() }
                    if (adapter != null) reapplyIfNeeded(act, adapter, throttle = false, force = true)
                }.onFailure { GLog.w("$LOG 看门狗重套失败", it) }
                mainHandler.postDelayed(this, WATCH_INTERVAL_MS)
            }
        }
        synchronized(state) { state.watch = runnable }
        mainHandler.postDelayed(runnable, WATCH_INTERVAL_MS)
    }

    private fun stopWatch(state: PageState) {
        val old = synchronized(state) {
            val w = state.watch
            state.watch = null
            w
        }
        if (old != null) mainHandler.removeCallbacks(old)
    }

    /**
     * 把并集重新套回这一页（宿主刷新后 / 看门狗定期 / notify 前置）。
     *
     * @param throttle true 时受 [REAPPLY_MIN_INTERVAL_MS] 节流（给宿主自己刷新的那一路用）
     * @param force true 时**强制走完整慢路**，跳过快路的时间闸 —— 只给看门狗用，
     *              它是「宿主把头部重画成单策略数字」的唯一兜底，不能被合并掉。
     */
    private fun reapplyIfNeeded(
        act: Activity,
        adapter: Any,
        throttle: Boolean = true,
        force: Boolean = false,
    ): Boolean {
        val state = stateOf(act)
        val merged = (if (state.applied) state.mergedStrong else null) ?: return false
        if (state.running) return true
        // 用聚合时那一个适配器：页面里不止一个 ListView，按视图树重新找容易找到别的那个，
        // 写了半天其实写给了没人用的列表（真机 21:24 重套日志成功但界面纹丝不动）。
        val target = synchronized(state) { state.adapterRef?.get() }
            ?.takeIf { StrategyMergeTargets.isPageAdapter(it.javaClass.name) }
            ?: adapter
        val cur = StrategyMergeTargets.listField(target, StrategyMergeTargets.ADAPTER_FIELD_DATA)
        // 快路：列表没被宿主动过、头部两颗数字也还是我们写的那份 —— 直接收工。
        // 这一页每个 notify（勾选股票、全选 / 反选、截图前翻页）都会走到这里，
        // 慢路一次 = 十几次反射 + 全树扫 60 多个 TextView，勾选时肉眼可见掉帧。
        if (!force && cur != null && cur.size == merged.size &&
            cur.firstOrNull() === merged.firstOrNull() && headerCountsOk(state) &&
            android.os.SystemClock.uptimeMillis() - state.lastSlowAt < FAST_SLOW_MS
        ) {
            return true
        }
        state.lastSlowAt = android.os.SystemClock.uptimeMillis()
        // 头部三处文本（策略名称 / 涨跌家数 / 策略个股N只）宿主每轮刷新都会重写，
        // 和数据有没有被改无关，所以放在早返回之前：同值 setText 不会闪。
        val model = StrategyMergeTargets.field(act, *StrategyMergeTargets.ACT_FIELD_MODEL)
        val stg = StrategyMergeTargets.field(model, StrategyMergeTargets.MODEL_FIELD_STG)
        val label = state.label
        if (stg != null && !label.isNullOrEmpty()) {
            StrategyMergeTargets.write(stg, StrategyMergeTargets.STG_FIELD_NAME, label)
        }
        // 家数光改文本不够：宿主下一轮是按 model / m_stg 上的 upNum、downNum 重画的，
        // 会把单策略的值（真机实测 16/1）盖回来，用户看到的就是「涨价数一直在跳」。
        // 这里连字段一起写，看门狗 700ms 一跳，宿主顶回后最多一个周期就纠回来。
        // 取数窗口（state.running）里绝不写这两个字段：MergeRun 每吸收一份响应都要读它们
        // 来累加涨跌家数，字段上留着上一轮的聚合值就会被重复计入。
        if (!state.running && (state.upSum > 0 || state.downSum > 0)) {
            StrategyMergeTargets.write(model, StrategyMergeTargets.MODEL_FIELD_UP_NUM, state.upSum)
            StrategyMergeTargets.write(model, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM, state.downSum)
            StrategyMergeTargets.write(stg, StrategyMergeTargets.MODEL_FIELD_UP_NUM, state.upSum)
            StrategyMergeTargets.write(stg, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM, state.downSum)
        }
        writeHeaderCounts(act, state.upSum, state.downSum, state)
        writeMergedResNum(act, state, merged.size)
        // 标签条同理：宿主重画后要按合并结果补回去（同内容时内部早返回，几乎零成本）
        applyTagTexts(act, state)
        // 列表可能已经被宿主换成空占位适配器（它那条策略没股票）：先把并集适配器装回列表，
        // 否则下面写的是「不在列表上」的那个适配器，界面永远「暂无数据」= 用户看到的 0 只。
        reattachIfSwapped(act)
        // 第一项还是同一实例 → 宿主没动过数据，列表仍是并集
        if (cur != null && cur.size == merged.size && cur.firstOrNull() === merged.firstOrNull()) {
            return true
        }
        val now = System.currentTimeMillis()
        synchronized(state) {
            // 刷新前置那一枪不能节流（节流掉了宿主就用自己的数据画完这一帧了）
            if (throttle && now - state.lastReapplyAt < REAPPLY_MIN_INTERVAL_MS) return true
            state.lastReapplyAt = now
        }
        if (!StrategyMergeTargets.write(target, StrategyMergeTargets.ADAPTER_FIELD_DATA, ArrayList(merged))) {
            return false
        }
        StrategyMergeTargets.notifyAdapter(target)
        val after = StrategyMergeTargets.listField(target, StrategyMergeTargets.ADAPTER_FIELD_DATA)?.size ?: -1
        GLog.i(
            "$LOG 宿主刷新后已把并集重新套回（${merged.size} 只，适配器 " +
                "${target.javaClass.simpleName}@${System.identityHashCode(target)}，写后 data=$after）",
        )
        return true
    }

    /**
     * 头部「涨家数 / 跌家数」还是不是我们写的聚合值。
     *
     * 只读上一次认下来的两颗 TextView（弱引用），**不扫视图树** —— 看门狗每 700ms 一跳，
     * 加上宿主每次 notify 都会触发一次重套，全树扫描是这一页卡顿的主要来源。
     */
    private fun headerCountsOk(state: PageState): Boolean {
        val up = state.upTvRef?.get() ?: return false
        val down = state.downTvRef?.get() ?: return false
        if (!up.isAttachedToWindow || !down.isAttachedToWindow) return false
        return up.text?.toString() == state.upSum.toString() &&
            down.text?.toString() == state.downSum.toString()
    }

    /**
     * 把一份缓存快照套到页面上（列表 + 头部 + 计数 + 标签），返回是否套上。
     *
     * 两处用它：
     * - [start]：进页面先秒套上次的完整结果，用户第一眼看到的就是**完整**并集，
     *   不用等 4 个成员挨个取（那段时间界面是宿主的单策略，或者残缺并集）；
     * - [MergeRun.finish]：本轮有成员没取到时保住上次那份，不让残缺覆盖完整。
     *
     * 交给宿主的一定是**副本**：宿主刷新时会就地 clear + addAll 那份 list，
     * 直接交缓存实例会把缓存本身清空。
     */
    private fun applySnapshot(
        act: Activity,
        state: PageState,
        model: Any,
        stg: Any?,
        adapter: Any,
        snap: StrategyMergeCache.Snapshot,
    ): Boolean {
        val merged = snap.merged() ?: return false
        if (!StrategyMergeTargets.write(
                adapter,
                StrategyMergeTargets.ADAPTER_FIELD_DATA,
                ArrayList<Any>(merged),
            )
        ) {
            return false
        }
        val copy = ArrayList<Any>(merged)
        state.mergedRef = WeakReference<Any>(copy)
        state.mergedStrong = copy
        state.mergedSize = copy.size
        state.applied = true
        state.upSum = snap.up
        state.downSum = snap.down
        state.label = snap.label
        state.tagTexts = snap.tags.ifEmpty { null }
        if (stg != null) StrategyMergeTargets.write(stg, StrategyMergeTargets.STG_FIELD_NAME, snap.label)
        // 这里**故意只写文本、不写 model / m_stg 上的 upNum、downNum**：
        // 取数窗口里宿主要靠这两个字段告诉我们「这份响应的涨跌家数」，写进聚合值就等于把
        // 成员统计的读数污染了 —— 下一轮累加会把上一轮的聚合值当成某个成员的值再加一遍
        // （真机实测：每进出一次详情页涨家数 +134）。聚合值只在跑完之后由看门狗补回。
        // stId 还回本页那条：宿主下一次 autoRefresh 取本页策略，而不是停在某个成员上
        state.originalStId?.let { StrategyMergeTargets.write(stg, StrategyMergeTargets.STG_FIELD_ID, it) }
        state.touchedId = false
        StrategyMergeTargets.notifyAdapter(adapter)
        writeHeaderCounts(act, snap.up, snap.down, state)
        writeMergedResNum(act, state, copy.size)
        if (state.tagTexts != null) applyTagTexts(act, state)
        startWatch(act, state)
        return true
    }

    private fun onActivityResumed(env: FeatureEnv, act: Activity) {
        if (isDetailPage(act)) {
            start(env, act, null)
            return
        }
        if (isListPage(act)) decorateList(env, act)
    }

    private fun isDetailPage(act: Activity): Boolean =
        StrategyMergeTargets.isDetailActivity(act.javaClass.name)

    private fun isListPage(act: Activity): Boolean =
        StrategyMergeTargets.isListActivity(act.javaClass.name)

    // ------------------------------------------------------------------ 列表页入口

    /**
     * 「我的策略」列表页：把聚合组的入口卡片改名 + 把整页策略灌进名称索引。
     *
     * 只改宿主那一个 `Strategy` 对象的 name（不插假条目、不动列表结构）：
     * 插一条宿主里不存在的策略，点击会走进一条查不到数据的详情页；
     * 而「组内第一条」本来就是真实策略，点进去详情页照常按整组聚合。
     */
    private fun decorateList(env: FeatureEnv, act: Activity) {
        startListWatch(env, act)
    }

    /**
     * 起（或踢一脚）列表页巡检。
     *
     * 已在巡检就只补一次即时扫描（宿主 onResume 每次都会进来），不叠第二条循环。
     * 首次延迟 [LIST_DECORATE_DELAY_MS]：宿主在 onResume 之后才把策略列表填进适配器。
     */
    private fun startListWatch(env: FeatureEnv, act: Activity) {
        val exist = synchronized(listWatches) { listWatches[act] }
        if (exist != null) {
            mainHandler.post(exist)
            return
        }
        val runnable = object : Runnable {
            override fun run() {
                if (act.isFinishing || act.isDestroyed) {
                    stopListWatch(act)
                    return
                }
                runCatching { decorateListNow(env, act) }
                    .onFailure { GLog.w("$LOG 列表页聚合入口处理失败", it) }
                mainHandler.postDelayed(this, LIST_WATCH_INTERVAL_MS)
            }
        }
        synchronized(listWatches) { listWatches[act] = runnable }
        mainHandler.postDelayed(runnable, LIST_DECORATE_DELAY_MS)
    }

    private fun stopListWatch(act: Activity) {
        val r = synchronized(listWatches) { listWatches.remove(act) }
        if (r != null) mainHandler.removeCallbacks(r)
    }

    private fun decorateListNow(env: FeatureEnv, act: Activity) {
        if (act.isFinishing) return
        val cfg = env.config
        if (!cfg.stgMerge) return

        // 索引先是内存态、再是宿主磁盘缓存：页面切换、进程被杀之后，成员只填名称也要能解析
        StrategyStgIndex.bind(act)

        val adapter = findListAdapter(act)
        if (adapter == null) {
            GLog.i("$LOG 列表页没找到承载策略的适配器，跳过聚合入口")
            return
        }
        val data = StrategyMergeTargets.listField(adapter, *StrategyMergeTargets.LIST_DATA_FIELDS)
        if (data.isNullOrEmpty()) {
            GLog.i(
                "$LOG 列表页适配器 ${adapter.javaClass.name} 读不到列表数据" +
                    "（候选 ${StrategyMergeTargets.LIST_DATA_FIELDS.joinToString("/")}）",
            )
            return
        }

        // ① 整页策略对象灌进索引：名称 → stId（成员只填名称时用）+ stId → 标签（聚合标签用）
        // 详情页模型上的 m_tagList 只反映当前页那一条策略，换 stId 取数时宿主不会换标签，
        // 所以成员的标签只能在这一页整表采集。
        // 已经被我们改名成聚合入口的卡要跳过：它的 name 是「聚合 · X（N 条）」、tags 是合并后的，
        // 再采一遍会把「成员原名 → stId」和「原生标签」两条正路数据污染掉。
        var indexed = 0
        var tagCount = 0
        // 这一轮页面上真实存在的策略：广播给模块 App 用这个，不用索引全量（见 ①.b）
        val live = ArrayList<StrategyStgIndex.Entry>(data.size)
        for (item in data) {
            val nm = stgNameOf(item)
            if (nm != null && entryLabels.contains(nm)) continue
            val sid = stgIdOf(item)
            val tags = StrategyMergeTargets.strategyTagNames(item)
            if (!nm.isNullOrEmpty() && !sid.isNullOrEmpty()) {
                StrategyStgIndex.remember(nm, sid)
                live.add(StrategyStgIndex.Entry(sid, nm, tags))
                indexed++
            }
            if (!sid.isNullOrEmpty() && tags.isNotEmpty()) {
                StrategyStgIndex.rememberTags(sid, tags)
                tagCount++
            }
        }

        val stat = data.size.toString() + "|" + indexed + "|" + tagCount
        if (stat != lastListStat) {
            lastListStat = stat
            GLog.i("$LOG 已登记 $indexed 条策略（$tagCount 条带标签），成员标签取自这一页")
        }

        // 组配置要用来补「被收起但确实存在」的成员卡（见 ①.b），先解出来；
        // 解不出来就整页不装饰，也就没有可广播的清单。
        val merge = runCatching { StrategyMergeStore.decode(cfg.stgMergeGroups) }.getOrNull() ?: return

        // ①.b 把**这一页实际存在的策略**广播给模块 App：两个进程不同 UID，模块 App 读不到宿主的私有数据，
        //      设置页「从金太阳导入」那份列表只能由宿主主动发（见 core/StgCatalogBroadcast）。
        //      只发 live 不发索引全量：索引只增不删，删掉的策略 / 早前接口批量建的策略会一直留在里面，
        //      导进聚合组就是一条取不到数的死成员（真机症状：清单里冒出 T4 / TPMP50）。
        //      发送有两道节流，巡检每 800ms 跑一轮也不会把广播刷爆。
        //      但启用组的成员卡在 ③ 会被从 adapter 数据里摘掉、入口卡在 ② 会被改名，
        //      这两类在下一轮都读不到原名 —— 它们是真策略，得按配置 / 改名时的记录补回来，
        //      否则「从金太阳导入」里少几条，用户以为策略被删了。所以发送放在 ③ 之后。

        // ② 每个启用组只把**一张**成员卡片改名成「聚合 · 组名（N 条）」当入口，并把这组标签合并后写回；
        //    其余成员卡从列表数据里摘掉 —— 聚合的意思就是「几条并成一条」，成员卡还各占一行，
        //    用户会以为聚合没生效（真机反馈：入口卡看着像绑在 70e 上，50e / 100e 还挂着）。
        var renamed = 0
        var retagged = 0
        val toHide = ArrayList<Any?>()
        // 已经被别的组拿去当入口的卡：纯条件组借卡时要避开，否则两个组抢同一张，
        // 后写的那组把名字覆盖掉，前一组就再没有入口了。
        val claimed = ArrayList<Any?>()
        // 温和模式的日志去重：这一页每次重扫都别重复刷同一条
        val gentleNoted = HashSet<String>()
        for (g in merge.groups) {
            if (!g.enabled) continue
            // 条数按**展开后**算，和详情页头部同一个数：口径成员不是一条策略，
            // 它展开成「市值档 × 量比档」。以前这里用配置里的原始成员数，
            // 于是列表卡写「（2 条）」、点进去却是 13 条，用户以为聚合没生效。
            val memberCount = StrategyComboCatalog.expandedCount(g.members)
            if (memberCount < 2) continue
            // 组名本身就叫「聚合…」时不再叠前缀，否则会出现「聚合 · 聚合（聚合）」这种读不懂的名字
            val label = entryLabelOf(g.name, memberCount)
            // 详情页头部写的是不带条数的版本，宿主会把它带回列表，两种都得认
            val bare = entryLabelBare(g.name)
            // 幂等的锚点：**整页**里已经有一张卡叫 label 就说明这组改过了，只补标签。
            // 以前是「找第一条还叫成员名的卡」—— 改完的那条不再匹配，下一轮 onResume 接着改第二、
            // 第三条，组里 4 张卡最后全叫「聚合 · X（4 条）」，下面还各自挂着原生股票和标签，
            // 用户看到的就是「列表一直在膨胀、标签和股票都不对」。
            // 这里必须查整页而不是查「匹配成员名的卡」：成员只填名称时，改名后的入口卡不再按名称匹配，
            // 只看匹配结果会以为这组还没改过。
            val done = data.firstOrNull {
                val n = stgNameOf(it)
                n == label || n == bare
            }
            // 成员数变了（加了 combo 或改了口径），卡上还挂着旧条数「（2 条）」：
            // 名字能反解出本组名、只是数字不是当前值 —— 那还是本组入口卡，把名字改对就行。
            // 少了这一条，改名后的卡既不等于新 label、也不再叫成员原名，下面两路都找不到它，
            // 卡名就永远停在旧数字上（真机就是这个现象）。
            val stale = if (done != null) null else data.firstOrNull {
                val n = stgNameOf(it)
                !n.isNullOrEmpty() && entryLabelGroupNames(n).contains(g.name)
            }
            // 入口卡放在组里**第一个成员**的位置，不是 data 里随便那张：
            // 以前按页面顺序挑，用户看到的就是「聚合卡绑在 70e 上」，排在它前面的 50e / 100e 还单独挂着。
            var card = done ?: stale
            // 这张卡是不是**用户自己的策略卡**（按成员名匹配到的）：是的话下面走温和模式，
            // 不改名、不收卡 —— 用户原来的策略要留在列表里。
            var fromMember = false
            if (card == null) {
                for (m in g.members) {
                    val hit = data.firstOrNull { item -> claimed.none { it === item } && sameMember(m, item) }
                    if (hit != null) {
                        card = hit
                        fromMember = true
                        break
                    }
                }
            }
            // 纯条件组（成员全是 cond: / combo: 占位，或成员名在宿主里还没见过）在页面上一张卡都没有。
            //
            // 以前的做法是**借**一张用户自己的策略卡改名当入口 —— 用户看到的正是「我的策略被聚合吃掉了」：
            // 量500 从列表里消失，只剩一张「聚合组2（24 条）」。
            // 现在改成**克隆**：浅拷一份策略对象、只改克隆那份的名字，插在原卡前面。
            // 原卡一动不动，列表里两张都在。点克隆卡进详情页时，宿主把卡上的名字原样带过去，
            // 那就是本组的入口名「组名（N 条）」，详情页的命中判据 ③ 认得出这是哪一组。
            if (card == null) {
                val donor = data.firstOrNull { item ->
                    val n = stgNameOf(item) ?: return@firstOrNull false
                    if (n.isEmpty()) return@firstOrNull false
                    if (stgIdOf(item).isNullOrEmpty()) return@firstOrNull false
                    // 裸组名的入口卡（组名自己就以「聚合」开头，卡名等于组名）正则认不出来，
                    // 再按 entryLabels 精确排除一次，免得把别组的入口卡克隆一份当自己的
                    if (entryLabelGroupNames(n).isNotEmpty() || n in entryLabels) return@firstOrNull false
                    // 自己的临时策略（整轮复用那条）不能借：收尾就删，入口卡会跟着消失
                    if (n.startsWith(StrategyComboClient.TEMP_PREFIX)) return@firstOrNull false
                    if (claimed.any { it === item }) return@firstOrNull false
                    if (merge.groups.any { gg -> gg.enabled && gg.members.any { sameMember(it, item) } }) {
                        return@firstOrNull false
                    }
                    true
                }
                val clone = donor?.let { cloneCard(it) }
                val list = data as? MutableList<Any?>
                val at = if (donor == null) -1 else (list?.indexOfFirst { it === donor } ?: -1)
                if (donor != null && clone != null && list != null && at >= 0 &&
                    StrategyMergeTargets.write(clone, StrategyMergeTargets.STG_FIELD_NAME, label)
                ) {
                    list.add(at, clone)
                    claimed.add(clone)
                    if (gentleNoted.add(g.id)) {
                        GLog.i("$LOG 组「" + g.name + "」没有成员卡，克隆「" + stgNameOf(donor) + "」当入口（原卡保留）")
                    }
                    renamed++
                    entryLabels.add(label)
                    entryLabels.add(bare)
                    continue
                }
                GLog.w("$LOG 组「" + g.name + "」建不出入口卡（页面 " + data.size + " 张，克隆=" + (clone != null) + "），这一轮不建入口")
                continue
            }
            /* 旧的借卡逻辑（改名版）保留在这里便于回滚，正常不会再走到：
            if (card == null) {
                card = data.firstOrNull { item ->
                    val n = stgNameOf(item) ?: return@firstOrNull false
                    if (n.isEmpty()) return@firstOrNull false
                    if (stgIdOf(item).isNullOrEmpty()) return@firstOrNull false
                    if (entryLabelGroupNames(n).isNotEmpty()) return@firstOrNull false
                    // 自己的临时策略（整轮复用那条）更不能借：收尾就删，入口卡会跟着消失
                    if (n.startsWith(StrategyComboClient.TEMP_PREFIX)) return@firstOrNull false
                    if (claimed.any { it === item }) return@firstOrNull false
                    // 别组的**成员卡**也不能借：那组的 ③ 步会把自己的成员卡从列表里摘掉，
                    // 借过来的入口卡跟着消失，两组同时没入口。
                    if (merge.groups.any { gg -> gg.enabled && gg.members.any { sameMember(it, item) } }) {
                        return@firstOrNull false
                    }
                    true
                }
                if (card != null) {
                    GLog.i("$LOG 组「" + g.name + "」在页面上没有成员卡，借「" + stgNameOf(card) + "」当入口")
                }
            }
            */
            if (fromMember) {
                // 温和模式：这一组在列表上有用户自己的成员卡，点它进详情页就按 stId 归组聚合成并集
                // （见命中判据 ① ），没有任何理由把卡改掉或收起来。
                // 改造前这里会：把首张成员卡改名成「聚合 · X（N 条）」、其余成员卡从适配器数据里摘掉 ——
                // 用户看到的正是「我的策略被聚合吃掉了」：量50 / 量70 / 量100 / 量500 只剩一张。
                if (gentleNoted.add(g.id)) {
                    GLog.i("$LOG 组「" + g.name + "」在列表上有成员卡，走温和模式（不改名、不收卡）")
                }
                continue
            }
            claimed.add(card)
            // 改名之前先把这张卡的真实身份记进 [entryOriginals]：改完以后页面上只剩「聚合 · X（N 条）」，
            // 下一轮再怎么扫也扫不出它是哪条策略，「从金太阳导入」里就永久少一条。
            if (done == null) {
                val origName = stgNameOf(card).orEmpty()
                val origId = stgIdOf(card).orEmpty()
                if (origName.isNotEmpty() && origId.isNotEmpty() &&
                    entryLabelGroupNames(origName).isEmpty() && origName !in entryLabels
                ) {
                    entryOriginals[g.name] = StrategyStgIndex.Entry(origId, origName, StrategyStgIndex.tagsOf(origId))
                }
            }
            if (done == null && StrategyMergeTargets.write(card, StrategyMergeTargets.STG_FIELD_NAME, label)) {
                renamed++
                entryLabels.add(label)
                entryLabels.add(bare)
            }
            // 标签按**整组**算：只留首卡自己的标签，组里「流通市值100-500亿」「500亿及以上」那几条
            // 就看不见了，用户会觉得标签写错。区间型标签由 mergeTagNames 取并集。
            val want = groupTagNames(g, data)
            if (writeCardTags(card, want)) retagged++
            // 其它成员卡收进这一张入口卡（同一张卡被两个组同时引用时只收一次）
            for (item in data) {
                if (item === card) continue
                if (groupMemberOf(g, item) != null && toHide.none { it === item }) toHide.add(item)
            }
        }

        // ③ 把成员卡从适配器数据里摘掉：ListView 只画剩下的，不出现空行、点击下标也不会错位。
        //    宿主下一次自己刷新会重建 data，所以禁用组 / 删成员后不需要我们插回去。
        var hidden = 0
        @Suppress("UNCHECKED_CAST")
        val mutable = data as? MutableList<Any?>
        if (toHide.isNotEmpty()) {
            if (mutable == null) {
                GLog.w("$LOG 列表数据不可修改（" + data.javaClass.name + "），成员卡只能留在列表里")
            } else {
                for (h in toHide) {
                    val idx = mutable.indexOfFirst { it === h }
                    if (idx >= 0) {
                        mutable.removeAt(idx)
                        hidden++
                    }
                }
            }
        }
        if (renamed > 0 || retagged > 0 || hidden > 0) {
            StrategyMergeTargets.notifyAdapter(adapter)
            GLog.i(
                "$LOG 列表页：索引 $indexed 条（累计 " + StrategyStgIndex.size() + "），" +
                    "聚合入口 $renamed 个、合并标签 $retagged 张卡、收起成员卡 $hidden 张",
            )
        }

        // ④ 清单广播（见 ①.b）：页面上还在的 + 被收起的成员 + 被改名的入口卡，三份合起来才是
        //    「宿主里真实存在的策略」。三道来源缺一条，用户就会在导入清单里看到少东西。
        val published = ArrayList<StrategyStgIndex.Entry>(live.size + entryOriginals.size)
        published.addAll(live)
        published.addAll(entryOriginals.values)
        val entryNames = merge.groups.filter { it.enabled }
            .mapTo(HashSet()) { entryLabelOf(it.name, it.members.size) }
        StrategyStgCatalog.publish(act, withGroupMembers(merge, published), entryNames)
    }

    /**
     * 广播给模块 App 的清单 = 这一页看得到的策略 + 被启用组收起来的成员卡。
     *
     * 为什么不直接用 [live]：聚合生效后，成员卡会被从 adapter 数据里摘掉（避免同一批股票重复出现），
     * 下一轮巡检就再也读不到它们 —— 而它们是最真实存在的一批策略，少了几条用户会以为策略被删了
     * （真机症状：把「量500」「量70」加进组后，导入清单从 8 条掉到 6 条）。
     *
     * 这里只补**配置里点名的成员**，不碰 [StrategyStgIndex] 的历史：索引只增不删，
     * 早前用接口批量建过又没了的策略会永远留在里面（T4 / TPMP50 就是这么冒出来的）。
     * 条件 / 组合 / 标签成员不是宿主策略，按前缀跳过。
     */
    private fun withGroupMembers(merge: MergeConfig, live: List<StrategyStgIndex.Entry>): List<StrategyStgIndex.Entry> {
        if (merge.groups.none { it.enabled }) return live
        val ids = HashSet<String>(live.size * 2)
        for (e in live) ids.add(e.stId)
        val out = ArrayList<StrategyStgIndex.Entry>(live.size + 4)
        out.addAll(live)
        for (g in merge.groups) {
            if (!g.enabled) continue
            for (m in g.members) {
                val nm = m.name.trim()
                if (nm.isEmpty()) continue
                if (nm.startsWith(StrategyComboCatalog.COND_PREFIX) ||
                    nm.startsWith(StrategyComboCatalog.COMBO_PREFIX) ||
                    nm.startsWith(StrategyComboCatalog.LABEL_PREFIX)
                ) {
                    continue
                }
                // 成员只填名称时靠索引把名字解析回 stId；解析不出来说明这条成员本来就没下发成功
                val sid = m.stId.trim().ifEmpty { StrategyStgIndex.idOf(nm) ?: "" }
                if (sid.isEmpty() || !ids.add(sid)) continue
                out.add(StrategyStgIndex.Entry(sid, nm, StrategyStgIndex.tagsOf(sid)))
            }
        }
        return out
    }

    /** 列表页策略卡上的名称（宿主字段是 private，反射读） */
    private fun stgNameOf(item: Any?): String? =
        (StrategyMergeTargets.field(item, StrategyMergeTargets.STG_FIELD_NAME) as? String)?.trim()

    private fun stgIdOf(item: Any?): String? =
        (StrategyMergeTargets.field(item, StrategyMergeTargets.STG_FIELD_ID) as? String)?.trim()

    /** 这张卡是组的哪个成员：成员填了 stId 按 id 认，只填名称按名称认；都不是返回 null */
    private fun groupMemberOf(g: MergeGroup, item: Any?): MergeMember? =
        g.members.firstOrNull { sameMember(it, item) }

    /** 这张卡是不是这个成员（同一个判据给「选入口卡」和「收成员卡」共用） */
    private fun sameMember(m: MergeMember, item: Any?): Boolean {
        val msid = m.stId.trim()
        if (msid.isNotEmpty()) return msid == stgIdOf(item)
        val nm = m.name.trim()
        return nm.isNotEmpty() && nm == stgNameOf(item)
    }

    /**
     * 整组要显示的标签（合并后）。
     *
     * 数据源按优先级：索引里该成员 stId 的标签（列表页采集的原生标签，最干净）→ 该成员卡上的标签。
     * 入口卡自己那张的 tags 已经被我们改成合并结果，所以不能拿它再参与下一轮合并
     * （「流通市值50亿及以上」这种合成文本没有区间，再合并会跟真实区间并排出现两条）。
     */
    private fun groupTagNames(g: MergeGroup, data: List<*>): List<String> {
        val pool = ArrayList<String>(g.members.size * 2)
        for (m in g.members) {
            // 口径成员在宿主里没有对应策略，原生 tags 一条也采不到：
            // 不在这里按档位表反推，标签条就会整个缺掉市值维度（真机：中盘组只显示「量比4.0以上」）。
            StrategyComboCatalog.bucketOfMember(m.name, m.stId)?.let { pool.addAll(StrategyComboCatalog.tagNamesOf(it)) }
            StrategyComboCatalog.conditionOfMember(m.name, m.stId)?.let { pool.addAll(StrategyComboCatalog.tagNamesOf(it)) }
            // 标签成员（`label:<labelId>`）同理：它也不是宿主策略，原生 tags 一条都采不到，
            // 必须按档位表反推，否则 pool 是空的 → writeCardTags 直接返回 false →
            // 入口卡保留借来那张卡的标签（真机：聚合组2 选了「量比1.5-2.0 + 流通市值50-70亿」，
            // 卡上却写着「流通市值500亿及以上 / 创新高20交易日」）。
            StrategyComboCatalog.labelIdOf(m.name)?.let { pool.addAll(StrategyComboCatalog.tagNamesOfKey(m.name)) }
            val sid = m.stId.trim().ifEmpty { StrategyStgIndex.idOf(m.name).orEmpty() }
            val fromIndex = if (sid.isNotEmpty()) StrategyStgIndex.tagsOf(sid) else emptyList()
            if (fromIndex.isNotEmpty()) {
                pool.addAll(fromIndex)
                continue
            }
            val card = data.firstOrNull { item ->
                val nm = stgNameOf(item)
                nm != null && !entryLabels.contains(nm) &&
                    ((sid.isNotEmpty() && stgIdOf(item) == sid) || (m.name.trim().isNotEmpty() && m.name.trim() == nm))
            } ?: continue
            pool.addAll(StrategyMergeTargets.strategyTagNames(card))
        }
        return mergeTagNames(pool)
    }

    /**
     * 把合并后的标签写回入口卡的 `tags` 字段。
     *
     * 宿主卡片是按 `tags` 字符串渲染 chips 的，改完 notify 就会重画；
     * 结果与原标签一致时不写 —— 巡检每 800ms 跑一次，不能每轮都触发整表重绘。
     */
    private fun writeCardTags(card: Any, want: List<String>): Boolean {
        if (want.isEmpty()) return false
        if (StrategyMergeTargets.strategyTagNames(card) == want) return false
        val raw = (StrategyMergeTargets.field(card, StrategyMergeTargets.STG_FIELD_TAGS) as? String)
            ?.trim().orEmpty()
        val json = mergeTagsJson(listOf(raw), want) ?: return false
        val ok = StrategyMergeTargets.write(card, StrategyMergeTargets.STG_FIELD_TAGS, json)
        if (ok) GLog.i("$LOG 入口卡标签按整组合并：" + want.joinToString(" + "))
        return ok
    }

    /** 列表页里承载策略卡片的适配器：先按 id 找，再退化成遍历视图树找 ListView */
    private fun findListAdapter(act: Activity): Any? {
        val id = act.resources.viewIdOrZero(*StrategyMergeTargets.LIST_PAGE_ID_CANDIDATES)
        if (id != 0) adapterOf(act.findViewById<View>(id))?.let { return it }
        val lists = ArrayList<View>(4)
        collectLists(act.window?.decorView, lists)
        for (v in lists) adapterOf(v)?.let { return it }
        return null
    }

    /**
     * 从容器拿「真正装策略对象的适配器」。
     *
     * 「我的策略」列表页实测是 **RecyclerView**（uiautomator 2026-11-11：全页唯一滚动容器），
     * 详情页才是 ListView —— 两个都要认。RecyclerView 只能按类名认（模块对 androidx 无编译期依赖），
     * 适配器走它自己的 getAdapter()。
     */
    private fun adapterOf(v: View?): Any? {
        if (v == null) return null
        if (v is ListView) return StrategyMergeTargets.unwrapListAdapter(v.adapter)
        if (v.javaClass.name == StrategyMergeTargets.CLS_RECYCLER_VIEW) {
            val m = Reflect.findMethod(v.javaClass, "getAdapter") ?: return null
            return runCatching { m.invoke(v) }.getOrNull()
        }
        return null
    }

    /**
     * 直接改头部那两颗数字（「涨家数 40 跌家数 7」）。
     *
     * 宿主这两个数字不来自 model / m_stg 上的同名字段（真机实测两边都写了还是 40/7 ——
     * 那是它最后一次取数那条策略的统计），只能按文案认节点、写 TextView。
     * 纯 UI 写文本，不动宿主数据；宿主下次自己刷新时会覆盖回来，所以跟着补写一起调。
     */
    private fun writeHeaderCounts(act: Activity, up: Int, down: Int, state: PageState? = null): Boolean {
        // 快路：已经认过这两颗数字就不必再扫树，稳态只剩两次 String 比较
        val cachedUp = state?.upTvRef?.get()
        val cachedDown = state?.downTvRef?.get()
        if (cachedUp != null && cachedDown != null &&
            cachedUp.isAttachedToWindow && cachedDown.isAttachedToWindow
        ) {
            var changed = false
            if (cachedUp.text?.toString() != up.toString()) {
                cachedUp.text = up.toString()
                changed = true
            }
            if (cachedDown.text?.toString() != down.toString()) {
                cachedDown.text = down.toString()
                changed = true
            }
            return changed
        }
        val root = act.window?.decorView ?: return false
        val texts = ArrayList<TextView>(96)
        collectTextViews(root, texts)
        var ok = false
        for (i in texts.indices) {
            val label = texts[i].text?.toString()?.trim()
            val target = when (label) {
                "涨家数" -> up
                "跌家数" -> down
                else -> continue
            }
            val parent = texts[i].parent as? android.view.ViewGroup ?: continue
            // 一行里是「涨家数 40 跌家数 7」四个节点共一个父容器：
            // 只能从标签自己往后找第一个数字，不能每回都从头找 —— 之前两个标签都命中同一个 40，
            // 后写的跌家数把涨家数盖掉，真机显示成「涨家数 8 跌家数 7」。
            var self = -1
            for (j in 0 until parent.childCount) {
                if (parent.getChildAt(j) === texts[i]) { self = j; break }
            }
            if (self < 0) continue
            for (j in self + 1 until parent.childCount) {
                val c = parent.getChildAt(j) as? TextView ?: continue
                if (c.text?.toString()?.trim()?.toIntOrNull() == null) continue
                c.text = target.toString()
                // 认出来的这颗数字记下来：下一跳就不必再扫整棵树
                if (state != null) {
                    when (label) {
                        "涨家数" -> state.upTvRef = WeakReference(c)
                        "跌家数" -> state.downTvRef = WeakReference(c)
                    }
                }
                ok = true
                break
            }
        }
        return ok
    }

    private fun collectTextViews(v: View?, out: MutableList<TextView>) {
        if (v == null) return
        if (v is TextView) out.add(v)
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) collectTextViews(v.getChildAt(i), out)
        }
    }
    private fun collectLists(v: View?, out: MutableList<View>) {
        if (v == null) return
        if (v is ListView || v.javaClass.name == StrategyMergeTargets.CLS_RECYCLER_VIEW) out.add(v)
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) collectLists(v.getChildAt(i), out)
        }
    }

    // ------------------------------------------------------------------ 编排

    /**
     * 一轮聚合的编排：判定 → 防重入 → 逐成员借宿主取数 → 合并 → 写回。
     *
     * 所有早退都走 [noteSkip]，只写一行 INFO、**不改宿主任何东西**。
     */
    private fun start(env: FeatureEnv, act: Activity, lv: ListView?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { start(env, act, lv) }
            return
        }
        if (act.isFinishing || act.isDestroyed) return

        val cfg = env.config
        if (!cfg.stgMerge) {
            noteSkip(act, "STG_MERGE 未开启")
            return
        }
        // 成员可能只填了名称，stId 得靠索引补；索引先查内存，内存空了再补读宿主磁盘缓存
        StrategyStgIndex.bind(act)

        val merge = try {
            StrategyMergeStore.decode(cfg.stgMergeGroups)
        } catch (t: Throwable) {
            noteSkip(act, "聚合配置解码异常：${t.javaClass.simpleName}")
            return
        }

        val model = StrategyMergeTargets.field(act, *StrategyMergeTargets.ACT_FIELD_MODEL)
        if (model == null) {
            noteSkip(act, "取不到页面模型字段（${StrategyMergeTargets.ACT_FIELD_MODEL.joinToString("/")}）")
            return
        }
        val stg = StrategyMergeTargets.field(model, StrategyMergeTargets.MODEL_FIELD_STG)
        if (stg == null) {
            noteSkip(act, "取不到 m_stg（页面模型还没准备好）")
            return
        }
        val pageStId = (StrategyMergeTargets.field(stg, StrategyMergeTargets.STG_FIELD_ID) as? String)?.trim()
        if (pageStId.isNullOrEmpty()) {
            noteSkip(act, "当前页 m_stg.id 为空")
            return
        }

        val pageName = (StrategyMergeTargets.field(stg, StrategyMergeTargets.STG_FIELD_NAME) as? String)?.trim().orEmpty()
        // 先把自己登记进名称索引：成员只填名称时，靠它把「当前页」也解析成 stId
        StrategyStgIndex.remember(pageName, pageStId)
        // 这里不再补发策略清单：详情页只有当前这一条策略，发出去要么是一份残缺清单、
        // 要么得回头去读索引全量（含已删除的旧策略，见 StrategyStgCatalog 类注释）。
        // 清单只由列表页按「页面实测条目」发送；用户要导入，本来就得先打开一次「我的策略」。

        // 只认「已启用且成员里包含当前页策略」的那一个组；一个策略同时属于多个组时取第一个。
        // 三条匹配路，按可靠性排序：
        //  ① stId：成员填了真实编号，或成员只填名称时用列表页采到的「名称 → stId」索引解析出来再比；
        //  ② 成员名与当前页名相等（用户点的是原生成员卡）；
        //  ③ 当前页名是我们自己写的入口名「聚合 · 组名（N 条）」时，还原出组名去认组。
        // 没有③就会出现真机实测过的怪相：点「聚合 · 创新高20日（4 条）」不聚合，
        // 点同一组里的「50e」「100e」却正常 —— 因为入口卡的名字被我们改过，两条名字路都对不上。
        fun hits(m: MergeMember): Boolean {
            val sid = m.stId.trim().ifEmpty { StrategyStgIndex.idOf(m.name.trim()).orEmpty() }
            if (sid.isNotEmpty() && sid == pageStId) return true
            val nm = m.name.trim()
            return nm.isNotEmpty() && nm == pageName
        }
        val labelNames = entryLabelGroupNames(pageName)
        // ⚠️ 不要按「克隆卡的 stId」认组：克隆卡与原卡是**同一个 stId**，按 stId 认会把
        // 用户点自己的「量500」也当成聚合入口。详情页只认名字（② 那条），克隆卡带的就是入口名。
        // ③ 卡名与「组名」精确相等（2026-11-12 卡名去掉「（N 条）」以后新增的一条路）：
        //    组名自己就以「聚合」开头时（如「聚合组2」）卡名就是裸组名，而
        //    entryLabelGroupNames 只认带「聚合 · 」前缀的裸名，靠它认不出来 ——
        //    真机症状是「点聚合组2 的卡进去不聚合：跳过：没有包含当前策略的已启用聚合组」。
        val group = merge.groups.firstOrNull { g -> g.enabled && g.members.any { hits(it) } }
            ?: merge.groups.firstOrNull { g ->
                val gn = g.name.trim()
                g.enabled && gn.isNotEmpty() &&
                    (labelNames.contains(gn) || pageName == gn || pageName == entryLabelOf(gn, g.members.size))
            }

        if (group == null) {
            noteSkip(act, "没有包含当前策略的已启用聚合组（stId=$pageStId name=$pageName）")
            return
        }
        // 组名可能重复、成员也可能被重复勾选：按 stId 去重，保持组内顺序。
        // 只填了名称的成员在这一步解析成 stId；解析不出来的先跳过（名称还没在宿主里见过）。
        val members = LinkedHashMap<String, String>()
        val unresolved = ArrayList<String>()
        for (m in group.members) {
            val nm = m.name.trim()
            // 口径成员：名字正好是目录里的四个口径之一，**且没填 stId**。
            // 填了 stId 说明用户真建了一条同名策略，那条能直接取，优先按策略走。
            // 展开成「市值档 × 量比档」若干条，每条取数时现建一条临时策略顶上去
            // （宿主账号只有 10 个策略名额，20 个组合装不下，只能一条一删）。
            // 判据在 StrategyComboCatalog.bucketOfMember 里只有一份：组装、算条数、拼标签三处共用。
            val bucket = StrategyComboCatalog.bucketOfMember(m.name, m.stId)
            if (bucket != null) {
                for (c in StrategyComboCatalog.combosOf(bucket)) {
                    val key = StrategyComboCatalog.COMBO_PREFIX + c.key
                    if (!members.containsKey(key)) {
                        members[key] = StrategyComboCatalog.displayName(c.key)
                    }
                }
                continue
            }
            // 自定义条件成员（`cond:市场档,市场档|量比档,量比档`）：用户在设置页自己选的档位区间。
            // 与口径成员同一套处理：不进宿主策略名单，取数时现建临时策略。
            val cond = StrategyComboCatalog.conditionOfMember(m.name, m.stId)
            if (cond != null) {
                for (c in StrategyComboCatalog.combosOf(cond)) {
                    val key = StrategyComboCatalog.COMBO_PREFIX + c.key
                    if (!members.containsKey(key)) {
                        members[key] = StrategyComboCatalog.displayName(c.key)
                    }
                }
                continue
            }
            // 自定义标签成员（`label:<labelId>`）：用户在设置页从宿主标签表里直接点的一档，
            // 与口径 / 条件成员同一条取数路径 —— 现建一条只含这一个标签的临时策略，取完即删。
            val labelId = StrategyComboCatalog.labelIdOf(nm)
            if (labelId != null) {
                // 父标签（「量比」「30分钟MACD」这种还要选一档的）本身接不了数：实测宿主
                // createStrategy 返回成功、成分股 0 只。用户在成员行 ⋮ 里选完档才变成一条
                // `label:<档位labelId>`，那时才进队列。这里先跳过，别白建一条 0 只的临时策略。
                if (!StrategyLabelTable.isParentOf(labelId)) {
                    val ckey = StrategyComboCatalog.COMBO_PREFIX + nm
                    if (!members.containsKey(ckey)) members[ckey] = StrategyLabelTable.displayOf(labelId)
                } else {
                    GLog.i("$LOG 成员「" + StrategyLabelTable.displayOf(labelId) + "」还没选档位，本轮跳过")
                }
                continue
            }
            var sid = m.stId.trim()
            if (sid.isEmpty()) sid = StrategyStgIndex.idOf(nm).orEmpty()
            if (sid.isEmpty()) {
                if (nm.isNotEmpty()) unresolved.add(nm)
                continue
            }
            if (members.containsKey(sid)) continue
            members[sid] = nm
        }
        if (unresolved.isNotEmpty()) {
            GLog.i(
                "$LOG 成员名称还没在宿主里见过，本轮先跳过：${unresolved.joinToString("、")}" +
                    "（索引 ${StrategyStgIndex.size()} 条：${StrategyStgIndex.namesSnapshot()}）",
            )
        }
        if (members.size < 2) {
            noteSkip(act, "聚合组成员不足 2 个（stId=$pageStId，已解析 ${members.size} 个）")
            return
        }

        val adapter = resolveAdapter(act, lv)
        if (adapter == null) {
            noteSkip(act, "取不到列表适配器（m_stkAdapter / ListView）")
            return
        }

        val targets = members.entries.filter { it.key != pageStId }.map { it.key to it.value }
        val memberIds = members.keys.toList()
        // 头部名字与列表入口卡同口径：列表是「聚合 · 组名（N 条）」，点进来必须一模一样
        val label = buildLabel(group.name, members.size)
        val state = stateOf(act)
        val key = pageStId + "|" + memberIds.joinToString(",") + "|" +
            Integer.toHexString(System.identityHashCode(adapter))

        // 防重入：宿主 onResume / setAdapter / 自己的刷新会连发，一次聚合只能有一条流水线
        var skip: String? = null
        var rollbackFirst = false
        synchronized(state) {
            val merged = state.mergedStrong
            val data = StrategyMergeTargets.listField(adapter, StrategyMergeTargets.ADAPTER_FIELD_DATA)
            val idle = System.currentTimeMillis() - state.finishedAt
            if (state.running) {
                skip = "上一轮聚合还没结束"
            } else if (sameContents(merged, data)) {
                skip = "本页已是聚合结果（${state.mergedSize} 只）"
            } else if (state.key == key && state.finishedAt > 0L && idle >= 0L && idle < SETTLE_MS) {
                skip = "上一轮刚结束 ${idle}ms，等宿主这轮刷新过去"
            } else if (state.runKey == key &&
                System.currentTimeMillis() - state.runWindowStart < StrategyMergeTargets.RUN_WINDOW_MS &&
                state.runCount >= StrategyMergeTargets.MAX_RUNS_PER_WINDOW
            ) {
                // 宿主反复刷新把结果顶掉、我们反复重取 —— 活锁，停手让它显示自己的单策略
                skip = "同一页 ${state.runCount} 次聚合都在 ${StrategyMergeTargets.RUN_WINDOW_MS}ms 内（宿主反复刷新），先停手"
            } else {
                rollbackFirst = state.applied && state.key != key
            }
        }
        val why = skip
        if (why != null) {
            noteSkip(act, why)
            return
        }
        if (rollbackFirst) {
            GLog.i("$LOG 本页换了策略（${state.key} → $key），先还原上一轮聚合")
            releaseRollback(act, state)
        }

        // 本页从此**按组**记筛选状态（「按策略保存筛选条件」开着时才生效，见 StockFilterScopes）：
        // 聚合期间宿主会把 m_stg.id 临时换成成员 stId、把 name 改写成「聚合 · 组名」，
        // 只有组 id 是这一页的稳定身份。登记必须排在**改宿主之前**（下面 applySnapshot 就会改），
        // 撤销点在 releaseRollback / onActivityDestroyed。
        if (StockFilterScopes.rememberMergePage(act, group.id, group.name)) {
            GLog.i("$LOG 本页筛选状态改为按聚合组 ${group.id}（${group.name}）分开记")
        }

        val cacheKey = group.id + "|" + memberIds.joinToString(",")
        val cachedSnap = StrategyMergeCache.get(cacheKey)
        synchronized(state) {
            val now = System.currentTimeMillis()
            if (state.runKey != key || now - state.runWindowStart >= StrategyMergeTargets.RUN_WINDOW_MS) {
                state.runKey = key
                state.runCount = 0
                state.runWindowStart = now
            }
            state.runCount++
            state.running = true
            state.key = key
            state.adapterRef = WeakReference<Any>(adapter)
            captureOrigin(act, state, model, stg, adapter, pageStId)
        }
        logWiring(act, "start")
        // 上次算出来的完整并集先套上：用户第一眼就是完整结果，这一轮只当后台校验。
        // 没有它，每进一次页面都要看「宿主单策略 → 残缺并集 → 完整并集」来回翻三次。
        if (cachedSnap != null && applySnapshot(act, state, model, stg, adapter, cachedSnap)) {
            GLog.i(
                "$LOG 先用上次的 ${cachedSnap.size} 只（缓存 " +
                    (System.currentTimeMillis() - cachedSnap.at) + "ms 前），后台校验这一轮",
            )
        }
        val run = MergeRun(env, act, state, model, stg, adapter, pageStId, targets, label, memberIds, cacheKey)
        synchronized(state) { state.run = run }
        GLog.i(
            "$LOG 开始聚合 stId=$pageStId，组 ${members.size} 个成员" +
                "（待取 ${targets.size} 个：${targets.joinToString(",") { it.second }}），" +
                "本页第 ${state.runCount} 轮",
        )
        run.start()
    }

    /**
     * 页面字段 `m_stkAdapter` 优先（与 ListView 里包着的是同一对象），取不到再从 ListView 剥 Header。
     *
     * **只认 `AIStkAdapter`**：宿主在数据还没回来时会挂 `AIStkEmptyAdapter`，它把数据项当 String 用
     * （`AIStkEmptyAdapter.convert` 第一行就是强转），往里写 StockInfo 会直接
     * `ClassCastException: StockInfo cannot be cast to String` 崩掉宿主（真机 2026-11-11 21:06 两次）。
     * 认不出就返回 null —— 不聚合只是没效果，崩宿主是事故。
     */
    private fun resolveAdapter(act: Activity, lv: ListView?): Any? {
        val field = StrategyMergeTargets.field(act, *StrategyMergeTargets.ACT_FIELD_ADAPTER)
        if (field != null && StrategyMergeTargets.isPageAdapter(field.javaClass.name)) return field
        val list = lv ?: currentListView(act)
        val unwrapped = StrategyMergeTargets.unwrapListAdapter(list?.adapter)
        if (unwrapped != null && StrategyMergeTargets.isPageAdapter(unwrapped.javaClass.name)) return unwrapped
        if (unwrapped != null) {
            GLog.i(
                "$LOG 本页适配器是 ${unwrapped.javaClass.name}" +
                    "（不是 ${StrategyMergeTargets.CLS_PAGE_ADAPTER}），本轮不聚合",
            )
        }
        return null
    }

    /** 按资源名找这页的股票 ListView（`lvStks`） */
    private fun currentListView(act: Activity): ListView? {
        // 资源名查 id（viewIdOrZero 走 getIdentifier，是字符串查表）在每 700ms 一跳的看门狗里太浪费，
        // 查一次记在 PageState 上：-1 = 还没查，0 = 这页确实没有。
        val state = stateOf(act)
        if (state.listId < 0) {
            state.listId = act.resources.viewIdOrZero(*StrategyMergeTargets.LIST_ID_CANDIDATES)
        }
        if (state.listId == 0) return null
        return runCatching { act.findViewById<View>(state.listId) as? ListView }.getOrNull()
    }

    // ------------------------------------------------------------------ 快照 / 回滚

    /** 记下「动手之前」的原样：中途任何一步炸了都要能原样还回去 */
    private fun captureOrigin(
        act: Activity,
        state: PageState,
        model: Any,
        stg: Any,
        adapter: Any,
        pageStId: String,
    ) {
        state.originalStId = pageStId
        state.originalName = StrategyMergeTargets.field(stg, StrategyMergeTargets.STG_FIELD_NAME) as? String
        state.originalUp = StrategyMergeTargets.intField(model, StrategyMergeTargets.MODEL_FIELD_UP_NUM)
        state.originalDown = StrategyMergeTargets.intField(model, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM)
        state.originalData = StrategyMergeTargets.listField(adapter, StrategyMergeTargets.ADAPTER_FIELD_DATA)
        state.originalPrice = StrategyMergeTargets.mapField(adapter, StrategyMergeTargets.ADAPTER_FIELD_PRICE_MAP)
        state.originalResNum = readResNum(act, state)
        state.hostResNumTail = state.originalResNum
            ?.let { StrategyMergeTargets.RES_NUM_REGEX.find(it)?.groupValues?.getOrNull(1) }
            ?: state.hostResNumTail
        state.applied = false
        state.touchedId = false
        state.mergedRef = null
        state.mergedStrong = null
        state.mergedSize = 0
        state.finishedAt = 0L
    }

    /**
     * 把宿主还原成「没聚合过」的样子。
     *
     * 只还原**真正动过**的部分：`applied` 才算改过列表/头部，`touchedId` 才算改过 `m_stg.id`；
     * 全程 `runCatching` + 尽力而为 —— 页面可能已经在销毁路上，还原失败只写日志，不抛。
     */
    private fun releaseRollback(act: Activity, state: PageState) {
        val adapter = state.adapterRef?.get()
        val model = StrategyMergeTargets.field(act, *StrategyMergeTargets.ACT_FIELD_MODEL)
        val stg = StrategyMergeTargets.field(model, StrategyMergeTargets.MODEL_FIELD_STG)
        val applied = state.applied
        val touchedId = state.touchedId
        runCatching {
            if (applied) {
                state.originalData?.let {
                    StrategyMergeTargets.write(adapter, StrategyMergeTargets.ADAPTER_FIELD_DATA, it)
                }
                state.originalPrice?.let {
                    StrategyMergeTargets.write(adapter, StrategyMergeTargets.ADAPTER_FIELD_PRICE_MAP, it)
                }
                StrategyMergeTargets.notifyAdapter(adapter)
                state.originalName?.let {
                    StrategyMergeTargets.write(stg, StrategyMergeTargets.STG_FIELD_NAME, it)
                }
                StrategyMergeTargets.write(model, StrategyMergeTargets.MODEL_FIELD_UP_NUM, state.originalUp)
                StrategyMergeTargets.write(model, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM, state.originalDown)
                state.originalResNum?.let { writeResNum(act, it, state) }
                StrategyMergeTargets.showStg(act)
            }
            if (touchedId) {
                state.originalStId?.let {
                    StrategyMergeTargets.write(stg, StrategyMergeTargets.STG_FIELD_ID, it)
                }
            }
        }.onFailure { GLog.w("$LOG 还原宿主时出错（尽力而为）", it) }
        state.applied = false
        state.touchedId = false
        state.originalData = null
        state.originalPrice = null
        state.originalResNum = null
        clearState(act, state)
        // 这一页不再是「某一组」了：撤销筛选作用域登记，回到按策略记 ——
        // 不清的话关掉「策略合并」之后，本页还会继续沿用那一组的筛选条件。
        StockFilterScopes.forgetMergePage(act)
        GLog.i("$LOG 已还原宿主（applied=$applied touchedId=$touchedId）")
    }

    /**
     * 两个列表是不是「同一份内容」：长度相同且逐项 `===`（优先按身份直判）。
     *
     * 宿主每次刷新都会**重造**一份列表，只比实例身份的话每一轮刷新都会重新聚合一次
     * （8 秒一批请求）；比内容能把「宿主自己刷了但内容没变」这种情况吃掉。
     */
    private fun sameContents(a: List<*>?, b: List<*>?): Boolean {
        if (a == null || b == null) return false
        if (a === b) return true
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }

    /** 清记账；进程级快照只在自己这页的东西才清，别把别页的结果抹了 */
    private fun clearState(act: Activity, state: PageState) {
        state.mergedRef = null
        state.mergedStrong = null
        state.mergedSize = 0
        state.finishedAt = 0L
        state.run = null
        synchronized(state) { state.running = false }
        if (StrategyMergeBridge.isFor(act)) StrategyMergeBridge.clear()
    }

    // ------------------------------------------------------------------ 头部文案

    /** 「策略个股 N 只」：宿主只按自己的单策略写，聚合后不会自己按 data 重算，得自己填 */
    private fun writeResNum(act: Activity, text: String, state: PageState? = null): Boolean {
        val cached = state?.resNumTvRef?.get()
        if (cached != null && cached.isAttachedToWindow) {
            if (cached.text?.toString() == text) return true
            return runCatching {
                cached.text = text
                true
            }.getOrDefault(false)
        }
        val id = act.resources.viewIdOrZero(*StrategyMergeTargets.RES_NUM_ID_CANDIDATES)
        if (id == 0) {
            GLog.i("$LOG 找不到 tv_stg_res_num，计数文案保持宿主原样")
            return false
        }
        val tv = runCatching { act.findViewById<View>(id) as? TextView }.getOrNull() ?: return false
        if (state != null) state.resNumTvRef = WeakReference(tv)
        return runCatching {
            tv.text = text
            true
        }.getOrDefault(false)
    }

    /** 读当前计数文案（回滚要原样还回去，不能按只数猜） */
    private fun readResNum(act: Activity, state: PageState? = null): String? {
        val cached = state?.resNumTvRef?.get()
        if (cached != null && cached.isAttachedToWindow) return runCatching { cached.text?.toString() }.getOrNull()
        val id = act.resources.viewIdOrZero(*StrategyMergeTargets.RES_NUM_ID_CANDIDATES)
        if (id == 0) return null
        val tv = runCatching { act.findViewById<View>(id) as? TextView }.getOrNull() ?: return null
        if (state != null) state.resNumTvRef = WeakReference(tv)
        return runCatching { tv.text?.toString() }.getOrNull()
    }

    /**
     * 写计数文案 —— **筛选生效时写成「策略个股 保留数/总数」**。
     *
     * 两个功能写的是同一个 `#tv_stg_res_num`：stgfilter 按筛选结果改成 `策略个股 36/212`，
     * 聚合又按并集只数写回 `策略个股212只` —— 用户看到的就是「筛是筛了，数字没动」
     * （真机 2026-11-12 01:12：列表只剩 36 行跌家股票，左上角还写着 212 只）。
     *
     * 判据不用类名（筛选代理会被 R8 混淆），直接看**列表现在挂出来多少行**：
     * 少于并集只数就是筛选中。两边格式逐字一致，写同值互相 no-op，不会来回覆盖。
     */
    private fun writeMergedResNum(act: Activity, state: PageState, total: Int) {
        val cur = readResNum(act, state)
        val shown = shownStockRows(act)
        val filtered = shown != null && total > 0 && shown < total
        val want = if (filtered) {
            String.format(Locale.ROOT, StrategyMergeTargets.RES_NUM_FILTERED_FORMAT, shown, total)
        } else {
            // 筛选态那串「36/212」抽不出尾巴，回落到进页面时记下的宿主原样
            val tail = cur?.let { StrategyMergeTargets.RES_NUM_REGEX.find(it)?.groupValues?.getOrNull(1) }
                ?: state.hostResNumTail.orEmpty()
            String.format(Locale.ROOT, StrategyMergeTargets.RES_NUM_FORMAT, total) + tail
        }
        if (cur == want) return
        if (writeResNum(act, want, state) && filtered) {
            GLog.i("$LOG 计数 → $want（筛选中：列表 $shown 行 / 并集 $total 只）")
        }
    }

    /**
     * 列表**真正显示**的股票行数（筛选生效时是子集行数），拿不到返回 null。
     *
     * 详情页的「策略名称 / 涨跌家数 / 标签 / 计数栏」全在 ListView 的 header 里，
     * 所以 `adapter.count` 要减掉头尾行。`getHeadersCount/getFootersCount` 只有
     * `HeaderViewListAdapter` 有，用反射取（framework 里这个类是隐藏实现，别硬转）。
     */
    private fun shownStockRows(act: Activity): Int? {
        val lv = currentListView(act) ?: return null
        val la = lv.adapter ?: return null
        val inner = StrategyMergeTargets.unwrapListAdapter(la) ?: return null
        // 宿主原生适配器直接挂着 = 没筛，返回 null 让调用方按全量写（与改造前完全一致）
        if (StrategyMergeTargets.isPageAdapter(inner.javaClass.name)) return null
        // 只有「里面那层基座是宿主原生适配器」才认作筛选代理；宿主的空适配器 / 别的列表都不算，
        // 否则进页面那一瞬（列表还没数据）会写成「策略个股 0/212」闪一下
        if (proxyBaseAdapter(inner) == null) return null
        val count = runCatching { la.count }.getOrNull() ?: return null
        return count - (intMethodOf(la, "getHeadersCount") ?: 0) - (intMethodOf(la, "getFootersCount") ?: 0)
    }

    /**
     * 筛选代理包里那个原生适配器（筛到 0 只也算筛选态，得认得出代理本身）。
     *
     * 不按类名认：模块发布构建会走 R8，代理类名会被混淆成 `f2.m`。代理里那个基座字段的
     * **声明类型是 `android.widget.ListAdapter`**（framework 接口，不会被改名），
     * 所以扫字段找「值是宿主原生适配器」的那一个就够了。
     */
    private fun proxyBaseAdapter(inner: Any): Any? {
        var c: Class<*>? = inner.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (!android.widget.ListAdapter::class.java.isAssignableFrom(f.type)) continue
                runCatching { f.isAccessible = true }.getOrNull() ?: continue
                val v = runCatching { f.get(inner) }.getOrNull() ?: continue
                if (StrategyMergeTargets.isPageAdapter(v.javaClass.name)) return v
            }
            c = c.superclass
        }
        return null
    }

    private fun intMethodOf(target: Any, name: String): Int? =
        Reflect.findMethod(target.javaClass, name)?.let { m ->
            runCatching { m.invoke(target) as? Int }.getOrNull()
        }

    /**
     * 聚合后的头部策略名 —— 与列表入口卡**同一个函数**（`entryLabelOf`）算出来。
     *
     * 用户的原话是「外面那个名字点进去就变了」。以前这里回落成成员名拼接
     * 「聚合 · 50e + 100e + 70e + …」：看不懂，还占三行把涨跌家数挤下去。
     * 现在只认组名，组名空了才用「聚合策略」；过长一律截断（头部一行放不下会把涨跌数挤掉）。
     */
    private fun buildLabel(groupName: String?, memberCount: Int): String {
        val text = entryLabelOf(groupName.orEmpty(), memberCount)
        return if (text.length <= StrategyMergeTargets.MERGE_NAME_MAX) {
            text
        } else {
            text.take(StrategyMergeTargets.MERGE_NAME_MAX) + "…"
        }
    }

    // ------------------------------------------------------------------ 记账

    private fun stateOf(act: Activity): PageState = synchronized(states) {
        states.getOrPut(act) { PageState() }
    }

    /** 早退原因只写一次 INFO（同页同原因），避免宿主 onResume 反复刷屏 */
    private fun noteSkip(act: Activity, why: String) {
        val tag = act.javaClass.name + "#" + System.identityHashCode(act) + "#" + why
        if (lastSkip.get() == tag) return
        lastSkip.set(tag)
        GLog.i("$LOG 跳过：$why")
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 开关被关掉：把已经聚合过的页面还原回去。
     *
     * [Feature] 约定这个方法在**主线程**调，所以这里可以直接碰视图。
     */
    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        if (cfg.stgMerge) return
        val watches = synchronized(listWatches) { listWatches.keys.toList() }
        for (a in watches) stopListWatch(a)
        entryLabels.clear()
        val pages = synchronized(states) { states.entries.map { it.key to it.value } }
        for ((act, state) in pages) {
            state.run?.cancel()
            runCatching { releaseRollback(act, state) }
                .onFailure { GLog.w("$LOG 关功能时还原失败", it) }
        }
        StrategyMergeBridge.clear()
        if (pages.isNotEmpty()) GLog.i("$LOG 开关已关，还原 ${pages.size} 页")
    }

    /**
     * 页面销毁：只清记账，**不在这里还原现场**（[Feature] 的约定）。
     *
     * 宿主已经自己在收尾了，再往上写适配器只会踩到「已经 detach 的列表」。
     */
    override fun onActivityDestroyed(activity: Activity) {
        // 筛选作用域登记跟着这页一起作废（弱键表本来也会随 Activity 回收，这里显式清一次，
        // 免得「同一页重建后先命中旧登记」）
        StockFilterScopes.forgetMergePage(activity)
        // 列表页没有 PageState（不改股票列表），巡检循环要单独停，否则它的 postDelayed 会把整页拖住
        stopListWatch(activity)
        val state = synchronized(states) { states.remove(activity) } ?: return
        state.run?.cancel()
        stopWatch(state)
        GLog.i("$LOG 页面销毁，清掉记账（聚合 ${state.mergedSize} 只）")
    }

    // ------------------------------------------------------------------ 记账结构

    /** 一页的聚合记账（字段含义见各条注释） */
    private class PageState {
        /** 一轮聚合进行中（防重入的第一道闸） */
        var running = false

        /** 本轮判定键：页面 stId + 成员 stId 列表 + 适配器身份 */
        var key: String? = null

        /** 上一轮结束时刻（0 = 没跑过），配合 [SETTLE_MS] 吃掉宿主的连发刷新 */
        var finishedAt = 0L

        /** 次数统计的键 / 窗口起点 / 次数：防止「宿主刷新顶掉结果 → 我们重取」的活锁 */
        var runKey: String? = null
        var runWindowStart = 0L
        var runCount = 0

        /** 当前流水线（关功能 / 销毁时要能叫停） */
        var run: MergeRun? = null

        /** 写回数据的那个适配器（弱引用：不能反向把 Activity 钉住） */
        var adapterRef: WeakReference<Any>? = null

        /**
         * 装股票的那个 [ListView]（弱引用）。
         *
         * 宿主会把列表适配器换成空占位（[StrategyMergeTargets.CLS_EMPTY_ADAPTER]），
         * 那时我们手里只剩适配器实例、没有视图，就没法把并集装回去 —— 记一份列表引用。
         */
        var lvRef: WeakReference<Any>? = null
        var watch: Runnable? = null

        /** 写回适配器的并集实例（与 `adapter.data` 同一对象即「界面上已经是聚合结果」） */
        var mergedRef: WeakReference<Any>? = null

        /**
         * 同一份并集的**强引用**。
         *
         * `mergedRef` 是弱引用，而这份 list 在交出去之后就没有别人强引用着（适配器拿的是我们
         * 另交的一份副本），GC 随时能把它收走。真机表现：进页面头几帧「顶住并集」生效，随后
         * `mergedRef.get()` 变 null，界面当场退回宿主那一格（6 只 / 99 只）并一路越长越少地抽。
         */
         var mergedStrong: List<Any?>? = null

        var mergedSize = 0

        /** 本轮「顶住并集」只打一条日志，别刷屏 */
        var holdNoted = false

        /** 原样快照：出岔子要还回去的那份 */
        var originalStId: String? = null
        var originalName: String? = null
        var originalUp = 0
        var originalDown = 0
        var originalData: List<*>? = null
        var originalPrice: Map<*, *>? = null
        var originalResNum: String? = null

        /**
         * 宿主计数文案里那截尾巴「(11-12 01:10:15)」。
         *
         * 筛选生效时我们写的是「策略个股 36/212」（与 stgfilter 同格式、不带尾巴），
         * 从筛选切回不筛时就读不到尾巴了 —— 记一份原样，切回去还能带上取数时间。
         */
        var hostResNumTail: String? = null

        /**
         * 头部「涨家数 / 跌家数」两颗数字与计数栏的弱引用。
         *
         * 看门狗 700ms 一跳，每跳都从 decorView 全树扫一遍 TextView（这一页有 60 多个），
         * 叠加宿主自己每帧的 notify —— 用户在多选态勾股票、点「全选 / 反选」时感知最明显。
         * 缓存之后稳态只剩几次 String 比较，不再碰视图树。
         */
        /** 上一次走完整慢路的时刻，配合 [FAST_SLOW_MS] 把一次通知风暴并成一次（见 [reapplyIfNeeded]） */
        var lastSlowAt = 0L

        /** `lvStks` 的资源 id 缓存：-1 还没查过，0 = 这页没有 */
        var listId = -1

        var upTvRef: WeakReference<TextView>? = null
        var downTvRef: WeakReference<TextView>? = null
        var resNumTvRef: WeakReference<TextView>? = null

        /** 是否写过 `m_stg.id`（回滚只还原动过的） */
        var touchedId = false

        /** 是否已经改过适配器 / 头部（回滚只还原动过的） */
        var applied = false

        /** 最近一次成功聚合的家数合计：宿主刷新后重新套回时头部要用 */
        var upSum = 0
        var downSum = 0

        /** 上次「刷新后重新套回」的时刻，配合 [REAPPLY_MIN_INTERVAL_MS] 防活锁 */
        var lastReapplyAt = 0L

        /** 聚合后的策略名（「聚合 · a + b」）：宿主重画时要把名字再按它写一遍 */
        var label: String? = null

        /** 合并后的标签文本（宿主刷新会重画标签条，看门狗按这份补回去；null = 本轮没有标签） */
        var tagTexts: List<String>? = null

        /** 一个宿主 Tag 样本：造新 Tag 时抄它的 tagId / isValid */
        var tagSample: Any? = null

        /** 标签条那个 RecyclerView（弱引用，免得每次巡检都遍历整棵视图树） */
        var tagRvRef: WeakReference<Any>? = null
    }

    // ------------------------------------------------------------------ 流水线

    /**
     * 一轮聚合的流水线：逐成员借宿主自己的取数通道，最后一次性写回。
     *
     * 为什么必须借宿主的取数：聚合组成员可能是别的分组 / 别的策略，本模块拼不出请求参数；
     * 只有换掉 `m_stg.id` 让宿主按它自己的逻辑取，拿到的才是**宿主自己的** `StockInfo`
     * 实例（去重、价位、点击全靠这个身份，自建包装类会全部失效）。
     *
     * 时序：`start` → 等本页自己的列表 → `fetchNext`（写 id + 发命令）→ 主线程轮询 `poll`
     * → 列表换了就 `absorb` → 下一个成员 → `finish`。
     */
    private inner class MergeRun(
        private val env: FeatureEnv,
        private val act: Activity,
        private val state: PageState,
        private val model: Any,
        private val stg: Any,
        private val adapter: Any,
        private val pageStId: String,
        private val targets: List<Pair<String, String>>,
        private val label: String,
        private val memberIds: List<String>,
        private val cacheKey: String,
    ) {
        /** `codeAndMarket` → 宿主 StockInfo；LinkedHashMap 保序，保留**首次出现**的那个实例 */
        private val uniq = LinkedHashMap<String, Any>()

        /** 合并后的 `priceMap`：同一个键保留先见到的价（= 本页策略的价优先，与探针一致） */
        private val prices = LinkedHashMap<Any, Any>()

        private var upSum = 0
        private var downSum = 0

        /** 待取成员队列。某个成员没取到会重新排到队尾，所以不能再用下标推进 */
        private val queue = ArrayDeque<Pair<String, String>>().apply { addAll(targets) }

        /** 已经重排过的成员：每个成员只重试 [StrategyMergeTargets.RETRY_ROUND] 次，防止死循环 */
        private val retried = HashSet<String>()

        /** 已经发出去几次请求（日志分母，重试会让它超过成员数） */
        private var attempts = 0

        /** 上一次因「id 被宿主改回」而重发命令的时刻：节流用，别把宿主喂成请求风暴 */
        private var lastResendAt = 0L

        /**
         * 上一帧我们自己写进宿主字段的家数。
         *
         * 取数窗口里要把聚合家数写回 `m_stg`/`model`，宿主重画头部才是聚合口径；
         * 但这两个字段同时又是「这一份响应的家数」，读回来会把自己写的值再累加一遍。
         * 记住写进去的数字，[addStat] 认出来就跳过。
         */
        private var wroteUp = Int.MIN_VALUE
        private var wroteDown = Int.MIN_VALUE

        /**
         * 本轮为口径成员建出来的**临时策略 stId**。
         *
         * 宿主账号上限 10 条，用户已占 8 条 —— 任何时刻只可能多挂 2 条，
         * 所以必须「取完一个立刻删一个」。这个集合就是清理台账：
         * 成员取完、轮次结束、页面销毁，三条路都得回来把这里清空。
         */
        private val tempIds: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

        /**
         * 整轮只建一条临时策略，成员之间靠改标签轮转。
         *
         * 宿主账号上限 10 条策略，用户自己就能占满；「每格建一条再删」在名额紧时
         * 24 个成员会全军覆没（真机 code 4100）。这里任何时刻只占 1 个名额。
         */
        private val comboSlot = StrategyComboClient.TempSlot()

        /** 各成员策略的标签（tagName）：换 id 取数后 model 里就是该成员的，取完一起合并 */
        private val tagNames = LinkedHashSet<String>()

        /** 第一个宿主 Tag 样本，造新 Tag 时抄 tagId / isValid */
        private var tagSample: Any? = null

        /** 有成员超时 / 没取到：界面照常渲染，只是并集不完整 */
        private var degraded = false
        private var cancelled = false

        /**
         * 总超时。普通组沿用固定 16 秒；**带口径成员的组按组合数放大**。
         *
         * 每个组合是「建临时策略 → 宿主取数 → 删临时策略」三步，实测 1.5~2.5 秒；
         * 一个 12 组合的口径固定 16 秒必然截断在半路，结果就是「聚合只出来一半」。
         */
        private val deadline = System.currentTimeMillis() + budgetMs()

        private fun budgetMs(): Long {
            val combos = targets.count { it.first.startsWith(StrategyComboCatalog.COMBO_PREFIX) }
            if (combos == 0) return StrategyMergeTargets.TOTAL_TIMEOUT_MS
            return StrategyMergeTargets.TOTAL_TIMEOUT_MS + combos * COMBO_BUDGET_MS
        }

        /** 叫停流水线，**不碰视图**（页面销毁 / 关功能用，视图由 [releaseRollback] 或宿主自己收） */
        fun cancel() {
            cancelled = true
            state.run = null
            synchronized(state) { state.running = false }
            MainThreadStallWatchdog.stop("取消 stId=$pageStId")
            GLog.i("$LOG 本轮被叫停（已取 ${uniq.size} 只）")
        }

        fun start() {
            MainThreadStallWatchdog.start("聚合 stId=$pageStId 成员 ${targets.size} 个")
            try {
                if (cancelled) return
                if (!isAlive()) {
                    abort("页面已销毁")
                    return
                }
                val seed = seedList()
                if (seed.isNullOrEmpty()) {
                    // 本页自己的列表还没回来：先等它，否则并集里会缺「本页策略」这一份
                    GLog.i("$LOG 本页列表还没加载，先等它出数据（最多 ${StrategyMergeTargets.MEMBER_TIMEOUT_MS}ms）")
                    awaitSeed(System.currentTimeMillis())
                    return
                }
                absorbSeed(seed)
                fetchNext()
            } catch (t: Throwable) {
                abort("起跑异常", t)
            }
        }

        /** 等本页自己的列表（`m_stkList` / `adapter.data` 任意一份非空就够） */
        private fun awaitSeed(startedAt: Long) {
            if (cancelled) return
            if (!isAlive()) {
                abort("页面已销毁")
                return
            }
            val seed = seedList()
            val waited = System.currentTimeMillis() - startedAt
            if (!seed.isNullOrEmpty()) {
                absorbSeed(seed)
                fetchNext()
                return
            }
            if (waited >= StrategyMergeTargets.MEMBER_TIMEOUT_MS ||
                System.currentTimeMillis() > deadline
            ) {
                degraded = true
                GLog.i("$LOG 等 ${waited}ms 本页仍无数据，直接按成员取（本页策略这一份会缺）")
                fetchNext()
                return
            }
            post { awaitSeed(startedAt) }
        }

        /** 本页当前列表：模型里的与适配器里的取**大的那份**（回填过程中两份会短暂不一致） */
        private fun seedList(): List<*>? {
            val a = StrategyMergeTargets.listField(model, StrategyMergeTargets.MODEL_FIELD_STK_LIST)
            val b = StrategyMergeTargets.listField(adapter, StrategyMergeTargets.ADAPTER_FIELD_DATA)
            return when {
                a == null -> b
                b == null -> a
                b.size > a.size -> b
                else -> a
            }
        }

        /** 记下当前 model 上这条策略的标签（换 id 取数后 model 里就是该成员的） */
        private fun absorbTags() {
            val names = StrategyMergeTargets.tagNames(model)
            if (names.isEmpty()) return
            tagNames.addAll(names)
            if (tagSample == null) {
                tagSample = StrategyMergeTargets.listField(model, StrategyMergeTargets.MODEL_FIELD_TAG_LIST)
                    ?.firstOrNull()
            }
        }

        private fun absorbSeed(seed: List<*>?) {
            val added = absorb(seed)
            absorbPrices(StrategyMergeTargets.mapField(adapter, StrategyMergeTargets.ADAPTER_FIELD_PRICE_MAP))
            absorbTags()
            addStat(seed?.size ?: 0)
            GLog.i(
                "$LOG 起点 stId=$pageStId：本页 ${seed?.size ?: 0} 只" +
                    "（去重后 ${uniq.size}，新增 $added），待取成员 ${targets.size} 个",
            )
        }

        /**
         * 下一个成员：先把 `m_stg.id` 换成它，再发宿主的取数命令，然后主线程轮询列表。
         *
         * 换 id 是唯一的开关 —— 写不进这个字段就不可能拿到这一成员，直接跳过（标 degraded）。
         */
        private fun fetchNext() {
            if (cancelled) {
                GLog.w("$LOG 取下一个成员时发现本轮已取消（已取 ${uniq.size} 只，队列还剩 ${queue.size} 个）")
                return
            }
            if (!isAlive()) {
                abort("页面已销毁")
                return
            }
            if (queue.isEmpty()) {
                finish()
                return
            }
            if (System.currentTimeMillis() > deadline) {
                degraded = true
                GLog.i("$LOG 总超时 ${budgetMs()}ms，带已拿到的 ${uniq.size} 只继续")
                finish()
                return
            }
            val member = queue.removeFirst()
            val memberStId = member.first
            val memberName = member.second
            if (memberStId.startsWith(StrategyComboCatalog.COMBO_PREFIX)) {
                openCombo(memberStId.removePrefix(StrategyComboCatalog.COMBO_PREFIX), memberName)
                return
            }
            takeMember(memberStId, memberName)
        }

        /**
         * 口径成员：先在后台建一条临时策略，拿到 stId 再交给宿主取数。
         *
         * 为什么要绕这一圈：宿主列表只认它**自己**的 `StockInfo` 实例，自建包装类在去重 /
         * 价位 / 点击上全失效（见 [absorb] 注释）；而口径不是一条真实策略，只能现建一条
         * 临时策略让宿主按它自己的逻辑取。
         */
        private fun openCombo(comboKey: String, memberName: String) {
            val combo = StrategyComboCatalog.comboOfKey(comboKey)
            if (combo == null) {
                GLog.w("$LOG 口径成员 $memberName 的 key 解析不出来：$comboKey")
                degraded = true
                fetchNext()
                return
            }
            comboExecutor.execute {
                val r = runCatching {
                    val id = comboSlot.ensure(combo)
                    comboSlot.apply(combo)
                    id
                }
                mainHandler.post {
                    if (cancelled) return@post
                    val tmpId = r.getOrNull()
                    if (tmpId == null) {
                        GLog.w("$LOG 口径成员 $memberName 取数失败：" + (r.exceptionOrNull()?.message ?: ""))
                        degraded = true
                        fetchNext()
                        return@post
                    }
                    tempIds.add(tmpId)
                    GLog.i("$LOG 口径成员 $memberName 已把临时策略 $tmpId 的标签切成本组合，交给宿主取数")
                    takeMember(tmpId, memberName)
                }
            }
        }

        /** 写 id + 发命令 + 起轮询；普通成员与口径的临时策略共用这一段 */
        private fun takeMember(memberStId: String, memberName: String) {
            val base = signature()
            // 取数窗口里我们可能把并集顶在适配器上（holdUnionWhileRunning），
            // 那一枪会改 `data` —— 只靠签名会把「自己顶的那一枪」当成宿主返回。
            // 代次只由「宿主自己塞进来的那一帧」推进，判到达用它。
            val baseGen = payloadGen()
            if (!StrategyMergeTargets.write(stg, StrategyMergeTargets.STG_FIELD_ID, memberStId)) {
                GLog.w("$LOG 成员 $memberName（$memberStId）写不进 m_stg.id")
                if (!retryOrGiveUp(memberStId, memberName, "写不进 id")) degraded = true
                fetchNext()
                return
            }
            state.touchedId = true
            if (!dispatchCommand()) {
                GLog.w("$LOG 成员 $memberName（$memberStId）的取数命令没发出去")
                if (!retryOrGiveUp(memberStId, memberName, "命令没发出去")) degraded = true
                fetchNext()
                return
            }
            attempts++
            GLog.i("$LOG 请求成员第 $attempts 次：$memberName（$memberStId）")
            val startedAt = System.currentTimeMillis()
            post { poll(memberStId, memberName, base, baseGen, startedAt) }
        }

        /**
         * 口径成员不能重排：队尾再排一轮时，那条整轮复用的临时策略早被下一个组合改了标签，
         * 写进去的 id 还在但内容已经不是这一格了，重试只会把别人的股票算进来。
         * 所以就地放弃、标 degraded、继续下一个成员；临时策略留到整轮收尾再删。
         */
        private fun retryOrGiveUp(memberStId: String, memberName: String, why: String): Boolean {
            if (!tempIds.contains(memberStId)) return requeue(memberStId, memberName, why)
            GLog.w("$LOG 口径成员 $memberName（$memberStId）$why，临时策略整轮复用，这一格不重试")
            return false
        }

        /** 轮次结束兜底：把还没删的临时策略一次性清掉（页面关掉 / 超时 / 异常三条路都走这） */
        private fun cleanupTemps() {
            val left = synchronized(tempIds) { tempIds.toList() }
            if (left.isEmpty()) return
            tempIds.clear()
            comboExecutor.execute {
                comboSlot.release()
                for (id in left) StrategyComboClient.dropTemp(id)
            }
            GLog.i("$LOG 收尾清掉 ${left.size} 条临时策略")
        }

        /**
         * 没取到的成员排到队尾，再给一次机会。
         *
         * 之前没有重试：一个成员超时，那一轮就带着残缺并集写回，界面停在一百多只
         * （真机 212 / 100 多来回跳）。重试一次能把「宿主刚好在刷新」这种偶发挡掉。
         *
         * @return true = 已重排（调用方继续）；false = 重试次数用完或时间不够（调用方标 degraded）
         */
        private fun requeue(memberStId: String, memberName: String, why: String): Boolean {
            if (retried.size >= targets.size * StrategyMergeTargets.RETRY_ROUND) return false
            if (!retried.add(memberStId)) return false
            if (System.currentTimeMillis() + StrategyMergeTargets.MEMBER_TIMEOUT_MS > deadline) {
                GLog.i("$LOG 成员 $memberName（$memberStId）$why，但剩余时间不够重试了")
                return false
            }
            queue.addLast(memberStId to memberName)
            GLog.i("$LOG 成员 $memberName（$memberStId）$why，排到队尾重试")
            return true
        }

        /**
         * 主线程轮询：列表签名一变就当这一成员到手。
         *
         * 单成员最多等 [StrategyMergeTargets.MEMBER_TIMEOUT_MS]（实测换列表 1~3 秒），
         * 到点跳过并标 degraded；总时长受 [StrategyMergeTargets.TOTAL_TIMEOUT_MS] 约束。
         */
        private fun poll(
            memberStId: String,
            memberName: String,
            base: String,
            baseGen: Int,
            startedAt: Long,
        ) {
            if (cancelled) {
                GLog.w(
                    "$LOG 成员 $memberName（$memberStId）的轮询发现本轮已取消，停在这里" +
                        "（已取 ${uniq.size} 只，等 ${System.currentTimeMillis() - startedAt}ms）",
                )
                return
            }
            if (!isAlive()) {
                abort("页面已销毁")
                return
            }
            val now = System.currentTimeMillis()
            val waited = now - startedAt
            // 口径成员可能一只都没有（盘中某些档位确实空，量比这类字段更是常态）。
            // 空列表不改变签名 → 只能干等满 MEMBER_TIMEOUT_MS；12 个组合就是 36 秒白等，
            // 还会把总预算吃光。给个更短的确认窗口，到点仍为空就按「0 只」放过（不标 degraded：空是真实结果）。
            if (waited >= COMBO_EMPTY_CONFIRM_MS && tempIds.contains(memberStId) && seedList().isNullOrEmpty()) {
                GLog.i("$LOG 口径成员 $memberName（$memberStId）列表为空，按 0 只放过（等 ${waited}ms）")
                fetchNext()
                return
            }
            // 宿主的 autoRefresh 也按 m_stg.id 取数：它一刷新就把我们写的成员 id 改回本页那条，
            // 之后到手的列表是「本页策略」的，不是这个成员的。以前无条件 absorb，等于把本页
            // 数据当成员数据并进并集 —— 这是「一时 212 只、一时 100 多只」的另一半原因。
            // 判据：id 被改回就重写 id 重发命令，这一轮绝不吃数据。
            val curId = (StrategyMergeTargets.field(stg, StrategyMergeTargets.STG_FIELD_ID) as? String)?.trim()
            if (curId != null && curId != memberStId) {
                // 重发要节流：宿主可能每 120ms 就把它改回来一次，跟着重发会把请求打成风暴
                if (now - lastResendAt >= ID_RESEND_MIN_INTERVAL_MS) {
                    lastResendAt = now
                    GLog.i("$LOG 成员 $memberName（$memberStId）的 id 被宿主改成 $curId，重发一次取数")
                    StrategyMergeTargets.write(stg, StrategyMergeTargets.STG_FIELD_ID, memberStId)
                    dispatchCommand()
                }
                if (waited >= StrategyMergeTargets.MEMBER_TIMEOUT_MS || now > deadline) {
                    GLog.i(
                        "$LOG 成员 $memberName（$memberStId）等 ${waited}ms 一直被宿主抢 id" +
                            "（累计 ${uniq.size} 只）",
                    )
                    if (!retryOrGiveUp(memberStId, memberName, "总被抢 id")) degraded = true
                    fetchNext()
                    return
                }
                post { poll(memberStId, memberName, base, baseGen, startedAt) }
                return
            }
            // 到达判据：优先认「宿主自己塞进来的那一帧」（代次推进）；
            // 只有没在顶并集时才退回比签名 —— 顶住时 `data` 被我们改过，签名不可信。
            val genMoved = payloadGen() != baseGen
            val arrived = genMoved || (!holdingUnion() && signature() != base)
            if (arrived) {
                val list = if (genMoved) payloadSnapshot() else seedList()
                val added = absorb(list)
                absorbPrices(StrategyMergeTargets.mapField(adapter, StrategyMergeTargets.ADAPTER_FIELD_PRICE_MAP))
                absorbTags()
                addStat(list?.size ?: 0)
                GLog.i(
                    "$LOG 成员 $memberName（$memberStId）到位：${list?.size ?: 0} 只（新增 $added），" +
                        "累计 ${uniq.size} 只，用时 ${waited}ms",
                )
                // 没有上一轮并集可顶（本轮是冷启动第一跑）时，至少让界面**只增不减**：
                // 宿主每换一个成员就把列表缩回那一格，用户看到的就是 12 → 73 → 12 → … 来回抽。
                showGrowingUnion()
                fetchNext()
                return
            }
            if (waited >= StrategyMergeTargets.MEMBER_TIMEOUT_MS || now > deadline) {
                GLog.i(
                    "$LOG 成员 $memberName（$memberStId）等 ${waited}ms 列表没换" +
                        "（累计 ${uniq.size} 只）",
                )
                if (!retryOrGiveUp(memberStId, memberName, "列表没换")) degraded = true
                fetchNext()
                return
            }
            post { poll(memberStId, memberName, base, baseGen, startedAt) }
        }

        /**
         * 冷启动第一跑：把「已经取到的并集」写回界面，列表只长不缩。
         *
         * 有上一轮缓存时这条路不走（[holdUnionWhileRunning] 会整份顶住，一步到位）；
         * 没缓存时每换一个成员宿主的列表都会缩回那一格，来回抽比慢更难看。
         */
        private fun showGrowingUnion() {
            if (holdingUnion() || uniq.isEmpty()) return
            val copy = ArrayList<Any>(uniq.size)
            copy.addAll(uniq.values)
            if (!StrategyMergeTargets.write(adapter, StrategyMergeTargets.ADAPTER_FIELD_DATA, copy)) return
            StrategyMergeTargets.notifyAdapter(adapter)
            writeMergedResNum(act, state, copy.size)
        }

        /** 本轮是否把上一轮的并集顶在屏幕上（有缓存才会顶，顶住时 `data` 不算宿主响应） */
        private fun holdingUnion(): Boolean =
            state.mergedStrong?.isNotEmpty() == true

        /**
         * 列表签名：模型列表尺寸 / 适配器列表尺寸 / 首尾两行的 `codeAndMarket`。
         *
         * 只比尺寸不够 —— 两个成员策略可能刚好一样多只、首行还相同；带上尾行能把这种情况分开。
         * 尺寸用 -1 表示「读不到」，避免「0 只」与「字段没读到」混为一谈。
         */
        private fun signature(): String {
            val a = StrategyMergeTargets.listField(model, StrategyMergeTargets.MODEL_FIELD_STK_LIST)
            val b = StrategyMergeTargets.listField(adapter, StrategyMergeTargets.ADAPTER_FIELD_DATA)
            val head = StrategyMergeTargets.camOf(a?.firstOrNull() ?: b?.firstOrNull())
            val tail = StrategyMergeTargets.camOf(a?.lastOrNull() ?: b?.lastOrNull())
            return "${a?.size ?: -1}/${b?.size ?: -1}/$head/$tail"
        }

        /**
         * 把这次响应里的涨跌家数累加进来。
         *
         * 判据只有一条上界：`涨 + 跌` 不可能超过这份列表的只数。宿主换 id 后如果只换了列表、
         * 没换统计字段，读到的就是上一份数据（甚至是上一轮我们自己写回去的聚合值）的家数，
         * 上界一卡就挡住；真机实测过没有这道闸时每进一次详情页涨家数就 +134。
         */
        /**
         * 取数窗口里把聚合家数写回宿主自己那两个字段（`m_stg` 与 `model` 各一份）。
         *
         * 宿主每换一个成员都会按这两个字段重画头部；不写回去，屏幕上就是「这一格的家数」，
         * 真机实测 7/2 → 1/0 来回跳，用户以为聚合算错了。写之前记下数字，[addStat] 认出来跳过。
         */
        fun holdHeaderCounts() {
            val cachedUp = state.upSum
            val cachedDown = state.downSum
            // 有上一轮聚合值就顶上一轮的（一步到位）；冷启动第一跑没有，就用本轮已经累到的
            val up = if (cachedUp > 0 || cachedDown > 0) cachedUp else upSum
            val down = if (cachedUp > 0 || cachedDown > 0) cachedDown else downSum
            if (up <= 0 && down <= 0) return
            wroteUp = up
            wroteDown = down
            runCatching {
                StrategyMergeTargets.write(model, StrategyMergeTargets.MODEL_FIELD_UP_NUM, up)
                StrategyMergeTargets.write(model, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM, down)
                StrategyMergeTargets.write(stg, StrategyMergeTargets.MODEL_FIELD_UP_NUM, up)
                StrategyMergeTargets.write(stg, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM, down)
            }.onFailure { GLog.w("$LOG 回写聚合家数失败", it) }
        }

        /**
         * 宿主这一帧自己的涨跌家数，在 [holdHeaderCounts] 覆盖 model 之前抄下来。
         *
         * 为什么不能现读 model：取数窗口里每一帧我们都把聚合值写回那两个字段，
         * 现读读到的就是自己写的数，再累加一次既会污染、又会被 wroteUp 那道闸整段跳过
         * （真机 24 成员组收尾时「已渲染 636 只（涨 0 / 跌 0）」就是这么来的）。
         */
        private var hostUp = -1
        private var hostDown = -1

        fun captureHostStats() {
            if (!synchronized(state) { state.running }) return
            hostUp = StrategyMergeTargets.intField(model, StrategyMergeTargets.MODEL_FIELD_UP_NUM)
            hostDown = StrategyMergeTargets.intField(model, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM)
        }

        /**
         * 诊断：把并集第一行的类名与全部字段打一次日志。
         *
         * 只在「读不到涨跌幅字段」时触发一次 —— 宿主换了 `StockInfo` 的字段名，
         * 日志里的那份清单就是新的候选表。
         */
        private var rowDumped = false

        private fun dumpRowFieldsOnce(list: List<*>?) {
            if (rowDumped) return
            val e = list?.firstOrNull { it != null } ?: return
            rowDumped = true
            val sb = StringBuilder()
            var c: Class<*>? = e.javaClass
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    runCatching { f.isAccessible = true }
                    val v = runCatching { f.get(e) }.getOrNull()
                    sb.append(f.name).append(':').append(f.type.simpleName).append('=')
                    sb.append(if (v is String) v.take(20) else v?.toString()?.take(20) ?: "null")
                    sb.append(" | ")
                }
                c = c.superclass
            }
            GLog.i("$LOG [row] ${e.javaClass.name} :: $sb")
        }

        private fun addStat(size: Int): Boolean {
            val u = hostUp
            val d = hostDown
            // 宿主这一帧没重写家数（抄到的还是我们上一帧写进去的聚合值）→ 不是真统计，别累加
            if (u == wroteUp && d == wroteDown) return false
            if (u < 0 || d < 0 || (size > 0 && u + d > size)) {
                GLog.w("$LOG 这份响应的统计 $u/$d 和列表 $size 只对不上，不计入家数")
                return false
            }
            upSum += u
            downSum += d
            return true
        }

        /** 把一份列表并进 [uniq]（按 `codeAndMarket` 保序去重，保留首次出现的实例），返回新增只数 */
        private fun absorb(list: List<*>?): Int {
            if (list.isNullOrEmpty()) return 0
            var added = 0
            for (e in list) {
                if (e == null) continue
                // 读不到 codeAndMarket 时按对象身份兜底：宁可重复一只，也不能丢票
                val key = StrategyMergeTargets.camOf(e) ?: ("#anon:" + System.identityHashCode(e))
                if (!uniq.containsKey(key)) {
                    uniq[key] = e
                    added++
                }
            }
            return added
        }

        /** 把一份 priceMap 并进 [prices]（同一个键保留先见到的，不覆盖） */
        private fun absorbPrices(map: Map<*, *>?) {
            if (map == null || map.isEmpty()) return
            for (e in map.entries) {
                val k = e.key ?: continue
                val v = e.value ?: continue
                if (!prices.containsKey(k)) prices[k] = v
            }
        }

        /**
         * 发宿主的取数命令：`EngineModel.setBizCommand("biz_type_for_cmd_biz_get_stg_data")`
         * 然后 `m_biz.getStg()`。
         *
         * **必须在主线程**：实测工作线程发这条命令只有 1 次请求、列表根本不换（探针 v51）。
         */
        private fun dispatchCommand(): Boolean {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                GLog.w("$LOG 不在主线程，取数命令不发（实测工作线程无效）")
                return false
            }
            return try {
                val biz = StrategyMergeTargets.field(model, StrategyMergeTargets.MODEL_FIELD_BIZ)
                if (biz == null) {
                    GLog.w("$LOG 取不到 m_biz，发不了取数命令")
                    return false
                }
                val engineCls = env.classLoader.loadClass(StrategyMergeTargets.CLS_ENGINE_MODEL)
                val setCmd = engineCls.getMethod(
                    StrategyMergeTargets.ENGINE_SET_BIZ_COMMAND,
                    String::class.java,
                )
                val cmd = StrategyMergeTargets.CMD_BIZ_GET_STG_DATA
                runCatching { setCmd.invoke(null, cmd) }.recoverCatching {
                    // 万一哪天从静态方法换成实例方法：照探针的写法把页面模型当接收者再试一次
                    setCmd.invoke(model, cmd)
                }.getOrThrow()
                val getStg = Reflect.findMethod(biz.javaClass, StrategyMergeTargets.BIZ_METHOD_GET_STG)
                if (getStg == null) {
                    GLog.w("$LOG m_biz 上没有 ${StrategyMergeTargets.BIZ_METHOD_GET_STG}()")
                    return false
                }
                getStg.invoke(biz)
                true
            } catch (t: Throwable) {
                GLog.w("$LOG 取数命令发送失败", t)
                false
            }
        }

        /**
         * 收尾：合并结果一次性写回宿主，然后发布进程级快照（供别的分片消费）。
         *
         * 写回顺序是「越靠近数据源越先写」：适配器 data → priceMap → notify → 模型列表 →
         * 头部（name / upNum / downNum）→ stId 还原 → `showStg()` → 计数文案。
         * 任何一步写不进去都回滚整轮（不留半聚合的界面）。
         */
        private fun finish() {
            if (cancelled) return
            cancelled = true
            MainThreadStallWatchdog.stop("轮次结束 stId=$pageStId")
            // 还没删掉的临时策略在这里兜底：下面每个 return 都是「这一轮结束」，
            // 漏掉就会占着宿主名额（上限 10 条），下一轮直接建不出来。
            cleanupTemps()
            try {
                if (uniq.isEmpty()) {
                    GLog.i("$LOG 一只都没取到，还原宿主（成员全失败）")
                    releaseRollback(act, state)
                    return
                }
                val merged = ArrayList<Any>(uniq.size)
                merged.addAll(uniq.values)
                val ids = uniq.keys.toList()
                val cached = StrategyMergeCache.get(cacheKey)

                // 完整性闸：本轮有成员没取到（degraded），内存里却还留着一份更完整的结果 ——
                // 继续显示上次那份，不让残缺并集盖上去。这是「一时 212 只、一时 100 多只」的止跳点。
                if (degraded && cached != null && cached.size > uniq.size &&
                    applySnapshot(act, state, model, stg, adapter, cached)
                ) {
                    GLog.i(
                        "$LOG 本轮残缺（${uniq.size} 只，有成员没取到），保留上次的 ${cached.size} 只不覆盖",
                    )
                    return
                }

                logWiring(act, "finish-before")
                // 写回前最后一道闸：适配器在聚合途中被宿主换掉（空适配器 / 别的列表）就整轮放弃
                if (!StrategyMergeTargets.isPageAdapter(adapter.javaClass.name)) {
                    GLog.w(
                        "$LOG 适配器已变成 ${adapter.javaClass.name}（不是 ${StrategyMergeTargets.CLS_PAGE_ADAPTER}），放弃写回",
                    )
                    releaseRollback(act, state)
                    return
                }
                // ① 原生适配器：**交一份副本**出去。
                // 宿主刷新时会就地 clear + addAll 我们交给它的那个 List（真机 2026-11-11 21:17:40
                // 抓到 merged 从 64 被改成 47），所以自己必须留一份干净的 merged 给重套用。
                // 与上次结果完全一致时不重写：进页面秒套的就是这份内容，重写只会让列表闪一下。
                val sameAsCached = cached != null && !degraded && cached.sameAs(ids)
                state.mergedRef = WeakReference<Any>(merged)
                state.mergedStrong = merged
                state.mergedSize = merged.size
                if (sameAsCached) {
                    GLog.i("$LOG 本轮与上次结果一致（${merged.size} 只），列表不重写")
                } else if (!StrategyMergeTargets.write(
                        adapter,
                        StrategyMergeTargets.ADAPTER_FIELD_DATA,
                        ArrayList<Any>(merged),
                    )
                ) {
                    GLog.w("$LOG 写不进适配器 data，还原")
                    releaseRollback(act, state)
                    return
                }
                // 结果与上次完全一致 → 家数也沿用上次那份。
                // 本轮自己累加出来的家数可能少一份「本页策略」的统计：宿主换 id 取数的过程中会把
                // model 上的 upNum/downNum 清掉，absorbSeed 读到 0（真机：首轮 174/36，
                // 再进就变 134/29，缺的正是本页 47 只的 40/7）。并集一模一样，家数就不该变。
                if (sameAsCached) {
                    if (upSum != cached.up || downSum != cached.down) {
                        GLog.i("$LOG 本轮家数 ${upSum}/${downSum} 少了本页那份，沿上次的 ${cached.up}/${cached.down}")
                    }
                    upSum = cached.up
                    downSum = cached.down
                }
                // 口径成员的响应里 upNum/downNum 恒为 -1（宿主只给真实策略算家数），累加出来只能是
                // 0/0 —— 真机 24 组合组收尾时头部就是「涨 0 / 跌 0」。并集每一行都自带涨跌幅，
                // 直接数一遍：至少和屏幕上这 N 只对得上，比 0/0 强。
                if (upSum + downSum < merged.size) {
                    val counted = StrategyMergeTargets.countUpDown(merged)
                    if (counted != null && counted.first + counted.second >= merged.size * 0.8) {
                        GLog.i("$LOG 家数按并集重数：${counted.first}/${counted.second}（累加只拿到 $upSum/$downSum）")
                        upSum = counted.first
                        downSum = counted.second
                    } else {
                        dumpRowFieldsOnce(merged)
                        GLog.w("$LOG 并集里读不到涨跌幅字段，家数仍是 $upSum/$downSum")
                    }
                }
                state.applied = true
                state.upSum = upSum
                state.downSum = downSum
                if (prices.isNotEmpty()) {
                    StrategyMergeTargets.write(
                        adapter,
                        StrategyMergeTargets.ADAPTER_FIELD_PRICE_MAP,
                        LinkedHashMap<Any, Any>(prices),
                    )
                }
                // ② **不写 model.m_stkList**（曾经写过，会把宿主搞崩）。
                // 宿主在「数据还没回来 / 刷新中」时会挂 AIStkEmptyAdapter，它把 m_stkList 的每一项
                // 当 String 拼占位文案（AIStkEmptyAdapter.convert 第一行强转）。m_stkList 平时是空的，
                // 我们塞进 64 个 StockInfo 后，它下一次刷新就 ClassCastException 崩宿主
                // （真机 2026-11-11 21:06 / 21:11 两次）。列表展示只靠适配器 data，够用。
                if (!StrategyMergeTargets.notifyAdapter(adapter)) {
                    GLog.w("$LOG 适配器 notifyDataSetChanged 调不到，列表可能不刷新")
                }
                logWiring(act, "finish-after")
                mainHandler.postDelayed({ if (!act.isFinishing) logWiring(act, "finish+600") }, 600L)
                // ③ 头部：组名 + 涨跌合计，再让宿主自己按 m_stg 重画一遍
                StrategyMergeTargets.write(stg, StrategyMergeTargets.STG_FIELD_NAME, label)
                state.label = label
                // 家数两边都写：头部到底读 model 还是 m_stg 上的同名字段没有定论，
                // 真机实测只写 model 会被宿主用「最后一次取数那条策略」的值盖回去（40/7）。
                StrategyMergeTargets.write(model, StrategyMergeTargets.MODEL_FIELD_UP_NUM, upSum)
                StrategyMergeTargets.write(model, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM, downSum)
                StrategyMergeTargets.write(stg, StrategyMergeTargets.MODEL_FIELD_UP_NUM, upSum)
                StrategyMergeTargets.write(stg, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM, downSum)
                // ④ stId 还回本页原来那条：宿主之后按它刷新时回到本策略，而不是停在最后一个成员
                StrategyMergeTargets.write(stg, StrategyMergeTargets.STG_FIELD_ID, pageStId)
                state.touchedId = false
                StrategyMergeTargets.showStg(act)
                if (!writeHeaderCounts(act, upSum, downSum, state)) {
                    GLog.i("$LOG 头部涨跌家数没找到可写的节点（文案可能改了）")
                }
                // 宿主 showStg 之后若把列表换成了空占位适配器（它那条策略当前没股票），
                // 并集就永远显示不出来 —— 当场把并集适配器装回列表（真机 13:12 / 13:16 的「0 只」）。
                reattachIfSwapped(act)
                // 宿主在这之后再刷一轮自己的响应，会把涨跌家数顶回单策略的值（真机实测 16/1），
                // 所以补两次延迟写回：让它自己的刷新先落地，我们最后覆盖。
                for (delayMs in HEADER_RETRY_DELAYS) {
                    mainHandler.postDelayed({
                        if (act.isFinishing) return@postDelayed
                        StrategyMergeTargets.write(model, StrategyMergeTargets.MODEL_FIELD_UP_NUM, upSum)
                        StrategyMergeTargets.write(model, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM, downSum)
                        StrategyMergeTargets.write(stg, StrategyMergeTargets.MODEL_FIELD_UP_NUM, upSum)
                        StrategyMergeTargets.write(stg, StrategyMergeTargets.MODEL_FIELD_DOWN_NUM, downSum)
                        // 别调宿主 showStg：它会把列表按宿主自己的 m_stkList 重画一遍（回落来源），
                        // 头部这两行文本自己写就够了。
                        writeHeaderCounts(act, upSum, downSum, state)
                    }, delayMs)
                }
                // ⑤ 「策略个股 N 只」宿主不会按 data 重算，自己写成交互只数
                writeMergedResNum(act, state, merged.size)
                // ⑥ 标签条：各成员的标签合并成一组（区间型取 min-max），宿主刷新后看门狗再补
                // 成员标签以「我的策略」列表页采到的 Strategy.tags 为准：详情页 m_tagList 只有当前页那条，
                // 换 stId 取数时宿主不会跟着换（真机实测标签停在 s1）。
                for (sid in memberIds) {
                    // 口径成员的队列 id 是占位 key（combo:...），索引里查不到标签，按档位反推
                    if (sid.startsWith(StrategyComboCatalog.COMBO_PREFIX)) {
                        tagNames.addAll(StrategyComboCatalog.tagNamesOfKey(sid))
                    } else {
                        tagNames.addAll(StrategyStgIndex.tagsOf(sid))
                    }
                }
                GLog.i("$LOG 汇总标签 ${tagNames.size} 条：${tagNames.joinToString(" | ")}")
                val mergedTags = mergeTagNames(tagNames.toList())
                if (mergedTags.isNotEmpty()) {
                    state.tagTexts = mergedTags
                    state.tagSample = tagSample
                    if (!applyTagTexts(act, state)) {
                        GLog.i("$LOG 标签条没换成聚合标签（保持宿主原样）")
                    }
                }

                state.mergedRef = WeakReference<Any>(merged)
                state.mergedStrong = merged
                state.mergedSize = merged.size
                state.finishedAt = System.currentTimeMillis()
                StrategyMergeBridge.publish(
                    merged = merged,
                    source = merged,
                    owner = act,
                    pageStId = pageStId,
                    memberStIds = memberIds,
                    degraded = degraded,
                )
                GLog.i(
                    "$LOG 已渲染 ${merged.size} 只（成员 ${memberIds.size} 个，涨 $upSum / 跌 $downSum" +
                        (if (degraded) "，degraded：有成员没取到" else "") + "）",
                )
                // 只缓存**完整**的一轮：残缺并集进了缓存，下次进页面会先把它套上去，越套越小。
                if (!degraded) {
                    StrategyMergeCache.put(
                        key = cacheKey,
                        merged = merged,
                        ids = ids,
                        up = upSum,
                        down = downSum,
                        label = label,
                        tags = state.tagTexts.orEmpty(),
                    )
                }
                logListAdapters(act, "finish")
                startWatch(act, state)
            } catch (t: Throwable) {
                GLog.w("$LOG 渲染异常，还原宿主", t)
                releaseRollback(act, state)
            } finally {
                state.run = null
                synchronized(state) { state.running = false }
            }
        }

        /**
         * 中止：页面还在就还原（不留半聚合界面），页面已经销毁就只清记账。
         *
         * 所有反射写入都在这条路径上被 `runCatching` 兜住 —— 异常抛到主线程 Looper 会崩宿主。
         */
        private fun abort(why: String, tr: Throwable? = null) {
            if (cancelled) return
            cancelled = true
            MainThreadStallWatchdog.stop("中止：$why")
            cleanupTemps()
            if (!isAlive()) {
                GLog.i("$LOG 中止（$why）：页面已销毁，只清记账")
                clearState(act, state)
                return
            }
            val msg = "$LOG 中止（$why），还原宿主（已拿 ${uniq.size} 只）"
            if (tr != null) GLog.w(msg, tr) else GLog.w(msg)
            releaseRollback(act, state)
        }

        /** 所有延迟动作都走这里：主线程 + 取消检查 + 异常兜底 */
        private fun post(tick: () -> Unit) {
            mainHandler.postDelayed({
                if (cancelled) {
                    GLog.w("$LOG 延迟任务本轮已取消，跳过")
                    return@postDelayed
                }
                try {
                    tick()
                } catch (t: Throwable) {
                    abort("轮询异常", t)
                }
            }, StrategyMergeTargets.POLL_INTERVAL_MS)
        }

        private fun isAlive(): Boolean = !act.isFinishing && !act.isDestroyed
    }
}
