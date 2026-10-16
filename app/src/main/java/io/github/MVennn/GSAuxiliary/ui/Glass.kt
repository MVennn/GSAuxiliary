package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import kotlin.math.abs

/**
 * 液态玻璃材质 —— 顶栏与悬浮底栏共用的「一套玻璃」。
 *
 * 2.7.0 起「液态玻璃」是一个**总开关**（`ConfigKeys.LIQUID_GLASS`）：
 * 开着的时候顶栏和底栏都是玻璃，关掉就都变回纯色。
 * 玻璃的**面色**（偏黑 / 偏白）、**光效**（模糊 / 折射 / 投影）也由同一套参数决定，
 * 这样两处表面永远是一套材质，不会各调各的。
 */

/**
 * 顶栏（`SmallTopAppBar`）的高度 —— 由 `Scaffold` 通过 `PaddingValues` 给出。
 *
 * 玻璃模式下内容要**铺到顶栏下面**（否则玻璃背后什么都没有，采样出来只是一块平色），
 * 所以内容不能再整体下移，而是由各 Tab 在滚动内容开头补这一段空白（本值），
 * 让「首屏从顶栏下方开始、滚上去的内容从玻璃后面穿过」两件事同时成立。
 */
internal val LocalTopInset = compositionLocalOf { 0.dp }

/** 当前是否深色配色（由 `AppTheme` 提供，避免各处重复订阅主题配置） */
internal val LocalDarkTheme = compositionLocalOf { false }

/**
 * 玻璃的「面色」—— 决定这块玻璃偏黑还是偏白。
 *
 * 需求是「黑色模式下是黑色液态玻璃，白色模式下是带白色的液态玻璃」，
 * 所以直接用**纯黑 / 纯白 + 透明度**，而不是主题里的 `surfaceContainerHigh`：
 * 后者本身偏灰，叠在模糊后的内容上会显得发脏，出不来干净通透的观感。
 *
 * @param effect **玻璃光效**开关。
 *   - `true`（完整版）：有模糊兜底，面色给 [GlassAlphaFull] 的薄薄一层，
 *     通透感靠模糊；面色只负责补一点颜色倾向。
 *   - `false`（简约版）：**没有模糊兜底，所以一律给不透明的纯色**。
 *     以前这里用 0.70 的半透明，结果底下的文字、图标直接透上来跟当前内容糊在一起，
 *     字都看不清 —— 既然不做模糊，就不该再叫「玻璃」，老老实实当纯色栏。
 */
internal fun glassSurfaceTint(dark: Boolean, effect: Boolean): Color {
    if (!effect) return if (dark) Color.Black else Color.White
    return (if (dark) Color.Black else Color.White).copy(alpha = GlassAlphaFull)
}

/**
 * 完整版（有模糊）的面色透明度。
 *
 * 2.9.0 从 0.52 降到 0.30：用户反馈「玻璃不够通透」。面色只是给模糊结果**补一点颜色倾向**，
 * 通透感主要由它决定 —— alpha 越高越像磨砂塑料，越低越像真玻璃。
 * 0.30 在深色下仍然能保证顶栏文字可读（底下还有一层模糊兜底）。
 */
private const val GlassAlphaFull = 0.30f

/** 顶栏玻璃的模糊半径 */
private val TopBarBlur = 24.dp

/**
 * 悬浮底栏的「滑动自动收起」状态。
 *
 * 向下滑（看后面的内容）→ 收起，避免挡住内容；向上滑（回看）→ 放出。
 *
 * ## 判定为什么要累计
 *
 * 单次滚动事件的 delta 只有几像素，直接按符号判定会让底栏在手指轻微抖动时反复闪。
 * 这里按**连续同向**的滚动量累计：方向一变就清零重新计，
 * 累计超过阈值才真正翻转状态 —— 手感上就是「滑一下就走，抖一下不动」。
 */
internal class DockScrollState {

