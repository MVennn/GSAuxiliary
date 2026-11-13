# 智能选股·我的策略聚合（聚合组）：设计、实现与验收要点

> **一句话**：用原生 `AIStkStgDetailAct` 页面加一层「聚合结果代理」，把多条原生策略（含条件成员、标签成员、口径成员）的股票并成一张看起来完全原生的列表；功能已于 2026-11-12 落地，设置放在模块设置页。

> **状态（2026-11-12）：已落地。** 代码在 `feature/stgmerge/`（`StrategyMergeFeature` / `StrategyMergeTargets` / `StrategyMergeBridge` / `StrategyComboCatalog` / `StrategyComboClient` / `StrategyLabelTable` / `StrategyStgIndex` / `StrategyStgCatalog` / `StrategyMergeCache`），配置在 `core/StrategyMergeStore`（键 `stg_merge_groups`，JSON v1），界面在 `ui/StrategyMergeScreen` 与 `ui/StrategyMergeEditScreen`，宿主策略清单由 `core/StgCatalogBroadcast` + `app/HostStgCatalogReceiver` 收下（`core/HostSession` 装配）。逐日修复过程留在 [CHANGELOG.md](../CHANGELOG.md)，本文只记现状。

> 配套：宿主会话与请求体回填在 `core/HostSession`，主线程卡死看门狗在 `core/MainThreadStallWatchdog`，宿主策略清单在 `app/HostStgCatalog` + `app/HostStgCatalogReceiver`，涨跌幅时间线裁决在 `feature/filter/PercentTimeline`。
>
> 逆向结论正本在 [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md)，架构正本在 [ARCHITECTURE.md](ARCHITECTURE.md)，扩展正本在 [EXTENDING.md](EXTENDING.md)，界面规范正本在 [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md)，行勾选正本在 [FEATURE-ROW-SELECTION.md](FEATURE-ROW-SELECTION.md)；本文只做汇总、引用与方案对比，不另立正本。

---

## 1. 结论先行

| 项 | 结论 |
|---|---|
| 用户要的 | 一个聚合组当一条策略用：出现在「我的策略」列表里，点进去是原生详情页、原生行、原生菜单，股票是若干条策略的并集 |
| 机制 | 改 `AIStkStgDetailModel.m_stg.id` → 主线程 `setBizCommand(biz_type_for_cmd_biz_get_stg_data)` + `biz.getStg()` 让宿主自己重取 → 按 `codeAndMarket` 保序并集 → 写回 `AIStkAdapter.data` + `notifyDataSetChanged()` |
| 三条硬结论 | ① 取数不用自己组包（鉴权、`openapi_sign`、自研 `com.guosen.androidnet.tcpb.*` 全用宿主的）；② 渲染不用换适配器（`data` 就是渲染真源）；③ 点击 / 全选 / 直达行情天然正确，但必须以「并集写的是宿主自己的 `StockInfo` 实例」+「宿主取值列表被临时补齐」为前提 |
| 呈现路线 | A（结果页数据源 merge）为主；B（自写代理适配器）只在需要行级过滤、不能碰宿主 `data` 时启用 |
| 静态实证的边界 | 8.7.5 宿主包 static 拆包成功但 `class_count=53` 全是加固壳类，业务类名 / 方法 / 资源 id 一律以运行时探针为准 |
| 现状（2026-11-13） | 条件成员（`cond:`）、标签成员（`label:`）、宿主广播清单、单槽轮转、缓存秒套、一步到位、家数重数优先、逐只数据核对全部落地 |

---

## 2. 需求还原

用户原话的四个硬点：

1. **入口在「我的策略」列表里**：聚合组要像一条普通策略卡一样出现在列表页，能点进去。
2. **进去必须是原生详情页**：页面完全调用原生的页面实现，行视图、标签栏、菜单、多选、滑动、直达行情都照旧 —— 不要自绘一张「像原生」的表。
3. **内容是若干条策略的并集**：按 `codeAndMarket` 去重，可跨市值档、跨条件维度；同一只票只出现一次。
4. **头部与计数要跟着聚合走**：策略名称、「策略个股 N 只」、涨跌家数、标签条、日涨跌幅都得按并集重算，不能被宿主刷回单策略的值。

收尾口径：功能开关默认关；关掉即回原生单策略页；配置全在 remote prefs，删掉即回退。

---

## 3. 宿主事实

宿主是加固过的原生 App，本节只记「能用来做聚合」的那部分事实：页面链路、取数契约、可复用资源、静态拆包到哪一步、以及真机探针实测到的运行时形态。

### 3.1 页面链路

`MainAct` → `AIStkIndexAct`（智能选股首页）→ `AIStkStgListAct`（我的策略列表）→ `AIStkStgDetailAct`（策略详情）。链路与三段路由 action 见 [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md):114-125、:384-388 与 [FEATURE-DIRECT-QUOTE.md](FEATURE-DIRECT-QUOTE.md):388；8.7.5 manifest 里三个 Activity 均为 `exported=false` + `portrait`。

### 3.2 数据源与接口契约

| 项 | 值 |
|---|---|
| 详情取数 | `POST /gsnews/strategy/queryStrategyQuoteDetail/1.0`，Host `goldsundg.guosen.com.cn`，Content-Length 382 |
| 业务参数 | 只有一个 `stId`；零分页参数；每条策略服务端上限 100 只 |
| 跳转载荷 | `adapter_key.stage=Strategy{id,name,tags=[labelId…]}` |
| 网络栈 | 宿主自研 `com.guosen.androidnet.tcpb.*` + Conscrypt，自己组包成本高、加固壳改包更脆 |
| 改标签 | `POST /gsnews/strategy/updateStrategy/1.0`（`stId` / `labelId` 逗号分隔 / `stName` 可选），~60 ms 生效 |
| 策略清单 | `POST /gsnews/strategy/queryStrategy/1.0`（`pageNo` / `pageSize`），所有策略塞在 `data[0].member` 字符串化数组里 |
| 结果对象 | `AIStkAdapter` 继承宿主 `BaseQuickAdapter`，字段 `data` 是渲染真源；Activity 字段 `m_stkAdapter` 与 `ListView` 里那层 `getWrappedAdapter()` 是同一对象 |

### 3.3 可复用资源与控件

| 常量 | 值 / 说明 |
|---|---|
| `CLS_BASE_QUICK_ADAPTER` / `CLS_STOCK_INFO` / `TARGET_ADAPTERS` | 适配器基类、行对象类、候选适配器类名 |
| `LAYOUT_DETAIL` = `act_ai_stk_stg_detail`、`LAYOUT_EMPTY` = `empty_ai_stk` | 详情页与空态页布局 |
| `COL_TITLE_ID` = `ll_top` | 列标题行（名称 / 最新价 / 涨跌幅），是 `ListView` header 末尾的普通 View，不是 adapter item |
| `lvStks`（id `2132479882`） | 股票列表 `ListView`，外层包 `HeaderViewListAdapter`（2 个 header） |
| `MENU_ANCHOR_ID` = `tv_del_stg`、`MENU_EDIT_NAME_ID` = `tv_edit_name` | 菜单锚点与改名入口 |
| `RES_NUM_ID_CANDIDATES` = `tv_stg_res_num` / `tv_stg_res_time` / `tv_stg_add_zxg` | 计数栏候选 id（「策略个股 N 只」+ 时间戳） |
| `PERCENT_ID_CANDIDATES` = `tv_item_stk_percent` / `ct_item` | 行内涨跌幅文本候选 |
| `CAM_FIELDS` = `codeAndMarket` / `m_codeAndMarket`、`CODE_FIELDS` / `MARKET_FIELDS` / `NAME_FIELDS` | 去重键与行身份字段；去重键形如 `300006.1` |
| `PERCENT_FIELDS` | 11 项候选，含 `zdf`（`BigDecimal`），定义在 `feature/stgfilter/StgStockFilterTargets.kt:199`；另有 `STOCK_PCT_FIELDS`（`feature/stgmerge/StrategyMergeTargets.kt:392`，同为 11 项）供 `pctOf` 逐项试读 |
| `TYPE_FLAG_FIELDS` = `isCyb` / `isKCPlate` / `isXXLD` | 板块标记 |
| 行高 138px | 一屏约 10 行；底部 `ll_add_zxg_bottom` / `tv_bottom_zxg_num` / `tv_bottom_add_zxg` |

### 3.4 静态拆包边界

- 8.6.0（versionCode 271）：梆梆 SecNeo 加固（`AW` / `AP` / `CP` + `libDexHelper.so`），`97,392,176` 字节里 `class_defs=53`。
- 8.7.5：包 `150,953,313` B，`SHA256 39B1B20D…CE6A89`；静态拆包（`--format json`）跑通后实得 `class_count=53` / `resource_count=11789` / `version_code=275` / `min_sdk=21` / `target_sdk=30`；`AIStkAdapter` / `StockInfo` / `queryStrategyQuoteDetail` / `IntentStockInfo` 全部零命中。
- 能证的只有组件声明与三段路由 action（`router_ai_stk_index` / `router_ai_stk_stg_list` / `router_ai_stg_stk_detail`）；`limitations[0]` 原文「Static decompiler observations do not establish runtime behavior; the APK is never executed.」。
- 工具侧：`REA_ADB_PATH` 与 `REA_APKTOOL_COMMAND` 未配置，`decode-android-resources` 关闭（资源表解码不参与结论）；`rea doctor` 的 `config_drift` 属预期诊断输出。

