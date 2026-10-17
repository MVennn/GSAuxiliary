package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.MVennn.GSAuxiliary.core.ConfigKeys
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 自绘开关 —— 替换 Miuix 的 `Switch` / `SwitchPreference`。
 *
 * **几何与交互完全对齐 Miuix**（49×28 轨道、20dp 滑块、拖动翻转、两档震动反馈），
 * 换的只有**动画手感**：Miuix 的弹簧（0.7/987）几乎不带过冲，落位是「啪一下贴住」；
 * 这里换成 [AnimCurves] 里的苹果味参数 —— 滑块到位后有一次可见的过冲回坐，
 * 颜色过渡走 [AppleExit] 的 easeInOut，与弹层 / 转场一个脾气。
 *
 * 结构参考 Miuix Switch（top.yukonga.miuix.kmp.basic.Switch，Apache-2.0）。
 * 之所以复制结构而不是给它传参：0.9.3 的动画 spec 是组件内部 `remember` 的私有值，
 * 没有任何注入口。
 *
 * 颜色直接取 `MiuixTheme` 的语义色（与 `SwitchDefaults.switchColors()` 同源），
 * 不引入 `SwitchColors` —— 那个类的取色方法是 internal，模块侧拿不到。
 */

/** 开关轨道尺寸（Miuix 同款） */
private val TrackSize = DpSize(49.dp, 28.dp)

/** 滑块直径（Miuix 同款） */
private const val ThumbSize = 20

/** 滑块轨道内的左右行程：4dp → 25dp，拖动边界 ±21（Miuix 同款） */
private val ThumbOffsetOn = 25.dp
private val ThumbOffsetOff = 4.dp
private const val DragRange = 21f

/** 按压 / 拖住滑块时的放大倍率（Miuix 同款） */
private const val PressedThumbScale = 1.127f

/** 液态滑块按住时的放大倍率 —— 比 Miuix 大一档，配合折射加深出「跃出轨道」的感觉 */
private const val LiquidPressedThumbScale = 1.35f

