package io.github.SansKritO4.GSAuxiliary.feature.stockbar

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
import io.github.SansKritO4.GSAuxiliary.core.ConfigSnapshot
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.core.HookBag
import io.github.SansKritO4.GSAuxiliary.feature.Feature
import io.github.SansKritO4.GSAuxiliary.feature.FeatureEnv
import io.github.SansKritO4.GSAuxiliary.util.Reflect
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
 * ⚠️ **不要指望面板项有可点击祖先**：宿主的点击是挂在 `GridView` 上的
 * `OnItemClickListener`（探针栈 `StockDetailFrag$34#onItemClick` 就是它），
 * 项容器本身不保证 `clickable`。所以必须走 `performItemClick`。
 *
 * ## 四、注入时机
 *
 * `StockFragAct` 继承 `EngineAct`，引擎片段是**异步挂载**的 —— 实测 `onResume`
 * 那一刻 `decorView.findViewById(stk_detial_bottom_bar)` 返回 **null**，
 * 而且底栏容器出现之后里面的按钮还要再晚一点才齐。
 * 所以用**以「注入成功」为准的有限重试**，不是「找到底栏就完事」。
 *
 * 重试之前先按**页面类名**门禁（[StockDetailBarTargets.isWatchActivity]）：
 * 详情页之外的页面直接跳过，否则每个页面都要白跑 6 秒重试并打一条误导性的警告。
 *
 * ## 五、图标
 *
 * 底栏按钮是「图标在上、文字在下」的样式，图标来自面板里那一项自带的
 * `drawableTop`。注入后**异步采集一次**（同样是隐形开一次面板），取到就套到
 * 所有已注入的按钮上；取不到就退化成纯文字按钮，功能不受影响。
 */
class StockDetailBarFeature : Feature {

    override val id = "stock_bar_entry"
    override val title = "个股详情页底栏智能盯盘入口"

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.stockBarEntry

    /** 一次注入的现场，用于「关开关立即还原」 */
    private class Injected(
        val box: ViewGroup,
        val holder: LinearLayout,
        val moreText: TextView,
        val button: TextView,
        val originalBoxWidth: Int,
        /** 「更多」那个图标的目标尺寸 —— 面板里采到的图标比它大，不缩会顶破底栏 */
        val iconW: Int,
        val iconH: Int,
    )

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

    // ------------------------------------------------------------------ 状态

    /** 面板里那一项的图标（进程级缓存，取到一次即可复用） */
    @Volatile private var entryIcon: Drawable? = null

    /** 是否已经发起过图标采集（失败会复位，允许下次重试） */
    private val harvestStarted = AtomicBoolean(false)

    /**
     * 「这次面板是我们主动打开的」标记。
     *
     * 存的是**代次**而不是布尔：点击与超时兜底各自拿着自己的代次去 `compareAndSet`，
     * 避免「上一次的超时任务把下一次的标记清掉」这种竞态。
     */
    private val pendingClick = AtomicInteger(0)
    private val pendingHarvest = AtomicInteger(0)
    private val gen = AtomicInteger(0)

    /** 已注入的现场，键是「更多」的盒子（弱引用） */
    private val injected =
        Collections.synchronizedMap(WeakHashMap<ViewGroup, Injected>())

    /** 「我们主动弹面板」前的宿主窗口快照（见 [WindowSnapshot]） */
    private val pendingWindow = AtomicReference<WindowSnapshot?>(null)

    /**
     * 「我们主动弹面板」的**全过程**代次（0 = 空闲）。
     *
     * 与 [pendingClick] 的区别：那个在 `PopupWindow` 显示时就被清掉（用于「认领」），
     * 而这个要活到**面板关掉为止** —— 宿主是在 `show()` **返回之后**才把窗口调暗的，
     * 认领那一刻还来得及，之后就得靠这个标记继续压住（见 [hookWindowLayout]）。
     */
    private val inFlight = AtomicInteger(0)

