package io.github.MVennn.GSAuxiliary.core

/**
 * 宿主登录态（session / userCode / 签名）的**进程内缓存**。
 *
 * ## 为什么需要它
 *
 * 「按标签组合现算股票」不能靠宿主策略：宿主账号**最多 10 条策略**（服务端硬限制，
 * `createStrategy` 返回 `code 4100「策略数不能超过10个」`），而用户的四个口径拆开是
 * 3×4 + 3 + 4 + 1 = 20 个「市值档 × 量比档」组合，装不下。插件只能用
 * 「临时建一条 → 取数 → 删掉」的方式轮转，这条路需要自己发 HTTP。
 *
 * 自己发请求就要带宿主的登录凭据。这些值**没有**落在 shared_prefs（实测
 * `/data/data/com.guosen.android/shared_prefs/` 下的 xml 里搜不到 session 明文），
 * 唯一稳定的来源是宿主自己发出的请求体 —— 由
 * [io.github.MVennn.GSAuxiliary.feature.hostnet.HostNetProbeFeature] 在
 * `OutputStream.write` 上截获后回填到这里。
 *
 * ## 边界
 *
 *  - **只在目标进程内存里存活**，不落盘、不进日志（日志只打长度与新鲜度）；
 *  - 宿主每次请求都会刷新一遍，天然跟着登录态走，不需要自己管过期；
 *  - 没收到过任何请求时 [isReady] 为 false，调用方按「本轮不取数」降级，不抛异常。
 */
object HostSession {

    /** 会话票据；空 = 还没截到 */
    @Volatile
    var session: String = ""
        private set

    /** 用户号；空 = 还没截到 */
    @Volatile
    var userCode: String = ""
        private set

    /** 请求签名（服务端**不校验参数绑定**，实测复用旧签名发全新参数照样 code=0） */
    @Volatile
    var sign: String = ""
        private set

    /** 公共参数里的包时间（`packtm`），原样复用即可 */
    @Volatile
    var packtm: String = ""
        private set

    /** 公共参数里的版本号（`sysVer`），原样复用即可 */
    @Volatile
    var sysVer: String = ""
        private set

    /**
     * 公共参数块原文：从请求体开头截到 `openapi_sign=<值>` 结束为止。
     *
     * 不复用「逐字段重建」的做法 —— 公共块里有 `deviceVers=12|32|0` 这种带裸竖线、
     * `conn_style=2.0.03.0.0` 这种点分三段的值，字段顺序也可能被服务端当签名输入
     * （虽然实测不校验参数绑定，但没必要去赌）。原样留一段，只替换 `pkg` 与 `reqtime`。
     */
    @Volatile
    var publicBlock: String = ""
        private set

    /** 最近一次成功回填的时间戳（ms） */
    @Volatile
    var updatedAt: Long = 0L
        private set

    /** 三个必填项齐了才算可用 */
    fun isReady(): Boolean = session.isNotEmpty() && userCode.isNotEmpty() && sign.isNotEmpty()

    /**
     * 从一段请求体里把公共参数抠出来。
     *
     * 判据是**必须带 `openapi_sign=`**：宿主的行情/推送请求有几十种，随机挑一条
     * 可能压根没有登录参数；只有走统一网关的那批才带完整公共块。
     *
     * @return 值是否**变了**（首次必 true）。日志用它去重：宿主每发一次请求都会带一遍
     *         公共块，每次都打就会把 logcat 刷满。
     */
    fun capture(body: String): Boolean {
        if (body.indexOf("openapi_sign=") < 0) return false
        val s = paramOf(body, "session")
        val u = paramOf(body, "userCode")
        val g = paramOf(body, "openapi_sign")
        if (s.isEmpty() || g.isEmpty()) return false
        val changed = s != session || g != sign || (u.isNotEmpty() && u != userCode)
        session = s
        sign = g
        if (u.isNotEmpty()) userCode = u
        val p = paramOf(body, "packtm")
        if (p.isNotEmpty()) packtm = p
        val v = paramOf(body, "sysVer")
        if (v.isNotEmpty()) sysVer = v
        val at = body.indexOf("openapi_sign=")
        if (at >= 0) publicBlock = body.substring(0, at + "openapi_sign=".length + g.length)
        updatedAt = System.currentTimeMillis()
        return changed
    }

    /**
     * 取 `key=value` 的 value（到 `&` 或串尾为止）。
     *
     * 不用正则：请求体最多几十 KB，而这条路径每发一次请求就调几次，
     * 正则的编译与回溯都属于白花。命中位置必须落在串首或 `&` 之后，
     * 否则 `xxsession=` 这类山寨 key 会误中。
     */
    private fun paramOf(body: String, key: String): String {
        val k = key + "="
        var i = body.indexOf(k)
        while (i >= 0) {
            if (i == 0 || body[i - 1] == '&' || body[i - 1] == '\n' || body[i - 1] == ' ') {
                val start = i + k.length
                var end = start
                while (end < body.length && body[end] != '&' && body[end] != '\n' && body[end] != ' ') end++
                return body.substring(start, end).trim()
            }
            i = body.indexOf(k, i + 1)
        }
        return ""
    }

    /**
     * 拼一份可直接 POST 的请求体：公共块（换掉 pkg / reqtime）+ `&` + 业务参数。
     *
     * `pkg` 是自增的包序号，服务端只当去重键用；`reqtime` 是 `yyyyMMdd_HHmmssSSS`。
     * 两者都必须和上一次不同，否则服务端可能按「重复包」直接丢弃。
     */
    fun buildBody(bizParams: String, pkgSeq: Long, stamp: String): String {
        var t = publicBlock
        t = replaceParam(t, "pkg", "N_" + pkgSeq)
        t = replaceParam(t, "reqtime", stamp)
        return if (bizParams.isEmpty()) t else t + "&" + bizParams
    }

    /** 把 `key=value` 的 value 换成新值；key 不存在时原样返回 */
    private fun replaceParam(body: String, key: String, value: String): String {
        val k = key + "="
        var i = body.indexOf(k)
        while (i >= 0) {
            if (i == 0 || body[i - 1] == '&') {
                val start = i + k.length
                var end = start
                while (end < body.length && body[end] != '&') end++
                return body.substring(0, start) + value + body.substring(end)
            }
            i = body.indexOf(k, i + 1)
        }
        return body
    }

    /** 诊断用：不打印任何凭据原文，只报长度与新鲜度 */
    fun describe(): String {
        val age = if (updatedAt == 0L) "从未" else (System.currentTimeMillis() - updatedAt).toString() + "ms"
        val uc = if (userCode.isEmpty()) "无" else "有"
        return "session=" + session.length + "B userCode=" + uc + " sign=" + sign.length + "B 年龄=" + age
    }
}
