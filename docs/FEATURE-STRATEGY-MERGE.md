# 智能选股·我的策略聚合（聚合组）踩点报告与实现记录

> **一句话**：用原生 `AIStkStgDetailAct` 页面加一层「聚合结果代理」，把多条原生策略的股票并成一张看起来完全原生的列表；设置放模块设置页，落地前先补一轮「我的策略列表页」运行时探针。

> **状态（2026-11-12）：已落地。** 代码在 `feature/stgmerge/`（`StrategyMergeFeature` / `StrategyMergeTargets` / `StrategyMergeBridge` / `StrategyComboCatalog` / `StrategyStgIndex` / `StrategyMergeCache`），配置在 `core/StrategyMergeStore`（键 `stg_merge_groups`，JSON v1），界面在 `ui/StrategyMergeScreen` 与 `ui/StrategyMergeEditScreen`，宿主策略清单由 `core/StgCatalogBroadcast` + `app/HostStgCatalogReceiver` 收下（`core/HostSession` 装配）。下文 §1–§10 的踩点、路线对比与真机探针记录**保留作历史依据**，「功能尚未落地 / 源码零改动 / 待拍板」都是踩点当时的表述。
>
> 逆向结论正本在 [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md)，架构正本在 [ARCHITECTURE.md](ARCHITECTURE.md)，扩展正本在 [EXTENDING.md](EXTENDING.md)，界面规范正本在 [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md)；本文只做汇总、引用与方案对比，不另立正本。

---

## 0. 结论先行

| 项 | 结论 |
|---|---|
| 用户要的 | 把「流通市值 50e–500e + 创新高 20 日」这类跨预设分段的多个原生策略，合成一个大类；多条策略的输出并到**一个原生页面**上，观感与原生一模一样，只是股票是聚合体；聚合设置放模块里 |
| 原生页可复用 | 可以，**且已实机跑通**。`AIStkStgDetailAct` 的 `lvStks` / `AIStkAdapter` / `act_ai_stk_stg_detail` 全套资源已在仓内被三个现成功能复用过；2026-11-11 在 MuMu 实机（宿主 8.7.1）用探针验证「换 `stId` 原生重取 + 写回 `AIStkAdapter.data`」后，页面把两段市值策略的 64 只股票混排渲染成一张原生列表（见 §2.5、§10.5） |
| 推荐路线 | **C（按成员 `stId` 逐条让宿主自己重取）+ A（把并集写回原生 `AIStkAdapter.data` 再 `notifyDataSetChanged`）**，两条都已实机验证；B（自写代理适配器）降为可选件，只在需要「行级过滤 / 不改宿主 `data`」时启用。配置与设置页照 `QuickConfigStore` / `QuickConfigScreen` 三层模板 |
| 主要缺口 | 「策略详情页」侧的证据**已闭合**（模型类 / 命令表 / 重取时机 / 适配器 / 去重键 / 计数文案全部拿到，见 §2.5）；仍缺的只剩「我的策略列表页 `AIStkStgListAct`」的适配器与 `stId` 字段，仅影响「从列表页勾选导入成员」这条增强，不挡主闭环 |
| 可绕开缺口 | 成员 `stId` 由模块设置页手工登记（或探针补齐后从列表页勾选导入），取数走宿主自己的 `queryStrategyQuoteDetail` 通道，不依赖列表页适配器 |
| 本轮产出 | 只读踩点 + 四条路线对比 + 推荐设计 + 待拍板三点 + 探针清单 + **MuMu 实机机制验证（v33→v54 探针）**；功能源码仍零改动 |
| 静态实证 | 宿主 8.7.5 真机包 REA 拆包成功（`REA_EXIT=0` / `JSON_BYTES=902254`），manifest 保留 `router_ai_stk_index` / `router_ai_stk_stg_list` / `router_ai_stg_stk_detail` 三段路由；但加固壳 `class_count=53`，业务类零命中，运行时仍靠探针 |

---

## 1. 需求还原

用户原话（m00001）的四个硬点：

1. 目标形态是「要求1 流通市值在 50e 到 500e」+「要求2 创新高 20 日」这样一条连续区间策略。
2. 国信金太阳原生条件里流通市值只能选预设阶段，例如 `50-70e`、`100-500e`，连续区间被切成多个分段。
3. 新功能「用原生的功能进行整合，将这类的策略整合成一个大类」：把跨分段的多个原生策略各自的结果合并到一个页面。
4. 「这个页面完全调用原生的页面实现，就相当于一切都看起来是原生的，只是看到的股票是多个策略的聚合体。聚合的设置菜单之类的放在模块里吧，然后获取策略页的策略进行聚合？」

收尾口径：「具体的功能实现需要你踩点以后再说。你可以先进行踩点并出具你相关的想法」。因此本文只给踩点事实与方案判断，接线清单留作后续实现的施工图。

---

## 2. 已确认的宿主事实（引用正本，逐字保留类名 / 资源 id）

### 2.1 页面链路

[REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md):114-125、:384-388：

```text
首页 → 智能选股 → 我的策略 → 任一策略
MainAct → AIStkIndexAct → AIStkStgListAct → AIStkStgDetailAct
```

[FEATURE-DIRECT-QUOTE.md](FEATURE-DIRECT-QUOTE.md):388 真机记录：「连续点击 6/6 次均直达 `StockFragAct`；每次返回都回到 `AIStkStgDetailAct`，**未再回落 `AIStkStgListAct`**」。说明详情页是独立可停留的结果页，返回栈不强制经过列表页，聚合页挂在这层比较稳。

### 2.2 策略详情页数据源与接口契约

| 事实 | 原文 / 值 | 出处 |
|---|---|---|
| 结果接口 | `POST /gsnews/strategy/queryStrategyQuoteDetail/1.0`，`Host: goldsundg.guosen.com.cn`，`Content-Length: 382` | [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md):600-617 |
| 唯一业务参数 | `stId=<策略id>`；其余全是 `pkg / reqtime / packtm / sysVer / softName / netAddr / userCode / session / openapi_sign` 客户端元信息与鉴权 | 同上 |
| 分页参数 | 零 `count` / `limit` / `pageSize` / `pageNo` / `num`；三次独立抓包参数集合与 Content-Length 完全一致 | 同上 :600-617 |
| 每策略上限 | 服务端施加 100 只上限，客户端改代码取不到第 101 只 | [CHANGELOG.md](../CHANGELOG.md):2345-2399 |
| 列表→详情 Intent | `adapter_key.stage=Strategy{id,name,tags=[labelId…]}`，**不含股票列表、不含数量** | 同上 :2353 |
| 网络栈 | 金太阳零 OkHttp 命中，走自研 `com.guosen.androidnet.tcpb.*` + Conscrypt TLS | FEATURE-STG-FILTER.md:625-630 |
| 结果页适配器 | `com.guosen.quotation.adapter.aistk.AIStkAdapter` 继承 `com.guosen.commonui.adapter.BaseQuickAdapter`，数据字段名 `data`，Activity 字段 `m_stkAdapter` | [tools/contract/host-contract.json](../tools/contract/host-contract.json):1390-1393、[REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md):129-153 |

关键推论：每个策略的结果由服务端按 `stId` 整包返回、客户端整包渲染。聚合的本质就是「对 N 个 `stId` 各取一包 `StockInfo`，合并成一个 `data` 交给原生适配器」，与宿主自己的取数语义同构。

### 2.3 原生结果页可复用的资源与控件

[tools/contract/host-contract.json](../tools/contract/host-contract.json):1199-1408（`StgStockFilterTargets.kt` 段）逐字：

| 类别 | 值 |
|---|---|
| 类 | `CLS_BASE_QUICK_ADAPTER=com.guosen.commonui.adapter.BaseQuickAdapter` / `CLS_STOCK_INFO=com.guosen.android.quotes.data.StockInfo` / `TARGET_ADAPTERS=com.guosen.quotation.adapter.aistk.AIStkAdapter` |
| 布局 / 容器 | `LAYOUT_DETAIL=act_ai_stk_stg_detail` / `LAYOUT_EMPTY=empty_ai_stk` / `COL_TITLE_ID=ll_top` / `lvStks` |
| 菜单与计数 | `MENU_ANCHOR_ID=tv_del_stg` / `MENU_EDIT_NAME_ID=tv_edit_name` / `RES_NUM_ID_CANDIDATES=tv_stg_res_num` / `tv_stg_res_time` / `tv_stg_add_zxg` |
| 行内 | `PERCENT_ID_CANDIDATES=tv_item_stk_percent` / `ct_item` / 行高 138px、一屏约 10 行 |
| 底部多选 | `ll_add_zxg_bottom` / `tv_bottom_zxg_num` / `tv_bottom_add_zxg` |
| 字段候选 | `CAM_FIELDS=codeAndMarket,m_codeAndMarket` / `CODE_FIELDS=code,mCode,m_stkCode` / `MARKET_FIELDS=mMarket,m_stkMarket,market` / `NAME_FIELDS=m_name,m_stkName,name,stockName` / `PERCENT_FIELDS` 含 `zdf`(BigDecimal) 等 14 项 / `TYPE_FLAG_FIELDS=isCyb,isKCPlate,isXXLD` |

去重键建议直接用 `codeAndMarket`（形如 `300006.1`），与 `ZXManager.m_stkGrpMap` 的 key 同构（[REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md):157-180）。

### 2.4 静态拆包边界（REA 只读核对结论）

[REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md):36-48、:59-73、:82-86：宿主 8.6.0 / vc271，梆梆企业版 `SecNeo/Bangcle`，`com.secneo.apkwrapper.AW` / `AP` / `CP`，`libDexHelper.so`；单 dex 未压缩 97,392,176 字节里 `class_defs=53`，其余为加密载荷，「静态反编译这条路是堵死的」。

用户 m00016 直接注入 `/reverse-engineer-anything` 技能（可选），m00093 又补了宿主 8.7.5 真机包（`<下载目录>/国信金太阳_8.7.5.apk`，150,953,313 字节 / SHA256 `39B1B20D…CE6A89` / `LastWriteTime=2026-11-11T18:32:43+08:00`）。下表是本轮 REA 全链路实测：

| 项 | 实测 |
|---|---|
| 技能与 CLI | `/reverse-engineer-anything` 技能由用户直接注入；`rea@6.7.0` CLI 可用，`inspect-android-package / inspect-android-class / inspect-android-method / analyze-javascript-application` 等子命令在册 |
| 引擎 | `ghidra 12.1.4` healthy（`F:\Program Files\ghidra_12.1.4_PUBLIC`）、`jadx 0.7.1` healthy（`jadx-headless-mcp-0.7.1-all.jar`）、`kuna 1.990` healthy |
| 注册 | `rea doctor --client dsh --json` → `healthy:false` / `environment_healthy:false` / `registration:dsh classification=config_drift` / `skill:identity config_drift`；会话里零 REA MCP 工具 |
| 设备 | `rea list-adb-devices --json` 与 `rea inspect-adb-client --json` → `capability_unavailable` / `The selected adb binary was not found: adb. Set REA_ADB_PATH to an absolute path to an existing adb binary.` |
| 宿主样本（先验） | 首次搜盘时 8.7.5 尚未下载：`glob("*.apk", F:\Project)` 7 个全是模块包与微信包、`glob("*guosen*.apk", F:\Project)` → `paths:[]`、Downloads / Desktop 零命中 |
| 宿主 8.7.5 真拆包 | `rea inspect-android-package <8.7.5.apk> --format json` → `REA_EXIT=0` / `JSON_BYTES=902254`；`provider=jadx 0.7.1` / `package=com.guosen.android` / `version_name=8.7.5` / `version_code=275` / `min_sdk=21` / `target_sdk=30` / `class_count=53` / `resource_count=11789` / `permissions=57` / `manifest.text=418143` 字符 |
| CLI 真跑（模块包验链路） | `rea inspect-android-package "F:\Project\GSAuxiliary\.work\GSAuxiliary_58-3.7.0.apk" --format json` 成功产出 Evidence：`provider=jadx 0.7.1` / `class_count=3182` / `resource_count=99` / `package=io.github.MVennn.GSAuxiliary` / `version_name=3.7.0` / `version_code=58` / `min_sdk=27` / `target_sdk=36` |
| 8.7.5 manifest 实证 | `<application android:name="com.secneo.apkwrapper.AW">`；三段页面链 Activity 全在：`AIStkIndexAct`（action `router_ai_stk_index`）/ `AIStkStgListAct`（`router_ai_stk_stg_list`）/ `AIStkStgDetailAct`（`router_ai_stg_stk_detail`），均 `exported=false` + `portrait` → 「完全复用原生页面」由推测升级为有静态声明支撑 |

结论：8.7.5 宿主包静态拆包**成功且退出码 0**，但它是 SecNeo 加固壳 —— `class_count=53` 全是壳类，整份 Evidence 里 `AIStkAdapter=0` / `StockInfo=0` / `queryStrategyQuoteDetail=0` / `IntentStockInfo=0`，`raw_result.loaded.state=LOADED` / `decompile_timeout_ms=90000`；`limitations[0]` 原文「Static decompiler observations do not establish runtime behavior; the APK is never executed.」。manifest 能证组件声明与三段路由 action，**不能证适配器 / 数据接口的运行时行为**，故业务类名、方法签名、资源 id 一律以 [tools/probe/README.md](../tools/probe/README.md) 运行时探针与 [tools/contract/dump/README.md](../tools/contract/dump/README.md) 类名快照为准。`rea setup` 会重写 `~/.dsh/cordis.patch.yml` 并替换已注入技能（碰 §16 偏好），本轮跳过；`REA_ADB_PATH` / `REA_APKTOOL_COMMAND` 亦未补，设备侧取包与资源表解码本轮关闭。

### 2.5 详情页运行时实证（MuMu 12 / 宿主 8.7.1 / LSPosed 2.2.1，2026-11-11）

静态拆包拿不到业务类，本节全部来自 [tools/probe](../tools/probe/README.md) 探针在真机页面上的 dump 与注入实验（探针版本 v33→v54，日志 tag `GSRecon`）。它把 §2.2 里「客户端整包渲染」的推论变成了可复现的机制。

| 项 | 实测值 |
|---|---|
| 页面 | `com.guosen.quotation.ui.aistk.AIStkStgDetailAct`（`策略详情`），控件 `tv_top_title` / `tv_tag_name` / `tv_stg_res_num` |
| 模型类 | `com.guosen.quotation.model.aistk.AIStkStgDetailModel`，**0 个自有方法**，逻辑全在 `EngineModel` / `AutoModel` / `BasicModel` |
| 模型字段 | `Strategy m_stg`、`ArrayList m_stkList`、`List m_tagList`、`int upNum / downNum / flatNum`、`boolean isChoiceMode / isAuto`、`int m_delTagPos`、`String m_explainContent / m_updateStgName`、`EngineBiz m_biz`、`Map m_methodMap`、`Object[] m_objs`、`IEnginePage m_page`、`Map m_paramMap` |
| `Strategy` 字段 | `String id / name / tags / leadStkCode / leadStkName / leadStkPrice / leadStkZdf / zdf` —— **没有「股票数量」字段**，头部「策略个股 N 只」不由它算 |
| Biz 字段 | `AIStkStgDetailBiz` 只有 `mCanCommit / m_bizErrorJoey / m_handler / m_handlerCallback / m_model / m_modelCallback` —— **biz 不持股票数据** |
| 命令表 | `EngineModel.m_methodMap` 15 条，取数 `biz_type_for_cmd_biz_get_stg_data -> AIStkStgDetailBiz.getStg()`，渲染 `view_type_for_cmd_view_show_stg_data -> AIStkStgDetailAct.showStg()`，另有 `autoRefresh / showStkQuote / delStg / updateStg / delTag / getExplain / updateName / delStgOK / delTagOK / showExpLain / proceeErrorBack / proceeBizErrorBack / dismissRequestDialog` |
| 发命令入口 | `EngineModel.setBizCommand(String)` / `setViewCommand(String)` / `setViewBiz(...)` / `excuteBook()` / `recvPublish(String, String)` / `clearViewBiz()` |
| 结果适配器 | `com.guosen.quotation.adapter.aistk.AIStkAdapter`，CTOR `(Context, List)`，字段 `List data`（渲染真源）/ `Map priceMap`（key 形如 `688135.2`）/ `showChoice` / `showFlashEffect` / `layoutResId=2132673931`，方法 `convert(BaseAdapterHelper, StockInfo)` / `setShowChoice(boolean)` |
| 列表容器 | **`ListView`（id `2132479882`）**，`getAdapter()` 返回 `android.widget.HeaderViewListAdapter`（含 2 个 header 行），`getWrappedAdapter()` 才是 `AIStkAdapter`，且与 Activity 字段 `m_stkAdapter` **是同一个对象** |
| 行对象 | `com.guosen.android.quotes.data.StockInfo`，稳定去重键 `String codeAndMarket`（实测 `688135.2`），另有 `String marketCode` |

两个决定性实验（探针已固化为可重放步骤）：

```text
# 实验一：让原生链路按另一条 stId 重取整页
1) 取页面 Activity 字段 m_stkModel（AIStkStgDetailModel），拿到 model.m_stg.id（实测 1630342260686065664）
2) 工作线程里 setField(model.m_stg, "id", "1569198903482388480") + setBizCommand(biz_type_for_cmd_biz_get_stg_data)
   → 全 logcat 只有 1 次 detail 请求，列表仍是 17 只（命令派发必须主线程）
3) 改在 act.runOnUiThread 里：先 setBizCommand(...)，再反射直调 biz.getStg()
   → POST /gsnews/strategy/queryStrategyQuoteDetail/1.0 的 stId 变成 1569198903482388480
   → 3 秒后 model.m_stkList=47，upNum=40，downNum=7（与 50e 那页真实数据一致）

# 实验二：把并集写回适配器，让原生列表渲染聚合结果
1) 首屏快照：adapter.data（17 项）+ upNum/downNum + adapter.priceMap
2) 重取第二页后快照：adapter.data（47 项）
3) 以 codeAndMarket 为键做 LinkedHashMap 保序并集 → 64 项（两段市值得 17+47，零交集）
4) 主线程：找 adapter 为 HeaderViewListAdapter 的 ListView → getWrappedAdapter() 拿到 AIStkAdapter
   写回 data=64 项、priceMap 合并、notifyDataSetChanged()
   → 日志 ListView n=66（64 行 + 2 header），滚动可见 70-100e 段与 50e 段股票混排
5) 头部：model.m_stg.name="聚合测试"、upNum/downNum 相加后调 showStg()，名称与涨跌家数立刻跟着变
```

