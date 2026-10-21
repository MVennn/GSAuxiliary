package io.github.MVennn.GSAuxiliary.feature.watchlist

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.stgfilter.StgFilterBridge
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 功能：策略详情页「自选股」名称高亮。
 *
 * 原理：
 *   行情页会给 `StockInfo.isZx == true` 的行把名称染成 `#FFFF8000`。
 *   策略详情页用的是同一个 `StockInfo`，但数据源没填 `isZx`，所以永远不黄。
 *   这里在 `BaseQuickAdapter.getView` 返回后，用国信自己的
 *   `ZXWorkflow.isBelongByStock(code, market)` 重新判定一次并补上颜色。
 *
 * 只染名称，不动涨跌幅、不动其它任何 UI。
 */
class WatchlistFeature : Feature {

    override val id = "watchlist_highlight"
    override val title = "自选股高亮"

    /** 记录每个名称 TextView 第一次见到的「非高亮色」，用于还原 */
    private val originalColors =
        Collections.synchronizedMap(WeakHashMap<TextView, Int>())

    /**
     * 记录每个名称 TextView 第一次见到的「原始 Typeface」，用于还原。
     *
     * 与 [originalColors] 同一套道理：RecyclerView 复用视图，
     * 换一批数据后要把加粗撤掉 —— 没有原 Typeface 就还原不回去。
     */
    private val originalTypefaces =
        Collections.synchronizedMap(WeakHashMap<TextView, Typeface>())

