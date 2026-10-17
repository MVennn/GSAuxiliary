package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.QuoteBar
import io.github.MVennn.GSAuxiliary.core.QuoteSnapshot
import io.github.MVennn.GSAuxiliary.feature.aistare.AiStareQuickConfigTargets
import org.json.JSONObject

/**
 * ② 公开行情 HTTP（腾讯，事实上的主通道，§5.2 写死便于复核）。
 *
 * 接口（腾讯）：
 *  - 实时：`https://qt.gtimg.cn/q=<mkt><code>`，`~` 分隔文本（**GBK**），
 *    字段 3=最新价 4=昨收 5=今开 33=最高 34=最低 47=涨停 48=跌停（无则 -1）
 *    1=名称 2=代码；
 *  - 日 K：`https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=<mkt><code>,day,,,320,`
 *    （末段留空 = **不复权**，与 §5.3 的口径一致；响应 `data.<mkt><code>.day`
 *    是 `[日期, 开, 收, 高, 低, 量]` 数组，升序，含今日盘中未完成的一根）。
 *
 * `market` 换算（§1.4）：**1 → sz，2 → sh**。
 *
 * 缓存与刷新（§5.4）：日 K 按 `code.market` 缓存 5 分钟（盯盘设置是低频动作，
 * 避免连点方案反复打接口）；**实时快照不缓存**（必须是当下的价）。
 * 网络请求走 [HttpTransport]（状态码判定 + 重试 + 编码 + 连接释放），
 * 本类不做线程切换。
 */
class PublicQuoteSource : QuoteSource {

    override val name = "public"

    /** 日 K 缓存：key = code.market，value = (时间戳, 当时请求的根数, 拿到的行) */
    private val klineCache = HashMap<String, Triple<Long, Int, List<QuoteBar>>>()

    override fun snapshot(code: String, market: String): QuoteSnapshot? {
        val mkt = AiStareQuickConfigTargets.marketToPrefix(market) ?: return null
        // ⚠️ 快照接口是 **GBK**：按 UTF-8 解会把「华力创通」解成一串问号，
        // 名称一废，引擎的 ST 判定就失效（→ 涨停价算错 5%/10%）。
        // 数字字段是 ASCII 所以旧实现没暴露，但这是实打实的错值。
        val text = HttpTransport.get(
            AiStareQuickConfigTargets.QT_SNAPSHOT_URL + mkt + code,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = SNAPSHOT_TIMEOUT_MS,
            charset = HttpTransport.GBK,
            tag = "tencent/snapshot",
        ) ?: run {
            GLog.w("一键配置取数：公开行情实时请求失败（$mkt$code）")
            return null
        }
        return parseSnapshot(text, code) ?: run {
            GLog.w("一键配置取数：公开行情实时响应解析失败（$mkt$code）")
            null
        }
    }

    override fun dailyKline(code: String, market: String, n: Int): List<QuoteBar>? {
        if (n <= 0) return null
        val mkt = AiStareQuickConfigTargets.marketToPrefix(market) ?: return null
        val key = "$code.$market"
        // 要多少根问多少根（下限 320）：「历史前高 / 前低」要 2000 根（≈8 年），
        // 「30 日最高价」只要 320。腾讯单次上限就是 2000，超过会回 param error。
        val want = maxOf(n, DEFAULT_BARS).coerceAtMost(MAX_BARS)

        val now = System.currentTimeMillis()
        synchronized(klineCache) {
            klineCache[key]?.let { (t, asked, bars) ->
                // 够用就命中：① 行数够 n；② 上次要的就不少（说明这只股票总共只有这么多）
                if (now - t < KLINE_TTL_MS && (bars.size >= n || asked >= n)) return bars
            }
        }

        val url = String.format(AiStareQuickConfigTargets.QT_KLINE_URL, mkt + code)
            // 连逗号一起换：模板是 `…,day,,,320,`，只替数字会少一格 → 服务端 param error
            .replace(KLINE_COUNT_TOKEN, ",$want,")
        // 2000 根的响应 100 多 KB，快照那 5 秒容易读不完 —— 日 K 单独给更长
        val text = HttpTransport.get(
            url,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = KLINE_TIMEOUT_MS,
            tag = "tencent/kline",
        ) ?: run {
            GLog.w("一键配置取数：公开行情日 K 请求失败（$mkt$code）")
            return null
        }
        val bars = parseKline(text, mkt + code) ?: run {
            GLog.w("一键配置取数：公开行情日 K 解析失败（$mkt$code）")
            return null
        }
        synchronized(klineCache) {
            if (klineCache.size >= KLINE_CACHE_MAX) klineCache.clear() // 极简淘汰
            klineCache[key] = Triple(now, want, bars)
        }
        // **不再因为「不足 n 根」返回 null**：新股 / 次新股本来就凑不到 2000 根，
        // 「历史前高」要的是「有多少算多少」。够不够由引擎按各项口径自己判
        // （highN/lowN/maN 仍要求 size >= N，历史项只要求 >= 2）。
        if (bars.size < n) {
            GLog.i("一键配置取数：日 K 只有 ${bars.size} 根（要 $n，$mkt$code），按实有数据返回")
        }
        return bars
    }

    // ------------------------------------------------------------ 解析

