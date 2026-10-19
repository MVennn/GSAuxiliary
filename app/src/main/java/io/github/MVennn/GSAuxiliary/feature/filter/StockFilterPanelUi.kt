package io.github.MVennn.GSAuxiliary.feature.filter

import android.app.Activity
import android.content.res.Resources
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.TargetApp

/**
 * 筛选面板的**定位常量** —— 策略详情页的面板与行情页（宿主自己）的面板是**同一套布局**，
 * 所以 id 只在这里写一份，两边共用。
 *
 * 实测（金太阳 8.6.0 / vc271，魅族 20 / Android 16）：
 *
 * ```xml
 * ConstraintLayout                      res/layout/dialog_marketfliter.xml（原包名把 filter 拼成 fliter，照抄）
 *   ├─ TextView #tv1  "沪深京A排行榜-股票类型"（标题）
 *   ├─ TextView #tv2  "请在下方勾选您想看的股票类型"（副标题）
 *   ├─ TextView #tv3  "沪深A主板"（分组头）
 *   ├─ ImageView（分组折叠箭头，无 id）
 *   ├─ View #line1（分隔线）
 *   ├─ RecyclerView #rv_markets   ← 行容器
 *   ├─ TextView #tv_cancel "取消"
 *   └─ TextView #tv_ok     "确定"
 * ```
 *
 * 行布局 `res/layout/item_market_filter.xml`：
 * ```xml
 * ConstraintLayout #itemroot (clickable, 45dp)
 *   ├─ TextView   #name
 *   ├─ CheckBox   #cb_checked (30dp)
 *   └─ View（0.5dp 分隔线）
 * ```
 */
internal object FilterPanelIds {

    const val TARGET_PKG = TargetApp.PACKAGE

    const val LAYOUT_FILTER_PANEL = "dialog_marketfliter"
    const val LAYOUT_FILTER_ITEM = "item_market_filter"

    const val ID_PANEL_TITLE = "tv1"
    const val ID_PANEL_SUBTITLE = "tv2"
    const val ID_PANEL_SECTION = "tv3"
    const val ID_PANEL_LIST = "rv_markets"
    const val ID_PANEL_CANCEL = "tv_cancel"
    const val ID_PANEL_OK = "tv_ok"

    const val ID_ITEM_NAME = "name"
    const val ID_ITEM_CHECK = "cb_checked"

    /** 面板窗口从屏幕底部往上弹，压暗程度与宿主一致 */
    const val PANEL_DIM = 0.5f

    /**
     * **我们自己弹的**筛选面板（策略详情页那颗）根视图上的标记。
     *
     * 行情侧功能挂在框架 `Dialog.show` 上找「宿主的面板」，用这个 tag 把自己弹的
     * 那张排除掉；热重载后新实例也靠它认出旧实例弹的面板（规则 1.2）。
     */
    const val TAG_OUR_PANEL = "gsaux_stock_filter_panel"

    /**
     * 注入到**宿主**筛选面板里的那一块（分组头 + 三行）的标记。
     *
     * 幂等与撤销都靠它：宿主的 Dialog 可能被复用，重复 show 时先找 tag 再决定注入；
     * 热重载后新实例也认得出「这是上一任注入的块」（规则 1.2）。
     */
    const val TAG_INJECTED_BLOCK = "gsaux_market_filter_block"
}

/**
 * 筛选面板的**装配工具**：策略详情页自己弹的面板与行情页往宿主面板里注入的行，
 * 用的是同一批控件、同一套样式来源（真的 inflate 宿主布局，不照着重画）。
 *
 * ## 为什么行容器是 `LinearLayout`
 *
 * 面板里原本的行容器是 `RecyclerView #rv_markets`，它的 `LayoutManager` 与 adapter
 * 都由宿主的代码在运行时设 —— 我们只 inflate 布局拿不到那段代码，所以把它换成
 * 垂直 `LinearLayout` 再塞行（沿用**同一个 `LayoutParams` 实例**，
 * `ConstraintLayout` 的约束一条不丢）。模块编译期因此也不必依赖 androidx.recyclerview。
 *
 * ## 样式从哪来
 *
 * 行 ← inflate `item_market_filter`；分组头 ← 克隆面板里现成的 `#tv3`。
 * 一个字面量样式值都不手写，App 换肤 / 深色模式自动跟随。
 */
internal object StockFilterPanelUi {

    fun layoutId(res: Resources, name: String): Int =
        runCatching { res.getIdentifier(name, "layout", FilterPanelIds.TARGET_PKG) }.getOrDefault(0)

    fun viewId(res: Resources, name: String): Int =
        runCatching { res.getIdentifier(name, "id", FilterPanelIds.TARGET_PKG) }.getOrDefault(0)

    fun attrId(res: Resources, name: String): Int =
        runCatching { res.getIdentifier(name, "attr", FilterPanelIds.TARGET_PKG) }.getOrDefault(0)

