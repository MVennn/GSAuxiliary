package io.github.MVennn.GSAuxiliary.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import io.github.MVennn.GSAuxiliary.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sign

/**
 * 底栏（Dock）。
 *
 * 两种形态，由设置页的「液态玻璃」总开关切换（`ConfigKeys.LIQUID_GLASS`）：
 *  - [MiuixDock]      —— Miuix 官方 [NavigationBar]，贴边、实底
 *  - [LiquidGlassDock] —— 悬浮胶囊 + 液态玻璃
 *
 * 3.2.0 起的玻璃实现**逐条对齐 Kyant0/AndroidLiquidGlass 演示包里的
 * LiquidBottomTabs**（backdrop 库作者自己的 Catalog demo），三层结构：
 *
 *   ① 玻璃面板（Row）：vibrancy + blur(8dp) + lens(24dp, 24dp)，
 *      按下时整体微涨（1 + 16dp/宽）；
 *   ② **隐藏的染色层**：同样的 Tab 内容，alpha(0) 不显示、但录进 tabsBackdrop，
 *      整层被 ColorFilter.tint 染成主色 —— 专供滑块采样；
 *   ③ 滑块：采样 `rememberCombinedBackdrop(页面, 染色层)`，于是滑块范围内的
 *      图标 / 标题变成「主色染过的玻璃」，配 lens(色差) + 随按压进度浮现的
 *      高光 / 外投影 / 内阴影 —— 就是演示包那个「把玻璃拿起来」的观感。
 *
 * 交互物理（[DampedDragAnimation] / [InteractiveHighlight]，见 LiquidPhysics.kt）
 * 同样来自该 demo：按压缩放 78/56、拖动时按速度做**甩动形变**（快甩变扁再回弹）、
 * 高光斑跟随手指、松手阻尼弹簧吸附。
 *
 * ## 完整版 vs 简约版（「玻璃光效」开关，正本 docs/LIQUID-GLASS.md §3）
 *
 * 简约版是**磨砂玻璃**（Regular）：vibrancy + blur 保留，去掉折射 / 染色层 /
 * 光斑 / 投影，滑块退化为实底色块，面板面色走 glassSurfaceTint 的磨砂档；
 * API < 31 无 RenderEffect 时整体纯色兜底（tier = Plain）。
 *
 * ## 交互
 *
 *  - **点击板块**：切换（阻尼弹簧滑过去）
 *  - **按住滑块左右拖**：滑块跟手 + 面板整体轻微让位（弹性 panelOffset）
 *  - **长按板块**：弹出名称气泡（约 1.4s 后自动消失）
 *  - **向下滑内容**：整条收起（由 `HomeScaffold` 的滚动监听驱动，见 `DockScrollState`）
 */
internal enum class Tab(
    @StringRes val labelRes: Int,
    @StringRes val subtitleRes: Int,
    val icon: ImageVector,
) {
    MODULE(R.string.tab_module, R.string.subtitle_module, AppIcons.Apps),
    FEATURE(R.string.tab_feature, R.string.subtitle_feature, AppIcons.Extension),
    SETTINGS(R.string.tab_settings, R.string.subtitle_settings, AppIcons.Settings),
    ABOUT(R.string.tab_about, R.string.subtitle_about, AppIcons.Info),
}

/**
 * 液态底栏时，各 Tab 需要在**滚动内容末尾**补的空白高度。
 *
 * 为什么不直接把内容整体上推：那样底栏下方就没有内容可供玻璃采样，
 * 底栏会贴在空白上，看起来「没悬浮」。改成在滚动内容里补空白后，
 * 列表可以滚到底栏之下，玻璃才有折射对象。
 */
internal val LocalDockInset = compositionLocalOf { 0.dp }

/** Tab 内容在按压滑块时的等比放大（由底栏统一提供，见 LiquidBottomTab 同款机制） */
internal val LocalDockTabScale = staticCompositionLocalOf { { 1f } }

/** Miuix 默认底栏：贴边、随主题 */
@Composable
internal fun MiuixDock(
    current: Tab,
    onSelect: (Tab) -> Unit,
) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = tab.icon,
                label = stringResource(tab.labelRes),
            )
        }
    }
}

/** 长按气泡的停留时长 */
private const val BubbleMillis = 1400L

/** 胶囊内左右内边距（官方 demo 同款 4dp，Tab 槽位与滑块都对齐它） */
private val DockInnerPadding = 4.dp

