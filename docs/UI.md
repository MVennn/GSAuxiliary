# 设置界面

设置界面是模块自己的 App（`io.github.SansKritO4.GSAuxiliary`），用 [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)
0.9.3 的 Compose 组件写成，小米 HyperOS 风格。

代码在 `app/src/main/java/io/github/SansKritO4/GSAuxiliary/ui/`。

---

## 1. 文件结构

| 文件 | 职责 |
|---|---|
| `SettingsActivity.kt` | Activity、主题 / 本地化、**两层布局**（一级底衬 / 二级滑入）、顶栏、返回与可预测返回、弹窗宿主装配 |
| `Glass.kt` | 液态玻璃：`GlassTopBar`（顶栏，无边框 + `extra()` 插槽）、`glassSurfaceTint()`（面色）、`LocalTopInset` / `LocalDarkTheme`、`DockScrollState` + `dockScrollConnection`（下滑收起） |
| `AppDialog.kt` | 自绘弹窗宿主 `DialogHost` + `DialogController` / `ChoiceDialogSpec` / `ConfirmDialogSpec` / `LocalDialogController`（见 §1.3） |
| `PrefState.kt` | 读取配置的 Composable（`rememberBoolPref` / `rememberLocalBoolPref` / `rememberIntPref` / `rememberStringPref` / `rememberConnected` / `rememberDarkTheme` / `rememberPredictiveBack`） |
| `SystemBars.kt` | 系统栏沉浸：`SyncSystemBars`（图标明暗 + 窗口底色跟随**生效的**深浅色）、`LocalNavBarInset`、`Context.findActivity()` |
| `FeatureCatalog.kt` | 功能展示元数据（`FeatureSpec` + `FEATURE_CATALOG`） |
| `Widgets.kt` | 共用小部件（`cardPadding()` 卡片留白、颜色预设项、预览行、卡片注脚、`openUrl`） |
| `AppIcons.kt` | 自绘 `ImageVector` 图标（不引第三方图标库，见 §10） |
| `Dock.kt` | `Tab` 枚举 + `LocalDockInset` + `MiuixDock`（官方 NavigationBar）+ `LiquidGlassDock`（液态玻璃悬浮底栏，含光效、可拖动滑动指示与长按气泡） |
| `ModuleTab.kt` | Tab 1 概览（顶部状态卡 + 模块 / 运行环境 两组，见 §3） |
| `FeatureTab.kt` | Tab 2 功能（搜索框在**列表顶部**随列表滚动 + 按 `query` 过滤 + 功能卡片 + 二级入口） |
| `SettingsTab.kt` | Tab 3 设置（**仅模块级设置项**） |
| `AboutTab.kt` | Tab 4 关于（含免责声明二级页正文 `DisclaimerContent`） |
| `WatchlistDetail.kt` | 二级页：自选股高亮的**染色设置**（作用域 / 高亮颜色 / 效果预览） |
| `StrategyQuoteDetail.kt` | 二级页：策略直达标准行情的**滑动设置**（仅「左右滑动切换股票」一项，见 §4.3） |
| `LogViewer.kt` | 二级页：查看日志（筛选 / 复制 / **保存到任意位置** / 清空） |
| `MarkdownText.kt` | 轻量 Markdown 渲染（`AboutTab` 的免责声明正文用；不引 Markdown 库） |

一级页面的骨架：

```
Scaffold
├── topBar
│     · 液态玻璃 = GlassTopBar（drawBackdrop 玻璃 + 透明 SmallTopAppBar）
│     · 纯色     = SmallTopAppBar
│     （搜索框 2.9.3 起不在顶栏，见 §4）
├── bottomBar（两种形态交叉淡入淡出）
│     · Miuix 默认底栏  = 真的放在 bottomBar 槽里
│     · 液态玻璃悬浮底栏 = bottomBar 留空，改放在 content 内做浮层
└── 内容区（AnimatedContent 按当前 Tab 切换；液态玻璃模式下会被录进 backdrop 图层）
      └── 悬浮底栏（align BottomCenter）
      └── DialogHost（最后一个子节点，压住一切）
```

> **液态底栏为什么放在 content 里而不是 `Scaffold` 外面**：`DialogHost` 与内容都在
> `Scaffold` 的 content 内，挂在外面的浮层会盖住它们。详见 §1.3.3。

### 1.0 三层布局（2.3.1 重写，2.5.0 去掉自绘覆盖层，2.7.0 加底色层，2.8.0 换底色 token）

`MainScreen` 不再是「用 `AnimatedContent` 整体替换页面」，而是叠放：

```
Box(根容器，铺 MiuixTheme.colorScheme.surface)
├── 第 0 层  底色层 —— 只铺一个 surface 色，不挂任何 graphicsLayer 变换（2.7.0 新增）
├── 第 1 层  一级首页 HomeScaffold —— 常驻底衬
│            按进度 p 缩放 1 → 0.94、透明度 1 → 0.65
└── 第 2 层  二级页 SubScreen —— graphicsLayer.translationX = width * (1 - p)
             p = 1 完全覆盖（位移 0）；p = 0 滑到屏幕右侧之外
```

> **第 0 层为什么必须存在**（2.7.0 修的「预测手势灰边」）：可预测返回会把一级页
> 缩到 0.94 并淡到 0.65，缩下去以后四周会露出下面这一层。它要满足两个条件 ——
> ① 在**最底层**；② **不挂任何变换**（缩放 / 平移都不行，否则它自己也缩，灰边照旧）。
> 颜色跟着深浅模式走。
>
> **⚠️ 2.8.0 修正：这一层的颜色必须是 `surface`，不能是 `background`。**
> 2.7.0 时用的是 `MiuixTheme.colorScheme.background`，结果整页的卡片全部消失 ——
> Miuix 里 `background` 与 `Card` 的默认色 `surfaceContainer` **完全同色**：

| token | 深色 | 浅色 |
|---|---|---|
| `background` | #242424 | #FFFFFF |
| **`surface`** | **#000000** | **#F7F7F7** |
| `surfaceContainer`（`Card` 默认色） | #242424 | #FFFFFF |
| `surfaceContainerHigh` | #242424 | #E8E8E8 |

> 所以 `background` 与卡片同色 → 卡片与页面糊成一片、看不出分组。
> 页面底色统一取 **`surface`**：第 0 层底色层、根 `Box`、`Scaffold.containerColor`、
> `SubScreen` 的 `Scaffold`、`rememberLayerBackdrop` 的底衬，五处必须是同一个值。
> （这 53 个默认色值是用 `javap -c` 逆 `ColorsKt.darkColorScheme$default` /
> `lightColorScheme$default` 的 `ldc2_w` 常量 + 掩码位配对解出来的，不是猜的。）
>
> `Scaffold` 的 `containerColor` 也要显式传：Miuix `Scaffold` 的默认容器色恰好就是
> `colorScheme.surface`，写出来是为了把「页面底色 = surface」这条约定钉在调用点上。

> 2.3.1–2.4.0 期间曾有第 3 层 `AppOverlayHost`（自绘覆盖层）。2.5.0 查明
> 「Miuix 弹层不显示 / 闪退」的真实原因是 `LocaleHelper` 返回了非 `ContextWrapper`
> 的 `ContextImpl`（见 §1.3），修掉之后弹层改回 Miuix 官方组件，自绘覆盖层整体删除。
> 2.8.0 又新增了一个**只装弹窗**的自绘宿主 `DialogHost`（见 §1.4），原因不同，见该节。

`p` 是一个 `Animatable<Float>`：

- 常规前进 / 返回：`LaunchedEffect(route)` 把它动画到 0 / 1
- 可预测返回手势：`PredictiveBackHandler` 里 `snapTo(1f - it.progress)` 跟手，
  手势取消再动画回 1

这样做的好处：
- **可预测返回时一级菜单就在二级页下方**（第 1 层常驻），不再是黑底
- 两层各自铺背景，切换过程**不会闪白**（原先 `SizeTransform(clip = false)` 会露窗口底色）

### 1.0.1 板块切换动画

四个主板块（概览 / 功能 / 设置 / 关于）的切换动画在 `HomeScaffold` 的内容区，
用 `AnimatedContent` 按 `Tab.ordinal` 顺序做「淡入 + 轻微横移」：

```kotlin
val forward = targetState.ordinal > initialState.ordinal
enter = slideInHorizontally(tween(260)) { if (forward) it / 8 else -it / 8 } + fadeIn(tween(220))
exit  = slideOutHorizontally(tween(260)) { if (forward) -it / 8 else it / 8 } + fadeOut(tween(160))
```

位移只有屏宽的 1/8，是「跟手滑一点」而不是整页推走 —— 整页推会让人误以为换了页面。

### 1.1 Insets 归属（踩过的坑）

系统栏 inset **只由 `Scaffold` 负责**（Miuix `Scaffold` 的默认 `windowInsets`
= `systemBars ∪ displayCutout`）。各 Tab / 二级页的内容**不得**再自己加
`navigationBarsPadding()` —— 否则底栏高度会被算两遍，表现为「底部一整片死区，
内容挤不上去」（2.3.0 修掉的正是这个）。

**悬浮底栏的让位不能靠整体上推**（2.3.1 修掉的问题）：内容一旦被推上去，
底栏背后就是空白，玻璃没有可采样的对象 → 看起来「没悬浮」；若根容器又没铺背景，
还会露出窗口底色（深色下即一片黑）。正确做法：

```kotlin
// 内容一直延伸到屏幕底部（液态底栏时连系统栏 inset 也吃掉，见 §11）
Box(Modifier.fillMaxSize().padding(top = top, bottom = if (liquidDock) 0.dp else bottom)) {
    CompositionLocalProvider(LocalDockInset provides if (liquidDock) DockHeight + DockMargin else 0.dp) {
        AnimatedContent(...) { tab -> ... }
    }
}

// 各 Tab 在「滚动内容末尾」补一段等高空白，列表就能滚到底栏之下
Spacer(Modifier.height(24.dp + LocalDockInset.current))
```

### 1.2 卡片左右留白

Miuix `Card` 本身是满宽的，必须由调用方补左右边距。统一用
`Modifier.cardPadding()`（= 12dp）。这个值和 Miuix 的默认内边距正好对齐：

```
SmallTitle 默认内边距 28dp  ==  卡片外边距 12dp + 卡片内边距 16dp
```

### 1.3 弹层：自绘 `DialogHost`（2.8.0 起；2.9.0 锚定定位；2.9.2 分化为两种形态）

