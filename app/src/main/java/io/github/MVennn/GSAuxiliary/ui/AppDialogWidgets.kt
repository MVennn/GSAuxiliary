package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DialogBody(controller: DialogController, spec: DialogSpec) {
    when (spec) {
        // 选择类弹窗**不画标题**（2.9.2 去掉）——选项本身已经说明这是哪个设置项，
        // 多一行标题只会让下拉菜单变高、变啰嗦。
        // `verticalScroll`：选项多到超过卡片高度上限时（见 MenuMaxHeightFraction）
        // 由列表自己滚动，而不是把卡片撑满整屏。
        is ChoiceDialogSpec -> {
            val scrollState = rememberScrollState()
            // 3.2.1：滚到顶 / 底之后**继续拖**的那段余量（列表自己不消费）用来驱动
            // 指示条的「拉长 + 回弹」。不抓这段的话，拉满时目标值恒为 0 / 1，
            // 弹簧没有可追的差量 —— 也就是用户说的「拉满是没有动画的」。
            var edgePull by remember { mutableFloatStateOf(0f) }
            val edgeScroll = remember {
                object : NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (available.y != 0f) {
                            edgePull = (edgePull + available.y).coerceIn(-EdgePullMax, EdgePullMax)
                        }
                        return Offset.Zero
                    }
                }
            }
            // 手指离开后让余量自己弹回去（松手即回弹，不是等下一次滚动）。
            // 这里手写一小段欠阻尼弹簧积分：`animate()` 对 Float 没有直接重载，
            // 而为了这一处引一个 Animatable 反而要多维护一条状态。
            LaunchedEffect(scrollState.isScrollInProgress) {
                if (scrollState.isScrollInProgress || edgePull == 0f) return@LaunchedEffect
                var value = edgePull
                var velocity = 0f
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.032f)
                    last = now
                    // k / c 取欠阻尼：约 0.5 秒收敛，肉眼能看到一次回弹
                    val accel = -520f * value - 26f * velocity
                    velocity += accel * dt
                    value += velocity * dt
                    edgePull = value
                    if (abs(value) < 0.5f && abs(velocity) < 8f) {
                        edgePull = 0f
                        break
                    }
                }
            }
            Box {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .nestedScroll(edgeScroll)
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
                // 指示条**常驻**（3.1.1）：内容一旦超出就一直在，不再「拖一下才淡入」。
                // 不超出时 `maxValue == 0`，它自己什么也不画。
                //
                // `matchParentSize()` 而不是 `fillMaxHeight()`：后者会**参与测量**，
                // 把卡片顶到最大高度 —— 7 个周期的菜单本该 329dp，却被撑成 390dp、
                // 底下白出 60dp（用户报的「弹窗过长」）。matchParentSize 只取父盒
                // 已经量好的尺寸、不反过来影响它，卡片才会贴合内容。
                MenuScrollThumb(
                    scrollState = scrollState,
                    edgePull = { edgePull },
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        is ConfirmDialogSpec -> Column(
            // 内容间距对齐 Miuix `DialogDefaults.insideMargin`（24×24）：标题 / 摘要
            // 各自 fillMaxWidth + 尾随 12dp（Miuix DialogContent 的节奏）。
            // 带 `verticalScroll` 只为兜住极端情况（大字号 / 矮屏把卡片顶到高度上限）
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = spec.title,
                // 兜底：超长标题最多 3 行（现有标题都是 1 行，这里等于零视觉变化），
                // 再长就省略 —— 否则它会把副标题挤到不确定的位置
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
                // 主标题字号 = `title4`（**18sp**）+ Medium（2.9.6 定稿）：夹在
                // 「主页卡片标题 headline1 = 17sp」与「顶栏标题 title3 = 20sp」之间 ——
                // 17sp 观感偏低（2.9.4）、20sp 又过大（2.9.5），18sp 是这一档的稳定解，
                // 且正好是 Miuix 文字样式表里 17 与 20 之间的那一档。
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    // Miuix DialogContent：标题尾随 12dp
                    .padding(bottom = 12.dp),
            )
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
                modifier = Modifier
                    .fillMaxWidth()
                    // Miuix DialogContent：摘要尾随 12dp（原 2.9.3 的 28dp 留白取消，
                    // 间距节奏统一交给卡片 24dp 内边距 + 两段 12dp）
                    .padding(bottom = 12.dp),
            )
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
                // 间距节奏与确认弹窗统一（Miuix DialogContent：24×24 内边距 + 两段 12dp）
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = spec.title,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.onSurfaceContainer,
                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )

                // 说明文字：纯文本 —— 免责声明的入口在下方勾选行里
                Text(
                    text = spec.message,
                    color = colors.onSurfaceContainer,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                )

                AgreementCheckRow(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    prefix = spec.checkPrefix,
                    linkText = spec.checkLinkText,
                    suffix = spec.checkSuffix,
                    onOpenLink = spec.onOpenDisclaimer,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

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

/** 到头后继续拖的余量上限（px）：超过这个值再拖也不再拉长，避免拉成一根长条 */
private const val EdgePullMax = 160f

/** 选项列表的滚动指示条：宽 3dp、距卡片右缘 5dp、上下各留 16dp（几何在 drawBehind 里算） */
private val MenuThumbWidth = 3.dp
private val MenuThumbRightInset = 5.dp
/** 上下留白 16dp：卡片圆角 28dp，留 8dp 时条头会压在圆角的弧线上；16dp 起就落在直边里 */
private val MenuThumbVerticalInset = 16.dp

/**
 * 选项列表右侧的滚动指示条 —— **常驻显示**（3.1.1 起）。
 *
 * 为什么自己画：Miuix 0.9.3 没有给「任意可滚动容器」用的指示条（它的滚动条是 Lazy 列表
 * 内部实现），而这里是一个 `verticalScroll` 的 Column。几何按「视口 / 内容总高」算、
 * 位置按当前 value 算，与系统滚动条语义一致：条越短说明内容越长，条到底等于滚到底。
 * 状态值都在 `drawBehind` 里读 —— 滚动只触发重绘，不触发重组。
 *
 * ## 为什么自带几何（而不是一条 `fillMaxHeight` 的窄 Box）
 *
 * 指示条一旦承度**测量**，就会把外面的卡片顶到最大高度：7 个周期的菜单只有 329dp 内容，
 * 却因为条子 `fillMaxHeight` 被撑到 45% 上限（390dp），底下白出一大截 —— 用户报的
 * 「弹窗过长」就是它。所以调用方传 `matchParentSize()`（只读父盒量好的尺寸、不参与
 * 测量），宽度 / 右缘留白 / 上下留白都在 `drawBehind` 里自己算。
 *
 * ## 两个上机反馈的修正
 *
 * 1. **常驻**：原来只有 `isScrollInProgress` 时才淡入、停手 420ms 淡出，用户根本不知道
 *    这列表还能滚（得先试着拖一下才看见）。现在内容超出就一直在。
 * 2. **颜色压淡到 30%**：原来 `onSurfaceVariantSummary` 上再叠 70% 不透明度 —— 深色主题
 *    下接近纯白、浅色下接近纯黑，一条 3dp 的细线比身后的卡片还跳（「对比生硬」）。
 *    现在是一条浅色轨道，只负责提示「还有内容」，不跟正文抢注意力。
 */
@Composable
private fun MenuScrollThumb(
    scrollState: ScrollState,
    /** 到头之后继续拖的余量（px），由列表的嵌套滚动喂进来；只在绘制阶段读 */
    edgePull: () -> Float,
    modifier: Modifier = Modifier,
) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    // 3.2.1：指示条加弹性。位置不再直接跟 `scrollState.value` 走，而是追一个
    // 欠阻尼弹簧（dampingRatio < 1）—— 拖动时条子略微落后于手指，甩到底 /
    // 顶时冲过头再弹回来；冲到轨道之外的那一截不裁掉，而是转成「拉长」，
    // 于是「拉满」也有动画（原来到端点就是静止的）。
    //
    // `fraction` 只在下面的 drawBehind 里被读 —— 读的是 State，滚动与弹簧
    // 都只让这一层**重绘**，概览页 / 菜单列表不会跟着重组。
    val rawFraction =
        if (scrollState.maxValue > 0) scrollState.value.toFloat() / scrollState.maxValue else 0f
    val fraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
        label = "menu-thumb-elastic",
    )
    Box(
        modifier = modifier.drawBehind {
            val max = scrollState.maxValue
            val viewport = scrollState.viewportSize
            if (max <= 0 || viewport <= 0) return@drawBehind
            val w = MenuThumbWidth.toPx()
            val inset = MenuThumbVerticalInset.toPx()
            // 条子的活动范围（去掉上下留白），最短不低于自身宽度的两倍
            val track = (size.height - inset * 2).coerceAtLeast(w)
            val total = (max + viewport).toFloat()
            val baseH = (track * viewport / total).coerceAtLeast(w * 2f)
            // 两路拉伸：① 弹簧过冲（甩到底时的惯性）；② 到头后继续拖的余量
            //    （手指还按着、列表已经不动了）—— 后者是「拉满也有动画」的来源。
            val overshoot = fraction - fraction.coerceIn(0f, 1f)
            val pull = abs(edgePull()).coerceIn(0f, EdgePullMax)
            val stretch = 1f +
                (abs(overshoot) * 1.2f).coerceAtMost(0.6f) +
                (pull / EdgePullMax) * 0.45f
            val h = (baseH * stretch).coerceAtMost(track)
            val y = (inset + (track - h) * fraction.coerceIn(0f, 1f))
                .coerceIn(inset, inset + track - h)
            drawRoundRect(
                // 18%：Miuix 的 `onSurfaceVariantSummary` 在亮色主题下几乎是纯黑
                // （真机取色 = 卡片 245 上叠出 170），所以不透明度得比直觉更低。
                // 这一档实测落点约 201（卡片 245），是一条「看得见但不抢眼」的浅轨。
                color = color.copy(alpha = 0.18f),
                topLeft = Offset(size.width - w - MenuThumbRightInset.toPx(), y),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f),
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
    modifier: Modifier = Modifier,
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
        modifier = modifier
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
