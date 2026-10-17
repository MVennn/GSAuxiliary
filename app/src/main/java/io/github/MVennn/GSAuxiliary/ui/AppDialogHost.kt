package io.github.MVennn.GSAuxiliary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 入场 / 出场动画时长（缩放 / 位移那条线） */
private const val DialogMillis = 220

/**
 * 锚定菜单「渐隐渐出」的时长 —— 透明度那条线。
 *
 * 与 [DialogMillis] **互相独立**：两条动画各自跑自己的时长，改一个不会拖着另一个走。
 * 比缩放略短一点，让「显影」先完成、缩放再稳稳落位。
 */
private const val DialogFadeMillis = 180

/**
 * 苹果风入场曲线 —— **锚定菜单**（缩放）用。
 *
 * easeOutBack 族：前段快、后段带一点回弹过冲再收住。缩放的动画区间只有
 * `AppearScale(0.80) → 1` 这 0.2，曲线约 10% 的峰值过冲折算下来只是
 * ~2% 的轻微「弹一下」（峰值 ≈ 1.02），正好是 iOS 弹层那种落位手感。
 */
private val AppleMenuEnterEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * 苹果风入场曲线 —— **贴底弹窗**（位移）用。
 *
 * 与 [AppleMenuEnterEasing] 同族，但控制点从 1.56 压到 1.3：位移的动画区间
 * 是**整个卡片高度**，同样的过冲比例会放大成一大段肉眼可见的弹跳，所以把
 * 峰值过冲压到 ~3%（几 dp 的落位回坐），保留「滑到位再稳一下」的苹果味。
 */
private val AppleSheetEnterEasing = CubicBezierEasing(0.34f, 1.3f, 0.64f, 1f)

/**
 * 苹果风出场曲线（两种形态共用）。
 *
 * `(0.42, 0, 0.58, 1)` 即 `UIView.animate` 的默认 easeInOut ——
 * 平滑起步、平滑离场。出场方向**不能**用带回弹的曲线：过冲会冲过 0，
 * 表现为卡片缩得比起始还小 / 滑出屏幕后又弹回来一点。
 */
private val AppleExitEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** [resolveAnchorPlacement] 的裁决结果：摆放 y（px）+ 配套的缩放原点 */
private data class AnchorPlacement(val y: Float, val origin: TransformOrigin)

/**
 * 锚定菜单「从触发控件右中（箭头处）弹出」的**一次性裁决** —— 布局与绘制共用。
 *
 * 布局期（[SubcomposeLayout]）用它定卡片 y；绘制期（`graphicsLayer`）取配套算出的
 * 缩放原点。两边拿到的 `cardH` 是同一张卡片量出来的同一个值，其余入参也一致，
 * 所以**位置与原点必然配套**：原点永远落在离箭头更近的那个右角上 ——
 * 卡片整体在箭头下方就是右上角（往左下长），在上方就是右下角（往左上长）。
 *
 * 规则（沿用「贴箭头展开」的思路，锚点从控件底边换成控件竖直中心）：
 *  - 下方放得下 → 卡片**顶边**贴箭头，从右上角往左下长；
 *  - 下方放不下、上方放得下 → 卡片**底边**贴箭头，从右下角往左上长；
 *  - 两头都放不下（菜单比一屏还高，理论上到不了）→ 整体收进屏幕留白，
 *    箭头离哪个角近就按哪个角缩放。
 */
private fun resolveAnchorPlacement(
    cardH: Float,
    centerY: Float,
    screenHeight: Float,
    marginPx: Float,
): AnchorPlacement {
    val lo = marginPx
    val hi = (screenHeight - cardH - marginPx).coerceAtLeast(marginPx)
    return when {
        // 优先：顶边贴箭头、向下展开（「从右上往左下」）
        centerY + cardH + marginPx <= screenHeight ->
            AnchorPlacement(centerY, TransformOrigin(1f, 0f))
        // 下方放不下：底边贴箭头、向上展开（「从右下往左上」）
        centerY - cardH >= marginPx ->
            AnchorPlacement(centerY - cardH, TransformOrigin(1f, 1f))
        // 两头都放不下：收进留白，取离箭头更近的右角
        else -> {
            val y = centerY.coerceIn(lo, hi)
            val origin = if (abs(centerY - y) <= abs(centerY - (y + cardH))) {
                TransformOrigin(1f, 0f)
            } else {
                TransformOrigin(1f, 1f)
            }
            AnchorPlacement(y, origin)
        }
    }
}

