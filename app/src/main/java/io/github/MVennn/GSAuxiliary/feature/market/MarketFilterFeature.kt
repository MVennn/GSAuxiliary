package io.github.MVennn.GSAuxiliary.feature.market

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.filter.FilterPanelIds
import io.github.MVennn.GSAuxiliary.feature.filter.FilterRow
import io.github.MVennn.GSAuxiliary.feature.filter.StockFilterPanelUi
import io.github.MVennn.GSAuxiliary.feature.filter.StockFilterStore
import io.github.MVennn.GSAuxiliary.feature.filter.StockFilters
import io.github.MVennn.GSAuxiliary.feature.filter.StockTypes
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiClean
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiCleanFeature
import io.github.MVennn.GSAuxiliary.feature.watchlist.ZxWatchlist
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * 功能：**行情页「沪深京A」筛选面板里的「涨跌与自选」三行 + 按它们筛排行榜**。
 *
 * ## 做什么（两件事）
 *
 * 1. **面板**：宿主那颗漏斗（`FilterDiaglog`）里原有「沪深A主板」一组五行
 *    （创业板 / 科创板 / 北证A / 未开板次新 / ST股），我们在这组**下面**追加
 *    `涨跌与自选` 三行（涨家股票 / 跌家股票 / 自选股票）—— 与策略详情页那颗
 *    面板里的三行**同一份状态、同一套文案**（`feature/filter/` 共享模型）。
 *    宿主原有的行、它的适配器与「确定」监听一个字都不动：点确定时我们的三行
 *    按宿主面板的规矩落盘并立即生效，点取消则整份丢弃（与宿主自己的行同理）。
 * 2. **列表**：按这三行把沪深京A排行榜里不匹配的行**收起**（高度归零 + GONE）。
 *    排行榜的每一行是外层 `#rv_markets_hs` 的一个子项（见 [MarketFilterTargets]），
 *    所以「筛掉」= 收起那一个子项；改视图走 `ViewPatcher`，关掉开关一键还原。
 *
 * ## 语义（与策略详情页那三行完全一致，见 [StockFilters] 的注释）
 *
 * 自选优先、平盘不属于任何类别、三个都不勾 = 不筛。
 * 「股票类型」那五行是宿主自己的过滤，我们不做也不碰 —— 两边各管一维，天然取交集。
 *
 * ## 为什么收起行而不是「换 adapter 数据」
 *
 * 排行榜的 adapter 是宿主自己的（`MarketsHQAdapter`），数据项与整页其它子项
 * （指数概览 / 市场概览 / 板块…）混在同一个列表里 —— 动它的数据等于把整页
 * 所有子项的序号都改一遍，宿主自己的点击 / 刷新逻辑全部悬空。
 * 收起行只改「这一个子项的可见性与高度」，宿主的账本一个字都不动。
 */
class MarketFilterFeature : UiCleanFeature() {

    override val id = "market_stock_filter"
    override val title = "行情市场筛选涨跌与自选"

    override val pages = MarketTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.marketStockFilter

    /**
     * 我们包过「确定」按钮的那些 → **宿主原本的点击监听**（弱引用）。
     *
     * 撤包装时要把 original 放回去，否则宿主自己的「确定」行为永久失联
     * （`StockFilterPanelUi.wrapClick` 现在会把它返回给我们）。
     */
    private val wrappedOkButtons =
        Collections.synchronizedMap(WeakHashMap<TextView, View.OnClickListener>())

    /**
     * 最近一次注入过的那个面板（弱引用）。
     *
     * 有了它，关开关时能**立刻**把三行从正开着的面板里撤掉 ——
     * 不用等用户下次打开面板才发现「三行还在」。
     */
    @Volatile private var injectedPanel: WeakReference<ViewGroup>? = null

    /**
     * 上一轮有没有收起过行。
     *
     * 用途：方向筛选关掉时**要还原**之前收起的行（否则行会一直藏着）。
     * 只在「确实藏过」时才遍历还原 —— 稳态下（没藏过）连遍历都省掉。
     */
    @Volatile private var hadHiddenRows = false

    override val idNames = MarketTargets.PAGE_ANCHOR_ID_CANDIDATES + arrayOf(
        MarketFilterTargets.RANKING_LIST_ID,
        MarketFilterTargets.RANKING_CODE_ID,
        MarketFilterTargets.RANKING_PERCENT_ID,
        FilterPanelIds.ID_PANEL_LIST,
        FilterPanelIds.ID_PANEL_OK,
    )

