package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.LogMask
import io.github.MVennn.GSAuxiliary.core.QuoteBar
import io.github.MVennn.GSAuxiliary.core.QuoteSnapshot
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ③ 第二家公开行情（东方财富）—— 腾讯挂掉时的兜底通道。
 *
 * ## 为什么值得多这一级
 *
 * 腾讯的 `qt.gtimg.cn` / `web.ifzq.gtimg.cn` 是**唯一**的外部依赖：它被限流、
 * 被运营商链路掐一下、或者只是握手慢过 3 秒，整个「一键配置」就只剩 DOM 通道
 * 那一个最新价可用（日 K 完全拿不到）。加一家独立供应商，成功率不是一个量级。
 *
 * ## 接口（写死便于复核）
 *
 * - 实时：`https://push2.eastmoney.com/api/qt/stock/get?secid=<0|1>.<code>&fltt=2`
 *   字段 `f43` 最新 / `f44` 最高 / `f45` 最低 / `f46` 今开 / `f60` 昨收 /
 *   `f51` 涨停 / `f52` 跌停（无涨跌停时 -1）/ `f57` 代码 / `f58` 名称。
 *   **`fltt=2` 必须带**：不带的话价格是放大 100 倍的整数（茅台 132900），
 *   自己换算容易在低价股上出错；带上它服务端直接给元。
 * - 日 K：`https://push2his.eastmoney.com/api/qt/stock/kline/get?...&klt=101&fqt=0`
 *   `klines` 是 `"日期,开,收,高,低,量,额"` 的字符串数组，升序。
 *   `fqt=0` = **不复权**，与 §5.3 的口径一致。
 *
 * `market` → `secid` 前缀：**1（深）→ 0，2（沪）→ 1**。认不出就不猜
 * （猜错会拿到**另一只股票**的价，比取不到更糟）。
 */
class EastmoneyQuoteSource : QuoteSource {

    override val name = "eastmoney"

    /** 日 K 缓存：与 [PublicQuoteSource] 同口径（5 分钟），避免同一槽的多个取值项重复打接口 */
    private val klineCache = HashMap<String, Triple<Long, Int, List<QuoteBar>>>()

    override fun snapshot(code: String, market: String): QuoteSnapshot? {
        val secid = secid(code, market) ?: return null
        val text = HttpTransport.get(
            SNAPSHOT_URL + secid,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = SNAPSHOT_TIMEOUT_MS,
            tag = "eastmoney/snapshot",
        ) ?: return null
        return parseSnapshot(text, code) ?: run {
            GLog.w("一键配置取数：东财实时响应解析失败（${LogMask.stock(code, market)}）")
            null
        }
    }

    override fun dailyKline(code: String, market: String, n: Int): List<QuoteBar>? {
        if (n <= 0) return null
        val secid = secid(code, market) ?: return null
        val want = maxOf(n, DEFAULT_BARS).coerceAtMost(MAX_BARS)
        val key = "$code.$market"

        val now = System.currentTimeMillis()
        synchronized(klineCache) {
            klineCache[key]?.let { (t, asked, bars) ->
                if (now - t < KLINE_TTL_MS && (bars.size >= n || asked >= n)) return bars
            }
        }

        val text = HttpTransport.get(
            "$KLINE_URL$secid&klt=101&fqt=0&lmt=$want&end=20500101&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57&fltt=2",
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = KLINE_TIMEOUT_MS,
            tag = "eastmoney/kline",
        ) ?: return null
        val bars = parseKline(text) ?: run {
            GLog.w("一键配置取数：东财日 K 响应解析失败（${LogMask.stock(code, market)}）")
            return null
        }
        synchronized(klineCache) {
            if (klineCache.size >= KLINE_CACHE_MAX) klineCache.clear()
            klineCache[key] = Triple(now, want, bars)
        }
        return bars
    }

    // ------------------------------------------------------------ 解析