/** 选择类弹窗入场时的起始缩放（只做缩放、不做透明度，避免「空白卡片」观感） */
private const val AppearScale = 0.80f

/**
 * 遮罩最大不透明度；入场时从 0 渐变到这个值。
 *
 * 2.9.3 由 0.32 加深到 0.48：用户反馈「压黑不够」，弹窗与身后内容的对比不足。
 * 两种弹窗（锚定菜单 / 贴底确认）共用同一个遮罩，所以主题设置与重置模块
 * 会同步加深。
 */
private const val ScrimAlpha = 0.48f

/** 弹窗圆角（对齐 Miuix 对话框的观感） */
private val DialogCorner = 28.dp

/** 锚定式弹窗（下拉菜单）的固定宽度 */
private val MenuWidth = 200.dp

/**
 * 锚定菜单的**最大高度**（占屏幕高度的比例）。
 *
 * 取值项目录有 18 项，不管一下的话菜单会一路长到接近满屏 —— 既看不出「这是个菜单」，
 * 又把身后的界面全盖住（用户反馈「弹窗高度已接近占满整个屏幕」）。
 * 超过这个高度就由内容自己滚动（[DialogBody] 的选择列表带了 `verticalScroll`）。
 */
private const val MenuMaxHeightFraction = 0.45f

/** 卡片与屏幕边缘的最小距离；同时也是底部弹窗的左右留白（= 主页卡片的 `cardPadding`） */
private val ScreenMargin = 12.dp

/**
 * 弹窗宿主 —— 挂在 `MainScreen` **最外层 `Box`** 的最后一个子节点上。
 *
 * 之所以不在 `Scaffold` 的 content 里：锚点用的是窗口坐标，而 content 的坐标系
 * 被顶栏整体下移了一段，直接用会整体偏上。挂在最外层既对齐了坐标系，
 * 也保证弹窗压住顶栏 / 底栏 / 内容。
 *
 * @param backdrop 与顶栏 / 底栏同一个内容图层，玻璃卡片靠它采样身后的画面
 * @param liquidGlass 「液态玻璃」总开关
 * @param effect     「玻璃光效」开关
 */
