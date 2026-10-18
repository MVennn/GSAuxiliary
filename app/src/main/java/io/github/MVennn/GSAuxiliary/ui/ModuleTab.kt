package io.github.MVennn.GSAuxiliary.ui

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
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

    // 框架信息一次取全，只喂给顶部状态卡
    val framework = remember(connected.value, unknown, unreadable, noFramework) {
        val svc = FrameworkBridge.service()
        if (svc == null) {
            // 连都没连上：框架没装 / 没启用模块 / 服务已断开
            FrameworkInfo(
                state = FrameworkState.Disconnected,
                version = noFramework,
                api = "—",
            )
        } else {
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
            FrameworkInfo(
                state = if (activated) FrameworkState.Active else FrameworkState.Inactive,
                version = version,
                api = api,
            )
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
 * **3.2.1 起这张卡就是「缩小版的关于」**：卡面光场、文字配色、图标玻璃全部复用
 * [AboutHeroBackground] / [heroAccent] / [heroCaption] / [HeroGlassGlyph]，
 * 一行视觉代码都没有另写 —— 关于页改什么，这张卡跟着改什么。
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
 * ## 三态配色（2.17.1）
 *
 * 状态从「激活 / 未激活」两态扩成三态，颜色各自独立：
 *
 *  | 状态 | 条件 | 颜色 |
 *  |---|---|---|
 *  | 已激活 | 连上框架服务，且作用域非空、版本可读 | [StatusActiveColor] |
 *  | 未激活 | 连上框架服务，但作用域为空 / 信息读不出来 | `colorScheme.error` |
 *  | 未连接 | 拿不到框架服务（没装 LSPosed、模块没启用、服务已断开） | `colorScheme.onBackgroundVariant` |
 *
 * **3.2.1 起色只用一处**：`accent` 只染右侧那块玻璃底板的染色层，卡面不再是
 * 「状态色 @0.14」而是关于页的流动光场，标题与副行改用 [heroAccent] / [heroCaption]
 * —— 三态的区别落在底板颜色与文案上，文字本身保持关于页的紫／浅紫。
 *
 * 图标走 [AppIcons] 自绘（不引图标库，理由见该文件注释），图形本身挖进玻璃底板
 * （[HeroGlassGlyph]），与关于页 mark 同一块玻璃、同一种挖法。
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
    // 状态色只用来**染玻璃底板**（对勾那块玻璃），文字取关于页那套配色 ——
    // 卡面是彩色光场，标题再跟状态色走就会互相打架。
    val accent = when (state) {
        FrameworkState.Active -> StatusActiveColor
        FrameworkState.Inactive -> MiuixTheme.colorScheme.error
        FrameworkState.Disconnected -> MiuixTheme.colorScheme.onBackgroundVariant
    }
    val darkTheme = LocalDarkTheme.current
    val surface = MiuixTheme.colorScheme.surface

    // 光场采样源：与关于页完全同一条链路 —— 先把卡面光场录进 backdrop，
    // 图标那块玻璃才有东西可采（`drawRect(surface)` 打底，玻璃在渐变还没画上来时
    // 也是干净的，而不是透出卡片下面的东西）。
    val cardBackdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }

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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = StatusCardMinHeight),
        ) {
            // --- 卡面：关于页那套流动光场（同一份代码，不是另写一遍） ---
            //
            // `AboutHeroBackground` 是 Hero 背景本体，这里只是关掉底边渐隐：
            // 关于页下面还有内容要接，卡片是封闭表面，渐隐只会把下半张的光吃掉。
            // 关掉之后光场铺满卡面，Card 的 squircle 会把溢出的部分裁掉。
            AboutHeroBackground(
                time = fieldClock,
                darkTheme = darkTheme,
                alpha = alwaysVisible,
                backdrop = cardBackdrop,
                fadeBottom = false,
                modifier = Modifier.matchParentSize(),
            )

            // --- 右侧装饰图标：关于页 mark 的做法，挖进同一块玻璃 ---
            //
            // 玻璃底板采样卡面光场 → 模糊 → 按状态染色，再把对勾 / 感叹号用
            // `BlendMode.Clear` 挖成负空间（`HeroGlassGlyph`，与 mark 共用 GlassPlate）。
            // 图形取自 AppIcons 的路径常量，与列表里那份图标是同一个图形。
            //
            // 尺寸 132dp 对 148dp 的卡高正好上下各留 8dp；放在 matchParentSize
            // 容器里不参与测量，所以调图标尺寸不会把卡片撑高。
            Box(Modifier.matchParentSize()) {
                HeroGlassGlyph(
                    darkTheme = darkTheme,
                    backdrop = cardBackdrop,
                    pathData = if (state == FrameworkState.Active) {
                        AppIcons.CheckCirclePath
                    } else {
                        AppIcons.ErrorCirclePath
                    },
                    tint = accent.copy(alpha = if (darkTheme) 0.55f else 0.62f),
                    fallback = listOf(
                        accent.copy(alpha = 0.86f),
                        accent.copy(alpha = 0.52f),
                    ),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = StatusCardIconEndPadding)
                        .size(StatusCardIconSize),
                )
            }

            // --- 左侧三行文字：垂直居中，字号与节奏照旧 ---
            //
            // 配色按关于页来：标题用 heroAccent（与关于页名称同一个常量），
            // 版本与 API 两行用 heroCaption（名称色降透明度，关于页版本号同款）。
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    // 右侧留出图标那块玻璃的整个宽度：它现在是**不透明的玻璃板**
                    // （以前是 0.30 的水印，文字压上去也无所谓），文字不能钻到下面去。
                    // 中文三行都很短，英文最长的「Not activated」约 144dp，172dp 放得下。
                    .padding(
                        start = 16.dp,
                        end = StatusCardIconSize + StatusCardIconEndPadding,
                    ),
            ) {
                Text(
                    text = title,
                    color = heroAccent(darkTheme),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = version,
                    color = heroCaption(darkTheme),
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = api,
                    color = heroCaption(darkTheme),
                    fontSize = 15.sp,
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
 * 「已激活」的绿色（2.17.1 由 `0xFF1AAE6A` 加深到 `0xFF0E8A52`）。
 *
 * 原来的中绿在浅色卡面上偏亮，标题读起来发飘。加深一档之后标题稳下来，
 * 卡面底色（同一颜色 `alpha = 0.14`）几乎看不出变化。
 */
private val StatusActiveColor = Color(0xFF0E8A52)

/**
 * 右侧装饰图标的尺寸（2.9.2 由 150dp 放大到 170dp，2.17.1 再到 260dp，
 * 2.17.2 收到 132dp 并改为**完整显示**、不再裁切）。
 *
 * 图标在 `matchParentSize` 容器里，所以这个尺寸**不影响卡片高度**。
 * 132dp 对 148dp 的卡片最小高度正好上下各留 8dp —— 图标与卡片近乎等高，
 * 对勾读得清，也不压过左侧文字。
 */
private val StatusCardIconSize = 132.dp

/**
 * 图标右缘距卡片右缘的距离（2.17.2 新增，16dp）。
 *
 * 取 0 时图标贴着卡边；留 16dp 与左侧文字的 16dp 起始边距对称，
 * 也给卡片圆角让出余地。
 */
private val StatusCardIconEndPadding = 16.dp
