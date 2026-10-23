package io.github.MVennn.GSAuxiliary.feature.strategyquote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.stgfilter.StgFilterBridge
import io.github.MVennn.GSAuxiliary.util.Reflect

/**
 * 功能：策略详情页点股票**直达「标准行情」个股详情页**，并支持左右滑动换股。
 *
 * ## 实测链路（`docs/FEATURE-DIRECT-QUOTE.md`）
 *
 * ```
 * 原版：AIStkStgDetailAct.onItemClick ──action=router_ai_stk_detail──▶ AIStkDetailFragAct（中间页）
 *                                          │ 用户再点右上角「标准行情」
 *                                          └──action=quotes.stockDetail──▶ StockFragAct  count=1 ❌
 *
 * 行情：MainAct 列表 ──action=quotes.stockDetail──▶ StockFragAct  count=N ✅ 可滑动
 * ```
 *
 * 两者差在 extras：行情侧多一个 `adapter_key.stockList`（整张列表）+ `stockIndex`（当前下标）。
 *
 * ## 做法
 *
 * 挂在引擎路由 `EngineAct#startEngineAct(EngineIntent)` 上，**放行之前**改写：
 *  1. 命中条件：调用方是 [StgDirectQuoteTargets.CLS_STG_DETAIL]，且 action 是 `router_ai_stk_detail`；
 *  2. 把 `adapter_key.stockList` 里的 `StockInfo` 转成 `IntentStockInfo`
 *     （用 App 自带的 `IntentStockInfo(StockInfo)` 构造器，不自己拼字段），
 *     并写进一个**新的列表 / Bundle 副本** —— 绝不原地改写宿主持有的集合；
 *  3. 补上 `stockIndex`，把 action 改成 `quotes.stockDetail`；
 *  4. `chain.proceed()` —— 路由仍由 App 自己完成，我们只换目的地。
 *
 * 好处：不需要知道 `StockFragAct` 从哪拿数据、也不需要自己构造 Intent，
 * 完全复用 App 的路由与解析逻辑。
 */
class StgDirectQuoteFeature : Feature {

    override val id = "stg_direct_quote"
    override val title = "策略详情直达标准行情"

    /** 目标数据类；解析失败后不再反复尝试 */
    @Volatile private var intentStockInfoCls: Class<*>? = null
    @Volatile private var resolveFailed = false

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.stgDirectQuote

    // ------------------------------------------------------------------ 安装

    override fun install(env: FeatureEnv, bag: HookBag) {
        intentStockInfoCls = null
        resolveFailed = false

        val cls = Reflect.loadClass(StgDirectQuoteTargets.CLS_ENGINE_ACT, env.classLoader)
        if (cls == null) {
            GLog.e("找不到 ${StgDirectQuoteTargets.CLS_ENGINE_ACT}，功能不会生效")
            return
        }

        var hooked = 0
        for (name in StgDirectQuoteTargets.LAUNCH_METHODS) {
            for (m in Reflect.methodsNamed(cls, name)) {
                // 只关心带 EngineIntent 的重载
                if (m.parameterTypes.isEmpty() || !Intent::class.java.isAssignableFrom(m.parameterTypes[0])) {
                    continue
                }
                bag.add(
                    env.xposed.hook(m)
                        .setId("stgquote:$name:${m.parameterTypes.size}")
                        .intercept { chain ->
                            runCatching { onStartEngineAct(env, chain.thisObject, chain.args) }
                                .onFailure { GLog.w("EngineIntent 改写异常", it) }
                            chain.proceed()
                        }
                )
                hooked++
            }
        }

        if (hooked == 0) {
            GLog.e("直达标准行情：没有挂上任何钩子（宿主类名可能已变，用 tools/probe 重新定位）")
        } else {
            GLog.i("策略直达标准行情：已挂 $hooked 个引擎跳转点")
        }
    }

    // ------------------------------------------------------------------ 核心

    private fun onStartEngineAct(env: FeatureEnv, self: Any?, args: List<Any?>) {
        // 只处理「策略详情页」发起的跳转
        if (self !is Activity) return
        if (self.javaClass.name != StgDirectQuoteTargets.CLS_STG_DETAIL) return

        val ei = args.firstOrNull { it is Intent } as? Intent ?: return
        if (ei.action != StgDirectQuoteTargets.ACTION_TO_MIDDLE) return

        val cfg = env.config
        if (!cfg.stgDirectQuote) return

        val bundle = runCatching { ei.getBundleExtra(StgDirectQuoteTargets.KEY_ADAPTER) }.getOrNull()
        if (bundle == null) {
            GLog.w("直达标准行情：没有 adapter_key，放弃改写")
            return
        }

        if (!rewriteExtras(env, ei, bundle, cfg.stgQuoteSwipe)) return

        // 最后一步：换目的地。放在最后是为了「改写没成功就别改目的地」——
        // 否则会进一个没有数据的个股详情页。
        ei.action = StgDirectQuoteTargets.ACTION_STOCK_DETAIL
        GLog.i("策略直达标准行情：已改写成 ${StgDirectQuoteTargets.ACTION_STOCK_DETAIL}" +
                "（滑动=${cfg.stgQuoteSwipe}）")
    }