> 历史：2.3.1 曾自绘覆盖层 → 2.5.0 查明根因后回归 Miuix 官方弹层
> （`OverlayDropdownPreference` / `OverlayDialog`）→ **2.8.0 又改回自绘**，
> 但这次只自绘「弹窗」这一种，且原因与 2.3.1 完全不同。见 §1.4。

三个选择项（主题设置 / 语言 / 日志输出等级）与「重置」的二次确认，
统一由 `ui/AppDialog.kt` 的 `DialogHost` 渲染。

#### 两种形态（2.9.2 分化）

| | `ChoiceDialogSpec`（主题 / 语言 / 日志等级） | `ConfirmDialogSpec`（重置） |
|---|---|---|
| 定位 | **锚定触发控件**右下方，放不下翻右上方 | **贴屏幕底部**，宽度 = 主页卡片宽（`cardPadding` = 12dp） |
| 入场 | 以**右上角**为原点缩放展开（`0.80 → 1`）+ **渐显**（2.9.4） | 从屏幕下方**向上滑入** |
| 出场 | 向右上角**缩回** + **渐隐**（2.9.3 补缩回、2.9.4 补渐隐） | 向**下滑出** |
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

用户反馈「主题设置 / 语言 / 日志等级这三个控件**还是会闪退**」。定位到两条**独立**根因：

1. **弹层在自己的 `onClick` 里同步开始收起动画** —— 此时任何一次配置写入
   （`commit()` → `OnSharedPreferenceChangeListener` **同步**回调 → 改写 Compose 状态）
   都会把弹层的入参换掉，动画与重组打架。2.7.0 用「延迟写入」绕（`editDeferred`，360ms），
   能压住大部分情况但治不干净。
2. `LocaleHelper.wrap()` 返回非 `ContextWrapper` 的 `ContextImpl`（2.5.0 已修）。

换成自绘之后**根因 1 直接不存在**：弹窗只是普通 Composition 的一部分，
没有「库自己的收起动画」，关闭就是一次状态翻转。
`editDeferred` 仍然保留，作为第二层防御（挡与关闭动画重叠的重组）。

#### 1.3.2 弹窗也吃「液态玻璃」开关

`DialogHost` 收 `liquidGlass` / `effect` 两个参数，与顶栏 / 底栏同一套：

| 开关 | 卡片材质 |
|---|---|
| 液态玻璃**开** | `drawBackdrop`（`vibrancy()` + `blur(24.dp)` + `glassSurfaceTint` 面色 + `Shadow(24.dp, 0.30f)` 外投影），圆角 28dp |
| 液态玻璃**关** | `surfaceContainerHigh` 纯色（深色 #242424 / 浅色 #E8E8E8，与页面 `surface` 有明显对比） |

遮罩：**所有弹窗都有**，且随进度「渐变压黑」——

```kotlin
Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = ScrimAlpha * progress)))
```

`progress` 是入场的 `Animatable(0f → 1f)`，所以遮罩是**由透明渐变到 `ScrimAlpha`（0.48）**，
不是「啪」地一下变暗。2.9.1 之前锚定式弹窗**完全不画遮罩**（怕看不出它挂在哪个控件上），
2.9.2 按用户要求统一加上；2.9.3 又把 `ScrimAlpha` 从 0.32 **加深到 0.48**
（用户反馈压黑不够，弹窗与身后内容的对比不足 —— 主题设置与重置模块共用同一遮罩，
一次加深两处同步生效）。

入场 / 出场变换（全部走 `tween`，**没有任何 `spring` / 回弹过冲**）：

| 形态 | 入场 | 出场（2.9.3 起两种都有） |
|---|---|---|
| 锚定菜单 | 以 `TransformOrigin(1f, 0f)`（**右上角**）为原点，`AppearScale = 0.80f` 缩放展开 + **渐显**（alpha 0 → 1，2.9.4） | 向右上角**缩回** + **渐隐**（alpha 1 → 0，2.9.4，progress 1 → 0） |
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

> **为什么锚定菜单用「右上角原点」**：卡片右边缘本来就与触发控件右边缘对齐，
> 右上角是离控件最近的一点。以它为原点缩放，右上角固定不动、卡片向**下 + 向左**
> 长出来 —— 观感上就是「从右上往右下方向展开」，不会像居中缩放那样先出现在屏幕中间。

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

用户要求「弹窗相对触发控件定位，优先显示在控件**右下**方，下方空间不足时改为**右上**方」。

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
            // 右对齐触发控件右边缘；下方放不下就翻到上方
            val x = (anchor.right - cardW).coerceIn(side, (width - cardW - side).coerceAtLeast(side))
            val below = anchor.bottom + gapPx
            val y = if (below + cardH + side <= height) below
                    else (anchor.top - gapPx - cardH).coerceAtLeast(side)
            x to y
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
  右下方「长出来」，与 LSPosed 的观感一致。
- **`coerceIn` 保证不出屏**：左右夹在 `[side, width - cardW - side]`；
  上方翻转后仍可能顶出屏幕，再 `coerceAtLeast(side)` 兜住。
- 缩放原点（`TransformOrigin`）取 `(1f, 0f)`（右上角）—— 见 §1.3.2 的理由。
- **贴底弹窗用同一个 `cap`**（`width - 2 × 12dp`），所以它和主页卡片左右边缘对齐，
  细节见 §1.3.6。

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

## 2. 职责边界：设置页 vs 功能页

这是 2.2.0 的核心调整。

| 页面 | 放什么 | 不放什么 |
|---|---|---|
| **设置** | **模块范围内**的设置：桌面图标、材质设置（液态玻璃 / 玻璃光效）、通用（主题设置 / 语言 / 可预测返回）、数据管理（备份 / 恢复 / 重置）、框架操作（热重载）、日志（等级 / 查看） | 任何具体功能的开关与参数 |
| **功能** | 每个功能一行**开关**；开关打开后在下方展开该功能的「XX设置」入口 | 具体参数（都在二级菜单里） |

具体功能自己的可调项（染色设置 / 滑动设置、高亮颜色、效果预览）**只在功能 Tab 的二级菜单里出现**。

> 有二级入口的才展开 —— 「策略多选全选」没有可调参数，所以只有开关，不展开入口
> （`FeatureSpec.hasDetail` 为 `false`）。

---

## 3. Tab 1 · 概览

（2.6.0 从「模块」改名「概览」，并重排分类；**2.8.0 瘦身到一屏**；2.9.1 副标题改为
「环境概览」。底栏文案走 `R.string.tab_module`，副标题走
`R.string.subtitle_module` =「环境概览」。）

页面自上而下：

```
概览
├── ① 状态卡          ← 仿 LSPosed 模块页顶部那张「已激活 + 大对勾」，内含框架版本 / API
├── ② 模块           版本 / 构建时间
└── ③ 运行环境       安卓版本 / 设备型号 / Miuix 版本
```

### 3.0 为什么只剩三块（2.8.0 精简）

原来还有「框架」分组（连接状态 / 框架版本 / API 版本 / 作用域）、「配置通道」一行、
「模块名称」一行。三块**都和别处重复**：

| 删掉的 | 为什么 |
|---|---|
| 框架分组 · 连接状态 / 框架版本 / API 版本 | 顶部状态卡已经全部给出 |
| 框架分组 · 作用域 | 由 `scope.list` 静态声明（`com.guosen.android`），不是运行期信息 |
| 配置通道 | 排障信息（remote preferences ↔ 本地暂存），属「查看日志」 |
| 模块名称 | 就是 App 名字，顶栏标题已经写着 |

去掉之后整页不再需要滚动，剩下的每一行都是「不看就不知道」的信息。

### 3.1 状态卡：Miuix 里没有这个组件

LSPosed 模块页顶部那张卡（绿色「已激活」+ 一枚大对勾）看着像 Miuix 自带件，
实际不是。把 `miuix-ui` / `miuix-core` / `miuix-preference` **0.9.3 的完整类清单**
过了一遍：`Card` / `BasicComponent` / `SmallTitle` / `Checkbox` / `Badge` /
`ProgressIndicator` / `Snackbar` / `Tooltip` / `Surface` / `TabRow` 都在，
但**没有**「状态卡 / Hero 卡」这一类复合组件 —— LSPosed 那张卡也是它自己用
Miuix 基础件拼的。

所以 `ModuleTab.kt` 里按 Miuix 的视觉规范自绘了一个等效实现：

```kotlin
Card(
    // 内边距改由文字列自己加 —— 装饰图标要与卡边留出距离、贴到卡片边缘之外的范围，
    // 不能被 Card 的内边距挡住
    insideMargin = PaddingValues(0.dp),
    colors = CardDefaults.defaultColors(
        color = accent.copy(alpha = 0.14f),        // 卡面：主色 14% 透明度
        contentColor = MiuixTheme.colorScheme.onBackground,
    ),
) {
    Box(Modifier.fillMaxWidth().heightIn(min = StatusCardMinHeight)) {
        // 2.9.3 关键：装饰图标放进 matchParentSize 容器 —— 它不参与父级尺寸测量，
        // 卡片高度只由「文字内容 + min 高度」决定，调图标尺寸不会撑高卡片。
        // 2.17.2：图标画在文字下层（先声明），窄屏 / 大字号下碰到也只是水印垫底。
        Box(Modifier.matchParentSize()) {
            Icon(
                AppIcons.CheckCircle | ErrorCircle,
                tint = accent.copy(alpha = 0.30f), // 2.17.1：0.16 → 0.30
                modifier = Modifier
                    .align(Alignment.CenterEnd)    // 2.17.2：完整显示，右侧垂直居中
                    .padding(end = StatusCardIconEndPadding)
                    .size(StatusCardIconSize),
            )
        }
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp)) {
            标题(22sp) / 版本(16sp) / API(15sp)                // 2.9.2：字号上调一档
        }
    }
}

private val StatusCardMinHeight = 148.dp    // 2.9.3 起这才是卡片真实高度（见 §3.1.3）
private val StatusCardIconSize = 132.dp     // 2.17.2：260 → 132，不再裁切
private val StatusCardIconEndPadding = 16.dp // 2.17.2：距右缘 16dp，与文字起始边距对称
```

