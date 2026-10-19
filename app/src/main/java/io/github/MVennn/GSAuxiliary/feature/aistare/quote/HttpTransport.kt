package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import io.github.MVennn.GSAuxiliary.core.GLog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 取数层的极简 HTTP 客户端。
 *
 * 不用 OkHttp：模块只在目标进程里跑一次几十毫秒的请求，引第三方库等于往宿主的
 * 进程里塞依赖，价值为负。
 *
 * 但裸写 `URL.openConnection().inputStream` 缺了四件事，而**这四件事正是
 * 「经常取不到数」的直接来源**（2026-10-17 排查）：
 *
 *  1. **不看状态码**：旧实现直接开 `inputStream`，4xx / 5xx 一律抛
 *     `FileNotFoundException` 被吞成「失败」，真实原因（限流 / 参数错）
 *     永远进不了日志，事后无从排查。现在按状态码分流并写进日志。
 *  2. **不重试**：移动网络下第一次 DNS + TLS 握手经常就吃掉 3 秒，一次失败
 *     等于本次「取数失败」。现在对**可恢复**错误（连接异常 / 读超时 / 5xx）
 *     重试一次；4xx 不重试（参数错了重试只是白等）。
 *  3. **不释放连接**：只 `close()` 流不 `disconnect()`，异常路径下 socket
 *     会挂在连接池里，连点几次就可能把自己的连接额度耗干净。
 *  4. **不限响应体**：运营商劫持页 / 错误页可能是几十 KB 的 HTML，
 *     整段读进内存没意义。现在封顶 4 MB。
 *
 * 另外把「连接超时」和「读超时」分开给：握手可以短，读大响应要长。
 */
internal object HttpTransport {

    /** 腾讯 `qt.gtimg.cn` 是 GBK（名称字段会乱码 → ST 判定失效）；日 K 与东财是 UTF-8 */
    val GBK: Charset = runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.UTF_8)

    // 行情接口（腾讯/东财）对非浏览器 UA 会拒答或限流；使用常规移动浏览器
    // UA 是公开行情页面的标准请求形态。模块不上传任何用户数据，仅按需查询
    // 公开行情（见 DISCLAIMER 第六条的数据流说明）。
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024
    private const val RETRY_DELAY_MS = 220L

    /**
     * GET 一个文本响应。
     *
     * @param connectTimeoutMs 建立连接（含 TLS 握手）的超时
     * @param readTimeoutMs   单次读取的超时
     * @param charset         响应编码（快照接口是 GBK，别的地方 UTF-8）
     * @param attempts        总尝试次数（含第一次）；默认 2 = 失败重试一次
     * @param tag             日志前缀（写通道名，便于定位是哪一级挂了）
     * @return 响应体；空体 / 非 2xx / 重试后仍失败 → null（调用方按「本级失败」降级）
     */
    fun get(
        url: String,
        connectTimeoutMs: Int = 4000,
        readTimeoutMs: Int = 5000,
        charset: Charset = Charsets.UTF_8,
        attempts: Int = 2,
        tag: String = "",
    ): String? = get(url, connectTimeoutMs, readTimeoutMs, charset, attempts, tag) { URL(it).openConnection() as HttpURLConnection }

    /**
     * [open] 可注入的版本 —— **只给测试用**（生产路径走上面那个重载，行为完全一样）。
     *
     * 为什么要留这个接缝：`disconnect()` 的时机只有三条规则，而它们全都是
     * 「什么时候该断 / 什么时候不该断」—— 不注入假连接就没法在 JVM 上验证
     * （真机上「连接没复用」表现为「取数慢一点」，很难归因）。
     */
    internal fun get(
        url: String,
        connectTimeoutMs: Int = 4000,
        readTimeoutMs: Int = 5000,
        charset: Charset = Charsets.UTF_8,
        attempts: Int = 2,
        tag: String = "",
        open: (String) -> HttpURLConnection,
    ): String? {
        val tries = attempts.coerceAtLeast(1)
        var lastError: Throwable? = null

        for (attempt in 1..tries) {
            var retryable = true
            try {
                val conn = open(url).apply {
                    requestMethod = "GET"
                    this.connectTimeout = connectTimeoutMs
                    this.readTimeout = readTimeoutMs
                    instanceFollowRedirects = true
                    // 不要 gzip：省得自己解压，响应本来就小（日 K 除外，也就 100 多 KB）
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                // ⚠️ **成功且把响应体读干净时不能 disconnect** ——
                // 那样连接回不了 keep-alive 池，每个请求都要重走一遍 TCP + TLS 握手
                // （移动网络一次 1~3 个 RTT，100~600ms）。取数一只股票要打两个接口
                // （快照 + 日 K），每只多付 0.2~1.2s。
                //
                // **其余情况一律断开**（socket 泄漏的风险都在这些路径上）：
                //  · 状态码不是 2xx —— 错误页的 body 我们不读，不读干净就不能复用；
                //  · body 超了 4 MB 上限（`readCapped` 返回 null）—— 没读完；
                //  · 空响应；
                //  · 抛异常（连接超时 / 读超时 / DNS 失败）。
                var reusable = false
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        lastError = IOException("HTTP $code")
                        // 4xx 是我们自己请求的问题（代码/参数/路径），重试无益
                        retryable = code >= 500
                    } else {
                        val bytes = conn.inputStream.use { readCapped(it) }
                        if (bytes == null || bytes.isEmpty()) {
                            lastError = IOException("空响应")
                        } else {
                            // 读干净了 → 连接可以回池复用
                            reusable = true
                            return String(bytes, charset)
                        }
                    }
                } finally {
                    // close() 只关流，不断开连接 —— 要复用就别 disconnect
                    if (!reusable) runCatching { conn.disconnect() }
                }
            } catch (t: Throwable) {
                lastError = t
            }

            if (!retryable) break
            if (attempt < tries) runCatching { Thread.sleep(RETRY_DELAY_MS * attempt) }
        }

        // 失败事实保留在 INFO（方便排查），但不带完整 URL —— URL 里的股票代码
        // 属于用户持仓信息，不能明文进全局日志；含代码的细节降级到 DEBUG。
        GLog.i("一键配置取数：HTTP 失败${if (tag.isEmpty()) "" else "（$tag）"} $lastError")
        GLog.d("一键配置取数：失败请求 URL = $url${lastError?.let { "（$it）" } ?: ""}")
        return null
    }

    /** 读满 [MAX_BODY_BYTES] 就放弃（防止劫持页 / 错误页把内存撑爆） */
    private fun readCapped(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(16 * 1024)
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BODY_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
}
