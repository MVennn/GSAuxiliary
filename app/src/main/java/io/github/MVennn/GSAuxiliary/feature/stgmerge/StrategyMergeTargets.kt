package io.github.MVennn.GSAuxiliary.feature.stgmerge

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import io.github.MVennn.GSAuxiliary.core.TargetApp
import io.github.MVennn.GSAuxiliary.util.Reflect

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
        return runCatching {
            val arr = org.json.JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val nm = arr.optJSONObject(i)?.optString("labelName").orEmpty().trim()
                if (nm.isNotEmpty()) out.add(nm)
            }
            out
        }.getOrDefault(emptyList())
    }

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

    /** 「策略个股 N 只」的写法（与宿主原生文案同格式，N 换成聚合只数） */
    const val RES_NUM_FORMAT = "策略个股%d只"

    /** 宿主原样是「策略个股47只(11-11 19:40:22)」：换数字时把括号里的时间戳原样留下 */
    val RES_NUM_REGEX = Regex("策略个股\\d+只(.*)")

    /**
     * 筛选生效时的计数写法：**保留数/总数**。
     *
     * 与 stgfilter 的 `OUR_RES_NUM`（`^策略个股 \d+/\d+$`）**逐字同格式**：两个功能都会写
     * 同一个 `#tv_stg_res_num`，写成两种样子就会来回覆盖（屏幕上数字闪）。
     * 这里刻意**不带尾部时间戳**：stgfilter 的正则是严格全匹配，带尾巴它就认不出是自己写的了。
     */
    const val RES_NUM_FILTERED_FORMAT = "策略个股 %d/%d"

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

    /** 读一只股票的涨跌幅（百分数，如 `10.05`）；读不到返回 null */
    fun pctOf(item: Any?): Double? {
        if (item == null) return null
        return when (val v = Reflect.readField(item, *STOCK_PCT_FIELDS)) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }
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
