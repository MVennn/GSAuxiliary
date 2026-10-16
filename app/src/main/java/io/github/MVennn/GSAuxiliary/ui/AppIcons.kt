package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 本项目用到的全部矢量图标。
 *
 * 为什么不用 `androidx.compose.material:material-icons-extended`：
 * 那个 AAR 把 Material 全量图标（Filled / Outlined / Rounded / Sharp / TwoTone
 * 五种风格）都打进了包，dex 里实测有 **11400 个类**，占整个 APK 类总数（26553）
 * 的 43%，是安装体积虚高的唯一主因。而本模块界面只用到 10 个图标。
 *
 * 这里用 Material Design 官方 24dp 图标的 path 数据自建 ImageVector，
 * 零额外依赖、只生成 10 个类。
 *
 * 维护约定：新增图标时**不要**引回 material-icons 依赖，照抄本文件的写法加一条即可。
 * path 数据取自 Material Design Icons 官方 24x24 网格，坐标原点在左上角。
 */
internal object AppIcons {

    /**
     * 与 [icon] 相同，但视口尺寸自定 —— 给「不是 24×24 网格」的图标用
     * （目前只有从 Miuix 图标转过来的 [ArrowDown]，视口是 16×10）。
     */
    private fun iconOf(
        name: String,
        width: Dp,
        height: Dp,
        pathData: String,
        viewportWidth: Float = width.value,
        viewportHeight: Float = height.value,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = width,
        defaultHeight = height,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

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

    /** 竖排三点（方案行的操作菜单触发）。 */
    val MoreVert: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            name = "MoreVert",
            pathData = "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 " +
                "0.9,2 2,2zm0,2c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 " +
                "-0.9,-2 -2,-2zm0,6c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 " +
                "-0.9,-2 -2,-2z",
        )
    }

    /**
     * 朝右的箭头（进入下一级的行用）—— Miuix `ArrowRight` 的原路径（10×16 视口，
     * 1dp 细笔画）。
     *
     * 为什么不直接用 `ArrowPreference` 里那颗：库里的箭头走
     * `Image(colorFilter = ColorFilter.tint(...))`，也就是**画笔记账层**的染色 ——
     * 在这台机器上整体不生效（见 [Widgets.TintedIcon] 的注释），于是那些箭头永远
     * 画成占位黑、不跟亮暗色走。要全 App 一套观感，只能连右箭头也自己画。
     */
    val ArrowRight: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        iconOf(
            name = "ArrowRight",
            width = 10.dp,
            height = 16.dp,
            pathData = "M1.65,1.469 " +
                "C1.929,1.19 2.381,1.19 2.66,1.469 " +
                "L8.721,7.53 " +
                "C9.0,7.809 9.0,8.261 8.721,8.54 " +
                "L2.66,14.601 " +
                "C2.381,14.88 1.929,14.88 1.65,14.601 " +
                "C1.371,14.322 1.371,13.87 1.65,13.591 " +
                "L7.205,8.035 " +
                "L1.65,2.479 " +
                "C1.371,2.2 1.371,1.748 1.65,1.469 Z",
        )
    }

    /**
     * 朝下的箭头（会呼出弹窗的行用）。
     *
     * 会**呼出弹窗**的行（主题设置 / 语言 / 日志等级 / 聚合方式 / 周期 / 提醒时效）用它
     * 替掉 Miuix `ArrowPreference` 那颗朝右的箭头 —— 朝右的意思是「进入下一级页面」。
     *
     * ## 为什么用 Miuix 那条路径转 90°
     *
     * 第一版是照 Material 的 `expand_more` 画的（填充三角那种粗箭头，18dp 方框），
     * 上机后被指出「比旁边那颗箭头粗/深、还比它小、左右也没对齐」—— 因为 Miuix 的
     * `ArrowRight` 是 **10×16 视口里的 1dp 细笔画**，两者根本不是一套粗细。
     * 现在直接取库里的 `ArrowRight` 路径（`icon/basic/ArrowRight.kt`，
     * `M 1.65,1.469 … Z`）绕原点旋转 90°，得到 16×10 的同款细笔画朝下箭头：
     * 粗细、颜色（同样由调用方给 `onSurfaceVariantActions`）、右缘留白都对得上。
     */
    val ArrowDown: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        iconOf(
            name = "ArrowDown",
            // 画的是 16×10 的路径（右箭头转过来），但**声明尺寸放大 1.25 倍**：
            // 屏幕上一颗朝下的「V」比朝右的「>」显小（同样面积，视觉高度只有 10:16），
            // 上机后反馈「有点小」就是它。视口仍是 16×10，笔画按 1.25 倍缩放（1.25dp），
            // 与旁边那颗 10×16 的右箭头粗细相当。
            width = 20.dp,
            height = 12.5.dp,
            viewportWidth = 16f,
            viewportHeight = 10f,
            pathData = "M14.531,1.65 " +
                "C14.81,1.929 14.81,2.381 14.531,2.66 " +
                "L8.47,8.721 " +
                "C8.191,9.0 7.739,9.0 7.46,8.721 " +
                "L1.399,2.66 " +
                "C1.12,2.381 1.12,1.929 1.399,1.65 " +
                "C1.678,1.371 2.13,1.371 2.409,1.65 " +
                "L7.965,7.205 " +
                "L13.521,1.65 " +
                "C13.8,1.371 14.252,1.371 14.531,1.65 Z",
        )
    }

    /** 加号（新建方案 / 添加取值项）。 */
    val Add: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            name = "Add",
            pathData = "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
        )
    }

    /** 叉号（删除一个取值项）。 */
    val Close: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
        icon(
            name = "Close",
            pathData = "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 " +
                "6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
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