### 3.5 详情页运行时实证（MuMu 12 / 宿主 8.7.1 / LSPosed 2.2.1，2026-11-11）

| 项 | 实测值 |
|---|---|
| 页面 / 模型 | `com.guosen.quotation.ui.aistk.AIStkStgDetailAct`；`com.guosen.quotation.model.aistk.AIStkStgDetailModel`（**0 个自有方法**，逻辑全在 `EngineModel` / `AutoModel` / `BasicModel`） |
| 模型字段 | `Strategy m_stg`、`ArrayList m_stkList`、`List m_tagList`、`int upNum / downNum / flatNum`、`EngineBiz m_biz`、`Map m_methodMap`、`Map m_paramMap` 等 |
| `Strategy` 字段 | `id / name / tags / leadStkCode / leadStkName / leadStkPrice / leadStkZdf / zdf` —— **没有「股票数量」字段**，「策略个股 N 只」不由它算 |
| Biz | `AIStkStgDetailBiz` 只有 `mCanCommit / m_bizErrorJoey / m_handler / m_handlerCallback / m_model / m_modelCallback` —— **不持股票数据** |
| 命令表 | `EngineModel.m_methodMap` 15 条：取数 `biz_type_for_cmd_biz_get_stg_data -> getStg()`、渲染 `view_type_for_cmd_view_show_stg_data -> showStg()`，另有 `autoRefresh / showStkQuote / delStg / updateStg / delTag / getExplain / updateName / delStgOK / delTagOK / showExpLain / proceeErrorBack / proceeBizErrorBack / dismissRequestDialog` |
| 适配器 | `com.guosen.quotation.adapter.aistk.AIStkAdapter`，CTOR `(Context, List)`，字段 `data`（渲染真源）/ `priceMap`（key 形如 `688135.2`）/ `showChoice` / `showFlashEffect` / `layoutResId=2132673931`；方法 `convert(BaseAdapterHelper, StockInfo)` / `setShowChoice(boolean)` |
| 列表容器 | `ListView`（id `2132479882`），`getAdapter()` 返回 `HeaderViewListAdapter`（含 2 个 header），`getWrappedAdapter()` 才是 `AIStkAdapter`，且与 `m_stkAdapter` 同一对象 |
| 行对象 | `com.guosen.android.quotes.data.StockInfo`，稳定去重键 `codeAndMarket`（实测 `688135.2`），另有 `marketCode` |

**实验一（换 `stId` 让原生链路重取整页）**：取 `model.m_stg.id`（实测 `1630342260686065664`）→ 主线程 `setBizCommand(biz_type_for_cmd_biz_get_stg_data)` 再反射直调 `biz.getStg()` → `POST /gsnews/strategy/queryStrategyQuoteDetail/1.0` 的 `stId` 变成 `1569198903482388480` → 3 秒后 `m_stkList=47`、`upNum=40`、`downNum=7`。工作线程发命令无效（全 logcat 只有 1 次请求、列表仍 17 只）。

**实验二（把并集写回适配器让原生列表渲染聚合结果）**：首屏快照 `data`(17) + `priceMap`，重取第二页后 `data`(47)，按 `codeAndMarket` 做 `LinkedHashMap` 保序并集得 64 项（两段市值零交集）→ 主线程往 `getWrappedAdapter()` 写 `data=64` + 合并 `priceMap` + `notifyDataSetChanged()` → 日志 `ListView n=66`（64 行 + 2 header），两段市值混排。

五条实测结论：

1. **取数不用自己组包**：改 `m_stg.id` + 主线程 `biz.getStg()`，宿主用自己的 `session / openapi_sign` 重取任意 `stId`，拿到的就是原生 `StockInfo`。
2. **渲染不用换适配器**：`AIStkAdapter.data` 是渲染真源，写回 + `notifyDataSetChanged()` 后行视图、排版、滚动全由宿主自己画。
3. **头部文案分两类**：`m_stg.name` / `upNum` / `downNum` 走模型字段、`showStg()` 会跟；「策略个股 N 只」不跟 `data`，必须由模块按聚合结果自填 `tv_stg_res_num`。
4. **价格列跟 `priceMap` 走**：只写 `data` 不带 `priceMap`，第二段的「最新价」会缺。
5. **点击语义天然正确**：`data` 里是宿主自己的 `StockInfo` 实例，`onItemClick` 取到同一对象；但宿主刷新会整表替换 `data`，并集要重放。

探针踩坑（复现必看）：`AIStkStgDetailModel` 在 `onPackageReady` 阶段还没被加固类加载器装载，按类名 dump 会 `ClassNotFoundException`，必须在页面 `onResume` 后用运行时的 `m_model` 反查；探针 APK 重装后 LSPosed 的「启用模块」开关可能被关掉，要回管理器重开；命令一律 `runOnUiThread`。

---

## 4. 模块事实

模块侧现成的通道与模板足以承载聚合配置、设置页与跨功能结果共享，本节记这些可复用的地基。

### 4.1 进程模型与配置通道

[ARCHITECTURE.md](ARCHITECTURE.md):8-45：同一 APK 两套代码两进程 —— 模块进程 `io.github.MVennn.GSAuxiliary`（`ui/**` 设置界面）与目标进程 `com.guosen.android`（`GszxModule.kt` → `FeatureManager` → 各 `feature/**`）。写侧 `XposedService.getRemotePreferences("config")`，读侧 `XposedInterface.getRemotePreferences("config")`，由 LSPosed 守护进程托管持久化 + 跨进程同步 + 变更推送。

[GszxModule.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/GszxModule.kt):36-145：`onPackageReady` 判包名 → `install(classLoader)`；「先绑 `configSource.onChanged` 再 `configSource.open()`」→ `env.config = configSource.snapshot` → `manager.installAll()`。[FeatureManager.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/FeatureManager.kt)：`features` 20 个（19 个用户功能 + `HostNetProbeFeature`），`installAll()` 半装失败 `bag.rollbackTo(mark)`，`uninstallAll()` 先 `allOffSnapshot()` 再 `bag.unhookAll()`。[Feature.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/Feature.kt):1-51：`interface Feature`（`install` / `isEnabled` / `onConfigChanged` / `onActivityDestroyed`），约定钩子句柄一律进 `bag`。

### 4.2 设置页现成模板

| 层 | 现成实现 | 出处 |
|---|---|---|
| 数据层 | `object QuickConfigStore`：remote prefs 键 `aistare_quick_config`，`FORMAT_VERSION = 1`，`EXTRA_ROUTE = "route"` / `ROUTE_QUICK_CONFIG = "aistare_quick_config"`，容错四原则「整体坏回落出厂 / 单条坏丢该条 / source 不认识丢弃 / 全坏回落默认」 | [core/QuickConfigStore.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/QuickConfigStore.kt) |
| 路由 | `sealed interface Route` + `RouteSaver : Saver<Route, String>` + `onOpenFeatureDetail when (id)` | [ui/Routing.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/Routing.kt):32-51、:63-95、:271-284、:323-394 |
| 开关与入口 | `FeatureTab(onOpenDetail)`：`GsSwitchPreference` + `AnimatedVisibility` 展开 `BasicComponent` 入口行 | [ui/FeatureTab.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/FeatureTab.kt):39-123 |
| 展示元数据 | `FeatureSpec` / `FEATURE_CATALOG`；`ui/**` 侧引用 `FeatureManager` 会把 `io.github.libxposed.api` 带进模块 App 进程触发 `NoClassDefFoundError`，故界面自维护元数据 | [ui/FeatureCatalog.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/FeatureCatalog.kt):77-82、:84-118、:121 |
| 行型与弹层 | 可配置列表逐条行 + 行尾 `✕` 删单条 + 新建弹层 + 清空 `ConfirmDialogSpec`；贴底弹层 ≤420dp | [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md):183-247（§3.7 在 :228、§3.8 在 :248）、:361-432（§5.3 在 :370） |

### 4.3 五处齐补与 fingerprint 位预算

[core/ConfigKeys.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/ConfigKeys.kt)：`ALL_KEYS`（39 键）、`FEATURE_KEYS`（22 键）、`FEATURE_PARAM_KEYS`（10 键）、`SNAPSHOT_KEYS`（33 键）、`DEFAULTS` 五个表；[core/ConfigSnapshot.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/ConfigSnapshot.kt)：33 字段 + `fingerprint`；[core/ConfigSource.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/ConfigSource.kt)：变更监听用 `if (key != null && key !in ConfigKeys.SNAPSHOT_KEYS) return@OnSharedPreferenceChangeListener` 挡无关键。

