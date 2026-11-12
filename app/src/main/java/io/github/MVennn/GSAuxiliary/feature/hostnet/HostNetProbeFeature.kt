package io.github.MVennn.GSAuxiliary.feature.hostnet

import io.github.MVennn.GSAuxiliary.BuildConfig
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HostSession
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.util.Reflect

/**
 * 宿主网络探针（**临时诊断用**）。
 *
 * 目的：宿主「标签选股 → 添加至我的策略」整条链路是服务器下发的 H5，
 * 页面结构与提交接口都在服务端，本地既没有 dex 符号也没开 WebView devtools。
 * 逐个 UI 点 16 个策略既慢又容易点错，所以先在这里把宿主自己发出的
 * HTTP 请求（okhttp3）原样记下来，找到「新建策略」那条接口后再决定是脚本化
 * 提交还是继续走 UI。
 *
 * 挂载点选 [okhttp3.OkHttpClient#newCall]：3.x / 4.x 都存在且是 public，
 * 比 RealCall（4.x 挪到 okhttp3.internal.connection）稳定；Request 直接从
 * 参数拿，不必反查 Call。
 *
 * 只打印 POST / PUT 与命中关键字的 GET —— 行情轮询全是 GET，不滤会把
 * logcat 刷满。日志可能含 cookie / token：仅本机 logcat，不外发。
 */
class HostNetProbeFeature : Feature {

    override val id = "hostnet.probe"
    override val title = "宿主网络探针"

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = true

    override fun install(env: FeatureEnv, bag: HookBag) {
        val cls = runCatching { env.classLoader.loadClass("okhttp3.OkHttpClient") }.getOrNull()
        if (cls == null) {
            GLog.w("网络探针：宿主里没有 okhttp3.OkHttpClient，跳过")
            return
        }
        val methods = Reflect.methodsNamed(cls, "newCall")
        var n = 0
        for (m in methods) {
            if (m.parameterTypes.size != 1) continue
            bag.add(
                env.xposed.hook(m)
                    .setId("hostnet:newCall:" + m.parameterTypes[0].simpleName)
                    .intercept { chain ->
                        runCatching { dump(env, chain.getArg(0)) }
                            .onFailure { GLog.w("网络探针：解析请求失败", it) }
                        chain.proceed()
                    }
            )
            n++
        }
        GLog.i("网络探针：已挂 OkHttpClient.newCall ×" + n)

        // 兜底：宿主若不走 okhttp3（加固壳常把网络下沉到 native 或别的实现），
        // 至少把 java.net.URL 这一层抓住 —— 它在 bootstrap loader 里，
        // 任何实现的 HTTP(S) 只要经过 URL.openConnection 就一定会进来。
        val urlCls = runCatching { Class.forName("java.net.URL") }.getOrNull()
        val oc = urlCls?.let { Reflect.methodsNamed(it, "openConnection") }
        if (oc != null && oc.isNotEmpty()) {
            var k = 0
            for (m in oc) {
                if (m.parameterTypes.isNotEmpty()) continue
                bag.add(
                    env.xposed.hook(m)
                        .setId("hostnet:openConnection")
                        .intercept { chain ->
                            runCatching {
                                val u = chain.thisObject.toString()
                                pendingUrl.set(u)
                                noteUrl(u)
                            }
                            chain.proceed()
                        }
                )
                k++
            }
            GLog.i("网络探针：已挂 URL.openConnection ×" + k)
        }
        hookOutputStreamWrite(env, bag)
        // WebView 远程调试只在 debug 包里开：它会把页面全部请求与 Cookie 暴露给任何能做
        // adb forward 的进程，release 包不该带这个口子（详见 enableWebViewDebug 的 KDoc）。
        if (BuildConfig.DEBUG) enableWebViewDebug(env)
        hookWebViewLoadUrl(env, bag)
    }

