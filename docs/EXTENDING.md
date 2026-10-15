# 扩展与演进指南

> 本文回答两个问题：**怎么加一个新功能**，以及**这套架构以后能长成什么样**。
>
> 阅读顺序建议：先看 [架构说明](ARCHITECTURE.md) 了解分层，再回到本文动手。

---

## 1. 架构分层速览

```
core/       与框架无关的公共层
              · 两处共用：GLog / ConfigKeys / ConfigSnapshot / TargetApp
              · 仅目标进程：ConfigSource / HookBag（可引用 api 类型）
util/       纯工具
feature/    功能层 —— 新功能都加在这里（仅目标进程）
app/        模块 App 自己进程的逻辑（不得引用 api 类型）
ui/         设置界面（不得引用 api 类型，也不得引用 FeatureManager）
GszxModule  入口 —— 只做编排（仅目标进程）
```

一次功能安装的完整链路：

```
onPackageReady(classLoader)
   └─ FeatureManager.installAll()
        └─ 遍历 features，对每个「当前配置下启用」的功能调用 install(env, bag)
             └─ 功能内部：Reflect 找宿主类 → env.xposed.hook(m).intercept { … }
                                                   └─ 句柄塞进 bag
```

配置变化：

```
框架推送 → ConfigSource 监听 → FeatureManager.onConfigChanged(切主线程) → 各功能 onConfigChanged
```

---

## 2. 四条架构红线

这四条不是风格偏好，是踩过坑之后总结的硬约束。违反任何一条都会导致**模块静默失效**或**运行时崩溃**。

### 2.1 manifest 里绝对不能出现 `xposedminversion`

LSPosed 判定模块新旧的逻辑只看这一个 key：

```java
// LSPosed ModuleUtil.java
static boolean isLegacyModule(PackageInfo info) {
    return info.applicationInfo.metaData.containsKey("xposedminversion");
}
```

一旦出现，LSPosed 就把模块当 legacy 模块，去找 `assets/xposed_init` ——
而本项目已经没有那个文件了，结果是**模块静默不加载**（日志里连一行都没有）。

同理，`xposedmodule` / `xposedsharedprefs` / `xposedscope` / `xposeddescription`
也一个都不要加。模块元数据全部在：

```
META-INF/xposed/module.prop      模块属性
META-INF/xposed/java_init.list   入口类
META-INF/xposed/scope.list       作用域
```

模块名 / 描述取自 `android:label` / `android:description`。

### 2.2 `io.github.libxposed:api` 必须是 `compileOnly`

```kotlin
compileOnly("io.github.libxposed:api:102.0.0")        // ✅ 框架注入，不能打包
implementation("io.github.libxposed:service:102.0.0") // ✅ 模块 App 侧要用，必须打包
```

API 类由框架在目标进程注入。打成 `implementation` 会被 LSPosed 拒绝加载并报：

```
The Xposed API classes are compiled into the module's APK.
```

**怎么验证**（别用 `strings`，会误报 —— 模块自己的类里有 `io/github/libxposed/api/...`
的字符串常量引用）：

```bash
unzip -o -q app/build/outputs/apk/debug/app-debug.apk 'classes*.dex' -d /tmp/apkx
dexdump -f /tmp/apkx/classes*.dex | grep "Class descriptor" | grep 'libxposed'
# 期望：只有 io/github/libxposed/service/*，没有 io/github/libxposed/api/*
```

### 2.3 模块 App 进程会加载的类，不得 import `io.github.libxposed.api.*`

判定标准不是"在哪个目录"，而是**这个类会不会被模块 App 自己的进程加载**。
那个进程的 APK 里没有 api 类（`compileOnly`），一旦签名里出现这些类型就会
`NoClassDefFoundError`。

`core/` 下的文件要分开看：

| 文件 | 模块 App 进程会加载？ | 可以 import api？ |
|---|---|---|
| `core/GLog.kt` / `ConfigKeys.kt` / `ConfigSnapshot.kt` / `TargetApp.kt` | ✅ 会（两处共用） | ❌ 不行 |
| `core/ConfigSource.kt` / `core/HookBag.kt` | ❌ 不会（只在目标进程用） | ✅ 可以 |
| `app/**` | ✅ 会 | ❌ 不行 |
| `ui/**` | ✅ 会 | ❌ 不行 |
| `feature/**` | ❌ 不会 | ✅ 可以 |

`core/GLog.kt` 是标准示范：框架接口通过纯 Java 函数式接口注入，而不是直接持有。

```kotlin
object GLog {
    fun interface Sink { fun log(priority: Int, tag: String, msg: String, tr: Throwable?) }
    fun install(s: Sink) { sink = s }
}
```

