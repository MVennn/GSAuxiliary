# 界面设计规范 · DESIGN-SYSTEM

> **一句话**：先看「状态一眼可读、一行一个决定、卡是分组不是容器」，再照第 3 章的行型目录挑组件 —— 两仓的界面之所以像，不是因为组件一样，而是因为每次都从这张表里挑。

本文件是 **LSPosed 模块系列仓共用**的界面设计规范。它把 GSAuxiliary 与 WxPicLabe 两仓已经跑通的界面做法收敛成一套可执行规则，写清每条规则的**数值、代码形态与反例**。

| 项 | 值 |
|---|---|
| 适用范围 | `GSAuxiliary`、`WxPicLabe`（同源共享 UI 框架的两仓），以及今后从这两仓派生出的模块 |
| 对应框架 | Jetpack Compose + Miuix **0.9.3**（锁版本，见第 1.2 节） |
| 正本关系 | 本文件管**跨仓共性**；各仓 `docs/UI.md` 管**本仓专有页面与文件职责**；`docs/UI-MATERIAL.md` 管材质与弹层内部实现 |
| 副本约定 | 两仓各存一份同内容副本。**改一份必须同步另一仓**，用 `fc /b` 或 `diff` 逐字比对后再提交 |
| 落地检查 | 提交前跑第 13 章自检表；改页面必过真机看一遍（Compose 布局问题编译期零报错） |

---

## 0. 这份规范怎么用

### 0.1 三层文档分工

| 层 | 文件 | 管什么 | 不管什么 |
|---|---|---|---|
| 通用规范 | **本文件** | 骨架、间距、行型、字段形态、弹层选型、图标几何、文案、颜色语义、空态、反馈、无障碍 | 具体某页的分区内容 |
| 本仓界面 | `docs/UI.md` | 文件职责表、本仓页面清单与分区序列、本仓专有形态 | 跨仓共通数值（本文已有） |
| 材质实现 | `docs/UI-MATERIAL.md` | 自绘 DialogHost 内部、液态玻璃三档、组件清单与状态口径 | 设计取舍 |

**判据**：一个规则如果在另一仓也成立，就写进本文件；只有本仓有的页面形态，写进本仓 `docs/UI.md`。同一规则**不得**在两处各写一遍 —— 那是分叉的起点。

### 0.2 十条不可协商的设计原则

| 编号 | 原则 | 落到操作上 |
|---|---|---|
| P1 | **状态一眼可读** | 每个开关、每个入口行的副标题都要能独立回答「现在是什么状态」，不点进去也能判断 |
| P2 | **一行一个决定** | 一个行只做一件事；同一配置键不在两级页面各画一次开关 |
| P3 | **即改即存** | 功能参数页没有保存按钮，改一下就落盘；唯一的二次确认留给「整批毁掉」的动作 |
| P4 | **卡是分组不是容器** | 一张卡 = 一组同类决定；卡内不画分隔线、不写解释段落 |
| P5 | **语义进副标题** | 需要解释「这句话什么意思」时，写成该行的副标题，不写成卡片注脚 |
| P6 | **动作就地反馈** | 进行中→行内置灰；一次性结果→Toast；需要停留查看→卡片注脚（第 10 章） |
| P7 | **能看的不让打字** | 离散取值一律选择器，不让用户手输（列分隔符、模式、主题、日志等级都属此类） |
| P8 | **危险动作靠确认不靠红** | 颜色只是辅助，真正的防线是二次确认弹层的「范围 + 后果」文案 |
| P9 | **默认关** | 所有功能开关出厂一律 `false`，默认值只从 `ConfigKeys.DEFAULT_*` 取 |
| P10 | **真机才算验完** | Compose 的错位、重叠、裁切编译期全不报错，只有真机能看见 |

---

## 1. 页面骨架

### 1.1 三层叠放（顺序写死）

| 层 | 内容 | 位置要求 |
|---|---|---|
| 第 0 层 | 页面底色 Box | **最底层**，不挂任何 `graphicsLayer` 变换（否则可预测返回时四周露灰边） |
| 第 1 层 | 一级页 `HomeScaffold` | 承载顶栏 / 四个 Tab / 底栏 |
| 第 2 层 | 二级页 `SubScreen` | 与三级页本体是**兄弟**节点 |
| 第 3 层 | 三级页 | 单独占一层，用 `editProgress` 独立驱动 |
| 末位 | `DialogHost` | 必须是 `MainScreen` 最外层 Box 的**最后一个**子节点，压住一切 |

**为什么第 2、3 层要拆开**：二级页滑入与三级页滑入是两条独立的滑动进度（`subProgress` / `editProgress`）。合成一条会导致「在三级页返回时二级页也跟着动」。

### 1.2 Scaffold 契约