| 状态 | 主色 | 图标 |
|---|---|---|
| 已激活（连上框架，作用域非空、信息可读） | `Color(0xFF0E8A52)` 绿 | `AppIcons.CheckCircle` |
| 未激活（连上框架，但作用域为空 / 信息读不出） | `MiuixTheme.colorScheme.error` | `AppIcons.ErrorCircle` |
| 未连接（拿不到框架服务） | `MiuixTheme.colorScheme.onBackgroundVariant` | `AppIcons.ErrorCircle` |

卡面用主色 14% 透明度而不是实色，深浅色主题下都不用另外配一套。

#### 3.1.1 2.9.1：纵向拉长 + 图标「只露左上四分之一」

用户要求「容器框纵向拉长，长度约为当前高度的一半；右侧图标放大，放到右下角，
只展示其左上四分之一，以营造设计感」。三处改动：

1. **高度**：`heightIn(min = 148.dp)`（原内容高度约 97dp），约 +50%；
   三行文字改在 `Box` 里 `align(Alignment.CenterStart)` 垂直居中，留白更从容。
2. **图标放大**：`52.dp → 150.dp`，`tint` 从实色降到 `accent.copy(alpha = 0.18f)` ——
   尺寸上去了就必须降透明度，否则会喧宾夺主。
3. **只露左上四分之一**：`align(BottomEnd)` 让图标右下角贴卡片右下角，再
   `offset(x = 75.dp, y = 75.dp)` 各推**半个身位**（150 / 2），于是图标**中心**
   正好落在卡片的右下角上 —— 卡片范围之内只剩它的左上四分之一。

> **不需要自己包一层 `clip`。** Miuix `Card` 的 `BasicCard` 内部走
> `squircleSurface`，其中调用了 `ClipKt.clip(modifier, shape)`
> （`javap -c` 逆 `miuix-squircle` 的 `SquircleBackgroundKt` 确认），
> 超出卡片范围的内容会被自动切掉 —— 这也是为什么 `insideMargin` 必须置 0：
> 否则图标会被内边距提前裁一刀，露出来的就不止四分之一了。

> 露出来的其实是「圆的左上弧线」：24×24 视口里，圆心 (12,12)、半径 9.4，
> 左上象限（x<12, y<12）只包含顶部到左端的弧；勾的端点 (7.2,12.4) 恰好在
> y=12 之外，所以看不到勾。落在角落里就是一段干净的装饰弧线。

#### 3.1.2 2.9.2：字号上调 + 图标放大并向左上微调

用户反馈「LSPosed API 激活相关字体适当加大」「右下角图标向左上方微调（当前约
四分之一不可见），同时适当放大」。三处改动：

| 项 | 2.9.1 | 2.9.2 | 说明 |
|---|---|---|---|
| 标题字号 | 20sp | **22sp** | 状态文案是这张卡的主信息 |
| 版本字号 | 14sp | **16sp** | |
| API 字号 | 13sp | **15sp** | 「LSPosed API 已激活」这行 |
| 图标尺寸 | 150dp | **170dp** | 适当放大 |
| 图标透明度 | 0.18f | **0.16f** | 再放大就得再降一点，维持同样的视觉重量 |
| 图标偏移 | `75.dp, 75.dp` | `170/2 - 16, 170/2 - 16` | 各推半个身位后再**回缩 16dp** |

偏移量的推导：`align(BottomEnd)` 之后，图标中心落在卡片右下角 → 露出左上**四分之一**；
每边回缩 `StatusCardIconInset = 16.dp`，等于把图标往左上平移 16dp，
露出的部分从 85dp 见方变成 **101dp 见方**（约 60%），既不显得被生硬切掉，
又保留了「溢出卡片」的设计感。

> 用 `StatusCardIconSize / 2f - StatusCardIconInset` 而不是写死 `69.dp`，
> 是为了让「改尺寸」和「改内缩」互不干扰 —— 调 `StatusCardIconSize` 时
> 偏移会跟着走，不用两处同步改。

#### 3.1.3 2.9.3：高度回退（图标不再撑高卡片）+ 图标再向左上移

用户反馈「仅改字体大小、不要改高度，高度回退」「icon 再向左上移动一些」。
这次挖出一个**一直存在但没被发现的真相**：

> **装饰图标一直都在参与卡片的高度测量。**
> `Modifier.offset` 只挪位置、不改测量尺寸，所以 `Modifier.size(170.dp)` 的图标
> 作为 Box 的直接子节点时，Box 的高度恒等于 170dp —— `heightIn(min = 148.dp)`
> 从来没真正生效过。2.9.1「纵向拉长」的 148dp 其实是 150dp 图标顶出来的，
> 2.9.2 图标放大到 170dp 后卡片又被顶高 20dp。

两处改动：

1. **图标移进 `matchParentSize` 容器**：`matchParentSize` 的节点不参与父级尺寸
   测量，卡片高度从此只由「文字内容 + [StatusCardMinHeight]」决定 —— 固定 148dp，
   **之后只调字号不会再改变卡片高度**（这正是用户要的）。
2. **`StatusCardIconInset` 16dp → 26dp**：露出部分从 101dp 见方变成
   **111dp 见方**（约占图标 65%），图标整体再往左上挪 10dp。

#### 3.1.4 2.17.2：裁切弧废弃，图标完整显示

2.9.1 ~ 2.17.1 一直走「顶到右下角、只露左上一块弧线」的路线，且一版比一版大 ——
2.17.1 到 260dp / 内缩 60dp 后，露出的 190dp 见方竖向盖满整条卡片右缘，
弧线从右上连到右下，认不出是个对勾。2.17.2 推倒重来：

- **完整显示**：`align(CenterEnd)` 右侧垂直居中，132dp 对 148dp 卡高上下各留
  8dp，`StatusCardIconEndPadding = 16.dp` 让图标与右缘留出与文字起始边距对称的
  空隙 —— 对齐 LSPosed 那张「已激活 + 大对勾」卡的原始版式；
- **`StatusCardIconInset` 删除**：不再裁切，偏移推导公式随之消失；
- **图标画在文字下层**（Box 里先声明图标、后声明文字列）：窄屏 / 大字号下
  文字延长线碰到图标，也只是 0.30 透明度的水印垫底，不会盖字。

### 3.2 分类按「信息归属」划分

之前是「系统信息」+「模块状态」两组，把「模块版本 / 安卓版本 / LSPosed 版本 /
设备型号」混在一起，看不出哪一行是「我自己」、哪一行是「环境」。
2.6.0 按信息归属拆开，2.8.0 收敛为两组：

| 分组 | 含义 | 内容 |
|---|---|---|
| 模块 | 这个模块自身 | 版本、构建时间 |
| 运行环境 | 手机与依赖库 | 安卓版本、设备型号、Miuix 版本 |

框架信息（连接状态 / 框架版本 / API 版本）**只喂给顶部状态卡**，
一次 `remember(connected.value, …) { … }` 取全，不重复读服务。

> 这一页**只做展示**，不放任何操作项（热重载在「设置 → 框架操作」）。
> 排查问题的完整流程见 [排错手册](TROUBLESHOOTING.md)。

### 3.3 两个图标为什么用贝塞尔画圆

`AppIcons.CheckCircle` / `ErrorCircle` 的外圈原本写成 `a`（SVG 圆弧）命令，
但 `tools/verify_icons.py` 的 path 解析器只认 `M/L/H/V/C/S/Q/T/Z`，遇到 `A`
会直接报「暂不支持的命令」，整个校验脚本挂掉。

改用**四段三次贝塞尔**近似圆：控制点偏移 = 半径 × 0.5522847
（即标准的 4/3·tan(π/8)）。视觉上与真圆无法区分，而校验脚本能完整解析。
`EXPECTED_SUBPATHS` 里也补上了这两个图标的期望子路径数（2 / 3）。

---

## 4. Tab 2 · 功能

**搜索框是列表的第一个元素**（2.9.3 起），随列表一起滚动；
按功能名 / 说明 / 开关文案匹配，按分类用 `SmallTitle` 隔开。

#### 4.1 搜索框的位置史：顶栏 → 列表顶部（2.9.3）

| 版本 | 位置 | 问题 |
|---|---|---|
| 2.8.0 | 顶栏（`GlassTopBar` 的 `extra` 插槽） | 切板块时 `if (!visible) return`，一帧直接消失 |
| 2.9.1 | 同上，加横向动画 | 和板块左右平移撞车，`tabForward` 参数满地都是 |
| 2.9.2 | 同上，改竖向动画 | 不撞了，但「顶栏高度随板块变」这件事本身还在 |
| 2.9.3 | **列表顶部** | 全部历史问题一次性消失（见下） |

2.9.3 的修法不是「再调一版动画」，而是**换位置**：搜索框作为功能列表的第一个元素
（`Spacer(8dp + LocalTopInset)` 之后），跟着 `verticalScroll` 一起滚动。于是：

- **不需要任何进出动画** —— 它只在「功能」板块存在，板块一切走，
  整个列表（含搜索框）跟着 `AnimatedContent` 的板块切换动画一起走；
- **不再和板块切换方向打架** —— `tabForward`（2.9.1）那类参数彻底没有了；
- **顶栏四个板块完全一致** —— 高度不再随「是否在功能页」变化，切换时顶栏纹丝不动。

状态仍提升在 `HomeScaffold`（`rememberTextFieldState()`）：
`FeatureTab(searchState, …)` 既渲染搜索框又用它过滤，切走再切回关键词不丢。

> `GlassTopBar` 的 `extra` 插槽保留（顶栏下挂内容的能力还在），只是不再被搜索框使用。

#### 4.2 搜索框配色：与卡片同色 + 对比度增强（2.9.4）

`TextField` 的默认配色（`TextFieldDefaults.textFieldColors()`）是
`backgroundColor = secondaryContainer`（深色 #434343 / 浅色 #F0F0F0），
和页面卡片（Card 默认色 `surfaceContainer`：深色 #242424 / 浅色 #FFFFFF）是两种不同的底。
2.9.4 起在调用点显式覆盖：

| 项 | 原值 | 现值 |
|---|---|---|
| 背景 | `secondaryContainer` | **`surfaceContainer`（与卡片同色）** |
| 前置图标 | `onSurfaceVariantSummary`（浅色 60% 黑 / 深色 50% 白） | **`onSurfaceSecondary`**（浅色 80% 黑 / 深色 80% 白） |
| 占位文字 | `onSecondaryContainer`（浅色 #A9A9A9 / 深色 #7C7C7C） | **`onSurfaceSecondary`** |
| 边框 / 光标 | `primary`（未动） | `primary` |