实测结论：

1. **取数不用自己组包**：改 `model.m_stg.id` 再在主线程调 `biz.getStg()`，宿主用自己的 `session / openapi_sign` 重取任意 `stId`，客户端拿到的就是原生 `StockInfo`，鉴权零改造。
2. **渲染不用换适配器**：`AIStkAdapter.data` 就是渲染真源，`ListView` 外面只包了一层 `HeaderViewListAdapter`；写回 `data` + `notifyDataSetChanged()` 后行视图、排版、滚动全由宿主自己画，聚合页与原生态完全一致。
3. **头部文案分两类**：`m_stg.name`、`upNum/downNum` 走模型字段，`showStg()` 会跟；「策略个股 N 只」不跟 `data`（写回后仍显示 47 只），必须由模块按聚合结果自填 `tv_stg_res_num`。
4. **价格列跟着 `priceMap` 走**：只写 `data` 不带 `priceMap` 时，第二段股票的最新价会缺；合并两页 `priceMap` 后才完整（实测 `688486.2=50.11` 这类 key）。
5. **点击语义天然正确**：`data` 里放的是宿主自己的 `StockInfo` 实例，`onItemClick` 取到的就是同一对象；但宿主刷新会整表替换 `data`，聚合结果要在数据回调后重放一次。

探针踩坑（后续复现必看）：`AIStkStgDetailModel` 在 `onPackageReady` 阶段还没被 SecNeo 类加载器装载，按类名 dump 会 `ClassNotFoundException`，必须在页面 `onResume` 后用运行时的 `m_model` 反查；探针 APK 重装后 LSPosed 的「启用模块」开关可能被关掉，装完要回管理器重开并确认 `GSProbe ... 载入 pid=` 横幅；命令从工作线程发无效，一律 `runOnUiThread`。

---

## 3. 已确认的模块事实

### 3.1 进程模型与配置通道

[ARCHITECTURE.md](ARCHITECTURE.md):8-45：同一 APK 两套代码两进程 —— 模块进程 `io.github.MVennn.GSAuxiliary`（`ui/**` 设置界面）与目标进程 `com.guosen.android`（`GszxModule.kt` → `FeatureManager` → 各 `feature/**`）。写侧 `XposedService.getRemotePreferences("config")`，读侧 `XposedInterface.getRemotePreferences("config")`，由 LSPosed 守护进程托管持久化 + 跨进程同步 + 变更推送，免轮询。

[GszxModule.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/GszxModule.kt):36-145：`onPackageReady` 判包名 → `install(param.classLoader)`；`install()` 里 `val env = FeatureEnv(xposed, classLoader)` / `val configSource = ConfigSource(xposed)` / 「先绑 `configSource.onChanged` 再 `configSource.open()`」→ `env.config = configSource.snapshot` → `manager.installAll()`；注释原文「具体功能一律落在 feature/ 下，新增功能不需要改本文件」。

[feature/FeatureManager.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/FeatureManager.kt):55-74：`val features: List<Feature> = listOf(…)` 现有 18 个，注释「新增功能 = 在这里加一行。这是唯一的接入点。」；`installAll()` 半装钩子失败 `bag.rollbackTo(mark)`；`onConfigChanged(cfg)` 主线程补装 + 分发；`uninstallAll()` 先 `allOffSnapshot()` 让每功能还原视图现场再 `bag.unhookAll()`。

[feature/Feature.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/Feature.kt):1-51：`interface Feature { val id: String; val title: String; fun isEnabled(cfg: ConfigSnapshot): Boolean; fun install(env: FeatureEnv, bag: HookBag); fun onConfigChanged(env: FeatureEnv, cfg: ConfigSnapshot) {}; fun onActivityDestroyed(activity: Activity) {} }`；约定「install 里只做挂钩子，所有钩子句柄都要塞进 bag」「返回 false 时不安装，已装上的钩子仍在，功能内部仍要在钩子里判断开关」。

### 3.2 设置页现成模板

| 层 | 现成实现 | 出处 |
|---|---|---|
| 数据层 | `object QuickConfigStore`：remote prefs 键 `aistare_quick_config` 存 v1 JSON 字符串，`data class Scheme / Slot / Term / Data`，`FORMAT_VERSION = 1`，`EXTRA_ROUTE = "route"` / `ROUTE_QUICK_CONFIG = "aistare_quick_config"`，容错四原则「整体坏回落出厂 / 单条坏丢弃该条 / source 不认识丢弃该项 / 全坏回落默认」 | [core/QuickConfigStore.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/QuickConfigStore.kt):1-200 |
| 路由 | `internal sealed interface Route`（`QuickConfig` / `data class QuickConfigEdit(val schemeId: String)` / `QuickConfigButton` …），`RouteSaver : Saver<Route, String>`，`onOpenFeatureDetail when (id)` 映射，注释「新增带二级菜单的功能时在这里加一行」 | [ui/Routing.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/Routing.kt):32-51、:63-95、:271-284、:323-394 |
| 开关与入口 | `internal fun FeatureTab(onOpenDetail: (FeatureSpec, Boolean) -> Unit)`，`GsSwitchPreference` + `AnimatedVisibility(expandVertically(tween(220)) + fadeIn(tween(180)))` 展开 `BasicComponent` 入口行 `CenteredArrow()` | [ui/FeatureTab.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/FeatureTab.kt):39-123 |
| 展示元数据 | `internal data class FeatureSpec(val id: String, val categoryRes: Int, val switchTitleRes: Int, …, val detailEntry2Res: Int? = null)` / `val hasDetail: Boolean` / `FEATURE_CATALOG: List<FeatureSpec>` 顺序即界面顺序；`ui/**` 侧引用 `FeatureManager` 会把 `io.github.libxposed.api` 带进模块 App 进程触发 `NoClassDefFoundError`，故界面自维护元数据 | [ui/FeatureCatalog.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/FeatureCatalog.kt):77-82、:84-118、:121 |
| 行型与弹层 | 可配置列表逐条行 + 行尾 `✕` 删单条 + 新建弹层 + 清空 `ConfirmDialogSpec`，卡内不画线；贴底弹层 ≤420dp；键盘让位量 `root.height − (可见区 bottom − 根视图屏幕 y)`，160ms | [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md):139-248（§3.7 在 :201、§3.8 在 :221）、:316-427（§5.3 在 :339） |

### 3.3 新增配置的接线清单（五处齐补）

[core/ConfigKeys.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/ConfigKeys.kt):528-541 `ALL_KEYS`、:551-560 `FEATURE_KEYS`、:571-575 `FEATURE_PARAM_KEYS`、:592-602 `SNAPSHOT_KEYS`、:605-642 `DEFAULTS`；[core/ConfigSnapshot.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/ConfigSnapshot.kt):11-69 字段 + :102-141 `fingerprint`；[core/ConfigSource.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/ConfigSource.kt):41 变更监听用 `if (key != null && key !in ConfigKeys.SNAPSHOT_KEYS) return@OnSharedPreferenceChangeListener` 挡掉无关键，`read()` 逐键构造 30 字段快照。

⚠️ `SNAPSHOT_KEYS` 漏补 → 目标进程静默收不到变更。⚠️ `fingerprint` 现有 26 个布尔位占 0..25，Int 32 位只剩 6 位，再加布尔项 `bit shl 1` 在第 32 位溢出成 0 会让两个开关互相串；聚合配置建议走 **一个 JSON 字符串键**（照 `AISTARE_QUICK_CONFIG` / `ZX_COPY_RULES`），把布尔量收进 JSON，避免再吃位。

[EXTENDING.md](EXTENDING.md):40-143 四条架构红线：manifest 零 `xposedminversion`；`compileOnly("io.github.libxposed:api:102.0.0")` 与 `implementation("io.github.libxposed:service:102.0.0")`；模块 App 进程加载的类零 `import io.github.libxposed.api.*`；钩子一律 `bag.add(env.xposed.hook(m).setId("xxx").intercept { chain -> … })`。

### 3.4 跨功能共享结果的现成桥

[feature/stgfilter/StgFilterBridge.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgfilter/StgFilterBridge.kt):1-166：`object StgFilterBridge` 进程级单例，`class Snapshot(val kept: List<Any>, val keptIndices: IntArray, val source: List<*>, val owner: Any?)`，元素身份用 `===` 而非 `equals`，快照绑定到「算出它的那个列表实例」；`fun publish(kept, keptIndices, source, owner)` / `fullIndexAt(proxyIndex, owner)` / `fullIndices(count, owner)` / `keepOnly(source)` 用 `IdentityHashMap` / `runWithRemapBypassed` 用 `ThreadLocal<Boolean>`；`fullIndices` 长度必须与列表行数一致，任何一项缺失返回 `null`。

[FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md):227-241 的 `FilteredAdapter` 是关键：**不换宿主 `data`**，而是给 `ListView` 套一层模块自写代理，`getCount()` 返回子集行数、`getView / getItem` 用全量下标转调原 `adapter`、`registerDataSetObserver` 转发，滚动 / 翻页 / 惯性全原生。:245-263 记录真机坑：宿主 `AIStkStgDetailAct.onItemClick` 拿 `adapter.getCount()` 做范围校验却用**自己那份全量列表**取值，直接改 `data` 会让「全量下标 ≥ 筛选后行数」的行永远点不动（日志「筛选股票：全量 35 → 保留 1」「点击 position=1 → 映射成全量下标 2 转发给宿主」「页面一动不动」）。:324-338 给出下标回映射 `mapped = header + keptIndices[position - header]` 与 `AdapterView.mOnItemClickListener` 反射取法。

这两处合起来就是「聚合结果数据集 + 下标回映射 + 跨页归属校验」的现成模板，聚合页照抄即可。

---

## 4. 四条候选路线对比

| 维度 | A 结果页数据源 merge | B 原生页 + 代理适配器（推荐呈现） | C 按成员 `stId` 逐条取原生结果（推荐取数） | D 服务端 / 接口层聚合 |
|---|---|---|---|---|
| 做法 | 挂 `BaseAdapter.notifyDataSetChanged` 前置，把多个 `AIStkAdapter.data` 并进当前页 | 复用 `AIStkStgDetailAct` 全套资源，给 `lvStks` 装 `FilteredAdapter` 风格的 `MergeAdapter`，只换呈现的数据集 | 模块按成员 `stId` 逐个触发宿主自己的 `queryStrategyQuoteDetail` 取包，合并 `StockInfo` 后一次性喂给 B | 自己发 HTTP 组包或改服务端响应 |
| 原生观感 | 高（改的是数据） | 最高（行视图、菜单、多选、滑动、详情跳转全是宿主自己画的） | 高（结果对象仍是宿主 `StockInfo`） | 低（要自己造页面或改包） |
| 去重 | 合并时按 `codeAndMarket` 去重 | 由数据集侧保证 | 由数据集侧保证，最自然 | 取决于响应改造 |
| 排序 | 沿用宿主顺序 + 合并序 | 沿用宿主顺序 | 可定「成员序 / 代码序 / 涨幅序」 | 服务端定 |
| 分页 | 宿主整包返回，无分页参数 | 无分页，代理 `getCount()` 控制 | 每 `stId` 受服务端 100 只上限，N 个成员上限 N×100 | 可突破但改动面最大 |
| 刷新 | 宿主刷新会覆盖合并结果，需每次 `notifyDataSetChanged` 重放 | 代理还在列表上就 `update(下标表)` 不重建 | 逐包失败可单独重试 | 依赖网络栈 |
| 点击 / 全选 / 直达行情 | 下标语义会错位，必须回映射 | 必须走 `StgFilterBridge` 式 `fullIndices` 回映射，`StgSelectAllFeature` / `StgDirectQuoteFeature` 才对得上 | 与 B 同 | 与 B 同 |
| 失败回退 | 早退即展示宿主原列表 | 早退即摘代理、还原 `lvStks.adapter`，观感回原生 | 单包失败保留其余成员结果 + 计数文案标 degraded | 整页失败 |
| 可回滚 | 开关一关就停合并 | `onConfigChanged(false)` 还原现场 + `bag.unhookAll()` 可全撤 | 配置删掉成员即回退 | 需改回包 |
| 改动面 | 小，但踩 `data` 原地改的坑 | 中，复用现成 `FilteredAdapter` 骨架 | 中，依赖宿主取数时机 | 大且撞加固 / 鉴权 |
| 主要风险 | `AIStkStgDetailAct.onItemClick` 全量下标错位 | 代理身份判定被 R8 混淆（见 §8） | 并发取数时序 / 登录态 / `session` 过期 | `openapi_sign` 与 `userCode / session` 鉴权链，改造成本高 |

**取舍（按 §2.5 实机结论修订）**：A 已从「有坑」升级为**已实证可行** —— 直接改 `AIStkAdapter.data` 并 `notifyDataSetChanged()`，`ListView n=66` 实测渲染两段市值混排；`onItemClick` 的全量下标坑只在「自写代理适配器让 `getCount()` 与 `data` 不一致」时才出现，直接写 `data` 时适配器与宿主列表是同一份、行对象同一实例，点击天然对齐。D 改动面与鉴权成本都最大，且宿主加固 + 自研 `tcpb.*` 栈让改包变脆。**推荐 A（呈现）+ C（取数）**：C 用「改 `m_stg.id` + 主线程 `biz.getStg()`」让宿主自己取每一包，A 把并集写回 `AIStkAdapter.data`；中间用一份进程级 `MergeSnapshot`（照 `StgFilterBridge.Snapshot`）持去重后的 `StockInfo` 列表，B 的代理适配器仅在需要「行级过滤 / 不碰宿主 `data`」时启用。

---

## 5. 推荐方案设计

### 5.1 数据模型（模块进程 + 目标进程共用，放 `core/`）

照 `QuickConfigStore` 建 `StrategyMergeStore`，remote prefs 一个 JSON 字符串键 `stg_merge_groups`，v1 结构：

```json
{"v":1,"groups":[{"id":"g1","name":"市值50-500e + 创新高20日","enabled":true,"members":[{"stId":"<ID_A>","name":"流通市值 50-70e"},{"stId":"<ID_B>","name":"流通市值 100-500e"}],"dedupKey":"codeAndMarket","order":"member"}]}
```

`members` 里 `stId` 是宿主 `queryStrategyQuoteDetail` 唯一业务参数；`name` 只用于设置页展示与计数文案；`dedupKey` 固定 `codeAndMarket`（与 `ZXManager.m_stkGrpMap` 的 `code.market` 同构）。解析照 `QuickConfigStore.decodeOrNull` 做版本闸门 + `seenIds` 去重 + 坏数据回落。

### 5.2 设置页（模块 App 进程）

- 「功能」页 `category_strategy` 分区新增一项 `stg_merge`：`GsSwitchPreference` 总开关，打开后按 [FeatureTab.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/FeatureTab.kt):39-123 展开一行入口「策略整合设置入口」。
- 二级页 `StrategyMergeScreen`、三级页 `StrategyMergeEditScreen`，照 `QuickConfigScreen` / `QuickConfigEditScreen` 的三层路由（[Routing.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/Routing.kt):32-51、:63-95、:271-284、:323-394）加 `Route.StrategyMerge` / `data class Route.StrategyMergeEdit(val groupId: String)`，`RouteSaver` 的 `when` 必须补分支。
- 组列表按 [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md):201 的可配置列表条目行：逐条一行 `BasicComponent(title=组名, summary=成员数 + 状态句)`，行尾 `✕` 用 `AppIcons.Close` + `onSurfaceVariantActions` 中性色单条直删；底部「＋ 新建聚合组」用 `AppIcons.PlusThin` + `TextInputDialogSpec` 贴底弹层（:221、:316-427）；整批清空才走 `ConfirmDialogSpec`。
- 编辑页里成员逐条一行（标题 = 策略名称，副标题 = `stId`，行尾 `✕`）+ 新建弹层；弹层只问策略名称，校验拦在弹层：名称必填、组内不重名。`stId` 可留空，由目标进程按名称从 `StrategyStgIndex` 补齐（2026-11-11 起是一步弹层，原「先名称再 stId」两步已合并）。
- 注意 `FEATURE_CATALOG` 与 `FeatureManager.features` 两处都要登记（[FeatureCatalog.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/FeatureCatalog.kt):77-82、[FeatureManager.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/FeatureManager.kt):55-74）。

### 5.3 目标进程功能

新增 `feature/stgmerge/`：`StrategyMergeFeature.kt`（实现 `Feature`，`bag.add(...)` 挂 `Activity.onResume` / `BaseAdapter.notifyDataSetChanged` / `ListView.setAdapter`）、`StrategyMergeTargets.kt`（把 `act_ai_stk_stg_detail` / `lvStks` / `AIStkAdapter` / `tv_stg_res_num` / `tv_stg_res_time` 等候选常量集中，照 `StgStockFilterTargets.kt`）、`MergeAdapter.kt`（照 `FilteredAdapter`）、`MergeBridge.kt`（照 `StgFilterBridge`，进程级 `MergeSnapshot` 持 `merged: List<Any>` + `fullIndices: IntArray` + `owner: Activity`）。

首帧就要是聚合后的列表：[FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md):208-220 已经踩过「宿主把已填好数据的适配器直接 `setAdapter(...)`」与 `notifyDataSetChanged` 两条路，聚合同样要两条都挂，否则会「闪几帧未聚合列表」。

### 5.4 点击 / 全选 / 直达行情的下标契约