⚠️ `SNAPSHOT_KEYS` 漏补 → 目标进程静默收不到变更。⚠️ `fingerprint` 现有 28 个布尔位占 0..27，Int 32 位只剩 4 位，再加布尔项 `bit shl 1` 在第 32 位溢出成 0 会让两个开关互相串 —— 所以聚合配置走**一个 JSON 字符串键**（照 `AISTARE_QUICK_CONFIG` / `ZX_COPY_RULES`），布尔量收进 JSON，不再吃位。

[EXTENDING.md](EXTENDING.md):40-143 四条架构红线：manifest 零 `xposedminversion`；`compileOnly("io.github.libxposed:api:102.0.0")` + `implementation("io.github.libxposed:service:102.0.0")`；模块 App 进程加载的类零 `import io.github.libxposed.api.*`；钩子一律 `bag.add(env.xposed.hook(m).setId("xxx").intercept { chain -> … })`。

### 4.4 跨功能共享结果的现成桥

[feature/stgfilter/StgFilterBridge.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgfilter/StgFilterBridge.kt)：`class Snapshot(kept / keptIndices / source / owner)`，元素身份用 `===`，快照绑定到「算出它的那个列表实例」；`publish` / `fullIndexAt` / `fullIndices(count, owner)` / `keepOnly` 走 `IdentityHashMap`，`runWithRemapBypassed` 用 `ThreadLocal<Boolean>`；`fullIndices` 长度必须与列表行数一致，任何一项缺失返回 `null`。

[FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md) §五「行过滤」里的 `FilteredAdapter` 是关键：**不换宿主 `data`**，给 `ListView` 套一层代理，`getCount()` 返回子集行数、`getView` / `getItem` 用全量下标转调原 adapter。真机坑：宿主 `AIStkStgDetailAct.onItemClick` 拿 `adapter.getCount()` 做范围校验却用自己那份全量列表取值，直接改 `data` 会让「全量下标 ≥ 筛选后行数」的行点不动（日志「筛选股票：全量 35 → 保留 1」「点击 position=1 → 映射成全量下标 2 转发给宿主」「页面一动不动」）。下标回映射 `mapped = header + keptIndices[position - header]`。

---

## 5. 四条路线对比（结论）

| 路线 | 结论 |
|---|---|
| **A 结果页数据源 merge** | **选定**（呈现）。挂 `BaseAdapter.notifyDataSetChanged` 前置 + 写 `AIStkAdapter.data`；§3.5 实验二已实证 `ListView n=66` 渲染两段市值混排，「点击下标错位」只在自写代理让 `getCount()` 与 `data` 不一致时才出现，直接写 `data` 时适配器与宿主列表同源、行对象同一实例，天然对齐 |
| **C 按成员 `stId` 逐条取原生结果** | **选定**（取数）。每包独立超时与重试，单包失败保留其余成员结果 |
| **B 原生页 + 代理适配器** | **降为可选件**：只在需要行级过滤、不能碰宿主 `data` 时启用（照 `FilteredAdapter` 骨架） |
| **D 服务端 / 接口层聚合** | **否决**：撞加固壳与 `openapi_sign` + `userCode / session` 鉴权链，改包变脆、改动面最大 |

---

## 6. 现行方案设计

选定 C（按成员取原生结果）+ A（结果页数据源 merge）后的落地设计，六节分别管配置、界面、目标进程功能、下标契约、成员来源、取数与回退。

### 6.1 数据模型（`core/StrategyMergeStore.kt`）

remote prefs 一个 JSON 字符串键 `stg_merge_groups`，v1：

```json
{"v":1,"groups":[{"id":"g1","name":"市值50-500e + 创新高20日","enabled":true,"members":[{"stId":"<ID_A>","name":"流通市值 50-70e"},{"stId":"<ID_B>","name":"流通市值 100-500e"}],"dedupKey":"codeAndMarket","order":"member"}]}
```

`members.stId` 是宿主 `queryStrategyQuoteDetail` 的唯一业务参数；`name` 只用于设置页展示与计数文案；`dedupKey` 固定 `codeAndMarket`（与 `ZXManager.m_stkGrpMap` 的 `code.market` 同构）。解析照 `QuickConfigStore.decodeOrNull` 做版本闸门 + `seenIds` 去重 + 坏数据回落。

### 6.2 设置页

- 「功能」页 `category_strategy` 分区一项 `stg_merge`：`GsSwitchPreference` 总开关，打开后展开一行入口「我的策略聚合设置入口」。
- 二级页 `StrategyMergeScreen`、三级页 `StrategyMergeEditScreen`，照 `QuickConfigScreen` / `QuickConfigEditScreen` 的三层路由加 `Route.StrategyMerge` / `data class Route.StrategyMergeEdit(val groupId: String)`，`RouteSaver` 的 `when` 必须补分支。
- 组列表按 [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md):201 的可配置列表行：逐条一行 `BasicComponent(title=组名, summary=条数 + 状态句)`，行尾 `✕` 用 `AppIcons.Close` 单条直删；底部「＋ 新建聚合组」用 `AppIcons.PlusThin` + `TextInputDialogSpec` 贴底弹层（:221、:316-427）；整批清空才走 `ConfirmDialogSpec`。
- 编辑页区块顺序：`聚合组`（名称 + 启用开关）→ `成员策略` → `从组合维度导入` → `从金太阳导入` → `从聚合组导入`。
- `FEATURE_CATALOG` 与 `FeatureManager.features` 两处都要登记。

### 6.3 目标进程功能

`feature/stgmerge/` 下 `StrategyMergeFeature`（实现 `Feature`，`bag.add(...)` 挂 `Activity.onResume` / `ListView.setAdapter` / `showStg` / `BaseAdapter.notifyDataSetChanged`）、`StrategyMergeTargets`（宿主类名 / 反射字段名 / 资源 id / 候选常量数组的唯一出处）、`StrategyMergeBridge`（进程级 `MergeSnapshot`，供其它功能读当前页并集）。首帧就要是聚合后的列表：宿主既会「把已填好数据的适配器直接 `setAdapter(...)`」也会走 `notifyDataSetChanged`，两条路都要挂。

### 6.4 下标契约

聚合后 `ListView` 眼里只有合并行数，宿主 `onItemClick` 仍按全量取值 → `mapped = header + fullIndices[position - header]`；`StgSelectAllFeature` 走 `MergeBridge.fullIndices`；`StgDirectQuoteFeature` 读 `adapter_key.stockList` 时 `keepOnly` 过滤。

**回映射解决不了「宿主根本没有股票」**：宿主 `AIStkStgDetailAct.onItemClick`（`AIStkStgDetailAct.java:196`）用 `adapter.getCount()` 做范围校验、却用**它自己那份** `AIStkStgDetailModel.m_stkList` 取值，聚合走「直接改 `adapter.data`」，`m_stkList` 没人写过、进页被清成 0 → 每次点击 `IndexOutOfBoundsException`，被行点击包装器 `runCatching` 吞成「点了没反应」。

现行处置：[MergeClickFill.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/MergeClickFill.kt) 在 `onItemClick` 进去那一刻，把 Activity 与 Model 上**元素类型与并集同类**的 List 字段临时换成并集副本，宿主读完立刻 `set` 回原引用（不 `notify`、不 `requestLayout`）。类型判据 `holdsSameType`：非空看首元素与并集元素的继承关系，空表读 `Field.genericType` 实参名 —— 把 `checkCodes` / `checkMarkets` / `checkPrices` / `m_bcList` 挡在外面，真机日志因此只剩一个字段「行点击前临时补上宿主取值列表：AIStkStgDetailModel.m_stkList(0→630)」。挂点 `hookDetailItemClick`：`chain.proceed()` 抛异常只记日志。

**写并集一律原地改，不换 `data` 实例**：`AbsListView` 在 `ACTION_UP` 按「这一帧数据换没换过」决定丢不丢点击，换实例会让「有些行点不动、越靠下越明显」。统一走 `StrategyMergeTargets.writeListInPlace`：`Reflect.findField` 取现值，是 `List` 就 `clear + addAll` 到同一实例；只有 `null` 空态（`ensureListData` 造新表）与现值不是可写 `List`（`Arrays.asList` / `unmodifiableList`）才换对象。连带：`captureOrigin` 的 `state.originalData` 必须存**副本**（`ArrayList<Any?>(it)`），否则 `releaseRollback` 会把并集当原生数据还回去。

**两条会「静默全废 / 直接崩溃」的坑**：

