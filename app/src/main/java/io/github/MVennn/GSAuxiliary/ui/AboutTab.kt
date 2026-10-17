package io.github.MVennn.GSAuxiliary.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.MVennn.GSAuxiliary.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Tab 4 · 关于 —— 顶部 Hero、作者、开源鸣谢、第三方组件声明、免责声明。
 *
 * ## 布局：背景 / 内容 / 前景 三层
 *
 * ```
 * Box
 *  ├─ AboutHeroBackground   贴屏顶的流动渐变，随滚动淡出 + 慢速上移（视差 0.12）
 *  ├─ Column(verticalScroll) 首项 Spacer 让出 Hero 高度，其后是各分组卡片
 *  └─ AboutHeroContent      固定在上方的图标与标题，随滚动淡出、缩小、上移
 * ```
 *
 * 三层都在同一个 `Box` 里，滚动量只从 [scrollState] 一处来：
 *   - 背景 `alpha` / 位移、前景 `progress` 都走 `derivedStateOf`，滚动时只重组成
 *     用到它们的节点，不会每帧重建整页；
 *   - 动画时间用 `mutableFloatStateOf` 装在 `remember` 里、以 lambda 形式传给绘制层，
 *     于是每帧只是重绘，不触发组合（理由见 [AboutHeroBackground] 的注释）。
 *
 * ## 与顶栏的关系
 *
 * 切到关于页时顶栏整条向上滑出（见 [HomeScaffold] 的 topBarHide）：Hero 要吃到
 * 状态栏后面，顶栏留着会把渐变头部截成一条直线。关于页拿到的 [LocalTopInset]
 * 恒为 0（按板块取值，不跟顶栏收起进度联动），所以 Hero 高度、卡片起点在整段
 * 切换动画里都是定值 —— 页面只做横向滑动，不会被顶栏归位拽着上下走。
 *
 * ## 版本号
 *
 * Hero 里给的是一行「版本(构建号)」，作为「这是哪一版」的身份信息；「概览」页
 * 那份是完整的构建信息（版本、构建时间、运行环境）。两处不重复摆同一组数据。
 */
