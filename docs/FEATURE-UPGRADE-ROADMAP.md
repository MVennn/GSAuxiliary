# 功能升级路线图 —— 22 个功能的对象族反推

> **一句话**：把「一个开关只做一件事」的功能，反推成「同一套规则作用在 N 个同类对象上」；本文给出四条判据、22 个功能的逐个判定、四个值得做的族的具体形态，以及其余十八个为什么不动。

本文是**判定与形态**的正本。具体到某个族的完整交互与重排公式，见该族自己的设计稿（现在只有底栏一族有：[FEATURE-DOCK-ENTRIES](FEATURE-DOCK-ENTRIES.md)）。

---

## 一、判据

### 1.1 四条判据

一个功能要升级成「对象族 + 二级设置页」，四条必须全中（任一条不中就不升级，理由写在 §6）：

| 判据 | 内容 | 反例长什么样 |
|---|---|---|
| **P1 对象族 ≥3** | 被操作的对象有三个以上，且是**同类同权**的平级对象（六个底栏格、七条菜单项、三个页签） | 一条横幅的三页轮播、同一弹窗的四种识别通道、一颗按钮的两种走法 |
| **P2 操作一致** | 对每个对象做的是同一件事（藏 / 提到外面 / 配一个颜色），实现上是同一条代码路径 | 「卡片本体跳详情」和「隐藏编辑图标」两种操作混在一页 |
| **P3 状态可序列化** | 用户的选择能落成一份清单或映射，且**不随对象数量变化** | 「压缩到 60%」这种连续比例、「保留几行」这种由布局反推的像素比 |
| **P4 宿主机制不随数量变** | 目标 App 那边的注入 / 重排机制，加一个对象只是多跑一遍，不改公式 | 只能在固定位置插一颗按钮、加第二个就要重写约束链 |

### 1.2 三条硬约束（决定形态，不是判据）

| 约束 | 证据 | 影响 |
|---|---|---|
| **布尔位只剩 4 个** | `core/ConfigSnapshot.kt:101-104`：28 个布尔项已占位 0..27，再加一个 `bit shl 1` 会在第 32 位溢出成 0，两个开关静默互相干扰 | 任何「每个对象一个布尔键」的写法直接否决；新维度一律走 **String 型 JSON 参数键**，进 `FEATURE_PARAM_KEYS` |
| **一个功能最多两个二级入口** | `ui/FeatureCatalog.kt` 的 `FeatureSpec` 只有 `detailEntryRes` / `detailEntry2Res` 两个字段（渲染见 `ui/FeatureTab.kt:102-115`） | 一个族要第三个入口，就得先给 spec 加字段；连带 `ui/Screens.kt` 的 `#2` 后缀约定要扩到 `#3` |
| **功能页只渲染两种行** | `ui/FeatureTab.kt:84-118`：开关行 `GsSwitchPreference` + 入口行 `BasicComponent`（带 ›）。选择行（当前值 + ↓ + 弹层）只存在于设置页 | 想在功能页放「选一个值」，得先加第三种行型；这会动到 22 个功能共用的渲染器，不是单个功能的事 |

### 1.3 三种族型，只有第一种算升级

| 族型 | 形态 | 例子 | 算不算升级 |
|---|---|---|---|
| **对象族** | 同一操作 × N 个同类对象 | 底栏六格、详情页弹层七项 | 算。这才是「反推出新功能」 |
| **动作族** | 一条动作拆成准入 / 确认 / 去向三个环节 | 盯盘保存的三件 | 不算，最多归并成一页（省点击，不多维度） |
| **档位族** | 一个连续量切成两三档 | 板块单行 / 双行、概览 0.6 / 0.8 | 不算，且大多数档位只有两档，做出来是一行选择行 |

---

## 二、全量判定

判据列为 P1/P2/P3/P4 是否命中；「族型」列写对象族 / 动作族 / 档位族 / 已是族。