聚合后 `ListView` 眼里只有合并行数，宿主 `onItemClick` 仍按全量列表取值。照 [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md):324-338 做 `mapped = header + fullIndices[position - header]` 回映射；`StgSelectAllFeature` 的全选要走 `StgFilterBridge.fullIndices(total, owner)` 同款的 `MergeBridge.fullIndices`；`StgDirectQuoteFeature` 读 `adapter_key.stockList` 时要 `keepOnly` 过滤，避免滑到非聚合成员（[StgFilterBridge.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgfilter/StgFilterBridge.kt):8-18 真机案例：筛「自选」后能滑到「华北制药」）。

### 5.5 成员 `stId` 的两种读法

| 读法 | 做法 | 现状 |
|---|---|---|
| 读法 1：从「我的策略」列表页抓取（用户思路） | 挂 `AIStkStgListAct`，dump 列表适配器 / item 类型 / `stId` `name` `tags` 字段，设置页里勾选导入成员 | **证据缺口**：仓内 `AIStkStgListAct` 只在 [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md):121、:387 与 [FEATURE-DIRECT-QUOTE.md](FEATURE-DIRECT-QUOTE.md):388 出现三次，全是页面链路描述；`grep("(?i)queryStrategyQuoteDetail|stId|AIStkStgList|StgList|MyStrategy|Strategy\{|getStg|策略列表", app/src/main/java, "*.kt")` 31 命中全是策略**详情**页功能，零列表页 hook、零接口读取 |
| 读法 2：设置页手工登记 `stId` | 用户把分段策略的 `stId` 抄进模块设置；`stId` 由探针 `LAUNCH` extras 或列表页长按菜单取得一次即可 | 可先落地，绕开缺口 |

推荐顺序：先按读法 2 打通「配置 → 取数 → 合并 → 原生页呈现」闭环；探针补齐后把读法 1 做成设置页里的「从我的策略导入」入口，两种读法并存。

**现状（2026-11-11）**：v1 已支持 `stId` 留空 —— 成员以**名称**为识别键，手工登记 `stId` 降为**可选**。数据层（`StrategyMergeStore` 的 `decode` / `encode`）只在「名称与 `stId` 都为空」时才丢成员，组内按名称去重（名称空时按 `stId`），`stId` 空就照写空串；目标进程在 `m.stId` 为空时用 `StrategyStgIndex.idOf(m.name)` 按名称解析。设置页弹层因此只问策略名称，编号由目标进程现认。

### 5.6 取数、去重、刷新与回退

- 取数：沿用宿主自己的 `queryStrategyQuoteDetail` 通道（`stId` 唯一业务参数），按成员并发取 N 包；每包独立超时与重试，单包失败保留其余成员结果，计数文案标 degraded。
- 去重：按 `codeAndMarket` 用 `LinkedHashMap` 保序去重，保留首次出现成员的 `StockInfo` 实例（身份 `===`，交回宿主时对象类型与 `CLS_STOCK_INFO` 一致）。
- 排序：默认成员序（`order=member`），可选代码序 / 涨幅序；「涨幅」读 `PERCENT_FIELDS` 里的 `zdf`(BigDecimal)，读不到解析 `tv_item_stk_percent` 文案兜底（[FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md):393-400）。
- 计数：`tv_stg_res_num`「策略个股 N 只」与 `tv_stg_res_time` 由模块按聚合结果回填，避免显示某一成员的单包数字。
- 刷新：宿主 `notifyDataSetChanged` 时重算，结果与 `fullIndices` 一起原子替换；代理还在列表上只 `update()` 不重建（[FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md):273-292）。
- 回退：任一环节早退都摘代理、还原 `lvStks.adapter`、清零 `MergeSnapshot`，页面回到宿主原生单策略结果；每处早退按 [CODE-RULES.md](CODE-RULES.md):3.3 带原因日志。
- 可回滚：总开关关闭走 `onConfigChanged(false)` 还原现场；热重载走 `uninstallAll()` → `bag.unhookAll()`；聚合配置全在 remote prefs，删掉即回原生。

---

## 6. 增强项前置：补一轮策略列表页探针

[tools/probe/README.md](../tools/probe/README.md):25-33、:85-99、:125-134、:140-155 已有可照抄的框架类钩子（`Activity.onResume` → `ACTIVITY > …`、`Activity.startActivityForResult / startActivity` → `LAUNCH` 含全部 extras key + 类型 + 值、`dumpClassMembers(类名)` → `CLASS`、延迟视图树 → `PAGER / RV / VW`），输出 `XposedInterface.log(Log.ERROR, "GSRecon", …)`，从 `/data/adb/lspd/log/modules_*.log` 读，构建 `bash tools/probe/build.sh`。

状态（2026-11-11）：**详情页侧已全部拿到**，见 §2.5 —— 模型类 / 命令表 / 重取时机 / 适配器（含 `HeaderViewListAdapter` 包装）/ 去重键 `codeAndMarket` / 计数文案归属，全部有 dump 与注入实验支撑。**仍未完成**的是列表页这 5 项，只挡「从『我的策略』勾选导入成员」这条增强，不挡主闭环。

| # | 要拿到什么 | 用途 |
|---|---|---|
| 1 | `AIStkStgListAct` 的列表控件 id 与类型（`ListView` / `RecyclerView`）、适配器全限定名 | 决定读法 1 的挂点 |
| 2 | 策略 item 的数据类型全限定名 + 字段（`stId` / `name` / `tags` / `labelId` 候选） | 构造 `members` |
| 3 | 点策略进详情的 `LAUNCH` extras 全文（`adapter_key.stage=Strategy{id,name,tags=[labelId…]}` 的现场值） | 确认 `stId` 落在哪个 key，与 [CHANGELOG.md](../CHANGELOG.md):2353 对齐 |
| 4 | 策略列表自身的数据接口（若有）与分页参数 | 判断能否一次拿到全部 `stId`，以及是否受 100 只之外的另一层上限 |
| 5 | 策略对象是否可序列化 / 是否跨页复用同一实例 | 决定 `MergeSnapshot.owner` 的归属校验口径 |

探针产出按 [tools/contract/dump/README.md](../tools/contract/dump/README.md) 的规矩更新 `8.6.0-271-classes.txt` 与 `host-contract.json` 的 `host.versionName / host.versionCode`；`check_contract.py` 对「不在 dump 里」只提示不报错，别把「没扫到」当成「类已失效」。本轮详情页 dump 的类名与字段可直接回填 `host-contract.json`：`AIStkStgDetailModel` / `AIStkStgDetailBiz` / `AIStkAdapter` / `StockInfo.codeAndMarket` / 命令表 15 条。

---

## 7. 三个待拍板决策点

| # | 决策 | 选项 | 我的倾向 |
|---|---|---|---|
| 1 | 聚合结果挂在哪个原生页 | ① 复用 `AIStkStgDetailAct` 结果页（推荐）② 复用 `AIStkStgListAct` 列表页做「一个大类」入口再进详情 ③ 新建空壳 Activity | ① 最贴合「页面完全调用原生的页面实现」，且「看起来是原生的」由宿主自己画；② 可作为后续「大类入口」的增强 |
| 2 | 成员 `stId` 来源 | ① 设置页手工登记 ② 从「我的策略」列表页勾选导入 ③ 两者并存 | ③；先用 ① 打通闭环，探针补齐后补 ② |
| 3 | 取数时机 | ① 进结果页时并发拉 N 包 ② 模块侧预取缓存 + 进页读缓存 ③ 只在宿主自己取完单包后合并 | ① 语义最直白；② 体验更顺但要处理过期与登录态；③ 改动面最小但上限受单策略约束 |

这三点都属于「用户拍板更省返工」的取舍，实现阶段按选定值直接落地即可，探针补齐与配置模板不依赖它们。

---

## 8. 已知坑与规避（逐条带证据）

| 坑 | 原始证据 | 规避 |
|---|---|---|
| 原地改宿主持有的 `ArrayList` | [CHANGELOG.md](../CHANGELOG.md):2381-2398：「`adapter_key.stockList` 与 `AIStkAdapter` 是同一个 `ArrayList` 实例，原实现原地 `clear()+addAll()` … 第二次宿主按 `StockInfo` 强转 → `ClassCastException: IntentStockInfo cannot be cast to StockInfo (AIStkStgDetailAct.onItemClick:196)`」 | 构造全新 `ArrayList` + `Bundle(bundle)` 副本，绝不原地改；索引落点按对象引用记 |
| 全量下标 vs 聚合后位置 | [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md):245-263 真机日志「页面一动不动」 | 代理 `MergeAdapter` + `fullIndices` 回映射，`mapped = header + keptIndices[position - header]` |
| 代理身份被 R8 混淆 | [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md):294-318：「模块自己也开 R8，装机包里 `io.github...StgStockFilterFeature$FilteredAdapter` → `f2.m`，原先只靠类名含 `FilteredAdapter` 连自己刚装的都认不出」 | 本实例用 `proxiedLists[lv]` 身份直判；类名与 `getTarget()` 由 `app/proguard-rules.pro` 的 `-keepnames / -keepclassmembers` 保住 |
| 多选态 `checked states` | [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md):344-350：「搬状态会让勾选落到下一行」 | 只改转发给宿主监听器的 `position`，转发时 `runWithRemapBypassed` |
| 单策略 100 只上限 | [CHANGELOG.md](../CHANGELOG.md):2345-2399 | 聚合上限 N×100；计数文案按实际合并数，别承诺解除上限 |
| 配置变更静默丢失 | [ConfigSource.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/ConfigSource.kt):41、[ConfigKeys.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/ConfigKeys.kt):592-602 | 五处齐补，`SNAPSHOT_KEYS` 尤其漏不得 |
| `fingerprint` 位溢出 | [ConfigSnapshot.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/ConfigSnapshot.kt):78-97、:102-141 | 用一个 JSON 字符串键承载全部聚合状态，不再吃布尔位 |
| 加固静态反编译 | [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md):59-86、[tools/probe/README.md](../tools/probe/README.md):11-15 | 类名 / 方法 / 字段一律运行时探针取，静态只做清单与资源核对 |
| 热重载装饰现场 | [CODE-RULES.md](CODE-RULES.md):1.2、:1.2.1 | 注入即埋 keyed tag / 类名标记 / 可反射拆包代理三选一；`onConfigChanged(false)` 可撤销 |
| 日志脱敏 | [CODE-RULES.md](CODE-RULES.md):3.1 | INFO 及以上零完整「6位代码.市场号」，走 `maskStockCode()`；`userCode / session / openapi_sign` 零落盘 |

---

## 9. 后续实现的改动面（施工图，本轮零落地）

| 文件 | 改动 | 依据 |
|---|---|---|
| `core/StrategyMergeStore.kt`（新） | v1 JSON 组 / 成员 `stId` / 解析容错 | `QuickConfigStore.kt` |
| `core/ConfigKeys.kt` | 加 `STG_MERGE` 开关 + `STG_MERGE_GROUPS` JSON 键，补 `ALL_KEYS / FEATURE_KEYS / FEATURE_PARAM_KEYS / SNAPSHOT_KEYS / DEFAULTS` | §3.3 |
| `core/ConfigSnapshot.kt` / `core/ConfigSource.kt` | 加字段 + `fingerprint` 的 `hashCode()` 异或 + `read()` | §3.3 |
| `feature/stgmerge/`（新，4–5 个文件） | `StrategyMergeFeature / Targets / MergeAdapter / MergeBridge` | §5.3、§5.4 |
| `feature/FeatureManager.kt` | `features` 加一行 | :55-74 |
| `ui/FeatureCatalog.kt` / `ui/FeatureTab.kt` | `FEATURE_CATALOG` 加 `stg_merge` 一项、展开入口 | §5.2 |
| `ui/Routing.kt` / `ui/StrategyMergeScreen.kt` / `ui/StrategyMergeEditScreen.kt`（新） | 三层路由 + 列表 / 编辑页 | §5.2 |
| `res/values/strings.xml` | 文案按 [NAMING.md](NAMING.md) 走 | 「策略整合设置」「＋ 新建聚合组」 |
| `app/proguard-rules.pro` | `-keepnames / -keepclassmembers` 保 `MergeAdapter` | §8 |
| `tools/contract/host-contract.json` + `tools/contract/dump/8.6.0-271-classes.txt` | 探针补齐后回填列表页类名 | §6 |
| `docs/ARCHITECTURE.md` §3.2、`docs/UI.md` 功能页清单、`docs/NAMING.md` §5、候选常量表 | 按 [DOC-RULES.md](DOC-RULES.md) 变更触发矩阵同步 | 功能真正落地那一轮 |

---

## 10. 踩点过程与验收记录

### 10.1 子代理

按 §7/§14 判定「≥3目标=是、≥3处同类=是、≥10次工具=是、多视角=是」，同批派发三路只读分片：A 宿主智能选股链路 `eee23337-f8de-4768-b7be-42da14ec56e6`、B 模块架构与设置入口 `2b5c29b2-0fdd-4cab-9a3e-3365903fc144`、C 多策略聚合可行性 `df14b693-c123-4961-95d4-52d460c23aaa`，分片路径 `.work/recon-a-host-stock-ui.md` / `.work/recon-b-module-arch.md` / `.work/recon-c-merge-feasibility.md`。两次 `send_message` 补发「先读技能目录、codegraph 索引不可写直接 grep/glob/read、REA 边界、立即收口」。

验收原始输出（逐项核对实际产物，`self-report` 未作为结论）：

```text
pwsh Get-ChildItem .work -Filter "recon-*.md":
  recon-a-host-stock-ui.md lines=249 bytes=38262  (read :1-26 已核页面链 / AIStkAdapter / stId)
  recon-b-module-arch.md  lines=174 bytes=69588  (read 已核 FEATURE_CATALOG / ConfigKeys 五处 / StgFilterBridge)
  recon-c-merge-feasibility.md -> 无产物
list_agents(children) -> A inactive / B inactive / C running（三次催交）
git status --short -> ?? docs/FEATURE-STRATEGY-MERGE.md
```

A、B 两路落盘并经 `Get-ChildItem` 行数 / 字节 + `read` 首段 + 关键数字独立复现后验收通过；C 长时间空手，按 §14 回退口径「哪一路空手就原样贴错、标回进行中、由主线自己接这一块」，`interrupt_agent` 已发（`accepted=true`），四条路线对比 / 去重 / 排序 / 回退 / 三决策点由主线 §4 / §5 / §7 / §8 独立接管。⚠️ 带目录前缀的 `glob(".work/recon-*.md", path=...)` 在此仓返回 `paths:[]`，与 `Get-ChildItem` 实测冲突，统计分片改用 `pwsh Get-ChildItem` / `Get-Content`。

### 10.2 REA 报错原文

```text
rea inspect-android-package <apk> --format json --token-limit 1800
{"ok":false,"error":{"code":"UNSUPPORTED_OUTPUT_COMBINATION","message":"Token windows cannot preserve structured output. Remove --token-limit/--token-offset and use --filter-output or command pagination."}}

rea inspect-android-package <apk> --format json --filter-output
{"ok":false,"error":{"code":"MISSING_OUTPUT_VALUE","message":"--filter-output requires a value, as in --filter-output <value> or --filter-output=<value>."}}

rea list-adb-devices --json / rea inspect-adb-client --json
{"code":"capability_unavailable","category":"unsupported_provider","message":"The selected adb binary was not found: adb. Set REA_ADB_PATH to an absolute path to an existing adb binary.","retryable":false}

codegraph_status(projectPath="F:\Project\GSAuxiliary")
[ERR] Failed to get status: unable to open database file
[hint] codegraph needs a writable index directory (WAL: it creates -wal/-shm beside its SQLite db).

rea decode-android-resources <8.7.5.apk> --format json
{"code":"capability_unavailable","category":"unsupported_provider","message":"The apktool launcher was not found on the selected Windows PATH/PATHEXT. Set REA_APKTOOL_COMMAND to an absolute apktool launcher, or add its directory and extension to PATH and PATHEXT.","retryable":false,"remediation":{"action":"The apktool launcher was not found on the selected Windows PATH/PATHEXT. Set REA_APKTOOL_COMMAND to an absolute apktool launcher, or add its directory and extension to PATH and PATHEXT."},"details":{"provider_id":"apktool","operation":"decode_android_resources","reason":"The apktool launcher was not found on the selected Windows PATH/PATHEXT. Set REA_APKTOOL_COMMAND to an absolute apktool launcher, or add its directory and extension to PATH and PATHEXT."}}
```

修法：REA 结构化输出与 `--token-limit/--token-offset` 互斥，`--filter-output` 必须带值，正确写法是纯 `--format json`；`codegraph` 索引目录不可写按经验卡降级 `grep / glob / read`，零 `init`、零重试、零 escalation。8.7.5 宿主包改用纯 `--format json` 后 `REA_EXIT=0` / `JSON_BYTES=902254` 真拆包成功（Evidence 落 `.work/rea-guosen-8.7.5.json`）；`decode-android-resources` 因缺 apktool 未跑通，资源表解码本轮关闭，不补环境变量、不装工具。

### 10.3 失败工具清单

| 工具 | 来源 | 现象 |
|---|---|---|
| `codegraph_status` | 官方 PTC / CodeGraph 插件（来源未知） | `exitCode 1` / `unable to open database file`，可能有兼容性问题 |
| `rea doctor --client dsh --json` | REA CLI `rea@6.7.0` | `exitCode 1` 属预期诊断输出（`config_drift`），可能有兼容性问题 |
| `rea list-adb-devices` / `rea inspect-adb-client` | REA CLI | `capability_unavailable`（`REA_ADB_PATH` 未配置），可能有兼容性问题 |
| `rea inspect-android-package` 两次参数写法 | REA CLI | `UNSUPPORTED_OUTPUT_COMBINATION` / `MISSING_OUTPUT_VALUE`，已用正确写法跑通 |
| `rea decode-android-resources` | REA CLI / apktool | `capability_unavailable`（`REA_APKTOOL_COMMAND` 未配置、PATH/PATHEXT 无 apktool），资源表解码本轮关闭，可能有兼容性问题 |

### 10.4 仓库状态

`git status --short`（`workdir=F:\Project\GSAuxiliary`）基线 `exitCode=0`、stdout 空；收尾时新增本文、[README.md](README.md) 索引一行、[EXTENDING.md](EXTENDING.md) §5.2 一处引用，`tools/probe/` 两文件（实机验证所需，见 §10.5），以及 `.work/` 下 REA 中间产物与截屏（`.gitignore:66:/.work/` 已忽略）。`app/` 功能源码零改动、零 git 写操作，`git commit` / `push` 未执行。