    /** 每轮只打一条「拦住宿主调暗」的日志，避免刷屏 */
    private val guardLogged = AtomicBoolean(false)

    /** 资源 id 缓存：0 = 还没解析过，-1 = 解析过但不存在，>0 = 命中 */
    @Volatile private var bottomBarIdCache = 0
    @Volatile private var menuGridIdCache = 0
    @Volatile private var menuItemTextIdCache = 0

    // ------------------------------------------------------------------ 安装

    override fun install(env: FeatureEnv, bag: HookBag) {
        hookActivityResume(env, bag)
        hookPopupWindow(env, bag)
        hookWindowLayout(env, bag)
    }

    /** 挂 `Activity.onResume`：每次页面回来都同步一次注入状态 */
    private fun hookActivityResume(env: FeatureEnv, bag: HookBag) {
        val cls = Reflect.loadClass("android.app.Activity", env.classLoader)
        if (cls == null) {
            GLog.e("底栏入口：找不到 android.app.Activity，功能不会生效")
            return
        }
        var hooked = 0
        for (m in Reflect.methodsNamed(cls, "onResume")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("stockbar:onResume")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            (chain.thisObject as? Activity)?.let { onActivityResumed(env, it) }
                        }.onFailure { GLog.w("底栏入口：onResume 处理异常", it) }
                        result
                    }
            )
            hooked++
        }
        if (hooked == 0) GLog.e("底栏入口：没挂上 Activity.onResume") else
            GLog.i("底栏入口：已挂 $hooked 个 Activity.onResume")
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
     * 代次非 0 时把 `alpha` / `dimAmount` 夹回快照值即可，一帧都不会被画出来。
     *
     * ⚠️ 只在 [inFlight] 非 0（我们自己的那 ~50ms）里动手，用户手点「更多」时
     * 一个字节都不碰 —— 原版的调暗行为保持不变。
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
        if (inFlight.get() == 0) return
        val s = pendingWindow.get() ?: return
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

    // ------------------------------------------------------------------ 注入

    private fun onActivityResumed(env: FeatureEnv, activity: Activity) {
        if (!env.config.stockBarEntry) {
            // 关掉开关：把底栏还原成原版
            removeAllInjected()
            return
        }
        // 只有个股 / 指数详情页才值得跑注入重试。别的页面（H5、资讯、首页…）
        // 每次 onResume 都要白跑 60 次 × 100ms 的重试，最后再打一条
        // 「重试 60 次仍没等到可注入的底栏」的警告 —— 纯噪音，而且这条警告会把
        // 真正「详情页注入失败」的信号淹没。判定方式与 `StgSelectAllFeature` /
        // `StgStockFilterFeature` 一致：按承载页面的类名。
        if (!StockDetailBarTargets.isWatchActivity(activity.javaClass.name)) return
        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return
        scheduleInject(env, root, 0)
    }

    /** 以「注入成功」为准的有限重试（引擎片段挂载完成前底栏还不存在） */
    private fun scheduleInject(env: FeatureEnv, root: View, attempt: Int) {
        if (!env.config.stockBarEntry) return
        if (tryInjectOnce(env, root, attempt)) return
        if (attempt < INJECT_ATTEMPTS) {
            root.postDelayed(
                { runCatching { scheduleInject(env, root, attempt + 1) } },
                INJECT_RETRY_MS,
            )
        } else {
            GLog.w("底栏入口：重试 $INJECT_ATTEMPTS 次仍没等到可注入的底栏，本页放弃")
        }
    }

    /** 尝试注入一次；返回 true = 已注入 / 无需再试 */
    private fun tryInjectOnce(env: FeatureEnv, root: View, attempt: Int): Boolean {
        val barId = resolveId(root.context, IdKey.BOTTOM_BAR)
        if (barId == 0) return true                  // App 里没这个资源，重试也没用
        val bar = root.findViewById<View>(barId) ?: return false
        if (attempt == DIAG_ATTEMPT) logDiag(root, bar)
        return runCatching { inject(env, bar) }.getOrElse {
            GLog.w("底栏入口：注入失败", it)
            true                                     // 抛异常就别再重试了
        }
    }

    /** 一次性的现场诊断（只在重试到 [DIAG_ATTEMPT] 次、还没成功时打一条） */
    private fun logDiag(root: View, bar: View) {
        val ids = StockDetailBarTargets.MORE_TEXT_ID_CANDIDATES.joinToString(" ") {
            "$it=${resolveIdByName(root.context, it)}"
        }
        val kids = (bar as? ViewGroup)?.let { g ->
            (0 until g.childCount).joinToString(",") { i ->
                "${g.getChildAt(i).javaClass.simpleName}#${g.getChildAt(i).id}"
            }
        }
        GLog.i("底栏入口：注入现场 bar=${bar.javaClass.simpleName} 子项=[$kids] 更多 id: $ids")
    }

    /**
     * 幂等地把「智能盯盘」按钮并排塞进「更多」的盒子里。
     *
     * 返回 true = 已经注入 / 本来就注入过；false = 这页还没准备好（调用方重试）。
     */
    private fun inject(env: FeatureEnv, bar: View): Boolean {
        val moreText = findMoreText(bar) ?: return false
        // 幂等：「更多」已经被我们搬进 holder 了，所以不能只看它的直接父容器
        if (findInjectedAncestor(moreText) != null) return true
        val box = moreText.parent as? ViewGroup ?: return false
        // 盒子可能是别的页面残留的同一个 id，确认它属于这条底栏
        if (!isDescendant(box, bar)) return false

        val entry = buildEntry(moreText) ?: return false
        val built = buildInline(moreText, box, entry) ?: return false
        injected[box] = built
        applyIcon(built)
        GLog.i(
            "底栏入口：已在「自选」与「更多」之间注入「${StockDetailBarTargets.ENTRY_TEXT}」" +
                "（盒子 ${box.javaClass.simpleName}，原宽 ${built.originalBoxWidth}px）"
        )

        // 首次注入时异步取一次图标（隐形开一次面板，用户看不到）
        maybeHarvestIcon(moreText)
        return true
    }

    /** 沿祖先找「已经注入过」的那个盒子（「更多」此刻可能被搬进了 holder） */
    private fun findInjectedAncestor(v: View): ViewGroup? {
        var cur: View? = v
        while (cur != null) {
            val p = cur.parent as? ViewGroup ?: return null
            if (injected.containsKey(p)) return p
            cur = p
        }
        return null
    }

    /**
     * 真正动手：把「更多」搬进一个横向容器，前面并排我们的按钮，再把容器塞回盒子，
     * 最后把盒子宽度改成 `wrap_content`（这一步才是「买卖让位」的来源）。
     */
    private fun buildInline(moreText: TextView, box: ViewGroup, entry: TextView): Injected? {
        val originalWidth = box.width.takeIf { it > 0 }
            ?: box.measuredWidth.takeIf { it > 0 }
            ?: box.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val gapPx = gapPx(moreText)
        val moreWidth = moreText.width.takeIf { it > 0 }
            ?: moreText.measuredWidth.takeIf { it > 0 } ?: 0
        val index = box.indexOfChild(moreText)
        val (iconW, iconH) = refIconSize(moreText)

        // ① 把「更多」从盒子里摘出来
        box.removeView(moreText)

        // ② 横向容器：[我们的按钮] [更多]
        val holder = LinearLayout(moreText.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
            runCatching { box.addView(moreText, index.coerceAtLeast(0)) }
            return null
        }

        // ④ 盒子宽度 → wrap_content：链上固定成员变宽，买卖自动让位
        val lp = box.layoutParams
        if (lp != null && lp.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            runCatching { box.layoutParams = lp }
        }
        runCatching { box.requestLayout() }

        return Injected(box, holder, moreText, entry, originalWidth, iconW, iconH)
    }

    /** 「更多」自己那个图标的尺寸 —— 我们的图标按它缩放，才能和同排按钮一样高 */
    private fun refIconSize(moreText: TextView): Pair<Int, Int> {
        val d = runCatching { moreText.compoundDrawables.getOrNull(1) }.getOrNull()
        var w = d?.intrinsicWidth ?: 0
        var h = d?.intrinsicHeight ?: 0
        if (w <= 0) w = runCatching { d?.bounds?.width() ?: 0 }.getOrDefault(0)
        if (h <= 0) h = runCatching { d?.bounds?.height() ?: 0 }.getOrDefault(0)
        if (w <= 0 || h <= 0) {
            // 兜底：按 22dp 方图（与「更多」的 ••• 视觉相当）
            val px = (FALLBACK_ICON_DP * moreText.resources.displayMetrics.density + 0.5f).toInt()
            w = px
            h = px
        }
        return w to h
    }

    /** 与「更多」之间的间距：优先沿用盒子原本的 margin，拿不到就用 10dp */
    private fun gapPx(moreText: TextView): Int {
        val m = (moreText.parent as? View)?.layoutParams as? ViewGroup.MarginLayoutParams
        val fromMargin = m?.marginStart ?: 0
        if (fromMargin > 0) return fromMargin
        return (GAP_DP * moreText.resources.displayMetrics.density + 0.5f).toInt()
    }

    /** 从「更多」按钮克隆出一颗样式一致、但**独立 id / 独立 tag** 的按钮 */
    private fun buildEntry(moreText: TextView): TextView? {
        val button = TextView(moreText.context)
        copyStyle(moreText, button)
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

    /**
     * 复制「更多」按钮的可见样式。
     *
     * 与 `StgSelectAllFeature.copyStyle` 同思路：只复制可见属性，尺寸交给容器。
     */
    private fun copyStyle(src: TextView, dst: TextView) {
        runCatching { dst.setTextColor(src.textColors) }
        runCatching { src.typeface?.let { dst.typeface = it } }
        runCatching { dst.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, src.textSize) }
        runCatching { dst.letterSpacing = src.letterSpacing }
        runCatching { dst.textScaleX = src.textScaleX }
        runCatching {
            dst.setPadding(src.paddingLeft, src.paddingTop, src.paddingRight, src.paddingBottom)
        }
        runCatching { dst.compoundDrawablePadding = src.compoundDrawablePadding }
        runCatching { dst.includeFontPadding = src.includeFontPadding }
        runCatching { dst.maxLines = src.maxLines }
        runCatching { dst.ellipsize = src.ellipsize }
        runCatching { dst.setBackground(src.background?.constantState?.newDrawable()?.mutate()) }
        runCatching { dst.backgroundTintList = src.backgroundTintList }
        runCatching { dst.foreground = src.foreground?.constantState?.newDrawable()?.mutate() }
        runCatching { dst.setAllCaps(false) }
    }

    /** 关开关 / 撤销注入：把「更多」搬回盒子、盒子宽度改回原值 */
    private fun removeAllInjected() {
        val entries = synchronized(injected) {
            val list = injected.values.toList()
            injected.clear()
            list
        }
        for (e in entries) runCatching { detach(e) }
    }

    private fun detach(e: Injected) {
        val holder = e.holder
        runCatching { holder.removeView(e.button) }
        runCatching { holder.removeView(e.moreText) }
        runCatching { (holder.parent as? ViewGroup)?.removeView(holder) }
        val index = runCatching { e.box.indexOfChild(e.moreText) }.getOrDefault(-1)
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

    /** 用户点了我们注入的按钮：触发「更多」→ 由弹层钩子接力点「智能盯盘」 */
    private fun openEntry(v: View) {
        val root = v.rootView ?: return
        val moreText = findMoreText(root) ?: run {
            GLog.w("底栏入口：点击时找不到「更多」按钮")
            return
        }
        val token = gen.incrementAndGet()
        pendingClick.set(token)
        // 全程标记（活到面板关掉为止）—— 宿主是在 show() 之后才调暗窗口的
        inFlight.set(token)
        guardLogged.set(false)
        // ★ 必须在 performClick 之前抓窗口快照 —— 宿主是在点击处理里把窗口调暗的
        pendingWindow.set(captureWindow(v))
        moreText.performClick()
        // 超时兜底：面板没被我们拦到就清标记，别劫持用户下一次手点「更多」
        moreText.postDelayed({
            if (pendingClick.compareAndSet(token, 0)) {
                inFlight.compareAndSet(token, 0)
                GLog.w("底栏入口：没等到「更多」面板，本次点击未生效")
            }
        }, PENDING_TIMEOUT_MS)
    }

    /** 首次注入时隐形开一次面板，把「智能盯盘」那一项的图标取回来 */
    private fun maybeHarvestIcon(moreText: TextView) {
        if (entryIcon != null) return
        if (!harvestStarted.compareAndSet(false, true)) return
        val token = gen.incrementAndGet()
        pendingHarvest.set(token)
        inFlight.set(token)
        guardLogged.set(false)
        moreText.post {
            pendingWindow.set(captureWindow(moreText))
            runCatching { moreText.performClick() }
                .onFailure { GLog.w("底栏入口：采集图标时打开面板失败", it) }
            moreText.postDelayed({
                if (pendingHarvest.compareAndSet(token, 0)) {
                    inFlight.compareAndSet(token, 0)
                    harvestStarted.set(false)      // 允许下次重试
                    GLog.w("底栏入口：没等到面板，图标采集跳过（按钮退化为纯文字）")
                }
            }, PENDING_TIMEOUT_MS)
        }
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
     * 面板显示时被调用。
     *
     * - 只有确认「这是更多面板」（能找到 `GridView`）才认领，
     *   否则原样放行 —— 绝不能误伤 App 里其它 `PopupWindow`；
     * - 不是我们触发的那一次，一个字节都不改。
     */
    private fun onPopupShown(self: Any?) {
        val popup = self as? PopupWindow ?: return
        val click = pendingClick.getAndSet(0)
        val harvest = pendingHarvest.getAndSet(0)
        if (click == 0 && harvest == 0) return

        val content = popup.contentView ?: run {
            GLog.w("底栏入口：面板没有 contentView，放弃本次认领")
            return
        }
        // GridView 是「更多面板」的指纹；findViewById 在布局前就能命中，所以能同步判断
        if (findMenuGrid(content) == null) {
            GLog.w("底栏入口：这次弹层里没有 ${StockDetailBarTargets.MENU_GRID_ID_CANDIDATES.joinToString()}，不是更多面板")
            return
        }

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
        restoreWindow(pendingWindow.get(), "打开时")

        // token = 这一轮「我们主动弹面板」的代次，收尾时用它把 [inFlight] 清掉
        val token = if (click != 0) click else harvest
        content.post { attemptHandle(content, layer, popup, click, token, 0) }
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
        click: Int,
        token: Int,
        attempt: Int,
    ) {
        val grid = findMenuGrid(content)
        val item = grid?.let { findMenuEntryText(it) }

        if (item == null && attempt < MAX_ATTEMPTS) {
            content.postDelayed(
                { runCatching { attemptHandle(content, layer, popup, click, token, attempt + 1) } },
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
                if (click != 0 && grid != null) clickMenuEntry(grid, item)
            }
        }.onFailure { GLog.w("底栏入口：处理面板内容异常", it) }

        // 宿主可能在 dismiss 里再调一次窗口，所以这里再兜一次底
        restoreWindow(pendingWindow.getAndSet(null), "关闭时")
        runCatching { layer.alpha = 1f }   // 弹层实例可能被宿主复用，务必还原
        runCatching { popup.dismiss() }

        // 收尾：撤掉防闪夹子（夹子只活在我们自己这一轮里）
        inFlight.compareAndSet(token, 0)
        guardLogged.set(false)

        if (item == null) {
            harvestStarted.set(false)        // 允许下次重试
            GLog.w("底栏入口：面板里没找到「${StockDetailBarTargets.MENU_ITEM_TEXT}」")
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
        }
    }

    // ------------------------------------------------------------------ 查找

    /**
     * 找底栏上的「更多」按钮。
     *
     * 两套形态各有一个「更多」，**取当前显示的那个**（另一个是 `GONE`）。
     * 都按不到 id 时，再**只在底栏范围内**按文字兜底 —— 页面别处也有「更多」
     * （K 线周期栏就有一个），全树按文字找会抓错。
     */
    private fun findMoreText(anyView: View): TextView? {
        val root = anyView.rootView ?: return null
        var fallback: TextView? = null
        for (name in StockDetailBarTargets.MORE_TEXT_ID_CANDIDATES) {
            val id = resolveIdByName(root.context, name)
            if (id == 0) continue
            val tv = root.findViewById<View>(id) as? TextView ?: continue
            if (tv.isShown) return tv
            if (fallback == null) fallback = tv
        }
        if (fallback != null) return fallback

        val barId = resolveId(root.context, IdKey.BOTTOM_BAR)
        if (barId == 0) return null
        val bar = root.findViewById<View>(barId) ?: return null
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

    /** 单次解析（不缓存）：用于「两个形态各一个」的候选 id */
    private fun resolveIdByName(context: Context, name: String): Int =
        runCatching {
            context.resources.getIdentifier(name, "id", StockDetailBarTargets.TARGET_PKG)
        }.getOrDefault(0)

    private enum class IdKey(val names: Array<String>) {
        BOTTOM_BAR(StockDetailBarTargets.BOTTOM_BAR_ID_CANDIDATES),
        MENU_GRID(StockDetailBarTargets.MENU_GRID_ID_CANDIDATES),
        MENU_ITEM_TEXT(StockDetailBarTargets.MENU_ITEM_TEXT_ID_CANDIDATES),
    }

    // ------------------------------------------------------------------ 配置

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        if (cfg.stockBarEntry) return
        removeAllInjected()
        GLog.i("底栏入口：已关闭，底栏恢复原版")
    }

    private companion object {
        /** 「更多」面板的显示入口（实测是 PopupWindow，不是 Dialog） */
        val POPUP_SHOW_METHODS = arrayOf("showAsDropDown", "showAtLocation")

        /** 等面板出现的上限；超时就把标记清掉，避免影响用户手点 */
        const val PENDING_TIMEOUT_MS = 1500L

        /** 等 `GridView` 子项完成 measure/layout 的重试次数与间隔 */
        const val MAX_ATTEMPTS = 12
        const val RETRY_MS = 16L

        /**
         * 等底栏出现的重试次数与间隔。
         *
         * `EngineAct` 的引擎片段是异步挂载的，实测 `onResume` 时底栏还不存在
         * （`decorView.findViewById` 返回 null），有时要 3 秒以上才齐。
         */
        const val INJECT_ATTEMPTS = 60
        const val INJECT_RETRY_MS = 100L

        /** 重试到第几次还没成功就打一条现场诊断（成功路径零噪音） */
        const val DIAG_ATTEMPT = 20

        /** 拿不到盒子 margin 时，新按钮与「更多」之间的兜底间距（dp） */
        const val GAP_DP = 10f

        /** 量不到「更多」图标尺寸时的兜底边长（dp） */
        const val FALLBACK_ICON_DP = 22f
    }
}