| # | id | 现用标题 | 对象数 | P1 | P2 | P3 | P4 | 判定 | 建议 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `stock_bar_entry` | 增加 智能盯盘 入口 | 7（个股页弹层） | ✓ | ✓ | ✓ | ✓ | **A 够格** | 已升级成「详情页底栏入口」，容量 2（落地见 §3.1） |
| 2 | `dock_clean` | 自定义 底栏 入口 | 6（底栏格） | ✓ | ✓ | ✓ | ✓ | **A 够格** | **已落地 2026-11-14**，见 [FEATURE-DOCK-ENTRIES](FEATURE-DOCK-ENTRIES.md) |
| 3 | `watchlist_highlight` | 自选股高亮 | 3（涨/跌/平） | ✓ | ✓ | ✓ | ✓ | **A 够格** | **已落地 2026-11-14**，三档配色 + 入口状态句 |
| 4 | `zx_group_copy` | 自选分组复制 | 3（页签）+ 规则表 | ✓ | ✓ | ✓ | ✓ | **A 够格 · 已落地 2026-11-14** | 规则绑分组，只多一维 |
| 5 | `stg_stock_filter` | 增加 筛选股票 菜单项 | 策略 ≤200 + 聚合组 | ✓ | ✓ | ✓ | ✓ | **B 够格·阻塞** | 筛选值在目标进程，回传通道要先补 |
| 6 | `market_index_hide` | 隐藏 指数概览 | 4（指数项） | ✓ | ✓ | ✓ | ✓ | **B 够格·低价值** | 只能按位置序号做 key，id 未二次核实 |
| 7 | `watchlist_bold` | 自选股名称加粗 | 4（行内列） | ✓ | ✓ | ✓ | ✓ | **B 够格·暂缓** | 需求集中在名称列，四列收益不均 |
| 8 | `stg_merge` | 我的策略聚合 | 组（可增删） | — | — | — | — | 已是族 | 列表式母版，不改 |
| 9 | `aistare_quick_config` | 增加 配置 按钮 | 方案（可增删） | — | — | — | — | 已是族 | 列表式母版，不改 |
| 10 | `ipo_dialog_clean` | 隐藏 新股申购 弹窗 | 1 | ✗ | ✓ | ✓ | ✓ | 不够格 | 四个数组是同一弹窗的四条识别通道 |
| 11 | `stg_direct_quote` | 直达标准行情 | 2 态 | ✗ | ✗ | ✓ | ✓ | 不够格 | 第三档「不跳」= 关掉功能 |
| 12 | `stg_select_all` | 增加 全选 按钮 | 1 | ✗ | ✗ | ✓ | ✓ | 不够格 | 宿主多选栏只有一颗锚点按钮 |
| 13 | `stg_snap_add` | 增加 截图并添加 按钮 | 1 | ✗ | ✗ | ✓ | ✓ | 不够格 | 同上；可做的是按钮文案项 |
| 14 | `aistare_empty_save` | 优化确定逻辑 | 2 | ✗ | ✓ | ✓ | ✓ | 不够格 | 动作族第 1 环（准入） |
| 15 | `aistare_skip_save_confirm` | 无需确定保存盯盘 | 1 | ✗ | ✓ | ✓ | ✓ | 不够格 | 动作族第 2 环（确认） |
| 16 | `aistare_card_detail` | 盯盘数据卡片拓展 | 3 | ✓ | ✗ | ✓ | ✗ | 不够格 | 卡片本体与编辑/删除不是同一操作 |
| 17 | `home_banner_hide` | 隐藏 横幅 | 1 | ✗ | ✓ | ✓ | ✓ | 不够格 | 首页只有一条横幅，三页是同一横幅 |
| 18 | `market_banner_hide` | 隐藏 滚动横幅与快捷入口 | 2 | ✗ | ✓ | ✓ | ✓ | 不够格 | 两块合并成一个开关，该拆不该扩 |
| 19 | `market_sector_compact` | 板块显示单行 | 6 | ✓ | ✓ | ✗ | ✓ | 不够格 | 状态是像素高度，且 3 列下只有两档 |
| 20 | `market_overview_compact` | 市场概览高度优化 | 1 | ✗ | ✓ | ✗ | ✓ | 不够格 | 三张图轮流占位、状态是 0.6f |
| 21 | `market_stock_filter` | 增加 涨跌与自选 筛选项 | 3 | ✓ | ✓ | ✓ | ✓ | 不够格·反例 | 族成立得太彻底：选择器已在宿主面板里 |
| 22 | `nav_bar_blend` | 手势导航条同色 | 页面（无清单） | ✗ | ✓ | ✓ | ✓ | 不够格 | 模块里没有页面枚举机制 |

