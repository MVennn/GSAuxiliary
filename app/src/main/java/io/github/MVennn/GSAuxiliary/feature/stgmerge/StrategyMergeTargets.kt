package io.github.MVennn.GSAuxiliary.feature.stgmerge

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import io.github.MVennn.GSAuxiliary.core.TargetApp
import io.github.MVennn.GSAuxiliary.feature.stgrow.StgCountBar
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.reflect.Field
import java.util.WeakHashMap

/**
 * 「策略详情页聚合多组策略」的定位常量与反射工具。
 *
 * ## 为什么全收在这一个文件
 *
 * 宿主的类名 / 字段名 / 方法名一律是**字符串反射**（见 [Reflect]），本模块对宿主零编译期依赖。
 * 按仓内约定（照 `feature/stgfilter/StgStockFilterTargets.kt`），这些名字只能出现在这里：
 * 宿主升级改名时，改动面就是这一个文件，Feature 里一行都不用动。
 *
 * ## 全部来自真机实测（金太阳 8.6.0 / vc271；探针 v49→v54）
 *
 * 详见 `docs/FEATURE-STRATEGY-MERGE.md` §2.5 与 §10.5，日志原文在探针
 * `tools/probe/src/com/wb/probe/ProbeModule.java` 的 v44~v54 段。
 *
 * ```
 * AIStkStgDetailAct
 *   ├─ m_stkModel   AIStkStgDetailModel
 *   │    ├─ m_stg      Strategy            { String id, String name }
 *   │    ├─ m_stkList  ArrayList<StockInfo>
 *   │    ├─ upNum / downNum  int
 *   │    └─ m_biz      AIStkStgDetailBiz   { getStg(), autoRefresh() }
 *   └─ m_stkAdapter AIStkAdapter            { List data, Map priceMap }
 * ```
 *
 * ## 换策略取数走宿主的「命令驱动」模型
 *
 * 1. 先把 `m_stkModel.m_stg.id` 改成目标 stId；
 * 2. **在主线程**发命令：
 *    `EngineModel.setBizCommand("biz_type_for_cmd_biz_get_stg_data")` → `m_biz.getStg()`；
 * 3. 1~3 秒后 `m_stkList` 换成新策略的列表（命令必须在主线程发，工作线程实测只有
 *    1 次请求、列表不换）。
 */
object StrategyMergeTargets {

    const val TARGET_PKG = TargetApp.PACKAGE

    /** 只对这一个页面做聚合（Activity 类名，实测） */
    const val DETAIL_ACTIVITY = "com.guosen.quotation.ui.aistk.AIStkStgDetailAct"

    /**
     * 「我的策略」列表页。
     *
     * 这一页只做两件事：把组内第一条成员那张卡片改名成「聚合 · 组名」当入口用，
     * 顺手把整页策略对象里的「名称 → stId」灌进 StrategyStgIndex（成员只填名称时的数据源）。
     */
    const val LIST_ACTIVITY = "com.guosen.quotation.ui.aistk.AIStkStgListAct"

    /**
     * `notifyDataSetChanged` 的定义处：宿主的 `BaseQuickAdapter` 没重写它（钩子挂 0 个），
     * 真正实现在 `android.widget.BaseAdapter` —— 刷新前置替换数据要钩这一层。
     */
    const val CLS_ADAPTER_NOTIFY = "android.widget.BaseAdapter"

    /** 列表页承载策略卡片的容器 id 候选；取不到时退化成「遍历视图树找 ListView」 */
    val LIST_PAGE_ID_CANDIDATES = arrayOf("lvStgs", "lv_stg_list", "rvStgList", "rv_stg_list")

    /**
     * 列表页适配器上装数据的字段名候选。
     *
     * 详情页是 data（实测），列表页不保证同名 —— 按候选读，谁在就用谁。
     */
    val LIST_DATA_FIELDS = arrayOf("data", "mData", "mList", "list", "mStgList", "mDataList")

    // ------------------------------------------------------------------ 页面

    /**
     * 页面上的**页面模型**字段。
     *
     * 探针里两种读法都跑通过：`m_stkModel` 是这一页自己的字段名（文档 §2.5 口径），
     * `m_model` 是基类上的通用字段（探针 v41 用的就是它）。按候选数组读，谁在就用谁。
     */
    val ACT_FIELD_MODEL = arrayOf("m_stkModel", "m_model")

