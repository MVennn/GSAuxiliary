# 逆向与 Hook

本文分两部分：
**第一部分（过程与证据）** 记录从「拿到 APK」到「定位到可 Hook 的点」的完整过程，
**结论**是实际写代码时依赖的事实，**过程**是为了下次能复现；
**第二部分（难点与解法）** 记录本模块**所有真正卡住过的问题**及其解法，
如果你要给同类「加固 + vlayout + 多进程配置」的 App 写模块，第二部分能省掉大量重复试错。

> **另见**：顶号与登录态调研（一次以**否定结论**收场的调研，四条已验证无效的拦截思路别再走）——因法律风险整改已移出公开仓库，本地存档在 `.work/legal-archive/`（不进 git）。
> 那套登录态机制（`ParseLoginDataProcess` 的三连检查、`UserAccountManager` 是登录态总出口、
> 被顶后重启会清空 `USER_ACCOUNT_CONFIGS`）对写别的功能同样有用，
> 而里面**四条已经验证无效的拦截思路**就别再走一遍了。

目标 App：**国信金太阳 8.6.0**（`com.guosen.android`）

> 本文档仅为**维护本模块与目标应用新版本的兼容性**而记录，面向的是已经合法取得并使用目标应用的开发者本人；不鼓励、不引导对任何第三方应用进行逆向。文中涉及的目标应用内部信息（类名、字段、结构）均为事实性描述，不包含目标应用的任何代码或受版权保护的表达。

---

# 第一部分 · 过程与证据

## 一、APK 基础信息

| 项 | 值 |
|---|---|
| 包名 | `com.guosen.android` |
| 版本 | versionName `8.6.0` / versionCode `271` |
| minSdk / targetSdk | 21 / 30 |
| 体积 | 150,373,575 字节（约 144 MB） |
| DEX | 单 dex，未压缩 97,392,176 字节 |
| ABI | `arm64-v8a` / `armeabi-v7a` |
| 打包时间 | 2026-09-15 |

---

## 二、加固识别：梆梆企业版（SecNeo / Bangcle）

**证据链完整。**

### 2.1 manifest 里的壳入口

```xml
<application
    android:name="com.secneo.apkwrapper.AW"
    android:appComponentFactory="com.secneo.apkwrapper.AP" />

<provider android:name="com.secneo.apkwrapper.CP" />
```

### 2.2 native 壳库

```
lib/arm64-v8a/libDexHelper.so         1,070,186 字节
lib/arm64-v8a/libDexHelper-x86.so     1,237,741 字节
lib/armeabi-v7a/libDexHelper.so         627,418 字节
lib/armeabi-v7a/libDexHelper-x86.so   1,151,229 字节
```

### 2.3 DEX 头部自洽但内容是加密载荷

`classes.dex` 的 header 声明 `file_size = 97,392,176`，与实际大小一致，
看起来"正常"。但内部索引数量小得离谱：

```
string_ids : 515        ← 97 MB 的 dex 只有 515 个字符串
type_ids   : 166
proto_ids  : 91
field_ids  : 33
method_ids : 304
class_defs : 53         ← 只有 53 个类
```

**结论：这 97 MB 里，只有头部 + 一小段壳代码有效，其余约 97 MB 全是加密载荷。**

壳内可见的字符串进一步印证：

```
###HAVEX86###        ← SecNeo 保护标记
###HAVEX8664###      ← SecNeo 保护标记
```

### 2.4 所以静态反编译这条路是堵死的

- dex 被整体加密，运行时才由 native 壳在内存中解密
- 字符串被混淆，`strings` 拿不到有效类名
- IDA / x64dbg 要跟 native 解密流程，成本极高，且对 Java 层帮助有限

---

## 三、关键判断：改走动态路线

> **梆梆保护的是静态文件，运行时内存里一定是明文。**

所以正确路线不是"破解壳"，而是**在运行时观察**：

1. 写一个探针 LSPosed 模块，注入目标进程
2. 在**框架层**挂钩，把运行时才存在的类名 / 方法名 / 字段名打印出来
3. 用打印结果反推 Hook 点