| 项 | 规定 |
|---|---|
| `containerColor` | **显式**传 `colorScheme.surface`，把「页面底色 = surface」钉在调用点 |
| 系统栏 inset | **只由** Scaffold 负责；任何 Tab、二级页内容**不得**再自己加 `navigationBarsPadding()` |
| 悬浮底栏让位 | 不用整体上推内容；用 `LocalDockInset` + 列表末尾 `Spacer(24.dp + inset)` |
| 液态玻璃底栏 | 必须放在 `content` 内做浮层，**不得**挂在 Scaffold 外面（外面的浮层盖不住 DialogHost） |
| Miuix 版本 | 锁 0.9.3，**不升** 0.9.4+；backdrop 锁 1.x（升级会连带 AGP 9 迁移） |

### 1.3 底色

- 页面底色**一律** `colorScheme.surface`，**不得**用 `background`。Miuix 里 `background` 与 Card 默认色 `surfaceContainer` 同色，用了卡片会整片消失。
- 同一底色值必须**五处一致**：第 0 层、根 Box、`Scaffold.containerColor`、`SubScreen` 的 Scaffold、`rememberLayerBackdrop` 底衬。
- 切换主题时的遮罩取 `surface`，不是 `background`（取错会闪一下）。

### 1.4 留白三分法（三种口径，别混）

| 位置 | 数值 | 说明 |
|---|---|---|
| 首屏顶部 | 一级页 `8.dp + LocalTopInset`（玻璃模式）／ 二三级 `8.dp` | 二级页首屏不再加 TopInset，它已经被一级页吃掉 |
| 组间（两个 SmallTitle 之间） | **不加 Spacer** | SmallTitle 自带上下内边距（28dp），再补就双倍 |
| 同组内两卡之间 | `Spacer(12.dp)` | 拆卡后必须补，否则两卡贴成一整块 |
| 列表末尾 | 一级 `24.dp + LocalDockInset`；二三级 `24.dp + LocalNavBarInset` | 让最后一张卡能滚出底栏／导航条 |
| 卡片左右 | `Modifier.cardPadding()` = 水平 12dp | Miuix Card 满宽，边距由调用方补 |
| 卡内行左内边距 | 16dp | 卡片 12 + 行 16 = 首屏内容左缘 28dp，全站对齐就靠这一条 |

**反例**：为了给搜索框加边距而在卡里再包一层 `Column(padding(...))`。搜索框自己在卡内只吃 4dp 水平（4 + 内建 12 = 16dp），包一层就会变成 16 + 12 = 28dp，与同卡其它行错位。

### 1.5 新增一个页面要动哪些文件（漏一步就白做）

| 步 | 二级页 | 三级页（多一步） |
|---|---|---|
| 1 | `Route` 加一条（含字符串 key） | 同左 |
| 2 | `RouteSaver` 的 `save` / `restore` 各加一支 | 同左 |
| 3 | `MainScreen` 的 `when` 加分支（穷尽，漏了编译失败） | 同左 |
| 4 | — | `editParent(route)` 登记父页，否则返回会跳回首页 |
| 5 | 入口行：`BasicComponent` + 状态副标题 + `ArrowRightIcon()` | 同左 |
| 6 | `FeatureCatalog` 的 `detailTitleRes` / `detailEntryRes` / `detailEntrySummaryRes` | 同上（若从功能页进） |
| 7 | 中英 `strings.xml` 各一条标题（副标题若为状态句则两条） | 同左 |
| 8 | 本仓 `docs/UI.md` 的文件职责表 + 页面清单 | 同左 |

---

## 2. 分组与间距

### 2.1 SmallTitle（分区标题）

- 一页由若干「`SmallTitle` + 一张或多张 `Card`」组成，这是唯一的页面组织方式。
- 分区名：**名词、2–5 字、不带动词、不带「设置」后缀**（「材质设置」是历史例外，新分区不再这么起）。
- 排序：**状态 / 开关在前，动作在最后**。动作类分区统一叫「操作」或「动作」，不叫「新建与清空」这种把两个动词拼起来的名字。
- 新增分区要同步登记：`FeatureCatalog` 顶部注释 + 本仓 `NAMING.md` 的分区名表。

### 2.2 Card

- 满宽 + `Modifier.cardPadding()`。
- 卡内**只放能点的行**。不写解释性注释行（要解释就写进那一行的副标题）。
- 卡内**一律不画** `HorizontalDivider` / `VerticalDivider`。要分层就拆卡 + 卡间 `Spacer(12.dp)`。
- `CardNote` 只允许承载**结果**与**空态**，不承载解释（见 9、10 章）。

### 2.3 数值表（照抄，不要自己发明）

| 量 | 值 | 出处 |
|---|---|---|
| 卡片水平外边距 | 12dp（`CardHMargin` / `cardPadding()`） | 两仓 `Widgets.kt` |
| 同组卡间距 | 12dp | 拆卡处 |
| 卡内行左内边距 | 16dp | 行组件内部 |
| 行尾图标触控区 | 40dp 圆 | `Widgets` / 行尾槽位 |
| 页内搜索框高 | 44dp | `AppSearchField` |
| 弹层小菜单宽 | 200dp | `AppDialogHost` |
| 贴底弹层最大宽 | 420dp | `AppDialogHost` |
| 状态卡最小高 | 148dp（WX 概览） | `ModuleTab` |
| Hero 高 | `0.60 × 屏高`，`coerceIn(300.dp, 560.dp)` | 关于页 |
| 主题切换遮罩时长 | 320ms（`ThemeFadeMillis`） | `SettingsActivity` |

