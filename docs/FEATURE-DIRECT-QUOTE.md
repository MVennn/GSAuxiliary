# 策略详情页「直达标准行情 + 左右滑动」实现方案

> 目标：在 **智能选股 → 我的策略 → 策略详情**（`AIStkStgDetailAct`）里点击一只股票，
> **跳过中间那一页**，直接进「标准行情」个股详情页，并且能像行情 / 自选列表一样
> **左右滑动切换股票**。
>
> 本文的链路与契约**已在真机（魅族 20 / 金太阳 8.6.0 vc271）实测确认**，
> 依据是 `tools/probe/` v25 的运行时探针日志，不是推断。

---

## 0. 结论先行

| 问题 | 答案 |
|---|---|
| 中间那一步是什么 | **不是弹窗，是一个独立页面** `com.guosen.quotation.ui.aistk.AIStkDetailFragAct`（智能选股个股详情）。用户要点的「标准行情」就是它右上角的按钮。 |
| 左右滑动靠什么 | 一个 `adapter_key` Bundle 里的 **`stockList`（ArrayList&lt;IntentStockInfo&gt;）+ `stockIndex`（Integer）**。行情页传了整张列表，策略链路只传了一只。 |
| 能不能做 | **能**，而且不需要动目标 App 源码，只需在**两个跳转点之间改一次 Intent**。 |
| 核心难点 | `StockInfo` → `IntentStockInfo` 的类型转换 —— 好消息：`IntentStockInfo` 自带 **`IntentStockInfo(StockInfo)` 构造器**，转换不用自己拼字段。 |

---

## 1. 实测链路（真机证据）

### 1.1 三条路径的 Intent 契约对比

| # | 来源 | action | extras | node_level | 结果 |
|---|---|---|---|---|---|
| **A** | 自选列表 `NewZxgGroupFrag`<br>→ `StockListView.onItemClick` | `quotes.stockDetail` | `adapter_key` = Bundle{ **`stockList`** = `ArrayList<IntentStockInfo>` ×33 }<br>**顶层** `stockIndex` = **2** | **2** | `StockFragAct`<br>pager: **count=33, current=2** ✅ 可滑动 |
| **B** | 策略详情 `AIStkStgDetailAct`<br>→ `onItemClick` | `router_ai_stk_detail` | `adapter_key` = Bundle{ `stockItem` = StockInfo, **`stockList`** = `ArrayList<StockInfo>` ×7 } | **5** | `AIStkDetailFragAct`（中间页） |
| **C** | 中间页「标准行情」<br>→ `AIStkDetailFragAct#onClick` | `quotes.stockDetail` | `adapter_key` = Bundle{ `stockInfo` = `IntentStockInfo` ×1 }<br>**没有 `stockList`、没有 `stockIndex`** | **6** | `StockFragAct`<br>pager: **count=1, current=0** ❌ 不能滑动 |

### 1.2 关键证据（探针原样输出）

```
# A：自选列表 → 个股
========== LAUNCH ==========
来自页面: com.guosen.app.goldsun.ui.MainAct
  [LAUNCH] action = quotes.stockDetail  flags = 0x0
       extras(3):
         adapter_key : android.os.Bundle = Bundle{stockList=size=33 [IntentStockInfo{f10TabType= mStkCode=601328 mStkMarket=2 mStkSetCode=}, ...], }
         stockIndex : java.lang.Integer = 2
         node_level : java.lang.Integer = 2
       stack: com.guosen.engine.process.EngineFrag#startEngineActForResult:399
              <- EngineFrag#startEngineAct:379
              <- com.guosen.quotation.ui.frag.NewZxgGroupFrag$6#onItemClick:544
              <- com.guosen.quotation.view.StockListView#onItemClick:476

---------- VIEW[QUOTE] +1200ms ----------
  PAGER com.guosen.commonui.LockViewPager #vp_stocks
        adapter=com.guosen.quotation.adapter.StockFragAdapter count=33 current=2
```

```
# C：中间页「标准行情」 → 个股
========== LAUNCH ==========
来自页面: com.guosen.quotation.ui.aistk.AIStkDetailFragAct
  [LAUNCH] action = quotes.stockDetail  flags = 0x0
       extras(2):
         adapter_key : android.os.Bundle = Bundle{stockInfo=IntentStockInfo{f10TabType= mStkCode=301282 mStkMarket=1}, }
         node_level : java.lang.Integer = 6
       stack: com.guosen.engine.process.EngineAct#startEngineActForResult:359
              <- EngineAct#startEngineAct:346
              <- AIStkDetailFragAct#onClick:126

---------- VIEW[QUOTE] +1200ms ----------
  PAGER com.guosen.commonui.LockViewPager #vp_stocks
        adapter=com.guosen.quotation.adapter.StockFragAdapter count=1 current=0
```

