# 模块 App 界面踩坑史

> **一句话**：模块 App 界面里「改过一次、以后别改回去」的三段历史 ——
> 安装体积构成、系统栏沉浸、Launcher 图标。结论已在 [UI.md](UI.md) 相应小节给出，
> 这里保留完整的前因后果与根因分析，防止后人再踩一遍。

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

## 12. Launcher 图标（2.6.0 重绘，2.9.1 收紧留白，3.2.0 换图形）

简约苹果风格：**圆角方形底 + 竖向渐变 + 单一符号 + 大量留白**。
符号在 3.2.0 换成「一道上扬的弧线 + 顶端箭镞」—— 见 §12.1.2；
2.6.0 到 3.1.0 用的是「上升行情折线 + 右上直角箭头」。

### 12.1 三个矢量层 + 五档位图

| 文件 | 作用 |
|---|---|
| `drawable/ic_launcher_background.xml` | 自适应图标背景层：铺满 108×108 的矩形 + 竖向渐变 |
| `drawable/ic_launcher_foreground.xml` | 前景层：弧线 + 箭镞，描边与填充带渐变 |
| `drawable/ic_launcher_monochrome.xml` | Android 13+ 主题图标剪影（纯白，几何与前景完全一致） |
| `mipmap-anydpi-v26/ic_launcher{,_round}.xml` | 自适应图标，`<background>` 指向上面那个 drawable |
| `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round}.png` | 位图回退，由脚本生成 |

设计常量（108 网格，与矢量 viewport 同坐标系）：

```
弧线  M33.08,75.39 Q67.4,75.39 67.4,50.94      起笔水平、收笔竖直的二次贝塞尔
箭镞  M67.4,29.79 L57.06,50.94 L77.74,50.94 Z  底边中点正是弧线收笔点
线宽  strokeWidth 8.46（round cap）；箭镞描边 2.82（只用来把三个角磨圆）
底色  #FF2B3242 → #FF161A22（竖向）
线色  #FFFFC24A → #FFFF7A00（沿弧线方向，起点浅金 → 箭镞顶端深橙）
```

弧线与箭镞是两条独立 path：弧线只描边，箭镞填充 + 同色描边。渐变轴共用
（从弧线起笔到箭镞顶端），两段颜色因此接得上。

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

#### 12.1.2 3.2.0：换成一笔弧线 + 箭镞

「涨—跌—涨」的折线有两个拐点，缩到 48dp 以下那个 V 形回踩会糊成一团；
换成**一次转向**的弧线，任何尺寸都读得出「往上走」。设计源图是
`rise-1x1.svg`（240×240，图形是 mask 里的负空间）。

坐标从源图到 108 网格经过两次变换：

```
① 源图自身的 mask 分组   translate(120 120) scale(0.595) translate(-129 -113)
② 装进 108 网格          (p - 120) × 0.395 + 54
```

② 的 **0.395** 是安全区拟合系数。源图图形在自己的画布里偏满，直接搬过来外接圆
半径 38.9，超过自适应图标圆形遮罩的 36 —— 圆形桌面会把弧线起笔那一头切掉。
0.395 收到 **34.2**，留 1.8 的余量；代价是图形比源图略瘦，
换来的是圆角 / 圆形遮罩下都不被裁。

配套改动：

- 关于页 mark（`ui/AboutHero.kt`）的负空间挖空换成同一套坐标，线宽也用同一份
  常量；圆角从写死的 24dp 改成 `RoundedCornerShape(22)`（百分比），
  这样 88dp 的 mark 与 108 网格里 24/108 = 22.2% 的图标圆角比例一致
- `tools/make_social_preview.py` 不再照着 SVG 重画一遍：tile 直接取
  `gen_launcher_icons.render()` 的像素，social preview 与图标位图逐像素同源；
  右侧巨型水印跟着换成弧线 + 实心箭镞