    /**
     * 把「单只 + StockInfo 列表」改写成「IntentStockInfo 列表 + 下标」。
     *
     * 注意 `stockIndex` 是**顶层 extra**（与 `adapter_key` 平级），
     * 不在 Bundle 里 —— 这是实测契约，别写进 Bundle。
     *
     * 返回 true 表示已经具备让个股详情页翻页所需的数据。
     *
     * ## ⚠️ 绝不能改写宿主持有的集合对象（2.12.0 修复「二次点击闪退」）
     *
     * 策略页构造 `EngineIntent` 时，`adapter_key.stockList` 与
     * `AIStkStgDetailAct` 的列表适配器（`AIStkAdapter`）**持有的是同一个 ArrayList 实例**。
     * 早期版本对这个列表做**原地** `clear() + addAll()`，于是首次点击就把宿主适配器的
     * 数据项换成了 `IntentStockInfo`；返回策略页后第二次点击时，宿主
     * `AIStkStgDetailAct.onItemClick` 仍按 `StockInfo` 强转 → `ClassCastException`
     * → 页面被系统回收（表现为「闪退回我的策略列表」）。
     *
     * 修法：
     *  1. 构造**全新的** ArrayList，绝不碰宿主那个列表；
     *  2. 连 `adapter_key` 这个 Bundle 都用**副本**（`Bundle(bundle)`）来承载新列表，
     *     把宿主传进来的原 Bundle 原封不动留在那里；
     *  3. 存储方式按行情侧契约走（`IntentStockInfo` 是 Parcelable 就用
     *     `putParcelableArrayList`，否则退回 `putSerializable`）。
     */
    private fun rewriteExtras(env: FeatureEnv, ei: Intent, bundle: Bundle, swipe: Boolean): Boolean {
        val rawList = runCatching { bundle.get(StgDirectQuoteTargets.KEY_STOCK_LIST) }.getOrNull()
            as? List<*>
        val clicked = runCatching { bundle.get(StgDirectQuoteTargets.KEY_STOCK_ITEM) }.getOrNull()

        // 翻页列表必须与用户**在列表页看到的那一份**一致。
        // 筛过之后列表只剩子集，而宿主塞进 extras 的仍是全量 —— 不收敛的话，
        // 左右滑动会滑到本该被筛掉的股票（真机实测：筛「自选」也能滑出跌股「华北制药」）。
        // 桥里存的是与适配器同一批对象，按身份挑出该留下的那几只，顺序天然保持一致。
        var originalList = rawList
        val kept = StgFilterBridge.keepOnly(rawList)
        if (kept != null) {
            if (clicked == null || kept.any { it === clicked }) {
                originalList = kept
                GLog.i("策略直达标准行情：翻页列表按筛选结果收敛 ${rawList?.size} → ${kept.size}")
            } else {
                GLog.w("策略直达标准行情：被点的股票不在筛选结果里，翻页列表退回全量")
            }
        } else if (!rawList.isNullOrEmpty() && StgFilterBridge.hasSnapshot()) {
            // 有筛选快照，但和这张列表对不上（页面已换 / 列表已重建 / Bundle 里是副本），
            // 或者保留项已经不在列表里。通道只对「算出快照的那个列表实例」生效，
            // 这里按全量走 —— 套错页面比不筛更糟。
            GLog.w("策略直达标准行情：筛选快照与当前列表对不上（或结果为空），翻页列表按全量")
        }

        val cls = resolveItemClass(env, originalList, clicked) ?: run {
            GLog.w("直达标准行情：拿不到 ${StgDirectQuoteTargets.CLS_INTENT_STOCK_INFO}")
            return false
        }

        // 用副本承载改写结果 —— 宿主那个 Bundle 与它里面的列表一个字节都不动
        val out = Bundle(bundle)

        // 关掉「滑动」：按行情侧「单只」契约走（只放一只 stockInfo，进个股详情页 count=1），
        // 同时把策略侧残留的 stockList（ArrayList<StockInfo>）清掉 ——
        // 否则个股详情页会把 StockInfo 当 IntentStockInfo 读，照样崩。
        if (!swipe) {
            val one = toIntentStockInfo(cls, clicked) ?: run {
                GLog.w("直达标准行情：单只转换失败，放弃改写（退回原版中间页）")
                return false
            }
            out.remove(StgDirectQuoteTargets.KEY_STOCK_LIST)
            out.remove(StgDirectQuoteTargets.KEY_STOCK_ITEM)
            if (!putStockInfo(out, one)) {
                GLog.w("直达标准行情：写入单只 ${one.javaClass.simpleName} 失败")
                return false
            }
            runCatching { ei.putExtra(StgDirectQuoteTargets.KEY_ADAPTER, out) }
            // 不补顶层 stockIndex —— 行情侧「单只」契约（docs/FEATURE-DIRECT-QUOTE.md 路径 C）
            // 里就没有这个 extra，多补一个反而偏离实测契约。
            GLog.i("策略直达标准行情：滑动已关闭，按单只进入")
            return true
        }

        // 滑动开启：整张列表转成 IntentStockInfo。
        //
        // 顺带在**转换过程中**记录「被点那只」在 converted 里的下标 —— 用**对象引用**（`===`）比对，
        // 而不是事后拿「代码.市场」字符串去 converted 里 indexOfFirst：converted 里是
        // IntentStockInfo，它的 CAM 字段并不存在，字符串匹配会 -1 → index 退回 0 → 点击第 N 只
        // 却打开第 1 只（不崩但进错股）。转换时元素就在手上，引用比对最稳。
        val converted = ArrayList<Any>()
        // 转换时顺手记「代码.市场 → converted 下标」。converted 会因为转换失败跳过元素，
        // 与 originalList 不等长 —— 事后拿原始下标去索引 converted 会指到另一只股票。
        val plan = ArrayList<Pair<String?, Boolean>>()
        var index = -1
        if (originalList != null) {
            for (e in originalList) {
                val isi = toIntentStockInfo(cls, e)
                plan.add(codeAndMarket(e) to (isi != null))
                if (isi == null) continue
                if (index < 0 && e === clicked) index = converted.size
                converted.add(isi)
            }
        }
        // 引用比对没命中（少见）→ 用同一轮转换时记下的映射查
        if (index < 0 && originalList != null) {
            val cam = codeAndMarket(clicked)
            if (cam != null) {
                index = DirectQuoteIndex.build(plan)[cam] ?: -1
            }
        }
        if (converted.isEmpty()) {
            // 列表拿不到（或一条都转不了）→ 退化成单只，至少保证能进详情页
            val one = toIntentStockInfo(cls, clicked) ?: return false
            converted.add(one)
            index = 0
            GLog.w("直达标准行情：列表不可用，退化为单只")
        }
        if (index < 0) index = 0

        if (!putStockList(out, converted)) {
            GLog.w("直达标准行情：写入 stockList 失败，放弃改写（退回原版中间页）")
            return false
        }
        out.remove(StgDirectQuoteTargets.KEY_STOCK_ITEM)
        // 把改过的副本明确回写一次，覆盖 EngineIntent 里那份原始 extras
        runCatching { ei.putExtra(StgDirectQuoteTargets.KEY_ADAPTER, out) }
        ei.putExtra(StgDirectQuoteTargets.KEY_STOCK_INDEX, index)
        GLog.i("直达标准行情：stockList ${converted.size} 只，stockIndex=$index")
        return true
    }