占位文字与图标同色、比已输入正文（`onBackground`）弱一档 —— 明确高于原来的灰，
又不至于和正文混淆。

每个功能在卡片里渲染为：

```
┌─────────────────────────────────────────┐
│ 自选股高亮                    [开关]   │
│ 把已加入自选的股票名称显示为高亮色        │
├─────────────────────────────────────────┤   ← 开关打开后才出现
│ 染色设置                           ›     │
│ 作用域、颜色、预览                        │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│ 增加 全选 按钮                [开关]   │   ← 没有可调参数，不展开入口
│ 在策略详情的多选栏里显示全选按钮        │
└─────────────────────────────────────────┘
```

**统一交互约定**：一级页面只放「开关」；**打开开关后**，其下方展开「XX设置」入口，
点击进入二级菜单，所有具体参数都在二级菜单里。
关闭开关时入口**收起**（`expandVertically` / `shrinkVertically` 动画）——
展开与否本身就说明了一切，页面上不再额外写提示语。

当前十一个功能（顺序即界面顺序，分组见 `ui/FeatureCatalog.kt` 顶部的「分区归属标准」；
文案与命名规则见 [文案与命名规范](NAMING.md)）：

| 分区 | 功能 | 开关配置键 | 二级入口 |
|---|---|---|---|
| 策略页 | 策略直达标准行情 | `stg_direct_quote` | 滑动设置（`StrategyQuoteDetail`） |
| 策略页 | 策略多选全选 | `stg_select_all` | 无 |
| 策略页 | 策略筛选股票 | `stg_stock_filter` | 无（筛选开关在目标 App 自己的面板里） |
| 股票详情页 | 底栏智能盯盘入口 | `stock_bar_entry` | 无 |
| 底栏 | 底栏隐藏理财项 | `dock_clean` | 无 |
| 首页 | 首页隐藏横幅 | `home_banner_hide` | 无 |
| 行情市场 | 行情市场隐藏滚动横幅与快捷入口 | `market_banner_hide` | 无 |
| 行情市场 | 行情市场板块只留三项 | `market_sector_compact` | 无 |
| 行情市场 | 行情市场概览压矮 | `market_overview_compact` | 无 |
| 显示 | 手势导航条同色 | `nav_bar_blend` | 无 |
| 显示 | 自选股高亮 | `enabled` | 染色设置（`WatchlistDetail`） |

> 上表第一列写的是**分区名**，第二列写的是 `titleRes`（完整功能名，只喂搜索）；
> 卡片上用户看到的是 `switchTitleRes`，与第二列不一定逐字相同 —— 对照表见
> [文案与命名规范](NAMING.md) §5。

**所有开关出厂默认都是「关」**（2.11.2 起）。
功能是侵入目标 App 行为的增强，装完模块不代表用户同意被改 ——
`FeatureSpec.defaultEnabled` 一律取 `ConfigKeys.DEFAULT_*`，而那些默认值全是 `false`。

功能清单的数据源是 `FeatureCatalog.FEATURE_CATALOG`（展示元数据），
运行期真正生效的实现类登记在 `feature/FeatureManager.features` —— 两处都要加。

#### 4.3 二级页：「染色设置」与「滑动设置」

| 二级页 | 入口（功能 Tab） | 内容 |
|---|---|---|
| `WatchlistDetail.kt` | 自选股高亮 → 染色设置 | 作用于策略详情页（开关）/ 高亮颜色（4 种预设 + 预览）/ 效果预览 |
| `StrategyQuoteDetail.kt` | 直达标准行情 → 滑动设置 | 左右滑动切换股票（开关，不带副标题 —— 2.17.2 起，见 [NAMING.md](NAMING.md) §3） |

**二级页只放「总开关之外」的参数。**

> **同一个配置键绝不允许在两级页面各渲染一次开关。** 2.11.2 修过这个病灶：
> `stg_direct_quote` 曾在功能 Tab 与「直达设置」二级页各画一遍，
> 用户在二级页关掉它 → 一级页的「XX设置」入口**当场收起**，人被弹回一级，
> 看着像页面崩了。现在二级页里那一项已删除，页标题也从「直达设置」改为
> **「滑动设置」** —— 因为里面只剩滑动这一项了。
>
> 「左右滑动」的 `enabled` 仍跟随 `stg_direct_quote`：正常路径下恒为 `true`，
> 留着是兜住非常规路径（进程重建后路由被恢复、而配置已被重置）。

---

## 5. Tab 3 · 设置（仅模块级设置项）

| 分组 | 项 | 说明 |
|---|---|---|
| 界面 | 桌面图标 | 开关；切 `activity-alias`（`.ui.LauncherAlias`）的 enabled 状态 |
| 材质设置 | 液态玻璃 | 开关；**总开关**，一次决定顶栏 / 底栏 / 弹窗三处材质（玻璃 ↔ 纯色） |
| 材质设置 | 玻璃光效 | 开关；**仅液态玻璃开启时内嵌显示**，控制模糊 / 增艳 / 投影 |
| 通用 | 主题设置 | 右侧显示当前值，点开**自绘单选弹窗**：跟随系统 / 亮色 / 暗色 |
| 通用 | 语言 | 右侧显示当前值，点开**自绘单选弹窗**：跟随系统 / 简体中文 / English |
| 通用 | 可预测返回 | 开关；二级页返回跟手预览（Android 13+），**默认开** |
| 数据管理 | 备份模块配置 | 导出为 JSON（SAF `CreateDocument`），IO 在后台线程；文件名 `gszx-backup-yyyyMMdd-HHmmss.json` |
| 数据管理 | 恢复模块 | 从 JSON 恢复（SAF `OpenDocument`），IO 在后台线程 |
| 数据管理 | 重置模块 | **自绘确认弹窗**二次确认后 `resetAllFeaturesDeferred()`：**关闭全部功能开关 + 恢复功能参数**，不退出页面 |
| 框架操作 | 热重载目标进程 | 调 `XposedService.hotReloadModule()`，Binder 调用放 `Dispatchers.IO`；**点一次必有一条 Toast 反馈**（2.12.1 起）：成功「已热重载」/ 失败 / 不支持 / 进行中 / 目标进程已退出 / 8 秒超时 / 没有正在运行的目标进程 / 需要框架连接 |
| 日志 | 日志输出等级 | 右侧显示当前值，点开**自绘单选弹窗**：关闭 / 错误 / 信息 / 调试 |
| 日志 | 查看日志 | 进入二级页 |

> 分组名 2.8.0 由「Dock 设置」改为**「材质设置」**（`R.string.section_dock`）——
> 这个开关管的是顶栏 / 底栏 / 弹窗三处材质，叫「Dock」已经不准确了。

### 5.1 桌面图标

桌面图标挂在 `activity-alias` 上，而不是 Activity 本体：

- 关掉图标 = 禁用 alias（`COMPONENT_ENABLED_STATE_DISABLED`）
- 打开图标 = 恢复 manifest 默认状态（`COMPONENT_ENABLED_STATE_DEFAULT`）

`SettingsActivity` 本体**始终 enabled**，并且**自己带一条
`MAIN + de.robv.android.xposed.category.MODULE_SETTINGS` 的 intent-filter**。
LSPosed 找模块设置入口时优先按这个 category 查（见 LSPosed `ModuleUtil`），
所以关掉桌面图标**不会**连带关掉 LSPosed 里的设置入口 —— 这是 2.3.0 修的 bug。
`GszxApp.onCreate` 每次启动都会把配置同步到组件状态，重装 / 升级后自愈。

### 5.2 液态玻璃（总开关）

「液态玻璃」（`ConfigKeys.LIQUID_GLASS`）是 2.7.0 起的一个**总开关**，
一次决定**三处表面** —— 它不再只描述底栏形态（旧名「Dock 变换」只覆盖底栏，
语义已过时，所以 2.7.0 单独拆成一组、2.8.0 把组名改成「材质设置」）：

| | 开（玻璃） | 关（纯色） |
|---|---|---|
| 顶栏 | `GlassTopBar`：`drawBackdrop` 采样内容做模糊 + 半透明面色，**无任何边框** | Miuix 默认 `SmallTopAppBar`，纯色 |
| 底栏 | `LiquidGlassDock`：自绘胶囊 + `drawBackdrop`，悬浮 + 模糊 + 增艳 + 外投影，**下滑自动收起** | Miuix `NavigationBar`，贴边、实底、随主题 |
| 弹窗 | `DialogHost`：`drawBackdrop` + 28dp 圆角 + 外投影 | `surfaceContainerHigh` 纯色卡片 |

**面色有颜色倾向**：深色模式是**黑玻璃**（纯黑 + alpha），浅色模式是**白玻璃**
（纯白 + alpha），由 `glassSurfaceTint(dark, effect)` 统一给出 —— 顶栏与底栏共用
同一套规则，两处永远是一套材质。刻意**不用**主题里的 `surfaceContainerHigh`：
它本身偏灰，叠在模糊后的内容上会发脏，出不来通透感。

两种底栏形态的对照：

| 形态 | 实现 | 特点 |
|---|---|---|
| Miuix 默认底栏 | Miuix `NavigationBar` + `NavigationBarItem` | 贴边、实底、随主题 |
| 液态玻璃悬浮底栏 | 自绘胶囊 + `drawBackdrop` | 悬浮、半透明、对内容做模糊 + 折射 |

液态玻璃的实现细节见 §6。

**底栏**两种形态之间用 `AnimatedContent` 做交叉淡变（`fadeIn(220)` / `fadeOut(180)`），
**不是硬切**，`targetState` 就是 `ConfigKeys.LIQUID_GLASS`。

**悬浮底栏**（content 里那一层）刻意**不用** `AnimatedContent`：它的 `SizeTransform`
默认 `clip = true`，会把长按气泡（画在胶囊上方 `-38dp`）切掉；改用
`animateFloatAsState` 驱动透明度。而且 `Modifier.align` 必须挂在外层 `Box` 的
**直接子节点**上（详见 §6.3）。

「材质设置」分组里的「玻璃光效」开关（`ConfigKeys.GLASS_EFFECT`）包在
`AnimatedVisibility` 里，**仅液态玻璃开启时内嵌显示**，
展开 / 收起动画与功能页二级入口**完全一致**
（`expandVertically(220) + fadeIn(180)` / `shrinkVertically(180) + fadeOut(120)`）。