    /** 页面上的**渲染适配器**字段（与 ListView.getAdapter().getWrappedAdapter() 是同一对象） */
    val ACT_FIELD_ADAPTER = arrayOf("m_stkAdapter")

    /** 页面自己的「按 m_stg 重画头部」方法（改完 name / upNum / downNum 后必须调它） */
    const val ACT_METHOD_SHOW_STG = "showStg"

    /**
     * 头部「日涨跌幅」那颗数字。
     *
     * 宿主只会算**单条策略**的涨跌幅：换成本模块拼的并集之后它算不出来，头部就一直挂着 `--`
     * （真机 2026-11-12 18:59 探针：开机 `+9.78%`，聚合写回前后都是 `--`）。
     * 这条纯 UI 写入，不动宿主数据。
     */
    const val HEADER_ZDF_ID = "tv_stg_zdf"

    /**
     * 列表里的**列标题行**（名称 / 最新价 / 涨跌幅）。
     *
     * 实测它不是 adapter item，而是 ListView header 末尾的一个普通 View：宿主在换成空占位
     * 适配器（[CLS_EMPTY_ADAPTER]）时连它一起收起，聚合完把并集装回列表后它不会自己再出来 ——
     * 用户看到的就是「最后定格时表头消失」。id 实测与 stgfilter 那份一致（`ll_top`）。
     */
    const val COL_TITLE_ID = "ll_top"

    /** 页面模型类（只用于日志与兜底判据，不参与反射取字段） */
    const val CLS_PAGE_MODEL = "com.guosen.quotation.model.aistk.AIStkStgDetailModel"

    /** 列表适配器类；`ListView.setAdapter` 后置钩子靠它认「这一页的列表」 */
    const val CLS_PAGE_ADAPTER = "com.guosen.quotation.adapter.aistk.AIStkAdapter"

    /**
     * 空列表占位适配器。
     *
     * 宿主那条策略当前没有股票时会把 ListView 换成它；它的 data 是 String 提示语
     * （`AIStkEmptyAdapter.convert` 第一行就强转 String），**绝不能往里塞 StockInfo**，
     * 但我们可以把并集那个 `AIStkAdapter` 再装回列表（见 `reattachIfSwapped`）。
     */
    const val CLS_EMPTY_ADAPTER = "com.guosen.quotation.adapter.aistk.AIStkEmptyAdapter"

    // ------------------------------------------------------------------ 模型字段

    const val MODEL_FIELD_STG = "m_stg"
    const val MODEL_FIELD_STK_LIST = "m_stkList"
    const val MODEL_FIELD_BIZ = "m_biz"
    const val MODEL_FIELD_UP_NUM = "upNum"
    const val MODEL_FIELD_DOWN_NUM = "downNum"

    // ------------------------------------------------------------------ 策略字段

    const val STG_FIELD_ID = "id"
    const val STG_FIELD_NAME = "name"

    /** 列表页策略卡片上的标签：**JSON 字符串**，元素形如 `{"labelName":"量比1.5-2.0"}` */
    const val STG_FIELD_TAGS = "tags"

