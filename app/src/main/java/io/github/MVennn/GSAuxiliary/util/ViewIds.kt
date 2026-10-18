package io.github.MVennn.GSAuxiliary.util

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Collections
import java.util.WeakHashMap

/**
 * 通用视图树工具：资源 id 解析、视图树快照，以及快照依赖的文案 / 几何小工具。
 *
 * ## 为何放这里（2026-10-17 从 feature/uiclean 上提）
 *
 * `PageSyncFeature`（feature/ 包的「改视图」调度骨架）要用 [IdCache] 预热资源 id、
 * 用 [dump] 在认不出目标控件时打视图树快照；而这些东西原先定义在
 * `feature/uiclean/UiClean` 上 —— 于是 feature/ 反向依赖 feature/uiclean，
 * 形成 feature/ ↔ feature/uiclean 的包级循环。
 * 它们本就是与「精简」语义无关的通用件（id 解析只依赖 `Resources`、
 * 快照只依赖 `View` 树），上提到 util 后 PageSyncFeature 只依赖 util，循环解除。
 * `UiClean` 保留的只剩真正的精简语义（遍历 / 文案查找 / 结构判据），
 * 并以薄委托入口兼容既有调用方（见 `UiClean` 类注释）。
 *
 * 本层只依赖 Android 框架类，不依赖 libxposed api。
 */
object ViewIds {

    // ------------------------------------------------------------------ 文案 / 几何

    /** 视图上的可见文案；空串 / 全空白一律当「没有文案」 */
    fun textOf(v: View): String? =
        (v as? TextView)?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /** [v] 在 [root] 坐标系里的矩形；不是后代或还没布局时返回 null */
    fun rectIn(root: View, v: View): Rect? = rectIn(root, v, Rect())

    /**
     * 同 [rectIn]，但把结果写进调用方给的 [out]。
     *
     * 热路径上（如 `NavBarTint.coveringChild` 逐层逐子项量位置）用同一个 `Rect`
     * 反复量，就不再是「每个子项一个 `Rect`」。返回 `null` 表示量不出来 ——
     * 此时 [out] 的内容无意义，调用方不要读。
     */
    fun rectIn(root: View, v: View, out: Rect): Rect? {
        // 注意：View.getDrawingRect 是 void（把结果写进参数里），不是返回布尔
        v.getDrawingRect(out)
        if (out.width() <= 0 || out.height() <= 0) return null
        if (root === v) return out
        // offsetDescendantRectToMyCoords 定义在 ViewGroup 上（它要按自己的坐标系换算）
        val group = root as? ViewGroup ?: return null
        return runCatching {
            group.offsetDescendantRectToMyCoords(v, out)
            out
        }.getOrNull()
    }

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
 * 「资源 id 名 → id」解析器。
 *
 * 宿主被加固，静态反编译拿不到 `R.id`，但**运行时**用
 * `Resources.getIdentifier(name, "id", pkg)` 一定拿得到 ——
 * 这正是本项目「候选名 + 多级兜底」容错模式的基础。
 *
 * ## 缓存分两层，因为命中与未命中不一样
 *
 *  - **命中**（解析出非 0）：写进 [HITS] 里**本包**那张表，**进程级共享** ——
 *    同一个包里的 id 与配置、与哪个 `Context` 都无关，一次拿到就永远有效。
 *  - **未命中**（返回 0）：只写进本实例的 `misses`。理由：0 有可能是**时机问题**
 *    而不是真的不存在 —— 名字是在启动早期（启动图那会儿）被 [warm] 预解析的，
 *    那时万一资源还没装配完，进程级缓存会把「这次没查到」永久钉死。
 *    所以未命中按 `Context` 各记一份，换个页面还会再试一次，代价只是一次函数调用。
 *
 * ## 命中表按包分表（2026-10-19）
 *
 * 原先命中表是「`"包名/资源名"` → id」一张全局表，于是**每次查表都要先拼一次字符串**
 * 再抢一次全局锁 —— 而查表本身就在布局回调的热路径上（`firstOf` 一趟按候选顺序查十几个名字）。
 * 改成「包名 → (资源名 → id)」两层：构造期取一次本包那张表的引用（本类的 `pkg` 是不可变的），
 * 之后查表直接用名字做键，锁也落在本包那张表上，不再跨包互斥。
 *
 * 反查（id → 名字）走 `getResourceEntryName`，供 [ViewIds.dump] 输出可读的名字。
 */
class IdCache(context: Context, private val pkg: String) {

    private val res = (context.applicationContext ?: context).resources

    /** 本包在进程级共享的命中表；构造期取一次引用，之后查表不再拼串、不再抢全局锁 */
    private val hits: HashMap<String, Int> =
        synchronized(HITS) { HITS.getOrPut(pkg) { HashMap() } }

    /** 本 `Context` 上查不到的 id 名（见类注释：未命中不跨 `Context` 共享） */
    private val misses = HashMap<String, Int>()

    /**
     * 解析一个 id 名；不存在返回 0。
     *
     * `getIdentifier` 是**按名字扫资源表**，不便宜（每个名字一次）。
     * 所以启动路径上的解析一定要提前做掉 —— 见 [warm]。
     */
    fun of(name: String): Int {
        synchronized(hits) { hits[name] }?.let { return it }
        synchronized(misses) { misses[name] }?.let { return it }
        val id = runCatching { res.getIdentifier(name, "id", pkg) }.getOrDefault(0)
        if (id != 0) synchronized(hits) { hits[name] = id }
        else synchronized(misses) { misses[name] = 0 }
        return id
    }

    /**
     * 先把一批名字解析好（幂等，重复调用只剩一次哈希查表）。
     *
     * 调用点就是全部意义：`UiCleanFeature` 在**每个页面 resume 时**（早于该页第一次布局）
     * 就调它，而第一个 resume 的页面通常正是**启动图** —— 那一两秒主线程闲着，
     * 正好把这十几个 `getIdentifier` 算掉，等真正要改界面时不占首帧的时间。
     *
     * 返回解析到的数量（日志用）。
     */
    fun warm(names: Array<String>): Int {
        var hit = 0
        for (n in names) if (of(n) != 0) hit++
        return hit
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

    private companion object {
        /** `包名 -> (资源名 -> id)`，进程级共享（只存命中的） */
        val HITS = HashMap<String, HashMap<String, Int>>()
    }
}