**推论**：`FeatureManager` → `FeatureEnv` / `HookBag` → api 类型，链条一路传导，
所以 `ui/` 也**不能**引用 `FeatureManager`。设置界面想展示功能列表，得自己维护一份
展示用的元数据（见 `ui/FeatureCatalog.kt` 的 `FEATURE_CATALOG`）。

同理，`core/ConfigSource.kt` 与 `core/HookBag.kt` 虽然自己可以引用 api，
但**绝不能被 `app/` 或 `ui/` 引用**，否则整条链又会把 api 类型带进模块 App 进程。

**怎么验证**（比 2.2 的 dexdump 更直接，直接看源码依赖）：

```bash
cd app/src/main/java/io/github/SansKritO4/GSAuxiliary
# 模块 App 进程侧引用了哪些 core/feature 类
grep -rn "^import io\.github\.SansKritO4\.GSAuxiliary\.\(core\|feature\)" app/ ui/
# 期望：只有 core.ConfigKeys / core.GLog（含 core.LogLevel）这类无 api 依赖的类
grep -rn "^import io\.github\.libxposed" app/ ui/ core/
# 期望：app/ 只有 service.*（已打包）；ui/ 只有 service.*；core/ 只有 ConfigSource、HookBag 出现 api.*
```

### 2.4 挂钩子必须把句柄放进 `HookBag`

API 102 与传统 API 最大的行为差异：**钩子必须持有句柄才能卸载**。
句柄不收好，热重载时旧钩子会残留，功能叠加生效。

```kotlin
bag.add(env.xposed.hook(m).setId("xxx").intercept { chain -> … })
```

---

## 3. 新增一个功能：四步

以「给自选股名称加粗」为例。**不需要动入口类、配置层或 UI 之外的任何文件。**

### 第 1 步：加配置键（如果功能需要开关）

`core/ConfigKeys.kt`：

```kotlin
const val BOLD_WATCHLIST = "bold_watchlist"
const val DEFAULT_BOLD_WATCHLIST = false
```

`core/ConfigSnapshot.kt`：加字段 + 加进 `fingerprint`：

```kotlin
data class ConfigSnapshot(
    val enabled: Boolean,
    val color: Int,
    val scopeStrategy: Boolean,
    val logLevel: Int,
    val stgDirectQuote: Boolean,         // 以下四项为 2.12.x 现状
    val stgQuoteSwipe: Boolean,
    val stgSelectAll: Boolean,
    val boldWatchlist: Boolean,          // ← 新增
) {
    val fingerprint: Int
        get() = ((if (enabled) 1 else 0) * 31
                + color * 7
                + (if (scopeStrategy) 1 else 0) * 131
                + logLevel * 17
                + (if (stgDirectQuote) 1 else 0) * 977
                + (if (stgQuoteSwipe) 1 else 0) * 1231
                + (if (stgSelectAll) 1 else 0) * 1543
                + (if (boldWatchlist) 1 else 0) * 1877)   // ← 新增，取一个未占用的质数
}
```

> **指纹里的乘数互不相同**（31 / 7 / 131 / 17 / 977 / 1231 / 1543 / …），
> 且必须是质数风格的无关联值 —— 两个不同字段偶然算出同一个指纹，
> 就会漏掉一次配置刷新。新增字段时挑一个没被占用的数。

`core/ConfigSource.kt` 的 `read()` 里补一行：

```kotlin
boldWatchlist = p.getBoolean(ConfigKeys.BOLD_WATCHLIST, ConfigKeys.DEFAULT_BOLD_WATCHLIST),
```

### 第 2 步：写功能类

`feature/watchlist/BoldWatchlistFeature.kt`：

```kotlin
class BoldWatchlistFeature : Feature {

    override val id = "watchlist_bold"
    override val title = "自选股加粗"

    override fun isEnabled(cfg: ConfigSnapshot) = cfg.enabled && cfg.boldWatchlist

    override fun install(env: FeatureEnv, bag: HookBag) {
        val cls = Reflect.loadClass("com.guosen.commonui.adapter.BaseQuickAdapter", env.classLoader)
            ?: return
        for (m in Reflect.methodsNamed(cls, "getView")) {
            bag.add(
                env.xposed.hook(m)
                    .setId("bold:getView")
                    .intercept { chain ->
                        val r = chain.proceed()
                        // 在这里改 Typeface
                        r
                    }
            )
        }
    }
}
```

约定：

