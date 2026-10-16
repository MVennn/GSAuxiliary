package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.QuoteBar
import io.github.MVennn.GSAuxiliary.core.QuoteSnapshot

/**
 * 取数源接口。三个实现按「① 宿主 → ② 公开行情 → ③ 页面 DOM」降级（§5.2），
 * 每一级失败都记一条明确日志。
 */
interface QuoteSource {
    /** 通道名（日志与降级提示用），如 "host" / "public" / "dom" */
    val name: String

    /** 实时快照；失败返回 null（降级到下一级） */
    fun snapshot(code: String, market: String): QuoteSnapshot?

    /**
     * 最近 n 根日 K（升序，不复权）；不足 n 根或失败返回 null。
     * ③ DOM 通道只有页面上的最新价，永远给不了日 K（返回 null）。
     */
    fun dailyKline(code: String, market: String, n: Int): List<QuoteBar>?
}

/**
 * 一次套用的取数会话。
 *
 * **通道锁定**（§5.2）：同一个方案里可能既有实时项又有日 K 项，两者必须走
 * **同一个通道**（口径一致），所以**第一次真正给出数据的通道**本次全程锁定；
 * 已锁定后其它通道不再尝试，该通道自己失败就整体失败 —— 宁可放弃也不混搭
 * 「最新价是宿主的、30 日高是腾讯的」这种口径。
 *
 * 会话在每次「点配置」时新建，用完即弃 —— 无状态、无跨点击缓存
 * （日 K 的 5 分钟缓存由 [PublicQuoteSource] 自己持有）。
 */
class QuoteSession(private val sources: List<QuoteSource>) {

    /** 本次实际使用的通道名（回填来源提示用）；null = 全部失败 */
    @Volatile
    var usedSource: String? = null
        private set

    @Volatile
    private var locked: QuoteSource? = null

    fun snapshot(code: String, market: String): QuoteSnapshot? {
        locked?.let { return runOrNull(it) { s -> s.snapshot(code, market) } }
        for (s in sources) {
            val r = runOrNull(s) { it.snapshot(code, market) }
            if (r != null) {
                lockOn(s)
                return r
            }
        }
        GLog.w("一键配置取数：全部通道的实时快照都失败")
        return null
    }

    fun dailyKline(code: String, market: String, n: Int): List<QuoteBar>? {
        locked?.let { return runOrNull(it) { s -> s.dailyKline(code, market, n) } }
        for (s in sources) {
            val r = runOrNull(s) { it.dailyKline(code, market, n) }
            if (r != null) {
                lockOn(s)
                return r
            }
        }
        // 未锁定成功不算全灭 —— 实时快照可能由后面的调用打通
        GLog.w("一键配置取数：全部通道的日 K 都失败（n=$n）")
        return null
    }

    private fun lockOn(s: QuoteSource) {
        synchronized(this) {
            if (locked == null) {
                locked = s
                usedSource = s.name
                GLog.i("一键配置取数：通道锁定 ${s.name}")
            }
        }
    }

    private inline fun <T> runOrNull(source: QuoteSource, block: (QuoteSource) -> T?): T? {
        return try {
            block(source)
        } catch (t: Throwable) {
            GLog.w("一键配置取数：通道 ${source.name} 异常（降级）", t)
            null
        }
    }
}
