package io.github.SansKritO4.GSAuxiliary.feature.navbar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import io.github.SansKritO4.GSAuxiliary.core.GLog
import io.github.SansKritO4.GSAuxiliary.feature.uiclean.UiClean
import kotlin.math.abs
import kotlin.math.max

/**
 * 「导航条取色 + 上色」的纯工具层。
 *
 * 只依赖 Android 框架，**不碰任何钩子、不持任何状态** ——
 * 全部的调度与记账在 `NavBarBlendFeature` 里。
 *
 * ## 三件事
 *
 *  1. **量出取色带在哪**（[bandOf]）：`decor.height - navBarHeight` 那条线。
 *     这一条是整个功能的命门 —— 取错位置就会取到自己填进去的颜色（见
 *     `NavBarTargets` 类注释第二节）。
 *  2. **取色**（[structural] / [sampleRow]）：结构合成为主、真实像素为辅。
 *  3. **上色**（[paint]）：写 `navigationBarColor` + 关强制对比度 +
 *     按亮度决定导航条上那条小横杠画深色还是浅色。
 */
object NavBarTint {

    /** 取色带：[top, bottom) 在 `decor` 坐标系里的一段 */
    data class Band(val top: Int, val bottom: Int) {
        val valid: Boolean get() = bottom > top
    }

    // ---------------------------------------------------------------- 量位置

    /**
     * 导航条高度（px）。
     *
     * 取不到（还没 attach / 全屏隐藏了导航条）返回 0 —— 此时取色带就贴在
     * `decor` 的底边上，仍然是对的。
     */
    @Suppress("DEPRECATION")
    fun navBarHeight(decor: View): Int {
        val ins = runCatching { decor.rootWindowInsets }.getOrNull() ?: return 0
        return if (Build.VERSION.SDK_INT >= 30) {
            runCatching { ins.getInsets(WindowInsets.Type.navigationBars()).bottom }.getOrDefault(0)
        } else {
            runCatching { ins.stableInsetBottom }.getOrDefault(0)
        }
    }

    /** [v] 所在屏幕密度下的 dp → px */
    fun dp(v: View, value: Int): Int =
        (value * v.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 内容最后那一行的取色带（见 `NavBarTargets` 类注释第二节的图）。
     *
     * **下沿必须落在导航条顶边上**，取错就会取到自己填进去的颜色（自我循环）。
     * 两条路互为兜底：
     *
     *  1. `decor.height - navBarHeight`（[navBarHeight] 有值时最准）；
     *  2. 内容根 `android.R.id.content` 的底边 —— 非沉浸式窗口里它正好是导航条顶边。
     *
     * 两条都拿不到（还没 attach / 导航条被隐藏）才退到 `decor` 的底边，
     * 那时内容本来就是铺满的，取最底下一行仍然对。
     */
    fun bandOf(decor: View): Band {
        val h = decor.height
        if (h <= 0) return Band(0, 0)
        val navH = navBarHeight(decor)
        val contentBottom = runCatching {
            decor.findViewById<View>(android.R.id.content)?.bottom
        }.getOrNull()
        val bottom = if (navH > 0) {
            h - navH
        } else if (contentBottom != null && contentBottom > 0 && contentBottom < h) {
            contentBottom
        } else {
            h
        }
        val safeBottom = bottom.coerceAtLeast(1).coerceAtMost(h)
        val top = (safeBottom - dp(decor, NavBarTargets.BAND_DP)).coerceAtLeast(0)
        return Band(top, safeBottom)
    }

    // ---------------------------------------------------------------- 取色 ① 结构合成

    /**
     * 结构合成取色：**同步**、零分配、首帧就能给结果。
     *
     * 做法是从 `decor` 起**逐层下钻**，每一层只挑「覆盖取色带、且宽度够」的
     * 那个子视图（[coveringChild]），直到没有更合适的为止。得到的是一条
     * `decor → … → 最底下那一层` 的链，然后从外到里把每层的背景色按 alpha 合成。
     *
     * 为什么是「最宽的那一层」而不是「最深的那一层」：底栏一行六格并列，
     * 每格只占 1/6 宽，而且可能带自己的选中态底色 —— 往深钻会取到某一格的
     * 颜色。**代表「周边界面」的是整行**，所以宽度低于 [NavBarTargets.MIN_COVER_RATIO]
     * 的子视图一律不再下钻。
     *
     * @param base 合成底色（主题的 `windowBackground`，见 [windowBackground]）
     * @return 不透明的 ARGB；合成完仍半透明（整条链都没给实心背景）返回 null
     */
    fun structural(decor: View, base: Int): Int? {
        val band = bandOf(decor)
        if (!band.valid) return null
        var acc = base
        var cur: View = decor
        var guard = 0
        while (guard++ < NavBarTargets.MAX_CHAIN_DEPTH) {
            val bg = cur.background
            if (bg != null) {
                val c = drawableColor(bg)
                if (Color.alpha(c) > 0) acc = over(c, acc)
            }
            val next = coveringChild(decor, cur, band) ?: break
            cur = next
        }
        return if (Color.alpha(acc) >= NavBarTargets.MIN_ALPHA) acc else null
    }

    /** [cur] 的子视图里「覆盖取色带且最宽」的那一个；没有返回 null */
    private fun coveringChild(decor: View, cur: View, band: Band): View? {
        val g = cur as? ViewGroup ?: return null
        val self = UiClean.rectIn(decor, cur)?.width() ?: cur.width
        val minW = (self * NavBarTargets.MIN_COVER_RATIO).toInt()
        var best: View? = null
        var bestW = 0
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (!UiClean.visibleOnScreen(c)) continue
            val r = UiClean.rectIn(decor, c) ?: continue
            if (r.bottom <= band.top || r.top >= band.bottom) continue
            val w = r.width()
            if (w >= minW && w > bestW) {
                bestW = w
                best = c
            }
        }
        return best
    }