| 约定 | 原因 |
|---|---|
| `install` 里只挂钩子，不做重活 | 它在 `onPackageReady` 里同步执行，会拖慢宿主启动 |
| 所有句柄必须进 `bag` | 否则热重载残留（见红线 4） |
| `onConfigChanged` 在主线程被调用 | `FeatureManager` 负责切线程，实现方不用自己 `post` |
| 钩子里的异常必须自己吞掉 | 抛出去会影响目标 App；参考 `WatchlistFeature` 的 `runCatching` |
| 关掉开关时也要走一遍逻辑 | RecyclerView 会复用视图，不主动还原会残留旧颜色 |

### 第 3 步：登记

`feature/FeatureManager.kt` —— **唯一的接入点**：

```kotlin
val features: List<Feature> = listOf(
    WatchlistFeature(),
    BoldWatchlistFeature(),      // ← 加这一行
)
```

### 第 4 步：加设置项

**约定**：一级页面（功能 Tab）只放**开关**；具体参数一律收进**二级菜单**。
打开开关后，其下方才会展开「XX设置」入口。

1）在 `ui/FeatureCatalog.kt` 的 `FEATURE_CATALOG` 加一条 `FeatureSpec`，
   填好分类 / 标题 / 说明 / 开关文案 / 开关对应的配置键，
   以及二级菜单的标题与入口名：

```kotlin
FeatureSpec(
    id = "watchlist_bold",
    categoryRes = R.string.category_display,     // 「显示增强」；详见下面的「分区怎么选」
    titleRes = R.string.feature_bold_title,
    summaryRes = R.string.feature_bold_summary,
    switchTitleRes = R.string.feature_bold_switch,
    switchSummaryRes = R.string.feature_bold_switch_summary,
    enableKey = ConfigKeys.BOLD_WATCHLIST,
    defaultEnabled = ConfigKeys.DEFAULT_BOLD_WATCHLIST,
    detailTitleRes = R.string.detail_bold_title,
    detailEntryRes = R.string.feature_bold_detail_entry,
    detailEntrySummaryRes = R.string.feature_bold_detail_entry_summary,
)
```

> 当前只存在三个分类资源：`category_strategy`（策略页增强）、
> `category_stock_detail`（股票详情页增强）与 `category_display`（显示增强）。
> 引一个不存在的 `category_*` 会直接编译失败。

开关与「开启后展开二级入口」是 `FeatureTab` 统一渲染的，不用自己写。

> **`defaultEnabled` 一律填 `false`（硬约定，2.11.2 起）。**
> 「功能」是侵入目标 App 行为的增强，装完模块不代表用户同意被改 —— 功能菜单里
> 每一个开关的出厂默认都必须是**关**，由用户自己按需打开。
> 与之相对，功能**二级菜单里的参数**（同类的 `ConfigKeys.DEFAULT_XXX`）默认值照旧
> 取「开启 / 合理值」：二级入口只在总开关打开后才出现，进去时子项本就该是配好的状态。
>
> 因此新功能的配置键要**分成两类登记**：
>  - 总开关 → 写进 `ConfigKeys.FEATURE_KEYS`（`DEFAULT_*` = `false`）
>  - 功能参数 → 写进 `ConfigKeys.FEATURE_PARAM_KEYS`（`DEFAULT_*` = 开启 / 默认值）
>
> 这样「重置模块」才能各归各位：开关一律关掉，参数恢复默认。

**分区怎么选（必读）** —— 功能列表按分区展示，目前三个分区，
写 `categoryRes` 前先按下表对号入座（完整判据见 `ui/FeatureCatalog.kt` 顶部注释）：

| 分区 | 一句话判据 | 归属范围 |
|---|---|---|
| `R.string.category_strategy` **策略页增强** | **改变策略页的交互行为** —— 点下去会发生什么、能做什么操作 | 点股票直达个股详情、策略页全选 / 反选股票、策略页三点菜单「筛选股票」、批量加入自选、长按菜单 … |
| `R.string.category_stock_detail` **股票详情页增强** | **改变「个股 / 指数详情页」的交互行为** —— 判据同上，只是页面换成详情页 | 底栏「智能盯盘」快捷入口、详情页快捷下单、K 线手势、盘口长按 … |
| `R.string.category_display` **显示增强** | **只改变看到的样子** —— 颜色、字号、图标、布局、动效，不动交互语义 | 自选股名称高亮、名称加粗 / 字号、涨跌幅色块、盘口数字着色、K 线指标叠加 … |

判不准时按顺序问：**① 关掉它，用户的操作路径会变吗？**（会 → 该页面的「增强」分区）
**② 挂钩子的是「点击/跳转/数据流」还是「渲染」？**（渲染 → 显示增强）
**③ 换个不叫「策略页」的页面它还成立吗？**（成立 → 显示增强）

