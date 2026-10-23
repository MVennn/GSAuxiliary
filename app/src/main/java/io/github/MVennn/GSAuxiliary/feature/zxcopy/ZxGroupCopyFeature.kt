package io.github.MVennn.GSAuxiliary.feature.zxcopy

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.core.ConfigSnapshot
import io.github.MVennn.GSAuxiliary.core.GLog
import io.github.MVennn.GSAuxiliary.core.TargetApp
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyFormat
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyImageRenderer
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyPdfRenderer
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyRuleStore
import io.github.MVennn.GSAuxiliary.feature.FeatureEnv
import io.github.MVennn.GSAuxiliary.feature.PageSyncFeature
import io.github.MVennn.GSAuxiliary.feature.uiclean.UiClean
import io.github.MVennn.GSAuxiliary.util.Reflect
import io.github.MVennn.GSAuxiliary.util.ViewIds
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 功能：**自选页右上角的「编辑」按钮换成「复制」按钮**。
 *
 * 短按按默认规则复制当前分组的股票；长按（[ConfigSnapshot.zxCopyMenu] 开启时）
 * 弹动作菜单：复制 / 按其它规则复制 / 分享文本 / 导出图片 / 导出 PDF。
 *
 * ## 认页与认按钮（真机 2026-10-23 实测）
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
 * 原图 / 原点击监听 / 原长按监听 / 原 tint / 原 scaleType / 原 contentDescription /
 * 原 clickable / 原 longClickable 全部挂在 ImageView 的 keyed tag 上
 * （`R.id.gsaux_zxcopy_*`），不存 Feature 实例 —— 热重载后的新实例能按 tag 认出现场，
 * 关开关时也能把宿主按钮原样还回去。
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

    /** 最近一次配置（主线程写，点击读；用于短按默认动作与长按菜单开关） */
    @Volatile private var cfg: ConfigSnapshot = ConfigSnapshot.DEFAULT

    /** 自绘复制图标（按尺寸缓存；同一进程只有自选页一颗按钮用到） */
    @Volatile private var iconCache: Drawable? = null
    @Volatile private var iconSize = 0

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 导出放单线程；页面销毁也不影响已经开始的落盘 */
    private val exporter = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gsaux-zxcopy-export").apply { isDaemon = true }
    }

    // ------------------------------------------------------------------ 点击 / 长按

    /** 短按：按默认规则的 `action` 执行（默认是复制） */
    private val onClick = View.OnClickListener { v ->
        val act = activityOf(v.context)
        if (act == null) {
            GLog.w("$id：复制按钮的 Context 不是 Activity，忽略本次点击")
            return@OnClickListener
        }
        runCatching { performDefault(act) }
            .onFailure {
                GLog.w("$id：短按处理失败", it)
                toast(act, "操作失败")
            }
    }

    /** 长按：弹动作菜单（菜单开关关闭时不会挂到这个监听上） */
    private val onLongClick = View.OnLongClickListener { v ->
        if (!cfg.zxCopyMenu) return@OnLongClickListener false
        val act = activityOf(v.context) ?: return@OnLongClickListener false
        val data = ZxCopyRuleStore.decode(cfg.zxCopyRules)
        val rules = data.rules
        if (rules.isEmpty()) return@OnLongClickListener false
        runCatching {
            ZxCopyMenu(v.context).show(v, rules, data.defaultId) { action ->
                val rule = data.defaultRule() ?: return@show
                runCatching {
                    when (action) {
                        is ZxCopyMenu.Action.Copy -> {
                            val picked = rules.firstOrNull { it.id == action.ruleId } ?: rule
                            performCopy(act, picked)
                        }
                        ZxCopyMenu.Action.ShareText -> performShareText(act, rule)
                        ZxCopyMenu.Action.Image -> performImage(act, rule)
                        ZxCopyMenu.Action.Pdf -> performPdf(act, rule)
                    }
                }.onFailure {
                    GLog.w("$id：菜单动作失败", it)
                    toast(act, "操作失败")
                }
            }
        }.onFailure {
            GLog.w("$id：弹出动作菜单失败", it)
            toast(act, "菜单打开失败")
        }
        true
    }

    // ------------------------------------------------------------------ 同步

    override fun sync(env: FeatureEnv, activity: Activity): Boolean {
        cfg = env.config
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

        patch(pencil, env.config)
        return true
    }

    override fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {
        this.cfg = cfg
        super.onConfigChanged(env, cfg)
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
    private fun patch(pencil: ImageView, config: ConfigSnapshot) {
        val patched = pencil.getTag(R.id.gsaux_zxcopy_patched) == true
        val oursDrawable = pencil.getTag(R.id.gsaux_zxcopy_ours_drawable) as? Drawable
        val oursListener = pencil.getTag(R.id.gsaux_zxcopy_ours_listener) as? View.OnClickListener
        val oursLong = pencil.getTag(R.id.gsaux_zxcopy_ours_long_listener) as? View.OnLongClickListener

        val menuOk = if (config.zxCopyMenu) {
            oursLong === onLongClick && pencil.isLongClickable
        } else {
            oursLong == null
        }

        if (patched && oursDrawable != null && oursListener === onClick && menuOk &&
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

        // 长按：菜单开关打开时挂我们的监听；关掉时还原宿主原来的长按行为
        if (config.zxCopyMenu) {
            if (oursLong !== onLongClick) {
                val currentLong = currentLongClickListener(pencil)
                if (currentLong !== oursLong) {
                    pencil.setTag(R.id.gsaux_zxcopy_orig_long_listener, currentLong)
                    pencil.setTag(R.id.gsaux_zxcopy_orig_long_clickable, pencil.isLongClickable)
                }
                pencil.setOnLongClickListener(onLongClick)
                pencil.isLongClickable = true
                pencil.setTag(R.id.gsaux_zxcopy_ours_long_listener, onLongClick)
                changed = true
            }
        } else if (oursLong != null) {
            val origLong = pencil.getTag(R.id.gsaux_zxcopy_orig_long_listener) as? View.OnLongClickListener
            val origLongClickable = pencil.getTag(R.id.gsaux_zxcopy_orig_long_clickable) as? Boolean
            pencil.setOnLongClickListener(origLong)
            if (origLongClickable != null) pencil.isLongClickable = origLongClickable
            pencil.setTag(R.id.gsaux_zxcopy_ours_long_listener, null)
            pencil.setTag(R.id.gsaux_zxcopy_orig_long_listener, null)
            pencil.setTag(R.id.gsaux_zxcopy_orig_long_clickable, null)
            changed = true
        }

        pencil.setTag(R.id.gsaux_zxcopy_ours_drawable, icon)
        pencil.setTag(R.id.gsaux_zxcopy_ours_listener, onClick)
        pencil.setTag(R.id.gsaux_zxcopy_patched, true)
        if (changed) {
            noteChange()
            if (GLog.isInfo) GLog.i("$id：已把自选页的编辑按钮换成复制按钮（长按菜单=${config.zxCopyMenu}）")
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

    private fun currentLongClickListener(v: View): View.OnLongClickListener? = runCatching {
        val infoField = Reflect.findField(v.javaClass, "mListenerInfo") ?: return null
        val info = infoField.get(v) ?: return null
        val listenerField = Reflect.findField(info.javaClass, "mOnLongClickListener") ?: return null
        listenerField.get(info) as? View.OnLongClickListener
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
        val origLong = v.getTag(R.id.gsaux_zxcopy_orig_long_listener) as? View.OnLongClickListener
        val origLongClickable = v.getTag(R.id.gsaux_zxcopy_orig_long_clickable) as? Boolean
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
        v.setOnLongClickListener(origLong)
        v.isClickable = origClickable
        v.isFocusable = origFocusable
        if (origLongClickable != null) v.isLongClickable = origLongClickable

        clearTags(v)
    }

    private fun clearTags(v: View) {
        v.setTag(R.id.gsaux_zxcopy_patched, null)
        v.setTag(R.id.gsaux_zxcopy_orig_drawable, null)
        v.setTag(R.id.gsaux_zxcopy_orig_listener, null)
        v.setTag(R.id.gsaux_zxcopy_orig_long_listener, null)
        v.setTag(R.id.gsaux_zxcopy_orig_long_clickable, null)
        v.setTag(R.id.gsaux_zxcopy_orig_tint, null)
        v.setTag(R.id.gsaux_zxcopy_orig_scale, null)
        v.setTag(R.id.gsaux_zxcopy_orig_cd, null)
        v.setTag(R.id.gsaux_zxcopy_orig_clickable, null)
        v.setTag(R.id.gsaux_zxcopy_orig_focusable, null)
        v.setTag(R.id.gsaux_zxcopy_ours_drawable, null)
        v.setTag(R.id.gsaux_zxcopy_ours_listener, null)
        v.setTag(R.id.gsaux_zxcopy_ours_long_listener, null)
    }

    override fun onActivityDestroyed(activity: Activity) {
        super.onActivityDestroyed(activity)
        val root = runCatching { activity.window?.decorView }.getOrNull() ?: return
        synchronized(seenDecors) { seenDecors.remove(root) }
    }

    // ------------------------------------------------------------------ 动作

    private fun performDefault(activity: Activity) {
        val data = ZxCopyRuleStore.decode(cfg.zxCopyRules)
        val rule = data.defaultRule() ?: ZxCopyRuleStore.defaultData().defaultRule() ?: return
        when (rule.action) {
            ZxCopyRuleStore.ACTION_SHARE_TEXT -> performShareText(activity, rule)
            ZxCopyRuleStore.ACTION_IMAGE -> performImage(activity, rule)
            ZxCopyRuleStore.ACTION_PDF -> performPdf(activity, rule)
            else -> performCopy(activity, rule)
        }
    }

    private fun performCopy(activity: Activity, rule: ZxCopyRuleStore.Rule) {
        val snap = readSnapshot(activity) ?: return
        if (snap.rows.isEmpty()) {
            toast(activity, "当前分组没有股票")
            return
        }
        val text = ZxCopyFormat.render(snap.rows, rule, metaOf(snap))
        if (text.isEmpty()) {
            toast(activity, "当前分组没有股票")
            return
        }
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            toast(activity, "复制失败：剪贴板不可用")
            GLog.w("$id：ClipboardManager 不可用")
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText(ZxGroupCopyTargets.CLIP_LABEL, text))
        toast(activity, "已复制「${snap.groupName ?: "当前分组"}」${snap.rows.size} 只股票")
        GLog.i("$id：已复制「${snap.groupName ?: "?"}」${snap.rows.size} 只（格式=${rule.format}，字段=${rule.fields.size}）")
    }

    private fun performShareText(activity: Activity, rule: ZxCopyRuleStore.Rule) {
        val snap = readSnapshot(activity) ?: return
        if (snap.rows.isEmpty()) {
            toast(activity, "当前分组没有股票")
            return
        }
        val text = ZxCopyFormat.render(snap.rows, rule, metaOf(snap))
        if (text.isEmpty()) {
            toast(activity, "当前分组没有股票")
            return
        }
        if (!ZxCopyShare.shareText(activity, text)) toast(activity, "分享失败")
    }

    private fun performImage(activity: Activity, rule: ZxCopyRuleStore.Rule) {
        val snap = readSnapshot(activity) ?: return
        if (snap.rows.isEmpty()) {
            toast(activity, "当前分组没有股票")
            return
        }
        val meta = metaOf(snap)
        val metrics = DisplayMetrics().also { it.setTo(activity.resources.displayMetrics) }
        val app = activity.applicationContext
        val actRef = WeakReference(activity)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = ZxCopyExportStorage.safeName("自选分组_${snap.groupName ?: "全部"}_$stamp")
        toast(activity, "正在导出图片…")
        exporter.execute {
            val renderer = ZxCopyImageRenderer(snap.rows, rule, meta, metrics)
            val uris = ArrayList<Uri>()
            var saved = 0
            for (i in 0 until renderer.pageCount) {
                val bmp = runCatching { renderer.renderPage(i) }.getOrNull() ?: continue
                val name = "${base}_${i + 1}.png"
                val one = ZxCopyExportStorage.saveImage(app, bmp, name)
                bmp.recycle()
                if (one != null) {
                    saved++
                    one.uri?.let { uris.add(it) }
                }
            }
            mainHandler.post {
                val act = actRef.get()
                if (act == null || act.isFinishing || act.isDestroyed) {
                    GLog.i("$id：图片已导出 $saved 张，页面已销毁，跳过提示/分享")
                    return@post
                }
                if (saved <= 0) {
                    toast(act, "导出图片失败")
                    return@post
                }
                toast(act, "已导出 $saved 张图片")
                if (rule.shareAfterExport) {
                    if (uris.isEmpty()) {
                        toast(act, "当前系统不支持直接分享，已保存到 App 目录")
                    } else if (!ZxCopyShare.shareImages(act, uris)) {
                        toast(act, "分享失败")
                    }
                }
            }
        }
    }

    private fun performPdf(activity: Activity, rule: ZxCopyRuleStore.Rule) {
        val snap = readSnapshot(activity) ?: return
        if (snap.rows.isEmpty()) {
            toast(activity, "当前分组没有股票")
            return
        }
        val meta = metaOf(snap)
        val app = activity.applicationContext
        val actRef = WeakReference(activity)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = ZxCopyExportStorage.safeName("自选分组_${snap.groupName ?: "全部"}_$stamp") + ".pdf"
        toast(activity, "正在导出 PDF…")
        exporter.execute {
            val bytes = runCatching { ZxCopyPdfRenderer(snap.rows, rule, meta).render() }.getOrNull()
            val saved = bytes?.let { ZxCopyExportStorage.savePdf(app, it, name) }
            mainHandler.post {
                val act = actRef.get()
                if (act == null || act.isFinishing || act.isDestroyed) {
                    GLog.i("$id：PDF 已导出，页面已销毁，跳过提示/分享")
                    return@post
                }
                if (saved == null) {
                    toast(act, "导出 PDF 失败")
                    return@post
                }
                toast(act, "已导出 PDF")
                if (rule.shareAfterExport) {
                    if (saved.uri == null) {
                        toast(act, "当前系统不支持直接分享，已保存到 ${saved.path}")
                    } else if (!ZxCopyShare.sharePdf(act, saved.uri)) {
                        toast(act, "分享失败")
                    }
                }
            }
        }
    }

    private fun readSnapshot(activity: Activity): ZxGroupStocks.Snapshot? {
        val ids = ViewIds.idsFor(activity, TargetApp.PACKAGE)
        val snap = ZxGroupStocks.read(activity, ids)
        if (snap == null) toast(activity, "复制失败：找不到自选列表")
        return snap
    }

    private fun metaOf(snap: ZxGroupStocks.Snapshot): ZxCopyFormat.Meta =
        ZxCopyFormat.Meta(
            groupName = snap.groupName,
            timestamp = snap.timestamp,
            count = snap.rows.size,
            title = "自选分组",
        )

    private fun toast(activity: Activity, msg: String) {
        runCatching { Toast.makeText(activity.applicationContext, msg, Toast.LENGTH_SHORT).show() }
            .onFailure { GLog.w("$id：Toast 失败", it) }
    }

    /** 从 View 的 Context 链里找 Activity（监听不捕获页面，避免泄漏） */
    private fun activityOf(context: Context?): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