1. 反射缓存**不能**用 `java.lang.ClassValue`：Android libcore 没这个类，宿主进程启动即 `ClassNotFoundException: Didn't find class "java.lang.ClassValue"`，日志只留「功能安装完成：0/20，共 0 个钩子」。现用 [Reflect.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/util/Reflect.kt) 的「Class → 名字键（`f:` / `m:` / `n:` 前缀）→ 成员」`ConcurrentHashMap` 负缓存，热路径零锁零异常。
2. 往 `HeaderViewListAdapter` 内层塞适配器**必须重登记数据集观察者**：只反射改 `mAdapter` 的话 `AdapterDataSetObserver` 还挂在旧内层，页面销毁时 `AbsListView.onDetachedFromWindow` → `unregisterDataSetObserver` 顺新内层转发，抛 `IllegalStateException: ... was not registered` 带走宿主进程。正确做法是 `swapHeaderInner`：换前向旧内层注销、换完向新内层登记，观察者实例取自 `AbsListView.mDataSetObserver`。

### 6.5 成员 `stId` 的两种读法

| 读法 | 做法 | 现状 |
|---|---|---|
| 读法 1：从「我的策略」列表页勾选导入 | 挂 `AIStkStgListAct`，dump 适配器 / item 类型 / `stId` `name` `tags` 字段 | **已由 §8.7 的宿主广播清单取代**：不需要 hook 列表页，宿主自己把「页面上真实存在的策略」广播过来 |
| 读法 2：设置页手工登记 | 用户把 `stId` 抄进模块设置 | 仍支持；`stId` 可留空，由目标进程按名称从 `StrategyStgIndex.idOf` 现认 |

v1 数据层只在「名称与 `stId` 都为空」时才丢成员，组内按名称去重（名称空时按 `stId`），`stId` 空就照写空串。

### 6.6 取数、去重、刷新与回退

- 取数：沿用宿主 `queryStrategyQuoteDetail` 通道，按成员取 N 包；每包独立超时与重试，单包失败保留其余成员结果，计数标 degraded。
- 去重：按 `codeAndMarket` 用 `LinkedHashMap` 保序去重，保留首次出现成员的 `StockInfo` 实例（身份 `===`）。
- 排序：默认成员序（`order=member`），可选代码序 / 涨幅序；「涨幅」读 `PERCENT_FIELDS` 的 `zdf`，读不到解析 `tv_item_stk_percent` 文案兜底。
- 计数：`tv_stg_res_num` 与 `tv_stg_res_time` 由模块按聚合结果回填。
- 刷新：宿主 `notifyDataSetChanged` 时重算，结果与 `fullIndices` 一起原子替换；看门狗 700ms（`WATCH_INTERVAL_MS`）兜「改了 data 不 notify」。
- 回退：任一环节早退都摘代理、还原 `lvStks.adapter`、清零 `MergeSnapshot`；总开关关闭走 `onConfigChanged(false)`，热重载走 `uninstallAll()` → `bag.unhookAll()`。

---

## 7. 踩点结论与决策

踩点阶段的探针清单与三个待拍板决策点，已经全部收敛成现行口径 —— 下面写的是「现在为什么这么定」，过程留 [CHANGELOG.md](../CHANGELOG.md)。

### 7.1 列表页探针：原始五问的落点

| 原问题 | 现在的答案 |
|---|---|
| 列表控件与适配器全限定名 | 不再需要 hook 列表页：宿主侧在 `decorateListNow` 的采集循环里现算条目（名称 / stId / 标签同一趟拿），经 `StgCatalogBroadcast` 广播给模块进程 |
| 策略 item 的数据类型与字段 | 同上，命中 `Strategy{id,name,tags}`；`tags` 是 JSON 字符串数组，元素含 `labelId` / `labelName` / `isValid` |
| 跳转 `LAUNCH` extras 全文 | `adapter_key.stage=Strategy{id,name,tags=[labelId…]}`，`stId` 就在这里 |
| 列表页数据接口与分页 | 不需要：清单改由宿主广播，模块只按 `stId` 取数 |
| 策略对象能否跨页复用 | 不依赖：去重与归属一律按 `codeAndMarket` 与「算出它的那个列表实例」判 |

### 7.2 三个决策点的已定口径

| # | 决策 | 已定 | 为什么 |
|---|---|---|---|
| 1 | 聚合结果挂在哪个原生页 | **① 复用 `AIStkStgDetailAct` 结果页** | 最贴合「页面完全调用原生实现」，「看起来原生」由宿主自己画；② `AIStkStgListAct` 大类入口留作后续增强，③ 空壳 Activity 否决 |
| 2 | 成员 `stId` 来源 | **③ 两者并存**，且已升级为「宿主广播清单 + 手工登记 + 条件 / 标签 / 口径成员」 | 读法 2 打通闭环，读法 1 直接由广播实现（§8.7），不需要 hook 列表页 |
| 3 | 取数时机 | **② 模块侧预取缓存 + 进页读缓存，后台再校验**（不是纯 ① 并发拉 N 包） | 首屏第二眼就是完整并集：`StrategyMergeCache` 命中即 `applySnapshot` 秒套，1~3 秒的单策略列表不再露出；纯 ① 在 24 成员下要等 3.4 秒，③ 上限受单策略约束 |

### 7.3 踩点结论

模块改 `m_stg.id` → 主线程让宿主自己按新 `stId` 重取（鉴权、签名、协议全用宿主的）→ 按 `codeAndMarket` 保序并集 → 写回原生 `AIStkAdapter.data` + `notifyDataSetChanged()` → 原生 `ListView` 把两段市值策略的股票渲染成一张表，头部名称与涨跌家数同时生效。剩下的都是产品化工作，不是可行性工作；跨分段「50e–500e」本质上就是「多个 `stId` 成员的并集」。

---

## 8. 实现与验收要点

2026-11-11 至 11-13 的逐日落地，压成十组现行要点：代码位置、必须绕开的宿主行为、刷新接管、标签与 chip 合成、入口卡、条数家数口径、条件成员与单槽轮转、性能与稳定性、数据准确度、未复验项。

### 8.1 代码位置

| 文件 | 职责 |
|---|---|
| [feature/stgmerge/StrategyMergeFeature.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyMergeFeature.kt) | 接线与刷新接管：四处 hook（onResume / setAdapter / showStg / BaseAdapter.notify）+ 700ms 看门狗 + 重套与头部回填 |
| [feature/stgmerge/StrategyMergeTargets.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyMergeTargets.kt) | 宿主类名 / 反射字段名 / 资源 id 的唯一出处，`isPageAdapter` / `unwrapListAdapter` / `notifyAdapter` / `listField` / `writeListInPlace` / `countTier` / `pctOf` 等原语 |
| [feature/stgmerge/StrategyMergeBridge.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyMergeBridge.kt) | 跨功能发布聚合结果（供其它功能读当前页并集） |
| [feature/stgmerge/StrategyStgIndex.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyStgIndex.kt) | 策略列表页 → `stId` 索引（成员登记用） |
| [core/StrategyMergeStore.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/StrategyMergeStore.kt) | 组与成员的持久化 |
| [ui/StrategyMergeScreen.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/StrategyMergeScreen.kt)、[ui/StrategyMergeEditScreen.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/StrategyMergeEditScreen.kt) | 二级组列表页（新建 / 单条 ✕ / 清空）与三级组编辑页，按 [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md) 的可配置列表规范 |
| `feature/stgmerge/MergeClickFill.kt` | 行点击前临时补齐宿主取值列表（§6.4） |
| `feature/stgmerge/StrategyMergeCache.kt` | 结果缓存（LRU 8 / TTL 10 分钟，§8.8） |
| `feature/stgmerge/StrategyComboCatalog.kt` / `StrategyComboClient.kt` / `StrategyLabelTable.kt` | 口径 / 条件 / 标签成员的展开、取数、chip 反查（§8.4、§8.7） |
| `feature/stgmerge/StrategyStgCatalog.kt` + `core/StgCatalogBroadcast.kt` | 宿主侧组包广播，模块侧 `app/HostStgCatalogReceiver` 落盘（§8.7） |

### 8.2 必须绕开的宿主行为

| # | 宿主行为 | 真机现象 | 处理 |
|---|---|---|---|
| A | 数据未回来 / 刷新中会挂 `AIStkEmptyAdapter`，它把 `m_stkList` 每项当 `String` 强转 | `ClassCastException: com.guosen.android.quotes.data.StockInfo cannot be cast to java.lang.String`，进详情 9 秒崩 | **常态不写** `model.m_stkList`（空态下尤其），并集只写适配器 `data`；唯一例外是行点击那一下由 `MergeClickFill` 临时补、读完立刻还原（§6.4） |
| B | `showStg()` 是「按宿主自己的 `m_stkList` 重画整页」，不是纯刷新头部 | 头部重试里每调一次，列表就从 64 只回落成 47 只，6 秒内可见 | 头部 `postDelayed` 重试里**不再调** `showStg()`，只写「策略名称 / 涨家数 / 跌家数 / 策略个股 N 只」四处文本节点 |
| C | 宿主每轮刷新都会重写头部三处文本；也会**直接改 `data` 却不发 `notifyDataSetChanged`** | 名称回落「聚合测试」、家数回落 40/7、计数回落「47只」，而 `data` 里其实还是 64 项 | ① 头部写入放到「数据一致就早返回」之前（同值 `setText` 不闪）；② 前置 hook `BaseAdapter.notifyDataSetChanged` 抢在重画前重套；③ 700ms 看门狗兜住「改了 data 不 notify」 |
| D | 宿主在空态 / 刷新途中把列标题行 `ll_top` 隐藏成 `visibility=0` 并压成 0 高 | 聚合表铺了 630 行却看不到「名称 / 最新价 / 涨跌幅」表头 | 模块自己还原：`restoreColTitle` 每次都把可见性与高度写回去；`COL_TITLE_ABSENT = -2`（`StrategyMergeFeature.kt:73`）表示「还没量到过宿主原始高度」，量到一次就记住原值，避免拿量到的 0 反过来覆盖宿主 |

