package io.github.MVennn.GSAuxiliary.feature.uiclean

import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import io.github.MVennn.GSAuxiliary.core.GLog
import java.util.Collections
import java.util.WeakHashMap

/**
 * 视图补丁表：**改之前先记账，一键还原**。
 *
 * ## 为什么必须记账
 *
 * 精简类功能改的都是**宿主自己的视图对象**（不是我们造出来的），
 * 而宿主会反复复用它们：
 *
 *  - 关掉开关时要把 `visibility` / `height` / `weight` 全部改回去；
 *  - 页面返回、Fragment 重建后，新的视图对象又要重新记账；
 *  - `LayoutParams` 是**活的对象**，被宿主和视图共用 ——
 *    直接 `v.layoutParams.height = x` 会污染宿主自己后续的布局计算，
 *    所以原值必须留一份。
 *
 * 因此这里用**弱引用键**记账：视图被回收时记录自动消失，不会泄漏 Activity。
 *
 * ## 为什么还原要「原地改」而不是换一个 LayoutParams
 *
 * `LayoutParams` 没有通用的拷贝构造函数（`LinearLayout.LayoutParams` 的
 * `weight` 在 `MarginLayoutParams` 的拷贝构造里会丢），所以原值只存
 * **标量**（width / height / weight / visibility / leftMargin / marginStart），
 * 还原时写回同一个 `LayoutParams` 对象 —— 这样宿主自己持有的那个引用也一起被修正。
 *
 * ## 为什么还要记「改了几次」（[mutations]）
 *
 * 记账是为了**还原**，计数是为了**首帧屏障**：精简类功能经常一拍改不完
 * （见 `DockCleanFeature.fillRow` 的四招状态机，每招都要等下一帧量过才知道有没有用），
 * 那些中间态一旦被画出来就是肉眼可见的跳动。[mutations] 让
 * [UiCleanFeature] 知道「自上一帧以来界面动过」，于是可以只拦那一帧、
 * 等布局稳定后一次画出来 —— 用户看不到任何中间态。
 */
class ViewPatcher(private val tag: String) {

    private class Snapshot(
        val visibility: Int,
        val width: Int,
        val height: Int,
        val weight: Float,
        val leftMargin: Int,
        val marginStart: Int,
    )

    private val snapshots = Collections.synchronizedMap(WeakHashMap<View, Snapshot>())

    /** 被 [moveToEnd] 挪过位的视图 → 原来的序号 */
    private val orders = Collections.synchronizedMap(WeakHashMap<View, Int>())

    /** 已记账的改动处数（日志用） */
    val size: Int get() = synchronized(snapshots) { snapshots.size }

    /**
     * 累计改动次数 —— **单调递增**，还原也算一次，永不回退。
     *
     * 只服务一件事：[UiCleanFeature] 的「首帧屏障」靠它判断
     * 「自上一帧以来界面动过没有」。动过就把这一帧拦掉，让下一帧按新布局
     * 一次画出来 —— 用户永远不会看到「改到一半」的中间态。
     *
     * 所以**每个真的改动了视图的方法都必须 [bump]**，漏一个就会出现
     * 「改了但判定为没改」→ 中间态被画出来（正是本模块要消灭的闪烁）。
     */
    @Volatile
    var mutations: Int = 0
        private set

    private fun bump() {
        mutations++
    }

    // ------------------------------------------------------------------ 改

    /** 隐藏（`GONE`，不占位）。返回 true = 本次真的改了 */
    fun hide(v: View, why: String): Boolean {
        if (v.visibility == View.GONE) return false
        remember(v)
        v.visibility = View.GONE
        bump()
        if (GLog.isInfo) GLog.i("$tag：隐藏 $why（${describe(v)}）")
        return true
    }

