package io.github.MVennn.GSAuxiliary.feature.filter

/**
 * 「筛选股票」的**共享模型** —— 策略详情页与行情页「沪深京A」筛选面板用的是同一套条件、同一份落盘。
 *
 * ## 两项维度、两套语义（都是宿主自己的规矩，不是我们发明的）
 *
 * | 维度 | 项 | 勾选的含义 | 全不勾 / 全勾 |
 * |---|---|---|---|
 * | **涨跌与自选**（策略侧） | [up] / [down] / [zx] | 「未勾选的类别不显示」——每一只股票归到**唯一**一个类别，类别没勾就看不见 | 三个都不勾 = 不做方向过滤 |
 * | **股票类型**（行情侧） | [cyb] / [kcb] / [bzA] / [wkb] / [st] | 「勾选您想看的股票类型」——每只股票可以同时属于多个类型（*ST卓然 = 科创板 + ST股），**只要有一个类型没勾就被排除** | 五个全勾（默认）= 不做类型过滤 |
 *
 * 方向的类别判定（自选优先、平盘不属于任何类别）见 [keepsDirection]；
 * 类型的判定（代码前缀 / 名称 / 行情数据字段）见 [StockTypes]。
 *
 * ## 默认值
 *
 * 方向的三个默认 **false**（= 不筛），类型的五个默认 **true**（= 全显示）——
 * 与宿主行情筛面板的出厂状态一致：用户第一次打开两个面板看到的都是「没在筛」。
 *
 * 两类条件之间取**交集**：既要满足方向，也要满足类型。
 */
data class StockFilters(
    val up: Boolean = false,
    val down: Boolean = false,
    val zx: Boolean = false,
    val cyb: Boolean = true,
    val kcb: Boolean = true,
    val bzA: Boolean = true,
    val wkb: Boolean = true,
    val st: Boolean = true,
) {

    /** 至少勾了一个方向条件（决定「未勾选的类别不显示」这条规则要不要生效） */
    val directionFiltering: Boolean get() = up || down || zx

    /** 有类型没勾（类型过滤生效） */
    val typeFiltering: Boolean get() = !(cyb && kcb && bzA && wkb && st)

    /** 有任何一条筛选生效 */
    val active: Boolean get() = directionFiltering || typeFiltering

    /** 取某一项的值（面板按 [FilterRow] 建行时用） */
    operator fun get(row: FilterRow): Boolean = when (row) {
        FilterRow.UP -> up
        FilterRow.DOWN -> down
        FilterRow.ZX -> zx
        FilterRow.CYB -> cyb
        FilterRow.KCB -> kcb
        FilterRow.BZA -> bzA
        FilterRow.WKB -> wkb
        FilterRow.ST -> st
    }

    /** 改某一项的值（面板收状态时用） */
    fun set(row: FilterRow, value: Boolean): StockFilters = when (row) {
        FilterRow.UP -> copy(up = value)
        FilterRow.DOWN -> copy(down = value)
        FilterRow.ZX -> copy(zx = value)
        FilterRow.CYB -> copy(cyb = value)
        FilterRow.KCB -> copy(kcb = value)
        FilterRow.BZA -> copy(bzA = value)
        FilterRow.WKB -> copy(wkb = value)
        FilterRow.ST -> copy(st = value)
    }

    /**
     * 方向维度：这一只股票该不该留下。
     *
     * 与需求（2.16.1 起实测）一致：**自选优先，平盘不属于任何类别** ——
     * 只勾「涨家股票」时，自选里正在涨的股票也不显示；只要开了方向筛选，
     * 平盘（涨幅读不到 / 恰好为 0）就看不见 —— 这是「未勾选的类别不显示」的直接推论。
     */
    fun keepsDirection(inWatchlist: Boolean, percent: Double?): Boolean {
        if (!directionFiltering) return true
        return when (categoryOf(inWatchlist, percent)) {
            Category.ZX -> zx
            Category.UP -> up
            Category.DOWN -> down
            Category.FLAT -> false
        }
    }

    /** 类型维度：只要有一个类型没勾就排除（[StockTypes] 给的位掩码） */
    fun keepsTypes(types: Int): Boolean {
        if (!typeFiltering) return true
        for (row in FilterRow.TYPE_ROWS) if (!get(row) && StockTypes.has(types, row)) return false
        return true
    }

    /** 两个维度同时满足才留下 */
    fun keeps(inWatchlist: Boolean, percent: Double?, types: Int): Boolean =
        keepsDirection(inWatchlist, percent) && keepsTypes(types)

    companion object {
        /** 不筛（方向全不勾 + 类型全勾） */
        val OFF = StockFilters()

        /** 一只股票的类别 */
        fun categoryOf(inWatchlist: Boolean, percent: Double?): Category = when {
            inWatchlist -> Category.ZX
            percent == null -> Category.FLAT
            percent > 0.0 -> Category.UP
            percent < 0.0 -> Category.DOWN
            else -> Category.FLAT
        }
    }
}

/** 一只股票在「涨跌与自选」维度上的唯一类别 */
enum class Category { UP, DOWN, ZX, FLAT }

/**
 * 面板里的一行 —— **选项的单一正本**：策略详情页的面板按它建行，
 * 行情页注入到宿主面板里的三行也按它建行，文案与落盘键都在这里，两边不可能写歪。
 */
enum class FilterRow(val key: String, val label: String) {
    UP("up", "涨家股票"),
    DOWN("down", "跌家股票"),
    ZX("zx", "自选股票"),
    CYB("cyb", "创业板"),
    KCB("kcb", "科创板"),
    BZA("bza", "北证A"),
    WKB("wkb", "未开板次新"),
    ST("st", "ST股"),
    ;

    /** 是不是「股票类型」那一组（面板分组 / 行情侧宿主自己的五行的同步都按它分） */
    val isType: Boolean get() = this in TYPE_ROWS

    /**
     * 在 [TYPE_ROWS] 里的下标（0..4）。
     *
     * 用途：筛选时按类型统计命中数要按下标直接写数组。
     * 原先用的是 `TYPE_ROWS.indexOf(row)` —— 那是**线性查找**（还要分配一个迭代器），
     * 而它在最内层循环里每项调 5 次（每项 5 次 `indexOf` + 5 个迭代器）。
     * 这里在枚举初始化时算好一次，**下标与 [TYPE_ROWS] 的顺序天然一致**。
     */
    val typeIndex: Int by lazy { TYPE_ROWS.indexOf(this) }

    companion object {
        /** 涨跌与自选组（策略侧原有的三项） */
        val DIRECTION_ROWS = listOf(UP, DOWN, ZX)

        /** 股票类型组（行情页「沪深京A」漏斗里原有的五项） */
        val TYPE_ROWS = listOf(CYB, KCB, BZA, WKB, ST)

        /** 面板两组的分组头文案 —— 策略面板与行情面板注入的行都用它，两边读起来是一套 */
        const val SECTION_DIRECTION = "涨跌与自选"
        const val SECTION_TYPE = "股票类型"
    }
}

/** 日志里的一行可读描述（早退诊断与「条件已生效」都打它） */
fun StockFilters.describe(): String = buildString {
    append("涨家股票=").append(up)
    append(" 跌家股票=").append(down)
    append(" 自选股票=").append(zx)
    append(" | 创业板=").append(cyb)
    append(" 科创板=").append(kcb)
    append(" 北证A=").append(bzA)
    append(" 未开板次新=").append(wkb)
    append(" ST股=").append(st)
}
