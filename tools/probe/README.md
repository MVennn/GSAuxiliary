# GoldSun Probe — 运行时探针模块

一个**独立的、一次性的** LSPosed 模块，只做一件事：在目标 App 运行起来之后，把它的**内存态**掏出来看。

它不是 GSAuxiliary 的一部分，也不会被最终用户安装。它是**维护者的工具**——当国信金太阳升级、加固变严、类名混淆变动，导致 `GSAuxiliary` 的 Hook 失效时，用这个探针重新把"该 Hook 什么、怎么判自选"重新摸清楚。

---

## 为什么需要它

目标 App 用了**梆梆企业版加固（SecNeo）**，DEX 文件被加密+VM 化。用 jadx / apktool 静态反编译，拿到的是一堆 `a.a.a` 这种名字，看不到任何有意义的业务代码——**静态分析这条路是死的**。

但加固只保护**磁盘上的静态文件**。App 一旦跑起来，DEX 已经被解密并加载进内存，此时通过 Xposed 反射拿到的类名、方法名、字段名**全是明文**。

所以策略是：**放弃静态，改在运行时探针。**

> 详细分析过程见 [`../../docs/REVERSE-ENGINEERING.md`](../../docs/REVERSE-ENGINEERING.md)。

---

## 它具体做了什么

`src/com/wb/probe/ProbeModule.java`，继承 `io.github.libxposed.api.XposedModule`（**libxposed API 102**），注入 `com.guosen.android` 进程后挂下面几组钩子（**v18** = 自选判定，**v19～v25** = 跳转链与 Intent 契约 / 类结构 dump，**v30/v31** = 登录态与存储写入侦察，**v33～v35** = WebView 桥侦察）：

### 1. 页面生命周期 — 知道"现在在哪个页面"

```
hookAllMethods(android.app.Activity, "onResume")
  └─ afterHookedMethod
       ├─ 类名以 com.guosen 开头 → 打印 ACTIVITY > com.guosen.android.xxx.YyyAct
       ├─ 类名以 .MainAct 结尾        → 延迟 6s / 16s 各拍一次快照（行情页）
       └─ 类名以 .AIStkStgDetailAct 结尾 → 延迟 2.5s 拍一次快照（策略详情页）
```

这一步的作用是**把日志和页面锚定起来**——日志里出现 `SNAP[STG]` 就知道这是策略详情页的数据，出现 `SNAP[MAIN]` 就是行情页。

> 为什么要延迟？因为 `onResume` 返回时列表还没渲染完，数据源可能还是空的。

### 2. 自选数据源 — 知道"哪些股票在自选里"

拿到 `ZXManager` 单例（`ZXManager.instance()`），然后：

| 动作 | 目标 | 目的 |
|---|---|---|
| `dumpFields` | `ZXManager` 实例的全部非静态字段 | 看清内部数据结构 |
| `dumpWatchlist` | `m_zxMap` / `m_stkGrpMap` / `m_groupInfoDatas` | 自选核心 Map |
| `dumpStaticArray` | `ZXWorkflow.getAllStock()` | 全部自选（扁平） |
| `dumpStockDataArray` | `ZXWorkflow.getZXDefaultGroupStocks()` / `getStocksByGroupId(id)` | 按分组拿自选 |
| `dumpGroupDatas` | `ZXWorkflow.getAllGroups()` | 分组元信息 |
| **`crossCheck`** | 5 种候选判定 API 打分 | ★ 见下 |

### 3. 交叉验证 — 这是探针存在的真正理由

`crossCheck()` 是核心。它内置了 **20 只股票在行情页的 `isZx` 真值样本**（`HQ_CAM` / `HQ_TRUTH` 两个常量数组），然后对 5 种候选判定方式逐一打分：

| 候选 | 调用 |
|---|---|
| A | `ZXWorkflow.isBelongByStock(code, market)` — 静态，2 参 |
| B | `ZXManager.isBelongToAnyGroupByStock(code, market)` — 实例，2 参 |
| C | `ZXManager.isBelongToGroupByStock(code, market, groupId)` — 实例，3 参 |
| D | `ZXWorkflow.getAllStock()` 结果里是否包含该股 |
| E | `ZXWorkflow.getZXDefaultGroupStocks()` 结果里是否包含该股 |

