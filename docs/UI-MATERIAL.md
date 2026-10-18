# 界面材质与弹层

> **一句话**：本模块的「表面」怎么画 —— 弹窗（贴底 / 锚定）、液态玻璃三档、组件清单与状态口径。
>
> 本篇是 2026-10-18 从 [UI.md](UI.md) 拆出来的：UI.md 当时 1729 行，超了
> [DOC-RULES](DOC-RULES.md) §6 的 800 行预算。**节号沿用原稿**（1.3 / 6 / 7 / 8），
> 跳号是因为只把与「材质 / 弹层 / 组件」相关的章节搬到本篇 —— 这样两篇里所有
> 「见 §x.y」的交叉引用都不用改写。页面骨架与三个 Tab 的内容仍在 [UI.md](UI.md)，
> 改界面的踩坑清单在 [UI-HISTORY](UI-HISTORY.md) §13。
> 节内出现的「见 §1.0 / §1.1 / §5.x」这些编号仍指 [UI.md](UI.md) 里的对应章节。

---


### 1.3 弹层：自绘 `DialogHost`（2.8.0 起；2.9.0 锚定定位；2.9.2 分化为两种形态）

> 历史：2.3.1 曾自绘覆盖层 → 2.5.0 查明根因后回归 Miuix 官方弹层
> （`OverlayDropdownPreference` / `OverlayDialog`）→ **2.8.0 又改回自绘**，
> 但这次只自绘「弹窗」这一种，且原因与 2.3.1 完全不同。见 §1.4。

两个选择项（主题设置 / 语言）与「重置」的二次确认，
统一由 `ui/AppDialog.kt` 的 `DialogHost` 渲染。

#### 两种形态（2.9.2 分化）

| | `ChoiceDialogSpec`（主题 / 语言） | `ConfirmDialogSpec`（重置） |
|---|---|---|
| 定位 | **从触发控件右中（箭头处）弹出**：优先顶边贴箭头向下展开，下方放不下翻到底边贴箭头向上展开 | **贴屏幕底部**，宽度 = 主页卡片宽（`cardPadding` = 12dp） |
| 入场 | 以**离箭头最近的右角**为原点缩放展开（`0.80 → 1`）+ **渐显**（2.9.4）：在箭头下方 → 右上角往左下长；翻到上方 → 右下角往左上长 | 从屏幕下方**向上滑入** |
| 出场 | 向该右角**缩回** + **渐隐**（2.9.3 补缩回、2.9.4 补渐隐） | 向**下滑出** |
| 标题 | **没有**（2.9.2 去掉，选项本身已说明一切） | 有（居中；主标题 `title4` 18sp + Medium、副标题 15sp —— 2.9.6 定稿，调参史见 §1.3.6） |
| 内容 | 若干选项行（选中项主色 + 对勾） | 标题 + 说明 + 「取消 / 确定」两个等宽按钮 |

`DialogController` 只维护**一个** `spec`，两种弹窗是它的两个子类型：

```kotlin
sealed interface DialogSpec { val anchor: Rect? }
class ChoiceDialogSpec(val items: List<String>, val selectedIndex: Int, ...) : DialogSpec
class ConfirmDialogSpec(val title: String, val message: String, ...) : DialogSpec

class DialogController {
    var spec by mutableStateOf<DialogSpec?>(null); private set
    var closing by mutableStateOf(false); private set   // 正在播「收起」动画

    fun dismiss() = when (spec) {
        null -> Unit
        is ConfirmDialogSpec -> closing = true   // 贴底弹窗：播完下滑动画再清空
        else -> spec = null                      // 下拉菜单：立即消失
    }
    fun finishDismiss() { spec = null; closing = false }   // 只由宿主调用
}
```

> **`closing` 是「既要退出动画、又不要空白帧」的关键。**
> 早期版本关闭时先把 `spec` 置空再跑退出动画，于是渲染出一张没有内容的空卡片
> （用户反馈的「关闭时先出现空白弹窗」）。现在退出动画期间 **`spec` 仍然保留**，
> 动画跑完才 `finishDismiss()` 真正清空 —— 内容全程都在。
> 选择类弹窗本来就不需要退出动画，直接置空最干净。

```kotlin
// 一级页面（SettingsTab）只负责「发起」
val dialogs = LocalDialogController.current
var themeAnchor by remember { mutableStateOf<Rect?>(null) }   // 2.9.0：记录触发控件位置
ArrowPreference(
    title = stringResource(R.string.item_theme),
    summary = themeLabels.getOrElse(index, { "" }),   // 右侧直接显示当前值
    modifier = Modifier.onGloballyPositioned { themeAnchor = it.boundsInWindow() },
    onClick = {
        dialogs?.showChoice(
            ChoiceDialogSpec(
                items = themeLabels.toList(),         // 2.9.2：不再传 title
                selectedIndex = index,
                anchor = themeAnchor,                 // 2.9.0：相对该控件定位
                onSelect = { idx -> /* 写入走 editDeferred */ },
            ),
        )
    },
)
```

- `DialogController` 在 `HomeScaffold` 里 `remember` 一份，经 `LocalDialogController`
  （`compositionLocalOf`，默认 `null`）下发；
- **2.9.0 起 `DialogHost` 挂在 `MainScreen` 最外层 `Box` 的最后一个子节点**，
  而不是 `HomeScaffold` 的内容层里 —— 因为锚点坐标来自 `boundsInWindow()`
  （**窗口坐标**），宿主必须和窗口原点对齐，否则算出来的 x/y 会整体偏掉（见 §1.3.4）；
- 支持**点遮罩关闭**与**返回键关闭**（`BackHandler(enabled = visible)`，
  后注册者优先，所以弹窗开着时返回键先关弹窗）；
- `spec == null` 时 `DialogHost` 直接 `return`，不留一层看不见的遮罩挡点击。

#### 1.3.1 为什么必须自己画（Miuix 弹层的两条崩溃路径）

用户反馈「主题设置 / 语言这两个控件**还是会闪退**」（当时还有第三个「日志输出等级」，
已随 3.1.0 移除）。定位到两条**独立**根因：

1. **弹层在自己的 `onClick` 里同步开始收起动画** —— 此时任何一次配置写入
   （`commit()` → `OnSharedPreferenceChangeListener` **同步**回调 → 改写 Compose 状态）
   都会把弹层的入参换掉，动画与重组打架。2.7.0 用「延迟写入」绕（`editDeferred`，360ms），
   能压住大部分情况但治不干净。