    // ---------------------------------------------------------------- 取色 ② 像素校准

    /**
     * `PixelCopy` 拷下的那一行（已缩放成 `SAMPLE_W × 1`）取平均色。
     *
     * 全透明的像素不参与平均 —— 取色带压在透明区域上时宁愿少几个样本，
     * 也不要把窗口底色算进来。样本全透明返回 [Color.TRANSPARENT]，调用方自行忽略。
     */
    fun average(bmp: Bitmap): Int {
        val n = bmp.width * bmp.height
        if (n <= 0) return Color.TRANSPARENT
        val px = IntArray(n)
        runCatching { bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            .onFailure { return Color.TRANSPARENT }
        var r = 0L
        var g = 0L
        var b = 0L
        var cnt = 0
        for (v in px) {
            if (Color.alpha(v) == 0) continue
            r += Color.red(v)
            g += Color.green(v)
            b += Color.blue(v)
            cnt++
        }
        if (cnt == 0) return Color.TRANSPARENT
        return 0xFF000000.toInt() or
            ((r / cnt).toInt() shl 16) or
            ((g / cnt).toInt() shl 8) or
            (b / cnt).toInt()
    }

    /** 两个颜色的最大通道差（0–255） */
    fun distance(a: Int, b: Int): Int = max(
        abs(Color.red(a) - Color.red(b)),
        max(abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b))),
    )

    // ---------------------------------------------------------------- 取色 ③ 兜底

    /**
     * 主题的 `android:windowBackground` 对应的颜色。
     *
     * 它是合成链的**底色**：链上任何一层给了实心背景就覆盖它，
     * 一层都没有时它至少保证结果是「宿主页面的底色」而不是黑 / 白硬块。
     *
     * 解析不出（主题没声明、声明的是渐变 / 图片且渲染失败）返回 null。
     */
    fun windowBackground(activity: Activity): Int? {
        val tv = TypedValue()
        val ok = runCatching {
            activity.theme.resolveAttribute(android.R.attr.windowBackground, tv, true)
        }.getOrDefault(false)
        if (!ok) return null
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data or 0xFF000000.toInt()
        }
        if (tv.resourceId == 0) return null
        val d = runCatching { activity.resources.getDrawable(tv.resourceId, activity.theme) }
            .getOrNull() ?: return null
        val c = drawableColor(d)
        return if (Color.alpha(c) >= NavBarTargets.MIN_ALPHA) c else null
    }

    // ---------------------------------------------------------------- 上色

    /**
     * 把 [color] 写到导航条那一块上。
     *
     * 三件事缺一不可：
     *
     *  1. `navigationBarColor` —— 那一块是 `DecorView` 按这个值自己填的；
     *  2. **关掉强制对比度**（API 29+）—— 它是「小白条上蒙一层灰」的元凶：
     *     系统为了保证金太阳的小横杠一定看得见，会在透明导航条上叠一层
     *     半透明遮罩。本模块自己的设置界面在 2.5.0 就踩过同一个坑
     *     （见 `docs/UI.md` §11.1）；
     *  3. 按亮度设横杠明暗 —— 关掉遮罩之后，横杠画深色还是浅色就完全由
     *     `appearanceLightNavigationBars` 决定了，不设就会出现
     *     「白横杠压在白底上」这种看不见的情况。
     *
     * @param keepContrast true = 保留系统的强制对比度（三键导航时用，见 [gestureNav]）
     */
    fun paint(activity: Activity, color: Int, keepContrast: Boolean) {
        val w = activity.window ?: return
        runCatching {
            if (w.navigationBarColor != color) w.navigationBarColor = color
        }.onFailure { t ->
            GLog.w("nav_bar_blend：写 navigationBarColor 失败", t)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { w.isNavigationBarContrastEnforced = keepContrast }
                .onFailure { t ->
                    GLog.d("nav_bar_blend：改 navigationBarContrastEnforced 失败（$t）")
                }
        }
        setLightNavBar(activity, color)
    }

    /**
     * 按填进去的颜色决定导航条上那条小横杠画深色还是浅色。
     *
     * 判据是**填进去的颜色**的亮度，不是系统深色开关 —— 用户可能把金太阳
     * 强制成深色而系统是浅色（本模块设置界面踩过同款问题，见 `docs/UI.md` §11.3）。
     */
    fun setLightNavBar(activity: Activity, color: Int) {
        setNavBarLight(activity, Color.luminance(color) >= NavBarTargets.LIGHT_LUMINANCE)
    }

    /**
     * 直接设「导航条是浅底」这一位。
     *
     * API 30+ 走 `WindowInsetsController`；26–29 走 `SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR`
     * （只动这一位，宿主自己的其它 flag 原样保留）；26 以下系统本身就没有这个概念。
     */
    fun setNavBarLight(activity: Activity, light: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val ic = runCatching { activity.window?.insetsController }.getOrNull() ?: return
            runCatching {
                ic.setSystemBarsAppearance(
                    if (light) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                )
            }
        } else if (Build.VERSION.SDK_INT >= 26) {
            val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return
            val flag = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            runCatching {
                decor.systemUiVisibility =
                    (decor.systemUiVisibility and flag.inv()) or (if (light) flag else 0)
            }
        }
    }

    /** 当前「导航条是浅底」这一位的值；取不到返回 null（关开关还原时就不动它） */
    fun navBarLightNow(activity: Activity): Boolean? {
        if (Build.VERSION.SDK_INT >= 30) {
            val ic = runCatching { activity.window?.insetsController }.getOrNull() ?: return null
            val app = runCatching { ic.systemBarsAppearance }.getOrNull() ?: return null
            return app and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val decor = runCatching { activity.window?.decorView }.getOrNull() ?: return null
            return decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0
        }
        return null
    }

    /**
     * 当前是不是**手势导航**（而不是三键）。
     *
     * 用系统资源 `android:integer/config_navBarInteractionMode`（AOSP 定义：
     * 0 = 三键、1 = 两键、2 = 手势）。取不到就按手势处理。
     *
     * 为什么要区分：**三键模式下那一条是真系统栏、上面有按键**，
     * 关掉强制对比度可能让按键看不清；所以三键时保留系统的强制对比度，
     * 只改颜色。手势模式下只有一条小横杠，才是彻底融入的场景。
     */
    fun gestureNav(activity: Activity): Boolean {
        if (navMode < 0) {
            val id = runCatching {
                activity.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
            }.getOrDefault(0)
            navMode = if (id == 0) 2 else runCatching { activity.resources.getInteger(id) }.getOrDefault(2)
        }
        return navMode == 2
    }

    /** 交互模式缓存（进程内一次就够；-1 = 还没查过） */
    @Volatile
    private var navMode = -1

    // ---------------------------------------------------------------- 颜色小工具

    /**
     * 一个 `Drawable` 「大概是什么颜色」。
     *
     * [ColorDrawable] 直接取（精确）；其余一律**渲染成 1×1 位图**再读那一像素 ——
     * 这样 `GradientDrawable` / `ShapeDrawable` / `LayerDrawable` / `StateListDrawable`
     * / 九宫格都能拿到一个说得过去的值，不用去反射各个版本里名字还不一样的私有字段。
     *
     * 取不到（渲染失败 / 真的是透明的）返回 [Color.TRANSPARENT]，
     * 调用方按「这一层没有给颜色」处理。
     */
    fun drawableColor(d: Drawable): Int {
        if (d is ColorDrawable) return d.color
        val old = Rect(d.bounds)
        return runCatching {
            scratch.eraseColor(Color.TRANSPARENT)
            d.setBounds(0, 0, NavBarTargets.RENDER_EDGE_PX, NavBarTargets.RENDER_EDGE_PX)
            d.draw(scratchCanvas)
            scratch.getPixel(0, 0)
        }.getOrDefault(Color.TRANSPARENT).also {
            runCatching { d.bounds = old }
        }
    }

    /** 源色叠加在目标色之上（标准 source-over，返回的颜色带正确 alpha） */
    fun over(fg: Int, bg: Int): Int {
        val af = Color.alpha(fg)
        if (af >= 255) return fg
        if (af <= 0) return bg
        val ab = Color.alpha(bg)
        val wf = af / 255f
        val outA = af + ab * (1f - wf)
        if (outA <= 0f) return 0
        fun mix(f: Int, b: Int): Int =
            ((f * af + b * ab * (1f - wf)) / outA).toInt().coerceIn(0, 255)
        return ((outA.toInt() and 0xFF) shl 24) or
            (mix(Color.red(fg), Color.red(bg)) shl 16) or
            (mix(Color.green(fg), Color.green(bg)) shl 8) or
            mix(Color.blue(fg), Color.blue(bg))
    }

    /** `#` + 6 位十六进制（日志用，别在热路径里调） */
    fun hex(c: Int): String =
        "#%02X%02X%02X".format(Color.red(c), Color.green(c), Color.blue(c))

    // ---------------------------------------------------------------- 私有

    /** 复用的 1×1 画板（只在主线程用，见 [drawableColor]） */
    private val scratch = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val scratchCanvas = Canvas(scratch)
}
