# 设置界面

设置界面是模块自己的 App（`io.github.MVennn.GSAuxiliary`），用 [Miuix](https://github.com/miuix-kotlin-multiplatform/miuix)
0.9.3 的 Compose 组件写成，小米 HyperOS 风格。

代码在 `app/src/main/java/io/github/MVennn/GSAuxiliary/ui/`。

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
| `AppIcons.kt` | 自绘 `ImageVector` 图标（不引第三方图标库，见 [UI-HISTORY §10](UI-HISTORY.md#10-安装体积构成240-起)） |
| `Dock.kt` | `Tab` 枚举 + `LocalDockInset` + `MiuixDock`（官方 NavigationBar）+ `LiquidGlassDock`（液态玻璃悬浮底栏，含光效、可拖动滑动指示与长按气泡） |
| `ModuleTab.kt` | Tab 1 概览（顶部状态卡 + 模块 / 运行环境 两组，见 §3） |
| `FeatureTab.kt` | Tab 2 功能（按分类分组的开关卡片 + 开启后展开的二级入口，见 §4） |
| `SettingsTab.kt` | Tab 3 设置（**仅模块级设置项**） |
| `AboutTab.kt` | Tab 4 关于：Hero + 作者卡 + 开源鸣谢 + 免责声明二级页正文 `DisclaimerContent` |
| `AboutHero.kt` | 关于页 Hero 的绘制（渐变光场 / 负空间 mark / 滚动淡出），切到该页时顶栏上滑收起 |
| `WatchlistDetail.kt` | 二级页：自选股高亮的**高亮颜色设置**（作用域 / 高亮颜色 / 效果预览） |
| `StrategyQuoteDetail.kt` | 二级页：策略直达标准行情的**滑动设置**（仅「左右滑动切换股票」一项，见 §4.1） |
| `QuickConfigScreen.kt` | 二级页：一键配置的**方案列表**（增删改排序 / 设默认，见 §4.4） |
| `QuickConfigEditScreen.kt` | **三级页**：单个方案的编辑（名称 / 涨到槽 / 跌到槽 / 提醒时效 / 设为默认 / 删除，见 §4.4） |
| `MarkdownText.kt` | 轻量 Markdown 渲染（`AboutTab` 的免责声明正文用；不引 Markdown 库） |

一级页面的骨架：

```
Scaffold
├── topBar
│     · 液态玻璃 = GlassTopBar（drawBackdrop 玻璃 + 透明 SmallTopAppBar）
│     · 纯色     = SmallTopAppBar
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

`MainScreen` 不再是「用 `AnimatedContent` 整体替换页面」，而是叠放
（3.1.0 起二级之上还有一层三级页）：

```
Box(根容器，铺 MiuixTheme.colorScheme.surface)
├── 第 0 层  底色层 —— 只铺一个 surface 色，不挂任何 graphicsLayer 变换（2.7.0 新增）
├── 第 1 层  一级首页 HomeScaffold —— 常驻底衬
│            按 subProgress 缩放 1 → 0.94、透明度 1 → 0.65
└── 第 2 层  二级页 SubScreen —— translationX = width * (1 - subProgress)
             │  其本体（一个内层 Box）再按 editProgress 缩放 1 → 0.94
             └── 第 3 层  三级页 —— translationX = width * (1 - editProgress)
                          （与二级页本体是**兄弟节点**，不跟着一起缩）
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

- 常规前进 / 返回：`LaunchedEffect(route)` 把它们动画到 0 / 1
- 可预测返回手势：`PredictiveBackHandler` 里 `snapTo(1f - it.progress)` 跟手，
  手势取消再动画回 1

这样做的好处：
- **可预测返回时一级菜单就在二级页下方**（第 1 层常驻），不再是黑底
- 两层各自铺背景，切换过程**不会闪白**（原先 `SizeTransform(clip = false)` 会露窗口底色）

### 1.0.0 三级页为什么单独一层（3.1.0）

3.1.0 只有一条进度，三级页（方案编辑）直接顶掉二级页的内容，于是：

- **进三级页没有动画**（内容原地替换）；
- **从三级页可预测返回，看着像要回一级页**：手势把整层二级页往右推，底下露出
  首页，提交后又把方案列表推回来 —— 用户看到的是「退回首页 → 又跳回列表」。

现在二级、三级各一条进度，手势只推**最上面那一层**：

| 动作 | subProgress | editProgress |
|---|---|---|
| 进二级页 | 0 → 1 | 保持 0 |
| 进三级页 | 保持 1 | 0 → 1 |
| 三级页返回 | 保持 1（本体 0.94 → 1 回正） | 1 → 0 |
| 二级页返回 | 1 → 0 | 已为 0 |

两个注意点：

- **缩放只能挂在第 2 层内层的那个 Box 上**。挂到外层 Box 会连三级页一起缩 ——
  三级页是它的子节点，父节点的 `graphicsLayer` 变换会传给子节点。
- 二级页缩到 0.94 时与一级页（此时也是 0.94）**同尺寸**，所以四周不会露出一级页。

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
// 内容一直延伸到屏幕底部（液态底栏时连系统栏 inset 也吃掉，见 UI-HISTORY §11）
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

### 1.3 弹层：自绘 `DialogHost`

弹层（主题 / 语言的下拉、重置确认、方案菜单）**一律自绘**，代码在 `ui/AppDialog.kt`
（`DialogController` / `DialogHost`）、`ui/AppDialogSpec.kt`（两种 spec）、
`ui/AppDialogWidgets.kt`（贴底卡片与选项行）。

四条硬约束（细节、定位算法与调参史见 [UI-MATERIAL](UI-MATERIAL.md) §1.3）：

1. **不用 Miuix 弹层** —— 它在自己的 `onClick` 里同步开始收起动画，与同步回写的配置打架会闪退；
2. **动作排在关闭动画之后** —— 一律 `DialogController.dismiss { 动作 }`，动画期间不写配置；
3. **`spec` 要保留到退出动画播完** —— `closing = true` → 播完 → `finishDismiss()` 才置空，
   否则会渲染出一个没有标题和选项的空壳；
4. **宿主挂在 `MainScreen` 最外层 `Box` 的最后一个子节点** —— 锚点用 `boundsInWindow()`，
   宿主必须与窗口原点对齐，否则弹窗整体偏移。

## 2. 职责边界：设置页 vs 功能页

这是 2.2.0 的核心调整。

| 页面 | 放什么 | 不放什么 |
|---|---|---|
| **设置** | **模块范围内**的设置：桌面图标、材质设置（液态玻璃 / 玻璃光效）、通用（主题设置 / 语言 / 可预测返回）、数据管理（备份 / 恢复 / 重置）、日志（等级 / 导出） | 任何具体功能的开关与参数 |
| **功能** | 每个功能一行**开关**；开关打开后在下方展开该功能的「XX设置」入口 | 具体参数（都在二级菜单里） |

具体功能自己的可调项（高亮颜色设置 / 滑动设置、高亮颜色、效果预览）**只在功能 Tab 的二级菜单里出现**。

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
| 配置通道 | 排障信息（remote preferences ↔ 本地暂存），留在日志里就够了 |
| 模块名称 | 就是 App 名字，顶栏标题已经写着 |

去掉之后整页不再需要滚动，剩下的每一行都是「不看就不知道」的信息。

### 3.1 状态卡：Miuix 里没有这个组件

LSPosed 模块页顶部那张卡（绿色「已激活」+ 一枚大对勾）看着像 Miuix 自带件，
实际不是。把 `miuix-ui` / `miuix-core` / `miuix-preference` **0.9.3 的完整类清单**
过了一遍：`Card` / `BasicComponent` / `SmallTitle` / `Checkbox` / `Badge` /
`ProgressIndicator` / `Snackbar` / `Tooltip` / `Surface` / `TabRow` 都在，
但**没有**「状态卡 / Hero 卡」这一类复合组件 —— LSPosed 那张卡也是它自己用
Miuix 基础件拼的。

所以 `ModuleTab.kt` 里按 Miuix 的视觉规范自绘了一个等效实现。

> **3.2.1 起这张卡是「缩小版的关于」**：卡面直接用关于页的 `AboutHeroBackground`
> （同一份代码，只关掉底边渐隐 —— 卡片是封闭表面，渐隐只会吃掉下半张的光）。
> **不要**再压半透明底色：彩色光场上压一层灰，光感就没了。
>
> **配色分两层**：三态色（绿 / 红 / 灰）同时给**背景流光**与**右侧图标**；前景文字
> 保持中性（深色白 / 浅色黑，副行按同色降透明度）。文字再染色会和背景糊在一起。
>
> **图标照 LSPosed 那张「已激活」卡**：落在**右下角**，右边与下边各被卡边裁掉，
> 168dp（光圆 ≈ 卡高的 0.89 倍），已激活时是亮绿对勾（`#6ACF73`）。

```kotlin
Card(
    insideMargin = PaddingValues(0.dp),            // 图标要贴到卡边
    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surface),
) {
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = StatusCardMinHeight)) {
        AboutHeroBackground(                        // 关于页背景本体
            time = fieldClock, darkTheme = darkTheme, alpha = alwaysVisible,
            fadeBottom = false,
            palettes = lightFieldPalettesFrom(accent, darkTheme),  // 状态色 → 整套调色板
            modifier = Modifier.matchParentSize(),
        )
        // 状态色 + 出血：两条分支分开写，别在同一次调用里换 imageVector
        // （TintedIcon 内部按 painter 缓存，复用会停在上一颗图形）
        val glyphModifier = Modifier.align(Alignment.CenterEnd)
            .offset(x = iconShift).size(StatusCardIconSize)
        if (active) {
            TintedIcon(AppIcons.CheckCircle, tint = accent, modifier = glyphModifier)
        } else {
            TintedIcon(AppIcons.ErrorCircle, tint = accent, modifier = glyphModifier)
        }
        Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp)) {
            标题(titleColor, Bold, 34sp) / 版本(subColor, SemiBold, 18sp) / API(subColor, 16sp)
        }
    }
}

private val StatusCardMinHeight = 148.dp     // 2.9.3 起这才是卡片真实高度（见 §3.1.3）
private val StatusCardTitleSize = 34.sp      // 3.2.1：30 → 34，加粗到 Bold
private val StatusCardVersionSize = 18.sp    // 与标题是同一句话的两段，SemiBold
private val StatusCardApiSize = 16.sp        // API 是附注，收小一档、常规字重
private val StatusCardIconSize = 168.dp      // 3.2.1：按参照图回到 168（光圆 ≈ 卡高 0.89）
```

**调色板强度**：深色 `lerp(Black, accent, 0.55~0.95)` + alpha 0.40~0.52；浅色
`lerp(White, accent, 0.04~0.31)` —— 浅色必须压得比深色更狠，否则合成出来是灰绿
（实测比 LSPosed 浅色卡暗 30 个色阶，看着像脏）。

**行距**：标题与版本 2dp（原 6dp），版本与 API 也 2dp。**图标让位**：`TextMeasurer` 先量
三行最宽那行 —— 默认露 75%（`StatusCardIconDefaultVisible`），文字更宽就继续往右推，
最少留 25%（`StatusCardIconMinVisible`）；向下探出 `StatusCardIconDropFraction = 0.28`
（光圆上沿落在卡高 30%，圆底仍被切掉约 29dp，但**勾完整可见** —— 0.34 会把勾尾切掉）。

**状态读取**：读到「已激活」之前每 400ms 重读一次框架信息（最多 8 次）—— 框架刚起来的
那一小段 `scope` / 版本号可能还读不到，只算一次会永远停在「未激活 + 感叹号」。

| 状态 | 条件 | 流光 | 图标 | 文字 |
|---|---|---|---|---|
| 已激活 | 连上框架服务，作用域非空、版本可读 | `#0E8A52` 绿 | 深色 `#6ACF73` 亮绿 / 浅色 `#0E8A52` | 深色白 / 浅色黑 |
| 未激活 | 连上框架服务，但作用域为空 / 信息读不出 | `colorScheme.error` | 同色 | 同上 |
| 未连接 | 拿不到框架服务（没装 / 没启用 / 服务已断开） | `onBackgroundVariant` | 同色 | 同上 |

#### 3.1.1 图标的版式演进（2.9.1 → 2.17.2）

2.9.1 用户要求「卡片纵向拉长、右侧图标放到右下角只露左上四分之一」：高度定
`148dp`，图标 `52 → 150dp`、透明度降到 0.18，`align(BottomEnd)` 后各推半个身位。
2.9.2 字号上调一档（20/14/13 → 22/16/15）、图标 `150 → 170dp` 并回缩 16dp；
2.9.3 才发现**图标一直在参与卡片高度测量**（`offset` 不改尺寸，170dp 的图标
把卡片顶高），于是把它移进 `matchParentSize` 容器，高度从此只由内容 + 148dp 决定。

> **不需要自己包一层 `clip`**：Miuix `Card` 的 `BasicCard` 内部走 `squircleSurface`，
> 其中调用了 `ClipKt.clip(modifier, shape)`（`javap -c` 逆 `SquircleBackgroundKt`
> 确认），超出卡片范围的内容会被自动切掉 —— 这也是 `insideMargin` 必须置 0 的原因。

#### 3.1.4 2.17.2：裁切弧废弃，图标完整显示

2.9.1 ~ 2.17.1 一直走「顶到右下角、只露左上一块弧线」的路线，且一版比一版大 ——
2.17.1 到 260dp / 内缩 60dp 后，露出的 190dp 见方竖向盖满整条卡片右缘，
弧线从右上连到右下，认不出是个对勾。2.17.2 推倒重来：

- **完整显示**：`align(CenterEnd)` 右侧垂直居中，132dp 对 148dp 卡高上下各留 8dp；
- **`StatusCardIconInset` 删除**：不再裁切，偏移推导公式随之消失；
- **图标画在文字下层**（Box 里先声明图标、后声明文字列），窄屏下也不会盖字。

> 3.2.1 又按用户提供的 LSPosed 截图改了回来：图标**贴右缘、吃出卡片外**（默认露
> 75%），颜色用状态色。§3.1 开头那段就是这个形态。

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

> 这一页**只做展示**，不放任何操作项（2.18.0 起「框架操作」组也删了 —— 热重载按钮已移除，模块升级时框架自动热重载）。
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

页面直接渲染 `FEATURE_CATALOG`：按 `categoryRes` 分组，每组一个 `SmallTitle` + 一张
`Card`，组内顺序即目录顺序。页面上**没有搜索、没有筛选** —— 3.2.0 起移除了原有的
搜索框（连带 `titleRes` / `summaryRes` 这两个只喂搜索的元数据字段）。

每个功能在卡片里渲染为：

```
┌─────────────────────────────────────────┐
│ 自选股高亮                    [开关]   │
│ 把已加入自选的股票名称显示为高亮色        │
├─────────────────────────────────────────┤   ← 开关打开后才出现
│ 高亮颜色设置                       ›     │
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

当前十六个功能（顺序即界面顺序，分组见 `ui/FeatureCatalog.kt` 顶部的「分区归属标准」；
文案与命名规则见 [文案与命名规范](NAMING.md)）：

| 分区 | 功能 | 开关配置键 | 二级入口 |
|---|---|---|---|
| 策略页 | 策略直达标准行情 | `stg_direct_quote` | 滑动设置（`StrategyQuoteDetail`） |
| 策略页 | 策略多选全选 | `stg_select_all` | 无 |
| 策略页 | 策略筛选股票 | `stg_stock_filter` | 无（筛选开关在目标 App 自己的面板里） |
| 股票详情页 | 底栏智能盯盘入口 | `stock_bar_entry` | 无 |
| 智能盯盘 | 价格页一键配置 | `aistare_quick_config` | 方案设置 · 配置按钮设置（`QuickConfigScreen` / `QuickConfigButtonScreen`） |
| 智能盯盘 | 保存时清空盯盘 | `aistare_empty_save` | 无 |
| 智能盯盘 | 保存后免确认 | `aistare_skip_save_confirm` | 保存后返回位置设置（`AiStareSaveBehaviorScreen`） |
| 智能盯盘 | 盯盘数据卡片拓展 | `aistare_card_detail` | 无 |
| 底栏 | 底栏隐藏理财入口 | `dock_clean` | 无 |
| 首页 | 首页隐藏横幅 | `home_banner_hide` | 无 |
| 行情市场 | 行情市场隐藏滚动横幅与快捷入口 | `market_banner_hide` | 无 |
| 行情市场 | 行情市场板块显示单行 | `market_sector_compact` | 无 |
| 行情市场 | 行情市场概览高度优化 | `market_overview_compact` | 无 |
| 显示 | 手势导航条同色 | `nav_bar_blend` | 无 |
| 显示 | 自选股高亮 | `enabled` | 高亮颜色设置（`WatchlistDetail`） |

> 上表第二列写的是卡片上的功能名，即 `switchTitleRes`（与 `docs/NAMING.md` §5 的
> 对照表逐字一致）。

**所有开关出厂默认都是「关」**（2.11.2 起）。
功能是侵入目标 App 行为的增强，装完模块不代表用户同意被改 ——
`FeatureSpec.defaultEnabled` 一律取 `ConfigKeys.DEFAULT_*`，而那些默认值全是 `false`。

功能清单的数据源是 `FeatureCatalog.FEATURE_CATALOG`（展示元数据），
运行期真正生效的实现类登记在 `feature/FeatureManager.features` —— 两处都要加。

#### 4.1 二级页：「高亮颜色设置」与「滑动设置」

| 二级页 | 入口（功能 Tab） | 内容 |
|---|---|---|
| `WatchlistDetail.kt` | 自选股高亮 → 高亮颜色设置 | 作用于策略详情页（开关）/ 高亮颜色（4 种预设）/ 效果预览（两行示例，无说明行） |
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

#### 4.4 「一键配置」的方案列表页与编辑页（3.1.0 重排）

| 页面 | 层级 | 结构 |
|---|---|---|
| `QuickConfigScreen.kt` | 二级 | 「方案」一张卡：方案行（`ArrowPreference`：名称 + 预览 + 默认徽标 + ⋮ 菜单）+ 新建方案（主色 + 加号）+ 卡底一句口径脚注 |
| `QuickConfigEditScreen.kt` | **三级** | 名称卡 → 「最新涨到」「最新跌到」两张槽卡 → 提醒时效单卡 → 「操作」卡（设为默认 / 删除方案） |

三件事值得记：

- **卡内一律 Miuix 组件**（`ArrowPreference` / `BasicComponent` / `SwitchPreference` /
  `IconButton`），不再手写 `Row` + `Text` + `clickable`：按压高亮、字号、内边距、
  触控目标都由组件给，手写版本每处都要自己对齐一遍。
- **弹层锚点每个控件自己 `remember` 一份**（见 §9）。3.1.0 用共享变量接所有控件的
  `boundsInWindow`，谁最后布局完谁就是锚点 —— 点第一行的 ⋮，菜单从**最后一行**
  弹出来；编辑页点「聚合」，菜单贴在「添加取值项」上。
- **取值项清单不摆在列表页**：18 个取值项按分组平铺比方案列表还长，而那些名字在
  编辑页「添加取值项」的弹层里本来就有。列表页只留一句脚注（`quick_terms_note`）。

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
| 数据管理 | 备份配置 | 导出为 JSON（SAF `CreateDocument`），IO 在后台线程；文件名 `gsauxiliary-backup-yyyyMMdd-HHmmss.json`（2.18.0 起前缀由 `gszx-` 更名） |
| 数据管理 | 恢复配置 | 从 JSON 恢复（SAF `OpenDocument`），IO 在后台线程 |
| 数据管理 | 重置模块 | **自绘确认弹窗**二次确认后 `resetAllFeaturesDeferred()`：**关闭全部功能开关 + 恢复功能参数**，不退出页面 |
| 日志 | 日志等级 | 右侧显示当前值（`R.array.log_level_labels`），点开**自绘单选弹窗**：关闭 / 错误 / 信息 / 调试 |
| 日志 | 导出日志 | 把两个进程的日志拼成一份 txt（SAF `CreateDocument`），文件名 `gsauxiliary-log-yyyyMMdd-HHmmss.txt`；结果走 Toast |

> 「日志」分组在 3.1.0 一度整体删除，同版本后期按用户要求以新形态装回：
> **不再在模块里看日志，只保留「等级」与「导出」**，见 §5.7。

> 「框架操作 → 热重载目标进程」已于 2.18.0 移除：模块 APK 升级时框架会自动热重载
> （`module.prop` 的 `autoHotReload=true`），无需手动入口；`XposedService` 的
> 热重载类型也因此不再进界面层。

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

液态玻璃的实现细节见 §6；两档光效（完整 / 简约）的**正本定义**在
[LIQUID-GLASS.md](LIQUID-GLASS.md) —— 本节记录开关行为，不重复参数表。

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
只动「功能」范畴，界面设置（图标 / 液态玻璃 / 光效 / 语言 / 主题设置 / 可预测返回）不受影响，
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

### 5.7 日志：等级可调 + 一键导出（3.1.0）

设置页的「日志」分组只有两行 —— **「日志等级」与「导出日志」**。这一组的定位是
「排障开关」：用户不需要读懂日志，只要能在出问题时把日志交出来。

| 行 | 行为 |
|---|---|
| 日志等级 | 右侧显示当前档（关闭 / 错误 / 信息 / 调试），点开自绘单选弹窗；写入 `ConfigKeys.LOG_LEVEL`，**两个进程共用一个值** |
| 导出日志 | 直接弹系统保存对话框（SAF `CreateDocument`，`text/plain`），把两个进程的日志拼成一份文本；导出期间行内置灰并显示「正在导出…」，结果用 Toast 报（`log_export_done` / `log_export_partial` / `log_export_failed`） |

#### 等级怎么生效

`LogLevel` 四档（`core/GLog.kt`）：`OFF / ERROR / INFO / DEBUG`，默认 `INFO`
（`GLog.d` 不输出）。两个进程各自应用，**都不需要重启**：

| 进程 | 应用点 |
|---|---|
| 模块 App | `GszxApp.onCreate` 里应用一次；此后配置一变，`ModulePreferences.observe` 回调里再应用 |
| 目标进程 | `GszxModule.install()` 按首帧 `ConfigSnapshot.logLevel` 设置；之后框架推来的配置变化回调里持续同步（`ConfigSource.onChanged`） |

选「关闭」时顺带清掉落盘历史（`GLog.clearStore()`）：等级只拦新日志，留着旧日志会让人
以为开关没生效 —— 这条语义是 2.17.3 定的，沿用至今。

#### 导出的是什么

`core/LogExporter` 拼一份纯文本，抬头写清模块版本 / 设备 / 当前等级，然后两节：

| 节 | 来源 | 取法 |
|---|---|---|
| 模块 App 进程 | `filesDir/logs/gsauxiliary.log`（`core/LogStore`，单文件 512 KB、轮转留一份 `.1`） | 直接读，不需要任何权限 |
| 目标进程 | LSPosed 的 `/data/adb/lspd/log/` | **先直读**，读不到再 `su -c`（会弹一次 root 授权，20 秒超时） |

每个进程最多 3000 行（尾部），避免导出个几十 MB 的文件。目标进程那节**拿不到也照样出文件**，
原因写在那一节的抬头（未授权 / 超时 / 文件里没有本模块的行），**不做静默降级** ——
用户拿这份文件来排查时，最怕「看起来没日志」而不知道是被拦住了。提示语也据此分两种：
拿全了说「已导出日志到 X」，只有一半说「已导出日志到 X（目标进程日志未取到）」。

#### 为什么不再有「查看日志」页

3.1.0 里这里曾是「日志输出等级 + 查看日志二级页」（`ui/LogViewer.kt`，带级别筛选 /
复制 / 保存 / 清空），页面读的是 `GLog` 的内存环形缓冲 —— 而那个缓冲只装模块 App
进程自己的日志，**真正要看的钩子日志在目标进程里**，页面反而给不出。同版本后期整体换成
「导出」：把两个进程都拿到，交给用户自己去用（发给开发者、存下来慢慢看）。

代价是**模块里读不到日志内容了**：想看内容得先导出，或者照旧走 adb（见
`docs/TROUBLESHOOTING.md` §0 的三条通道）。

> 历史：查看页 2.3.0 按 Miuix 风格重做、2.6.0 加「保存到任意位置」、3.1.0 重排过一版，
> 3.1.0 连同 `TabRow` / `TextButton` 两个只为它存在的组件一起删除。

---

## 6. 液态玻璃（顶栏 + 悬浮底栏 + 弹窗）

材质体系只有三个表面：**顶栏**（`GlassTopBar`）、**悬浮底栏**（`LiquidGlassDock`）、
**弹窗**（`DialogCard`）。三档（完整 / 简约 / 纯色兜底）的定义与逐表面参数矩阵是
[LIQUID-GLASS](LIQUID-GLASS.md) 的正本；本模块里的实现细节、滑动指示、手势与
「底栏消失 / 下滑收起」两次踩坑，已移到 [UI-MATERIAL](UI-MATERIAL.md) §6。


## 7. 状态管理

口径见 [UI-MATERIAL](UI-MATERIAL.md) §7：配置（`PrefState`，落 `ModulePreferences`）、
路由（`Routing.kt` 的 `Route` + 两条进度）、纯界面态（`remember` / `rememberSaveable`）。
**跨 Activity 重建要活下来的必须 `rememberSaveable`**（转屏别把用户从编辑页踢回首页）。

## 8. 组件清单

可用组件与「别用哪一个」的清单见 [UI-MATERIAL](UI-MATERIAL.md) §8；新增图标只走
`ui/AppIcons.kt`，不要引回 `material-icons-extended`（体积代价见
[UI-HISTORY](UI-HISTORY.md) §10）。

## 9. 改界面时的注意事项

踩坑清单原文已移到 [UI-HISTORY](UI-HISTORY.md) §13（那里本来就是「踩坑史」）。
动界面前**至少先过一遍**，其中最容易再犯的五条：

- Miuix 锁 0.9.3、backdrop 锁 1.x（升级会连带 AGP 9 迁移）；
- 页面底色用 `colorScheme.surface`，用 `background` 会让卡片整片消失；
- 箭头一律自绘（`Widgets.ArrowRightIcon` / `DownArrowIcon`），Miuix 自带的染色不生效；
- 弹层一律 `dismiss { 动作 }`，不要在动画期间写配置；
- 系统栏 inset 只归 `Scaffold`，内容里再加 `navigationBarsPadding()` 会算两遍。

## 10. 体积 / 系统栏 / 图标（详见踩坑史）

- **安装体积**：见 [UI-HISTORY](UI-HISTORY.md) §10（`material-icons-extended` 是历史主因，现已移除）；
- **系统栏沉浸**：见 §11（全透明 + 关掉强制对比度 + `WindowInsetsControllerCompat`）；
- **Launcher 图标**：见 §12（三层矢量 + 五档位图；3.2.0 换成弧线 + 箭镞）。