    /**
     * `{"data":{"f43":12.70,"f44":13.05,"f45":12.31,"f46":12.40,"f57":"300045",
     * "f58":"华力创通","f60":10.58,"f51":12.70,"f52":8.46}}` → 快照。
     *
     * 两条安全线：**代码必须对得上**（防止 secid 映射错误拿到别的股票）、
     * **价格必须落在合理区间**（防止接口换成放大 100 倍的口径后写进错误价位）。
     * `f51`/`f52` 是官方涨跌停价（-1 = 无涨跌停）；与腾讯同一套合理性检查。
     */
    internal fun parseSnapshot(text: String, expectCode: String): QuoteSnapshot? = runCatching {
        val data = JSONObject(text).optJSONObject("data") ?: return null
        val echoCode = data.optString("f57").takeIf { it.isNotEmpty() && it != "null" }
        if (echoCode != null && echoCode != expectCode) {
            GLog.w(
                "一键配置取数：东财返回的代码（${LogMask.code(echoCode)}）" +
                    "≠ 请求的（${LogMask.code(expectCode)}），丢弃",
            )
            return null
        }
        val latest = price(data, "f43")
        val prevClose = price(data, "f60")
        // 停牌 / 集合竞价前 f43 可能是 "-"：latest 允许缺失（prevClose 系的取值项仍可用），
        // 但昨收必须有 —— 连昨收都没有说明这一级根本不可用
        if (prevClose == null) return null
        // 合理性：latest 与昨收不该差出 35% 以上（正常 A 股单日最大 30%，留点余量）
        if (latest != null && !plausible(latest, prevClose)) {
            GLog.w("一键配置取数：东财价格不合理（latest=$latest prevClose=$prevClose），丢弃")
            return null
        }

        // 官方涨跌停：与腾讯同规则 —— 有值且合理就用，明确 -1 记「无涨跌停」，
        // 不合理当没给（引擎走兜底表）
        val rawUp = num(data, "f51")
        val rawDown = num(data, "f52")
        var limitUp: java.math.BigDecimal? = null
        var limitDown: java.math.BigDecimal? = null
        var noPriceLimit = false
        if (rawUp != null && rawDown != null) {
            when {
                rawUp.signum() <= 0 || rawDown.signum() <= 0 -> noPriceLimit = true
                rawUp > prevClose && rawDown < prevClose -> {
                    limitUp = rawUp.setScale(2, java.math.RoundingMode.HALF_UP)
                    limitDown = rawDown.setScale(2, java.math.RoundingMode.HALF_UP)
                }
                else -> GLog.w("一键配置取数：东财涨跌停字段不合理（up=$rawUp down=$rawDown prev=$prevClose），忽略")
            }
        }

        QuoteSnapshot(
            latest = latest,
            prevClose = prevClose,
            open = price(data, "f46"),
            dayHigh = price(data, "f44"),
            dayLow = price(data, "f45"),
            name = data.optString("f58").takeIf { it.isNotEmpty() && it != "null" },
            limitUp = limitUp,
            limitDown = limitDown,
            noPriceLimit = noPriceLimit,
        )
    }.getOrNull()

    /** `klines` = `["2026-10-16,12.00,12.50,12.60,11.90,12345.00,1.56", …]` */
    internal fun parseKline(text: String): List<QuoteBar>? = runCatching {
        val data = JSONObject(text).optJSONObject("data") ?: return null
        val arr = data.optJSONArray("klines") ?: return null
        val bars = ArrayList<QuoteBar>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optString(i) ?: continue
            val f = row.split(',')
            if (f.size < 5) continue
            // 停牌日 / 脏数据（"-"）跳过：留着会让引擎的「全列可解析」判据整体失败
            if (f[1].toBigDecimalOrNull() == null || f[2].toBigDecimalOrNull() == null ||
                f[3].toBigDecimalOrNull() == null || f[4].toBigDecimalOrNull() == null
            ) continue
            bars.add(QuoteBar(date = f[0], open = f[1], close = f[2], high = f[3], low = f[4]))
        }
        bars.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun price(o: JSONObject, key: String): BigDecimal? {
        if (!o.has(key)) return null
        val raw = o.optDouble(key, Double.NaN)
        if (raw.isNaN() || raw.isInfinite() || raw <= 0.0) return null
        return BigDecimal.valueOf(raw).setScale(2, RoundingMode.HALF_UP)
    }

    /** 宽容取数：数字 / 数字串都收，`"-"`（东财的「无值」写法）与缺失返回 null */
    private fun num(o: JSONObject, key: String): BigDecimal? {
        val raw = o.opt(key) ?: return null
        return when (raw) {
            is Number -> BigDecimal.valueOf(raw.toDouble())
            is String -> raw.trim().toBigDecimalOrNull()
            else -> null
        }
    }

    private fun plausible(latest: BigDecimal, prevClose: BigDecimal): Boolean {
        if (prevClose.signum() <= 0) return false
        val ratio = latest.divide(prevClose, 4, RoundingMode.HALF_UP)
        return ratio > BigDecimal("0.65") && ratio < BigDecimal("1.35")
    }

    /** 1（深）→ 0，2（沪）→ 1；其它（港股 / 美股 / 认不出的市场号）不猜 */
    private fun secid(code: String, market: String): String? = when (market) {
        "1" -> "0.$code"
        "2" -> "1.$code"
        else -> null
    }

    companion object {
        private const val SNAPSHOT_URL =
            "https://push2.eastmoney.com/api/qt/stock/get?fltt=2&fields=f43,f44,f45,f46,f51,f52,f57,f58,f60&secid="
        private const val KLINE_URL =
            "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid="

        private const val DEFAULT_BARS = 320

        /** 腾讯接口上限 2000 根；东财给同样的量（≈8 年），别多要 */
        private const val MAX_BARS = 2000

        private const val CONNECT_TIMEOUT_MS = 4000
        private const val SNAPSHOT_TIMEOUT_MS = 5000
        private const val KLINE_TIMEOUT_MS = 8000

        private const val KLINE_TTL_MS = 5 * 60 * 1000L
        private const val KLINE_CACHE_MAX = 16
    }
}
