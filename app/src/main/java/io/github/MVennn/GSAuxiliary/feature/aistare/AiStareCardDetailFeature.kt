package io.github.MVennn.GSAuxiliary.feature.aistare

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.core.TargetApp
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.util.Collections
import java.util.WeakHashMap

/**
 * 功能：**「我的盯盘」卡片点一下就进个股详情页**。
 *
 * ## 为什么需要它
 *
 * 「智能盯盘 → 我的盯盘」里，每只股票就是一张卡片（股票名 + 代码 + 一句提醒描述 +
 * 右下角编辑 / 删除）。这个页面**没有任何通往个股详情页的入口** —— 想看 K 线、
 * 盘口、分时，只能退出去重新搜一遍代码。本功能给卡片本身挂一个点击：点卡片空白处
 * → 直接进该股的个股详情页（`StockFragAct`，也就是行情 / 自选点进去的同一个页面）。
 *
 * ## 界面一点没动
 *
 * - 卡片**不加任何可见元素**（不加箭头、不改底色、不改布局），只是从不可点变成可点；
 * - 右下角「编辑」「删除」是两个自带 `clickable=true` 的 `ImageView`，**子视图先消费点击**，
 *   所以它们的行为与原来完全一致；
 * - 卡片上方那行「股票名 + 代码 + ›」（`layoutStock`，宿主本来就给它设了监听）**不碰**，
 *   原有点击行为原样保留。
 *
 * ## 实现路径
 *
 * ```
 * ListView.setAdapter（页面加载 / 切页签都会走）
 *   └ 命中「我的盯盘」列表（id = listMyStare，或宿主就是 AIStareIndexAct）
 *       └ 动态给它这次挂上的 adapter 挂 getView（类首次出现时挂一次）
 *           └ 每行渲染完：从数据对象取「代码.市场」→ 记到 (卡片视图 → code.market)
 *                        → 给 #layoutContent 设点击
 * 点卡片 → 查表拿 code / market → startActivity(quotes.stockDetail + IntentStockInfo)
 * ```
 *
 * **点击时不用 position，用「视图 → 代码」的表**：ListView 会复用行视图，
 * position 只在 `getView` 那一刻是对的；把它存进视图（或这儿的弱表）才不会张冠李戴。
 * 这个坑与 `StgDirectQuoteFeature` §3.3 第 6 条是同一类问题（点第 5 只开了第 1 只）。
 *
 * ## 数据从哪来
 *
 * 卡片上的 `tvCodeMarket` 只有 6 位代码（`000001`），**没有市场号**，而详情页契约要
 * `(code, market)`（1 = 深、2 = 沪、北交所另算）。靠代码前缀猜市场太脆，
 * 所以走 adapter 的 item 对象：宿主自己的 `codeAndMarket` 里就带着市场号。
 * 拿不到时才退回「从 `tvCodeMarket` 文本 + 前缀猜」，并且只认沪 / 深两种，
 * 其余（北交所等）**宁可不跳也不跳错**。
 */
class AiStareCardDetailFeature : Feature {

    override val id = "aistare_card_detail"
    override val title = "我的盯盘卡片直达个股详情"

    override fun isEnabled(cfg: ConfigSnapshot) = cfg.aiStareCardDetail

