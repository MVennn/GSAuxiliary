package io.github.MVennn.GSAuxiliary.feature.stgfilter

import android.app.Activity
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.feature.filter.FilterPanelIds
import io.github.MVennn.GSAuxiliary.feature.filter.FilterRow
import io.github.MVennn.GSAuxiliary.feature.filter.StockFilterPanelUi
import io.github.MVennn.GSAuxiliary.feature.filter.StockFilterStore
import io.github.MVennn.GSAuxiliary.feature.filter.StockFilters
import io.github.MVennn.GSAuxiliary.feature.filter.describe

/**
 * 策略详情页的「筛选股票」面板。
 *
 * ## 「复用软件内部现有筛选组件的 UI」是怎么做到的
 *
 * 不是照着截图重画，而是**真的把目标 App 自己的布局资源拿过来 inflate**
 * （外壳 `dialog_marketfliter` + 每一行 `item_market_filter`，常量见 [FilterPanelIds]）
 * —— 与行情页「沪深京A」那颗漏斗同源，App 换肤 / 深色模式自动跟随。
 *
 * ## 面板里有什么（2.18.0 起两组共八行）
 *
 * ```
 * 筛选股票                        ← tv1
 * 勾选后对应的条件才会生效          ← tv2
 * ── 涨跌与自选 ──                ← tv3（宿主那行分组头，文案改成这组）
 *   涨家股票 / 跌家股票 / 自选股票
 * ── 股票类型 ──                  ← 克隆 tv3 的分组头
 *   创业板 / 科创板 / 北证A / 未开板次新 / ST股
 * [重置] [确定]
 * ```
 *
 * 两组条件的语义（勾选 = 什么）见 [StockFilters] 的注释：
 * 方向是「未勾选的类别不显示」，类型是「有一个类型没勾就排除」。
 *
 * ## 文案为什么硬编码中文
 *
 * 这段代码跑在**目标进程**里，模块的 `R.string` 在那边不存在（模块 APK 的资源没有
 * 并进目标的资源表），只能写字面量 —— 与同项目 `StgSelectAllFeature` 注入「全选」
 * 按钮的做法一致。
 */
internal object StgFilterPanel {

    private const val TAG = "筛选股票"

    /** 打开筛选面板。所有异常都吞掉并记日志，绝不抛给宿主。 */
    fun show(activity: Activity, current: StockFilters, onApply: (StockFilters) -> Unit) {
        runCatching { showInner(activity, current, onApply) }
            .onFailure { GLog.w("$TAG：打不开筛选面板", it) }
    }

    private fun showInner(activity: Activity, current: StockFilters, onApply: (StockFilters) -> Unit) {
        val res = activity.resources
        val panelLayoutId = StockFilterPanelUi.layoutId(res, FilterPanelIds.LAYOUT_FILTER_PANEL)
        if (panelLayoutId == 0) {
            GLog.w(
                "$TAG：找不到布局 ${FilterPanelIds.LAYOUT_FILTER_PANEL} / " +
                        "${FilterPanelIds.LAYOUT_FILTER_ITEM}（App 版本可能已变）"
            )
            return
        }

        val inflater = LayoutInflater.from(activity)
        val root = inflater.inflate(panelLayoutId, null) as? ViewGroup ?: run {
            GLog.w("$TAG：面板布局根不是 ViewGroup")
            return
        }
        // 标记「这张面板是我们弹的」：行情侧同样挂 Dialog.show 的功能靠它放过我们
        // （同一份布局、同一批 id，不标记就分不清是谁的面板）
        runCatching { root.tag = FilterPanelIds.TAG_OUR_PANEL }
        StockFilterPanelUi.ensurePanelBackground(activity, root, TAG)

        // ---- 文案 ----
        StockFilterPanelUi.find<TextView>(root, res, FilterPanelIds.ID_PANEL_TITLE)?.text = "筛选股票"
        StockFilterPanelUi.find<TextView>(root, res, FilterPanelIds.ID_PANEL_SUBTITLE)?.text =
            "勾选后对应的条件才会生效"
        StockFilterPanelUi.find<TextView>(root, res, FilterPanelIds.ID_PANEL_SECTION)?.text =
            FilterRow.SECTION_DIRECTION
        val resetBtn = StockFilterPanelUi.find<TextView>(root, res, FilterPanelIds.ID_PANEL_CANCEL)
        val okBtn = StockFilterPanelUi.find<TextView>(root, res, FilterPanelIds.ID_PANEL_OK)
        if (resetBtn == null || okBtn == null) {
            GLog.w("$TAG：面板里找不到 ${FilterPanelIds.ID_PANEL_CANCEL} / ${FilterPanelIds.ID_PANEL_OK}")
            return
        }
        resetBtn.text = "重置"
        StockFilterPanelUi.hideSectionArrow(root)

        // ---- 行容器：RecyclerView 原地换成 LinearLayout ----
        val original = StockFilterPanelUi.find<View>(root, res, FilterPanelIds.ID_PANEL_LIST)
        val rowsHost = original?.let { StockFilterPanelUi.swapToLinearHost(it) }
        if (rowsHost == null) {
            GLog.w("$TAG：面板里找不到 ${FilterPanelIds.ID_PANEL_LIST}")
            return
        }

        // ---- 两组装行：方向（tv3 那组）+ 类型（克隆一个同名分组头）----
        val state = HashMap<FilterRow, Boolean>()
        val boxes = HashMap<FilterRow, CheckBox>()
        for (row in FilterRow.entries) {
            if (row == FilterRow.TYPE_ROWS.first()) {
                StockFilterPanelUi.addSectionHeader(root, res, rowsHost, FilterRow.SECTION_TYPE, rowsHost)
            }
            val checked = current[row]
            state[row] = checked
            val view = StockFilterPanelUi.buildRow(
                inflater, res, rowsHost, row.label, checked,
            ) { now -> state[row] = now }
            if (view != null) {
                StockFilterPanelUi.find<CheckBox>(view, res, FilterPanelIds.ID_ITEM_CHECK)
                    ?.let { boxes[row] = it }
            }
        }

        // ---- 窗口：与行情页一致，从底部往上弹 ----
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(FilterPanelIds.PANEL_DIM)
        }
        dialog.setCanceledOnTouchOutside(true)

        /** 以当前控件状态为准收集一份条件（勾选框本身可能被直接点中） */
        fun collect(): StockFilters {
            var f = StockFilters.OFF
            for (row in FilterRow.entries) {
                val checked = boxes[row]?.isChecked ?: state[row] ?: f[row]
                state[row] = checked
                f = f.set(row, checked)
            }
            return f
        }

        // 「重置」：清掉全部条件（方向全不勾 + 类型全勾 = 不筛）并**立即生效**，面板不关
        // —— 关掉就出现「重置完还想再选却得重开」的别扭
        resetBtn.setOnClickListener {
            for (row in FilterRow.entries) {
                state[row] = StockFilters.OFF[row]
                boxes[row]?.isChecked = StockFilters.OFF[row]
            }
            StockFilterStore.save(activity, StockFilters.OFF)
            onApply(StockFilters.OFF)
        }

        // 「确定」：保持原样 —— 落盘 + 生效 + 关闭
        okBtn.setOnClickListener {
            val f = collect()
            StockFilterStore.save(activity, f)
            onApply(f)
            runCatching { dialog.dismiss() }
        }

        dialog.show()
        GLog.i("$TAG：筛选面板已打开（${current.describe()}）")
    }
}
