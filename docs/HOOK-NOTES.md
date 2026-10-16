# Hook 难点与方法

本文记录本模块**所有真正卡住过的问题**及其解法。
如果你要给同类「加固 + vlayout + 多进程配置」的 App 写模块，这篇能省掉大量重复试错。

> **另见**：顶号与登录态调研（该记录已下线归档） —— 一次以**否定结论**收场的调研。
> 那套登录态机制（`ParseLoginDataProcess` 的三连检查、`UserAccountManager` 是登录态总出口、
> 被顶后重启会清空 `USER_ACCOUNT_CONFIGS`）对写别的功能同样有用，
> 而里面**四条已经验证无效的拦截思路**就别再走一遍了。

目标 App：**国信金太阳 8.6.0**（`com.guosen.android`）

---

## 0. 总览：难点分级

| 难度 | 问题 | 一句话解法 |
|---|---|---|
| ★★★ | 加固 App 无法静态反编译 | 放弃静态，改用**运行时动态探针**定位 |
| ★★★ | 配置跨进程同步 | 用框架托管的 **remote preferences**（见 §7） |
| ★★☆ | vlayout 子适配器抓不到 | 挂钩点上移到 `BaseQuickAdapter.getView` |
| ★★☆ | 类加载器不对，找不到宿主类 | `Class.forName(name, false, env.classLoader)` |
| ★★☆ | 关闭开关后旧颜色不还原 | Hook 里**不能提前 return** |
| ★★☆ | 钩子摘不掉 / 热重载残留 | 句柄必须收进 `HookBag` |
| ★☆☆ | 模块日志在 logcat 里看不到 | 去 `/data/adb/lspd/log/` 找 |
| ★☆☆ | RecyclerView 复用导致状态残留 | 记录「原始色」而非「当前色」 |

---

## 1. 加固：为什么放弃静态反编译

APK 解包后 `classes.dex` 里几乎没有业务代码，只有 `com.secneo.apkwrapper.AW` /
`ApplicationWrapper` 两个壳类 —— 这是**梆梆加固企业版**。静态路径被堵死：
dex 整体加密（运行时由壳在内存解密）、字符串混淆、跟 native 壳解密流程成本极高。

> 完整证据链（壳入口、`libDexHelper.so` 清单、DEX header 计数、`###HAVEX86###` 标记）
> 见 [REVERSE-ENGINEERING.md §二](REVERSE-ENGINEERING.md)。

**关键判断：梆梆保护的是静态文件，运行时内存里一定是明文。**
所以正确路线是**动态**：

1. 写一个「探针模块」注入目标进程
2. 在框架层挂钩，把运行时才存在的类名 / 方法名 / 字段名打印出来
3. 用打印结果反推 Hook 点

> 实测：梆梆**没有**拦截 LSPosed 注入。模块可以正常挂上并生效。
> 探针源码保留在 [`tools/probe/`](../tools/probe/)。

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
Activity 序列 `MainAct → AIStkIndexAct → AIStkStgListAct → AIStkStgDetailAct`。

> 带缩进的完整链路图见 [REVERSE-ENGINEERING.md §四](REVERSE-ENGINEERING.md)。

---

## 3. 染色机制：为什么原版只有行情页是黄的

### 3.1 观察

- **行情页**：已加自选的股票，名称显示 `#FFFF8000`（琥珀黄）
- **策略详情页**：同样是已加自选的股票，名称是普通深色 `#212634`

两个页面用的是**同一个 `StockInfo` 类**。

### 3.2 结论

`StockInfo` 上有个布尔字段 `isZx`：

- 行情页的数据源会正确填充它 → 适配器据此染色
- 策略详情页的数据源**没有填** → `isZx` 恒为 `false` → 永远不黄

所以这不是"策略页缺功能"，而是**数据源少填了一个字段**。

### 3.3 我们的做法：不改数据，改视图

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
> `android.util.Log` —— 那部分日志是**在 logcat 里**的（TAG = `GSZXEnhance`）。

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
   看模块 App「设置 → 查看日志」即可。完整手册见
   [界面精简方案与校准手册](FEATURE-UI-CLEAN.md#4-真机校准手册认不出来时照着做)
8. **隐藏新股申购提醒** → 改 `feature/ipoclean/IpoCleanTargets.kt`：
   `IPO_DIALOG_CLASSES` / `IPO_DIALOG_SIMPLE_NAMES` / `SHOW_METHODS`。
   弹窗类名或宿主展示方法改名时加候选即可；完整手册见
   [新股弹窗净化说明](FEATURE-IPO-DIALOG.md#3-认不出来时真机校准手册)

设计上所有关键名字都是**常量数组 + 多级兜底**（每个功能一个 `XxxTargets.kt`），
就是为了这种时候改一行就能修好。

> **改完功能后重装模块 APK**：不用重启金太阳。模块声明了 `autoHotReload=true`，
> LSPosed 会自动热重载目标进程（2.18.0 起模块 App 内不再提供手动触发入口）。
> 详见 [排错手册 · 热重载相关](TROUBLESHOOTING.md#8-热重载相关)。
