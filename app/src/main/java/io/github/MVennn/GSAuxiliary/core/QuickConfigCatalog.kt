package io.github.MVennn.GSAuxiliary.core

/**
 * 「一键配置」的取值项目录 —— 方案文档 §4.2 那张表的代码落点。
 *
 * `source` 的取值、含义、需要什么参数，**这张表就是设置页里「添加取值项」的可选清单**，
 * 也是解析时判断「认不认识这个取值项」的唯一权威（不认识的项解析时直接丢弃）。
 *
 * 展示名称不在这里（要本地化，在 `strings.xml` 的 `quick_term_*` 数组里，按
 * [TermSpec.id] 在 `QuickConfigCatalog.TERM_NAME_KEYS` 里的顺序一一对应）；
 * 参数的取值约束（周期可选值、百分比范围）也在这里，设置页的参数控件照它渲染。
 */
object QuickConfigCatalog {

    /** 参数种类 */
    const val PARAM_PERIOD = "period"
    const val PARAM_PCT = "pct"
    const val PARAM_VALUE = "value"

    /** 分类（设置页「添加取值项」弹层的分组顺序按这里） */
    const val GROUP_REALTIME = "realtime"   // 实时
    const val GROUP_POSITION = "position"   // 持仓
    const val GROUP_RANGE = "range"         // 区间
    const val GROUP_MA = "ma"               // 均线
    const val GROUP_PCT = "pct"             // 幅度
    const val GROUP_FIXED = "fixed"         // 固定

    /** 一个参数的说明 */
    data class ParamSpec(
        val key: String,
        /** 是否必需（缺失时该项解析失败被丢弃） */
        val required: Boolean,
        /** 周期类参数的可选值（交易日）；空 = 不是周期参数 */
        val periodChoices: List<String> = emptyList(),
        /** 百分比范围（含端点） */
        val pctMin: Double = 0.0,
        val pctMax: Double = 0.0,
        val pctStep: Double = 0.0,
    )

    /** 一个取值项的定义 */
    data class TermSpec(
        val id: String,
        /** 分类常量（[GROUP_REALTIME] …），设置页按它分组 */
        val group: String,
        /** 该项需要的参数；无参数项（如「最新价」）为空表 */
        val params: List<ParamSpec>,
    )

    /** `period` 的可选值（交易日），多个周期类取值项共用 */
    private val PERIOD_CHOICES = listOf("5", "10", "20", "30", "60", "120", "250")

    private fun period(required: Boolean) = ParamSpec(
        key = PARAM_PERIOD, required = required, periodChoices = PERIOD_CHOICES,
    )

    private fun pct() = ParamSpec(
        key = PARAM_PCT, required = true, pctMin = 0.1, pctMax = 30.0, pctStep = 0.1,
    )

    private fun value() = ParamSpec(key = PARAM_VALUE, required = true)

    /** 目录（顺序 = 设置页「添加取值项」弹层的组内顺序） */
    val ALL: List<TermSpec> = listOf(
        TermSpec("latest", GROUP_REALTIME, emptyList()),
        TermSpec("prevClose", GROUP_REALTIME, emptyList()),
        TermSpec("open", GROUP_REALTIME, emptyList()),
        TermSpec("dayHigh", GROUP_REALTIME, emptyList()),
        TermSpec("dayLow", GROUP_REALTIME, emptyList()),
        TermSpec("limitUp", GROUP_REALTIME, emptyList()),
        TermSpec("limitDown", GROUP_REALTIME, emptyList()),
        TermSpec("cost", GROUP_POSITION, emptyList()),
        TermSpec("highN", GROUP_RANGE, listOf(period(true))),
        TermSpec("lowN", GROUP_RANGE, listOf(period(true))),
        TermSpec("highCloseN", GROUP_RANGE, listOf(period(true))),
        TermSpec("lowCloseN", GROUP_RANGE, listOf(period(true))),
        // 历史前高 / 前低：全部可取历史（不含今日）里的极值，无参数
        TermSpec("historyHigh", GROUP_RANGE, emptyList()),
        TermSpec("historyLow", GROUP_RANGE, emptyList()),
        TermSpec("maN", GROUP_MA, listOf(period(true))),
        TermSpec("upPct", GROUP_PCT, listOf(pct())),
        TermSpec("downPct", GROUP_PCT, listOf(pct())),
        TermSpec("prevUpPct", GROUP_PCT, listOf(pct())),
        TermSpec("prevDownPct", GROUP_PCT, listOf(pct())),
        TermSpec("fixed", GROUP_FIXED, listOf(value())),
    )

    /** 分类在弹层里的出现顺序 */
    val GROUP_ORDER = listOf(
        GROUP_REALTIME, GROUP_POSITION, GROUP_RANGE, GROUP_MA, GROUP_PCT, GROUP_FIXED,
    )

    private val byIdMap: Map<String, TermSpec> = ALL.associateBy { it.id }

    /** 按 id 查定义；不认识（App 升级加了新项而配置来自旧版本）返回 null */
    fun byId(id: String): TermSpec? = byIdMap[id]

    /**
     * id → 展示名称的资源 key 顺序表：`strings.xml` 的 `quick_term_names`
     * （string-array）与 [ALL] 的顺序一一对应，设置页按下标取名字。
     * 放一张顺序表在这里是为了让「目录顺序」只有一份权威。
     */
    fun index(id: String): Int = ALL.indexOfFirst { it.id == id }
}
