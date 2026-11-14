# 底栏入口自定义（dock_clean 升级）设计稿

> **一句话**：把「隐藏 理财 入口」这一个单点开关，反推成「自定义 底栏 入口」总开关 +
> 二级页「底栏入口设置」，页内六个开关逐格决定底栏哪个入口显示。
>
> 状态：**已落地**（2026-11-14）。反推方法与判据见 §1，二级页的规范对账见 §6，
> 落地顺序见 §9，验收判据见 §10。
>
> 落地结果：一级开关 `feature_dock_clean_switch` 改为「自定义 底栏 入口」、一级入口行走
> `ui/FeatureTab.kt` 现算「已隐藏 N 个」、二级页 `ui/DockEntriesScreen.kt`（六行开关、至少留一格）、
> 参数键 `dock_entries`、目标侧 `feature/dock/DockCleanFeature.kt` 按 hidden 集合重排。
> 页面与入口已登记进 [UI](UI.md) §4.2、[NAMING](NAMING.md) §5、[DESIGN-SYSTEM](DESIGN-SYSTEM.md) §14.1。

---

## 1. 反推方法：单点开关 → 对象集合

### 1.1 四条判据

一个「只做一件事」的开关值不值得升级，看四条，缺一条就别升：

| # | 判据 | 反例（升了是负收益） |
|---|---|---|
| 1 | 它操作的对象是**一族同类对象**，数量 ≥ 3 | 「隐藏首页横幅」只有一条横幅，升级后页里只剩一个开关 |
| 2 | 每个对象的操作**完全一致**（都是显示 / 隐藏） | 「聚合组」每组的操作有重命名 / 排序 / 复制，那就不是开关组，是列表 |
| 3 | 每个对象的状态**能序列化成一个值** | 「压缩市场概览」的状态是像素高度，不是一个布尔 |
| 4 | 宿主侧的**机制不随对象数量变** | 宿主底栏是等分栅格，藏 1 格和藏 3 格走同一套重排 |

四条全中 → 升级；只中 1–2 条 → 换形态（拆开关 / 加档位）比硬套「清单」更省事。

### 1.2 四步反推

1. **找单点**：翻一遍 `ui/FeatureCatalog.kt`，挑出「一个开关 + 一段写死逻辑」的功能。
2. **问对象族**：问它藏 / 缩的那个东西，是不是同族里的一员（底栏 6 格、板块 6 项、横幅 2 条）。
3. **换形态**：把写死的单点换成「清单 + 每项状态」，原来的开关降级为「这套自定义是否生效」。
4. **参数下沉**：具体参数进二级页，一级页只留开关 + 一个「X 设置」入口行 —— 这正是
   [EXTENDING](EXTENDING.md) §3 与 [DESIGN-SYSTEM](DESIGN-SYSTEM.md) §3.0 已经定下的分工。

### 1.3 22 个功能逐条过

`ui/FeatureCatalog.kt` 里现在有 **22 条 `FeatureSpec`**，其中 **7 个功能带二级页、共 9 个二级入口**。
按 §1.1 过一遍：

| 功能（卡片名） | 现状形态 | 对象数 | 结论 |
|---|---|---|---|
| 隐藏 理财 入口 | 单点开关 | 6（底栏格） | **本篇升级** |
| 板块显示单行 | 单点开关 + 写死 `SECTOR_KEEP = 3` | 6（板块项） | 可升级成档位选择，不是清单（判据 3 不中） |
| 市场概览高度优化 | 单点开关 + 写死 `OVERVIEW_HEIGHT_FACTOR = 0.6f` | 1（高度） | 可升级成两档，收益低，暂缓 |
| 隐藏滚动横幅与快捷入口 | 一个开关管两个对象 | 2 | 可拆成两个开关（判据 1 不足 3） |
| 滑动设置 / 筛选条件保存设置 / 聚合组设置 / 方案设置 / 配置按钮设置 / 保存后返回位置设置 / 复制设置 / 复制按钮设置 / 高亮颜色设置 | 已有二级页 | — | 已到终点，不动 |
| 隐藏横幅（首页）/ 隐藏指数概览 / IPO 弹窗精简 / 自选加粗 等其余 | 单点开关，对象唯一或操作不同 | ≤ 2 | 不升级 |

### 1.4 本篇之外的候选