两个交互类分区之间按**功能生效的那个页面**选，一个功能只进一个分区；
两个页面都成立时归「主战场」那一侧，并在说明里点明另一侧也生效。

> **组内顺序 = `FEATURE_CATALOG` 的出现顺序**（`FeatureTab` 用 `groupBy` 分组，
> 首次出现的分组排最前）。所以想让某分区排在前面，就把它的一条 `FeatureSpec`
> 写在前面。分区顺序按「具体页面 → 通用外观」排：策略页 → 股票详情页 → 显示增强。
> **新增分区**要同时改 `ui/FeatureCatalog.kt` 的注释、
> 本表，以及 `values/strings.xml` / `values-en/strings.xml` 里的 `category_*`。

## 二级页 / 全屏页的导航条（小白条）沉浸

只要页面是「二级页」（走 `SettingsActivity.SubScreen`）或任何自绘全屏页，
**内容必须铺到屏幕物理最底边**，不要 `padding.calculateBottomPadding()`：

```kotlin
Scaffold { padding ->
    Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {  // ← 底部留 0
        content()
    }
}
```

一让开底部 inset，最后那 ~64px 就永远是空的，表现就是**「小白条下面填了一块纯色」**。
代价是最后一项可能压在导航条上，所以由外壳下发 `LocalNavBarInset`（`ui/SystemBars.kt`），
各页在滚动内容末尾补 `24.dp + LocalNavBarInset.current` 的空白。

> 一级页里的对应物是 `LocalDockInset`（悬浮底栏让位）。**二级页里它恒为 0**
> —— 那口子是 `HomeScaffold` 给的，二级页不在它的内容树里。

2）写二级菜单内容：仿照 `ui/WatchlistDetail.kt` / `ui/StrategyQuoteDetail.kt`，
   在 `ui/` 下新建一个 `XxxDetailScreen()`，用 `SmallTitle` + `Card` 分组，
   参数项用 Miuix 的 `SwitchPreference` / `ArrowPreference`，
   颜色选择用 `Widgets.kt` 里的 `ColorPresetItem`（带预览与选中态），
   卡片注脚用 `Widgets.kt` 的 `CardNote`，
   图标用 `AppIcons`（自绘 `ImageVector`，**不要引回 `material-icons-extended`**，
   原因见 `docs/UI.md` §10）。

   > ⚠️ **选择列表 / 二次确认一律走自绘 `DialogHost`，不要用 Miuix 的
   > `OverlayDropdownPreference` / `OverlayDialog`。**
   >
   > 这两个组件在库内部的 `onClick` 里**同步**开始收起动画，同时又同步回写配置状态，
   > 动画与重组互相打架 —— 表现为**一点就闪退**，调用侧无法规避
   > （2.8.0 已整体换成自绘，根因分析见 `docs/UI.md` §1.3 与 `ui/AppDialog.kt` 顶部注释）。
   >
   > 自绘弹层的用法：在 `HomeScaffold` 里取 `LocalDialogController.current`，
   > 调 `show(ChoiceDialogSpec(...))` / `show(ConfirmDialogSpec(...))`；
   > 写配置一律走 `ModulePreferences.editDeferred { }`（延迟写入，避开弹层收起动画窗口）。
   >
   > 顺带一条层级约定：**挂在 `Scaffold` 外面的浮层会盖住弹层** ——
   > `DialogHost` 与内容都在 content 内，液态悬浮底栏因此也放在 content 里
   > （见 `docs/UI.md` §1.3.3）。

3）在 `SettingsActivity.kt` 的 `Route` 里加一个 `data object`，
   并在 `MainScreen` 的 `when` 里挂上 `SubScreen(title = ..., onBack = ...) { XxxDetailScreen() }`。

**注意**：`ConfigSnapshot` 只放**目标进程要读**的键。
纯界面设置（例如 Dock 形态、桌面图标）只进 `ConfigKeys`，不进 `ConfigSnapshot`。

**完成。** 入口类、`FeatureManager` 的安装/卸载/配置分发逻辑都不用改 ——
`installAll()` 会自动安装，配置变化会自动切主线程分发。

---

## 4. 复用现有能力

写新功能时优先复用这些，别重新造：