2. `LocaleHelper.wrap()` 返回非 `ContextWrapper` 的 `ContextImpl`（2.5.0 已修）。

换成自绘之后**根因 1 直接不存在**：弹窗只是普通 Composition 的一部分，
没有「库自己的收起动画」，关闭就是一次状态翻转。
`editDeferred` 仍然保留，作为第二层防御（挡与关闭动画重叠的重组）。

#### 1.3.2 弹窗也吃「液态玻璃」开关

`DialogHost` 收 `liquidGlass` / `effect` 两个参数，与顶栏 / 底栏同一套
（两档光效的正本定义见 [LIQUID-GLASS.md](LIQUID-GLASS.md)，下表是当前现状）：

| 档位 | 卡片材质 |
|---|---|
| 液态玻璃开 · 玻璃光效开（完整版） | `drawBackdrop`（`vibrancy()` + `blur(24.dp)` + `glassSurfaceTint(dark, Full)` 面色 0.30 + `Shadow(24.dp)` 外投影 + `Highlight.Default` 描边），圆角 28dp |
| 液态玻璃开 · 玻璃光效关（简约版） | `vibrancy()` + `blur(24.dp)` + `glassSurfaceTint(dark, Frost)` 面色 0.45；**无描边、无投影** |
| 液态玻璃关 / API ≤ 30（纯色兜底） | `surfaceContainerHigh` 纯色（深色 #242424 / 浅色 #E8E8E8，与页面 `surface` 有明显对比） |

遮罩：**所有弹窗都有**，且随进度「渐变压黑」——

```kotlin
Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = ScrimAlpha * progress)))
```

`progress` 是入场的 `Animatable(0f → 1f)`，所以遮罩是**由透明渐变到 `ScrimAlpha`（0.48）**，
不是「啪」地一下变暗。2.9.1 之前锚定式弹窗**完全不画遮罩**（怕看不出它挂在哪个控件上），
2.9.2 按用户要求统一加上；2.9.3 又把 `ScrimAlpha` 从 0.32 **加深到 0.48**
（用户反馈压黑不够，弹窗与身后内容的对比不足 —— 主题设置与重置模块共用同一遮罩，
一次加深两处同步生效）。

入场 / 出场变换（全部走 `tween` + **苹果风 `CubicBezierEasing`**，**没有任何 `spring`**）：

| 形态 | 入场曲线 | 出场曲线 |
|---|---|---|
| 锚定菜单 | `AppleMenuEnterEasing` = easeOutBack 族 `(0.34, 1.56, 0.64, 1)`：约 10% 的峰值过冲折算到 0.2 的缩放区间只有 ~2% 的「轻弹一下」 | `AppleExitEasing` = easeInOut `(0.42, 0, 0.58, 1)`（`UIView.animate` 默认曲线），平滑离场 |
| 贴底弹窗 | `AppleSheetEnterEasing` = 同族 `(0.34, 1.3, 0.64, 1)`：位移区间是**整卡高度**，同样的过冲会放大成肉眼可见的弹跳，故压到 ~3% | 同上 |

> **出场方向不能回弹**：过冲会冲过 0 —— 卡片缩得比起始还小 / 滑出屏幕后又弹回来一点。

| 形态 | 入场 | 出场（2.9.3 起两种都有） |
|---|---|---|
| 锚定菜单 | 以 `TransformOrigin`（**离箭头最近的右角**：卡片在下方 = `(1f, 0f)` 右上角、翻到上方 = `(1f, 1f)` 右下角，布局期裁决）为原点，`AppearScale = 0.80f` 缩放展开 + **渐显**（alpha 0 → 1，2.9.4） | 向该右角**缩回** + **渐隐**（alpha 1 → 0，2.9.4，progress 1 → 0） |
| 贴底弹窗 | `translationY = (1 - progress) * height`，从屏幕下方滑上来（不渐隐） | 向**下滑出**（progress 1 → 0） |

> 2.9.2 时锚定菜单关闭是「立即置空、直接消失」—— 只有展开没有缩回，
> 用户反馈「点掉之后弹窗是凭空消失的」。2.9.3 让 `dismiss()` 统一只置 `closing`，
> 两种形态都播完出场动画再清空（`closing` 期间 `spec` 保留，不会渲染空壳）。

> **2.9.4：锚定菜单的透明度用「第二条独立动画」**。alpha 由**单独的 `Animatable`
> （`fade`）**驱动，与缩放用的 `progress` 互不读写：入场是两个 `LaunchedEffect`
> 各跑各的（`DialogMillis = 220` / `DialogFadeMillis = 180`），出场是两条 `launch`
> 并发、`join()` 齐了才 `finishDismiss()`。刻意不复用同一个进度 ——
> 绑定两条动画就是「动一个必动另一个」，独立驱动才能各自调时长、互不干扰。
> 贴底确认弹窗不使用渐隐（`fade` 恒为 1），行为与 2.9.3 完全一致。

> **为什么锚定菜单「从箭头（控件右中）弹出」**：入口行的箭头就在控件右缘、竖直居中处
> —— 弹出的起点应该就是它。卡片右边缘与控件右缘对齐，缩放原点取**离箭头更近的右角**：
> 卡片在箭头下方 → 右上角固定不动、往左下长；下方放不下翻到上方 → 右下角固定、往左上长。
> 位置（y）与原点由同一把尺（`resolveAnchorPlacement`）配套裁决：布局期算位置、
> 绘制期取原点，两边拿到的卡片高度一致，必然不会错位。

卡片内容外面再叠一层 `clickable(indication = null) { }` 的容器**吃掉点击**，
否则点卡片内部会穿透到遮罩上把弹窗关掉。

> **2.9.4：这一层同时挂了 `.clip(shape)`** —— Miuix 的按压高亮
> （`MiuixIndicationInstance.draw` 里就是给整个节点 `drawRect` 一层色）会铺满**整行**
> （`ChoiceRow` 是满宽），首 / 末行的矩形高亮越过 28dp 圆角、糊到弹窗之外。
> 把内容层按卡片形状裁一刀，高亮（及全部子节点绘制）即被限制在弹窗范围内。
> **只裁内容层、不裁外层**：外层还画着 `drawBackdrop` 的外投影，裁了连投影一起切掉。