    /**
     * 打开 WebView 远程调试：宿主「标签选股」是服务端下发的 H5，选股请求由页面 JS 发出，
     * 不走 java.net / HttpURLConnection，本地探针抓不到。把 devtools 通道打开后，
     * PC 侧 `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` + CDP 的 Network 域
     * 就能看到页面到底调了哪个接口 —— 这是「按标签直接查股票」的唯一取证途径。
     *
     * 只在 **debug 包**里打开（install 里按 [BuildConfig.DEBUG] 判）：release 包带着这个口子，
     * 任何能做 adb forward 的进程都能看到页面的全部请求与 Cookie。
     */
    private fun enableWebViewDebug(env: FeatureEnv) {
        val r = runCatching {
            val cls = env.classLoader.loadClass("android.webkit.WebView")
            cls.getMethod("setWebContentsDebuggingEnabled", Boolean::class.javaPrimitiveType)
                .invoke(null, true)
        }
        if (r.isSuccess) {
            GLog.i("网络探针：WebView 调试已打开（devtools 通道可用）")
        } else {
            val e = r.exceptionOrNull()
            GLog.w("网络探针：WebView 调试打开失败 → " + (e?.javaClass?.name ?: "?") + ": " + (e?.message ?: ""))
        }
    }

    /**
     * 抓 WebView 打开的 H5 地址。
     *
     * 宿主「标签选股」整页是服务端下发的 H5，页面 JS 发出的选股请求既不走 okhttp3
     * 也不走 java.net（WebView 的 XHR 在 native 层），本地钩不到请求体。
     * 但只要拿到页面地址，就能在 PC 上把它和它的 JS 包整份拉下来，
     * 直接从 JS 里读出「按标签查股票」的真实 path 与参数名 —— 比 CDP 稳。
     *
     * 同一个地址只记一次，避免页面重载刷屏。
     */
    private fun hookWebViewLoadUrl(env: FeatureEnv, bag: HookBag) {
        val cls = runCatching { Class.forName("android.webkit.WebView") }.getOrNull()
        if (cls == null) {
            GLog.w("网络探针：拿不到 android.webkit.WebView，跳过 loadUrl 钩子")
            return
        }
        val seen = HashSet<String>()
        var n = 0
        for (m in Reflect.methodsNamed(cls, "loadUrl")) {
            val ps = m.parameterTypes
            if (ps.isEmpty() || ps[0] != String::class.java) continue
            bag.add(
                env.xposed.hook(m)
                    .setId("hostnet:loadUrl:" + ps.size)
                    .intercept { chain ->
                        runCatching {
                            val raw = chain.getArg(0) as? String ?: return@runCatching
                            val hit = synchronized(seen) { seen.size < 40 && seen.add(raw) }
                            if (hit) GLog.i("网络探针 WEBVIEW → " + raw)
                        }
                        chain.proceed()
                    }
            )
            n++
        }
        GLog.i("网络探针：已挂 WebView.loadUrl ×" + n)
    }

    /**
     * 抓 POST 请求体：hook `java.io.OutputStream.write`。
     *
     * 宿主走的是 HttpURLConnection（Android 的实现在 com.android.okhttp.internal.huc），
     * okhttp3 那几个类只是被别的 SDK 引用。请求体在写流那一刻才有明文，
     * 所以用 ThreadLocal 把「本线程刚 openConnection 的 URL」带过来做归属，
     * 只有带着 URL 的那一次写才解析，其他写一律放过（开销只有一次 ThreadLocal 读）。
     */
    private fun hookOutputStreamWrite(env: FeatureEnv, bag: HookBag) {
        val cls = runCatching { Class.forName("java.io.OutputStream") }.getOrNull() ?: return
        var n = 0
        for (m in Reflect.methodsNamed(cls, "write")) {
            val pc = m.parameterTypes.size
            if (pc != 1 && pc != 3) continue
            if (m.parameterTypes[0] != ByteArray::class.java) continue
            bag.add(
                env.xposed.hook(m)
                    .setId("hostnet:write:" + pc)
                    .intercept { chain ->
                        if (pendingUrl.get() != null) {
                            runCatching {
                                val b = chain.getArg(0) as? ByteArray
                                if (b != null) {
                                    val off = if (pc == 3) (chain.getArg(1) as? Int ?: 0) else 0
                                    val len = if (pc == 3) (chain.getArg(2) as? Int ?: b.size) else b.size
                                    if (len > 0) {
                                        val text = String(b, off, minOf(len, BODY_LIMIT), Charsets.UTF_8)
                                        GLog.i("网络探针 BODY → " + pendingUrl.get() + "\n  " + text)
                                        // 顺手把宿主的 session / 签名截下来：聚合要按标签组合
                                        // 自己取数（宿主策略名额只有 10 个，装不下 20 个组合），
                                        // 而这些凭据不在 shared_prefs 里，只能在请求体里捡。
                                        // 用比打印更宽的窗口：公共块在业务参数之前，但也留点余量。
                                        val wide = String(b, off, minOf(len, CAPTURE_LIMIT), Charsets.UTF_8)
                                        if (HostSession.capture(wide)) {
                                            GLog.i("网络探针：已回填宿主会话 " + HostSession.describe())
                                        }
                                    }
                                }
                                pendingUrl.remove()
                            }.onFailure { GLog.w("网络探针：读请求体失败", it) }
                        }
                        chain.proceed()
                    }
            )
            n++
        }
        GLog.i("网络探针：已挂 OutputStream.write ×" + n)
    }