    /** 见过并登记过的 adapter 实例（弱引用：页面销毁后自然回收） */
    private val knownAdapters =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Any, Boolean>()))

    /** 已经挂过 getView 的 adapter 类名（同一个类只挂钩子一次） */
    private val hookedAdapterClasses =
        Collections.synchronizedSet(HashSet<String>())

    /** 卡片视图 → 「代码.市场」。getView 绑定时写入，点击时读取 */
    private val cardCam =
        Collections.synchronizedMap(WeakHashMap<View, String>())

    /** 已经打过诊断日志的 item 类名（每个数据类只打一次） */
    private val dumpedItemClasses =
        Collections.synchronizedSet(HashSet<String>())

    /** 资源 id 缓存（0 = 还没解析） */
    @Volatile private var listIdCache = -1
    @Volatile private var cardIdCache = -1
    @Volatile private var codeIdCache = -1

    /** 卡片点击 → 进详情页（监听器本身无状态，视图查表拿代码） */
    private val cardClicker = View.OnClickListener { v ->
        runCatching { onClickCard(v) }
            .onFailure { GLog.w("我的盯盘卡片：点击处理异常", it) }
    }

    // ------------------------------------------------------------ 安装

    override fun install(env: FeatureEnv, bag: HookBag) {
        val cl = env.classLoader
        var hooked = 0

        // 主挂点：ListView.setAdapter（页面加载与切页签时都会走到）
        val lvCls = Reflect.loadClass("android.widget.ListView", cl)
        if (lvCls != null) {
            for (m in Reflect.methodsNamed(lvCls, "setAdapter")) {
                bag.add(
                    env.xposed.hook(m)
                        .setId("$id:setAdapter")
                        .intercept { chain ->
                            val r = chain.proceed()
                            runCatching {
                                val lv = chain.thisObject as? ListView
                                if (lv != null && isTargetList(lv)) {
                                    registerAdapter(env, bag, lv.adapter)
                                }
                            }.onFailure { GLog.w("我的盯盘：setAdapter 后处理异常", it) }
                            r
                        },
                )
                hooked++
            }
        } else {
            GLog.w("我的盯盘卡片：找不到 android.widget.ListView，主挂点未装")
        }

        // 兜底：进页面时再对齐一次（钩子装晚了的场景，例如热重载后页面已在栈里）
        val actCls = Reflect.loadClass("android.app.Activity", cl)
        if (actCls != null) {
            for (m in Reflect.methodsNamed(actCls, "onResume")) {
                bag.add(
                    env.xposed.hook(m)
                        .setId("$id:onResume")
                        .intercept { chain ->
                            val r = chain.proceed()
                            runCatching {
                                val a = chain.thisObject as? Activity
                                if (a != null && isAistarePage(a)) {
                                    val lv = findTargetList(a)
                                    if (lv != null) registerAdapter(env, bag, lv.adapter)
                                }
                            }.onFailure { GLog.w("我的盯盘：onResume 后处理异常", it) }
                            r
                        },
                )
                hooked++
            }
        }

        if (hooked == 0) GLog.e("我的盯盘卡片：没有任何钩子装上（宿主类名可能已变）")
    }

    // ------------------------------------------------------------ adapter

    /**
     * 登记一个「我的盯盘」的 adapter，并给它挂 `getView`。
     *
     * 同一个 adapter **类**只挂一次钩子（钩子是挂在类上的，对所有实例生效），
     * 钩子里再用 [knownAdapters] 过滤，保证只处理这一页的列表。
     */
    private fun registerAdapter(env: FeatureEnv, bag: HookBag, rawAdapter: Any?) {
        val adapter = unwrapHeaderAdapter(rawAdapter) ?: return

        // 只认「我的盯盘」的 adapter；同页「今日提醒」是另一个 adapter，卡片语义不同
        val clsName = adapter.javaClass.name
        if (AiStareCardDetailTargets.TARGET_ADAPTERS.none { clsName == it }) return
        knownAdapters.add(adapter)

        // getView 不一定声明在 adapter 自己身上（宿主的 AIStareMyStareAdapter 就没有，
        // 实现在父类）—— 沿继承链向上找第一个「真的实现了 getView」的类再挂。
        // 挂点类可能与 adapter 类不同，钩子里仍按实例（knownAdapters）过滤。
        var c: Class<*>? = adapter.javaClass
        var hooked = false
        while (c != null && c != Any::class.java && !c.name.startsWith("android.") &&
            !c.name.startsWith("java.")
        ) {
            val name = c.name
            if (hookedAdapterClasses.add(name)) {
                val methods = Reflect.methodsNamed(c, "getView")
                for (m in methods) {
                    bag.add(
                        env.xposed.hook(m)
                            .setId("$id:getView:$name")
                            .intercept { chain ->
                                val result = chain.proceed()
                                runCatching { onGetView(env, chain.thisObject, chain.args, result) }
                                    .onFailure { GLog.w("我的盯盘：getView 后处理异常", it) }
                                result
                            },
                    )
                }
                if (methods.isNotEmpty()) {
                    GLog.i("我的盯盘卡片：已接管 ${adapter.javaClass.name}（挂点 $name，getView ×${methods.size}）")
                    hooked = true
                    break
                }
            } else if (Reflect.methodsNamed(c, "getView").isNotEmpty()) {
                hooked = true // 这个类早就挂过了
                break
            }
            c = c.superclass
        }
        if (!hooked) GLog.w("我的盯盘卡片：${adapter.javaClass.name} 继承链上没有可挂的 getView")
    }

    private fun onGetView(env: FeatureEnv, adapter: Any?, args: List<Any?>, result: Any?) {
        if (adapter == null || !knownAdapters.contains(adapter)) return
        val position = args.getOrNull(0) as? Int ?: return
        val row = result as? View ?: return

        val card = row.findViewById<View>(cardId(row)) ?: return
        val cam = camAt(adapter, position) ?: fallbackCam(row)
        if (cam == null) {
            // 数据里没找到、视图里也只认出个壳：这一行不挂点击，宁可少跳也不跳错
            card.setOnClickListener(null)
            cardCam.remove(card)
            return
        }
        cardCam[card] = cam
        bindCard(env, card)
    }

    /**
     * 给卡片挂 / 摘点击。
     *
     * 每行渲染都会走一遍：开关关掉时把监听器摘掉（复用视图上不能残留），
     * 打开时挂上。监听器是同一个对象，重复 `setOnClickListener` 没有副作用。
     */
    private fun bindCard(env: FeatureEnv, card: View) {
        if (env.config.aiStareCardDetail) {
            card.setOnClickListener(cardClicker)
        } else {
            card.setOnClickListener(null)
        }
    }

    private fun onClickCard(v: View) {
        val cam = cardCam[v] ?: run {
            GLog.w("我的盯盘卡片：点击的卡片没有登记代码，跳过")
            return
        }
        val dot = cam.lastIndexOf('.')
        if (dot <= 0 || dot == cam.length - 1) {
            GLog.w("我的盯盘卡片：代码格式不认识（$cam），跳过")
            return
        }
        val code = cam.substring(0, dot)
        val market = cam.substring(dot + 1)
        GLog.i("我的盯盘卡片：点卡片 → 个股详情页（$code.$market）")
        openStockDetail(v.context, code, market)
    }

    // ------------------------------------------------------------ 数据

    /** 从 adapter 的 item 里取「代码.市场」 */
    private fun camAt(adapter: Any, position: Int): String? {
        val item = itemAt(adapter, position) ?: return null
        dumpItemOnce(item)
        return camOf(item)
    }

    private fun camOf(item: Any?): String? {
        if (item == null) return null

        for (f in AiStareCardDetailTargets.CAM_FIELDS) {
            val v = Reflect.readField(item, f) as? String
            if (!v.isNullOrEmpty()) return v
        }

        var code: String? = null
        for (f in AiStareCardDetailTargets.CODE_FIELDS) {
            val v = Reflect.readField(item, f)?.toString()
            if (!v.isNullOrEmpty()) {
                code = v
                break
            }
        }
        if (code == null) return null

        for (f in AiStareCardDetailTargets.MARKET_FIELDS) {
            val v = Reflect.readField(item, f) ?: continue
            val s = v.toString()
            if (s.isNotEmpty()) return "$code.$s"
        }
        return null
    }

    /**
     * 数据取不到时的兜底：从行视图的 `tvCodeMarket` 文本拿 6 位代码，按前缀猜市场。
     *
     * 只认沪（6 / 5 开头 → 2）与深（0 / 3 / 1 开头 → 1）；北交所（4 / 8 / 9 开头）
     * 的市场号没实测过，**不猜** —— 跳错比不跳更糟。
     */
    private fun fallbackCam(row: View): String? {
        val id = codeId(row)
        if (id == 0) return null
        val text = (row.findViewById<View>(id) as? TextView)?.text?.toString()?.trim().orEmpty()
        if (text.length != 6 || text.any { !it.isDigit() }) return null
        val market = when (text.first()) {
            '6', '5' -> "2"
            '0', '3', '1' -> "1"
            else -> return null
        }
        return "$text.$market"
    }

    /** 第一次见到某种 item 对象时，把字段名字与值打出来（定位数据字段用，永久保留成本极低） */
    private fun dumpItemOnce(item: Any) {
        val name = item.javaClass.name
        if (!dumpedItemClasses.add(name)) return
        val fields = runCatching {
            item.javaClass.declaredFields
                .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .joinToString(", ") { f ->
                    runCatching {
                        f.isAccessible = true
                        val v = f.get(item)
                        val s = when (v) {
                            is List<*> -> "List(size=${v.size})"
                            is String -> "\"$v\""
                            null -> "null"
                            else -> v.toString().take(40)
                        }
                        "${f.name}=$s"
                    }.getOrDefault("${f.name}=?")
                }
        }.getOrDefault("?")
        GLog.i("我的盯盘卡片：item 类 $name 字段 -> $fields")
    }

    private fun itemAt(adapter: Any, position: Int): Any? {
        Reflect.findMethod(adapter.javaClass, "getItem", Int::class.javaPrimitiveType!!)?.let { m ->
            runCatching { m.invoke(adapter, position) }.getOrNull()?.let { return it }
        }
        for (mn in arrayOf("getData", "getItems", "getList")) {
            Reflect.findMethod(adapter.javaClass, mn)?.let { m ->
                pick(runCatching { m.invoke(adapter) }.getOrNull(), position)?.let { return it }
            }
        }
        for (fn in AiStareCardDetailTargets.DATA_FIELDS) {
            pick(Reflect.readField(adapter, fn), position)?.let { return it }
        }
        return null
    }

    private fun pick(list: Any?, position: Int): Any? {
        if (list is List<*>) {
            if (position >= 0 && position < list.size) return list[position]
        }
        return null
    }

    // ------------------------------------------------------------ 跳转

    /**
     * 拉起个股详情页（`StockFragAct`）。
     *
     * 契约与「智能盯盘一键配置」保存后拉起的是同一个（`docs/FEATURE-DIRECT-QUOTE.md`
     * 路径 C）：`action=quotes.stockDetail` + `adapter_key` Bundle 里放**单只**
     * `stockInfo`（`IntentStockInfo` 自带 `(code, market)` 构造器）。
     * 少了这个 extras，详情页会直接崩。
     */
    private fun openStockDetail(context: Context, code: String, market: String): Boolean = runCatching {
        val cl = context.classLoader
        val cls = Reflect.loadClass(AiStareCardDetailTargets.CLS_INTENT_STOCK_INFO, cl)
            ?: run {
                GLog.w("我的盯盘卡片：找不到 IntentStockInfo，无法拉起详情页")
                return@runCatching false
            }
        val ctor = cls.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java
        } ?: run {
            GLog.w("我的盯盘卡片：IntentStockInfo 没有 (code, market) 构造器")
            return@runCatching false
        }
        ctor.isAccessible = true
        val item = ctor.newInstance(code, market) as? android.os.Parcelable
            ?: run {
                GLog.w("我的盯盘卡片：IntentStockInfo 不是 Parcelable")
                return@runCatching false
            }

        val bundle = android.os.Bundle().apply { putParcelable(AiStareCardDetailTargets.KEY_STOCK_INFO, item) }
        val ctx = context.applicationContext ?: context
        val intent = Intent(AiStareCardDetailTargets.ACTION_STOCK_DETAIL)
            .setClassName(TargetApp.PACKAGE, AiStareCardDetailTargets.STOCK_FRAG_CLASS)
            .putExtra(AiStareCardDetailTargets.KEY_ADAPTER, bundle)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    }.getOrElse {
        GLog.w("我的盯盘卡片：拉起详情页失败", it)
        false
    }

    // ------------------------------------------------------------ 页面 / 视图识别

    private fun isAistarePage(a: Activity): Boolean {
        val name = a.javaClass.name
        return name == AiStareCardDetailTargets.ACTIVITY ||
            name.startsWith(AiStareCardDetailTargets.ACTIVITY_PREFIX)
    }

    private fun isTargetList(lv: ListView): Boolean {
        val id = listIdOf(lv.context)
        if (id != 0 && lv.id == id) return true
        return isAistarePage(unwrapActivity(lv.context) ?: return false)
    }

    /** 页面里找「我的盯盘」的 ListView：先按 id，再退化为「页面里第一个 ListView」 */
    private fun findTargetList(a: Activity): ListView? {
        val id = listIdOf(a)
        if (id != 0) return a.findViewById<View>(id) as? ListView
        return findByClass(a.window?.decorView, ListView::class.java)
    }

    private fun <T : View> findByClass(root: View?, cls: Class<T>): T? {
        if (root == null) return null
        if (cls.isInstance(root)) return cls.cast(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByClass(root.getChildAt(i), cls)?.let { return it }
            }
        }
        return null
    }

    private fun unwrapActivity(ctx: Context?): Activity? {
        var c = ctx
        var guard = 0
        while (c != null && guard++ < 10) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    /** `ListView.getAdapter()` 带 header 时会返回包装类，要拆出真正的业务 adapter */
    private fun unwrapHeaderAdapter(a: Any?): Any? {
        if (a == null) return null
        if (a.javaClass.name == "android.widget.HeaderViewListAdapter") {
            return runCatching { a.javaClass.getMethod("getWrappedAdapter").invoke(a) }.getOrNull()
        }
        return a
    }

    // ------------------------------------------------------------ 资源 id

    private fun listIdOf(ctx: Context?): Int {
        if (listIdCache >= 0) return listIdCache
        val res = ctx?.resources ?: return -1
        return res.getIdentifier(AiStareCardDetailTargets.LIST_ID, "id", AiStareCardDetailTargets.RES_PKG)
            .also { listIdCache = it }
    }

    private fun cardId(v: View): Int {
        if (cardIdCache < 0) cardIdCache = v.resources.getIdentifier(
            AiStareCardDetailTargets.CARD_ID, "id", AiStareCardDetailTargets.RES_PKG,
        )
        return cardIdCache
    }

    private fun codeId(v: View): Int {
        if (codeIdCache < 0) codeIdCache = v.resources.getIdentifier(
            AiStareCardDetailTargets.CODE_ID, "id", AiStareCardDetailTargets.RES_PKG,
        )
        return codeIdCache
    }

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        // 关开关即时生效：把已经挂上的卡片点击摘掉（复用视图上不能残留）
        if (!cfg.aiStareCardDetail) {
            val cards = synchronized(cardCam) { cardCam.keys.toList() }
            for (c in cards) runCatching { c.setOnClickListener(null) }
        }
    }
}
