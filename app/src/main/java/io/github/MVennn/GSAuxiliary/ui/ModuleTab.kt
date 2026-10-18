package io.github.MVennn.GSAuxiliary.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import io.github.MVennn.GSAuxiliary.BuildConfig
import io.github.MVennn.GSAuxiliary.R
import io.github.MVennn.GSAuxiliary.app.FrameworkBridge
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Tab 1 · 概览 —— 模块身份 + 运行环境，**一屏内看完**。
 *
 * 页面自上而下两块：
 *   1. **状态卡**（顶部）：一眼看出模块有没有在工作（大对勾 / 大感叹号），
 *      卡片里同时给出框架版本与 API 版本
 *   2. 模块 / 运行环境 两个分组
 *
 * ## 为什么只有两组（2.8.0 精简）
 *
 * 原来还有第三组「框架」（连接状态 / 框架版本 / API 版本 / 作用域）和
 * 「配置通道」一行，以及「模块名称」一行。这些都**和顶部状态卡重复**：
 *
 *  - 框架连接、框架版本、API 版本 → 状态卡里已经有了
 *  - 配置通道（remote preferences / 本地暂存）→ 属排障信息，留在日志里就够了
 *  - 模块名称 → 就是 App 名字，标题栏已经写着
 *
 * 去掉之后整页不再需要滚动，剩下的每一行都是「不看就不知道」的信息。
 *
 * 这一页**只做展示**，不放任何操作项（2.18.0 起「框架操作」组也没有了 ——
 * 热重载按钮已移除，模块升级时框架自动热重载，无需手动入口）。
 */
@Composable
internal fun ModuleTab() {
    val scrollState = rememberScrollState()
    val connected = rememberConnected()

    val unknown = stringResource(R.string.common_unknown)
    val unreadable = stringResource(R.string.framework_info_unreadable)
    val noFramework = stringResource(R.string.overview_status_no_framework)
    val activeTitle = stringResource(R.string.overview_status_active)
    val inactiveTitle = stringResource(R.string.overview_status_inactive)
    val disconnectedTitle = stringResource(R.string.overview_status_disconnected)

    val moduleVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
    val buildTime = BuildConfig.BUILD_TIME.ifEmpty { "—" }

    // 框架信息：读到就存下来，**未激活时短轮询几次**。
    //
    // 之前是 `remember(connected.key){ … }` 只算一次 —— 冷启动时框架服务刚连上、
    // `scope` / 版本号还读不到，这一版就永远停在「未激活 + 感叹号」，直到连接状态
    // 再次翻转才重算。LSPosed 明明已激活、卡片还挂感叹号，就是它。
    fun readFramework(): FrameworkInfo {
        val svc = FrameworkBridge.service()
        if (svc == null) {
            // 连都没连上：框架没装 / 没启用模块 / 服务已断开
            return FrameworkInfo(
                state = FrameworkState.Disconnected,
                version = noFramework,
                api = "—",
            )
        }
        val version = runCatching {
            listOfNotNull(svc.frameworkName, svc.frameworkVersion)
                .joinToString(" ")
                .ifEmpty { unreadable }
        }.getOrDefault(unreadable)
        val api = runCatching { "API ${svc.apiVersion}" }.getOrDefault(unknown)
        // 连上了不等于生效：作用域为空说明框架认得这个模块、但没把它派到任何应用上。
        // 读不到版本号 / API 也算异常态。两者任一不成立就落到「未激活」。
        val scope = runCatching { svc.scope }.getOrNull()
        val activated = version != unreadable && api != unknown && !scope.isNullOrEmpty()
        return FrameworkInfo(
            state = if (activated) FrameworkState.Active else FrameworkState.Inactive,
            version = version,
            api = api,
        )
    }

    var framework by remember(connected.value, unknown, unreadable, noFramework) {
        mutableStateOf(readFramework())
    }
    // 还没读到「已激活」就每 400ms 再看一眼（最多 8 次 ≈ 3.2s）：框架刚起来的
    // 那一小段确实可能还读不到作用域。读到已激活立刻停，不空转。
    LaunchedEffect(connected.value) {
        var tries = 0
        while (tries < 8 && framework.state != FrameworkState.Active) {
            delay(400)
            framework = readFramework()
            tries++
        }
    }

    val statusTitle = when (framework.state) {
        FrameworkState.Active -> activeTitle
        FrameworkState.Inactive -> inactiveTitle
        FrameworkState.Disconnected -> disconnectedTitle
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        // 玻璃顶栏时内容铺到屏幕顶边，首屏空白在这里补（见 Glass.kt 的 LocalTopInset）
        Spacer(Modifier.height(8.dp + LocalTopInset.current))

        // --- 顶部状态卡：已激活 / 未激活 / 未连接 ---
        OverviewStatusCard(
            state = framework.state,
            title = statusTitle,
            version = framework.version,
            api = framework.api,
            modifier = Modifier.cardPadding(),
        )

        // --- 模块自身 ---
        SmallTitle(stringResource(R.string.section_overview_module))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.info_module_version),
                summary = moduleVersion,
            )
            BasicComponent(
                title = stringResource(R.string.info_build_time),
                summary = buildTime,
            )
        }

        // --- 手机与依赖库 ---
        SmallTitle(stringResource(R.string.section_overview_runtime))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.info_android_version),
                summary = androidVersion,
            )
            BasicComponent(
                title = stringResource(R.string.info_device_model),
                summary = deviceModel,
            )
            BasicComponent(
                title = stringResource(R.string.info_miuix_version),
                summary = BuildConfig.MIUIX_VERSION,
            )
        }

        Spacer(Modifier.height(24.dp + LocalDockInset.current))
    }
}