**同一个 Pager、同一个 Adapter，`count` 从 33 变成 1 —— 这就是「能不能滑动」的全部原因。**

### 1.3 涉及的目标 App 类（实测类名）

| 角色 | 类名 |
|---|---|
| 点击来源 | `com.guosen.quotation.ui.aistk.AIStkStgDetailAct`（自实现 `AdapterView.OnItemClickListener`，栈里是 `#onItemClick:200`） |
| 中间页（要跳过） | `com.guosen.quotation.ui.aistk.AIStkDetailFragAct`（右上角 `#tv_top_right` 文案「标准行情」） |
| 目标页 | `com.guosen.quotation.ui.StockFragAct extends com.guosen.engine.process.EngineAct` |
| 翻页控件 | `com.guosen.commonui.LockViewPager` id=`vp_stocks` |
| 翻页适配器 | `com.guosen.quotation.adapter.StockFragAdapter` |
| 策略页股票列表 | `ListView` id=`lvStks`，适配器 `AIStkAdapter`（Activity 字段 `m_stkAdapter`） |
| 数据类（目标） | `com.guosen.android.quotes.data.IntentStockInfo`，字段 `mStkCode` / `mStkMarket` / `f10TabType` / `mStkSetCode`，**构造器 `(StockInfo)` / `(String,String)`** |
| 数据类（来源） | `StockInfo`（字段 `codeAndMarket` 等，simpleName = `StockInfo`） |
| 路由 | `com.guosen.engine.process.EngineAct#startEngineAct(EngineIntent)`；`EngineIntent extends Intent`，`getBundleExtra(String)` / `putExtra(String,Bundle)` |

---

## 2. 需要修改的相关「页面 / 组件 / 交互逻辑」

> 本模块是 LSPosed 模块，不改目标 App 源码 —— 以下每一项对应**一个 Hook 点**。

| # | 环节 | 现状 | 要改成 |
|---|---|---|---|
| 1 | **交互入口** | `AIStkStgDetailAct.onItemClick(AdapterView, View, position, id)` → 构造 EngineIntent(action=`router_ai_stk_detail`) 去中间页 | 同一入口，把 EngineIntent 换成 `quotes.stockDetail` + 行情同款 extras |
| 2 | **中间页** | `AIStkDetailFragAct` 被拉起来，用户再点右上角「标准行情」 | **不再出现**（第 1 步改了 action 就没人拉它） |
| 3 | **目标页数据** | `adapter_key` 只有单只 `stockInfo` → `StockFragAdapter` count=1 | 塞入策略的 7 只 `stockList` + `stockIndex` → count=7，可滑动 |
| 4 | **模块侧** | 无 | 新增 `Feature` + 配置键 + 设置项（见 §4） |

---

## 3. 实现方案

### 3.1 拦截点选择

有三个候选，**推荐 B**：

| | 挂钩点 | 评价 |
|---|---|---|
| A | `AIStkStgDetailAct#onItemClick`，不 `proceed()`，自己 new 一个 `EngineIntent` | 要自己构造 EngineIntent（`m_level` / `m_adapter` 等内部状态），**风险高** |
| **B** | **`EngineAct#startEngineAct(EngineIntent)` / `startEngineActForResult(EngineIntent,int)`，`proceed()` 之前改写 `EngineIntent`** | ✅ **推荐**：直接复用 App 自己构造好的 EngineIntent，只改 action 与 extras；路由交给 App 自己 |
| C | 让中间页正常出现，再代理点击「标准行情」 | 兜底方案：不碰 Intent 契约，但会闪一帧，且中间页仍会进入返回栈 |

### 3.2 核心改写逻辑（路线 B）