    inline fun <reified T : View> find(root: View, res: Resources, idName: String): T? {
        val id = viewId(res, idName)
        if (id == 0) return null
        return runCatching { root.findViewById<View>(id) as? T }.getOrNull()
    }

    /**
     * 把 [target] 从其父容器里换成同尺寸的垂直 [LinearLayout]。
     *
     * 关键：**沿用原 View 的 `layoutParams` 实例** —— 面板是 `ConstraintLayout`，
     * 约束（`layout_constraintXxx`）就存在 `ConstraintLayout.LayoutParams` 里，
     * 把同一个实例挂到新 View 上，位置 / margin / 约束一条不丢。
     */
    fun swapToLinearHost(target: View): LinearLayout? {
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

    /**
     * 把 [target]**包**进一个垂直 `LinearLayout`（target 原样留在里面，返回那个新容器）。
     *
     * 与 [swapToLinearHost] 的区别：那个是「换掉」（原 View 不再要了，策略面板用它把
     * 宿主的 `RecyclerView` 换成自己塞行的容器）；这个是「包住」（原 View 是宿主自己的，
     * 行还在里面，我们只想在它**后面**接一块自己的内容）—— 行情面板注入「涨跌与自选」
     * 三行时用这个：宿主的 `#rv_markets` 原封不动，它的 adapter / 监听照旧能用。
     *
     * 外层容器沿用原 View 的 `LayoutParams` 实例（约束一条不丢，同 [swapToLinearHost]）。
     *
     * ⚠️ **收编时要把原 View 的横向外边距清零**：`LinearLayout` 把
     * `ConstraintLayout.LayoutParams` 转成自己的 `LayoutParams` 时会**连外边距一起抄**，
     * 而这份外边距（`layout_marginStart`）已经被外层容器用过了 —— 不清零就是「左缩进 ×2」，
     * 宿主的行会被我们整体推右一截（2026-10-19 真机实测：行内名称 70px → 112px）。
     * 纵向边距不动（那是容器之间的间距，行内不需要，但清零反而少一段留白）。
     */
    fun wrapWithVerticalHost(target: View): LinearLayout? {
        val parent = target.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(target)
        val lp = target.layoutParams
        parent.removeView(target)
        val host = LinearLayout(target.context).apply {
            orientation = LinearLayout.VERTICAL
            if (lp != null) layoutParams = lp
        }
        parent.addView(host, if (index in 0..parent.childCount) index else -1)
        host.addView(target)
        // 转出来的那份 LayoutParams 是副本，改它不会碰宿主原来那个实例
        (target.layoutParams as? ViewGroup.MarginLayoutParams)?.let { p ->
            p.leftMargin = 0
            p.rightMargin = 0
            runCatching { p.marginStart = 0 }
            runCatching { p.marginEnd = 0 }
        }
        return host
    }

    /**
     * 读一个 View 上**已经装着**的点击监听（`View.mListenerInfo` 是隐藏字段）。
     *
     * 用途：宿主面板的「确定 / 取消」上挂的是宿主自己的匿名监听
     * （`FilterDiaglog$2` / `$3`），我们既不能 `setOnClickListener` 把它顶掉
     * （宿主的行为会没），也不能凭空再挂一个（同一个 View 只有一个监听）。
     * 所以要「拿出来 → 包一层 → 放回去」。
     */
    fun clickListener(v: View): View.OnClickListener? = runCatching {
        val infoF = View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }
        val info = infoF.get(v) ?: return null
        val clickF = info.javaClass.getDeclaredField("mOnClickListener").apply { isAccessible = true }
        clickF.get(info) as? View.OnClickListener
    }.getOrNull()

    /**
     * 把 [action] 包在 [v] 原有的点击监听**外面**（原有为空时只跑 [action]）。
     *
     * @return **宿主原本的监听**（没有则 null）—— 调用方必须留着它：
     *   撤包装时要把 original 放回去，否则宿主自己的「确定」行为就永久失联。
     *   （原先这里把它捕获进闭包就没人拿得到了，导致注入无法撤销。）
     */
    fun wrapClick(v: View, action: (View) -> Unit): View.OnClickListener? {
        val original = clickListener(v)
        v.setOnClickListener { view ->
            runCatching { action(view) }
            original?.let { runCatching { it.onClick(view) } }
        }
        return original
    }

    /** 按宿主行布局建一行：`#name` 文案 + `#cb_checked` 勾选 + 整行可点。
     *
     * 点**整行**即切换（与行情页那颗漏斗的交互一致；勾选框本身也可点）。
     */
    fun buildRow(
        inflater: LayoutInflater,
        res: Resources,
        host: ViewGroup,
        label: String,
        checked: Boolean,
        onToggle: (Boolean) -> Unit,
    ): View? {
        val itemLayoutId = layoutId(res, FilterPanelIds.LAYOUT_FILTER_ITEM)
        if (itemLayoutId == 0) return null
        val item = runCatching { inflater.inflate(itemLayoutId, host, false) }.getOrNull() ?: return null
        val box = find<CheckBox>(item, res, FilterPanelIds.ID_ITEM_CHECK)
        runCatching { box?.isChecked = checked }
        find<TextView>(item, res, FilterPanelIds.ID_ITEM_NAME)?.text = label
        item.isClickable = true
        item.setOnClickListener {
            val b = box ?: return@setOnClickListener
            b.isChecked = !b.isChecked
            onToggle(b.isChecked)
        }
        host.addView(item)
        return item
    }