/**
 * 液态玻璃悬浮底栏（对齐官方 LiquidBottomTabs）。
 *
 * @param effect 是否绘制完整玻璃效果（模糊 / 折射 / 投影 / 染色层 / 光斑）。
 *               关闭时为「简约版」：滑块是实底色块，面板只有面色与高光。
 */
@Composable
internal fun LiquidGlassDock(
    backdrop: LayerBackdrop,
    effect: Boolean,
    current: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    val tier = glassTier(effect)
    // 面色取官方 demo 的玻璃面板配方：#FAFAFA / #121212 @ 0.4，靠模糊兜底保持通透。
    // 磨砂版 / 纯色兜底的面色走 glassSurfaceTint 分档（磨砂半透明加深、兜底不透明）。
    val containerColor =
        if (dark) Color(0xFF121212).copy(alpha = 0.4f)
        else Color(0xFFFAFAFA).copy(alpha = 0.4f)
    val accentColor = MiuixTheme.colorScheme.primary

    val tabsCount = Tab.entries.size

    // 隐藏的染色层录进这里，滑块采样「页面 + 这一层」
    val tabsBackdrop = rememberLayerBackdrop { drawContent() }

    // 长按气泡：pressed 非空时显示，1.4s 后自动清掉
    var pressed by remember { mutableStateOf<Tab?>(null) }
    var lastPressed by remember { mutableStateOf(Tab.MODULE) }
    var dockWidthPx by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        modifier
            .onSizeChanged { dockWidthPx = it.width },
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        // 每个板块的宽度：去掉面板左右 4dp 内边距后均分（官方 demo 同款公式）
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - DockInnerPadding.toPx() * 2f) / tabsCount
        }

        // 拖动越界时整条面板的弹性让位：最多 4dp，随越界比例缓出
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth)
                    .fastCoerceIn(-1f, 1f)
                with(density) {
                    DockInnerPadding.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        var currentIndex by remember { mutableIntStateOf(current.ordinal) }
        val dampedDragAnimation = remember(scope) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = current.ordinal.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex =
                        targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    scope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    scope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        // 外部状态（点击板块 / 配置回调）→ 驱动滑块动画
        LaunchedEffect(current) {
            currentIndex = current.ordinal
        }
        // 内部选中（拖动松手）→ 回调出去
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                    onSelect(Tab.entries[index])
                }
        }
        LaunchedEffect(pressed) {
            val p = pressed ?: return@LaunchedEffect
            lastPressed = p
            delay(BubbleMillis)
            pressed = null
        }

        // 光斑锚在滑块中心、垂直居中 —— 跟着滑块走（官方 demo 同款）
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val interactiveHighlight = remember(scope) {
            InteractiveHighlight(
                animationScope = scope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        } else {
                            size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        },
                        size.height / 2f,
                    )
                },
            )
        }

        // ---- ① 玻璃面板 + 可见的 Tab 内容 ----
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { DockCapsuleShape },
                    effects = {
                        if (tier != GlassTier.Plain) {
                            vibrancy()
                            blur(8.dp.toPx())
                            // 折射是「光」的部分，磨砂版不出（API 31-32 库内自动 no-op）
                            if (tier == GlassTier.Full) {
                                lens(24f.dp.toPx(), 24f.dp.toPx())
                            }
                        }
                    },
                    layerBlock = {
                        // 按住滑块时整条面板微微「鼓起来」（按压缩放，磨砂版保留）
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = {
                        drawRect(
                            if (tier == GlassTier.Full) containerColor
                            else glassSurfaceTint(dark, tier),
                        )
                    },
                )
                .then(if (tier == GlassTier.Full) interactiveHighlight.modifier else Modifier)
                .height(DockHeight)
                .fillMaxWidth()
                .padding(DockInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                DockTab(
                    tab = tab,
                    selected = current == tab,
                    interactive = true,
                    onClick = { onSelect(tab) },
                    onLongClick = {
                        lastPressed = tab
                        pressed = tab
                    },
                )
            }
        }

        // ---- ② 隐藏的染色层：录进 tabsBackdrop，专供滑块采样 ----
        //
        // alpha(0) 只是不显示；layerBackdrop 记录的是它里面的完整画面，
        // 整层再被 ColorFilter.tint 染成主色 —— 滑块里看到的就是「主色玻璃 + 图标」。
        // 简约版滑块是实底色块，不采样，这一层整个省掉（磨砂版只有面板，滑块仍实底）。
        if (tier == GlassTier.Full) {
            CompositionLocalProvider(
                LocalDockTabScale provides {
                    lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                },
            ) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer { translationX = panelOffset }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { DockCapsuleShape },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                                blur(8f.dp.toPx())
                                lens(
                                    24f.dp.toPx() * progress,
                                    24f.dp.toPx() * progress,
                                )
                            },
                            highlight = {
                                val progress = dampedDragAnimation.pressProgress
                                Highlight.Default.copy(alpha = progress)
                            },
                            onDrawSurface = { drawRect(containerColor) },
                        )
                        .then(interactiveHighlight.modifier)
                        .height(DockHeight)
                        .fillMaxWidth()
                        .padding(horizontal = DockInnerPadding)
                        .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tab.entries.forEach { tab ->
                        DockTab(
                            tab = tab,
                            selected = current == tab,
                            interactive = false,
                        )
                    }
                }
            }
        }

        // ---- ③ 滑块：采样「页面 + 染色层」的玻璃放大镜 ----
        Box(
            Modifier
                .padding(horizontal = DockInnerPadding)
                .graphicsLayer {
                    translationX = dampedDragAnimation.value * tabWidth + panelOffset
                }
                .then(if (tier == GlassTier.Full) interactiveHighlight.gestureModifier else Modifier)
                .then(dampedDragAnimation.modifier)
                .then(
                    if (tier == GlassTier.Full) {
                        Modifier.drawBackdrop(
                            backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                            shape = { DockCapsuleShape },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                lens(
                                    10f.dp.toPx() * progress,
                                    14f.dp.toPx() * progress,
                                    chromaticAberration = true,
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                            },
                            shadow = {
                                Shadow(alpha = dampedDragAnimation.pressProgress)
                            },
                            innerShadow = {
                                val progress = dampedDragAnimation.pressProgress
                                InnerShadow(
                                    radius = 8f.dp * progress,
                                    alpha = progress,
                                )
                            },
                            layerBlock = {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                // 甩动形变：快甩横向压扁、纵向拉长，回弹时相反
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(
                                    if (dark) Color.White.copy(0.1f)
                                    else Color.Black.copy(0.1f),
                                    alpha = 1f - progress,
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            },
                        )
                    } else {
                        // 磨砂版 / 纯色兜底滑块：实底色块（无折射 / 投影 / 光斑），保留按压缩放
                        Modifier
                            .graphicsLayer {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                            }
                            .clip(DockCapsuleShape)
                            .background(accentColor.copy(alpha = 0.22f))
                    },
                )
                .height(DockHeight - DockInnerPadding * 2)
                .fillMaxWidth(1f / tabsCount),
        )

        // ---- ④ 长按气泡（画在最外层，不受圆角裁剪）----
        if (dockWidthPx > 0) {
            val padPx = with(density) { DockInnerPadding.toPx() }
            val innerWidth = (dockWidthPx - padPx * 2).coerceAtLeast(0f)
            val centerX = padPx + innerWidth * (lastPressed.ordinal + 0.5f) / tabsCount
            val offsetX = with(density) { (centerX - dockWidthPx / 2f).toDp() }

            AnimatedVisibility(
                visible = pressed != null,
                enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.8f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(x = offsetX, y = (-38).dp),
            ) {
                DockBubble(text = stringResource(lastPressed.labelRes))
            }
        }
    }
}

/** 胶囊形状：全尺寸圆角 == 体育场形，几何上与官方的 Capsure 一致，无需额外依赖 */
private val DockCapsuleShape = RoundedCornerShape(percent = 50)

/**
 * 底栏里的单个 Tab：图标 + 文字。
 *
 * - [interactive] = true：可点击（选择板块）+ 可长按（弹气泡）；内容色随选中态。
 * - [interactive] = false：纯绘制，供隐藏染色层复用 —— 不挂任何手势，
 *   指针事件会穿透到下面的可见层。
 *
 * 按压缩放由 [LocalDockTabScale] 统一驱动（滑块按下时内容一起 1→1.2）。
 */
@Composable
private fun RowScope.DockTab(
    tab: Tab,
    selected: Boolean,
    interactive: Boolean,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val scale = LocalDockTabScale.current
    val tint = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Column(
        Modifier
            .clip(DockCapsuleShape)
            .then(
                if (interactive) {
                    Modifier.combinedClickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Tab,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier
                },
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = stringResource(tab.labelRes),
            color = tint,
            fontSize = 11.sp,
        )
    }
}

/** 长按气泡：小圆角胶囊 + 名称 */
@Composable
private fun DockBubble(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MiuixTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onBackground,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