两条实测细节：

- `BaseAdapter.notifyDataSetChanged` 才是宿主真正用的实现：先挂 `com.guosen.commonui.adapter.BaseQuickAdapter` 得「notify×0」，换成 `android.widget.BaseAdapter` 才「notify×1」。
- 重套目标必须固定成「聚合时那一个适配器」（`state.adapterRef` 的 `WeakReference`），不能按视图树重找：详情页里除股票 `ListView` 还有标签栏 `RecyclerView`，重找会写到没人看的列表上（真机日志「重套成功」而界面纹丝不动）。

### 8.3 刷新接管清单

| 时机 | 钩子 | 动作 |
|---|---|---|
| 进页 | `Activity.onResume` 后置 | 首次聚合（`start`）；命中缓存先 `applySnapshot` 秒套 |
| 宿主挂适配器 | `ListView.setAdapter` 后置 | 认得出 `AIStkAdapter` 才聚合 / 重套，避免把 `StockInfo` 塞进空适配器；`setAdapterBusy` 只放第一层（§8.8） |
| 宿主重画 | `AIStkStgDetailAct.showStg` 后置 | 重套并集 + 回填头部 |
| 宿主改数据 | `android.widget.BaseAdapter.notifyDataSetChanged` **前置** | 抢在重画前把 `data` 写成并集（原地 `clear + addAll`） |
| 兜底 | 700ms `Handler` 看门狗（`WATCH_INTERVAL_MS`） | 巡检 `data` / 头部 / 标签 / 列标题行，不一致就纠回；`zdfOk` / `colTitleOk` 快路判据命中即跳过（`StrategyMergeFeature.kt:1566`）；页面销毁 `stopWatch` |

重套写**副本**（`ArrayList(merged)`）而不是并集本体：宿主刷新会对 `data` clear+addAll，交本体等于让宿主改掉私有快照（实测 64 只被改成 47 只）。换行集合之后再按代码对齐勾选态：`readCheckedKeys` 记下「勾着哪几只」→ `writeListInPlace` → `notifyAdapter` → `writeCheckedKeys` + `StgRowSelection.publish` + `bumpRowEpoch`（批量点行靠它发现行集合换了）。两个刻意边界：挂过滤代理时（`StgFilterBridge.isFor(adapter)` 为真）不接手；读不出来（`null`）就不写。

### 8.4 聚合标签合成规则

**成员标签不能从详情页取。** `m_tagList` 是 `List<com.guosen.android.aistock.data.Tag>`（只有 `tagId` / `tagName` / `isValid` 三个 String 字段 + 无参构造），换 `stId` 取数后宿主**不跟着换标签**（成员 s2 的 47 只已到账，两轮 `absorbTags` 读到的还是 s1 的标签）；列表页 `Strategy.tags` 是 JSON 字符串、元素含 `labelName`，且一次能采全表。所以成员标签从列表页整表采集（`StrategyStgIndex` 存 `stId → 标签名`），详情页 `m_tagList` 只当「当前页这一条」的兜底。

**合并规则（`mergeTagNames`，纯函数）**：区间型按前缀 + 单位分桶取 min–max，离散型按文本去重保序；数字按「整数不带小数点、小数最多两位去尾 0」输出。`量比1.5-2.0` + `量比2.0-2.5` → `量比1.5-2.5`；单成员带量比时结论是 `量比1.5-2`。

**四个条件两条 chip（`mergeTagNames` 的合成口径）**：列表卡 chip 行单行不滚动，四个条件原样写会被画成省略号，所以要「语义等价地变短、且每个 chip 都能拿到真 `labelId`」。

| 输入 | 输出 | 为什么 |
|---|---|---|
| `量比1-4` + `量比4.0以上` | `量比1以上` | 首尾相接（4 接 4），并集是 [1, +∞) |
| `流通市值50-500亿` + `流通市值500亿及以上` | `流通市值50亿及以上` | 同上；单位跟区间走，写法沿用原标签的「及以上」 |
| `量比1-2` + `量比3-4` | 两条都留 | 不相接；写成包络「1-4」会把没选的 2-3 说进去 |
| `流通市值50-70亿` + `流通市值50-70元` | 两条都留 | 单位不同不是同一维度（分组键 = 前缀 + 单位） |
| `涨跌幅-7%及以下` | 原样 | 单侧下界，负号必须吃进数字 |

实现要点：区间型与单侧开区间进**同一个桶**，后者按 ±∞ 参与合并；相接判定 `sp.lo <= last.hi + 1e-9`；输出按合并后形态选写法（上界无限 → `前缀+下界+单位+以上/及以上`，下界无限 → `前缀+上界+单位+以下`，两端有限 → `前缀+下界-上界+单位`）。

**chip 的 `labelId` 从哪来**：宿主 chip 少了 `labelId` 就画成无效标签（卡面感叹号）。合成文本在 1119 条档位表里没有原样的一条，所以 `StrategyLabelTable.entryForDisplay` 两步：① `byDisplay` / `labelName` 精确命中直接用；② `spanEntry` 按区间反查 —— 切掉最长父标签前缀（`流通市值`），把剩余文本解析成区间，取该父标签下**第一个相交的档位**顶上。`spanOfText` 同时认 `1-4`（两端）、`4.0以上` / `50亿及以上`（上界无限）、`3元以下` / `-7%及以下`（下界无限），单数字也算。真机反查：`量比1以上` → 量比1.0-1.5，`流通市值50亿及以上` → 流通市值50-70亿 —— chip 上写合成文本、点它落到那一档。

**标签写法白名单（`parseTag` 认四种写法）**：档位表 1119 条 / 85 个父标签 / 84 个多档父标签，旧白名单只认「前缀 + 数字-数字 + 单位」，84 个多档维度里 57 个含解析不了的档。

| 写法 | 例子 | 正则 |
|---|---|---|
| 区间 | `量比1-4`、`-7%—-5%`、`50-500亿`、`5百万元-1千万元` | `TAG_RANGE_REGEX`（分隔符认 `-` / `—` / `－` / `~` / `～` / `至`） |
| 单侧开区间 | `4.0以上`、`50亿及以上`、`3元以下`、`-7%及以下` | `TAG_OPEN_REGEX` |
| 比较词 | `大于1亿元`、`近10交易日>=10%`、`不超过5倍` | `TAG_CMP_REGEX` |
| 离散词（解析失败原样保留） | 金叉、放量、破净值、T字型 | 返回 `null` |

- **单位白名单** `TAG_UNIT_PAT`（长词在前）：金额 `万亿元/千亿元/…/万元/千亿/百亿/…/亿/万/千/百/十/元`；其余 `交易日/季度/倍/天/周/月/年/日/次/个/%/％`。
- **金额跨量级同轴**：`TAG_UNIT_SCALE` 把每个金额单位折成基准倍率，`5百万元-1千万元` + `1千万元-1亿元` → `5百万元-1亿元`；总市值 `1千亿及以上` 与 `500亿及以上` 同轴（尺度单位命中 `TAG_SCALE_UNITS` 时只写在末尾一次）。
- **比较词只在不被别段接上时才留**：`大于1亿元` 单独一条原样写，被 `1千万元-1亿元` 接上后并集是「1千万元以上」，判据 `cmpSpans[key]?.lo == sp.lo`。
- **端点保留用户看到的原文**：`量比1.50-2.00` + `2.00-2.50` → `量比1.50-2.50`；设置页 `StrategyComboCatalog.spanTag` 同步去掉 `trimDotZero`。
- **左端补单位**：`50-500亿` 的左端 50 也是「亿」，不补会按「元」算、与 `10亿以下` 判成叠。
- **不认的口语量级**：`1千5百万元` 明确不解析，原样保留（主力资金净流入里这几档不合并）。
- 反查走同一个解析器：`spanOfText` 调 `parseTag` 拿 `span.lo / span.hi`，不再自己写第二套「只认半角 - 和以上/以下」的逻辑 —— 统一后 `cannotResolve = 0`（常态断言 `parents=83 shrunk=45 cannotResolve=0`）。