/** 顶部状态卡的三态：已激活 / 未激活 / 未连接框架 */
private enum class FrameworkState { Active, Inactive, Disconnected }

/** 框架信息的一次性快照（只给顶部状态卡用） */
private data class FrameworkInfo(
    val state: FrameworkState,
    val version: String,
    val api: String,
)

/**
 * 概览页顶部的状态卡 —— 对应 LSPosed 页面顶部那张「已激活 + 大对勾」的卡片。
 *
 * ## Miuix 里没有现成的这个组件
 *
 * 把 `miuix-ui` / `miuix-core` / `miuix-preference` 0.9.3 的类清单过了一遍：
 * `Card` / `BasicComponent` / `SmallTitle` / `Checkbox` / `Badge` /
 * `ProgressIndicator` / `Snackbar` / `Tooltip` … 都在，但**没有**「状态卡 / Hero 卡」
 * 这一类复合组件；LSPosed 那张卡也是它自己用 Miuix 基础件拼出来的。
 * 所以这里按 Miuix 的视觉规范自绘一个等效实现：
 * `Card` + 左侧三行文字 + 右侧一块超大的装饰图标。
 *
 * **3.2.1 起这张卡就是「缩小版的关于」**：卡面光场与文字配色直接复用
 * [AboutHeroBackground] / [heroAccent] / [heroCaption]，一行视觉代码都没有另写 ——
 * 关于页改什么，这张卡跟着改什么。图标仍是自己的大号线框图形（玻璃板那条已回退）。
 *
 * ## 版式调整
 *
 *  - **字号上调一档**（2.9.2）：标题 22sp / 版本 16sp / API 15sp —— 状态文案是
 *    这张卡的主信息，原尺寸在卡片里显得偏小；
 *  - **卡片高度固定**（2.9.3）：[StatusCardMinHeight] = 148dp，三行文字垂直居中。
 *    装饰图标放进 `matchParentSize` 容器，**不再参与高度测量** ——
 *    于是「只调字号」不会连带改变卡片高度（2.9.1 / 2.9.2 时高度其实是被图标
 *    顶到 150 / 170dp 的，见 [StatusCardMinHeight] 的注释）；
 *  - **图标完整显示、右侧垂直居中**（2.17.2 定稿；2.9.1 ~ 2.17.1 为裁切弧）：
 *    图标尺寸 [StatusCardIconSize]，`align(CenterEnd)` 靠右垂直居中、距右缘
 *    [StatusCardIconEndPadding]，整个圆完整露出 —— 132dp 对 148dp 的卡高，
 *    上下各留 8dp。此前一直走「顶到右下角、只露左上一块弧线」的路线，
 *    2.17.1 放大到 260dp 后弧线盖满整条卡片右缘，认不出是个对勾，故废弃。
 *
 *    > Miuix `Card` 的 `BasicCard` 内部走 `squircleSurface`，其中调用了
 *    > `ClipKt.clip(modifier, shape)`（`javap -c` 逆 `SquircleBackgroundKt` 确认），
 *    > 所以**不需要自己再包一层裁剪**，超出卡片范围的部分会被自动切掉。
 *
 * ## 三态与配色（绿 / 红 / 灰，只染背景）
 *
 * | 状态 | 条件 | 流光主色 |
 * |---|---|---|
 * | 已激活 | 连上框架服务，且作用域非空、版本可读 | [StatusActiveColor] 绿 |
 * | 未激活 | 连上框架服务，但作用域为空 / 信息读不出来 | `colorScheme.error` |
 * | 未连接 | 拿不到框架服务（没装 LSPosed、模块没启用、服务已断开） | `colorScheme.onBackgroundVariant` |
 *
 * **状态色只换背景那层流光**（[lightFieldPalettesFrom] 由主色派生整套调色板）；
 * 前景文字与图标保持中性：深色白、浅色黑，副行与图标按同一色降透明度。
 *
 * 图标走 [AppIcons] 自绘（不引图标库，理由见该文件注释），是**大号线框图形**：
 * 3.2.1 一度做成关于页 mark 那种玻璃板，被否掉了（这张卡要的是水印，不是一块实心玻璃）。
 *
 * @param state 三态之一，决定配色与右侧图标
 */
