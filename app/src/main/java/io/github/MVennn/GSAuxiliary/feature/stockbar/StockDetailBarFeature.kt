package io.github.MVennn.GSAuxiliary.feature.stockbar

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.PageSyncFeature
import io.github.MVennn.GSAuxiliary.util.Reflect
import io.github.MVennn.GSAuxiliary.util.ViewIds
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 功能：个股详情页底栏，在「自选」与「更多」之间注入一个 **「智能盯盘」快捷入口**。
 *
 * 「智能盯盘」原本埋在底栏「更多」的弹层里（个股页是第 2 项、指数页是第 1 项），
 * 点两下才到；本功能把它提到一级位置，底栏上直接可点。
 *
 * **LSPosed 运行时视图注入，不改目标 App 源码、不碰任何数据。**
 *
 * ## 一、怎么让买卖让位（踩过的坑，别改回去）
 *
 * 底栏那一行按钮是 **`ConstraintLayout`**（id 却叫 `ll_bottom_stk_normal`，名字骗人）。
 * 买 / 卖 是**带权重的链成员**（`layout_width=0dp` + weight），撤单 / 自选 / 更多是固定宽。
 *
 * 直觉上「往这一行 addView 一个固定宽按钮，买卖就会自动让位」—— **错的**。
 * 实测直接 addView 后新按钮被丢在容器左上角（`[0,2216][124,2326]`），买卖一个像素都没让：
 * `ConstraintLayout` 里**不带约束的子视图不参与链的测量**。
 *
 * 改成「克隆『更多』的约束 + 把前后两个邻居的约束改接过来」也能跑，但**非常脆**：
 * 实测一旦某一处约束没接上，整条链就散架（买卖被压成 0 宽、按钮和「更多」叠在一起）。
 *
 * **最终采用的办法：不碰任何约束，只把「更多」那个盒子加宽。**
 * 「更多」的盒子（`#fl_more`）本身就是链上的固定宽成员，把它加宽，
 * 链上剩余空间自然变少 → **买卖自动各让一半**。然后把新按钮和原来的「更多」
 * 并排塞进这个盒子里：
 *
 * ```
 * #fl_more  (宽度 96px → wrap_content，变宽 = 买卖让位)
 *   └ LinearLayout(横向)
 *        ├ 我们的「智能盯盘」按钮
 *        └ 原来的「更多」TextView（原样搬过来，点击行为完全不变）
 * ```
 *
 * 关开关时**逆向还原**：把「更多」搬回盒子、盒子宽度改回原值、删掉容器。
 *
 * ## 二、怎么自适应「个股 / 指数 / 板块」两套底栏
 *
 * 两套形态的按钮**在同一个 `ConstraintLayout` 里，靠 visibility 互斥**（另一套是 `GONE`）。
 * 所以锚点取「**当前可见的那个**『更多』」，个股形态（`iv_bottom_more`）与指数形态
 * （`iv_bottom_index_more`）走同一段代码 —— 不需要判断页面类型。
 *
 * ## 三、点下去怎么和原版走同一条路
 *
 * 「智能盯盘」的落点是 H5：`action=router_aistare_webview`，extras 里的 `url`
 * 带 **`userCode`（账号维度）**，自己拼 URL 既不可靠也不合适。所以这里
 * **复用宿主自己的点击路径**：
 *
 * ```
 * ① 触发「更多」按钮的点击        → 宿主弹出 PopupWindow（面板）
 * ② 在 PopupWindow 显示钩子里认出这次是我们触发的
 *    → 面板内容设成透明（用户一帧都看不到，不会闪）
 * ③ 下一帧在面板里按**文字**找到「智能盯盘」项
 *    → grid.performItemClick(...)，与用户手动点那一项完全同一条路径
 * ④ 关掉面板、还原透明度
 * ```
 *
 * 光把弹层设透明还不够 —— 宿主弹面板时**还会把 Activity 窗口调暗**
 * （`LayoutParams.alpha` 1.0 → 0.6），那几帧照样会画出来，用户看到「闪一下黑」。
 * 详见 [hookWindowLayout]。
 *
 * 按文字而不是按下标，是因为「智能盯盘」在个股页是第 2 项、指数页是第 1 项。
 *
 * ### 握手（认领）只有一条规矩：**先验指纹，再清标记**
 *
 * 上面 ①→④ 这几步不是原子的。「我们点了『更多』」与「面板显示出来」之间隔着宿主的一次
 * `show()`，而这一步随时可能被别的事情插进来。早期实现是「谁先看到弹层谁把标记清零」，
 * 于是有三种情况会让面板**落在我们手里却没人管它** —— 用户看到的就是
 * **「点了『智能盯盘』却弹出了『更多』」**（偶发，取决于时序）：
 *
 *  1. 弹层显示的那一刻 `contentView` 还是 null、或里面还没有 `#gv_stk_bottom`：
 *     标记已被清掉，等真正的更多面板显示出来时**已经没人认领**了；
 *  2. 图标采集那一轮（注入后隐形开一次面板，见 [maybeHarvestIcon]）与用户点击撞在一起：
 *     采集的弹层抢走了点击的标记，而点击自己那次 `performClick` 又开出**第二个面板**，
 *     两个都没人认；
 *  3. 上一轮还没走完用户又点了一下：单个标记被覆盖，两次 `performClick` 开出两个面板，
 *     只有一个能被认领。
 *
 * 现在只有一条规矩：**验过指纹才允许认领**（`PopupWindow.contentView` 里找得到
 * `#gv_stk_bottom` 才算我们的面板）。验不过就**一个字节都不动**，标记原样留着等真正的面板；
 * 而且**同一轮只能认领一次**（[Round.claimed]）—— `showAsDropDown` 那几个重载会嵌套回调，
 * 重复的认领请求直接被挡掉。
 *
 * 点击与采集两轮**互斥**：点击优先，会先把「还没弹面板」的采集轮掐掉（见 [openEntry]）；
 * 上一轮点击没走完时再来一次点击会被合并（绝不开第二个面板）。一轮的生死只有一条路：
 * 认领 → 处理 → 关闭 → [round] 清空（窗口夹子随之中止，不会留下来影响用户手点「更多」）。
 *
 * ⚠️ **不要指望面板项有可点击祖先**：宿主的点击是挂在 `GridView` 上的
 * `OnItemClickListener`（探针栈 `StockDetailFrag$34#onItemClick` 就是它），
 * 项容器本身不保证 `clickable`。所以必须走 `performItemClick`。
 *
 * ## 四、注入时机：**不能再靠 `onResume`**
 *
 * `StockFragAct` 继承 `EngineAct`，引擎片段是**异步挂载**的 —— 实测 `onResume`
 * 那一刻 `decorView.findViewById(stk_detial_bottom_bar)` 返回 **null**，
 * 而且底栏容器出现之后里面的按钮还要再晚一点才齐。
 *
 * 早期版本在这里挂了「60 次 × 100ms 的有限重试」，并且只在 `Activity.onResume`
 * 重新对齐状态 —— **真机上就掉在这里**：详情页左右翻页/换形态都不会走 `onResume`，
 * 宿主会把底栏（甚至整块 Fragment）重建一遍，我们注入的按钮跟着旧视图一起消失，
 * 而「重试」早就跑完了 → 用户看到的就是「翻几次之后智能盯盘没了」。
 *
 * 现在改成 [PageSyncFeature] 的调度：**布局回调驱动 + 幂等自愈**。
 * 宿主每次都靠布局把新视图铺开，所以每次重建都会给我们一次 `sync()`；
 * 而 [sync] 判的是「**我们的按钮现在还在不在盒子里**」（[liveRecord]），
 * 不是「我记不记得自己注入过」—— 后者会在宿主把子视图清掉之后永远误判成「已经好了」。
 *
 * 于是两种真实场景都被覆盖：
 *
 *  - **翻页换形态**（个股 ↔ 指数 / 板块）：两套按钮在同一个 `ConstraintLayout` 里靠
 *    visibility 互斥，另一种形态的「更多」是**另一个盒子** → 各自注入一份，翻到哪页都有；
 *  - **宿主重建底栏**：旧记录判为失效 → 摘掉自己的容器 → 重新注入（并重新加宽盒子）。
 *
 * 顺带把「注入时机」也钉在**这一帧画出来之前**：改完若还没稳定，首帧屏障会把这一帧拦掉
 * （见 [PageSyncFeature]），所以不会出现「先原样、再跳一下」。
 *
 * ## 五、图标
 *
 * 底栏按钮是「图标在上、文字在下」的样式，图标来自面板里那一项自带的
 * `drawableTop`。注入后**异步采集一次**（同样是隐形开一次面板），取到就套到
 * 所有已注入的按钮上；取不到就退化成纯文字按钮，功能不受影响。
 */