```
EngineAct.startEngineAct(EngineIntent ei) 被调用
  │
  ├─ this 是 AIStkStgDetailAct 吗？        ── 否 ─→ 原样放行
  ├─ ei.action == "router_ai_stk_detail" ? ── 否 ─→ 原样放行
  │
  ├─ val b = ei.getBundleExtra("adapter_key")          // 已含 stockItem + stockList(StockInfo×7)
  ├─ val stocks = b.get("stockList") as List<*>        // 7 只 StockInfo
  ├─ val idx = indexOf(b.get("stockItem"), stocks)     // 被点那只是第几个
  ├─ val list = ArrayList<Any>()
  │     stocks.forEach { list += IntentStockInfo(it) } // ★ 复用 App 自带构造器做类型转换
  ├─ val copy = Bundle(b)                              // ★★ 关键：先复制一份 Bundle
  ├─ copy.remove("stockItem")
  ├─ copy.putParcelableArrayList("stockList", list)    // Parcelable 优先，Serializable 兜底
  ├─ ei.putExtra("adapter_key", copy)                  // ★★ 写回**副本**，绝不碰原 Bundle
  ├─ ei.putExtra("stockIndex", idx)                    // ★ 顶层 extra，不在 Bundle 里
  ├─ ei.setAction("quotes.stockDetail")                // ★ 关键：不再去中间页
  └─ chain.proceed()                                    // 交给 App 路由 → StockFragAct（7 页可滑）
```

> ⚠️ **必须写 Bundle 副本，不能原地改。** `adapter_key` 里那个 `stockList`
> 与宿主 `AIStkStgDetailAct` 的列表适配器（`AIStkAdapter`）**是同一个 `ArrayList` 对象**。
> 一旦对它 `clear() + addAll()` 原地换成 `IntentStockInfo`，
> 宿主第二次点击时会按 `StockInfo` 强转 → `ClassCastException` → **页面崩溃**。
> 这正是 2.12.1 修掉的 bug（真机 `logcat -b crash` 坐实的根因：
> `AIStkStgDetailAct.onItemClick:196`）。**改这段代码前先把这条读三遍。**

**为什么这条路径成立**：`EngineAct` 是按 **action** 查路由表的（`router_ai_stk_detail` → `AIStkDetailFragAct`，`quotes.stockDetail` → `StockFragAct`）。
把 action 换掉，目标页自然就变了；再补上行情页那三个 extra 的语义，`StockFragAdapter` 就会拿到 7 条数据、pager 就有 7 页。

### 3.3 实现要点与坑

1. **`stockIndex` 必须补，且它是外层顶层 extra**（与 `adapter_key` 平级，不在 Bundle 内）。
   实探输出里三个 extra 是并列的：`adapter_key` / `stockIndex` / `node_level`。写进 Bundle 里读不到。
2. **`node_level` 暂不动**（策略侧恰好是 5，行情侧是 2）。它是引擎返回栈的层级记账，
   先保持原值；若实测发现返回行为异常，再改（候选：沿用原值 / 对齐行情侧 2）。
3. **`stockList` 的存储类型**：行情侧读出来是 `ArrayList`。Bundle 里既可能用
   `putParcelableArrayList` 也可能用 `putSerializable`。**按 `putParcelableArrayList` 优先、
   `putSerializable` 兜底**（见 `StgDirectQuoteFeature` 里那两处 `runCatching` 回退分支）。
4. **绝不原地改写宿主持有的集合**（2.12.1 起）：`stockList` 与宿主适配器的数据是
   **同一个对象**，必须 `Bundle(b)` 复制一份改写再 `putExtra` 回去。同一条规则也适用于
   「关闭左右滑动」时的单只契约 —— 写 `adapter_key.stockInfo` 时要清掉副本里残留的
   `stockList`，否则行情侧会读到过期的策略列表。
5. **`IntentStockInfo` 的构造器要按类型探测**，不能写死：
   ```kotlin
   val ctor = isiClass.declaredConstructors.firstOrNull { c ->
       c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(item.javaClass)
   }
   ```
   找不到就退到 `(String code, String market)` 构造器，从 `codeAndMarket` 拆出来。
6. **索引匹配：在转换过程中记录下标，别事后匹配字符串**（2.12.1 定稿）。
   遍历原始列表做 `StockInfo → IntentStockInfo` 转换时，元素就在手上，
   用**对象引用**（`e === clicked`）判断"这条就是被点的那只"，并记下它在 `converted` 里的下标。
   兜底才退到「在**原始列表**里按 `codeAndMarket` 找下标，再映射回 `converted`」。
   ⚠️ **不能拿 `codeAndMarket` 去 `converted` 里 `indexOfFirst`**：`converted` 的元素是
   `IntentStockInfo`，它**没有** `codeAndMarket` 字段，会退化成 `mStkCode` + `m_stkMarket` 拼接；
   一旦与 `StockInfo.codeAndMarket` 的市场号表示不一致就返回 `-1` → 下标被兜底成 `0`
   → **点第 5 只却打开第 1 只**。这类 bug **不崩、不报错**，只进错股票，比崩溃更难发现。