---

## 三、A 级：值得做

### 3.1 `stock_bar_entry` —— 详情页底栏入口（已落地 2026-11-14）

**为什么它排第一**：底栏那族解决「不想看的入口」，这一族解决「想一键直达的入口」，是同一个思路的另一半。而且宿主侧**本来就有一条重排机制**：把「更多」盒子加宽，「买 / 卖」按权重自动让位（`feature/stockbar/StockDetailBarTargets.kt:39-60`，实测 1 项时盒子 96→246px、买卖各让一半），加项只是往盒子里多插一颗按钮。

| 项 | 形态 |
|---|---|
| 一级开关 | 标题改「自定义 详情页底栏 入口」，仍用 `STOCK_BAR_ENTRY` 键当总开关；**不加副标题** |
| 一级入口行 | 「详情页底栏入口设置」+ 副标题「已提上 %1$d 个」/「只用默认入口」+ ›，计数在 `ui/FeatureTab.kt:128` 现算 |
| 二级页 | 一卡七行 `GsSwitchPreference`（条件单 / 智能盯盘 / 智能K线 / 产业链 / 区间统计 / 交易明细 / 股票PK），勾=提到外面；达到容量上限后其余行 `enabled=false` + 副标题「底栏放不下第三个」 |
| 键 | `STOCK_BAR_ITEMS = "stock_bar_items"`，默认 `""`，`{"v":1,"items":["智能盯盘"]}`；旧布尔键保留作回落（开且未写过新键 → 读作 `["智能盯盘"]`） |
| 容量 | **最多 2 项**。每项占 124px 按钮 + 26px 间距，实测 1 项后买 246 / 卖 245；第二项约 171 / 170；第三项约 96 / 95（推算，未实测）——第三项等于把买卖压到只有文字宽 |
| 目标侧改动 | 注入记账由「单入口」改成「一个盒子对一份列表」（`StockDetailBarFeature.kt` 的 `Injected` 现在只有单个 `button` 字段）；其余链路（开「更多」→ 按文案定位 → `performItemClick`）逐项复用 |
| 形态差异 | 个股页 7 项、指数页只有 2 项（`StockDetailBarTargets.kt:74-76`），二级页在指数页会有多项点不中 → 目标侧按形态只认前 2 项 |
| 成本 | 10 个文件（新页面 + Route 三处 + FeatureSpec 三个 `detail*Res` + 两条 strings + core 三件 + StockDetailBar 两件） |

**风险**：每项图标都要隐形开一次「更多」面板采集（`PENDING_TIMEOUT_MS=1500`、`MAX_ATTEMPTS=12`），多入口会串行拖慢；宿主文案随语言切换时按中文文案匹配会失手（未验证）。

**落地结果（2026-11-14）**：与本节原稿有五处差异，都以实际代码为准。

| 项 | 原稿 | 落地 |
|---|---|---|
| 键值 | 存中文面板文字（`["智能盯盘"]`） | 存稳定 key（`["aistare"]`），中文只作面板定位用；宿主换语言也不影响配置 |
| 一级入口副标题 | 「已提上 %1$d 个」/「只用默认入口」 | 「已外提 %1$d 个」/「一项都不外提」（与「已隐藏 N 个」同构） |
| 满额副标题 | 「底栏放不下第三个」 | 「仍在更多里·底栏最多两个入口」（先说当前状态，再说限制） |
| 指数页 | 目标侧只认前 2 项 | 目标侧按「更多」按钮的 id 判形态，指数页只暴露「智能盯盘 / 区间统计」两项 |
| 图标采集 | 未定 | 按面板文字分别缓存（`entryIcons: ConcurrentHashMap`），一次采集轮把所有缺图标的项一起补 |

