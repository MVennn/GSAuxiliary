# 架构说明

> 模块实现为 **LSPosed 现代模块（libxposed API 102）**。
> 怎么加新功能见 [扩展与演进指南](EXTENDING.md)。

---

## 1. 进程模型

同一份 APK 里有**两套代码，跑在两个进程**：

```
┌─────────────────────────────────────┐    ┌─────────────────────────────────────┐
│ io.github.MVennn.GSAuxiliary（模块自己的进程）  │    │ com.guosen.android（目标 App 进程）  │
│                                     │    │                                     │
│ ui/SettingsActivity.kt  ← 路由       │    │ GszxModule.kt      ← 被注入执行      │
│   ├─ ui/{Dock,Glass,AppDialog,      │    │   └─ FeatureManager                  │
│   │      SystemBars}.kt             │    │        ├─ WatchlistFeature           │
│   ├─ ui/{Module,Feature,Settings,   │    │        ├─ StgDirectQuoteFeature      │
│   │      About}Tab.kt               │    │        └─ StgSelectAllFeature        │
│   ├─ ui/{WatchlistDetail,           │    │ core/ConfigSource                    │
│   │      StrategyQuoteDetail,       │    │ core/HookBag                         │
│   │      QuickConfigScreen.kt,      │    │ core/{ConfigKeys,ConfigSnapshot,     │
│   │      QuickConfigEditScreen.kt   │    │       GLog,TargetApp}.kt             │
│   │      ← 二级 / 三级页            │    │ util/Reflect.kt                      │
│   └─ ui/{PrefState,FeatureCatalog,  │    │                                      │
│          Widgets,AppIcons,          │    │                                      │
│          MarkdownText}.kt           │    │                                      │
│ app/GszxApp.kt                      │    │                                      │
│ app/{FrameworkBridge,ConfigBackup,  │    │                                      │
│      LauncherIcon,ModulePreferences,│    │                                      │
│      LocaleHelper,                  │    │                                      │
│      DebugConfigProvider}.kt        │    │                                      │
│ core/Log{Store,Exporter}.kt         │    │                                      │
│                                     │    │                                      │
│         │ 写                        │    │         │ 读                         │
│         ▼                           │    │         ▼                            │
│   XposedService.                    │    │   XposedInterface.                   │
│   getRemotePreferences("config")    │    │   getRemotePreferences("config")     │
└─────────────────────────────────────┘    └──────────────────────────────────────┘
                  ▲                                        ▲
                  │          LSPosed 守护进程托管          │
                  └───────────remote preferences───────────┘
                  （框架负责持久化 + 跨进程同步 + 变更推送）
```