7. 钩子里的异常必须 `runCatching` 吞掉，绝不抛给宿主（同 `WatchlistFeature`）。

### 3.4 降级链

`直达 + 滑动` → `仅直达`（不改 stockList，只改 action；进去仍是单只）→ `路线 C 代理点击` → 功能整体关闭。

---

## 4. 模块侧改动清单

| 文件 | 改动 |
|---|---|
| `feature/strategyquote/StgDirectQuoteTargets.kt`（新增） | 目标 App 类名 / action / extra key 候选常量 |
| `feature/strategyquote/StgDirectQuoteFeature.kt`（新增） | 功能本体：挂 `EngineAct.startEngineAct*`，改写 EngineIntent |
| `feature/FeatureManager.kt` | `features` 列表加一行 |
| `core/ConfigKeys.kt` | 加 `STG_DIRECT_QUOTE` / `STG_QUOTE_SWIPE` + 默认值 + 进 `DEFAULTS` |
| `core/ConfigSnapshot.kt` | 加两个字段 + 进 `fingerprint` |
| `core/ConfigSource.kt` | `read()` 补两行 |
| `ui/FeatureCatalog.kt` | 加一条 `FeatureSpec` |
| `ui/StrategyQuoteDetail.kt`（新增） | 二级菜单（2.11.2 起只剩「左右滑动」一项，总开关不在这里重复） |
| `ui/SettingsActivity.kt` | `Route` 加一个 `data object` + `when` 分支 |
| `res/values/strings.xml` + `values-en/strings.xml` | 中英文案（副标题按状态取串，沿用 `_on/_off` 约定） |

**不动**：`GszxModule.kt`、`WatchlistFeature`、`ConfigKeys.GROUP`、`scope.list`。

### 4.1 常量骨架

```kotlin
object StgDirectQuoteTargets {
    const val TARGET_PKG = TargetApp.PACKAGE

    /** 点击来源（判定 `this` 用） */
    const val CLS_STG_DETAIL = "com.guosen.quotation.ui.aistk.AIStkStgDetailAct"
    /** 引擎路由基类（挂钩子用） */
    const val CLS_ENGINE_ACT = "com.guosen.engine.process.EngineAct"
    /** 数据类：自带 (StockInfo) 构造器，用它做类型转换 */
    const val CLS_INTENT_STOCK_INFO = "com.guosen.android.quotes.data.IntentStockInfo"

    /** 策略页去中间页的 action（命中它才改写） */
    const val ACTION_TO_MIDDLE = "router_ai_stk_detail"
    /** 行情页进个股详情的 action（改写成它） */
    const val ACTION_STOCK_DETAIL = "quotes.stockDetail"

    /** 要挂的引擎跳转方法（候选数组，App 升级后加一项即可） */
    val LAUNCH_METHODS = arrayOf("startEngineAct", "startEngineActForResult")

    /** extras key（Bundle 名 + 各条目名） */
    const val KEY_ADAPTER = "adapter_key"
    const val KEY_STOCK_LIST = "stockList"
    const val KEY_STOCK_ITEM = "stockItem"
    /** 关掉左右滑动时走行情侧的单只契约 */
    const val KEY_STOCK_INFO = "stockInfo"
    const val KEY_STOCK_INDEX = "stockIndex"

    /** 判定「这只股票」用的字段候选（沿用 WatchlistTargets 的候选数组风格） */
    val CAM_FIELDS = arrayOf("codeAndMarket", "m_codeAndMarket")
    val CODE_FIELDS = arrayOf("m_stkCode", "mStkCode", "code", "mCode")
    val MARKET_FIELDS = arrayOf("m_stkMarket", "mStkMarket", "market", "mMarket")
}
```

### 4.2 功能骨架