    /**
     * 分组头：克隆面板里那颗 `#tv3` 的样式，返回新建的 TextView（拿不到源样式时返回 null）。
     *
     * ## 左边距要「补差额」
     *
     * `#tv3` 是**面板根容器**的孩子（ConstraintLayout，`layout_marginStart` 25dp），
     * 而我们的分组头要放进**行容器**里（[host]，它的左边缘 = 原来的行容器左边缘，
     * 本身还带着 15dp 外边距）。直接抄 `#tv3` 的 25dp 会变成 15+25=40dp，比它多一截。
     * 所以这里算的是**差额**：`tv3 的左边距 − 行容器的左边距`，
     * 两张面板（策略 / 行情）都套这条公式，头部与宿主的对齐口径一致。
     *
     * [anchor] 传「行容器原来的那个 View」（策略面板是 `#rv_markets` 自己，
     * 行情面板也是它），用来取行容器的外边距。
     */
    fun addSectionHeader(
        root: View,
        res: Resources,
        host: ViewGroup,
        label: String,
        anchor: View?,
    ): TextView? {
        val src = find<TextView>(root, res, FilterPanelIds.ID_PANEL_SECTION) ?: return null
        val header = runCatching {
            TextView(root.context).apply {
                cloneTextStyle(src, this)
                text = label
            }
        }.getOrNull() ?: return null
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val srcM = src.layoutParams as? ViewGroup.MarginLayoutParams
        val anchorM = anchor?.layoutParams as? ViewGroup.MarginLayoutParams
        if (srcM != null) {
            val indent = startMargin(srcM) - (anchorM?.let { startMargin(it) } ?: 0)
            lp.leftMargin = indent.coerceAtLeast(0)
            lp.rightMargin = srcM.rightMargin
            lp.topMargin = srcM.topMargin
            lp.bottomMargin = srcM.bottomMargin
        }
        host.addView(header, lp)
        return header
    }

    /**
     * 左边距（`layout_marginStart` 优先）。
     *
     * 两个字段都要看：布局**跑过一轮之后** `marginStart` 会被解析进 `leftMargin`
     * （LTR 下相等），没跑过时只有 `marginStart` 有值。取两者的大者，
     * 两种情况都得到「实际会生效的那个缩进」。
     */
    private fun startMargin(m: ViewGroup.MarginLayoutParams): Int =
        maxOf(m.marginStart, m.leftMargin)

    /** 把一个 TextView 的文字样式（背景 / 字色 / 字体 / 字号 / 内边距 / gravity…）整套克隆到另一个 */
    fun cloneTextStyle(src: TextView, dst: TextView) {
        runCatching { dst.setBackground(src.background?.constantState?.newDrawable()?.mutate()) }
        runCatching { dst.setTextColor(src.textColors) }
        runCatching { src.typeface?.let { dst.typeface = it } }
        runCatching { dst.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.textSize) }
        runCatching {
            dst.setPadding(src.paddingLeft, src.paddingTop, src.paddingRight, src.paddingBottom)
        }
        runCatching { dst.gravity = src.gravity }
        runCatching { dst.includeFontPadding = src.includeFontPadding }
        runCatching { dst.maxLines = src.maxLines }
        runCatching { dst.minWidth = src.minWidth }
        runCatching { dst.minHeight = src.minHeight }
        runCatching { dst.setAllCaps(false) }
        (src.layoutParams as? ViewGroup.MarginLayoutParams)?.let { s ->
            (dst.layoutParams as? ViewGroup.MarginLayoutParams)?.let { d ->
                d.leftMargin = s.leftMargin
                d.rightMargin = s.rightMargin
                d.topMargin = s.topMargin
                d.bottomMargin = s.bottomMargin
            }
        }
    }

    /** 面板背景兜底：`?attr/shape_half_top_round_solid_12` 在个别主题下解析不到时手动补一次 */
    fun ensurePanelBackground(activity: Activity, root: ViewGroup, logTag: String) {
        if (root.background != null) return
        val attr = attrId(activity.resources, "shape_half_top_round_solid_12")
        runCatching {
            val tv = TypedValue()
            if (activity.theme.resolveAttribute(attr, tv, true) && tv.resourceId != 0) {
                root.setBackgroundResource(tv.resourceId)
            }
        }.onFailure { GLog.w("$logTag：补面板背景失败", it) }
    }

    /** 隐藏分组折叠箭头（面板里唯一那颗直接挂在根上的 ImageView） */
    fun hideSectionArrow(root: ViewGroup) {
        for (i in 0 until root.childCount) {
            (root.getChildAt(i) as? ImageView)?.visibility = View.GONE
        }
    }
}
