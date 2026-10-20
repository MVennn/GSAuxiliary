# 排错手册

按「现象 → 排查步骤 → 原因/解法」组织。

**第一步永远是看 LSPosed 日志。**

---

## 0. 先学会看日志

三条通道，按顺手程度选：

**① 模块内直接导出（不需要 adb，推荐给用户）**

设置 → 日志 → 导出日志。它会把**两个进程**的日志拼成一份 txt 存到你选的位置。
目标进程那段要读 LSPosed 的 `/data/adb/lspd/log/`，会弹一次 root 授权；
没授权也照样出文件，那一节会写明原因。想更啰嗦的日志，先把「日志等级」调到**调试**，
复现一次问题再导出（等级对两个进程都生效，不用重启目标 App）。

**② adb 看 LSPosed 日志（目标进程在这里，实时）**

```bash
adb shell su -c "grep GSZXEnhance /data/adb/lspd/log/modules_*.log | tail -30"
```

**③ adb 看模块 App 自己进程的日志（在 logcat 里）**

```bash
adb logcat -s GSZXEnhance
```

> 这是最容易浪费时间的误区：`logcat -s GSZXEnhance` 看不到目标进程的日志，
> 因为 `XposedInterface.log()` 被 LSPosed 收进了自己的日志文件。

正常加载时应该看到（样例取自 2.16.0，当时 **11 个功能全开**；2.17.0 起新增
新股弹窗净化，功能共 **12** 个，各段条数相应多一行）。每个启用功能的
钩子日志先打，随后才是 `FeatureManager` 的「已安装功能 [id] 标题」那一行：

```
GSZXEnhance: === GSAuxiliary v2.16.0 载入 === process=com.guosen.android pid=12345 framework=LSPosed 2.2.1 api=102 caps=0x…
GSZXEnhance: 配置通道 = remote preferences -> ConfigSnapshot(enabled=true, color=-32768, scopeStrategy=true, logLevel=2, stgDirectQuote=true, stgQuoteSwipe=true, stgSelectAll=true, stgStockFilter=true, stockBarEntry=true, dockClean=true, homeBannerHide=true, marketBannerHide=true, marketSectorCompact=true, marketOverviewCompact=true, navBarBlend=true)
GSZXEnhance: 已挂 com.guosen.commonui.adapter.BaseQuickAdapter.getView ×1
GSZXEnhance: 已挂 com.guosen.quotation.adapter.aistk.AIStkAdapter.getView ×1
GSZXEnhance: 已挂 android.app.Activity.onResume
GSZXEnhance: 已安装功能 [watchlist_highlight] 自选股高亮
GSZXEnhance: 策略直达标准行情：已挂 2 个引擎跳转点
GSZXEnhance: 已安装功能 [stg_direct_quote] 策略详情直达标准行情
GSZXEnhance: 策略全选：已挂 1 个 Activity.onResume
GSZXEnhance: 已安装功能 [stg_select_all] 策略详情页多选全选
…（其余功能各打一条「已安装功能 [id] 标题」+ 自己的挂载日志）…
GSZXEnhance: 功能安装完成：11/11，共 N 个钩子
```

> ⚠️ **功能开关出厂默认全是关的**（2.11.2 起的硬约定）。
> 全新安装、还没打开任何开关时，上面这几行是**这样的**：
>
> ```
> GSZXEnhance: 跳过功能 [watchlist_highlight]：当前配置下未启用
> GSZXEnhance: 跳过功能 [stg_direct_quote]：当前配置下未启用
> …（每个功能各一条，id / 顺序同上一段）…
> GSZXEnhance: 功能安装完成：0/11，共 0 个钩子
> ```
>
> `0/11` **不是故障**（数字随版本走，当前 3.1.0 全新安装是 `0/14`——安装计数按
> 功能类算，「功能」页的 16 张卡片里「跳过保存确认」「空数据保存」两个开关由
> 一键配置功能一并实现，不占安装位）。先去设置界面「功能」页把要用的开关打开。

