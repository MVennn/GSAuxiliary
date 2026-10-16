package io.github.MVennn.GSAuxiliary.feature.aistare

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.github.MVennn.GSAuxiliary.BuildConfig
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.core.QuickConfigEngine
import io.github.MVennn.GSAuxiliary.core.QuickConfigStore
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.aistare.quote.DomQuoteSource
import io.github.MVennn.GSAuxiliary.feature.aistare.quote.HostQuoteSource
import io.github.MVennn.GSAuxiliary.feature.aistare.quote.PublicQuoteSource
import io.github.MVennn.GSAuxiliary.feature.aistare.quote.QuoteSession
import io.github.MVennn.GSAuxiliary.util.Reflect
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.math.BigDecimal
import java.util.concurrent.Executors

/**
 * 智能盯盘「一键配置」：在价格页（H5）底部「保存」右边注入「配置」按钮，
 * 点击后按用户搭好的方案把「最新涨到 / 最新跌到」填上。
 *
 * ## 实现路径（为什么全走 H5 注入）
 *
 * 价格页是 WebView 里的 H5（`uiautomator` 看到的「保存」只是可访问性映射），
 * 原生视图注入根本摆不到「保存」旁边 —— 本项目的分界在这里完全成立：
 * **原生页走视图注入，H5 页走脚本注入**（方案文档附记）。
 *
 * ## 链路
 *
 * ```
 * onPageFinished（钩宿主 WebViewClient 子类，§10-2 实测 HybirdWebView$4）
 *   └ URL 命中 /earlywarning/setStrategy →
 *       addJavascriptInterface(gsaux) + evaluateJavascript(INJECT + init)
 * 点「配置」→ JS 调 gsaux.apply(id)（自建桥，不进宿主命令漏斗）
 *   └ JavaBridge 线程 → 主线程抓页面文本（③DOM 通道的原料）
 *       → 子线程取数（QuoteSession：①宿主 → ②公开行情 → ③DOM，通道锁定）
 *       → QuickConfigEngine 算价（区间校验）
 *       → 主线程 evaluateJavascript(fill(up, down))
 *       → JS 写值 + 派发 input/change/blur，宿主自己的校验接管（保存键变可用）
 * ```
 *
 * ## 刻意不做（方案文档 §3.2）
 *
 * - **套用后不自动点「保存」**：落库是宿主的动作，算错价格直接写进盯盘策略
 *   代价比多点一下大得多（§10-6 实测保存不跳页，用户改完自己点）；
 * - 取数失败**不写值**，页面顶部轻提示「取数失败」—— 绝不写半截或 0。
 */
class AiStareQuickConfigFeature : Feature {

    override val id = "aistare_quick_config"
    override val title = "智能盯盘一键配置"