    /**
     * 从列表页的 `Strategy` 对象里取标签名。
     *
     * 详情页的 `m_tagList` 只反映**当前页那一条**策略 —— 换 stId 取数时宿主不会跟着换标签
     * （真机实测：成员 s2 的 47 只股票已取到，标签却还是 s1 的）。所以聚合的成员标签一律以
     * 这里为准：进过「我的策略」列表页，整页每条策略的标签就都进了 [StrategyStgIndex]。
     */
    fun strategyTagNames(item: Any?): List<String> {
        val raw = (field(item, STG_FIELD_TAGS) as? String)?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        // 列表页 800ms 一轮巡检，每轮对每张卡都把同一份 tags 原文重新 JSONArray 解析一遍。
        // 按**原文内容**缓存结果（键必须是字符串内容，不能用对象引用：宿主会原地改卡片）：
        // 内容没变就零解析，64 条封顶，最久没用的先走。
        synchronized(tagNameCache) {
            tagNameCache[raw]?.let { return it }
        }
        val parsed = runCatching {
            val arr = org.json.JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val nm = arr.optJSONObject(i)?.optString("labelName").orEmpty().trim()
                if (nm.isNotEmpty()) out.add(nm)
            }
            out as List<String>
        }.getOrDefault(emptyList())
        synchronized(tagNameCache) {
            if (tagNameCache.size >= TAG_NAME_CACHE_MAX) {
                val oldest = tagNameCache.keys.firstOrNull()
                if (oldest != null) tagNameCache.remove(oldest)
            }
            tagNameCache[raw] = parsed
        }
        return parsed
    }

    /** [strategyTagNames] 的内容缓存：上限 [TAG_NAME_CACHE_MAX] 条，插入顺序淘汰 */
    private val tagNameCache = LinkedHashMap<String, List<String>>(64)

    /** 策略列表页一共也就几十张卡，64 条足够覆盖一页再加几页 */
    private const val TAG_NAME_CACHE_MAX = 64

    // ------------------------------------------------------------------ 适配器字段

    const val ADAPTER_FIELD_DATA = "data"
    const val ADAPTER_FIELD_PRICE_MAP = "priceMap"
    const val ADAPTER_METHOD_NOTIFY = "notifyDataSetChanged"

    /** 股票数据项的去重键：`StockInfo.codeAndMarket`，形如 `688135.2` */
    val STOCK_CAM_FIELDS = arrayOf("codeAndMarket", "m_codeAndMarket")

    // ------------------------------------------------------------------ 视图 id

    /** 承载股票行的 ListView（ListView 外层是 HeaderViewListAdapter，**别去 setAdapter**） */
    val LIST_ID_CANDIDATES = arrayOf("lvStks")

    /** 「策略个股 N 只」那颗计数 —— 宿主只按自己的单策略写，聚合后要自己改 */
    val RES_NUM_ID_CANDIDATES = arrayOf("tv_stg_res_num")

    /** 「我的策略」列表页的滚动容器（实测是 RecyclerView，用类名认，不引 androidx 依赖） */
    const val CLS_RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"

    const val CLS_HEADER_ADAPTER = "android.widget.HeaderViewListAdapter"
    const val HEADER_ADAPTER_METHOD = "getWrappedAdapter"

    // ------------------------------------------------------------------ 取数命令

    /** 宿主命令驱动模型的基类：`setBizCommand(String)` 是它的公有方法 */
    const val CLS_ENGINE_MODEL = "com.guosen.engine.process.EngineModel"

    const val ENGINE_SET_BIZ_COMMAND = "setBizCommand"

    /** 「按当前 m_stg.id 取这一条策略的股票列表」的命令名（探针 v42 从 m_methodMap 打出来的原文） */
    const val CMD_BIZ_GET_STG_DATA = "biz_type_for_cmd_biz_get_stg_data"

    /** `m_biz` 上真正发请求的方法 */
    const val BIZ_METHOD_GET_STG = "getStg"

    // ------------------------------------------------------------------ 标签条

    /**
     * 头部标签条的模型字段：`List<com.guosen.android.aistock.data.Tag>`（真机 2026-11-11 21:50 实测）
     *
     * Tag 只有三个实例字段：`String tagId` / `String tagName` / `String isValid`，无参构造。
     * 页面上的 chip 由 `RecyclerView` + `AITagAd` 渲染，每项是一个 `AutoSizeCompatTextView`，
     * 文本就是 `tagName` 原文（形如「流通市值70-100亿」）。
     */
    const val MODEL_FIELD_TAG_LIST = "m_tagList"

    /** 标签条的适配器（用它换数据，比逐条改 TextView 能应付标签数量变化） */
    const val CLS_TAG_ADAPTER = "com.guosen.quotation.adapter.aistk.AITagAd"

    const val CLS_TAG = "com.guosen.android.aistock.data.Tag"

    /**
     * 「我的策略」列表里每张策略卡的 bean。
     *
     * 真机 dump 确认（2026-11-12）：`卡#0 类=com.guosen.android.aistock.data.Strategy 无参构造=true`，
     * 字段 id / name / tags / leadStkCode / leadStkName / leadStkPrice / leadStkZdf / zdf。
     * 没有可克隆的策略卡时靠它自建一张入口卡（见 StrategyMergeFeature.newEntryCard）。
     */
    const val CLS_STRATEGY = "com.guosen.android.aistock.data.Strategy"
    const val TAG_FIELD_NAME = "tagName"
    const val TAG_FIELD_ID = "tagId"
    const val TAG_FIELD_VALID = "isValid"

    /** Tag 的「有效」标记默认值（宿主字段名就是 FLAG_IS_VAILD，原样照抄） */
    const val TAG_VALID_DEFAULT = "1"

    /** BaseQuickAdapter 系装数据的入口方法候选（有就调，没有再退到写字段） */
    val TAG_ADAPTER_SET_METHODS = arrayOf("setNewData", "setList", "setData", "refreshData", "setNewInstance")

    /** 适配器数据字段候选（BaseQuickAdapter 是 mData） */
    val TAG_ADAPTER_DATA_FIELDS = arrayOf("mData", "data", "mItems", "mList")

    // ------------------------------------------------------------------ 计时

    /**
     * 单个成员单次等宿主的超时。
     *
     * 实测换列表 1~3 秒，原来取 2000ms（单次请求的下限口径）会把「刚好 2 秒多」的成员判成超时，
     * 那一轮就带着残缺并集写回 —— 真机看到的就是「策略个股」212 / 100 多来回跳。
     * 抬到 3000ms 并配上成员级重试（[RETRY_ROUND]），首屏由 [StrategyMergeCache] 秒套兜住，不会更慢。
     */
    const val MEMBER_TIMEOUT_MS = 3000L

    /**
     * 一轮聚合的总超时。
     *
     * 4 个成员 × 3 秒 = 12 秒，再留一次重试余量，所以给 16 秒。
     * 首屏不等人：进页面先套上次的完整结果，这一轮只是后台校验。
     */
    const val TOTAL_TIMEOUT_MS = 16_000L

    /** 成员没取到时最多重排几次（一次即够：重试还是失败就认残缺，交给完整性闸挡） */
    const val RETRY_ROUND = 1

    /** 聚合结果缓存的有效期：超过就重算（行情变了，旧并集只数已经不可信） */
    const val SNAPSHOT_TTL_MS = 10 * 60_000L

    /** 轮询间隔：够密能抓住 1~3 秒的换列表，又不至于把主线程喂满 */
    const val POLL_INTERVAL_MS = 120L

    /**
     * 同一页同一组在 [RUN_WINDOW_MS] 内最多完整聚合几次。
     *
     * 宿主自己会刷新详情页（`m_biz.autoRefresh()`）：它一刷新就把列表顶回单策略，
     * 我们判「已聚合」失效又要重跑一轮，没有上限就会变成「每 8 秒一批请求」的活锁。
     * 到上限只写日志停手，页面退回宿主原生的单策略视图（不算坏事）。
     */
    const val MAX_RUNS_PER_WINDOW = 4

    /** [MAX_RUNS_PER_WINDOW] 的统计窗口 */
    const val RUN_WINDOW_MS = 60_000L

    // ------------------------------------------------------------------ 文案

    /**
     * 「策略个股 N 只」的写法（与宿主原生文案同格式，N 换成聚合只数）。
     *
     * 文案口径的唯一正本是 [StgCountBar]（筛选侧也读同一份）：本文件只做转发，
     * 不再自己攥一份格式串 —— 两处各写一份靠注释维持一致，真机上已经因此对不上过。
     */
    val RES_NUM_FORMAT = StgCountBar.FORMAT_TOTAL

    /** 宿主原样是「策略个股47只(11-11 19:40:22)」：换数字时把括号里的时间戳原样留下 */
    val RES_NUM_REGEX = StgCountBar.HOST_TOTAL

    /**
     * 筛选生效时的计数写法：**保留数/总数**。
     *
     * 口径正本同样是 [StgCountBar]（筛选侧的 `isOurs` 与这里是同一个正则）：两个功能都会写
     * 同一个 `#tv_stg_res_num`，写成两种样子就会来回覆盖（屏幕上数字闪）。
     * 这里刻意**不带尾部时间戳**：`StgCountBar.OURS` 是严格全匹配，带尾巴就认不出是自己写的了。
     */
    val RES_NUM_FILTERED_FORMAT = StgCountBar.FORMAT_KEPT

    /** 聚合后写进头部策略名的前缀（后面接组内成员名） */
    const val MERGE_NAME_PREFIX = "聚合 · "

    /** 组名过长时截断的长度（头部一行放不下，宿主会自己省略但会挤掉涨跌数） */
    const val MERGE_NAME_MAX = 24

    /** 「我的策略」列表里那个聚合入口卡片的名字后缀（用户从列表一眼能认出来） */
    const val LIST_ENTRY_TAG = "（聚合）"

    /** 成员全都没名字时的兜底 */
    const val MERGE_NAME_FALLBACK = "聚合策略"

    // ------------------------------------------------------------------ 判据

    fun isDetailActivity(className: String): Boolean = className == DETAIL_ACTIVITY

    fun isListActivity(className: String): Boolean = className == LIST_ACTIVITY

    fun isPageAdapter(className: String): Boolean = className == CLS_PAGE_ADAPTER

    // ------------------------------------------------------------------ 反射工具

    /**
     * 读字段（按候选名依次试，第一个非 null 的胜出）。
     *
     * 宿主字段全是 private，[Reflect.findField] 已经 setAccessible，这里直接读。
     */
    fun field(target: Any?, vararg names: String): Any? {
        if (target == null || names.isEmpty()) return null
        return Reflect.readField(target, *names)
    }

    /**
     * 写字段，返回是否写成。
     *
     * 失败**不抛异常**：宿主改版后字段可能改名/改类型，调用方按「写不进就早退」处理，
     * 绝不能把异常抛到宿主的 onResume / 主线程 Handler 上。
     */
    fun write(target: Any?, name: String, value: Any?): Boolean {
        if (target == null) return false
        val f = Reflect.findField(target.javaClass, name) ?: return false
        return runCatching {
            f.set(target, value)
            true
        }.getOrDefault(false)
    }

    /**
     * 把 [value] 写进 [target] 的列表字段，**能原地就原地**（`clear + addAll`），实例保持不变。
     *
     * 为什么必须原地：宿主 `AbsListView` 在 `ACTION_UP` 分支里按「这一帧数据有没有换过」决定
     * 丢不丢这一下点击（`docs/FEATURE-STG-FILTER.md` 的真机教训：把 `data` 换成新对象之后
     * 「有些行点不动，越靠下越明显」）。原地改只换内容、不换实例，宿主持有的引用跟它自己的
     * 数据版本判断都不受影响。
     *
     * 字段现在不是可写的 List（空态 null、`Arrays.asList`、`Collections.unmodifiableList`）时，
     * 退回 [write] 换新对象 —— 退化路径和改造前行为一致，不会因为新逻辑反而写不进去。
     */
    fun writeListInPlace(target: Any?, name: String, value: List<Any?>): Boolean {
        val holder = target ?: return false
        val f = Reflect.findField(holder.javaClass, name)
        if (f != null) {
            val cur = runCatching { f.get(holder) }.getOrNull()
            if (cur is List<*> && cur !== value) {
                @Suppress("UNCHECKED_CAST")
                val m = cur as MutableList<Any?>
                // 先留一份原内容：clear 成功而 addAll 抛异常（不可变 List、元素类型被宿主校验挡住）
                // 的话，宿主那份列表已经被我们清空，不回滚就等于把这一页的股票全弄没了。
                val backup = ArrayList<Any?>(cur)
                if (runCatching { m.clear(); m.addAll(value) }.isSuccess) return true
                runCatching { m.clear(); m.addAll(backup) }
            }
        }
        return write(holder, name, ArrayList<Any?>(value))
    }

    /** 读 int 字段；读不到按 0 算（涨跌家数只是展示用，缺了不该挡整条链路） */
    fun intField(target: Any?, name: String): Int {
        val v = field(target, name) ?: return 0
        return (v as? Number)?.toInt() ?: 0
    }

    /** 读 List 字段（`m_stkList` / `data`）；不是 List 就当没读到 */
    fun listField(target: Any?, vararg names: String): List<*>? = field(target, *names) as? List<*>

    /** 读 Map 字段（`priceMap`）；不是 Map 就当没读到 */
    fun mapField(target: Any?, name: String): Map<*, *>? = field(target, name) as? Map<*, *>

    /** 股票数据项的去重键；读不到返回 null（调用方按对象身份兜底，别丢票） */
    fun camOf(item: Any?): String? {
        if (item == null) return null
        val v = Reflect.readField(item, *STOCK_CAM_FIELDS) as? String
        return if (v.isNullOrBlank()) null else v
    }

    /**
     * 股票数据项的涨跌幅字段候选。
     *
     * 宿主 `StockInfo` 上叫哪个名字没有定论（口径成员的响应里 `upNum`/`downNum` 恒为 -1，
     * 只能自己按行数），所以按候选名挨个试，读到第一个非空即可。
     */
    private val STOCK_PCT_FIELDS = arrayOf(
        "zdf", "m_zdf", "zf", "m_zf", "pct", "m_pct",
        "changePercent", "m_changePercent", "zdfRate", "m_zdfRate", "zfRate",
    )

    /**
     * 每个股票类**第一条读得出涨跌幅的字段**（WeakHashMap：类卸载后缓存自然消失）。
     *
     * 为什么要缓存：`countUpDown` 在收尾时对整份并集（几百上千只）主线程跑一遍，
     * 未命中一个候选名就要 new 一个 NoSuchFieldException；11 个候选 × 上千行 =
     * 上千次异常构造，正好卡在「最后一个成员取完 → 界面写回」这段，真机上就是 ANR。
     */
    private val pctFieldCache = WeakHashMap<Class<*>, Field>()

    /** 读一只股票的涨跌幅（百分数，如 `10.05`）；读不到返回 null */
    fun pctOf(item: Any?): Double? {
        if (item == null) return null
        val cls = item.javaClass
        val cached = synchronized(pctFieldCache) { pctFieldCache[cls] }
        if (cached != null) {
            val d = toPct(runCatching { cached.get(item) }.getOrNull())
            // 这一只在这个字段上恰好是 null 时，仍走一遍候选扫描（别的名字可能才有值）。
            // 命中率是常态，扫描只发生在个别行。
            if (d != null) return d
        }
        for (name in STOCK_PCT_FIELDS) {
            val f = Reflect.findField(cls, name) ?: continue
            val d = toPct(runCatching { f.get(item) }.getOrNull()) ?: continue
            synchronized(pctFieldCache) { pctFieldCache[cls] = f }
            return d
        }
        return null
    }

    private fun toPct(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    /**
     * 数一份列表里的涨 / 跌只数。
     *
     * 一条涨跌幅都读不到返回 null —— 调用方据此判断「字段名又变了」，别把 0/0 当真。
     */
    fun countUpDown(list: List<*>?): Pair<Int, Int>? {
        if (list.isNullOrEmpty()) return null
        var up = 0
        var down = 0
        var got = 0
        for (e in list) {
            val p = pctOf(e) ?: continue
            got++
            if (p > 0.0) up++ else if (p < 0.0) down++
        }
        return if (got == 0) null else up to down
    }

    /** 家数采用「按并集重数」 */
    const val TIER_COUNT = 0

    /** 家数采用「沿上次缓存」 */
    const val TIER_CACHE = 1

    /** 家数采用「成员累加」（兜底，重叠时会偏大） */
    const val TIER_SUM = 2

    /** 重数可信比例：并集里能读出涨跌幅的行占比到这条线才认重数 */
    private const val COUNT_TRUST_RATIO = 0.8

    /**
     * 家数该采用哪一档 —— **重数优先**（2026-11-13 真机核对后定）。
     *
     * 并集每一行都自带涨跌幅，重数天然与屏幕上这 N 只对得上；成员之间有重叠时
     * 累加值一定偏大（真机抓到并集 778 只、头部写「涨 581 / 跌 259」，就是 24 个成员
     * 各自服务端家数相加）。所以只在「一行涨跌幅都读不出」时才退回缓存与累加。
     */
    fun countTier(mergedSize: Int, counted: Pair<Int, Int>?, sameAsCached: Boolean): Int {
        if (counted != null && mergedSize > 0 &&
            counted.first + counted.second >= mergedSize * COUNT_TRUST_RATIO
        ) {
            return TIER_COUNT
        }
        return if (sameAsCached) TIER_CACHE else TIER_SUM
    }

    /**
     * 宿主 model 上的标签文本（`m_tagList` 里每个 Tag 的 tagName）。
     *
     * 读不到返回空列表 —— 标签只是展示，缺了不该挡聚合主链路。
     */
    fun tagNames(model: Any?): List<String> {
        val list = listField(model, MODEL_FIELD_TAG_LIST) ?: return emptyList()
        val out = ArrayList<String>(list.size)
        for (e in list) {
            val n = (field(e, TAG_FIELD_NAME) as? String)?.trim()
            if (!n.isNullOrEmpty()) out.add(n)
        }
        return out
    }

    /**
     * 造一个宿主 Tag 实例（无参构造 + 写 tagName），给标签条换数据用。
     *
     * `tagId` / `isValid` 照抄 [sample]：宿主点标签或删标签时可能按 tagId 认人，
     * 留空有风险；没有样本就写默认有效标记。
     */
    fun newTag(name: String, sample: Any? = null): Any? {
        // 优先用样本自己的类：模块的 ClassLoader 未必能 Class.forName 到宿主类（实测失败）
        val cls = sample?.javaClass ?: runCatching { Class.forName(CLS_TAG) }.getOrNull() ?: return null
        val tag = runCatching {
            cls.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
        }.getOrNull() ?: return null
        write(tag, TAG_FIELD_NAME, name)
        val id = (field(sample, TAG_FIELD_ID) as? String).orEmpty()
        write(tag, TAG_FIELD_ID, id)
        val valid = (field(sample, TAG_FIELD_VALID) as? String)?.takeIf { it.isNotEmpty() } ?: TAG_VALID_DEFAULT
        write(tag, TAG_FIELD_VALID, valid)
        return tag
    }

    /**
     * 剥掉 ListView 外面的 HeaderViewListAdapter，拿真正承载股票行的适配器。
     *
     * 宿主给列表加了两行 Header（策略名 + 涨跌），所以 `ListView.getAdapter()` 拿到的是
     * `android.widget.HeaderViewListAdapter`（framework 里的隐藏类，只能反射认）；
     * 它包着的那个才是 `AIStkAdapter`，也是页面字段 `m_stkAdapter` 指向的同一对象。
     */
    fun unwrapListAdapter(adapter: Any?): Any? {
        if (adapter == null) return null
        if (adapter.javaClass.name != CLS_HEADER_ADAPTER) return adapter
        val m = Reflect.findMethod(adapter.javaClass, HEADER_ADAPTER_METHOD) ?: return null
        return runCatching { m.invoke(adapter) }.getOrNull()
    }

    /** 调适配器自己的 `notifyDataSetChanged()`（主线程调用） */
    fun notifyAdapter(adapter: Any?): Boolean {
        if (adapter == null) return false
        val m = Reflect.findMethod(adapter.javaClass, ADAPTER_METHOD_NOTIFY) ?: return false
        return runCatching {
            m.invoke(adapter)
            true
        }.getOrDefault(false)
    }

    /** 调页面的 `showStg()` 重画头部（主线程调用） */
    fun showStg(act: Activity?): Boolean {
        if (act == null) return false
        val m = Reflect.findMethod(act.javaClass, ACT_METHOD_SHOW_STG) ?: return false
        return runCatching {
            m.invoke(act)
            true
        }.getOrDefault(false)
    }
}

// ---------------------------------------------------------------------- 视图工具

/**
 * 按资源名取 id（取不到返回 0）。
 *
 * 宿主是加固包，id 是运行期查的；候选数组的意义与其它 Targets 一致 —— 升级后改一个就够。
 */
@Suppress("DEPRECATION")
internal fun Resources.viewIdOrZero(vararg names: String): Int {
    for (n in names) {
        val id = runCatching { getIdentifier(n, "id", StrategyMergeTargets.TARGET_PKG) }.getOrDefault(0)
        if (id != 0) return id
    }
    return 0
}

/**
 * 从任意 Context 往上找承载它的 Activity。
 *
 * `ListView.setAdapter` 发生时 `onResume` 可能还没到，只能从列表自己的 Context 认页面；
 * 沿 `ContextWrapper.baseContext` 最多爬 10 层，防环形包装。
 */
internal fun findActivity(ctx: Context?): Activity? {
    var c: Context? = ctx
    var guard = 0
    while (c != null && guard++ < 10) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}