    /**
     * 隐藏**并且**把高度压成 0 —— `GONE` 在 `RecyclerView` 的子项上不生效。
     *
     * 普通 `ViewGroup`（`LinearLayout` / `FrameLayout` / `RelativeLayout`）在测量时
     * 会主动跳过 `GONE` 的子视图，所以 `hide()` 就够了。但 `RecyclerView` 不是 ——
     * 它的 `LayoutManager` 会把**所有已挂上的子项**都测一遍并摆放，
     * 不判 `visibility`，于是 `GONE` 的那一项照样占着一条等高的空档。
     *
     * 实测行情市场页的「市场概览 / 滚动横幅 / 快捷入口 / 板块」都是
     * `#rv_markets_hs` 的**子项**，所以这里必须把 `layoutParams.height` 一起设成 0：
     * `getChildMeasureSpec` 拿到 0 就会按 `EXACTLY 0` 去测，那一项自然不占地方。
     */
    fun collapse(v: View, why: String): Boolean {
        val lp = v.layoutParams ?: return false
        if (v.visibility == View.GONE && lp.height == 0) return false
        remember(v)
        v.visibility = View.GONE
        lp.height = 0
        v.requestLayout()
        bump()
        if (GLog.isInfo) GLog.i("$tag：收起 $why（高度归零 + GONE，${describe(v)}）")
        return true
    }

    /**
     * 把高度钉成 [height] px（也接受 `WRAP_CONTENT` / `MATCH_PARENT` 这两个负值常量）。
     * 返回 true = 本次真的改了。
     */
    fun setHeight(v: View, height: Int, why: String): Boolean {
        if (height == 0) return false
        val lp = v.layoutParams ?: return false
        if (lp.height == height) return false
        remember(v)
        lp.height = height
        v.requestLayout()
        bump()
        if (GLog.isInfo) GLog.i("$tag：$why 高度 -> ${height}px（${describe(v)}）")
        return true
    }

    /**
     * 按比例压缩高度。
     *
     * 原高优先取**实测高度**（`v.height`）—— 因为绝大多数宿主容器的高度是
     * `wrap_content` 或 `match_parent`（`layoutParams.height` 是 -2 / -1，
     * 拿它乘比例毫无意义）。实在量不到就放弃，不做「猜一个数字」这种危险动作。
     */
    fun scaleHeight(v: View, factor: Float, why: String): Boolean {
        val now = UiClean.heightOf(v)
        if (now <= 0) return false
        return setHeight(v, (now * factor).toInt().coerceAtLeast(1), why)
    }

    /**
     * 把宽度钉成 [width] px（也接受 `WRAP_CONTENT` / `MATCH_PARENT` 这两个负值常量）。
     *
     * 用在「等分栅格里去掉一格之后把剩下的重新铺开」：宿主自己的栅格布局
     * （实测是 `com.guosen.commonui.SingleGridLayout`）不一定按可见子项重算格子宽度，
     * 也可能拿 `getChildCount()` 当列数、让 `GONE` 的那格继续占位。
     */
    fun setWidth(v: View, width: Int, why: String): Boolean {
        val lp = v.layoutParams ?: return false
        if (lp.width == width) return false
        remember(v)
        lp.width = width
        v.requestLayout()
        bump()
        if (GLog.isInfo) GLog.i("$tag：$why 宽度 -> ${width}px（${describe(v)}）")
        return true
    }

    /**
     * 设置左边距（`leftMargin` 与 `marginStart` 一起设，免得 RTL 下只有一个生效）。
     *
     * 只有在「子项位置本来就是靠边距排的」时候才该调用 —— 位置由布局自己算的栅格
     * 再叠一层边距会被推到两倍，见 `DockCleanFeature.fillRow` 的判据。
     */
    fun setLeftMargin(v: View, px: Int, why: String): Boolean {
        val lp = v.layoutParams as? MarginLayoutParams ?: return false
        if (lp.leftMargin == px && lp.marginStart == px) return false
        remember(v)
        lp.leftMargin = px
        lp.marginStart = px
        v.requestLayout()
        bump()
        if (GLog.isInfo) GLog.i("$tag：$why 左边距 -> ${px}px（${describe(v)}）")
        return true
    }

    /** 在横向 `LinearLayout` 里让该项「吃掉剩余空间」（`width=0` + `weight=1`） */
    fun stretch(v: View, why: String): Boolean {
        val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return false
        if (lp.width == 0 && lp.weight > 0f) return false
        remember(v)
        lp.width = 0
        lp.weight = 1f
        v.requestLayout()
        bump()
        if (GLog.isInfo) GLog.i("$tag：$why 均分铺满（${describe(v)}）")
        return true
    }

    // ------------------------------------------------------------------ 还原

