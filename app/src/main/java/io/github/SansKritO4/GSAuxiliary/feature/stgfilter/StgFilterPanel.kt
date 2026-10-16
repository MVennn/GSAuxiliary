package io.github.SansKritO4.GSAuxiliary.feature.stgfilter

import android.app.Activity
import android.app.Dialog
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.SansKritO4.GSAuxiliary.core.GLog

/**
 * 策略详情页的「筛选股票」面板。
 *
 * ## 「复用软件内部现有筛选组件的 UI」是怎么做到的
 *
 * 不是照着截图重画，而是**真的把目标 App 自己的布局资源拿过来 inflate**：
 *
 *  - 面板外壳 = `res/layout/dialog_marketfliter.xml`（行情页「沪深京A」左侧漏斗点出来的那个）
 *  - 每一行 = `res/layout/item_market_filter.xml`
 *
 * 于是圆角背景、标题字号、副标题底块、分隔线、行高、勾选框样式、底部两颗按钮
 * 全部与行情页那颗漏斗**同源**，App 换肤 / 深色模式也跟着走。
 *
 * 需要改的三处（需求指定）：
 *  1. 标题 / 副标题 / 分组头文案换成「筛选股票」这套；
 *  2. `#tv_cancel` 的文案 `取消` → `重置`（`#tv_ok` 的 `确定` 原样不动）；
 *  3. 行容器原本是 `RecyclerView #rv_markets`，它的 LayoutManager 与 adapter 由 App 代码
 *     在运行时设 —— 我们只 inflate 布局拿不到那段代码，所以把它**原地换成一个垂直
 *     `LinearLayout`**（沿用同一个 `LayoutParams` 实例，`ConstraintLayout` 的约束一条不丢），
 *     再把三行塞进去。这样模块编译期也不必依赖 androidx.recyclerview。
 *
 * 分组折叠箭头（`tv3` 右边那颗无 id 的 ImageView）**隐藏**：原版那个分组可折叠，
 * 我们这里只有固定三行，留一个点了没反应的箭头是骗人。
 *
 * ## 文案为什么硬编码中文
 *
 * 这段代码跑在**目标进程**里，模块的 `R.string` 在那边不存在（模块 APK 的资源没有
 * 并进目标的资源表），只能写字面量 —— 与同项目 `StgSelectAllFeature` 注入「全选」
 * 按钮的做法一致。
 */
internal object StgFilterPanel {

    /** 面板里的三行，顺序即显示顺序 */
    private val ROW_LABELS = arrayOf("涨家股票", "跌家股票", "自选股票")

    /** 打开筛选面板。所有异常都吞掉并记日志，绝不抛给宿主。 */
    fun show(activity: Activity, current: StgFilters, onApply: (StgFilters) -> Unit) {
        runCatching { showInner(activity, current, onApply) }
            .onFailure { GLog.w("筛选股票：打不开筛选面板", it) }
    }

