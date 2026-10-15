package io.github.SansKritO4.GSAuxiliary.feature.uiclean

import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import io.github.SansKritO4.GSAuxiliary.core.GLog
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

    /** 已记账的改动处数（日志用） */
    val size: Int get() = synchronized(snapshots) { snapshots.size }

    // ------------------------------------------------------------------ 改

    /** 隐藏（`GONE`，不占位）。返回 true = 本次真的改了 */
    fun hide(v: View, why: String): Boolean {
        if (v.visibility == View.GONE) return false
        remember(v)
        v.visibility = View.GONE
        GLog.i("$tag：隐藏 $why（${describe(v)}）")
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
        GLog.i("$tag：$why 高度 -> ${height}px（${describe(v)}）")
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
        GLog.i("$tag：$why 宽度 -> ${width}px（${describe(v)}）")
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
        GLog.i("$tag：$why 左边距 -> ${px}px（${describe(v)}）")
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
        GLog.i("$tag：$why 均分铺满（${describe(v)}）")
        return true
    }

    // ------------------------------------------------------------------ 还原

    /** 把全部改动还原成宿主原样；幂等，重复调用安全 */
    fun restoreAll() {
        val entries = synchronized(snapshots) {
            val list = snapshots.entries.toList()
            snapshots.clear()
            list
        }
        for ((v, s) in entries) {
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
        if (entries.isNotEmpty()) GLog.i("$tag：已还原 ${entries.size} 处界面改动")
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
