# 逆向过程与结论

记录从「拿到 APK」到「定位到可 Hook 的点」的完整过程。
**结论部分**是实际写代码时依赖的事实，**过程部分**是为了下次能复现。

---

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

关键是**不需要预先知道任何业务类名**，全部走框架类：

| 挂钩点 | 拿到什么 |
|---|---|
| `Activity.onCreate` | 页面类名 → 画出页面链路 |
| `RecyclerView$Adapter.bindViewHolder` | adapter 类名 + item 类全字段（反射）+ holder 内每个 TextView 的 resource-id / 文本 / 当前颜色 |
| `TextView.setTextColor` | **只记录黄系颜色**（R>0x80 且 G>0x80 且 B<0x80）→ 直接定位"谁在染黄" |
| `WebView.loadUrl` | 判断某页面是不是 H5 |
| `SQLiteDatabase.rawQuery` | 抓自选股相关 SQL |
| `SharedPreferencesImpl.getString` | 抓解密后的配置值 |

这一套下来，**不用反编译一行代码**就拿到了全部需要的信息。

> 仓库里现在的 `tools/probe/`（**v25**）已经**收窄**成三件事：
> ① 挂 `Activity.onResume` 打页面链路；
> ② dump `ZXManager` / `ZXWorkflow` 的自选数据并做判定 API 交叉验证（v18）；
> ③ 挂一整套**通用**的跳转 / 布局侦察钩子 —— `Activity.startActivity*`（`LAUNCH`）、
> `Dialog.show`（`DLG`）、`PopupWindow.show*`（`POP`）、`Activity.onCreate`（`PAGE`）、
> 延迟 dump 视图树（`PAGER` / `RV` / `VW`）、`dumpClassMembers`（`CLASS`）—— v19～v25。
> 第 ③ 组全是框架类方法，**不预设任何业务类名**，用来摸清「点一下到底发生了什么」
> （详见 [`tools/probe/README.md`](../tools/probe/README.md) 第 4 节）。
>
> 需要重新做全量侦察时，按上面这张表临时加钩子即可。

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
[HOOK-NOTES.md · Hook 点怎么选](HOOK-NOTES.md#4-hook-点怎么选本项目的核心难点)。

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
| 底栏移除「理财」 | `feature/dock/DockCleanTargets.kt` |
| 首页隐藏横幅 | `feature/home/HomeBannerTargets.kt` |
| 行情市场隐藏横幅 / 板块压缩 / 概览压缩 | `feature/market/MarketTargets.kt` |

> **界面精简类功能（底栏 / 首页 / 行情市场）不需要先跑探针**：
> 它们认不出目标控件时会**自己**把当前页面的视图树（含真实资源 id 名与坐标）
> 打进模块日志 —— 打开功能、进一次页面、看「设置 → 查看日志」即可，
> 步骤见 [FEATURE-UI-CLEAN.md §4](FEATURE-UI-CLEAN.md#4-真机校准手册认不出来时照着做)。

完整排查流程见 [排错手册 · App 升级后失效](TROUBLESHOOTING.md#6-app-升级后失效)。