| 候选 | 形态 | 成本 | 收益 | 建议 |
|---|---|---|---|---|
| 板块保留行数 | 二级页一行「显示行数」，3 / 4 / 5 / 6 四档，`ChoiceDialogSpec` 锚定本行 | 低（只换 `SECTOR_KEEP` 的取值来源） | 中（不同屏幕差异明显） | 下一篇做，单独出稿 |
| 市场横幅拆两项 | 两个独立开关，无二级页 | 极低 | 中 | 顺手做，不占本篇 |
| 市场概览压缩比例 | 两档（紧凑 / 标准） | 低 | 低 | 暂缓 |

---

## 2. 目标形态

### 2.1 一级页（功能 Tab → 底栏分区）

| 元素 | 内容 |
|---|---|
| 开关行 | 标题「自定义 底栏 入口」；无副标题；出厂关 |
| 入口行（开关打开后展开） | 标题「底栏入口设置」；副标题「已隐藏 %d 个」/「六个入口全部显示」；行尾 › |

开关的语义从「隐藏理财」变成「这套自定义是否生效」，**不再承担具体是哪一格**。

### 2.2 二级页（底栏入口设置）

```
SubScreen(标题 = 底栏入口设置)
└── Column(fillMaxSize + verticalScroll)
    ├── Spacer(8.dp)
    ├── Card(Modifier.cardPadding())
    │   ├── GsSwitchPreference  隐藏 首页 入口     副标题 已隐藏 / 显示中
    │   ├── GsSwitchPreference  隐藏 行情 入口
    │   ├── GsSwitchPreference  隐藏 自选 入口
    │   ├── GsSwitchPreference  隐藏 交易 入口
    │   ├── GsSwitchPreference  隐藏 理财 入口
    │   └── GsSwitchPreference  隐藏 账户 入口
    └── Spacer(24.dp + LocalNavBarInset.current)
```

一页一张卡、卡内六行开关，**没有**新建行、没有 ⋮、没有弹层、没有说明文字 —— 与
`ui/StgFilterSettingsScreen.kt`（63 行）完全同构，是现役最短的二级页母版。

六行估算 420dp 上下（开关行 70dp 一档），普通屏一屏放得下；照样保留 `verticalScroll`，与其它二级页一致。

### 2.3 为什么是「六行开关」而不是「列表 + ✕」

| 方案 | 形态 | 判据 | 结论 |
|---|---|---|---|
| A 六行开关（本稿） | 每格一个「隐藏 X 入口」开关，开 = 藏 | 状态全可见、一次看全 6 项、零新交互组件 | ✅ 采用 |
| B 列表 + 行尾 ✕ | 卡内列「已隐藏的入口」，✕ 恢复显示 | 默认空表 → 要空态占位；「隐藏更多」还得再来一层选择器 | ❌ 多一层、多两种形态 |
| C 单行 + 弹层多选 | 一行「管理的入口」→ 多选弹层 | 现有五种 `DialogSpec` 都是单选 / 确认 / 输入，没有多选 | ❌ 要造新组件，违反 §0.3「组件取用」 |

A 直接落在 [DESIGN-SYSTEM](DESIGN-SYSTEM.md) §3.1（开关行）+ §7.6（`_on` / `_off` 状态串）之内，一行新交互都没引入。

---

## 3. 文案（中英同步）

| 资源名 | 中文 | 英文 |
|---|---|---|
| `feature_dock_clean_switch` | 自定义 底栏 入口 | Customize dock entries |
| `feature_dock_clean_entry` | 底栏入口设置 | Dock entry settings |
| `feature_dock_clean_entry_summary` | 已隐藏 %1$d 个 | %1$d hidden |
| `feature_dock_clean_entry_summary_all` | 六个入口全部显示 | All six shown |
| `detail_dock_clean_title` | 底栏入口设置 | Dock entry settings |
| `switch_dock_entry_home` | 隐藏 首页 入口 | Hide "Home" entry |
| `switch_dock_entry_quotes` | 隐藏 行情 入口 | Hide "Quotes" entry |
| `switch_dock_entry_zx` | 隐藏 自选 入口 | Hide "Watchlist" entry |
| `switch_dock_entry_trade` | 隐藏 交易 入口 | Hide "Trade" entry |
| `switch_dock_entry_money` | 隐藏 理财 入口 | Hide "Wealth" entry |
| `switch_dock_entry_mine` | 隐藏 账户 入口 | Hide "Account" entry |
| `switch_dock_entry_summary_on` | 已隐藏 | Hidden |
| `switch_dock_entry_summary_off` | 显示中 | Shown |
| `switch_dock_entry_summary_locked` | 显示中·底栏至少保留一个入口 | Shown · at least one entry stays |