class StockDetailBarFeature : PageSyncFeature() {

    override val id = "stock_bar_entry"
    override val title = "个股详情页底栏智能盯盘入口"

    /** 只在这一个页面跑同步：别的页面（H5、资讯、首页…）连一次 `sync` 都不该进来 */
    override val pages = StockDetailBarTargets.WATCH_ACTIVITIES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.stockBarEntry

    /** 预热：这些 id 名在进入任意页面时就先解析掉（省首帧里的 `getIdentifier`） */
    override val idNames = StockDetailBarTargets.BOTTOM_BAR_ID_CANDIDATES +
        StockDetailBarTargets.MORE_TEXT_ID_CANDIDATES +
        StockDetailBarTargets.MORE_BOX_ID_CANDIDATES +
        StockDetailBarTargets.MENU_GRID_ID_CANDIDATES +
        StockDetailBarTargets.MENU_ITEM_TEXT_ID_CANDIDATES

    /**
     * 还有没落地的活就不让限速闸挡（见 [PageSyncFeature.needsFollowUp]）。
     *
     * 三种情况都发生在宿主**刚重建或刚重排过**之后，而那一趟布局回调正是必须进来的 ——
     * 被限速延后的话，用户看到的就是「左右滑动时不注入，要停留一会儿才出现」、
     * 「先注入文字，图标过一会儿才补上」（2026-10-19 修 BUG1）：
     *
     *  1. 现场还没复核过（刚注入 / 宿主刚重建过底栏）→ 立刻看一眼；
     *  2. 现场失活（我们的容器已经不在盒子里）→ 立刻重新注入；
     *  3. 图标采到过、这颗按钮却还没套上 → 立刻补。
     *
     * 收敛后必须回到 false（由 [reassert] 复核通过后置 [Injected.confirmed]），
     * 否则详情页每一次布局回调都会全量重扫一遍底栏。
     */
    override val needsFollowUp: Boolean
        get() = synchronized(injected) { injected.any { (_, e) -> !settled(e) } }

    /** 这一份现场算不算「看过一眼、确实落地」—— [needsFollowUp] 的判据 */
    private fun settled(e: Injected): Boolean {
        // 盒子已经不在窗口上（分页器把那一页销毁了）：这份记账只是**过期**，不是「没落地」——
        // 宿主把那一页翻回来是**新的**视图对象，会走一次全新的注入。不这么判的话，
        // 一根残留记账就能让本页永远不进限速闸（滚动期每帧全量重扫）。
        if (!e.box.isAttachedToWindow) return true
        if (e.holder.parent !== e.box) return false
        if (e.button.parent !== e.holder) return false
        if (!e.button.isAttachedToWindow) return false
        if (!e.confirmed) return false
        // 图标采到过就必须套上；还没采到不算「没落地」（采集是异步的，见 maybeHarvestIcon）
        return entryIcon == null || topDrawableOf(e) != null
    }

    /** 一次注入的现场，用于「关开关立即还原」 */
    private class Injected(
        val box: ViewGroup,
        val holder: LinearLayout,
        val moreText: TextView,
        val button: TextView,
        val originalBoxWidth: Int,
        /** 「更多」摘出前在盒子里的下标 —— detach 时按原位放回，别再现场查（那时它已不在盒子里，恒 -1） */
        val originalMoreIndex: Int,
        /** 「更多」那个图标的目标尺寸 —— 面板里采到的图标比它大，不缩会顶破底栏 */
        val iconW: Int,
        val iconH: Int,
    ) {
        /**
         * 这一份现场**复核过一眼、确实落地**了没有 —— [needsFollowUp] 只看它。
         *
         * 注入或宿主重建之后先置 false：宿主紧接着的那一趟布局（重建底栏、重排栅格、
         * 套回它自己的 `LayoutParams`）正是会把我们改回去的一趟，必须让它进来复核，
         * 复核通过（[reassert]）才置 true，重新交给限速闸。
         */
        @Volatile var confirmed = false
    }

    /**
     * 「我们主动弹面板」**之前**的宿主窗口状态。
     *
     * 宿主弹「更多」面板时会把 Activity 窗口调暗（实测 `alpha` 由 1.0 变
     * `0.60009766`），窗口一暗整页就闪一下黑。这里记下弹之前的值，
     * 在 `PopupWindow.show()` 返回的**同一帧内**还原回去 —— 一帧都没画过，用户看不到。
     */
    private class WindowSnapshot(
        val decor: View,
        val wm: WindowManager?,
        val lp: WindowManager.LayoutParams?,
        val alpha: Float,
        val dim: Float,
        val viewAlpha: Float,
    )

    /**
     * 一次「我们主动开面板」的全过程（null = 空闲）。
     *
     * 旧实现用三颗 `AtomicInteger`（`pendingClick` / `pendingHarvest` / `inFlight`）
     * 分别记「点击」「采集」「全程」，三者的生死时间对不齐，留下的坑正是
     * 「点『智能盯盘』却弹出『更多』」（见类注释第三节）。现在一轮就是一个对象：
     * 「谁在等面板」「认领了没有」「窗口快照是谁的」都在一起，
     * 收尾只有 [end] 一条路，不会出现「标记清了、夹子还架着」这种半死状态。
     */
    private class Round(
        /** 代次，只用于日志（看得懂「哪一轮」） */
        val gen: Int,
        /** true = 图标采集（隐形开一次面板取图标），false = 用户点击 */
        val harvest: Boolean,
    ) {
        /** 弹面板之前的宿主窗口状态（`performClick` 之前抓，见 [captureWindow]） */
        @Volatile var window: WindowSnapshot? = null

        /** 面板已被我们认领 —— **同一轮只允许认领一次**（嵌套的 show 重载会重复回调） */
        val claimed = AtomicBoolean(false)
    }

    // ------------------------------------------------------------------ 状态

    /** 面板里那一项的图标（进程级缓存，取到一次即可复用） */
    @Volatile private var entryIcon: Drawable? = null

    /** 是否已经发起过图标采集（失败会复位，允许下次重试） */
    private val harvestStarted = AtomicBoolean(false)

    /** 当前那一轮（null = 空闲）；点击与采集**互斥**，见 [openEntry] */
    private val round = AtomicReference<Round?>(null)

    /** 轮次代次（只用于日志） */
    private val gen = AtomicInteger(0)

    /** 已注入的现场，键是「更多」的盒子（弱引用） */
    private val injected =
        Collections.synchronizedMap(WeakHashMap<ViewGroup, Injected>())

    /**
     * 本实例见过的页面 `decorView`（弱引用）。
     *
     * 热重载兜底清理（[restore] 里的按 tag 扫描）需要一个扫描起点，而走到那条路时
     * 基类 [PageSyncFeature] 的 decorView 表已先被 detach 清空 —— 这里自己留一份。
     */
    private val seenDecors = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /** 每轮只打一条「拦住宿主调暗」的日志，避免刷屏 */
    private val guardLogged = AtomicBoolean(false)