**实测：梆梆没有拦截 LSPosed 注入。** 模块可以正常挂上并生效。

探针源码保留在 [`tools/probe/`](../tools/probe/)，App 升级后可以重跑。

### 3.1 探针挂了哪些「万能点」

关键是**不需要预先知道任何业务类名**，全部走框架类。

> 挂钩点与各能拿到的信息（`Activity.onCreate` / `bindViewHolder` / `setTextColor` …），
> 以及 `tools/probe/`（**v25**）现在收窄成的三件事 —— 见 [下文 §1](#1-加固为什么放弃静态反编译)。
> 那里是这张表与收窄说明的**唯一出处**，避免两边改动不同步。

---

## 四、结论一：页面链路

探针输出的 Activity 序列：

```
MainAct                       首页
 └─ AIStkIndexAct             智能选股
     └─ AIStkStgListAct       我的策略（策略列表）
         └─ AIStkStgDetailAct 策略详情  ← 目标页面
```

对应操作路径：`首页 → 智能选股 → 我的策略 → 任一策略`。

---

## 五、结论二：染色机制

### 5.1 观察

用 `TextView.setTextColor` 探针（只记录黄系颜色）得到：

- **行情页**：已加自选的股票名称被染成 `#FFFF8000`
- **策略详情页**：同样是已加自选的股票，名称是普通深色

### 5.2 定位到具体调用方

`bindViewHolder` 探针输出显示，两个页面用的是**同一个 `StockInfo` 类**，
其中有个布尔字段 `isZx`：

- 行情页的数据源会正确填充它 → 适配器据此染色
- 策略详情页的数据源**没有填** → `isZx` 恒为 `false` → 永远不黄

**所以这不是"策略页缺功能"，而是数据源少填了一个字段。**

### 5.3 适配器

| 页面 | 适配器 |
|---|---|
| 行情页 | `MarketsHQAdapter` |
| 策略详情页 | `com.guosen.quotation.adapter.aistk.AIStkAdapter`（继承 `com.guosen.commonui.adapter.BaseQuickAdapter`） |

---

## 六、结论三：自选判定 API 及校准

### 6.1 找到 API

从 `ZXManager` 的私有字段 dump 里看到：

```
m_stkGrpMap (size=178)          ← 自选股票映射
m_groupInfoDatas
  GRPINFO m_groupId=-1 m_groupName=全部
  GRPINFO m_groupId=1  m_groupName=自选
  GRPINFO m_groupId=2  m_groupName=1
getAllStock() -> 178 项
```

`m_stkGrpMap` 的 key 形如 `"300006.1"`（代码 + 点 + 市场）。

上层封装：

```java
ZXWorkflow.isBelongByStock(code, market)
  └─ ZXManager.isBelongToAnyGroupByStock(...)
       └─ 等价于 m_stkGrpMap.containsKey("code.market")
```

### 6.2 校准：拿行情页真值交叉验证

探针内置 20 只**行情页**股票的真值样本（`HQ_CAM` / `HQ_TRUTH`，
其中只有 `300006.1` 与 `300516.1` 两只 `isZx = true`，其余 18 只为 `false`），
对 5 种候选判定方式逐一打分：

| 候选 | 调用 | 形态 |
|---|---|---|
| A | `ZXWorkflow.isBelongByStock(code, market)` | 静态，2 参 |
| B | `ZXManager.isBelongToAnyGroupByStock(code, market)` | 实例，2 参 |
| C | `ZXManager.isBelongToGroupByStock(code, market, groupId)` | 实例，3 参 |
| D | `ZXWorkflow.getAllStock()` 结果里是否含该股 | 静态，集合包含 |
| E | `ZXWorkflow.getZXDefaultGroupStocks()` 结果里是否含该股 | 静态，集合包含 |

> ⚠️ **候选的定义以探针源码为准**（`tools/probe/src/com/wb/probe/ProbeModule.java`
> 的 `crossCheck`）。这一节早期版本曾把 D/E 误标成 `isBelongByStock`，
> 并给出互相打架的 0/20、18/20 两组数字 —— 那是探针 v16/v17 代的旧记录，
> 已按现行 v25 源码更正。**具体每项命中数请以真机跑探针的输出为准**
> （示例见 [`tools/probe/README.md`](../tools/probe/README.md)）。

**为什么必须做交叉验证**：探针 v16 版直接用了 `isBelongByStock`，
在策略详情页 10/11 判 `true`，看起来"能用"；
但拿行情页 20 只真值一比对就发现它的语义有偏差。
**不交叉验证就不知道哪个 API 是真的对。**

**最终结论**：`ZXWorkflow.isBelongByStock(code, market)`（候选 A，静态 2 参）
与行情页同源、判定一致，被 `feature/watchlist/ZxWatchlist.kt` 采用，
并以直接查 `ZXManager.m_stkGrpMap` 的 `"code.market"` 键作为兜底。

---

## 七、结论四：Hook 点

最终选择 `BaseQuickAdapter.getView`。

试错过程与原因（vlayout、抽象方法、类加载器）见
[下文 · Hook 点怎么选](#4-hook-点怎么选本项目的核心难点)。

---

## 八、复现步骤

如果 App 升级后需要重新做一遍，按这个顺序：

```bash
# 1. 构建并安装探针
bash tools/probe/build.sh
adb install -r tools/probe/out/GoldSunProbe.apk

# 2. 在 LSPosed 里启用探针，作用域勾「国信金太阳」

# 3. 强制停止并重开国信金太阳，正常操作到目标页面

# 4. 看探针输出（同样在 LSPosed 日志里，不在 logcat）
adb shell su -c "grep -i probe /data/adb/lspd/log/modules_*.log | tail -50"
```

从输出里找：

- `ACTIVITY >` → 页面类名（画出页面链路）
- `ZXManager 实例字段` / `m_stkGrpMap` → 自选数据源结构
- 交叉验证表 `A:isBelong | B:anyGroup | …` → 哪个判定 API 与行情页真值完全一致
- `LAUNCH` / `PAGE` / `PAGER` → 页面跳转的 Intent 契约与翻页控件
  （结论见 [FEATURE-DIRECT-QUOTE.md](FEATURE-DIRECT-QUOTE.md)）
- `CLASS` → 任意类的构造器 / 字段 / 方法签名（用来找 `IntentStockInfo(StockInfo)`
  这类「自带转换构造器」）

拿到之后，按功能改对应的候选常量文件：

| 功能 | 改哪里 |
|---|---|
| 自选高亮 | `feature/watchlist/WatchlistTargets.kt` |
| 直达标准行情 + 左右滑动 | `feature/strategyquote/StgDirectQuoteTargets.kt` |
| 多选栏全选 / 反选 | `feature/strategyselect/StgSelectAllTargets.kt` |
| 策略详情页筛选股票 | `feature/stgfilter/StgStockFilterTargets.kt` |
| 底栏「智能盯盘」入口 | `feature/stockbar/StockDetailBarTargets.kt` |
| 底栏隐藏理财入口 | `feature/dock/DockCleanTargets.kt` |
| 首页隐藏横幅 | `feature/home/HomeBannerTargets.kt` |
| 行情市场隐藏滚动横幅与快捷入口 / 板块显示单行 / 市场概览高度优化 | `feature/market/MarketTargets.kt` |

> **界面精简类功能（底栏 / 首页 / 行情市场）不需要先跑探针** —— 它们认不出目标控件时
> 会**自己**把当前页面的视图树（含真实资源 id 名与坐标）打进模块日志，
> 步骤见 [FEATURE-UI-CLEAN.md §4](FEATURE-UI-CLEAN.md#4-真机校准手册认不出来时照着做)；
> 该改哪个文件见 [下文 §10](#10-失效预案app-升级后怎么办)。

完整排查流程见 [排错手册 · App 升级后失效](TROUBLESHOOTING.md#6-app-升级后失效)。

---

# 第二部分 · 难点与解法

## 0. 总览：难点分级

| 难度 | 问题 | 一句话解法 |
|---|---|---|
| ★★★ | 加固 App 无法静态反编译 | 放弃静态，改用**运行时动态探针**定位 |
| ★★★ | 配置跨进程同步 | 用框架托管的 **remote preferences**（见 §7） |
| ★★☆ | vlayout 子适配器抓不到 | 挂钩点上移到 `BaseQuickAdapter.getView` |
| ★★☆ | 类加载器不对，找不到宿主类 | `Class.forName(name, false, env.classLoader)` |
| ★★☆ | 关闭开关后旧颜色不还原 | Hook 里**不能提前 return** |
| ★★☆ | 钩子摘不掉 / 热重载残留 | 句柄必须收进 `HookBag` |
| ★☆☆ | 模块日志在 logcat 里看不到 | 去 `/data/adb/lspd/log/` 找，或用「设置 → 日志 → 导出日志」 |
| ★☆☆ | RecyclerView 复用导致状态残留 | 记录「原始色」而非「当前色」 |

---

## 1. 加固：为什么放弃静态反编译

APK 解包后 `classes.dex` 里几乎没有业务代码，只有 `com.secneo.apkwrapper.AW` /
`ApplicationWrapper` 两个壳类 —— 这是**梆梆加固企业版**，静态反编译这条路是堵死的
（壳入口、`libDexHelper.so` 清单、DEX header 计数、`###HAVEX86###` 标记等
**完整证据链见上文第二章**；「运行时内存里是明文、改走动态路线」的关键判断
**见上文第三章**）。

### 探针挂了哪些「万能点」

早期侦察代探针不需要预先知道任何业务类名，全部走框架类：

| 挂钩点 | 拿到什么 |
|---|---|
| `Activity.onCreate` | 页面类名（画出页面链路） |
| `RecyclerView$Adapter.bindViewHolder` | adapter 类名 + item 类全字段 + holder 内每个 TextView 的 id / 文本 / 颜色 |
| `TextView.setTextColor` | 只记录黄系颜色 → 直接定位「谁在染黄」 |
| `SharedPreferencesImpl.getString` | 解密后的配置值 |

这一套下来，**不用反编译一行代码**就拿到了全部需要的信息。

> 仓库里现在的 `tools/probe/`（**v25**）已经**收窄**成三件事：
> ① 挂 `Activity.onResume` 打页面链路；
> ② dump `ZXManager` / `ZXWorkflow` 的自选数据并做判定 API 交叉验证（v18）；
> ③ 挂一整套**通用**的跳转 / 布局侦察钩子 —— `Activity.startActivity*`（`LAUNCH`）、
> `Dialog.show`（`DLG`）、`PopupWindow.show*`（`POP`）、`Activity.onCreate`（`PAGE`）、
> 延迟 dump 视图树（`PAGER` / `RV` / `VW`）、`dumpClassMembers`（`CLASS`）—— v19～v25。
> 第 ③ 组全是框架类方法，**不预设任何业务类名**，用来摸清「点一下到底发生了什么」。
>
> 需要重新做全量侦察时，按上面这张表临时加钩子即可。

---

## 2. 页面链路

目标页面在**策略详情页**：`首页 → 智能选股 → 我的策略 → 任一策略`，
Activity 序列 `MainAct → AIStkIndexAct → AIStkStgListAct → AIStkStgDetailAct`
（带缩进的完整链路图**见上文第四章**）。

---

## 3. 染色机制：为什么原版只有行情页是黄的

### 3.1 观察与结论

- **行情页**：已加自选的股票，名称显示 `#FFFF8000`（琥珀黄）
- **策略详情页**：同样是已加自选的股票，名称是普通深色 `#212634`

两个页面用的是**同一个 `StockInfo` 类**，`StockInfo` 上有个布尔字段 `isZx`：
行情页的数据源会正确填充它 → 适配器据此染色；策略详情页的数据源**没有填** →
`isZx` 恒为 `false` → 永远不黄。所以这不是"策略页缺功能"，
而是**数据源少填了一个字段**（探针观察与适配器结论**见上文第五章**）。

### 3.2 我们的做法：不改数据，改视图

不去动数据源（容易踩到内部状态），而是在**视图渲染完成后**补一次判定：

```
列表行视图创建/复用 → 找到名称 TextView → 用国信自己的 API 判定是否在自选
                    → 命中则 setTextColor(高亮色)，否则还原
```

判定 API 用国信自己的：

```java
ZXWorkflow.isBelongByStock(code, market)   // code="300006", market="1"
```

内部链路：`ZXWorkflow.isBelongByStock` → `ZXManager.isBelongToAnyGroupByStock`
→ 等价于 `m_stkGrpMap.containsKey("code.market")`。

**与原版同源**，所以不存在"自选库不同步""边界情况判定不一致"的问题。

> 实测校准：拿行情页 20 只股票的真值做交叉验证，
> `ZXWorkflow.isBelongByStock` 判定 **20/20 全部吻合**。

---

## 4. Hook 点怎么选（本项目的核心难点）

### 4.1 试错过程

| 尝试 | 结果 | 原因 |
|---|---|---|
| `RecyclerView$Adapter.onBindViewHolder` | ❌ 报 `Cannot hook abstract methods` | 这是抽象方法，不能直接挂 |
| 改挂 `bindViewHolder`（非抽象） | ❌ 抓不到策略详情页 | 见下 |
| 挂 `TextView.setTextColor` | ⚠️ 能拿到颜色，但拿不到"这一行是哪只股票" | 缺少上下文 |
| **挂 `BaseQuickAdapter.getView`** | ✅ 成功 | 见下 |

### 4.2 为什么 `bindViewHolder` 抓不到：vlayout

策略详情页用的是**阿里巴巴 vlayout**。

vlayout 的 `DelegateAdapter` 会把子适配器包一层，
**子适配器的 `bindViewHolder` 并不经过 `RecyclerView.Adapter.bindViewHolder` 的常规路径**，
所以在那个点上永远等不到策略页的行。

### 4.3 为什么 `getView` 可以

`BaseQuickAdapter` 是国信自己的列表基类（`com.guosen.commonui.adapter.BaseQuickAdapter`），
它内部最终会调用到 `getView(position, convertView, parent)` ——
**只要列表要显示一行，这个方法就一定被调用**，跟外面套了几层 DelegateAdapter 无关。

而且它在**行视图构建完成之后**才返回，正好是补颜色的时机。

```kotlin
// 拦截链：chain.proceed() 相当于调用原方法
env.xposed.hook(method)
    .setId("watchlist:getView:base")
    .intercept { chain ->
        val result = chain.proceed()        // 原方法执行完，行视图已构建
        runCatching { onGetView(env, chain.thisObject, chain.args, result) }
            .onFailure { GLog.w("getView 后处理异常", it) }
        result
    }
```

> API 102 没有 `hookAllMethods` 这种批量写法，需要自己枚举 `Executable`。
> 本项目封装在 `Reflect.methodsNamed(cls, "getView")`，它会自动过滤 abstract 方法。

同时**双保险**再挂一次目标适配器自己的 `getView`：

```
"com.guosen.quotation.adapter.aistk.AIStkAdapter"
```

（若某版本里该适配器覆写了 `getView`，也能覆盖到；找不到这个类就静默跳过。）

### 4.4 怎么从 adapter 里取出「代码.市场」

不能假设数据项的类名/字段名（加固 + 混淆），所以全部走**反射 + 候选字段名**
（集中在 `feature/watchlist/WatchlistTargets.kt`）：

```kotlin
// 优先：直接有 "code.market" 字符串字段
CAM_FIELDS    = arrayOf("codeAndMarket", "m_codeAndMarket")

// 兜底：代码字段 + 市场字段 拼起来
CODE_FIELDS   = arrayOf("m_stkCode", "code", "mCode")
MARKET_FIELDS = arrayOf("m_stkMarket", "market", "mMarket")
```

`Reflect.readField()` 会沿类继承链向上找，兼容字段声明在父类的情况。

取 adapter 的数据项也有多级兜底：`getItem(int)` → `getData()/getItems()/getList()` → 常见字段名。

---

## 5. 类加载器：一个必踩的坑

在 Xposed 模块里写 `Class.forName("com.guosen.xxx.Yyy")` **会抛 ClassNotFoundException**，
即使这个类明明存在。

原因：模块代码跑在目标进程里，但 `Class.forName(String)` 用的是**模块自己的类加载器**，
而宿主的类是目标 App 的 `PathClassLoader` 加载的，两者看不到彼此。

**正确写法**（第三个参数传宿主的 classLoader）：

```kotlin
override fun onPackageReady(param: PackageReadyParam) {
    install(param.classLoader)          // 从这里拿到宿主 classLoader
}

// 或者用项目封装的工具
Reflect.loadClass(name, env.classLoader)
```

同理，任何"提前主动加载宿主类"的地方都要带上宿主的 classLoader。

---

## 6. 关闭开关后颜色不还原

### 现象

关掉模块开关，已经渲染过的行**依然是黄的**。

### 原因（两个叠加）

1. 最初的实现里写了 `if (!cfg.enabled) return;` —— 关掉时直接跳过，什么都不做
2. RecyclerView 会**复用** item view，黄颜色是残留在 `TextView` 上的状态，
   不主动改它就一直在

### 解法

**不要提前 return**。无论开关开还是关，都走同一条染色逻辑：

```kotlin
val target = if (inWatchlist) cfg.color else originalColor(nameTv)
nameTv.setTextColor(target)
```

关掉开关时 `inWatchlist` 恒为 `false`，于是每行都被还原成原始色。

### 附带问题：什么是「原始色」

不能简单地把"当前颜色"当作原始色 —— 当前颜色可能就是我们自己染的黄。
所以维护一张 `WeakHashMap<TextView, Int>` 缓存**第一次见到的非高亮色**：

```kotlin
if (!isPresetColor(cur)) originalColors[tv] = cur
```

用 `WeakHashMap` 避免长时间持有已回收的 View。

---

## 7. 配置通道

### 7.1 用框架托管的 remote preferences

```
设置界面（模块进程）
   └─ XposedService.getRemotePreferences("config")      ← 通过 binder 拿到框架的 prefs
被注入进程
   └─ XposedInterface.getRemotePreferences("config")    ← 同一份数据，框架托管
   └─ registerOnSharedPreferenceChangeListener { … }    ← 值一变，框架主动推送
```

框架负责：**持久化 + 跨进程同步 + 变更推送**。我们两边都只是拿一个
`SharedPreferences` 来用。

### 7.2 目标进程侧（`core/ConfigSource.kt`）

```kotlin
val caps = xposed.frameworkProperties
if (caps and XposedInterface.PROP_CAP_REMOTE == 0L) {
    // 框架不支持 remote 能力 → 明确记日志，不静默失败
    channel = "框架未声明 remote preferences 能力"
    snapshot = ConfigSnapshot.DEFAULT
    return
}

val p = xposed.getRemotePreferences(ConfigKeys.GROUP)     // "config"
p.registerOnSharedPreferenceChangeListener(changeListener)
snapshot = read()
```

变更回调在 **Binder 线程**触发，所以回调里重新读快照后交给
`FeatureManager` 切主线程分发。

### 7.3 模块 App 侧（`app/ModulePreferences.kt` + `app/FrameworkBridge.kt`）

```kotlin
// FrameworkBridge：注册服务监听，拿 binder
XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
    override fun onServiceBind(service: XposedService) { … }
    override fun onServiceDied(service: XposedService) { … }
})

// ModulePreferences：REMOTE 优先，未连接时退回 LOCAL
remote = service.getRemotePreferences(ConfigKeys.GROUP)
```

**降级行为**：框架未连接时（比如刚装好还没在 LSPosed 里勾选），
模块 App 侧自动退回本地 `SharedPreferences`；框架一连上，
把本地值**一次性迁移**到远端，用户不会丢配置。

### 7.4 踩过的坑：不要自己找 prefs 文件

在 API 102 之前，跨进程配置是靠「让 LSPosed 重定向模块的 SharedPreferences
到公共目录 + 每次写盘后 chmod 0664 + 另一个进程用 `XSharedPreferences` 读」实现的。
那条路有几个致命问题：

- 文件权限是 **0660**，另一个 UID 读不到，而读失败的行为是**静默返回默认值** ——
  不报错、不打日志，极难排查
- 没有任何变更通知机制，只能**轮询**（当年是每 3 秒重读一次）
- 还试过用 `exported` 的 `ContentProvider` 传配置，但 Android 11+ 的
  **包可见性（package visibility）**会拦死 —— 目标 App 没有在 `<queries>` 里
  声明我们的模块包，被注入进程拿到的是 `null` cursor

**现在整条路都废弃了**：框架的 remote preferences 一次性解决了权限、同步、
通知三个问题，代码里也不需要任何文件操作。

---

## 8. 配置变更后列表不刷新

### 现象

改完设置切回 App，已经渲染过的行颜色没变。

### 原因

`getView` 只在**列表要显示某一行**时被调用。已经渲染好的行，
RecyclerView 不会主动重新绑定。

### 解法（两层）

**① 配置变化时主动 `notifyDataSetChanged()`** —— 主路径

用弱引用集合记住见过的 adapter，延后到下一帧再调用（避开
`Cannot call this method while RecyclerView is computing a layout` 崩溃）：

```kotlin
mainHandler.post {
    for (a in seenAdapters) {
        Reflect.findMethod(a.javaClass, "notifyDataSetChanged")?.invoke(a)
    }
}
```

**② 挂 `Activity.onResume`** —— 兜底

切回 App 时比对配置指纹，变了就刷新，用户**不用手动滚动**：

```kotlin
val cls = Reflect.loadClass("android.app.Activity", env.classLoader)!!
for (m in Reflect.methodsNamed(cls, "onResume")) {
    bag.add(env.xposed.hook(m).setId("watchlist:onResume").intercept { chain ->
        val r = chain.proceed()
        runCatching { observeConfig(env.config, allowRefresh = true) }
        r
    })
}
```

---

## 9. 模块日志：不在 logcat 里

目标进程的日志走 `XposedInterface.log()`，**不会**进 `logcat -s <TAG>`。
LSPosed 把它写进自己的日志文件：

```
/data/adb/lspd/log/modules_<时间戳>.log
```

查看：

```bash
adb shell su -c "grep GSZXEnhance /data/adb/lspd/log/modules_*.log | tail -20"
```

> 排查"模块到底有没有加载"时，**先看这里**，不要浪费时间在 logcat 上。
>
> 例外：**模块 App 自己的进程**没有框架接口，`core/GLog.kt` 会自动退回
> `android.util.Log` —— 那部分日志是**在 logcat 里**的（TAG = `GSZXEnhance`），
> 同时还会落盘到 `filesDir/logs/`，供「设置 → 日志 → 导出日志」读取。
>
> 不想用 adb 的场合：设置页的「导出日志」把上面两处拼成一份 txt
> （目标进程那节要 `su` 读 LSPosed 日志文件，会弹一次授权）。

---

## 10. 失效预案（App 升级后怎么办）

如果国信升级后模块不生效，按顺序检查。**每个功能各自带一份候选常量文件，
互不影响** —— 先看日志判断是哪个功能挂了，再去改对应的那一份：

1. **看 LSPosed 日志**确认模块有没有载入、钩子有没有装上、哪个功能被跳过
2. **自选高亮**
   - 适配器类名变了 → 改 `feature/watchlist/WatchlistTargets.kt` 的
     `TARGET_ADAPTERS` / `CLS_BASE_QUICK_ADAPTER`
   - 控件 id 变了 → 改 `NAME_ID_CANDIDATES`
   - 数据项字段名变了 → 往 `CAM_FIELDS` / `CODE_FIELDS` / `MARKET_FIELDS` 加候选名
   - 自选判定 API 变了 → 改 `feature/watchlist/ZxWatchlist.kt`，
     兜底逻辑是直接查 `ZXManager.m_stkGrpMap` 的 `"code.market"` 键
3. **直达标准行情 + 左右滑动** → 改 `feature/strategyquote/StgDirectQuoteTargets.kt`：
   `CLS_ENGINE_ACT` / `LAUNCH_METHODS` / `ACTION_*` / `KEY_STOCK_*` /
   `CLS_INTENT_STOCK_INFO` / 三个字段候选数组
4. **多选栏全选 / 反选** → 改 `feature/strategyselect/StgSelectAllTargets.kt`：
   `CANCEL_BUTTON_ID_CANDIDATES` / `LIST_ID_CANDIDATES` / `CHECKBOX_ID_CANDIDATES` /
   `COUNT_ID_CANDIDATES` / `WATCH_ACTIVITIES`
5. **策略详情页筛选股票** → 改 `feature/stgfilter/StgStockFilterTargets.kt`
6. **个股详情页底栏「智能盯盘」入口** → 改 `feature/stockbar/StockDetailBarTargets.kt`：
   `BOTTOM_BAR_ID_CANDIDATES` / `MORE_TEXT_ID_CANDIDATES` / `MENU_GRID_ID_CANDIDATES` /
   `MENU_ITEM_TEXT_ID_CANDIDATES` / `MENU_ITEM_TEXT`。
   「更多」还有**按文字 + 限定在底栏内**的兜底，id 改名一般不用改代码；
   若面板不再是 `PopupWindow`，日志会明确报出来（见
   [底栏入口实现说明](FEATURE-STOCK-BAR.md)）
7. **界面精简类（底栏 / 首页 / 行情市场）** → 改
   `feature/dock/DockCleanTargets.kt` / `feature/home/HomeBannerTargets.kt` /
   `feature/market/MarketTargets.kt` 的候选数组与结构阈值。
   **这一类不用跑探针**：认不出目标控件时模块会**自己**把当前页面的视图树
   （含真实资源 id 名与坐标）打进模块日志 —— 打开开关、进一次页面、
   看模块日志（目标进程 → `/data/adb/lspd/log/`）即可。完整手册见
   [界面精简方案与校准手册](FEATURE-UI-CLEAN.md#4-真机校准手册认不出来时照着做)
8. **隐藏新股申购提醒** → 改 `feature/ipoclean/IpoCleanTargets.kt`：
   `IPO_DIALOG_CLASSES` / `IPO_DIALOG_SIMPLE_NAMES` / `SHOW_METHODS`。
   弹窗类名或宿主展示方法改名时加候选即可；完整手册见
   [新股弹窗净化说明](FEATURE-IPO-DIALOG.md#3-认不出来时真机校准手册)
9. **智能盯盘价格页一键配置** → 改 `feature/aistare/AiStareQuickConfigTargets.kt`
   （页面类名 / WebView 容器 / H5 桥候选）。这一层挂在宿主 H5 上，失效表现与
   原生功能不同（按钮不出现或点了没反应），排查按
   [一键配置方案](FEATURE-AISTARE-QUICK-CONFIG.md#11-复现侦察的步骤app-升级后照做)
   §11 的五步重新侦察：进价格页 → dump 结构 → 拿 WebView URL → 验 H5 桥 → 采按钮色。
10. **盯盘数据卡片拓展** → 改 `feature/aistare/AiStareCardDetailTargets.kt`
    （列表适配器与卡片控件候选）。适配器类名 / 控件 id 变了加候选即可，
    跳转契约（`quotes.stockDetail` + `IntentStockInfo`）见
    [卡片拓展说明](FEATURE-AISTARE-CARD-DETAIL.md)。

设计上所有关键名字都是**常量数组 + 多级兜底**（每个功能一个 `XxxTargets.kt`），
就是为了这种时候改一行就能修好。

> **改完功能后重装模块 APK**：不用重启金太阳。模块声明了 `autoHotReload=true`，
> LSPosed 会自动热重载目标进程（2.18.0 起模块 App 内不再提供手动触发入口）。
> 详见 [排错手册 · 热重载相关](TROUBLESHOOTING.md#8-热重载相关)。