    /** 当前线程刚打开的连接 URL（只有紧接着的那一次写算它的 body） */
    private val pendingUrl = ThreadLocal<String?>()

    /** URL 去重，只留每个 host+path 的第一条，避免行情轮询刷屏 */
    private fun noteUrl(url: String) {
        val key = url.substringBefore('?')
        if (!SEEN.add(key)) return
        GLog.i("网络探针 URL → " + url.take(400))
    }

    private fun dump(env: FeatureEnv, req: Any?) {
        if (req == null) return
        val rc = req.javaClass
        val url = runCatching { rc.getMethod("url").invoke(req).toString() }.getOrNull() ?: return
        val method = runCatching { rc.getMethod("method").invoke(req) as String }.getOrNull() ?: ""
        val bodyText = runCatching { bodyText(env, req, rc) }.getOrNull() ?: ""
        val isWrite = method == "POST" || method == "PUT" || method == "PATCH"
        if (!isWrite && KEYWORDS.none { url.contains(it) }) return
        if (!isWrite && bodyText.isEmpty() && KEYWORDS.none { url.contains(it) }) return
        val headers = runCatching {
            rc.getMethod("headers").invoke(req).toString().replace("\n", " | ")
        }.getOrNull() ?: ""
        GLog.i(
            "网络探针 → " + method + " " + url +
                (if (bodyText.isEmpty()) "" else "\n  body=" + bodyText.take(BODY_LIMIT)) +
                "\n  headers=" + headers.take(HEADER_LIMIT)
        )
    }

    private fun bodyText(env: FeatureEnv, req: Any, rc: Class<*>): String {
        val body = rc.getMethod("body").invoke(req) ?: return ""
        val bc = body.javaClass
        if (bc.name.contains("FormBody")) {
            val size = bc.getMethod("size").invoke(body) as Int
            val nm = bc.getMethod("name", Int::class.javaPrimitiveType)
            val vm = bc.getMethod("value", Int::class.javaPrimitiveType)
            return (0 until size).joinToString("&") {
                nm.invoke(body, it).toString() + "=" + vm.invoke(body, it).toString()
            }
        }
        // 其余 RequestBody 一律 writeTo 到一个空 Buffer 再读出来
        val bufferCls = runCatching { env.classLoader.loadClass("okio.Buffer") }.getOrNull() ?: return ""
        val buffer = bufferCls.getConstructor().newInstance()
        bc.getMethod("writeTo", bufferCls).invoke(body, buffer)
        return bufferCls.getMethod("readUtf8").invoke(buffer) as? String ?: ""
    }

    private companion object {
        val SEEN = java.util.Collections.synchronizedSet(HashSet<String>())
        val KEYWORDS = arrayOf("stg", "strategy", "choose", "aistk", "condition", "tag", "selfstock")
        const val BODY_LIMIT = 3000
        const val CAPTURE_LIMIT = 16384
        const val HEADER_LIMIT = 900
    }
}