#### 1.3.3 弹层与 Dock 的层级关系

**2.9.0 起 `DialogHost` 挂在 `MainScreen` 最外层 `Box` 的最后一个子节点**（不再放
`HomeScaffold` 内容层），既是窗口坐标系对齐的需要（见 §1.3.4），也顺带保证了层级最高：

```
第 0 层底色层  <  一级页 HomeScaffold（含顶栏 / 内容 / 悬浮底栏）  <  二级页 SubScreen  <  DialogHost
```

> 2.8.0 时它挂在 `HomeScaffold` 内容层最后一个子节点（天然压住悬浮底栏）。
> 2.9.0 因为要按**窗口坐标**摆弹窗，上移到最外层 `Box` —— 那里与窗口原点对齐，
> `boundsInWindow()` 的坐标可直接用。

反过来，**液态悬浮底栏必须放在 `Scaffold` 的 content 里面**（用 `align(BottomCenter)`），
否则它会盖住弹窗。同理，`Modifier.align(...)` 必须挂在 `Box` 的**直接子节点**上 ——
`align` 是 `BoxScope` 的 parent data，`AnimatedContent` 内部自己的 Layout
既不读也不转发它（2.6.0 修过的「底栏消失了」，见 §6.3）。

#### 1.3.4 相对触发控件定位（2.9.0；2.9.2 加入贴底分支）

2.9.0 时按用户要求做过「优先控件**右下**方、下方放不下翻**右上**方」（锚点是控件底边）；
现在改为**「从控件右中（箭头处）弹出」**：锚点换成控件的竖直中心（入口行箭头所在处），
优先卡片**顶边**贴箭头向下展开，下方放不下翻到**底边**贴箭头向上展开 ——
即「从右上往左下」，翻侧时「从右下往左上」。

定位不能用 `Popup` / `DropdownMenu` —— 那些要么自己管坐标系、要么带库内动画。
这里用 **`SubcomposeLayout`**：先量出卡片实际尺寸，再算坐标，**一次成型**，
避免「先出现在屏幕中间再跳到控件旁边」的抖动。

```kotlin
SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
    if (spec == null) layout(0, 0) {}

    // 贴底弹窗（无锚点的 Confirm）走 sheet 分支：宽度对齐主页卡片
    val sheet = spec is ConfirmDialogSpec && anchor == null
    val cap = if (anchor != null) MenuWidth.roundToPx().coerceAtMost(width - side * 2)
              else (width - side * 2).coerceAtLeast(0)      // side = ScreenMargin(12dp)

    val measurable = subcompose("card") { DialogCard(...) }
    val card = measurable.first().measure(
        Constraints(
            minWidth = if (anchor != null || sheet) cap else 0,   // 锚定 / 贴底都定宽
            maxWidth = cap,
        ),
    )
    val cardW = card.width; val cardH = card.height
    val (x, y) = when {
        sheet -> {
            val navBottom = WindowInsets.navigationBars.getBottom(density)
            side to (height - cardH - side - navBottom).coerceAtLeast(side)  // 贴底、抬过导航条
        }
        anchor != null -> {
            // 右对齐触发控件右边缘；从控件右中（箭头处）弹出：
            // 顶边贴箭头向下展开，下方放不下翻到底边贴箭头向上展开
            val x = (anchor.right - cardW).coerceIn(side, (width - cardW - side).coerceAtLeast(side))
            val placement = resolveAnchorPlacement(
                cardH = cardH.toFloat(),
                centerY = (anchor.top + anchor.bottom) / 2f,
                screenHeight = height.toFloat(),
                marginPx = side.toFloat(),
            )
            // placement.origin 写回 menuOrigin（state），绘制期取同一份做缩放原点
            x to placement.y.toInt()
        }
        else -> (width - cardW) / 2 to (height - cardH) / 2     // 居中兜底
    }
    layout(width, height) { card.place(x.toInt(), y.toInt()) }
}
```

要点：

- **锚点用 `boundsInWindow()`（窗口坐标）**，所以宿主 `DialogHost` 必须挂在
  与窗口原点对齐的最外层 `Box`（2.9.0 把它从 `HomeScaffold` 内容层**上移**）。
  挂在带 inset padding 的层级里会让 y 整体偏掉一个状态栏高度。
- **右对齐控件右边缘**（`anchor.right - cardW`），不是左对齐 —— 视觉上弹窗从控件
  右缘「长出来」。
- **`coerceIn` 保证不出屏**：左右夹在 `[side, width - cardW - side]`；
  y 由 `resolveAnchorPlacement` 裁决 —— 顶边贴箭头（`centerY`）优先、
  底边贴箭头（`centerY - cardH`）兜底，两头都放不下再收进 `[side, height - cardH - side]`。
- 缩放原点（`TransformOrigin`）取**离箭头更近的右角**（下方 = 右上角 / 上方 = 右下角），
  与 y 由同一个函数配套算出 —— 见 §1.3.2 的理由。
- **贴底弹窗用同一个 `cap`**（`width - 2 × 12dp`），所以它和主页卡片左右边缘对齐，
  细节见 §1.3.6。

> **3.2.1：右侧常驻指示条加了弹性。** 位置不再直接跟 `scrollState.value`（线性跟手），
> 而是追一个欠阻尼弹簧：拖动时条子略微落后，甩到顶 / 底时冲过头再弹回；过冲量不裁掉，
> 而是转成「拉长」（最多 60%），所以拉满也有动画。动画值只在 `drawBehind` 里读，
> 滚动与回弹都只让这一层重绘。

#### 1.3.5 消除空白帧（2.9.0 引入，2.9.2 与退出动画兼容）

用户反馈「弹窗出现前会先渲染一个**空白弹窗**，再加载内容；关闭时同样先闪一个空白弹窗」。
两处独立根因：

1. **入场做了透明度渐显** —— 前几帧只有淡淡轮廓、内容还没「亮」，看着像先弹了个空框。
2. **`dismiss()` 先把数据置空、再跑退出动画** —— 于是渲染出一个**没有标题和选项**的空卡片。

2.9.0 的修法：入场**只缩放不渐显**；`dismiss()` **立即置空**（索性不做退出动画）。
2.9.2 要给出场加动画（贴底弹窗要「向下滑出」），于是把第 2 条换成更通用的做法：

