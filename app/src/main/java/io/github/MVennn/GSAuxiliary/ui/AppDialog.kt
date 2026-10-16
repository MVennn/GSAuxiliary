package io.github.MVennn.GSAuxiliary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 自绘弹窗（Miuix 风格 + 可选液态玻璃）。
 *
 * ## 为什么「一半自绘、一半用库」
 *
 * Miuix 的下拉弹层（`OverlayDropdownPreference` / `OverlayDialog`，0.3.x 时代）会在
 * 自己的 `onClick` 里**同步开始收起动画**，此时任何一次配置写入（`commit()` →
 * `OnSharedPreferenceChangeListener` 同步回调 → Compose 状态改写）都会把弹层
 * 入参换掉，动画与重组打架 → 崩。历史上一共两条独立根因，其中一条治不干净。
 *
 * 所以**锚定菜单**（主题 / 语言 / 日志等级）维持自绘覆盖层：
 *  - 没有「库自己的收起动画」，关闭就是一次普通的状态翻转；
 *  - 层级完全由我们决定（画在最外层 Box 的最后一个子节点，天然压住顶栏 / 底栏）；
 *  - 液态玻璃能直接用宿主那边的 [LayerBackdrop]，和顶栏 / 底栏是同一套材质。
 *
 * **贴底弹窗**（重置确认 / 免责声明同意）则换用 Miuix 0.9.3 的
 * `OverlayBottomSheet`（见 [BottomSheetDialogHost]）：它有自绘版做不出来的
 * 原生手感 —— folme 弹簧上滑 / 下滑、把手拖拽关闭、预测性返回手势。当年的崩溃
 * 路径对它不成立：收起由**我们的 `show` 状态**驱动（不是库在 onClick 里自作主张），
 * 确认回调全部走 `rememberUpdatedState`，且重置的配置写入本来就是延迟落盘
 * （`resetAllFeaturesDeferred`），动画期间没有同步入参改写。它渲染进 `Scaffold`
 * 内置的 `MiuixPopupHost`，所以**调用点必须在 Scaffold 的 composition 范围内**。
 *
 * ## 两种形态
 *
 * | | [ChoiceDialogSpec]（主题 / 语言 / 日志等级） | [ConfirmDialogSpec]（重置）<br>[AgreementDialogSpec]（首次启动同意） |
 * |---|---|---|
 * | 实现 | 自绘覆盖层（本文件 `DialogHost`） | Miuix `OverlayBottomSheet`（[BottomSheetDialogHost]） |
 * | 定位 | **从触发控件右中（箭头处）弹出**：优先卡片顶边贴箭头向下展开，下方放不下翻到底边贴箭头向上展开 | **贴屏幕底部**（Miuix 原生），左右留白 = 主页卡片宽 |
 * | 入场 | 以**离箭头最近的右角**为原点缩放展开 + **渐显**（alpha 0 → 1），苹果风回弹曲线<br>（卡片在箭头下方 → 右上角往左下长；翻到上方 → 右下角往左上长） | folme 弹簧从屏幕下方**向上滑入**（库动画） |
 * | 出场 | 向该右角**缩回** + **渐隐**（alpha 1 → 0），苹果风 easeInOut | folme 弹簧**下滑出屏**（库动画），把手可拖拽关闭 |
 * | 标题 | **没有**（2.9.2 去掉，选项本身已经说明一切） | 有（库的标题行，`title4` 18sp + Medium 居中，与 2.9.6 定稿同规格） |
 *
 * ## 动画：缩放与透明度**各自独立**（2.9.4）
 *
 * 锚定菜单的入场 / 出场由两条**互不读写**的动画叠成：
 *  - 缩放：`progress`（`Animatable`）驱动 `scaleX/scaleY` + `TransformOrigin`
 *    （原点由布局期裁决：卡片在箭头下方 → 右上角、翻到上方 → 右下角，见 [resolveAnchorPlacement]）；
 *  - 透明度：`fade`（**另一个** `Animatable`）驱动 `alpha`，渐显 / 渐隐。
 *
 * 两条进度分别跑在自己的协程里（入场是两个 `LaunchedEffect`，出场是两个 `launch`
 * 并发跑完再 `finishDismiss`），时长也各自独立 —— 缩放调整不会连带改掉渐隐的节奏，
 * 反之亦然。刻意**不**复用同一个进度：那等于把两种动画焊死，动一个必动另一个。
 *
 * 贴底弹窗换 `OverlayBottomSheet` 后不再吃这套 tween 曲线 —— 它的滑入 / 滑出 /
 * 拖拽全是库的 folme 弹簧，与自绘动画互不相干。
 *
 * ## 出场动画与 `closing` 标记
 *
 * 两种形态的出场都要播动画，所以 [DialogController.dismiss] 统一只置 `closing`，
 * **不立刻清空 spec** —— 退出期间内容必须还在，否则会渲染出一张空卡片
 * （用户反馈过的「关闭时先出现空白弹窗」）。动画跑完由 [DialogController.finishDismiss]
 * 真正清空；`OverlayBottomSheet` 那边对应的时点是 `onDismissFinished`。
 *
 * ## 为什么用 `SubcomposeLayout`
 *
 * 锚定定位需要**先知道卡片尺寸**才能算坐标。常见写法是「先摆在中间，量到尺寸后再
 * 用 offset 挪过去」，那会露出「先出现在错误位置再跳一下」的空窗。
 * [SubcomposeLayout] 允许先量卡片、再决定摆放位置，**一次成型**。
 *
 * ## 动画：锚定菜单只用 `tween` + 苹果风贝塞尔曲线，**没有弹簧**
 *
 * 入场 / 出场全部走 `tween` + 单段缓动，曲线是「苹果风味」的
 * `CubicBezierEasing`：入场带一点 easeOutBack 式回弹过冲（缩放区间小，
 * 折算下来约 2%），落位稳稳收住；出场 easeInOut（`UIView.animate` 的默认曲线）
 * 平滑离场。仍然没有任何 `spring`：时长可预期，而且出场方向一旦回弹就会冲过 0
 * （缩得比起始还小 / 滑出屏幕又弹回来）。
 *
 * ## 液态玻璃
 *
 * 两种弹窗都由「液态玻璃」总开关控制：
 *  - **开** → 玻璃卡片（`drawBackdrop`：模糊 + 增艳 + 半透明面色 + 外投影），
 *    面色同样走 [glassSurfaceTint]，所以深色是黑玻璃、浅色是白玻璃。
 *    贴底弹窗的玻璃从 `OverlayBottomSheet` 的 `modifier` 注入 —— 那个 modifier
 *    插在库的 `squircleSurface(纯色)` 之前，玻璃能铺满整张 sheet（含把手区）；
 *  - **关** → 纯色卡片（`surfaceContainerHigh`，与 2.9.x 自绘版同色），
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
 *  - 勾选行的文字里嵌一个**可点击的链接**（「免责声明」），点了跳二级页全文；
 *  - [dismissible] = `false`，点空白 / 按返回都关不掉。
 *
 * 说明文字 [message] 是**纯文本**（免责声明入口不在副标题里），链接挪到勾选行：
 * 勾选行拆成「前导 + 链接 + 后接」三段，链接文字由调用方给（便于本地化，
 * 而且链接在句子里的位置各语言不一样，不能写死拼接顺序）。
 */
