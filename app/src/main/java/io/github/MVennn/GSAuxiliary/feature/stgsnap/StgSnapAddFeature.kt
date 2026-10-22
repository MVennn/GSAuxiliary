package io.github.MVennn.GSAuxiliary.feature.stgsnap

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.HookBag
import io.github.MVennn.GSAuxiliary.feature.Feature
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.util.Density
import io.github.MVennn.GSAuxiliary.util.Reflect
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * 功能：策略详情多选态底部「确认添加」左侧注入「截图并添加」。
 *
 * 点击后：
 *  1. 读取勾选股票（checked states + 筛选映射）；
 *  2. 按选中子集打开一次 `StockFragAct`（`stockList` + `stockIndex=0`）；
 *  3. 逐页切到每一只，**等标题代码匹配 + 日 K 数据非空**后 PixelCopy 截图；
 *  4. 存相册；
 *  5. finish 回策略页，自动点宿主「确认添加」→ 宿主弹 `ChooseGroupAct`。
 *
 * 数据没就绪的那一页不截图（只记失败），绝不保存半张图。
 */
class StgSnapAddFeature : Feature {

    override val id = "stg_snap_add"
    override val title = "策略详情截图并添加"

    /** 锚点按钮 → 我们挂的 TextWatcher（弱引用，防泄漏） */
    private val watchers =
        Collections.synchronizedMap(WeakHashMap<TextView, WeakReference<TextWatcher>>())

    /** 锚点按钮 → 注入的「截图并添加」 */
    private val injected = Collections.synchronizedMap(WeakHashMap<TextView, TextView>())

    private var lastStrategy: WeakReference<Activity>? = null
    private var lastCancel: WeakReference<TextView>? = null

    private var pendingItems: List<SelectedStock>? = null
    private var pendingStrategy: WeakReference<Activity>? = null
    private var pendingCount = 0
    private var job: SnapCaptureJob? = null

    private var pendingConfirm = false
    private var pendingSaved = 0
    private var pendingFailed = 0
    private var pendingError: String? = null

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.stgSnapAdd

    // ------------------------------------------------------------------ 安装

