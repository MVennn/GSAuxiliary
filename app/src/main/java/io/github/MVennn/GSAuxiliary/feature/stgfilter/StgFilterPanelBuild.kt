package io.github.MVennn.GSAuxiliary.feature.stgfilter

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.filter.StockFilterPanelUi
import io.github.MVennn.GSAuxiliary.util.Density
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「筛选股票」的**面板装配**：往策略详情页三点菜单里注入入口项，点开后接上 [StgFilterPanel]。
 *
 * 从 [StgStockFilterFeature] 拆出（原「菜单注入」与「样式克隆」两段，注释随代码原样搬移）；
 * Feature 本体只留挂点分析、hook 安装与行过滤状态机。
 *
 * 装配期间用到的共用小工具（[findActivity] / [viewIdOrZero]）也放在本文件底部，
 * 供 Feature 本体与装配逻辑双方调用。
 */
internal class StgFilterPanelBuild(private val feature: StgStockFilterFeature) {

    /** 缓存资源 id（0 = 还没解析过） */
    @Volatile private var menuAnchorIdCache = 0
    @Volatile private var menuEditNameIdCache = 0

    /** 三点菜单窗口被我们改过的原始高度，撤销时还原 */
    private val popupOriginalHeight = Collections.synchronizedMap(WeakHashMap<PopupWindow, Int>())

    /** 菜单容器归一化前的 lp.height，按容器记账（多个菜单实例互不覆盖） */
    private val parentOriginalHeight = Collections.synchronizedMap(WeakHashMap<ViewGroup, Int>())

    // ------------------------------------------------------------------ 菜单注入

    internal fun maybeInjectMenuItem(env: FeatureEnv, pop: PopupWindow) {
        val content = runCatching { pop.contentView }.getOrNull() as? ViewGroup ?: return
        val res = content.resources

        if (menuAnchorIdCache == 0) {
            menuAnchorIdCache = res.viewIdOrZero(StgStockFilterTargets.MENU_ANCHOR_ID)
        }
        val anchorId = menuAnchorIdCache
        if (anchorId == 0) return

        // 只有「删除策略」在里面的才是策略详情页的三点菜单；别的 PopupWindow 一律放过
        val anchor = runCatching { content.findViewById<View>(anchorId) }.getOrNull() ?: return
        val parent = anchor.parent as? ViewGroup ?: return

        if (!env.config.stgStockFilter) {
            removeInjected(pop, parent)
            return
        }
        if (parent.findViewWithTag<View>(TAG_ITEM) != null) return   // 幂等

        val anchorText = anchor as? TextView ?: return

        if (menuEditNameIdCache == 0) {
            menuEditNameIdCache = res.viewIdOrZero(StgStockFilterTargets.MENU_EDIT_NAME_ID)
        }
        val editName = menuEditNameIdCache.takeIf { it != 0 }
            ?.let { runCatching { content.findViewById<View>(it) }.getOrNull() }

        val activity = findActivity(content.context)
        if (activity == null) {
            GLog.w("筛选股票：三点菜单的 Context 里找不到 Activity，放弃注入")
            return
        }

        // 分隔线：原菜单里唯一的 ImageView，克隆一个（含背景色）
        val dividerSrc = (0 until parent.childCount)
            .map { parent.getChildAt(it) }
            .firstOrNull { it is ImageView }
        val divider = dividerSrc?.let { src ->
            runCatching {
                ImageView(src.context).apply {
                    background = src.background?.constantState?.newDrawable()?.mutate()
                    layoutParams = cloneParams(src)
                }
            }.getOrNull()
        }

        val item = TextView(parent.context).apply {
            text = MENU_ITEM_TEXT
            cloneTextStyle(anchorText, this)
            layoutParams = cloneParams(anchorText)
            id = View.generateViewId()
            tag = TAG_ITEM
            isClickable = true
            isFocusable = true
            setOnClickListener { v ->
                runCatching {
                    pop.dismiss()
                    if (!env.config.stgStockFilter) return@runCatching
                    val act = findActivity(v.context) ?: activity
                    StgFilterPanel.show(act, feature.filtersBy(act)) { applied ->
                        feature.onFilterApplied(env, act, applied)
                    }
                }.onFailure { GLog.w("筛选股票：打开筛选页失败", it) }
            }
        }

        // 插在「修改名称」**下方**
        val at = if (editName != null) parent.indexOfChild(editName) + 1 else parent.childCount
        runCatching {
            var idx = at.coerceIn(0, parent.childCount)
            divider?.let { parent.addView(it, idx); idx++ }
            parent.addView(item, idx.coerceAtMost(parent.childCount))
        }.onFailure {
            GLog.w("筛选股票：插入菜单项失败", it)
            return
        }

        // 两项 → 三项：把每一项从「height=0dp + weight=1」改成「固定高度、不参与权重」，
        // 再把容器与**弹窗窗口**的高度都放开 —— 否则三项会被压进原来那 98dp 里
        // （实测每项矮了 1/3，甚至第三项被整个裁掉）。
        //
        // 为什么不直接改容器高度：inflate(layout, null) 出来的根 View **没有 LayoutParams**
        // （交给 PopupWindow 时才会被赋上 MATCH_PARENT），拿不到可改的高度值。
        // 而它外面的 `PopupWindow` 是**用固定尺寸 new 出来的**（实测窗口高度恒为 98dp），
        // 所以还必须把 `pop.height` 改成 WRAP_CONTENT，让窗口按新内容重新量一次。
        runCatching {
            if (!popupOriginalHeight.containsKey(pop)) popupOriginalHeight[pop] = pop.height
            GLog.i("筛选股票：菜单窗口原尺寸 width=${pop.width} height=${pop.height}" +
                    "（-1=MATCH_PARENT, -2=WRAP_CONTENT）")
            pop.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }.onFailure { GLog.w("筛选股票：调整菜单窗口高度失败", it) }
        runCatching { normalizeMenuItemHeights(parent) }

        GLog.i("筛选股票：已在三点菜单「修改名称」下方注入「$MENU_ITEM_TEXT」")
    }