@Composable
internal fun DialogHost(
    controller: DialogController,
    backdrop: LayerBackdrop,
    liquidGlass: Boolean,
    effect: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = controller.spec ?: return
    val dark = LocalDarkTheme.current
    val density = LocalDensity.current
    val marginPx = with(density) { ScreenMargin.toPx() }
    val menuWidthPx = with(density) { MenuWidth.roundToPx() }
    // 底部弹窗要抬到导航条之上
    val navBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()

    val anchor = spec.anchor
    // 贴底弹出：确认弹窗 / 同意弹窗，且没有锚点
    val sheet = (spec is ConfirmDialogSpec || spec is AgreementDialogSpec) && anchor == null

    // 进度：0 = 收起（初始态）、1 = 完全展开。
    // `remember(spec)` 让每换一个弹窗都重新从 0 开始 —— 于是「入场」天然成立，
    // 不会先按旧值画一帧再跳回去。
    val progress = remember(spec) { Animatable(0f) }

    // 透明度进度：**独立于 progress 的第二条动画**（2.9.4）。
    //
    // 主题 / 语言这类锚定菜单，除缩放外还要「渐隐渐出」：
    // 入场 0 → 1、出场 1 → 0。刻意用**单独的 Animatable** 驱动、跑在各自的协程里，
    // 与缩放那条线**互不读写**（时长也各自独立）—— 两条动画叠加显示，
    // 但谁也不会把对方拽慢、打断或改写。
    // 贴底确认弹窗（重置）不走渐隐，起始值直接取 1，行为与 2.9.3 一致。
    val isMenu = spec is ChoiceDialogSpec
    val fade = remember(spec) { Animatable(if (isMenu) 0f else 1f) }

    // 锚定菜单的缩放原点：布局期按「卡片落在箭头上方还是下方」裁决
    // （resolveAnchorPlacement）写进来，绘制期读同一份值 —— 同一帧里布局先于绘制，
    // 首帧画出来的就是与位置配套的原点，不会闪错展开方向。
    var menuOrigin by remember(spec) { mutableStateOf(TransformOrigin(1f, 0f)) }

    LaunchedEffect(spec) {
        // 入场曲线按形态分：锚定菜单走缩放（区间小，过冲可以给足），
        // 贴底弹窗走位移（区间是整卡高度，过冲必须压小）—— 见两条曲线的 KDoc。
        val enterEasing = if (isMenu) AppleMenuEnterEasing else AppleSheetEnterEasing
        progress.animateTo(1f, tween(DialogMillis, easing = enterEasing))
    }
    // 渐显：自己的一条协程，独立于上面的缩放动画
    LaunchedEffect(spec) {
        if (isMenu) fade.animateTo(1f, tween(DialogFadeMillis, easing = LinearOutSlowInEasing))
    }
    // 出场：缩放 1 → 0（锚定菜单缩回右缘中点 / 贴底弹窗向下滑走）+ 透明 1 → 0（仅锚定菜单），
    // 两条并发跑，**都跑完**才真正清空 spec。
    // 出场统一走苹果风 easeInOut（[AppleExitEasing]）—— 出场方向不能回弹，见其 KDoc。
    LaunchedEffect(controller.closing) {
        if (!controller.closing) return@LaunchedEffect
        val scaleDone = launch {
            progress.animateTo(0f, tween(DialogMillis, easing = AppleExitEasing))
        }
        val fadeDone = if (isMenu) {
            launch { fade.animateTo(0f, tween(DialogFadeMillis, easing = AppleExitEasing)) }
        } else {
            null
        }
        scaleDone.join()
        fadeDone?.join()
        controller.finishDismiss()
    }

    // 系统返回键：弹窗开着时先关弹窗（后注册的 BackHandler 优先）。
    // 不可关闭的弹窗（首次启动同意）这里**只吞掉返回**，不放行给下层 ——
    // 否则一按返回就退出 App，用户会以为模块坏了。
    BackHandler(enabled = true) { if (spec.dismissible) controller.dismiss() }

    val p = progress.value
    val fadeValue = fade.value

    Box(modifier.fillMaxSize()) {
        // 遮罩：**所有弹窗都有**，且随 progress「渐变压黑」——
        // 出现时由透明渐变到 ScrimAlpha，关闭时再渐变回透明。
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = ScrimAlpha * p)),
        )
        // 点空白处关闭。刻意不要水波纹（indication = null）。
        // 不可关闭的弹窗不加这层 —— 加了就等于给了绕过的口子。
        if (spec.dismissible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { controller.dismiss() },
                    ),
            )
        }

        // 先量卡片、再算位置，一次成型
        SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val side = marginPx.toInt()
            val cap = if (anchor != null) {
                menuWidthPx.coerceAtMost((width - side * 2).coerceAtLeast(0))
            } else {
                // 贴底弹窗与居中弹窗都吃满「卡片宽度」
                (width - side * 2).coerceAtLeast(0)
            }

            val measurable = subcompose("card") {
                DialogCard(
                    controller = controller,
                    spec = spec,
                    backdrop = backdrop,
                    liquidGlass = liquidGlass,
                    effect = effect,
                    dark = dark,
                    progress = p,
                    fade = fadeValue,
                    sheet = sheet,
                    menuOrigin = menuOrigin,
                )
            }.first()
            val placeable = measurable.measure(
                Constraints(
                    // 锚定菜单定宽；贴底弹窗铺满卡片宽度
                    minWidth = if (anchor != null || sheet) cap else 0,
                    maxWidth = cap,
                    // 高度封顶：锚定菜单取屏幕的 60%（超出部分内容自己滚），
                    // 贴底 / 居中弹窗只保证不越过上下留白
                    maxHeight = if (anchor != null) {
                        (height * MenuMaxHeightFraction).toInt().coerceAtLeast(side)
                    } else {
                        (height - side * 2).coerceAtLeast(side)
                    },
                ),
            )
            val cardW = placeable.width
            val cardH = placeable.height

            val x: Int
            val y: Int
            when {
                // 贴底：左右各留 ScreenMargin，底部再抬起导航条高度
                sheet -> {
                    x = side
                    y = (height - cardH - side - navBottomPx.toInt()).coerceAtLeast(side)
                }

                anchor != null -> {
                    // 卡片右边缘与触发控件右边缘对齐
                    x = (anchor.right - cardW)
                        .coerceIn(
                            marginPx,
                            (width - cardW - marginPx).coerceAtLeast(marginPx),
                        )
                        .toInt()
                    // 从触发控件「右中」（箭头处）弹出：位置与缩放原点用同一把尺裁决
                    // （resolveAnchorPlacement），原点写回 menuOrigin 供绘制期取用。
                    val centerY = (anchor.top + anchor.bottom) / 2f
                    val placement = resolveAnchorPlacement(
                        cardH = cardH.toFloat(),
                        centerY = centerY,
                        screenHeight = height.toFloat(),
                        marginPx = marginPx,
                    )
                    menuOrigin = placement.origin
                    y = placement.y.toInt()
                }

                else -> {
                    x = (width - cardW) / 2
                    y = (height - cardH) / 2
                }
            }
            layout(width, height) { placeable.place(x, y) }
        }
    }
}