    override fun install(env: FeatureEnv, bag: HookBag) {
        super.install(env, bag)
        ZxWatchlist.init(env.classLoader)
        val n = hookDialogShow(env, bag)
        if (n == 0) GLog.e("$id：没挂上 Dialog.show（行情筛选面板注入不了）")
    }

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        StockFilterStore.invalidate()
        // ⚠️ 关掉开关时**立刻**把注入到宿主面板里的东西撤掉（规则 1.1）。
        // 面板可能正开着 —— 那就把正开着的那张也撤了，用户不用关掉重开才发现三行还在。
        if (!cfg.marketStockFilter) {
            injectedPanel?.get()?.let { removeInjected(it) }
            restoreOkButtons()
        }
        super.onConfigChanged(env, cfg)
    }

    // ------------------------------------------------------------------ 面板：注入三行

    /**
     * 挂 `android.app.Dialog#show` 的**前置**钩子。
     *
     * 前置的理由与三点菜单注入相同（见 `docs/FEATURE-STG-FILTER.md`）：面板一 show，
     * 窗口就按当时的内容量尺寸并交给 WindowManager，之后再往里 `addView` 会被裁掉。
     *
     * ⚠️ `Dialog` 是框架类、全 App 都在弹，所以判定**只看视图**：
     * 内容视图里同时找得到 `#rv_markets` 与 `#tv_ok`，且根视图上没有我们自己的面板标记
     * （策略详情页那张面板是同一份布局，靠 `TAG_OUR_PANEL` 区分）。
     */
    private fun hookDialogShow(env: FeatureEnv, bag: HookBag): Int {
        val cls = Reflect.loadClass("android.app.Dialog", env.classLoader) ?: return 0
        val methods = Reflect.methodsNamed(cls, "show")
        for (m in methods) {
            bag.add(
                env.xposed.hook(m)
                    .setId("$id:dialogShow")
                    .intercept { chain ->
                        runCatching {
                            // ⚠️ **无条件进来**：开关关掉时也要进来「把注入的东西撤掉」。
                            // 原来在这里判 `env.config.marketStockFilter`，开关一关就短路，
                            // 于是注入的三行与被包的「确定」永远撤不掉（规则 1.1）。
                            (chain.thisObject as? Dialog)?.let { maybeInject(env, it) }
                        }.onFailure { GLog.w("$id：面板注入异常", it) }
                        chain.proceed()
                    }
            )
        }
        if (methods.isNotEmpty()) GLog.i("$id：已挂 Dialog.show ×${methods.size}")
        return methods.size
    }

    private fun maybeInject(env: FeatureEnv, dialog: Dialog) {
        val panel = dialogPanel(dialog) ?: return
        val res = panel.resources

        // 幂等：宿主可能复用同一个 Dialog 实例，重复 show 时只刷新勾选态
        val existing = panel.findViewWithTag<View>(FilterPanelIds.TAG_INJECTED_BLOCK)
        if (existing != null) {
            if (env.config.marketStockFilter) {
                refreshInjected(existing, res, filters(panel.context))
            } else {
                // 开关关了 → 先撤掉上一次注入的
                removeInjected(panel)
            }
            return
        }
        // 没注入过，但开关是关的 → 把可能残留的「确定」包装撤掉（上一任实例注入的）
        if (!env.config.marketStockFilter) {
            restoreOkButtons()
            return
        }

        val rv = StockFilterPanelUi.find<View>(panel, res, FilterPanelIds.ID_PANEL_LIST) ?: return
        val okBtn = StockFilterPanelUi.find<TextView>(panel, res, FilterPanelIds.ID_PANEL_OK) ?: return
        val title = StockFilterPanelUi.find<TextView>(panel, res, FilterPanelIds.ID_PANEL_TITLE)
            ?.text?.toString().orEmpty()

        val host = StockFilterPanelUi.wrapWithVerticalHost(rv) ?: run {
            GLog.w("$id：面板里换不出行容器，注入放弃")
            return
        }
        val block = LinearLayout(panel.context).apply {
            orientation = LinearLayout.VERTICAL
            tag = FilterPanelIds.TAG_INJECTED_BLOCK
        }
        host.addView(
            block,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        StockFilterPanelUi.addSectionHeader(panel, res, block, FilterRow.SECTION_DIRECTION, host)

        // 面板打开时的状态：三行按共享状态预勾（改完点确定才落盘，点取消整份丢弃）
        val opened = filters(panel.context)
        val state = HashMap<FilterRow, Boolean>()
        val boxes = HashMap<FilterRow, CheckBox>()
        val inflater = LayoutInflater.from(panel.context)
        for (row in FilterRow.DIRECTION_ROWS) {
            val checked = opened[row]
            state[row] = checked
            val view = StockFilterPanelUi.buildRow(inflater, res, block, row.label, checked) { now ->
                state[row] = now
            }
            if (view != null) {
                StockFilterPanelUi.find<CheckBox>(view, res, FilterPanelIds.ID_ITEM_CHECK)
                    ?.let { boxes[row] = it }
            }
        }

        // 「确定」：先按我们的三行落盘 + 立即生效，再把宿主的确定原样放行
        // （宿主会应用它自己的类型过滤并关掉面板）。类型那五项是宿主自己的，保持不变。
        // ⚠️ original 必须**存下来**：撤包装时要把宿主自己的监听放回去，
        // 抓进闭包就没人拿得到了（`wrapClick` 现在会把它返回）。
        val original = StockFilterPanelUi.wrapClick(okBtn) {
            var applied = opened
            for (row in FilterRow.DIRECTION_ROWS) {
                val checked = boxes[row]?.isChecked ?: state[row] ?: false
                state[row] = checked
                applied = applied.set(row, checked)
            }
            StockFilterStore.save(panel.context, applied)
            GLog.i("$id：筛选条件已生效（${summaryOf(applied)}）")
        }
        if (original != null) wrappedOkButtons[okBtn] = original
        injectedPanel = WeakReference(panel)

        GLog.i("$id：已在宿主筛选面板里注入「${FilterRow.SECTION_DIRECTION}」三行（面板标题「$title」）")
    }

    /**
     * 撤掉注入到宿主面板里的那一块，并把「确定」按钮的监听还原成宿主自己的。
     *
     * 规则 1.1：install 落下的每一笔都要能被撤销。原先**只做不撤** ——
     * `TAG_INJECTED_BLOCK` 的注释写着「幂等与撤销都靠它」，可全仓没有任何一处
     * 按这个 tag 撤销。结果：关掉开关后三行还在（用户以为开关没生效），
     * 而且「确定」仍被我们的包装器包着 → 用户在面板里勾的方向仍会落盘进
     * **策略详情页共用**的那份筛选状态 → 关掉 A 功能、B 功能的行为被改了。
     */
    private fun removeInjected(panel: ViewGroup) {
        val block = panel.findViewWithTag<View>(FilterPanelIds.TAG_INJECTED_BLOCK) ?: return
        runCatching { (block.parent as? ViewGroup)?.removeView(block) }
            .onFailure { GLog.w("$id：撤掉注入块失败", it) }
        restoreOkButtons()
        injectedPanel = null
        GLog.i("$id：已撤掉面板里的「${FilterRow.SECTION_DIRECTION}」注入块")
    }

    /** 把「确定」按钮的监听还原成宿主自己的（我们包过的那些） */
    private fun restoreOkButtons() {
        val entries = synchronized(wrappedOkButtons) {
            val list = wrappedOkButtons.entries.toList()
            wrappedOkButtons.clear()
            list
        }
        for ((btn, original) in entries) {
            runCatching { btn.setOnClickListener(original) }
                .onFailure { GLog.w("$id：还原「确定」按钮监听失败", it) }
        }
    }

    /** 宿主行情筛选面板的内容视图；不是它（或是我方自己的面板）就返回 null */
    private fun dialogPanel(dialog: Dialog): ViewGroup? {
        val content = runCatching {
            dialog.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
        }.getOrNull() ?: return null
        val panel = content.getChildAt(0) as? ViewGroup ?: return null
        if (panel.tag == FilterPanelIds.TAG_OUR_PANEL) return null   // 策略详情页那张，不碰
        val res = panel.resources
        if (StockFilterPanelUi.find<View>(panel, res, FilterPanelIds.ID_PANEL_LIST) == null) return null
        if (StockFilterPanelUi.find<View>(panel, res, FilterPanelIds.ID_PANEL_OK) == null) return null
        return panel
    }

    /**
     * 刷新注入块的勾选态（宿主复用同一个 Dialog 时用）。
     *
     * 按**行名**认行（而不是按子项序号）：分组头克隆失败时结构会少一位，
     * 按序号读就会整块错位 —— 名字对不上的子项直接跳过。
     */
    private fun refreshInjected(block: View, res: Resources, f: StockFilters) {
        val host = block as? ViewGroup ?: return
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i) ?: continue
            val label = StockFilterPanelUi.find<TextView>(child, res, FilterPanelIds.ID_ITEM_NAME)
                ?.text?.toString() ?: continue
            val row = FilterRow.DIRECTION_ROWS.firstOrNull { it.label == label } ?: continue
            val box = StockFilterPanelUi.find<CheckBox>(child, res, FilterPanelIds.ID_ITEM_CHECK)
            runCatching { box?.isChecked = f[row] }
        }
    }

    // ------------------------------------------------------------------ 列表：按条件收起行

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val ids = UiClean.idsFor(activity, MarketTargets.TARGET_PKG)
        if (!MarketTargets.onMarketPage(root, ids)) return true      // 不在行情页：什么都不做

        val listId = ids.of(MarketFilterTargets.RANKING_LIST_ID)
        val codeId = ids.of(MarketFilterTargets.RANKING_CODE_ID)
        val percentId = ids.of(MarketFilterTargets.RANKING_PERCENT_ID)
        if (listId == 0 || codeId == 0 || percentId == 0) return true
        val rv = root.findViewById<ViewGroup>(listId) ?: return true

        val f = filters(activity)
        // 不筛方向时一行都不用动 —— 直接返回，连遍历都省掉
        if (!f.directionFiltering) {
            if (hadHiddenRows) {
                hadHiddenRows = false
                restoreAllRows(rv)
            }
            return true
        }

        var rows = 0
        var hidden = 0
        // 整趟只算一次那句解释（Kotlin 实参在调用前求值，原先每行都拼一次）
        val reason = collapseReason(f)
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val views = rowViews(child, codeId, percentId) ?: continue   // 不是股票行
            rows++
            if (keepRow(views, f)) {
                if (isCollapsed(child)) patcher.restore(child)
            } else {
                hidden++
                patcher.collapse(child, reason)
            }
        }
        hadHiddenRows = hidden > 0
        if (rows >= MarketFilterTargets.RANKING_MIN_ROWS && hidden > 0 && GLog.isDebug) {
            GLog.d("$id：排行榜 $rows 行 → 收起 $hidden 行（${summaryOf(f)}）")
        }
        return true
    }

    /** 把之前收起的行全部还原（方向筛选关掉时用） */
    private fun restoreAllRows(rv: ViewGroup) {
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            if (isCollapsed(child)) patcher.restore(child)
        }
    }

    /**
     * 一行里的两个 TextView（代码 / 涨跌幅）。
     *
     * **只缓存视图引用，绝不缓存文本**：文本随行情刷新变，缓存它 = 拿旧涨跌幅 = 改判定语义。
     * 键是**行视图对象本身**（弱引用）—— 列表复用时行视图会被换掉内容，
     * 但它身上的那两个 TextView 引用不变，所以复用是安全的。
     */
    private class RowViews(val code: TextView, val percent: TextView?)

    private val rowViewsCache =
        Collections.synchronizedMap(WeakHashMap<View, RowViews>())

    /**
     * 取一行的「代码」TextView（判定是不是股票行）与「涨跌幅」TextView。
     *
     * ⚠️ **是不是股票行只看代码那个**（与原判定一致）：涨跌幅那个可能不存在，
     * 用它当判据会让这一行不再被收起/还原 —— 那是行为变化，不能顺手改。
     * 涨跌幅取不到时按「读不到涨跌幅」处理（`parsePercent(null)` = null = 平盘）。
     */
    private fun rowViews(row: View, codeId: Int, percentId: Int): RowViews? {
        rowViewsCache[row]?.let { return it }
        val codeTv = row.findViewById<TextView>(codeId) ?: return null
        val pctTv = row.findViewById<TextView>(percentId)
        val v = RowViews(codeTv, pctTv)
        rowViewsCache[row] = v
        return v
    }

    /**
     * 收起行时写在日志里的那句解释。
     *
     * ⚠️ **整趟算一次**：Kotlin 的实参在调用前求值，原先每行都拼一次
     * `"排行榜行（${summaryOf(f)}）"` —— 每帧每行一次 String 分配，
     * 而 `ViewPatcher.collapse` 内部的日志是等级保护的（规则 2.5），
     * 拼串却在调用点已经发生，等级调到「关闭」也省不掉。
     */
    private fun collapseReason(f: StockFilters): String = "排行榜行（${summaryOf(f)}）"

    /** 这一行该不该留下（只判「涨跌与自选」；类型那五行是宿主自己筛的） */
    private fun keepRow(views: RowViews, f: StockFilters): Boolean {
        val code = views.code.text?.toString()
        val percent = parsePercent(views.percent?.text?.toString())
        val inZx = ZxWatchlist.isInWatchlist(code, StockTypes.marketOf(code))
        return f.keepsDirection(inZx, percent)
    }

    private fun isCollapsed(v: View): Boolean =
        v.visibility == View.GONE && v.layoutParams?.height == 0

    private fun filters(ctx: Context): StockFilters = StockFilterStore.load(ctx)

    /** 日志用：把生效条件说成一句话（只列勾了的） */
    private fun summaryOf(f: StockFilters): String = buildString {
        for (row in FilterRow.DIRECTION_ROWS) {
            if (!f[row]) continue
            if (isNotEmpty()) append('/')
            append(row.label)
        }
        if (isEmpty()) append("未筛")
    }

    private companion object {
        /** `+9.99%` / `-1.45%` / `0.00%` / `--`（与策略详情页同一个口径） */
        fun parsePercent(s: String?): Double? {
            val t = s?.trim()?.removeSuffix("%")?.replace("+", "") ?: return null
            if (t.isEmpty() || t == "--" || t == "-") return null
            return t.toDoubleOrNull()
        }
    }
}