    private fun showInner(activity: Activity, current: StgFilters, onApply: (StgFilters) -> Unit) {
        val res = activity.resources
        val panelLayoutId = res.layoutId(StgStockFilterTargets.LAYOUT_FILTER_PANEL)
        val itemLayoutId = res.layoutId(StgStockFilterTargets.LAYOUT_FILTER_ITEM)
        if (panelLayoutId == 0 || itemLayoutId == 0) {
            GLog.w(
                "筛选股票：找不到布局 ${StgStockFilterTargets.LAYOUT_FILTER_PANEL} / " +
                        "${StgStockFilterTargets.LAYOUT_FILTER_ITEM}（App 版本可能已变）"
            )
            return
        }

        val inflater = LayoutInflater.from(activity)
        val root = inflater.inflate(panelLayoutId, null) as? ViewGroup ?: run {
            GLog.w("筛选股票：面板布局根不是 ViewGroup")
            return
        }
        ensurePanelBackground(activity, root)

        // ---- 文案 ----
        root.find<TextView>(res, StgStockFilterTargets.ID_PANEL_TITLE)?.text = "筛选股票"
        root.find<TextView>(res, StgStockFilterTargets.ID_PANEL_SUBTITLE)?.text =
            "勾选后对应的条件才会生效"
        root.find<TextView>(res, StgStockFilterTargets.ID_PANEL_SECTION)?.text = "筛选条件"
        val resetBtn = root.find<TextView>(res, StgStockFilterTargets.ID_PANEL_CANCEL)
        val okBtn = root.find<TextView>(res, StgStockFilterTargets.ID_PANEL_OK)
        if (resetBtn == null || okBtn == null) {
            GLog.w("筛选股票：面板里找不到 ${StgStockFilterTargets.ID_PANEL_CANCEL} / " +
                    StgStockFilterTargets.ID_PANEL_OK)
            return
        }
        resetBtn.text = "重置"
        hideSectionArrow(root)

        // ---- 行容器：RecyclerView 原地换成 LinearLayout ----
        val original = root.find<View>(res, StgStockFilterTargets.ID_PANEL_LIST)
        val rowsHost = original?.let { swapToLinearHost(it) }
        if (rowsHost == null) {
            GLog.w("筛选股票：面板里找不到 ${StgStockFilterTargets.ID_PANEL_LIST}")
            return
        }

        // ---- 三行 ----
        val n = ROW_LABELS.size
        val state = booleanArrayOf(current.up, current.down, current.zx)
        val boxes = arrayOfNulls<CheckBox>(n)
        for (i in 0 until n) {
            val item = inflater.inflate(itemLayoutId, rowsHost, false) ?: continue
            item.find<TextView>(res, StgStockFilterTargets.ID_ITEM_NAME)?.text = ROW_LABELS[i]
            val box = item.find<CheckBox>(res, StgStockFilterTargets.ID_ITEM_CHECK)
            boxes[i] = box
            box?.isChecked = state[i]
            // 整行可点：点行即切换勾选（与行情页那颗漏斗的交互一致）
            item.isClickable = true
            item.setOnClickListener {
                val b = boxes[i] ?: return@setOnClickListener
                b.isChecked = !b.isChecked
                state[i] = b.isChecked
            }
            rowsHost.addView(item)
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
            w.setDimAmount(StgStockFilterTargets.PANEL_DIM)
        }
        dialog.setCanceledOnTouchOutside(true)

        /** 以三行当前勾选状态为准，收集一份筛选条件 */
        fun collect(): StgFilters {
            for (i in 0 until n) boxes[i]?.let { state[i] = it.isChecked }
            return StgFilters(up = state[0], down = state[1], zx = state[2])
        }

        // 「重置」：清空三个条件并**立即生效**，面板不关
        // —— 关掉就出现「重置完还想再选却得重开」的别扭
        resetBtn.setOnClickListener {
            for (i in 0 until n) {
                state[i] = false
                boxes[i]?.isChecked = false
            }
            StgFilterStore.save(activity, StgFilters.OFF)
            onApply(StgFilters.OFF)
        }

        // 「确定」：保持原样 —— 落盘 + 生效 + 关闭
        okBtn.setOnClickListener {
            val f = collect()
            StgFilterStore.save(activity, f)
            onApply(f)
            runCatching { dialog.dismiss() }
        }

        dialog.show()
        GLog.i("筛选股票：筛选面板已打开（${describe(current)}）")
    }

    // ------------------------------------------------------------------ 工具

    private fun Resources.layoutId(name: String): Int =
        runCatching { getIdentifier(name, "layout", StgStockFilterTargets.TARGET_PKG) }.getOrDefault(0)

    private fun Resources.viewId(name: String): Int =
        runCatching { getIdentifier(name, "id", StgStockFilterTargets.TARGET_PKG) }.getOrDefault(0)

    private fun Resources.attrId(name: String): Int =
        runCatching { getIdentifier(name, "attr", StgStockFilterTargets.TARGET_PKG) }.getOrDefault(0)

    private inline fun <reified T : View> View.find(res: Resources, idName: String): T? {
        val id = res.viewId(idName)
        if (id == 0) return null
        return runCatching { findViewById<View>(id) as? T }.getOrNull()
    }

    /**
     * 把 [target] 从其父容器里换成同尺寸的垂直 [LinearLayout]。
     *
     * 关键：**沿用原 View 的 `layoutParams` 实例** —— 面板是 `ConstraintLayout`，
     * 约束（`layout_constraintXxx`）就存在 `ConstraintLayout.LayoutParams` 里，
     * 把同一个实例挂到新 View 上，位置 / margin / 约束一条不丢。
     */
    private fun swapToLinearHost(target: View): LinearLayout? {
        val parent = target.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(target)
        val lp = target.layoutParams
        parent.removeView(target)
        val host = LinearLayout(target.context).apply {
            orientation = LinearLayout.VERTICAL
            if (lp != null) layoutParams = lp
        }
        parent.addView(host, if (index in 0..parent.childCount) index else -1)
        return host
    }

    /** 面板背景兜底：`?attr/shape_half_top_round_solid_12` 在个别主题下解析不到时手动补一次 */
    private fun ensurePanelBackground(activity: Activity, root: ViewGroup) {
        if (root.background != null) return
        val attr = activity.resources.attrId("shape_half_top_round_solid_12")
        runCatching {
            val tv = TypedValue()
            if (activity.theme.resolveAttribute(attr, tv, true) && tv.resourceId != 0) {
                root.setBackgroundResource(tv.resourceId)
            }
        }.onFailure { GLog.w("筛选股票：补面板背景失败", it) }
    }

    /** 隐藏分组折叠箭头（面板里唯一那颗直接挂在根上的 ImageView） */
    private fun hideSectionArrow(root: ViewGroup) {
        for (i in 0 until root.childCount) {
            (root.getChildAt(i) as? ImageView)?.visibility = View.GONE
        }
    }

    /** 调试用：把筛选状态变成一行可读文本 */
    fun describe(f: StgFilters): String = "涨家股票=${f.up} 跌家股票=${f.down} 自选股票=${f.zx}"
}
