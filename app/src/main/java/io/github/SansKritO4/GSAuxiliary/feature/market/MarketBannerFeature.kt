package io.github.SansKritO4.GSAuxiliary.feature.market

import android.app.Activity
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.feature.FeatureEnv
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.IdCache
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiClean
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiCleanFeature
import java.lang.ref.WeakReference

/**
 * 功能：**行情市场页——滚动横幅与快捷入口一起隐藏**。
 *
 * ## 做什么
 *
 * 把「市场概览图」下方那条**滚动横幅**（跑马灯快讯）、以及它下面那排**快捷入口**
 * 一并设成 `GONE`，让板块直接顶上来。
 *
 * ## 怎么认滚动横幅
 *
 *  1. 候选 id（[MarketTargets.MARQUEE_BAR_ID_CANDIDATES]）；
 *  2. 结构：**跑马灯文字**——`ellipsize = MARQUEE` 且文案够长
 *     （[MarketTargets.MIN_MARQUEE_TEXT] 个字以上，滤掉标签类的短文案），
 *     再从这个文字往上找第一个高度不超过
 *     [MarketTargets.MARQUEE_BAR_MAX_HEIGHT_DP] 的祖先，那就是「条」本身。
 *
 * 用 `ellipsize = MARQUEE` 当判据是稳的：跑马灯在 Android 上只有这一种标准做法
 * （`setEllipsize(MARQUEE)` + `setSelected(true)`），比猜 id 可靠得多。
 *
 * ## 怎么认快捷入口
 *
 * 候选 id 优先；否则取页面上**第一个**「可见 + 每格都是图标加文字」的网格，
 * 并且要求它的顶边在滚动横幅**下方**（横幅认到了才有这个约束）。
 */
class MarketBannerFeature : UiCleanFeature() {

    override val id = "market_banner_hide"
    override val title = "行情市场滚动横幅与快捷入口"

    override val pages = MarketTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.marketBannerHide

    private var lastBar: WeakReference<View>? = null
    private var lastGrid: WeakReference<View>? = null

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        if (alreadyHidden(lastBar?.get()) && alreadyHidden(lastGrid?.get())) return true

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val ids = UiClean.idsFor(activity, MarketTargets.TARGET_PKG)

        val bar = findMarqueeBar(root, ids)
        if (bar != null) {
            patcher.hide(bar, "行情市场滚动横幅")
            lastBar = WeakReference(bar)
        }

        val grid = findEntryGrid(root, ids, bar)
        if (grid != null) {
            patcher.hide(grid, "行情市场快捷入口")
            lastGrid = WeakReference(grid)
        }

        if (bar == null && grid == null) {
            // 两个都没认出来：可能不在行情市场页，也可能还没铺开 —— 交给基类重试 / 打快照
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ 滚动横幅

    private fun findMarqueeBar(root: View, ids: IdCache): View? {
        val byId = ids.firstOf(MarketTargets.MARQUEE_BAR_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v != null && v.isShown) return v
        }

        val tv = UiClean.find(root) { v ->
            v.isShown && v is TextView &&
                v.ellipsize == TextUtils.TruncateAt.MARQUEE &&
                (UiClean.textOf(v)?.length ?: 0) >= MarketTargets.MIN_MARQUEE_TEXT
        } as? TextView ?: return null

        GLog.i("$id：认到跑马灯文字「${UiClean.textOf(tv)?.take(16)}…」")
        return barOf(tv)
    }

    /** 从跑马灯文字往上找「条状容器」：第一个高度不超过上限的祖先 */
    private fun barOf(tv: TextView): View {
        var cur: View = tv
        var hops = 0
        while (hops < 3) {
            val p = cur.parent as? ViewGroup ?: break
            val h = UiClean.heightOf(p)
            if (h in 1..UiClean.dp(p, MarketTargets.MARQUEE_BAR_MAX_HEIGHT_DP)) return p
            cur = p
            hops++
        }
        return tv
    }

    // ------------------------------------------------------------------ 快捷入口

    private fun findEntryGrid(root: View, ids: IdCache, bar: View?): View? {
        val byId = ids.firstOf(MarketTargets.QUICK_ENTRY_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v is ViewGroup && v.isShown) return v
        }

        val barTop = bar?.let { UiClean.rectIn(root, it)?.top } ?: -1
        var hit: ViewGroup? = null
        UiClean.walk(root) { v ->
            if (hit != null) return@walk true
            if (v is ViewGroup && v.isShown && isEntryGrid(v)) {
                // 认到横幅时要求网格在横幅下方 —— 避免抓到概览图上方那一排
                if (barTop < 0) {
                    hit = v
                    true
                } else {
                    val top = UiClean.rectIn(root, v)?.top ?: return@walk false
                    if (top >= barTop) {
                        hit = v
                        true
                    } else {
                        false
                    }
                }
            } else {
                false
            }
        }
        return hit
    }

    private fun isEntryGrid(g: ViewGroup): Boolean {
        val n = g.childCount
        if (n < MarketTargets.MIN_ENTRY_ITEMS) return false

        var entries = 0
        for (i in 0 until n) if (UiClean.looksLikeEntryItem(g.getChildAt(i))) entries++
        if (entries < n - 1) return false

        val h = UiClean.heightOf(g)
        if (h > 0 && h > UiClean.dp(g, MarketTargets.MAX_ENTRY_GRID_HEIGHT_DP)) return false
        return true
    }
}
