package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import io.github.MVennn.GSAuxiliary.BuildConfig
import io.github.MVennn.GSAuxiliary.R
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Text

/**
 * 关于页的顶部视觉区（Hero）：流动渐变光场 + 染色 mark + 名称 + 版本。
 *
 * ## 来源
 *
 * 结构与动画参数复刻自 HyperIsland 的 `AboutPage.kt`（MIT，Copyright (c) 2026 1812z，
 * https://github.com/1812z/HyperIsland）。差异只有一处：
 *
 * 他的 mark / wordmark 用 miuix 0.9.4 的 `miuix-blur`（textureBlur + BlendColorEntry
 * 做 ColorDodge / LinearLight 混合）把背景纹理揉进图形；本项目因 AGP 版本锁在
 * miuix 0.9.3，没有那个 artifact（见 docs/BUILD.md 的版本约束），于是走他源码里
 * 的另一条渲染路径 —— 渐变蒙版（`BlendMode.SrcIn`），即他给不支持 RuntimeShader
 * 的设备准备的 fallback。观感上 mark 同样是被光场染色的单色剪影。
 *
 * 调色板沿用原版的蓝紫三组 —— 与 miuix 主题的主色系一致，不再另起一套金色。
 *
 * ## 三层结构与滚动
 *
 *   1. [AboutHeroBackground] —— 铺满整块 Hero（含状态栏后面），随滚动淡出 + 慢速视差
 *   2. 可滚动的卡片列表 —— 首项用等高的空白给 Hero 让位
 *   3. [AboutHeroContent] —— 固定在顶部，随滚动淡出并轻微缩小
 *
 * 背景「淡出」而不是「跟着滚走」：它铺到了状态栏后面，滚走会在顶部留一条空白带。
 *
 * ## 动画只在绘制阶段读取时间
 *
 * 时间与滚动进度都以 `() -> Float` 的形式往下传：Compose 的 `Canvas` /
 * `drawWithContent` 在绘制阶段读 snapshot 值只会重绘、不会重组；传 `Float`
 * 会让整棵 Hero 子树每帧重组。
 */

/** Hero 高度：屏高的这个比例，再夹在下面的上下限之间（矮屏不至于把卡片全挤下去） */
internal const val AboutHeroHeightFraction = 0.60f
internal val AboutHeroMinHeight = 300.dp
internal val AboutHeroMaxHeight = 560.dp

/** 背景层的总高度 = Hero 高度 + 这段余量，多出来的部分留给底部渐隐 */
internal val AboutHeroBackdropHeight = 180.dp

/** Hero 内容整体从垂直中心往下让一点：图标别顶在状态栏边上（原版同值） */
internal val AboutHeroContentOffset = 30.dp

/** 背景在滚过这段距离后完全淡出 —— 固定值而非屏高比例，原版同为 389dp */
internal val AboutBackgroundFadeDistance = 389.dp

/**
 * Hero 背景。
 *
 * @param time 动画时间（秒），**在绘制阶段读取**
 * @param darkTheme 当前配色是否深色（决定调色板）
 * @param alpha 整体不透明度，由滚动量算出；同样只在绘制阶段读取
 */
@Composable
internal fun AboutHeroBackground(
    time: () -> Float,
    darkTheme: Boolean,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
) {
    Canvas(
        modifier = modifier
            // 把这块光场录进 backdrop：mark 要采样它才能做成「透光的玻璃」
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .graphicsLayer {
                this.alpha = alpha()
                // DstIn 的渐隐需要一层独立图层，否则会作用到父图层、把页面其它内容也裁掉
                compositingStrategy = CompositingStrategy.Offscreen
            },
    ) {
        // 背景完全淡出后（滚过 AboutBackgroundFadeDistance）跳过整块绘制：
        // 渐变场 + 离屏合成 + DstIn 渐隐都不便宜，画了也是全透明的，
        // 这里直接 return 掉，滚远之后不再为这块光场花任何绘制开销。
        if (alpha() <= 0f) return@Canvas
        val t = time()
        val colors = animatedGradientColors(t, darkTheme)
        drawAboutGradientField(
            animationTime = t,
            colors = colors,
            fieldSize = size,
            sampleOrigin = Offset.Zero,
        )
        // 底边渐隐进页面底色（原版 0.68 处才开始收口）
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White,
                    0.68f to Color.White,
                    1f to Color.Transparent,
                ),
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}

/**
 * 只画「流动光场」这一层：不带底边渐隐、不录 backdrop、不跟滚动。
 *
 * 关于页 Hero 用它铺满首屏；概览页的 LSPosed 状态卡把它压进卡面，
 * 于是两张卡是同一套调色板与同一条时间轴（用户说的「用关于页的元素赋能」）。
 *
 * @param time 动画时间（秒），**只在绘制阶段读取**
 */
