package io.github.MVennn.GSAuxiliary.feature.zxcopy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.TargetApp
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.PageSyncFeature
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiClean
import io.github.MVennn.GSAuxiliary.util.Reflect
import io.github.MVennn.GSAuxiliary.util.ViewIds
import java.util.Collections
import java.util.WeakHashMap

/**
 * 功能：**自选页右上角的「编辑」按钮换成「复制」按钮**。
 *
 * 点一下把当前显示分组里的全部股票按「名称 代码」一行一只复制到剪贴板：
 *
 * ```
 * 金山办公 688111
 * 爱尔眼科 300015
 * ```
 *
 * ## 认页与认按钮（真机 2026-10-23 实测）
 *
 * 自选页顶栏：
 *
 * ```
 * #common_top
 *   ├─ MagicIndicator #indicator_zx          ← 自选页独有
 *   ├─ ImageView #iv_top_manager             ← 铅笔「编辑」（本功能替换）
 *   └─ ImageView #iv_top_search              ← 搜索（不动）
 * ```
 *
 * ⚠️ `#iv_top_manager` 在整棵树里有 3 个实例（自选顶栏 / 行情顶栏 / 分组行的「≡」），
 * **不能全局 `findViewById`**。这里先用 `#indicator_zx` 认出页面，再上溯到
 * `#common_top`，最后在它内部取按钮；行情页的 `#indicator_zx` 不存在，自然跳过。
 *
 * ## 还原与热重载
 *
 * 原图 / 原监听 / 原 tint / 原 scaleType / 原 contentDescription / 原 clickable
 * 全部挂在 ImageView 的 keyed tag 上（`R.id.gsaux_zxcopy_*`），不存 Feature 实例 ——
 * 热重载后的新实例能按 tag 认出现场（规则 1.2），关开关时也能把宿主按钮原样还回去。
 *
 * 页面销毁只清记账、不还原（规则见 [PageSyncFeature.onActivityDestroyed]）。
 */
class ZxGroupCopyFeature : PageSyncFeature() {

    override val id = "zx_group_copy"
    override val title = "自选分组复制"

    override val pages = ZxGroupCopyTargets.PAGES

    override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.zxGroupCopy

    override val idNames = arrayOf(
        ZxGroupCopyTargets.ZX_INDICATOR_ID,
        ZxGroupCopyTargets.TOP_BAR_ID,
        ZxGroupCopyTargets.EDIT_BUTTON_ID,
        ZxGroupCopyTargets.GROUP_INDICATOR_ID,
        ZxGroupCopyTargets.TITLE_CONTAINER_ID,
        ZxGroupCopyTargets.PAGER_ID,
        ZxGroupCopyTargets.LIST_CONTAINER_ID,
        ZxGroupCopyTargets.NAME_ID,
        ZxGroupCopyTargets.CODE_ID,
    )

    /** 见过的 decorView（弱引用）—— 关开关 / 热重载时按它扫描并还原 tag 现场 */
    private val seenDecors = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /** 自绘复制图标（按尺寸缓存；同一进程只有自选页一颗按钮用到） */
    @Volatile private var iconCache: Drawable? = null
    @Volatile private var iconSize = 0

    /** 所有复制按钮共用的点击监听：从 View 的 Context 现取 Activity，不捕获页面 */
    private val onClick = View.OnClickListener { v ->
        val act = activityOf(v.context)
        if (act == null) {
            GLog.w("$id：复制按钮的 Context 不是 Activity，忽略本次点击")
            return@OnClickListener
        }
        runCatching { copyCurrentGroup(act) }
            .onFailure {
                GLog.w("$id：复制失败", it)
                toast(act, "复制失败")
            }
    }

    // ------------------------------------------------------------------ 同步

    override fun sync(env: FeatureEnv, activity: Activity): Boolean {
        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return true
        seenDecors[root] = true

        val ids = ViewIds.idsFor(activity, TargetApp.PACKAGE)
        val zxIndicatorId = ids.of(ZxGroupCopyTargets.ZX_INDICATOR_ID)
        val topBarId = ids.of(ZxGroupCopyTargets.TOP_BAR_ID)
        val editId = ids.of(ZxGroupCopyTargets.EDIT_BUTTON_ID)
        if (zxIndicatorId == 0 || topBarId == 0 || editId == 0) return false

        // 自选页独有的锚点：行情页 / 首页没有它 → 本页无事可做
        val indicator = root.findViewById<View>(zxIndicatorId) ?: return true
        val topBar = ancestorWithId(indicator, topBarId) ?: return false
        val pencil = topBar.findViewById<View>(editId) as? ImageView ?: return false

        // 自选页在后台（当前停在别的 tab）时按钮不可见，等切回来那趟布局再改
        if (!pencil.isShown) return true

        patch(pencil)
        return true
    }

