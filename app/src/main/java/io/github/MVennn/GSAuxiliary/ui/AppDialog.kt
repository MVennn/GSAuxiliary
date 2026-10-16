package io.github.MVennn.GSAuxiliary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
import kotlin.math.abs

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
 * | | [ChoiceDialogSpec]（主题 / 语言） | [ConfirmDialogSpec]（重置）<br>[AgreementDialogSpec]（首次启动同意） |
 * |---|---|---|
 * | 定位 | **从触发控件右中（箭头处）弹出**：优先卡片顶边贴箭头向下展开，下方放不下翻到底边贴箭头向上展开 | **贴屏幕底部**，宽度 = 主页卡片宽 |
 * | 入场 | 以**离箭头最近的右角**为原点缩放展开 + **渐显**（alpha 0 → 1），苹果风回弹曲线<br>（卡片在箭头下方 → 右上角往左下长；翻到上方 → 右下角往左上长） | 从屏幕下方**向上滑入**，苹果风回弹曲线 |
 * | 出场 | 向该右角**缩回** + **渐隐**（alpha 1 → 0），苹果风 easeInOut | 向**下滑出**，苹果风 easeInOut |
 * | 标题 | **没有**（2.9.2 去掉，选项本身已经说明一切） | 有（居中；主标题 `title4` 18sp + Medium、副标题 15sp，2.9.6 定稿） |
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
 * ## 动画：只用 `tween` + 苹果风贝塞尔曲线，**没有弹簧**
 *
 * 入场 / 出场仍全部走 `tween` + 单段缓动，但曲线换成「苹果风味」的
 * `CubicBezierEasing`：入场带一点 easeOutBack 式回弹过冲（缩放区间小，锚定菜单
 * 折算下来约 2%；贴底弹窗的位移区间是整卡高度，过冲单独压到约 3%），落位稳稳收住；
 * 出场 easeInOut（`UIView.animate` 的默认曲线）平滑离场。仍然没有任何 `spring`：
 * 时长可预期，而且出场方向一旦回弹就会冲过 0（缩得比起始还小 / 滑出屏幕又弹回来）。
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
    /**
     * 置灰且不可点的项（3.1.1）。
     *
     * 用在「点了也不会有任何变化」的动作菜单项上 —— 例如方案已经是默认时的「设为默认」、
     * 只有一套方案时的「删除方案」、第一项的「上移」。原来这些项点了没反应，用户会以为
     * 菜单坏了。**只是不可点，不会隐藏**：位置固定，菜单高度不会因为状态变化而跳动。
     */
    val disabledIndices: Set<Int> = emptySet(),
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
 *  - **没有取消按钮**，只有一颗「确定」，且**未勾选时置灰点不动**；
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

    /**
     * 「关闭之后再执行」的动作（见 [dismiss]）。
     *
     * 弹层里的点击**不能**顺手把配置写掉：`commit()` 会同步回调
     * `OnSharedPreferenceChangeListener` → 订阅方改写 Compose 状态 →
     * 正跑着收起动画的弹层被换掉入参 → 崩溃（`ModulePreferences.editDeferred`
     * 的注释里记的就是这条根因，3.1.x 的 ⋮ 菜单「删除方案」踩的正是它：
     * 删掉一行 → 列表当场重组，而弹窗的收起动画还没播完）。
     * 现在统一排到这里，等 [finishDismiss] 播完动画再落配置。
     */
    private var afterDismiss: (() -> Unit)? = null

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
    fun dismiss(afterClose: (() -> Unit)? = null) {
        if (spec == null) return
        // 只在传了新动作时覆盖：菜单项已经点过（动作排着队）、随后又点了遮罩 /
        // 按了返回，不能把排队中的那次选择丢掉。
        if (afterClose != null) afterDismiss = afterClose
        closing = true
    }

    /** 退出动画播完，真正清空（只由宿主调用），随后执行「关闭之后再做」的动作 */
    fun finishDismiss() {
        spec = null
        closing = false
        val action = afterDismiss
        afterDismiss = null
        action?.invoke()
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

@Composable
private fun DialogBody(controller: DialogController, spec: DialogSpec) {
    when (spec) {
        // 选择类弹窗**不画标题**（2.9.2 去掉）——选项本身已经说明这是哪个设置项，
        // 多一行标题只会让下拉菜单变高、变啰嗦。
        // `verticalScroll`：选项多到超过卡片高度上限时（见 MenuMaxHeightFraction）
        // 由列表自己滚动，而不是把卡片撑满整屏。
        is ChoiceDialogSpec -> {
            val scrollState = rememberScrollState()
            val scrolling = scrollState.isScrollInProgress
            // 滚动时才出现的小指示条：内容没超出（maxValue == 0）时连画都不画
            val thumbAlpha by animateFloatAsState(
                targetValue = if (scrolling && scrollState.maxValue > 0) 1f else 0f,
                // 出现要快（跟手）、消失慢一点（停手后还能看到它在哪）
                animationSpec = tween(if (scrolling) 90 else 420),
                label = "menu-scroll-thumb",
            )
            Box {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(vertical = 8.dp),
                ) {
                    spec.items.forEachIndexed { index, label ->
                        ChoiceRow(
                            label = label,
                            selected = index == spec.selectedIndex,
                            enabled = index !in spec.disabledIndices,
                            // 先关弹窗、**播完收起动画**再落配置：`onSelect` 里往往是一次
                            // 配置写入（删除方案 / 上移下移 / 设为默认），同步执行会让列表在
                            // 弹窗动画还没结束时重组 —— 见 [DialogController.dismiss]。
                            onClick = { controller.dismiss { spec.onSelect(index) } },
                        )
                    }
                }
                if (thumbAlpha > 0.01f) {
                    MenuScrollThumb(
                        scrollState = scrollState,
                        alpha = thumbAlpha,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp)
                            .width(3.dp)
                            .fillMaxHeight()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }

        is ConfirmDialogSpec -> Column(
            // 纵向留白 2.9.3 由 22dp 加到 32dp：贴底弹窗铺满整条卡片宽度，
            // 内容却只有两行字 + 一排按钮，太紧就会「扁长」。
            // 带 `verticalScroll` 只为兜住极端情况（大字号 / 矮屏把卡片顶到高度上限）
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 32.dp),
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
                    // 同上：确认动作（删除方案 / 重置模块）等弹窗关闭后再执行
                    onClick = { controller.dismiss { spec.onConfirm() } },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        is AgreementDialogSpec -> {
            // 勾选状态放这里：`remember(spec)` 让「换一个新的同意弹窗」时回到未勾选
            var checked by remember(spec) { mutableStateOf(false) }
            val colors = MiuixTheme.colorScheme

            Column(
                // 同上：`verticalScroll` 兜住大字号 / 矮屏（免责声明那段文字不短）
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 28.dp),
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

                // 说明文字：纯文本 —— 免责声明的入口在下方勾选行里
                Text(
                    text = spec.message,
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
                    // 同意写入的是本机状态，同样会触发一次重组 —— 一样等关闭之后再做
                    onClick = { controller.dismiss { spec.onConfirm() } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 选项列表右侧的滚动指示条 —— **只在滚动时出现**（淡入淡出由调用方给的 [alpha] 驱动）。
 *
 * 为什么自己画：Miuix 0.9.3 没有给「任意可滚动容器」用的指示条（它的滚动条是 Lazy 列表
 * 内部实现），而这里是一个 `verticalScroll` 的 Column。几何按「视口 / 内容总高」算、
 * 位置按当前 value 算，与系统滚动条语义一致：条越短说明内容越长，条到底等于滚到底。
 * 状态值都在 `drawBehind` 里读 —— 滚动只触发重绘，不触发重组。
 */
@Composable
private fun MenuScrollThumb(
    scrollState: ScrollState,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = modifier.drawBehind {
            val max = scrollState.maxValue
            val viewport = scrollState.viewportSize
            if (max <= 0 || viewport <= 0) return@drawBehind
            val total = (max + viewport).toFloat()
            // 最短不低于自身宽度的两倍，免得内容极长时细到看不见
            val h = (size.height * viewport / total).coerceAtLeast(size.width * 2f)
            val y = (size.height - h) * (scrollState.value.toFloat() / max)
            drawRoundRect(
                color = color.copy(alpha = 0.7f * alpha),
                topLeft = Offset(0f, y),
                size = Size(size.width, h),
                cornerRadius = CornerRadius(size.width / 2f),
            )
        },
    )
}

/** 单选弹窗里的一行：左标题、右对勾 */

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 禁用项**不挂 clickable**：连按压高亮都不该有，免得看着像能点
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = when {
                !enabled -> MiuixTheme.colorScheme.disabledOnSurface
                selected -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.onSurfaceContainer
            },
            fontSize = 15.sp,
            fontWeight = if (selected && enabled) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        if (selected) {
            // TintedIcon：`Icon(imageVector, tint = …)` 的染色在本机不生效（见 Widgets.kt）
            TintedIcon(
                imageVector = AppIcons.Check,
                tint = if (enabled) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.disabledOnSurface
                },
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
 *
 * ## 落点要先换算到文字自己的坐标系
 *
 * 手势挂在**整行**上，而 `getOffsetForPosition` 要的是**文字内部**的坐标。
 * 两者之间隔着「行内边距 4dp + 复选框 20dp + 间距 10dp」这一段 —— 直接拿行内
 * 坐标去问，整段文字等于被凭空右移了约两个字宽：点在链接中间 / 右侧时算出的
 * offset 已经越过链接区间，就被判成「点的是空白」，于是**点免责声明反而把勾选
 * 上了**（3.2.0 修的问题）。
 *
 * 所以行与文字各记一份窗口坐标（[boundsInWindow]，与锚定弹窗同一套坐标口径），
 * 相减得到文字左上角在行内的偏移，点击时先减掉它再问 offset；两个矩形差值是
 * 相对的，不关心行内边距算在哪一层。手势 lambda 里是**实时读**这两个状态，
 * 所以滚动 / 换字号之后不会拿到旧值。
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
    // 行与文字各自的窗口矩形：相减 = 文字左上角在行内的偏移（见上面的 KDoc）。
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    var textBounds by remember { mutableStateOf(Rect.Zero) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // 与下面的 `pointerInput` 紧挨着：两个都是「不改布局」的修饰符，
            // 落在同一个坐标层上，矩形与点击落点才是同一套原点。
            .onGloballyPositioned { rowBounds = it.boundsInWindow() }
            // `checked` 进 key：翻转要拿最新的值，否则会拿旧状态取反出乱子
            .pointerInput(checked, onCheckedChange, onOpenLink) {
                detectTapGestures { pos ->
                    val offset = layout?.getOffsetForPosition(
                        pos - textBounds.topLeft + rowBounds.topLeft,
                    ) ?: return@detectTapGestures
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
            // 记下文字自己的窗口矩形：点击落点要靠它换算成「文字内部」坐标
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { textBounds = it.boundsInWindow() },
        )
    }
}
