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

    /**
     * 一次截获得到的完整登录态。
     *
     * ## 为什么整体存一份而不是六个 `@Volatile` 字段（2026-11-14）
     *
     * 六个字段各自 volatile 时，[capture] 是「逐个赋值」的：另一条线程在中间读，
     * 会拿到**新的 session 配旧的 sign**（甚至新 publicBlock 配旧 packtm）。
     * [buildBody] 拿这种半新半旧的组合去发请求，服务端认不出是谁，只回来一个业务错误码，
     * 现场看就是「偶发取不到数」，还查不出规律。
     *
     * 现在整份是不可变对象，[capture] 一次发布：读方要么看到上一份完整状态，
     * 要么看到这一份完整状态。
     */
    private data class Snap(
        val session: String = "",
        val userCode: String = "",
        val sign: String = "",
        val packtm: String = "",
        val sysVer: String = "",
        val publicBlock: String = "",
        val updatedAt: Long = 0L,
    )

    @Volatile
    private var snap: Snap = Snap()

    /** 会话票据；空 = 还没截到 */
    val session: String get() = snap.session

    /** 用户号；空 = 还没截到 */
    val userCode: String get() = snap.userCode

    /** 请求签名（服务端**不校验参数绑定**，实测复用旧签名发全新参数照样 code=0） */
    val sign: String get() = snap.sign

    /** 公共参数里的包时间（`packtm`），原样复用即可 */
    val packtm: String get() = snap.packtm

    /** 公共参数里的版本号（`sysVer`），原样复用即可 */
    val sysVer: String get() = snap.sysVer

    /**
     * 公共参数块原文：从请求体开头截到 `openapi_sign=<值>` 结束为止。
     *
     * 不复用「逐字段重建」的做法 —— 公共块里有 `deviceVers=12|32|0` 这种带裸竖线、
     * `conn_style=2.0.03.0.0` 这种点分三段的值，字段顺序也可能被服务端当签名输入
     * （虽然实测不校验参数绑定，但没必要去赌）。原样留一段，只替换 `pkg` 与 `reqtime`。
     */
    val publicBlock: String get() = snap.publicBlock

    /** 最近一次成功回填的时间戳（ms） */
    val updatedAt: Long get() = snap.updatedAt

    /** 三个必填项齐了才算可用 */
    fun isReady(): Boolean = snap.let { it.session.isNotEmpty() && it.userCode.isNotEmpty() && it.sign.isNotEmpty() }

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
        val prev = snap
        val p = paramOf(body, "packtm").ifEmpty { prev.packtm }
        val v = paramOf(body, "sysVer").ifEmpty { prev.sysVer }
        val at = body.indexOf("openapi_sign=")
        val block = if (at >= 0) {
            body.substring(0, at + "openapi_sign=".length + g.length)
        } else {
            prev.publicBlock
        }
        val next = Snap(
            session = s,
            userCode = u.ifEmpty { prev.userCode },
            sign = g,
            packtm = p,
            sysVer = v,
            publicBlock = block,
            updatedAt = System.currentTimeMillis(),
        )
        // 整份发布：读方不会看到「新 session 配旧 sign」。
        snap = next
        return s != prev.session || g != prev.sign || (u.isNotEmpty() && u != prev.userCode)
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
        // 只取一次快照：中途 capture 换了整份也不影响这一趟拼出来的完整性。
        var t = snap.publicBlock
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
        val s = snap
        val age = if (s.updatedAt == 0L) "从未" else (System.currentTimeMillis() - s.updatedAt).toString() + "ms"
        val uc = if (s.userCode.isEmpty()) "无" else "有"
        return "session=" + s.session.length + "B userCode=" + uc + " sign=" + s.sign.length + "B 年龄=" + age
    }
}
