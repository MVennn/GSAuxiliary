package io.github.SansKritO4.GSAuxiliary.feature.uiclean

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「界面精简」类功能的公共工具。
 *
 * ## 为什么需要这一层
 *
 * 精简类功能（去掉底栏某一项、隐藏一条横幅、把六项压成三项）与
 * 「往界面里插控件」是两种难度：
 *
 *  - 插控件要**造**一个视图，必须精确知道宿主用什么容器、什么约束（见
 *    `StockDetailBarFeature` 的一堆踩坑记录）；
 *  - 精简只要**认**出现成的视图，然后 `GONE` 掉或者改个高度 ——
 *    对「认」的精度要求低得多，可以大量依赖**结构特征 + 文案**，
 *    而不是硬编码资源 id。
 *
 * 所以这里提供的是「认视图」的工具箱：遍历、按文案找、按结构判、
 * 资源 id 多级兜底、以及**认不出来时把视图树打出来**（[dump]）。
 *
 * ## 与宿主 App 的关系
 *
 * 全部只读 + 只改 `visibility` / `layoutParams.height`，不碰任何数据、
 * 不触发任何业务方法。宿主升级后结构变了，最坏结果是「认不出来 → 什么都不做」，
 * 不会改错地方 —— 唯一的例外是「按文案找」可能撞到同名控件，
 * 所以每个功能都额外加了**位置/结构约束**（见各 `*Targets.kt` 的判据注释）。
 */
object UiClean {

    // ------------------------------------------------------------------ 遍历

    /** 深度优先遍历；[visit] 返回 `true` 表示「到此为止」，整棵树不再继续 */
    fun walk(v: View, visit: (View) -> Boolean) {
        if (visit(v)) return
        if (v !is ViewGroup) return
        for (i in 0 until v.childCount) walk(v.getChildAt(i), visit)
    }

    /** 深度优先找第一个满足 [predicate] 的视图；找不到返回 null */
    fun find(v: View, predicate: (View) -> Boolean): View? {
        var hit: View? = null
        walk(v) { cur ->
            if (hit != null) return@walk true
            if (predicate(cur)) {
                hit = cur
                true
            } else {
                false
            }
        }
        return hit
    }

    /** 深度优先收集满足 [predicate] 的视图，最多 [limit] 个 */
    fun findAll(v: View, predicate: (View) -> Boolean, limit: Int = 64): List<View> {
        val out = ArrayList<View>()
        walk(v) { cur ->
            if (out.size >= limit) return@walk true
            if (predicate(cur)) out.add(cur)
            false
        }
        return out
    }

    // ------------------------------------------------------------------ 文本

    /** 视图上的可见文案；空串 / 全空白一律当「没有文案」 */
    fun textOf(v: View): String? =
        (v as? TextView)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /** 子树（含自身）里第一个文案等于 [text] 的 `TextView` */
    fun findText(root: View, text: String): TextView? =
        find(root) { it is TextView && textOf(it) == text } as? TextView

    /** 子树（含自身）里第一个文案命中 [texts] 的 `TextView` */
    fun findTextAny(root: View, texts: Collection<String>): TextView? =
        find(root) { it is TextView && texts.contains(textOf(it)) } as? TextView

    /** 子树（含自身）里是否出现过这些文案中的任意一条 */
    fun hasAnyText(root: View, texts: Collection<String>): Boolean =
        find(root) { it is TextView && texts.contains(textOf(it)) } != null

    // ------------------------------------------------------------------ 结构

    /** 子树里有没有图标（`ImageView`，或带 `drawableTop` 的 `TextView`） */
    fun hasIcon(v: View): Boolean = find(v) {
        it is ImageView || (it is TextView && it.compoundDrawables.getOrNull(1) != null)
    } != null

    /** 子树里第一个有文案的 `TextView` */
    fun firstLabel(v: View): TextView? =
        find(v) { it is TextView && !textOf(it).isNullOrEmpty() } as? TextView

    /**
     * 「一格入口」：图标在上、文字在下的一格。
     *
     * 底栏 tab、首页快捷入口、行情页快捷入口都是这个形状，
     * 所以这个判据被三处复用 —— 它是本层最重要的结构特征。
     */
    fun looksLikeEntryItem(v: View): Boolean = v is ViewGroup && hasIcon(v) && firstLabel(v) != null

    /** 屏幕上真的画得出来（`GONE`、零尺寸、父链不可见都算 false） */
    fun visibleOnScreen(v: View): Boolean = v.isShown && v.width > 0 && v.height > 0

    /** 屏幕上的实际高度（已布局用实测值，没布局用测量值，都没有返回 0） */
    fun heightOf(v: View): Int {
        if (v.height > 0) return v.height
        if (v.measuredHeight > 0) return v.measuredHeight
        return 0
    }

    /** [v] 在 [root] 坐标系里的矩形；不是后代或还没布局时返回 null */
    fun rectIn(root: View, v: View): Rect? {
        val r = Rect()
        // 注意：View.getDrawingRect 是 void（把结果写进参数里），不是返回布尔
        v.getDrawingRect(r)
        if (r.width() <= 0 || r.height() <= 0) return null
        if (root === v) return r
        // offsetDescendantRectToMyCoords 定义在 ViewGroup 上（它要按自己的坐标系换算）
        val group = root as? ViewGroup ?: return null
        return runCatching {
            group.offsetDescendantRectToMyCoords(v, r)
            r
        }.getOrNull()
    }