```kotlin
// 缩放线：0 = 收起（初始态）、1 = 完全展开。
// remember(spec) 让每换一个弹窗都重新从 0 开始 —— 入场天然成立，不会先画一帧再跳回。
val progress = remember(spec) { Animatable(0f) }
// 透明度线（2.9.4）：**另一个** Animatable，与缩放互不读写；贴底弹窗恒为 1
val isMenu = spec is ChoiceDialogSpec
val fade = remember(spec) { Animatable(if (isMenu) 0f else 1f) }

LaunchedEffect(spec) { progress.animateTo(1f, tween(DialogMillis, ...)) }          // 缩放
LaunchedEffect(spec) { if (isMenu) fade.animateTo(1f, tween(DialogFadeMillis, ...)) }  // 渐显

LaunchedEffect(controller.closing) {
    if (!controller.closing) return@LaunchedEffect
    val scaleDone = launch { progress.animateTo(0f, tween(DialogMillis, ...)) }
    val fadeDone = if (isMenu) launch { fade.animateTo(0f, tween(DialogFadeMillis, ...)) } else null
    scaleDone.join(); fadeDone?.join()
    controller.finishDismiss()      // 两条动画都跑完才真正清空 spec
}
```

| 根因 | 做法 |
|---|---|
| ① 入场渐显像空框 | 2.9.2 入场**只做缩放 / 位移**；**2.9.4 按用户要求为锚定菜单恢复渐显**（内容第一帧就完整，只是渐亮；渐变由独立动画驱动，见下方说明） |
| ② 退出期间渲染空壳 | 退出动画期间 **`spec` 保留**（靠 `closing` 标记），跑完才 `finishDismiss()` |

> **2.9.4 为什么敢把渐显加回来**：2.9.0 的「空框感」是两根因叠加的结果 ——
> ① 入场淡入 + ② 「关闭时先置空 `spec`」的 bug。②早已由 `closing` 修掉，
> 现在内容**从第一帧就是完整的**，渐显只是「渐亮」、不是「加载」；
> 并且这次是用户明确要求主题设置弹窗要有渐隐渐出。为不打扰缩放节奏，
> 透明度走**单独一条** `DialogFadeMillis = 180` 的动画，与 220ms 的缩放并行。

> **`remember(spec)` 而不是 `remember { }` + `snapTo`**：后者会在「spec 换了但 effect
> 还没跑」的那一帧先按旧值（= 1）画一次完整卡片，再 snap 回 0 —— 又是一次闪。
> 用 `spec` 当 key，新弹窗直接拿到一个全新的 `Animatable(0f)`，第一帧就是收起态。

#### 1.3.6 贴底弹窗（2.9.2；2.9.3 调整比例）

「重置模块」的二次确认按用户要求改成**从屏幕下方往上弹出**的形态：

- **宽度 = 主页卡片宽度**：`cap = width - 2 × ScreenMargin(12dp)`，
  和 `cardPadding()` 是同一个值，所以它和页面里的卡片左右边缘**完全对齐**；
- **位置**：`x = ScreenMargin`、`y = height - cardH - ScreenMargin - 导航栏高度`，
  即左右各留 12dp、底部再抬过导航条（`WindowInsets.navigationBars.getBottom(density)`）；
- **入场 / 出场**：`translationY = (1 - progress) * height`（`progress` 1 → 0 时向下滑走）；
- **内容**：居中标题（`title4` 18sp + Medium）+ 居中说明（15sp，与标题同色）
  + 「取消 / 确定」两个 `weight(1f)` 等宽按钮。
  **按钮尺寸**（2.9.7）：纵向内边距 `12dp`（比 2.9.6 的 11dp 高 2dp）、
  两钮间距 `16dp`（比 12dp 各收窄 2dp）—— `weight(1f)` 的按钮宽度由「行宽 − 间距」算出，
  想让**单颗**按钮变窄只能调间距或整行两侧留白，不能直接给它定宽。

2.9.3 的两处比例调整（用户反馈「过于扁长」「标题正文只有字号差却像两级信息」）：

| 项 | 2.9.2 | 2.9.3 |
|---|---|---|
| 纵向内边距 | 22dp | **32dp** |
| 说明与按钮的间距 | 20dp | **28dp** |
| 正文颜色 | `onSurfaceContainerVariant` | **`onSurfaceContainer`（与标题同色）** |

正文与标题同色的理由：这里正文就是标题的展开说明，不是两级信息；
两个近似的灰色反而让人去找「它们有什么层级差别」。

**字号调参史（2.9.4 ~ 2.9.6 定稿）**：主标题 / 副标题在「比外面的字小一号」这条反馈上
走了 17 → 20 → **18**（主）、14 → 16 → **15**（副）：

| 版本 | 主标题 | 副标题 | 反馈 |
|---|---|---|---|
| 2.9.3 及以前 | 手写 17sp + SemiBold | 手写 14sp | —— |
| 2.9.4 | `headline1` 17sp + Medium | `body2` 14sp | 「还是比外面的字小一号」 |
| 2.9.5 | `title3` 20sp + Medium | `body1` 16sp | 「20 太大了」 |
| **2.9.6** | **`title4` 18sp + Medium** | **15sp**（无此档样式，直接写 sp） | 定稿 |

对照物是**顶栏标题**（`title3` 20sp + Medium）—— 它才是页面上最显眼的那行「外面的字」，
17sp 放整宽弹窗里居中看自然显小；但顶到 20sp 又过大，18sp 是折中。

**确认按钮统一用 Miuix 主题色**：底色直接铺 `MiuixTheme.colorScheme.primary`、
文字用 `onPrimary`（与 Miuix 自己的 `ButtonDefaults.buttonColorsPrimary()` 观感一致）。
2.9.1 之前用的是 `primary.copy(alpha = 0.16f)` 的浅色底，偏灰、不像主操作按钮。

### 1.4 2.8.0 的自绘宿主 vs 2.3.1 的自绘覆盖层

| | 2.3.1 `AppOverlayHost` | 2.8.0 `DialogHost` |
|---|---|---|
| 装什么 | 下拉、对话框、**Toast / 全局浮层** | 只装「单选」与「确认」两种弹窗 |
| 为什么 | 误判 Miuix 弹层不显示 / 闪退 | 绕开「库在自己的 `onClick` 里同步收起动画」 |
| 结局 | 2.5.0 查明根因后**整体删除** | 保留；根因在库内部，无法从调用侧消除 |

