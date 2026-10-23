package io.github.MVennn.GSAuxiliary.feature.zxcopy

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import io.github.MVennn.GSAuxiliary.core.zxcopy.ZxCopyRuleStore

/**
 * 复制按钮的长按动作菜单（**宿主进程内的 PopupWindow**）。
 *
 * 为什么不用模块 App 的 Compose 弹层：那个界面跑在模块自己的进程里，宿主进程
 * 拿不到 `DialogHost`；这里只能就地弹一个原生 PopupWindow。
 *
 * 样式刻意做得轻：白 / 深灰圆角卡片、无标题、行高 44dp、贴按钮右下角弹出。
 * 每一项都是一个普通 `TextView` + 系统 selectableItemBackground，点完先
 * `dismiss()` 再回调，避免弹层还开着就开始导出。
 */
internal class ZxCopyMenu(private val context: Context) {

    sealed interface Action {
        data class Copy(val ruleId: String) : Action
        data object ShareText : Action
        data object Image : Action
        data object Pdf : Action
    }

    fun show(
        anchor: View,
        rules: List<ZxCopyRuleStore.Rule>,
        defaultId: String,
        onAction: (Action) -> Unit,
    ) {
        if (rules.isEmpty() || !anchor.isAttachedToWindow) return

        val dark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (dark) 0xFF2A2A2A.toInt() else Color.WHITE
        val textColor = if (dark) 0xFFEDEDED.toInt() else 0xFF212634.toInt()
        val dividerColor = if (dark) 0xFF3A3A3A.toInt() else 0xFFE5E7EB.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(bgColor)
                // 白底卡片压在同样偏白的行情区上时，只靠 elevation 阴影几乎看不出来；
                // 加一圈 1px 描边，菜单边界才清楚。
                setStroke(dp(1), dividerColor)
            }
        }

        fun addItem(label: String, action: Action) {
            root.addView(
                TextView(context).apply {
                    text = label
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    isClickable = true
                    isFocusable = true
                    // `android.R.attr.selectableItemBackground` 是**属性 id**，不是 drawable
                    // 资源 id；直接 setBackgroundResource 会抛 Resources$NotFoundException
                    // （Resource ID #0x101030e）。先按主题解析成真实资源再设。
                    val tv = TypedValue()
                    if (context.theme.resolveAttribute(
                            android.R.attr.selectableItemBackground, tv, true,
                        ) && tv.resourceId != 0
                    ) {
                        setBackgroundResource(tv.resourceId)
                    }
                    setOnClickListener {
                        popupRef?.dismiss()
                        onAction(action)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        fun addDivider() {
            root.addView(
                View(context).apply { setBackgroundColor(dividerColor) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(1),
                ).apply { topMargin = dp(4); bottomMargin = dp(4) },
            )
        }

        val defaultRule = rules.firstOrNull { it.id == defaultId } ?: rules.first()
        addItem("复制（${defaultRule.name}）", Action.Copy(defaultRule.id))
        for (r in rules) {
            if (r.id == defaultRule.id) continue
            addItem("复制为「${r.name}」", Action.Copy(r.id))
        }
        addDivider()
        addItem("分享文本", Action.ShareText)
        addItem("导出图片", Action.Image)
        addItem("导出 PDF", Action.Pdf)

        val widthPx = dp(240)
        val popup = PopupWindow(root, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            setOnDismissListener { popupRef = null }
        }
        popupRef = popup
        // 右边缘与按钮右边缘对齐；y 让出一点，别贴着按钮底边
        popup.showAsDropDown(anchor, anchor.width - widthPx, dp(6))
    }

    @Volatile private var popupRef: PopupWindow? = null

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()
}
