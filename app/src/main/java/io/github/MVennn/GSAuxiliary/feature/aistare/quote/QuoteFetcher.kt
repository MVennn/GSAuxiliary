package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.QuickConfigEngine
import io.github.MVennn.GSAuxiliary.core.QuickConfigStore
import java.math.BigDecimal

/**
 * 「一键配置」的取数编排：**先看方案要什么，再按需取，最后算**。
 *
 * 从 `AiStareQuickConfigFeature` 里拆出来的原因有两个：
 *  1. 取数这块的可变因素最多（通道 / 降级 / 缓存 / 口径），混在装着桥、JS 注入、
 *     Activity 反射的大文件里没法单独读；
 *  2. 它现在有明确的行为契约可以对着日志复现：见 [fetch] 的三段日志。
 *
 * ## 通道顺序
 *
 * ```
 * host（宿主行情，只给快照）→ public（腾讯，快照 + 日 K）→ eastmoney（东财，快照 + 日 K）→ dom（页面文本，只给最新价）
 * ```
 *
 * 三个外部/兜底通道并存是本次「取数稳定性」修复的重点：旧实现只有腾讯一家，
 * 它抖一次整个功能就不可用；DOM 又只能救最新价（日 K 一点办法没有）。
 *
 * [publicSource] / [eastmoneySource] 是**成员变量**（不是每次新建）：两者的日 K
 * 缓存都挂在实例上，每次点击 new 一个等于缓存命中率恒为 0 —— 连点两次方案就要
 * 打两次 100 多 KB 的日 K 接口，慢且更容易被限流。
 */
class QuoteFetcher(private val host: HostQuoteSource?) {

    private val publicSource = PublicQuoteSource()
    private val eastmoneySource = EastmoneyQuoteSource()

    /** 取数 + 算价的最终结果 */
    data class Outcome(
        val up: BigDecimal,
        val down: BigDecimal,
        /** 实际用到的通道（日志用，如 `public`、`host+public`） */
        val source: String?,
    )

    /** 方案对数据的需求（决定打哪些接口、按什么顺序挑通道） */
    data class Needs(val snapshot: Boolean, val kline: Boolean)

    /** 用到实时快照的取值项 */
    private val SNAPSHOT_TERMS = setOf(
        "latest", "prevClose", "open", "dayHigh", "dayLow",
        "limitUp", "limitDown",
        "upPct", "downPct", "prevUpPct", "prevDownPct",
    )

    /** 用到日 K 的取值项 */
    private val KLINE_TERMS = setOf(
        "highN", "lowN", "highCloseN", "lowCloseN", "maN", "historyHigh", "historyLow",
    )

    /**
     * 取数并算价；任一必需项失败返回 null（调用方**不写值**，§3.2）。
     *
     * 三段日志都能独立对着看：
     *  - 「方案需要 …」—— 本次要打哪几类接口；
     *  - 会话内部的「实时快照来自 x / 日 K 来自 y」（debug）与「全部通道都失败」（warn）；
     *  - 失败时的「算价放弃（快照=… 日K=… 涨到槽=… 跌到槽=…）」—— 一眼看出是
     *    没取到数，还是取到了但被区间校验挡住。
     */
    fun fetch(
        scheme: QuickConfigStore.Scheme,
        code: String,
        market: String,
        pageText: String,
    ): Outcome? {
        val needs = needsOf(scheme)
        GLog.i(
            "一键配置取数：方案「${scheme.name}」需要" +
                "快照=${needs.snapshot} 日K=${needs.kline}（$code.$market）",
        )

        val sources = ArrayList<QuoteSource>(4)
        if (host != null) sources.add(host)
        sources.add(publicSource)
        sources.add(eastmoneySource)
        // 页面文本抓不到时（页面已销毁 / 注入被拦）就别放 DOM 通道 —— 让它必然
        // 失败一次只是徒增日志噪音
        if (pageText.isNotEmpty()) {
            sources.add(DomQuoteSource(pageText))
        } else {
            GLog.d("一键配置取数：没有页面文本，DOM 通道本次不参与")
        }

        val session = QuoteSession(sources, needs.snapshot, needs.kline)
        val snapshot = session.snapshot(code, market)
        if (needs.snapshot && snapshot == null) {
            GLog.w("一键配置取数：方案需要实时快照，但所有通道都没给出来")
        }

        val input = QuickConfigEngine.Input(
            code = code,
            market = market,
            snapshot = snapshot,
            cost = null, // cost 通道未确认（§10-5），首版恒为「无持仓」
            klines = { n -> session.dailyKline(code, market, n) },
        )
        val result = runCatching { QuickConfigEngine.evaluate(scheme, input) }
            .onFailure { GLog.w("一键配置取数：计算异常（整体放弃）", it) }
            .getOrNull()
        if (result != null) {
            return Outcome(result.first, result.second, session.usedSource)
        }

        // 失败定位：是「没取到数」还是「取到了但不过校验」，分开写清楚
        val up = runCatching { QuickConfigEngine.evaluateSlot(scheme.up, input) }.getOrNull()
        val down = runCatching { QuickConfigEngine.evaluateSlot(scheme.down, input) }.getOrNull()
        GLog.w(
            "一键配置取数：算价放弃（快照=${session.snapshotSource ?: "无"}，" +
                "日K=${session.klineSource ?: "无"}；" +
                "涨到槽=${up?.toPlainString() ?: "失败"}，跌到槽=${down?.toPlainString() ?: "失败"}）",
        )
        return null
    }

    /** 方案里用到的取值项 → 需要哪几类数据 */
    fun needsOf(scheme: QuickConfigStore.Scheme): Needs {
        val used = (scheme.up.terms + scheme.down.terms).map { it.source }.toSet()
        return Needs(
            // 「最近前高 / 前低」只靠日 K 就能算（返回值天然在今日高低点之外），不额外要快照
            snapshot = used.any { it in SNAPSHOT_TERMS },
            kline = used.any { it in KLINE_TERMS },
        )
    }
}
