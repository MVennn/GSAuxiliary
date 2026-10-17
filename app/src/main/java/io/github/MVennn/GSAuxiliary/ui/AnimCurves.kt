package io.github.MVennn.GSAuxiliary.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp

/**
 * 全应用共用的动画曲线 / 弹簧正本。
 *
 * 之前只有弹层（AppDialogHost）有自己的曲线，而且锁在私有常量里；
 * 自绘开关（GsSwitch）进来之后，苹果味的手感参数需要跨组件复用，
 * 所以在这里立一份正本，后续新动画一律从这里取，别再各处散落 tween。
 */

/**
 * 苹果风入场（带回弹）：easeOutBack 族，峰值 ~10% 过冲。
 * 适用缩放区间小的场景（如弹层 AppearScale 0.80 → 1）。
 */
internal val AppleEnterBouncy = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/**
 * 苹果风入场（轻回弹）：同族但过冲压到 ~3%，适用位移区间大的场景
 * （贴底弹窗滑入整卡高度，1.56 的过冲会放大成肉眼可见的弹跳）。
 */
internal val AppleEnterSoft = CubicBezierEasing(0.34f, 1.3f, 0.64f, 1f)

/**
 * 苹果风出场：`UIView.animate` 默认 easeInOut。出场方向**不能**用带回弹的曲线，
 * 过冲会冲过终点（卡片缩得比起始还小 / 滑出后又弹回来）。
 * 也适用于颜色过渡这类「过冲无意义」的属性。
 */
internal val AppleExit = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** 开关滑块位移弹簧：比 Miuix 自带的（0.7 / 987）更「弹」——
 *  阻尼压低到 0.62 让落位带一次可见的过冲回坐，刚度降到 520 让弹跳更从容。 */
internal val SwitchThumbSpring = spring<Dp>(dampingRatio = 0.62f, stiffness = 520f)

/** 开关滑块按压缩放弹簧：软一点的回弹，配合 1.127 的按压放大倍率 */
internal val SwitchThumbScaleSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 400f)