| 缺哪一行 | 说明 |
|---|---|
| 第 1 行 | 模块没被注入 → 检查 LSPosed 是否勾选模块、作用域是否包含国信金太阳 |
| 「配置通道」 | 框架不支持 remote 能力，或 remote prefs 打不开（见第 3 节） |
| 「已挂 …getView」 | 宿主类名变了 → 用 `tools/probe/` 重新定位 |
| 「已安装功能」 | 该功能开关是关的（正常现象，见上方警告） |
| 「已挂 N 个引擎跳转点」/「已挂 Activity.onResume」 | 对应功能的宿主类名变了 → 见第 6 节 |

---

## 1. 模块完全没生效

**排查**

```bash
# 1) 确认模块被 LSPosed 认识
adb shell su -c "grep -i gszx /data/adb/lspd/log/modules_*.log | head -5"

# 2) 确认配置写进去了（adb 调试通道，仅 root/shell 可用）
adb shell su -c "content query --uri content://io.github.MVennn.GSAuxiliary.config/config"
```

也可以直接看设置界面「**概览**」页：顶部状态卡会显示**框架连接状态**，一眼能看出问题。
（该页 2.6.0 起由「模块」改名「概览」；页面上**不再单独列「配置通道」行**，
排障关心的是日志 —— 模块内只提供「设置 → 日志 → 导出日志」，看日志按上面三条通道走。）

**常见原因**