internal class AgreementDialogSpec(
    val title: String,
    /** 说明文字（纯文本，不含任何链接） */
    val message: String,
    /** 勾选行：前导文字（自带尾随空格） */
    val checkPrefix: String,
    /** 勾选行：可点击的「免责声明」链接文字 */
    val checkLinkText: String,
    /** 勾选行：后接文字（自带前导空格） */
    val checkSuffix: String,
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

/**
 * 苹果风入场曲线 —— **锚定菜单**（缩放）用。
 *
 * easeOutBack 族：前段快、后段带一点回弹过冲再收住。缩放的动画区间只有
 * `AppearScale(0.80) → 1` 这 0.2，曲线约 10% 的峰值过冲折算下来只是
 * ~2% 的轻微「弹一下」（峰值 ≈ 1.02），正好是 iOS 弹层那种落位手感。
 */
private val AppleMenuEnterEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

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

/** 卡片与屏幕边缘的最小距离；同时也是底部弹窗的左右留白（= 主页卡片的 `cardPadding`） */
private val ScreenMargin = 12.dp

/**
 * 锚定菜单宿主 —— 挂在 `MainScreen` **最外层 `Box`** 的最后一个子节点上。
 *
 * 之所以不在 `Scaffold` 的 content 里：锚点用的是窗口坐标，而 content 的坐标系
 * 被顶栏整体下移了一段，直接用会整体偏上。挂在最外层既对齐了坐标系，
 * 也保证弹窗压住顶栏 / 底栏 / 内容。
 *
 * 只渲染 [ChoiceDialogSpec]（主题 / 语言 / 日志等级的下拉菜单）；
 * 贴底弹窗（[ConfirmDialogSpec] / [AgreementDialogSpec]）由
 * [BottomSheetDialogHost] 接管 —— 见其 KDoc。
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
    // 贴底弹窗走 Miuix OverlayBottomSheet（BottomSheetDialogHost）—— 收起期间
    // spec 仍然保留，这里用类型判断互斥，谁都不画对方的形态。
    if (spec !is ChoiceDialogSpec) return
    val dark = LocalDarkTheme.current
    val density = LocalDensity.current
    val marginPx = with(density) { ScreenMargin.toPx() }
    val menuWidthPx = with(density) { MenuWidth.roundToPx() }

    val anchor = spec.anchor

    // 进度：0 = 收起（初始态）、1 = 完全展开。
    // `remember(spec)` 让每换一个弹窗都重新从 0 开始 —— 于是「入场」天然成立，
    // 不会先按旧值画一帧再跳回去。
    val progress = remember(spec) { Animatable(0f) }

    // 透明度进度：**独立于 progress 的第二条动画**（2.9.4）。
    //
    // 锚定菜单除缩放外还要「渐隐渐出」：入场 0 → 1、出场 1 → 0。刻意用**单独的
    // Animatable** 驱动、跑在各自的协程里，与缩放那条线**互不读写**（时长也各自
    // 独立）—— 两条动画叠加显示，但谁也不会把对方拽慢、打断或改写。
    val fade = remember(spec) { Animatable(0f) }

    // 锚定菜单的缩放原点：布局期按「卡片落在箭头上方还是下方」裁决
    // （resolveAnchorPlacement）写进来，绘制期读同一份值 —— 同一帧里布局先于绘制，
    // 首帧画出来的就是与位置配套的原点，不会闪错展开方向。
    var menuOrigin by remember(spec) { mutableStateOf(TransformOrigin(1f, 0f)) }

    LaunchedEffect(spec) {
        progress.animateTo(1f, tween(DialogMillis, easing = AppleMenuEnterEasing))
    }
    // 渐显：自己的一条协程，独立于上面的缩放动画
    LaunchedEffect(spec) {
        fade.animateTo(1f, tween(DialogFadeMillis, easing = LinearOutSlowInEasing))
    }
    // 出场：缩放 1 → 0（缩回离箭头最近的右角）+ 透明 1 → 0，
    // 两条并发跑，**都跑完**才真正清空 spec。
    // 出场统一走苹果风 easeInOut（[AppleExitEasing]）—— 出场方向不能回弹，见其 KDoc。
    LaunchedEffect(controller.closing) {
        if (!controller.closing) return@LaunchedEffect
        val scaleDone = launch {
            progress.animateTo(0f, tween(DialogMillis, easing = AppleExitEasing))
        }
        val fadeDone = launch {
            fade.animateTo(0f, tween(DialogFadeMillis, easing = AppleExitEasing))
        }
        scaleDone.join()
        fadeDone.join()
        controller.finishDismiss()
    }

    // 系统返回键：弹窗开着时先关弹窗（后注册的 BackHandler 优先）。
    BackHandler(enabled = true) { if (spec.dismissible) controller.dismiss() }

    val p = progress.value
    val fadeValue = fade.value

    Box(modifier.fillMaxSize()) {
        // 遮罩：随 progress「渐变压黑」——
        // 出现时由透明渐变到 ScrimAlpha，关闭时再渐变回透明。
        // （贴底弹窗的遮罩由 OverlayBottomSheet 自带，不走这里。）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = ScrimAlpha * p)),
        )
        // 点空白处关闭。刻意不要水波纹（indication = null）。
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { controller.dismiss() },
                ),
        )

        // 先量卡片、再算位置，一次成型
        SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val side = marginPx.toInt()
            val cap = if (anchor != null) {
                menuWidthPx.coerceAtMost((width - side * 2).coerceAtLeast(0))
            } else {
                // 无锚点兜底：吃满「卡片宽度」
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
                    menuOrigin = menuOrigin,
                )
            }.first()
            val placeable = measurable.measure(
                Constraints(
                    // 锚定菜单定宽
                    minWidth = if (anchor != null) cap else 0,
                    maxWidth = cap,
                ),
            )
            val cardW = placeable.width
            val cardH = placeable.height

            val x: Int
            val y: Int
            when {
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
 * 贴底弹窗宿主（Miuix `OverlayBottomSheet`）—— 重置确认 + 首次启动同意。
 *
 * ⚠️ **调用点必须在 Miuix `Scaffold` 的 composition 范围内**（HomeScaffold 的
 * content lambda 里即可）：`OverlayBottomSheet` 经 `MiuixPopupUtils.DialogLayout`
 * 把内容登记进 Scaffold 内置的 `MiuixPopupHost`，登记表存放在 Scaffold 提供的
 * `LocalRootDialogStates` 里 —— 在 Scaffold 外面调用拿到的是空表，
 * 弹层永远不显示。它与自绘的 [DialogHost] 读同一个 [DialogController]，
 * 只是渲染通道不同；两个宿主按 spec 类型互斥，谁都不画对方的形态。
 *
 * ## 与 `closing` / `finishDismiss` 的配合
 *
 * `show = spec 在且未在收起`。`dismiss()` 置 `closing` → `show` 翻 false →
 * 库播下滑动画，期间 spec 保留（不会闪空卡）；播完 `onDismissFinished` →
 * [DialogController.finishDismiss] 真正清空 —— 与自绘时代同一套语义。
 * 收起由**我们的 `show` 状态**驱动，不是库在 onClick 里自作主张 ——
 * 0.3.x 时代「同步收起动画 × 配置写入」的崩溃路径对它不成立；
 * 重置的配置写入本来就是延迟落盘（`resetAllFeaturesDeferred`），双保险。
 *
 * ## 玻璃材质跟随「液态玻璃」开关
 *
 *  - **开**：`backgroundColor` 给透明，玻璃从 `modifier` 注入 —— 那个 modifier
 *    插在库容器 `squircleSurface(纯色)` 之前，[drawBackdrop] 的玻璃
 *    （模糊 + 增艳 + 面色 + 外投影）铺满整张 sheet（含把手区），
 *    形状对齐库的顶部圆角（底部 0，sheet 贴屏幕底边）；
 *  - **关**：`surfaceContainerHigh` 纯色（与 2.9.x 自绘版同色）。
 */
@Composable
internal fun BottomSheetDialogHost(
    controller: DialogController,
    backdrop: LayerBackdrop,
    liquidGlass: Boolean,
    effect: Boolean,
) {
    val spec = controller.spec ?: return
    if (spec !is ConfirmDialogSpec && spec !is AgreementDialogSpec) return
    val dark = LocalDarkTheme.current

    OverlayBottomSheet(
        show = !controller.closing,
        title = when (spec) {
            is ConfirmDialogSpec -> spec.title
            is AgreementDialogSpec -> spec.title
            else -> null
        },
        backgroundColor = if (liquidGlass) {
            Color.Transparent
        } else {
            MiuixTheme.colorScheme.surfaceContainerHigh
        },
        modifier = if (liquidGlass) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(topStart = DialogCorner, topEnd = DialogCorner) },
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
            Modifier
        },
        // 左右留白对齐主页卡片（cardPadding = ScreenMargin = 12dp）；
        // 纵向交给库的贴底样式
        outsideMargin = DpSize(ScreenMargin, 0.dp),
        // 内容的 18dp 横向留白由库的 insideMargin 承担（与 2.9.x 自绘版一致），
        // 底部空间在 content 里自己补导航条 inset
        insideMargin = DpSize(18.dp, 0.dp),
        // 免责声明同意弹窗是硬门槛：拖拽 / 返回 / 点外部都关不掉
        allowDismiss = spec.dismissible,
        onDismissRequest = { controller.dismiss() },
        onDismissFinished = { controller.finishDismiss() },
        content = {
            // 库的弹层宿主贴屏幕底边且不吃导航条 inset，内容自己让开
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (spec) {
                    is ConfirmDialogSpec -> ConfirmSheetContent(controller, spec)
                    is AgreementDialogSpec -> AgreementSheetContent(controller, spec)
                    else -> {}
                }
            }
        },
    )
}

