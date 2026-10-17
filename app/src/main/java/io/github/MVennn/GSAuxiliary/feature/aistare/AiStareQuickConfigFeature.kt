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
import io.github.MVennn.GSAuxiliary.core.QuickConfigStore
import io.github.MVennn.GSAuxiliary.core.TargetApp
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.aistare.quote.HostQuoteSource
import io.github.MVennn.GSAuxiliary.feature.aistare.quote.QuoteFetcher
import io.github.MVennn.GSAuxiliary.util.Reflect
import org.json.JSONTokener
import java.lang.ref.WeakReference
import java.math.BigDecimal
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
 *   └ JavaBridge 线程 → 主线程抓页面文本（DOM 通道的原料，1.5 秒兜底）
 *       → 子线程取数（QuoteFetcher：按方案需求挑通道，
 *         ① 宿主 → ② 腾讯 → ③ 东财 → ④ 页面 DOM，失败逐级降级）
 *       → QuickConfigEngine 算价（区间校验）
 *       → 主线程 evaluateJavascript(fill(up, down))
 *       → JS 写值 + 派发 input/change/blur，宿主自己的校验接管（保存键变可用）
 * ```
 *
 * ## 刻意不做（方案文档 §3.2）
 *
 * - **算完立刻写值，随后自动点一次宿主的「保存」**（2026-10-17 用户要求「配置并保存」）：
 *   保存是宿主自己的按钮、走它自己的校验与提交，我们只是替用户按一下
 *   （`element.click()`，轮询到 `disable` 放开再点，1.5 秒不放开就放弃）；
 * - 取数失败**不写值也不点保存**，页面顶部轻提示「取数失败」—— 绝不写半截或 0。
 */
class AiStareQuickConfigFeature : Feature {

    override val id = "aistare_quick_config"
    override val title = "智能盯盘一键配置"

    override fun isEnabled(cfg: ConfigSnapshot) = cfg.aistareQuickEnabled

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 取数 / 计算线程。
     *
     * **必须是持久的一个**（旧实现写成 `get() = newSingleThreadExecutor{…}`，
     * 每次取用都新建一个线程池且从不 shutdown：一是线程堆积，二是「串行」的
     * 本意完全失效，连点两下「配置并保存」就是两条线程各打一遍日 K 接口）。
     */
    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "gsaux-quickcfg").apply { isDaemon = true } }
    }

    /**
     * 取数编排（通道顺序 / 降级 / 缓存都在里面）。
     *
     * 懒初始化是为了拿到 [install] 里才建好的 [hostSource]；实例是**持久**的，
     * 腾讯与东财的日 K 缓存才能跨点击生效。
     */
    private val fetcher: QuoteFetcher by lazy { QuoteFetcher(hostSource) }

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
                                // 桥必须在**页面开始加载之前**挂上（4-项实测）：
                                // `addJavascriptInterface` 对「已经加载完的文档」不生效 ——
                                // 在 onPageFinished 里补挂，页面里 `typeof window.gsaux`
                                // 仍是 undefined，点按钮只会提示「暂时不可用」。
                                // HybirdWebView 在构造函数里就调 setWebViewClient，
                                // 这是整条链路上最早能拿到 WebView 实例的时刻。
                                (chain.thisObject as? WebView)?.let { wv ->
                                    runCatching {
                                        wv.addJavascriptInterface(
                                            Bridge(env),
                                            AiStareQuickConfigTargets.JS_BRIDGE_NAME,
                                        )
                                    }.onFailure { GLog.w("一键配置：构造期挂桥失败", it) }
                                }
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
        // 只认价格页（`setStrategy/1`）：涨跌幅 / 换手率等页签还没适配取数，不注入
        if (!AiStareQuickConfigTargets.isPricePage(url)) return

        currentWebView = WeakReference(webView)
        mainHandler.post { injectInto(env, webView, url) }
    }

    private fun injectInto(env: FeatureEnv, webView: WebView, url: String) {
        runCatching {
            val bridge = Bridge(env)
            webView.addJavascriptInterface(bridge, AiStareQuickConfigTargets.JS_BRIDGE_NAME)
            webView.evaluateJavascript(injectedJs, null)
            val sj = schemesJson(env)
            GLog.i("一键配置：注入方案 ${sj.take(160)}（开关=${env.config.aistareQuickEnabled}）")
            webView.evaluateJavascript(
                // 必须过 safeJson：initCall 拼的是 `init("...", true)`，
                // 直接把原始 JSON 塞进去会变成数组字面量 —— JS 收到的是对象，
                // `String()` 出来是 `[object Object]`（15 个字符），JSON.parse 必然失败，
                // 方案列表恒为空（3.1.0 起就带着这个 bug，2026-10-17 实测抓到）。
                AiStareJs.initCall(
                    AiStareJs.safeJson(sj),
                    true,
                    env.config.aiStarePickScheme,
                    env.config.aiStareSkipSaveConfirm,
                ),
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
                // `def`：JS 端在「不弹选择层」时要直接套默认方案
                .append("\",\"def\":").append(s.id == data.defaultId)
                .append("}")
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
                    AiStareJs.initCall(
                        AiStareJs.safeJson(schemesJson(env)),
                        isEnabled(cfg),
                        cfg.aiStarePickScheme,
                        cfg.aiStareSkipSaveConfirm,
                    ),
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

        /**
         * JS → 日志（排查用）。H5 里没有 console 可看，点一次按钮到底走到哪一步
         * 只能靠这条通道打回 LSPosed 日志。
         */
        @JavascriptInterface
        fun diag(msg: String) {
            GLog.i("一键配置JS：$msg")
        }

        /** JS 要一条提示 —— 走**安卓原生 Toast**（页面关了它还在） */
        @JavascriptInterface
        fun toast(msg: String) {
            nativeToast(msg)
        }

        /**
         * JS 调 `gsaux.apply("s1")`：套用方案。
         *
         * **整个流程都丢到主线程**：这个回调本身跑在 JavaBridge 线程上，而
         * `WebView.getUrl()` / `evaluateJavascript()` 都是主线程 API —— 在桥上直接取
         * `wv.url` 拿到的是空串（8-项实测，被 URL 守卫静默挡掉，表现就是「点了没反应」）。
         */
        @JavascriptInterface
        fun apply(schemeId: String) {
            val cfg = env.config
            GLog.i("一键配置：apply $schemeId（开关=${cfg.aistareQuickEnabled}）")
            if (!cfg.aistareQuickEnabled) return
            mainHandler.post {
                runCatching {
                    val wv = currentWebView?.get()
                    if (wv == null) { GLog.w("一键配置：apply 时没有 currentWebView"); return@runCatching }
                    // 桥现在挂在每个 HybirdWebView 上（必须赶在页面加载前挂），别的 H5
                    // 也看得见它：当前不是价格页就沉默返回，不要在别的页面弹「取数失败」。
                    val curUrl = wv.url.orEmpty()
                    if (!AiStareQuickConfigTargets.isPricePage(curUrl)) {
                        GLog.w("一键配置：apply 时 URL 不是价格页（$curUrl）")
                        return@runCatching
                    }
                    val scheme = QuickConfigStore.decode(cfg.aiStareQuickConfig)
                        .schemes.firstOrNull { it.id == schemeId }
                    if (scheme == null) {
                        GLog.w("一键配置：apply 找不到方案 $schemeId")
                        return@runCatching
                    }
                    val parsed = parseStockCode(curUrl)
                    if (parsed == null) {
                        GLog.w("一键配置：URL 里解不出 stockCode（$curUrl）")
                        nativeToast("取数失败")
                        return@runCatching
                    }
                    GLog.i("一键配置：开始取数 ${parsed.first}.${parsed.second}")

                    // 先抓页面全文（④ DOM 通道的原料；必须在主线程调 WebView）。
                    //
                    // `evaluateJavascript` 的回调**不保证一定回来**（页面刚被销毁、
                    // 渲染进程没起来时它就一直不回）。旧实现只靠这个回调推进，于是
                    // 卡住时表现为「点了按钮什么都不发生」—— 既没有取数也没有提示，
                    // 用户只能再点一次。现在加一道 1.5 秒兜底：到点还没开跑就按
                    // 「没有页面文本」继续，公开行情通道照样能给数。
                    val kicked = AtomicBoolean(false)
                    fun kick(text: String) {
                        if (!kicked.compareAndSet(false, true)) return
                        worker.execute { applyOnWorker(wv, scheme, parsed.first, parsed.second, text) }
                    }
                    mainHandler.postDelayed(
                        {
                            if (!kicked.get()) GLog.w("一键配置：抓取页面文本超时（${PAGE_TEXT_TIMEOUT_MS}ms），按无 DOM 文本继续")
                            kick("")
                        },
                        PAGE_TEXT_TIMEOUT_MS,
                    )
                    runCatching {
                        wv.evaluateJavascript("(document.body ? (document.body.innerText || '') : '')") { raw ->
                            val pageText = runCatching {
                                JSONTokener(raw).nextValue() as? String
                            }.getOrNull().orEmpty()
                            kick(pageText)
                        }
                    }.onFailure {
                        GLog.w("一键配置：抓取页面文本失败", it)
                        kick("")
                    }
                }.onFailure { GLog.w("一键配置：apply 流程异常", it) }
            }
        }

        /**
         * JS 调 `gsaux.saved()`：保存成功且「无需确定保存」打开时，
         * 替用户把页面收掉（宿主那个「保存成功！」弹窗已经被 JS 藏了）。
         *
         * 退几层由 [ConfigSnapshot.aiStareSaveBackToStock] 决定：
         *  - `false`：只关当前这一页（价格页）→ 回到上一页（策略列表）；
         *  - `true` ：继续把叠在上面的「智能盯盘」系列页面逐个收掉，直到个股详情页。
         */
        @JavascriptInterface
        fun saved() {
            val backToStock = env.config.aiStareSaveBackToStock
            GLog.i("一键配置：保存完成，收页面（回个股详情页=$backToStock）")
            mainHandler.post {
                runCatching {
                    // 提示先弹：原生 Toast 是系统浮层，页面立刻关掉也不影响它
                    nativeToast("保存成功")
                    if (!backToStock) {
                        topActivity()?.finish()
                        return@runCatching
                    }
                    val pages = aistareActivities()
                    if (pages.isEmpty()) { topActivity()?.finish(); return@runCatching }

                    // 用户是从「个股详情页底栏 → 智能盯盘」进来的：详情页就在栈底，收掉上面
                    // 这族页面它自己会露出来。不是从详情页进来的（首页 → 智能盯盘 → 创建 →
                    // 价格），栈里压根没有详情页 —— 收完会掉到首页，所以要**自己拉起一个**。
                    val hasDetail = allActivities().any { it.javaClass.name == STOCK_FRAG_CLASS }
                    val parsed = parseStockCode(currentWebView?.get()?.url.orEmpty())
                    if (!hasDetail && parsed != null) {
                        // 先拉起详情页、再收页面：反过来做会让用户先看见首页闪一下
                        openStockDetail(env, parsed.first, parsed.second)
                        mainHandler.postDelayed({ pages.forEach { it.finish() } }, DETAIL_FINISH_DELAY_MS)
                    } else {
                        // **一次性**把智能盯盘这一族页面全收掉：逐个 finish（每层等 450ms）
                        // 会让用户看见「先回智能盯盘、再回详情页」两跳
                        pages.forEach { it.finish() }
                    }
                }.onFailure { GLog.w("一键配置：保存后返回失败", it) }
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
        wv: WebView,
        scheme: QuickConfigStore.Scheme,
        code: String,
        market: String,
        pageText: String,
    ) {
        // 取数 / 降级 / 按需打接口都在 QuoteFetcher 里；这里只管「拿到结果就回填，
        // 拿不到就提示」，不写半截值（§3.2）
        val outcome = fetcher.fetch(scheme, code, market, pageText)

        mainHandler.post {
            runCatching {
                if (outcome == null) {
                    // 整体放弃：不写值，弹一条原生 Toast（§3.2「取数失败不写值」）
                    nativeToast("取数失败")
                    return@post
                }
                wv.evaluateJavascript(AiStareJs.fillCall(toPlain(outcome.up), toPlain(outcome.down)), null)
                GLog.i(
                    "一键配置：已套用「${scheme.name}」（${outcome.source}）" +
                        "涨=${outcome.up} 跌=${outcome.down}",
                )
            }.onFailure { GLog.w("一键配置：回填失败", it) }
        }
    }

    /**
     * 原生 Toast（系统浮层，页面关掉后仍在屏上）。
     *
     * Context 优先取 **WebView 自己的**（它就是宿主 Activity 的 context，能保证
     * Toast 在这个应用名下显示）；拿不到再退回 `ActivityThread.currentApplication()`。
     * 注意页面已经 finish 时 Activity 失效 —— 所以要 unwrap 到 Application。
     */
    private fun nativeToast(text: String) {
        mainHandler.post {
            runCatching {
                val wvCtx = currentWebView?.get()?.context
                val ctx = wvCtx?.applicationContext ?: hostApplicationContext()
                if (ctx == null) { GLog.w("一键配置：Toast 拿不到 Context，跳过"); return@runCatching }
                android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()
                GLog.i("一键配置：已弹原生 Toast「$text」")
            }.onFailure { GLog.w("一键配置：Toast 失败", it) }
        }
    }

    /** 当前栈里的**全部** Activity（用来判断「详情页在不在栈里」） */
    private fun allActivities(): List<android.app.Activity> = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
        val f = at.getDeclaredField("mActivities").apply { isAccessible = true }
        val map = f.get(thread) as? Map<*, *> ?: return@runCatching emptyList()
        map.values.mapNotNull { rec ->
            if (rec == null) return@mapNotNull null
            runCatching {
                rec.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                    .get(rec) as? android.app.Activity
            }.getOrNull()?.takeIf { !it.isFinishing }
        }
    }.getOrDefault(emptyList())

    /**
     * 拉起个股详情页（`StockFragAct`）。
     *
     * 契约来自 `docs/FEATURE-DIRECT-QUOTE.md` 路径 C（本仓 strategyquote 已经在用）：
     * `action=quotes.stockDetail` + `adapter_key` Bundle 里放**单只** `stockInfo`
     * （`com.guosen.android.quotes.data.IntentStockInfo`，它自带 `(String code, String market)` 构造器）。
     * 少了这个 extras，详情页会直接崩（实测 `am start` 不带它 → 异常退出）。
     */
    private fun openStockDetail(env: FeatureEnv, code: String, market: String): Boolean = runCatching {
        val cls = Reflect.loadClass(CLS_INTENT_STOCK_INFO, env.classLoader)
            ?: run { GLog.w("一键配置：找不到 $CLS_INTENT_STOCK_INFO，无法拉起详情页"); return@runCatching false }
        val ctor = cls.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        } ?: run { GLog.w("一键配置：IntentStockInfo 没有 (code, market) 构造器"); return@runCatching false }
        ctor.isAccessible = true
        val item = ctor.newInstance(code, market) as? android.os.Parcelable
            ?: run { GLog.w("一键配置：IntentStockInfo 不是 Parcelable"); return@runCatching false }

        val bundle = android.os.Bundle().apply {
            putParcelable(KEY_STOCK_INFO, item)
        }
        val ctx = currentWebView?.get()?.context?.applicationContext ?: hostApplicationContext()
            ?: run { GLog.w("一键配置：拿不到 Context，拉不起详情页"); return@runCatching false }
        val intent = Intent(ACTION_STOCK_DETAIL)
            .setClassName(TargetApp.PACKAGE, STOCK_FRAG_CLASS)
            .putExtra(KEY_ADAPTER, bundle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        GLog.i("一键配置：已拉起个股详情页（$code.$market）")
        true
    }.getOrDefault(false)

    /** 当前栈里所有「智能盯盘」页（价格页 / 策略列表 / 盯盘首页 …） */
    private fun aistareActivities(): List<android.app.Activity> = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
        val f = at.getDeclaredField("mActivities").apply { isAccessible = true }
        val map = f.get(thread) as? Map<*, *> ?: return@runCatching emptyList()
        map.values.mapNotNull { rec ->
            if (rec == null) return@mapNotNull null
            val act = runCatching {
                rec.javaClass.getDeclaredField("activity").apply { isAccessible = true }
                    .get(rec) as? android.app.Activity
            }.getOrNull()
            act?.takeIf { !it.isFinishing && it.javaClass.name.startsWith(AISTARE_PACKAGE) }
        }
    }.getOrDefault(emptyList())

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

    /**
     * 当前处于前台的 Activity（反射 `ActivityThread.mActivities`）。
     *
     * 为什么不用 `webView.context`：它只给到当前这个 WebView 所在的那一个页面，
     * 「退到个股详情页」要连续收掉好几个页面，必须能拿到实时的栈顶。
     */
    private fun topActivity(): android.app.Activity? = runCatching {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
        val f = at.getDeclaredField("mActivities").apply { isAccessible = true }
        val map = f.get(thread) as? Map<*, *> ?: return@runCatching null
        map.values.asSequence().mapNotNull { rec ->
            if (rec == null) return@mapNotNull null
            val cls = rec.javaClass
            val paused = runCatching {
                cls.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(rec)
            }.getOrDefault(true)
            val act = runCatching {
                cls.getDeclaredField("activity").apply { isAccessible = true }.get(rec) as? android.app.Activity
            }.getOrNull()
            if (!paused && act != null && !act.isFinishing) act else null
        }.lastOrNull()
    }.getOrNull()

    /** 个股详情页 */
    private val STOCK_FRAG_CLASS = "com.guosen.quotation.ui.StockFragAct"

    /** 详情页契约（见 openStockDetail 的注释） */
    private val CLS_INTENT_STOCK_INFO = "com.guosen.android.quotes.data.IntentStockInfo"
    private val ACTION_STOCK_DETAIL = "quotes.stockDetail"
    private val KEY_ADAPTER = "adapter_key"
    private val KEY_STOCK_INFO = "stockInfo"

    /** 拉起详情页后再等多久收掉旧页面（让详情页先画出来，避免首页闪一下） */
    private val DETAIL_FINISH_DELAY_MS = 400L

    /** 智能盯盘那一族页面的类名前缀（`saved()` 只收这些） */
    private val AISTARE_PACKAGE = "com.guosen.quotation.aistare"

    /**
     * 抓页面文本的兜底等待：超过这个时间还没回调就直接开跑。
     * 取数本身有 5~8 秒的超时，这里不能等太久，否则用户体感就是「按钮没反应」。
     */
    private val PAGE_TEXT_TIMEOUT_MS = 1500L

    /** BigDecimal → JS（两位小数字符串，不科学计数） */
    private fun toPlain(v: BigDecimal): String = v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
}