    /** 从 [v] 上溯到 id 为 [id] 的祖先（含自身） */
    private fun ancestorWithId(v: View, id: Int): View? {
        var cur: View? = v
        var guard = 0
        while (cur != null && guard++ < 20) {
            if (cur.id == id) return cur
            cur = cur.parent as? View
        }
        return null
    }

    /**
     * 把 [pencil] 换成复制按钮（幂等 + 自愈）。
     *
     * 快路径：tag 里的现场全对得上就直接返回，避免每次布局都做一次反射查监听
     * （自选列表滚动也会触发布局回调）。宿主重建页面时是新 ImageView，
     * `patched` 为 false，会重新走完整路径。
     */
    private fun patch(pencil: ImageView) {
        val patched = pencil.getTag(R.id.gsaux_zxcopy_patched) == true
        val oursDrawable = pencil.getTag(R.id.gsaux_zxcopy_ours_drawable) as? Drawable
        val oursListener = pencil.getTag(R.id.gsaux_zxcopy_ours_listener) as? View.OnClickListener

        if (patched && oursDrawable != null && oursListener === onClick &&
            pencil.drawable === oursDrawable && pencil.imageTintList == null &&
            pencil.scaleType == ImageView.ScaleType.FIT_CENTER &&
            pencil.contentDescription?.toString() == ZxGroupCopyTargets.COPY_DESC &&
            pencil.isClickable
        ) {
            return
        }

        val currentDrawable = pencil.drawable
        val currentListener = currentClickListener(pencil)

        // 记录宿主原现场：只有「当前不是我们放进去的东西」才记，
        // 否则热重载后会把我们自己的图标当成宿主原图。
        if (!patched || (oursDrawable != null && currentDrawable !== oursDrawable)) {
            pencil.setTag(R.id.gsaux_zxcopy_orig_drawable, currentDrawable)
            pencil.setTag(R.id.gsaux_zxcopy_orig_tint, pencil.imageTintList)
            pencil.setTag(R.id.gsaux_zxcopy_orig_scale, pencil.scaleType)
            pencil.setTag(R.id.gsaux_zxcopy_orig_cd, pencil.contentDescription)
            pencil.setTag(R.id.gsaux_zxcopy_orig_clickable, pencil.isClickable)
            pencil.setTag(R.id.gsaux_zxcopy_orig_focusable, pencil.isFocusable)
        }
        if (currentListener !== oursListener) {
            pencil.setTag(R.id.gsaux_zxcopy_orig_listener, currentListener)
        }

        val icon = if (patched && oursDrawable != null) oursDrawable else iconFor(pencil)
        var changed = !patched
        if (pencil.imageTintList != null) {
            pencil.imageTintList = null
            changed = true
        }
        if (pencil.scaleType != ImageView.ScaleType.FIT_CENTER) {
            pencil.scaleType = ImageView.ScaleType.FIT_CENTER
            changed = true
        }
        if (pencil.drawable !== icon) {
            pencil.setImageDrawable(icon)
            changed = true
        }
        if (pencil.contentDescription?.toString() != ZxGroupCopyTargets.COPY_DESC) {
            pencil.contentDescription = ZxGroupCopyTargets.COPY_DESC
            changed = true
        }
        if (currentListener !== onClick) {
            pencil.setOnClickListener(onClick)
            changed = true
        }
        if (!pencil.isClickable) {
            pencil.isClickable = true
            changed = true
        }

        pencil.setTag(R.id.gsaux_zxcopy_ours_drawable, icon)
        pencil.setTag(R.id.gsaux_zxcopy_ours_listener, onClick)
        pencil.setTag(R.id.gsaux_zxcopy_patched, true)
        if (changed) {
            noteChange()
            if (GLog.isInfo) GLog.i("$id：已把自选页的编辑按钮换成复制按钮")
        }
    }

    private fun iconFor(v: ImageView): Drawable {
        val size = if (v.width > 0) v.width else (24 * v.resources.displayMetrics.density).toInt()
        val cached = iconCache
        if (cached != null && iconSize == size) return cached
        val d = ZxCopyIcon.create(v.resources.displayMetrics, size)
        iconCache = d
        iconSize = size
        return d
    }

    /**
     * 取 View 当前的点击监听。
     *
     * `View.getOnClickListener()` 是 hidden API，只能反射 `mListenerInfo.mOnClickListener`；
     * 这也是判断「宿主有没有把监听改回去」的唯一可靠办法。
     */
    private fun currentClickListener(v: View): View.OnClickListener? = runCatching {
        val infoField = Reflect.findField(v.javaClass, "mListenerInfo") ?: return null
        val info = infoField.get(v) ?: return null
        val listenerField = Reflect.findField(info.javaClass, "mOnClickListener") ?: return null
        listenerField.get(info) as? View.OnClickListener
    }.getOrNull()