### 10.5 MuMu 实机验证（探针 v33→v54）

环境：MuMu 12（Android 12）/ 宿主 `com.guosen.android` 8.7.1 / LSPosed 2.2.1 / 探针 `bash tools/probe/build.sh`，adb 走 `-s 127.0.0.1:16416`，导航脚本见 `.work/nav-detail.ps1`（force-stop → `UserLoginFlashAct` → 智能选股 → 我的策略 → 第一条策略）。全部结论来自日志 tag `GSRecon` 与截图，非静态推断。

| 版本 | 动作 | 结果 |
|---|---|---|
| v41 | `install` 按类名 dump `AIStkStgDetailModel` | `java.lang.ClassNotFoundException` —— 包名猜错且业务类此时未装载 |
| v42–v43 | 页面 `onResume` 后从 `m_model` 反查运行时类再 dump | 拿到真类 `com.guosen.quotation.model.aistk.AIStkStgDetailModel` + 全部字段 + 15 条命令表 |
| v44 | 工作线程改 `m_stg.id` 后 `setBizCommand(...)` | 全 logcat 只有 1 次 detail 请求，列表仍 17 只 —— 命令派发必须在主线程 |
| v45 | `runOnUiThread` 内 `setBizCommand` + 反射直调 `biz.getStg()` | 请求 `stId` 变为另一条策略，3 秒后 `m_stkList=47 / upNum=40 / downNum=7` |
| v46 | 写 `model.m_stkList` 后 `showStg()` | 头部 `name / upNum / downNum` 跟着变，但列表与「策略个股 47 只」不动 —— 列表真源是适配器 |
| v47–v48 | dump biz / 视图树找适配器 | biz 无股票数据；适配器 = `AIStkAdapter`（`data` / `priceMap` / `layoutResId=2132673931`） |
| v49 | 工作线程写 `adapter.data` + `notifyDataSetChanged` | `InvocationTargetException`，无渲染变化 |
| v50–v52 | 按 `RecyclerView` 找容器 / 调 `setNewData` | 先 `NoSuchMethodException: AIStkAdapter.setNewData`，再遍历视图树定位真容器 |
| v53 | 找 adapter 为 `HeaderViewListAdapter` 的 `ListView` → `getWrappedAdapter()` → 写 `data` + `priceMap` → `notifyDataSetChanged()` | **成功**：`data=64`（17+47 零交集），`ListView n=66`（64 行 + 2 header） |
| v54 | dump `Strategy` 实例字段 | 字段全表（§2.5），确认**无股票数量字段** |

关键日志原文：

```text
v44  改 id 后发命令：detail 请求次数=1，列表=17 upNum=16 downNum=1
v45  runOnUiThread 发命令+直调：stId=1569198903482388480，二次取数后 列表=47 upNum=40 downNum=7
v49  java.lang.reflect.InvocationTargetException（工作线程 notifyDataSetChanged）
v50  java.lang.NoSuchMethodException: com.guosen.quotation.adapter.aistk.AIStkAdapter.setNewData
v53  == v53 真实适配器=com.guosen.quotation.adapter.aistk.AIStkAdapter data=64 与页面字段同对象=true ListView n=66 ==
```

截图证据（截屏在 `.work/`，已被 `.gitignore` 忽略）：`.work/s14.png` 首屏仍是 70-100e 段股票，头部显示聚合名与 `涨家数 40 / 跌家数 7`；`.work/s15.png` 两次上滑后出现 50e 段股票（禾川科技 688320、腾龙股份 603158、联创电子 002036、泛亚微透 688386、咸亨国际 605056、亿嘉和 603666、华新科技 301265、红旗连锁 002697、百纳千成 300291、金杯汽车 600609、香山股份 002870、宿迁联盛）—— 两段市值混排在同一张原生列表里。

本轮代码改动只落在 `tools/probe/`（`build.sh` 与 `ProbeModule.java`，探针 v33→v54），`app/` 功能源码零改动；`git status --short`：`M docs/EXTENDING.md` / `M docs/README.md` / `M tools/probe/build.sh` / `M tools/probe/src/com/wb/probe/ProbeModule.java` / `?? docs/FEATURE-STRATEGY-MERGE.md`。

---

## 11. 本轮结论

「智能选股·我的策略聚合」已经不是在纸面上通得过的路，而是**在真机上跑通过的机制**：模块改 `model.m_stg.id` → 主线程让宿主自己按新 `stId` 重取（鉴权、签名、协议全用宿主的）→ 按 `codeAndMarket` 做保序并集 → 写回原生 `AIStkAdapter.data` + `notifyDataSetChanged()` → 原生 `ListView` 把两段市值策略的 64 只股票渲染成一张表，头部名称与涨跌家数同时生效。三条硬结论：取数不用自己组包，渲染不用换适配器，页面看起来就是原生页——因为它就是原生页在画。

剩下的都是产品化工作，不是可行性工作：`StrategyMergeStore` 一个 JSON 键存组与成员、设置页照 `QuickConfigScreen` 三层模板、`tv_stg_res_num` 计数文案由模块按聚合结果回填、宿主刷新时重放一次合并、任一环早退即还原现场。跨分段「50e–500e」本质上就是「多个 `stId` 成员的并集」，而「一个聚合大类」就是模块设置页里的一行组配置。唯一的可选增强是「从『我的策略』列表页勾选导入成员」——它只缺 `AIStkStgListAct` 的适配器与 `stId` 字段（§6 五问），不挡主闭环；先按设置页手工登记 `stId` 即可打通，探针补齐后再补导入入口，全程可回滚。

---

## 12. 落地实现与真机验收（2026-11-11 21:40）

> **一句话**：功能已从「机制通得过」走到「用起来不回落」，中间只隔着三个宿主行为坑，全部修掉并在 MuMu 实机通过 105 秒稳定性验收。

### 12.1 代码位置

| 文件 | 职责 |
|---|---|
| [feature/stgmerge/StrategyMergeFeature.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyMergeFeature.kt) | 接线与刷新接管：四处 hook（onResume / setAdapter / showStg / BaseAdapter.notify）+ 700ms 看门狗 + 重套与头部回填 |
| [feature/stgmerge/StrategyMergeTargets.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyMergeTargets.kt) | 宿主类名 / 反射字段名 / 资源 id 的唯一出处，`isPageAdapter` / `unwrapListAdapter` / `notifyAdapter` / `listField` 等原语 |
| [feature/stgmerge/StrategyMergeBridge.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyMergeBridge.kt) | 跨功能发布聚合结果（供其它功能读当前页并集） |
| [feature/stgmerge/StrategyStgIndex.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyStgIndex.kt) | 策略列表页 → `stId` 索引（成员登记用） |
| [core/StrategyMergeStore.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/StrategyMergeStore.kt) | 组与成员的持久化 |
| [ui/StrategyMergeScreen.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/StrategyMergeScreen.kt)、[ui/StrategyMergeEditScreen.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/ui/StrategyMergeEditScreen.kt) | 二级组列表页（新建 / 单条 ✕ / 清空）与三级组编辑页，按 [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md) 的可配置列表规范 |

### 12.2 三个必须绕开的宿主行为

| # | 宿主行为 | 真机现象 | 处理 |
|---|---|---|---|
| A | 数据未回来 / 刷新中会挂 `AIStkEmptyAdapter`，它把 `m_stkList` 每项当 `String` 强转 | `ClassCastException: com.guosen.android.quotes.data.StockInfo cannot be cast to java.lang.String`，进详情 9 秒崩 | **不写** `model.m_stkList`，并集只写适配器 `data`（宿主数据源唯一真源是适配器，§10.5 v46 已证） |
| B | `showStg()` 是「按宿主自己的 `m_stkList` 重画整页」，不是纯刷新头部 | 头部重试里每调一次，列表就从 64 只回落成 47 只，且回落发生在肉眼可见的 6 秒内 | 头部那几轮 `postDelayed` 重试里**不再调 `showStg()`**，只写「策略名称 / 涨家数 / 跌家数 / 策略个股 N 只」四处文本节点 |
| C | 宿主每轮刷新都会重写头部三处文本；也会**直接改 `data` 却不发 `notifyDataSetChanged`** | 名称回落「聚合测试」、家数回落 40/7、计数回落「47只」，而 `data` 里其实还是 64 项 | ① 头部写入放到「数据一致就早返回」之前（同值 `setText` 不闪）；② 前置 hook `BaseAdapter.notifyDataSetChanged` 抢在宿主重画前重套；③ 700ms 看门狗兜住「改了 data 不 notify」这条 |

补充两条实测细节：

- `BaseAdapter.notifyDataSetChanged` 才是宿主真正用的实现：先挂 `com.guosen.commonui.adapter.BaseQuickAdapter` 得「notify×0」，换成 `android.widget.BaseAdapter` 才「notify×1」。
- 重套目标必须固定成「聚合时那一个适配器」（`state.adapterRef` 的 `WeakReference`），不能按视图树重找：详情页里除股票 `ListView` 还有标签栏 `RecyclerView`，重找会写到一个没人看的列表上（真机日志「重套成功」而界面纹丝不动就是这么来的）。

### 12.3 刷新接管清单

| 时机 | 钩子 | 动作 |
|---|---|---|
| 进页 | `Activity.onResume` 后置 | 首次聚合（`start`） |
| 宿主挂适配器 | `ListView.setAdapter` 后置 | 认得出 `AIStkAdapter` 才聚合 / 重套，避免把 `StockInfo` 塞进空适配器 |
| 宿主重画 | `AIStkStgDetailAct.showStg` 后置 | 重套并集 + 回填头部 |
| 宿主改数据 | `android.widget.BaseAdapter.notifyDataSetChanged` **前置** | 抢在重画前把 `data` 换成并集副本 |
| 兜底 | 700ms `Handler` 看门狗（`WATCH_INTERVAL_MS`） | 巡检 `data` 与头部，不一致就纠回；页面销毁时 `stopWatch` |

重套一律写**副本**（`ArrayList(merged)`）而不是并集本体：宿主刷新会对 `data` 做 clear+addAll，交本体等于让宿主把私有快照改掉（实测 64 只被改成 47 只）。

### 12.4 真机验收（MuMu Android 12 / 宿主 8.7.1 / LSPosed）

| 时间点 | 界面 |
|---|---|
| 聚合完成（21:36:30） | 策略名称「聚合 · s1 + s2」、涨家数 56 / 跌家数 8、策略个股 64只 |
| 宿主首轮刷新（21:36:37） | 仍 64 只，计数时间戳跟着宿主走成 `(11-11 21:36:37)` |
| +105 秒（21:38） | 仍 64 只 / 56 / 8，无回落 |
| 滚到底 | 列表真有 64 行，第 64 只是「中电鑫龙 002298」，其下是原生风险提示 |
| 进程 | `pidof com.guosen.android` 存活，crash buffer 无 `FATAL` |

日志原文：

```text
策略聚合： 已渲染 64 只（成员 2 个，涨 56 / 跌 8）
策略聚合： 宿主刷新后已把并集重新套回（64 只，适配器 AIStkAdapter@118792554，写后 data=64）
策略聚合： [finish] 列表#0 ListView@263499138 lvAdapter=AIStkAdapter@168270284 inner=AIStkAdapter@168270284 data=64 count=66
```

`count=66` 是 64 行 + 2 个 header（宿主在 `getCount()` 里自加），与 §10.5 v53 的 `ListView n=66` 一致。

截图证据（`.work/` 已被 `.gitignore` 忽略）：`.work/a3.png`（105 秒稳定态）、`.work/z4.png`（滚到底见第 64 只）、`.work/b1.png`（清理诊断后的回归）。

### 12.5 门禁

```text
python tools/ci/check_links.py   → 文档链接通过（66 篇 Markdown / 274 链接 / 0 失效 / 0 孤儿）  exit 0
python tools/hygiene.py          → 243 项、685.9M，无新增冗余                              exit 0
./gradlew assembleDebug          → BUILD SUCCESSFUL
```
---

## 13. 聚合标签（2026-11-11 22:05）

> **一句话**：详情页「策略名称」下面那排 chip 也按聚合结果重算 —— 同前缀的数值区间取 min–max，离散标签去重保留，写回仍走宿主自己的 `AITagAd`。

### 13.1 成员标签为什么不能从详情页取

| 事实 | 实测证据 |
|---|---|
| 详情页模型 `m_tagList` 是 `List<com.guosen.android.aistock.data.Tag>`，Tag 只有 `tagId` / `tagName` / `isValid` 三个 String 字段、一个无参构造 | 探针读字段得到（`[tag] 类=… 字段=String isValid String tagId String tagName`） |
| **换 `stId` 取数后宿主不会跟着换标签** | 成员 s2 的 47 只已到账（`成员 s2 到位：47 只`），两轮 `absorbTags` 读到的都还是 s1 的 `[流通市值70-100亿, 量比1.5-2.0]` |
| 列表页 `Strategy.tags` 是 **JSON 字符串**，元素含 `labelName` | `[{"isValid":"1","labelId":"332921124713136128","labelName":"流通市值70-100亿"},{"isValid":"1","labelId":"1363924975269969920","labelName":"量比1.5-2.0"}]` |
| 「我的策略」列表页一次能采全表 | `已登记 8 条策略（8 条带标签），成员标签取自这一页` |

所以成员标签从**列表页**整表采集（[StrategyStgIndex](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyStgIndex.kt) 里存 `stId → 标签名`），详情页的 `m_tagList` 只当「当前页这一条」的兜底。

### 13.2 合并规则

| 类型 | 判据 | 处理 | 本例 |
|---|---|---|---|
| 区间型 | `^(.*?)(\d+(?:\.\d+)?)-(\d+(?:\.\d+)?)(.*)$`，前缀与后缀都相同的归一组 | 取 min–max | `流通市值50-70亿` + `流通市值70-100亿` → `流通市值50-100亿` |
| 离散型 | 其余标签 | 按文本去重，保留首次出现顺序 | `创新高20交易日` |

数字按「整数不带小数点、小数最多两位并去掉尾 0」输出（`1.5`、`2.0` → `2`），所以 `量比1.5-2.0` + `量比2.0-2.5` 会写成 `量比1.5-2.5`，本例只有一个成员带量比，结论是 `量比1.5-2`。合并函数是 [StrategyMergeFeature.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyMergeFeature.kt) 里的 `mergeTagNames`（`internal`，纯函数，无 Android 依赖）。

### 13.3 写回路径（按成功率排序）

| 顺序 | 手段 | 结果 |
|---|---|---|
| 1 | 适配器方法：`setNewData` / `setList` / `setData` / `refreshData` / `setNewInstance`，单参且参数是 `List` | `AITagAd` 一个都没有，全落空 |
| 2 | 数据字段 `mData` / `data` / `mItems` / `mList` + `notifyDataSetChanged` | **实测命中 `AITagAd.mData`** |
| 3 | 直接改 chip 的 `TextView` 文本（条数没变时兜底） | 只在标签数量不变时有效 |

Tag 实例必须用**宿主自己的类**造：`Class.forName("com.guosen.android.aistock.data.Tag")` 在模块 ClassLoader 里拿不到（实测造不出实例），改成取样本的 `sample.javaClass` 就正常。造出来的 Tag 抄样本的 `tagId` / `isValid`，只换 `tagName` —— 宿主点标签 / 删标签时按 `tagId` 认人。

### 13.4 刷新接管

标签条与股票列表同一个看门狗：`finish()` 写一次，之后每 700ms `reapplyIfNeeded` 里跟着补一次（内容相同即早返回，无闪烁）。宿主每轮重画标签后都会被拉回聚合结果。

### 13.5 真机验收（MuMu Android 12 / 宿主 8.7.1 / LSPosed）

| 时间点 | 界面 |
|---|---|
| 聚合完成 | 策略名称「聚合 · s1 + s2」、涨家数 56 / 跌家数 8、三个 chip `流通市值50-100亿` `量比1.5-2` `创新高20交易日`、策略个股 64只 |
| +28 秒 | 截图与上一次**字节完全相同**（`sha256 773902807b42f669…`），宿主两轮刷新都没把标签顶回去 |
| +60 秒 | 仍是三个 chip / 64 只 / 56-8；`pidof com.guosen.android` 返两个 pid 存活，crash buffer 无 `FATAL` |
| 滚到底 | 第 64 只是「中电鑫龙 002298」，其下是原生风险提示 |
| 进程 | `pidof com.guosen.android` 存活，crash buffer 无 `FATAL` |

日志原文：

```text
策略聚合： 已登记 8 条策略（8 条带标签），成员标签取自这一页
策略聚合： 汇总标签 4 条：流通市值70-100亿 | 量比1.5-2.0 | 流通市值50-70亿 | 创新高20交易日
策略聚合： 标签条走数据字段 AITagAd.{ mData } + notify
策略聚合： 标签条已换成聚合标签：流通市值50-100亿 + 量比1.5-2 + 创新高20交易日
策略聚合： 已渲染 64 只（成员 2 个，涨 56 / 跌 8）
```

截图证据（`.work/` 已被 `.gitignore` 忽略）：`.work/h3.png`（三个聚合 chip）、`.work/h4.png`（28 秒后逐字节相同）。

### 13.6 边界与降级

- **没进过「我的策略」列表页**（例如从其它入口直达详情页）：索引里没有成员标签，结果只显示当前页那条策略自己的标签 —— 不崩、不错位，只是没合并。进一次列表页就补齐。
- 底部「修改策略标签」仍是宿主原生入口，点进去改的是宿主自己的标签；聚合标签只作用于**这一页的显示**，不回写策略本身。
- 标签条合并结果按「首次出现顺序」排列，第一个成员的标签永远在前。

### 13.7 门禁

```text
python tools/ci/check_links.py   → 66 篇 Markdown / 274 链接 / 0 断链 / 0 孤儿   exit 0
python tools/hygiene.py          → 254 项、687.4M，无新增冗余                    exit 0
./gradlew assembleDebug          → BUILD SUCCESSFUL（清掉临时诊断后回归）
```

