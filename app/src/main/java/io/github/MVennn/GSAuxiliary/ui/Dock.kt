package io.github.MVennn.GSAuxiliary.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import io.github.MVennn.GSAuxiliary.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 底栏（Dock）。
 *
 * 两种形态，由设置页的「液态玻璃」总开关切换（`ConfigKeys.LIQUID_GLASS`）：
 *  - [MiuixDock]      —— Miuix 官方 [NavigationBar]，贴边、实底
 *  - [LiquidGlassDock] —— 悬浮胶囊 + 液态玻璃（backdrop 采样背景 → 模糊 + 折射 + 增艳）
 *
 * 液态玻璃参考 Kyant0/AndroidLiquidGlass 的液态开关（`LiquidToggle`）：
 * 把 Tab 内容通过 `Modifier.layerBackdrop` 录进一个图层，底栏再用
 * `Modifier.drawBackdrop` 把该图层采样回来，叠加模糊 / 折射 / 高光 / 内阴影，
 * 并补一层**外投影**让它真正「浮」在内容之上。
 *
 * 图层顺序（自下而上）：
 *   ① dock 内容层（玻璃胶囊 + 图标行，录进 dockBackdrop）
 *   ② 滑块（放大镜）—— 采样 ① 并放大绘制，所以画在 ① 之上
 *   ③ 长按气泡（画在最外，不受圆角裁剪）
 * 滑块必须画在内容层**之上**：它要「盖住」正常大小的图标 / 标题，
 * 用放大后的画面替换它们 —— 这才是放大镜。
 *
 * ## 完整版 vs 简约版（「玻璃光效」开关）
 *
 * | | 完整版（开） | 简约版（关） |
 * |---|---|---|
 * | 模糊 + 折射 | 有 | **无** |
 * | 外投影 | 有 | **无** |
 * | 内阴影 | 有 | **无** |
 * | 顶部高光（白边） | 有 | **有** |
 * | 底色 | 半透明 | 接近实底 |
 *
 * 注意：简约版去掉的是**模糊与投影**，白边（高光描边）保留 ——
 * 底色相应加实，否则没有模糊兜底、文字会和透出来的内容糊在一起。
 *
 * 底色由 [glassSurfaceTint] 统一给出，与顶栏共用同一套「黑玻璃 / 白玻璃」规则。
 *
 * ## 交互
 *
 *  - **点击**：切换板块
 *  - **按住滑块左右拖**：滑块跟手移动；**跨过中线即刻切换**，松手只负责把滑块吸附对齐
 *  - **长按**：弹出该板块的名称气泡（约 1.4s 后自动消失）
 *  - **向下滑内容**：整条收起（由 `HomeScaffold` 的滚动监听驱动，见 [DockScrollState]）
 *
 * ## 拖拽为什么不再依赖 `onDragEnd`
 *
 * 旧实现把「切换」全部压在 `onDragEnd` 上，而且把 `dragging` 也当成吸附动画的 key，
 * 于是有两个必然会翻车的地方：
 *
 *  1. `pointerInput` 的 key 只有 `(itemCount, itemWidthPx)`，手势回调里捕获的
 *     `current` / `onSelect` **不会随重组刷新**（过期闭包）。用过期值做判断就会
 *     时灵时不灵。
 *  2. 松手那一刻 `dragging` 由 true 翻成 false，吸附动画立刻用**尚未更新的
 *     `current`** 重新起跑 —— 滑块被拉回原位，看起来就是「拖到位却没切过去」。
 *
 * 现在：用 [rememberUpdatedState] 拿最新值；吸附动画只在 `current` 变化时触发；
 * 拖动过程中跨过中线就调用 `onSelect` 实时切换，所以即使手势被上层取消
 * （`onDragEnd` 根本不触发）也一定能切过去。
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

/** 胶囊内左右内边距（滑动指示与气泡定位都用它） */
private val DockInnerPadding = 6.dp

/** 滑块吸附动画时长 */
private const val IndicatorMillis = 260

/** 拖动时「放大镜」的进出时长（松手后也用它还原） */
private const val MagnifyMillis = 180

/**
 * 滑块被捏住（按住拖拽）时的**等比**放大倍率。
 *
 * 必须等比，不能像 2.9.2 那样 X / Y 各给一个值 —— 放大镜采样的是 dock 自身的
 * 画面，非等比缩放会把图标和标题拉变形。
 *
 * 2.9.4 由 0.34 收敛到 **0.22**（用户反馈「放大倍率过大」，要求更柔和自然）：
 * 高度 `(DockHeight - 10dp) × 1.22 ≈ 66dp` 只比 64dp 的胶囊探出一线、
 * 宽度也只比板块槽位宽一点 —— 仍看得出「被拿起来」，但不再猛地撑满整条底栏。
 */