```kotlin
class StgDirectQuoteFeature : Feature {

    override val id = "stg_direct_quote"
    override val title = "策略详情直达标准行情"

    override fun isEnabled(cfg: ConfigSnapshot): Boolean = cfg.stgDirectQuote

    override fun install(env: FeatureEnv, bag: HookBag) {
        val cls = Reflect.loadClass(StgDirectQuoteTargets.CLS_ENGINE_ACT, env.classLoader) ?: run {
            GLog.w("找不到 ${StgDirectQuoteTargets.CLS_ENGINE_ACT}，功能未安装")
            return
        }
        var hooked = 0
        for (m in Reflect.methodsNamed(cls, "startEngineAct") +
                 Reflect.methodsNamed(cls, "startEngineActForResult")) {
            bag.add(
                env.xposed.hook(m).setId("stgquote:${m.name}").intercept { chain ->
                    runCatching { rewrite(env, chain.thisObject, chain.args) }
                        .onFailure { GLog.w("EngineIntent 改写失败", it) }
                    chain.proceed()     // 改写后再放行，让 App 自己路由
                }
            )
            hooked++
        }
        if (hooked == 0) GLog.e("直达标准行情：没有任何钩子装上")
    }

    /** 命中「策略详情 → 中间页」时，把 EngineIntent 改写成「行情页 → 个股详情」 */
    private fun rewrite(env: FeatureEnv, self: Any?, args: List<Any?>) { /* §3.2 的逻辑 */ }
}
```

---

## 5. 风险与降级

| 风险 | 概率 | 对策 |
|---|---|---|
| `stockList` 存储类型猜错（Parcelable vs Serializable） | 中 | 真机一轮即可判定；两种都试 |
| `node_level` 不改导致返回行为异常 | 低 | 保持原值；异常再对齐行情侧 |
| `IntentStockInfo(StockInfo)` 构造器被 SOPHIX VM 化 | 低 | 退到 `(String,String)` 构造器，自己拆 `codeAndMarket` |
| App 升级后 action / key 改名 | 定期 | 全部进候选数组；按 `docs/TROUBLESHOOTING.md` 重跑探针 |
| 影响原版稳定性 | — | 所有改写包在 `runCatching` 里；异常绝不外抛 |

---

## 6. 验收标准

1. 策略详情页点股票 → **不再出现** `AIStkDetailFragAct` → 直达 `StockFragAct`。
2. `#vp_stocks` 的 **count = 策略股票数**（本例 7），`current` = 被点那只。
3. 左滑 / 右滑在策略的股票之间切换，顺序与策略列表一致。
4. **连续点击 ≥ 5 只不同股票，每次都直达、不崩溃**（2.12.1 的验收项；
   修复前同操作**必然在第 2 次**抛 `ClassCastException` —— 见 §3.2 的警告）。
5. 行情 / 自选列表行为**零变化**（hook 只对 `AIStkStgDetailAct` 生效）。
6. 关掉开关 → 立即恢复原版行为（进中间页），无需重启目标 App。
7. 目标 App 无新增崩溃；release 包 `Lio/github/libxposed/api` 定义类 **0**。

---

## 7. 复现侦察的步骤（App 升级后照做）

```bash
bash tools/probe/build.sh                          # 手工构建探针
bash tools/adbw.sh install -r tools/probe/out/GoldSunProbe.apk
# LSPosed 里启用「GoldSun Probe」（作用域已由 staticScope 自动写入），然后：
bash tools/adbw.sh shell "am force-stop com.guosen.android"
bash tools/adbw.sh shell "monkey -p com.guosen.android -c android.intent.category.LAUNCHER 1"

bash tools/glog.sh mark                          # 记录日志游标
# 操作：自选列表点一只股票 → 记录「可滑动」契约
bash tools/glog.sh tail --module all | grep -A8 "LAUNCH"
bash tools/glog.sh mark
#       我的策略 → 任一策略 → 点股票 → 点「标准行情」 → 记录「不可滑动」契约
bash tools/glog.sh tail --module all | grep -A8 "LAUNCH"
```

对比两次 `LAUNCH` 的 extras，差异就是 §1.1 那张表。

**UI 自动化**（复现「首页 → 智能选股 → 我的策略」链路）：

```bash
bash tools/ui.sh list              # 列元素、拿坐标
bash tools/ui.sh tap "我的策略"     # 按文字定位并点击
bash tools/ui.sh tapxy 540 889     # 直接点坐标（策略里的股票行）
bash tools/ui.sh act               # 确认栈顶 Activity
```