要点：

- 六行共用 `_summary_on` / `_off` 两条（同 `stg_merge_group_state_on/off` 跨行共用的先例），
  文案完全一致，不为六行复制十二份同文。
- 开关标题沿用「隐藏 理财 入口」已有的三段式「动词 + 内容 + 控件类型」，六行同构。
- 英文引号按 [NAMING](NAMING.md) §6 保留，撇号不出现，无需转义。
- 一级开关**不加副标题**：能写的只有「按设置隐藏底栏入口」这类机制句，而计数已经由入口行副标题承担
  （同一结果不重复播报）。

---

## 4. 尺寸与状态表

| 项 | 值 | 依据 |
|---|---|---|
| 卡片水平外边距 | 12dp（`Modifier.cardPadding()`） | DESIGN-SYSTEM §1.4 / §2.3 |
| 卡内行左内边距 | 16dp | 同上 |
| 首屏顶部留白 | 8dp（二级页不加 `LocalTopInset`） | §1.4 |
| 列表末尾留白 | 24dp + `LocalNavBarInset` | §1.4 |
| 行高 / 热区 | 整行可点，≥ 40dp | §11 |
| 页面底色 | `colorScheme.surface` | §0.3 |

行状态：

| 状态 | 开关 | 副标题 | 交互 |
|---|---|---|---|
| 该格显示中 | off | 显示中 | 可点，点后变「已隐藏」 |
| 该格已隐藏 | on | 已隐藏 | 可点，点后恢复显示 |
| 只剩这一格显示 | off + `enabled = false` | 显示中·底栏至少保留一个入口 | 不可点（禁用配色） |

三类行文案都进 strings，不拼字符串。

---

## 5. 交互规则

### 5.1 开关语义

**开 = 隐藏该格**，与现役「隐藏 理财 入口」的读法一致，也与出厂全关（全部显示）一致。
不改成「显示该格」：那样出厂默认要全开，与 §3.1「功能开关出厂默认关」冲突，而且「启用这个功能」
与「每个格显示不显示」两套默认值会互相打架。

### 5.2 下限一个入口

底栏全藏 = 整行空白、用户失去唯一导航，没有任何回退路径，所以**至少保留一格**：

- 已隐藏 5 个时，剩下那一个「显示中」的行置 `enabled = false`，副标题换成 `_summary_locked`。
- 用户在别行恢复一格 → 隐藏数回到 4 → 被锁那行自动解锁，闭环不需要额外弹窗。
- 不播 Toast：禁用态本来就点不动，点了不会发生什么，播报反而是噪音。

目标进程侧再兜一道：重排时若「可见格数 = 0」，`restoreAll()` 回到全显示并记一条警告日志 ——
宁可全显示，也不留空底栏。

### 5.3 写回时机

`onCheckedChange` 里立刻把整份 JSON 写回 remote preferences，**没有保存按钮**，与
`ui/QuickConfigScreen.kt` 的「每改一下立即写回」一致。写入前重读一次最新值再合并本行改动，
避免两次快速点击互相覆盖（`StrategyMergeScreen` 的 `updateGroup` 同做法）。

### 5.4 反馈

本页**没有 Toast、没有 CardNote、没有弹层**：开关自身状态 + 一级页入口行计数就是全部反馈
（DESIGN-SYSTEM §10「同一结果不重复播报」）。

### 5.5 边界

| 情况 | 行为 |
|---|---|
| 宿主这一版没有某格（如无「交易」） | 该格开关照常可写；目标进程认不到就记日志，无副作用 |
| 目标进程未运行 / 模块未启用 | 值照写，宿主下次进主界面生效 |
| 配置被重置 | `dock_entries` 回空数组、`dock_clean` 关 → 六格全显示 |
| JSON 解析失败 | 视为空数组（全显示）+ 一条警告日志，不抛异常 |

---

## 6. 规范对照表

