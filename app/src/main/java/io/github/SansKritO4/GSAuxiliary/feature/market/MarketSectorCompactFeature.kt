package io.github.SansKritO4.GSAuxiliary.feature.market

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.feature.FeatureEnv
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.IdCache
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiClean
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiCleanFeature
import java.lang.ref.WeakReference

/**
 * 功能：**行情市场页的「板块」由六项压缩为三项**。
 *
 * ## 做什么
 *
 * 板块区原本一屏铺六个，只保留最上面三个，剩下的收起 —— 板块区整体变矮，
 * 「市场概览」顺势上移。
 *
 * ## 三种版式，三种压法（按容器实际形态分派）
 *
 * | 容器形态 | 判据 | 压法 |
 * |---|---|---|
 * | **六格平铺** | 子视图数 == 6 | 后三项 `GONE`；容器若是固定高，改成 `wrap_content` |
 * | **两行 × 三列** | 子视图数 == 2，且每行 3 格 | 第二行 `GONE`；容器固定高则改 `wrap_content` |
 * | **可滚动列表** | `AbsListView` / `RecyclerView` | 藏子项没用（一滚就回来），改成**限高**为原来的 3/6 |
 *
 * ## 认法
 *
 * 候选 id 优先（[MarketTargets.SECTOR_GROUP_ID_CANDIDATES]）；
 * 否则按结构找「子视图正好 6 个、宽度基本一致、每项都有文案」的容器，
 * 并**排除快捷入口网格**（那种也是 6 个，但每格都有图标）。
 */
class MarketSectorCompactFeature : UiCleanFeature() {

    override val id = "market_sector_compact"
    override val title = "行情市场板块只留三项"

    override val pages = MarketTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.marketSectorCompact

    private var lastGroup: WeakReference<View>? = null

    override fun applyTo(env: FeatureEnv, activity: Activity): Boolean {
        // 幂等：处理过的容器还在树上 = 已经压过（改的是子项和容器高度，没有「被藏的那一个」可判）
        if (stillAttached(lastGroup?.get())) return true

        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        val ids = UiClean.idsFor(activity, MarketTargets.TARGET_PKG)
        val group = findSectorGroup(root, ids) ?: return false

        // 认到了但还没填充子项 / 还没量出高度 → 返回 false 等下一帧，
        // 别把「已处理」标记打上去（否则这个容器就永远压不动了）
        if (!compact(group)) return false

        lastGroup = WeakReference(group)
        return true
    }

    // ------------------------------------------------------------------ 定位

    private fun findSectorGroup(root: View, ids: IdCache): View? {
        val byId = ids.firstOf(MarketTargets.SECTOR_GROUP_ID_CANDIDATES)
        if (byId != 0) {
            val v = root.findViewById<View>(byId)
            if (v != null && v.isShown) return v
        }

        // 结构：子项正好 6 个、宽度一致、每项有文案、且不是「图标 + 文字」网格
        return UiClean.find(root) { v ->
            v is ViewGroup && v.isShown && v.childCount == MarketTargets.SECTOR_TOTAL &&
                isUniformRows(v) && !looksLikeEntryGrid(v)
        }
    }

    private fun isUniformRows(g: ViewGroup): Boolean {
        val widths = ArrayList<Int>(g.childCount)
        var labels = 0
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.width > 0) widths.add(c.width)
            if (UiClean.firstLabel(c) != null) labels++
        }
        if (widths.size != g.childCount) return false
        if (labels < g.childCount - 1) return false
        val min = widths.min()
        val max = widths.max()
        return max <= min * MarketTargets.SECTOR_WIDTH_TOLERANCE
    }

    /** 「图标 + 文字」网格（快捷入口）——它也是 6 个，但不是板块 */
    private fun looksLikeEntryGrid(g: ViewGroup): Boolean {
        var entries = 0
        for (i in 0 until g.childCount) if (UiClean.looksLikeEntryItem(g.getChildAt(i))) entries++
        return entries >= g.childCount - 1
    }

    // ------------------------------------------------------------------ 压缩

    /**
     * 压缩板块区。返回 `false` = 容器还没准备好（**别打「已处理」标记**），
     * `true` = 处理完了（含「认得出容器但形态不认识」这种放弃情形）。
     */
    private fun compact(raw: View): Boolean {
        // 宿主常常把列表包在卡片里（容器 → 卡片 → 列表），单子项包装层往下钻几层
        val group = unwrapSingleChild(raw)

        // ① 可滚动列表：藏子项无效（滚动会把它们带回来），改成限高
        if (isScrollable(group)) {
            val h = UiClean.heightOf(group)
            if (h <= 0) return false
            patcher.setHeight(
                group,
                h * MarketTargets.SECTOR_KEEP / MarketTargets.SECTOR_TOTAL,
                "板块列表限高",
            )
            return true
        }

        if (group !is ViewGroup) return true
        if (group.childCount == 0) return false        // 还没填充子项

        // ② 六格平铺：藏后三项
        if (group.childCount == MarketTargets.SECTOR_TOTAL) {
            var hidden = 0
            for (i in MarketTargets.SECTOR_KEEP until group.childCount) {
                if (patcher.hide(group.getChildAt(i), "板块第 ${i + 1} 项")) hidden++
            }
            relaxFixedHeight(group)
            GLog.i("$id：板块由 ${MarketTargets.SECTOR_TOTAL} 项压到 ${MarketTargets.SECTOR_KEEP} 项（藏了 $hidden 项）")
            return true
        }

        // ③ 两行 × 三列：藏第二行
        if (group.childCount == 2) {
            val second = group.getChildAt(1) as? ViewGroup
            if (second != null && second.childCount >= 1) {
                patcher.hide(second, "板块第二行")
                relaxFixedHeight(group)
                GLog.i("$id：板块已收起第二行")
                return true
            }
        }

        GLog.i("$id：板块容器形态不认识（${group.javaClass.simpleName}，子项 ${group.childCount} 个），本页跳过")
        return true
    }

    /** 单子项包装层往下钻（最多 3 层）：卡片 / 容器套一层是常态 */
    private fun unwrapSingleChild(start: View): View {
        var cur = start
        var depth = 0
        while (depth++ < 3) {
            val g = cur as? ViewGroup ?: return cur
            if (g.childCount != 1) return cur
            val only = g.getChildAt(0)
            if (only !is ViewGroup) return cur
            cur = only
        }
        return cur
    }

    /**
     * 容器是**固定高度**的话，藏掉子项之后会留一片空白，得一起改成 `wrap_content`。
     * 高度是 `wrap_content` / `match_parent` 时不动它。
     */
    private fun relaxFixedHeight(container: View) {
        val lp = container.layoutParams ?: return
        if (lp.height > 0) {
            patcher.setHeight(container, ViewGroup.LayoutParams.WRAP_CONTENT, "板块容器")
        }
    }

    private fun isScrollable(v: View): Boolean {
        if (v is android.widget.AbsListView) return true
        var c: Class<*>? = v.javaClass
        var depth = 0
        while (c != null && depth++ < 8) {
            if (c.name == "androidx.recyclerview.widget.RecyclerView") return true
            c = c.superclass
        }
        return false
    }
}
