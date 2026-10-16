package com.wb.probe;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * GSProbe v33 —— 页面 / 点击链 / Intent 契约 / 明文 HTTP / WebView 桥侦察（现代 libxposed API 102 版）
 *
 * 历史:
 *   v18  自选判定 API 交叉验证（ZXManager dump + 20 只真值打分）
 *   v19  新增「策略详情页 → 个股详情」跳转链侦察，服务新功能
 *        「策略页点击股票直达标准行情 + 左右滑动」。
 *   v26  新增 OkHttp 抓包（`okhttp3.OkHttpClient.newCall` / `ResponseBody.string()`），
 *        用于判定「策略详情页个股 100 上限」是客户端还是服务端截断。
 *        —— 实测对金太阳**全程 0 命中**（它不用 OkHttp）。
 *   v27  改为**两级网络钩子**：OkHttp 之外，再挂裸 `java.net.Socket` 的
 *        `getInputStream/getOutputStream`，对返回的实现类沿继承链动态挂
 *        `read/write(byte[],int,int)` —— 金太阳走自研 HTTP + Conscrypt TLS，
 *        只有挂到 `ConscryptEngineSocket$SSLOutputStream` 那一层才拿得到明文。
 *        用「时间窗 + 只抓 HTTP 方法开头 / gzip·JSON 响应 + 总量上限」控制日志量。
 *        这一组钩子与业务无关，**换任何 App 都能用**。
 *   v30  登录态侦察（ParseLoginDataProcess 的 checkLogout / showLogoutAlert）。
 *   v31  本地存储写入侦察（SharedPreferencesImpl$EditorImpl 的 clear / remove）。
 *   v33  **WebView 桥侦察**（服务「智能盯盘 → 价格页一键配置」方案
 *        docs/FEATURE-AISTARE-QUICK-CONFIG.md §10 的 7 项待确认）：
 *        - dump HybirdWebView / NestedHybridWebView / AIStareWebView / GsBridge
 *          类成员 —— 回答「怎么从容器拿到真正的 android.webkit.WebView」；
 *        - 钩 WebView#setWebViewClient 抓**实际子类**，dump 其覆写方法并动态挂
 *          onPageStarted / onPageFinished —— 回答「onPageFinished 挂哪个子类」；
 *        - 钩 WebView#addJavascriptInterface / removeJavascriptInterface ——
 *          回答「宿主是否注册/清理 JS 桥」；
 *        - 钩 GsBridge#parserCommand / HybirdWebView#parserCommand /
 *          AIStareWebView#parserMsg，打印命令原文 —— 回答「保存动作发什么命令、
 *          自建命令要在哪一级截断」；
 *        - 钩 WebView#loadUrl —— 回答「价格页是否整页导航」。
 *
 * v19 新增钩子（全部是**框架类**，不需要预先知道任何业务类名）:
 *   1. Activity.startActivity / startActivityForResult
 *        → 抓每一次页面跳转的 Intent：component / action / flags / **全部 extras（key + 类型 + 值）**
 *          + 发起页面类名 + 精简调用栈（只看 com.guosen 帧）。
 *        用途: 对比「行情页 → StockFragAct」与「策略页 →（标准行情）→ StockFragAct」
 *              两次 Intent 的 extras，找出承载「翻页股票列表」的那个 key。
 *   2. Dialog.show / PopupWindow.showAsDropDown / showAtLocation
 *        → 判断策略页点股票后弹出的中间 UI 是什么形态（Dialog？PopupWindow？）。
 *   3. Activity.onCreate / onNewIntent（筛 StockFragAct 与 AIStkStgDetailAct）
 *        → dump 该页 Intent + 页内 ViewPager / ViewPager2 / RecyclerView 的
 *          adapter 类名、item 数、当前页，以及全部可点击控件及其监听器类名。
 *        用途: 找到「拦截点击」的挂钩点，以及「喂列表」的目标控件。
 *
 * 阅读方法（真机跑完看 LSPosed 日志）:
 *   ACTIVITY[...]   页面链路（onResume 顺序）
 *   LAUNCH[...]     一次页面跳转，紧跟一段 extras
 *   DLG[...]        中间 UI 是 Dialog
 *   POP[...]        中间 UI 是 PopupWindow
 *   PAGE[...]       目标页的 Intent + 实例字段
 *   PAGER / RV[...] 个股详情页里的翻页控件
 *   VW[...]         可点击控件 + 监听器类名（找点击钩子用）
 *   CLASS[...]      任意类的构造器 / 字段 / 方法签名（找「自带转换构造器」用）
 *   HTTP-REQ[...]   OkHttp 出站请求（金太阳 0 命中）
 *   NET-W / NET-R   裸 socket 明文写出 / 读入（配 TLS[...] 看握手）
 *   WVB-CLIENT      setWebViewClient 挂上的实际 WebViewClient 子类（+ 全部声明方法）
 *   WVB-CB          WebViewClient 子类的页面回调实际触发（url 一起打）
 *   WVB-LOADURL     WebView.loadUrl 调用（整页导航判定）
 *   WVB-JSIFACE     addJavascriptInterface / removeJavascriptInterface
 *   WVB-CMD         H5 命令漏斗原文（GsBridge / HybirdWebView / AIStareWebView）
 *
 * 注意: 本类**不引用任何 legacy `de.robv.android.xposed` 类型**，也不引用 androidx 类型
 *      （探针不走 Gradle，classpath 里没有 androidx，一律按类名反射）。
 */
public class ProbeModule extends XposedModule {

    /** TCP 抓包窗口长度：够覆盖「进页面 → 请求策略个股 → 回包」 */
    private static final long NET_CAPTURE_MS = 600000L;

    /** TCP 抓包总量上限，避免行情推送把日志刷爆 */
    private static final int NET_MAX_CHUNKS = 1500;

    /** 单次 dump 的最大字节数 */
    private static final int NET_MAX_BYTES = 4096;

    /** Conscrypt 的 SSLSocket 实现类（覆写了 `Socket#getOutputStream`，必须单独挂） */
    private static final String[] TLS_SOCKET_CLASSES = {
            "com.android.org.conscrypt.ConscryptEngineSocket",
            "com.android.org.conscrypt.ConscryptFileDescriptorSocket",
            "com.android.org.conscrypt.OpenSSLSocketImpl",
    };

    private static final String TAG = "GSRecon";

    /** 策略详情页（点击来源） */
    private static final String CLS_STG_DETAIL = "com.guosen.quotation.ui.aistk.AIStkStgDetailAct";

    /** 个股详情页（跳转目标，「标准行情」） */
    private static final String CLS_STOCK_QUOTE = "com.guosen.quotation.ui.StockFragAct";

    /** 宿主 classLoader，来自 PackageReadyParam */
    private ClassLoader sCL;

    /** ZXManager 单例缓存 */
    private Object sMgr;

    /** 页面跳转日志去重（同一个 Intent 会被多层 startActivity 各拦一次） */
    private volatile String sLastLaunchKey;
    private volatile long sLastLaunchAt;

    /** 行情页真值样本: cam=code.market */
    private static final String[] HQ_CAM = {
            "300338.1", "300299.1", "300006.1", "301132.1", "920575.5",
            "301052.1", "301010.1", "688018.2", "688685.2", "300516.1",
            "301036.1", "600252.2", "002343.1", "002696.1", "605189.2",
            "600097.2", "603968.2", "600408.2", "000668.1", "600202.2"
    };
    /** 对应的 StockInfo.isZx 真值 */
    private static final boolean[] HQ_TRUTH = {
            false, false, true, false, false,
            false, false, false, false, true,
            false, false, false, false, false,
            false, false, false, false, false
    };

    /**
     * 统一日志出口。走框架的 `log()`，与旧版 `android.util.Log` 落点一致
     * （LSPosed 会把模块日志收进 `/data/adb/lspd/log/modules_*.log`，不进 logcat）。
     */
    private void log(String s) {
        try {
            log(Log.ERROR, TAG, s);
        } catch (Throwable ignored) {
            // 极端情况下框架接口不可用，退回到 logcat
            Log.e(TAG, s);
        }
    }

