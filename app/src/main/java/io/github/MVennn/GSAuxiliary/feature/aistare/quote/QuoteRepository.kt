package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.QuoteBar
import io.github.MVennn.GSAuxiliary.core.QuoteSnapshot

/**
 * 取数源接口。各实现按「① 宿主 → ② 腾讯 → ③ 东财 → ④ 页面 DOM」排队，
 * 每一级失败都记一条明确日志（§5.2）。
 *
 * [supportsSnapshot] / [supportsKline] 是**能力声明**：宿主通道只能给实时快照
 * （日 K 入口未确认，§10-4），页面 DOM 只能给最新价 —— 旧实现没有这个区分，
 * 于是「锁到一个给不了日 K 的通道」会把整次取数判死（见 [QuoteSession]）。
 */
interface QuoteSource {
    /** 通道名（日志与降级提示用），如 "host" / "public" / "eastmoney" / "dom" */
    val name: String

    /** 能否提供实时快照 */
    val supportsSnapshot: Boolean get() = true

    /** 能否提供日 K */
    val supportsKline: Boolean get() = true

    /** 实时快照；失败返回 null（降级到下一级） */
    fun snapshot(code: String, market: String): QuoteSnapshot?

    /**
     * 最近 n 根日 K（升序，不复权）；失败返回 null。
     * 只给最新价的通道（DOM）永远返回 null —— 由 [supportsKline] 提前排除。
     */
    fun dailyKline(code: String, market: String, n: Int): List<QuoteBar>?
}

/**
 * 一次套用的取数会话。
 *
 * ## 与旧实现的关键差别（2026-10-17 取数稳定性修复）
 *
 * 旧实现是「第一个给出数据的通道**全局锁定**，之后别的一律不试」：
 *
 *  - 主路径（个股详情页 → 智能盯盘 → 价格）下宿主 `StockInfo` 缓存命中，
 *    `snapshot()` 把通道锁在 **host**；而 `HostQuoteSource.dailyKline()`
 *    按设计恒为 null（日 K 入口未确认）→ 只要方案里有 `highN` / `lowN`
 *    （**默认方案「30 日高低」就是它**），日 K 必然拿不到，整次套用直接
 *    判「取数失败」。这是「经常取不到数据」最主要的根因。
 *  - 锁定之后哪怕该通道只是**偶发**超时一次，也没有任何降级机会 ——
 *    一次抖动 = 一次失败。
 *
 * 现在的规则：
 *
 *  1. **按能力挑通道**：[needsKline] 为真时，取快照优先选「两样都能给」的
 *     通道（口径一致）；都不行再退回只给快照的通道（有数比没数好，日志会
 *     记下混搭）。
 *  2. **同通道优先、失败继续降级**：上次成功的通道排在最前（省一次
 *     无谓的失败），但它失败就继续试后面的，**不再一锤定音**。
 *  3. **按需取数**：方案里没有实时项就不打快照接口（少一次 3 秒的请求，
 *     也少一次被锁到 host 的机会）。
 *
 * 会话在每次「点配置」时新建，用完即弃 —— 无状态、无跨点击缓存
 * （日 K 的 5 分钟缓存由 [PublicQuoteSource] 自己持有，且该实例是**持久**的）。
 */