| 设计决策 | 依据 |
|---|---|
| 一级页只放一个开关 + 一个入口行 | DESIGN-SYSTEM §3.0 决策树、§3.1；EXTENDING §3 |
| 开关打开才展开入口行，220ms 展开 / 180ms 收起 | §3.1 |
| 入口行副标题写状态（已隐藏 N 个 / 六个入口全部显示） | §3.2、§7.3 状态类「值·计数」 |
| 入口行行尾 › | §3.2（› = 进二级页） |
| 二级页 = 一张卡 + 六行开关，无小标题 | §1.4、§2.1、§2.2（卡内只放能点的行） |
| 六行都是开关行，二态用 `_summary_on/_off` | §3.1、§7.6 |
| 最后一格用禁用态而不是弹窗拦截 | §3.5 的 `disabledIndices` 先例、`GsSwitchPreference(enabled = false)` |
| 页面没有任何常驻说明文字 | §2.2、§13 自检项「无解释性文字」 |
| 写配置无保存按钮、即时生效 | §0.3 与方案设置母版一致 |
| 新增页面要动的 8 处 | §1.5 |

> 与 §1.5 的一处差异：本页**不需要** `editParent`（不是三级页），也不需要在 `RouteSaver`
> 里带参数（页面无入参）。§1.5 的其余六处照做，清单见 §9。

---

## 7. 数据模型与迁移

### 7.1 新键 `dock_entries`

```
ConfigKeys.DOCK_ENTRIES = "dock_entries"
默认值  DEFAULTS["dock_entries"] = ""
取值    {"v":1,"hidden":["money","trade"]}
```

- `hidden` 是**被隐藏的格 key 数组**，顺序无关，读取时收进 `Set<String>`。
- key 固定六个：`home` / `quotes` / `zx` / `trade` / `money` / `mine`，与宿主 `cb_main_tab_*`
  后缀同名 —— 出问题时日志与视图树能直接对上。
- `v` 留给以后加字段（例如排序、图标），解析时未知字段忽略。

### 7.2 为什么不是六个布尔键

| 方案 | 代价 |
|---|---|
| 六个布尔（`dock_hide_home` …） | 六个 `ConfigKeys` + 六条默认值 + 六处重置 + 六处快照，改一次要动六轮 |
| 一个 JSON（本稿） | 一个键、一次解析，以后加排序 / 图标不用再动键表 |

### 7.3 迁移：读取侧回落，不写盘

旧用户（`dock_clean = true`）不该在升级后看见理财回来。做法是**只回落、不写回**：

```kotlin
fun decode(raw: String?, legacyHidden: Boolean): Set<String> =
    if (raw.isNullOrBlank()) { if (legacyHidden) setOf("money") else emptySet() }
    else parseOrDefault(raw, emptySet())
```

- 键存在（哪怕 `[]`）→ 完全以新键为准，用户主动「全部显示」不会被旧开关复活。
- 键不存在 + 旧开关开 → 等价于旧行为（只藏理财）。
- 两个进程（模块 App / 目标进程）都调同一个 `decode`，`ConfigSnapshot` 同时带 `dockClean`
  与新键原始字符串即可，**不需要任何一次性写盘**；回滚也只是把功能关掉。

因此 `DOCK_CLEAN` 键**保留**（仅作回落源，1.x 期间不删），`DEFAULT_DOCK_CLEAN` 不动。

### 7.4 键要落的六处

| 位置 | 改法 |
|---|---|
| `core/ConfigKeys.kt` `ALL_KEYS`（现 :610-622） | 加 `DOCK_ENTRIES` |
| `core/ConfigKeys.kt` `FEATURE_PARAM_KEYS`（现 :662-666） | 加 `DOCK_ENTRIES`（重置回默认空数组，不是关掉） |
| `core/ConfigKeys.kt` `SNAPSHOT_KEYS`（现 :684-695） | **必须加**，否则目标进程收不到变更（只增不减） |
| `core/ConfigKeys.kt` `DEFAULTS`（:676-709+） | `DOCK_ENTRIES to ""` |
| `core/ConfigSnapshot.kt`（现 :38-45 / :157 / :178）、`core/ConfigSource.kt`（现 :254-255） | 加 `val dockEntries: String`、进 `mix`、默认 `""`、读远端值 |
| `app/DebugConfigProvider.kt`（:60-78 列名 / :133 列清单 / :161 读 / :267-268 导入） | 加 `COL_DOCK_ENTRIES = "dock_entries"` 并进清单，否则调试面板看不到新键 |

`DOCK_CLEAN` 在前五处**原样保留**；第六处（调试面板）是纯调试用，也照加一份新列，旧列不删。

---

## 8. 目标进程侧实现要点

### 8.1 六项目标表

