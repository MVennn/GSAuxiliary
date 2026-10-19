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
                            if (env.config.marketStockFilter) {
                                (chain.thisObject as? Dialog)?.let { maybeInject(it) }
                            }
                        }.onFailure { GLog.w("$id：面板注入异常", it) }
                        chain.proceed()
                    }
            )
        }
        if (methods.isNotEmpty()) GLog.i("$id：已挂 Dialog.show ×${methods.size}")
        return methods.size
    }

    private fun maybeInject(dialog: Dialog) {
        val panel = dialogPanel(dialog) ?: return
        val res = panel.resources

        // 幂等：宿主可能复用同一个 Dialog 实例，重复 show 时只刷新勾选态
        val existing = panel.findViewWithTag<View>(FilterPanelIds.TAG_INJECTED_BLOCK)
        if (existing != null) {
            refreshInjected(existing, res, filters(panel.context))
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
        StockFilterPanelUi.wrapClick(okBtn) {
            var applied = opened
            for (row in FilterRow.DIRECTION_ROWS) {
                val checked = boxes[row]?.isChecked ?: state[row] ?: false
                state[row] = checked
                applied = applied.set(row, checked)
            }
            StockFilterStore.save(panel.context, applied)
            GLog.i("$id：筛选条件已生效（${summaryOf(applied)}）")
        }

        GLog.i("$id：已在宿主筛选面板里注入「${FilterRow.SECTION_DIRECTION}」三行（面板标题「$title」）")
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
        var rows = 0
        var hidden = 0
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            if (child.findViewById<View>(codeId) == null) continue   // 不是股票行
            rows++
            if (keepRow(child, f, codeId, percentId)) {
                if (isCollapsed(child)) patcher.restore(child)
            } else {
                hidden++
                patcher.collapse(child, "排行榜行（${summaryOf(f)}）")
            }
        }
        if (rows >= MarketFilterTargets.RANKING_MIN_ROWS && hidden > 0 && GLog.isDebug) {
            GLog.d("$id：排行榜 $rows 行 → 收起 $hidden 行（${summaryOf(f)}）")
        }
        return true
    }

    /** 这一行该不该留下（只判「涨跌与自选」；类型那五行是宿主自己筛的） */
    private fun keepRow(row: View, f: StockFilters, codeId: Int, percentId: Int): Boolean {
        if (!f.directionFiltering) return true
        val code = textOf(row, codeId)
        val percent = parsePercent(textOf(row, percentId))
        val inZx = ZxWatchlist.isInWatchlist(code, StockTypes.marketOf(code))
        return f.keepsDirection(inZx, percent)
    }

    private fun textOf(row: View, id: Int): String? =
        runCatching { row.findViewById<View>(id) as? TextView }.getOrNull()?.text?.toString()

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