| 原因 | 解法 |
|---|---|
| LSPosed 里没勾选模块 | 勾选后**强制停止**目标 App 再打开 |
| LSPosed 版本太旧（不支持 API 102） | 升级 LSPosed |
| 作用域没勾「国信金太阳」 | 模块已固定声明作用域，正常会自动勾；异常时手动勾 |
| 装了新版本但没重启 App | 首次启用必须重启目标 App（之后升级模块可热重载） |
| 换了签名 | 卸载重装，并在 LSPosed 里重新勾选 |
| App 升级后类名变了 | 见 [第 6 节](#6-app-升级后失效) |

---

## 2. 模块加载了，但策略详情页没有黄名

**排查顺序**

1. 确认当前确实在**策略详情页**（`AIStkStgDetailAct`）：
   ```bash
   bash tools/drive.sh "act"
   ```
2. 确认开关是开的：
   ```bash
   bash tools/sh.sh "content query --uri content://io.github.MVennn.GSAuxiliary.config/config"
   # enabled=1 才对
   ```
3. 用自动校验脚本看实际颜色：
   ```bash
   python tools/verify.py
   ```

**可能原因**

| 现象 | 原因 | 解法 |
|---|---|---|
| 全部普通色，`enabled=0` | 开关被关了 | 打开设置界面重新开启 |
| 全部普通色，`enabled=1` | 数据项字段名变了，取不到股票代码 | 往 `WatchlistTargets.CAM_FIELDS` 等数组里加候选字段名 |
| 全部普通色 | 适配器类名变了 | 用探针重新定位，改 `WatchlistTargets.TARGET_ADAPTERS` |
| 全部普通色，日志里有「没有任何钩子装上」 | `BaseQuickAdapter` 类找不到 | 用探针确认类名，改 `WatchlistTargets.CLS_BASE_QUICK_ADAPTER` |
| 只有部分行变色 | 列表没重绘 | 滚动一下，或退出重进页面 |
| 名称找不到 | 控件 id 变了 | 改 `WatchlistTargets.NAME_ID_CANDIDATES` |

### 2.1 直达标准行情不生效（点股票还是进中间页）

先看日志里有没有这两行：

```
已安装功能 [stg_direct_quote] 策略详情直达标准行情
策略直达标准行情：已挂 2 个引擎跳转点
```

| 现象 | 原因 | 解法 |
|---|---|---|
| 日志里是「找不到 `com.guosen.engine.process.EngineAct`，功能不会生效」 | 引擎基类改名 | 用探针定位后改 `StgDirectQuoteTargets.CLS_ENGINE_ACT` |
| 日志里是「没有挂上任何钩子」 | `startEngineAct*` 方法改名了 | 往 `StgDirectQuoteTargets.LAUNCH_METHODS` 里加候选方法名 |
| 有「已改写成 `quotes.stockDetail`」但界面没动 | 改对了但被 App 覆盖 | 看有没有 `EngineIntent 改写异常` 告警 |
| 日志里是「没有 `adapter_key`，放弃改写」 | 策略页的 extra key 变了 | 改 `StgDirectQuoteTargets.KEY_ADAPTER` / `KEY_STOCK_LIST` / `KEY_STOCK_ITEM` |
| 直达正常，但**不能左右滑动** | 滑动开关被关了，或 `stockList` 写入失败 | 先去「功能 → 直达标准行情 → 滑动设置」确认开着；日志里若见「列表不可用，退化为单只」则说明数据项结构变了 |

> 完整链路、Intent 契约与降级链见 [FEATURE-DIRECT-QUOTE.md](FEATURE-DIRECT-QUOTE.md)。

### 2.2 多选栏里没有「全选」按钮

| 现象 | 原因 | 解法 |
|---|---|---|
| 日志里是「找不到 `…tv_stg_add_zxg…`」 | 多选按钮的控件 id 变了 | 改 `StgSelectAllTargets.CANCEL_BUTTON_ID_CANDIDATES` |
| 日志里是「没挂上 `Activity.onResume`」 | 框架没给 `android.app.Activity` | 确认框架工作正常（这通常与其它功能同时失效） |
| 进了多选态但按钮没出现 | 注入被布局挤掉 / 插入位置变了 | 看有没有「插入按钮失败」；确认 `CANCEL_BUTTON_ID_CANDIDATES` 找到的是**那个**「取消」 |
| 按钮出现了，但点「全选」只勾中一部分 | 宿主切换后会刷新列表 | 这是已修过的点（必须分帧 `postDelayed`）；若复现，检查 `StgSelectAllFeature` 的逐行调度 |
| 按钮出现**两个** | 重复注入 | 退出多选态再进一次应只剩一个；持续复现则检查 `onResume` 的注入守卫 |

---

## 3. 设置里改了开关，但 App 里没反应

配置走框架的 remote preferences，正常情况下是**实时推送**的。
没反应基本只有两类原因。

**排查**

```bash
# 1) 看配置写没写进去
bash tools/sh.sh "content query --uri content://io.github.MVennn.GSAuxiliary.config/config"

# 2) 看目标进程的配置通道状态
bash tools/sh.sh --root "grep '配置通道' /data/adb/lspd/log/modules_*.log | tail -3"

# 3) 看有没有收到推送
bash tools/sh.sh --root "grep '配置已更新' /data/adb/lspd/log/modules_*.log | tail -3"
```

### 原因 A：框架未连接（配置后端 = LOCAL）

设置界面「**概览**」页的顶部状态卡会显示「未激活 / 未连接到 LSPosed 框架」。

含义：模块 App 拿不到 LSPosed 服务的 binder，改动只写在本地，
**目标进程看不到**。

| 检查 | 解法 |
|---|---|
| LSPosed 里没勾选模块 | 勾选（勾选后框架才会 bind 服务） |
| 框架版本太旧 | 升级 LSPosed |
| 服务绑定失败 | 看 logcat 里 `注册框架服务监听失败` 的堆栈 |

> 框架一连上，`ModulePreferences` 会**自动把本地暂存的值一次性迁移到远端**，
> 用户不会丢配置。日志里会出现 `已把本地暂存的 N 项配置迁移到 remote preferences`。

### 原因 B：框架声明了 remote 能力但打不开

日志里会出现：

```
GSZXEnhance: 配置通道 = remote preferences 不可用: …
```

或

```
GSZXEnhance: 框架未声明 remote preferences 能力 (PROP_CAP_REMOTE=0)
```

| 原因 | 解法 |
|---|---|
| LSPosed 版本太旧，不提供 remote preferences | 升级 LSPosed（需要支持 API 102） |
| 模块被识别成 legacy 模块 | 删掉 manifest 里的 `xposedminversion`（见第 7 节） |

> 通道打不开会**明确记日志**，不会静默失败 —— 这也是设置界面要显示
> 「配置通道」的原因。

---

## 4. 关掉开关后，已经变黄的行还是黄的

**现象一句话**：关掉开关，已经渲染过的行仍然是黄的。

机理（`if (!cfg.enabled) return` 导致跳过 + RecyclerView 复用视图残留颜色）与解法
（无论开关开还是关都走一遍染色逻辑，关闭时用原始色覆盖）已在
[REVERSE-ENGINEERING.md §6（难点与解法）](REVERSE-ENGINEERING.md#6-关闭开关后颜色不还原) 讲透，这里不重抄。仍复现时按序排查：

1. 滚动一下列表，或退出重进页面（触发重新绑定）
2. 确认 `enabled=0` 真的写进去了（见 [第 3 节](#3-设置里改了开关但-app-里没反应)）

---

## 5. 校验脚本报错

| 报错 | 原因 | 解法 |
|---|---|---|
| `找不到 adb` | 没探测到 SDK | 设 `ANDROID_SDK` 或 `ADB` 环境变量 |
| `找不到 ffmpeg` | ffmpeg 不在 PATH | 设 `FFMPEG` 环境变量 |
| `没解析到股票行` | 当前不在策略详情页 | 先导航过去 |
| `ffmpeg 输出不足` | 截图尺寸判断错误 | 用 `--size` 指定，或检查 `wm size` |

`tools/verify.py` 会自动从 `ANDROID_SDK` / `ANDROID_HOME` / `LOCALAPPDATA` 探测 adb，
并从 PATH 探测 ffmpeg，一般不需要手动配置。

---

## 6. App 升级后失效

按这个顺序改，**大概率只需要改一个常量**。
每个功能各自带一份候选常量文件（总表原在 reference/，已转本地留存，不再随仓库分发），
哪一列出问题就改哪一份：

| 步骤 | 检查什么 | 改哪里 |
|---|---|---|
| 1 | 看 LSPosed 日志，钩子有没有装上（见 [第 0 节](#0-先学会看日志)） | — |
| 2 | **自选高亮**：适配器类名 / 名称控件 id / 代码市场字段 / 自选判定 API | `feature/watchlist/WatchlistTargets.kt`（`TARGET_ADAPTERS`、`CLS_BASE_QUICK_ADAPTER`、`NAME_ID_CANDIDATES`、`CAM_FIELDS`、`CODE_FIELDS`、`MARKET_FIELDS`）+ `feature/watchlist/ZxWatchlist.kt`（兜底是直接查 `ZXManager.m_stkGrpMap`） |
| 3 | **直达标准行情**：引擎基类 / action / extra key | `feature/strategyquote/StgDirectQuoteTargets.kt`（见 [FEATURE-DIRECT-QUOTE.md](FEATURE-DIRECT-QUOTE.md)） |
| 4 | **多选全选**：多选按钮 id / 列表 id / 复选框 id | `feature/strategyselect/StgSelectAllTargets.kt`（见 [FEATURE-SELECT-ALL.md](FEATURE-SELECT-ALL.md)） |
| 5 | **策略详情筛选股票**：三点菜单 / 筛选面板 id | `feature/stgfilter/StgStockFilterTargets.kt`（见 [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md)） |
| 6 | **底栏「智能盯盘」入口**：底栏 / 弹层面板 id | `feature/stockbar/StockDetailBarTargets.kt`（见 [FEATURE-STOCK-BAR.md](FEATURE-STOCK-BAR.md)） |
| 7 | **界面精简五个**（底栏 / 首页 / 行情市场）：各版块的资源 id | `feature/dock/DockCleanTargets.kt`、`feature/home/HomeBannerTargets.kt`、`feature/market/MarketTargets.kt`（骨架与真机校准见 [FEATURE-UI-CLEAN.md](FEATURE-UI-CLEAN.md)） |
| 8 | **手势导航条同色**：取色带 / 调参常量 | `feature/navbar/NavBarTargets.kt`（见 [FEATURE-NAV-BAR.md](FEATURE-NAV-BAR.md)） |
| 9 | **新股申购提醒弹窗净化**：宿主弹窗链路 / 类名 / 方法名 | `feature/ipoclean/IpoCleanTargets.kt`（见 [FEATURE-IPO-DIALOG.md](FEATURE-IPO-DIALOG.md)） |

**重新定位类名的方法**：用 `tools/probe/` 里的运行时探针。

```bash
bash tools/probe/build.sh
adb install -r tools/probe/out/GoldSunProbe.apk
# 在 LSPosed 里启用探针，作用域勾国信金太阳
# 然后正常操作 App，探针会把 adapter 类名 / item 字段 / 控件 id / 跳转 Intent 全打到 LSPosed 日志
```

探针现在挂的不只是页面链路，还有一整套**通用**钩子（`LAUNCH` / `DLG` / `POP` /
`PAGE` / `PAGER` / `CLASS`），用来摸清「点一下到底发生了什么」——
定位 `IntentStockInfo(StockInfo)` 这类构造器就靠它。

详见 [tools/probe/README.md](../tools/probe/README.md)。

---

## 7. 构建 / 加载失败

### 7.1 构建报错

| 报错 | 原因 | 解法 |
|---|---|---|
| `requires AGP 9.1.0 or higher` | Miuix 版本太高 | 用 0.9.3，别升 |
| `plugin is no longer required since AGP 9.0` | AGP 9.x 内置 Kotlin | 用 AGP 8.10.1 |
| `Failed to find target android-37` | platform 装了但**元数据是 schema v4**，AGP 8.10.1 直接跳过 | 见 [BUILD.md §4.1](BUILD.md#41-compilesdk-37-的平台agp-只认-schema-v3-的元数据) |
| `Could not find method ...` | Gradle 版本太低 | 用仓库自带 Wrapper `./gradlew` |
| `Error parsing AndroidManifest.xml` | XML 注释里出现了 `--`（非法） | 注释里不要写 `--` |

### 7.2 模块被 LSPosed 拒绝加载

| 报错 / 现象 | 原因 | 解法 |
|---|---|---|
| 模块完全不加载，日志里连第 1 行都没有 | manifest 里残留 `xposedminversion` | **删掉全部 `xposed*` meta-data**（见 [EXTENDING.md](EXTENDING.md#21-manifest-里绝对不能出现-xposedminversion)） |
| `The Xposed API classes are compiled into the module's APK` | `api` 依赖写成了 `implementation` | 改回 `compileOnly`（见 [BUILD.md §3.1](BUILD.md#31-xposed-api-用-maven-依赖libxposed-api-102)） |
| `NoClassDefFoundError: io.github.libxposed.api.…`（模块 App 进程） | `core/` 或 `app/` 里有人 import 了 api 类型 | 用 `GLog.Sink` 那样的函数式接口解耦 |
| 模块加载了但钩子全没装上 | 现代模块不能调 legacy API | 确认用的是 `hook(Executable).intercept(Chain)` |

**自查怎么做**：manifest 的 legacy key 扫描、dex 里 api 类的计数，两条命令都在
[BUILD.md §2「构建后的自查」](BUILD.md#2-依赖版本组合重要) 与
[§3.1](BUILD.md#31-xposed-api-用-maven-依赖libxposed-api-102) 里 —— 那里是唯一真源，这里不重抄。

> ⚠️ 两条容易踩的**误报**，记在这里就够：
>
> - **别用 `grep -i xposed`**：`android:description="@string/xposed_description"`（字符串资源名）
>   与 `de.robv.android.xposed.category.MODULE_SETTINGS`（LSPosed 靠它找设置入口）
>   都是**合法且必需**的。真正判 legacy 的 key 是 **`xposedminversion`**（无下划线）。
> - **别用 `strings` 数 api 类**：模块自己的类里有 `io/github/libxposed/api/...` 的
>   字符串常量引用，会误报。必须看 dexdump 的 `Class descriptor`。

---

## 8. 热重载相关

模块声明了 `autoHotReload=true`，升级模块 APK 后目标 App 无需重启。

| 现象（点击后弹出的 Toast） | 原因 | 解法 |
|---|---|---|
| 热重载后钩子失效 | `onHotReloaded` 里拿不到宿主 classLoader | 日志会出现「热重载后拿不到宿主 classLoader」→ 重启目标 App |
| 「没有正在运行的目标程序」 | 金太阳没在运行（`runningTargets` 为空） | 先打开一次国信金太阳 |
| 「热重载超时，目标进程可能无响应」 | 8 秒内框架没回 `onHotReloadResult` | 检查 LSPosed 日志，或直接重启目标 App |
| 「热重载失败，详见模块日志」 | 模块 `onHotReloading` 返回 false，或框架侧抛异常 | 看模块日志里那条 `热重载 <进程>(pid)…` 的 `message` |
| 「当前框架不支持热重载」 | 框架 API < 102 或未提供该能力 | 升级 LSPosed / 框架 |
| 「目标进程正在热重载中，请稍候」 | 上一次热重载还没结束就再点了一次 | 等它跑完 |
| 「目标进程已退出」 | 重载过程中金太阳进程被杀 | 重新打开金太阳（模块会重新注入） |

> 2.12.1 起**点一次必有一条 Toast**（成功也会说「已热重载」）。此前只有「没有正在运行的目标程序」
> 会提示，目标进程正常或处于后台时点下去毫无反馈。**2.18.0 移除手动触发入口**后
> 这些 Toast 不再出现；热重载只在模块 APK 升级时由框架自动触发，失败信息看 LSPosed 日志。

### 框架太旧：静默不加载的防线

模块要求**较新的 LSPosed**（现代 Xposed API 102 + 热重载能力）。过旧的 LSPosed 版本、
以及太极 / LSPatch / EdXposed 等其它 Xposed 实现，会**静默不加载**——
模块在管理器里显示已启用，但零日志、零生效（连 [第 0 节](#0-先学会看日志)样例的第 1 行都没有，
与「没勾选模块」的表现很像，先排除后者再怀疑框架版本）。

判断框架新旧，看 LSPosed 日志里模块的「载入」行（`onModuleLoaded` 打印，样例见第 0 节）：

```
=== GSAuxiliary v3.1.0 载入 === process=… framework=LSPosed x.y.z api=102 caps=0x…
```

`api=` 后面的数字就是框架实现的 API 版本，**低于 102 即框架过旧**，升级 LSPosed 后重试；
若连「载入」行都搜不到且已确认勾选模块，多半也是框架不支持现代 API，同样升级解决。

---

## 9. 设备相关（自动化脚本）

| 现象 | 原因 | 解法 |
|---|---|---|
| `no devices/emulators found` | adb server 冷启动后无线设备还没被发现 | 脚本自带 60 秒重试；也可设 `ADB_WIFI_TARGET` |
| 命令报 `device not found` | 中途 adb server 被重启 | 不要并发跑多个 adb 命令 |
| `su -c` 命令被截断 | 内层命令里含单引号 | `--root` 后的命令里不要用单引号，管道放到宿主机侧 |
| `content query` 报 `SecurityException` | adb 调试通道只允许 root/shell | 用 `su -c` 执行 |

---

## 10. 设备息屏导致脚本点不动

自动化脚本跑长序列时，设备可能息屏。开头加：

```bash
bash tools/drive.sh "key 224" "wait 2" "swipe 540 2000 540 900 250" "wait 3" "launch" "wait 12"
```

`key 224` 是 `KEYCODE_WAKEUP`，后面那次 swipe 是上滑解锁。
