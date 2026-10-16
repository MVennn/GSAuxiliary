package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.QuoteBar
import io.github.MVennn.GSAuxiliary.core.QuoteSnapshot
import io.github.MVennn.GSAuxiliary.feature.aistare.AiStareQuickConfigTargets
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ② 公开行情 HTTP（兜底通道，§5.2 写死便于复核）—— 事实上的主通道。
 *
 * 接口（腾讯）：
 *  - 实时：`https://qt.gtimg.cn/q=<mkt><code>`，`~` 分隔文本，
 *    字段 3=最新价 4=昨收 5=今开 33=最高 34=最低 1=名称；
 *  - 日 K：`https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=<mkt><code>,day,,,320,`
 *    （末段留空 = **不复权**，与 §5.3 的口径一致；响应 `data.<mkt><code>.day`
 *    是 `[日期, 开, 收, 高, 低, 量]` 数组，升序，含今日盘中未完成的一根）。
 *
 * `market` 换算（§1.4）：**1 → sz，2 → sh**。
 *
 * 缓存与刷新（§5.4）：日 K 按 `code.market` 缓存 5 分钟（盯盘设置是低频动作，
 * 避免连点方案反复打接口）；**实时快照不缓存**（必须是当下的价）。
 * 网络请求在调用方给的线程上跑（本类不做线程切换），超时 3 秒。
 */
class PublicQuoteSource : QuoteSource {

    override val name = "public"

    /** 日 K 缓存：key = code.market，value = (时间戳, 全量 320 根) */
    private val klineCache = HashMap<String, Pair<Long, List<QuoteBar>>>()

    override fun snapshot(code: String, market: String): QuoteSnapshot? {
        val mkt = AiStareQuickConfigTargets.marketToPrefix(market) ?: return null
        val text = httpGet(AiStareQuickConfigTargets.QT_SNAPSHOT_URL + mkt + code) ?: run {
            GLog.w("一键配置取数：公开行情实时请求失败（$mkt$code）")
            return null
        }
        return parseSnapshot(text) ?: run {
            GLog.w("一键配置取数：公开行情实时响应解析失败（$mkt$code）")
            null
        }
    }

    override fun dailyKline(code: String, market: String, n: Int): List<QuoteBar>? {
        if (n <= 0) return null
        val mkt = AiStareQuickConfigTargets.marketToPrefix(market) ?: return null
        val key = "$code.$market"

        val now = System.currentTimeMillis()
        synchronized(klineCache) {
            klineCache[key]?.let { (t, bars) ->
                if (now - t < KLINE_TTL_MS && bars.size >= n) return bars
            }
        }

        val url = String.format(AiStareQuickConfigTargets.QT_KLINE_URL, mkt + code)
        val text = httpGet(url) ?: run {
            GLog.w("一键配置取数：公开行情日 K 请求失败（$mkt$code）")
            return null
        }
        val bars = parseKline(text, mkt + code) ?: run {
            GLog.w("一键配置取数：公开行情日 K 解析失败（$mkt$code）")
            return null
        }
        synchronized(klineCache) {
            if (klineCache.size >= KLINE_CACHE_MAX) klineCache.clear() // 极简淘汰
            klineCache[key] = now to bars
        }
        if (bars.size < n) {
            GLog.w("一键配置取数：日 K 不足 $n 根（${bars.size}，$mkt$code），按失败处理")
            return null
        }
        return bars
    }

    // ------------------------------------------------------------ 解析

    /** `v_sz300045="51~华力创通~300045~12.70~10.58~…"` → 按文档字段的快照 */
    internal fun parseSnapshot(text: String): QuoteSnapshot? {
        val start = text.indexOf('"')
        val end = text.lastIndexOf('"')
        if (start < 0 || end <= start) return null
        val parts = text.substring(start + 1, end).split("~")
        if (parts.size < 35) return null

        fun dec(i: Int): java.math.BigDecimal? =
            parts.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() && it != "0.00" }?.toBigDecimalOrNull()

        val latest = dec(3) ?: return null
        val prevClose = dec(4) ?: return null
        return QuoteSnapshot(
            latest = latest,
            prevClose = prevClose,
            // 今开可能为 0.00（集合竞价前）——给 null 让该项自己失败
            open = dec(5),
            dayHigh = dec(33),
            dayLow = dec(34),
            name = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() },
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
                bars.add(
                    QuoteBar(
                        date = row.optString(0),
                        open = row.optString(1),
                        close = row.optString(2),
                        high = row.optString(3),
                        low = row.optString(4),
                    ),
                )
            }
            if (bars.isEmpty()) null else bars
        }.getOrNull()
    }

    // ------------------------------------------------------------ HTTP

    private fun httpGet(url: String, timeoutMs: Int = TIMEOUT_MS): String? {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) GSAuxiliary")
            conn.inputStream.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        }.onFailure { GLog.w("一键配置取数：HTTP 失败 $url", it) }.getOrNull()
    }

    companion object {
        private const val TIMEOUT_MS = 3000
        private const val KLINE_TTL_MS = 5 * 60 * 1000L
        private const val KLINE_CACHE_MAX = 16
    }
}