---

## 3. 行型目录（最常查的一章）

### 3.0 决策树

```text
这一行要让用户做什么？
├─ 开 / 关一个功能        → 3.1 开关行
├─ 进另一个页面          → 3.2 入口行（行尾 ›）
├─ 原地展开子内容        → 3.3 展开行（行尾 ↓）
├─ 点了立刻执行一次      → 3.4 动作行（不画箭头）
├─ 从几个固定值里选一个  → 3.5 选择行（值进副标题 + ↓）
├─ 填一个数字 / 短文本   → 3.6 行内输入行
├─ 展示表里的一条（可删）→ 3.7 列表条目行（行尾 ✕）
├─ 新增一条              → 3.8 新建行（主色 + ＋）
└─ 只想说一句话          → 3.9 说明行（CardNote，只用于结果 / 空态）
```

### 3.1 开关行

- 组件：`GsSwitchPreference`（自绘，替换 Miuix Switch / SwitchPreference）。
- 副标题：**描述当前状态**，且必须按状态取不同字符串（`_on` / `_off`，第 7.6 节）。
- 开关为「功能总开关」时，打开后**才**在其下方展开「XX设置」入口（`expandVertically(220) + fadeIn(180)`；收起 `shrinkVertically(180) + fadeOut(120)`）。关闭时入口收起，页面上**不再**额外写提示语。
- 出厂默认一律关。

### 3.2 入口行

```kotlin
BasicComponent(
    title = stringResource(R.string.xxx_entry),
    summary = statusText,           // 状态句，见 7.3
    endActions = { ArrowRightIcon() },
    onClick = { onOpen(Route.Xxx) },
)
```

- 副标题**必须有**：这一行现在是什么状态（「白名单 · 3 条关键字」「1 条规则」「已选 3 个会话」）。没有状态的入口行是被允许的例外，但要能一句话说清为什么（例如「导入与导出」这类纯动作入口）。
- 箭头语义：`›` = 进二级页或推进式动作。

### 3.3 展开行

- 同 `BasicComponent`，`endActions = { DownArrowIcon() }`，点了在原地展开内容。
- 典型：记录页的行点击（原地弹选择层）、功能卡展开二级入口。

### 3.4 动作行

- **不画箭头**。点了立刻执行（刷新名单、从剪贴板导入、复制到剪贴板）。
- 结果走 Toast（第 7.5 节），不在页面上留一行「已复制」。
- 需要二次确认的动作（清空 / 重置）写成普通行 + 确认弹层，**不给标题上红**（第 8.2 节）。

### 3.5 选择行（离散取值）

- 形态：`BasicComponent`，**当前值进副标题**，行尾 `DownArrowIcon()`，点击弹 `ChoiceDialogSpec`（锚定在行上）。
- 适合：主题、语言、日志等级、列分隔符、匹配模式（二选一除外，见下）。
- **二选一不弹窗**：直接做成开关行（黑白名单就是这么做的 —— 开关位置一眼可读，比「点开才知道有两个选项」快）。
- **禁用项**用 `ChoiceDialogSpec(disabledIndices = ...)`，不要塞一个点了没反应的选项。

### 3.6 行内输入行

- 只用于**同一张卡里必须并排比较的少量数值**（例如盯盘价格页一组阈值）。
- 其余情况一律走第 4 章的「一行入口 + 弹层输入」。
- 页面上**不常驻**多行文本框。历史上重命名规则页就是一整块 260dp 文本框，规则长什么样、哪条错了全靠用户自己读 —— 已废弃。

### 3.7 列表条目行（可删的单条）

```kotlin
BasicComponent(
    title = item.title,      // 原名 / 关键字
    summary = item.detail,   // 新名 / 命中示例（可空）
    endActions = {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = deleteLabel) { onRemove() },
            contentAlignment = Alignment.CenterEnd) {
            TintedIcon(AppIcons.Close, MiuixTheme.colorScheme.onSurfaceVariantActions,
                contentDescription = deleteLabel)
        }
    },
)
```

- ✕ 用中性色 `onSurfaceVariantActions`，**不是红色**；不传 `Modifier.size()`（尺寸归图标自己声明）。
- 删除是**单条**直接删（可撤销/可重建），不做二次确认；**整批**清空才走确认弹层。
- 空表退化成一行占位（`xxx_empty`），保留「新建」入口，不要连卡片一起消失。

### 3.8 新建行

- 标题与 ＋ 同用 `colorScheme.primary`，行尾 `AppIcons.PlusThin`（**不要**用 `AppIcons.Add`，那是 24dp 网格填充十字，与 10dp 细描边 ✕ 不同画布）。
- 点击弹 `TextInputDialogSpec`，校验拦在弹层里（空串 / 格式 / 重名）。

### 3.9 说明行（CardNote）

- 位置：卡内最后一行，`padding(horizontal = 16.dp, vertical = 8.dp)`，13sp，`onSurfaceVariantSummary`。
- **只允许两种内容**：一次性结果（「备份已保存到…」）、空态（「还没有规则」）。
- 解释性文字不在这里 —— 写进行副标题。