@Composable
internal fun AboutTab(
    onOpenDisclaimer: () -> Unit,
    onOpenNotice: () -> Unit,
) {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()
    val darkTheme = LocalDarkTheme.current
    val topInset = LocalTopInset.current
    val linkFailed = stringResource(R.string.oss_link_failed)
    // 可跳转地址一律先取出来，避免在 onClick 里调 stringResource
    val githubUrl = stringResource(R.string.about_value_github)
    val repoUrl = stringResource(R.string.about_value_repo_url)

    // Hero 高度：屏高比例 + 上下限（矮屏别把「关于作者」整组挤到屏幕外）
    val heroHeight = (LocalConfiguration.current.screenHeightDp.dp * AboutHeroHeightFraction)
        .coerceIn(AboutHeroMinHeight, AboutHeroMaxHeight)
    val density = LocalDensity.current
    val heroHeightPx = with(density) { heroHeight.toPx() }

    // Hero 光场的采样源：mark 要「透出背后的光」，就得先把这块渐变录进 backdrop。
    // 录的起点是页面底色，所以即使渐变还没画上来，玻璃也是干净的。
    val pageSurface = MiuixTheme.colorScheme.surface
    val heroBackdrop = rememberLayerBackdrop {
        drawRect(pageSurface)
        drawContent()
    }


    // 动画时间：只在绘制阶段被读取（见文件头注释），这里只负责推进
    val heroTime = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var previousFrame = 0L
        while (true) {
            withFrameNanos { frameTime ->
                if (previousFrame != 0L) {
                    heroTime.floatValue += (frameTime - previousFrame) / 1_000_000_000f
                }
                previousFrame = frameTime
            }
        }
    }
    // 稳定实例：否则每次重组都给绘制层递一个新 lambda，等于每帧重新布置一次观察者
    val heroTimeProvider = remember(heroTime) { { heroTime.floatValue } }

    // 滚动派生的两个量：包成 State 并以 lambda 往下传，组合阶段**不读**它们，
    // 于是滚动时只有绘制层失效，整页不会每帧重组。
    //
    // 两段节奏照搬上游（AboutPage.kt）：
    //   - 背景在 389dp 的滚动距离内淡到看不见（固定值，不随屏高走）；
    //   - logo 从 Hero 的 25% 处开始退，用掉 35% 的行程 —— 等背景还很亮的时候
    //     它就已经走了大半，所以不会出现「一片黑里挂着一个图标」的过渡。
    val backgroundFadePx = remember(density) { with(density) { AboutBackgroundFadeDistance.toPx() } }
    val logoFadeStartPx = remember(heroHeightPx) { heroHeightPx * 0.25f }
    val logoFadeDistancePx = remember(heroHeightPx) { heroHeightPx * 0.35f }
    val backdropAlpha = remember(backgroundFadePx) {
        derivedStateOf { (1f - scrollState.value / backgroundFadePx).coerceIn(0f, 1f) }
    }
    val heroProgress = remember(logoFadeStartPx, logoFadeDistancePx) {
        derivedStateOf {
            ((scrollState.value - logoFadeStartPx) / logoFadeDistancePx).coerceIn(0f, 1f)
        }
    }
    val backdropAlphaProvider = remember(backdropAlpha) { { backdropAlpha.value } }
    val heroProgressProvider = remember(heroProgress) { { heroProgress.value } }

    fun open(url: String) {
        if (!openUrl(ctx, url)) {
            Toast.makeText(ctx, linkFailed, Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AboutHeroBackground(
            time = heroTimeProvider,
            darkTheme = darkTheme,
            alpha = backdropAlphaProvider,
            backdrop = heroBackdrop,
            modifier = Modifier
                .fillMaxWidth()
                .height(heroHeight + topInset + AboutHeroBackdropHeight)
                // 视差：背景比手指慢，才像「远处的光」而不是贴在内容上的色块
                .graphicsLayer { translationY = -scrollState.value * 0.12f },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            // Hero 让位：玻璃形态下还要额外让出顶栏那一段（背景已经画到顶栏后面去了）
            Spacer(Modifier.height(heroHeight + topInset))

            // 2.17.2 起关于页只回答「谁写的、用了谁的东西、有什么后果」：
            // 「模块信息」整组（模块名称 / 版本 / 作用范围 / 目标框架）与「技术细节」
            // 整组（Hook 入口 / 挂钩点 / 判定依据 / 控件 ID / 配置通道）都请出了界面。
            // 完整档案在 docs/ARCHITECTURE.md 与 docs/REVERSE-ENGINEERING.md。
            SmallTitle(stringResource(R.string.section_author))
            DeveloperCard(
                name = stringResource(R.string.about_value_developer),
                handle = stringResource(R.string.about_value_handle),
                onClick = { open(githubUrl) },
            )
            Card(modifier = Modifier.cardPadding()) {
                BasicComponent(
                    title = stringResource(R.string.about_license),
                    summary = stringResource(R.string.about_value_license),
                )
                // 项目仓库：与作者卡同样跳浏览器（GitHub 主页已由上面那张卡承担）
                BasicComponent(
                    title = stringResource(R.string.about_repo),
                    summary = repoUrl,
                    endActions = { ArrowRightIcon() },
                    onClick = { open(repoUrl) },
                )
            }

            // --- 开源鸣谢：逐条可点击跳转 ---
            SmallTitle(stringResource(R.string.section_open_source))
            Card(modifier = Modifier.cardPadding()) {
                BasicComponent(
                    title = stringResource(R.string.oss_miuix),
                    summary = stringResource(R.string.oss_miuix_desc),
                    endActions = { ArrowRightIcon() },
                    onClick = { open("https://github.com/miuix-kotlin-multiplatform/miuix") },
                )
                BasicComponent(
                    title = stringResource(R.string.oss_lsposed),
                    summary = stringResource(R.string.oss_lsposed_desc),
                    endActions = { ArrowRightIcon() },
                    onClick = { open("https://github.com/LSPosed/LSPosed") },
                )
                BasicComponent(
                    title = stringResource(R.string.oss_liquid_glass),
                    summary = stringResource(R.string.oss_liquid_glass_desc),
                    endActions = { ArrowRightIcon() },
                    onClick = { open("https://github.com/Kyant0/AndroidLiquidGlass") },
                )
                BasicComponent(
                    title = stringResource(R.string.oss_compose),
                    summary = stringResource(R.string.oss_compose_desc),
                    endActions = { ArrowRightIcon() },
                    onClick = { open("https://developer.android.com/jetpack/compose") },
                )
                BasicComponent(
                    title = stringResource(R.string.oss_kotlin),
                    summary = stringResource(R.string.oss_kotlin_desc),
                    endActions = { ArrowRightIcon() },
                    onClick = { open("https://kotlinlang.org") },
                )
                BasicComponent(
                    title = stringResource(R.string.oss_material_icons),
                    summary = stringResource(R.string.oss_material_icons_desc),
                    endActions = { ArrowRightIcon() },
                    onClick = { open("https://fonts.google.com/icons") },
                )
                // 本条不是代码依赖，是设计上的「站在别人肩膀上」，写明白免得被当成
                // 代码来源（本页视觉与交互思路参考该项目，实现为本项目自写）
                BasicComponent(
                    title = stringResource(R.string.oss_hyperisland),
                    summary = stringResource(R.string.oss_hyperisland_desc),
                    endActions = { ArrowRightIcon() },
                    onClick = { open("https://github.com/1812z/HyperIsland") },
                )
                // 第三方组件声明（NOTICE 随 APK 分发，二级页展示；Apache-2.0 §4 的 attribution 载体）
                BasicComponent(
                    title = stringResource(R.string.about_third_party),
                    summary = stringResource(R.string.about_third_party_desc),
                    endActions = { ArrowRightIcon() },
                    onClick = onOpenNotice,
                )
            }

            // --- 免责声明：一行摘要，点进去看完整文本 ---
            SmallTitle(stringResource(R.string.section_disclaimer))
            Card(modifier = Modifier.cardPadding()) {
                BasicComponent(
                    title = stringResource(R.string.disclaimer_title),
                    summary = stringResource(R.string.disclaimer_body),
                    endActions = { ArrowRightIcon() },
                    onClick = onOpenDisclaimer,
                )
            }

            Spacer(Modifier.height(24.dp + LocalDockInset.current))
        }

        AboutHeroContent(
            progress = heroProgressProvider,
            darkTheme = darkTheme,
            time = heroTimeProvider,
            backdrop = heroBackdrop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = topInset)
                .height(heroHeight),
        )
    }
}