## 14 表面收敛：空态引导句与输入框文字色（2026-11-11 23:10）

### 14.1 两句空态引导句已删

| 位置 | 原文案（资源名） | 现状 |
|---|---|---|
| 聚合组设置的组卡片空态 | `stg_merge_groups_empty`「还没有聚合组，先新建一个」 | 不画占位行，卡片里直接是「新建聚合组」 |
| 编辑聚合组的成员卡片空态 | `stg_merge_members_empty`「还没有成员，先添加一个」 | 不画占位行，卡片里直接是「添加成员」 |

两条资源（`values` + `values-en`）一并删除，代码里只留一行注释说明「空表不画占位句」。空表就是空行，不再补引导文案。

### 14.2 输入框文字不跟随深浅色（全局修复）

现象：深色模式下「添加成员」「新建聚合组」这类弹层里，**输入内容的字是黑的**；浅色正常。

根因链（miuix 0.9.3 源码）：

1. miuix 有两个 `MiuixTheme` 重载。传 `ThemeController` 的那个会 `LocalContentColor provides colorScheme.onBackground`；**本项目用的 `MiuixTheme(colors = …)` 只 provide LocalColors / LocalTextStyles / LocalIndication / LocalOverscrollFactory，不 provide `LocalContentColor`**。
2. `top.yukonga.miuix.kmp.theme.LocalContentColor` 默认值是 `Color.Black`。
3. miuix `TextField` 的正文色 = `textStyle.color.takeOrElse { LocalContentColor.current }`，而默认 `textStyle`（`MiuixTheme.textStyles.main`）只定义了 `fontSize`、没有 color → 取到默认黑。
4. `TextFieldColors` 只有 `backgroundColor` / `borderColor` / `labelColor`，**没有 textColor**，调 `textFieldColors()` 也改不了字色。

修法（一处生效）：`ui/SettingsActivity.kt` 的 `AppTheme` 里，在已有的 `CompositionLocalProvider` 中补一行 ——

```kotlin
LocalContentColor provides MiuixTheme.colorScheme.onBackground,
```

仓内 5 处 `TextField`（`ui/AppDialogWidgets.kt:276`、`ui/QuickConfigEditScreen.kt:321` 与 `:601`、`ui/ZxCopyRuleEditScreen.kt:687` 与 `:766`）都只传 `backgroundColor`，因此这一行同时覆盖全部，之后新增的输入框也自动跟随。

### 14.3 真机验收（MuMu Android 12 / 横屏 1280x720 / 切换系统深浅色）

| 场景 | 结果 |
|---|---|
| 深色模式打开「添加成员」弹层，输入 `400e` | 文字为浅色、对比背景可读（修复前是黑字，见 `.work/s-current.png`）→ `.work/n9.png` |
| 系统切浅色（`cmd uimode night no`）后同一弹层再输入 `400e` | 文字为黑色 → `.work/n10.png` |
| 编辑聚合组成员卡 | 只有「＋ 添加成员」一行 → `.work/n8.png` |
| 聚合组设置页 | 「500e / 0 个成员 · 未生效」+「＋ 新建聚合组」，无占位句 → `.work/n7.png` |

### 14.4 门禁

```text
python tools/ci/check_links.py   → exit 0
python tools/hygiene.py          → exit 0
./gradlew assembleDebug          → BUILD SUCCESSFUL
```

> 2026-11-11 修复：添加成员后不入列表（`encode` / `decode` 丢弃空 `stId` 成员）—— 成员改以名称为识别键、`stId` 可留空且空串照样写盘，见 §5.5。

---

## 15 「添加成员」不入列表：根因、改法与真机验收

### 15.1 根因

| 层 | 旧行为 | 结果 |
|---|---|---|
| 编辑页 `ui/StrategyMergeEditScreen.kt` | 两步弹层：先问名称、再问 `stId`，第二步 hint 写着「可留空」 | 成员带着 `stId=""` 提交 |
| 数据层 `core/StrategyMergeStore.kt` | `decode` 与 `encode` 都以 `stId.isEmpty()` 为丢弃条件 | 成员一写盘就被过滤，`observe` 回流后列表仍是 0 个 |
| 目标进程 `feature/stgmerge/StrategyMergeFeature.kt` | 早就支持 `stId` 空 → 按名称查 `StrategyStgIndex.idOf` | 目标进程一侧本来就没问题 |

一句话：界面允许留空，存储层不允许留空，两者口径相反。

### 15.2 改法

- **识别键改成名称**：`MergeMember(stId, name)` 以 `name` 为键，只有「名称与 `stId` 都为空」才丢；组内按名称去重，名称空才退 `stId`（`decode` / `encode` 两处同口径，见 [StrategyMergeStore.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/StrategyMergeStore.kt):150-169、:206-227）。
- **`stId` 空串照写**：`encode` 不再跳过空 `stId` 成员，`"stId":""` 原样落盘，等目标进程按名称补齐。
- **弹层合并成一步**：只问策略名称。用户看不到 `stId`，字段自述可留空，保留第二步没有意义 —— 原「先名称再 `stId`」两步弹层与 `stg_merge_member_stid_*` 五条文案一并删除，判重文案改名 `stg_merge_member_duplicate`（中「这个策略名称已经在这个组里」/ en「This strategy name is already in this group」）。
- **单测**：新增 `app/src/test/java/io/github/MVennn/GSAuxiliary/core/StrategyMergeStoreTest.kt` 9 条 —— 空 `stId` 往返保留、`encode` JSON 里可见 `"stId":""`、老成员照旧、按名称去重、两名空成员才丢、名称空回落 `stId` 并按 `stId` 去重、`encode` 两次逐字相同、坏 JSON 回落空配置、空 id 组丢弃且组名回落「聚合组 1」。

### 15.3 真机验收（MuMu Android 12 / 横屏 1280x720 / debug 包 2026-11-12 00:01）

| 场景 | 结果 |
|---|---|
| 编辑页（成员 0）点「添加成员」 | 只弹一个贴底弹层：「添加成员」「照策略详情页显示的名称填」「策略名称」，没有第二步 `stId` 输入 |
| 输入 `TestA` → 确定 | 成员卡出现 `TestA`，副标题「打开一次宿主后自动识别编号」，组摘要「1 个成员 · 未生效，至少要 2 个策略」 |
| 再添加 `TestA` → 确定 | 弹层不收起，提示「这个策略名称已经在这个组里」 |
| 再添加 `TestB` → 确定 | 组摘要「2 个成员 · 生效」，两条成员都在 |
| `am force-stop` 冷启设置页 → 聚合组二级页 | 仍是「2 个成员 · 生效」（配置经 remote prefs 读回，不是内存态） |

存储侧证据（root）：

```text
strings /data/adb/lspd/config/modules_config.db | grep stg_merge
→ io.github.MVennn.GSAuxiliaryconfigstg_merge_groups
```

新版 LSPosed 把 remote prefs 存在 `modules_config.db` 里（前缀 = 模块包名 + group + key），模块自己的 `shared_prefs/config.xml` 只有本机状态（`disclaimer_accepted` / `log_level`），所以「配置文件里搜不到这个键」不代表没写盘。目标进程侧同一时刻的日志：

```text
I/LSPosedFramework (com.guosen.android)[io.github.MVennn.GSAuxiliary,…] 配置已更新（stg_merge_groups）指纹=-1854423081
I/LSPosedFramework (com.guosen.android)[io.github.MVennn.GSAuxiliary,…] 配置已更新（stg_merge_groups）指纹=-1320728983
```

### 15.4 门禁

```text
gradlew :app:testReleaseUnitTest --tests "*StrategyMergeStoreTest"  → tests=9 failures=0 errors=0
gradlew :app:assembleDebug                                          → BUILD SUCCESSFUL（app-debug.apk 12,265,726 B）
python tools/ci/check_links.py                                      → 66 篇 / 274 链接 / 0 断链 / 0 孤儿  exit 0
python tools/hygiene.py                                             → exit 0
```

---

## 16 列表页聚合入口、巡检与「名称 → stId」索引（2026-11-12 00:39）

### 16.1 三个症状与根因

| 症状 | 根因 | 证据 |
|---|---|---|
| 列表一直膨胀：三张卡都叫「聚合 · 创新高20日（4 条）」 | 巡检每轮挑一张成员卡改名，宿主的 `stgName` 不认「已处理」，下一轮又改名、又生成新入口卡 | 用户截图（三卡同名、chips 各异） |
| 标签与股票内容错乱 | 入口卡只带本页那一张成员卡的标签，其它成员的标签/行被拼进同一张卡 | 同一张卡的 chips 与股票预览互相不匹配 |
| 上拉刷新后聚合策略全部消失 | 入口卡名带「（N 条）」尾巴，宿主刷新后重算列表，改造点没在新列表里重放 | 刷新截图 `.work/shots/v3.png` 为空，`.work/shots/v4.png` 才补回 |
| 有时聚合有时不聚合（47 只 vs 212 只） | `StrategyStgIndex`（名称 → `stId`）只活内存，页面切换后新实例里是空的，成员全填名称就解析不出 | 日志「成员名称还没在宿主里见过，本轮先跳过：50e、100e、70e、500e」+「已解析 0 个」 |

### 16.2 改法

**入口卡命名（幂等键）** —— `feature/stgmerge/StrategyMergeFeature.kt`:143 `entryLabelOf(groupName, memberCount)` = `entryLabelBare(groupName) + "（N 条）"`；`:154` `entryLabelBare` 组名以「聚合」开头不再叠前缀、空组名回落「聚合策略」。

**入口名逆解析（详情页名字反查组）** —— `:167` `entryLabelGroupNames(label)`：带「（N 条）」的老写法返回 `[去条数全文, 去「聚合 · 」前缀的裸组名]`；不带条数时只认「聚合 · 」开头的写法（详情页头部写的就是这种）。普通策略名（`50e` / `创新高20日`）一律返回空，避免误改。

**详情页组匹配** —— 组匹配从「`m.stId == pageStId` 或 `m.name == pageName`」改为局部函数 `hits(m)`：`sid = m.stId.trim().ifEmpty { StrategyStgIndex.idOf(m.name.trim()).orEmpty() }`，`sid` 非空且等于 `pageStId` 即命中；否则比名称。全部不中时再用 `entryLabelGroupNames(pageName)` 与组名比对。原因：入口卡改名后宿主把带尾巴的名字带进详情页，成员名与组名都对不上。

**名称 → `stId` 索引落盘** —— `feature/stgmerge/StrategyStgIndex.kt` 从纯内存 object 改为「内存 + 宿主私有 SharedPreferences 缓存」：

| 位置 | 行为 |
|---|---|
| `:37-42` `PREFS_NAME = "gsaux_stg_index"` / `KEY_INDEX = "index"` / `FLUSH_DELAY_MS = 500L` | 不碰模块配置（`ModulePreferences`），用宿主自己的私有盘 |
| `:67` `bind(ctx)` | 幂等绑定 + 首次读盘，`decorateListNow()` 开头（`StrategyMergeFeature.kt:711`）与详情页匹配前（`:956`）各调一次 |
| `:75` `loadNow()` | 只补内存里还没有的键，不覆盖本轮采集；成功打「索引从磁盘恢复 N 条（累计 M）」 |
| `:154` `scheduleFlush()` / `:163` `flushNow()` | `remember` / `rememberTags` 值变了才写，500 ms 节流；序列化 `{"v":1,"names":{…},"tags":{…}}` |
| `:147` `namesSnapshot()` | 诊断日志用，未命中时把索引快照打进同一行 |
| `clear()` | 置 `dirty` 后立刻 `flushNow()`，盘上一起清 |

**巡检幂等锚点** —— `decorateListNow()` 的 `done` 判定同时接受带尾巴与裸名：`n == label || n == bare`，改名成功后两个名字都塞进 `entryLabels`，防止宿主把详情页的裸名带回列表后巡检再挑一张成员卡重改、列表重新膨胀。

**详情页标题** —— `:1262` `buildLabel(groupName, names)`：组名非空用 `entryLabelBare`（原来把 4 个成员名堆成「聚合 · 50e + 100e + 70e + …」三行，把涨跌家数挤下去），组名为空才回落成员名 `+` 拼接；超过 `StrategyMergeTargets.MERGE_NAME_MAX = 24` 截断加「…」。

**巡检日志节流** —— `lastListStat = data.size|indexed|tagCount` 变化才打，尾部日志只在 `renamed/retagged > 0` 时打（原来每 800 ms 两条）。

### 16.3 真机验收（MuMu Android 12 / 横屏 1280x720 / debug 包 2026-11-12 00:39:24，APK 12,265,718 B）

| 场景 | 结果 |
|---|---|
| 列表页（配置 1 组 4 成员，`stId` 全空） | 只有 1 张入口卡「聚合 · 创新高20日（4 条）」，合并标签 `流通市值50-500亿` / `创新高20交易日` / `流通市值500亿及以上` |
| 下拉刷新 | 刷新中显示「更新中…」，完成后入口卡与合并标签自动补回，仍只有 1 张 |
| 点入口卡进详情 | 四成员全到位：`50e` 47 只 → `100e` 94 只 → `500e` 14 只，累计 212 只、174 涨 / 36 跌，总耗时约 500 ms |
| 详情页标题 | 「聚合 · 创新高20日」（两行），不再堆成员名 |
| 从详情返回列表 | 截图与进详情前逐字节相同（108,235 B） |
| 返回后再下拉刷新 | 同样 108,235 B；日志「列表页：索引 8 条（累计 9），聚合入口 1 个、合并标签 1 张卡」 |
| `am force-stop` 冷启 → 点入口卡 | 日志「索引从磁盘恢复 9 条（累计 9）」，仍聚合 212 只 —— 内存索引空也能靠宿主盘缓存解析成员 |

坐标经验：截图坐标与 `uiautomator dump` 的 `bounds` 有系统栏偏移（「我的策略」按钮截图 y≈111，dump 为 `[543,139][678,192]` 即 y≈165），点不动时先 dump 取真实 `bounds`。

### 16.4 单测

`app/src/test/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyMergeTagTest.kt`：19 条，本轮补两条 —— 「详情页那种不带条数的入口名也能还原组名」（`entryLabelGroupNames(entryLabelBare("创新高20日"))` 等于 `["聚合 · 创新高20日", "创新高20日"]`）、「无条数入口名与带条数入口名指向同一个组」；保留「普通策略名不会被当成入口名」。

### 16.5 门禁

```text
gradlew :app:testDebugUnitTest --tests "*StrategyMergeTagTest"  → tests=19 failures=0 errors=0
gradlew :app:assembleDebug                                     → BUILD SUCCESSFUL（app-debug.apk 12,265,718 B）
python tools/ci/check_links.py                                 → exit 0
python tools/hygiene.py                                        → exit 0
```



## 17 收起成员卡：聚合就是把几条并成一条（2026-11-12）

### 17.1 用户看到的问题

- 四条策略聚合成一条之后，列表里 `50e` / `100e` / `70e` / `500e` **还是各占一行**，只有其中一张改名叫「聚合 · 创新高20日（4 条）」。用户的原话是「入口是直接绑定在 70e 上的对吧，既然我都聚合了，为什么其他的几个被聚合的策略不消失呢」—— 看着像聚合只改了个名字。
- 点这张卡进详情页，头部标题变成「聚合 · 50e + 100e + 70e + …」，与列表外层对不上：「聚合以后的名字为什么不是外面的那个名字，点进去以后会变成 xx+xx+xx」。

### 17.2 改法（全部在 `feature/stgmerge/StrategyMergeFeature.kt`）

| 位置 | 改动 | 为什么 |
| --- | --- | --- |
| `decorateListNow()` ② 选卡 | `done` 命中就用它；否则按 `g.members` 顺序找第一个匹配的卡（`sameMember`），不再按页面顺序取第一张 | 以前按页面顺序挑，用户看到「聚合卡绑在 70e 上」，排在它前面的成员还单独挂着 |
| `decorateListNow()` ② 末尾 | 新增 `toHide`：组内除入口卡之外的成员卡（`groupMemberOf != null`，identity 去重） | 聚合 = 并成一条，成员卡留着就是「没生效」 |
| `decorateListNow()` ③ | `data as? MutableList<Any?>`，逐个 `removeAt(indexOfFirst { it === h })`，一次 `notifyAdapter` | 从数据层摘掉，ListView 只画剩下的，不留空行、点击下标不错位 |
| `sameMember(m, item)`（新增） | `stId` 非空按 stId 比，否则按名称比；`groupMemberOf` 复用它 | 「选入口卡」和「收成员卡」必须是同一判据，否则会出现选了却不收、收了却不选 |
| `buildLabel(groupName, memberCount)` | 直接调 `entryLabelOf(groupName, memberCount)`，超 `MERGE_NAME_MAX`（24）截断；**删掉成员名拼接回落** | 列表卡和详情页标题必须是同一个函数算出来的，否则迟早再对不上 |

### 17.3 为什么删得掉、为什么不怕宿主刷新

- 真机日志实测 `data=5（ArrayList）`，`as? MutableList<Any?>` 成功，`removeAt` 立刻生效。若将来 data 换成不可变集合，只打一条 `列表数据不可修改（…）` 的 WARN 并放弃收起，不影响改名与标签。
- ListView 按 position 取 `data[position]`，数据删掉后 `getCount` 同步变小，不会出现空行，也不会点错。
- **不插回**：宿主下一次自己刷新会重建 data，成员卡自然回来。禁用组、删成员之后立刻恢复原样，不需要补丁代码。

### 17.4 真机验收（MuMu / Android 12 / `AIStkStgListAct`）

```text
收起前 data=8（量70 / 量100 / 量50 / 50e / 100e / 70e / 500e / 量500）
策略聚合： 列表页：索引 8 条（累计 10），聚合入口 1 个、合并标签 1 张卡、收起成员卡 3 张
下一轮：data=5，待收=0（已稳定，不再改动）
```

