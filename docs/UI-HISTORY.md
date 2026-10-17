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