    override fun isEnabled(cfg: ConfigSnapshot) = cfg.aistareQuickEnabled

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 取数 / 计算线程：每次套用新建一个单线程池，用完即弃（低频动作，无泄漏顾虑） */
    private val executor get() = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gsaux-quickcfg").apply { isDaemon = true }
    }

    /** 最近一个加载过价格页的 WebView（关开关时对它调 remove；弱引用防泄漏） */
    @Volatile
    private var currentWebView: WeakReference<WebView>? = null

    /** 宿主行情通道（构造时挂不了钩 —— 需要在 install 里挂 StockInfo 构造器） */
    private var hostSource: HostQuoteSource? = null

    /** 已动态挂过 onPageFinished 的 client 类（防重复挂） */
    private val hookedClients = HashSet<String>()

    /** JS 脚本（页面契约常量 + 按钮文案，一次构建） */
    private val injectedJs: String by lazy {
        AiStareJs.build(
            buttonText = AiStareQuickConfigTargets.BUTTON_TEXT,
            buttonColor = AiStareQuickConfigTargets.BUTTON_COLOR,
            saveText = AiStareQuickConfigTargets.SAVE_BUTTON_TEXT,
        )
    }

    // ------------------------------------------------------------ 安装

    @SuppressLint("SetJavaScriptEnabled")
    override fun install(env: FeatureEnv, bag: HookBag) {
        val cl = env.classLoader

        // ---- ① 宿主行情通道：挂 StockInfo 构造器，缓存最近实例（HostQuoteSource 用） ----
        val host = HostQuoteSource(cl)
        hostSource = host
        Reflect.loadClass(HostQuoteSource.HOST_STOCK_INFO, cl)?.let { infoCls ->
            for (ctor in runCatching { infoCls.declaredConstructors }.getOrDefault(emptyArray())) {
                runCatching {
                    ctor.isAccessible = true
                    bag.add(
                        env.xposed.hook(ctor)
                            .setId("$id:stockinfo-ctor")
                            .intercept { chain ->
                                val r = chain.proceed()
                                runCatching { host.noteInstance(chain.thisObject) }
                                r
                            },
                    )
                }
            }
        }

        // ---- ② 主挂点：setWebViewClient 动态抓实际子类（不依赖匿名类编号，§10-2） ----
        val webViewCls = Reflect.loadClass("android.webkit.WebView", cl)
        if (webViewCls != null) {
            for (m in Reflect.methodsNamed(webViewCls, "setWebViewClient")) {
                bag.add(
                    env.xposed.hook(m)
                        .setId("$id:setWebViewClient")
                        .intercept { chain ->
                            val r = chain.proceed()
                            runCatching {
                                val client = chain.args.firstOrNull() ?: return@runCatching
                                hookClientOnPageFinished(env, bag, client.javaClass)
                            }.onFailure { GLog.w("一键配置：抓 WebViewClient 子类失败", it) }
                            r
                        },
                )
            }
        }

        // ---- ③ 兜底：直接按实测类名挂（setWebViewClient 时机万一错过） ----
        Reflect.loadClass(AiStareQuickConfigTargets.WEBVIEW_CLIENT_FALLBACK, cl)?.let { cls ->
            hookClientOnPageFinished(env, bag, cls)
        }
    }

    /**
     * 给 WebViewClient 子类挂 `onPageFinished`（每个类只挂一次）。
     * **必须钩子类本身** —— 钩基类 `WebViewClient` 拦不到子类覆写（§10-2）。
     */
    private fun hookClientOnPageFinished(env: FeatureEnv, bag: HookBag, clientClass: Class<*>) {
        val name = clientClass.name
        // 只认宿主的 HybirdWebView$N 匿名子类，别的页面（登录等）的 client 不碰
        if (!name.startsWith(AiStareQuickConfigTargets.WEBVIEW_CLIENT_PREFIX)) return
        synchronized(hookedClients) {
            if (!hookedClients.add(name)) return
        }
        for (m in Reflect.methodsNamed(clientClass, "onPageFinished")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("$id:onPageFinished:$name")
                    .intercept { chain ->
                        val r = chain.proceed()
                        runCatching {
                            val wv = chain.args.firstOrNull() as? WebView ?: return@runCatching
                            val url = chain.args.getOrNull(1) as? String ?: ""
                            onPageFinished(env, wv, url)
                        }.onFailure { GLog.w("一键配置：onPageFinished 后处理失败", it) }
                        r
                    },
            )
        }
        GLog.i("一键配置：已挂 $name#onPageFinished")
    }

    // ------------------------------------------------------------ 页面注入

    private fun onPageFinished(env: FeatureEnv, webView: WebView, url: String) {
        if (!isEnabled(env.config)) return
        if (AiStareQuickConfigTargets.PAGE_URL_MARKS.none { url.contains(it) }) return

        currentWebView = WeakReference(webView)
        mainHandler.post { injectInto(env, webView, url) }
    }

    private fun injectInto(env: FeatureEnv, webView: WebView, url: String) {
        runCatching {
            val bridge = Bridge(env)
            webView.addJavascriptInterface(bridge, AiStareQuickConfigTargets.JS_BRIDGE_NAME)
            webView.evaluateJavascript(injectedJs, null)
            webView.evaluateJavascript(
                AiStareJs.initCall(schemesJson(env), true),
                null,
            )
            GLog.i("一键配置：已注入价格页（${Uri.parse(url).getQueryParameter("stockCode")}）")
        }.onFailure { GLog.w("一键配置：JS 注入失败", it) }
    }

    /** 方案摘要 JSON（JS 弹层用）：[{id,name,preview}] */
    private fun schemesJson(env: FeatureEnv): String {
        val data = QuickConfigStore.decode(env.config.aiStareQuickConfig)
        val sb = StringBuilder("[")
        data.schemes.forEachIndexed { i, s ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(jsonEscape(s.id))
                .append("\",\"name\":\"").append(jsonEscape(s.name))
                .append("\",\"preview\":\"").append(jsonEscape(s.preview))
                .append("\"}")
        }
        return sb.append(']').toString()
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")

    // ------------------------------------------------------------ 配置变化

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        val wv = currentWebView?.get() ?: return
        mainHandler.post {
            runCatching {
                // 不分开关状态统一重发 init：开 = 补注入 / 更新方案摘要；关 = JS 端摘按钮
                wv.evaluateJavascript(
                    AiStareJs.initCall(schemesJson(env), isEnabled(cfg)),
                    null,
                )
            }
        }
    }

    // ------------------------------------------------------------ 自建 JS 桥

    /**
     * 注入给 H5 的桥对象。**刻意不进宿主命令漏斗**：`gsaux.apply/manage` 由
     * `addJavascriptInterface` 直达，宿主的 `GsBridge#showCommandError`
     * 永远看不到我们的命令（§0 / §10-3）。
     */
    private inner class Bridge(private val env: FeatureEnv) {

        /** JS 调 `gsaux.apply("s1")`：套用方案（JavaBridge 线程） */
        @JavascriptInterface
        fun apply(schemeId: String) {
            val cfg = env.config
            if (!cfg.aistareQuickEnabled) return
            val wv = currentWebView?.get() ?: return

            val scheme = QuickConfigStore.decode(cfg.aiStareQuickConfig)
                .schemes.firstOrNull { it.id == schemeId } ?: return
            val url = runCatching { wv.url }.getOrNull() ?: return
            val parsed = parseStockCode(url) ?: run {
                GLog.w("一键配置：URL 里解不出 stockCode（$url）")
                postToast(wv, "取数失败")
                return
            }

            // 先抓页面全文（③ DOM 通道的原料；必须在主线程调 WebView）
            mainHandler.post {
                runCatching {
                    wv.evaluateJavascript("(document.body ? (document.body.innerText || '') : '')") { raw ->
                        val pageText = runCatching {
                            JSONTokener(raw).nextValue() as? String
                        }.getOrNull().orEmpty()
                        executor.execute {
                            applyOnWorker(env, wv, scheme, parsed.first, parsed.second, pageText)
                        }
                    }
                }.onFailure {
                    GLog.w("一键配置：抓取页面文本失败", it)
                    executor.execute {
                        applyOnWorker(env, wv, scheme, parsed.first, parsed.second, "")
                    }
                }
            }
        }

        /** JS 调 `gsaux.manage()`：拉起模块 App 并直达「一键配置」二级页（§6.1） */
        @JavascriptInterface
        fun manage() {
            mainHandler.post {
                runCatching {
                    val ctx = hostApplicationContext() ?: run {
                        GLog.w("一键配置：拿不到宿主 Context，拉起模块 App 失败")
                        return@post
                    }
                    val intent = Intent(Intent.ACTION_MAIN)
                        .setClassName(BuildConfig.APPLICATION_ID, "io.github.MVennn.GSAuxiliary.ui.SettingsActivity")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.putExtra(QuickConfigStore.EXTRA_ROUTE, QuickConfigStore.ROUTE_QUICK_CONFIG)
                    ctx.startActivity(intent)
                }.onFailure { GLog.w("一键配置：拉起模块 App 失败", it) }
            }
        }
    }

    // ------------------------------------------------------------ 取数 + 计算（子线程）

    private fun applyOnWorker(
        env: FeatureEnv,
        wv: WebView,
        scheme: QuickConfigStore.Scheme,
        code: String,
        market: String,
        pageText: String,
    ) {
        val session = QuoteSession(listOfNotNull(hostSource, PublicQuoteSource(), DomQuoteSource(pageText)))
        val snapshot = session.snapshot(code, market)

        val input = QuickConfigEngine.Input(
            code = code,
            market = market,
            snapshot = snapshot,
            cost = null, // cost 通道未确认（§10-5），首版恒为「无持仓」
            klines = { n -> session.dailyKline(code, market, n) },
        )
        val result = runCatching { QuickConfigEngine.evaluate(scheme, input) }
            .onFailure { GLog.w("一键配置：计算异常（整体放弃）", it) }
            .getOrNull()

        mainHandler.post {
            runCatching {
                val (up, down) = result
                    ?: run {
                        // 整体放弃：不写值，顶部轻提示（§3.2「取数失败不写值」）
                        wv.evaluateJavascript(AiStareJs.toastCall("取数失败"), null)
                        return@post
                    }
                wv.evaluateJavascript(AiStareJs.fillCall(toPlain(up), toPlain(down)), null)
                GLog.i("一键配置：已套用「${scheme.name}」（${session.usedSource}）涨=$up 跌=$down")
            }.onFailure { GLog.w("一键配置：回填失败", it) }
        }
    }

    private fun postToast(wv: WebView, text: String) {
        mainHandler.post {
            runCatching { wv.evaluateJavascript(AiStareJs.toastCall(text), null) }
        }
    }

    // ------------------------------------------------------------ 工具

    /** URL → (code, market)；实测参数名 `stockCode=300045.1`（§1.4） */
    private fun parseStockCode(url: String): Pair<String, String>? {
        val raw = runCatching { Uri.parse(url).getQueryParameter("stockCode") }.getOrNull() ?: return null
        val dot = raw.lastIndexOf('.')
        if (dot <= 0 || dot == raw.length - 1) return null
        return raw.substring(0, dot) to raw.substring(dot + 1)
    }

    /**
     * 宿主进程的 Application Context（`startActivity` 需要）。
     * 与 `GszxModule.currentAppClassLoader` 同一个技法：反射 `ActivityThread.currentApplication()`，
     * 价格页运行时宿主早已起来，必定非空。
     */
    private fun hostApplicationContext(): android.content.Context? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val m = at.getDeclaredMethod("currentApplication")
        m.isAccessible = true
        m.invoke(null) as? android.content.Context
    }.getOrNull()

    /** BigDecimal → JS（两位小数字符串，不科学计数） */
    private fun toPlain(v: BigDecimal): String = v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
}