### 3.10 行型速查

| 想做 | 组件 | 行尾 | 副标题 | 点击行为 |
|---|---|---|---|---|
| 开关 | `GsSwitchPreference` | 开关 | 状态句（按状态变字符串） | 立即落盘 |
| 进页面 | `BasicComponent` | `›` | 状态句 | 进路由 |
| 原地展开 | `BasicComponent` | `↓` | 可选 | 展开 |
| 立即执行 | `BasicComponent` | 无 | 可选 | 执行 + Toast |
| 选一个值 | `BasicComponent` | `↓` | 当前值 | `ChoiceDialogSpec` |
| 填短值 | `BasicComponent` + 弹层 | `›` | 当前值 | `TextInputDialogSpec` |
| 列表条目 | `BasicComponent` | `✕` | 说明（可空） | 无 / 进详情 |
| 新建 | `BasicComponent`（primary） | `＋` | 可选 | `TextInputDialogSpec` |
| 说明 / 结果 | `CardNote` | 无 | — | 无 |

---

## 4. 可编辑字段：只有三种形态

### 4.1 三形态

| 形态 | 用于 | 组件 | 落配置时机 |
|---|---|---|---|
| **行内选择器** | 离散取值（模式 / 主题 / 分隔符 / 等级） | `BasicComponent` + `ChoiceDialogSpec` | 收起动画之后 |
| **一行入口 + 弹层输入** | 需要打字的任何值（单个文字、多行规则表的一次新增、数字） | `BasicComponent` + `TextInputDialogSpec` | 收起动画之后 |
| **页内搜索** | 在已有列表里筛（不落配置） | `AppSearchField` | 只 `remember`，不持久化 |

**硬约束**：
- 页面上**不常驻** `TextField`（唯一例外是搜索框）。常驻输入框会把页面撑高，且「框里的字」与「已保存的字」是两回事。
- 需要打字的值**一律**「一行入口 + 弹层」，清空走二次确认。
- 页内搜索**只** `remember`，下次进来是干净的全量视图。

### 4.2 页内搜索框 AppSearchField（唯一实现）

| 参数 | 规定 |
|---|---|
| 高度 | 固定 44dp |
| 圆角 | 12dp |
| 底色 | `surfaceContainerHighest`，非聚焦 `alpha 0.9`、聚焦 `1.0`，**无描边** |
| 壳内左内边距 | 12dp（放进卡里时外层只给 4dp，4 + 12 = 16dp 与同卡其它行同轴） |
| 字号 | 15sp |
| 光标 | **必须**传 `cursorBrush = SolidColor(primary)`（不传在深色下是纯黑，看不见） |
| 清空键 | 非空时右端 40dp 圆形热区 + `AppIcons.Close`（中性色），`onClickLabel` = 「清空搜索」 |
| 键盘 | `singleLine` + `ImeAction.Search`，回车收键盘 |
| 无障碍 | `contentDescription` = 占位文案 |

**不要**：
- 把 `AppSearchField` 塞进弹层（会套两层底、字号打架）。弹层输入是另一种形态，见 4.3。
- 为加边距再包一层 `Column` / `Row`。
- 每个页面各写一份搜索框（历史上三页三套：整宽无框 / 带外框 / 带图标，已统一）。

### 4.3 弹层输入框（TextInputDialogSpec）

**能力面（两仓合并后的唯一口径）**：

| 参数 | 说明 |
|---|---|
| `title` | 一句祈使或名词（「新建规则」「页脚文字」） |
| `message` | 补充说明，**空串不占行**（用 `isNotBlank()` 判，不要 `isNotEmpty()`） |
| `hint` | 输入框占位 |
| `initial` | 初值（编辑既有值时传当前值） |
| `confirmText` / `cancelText` | 取消传空串时**不画**取消按钮（纯说明 / 单按钮场景） |
| `numeric` | 数字键盘 |
| `maxChars` | 长度上限，默认 120（数字场景可设 3） |
| `validate` | 返回非 null 文案时弹层**不关**，红字显示在输入框下方；改一个字符自动撤红字 |
| `onConfirm` | 校验通过后回调，配置写入排在收起动画之后 |

**交互规定**：
- 弹层一出来**自动聚焦并拉起键盘**（`withFrameNanos` 后 `requestFocus`）。
- 键盘弹出时贴底卡片**跟着抬到键盘之上**（`sheetBottomPx = maxOf(navBottomPx, imeBottomPx)`）。
- 输入框是真 `TextField`（长按粘贴等系统行为都在），不是自绘只读框。
- 校验红字：13sp + `colorScheme.error` + `padding(top = 8.dp)`。

### 4.4 清空与删除

| 场景 | 做法 |
|---|---|
| 删一条（列表项） | 行尾 ✕ 直接删，可重建 |
| 清空整批 | 独立一行「清空 X」→ `ConfirmDialogSpec`，message 写清「清空后两种模式各自会怎样」 |
| 清一个有内容的值 | 行尾 ✕ → 确认弹层 |