/**
 * 卡片本体：材质 + 内容 + 入场 / 出场变换。
 *
 * @param progress 0 = 收起、1 = 完全展开（驱动缩放 / 位移）
 * @param fade     0 = 全透明、1 = 不透明（驱动 alpha，仅锚定菜单用；贴底弹窗恒 1）
 * @param sheet    `true` = 贴底弹窗（纵向滑入滑出），`false` = 锚定菜单（贴箭头缩放 + 渐隐渐出）
 * @param menuOrigin 锚定菜单的缩放原点（布局期按箭头方位裁决，见 [resolveAnchorPlacement]）；贴底弹窗不用
 */
@Composable
private fun DialogCard(
    controller: DialogController,
    spec: DialogSpec,
    backdrop: LayerBackdrop,
    liquidGlass: Boolean,
    effect: Boolean,
    dark: Boolean,
    progress: Float,
    fade: Float,
    sheet: Boolean,
    menuOrigin: TransformOrigin,
) {
    val shape = RoundedCornerShape(DialogCorner)
    Box(
        modifier = Modifier.graphicsLayer {
            if (sheet) {
                // 从屏幕下方滑上来；关闭时反向滑回去
                translationY = (1f - progress) * size.height
            } else {
                // 以**离箭头最近的右角**为原点缩放（原点由布局期裁决，见
                // [resolveAnchorPlacement]）：卡片在箭头下方 → 右上角固定，向左下长出来；
                // 翻到上方 → 右下角固定，向左上长出来。关闭时（progress 1 → 0）
                // 缩回同一个角 —— 入场 / 出场同一套变换。
                val s = AppearScale + (1f - AppearScale) * progress
                scaleX = s
                scaleY = s
                transformOrigin = menuOrigin
                // 渐隐渐出：alpha 走**另一条独立动画**（`fade`，见 DialogHost 的注释），
                // 与上面的缩放各跑各的、互不干扰。
                alpha = fade
            }
        },
    ) {
        // --- 卡片背景 ---
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (liquidGlass) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                if (effect) {
                                    vibrancy()
                                    blur(24.dp.toPx())
                                }
                            },
                            shadow = { Shadow(radius = 24.dp, alpha = 0.30f) },
                            onDrawSurface = { drawRect(glassSurfaceTint(dark, effect)) },
                        )
                    } else {
                        Modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh, shape)
                    }
                ),
        )

        // --- 内容：点它不应该关闭弹窗，所以再叠一层「吃掉点击」的容器 ---
        //
        // `.clip(shape)`（2.9.4）：Miuix 的按压高亮（`MiuixIndicationInstance.draw`
        // 里就是给整个节点 `drawRect` 一层色）会铺满**整行**（`ChoiceRow` 是满宽），
        // 首 / 末行的矩形会越过卡片的 28dp 圆角、糊到弹窗之外。这里把内容层按
        // 卡片形状裁一刀，高亮和其它子节点绘制全部限制在弹窗范围内；
        // 只裁内容层、不裁外层 —— 外层的 `drawBackdrop` 还画着外投影，裁了会连投影一起切掉。
        Box(
            modifier = Modifier
                .clip(shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            DialogBody(controller, spec)
        }
    }
}