区别在于：2.3.1 的根因（`LocaleHelper` 返回 `ContextImpl`）**在调用侧**，
修掉之后库的弹层就正常了；2.8.0 的根因（弹层 `onClick` 内同步收起动画 + 同步回调改状态）
**在库内部**，调用侧只能绕，所以自绘是终态方案。

---

---

## 6. 液态玻璃（顶栏 + 悬浮底栏 + 弹窗）

参考 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，
依赖 `io.github.kyant0:backdrop:1.0.6`。

2.7.0 起「液态玻璃」是**一套材质、多处表面** —— 顶栏（`GlassTopBar`）、悬浮底栏
（`LiquidGlassDock`）与弹窗（`DialogHost`，2.8.0 加入）共用 `LayerBackdrop`、
共用 `glassSurfaceTint()` 的面色规则，所以三处永远一致。
开关是 `ConfigKeys.LIQUID_GLASS`，光效厚薄是 `ConfigKeys.GLASS_EFFECT`。

原理是「把内容录进图层 → 玻璃把图层采样回来做效果」：

```kotlin
// HomeScaffold：内容录进图层（顶栏 / 底栏 / 弹窗都从这里采样）
// 注意 drawRect 的底衬色必须是 `surface`，见 §1.0
val backdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
...
Box(Modifier.fillMaxSize().layerBackdrop(backdrop))   // 内容容器
```

**顶栏玻璃**（`Glass.kt · GlassTopBar`）：结构是

```kotlin
Box(modifier) {
    Box(Modifier.matchParentSize().drawBackdrop(
        shape = { RectangleShape },
        effects = { if (effect) { vibrancy(); blur(TopBarBlur) } },
        highlight = null,                          // ← 不描边
        onDrawSurface = { drawRect(glassSurfaceTint(dark, effect)) },
    ))
    Column {
        SmallTopAppBar(title, subtitle, color = Color.Transparent)
        extra()                                    // ← 顶栏下挂内容的能力（当前没有页面使用）
    }
}
```

要叠而不是给 `SmallTopAppBar` 传色，是因为只有 `drawBackdrop` 能读 `LayerBackdrop` 做模糊。
玻璃模式下内容铺到 y=0，首屏空白改由各 Tab 在滚动内容开头补 `LocalTopInset`，
这样「首屏从顶栏下方开始」和「滚上去的内容从玻璃后面穿过」同时成立。

> **2.8.0：顶栏不做任何「边框」。** 不画底部发丝线，也把 `Highlight` 显式置 `null`
> （不传描边）。整条顶栏贴边、下面紧接滚动内容，任何描边都会变成一条突兀的横线；
> 玻璃与内容的分界交给面色本身的对比度。
> `SmallTopAppBar` 的 `defaultWindowInsetsPadding` 默认开启，所以它已经含了状态栏高度，
> `matchParentSize` 的玻璃会一并盖住状态栏那条，视觉上是一整块。

> **`extra()` 插槽当前没有使用方。** 2.8.0 时功能搜索框挂在里面（与顶栏共用同一块玻璃、
> 列表滚动时不动），2.9.3 移到功能列表顶部，3.2.0 随搜索框一并移除。插槽保留，
> 顶栏下挂内容的能力还在。

**底栏**内部是**四个同级节点**，顺序即图层顺序：

```kotlin
Box(modifier) {                      // modifier 带 align/navigationBarsPadding/padding
    // 整条底栏吃手势：点击 / 拖动 / 长按由一个 awaitEachGesture 统一判定
    // （2.9.0 起不再分「父级拖动 + 子级 clickable」两条链，见 §6.2.1）

    // 1) 玻璃胶囊本体（只有它带 drawBackdrop）
    Box(
        Modifier.fillMaxWidth().height(DockHeight)
            .onSizeChanged { dockWidthPx = it.width }
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(percent = 50) },
                effects = {
                    if (effect) {            // 简约版：不做任何模糊/折射
                        vibrancy()
                        blur(20.dp.toPx())
                        lens(14.dp.toPx(), 26.dp.toPx())
                    }
                },
                highlight = { Highlight.Default },          // 白边两种形态都保留
                shadow = if (effect) ({ Shadow(radius = 18.dp, alpha = 0.28f) }) else null,
                innerShadow = if (effect) ({ InnerShadow.Default }) else null,
                onDrawSurface = { drawRect(surfaceColor) },
            )
    )

    // 2) 滑动指示：选中项下方一枚随切换滑动的胶囊（玻璃之上、图标之下）
    Box(Modifier.align(Alignment.CenterStart).offset { IntOffset(x, 0) }...)

    // 3) 图标行（4 个 Tab）
    Row(...) { Tab.entries.forEach { GlassDockItem(it) } }

    // 4) 长按气泡
}
```

**为什么拆成四个同级节点**：`drawBackdrop` 的 `onDrawSurface` 会盖一层半透明面色，
如果滑动指示画在它下面就会被压暗；如果和图标放在同一个 `Row` 里，
又会被 `drawBackdrop` 的形状裁剪。所以玻璃、指示、图标、气泡各占一层。

### 6.1 完整版 vs 简约版（「玻璃光效」开关）

**两档的完整定义在 [LIQUID-GLASS](LIQUID-GLASS.md) §3–§6，这里只留结论，不再抄一遍**
（3.2.0 改了档位设计，抄过来的那份没跟着改，已经对不上实现）。

一句话：**完整版**＝通透优先（`vibrancy + blur + lens`、rim、投影、内阴影、交互物理全开，
面色 0.30）；**简约版**＝可读性优先（只留 `vibrancy + blur`，面色 0.45，折射 / 色差 /
rim / 投影 / 内阴影全砍，按压只保留缩放）；能力不足（API ≤ 30）自动落到**纯色兜底**，
两档都不可用 —— 那不是档位，是降级。

两处与旧描述不同的地方，读代码时别按老印象：

- **简约版有模糊**（2.11.1 曾是不透明纯色，3.1.0 起改回磨砂 —— 模糊是「玻璃 vs 纸」的分界）；
- **顶栏没有白边**（贴边表面，`highlight = null`），浮层（弹窗 / 底栏胶囊）才有。