**写回路径（按成功率排序）**：① 适配器方法 `setNewData` / `setList` / `setData` / `refreshData` / `setNewInstance`（单参且参数是 `List`）—— `AITagAd` 一个都没有；② 数据字段 `mData` / `data` / `mItems` / `mList` + `notifyDataSetChanged` —— **实测命中 `AITagAd.mData`**；③ 条数没变时直接改 chip 的 `TextView`。Tag 实例必须用宿主自己的类造：取样本的 `sample.javaClass`（`Class.forName("com.guosen.android.aistock.data.Tag")` 在模块 ClassLoader 里拿不到），抄样本的 `tagId` / `isValid` 只换 `tagName`。

**边界**：没进过「我的策略」列表页时索引里没有成员标签，只显示当前页那条策略自己的标签（不崩、不错位）；底部「修改策略标签」仍是宿主原生入口，聚合标签只作用于**这一页的显示**；chip 按「首次出现顺序」排，第一个成员的标签永远在前。标签条与股票列表同一个看门狗（`finish` 写一次，之后每 700ms `reapplyIfNeeded` 补，内容相同即早返回）。

### 8.5 入口卡与列表页

**入口卡命名（幂等键）**：`entryLabelOf(groupName, memberCount)`（`StrategyMergeFeature.kt:435`）直接返回 `entryLabelBare(groupName)`（`:445`；`memberCount` 参数只为调用点稳定，`@Suppress("UNUSED_PARAMETER")`）—— 组名以「聚合」开头不再叠前缀，空组名回落「聚合策略」。卡名**不带条数尾巴**。

**入口名逆解析**：`entryLabelGroupNames(label)`（`:458`）对老写法「聚合 · X（N 条）」返回 `[去条数全文, 去「聚合 · 」前缀的裸组名]`（老卡升级后还会出现一两轮，留作兜底）；不带条数时只认「聚合 · 」开头。普通策略名（`50e` / `创新高20日`）一律返回空，避免误改。

**去条数牵动的四张网**（尾巴同时是识别标记，去掉后四处都要补「与组名精确相等」的路）：

| 位置 | 判据 | 不改的症状 |
|---|---|---|
| 详情页认组 ③ | `labelNames.contains(gn) || pageName == gn || pageName == entryLabelOf(gn, g.members.size)` | 组名自己以「聚合」开头时卡名就是裸组名，逆解析认不出 → 点入口卡不聚合，日志「跳过：没有包含当前策略的已启用聚合组」 |
| 克隆借卡候选 | 排除条件加 `n in entryLabels` | 把别组的入口卡克隆一份当自己的入口 |
| `entryOriginals` 记录 | 加 `&& origName !in entryLabels` | 入口卡被当成真实策略记进原卡表 |
| 清单广播 | `publish(ctx, entries, entryLabels)`，`looksLikeEntryLabel(e.name) || e.name.trim() in entryLabels` 跳过 | 「从金太阳导入」多出一条叫「聚合组2」的假策略 |

**详情页组匹配**：局部函数 `hits(m)` —— `sid = m.stId.trim().ifEmpty { StrategyStgIndex.idOf(m.name.trim()).orEmpty() }`，`sid` 非空且等于 `pageStId` 即命中，否则比名称；全不中再用 `entryLabelGroupNames(pageName)` 与组名比对。

**克隆 / 自建入口卡**：`donor?.let { cloneCard(it) } ?: newEntryCard(env, gi)` —— 页面上没有可借的卡时构造宿主 `Strategy`（`CLS_STRATEGY = "com.guosen.android.aistock.data.Strategy"`，无参构造可用），写 `id=90N`（伪 id 前缀常量）/ `name=""` / `tags="[]"`。克隆后必过 `prepareEntryCard(clone)`：按字段类型清零 `ENTRY_QUOTE_FIELDS`（`leadStkCode` / `leadStkName` / `leadStkPrice` / `leadStkZdf` / `zdf`；String → 空串，数值 / 布尔 → 0）。借卡四条条件：名字与 `stId` 非空；`entryLabelGroupNames` 为空；未进本轮 `claimed`；不是任何启用组的成员卡。借来的卡只改内存名字、不写服务器 —— 每轮刷新宿主拉回来的仍是原名。

**标签写卡**：`writeCardTags` 带 `clearWhenEmpty`（`want` 空时写 `"[]"`，克隆卡不能挂 donor 的标签），判等拿「实际写进 JSON 的名单」比 `cur`（拿 `want` 比会永远不等 → 巡检每 800ms 重写 + 整表 notify）。`mergeTagsJson` 新造词条查 `StrategyLabelTable.entryForDisplay(n)` 补 `labelId` + `isValid="1"`，查不到跳过并计数（写上去就是点不动的无效标签）；`parsed` 判据是「至少一段原 JSON 解析成功」（自建卡的 `tags` 就是 `[]`）；`want` 非空但条目全被跳过时返回 `null`，调用方 `?: return false` 保持原样 —— 只有 `clearWhenEmpty` 的两处自造入口卡才写 `[]`。

**收起成员卡**：`sameMember(m, item)`（`:2136`）按 `codeAndMarket` / 名称判同一只，命中的行收进 `toHide`（`:1878` 建表、`:2041` 收集、`:2056-2060` 统一 `removeAt` 倒序删），数据一律 `MutableList` 判据、绝不原地改宿主实例列表。

**名称 → `stId` 索引落盘**（`feature/stgmerge/StrategyStgIndex.kt`）：`PREFS_NAME = "gsaux_stg_index"` / `KEY_INDEX = "index"` / `FLUSH_DELAY_MS = 500L`，用宿主自己的私有盘（不碰 `ModulePreferences`）；`bind(ctx)` 幂等绑定 + 首次读盘；`loadNow()` 只补内存里没有的键；`scheduleFlush()` / `flushNow()` 值变了才写、500ms 节流；`namesSnapshot()` 供诊断日志；`clear()` 置 `dirty` 后立刻 `flushNow()`。序列化 `{"v":1,"names":{…},"tags":{…}}`。

**巡检幂等与日志节流**：`decorateListNow()` 的 `done` 判定同时接受带尾巴与裸名（`n == label || n == bare`），改名成功后两个名字都塞进 `entryLabels`，防止下一轮再挑一张成员卡重改、列表重新膨胀；`done` 之后补一路 `stale`（名字带「（N 条）」且去条数后等于本组名 → 认作旧卡）。详情页标题 `buildLabel(groupName, names)`：组名非空用 `entryLabelBare`，组名为空才回落成员名 `+` 拼接，超过 `StrategyMergeTargets.MERGE_NAME_MAX = 24` 截断加「…」。巡检日志按 `lastListStat = data.size|indexed|tagCount` 变化才打，尾部日志只在 `renamed/retagged > 0` 时打。

### 8.6 条数口径与家数口径

**条数按「展开后」数**：口径成员（`stId` 留空、名字就叫「中盘 1.5-4」）在宿主里没有卡，入口卡的条数只能模块自己算 —— 只算成员数会写成 2 条，详情页却按展开后的 13 条取数。判据唯一实现：`StrategyComboCatalog.bucketOfMember(name, stId)`（`stId` 空且名字命中 `BUCKETS` 的中文名或 key 才算口径成员）、`expandedCount(members)`（累加 `comboCount`，其余按名字去重 +1）、`tagNamesOf(bucket)` / `tagNamesOfKey(comboKey)`（把口径反推成宿主原生标签写法）。列表装配用 `expandedCount`（`< 2` 门槛不变），设置页删掉私有 `mergeMemberCount`。第三处实现出现就会「列表写 13、设置页写 2、详情页取 13」，单测 `StrategyComboCatalogTest` 锁住这两个函数。

**摘要显示数与生效门槛是两个数**：`expandedCount` 算 0（待选档位的父标签成员）但成员表里有行时，摘要显示 `max(expandedCount, members.size)`，而生效门槛仍只认 `expanded` —— 取不出股票的口径成员不能把组撑成「生效」。

**筛选联动计数**：`tv_stg_res_num` 被 stgfilter 与 stgmerge 两个功能同时写，差一个空格就会每 700ms 互相覆盖。判据不认类名（代理会被 R8 混淆成 `f2.m` 之类），看「列表现在挂出来多少行」：`shownStockRows(act)`（`:2988`）剥 `HeaderViewListAdapter` → 内层是宿主 `AIStkAdapter` 就返回 `null`（没筛，也挡住空适配器 / `AITagAd` 误判）→ 内层不是原生但字段里有「声明类型 `android.widget.ListAdapter`、值是原生适配器」的即认作筛选代理（framework 接口不会被改名，筛到 0 只也认得出）→ 返回 `la.count - getHeadersCount() - getFootersCount()`。写计数统一走 `writeMergedResNum`（`:2963`）：`shown != null && total > 0 && shown < total` 判为筛选中写 `StrategyMergeTargets.RES_NUM_FILTERED_FORMAT`（`"策略个股 %d/%d"`，与 stgfilter 的 `OUR_RES_NUM` 逐字对齐），否则写并集只数。`PageState.hostResNumTail` 在 `captureOrigin` 时记下宿主原样时间戳尾巴，`"36/212"` 抽不出尾巴时回落用它。

