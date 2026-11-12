package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap

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

/**
 * 一个「单选」弹窗：若干选项 + 当前选中项。
 *
 * 常规用法的动作菜单**不画标题**（2.9.2 去掉：选项本身已经说明是哪个设置项）。
 * [header] 只给「分步向导」这类**需要告诉用户现在选第几步**的场合用 —— 四步选档位时
 * 没有一行提示，用户不知道这一步问的是流通市值还是量比。
 */
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
    /** 顶部一行提示（分步向导用）；null = 不画，与动作菜单完全一致 */
    val header: String? = null,
    /**
     * 列表内的分组标题：键 = 项下标，值 = 插在**这一行之前**的段标题。
     *
     * 与 [header] 的区别：[header] 是顶部固定的一行提示；这个是随内容滚动的分隔行。
     * 宿主标签表有 134 个标签名，平铺成一栏既不分组也没法找 —— 按宿主的六个分组
     * （技术指标 / 行情面 / 基本面 / 技术形态 / 股票范围）切开才点得动。
     */
    val groupHeaders: Map<Int, String> = emptyMap(),
) : DialogSpec

/**
 * 多选菜单里的一段：段标题 + 一组可勾选的项。
 *
 * 段是**维度**（「流通市值」「量比」），项是宿主自己的档位名 —— 界面上一段一段平铺，
 * 用户不用记「中盘 1.5-4」这种口径名，也不用被一步步追问。
 *
 * [selected] 由**调用方**持有，不是菜单内部状态：勾一下就已经落盘了，
 * 菜单自己再存一份只会和配置打架。
 */
internal class MultiChoiceSection(
    val title: String,
    val items: List<String>,
    /** 当前已生效的项（调用方从配置回读后传进来） */
    val selected: Set<Int> = emptySet(),
)

/**
 * 一个「多选菜单」：若干段同时平铺，点一行即勾上 / 取消，**没有确定按钮**。
 *
 * 为什么要它（2026-11-12 用户两次纠正）：选条件先做成四步单选向导，再改成贴底大弹层
 * 「勾完点确定」。用户对着方案设置说「直接照搬那套」——方案设置是**锚在控件右侧的窄菜单、
 * 点一项立刻生效、没有确定取消**。所以这里只留一个 [onToggle]：点哪行，调用方立刻写配置，
 * 菜单不关（一维要连着点几档），勾完点空白处退出。
 *
 * [onToggle] 收的是（第几段，第几项）。勾选态**不进菜单内部状态**，由调用方落盘后
 * 从 [sections] 再传回来 —— 中间态即最终态，不存在「取消了但配置已经写了」这种缝。
 */
