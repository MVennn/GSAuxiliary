package io.github.MVennn.GSAuxiliary.feature.filter

/**
 * 「股票类型」判定 —— 面板里那五行的**唯一判据**（策略详情页自己判，行情页由宿主判）。
 *
 * ## 每个类型怎么认（按可靠度排序，能拿到行情数据字段就用字段）
 *
 * | 类型 | 首选判据（`StockInfo` 字段） | 兜底判据（代码前缀 / 名称） |
 * |---|---|---|
 * | 创业板 | `isCyb` | `300` / `301` / `302` |
 * | 科创板 | `isKCPlate` | `688` / `689` |
 * | 北证A | 市场号 `5`（实测 `920575.5`） | `43` / `83` / `87` / `88` / `920` |
 * | 未开板次新 | `isXXLD` | 无（拿不到字段就不认，见下） |
 * | ST股 | — | 名称含 `ST`（`*ST卓然` / `ST摘帽` …；行情页实测的判定口径与此一致） |
 *
 * 类型是**并存**的：`*ST卓然`（688121）同时是「科创板」与「ST股」——
 * 只要其中一个类型没在面板上勾选，这只股票就不显示（宿主行情面板实测就是这个口径：
 * 只取消勾选「ST股」时它照样消失，尽管「科创板」还勾着）。
 *
 * ## 两个已知的不确定点（App 升级后按这里复核）
 *
 * 1. **未开板次新**只能靠 `StockInfo.isXXLD`（探针 dump 出来的字段名，8.6.0 / vc271）。
 *    它的运行时取值没做过核对（登录态过期，策略页进不去），所以这里**只认字段、
 *    不做猜测性兜底** —— 认不出就不把股票归到这一类：勾着它时这类股票照常显示（等于没筛），
 *    不会误伤别的股票。真要复核：拿一只当天上市的次新，看它的 `isXXLD` 是不是 true。
 * 2. 市场号取自实测（`1` = 深 / `2` = 沪 / `5` = 北），另配代码前缀兜底；
 *    两个都对不上就不归「北证A」。
 */
object StockTypes {

    const val NONE = 0
    const val CYB = 1
    const val KCB = 1 shl 1
    const val BZA = 1 shl 2
    const val WKB = 1 shl 3
    const val ST = 1 shl 4

    /** 某一类型行对应的位（非类型行返回 0） */
    fun bitOf(row: FilterRow): Int = when (row) {
        FilterRow.CYB -> CYB
        FilterRow.KCB -> KCB
        FilterRow.BZA -> BZA
        FilterRow.WKB -> WKB
        FilterRow.ST -> ST
        else -> 0
    }

    /** 位掩码里有没有某一行对应的类型 */
    fun has(types: Int, row: FilterRow): Boolean {
        val bit = bitOf(row)
        if (bit == 0) return false
        return types and bit != 0
    }

    /**
     * 由「代码 + 市场号 + 名称」判类型（不依赖行情数据字段的那一半）。
     *
     * @param code   6 位代码（`301666`）
     * @param market 市场号（`1` 深 / `2` 沪 / `5` 北），可空
     * @param name   股票名称（判 ST 用），可空
     */
    fun byCodeAndMarket(code: String?, market: String?, name: String?): Int {
        var t = NONE
        if (name != null && name.contains("ST")) t = t or ST
        val c = code?.trim().orEmpty()
        if (c.isEmpty()) return t
        val m = market?.trim()
        when {
            m == MARKET_BJ || c.startsWith("43") || c.startsWith("83") ||
                c.startsWith("87") || c.startsWith("88") || c.startsWith("920") -> t = t or BZA
            c.startsWith("688") || c.startsWith("689") -> t = t or KCB
            c.startsWith("300") || c.startsWith("301") || c.startsWith("302") -> t = t or CYB
        }
        return t
    }

    /** `"301666.1"`（代码.市场，宿主 `StockInfo.codeAndMarket` 的口径）→ 类型位掩码 */
    fun byCodeAndMarket(cam: String?, name: String?): Int {
        if (cam.isNullOrEmpty()) return byCodeAndMarket(null, null, name)
        val dot = cam.indexOf('.')
        return if (dot > 0) {
            byCodeAndMarket(cam.substring(0, dot), cam.substring(dot + 1), name)
        } else {
            byCodeAndMarket(cam, null, name)
        }
    }

    /**
     * 行情页的排行榜行里只有 6 位代码，没有市场号 —— 按代码前缀推。
     *
     * 只为「自选股票」这一项服务（`ZXWorkflow.isBelongByStock(code, market)` 要市场号）；
     * 推不出来返回 null，调用方按「查不到自选」处理（保守：宁可少筛，不乱筛）。
     */
    fun marketOf(code: String?): String? {
        val c = code?.trim().orEmpty()
        if (c.length < 6) return null
        return when {
            // 沪：600/601/603/605/688… 与 900 开头的沪 B；92 开头是北交所，先让给下面
            c.startsWith("6") || (c.startsWith("9") && !c.startsWith("92")) -> MARKET_SH
            // 深：000/001/002/003/300/301… 与 200 开头的深 B
            c.startsWith("0") || c.startsWith("2") || c.startsWith("3") -> MARKET_SZ
            // 北：43/83/87/88/920 四类新老代码
            c.startsWith("4") || c.startsWith("8") || c.startsWith("92") -> MARKET_BJ
            else -> null
        }
    }

    /** 日志里的一行可读描述（筛完把命中数打出来时用） */
    fun describe(types: Int): String = buildString {
        var first = true
        for (row in FilterRow.TYPE_ROWS) {
            if (!has(types, row)) continue
            if (!first) append("/")
            append(row.label)
            first = false
        }
        if (first) append("无")
    }

    const val MARKET_SH = "2"
    const val MARKET_SZ = "1"

    /** 北交所的市场号（实测 `920575.5`；北证A 的票在行情页就是这个号） */
    const val MARKET_BJ = "5"
}