具体参数矩阵见 [LIQUID-GLASS](LIQUID-GLASS.md) §6，实现位置见本章开头与 §5.2。

### 6.2 滑动指示（可拖动）

指示的位置状态是「**第几个 Tab**」为单位的 `Float`（拖动过程中可以是小数），
不是像素 —— 这样拖动和吸附用同一套坐标，不需要来回换算。

```kotlin
var indicator by remember { mutableFloatStateOf(current.ordinal.toFloat()) }
val latestCurrent = rememberUpdatedState(current)
val latestOnSelect = rememberUpdatedState(onSelect)
val snapJob = remember { mutableStateOf<Job?>(null) }

fun snapTo(index: Int) {                      // 换目标先取消上一段，避免两段动画互拉
    snapJob.value?.cancel()
    snapJob.value = scope.launch {
        animate(indicator, index.toFloat(), tween(260)) { v, _ -> indicator = v }
    }
}

// ⚠️ 只以 current 为 key，**刻意不把 dragging 写进来**
LaunchedEffect(current) { if (!dragging) snapTo(current.ordinal) }
```

这段状态代码里还有两条容易踩的坑：

1. **`LaunchedEffect` 的 key 不能带 `dragging`**。松手那一刻 `dragging` 由
   `true` 翻 `false`，若把它当 key 会立刻用**尚未更新的 `current`** 再起一段吸附
   动画，滑块被拉回原处。
2. **不能用 `Animatable` 做拖动位移**：`Animatable.snapTo()` 是 **suspend**，
   而拖拽回调不是挂起上下文，在里面调会编译报错
   （`Suspension functions can only be called within coroutine body`）。
   用 `mutableFloatStateOf` + 独立的 `animate(...)`（外面套一层可取消的 `Job`）才成立。

#### 6.2.1 单一手势链：点击 / 拖动 / 长按三者互斥（2.9.0）

**2.9.0 之前的写法是两条手势链**：父级 `detectHorizontalDragGestures` 管拖动、
子级 `combinedClickable` 管点击。用户反馈「**点击切换会与拖动切换冲突，
点击时偶发闪烁**」—— 根因就在这两条链：

- 手指落下去轻抖越过 `touchSlop` 时，父级**抢先**判定为拖动并 `consume()`；
- 子级的 `combinedClickable` 收到已消费的事件 → **点击被取消**；
- 结果是「既没切成板块、滑块又抖了一下」，看起来就是闪。

2.9.0 把三者合并进**一个 `awaitEachGesture`**，用同一份状态机判定，天然互斥：

```kotlin
.pointerInput(itemCount, itemWidthPx) {
    val slop = viewConfiguration.touchSlop
    val longPressMs = viewConfiguration.longPressTimeoutMillis
    fun indexAt(x: Float) = ((x - padPx) / itemWidthPx).toInt().coerceIn(0, itemCount - 1)

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startX = down.position.x; val startTime = down.uptimeMillis
        var lastX = startX; var now = startTime
        var isDrag = false; var longPressed = false
        snapJob.value?.cancel()

        while (true) {
            // 未越 slop 且未长按时，给等待长按加一个超时；到点即触发长按气泡
            val event = if (isDrag || longPressed) awaitPointerEvent()
                        else withTimeoutOrNull((longPressMs - (now - startTime)).coerceAtLeast(1L)) {
                            awaitPointerEvent()
                        }
            if (event == null) { longPressed = true; /* 弹气泡 */ continue }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            now = change.uptimeMillis
            if (!change.pressed) break
            // 越 slop 才升级为拖动；一旦拖动就不再可能变成点击
            if (!isDrag && !longPressed && abs(change.position.x - startX) > slop) {
                isDrag = true; dragging = true
            }
            if (isDrag) {
                val step = change.position.x - lastX; lastX = change.position.x
                change.consume()
                val next = (indicator + step / itemWidthPx).coerceIn(0f, (itemCount - 1).toFloat())
                indicator = next
                val index = next.roundToInt()
                if (index != latestCurrent.value.ordinal) latestOnSelect.value(Tab.entries[index])
            }
        }
        dragging = false
        when {
            isDrag -> { /* 松手：吸附到最近板块并选中 */ }
            !longPressed -> { val index = indexAt(startX)   // 未拖动 → 按**落点**选中
                              latestOnSelect.value(Tab.entries[index]); snapTo(index) }
            // longPressed 分支：什么都不做，气泡自己会消失
        }
        pressed = null
    }
}
```

判定优先级：**拖动 > 长按 > 点击**。

| 手势 | 触发条件 | 结果 |
|---|---|---|
| 点击 | 未越 slop、未到长按超时即抬起 | 按**落点**选中的 Tab 切换板块 |
| 拖动 | 位移越过 `touchSlop` | 跟手移动滑块、跨中线即切换、松手吸附 |
| 长按 | 静置到 `longPressTimeoutMillis` | 弹该板块名称气泡 |

要点：

1. **点击按「落点」判定**（`indexAt(startX)`），不是按当前选中项 ——
   这样点哪个板块就切到哪个，符合直觉。
2. **`withTimeoutOrNull` 只包住「等待长按」这一段**；一旦升级为拖动或已长按，
   就退化成普通 `awaitPointerEvent()`，不再有超时。
3. **`down` 用 `requireUnconsumed = false`**：父级（滚动容器）可能已经消费了
   down，仍要能接管后续事件。
4. **`pointerInput` 的 key 仍是 `(itemCount, itemWidthPx)`**，闭包过期问题照旧 ——
   `current` / `onSelect` 一律走 `rememberUpdatedState`。

#### 6.2.2 拖动视觉：聚焦放大镜（2.9.0 引入；2.9.3 / 2.9.4 迭代）

用户要求「拖动时**放大蓝色高光**，并在高光内呈现**放大镜**效果，松手后还原」。

拖动进度用 `magnify: Float`（`animateFloatAsState`，0 → 1，`tween(180)`）驱动。
2.9.3 把它重做成**真正的放大镜**：滑块移到「玻璃胶囊 + 图标行」内容层**之上**，
只采样 `dockBackdrop`（dock 自己那层），先等比放大、再 `drawBackdrop` 折射绘制：

