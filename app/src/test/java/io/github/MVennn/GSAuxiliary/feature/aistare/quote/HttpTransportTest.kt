package io.github.MVennn.GSAuxiliary.feature.aistare.quote

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP 客户端的**连接释放时机**。
 *
 * ## 这条测试为什么重要
 *
 * `HttpTransport` 里的 `disconnect()` 时机是两头都要命的：
 *
 *  - **该断不断** → socket 挂在连接池里，连点几次就把自己的连接额度耗干净
 *    （原实现的第 3 个缺口）；
 *  - **不该断也断** → 连接回不了 keep-alive 池，每个请求重走一遍 TCP + TLS 握手
 *    （移动网络一次 1~3 个 RTT，100~600ms）—— 取数一只股票打两个接口，
 *    每只多付 0.2~1.2s，用户是**能感觉到**的。
 *
 * 两种错法在真机上都不好查（一个表现为「取数越来越慢」，一个表现为「取数慢慢失败」），
 * 而正确时机只有三条规则：
 *
 *  1. **2xx 且响应体读干净** → 不 disconnect（连接可复用）；
 *  2. **状态码不是 2xx** → disconnect（错误页的 body 我们没读，不读干净不能复用）；
 *  3. **读超上限 / 空响应 / 抛异常** → disconnect（没读完）。
 *
 * 下面用假连接把三条规则逐条钉死。
 */
class HttpTransportTest {

    /** 假连接：记录 disconnect 次数，按剧本给状态码与响应体 */
    private class FakeConn(
        val status: Int,
        val body: ByteArray?,
        val throwOnRead: Boolean = false,
    ) : HttpURLConnection(URL("https://example.invalid/x")) {
        var disconnectCount = 0
            private set
        var disconnectCalled = false
            private set

        override fun connect() = Unit
        override fun disconnect() {
            disconnectCount++
            disconnectCalled = true
        }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int {
            if (status == 0) throw IOException("模拟的连接失败")
            return status
        }
        override fun getInputStream(): InputStream {
            if (throwOnRead) throw IOException("模拟的读超时")
            return ByteArrayInputStream(body ?: ByteArray(0))
        }
        override fun getErrorStream(): InputStream? = null
    }

    /** 把一次请求喂给 HttpTransport，返回那次用的假连接 */
    private fun run(
        status: Int,
        body: ByteArray?,
        throwOnRead: Boolean = false,
        attempts: Int = 1,
    ): FakeConn {
        val conn = FakeConn(status, body, throwOnRead)
        HttpTransport.get(
            url = "https://example.invalid/x",
            attempts = attempts,
            open = { conn },
        )
        return conn
    }

    @Test
    fun `2xx 且读干净 不 disconnect - 连接回池复用`() {
        val conn = run(200, "OK".toByteArray())
        assertEquals(
            0,
            conn.disconnectCount,
            "成功且读干净的连接不该被 disconnect（否则每次请求都要重走 TLS 握手）",
        )
    }

    @Test
    fun `非 2xx 必须 disconnect - 错误页的 body 没读完`() {
        val conn = run(404, "not found".toByteArray())
        assertTrue(conn.disconnectCalled, "4xx 必须 disconnect（错误页 body 没读完，不能复用）")
    }

    @Test
    fun `5xx 也必须 disconnect`() {
        val conn = run(503, "busy".toByteArray())
        assertTrue(conn.disconnectCalled, "5xx 必须 disconnect")
    }

    @Test
    fun `读的时候抛异常 必须 disconnect - 否则 socket 泄漏`() {
        val conn = run(200, null, throwOnRead = true)
        assertTrue(conn.disconnectCalled, "读异常必须 disconnect（否则 socket 泄漏）")
    }

    @Test
    fun `空响应 必须 disconnect`() {
        val conn = run(200, ByteArray(0))
        assertTrue(conn.disconnectCalled, "空响应必须 disconnect")
    }
}