- 位图侧的箭镞用「点在三角形内 || 到三边距离 <= 描边/2」判定，
  圆角接头仍是距离场副产品，没引入新的几何特判

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
2. 前景用**距离场**：弧线先按二次贝塞尔采样成密折线，对每个像素求到各段的最短距离，
   `距离 <= 线宽/2` 即上色；箭镞另算「点在三角形内 || 到三边距离 <= 描边/2」。
   圆头笔帽与圆角拐点由距离场**天然产生**，不需要单独处理 line cap / line join
3. 圆形遮罩（`ic_launcher_round.png`）
4. 4× 超采样降采样做抗锯齿

脚本顶部的 `BG_TOP` / `BG_BOTTOM` / `LINE_TOP` / `LINE_BOTTOM` / `CURVE_START` /
`CURVE_CTRL` / `CURVE_END` / `CURVE_STROKE` / `TRI` / `TRI_STROKE` / `CORNER`
必须与两个矢量文件**逐项对齐** —— 改了一边就要改另一边。
`make_social_preview.py` 的 tile 与右侧水印也读这份常量，所以是「改一处，四处跟」。

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

---

## 13. 改界面时的注意事项（原文，2026-10-18 从 UI.md §9 移入）

> 这一节是踩坑清单的**全文**，UI.md §9 只留了最容易再犯的五条摘要。
> 节内的「见 §x.y」指向**原 UI.md 的编号**：§1.3（弹层）、§6（玻璃）、§7（状态）、
> §8（组件）这些章节已搬到 [UI-MATERIAL.md](UI-MATERIAL.md)，编号照旧。
> 节内没有编号，条目按「能不能再踩一次」排列。


- **Miuix 0.9.3 不可升级**（0.9.4+ 要求 AGP ≥ 9.1，与 Kotlin 插件冲突），
  新增组件前先确认它在 0.9.3 的 API 里可用