**家数「重数优先」**：原逻辑「并集与上次一致就沿用缓存，只有累加值 < 并集只数才重数」在成员重叠时会写偏大的累加值（真机：并集 778 只、头部 581 + 259 = 840）。现行 `StrategyMergeTargets.countTier(mergedSize, counted, sameAsCached)` 三档：

| 档 | 条件 | 家数来源 |
|---|---|---|
| `TIER_COUNT` | 重数覆盖 ≥ 80% 的并集行 | 并集重数（与屏幕天然一致） |
| `TIER_CACHE` | 覆盖不足 + 本轮并集与上次一模一样 | 上次缓存 |
| `TIER_SUM` | 以上都不成立 | 成员累加（兜底，可能偏大） |

缓存从第一档挪到最后：缓存本身可能就是偏大的累加值。日志（家数真变了才打）「家数按并集重数：535/211（成员累加 581/259，重叠已去掉）」，判据是 `X + Y ≤ 并集只数`（可小于，因为有平盘）。只出现「并集里读不到涨跌幅字段」说明宿主换了字段名，改 `StrategyMergeTargets.PCT_FIELDS`。

**筛选状态按组保存**：聚合期间宿主把 `m_stg.id` 换成成员 stId、把 `m_stg.name` 改成入口名，两个当场身份都不可信 —— 由 `start()` 在改宿主**之前**调 `StockFilterScopes.rememberMergePage(act, group.id, group.name)` 显式登记，作用域 `grp:<组 id>`，撤销点 `releaseRollback()` 与 `onActivityDestroyed()`；组优先于策略，开关关着时行为与历史全局键一致。

### 8.7 条件成员与单槽轮转

**成员格式**：`cond:<市值档列表>|<量比档列表>`，例 `cond:50-70亿,70-100亿,100-500亿|1.5-2.0,2.0-2.5,2.5-3.0,3.0-4.0`；`stId` 必须为空（填了就按普通成员走），展开条数 = 各维档数笛卡尔积（`StrategyComboCatalog.Condition.comboCount`）。编码只加不删，旧配置（真实策略名、口径名 `中盘 1.5-4`）照旧解析。标签成员写成 `label:<labelId>`，队列 key 会被包成 `combo:label:<id>`，所以 `labelIdOf` 先剥一层 `COMBO_PREFIX` 再判 `LABEL_PREFIX`；`groupTagNames` 在条件成员之后补 `labelIdOf(m.name)?.let { pool.addAll(tagNamesOfKey(m.name)) }`，否则标签成员的 chips 会退回借来那张原生卡的标签。

**组合维度界面**：「添加成员」里的组合维度进的是**页内第四层子视图**（不是弹层）：点某一维只铺这一维全部档位、多选勾（`DialogController.showMultiChoice`，`StrategyMergeEditScreen.kt:532`）；「+ 添加维度」一级选分组、二级选标签，新维默认只勾第一档（`addDim` `:311`）；每维行尾 ⋮ →「移除这一维」（只剩一维禁用，`:509-528`）；「完成」把整张维度表落成**一条** `cond:` 成员（`commitCond` `:259`）。落盘后行标题 `流通市值50-500亿 · 量比1.5-4`，副标题 `… · 12 条组合`。

**单槽轮转**：账号级策略上限 10 条（服务器 `code 4100 策略数不能超过10个`），24 个口径成员一起 `create` 直接撞上限（表现为「临时策略一条都建不出来 → 聚合 0 只」）。改成**一条临时策略整轮复用**：`StrategyComboClient.createOrFreeSlot` 建一次（撞 4100 先 `purgeLeftovers()` 清自己漏删的再重试一次），之后每个成员只 `update(stId, labelIds)` 改条件。服务器按当前标签现算，同一临时策略连查 24 次得到 100/56/45/25/17/12/8/4/2/0 只，证明不是缓存。`const val TEMP_PREFIX = "GSZtmp"`（`purgeLeftovers` 认它，也认早期 `Z` + 8 位数字）；`TempSlot.ensure / apply / release`，`apply` 标签没变不发请求，`release` 删掉并清空台账；`tempIds` 里的成员**不再重试**；名字以 `TEMP_PREFIX` 开头的卡不许借（收尾就删）。实测 24 格全部复用同一临时策略、每格 121~140 ms、整轮约 3.4 秒。两个数据坑写死在客户端：请求头必须 `Content-Type: application/x-www-form-urlencoded; charset=UTF-8`（不带时中文策略名乱码）；探测接口别拿真实策略 id 试跑。

**宿主广播清单（「从金太阳导入」）**：模块 App 与宿主两个 UID 读不到彼此私有数据，`XposedInterface.getRemotePreferences` 返回只读，共享文件要 `MANAGE_EXTERNAL_STORAGE` —— 采用**宿主 `sendBroadcast` + 模块静态接收器**（零权限）：`core/StgCatalogBroadcast`（action / 包名 / extra 键 / `VERSION = 1`）、`feature/stgmerge/StrategyStgCatalog`（组包 + 两道节流：最短 3 秒一次，内容没变最多 30 秒补发）、`app/HostStgCatalog` + `app/HostStgCatalogReceiver`（落 `gsaux_host_catalog`）。载荷 `{"v":1,"at":<毫秒>,"items":[{"id":…,"name":…,"tags":[…]}]}`，`v` 不符整份丢弃，`id` 或 `name` 缺一半的条目直接丢。

清单口径是**这一轮列表页适配器里真实存在的条目 ∪ 启用组成员**：索引只 remember 不淘汰（`T4` / `TPMP50` 这类早前用接口批量建过又没了的策略会一直躺着），所以不能发索引全量。三份来源合并后广播：`live`（这一轮真实存在的条目）、`withGroupMembers`（被收起 / 被借走的成员卡，按 `stId` 或名称反查补回）、`entryOriginals`（组名 → 入口卡原身份，只在 `done == null` 首次改名时记一次）。`cond:` / `combo:` / `label:` 成员不是宿主策略、跳过；入口卡由 `looksLikeEntryLabel` + `entryLabels` 拦下。

界面侧：首行是清单头「策略清单 / 上次同步 12:20 · 共 8 条」（只展示），每条一行标题策略名、副标题依次取「已在当前组里」/ 成员上限提示 /「无标签」/ 标签拼接；点一行直接加成成员（去重同时看 `stId` 与名称）；还没收到广播时显示「还没有同步到金太阳的策略」+「打开一次金太阳的『我的策略』列表，再回到这里」。索引本身仍保留全量 —— 成员只填名称时靠它把名字解析回 `stId`。

### 8.8 性能与稳定性

**三道闸缺一不可**（同一张入口卡一会儿 212 只、一会儿 100 多只；家数在宿主刷新后跳回单策略值；每进一次详情页涨家数就往上加一截 —— `174/36 → 308/65 → 442/94 → 576/123 → 710/152`，每次固定 `+134 / +29`）：

| 闸 | 内容 |
|---|---|
| 超时与完整性 | `MEMBER_TIMEOUT_MS` = 3000（2 秒会把「刚好换完列表」判成超时）、`TOTAL_TIMEOUT_MS` = 16_000L、`RETRY_ROUND = 1`（`ArrayDeque` + `retried` + `requeue()`，单成员超时排到队尾重试一次）；`finish()` 完整性闸：`degraded && cached.size > uniq.size` → 保留上次那份，残缺不许覆盖完整 |
| 缓存秒套 | `StrategyMergeCache`：key = `组 id + "|" + 成员 stId 序列`，`MAX_ENTRIES = 8`（accessOrder LRU），`SNAPSHOT_TTL_MS = 10 * 60_000L`（时钟回拨按过期），值里列表用 `WeakReference`（被回收就当没有）；`applySnapshot` 只写列表 / 标题 / 计数 / 标签，**不写** `model` 的 `upNum` / `downNum` |
| 累加隔离 | `addStat`（`:3816`）累加前校验 `涨 + 跌 ≤ 本次列表只数`，不满足打 WARN 丢弃；`reapplyIfNeeded` 的家数字段补写加 `!state.running`；`poll()` 的 id 守卫在 `m_stg.id` 被 `autoRefresh` 改回时按 `ID_RESEND_MIN_INTERVAL_MS`（600ms）重写 id + 重发、**这一轮不 absorb** |