实现文件：`core/StockBarItemStore.kt`（新）、`ui/StockBarEntriesScreen.kt`（新）、
`feature/stockbar/StockDetailBarTargets.kt`（七项映射表）、`feature/stockbar/StockDetailBarFeature.kt`
（`Injected` 单按钮 → 按钮列表）、`ui/FeatureCatalog.kt` / `ui/Routing.kt` / `ui/FeatureTab.kt`、
`core/ConfigKeys.kt` / `core/ConfigSnapshot.kt` / `core/ConfigSource.kt` / `feature/FeatureManager.kt` /
`app/DebugConfigProvider.kt`、中英 strings 各 15 条、单测 `StockBarItemStoreTest`（12 条）。

### 3.2 `watchlist_highlight` —— 高亮分涨 / 跌 / 平三档（已落地 2026-11-14）

现状是一个颜色值（`ConfigKeys.COLOR`，默认 `0xFFFF8000`）给所有在自选的股票名称染色。涨跌平是行数据里现成的字段（`zdf` 已登记在 `core/zxcopy/ZxCopyHostFields.kt:23`），三档是真同类对象。

| 项 | 形态 |
|---|---|
| 一级 | 标题与副标题都不动（「自选股高亮」已合规） |
| 二级 | 沿用现有「高亮颜色设置」页（`Route.WatchlistDetail` 已有，不加 Route）：把那排色块换成三行**选值行**（当前色块 + ↓ + `ChoiceDialogSpec`），行名「上涨 / 下跌 / 平盘」 |
| 键 | `WATCHLIST_COLORS = "watchlist_colors"`，`{"v":1,"up":"#FFFF3B30","down":"#FF3482FF","flat":"#FFFF8000"}`；旧 `color` 只读回落（解不出新键 → 三档同色 = 与现行为等价） |
| 成本 | 8 个文件 |

**风险**：`WatchlistFeature.kt:507-508` 的 `isPresetColor` 只认 `PRESET_COLORS` 四色，三档默认色若不并入该集合，关开关时会把我们染的色当成原色记进 tag，还原成错色。真机必须走一遍「行情跳动后改档位」与「筛选中 / header 行」两条路径。

**落地结果（2026-11-14）**：三个档位的取色全部收在 `core/WatchlistColorStore`（`decode` / `colorFor` / `encode` / `distinctCount`），
判档逻辑单独放在纯函数 `core/WatchlistTier`（`ofZdf` 读数据项字段、`ofText` 读行内 `tv_item_stk_percent` 文案，**读不出返回 `null`** —— 由调用方落回平盘，
不会把「字段缺失」当成平盘染色）。
二级页 `ui/WatchlistDetail.kt` 换成三行选值行 + 四行预览；入口副标题在 `ui/FeatureTab.kt` 现算「已分 N 种颜色」；
`WATCHLIST_COLORS` 走 String JSON（28 个布尔位一个没占）。单测 `WatchlistTierTest` 9 条 + `WatchlistColorStoreTest` 14 条，全套 438 条通过。
风险那条：三档色一律从 `PRESET_COLORS` 里挑（UI 只给这四色），所以 `isPresetColor` 的集合不用扩，关开关能还原成原色。

### 3.3 `zx_group_copy` —— 复制规则绑分组（已落地 2026-11-14）

规则表本身就是对象族（`ZxCopyRuleStore.kt:115-144`，每条 19 个字段 + 专用编辑页 + 行级四操作），缺的只有一维：这条规则**用在哪个页签**。宿主三个页签是现成的（`#vp_zx_group` 三页），`Snapshot.groupName` 已经取到分组名。

| 项 | 形态 |
|---|---|
| 一级 | 不动 |
| 二级 | 在规则编辑页「按钮动作」卡后插一张「适用分组」卡：**一条选择行**（任何分组 / 全部 / 自选 / 持仓股）；规则列表预览前面多一段「仅 持仓股」 |
| 键 | **不新增键**，在 `zx_copy_rules` 的 `rules[]` 里加可选字段 `"groups":[]`，空 = 不绑定。**不要动 `FORMAT_VERSION`**（升版本只会诱使后来者写分支） |
| 成本 | 5 个文件，目标侧零重排 |