@Composable
private fun OverviewStatusCard(
    state: FrameworkState,
    title: String,
    version: String,
    api: String,
    modifier: Modifier = Modifier,
) {
    val darkTheme = LocalDarkTheme.current
    // 三态配色（绿 / 红 / 灰）只作用在**背景流光**上：状态色派生出整套光场调色板，
    // 于是「已激活」是一层绿光、「未激活」一层红光、「未连接」一层灰光。
    val accent = when (state) {
        FrameworkState.Active -> StatusActiveColor
        FrameworkState.Inactive -> MiuixTheme.colorScheme.error
        FrameworkState.Disconnected -> MiuixTheme.colorScheme.onBackgroundVariant
    }
    val fieldPalettes = remember(accent, darkTheme) {
        lightFieldPalettesFrom(accent, darkTheme)
    }
    // 前景文字：深色白、浅色黑（3.2.1 定稿）。背景流光已经带色，文字保持中性
    // 最干净，压在任何色系上都不打架；副行仍按关于页那套「同色降透明度」派生。
    val titleColor = if (darkTheme) Color.White else Color.Black
    val subColor = titleColor.copy(alpha = if (darkTheme) 0.62f else 0.58f)
    val surface = MiuixTheme.colorScheme.surface

    // 动画时间：只在绘制阶段被读取（`fieldClock` 是个 lambda），推进靠
    // withFrameNanos 累加 —— 每帧只有这层光场重绘，概览页不会跟着重组。
    val fieldTime = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var previous = 0L
        while (true) {
            withFrameNanos { frame ->
                if (previous != 0L) {
                    fieldTime.floatValue += (frame - previous) / 1_000_000_000f
                }
                previous = frame
            }
        }
    }
    val fieldClock = remember(fieldTime) { { fieldTime.floatValue } }
    // 卡片不跟滚动，恒为全亮（关于页那个参数是给滚动淡出用的）
    val alwaysVisible = remember { { 1f } }

    Card(
        modifier = modifier,
        // 内边距改由文字列自己加：装饰图标要贴到卡片边缘，不能被 Card 的内边距挡住
        insideMargin = PaddingValues(0.dp),
        // 卡面底色就是页面底色：卡片的形由光场给出，不再垫一层状态色
        colors = CardDefaults.defaultColors(color = surface),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = StatusCardMinHeight),
        ) {
            // --- 图标要不要让位：先量一遍文字 ---
            //
            // 用 `TextMeasurer` 在**进入布局之前**量出三行里最宽的那一行，于是首帧
            // 就是最终位置 —— 不会出现「先整颗图标、下一帧再被推出去」的闪动。
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val textMaxPx = remember(title, version, api, density) {
                listOf(
                    title to TextStyle(
                        fontSize = StatusCardTitleSize,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    version to TextStyle(fontSize = StatusCardVersionSize),
                    api to TextStyle(fontSize = StatusCardApiSize),
                ).maxOf { (text, style) ->
                    measurer.measure(
                        text = text,
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                    ).size.width
                }.toFloat()
            }
            val iconSizePx = with(density) { StatusCardIconSize.toPx() }
            val reservedPx = with(density) {
                (StatusCardTextStartPadding + StatusCardIconGap).toPx()
            }
            // 能露出几分之几：默认只露 [StatusCardIconDefaultVisible]（图标本来就吃在
            // 卡片右缘外，参考 LSPosed 那张「已激活」卡），文字更宽时继续往右推，
            // 最少留 [StatusCardIconMinVisible]。
            val iconVisibleFraction = ((constraints.maxWidth - reservedPx - textMaxPx) / iconSizePx)
                .coerceIn(StatusCardIconMinVisible, StatusCardIconDefaultVisible)
            val iconShift = with(density) { (iconSizePx * (1f - iconVisibleFraction)).toDp() }

            // --- 卡面：关于页那套流动光场（同一份代码，不是另写一遍） ---
            //
            // `AboutHeroBackground` 是 Hero 背景本体，这里只是关掉底边渐隐：
            // 关于页下面还有内容要接，卡片是封闭表面，渐隐只会把下半张的光吃掉。
            // 关掉之后光场铺满卡面，Card 的 squircle 会把溢出的部分裁掉。
            AboutHeroBackground(
                time = fieldClock,
                darkTheme = darkTheme,
                alpha = alwaysVisible,
                // 状态色只换调色板，其余参数与关于页完全一致
                palettes = fieldPalettes,
                // 不再录 backdrop：图标已回退成普通矢量，没有东西要采样它了
                fadeBottom = false,
                modifier = Modifier.matchParentSize(),
            )

            // --- 右侧装饰图标：状态色、贴右缘出血（照 LSPosed 那张「已激活」卡）---
            //
            // 位置：`CenterEnd` + 向右偏移 = 只露 [StatusCardIconDefaultVisible]，
            // 其余吃到卡片外（Card 的 squircle 会裁掉），不是整颗摆在卡里。
            // 颜色：用状态色 [accent]（已激活亮绿 / 未激活红 / 未连接灰），
            // 不再跟随文字 —— 参考那张卡里对勾就是绿的。
            //
            // **两种图形必须写在两条分支里**：`TintedIcon` 内部按 painter 缓存，
            // 同一个调用点只改参数会复用上一颗图形（用户看到「已激活还挂着感叹号」）。
            Box(Modifier.matchParentSize()) {
                val glyphModifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = iconShift)
                    .size(StatusCardIconSize)
                if (state == FrameworkState.Active) {
                    TintedIcon(
                        imageVector = AppIcons.CheckCircle,
                        tint = accent,
                        modifier = glyphModifier,
                    )
                } else {
                    TintedIcon(
                        imageVector = AppIcons.ErrorCircle,
                        tint = accent,
                        modifier = glyphModifier,
                    )
                }
            }

            // --- 左侧三行文字：垂直居中 ---
            //
            // 配色按关于页来：标题用 heroAccent（与关于页名称同一个常量），
            // 版本与 API 两行用 heroCaption（名称色降透明度，关于页版本号同款）。
            // 字号在 3.2.1 按原比例整体放大 1.25 倍（24/14/15 → 30/18/19），
            // 三行的相对大小不变，只是整块更醒目。
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = StatusCardTextStartPadding),
            ) {
                Text(
                    text = title,
                    color = titleColor,
                    fontSize = StatusCardTitleSize,
                    fontWeight = FontWeight.Bold,
                )
                // 标题与版本贴得近一点：这两行是「一句主信息 + 它的补充」，
                // 空太多会读成两块
                Spacer(Modifier.height(2.dp))
                Text(
                    text = version,
                    color = subColor,
                    fontSize = StatusCardVersionSize,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = api,
                    color = subColor,
                    fontSize = StatusCardApiSize,
                )
            }
        }
    }
}