- **backdrop 停在 1.x**（2.x 需要 Compose 1.12 → AGP 9.1+）
- Miuix 扩展图标（`MiuixIcons.Extended.*`）**不在 0.9.3 的产物里**，
  图标统一走 `ui/AppIcons.kt`（自绘 `ImageVector`）。
  **不要引回 `material-icons-extended`** —— 那个包会把 11400 个用不到的图标类带进 dex
  （详见 [UI-HISTORY §10](UI-HISTORY.md#10-安装体积构成240-起)），新增图标照抄 `AppIcons.kt` 的写法加一条即可
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
- **板块切换时不要让任何共享状态挂动画**（2.9.2 修的教训，搜索框已移除）：早期搜索框
  挂在顶栏，为了「切板块」做了横向滑入 + 方向跟着左右切换走，结果与板块本身的左右平移
  撞在一起 → 割裂感。教训保留：动画值只驱动它自己的元素，切换期间每页按身份取定值
  （见 `docs/UI-HISTORY.md`）
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
  API 35+ 已失效）。详见 [UI-HISTORY §11](UI-HISTORY.md#11-系统栏沉浸250-起)
- **页面底色必须是 `colorScheme.surface`，不是 `background`**：Miuix 里
  `background` 与 `Card` 的默认色 `surfaceContainer` **完全同色**
  （深色都是 #242424 / 浅色都是 #FFFFFF），用 `background` 会让所有卡片消失、
  整页看不出分组（2.7.0 的回归，2.8.0 修）。需要同色的五处：
  第 0 层底色层、根 `Box`、`Scaffold.containerColor`、`SubScreen` 的 `Scaffold`、
  `rememberLayerBackdrop` 的底衬。色值表见 §1.0
- **可预测返回要有一层不参与变换的底色层**：一级页缩放后露出的那一圈，必须由
  一个**最底层、不挂任何 `graphicsLayer` 变换**的 `Box` 铺 `surface` 兜住（见 §1.0）
- **三级页要单独一层、单独一条进度**（3.1.0，见 §1.0.0）：只有一条进度时，
  三级页的可预测返回会把整层二级页推走、露出首页，看着像要回一级页
- **锚定菜单的锚点必须每个触发控件一份**：共享一个 `Rect` 状态的写法是错的 ——
  `onGloballyPositioned` 谁最后回调谁就是锚点，菜单会贴在别的行上（3.1.0 修）
- **自绘弹窗关闭后要立刻落配置**：`DialogHost` 没有「库的收起动画」，但关闭瞬间
  仍是一次状态翻转；配置写入统一走 `ModulePreferences.editDeferred { }`
  （延迟 360ms + `AtomicReference` 只保留最后一次）作为第二层防御；
  `notifyChanged()` 一律 post 到主线程（2.7.0 / 2.8.0 两轮修）
- **弹层里的动作要排在「关闭动画播完」之后**（3.1.0）：`DialogController.dismiss { 动作 }`
  把动作交给 `finishDismiss()` 执行。`dismiss()` 与写配置**挨着**调用时，`commit()`
  会同步回调订阅方当场重组，而收起动画还在跑 —— 就是上一条那类崩溃。
  （菜单 / 确认 / 同意三种弹窗的动作都走这条。）
- **图标染色要走 GraphicsLayer，别用 `Icon(imageVector, tint = ...)`**（3.1.0）：
  `Icon(imageVector, tint)` 与 `Image(colorFilter = ColorFilter.tint(...))` 这条
  **画笔记账层**的染色在魅族 20 / Android 16 上整体不生效（debug 与 release 都复现，
  换成 `Color.Red` 也照旧画成占位黑），图标一律是黑的。统一改用 `Widgets.TintedIcon`
  （miuix `Icon(painter, tint = ColorProducer, ...)` → `layer.colorFilter`，
  与 Miuix 底栏图标同一条路）
- **锚定菜单要有最大高度**（3.1.0）：`MenuMaxHeightFraction = 0.45` 把卡片限在屏高 45%，
  超出的选项由列表自己 `verticalScroll`（右侧是一条**常驻**指示条，内容超出才画）——
  取值项目录 18 项，不封顶时菜单几乎铺满整屏
- **画在卡片里的东西别参与测量**（3.1.0 踩过）：那条常驻指示条一开始写成
  `Modifier.width(3.dp).fillMaxHeight()`，`fillMaxHeight` 会参与测量，把整张卡片顶到
  高度上限 —— 7 个周期的菜单只有 329dp 内容，却被撑成 390dp、底部白出一大截
  （用户报的「弹窗过长」）。改成 `matchParentSize()`（只取父盒量好的尺寸、不反过来
  影响它），宽度 / 右缘 5dp / 上下 16dp 的几何全在 `drawBehind` 里自己算。
  顺带两个上机结论：① 开头 8dp 会压在 28dp 圆角的弧线上，16dp 才落在直边里；
  ② Miuix 亮色主题的 `onSurfaceVariantSummary` 几乎是纯黑，不透明度给到 0.7 就是
  一条硬黑线，**0.18** 才是不抢眼的浅轨（真机取色：卡片 245 → 条子 200）
- **`AnimatedVisibility(visible = false)` 首次挂载不播退出动画**（3.1.0 踩过）：
  想给列表行的「删除」补收起动画，不能按状态分成「普通 / 进入 / 移除」三条分支 ——
  删除那一刻节点是新建的，`visible` 直接就是 false，动画不会跑。正确写法是
  **每一行常驻一个 `AnimatedVisibility`**，出现与移除都只翻 `visible`
- **App 里的箭头一律自绘，不用 `ArrowPreference`**（3.1.0）：Miuix 自带那颗箭头走
  `Image(colorFilter = ColorFilter.tint(...))` —— **画笔记账层**染色，在本机不生效，
  库里所有箭头**永远是黑的**、不跟亮暗色走（和自绘图标摆一起明显不是一套）。
  现在 14 处 `ArrowPreference` 全换成 `BasicComponent` + `Widgets.ArrowRightIcon()`：
  - 会**呼出弹窗**的行（主题设置 / 语言 / 日志等级 / 聚合方式 / 周期 / 提醒时效）
    用 `Widgets.DownArrowIcon()`（朝下 = 就地弹窗，朝右 = 进入下一级）；
  - 进入下一级 / 动作行保持朝右，禁用态传 `ArrowRightIcon(enabled = false)`。
  两颗箭头现在是**同一颗 chevron**（`strokedIconOf` 自绘，斜率 47°、描边 1.4dp、
  臂长 5.9dp；3.2.1 起删除键的 ✕ 也并入这套度量：1.4dp / 47° / 单臂 4.0×4.3 /
  10×10.7 画布，墨迹中心与 › 同点），只差方向：› 墨迹 5.7×10.1dp、⌄ 墨迹 9.5×5.8dp，两者墨迹中心同列
  （实测 cx 都 986.5、⋮ 与 › 的 cy 都 485.5）。
  - **别用「缩放旋转路径」调大小**（连踩两轮）：放大到 20×12.5 比 › 宽一倍、右缘对齐
    后中心偏左 3.3dp；缩到 11dp 笔画又只剩 0.69dp。描边是路径的一部分，缩放会连笔画一起改。
  - **判断「两颗箭头是不是一套」，看臂长而不是外框**：Miuix 原路径（10×16 视口）
    臂长 9.65dp，而下箭头那版只有 5.87dp —— 差 1.6 倍，摆同一页就是「右箭头比下箭头
    大一圈」。同臂长 + 同斜率 + 同描边，才是一套（旋转不变）。
  - 列对齐靠**两个图标的声明宽度一致**（都 10dp），墨迹在框内居中，中心自然对齐。
- **主题过渡遮罩要靠 `try/finally` 收尾**（3.1.0）：那层「旧配色遮罩」是
  `Animatable.animateTo` 淡出的，过渡途中被重启就停在半透明处 —— 表现是「切完主题
  整个界面被压暗，重启才恢复」。用 `withContext(NonCancellable) { snapTo(0f) }` 兜底
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
- **状态卡浅色光场的「浓度」与「档距」是两个独立的旋钮，缺一不可**（3.2.1 后封版期）：
  浅色调色板四档全不透明，渐变只靠档间色差读出来 —— 四档兑白比例挤在 0.18 的
  窄档距里时几乎同色，光场铺出来就是一块死板单色。深色看不出这问题，因为它另有
  alpha 0.40~0.52 的档差撑着结构。三轮校准：0.03~0.23 淡到与灰底融成一片；
  0.39~0.59 够绿但渐变消失；定稿「浓度 0.17~0.63 + 档距 0.44」，左上淡薄荷、
  右下饱和绿。参数正本与推导见 [UI.md §3.1](UI.md#31-状态卡miuix-里没有这个组件)，调之前先想清楚
  拧的是哪个旋钮
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
- **行尾控件的显隐动画别只用横向**（3.2.1）：`AnimatedVisibility(exit = shrinkHorizontally)`
  收窄时子节点高度一直留着，**播完那一刻**节点离场、高度归零 —— 而 `BasicComponent`
  的行高取「文字行 / startAction / endActions」里最高的那个，于是整行矮一截、整张卡
  跟着跳一下（「✕ 消失时卡片突然变矮」就是这个）。做法：别卸载节点，改成常驻一个
  跟触控区同高的格子、只让**宽度**在 40 ↔ 0 之间动，行高在两种状态下一致。
  竖排的 `shrinkVertically` 没这问题（它把高度动到 0 才离场）

- **想让一个控件「只撑高度、不显示」时，光把文字设成透明不够**（3.3.0 顶栏改版踩到）：
  透明文字**仍然在语义树里** —— 读屏会把同一行念两遍（高度尺里的那个 + 真标题）。
  还要加 `Modifier.clearAndSetSemantics {}` 把它整个从语义树里摘掉。
  另外透明文字**照样参与测量**（这正是要的：高度保留），所以「透明 + 清语义」
  才是「只当高度尺」的完整做法（见 [UI.md §1.0.2](UI.md#102-顶栏只留标题副标题只撑高度33x)）
- **`TextField` 默认背景是 `secondaryContainer`，不是卡片色**（2.9.4）：
  想要「与卡片一致」必须显式传
  `colors = TextFieldDefaults.textFieldColors(backgroundColor = surfaceContainer, labelColor = onSurfaceSecondary)`；
  `borderColor` 默认 `primary`（同时是光标色），不要顺手改掉

---