对每只股票打印一行对照表，最后打印命中数。**输出格式**如下
（数值是示意，真实结果以真机跑出来的为准；真值样本 `HQ_TRUTH` 里
20 只只有 2 只为 `true`，其余 18 只为 `false`）：

```
    cam        truth | A:isBelong | B:anyGroup | C:grpDefault | D:inAll | E:inDef
    300338.1  f     | f          | f         | f            | f      | f
    ...
    300006.1  T     | T          | T         | T            | T      | T
    ------------------------------------------------------------
    命中: A=__/20  B=__/20  C=__/20  D=__/20  E=__/20
```

**命中数最高、且与行情页真值完全一致的那个候选，就是可以放心用的判定 API。**
若某项打出 `ERR`，说明该 API 在那个线程上取不到数据（例如
`ZXManager.instance()` 返回 `null`）—— 这种候选直接排除。

> 为什么必须这么验？因为探针 v16 版直接用了 `isBelongByStock`，在策略详情页 10/11 判 true，看起来"能用"；但拿行情页 20 只真值一比对，发现它语义有偏差。**不交叉验证就不知道哪个 API 是真的对。**
>
> 最终结论：**`ZXWorkflow.isBelongByStock(code, market)` 静态 2 参版本 20/20 全对**，被 `feature/watchlist/ZxWatchlist.kt` 采用。详见 [`../../docs/REVERSE-ENGINEERING.md`](../../docs/REVERSE-ENGINEERING.md)（原 HOOK-NOTES 已并入）。

### 4. 跳转链与 Intent 契约（v19～v25 新增）

服务新功能 **「策略详情页点击股票直达标准行情 + 左右滑动」**（方案见
[`../../docs/FEATURE-DIRECT-QUOTE.md`](../../docs/FEATURE-DIRECT-QUOTE.md)）。
加固 App 只能靠运行时才确定「点一下到底发生了什么」，所以 v19 全部挂**框架类**，
不需要预先知道任何业务类名：

| 挂钩点 | 日志前缀 | 拿到什么 |
|---|---|---|
| `Activity.startActivityForResult` / `startActivity` | `LAUNCH` | 每一次页面跳转的 Intent：component / action / flags / **全部 extras（key + 类型 + 值）** + 发起页面类名 + 精简调用栈 |
| `Dialog.show` | `DLG` | 中间 UI 是 Dialog → 打印其视图树的控件 id 与监听器 |
| `PopupWindow.showAsDropDown` / `showAtLocation` | `POP` | 中间 UI 是 PopupWindow → 同上 |
| `Activity.onCreate` / `onNewIntent`（筛 `StockFragAct` / `AIStkStgDetailAct`） | `PAGE[QUOTE]` / `PAGE[STG]` | 该页 Intent + Activity 实例字段 |
| 延迟 dump 页内视图树 | `PAGER` / `RV` / `VW` | `ViewPager` / `ViewPager2` / `RecyclerView` 的 adapter 类名、item 数、当前页；以及**每个可点击控件的监听器类名** |
| `dumpClassMembers(类名)`（启动时） | `CLASS` | 任意类的**构造器签名 / 实例字段 / 方法签名**。加固 App 里字段与构造器不能靠猜 —— 找 `IntentStockInfo(StockInfo)` 这种"自带转换构造器"就靠它 |

**判读方法**：把「行情页点股票」与「策略页点股票」两次 `LAUNCH` 的 extras 逐 key diff，
**只在行情侧出现的那个 key，就是承载「翻页股票列表」的上下文**——它的名字与类型直接
决定新功能能不能实现左右滑动。

> 这套钩子全是框架类的通用方法，**换任何 App 都能用**，不绑定国信。

### 5. WebView 桥侦察（v33～v35 新增）

服务「智能盯盘 → 价格页一键配置」方案
（[`../../docs/FEATURE-AISTARE-QUICK-CONFIG.md`](../../docs/FEATURE-AISTARE-QUICK-CONFIG.md) §10）：