**副标题跟随开关**（2.9.9）：开 → `item_glass_effect_summary_on`（「完整的玻璃光效」）、
关 → `item_glass_effect_summary_off`（「简约版本」）。原先是一条静态说明
（「完整的玻璃光效和简约版本」），读不出当前处在哪一档；现在与「液态玻璃」那条
（仅关掉时给 `liquid_glass_off`）、「可预测返回」、「桌面图标」同一套写法 ——
**凡是开关的副标题用来描述「当前状态」的，一律按状态取不同字符串**。

### 5.3 主题设置（2.9.0 由「深色模式」改名）

> 控件名 2.9.0 起改为「**主题设置**」（`item_theme`，英文 "Theme"）——
> 它管的是「跟随系统 / 亮色 / 暗色」三选一，叫「深色模式」名不副实。

`rememberDarkTheme()`：`跟随系统` 时看 `isSystemInDarkTheme()`，
否则直接返回 `亮色 = false` / `暗色 = true`，结果喂给 `MiuixTheme`。
`ConfigKeys.THEME` 只在模块 App 进程消费，不进 `ConfigSnapshot`。

**切换时的过渡**：Miuix 的 `Colors` 有 53 个字段、setter 是 `internal`
（`setPrimary-8_81llA$top_yukonga_miuix_kmp_miuix_ui`），外部**无法**逐字段插值，
`ThemeController.currentColors` 也是直读实时状态，所以**颜色本身做不了动画**。

改为「**旧配色整屏盖一层 → 淡出**」。2.8.0 的初版仍会闪，2.9.0 定位到**三个**独立根因，
逐一修掉后过渡才干净：

```kotlin
val (darkColors, lightColors) = remember { ... }        // 两套配色缓存
var shownDark by remember { mutableStateOf(dark) }      // ① 实际渲染用的配色
var veilColor by remember { mutableStateOf(Color.Transparent) }
val veilAlpha = remember { Animatable(0f) }

LaunchedEffect(dark) {
    if (shownDark == dark) return@LaunchedEffect
    veilColor = if (shownDark) darkColors.surface else lightColors.surface  // 旧背景色
    veilAlpha.snapTo(1f)          // ② 遮罩立即铺满（不是下一帧才铺）
    withFrameNanos { }            // ③ 等一帧，确保遮罩这一帧已经画出来
    shownDark = dark              //    此刻才切配色
    veilAlpha.animateTo(0f, tween(ThemeFadeMillis))
}

MiuixTheme(colors = if (shownDark) darkColors else lightColors) {
    Box(Modifier.fillMaxSize()) {
        content()
        // 遮罩常驻，只靠 alpha 控制显隐（不是 if (veil != null) 条件挂载）
        Box(Modifier.fillMaxSize().background(veilColor.copy(alpha = veilAlpha.value)))
    }
}
```

| 根因 | 症状 | 修法 |
|---|---|---|
| ① `MiuixTheme` 直接用目标值 `if (dark) …` | 切色当帧生效，遮罩还没铺 → 先闪一下新色 | 引入 `shownDark`（渲染值）与 `dark`（目标值）**分离** |
| ② 遮罩在 `LaunchedEffect` 里才 `snapTo(1f)` | 遮罩晚一帧才出现，中间露出新配色的白 / 黑 | `snapTo` 提到切色之前，**先铺满再切** |
| ③ `if (veil != null) Box(...)` 条件挂载 | 节点插入那一帧还没画出来 → 闪黑 / 闪白 | 遮罩**常驻**，用 `alpha` 控制，`withFrameNanos { }` 再等一帧 |

> 遮罩取的是 **`surface`**（页面底色），不是 `background` —— 两者在 Miuix 里不是同一个色
> （见 §1.0 的色值表），取错会在淡出过程中闪一下。

视觉上就是从旧配色平滑过渡到新配色（`ThemeFadeMillis = 320`）。
两套 `Colors` 用 `remember` 缓存，避免每次重组重建，也为了让遮罩能取到旧配色。

> **⚠️ 不要退回「直接给 `MiuixTheme` 传 `dark`」。** 那正是 2.8.0 闪屏的第一根因 ——
> 当帧换色、遮罩晚一帧，用户看到的就是「深→浅先变白、再闪黑、最后变白」。

### 5.4 语言

`LocaleHelper.wrap(base)` 在 `SettingsActivity.attachBaseContext` 里按选择包装 Context，
之后所有 `stringResource(...)` 都会走对应 `values-xx/strings.xml`：

- `values/strings.xml` —— 简体中文（默认）
- `values-en/strings.xml` —— English

没用 `LocaleManager`：那需要 API 33+，而本项目 minSdk 27；
`createConfigurationContext` 从 API 17 起就可用。

**切换语言不需要 `recreate()`**：`AppTheme` 在 Compose 层把包好的 Context
通过 `CompositionLocalProvider(LocalContext provides ...)` 提供下去，
配置一变 `stringResource` 立刻走新语言。

**包装必须保持 `ContextWrapper` 身份，不能直接返回 `createConfigurationContext()` 的结果。**
那个返回值是 `ContextImpl`，继承自 `Context` 而**不是** `ContextWrapper`，
`baseContext` 链是断的；androidx 沿链找宿主时会在 `checkcast ContextWrapper` 处抛
`ClassCastException`，表现为「切完语言一点设置页就闪退」。详见 §1.3。

### 5.5 可预测返回

开关写入 `ConfigKeys.PREDICTIVE_BACK`（**默认开**），配合 manifest 的
`android:enableOnBackInvokedCallback="true"`。

开启时二级页用 `PredictiveBackHandler` 接管返回手势，把 `progress` 映射成
`Animatable` 的进度（`snapTo(1f - progress)`），第 1 层的一级菜单因此会在二级页
下方实时露出来并同步放大；关闭时退回普通 `BackHandler` + 常规动画。

**两种模式都保证「二级页返回 → 回到一级菜单」**，而不是直接退出设置页。

### 5.5.1 重置模块

`ModulePreferences.resetAllFeaturesDeferred()`：

```kotlin
for (k in ConfigKeys.FEATURE_KEYS) putBoolean(k, false)   // 一律写 false，不是写默认值
for (k in ConfigKeys.FEATURE_PARAM_KEYS) { /* 恢复 DEFAULTS */ }
```

**为什么仍然显式写 `false` 而不写回默认值**：2.11.2 起功能开关的出厂默认
已经是 `false`（`DEFAULT_ENABLED = false`），两者结果相同；但显式写 `false`
表达的是「重置 = 关掉」这个**语义**，不跟着默认值联动 —— 哪天某个功能又想
默认开启，重置逻辑不必跟着改。一旦改成写回默认值，而默认值又是「开」，
重置就等于没关掉，用户会以为重置失效（这是 2.3.1 修过的 bug）。
只动「功能」范畴，界面设置（图标 / 液态玻璃 / 光效 / 语言 / 主题设置 / 可预测返回 / 日志等级）不受影响，
且**不退出设置页**。

二次确认走**自绘确认弹窗**（`ConfirmDialogSpec` + `DialogHost`，见 §1.3）；
写入走 `editDeferred`（延迟 360ms + `AtomicReference` 只保留最后一次），
避开弹窗关闭动画与重组的重叠窗口。

### 5.6 备份 / 恢复

`app/ConfigBackup.kt` 导出为自描述 JSON：

```json
{
  "format": "gszx-config",
  "formatVersion": 1,
  "moduleVersion": "2.16.0",
  "versionCode": 37,
  "exportedAt": "2026-10-15 12:00:00",
  "config": { "enabled": true, "color": -32768, "...": "..." },
  "defaults": { "...": "..." }
}
```

> `moduleVersion` / `versionCode` 取 `BuildConfig`，**随每次构建刷新**；
> 上面是 2.16.0 的样例值，不是固定文本。
> `defaults` 段直接来自 `ConfigKeys.DEFAULTS`，与「重置模块」同源。

恢复时**只认 `ConfigKeys.ALL_KEYS` 白名单里的键**，文件里多出来的字段一律忽略，
避免被塞进无关键。格式版本高于当前支持时直接拒绝。

读写走 SAF（`ActivityResultContracts.CreateDocument` / `OpenDocument`），不需要任何存储权限。
**导出 / 恢复的整段 IO 都跑在 `Dispatchers.IO`**（跨进程读 remote preferences + 写文件），
期间两个入口置灰并显示「正在导出… / 正在恢复…」—— 这是 2.3.0 修掉的卡顿。

### 5.7 查看日志

`core/GLog.kt` 维护一个 500 行的内存环形缓冲。二级页可查看 / 复制 / **保存** / 清空。

页面结构（2.3.0 按 Miuix 风格重做，2.6.0 加保存）：

```
Column
├── TabRow          级别筛选（全部 / 错误 / 警告 / 信息 / 调试）
├── Text            「共 N 行」
├── Card(weight 1f) 等宽日志列表（级别标签用色块，正文 11sp Monospace）
├── Card            操作条：刷新 / 复制 / 保存 / 清空
└── Card            页脚说明（目标进程日志在 /data/adb/lspd/log/）
```

**操作条四等分占满整行**（2.6.0 改）：四个 `TextButton` 各挂
`Modifier.weight(1f)`，`Row` 用 `fillMaxWidth()` + `Arrangement.spacedBy(4.dp)`。
之前是固定宽度的按钮靠左排，右边空一大截。`TextButton` 接 `modifier`，
所以 `weight` 生效。

**保存走 SAF**（`ActivityResultContracts.CreateDocument("text/plain")`）：

- 用户自己在系统文件选择器里挑位置，**不需要任何存储权限**（也不用 `WRITE_EXTERNAL_STORAGE`）
- 默认文件名 `gszx-log-yyyyMMdd-HHmmss.txt`，前缀沿用项目代号 `gszx-`，
  与备份文件 `gszx-backup-…json` 一致
- 文件写入放 `Dispatchers.IO`，Toast 回主线程再弹（后台线程没有 `Looper`，
  直接 `Toast.show()` 会崩 —— 这也是 2.5.0 修过的坑）
- `uri == null` 表示用户取消，直接返回，不提示

注意：这里只显示**模块 App 进程**的日志。目标进程的日志由 LSPosed 写到
`/data/adb/lspd/log/`，普通应用读不到，页脚有注明。

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
        extra()                                    // ← 顶栏下挂内容的能力；2.9.3 起搜索框已移到功能列表顶部，不再挂这里
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

