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
 * 功能：**首页快捷入口下方的那条横幅不再显示**。
 *
 * ## 做什么
 *
 * 把首页「快捷入口」网格下方那条横幅设成 `GONE`（不占位），页面内容顺势上移。
 * 只改可见性，不碰横幅里的图片 / 链接 / 数据。
 *
 * ## 认法
 *
 * 见 [HomeBannerTargets]：**先按 id 候选直取**（实测 `layout_banner`），
 * 拿不到就「先认快捷入口网格 → 再往上爬几层、取网格后面第一个像横幅的兄弟」。
 *
 * 为什么要先认网格再找横幅：首页上「像横幅的块」不止一个（顶部的搜索条、
 * 中间的行情卡片都能被宽高判据命中）。用**快捷入口当锚点**，
 * 位置关系就被钉死了 —— 这是本功能准确率的关键。
 *
 * ## 认不出来时
 *
 *  - **认不到快捷入口网格** → 返回 `false`，交给基类重试；重试窗口用尽后由基类
 *    打一条警告 + 一份当前页面的视图树快照（校准入口）。
 *  - **网格认到了、附近却没有「像横幅」的块** → 认定这页本来就没有横幅，
 *    返回 `true` 不再重试（不刷屏）。
 *
 * 判据刻意保守：**藏错一块内容比不藏严重得多**。
 */
class HomeBannerFeature : UiCleanFeature() {

    override val id = "home_banner_hide"
    override val title = "首页隐藏横幅"

    override val pages = HomeBannerTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.homeBannerHide

    private var lastHidden: WeakReference<View>? = null

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        if (alreadyHidden(lastHidden?.get())) return true

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val ids = UiClean.idsFor(activity, HomeBannerTargets.TARGET_PKG)

        // ① 候选 id 直取。
        //
        //    候选是按**真机实测**维护的，命中就认它是对的，只做一个最低限度的
        //    合理性检查（可见 + 量得出高度），**不**再套 looksLikeBanner ——
        //    广告位里是三个 ViewPager 页，严格判据（范围内文案 ≤ 2 条）反而会把
        //    正确的目标拒掉。结构判据留给下面那条兜底路径。
        val byId = ids.firstOf(HomeBannerTargets.BANNER_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v != null && v.isShown && UiClean.heightOf(v) > 0) {
                patcher.hide(v, "首页横幅（id 命中）")
                lastHidden = WeakReference(v)
                return true
            }
        }

        // ② 结构：快捷入口网格 → 它下面那条横幅。
        //    认不出「快捷入口」= 还没铺开或不在首页 → 返回 false 让基类重试
        //    （重试窗口用尽后会打视图树快照，那就是校准入口）。
        val grid = findEntryGrid(root, ids) ?: return false
        val banner = findBannerAfter(grid) ?: return true
        patcher.hide(banner, "首页横幅（快捷入口下方）")
        lastHidden = WeakReference(banner)
        return true
    }

    // ------------------------------------------------------------------ 定位

    /**
     * 可见的「首页快捷入口网格」：先按候选 id，再按结构（前序取最外层）。
     *
     * 拿到之后还要过一次 [isHomeEntryGrid] ——
     * 行情页的 ETF 子页复用了同一个 `rv_home_func`，不区分的话会在那一页
     * 把它自己的广告位当成「首页横幅」藏掉。
     */
    private fun findEntryGrid(root: View, ids: IdCache): ViewGroup? {
        val itemId = ids.of(HomeBannerTargets.HOME_ENTRY_ITEM_ID)

        val byId = ids.firstOf(HomeBannerTargets.QUICK_ENTRY_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v is ViewGroup && v.isShown && isHomeEntryGrid(v, itemId)) return v
        }

        var hit: ViewGroup? = null
        UiClean.walk(root) { v ->
            if (hit != null) return@walk true
            if (v is ViewGroup && v.isShown && isEntryGrid(v) && isHomeEntryGrid(v, itemId)) {
                hit = v
                true
            } else {
                false
            }
        }
        return hit
    }

    /**
     * 「首页那一版」快捷入口的确认。
     *
     * [itemId] 是实测到的格子 id（`home_func_icon`）。**解析不到时一律放行** ——
     * 那说明宿主改版了，这时宁可退回纯结构判据，
     * 也不能因为一个辅助 id 失效就把整个功能停掉。
     */
    private fun isHomeEntryGrid(grid: ViewGroup, itemId: Int): Boolean =
        itemId == 0 || grid.findViewById<View>(itemId) != null

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

    /**
     * 「像横幅」的保守判据：高度适中 + 够宽 + 有图 + **范围内的**文案极少。
     *
     * 为什么文案要限定在范围内：广告位是 `ViewPager`，离屏那几页同样 `VISIBLE`，
     * 里面的标题也会被 `findAll` 数到 —— 真机上三个页面一共 6 条文案，
     * 不限定范围的话「≤ 2 条」这条判据永远不成立。
     */
    private fun looksLikeBanner(v: View): Boolean {
        val h = UiClean.heightOf(v)
        if (h <= 0) return false
        if (h < UiClean.dp(v, HomeBannerTargets.BANNER_MIN_HEIGHT_DP)) return false
        if (h > UiClean.dp(v, HomeBannerTargets.BANNER_MAX_HEIGHT_DP)) return false

        val parentW = (v.parent as? View)?.width ?: 0
        if (parentW > 0 && v.width < parentW * 0.6f) return false

        if (!UiClean.hasIcon(v)) return false

        val w = v.width
        val labels = UiClean.findAll(
            v,
            { it is TextView && !UiClean.textOf(it).isNullOrEmpty() },
            limit = 64,
        ).count { tv ->
            val r = UiClean.rectIn(v, tv)
            r != null && r.left >= 0 && (w <= 0 || r.right <= w)
        }
        if (labels > HomeBannerTargets.BANNER_MAX_LABELS) return false

        GLog.i("$id：命中横幅 ${v.javaClass.simpleName}（高 ${h}px，范围内文案 $labels 条）")
        return true
    }
}