    /** 资源 id 缓存：0 = 还没解析过，-1 = 解析过但不存在，>0 = 命中 */
    @Volatile private var bottomBarIdCache = 0
    @Volatile private var menuGridIdCache = 0
    @Volatile private var menuItemTextIdCache = 0

    /** 上一次「部分底栏没同步」的日志指纹（去重用，见 [sync]） */
    @Volatile private var lastPartialLogged = 0

    // ------------------------------------------------------------------ 安装

    override fun install(env: FeatureEnv, bag: HookBag) {
        // 把「onResume + 布局回调 + 首帧屏障」那套调度挂上（见 PageSyncFeature）
        super.install(env, bag)
        hookPopupWindow(env, bag)
        hookWindowLayout(env, bag)
    }

    /**
     * 挂 `PopupWindow` 的显示方法：认领「我们自己触发的」那一次弹层。
     *
     * 实测「更多」面板是 `PopupWindow`（`dumpsys window` 里是 `PopupWindow:xxxx`），
     * 不是 `Dialog`，所以只需要挂这两个入口。
     */
    private fun hookPopupWindow(env: FeatureEnv, bag: HookBag) {
        val cls = Reflect.loadClass("android.widget.PopupWindow", env.classLoader)
        if (cls == null) {
            GLog.e("底栏入口：找不到 android.widget.PopupWindow，快捷入口点了不会有反应")
            return
        }
        var hooked = 0
        for (name in POPUP_SHOW_METHODS) {
            for (m in Reflect.methodsNamed(cls, name)) {
                bag.add(
                    env.xposed.hook(m)
                        .setId("stockbar:$name")
                        .intercept { chain ->
                            val result = chain.proceed()
                            runCatching { onPopupShown(chain.thisObject) }
                                .onFailure { GLog.w("底栏入口：处理弹层显示异常", it) }
                            result
                        }
                )
                hooked++
            }
        }
        if (hooked == 0) GLog.e("底栏入口：没挂上 PopupWindow 的显示方法") else
            GLog.i("底栏入口：已挂 $hooked 个 PopupWindow 显示方法")
    }