@Composable
internal fun HeroLightField(
    time: () -> Float,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val t = time()
        drawAboutGradientField(
            animationTime = t,
            colors = animatedGradientColors(t, darkTheme),
            fieldSize = size,
            sampleOrigin = Offset.Zero,
        )
    }
}

/**
 * Hero 前景：染色 mark + 名称 + 版本 + 定位语。
 *
 * @param progress 0（完全可见）→ 1（滚出视野），控制淡出与缩小；只在绘制阶段读取
 * @param time 动画时间（秒），mark 的染色渐变由它驱动
 */
@Composable
internal fun AboutHeroContent(
    progress: () -> Float,
    darkTheme: Boolean,
    time: () -> Float,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
) {
    Column(
        modifier = modifier.graphicsLayer {
            val p = progress().coerceIn(0f, 1f)
            alpha = 1f - p
            val scale = 1f - 0.10f * p
            scaleX = scale
            scaleY = scale
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // mark 与名称必须同色：一颗彩色的标配一行白字，会像从两个界面上抠下来的
        val accent = if (darkTheme) HeroAccentDark else HeroAccentLight
        val caption = accent.copy(alpha = if (darkTheme) 0.62f else 0.58f)
        Column(
            modifier = Modifier.graphicsLayer { translationY = AboutHeroContentOffset.toPx() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AboutMark(
                time = time,
                darkTheme = darkTheme,
                backdrop = backdrop,
                modifier = Modifier.size(88.dp),
            )
            Spacer(Modifier.height(12.dp))
            // 字号按上游 wordmark 的高度来（那张图 280×40dp，字面约 40dp），
            // 用文字代替图片时 40sp 才和它的视觉重量相当
            Text(
                text = stringResource(R.string.app_name),
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
                fontSize = 15.sp,
                color = caption,
            )
        }
    }
}

/**
 * mark：实心圆角方块，用负空间挖出一道上升的弧线和顶端的箭镞。
 *
 * ## 为什么它是玻璃，而不是一块纯色
 *
 * 参考实现的 mark 表面是「背景纹理经模糊与混合后的结果」——浅色下用 ColorBurn 把
 * 背景压成深紫，深色下用 ColorDodge 把背景提亮。于是同一颗标在两种主题下自动
 * 变成「背景的强对比版本」，看着就像一块透光的玻璃，而不是贴上去的色块。
 *
 * miuix 0.9.4 的 `miuix-blur` 拿不到（本项目锁 0.9.3），这里用项目里已有的
 * backdrop 库达成同一件事：采样 Hero 光场 → 模糊 → 叠一层染色。
 * 采样不到（backdrop 为空）时退回浅色渐变，至少不会画成空白。
 *
 * 绘制顺序（同一离屏图层内）：
 *   1. 玻璃：`drawBackdrop` 采样 + 模糊 + 染色
 *   2. 图形：`BlendMode.Clear` 抠出负空间
 *
 * 图形坐标与启动器前景同一套（见 ic_launcher_foreground.xml 的注释），
 * 连线宽都是同一份常量 —— 这样关于页的 mark 和桌面上的图标是同一颗标。
 */
@Composable
private fun AboutMark(
    time: () -> Float,
    darkTheme: Boolean,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    // 圆角按启动器图标同一比例（24/108 = 22.2%），mark 才和桌面那颗图标是一颗标
    val shape = RoundedCornerShape(22)
    val tint = if (darkTheme) MarkTintDark else MarkTintLight
    Box(
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                vibrancy()
                                blur(MarkBlur.toPx())
                            },
                            // 不要高光：mark 本身就是一块圆角玻璃，加描边会变成两条边
                            highlight = null,
                            onDrawSurface = { drawRect(tint) },
                        )
                    } else {
                        Modifier.background(
                            Brush.linearGradient(if (darkTheme) MarkFallbackDark else MarkFallbackLight),
                        )
                    },
                ),
        )
        Canvas(modifier = Modifier.matchParentSize()) {
            val unit = size.minDimension / MarkCanvasUnits
            // 一笔从右下扫上去的弧线 + 顶端的实心箭镞。早期的「涨—跌—涨」折线在
            // 挖空形态下会多出一个 V 形回踩，放大后看着像个歪勾；一根弧线只有一次
            // 转向，负空间越简单越像「标识」，所以换成了这一笔。
            val curve = Path().apply {
                moveTo(33.08f * unit, 75.39f * unit)
                quadraticTo(67.4f * unit, 75.39f * unit, 67.4f * unit, 50.94f * unit)
            }
            drawPath(
                path = curve,
                color = Color.Transparent,
                style = Stroke(width = MarkCurveStroke * unit, cap = StrokeCap.Round),
                blendMode = BlendMode.Clear,
            )
            val head = Path().apply {
                moveTo(67.4f * unit, 29.79f * unit)
                lineTo(57.06f * unit, 50.94f * unit)
                lineTo(77.74f * unit, 50.94f * unit)
                close()
            }
            drawPath(head, color = Color.Transparent, blendMode = BlendMode.Clear)
            // 描边把三个尖角磨圆，尺度与弧线的圆头一致
            drawPath(
                path = head,
                color = Color.Transparent,
                style = Stroke(width = MarkTriStroke * unit, join = StrokeJoin.Round),
                blendMode = BlendMode.Clear,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 绘制（以下函数复刻自 HyperIsland 的 AboutPage.kt，参数名保持一致便于对照）
// ---------------------------------------------------------------------------

/**
 * 一层线性渐变打底 + 四个径向光斑。
 *
 * 光斑坐标用 `sin/cos` 的正弦游走（频率各不相同）而不是随机数 —— 可复现、
 * 无状态、且永远不会走到边界上。
 *
 * @param sampleOrigin 采样原点偏移（原版给 textureBlur 用；本实现恒为 [Offset.Zero]，
 *   保留参数是为了与上游源码逐行对照）
 */
internal fun DrawScope.drawAboutGradientField(
    animationTime: Float,
    colors: List<Color>,
    fieldSize: Size,
    sampleOrigin: Offset,
    blendMode: BlendMode = BlendMode.SrcOver,
) {
    val strengthenedColors = colors.map(::strengthenGradientColor)
    val translucentPalette = strengthenedColors.any { it.alpha < 0.8f }
    val radius = fieldSize.maxDimension * 0.62f
    val motionTime = animationTime * BackgroundSpeed
    drawRect(
        brush = Brush.linearGradient(
            colors = strengthenedColors.map { color ->
                color.copy(
                    alpha = if (translucentPalette) {
                        color.alpha * 0.72f
                    } else {
                        0.58f
                    },
                )
            },
            start = Offset(-sampleOrigin.x, -sampleOrigin.y),
            end = Offset(
                fieldSize.width - sampleOrigin.x,
                fieldSize.height - sampleOrigin.y,
            ),
        ),
        blendMode = blendMode,
    )
    val centers = listOf(
        Offset(
            x = fieldSize.width * (0.18f + 0.10f * sin(motionTime)),
            y = fieldSize.height * (0.20f + 0.08f * cos(motionTime * 0.8f)),
        ),
        Offset(
            x = fieldSize.width * (0.82f + 0.10f * cos(motionTime * 0.9f)),
            y = fieldSize.height * (0.78f + 0.10f * sin(motionTime * 0.7f)),
        ),
        Offset(
            x = fieldSize.width * (0.22f + 0.12f * cos(motionTime * 0.65f)),
            y = fieldSize.height * (0.80f + 0.08f * sin(motionTime * 0.85f)),
        ),
        Offset(
            x = fieldSize.width * (0.80f + 0.12f * sin(motionTime * 0.72f)),
            y = fieldSize.height * (0.20f + 0.08f * cos(motionTime * 0.62f)),
        ),
    )
    centers.forEachIndexed { index, globalCenter ->
        val color = strengthenedColors[index]
        val localCenter = globalCenter - sampleOrigin
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(
                        alpha = if (translucentPalette) {
                            color.alpha * 0.96f
                        } else {
                            0.88f
                        },
                    ),
                    color.copy(alpha = 0f),
                ),
                center = localCenter,
                radius = radius,
            ),
            center = localCenter,
            radius = radius,
            blendMode = blendMode,
        )
    }
}

