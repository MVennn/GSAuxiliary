package io.github.MVennn.GSAuxiliary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.launch

/**
 * 自绘弹窗（Miuix 风格 + 可选液态玻璃）。
 *
 * ## 为什么自己画，不用 Miuix 的 `OverlayDropdownPreference` / `OverlayDialog`
 *
 * Miuix 的弹层由 `Scaffold` 内部的 `MiuixPopupHost` 渲染，而它的弹层实现会在
 * 自己的 `onClick` 里**同步开始收起动画**，此时任何一次配置写入（`commit()` →
 * `OnSharedPreferenceChangeListener` 同步回调 → Compose 状态改写）都会把弹层
 * 入参换掉，动画与重组打架 → 崩。历史上一共两条独立根因，其中一条治不干净。
 *
 * 换成**自绘覆盖层**之后，弹层只是普通 Composition 的一部分：
 *  - 没有「库自己的收起动画」，关闭就是一次普通的状态翻转；
 *  - 层级完全由我们决定（画在最外层 Box 的最后一个子节点，天然压住顶栏 / 底栏）；
 *  - 液态玻璃能直接用宿主那边的 [LayerBackdrop]，和顶栏 / 底栏是同一套材质。
 *
 * ## 两种形态（2.9.2 分化；2.9.4 锚定菜单补渐隐渐出）
 *
 * | | [ChoiceDialogSpec]（主题 / 语言 / 日志等级） | [ConfirmDialogSpec]（重置）<br>[AgreementDialogSpec]（首次启动同意） |
 * |---|---|---|
 * | 定位 | **锚定触发控件**右下方，放不下翻右上方 | **贴屏幕底部**，宽度 = 主页卡片宽 |
 * | 入场 | 以**右上角**为原点缩放展开 + **渐显**（alpha 0 → 1） | 从屏幕下方**向上滑入** |
 * | 出场 | 向**右上角**缩回 + **渐隐**（alpha 1 → 0） | 向**下滑出** |
 * | 标题 | **没有**（2.9.2 去掉，选项本身已经说明一切） | 有（居中；主标题 `title4` 18sp + Medium、副标题 15sp，2.9.6 定稿） |
 *
 * ## 动画：缩放与透明度**各自独立**（2.9.4）
 *
 * 锚定菜单的入场 / 出场由两条**互不读写**的动画叠成：
 *  - 缩放：`progress`（`Animatable`）驱动 `scaleX/scaleY` + `TransformOrigin(1f, 0f)`；
 *  - 透明度：`fade`（**另一个** `Animatable`）驱动 `alpha`，渐显 / 渐隐。
 *
 * 两条进度分别跑在自己的协程里（入场是两个 `LaunchedEffect`，出场是两个 `launch`
 * 并发跑完再 `finishDismiss`），时长也各自独立 —— 缩放调整不会连带改掉渐隐的节奏，
 * 反之亦然。刻意**不**复用同一个进度：那等于把两种动画焊死，动一个必动另一个。
 * 贴底确认弹窗不使用 alpha 渐隐，`fade` 恒为 1，行为与 2.9.3 完全一致。
 *
 * ## 出场动画与 `closing` 标记
 *
 * 两种形态的出场都要播动画，所以 [DialogController.dismiss] 统一只置 `closing`，
 * **不立刻清空 spec** —— 退出期间内容必须还在，否则会渲染出一张空卡片
 * （用户反馈过的「关闭时先出现空白弹窗」）。动画跑完由 [DialogController.finishDismiss]
 * 真正清空。
 *
 * ## 为什么用 `SubcomposeLayout`
 *
 * 锚定定位需要**先知道卡片尺寸**才能算坐标。常见写法是「先摆在中间，量到尺寸后再
 * 用 offset 挪过去」，那会露出「先出现在错误位置再跳一下」的空窗。
 * [SubcomposeLayout] 允许先量卡片、再决定摆放位置，**一次成型**。
 *
 * ## 动画：只用 `tween`，**没有弹簧**
 *
 * 入场 / 出场全部走 `tween` + 单段缓动（入场 `LinearOutSlowInEasing` 减速落位，
 * 出场 `FastOutLinearInEasing` 加速离场），没有任何 `spring` 或回弹过冲。
 *
 * ## 液态玻璃
 *
 * 由「液态玻璃」总开关控制：
 *  - **开** → 玻璃卡片（`drawBackdrop`：模糊 + 增艳 + 半透明面色 + 外投影），
 *    面色同样走 [glassSurfaceTint]，所以深色是黑玻璃、浅色是白玻璃；
 *  - **关** → 纯色卡片（`surfaceContainerHigh`），
 *    它在 Miuix 里与页面底色 `surface` 有明显对比（深色 #242424 / 浅色 #E8E8E8）。
 */