/** 重置确认的 sheet 内容：说明 + 「取消 / 确定」两个等宽按钮（规格沿用 2.9.x 定稿） */
@Composable
private fun ConfirmSheetContent(controller: DialogController, spec: ConfirmDialogSpec) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = spec.message,
            // 与标题同色（2.9.3）：正文就是标题的展开说明
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            // 副标题 15sp（2.9.6 定稿）
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        // 按钮前留白 2.9.3 由 20dp 加到 28dp：把整体高度撑起来
        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            // 间距 12 → 16dp（2.9.7）：两个按钮各**收窄 2dp**
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
                    // 先关弹窗再落配置：重置走 resetAllFeaturesDeferred（延迟落盘），
                    // 写入发生在关闭之后（与历史崩溃同一套防御）。
                    controller.dismiss()
                    spec.onConfirm()
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 免责声明同意的 sheet 内容：说明 + 勾选行（带链接）+ 唯一一颗「同意」 */
@Composable
private fun AgreementSheetContent(controller: DialogController, spec: AgreementDialogSpec) {
    // 勾选状态放这里：`remember(spec)` 让「换一个新的同意弹窗」时回到未勾选
    var checked by remember(spec) { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 说明文字：纯文本 —— 免责声明的入口在下方勾选行里
        Text(
            text = spec.message,
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            fontSize = 15.sp,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(18.dp))
        AgreementCheckRow(
            checked = checked,
            onCheckedChange = { checked = it },
            prefix = spec.checkPrefix,
            linkText = spec.checkLinkText,
            suffix = spec.checkSuffix,
            onOpenLink = spec.onOpenDisclaimer,
        )
        Spacer(Modifier.height(18.dp))

        // 只有一颗按钮，且未勾选时不可点
        DialogButton(
            text = spec.confirmText,
            emphasized = true,
            enabled = checked,
            onClick = {
                // 同意落盘在关闭之后：dismiss 只置 closing，写入走调用方的回调
                controller.dismiss()
                spec.onConfirm()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 卡片本体（锚定菜单）：材质 + 内容 + 入场 / 出场变换。
 *
 * @param progress 0 = 收起、1 = 完全展开（驱动缩放）
 * @param fade     0 = 全透明、1 = 不透明（驱动 alpha）
 * @param menuOrigin 缩放原点（布局期按箭头方位裁决，见 [resolveAnchorPlacement]）
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
    menuOrigin: TransformOrigin,
) {
    val shape = RoundedCornerShape(DialogCorner)
    Box(
        modifier = Modifier.graphicsLayer {
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
    // 选择类弹窗**不画标题**（2.9.2 去掉）——选项本身已经说明这是哪个设置项，
    // 多一行标题只会让下拉菜单变高、变啰嗦。
    if (spec is ChoiceDialogSpec) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
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
 *
 * 「免责声明」链接（2.10 起从说明文字挪到这里）也画在这一行：
 * 点击落在链接文字上 → 打开免责声明全文；落在行内其余任何位置 → 翻转勾选。
 *
 * ## 为什么不用 `LinkAnnotation`
 *
 * 那类链接的点击由 `Text` 内部的手势处理，事件消费行为和「整行翻转勾选」的
 * 行级 clickable 容易打架（点链接会不会连带翻转勾选，取决于 Text 内部
 * 消费了多少）。所以这里用**一个 detector 统一裁决**：行上挂
 * [pointerInput]，拿 [TextLayoutResult.getOffsetForPosition] 把点击位置换算
 * 成字符 offset，落在链接区间就跳全文、否则翻转勾选 —— 不存在两套手势抢事件。
 */
@Composable
private fun AgreementCheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    prefix: String,
    linkText: String,
    suffix: String,
    onOpenLink: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // 链接在整段文字里的字符区间（拼接完就确定，只依赖两段文字的长度）
    val linkRange = remember(prefix, linkText) {
        prefix.length until prefix.length + linkText.length
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // `checked` 进 key：翻转要拿最新的值，否则会拿旧状态取反出乱子
            .pointerInput(checked, onCheckedChange, onOpenLink) {
                detectTapGestures { pos ->
                    val offset = layout?.getOffsetForPosition(pos)
                        ?: return@detectTapGestures
                    if (offset in linkRange) {
                        onOpenLink()
                    } else {
                        onCheckedChange(!checked)
                    }
                }
            }
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
            text = buildAnnotatedString {
                append(prefix)
                // 链接样式与原说明文字里的链接一致：主色 + SemiBold + 下划线
                withStyle(
                    SpanStyle(
                        color = colors.primary,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                    ),
                ) {
                    append(linkText)
                }
                append(suffix)
            },
            onTextLayout = { layout = it },
            color = colors.onSurfaceContainer,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            modifier = Modifier.weight(1f),
        )
    }
}