---

## 5. 弹层

### 5.1 选型表（先查表再写代码）

| 要弹的东西 | spec | 锚点 | 按钮 |
|---|---|---|---|
| 从几个固定值里选一个 | `ChoiceDialogSpec` | **必须**传 `anchor`（贴着触发行展开） | 取消 + 确定；当前项打勾；纯动作菜单写 `selectedIndex = -1` |
| 确认一件有后果的事 | `ConfirmDialogSpec` | **恒为 `null`**（贴底） | 取消 + 确定；`cancelText` 传空串则只画一颗 |
| 输入文字 / 数字 | `TextInputDialogSpec` | 贴底 | 取消 + 确定 |
| 看一张图 | `ImageDialogSpec` | 恒 `null` | 关闭 |
| 必须读的协议 | `AgreementDialogSpec` | 恒 `null` | 不可关闭，返回键**只吞不放行** |

**一律用自绘 `DialogHost`**，不用 Miuix 的 `OverlayDropdownPreference` / `OverlayDialog`。

### 5.2 尺寸与位置

| 项 | 值 |
|---|---|
| 锚定小菜单宽 | 200dp，右缘与触发控件对齐 |
| 贴底 sheet 宽 | ≤ 420dp，水平居中 |
| 贴底位置 | 抬到导航条之上；键盘出现时抬到键盘之上 |
| 进出动画 | 菜单缩放进出；sheet 上滑下滑 |

### 5.3 时机（最容易写错的一条）

```kotlin
controller.dismiss { spec.onSelect(index) }   // 对：动作排在收起动画之后
spec.onSelect(index); controller.dismiss()     // 错：动画期间落配置，会看到内容先变后缩
```

- `spec` 必须保留到退出动画播完（`closing → 播完 → finishDismiss` 才置空），否则渲染成空壳。
- 每个控件的锚点**各自** `remember` 一份，**不得**用共享变量接所有控件的 `boundsInWindow`。

### 5.4 文案

- 标题：一句祈使或名词；不要写「提示」。
- message：写清**范围与后果**（「清空后白名单会放行全部，黑名单会全部照常发」），不写「确定要继续吗」。
- 按钮：`取消` / `确定`（或动词化：`清空` / `删除`）。

### 5.5 校验

- 校验**一律**在弹层里，不关弹层、不 Toast。
- 四类常见非法输入都要挡：空串、格式不对、重名、超长。
- 文案要能指导下一步（「格式不对，写成「原名 → 新名」」而不是「输入有误」）。

---

## 6. 图标

### 6.1 画布与几何

| 图标 | 用途 | 尺寸 | 画法 |
|---|---|---|---|
| `AppIcons.Close` | 删一项 | 10 × 10.7dp | 描边叉，与箭头同粗 |
| `AppIcons.PlusThin` | 新建 | 10 × 10.7dp | 描边十字，1.4dp 笔画 |
| `AppIcons.ArrowRight` | 进页面 | 10 × 16dp | 描边箭头 |
| `AppIcons.ArrowDown` | 原地展开 | 16 × 10dp | 描边箭头 |
| `AppIcons.Add` | — | **禁用** | 24dp 网格填充十字，与行尾其它图标不同画布 |

**硬约束**：
- 同一张卡里并列的行尾图标，必须**同画布、同笔画粗细**。
- **不要**给行尾图标传 `Modifier.size()` —— 尺寸归图标自己声明。
- 新增图标**只走** `ui/AppIcons.kt`，不引 `material-icons-extended`。
- path 命令白名单：`M L H V C S Q T Z`（贝塞尔近似圆，不写圆弧命令）。
- 箭头一律自绘（Miuix 自带箭头的染色不生效）。

### 6.2 箭头三档语义（选型判据）

| 箭头 | 含义 | 例子 |
|---|---|---|
| `↓` | 点了**原地**出东西（弹层 / 展开） | 选择模式、展开子项、记录行弹详情 |
| `›` | 进**另一个页面**，或推进式动作 | 发送设置 → 黑白名单 |
| 不画 | 点了**立刻执行** | 刷新名单、从剪贴板导入 |

反例：原地展开却画 `›`，用户会以为要跳页；跳页却画 `↓`，用户点了等着原地出东西。

### 6.3 着色

| token | 用途 |
|---|---|
| `onSurfaceVariantActions` | 行尾中性图标（✕ / › / ⋮） |
| `primary` | 新建 / 添加行（＋ 与标题同色）、选中态、开关、光标、胶囊底 |
| `error` | 仅两处：弹层校验红字、状态色「未激活」。**不用于** ✕、不给危险动作标题上红 |
| `disabledOnSecondaryVariant` | 禁用态行尾图标 |

---

## 7. 文案

### 7.1 分区名

- 名词、2–5 字、不带动词、不带「设置」后缀。
- 状态 / 开关在前，动作最后。

### 7.2 行标题

- 名词或动宾短语，**不写句子**。
- 同一对象在全 App 用同一个词（`NAMING.md` 有词表）。

### 7.3 副标题的两类模板