| 能力 | 位置 | 说明 |
|---|---|---|
| 反射找类 / 找方法 / 读字段 | `util/Reflect.kt` | 沿继承链查找；`methodsNamed` 自动过滤 abstract 方法 |
| **候选名 + 多级兜底** | 每个功能自己的 `XxxTargets.kt` | 这是本项目的**核心容错模式**：所有目标 App 的类名 / 方法名 / 字段名都写成常量数组，App 升级后只加一项，不用重做逆向。每加一个功能就多一份：`watchlist/WatchlistTargets`、`strategyquote/StgDirectQuoteTargets`、`strategyselect/StgSelectAllTargets`、`stgfilter/StgStockFilterTargets`、`stockbar/StockDetailBarTargets` |
| 自选判定 | `feature/watchlist/ZxWatchlist.kt` | 与行情页同源，20/20 校准过 |
| 配置读取 | `env.config`（`ConfigSnapshot`） | 内存快照，热路径零 IO |
| 日志 | `core/GLog.kt` | 两个进程共用，自动选落点 |
| 钩子生命周期 | `core/HookBag.kt` | 集中卸载 |
| 配置变更通知 | `Feature.onConfigChanged` | 主线程，异常隔离 |
| 运行时视图注入 | `feature/strategyselect/StgSelectAllFeature.kt` | 往宿主布局里插控件、克隆同款样式、分帧驱动宿主自己的点击回调 —— 想做「加按钮 / 改布局」类功能先看它 |

---

## 5. 演进路线

### 5.1 近期（架构已就绪，加个 `Feature` 就行）

| 功能 | 复用 |
|---|---|
| 自选股名称加粗 / 字号 | `WatchlistFeature` 的 `getView` 钩子可提取为公共基类 |
| 涨跌幅色块标记 | 同上 |
| 盘口大单数字着色 | `Reflect` + 候选名机制 |
| K 线指标叠加（MA / 布林带） | 需要新增一组 `WatchlistTargets` 式候选常量 |

### 5.2 中期（需要小规模基础设施）

| 能力 | 需要做的事 |
|---|---|
| 多目标 App 支持 | `TargetApp.PACKAGE` 从常量改为集合；`scope.list` 加包名；`FeatureEnv` 补回 `packageName` |
| 功能级作用域 | `Feature` 加 `fun scope(pkg: String): Boolean` |
| 配置项分组 | `ConfigKeys.GROUP` 已参数化，`ConfigSource` 支持多组 |
| 设置界面按功能动态生成 | **已部分完成**：功能列表与开关由 `ui/FeatureCatalog.kt` 驱动； 二级菜单内容仍是每个功能手写（要彻底动态化得把参数项也描述成数据） |
| 运行时开关单个功能 | `HookBag` 已支持按功能卸载，需要给 `Feature` 加 `uninstall` |

### 5.3 远期

- **`DebugConfigProvider`** 目前只是 adb 读写配置，可以扩成完整的自动化测试夹具
- **`ConfigSource.channel`** 是配置通道的健康状态，可在设置界面可视化
- **热重载**已实现（`autoHotReload=true` + `onHotReloaded` 重装），可进一步做版本比对

---

## 6. 常见报错

| 报错 / 现象 | 原因 | 解法 |
|---|---|---|
| 模块装上但完全不生效，日志里一行都没有 | manifest 里有 `xposedminversion` | 删掉全部 `xposed*` meta-data（红线 1） |
| `The Xposed API classes are compiled into the module's APK` | `api` 写成了 `implementation` | 改回 `compileOnly`（红线 2） |
| `NoClassDefFoundError: io.github.libxposed.api.…` | `core/` 或 `app/` 引用了 api 类型 | 用 `GLog.Sink` 式解耦（红线 3） |
| 热重载后功能叠加生效两次 | 句柄没进 `HookBag` | 所有 `hook()` 的返回值都要 `bag.add`（红线 4） |
| `Unresolved reference 'ActivityThread'` | 误加了 import | 反射用 `Class.forName`，不需要 import |
| `Reification of an intersection type` | `arrayOf(...)` 传给泛型擦除的参数 | 写成 `arrayOf<Any>(...)` |
| `Error parsing AndroidManifest.xml` | XML 注释里出现了 `--`（非法） | 注释里不要写 `--` |
| `requires AGP 9.1.0 or higher` | Miuix 版本过高 | 用 0.9.3，别升（见 [BUILD.md](BUILD.md)） |
| 点中设置页的下拉 / 确认项**当场闪退** | 用了 Miuix 的 `OverlayDropdownPreference` / `OverlayDialog` | 改用自绘 `DialogHost`（见本文第 4 步的警告框、`docs/UI.md` §1.3） |
| 切完语言点设置页闪退 | `LocaleHelper.wrap()` 直接返回了 `ContextImpl` | 必须包一层 `ContextWrapper`（见 `docs/UI.md` §9） |

更多运行时问题见 [排错手册](TROUBLESHOOTING.md)。
