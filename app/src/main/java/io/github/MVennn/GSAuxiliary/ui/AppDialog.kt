package io.github.MVennn.GSAuxiliary.ui

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
 *
 * 实现拆在同包的三个文件里：
 *  - `AppDialogSpec.kt`：Spec 数据类 + [DialogController]（dismiss 回调时序）
 *  - `AppDialogHost.kt`：[DialogHost] + 动画参数
 *  - `AppDialogWidgets.kt`：弹窗内容子组件
 */
