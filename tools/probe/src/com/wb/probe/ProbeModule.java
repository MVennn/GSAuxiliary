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
 * GSProbe v25 —— 页面 / 点击链 / Intent 契约侦察（现代 libxposed API 102 版）
 *
 * 历史:
 *   v18  自选判定 API 交叉验证（ZXManager dump + 20 只真值打分）
 *   v19  新增「策略详情页 → 个股详情」跳转链侦察，服务新功能
 *        「策略页点击股票直达标准行情 + 左右滑动」。
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
 *   LAUNCH[...]     一次页面跳转，紧跟一段 extras
 *   DLG[...]        中间 UI 是 Dialog
 *   POP[...]        中间 UI 是 PopupWindow
 *   PAGER / RV[...] 个股详情页里的翻页控件
 *   VW[...]         可点击控件 + 监听器类名（找点击钩子用）
 *
 * 注意: 本类**不引用任何 legacy `de.robv.android.xposed` 类型**，也不引用 androidx 类型
 *      （探针不走 Gradle，classpath 里没有 androidx，一律按类名反射）。
 */
public class ProbeModule extends XposedModule {

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
        log("=== GSProbe v25 (libxposed API 102) 载入 | process=" + process
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
        // 构造个股详情 Intent 需要自己 new 出这些数据类，先把可用构造器/字段摸清
        dumpClassMembers("com.guosen.android.quotes.data.IntentStockInfo");
        // 页面跳转全部走引擎路由，签名必须实测（加固 App 猜不得）
        dumpClassMembers("com.guosen.engine.process.EngineAct");
        dumpClassMembers("com.guosen.engine.process.EngineFrag");
        dumpClassMembers("com.guosen.engine.process.EngineIntent");
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
                    log("  F " + f.getType().getSimpleName() + " " + f.getName());
                }
                t = t.getSuperclass();
            }
            log("  -- setters / getters --");
            boolean all = c.getSimpleName().startsWith("Engine");
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

    private static String sig(Class<?>[] ps) {
        StringBuilder sb = new StringBuilder();
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

    // ============================================================ 通用钩子工具

    /** 钩子回调体：原方法执行完后拿到 this 与参数 */
    private interface After {
        void run(Object self, Object[] args);
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
