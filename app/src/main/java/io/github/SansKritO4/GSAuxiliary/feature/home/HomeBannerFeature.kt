package io.github.SansKritO4.GSAuxiliary.feature.home

import android.app.Activity
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
 * 功能：**首页快捷入口下方的横幅不再显示**。
 *
 * ## 做什么
 *
 * 把首页「快捷入口」网格下方那条横幅设成 `GONE`（不占位），页面内容顺势上移。
 * 只改可见性，不碰横幅里的图片 / 链接 / 数据。
 *
 * ## 认法
 *
 * 见 [HomeBannerTargets]：**先按 id 候选直取**，拿不到就
 * 「先认快捷入口网格 → 再往上爬几层、取网格后面第一个像横幅的兄弟」。
 *
 * 为什么要先认网格再找横幅：首页上「像横幅的块」不止一个（顶部的搜索条、
 * 中间的行情卡片都能被宽高判据命中）。用**快捷入口当锚点**，
 * 位置关系就被钉死了 —— 这是本功能准确率的关键。
 *
 * ## 认不出来时
 *
 * 什么都不做（返回 `true`，不重试刷屏），并由基类打一条警告 + 视图树快照。
 * 保守是刻意的：藏错一块内容比不藏严重得多。
 */
class HomeBannerFeature : UiCleanFeature() {

    override val id = "home_banner_hide"
    override val title = "首页横幅隐藏"

    override val pages = HomeBannerTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.homeBannerHide

    private var lastHidden: WeakReference<View>? = null

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        if (alreadyHidden(lastHidden?.get())) return true

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true

        // ① 候选 id 直取：命中且确实「像横幅」才动手
        val ids = UiClean.idsFor(activity, HomeBannerTargets.TARGET_PKG)
        val byId = ids.firstOf(HomeBannerTargets.BANNER_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v != null && v.isShown && looksLikeBanner(v)) {
                patcher.hide(v, "首页横幅（id 命中）")
                lastHidden = WeakReference(v)
                return true
            }
        }

        // ② 结构：快捷入口网格 → 它下面那条横幅
        //    认不出「快捷入口」= 还没铺开或不在首页 → 返回 false 让基类重试（重试窗口外会打快照）
        val grid = findEntryGrid(root, ids) ?: return false
        val banner = findBannerAfter(grid) ?: return true
        patcher.hide(banner, "首页横幅（快捷入口下方）")
        lastHidden = WeakReference(banner)
        return true
    }

    // ------------------------------------------------------------------ 定位

    /** 可见的「快捷入口网格」：先按候选 id，再按结构（前序取最外层） */
    private fun findEntryGrid(root: View, ids: IdCache): ViewGroup? {
        val byId = ids.firstOf(HomeBannerTargets.QUICK_ENTRY_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v is ViewGroup && v.isShown) return v
        }
        var hit: ViewGroup? = null
        UiClean.walk(root) { v ->
            if (hit != null) return@walk true
            if (v is ViewGroup && v.isShown && isEntryGrid(v)) {
                hit = v
                true
            } else {
                false
            }
        }
        return hit
    }

    private fun isEntryGrid(g: ViewGroup): Boolean {
        val n = g.childCount
        if (n < HomeBannerTargets.MIN_ENTRY_ITEMS) return false

        var entries = 0
        for (i in 0 until n) if (UiClean.looksLikeEntryItem(g.getChildAt(i))) entries++
        if (entries < n - 1) return false

        val h = UiClean.heightOf(g)
        if (h > 0 && h > UiClean.dp(g, HomeBannerTargets.MAX_ENTRY_GRID_HEIGHT_DP)) return false
        return true
    }

    /**
     * 从 [grid] 出发往上爬最多 [HomeBannerTargets.MAX_HOPS] 层，
     * 每层检查「网格（或它的祖先）后面第一个可见兄弟」是不是横幅。
     */
    private fun findBannerAfter(grid: View): View? {
        var child: View = grid
        var hops = 0
        while (hops < HomeBannerTargets.MAX_HOPS) {
            val container = child.parent as? ViewGroup ?: return null
            val idx = container.indexOfChild(child)
            if (idx >= 0) {
                for (i in idx + 1 until container.childCount) {
                    val sib = container.getChildAt(i)
                    if (!sib.isShown) continue
                    if (looksLikeBanner(sib)) return sib
                    // 后面第一个「有内容」的兄弟不是横幅 → 这一层不必再往下看
                    if (UiClean.heightOf(sib) > 0) break
                }
            }
            child = container
            hops++
        }
        return null
    }

    /** 「像横幅」的保守判据：高度适中 + 够宽 + 有图 + 文案极少 */
    private fun looksLikeBanner(v: View): Boolean {
        val h = UiClean.heightOf(v)
        if (h <= 0) return false
        if (h < UiClean.dp(v, HomeBannerTargets.BANNER_MIN_HEIGHT_DP)) return false
        if (h > UiClean.dp(v, HomeBannerTargets.BANNER_MAX_HEIGHT_DP)) return false

        val parentW = (v.parent as? View)?.width ?: 0
        if (parentW > 0 && v.width < parentW * 0.6f) return false

        if (!UiClean.hasIcon(v)) return false

        val labels = UiClean.findAll(
            v,
            { it is TextView && !UiClean.textOf(it).isNullOrEmpty() },
            limit = HomeBannerTargets.BANNER_MAX_LABELS + 1,
        )
        if (labels.size > HomeBannerTargets.BANNER_MAX_LABELS) return false

        GLog.i("$id：命中横幅 ${v.javaClass.simpleName}（高 ${h}px，文案 ${labels.size} 条）")
        return true
    }
}