`feature/dock/DockCleanTargets.kt` 现有 `REMOVE_ITEM_ID_CANDIDATES` / `REMOVE_TEXT` 是「理财」那一格的
定位常量，**保留原名不动**（[FEATURE-UI-CLEAN](FEATURE-UI-CLEAN.md) §4 的校准手册逐字引用了它们），
新增一张表把它们包进去：

```kotlin
class DockEntry(val key: String, val label: String, val idCandidates: Array<String>)

val ENTRIES = arrayOf(
    DockEntry("home",   "首页", arrayOf("cb_main_tab_home")),
    DockEntry("quotes", "行情", arrayOf("cb_main_tab_quotes")),
    DockEntry("zx",     "自选", arrayOf("cb_main_tab_zx")),
    DockEntry("trade",  "交易", arrayOf("cb_main_tab_trade")),
    DockEntry("money",  "理财", REMOVE_ITEM_ID_CANDIDATES),   // 8.6.0 实测命中过
    DockEntry("mine",   "账户", arrayOf("cb_main_tab_mine")),
)
```

定位仍是**两级兜底**：先按该格自己的候选 id，再用「认行 + 在行里按文案找格」——
六个 `label` 就是六条文案兜底，`UiClean.findText` 现成可用。**不按下标**：不同账号底栏项数不一样，
下标一错就藏错格。

### 8.2 多格重排：把「藏一格」的公式推广一行

现役 `DockCleanFeature.fillRow` 的可用路径是两步：整行加宽到 `refRowW × n / k`，
再给被藏那格**之后**的每一格一个 `-newCell` 左边距。多格只是把「之后一格」换成「前面藏了几个」：

```
n        = row.childCount           // 含被藏的，8.6.0 实测 6
k        = 可见格数
newCell  = refRowW / k
widened  = newCell × n              // = refRowW × n / k
b(i)     = 第 i 格前面被藏的格数
leftMargin(i) = -b(i) × newCell     // b(i)=0 时不动
```

`SingleGridLayout` 按「序号 × 行宽 ÷ 子项数」摆格，所以第 i 格的自然左边界是 `i × newCell`；
前面有 `b(i)` 个格被 `GONE` 但仍在栅格里占位，减掉 `b(i) × newCell` 就补到 `(i − b(i)) × newCell`，
也就是它应落的槽位。末格右边界 = `k × newCell = refRowW`，正好铺满。

数值例（8.6.0 实测：行 1080px、6 格 × 180px，藏 `money` + `trade`）：

| 格 | i | b(i) | leftMargin | 落位 left → right |
|---|---|---|---|---|
| 首页 | 0 | 0 | 0 | 0 → 270 |
| 行情 | 1 | 0 | 0 | 270 → 540 |
| 自选 | 2 | 0 | 0 | 540 → 810 |
| 交易 | 3 | — | 已 GONE | — |
| 理财 | 4 | — | 已 GONE | — |
| 账户 | 5 | 2 | −540 | 810 → 1080 |

`k = n − 1`（只藏一格）时 `b(i)` 只有 0 和 1 两种取值，公式退化成现役代码 —— 这一步可以拿
**单藏理财**当行为等价基线验：首帧位置、行宽、末格右边界三项逐像素对齐。

### 8.3 幂等与恢复显示

- 每次 `applyTo` 重算期望集合（`snapshot` 解出来的 `dockHidden`），与「本功能已藏的格」做差集：
  该藏的藏、该显的 `patcher.restore(view)` 后把 `leftMargin` 一起还原。
- 已藏的格用 `MutableMap<String, WeakReference<View>>` 记账，键就是六个 key；
  视图被回收就自然失效，下一次 `applyTo` 重新认。
- 重排状态机沿用 `Reflow{TODO, CELL_WIDTH, ROW_WIDTH, REORDER, SETTLED}`，每步等下一帧复查，
  全失败则 `restoreAll()` + 重新隐藏并 `SETTLED`，不无限重试；`REORDER` 之后必须补一次 `hide`，
  避免「藏 → 撤 → 藏」的闪动（2026-10-19 那次修复）。

### 8.4 风险

