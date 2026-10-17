package io.github.MVennn.GSAuxiliary.feature.watchlist

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
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

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.enabled

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
                        // 钩子里的异常绝不能影响目标 App
                        runCatching { onGetView(env, chain.thisObject, chain.args, result) }
                            .onFailure { GLog.w("getView 后处理异常", it) }
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
            repaintVisibleRows(env, activity)
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
        val adapter = unwrapHeaderAdapter(lv.adapter) ?: return
        if (!WatchlistTargets.isTargetAdapter(adapter.javaClass.name)) return

        seenAdapters.add(adapter)
        val cfg = env.config

        // ListView 的 header 也占一个 adapter position，需要减掉它才能对齐数据下标
        val headerCount = lv.headerViewsCount
        val first = lv.firstVisiblePosition

        for (i in 0 until lv.childCount) {
            val pos = first + i
            if (pos < headerCount) continue          // header，不是股票行
            val row = lv.getChildAt(i) ?: continue
            val nameTv = findNameView(row) ?: continue
            val dataPos = pos - headerCount

            val inWatchlist = if (cfg.enabled && cfg.scopeStrategy) judge(adapter, dataPos) else false
            val target = if (inWatchlist) cfg.color else originalColor(nameTv)
            runCatching {
                val colors = nameTv.textColors
                if (colors == null || colors.defaultColor != target) {
                    nameTv.setTextColor(target)
                }
            }.onFailure { GLog.w("重绘名称失败 pos=$dataPos", it) }
        }
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

    private fun onGetView(env: FeatureEnv, adapter: Any?, args: List<Any?>, result: Any?) {
        if (adapter == null) return
        if (!WatchlistTargets.isTargetAdapter(adapter.javaClass.name)) return

        val position = args.getOrNull(0) as? Int ?: return
        val row = result as? View ?: return
        val nameTv = findNameView(row) ?: return

        seenAdapters.add(adapter)
        observeConfig(env.config, allowRefresh = true)

        val cfg = env.config
        val inWatchlist = if (cfg.enabled && cfg.scopeStrategy) {
            judge(adapter, position)
        } else {
            false
        }

        // 注意：关闭开关时也要显式还原原色，
        // 否则 RecyclerView 复用视图上残留的高亮色不会自己消失。
        val target = if (inWatchlist) cfg.color else originalColor(nameTv)
        runCatching {
            val colors = nameTv.textColors
            if (colors == null || colors.defaultColor != target) {
                nameTv.setTextColor(target)
            }
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

    /** 读取 TextView 的「原始色」，只缓存非高亮值，避免把高亮色误当成原色 */
    private fun originalColor(tv: TextView): Int {
        originalColors[tv]?.let { return it }

        var cur = ConfigKeys.FALLBACK_NORMAL_COLOR
        runCatching {
            val colors = tv.textColors
            if (colors != null) {
                val c = colors.defaultColor
                if (c != 0) cur = c
            }
        }

        if (!isPresetColor(cur)) {
            originalColors[tv] = cur
            return cur
        }
        return ConfigKeys.FALLBACK_NORMAL_COLOR
    }

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