    /** 累计的同向滚动量（像素） */
    private var run = 0f

    /** 上一次的滚动方向：true = 向下滑 */
    private var lastDown = false

    /** 底栏当前是否收起 */
    var hidden by mutableStateOf(false)
        private set

    /**
     * @param deltaY 嵌套滚动给出的纵向增量。
     *   **约定：负值 = 向下滑（内容上移、看后面的内容），正值 = 向上滑。**
     *   这一约定与 Material3 / Miuix 的 `ExitUntilCollapsedScrollBehavior` 一致
     *   （它把 `consumed.y` 直接加到 `heightOffset` 上，而 `heightOffset` 变负就是折叠）。
     * @param thresholdPx 翻转前需要累计的同向滚动量
     */
    fun onScroll(deltaY: Float, thresholdPx: Float) {
        if (deltaY == 0f) return
        val down = deltaY < 0f
        if (down != lastDown) {
            lastDown = down
            run = 0f
        }
        run += abs(deltaY)
        if (run < thresholdPx) return
        hidden = down
    }

    /** 回到「不收起」的初始状态（切板块、关掉玻璃时调用） */
    fun reset() {
        hidden = false
        run = 0f
    }
}

@Composable
internal fun rememberDockScrollState(): DockScrollState = remember { DockScrollState() }

/**
 * 把滚动事件喂给 [state] 的嵌套滚动连接。
 *
 * 装在**内容容器**上（不是滚动组件本身）：子级滚动组件把未消费的增量向上派发，
 * 这里只看方向、**不消费**（返回 `Offset.Zero`），所以完全不影响滚动本身。
 */
internal fun dockScrollConnection(
    state: DockScrollState,
    thresholdPx: Float,
): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        state.onScroll(available.y, thresholdPx)
        return Offset.Zero
    }
}

/**
 * 液态玻璃顶栏 —— 代替纯色的 `SmallTopAppBar`。
 *
 * 结构：一层铺满的玻璃（`drawBackdrop`）+ 一层**底色透明**的 `SmallTopAppBar`，
 * 下方可以再挂一段 [extra]（功能页的搜索框就走这里）。
 * 之所以要叠而不是给 `SmallTopAppBar` 传个颜色了事，是因为玻璃要把**身后的内容**
 * 采样回来做模糊 —— 只有 `drawBackdrop` 能读 [LayerBackdrop]。
 *
 * ## 刻意不做任何「边框」
 *
 * 顶栏**不画底部发丝线、也不要 `Highlight` 描边**，就是单纯一块玻璃
 * （模糊 + 增艳 + 半透明面色）。整条贴边、下面紧接滚动内容，
 * 任何描边都会变成一条突兀的横线。玻璃与内容的分界交给面色本身的对比度。
 *
 * `SmallTopAppBar` 的 `defaultWindowInsetsPadding` 默认开启，所以它的高度里
 * 已经含了状态栏；`matchParentSize` 的玻璃会一并盖住状态栏那条，视觉上是一整块。
 *
 * @param effect 是否做模糊。关闭时只留半透明面色（简约版），
 *               与底栏的「玻璃光效」开关是同一个值。
 * @param extra  顶栏下方的附加内容（如功能页的搜索框），会被同一块玻璃盖住。
 */
@Composable
internal fun GlassTopBar(
    backdrop: LayerBackdrop,
    effect: Boolean,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val dark = LocalDarkTheme.current
    val tint = glassSurfaceTint(dark, effect)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = {
                        if (effect) {
                            vibrancy()
                            blur(TopBarBlur.toPx())
                        }
                    },
                    // 不要描边 / 高光：顶栏是贴边的一整块，任何描边都会变成横线
                    highlight = null,
                    onDrawSurface = { drawRect(tint) },
                ),
        )
        Column {
            SmallTopAppBar(
                title = title,
                subtitle = subtitle,
                color = Color.Transparent,
            )
            extra()
        }
    }
}