/**
 * 作者卡：圆形头像 + 名字 + GitHub 账号，整卡可点（跳 GitHub）。
 *
 * 头像用的是作者的 GitHub 头像（拉自 github.com/MVennn.png，240×240，
 * `drawable-nodpi` 保证不随密度放大）。外面那道 2dp 白边与参考实现一致 ——
 * 没有它，深色照片贴在深色卡片上会糊成一坨、看不出是圆的。
 */
@Composable
private fun DeveloperCard(
    name: String,
    handle: String,
    onClick: () -> Unit,
) {
    val avatarShape = CircleShape
    Card(
        // 与下面「开源协议 / 项目仓库」那张卡之间留一道缝：两张卡贴在一起会
        // 被读成一整张卡中间断了条线（分组内部的多行才该无缝合在一张卡里）
        modifier = Modifier
            .cardPadding()
            .padding(bottom = 12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.about_developer_avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(avatarShape)
                    .border(2.dp, Color.White.copy(alpha = 0.68f), avatarShape),
            )
            Column(
                modifier = Modifier
                    .padding(start = 14.dp)
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = handle,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            ArrowRightIcon()
        }
    }
}

/**
 * 免责声明全文（二级页内容）。
 *
 * 正文是一整块 Markdown（见 `R.string.disclaimer_full`），交给 [MarkdownText] 渲染：
 * 一级标题、二级标题、加粗、斜体、列表、引用各走各的字号与字重，长文才有结构。
 *
 * 文案必须是「单行 + `\n` 转义」形态 —— Android 会折叠字符串里的真实换行，
 * 一折叠空行就没了，Markdown 的分段和标题全部失效。
 */
@Composable
internal fun DisclaimerContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            MarkdownText(
                markdown = stringResource(R.string.disclaimer_full),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        // 二级页里 LocalDockInset 恒为 0（那口子是给一级页悬浮底栏的），
        // 这里让的是手势导航条的高度 —— 见 SubScreen 的注释。
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/**
 * 第三方组件声明（二级页内容）。
 *
 * 正文读的是随 APK 分发的 `assets/NOTICE.txt`（由根目录 [NOTICE] 复制进来，
 * 改许可证信息时两处要同步）。与免责声明不同它是一份真文件而非 string 资源
 * —— Apache-2.0 §4 要求分发包里带着 NOTICE，string 资源在编译期会并进
 * resources.arsc，作为「文件随包分发」的载体不够直白。
 */
@Composable
internal fun NoticeContent() {
    val ctx = LocalContext.current
    var notice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        notice = runCatching {
            ctx.assets.open("NOTICE.txt").bufferedReader().use { it.readText() }
        }.getOrElse { "" }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            if (notice.isNotEmpty()) {
                MarkdownText(
                    markdown = notice,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
        // 同免责声明页：这里让的是手势导航条的高度 —— 见 SubScreen 的注释。
        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