/** 饱和度 +18%、整体压暗 1.5%：深色下不会糊成一片灰，浅色下更透亮 */
private fun strengthenGradientColor(color: Color): Color {
    val average = (color.red + color.green + color.blue) / 3f
    val saturation = 1.18f
    val brightnessOffset = 0.015f
    return Color(
        red = (average + (color.red - average) * saturation - brightnessOffset).coerceIn(0f, 1f),
        green = (average + (color.green - average) * saturation - brightnessOffset).coerceIn(0f, 1f),
        blue = (average + (color.blue - average) * saturation - brightnessOffset).coerceIn(0f, 1f),
        alpha = color.alpha,
    )
}

/**
 * 按时间在调色板之间插值。
 *
 * 顺序刻意做成 1 → 0 → 1 → 2 的折返，而不是 0 → 1 → 2 → 0 的循环：
 * 折返两端共用同一块调色板，接缝处不会突然跳色。
 */
internal fun animatedGradientColors(
    animationTime: Float,
    dark: Boolean,
): List<Color> {
    val palettes = if (dark) DarkGradientPalettes else LightGradientPalettes
    val segmentValue = animationTime / ColorInterpolationSeconds
    val segment = floor(segmentValue).toInt() % 4
    val rawProgress = segmentValue - floor(segmentValue)
    val progress = rawProgress * rawProgress * (3f - 2f * rawProgress)
    val start = when (segment) {
        0 -> palettes[1]
        1 -> palettes[0]
        2 -> palettes[1]
        else -> palettes[2]
    }
    val end = when (segment) {
        0 -> palettes[0]
        1 -> palettes[1]
        2 -> palettes[2]
        else -> palettes[1]
    }
    return start.indices.map { index -> lerp(start[index], end[index], progress) }
}