| 元素 | 表现 | 实现 |
|---|---|---|
| 滑块胶囊 | **等比**放大（被「拿起来」） | `graphicsLayer { scaleX = scaleY = 1f + KnobScale * magnify }` |
| 镜内内容 | 图标 / 标题 / 底部玻璃一起被放大 | 滑块采样 `dockBackdrop`（`rememberLayerBackdrop` 单独录的「胶囊 + 图标行」层） |
| 高光本体 | 折射放大镜 | `drawBackdrop(backdrop = dockBackdrop, shape = RoundedCornerShape(percent = 50), effects = { vibrancy(); lens(20dp·m, 36dp·m) }, shadow = Shadow(16dp, 0.30f), innerShadow, onDrawSurface = { drawRect(primary.copy(0.14f + 0.10f·m)) })` |
| 当前板块图标 / 文字 | **原样保留**（2.9.3 起不再缩放 / 淡出 —— 镜中要能看见它们） | — |

放大倍率的调参史（`KnobScale`，等比；2.9.3 起滑块常态高度 = `DockHeight - KnobInset × 2` = 54dp）：

| 版本 | 值 | 放大后高度 | 备注 |
|---|---|---|---|
| 2.9.2 | X 0.06 / Y 0.26（**非等比**） | 50 × 1.26 ≈ 63dp < 64dp | 非等比会把图标拉变形，而且压不出胶囊 |
| 2.9.3 | **0.34** 等比 | 54 × 1.34 ≈ 72dp | 修掉「穿透 / 变形」，但用户反馈放大「过猛」 |
| 2.9.4 | **0.22** 等比，`lens` 同步收紧 26/48 → **20/36dp** | 54 × 1.22 ≈ 66dp | 只比 64dp 胶囊探出一线，柔和自然 |

- 只有**当前高亮板块**拿到 `magnify`（其余项本来就不参与）；
  `magnify > 0.01f` 时才切到 `drawBackdrop` 分支，否则用便宜的纯色
  `background(primary.copy(alpha = 0.22f))` —— 静止态没必要挂图层。

**点击不做「变白」反馈**（2.7.0 移除）：`MiuixTheme` 会把 `LocalIndication`
设成 `MiuixIndication(colorScheme.onBackground)`，按压时叠一层**背景色反色**浮层 ——
深色模式下就是一层白色，按一下整块变白，与玻璃观感直接冲突。所以 `GlassDockItem`
**不再挂任何 `clickable` / `combinedClickable`**（手势全部收归上面的 `awaitEachGesture`），
从根上就没有 indication；选中态本身已有滑块 + 主色图标两重反馈，不需要水波纹。

**外投影**（`shadow`）是「悬浮感」的关键 —— 缺了它胶囊会像贴在背景上。

**长按气泡**：长按某个 Tab，会在它**正上方**弹出名称气泡（约 1.4s 后自动消失）。
气泡必须画在 `drawBackdrop` 的**外面** —— 那个图层是按形状裁剪子节点的
（`GraphicsLayerScope.setClip(true)`），放进去会被圆角胶囊裁掉。

**降级行为**（由 backdrop 自己判断，不会崩）：

| 效果 | 最低 API | 低版本表现 |
|---|---|---|
| `blur()` | 31 | 无模糊，仍有半透明 + 描边 |
| `vibrancy()` | 31 | 不增艳 |
| `lens()` | 33 | 无折射 |

`lens()` 只支持 `RoundedCornerShape` / `AbsoluteRoundedCornerShape` / `RoundedRectangularShape`，
换别的形状会抛 `UnsupportedOperationException`。

**版本约束**：backdrop **不能升到 2.x**。2.0.1 依赖 Compose 1.12，
而 Compose 1.12 要求 AGP ≥ 9.1，与本项目锁定的 AGP 8.10.1 冲突（同 Miuix 0.9.4+ 的坑）。

### 6.3 「底栏消失了」——`Modifier.align` 写错作用域（2.6.0 修）

症状：打开液态底栏后，底栏**完全看不见**，像被删掉一样。

根因是 `Modifier.align(Alignment.BottomCenter)` 被写在了
`AnimatedContent` 的 content lambda 里：

```kotlin
// ❌ 错：底栏被丢到内容区左上角，被顶栏盖住
AnimatedContent(targetState = liquidDock) { on ->
    if (on) {
        LiquidGlassDock(
            modifier = Modifier
                .align(Alignment.BottomCenter)   // ← 这里
                .navigationBarsPadding()
                .padding(...),
        )
    }
}
```

`align` 是 **`BoxScope` 的 parent data**，只有 `Box` 的**直接子节点**才会被读取。
而 `AnimatedContentScope` 只继承 `AnimatedVisibilityScope`，
**不是 `BoxScope`**（用 `javap` 分别验过 compose-animation 1.10.5 与 1.12.0）：

```
$ javap AnimatedContentScope.class
public interface AnimatedContentScope extends AnimatedVisibilityScope { ... }
```

所以那行 `align` 里的 `Alignment.BottomCenter` 解析到的是**外层 `Box`** 的
`BoxScope`（编译能过），但它挂在 `AnimatedContent` 内部 Layout 的子节点上 ——
那份 parent data 没人读，节点就落在 Layout 的左上角，也就是内容区的左上角、
被顶栏压住，看起来就是「底栏没了」。

**修法**：把对齐与 insets 挪到外层 `Box` 的直接子节点上：

```kotlin
// ✅ 对
Box(Modifier.fillMaxSize()) {
    content()                                   // 含 AnimatedContent
    if (dockAlpha > 0.005f) {
        LiquidGlassDock(
            modifier = Modifier
                .align(Alignment.BottomCenter)  // ← Box 的直接子节点
                .graphicsLayer { alpha = dockAlpha }
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = DockMargin),
        )
    }
}
```

**顺带**：这里也**刻意不用** `AnimatedContent` 做显隐过渡。
`AnimatedContent` 的 `SizeTransform` 默认 `clip = true`，会把画在胶囊上方
`-38.dp` 处的长按气泡切掉。改成 `animateFloatAsState` + `graphicsLayer { alpha }`
更稳，而且没有额外布局层。

> 这类「parent data 挂错节点」的问题编译期**完全不报错**，只在真机上表现为
> 「元素跑偏 / 消失」。凡是 `Modifier.align` / `Modifier.weight`，
> 都要确认它挂的是对应 Scope 容器的**直接子节点**。

