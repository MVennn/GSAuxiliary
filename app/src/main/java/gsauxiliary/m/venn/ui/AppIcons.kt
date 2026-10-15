package gsauxiliary.m.venn.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 本项目用到的全部矢量图标。
 *
 * 为什么不用 `androidx.compose.material:material-icons-extended`：
 * 那个 AAR 把 Material 全量图标（Filled / Outlined / Rounded / Sharp / TwoTone
 * 五种风格）都打进了包，dex 里实测有 **11400 个类**，占整个 APK 类总数（26553）
 * 的 43%，是安装体积虚高的唯一主因。而本模块界面只用到 7 个图标。
 *
 * 这里用 Material Design 官方 24dp 图标的 path 数据自建 ImageVector，
 * 零额外依赖、只生成 7 个类。
 *
 * 维护约定：新增图标时**不要**引回 material-icons 依赖，照抄本文件的写法加一条即可。
 * path 数据取自 Material Design Icons 官方 24x24 网格，坐标原点在左上角。
 */
internal object AppIcons {

    /** 解析 Material 官方 24x24 网格的 path 字符串，构建 ImageVector。 */
    private fun icon(
        name: String,
        pathData: String,
        autoMirror: Boolean = false,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        // 实际颜色由 Icon(imageVector, tint = ...) 的 ColorFilter 覆盖，
        // 这里给个确定值只是为了让向量本身可以被单独渲染。
        fill = SolidColor(Color.Black),
    ).build()

    /**
     * 描边风格图标：只描边、不填充（用于「大对勾 / 大感叹号」这类线框图形）。
     *
     * 与 [icon] 一样，描边颜色只是占位，实际颜色由 `Icon(..., tint = ...)`
     * 的 `ColorFilter` 覆盖，所以调用方可以随便换色。
     */
    private fun strokedIcon(
        name: String,
        pathData: String,
        strokeWidth: Float = 1.9f,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = strokeWidth,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

    /** 返回箭头（RTL 布局下自动镜像）。 */
    val ArrowBack: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            name = "ArrowBack",
            autoMirror = true,
            pathData = "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
        )
    }

    /** 对勾（用于选中态、列表勾选项）。 */
    val Check: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            name = "Check",
            pathData = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z",
        )
    }

    /** 放大镜（功能页搜索栏）。 */
    val Search: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            name = "Search",
            pathData = "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 " +
                "13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 " +
                "4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zm-6,0C7.01,14 " +
                "5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
        )
    }

    /** 齿轮（设置板块）。 */
    val Settings: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            name = "Settings",
            pathData = "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 " +
                "-0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 " +
                "0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c" +
                "-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 " +
                "-0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 " +
                "8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 " +
                "2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 " +
                "0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 " +
                "0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 " +
                "0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 " +
                "1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 " +
                "0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 " +
                "3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
        )
    }

    /** 信息（关于板块）。 */
    val Info: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            name = "Info",
            pathData = "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 " +
                "12,2zm1,15h-2v-6h2v6zm0,-8h-2V7h2v2z",
        )
    }

    /** 九宫格（模块板块）。 */
    val Apps: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            name = "Apps",
            pathData = "M4,8h4V4H4v4zm6,12h4v-4h-4v4zm-6,0h4v-4H4v4zm0,-6h4v-4H4v4zm6,0h4v-4h-4v4z" +
                "m6,-10v4h4V4h-4zm-6,4h4V4h-4v4zm6,6h4v-4h-4v4zm0,6h4v-4h-4v4z",
        )
    }

    /** 拼图块（功能板块）。 */
    val Extension: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            name = "Extension",
            pathData = "M20.5,11H19V7c0,-1.1 -0.9,-2 -2,-2h-4V3.5C13,2.12 11.88,1 10.5,1S8,2.12 " +
                "8,3.5V5H4c-1.1,0 -1.99,0.9 -1.99,2v3.8H3.5c1.49,0 2.7,1.21 2.7,2.7s-1.21,2.7 " +
                "-2.7,2.7H2V20c0,1.1 0.9,2 2,2h3.8v-1.5c0,-1.49 1.21,-2.7 2.7,-2.7 " +
                "1.49,0 2.7,1.21 2.7,2.7V22H17c1.1,0 2,-0.9 2,-2v-4h1.5c1.38,0 2.5,-1.12 " +
                "2.5,-2.5S21.88,11 20.5,11z",
        )
    }

    /**
     * 圆形对勾（概览页状态卡：模块已激活）。
     *
     * 两段子路径共用同一套描边参数：外圈圆 + 里面的勾。
     * 用描边而不是填充，是为了在 52dp 下依然保持细线条的「线框」观感 ——
     * 和 LSPosed 页面顶部那张卡的视觉一致。
     *
     * ## 圆为什么用三次贝塞尔而不是 `A` 圆弧
     *
     * `tools/verify_icons.py` 的 path 解析器只认 `M/L/H/V/C/S/Q/T/Z`，遇到 `A`
     * 会直接报「暂不支持的命令」。用四段三次贝塞尔（控制点偏移 = 半径 × 0.5522847，
     * 即标准的圆近似常数 4/3·tan(π/8)）画出来的圆在视觉上和真圆无法区分，
     * 又能被校验脚本完整解析，所以这里统一用贝塞尔。
     */
    val CheckCircle: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon(
            name = "CheckCircle",
            // 圆：中心 (12,12)、半径 9.4，四段三次贝塞尔顺时针闭合；
            // 勾：左下 → 拐点 → 右上
            pathData = "M12,2.6 " +
                "C17.19,2.6 21.4,6.81 21.4,12 " +
                "C21.4,17.19 17.19,21.4 12,21.4 " +
                "C6.81,21.4 2.6,17.19 2.6,12 " +
                "C2.6,6.81 6.81,2.6 12,2.6 Z " +
                "M7.2,12.4 l3.2,3.2 l6.4,-6.8",
        )
    }

    /**
     * 圆形感叹号（概览页状态卡：模块未激活）。
     *
     * 圆 + 一根竖线 + 一个点。点用「极短线段 + 圆头笔帽」实现，
     * 因为 `PathParser` 里零长度线段在部分渲染器上不会画出来。
     * 圆的画法与 [CheckCircle] 相同（三次贝塞尔近似，理由见该处注释）。
     */
    val ErrorCircle: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        strokedIcon(
            name = "ErrorCircle",
            pathData = "M12,2.6 " +
                "C17.19,2.6 21.4,6.81 21.4,12 " +
                "C21.4,17.19 17.19,21.4 12,21.4 " +
                "C6.81,21.4 2.6,17.19 2.6,12 " +
                "C2.6,6.81 6.81,2.6 12,2.6 Z " +
                "M12,7.2 l0,5.6 M12,16.5 l0,0.2",
        )
    }
}