    // ------------------------------------------------------------------ 还原

    override fun restore() {
        val roots = synchronized(seenDecors) { seenDecors.keys.toList() }
        var restored = 0
        for (root in roots) {
            UiClean.walk(root) { v ->
                if (v is ImageView && v.getTag(R.id.gsaux_zxcopy_patched) == true) {
                    runCatching { restoreOne(v) }
                        .onFailure { GLog.w("$id：还原复制按钮失败", it) }
                    restored++
                }
                false
            }
        }
        synchronized(seenDecors) { seenDecors.clear() }
        if (restored > 0) GLog.i("$id：已还原 $restored 个复制按钮")
    }

    private fun restoreOne(v: ImageView) {
        val origDrawable = v.getTag(R.id.gsaux_zxcopy_orig_drawable) as? Drawable
        val origListener = v.getTag(R.id.gsaux_zxcopy_orig_listener) as? View.OnClickListener
        val origTint = v.getTag(R.id.gsaux_zxcopy_orig_tint) as? ColorStateList
        val origScale = v.getTag(R.id.gsaux_zxcopy_orig_scale) as? ImageView.ScaleType
        val origDesc = v.getTag(R.id.gsaux_zxcopy_orig_cd) as? CharSequence
        val origClickable = v.getTag(R.id.gsaux_zxcopy_orig_clickable) as? Boolean ?: true
        val origFocusable = v.getTag(R.id.gsaux_zxcopy_orig_focusable) as? Boolean ?: v.isFocusable

        v.imageTintList = origTint
        if (origScale != null) v.scaleType = origScale
        v.setImageDrawable(origDrawable)
        v.contentDescription = origDesc
        v.setOnClickListener(origListener)
        v.isClickable = origClickable
        v.isFocusable = origFocusable

        clearTags(v)
    }

    private fun clearTags(v: View) {
        v.setTag(R.id.gsaux_zxcopy_patched, null)
        v.setTag(R.id.gsaux_zxcopy_orig_drawable, null)
        v.setTag(R.id.gsaux_zxcopy_orig_listener, null)
        v.setTag(R.id.gsaux_zxcopy_orig_tint, null)
        v.setTag(R.id.gsaux_zxcopy_orig_scale, null)
        v.setTag(R.id.gsaux_zxcopy_orig_cd, null)
        v.setTag(R.id.gsaux_zxcopy_orig_clickable, null)
        v.setTag(R.id.gsaux_zxcopy_orig_focusable, null)
        v.setTag(R.id.gsaux_zxcopy_ours_drawable, null)
        v.setTag(R.id.gsaux_zxcopy_ours_listener, null)
    }

    override fun onActivityDestroyed(activity: Activity) {
        super.onActivityDestroyed(activity)
        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return
        synchronized(seenDecors) { seenDecors.remove(root) }
    }

    // ------------------------------------------------------------------ 复制

    private fun copyCurrentGroup(activity: Activity) {
        val ids = ViewIds.idsFor(activity, TargetApp.PACKAGE)
        val snapshot = ZxGroupStocks.read(activity, ids)
        if (snapshot == null) {
            toast(activity, "复制失败：找不到自选列表")
            return
        }

        val text = ZxCopyPlan.format(snapshot.stocks)
        if (text.isEmpty()) {
            toast(activity, "当前分组没有股票")
            GLog.i("$id：当前分组为空，未写剪贴板")
            return
        }

        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            toast(activity, "复制失败：剪贴板不可用")
            GLog.w("$id：ClipboardManager 不可用")
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText(ZxGroupCopyTargets.CLIP_LABEL, text))

        val name = snapshot.groupName
        val label = if (name.isNullOrBlank()) "" else "「$name」"
        toast(activity, "已复制$label${snapshot.stocks.size} 只股票")
        // 规则 3.1：日志不写完整代码，只记数量
        GLog.i("$id：已复制${label}${snapshot.stocks.size} 只股票（${text.length} 字符）")
    }

    private fun toast(activity: Activity, msg: String) {
        runCatching { Toast.makeText(activity.applicationContext, msg, Toast.LENGTH_SHORT).show() }
            .onFailure { GLog.w("$id：Toast 失败", it) }
    }

    /** 从 View 的 Context 链里找 Activity（复制按钮不捕获页面，避免泄漏） */
    private fun activityOf(context: Context?): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