**风险**：「持仓股」页的分组名可能不在 `getAllGroups()` 的返回里（该接口只回 2 组）→ 必须定义空名回落 `__all__`，否则那一页复制不出来。

**落地结果（2026-11-14）**：与上面两处原稿不同，改成了更稳的两条：

- **绑定的是分组 tab 的稳定键，不是分组名**：`core/zxcopy/ZxCopyGroups.kt` 只认 `all` / `self` / `holding`，
  对应宿主 tab 的全部 / 自选 / 持仓股。存中文标题的话宿主一改文案全断；认不出的 tab 文案
  （自建分组、宿主改名）一律返回 `null` 按「没绑定」处理，**不用位置序号兜底**（分组增删会挪位置）。
- **不设 `__all__` 哨兵**：`groups` 空数组 = 不绑定，少一层编码分支；老配置解析出来就是这个状态，
  升级模块不改任何既有规则的行为。
- UI 改成**一条选择行**而不是三行开关（[DESIGN-SYSTEM](DESIGN-SYSTEM.md) §3.5：从固定几个值里选一个用选择行；
  三开关还要额外定义「全关」「至少留一个」的语义）。
- 取值语义：`Data.ruleForGroup(groupKey)` 取第一条命中当前分组的规则，否则回落 `defaultRule()`；
  `feature/zxcopy/ZxGroupStocks.kt` 的 `groupKeyOf` 提供当前 tab 键。
- 单测：`ZxCopyRuleStoreTest` 补 6 条（往返 / 未知键丢弃 / 缺字段 / 回落默认 / 同组取第一条 / duplicate 保绑定），
  新增 `ZxCopyGroupsTest` 5 条；全套 449 条通过，`:app:assembleDebug` 通过。

### 3.4 `dock_clean` —— 已落地

见 [FEATURE-DOCK-ENTRIES](FEATURE-DOCK-ENTRIES.md)。本路线图只记一条：它的「先落地样板基础设施」这一步，是另外三族的共同前置 —— 二级页母版、`RouteSaver` 分支、入口行计数、JSON 回落读取，一次做完三族都省。

**落地结果（2026-11-14）**：母版照 `ui/StgFilterSettingsScreen.kt` 抄（`Column` + `Card` + 六个 `GsSwitchPreference`），
`ui/Routing.kt` 加了 `Route.DockEntries` 与 `RouteSaver` 两个分支，入口行计数在 `ui/FeatureTab.kt` 用 `DockEntryStore.decode` 现算，
JSON 回落读取抽成 `core/DockEntryStore.kt`（`decode(raw, legacyHidden)`，读侧回落不写盘）。
另外三族现在可以直接复用：`Route` 加一项 + `RouteSaver` 两分支 + 一个 `decode/encode` 的 Store + 入口行一个 `remember*Pref` 分支。

---

## 四、B 级：够格，但不划算

| id | 够格的理由 | 为什么不排进 A |
|---|---|---|
| `stg_stock_filter` | 策略与聚合组各配一套 8 行筛选（`StockFilterScopes.kt:44` 的 `grp:` 前缀、`MAX_ITEMS=200`） | **硬阻塞**：8 个筛选取值住在目标 App 自己的 prefs 里（`ConfigKeys.kt:139-142` 明写 remote preferences 在目标进程只读），模块 UI 进程读不到现值；现有回传只带策略 id 与名字。要么显示空值，要么先造通路。工作量 ≈12 文件，是另外三族之和 |
| `market_index_hide` | 块内 4 项同权（`MarketTargets.kt:164/184`） | 现状是**整块隐藏**，按项隐藏等于新功能；4 项共用 `ll_content` 只能用序号做 key，而候选 id 自述「来源只有一处、未二次核实」（`:162-167`）。收益全场最低 |
| `watchlist_bold` | 行内 4 列可分别加粗（名称 / 代码 / 现价 / 涨跌幅） | 需求集中在名称列；且 price / percent 两个 id 只在策略详情页测得，自选分组页未登记，跨页复用会找不到控件 |