    /**
     * 把新列表写进 Bundle 的 `stockList` 条目。
     *
     * 元素是 `Parcelable` 就按行情侧契约用 `putParcelableArrayList`（首选），
     * 否则退回 `putSerializable`。两种都失败返回 false。
     */
    private fun putStockList(bundle: Bundle, converted: ArrayList<Any>): Boolean {
        if (converted.isNotEmpty() && converted.all { it is Parcelable }) {
            val ok = runCatching {
                @Suppress("UNCHECKED_CAST")
                bundle.putParcelableArrayList(
                    StgDirectQuoteTargets.KEY_STOCK_LIST,
                    converted as ArrayList<Parcelable>,
                )
            }.isSuccess
            if (ok) return true
            GLog.w("直达标准行情：putParcelableArrayList 失败，改用 putSerializable")
        }
        return runCatching {
            bundle.putSerializable(StgDirectQuoteTargets.KEY_STOCK_LIST, converted)
            true
        }.getOrDefault(false)
    }

    /** 写入单只 `stockInfo`（中间页「标准行情」契约），Parcelable 优先，退回 Serializable */
    private fun putStockInfo(bundle: Bundle, one: Any): Boolean {
        if (one is Parcelable) {
            val ok = runCatching { bundle.putParcelable(StgDirectQuoteTargets.KEY_STOCK_INFO, one) }
                .isSuccess
            if (ok) return true
            GLog.w("直达标准行情：putParcelable(stockInfo) 失败，改用 putSerializable")
        }
        if (one is java.io.Serializable) {
            return runCatching {
                bundle.putSerializable(StgDirectQuoteTargets.KEY_STOCK_INFO, one)
                true
            }.getOrDefault(false)
        }
        return false
    }