/**
 * 状态卡固定高度（`heightIn(min = ...)`）。
 *
 * 文字内容本身只有约 82dp（22 / 16 / 15sp 三行），这里给到 148dp 是为了让
 * 三行文字垂直居中、留白从容。
 *
 * **2.9.3 起这确实是卡片的实际高度**：装饰图标改放进 `matchParentSize` 容器后
 * 不再参与测量，卡片高度不再被图标顶高。之前（2.9.1 / 2.9.2）虽然写了这个 min，
 * 但图标分别有 150dp / 170dp，实际高度一直是「图标说了算」——
 * 也就是说 2.9.1 的「纵向拉长」其实并没有按这个值生效，2.9.3 才真正落到 148dp。
 *
 * 选 148dp 而不是「内容自然高度」还有一个原因：高度固定之后，
 * **只调字号不会再改变卡片高度**（用户 2.9.3 的要求）。
 */
private val StatusCardMinHeight = 148.dp

/**
 * 状态卡三行文字的字号（3.2.1 定稿）。
 *
 * 先按 1.25 倍整体放大到 30 / 18 / 19，再按用户反馈微调：主标题再大一档并加粗到
 * Bold（状态是这张卡的主信息），版本行加粗到 SemiBold（它与标题是同一句话的
 * 两段），API 行收小一档 —— 它是附注，不该和版本行同重。
 */
