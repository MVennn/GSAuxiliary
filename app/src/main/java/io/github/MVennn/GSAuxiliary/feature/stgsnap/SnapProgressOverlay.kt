package io.github.MVennn.GSAuxiliary.feature.stgsnap

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.GLog

/**
 * 详情页顶部的截图进度条。
 *
 * 截图前必须 [hideForCapture] 并等一帧，否则进度条会被 PixelCopy 截进图里。
 * 用 `INVISIBLE` 而不是 `GONE`：不参与绘制、但不会让下面的页面重新布局。
 */
internal class SnapProgressOverlay(private val activity: Activity) {

    private val root: LinearLayout?
    private val progress: TextView?
    private val cancel: TextView?

    var onCancel: (() -> Unit)? = null

    init {
        val decor = activity.window?.decorView as? ViewGroup
        if (decor == null) {
            root = null
            progress = null
            cancel = null
            GLog.w("截图并添加：decorView 不是 ViewGroup，进度条不可用")
        } else {
            val dp = activity.resources.displayMetrics.density
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.argb(0xD8, 0, 0, 0))
                setPadding((16 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            }
            val p = TextView(activity).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = "准备截图…"
            }
            val c = TextView(activity).apply {
                text = "取消"
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding((14 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt())
                isClickable = true
                setOnClickListener { onCancel?.invoke() }
            }
            r.addView(
                p,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            r.addView(c, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
            lp.topMargin = statusBarHeight()
            decor.addView(r, lp)
            root = r
            progress = p
            cancel = c
        }
    }

    fun show() {
        root?.visibility = View.VISIBLE
    }

    fun update(done: Int, total: Int, name: String) {
        val safeName = name.ifEmpty { "…" }
        progress?.text = "正在截图 $done/$total · $safeName"
    }

    fun hideForCapture() {
        root?.visibility = View.INVISIBLE
    }

    fun restoreAfterCapture() {
        root?.visibility = View.VISIBLE
    }

    fun remove() {
        runCatching { (root?.parent as? ViewGroup)?.removeView(root) }
        runCatching { cancel?.setOnClickListener(null) }
    }

    private fun statusBarHeight(): Int {
        return try {
            val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id != 0) activity.resources.getDimensionPixelSize(id) else 0
        } catch (t: Throwable) {
            0
        }
    }
}