    /**
     * `v_sz300045="51~华力创通~300045~12.70~10.58~…"` → 按文档字段的快照。
     *
     * 两条容错（2026-10-17）：
     *  - **代码校验**：`parts[2]` 与请求的代码不一致就丢弃 —— 映射错 / 串包时
     *    宁可降级，也不能把别的股票的价写进盯盘策略；
     *  - **latest 允许缺失**：停牌、集合竞价前 `latest = 0.00`，旧实现直接判整份
     *    快照失败，于是「涨停价 / 昨收 ±p%」这类**只依赖昨收**的方案也跟着失败。
     *    现在 latest 给 null、由取值项自己判，昨收系的项照常可用。
     */
    internal fun parseSnapshot(text: String, expectCode: String): QuoteSnapshot? {
        val start = text.indexOf('"')
        val end = text.lastIndexOf('"')
        if (start < 0 || end <= start) return null
        val parts = text.substring(start + 1, end).split("~")
        // 字段 3/4 至少要在 —— 正常 A 股响应有 50 多项，但别再要求 35 项那么死
        if (parts.size < 5) return null

        val echoCode = parts.getOrNull(2)?.trim()
        if (!echoCode.isNullOrEmpty() && echoCode != expectCode) {
            GLog.w("一键配置取数：腾讯返回的代码（$echoCode）≠ 请求的（$expectCode），丢弃")
            return null
        }

        fun dec(i: Int): java.math.BigDecimal? =
            parts.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() && it != "0.00" }?.toBigDecimalOrNull()

        // 昨收是这一类取值的根（涨停 / 昨收 ±p% / 区间校验都要它），必须有
        val prevClose = dec(4) ?: return null
        if (prevClose.signum() <= 0) return null

        // 官方涨跌停价（字段 47/48，实测确认：正常股票给价，指数/新股/退市整理给 -1）。
        // 有值且过合理性检查就直接用 —— ST 幅度调整这类规则变化不用跟着改表；
        // -1 是「明确没有涨跌停」，与「字段不在」分开传给引擎（前者判失败，后者兜底表算）。
        var limitUp: java.math.BigDecimal? = null
        var limitDown: java.math.BigDecimal? = null
        var noPriceLimit = false
        val ru = parts.getOrNull(47)?.trim()?.toBigDecimalOrNull()
        val rd = parts.getOrNull(48)?.trim()?.toBigDecimalOrNull()
        if (ru != null && rd != null) {
            when {
                ru.signum() <= 0 || rd.signum() <= 0 -> noPriceLimit = true
                ru > prevClose && rd < prevClose -> {
                    limitUp = ru.setScale(2, java.math.RoundingMode.HALF_UP)
                    limitDown = rd.setScale(2, java.math.RoundingMode.HALF_UP)
                }
                // 数值不合理（字段挪位 / 串包）→ 当没给，引擎走兜底表
                else -> GLog.w("一键配置取数：腾讯涨跌停字段不合理（up=$ru down=$rd prev=$prevClose），忽略")
            }
        }

        return QuoteSnapshot(
            latest = dec(3),
            prevClose = prevClose,
            // 今开可能为 0.00（集合竞价前）——给 null 让该项自己失败
            open = dec(5),
            dayHigh = dec(33),
            dayLow = dec(34),
            name = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
            limitUp = limitUp,
            limitDown = limitDown,
            noPriceLimit = noPriceLimit,
        )
    }

    /**
     * 响应 `data.<code>.{day|qfqday}`：`[["2026-01-02","10.00","10.50","10.60","9.90","12345"], …]`
     * 字段序：日期, 开, 收, 高, 低, 量。兼容 `qfqday`（万一接口返回了前复权键）。
     */
    internal fun parseKline(text: String, symbol: String): List<QuoteBar>? {
        return runCatching {
            val data = JSONObject(text).optJSONObject("data") ?: return null
            val obj = data.optJSONObject(symbol) ?: return null
            val arr = obj.optJSONArray("day") ?: obj.optJSONArray("qfqday") ?: return null
            val bars = ArrayList<QuoteBar>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                if (row.length() < 5) continue
                // 停牌日 / 脏行（"-"、空串）直接跳过：留着会让引擎「整列可解析」的
                // 判据整体失败 —— 一根坏数据连带把 250 根的历史项全判死
                val open = row.optString(1)
                val close = row.optString(2)
                val high = row.optString(3)
                val low = row.optString(4)
                if (open.toBigDecimalOrNull() == null || close.toBigDecimalOrNull() == null ||
                    high.toBigDecimalOrNull() == null || low.toBigDecimalOrNull() == null
                ) continue
                bars.add(QuoteBar(date = row.optString(0), open = open, close = close, high = high, low = low))
            }
            if (bars.isEmpty()) null else bars
        }.getOrNull()
    }

    companion object {
        /** 请求根数的下限（普通取值项够用） */
        private const val DEFAULT_BARS = 320

        /** 腾讯单次上限 2000 根，超过直接回 param error */
        private const val MAX_BARS = 2000

        /**
         * URL 模板里「根数」那一格的占位符 —— **连着前后逗号一起匹配**。
         * 模板是 `…,day,,,320,`；只替数字（320 → 2000）会变成 `…,day,,2000,`，
         * 参数少一格，服务端直接回 param error（踩过）。
         */
        private const val KLINE_COUNT_TOKEN = ",320,"

        private const val CONNECT_TIMEOUT_MS = 4000
        private const val SNAPSHOT_TIMEOUT_MS = 5000

        /** 日 K 的读超时（2000 根 100 多 KB，快照那 5 秒不够） */
        private const val KLINE_TIMEOUT_MS = 8000
        private const val KLINE_TTL_MS = 5 * 60 * 1000L
        private const val KLINE_CACHE_MAX = 16
    }
}
