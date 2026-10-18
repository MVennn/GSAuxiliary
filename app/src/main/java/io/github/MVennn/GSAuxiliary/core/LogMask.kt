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
}