    /** dp → px */
    fun dp(v: View, value: Int): Int =
        (value * v.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 一行里宽度和 [ref] 相近的其它格子。
     *
     * 用在「底栏去掉一项之后，剩下的项要重新铺满整行」——
     * 只挑宽度相近的兄弟，避免把行里那些**不是格子**的子视图
     * （分隔线、居中凸起的按钮）一起拉伸。
     *
     * [refWidth] 用来覆盖 [ref] 自己的宽度：调用方通常**已经把 ref 隐藏了**
     * （`GONE` 的视图宽度归零），这时必须把隐藏前量到的宽度传进来。
     */
    fun peersOf(ref: View, refWidth: Int = 0): List<View> {
        val row = ref.parent as? ViewGroup ?: return emptyList()
        val refW = refWidth.takeIf { it > 0 } ?: ref.width.takeIf { it > 0 } ?: return emptyList()
        val tol = (refW * 0.35f).toInt()
        val out = ArrayList<View>(row.childCount)
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c === ref) continue
            if (c.visibility != View.VISIBLE) continue
            val w = c.width.takeIf { it > 0 } ?: continue
            if (kotlin.math.abs(w - refW) <= tol) out.add(c)
        }
        return out
    }

    // ------------------------------------------------------------------ Context

    /** 从任意 `Context` 上溯到 `Activity`（`View.context` 通常是 `ContextThemeWrapper`） */
    fun activityOf(context: Context?): Activity? {
        var c = context
        var guard = 0
        while (c is ContextWrapper && guard++ < 12) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    fun activityOf(v: View?): Activity? = activityOf(v?.context)

    // ------------------------------------------------------------------ 资源 id

    private val idCaches = Collections.synchronizedMap(WeakHashMap<Context, IdCache>())

    /** 取（并缓存）某个 `Context` 上的资源 id 解析器 */
    fun idsFor(context: Context, pkg: String): IdCache =
        synchronized(idCaches) { idCaches.getOrPut(context) { IdCache(context, pkg) } }

    // ------------------------------------------------------------------ 诊断

    /**
     * 把一棵视图树压成多行文本 —— **认不出目标控件时的唯一救命稻草**。
     *
     * 输出形如（缩进即层级）：
     *
     * ```
     * FrameLayout #content [0,0][1080,2205]
     *   LinearLayout #ll_home_menu [0,320][1080,520]
     *     LinearLayout [0,320][180,520]
     *       ImageView #iv_item_icon [30,340][90,400]
     *       TextView #tv_item_name "新股申购" [10,404][170,440]
     * ```
     *
     * 每一行都带**真实资源 id 名**（`getResourceEntryName` 反查）与屏幕坐标，
     * 拿到之后直接往对应 `*Targets.kt` 的候选数组里加一项即可，不需要 adb。
     */
    fun dump(root: View, ids: IdCache?, maxNodes: Int = 140, maxDepth: Int = 14): String {
        val sb = StringBuilder()
        var n = 0
        fun rec(v: View, depth: Int) {
            if (n >= maxNodes || depth > maxDepth) return
            n++
            val r = rectIn(root, v)
            sb.append("  ".repeat(depth)).append(v.javaClass.simpleName)
            sb.append(" #").append(ids?.nameOf(v.id) ?: v.id.toString())
            if (r != null) {
                sb.append(" [").append(r.left).append(',').append(r.top)
                    .append("][").append(r.right).append(',').append(r.bottom).append(']')
            } else {
                sb.append(" [未布局]")
            }
            if (v.visibility != View.VISIBLE) sb.append(" vis=").append(v.visibility)
            textOf(v)?.let { sb.append(" \"").append(it.take(24)).append('"') }
            sb.append('\n')
            if (v is ViewGroup) for (i in 0 until v.childCount) rec(v.getChildAt(i), depth + 1)
        }
        rec(root, 0)
        if (n >= maxNodes) sb.append("  …（已截断，共 $n 个节点）\n")
        return sb.toString()
    }
}

/**
 * 「资源 id 名 → id」解析器，带进程级缓存。
 *
 * 宿主被加固，静态反编译拿不到 `R.id`，但**运行时**用
 * `Resources.getIdentifier(name, "id", pkg)` 一定拿得到 ——
 * 这正是本项目「候选名 + 多级兜底」容错模式的基础。
 *
 * 反查（id → 名字）走 `getResourceEntryName`，供 [UiClean.dump] 输出可读的名字。
 */
class IdCache(context: Context, private val pkg: String) {

    private val res = (context.applicationContext ?: context).resources

    private val byName = HashMap<String, Int>()

    /** 解析一个 id 名；不存在返回 0（解析结果同样缓存，含 0） */
    fun of(name: String): Int = synchronized(byName) {
        byName.getOrPut(name) {
            runCatching { res.getIdentifier(name, "id", pkg) }.getOrDefault(0)
        }
    }

    /** 按候选顺序取第一个存在的 id；全都不存在返回 0 */
    fun firstOf(names: Array<String>): Int {
        for (n in names) {
            val id = of(n)
            if (id != 0) return id
        }
        return 0
    }

    /** id → 资源名（反查失败返回 null，不抛） */
    fun nameOf(id: Int): String? =
        if (id == 0) null else runCatching { res.getResourceEntryName(id) }.getOrNull()
}