---

## 五、已经是族：不需要升级，但可以照抄

`stg_merge`（聚合组）与 `aistare_quick_config`（多方案）已经是**列表式对象族**：底部「新建」动作行 → 落一行 → 行内重排 / 删除 → 点行进编辑页（`ui/StrategyMergeScreen.kt:81-90`、`ui/QuickConfigScreen.kt:89/221`，规则表同款 `ui/ZxCopyRuleScreen.kt:89-94`）。

这两页是**实现母版**：新增的族如果需要「可增删的对象」，照抄它们，不要另造交互。反过来，它们也确实**没有**可升级的维度 —— 已经是「N 个对象 + 每对象若干字段」。

---

## 六、不够格：逐条理由

按不合格原因归类，同因合并：

**① 对象只有一个（P1 否）**

| id | 证据 | 顺带该做的 |
|---|---|---|
| `ipo_dialog_clean` | `IpoCleanTargets.kt:43-45` 全名数组只有 1 个元素；简名数组是包路径改版兜底、`CREATE_METHODS` 自述只用于日志、`SHOW_METHODS` 是同一请求链第二道保险 | 无（标题已合规）。真要做，得先真机 dump 出 ≥3 个宿主弹窗类名 |
| `home_banner_hide` | 首页只有一条 `#layout_banner`，14 个候选名是同一容器的别名 | 无 |
| `market_overview_compact` | 三张图轮流占位、只有一张可见；状态是 `0.6f` | 无；若真要手感，是一行「压缩比例」选择行，收益低 |
| `stg_select_all` | 宿主多选栏只有「取消」一个锚点，按钮插在它左边；「反选」只出现在量宽与文案切换 | 不动。做档位会是「全选 / 反选 / 清空」一行，收益为负 |
| `stg_snap_add` | 一颗按钮、一个注入锚点 | 可做「按钮文案」输入行，属设置项不属族 |
| `aistare_skip_save_confirm` | 三处保存入口点的是同一个 DOM 按钮 | 动作族第 2 环，可归并进「保存行为设置」页 |

**② 操作不一致（P2 否）**

| id | 证据 |
|---|---|
| `stg_direct_quote` | 作用对象是「被点的那一只股票」，一次只有一只；现有 `stg_quote_swipe` 两态，第三档等于关掉功能 |
| `aistare_card_detail` | 卡片本体是「跳个股详情」（模块新加），编辑/删除是宿主既有控件 —— 隐藏它们要遍历视图 + 让右侧重排，两类操作两种实现；且 `EDIT_ICON_IDS` 全仓零读取，等于从零写 |

**③ 状态不可序列化 / 只有两档（P3 否）**

| id | 证据 |
|---|---|
| `market_sector_compact` | 状态落成容器像素高度（`MarketSectorCompactFeature.kt:145-157`）；3 列布局下只有「单行 / 双行」两档，4 与 5 会裁出半行 |

**④ 族成立得太彻底（反例）**

| id | 说明 |
|---|---|
| `market_stock_filter` | 三个筛选项的选择器**已经在宿主筛选面板里**，面板就是唯一操作现场。模块再建一页会产生第二处入口、同一份状态两处编辑 —— 这是「不该升级」的典型：升级的判据它全中，但它已经完成了 |
| `zx_copy_menu` | 菜单项天然 ≥3，但内容大半被规则表覆盖（每条规则各占一行），剩 4 个固定动作用户基本不关。更省事的是把这页唯一那行开关并回「复制设置」顶部 |

**⑤ 缺对象来源**

| id | 说明 |
|---|---|
| `nav_bar_blend` | 对象是「页面」，而模块里没有任何页面清单 / 枚举机制；分方向（手势 / 三键）已由系统配置自动判定，不需要用户选 |

**⑥ 该拆不该扩**

| id | 说明 |
|---|---|
| `market_banner_hide` | 一个开关收两块（轮播 `#avf_hotpoint` + 宫格 `#rv_entra`），P1 只有 2。正确动作是**拆成两个开关**，不是建族。注意拆开会各用 1 个布尔位，占掉余量 4 个里的一半 |

