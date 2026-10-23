package io.github.MVennn.GSAuxiliary.feature.zxcopy

/**
 * 剪贴板文本的纯逻辑：代码规范化、去重、格式化。
 *
 * 抽出来单独一个文件是为了能单测 —— 真机上「复制出来少一行 / 多一行 / 串了市场」
 * 这类问题肉眼很难逐条核对，而这里的规则可以用单元测试钉死。
 */
object ZxCopyPlan {

    /**
     * 一只待复制的股票。
     *
     * [market] 只参与去重，**不写进剪贴板** —— 用户要的格式是「名称 代码」，
     * 例：`金山办公 688111`。保留它是为了「同一代码、不同市场」的极端情况
     * （例如指数与个股同为 `000001`）不会被误当成重复项丢掉。
     */
    data class Stock(
        val name: String?,
        val code: String,
        val market: String? = null,
    )

    /** 6 位数字代码；前后不能再挨着数字，避免从 7 位串里截出前 6 位 */
    private val CODE = Regex("(?<!\\d)\\d{6}(?!\\d)")

    /**
     * 从 `m_stkCode` / `codeAndMarket` / 行内 TextView 文本里取出 6 位代码。
     *
     * 实测取值形态：`688111`、`688111.2`、`SH688111`、`688111（两融）`。
     * 取不到就返回 null（调用方跳过该条，绝不猜一个代码出来）。
     */
    fun normalizeCode(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return CODE.find(s)?.value
    }

    /** 名称去空白；空名称返回 null（调用方退化成「只复制代码」） */
    fun normalizeName(raw: String?): String? {
        val s = raw?.replace('\n', ' ')?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return s.takeIf { it.isNotEmpty() }
    }

    /**
     * 生成最终剪贴板文本：一行一只，`名称 代码`；名称缺失时只写代码。
     *
     * - 代码不规范（不是 6 位数字）的条目**跳过**；
     * - 同一「代码.市场」只保留第一次出现（保持列表当前顺序）；
     * - 行尾不留换行。
     */
    fun format(stocks: List<Stock>): String {
        val seen = HashSet<String>(stocks.size)
        val lines = ArrayList<String>(stocks.size)
        for (s in stocks) {
            val code = normalizeCode(s.code) ?: continue
            val key = if (s.market.isNullOrBlank()) code else "$code.${s.market}"
            if (!seen.add(key)) continue
            val name = normalizeName(s.name)
            lines.add(if (name == null) code else "$name $code")
        }
        return lines.joinToString("\n")
    }
}