    // ------------------------------------------------------------ 类型转换

    private fun resolveItemClass(env: FeatureEnv, list: List<*>?, clicked: Any?): Class<*>? {
        intentStockInfoCls?.let { return it }
        if (resolveFailed) return null

        Reflect.loadClass(StgDirectQuoteTargets.CLS_INTENT_STOCK_INFO, env.classLoader)?.let {
            intentStockInfoCls = it
            return it
        }
        // 类名候选失败也不要永久放弃：列表里第一条的真实类型就是它
        val sample = list?.firstOrNull() ?: clicked
        if (sample != null) {
            val simple = sample.javaClass.simpleName
            GLog.w("直达标准行情：没找到 ${StgDirectQuoteTargets.CLS_INTENT_STOCK_INFO}，样本类型=$simple")
        }
        resolveFailed = true
        return null
    }

    /**
     * `StockInfo` → `IntentStockInfo`。
     *
     * 优先用**参数类型能接受该对象的单参构造器**（实测 App 自带 `IntentStockInfo(StockInfo)`），
     * 失败再退到 `(String code, String market)`。
     */
    private fun toIntentStockInfo(cls: Class<*>, item: Any?): Any? {
        if (item == null) return null
        if (cls.isInstance(item)) return item

        cls.declaredConstructors
            .firstOrNull { it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(item.javaClass) }
            ?.let { c ->
                runCatching {
                    c.isAccessible = true
                    c.newInstance(item)
                }.getOrNull()?.let { return it }
            }

        val cam = codeAndMarket(item) ?: return null
        val dot = cam.indexOf('.')
        if (dot <= 0) return null
        val code = cam.substring(0, dot)
        val market = cam.substring(dot + 1)

        cls.declaredConstructors
            .firstOrNull {
                it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.parameterTypes[1] == String::class.java
            }
            ?.let { c ->
                runCatching {
                    c.isAccessible = true
                    c.newInstance(code, market)
                }.getOrNull()?.let { return it }
            }
        return null
    }

    // ------------------------------------------------------------ 字段读取

    /** 读出「代码.市场」，失败返回 null */
    private fun codeAndMarket(item: Any?): String? {
        if (item == null) return null

        for (f in StgDirectQuoteTargets.CAM_FIELDS) {
            val v = Reflect.readField(item, f) as? String
            if (!v.isNullOrEmpty()) return v
        }

        var code: String? = null
        for (f in StgDirectQuoteTargets.CODE_FIELDS) {
            val v = Reflect.readField(item, f) as? String
            if (!v.isNullOrEmpty()) {
                code = v
                break
            }
        }
        if (code == null) return null

        for (f in StgDirectQuoteTargets.MARKET_FIELDS) {
            val v = Reflect.readField(item, f) ?: continue
            val s = v.toString()
            if (s.isNotEmpty()) return "$code.$s"
        }
        return null
    }
}

/**
 * 「原始列表 → 转换后列表」的下标映射。
 *
 * `toIntentStockInfo` 失败的元素会被跳过，所以 `converted` 与 `originalList` 不等长。
 * 兜底查下标时必须用转换那一轮记下的映射 —— 拿原始下标去索引 `converted`
 * 会指到另一只股票（点第 N 只、打开第 M 只，不崩但进错股）。
 */
internal object DirectQuoteIndex {

    /**
     * @param entries 原始顺序上的（代码.市场, 是否转换成功）
     * @return 代码.市场 → 在 converted 里的下标
     */
    fun build(entries: List<Pair<String?, Boolean>>): Map<String, Int> {
        val out = HashMap<String, Int>(entries.size * 2)
        var convertedSize = 0
        for ((cam, ok) in entries) {
            if (!ok) continue
            if (cam != null) out[cam] = convertedSize
            convertedSize++
        }
        return out
    }
}