private val StatusCardTitleSize = 34.sp
private val StatusCardVersionSize = 18.sp
private val StatusCardApiSize = 16.sp

/** 图标默认露出多少（其余吃到卡片右缘外，与 LSPosed 那张卡一致） */
private const val StatusCardIconDefaultVisible = 0.75f

/**
 * 「已激活」的绿色（2.17.1 由 `0xFF1AAE6A` 加深到 `0xFF0E8A52`）。
 *
 * 原来的中绿在浅色卡面上偏亮，标题读起来发飘；加深一档之后标题稳下来。
 */
private val StatusActiveColor = Color(0xFF0E8A52)

/** 文字挤到没地方时，图标最少露出这个比例（再少就认不出图形了） */
private const val StatusCardIconMinVisible = 0.25f

/** 文字起始边距 */
private val StatusCardTextStartPadding = 16.dp

/** 文字与图标之间至少留的空隙，用来算「还剩多少给图标」 */
private val StatusCardIconGap = 12.dp

/**
 * 右侧装饰图标的尺寸（2.9.2 由 150dp 放大到 170dp，2.17.1 再到 260dp，
 * 2.17.2 收到 132dp 并改为**完整显示**、不再裁切）。
 *
 * 图标在 `matchParentSize` 容器里，所以这个尺寸**不影响卡片高度**。
 * 132dp 对 148dp 的卡片最小高度正好上下各留 8dp —— 图标与卡片近乎等高，
 * 对勾读得清，也不压过左侧文字。
 */
private val StatusCardIconSize = 132.dp