| 类型 | 说清什么 | 模板 | 例子 |
|---|---|---|---|
| **位置类** | 这件事塞到哪 / 影响谁 | 「作用域 + 对象」 | 「作用于自选页复制按钮」 |
| **状态类** | 此刻是什么状态 | 「值 · 计数」或「值 · 补充」 | 「白名单 · 3 条关键字」「1 条规则」「已选 3 个会话」 |

- 长度：中文 ≤ 20 字、英文 ≤ 60 字符。
- 不写机制与后果（「开启后会自动…」），不写「A 自动 B」句式。
- 状态类必须随状态变字符串（7.6）。

### 7.4 空态句式

- 统一「还没有 X」+ 一句「为什么为空 / 该怎么办」。
- **禁止**只写「无」「暂无数据」。
- 必须成对提供 `empty_body`（本来就没有）与 `empty_search`（被搜索筛掉），共用一句就是缺陷。
- 空态里允许出现的唯一动作是**出口**（清除筛选 / 从微信读取），不放新建按钮。

### 7.5 Toast 三档

| 档 | 模板 | 例子 |
|---|---|---|
| 成功 | 已 <动作> <对象> | 已保存、已清空关键字 |
| 部分 | 已 <动作> <对象>（<缺什么>） | 已导入 3 条，跳过 2 条重复 |
| 失败 | <动作>失败：<原因> | 导入失败：文件不是文本 |

- 时长一律 `LENGTH_SHORT`，禁自定义时长。
- 同一结果不重复播报（Toast 与卡片注脚二选一，第 10 章）。

### 7.6 状态字符串命名

| 态数 | 命名 | 例 |
|---|---|---|
| 2 | `<item>_summary_on` / `_off` | `item_glass_effect_summary_on` |
| 3+ | `<item>_summary_<state>` | `filter_summary_whitelist` |
| 默认态 | **必须**有独立资源，不复用标题 | — |

### 7.7 中英同步

- 中英 `strings.xml` **同时**改，缺一即视为未完成。
- 英文值里的撇号要转义（`\'`），否则编译失败。

---
## 8. 颜色与语义

### 8.1 token 表

| 用途 | token | 说明 |
|---|---|---|
| 页面底色 | `colorScheme.surface` | 五处一致（第 1.3 节） |
| 卡片底 | `surfaceContainer` | Miuix Card 默认，不要自己覆写 |
| 输入框底 | `surfaceContainerHighest`（0.9 / 聚焦 1.0） | 搜索框 |
| 主文字 | `onSurface` | 行标题 |
| 次文字 | `onSurfaceVariantSummary` | 副标题、CardNote、13sp 说明 |
| 行尾中性图标 | `onSurfaceVariantActions` | ✕ / › / ⋮ |
| 强调 / 新建 | `primary` | ＋、选中、开关、光标 |
| 错误 | `error` | 仅弹层校验红字 + 「未激活」状态色 |
| 禁用图标 | `disabledOnSecondaryVariant` | — |

### 8.2 危险动作三档（只有一档允许用红）

| 档 | 例 | 着色 | 防线 |
|---|---|---|---|
| **批毁** | 清空关键字、重置模块、清空发送记录 | 行标题可用 `error`（三档里**唯一**允许的一处） | 必走 `ConfirmDialogSpec`，message 写范围 + 后果 |
| **单条删** | 删一条规则 / 一条关键字 | 中性 `onSurfaceVariantActions` | 不加确认，可重建 |
| **可撤销** | 关开关 | 不改色 | 无 |

补充：已经落地的做法是「连红都不用」—— 重置行走普通行样式，**靠确认弹层与副标题说后果**（「重置 = 关掉全部功能开关」）。这条比上红更稳，新增动作优先照这个来；确实需要视觉预警时，红只出现在弹层确认按钮上，不出现在行里。

### 8.3 液态玻璃的适用面（GS）

- 液态玻璃是**总开关**，一次决定顶栏 / 底栏 / 弹窗三处材质。
- 光效**仅**玻璃开启时内嵌显示。
- **贴边表面不放 rim light**；`layerBackdrop` 这类每帧采样的修饰符必须条件挂载。
- 关闭玻璃时退回 Miuix 官方 `NavigationBar`，两种形态的**条目与顺序必须一致**。

---

## 9. 空态与搜索

### 9.1 三件套（缺一不可）

| 件 | 要求 |
|---|---|
| 可见性 | 页面上要能看见「命中几条」或「一条都没有」 |
| 可区分 | 「本来没有数据」与「被搜索筛掉」是两句不同文案 |
| 有出口 | 留一条退出搜索的路径（清空搜索 / 清除筛选） |

### 9.2 句式

- 无数据：`还没有 <对象>` + 一句怎么办（「点下面的新建关键字加一条」）。
- 搜索无结果：`没有符合条件的 <对象>，换个关键词。`
- 禁止：「无」「暂无数据」「NULL」。

### 9.3 形态