> **2.8.0（历史做法）：功能搜索框当时挂在 `extra()` 插槽里**，因此它和顶栏共用同一块玻璃 ——
> 列表滚动时搜索框不动。纯色模式下同一段内容挂在 `SmallTopAppBar` 下方
> （外面包一层带底色的 `Column`）。**2.9.3 已把搜索框移到功能列表顶部**（见 §4.1）——
> 它现在是列表的第一个元素、随列表滚动；`extra()` 插槽本身保留（顶栏下挂内容的能力还在）。

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

以**悬浮底栏**为例（它是效果最全的一处）：

| | 完整版（开关**开**） | 简约版（开关**关**） |
|---|---|---|
| 模糊 + 增艳（`vibrancy` / `blur` / `lens`） | 有 | **无** |
| 外投影（`Shadow`） | 有 | **无** |
| 内阴影（`InnerShadow`） | 有 | **无** |
| 顶部高光 / 白边（`Highlight`） | **有** | **有** |
| 胶囊底色 alpha | **0.30** | **不透明纯黑 / 纯白（无 alpha）** |

**简约版去掉的是「模糊」和「投影」，不是白边。** 白边（`Highlight.Default`）
两种形态都给，因为它是胶囊的轮廓线，去掉之后整条底栏会「糊」进背景里、
看不出边界。完整版的底色 alpha 由 `Glass.kt` 的常量 `GlassAlphaFull` 给出；
简约版已经没有 alpha 常量了 —— 光效关掉后它是一块**不透明纯色**，理由见下。

> **2.9.0 提高通透度**：`GlassAlphaFull` `0.52 → 0.30`。面色越淡、底下内容透出来越多，
> 就越「通透」；完整版有 `blur()` 兜底，0.30 足够压住底层文字、保证可读，所以可以放得比较低。
>
> **2.11.1：简约版改为不透明纯色**。此前简约版面色是 0.70 半透明（由 `GlassAlphaMinimal`
> 常量给出），但光效关掉时**没有模糊兜底** —— 底下滚过去的文字 / 图标直接透上来、
> 跟当前内容糊在一起，字都看不清。既然不做模糊，就不该再叫「玻璃」，
> 于是 `effect = false` 时 `glassSurfaceTint()` 直接返回**不透明**的纯黑 / 纯白，
> 常量 `GlassAlphaMinimal` 随之删除。
> 顶栏与底栏共用 `glassSurfaceTint()`，所以「玻璃光效」这一个开关同时决定
> 两处的模糊与底色厚薄。

> **例外：顶栏没有白边。** 2.8.0 起 `GlassTopBar` 显式传 `highlight = null` ——
> 顶栏是贴边的一整块，白边 / 发丝线会变成一条突兀的横线（见 §6）。
> 弹窗（`DialogHost`）也只在完整版做模糊，不开 `Highlight`，改用 `Shadow` 外投影
> 把卡片从遮罩里「抬」起来。

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
| `ArrowPreference` | Miuix | 可点击的动作项（二级入口、主题设置 / 语言 / 日志等级、备份、恢复、重置、热重载、开源链接、免责声明） |
| `TextField` + `rememberTextFieldState` | Miuix / Compose | 功能搜索框（2.9.3 起是**功能列表的第一个元素**，随列表滚动；`GlassTopBar` 的 `extra()` 插槽保留但已不被搜索框使用，见 §4.1） |
| `TabRow` | Miuix | 日志页的级别筛选（参数名是 `tabs` / `selectedTabIndex` / `onTabSelected`） |
| `Surface` | Miuix | 长按气泡的底板 |
| `TextButton` / `IconButton` / `Icon` | Miuix | 日志页按钮、返回按钮、图标 |
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

## 9. 改界面时的注意事项

- **Miuix 0.9.3 不可升级**（0.9.4+ 要求 AGP ≥ 9.1，与 Kotlin 插件冲突），
  新增组件前先确认它在 0.9.3 的 API 里可用
- **backdrop 停在 1.x**（2.x 需要 Compose 1.12 → AGP 9.1+）
- Miuix 扩展图标（`MiuixIcons.Extended.*`）**不在 0.9.3 的产物里**，
  图标统一走 `ui/AppIcons.kt`（自绘 `ImageVector`）。
  **不要引回 `material-icons-extended`** —— 那个包会把 11400 个用不到的图标类带进 dex
  （详见 §10），新增图标照抄 `AppIcons.kt` 的写法加一条即可
- `Icon` 的 `contentDescription` **没有默认值**，必须显式传（可传 `null`）
- `NavigationBar` 接收的是 `content: @Composable RowScope.() -> Unit`，
  `NavigationBarItem` 是 `RowScope` 的扩展函数 —— 不能当普通列表项用
- `ArrowPreference` 在 `top.yukonga.miuix.kmp.preference`，**不在** `.basic`
- `BasicComponent` 的第一个参数是 `modifier`，标题必须写成 `title = "..."`
- 所有面向用户的文案走资源（`values/` + `values-en/`），不要在 Kotlin 里硬编码中文
- 读写配置一律走 `ModulePreferences`，不要直接操作 `SharedPreferences`
- **弹层走自绘 `DialogHost`**（`ui/AppDialog.kt`），**不要**再用 Miuix 的
  `OverlayDropdownPreference` / `OverlayDialog`。它们在库内部的 `onClick` 里
  同步开始收起动画，与同步回写的配置状态打架 → 闪退（2.8.0 换成自绘，见 §1.3）。
  **2.9.0 起 `DialogHost` 挂在 `MainScreen` 最外层 `Box` 的最后一个子节点** ——
  锚点用 `boundsInWindow()`（窗口坐标），宿主必须与窗口原点对齐，否则弹窗会整体偏掉
- **弹窗定位用 `SubcomposeLayout` 先量后摆**，不要用 `Popup` / `DropdownMenu`：
  前者能一次成型（先量出卡片尺寸再算坐标），避免「先出现在中间再跳过去」的抖动。
  **所有弹窗都有渐变压黑遮罩**（2.9.2 起，`ScrimAlpha * progress`）；
  锚定菜单的缩放原点取贴控件那一角 `TransformOrigin(1f, 0f)`（见 §1.3.2 / §1.3.4）
- **弹窗退出动画期间必须保留 `spec`**（2.9.2 起）：`DialogController.closing = true`
  → 播收起动画 → `finishDismiss()` 才置空 `spec`。**不能在 `dismiss()` 里直接置空** ——
  内容全部来自 `spec`，先置空会渲染出空壳。选择类弹窗（下拉菜单）不需要退出动画，
  仍走「立即置空」；只有贴底确认弹窗需要下滑退出（见 §1.3.5 / §1.3.6）
- **入场动画用 `remember(spec) { Animatable(0f) }` 而不是 `remember { }` + `snapTo`**：
  后者会在 spec 换了但 effect 还没跑的那一帧先按旧值（=1）画一次完整卡片再 snap 回 0，
  又是一次闪；用 `spec` 当 key 让它直接拿到全新的 `Animatable(0f)`（见 §1.3.5）
- **搜索框动画不要跟板块切换方向耦合**（2.9.2 修）：早期做成「横向滑入 + 方向跟着
  左右切换走」，结果从功能页切到别的页时，动画从左上 / 右上移动过来，与板块本身的
  左右平移撞在一起 → 割裂感。改成竖向 `expandFrom = Top` / `shrinkTowards = Top`
  + `-it/4` 位移，与板块切换**完全解耦**（见 §4.1）
- **主题切换不要直接给 `MiuixTheme` 传目标 `dark`**：必须走 `shownDark`（渲染值）
  与目标值分离 + 遮罩常驻 + `withFrameNanos { }` 再切，否则会「先变白再闪黑」
  （2.9.0 修，三根因见 §5.3）
- **Dock 的手势不要拆成两条链**（父级拖动 + 子级 `clickable`）：手指轻抖越过
  `touchSlop` 时父级抢先拖动并 `consume()`，子级点击被取消 → 「既没切换又抖一下」。
  点击 / 拖动 / 长按合并进**一个 `awaitEachGesture`**，用同一份状态机判定（见 §6.2.1）
- **`LocaleHelper.wrap()` 必须返回 `ContextWrapper`**，不能直接返回
  `createConfigurationContext()` 的结果（那是 `ContextImpl`，会打断 androidx 的
  宿主查找链，表现为「切完语言点设置页闪退」）
- **语言切换不要 `recreate()`** —— `AppTheme` 在 Compose 层 `provides LocalContext` 即可
- **`Card` 要自己加 `Modifier.cardPadding()`**（12dp），Miuix 不会替你加左右边距
- **系统栏 inset 只归 `Scaffold` 管**，内容里不要再加 `navigationBarsPadding()`（会算两遍）
- **系统栏一律全透明 + 关掉 `enforceNavigationBarContrast`**，否则手势条那一条会被
  系统遮罩填成纯色带；图标明暗只能靠 `WindowInsetsControllerCompat` 设（主题属性在
  API 35+ 已失效）。详见 §11
- **页面底色必须是 `colorScheme.surface`，不是 `background`**：Miuix 里
  `background` 与 `Card` 的默认色 `surfaceContainer` **完全同色**
  （深色都是 #242424 / 浅色都是 #FFFFFF），用 `background` 会让所有卡片消失、
  整页看不出分组（2.7.0 的回归，2.8.0 修）。需要同色的五处：
  第 0 层底色层、根 `Box`、`Scaffold.containerColor`、`SubScreen` 的 `Scaffold`、
  `rememberLayerBackdrop` 的底衬。色值表见 §1.0
- **可预测返回要有一层不参与变换的底色层**：一级页缩放后露出的那一圈，必须由
  一个**最底层、不挂任何 `graphicsLayer` 变换**的 `Box` 铺 `surface` 兜住（见 §1.0）
- **自绘弹窗关闭后要立刻落配置**：`DialogHost` 没有「库的收起动画」，但关闭瞬间
  仍是一次状态翻转；配置写入统一走 `ModulePreferences.editDeferred { }`
  （延迟 360ms + `AtomicReference` 只保留最后一次）作为第二层防御；
  `notifyChanged()` 一律 post 到主线程（2.7.0 / 2.8.0 两轮修）
- **`values-en/strings.xml` 里的撇号必须写成 `\'`，引号写成 `\"`**。
  写 `&#39;` / `&quot;` 会在 AGP 合并资源时被**解码成裸字符**写进
  `merged.dir/values-en.xml`，而 aapt2 要求撇号转义，报
  `Invalid unicode escape sequence in string`（行号会指向文件末尾，极具误导性）。
  直接对合并后的文件跑 `aapt2 compile --dir` 才能看到真话（`unescaped apostrophe in string`）