| 挂钩点 | 日志前缀 | 拿到什么 |
|---|---|---|
| `WebView#setWebViewClient` | `WVB-CLIENT` | 宿主实际挂的 **WebViewClient 子类**（`HybirdWebView$4`）+ 其全部声明方法 |
| （动态挂子类回调） | `WVB-CB` | `onPageStarted` / `onPageFinished` 等的实际触发与 URL |
| `WebView#loadUrl` | `WVB-LOADURL` | 每次整页导航的 URL + 调用栈 |
| `WebView#addJavascriptInterface` / `removeJavascriptInterface` | `WVB-JSIFACE` | 宿主注册/清理了哪些 JS 桥 |
| `GsBridge#parserCommand` / `HybirdWebView#parserCommand` / `AIStareWebView#parserMsg` | `WVB-CMD` | H5 命令漏斗的**命令原文**（`guosen://WebContainer/...`） |
| `dumpClassMembers`（类成员全名 dump，v35 起 com.guosen 字段类型打完整包名） | `CLASS` | `HybirdWebView` / `KXChartInfo` / `KXChartItem` 等，找「拿 WebView 的方法」「K 线数据结构」 |

> v35 的一个关键改动：`CLASS` dump 里 `com.guosen.*` 类型的字段**打完整包名**——
> KXChartInfo 的真实包名 `com.guosen.android.quotes.data.kx` 就是这么找到的
> （字段可见但包名未知时，从持有者的字段声明反查）。

### 输出到哪

全部走框架的 `XposedInterface.log(Log.ERROR, "GSRecon", …)`，TAG = **`GSRecon`**。
（v17 之前用的是 `android.util.Log`，落点其实一样。）

⚠️ **这些日志不会出现在 `adb logcat` 里**（LSPosed 会把模块日志单独收集）。要读日志请走 LSPosed 的模块日志文件：

```bash
adb shell su -c 'cat /data/adb/lspd/log/modules_*.log'
```

或用本仓库的 `tools/sh.sh --root`。

---

## 构建

**不走 Gradle**——探针是个单文件模块，手工构建更快更可控。

```bash
bash tools/probe/build.sh
```

流程：

```
拉取 libxposed api aar → javac → jar → d8 → aapt2
  → 打包 dex + META-INF/xposed/ → zipalign → apksigner
```

**产物：`tools/probe/out/GoldSunProbe.apk`**（约 17 KB）

### 依赖

| 依赖 | 说明 |
|---|---|
| `ANDROID_SDK` | 自动探测（`ANDROID_SDK` / `ANDROID_HOME` / `%LOCALAPPDATA%\Android\Sdk` / `~/Library/Android/sdk` …） |
| `JAVA_HOME` | 自动探测 JDK；脚本会把它转成 Windows 形式再导出。**需要 JDK 17+** |
| `PYTHON` | 用于 `pack_dex.py`（把 dex 和 `META-INF/xposed/` 塞进 apk） |
| `io.github.libxposed:api:102.0.0` | 现代 Xposed API。**脚本自动从 Maven 下载 aar 并抽出 classes.jar**，无需手工准备 |

> 探针与主模块使用同一套 libxposed API 102，仓库里不保留任何手工放置的 Xposed jar。

### 构建时的三个特殊点

1. **必须编到 Java 17**：libxposed api 的字节码是 major 61（Java 17），
   `--release 8` 会报 `bad class file: class file has wrong version 61.0, should be 52.0`。
2. **api 只用于编译，不进 dex**：脚本把抽出的 `classes.jar` 同时传给
   `d8 --lib`，告诉 d8「运行时由框架提供」。
3. **元数据在打包时合并**：`meta/META-INF/xposed/{module.prop,java_init.list,scope.list}`
   由 `pack_dex.py` 的附加目录参数原样写进 APK 根
   （不能用 `aapt2 -A assets`，那样会变成 `assets/META-INF/...`，路径不对）。

### 踩过的坑（脚本里已处理）

1. **原生工具不认 MSYS 路径**
   `javac` / `d8` / `aapt2` 是原生 Windows 程序，`/f/Project/...` 会被 MSYS 改写成 `\f\Project\...` 然后报 `file not found`。
   → 脚本统一用 `winpath()` 转成 `F:/...`，并 `export MSYS_NO_PATHCONV=1` 夺回控制权。