private const val KnobScale = 0.22f

/** 滑块相对胶囊上下各内缩的量（决定常态滑块的高度） */
private val KnobInset = 5.dp

/** 滑块左右相对板块槽位的内缩量 */
private val KnobSideInset = 4.dp

/**
 * 液态玻璃悬浮底栏。
 *
 * @param effect 是否绘制完整玻璃效果（模糊 / 折射 / 投影 / 内阴影）。
 *               关闭时为「简约版」：去掉模糊与投影，保留白边与实底。
 */
@Composable
internal fun LiquidGlassDock(
    backdrop: LayerBackdrop,
    effect: Boolean,
    current: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    // 面色与顶栏同源：深色模式黑玻璃、浅色模式白玻璃。
    // 完整版靠模糊兜底，面色可以薄一点；简约版没有模糊，面色必须够实。
    val surface = glassSurfaceTint(LocalDarkTheme.current, effect)
    val primary = MiuixTheme.colorScheme.primary
    val density = LocalDensity.current

    // 长按气泡状态：pressed 非空时显示，1.4s 后自动清掉
    var pressed by remember { mutableStateOf<Tab?>(null) }
    var lastPressed by remember { mutableStateOf(Tab.MODULE) }
    var dockWidthPx by remember { mutableIntStateOf(0) }

    // --- 滑块：点击切换时动画吸附；按住拖动时即时跟手 ---
    val itemCount = Tab.entries.size
    var dragging by remember { mutableStateOf(false) }
    // 滑块位置以「第几个板块」为单位，拖动过程中可以是小数
    var indicator by remember { mutableFloatStateOf(current.ordinal.toFloat()) }

    // 手势回调活在 `pointerInput` 里，它的 key 只有 (itemCount, itemWidthPx)，
    // 重组时**不会**重建 —— 直接读 `current` / `onSelect` 会拿到过期闭包。
    val latestCurrent = rememberUpdatedState(current)
    val latestOnSelect = rememberUpdatedState(onSelect)

    val scope = rememberCoroutineScope()
    // 当前正在跑的吸附动画；换目标时先取消，避免两段动画互相拉扯
    val snapJob = remember { mutableStateOf<Job?>(null) }

    // dock 自身的图层：滑块（放大镜）采样**这一层**，而不是页面内容层。
    //
    // 2.9.3 修的问题：之前滑块采样的是 `backdrop`（页面内容），于是放大镜里看到的
    // 是底下的页面 —— 视觉上像把底栏「穿透」了一个洞，而 dock 自己的图标、标题、
    // 底部玻璃一点都没被放大。现在把「玻璃胶囊 + 图标行」单独录进这一层。
    val dockBackdrop = rememberLayerBackdrop { drawContent() }

    /** 把滑块吸附到第 [index] 个板块 */
    fun snapTo(index: Int) {
        snapJob.value?.cancel()
        snapJob.value = scope.launch {
            animate(
                initialValue = indicator,
                targetValue = index.toFloat(),
                animationSpec = tween(IndicatorMillis),
            ) { value, _ -> indicator = value }
        }
    }

    // 拖动时滑块（放大镜）整体「被拿起来」，松手平滑还原（0 = 常态，1 = 完全放大）
    val magnify by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(MagnifyMillis),
        label = "dock-magnify",
    )

    // 只在 current 变化时吸附。
    //
    // ⚠️ **刻意不把 dragging 作为 key**：松手那一刻 dragging 由 true 翻成 false，
    // 若把它当 key，会立刻用**还没更新的 current** 再起一段吸附动画，滑块被拉回原位。
    LaunchedEffect(current) {
        if (!dragging) snapTo(current.ordinal)
    }

    LaunchedEffect(pressed) {
        val p = pressed ?: return@LaunchedEffect
        lastPressed = p
        delay(BubbleMillis)
        pressed = null
    }

    val padPx = with(density) { DockInnerPadding.toPx() }
    // 每个板块的宽度（去掉内边距后均分），把像素位移换算成「第几个板块」
    val itemWidthPx = ((dockWidthPx - padPx * 2).coerceAtLeast(0f)) / itemCount

    Box(
        modifier = modifier
            // -----------------------------------------------------------------
            // 手势：点击 / 拖动 / 长按 由**同一个** awaitEachGesture 裁决
            //
            // 以前是「父级挂 detectHorizontalDragGestures + 每个 item 挂
            // combinedClickable」。两条手势链同时收到同一批事件，手指轻抖越过
            // touchSlop 时父级抢先进入拖动并 consume，子级的点击随之被取消 ——
            // 结果既没切板块、滑块又抖了一下，就是「点击时偶发闪烁」。
            //
            // 现在只留一条链：越过 slop 才算拖动，没越过就按**落点**当点击，
            // 静置到长按超时就弹气泡。三者互斥，不可能既点了又拖了。
            // -----------------------------------------------------------------
            .pointerInput(itemCount, itemWidthPx) {
                if (itemWidthPx <= 0f) return@pointerInput
                val slop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis

                /** 落点 x 落在第几个板块上 */
                fun indexAt(x: Float): Int =
                    ((x - padPx) / itemWidthPx).toInt().coerceIn(0, itemCount - 1)

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val startTime = down.uptimeMillis
                    var lastX = startX
                    var now = startTime
                    var isDrag = false
                    var longPressed = false

                    // 一碰就先掐掉还在跑的吸附动画，避免和手指抢滑块
                    snapJob.value?.cancel()

                    while (true) {
                        val event = if (isDrag || longPressed) {
                            awaitPointerEvent()
                        } else {
                            // 长按计时：这段时间里既没抬手、也没越过 slop，就算长按
                            val remain = (longPressMs - (now - startTime)).coerceAtLeast(1L)
                            withTimeoutOrNull(remain) { awaitPointerEvent() }
                        }
                        if (event == null) {
                            // 长按触发：弹气泡，继续等抬手（抬手后不再切换板块）
                            longPressed = true
                            val t = Tab.entries[indexAt(startX)]
                            lastPressed = t
                            pressed = t
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        now = change.uptimeMillis
                        if (!change.pressed) break

                        if (!isDrag && !longPressed &&
                            abs(change.position.x - startX) > slop
                        ) {
                            // 越过 touchSlop 才认定是拖动 —— 点击时手指抖一点不会被误判
                            isDrag = true
                            dragging = true
                            lastPressed = Tab.entries[
                                indicator.roundToInt().coerceIn(0, itemCount - 1)
                            ]
                        }
                        if (isDrag) {
                            val step = change.position.x - lastX
                            lastX = change.position.x
                            change.consume()
                            val next = (indicator + step / itemWidthPx)
                                .coerceIn(0f, (itemCount - 1).toFloat())
                            indicator = next

                            // **拖动即切换**：跨过中线就立刻选中。
                            // 不把切换压在抬手时刻 —— 手势被上层取消时收尾逻辑不会跑，
                            // 那样就会出现「拖到位却没切过去、还被拉回原位」。
                            val index = next.roundToInt().coerceIn(0, itemCount - 1)
                            if (index != latestCurrent.value.ordinal) {
                                lastPressed = Tab.entries[index]
                                latestOnSelect.value(Tab.entries[index])
                            }
                        }
                    }

                    // ---- 收尾：三种结局互斥 ----
                    dragging = false
                    when {
                        isDrag -> {
                            // 拖动过程中已经实时切过了，这里只负责把滑块对齐到最近板块
                            val target = indicator.roundToInt().coerceIn(0, itemCount - 1)
                            latestOnSelect.value(Tab.entries[target])
                            snapTo(target)
                        }

                        !longPressed -> {
                            // 点击：按**落点**选中，而不是按滑块当前所在位置
                            val index = indexAt(startX)
                            latestOnSelect.value(Tab.entries[index])
                            snapTo(index)
                        }
                    }
                    pressed = null
                }
            },
    ) {
        // ---- 1) dock 内容层：玻璃胶囊 + 图标行，录进 dockBackdrop ----
        //
        // 滑块（放大镜）要放大的就是这一层的内容（图标 / 标题 / 底部玻璃），
        // 所以它必须单独成层、供 drawBackdrop 采样；滑块画在这一层**之外**，
        // 才不会把自己也录进去（自我反馈）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(DockHeight)
                .onSizeChanged { dockWidthPx = it.width }
                .layerBackdrop(dockBackdrop),
        ) {
            // ---- 1a) 玻璃胶囊本体 ----
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            if (effect) {
                                vibrancy()
                                blur(20.dp.toPx())
                                lens(14.dp.toPx(), 26.dp.toPx())
                            }
                        },
                        // 白边（顶部高光）两种形态都保留
                        highlight = { Highlight.Default },
                        // 外投影只在完整版给：「悬浮感」靠它，简约版要去掉
                        shadow = if (effect) {
                            { Shadow(radius = 18.dp, alpha = 0.28f) }
                        } else {
                            null
                        },
                        innerShadow = if (effect) ({ InnerShadow.Default }) else null,
                        onDrawSurface = { drawRect(surface) },
                    ),
            )

            // ---- 1b) 图标行 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DockHeight)
                    .padding(horizontal = DockInnerPadding),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Tab.entries.forEach { tab ->
                    GlassDockItem(
                        tab = tab,
                        selected = current == tab,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- 2) 滑块（放大镜）：画在内容层之上 ----
        //
        // 它采样 dockBackdrop 并放大绘制，于是滑块范围内的图标 / 标题 / 底部玻璃
        // 一起被「拿起来」放大；范围外的内容不受影响 —— 这就是放大镜的观感。
        // 标题和图标在采样的画面里**原样保留**（2.9.3 之前文字会随拖动淡出，
        // 于是放大镜里的标题也跟着消失了）。
        if (dockWidthPx > 0) {
            val innerWidth = (dockWidthPx - padPx * 2).coerceAtLeast(0f)
            val itemWidth = innerWidth / itemCount
            val knobSide = with(density) { KnobSideInset.toPx() }
            val knobWidth = (itemWidth - knobSide * 2).coerceAtLeast(0f)
            val knobOffset = padPx + itemWidth * indicator + knobSide

            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(knobOffset.roundToInt(), 0) }
                    .width(with(density) { knobWidth.toDp() })
                    .height(DockHeight - KnobInset * 2)
                    // 被捏住时**等比**放大（宽高同步）：高度只比胶囊探出一线，
                    // 读起来才像「被拿起来」，又不会放大过猛。先缩放再画，
                    // drawBackdrop 的采样结果会跟着一起被放大 —— 这正是放大镜。
                    .graphicsLayer {
                        val s = 1f + KnobScale * magnify
                        scaleX = s
                        scaleY = s
                    }
                    .then(
                        if (magnify > 0.01f) {
                            // 液态玻璃放大镜：折射（lens）+ 增艳 + 高光描边 +
                            // 内阴影 + 外投影 —— 一整套「玻璃把内容拿起来」的材质。
                            // 折射强度随捏住的进度一起长，起步时不会猛地变形。
                            // 2.9.4 把折射范围 / 强度由 26/48dp 收紧到 20/36dp，
                            // 与倍率下调（0.34 → 0.22）配套，整体观感更柔和自然。
                            Modifier.drawBackdrop(
                                backdrop = dockBackdrop,
                                shape = { RoundedCornerShape(percent = 50) },
                                effects = {
                                    vibrancy()
                                    lens(20.dp.toPx() * magnify, 36.dp.toPx() * magnify)
                                },
                                highlight = { Highlight.Default },
                                shadow = { Shadow(radius = 16.dp, alpha = 0.30f) },
                                innerShadow = { InnerShadow.Default },
                                // 主色薄薄一层：既标出「当前板块」的色相，
                                // 又不挡住放大后的内容
                                onDrawSurface = {
                                    drawRect(primary.copy(alpha = 0.14f + 0.10f * magnify))
                                },
                            )
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(primary.copy(alpha = 0.22f))
                        }
                    ),
            )
        }

        // ---- 3) 长按气泡（必须画在 drawBackdrop 之外，否则被圆角裁剪）----
        if (dockWidthPx > 0) {
            val innerWidth = (dockWidthPx - padPx * 2).coerceAtLeast(0f)
            val centerX = padPx + innerWidth * (lastPressed.ordinal + 0.5f) / itemCount
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

/**
 * 玻璃底栏里的单个 Tab：图标 + 文字，选中用主色。
 *
 * **不再自带 `clickable`**：点击 / 拖动 / 长按统一由 [LiquidGlassDock] 的那一条
 * `awaitEachGesture` 裁决（见那里的注释）。这里只负责画。
 *
 * **也不再对图标 / 文字做任何缩放或淡出**（2.9.3 移除）：放大效果完全交给滑块的
 * 放大镜 —— 它采样 dock 图层再放大绘制，图标和标题在镜中一起被放大。
 * 之前文字随拖动淡出（`alpha = 1 - magnify`）导致「滑块经过时标题消失」，
 * 而且淡出也被录进图层，放大镜里照样看不到标题。
 */
@Composable
private fun GlassDockItem(
    tab: Tab,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Column(
        modifier = modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