    /** 把菜单里每个文字项的权重去掉、高度固定成 `原设计总高 / 原项数` */
    private fun normalizeMenuItemHeights(parent: ViewGroup) {
        val itemPx = Density.dp(parent, MENU_DESIGN_HEIGHT_DP / MENU_BASE_ITEM_COUNT)
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i) as? TextView ?: continue
            val p = c.layoutParams as? LinearLayout.LayoutParams ?: continue
            p.weight = 0f
            p.height = itemPx
            c.layoutParams = p
        }
        // 审查 2026-10-17 低-10：容器原 lp.height 非 null 时存档，还原时按原值放回
        parent.layoutParams?.height?.takeIf { it > 0 }?.let { parentOriginalHeight[parent] = it }
        val lp = parent.layoutParams ?: return
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        parent.layoutParams = lp
        GLog.i("筛选股票：菜单项高度已归一（${parent.childCount} 项 × ${itemPx}px）")
    }

    private fun restoreMenuItemHeights(parent: ViewGroup) {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i) as? TextView ?: continue
            val p = c.layoutParams as? LinearLayout.LayoutParams ?: continue
            p.weight = 1f
            p.height = 0
            c.layoutParams = p
        }
        // 审查 2026-10-17 低-10：归一化改过容器 lp.height 的，按存档原值还原
        parentOriginalHeight.remove(parent)?.let {
            parent.layoutParams?.height = it
        }
    }

    private fun removeInjected(pop: PopupWindow, parent: ViewGroup) {
        val item = parent.findViewWithTag<View>(TAG_ITEM) ?: return
        val idx = parent.indexOfChild(item)
        runCatching { parent.removeView(item) }
        // 分隔线紧邻在菜单项前一个位置（注入时成对放入）
        if (idx - 1 >= 0) {
            val prev = parent.getChildAt(idx - 1)
            if (prev is ImageView) runCatching { parent.removeView(prev) }
        }
        runCatching { restoreMenuItemHeights(parent) }
        runCatching { popupOriginalHeight.remove(pop)?.let { pop.height = it } }
    }

    // ------------------------------------------------------------ 样式克隆

    /** 文字样式克隆复用共享实现（面板的分组头克隆也用它，见 [StockFilterPanelUi]） */
    private fun cloneTextStyle(src: TextView, dst: TextView) =
        StockFilterPanelUi.cloneTextStyle(src, dst)

    private fun cloneParams(src: View): ViewGroup.LayoutParams {
        val lp = src.layoutParams
        return when (lp) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(lp)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(lp)
            is ViewGroup.LayoutParams -> ViewGroup.LayoutParams(lp)
            else -> ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private companion object {
        /** 注入项在菜单里的文字 */
        const val MENU_ITEM_TEXT = "筛选股票"

        /** 注入项的标记 tag —— 幂等判定与撤销都靠它 */
        const val TAG_ITEM = "gsaux_stg_filter_item"

        /** 原菜单布局 `pop_ai_stg.xml` 的设计高度（dp）：两项均分 */
        const val MENU_DESIGN_HEIGHT_DP = 98f

        /** 原菜单里的项数（「修改名称」+「删除策略」） */
        const val MENU_BASE_ITEM_COUNT = 2
    }
}

/** 依次尝试候选 id 名，返回第一个解析到的（找不到返回 0） */
internal fun Resources.viewIdOrZero(vararg names: String): Int {
    for (n in names) {
        val r = runCatching {
            getIdentifier(n, "id", StgStockFilterTargets.TARGET_PKG)
        }.getOrDefault(0)
        if (r != 0) return r
    }
    return 0
}

internal fun findActivity(ctx: Context?): Activity? {
    var c: Context? = ctx
    var guard = 0
    while (c != null && guard++ < 10) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
    }
    return null
}