2. **`d8.bat` 自己读 `JAVA_HOME`**
   报 `ERROR: JAVA_HOME is set to an invalid directory: /c/Program Files/Java/jdk-21`。
   → 因为 `.bat` 包装脚本不经过 MSYS，必须给它 Windows 形式：`export JAVA_HOME="$(winpath "$JAVA_HOME")"`。

3. **classpath 分隔符**
   Windows 是 `;`，类 Unix 是 `:`。脚本用 `uname -s` 判断。

4. **javac 中文环境乱码**
   → 加 `-J-Duser.language=en -J-Duser.country=US`。

---

## 安装 & 使用

```bash
# 1. 构建
bash tools/probe/build.sh

# 2. 安装
adb install -r tools/probe/out/GoldSunProbe.apk

# 3. 在 LSPosed 里启用，作用域勾选「国信金太阳」，然后强杀目标 App
adb shell am force-stop com.guosen.android

# 4. 手动操作 App：进行情页看一遍 → 进 我的策略 → 策略详情
#    （探针会在页面 onResume 后自动拍快照）

# 5. 读日志
bash tools/sh.sh --root 'cat /data/adb/lspd/log/modules_*.log' | grep GSRecon
```

> 探针作用域在 `meta/META-INF/xposed/scope.list` 里写死为 `com.guosen.android`
> （`module.prop` 里 `staticScope=true`，LSPosed 会自动应用，通常不用手动勾）。
> 换目标 App 时需要改这个文件 + `ProbeModule.java` 里 `onModuleLoaded` /
> `onPackageReady` 的包名判断。

---

## 文件说明

```
tools/probe/
├── build.sh                 # 手工构建脚本（拉 api → javac→d8→aapt2→打包→apksigner）
├── pack_dex.py              # 把 classes.dex + 附加目录树写进 apk 的 zip 里
├── AndroidManifest.xml      # 普通 Android 声明（★ 无任何 legacy xposed* meta-data）
├── meta/META-INF/xposed/    # ★ 现代模块元数据（打包时合并进 APK 根）
│   ├── module.prop          #   minApiVersion=102 / targetApiVersion=102 / staticScope
│   ├── java_init.list       #   入口类：com.wb.probe.ProbeModule
│   └── scope.list           #   作用域：com.guosen.android
├── res/values/
│   └── strings.xml          # 模块名 "GoldSun Probe" + 描述
├── src/com/wb/probe/
│   └── ProbeModule.java     # ★ 全部逻辑（extends XposedModule）
├── probe.keystore           # 探针签名密钥（密码 android / 别名 probe）
│                            #   ↑ 被 .gitignore 的 *.keystore 排除，**不入库**；
│                            #     缺失时 build.sh 会自动 keytool 生成
├── build/                   # 中间产物（gitignore，可随时删，build.sh 重建）
└── out/GoldSunProbe.apk     # 最终产物（gitignore）
```

> 现代 API 模块**不使用** `assets/xposed_init`，也不用 `xposedmodule` /
> `xposedminversion` / `xposedscope` 这些 meta-data。
> 保留其中任何一个（尤其是 `xposedminversion`）都会让 LSPosed 把模块当 legacy 处理。
> 详见 [`../../docs/EXTENDING.md`](../../docs/EXTENDING.md) 的「架构红线」一节。

---

## 什么时候该重新跑一遍探针

- 国信金太阳**大版本升级**后 Hook 失效
- `WatchlistFeature` 抛 `ClassNotFoundException`
- 自选判定开始误判（黄名标错股票）
- 想加**新功能**，需要摸清新的页面/数据类

跑完之后，把新结论回写到 [`../../docs/REVERSE-ENGINEERING.md`](../../docs/REVERSE-ENGINEERING.md)（逆向与 Hook：过程与证据 + 难点与解法）。

---

## 免责

仅供**个人学习研究**与**本机自有设备**上的模块维护使用。请勿用于任何商业用途、逆向传播或规避软件授权。使用风险自负。