internal class MultiChoiceDialogSpec(
    val sections: List<MultiChoiceSection>,
    val onToggle: (sectionIndex: Int, itemIndex: Int) -> Unit,
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
 * 图片预览弹窗：贴屏幕底部、从下往上滑入（与「重置模块」确认弹窗同一个壳）。
 *
 * 弹层材质跟随 App 主题 / 液态玻璃设置；**不再按图片深浅给整窗铺底色** ——
 * 浅色模式下弹出一张黑底的图，不应该把标题和关闭按钮也一起变黑。
 */
internal class ImageDialogSpec(
    val image: ImageBitmap,
    val title: String?,
    val closeText: String,
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
 * 贴底文本输入弹窗。
 *
 * 卡片骨架跟 [ConfirmDialogSpec] 一样（标题 + 说明 + 两颗按钮），只是中间多了一个
 * **真的能敲字的输入框**：输入框一律用真 `TextField`，长按粘贴、光标拖动、选中
 * 这些系统行为才能原生可用（自绘一个假输入框等于把这些全丢掉）。
 *
 * [validate] 在点「确定」时执行：返回非 null 的文案 = 校验不通过，那段文字就地显示在
 * 输入框下方，弹窗**不关闭**，用户改完可以直接再点确定；返回 null 才走 [onConfirm]。
 * [onConfirm] 由弹层宿主排进 [DialogController.dismiss] 的 afterDismiss，等收起动画
 * 播完再落配置（同步写的后果见 [DialogController.afterDismiss] 的注释）。
 */
internal class TextInputDialogSpec(
    val title: String,
    /** 说明文字（纯文本，可为空串；为空时不留空档） */
    val message: String,
    /** 输入框占位文字 */
    val hint: String,
    /** 初始内容（编辑已有内容时带原值，新建时为空串） */
    val initial: String,
    val confirmText: String,
    val cancelText: String,
    /** 校验：入参是去掉首尾空白之前的原始输入；返回错误文案 = 不通过 */
    val validate: (String) -> String?,
    val onConfirm: (String) -> Unit,
    override val anchor: Rect? = null,
) : DialogSpec

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
     * 「这一次弹出」的编号：只在**从没弹窗到有弹窗**时 +1。
     *
     * 弹层内容原地刷新（多选菜单点一下勾选 / 取消、确认弹窗换文案）时编号不变。
     * 入场动画只认这个编号 —— 早先按 `spec` 对象做 key，每换一次 spec 就从 0 重播缩放，
     * 表现就是「点一下勾选，菜单闪一下」（2026-11-12 用户报的）。
     */
    var openSeq by mutableStateOf(0)
        private set

    /** 打开一个新弹窗：只有原本没有弹窗（或在播退出动画）时才算「新的一次」 */
    private fun opened() {
        if (spec == null || closing) openSeq++
    }

    /**
     * 「关闭之后再执行」的动作（见 [dismiss]）。
     *
     * 弹层里的点击**不能**顺手把配置写掉：`commit()` 会同步回调
     * `OnSharedPreferenceChangeListener` → 订阅方改写 Compose 状态 →
     * 正跑着收起动画的弹层被换掉入参 → 崩溃（3.1.x 的 ⋮ 菜单「删除方案」
     * 踩的正是它：删掉一行 → 列表当场重组，而弹窗的收起动画还没播完）。
     * 现在统一排到这里，等 [finishDismiss] 播完动画再落配置 ——
     * 此刻弹窗已彻底关闭，同步写入是安全的（动作必须同步，不能走延迟写入：
     * 那 360ms 正是「副标题顿一会才切换」的根因）。
     */
    private var afterDismiss: (() -> Unit)? = null

    fun showChoice(s: ChoiceDialogSpec) {
        // 新弹窗开启意味着旧弹窗的上下文已作废：如果旧弹窗正在播退出动画
        // （afterDismiss 已排队），那个动作不能再落到新弹窗关闭时执行。
        afterDismiss = null
        closing = false
        opened()
        spec = s
    }

    /** 弹出多选弹层（条件档位这类「一次勾完」的场合） */
    fun showMultiChoice(s: MultiChoiceDialogSpec) {
        afterDismiss = null
        closing = false
        opened()
        spec = s
    }

    fun showConfirm(s: ConfirmDialogSpec) {
        afterDismiss = null
        closing = false
        opened()
        spec = s
    }

    /** 弹出图片预览（贴底、从下往上滑入） */
    fun showImage(s: ImageDialogSpec) {
        afterDismiss = null
        closing = false
        opened()
        spec = s
    }

    /** 弹出「首次启动必须同意免责声明」（不可关闭） */
    fun showAgreement(s: AgreementDialogSpec) {
        // 已经在显示同一个同意弹窗时不要再赋值：`remember(spec)` 的勾选状态会被重置，
        // 用户勾了之后如果外层恰好重组一次，勾就会莫名其妙掉回未勾选。
        if (spec is AgreementDialogSpec && !closing) return
        afterDismiss = null
        closing = false
        opened()
        spec = s
    }

    /** 弹出贴底文本输入（页脚文字这类单行文本） */
    fun showTextInput(s: TextInputDialogSpec) {
        afterDismiss = null
        closing = false
        opened()
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