- 列表只剩一张「聚合 · 创新高20日（4 条）」，`100e` / `70e` / `500e` 三张卡消失，`50e` 那张原地变成入口卡。
- 点入口卡进详情页：头部 `聚合 · 创新高20日（4 条）`，与列表一字不差；成员 4 个全解析，212 只（涨 174 / 跌 36）。
- 之前从同一张卡进只有 47 只，是因为当时索引只采到部分成员；索引落盘（§16）之后四成员都能解析，212 只才是并集真值。

### 17.5 门禁

```text
gradlew :app:testDebugUnitTest --tests "*StrategyMergeTagTest"  → tests=19 failures=0 errors=0
gradlew :app:assembleDebug                                     → BUILD SUCCESSFUL
python tools/ci/check_links.py                                 → exit 0
python tools/hygiene.py                                        → exit 0

## 18 并集不许残缺、家数不许跳：缓存秒套 + 成员重试 + 累加隔离（2026-11-12）

### 18.1 用户看到的问题

> 「日涨跌幅和涨价数会跳，策略个股不能稳定获取，有时候是 212 有时候又是 100 多。是否需要增强判断保证最后结果是正确」

三条症状，三个互不相干的根因，修一条不会顺带修好另一条：

| 症状 | 根因 | 具体位置 |
| --- | --- | --- |
| 同一张入口卡，一会儿 212 只、一会儿 100 多只 | 某个成员的取数等超时（`MEMBER_TIMEOUT_MS` 原 2000 ms，实测宿主换列表要 1~3 秒），标 `degraded` 后 `finish()` **照样把残缺并集写回**，之后看门狗一直套这份残缺数据 | `StrategyMergeFeature.kt` `poll()` 超时分支 + `finish()` |
| 涨跌家数在宿主刷新后跳回单策略的值 | 头部数字宿主是按 `model` / `m_stg` 上的 `upNum`、`downNum` 重画的，我们只补 TextView 文本，它下一轮就盖回来 | `reapplyIfNeeded()` |
| 每进一次详情页，涨家数就往上加一截 | 聚合值写回 `model` 之后，**下一轮取数时 `absorbSeed` / `poll` 又从同一个字段读家数**，把上一轮的聚合值当成某个成员的统计再加一遍 | `absorbSeed()` / `poll()` 的累加 |

修复前实测的家数序列（同一入口卡连进 4 次）：`174/36 → 308/65 → 442/94 → 576/123 → 710/152`，每次固定 `+134 / +29`。

### 18.2 新增：结果缓存 `feature/stgmerge/StrategyMergeCache.kt`

| 项 | 值 |
| --- | --- |
| key | `组 id + "\|" + 成员 stId 序列`（成员集合变了就是另一份结果） |
| 存什么 | `Snapshot`：去重后的 id 序列（`sameAs` 用它比）、`WeakReference` 指的合并列表、涨跌家数、标题、合并标签、时间戳 |
| 容量 | `MAX_ENTRIES = 8`，accessOrder LRU |
| 有效期 | `SNAPSHOT_TTL_MS = 10 分钟`，时钟回拨按过期处理 |
| 为什么是弱引用 | 缓存只是「上次那份列表」的旁证，宿主重建 data 后老实例该被回收；被回收就当没有，重新跑一轮 |

### 18.3 改法一览

| 文件 / 位置 | 改动 | 为什么 |
| --- | --- | --- |
| `StrategyMergeTargets.kt` 计时区 | `MEMBER_TIMEOUT_MS` 2000 → **3000**；`TOTAL_TIMEOUT_MS` 8000 → **16000**；新增 `RETRY_ROUND = 1`、`SNAPSHOT_TTL_MS` | 2 秒会把「刚好换完列表」判成超时；总预算要容得下 4 成员 × 3 秒 + 一轮重试 |
| `start()` | 命中缓存先 `applySnapshot` **秒套**，日志「先用上次的 N 只（缓存 Xms 前），后台校验这一轮」 | 用户第一眼就是完整并集，不用盯着 1~3 秒的单策略列表 |
| `applySnapshot()` | 只写列表、标题、计数、标签文本；**不写** `model` / `m_stg` 的 `upNum`、`downNum` | 取数窗口里这两个字段是宿主报「这份响应涨跌家数」的唯一通道，写进去就污染下一轮累加 |
| `MergeRun` 成员队列 | 删 `index`，改 `ArrayDeque` + `retried` 集合 + `requeue()` | 单个成员超时/被抢 id 时排到队尾重试一次，不再直接把整轮打成 `degraded` |
| `poll()` | 新增 id 守卫：`m_stg.id` 被宿主 `autoRefresh` 改回本页时，节流 600 ms（`ID_RESEND_MIN_INTERVAL_MS`）重写 id + 重发取数，**这一轮不 absorb** | 以前无条件吸收，等于把本页数据当成成员并进并集，同时把并集撑大 |
| `finish()` 完整性闸 | `degraded && cached.size > uniq.size` → 保留上次那份，日志「本轮残缺（N 只…），保留上次的 M 只不覆盖」 | 残缺不许覆盖完整，这是「一会儿 212 一会儿 100 多」的止跳点 |
| `finish()` 同结果短路 | `sameAsCached` 时不重写 `adapter.data`，日志「本轮与上次结果一致（N 只），列表不重写」 | 进页面秒套的就是这份内容，重写只会让列表闪一下 |
| `finish()` 家数沿用 | 结果一致时把本轮自己累加的家数换成缓存里那份，日志「本轮家数 X/Y 少了本页那份，沿上次的 A/B」 | 换 id 取数期间宿主会把 `model` 的统计清掉，本页那份读成 0（真机：174/36 → 134/29，少的正是本页 47 只的 40/7） |
| `addStat(size)`（新增） | 累加前校验 `涨 + 跌 ≤ 本次列表只数`，不满足打 WARN 并丢弃 | 挡住「只换了列表没换统计」的脏值，是上述 `+134` 的直接解药 |
| `reapplyIfNeeded()` | 家数字段补写加 `!state.running` 条件 | 取数窗口内绝不写这两个字段；跑完之后才让看门狗 700 ms 一跳纠偏 |

### 18.4 为什么三道闸缺一不可

- **只秒套不隔离累加** → 更糟：每轮都从上一轮的聚合值起累加，`+134` 变成固定叠加。
- **只隔离累加不秒套** → 每次进页面仍要空等 1~3 秒，用户第一眼看到的是宿主的单策略列表。
- **只加重试不做完整性闸** → 重试本身有预算上限，预算耗尽时那一轮仍然是残缺的，必须拒绝写回。

### 18.5 真机验收（MuMu / Android 12 / `AIStkStgDetailAct` / 同一入口卡连进 5 次）

```text
第 1 次（缓存空）
  成员 100e 到位：94 只（新增 94），累计 141 只，用时 121ms
  成员 70e  到位：57 只（新增 57），累计 198 只，用时 121ms
  成员 500e 到位：14 只（新增 14），累计 212 只，用时 121ms
  已渲染 212 只（成员 4 个，涨 174 / 跌 36）

第 2~5 次（每次都是同一串）
  先用上次的 212 只（缓存 3769ms 前），后台校验这一轮
  本轮与上次结果一致（212 只），列表不重写
  本轮家数 134/29 少了本页那份，沿上次的 174/36
  已渲染 212 只（成员 4 个，涨 174 / 跌 36）
```

| 验收点 | 结果 |
| --- | --- |
| 进页面第一眼 | 0.9 秒截图即 212 只 / 174 涨 / 36 跌，标题「聚合 · 创新高20日（4 条）」 |
| 反复进出 5 次 | 5 次全部 212 只、174/36，不再出现 100 多 |
| 静置 30 秒 | 头部家数不动；个股行的最新价 / 涨跌幅照常实时刷新（预期行为） |
| 列表页 | 仍只有 1 张入口卡、收起 3 张成员卡（§17 不回归） |

### 18.6 接管不了的部分

- 头部「日涨跌幅 +1.58%」由宿主按自己的缓存算，翻遍 `StrategyMergeTargets` 也没有对应字段（`grep -E "涨跌|PCT|RATE"` 零命中），写不进任何东西。能做的是让并集与家数稳定、缩短宿主抢 id 的窗口，减少它重算的次数。
- 个股行的涨跌幅是实时行情，本来就该动，不属于「跳」。

### 18.7 单测与门禁

```text
app/src/test/.../StrategyMergeCacheTest.kt    → tests=9  failures=0（新鲜度 / 过期 / 时钟回拨 / 覆盖 / 淘汰 / 弱引用被回收）
app/src/test/.../StrategyMergeTagTest.kt      → tests=19 failures=0
gradlew :app:testDebugUnitTest :app:assembleDebug → BUILD SUCCESSFUL
python tools/ci/check_links.py                → exit 0
python tools/hygiene.py                       → exit 0
```

---

## 19 筛选联动：计数栏写「筛后/总数」，两个功能不再抢同一个 TextView（2026-11-12）

### 19.1 现象与根因

详情页用「··· → 筛选股票」筛完之后，列表只剩筛后的行，但左上角仍写「策略个股212只(时间)」，看不出筛剩多少。

`tv_stg_res_num` 这一个 TextView 被两个功能同时写：

| 功能 | 写什么 | 位置 |
| --- | --- | --- |
| stgfilter | 筛选生效时写「策略个股 36/212」，还原时写「策略个股212只」 | `feature/stgfilter/StgStockFilterFeature.kt` 的 `updateResNum(act, kept, total, active)` |
| stgmerge | 只按**并集只数**写「策略个股212只(时间)」 | `writeResNum(act, resNumText(count, cur))` 三处 |

日志顺序能直接看到覆盖：stgfilter 先写对了 `筛选股票：左上角计数 → 策略个股 36/212`，紧接着 stgmerge 的看门狗（每 700 毫秒一轮 `reapplyIfNeeded`）把同一个 View 改回「策略个股212只」。

### 19.2 判据：不认类名，认「列表挂出来多少行」

筛选代理是模块自己装的 `StgStockFilterFeature.FilteredAdapter`，但它会被 R8 混淆（发布构建里叫 `f2.m` 之类），按类名认不可靠。改成看**列表现在挂出来多少行**：

```kotlin
// StrategyMergeFeature.kt:1417
private fun shownStockRows(act: Activity): Int?
    // lv.adapter 剥掉 HeaderViewListAdapter → inner
    // inner 是宿主原生 AIStkAdapter → 没筛，返回 null
    // inner 不是原生、但字段里有个「声明类型是 ListAdapter、值本身是原生适配器」的 → 认作筛选代理
    // 返回 la.count - getHeadersCount() - getFootersCount()
```

三个守卫各有来由：

- **剥 HeaderViewListAdapter**：详情页的「策略名称 / 涨跌家数 / 标签 / 计数栏」全在 ListView 的 header 里，不扣掉头尾行数字会偏大。
- **inner 是原生适配器就返回 `null`**：宿主的空适配器（`AIStkEmptyAdapter`）和标签页的 `AITagAd` 也会出现在这个页面上，不能一律当成筛选态，否则进页面那一瞬会写成「策略个股 0/212」。
- **代理识别走字段类型 `android.widget.ListAdapter`**：framework 接口不会被改名，比类名稳；筛到 0 只时基座照样在，所以 0 行也认得出是筛选态。

### 19.3 改法一览

| 位置 | 改动 |
| --- | --- |
| `StrategyMergeFeature.kt:1392` | 新增 `writeMergedResNum(act, state, total)`：`shown != null && total > 0 && shown < total` 判为筛选中，写 `RES_NUM_FILTERED_FORMAT`（「策略个股 %d/%d」），否则按原格式写并集只数 |
| 三处写计数 | `reapplyIfNeeded`(:641) / `applySnapshot`(:712) / `finish`(:2085) 全部改调 `writeMergedResNum`，不再各写各的 |
| `StrategyMergeTargets.kt:251` | 新增 `RES_NUM_FILTERED_FORMAT = "策略个股 %d/%d"`，与 stgfilter 的 `OUR_RES_NUM`（`^策略个股 \d+/\d+$`）逐字对齐 |
| `PageState.hostResNumTail`(:1567) | `captureOrigin`(:1274) 进页面时把宿主原样的时间戳尾巴记下来：筛选态那串「36/212」用 `RES_NUM_REGEX` 抽不出尾巴，回落要用这一份 |

### 19.4 为什么两边格式必须完全一样

stgfilter 非筛选态只在 `OUR_RES_NUM.matches(cur)` 为真（即当前是「36/212」）时才改写，stgmerge 这边 `if (cur == want) return`。两边写同一串字符时互相都是 no-op，谁也不会把对方的字擦掉；差一个空格就会变成每 700 毫秒互相覆盖一次、肉眼可见地闪。

### 19.5 真机验收（MuMu / Android 12 / 宿主 8.7.5 / 聚合 212 只 4 成员）

| 操作 | 计数栏 | 附注 |
| --- | --- | --- |
| 进详情页（筛选条件为「跌家」仍生效） | 策略个股 36/212(11-12 01:16:45) | 列表同步只剩跌家股票 |
| 「··· → 筛选股票 → 重置 → 确定」 | 策略个股212只(11-12 01:16:52) | 尾巴换成本轮时间，列表回全量 |
| 改筛「涨家」 | 策略个股 174/212(11-12 01:16:52) | 与头部「涨家数 174」对得上 |
| 退出再进（筛选仍为涨家） | 策略个股 174/212(11-12 01:17:54) | 重进不再被写回 212 |

对应日志：

```text
策略聚合：计数 → 策略个股 174/212（筛选中：列表 174 行 / 并集 212 只）
筛选股票：筛选未生效，已还原全量（212 只）
策略聚合：宿主刷新后已把并集重新套回（212 只，适配器 AIStkAdapter@…，写后 data=212）
```

### 19.6 已知残留（两条都不影响结论）

- **进页面第一瞬可能闪一个偏小的分子**：宿主列表是分页懒加载，页面刚起来时只有十行左右，此时 174 还没并进来，会先写「策略个股 8/212」，看门狗在 1 秒内纠正成 174/212。要彻底消掉得等 `state.applied` 之后再判筛选态，代价是筛选态第一秒没有数字，权衡后保留现在的行为。
- **头部「涨家数 174 / 跌家数 36」始终是全量口径**：聚合的家数统计本来就按并集算，不随筛选变（筛选只影响列表行与计数栏），与宿主原生策略的行为一致。

### 19.7 门禁

```text
gradlew :app:testDebugUnitTest :app:assembleDebug → BUILD SUCCESSFUL
app-debug.apk → 12,265,717 字节（2026-11-12 01:13）
python tools/ci/check_links.py → exit 0
python tools/hygiene.py        → exit 0
```

### 19.8 筛选状态按**组**保存（跟着「按策略保存筛选条件」走）

聚合页开筛选时，那份状态记在**哪个组**头上，不能靠页面当场猜：聚合期间宿主把
`m_stg.id` 临时换成成员策略的 stId、把 `m_stg.name` 改写成「聚合 · 组名（N 条）」，
两个当场身份都不可信。所以改由聚合侧显式登记：

- `StrategyMergeFeature.start()` 在**改宿主之前**（`applySnapshot` 之前）调
  `StockFilterScopes.rememberMergePage(act, group.id, group.name)`；
- 撤销点两处：`releaseRollback()`（关掉「策略整合」/ 换页时还原宿主）与 `onActivityDestroyed()`。
- 登记之后本页的作用域就是 `grp:<组 id>`，**组优先于策略**：同一组的成员怎么换、成员策略自己那份条件怎么改，
  都不会串到组页上来；反过来组页筛完退出，成员策略页仍用它自己那份。

开关关着（出厂）时 `scopeOf` 恒为 `null`，读写还是历史的全局键，聚合页与别的页共用一份 —— 行为与本节之前一模一样。
详见 [`FEATURE-STG-FILTER.md`](FEATURE-STG-FILTER.md) §六「按策略保存筛选条件」。

## 20 条数口径统一：列表卡 / 设置页 / 详情页都按「展开后」数（2026-11-12 10:28）

> **一句话**：口径成员（`stId` 留空、名字就叫「中盘 1.5-4」这种）在宿主里没有对应策略卡，列表入口卡的「（N 条）」只能由模块自己算。之前只算了成员条数（2 条），详情页按展开后的组合数取数（13 只口径），两处对不上，用户以为聚合没生效。

### 20.1 现象与根因

| 现象 | 根因 |
| --- | --- |
| 列表卡写「聚合 · 中盘 1.5-4（2 条）」，点进去却取 13 份数据 | 列表装配用 `g.members.size`；一个口径成员在运行时展开成 `markets × ratios` 个临时策略 |
| 设置页摘要同样按成员数显示 | `StrategyMergeScreen.mergeMemberCount` 是第二份实现，各写各的 |
| 成员数变化后卡名停在旧数字 | 改名幂等三路（新 label / 裸名 / 成员原名）都匹配不到旧卡，宿主卡名再也不会被改写 |
| 卡的标签缺整个市值维度 | 口径成员没有原生 `tags`，`groupTagNames` 只从成员标签里取 |

### 20.2 改法一览

| 位置 | 改动 |
| --- | --- |
| `StrategyComboCatalog.kt` | 新增 `bucketOfMember(name, stId)`：**判据唯一实现** —— `stId` 为空且名字命中 `BUCKETS` 的中文名或 key 才算口径成员；新增 `expandedCount(members)` 累加 `comboCount`、其余按名字（空则 `stId`）去重 +1 |
| `StrategyComboCatalog.kt` | 新增 `tagNamesOf(bucket)` / `tagNamesOfKey(comboKey)`：把口径反推成宿主原生标签写法（`流通市值50-70亿`、`量比1.5-2.0`），供标签汇总用 |
| `StrategyMergeFeature.kt` 列表装配 | `g.members.size` → `StrategyComboCatalog.expandedCount(g.members)`，`< 2` 的门槛不变 |
| `StrategyMergeFeature.kt` 改名幂等 | `done` 之后补一路 `stale`：名字带「（N 条）」且去条数后等于本组名 → 认作旧卡，改名并据此定位 |
| `StrategyMergeFeature.kt` `groupTagNames` | 循环开头按 `bucketOfMember` 补 `tagNamesOf(bucket)`，否则合成卡的标签只剩「创新高20交易日」这类真实成员标签 |
| `StrategyMergeFeature.kt` 详情页标签汇总 | `sid.startsWith(COMBO_PREFIX)` 时走 `tagNamesOfKey(sid)`，其余仍走 `StrategyStgIndex.tagsOf(sid)` |
| `StrategyMergeScreen.kt` | 删掉 15 行私有 `mergeMemberCount`，改为 `StrategyComboCatalog.expandedCount(group.members)` |

### 20.3 为什么必须只有一份实现

三处数条各自实现的话，改一处漏两处就是「列表写 13、设置页写 2、详情页取 13」。现在单测 `StrategyComboCatalogTest` 的 11 个用例直接锁住 `expandedCount` 与 `bucketOfMember`，谁再写第二份实现编译期就会打架。

### 20.4 真机复验（MuMu / Android 12 / 宿主 8.7.5）

| 入口卡 | 修复前 | 修复后 | 标签 |
| --- | --- | --- | --- |
| 聚合 · 中盘 1.5-4 | 2 条 | **13 条** | 流通市值50-500亿 + 量比1.5-4 |
| 聚合 · 中盘 4+ | 2 条 | **4 条** | 流通市值50-500亿 + 量比4.0以上 |
| 聚合 · 大盘 1.5以上 | 3 条 | **6 条** | 流通市值500亿及以上 + 量比1.5-4 + 量比4.0以上 |
| 聚合 · 创新高20日 | 4 条 | 4 条 | 流通市值50-500亿 + 创新高20交易日 + 流通市值500亿及以上 |

详情页（点「中盘 1.5-4」）：策略名「聚合 · 中盘 1.5-4（13 条）」，标签条两条，12 个组合逐个 `create → query → delete`（120–371 ms 级），末条 `到位：84 只（新增 27），累计 787 只`，日志 `汇总标签 9 条` → 区间合并后界面 2 条。

同一页同时验到筛选联动仍有效：自动恢复的「涨家」条件把 787 只筛成 563 只，计数栏写 `策略个股 563/787`（对应日志 `筛选股票：全量 787 → 保留 563（涨 563 / …）`）。

### 20.5 已知残留

- **合成出来的区间标签（`流通市值50-500亿`、`量比1.5-4`）在列表卡上显示为灰色 + ⓘ**：宿主按自己标签库里的 `labelId` 决定 chip 样式，区间文本不是它库里的条目。内容与取数完全正确，只是样式不同；详情页同一批标签是白色正常（走 `model.m_tagList` 的 `tagName/tagId` 路径，`newTag` 复制了源元素的 `tagId`）。要白化得先反查宿主 chip 渲染判据，宿主加固壳无 dexdump，成本高于收益，暂留。
- **头部涨跌家数仍是全量口径**：见 §19.6 第二条，未变。

### 20.6 门禁

```text
gradlew :app:testDebugUnitTest :app:assembleDebug → BUILD SUCCESSFUL
  25 个测试 XML 全 0 失败（StrategyComboCatalogTest tests=11 failures=0）