/** 弹窗内容的公共接口：都带一个可选的「触发控件位置」，用来做锚定定位 */
internal sealed interface DialogSpec {

    /** 触发控件在**窗口坐标系**里的矩形；`null` = 不锚定（贴底 / 居中） */
    val anchor: Rect?

    /**
     * 是否允许「点空白 / 按返回」关掉。
     *
     * 默认 `true`。**首次启动的免责声明同意弹窗是 `false`** ——
     * 它是硬门槛：没同意就不能用，不能靠点一下旁边溜过去。
     */
    val dismissible: Boolean get() = true
}

/** 一个「单选」弹窗：若干选项 + 当前选中项。**没有标题**（2.9.2 去掉） */
internal class ChoiceDialogSpec(
    val items: List<String>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit,
    override val anchor: Rect? = null,
) : DialogSpec

/** 一个「确认 / 取消」弹窗：标题 + 说明 + 两个按钮，贴屏幕底部弹出 */
internal class ConfirmDialogSpec(
    val title: String,
    val message: String,
    val confirmText: String,
    val cancelText: String,
    val onConfirm: () -> Unit,
    override val anchor: Rect? = null,
) : DialogSpec

/**
 * 「首次启动必须同意免责声明」弹窗。
 *
 * 与 [ConfirmDialogSpec] 的区别（也是它必须单独一个类的原因）：
 *  - **没有取消按钮**，只有一颗「同意」，且**未勾选时置灰点不动**；
 *  - 说明文字里嵌一个**可点击的链接**（「免责声明」），点了跳二级页全文；
 *  - [dismissible] = `false`，点空白 / 按返回都关不掉。
 *
 * 文案拆成「前导 + 链接 + 后接」三段，链接文字由调用方给（便于本地化，
 * 而且链接在句子里的位置各语言不一样，不能写死拼接顺序）。
 */
internal class AgreementDialogSpec(
    val title: String,
    val messagePrefix: String,
    val linkText: String,
    val messageSuffix: String,
    val checkLabel: String,
    val confirmText: String,
    val onOpenDisclaimer: () -> Unit,
    val onConfirm: () -> Unit,
) : DialogSpec {

    override val anchor: Rect? = null

    override val dismissible: Boolean = false
}

/**
 * 弹窗宿主状态。
 *
 * 放在 `HomeScaffold` 里 remember 一份，通过 [LocalDialogController] 下发给各 Tab
 * （设置页的三个选择项、重置确认都从这里弹）。
 */
internal class DialogController {

    /** 当前要显示的弹窗；`null` = 不显示 */
    var spec by mutableStateOf<DialogSpec?>(null)
        private set

    /**
     * 是否正在播放「收起」动画。
     *
     * 为 `true` 时 **spec 仍然保留** —— 退出动画期间内容必须还在，
     * 否则会渲染出一张没有内容的空卡片（用户反馈过的「关闭时先出现空白弹窗」）。
     * 动画跑完由 [finishDismiss] 真正清空。
     */
    var closing by mutableStateOf(false)
        private set

    fun showChoice(s: ChoiceDialogSpec) {
        closing = false
        spec = s
    }

    fun showConfirm(s: ConfirmDialogSpec) {
        closing = false
        spec = s
    }

    /** 弹出「首次启动必须同意免责声明」（不可关闭） */
    fun showAgreement(s: AgreementDialogSpec) {
        // 已经在显示同一个同意弹窗时不要再赋值：`remember(spec)` 的勾选状态会被重置，
        // 用户勾了之后如果外层恰好重组一次，勾就会莫名其妙掉回未勾选。
        if (spec is AgreementDialogSpec && !closing) return
        closing = false
        spec = s
    }

    /**
     * 关闭弹窗 —— **两种形态都走退出动画**（2.9.3 起）。
     *
     * 标记 [closing]，由宿主播完「缩回 / 下滑」动画后再 [finishDismiss] 清空。
     * 之所以不再让选择类弹窗「立即置空」：那样只有展开动画、没有缩回动画，
     * 用户反馈「点掉之后弹窗是凭空消失的」，缺少收束感。
     *
     * 退出期间 [spec] 仍然保留，所以不会渲染出空壳卡片。
     */
    fun dismiss() {
        if (spec == null) return
        closing = true
    }

    /** 退出动画播完，真正清空（只由宿主调用） */
    fun finishDismiss() {
        spec = null
        closing = false
    }
}

/** 下发给各 Tab 的弹窗控制器；没有宿主时为 null（调用点做空判断） */
internal val LocalDialogController = compositionLocalOf<DialogController?> { null }

/** 入场 / 出场动画时长（缩放 / 位移那条线） */
private const val DialogMillis = 220