- 卡内空表：退化成一行占位（`BasicComponent` 禁用态，或 `CardNote`），**保留其他行**（新建入口不能一起消失）。
- 整页空：独立空态块（图 + 一句话），不放新建按钮。
- 空态不是「注释行」，允许占一行 —— 与第 2.2 节「卡内不写解释」不冲突，区别在于：空态说的是**结果**，注释说的是**道理**。

---

## 10. 反馈放哪：三选一

| 场景 | 放哪 | 形态 |
|---|---|---|
| **进行中**（导出 / 恢复 / 刷新） | 行内 | 入口置灰 + 副标题「正在导出…」 |
| **一次性结果**（保存 / 清空 / 复制 / 新建） | Toast | 第 7.5 节三档 |
| **需要停留查看**（备份路径、错误详情） | 卡内注脚 `CardNote` | 13sp + 次文字色 |

**禁止**：同一结果既 Toast 又在卡里留一行；把校验错误做成 Toast（校验归属弹层，就地红字）。

---

## 11. 无障碍最小集

| 项 | 规范 |
|---|---|
| 可点图标 | 必须给 `contentDescription`，且**同时**给 `clickable` 写 `onClickLabel`（读屏才念得出一句完整动作） |
| 纯装饰图标 | `clearAndSetSemantics {}`（例：高度尺上的文字，否则读屏重复念标题） |
| 触控目标 | ≥ 40dp（行尾图标一律 40dp 圆热区）；开关整行可点 |
| 弹层焦点 | 打开即聚焦到输入框（或首个可操作项） |
| 状态变化 | 「导出中 / 刷新中」的时序要能被读屏读到（文字而非纯色变化） |

---

## 12. 动画

| 场景 | 参数 |
|---|---|
| 功能开关展开二级入口 | `expandVertically(220) + fadeIn(180)`；收起 `shrinkVertically(180) + fadeOut(120)` |
| 板块切换（Tab 之间） | `AnimatedContent` 淡入 + 横移，位移**只有**屏宽 1/8，不整页推走 |
| 二级页与三级页滑入 | 两条独立进度；二级页缩到 0.94 时与一级页同尺寸 |
| 主题切换 | `ThemeFadeMillis = 320`，遮罩常驻 + `snapTo` 先行 + 等一帧 |
| 弹层出现 / 消失 | 菜单缩放；sheet 上滑下滑；动作排在消失之后 |

**禁止**：顺手改动画参数（曲线与时长是逐次调出来的）；把缩放挂在第 2 层外层（会连三级页一起缩）。

---

## 13. 提交前自检（照着勾）

| # | 检查项 |
|---|---|
| 1 | 新页面按第 1.5 节八步全做完（Route / Saver / when / editParent / 入口行 / FeatureCatalog / 中英 strings / 文档） |
| 2 | 页面底色五处都是 `surface`，没有一处用 `background` |
| 3 | 每张卡都套了 `Modifier.cardPadding()` |
| 4 | 同组两卡之间补了 `Spacer(12.dp)`；跨组没有多补 |
| 5 | 卡内没有 `HorizontalDivider` / `VerticalDivider` |
| 6 | 卡内没有解释性文字（语义已进副标题） |
| 7 | 每个入口行都有状态副标题 |
| 8 | 开关副标题按状态取不同字符串（`_on` / `_off`） |
| 9 | 页面里没有常驻 `TextField`（搜索框除外） |
| 10 | 需要打字的值走的是「行入口 + 弹层」 |
| 11 | 弹层选了对的 spec（第 5.1 节），`Choice` 传了 anchor、其余贴底 |
| 12 | 配置写入在 `dismiss { }` 之后 |
| 13 | 每个控件的锚点各自 `remember` |
| 14 | 行尾图标 40dp 热区、无 `Modifier.size()`、同卡同画布 |
| 15 | 箭头三档语义正确（↓ / › / 无） |
| 16 | ✕ 不是红色，危险动作走确认弹层 |
| 17 | 空态成对（`empty_body` / `empty_search`），有出口 |
| 18 | 搜索框用的是 `AppSearchField`，没在页面里手写 `BasicTextField` |
| 19 | 中英 `strings.xml` 同步，英文撇号已转义 |
| 20 | **真机看过一遍**（旋转、深浅色、键盘弹出、长文案） |

---

## 14. 附录：两仓页面清单与专有形态

### 14.1 GSAuxiliary（15 页）

| 层 | 页面 | 文件 |
|---|---|---|
| 一级 | 概览 / 功能 / 设置 / 关于 | `ModuleTab.kt` / `FeatureTab.kt` / `SettingsTab.kt` / `AboutTab.kt` |
| 二级 | 自选股高亮 | `WatchlistDetail.kt` |
| 二级 | 策略页直达个股 | `StrategyQuoteDetail.kt` |
| 二级 | 一键配置方案列表 | `QuickConfigScreen.kt` |
| 三级 | 一键配置方案编辑 | `QuickConfigEditScreen.kt` |
| 二级 | 一键配置按钮设置 | `AiStareSettingsScreens.kt` |
| 二级 | 保存行为 | `AiStareSettingsScreens.kt` |
| 二级 | 复制规则列表 | `ZxCopyRuleScreen.kt` |
| 三级 | 复制规则编辑 | `ZxCopyRuleEditScreen.kt` |
| 二级 | 复制按钮设置 | `ZxCopyButtonScreen.kt` |
| 二级 | 免责声明 / 公告 | `AboutTab.kt` |