| 风险 | 处置 |
|---|---|
| 宿主 tab 序号与页面索引耦合（`ROW_WIDTH` 那条老路） | 只加宽 + 负边距，**不 `moveToEnd`**；真机上一旦发现点错页，退到「不重排，只藏」 |
| 多格负边距在非等宽版式上错位 | 沿用现役判据：`abs(width − w0) > w0/4` 视为非等宽，一律不碰，直接 `SETTLED` |
| 某格认不出 | 该格保持原样，日志记「底栏里没有『交易』，跳过」；不影响其它格 |
| 宿主底栏格数变了（少一格 / 多一格） | 认行仍走 `fl_main_tab`（id 优先，不看格数）；结构兜底只认 `MIN_ITEMS`–`MAX_ITEMS`=3–6 格，多出来的格不参与重排，只按 id / 文案逐格处理 |
| 可见格数 = 0 | 见 §5.2，`restoreAll()` 回全显示 |

---

## 9. 落地清单

### 阶段一（界面与配置，行为等同现役「只藏理财」）

| 文件 | 改动 |
|---|---|
| `ui/Routing.kt` | 加 `Route.DockEntries`；`RouteSaver` 的 save / restore 各加一个分支 |
| `ui/Routing.kt`（现 :384） | `onOpenFeatureDetail` 的 `when (id)` 里加一行 `"dock_clean" -> Route.DockEntries` |
| `ui/DockEntriesScreen.kt` | 新增，一卡六行开关（照 `StgFilterSettingsScreen.kt`） |
| `ui/FeatureCatalog.kt`（:267-275） | 补三个 `detail*Res`，`hasDetail` 转 true |
| `ui/FeatureTab.kt` | `featureEntrySummary` 加 `dock_clean` 分支，现算「已隐藏 N 个」 |
| `res/values/strings.xml`、`values-en/strings.xml` | §3 的十四条，中英同步 |
| `core/ConfigKeys.kt`、`core/ConfigSnapshot.kt`、`core/ConfigSource.kt`、`app/DebugConfigProvider.kt` | §7.4 六处 |

### 阶段二（目标进程，多格）

| 文件 | 改动 |
|---|---|
| `feature/dock/DockCleanTargets.kt` | 加 `DockEntry` + `ENTRIES`，旧常量保留 |
| `feature/dock/DockCleanFeature.kt` | 单目标 → 六目标；`fillRow` 按 §8.2 推广；加差集与恢复 |

### 阶段三（文档与自检）

| 文件 | 改动 |
|---|---|
| `docs/README.md` | 索引加本篇（功能专项） |
| `docs/UI.md`（:452） | 功能页清单那行改成新名字与新键 |
| `docs/ARCHITECTURE.md`（:123 / :254 / :386 / :503） | 文件职责、挂载表、快照注释、候选常量表 |
| `docs/NAMING.md`（:182） | 功能对照表行；二级入口从「九个」变「十个」 |
| `docs/DESIGN-SYSTEM.md` §14.1 | 页面清单加一页 |
| `docs/FEATURE-UI-CLEAN.md` | §7.4 校准手册补一句「现在支持多格，公式见 FEATURE-DOCK-ENTRIES §8.2」 |

---

## 10. 验收判据

| # | 判据 |
|---|---|
| 1 | 开关打开、二级页六行全关时，底栏与未开功能时逐像素一致 |
| 2 | 只藏理财时，首帧位置 / 行宽 / 末格右边界与现役版本一致 |
| 3 | 藏 2 格、3 格、5 格分别铺满，末格右边界 = 行宽（误差 ≤ 1/4 格宽） |
| 4 | 藏 5 格时第 6 行开关禁用，恢复一格后立刻解锁 |
| 5 | 恢复显示后 `leftMargin` 归零，位置与全显示一致 |
| 6 | 旧配置升级（`dock_clean = true`、无新键）后仍然只见理财被藏 |
| 7 | 重置模块后六格全显示、开关全关 |
| 8 | 中英切换无截断（英文 ≤ 60 字符） |
| 9 | 二级页键盘不弹、无弹层、无 Toast |
| 10 | 真机走一遍 1–9，日志里没有任何警告 |

---

## 11. 备选与暂缓

| 备选 | 说明 | 暂缓理由 |
|---|---|---|
| A 卡底「全部显示」动作行 | 一次恢复六格，按 §3.4 动作行 + Toast | 六行开关本来一眼可读，最多点五次；多一种行形态就多一处规范例外 |
| B 底栏入口排序 | `hidden` 旁边加 `order` 数组，目标进程按数组挪格 | 挪格要动宿主 tab 索引，风险比藏格高一个数量级（见 §8.4），单独出稿 |
| C 换图标 / 改文案 | 需要按格替换 `ImageView` / `TextView` 内容 | 与本功能正交，属另一个特性 |