    // ============================================================ 生命周期

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        String process = param.getProcessName();
        if (process == null || !process.startsWith("com.guosen.android")) {
            // 不在作用域内：告诉框架本入口不再需要后续回调
            detach();
            return;
        }
        log("=== GSProbe v33 (libxposed API 102) 载入 | process=" + process
                + " pid=" + Process.myPid()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " api=" + getApiVersion() + " ===");
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!"com.guosen.android".equals(param.getPackageName())) return;
        sCL = param.getClassLoader();
        hookActivity();
        hookActivityLaunch();
        hookMiddleUi();
        hookPageCreate();
        hookHttp();
        hookSocketIo();
        hookLoginState();
        hookPrefsWrites();
        // 构造个股详情 Intent 需要自己 new 出这些数据类，先把可用构造器/字段摸清
        dumpClassMembers("com.guosen.android.quotes.data.IntentStockInfo");
        // 页面跳转全部走引擎路由，签名必须实测（加固 App 猜不得）
        dumpClassMembers("com.guosen.engine.process.EngineAct");
        dumpClassMembers("com.guosen.engine.process.EngineFrag");
        dumpClassMembers("com.guosen.engine.process.EngineIntent");
        // 策略详情页的列表适配器：要「真正移除被筛掉的股票」必须知道它的数据 API
        dumpClassMembers("com.guosen.commonui.adapter.BaseQuickAdapter");
        dumpClassMembers("com.guosen.quotation.adapter.aistk.AIStkAdapter");
        // 列表数据项：判断「涨幅 / 跌幅」要读它的涨跌幅字段，字段名不能猜
        dumpClassMembers("com.guosen.android.quotes.data.StockInfo");
        // 通用弹窗：登出提示 / 各类提醒都走它，签名是写反净化钩子的前提
        dumpClassMembers("com.guosen.commonui.dialog.CustomAlertDialog");
        // 登录数据解析：被顶 / 登出提示 / 系统公告都从这一条链出来
        dumpClassMembers("com.guosen.app.goldsun.login.ParseLoginDataProcess");
        // 大类的关键入口签名（整类 dump 太吵，只挑要用的）
        dumpNamedMethods("com.guosen.app.goldsun.ui.MainAct",
                "showSgbDialog", "showMsgDialog", "startParseLoginData", "onSessionLoginCompleted");
        dumpNamedMethods("com.guosen.commonui.dialog.PopAlert",
                "createIpoAlertDialog", "showSingle", "createDialog");
        // 登录状态载体：判定「要不要登出」十有八九读的是它
        dumpClassMembers("com.guosen.app.goldsun.login.WeakPwdLoginStatus");
        // 账号管理 / 登录工作流：被顶后「清凭证」「标未登录」的发起方，
        // 找登录态到底存在哪个字段上就看它们
        dumpClassMembers("com.guosen.android.login.UserAccountManager");
        dumpClassMembers("com.guosen.app.goldsun.login.LoginWorkFlowManager");
        // ---- v33: WebView 桥侦察（H5 页面注入方案的挂点与取数确认）----
        hookWebViewBridge();
        // 价格页容器链：HybirdWebView → NestedHybridWebView → 真正的 WebView，
        // 「拿 WebView 实例的方法名」就看这三个类的字段与 getter
        dumpClassMembers("com.guosen.commonui.webview.HybirdWebView");
        dumpClassMembers("com.guosen.commonui.webview.NestedHybridWebView");
        dumpClassMembers("com.guosen.quotation.aistare.ui.AIStareWebView");
        // H5 命令漏斗的最后一跳（showCommandError 在这里，自建命令要在它之前截）
        dumpClassMembers("com.guosen.module.hybrid.GsBridge");
        // 日 K 图组：HostQuoteSource 的日K数据候选源
        dumpClassMembers("com.guosen.quotation.logicview.KXChartGroupView");
        // 日 K 数据本体（v34）：KXChartGroupView.m_kxChartInfo 的类型，K 线数组应该挂在这
        dumpClassMembers("com.guosen.android.quotes.data.kx.KXChartInfo");
        // 单根 K 线（m_kxChartItems 的元素类型）：开高低收字段名就看它（v35）
        dumpClassMembers("com.guosen.android.quotes.data.kx.KXChartItem");
        // StockInfo.parseFund(TCRSDataObject,...) 的参数类型：实时行情推送的数据总线（v34）
        dumpClassMembers("com.guosen.android.quotes.data.TCRSDataObject");
        // 持仓成本候选（类名盲试，失败无害）
        dumpClassMembers("com.guosen.android.quotes.data.PositionInfo");
        dumpClassMembers("com.guosen.android.quotes.data.HoldStockInfo");
    }

    /** dump 某类的构造器 / 实例字段 / setter-getter（加固 App 里字段名不可假设，必须实测） */
    private void dumpClassMembers(String name) {
        try {
            Class<?> c = Class.forName(name, false, sCL);
            log("");
            log("========== CLASS " + name + " ==========");
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                log("  CTOR (" + sig(k.getParameterTypes()) + ")");
            }
            log("  -- fields --");
            Class<?> t = c;
            while (t != null && t != Object.class) {
                for (Field f : t.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    // com.guosen 自有类型打完整包名（找 KXChartInfo 这类「字段可见但包名未知」的类）
                    String tn = f.getType().getName();
                    log("  F " + (tn.startsWith("com.guosen") ? tn : f.getType().getSimpleName())
                            + " " + f.getName());
                }
                t = t.getSuperclass();
            }
            log("  -- setters / getters --");
            boolean all = c.getSimpleName().startsWith("Engine")
                    || c.getSimpleName().startsWith("ParseLoginDataProcess");
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName();
                if (all || n.startsWith("set") || n.startsWith("get") || n.startsWith("is")
                        || n.equals("valueOf") || n.contains("parse") || n.contains("convert")) {
                    log("  M " + Modifier.toString(m.getModifiers()) + " "
                            + m.getReturnType().getSimpleName() + " " + n
                            + "(" + sig(m.getParameterTypes()) + ")");
                }
            }
        } catch (Throwable t) {
            log("dumpClassMembers(" + name + ") 失败: " + t);
        }
    }

    /**
     * 登录态侦察（v30）。
     *
     * 被顶之后重启，宿主走 `ParseLoginDataProcess` 那串检查时会判定「要登出」。
     * 这个钩子把判定现场拍下来 —— 尤其是 `mStatus`（登录结果状态）——
     * 用来回答「宿主**凭什么**认为会话失效了」，这是「顶号后保持登录」能不能
     * 真正做到「数据也能用」的关键。
     *
     * 两个点都是**只观察不拦**（proceed 照走），不影响宿主行为。
     */
    private void hookLoginState() {
        Class<?> cls;
        try {
            cls = Class.forName("com.guosen.app.goldsun.login.ParseLoginDataProcess", false, sCL);
        } catch (Throwable t) {
            log("hookLoginState: 找不到 ParseLoginDataProcess " + t);
            return;
        }
        int n = 0;
        n += hookNamed(cls, "checkLogout", "probe:loginstate", new After() {
            @Override public void run(Object self, Object[] args) {
                log("");
                log("========== LOGIN-STATE checkLogout 触发 ==========");
                dumpFields("ParseLoginDataProcess", self);
                dumpStatusField(self);
            }
        });
        n += hookNamed(cls, "showLogoutAlert", "probe:loginstate", new After() {
            @Override public void run(Object self, Object[] args) {
                log("");
                log("========== LOGIN-STATE showLogoutAlert ==========");
                if (args != null) {
                    for (Object a : args) log("  arg: " + summarize(a));
                }
                dumpFields("ParseLoginDataProcess", self);
                dumpStatusField(self);
            }
        });
        log("已挂登录态侦察点 ×" + n);
    }

    /** `mStatus` 是判定依据的载体，单独深挖一层 */
    private void dumpStatusField(Object self) {
        try {
            Class<?> c = self.getClass();
            Field f = null;
            while (c != null && c != Object.class) {
                try {
                    f = c.getDeclaredField("mStatus");
                    break;
                } catch (Throwable ignored) {}
                c = c.getSuperclass();
            }
            if (f == null) {
                log("  (没有 mStatus 字段)");
                return;
            }
            f.setAccessible(true);
            Object st = f.get(self);
            if (st == null) {
                log("  mStatus = null");
                return;
            }
            log("  mStatus 类型 = " + st.getClass().getName());
            dumpFields("mStatus", st);
        } catch (Throwable t) {
            log("  dumpStatusField 失败 " + t);
        }
    }

    /**
     * 本地存储写入侦察（v31）。
     *
     * 被顶之后重启，宿主会把登录相关信息从 SharedPreferences 里抹掉 ——
     * 实测 `/data/data/com.guosen.android/shared_prefs/USER_ACCOUNT_CONFIGS.xml`
     * 被清成空 `<map />`，时间戳正好是重启那一刻。这就是「界面留着、数据全空」
     * 的直接原因。
     *
     * 这里挂 `EditorImpl` 的 `clear` / `remove`，把调用栈打出来，定位是谁清的。
     * 只观察、不改行为。
     */
    private void hookPrefsWrites() {
        Class<?> editor;
        try {
            editor = Class.forName("android.app.SharedPreferencesImpl$EditorImpl", false, sCL);
        } catch (Throwable t) {
            log("hookPrefsWrites: 找不到 EditorImpl " + t);
            return;
        }
        int n = 0;
        for (final String name : new String[]{"clear", "remove"}) {
            n += hookNamed(editor, name, "probe:prefs", new After() {
                @Override public void run(Object self, Object[] args) {
                    log("");
                    log("========== PREFS." + name + " "
                            + (args != null && args.length > 0 ? summarize(args[0]) : "") + " ==========");
                    log("  文件: " + prefsFileOf(self));
                    log("  stack: " + stackHint());
                }
            });
        }
        log("已挂本地存储写入侦察点 ×" + n);
    }

    /** EditorImpl 是非静态内部类，`this$0` 就是它所属的 SharedPreferencesImpl */
    private String prefsFileOf(Object editor) {
        try {
            Field outer = editor.getClass().getDeclaredField("this$0");
            outer.setAccessible(true);
            Object impl = outer.get(editor);
            if (impl == null) return "(拿不到 owner)";
            Field mFile = impl.getClass().getDeclaredField("mFile");
            mFile.setAccessible(true);
            Object f = mFile.get(impl);
            return String.valueOf(f);
        } catch (Throwable t) {
            return "(读取失败 " + t + ")";
        }
    }

    // ============================================================ v33: WebView 桥侦察

    /** 已挂过回调的 WebViewClient 子类（防重复挂） */
    private final Set<String> hookedClients = new HashSet<>();
    /** 已记录过的 JS 桥接口名（防重复打日志） */
    private final Set<String> seenJsIfaces = new HashSet<>();
    /** 已记录过的 loadUrl 地址（同 URL 不重复打） */
    private final Set<String> seenLoadUrls = new HashSet<>();

    /**
     * v33 WebView 桥侦察 —— 服务「智能盯盘 → 价格页一键配置」方案
     * （docs/FEATURE-AISTARE-QUICK-CONFIG.md §10 的 7 项待真机确认）。
     *
     * ① setWebViewClient → 抓**实际子类**名 + dump 其声明方法 + 动态挂页面回调
     *    （回答：onPageFinished 挂哪个子类；钩基类拦不到子类覆写，必须先抓子类）
     * ② loadUrl → URL + 调用栈（回答：价格页是不是整页导航）
     * ③ addJavascriptInterface / removeJavascriptInterface → 桥名 + 栈
     *    （回答：宿主注册了哪些桥、有没有清理掉我们的机会）
     * ④ parserCommand / parserMsg → 命令原文（回答：保存发什么命令、自建命令在漏斗前怎么截）
     *
     * 全部只观察不拦，proceed 照走。
     */
    private void hookWebViewBridge() {
        int n = 0;
        try {
            Class<?> wv = Class.forName("android.webkit.WebView", false, sCL);

            // ① setWebViewClient：宿主一挂 client 就抓子类
            n += hookNamed(wv, "setWebViewClient", "probe:wvclient", new After() {
                @Override public void run(Object self, Object[] args) {
                    if (args == null || args.length == 0 || args[0] == null) return;
                    Class<?> cc = args[0].getClass();
                    log("");
                    log("========== WVB-CLIENT setWebViewClient -> " + cc.getName()
                            + " (extends " + cc.getSuperclass().getName() + ") ==========");
                    log("  stack: " + stackHint());
                    log("  -- 子类声明的方法 --");
                    for (Method m : cc.getDeclaredMethods()) {
                        log("  M " + Modifier.toString(m.getModifiers()) + " "
                                + m.getReturnType().getSimpleName() + " " + m.getName()
                                + "(" + sig(m.getParameterTypes()) + ")");
                    }
                    hookClientCallbacks(cc);
                }
            });

            // ② loadUrl：整页导航判定
            n += hookNamed(wv, "loadUrl", "probe:loadurl", new After() {
                @Override public void run(Object self, Object[] args) {
                    if (args == null || args.length == 0 || !(args[0] instanceof String)) return;
                    String url = (String) args[0];
                    if (url == null || url.length() == 0 || url.startsWith("about:")) return;
                    synchronized (seenLoadUrls) {
                        if (!seenLoadUrls.add(url)) return;
                    }
                    log("");
                    log("WVB-LOADURL " + cut(url, 400));
                    log("  stack: " + stackHint());
                }
            });

            // ③ JS 桥注册 / 清理
            n += hookNamed(wv, "addJavascriptInterface", "probe:jsiface", new After() {
                @Override public void run(Object self, Object[] args) {
                    if (args == null || args.length < 2 || args[1] == null) return;
                    String name = String.valueOf(args[1]);
                    synchronized (seenJsIfaces) {
                        if (!seenJsIfaces.add(name)) return;
                    }
                    log("");
                    log("WVB-JSIFACE add \"" + name + "\" obj="
                            + (args[0] == null ? "null" : args[0].getClass().getName()));
                    log("  stack: " + stackHint());
                }
            });
            n += hookNamed(wv, "removeJavascriptInterface", "probe:jsiface-rm", new After() {
                @Override public void run(Object self, Object[] args) {
                    if (args == null || args.length < 1) return;
                    log("");
                    log("WVB-JSIFACE remove \"" + args[0] + "\"");
                    log("  stack: " + stackHint());
                }
            });
        } catch (Throwable t) {
            log("hookWebViewBridge: WebView 钩子失败 " + t);
        }

        // ④ H5 命令漏斗：三个层级各挂一遍，看命令在哪一级、长什么样
        for (final String cn : new String[]{
                "com.guosen.module.hybrid.GsBridge",
                "com.guosen.commonui.webview.HybirdWebView",
                "com.guosen.quotation.aistare.ui.AIStareWebView"}) {
            try {
                Class<?> c = Class.forName(cn, false, sCL);
                for (final Method m : c.getDeclaredMethods()) {
                    final String mn = m.getName();
                    if (!mn.equals("parserCommand") && !mn.equals("parserMsg")) continue;
                    if (Modifier.isStatic(m.getModifiers())) {
                        log("  " + cn + "#" + mn + " 是静态方法（挂法无碍，继续）");
                    }
                    m.setAccessible(true);
                    hook(m)
                            .setId("probe:pcmd:" + cn + "#" + mn)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object r = chain.proceed();
                                    try {
                                        Object[] a = chain.getArgs().toArray();
                                        StringBuilder sb = new StringBuilder("WVB-CMD ")
                                                .append(cn).append('#').append(mn).append('(');
                                        for (int i = 0; i < a.length; i++) {
                                            if (i > 0) sb.append(", ");
                                            sb.append(cut(summarize(a[i]), 300));
                                        }
                                        log(sb.append(')').toString());
                                    } catch (Throwable ignored) {}
                                    return r;
                                }
                            });
                    n++;
                }
            } catch (Throwable t) {
                log("hookWebViewBridge: " + cn + " 失败 " + t);
            }
        }

        log("WebView 桥侦察点已挂 ×" + n);
    }

    /** 动态挂 WebViewClient 子类的页面回调（每个子类只挂一次） */
    private void hookClientCallbacks(Class<?> cc) {
        synchronized (hookedClients) {
            if (!hookedClients.add(cc.getName())) return;
        }
        String[] wanted = {"onPageStarted", "onPageFinished", "shouldOverrideUrlLoading",
                "doUpdateVisitedHistory", "onReceivedError"};
        int hooked = 0;
        for (final Method m : cc.getDeclaredMethods()) {
            boolean hit = false;
            for (String nm : wanted) if (m.getName().equals(nm)) { hit = true; break; }
            if (!hit || Modifier.isAbstract(m.getModifiers())) continue;
            try {
                m.setAccessible(true);
                final String mn = m.getName();
                hook(m)
                        .setId("probe:wvcb:" + cc.getName() + "#" + mn)
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object r = chain.proceed();
                                try {
                                    Object[] a = chain.getArgs().toArray();
                                    String url = "";
                                    for (Object x : a) {
                                        if (x instanceof String) { url = (String) x; break; }
                                    }
                                    log("WVB-CB " + cc.getSimpleName() + "#" + mn
                                            + (url.isEmpty() ? "" : " url=" + cut(url, 200)));
                                } catch (Throwable ignored) {}
                                return r;
                            }
                        });
                hooked++;
            } catch (Throwable t) {
                log("  挂 " + cc.getSimpleName() + "#" + m.getName() + " 失败 " + t);
            }
        }
        log("  (子类回调钩子 ×" + hooked + ")");
    }

    /** 只 dump 指定名字的方法签名（大类的某些方法用；整类全 dump 太吵） */
    private void dumpNamedMethods(String clsName, String... names) {
        try {
            Class<?> c = Class.forName(clsName, false, sCL);
            log("");
            log("========== METHODS " + clsName + " ==========");
            for (Method m : c.getDeclaredMethods()) {
                for (String n : names) {
                    if (!m.getName().equals(n)) continue;
                    log("  M " + Modifier.toString(m.getModifiers()) + " "
                            + m.getReturnType().getSimpleName() + " " + m.getName()
                            + "(" + sig(m.getParameterTypes()) + ")");
                }
            }
        } catch (Throwable t) {
            log("dumpNamedMethods(" + clsName + ") 失败: " + t);
        }
    }

    private static String sig(Class<?>[] ps) {        StringBuilder sb = new StringBuilder();
        for (Class<?> p : ps) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getSimpleName());
        }
        return sb.toString();
    }

    // ============================================================ 页面生命周期

    private void hookActivity() {
        try {
            Class<?> cls = Class.forName("android.app.Activity", false, sCL);
            Method onResume = cls.getDeclaredMethod("onResume");

            hook(onResume)
                    .setId("probe:Activity.onResume")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = chain.proceed();
                            try {
                                Object self = chain.getThisObject();
                                if (self == null) return result;
                                String cn = self.getClass().getName();
                                if (!cn.startsWith("com.guosen")) return result;
                                log("ACTIVITY > " + cn);
                                // MainAct(含行情页) 与 策略详情页 各自触发一次快照
                                if (cn.endsWith(".MainAct")) {
                                    scheduleSnap("MAIN", 6000);
                                    scheduleSnap("MAIN", 16000);
                                } else if (cn.endsWith(".AIStkStgDetailAct")) {
                                    scheduleSnap("STG", 2500);
                                }
                            } catch (Throwable t) {
                                log("ACT hook err " + t);
                            }
                            return result;
                        }
                    });

            log("已挂 android.app.Activity.onResume");
        } catch (Throwable t) {
            log("hookActivity 失败 " + t);
        }
    }

    private void scheduleSnap(final String tag, final long delay) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try { Thread.sleep(delay); } catch (Throwable ignored) {}
                try { snapshot(tag); } catch (Throwable t) { log("snap err " + t); }
            }
        }).start();
    }

    // ============================================================ v19: 页面跳转 / Intent 契约

    /**
     * 挂 Activity 的页面启动方法，抓 Intent 契约。
     *
     * `startActivityForResult(Intent,int,Bundle)` 是 Activity 所有启动路径的**唯一汇聚点**
     * （`startActivity(Intent)` 与 `startActivityForResult(Intent,int)` 最终都走到它），
     * 但为防某些子类覆写了短参版本，这里把三个都挂上，用时间窗口去重。
     */
    private void hookActivityLaunch() {
        Class<?> act;
        try {
            act = Class.forName("android.app.Activity", false, sCL);
        } catch (Throwable t) {
            log("hookActivityLaunch: 找不到 Activity " + t);
            return;
        }

        int n = 0;
        n += hookNamed(act, "startActivityForResult", "probe:launchFR", new After() {
            @Override public void run(Object self, Object[] args) {
                if (!(self instanceof Activity)) return;
                Intent it = firstIntent(args);
                logLaunch((Activity) self, it);
            }
        });
        n += hookNamed(act, "startActivity", "probe:launch", new After() {
            @Override public void run(Object self, Object[] args) {
                if (!(self instanceof Activity)) return;
                logLaunch((Activity) self, firstIntent(args));
            }
        });
        log("已挂 Activity 页面启动点 ×" + n + "（抓 Intent 契约）");
    }

    private void logLaunch(Activity self, Intent it) {
        if (it == null) return;
        String key = self.getClass().getName() + "|" + componentOf(it) + "|" + extrasKeyOf(it);
        long now = System.currentTimeMillis();
        if (key.equals(sLastLaunchKey) && now - sLastLaunchAt < 400) return;
        sLastLaunchKey = key;
        sLastLaunchAt = now;

        log("");
        log("========== LAUNCH ==========");
        log("来自页面: " + self.getClass().getName());
        dumpIntent("LAUNCH", it);
    }

    /** 中间 UI 形态侦察：Dialog 还是 PopupWindow */
    private void hookMiddleUi() {
        int n = 0;
        try {
            Class<?> dlg = Class.forName("android.app.Dialog", false, sCL);
            n += hookNamed(dlg, "show", "probe:dlg", new After() {
                @Override public void run(Object self, Object[] args) {
                    if (!(self instanceof Dialog)) return;
                    Dialog d = (Dialog) self;
                    log("");
                    log("DLG > " + d.getClass().getName() + "  (中间 UI 是 Dialog)");
                    log("  stack: " + stackHint());
                    try {
                        if (d.getWindow() != null) {
                            dumpClickableViews("DLG-VW", d.getWindow().getDecorView(), d.getContext());
                        }
                    } catch (Throwable t) { log("  DLG 视图 dump 失败 " + t); }
                }
            });
        } catch (Throwable t) {
            log("hookMiddleUi: Dialog 失败 " + t);
        }

        try {
            Class<?> pw = Class.forName("android.widget.PopupWindow", false, sCL);
            After pop = new After() {
                @Override public void run(Object self, Object[] args) {
                    if (!(self instanceof PopupWindow)) return;
                    PopupWindow p = (PopupWindow) self;
                    log("");
                    log("POP > " + p.getClass().getName() + "  (中间 UI 是 PopupWindow)");
                    log("  stack: " + stackHint());
                    try {
                        View c = p.getContentView();
                        if (c != null) dumpClickableViews("POP-VW", c, c.getContext());
                    } catch (Throwable t) { log("  POP 视图 dump 失败 " + t); }
                }
            };
            n += hookNamed(pw, "showAsDropDown", "probe:pop", pop);
            n += hookNamed(pw, "showAtLocation", "probe:pop", pop);
        } catch (Throwable t) {
            log("hookMiddleUi: PopupWindow 失败 " + t);
        }
        log("已挂中间 UI 展示点 ×" + n + "（判断 Dialog / PopupWindow）");
    }

    /** 目标页进入时 dump：自己的 Intent、页内翻页控件、可点击控件 */
    private void hookPageCreate() {
        Class<?> act;
        try {
            act = Class.forName("android.app.Activity", false, sCL);
        } catch (Throwable t) {
            log("hookPageCreate: 找不到 Activity " + t);
            return;
        }

        int n = 0;
        n += hookNamed(act, "onCreate", "probe:onCreate", new After() {
            @Override public void run(Object self, Object[] args) {
                if (self instanceof Activity) onPageShown((Activity) self, "onCreate");
            }
        });
        n += hookNamed(act, "onNewIntent", "probe:onNewIntent", new After() {
            @Override public void run(Object self, Object[] args) {
                if (self instanceof Activity) onPageShown((Activity) self, "onNewIntent");
            }
        });
        log("已挂目标页进入点 ×" + n);
    }

    private void onPageShown(final Activity act, String how) {
        String cn = act.getClass().getName();
        final boolean isQuote = cn.equals(CLS_STOCK_QUOTE);
        final boolean isStg = cn.equals(CLS_STG_DETAIL);
        if (!isQuote && !isStg) return;

        log("");
        log("========== PAGE[" + (isQuote ? "QUOTE" : "STG") + "] " + how + " ==========");
        try {
            dumpIntent(isQuote ? "QUOTE-INTENT" : "STG-INTENT", act.getIntent());
        } catch (Throwable t) {
            log("  getIntent 失败 " + t);
        }
        try {
            dumpFields(isQuote ? "StockFragAct 实例字段" : "AIStkStgDetailAct 实例字段", act);
        } catch (Throwable t) {
            log("  dumpFields 失败 " + t);
        }

        // 等界面渲染完再 dump 视图树
        if (isStg) {
            captureUntil = System.currentTimeMillis() + NET_CAPTURE_MS;
            netChunks.set(0);
            log("");
            log("== NET CAPTURE 开窗 " + (NET_CAPTURE_MS / 1000) + "s（策略详情页）==");
        }
        scheduleViewDump(act, isQuote ? "QUOTE" : "STG", 1200);
        scheduleViewDump(act, isQuote ? "QUOTE" : "STG", 3500);
    }

    private void scheduleViewDump(final Activity act, final String tag, final long delay) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try { Thread.sleep(delay); } catch (Throwable ignored) {}
                try {
                    log("");
                    log("---------- VIEW[" + tag + "] +" + delay + "ms ----------");
                    dumpPages(act, tag);
                    dumpClickableViews("VW", act.getWindow().getDecorView(), act);
                } catch (Throwable t) {
                    log("view dump err " + t);
                }
            }
        }).start();
    }

    /** 找页内的 ViewPager / ViewPager2 / RecyclerView，打印 adapter 与 item 数 */
    private void dumpPages(Activity act, String tag) {
        try {
            walkPages(act.getWindow().getDecorView(), act, 0, new int[]{0});
        } catch (Throwable t) {
            log("  walkPages 失败 " + t);
        }
    }

    private void walkPages(View v, Activity act, int depth, int[] budget) {
        if (v == null || depth > 40 || budget[0] > 40) return;
        String cn = v.getClass().getName();
        try {
            if (cn.contains("ViewPager")) {
                budget[0]++;
                Object ad = invoke0(v, "getAdapter");
                log("  PAGER " + cn + " #" + idName(act, v.getId())
                        + " adapter=" + (ad == null ? "null" : ad.getClass().getName())
                        + " count=" + countOf(ad) + " current=" + invoke0(v, "getCurrentItem"));
            } else if (cn.contains("RecyclerView")) {
                budget[0]++;
                Object ad = invoke0(v, "getAdapter");
                log("  RV    " + cn + " #" + idName(act, v.getId())
                        + " adapter=" + (ad == null ? "null" : ad.getClass().getName())
                        + " itemCount=" + countOf(ad));
            }
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkPages(g.getChildAt(i), act, depth + 1, budget);
        }
    }

    /** 打印有 id 或挂了点击监听的控件（找「拦截点击」的钩子点） */
    private void dumpClickableViews(String pre, View root, Object context) {
        if (root == null) return;
        dumpClickableViews(pre, root, context, 0, new int[]{0});
    }

    private void dumpClickableViews(String pre, View v, Object context, int depth, int[] budget) {
        if (v == null || depth > 40 || budget[0] > 500) return;
        try {
            String listener = clickListenerOf(v);
            int id = v.getId();
            String idn = (id == View.NO_ID) ? null : idName(context, id);
            if (listener != null || idn != null) {
                budget[0]++;
                StringBuilder sb = new StringBuilder("  ").append(pre).append(" ");
                for (int i = 0; i < depth; i++) sb.append('.');
                sb.append(v.getClass().getSimpleName());
                if (idn != null) sb.append(" #").append(idn);
                sb.append(v.isClickable() ? " [clickable]" : "");
                sb.append(" listener=").append(listener == null ? "-" : listener);
                if (v instanceof TextView) {
                    CharSequence t = ((TextView) v).getText();
                    if (t != null && t.length() > 0) sb.append(" text=\"").append(cut(t.toString(), 24)).append('"');
                }
                log(sb.toString());
            }
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpClickableViews(pre, g.getChildAt(i), context, depth + 1, budget);
            }
        }
    }

    // ============================================================ 主逻辑

    private void snapshot(String tag) {
        log("");
        log("========== SNAP[" + tag + "] ==========");

        Object mgr = zxManager();
        if (mgr == null) {
            log("  !! ZXManager 实例获取失败");
            return;
        }

        dumpFields("ZXManager 实例字段", mgr);
        dumpWatchlist(mgr);

        String defGrpId = str(getField(mgr, "mDefaultGrpId"));
        log("  mDefaultGrpId = " + defGrpId);

        dumpStaticArray("ZXWorkflow.getAllStock()", "getAllStock");
        dumpStockDataArray("ZXWorkflow.getZXDefaultGroupStocks()", "getZXDefaultGroupStocks", null);
        dumpGroupDatas();
        if (defGrpId != null && defGrpId.length() > 0) {
            dumpStockDataArray("ZXWorkflow.getStocksByGroupId(" + defGrpId + ")",
                    "getStocksByGroupId", new Object[]{defGrpId});
        }

        crossCheck(defGrpId);
    }

    private Object zxManager() {
        if (sMgr != null) return sMgr;
        try {
            Class<?> c = Class.forName("com.guosen.android.zxstock.ZXManager", false, sCL);
            Method m = c.getDeclaredMethod("instance");
            m.setAccessible(true);
            sMgr = m.invoke(null);
        } catch (Throwable t) {
            log("  ZXManager.instance() 失败 " + t);
        }
        return sMgr;
    }

    /** 直接读 m_zxMap / m_stkGrpMap / m_groupInfoDatas 等私有字段 */
    private void dumpWatchlist(Object mgr) {
        Object zxMap = getField(mgr, "m_zxMap");
        log("  --- m_zxMap (" + typeOf(zxMap) + ", size=" + sizeOf(zxMap) + ") ---");
        dumpMapEntries("    ZXMAP", zxMap, 200);

        Object stkGrpMap = getField(mgr, "m_stkGrpMap");
        log("  --- m_stkGrpMap (" + typeOf(stkGrpMap) + ", size=" + sizeOf(stkGrpMap) + ") ---");
        dumpMapEntries("    STKGRP", stkGrpMap, 60);

        Object grpInfos = getField(mgr, "m_groupInfoDatas");
        log("  --- m_groupInfoDatas (" + typeOf(grpInfos) + ", size=" + sizeOf(grpInfos) + ") ---");
        if (grpInfos instanceof Collection) {
            for (Object o : (Collection<?>) grpInfos) {
                log("    GRPINFO " + summarize(o));
            }
        }
    }

    private void dumpMapEntries(String pre, Object map, int max) {
        if (!(map instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) map;
        int i = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (i++ >= max) { log(pre + " ...(截断, 共" + m.size() + ")"); break; }
            log(pre + " key=" + e.getKey() + "  val=" + summarize(e.getValue()));
        }
    }

    private void dumpGroupDatas() {
        try {
            Class<?> c = Class.forName("com.guosen.android.zxstock.ZXWorkflow", false, sCL);
            Method m = c.getDeclaredMethod("getAllGroups");
            m.setAccessible(true);
            Object r = m.invoke(null);
            Object[] arr = toArray(r);
            log("  --- ZXWorkflow.getAllGroups() -> " + (arr == null ? "null" : arr.length + " 组") + " ---");
            if (arr != null) for (Object o : arr) log("    GROUP " + summarize(o));
        } catch (Throwable t) {
            log("  getAllGroups 失败 " + t);
        }
    }

    private void dumpStaticArray(String label, String method) {
        try {
            Class<?> c = Class.forName("com.guosen.android.zxstock.ZXWorkflow", false, sCL);
            Method m = c.getDeclaredMethod(method);
            m.setAccessible(true);
            Object r = m.invoke(null);
            Object[] arr = toArray(r);
            log("  --- " + label + " -> " + (arr == null ? "null" : arr.length + " 项") + " ---");
            if (arr != null) {
                StringBuilder sb = new StringBuilder("    ");
                for (int i = 0; i < arr.length; i++) {
                    if (i % 8 == 0 && i > 0) { log(sb.toString()); sb = new StringBuilder("    "); }
                    sb.append(arr[i]).append("  ");
                }
                if (sb.length() > 4) log(sb.toString());
            }
        } catch (Throwable t) {
            log("  " + label + " 失败 " + t);
        }
    }

    private void dumpStockDataArray(String label, String method, Object[] args) {
        try {
            Class<?> c = Class.forName("com.guosen.android.zxstock.ZXWorkflow", false, sCL);
            Method m;
            if (args == null) {
                m = c.getDeclaredMethod(method);
            } else {
                Class<?>[] pt = new Class<?>[args.length];
                for (int i = 0; i < args.length; i++) pt[i] = String.class;
                m = c.getDeclaredMethod(method, pt);
            }
            m.setAccessible(true);
            Object r = m.invoke(null, args);
            Object[] arr = toArray(r);
            log("  --- " + label + " -> " + (arr == null ? "null" : arr.length + " 只") + " ---");
            if (arr != null) for (Object o : arr) log("    ZX " + summarize(o));
        } catch (Throwable t) {
            log("  " + label + " 失败 " + t);
        }
    }

    // ============================================================ 交叉验证

    private void crossCheck(String defGrpId) {
        log("");
        log("  ===== 交叉验证: 行情页真值 vs 候选判定 =====");

        Set<String> allStock = new HashSet<String>();
        Set<String> defStocks = new HashSet<String>();
        try {
            Object[] a = toArray(callStatic("ZXWorkflow", "getAllStock"));
            if (a != null) for (Object o : a) if (o != null) allStock.add(String.valueOf(o));
            Object[] d = toArray(callStatic("ZXWorkflow", "getZXDefaultGroupStocks"));
            if (d != null) for (Object o : d) {
                String cam = stockDataCam(o);
                if (cam != null) defStocks.add(cam);
            }
        } catch (Throwable t) {
            log("    预备数据失败 " + t);
        }
        log("    getAllStock() = " + allStock.size() + " 项 -> " + allStock);
        log("    getZXDefaultGroupStocks() = " + defStocks.size() + " 项 -> " + defStocks);

        int okA = 0, okB = 0, okC = 0, okD = 0, okE = 0;
        log("    cam        truth | A:isBelong | B:anyGroup | C:grpDefault | D:inAll | E:inDef");
        for (int i = 0; i < HQ_CAM.length; i++) {
            String cam = HQ_CAM[i];
            int dot = cam.indexOf('.');
            String code = cam.substring(0, dot);
            String mkt = cam.substring(dot + 1);

            Boolean a = boolStatic2("ZXWorkflow", "isBelongByStock", code, mkt);
            Boolean b = boolInst2("ZXManager", "isBelongToAnyGroupByStock", code, mkt);
            Boolean c = boolInst3("ZXManager", "isBelongToGroupByStock", code, mkt,
                    defGrpId == null ? "" : defGrpId);
            boolean d = allStock.contains(cam) || allStock.contains(code);
            boolean e = defStocks.contains(cam) || defStocks.contains(code);

            boolean t = HQ_TRUTH[i];
            if (a != null && a == t) okA++;
            if (b != null && b == t) okB++;
            if (c != null && c == t) okC++;
            if (d == t) okD++;
            if (e == t) okE++;

            log("    " + cam + "  " + (t ? "T" : "f") + "    | "
                    + fmt(a) + "         | " + fmt(b) + "        | " + fmt(c)
                    + "          | " + (d ? "T" : "f") + "     | " + (e ? "T" : "f"));
        }
        log("    ------------------------------------------------------------");
        log("    命中: A=" + okA + "/20  B=" + okB + "/20  C=" + okC + "/20  D=" + okD + "/20  E=" + okE + "/20");
        log("  ===== 交叉验证结束 =====");
    }

    private static String fmt(Boolean b) {
        return b == null ? "ERR " : (b ? "T   " : "f   ");
    }

    private String stockDataCam(Object sd) {
        if (sd == null) return null;
        String v = str(getField(sd, "codeAndMarket"));
        if (v != null) return v;
        String code = str(getField(sd, "m_code"));
        String mkt = str(getField(sd, "m_market"));
        if (code != null && mkt != null) return code + "." + mkt;
        return null;
    }

    // ============================================================ HTTP 抓包（OkHttp）

    /**
     * 抓 OkHttp 的**出站请求**与**响应正文**。
     *
     * 挂 OkHttp 而不是业务类：`okhttp3.OkHttpClient.newCall(Request)` 与
     * `okhttp3.ResponseBody.string()` 都是库的公开方法，与加固/混淆的业务类名无关。
     *
     * 用途：判定「策略详情页个股 100 上限」到底是**客户端截断**还是**服务端截断** ——
     * 看请求里带不带 pageSize/limit 之类的分页参数、以及响应正文里到底有多少条。
     */
    private void hookHttp() {
        int n = 0;

        try {
            Class<?> client = Class.forName("okhttp3.OkHttpClient", false, sCL);
            n += hookNamed(client, "newCall", "probe:http-req", new After() {
                @Override public void run(Object self, Object[] args) {
                    if (args == null || args.length == 0) return;
                    logRequest(args[0]);
                }
            });
        } catch (Throwable t) {
            log("hookHttp: OkHttpClient 失败 " + t);
        }

        try {
            Class<?> rb = Class.forName("okhttp3.ResponseBody", false, sCL);
            n += hookNamedResult(rb, "string", "probe:http-resp", new ResultAfter() {
                @Override public void run(Object self, Object[] args, Object result) {
                    logResponseBody(result);
                }
            });
        } catch (Throwable t) {
            log("hookHttp: ResponseBody 失败 " + t);
        }

        log("已挂 HTTP 抓包点 ×" + n);
    }

    /** 与业务无关的噪声域名，直接跳过 */
    private static final String[] HTTP_NOISE = {
            "sensorsdata", "networkbench", "nbs.", "bugly", "umeng", "unitid",
            "doubleclick", "google-analytics", "crashsight", "growing", "mta.qq",
    };

    /** 只有「跟策略/AI 选股有关」的请求才值得打出来，否则日志会被行情轮询淹没 */
    private boolean interestingHttp(String s) {
        if (s == null || s.isEmpty()) return false;
        String l = s.toLowerCase();
        for (String k : HTTP_NOISE) if (l.contains(k)) return false;
        // v26 第一轮：不按关键词过滤，只排噪声域名 —— 先看清 App 到底走哪些 HTTP 接口
        return true;
    }

    /** 打印一个 OkHttp 请求：URL / method / body（body 可能为空） */
    private void logRequest(Object req) {
        if (req == null) return;
        try {
            String url = strFirst(invoke0(req, "url"));
            String method = strFirst(invoke0(req, "method"));
            String body = requestBody(req);
            String all = String.valueOf(url) + " " + body;
            if (!interestingHttp(all)) return;
            log("");
            log("== HTTP-REQ ==");
            log("  " + method + " " + url);
            if (body != null && !body.isEmpty()) {
                log("  BODY: " + cut(body, 4000));
            }
        } catch (Throwable t) {
            log("HTTP-REQ 异常 " + t);
        }
    }

    /** 把 RequestBody 写进一个 okio.Buffer 再取成字符串（只读，不改原体） */
    private String requestBody(Object req) {
        try {
            Object rb = invoke0(req, "body");
            if (rb == null) return null;
            Class<?> sink = Class.forName("okio.BufferedSink", false, sCL);
            Class<?> buf = Class.forName("okio.Buffer", false, sCL);
            Object buffer = buf.getDeclaredConstructor().newInstance();
            Method w = rb.getClass().getMethod("writeTo", sink);
            w.setAccessible(true);
            w.invoke(rb, buffer);
            return strFirst(buf.getMethod("readUtf8").invoke(buffer));
        } catch (Throwable t) {
            return "(读请求体失败: " + t + ")";
        }
    }

    /** 打印响应正文 —— 只留在意的那几类（含 stId / 策略 id / 大列表） */
    private void logResponseBody(Object body) {
        if (!(body instanceof String)) return;
        String s = (String) body;
        if (s.isEmpty()) return;
        boolean hit = s.contains("\"total\"") || s.contains("\"list\"") || s.contains("\"stId\"")
                || s.contains("1569198903482388480") || s.contains("策略")
                || s.contains("\"stockList\"") || s.contains("个股") || s.contains("\"count\"");
        if (!hit) return;
        log("");
        log("== HTTP-RESP (" + s.length() + " 字节) ==");
        log("  " + cut(s, 12000));
    }

    private String strFirst(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    // ============================================================ TCP 抓包（自研协议）

    /**
     * 抓**自定义 TCP 通道**上的原始字节。
     *
     * 为什么还需要它：OkHttp 钩子（[hookHttp]）实测 **0 命中** —— 金太阳的行情与选股数据
     * 走的是通达信式自研协议（`cache/datacache.json` 里的 `BestHost = tdxWifi:…`），
     * 所以要判定「策略详情页 100 只上限」到底带没带在**请求参数**里，只能看这一层。
     *
     * 做法：挂 `java.net.Socket#getInputStream/getOutputStream`，**动态**给返回的那个流
     * 实现类挂 `read/write(byte[],int,int)`（每个类只挂一次）。为了不把行情推送刷屏，
     * 只在 [captureUntil] 这个「时间窗」内 dump，且总量有上限。
     */
    private volatile long captureUntil = 0L;

    private final Set<String> hookedStreams = new HashSet<>();
    private final Set<String> seenPeers = new HashSet<>();
    private final java.util.concurrent.atomic.AtomicInteger netChunks =
            new java.util.concurrent.atomic.AtomicInteger();

    private void hookSocketIo() {
        try {
            Class<?> sock = Class.forName("java.net.Socket", false, sCL);

            int n = hookNamedResult(sock, "getOutputStream", "probe:net-out",
                    new ResultAfter() {
                        @Override public void run(Object self, Object[] args, Object result) {
                            if (result != null) hookStreamMethods(result, true);
                        }
                    });
            n += hookNamedResult(sock, "getInputStream", "probe:net-in",
                    new ResultAfter() {
                        @Override public void run(Object self, Object[] args, Object result) {
                            if (result != null) hookStreamMethods(result, false);
                        }
                    });
            n += hookNamed(sock, "connect", "probe:net-conn", new After() {
                @Override public void run(Object self, Object[] args) {
                    logPeer(args);
                }
            });

            log("已挂 socket 钩子 ×" + n);

            // TLS：上一步抓到的全是 `17 03 03 …`（TLS Application Data），是密文。
            // 明文要从 **SSLSocket 的流** 上取 —— Conscrypt 的实现类覆写了
            // `Socket#getOutputStream`，所以挂父类那条路不生效，得逐个股实现类挂。
            int m = 0;
            for (String cn : TLS_SOCKET_CLASSES) {
                Class<?> c = Class.forName(cn, false, sCL);
                if (c == null) continue;
                m += hookNamedResult(c, "getOutputStream", "probe:tls-out", new ResultAfter() {
                    @Override public void run(Object self, Object[] args, Object result) {
                        if (result != null) hookStreamMethods(result, true);
                    }
                });
                m += hookNamedResult(c, "getInputStream", "probe:tls-in", new ResultAfter() {
                    @Override public void run(Object self, Object[] args, Object result) {
                        if (result != null) hookStreamMethods(result, false);
                    }
                });
                log("TLS 流入口 " + cn + " ×" + m);
            }
            log("已挂 TLS 明文流入口 ×" + m);
        } catch (Throwable t) {
            log("hookSocketIo 失败 " + t);
        }
    }

    /** 认一下 API 主机 + 调用方（谁在连、连去哪） */
    private void logPeer(Object[] args) {
        try {
            String s = (args != null && args.length > 0 && args[0] != null)
                    ? String.valueOf(args[0]) : "(null)";
            synchronized (seenPeers) {
                if (!seenPeers.add(s)) return;
            }
            log("");
            log("== NET-PEER " + s + " ==");
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            for (int i = 1; i < Math.min(st.length, 22); i++) {
                String c = st[i].getClassName();
                if (c.startsWith("com.wb.probe") || c.startsWith("java.lang.Thread")) continue;
                log("    at " + c + "#" + st[i].getMethodName() + ":" + st[i].getLineNumber());
                if (c.startsWith("com.guosen")) break;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 给「流实现类」挂 read/write —— 每个类只挂一次（沿继承链找，方法常声明在父类） */
    private void hookStreamMethods(final Object stream, final boolean out) {
        final Class<?> concrete = stream.getClass();
        String key = concrete.getName() + (out ? "#w" : "#r");
        synchronized (hookedStreams) {
            if (!hookedStreams.add(key)) return;
        }
        log("");
        log("== NET-STREAM " + (out ? "OUT" : "IN") + " = " + concrete.getName() + " ==");
        int hooked = 0;
        Class<?> c = concrete;
        while (c != null && c != Object.class) {
            int before = hooked;
            for (final Method m : c.getDeclaredMethods()) {
                final String name = m.getName();
                if (out) {
                    if (!name.equals("write")) continue;
                } else {
                    if (!name.equals("read") && !name.equals("readFully")) continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 3 || ps[0] != byte[].class) continue;   // (byte[], int, int)
                try {
                    m.setAccessible(true);
                    final boolean isRead = !out;
                    hook(m)
                            .setId("probe:net:" + c.getSimpleName() + ":" + name)
                            .intercept(new XposedInterface.Hooker() {
                                @Override
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    if (isRead) {
                                        Object r = chain.proceed();
                                        try {
                                            if (System.currentTimeMillis() < captureUntil) {
                                                Object[] a = chain.getArgs().toArray();
                                                int n = (r instanceof Integer) ? (Integer) r : 0;
                                                if (n > 0) {
                                                    dumpChunk(true, (byte[]) a[0],
                                                            (Integer) a[1], n);
                                                }
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                        return r;
                                    }
                                    try {
                                        if (System.currentTimeMillis() < captureUntil) {
                                            Object[] a = chain.getArgs().toArray();
                                            dumpChunk(false, (byte[]) a[0],
                                                    (Integer) a[1], (Integer) a[2]);
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                    return chain.proceed();
                                }
                            });
                    hooked++;
                    log("  已挂 " + c.getName() + "#" + name + "(" + sig(ps) + ")");
                } catch (Throwable t) {
                    log("  挂流方法失败 " + c.getName() + "#" + name + " " + t);
                }
            }
            c = c.getSuperclass();
            // 只挂「最具体的那一层」：SocketOutputStream → FileOutputStream → OutputStream
            // 三个都有同名方法，全挂会把同一段字节重复 dump 三遍
            if (hooked > before) break;
        }
        if (hooked == 0) log("  （这个流实现类上没有可挂的 3 参 read/write）");
    }

    /** 一次读/写的原始字节：hex + 可打印 ASCII（Tdx 请求里带字符串，直接能认出来） */
    private void dumpChunk(boolean read, byte[] buf, int off, int len) {
        // 只抓「写出方向的 HTTP 明文请求」：要判定的是**请求参数**里带没带 limit/count，
        // 回包（行情推送）与 TLS 握手记录会把日志淹没。挂在 SSL 明文流上时，
        // 一次请求（含 body）通常是一次 write，所以整套 headers+body 都在这里。
        if (len < 8) return;
        int b0 = buf[off] & 0xff;
        if (read) {
            // 响应：只看「像正文」的 —— gzip(1f 8b) / JSON({ 或 [) / chunked 长度行
            if (!(b0 == 0x1f || b0 == '{' || b0 == '[')) return;
        } else {
            // 请求：只看 HTTP 方法开头
            if (!(b0 == 'P' || b0 == 'G' || b0 == 'H' || b0 == 'D')) return;
        }
        if (netChunks.incrementAndGet() > NET_MAX_CHUNKS) return;
        int n = Math.min(len, NET_MAX_BYTES);
        StringBuilder hex = new StringBuilder();
        StringBuilder asc = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int b = buf[off + i] & 0xff;
            hex.append(String.format("%02x", b));
            asc.append(b >= 32 && b < 127 ? (char) b : '.');
        }
        log((read ? "NET-R " : "NET-W ") + "len=" + len
                + (len > n ? "(截断到" + n + ")" : ""));
        log("  " + hex);
        log("  " + asc);
    }

    // ============================================================ 通用钩子工具

    /** 钩子回调体：原方法执行完后拿到 this 与参数 */
    private interface After {
        void run(Object self, Object[] args);
    }
    /** 带返回值的钩子回调体 */
    private interface ResultAfter {
        void run(Object self, Object[] args, Object result);
    }

    /** 同 [hookNamed]，但回调里额外拿到原方法的返回值 */
    private int hookNamedResult(Class<?> cls, String name, final String idPrefix,
                               final ResultAfter after) {
        int n = 0;
        for (final Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (Modifier.isAbstract(m.getModifiers())) continue;
            try {
                m.setAccessible(true);
                hook(m)
                        .setId(idPrefix + ":" + m.getName() + ":" + m.getParameterCount())
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object result = chain.proceed();
                                try {
                                    after.run(chain.getThisObject(), chain.getArgs().toArray(), result);
                                } catch (Throwable t) {
                                    log("hook[" + idPrefix + "] 回调异常 " + t);
                                }
                                return result;
                            }
                        });
                n++;
            } catch (Throwable t) {
                log("hook " + cls.getSimpleName() + "." + name + " 失败 " + t);
            }
        }
        return n;
    }

    /**
     * 挂某个类上**所有**同名（非抽象）方法，原方法执行完后回调 [after]。
     * 现代 API 没有 hookAllMethods，只能自己枚举。
     */
    private int hookNamed(Class<?> cls, String name, final String idPrefix, final After after) {
        int n = 0;
        for (final Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (Modifier.isAbstract(m.getModifiers())) continue;
            try {
                m.setAccessible(true);
                hook(m)
                        .setId(idPrefix + ":" + m.getName() + ":" + m.getParameterCount())
                        .intercept(new XposedInterface.Hooker() {
                            @Override
                            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                Object result = chain.proceed();
                                try {
                                    after.run(chain.getThisObject(), chain.getArgs().toArray());
                                } catch (Throwable t) {
                                    log("hook[" + idPrefix + "] 回调异常 " + t);
                                }
                                return result;
                            }
                        });
                n++;
            } catch (Throwable t) {
                log("hook " + cls.getSimpleName() + "." + name + " 失败 " + t);
            }
        }
        return n;
    }

    private static Intent firstIntent(Object[] args) {
        if (args == null) return null;
        for (Object a : args) if (a instanceof Intent) return (Intent) a;
        return null;
    }

    /** 打印一个 Intent 的完整契约 */
    private void dumpIntent(String tag, Intent it) {
        if (it == null) {
            log("  [" + tag + "] intent = null");
            return;
        }
        log("  [" + tag + "] to = " + componentOf(it)
                + "  action = " + it.getAction()
                + "  flags = 0x" + Integer.toHexString(it.getFlags()));
        try {
            if (it.getCategories() != null) log("       categories = " + it.getCategories());
            if (it.getDataString() != null) log("       data = " + it.getDataString());
        } catch (Throwable ignored) {}

        Bundle ex = null;
        try { ex = it.getExtras(); } catch (Throwable t) { log("       getExtras 异常 " + t); }
        if (ex == null) {
            log("       extras = <null>");
            return;
        }
        log("       extras(" + ex.size() + ") —— 对比两次跳转的 key，差异就是翻页列表上下文:");
        int i = 0;
        for (String k : ex.keySet()) {
            if (i++ >= 40) { log("         ...(截断)"); break; }
            Object v;
            try { v = ex.get(k); } catch (Throwable t) { v = "<读取异常>"; }
            log("         " + k + " : " + typeOf(v) + " = " + summarizeValue(v));
        }
        log("       stack: " + stackHint());
    }

    private static String componentOf(Intent it) {
        try {
            ComponentName cn = it.getComponent();
            if (cn != null) return cn.getClassName();
            return it.getPackage() == null ? "<无 component>" : it.getPackage();
        } catch (Throwable t) {
            return "<解析失败>";
        }
    }

    private static String extrasKeyOf(Intent it) {
        try {
            Bundle b = it.getExtras();
            return b == null ? "" : b.keySet().toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 精简调用栈：只看 com.guosen 帧，最多 10 帧 */
    private static String stackHint() {
        try {
            StackTraceElement[] st = new Throwable().getStackTrace();
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (StackTraceElement e : st) {
                if (!e.getClassName().startsWith("com.guosen")) continue;
                if (n > 0) sb.append(" <- ");
                sb.append(e.getClassName()).append('#').append(e.getMethodName())
                        .append(':').append(e.getLineNumber());
                if (++n >= 10) break;
            }
            return n == 0 ? "<无 com.guosen 帧>" : sb.toString();
        } catch (Throwable t) {
            return "<栈不可用>";
        }
    }

    /** 读控件的 OnClickListener 类名（View.mListenerInfo.mOnClickListener） */
    private String clickListenerOf(View v) {
        try {
            Field li = findField(v.getClass(), "mListenerInfo");
            if (li == null) return null;
            li.setAccessible(true);
            Object info = li.get(v);
            if (info == null) return null;
            Field cl = findField(info.getClass(), "mOnClickListener");
            if (cl == null) return null;
            cl.setAccessible(true);
            Object l = cl.get(info);
            return l == null ? null : l.getClass().getName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 用控件所属 Resources 反查 id 名（必须用**目标 App 的** Resources） */
    private static String idName(Object context, int id) {
        if (id == View.NO_ID || context == null) return null;
        try {
            Method m = context.getClass().getMethod("getResources");
            Object res = m.invoke(context);
            if (res == null) return null;
            Method re = res.getClass().getMethod("getResourceEntryName", int.class);
            Object n = re.invoke(res, id);
            return n == null ? null : n.toString();
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(id);
        }
    }

    private static Object invoke0(Object target, String method) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object countOf(Object adapter) {
        if (adapter == null) return null;
        Object c = invoke0(adapter, "getItemCount");
        if (c == null) c = invoke0(adapter, "getCount");
        return c;
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * extras 值摘要。集合 / 数组 / Bundle 都要**展开到元素字段级** ——
     * 「翻页列表上下文」往往就是藏在 Bundle 里的 ArrayList&lt;业务对象&gt;，
     * 只打对象引用（`Foo@7d439291`）等于没打。
     */
    private static String summarizeValue(Object v) {
        return summarizeValue(v, 0);
    }

    private static String summarizeValue(Object v, int depth) {
        if (v == null) return "null";
        if (depth > 3) return "<深度截断>";

        if (v instanceof Bundle) {
            Bundle b = (Bundle) v;
            StringBuilder sb = new StringBuilder("Bundle{");
            int i = 0;
            for (String k : b.keySet()) {
                if (i++ >= 12) { sb.append(", ...(共").append(b.size()).append(")"); break; }
                Object bv;
                try { bv = b.get(k); } catch (Throwable t) { bv = "<读取异常>"; }
                sb.append(k).append('=').append(summarizeValue(bv, depth + 1)).append(", ");
            }
            return sb.append('}').toString();
        }
        if (v instanceof Collection) {
            Collection<?> c = (Collection<?>) v;
            StringBuilder sb = new StringBuilder("size=").append(c.size()).append(" [");
            int i = 0;
            for (Object o : c) {
                if (i++ >= 4) { sb.append("..."); break; }
                sb.append(summarizeValue(o, depth + 1)).append(", ");
            }
            return sb.append(']').toString();
        }
        if (v instanceof Object[]) {
            Object[] a = (Object[]) v;
            StringBuilder sb = new StringBuilder("len=").append(a.length).append(" [");
            for (int i = 0; i < a.length && i < 4; i++) {
                sb.append(summarizeValue(a[i], depth + 1)).append(", ");
            }
            if (a.length > 4) sb.append("...");
            return sb.append(']').toString();
        }
        return summarize(v);
    }

    // ============================================================ 反射工具

    private Object callStatic(String cls, String mtd) {
        try {
            Class<?> c = Class.forName(cls, false, sCL);
            Method m = c.getDeclaredMethod(mtd);
            m.setAccessible(true);
            return m.invoke(null);
        } catch (Throwable t) { return null; }
    }

    private Boolean boolStatic2(String cls, String mtd, String a, String b) {
        try {
            Class<?> c = Class.forName(cls, false, sCL);
            Method m = c.getDeclaredMethod(mtd, String.class, String.class);
            m.setAccessible(true);
            Object r = m.invoke(null, a, b);
            return (r instanceof Boolean) ? (Boolean) r : null;
        } catch (Throwable t) { return null; }
    }

    private Boolean boolInst2(String cls, String mtd, String a, String b) {
        Object mgr = zxManager();
        if (mgr == null) return null;
        try {
            Class<?> c = Class.forName(cls, false, sCL);
            Method m = c.getDeclaredMethod(mtd, String.class, String.class);
            m.setAccessible(true);
            Object r = m.invoke(mgr, a, b);
            return (r instanceof Boolean) ? (Boolean) r : null;
        } catch (Throwable t) { return null; }
    }

    private Boolean boolInst3(String cls, String mtd, String a, String b, String c3) {
        Object mgr = zxManager();
        if (mgr == null) return null;
        try {
            Class<?> c = Class.forName(cls, false, sCL);
            Method m = c.getDeclaredMethod(mtd, String.class, String.class, String.class);
            m.setAccessible(true);
            Object r = m.invoke(mgr, a, b, c3);
            return (r instanceof Boolean) ? (Boolean) r : null;
        } catch (Throwable t) { return null; }
    }

    private static Field findField(Class<?> start, String name) {
        Class<?> c = start;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) {
            return null;
        }
    }

    private void dumpFields(String label, Object obj) {
        if (obj == null) return;
        log("  --- " + label + " ---");
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    log("    F > " + f.getType().getSimpleName() + " " + f.getName()
                            + " = " + summarize(v));
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private static Object[] toArray(Object o) {
        if (o == null) return null;
        if (o instanceof Object[]) return (Object[]) o;
        if (o instanceof int[]) {
            int[] a = (int[]) o; Object[] r = new Object[a.length];
            for (int i = 0; i < a.length; i++) r[i] = a[i];
            return r;
        }
        return null;
    }

    private static int sizeOf(Object o) {
        if (o instanceof Collection) return ((Collection<?>) o).size();
        if (o instanceof Map) return ((Map<?, ?>) o).size();
        return -1;
    }

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 对象摘要: 优先用 getter/toString, 截断到 220 字符 */
    private static String summarize(Object o) {
        if (o == null) return "null";
        if (o instanceof String || o instanceof Number || o instanceof Boolean) return String.valueOf(o);
        String s;
        try { s = String.valueOf(o); } catch (Throwable t) { s = "<toString 异常>"; }
        // 「默认 toString」= `类名@hashCode的十六进制`（注意是 hashCode，不是 identityHashCode，
        // 类可以覆写 hashCode 而不覆写 toString）。这种输出没有任何信息量，改成反射 dump 字段。
        boolean defaultToString = s != null && s.startsWith(o.getClass().getName() + "@");
        if (s == null || defaultToString) {
            StringBuilder sb = new StringBuilder();
            Class<?> c = o.getClass();
            int n = 0;
            while (c != null && c != Object.class && n < 24) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                            sb.append(f.getName()).append("=").append(v).append(" ");
                            n++;
                        }
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
            if (sb.length() > 0) s = o.getClass().getSimpleName() + "{" + sb.toString().trim() + "}";
        }
        if (s != null && s.length() > 220) s = s.substring(0, 220) + "...";
        return s;
    }
}
