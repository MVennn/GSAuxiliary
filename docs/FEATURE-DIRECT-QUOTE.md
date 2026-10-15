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
  ├─ b.remove("stockItem")
  ├─ b.putParcelableArrayList("stockList", list)       // 优先「原地清空重填」，保留 Bundle 原存储方式
  ├─ ei.putExtra("stockIndex", idx)                    // ★ 顶层 extra，不在 Bundle 里
  ├─ ei.setAction("quotes.stockDetail")                // ★ 关键：不再去中间页
  └─ chain.proceed()                                    // 交给 App 路由 → StockFragAct（7 页可滑）
```

**为什么这条路径成立**：`EngineAct` 是按 **action** 查路由表的（`router_ai_stk_detail` → `AIStkDetailFragAct`，`quotes.stockDetail` → `StockFragAct`）。
把 action 换掉，目标页自然就变了；再补上行情页那三个 extra 的语义，`StockFragAdapter` 就会拿到 7 条数据、pager 就有 7 页。

### 3.3 实现要点与坑

1. **`stockIndex` 必须补，且它是外层顶层 extra**（与 `adapter_key` 平级，不在 Bundle 内）。
   实探输出里三个 extra 是并列的：`adapter_key` / `stockIndex` / `node_level`。写进 Bundle 里读不到。
2. **`node_level` 暂不动**（策略侧恰好是 5，行情侧是 2）。它是引擎返回栈的层级记账，
   先保持原值；若实测发现返回行为异常，再改（候选：沿用原值 / 对齐行情侧 2）。
3. **`stockList` 的存储类型**：行情侧读出来是 `ArrayList`。Bundle 里既可能用
   `putParcelableArrayList` 也可能用 `putSerializable`。**先按 `putParcelableArrayList` 试**，
   不通就换 `putSerializable`（真机一轮即可判定）。
4. **`IntentStockInfo` 的构造器要按类型探测**，不能写死：
   ```kotlin
   val ctor = isiClass.declaredConstructors.firstOrNull { c ->
       c.parameterTypes.size == 1 && c.parameterTypes[0].isAssignableFrom(item.javaClass)
   }
   ```
   找不到就退到 `(String code, String market)` 构造器，从 `codeAndMarket` 拆出来。
5. **索引匹配**：`stockItem` 与 `stockList` 里的元素是同一个对象引用（同一批数据），
   优先用 `===` 比引用，退而比 `codeAndMarket` 字符串。
6. 钩子里的异常必须 `runCatching` 吞掉，绝不抛给宿主（同 `WatchlistFeature`）。

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

    /** extras key（Bundle 名 + 三个条目名） */
    const val KEY_ADAPTER = "adapter_key"
    const val KEY_STOCK_LIST = "stockList"
    const val KEY_STOCK_ITEM = "stockItem"
    const val KEY_STOCK_INDEX = "stockIndex"

    /** 判定「这只股票」用的字段候选（沿用 WatchlistTargets 的候选数组风格） */
    val CAM_FIELDS = arrayOf("codeAndMarket", "m_codeAndMarket")
    val CODE_FIELDS = arrayOf("m_stkCode", "mStkCode", "code")
    val MARKET_FIELDS = arrayOf("m_stkMarket", "mStkMarket", "market")
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
4. 行情 / 自选列表行为**零变化**（hook 只对 `AIStkStgDetailAct` 生效）。
5. 关掉开关 → 立即恢复原版行为（进中间页），无需重启目标 App。
6. 目标 App 无新增崩溃；release 包 `Lio/github/libxposed/api` 定义类 **0**。

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
> [`.workbuddy-ai/memory/2026-10-15.md`](../.workbuddy-ai/memory/2026-10-15.md)。
