package io.github.MVennn.GSAuxiliary.feature.stgrow

import io.github.MVennn.GSAuxiliary.feature.filter.StockTypes

/**
 * 行码（`代码.市场`）解析的**唯一入口**（2026-11-14 搭桥）。
 *
 * ## 为什么要有这个文件
 *
 * 同一个「代码.市场」串，仓库里此前有**四套**写法，各自的容错程度还不一样：
 *
 * | 位置 | 口径 |
 * | --- | --- |
 * | `feature/filter/CodeMarketParts.kt:40` | 宽松：无点 → 整串当代码、市场 null |
 * | `feature/strategyselect/StgSelectAllPlan.kt:178` | 归一化 + 市场号缺失按代码前缀推 |
 * | `feature/stgsnap/StgSnapPlan.kt:35` | 严格：恰一个点、6 位代码、市场 ∈ {1,2,5} |
 * | `feature/stgrow/StgRowSelection.kt:84` | 键值口径（缺市场号按 [StockTypes.marketOf] 推） |
 *
 * 这三套其实服务三个不同的问句，谁都对，但**没人记得住谁是哪一套**：
 * 加一个联动功能（筛选 → 全选 → 截图 / 自选判定）时，最容易犯的错就是拿错口径 ——
 * 2026-10-20 那次「类型筛选变松」就是宽松/严格写反了，单测还没抓到（测的不是跑的那条路）。
 *
 * 所以把三种口径**显式命名**收在这里，各自的调用方只调名字，不再自己 inline 切分：
 *  - [splitLenient]：只要「有没有市场号」这一层信息；
 *  - [splitDerived]：必须拿到市场号，缺了就按代码前缀推（推不出才算读不全）；
 *  - [splitStrict]：只接受宿主给的标准形态（截图落盘这种「宁缺勿错」的场合）。
 *
 * 三个函数都是纯逻辑、无分配以外的副作用，可直接单测（见 `RowCodeTest`）。
 */
internal object RowCode {

    private val SIX_DIGITS = Regex("^\\d{6}$")

    /** 1=深、2=沪、5=北 —— 与 [StockTypes] 的实测口径一致 */
    private val MARKETS = setOf("1", "2", "5")

    /** 6 位 A 股代码形态 */
    fun isCode(code: String?): Boolean = code != null && SIX_DIGITS.matches(code)

    /** 市场号是否属于宿主实测的三个取值 */
    fun isMarket(market: String?): Boolean = market != null && market in MARKETS

    /**
     * **宽松口径**（筛选 / 类型判定用）：有点 → 点前为代码、点后为市场；
     * **无点 → 整串当代码、市场 null**。
     *
     * 「无点」绝不能写成 code = null：宿主的 `StockInfo` 是构造后填充的，
     * 「只有代码、没有市场号」是真实存在的中间态，代码为 null 会让类型前缀判定整段失效
     * （历史事故，见 `CodeMarketParts` 的类注释）。
     */
    fun splitLenient(cam: String?): Pair<String?, String?> {
        if (cam.isNullOrEmpty()) return null to null
        val dot = cam.indexOf('.')
        return if (dot > 0) cam.substring(0, dot) to cam.substring(dot + 1) else cam to null
    }

    /** 归一化：去掉市场后缀与空白（`600000.1` → `600000`，`  600000 ` → `600000`） */
    fun codeOf(cam: String?): String = cam?.substringBefore('.')?.trim().orEmpty()

    /**
     * **推导口径**（全选 / 勾选态 / 自选判定用）：必须有市场号；
     * 宿主没给就按代码前缀推（[StockTypes.marketOf]），推不出返回 null。
     */
    fun splitDerived(cam: String?): Pair<String, String>? {
        if (cam.isNullOrEmpty()) return null
        val code = cam.substringBefore('.').trim()
        if (code.isEmpty()) return null
        val given = cam.substringAfter('.', "").trim()
        if (given.isNotEmpty()) return code to given
        val derived = StockTypes.marketOf(code) ?: return null
        return code to derived
    }

    /**
     * **严格口径**（截图落盘 / 持仓导出用，宁缺勿错）：恰好一个点、6 位代码、
     * 市场号 ∈ {1,2,5}；任何一条不满足返回 null，调用方按「读不懂这一行」处理。
     */
    fun splitStrict(cam: String?): Pair<String, String>? {
        if (cam.isNullOrEmpty()) return null
        val dot = cam.indexOf('.')
        if (dot <= 0 || dot != cam.lastIndexOf('.')) return null
        val code = cam.substring(0, dot)
        val market = cam.substring(dot + 1)
        if (!isCode(code) || !isMarket(market)) return null
        return code to market
    }
}