**GS 专有事实**：
- 开关自绘：`TrackSize = DpSize(49.dp, 28.dp)`、`ThumbSize = 20.dp`、`ThumbOffsetOn = 25.dp`、`DragRange = 21.dp`、按下缩放 `1.127f`；开关行 `endActions` 内边距 `end = 8.dp` 且 `align(CenterVertically)`。
- 图标一律 `TintedIcon`（GraphicsLayer colorFilter）—— 本机 `Icon(imageVector, tint = ...)` 的 tint 不生效，这是绕法。
- `endActions` 默认 Top 对齐，一行有多个行尾控件时必须包 `Row(align(CenterVertically))`；需要 ⋮ / ↓ / › 同宽同中线时用 `EndIconSlot = Box(size(40.dp), center)`。
- 编辑页被删时用 `lastXxx` 保底 + `Spacer(24.dp)` 顶一下再 `onDeleted()`，不黑屏。
- 公告页：`NOTICE.txt` 为空时整卡不渲染（唯一「真空白」）。
- 设置页五组固定顺序：界面 / 材质设置 / 通用 / 数据管理 / 日志。
- 备份、恢复、导出日志走 SAF，整段 IO 在 `Dispatchers.IO`，期间入口置灰。
- 配置文件只认 `ALL_KEYS` 白名单，多出来的字段一律忽略；格式版本高于支持直接拒绝。

### 14.2 WxPicLabe（12 页）

| 层 | 页面 | 文件 |
|---|---|---|
| 一级外壳 | MainScreen / HomeScaffold | `Screens.kt` / `Routing.kt` |
| 一级 | 概览 / 功能 / 设置 / 关于 | `ModuleTab.kt` / `FeatureTab.kt` / `SettingsTab.kt` / `AboutTab.kt` |
| 二级 | 发送设置 | `ImgLabelSendScreen.kt` |
| 二级 | 选择会话 | `ScopePickerScreen.kt` |
| 二级 | 重命名规则 | `ImgLabelRenameScreen.kt` |
| 二级 | 黑白名单 | `ImgLabelFilterScreen.kt` |
| 二级 | 发送记录 | `ImgLabelRecordScreen.kt` |
| 二级 | 免责声明 / 第三方组件 | `DisclaimerScreen.kt` / `NoticeScreen.kt` |

**WX 专有形态**：
- 发送设置页六区：发送范围（开关 + 名单入口）／发送提示（两开关）／重复图片（开关）／重命名（入口 + 「N 条规则」）／文字过滤（入口 + 「黑名单 · N 条关键字」）／记录（入口）。
- 黑白名单页三节：名单（开关行，二选一不做下拉）／匹配方式（两开关）／关键字（两张卡：列表卡 + 动作卡，间隔 12dp）；即改即生效、无保存按钮。
- 重命名规则页三节：规则（逐条 原名 → 新名，行尾 ✕）／新建与清空（两张卡）／导入与导出；新建的四类非法输入全部挡在弹层里。
- 发送记录页五段：概览卡／搜索与筛选卡／只读提示卡／按时间段分组／操作卡；筛选态下**全部命中组自动展开**。
- 选择会话页：已选 N 卡（点击展开名单）+ 刷新名单卡 + 搜索框 + 分组列表（群聊 / 好友各带全选）；返回目标是发送设置。
- `AppSearchField` 是本仓唯一搜索实现，被记录页与选择会话页共用。

### 14.3 两仓共用但容易漏的接线

| 项 | 位置 |
|---|---|
| 功能开关运行期注册 | `FeatureManager.features`（并需在 `FeatureCatalog.FEATURE_CATALOG` 登记展示位） |
| 配置键四件套 | `ConfigKeys` 的 `ALL_KEYS` / `FEATURE_PARAM_KEYS` / `SNAPSHOT_KEYS` / `DEFAULTS` 与 `FEATURE_KEYS`（总开关才进 FEATURE_KEYS） |
| 备份 / 日志文件名前缀 | 每个模块一个前缀常量，只在一处定义（不做字面量散落） |

---

## 15. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-10 | 首版：从 GSAuxiliary（15 页）与 WxPicLabe（12 页）现有实现反向提炼，收敛 129 条既有规则、18 条两仓冲突、14 条缺口 |

---

## 附：与旧文档的关系

| 旧文档 | 现在的定位 |
|---|---|
| `docs/UI.md` | 本仓页面清单与文件职责；跨仓共性条款以本文件为准，重复的部分逐步删除 |
| `docs/UI-MATERIAL.md` | 材质与弹层**内部实现**（DialogHost 绘制、玻璃三档、组件清单），不重复设计取舍 |
| `docs/UI-HISTORY.md` | 踩坑史与历史决策（Miuix 锁版本、系统栏、体积），规范正文引用它而不复制它 |
| `docs/NAMING.md` | 文案词表与命名规则，第 7 章引用它 |