- **`MiuixTheme` 通过 `LocalIndication` 提供 `MiuixIndication(onBackground)`**，
  按压时叠一层**背景色反色**浮层（深色模式即白色）。玻璃底栏**不挂任何
  `clickable` / `combinedClickable`**（手势全部收归 §6.2.1 的单一 `awaitEachGesture`），
  从根上就没有 indication，否则按一下整块变白（2.7.0 移除点击反馈，2.9.0 去掉可点击修饰符）
- **`pointerInput` 回调是过期闭包**：它的 key 只有 `(itemCount, itemWidthPx)`，
  重组不重建；手势里要用 `rememberUpdatedState` 取最新的 `current` / `onSelect`（见 §6.2）
- **悬浮底栏的让位靠 `LocalDockInset`**（滚动内容末尾补空白），不要把内容整体上推 ——
  推上去底栏背后就空了，玻璃没东西可采样，看起来「没悬浮」
- **`Modifier.align` / `Modifier.weight` 必须挂在对应容器的直接子节点上**。
  `align` 是 `BoxScope` 的 parent data，`weight` 是 `RowScope` / `ColumnScope` 的。
  `AnimatedContentScope` **只继承 `AnimatedVisibilityScope`、不是 `BoxScope`** ——
  在它的 content lambda 里写 `Modifier.align(...)` 能编译通过，但父容器读不到这份
  parent data，元素会掉到 Layout 左上角。2.6.0 的「底栏消失」就是这么来的，详见 §6.3
- **保存 / 导出文件走 SAF**（`ActivityResultContracts.CreateDocument`），
  不要申请存储权限，也不要直接写 `Environment.getExternalStorageDirectory()`
- **`Toast` 只能在主线程弹**。IO / Binder 调用放 `withContext(Dispatchers.IO)`，
  回来再 `Toast.makeText(...).show()`
- **XML 注释里不能出现连续两个 `-`**（`--`）。写分隔线用 `=` 或干脆不写，
  否则 `mergeResources` 直接报 `ParseError: 注释中不允许出现字符串 "--"`
- **根容器要铺 `MiuixTheme.colorScheme.surface`** —— 否则窗口底色会透出来（深色下是一片黑）
- `drawBackdrop` 的图层**会裁剪子节点**（`setClip(true)`），要溢出到胶囊外面的东西
  （比如长按气泡）必须放在 `drawBackdrop` **之外的兄弟节点**里；
  滑动指示同理，且要画在玻璃**之上**才不会被半透明面色压暗
- **手势回调里不能调挂起函数**（`Animatable.snapTo` 等）—— 用普通 state + 独立的
  `animate(...)`，见 §6.2
- **Binder 调用不能放主线程，`Toast` 不能放后台线程**：先 `withContext(Dispatchers.IO)`
  拿结果，回到 `scope.launch` 的主线程再弹提示（热重载踩过这个坑）
- 新增**功能开关**时，`core/ConfigKeys.kt` + `core/ConfigSnapshot.kt` +
  `core/ConfigSource.read()` 三处都要同步改；
  只被模块 App 消费的设置项（如 Dock 形态、主题设置、可预测返回）**不进** `ConfigSnapshot`
- **Dock 拖拽滑块的放大镜要采样「dock 自身图层」，不能采样页面 `backdrop`**（2.9.3 修）：
  早期滑块直接采样主页 `backdrop`（含底栏**下方**的内容），拖动时放大镜会透出底栏之下
  的内容、而非 dock 本身的图标 / 标题 / 玻璃 → 视觉穿透。修法：用
  `rememberLayerBackdrop { drawContent() }` 把「玻璃胶囊 + 图标行」单独录进 `dockBackdrop`，
  滑块画在内容层**之上**、只采样 `dockBackdrop`，穿透消失；
  `GlassDockItem` 删掉文字 `alpha = 1f - magnify`，标题与图标在镜中原样保留（2.9.3 修）
- **概览状态卡装饰图标要放进 `matchParentSize` 容器，否则会撑高卡片**（2.9.3 发现）：
  `Modifier.offset` 只挪位置不改正测量，`Modifier.size(170.dp)` 的图标作为 Box 直接子节点
  时，Box 高度恒等于 170dp，`heightIn(min = 148.dp)` **实际从未生效**（2.9.1 的 148dp 是
  150dp 图标顶出来的、2.9.2 图标放大后又被顶高 20dp）。把图标移进
  `Box(Modifier.matchParentSize())` 后它不参与父级测量，卡片高度才真正固定为 148dp，
  之后改字号 / 图标尺寸都不再改变卡片高度
- **弹窗遮罩压黑要一处加深、两处同步**：主题设置与重置模块共用同一个
  `Color.Black.copy(alpha = ScrimAlpha * progress)` 遮罩。2.9.3 把 `ScrimAlpha`
  从 0.32 **加深到 0.48**（用户反馈压黑不够、弹窗与身后内容对比不足），改一处两处一起生效，
  **不要**只在一处单独加遮罩
- **要让两条动画「独立运行」，就用两个 `Animatable`**（2.9.4）：主题设置弹窗的
  缩放（`progress`）与渐隐渐出（`fade`）刻意**不复用同一条进度** —— 入场是两个
  `LaunchedEffect` 各跑各的、出场是两条 `launch` 并发、`join()` 齐了再 `finishDismiss()`。
  共用一条进度等于把两种动画焊死（时长 / 曲线互相牵连），独立驱动才谈得上「互不干扰」
- **`MiuixIndication` 的按压高亮是「给整个节点 `drawRect` 铺一层」**（2.9.4）：
  它不会贴合圆角 —— 满宽行（`ChoiceRow`）的高亮是**矩形**，首 / 末行会越过弹窗
  28dp 圆角糊到外面。要给**内容层**加 `.clip(shape)` 裁一刀；**别裁外层** ——
  外层 `drawBackdrop` 还画着外投影，裁了连投影一起切掉
- **弹窗字号不要手写 sp（能引用就引用），且对齐对象要选「页面上最显眼的那行」**：
  重置弹窗的调参史是 **17 → 20 → 18（主）/ 14 → 16 → 15（副）**，2.9.6 定稿为主标题
  `title4.fontSize`（**18sp**）+ Medium、副标题 `15.sp`（Miuix 文字样式表没有 15 这一档：
  body2 = 14、body1 = 16，故直接写 sp）。三条经验：
  ① 17sp（= 卡片标题 `headline1`）在**整宽弹窗里居中**看就是「比外面的字小一号」——
  页面上最显眼的对照物是 **顶栏标题**（`SmallTopAppBar` 用 `title3` 20sp + Medium，
  `TopAppBarKt` 字节码可查）；
  ② 但直接顶到 20sp 又**过大**，18sp（`title4`）才是 17 与 20 之间的稳定解；
  ③ 正文同理：14sp 偏小、16sp 偏大，15sp 折中。**字号只能上机看，静态无法替代**
- **`TextField` 默认背景是 `secondaryContainer`，不是卡片色**（2.9.4）：
  想要「与卡片一致」必须显式传
  `colors = TextFieldDefaults.textFieldColors(backgroundColor = surfaceContainer, labelColor = onSurfaceSecondary)`；
  `borderColor` 默认 `primary`（同时是光标色），不要顺手改掉

---

## 10. 安装体积构成（2.4.0 起）

「设置」页的存储占用一度到 73 MB，和「只有一个功能」的体量完全不成比例。
实测下来原因很集中，记录在此，避免以后又走回头路。

### 10.1 前后对比

| 指标 | 2.3.1 | 2.4.0（刚开 R8） | 2.12.1 | **2.16.0（当前）** |
| --- | --- | --- | --- | --- |
| release APK | 11.45 MB | **1.34 MB** | **1.43 MB** | **1.50 MB** |
| dex 内类总数 | 26 553 | **2 987** | **3 068** | 未实测 |
| `classes.dex` 未压缩 | 30.9 MB | 2.6 MB | 2.57 MB | 未实测 |

> 2.16.0 比 2.12.1 略涨，是因为此后累计新增八个功能（2.13.0 策略筛选股票、
> 2.14.0 底栏智能盯盘入口、2.15.0 界面精简五项、2.16.0 手势导航条同色）——
> 涨幅不大，属于正常增长。
> 若某天又出现 **数倍级** 跳涨，先去查是不是有人引回了图标库（见 §10.2）。

安装后占用 ≈ APK 解压 + `dex2oat` 产物，通常是 APK 的 3～5 倍，
所以把 APK 压在 1.4 MB 量级后，安装占用回到正常范围。

### 10.2 主因：`material-icons-extended`

2.3.1 及以前 `app/build.gradle.kts` 里有这一行：

```kotlin
implementation("androidx.compose.material:material-icons-extended:1.7.5")
```

这个 AAR 把 Material 全量图标（Filled / Outlined / Rounded / Sharp / TwoTone 五种风格）
都编进了 dex，实测 **11 400 个类**，占当时类总数（26 553）的 **43%**；
而界面实际只用得到几个：返回箭头、对勾、放大镜、齿轮、信息、九宫格、拼图块、
圆对勾、圆感叹号（`AppIcons` 当前共 **9 个**）。

现已改为 `ui/AppIcons.kt` 用 Material 官方 24×24 网格的 path 数据自建 `ImageVector`，
零依赖、只生成这几个类。**新增图标不要引回这个依赖。**

### 10.3 其余部分都很小

| 部分 | 体积 | 说明 |
| --- | --- | --- |
| `classes.dex` | 2.57 MB | 唯一的大头 |
| `resources.arsc` | 172 KB | 资源索引 |
| `lib/*/libandroidx.graphics.path.so` | 约 37 KB | 4 个 ABI，Compose 路径绘制 |
| `assets/dexopt/baseline.prof` | 3.4 KB | 基线剖面，用于提速首次启动 |
| `res/` 单个最大项 | 1.7 KB | 图标位图 |

（上表为 2.12.1 release 包实测；2.16.0 的 release APK 为 1 567 896 B（≈ 1.50 MB），
类总数 / dex 未重测。**结论不变**：不存在「打包了奇怪东西」的情况，
唯一异常就是图标库。）

### 10.4 R8

2.4.0 起 release 构建 `isMinifyEnabled = true` / `isShrinkResources = true`。
入口类的 keep 规则见 `app/proguard-rules.pro`，**新增反射入口必须同步补规则**，
否则会出现「编译通过、装上去不生效」这种最难查的问题。