---

## 七、顺手该修的不一致

这些不属于升级路线，但改到对应文件时会撞上，一并列出来：

| 位置 | 问题 |
|---|---|
| `docs/UI.md:460` | 写 `zx_group_copy`「二级页：无」，与 `ui/FeatureCatalog.kt` 的两个入口矛盾 —— **已修（2026-11-14）**，改为列出两个入口 |
| `ui/ZxCopyRuleScreen.kt:66` | 注释说菜单开关在本页顶部，实际在 `ui/ZxCopyButtonScreen.kt:38-48` —— **已修（2026-11-14）** |
| `ui/Routing.kt:370` | `stg_stock_filter` 与 `aistare_quick_config` 两条 `when` 分支挤在同一行。**实测不是错误**：`--rerun-tasks` 全量重编通过，临时单测证明两个分支都活着 → 只是可读性问题，本轮不动 |
| `app/src/main/res/values/strings.xml:253` | `nav_bar_blend` 副标题「安卓底部小白条吸色取色」写的是机制，违反 [DESIGN-SYSTEM](DESIGN-SYSTEM.md) §7.3 |
| `app/src/main/res/values/strings.xml:264/268/275` | 三条盯盘功能标题是抽象词（「优化确定逻辑」「无需确定保存盯盘」「盯盘数据卡片拓展」），违反 [NAMING](NAMING.md) §7 |
| `app/src/main/res/values/strings.xml:236` | `ipo_dialog_clean` 标题合规，但 `market_banner_hide` 标题用「与」把两块绑死，拆开关时同步拆名 |

---

## 八、落地顺序

| 阶段 | 内容 | 前置 |
|---|---|---|
| 0 | ~~修 `ui/Routing.kt:370` 语法错误~~ —— 实测不是错误，只是可读性问题（§7），本阶段取消 | — |
| 1 | 样板基础设施：二级页母版抽出来、`RouteSaver` 加分支、入口行计数、JSON 读写的「回落不写盘」helper | 无 |
| 2 | `dock_clean` → `FEATURE-DOCK-ENTRIES` 落地（六格开关）—— **已落地 2026-11-14** | 阶段 1 |
| 3 | `stock_bar_entry` → 详情页底栏入口（容量 2）—— **已落地 2026-11-14** | 阶段 1；目标侧注入记账列表化 |
| 4 | `watchlist_highlight` 三档色（不需要新 Route，改现有页）—— **已落地 2026-11-14** | 阶段 1 |
| 5 | `zx_group_copy` 规则绑分组（不需要新键）—— **已落地 2026-11-14** | 阶段 1 |
| 6 | 文案与副标题清理（§7 后四项） | 无，可随时插入 |

阶段 2–5 的每一族，落地时按 [DESIGN-SYSTEM](DESIGN-SYSTEM.md) §1.5 的清单走：Route → RouteSaver save/restore → MainScreen when → 入口行 → FeatureSpec 三个 `detail*Res` → 中英 strings → UI.md → 本仓索引。

---

## 九、未验证清单

以下都没实测，落地前必须补证，不要当结论引用：

1. 详情页底栏第二 / 第三项的真实宽度（150px/项、171/96 是推算；只实测过 1 项）。
2. 宿主「更多」弹层七项文案是否随系统语言变化（模块按中文文案匹配）。
3. 指数页那 2 项是否与个股页共用同一套注入宽度约束。
4. 指数概览 4 项是否各带 weight（藏一项后能否自动均分）；第 4 项名字仍未核实。
5. 板块容器限高后是否露出半张卡片、`LockViewPager` 是否还能滑。
6. 自选分组页行布局里现价 / 涨跌幅两个 id 是否存在（决定 `watchlist_bold` 能不能做四列）。
7. `PIXEL_COOLDOWN_MS` 是全局还是 per-Activity 限频（影响任何按页面的方案）。
8. 在二级页改盯盘保存相关开关后，当前 H5 页面是否立刻生效。
9. `WATCHLIST_COLORS` 三档默认色并入 `PRESET_COLORS` 后，还原链路是否真的不再串色（需真机）。