    /**
     * 把 [v] 挪到父容器的**最后一位**（其余子项整体前移），并记住原位置。
     *
     * 用在「宿主栅格按子项序号摆位」这种版式上：想把少掉的那一格的影响挪到最右边
     * （屏幕外），就只能改变它的序号 —— 实测 `SingleGridLayout` 既不吃子项宽度、
     * 也不吃边距，只按「序号 × 行宽 ÷ 子项数」摆。
     *
     * ⚠️ **动了顺序就可能影响宿主的「tab 序号 → 页面」映射**：如果宿主在点击回调里
     * 用 `indexOfChild` 现算序号（而不是在注册时捕获），后面的 tab 就会点错页。
     * 所以调用方必须自己验证一次再决定要不要留着。
     */
    fun moveToEnd(v: View): Boolean {
        val parent = v.parent as? ViewGroup ?: return false
        val from = parent.indexOfChild(v)
        if (from < 0 || from == parent.childCount - 1) return false
        if (synchronized(orders) { orders.getOrPut(v) { from } } != from) return false
        runCatching {
            parent.removeView(v)
            parent.addView(v)
        }.onFailure { GLog.w("$tag：挪位失败（${describe(v)}）", it); return false }
        bump()
        if (GLog.isInfo) GLog.i("$tag：把 ${describe(v)} 从第 $from 位挪到最后一位（宿主栅格按序号摆位，只能这么绕）")
        return true
    }

    /** 把全部改动还原成宿主原样；幂等，重复调用安全 */
    fun restoreAll() {
        val entries = synchronized(snapshots) {
            val list = snapshots.entries.toList()
            snapshots.clear()
            list
        }
        val moved = restoreOrder()
        for ((v, s) in entries) restoreTo(v, s)
        if (entries.isNotEmpty() || moved) bump()
        if (entries.isNotEmpty()) GLog.i("$tag：已还原 ${entries.size} 处界面改动")
    }

    /**
     * 只还原**某一个**视图，其余改动保留。
     *
     * 用于「某一招试了没用，退回去别留后患」——例如把整行加宽之后发现
     * 宿主栅格把最后一格推出了屏幕，就得把加宽撤掉、退回到留一条空档。
     */
    fun restore(v: View) {
        val s = synchronized(snapshots) { snapshots.remove(v) } ?: return
        restoreTo(v, s)
        bump()
        if (GLog.isInfo) GLog.i("$tag：已还原 1 处（${describe(v)}）")
    }

    /** 把挪过位的视图挪回原位置；返回 true = 真的挪过 */
    private fun restoreOrder(): Boolean {
        val entries = synchronized(orders) {
            val list = orders.entries.toList()
            orders.clear()
            list
        }
        for ((v, index) in entries) {
            val parent = v.parent as? ViewGroup ?: continue
            runCatching {
                parent.removeView(v)
                parent.addView(v, index.coerceAtMost(parent.childCount))
            }.onFailure { GLog.w("$tag：挪回原位失败（${describe(v)}）", it) }
        }
        return entries.isNotEmpty()
    }

    private fun restoreTo(v: View, s: Snapshot) {
        runCatching {
            v.visibility = s.visibility
            v.layoutParams?.let { lp ->
                lp.width = s.width
                lp.height = s.height
                (lp as? LinearLayout.LayoutParams)?.let { it.weight = s.weight }
                (lp as? MarginLayoutParams)?.let {
                    it.leftMargin = s.leftMargin
                    it.marginStart = s.marginStart
                }
            }
            v.requestLayout()
        }.onFailure { GLog.w("$tag：还原失败（${describe(v)}）", it) }
    }

    // ------------------------------------------------------------------ 内部

    private fun remember(v: View) {
        if (snapshots.containsKey(v)) return
        val lp = v.layoutParams
        val mlp = lp as? MarginLayoutParams
        snapshots[v] = Snapshot(
            visibility = v.visibility,
            width = lp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            height = lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
            weight = (lp as? LinearLayout.LayoutParams)?.weight ?: 0f,
            leftMargin = mlp?.leftMargin ?: 0,
            marginStart = mlp?.marginStart ?: 0,
        )
    }

    /** 日志里的可读名字：类名 + 资源 id 名（拿不到就只报类名） */
    private fun describe(v: View): String {
        val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
        return v.javaClass.simpleName + if (name != null) " #$name" else ""
    }
}