class QuoteSession(
    private val sources: List<QuoteSource>,
    private val needsSnapshot: Boolean = true,
    private val needsKline: Boolean = true,
) {

    /** 实时快照来自哪个通道（null = 没取 / 全失败） */
    @Volatile
    var snapshotSource: String? = null
        private set

    /** 日 K 来自哪个通道（null = 没取 / 全失败） */
    @Volatile
    var klineSource: String? = null
        private set

    /** 本次实际用到的通道（日志 / 来源提示用），多个时用 `+` 连起来 */
    val usedSource: String?
        get() = listOfNotNull(snapshotSource, klineSource).distinct()
            .joinToString("+")
            .ifEmpty { null }

    private val lock = Any()

    companion object {
        /**
         * 日 K 「可用」的最少根数：低于它视同该通道没给出有效数据（换下一家），
         * 高于它就按实有返回（历史项要的是有多少算多少）。
         */
        private const val MIN_USABLE_BARS = 20
    }

    /** 上一次成功给出快照的通道（下次优先，不排它） */
    @Volatile
    private var preferredSnapshot: QuoteSource? = null

    /** 上一次成功给出日 K 的通道 */
    @Volatile
    private var preferredKline: QuoteSource? = null

    fun snapshot(code: String, market: String): QuoteSnapshot? {
        if (!needsSnapshot) return null
        val candidates = snapshotCandidates()
        for (s in candidates) {
            val r = attempt(s) { it.snapshot(code, market) } ?: continue
            synchronized(lock) { if (preferredSnapshot == null) preferredSnapshot = s }
            snapshotSource = s.name
            GLog.d("一键配置取数：实时快照来自 ${s.name}（$code.$market）")
            return r
        }
        GLog.w("一键配置取数：全部通道的实时快照都失败（候选=${candidates.joinToString { it.name }}）")
        return null
    }

    fun dailyKline(code: String, market: String, n: Int): List<QuoteBar>? {
        if (!needsKline) return null
        val candidates = klineCandidates()
        var best: Pair<QuoteSource, List<QuoteBar>>? = null
        for (s in candidates) {
            val r = attempt(s) { it.dailyKline(code, market, n) } ?: continue
            // 「够算」才直接用：>= n，或至少 [MIN_USABLE_BARS] 根（历史项要的是
            // 有多少算多少）。只回个位数的直接换下一家 —— 北交所在腾讯的 fqkline
            // 只回 1 根，别让这种残缺数据占住「本次通道」的位置。
            if (r.size >= n || r.size >= MIN_USABLE_BARS) {
                synchronized(lock) { if (preferredKline == null) preferredKline = s }
                klineSource = s.name
                GLog.d("一键配置取数：日 K 来自 ${s.name}（$code.$market，n=$n，${r.size} 根）")
                return r
            }
            if (best == null || r.size > best.second.size) best = s to r
        }
        // 全部通道都不够：把最长的那份交出去（次新股本来就只有几根，引擎自己判够不够）
        if (best != null) {
            klineSource = best.first.name
            GLog.w(
                "一键配置取数：所有通道的日 K 都不足（最多 ${best.second.size} 根，要 $n，$code.$market），按实有返回",
            )
            return best.second
        }
        GLog.w("一键配置取数：全部通道的日 K 都失败（$code.$market，n=$n，候选=${candidates.joinToString { it.name }}）")
        return null
    }

    // ------------------------------------------------------------ 候选排序

    private fun snapshotCandidates(): List<QuoteSource> {
        val usable = sources.filter { it.supportsSnapshot }
        // 方案里同时要日 K 时，先只在「两样都能给」的通道里挑 —— 免得最新价是
        // 宿主的、30 日高是腾讯的。都失败才退回只给快照的通道（宿主的价最准）。
        val ordered = if (needsKline) usable.sortedByDescending { it.supportsKline } else usable
        val pref = preferredSnapshot
        return if (pref != null && pref.supportsSnapshot) listOf(pref) + ordered.filter { it !== pref } else ordered
    }

    private fun klineCandidates(): List<QuoteSource> {
        val usable = sources.filter { it.supportsKline }
        // 快照已经由某个能给日 K 的通道给了，就优先用同一个通道（口径一致）
        val pref = preferredKline ?: preferredSnapshot?.takeIf { it.supportsKline }
        return if (pref != null) listOf(pref) + usable.filter { it !== pref } else usable
    }

    private inline fun <T> attempt(source: QuoteSource, block: (QuoteSource) -> T?): T? = try {
        block(source)
    } catch (t: Throwable) {
        // 单通道抛异常不打断整条降级链（反射 / 解析 / 网络都可能抛）
        GLog.w("一键配置取数：通道 ${source.name} 异常（降级到下一级）", t)
        null
    }
}