    /** 见过的适配器（弱引用），配置变化时用它触发列表重绘 */
    private val seenAdapters =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Any, Boolean>()))

    @Volatile private var nameIdCache = 0

    /** 策略详情页 ListView 的资源 id 缓存（0 = 还没解析过） */
    @Volatile private var listIdCache = 0

    /** 上一次已应用的配置指纹；[Int.MIN_VALUE] 表示还没应用过 */
    @Volatile private var appliedFingerprint = Int.MIN_VALUE

    /** 适配器类 -> 解析出的条目访问路径（弱引用键，随类卸载自动清理） */
    private val adapterAccessors =
        Collections.synchronizedMap(WeakHashMap<Class<*>, ItemAccess>())

    /** 条目类 -> 解析出的 code/market 字段访问路径（同样弱引用键） */
    private val itemAccessors =
        Collections.synchronizedMap(WeakHashMap<Class<*>, CamAccess>())

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * ⚠️ **or 链必须同时覆盖两个开关位**（`enabled` 与 `watchlistBold`）——
     * 这个 Feature 承载**两个**开关（高亮 / 加粗），而 `FeatureManager` 里只登记它一次。
     * 漏一位就是「界面上有开关、底下不装钩子」的**假开关**
     * （`docs/EXTENDING.md` §3 第 4 步；真事：第三轮 H1「跳过保存确认」）。
     */
    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.enabled || cfg.watchlistBold

    // ------------------------------------------------------------------ 安装

    override fun install(env: FeatureEnv, bag: HookBag) {
        ZxWatchlist.init(env.classLoader)

        var hooked = 0

        // 主入口：BaseQuickAdapter.getView
        Reflect.loadClass(WatchlistTargets.CLS_BASE_QUICK_ADAPTER, env.classLoader)?.let { cls ->
            hooked += hookGetView(env, bag, cls, "base")
        } ?: GLog.w("找不到 ${WatchlistTargets.CLS_BASE_QUICK_ADAPTER}，主入口未挂")

        // 双保险：目标适配器若自己覆写了 getView
        for (name in WatchlistTargets.TARGET_ADAPTERS) {
            val cls = Reflect.loadClass(name, env.classLoader) ?: continue
            hooked += hookGetView(env, bag, cls, name.substringAfterLast('.'))
        }

        // 回到前台时按最新配置刷新，改完设置切回 App 无需手动滚动
        hooked += hookActivityResume(env, bag)

        if (hooked == 0) {
            GLog.e("没有任何钩子装上，功能不会生效（宿主类名可能已变，用 tools/probe 重新定位）")
        }
    }

    private fun hookGetView(env: FeatureEnv, bag: HookBag, cls: Class<*>, tag: String): Int {
        val methods = Reflect.methodsNamed(cls, "getView")
        for (m in methods) {
            val hookId = "watchlist:getView:$tag:" +
                    m.parameterTypes.joinToString(",") { it.name }
            bag.add(
                env.xposed.hook(m)
                    .setId(hookId)
                    .intercept { chain ->
                        val result = chain.proceed()
                        // 钩子里的异常绝不能影响目标 App。
                        // 先判「是不是目标适配器」再取参数：钩子挂在**基类**上，
                        // 全 App 所有列表的每一行都会进来，非目标列表不该付
                        // 「物化整条参数表」的钱（`chain.args` 返回 List，我们只要第 0 个 —— 2026-10-19）
                        val self = chain.thisObject
                        if (self != null && WatchlistTargets.isTargetAdapter(self.javaClass.name)) {
                            runCatching { onGetView(env, self, (chain.getArg(0) as? Int) ?: -1, result) }
                                .onFailure { GLog.w("getView 后处理异常", it) }
                        }
                        result
                    }
            )
        }
        if (methods.isNotEmpty()) GLog.i("已挂 ${cls.name}.getView ×${methods.size}")
        return methods.size
    }

    private fun hookActivityResume(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass("android.app.Activity", env.classLoader) ?: return 0
        val methods = Reflect.methodsNamed(cls, "onResume")
        for (m in methods) {
            bag.add(
                env.xposed.hook(m)
                    .setId("watchlist:onResume")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            val self = chain.thisObject
                            if (self is Activity) onActivityResumed(env, self)
                        }.onFailure { GLog.w("onResume 处理异常", it) }
                        result
                    }
            )
        }
        if (methods.isNotEmpty()) GLog.i("已挂 android.app.Activity.onResume")
        return methods.size
    }

    // ------------------------------------------------------------------ 核心

    /**
     * 页面重新可见（`onResume`）时：
     *
     *  1. 配置指纹变了的话，刷新全部见过的适配器（改完设置切回 App 即生效）；
     *  2. **无条件**重绘「策略详情页」当前可见行 —— 这是「加入自选后返回该页，
     *     黄名不实时刷新」的修复点。
     *
     * 为什么 (2) 必须无条件、且不能只靠 `notifyDataSetChanged`：
     *  - 从个股详情页加/删自选返回时，**配置指纹没有变化**，`observeConfig` 会
     *    直接 `return`，于是什么都不刷；
     *  - 此时策略详情页的行视图**已经存在**，`BaseQuickAdapter.getView` 也不会
     *    再被调用（RecyclerView / ListView 复用旧视图），所以后置钩子那套着色逻辑
     *    根本没机会跑。
     *  → 直接遍历该 ListView 的可见子 View，重新判定 + 重新着色，才是最可靠的。
     *    「移除自选后变回原色」「开关关闭时还原原色」都由同一条逻辑覆盖
     *    （判定为否 → 用 `originalColor` 还原）。
     */
    private fun onActivityResumed(env: FeatureEnv, activity: Activity) {
        observeConfig(env.config, allowRefresh = true)

        if (WatchlistTargets.isResumeRepaintActivity(activity.javaClass.name)) {
            // 延后一帧再重绘：筛选功能的 onResume 可能晚于我们注册（钩子顺序决定），
            // 它会先清掉/重发 StgFilterBridge 的快照。同步读的话可能读到「刚被清空」
            // 的一瞬间，`isFor(live)` 为 false → 整段跳过（2026-10-21 复核）。
            mainHandler.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                repaintVisibleRows(env, activity)
            }
        }
    }

    /**
     * 遍历策略详情页 ListView 的可见行，对每行的名称 TextView 重新判定自选并着色。
     *
     * 直接操作已绑定的行视图，**不调用 `notifyDataSetChanged`** ——
     * 避免 `Cannot call this method while RecyclerView is computing a layout`。
     * 只是 `setTextColor`，不改布局，主线程 `onResume` 里同步做是安全的。
     */
    private fun repaintVisibleRows(env: FeatureEnv, activity: Activity) {
        val lvId = listViewId(activity)
        if (lvId == 0) return
        val lv = activity.findViewById<View>(lvId) as? android.widget.ListView ?: return
        val live = unwrapHeaderAdapter(lv.adapter) ?: return

        // 「筛选股票」可能给这个列表套了一层过滤代理 —— 真正的业务适配器被它包着。
        // 不拆的话 `isTargetAdapter(代理类名)` 永远为 false，整段重绘**静默跳过**：
        // 表现为「筛选生效时，从个股页加/删自选返回后名字不刷新」（2026-10-21）。
        val base = unwrapFilterProxy(live) ?: return
        if (!WatchlistTargets.isTargetAdapter(base.javaClass.name)) return

        // 必须记**原适配器**：代理没有覆写 notifyDataSetChanged，它的观察者
        // 是转发到 base 的，对代理发通知等于什么都没做。
        seenAdapters.add(base)
        val cfg = env.config

        // ListView 的 header 也占一个 adapter position，需要减掉它才能对齐数据下标
        val headerCount = lv.headerViewsCount
        val first = lv.firstVisiblePosition
        val filtered = live !== base
        if (filtered && !StgFilterBridge.isFor(live)) {
            // 列表上挂着代理，但桥里的快照不是它发布的（换页 / 列表重建 / 热重载后的旧代理）。
            // 整段跳过并记一条，别逐行刷同一条日志。
            GLog.w("自选高亮：筛选快照不属于当前列表，跳过本次重绘")
            return
        }

        var painted = 0
        var firstMap: String? = null
        for (i in 0 until lv.childCount) {
            val pos = first + i
            if (pos < headerCount) continue          // header，不是股票行
            val row = lv.getChildAt(i) ?: continue
            val nameTv = findNameView(row) ?: continue
            val dataPos = pos - headerCount

            // 筛选生效时 dataPos 是**子集下标**，而 itemAt 要的是全量下标。
            // 读不到下标表就跳过这一行 —— 宁可少染一只，也不能按子集下标染错一只。
            val fullPos = if (filtered) {
                val f = StgFilterBridge.fullIndexAt(dataPos, live)
                if (f == null) {
                    GLog.w("自选高亮：筛选下标越界（pos=$dataPos），跳过该行")
                    continue
                }
                f
            } else {
                dataPos
            }

            val inWatchlist = if (needsSelfCheck(cfg) && cfg.scopeStrategy) {
                judge(base, fullPos)
            } else {
                false
            }
            applyStyle(cfg, nameTv, inWatchlist, "重绘名称失败 pos=$fullPos")
            painted++
            if (firstMap == null) firstMap = "$dataPos→$fullPos"
        }
        // onResume 才跑一次，INFO 级不刷屏；筛选生效时这行能直接证明代理拆包与下标映射都走到了
        GLog.i(
            "自选高亮：重绘 $painted 行（筛选=$filtered" +
                (firstMap?.let { "，首个映射 $it" } ?: "") + "）",
        )
    }

    /**
     * 拆掉「筛选股票」装的过滤代理；不是代理就原样返回，认出来了但拆不开返回 null。
     *
     * 为什么按类名认：热重载后新旧实例的类是**不同 ClassLoader 下的同名类**，
     * `is` 判不出来；类名与 `getTarget()` 由 proguard 的 `-keepnames` /
     * `-keepclassmembers` 保住（规则 1.2.1）。这里只做跨实例兜底 ——
     * 本功能没有代理的记账表，也只能这么认。
     *
     * 拆不开必须返回 null 而不是原样返回：调用方拿代理类名去比目标适配器会失败，
     * 但更糟的是把代理当成业务适配器去 `getItem(子集下标)`，那会读到别的股票。
     */
    private fun unwrapFilterProxy(a: Any): Any? {
        if ("FilteredAdapter" !in a.javaClass.name) return a
        // ⚠️ 必须走 Reflect.findMethod（它设了 isAccessible）：FilteredAdapter 是
        // StgStockFilterFeature 的**包私有**嵌套类，跨包 `getMethod().invoke()` 会抛
        // IllegalAccessException —— 那样这里永远返回 null，整段重绘照样是死的。
        val m = Reflect.findMethod(a.javaClass, "getTarget") ?: run {
            GLog.w("自选高亮：过滤代理上没有 getTarget()（${a.javaClass.name}），本次不重绘")
            return null
        }
        return runCatching { m.invoke(a) }
            .onFailure { GLog.w("自选高亮：过滤代理拆包失败（${a.javaClass.name}）", it) }
            .getOrNull()
    }

    /**
     * 取出真正的业务适配器。
     *
     * 策略详情页的 ListView 带 header，`ListView.getAdapter()` 返回的是
     * `HeaderViewListAdapter` 包装类，其类名不是 `AIStkAdapter` ——
     * 不拆包的话 `isTargetAdapter` 永远为 false，重绘逻辑会被整体跳过。
     */
    private fun unwrapHeaderAdapter(a: Any?): Any? {
        if (a == null) return null
        if (a.javaClass.name == "android.widget.HeaderViewListAdapter") {
            return runCatching {
                a.javaClass.getMethod("getWrappedAdapter").invoke(a)
            }.getOrNull()
        }
        return a
    }

    private fun onGetView(env: FeatureEnv, adapter: Any, position: Int, result: Any?) {
        if (position < 0) return
        val row = result as? View ?: return
        val nameTv = findNameView(row) ?: return

        seenAdapters.add(adapter)
        observeConfig(env.config, allowRefresh = true)

        val cfg = env.config
        val inWatchlist = if (needsSelfCheck(cfg) && cfg.scopeStrategy) {
            judge(adapter, position)
        } else {
            false
        }

        // 注意：关闭开关时也要显式还原原色 / 原字重，
        // 否则 RecyclerView 复用视图上残留的高亮色与加粗不会自己消失。
        applyStyle(cfg, nameTv, inWatchlist, "设置名称样式失败")
    }

    /**
     * 这一轮要不要真的去问「是不是自选」。
     *
     * 两个开关都关时**不用问** —— 反正结果是「不是自选」，直接还原即可，
     * 省掉一次反射调用（`judge` 里有 `itemAt` + `codeAndMarket` + 反射 invoke）。
     */
    private fun needsSelfCheck(cfg: ConfigSnapshot): Boolean = cfg.enabled || cfg.watchlistBold

    /**
     * 把「高亮色 + 加粗」一起应用到名称 TextView 上。
     *
     * 两个开关**互相独立**：
     *  - [ConfigSnapshot.enabled] —— 自选股名称染成 [ConfigSnapshot.color]；
     *  - [ConfigSnapshot.watchlistBold] —— 自选股名称加粗。
     *
     * 任一关掉，对应的那一项就要**还原**（颜色回原色 / 字重回原字重）——
     * RecyclerView 复用视图，残留的样式不会自己消失。
     */
    private fun applyStyle(
        cfg: ConfigSnapshot,
        nameTv: TextView,
        inWatchlist: Boolean,
        failMsg: String,
    ) {
        // ---- 颜色 ----
        // ⚠️ **先无条件取一次原色**：这样「首次绑定到的就是自选股」的行也能记下宿主原色
        // （原先只在「不是自选」时才记 → 那类行还原时变成硬编码兜底色，宿主颜色被顶掉）。
        val original = originalColor(nameTv)
        val target = if (inWatchlist && cfg.enabled) cfg.color else original
        runCatching {
            val colors = nameTv.textColors
            if (colors == null || colors.defaultColor != target) {
                nameTv.setTextColor(target)
            }
        }.onFailure { GLog.w(failMsg, it) }

        // ---- 字重 ----
        applyBold(nameTv, inWatchlist && cfg.watchlistBold)
    }

    /**
     * 加粗 / 还原字重。
     *
     * 记原 Typeface 的时机很讲究：**只在第一次见到、且当前不是加粗态时记** ——
     * 记晚了就记下了「加粗后的」那个字重，还原时撤不回去
     * （与 [originalColor] 同一套道理）。
     */
    private fun applyBold(tv: TextView, bold: Boolean) {
        val cur = runCatching { tv.typeface }.getOrNull()
        val curStyle = cur?.style
        // ⚠️ **原字重优先读视图上的 tag**（规则 1.2）：
        // 只存在实例的表里的话，**热重载后新实例认不出**「这已经是我们加粗过的」——
        // 它看到的 Typeface 已经是 BOLD，于是「原字重永远记不下来」，
        // 用户关掉「加粗」也撤不掉 → **那些名字永久粗着**。
        val tagged = tv.getTag(R.id.gsaux_watchlist_original_typeface) as? Typeface
        val recorded = (tagged ?: originalTypefaces[tv])?.style

        // 决策走 [TypefacePlan]（纯逻辑，可单测）——
        // 「记原字重的时机」是这个功能最隐蔽的坑：记晚了就记下了加粗后的那个，
        // 还原时撤不回去（整行一直粗着）。
        when (val d = TypefacePlan.decide(curStyle, recorded, bold)) {
            is TypefacePlan.Decision.Set -> {
                if (d.recordOriginal && cur != null) {
                    originalTypefaces[tv] = cur
                    // 也记到视图上（热重载后的新实例读得到）
                    runCatching { tv.setTag(R.id.gsaux_watchlist_original_typeface, cur) }
                }
                if (curStyle != d.style) {
                    val base = originalTypefaces[tv] ?: cur
                    if (base != null) {
                        runCatching { tv.typeface = Typeface.create(base, d.style) }
                            .onFailure { GLog.w("加粗名称失败", it) }
                    }
                }
            }
            is TypefacePlan.Decision.Restore -> {
                // 还原完就把记录清掉（视图上的 tag 与实例的表都清）——
                // 留着的话下次加粗时会把「已还原的普通字重」当成原字重。
                val taggedOrig = tv.getTag(R.id.gsaux_watchlist_original_typeface) as? Typeface
                val orig = originalTypefaces.remove(tv) ?: taggedOrig
                runCatching { tv.setTag(R.id.gsaux_watchlist_original_typeface, null) }
                if (orig != null && cur != orig) {
                    runCatching { tv.typeface = orig }
                        .onFailure { GLog.w("还原名称字重失败", it) }
                }
            }
            TypefacePlan.Decision.None -> Unit
        }
    }

    private fun judge(adapter: Any, position: Int): Boolean {
        return try {
            val item = itemAt(adapter, position)
            val cam = codeAndMarket(item)
            if (cam != null) {
                val dot = cam.indexOf('.')
                dot > 0 && ZxWatchlist.isInWatchlist(cam.substring(0, dot), cam.substring(dot + 1))
            } else {
                false
            }
        } catch (t: Throwable) {
            GLog.w("判定失败 pos=$position", t)
            false
        }
    }

    /** 配置指纹变了就记下来；必要时让已渲染的行重绘 */
    private fun observeConfig(cfg: ConfigSnapshot, allowRefresh: Boolean) {
        if (cfg.fingerprint == appliedFingerprint) return
        val first = appliedFingerprint == Int.MIN_VALUE
        appliedFingerprint = cfg.fingerprint
        if (!first && allowRefresh) refreshLists()
    }

    /** 让所有见过的列表适配器重绘（延后到下一帧，避开 "computing layout" 崩溃） */
    private fun refreshLists() {
        mainHandler.post {
            val snapshot = synchronized(seenAdapters) { seenAdapters.toList() }
            for (a in snapshot) {
                runCatching {
                    Reflect.findMethod(a.javaClass, "notifyDataSetChanged")?.invoke(a)
                }.onFailure { GLog.w("notifyDataSetChanged 失败: ${a.javaClass.name}", it) }
            }
        }
    }

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        // 框架推送的配置变化已经在主线程分发到这里
        observeConfig(cfg, allowRefresh = true)
    }

    // ------------------------------------------------------------ 视图与反射

    /**
     * 读取 TextView 的「原始色」。
     *
     * ⚠️ **优先读视图上的 tag**（规则 1.2：装饰性现场必须能被「陌生人」识别）：
     * 存在实例的表里的话，**热重载后新实例认不出**「这已经是我们染过的」——
     * 它看到的是一个已经是高亮色的 TextView，于是把高亮色当成原色，
     * 还原时退回硬编码兜底色，**宿主自己的颜色被顶掉**。
     *
     * ⚠️ **每次都要先看一眼当前色并记下来**（不管这一行是不是自选）：
     * 原先只在「这一行不是自选」时才记 → 首次绑定到的就是自选股时，
     // 原色一次都没被记过 → 关掉高亮后还原成兜底色而不是宿主原来的颜色。
     */
    private fun originalColor(tv: TextView): Int {
        // ① 视图上的 tag（跨实例可认）
        val tagged = tv.getTag(R.id.gsaux_watchlist_original_color) as? Int
        if (tagged != null && tagged != 0) return tagged
        // ② 实例里的表（同实例的快路径）
        originalColors[tv]?.let { return it }

        var cur = ConfigKeys.FALLBACK_NORMAL_COLOR
        runCatching {
            val colors = tv.textColors
            if (colors != null) {
                val c = colors.defaultColor
                if (c != 0) cur = c
            }
        }

        // 判定走纯函数（可单测）：当前色是我们染上去的预设色 → 那它不是「原色」
        if (!isPresetColor(cur)) {
            originalColors[tv] = cur
            // ③ 也记到视图上（热重载后的新实例读得到）
            runCatching { tv.setTag(R.id.gsaux_watchlist_original_color, cur) }
            return cur
        }
        return ConfigKeys.FALLBACK_NORMAL_COLOR
    }

    /**
     * 这个颜色是不是「我们染上去的高亮色」？
     *
     * 用途：判断当前读到的颜色该不该被记成「宿主原本的颜色」——
     * 记错了的话，还原时会退回到硬编码的兜底色，**宿主自己的颜色被顶掉**。
     *
     * 为什么这样判断是安全的（2026-10-20 复核时确认过）：
     * 预设色全是**高饱和**的橙 / 黄 / 红 / 蓝（`0xFFFF8000` / `0xFFFFA000` /
     * `0xFFFF3B30` / `0xFF3482FF`），而宿主策略列表的名称色是**中性深灰**
     * （`0xFF212634`，原版 XML 默认值）—— 两边撞不上。
     * 唯一的边缘情况是「用户把高亮色设成跟宿主原色一模一样」，
     * 那时认不出来、还原成同一个颜色 —— **结果仍然是对的**。
     */
    private fun isPresetColor(color: Int): Boolean =
        ConfigKeys.PRESET_COLORS.any { it == color }

    private fun findNameView(row: View): TextView? {
        var id = nameIdCache
        if (id == 0) {
            for (name in WatchlistTargets.NAME_ID_CANDIDATES) {
                val r = runCatching {
                    row.resources.getIdentifier(name, "id", WatchlistTargets.TARGET_PKG)
                }.getOrDefault(0)
                if (r != 0) {
                    id = r
                    break
                }
            }
            if (id == 0) return null
            nameIdCache = id
        }
        return row.findViewById<View>(id) as? TextView
    }

    /** 解析策略详情页 ListView 的资源 id（候选数组 + 缓存），失败返回 0 */
    private fun listViewId(activity: Activity): Int {
        var id = listIdCache
        if (id != 0) return id
        for (name in WatchlistTargets.LIST_ID_CANDIDATES) {
            val r = runCatching {
                activity.resources.getIdentifier(name, "id", WatchlistTargets.TARGET_PKG)
            }.getOrDefault(0)
            if (r != 0) {
                id = r
                break
            }
        }
        if (id != 0) listIdCache = id
        return id
    }

    /** 适配器的条目访问路径：按适配器类解析一次、终身复用（宿主类不会换，无需失效） */
    private class ItemAccess(
        val getItem: Method?,
        val listMethods: List<Method>,
        val listFields: List<Field>,
    )

    /** 条目的 code/market 字段访问路径：按条目类解析一次、终身复用 */
    private class CamAccess(
        val camFields: List<Field>,
        val codeFields: List<Field>,
        val marketFields: List<Field>,
    )

    /**
     * 取适配器的条目访问路径。全候选都解析失败也照样缓存空访问器，
     * 避免 getView 每行都对整条继承链重扫一遍（与 [ZxWatchlist] 的失败标记同思路）。
     */
    private fun itemAccessOf(cls: Class<*>): ItemAccess {
        synchronized(adapterAccessors) {
            adapterAccessors[cls]?.let { return it }
            val acc = ItemAccess(
                getItem = Reflect.findMethod(cls, "getItem", Int::class.javaPrimitiveType!!),
                listMethods =
                    listOf("getData", "getItems", "getList").mapNotNull { Reflect.findMethod(cls, it) },
                listFields =
                    listOf("mData", "mDataList", "mItems", "data", "mList").mapNotNull { Reflect.findField(cls, it) },
            )
            adapterAccessors[cls] = acc
            return acc
        }
    }

    /** 取条目的 code/market 字段访问路径，失败也缓存（同上） */
    private fun camAccessOf(cls: Class<*>): CamAccess {
        synchronized(itemAccessors) {
            itemAccessors[cls]?.let { return it }
            val acc = CamAccess(
                camFields = WatchlistTargets.CAM_FIELDS.mapNotNull { Reflect.findField(cls, it) },
                codeFields = WatchlistTargets.CODE_FIELDS.mapNotNull { Reflect.findField(cls, it) },
                marketFields = WatchlistTargets.MARKET_FIELDS.mapNotNull { Reflect.findField(cls, it) },
            )
            itemAccessors[cls] = acc
            return acc
        }
    }

    private fun itemAt(adapter: Any, position: Int): Any? {
        val acc = itemAccessOf(adapter.javaClass)
        // 1) getItem(int)
        acc.getItem?.let { m ->
            runCatching { m.invoke(adapter, position) }.getOrNull()?.let { return it }
        }
        // 2) getData() / getItems() / getList()
        for (m in acc.listMethods) {
            pick(runCatching { m.invoke(adapter) }.getOrNull(), position)?.let { return it }
        }
        // 3) 常见字段名
        for (f in acc.listFields) {
            pick(runCatching { f.get(adapter) }.getOrNull(), position)?.let { return it }
        }
        return null
    }

    private fun pick(list: Any?, position: Int): Any? {
        if (list is List<*>) {
            if (position >= 0 && position < list.size) return list[position]
        }
        return null
    }

    private fun codeAndMarket(item: Any?): String? {
        if (item == null) return null
        val acc = camAccessOf(item.javaClass)

        for (f in acc.camFields) {
            val v = runCatching { f.get(item) }.getOrNull() as? String
            if (!v.isNullOrEmpty()) return v
        }

        var code: String? = null
        for (f in acc.codeFields) {
            val v = runCatching { f.get(item) }.getOrNull() as? String
            if (!v.isNullOrEmpty()) {
                code = v
                break
            }
        }
        if (code == null) return null

        for (f in acc.marketFields) {
            val v = runCatching { f.get(item) }.getOrNull() ?: continue
            val s = v.toString()
            if (s.isNotEmpty()) return "$code.$s"
        }
        return null
    }
}
