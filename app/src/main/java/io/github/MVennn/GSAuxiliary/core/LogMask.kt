package io.github.MVennn.GSAuxiliary.core

/**
 * 日志里的个股代码打码（`docs/CODE-RULES.md` §3：INFO 及以上不得出现完整 6 位代码）。
 *
 * 为什么要有这一层：目标进程的日志会经 LSPosed 落到 `/data/adb/lspd/log/`，
 * 用户求助时经常整段贴出来或导出。完整代码等于把关注列表交出去。
 *
 * 口径：保留前两位与市场后缀（`30****.1`），既能对上是哪只，又拼不出完整代码。
 * 拿不到合法代码时统一写「代码已略」——**不要**退化成打印原串。
 */
object LogMask {

    /** `"300045.1"` → `"30****.1"`；非法输入 → `"代码已略"` */
    fun stock(codeDotMarket: String?): String {
        if (codeDotMarket.isNullOrBlank()) return "代码已略"
        val dot = codeDotMarket.lastIndexOf('.')
        if (dot <= 2) return "代码已略"
        return codeDotMarket.take(2) + "****" + codeDotMarket.substring(dot)
    }

    /** 代码与市场分开传的调用方用这个（取数层都是这种形状） */
    fun stock(code: String?, market: String?): String =
        stock(if (code.isNullOrBlank()) null else "$code.${market.orEmpty()}")

    /**
     * 只有代码、没有市场后缀时用这个 —— 例如行情接口**回显**的代码（`300045`）。
     *
     * 与 [stock] 同一口径：保留前两位 + `****`。拼不出完整代码，但对排查仍有用
     * （能看出「回显的确实是另一只」还是「整体错位」）。
     */
    fun code(code: String?): String =
        if (code.isNullOrBlank() || code.length <= 2) "代码已略" else code.take(2) + "****"

    /**
     * 把 URL / 请求体 / 头部里的**凭据类取值**换成 `***`（2026-11-14 加）。
     *
     * 为什么需要：网络探针会把宿主的请求体与头部打进日志，而宿主的行情 / 策略请求
     * 带着 `session=…&openapi_sign=…`，头部还可能有 `Cookie` / `Authorization`。
     * 这些日志会落进 512KB 滚动日志、还能被界面「导出日志」整段拷走 ——
     * 等于把账号会话写进了可外发的文件（`docs/CODE-RULES.md` §3 只挡了个股代码）。
     *
     * 口径：**键名保留、取值抹掉**，仍然看得出「带了哪些参数」，但拼不出会话。
     * 只用于诊断日志；采集用的原始串不经过这里（[HostSession] 要真值）。
     */
    fun secrets(text: String?): String {
        if (text.isNullOrEmpty()) return text.orEmpty()
        val masked = SECRET_PAIR.replace(text) { m ->
            m.groupValues[1] + m.groupValues[2] + REDACTED
        }
        // 凭据抹完还要过一遍个股代码：行情接口常在查询串里回显 stockCode=600519.1，
        // 只抹 session 的话，INFO 日志照样把用户盯的票整段写进可导出文件（§3.1）。
        return STOCK_PARAM.replace(masked) { m ->
            m.groupValues[1] + "=" + m.groupValues[2].take(2) + "****" + m.groupValues[3]
        }
    }

    private const val REDACTED = "***"

    /**
     * 键名 + 分隔符 + 取值。
     *
     * 用原始字符串（三引号）写，正则里的反斜杠**不转义**，改的时候别再套一层。
     * 取值止于 `&`、空白、分号、逗号、引号与右花括号 —— 够覆盖 URL 查询串、
     * `k=v&k2=v2` 请求体、`Cookie: a=b; c=d` 与 JSON 里的 `"session":"…"`。
     */
    private val SECRET_PAIR = Regex(
        """(?i)(openapi_sign|usercode|jsessionid|authorization|cookie|session|ticket|token|password|passwd|pwd|sign)(\s*[=:]\s*)([^&\s;,"'}]+)"""
    )

    /**
     * 查询串里的个股代码：`stockCode=600519.1` / `symbol=000001`（键名不带分隔符，只认 `=`）。
     *
     * 与 [SECRET_PAIR] 分开写的唯一原因：这里要保留前两位与市场后缀，
     * 不能整段抹成 `***` —— 排查时还需要看出「是哪只票」。
     */
    private val STOCK_PARAM = Regex(
        """(?i)\b(stockCode|stock_code|symbol|secuCode|secu_code)=(\d{6})(\.\d+)?"""
    )
}