> ⚠️ **dump 与 tap 必须放在同一次调用里**，否则界面滚动了坐标就漂。
> 相关环境坑（无线调试掉线、mDNS 抢设备、`adb pull` 路径被改写、
> LSPosed 不自动启用新模块、`modules_config.db` 是陈旧快照）
> 见 [`../tools/README.md`](../tools/README.md) 与
> [`.workbuddy/memory/2026-10-15.md`](../.workbuddy/memory/2026-10-15.md)。

---

## 8. 2.12.1 修复：二次点击闪退（`stockList` 别名污染）

### 8.1 症状

策略详情页点股票 → **首次**正常直达标准行情个股详情页；返回详情页后**第二次**点任意股票
→ **闪退回「我的策略」列表页**（策略详情页被系统回收，App 本身没挂）。

### 8.2 根因

宿主构造 `EngineIntent` 时，`adapter_key.stockList` 与 `AIStkStgDetailAct` 的列表适配器
（`AIStkAdapter`，Activity 字段 `m_stkAdapter`）**持有的是同一个 `ArrayList` 实例**。

早期实现为了「保留 Bundle 原存储方式」，对这个列表做了**原地** `clear() + addAll()`：

```
首次点击
  ├─ 策略页构造 EngineIntent，adapter_key.stockList === 适配器数据列表（同一对象）
  ├─ 我们的钩子在 startEngineAct 里对它 clear() + addAll(IntentStockInfo…)
  │     ⇒ 适配器数据当场被换成 IntentStockInfo
  └─ 跳转成功（本次读的是刚写进去的数据，所以"看起来正常")

返回详情页 → 第二次点击
  └─ AIStkStgDetailAct.onItemClick 从适配器取出 IntentStockInfo，按 StockInfo 强转
       ⇒ ClassCastException → 页面崩溃
```

真机 `logcat -b crash`：

```
java.lang.ClassCastException: com.guosen.android.quotes.data.IntentStockInfo
        cannot be cast to com.guosen.android.quotes.data.StockInfo
  at com.guosen.quotation.ui.aistk.AIStkStgDetailAct.onItemClick(AIStkStgDetailAct.java:196)
```

**"首次正常、第二次必崩"这个不对称现象，就是别名污染的特征指纹** —— 如果是 Intent 契约
本身写错，第一次就该失败。

### 8.3 改法

1. **新建 `ArrayList`** 承载转换结果，绝不碰宿主那个列表；
2. 连 `adapter_key` 这个 Bundle 也换成**副本**（`Bundle(bundle)`）再写回；
3. 索引改为**转换过程中按对象引用记录**（见 §3.3 第 6 条）；
4. 「关闭左右滑动」分支改走行情侧**单只契约**（`adapter_key.stockInfo`），并清掉副本里残留的 `stockList`。

> ⚠️ **只做第 2 步是不够的。** `Bundle(bundle)` 是**浅拷贝**：它的内部 `map` 是新的，
> 但每个 value **仍然指向同一个对象**。所以"复制 Bundle"本身并不隔离 `stockList`，
> 必须**同时**做第 1 步（新建列表）才真正隔离。这是本次最容易写错的地方。

### 8.4 验证结果（真机 2.12.1 / vc32）

真机：魅族 20 / Android 16 (SDK 36) / LSPosed 2.2.1 / 金太阳 8.6.0 (vc271)，
策略 `100e`（32 只股票）。

| # | 验证项 | 结果 |
|---|---|---|
| 1 | 连续点击 | **6/6 次**均直达 `StockFragAct`；每次返回都回到 `AIStkStgDetailAct`，**未再回落 `AIStkStgListAct`** |
| 2 | 落点正确性 | 点第 1/2/3/4/5/6 行，页面分别显示 `新华文轩 601811` / `澳弘电子 605058` / `航天南湖 688552` / `思特威-W 688213` / `华鑫股份 600621` / `大智慧 601519` —— **与所点行一一对应，无下标偏移** |
| 3 | 左右滑动（`stg_quote_swipe=1`） | 航天南湖 → 左滑 → 思特威-W → 左滑 → 华鑫股份 → 右滑 → 思特威-W，**顺序与策略列表一致** |
| 4 | 关闭滑动（`stg_quote_swipe=0`） | 进入后显示所点那只；**左滑两次仍停在同一只**（count=1，不可滑） |
| 5 | 宿主数据未污染 | 连点 6 次后返回，策略详情页 32 行名称 / 最新价 / 涨跌幅**全部正常** |
| 6 | 崩溃计数 | `logcat -b crash` 中 `ClassCastException` / `FATAL` **计数 0** |