> 模块进程的 `ui/` 只**读**模块设置（`ModulePreferences` 的 LOCAL/REMOTE 双后端）与
> `feature/` 的功能开关**元数据**（`ui/FeatureCatalog.kt` 里的静态表），
> 不直接依赖 `FeatureManager` —— 原因见 [2.1 分层规则](#21-分层规则)。

配置通道由框架托管，两边只是各拿一个 `SharedPreferences` 来用：

- **写**（模块进程）：`XposedService.getRemotePreferences("config")`
- **读**（目标进程）：`XposedInterface.getRemotePreferences("config")`
- **变更通知**：框架主动把新值推给注册了监听的进程 —— 不需要轮询

---

## 2. 代码分层

**入口与公共层**

| 文件 | 职责 | 运行位置 |
|---|---|---|
| `GszxModule.kt` | 判进程、装配配置通道、驱动 `FeatureManager`、热重载 | 目标进程 |
| `core/ConfigSource.kt` | 打开 remote prefs、注册变更监听、产出 `ConfigSnapshot` | 目标进程 |
| `core/ConfigSnapshot.kt` | 不可变配置快照 + 稳定指纹 | 两处共用 |
| `core/ConfigKeys.kt` | 配置键与默认值（**纯常量，不引用任何框架类**） | 两处共用 |
| `core/GLog.kt` | 统一日志出口 + `LogLevel`，等级由配置驱动（**不引用 api 类型**） | 两处共用 |
| `core/LogStore.kt` | 日志落盘（两份滚动，`filesDir/logs`），供「导出日志」读取 | 模块 App |
| `core/LogExporter.kt` | 导出日志：本机落盘 + 直读 / `su` 读 LSPosed 日志，拼一份文本 | 模块 App |
| `core/HookBag.kt` | 钩子句柄集合，支持一次性卸载 | 目标进程 |
| `core/TargetApp.kt` | 目标包名 | 两处共用 |
| `util/Reflect.kt` | 反射：候选名、继承链查找、过滤 abstract | 目标进程 |
| `feature/Feature.kt` | 功能扩展接口 | 目标进程 |
| `feature/FeatureEnv.kt` | 功能运行环境（xposed / classLoader / config） | 目标进程 |
| `feature/FeatureManager.kt` | 功能注册表 + 安装/分发/卸载 | 目标进程 |
| `feature/PageSyncFeature.kt` | 「在宿主页面上动视图」类功能的调度骨架（布局回调 + 首帧屏障 + 幂等自愈 + 限速 + 失配快照 + id 预热） | 目标进程 |

**功能包（`feature/*/`，每个功能一份候选常量）**

| 文件 | 职责 | 运行位置 |
|---|---|---|
| `feature/watchlist/WatchlistFeature.kt` | **自选高亮**功能本体（渲染后补色） | 目标进程 |
| `feature/watchlist/WatchlistTargets.kt` | 该功能的宿主类名/字段名候选常量 | 目标进程 |
| `feature/watchlist/ZxWatchlist.kt` | 自选判定（`ZXWorkflow` + 兜底） | 目标进程 |
| `feature/strategyquote/StgDirectQuoteFeature.kt` | **策略详情直达标准行情 + 左右滑动**功能本体（改写 `EngineIntent`） | 目标进程 |
| `feature/strategyquote/StgDirectQuoteTargets.kt` | 该功能的类名 / action / extra key 候选常量 | 目标进程 |
| `feature/strategyselect/StgSelectAllFeature.kt` | **策略多选栏「全选 / 反选」**功能本体（运行时视图注入） | 目标进程 |
| `feature/strategyselect/StgSelectAllTargets.kt` | 该功能的宿主控件 id / 类名候选常量 | 目标进程 |
| `feature/stgfilter/StgStockFilterFeature.kt` | **策略详情筛选股票**功能本体（挂点分析与状态机：方向 + 股票类型两组条件） | 目标进程 |
| `feature/stgfilter/StgStockFilterTargets.kt` | 该功能的菜单 / 面板 id 候选常量 | 目标进程 |
| `feature/stgfilter/StgFilterPanelBuild.kt` | 面板装配（从 Feature 本体拆出：三点菜单注入、菜单项高度归一化、面板样式复刻） | 目标进程 |
| `feature/filter/StockFilters.kt` · `StockFilterStore.kt` · `StockTypes.kt` · `StockFilterPanelUi.kt` | **两块筛选面板共用的一套**：八项条件模型（涨跌与自选 / 股票类型两组语义）+ 落盘（目标 App 自己的 prefs，含进程级共享缓存）+ 股票类型判定 + 面板装配工具（行容器换法 / 建行 / 分组头 / 样式克隆 / 监听包装） | 目标进程 |
| `feature/stgfilter/StgFilterPanel.kt` · `StgFilterBridge.kt` | 策略详情页那颗面板（八行两组、重置 / 确定）+ 筛选结果只读通道（目标进程内：`StgStockFilterFeature` 换好数据后经 `StgFilterBridge` 流向直达标准行情，解决「筛选后滑动漏股票」） | 目标进程 |
| `feature/market/MarketFilterFeature.kt` · `MarketFilterTargets.kt` | **行情页「涨跌与自选」筛选**功能本体（往宿主 `FilterDiaglog` 里追加三行 + 按它们收起排行榜行）+ 定位常量 | 目标进程 |
| `feature/stockbar/StockDetailBarFeature.kt` | **个股详情页底栏「智能盯盘」快捷入口**功能本体（注入按钮 + 触发面板） | 目标进程 |
| `feature/stockbar/StockDetailBarTargets.kt` | 该功能的底栏 / 面板 id 候选常量 | 目标进程 |
| `feature/stockbar/StockDetailBarMeasure.kt` | 取色 / 加宽测量（从 Feature 本体拆出：从「更多」按钮上取样式与几何尺寸） | 目标进程 |
| `feature/aistare/AiStareQuickConfigFeature.kt` | **智能盯盘价格页「一键配置」**功能本体（H5 注入 + 自建 `gsaux` JS 桥 + 取数调度） | 目标进程 |
| `feature/aistare/AiStareQuickConfigTargets.kt` | 该功能的页面 URL / 桥名 / 配色 / 宿主行情字段候选常量（全部真机实测） | 目标进程 |
| `feature/aistare/AiStareJs.kt` | 注入价格页 H5 的脚本常量（找保存按钮 / 三等分 / 弹方案层 / 回填输入框） | 目标进程 |
| `feature/aistare/AiStareCardDetailFeature.kt` · `AiStareCardDetailTargets.kt` | **我的盯盘卡片跳个股详情页**功能本体（挂卡片点击 + 跳转契约）+ 定位常量 | 目标进程 |
| `feature/aistare/quote/QuoteRepository.kt` · `QuoteFetcher.kt` | 取数源接口 + 会话（按各通道**声明的能力**逐级降级，非全局锁定）+ 取数编排（持久实例，日 K 缓存） | 目标进程 |
| `feature/aistare/quote/HostQuoteSource.kt` · `PublicQuoteSource.kt` · `EastmoneyQuoteSource.kt` · `DomQuoteSource.kt` | 四条取数通道：① 宿主反射（只快照）→ ② 腾讯 → ③ 东财 → ④ 页面 DOM（只最新价） | 目标进程 |
| `feature/aistare/quote/HttpTransport.kt` | 公开行情 HTTP 统一出口（状态码判定、可恢复错误重试一次、响应体封顶） | 目标进程 |
| `feature/ipoclean/IpoDialogCleanFeature.kt` · `IpoCleanTargets.kt` | **新股申购提醒弹窗净化**功能本体 + 弹窗类名候选常量 | 目标进程 |
| `core/QuickConfigStore.kt` | 「一键配置」方案的数据模型 + JSON 编解码（**两处共用**：目标进程算价、模块进程的方案设置页读写） | 两处共用 |
| `core/QuickConfigCatalog.kt` | 取值项目录（§4.2 那张表）：id / 分类 / 参数约束；解析时「认不认识」的唯一权威 | 两处共用 |
| `core/QuickConfigEngine.kt` | 取值项计算（纯函数）：BigDecimal、涨停表、区间校验，不碰网络 | 两处共用 |
| `core/QuoteModels.kt` | 行情数据模型 `QuoteSnapshot` / `QuoteBar`（独立成文件：引擎与 `feature/aistare/quote/` 四个取数源共用） | 两处共用 |
| `feature/uiclean/UiCleanFeature.kt` | 「界面精简」类功能的公共骨架（认现成视图 → 隐藏 / 压缩，走 `PageSyncFeature`） | 目标进程 |
| `feature/uiclean/UiClean.kt` | 视图树遍历 / 文案查找 / 结构判据等共用工具 | 目标进程 |
| `feature/uiclean/ViewPatcher.kt` | 改动记账（隐藏 / 压缩 / 缩放），支持一键还原（首帧屏障判据来源） | 目标进程 |
| `feature/dock/DockCleanFeature.kt` · `DockCleanTargets.kt` | **底栏隐藏「理财」项**本体 + 定位常量 | 目标进程 |
| `feature/home/HomeBannerFeature.kt` · `HomeBannerTargets.kt` | **首页隐藏横幅**本体 + 定位常量 | 目标进程 |
| `feature/market/MarketBannerFeature.kt` | **行情市场隐藏滚动横幅与快捷入口**功能本体 | 目标进程 |
| `feature/market/MarketSectorCompactFeature.kt` | **行情市场板块六项压三项**功能本体 | 目标进程 |
| `feature/market/MarketOverviewCompactFeature.kt` | **行情市场「市场概览」压矮**功能本体 | 目标进程 |
| `feature/market/MarketTargets.kt` | 行情市场三个功能**共用**的定位常量（筛选功能另有 `MarketFilterTargets.kt`） | 目标进程 |
| `feature/navbar/NavBarBlendFeature.kt` | **手势导航条同色**功能本体 | 目标进程 |
| `feature/navbar/NavBarTargets.kt` · `NavBarTint.kt` | 定位 / 调参常量 + 取色上色实现 | 目标进程 |

**模块 App 进程（`app/`）**

| 文件 | 职责 | 运行位置 |
|---|---|---|
| `app/GszxApp.kt` | `Application`：初始化配置仓库、服务监听、桌面图标状态 | 模块进程 |
| `app/FrameworkBridge.kt` | 与 LSPosed 服务通信，拿 binder | 模块进程 |
| `app/ModulePreferences.kt` | 配置仓库（REMOTE / LOCAL 双后端） | 模块进程 |
| `app/ConfigBackup.kt` | 配置备份 / 恢复（自描述 JSON） | 模块进程 |
| `app/LauncherIcon.kt` | 桌面图标开关（切 `activity-alias` 的 enabled 状态） | 模块进程 |
| `app/LocaleHelper.kt` | 界面语言（`attachBaseContext` 包装 Context） | 模块进程 |
| `app/DebugConfigProvider.kt` | adb 调试通道（仅 root/shell） | 模块进程 |

**设置界面（`ui/`）**

| 文件 | 职责 | 运行位置 |
|---|---|---|
| `ui/SettingsActivity.kt` | Activity + 主题 + Dock / 顶栏装配（路由与页面骨架拆 `Routing.kt` / `Screens.kt`） | 模块进程 |
| `ui/Routing.kt` | 路由状态机 + 返回手势接管（从 SettingsActivity 拆出） | 模块进程 |
| `ui/Screens.kt` | `HomeScaffold` / `SubScreen` 页面骨架 + 免责 / NOTICE 渲染页（从 SettingsActivity 拆出） | 模块进程 |
| `ui/Dock.kt` | 默认底栏 / 液态玻璃悬浮底栏（`LocalDockInset`） | 模块进程 |
| `ui/Glass.kt` | 液态玻璃：顶栏、色料、下滑收起状态机 | 模块进程 |
| `ui/AppDialog.kt` | 自绘弹窗：设计动机与形态说明（实现拆 AppDialogHost / Spec / Widgets） | 模块进程 |
| `ui/AppDialogHost.kt` | 自绘弹窗宿主与进出动画（从 AppDialog 拆出） | 模块进程 |
| `ui/AppDialogSpec.kt` | 弹窗 Spec（Choice / Confirm / Agreement）+ `DialogController`（从 AppDialog 拆出） | 模块进程 |
| `ui/AppDialogWidgets.kt` | 弹窗子组件（`DialogBody` / 按钮行 / 勾选行等，从 AppDialog 拆出） | 模块进程 |
| `ui/SystemBars.kt` | 系统栏沉浸（`LocalNavBarInset`） | 模块进程 |
| `ui/AppIcons.kt` | 自绘 `ImageVector` 图标（零图标库依赖） | 模块进程 |
| `ui/AboutHero.kt` | 关于页 Hero：渐变光场、负空间 mark、滚动淡出（移植自 HyperIsland，MIT） | 模块进程 |
| `ui/MarkdownText.kt` | 轻量 Markdown 渲染（免责声明正文） | 模块进程 |
| `ui/*Tab.kt` | 四个板块的页面内容 | 模块进程 |
| `ui/WatchlistDetail.kt` · `ui/StrategyQuoteDetail.kt` | 二级页（高亮颜色设置 / 滑动设置） | 模块进程 |
| `ui/QuickConfigScreen.kt` · `ui/QuickConfigEditScreen.kt` | 二级页「一键配置方案列表」+ 三级页「方案编辑」 | 模块进程 |
| `ui/AiStareSettingsScreens.kt` | 智能盯盘类功能（卡详情 / 保存行为）的二级设置页 | 模块进程 |
| `ui/PrefState.kt` · `ui/FeatureCatalog.kt` · `ui/Widgets.kt` | 配置读取、功能展示元数据、共用部件 | 模块进程 |

### 2.1 分层规则

四条硬红线 —— `core/` 与 `app/` 不得 import `io.github.libxposed.api.*`；
`feature/` 只经 `FeatureEnv` 拿框架接口；`GszxModule` 不写业务逻辑；
挂钩子必须把句柄放进 `HookBag` —— **以
[EXTENDING.md §2「四条架构红线」](EXTENDING.md#2-四条架构红线) 为权威**：
逐条理由、违反后果与自查命令都在那里，本节不重抄。

> 注意例外：`core/ConfigSource.kt` 与 `core/HookBag.kt` 是「不得 import api 类型」
> 这条红线的**已声明豁免**（见 EXTENDING.md §2.3 精确边界）——其余 core/app 文件仍一律不许。

> `core/GLog.kt` 是「不得 import api 类型」这条红线的典型示例（EXTENDING.md 没有这个例子）：
> 框架接口通过纯 Java 函数式接口 `Sink` 注入，而不是直接持有 `XposedInterface`。

---

## 3. 生命周期与调用链

### 3.1 框架回调顺序

```
进程启动
   │
   ▼
onModuleLoaded(param)                    ← 一次
   │   ├─ GLog.install { … }             把日志出口接到框架
   │   ├─ 进程不在作用域？ → detach()    告诉框架不再需要后续回调
   │   └─ 打印版本 / 框架 / API / caps
   │
   ▼
onPackageLoaded(param)                   （本模块未使用）
   │
   ▼
onPackageReady(param)                    ← classLoader 已就绪
   │   └─ install(param.classLoader)
   │        ├─ ConfigSource(xposed).open()      读首帧配置
   │        ├─ configSource.onChanged = { … }   注册变更回调
   │        └─ FeatureManager(env).installAll() 安装所有启用功能
   │
   ▼
（运行期）
   ├─ 配置变化 → 框架推送 → ConfigSource 监听 → FeatureManager.onConfigChanged（切主线程）
   └─ 目标 App 触发钩子 → 对应 Feature 处理（见 §3.2）
   │
   ▼
onHotReloading(param): Boolean           ← 允许/拒绝
   └─ return true                        本模块无自建线程/全局宿主引用，可安全热重载
   │
   ▼
onHotReloaded(param)
   ├─ featureManager.uninstallAll()       卸自己记录的句柄
   ├─ param.oldHookHandles.forEach { it.unhook() }   框架给的列表兜底
   ├─ configSource.close()
   ├─ currentAppClassLoader()            从 ActivityThread 取宿主 classLoader
   └─ install(cl)                        用新代码重装钩子
```

### 3.2 全部功能的挂载点

功能之间**互相独立**：各自挂自己的钩子、各自读同一个 `ConfigSnapshot`。
没有一个功能依赖另一个功能的运行结果。

**挂钩子改行为 / 注入视图**（共 9 个）：

| 功能 | `Feature` 实现 | 挂在哪里 | 干什么 | 关键约束 |
|---|---|---|---|---|
| **自选高亮 + 名称加粗** | `WatchlistFeature` | `BaseQuickAdapter.getView`（拦截链）+ 目标适配器 `AIStkAdapter.getView` + `Activity.onResume` | `chain.proceed()` 之后从 adapter 取出「代码.市场」，判定是否在自选，`setTextColor`（高亮）/ `setTypeface`（加粗） | **不能提前 return** —— 关开关时也要走一遍逻辑，否则 RecyclerView 复用会残留旧颜色与旧字重（见 [REVERSE-ENGINEERING §6（难点与解法）](REVERSE-ENGINEERING.md#6-关闭开关后颜色不还原)）。⚠️ **这个 Feature 承载两个开关**（`enabled` 高亮 / `watchlist_bold` 加粗）—— `FeatureManager` 里**只登记一次**，但 `isEnabled` 的 or 链必须**同时**覆盖两个位，漏一个就是「界面上有开关、底下不装钩子」的假开关（第三轮 H1 的真事） |
| **直达标准行情 + 左右滑动** | `StgDirectQuoteFeature` | `EngineAct.startEngineAct` / `startEngineActForResult` | 命中「策略详情 → 中间页」时**改写 `EngineIntent`**（换 action、补 `stockList` / `stockIndex`），再 `chain.proceed()` 交给 App 自己路由 | **绝不原地改写宿主持有的集合** —— 必须构造新列表写进 `Bundle` 副本，否则第二次点击会 `ClassCastException` 崩溃（见 [FEATURE-DIRECT-QUOTE](FEATURE-DIRECT-QUOTE.md)） |
| **多选栏全选 / 反选** | `StgSelectAllFeature` | `#tv_stg_add_zxg` 的 `TextWatcher`（观察文案进出多选态）+ 包裹宿主 `OnItemClickListener` | 在「取消」左侧注入一颗克隆按钮，点击时逐行 `ListView.performItemClick(...)` 驱动宿主自己的选中模型 | **逐行必须分帧**（`postDelayed` 一行一帧）—— 宿主每次切换后会刷新列表，同帧继续拿旧子 View 会失效 |
| **策略详情筛选股票** | `StgStockFilterFeature` | `PopupWindow.showAsDropDown` / `showAtLocation`（**前置**改写）+ `BaseAdapter.notifyDataSetChanged`（**前置**换数据）+ `getView` + `Activity.onResume` | 往三点菜单注入「筛选股票」，点开面板后**换掉适配器 `data` 字段**（真删不是藏），再包一层行点击下标回映射 | **菜单注入必须在 `proceed()` 前**（`show` 一旦量过尺寸，后加的项会被裁掉）；**换数据要在 `notifyDataSetChanged` 前置**，否则第一帧先闪一下全量（见 [FEATURE-STG-FILTER](FEATURE-STG-FILTER.md)） |
| **个股详情页底栏「智能盯盘」入口** | `StockDetailBarFeature` | `PageSyncFeature` 骨架（`Activity.onResume` + 全局布局回调 + `OnPreDraw`）+ `PopupWindow.show*` + `WindowManagerGlobal.updateViewLayout` | 在底栏「自选」与「更多」之间注入一颗按钮；点它时触发「更多」→ 拦到面板 → 按文字 `performItemClick`「智能盯盘」 | **不碰任何约束**：只把「更多」盒子加宽让买卖让位；**面板要设透明并压住宿主调暗窗口**，否则点下去闪一下黑（见 [FEATURE-STOCK-BAR](FEATURE-STOCK-BAR.md)） |
| **手势导航条同色** | `NavBarBlendFeature` | `Activity.onResume`（挂布局回调）+ `Activity.onConfigurationChanged` + `ViewTreeObserver.OnGlobalLayoutListener`（真正落地）+ `PixelCopy` 像素校准 | 把导航条区域填成「内容最后一行」的颜色；三键导航时保留强制对比度 | **取色带必须落在导航条之上**（`decor.height - navBarHeight`），否则取到自己上次填的色、自我循环（见 [FEATURE-NAV-BAR](FEATURE-NAV-BAR.md)） |
| **新股申购提醒弹窗净化** | `IpoDialogCleanFeature` | `MainAct#showSgbDialog`（申购专用入口）+ `android.app.Dialog#show`（按类名兜底） | 两道都拦下弹窗**显示**：拦展示不拦构造，对象建了只是不 `show`，宿主后续流程照旧 | **判据收紧** —— `Dialog#show` 对**所有**弹窗生效，类全名或简名任一命中才拦，宁可漏也不能误伤宿主其它对话框（见 [FEATURE-IPO-DIALOG](FEATURE-IPO-DIALOG.md)） |
| **智能盯盘价格页一键配置** | `AiStareQuickConfigFeature` | `WebView#setWebViewClient`（动态抓实际子类 `HybirdWebView$4`）→ 其 `onPageFinished` + `StockInfo` 构造器（宿主行情缓存） | URL 命中价格页 → 注入 JS（`addJavascriptInterface("gsaux")` 自建桥，不进宿主命令漏斗）→ 在「保存」右侧插「配置」按钮；点按钮 → 子线程取数（① 宿主 → ② 腾讯 → ③ 东财 → ④ 页面 DOM，按通道能力降级）→ `QuickConfigEngine` 算价 → `evaluateJavascript` 回填两个输入框并派发 input/change | **H5 页走脚本注入**（分界约定，见 [FEATURE-AISTARE-QUICK-CONFIG](FEATURE-AISTARE-QUICK-CONFIG.md) 附记）；取数失败**不写值**；套用后**不自动点保存**；关开关即时摘按钮（`init(enabled=false)`）。功能目录里的「跳过保存确认」「空数据保存」两个开关也由本功能实现（注入脚本的两个能力位），不单列功能行 |
| **我的盯盘卡片跳个股详情页** | `AiStareCardDetailFeature` | `ListView#setAdapter`（命中 `#listMyStare` / `AIStareIndexAct`）→ 动态挂 adapter `getView` + `Activity.onResume` | 给「我的盯盘」卡片（`#layoutContent`）挂点击：从 adapter item 取 `codeAndMarket`，拿不到才退回「6 位代码 + 沪/深前缀猜市场」，`startActivity(quotes.stockDetail + IntentStockInfo)` 进详情页 | **不用 position 存股票**（复用会张冠李戴），用「卡片视图 → 代码.市场」弱表；编辑 / 删除是子视图自消费点击，不碰；只认沪 / 深，其余宁可不跳（见 [FEATURE-AISTARE-CARD-DETAIL](FEATURE-AISTARE-CARD-DETAIL.md)） |

**界面精简**（其余 7 个）**不挂钩子改行为** —— 它们挂在 `Activity.onResume` + 全局布局回调里
**认现成的视图**再隐藏 / 压缩，走 `feature/uiclean/` 那套骨架，改动过 `ViewPatcher` 记账
（可一键还原）。挂载点即 `PageSyncFeature` 骨架本身：

| 功能 | `Feature` 实现 | 目标页面（`pages` 门禁） | 做什么 |
|---|---|---|---|
| **底栏隐藏理财入口** | `DockCleanFeature` | `MainAct` | 把底栏「理财」那一格 `GONE` + 其余格均分铺满 |
| **首页隐藏横幅** | `HomeBannerFeature` | `MainAct` | 隐藏首页快捷入口下方那条横幅 |
| **行情市场隐藏滚动横幅与快捷入口** | `MarketBannerFeature` | `MainAct` | 收起轮播横幅 + 快捷入口两个版块 |
| **行情市场板块显示单行** | `MarketSectorCompactFeature` | `MainAct` | 板块 3 列 × 2 行压成一行（限高） |
| **行情市场概览高度优化** | `MarketOverviewCompactFeature` | `MainAct` | 概览图表区与柱状图一起压矮 |

三条共同约定：钩子里的异常一律 `runCatching` 吞掉（绝不抛给宿主）、
所有句柄进 `HookBag`、`onConfigChanged` 必须在主线程处理。
精简类功能的调度细节（改视图时机、首帧屏障、限速、失配时打视图树快照）见
[界面精简实现方案](FEATURE-UI-CLEAN.md) 与 `feature/PageSyncFeature.kt` 类注释。

> **当前状态（16 个 Feature 实现 / 19 个开关）**：上面两张表就是全部功能的现行挂载方式 ——
> 挂钩子改行为 9 个 + 界面精简 7 个。
>
> **口径说明**：「19 个开关」是**功能目录里的行数**（`ui/FeatureCatalog.kt` 的 `FeatureSpec` 条数），
> 「16 个实现」是 `FeatureManager` 里登记的 Feature 个数 —— 两者差 3 是因为有两组开关
> 各自共用同一个 Feature 实现：
> `aistare_quick_config` / `aistare_empty_save` / `aistare_skip_save_confirm`
> 三个由 `AiStareQuickConfigFeature` 实现（不单列功能行），
> `watchlist_highlight` / `watchlist_bold` 两个由 `WatchlistFeature` 实现。2.13.0 起陆续补齐了 `stg_stock_filter`（策略详情筛选股票）、
> `stock_bar_entry`（底栏智能盯盘入口）与 2.15.0 的界面精简五个功能，2.16.0 又新增了
> `nav_bar_blend`（手势导航条同色），2.17.0 补上了 `ipo_dialog_clean`
> （新股申购提醒弹窗净化），3.1.0 补上了 `aistare_quick_config`
> （智能盯盘价格页一键配置）与 `aistare_card_detail`
> （我的盯盘卡片跳个股详情页）—— 它们都已在上表，不再是「前瞻」。
| **隐藏 指数概览** | `MarketIndexHideFeature` | `PageSyncFeature` 骨架（`Activity.onResume` + 全局布局回调 + `OnPreDraw`） | 行情页顶部那一行（`#rv_markets_hs` 的**行1**，内含 `RecyclerView #rv_index` → `#ll_content` ×4）整块收起，高度归零 + `GONE` | **收的是「行1」不是 `#rv_index`** —— 版块内边距在子项上，且 `RecyclerView` 的 `LayoutManager` 不判 `visibility`，只设 `GONE` 照样占地方（见 [FEATURE-UI-CLEAN](FEATURE-UI-CLEAN.md) §3.6） |
| **行情市场筛选涨跌与自选** | `MarketFilterFeature` | `Dialog#show`（**前置**注入，宿主自己的筛选面板）+ `PageSyncFeature` 骨架（改排行榜行） | 往宿主的「沪深京A」筛选面板里追加「涨跌与自选」三行（与策略详情页那颗面板**共用同一份状态与文案**），点确定落盘；按这三行把排行榜里不匹配的行**收起**（高度归零 + GONE） | **注入必须前置**（面板一 `show` 就量过尺寸，之后 `addView` 会被裁掉）；判定**只看视图**（`Dialog` 是框架类、全 App 都在弹）；关开关能**撤掉**注入的三行并把宿主「确定」的监听还原（规则 1.1）；**不碰**宿主自己的类型过滤那五行（见 [FEATURE-MARKET-FILTER](FEATURE-MARKET-FILTER.md)） |

## 3.3 染色主流程（自选高亮）

```
列表要显示一行
   │
   ▼
BaseQuickAdapter.getView(position, convertView, parent)      ← 被 Hook（拦截链）
   │
   ├─ chain.proceed()  ─────────────→  原方法执行，返回行视图
   │
   ├─ 目标适配器是 AIStkAdapter 吗？ ── 否 ─→ 原样返回
   │
   ├─ findViewById(R.id.tv_item_stk_name) → TextView
   │
   ├─ 从 adapter 取 position 对应的数据项（反射 + 多级兜底）
   │     └─ 取出 "代码.市场"（如 300006.1）
   │
   ├─ ZxWatchlist.isInWatchlist(code, market)
   │     ├─ 主：ZXWorkflow.isBelongByStock(code, market)
   │     └─ 兜底：查 ZXManager.m_stkGrpMap 的 "code.market" 键
   │
   ├─ env.config  ← 内存中的不可变快照（零 IO）
   │
   └─ setTextColor(在自选 ? 高亮色 : 原始色)
```

钩子代码（`feature/watchlist/WatchlistFeature.kt`）：

```kotlin
env.xposed.hook(m)
    .setId("watchlist:getView:$tag:${...}")
    .intercept { chain ->
        val result = chain.proceed()          // 先让原方法跑完
        runCatching { onGetView(env, chain.thisObject, chain.args, result) }
            .onFailure { GLog.w("getView 后处理异常", it) }   // 绝不把异常抛给宿主
        result
    }
```

> **双保险**：除了 `BaseQuickAdapter.getView`，还会尝试挂目标适配器
> `AIStkAdapter.getView`（若某版本覆写了它）。

### 3.4 配置变更后如何自动刷新

```
（模块进程）设置界面改动
   │
   └─ ModulePreferences.edit { … } → XposedService.getRemotePreferences("config")
                                        │
                                        ▼  LSPosed 守护进程
                                        │
（目标进程）                            ▼
   ConfigSource 的 OnSharedPreferenceChangeListener 触发（Binder 线程）
      │
      ├─ 重新 read() → ConfigSnapshot
      ├─ 指纹变了？
      └─ onChanged(cfg)
            │
            └─ FeatureManager.onConfigChanged(cfg)
                   ├─ env.config = cfg
                   └─ mainHandler.post { 各 Feature.onConfigChanged() }
                          │
                          └─ WatchlistFeature → refreshLists()
                                 └─ mainHandler.post { 每个 adapter.notifyDataSetChanged() }
```

两条路径互为补充：

- **配置变化** → 框架推送 → 主线程分发（用户改完设置切回 App 即生效）
- **切回 App** → `Activity.onResume` 钩子 → 兜底比对指纹并刷新

> `notifyDataSetChanged()` 用 `post()` 延后到下一帧，
> 避免 `Cannot call this method while RecyclerView is computing a layout` 崩溃。

---

## 4. 关键数据结构

### 4.1 `ConfigSnapshot` — 配置快照

```kotlin
data class ConfigSnapshot(
    val enabled: Boolean,                // 自选高亮总开关
    val watchlistBold: Boolean,          // 自选股名称加粗（与高亮是两个独立开关）
    val color: Int,                      // 高亮色（ARGB）
    val scopeStrategy: Boolean,          // 自选高亮：是否作用于策略详情页
    val logLevel: Int,                   // 日志等级（两进程共用，见 core/GLog.kt）
    val stgDirectQuote: Boolean,         // 策略详情点股票直达个股详情
    val stgQuoteSwipe: Boolean,          // 直达时是否支持左右滑动换股
    val stgSelectAll: Boolean,           // 多选栏「全选 / 反选」按钮
    val stgStockFilter: Boolean,         // 策略详情三点菜单「筛选股票」
    val stockBarEntry: Boolean,          // 个股详情页底栏「智能盯盘」入口
    val dockClean: Boolean,              // 底栏不再显示「理财」那一格
    val homeBannerHide: Boolean,         // 隐藏首页快捷入口下方的横幅
    val marketBannerHide: Boolean,       // 隐藏行情市场滚动横幅与快捷入口
    val marketSectorCompact: Boolean,    // 行情市场板块由六项压缩为三项
    val marketIndexHide: Boolean,        // 行情市场顶部的「指数概览」整块收起
    val marketOverviewCompact: Boolean,  // 行情市场「市场概览」整体压矮
    val marketStockFilter: Boolean,      // 行情市场「沪深京A」筛选面板加「涨跌与自选」三行
    val navBarBlend: Boolean,            // 手势导航条填充与紧邻界面一致的颜色
    val ipoDialogClean: Boolean,         // 新股申购提醒弹窗净化
    val aistareQuickEnabled: Boolean,    // 智能盯盘价格页「一键配置」开关
    val aiStareQuickConfig: String,      // 「一键配置」方案 JSON（结构见 QuickConfigStore）
    val aiStareSkipSaveConfirm: Boolean, // 保存成功不弹确认弹窗（轻提示 + 自动返回）
    val aiStareEmptySave: Boolean,       // 两个价都空时「保存」仍可点（= 取消盯盘）
    val aiStareCardDetail: Boolean,      // 「我的盯盘」卡片点一下直达个股详情页
    val aiStareSaveBackToStock: Boolean, // 保存成功后退到个股详情页（false = 只回上一页）
    val aiStarePickScheme: Boolean,      // 多方案时弹「选择方案」层（false = 直接套默认方案）
) {
    // 稳定指纹，只在内容变化时改变。
    // ⚠️ 是**构造期算好的字段**，不是计算型 getter —— `getView` 每行都会读它一次，
    //    而方案 JSON 可达几十 KB，`String.hashCode` 是 O(n) 全扫（规则 2.2）。
    // ⚠️ 指纹是「布尔项占低位逐个异或」，**不是加权和** ——
    //    加法里两组不同的布尔组合能凑成同一个和（如 2137+2221 == 2011+2347），
    //    撞上时 `ConfigSource` 会判定「配置没变」→ 这次变更被**静默丢弃**。
    val fingerprint: Int = run { … }
}
```

> 快照共 **26 个字段**（上面这段是全量）；`ConfigKeys.ALL_KEYS` 共 32 个键
> = 26 个进快照 + 6 个只属模块 App 进程（桌面图标 / 液态玻璃 / 玻璃光效 / 语言 / 深色模式 / 可预测返回）。
> ⚠️ **日志等级（`LOG_LEVEL`）是例外** —— 它既不在功能键也不在参数键里，
> 但**进快照**（目标进程用它控制日志等级，见 `ConfigKeys` 的类注释）。

为什么用快照而不是直接读 `SharedPreferences`：

- `getView` 是热路径，快照读取是纯内存操作，零 IO
- 配置变化时**整体替换**，天然线程安全
- 新增配置项只需改这里 + `ConfigKeys` + 设置界面
  （⚠️ 还要加进 `ConfigKeys.SNAPSHOT_KEYS` —— 否则目标进程**收不到**它的变更）

### 4.2 `WatchlistFeature.originalColors` — 原始色缓存

```kotlin
Collections.synchronizedMap(WeakHashMap<TextView, Int>())
```

记录每个名称 `TextView` **第一次见到的非高亮色**，用于还原。

为什么不能直接用"当前颜色"当原始色？因为当前颜色可能就是我们自己染的黄色。
所以只在颜色**不属于任何预设高亮色**时才写入缓存。

用 `WeakHashMap` 避免长时间持有已回收的 View。

### 4.3 `WatchlistFeature.seenAdapters` — 见过的适配器

```kotlin
Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Any, Boolean>()))
```

配置变化时需要让它们重绘，同样用弱引用避免泄漏。

### 4.4 `HookBag` — 钩子句柄集合

```kotlin
Collections.synchronizedList(mutableListOf<XposedInterface.HookHandle>())
```

API 102 与传统 API 最大的行为差异：**钩子必须持有句柄才能卸载**。
把句柄集中管理，热重载 / 关闭功能 / 进程退出时一次性 `unhookAll()`。

### 4.5 配置的三层归属

`ConfigKeys` 里的键刻意分成三类，这是「设置页只管模块自身」这条产品规则的代码落点：

| 类别 | 键 | 谁读 | 进 `ConfigSnapshot`？ | 出厂默认 |
|---|---|---|---|---|
| **功能总开关** | `FEATURE_KEYS` 全表（19 个键）：`ENABLED` / `WATCHLIST_BOLD` / `STG_DIRECT_QUOTE` / `STG_SELECT_ALL` / `STG_STOCK_FILTER` / `STOCK_BAR_ENTRY` / 界面精简六项 / `MARKET_STOCK_FILTER` / `MARKET_INDEX_HIDE` / `NAV_BAR_BLEND` / `IPO_DIALOG_CLEAN` / `AISTARE_QUICK_ENABLED` / `AISTARE_SKIP_SAVE_CONFIRM` / `AISTARE_EMPTY_SAVE` / `AISTARE_CARD_DETAIL` | 目标进程 | ✅ | **一律 `false`** |
| **功能参数** | `COLOR` / `SCOPE_STRATEGY` / `STG_QUOTE_SWIPE` / `AISTARE_QUICK_CONFIG`（方案 JSON）/ `AISTARE_SAVE_BACK_TO_STOCK` / `AISTARE_PICK_SCHEME` | 目标进程 | ✅ | 开启 / 默认值 |
| **模块设置** | `DESKTOP_ICON` / `LIQUID_GLASS` / `GLASS_EFFECT` / `LANGUAGE` / `THEME` / `PREDICTIVE_BACK` | **只有模块 App 进程** | ❌ | 按需 |

判定标准很简单：**目标进程需不需要它**。

- 需要 → 必须进快照，否则功能逻辑拿不到
- 不需要 → 留在模块进程，避免把「底栏长什么样」这类纯 UI 偏好塞进目标进程的内存

前两类对应 `ConfigKeys.FEATURE_KEYS` 与 `FEATURE_PARAM_KEYS`，
是「重置模块」的分工依据：**开关一律关掉，参数恢复默认**（见 [EXTENDING.md](EXTENDING.md) 第 4 步）。

`DESKTOP_ICON` / `LIQUID_GLASS` / `GLASS_EFFECT` / `LANGUAGE` / `THEME` / `PREDICTIVE_BACK`
都**不在** `ConfigSnapshot` 里，目标进程对它们一无所知 ——
这正是「设置页只负责本模块范围内的设置项」在架构上的体现。

> `ui/FeatureCatalog.kt` 是 `feature/` 的**镜像表**：`ui/` 不能引用 `FeatureManager`
> （红线推论），所以它用一份静态的 `FeatureSpec` 列表描述「有哪些功能、叫什么、说明是什么」。
> 加功能时要同时更新这里，见 [EXTENDING.md · 第 4 步](EXTENDING.md)。

---

## 5. 反射兜底策略

因为目标 App 被加固，**类名 / 字段名都不能硬编码假设**，所有关键名字都做成候选数组 + 多级兜底。
每个功能包各带一份 `XxxTargets.kt`（行情市场三个功能共用一份），互不干扰：

| 功能 | 候选常量文件 |
|---|---|
| 自选高亮 | `feature/watchlist/WatchlistTargets.kt` |
| 直达标准行情 + 左右滑动 | `feature/strategyquote/StgDirectQuoteTargets.kt` |
| 多选栏全选 / 反选 | `feature/strategyselect/StgSelectAllTargets.kt` |
| 策略详情筛选股票 | `feature/stgfilter/StgStockFilterTargets.kt` |
| 个股详情页底栏「智能盯盘」入口 | `feature/stockbar/StockDetailBarTargets.kt` |
| 智能盯盘价格页「一键配置」 | `feature/aistare/AiStareQuickConfigTargets.kt` |
| 我的盯盘卡片跳个股详情页 | `feature/aistare/AiStareCardDetailTargets.kt` |
| 新股申购提醒弹窗净化 | `feature/ipoclean/IpoCleanTargets.kt` |
| 底栏隐藏理财入口 | `feature/dock/DockCleanTargets.kt` |
| 首页隐藏横幅 | `feature/home/HomeBannerTargets.kt` |
| 行情市场（滚动横幅 / 板块 / 概览，三功能共用） | `feature/market/MarketTargets.kt` |
| 手势导航条同色 | `feature/navbar/NavBarTargets.kt` |

以自选高亮为例：

```kotlin
// 目标适配器候选
TARGET_ADAPTERS = arrayOf("com.guosen.quotation.adapter.aistk.AIStkAdapter")

// 名称控件 id 候选
NAME_ID_CANDIDATES = arrayOf("tv_item_stk_name")

// 数据项里「代码.市场」的字段候选
CAM_FIELDS    = arrayOf("codeAndMarket", "m_codeAndMarket")
CODE_FIELDS   = arrayOf("m_stkCode", "code", "mCode")
MARKET_FIELDS = arrayOf("m_stkMarket", "market", "mMarket")
```

取 adapter 数据项也做了多级兜底：

1. `getItem(int)`
2. `getData()` / `getItems()` / `getList()`
3. 字段 `mData` / `mDataList` / `mItems` / `data` / `mList`

`Reflect.findField()` 会**沿继承链向上查找**，兼容字段声明在父类的情况。

> 这样设计的目的：App 升级后字段改名，只需要往数组里加一个候选名即可，
> 不用重新做一遍逆向。

---

## 6. 线程模型

| 操作 | 线程 |
|---|---|
| `getView` / `onResume` 钩子回调 | 主线程（UI） |
| `chain.proceed()` | 主线程（在原调用点） |
| 读取 `env.config` | 任意（不可变快照，volatile 引用） |
| `ConfigSource` 变更监听 | **Binder 线程**（框架推送） |
| `FeatureManager.onConfigChanged` 分发 | 主线程（`Handler(Looper.getMainLooper())`） |
| `notifyDataSetChanged()` | 主线程（经 `View.post()` 调度） |
| `XposedServiceHelper` 服务回调 | Binder 线程 → `FrameworkBridge` 切主线程分发 |
| 热重载请求（`hotReloadModule`） | 独立短生命周期线程（Binder 调用不能放主线程） |

**约定**：`Feature.onConfigChanged` 保证在**主线程**被调用，
功能实现不需要自己 `post`。这是 `FeatureManager` 的职责。

---

## 7. 模块清单

模块元数据全部在 APK 内的 `META-INF/xposed/`：

**`module.prop`**

```properties
minApiVersion=102
targetApiVersion=102
staticScope=true
exceptionMode=protective
autoHotReload=true
```

**`java_init.list`**

```
io.github.MVennn.GSAuxiliary.GszxModule
```

**`scope.list`**

```
com.guosen.android
```

`AndroidManifest.xml` 里只有常规声明：

```xml
<application
    android:name=".app.GszxApp"
    android:label="@string/app_name"
    android:description="@string/xposed_description"   <!-- 模块描述 -->
    … >

    <!-- 设置界面本体：MAIN + MODULE_SETTINGS（LSPosed 的「打开设置」入口），不带 LAUNCHER -->
    <activity
        android:name=".ui.SettingsActivity"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="de.robv.android.xposed.category.MODULE_SETTINGS" />
        </intent-filter>
    </activity>

    <!-- 桌面入口别名：开关只切它的 enabled 状态 -->
    <activity-alias
        android:name=".ui.LauncherAlias"
        android:targetActivity=".ui.SettingsActivity"
        android:enabled="true"
        android:exported="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity-alias>

    <!-- 仅 adb 调试用，不参与运行期配置读取 -->
    <provider
        android:name=".app.DebugConfigProvider"
        android:authorities="io.github.MVennn.GSAuxiliary.config"
        android:exported="true" />
</application>
```

> **桌面图标开关**为什么用 `activity-alias`：Activity 自身无法"隐藏自己"，
> 但别名可以。`LauncherIcon.apply()` 对 `.ui.LauncherAlias` 调
> `setComponentEnabledSetting(COMPONENT_ENABLED_STATE_DISABLED / DEFAULT)`，
> 关闭后桌面图标消失、模块仍然正常工作（LSPosed 通过 `java_init.list` 加载，不依赖桌面入口）。

> ⚠️ **不要**加 `xposedminversion` 之类的 legacy meta-data —— 会让模块被判定为
> legacy 模块从而静默不加载。原因见 [EXTENDING.md · 架构红线](EXTENDING.md#21-manifest-里绝对不能出现-xposedminversion)。