**一步到位（进页面就是完整并集）**：三个根因与对应处置 —— ① `mergedRef` 弱引用在取数窗口被 GC 收走 → 退回「逐格长大」，改用强引用副本 `mergedStrong`；② 列表挂着 `FilteredAdapter` 时它在 `notifyDataSetChanged` **之前**就用只有 1 行的 base 算好了 `indices`，所以 `holdUnionWhileRunning` 的顺序必须是「写 `data` → `holdHeaderCounts` / `writeHeaderCounts` → `notifyAdapter`（代理按新全量重算下标）→ 最后 `writeMergedResNum`」；③ 取数窗口每帧把自己的聚合家数写回 `model`，`poll` 里 `addStat` 现读就变成自己写的数 —— 改为 `captureHostPayload` 抄帧的同一时刻 `captureHostStats()` 存 `hostUp/hostDown`，`addStat` 用这两个。**顺序不可换**：抄帧抄家数必须在写回之前（晚一步抄到的是自己的聚合值），计数必须写在 `notifyAdapter` 之后（`X/总数` 的 X 是从代理现算的可见行数读的）。

**主线程死锁的两处重入闸**：24 格跑到最后一个时流水线静默停住、21 秒后宿主 ANR（`ANR in com.guosen.android (…AIStkStgDetailAct) Reason: Input dispatching timed out`），栈是 `applyFilter` / `onAdapterSet` / `hookListViewSetAdapter` 四帧无限重复。根因：`StgStockFilterFeature` 还原那句 `lv.adapter = base` 会**同步**回到自己的 `ListView.setAdapter` 后置钩子，而「列表上还挂着过滤代理」的记账（`st.proxy` / `proxiedLists`）写在赋值**之后**。两处闸都要：`StgStockFilterFeature` 的 `filtering = ThreadLocal<Boolean>()`（`applyFilter` 变薄壳、本体 `applyFilter0`）+ 还原时**先清记账再赋值**；`StrategyMergeFeature` 的 `setAdapterBusy = ThreadLocal<Boolean>()`（setAdapter 后置钩子只放第一层，嵌套层原样 `return result`）。stgfilter 断的是同一条链里的自我递归，stgmerge 断的是两个功能互相触发 —— 只拦一侧仍能拖住主线程。结果：看门狗「没动（」条数 6 条/轮 → 0，ANR 每轮一次 → 0。

**全选 / 反选提速 4 倍**：根因是 `syncHostModel` 每点一行就重做一遍全表校正（`O(N)` 读每行代码 / 原始代码 / 价格 / 连板数），285 行就是 285 × O(285)。改法：clickAll 期间建 `RowCodes(adapter, total)` 缓存表，`cachedRowCode` / `rowRawCodeAt` / `rowPriceAt` / `rowTwinAt` 先查表，非批量态原样回退老实现。实测（285 行筛选态、并集 630）：一次全选 1763 ms → **429 ms**（其中校正模型 1234 → 22 ms），反选 346 ms（校正 10 ms），gfxinfo 99th 1750 → **300 ms**。

**收尾自审的三处**：`pctFieldCache = WeakHashMap<Class<*>, Field>`（`StrategyMergeTargets.kt:404`）—— `pctOf` 逐候选名 `getDeclaredField` 未命中就抛 `NoSuchFieldException`，700 只 × 11 候选 ≈ 7700 次异常构造全压在写回那一段；缓存「已经成功读到过的那个字段」，某行该字段为 null 时回退完整候选扫描且不改缓存。`MainThreadStallWatchdog` 加 `@Volatile gen` 代次（`stop` 不 join，500ms 内重启会起第二条 `GSZX-main-watch`），并把 `stop` 从 `finish` 第一行挪进 `finally`（原来最重的活都在停表之后做）。`cleanupTemps` 的 `if (left.isEmpty()) return` 曾把 `comboSlot.release()` 一起跳过、`cancel()` 抢在记账前 `return`，于是一条 `GSZtmp…` 永久占住 10 条名额 —— 现在 `release` 与台账清空无条件先做，`cancel()` 也补一次 `cleanupTemps()`。另把几处静默失败补了日志（`cloneCard` 字段写不进去计数打 `W`、`commitCond` 撞同名 / 到上限、`comboCount` 空维从 1 条改 0、`combosOf` 空维返回 `emptyList()`），每轮 4 处调用的 `logWiring` 探针整段删除，只留 `logListAdapters(act, "finish")` 单次形态；全选耗时的三段插桩保留（微秒级，性能回归的唯一证据源）。

### 8.9 数据准确度

真机逐只比对（聚合组2 / 24 成员 / 并集 754 只 / 筛选勾「涨家 + 自选（须涨）+ 全部板块」/ 保留 535 行 / 2026-11-13 12:12—12:25 午休时段）：

| 维度 | 样本 | 结果 |
|---|---|---|
| 行级最新价 | 535 行逐只 | 与腾讯行情 **0 偏差**（容差 0.011 元） |
| 行级涨跌幅 | 535 行逐只 | **0 偏差**（容差 0.02 个百分点） |
| 代码 ↔ 名称配对 | 535 行逐只 | **0 差异**（证明没有串行） |
| 计数文案「策略个股 535/754」 | 日志「全量 754 → 保留 535」 | 一致 |

做法：回顶 14 次后 swipe 400 逐屏 dump（页面停在底部时第一步只能抓到个位数行，回顶是必需的）→ 按行 y 中心 ±26px 聚类成 `code<TAB>name<TAB>price<TAB>zdf` → `python tools/audit/compare_quotes.py rows.tsv 报告.md`（外部源 = 腾讯 `qt.gtimg.cn`，60 只一批，GBK 解码），输出 `rows=535 uniq=535 ext=535 missing=0 bad_price=0 bad_zdf=0`。口径三条：午休时段（11:30—13:00）报价冻结，是核「值对不对」的最佳窗口；容差按页面显示精度定；**必须比名称**，只比价格会漏掉「代码取错但恰好同价」的串行错误。

**535/754 的算术**（证明没丢数据）：日志「全量 754 → 保留 535（涨 334 / 跌 201 / 自选 211 / 平盘 8 / 创业板 125 …）」—— 754 = 334（普通涨）+ 201（普通跌）+ 211（自选）+ 8（平盘）✓；保留 535 = 334 + 201（自选里上涨的）；筛掉 219 = 201（普通跌）+ 10（自选里下跌）+ 8（平盘）✓。即「只勾涨家、自选也须涨」时屏幕上的 535 行就是全部上涨个股。头部「涨 535 / 跌 211」与保留数 535 同值是**巧合**，不是把家数当行数写。

**头部日涨跌幅是并集等权平均**：`mergedZdfText` / `writeMergedZdf` 算屏幕上这份并集的等权平均（逐只 `pctOf`），分母是整份 754 只（含被筛掉的 219 只跌 / 平），所以它比 535 只的实测平均 +2.573% 低 1.57 个百分点属**口径差**。反推校验：535 × 2.573 + 211 × X + 8 × 0 = 754 × 1.00 → 跌家平均 X ≈ −2.95%。

### 8.10 已知边界与未复验项

| 项 | 现状 |
|---|---|
| 页面上一张策略卡都没有时自建入口卡 | **未真机复现**（当前账号有卡，删除 / 新建策略会动用户数据）。已核对：`CLS_STRATEGY` 无参构造可用（dump 事实）、自建卡写 `id` / `name` / `tags` 三字段、点击时 `pageStId=90N` 不命中任何成员 → 走 `seedIsMember=false` 门控 |
| 盘中刷新及时性 | **未验**：刷新由宿主自己取数（模块只透传 `StockInfo`），午休报价冻结只证明了「值对不对」。要验得在 9:30—11:30 或 13:00—15:00 对同一屏抓两次，比两次差值与外部源 |
| 头部「日涨跌幅」原值 | 宿主按自己的缓存算，模块里没有对应字段（`StrategyMergeTargets` 里 grep 涨跌 / PCT / RATE 零命中）—— 现状由模块按并集等权平均重写；宿主重画后靠看门狗纠回 |
| 合成区间 chip 在列表卡上是灰色 + ⓘ | 宿主按自己标签库的 `labelId` 决定 chip 样式；内容与取数完全正确，只是样式不同（详情页同一批标签是白色正常）。要白化得反查宿主 chip 渲染判据，加固壳无 dexdump，成本高于收益 |
| 入口卡数量受宿主策略条数上限约束 | 已启用组比页面上可借的卡多时，多出来的组拿不到入口（日志「借不到策略卡（页面 N 张）」）；要更多入口只能在宿主里多建几条策略当卡位 |
| `策略个股` 分子与头部涨家数差几只 | 分子是筛选代理按自己的谓词数出来的可见行，头部是宿主按服务端字段数的（缺行情快照的票在代理里被排除），口径本来就不同 |
| 进页面第一瞬可能闪一个偏小的分子 | 宿主列表分页懒加载，页面刚起来只有十行左右，会先写「策略个股 8/212」，看门狗 1 秒内纠正；要彻底消掉得等 `state.applied` 之后才判筛选态，权衡后保留 |
| 头部涨跌家数始终是全量口径 | 聚合家数按并集算，不随筛选变（筛选只影响列表行与计数栏），与宿主原生策略行为一致 |
| 组成员卡被收起期间在列表页不可见 | 点不进去，只能从聚合入口卡进；组停用即恢复 |
