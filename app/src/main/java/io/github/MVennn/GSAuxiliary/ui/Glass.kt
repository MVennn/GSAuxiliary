package io.github.MVennn.GSAuxiliary.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
// 默认 0.dp 是**有意的缺省**：没包这一层的页面等于「内容从屏幕顶边开始」，
// 不会崩，但首屏会压到顶栏下面。新增页面 / 新板块时忘了 `provides` 就是这种表现
// （不报错、只是第一屏挤）—— 所以模板里每一处 `LocalTopInset provides` 都别删。

/** 当前是否深色配色（由 `AppTheme` 提供，避免各处重复订阅主题配置） */
internal val LocalDarkTheme = compositionLocalOf { false }
// 同上：默认 false（浅色）。忘了包不会崩，但深色主题下这一片的配色会按浅色算。

/**
 * 玻璃档位 —— 正本定义见 docs/LIQUID-GLASS.md §3。
 *
 * 对齐 Apple Liquid Glass 的三档体系：完整版 = Clear（真玻璃）、
 * 简约版 = Regular（磨砂）、纯色 = Opaque（兜底）。
 */
internal enum class GlassTier {
    /** 完整版：折射 / rim light / 投影 / 内阴影 / 交互物理全开 */
    Full,

    /** 简约版：vibrancy + blur 与面色保留，「光」的部分（折射 / 描边 / 投影）全关 */
    Frost,

    /** 纯色兜底：不是档位，是系统能力不足（无 RenderEffect）时的降级 */
    Plain,
}

/**
 * 由用户开关 + 系统能力裁决档位：
 * 「玻璃光效」开 → [GlassTier.Full]；关 → [GlassTier.Frost]；
 * API < 31 没有 RenderEffect → [GlassTier.Plain]（效果链会被库静默跳过，面色必须不透明）。
 */
internal fun glassTier(effect: Boolean): GlassTier =
    if (Build.VERSION.SDK_INT < 31) GlassTier.Plain
    else if (effect) GlassTier.Full
    else GlassTier.Frost

/**
 * 玻璃的「面色」—— 决定这块玻璃偏黑还是偏白。
 *
 * 需求是「黑色模式下是黑色液态玻璃，白色模式下是带白色的液态玻璃」，
 * 所以直接用**纯黑 / 纯白 + 透明度**，而不是主题里的 `surfaceContainerHigh`：
 * 后者本身偏灰，叠在模糊后的内容上会显得发脏，出不来干净通透的观感。
 *
 * @param tier 玻璃档位（[glassTier]），三档面色逐档加深 —— 规范 docs/LIQUID-GLASS.md §5：
 *   - [GlassTier.Full]：[GlassAlphaFull] 薄薄一层，通透感靠模糊与折射；
 *   - [GlassTier.Frost]：[GlassAlphaFrost] 深一档，磨砂没有折射帮忙分离前景，
 *     面色要多承担可读性 —— 但必须保持半透明，不透明就退化成了 plain；
 *   - [GlassTier.Plain]：不透明纯色。没有模糊兜底就不该再叫「玻璃」
 *     （以前 0.70 的半透明会让底下文字透上来糊成一团，字都看不清）。
 */
internal fun glassSurfaceTint(dark: Boolean, tier: GlassTier): Color {
    val base = if (dark) Color.Black else Color.White
    return when (tier) {
        GlassTier.Full -> base.copy(alpha = GlassAlphaFull)
        GlassTier.Frost -> base.copy(alpha = GlassAlphaFrost)
        GlassTier.Plain -> base
    }
}

/**
 * 完整版（有模糊 + 折射）的面色透明度。
 *
 * 2.9.0 从 0.52 降到 0.30：用户反馈「玻璃不够通透」。面色只是给模糊结果**补一点颜色倾向**，
 * 通透感主要由它决定 —— alpha 越高越像磨砂塑料，越低越像真玻璃。
 * 0.30 在深色下仍然能保证顶栏文字可读（底下还有一层模糊兜底）。
 */
private const val GlassAlphaFull = 0.30f

/** 磨砂版（简约版）面色：比完整版深一档，起始值 0.45，真机校准后回填 */
private const val GlassAlphaFrost = 0.45f

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
 * 顶栏内容：只显示居中的标题，但高度仍按「标题 + 副标题」的旧排版算。
 *
 * 副标题（各板块的「环境概览 / 功能菜单 / …」）不再显示，可它原先撑起的高度
 * 要保留 —— 顶栏整体高度不变、标题在剩余空间里垂直居中。做法是把原来的
 * [SmallTopAppBar] 当高度尺：文字全部透明、语义清空，只让它参与测量；
 * 真正的标题再叠在它上方（扣掉状态栏 inset 后）居中。
 *
 * @param subtitleForHeight 只用于撑高度的旧副标题文案，不会显示
 * @param barColor 顶栏底色；玻璃形态传透明（玻璃在下面一层），纯色形态传 surface
 */
@Composable
internal fun TopBarContent(
    title: String,
    subtitleForHeight: String,
    modifier: Modifier = Modifier,
    barColor: Color = Color.Transparent,
) {
    Box(modifier) {
        SmallTopAppBar(
            title = title,
            subtitle = subtitleForHeight,
            color = barColor,
            // 只当高度尺：文字透明 + 语义清空，既不显形也不被读屏重复朗读
            titleColor = Color.Transparent,
            subtitleColor = Color.Transparent,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = TopAppBarDefaults.TitlePadding),
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = MiuixTheme.textStyles.title3.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    subtitleForHeight: String,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val dark = LocalDarkTheme.current
    val tier = glassTier(effect)
    val tint = glassSurfaceTint(dark, tier)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = {
                        if (tier != GlassTier.Plain) {
                            vibrancy()
                            blur(TopBarBlur.toPx())
                        }
                    },
                    // 不要描边 / 高光：顶栏是贴边的一整块，任何描边都会变成横线
                    highlight = null,
                    // 投影属于「光」的部分：完整版才有，磨砂版与纯色兜底一律无。
                    // 顶栏收起时投影的跟随由位移承担（见 Screens.kt 的 topBar Box），
                    // 这里不再乘任何进度 —— alpha<1 离屏合成会把边界外的投影裁没。
                    shadow = { Shadow.Default.copy(alpha = if (tier == GlassTier.Full) 1f else 0f) },
                    onDrawSurface = { drawRect(tint) },
                ),
        )
        Column {
            TopBarContent(
                title = title,
                subtitleForHeight = subtitleForHeight,
            )
            extra()
        }
    }
}