app-debug.apk → 12,267,722 字节（2026-11-12 10:27:59），已 install -r 到 emulator-5556
```

## 21 条件成员：用户在模块里点档位，成员由模块自己生成（2026-11-12）

§20 之后，聚合的成员还是「宿主里已经存在的策略」。用户要的组合是「流通市值 50-500 亿 × 量比 1.5-4」这类**交集**，宿主没有对应策略卡，就得先去宿主里手建十几条策略、每条都受 10 条上限约束，再回模块里一条条挑 —— 用户原话是「你这样增加成员和让用户猜有什么区别」。

这一节把方向反过来：**用户只选档位条件，成员由模块自己拼、自己取数**。宿主里一条策略都不用建。

### 21.1 成员怎么表示

`MergeMember` 的格式没动，仍然只有 `stId` 和 `name` 两个字段，条件成员写成：

```text
cond:50-70亿,70-100亿,100-500亿|1.5-2.0,2.0-2.5,2.5-3.0,3.0-4.0
```

- 前缀 `cond:` 之后是 `市值档列表|量比档列表`，档位名与宿主 chip 上的一字不差（`流通市值50-70亿` 去掉前缀后的「50-70亿」）。
- `stId` 必须为空。填了 stId 说明用户真在宿主里建了同名策略，那时按普通成员走 —— 与口径成员 `bucketOfMember` 同一条判据。
- 展开条数 = 市值档数 × 量比档数，即 `StrategyComboCatalog.Condition.comboCount`。
- 编码只加不删：`MergeMember.name` 本来是自由文本，旧配置（真实策略名、口径名 `中盘 1.5-4`）照旧解析，不需要迁移。

### 21.2 取数怎么走

条件的每一格就是一个「市值档 + 量比档」组合，取数与口径成员**同一条路**：`StrategyComboClient.create` 现建一条临时策略（两个 labelId）→ `query` 拿成分股 → `delete` 删掉。宿主账号只有 10 个策略名额，装不下 12 个组合，所以只能一条一删地串行跑。

真机 12 个组合实测 **121-125 ms/格**，整组约 1.5 秒；临时策略建完即删，宿主自己的策略列表不受影响。

### 21.3 纯条件组的入口卡：借一张

聚合组的入口靠宿主「我的策略」列表里的一张卡：把它改名成「聚合 · 组名（N 条）」，用户点进去才进得了聚合详情页。以前挑卡的路只有一条 —— **组里的成员卡**。条件成员不是宿主策略，页面上没有它的卡，于是纯条件组整组被 `continue` 跳过：模块里配得好好的 12 条，宿主列表里根本不出现，哪都点不进去（真机原样复现）。

改法：`StrategyMergeFeature` 列表装配在「成员卡」这条路走空之后，**借一张原生策略卡**当入口。借款条件四条，缺一条都会打架：

| 条件 | 为什么 |
| --- | --- |
| 名字不为空、`stId` 不为空 | 没有 stId 的卡点不进详情页 |
| 名字反解不出组名（`entryLabelGroupNames` 为空） | 别组已经拿去当入口的卡不能再借 |
| 没进本轮的 `claimed` | 同一轮里先到的组先得，后到的组换一张 |
| 不是**任何**启用组的成员卡 | 别组第③步会把自己的成员卡从列表里摘掉，借来的入口卡跟着消失，两个组同时没入口 |

借来的卡只改内存里的名字，**不往服务器写**：每轮列表刷新，宿主自己拉回来的数据里那张卡仍叫「量100」，日志每轮都重复打一次「借「量100」当入口」就是证据。所以停用或删掉这一组，下一轮刷新卡名自然回到原名。

归组靠名字反解（`hits` 的第③路），不看这张卡原本是谁，所以借哪张都一样。

### 21.4 界面

三级页「添加成员」面板第一项就是 **按档位选条件**，走四步单选向导：

```text
第 1 步 / 4 · 流通市值从哪一档起    → 10亿以下 … 500亿及以上（7 档）
第 2 步 / 4 · 到哪一档止            → 同上（顺序反了自动对齐）
第 3 步 / 4 · 量比从哪一档起        → 0.5及以下 … 4.0以上（8 档）
第 4 步 / 4 · 到哪一档止            → 同上
```

落盘成一条 `cond:` 成员，行标题显示 `流通市值50-500亿 · 量比1.5-4`，副标题 `… · 12 条组合`。四步向导用的是现成的 `ChoiceDialogSpec`，只给它加了一个可选的 `header`（分步提示），没有新造控件。

### 21.5 真机验收

```text
模块里新建「聚合组1」→ 按档位选条件 50-70亿 … 100-500亿 × 1.5-2.0 … 3.0-4.0
  三级页摘要：12 个成员 · 生效
宿主「我的策略」：第三张卡变成「聚合组1（12 条）」，chips = 流通市值50-500亿 / 量比1.5-4
  日志：组「聚合组1」在页面上没有成员卡，借「量100」当入口
点进去：12 个成员逐个到位（121-125 ms/格），并集 659 只，涨 593 / 跌 157
  日志：已渲染 659 只（成员 12 个，涨 593 / 跌 157）
筛选中：计数栏 376/659（沿用 §19 的筛后/总数口径）
```

### 21.6 已知限制

- **页面上的卡不够借**：宿主账号的策略条数就是入口卡的上限（现在 5 条）。已启用的组比策略卡多时，多出来的组拿不到入口，日志打「借不到策略卡（页面 N 张）」，宿主里也就看不到它。要更多入口，只能在宿主里多建几条策略当卡位。
- **涨跌家数仍是各成员累加**：593 + 157 = 750 大于并集 659，同一只股票被两个档位同时选中时会重复计入。宿主只给每份响应一个总数、拿不到股票级的涨跌标记，这个口径自 §18 起未变，不影响列表正确性。
- **区间标签在列表卡上仍是灰色 + ⓘ**：见 §20.5 第一条，未变。

### 21.7 门禁

```text
gradlew :app:testDebugUnitTest :app:assembleDebug → BUILD SUCCESSFUL
  25 个测试 XML 全 0 失败（StrategyComboCatalogTest tests=13 failures=0）
app-debug.apk → 12,281,602 字节（2026-11-12 10:56:52），已 install -r 到 emulator-5556
```



## 22 从金太阳导入：宿主主动广播清单，模块只认「页面上真实存在的策略」（2026-11-12）

> 一句话：模块 App 与宿主是两个 UID，读不到彼此的私有数据；清单只能由宿主在「我的策略」页采集后广播过来，
> 而且**只发这一页真实存在的条目**，不能发索引全量 —— 索引只增不删，早前用接口批量建过又没了的策略会一直躺在里面。

### 22.1 为什么必须走广播

| 方案 | 结论 | 原因 |
| --- | --- | --- |
| 读宿主私有 prefs | 不行 | `/data/data/com.guosen.android/shared_prefs/gsaux_stg_index.xml` 跨 UID 读不到 |
| 目标进程写 remote preferences | 不行 | `XposedInterface.getRemotePreferences` 返回只读 SharedPreferences（见 `core/ConfigSource.kt` 的说明） |
| 共享文件 + `MANAGE_EXTERNAL_STORAGE` | 不选 | 为一个展示清单要一个高危权限 |
| 模块 App 主动拉一次 | 未做 | 需要宿主动态注册接收器并常驻，代价大于收益 |
| **宿主 `sendBroadcast` + 模块静态接收器** | **采用** | 不需要任何权限；模块侧收到就落自己的 prefs，页面每 2 秒重读 |

实现落在三个文件：

- `core/StgCatalogBroadcast.kt`：action / 包名 / extra 键 / 签名常量 / 载荷版本 `VERSION = 1`
- `feature/stgmerge/StrategyStgCatalog.kt`：宿主侧组包 + 两道节流（最短 3 秒一次；内容没变最多 30 秒补发一次）
- `app/HostStgCatalog.kt` + `app/HostStgCatalogReceiver.kt`：模块侧解析、落 `gsaux_host_catalog` prefs

载荷：

```json
{"v":1,"at":1794457185711,"items":[{"id":"1630342554861965312","name":"量500","tags":["流通市值500亿及以上","量比2.5-3.0"]}]}
```

`at` 是宿主组包时间（毫秒），模块页首行显示成 `上次同步 12:20 · 共 8 条`，用户能看出清单新不新。
`v` 不等于当前版本整份丢弃；`id` 或 `name` 缺一半的条目直接丢（写进成员配置也用不了）。

### 22.2 只发「页面实测条目」+ 组内成员

第一版拿 `StrategyStgIndex.entries()` 当数据源，真机上「从金太阳导入」冒出了 `T4`、`TPMP50` 两条 —— 宿主「我的策略」
里根本没有它们。根因是索引**只 remember 不淘汰**：

| 环节 | 行为 | 后果 |
| --- | --- | --- |
| 列表页每轮巡检 | `StrategyStgIndex.remember(名称, stId)` 只写不删 | 删掉的策略留在索引里 |
| 早前用 OpenAPI 批量建的策略 | 同样进过索引 | `T4`（量比4.0以上）、`TPMP50`（流通市值50-70亿）永远在 |
| `entries()` | 把整份历史原样给出 | 全部导进清单 |

现在的口径：**广播的清单 = 这一轮列表页适配器里真实存在的条目 ∪ 启用组的成员**。

- 页面条目在 `StrategyMergeFeature.decorateListNow` 的采集循环里现算（名称 / stId / 标签本来就是同一趟拿的）；
- 启用组的成员卡会被 ③ 从 adapter 数据里摘掉（避免同一批股票重复出现），下一轮就看不见了 —— 它们是**真策略**，
  按配置补回来（`withGroupMembers`），否则把「量500」「量70」加进组后，导入清单会从 8 条掉到 6 条；
- 条件 / 组合 / 标签成员（`cond:` / `combo:` / `label:` 前缀）不是宿主策略，跳过；
- 我们自己改名的入口卡（`聚合 · X（N 条）`）继续用 `looksLikeEntryLabel` 拦掉。

索引本身仍然保留全量：成员只填名称时，靠它把名字解析回 stId。

### 22.3 界面：区块顺序与点击行为

编辑页自上而下：`聚合组`（名称 + 启用开关）→ `成员策略`（成员行 + 「添加成员」）→ `从金太阳导入` → `从聚合组导入`。

- 首行是清单头：`策略清单` / `上次同步 12:20 · 共 8 条`，只展示、不可点；
- 每条策略一行：标题是策略名，副标题依次取 `已在当前组里` / 成员上限提示 / `无标签` / 宿主标签拼接；
- 点一行 = 直接加成成员（`MergeMember(stId, name)`），去重同时看 stId 与名称，改过名再加一次也不会重复；
- 还没收到广播时显示 `还没有同步到金太阳的策略` + `打开一次金太阳的「我的策略」列表，再回到这里`。

### 22.4 「0 个成员」但下面明明有一行

症状：刚点进来的父标签成员（例如 `30分钟MACD`，还没选档位）在成员区占一行，组摘要却写 `0 个成员`。
根因：摘要用 `expandedCount`（口径 / 标签成员展开后的条数），待选档位的父标签展不出条数、算 0。

改法（`ui/StrategyMergeScreen.kt`）：

```kotlin
val expanded = StrategyComboCatalog.expandedCount(group.members)
return if (expanded > group.members.size) expanded else group.members.size   // 显示数
// 生效门槛仍然只认 expanded：待选档位的成员取不出股票，不能让它把组撑成「生效」
```

### 22.5 与 §21.4 的关系

§21.4 的四步向导（市值起 / 市值止 / 量比起 / 量比止）已经撤掉：现在「添加成员」直接进宿主标签全表，
点一级标签即成成员、点成员行再细分档位。`cond:` 成员的数据结构不变，历史配置照旧可用。

### 22.6 真机验收（emulator-5556 / com.guosen.android / 720x1280）

```text
宿主「我的策略」日志：策略清单：已广播宿主策略清单 8 条
模块接收日志：宿主策略清单：已收到 8 条
模块编辑页：策略清单 / 上次同步 12:20 · 共 8 条
  量500 流通市值500亿及以上 · 量比2.5-3.0
  量70  流通市值70-100亿 · 量比2.5-3.0
  500e / 量100 / 50e / 100e / 70e / 量50 …
点「量500」「量70」→ 组摘要 2 个成员 · 生效，两行副标题变「已在当前组里」
模块 prefs（run-as 读 gsaux_host_catalog.xml）：items 正好 8 条，无 T4 / TPMP50
```

`T4` / `TPMP50` 仍留在索引里（成员按名称解析还要用），只是不再进广播。

### 22.7 门禁

```text
gradlew :app:testDebugUnitTest :app:assembleDebug → BUILD SUCCESSFUL
  28 个测试 XML / 288 tests / 0 failures
  HostStgCatalogTest tests=8（载荷解析 / 版本不符 / 缺字段 / 空白裁剪）
  StrategyStgCatalogTest tests=4（入口卡名拦截）
app-debug.apk → 12,296,740 字节，已 install -r 到 emulator-5556
```

## 23 标签成员也算标签、入口卡原名要补回清单（2026-11-12）

> 现象：聚合组 2 的成员是两条**策略标签**（量比1.5-2.0 + 流通市值50-70亿），可卡片上挂的是借来的那张
> 原生策略的标签「流通市值500亿及以上 / 创新高20交易日」；详情页标签条同样是错的。

### 23.1 两条链路各丢一次

| 位置 | 漏在哪 | 表现 |
| --- | --- | --- |
| `StrategyMergeFeature.groupTagNames`（列表卡 chips） | 只反推了口径成员（`bucketOfMember`）和条件成员（`conditionOfMember`），没有 `label:` 分支 | 标签成员 `sid` 为空、`idOf(name)` 找不到卡 → `continue`，pool 为空 → `mergeTagNames` 为空 → `writeCardTags(empty)` 直接返回 false → 卡片保留借来那张的原生标签 |
| 详情页标签汇总 | 只判 `sid.startsWith(COMBO_PREFIX)`，而标签成员入队时 key 已被包成 `combo:label:<id>` | `labelIdOf` 只认裸 `label:` → 详情页标签也丢 |

### 23.2 改法

1. `StrategyComboCatalog.labelIdOf` 先剥一层 `COMBO_PREFIX` 再判 `LABEL_PREFIX`，于是 `displayName` /
   `comboOfKey` / `tagNamesOfKey` 全部顺带支持队列 key（`combo:label:136392…`）。
2. `groupTagNames` 里在条件成员之后补一条 `labelIdOf(m.name)?.let { pool.addAll(tagNamesOfKey(m.name)) }`。
   档位表（`StrategyLabelTable` 的 1119 条）本来就带 `labelId → labelName`，标签成员的 chips 直接反查即得。
3. 广播清单补两类在页面上读不到的条目（见 23.3）。

### 23.3 改名会把自己的身份改没

入口卡被改写成「聚合 · X（N 条）」以后，列表页再怎么扫也扫不出它是哪条策略；成员卡又被 ③ 段从
adapter 里摘掉。于是「从金太阳导入」的清单在组启用期间会莫名少几条（真机：组一启用，`500e` 消失）。

发送点因此从 ①.b 挪到 ③ 之后，三份来源合并后再广播：

| 来源 | 覆盖 |
| --- | --- |
| `live` | 这一轮适配器里真实存在的条目 |
| `withGroupMembers(merge, …)` | 被收起 / 被借走的成员卡，按配置里的 stId 或名称反查补回 |
| `entryOriginals`（组名 → 入口卡原身份） | 改名之前记下的那张卡，含原生标签；只在 `done == null`（首次改名）时记录一次 |

### 23.4 真机验收（emulator-5556 / com.guosen.android / Android 12）

- 宿主列表卡：`聚合组2（2 条）` chips = 「量比1.5-2」「流通市值50-70亿」（修复前是 500e 的原生两条）。
- 详情页：策略名 `聚合组2（2 条）`，标签条同样两条，列表 207 只、涨 213 / 跌 1。
- 模块 prefs `shared_prefs/gsaux_host_catalog.xml`：`at=12:28:40`，`count=8`，
  内容为 量100 / 50e / 100e / 70e / 量50 / 量500 / 500e / 量70 —— 成员（量500、量70）与入口卡原名（500e）都回来了，
  历史策略 T4 / TPMP50 不再出现。

### 23.5 已知残留

- 列表卡上**新造／改写**出来的 chip 仍是灰色带 ⓘ（详情页正常）：宿主按 tagId 校验，我们复制的是同前缀原元素的 tagId，
  与改写后的 `labelName` 对不上。不影响筛选与并集，只是样式。
- 组成员卡被收起期间，它在列表页上不可见（点不进去），只能从聚合入口卡进；组停用即恢复。

### 23.6 门禁

- `gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL，28 个 XML / 291 tests / 0 failures（新增 3 个标签成员用例）。
- APK `app/build/outputs/apk/debug/app-debug.apk` 12,296,741 B。