@Composable
internal fun GsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val hasCallback = onCheckedChange != null

    // 液态玻璃滑块：受「液态玻璃」总开关 + 「玻璃光效」开关共同裁决档位
    // （正本 docs/LIQUID-GLASS.md §3）：完整 = 真玻璃、简约 = 磨砂、
    // 总开关关 / 禁用 / 无回调 / API 过低 = 纯色兜底（经典实底画法）。
    // 拖动翻转与震动逻辑各档共用，换的只是滑块这一层「面」怎么画。
    val liquidGlassPref by rememberBoolPref(ConfigKeys.LIQUID_GLASS, ConfigKeys.DEFAULT_LIQUID_GLASS)
    val glassEffectPref by rememberBoolPref(ConfigKeys.GLASS_EFFECT, ConfigKeys.DEFAULT_GLASS_EFFECT)
    val tier = when {
        !liquidGlassPref || !enabled || !hasCallback -> GlassTier.Plain
        else -> glassTier(glassEffectPref)
    }
    val useLiquid = tier != GlassTier.Plain

    val hapticFeedback = LocalHapticFeedback.current
    val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
    var hasVibrated by remember { mutableStateOf(false) }
    var hasVibratedOnce by remember { mutableStateOf(false) }
    var rawDragOffset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val dragOffsetDp = with(density) { rawDragOffset.toDp() }

    val thumbOffset = animateDpAsState(
        targetValue = (if (checked) ThumbOffsetOn else ThumbOffsetOff) + dragOffsetDp,
        animationSpec = SwitchThumbSpring,
        label = "gs-switch-thumb-offset",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (!enabled) {
            1f
        } else if (isPressed || isDragged) {
            // 完整版跃出轨道（1.35），磨砂版 / 经典版退 Miuix 同款档
            if (tier == GlassTier.Full) LiquidPressedThumbScale else PressedThumbScale
        } else {
            1f
        },
        animationSpec = SwitchThumbScaleSpring,
        label = "gs-switch-thumb-scale",
    )
    // 按压进度：驱动滑块的「玻璃变透」—— blur ↔ lens 交叉过渡、高光 / 阴影浮现。
    // 参数对齐 LiquidPhysics.DampedDragAnimation 的 pressProgress 弹簧（1f, 1000f）。
    val pressProgress by animateFloatAsState(
        targetValue = if (isPressed || isDragged) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f),
        label = "gs-switch-press",
    )

    // 轨道采样层在 trackColor 声明之后创建（见下方）。

    // 颜色没有「过冲」可言，走 AppleExit 的 easeInOut，时长对齐弹层 220ms。
    // 禁用态取色与 Miuix SwitchDefaults 同源（disabled* 语义色）。
    val thumbColor by animateColorAsState(
        targetValue = if (checked) {
            if (enabled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.disabledOnPrimary
        } else {
            if (enabled) MiuixTheme.colorScheme.onSecondary else MiuixTheme.colorScheme.disabledOnSecondary
        },
        animationSpec = tween(220, easing = AppleExit),
        label = "gs-switch-thumb-color",
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.disabledPrimary
        } else {
            if (enabled) MiuixTheme.colorScheme.secondary else MiuixTheme.colorScheme.disabledSecondary
        },
        animationSpec = tween(220, easing = AppleExit),
        label = "gs-switch-track-color",
    )

    // 轨道采样层：液态滑块从这里取「身后画面」——只录轨道色，不含滑块自己，
    // 避免滑块折射到自身的自引用（与 Dock 染色层的兄弟节点套路同源）。
    val trackBackdrop = rememberLayerBackdrop { drawRect(trackColor); drawContent() }

    val toggleableModifier = if (hasCallback) {
        remember(checked, enabled, interactionSource) {
            Modifier.toggleable(
                value = checked,
                onValueChange = { v ->
                    currentOnCheckedChange?.invoke(v)
                    currentHapticFeedback.performHapticFeedback(
                        if (v) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                    )
                },
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                // 自绘开关自带按压反馈（放大 + 玻璃变透），不吃 LocalIndication 的 ripple，
                // 否则按住时轨道上会浮出和其他组件一样的高亮块
                indication = null,
            )
        }
    } else {
        Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
            if (!enabled) disabled()
        }
    }

    Box(
        modifier = modifier
            .wrapContentSize(Alignment.Center)
            .size(TrackSize)
            .clip(CircleShape)
            .drawBehind { drawRect(trackColor) }
            .then(toggleableModifier),
    ) {
        // 轨道采样录制层：空盒子，只把轨道色录进 trackBackdrop（不含滑块）。
        if (useLiquid) {
            Box(
                Modifier
                    .matchParentSize()
                    .layerBackdrop(trackBackdrop)
            )
        }
        Box(
            modifier = Modifier
                .size(ThumbSize.dp)
                .align(Alignment.CenterStart)
                .offset { IntOffset(thumbOffset.value.roundToPx(), 0) }
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .then(
                    if (useLiquid) {
                        // 液态玻璃滑块：完整版按压时变透、折射轨道色；磨砂版只模糊。
                        // 参数风格对齐 Dock 滑块与 catalog LiquidToggle。
                        Modifier.drawBackdrop(
                            backdrop = trackBackdrop,
                            shape = { CircleShape },
                            effects = {
                                if (tier == GlassTier.Full) {
                                    val p = pressProgress
                                    if (p < 1f) blur(6f.dp.toPx() * (1f - p))
                                    // 折射量给到 dock 滑块同档（14dp），色差打开，
                                    // 按住时轨道色被明显「吸进」滑块 —— 跃出感主要来自这里
                                    if (p > 0f) {
                                        lens(
                                            5f.dp.toPx() * p,
                                            14f.dp.toPx() * p,
                                            chromaticAberration = true,
                                        )
                                    }
                                } else {
                                    // 磨砂版：只模糊不折射，按压不改变玻璃本身
                                    blur(6f.dp.toPx())
                                }
                            },
                            highlight = {
                                if (tier == GlassTier.Full) {
                                    Highlight.Default.copy(alpha = pressProgress)
                                } else {
                                    null
                                }
                            },
                            shadow = {
                                if (tier == GlassTier.Full) {
                                    Shadow(radius = 6.dp, alpha = pressProgress)
                                } else {
                                    null
                                }
                            },
                            innerShadow = {
                                if (tier == GlassTier.Full) {
                                    InnerShadow(radius = 3.dp * pressProgress, alpha = pressProgress)
                                } else {
                                    null
                                }
                            },
                            onDrawSurface = {
                                if (tier == GlassTier.Full) {
                                    // 按住时几乎全透，把折射完整露出来
                                    drawRect(
                                        thumbColor.copy(alpha = lerp(0.85f, 0.05f, pressProgress)),
                                    )
                                } else {
                                    // 磨砂版：面色恒定，按压只缩放
                                    drawRect(thumbColor.copy(alpha = 0.85f))
                                }
                            },
                        )
                    } else {
                        Modifier.drawBehind { drawCircle(thumbColor) }
                    },
                )
                .then(
                    if (enabled) {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { dragAmount ->
                                rawDragOffset += dragAmount / 2f
                                val clamped = if (checked) {
                                    rawDragOffset.coerceIn(-DragRange, 0f)
                                } else {
                                    rawDragOffset.coerceIn(0f, DragRange)
                                }
                                // 震动反馈的分档逻辑与 Miuix 一致：拉到头 / 回到起点各一档
                                if (clamped in -11f..-10f || clamped in 10f..11f) {
                                    hasVibratedOnce = false
                                } else if (clamped in -20f..-1f || clamped in 1f..20f) {
                                    hasVibrated = false
                                } else if (!hasVibrated) {
                                    if ((checked && clamped == -DragRange) ||
                                        (!checked && clamped == 0f)
                                    ) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                        hasVibrated = true
                                        hasVibratedOnce = true
                                    } else if ((checked && clamped == 0f) ||
                                        (!checked && clamped == DragRange)
                                    ) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                        hasVibrated = true
                                        hasVibratedOnce = true
                                    }
                                }
                                rawDragOffset = clamped
                            },
                            onDragStarted = { _ ->
                                hasVibrated = true
                                hasVibratedOnce = false
                                rawDragOffset = 0f
                            },
                            onDragStopped = {
                                if (abs(rawDragOffset) > DragRange / 2f) {
                                    currentOnCheckedChange?.invoke(!checked)
                                }
                                rawDragOffset = 0f
                            },
                        )
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/** 拖动增量折算成 Dp（draggable 给的是 px，除以密度即可） */

/**
 * 带「标题 + 副标题」的自绘开关行 —— 对齐 Miuix `SwitchPreference` 的用法。
 *
 * 只收本项目实际用到的参数（checked / onCheckedChange / title / summary /
 * summaryColor / enabled）；Miuix 版的 startAction / endActions / bottomAction /
 * switchColors / insideMargin / holdDownState 目前无人使用，不搬运。
 */
@Composable
internal fun GsSwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    BasicComponent(
        modifier = modifier,
        title = title,
        summary = summary,
        endActions = {
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .align(Alignment.CenterVertically)
                    .weight(1f, fill = false),
            ) {
                GsSwitch(
                    checked = checked,
                    onCheckedChange = currentOnCheckedChange,
                    enabled = enabled,
                )
            }
        },
        onClick = {
            currentOnCheckedChange.takeIf { enabled }?.invoke(!checked)
        },
        role = Role.Switch,
        enabled = enabled,
    )
}