    override fun install(env: FeatureEnv, bag: HookBag) {
        clearState()
        val cls = Reflect.loadClass("android.app.Activity", env.classLoader)
        if (cls == null) {
            GLog.e("截图并添加：找不到 android.app.Activity，功能不会生效")
            return
        }
        var resumed = 0
        for (m in Reflect.methodsNamed(cls, "onResume")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("$id:onResume")
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            (chain.thisObject as? Activity)?.let { onActivityResumed(env, it) }
                        }.onFailure { GLog.w("截图并添加：onResume 处理异常", it) }
                        result
                    },
            )
            resumed++
        }
        var paused = 0
        for (m in Reflect.methodsNamed(cls, "onPause")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("$id:onPause")
                    .intercept { chain ->
                        runCatching {
                            (chain.thisObject as? Activity)?.let { onActivityPaused(it) }
                        }.onFailure { GLog.w("截图并添加：onPause 处理异常", it) }
                        chain.proceed()
                    },
            )
            paused++
        }
        GLog.i("截图并添加：已挂 onResume×$resumed / onPause×$paused")
    }

    // ------------------------------------------------------------------ 页面

    private fun onActivityResumed(env: FeatureEnv, activity: Activity) {
        val cls = activity.javaClass.name
        when (cls) {
            StgSnapAddTargets.CLS_STG_DETAIL -> {
                lastStrategy = WeakReference(activity)
                val cancel = findView(activity, StgSnapAddTargets.CANCEL_BUTTON_ID_CANDIDATES)
                if (cancel is TextView) {
                    lastCancel = WeakReference(cancel)
                    ensureWatcher(env, cancel)
                }
                // 先处理接力（会立刻跳 ChooseGroupAct），再考虑注入
                if (handlePendingConfirm(env, activity)) return
                if (cancel is TextView) sync(env, activity, cancel, true)
            }

            StgSnapAddTargets.CLS_STOCK_QUOTE -> {
                val items = pendingItems ?: return
                if (job != null) return
                pendingItems = null
                pendingStrategy = null
                val capture = SnapCaptureJob(env, activity, items) { result ->
                    onJobFinished(result)
                }
                job = capture
                capture.start()
            }
        }
    }

    private fun onActivityPaused(activity: Activity) {
        if (activity.javaClass.name != StgSnapAddTargets.CLS_STOCK_QUOTE) return
        val running = job ?: return
        if (running.finishingNormally) return
        job = null
        running.cancelExternal("详情页暂停")
    }

    // ------------------------------------------------------------------ 注入

    private fun ensureWatcher(env: FeatureEnv, button: TextView) {
        if (watchers[button]?.get() != null) return
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runCatching {
                    val act = findActivity(button) ?: return@runCatching
                    sync(env, act, button, false)
                }.onFailure { GLog.w("截图并添加：同步按钮状态异常", it) }
            }
        }
        button.addTextChangedListener(watcher)
        watchers[button] = WeakReference(watcher)
    }

    private fun sync(env: FeatureEnv, activity: Activity, cancel: TextView, retry: Boolean) {
        if (!env.config.stgSnapAdd) {
            removeInjected(cancel)
            return
        }
        if (cancel.text?.toString() != StgSnapAddTargets.CANCEL_TEXT) {
            removeInjected(cancel)
            return
        }
        val confirm = findView(activity, StgSnapAddTargets.CONFIRM_BUTTON_ID_CANDIDATES)
        if (confirm == null) {
            if (retry) {
                cancel.post {
                    runCatching {
                        if (cancel.isAttachedToWindow &&
                            cancel.text?.toString() == StgSnapAddTargets.CANCEL_TEXT
                        ) {
                            sync(env, activity, cancel, false)
                        }
                    }
                }
            } else {
                GLog.w("截图并添加：找不到 ${StgSnapAddTargets.CONFIRM_BUTTON_ID_CANDIDATES.joinToString()}")
            }
            return
        }
        val existing = injected[cancel]
        if (existing != null && existing.parent === confirm.parent) return
        if (existing != null) removeInjected(cancel)
        inject(env, activity, cancel, confirm)
    }

    private fun inject(env: FeatureEnv, activity: Activity, cancel: TextView, confirm: View) {
        val parent = confirm.parent as? ViewGroup ?: return
        val button = TextView(confirm.context).apply {
            text = StgSnapAddTargets.BUTTON_TEXT
            copyStyle(confirm as? TextView, this)
            // 源按钮是固定宽 + 文字居中；我们用 wrap_content，文字会贴边。
            // 补 10dp 水平内边距，让文字左右都有余量（实测仍不挤「已选 N」）。
            val extra = Density.dp(confirm, 10f)
            setPadding(
                paddingLeft + extra,
                paddingTop,
                paddingRight + extra,
                paddingBottom,
            )
            gravity = android.view.Gravity.CENTER
            isClickable = true
            isFocusable = true
            tag = StgSnapAddTargets.BUTTON_TAG
            id = View.generateViewId()
            setOnClickListener {
                runCatching { onSnapClick(env, activity) }
                    .onFailure { GLog.w("截图并添加：按钮点击异常", it) }
            }
        }
        val srcLp = confirm.layoutParams
        val height = when {
            srcLp != null && srcLp.height == ViewGroup.LayoutParams.MATCH_PARENT ->
                ViewGroup.LayoutParams.MATCH_PARENT
            srcLp != null && srcLp.height > 0 -> srcLp.height
            confirm.height > 0 -> confirm.height
            else -> ViewGroup.LayoutParams.MATCH_PARENT
        }
        val srcWidth = when {
            confirm.width > 0 -> confirm.width
            confirm.layoutParams != null && confirm.layoutParams.width > 0 -> confirm.layoutParams.width
            else -> 0
        }
        val textWidth = button.paint.measureText(button.text.toString()).toInt() +
            button.paddingLeft + button.paddingRight
        val width = if (srcWidth > 0) {
            (srcWidth - Density.dp(confirm, 12f)).coerceAtLeast(textWidth)
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val lp: ViewGroup.LayoutParams = if (parent is LinearLayout) {
            LinearLayout.LayoutParams(width, height)
        } else {
            ViewGroup.MarginLayoutParams(width, height)
        }
        val index = parent.indexOfChild(confirm)
        runCatching { parent.addView(button, index, lp) }
            .onFailure {
                GLog.w("截图并添加：插入按钮失败", it)
                return
            }
        injected[cancel] = button
        // 注入时底栏可能还没量过；等一帧后按确认按钮的实测宽度补一次，
        // 让两颗按钮几乎等宽、文字有足够左右余量（不再贴边）。
        if (srcWidth <= 0) {
            parent.post {
                val now = confirm.width
                if (now > 0 && button.parent === parent) {
                    val target = (now - Density.dp(confirm, 12f)).coerceAtLeast(textWidth)
                    val p = button.layoutParams
                    p.width = target
                    button.layoutParams = p
                }
            }
        }
        GLog.i("截图并添加：已在确认添加左侧注入按钮")
    }

    private fun removeInjected(cancel: TextView) {
        val button = injected.remove(cancel) ?: return
        runCatching { (button.parent as? ViewGroup)?.removeView(button) }
        runCatching { button.setOnClickListener(null) }
    }

    private fun copyStyle(src: TextView?, dst: TextView) {
        if (src == null) return
        runCatching {
            val bg = src.background
            val cs = bg?.constantState
            if (cs != null) {
                dst.background = cs.newDrawable()
            } else {
                dst.background = bg
            }
        }
        runCatching { dst.backgroundTintList = src.backgroundTintList }
        runCatching { dst.setTextColor(src.textColors) }
        runCatching { dst.typeface = src.typeface }
        runCatching { dst.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, src.textSize) }
        runCatching { dst.textScaleX = src.textScaleX }
        runCatching { dst.letterSpacing = src.letterSpacing }
        runCatching { dst.includeFontPadding = src.includeFontPadding }
        runCatching { dst.ellipsize = src.ellipsize }
        runCatching { dst.maxLines = src.maxLines }
        runCatching { dst.minHeight = src.minHeight }
        runCatching { dst.foreground = src.foreground }
        dst.setPadding(src.paddingLeft, src.paddingTop, src.paddingRight, src.paddingBottom)
    }

    // ------------------------------------------------------------------ 点击

    private fun onSnapClick(env: FeatureEnv, activity: Activity) {
        if (!env.config.stgSnapAdd) return
        if (job != null || pendingItems != null) {
            toast(activity, "正在截图，请稍候")
            return
        }
        when (val result = SnapSelection.read(activity)) {
            is SelectionResult.Fail -> {
                GLog.w("截图并添加：读取选中失败（${result.reason}）")
                toast(activity, result.message)
            }

            is SelectionResult.Ok -> {
                pendingItems = result.items
                pendingStrategy = WeakReference(activity)
                pendingCount = result.items.size
                pendingSaved = 0
                pendingFailed = 0
                pendingError = null
                if (!StockDetailLauncher.launch(activity, env, result.items)) {
                    clearPending()
                    toast(activity, "打开个股详情页失败，请重试")
                }
            }
        }
    }

    private fun onJobFinished(result: SnapCaptureJob.Result) {
        job = null
        pendingSaved = result.saved
        pendingFailed = result.failed
        pendingError = result.error
        pendingConfirm = !result.cancelled && result.error == null && result.saved > 0
        when {
            result.cancelled -> {
                GLog.i("截图并添加：任务取消（已保存 ${result.saved}）")
            }

            result.error != null -> {
                GLog.w("截图并添加：任务失败（${result.error}）")
                toastCurrent("截图失败：${result.error}")
            }

            result.saved == 0 -> {
                toastCurrent("未截图：${result.failed} 只数据未就绪")
            }

            result.failed > 0 -> {
                toastCurrent("已截图 ${result.saved} 张，${result.failed} 只数据未就绪")
            }
        }
    }

    // ------------------------------------------------------------------ 确认页接力

    private fun handlePendingConfirm(env: FeatureEnv, activity: Activity): Boolean {
        if (!pendingConfirm) return false
        pendingConfirm = false
        if (!env.config.stgSnapAdd) return false
        val confirm = findView(activity, StgSnapAddTargets.CONFIRM_BUTTON_ID_CANDIDATES)
        if (confirm == null) {
            toast(activity, "找不到确认添加按钮，请手动加入")
            return true
        }
        val bottom = readBottomCount(activity)
        if (bottom >= 0 && pendingCount > 0 && bottom != pendingCount) {
            GLog.w("截图并添加：返回后已选计数=$bottom，启动前=$pendingCount，不自动确认")
            toast(activity, "选择已变化，请手动确认要加入的股票")
            return true
        }
        val saved = pendingSaved
        val failed = pendingFailed
        val msg = if (failed > 0) {
            "已截图 $saved 张（$failed 只数据未就绪），请选择分组"
        } else {
            "已截图 $saved 张，请选择分组"
        }
        toast(activity, msg)
        GLog.i("截图并添加：自动触发宿主确认添加")
        runCatching { confirm.performClick() }
            .onFailure { GLog.w("截图并添加：触发确认添加失败", it) }
        return true
    }

    // ------------------------------------------------------------------ 配置/销毁

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        if (!cfg.stgSnapAdd) {
            job?.let {
                job = null
                it.cancelExternal("功能已关闭")
            }
            clearPending()
            clearState()
            return
        }
        // 重新打开开关：把当前策略页的按钮补回来
        val activity = lastStrategy?.get()
        val cancel = lastCancel?.get()
        if (activity != null && cancel != null && cancel.isAttachedToWindow) {
            runCatching { sync(env, activity, cancel, false) }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        val cls = activity.javaClass.name
        if (cls == StgSnapAddTargets.CLS_STOCK_QUOTE) {
            val running = job
            if (running != null && !running.finishingNormally) {
                job = null
                running.cancelExternal("详情页销毁")
            }
        }
        if (cls == StgSnapAddTargets.CLS_STG_DETAIL) {
            if (lastStrategy?.get() === activity) lastStrategy = null
            if (lastCancel?.get()?.let { findActivity(it) } === activity) lastCancel = null
            if (pendingStrategy?.get() === activity) clearPending()
            pendingConfirm = false
        }
        removeForActivity(activity)
    }

    private fun removeForActivity(activity: Activity) {
        val keys = synchronized(watchers) { watchers.keys.toList() }
        for (key in keys) {
            if (findActivity(key) !== activity) continue
            val w = watchers.remove(key)?.get()
            if (w != null) runCatching { key.removeTextChangedListener(w) }
            removeInjected(key)
        }
    }

    /** 关开关 / 热重载：所有页面上的注入按钮和 watcher 都撤掉 */
    private fun clearState() {
        val keys = synchronized(watchers) { watchers.keys.toList() }
        for (key in keys) {
            val w = watchers.remove(key)?.get()
            if (w != null) runCatching { key.removeTextChangedListener(w) }
            removeInjected(key)
        }
        injected.clear()
        lastStrategy = null
        lastCancel = null
    }

    private fun clearPending() {
        pendingItems = null
        pendingStrategy = null
        pendingCount = 0
        pendingSaved = 0
        pendingFailed = 0
        pendingError = null
        pendingConfirm = false
    }

    // ------------------------------------------------------------------ 小工具

    private fun findView(activity: Activity, names: Array<String>): View? {
        for (name in names) {
            val id = runCatching {
                activity.resources.getIdentifier(name, "id", StgSnapAddTargets.TARGET_PKG)
            }.getOrDefault(0)
            if (id == 0) continue
            activity.findViewById<View>(id)?.let { return it }
        }
        return null
    }

    private fun readBottomCount(activity: Activity): Int {
        val view = findView(activity, StgSnapAddTargets.BOTTOM_COUNT_ID_CANDIDATES)
        return StgSnapPlan.parseCount((view as? TextView)?.text)
    }

    private fun findActivity(view: View): Activity? {
        var ctx: Context? = view.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }

    private fun toast(activity: Activity, message: String) {
        runCatching {
            Toast.makeText(activity.applicationContext, message, Toast.LENGTH_SHORT).show()
        }.onFailure { GLog.w("截图并添加：Toast 失败", it) }
    }

    private fun toastCurrent(message: String) {
        val activity = lastStrategy?.get() ?: return
        toast(activity, message)
    }
}