## 24 单槽轮转：一条临时策略复用整轮（2026-11-12 13:35）

### 24.1 为什么不再「每格建一条」

账号级的策略数上限是 **10 条**（服务器返回 `code 4100 策略数不能超过10个`）。用户已经有 8 条真实策略，
24 个口径成员一起 create 会直接撞上限 —— 真机表现是「临时策略一条都建不出来 → 成员全部取不到数 → 聚合 0 只」。
所以取数改成**一条临时策略整轮复用**：建一次，之后每个成员只是把它身上的标签改成这一格要的条件。

### 24.2 靠 `updateStrategy` 换条件，服务器按标签现算

| 接口 | 业务参数 | 结论 |
| --- | --- | --- |
| `POST /gsnews/strategy/updateStrategy/1.0` | `stId`、`labelId`（逗号分隔）、`stName`（可选） | 改完**立即**生效，~60 ms |
| `POST /gsnews/strategy/queryStrategyQuoteDetail/1.0` | `stId` | 结果由**当前标签**决定，不按 stId 缓存 |
| `POST /gsnews/strategy/queryStrategy/1.0` | `pageNo`、`pageSize` | 策略清单（所有策略塞在 `data[0].member` 这个字符串化数组里） |

同一个临时策略连查 24 次，各档只数分别是 100 / 56 / 45 / 25 / 17 / 12 / 8 / 4 / 2 / 0，**证明不是缓存的同一份**。

### 24.3 实现（`feature/stgmerge/StrategyComboClient.kt`）

| 成员 | 作用 |
| --- | --- |
| `const val TEMP_PREFIX = "GSZtmp"` | 临时策略名前缀，收尾靠它认领自己的垃圾 |
| `createOrFreeSlot(name, labelIds)` | 撞 4100 时先 `purgeLeftovers()` 清自己漏删的，清到了再重试一次 |
| `fun update(stId, labelIds)` | 只改标签 |
| `fun purgeLeftovers(): Int` | 名字以 `TEMP_PREFIX` 开头（或早期 `Z` + 8 位数字）就删 |
| `class TempSlot { ensure / apply / release }` | `apply` 里标签没变就不发请求；`release` 删掉并清空记账 |

`MergeRun` 侧：`openCombo` 走 `comboExecutor` 拿到 id 后交给 `takeMember`；`tempIds` 里的成员**不再重试**
（临时策略整轮复用，这一格失败就是服务器没给数）；`cleanupTemps` 统一 `comboSlot.release()`；
借入口卡的判据补了一条：名字以 `TEMP_PREFIX` 开头的卡不许借（它收尾就删，借了入口卡会消失）。

### 24.4 真机时序（emulator-5556 / 聚合组2 / 24 个口径成员）

- 24 格全部复用同一个临时策略 `1638327634200891392`，每格 121~140 ms，整轮约 **3.4 秒**（旧实现是 24 次建删）。
- 累计并集 756~764 只（盘中随时段变化），收尾日志 `收尾清掉 1 条临时策略`。

### 24.5 两个数据坑（写死在客户端里）

- **请求头必须带 charset**：`Content-Type: application/x-www-form-urlencoded; charset=UTF-8`。不带时中文策略名解码错乱 ——
  实测把「量50」改成了「é50」，发现后立刻改回。
- **探测接口别拿真实策略 id 当参数**：一次 `updateStrategy` 试跑把用户的「量50」标签改成了 `流通市值50-70亿 + 量比4.0以上`，
  已用同一接口复原（标签 `332921024326664192,1363925245672554496`，名字「量50」）。

## 25 主线程死锁：两个功能互相还原适配器（2026-11-12）

### 25.1 现象

24 个成员取到最后一个之后，流水线**静默停住**：不再有「到位」日志、界面停在半截；21 秒后宿主 ANR
（`ANR in com.guosen.android (…AIStkStgDetailAct) Reason: Input dispatching timed out`）。
本轮新加的主线程看门狗把它逮了个正着：

```
主线程已 3006ms 没动（聚合 stId=1630342554861965312 成员 24 个）栈：
    at io.github.MVennn.GSAuxiliary.feature.stgfilter.StgStockFilterFeature.applyFilter(StgStockFilterFeature.kt:807)
    at io.github.MVennn.GSAuxiliary.feature.stgfilter.StgStockFilterFeature.onAdapterSet(StgStockFilterFeature.kt:555)
    at io.github.MVennn.GSAuxiliary.feature.stgfilter.StgStockFilterFeature.hookListViewSetAdapter$lambda$0(StgStockFilterFeature.kt:529)
    at android.hardware.BriernieHeamoompZeelash.setAdapter(BriernieHeamoompZeelash.java)
    at io.github.MVennn.GSAuxiliary.feature.stgfilter.StgStockFilterFeature.applyFilter(StgStockFilterFeature.kt:807)
    …（同四帧无限重复）
```

### 25.2 根因

`StgStockFilterFeature.kt:807` 的还原那句 `runCatching { lv.adapter = base }` 会**同步**回到自己的
`ListView.setAdapter` 后置钩子（`:529` → `onAdapterSet` `:555` → `applyFilter`），而 `applyFilter` 里
判断「列表上还挂着过滤代理」的记账（`st.proxy` / `proxiedLists`）原来写在赋值**之后** ——
每一层递归看到的都还是「挂着代理」，于是再还原一次，无限套下去，主线程永远回不来。

`stgmerge` 这侧也有份：`ListView.setAdapter` 后置钩子里会「重套并集 / 起一轮取数」，这两件事都可能再次
`lv.adapter = …`，和上面对上就成了一条无底链。

### 25.3 改法：两处重入闸 + 还原顺序

| 位置 | 改动 |
| --- | --- |
| `feature/stgfilter/StgStockFilterFeature.kt:463` | 新增 `private val filtering = ThreadLocal<Boolean>()`；`applyFilter` 变成薄壳（同线程第二次进直接返回），本体改名 `applyFilter0` |
| 同文件「还原全量」段 | 先清记账（`st.proxy` / `st.snap` / `proxiedLists` / `StgFilterBridge.publish(null)`）**再**赋值 `lv.adapter = base`，嵌套层不会再把列表判成「挂着代理」 |
| `feature/stgmerge/StrategyMergeFeature.kt:299` | 新增 `private val setAdapterBusy = ThreadLocal<Boolean>()`；setAdapter 后置钩子只放**第一层**进来，嵌套层原样 `return result` |

两道闸都要：stgfilter 那侧断的是**同一条调用链里的自我递归**，stgmerge 那侧断的是**两个功能互相触发**
（还原 → 我们的钩子重套 → 再还原）。只拦一侧，另一侧仍能把主线程拖住。

### 25.4 真机验收

| 项目 | 修复前 | 修复后 |
| --- | --- | --- |
| 24 格跑完 | 静默 21 s，宿主 ANR（13:25 / 13:38 两次） | `收尾清掉 1 条临时策略` → `已渲染 756 只（成员 24 个，涨 382 / 跌 385）` |
| 看门狗 `没动（` 条数 | 6 条/轮，逐轮复现 | **0 条** |
| 宿主 `ANR in com.guosen.android` | 每轮一次 | **0 条** |
| 详情页计数 | 停在半截 / 0 只 | `策略个股756只(11-12 13:44:04)` |

筛选联动没被这次改动破坏（§19 的路径照旧）：

- 勾「涨家股票」+ 确定 → `策略个股 230/756(11-12 13:44:04)`，日志 `筛选股票：全量 756 → 保留 230（涨 230 / …）`；
- 点「重置」+ 确定 → 回到 `策略个股756只`，日志 `筛选股票：筛选未生效，已还原全量`。

### 25.5 顺带清掉的测试残留

宿主 prefs `shared_prefs/gsaux_stg_filter.xml` 里留着我在 §19 验收时勾的 `filter_up=true`（还有一份 `filter_up@grp:group2`），
它会让聚合页一直显示 `N/总数`。已把该文件删掉（备份在设备 `/sdcard/gsf_backup.xml`），重启宿主后恢复 `策略个股756只`。

### 25.6 门禁

- `gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL；28 个 XML / 293 tests / 0 failures（`StrategyComboCatalogTest` 24 个）。
- `python tools/ci/check_links.py` → exit 0（67 篇 Markdown / 356 条链接 / 0 失效 / 0 孤儿）；`python tools/hygiene.py` → exit 0。
- APK `app/build/outputs/apk/debug/app-debug.apk` 12,324,378 B（2026-11-12 13:42:27）。
## 26 一步到位：进页面就是完整并集，家数跟着并集走（2026-11-12）

目标（用户原话）：「这个刷新可以并行吗……可以做到一步到位吗，就是我打开就是聚合股票」。
上一轮已经能秒显并集，但屏幕会先闪宿主那一格，且收尾后头部涨跌家数变成 0/0。本轮把这两处一起修掉。

### 26.1 三个根因

| # | 现象 | 根因 |
| --- | --- | --- |
| 1 | 温进页面时并集闪回宿主单成员帧（1 只 / 100 只） | `mergedRef` 是弱引用，取数窗口里被 GC 收走 → `holdUnionWhileRunning` 直接 return，退回「逐格长大」 |
| 2 | 顶住并集后屏幕上仍只有一行 | 从首页进来的列表挂的是 stgfilter 的 `FilteredAdapter`，它在 `notifyDataSetChanged` **之前**就用当时只有 1 行的 base 算好了 `indices: IntArray`；我们写 data 晚了一步，代理照旧只显示 1 行 |
| 3 | 收尾后 `已渲染 N 只（涨 0 / 跌 0）` | 取数窗口每帧都把自己的聚合家数写回 `model` 的 upNum/downNum，`poll` 里 `addStat` 现读这两个字段，读到的是自己写的数，被去重闸整段跳过，宿主真值一次都没累加 |

### 26.2 改法一览（全部在 feature/stgmerge/StrategyMergeFeature.kt）

| 位置 | 改法 |
| --- | --- |
| `PageState.mergedStrong` | 新增强引用副本，`applySnapshot` / `finish` 赋值，`captureOrigin` / `clearState` 清空；所有读 `mergedRef` 的地方换读它 |
| `holdUnionWhileRunning` | 判宿主这一帧 `size >= 并集` 早退 → 写 `data`（失败打日志并 return）→ `holdHeaderCounts()` → `writeHeaderCounts` → `notifyAdapter`（让代理按新全量重算下标）→ **最后** `writeMergedResNum` |
| `captureHostPayload` | 抄帧的同一时刻调 `state.run.captureHostStats()`，把宿主这一帧的 upNum/downNum 存进 `hostUp/hostDown` |
| `addStat` | 累加源改成 `hostUp/hostDown`（不再现读 `model`）；保留 `u == wroteUp && d == wroteDown` 这条闸，用于识别「宿主这一帧没重写家数」 |

顺序不能换的两条：**抄帧抄家数必须在写回之前**（晚一步抄到的就是自己的聚合值）；**计数必须写在 notifyAdapter 之后**（`策略个股 X/总数` 的 X 是从代理现算的可见行数读的，写在 notify 之前读到的是上一格）。

### 26.3 真机验收（emulator-5556 / com.guosen.android / 聚合组2 24 成员）

| 时刻 | 计数栏 | 头部 | 列表 |
| --- | --- | --- | --- |
| 温进 ~1 s | `策略个股 284/624(11-12 15:00:32)` | 291 / 333（沿用上一轮聚合值） | 完整并集，按涨幅排序（蓝色光标 +10.97% / 澳洋健康 +10.08% / 龙江交通 +10.02% …） |
| ~4 s | 284/634 | 291 / 333 | 同上，不缩不闪 |
| ~9 s（收尾后） | 284/634 | **291 / 333**（上一版这里是 0 / 0） | 同上 |

日志：`先用上次的 624 只（缓存 18467ms 前）` → `取数期间顶住上一轮并集 624 只（宿主这一帧 1 只，不缩）` → 24 个成员逐个 ~120 ms → `已渲染 634 只（成员 24 个，涨 291 / 跌 333）`。

### 26.4 已知残留

- `策略个股` 的分子（284）与头部涨家数（291）差 7 只：分子是筛选代理按自己的谓词数出来的可见行，头部是宿主按服务端字段数的。两者口径本来就不同（缺少行情快照的票在代理里被排除），不是本轮回归。
- 头部「日涨跌幅」恒为 `--`：这一格由宿主自己按成员策略算，模块里没有对应字段可写。

### 26.5 门禁

- `gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL（EXIT=0），29 个 XML / 308 tests / 0 failures。
- `python tools/ci/check_links.py` → exit 0；`python tools/hygiene.py` → exit 0。
- APK `app/build/outputs/apk/debug/app-debug.apk` 已 install -r 装机复验。

## 27 入口卡不再带条数 + 全选/反选提速 4 倍（2026-11-12）

两件事一批做的：用户说「我们聚合的那个策略 后面的那个（xx条）可以去掉了吧」，顺手把详情页批量勾选的顿感收掉。

### 27.1 去掉「（N 条）」牵动的四张网

卡名以前是 `聚合 · 组名（N 条）`，条数尾巴同时是**识别标记**：详情页靠它认组、克隆借卡靠它排除、清单广播靠它拦下入口卡。去掉尾巴后，尾巴和「聚合 · 」前缀都不在了，四处判据都得补一条「与组名精确相等」的路。

| 位置 | 改法 | 不改的症状 |
| --- | --- | --- |
| 详情页认组 ③（`StrategyMergeFeature.kt` 约 :1628） | 判据从 `labelNames.contains(gn)` 扩成 `labelNames.contains(gn) || pageName == gn || pageName == entryLabelOf(gn, g.members.size)` | 组名自己就以「聚合」开头时（如「聚合组2」），卡名就是裸组名、不带前缀，`entryLabelGroupNames` 认不出 → 点入口卡不聚合，日志 `跳过：没有包含当前策略的已启用聚合组` |
| 克隆借卡候选（约 :1230） | 排除条件加 `n in entryLabels` | 把别组的入口卡克隆一份当自己的入口 |
| `entryOriginals` 记录条件（约 :1296） | 加 `&& origName !in entryLabels` | 入口卡被当成真实策略记进原卡表 |
| 清单广播（`StrategyStgCatalog.kt`） | `publish(ctx, entries, entryLabels)` 新增可选集合，`build` 里 `looksLikeEntryLabel(e.name) || e.name.trim() in entryLabels` 跳过 | 「从金太阳导入」里多出一条名叫「聚合组2」的假策略，底下是某个成员的 stId |

`entryLabelOf(groupName, memberCount)` 保留两参签名，实现直接转 `entryLabelBare(groupName)`，`@Suppress("UNUSED_PARAMETER")` 标注原因；调用点一处没改。

### 27.2 全选/反选：一次批量只扫一遍行对象

根因是 `syncHostModel` 里每点一行就重做一遍**全表**校正（`O(N)` 读 adapter 每行的代码 / 原始代码 / 价格 / 连板数），285 行就是 285 × O(285)。

改法：clickAll 期间建一张 `RowCodes(adapter, total)` 缓存表，四个字段各按 total 开数组；`cachedRowCode` / `rowRawCodeAt` / `rowPriceAt` / `rowTwinAt` 先查表，非批量态原样回退老实现（不改变非批量路径的任何行为）。缓存值仍走 `StgSelectAllPlan.isCodeLike` 校验并 `noteRowCodeMiss`，读不到的行照样能被容错计数抓到。

### 27.3 实测（同一 285 行筛选态、并集 630，emulator-5556）

| 指标 | 优化前 | 优化后 |
| --- | --- | --- |
| 一次全选总耗时 | 1763 ms | **429 ms** |
| 其中「校正模型」 | 1234 ms | **22 ms** |
| 反选总耗时 | 同量级 | **346 ms**（校正模型 10 ms） |
| gfxinfo 99th | 1750 ms | **300 ms** |
| Janky frames | 0% | 0%（legacy 5.36%） |

日志（本轮终版）：`一次性点击 285 行完成（耗时 429ms，失败 0，宿主计数 0 → 285） [分段：造View 1ms/276 行、宿主点击 33ms、校正模型 22ms]` → `反选 … 346ms … 宿主计数 285 → 0`。

### 27.4 门禁

- `gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL（EXIT=0），29 个 XML / 308 tests / 0 failures。
- `python tools/ci/check_links.py` → exit 0；`python tools/hygiene.py` → exit 0。
- APK 已 install -r 装机：入口卡名「聚合组2」、点进去聚合出 285/630（涨 285 / 跌 329）。