    /**
     * 挂 `WindowManagerGlobal.updateViewLayout`：**在我们主动开面板期间**把宿主对
     * Activity 窗口的调暗改回去。
     *
     * ## 为什么非挂不可（「闪一下黑」的真正根因）
     *
     * 宿主弹「更多」面板时会把 Activity 窗口调暗（实测 `alpha` 1.0 → `0.6`），
     * 而**调暗发生在 `popup.show()` 返回之后**（`onPopupShown` 里读到的还是 1.0，
     * 所以那里的一次性还原是空转）。等我们下一帧把面板点掉、`dismiss()` 时，
     * 宿主自己又把 `alpha` 还原成 1.0 —— 于是我们的「关闭时」还原同样读到 1.0，
     * 两条日志都不会打。**结果就是那 2~3 帧的调暗被实打实画了出来**：
     * 实测 50fps 录屏里连续 7 帧整屏亮度掉 26%（46.8 → 34.7），约 56ms，
     * 肉眼就是「点下去先黑一下」。
     *
     * 光靠「事后还原」治不了 —— 必须**不让它落地**。这里是宿主改窗口参数的唯一漏斗
     * （`Window.setAttributes` 与 `WindowManager.updateViewLayout` 最终都走它），
     * 我们那一轮还在时把 `alpha` / `dimAmount` 夹回快照值即可，一帧都不会被画出来。
     *
     * ⚠️ 只在 [round] 非空（我们自己的那 ~50ms）里动手，用户手点「更多」时
     * 一个字节都不碰 —— 原版的调暗行为保持不变。快照挂在轮次上，
     * 一轮结束就随之失效：不会出现「标记清了、夹子还架着」把后面的调暗全夹掉的残留。
     */
    private fun hookWindowLayout(env: FeatureEnv, bag: HookBag) {
        val cls = Reflect.loadClass("android.view.WindowManagerGlobal", env.classLoader)
        if (cls == null) {
            GLog.w("底栏入口：找不到 WindowManagerGlobal，防闪只能降级成「关掉时还原」")
            return
        }
        var hooked = 0
        for (m in Reflect.methodsNamed(cls, "updateViewLayout")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("stockbar:updateViewLayout")
                    .intercept { chain ->
                        runCatching { clampWindowDim(chain.args) }
                            .onFailure { GLog.w("底栏入口：压住宿主调暗失败", it) }
                        chain.proceed()
                    }
            )
            hooked++
        }
        if (hooked == 0) GLog.w("底栏入口：没挂上 updateViewLayout，防闪只能降级") else
            GLog.i("底栏入口：已挂 $hooked 个 updateViewLayout（防闪）")
    }

    /**
     * 把「宿主刚要写进窗口的调暗值」夹回快照值。
     *
     * 直接改 `LayoutParams` 对象本身（它是活的、被宿主与窗口共用），
     * **不动 `flags`** —— 动 flags 可能破坏宿主自己后续弹面板时的变暗行为。
     */
    private fun clampWindowDim(args: List<Any?>) {
        // 只在「我们自己的那一轮」里动手：轮次一结束 [round] 就空了，夹子跟着失效
        val s = round.get()?.window ?: return
        if (args.getOrNull(0) as? View !== s.decor) return
        val lp = args.getOrNull(1) as? WindowManager.LayoutParams ?: return
        if (lp.alpha == s.alpha && lp.dimAmount == s.dim) return
        val fromAlpha = lp.alpha
        val fromDim = lp.dimAmount
        lp.alpha = s.alpha
        lp.dimAmount = s.dim
        if (guardLogged.compareAndSet(false, true)) {
            GLog.i(
                "底栏入口：拦住宿主调暗窗口" +
                    "（alpha $fromAlpha -> ${s.alpha}，dim $fromDim -> ${s.dim}）"
            )
        }
    }

    // ------------------------------------------------------------------ 同步

    /**
     * 每次「页面进来 / 发生布局」都会走到这里，把底栏状态对齐一次。
     *
     * 返回 `true` = 这一趟没什么好做的（含「底栏还没挂上来，等下一次布局」）；
     * 返回 `false` = 底栏在、却一个都没同步成功 —— 这才是真异常，交给基类累计次数并打快照。
     */
    override fun sync(env: FeatureEnv, activity: Activity): Boolean {
        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        seenDecors[root] = true   // 留个扫描起点给热重载兜底清理（弱引用，页面销毁自动失效）
        val barId = resolveId(root.context, IdKey.BOTTOM_BAR)
        if (barId == 0) return true                       // App 里没这个资源，再同步也没用
        val bars = findBars(root, barId)
        if (bars.isEmpty()) return true                   // 引擎片段还没挂上：等布局回调
        var ok = 0
        for (bar in bars) {
            val done = runCatching { syncBar(bar) }.getOrElse {
                GLog.w("底栏入口：同步底栏失败", it)
                false
            }
            if (done) ok++
        }
        if (ok == 0) return false
        if (ok < bars.size) {
            // 部分没成是常态（还没铺开的那一页的底栏里什么都没有），别每次布局都打一遍
            val partial = bars.size * 10 + ok
            if (lastPartialLogged != partial) {
                lastPartialLogged = partial
                GLog.d("底栏入口：${bars.size} 份底栏里有 ${bars.size - ok} 份本次未同步（多半是还没铺开的那一页）")
            }
        }
        return true
    }

    /**
     * 页面里**所有**底栏。
     *
     * 实测 `StockFragAct` 的分页器**每一页各带一份底栏**（同一个资源 id，三个不同实例，
     * 各自还有自己的 `bottom_index_container`）。翻页换的就是另一份 —— 所以
     * `root.findViewById(barId)` 那种「只取第一个」的写法**永远只在给第一页注入**，
     * 用户一翻页就「智能盯盘没了」（这正是 2.15.0 报上来的现象）。必须全部同步。
     */
    private fun findBars(root: View, id: Int): List<View> {
        val out = ArrayList<View>(4)
        fun walk(v: View) {
            if (v.id == id) {
                out.add(v)              // 一页只有一份底栏，命中就不必再往下
                return
            }
            if (v !is ViewGroup) return
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    /**
     * 底栏在手：确认「我们的按钮还在不在」，不在就（重新）注入。
     *
     * 判据用 [liveRecord]（**现场**）而不是「记账表里有没有这一条」—— 宿主重建底栏
     * 之后记账表还在，只查它就会永远误判成「已经注入过了」，再也修不回来。
     */
    private fun syncBar(bar: View): Boolean {
        val more = findMoreText(bar) ?: return false
        val box = boxOf(bar, more) ?: return false
        // 盒子可能是别的页面残留的同一个 id，确认它属于这条底栏
        if (!isDescendant(box, bar)) return false
        val live = liveRecord(box, more)
        if (live != null) {
            reassert(live)
            return true
        }
        // 硬红线：**绝不把我们的容器套进另一个我们的容器里**。
        // 正常路径不会走到这里（盒子 + 现场都判过了）；走到只能说明「更多」外面套的
        // 是**旧实例 / 丢记账后留下的孤儿容器**（审查 2026-10-17 中-9：热重载残留）——
        // 按 tag 兜底还原掉它，本次先不注入，等下一次布局回调重新对齐。
        // 绝不能再套一层 —— 实测套 5 层就把整条底栏撑爆了。
        val orphanHolder = holderAncestorOf(more)
        if (orphanHolder != null) {
            val ok = runCatching { detachOrphan(orphanHolder) }
                .onSuccess { GLog.w("底栏入口：发现并清理了一处无记账的注入容器（热重载残留），等下次布局重新注入") }
                .onFailure { GLog.w("底栏入口：清理无记账的注入容器失败", it) }
                .isSuccess
            if (ok) noteChange()
            return true
        }
        val entry = buildEntry(more) ?: return false
        val built = buildInline(more, box, entry) ?: return false
        injected[box] = built
        noteChange()
        GLog.i(
            "底栏入口：已在「自选」与「更多」之间注入「${StockDetailBarTargets.ENTRY_TEXT}」" +
                "（盒子 ${box.javaClass.simpleName}，原宽 ${built.originalBoxWidth}px）"
        )
        // 图标早采到过（进程级缓存）就**当场套上**：不套的话这颗按钮要等下几趟同步被
        // [reassert] 补图标，用户看到的是「先注入文字，图标过一会儿才出现」（2026-10-19 修 BUG1）。
        // 一次都还没采到才交给下面那条异步采集（它要隐形开一次「更多」面板，快不了）。
        if (entryIcon != null) applyIcon(built)
        maybeHarvestIcon(more)
        return true
    }

    /**
     * 「更多」真正该待的那个盒子（`#fl_more` / `#fl_bottom_index_more`）。
     *
     * ⚠️ **两个「必须在 [bar] 范围内找」的原因**（都踩过）：
     *
     *  1. 分页器**每页各带一份底栏**（同一个资源 id），全树 `findViewById` 永远只返回第一份 ——
     *     拿它去同步第二页的底栏，会得到「这个盒子不是这条底栏的孩子」→ 永远同步失败；
     *  2. 我们注入之后「更多」的直接父级是**我们自己的容器**，取 `more.parent` 会取到壳，
     *     记账表里查不到 → 一层层往里再套（实测套了 5 层，底栏被撑爆）。
     *
     * 所以：先在 **[bar] 范围内**按资源 id 找盒子（并用 `isDescendant` 确认里面确实是这个「更多」）；
     * 认不到 id 就沿父链往上找，**跳过我们自己套的壳**（按 [StockDetailBarTargets.ENTRY_HOLDER_TAG] 认）。
     */
    private fun boxOf(bar: View, more: View): ViewGroup? {
        for (name in StockDetailBarTargets.MORE_BOX_ID_CANDIDATES) {
            val id = resolveIdByName(bar.context, name)
            if (id == 0) continue
            val v = bar.findViewById<View>(id) as? ViewGroup ?: continue
            if (isDescendant(more, v)) return v
        }
        var cur: View = more
        var guard = 0
        while (guard++ < 12) {
            val p = cur.parent as? ViewGroup ?: return null
            if (p.tag != StockDetailBarTargets.ENTRY_HOLDER_TAG) return p
            cur = p
        }
        return null
    }

    /** 从 [v] 上溯到它所在的那条底栏（按底栏的资源 id 认） */
    private fun barAncestorOf(v: View): View? {
        val id = resolveId(v.context, IdKey.BOTTOM_BAR)
        if (id == 0) return null
        var cur: View? = v
        var guard = 0
        while (cur != null && guard++ < 20) {
            if (cur.id == id) return cur
            cur = cur.parent as? View
        }
        return null
    }

    /** 「更多」的祖先里有没有我们注入的容器（沿父链按 tag 找） */
    private fun holderAncestorOf(v: View): ViewGroup? {
        var cur: View = v
        var guard = 0
        while (guard++ < 12) {
            val p = cur.parent as? ViewGroup ?: return null
            if (p.tag == StockDetailBarTargets.ENTRY_HOLDER_TAG) return p
            cur = p
        }
        return null
    }

    /**
     * 现场是否还成立 —— **「我们的按钮现在还在盒子里」**，而不是「我注入过」。
     *
     * 三个条件缺一不可：宿主还在用同一个「更多」、我们的容器还挂在盒子里、
     * 我们的按钮还挂在容器里而且在窗口上。任何一条不成立就说明宿主重建过这一块，
     * 于是把过期的记账丢掉、顺手把自己留下的容器摘掉，让调用方重新注入。
     */
    private fun liveRecord(box: ViewGroup, more: View): Injected? {
        val e = synchronized(injected) { injected[box] } ?: return null
        val alive = e.moreText === more &&
            e.holder.parent === box &&
            e.button.parent === e.holder &&
            e.button.isAttachedToWindow
        if (alive) return e

        synchronized(injected) { injected.remove(box) }
        cleanupStale(e)
        GLog.i("底栏入口：宿主重建了底栏（入口已不在树上），重新注入")
        return null
    }

    /** 丢掉一次失效注入留下的容器（**不**碰「更多」：它要么已被宿主换掉，要么由重新注入接管） */
    private fun cleanupStale(e: Injected) {
        runCatching { e.holder.removeView(e.button) }
        runCatching { (e.holder.parent as? ViewGroup)?.removeView(e.holder) }
    }

    /**
     * 现场还成立时，顺手把宿主改回去的东西再压一次，并把这一份现场标记成「已复核」。
     *
     * 实测宿主重排底栏时会重新套用它自己的 `LayoutParams`：盒子宽度一旦回到原来的
     * 固定值，我们的按钮就被挤没了（看着就是「入口消失了」）—— 加宽是「买卖让位」的
     * 唯一来源，必须重新压回去。图标同理（样式被重置时要补回来）。
     */
    private fun reassert(e: Injected) {
        val lp = e.box.layoutParams
        if (lp != null && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            runCatching { e.box.layoutParams = lp }
            runCatching { e.box.requestLayout() }
            noteChange()
            GLog.i("底栏入口：宿主把「更多」盒子宽度改回去了，已重新加宽（买卖重新让位）")
        }
        if (topDrawableOf(e) == null) applyIcon(e)
        // 这一份现场到此算复核过：限速闸恢复正常（见 needsFollowUp）
        e.confirmed = true
    }

    /** 我们套在入口按钮上的那颗图标；没套（或套失败）返回 null */
    private fun topDrawableOf(e: Injected): Drawable? =
        runCatching { e.button.compoundDrawables.getOrNull(1) }.getOrNull()

    /**
     * 真正动手：把「更多」搬进一个横向容器，前面并排我们的按钮，再把容器塞回盒子，
     * 最后把盒子宽度改成 `wrap_content`（这一步才是「买卖让位」的来源）。
     */
    private fun buildInline(moreText: TextView, box: ViewGroup, entry: TextView): Injected? {
        val originalWidth = box.width.takeIf { it > 0 }
            ?: box.measuredWidth.takeIf { it > 0 }
            ?: box.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val gapPx = StockDetailBarMeasure.gapPx(moreText)
        val moreWidth = moreText.width.takeIf { it > 0 }
            ?: moreText.measuredWidth.takeIf { it > 0 } ?: 0
        val index = box.indexOfChild(moreText)
        val (iconW, iconH) = StockDetailBarMeasure.refIconSize(moreText)

        // ① 把「更多」从盒子里摘出来
        box.removeView(moreText)

        // ② 横向容器：[我们的按钮] [更多]
        val holder = LinearLayout(moreText.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // tag 是「找盒子」的关键：注入后「更多」的父级就是我们，见 boxOf
            tag = StockDetailBarTargets.ENTRY_HOLDER_TAG
            // 盒子原宽再记一份在我们自己身上：热重载兜底清理（detachOrphan）没有
            // 旧实例的记账表可查，只能从视图本身找回原宽（审查 2026-10-17 中-9）
            setTag(KEY_ORIG_BOX_WIDTH, originalWidth)
        }
        entry.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { rightMargin = gapPx }
        moreText.layoutParams = LinearLayout.LayoutParams(
            if (moreWidth > 0) moreWidth else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER }
        holder.addView(entry)
        holder.addView(moreText)

        // ③ 容器塞回盒子
        val holderLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )
        runCatching {
            if (index in 0..box.childCount) box.addView(holder, index, holderLp)
            else box.addView(holder, holderLp)
        }.onFailure {
            GLog.w("底栏入口：容器塞回盒子失败，回滚", it)
            runCatching { holder.removeView(entry); holder.removeView(moreText) }
            runCatching { box.addView(moreText, index.coerceIn(0, box.childCount)) }
            return null
        }

        // ④ 盒子宽度 → wrap_content：链上固定成员变宽，买卖自动让位
        val lp = box.layoutParams
        if (lp != null && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            runCatching { box.layoutParams = lp }
        }
        runCatching { box.requestLayout() }

        return Injected(box, holder, moreText, entry, originalWidth, index, iconW, iconH)
    }

    /** 从「更多」按钮克隆出一颗样式一致、但**独立 id / 独立 tag** 的按钮 */
    private fun buildEntry(moreText: TextView): TextView? {
        val button = TextView(moreText.context)
        StockDetailBarMeasure.copyStyle(moreText, button)
        button.text = StockDetailBarTargets.ENTRY_TEXT
        button.tag = StockDetailBarTargets.ENTRY_TAG
        runCatching { button.id = View.generateViewId() }
        // 图标由采集流程补上；先清空，避免短暂顶着「更多」的图标（•••）
        runCatching { button.setCompoundDrawables(null, null, null, null) }
        runCatching { button.gravity = Gravity.CENTER }
        button.isClickable = true
        button.isFocusable = true
        button.setOnClickListener { v ->
            runCatching { openEntry(v) }.onFailure { GLog.w("底栏入口：打开智能盯盘失败", it) }
        }
        return button
    }

    /** 关开关 / 撤销注入：把「更多」搬回盒子、盒子宽度改回原值 */
    override fun restore() {
        val entries = synchronized(injected) {
            val list = injected.values.toList()
            injected.clear()
            list
        }
        for (e in entries) runCatching { detach(e) }
        // 审查 2026-10-17 中-9：遍历自己的记账表之后再加一道兜底 —— 热重载后跑到这里
        // 的实例可能账上没几笔（甚至一笔都没有），旧实例留下的按钮和加宽的盒子却还在
        // 树上。不依赖记账，直接按 [StockDetailBarTargets.ENTRY_HOLDER_TAG] 在本实例
        // 见过的页面上扫，认出来就按 [detach] 同样的方式还原。正常路径表已清空、
        // 扫不到东西，多花的只是一次浅层遍历。
        var swept = 0
        val roots = synchronized(seenDecors) { seenDecors.keys.toList() }
        for (r in roots) swept += runCatching { sweepOrphans(r) }.getOrDefault(0)
        if (entries.isNotEmpty() || swept > 0) noteChange()
    }

    /**
     * 页面销毁：释放这一页的注入记账（不还原现场）。
     *
     * `injected` 的值 `Injected` 持有 box / holder / moreText / button，全是这一页的视图，
     * 而 View 的 Context 就是 Activity —— 值→key 的强引用链让 WeakHashMap 的弱键失效。
     * 页面销毁时按 box 的身份把条目删掉，Activity 才能被 GC。
     */
    override fun onActivityDestroyed(activity: Activity) {
        super.onActivityDestroyed(activity)
        val r = round.get()
        if (synchronized(injected) { injected.isEmpty() } && r?.window == null) return
        val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
        val doomed = synchronized(injected) {
            val list = injected.entries.filter { (box, _) -> belongsToPage(box, activity, decor) }
            list.forEach { injected.remove(it.key) }
            list.map { it.value }
        }
        // 我们主动开面板的那一轮可能还握着 WindowSnapshot(decor)。页面没了就丢掉快照；
        // 不清 round 本身 —— 让 attemptHandle / 超时按身份自己收尾（它会先比 round）。
        if (r != null && r.window?.decor === decor) {
            r.window = null
            round.compareAndSet(r, null)
            guardLogged.set(false)
        }
        if (doomed.isNotEmpty()) {
            GLog.i("$id：页面销毁，已释放 ${doomed.size} 份底栏注入记账（未做还原）")
        }
    }

    /** 这个视图是不是属于 [activity] 这一页（Context 或根视图身份，避免误伤别的页面） */
    private fun belongsToPage(v: View, activity: Activity, decor: View): Boolean =
        runCatching { v.context === activity || v.rootView === decor }.getOrDefault(false)

    /** 在 [root] 子树里按 tag 找**任意实例**注入的容器并逐个还原，返回还原了几处 */
    private fun sweepOrphans(root: View?): Int {
        if (root == null) return 0
        val holders = ArrayList<ViewGroup>(4)
        collectHolders(root, holders)
        var n = 0
        for (h in holders) {
            val ok = runCatching { detachOrphan(h) }
                .onFailure { GLog.w("底栏入口：兜底清理注入残留失败", it) }
                .isSuccess
            if (ok) {
                n++
                GLog.i("底栏入口：兜底清理了一处注入残留（热重载 / 记账丢失）")
            }
        }
        return n
    }

    /** 深度优先收集带 [StockDetailBarTargets.ENTRY_HOLDER_TAG] 的容器（容器不会嵌套，命中即止） */
    private fun collectHolders(v: View, out: MutableList<ViewGroup>) {
        if (v is ViewGroup && v.tag == StockDetailBarTargets.ENTRY_HOLDER_TAG) {
            out.add(v)
            return
        }
        if (v !is ViewGroup) return
        for (i in 0 until v.childCount) collectHolders(v.getChildAt(i), out)
    }

    /**
     * 还原一个「没有记账」的注入容器 —— 与 [detach] 同构，但现场数据全部从视图本身取：
     *
     *  - 「更多」= 容器里不带 [StockDetailBarTargets.ENTRY_TAG] 的那个 `TextView`
     *    （注入结构固定为 [我们的按钮, 更多]）；
     *  - 放回位置 = 容器在盒子里的下标（注入时就是用它顶替「更多」的）；
     *  - 盒子原宽 = 注入时记在容器 keyed tag 上的那份（[KEY_ORIG_BOX_WIDTH]）。
     *
     * 旧版本注入的容器没有 keyed tag，那种情况下盒宽保持现状（`wrap_content`，
     * 视觉上仍是对的）；彻底复原交给宿主重建底栏或重启。
     */
    private fun detachOrphan(holder: ViewGroup) {
        val box = holder.parent as? ViewGroup ?: return
        val index = box.indexOfChild(holder)
        var more: TextView? = null
        for (i in 0 until holder.childCount) {
            val c = holder.getChildAt(i) as? TextView ?: continue
            if (c.tag != StockDetailBarTargets.ENTRY_TAG) more = c
        }
        runCatching { box.removeView(holder) }
        more?.let { m ->
            runCatching {
                if (index in 0..box.childCount) box.addView(m, index) else box.addView(m)
            }
        }
        val orig = holder.getTag(KEY_ORIG_BOX_WIDTH) as? Int
        val lp = box.layoutParams
        if (lp != null && orig != null && orig != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = orig
            runCatching { box.layoutParams = lp }
        }
        runCatching { box.requestLayout() }
    }

    /** 还有注入在现场时才值得在关开关时打日志（`detach` 自己有逐项日志） */
    override val hasChanges: Boolean get() = synchronized(injected) { injected.isNotEmpty() }

    private fun detach(e: Injected) {
        val holder = e.holder
        runCatching { holder.removeView(e.button) }
        runCatching { holder.removeView(e.moreText) }
        runCatching { (holder.parent as? ViewGroup)?.removeView(holder) }
        val index = e.originalMoreIndex
        runCatching {
            if (index < 0) e.box.addView(e.moreText) else e.box.addView(e.moreText, index)
        }
        val lp = e.box.layoutParams
        if (lp != null && e.originalBoxWidth != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = e.originalBoxWidth
            runCatching { e.box.layoutParams = lp }
        }
        runCatching { e.box.requestLayout() }
    }

    // ------------------------------------------------------------------ 打开

    /**
     * 用户点了我们注入的按钮：触发「更多」→ 由弹层钩子接力点「智能盯盘」。
     *
     * **一次点击 = 一轮**（[Round]），而且必须保证「一次点击最多只开一个面板」——
     * 每次 `performClick()` 都会让宿主弹一层 `PopupWindow`，开两层就一定有一层没人认领、
     * 明晃晃地留在屏幕上（用户看到的就是「点『智能盯盘』却弹出『更多』」）。
     * 所以进来先看有没有在途的轮次：
     *
     *  - 在途的是**还没弹面板的采集轮** → 直接掐掉（它本来就要隐形开一次面板，让位给用户）；
     *  - 在途的是**另一次点击** → 合并掉这一次，什么都不做（那一次已经会带用户过去）；
     *  - 在途的采集轮**已经认领**（正在处理）→ 放它走完，本轮照常开始，
     *    收尾时各自按身份核对自己的轮次，互不干扰。
     */
    private fun openEntry(v: View) {
        // 必须找**同一个底栏里**的「更多」：分页器每页各有一份底栏，找错页就点错
        val bar = barAncestorOf(v) ?: v.rootView ?: return
        val moreText = findMoreText(bar) ?: run {
            GLog.w("底栏入口：点击时找不到「更多」按钮")
            return
        }
        val active = round.get()
        if (active != null) {
            if (!active.harvest) {
                // 上一次点击还在等面板 / 正在处理：合并。再开一层面板没人认领
                GLog.d("底栏入口：上一次点击还在进行（第 ${active.gen} 轮），本次点击合并")
                return
            }
            // 采集轮让位给用户点击：还没弹面板的就此掐掉；已经弹了的由它自己那段
            // 收尾逻辑（[attemptHandle] 的「轮次被顶掉」分支）把弹层关掉，用户点击优先
            round.compareAndSet(active, null)
            // 采集那层若已经把窗口调暗了，先还原 —— 否则下面抓到的快照就是「暗着」的，
            // 整条点击链路都会把那一层暗当成基准留住
            restoreWindow(active.window, "让位时")
            GLog.d("底栏入口：图标采集（第 ${active.gen} 轮）让位给用户点击")
        }

        val r = begin(harvest = false) ?: run {
            GLog.d("底栏入口：已有一轮在途，本次点击合并")
            return
        }
        guardLogged.set(false)
        // ★ 必须在 performClick 之前抓窗口快照 —— 宿主是在点击处理里把窗口调暗的
        r.window = captureWindow(v)
        val shown = runCatching { moreText.performClick() }.getOrDefault(false)
        if (!shown) {
            end(r)
            GLog.w("底栏入口：「更多」没有响应点击，本次点击未生效")
            return
        }
        scheduleTimeout(r, moreText)
    }

    /** 首次注入时隐形开一次面板，把「智能盯盘」那一项的图标取回来 */
    private fun maybeHarvestIcon(moreText: TextView) {
        if (entryIcon != null) return
        if (!harvestStarted.compareAndSet(false, true)) return
        val r = begin(harvest = true)
        if (r == null) {
            // 有用户点击在途：**绝不插一脚**（两轮同时开面板必有一层认领不到）。
            // 采集不着急，等用户这一轮走完再来
            harvestStarted.set(false)
            moreText.postDelayed({
                if (moreText.isAttachedToWindow) maybeHarvestIcon(moreText)
            }, HARVEST_RETRY_MS)
            return
        }
        guardLogged.set(false)
        moreText.post {
            // 这一轮可能已经被用户点击掐掉了 —— 掐掉了就绝不能再开面板
            // （旧实现没这道门：采集晚到一步就用 `performClick` 又开出一层没有人认领的
            //  面板，用户看到的就是「点了入口却弹出『更多』」）
            if (round.get() !== r) {
                harvestStarted.set(false)   // 允许下次注入时重试采集
                return@post
            }
            r.window = captureWindow(moreText)
            val shown = runCatching { moreText.performClick() }.getOrDefault(false)
            if (!shown) {
                end(r)
                harvestStarted.set(false)      // 允许下次重试
                GLog.w("底栏入口：采集图标时「更多」没有响应点击，按钮退化为纯文字")
                return@post
            }
            scheduleTimeout(r, moreText)
        }
    }

    /** 开一轮「我们主动弹面板」：抢到空闲位才算开始（点击与采集互斥） */
    private fun begin(harvest: Boolean): Round? {
        val r = Round(gen.incrementAndGet(), harvest)
        return if (round.compareAndSet(null, r)) r else null
    }

    /**
     * 收尾：只清**自己那一轮**。
     *
     * 按身份核对（而不是按代次比较、也不是无条件清零）—— 上一轮的超时任务 / 重试链
     * 可能比下一轮晚到，无条件清零会把**下一轮**的标记抹掉，那正是「偶发弹出更多」的老病根。
     */
    private fun end(r: Round) {
        round.compareAndSet(r, null)
        guardLogged.set(false)
    }

    /**
     * 超时兜底：面板一直没出现就把这一轮作废。
     *
     * 只作废「还没被认领」的轮次；已经认领的正在 [attemptHandle] 里走，不归这里管。
     */
    private fun scheduleTimeout(r: Round, anchor: View) {
        anchor.postDelayed({
            if (round.get() !== r || r.claimed.get()) return@postDelayed
            end(r)
            if (r.harvest) harvestStarted.set(false)   // 允许下次重试
            GLog.w(
                "底栏入口：${if (r.harvest) "图标采集" else "点击"}没等到「更多」面板" +
                    "（第 ${r.gen} 轮，$PENDING_TIMEOUT_MS ms），本轮作废"
            )
        }, PENDING_TIMEOUT_MS)
    }

    // -------------------------------------------------------------- 窗口防闪

    /** 记下宿主窗口当前的 alpha / 调暗值（`performClick` 之前调） */
    private fun captureWindow(anchor: View): WindowSnapshot? {
        val decor = runCatching { anchor.rootView }.getOrNull() ?: return null
        val lp = runCatching { decor.layoutParams as? WindowManager.LayoutParams }.getOrNull()
        val wm = runCatching {
            decor.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull()
        return WindowSnapshot(
            decor = decor,
            wm = wm,
            lp = lp,
            alpha = lp?.alpha ?: 1f,
            dim = lp?.dimAmount ?: 0f,
            viewAlpha = decor.alpha,
        )
    }

    /**
     * 把宿主窗口还原成「弹面板之前」的样子。
     *
     * 只还原 `alpha` 与 `dimAmount`，**不动 `flags`** —— 动 flags 可能破坏宿主自己
     * 后续弹面板时的变暗行为，而 `dimAmount = 0` 已经足够让那一层变暗不可见。
     */
    private fun restoreWindow(s: WindowSnapshot?, tag: String) {
        if (s == null) return
        val lp = s.lp
        if (lp != null) {
            val nowAlpha = lp.alpha
            val nowDim = lp.dimAmount
            val dirty = nowAlpha != s.alpha || nowDim != s.dim
            runCatching {
                lp.alpha = s.alpha
                lp.dimAmount = s.dim
                s.wm?.updateViewLayout(s.decor, lp)
            }.onFailure { GLog.w("底栏入口：还原宿主窗口失败", it) }
            if (dirty) {
                GLog.i("底栏入口：$tag 宿主把窗口调暗了（alpha $nowAlpha dim $nowDim）-> 已还原")
            }
        }
        runCatching { s.decor.alpha = s.viewAlpha }
    }

    /**
     * 面板显示时被调用（`PopupWindow` 的每个显示重载都会回调，嵌套重载会回调好几次）。
     *
     * 顺序**不能颠倒**：
     *
     *  1. 没有在途的轮次 → 一个字节都不动（用户手点「更多」不受影响）；
     *  2. 这一轮已经认领过 → 直接走开（嵌套 `showAsDropDown` 的重复回调落在这里）；
     *  3. **先验指纹**：`contentView` 为空、或里面找不到 `#gv_stk_bottom` → **不动、也不清标记**。
     *     旧实现在这一步之前就把标记清了，于是「面板还没 inflate 完」「这次弹的不是更多面板」
     *     都会把认领机会白白吃掉；真正该认领的更多面板随后显示时已经没人接手
     *     → 面板明晃晃留在屏幕上，就是「点『智能盯盘』却弹出『更多』」；
     *  4. 验过才认领（`claimed` 只允许成功一次），藏弹层、还原窗口、下一帧处理内容。
     */
    private fun onPopupShown(self: Any?) {
        val popup = self as? PopupWindow ?: return
        val r = round.get() ?: return
        if (r.claimed.get()) return

        val content = popup.contentView ?: run {
            GLog.d("底栏入口：面板还没有 contentView，本次不认领（标记留到真正的弹层）")
            return
        }
        // GridView 是「更多面板」的指纹；findViewById 在布局前就能命中，所以能同步判断
        if (findMenuGrid(content) == null) {
            GLog.d(
                "底栏入口：这次弹层里没有 ${StockDetailBarTargets.MENU_GRID_ID_CANDIDATES.joinToString()}，" +
                    "不是更多面板 —— 不认领，第 ${r.gen} 轮继续等"
            )
            return
        }
        // 认领：同一轮只允许成功一次（嵌套重载会重复回调到这儿）
        if (!r.claimed.compareAndSet(false, true)) return

        // 面板已 addView 完成、但这一帧还没绘制 —— 现在处理，用户一帧都看不到
        //
        // ① 藏**整个弹层窗口**，不是只藏 contentView：PopupWindow 有背景时会把内容
        //    包进一层 PopupBackgroundView，只设 contentView 的 alpha 那层背景照样画出来
        //    （用户看到的就是「闪一下黑」）。
        val layer = runCatching { content.rootView }.getOrNull() ?: content
        runCatching { layer.alpha = 0f }
        // ② 宿主弹面板时会把 Activity 窗口调暗（实测 alpha 1.0 → 0.6），同步还原。
        //    注意这一次多半是空转（宿主是在 show() 返回之后才调的），真正兜住的是
        //    [hookWindowLayout] 那道夹子 + 下面 [attemptHandle] 里的「关闭时」还原。
        restoreWindow(r.window, "打开时")

        content.post { attemptHandle(content, layer, popup, r, 0) }
    }

    /**
     * 处理面板内容。
     *
     * **必须重试**：`PopupWindow.show()` 只是把窗口挂上去，`GridView` 的子项要等
     * 下一帧 measure/layout 之后才存在，所以 `post` 一次未必够。
     * 反正弹层是透明的，重试期间用户什么都看不到。
     */
    private fun attemptHandle(
        content: View,
        layer: View,
        popup: PopupWindow,
        r: Round,
        attempt: Int,
    ) {
        // 这一轮在我们重试期间被顶掉了（用户点了入口，采集轮让位）→ 只收拾自己那层弹层，
        // 别动窗口参数：此刻窗口归新的一轮管
        if (round.get() !== r) {
            restoreWindow(r.window, "让位时")
            runCatching { layer.alpha = 1f }
            runCatching { popup.dismiss() }
            return
        }

        val grid = findMenuGrid(content)
        val item = grid?.let { findMenuEntryText(it) }

        if (item == null && attempt < MAX_ATTEMPTS) {
            content.postDelayed(
                { runCatching { attemptHandle(content, layer, popup, r, attempt + 1) } },
                RETRY_MS,
            )
            return
        }

        runCatching {
            if (item != null) {
                val icon = harvestIcon(item)
                GLog.i("底栏入口：图标采集 -> ${if (icon != null) "成功" else "该面板项没有 drawableTop"}")
                if (icon != null) {
                    entryIcon = icon
                    applyIconToAll()
                }
                // 采集轮到此为止；只有点击轮才继续往「智能盯盘」里走
                if (!r.harvest && grid != null) clickMenuEntry(grid, item)
            }
        }.onFailure { GLog.w("底栏入口：处理面板内容异常", it) }

        // 宿主可能在 dismiss 里再调一次窗口，所以这里再兜一次底
        restoreWindow(r.window, "关闭时")
        r.window = null
        runCatching { layer.alpha = 1f }   // 弹层实例可能被宿主复用，务必还原
        runCatching { popup.dismiss() }

        // 收尾：这一轮结束，防闪夹子随之失效（见 [clampWindowDim]）
        end(r)

        if (item == null) {
            harvestStarted.set(false)        // 允许下次重试
            GLog.w("底栏入口：面板里没找到「${StockDetailBarTargets.MENU_ITEM_TEXT}」（第 ${r.gen} 轮）")
        }
    }

    /**
     * 走宿主的 item 点击路径。
     *
     * 宿主是 `gridView.setOnItemClickListener(...)`（探针栈 `StockDetailFrag$34#onItemClick`），
     * 所以**必须**用 [GridView.performItemClick] —— 面板项容器本身不保证 `clickable`，
     * 往上找可点击祖先会直接落空。
     */
    private fun clickMenuEntry(grid: GridView, itemText: TextView) {
        val box = directChildOf(grid, itemText) ?: run {
            GLog.w("底栏入口：「${StockDetailBarTargets.MENU_ITEM_TEXT}」不在 GridView 的直接子项里")
            return
        }
        val offset = (0 until grid.childCount).firstOrNull { grid.getChildAt(it) === box } ?: return
        val position = grid.firstVisiblePosition + offset
        val id = runCatching { grid.adapter?.getItemId(position) ?: position.toLong() }
            .getOrDefault(position.toLong())

        val hasItemListener = runCatching {
            val f = Class.forName("android.widget.AdapterView").getDeclaredField("mOnItemClickListener")
            f.isAccessible = true
            f.get(grid) != null
        }.getOrDefault(false)

        when {
            hasItemListener -> grid.performItemClick(box, position, id)
            box.isClickable -> box.performClick()
            else -> {
                GLog.w("底栏入口：「${StockDetailBarTargets.MENU_ITEM_TEXT}」既没有 item 监听也不可点击")
                return
            }
        }
        GLog.i("底栏入口：已触发「${StockDetailBarTargets.MENU_ITEM_TEXT}」（position=$position）")
    }

    /** 沿 parent 上溯到 `GridView` 的直接子项（也就是 item 容器） */
    private fun directChildOf(grid: GridView, v: View): View? {
        var cur: View? = v
        while (cur != null) {
            val p = cur.parent
            if (p === grid) return cur
            cur = p as? View
        }
        return null
    }

    // ------------------------------------------------------------------ 图标

    /** 采集「智能盯盘」项自带的 `drawableTop` */
    private fun harvestIcon(item: TextView): Drawable? {
        val d = runCatching { item.compoundDrawables.getOrNull(1) }.getOrNull() ?: return null
        return runCatching { d.constantState?.newDrawable()?.mutate() }.getOrNull()
    }

    private fun applyIconToAll() {
        val entries = synchronized(injected) { injected.values.toList() }
        for (e in entries) applyIcon(e)
    }

    private fun applyIcon(e: Injected) {
        val icon = entryIcon ?: return
        runCatching {
            val d = icon.constantState?.newDrawable()?.mutate() ?: return@runCatching
            // 必须显式设 bounds：`setCompoundDrawables` 不会给 drawable 设 bounds，
            // 结果是「图标拿到了但一个像素都画不出来」；而且面板里的图标比底栏的大，
            // 不缩到「更多」的尺寸会把底栏文字顶掉（都踩过）。
            if (e.iconW > 0 && e.iconH > 0) d.setBounds(0, 0, e.iconW, e.iconH)
            e.button.setCompoundDrawables(null, d, null, null)
            noteChange()          // 图标是在改视图，这一帧同样要先拦下来（见 PageSyncFeature）
        }
    }

    // ------------------------------------------------------------------ 查找

    /**
     * 找**这条底栏里**的「更多」按钮。
     *
     * ⚠️ 必须**限定在 [bar] 范围内**：分页器每页各带一份底栏（同一个资源 id），
     * 全树 `findViewById` 只会返回第一份 —— 用它去同步第二页，整条链都会认错。
     *
     * 两套形态各有一个「更多」，**取当前显示的那个**（另一个是 `GONE`）。
     * 都按不到 id 时，再**只在底栏范围内**按文字兜底 —— 页面别处也有「更多」
     * （K 线周期栏就有一个），全树按文字找会抓错。
     */
    private fun findMoreText(bar: View): TextView? {
        var fallback: TextView? = null
        for (name in StockDetailBarTargets.MORE_TEXT_ID_CANDIDATES) {
            val id = resolveIdByName(bar.context, name)
            if (id == 0) continue
            val tv = bar.findViewById<View>(id) as? TextView ?: continue
            if (tv.isShown) return tv
            if (fallback == null) fallback = tv
        }
        if (fallback != null) return fallback
        return findText(bar, StockDetailBarTargets.MORE_TEXT)
    }

    private fun findMenuGrid(content: View): GridView? {
        val id = resolveId(content.context, IdKey.MENU_GRID)
        if (id != 0) (content.findViewById<View>(id) as? GridView)?.let { return it }
        return findGridRecursive(content)
    }

    private fun findGridRecursive(v: View): GridView? {
        if (v is GridView) return v
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) findGridRecursive(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun findMenuEntryText(grid: GridView): TextView? {
        val id = resolveId(grid.context, IdKey.MENU_ITEM_TEXT)
        if (id != 0) {
            for (i in 0 until grid.childCount) {
                val tv = grid.getChildAt(i).findViewById<View>(id) as? TextView ?: continue
                if (tv.text?.toString() == StockDetailBarTargets.MENU_ITEM_TEXT) return tv
            }
        }
        return findText(grid, StockDetailBarTargets.MENU_ITEM_TEXT)
    }

    /** 深度优先找一个文案相等的 `TextView` */
    private fun findText(v: View, text: String): TextView? {
        if (v is TextView && v.text?.toString() == text) return v
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) findText(v.getChildAt(i), text)?.let { return it }
        return null
    }

    /** `child` 是否在 `ancestor` 的子树里 */
    private fun isDescendant(child: View, ancestor: View): Boolean {
        var cur: View? = child
        while (cur != null) {
            if (cur === ancestor) return true
            cur = cur.parent as? View
        }
        return false
    }

    // ------------------------------------------------------------------ 资源 id

    /** 解析一次资源 id 并缓存；返回 0 表示不存在 */
    private fun resolveId(context: Context, key: IdKey): Int {
        val cached = when (key) {
            IdKey.BOTTOM_BAR -> bottomBarIdCache
            IdKey.MENU_GRID -> menuGridIdCache
            IdKey.MENU_ITEM_TEXT -> menuItemTextIdCache
        }
        if (cached != 0) return if (cached > 0) cached else 0

        var found = 0
        for (name in key.names) {
            found = resolveIdByName(context, name)
            if (found != 0) break
        }
        val store = if (found != 0) found else -1
        when (key) {
            IdKey.BOTTOM_BAR -> bottomBarIdCache = store
            IdKey.MENU_GRID -> menuGridIdCache = store
            IdKey.MENU_ITEM_TEXT -> menuItemTextIdCache = store
        }
        return found
    }

    /**
     * 按名字解析资源 id —— 走 [ViewIds.idsFor] 的进程级缓存（且这些名字在进页面时已被 [idNames] 预热）。
     *
     * ⚠️ 这里**曾经**刻意不缓存（2026-10-19 改）：理由是「两个形态各一个候选 id，
     * 一槽一位的 [IdKey] 存不下两个」。但那让**每次布局回调**都要按名字扫资源表 ——
     * `findMoreText` 2 个候选 × `boxOf` 2 个候选，分页器最多 3 份底栏，一趟十几次
     * `Resources.getIdentifier`（按名字扫全表，比一次哈希查表贵好几个数量级）。
     * `IdCache` 是**按名字分槽**的（命中还进进程级表），不存在抢槽的问题。
     */
    private fun resolveIdByName(context: Context, name: String): Int =
        runCatching {
            ViewIds.idsFor(context, StockDetailBarTargets.TARGET_PKG).of(name)
        }.getOrDefault(0)

    private enum class IdKey(val names: Array<String>) {
        BOTTOM_BAR(StockDetailBarTargets.BOTTOM_BAR_ID_CANDIDATES),
        MENU_GRID(StockDetailBarTargets.MENU_GRID_ID_CANDIDATES),
        MENU_ITEM_TEXT(StockDetailBarTargets.MENU_ITEM_TEXT_ID_CANDIDATES),
    }

    // ------------------------------------------------------------------ 配置

    // 关开关注解在 `PageSyncFeature.onConfigChanged`：还原（[restore]）+ 摘掉两个回调。
    // 这里不再覆写 —— 「关掉立刻恢复原版」是骨架的统一契约。

    private companion object {
        /** 「更多」面板的显示入口（实测是 PopupWindow，不是 Dialog） */
        val POPUP_SHOW_METHODS = arrayOf("showAsDropDown", "showAtLocation")

        /**
         * 等面板出现的上限；超时就把这一轮作废，别劫持用户下一次手点「更多」。
         *
         * 只有「还没被认领」的轮次会超时 —— 认领到的正在 [attemptHandle] 里走。
         */
        const val PENDING_TIMEOUT_MS = 1500L

        /** 图标采集遇到用户点击时让位的重试间隔（采集不着急，等这一轮走完再来） */
        const val HARVEST_RETRY_MS = 600L

        /** 等 `GridView` 子项完成 measure/layout 的重试次数与间隔 */
        const val MAX_ATTEMPTS = 12
        const val RETRY_MS = 16L

        /**
         * 注入时把「更多」盒子的**原宽**记在我们自己的 holder 上（keyed tag）。
         *
         * holder 是我们 new 出来的私有视图，宿主不会碰它，用一个固定字符串的哈希当
         * key 不会与任何人撞。热重载兜底清理（[detachOrphan]）靠它找回原宽。
         */
        val KEY_ORIG_BOX_WIDTH = "gsaux.stockbar.orig_box_width".hashCode()
    }
}