/**
 * 锚定菜单「渐隐渐出」的时长 —— 透明度那条线。
 *
 * 与 [DialogMillis] **互相独立**：两条动画各自跑自己的时长，改一个不会拖着另一个走。
 * 比缩放略短一点，让「显影」先完成、缩放再稳稳落位。
 */
private const val DialogFadeMillis = 180

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

/** 卡片与屏幕边缘的最小距离；同时也是底部弹窗的左右留白（= 主页卡片的 `cardPadding`） */
private val ScreenMargin = 12.dp

/** 锚定卡片与触发控件之间的间距 */
private val AnchorGap = 6.dp

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
    val gapPx = with(density) { AnchorGap.toPx() }
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
    // 主题 / 语言 / 日志等级这类锚定菜单，除缩放外还要「渐隐渐出」：
    // 入场 0 → 1、出场 1 → 0。刻意用**单独的 Animatable** 驱动、跑在各自的协程里，
    // 与缩放那条线**互不读写**（时长也各自独立）—— 两条动画叠加显示，
    // 但谁也不会把对方拽慢、打断或改写。
    // 贴底确认弹窗（重置）不走渐隐，起始值直接取 1，行为与 2.9.3 一致。
    val isMenu = spec is ChoiceDialogSpec
    val fade = remember(spec) { Animatable(if (isMenu) 0f else 1f) }

    LaunchedEffect(spec) {
        progress.animateTo(1f, tween(DialogMillis, easing = LinearOutSlowInEasing))
    }
    // 渐显：自己的一条协程，独立于上面的缩放动画
    LaunchedEffect(spec) {
        if (isMenu) fade.animateTo(1f, tween(DialogFadeMillis, easing = LinearOutSlowInEasing))
    }
    // 出场：缩放 1 → 0（锚定菜单缩回右上角 / 贴底弹窗向下滑走）+ 透明 1 → 0（仅锚定菜单），
    // 两条并发跑，**都跑完**才真正清空 spec。
    LaunchedEffect(controller.closing) {
        if (!controller.closing) return@LaunchedEffect
        val scaleDone = launch {
            progress.animateTo(0f, tween(DialogMillis, easing = FastOutLinearInEasing))
        }
        val fadeDone = if (isMenu) {
            launch { fade.animateTo(0f, tween(DialogFadeMillis, easing = FastOutLinearInEasing)) }
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
                )
            }.first()
            val placeable = measurable.measure(
                Constraints(
                    // 锚定菜单定宽；贴底弹窗铺满卡片宽度
                    minWidth = if (anchor != null || sheet) cap else 0,
                    maxWidth = cap,
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
                    val below = anchor.bottom + gapPx
                    y = if (below + cardH + marginPx <= height) {
                        // 优先「控件右下方」
                        below.toInt()
                    } else {
                        // 下方放不下 → 「控件右上方」
                        (anchor.top - gapPx - cardH).coerceAtLeast(marginPx).toInt()
                    }
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
 * @param sheet    `true` = 贴底弹窗（纵向滑入滑出），`false` = 锚定菜单（右上角缩放 + 渐隐渐出）
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
) {
    val shape = RoundedCornerShape(DialogCorner)
    Box(
        modifier = Modifier.graphicsLayer {
            if (sheet) {
                // 从屏幕下方滑上来；关闭时反向滑回去
                translationY = (1f - progress) * size.height
            } else {
                // 以**右上角**为原点缩放：展开时向下 + 向左「长」出来，
                // 关闭时（progress 1 → 0）再缩回右上角 —— 入场 / 出场同一套变换。
                val s = AppearScale + (1f - AppearScale) * progress
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(1f, 0f)
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

@Composable
private fun DialogBody(controller: DialogController, spec: DialogSpec) {
    when (spec) {
        // 选择类弹窗**不画标题**（2.9.2 去掉）——选项本身已经说明这是哪个设置项，
        // 多一行标题只会让下拉菜单变高、变啰嗦。
        is ChoiceDialogSpec -> Column(modifier = Modifier.padding(vertical = 8.dp)) {
            spec.items.forEachIndexed { index, label ->
                ChoiceRow(
                    label = label,
                    selected = index == spec.selectedIndex,
                    onClick = {
                        // 先关弹窗再落配置：写入走 editDeferred，
                        // 保证状态更新发生在关闭之后（与历史崩溃同一套防御）。
                        controller.dismiss()
                        spec.onSelect(index)
                    },
                )
            }
        }

        is ConfirmDialogSpec -> Column(
            // 纵向留白 2.9.3 由 22dp 加到 32dp：贴底弹窗铺满整条卡片宽度，
            // 内容却只有两行字 + 一排按钮，太紧就会「扁长」。
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = spec.title,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                // 主标题字号 = `title4`（**18sp**）+ Medium（2.9.6 定稿）：夹在
                // 「主页卡片标题 headline1 = 17sp」与「顶栏标题 title3 = 20sp」之间 ——
                // 17sp 观感偏低（2.9.4）、20sp 又过大（2.9.5），18sp 是这一档的稳定解，
                // 且正好是 Miuix 文字样式表里 17 与 20 之间的那一档。
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = spec.message,
                // 与标题**同色**（2.9.3）：之前正文用 onSurfaceContainerVariant、
                // 标题用 onSurfaceContainer，两者只差字号 —— 看上去像两种不同层级的
                // 信息，但这里正文就是标题的展开说明，用同一个颜色更干净。
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                // 副标题 15sp（2.9.6 定稿）：Miuix 文字样式表里没有 15 这一档
                // （body2 = 14、body1 = 16），按用户要求取二者中间，故这里直接写 sp。
                // 与主标题 18 : 15 的层级差足够，仍是 Normal 字重。
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // 按钮前留白 2.9.3 由 20dp 加到 28dp（同上：把整体高度撑起来）
            Spacer(Modifier.height(28.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                // 间距 12 → 16dp（2.9.7）：两个按钮各**收窄 2dp**。总宽不变、外缘不动，
                // 只是中间让出 4dp —— 想让每个按钮自身变窄，只能从这一处（或整行两侧
                // 留白）扣，`weight(1f)` 的两颗按钮宽度是算出来的。
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DialogButton(
                    text = spec.cancelText,
                    emphasized = false,
                    onClick = { controller.dismiss() },
                    modifier = Modifier.weight(1f),
                )
                DialogButton(
                    text = spec.confirmText,
                    emphasized = true,
                    onClick = {
                        controller.dismiss()
                        spec.onConfirm()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        is AgreementDialogSpec -> {
            // 勾选状态放这里：`remember(spec)` 让「换一个新的同意弹窗」时回到未勾选
            var checked by remember(spec) { mutableStateOf(false) }
            val colors = MiuixTheme.colorScheme

            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = spec.title,
                    color = colors.onSurfaceContainer,
                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                // 说明文字：中间那段「免责声明」是可点链接，样式与主题主色一致
                val message = buildAnnotatedString {
                    append(spec.messagePrefix)
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "disclaimer",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = colors.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    textDecoration = TextDecoration.Underline,
                                )
                            ),
                            linkInteractionListener = { spec.onOpenDisclaimer() },
                        )
                    ) {
                        append(spec.linkText)
                    }
                    append(spec.messageSuffix)
                }
                Text(
                    text = message,
                    color = colors.onSurfaceContainer,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(18.dp))
                AgreementCheckRow(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    label = spec.checkLabel,
                )
                Spacer(Modifier.height(18.dp))

                // 只有一颗按钮，且未勾选时不可点
                DialogButton(
                    text = spec.confirmText,
                    emphasized = true,
                    enabled = checked,
                    onClick = {
                        controller.dismiss()
                        spec.onConfirm()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 单选弹窗里的一行：左标题、右对勾 */
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceContainer
            },
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            // 占位，保证未选中行与选中行的文字基线一致
            Spacer(Modifier.size(18.dp))
        }
    }
}

/**
 * 确认弹窗里的按钮。
 *
 * **强调态（确认）统一用 Miuix 主题色**：底色直接铺 `primary`、文字用 `onPrimary`
 * —— 与 Miuix 自己的 `ButtonDefaults.buttonColorsPrimary()` 观感一致（2.9.2）。
 *
 * 纵向内边距 2.9.7 由 11dp 加到 **12dp**：按钮整体**加高 2dp**（上下各 1dp）。
 * 宽度由确认弹窗那行 `Row` 的间距控制（见 `DialogBody`）。
 */
@Composable
private fun DialogButton(
    text: String,
    emphasized: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** `false` = 置灰且点不动（未勾选同意时用） */
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colorScheme
    // 禁用态：主色降到 38% 透明度，既明显"不可用"，又还看得出是主按钮
    val bg = when {
        !enabled -> colors.primary.copy(alpha = 0.38f)
        emphasized -> colors.primary
        else -> colors.surfaceContainerHighest.copy(alpha = 0.9f)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = when {
                !enabled -> colors.onPrimary.copy(alpha = 0.7f)
                emphasized -> colors.onPrimary
                else -> colors.onSurfaceContainer
            },
            fontSize = 15.sp,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 同意弹窗底部的勾选行。
 *
 * 勾选框**自绘**（Miuix 0.9.x 的 basic 包里没有 Checkbox）：
 * 未选中 = 描边空心方块，选中 = 主色实心 + 对勾。
 * 整行可点 —— 20dp 的方块对拇指太小，把点击区扩到整行（含文字）。
 */
@Composable
private fun AgreementCheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) colors.primary else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (checked) colors.primary else colors.outline,
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = colors.onSurfaceContainer,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            modifier = Modifier.weight(1f),
        )
    }
}