### 6.4 下滑自动收起（2.7.0 新增）

悬浮底栏会挡内容，所以向下滑（看后面的内容）时整条收起，向上滑（回看）时放出。
状态在 `Glass.kt · DockScrollState`，滚动事件由 `dockScrollConnection()` 喂进来。

**判定要累计，不能按单次 delta 的符号。** 单次滚动事件的 delta 只有几像素，
直接按符号判定会让底栏在手指轻微抖动时反复闪：

```kotlin
fun onScroll(deltaY: Float, thresholdPx: Float) {
    if (deltaY == 0f) return
    val down = deltaY < 0f                 // 约定：负 = 向下滑（与 Miuix/M3 一致）
    if (down != lastDown) { lastDown = down; run = 0f }   // 方向一变就清零
    run += abs(deltaY)
    if (run < thresholdPx) return
    hidden = down                          // 累计够一段同向滚动才翻转
}
```

手感上就是「滑一下就走，抖一下不动」。阈值是 `DockHideThreshold`。

**连接器只观测、不消费**（返回 `Offset.Zero`），所以滚动本身完全不受影响；
它装在**内容容器**上（不是滚动组件本身），子级滚动组件把未消费的增量向上派发。
切板块 / 关掉玻璃时 `dockScroll.reset()` —— 新板块从顶部开始，底栏本来就该在。

**动画**：`hidden` 经 `animateFloatAsState(tween(DockHideMillis))` 映射成
`hideFraction`（0→1），底栏 `graphicsLayer { translationY = hideFraction * hideDistancePx }`
整条滑出屏幕下方（`hideDistancePx = DockHeight + DockMargin + 56.dp`，56dp 是给手势
导航条的余量）。这个位移与「开关玻璃」的 `dockAlpha` **相乘**，两个效果互不干扰。

> 只有悬浮的那种底栏会「挡内容」，所以自动收起只对它生效；Miuix 贴边底栏不做。

---

## 7. 状态管理

界面不直接读写 `SharedPreferences`，全部经由 `app/ModulePreferences`：

```kotlin
@Composable
internal fun rememberBoolPref(key: String, def: Boolean): MutableState<Boolean> {
    val st = remember(key) { mutableStateOf(ModulePreferences.getBoolean(key, def)) }
    DisposableEffect(key) {
        val listener = { st.value = ModulePreferences.getBoolean(key, def) }
        ModulePreferences.observe(listener)
        onDispose { ModulePreferences.unobserve(listener) }
    }
    return st
}
```

这样做的收益：

- 框架已连接时，写的就是 remote preferences，**目标进程立刻可见**
- 外部变化（例如通过 adb 调试通道改的）也会通过 `ModulePreferences.observe` 回流到 UI
- 界面层完全不需要知道配置存在哪

---

## 8. 组件清单

| 组件 | 来源 | 用途 |
|---|---|---|
| `Scaffold` / `SmallTopAppBar` / `NavigationBar` / `NavigationBarItem` | Miuix | 页面骨架与默认底栏 |
| `Card` / `SmallTitle` | Miuix | 分组容器与分组标题（`Card` 需要 `Modifier.cardPadding()`） |
| `BasicComponent` | Miuix | 标题 + 副标题的信息行 |
| `SwitchPreference` | Miuix | 开关项（功能开关、桌面图标、液态玻璃、玻璃光效、可预测返回） |
| `ArrowPreference` | Miuix | 可点击的动作项（二级入口、主题设置 / 语言、备份、恢复、重置、开源链接、免责声明） |
| `TextField` + `rememberTextFieldState` | Miuix / Compose | 方案名称与取值项的文本输入（`QuickConfigEditScreen`） |
| `Surface` | Miuix | 长按气泡的底板 |
| `IconButton` / `Icon` | Miuix | 返回按钮、行内图标（⋮ 动作菜单、删除取值项） |

> `TabRow` / `TextButton` 曾只服务于「查看日志」页，随该页在 3.1.0 删除后已不再使用。
| `MiuixTheme` + `lightColorScheme()` / `darkColorScheme()` | Miuix | 主题设置（跟随系统 / 亮色 / 暗色） |
| `drawBackdrop` / `rememberLayerBackdrop` / `layerBackdrop` / `Highlight` / `InnerShadow` / `Shadow` | backdrop | 液态玻璃（顶栏 + 底栏 + 弹窗）、光效与外投影 |
| `GlassTopBar` | 自绘（`Glass.kt`） | 液态玻璃顶栏（`drawBackdrop` + 透明 `SmallTopAppBar` + `extra()` 插槽，**无边框**） |
| `DockScrollState` / `dockScrollConnection` | 自绘（`Glass.kt`） | 悬浮底栏的「下滑收起」判定（累计同向滚动，不消费增量） |
| `DialogHost` / `DialogController` / `LocalDialogController` / `ChoiceDialogSpec` / `ConfirmDialogSpec` | 自绘（`AppDialog.kt`） | 自绘弹窗宿主（Miuix 风格 + 液态玻璃），**不用** Miuix `MiuixPopupHost` |
| `SyncSystemBars` | 自绘（`SystemBars.kt`） | 系统栏图标明暗 + 窗口底色跟随生效的深浅色 |
| `CardDefaults.defaultColors(color, contentColor)` | Miuix | 概览页状态卡的自定义配色（主色 14% 透明卡面） |
| `Animatable` + `graphicsLayer` | Compose | 一二级路由的跟手位移（可预测返回） |
| `AnimatedContent` / `AnimatedVisibility` | Compose | 底栏形态交叉淡变、Tab 切换、玻璃光效开关展开 |
| `animateFloatAsState` + `graphicsLayer { alpha / translationY / scaleX }` | Compose | 悬浮底栏的显隐与下滑收起、弹窗出场（**不用 `AnimatedContent`**，理由见 §6.3） |
| `PredictiveBackHandler` / `BackHandler` | activity-compose | 返回手势接管；`BackHandler(enabled = visible)` 让返回键先关弹窗 |
| `rememberLauncherForActivityResult` + `ActivityResultContracts.CreateDocument` | activity-compose | 日志保存到任意位置（SAF，无需存储权限） |
| `AppIcons.*` | 自绘（`AppIcons.kt`） | 底栏、状态卡、弹窗对勾等图标（**不引第三方图标库**） |

---
