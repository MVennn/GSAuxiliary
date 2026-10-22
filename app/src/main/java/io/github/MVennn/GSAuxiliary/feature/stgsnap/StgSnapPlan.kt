package io.github.MVennn.GSAuxiliary.feature.stgsnap

/**
 * 一只要截图的股票，以及它对应的宿主数据对象。
 *
 * [item] 是宿主适配器里的 `StockInfo`，只在目标进程里用于转 `IntentStockInfo`；
 * 纯逻辑单元测试传 `null` 即可。
 */
internal data class SelectedStock(
    val code: String,
    val market: String,
    val name: String,
    val item: Any? = null,
) {
    val cam: String get() = "$code.$market"
}

/**
 * 「截图并添加」的纯逻辑：校验、去重、文件名、标题匹配。
 *
 * 放在单独文件里便于单元测试 —— 这些判据一旦错了，会表现为「截错股票 / 少截一只 /
 * 文件名对不上」，很难从界面看出来。
 */
internal object StgSnapPlan {

    private val CODE_RE = Regex("^\\d{6}$")
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\r\\n\\t]")

    fun isValidCode(code: String?): Boolean = code != null && CODE_RE.matches(code)

    /** 1=深、2=沪、5=北（与 `StockTypes` 的实测口径一致） */
    fun isValidMarket(market: String?): Boolean = market == "1" || market == "2" || market == "5"

    /** `300006.1` → (`300006`, `1`)；格式不对返回 null */
    fun splitCam(cam: String?): Pair<String, String>? {
        if (cam.isNullOrEmpty()) return null
        val dot = cam.indexOf('.')
        if (dot <= 0 || dot != cam.lastIndexOf('.')) return null
        val code = cam.substring(0, dot)
        val market = cam.substring(dot + 1)
        if (!isValidCode(code) || !isValidMarket(market)) return null
        return code to market
    }

    /** 按 `code.market` 去重，保留第一次出现的顺序 */
    fun dedup(items: List<SelectedStock>): List<SelectedStock> {
        val seen = HashSet<String>(items.size * 2)
        val out = ArrayList<SelectedStock>(items.size)
        for (item in items) {
            if (seen.add(item.cam)) out.add(item)
        }
        return out
    }

    /** 名称清掉路径非法字符与空白，过长截断；空名回退代码 */
    fun sanitizeName(name: String?, code: String): String {
        val cleaned = (name ?: "")
            .replace(ILLEGAL, "")
            .replace(" ", "")
            .trim()
        val cut = if (cleaned.length > 20) cleaned.substring(0, 20) else cleaned
        return cut.ifEmpty { code }
    }

    /** `01_贵州茅台_600519.png` */
    fun fileName(index: Int, name: String?, code: String): String {
        val safe = sanitizeName(name, code)
        val no = index.coerceAtLeast(1).toString().padStart(2, '0')
        return "${no}_${safe}_${code}.png"
    }

    /** 当前页标题（「名称 代码」）是否就是目标股票 */
    fun titleMatches(title: CharSequence?, code: String): Boolean =
        !title.isNullOrEmpty() && title.contains(code)

    /** 底部「已选 N」文本 → N；解析失败返回 -1 */
    fun parseCount(text: CharSequence?): Int {
        if (text == null) return -1
        val digits = text.filter { it.isDigit() }.toString()
        return digits.toIntOrNull() ?: -1
    }
}