红线不变：`io.github.libxposed:api` 必须保持 `compileOnly`，
R8 不会把它打进包（已实测 dex 内该类为 0 个）。

---

## 11. 系统栏沉浸（2.5.0 起）

目标：**状态栏 + 手势导航条一律全透明，内容一直铺到屏幕物理边缘**。

### 11.1 「小白条是纯色填充」的根因

`enableEdgeToEdge()` 的默认值看着是无害的，其实埋了一个坑。
androidx.activity 的 `EdgeToEdgeApi29.setUp` 反编译出来是这样的：

```java
WindowCompat.setDecorFitsSystemWindows(window, false);
window.setStatusBarColor(statusBarStyle.getScrimWithEnforcedContrast(isStatusBarDark));
window.setNavigationBarColor(navigationBarStyle.getScrimWithEnforcedContrast(isNavBarDark));
window.setStatusBarContrastEnforced(false);
window.setNavigationBarContrastEnforced(navigationBarStyle.getNightMode() == MODE_NIGHT_AUTO);  // ← 这里
new WindowInsetsControllerCompat(window, view).apply {
    setAppearanceLightStatusBars(!isStatusBarDark);
    setAppearanceLightNavigationBars(!isNavBarDark);
}
```

而 `SystemBarStyle.auto(...)`（**就是默认值**）的 `nightMode` **正是** `MODE_NIGHT_AUTO`。
于是最后那一行会把「导航栏强制对比度」打开，系统随即在透明的导航栏上叠一层
半透明遮罩 —— 观感上就是手势条那一条被**纯色填充**了。

（`getScrimWithEnforcedContrast` 在 `nightMode == MODE_NIGHT_AUTO` 时返回
`Color.TRANSPARENT`，所以 `navigationBarColor` 本身是对的；问题全在对比度开关上。）

### 11.2 三层一起改

| 层 | 位置 | 做什么 |
|---|---|---|
| 主题 | `values/themes.xml` | 两个系统栏色透明；`enforceStatusBarContrast` / `enforceNavigationBarContrast` = `false`；`windowLightStatusBar` / `windowLightNavigationBar` = `true` |
| 主题（深色） | `values-night/themes.xml` | 同上，两个 light 标志改 `false`，`windowBackground` 换成深色 |
| Activity | `SettingsActivity.onCreate` | `enableEdgeToEdge(SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) × 2)`；API 29+ 再显式 `window.isNavigationBarContrastEnforced = false`（双保险） |
| Compose | `ui/SystemBars.kt` → `SyncSystemBars` | 按**实际生效**的深浅色设 `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`，并把窗口底色换成 `MiuixTheme.colorScheme.surface` |

为什么主题层关掉了还要在代码里再关一次：主题属性只在**窗口创建时**读一次，
而 `enableEdgeToEdge()` 是在 `onCreate` 里、窗口已经建好之后跑的，
它会把对比度开关**重新打开**。两边都得管。

### 11.3 两个容易踩的点

**`windowLightNavigationBar` / `windowLightStatusBar` 从 API 35 起被忽略。**
这两个**主题属性**在新版本上不再生效，只有 `WindowInsetsControllerCompat` 这条路一直有效。
本项目 minSdk 27 / targetSdk 36，所以两条路都留着。

**图标明暗要按「实际生效」的深浅色，不能按系统。**
`enableEdgeToEdge()` 内部用 `detectDarkMode(resources)`（即系统深色开关）算图标颜色，
而本项目的深色模式是用户可选的。用户把 App 强制成暗色、系统却是亮色时，
按系统算出来的就是白图标压在浅色页面上 —— 看不见。所以 `SyncSystemBars`
吃的是 `rememberDarkTheme()` 的结果。

### 11.4 窗口底色

`android:windowBackground` 只在 Compose 内容之外的缝隙里露出来（冷启动首帧、
系统栏过渡动画、圆角裁剪）。取值必须和 Miuix 的 `colorScheme.surface` 对齐，
否则那条缝隙会是一条**不一致的颜色**。

XML 里的值只能按系统深色开关选（`values-night`），所以 `SyncSystemBars` 在
Compose 起来之后再用 `MiuixTheme.colorScheme.surface` 覆盖一次 ——
这样即使「系统亮色 + 用户强制暗色」这种组合，也不会闪错色。

### 11.5 自检

```bash
APK=app/build/outputs/apk/release/app-release.apk
aapt2 dump resources "$APK" | grep -A 8 "style/Theme.GSZX"
```

应当看到默认变体和 `(night)` 变体各一份，其中：

```
0x01010054=@color/window_background        # windowBackground
0x01010451=@android:color/transparent      # statusBarColor
0x01010452=@android:color/transparent      # navigationBarColor
0x010104e0=true                            # windowLightStatusBar
0x0101056c=true                            # windowLightNavigationBar
0x01010604=false                           # enforceStatusBarContrast
0x01010605=false                           # enforceNavigationBarContrast
```

---

## 12. Launcher 图标（2.6.0 重绘，2.9.1 收紧留白）

简约苹果风格：**圆角方形底 + 竖向渐变 + 单一符号 + 大量留白**。
符号选「上升行情线 + 右上箭头」—— 行情 / 收益最普适的图形语言，
也与模块用途直接对应。

### 12.1 三个矢量层 + 五档位图

| 文件 | 作用 |
|---|---|
| `drawable/ic_launcher_background.xml` | 自适应图标背景层：铺满 108×108 的矩形 + 竖向渐变 |
| `drawable/ic_launcher_foreground.xml` | 前景层：折线 + 箭头，描边带渐变 |
| `drawable/ic_launcher_monochrome.xml` | Android 13+ 主题图标剪影（纯白描边，几何与前景完全一致） |
| `mipmap-anydpi-v26/ic_launcher{,_round}.xml` | 自适应图标，`<background>` 指向上面那个 drawable |
| `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round}.png` | 位图回退，由脚本生成 |

设计常量（108 网格，与矢量 viewport 同坐标系）：

```
折线  M31,70.5 L44,55.5 L55.5,62 L77,37.5    先扬 → 小幅回踩 → 冲高
箭头  M67.5,37.5 L77,37.5 L77,47             尖端在折线终点，向左 + 向下的直角箭头（臂长 9.5）
描边  strokeWidth 6.5，round cap / round join
底色  #FF2B3242 → #FF161A22（竖向）
线色  #FFFFC24A → #FFFF7A00（沿折线方向，起点浅金 → 终点深橙）
```

折线与箭头合并成**同一条 path**，这样描边渐变是连续的。

#### 12.1.1 2.9.1：箭头缩小 + 四周留白

用户反馈「图标的箭头太大，上下左右留白较少」。原几何相对安全区（18..90，72×72）
**左右只留 4.5**，图形占到安全区宽度的 **87.5%**，确实发挤。收紧后：

| | 2.6.0 | 2.9.1 |
|---|---|---|
| 外接框 | x 22.5..85.5（宽 63） | **x 27.75..80.25（宽 52.5）** |
| 占安全区宽 | 87.5% | **72.9%** |
| 左右留白 | 4.5 | **9.75** |
| 上下留白 | 12.5 | **16.25** |
| 箭头臂长 | 13 | **9.5** |
| 描边 | 7 | **6.5** |

做法：把整条折线**绕 (54,54) 等比缩到 0.82**，箭头臂长再单独收到 9.5
（缩放后本就该是 10.66，再缩一点才看得出「箭头变小」）；描边同步降到 6.5，
否则线在小图形上会显得过粗。缩放前后外接框中心都精确落在 (54,54)，没有偏心。

> 三处几何必须**逐项对齐**：`ic_launcher_foreground.xml`、`ic_launcher_monochrome.xml`
> 与 `tools/gen_launcher_icons.py` 的 `POLYLINE` / `ARROW` / `STROKE` /
> `LINE_GRAD_FROM` / `LINE_GRAD_TO`。改完矢量图**必须重跑脚本**重新生成五档位图：
> `python tools/gen_launcher_icons.py`。
图形外接框（含描边半径 3.5）约 x 22.5..85.5、y 30.5..77.5，居中且不出 72×72 安全区。

### 12.2 背景层为什么必须是 drawable

`<background>` 只接受 **drawable**，纯色资源做不出渐变。
所以背景从 `@color/ic_launcher_background` 换成了
`@drawable/ic_launcher_background`（一个铺满的矢量矩形 + `<aapt:attr>` 里的
`<gradient>`），原来的颜色资源已删除。

`aapt:attr` 也是给**描边**上渐变色的唯一写法：

```xml
<path android:fillColor="#00000000" android:strokeWidth="7" ...>
    <aapt:attr name="android:strokeColor">
        <gradient android:type="linear" ... />
    </aapt:attr>
</path>
```

### 12.3 位图回退用距离场描边

`tools/gen_launcher_icons.py` 是纯标准库实现（`math` / `struct` / `zlib`，不依赖 Pillow）：

1. 逐行算圆角矩形的 x 区间，填竖向渐变色（圆角半径 24 / 108 ≈ 苹果 squircle 的 22%）
2. 前景用**距离场**：对每个像素求到折线各段的最短距离，`距离 <= 线宽/2` 即上色。
   圆头笔帽与圆角拐点由距离场**天然产生**，不需要单独处理 line cap / line join
3. 圆形遮罩（`ic_launcher_round.png`）
4. 4× 超采样降采样做抗锯齿

脚本顶部的 `BG_TOP` / `BG_BOTTOM` / `LINE_TOP` / `LINE_BOTTOM` / `POLYLINE` / `ARROW` /
`STROKE` / `CORNER` 必须与两个矢量文件**逐项对齐** —— 改了一边就要改另一边。

```bash
python tools/gen_launcher_icons.py
```

### 12.4 自检

```bash
APK=app/build/outputs/apk/release/app-release.apk
aapt2 dump resources "$APK" | grep -E "ic_launcher_(background|foreground|monochrome)|color/ic_launcher_background"
```

期望：三个 drawable 都在、`color/ic_launcher_background` **不存在**。
再确认 `mipmap/ic_launcher` 与 `ic_launcher_round` 各有
5 档 PNG + 1 个 anydpi XML。

真机自检：手势导航条那一条应当**透出页面内容**（列表可以滚到它下面），
而不是一条纯色带；深色 / 亮色来回切，手势条与状态栏的图标应当跟着翻色。