// ---------------------------------------------------------------------------
// mark 的几何与颜色
// ---------------------------------------------------------------------------

/** 图形坐标所在的画布边长（与启动器前景一致，便于直接抄坐标） */
private const val MarkCanvasUnits = 108f

/** 弧线宽度（108 画布单位），与 ic_launcher_foreground.xml 的 strokeWidth 同值 */
private const val MarkCurveStroke = 8.46f

/** 箭镞描边宽度（108 画布单位）—— 只用来把三个尖角磨圆 */
private const val MarkTriStroke = 2.82f

/** 玻璃 mark 的模糊半径：够糊掉背景的色斑，又不至于糊成一块纯色 */
private val MarkBlur = 16.dp

/** 名称与版本：深色下浅蓝紫，浅色下深紫红 —— mark 的染色也取这两套 */
private val HeroAccentDark = Color(0xFFC4C0FF)
private val HeroAccentLight = Color(0xFF5E2550)

/**
 * mark 叠在模糊背景上的染色。
 *
 * 浅色下压深（对应参考实现的 ColorBurn）、深色下提亮（对应 ColorDodge）——
 * 目的都是让 mark 与背景拉开一个明确的明度差，图形才立得住。
 */
private val MarkTintDark = Color(0xFFD6D3FF).copy(alpha = 0.55f)
private val MarkTintLight = Color(0xFF5A2350).copy(alpha = 0.62f)

/** 拿不到 backdrop 时的退路：纯色渐变，观感比玻璃硬一点，但不会画成空白 */
private val MarkFallbackDark = listOf(Color(0xFFD8D5FF), Color(0xFF9A93FF))
private val MarkFallbackLight = listOf(Color(0xFF7A3570), Color(0xFF4C1B44))

// ---------------------------------------------------------------------------
// 调色板与参数（数值与上游一致）
// ---------------------------------------------------------------------------

/** 光斑游走速度 */
private const val BackgroundSpeed = 0.12f

/** 一次完整换色的秒数 */
private const val ColorInterpolationSeconds = 12f

/** 浅色：粉蓝晨光 */
private val LightGradientPalettes = listOf(
    listOf(Color(1f, 0.90f, 0.94f), Color(1f, 0.84f, 0.89f), Color(0.97f, 0.73f, 0.82f), Color(0.64f, 0.65f, 0.98f)),
    listOf(Color(0.58f, 0.74f, 1f), Color(1f, 0.90f, 0.93f), Color(0.74f, 0.76f, 1f), Color(0.97f, 0.77f, 0.84f)),
    listOf(Color(0.98f, 0.86f, 0.90f), Color(0.60f, 0.73f, 0.98f), Color(0.92f, 0.93f, 1f), Color(0.56f, 0.69f, 1f)),
)

/** 深色：蓝紫夜光，与 miuix 主色同系 */
private val DarkGradientPalettes = listOf(
    listOf(
        Color(0.20f, 0.06f, 0.88f, 0.40f),
        Color(0.30f, 0.14f, 0.55f, 0.50f),
        Color(0f, 0.64f, 0.96f, 0.50f),
        Color(0.11f, 0.16f, 0.83f, 0.40f),
    ),
    listOf(
        Color(0.07f, 0.15f, 0.79f, 0.50f),
        Color(0.62f, 0.21f, 0.67f, 0.50f),
        Color(0.06f, 0.25f, 0.84f, 0.50f),
        Color(0f, 0.20f, 0.78f, 0.50f),
    ),
    listOf(
        Color(0.58f, 0.30f, 0.74f, 0.40f),
        Color(0.27f, 0.18f, 0.60f, 0.50f),
        Color(0.66f, 0.26f, 0.62f, 0.50f),
        Color(0.12f, 0.16f, 0.70f, 0.60f),
    ),
)
