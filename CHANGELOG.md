# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
版本号在 `app/build.gradle.kts` 的 `versionName` / `versionCode` 中维护。

---

## [2.12.1] — 2026-10-15

热重载的反馈闭环：**点一次必有一条提示**（含成功）。

### 变更

- **「热重载目标进程」点击后必定给出结果提示**（`ui/SettingsTab.kt` + 中英 strings）。
  - 症状：目标进程**在后台**（或一切正常）时点「热重载目标进程」，屏幕上**毫无动静**，
    无法判断是否重载成功、是否需要重试。
  - 根因：`requestHotReload()` 返回 `String?`，设计上**只有**「没在跑目标进程」这一种情况
    返回文案，成功 / 失败一律 `null` —— 调用方 `?.let { Toast }` 直接跳过。
    （该简化出自 2.8.0，当时的选择是「成功 / 失败不打扰」，实际代价是唯一的正向反馈也一并没了。）
  - 改法：`requestHotReload()` 改为返回**非空** `String`，调用方无条件弹 Toast；
    文案覆盖 `HotReloadResult.Status` 的全部五种终态，另加一档本地「8 秒无回调」超时，
    以及「服务掉线」（`FrameworkBridge.service()` 为 null，点击瞬间的兜底）与原有的「无目标进程」：

    | 触发 | 文案 |
    |---|---|
    | `SUCCEEDED` | 已热重载 |
    | `FAILED` | 热重载失败，详见模块日志 |
    | `UNSUPPORTED` | 当前框架不支持热重载 |
    | `IN_PROGRESS` | 目标进程正在热重载中，请稍候 |
    | `PROCESS_DIED` | 目标进程已退出 |
    | `latch.await` 超时 | 热重载超时，目标进程可能无响应 |
    | `runningTargets` 为空 | 没有正在运行的目标程序 |
    | 服务为 null | 需要框架连接 |

    新增 6 条字符串（`msg_hot_reload_ok` / `_failed` / `_unsupported` / `_in_progress` /
    `_died` / `_timeout`），中英同步。
  - 顺带：回调结果改由 `AtomicReference<HotReloadResult?>` 承接，`latch.await()` 的返回值
    不再被丢弃 —— 超时与「await 成功但无结果」都是显式分支，不再静默。
- `docs/UI.md` 设置页表格、`docs/TROUBLESHOOTING.md` §8 热重载排查表同步为「看 Toast 定位问题」。
- `app/build.gradle.kts`：`versionCode 31 → 32`、`versionName 2.12.0 → 2.12.1`。

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**；badging 2.12.1 / vc32。
- 状态枚举取值以 libxposed service 102 的 `HotReloadResult.Status` 为准（`javap` 常量池
  实测 `SUCCEEDED / FAILED / UNSUPPORTED / IN_PROGRESS / PROCESS_DIED`），未臆造分支。
- ⚠️ **真机回归未做**：需在「金太阳在后台」与「金太阳未运行」两种状态下各点一次，
  确认分别弹「已热重载」与「没有正在运行的目标程序」。当前目标 App 登录态未恢复，无法进策略页。

---

## [2.12.0] — 2026-10-15

三项策略详情页改动的落地（二次点击闪退修复 / 黄名返回实时刷新 / 多选栏「全选」按钮）。

### 变更

- **修复：策略详情页「直达标准行情」二次点击闪退**（`feature/strategyquote/StgDirectQuoteFeature.kt`）。
  - 症状：首次点股票正常直达个股详情页；返回后**第二次**点任意股票，闪退回「我的策略」列表。
  - 根因（真机 `logcat -b crash` 确认）：`rewriteExtras()` 对 `adapter_key.stockList` 做**原地**
    `clear() + addAll()`，而这个 ArrayList 与宿主 `AIStkStgDetailAct` 列表适配器（`AIStkAdapter`）
    **是同一个对象**。首次点击后适配器数据被换成 `IntentStockInfo`，第二次点击宿主按 `StockInfo`
    强转 → `ClassCastException: IntentStockInfo cannot be cast to StockInfo`（`AIStkStgDetailAct.onItemClick:196`）→ 页面崩溃。
  - 修法：构造**新列表**并写进 **Bundle 副本**（`Bundle(bundle)`），**绝不改写宿主持有的集合**；
    存储方式 `Parcelable` 优先、`Serializable` 兜底。顺带修正「关闭左右滑动」时的单只契约
    （改走行情侧 `adapter_key.stockInfo`，并清掉残留的策略侧 `stockList`）。
- **修复：加入自选后返回策略详情页，黄名不实时刷新**（`feature/watchlist/WatchlistFeature.kt` + `WatchlistTargets.kt`）。
  - 根因：返回时配置指纹未变化 → `observeConfig` 直接 `return`；且行视图已存在、`getView` 不再被调用，
    后置着色钩子根本没机会跑。
  - 修法：`onResume` 时对策略详情页**无条件强制重绘可见行**（遍历 `#lvStks` 的可见子 View，重新判定 + `setTextColor`），
    正确处理 ListView 的 header 位置偏移与 `HeaderViewListAdapter` 拆包。「移除自选变回原色」「开关关闭还原原色」
    由同一条逻辑覆盖；不调用 `notifyDataSetChanged`，避免 computing-layout 崩溃。
- **新增：策略详情页多选栏「全选 / 反选」按钮**（`feature/strategyselect/*` + `core/*` + `ui/FeatureCatalog.kt` + 中英 strings）。
  - 实现方式：**LSPosed 运行时视图注入**，在「取消」按钮左侧插入一颗**从「取消」克隆**的同款按钮
    （背景含状态、字色、字号、字体、内边距、对齐、行数、最小尺寸、LayoutParams 逐项复制），
    并给它一个**独立的 `generateViewId()` id**，避免与「取消」的 id 混淆。
  - **文案随状态切换**：未全选显示「全选」（点击勾选全部），已全选显示「反选」（点击取消全部）。
    重算文案的时机：进入多选态、每次行点击（包裹宿主 `OnItemClickListener`）、按钮点击后。
  - **与「取消」的间距**：只在注入按钮自己的 `rightMargin` 上加 **16dp**，**不改「取消」任何属性**
    （保证「与取消样式一致」这条验收仍成立）；并用 `paint` 量文字设 `minWidth`，避免切字抖动 / 挤走「取消」。
  - 触发时机：给 `#tv_stg_add_zxg` 挂 `TextWatcher`，观察其文案在「勾选加入自选股 ↔ 取消」之间切换
    —— **不**全局 Hook 超热路径 `TextView.setText`（行情页实时报价会频繁调用）。
  - 全选 / 反选动作：逐行调用 `ListView.performItemClick(...)`，走宿主自己的行点击监听器
    —— 与用户手动点行**完全同一条**代码路径，宿主选中模型与底部「已选 N」自然同步，无需知道内部字段。
    逐行必须**分帧**（`postDelayed` 一行一帧）：宿主每次切换后会刷新列表，同一帧继续拿旧子 View 会失效
    （否则「反选」只会清掉第一行 —— 真机实测踩到过）。
  - 新增配置键 `stg_select_all`（功能总开关，默认关，登记进 `ConfigKeys.FEATURE_KEYS`）。
- `app/DebugConfigProvider.kt` 增加 `stg_direct_quote` / `stg_quote_swipe` / `stg_select_all` 三列，
  供开发期脚本化开关功能（原先只能开关 `enabled` 等四项）。
- `app/build.gradle.kts`：`versionCode 30 → 31`、`versionName 2.11.2 → 2.12.0`。

### 验证结果

真机：魅族 20 / Android 16 (SDK 36) / LSPosed 2.2.1 / 国信金太阳 8.6.0 (vc271)。

- **构建**：`:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**；badging 2.12.0 / vc31；
  release dex `Lio/github/libxposed/api` 定义类 **0**；manifest 无任何 `xposed*` meta-data（四条红线均未触碰）。
- **任务 #1（要求连续点击 ≥5 次）**：策略详情页连续点 6 只不同股票，**每次都直达 `StockFragAct`**；
  `logcat -b crash` 中 `ClassCastException` 计数 **0**。修复前同操作**必然在第 2 次崩溃**
  （已用 crash 栈坐实根因：`AIStkStgDetailAct.onItemClick:196`）。
- **任务 #2（黄名返回实时刷新）**：
  - 把一个**不在自选**的股票（中恒集团 600252）在个股详情页「+自选」加入 → 返回策略详情页，
    `tools/verify.py` 判定其名称由 `#202030` 变为**高亮 `#F88000`**（**无需**退出重进）。
  - 再「删除自选」移除 → 返回策略详情页，名称**还原为 `#202030`**。
  - 全程凭 `onResume` 强制重绘生效，未调用 `notifyDataSetChanged`。
- **任务 #3（多选栏全选 / 反选按钮）**：
  - 注入的按钮与「取消」**同高同宽**（`[771,664,885,732]` vs `[927,664,1041,732]`，均 114×68），
    位于「取消」**左侧**，两按钮间距 42px ≈ **16dp**。
  - 点「全选」→ 全部勾选（真机 8/8、9/9、10/10 三例），文案切「**反选**」，底部「已选 N 只股票」同步；
    再点「反选」→ 全部取消（**0/9**），文案回到「全选」。
  - 反复进出多选态 2 次，注入按钮始终**只有一个**（无重复注入）；退出多选态自动移除；
    关闭开关后按钮**立即消失**（配置变更即时生效）。
  - 设置界面「功能」页「策略页增强」分区出现「启用「全选」按钮」开关（无二级入口 —— 该功能没有可调参数）。
- **回归**：直达标准行情（点股仍直达 `StockFragAct`）与自选高亮（`tools/verify.py` 仍正确着色）均正常。
- 备注：验证期间目标 App 登录态一度短暂失效（跳 `com.guosen.loginmodule.ui.LoginNewAct`），
  等待后自愈；全程**未做任何登录/身份认证操作**（遵守项目红线）。

---

## [2.11.2] — 2026-10-15

功能菜单里的功能开关改为**默认全部关闭**；删掉二级菜单里与总开关重复的「直达」开关。

### 变更

- **二级菜单里与一级总开关重复的「直达」开关已删除**（`ui/StrategyQuoteDetail.kt`）。
  - 症状：总开关 `STG_DIRECT_QUOTE` 在**两层页面各画了一遍** —— 一级页面的
    「启用直达标准行情」和二级菜单「直达设置」里的「直达标准行情」是同一个配置键。
    用户在二级菜单里关掉它，一级页面的「XX设置」入口会**当场收起**，
    人被从二级菜单里"弹"回一级，看着像页面崩了；两级状态还得处处同步，漏刷一处就上下对不上。
  - 改法：删掉二级菜单里的那一项与它所在的「直达」分组。
    该入口本来就只在总开关打开后才展开，所以能进来时「直达」必然是开的 ——
    那个开关在这里关不掉任何有用的东西。删掉后开关只出现在它该在的那一层。
  - 「左右滑动」的 `enabled = direct` 保护**保留**：正常路径下它恒为 `true`，
    留着是兜住非常规路径（进程重建后路由被恢复、而配置已被重置）——
    那时「不直达就没有个股详情页可谈滑动」，置灰比可点更诚实。
  - 文案随之调整（中英同步）：二级页标题 / 入口名 `直达设置` → **`滑动设置`**，
    入口说明 `是否直达、是否支持左右滑动` → `是否支持左右滑动换股`；
    删除已无引用的 `section_stg_direct`、`item_stg_direct_quote` 及其 `_on/_off` 共 4 条。
- **功能开关的出厂默认由「开」改为「关」**（`core/ConfigKeys.kt`）。
  - 症状：装完模块第一次打开设置，功能菜单里两个功能（自选股高亮、策略详情直达行情）
    都是**默认开启**的 —— 用户还没看过、没勾选，模块就已经在改目标 App 的行为了。
  - 改法：`DEFAULT_ENABLED: true → false`、`DEFAULT_STG_DIRECT_QUOTE: true → false`。
    两个开关是全项目默认值的**唯一来源**，`ConfigSource`（目标进程读取）、
    `ConfigSnapshot.DEFAULT`、`ui/FeatureCatalog` 的 `defaultEnabled`、
    `DebugConfigProvider` 全部经它取值，改一处即全局生效。
  - **功能二级菜单里的参数保持默认开启**，不受影响：`SCOPE_STRATEGY`（染色作用域）、
    `STG_QUOTE_SWIPE`（左右滑动换股）、`COLOR`（高亮色）默认值照旧。
    二级入口只在总开关打开后才出现，进去时子项本就该是「已经配好」的状态。
- **「重置模块」的写值语义不变**（`app/ModulePreferences.kt`）。功能开关键仍然
  **显式写 `false`**，而不是改写回 `ConfigKeys.DEFAULTS`：现在两者结果一样，
  但显式 `false` 表达的是「重置 = 关掉」这个语义，不跟着默认值联动 ——
  哪天某个功能又要默认开启，重置逻辑不必跟着改。
- 注释与文档同步：`ConfigKeys` 的默认值注释、`docs/UI.md` §5.5.1、
  `docs/EXTENDING.md` 第 4 步新增「`defaultEnabled` 一律填 `false`」硬约定、
  `docs/FEATURE-DIRECT-QUOTE.md` 改动清单、`README.md` 功能特性表。
- `app/build.gradle.kts`：`versionCode 29 → 30`、`versionName 2.11.1 → 2.11.2`

> 注：已有用户**已经开着的开关不会被翻掉** —— 默认值只在键不存在时生效，
> 已经写进 remote preferences 的值原样保留。本改动只影响新装 / 重置后的初始状态。

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
- 清除应用数据后首启：功能菜单两个开关均为**关**，不展开二级入口；
  打开任一开关 → 二级入口出现
- 「策略直达标准行情」二级菜单只剩「左右滑动」一项，页标题为「滑动设置」；
  已在两级开关之间反复切换，不再出现「二级里关掉 → 被弹回一级」
- `aapt2 dump resources`：`section_stg_direct` / `item_stg_direct_quote*` 均 **0** 处残留

---

## [2.11.1] — 2026-10-15

日志二级页底部遮挡修复、玻璃光效关闭时改用纯色材质。

### 变更

- **运行日志二级页：底部内容被手势导航条遮挡**（`ui/LogViewer.kt`）。
  - 症状：页面已沉浸（导航条区域透出页面底色），但最下面的「页脚说明」卡片压在
    手势条下面被遮住 —— 沉浸做对了，**内容却没有让位**。
  - 原因：上一版只给日志 `LazyColumn` 加了 `bottom = navInset` 的 `contentPadding`，
    而页脚说明与操作条在**列表之外**，压根没被让位到。
  - 改法：把让位提到外层 `Column` 上（`padding(bottom = LocalNavBarInset.current)`），
    整列抬到导航条之上；`LazyColumn` 的 `contentPadding` 恢复成普通的 10dp。
    背景仍铺满整屏（沉浸不变），但**内容全部落在导航条之上**。
- **「玻璃光效」关闭时，材质改为不透明纯色**（`ui/Glass.kt` 的 `glassSurfaceTint`）。
  - 症状：光效关掉（无模糊兜底）时面色仍是 0.70 半透明，底下滚过去的文字与图标
    直接透上来，顶栏/底栏/弹窗上的字看不清。
  - 改法：`effect = false` 时直接返回**不透明**的纯黑 / 纯白；`GlassAlphaMinimal`
    常量随之删除。既然不做模糊，就不该再当「玻璃」，老实做纯色栏。
  - 影响面：顶栏（`Glass.kt`）、悬浮底栏（`Dock.kt`）、弹窗卡片（`AppDialog.kt`）
    三处共用该函数，一处改完全都生效。
- `app/build.gradle.kts`：`versionCode 28 → 29`、`versionName 2.11.0 → 2.11.1`

> 注：关于页「技术细节 → 目标适配器」条目已在 [2.11.0] 删除，无需重复处理。

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
- 真机：日志二级页页脚说明卡片完全在导航条之上；关闭「玻璃光效」后顶栏/底栏为纯色不透明
- `aapt2 dump strings`：`目标适配器` / `Target adapter` 均为 **0** 处残留

---

## [2.11.0] — 2026-10-15

首次启动同意弹窗、免责声明 Markdown 化重排、关于页精简。

### 变更

- **新增「首次启动同意免责声明」硬门槛**（`AgreementDialogSpec`）：
  - 未同意前弹窗**关不掉** —— 点空白不关、按返回只吞键不退出（`DialogSpec.dismissible = false`）；
  - 说明文字里的 **「《免责声明》」是可点击链接**，点了跳免责声明二级页看全文；
    回到首页时弹窗自动重新出现（判据是「未同意 **且** 在首页」，不用额外的"暂不显示"标志位）；
  - 底部**自绘勾选框**（Miuix 0.9.x 的 basic 包里没有 Checkbox）+ 文案
    「我已仔细阅读并同意免责声明的全部内容」，**未勾选时「同意」按钮置灰不可点**
    （`DialogButton(enabled = false)`，主色降到 38% 透明度）；
  - 样式沿用「重置」弹窗那套（同一个 `DialogHost` / `DialogCard` / 贴底弹窗布局），
    只多出链接与勾选行；
  - 同意状态存 `ConfigKeys.DISCLAIMER_ACCEPTED`：**不进 `ConfigSnapshot`**
    （目标进程不关心），**不在** `FEATURE_KEYS` / `FEATURE_PARAM_KEYS`（「重置模块」不会
    逼用户重新同意），但在 `DEFAULTS` 里（恢复出厂设置会重新要求同意）。
- **免责声明改为 Markdown 样式重排**：
  - 新增 `ui/MarkdownText.kt`（**自写子集渲染器，不引第三方库**）：支持
    `#` / `##` / `###` 标题层级、`---` 分隔线、`- ` 列表、`> ` 引用、
    行内 `**粗体**` / `*斜体*` / `` `代码` ``；标题层级用「字号 + 字重 + 颜色」
    三重区分（H1 20sp Bold、H2 16sp SemiBold **主色** + 主色竖条、H3 15sp Medium），
    正文 14sp / 行高 22sp。
  - `R.string.disclaimer_full`（中英）改写成 Markdown 结构：7 个二级标题分节、
    关键条款加粗、例外情形斜体、结尾「继续使用即视为接受」用引用块。
  - ⚠️ 文案必须是**单行 + `\n` 转义**形态 —— Android 会把字符串里的真实换行
    折叠成空格，一折叠空行就没了，Markdown 的分段与标题全部失效。
    （英文还得转义撇号 `\'`，否则 aapt2 直接编译失败。）
- **关于页「技术细节」删除「目标适配器」条目**，并清掉随之无用的
  `tech_adapter` / `tech_value_adapter` 字符串（中英）。
- `app/build.gradle.kts`：`versionCode 27 → 28`、`versionName 2.10.1 → 2.11.0`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
- 真机：清掉 `disclaimer_accepted` 后启动 → 同意弹窗出现；未勾选时「同意」不可点；
  勾选后可点；点「《免责声明》」跳二级页；同意后重启不再弹
- `aapt2 dump badging`：versionCode `28` versionName `2.11.0`

---

## [2.10.1] — 2026-10-15

功能分区拆分、搜索框提示词留空、二级页手势导航条沉浸。

### 变更

- **功能分区拆成两个**（原「股票增强」拆掉）：
  - **策略页增强** `category_strategy` —— 改变**策略页交互行为**的增强。
    当前归属：策略详情直达标准行情。
  - **显示增强** `category_display` —— 只改**看到的样子**（颜色 / 字号 / 布局 / 动效）的增强。
    当前归属：自选股高亮。
  - 判据写进 `ui/FeatureCatalog.kt` 顶部注释（三问法 + 边界与例外），
    并在 `docs/EXTENDING.md` 第 4 步补了「分区怎么选（必读）」表，
    后续新增功能照表归类即可。`FEATURE_CATALOG` 顺序调整：策略页分区在前。
  - 资源：删 `category_stock`，新增 `category_strategy` / `category_display`（中英）。
- **功能搜索框提示词留空**：`search_hint` 中英文置空。
  提示词不再占用输入框视觉空间，用途由左侧放大镜图标表达。
- **二级页手势导航条（小白条）沉浸**（`SettingsActivity.SubScreen` + `ui/SystemBars.kt`）：
  - 根因：`SubScreen` 的内容区同时让开了 Scaffold 的**底部** inset
    （`padding.calculateBottomPadding()`），于是最后那 ~64px 永远是空的，
    系统手势条区域被填成一条纯色带 —— 即「不沉浸」。实测截图采样确认：
    y=2340~2399 整条都是页面底色 `#F7F7F7`，没有任何内容穿过。
  - 改法：内容区**只让开顶部**，底部 `0.dp`，一直铺到屏幕物理最底边；
    同时在 `ui/SystemBars.kt` 新增 `LocalNavBarInset` 并下发手势条高度，
    由各二级页在滚动内容末尾补 `24.dp + LocalNavBarInset.current` 的让位空白
    （与一级页用 `LocalDockInset` 给悬浮底栏让位同一套路，避免最后一项压在导航条上）。
  - 覆盖全部四个二级页：免责声明、染色设置、直达设置、日志。
- `app/build.gradle.kts`：`versionCode 26 → 27`、`versionName 2.10.0 → 2.10.1`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
- `aapt2 dump badging`：`gsauxiliary.m.venn` versionCode `27` versionName `2.10.1`
- 真机截图逐行采样：免责声明二级页底部导航条区域不再出现纯色带
- `dexdump` 红线复检：`Lio/github/libxposed/api` **定义类 0 个**（release）

---

## [2.10.0] — 2026-10-15

新功能：**策略详情页直达「标准行情」+ 左右滑动切换股票**。

### 背景

「智能选股 → 我的策略 → 策略详情」里点一只股票，原来会先进一个**中间页**
（`AIStkDetailFragAct`，个股介绍），得再点右上角「标准行情」才到个股详情页，
而且进去后**不能左右滑动**换股；而「行情 / 自选」列表点股票是一步到位、可以滑的。

### 实测结论（真机探针，金太阳 8.6.0 / vc271）

三条路径的 Intent 契约对比（详见 [docs/FEATURE-DIRECT-QUOTE.md](docs/FEATURE-DIRECT-QUOTE.md)）：

| 来源 | action | extras | node_level | 结果 |
|---|---|---|---|---|
| 自选列表 | `quotes.stockDetail` | `adapter_key.stockList` ×33 + `stockIndex=2` | 2 | `StockFragAct`，pager **count=33** ✅ |
| 策略详情 | `router_ai_stk_detail` | `adapter_key{stockItem, stockList×7}` | 5 | `AIStkDetailFragAct`（中间页） |
| 中间页「标准行情」 | `quotes.stockDetail` | `adapter_key.stockInfo` ×1 | 6 | `StockFragAct`，pager **count=1** ❌ |

**「能不能滑动」就是 `adapter_key.stockList` 有没有传整张列表 + 顶层 `stockIndex` 的区别。**
同一个 `LockViewPager #vp_stocks` / `StockFragAdapter`，count 从 33 变成 1。

### 变更

- 新增功能 **`stg_direct_quote`「策略详情直达标准行情」**：
  - `feature/strategyquote/StgDirectQuoteTargets.kt`（新增）—— 实测得到的类名 / action / extra key 候选常量；
  - `feature/strategyquote/StgDirectQuoteFeature.kt`（新增）—— 挂 `EngineAct#startEngineAct*`，
    **放行之前**把 `EngineIntent` 的 action 从 `router_ai_stk_detail` 改写成 `quotes.stockDetail`，
    并把 `adapter_key.stockList` 里的 `StockInfo` 换成 `IntentStockInfo`（复用 App 自带的
    `IntentStockInfo(StockInfo)` 构造器）、补上顶层 `stockIndex`；
  - `feature/FeatureManager.kt` —— 登记新功能。
- 配置：`core/ConfigKeys.kt` 加 `STG_DIRECT_QUOTE`（功能开关）与 `STG_QUOTE_SWIPE`（子选项，
  归 `FEATURE_PARAM_KEYS`）；`ConfigSnapshot` / `ConfigSource` 同步。
- 界面：`ui/FeatureCatalog.kt` 加条目；新增 `ui/StrategyQuoteDetail.kt` 二级菜单
  （两个开关，副标题按状态取 `_on` / `_off` 串）；`SettingsActivity` 的
  二级页分发从「写死打开染色设置」改为**按功能 id 路由**。
- 新增开发工具（`tools/`）：`dev.sh`（无线调试保活 / 自动发现端口）、`adbw.sh`
  （带自动重连的 adb 包装）、`ui.sh` + `uidump.py`（控件树 dump / 按文字点击）、
  `glog.sh` + `glog_tail.py`（按游标读 LSPosed 模块日志增量）。
- `tools/probe` 升到 **v25**：新增「跳转链与 Intent 契约」侦察（捕获 startActivity 的
  全部 extras、Dialog/PopupWindow 形态、页面内 ViewPager/RecyclerView 结构、
  可点击控件的监听器类名、任意类的构造器与字段）。

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
- 真机：目标进程日志确认 `策略直达标准行情：已挂 2 个引擎跳转点` / `功能安装完成：2/2，共 4 个钩子`
- **用户真机实测：点股票直达个股详情页，左右滑动可切换股票** ✅

### 已知边界

- `node_level` 未改动（保持策略侧的 5）。若后续发现返回栈行为异常，再对齐行情侧的 2。
- `stockList` 的替换采用「原地清空重填」，以保留 Bundle 原有的存储方式
  （Parcelable / Serializable 都兼容），避免猜错类型读不到。

---

## [2.9.9] — 2026-10-15

「玻璃光效」开关的副标题跟随开关状态变化。

### 变更

- **副标题按状态取不同文案**（`ui/SettingsTab.kt` + 中英 `strings.xml`）：
  开 → **「完整的玻璃光效」**（`item_glass_effect_summary_on`）、
  关 → **「简约版本」**（`item_glass_effect_summary_off`）。
  原来是静态说明 `item_glass_effect_summary` =「完整的玻璃光效和简约版本」——
  两种形态都写在一句话里，读不出当前处在哪一档；现在与「液态玻璃」
  （仅关掉时给 `liquid_glass_off`）、「可预测返回」、「桌面图标」同一套写法：
  **副标题用来描述「当前状态」的，按状态取不同字符串**。
- 英文：`Full glass lighting` / `Minimal variant`。
- 资源：删除 `item_glass_effect_summary`，新增 `item_glass_effect_summary_on` / `_off`
  （沿用项目既有 `_on` / `_off` 后缀约定，不改 key 命名风格）。
- `app/build.gradle.kts`：`versionCode 24 → 25`、`versionName 2.9.8 → 2.9.9`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**（1m 5s）
- release APK **1 445 080 B**；debug APK **10 891 549 B**
- `aapt2 dump badging`：`gsauxiliary.m.venn` versionCode `25` versionName `2.9.9`
- `aapt2 dump strings`：中英四条新文案均已进包，旧静态文案 0 残留
- `dexdump` 红线复检：`Lio/github/libxposed/api` **定义类 0 个**（release）

### 仍未做

- **真机验证**：切换开关时副标题是否即时刷新（`rememberBoolPref` 驱动的重组）
  需在设备上确认。

---

## [2.9.8] — 2026-10-15

重置弹窗副标题改为强提示文案。

### 变更

- **`dialog_reset_message` 换成短促的强提示**：
  - 中文：`将关闭全部功能（含默认开启的），并把功能参数恢复为默认值。界面设置不受影响，也不会退出本页。`
    → **`重置模块配置后所有数据将会删除！`**
  - 英文：长句 → **`All data will be deleted after the module configuration is reset!`**
- `app/build.gradle.kts`：`versionCode 23 → 24`、`versionName 2.9.7 → 2.9.8`

> ⚠️ **文案与实际行为不符，已向用户说明**：`ModulePreferences.resetAllFeaturesDeferred()`
> 只做 `writeFeatureDefaults()` —— 关掉功能开关 + 把**功能参数**写回默认值，
> **不删除任何文件 / 配置**；模块级设置（主题 / 语言 / 桌面图标 / 玻璃）完全不受影响，
> 「模块」页的备份文件也不会被碰。文案里的「所有数据将会删除」属于夸大警示，
> 并且与 `README`「不修改、不上传任何数据」的口径、以及重置完成后的 Toast
> `已关闭全部功能并恢复默认参数` 互相矛盾。用户坚持要这版文案，故保留，
> 若后续要改回准确表述，改这两处字符串即可。

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
- `aapt2 dump badging`：`gsauxiliary.m.venn` versionCode `24` versionName `2.9.8`
- `aapt2 dump strings`：新中文 / 英文文案已进包
- `dexdump` 红线复检：`Lio/github/libxposed/api` **定义类 0 个**（release）

### 仍未做

- **真机验证**：新文案在弹窗内的排版（15sp、居中、短句单行）需设备确认。

---

## [2.9.7] — 2026-10-15

重置弹窗两个按钮微调尺寸（各加高 2dp、收窄 2dp）。

### 变更

- **确认按钮尺寸**（`ui/AppDialog.kt`）：
  - `DialogButton` 纵向内边距 `11dp → 12dp` —— 按钮**加高 2dp**（上下各 1dp）；
  - 按钮行间距 `12dp → 16dp` —— 两颗 `weight(1f)` 等宽按钮**各收窄 2dp**，
    总宽不变、外缘不动，只是中间让出 4dp。
  - 为什么调间距而不是改按钮本身：`weight(1f)` 的宽度是「行宽 − 间距」算出来的，
    想让**单颗**按钮变窄只能调间距（或整行两侧留白），没法直接给它定宽。
- `app/build.gradle.kts`：`versionCode 22 → 23`、`versionName 2.9.6 → 2.9.7`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**（1m 1s）
- release APK **1 444 848 B**；debug APK **10 891 549 B**
- `aapt2 dump badging`：`gsauxiliary.m.venn` versionCode `23` versionName `2.9.7`
- `dexdump` 红线复检：`Lio/github/libxposed/api` **定义类 0 个**（release）

### 仍未做

- **真机验证**：按钮手感与弹窗字号观感需在设备上确认。

---

## [2.9.6] — 2026-10-15

重置弹窗字号折中定稿（18sp / 15sp）—— 2.9.5 的 20sp 被反馈「太大」。

### 变更

- **重置弹窗主 / 副标题下探到折中档**（`ui/AppDialog.kt`）：
  - 主标题 `title3`（20sp）→ **`title4`（18sp）** + Medium；
  - 副标题 `body1`（16sp）→ **15sp**（Miuix 文字样式表没有 15 这一档：
    body2 = 14、body1 = 16，按用户要求取二者中间，故直接写 `15.sp`）。
  - 三轮定稿：主 **17 → 20 → 18**、副 **14 → 16 → 15**。17sp（= 卡片标题 `headline1`）
    放在整宽弹窗里居中看就是「比外面的字小一号」，而对齐顶栏标题的 20sp 又过大 ——
    18sp（`title4`）是中间那一档的稳定解；正文同理落在 15sp。
- `app/build.gradle.kts`：`versionCode 21 → 22`、`versionName 2.9.5 → 2.9.6`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**（57s）
- release APK **1 444 848 B**；debug APK **10 891 549 B**
- `aapt2 dump badging`：`gsauxiliary.m.venn` versionCode `22` versionName `2.9.6`
- `dexdump` 红线复检：`Lio/github/libxposed/api` **定义类 0 个**（release）
- debug dex 符号确认：`getTitle4` 1 处（新主标题样式）；`getTitle3` / `getBody1`
  已归零（上一版引用被替换）

### 仍未做

- **真机验证**：18sp / 15sp 的观感需在设备上确认。

---

## [2.9.5] — 2026-10-15

重置弹窗字号再加大一号（2.9.4 之后用户反馈主 / 副标题观感仍比外面的字小一号）。

### 变更

- **重置弹窗主 / 副标题整体上调一档**（`ui/AppDialog.kt`）：
  - 主标题 `headline1`（17sp）→ **`title3`（20sp）** + Medium —— 与 **Miuix
    `SmallTopAppBar` 的标题同号**（`TopAppBarKt` 里标题用 `textStyles.title3` +
    `FontWeight.Medium`），也就是页面上最显眼的那行「外面的字」。
    2.9.4 按「与主页卡片标题一致」取 17sp（headline1），但 17sp 恰好比 20sp 低一档 ——
    弹窗标题在整宽卡片上居中、旁边就是 20sp 的顶栏标题，观感自然「小一号」。
  - 副标题 `body2`（14sp）→ **`body1`（16sp）**，与主标题保持 20 : 16 的层级差，
    仍是 Normal 字重；正文变长后贴底卡片随之变高，仍在安全范围内。
  - 两处继续引用 `MiuixTheme.textStyles`（不手写 sp），随主题字体自动保持一致。
- `app/build.gradle.kts`：`versionCode 20 → 21`、`versionName 2.9.4 → 2.9.5`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**（58s）
- release APK **1 444 848 B**；debug APK **10 891 549 B**
- `aapt2 dump badging`：`gsauxiliary.m.venn` versionCode `21` versionName `2.9.5`
- `dexdump` 红线复检：`Lio/github/libxposed/api` **定义类 0 个**（release）
- debug dex 符号确认：`getTitle3` / `getBody1` 各 1 处（重置弹窗标题 / 正文），
  `getHeadline1` 在 ui dex 中已归零（旧的 17sp 引用被替换）

### 仍未做

- **真机验证**：环境无 `adb`。20sp / 16sp 的观感（是否与顶栏标题齐平、卡片是否过高）
  需在设备上确认；若仍偏小，下一档是 `title2`（24sp）。

---

## [2.9.4] — 2026-10-15

四项界面修复（Dock 放大镜倍率收敛 / 主题设置弹窗渐隐渐出 / 弹窗按压高亮裁剪 / 重置弹窗字号与搜索框配色）。

### 变更

- **Dock 放大镜倍率下调、观感更柔和**：`KnobScale` `0.34 → **0.22**`（等比）。
  放大后高度 `54dp × 1.22 ≈ 66dp`，只比 64dp 的胶囊探出一线（2.9.3 的 72dp 明显撑出）；
  折射 `lens(26dp, 48dp)` → **`lens(20dp, 36dp)`**（强度仍随捏住进度成长）。
  「被拿起来」的观感保留，但不再猛。
- **主题设置弹窗补「渐隐渐出」，与缩放动画独立运行**：`DialogHost` 新增**第二个
  `Animatable`（`fade`）**专管 alpha —— 入场 0 → 1、出场 1 → 0（`DialogFadeMillis = 180`）；
  缩放仍由 `progress` 驱动（`DialogMillis = 220`）。两条进度**互不读写**、各自跑在自己的
  协程里：入场是两个 `LaunchedEffect`，出场是两条 `launch` 并发、`join()` 齐了才
  `finishDismiss()` —— 调整任一条的时长 / 曲线都不会牵动另一条。
  贴底确认弹窗（重置）不渐隐（`fade` 恒 1），行为与 2.9.3 完全一致。
- **弹窗内按压高亮裁进卡片圆角**：Miuix 的 `MiuixIndicationInstance.draw` 按压时给
  **整个节点 `drawRect` 一层色**，`ChoiceRow` 是满宽行 → 高亮为矩形，首 / 末行会越过
  28dp 圆角、糊出弹窗之外。`DialogCard` 的内容层加 **`.clip(shape)`**，把高亮
  （及全部子节点绘制）限制在弹窗范围内；**只裁内容层、不裁外层** ——
  外层还画着 `drawBackdrop` 的外投影，裁了会连投影一起切掉。
- **重置弹窗主 / 副标题字体对齐主页**：主标题改用主题文字样式
  **`headline1`（17sp）+ Medium**（= 主页卡片标题的字号与字重；原先手写
  17sp + SemiBold，字号虽同、字重重一档，看起来像「更大一号」）；
  副标题改用 **`body2`（14sp）**（= 主页卡片摘要）。两处不再手写 sp，
  直接引用 `MiuixTheme.textStyles` —— 随主题字体自动保持一致。
- **搜索框配色对齐卡片、对比度增强**：背景由 `TextField` 默认的
  `secondaryContainer`（深色 #434343 / 浅色 #F0F0F0）显式换成 **`surfaceContainer`**
  （Card 默认色：深色 #242424 / 浅色 #FFFFFF）—— 与卡片同色；
  前置图标 `onSurfaceVariantSummary` → **`onSurfaceSecondary`**、
  占位文字 `onSecondaryContainer` → **`onSurfaceSecondary`**（浅色 80% 黑 / 深色 80% 白），
  比已输入正文弱一档、但明显比原来的灰更清晰。
- `app/build.gradle.kts`：`versionCode 19 → 20`、`versionName 2.9.3 → 2.9.4`

### 资源 / 版本

- 新增常量：`DialogFadeMillis = 180`（弹窗渐隐渐出时长，独立于 `DialogMillis = 220`）
- 修改常量：`KnobScale` 0.34 → **0.22f**；滑块放大镜 `lens` 26/48dp → **20/36dp**
- 新增 `DialogHost` 状态：`fade: Animatable`（`isMenu` 时从 0 起，否则恒 1）
- 删除：无
- 版本 2.9.3/vc19 → **2.9.4/vc20**；`applicationId` 仍为 `gsauxiliary.m.venn`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**（1m 1s）
- release APK **1 444 848 B**；debug APK **10 891 549 B**
- `aapt2 dump badging`：`gsauxiliary.m.venn` versionCode `20` versionName `2.9.4`
  （`targetSdkVersion 36` / `compileSdkVersion 37`）
- `dexdump` 红线复检：`Lio/github/libxposed/api` **定义类 0 个**（release）
- debug dex 符号确认：`DialogFadeMillis` / `KnobScale` / `fadeValue` 均已落地；
  `AppDialogKt$DialogHost$3$1$fadeDone$1` 与 `$scaleDone$1`（两条并发出场协程）、
  捕获的 `$fade: Animatable`、`getOnSurfaceSecondary`（搜索框配色）、
  `ClipKt.clip` 调用点均在 dex 中可见

### 仍未做

- **真机验证**：当前环境没有 `adb`。四项改动只过编译 + 静态校验，需在设备上再走一遍：
  放大镜倍率手感（1.22×）、主题弹窗渐隐渐出与缩放是否互不干扰、按压高亮在圆角处是否收住、
  重置弹窗字号与搜索框底色 / 对比度。

---

## [2.9.3] — 2026-10-15

六项界面打磨（搜索框落位 / 弹窗缩回动画与压黑 / 重置弹窗配色与比例 / Dock 滑块放大镜 / 概览状态卡高度与图标）。

### 变更

- **搜索框从顶栏移到功能列表顶部，并去掉动画**：
  `FeatureTab` 新增 `searchState: TextFieldState` 参数（取代旧的 `query: String`），
  搜索框作为功能列表的**第一个元素** `FeatureSearchField(searchState)`（纯 `TextField` +
  `cardPadding()`，无 `AnimatedVisibility`）；`SettingsActivity` 删除顶栏里的
  `FeatureSearchField` 函数及其竖向动画 imports，`HomeScaffold` 仍 `rememberTextFieldState()`
  提升状态，关键词切走再切回不丢。顶栏四个板块完全一致、不再随「是否在功能页」变高。

- **主题设置弹窗补全缩回动画 + 遮罩加深**：
  `DialogController.dismiss()` 由「选择类立即置空、贴底才置 `closing`」改为**两种形态都只置
  `closing`**，`LaunchedEffect(controller.closing)` 播完出场动画再 `finishDismiss()`
  （`closing` 期间 `spec` 保留，不会渲染空壳）。遮罩 `ScrimAlpha` **0.32 → 0.48**
  （主题设置与重置模块共用同一遮罩，一次加深两处同步生效）。

- **重置弹窗标题与正文统一颜色**：`ConfirmDialogSpec` 正文由 `onSurfaceContainerVariant`
  改 `onSurfaceContainer`，与标题同色——正文只是标题的说明，不是两级信息，两个近似灰反而
  让人去找层级差。

- **重置弹窗拉高、去扁长**：纵向内边距 `22.dp → 32.dp`、说明与按钮间距 `20.dp → 28.dp`
  （`DialogCard` 的 `sheet` 分支）。

- **Dock 拖拽滑块放大镜修复（最核心）**：
  - 新增 `val dockBackdrop = rememberLayerBackdrop { drawContent() }`，把「玻璃胶囊 + 图标行」
    单独录进图层；外层 `Box(Modifier.layerBackdrop(dockBackdrop))` 包住内容层，
    滑块 `Box` 移到内容层**之上**、只采样 `dockBackdrop` —— 修掉「放大镜穿透底栏、透出下方内容」。
  - 滑块 `KnobScaleX = 0.06f` / `KnobScaleY = 0.26f` 非等比常量删除，改为
    **`KnobScale = 0.34f` 等比**（`scaleX = scaleY = 1f + KnobScale * magnify`），
    高度 `DockHeight - KnobInset*2`（= 54dp）× 1.34 ≈ **72dp > 64dp 胶囊**，宽高同步增大。
  - `GlassDockItem` 删掉 `magnify` 参数与图标缩放 / 文字 `alpha = 1f - magnify` 淡出逻辑
    —— 滑块经过标题时标题与图标原样保留。
  - 滑块 `magnify > 0.01f` 时 `drawBackdrop(backdrop = dockBackdrop, effects = {
    vibrancy(); lens(26.dp.toPx()*magnify, 48.dp.toPx()*magnify) }, highlight = Highlight.Default,
    shadow = Shadow(16.dp, 0.30f), innerShadow = InnerShadow.Default,
    onDrawSurface = { drawRect(primary.copy(alpha = 0.14f + 0.10f*magnify)) })`
    —— 苹果液态玻璃（Liquid Glass）滑块质感。

- **概览状态卡高度回退 + 图标再向左上移**：
  `Modifier.size(170.dp)` 的图标原先是 Box 直接子节点，`Modifier.offset` 不改正测量，
  卡片高度被图标顶到 170dp（`heightIn(min = 148.dp)` 从未真正生效）。改把图标移进
  `Box(Modifier.matchParentSize())`（不参与父级测量），卡片高度**固定 148dp**；
  `StatusCardIconInset` `16.dp → 26.dp`，图标再向左上挪 10dp（露出约 111dp 见方 ≈ 65%）。

### 资源 / 版本

- 新增常量：`KnobScale = 0.34f`（取代 `KnobScaleX`/`KnobScaleY`/`IconMagnify`）、
  `KnobInset = 5.dp`、`KnobSideInset = 4.dp`
- 修改常量：`ScrimAlpha` 0.32 → **0.48f**、`StatusCardIconInset` 16 → **26.dp**
- 删除：`FeatureSearchField` 的 `AnimatedVisibility` 竖向动画、`GlassDockItem.magnify` 参数、
  状态卡装饰图标的「参与测量」式摆放
- 版本 2.9.2/vc18 → **2.9.3/vc19**；`applicationId` 仍为 `gsauxiliary.m.venn`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**（完整构建 1m 4s）
- release APK **1 444 848 B**；debug APK **11 108 549 B**
- `aapt2 dump badging`：`gsauxiliary.m.venn` versionCode `19` versionName `2.9.3`
  （`targetSdkVersion 36` / `compileSdkVersion 37`）
- `dexdump` 红线复检：`Lio/github/libxposed/api` **定义类 0 个**（release 与 debug 均为 0）
- debug dex 符号确认：`dockBackdrop` / `KnobScale` / `StatusCardIconInset` /
  `ScrimAlpha` / `closing` 均已落地；`FeatureTab` 形参已为 `searchState`

### 仍未做

- **真机验证**：当前环境没有 `adb`，本轮六项改动（搜索框落位、弹窗缩回与压黑、重置弹窗
  配色与比例、Dock 滑块放大镜、状态卡高度与图标）只过了编译 + 静态校验，需在设备上再走一遍。

---

## [2.9.2] — 2026-10-15

弹层体系整体翻新（两种形态 / 渐变压黑遮罩 / 统一主题色确认键）、搜索框动画改为竖向、
概览状态卡字号与图标微调。

### 变更

- **搜索框进 / 出动画改为竖向**：`FeatureSearchField` 由「横向滑入 + 方向跟板块切换走」
  改为 `expandVertically(expandFrom = Alignment.Top)` +
  `shrinkVertically(shrinkTowards = Alignment.Top)` + `-it/4` 同向位移 ——
  「从上往下出现、从下往上消失」。与板块的左右平移**完全解耦**，消除从功能页切走时
  动画从左上 / 右上飞过来的割裂感；`MainScreen` 里的 `tabForward` 参数随之删除。
- **弹窗标题移除**：主题 / 语言 / 日志等级三个选择类弹窗不再画标题，
  `DialogSpec` 接口的 `title` 字段整体删除（`ChoiceDialogSpec` 只剩
  `items / selectedIndex / onSelect / anchor`）。
- **弹窗入场动画改为右上角原点缩放**：`TransformOrigin(1f, 0f)` + `AppearScale = 0.80f`，
  视觉上是「从右上往右下方向展开」；同时**全部改用 `tween`，去掉所有 `spring` / 回弹过冲**。
- **遮罩渐变压黑**：所有弹窗（含此前不画遮罩的锚定菜单）统一加
  `Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = ScrimAlpha * progress)))`，
  `progress` 由 0 → 1，遮罩由透明渐变到 0.32，不再「啪」地一下变暗。
- **重置模块确认弹窗改为贴底形态**：从屏幕下方往上弹出、`translationY = (1 - progress) × height`、
  关闭时向下滑走；宽度 `= width - 2 × 12dp`，与主页卡片左右边缘完全对齐；
  底部再抬过导航条（`WindowInsets.navigationBars.getBottom(density)`）。
- **确认按钮统一用 Miuix 主题色**：强调按钮底色 `MiuixTheme.colorScheme.primary` +
  文字 `onPrimary`（原为 `primary.copy(alpha = 0.16f)` 的浅色底，偏灰）。
- **概览状态卡字号上调 + 图标放大并向左上微调**：

  | 项 | 2.9.1 | 2.9.2 |
  |---|---|---|
  | 标题 / 版本 / API 字号 | 20 / 14 / 13 sp | **22 / 16 / 15 sp** |
  | 图标尺寸 | 150dp | **170dp** |
  | 图标透明度 | 0.18f | **0.16f** |
  | 图标偏移 | `75.dp, 75.dp` | `170/2 - 16, 170/2 - 16` |

  偏移改由 `StatusCardIconSize / 2f - StatusCardIconInset` 推导，各推半个身位后再
  回缩 `StatusCardIconInset = 16.dp`，露出约 **101dp 见方（≈60%）** ——
  不再是「四分之一不可见」的生硬切割。

### 资源 / 版本

- 新增常量：`StatusCardIconSize` 150 → **170.dp**、新增 `StatusCardIconInset = 16.dp`
- 删除常量 / 参数：`DialogSpec.title`、`ChoiceDialogSpec.title`、`MainScreen.tabForward`
- 版本 2.9.1/vc17 → **2.9.2/vc18**；`applicationId` 仍为 `gsauxiliary.m.venn`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**（55s）
- release APK **1 444 848 B**；debug APK **11 108 477 B**
- `aapt2 dump badging`：`gsauxiliary.m.venn` versionCode `18` versionName `2.9.2`
  （`targetSdkVersion 36` / `compileSdkVersion 37`）
- `dexdump` 红线复检：`Lio/github/libxposed/api` **定义类 0 个**（release 与 debug 均为 0）
- debug dex 符号确认：`StatusCardIconInset` / `StatusCardIconSize` / `AppearScale` /
  `finishDismiss` / `getClosing` 均已落地；三处 `ChoiceDialogSpec(...)` 调用已无 `title` 参数

### 仍未做

- **真机验证**：当前环境没有 `adb`，本轮改动（弹层两种形态、入场 / 退出动画、
  贴底确认弹窗、状态卡字号与图标）只过了编译 + 静态校验，需要在设备上再走一遍。

---

## [2.9.1] — 2026-10-15

概览页副标题与状态卡版式调整、功能搜索框补上切换动画、启动器图标收紧留白。

### 变更

- **概览副标题 →「环境概览」**：`subtitle_module` 由「模块 · 环境」改为「环境概览」
  （英文 `Module · Environment` → `Environment`）。
- **状态卡纵向拉长**：`heightIn(min = 148.dp)`（原内容高度约 97dp，+50%），
  三行文字改在 `Box` 里 `align(CenterStart)` 垂直居中，留白更从容。
- **状态卡图标放大并移到右下角，只露左上四分之一**：
  - 尺寸 `52.dp → 150.dp`，`tint` 从实色降到 `accent.copy(alpha = 0.18f)`；
  - `align(BottomEnd)` + `offset(x = 75.dp, y = 75.dp)` 各推**半个身位**，
    图标中心正好落在卡片右下角，卡内只剩左上四分之一；
  - `insideMargin` 由 `16/14dp` 改为 **`0.dp`**，内边距改由文字列自己加 ——
    否则图标会被内边距提前裁一刀，露出来的就不止四分之一。
  - **不需要自己包 `clip`**：Miuix `Card` 的 `BasicCard` 内部走 `squircleSurface`，
    其中调用了 `ClipKt.clip(modifier, shape)`（`javap -c` 逆 `miuix-squircle`
    的 `SquircleBackgroundKt` 确认），溢出部分自动被切掉。
- **功能搜索框补上「左右切换」消失动画**：`FeatureSearchField` 由
  `if (!visible) return`（一帧直接消失）改为 `AnimatedVisibility` ——
  横向 `slideInHorizontally` / `slideOutHorizontally` 方向**跟板块切换方向一致**
  （往右切从右进、向左出），叠 `fadeIn/fadeOut` 与 `expandVertically/shrinkVertically`
  （少了竖向动画，退出那一帧顶栏高度会突然塌一下）。
  方向由 `MainScreen` 在 `onSelectTab` 里**切换的那一刻**锁定并作为
  `tabForward` 传给 `HomeScaffold`。
- **启动器图标：箭头缩小 + 四周留白**：整条折线绕 (54,54) 等比缩到 **0.82**，
  箭头臂长 `13 → 9.5`，描边 `7 → 6.5`。

  | | 2.6.0 | 2.9.1 |
  |---|---|---|
  | 外接框宽 | 63（占安全区 87.5%） | **52.5（72.9%）** |
  | 左右留白 | 4.5 | **9.75** |
  | 上下留白 | 12.5 | **16.25** |

  外接框中心仍精确落在 (54,54)。同步更新 `ic_launcher_foreground.xml`、
  `ic_launcher_monochrome.xml` 与 `tools/gen_launcher_icons.py` 的
  `POLYLINE` / `ARROW` / `STROKE` / 渐变轴，并重跑脚本重新生成五档位图。

### 资源 / 版本

- 改写字符串：`subtitle_module`（`环境概览` / `Environment`）
- 新增常量：`StatusCardMinHeight = 148.dp`、`StatusCardIconSize = 150.dp`
- 重生成资源：`mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round}.png`
- 版本 2.9.0/vc16 → **2.9.1/vc17**；`applicationId` 仍为 `gsauxiliary.m.venn`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
- release APK **1 444 848 B**；debug APK 10 891 549 B
- `aapt2 dump badging`：`gsauxiliary.m.venn` versionCode `17` versionName `2.9.1`
- `aapt2 dump strings`：`环境概览` / `Environment` 均在
- `aapt2 dump xmltree`（debug APK）：前景层与单色层
  `pathData = "M31,70.5 L44,55.5 L55.5,62 L77,37.5 M67.5,37.5 L77,37.5 L77,47"`、
  `strokeWidth = 6.5`，与源码一致
- debug APK 内 10 张 launcher 位图尺寸与脚本输出**逐项一致**
  （1099 / 1673 / 2179 / 3114 / 3985，round 1383 / 2113 / 2775 / 4072 / 5365 字节）
- `dexdump` 红线复检：`Lio/github/libxposed/api` **定义类 0 个**
  （debug dex 里 33 处均为 `compileOnly` 引用，非定义；release 里也是 0）；
  `Lcom/wb/gszx` 残留 **0** 处；release 里被 keep 的类仍正好 7 个
- debug dex 确认新代码落地：`ModuleTabKt.StatusCardMinHeight` /
  `StatusCardIconSize`、`FeatureSearchField`（已带 `AnimatedVisibility`）
- 顺带修掉 `docs/UI.md` 里 §3.1 正文 + §3.2 被重复粘贴一份的结构错误，
  并把 §1.3.3 挪回 §1.3.4 / §1.3.5 之前（子节顺序恢复）

### 仍未做

- **真机验证**：当前环境没有 `adb`，本轮改动（状态卡版式、搜索框动画、
  新启动器图标）只过了编译 + 静态校验，需要在设备上再走一遍。

---

## [2.9.0] — 2026-10-15

「深色模式」更名为「主题设置」并彻底消除切换闪屏、弹窗改为相对触发控件定位且
一次性呈现内容、Dock 手势合并为单一状态机并新增拖动放大镜、玻璃进一步提升通透度、
热重载提示精简为一句、免责声明改写为公文体。

### 修复

- **主题切换闪屏（深→浅「先变白再闪黑最后变白」，浅→深同样）**：2.8.0 的
  「旧配色盖一层再淡出」思路对，但实现有三处独立缺陷，逐帧露馅：

  | 根因 | 症状 | 修法 |
  |---|---|---|
  | `MiuixTheme(colors = if (dark) …)` 直接用**目标值** | 当帧就换成新配色，遮罩还没铺 | 引入 `shownDark`（渲染值）与 `dark`（目标值）**分离** |
  | 遮罩在 `LaunchedEffect` 里才 `snapTo(1f)` | 遮罩晚一帧出现，中间露出新配色的白 / 黑 | `snapTo` 提到切色之前，**先铺满再切** |
  | `if (veil != null) Box(...)` **条件挂载** | 节点插入那一帧还没画出来 → 闪黑 / 闪白 | 遮罩**常驻**，用 `alpha` 控制；`withFrameNanos { }` 再等一帧 |

  现在 `AppTheme` 里 `shownDark` / `veilColor` / `veilAlpha` 三件套 + `withFrameNanos`
  到位，切换过程只剩一次平滑淡出（`ThemeFadeMillis = 320`）。
- **弹窗出现前 / 关闭时先渲染一个「空白弹窗」**：两处独立根因 ——
  ① 入场做了透明度渐显，前几帧只有淡淡轮廓、像先弹了个空框；
  ② `dismiss()` 先把 `spec` 置空再跑退出动画，于是渲染出一个没有标题和选项的空卡片。
  修法：**入场只缩放不渐显**（`AppearScale = 0.94f`，`tween(180)`），
  **`dismiss()` 立即置空、取消退出动画** —— 内容一次性完整呈现、关闭直接消失。
- **Dock 点击与拖动冲突、点击偶发闪烁**：父级 `detectHorizontalDragGestures` +
  子级 `combinedClickable` 是**两条手势链**，手指轻抖越过 `touchSlop` 时父级抢先
  拖动并 `consume()`，子级点击被取消 → 「既没切成板块、滑块又抖一下」。
  2.9.0 把**点击 / 拖动 / 长按合并进单个 `awaitEachGesture`**，用同一份状态机判定，
  三者互斥（拖动 > 长按 > 点击），从根上消除冲突。
- **英文 `msg_no_running_target` 仍带多余后缀**：`No running target process — open
  GoldSun once first` → `No running target program`，与中文一致。

### 变更

- **「深色模式」→「主题设置」**：`item_theme` 中英同步（`主题设置` / `Theme`）——
  它管的是「跟随系统 / 亮色 / 暗色」三选一，叫「深色模式」名不副实。
  弹窗标题取自同一资源，自动跟随。
- **弹窗改为相对触发控件定位**：新增 `DialogSpec`（`sealed interface`，
  带 `anchor: Rect?`），`ChoiceDialogSpec` / `ConfirmDialogSpec` 为其子类型；
  `SettingsTab` 三处 `ArrowPreference` 用 `onGloballyPositioned { boundsInWindow() }`
  采集锚点。`DialogHost` 改用 **`SubcomposeLayout`** 先量卡片尺寸再算坐标，**一次成型**：

  - **优先显示在控件右下方**（右对齐控件右边缘，`x = anchor.right - cardW`）；
  - **下方空间不足则翻到右上方**（`y = anchor.top - gap - cardH`）；
  - `coerceIn` 夹住左右、`coerceAtLeast` 兜住上边，保证不出屏；
  - **锚定时不画遮罩**，缩放原点取贴控件那一角（右上 `TransformOrigin(1f, 0f)`）；
  - `DialogHost` 从 `HomeScaffold` 内容层**上移到 `MainScreen` 最外层 `Box`** ——
    锚点是窗口坐标，宿主必须与窗口原点对齐。
- **Dock 拖动视觉：放大蓝色高光 + 放大镜**：新增 `magnify`（`animateFloatAsState`，
  0→1，`tween(180)`）统一驱动三处 —— 滑块 `graphicsLayer` 纵向拉伸（`scaleY = 1+0.26·m`）、
  高光在 `magnify > 0.01f` 时改用 `drawBackdrop` + `lens(18dp, 34dp)` 做折射放大镜
  （`onDrawSurface` 面色随 `magnify` 加深）、当前板块图标同步放大（`1+0.30·m`）、
  文字淡出（`alpha = 1-m`）。松手 `magnify` 回 0 全部还原。
  `GlassDockItem` **不再挂 `clickable` / `combinedClickable`**，手势全部收归底栏。
- **玻璃进一步通透**：`GlassAlphaFull` `0.52 → 0.30`、`GlassAlphaMinimal` `0.84 → 0.70`。
  完整版有 `blur()` 兜底，0.30 仍可读；简约版无模糊，0.70 是「通透 ↔ 可读」的平衡点。
- **热重载提示精简为一句**：`msg_no_running_target` 中文
  「没有正在运行的目标程序」、英文 `No running target program`，删除后续内容。
- **免责声明改写为公文体（红头文件风格）**：`disclaimer_full` 中英双版重写为
  「本软件（下称"本模块"）系开发者基于 Android 逆向工程与 Hook 技术学习目的
  自行编写的开源软件……为明确各方权利义务、防范法律风险，现就本模块的使用及
  相关事宜作如下声明」，正文分「一、关于使用行为的认定 / 二、关于责任的完全免除 /
  三、关于担保与维护 / 四、关于合规义务 / 五、关于关联关系与商标 /
  六、关于个人信息 / 七、关于条款效力」，**去掉全部【】符号**，结尾
  「使用者继续使用本模块的，即视为已接受修订后的声明」。

### 资源 / 版本

- 改写字符串：`item_theme`（`主题设置` / `Theme`）、
  `msg_no_running_target`（`没有正在运行的目标程序` / `No running target program`）、
  `disclaimer_full`（中英各一份，公文体，无【】）
- 新增代码符号：`DialogSpec`（sealed interface）、`AppearScale` / `MagnifyMillis` /
  `KnobScaleY` / `KnobScaleX` / `IconMagnify` 等常量
- 版本 2.8.0/vc15 → **2.9.0/vc16**；`applicationId` 仍为 `gsauxiliary.m.venn`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
  （R8 的 30 条 `kotlin metadata` 警告为既有现象，不影响产物）
- release APK **1 446 664 B**；debug APK 11 119 530 B
- `aapt2 dump badging`：`package: name='gsauxiliary.m.venn' versionCode='16'
  versionName='2.9.0'`，label `GSAuxiliary`
- `aapt2 dump strings`：`主题设置` / `没有正在运行的目标程序` 均在；
  `disclaimer_full` 公文体首句与 `I. DETERMINATION` / `VII. VALIDITY OF TERMS`
  原样保留，引号与撇号未被 aapt2 吃掉；全包 `【` 计数 **0**
- `dexdump` 红线：`Lio/github/libxposed/api` 定义类 **0** 个（`api` 仍为 `compileOnly`）；
  `Lcom/wb/gszx` 残留 **0** 处；release 里被 keep 的类仍正好 7 个
- debug dex 确认新代码落地：`AppDialogKt` / `DialogController` / `DialogSpec` /
  `ChoiceDialogSpec` / `ConfirmDialogSpec`；`awaitEachGesture` / `magnify` /
  `IconMagnify` / `KnobScaleY` / `GlassAlphaFull` / `GlassAlphaMinimal` /
  `shownDark` / `veilColor` / `veilAlpha` / `withFrameNanos`

### 仍未做

- **真机验证**：当前环境没有 `adb`（`adb: command not found`），
  本轮所有交互修复（主题过渡、弹窗锚定与无空白帧、Dock 手势与放大镜、玻璃通透度）
  只过了编译 + 静态校验，需要在设备上再走一遍。

---

## [2.8.0] — 2026-10-14

页面底色回归修复、顶栏去边框并把功能搜索挂上去、三个下拉彻底换成自绘弹窗、
概览页瘦身、包名迁移到 `gsauxiliary.m.venn`、免责声明重写。

### 修复

- **「页面里的大框全消失了」（2.7.0 的回归）**：2.7.0 修预测手势灰边时，把
  `Scaffold.containerColor` 与第 0 层底色层都改成了 `MiuixTheme.colorScheme.background`。
  实测（`javap` 逆 `ColorsKt.darkColorScheme$default` / `lightColorScheme$default` 的
  53 个默认常量）Miuix 里 **`background` 与 `Card` 的默认色 `surfaceContainer` 完全同色**：

  | | background | surface | surfaceContainer（Card 默认） |
  |---|---|---|---|
  | 深色 | #242424 | **#000000** | #242424 |
  | 浅色 | #FFFFFF | **#F7F7F7** | #FFFFFF |

  于是卡片与页面糊成一片，整页看不出分组。现在页面底色统一用 **`surface`**
  （`MainScreen` / `HomeScaffold` / `SubScreen` / 第 0 层底色层 / `rememberLayerBackdrop`
  的底衬），并在 `MainScreen` 写了色值对照注释防止再踩。
- **后台（最近任务）图标仍是旧图标**：`ActivityManager.TaskDescription` 的图标由系统
  在 Activity 首次创建时缓存，改 `mipmap` 不会刷新。`onCreate` 里新增
  `syncTaskDescription()`，每次都用 `packageManager.getApplicationIcon(packageName)`
  取当前图标重建 `TaskDescription`。
- **英文免责声明编译不过**：`&quot;` / `&#39;` 实体在 AGP 合并资源时被解码成裸 `"` / `'`
  写进 `merged.dir`，而 aapt2 要求撇号必须转义 —— 报
  `Invalid unicode escape sequence in string`（行号指向文件末尾，极具误导性）。
  改为 `\"` / `\'` 后合并文件原样透传，aapt2 再反转义，引号与撇号都能正确保留。

### 变更

- **顶栏去掉所有边框**：不再画底部发丝线、不再叠 `Highlight` 描边，
  就是单纯一块玻璃（模糊 + 增艳 + 半透明面色）。玻璃与内容的分界交给面色本身的对比度。
- **功能搜索移入顶栏**：`GlassTopBar` 新增 `extra: @Composable ColumnScope.() -> Unit`
  插槽，搜索框挂在 `SmallTopAppBar` 下方、被同一块玻璃盖住，**滚动时不再跟着列表滑走**。
  状态提升到 `HomeScaffold`（`rememberTextFieldState()`），`FeatureTab(query, …)`
  只负责过滤，删掉它自己那份 `TextField`。纯色模式下同样挂在顶栏（`Column` 包一层）。
- **三个下拉 → 自绘弹窗（Miuix 风格 + 液态玻璃）**：新增 `ui/AppDialog.kt`。
  深色模式 / 语言 / 日志输出等级改 `ArrowPreference`（右侧直接显示当前值），
  点击弹 `ChoiceDialogSpec`；重置改 `ConfirmDialogSpec`。
  弹窗由 `DialogController` 驱动，通过 `LocalDialogController` 下发，
  `DialogHost` 挂在 `HomeScaffold` 内容层的**最后一个**子节点（压住底栏），
  支持点遮罩关闭与返回键关闭。**玻璃开关直接操控弹窗材质**：
  开 → `drawBackdrop`（模糊 + 增艳 + `glassSurfaceTint` 面色 + 外投影）；
  关 → `surfaceContainerHigh` 纯色。彻底绕开 Miuix `MiuixPopupHost` 的
  「在自己 `onClick` 里同步开始收起动画」这条崩溃路径。
- **「Dock 设置」→「材质设置」**：分组名改为 `section_dock` = 「材质设置」/ "Material"，
  分组内仍是「液态玻璃」+「玻璃光效」两个开关。
- **热重载提示简化**：`requestHotReload` 改为返回 `String?`（`null` = 不提示）。
  只有「**没有正在运行的目标进程**」这一种情况才弹提示（`msg_no_running_target`），
  成功 / 失败一律只写 `GLog`。删掉 `msg_hot_reload_target` / `msg_hot_reload_failed`。
- **控件子标题精简**：所有控件（含二级菜单）的子标题压到单行。
  例如「可预测返回」的子标题由「二级页返回时跟随手势实时预览（安卓 13+）」
  → 「返回跟手预览（安卓 13+）」/「普通返回动画」；
  「运行日志」→「查看模块运行日志」；「备份」→「导出为 JSON 文件」；
  「重置」→「关闭全部功能并恢复默认参数」；「液态玻璃」「桌面图标」在主标题已足够
  清晰时**直接删掉开启态子标题**（只在关闭 / 隐藏时给一条说明）。
  顶栏副标题「概览 · 模块与环境」→「模块 · 环境」。
- **概览页瘦身（一屏看完）**：删掉「框架」分组（连接 / 版本 / API / 作用域，顶部状态卡
  已有）、「配置通道」一行（排障信息，属日志）、「模块名称」一行（就是标题栏）。
  现在只有：状态卡 + 「模块」（版本 / 构建时间）+「运行环境」（安卓 / 设备 / Miuix）。
- **包名 `com.wb.gszx` → `gsauxiliary.m.venn`**：源码目录整体迁移到
  `app/src/main/java/gsauxiliary/m/venn/`，同步改写 `namespace` / `applicationId`、
  `AndroidManifest.xml` 的 provider authority、`META-INF/xposed/java_init.list`、
  `proguard-rules.pro` 的 7 条 keep、`LauncherIcon.ALIAS_CLASS`、
  `tools/env.sh` 的 `MODULE_PKG` 默认值，以及 README / docs 里的路径与类名。
  **类名（`GszxModule` / `GszxApp` …）与底层配置键、日志文件名前缀 `gszx-`
  一律不动** —— 老用户配置不丢、备份文件仍可恢复。
- **免责声明重写**：`disclaimer_title` 改为「使用即代表同意，请 24 小时内删除」，
  `disclaimer_body` 改为「继续使用即视为同意，作者不承担任何责任」。
  `disclaimer_full` 不再用「一、二、三」分条，改成 7 段方括号小标题的紧凑表述：
  【使用即同意】【完全免责】【无担保、不维护】【责任与合规由您自负】
  【无关联与商标】【隐私】【条款可分割】。免责范围覆盖设备损坏、系统异常、
  数据丢失、账号封禁、应用异常、交易 / 投资损失、利润损失与服务中断，
  以及直接 / 间接 / 附带 / 特殊 / 惩罚性 / 后果性损失；
  并写明「未授权任何人以作者名义作出承诺」「条款无效时作最有利于作者的限缩解释」。

### 资源 / 版本

- 新增字符串：`msg_no_running_target`（中英各一份）；弹窗文案复用既有
  `action_confirm` / `action_cancel` / `dialog_reset_title` / `dialog_reset_message`
- 改写字符串：`section_dock` → 「材质设置」/ "Material"；
  `disclaimer_title` / `disclaimer_body` / `disclaimer_full`（中英各一份）；
  一批 `*_summary` 子标题精简
- 删除字符串（中英各一份，共 22 条）：
  `common_not_connected` / `common_copy` / `common_enabled` / `common_disabled` /
  `section_overview_framework` / `status_framework` / `status_channel` /
  `info_lsposed_version` / `info_framework_api` / `info_scope` /
  `msg_framework_not_connected` / `msg_no_callback` /
  `msg_hot_reload_target` / `msg_hot_reload_failed` /
  `item_desktop_icon_summary_on` / `liquid_glass_on` /
  `theme_system` / `theme_light` / `theme_dark` /
  `lang_system` / `lang_zh` / `lang_en`
  （后 6 条是被 `theme_labels` / `language_labels` 两个 string-array 取代的重复项）
- `colors.xml`：`window_background` `#FFF2F3F5` → `#FFF7F7F7`、
  `window_background_dark` `#FF1A1A1A` → `#FF000000`，与 Miuix 的 `surface` 对齐
- 版本 2.7.0/vc14 → **2.8.0/vc15**；`applicationId` = `gsauxiliary.m.venn`

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
- release APK **1 445 444 B**；debug APK 11 104 334 B
- `aapt2 dump badging`：`package: name='gsauxiliary.m.venn' versionCode='15' versionName='2.8.0'`，
  label `GSAuxiliary`
- `aapt2 dump xmltree`：三个 authority 全部随包名迁移
  （`gsauxiliary.m.venn.config` / `.androidx-startup` / `.XposedService`）
- APK 内 `META-INF/xposed/java_init.list` = `gsauxiliary.m.venn.GszxModule`，
  `scope.list` = `com.guosen.android`
- `dexdump` 红线：`Lio/github/libxposed/api` 定义类 **0** 个（`api` 仍为 `compileOnly`）；
  `Lcom/wb/gszx` 残留 **0** 处
- release dex 里被 keep 的类正好 7 个：`GszxModule` / `app.GszxApp` /
  `app.DebugConfigProvider` / `ui.SettingsActivity` / `core.ConfigKeys` /
  `core.ConfigSnapshot` / `feature.watchlist.WatchlistFeature`（= proguard 规则覆盖范围）
- debug dex 确认新代码落地：`AppDialogKt` 203 / `DialogController` 140 /
  `DialogHost` 36 / `ChoiceDialogSpec` 32 / `ConfirmDialogSpec` 32 /
  `FeatureSearchField` 3 / `GlassTopBar` 15 / `DockScrollState` 29 / `syncTaskDescription` 5
- `aapt2 dump resources`：新键（`msg_no_running_target` 等）在，旧键
  （`section_overview_framework` / `status_framework` / `status_channel` /
  `common_not_connected` / `msg_hot_reload_target` / `liquid_glass_on` …）全部消失
- `aapt2 dump strings`：`disclaimer_full` 的 `"as is"` 与 `author's name` 原样保留
  （引号 / 撇号未被 aapt2 吃掉）

### 仍未做

- **真机验证**：当前环境没有 `adb`（`adb: command not found`），
  本轮所有交互修复（搜索框跟随、弹窗材质、下滑收起、后台图标）只过了
  编译 + 静态校验，需要在设备上再走一遍。

---

## [2.7.0] — 2026-10-14

悬浮 Dock 的吸附 / 交互修复、顶栏与底栏统一为「液态玻璃」材质、设置页 Dock 分组重构、
预测手势灰边与下拉闪退彻底修复。

### 修复

- **Dock 滑块拖到位却被拉回原位**：旧实现把「切换」全部压在 `onDragEnd`，且把
  `dragging` 也当成吸附动画的 `LaunchedEffect` key —— 松手那一刻 `dragging` 由
  `true` 翻 `false`，吸附动画立刻用**尚未更新的 `current`** 重跑，滑块被拉回原处；
  又因 `pointerInput` 的 key 只有 `(itemCount, itemWidthPx)`，手势回调捕获的
  `current` / `onSelect` 是**过期闭包**，判断时灵时不灵。现在改为：
  - 拖动**跨过中线即 `onSelect`**（实时切换），`onDragEnd` 只负责把滑块吸附对齐；
    即使手势被上层取消（`onDragEnd` 不触发）也一定切得过去
  - 用 `rememberUpdatedState` 取最新的 `current` / `onSelect`
  - 吸附动画只在 `current` 变化时触发（**刻意不把 `dragging` 当 key**），
    并用一个可取消的 `Job` 保证换目标时先取消上一段动画
- **移除 Dock 触摸变白动画**：`MiuixTheme` 通过 `LocalIndication` 提供
  `MiuixIndication(colorScheme.onBackground)`，按压时叠一层**背景色反色**浮层
  （深色模式下就是白色）。`GlassDockItem` 的 `combinedClickable` 显式传
  `indication = null`；选中态本身已有滑块 + 主色图标两重反馈。
- **预测手势返回一级菜单时四周出现灰边**：两层修 —— ① 在 `MainScreen` 最底层
  增加一个**不参与任何手势动画**的底色层（铺 `MiuixTheme.colorScheme.background`，
  深浅模式自动切换）；② `Scaffold` 显式传 `containerColor = background` ——
  Miuix `Scaffold` 的默认容器色是 `colorScheme.surface`（≠ `background`），
  两者色差正是那圈灰边的另一个来源。二级页 `SubScreen` 的 `Scaffold` 同步处理。
- **深色模式 / 语言 / 日志输出等级点击闪退（彻底修复）**：两条独立根因叠加 ——
  ① Miuix 弹层（`OverlayDropdownPreference` / `OverlayDialog`）在自己的 `onClick`
  里**同步开始收起动画**，此时 `commit()` 经 `OnSharedPreferenceChangeListener`
  同步回调改写 Compose 状态 → 弹层入参被换掉 → 动画与重组打架崩溃；
  ② `LocaleHelper.wrap()` 返回非 `ContextWrapper` 的 `ContextImpl`（2.5.0 已修）。
  2.5.0 回归 Miuix 官方弹层时把 ① 的延迟写入（`editDeferred`）一起删掉了，
  只留下 ②，所以「仍会闪退」。本轮恢复并强化 `editDeferred`
  （延迟 360ms 盖过收起动画 + `AtomicReference` 只保留最后一次），
  下拉与重置确认全部改走它；`notifyChanged()` 也一律 post 到主线程。
  另新增 `resetAllFeaturesDeferred()`。

### 变更

- **顶栏改用液态玻璃**：顶部板块名称栏由纯色块改为液态玻璃（`GlassTopBar`：
  `drawBackdrop` + 半透明面色 + 底部发丝线，叠一层 `color = Color.Transparent`
  的 `SmallTopAppBar`）。**面色有颜色倾向** —— 深色模式是**黑玻璃**（纯黑 + alpha）、
  浅色模式是**白玻璃**（纯白 + alpha），由 `glassSurfaceTint()` 统一给出，
  顶栏与底栏共用同一套规则。液态玻璃**关闭时回退纯色**（Miuix 默认 `SmallTopAppBar`）。
- **「Dock 变换」→「液态玻璃」总开关**：`ConfigKeys.DOCK_LIQUID` / `DOCK_EFFECT`
  重命名为 `LIQUID_GLASS` / `GLASS_EFFECT`（底层字符串键 `dock_liquid` / `dock_effect`
  保持不变，老用户配置不丢）。开关一次决定**两处表面**（顶栏材质 + 底栏形态），
  不再是只描述底栏的「Dock 变换」。
- **设置页新增「Dock 设置」分组**：原「界面」分组只留桌面图标；「液态玻璃」开关
  与其下方的「玻璃光效」开关单独成组（原「光效」改名「玻璃光效」）。
  介绍文案简化为「完整的玻璃光效和简约版本」。
- **新增「下滑自动隐藏悬浮底栏」**：向下滑（看后面的内容）整条收起、向上滑放出。
  判定按**连续同向**滚动量累计（方向一变就清零），累计超过阈值才翻转 ——
  避免手指抖动时反复闪。连接器只观测、**不消费**增量（返回 `Offset.Zero`），
  滚动本身不受影响；切板块 / 关玻璃时重置。收起动画 = `animateFloatAsState`
  驱动的 `translationY`，与开关玻璃的 `alpha` 相乘。
- **关于页「项目仓库」可跳转**：由只读 `BasicComponent` 改为可点击 `ArrowPreference`，
  地址 `https://github.com/SansKritO4/GSAuxiliary`（新增资源 `about_value_repo_url`）。

### 资源 / 版本

- 新增字符串：`section_dock` / `item_liquid_glass` / `liquid_glass_on|off` /
  `item_glass_effect` / `item_glass_effect_summary`（中：「完整的玻璃光效和简约版本」）/
  `about_value_repo_url`（中英各一份）
- 删除字符串：`item_dock_liquid` / `dock_liquid_on|off` / `dock_switch_summary` /
  `item_dock_effect` / `item_dock_effect_summary_on|off` / `about_value_repo`
- 版本 2.6.0/vc13 → **2.7.0/vc14**

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
- release APK **1 484 416 B**；debug APK 10 865 713 B
- `aapt2 dump badging`：`versionCode='14' versionName='2.7.0'`，label `GSAuxiliary`
- `aapt2 dump resources`：新键齐全（`about_value_repo_url` / `item_glass_effect` /
  `item_glass_effect_summary` / `item_liquid_glass` / `liquid_glass_off|on` /
  `section_dock`），旧键（`item_dock_liquid` / `item_dock_effect` /
  `dock_switch_summary` / `about_value_repo`）已消失
- APK 内 `META-INF/xposed/{java_init.list,module.prop,scope.list}` 齐全；
  `dexdump` 确认 `Lio/github/libxposed/api` 定义类 **0** 个（红线仍守住）
- debug dex 内确认新代码落地：`editDeferred` 7 / `resetAllFeaturesDeferred` 3 /
  `DockScrollState` 9 / `dockScrollConnection` 6 / `GlassTopBar` 14 /
  `LiquidGlassDock` 88 / `glassSurfaceTint` 1 / `rememberUpdatedState` 8

### 仍未做

- **真机验证**（`adb devices` 为空）：滑块吸附手感、下滑收起的阈值与动画、
  顶栏玻璃观感与深浅两色、预测手势灰边是否消失、三个下拉是否彻底不闪退 ——
  均只过了编译 + 静态校验。

---

## [2.6.0] — 2026-10-14

更名 **GSAuxiliary**、换新图标、概览页重构、日志页增强，并修掉「底栏消失」这个回归。

### 变更

- **模块更名为 `GSAuxiliary`**：应用名、LSPosed 列表里的名称、设置页内所有
  「金太阳增强」字样统一替换；`settings.gradle.kts` 的 `rootProject.name` 同步改名。
  LSPosed 模块列表下的简介改为 **`Make GoldenSun Great Again！`**
  （取自 `android:description`，不是 legacy meta-data）。
- **新图标（简约苹果风格）**：圆角方形 + 深炭灰竖向渐变底 + 一条金色上升折线 + 右上箭头。
  自适应图标的背景层从纯色 `@color/ic_launcher_background` 改为渐变 drawable
  `@drawable/ic_launcher_background`（`<background>` 只吃 drawable，做不出渐变）；
  前景 / 单色层几何同步重画；`mipmap-*dpi` 的 PNG 回退由
  `tools/gen_launcher_icons.py` 用**距离场描边**重新生成（圆头笔帽与圆角拐点由距离场天然产生）。
  该颜色资源已删除。
- **「模块」Tab 更名为「概览」**：底栏文案、页面副标题（`模块 · 框架 · 环境`）同步更新。
- **关于页新增 GitHub 行**：开发者改为 `M.Venn`，项目仓库占位为 `GSAuxiliary`，
  并新增可点击跳转的 `https://github.com/SansKritO4`。

### 新增

- **概览页顶部状态卡**：仿 LSPosed 模块页那张「已激活 + 大对勾」的卡片。
  查过 `miuix-ui` / `miuix-core` / `miuix-preference` 0.9.3 的完整类清单 ——
  Miuix **没有**这类「状态卡 / Hero 卡」复合组件（LSPosed 那张也是它自己拼的），
  所以按 Miuix 视觉规范自绘：`Card`（自定义配色）+ 左侧三行文字 + 右侧一枚 52dp 图标。
  已激活 = 绿色 + 圆对勾；未激活 = 红色 + 圆感叹号。
- **概览页分类重排为三组**，按「信息归属」而不是「类型」划分：

  | 分组 | 内容 |
  |---|---|
  | 模块 | 名称、版本、构建时间、配置通道 |
  | 框架 | 连接状态、框架版本、API 版本、作用域 |
  | 运行环境 | 安卓版本、设备型号、Miuix 版本 |

  之前「模块版本 / 安卓版本 / LSPosed 版本 / 设备型号」混在一个「系统信息」里，
  看不出哪一行是「我自己」、哪一行是「环境」。
- **日志页新增「保存」按钮**：走 SAF（`ActivityResultContracts.CreateDocument`），
  可以存到任意位置，不需要任何存储权限；默认文件名 `gszx-log-yyyyMMdd-HHmmss.txt`。
  文件写入在 `Dispatchers.IO`，Toast 回主线程。
- **日志页操作条改为四等分自适应占满**：刷新 / 复制 / 保存 / 清空，
  每个 `TextButton` 挂 `Modifier.weight(1f)`，不再写死宽度、不再全部挤在左边。

### 修复

- **悬浮底栏「消失」**：`Modifier.align(Alignment.BottomCenter)` 原本写在
  `AnimatedContent` 的 content lambda 里。`AnimatedContentScope` 只继承
  `AnimatedVisibilityScope`、**不是** `BoxScope`，所以 `align` 解析到的是外层 `Box` 的
  parent data，却挂在 `AnimatedContent` 内部 Layout 的子节点上 ——
  那份 parent data 没人读，底栏被丢到内容区左上角、被顶栏盖住。
  现在把对齐与 insets 挪到外层 `Box` 的**直接子节点**上，并用
  `animateFloatAsState` + `graphicsLayer { alpha }` 做过渡（顺带避开
  `AnimatedContent` 默认 `clip = true` 会切掉长按气泡的问题）。
- **位图与矢量的描边渐变方向不一致**：矢量 `ic_launcher_foreground.xml` 用的是
  从折线起点 `(26,74)` 指向终点 `(82,34)` 的**斜向**渐变，而
  `tools/gen_launcher_icons.py` 一开始写成了竖向渐变。改成「把像素投影到渐变轴」
  求 `t`（超出端点夹住，等价于 SVG 的 `spreadMethod=pad`），两边现在完全一致。
- **`tools/verify_icons.py` 不支持 `A` 圆弧命令**：状态卡的两个图标原用 `a` 画圆，
  会让校验脚本直接报「暂不支持的命令」。改用四段三次贝塞尔近似
  （控制点偏移 = 半径 × 0.5522847），并把 `CheckCircle` / `ErrorCircle` 加进
  `EXPECTED_SUBPATHS`（期望子路径数 2 / 3）。
- **XML 注释里的 `-----` 分隔线导致资源编译失败**：`mergeResources` 报
  `ParseError: 注释中不允许出现字符串 "--"`。已改掉。
- **`info_app_version` 只从中文资源删除**，英文资源残留造成翻译缺失，已补齐；
  中英字符串现各 166 条、完全一一对应。

### 验证

- `:app:assembleDebug :app:assembleRelease` 均 **BUILD SUCCESSFUL**。
- Release APK **1 484 916 字节**（2.5.0：1 458 288）。
- dex 类总数 **3 088**（2.5.0：3 085）；`Lio/github/libxposed/api` 类 **0 个**。
- manifest 里 legacy `xposedminversion` / `xposeddescription` / `xposedmodule` meta-data **0 条**。
- `META-INF/xposed/{module.prop,java_init.list,scope.list}` 完整，
  `minApiVersion` / `targetApiVersion` = 102，作用域 `com.guosen.android`。
- keep 列表逐项命中：`GszxModule` / `GszxApp` / `SettingsActivity` /
  `DebugConfigProvider` / `ConfigKeys` / `ConfigSnapshot` / `WatchlistFeature` 各 1 个。
- `aapt2 dump badging`：`versionCode='13' versionName='2.6.0'`、
  `application-label='GSAuxiliary'`、`targetSdkVersion:'36'`。
- `aapt2 dump resources`：`string/xposed_description` 两种语言均为
  `Make GoldenSun Great Again！`；`drawable/ic_launcher_{background,foreground,monochrome}`
  均存在；`color/ic_launcher_background` 已消失；
  `mipmap/ic_launcher{,_round}` 各有 mdpi / hdpi / xhdpi / xxhdpi / xxxhdpi PNG + anydpi XML。
- `python tools/verify_icons.py`：9 个图标全部通过结构检查。

---

## [2.5.0] — 2026-10-14

一轮界面缺陷集中修复：两处闪退、系统栏沉浸、Dock 交互与动画、弹层回归 Miuix 官方组件。

### 修复

- **切换语言后点「设置」闪退**：`LocaleHelper.wrap()` 直接把
  `Context.createConfigurationContext()` 的返回值交给了 Activity —— 那是
  `ContextImpl`，继承自 `Context` 而**不是** `ContextWrapper`，`baseContext` 链是断的。
  androidx 找宿主时沿 `ContextWrapper` 链上溯，循环直接不成立，随后的
  `checkcast ContextWrapper` 抛 `ClassCastException`。只有设置页用了
  `rememberLauncherForActivityResult`（备份 / 恢复），所以只有它炸。
  现在返回一个保留了 `baseContext` 链的 `ContextWrapper`，只替换 `resources` / `assets`。
- **点「热重载」闪退**：原实现在一个裸 `Thread` 里直接 `Toast.makeText(...).show()`，
  后台线程没有 `Looper` → `RuntimeException`。改为挂起函数
  （`withContext(Dispatchers.IO)` 做 Binder 调用并等回调），回到 `scope.launch`
  的主线程再弹提示，成功 / 失败都有提示。
- **安卓系统「小白条」是纯色填充**：`enableEdgeToEdge()` 的默认
  `SystemBarStyle.auto(...)` 其 `nightMode` 是 `MODE_NIGHT_AUTO`，androidx 的
  `EdgeToEdgeApi29.setUp` 遇到这个值会执行
  `window.navigationBarContrastEnforced = true`，系统随即在透明的导航栏上叠一层
  半透明遮罩。已在主题层与代码层双重关掉（详见 `docs/UI.md` §11）。
- **切换深色 / 浅色是硬切，没有过渡**：Miuix 的 `Colors` 有 53 个字段且 setter 是
  `internal`，外部无法逐字段插值。改为「旧配色整屏盖一层 → 320ms 淡出」。
- **Dock 形态切换是生硬跳变**：两种底栏之间加 `AnimatedContent` 交叉淡变；
  四个板块切换也从硬切改为「淡入 + 1/8 屏宽横移」。
- **「Dock 变换」开关展开 / 收起下方选项时没有动画**：包进 `AnimatedVisibility`，
  动画与功能页的二级入口完全一致（`expandVertically(220) + fadeIn(180)`）。
- **简约版语义不对**：原来「关光效」是把白边一起去掉。现在改为
  **去掉模糊与投影，保留白边**，底色从 0.55 提到 0.92 兜底可读性。
- **液态底栏的滑块按住拖不动**：新增横向拖拽手势，滑块跟手移动、松手吸附到最近板块
  （没跨中线就原地回弹），拖动时气泡跟随。
- **「重置模块」的弹窗不符合 Miuix 规范**：改回官方 `OverlayDialog`。

### 变更

- **弹层回归 Miuix 官方组件**：三个下拉（深色模式 / 语言 / 日志等级）改回
  `OverlayDropdownPreference`，重置确认改用 `OverlayDialog`。
  2.3.1 引入的 `ui/AppOverlay.kt` 自绘覆盖层**整体删除**（含 `AppOverlayController` /
  `AppOverlayHost` / `AppOverlayRequest`），`MainScreen` 从三层布局回到两层，
  `ModulePreferences.editDeferred` 的历史说明也一并更新。
- **液态悬浮底栏移进 `Scaffold` 的 content 内部**：Miuix 弹层由 `Scaffold` 内部的
  `MiuixPopupHost` 渲染，位置在内容之上、`Scaffold` 的兄弟节点之下；
  挂在 `Scaffold` 外面的浮层会盖住下拉弹层与重置对话框。
- **免责声明加二级页**：关于页的免责声明卡片改为可点击入口，二级页
  （`Route.Disclaimer` → `DisclaimerContent()`）显示完整文本
  （风险自担 / 隐私 / 合规 / 无担保 / 其他五节）。
- **去掉「开源鸣谢」上方的「本模块站在这些开源项目的肩膀上」一句**，
  同时移除对应的 `oss_hint` 字符串资源（中英双语）。
- **系统栏沉浸**：新增 `ui/SystemBars.kt`。主题新增 `values-night/themes.xml`，
  `windowBackground` 抽成 `@color/window_background` / `@color/window_background_dark`；
  新增 `android:windowLightNavigationBar` 与
  `android:enforce{Status,Navigation}BarContrast=false`；
  `SyncSystemBars` 按**实际生效**的深浅色（可能被用户强制）设系统栏图标明暗，
  并把窗口底色换成 Miuix 的真实背景色。液态底栏时内容吃满底部 inset，
  一直铺到屏幕物理边缘。
- 版本 2.4.0 → 2.5.0（versionCode 11 → 12）。

### 验证

- release APK 1 458 288 字节（2.4.0 为 1 405 940，+52 KB 来自新增文案与夜间主题）。
- dex 内类总数 3 085（2.4.0 为 2 987）。
- 关键入口类全部保留：`GszxModule` / `GszxApp` / `SettingsActivity` /
  `DebugConfigProvider` / `ConfigKeys` / `ConfigSnapshot` 各 1 个。
- 红线通过：`Lio/github/libxposed/api` 相关类 **0 个**（仍为 compileOnly）；
  清单内 legacy `xposed*` meta-data **0 处**；
  `META-INF/xposed/{java_init.list,module.prop,scope.list}` 齐全
  （`minApiVersion` / `targetApiVersion` = 102，scope = `com.guosen.android`）。
- 资源校验：`style/Theme.GSZX` 在 `resources.arsc` 里有默认与 `(night)` 两个变体，
  `windowLightNavigationBar` / `enforceStatusBarContrast` / `enforceNavigationBarContrast`
  均已写入。
- **未做真机验证**（当时无设备连接）：两处闪退修复、系统栏沉浸、Dock 拖拽与各处动画
  都只过了编译与静态校验。

---

## [2.4.0] — 2026-10-14

**体积优化：APK 从 11.45 MB 降到 1.34 MB（-88%），dex 类数从 26 553 降到 2 987。**

### 修复

- **安装占用异常偏大（实测 73.67 MB）**：定位到唯一主因 ——
  `androidx.compose.material:material-icons-extended:1.7.5` 把 Material 全量图标
  （五种风格）都编进了 dex，实测 **11 400 个类**、占当时类总数的 43%，
  而界面实际只用到 7 个。`res/`、`assets/`、`lib/` 加起来不到 300 KB，
  并非「打包了奇怪的东西」。

### 变更

- **图标改为自绘**：新增 `ui/AppIcons.kt`，用 Material 官方 24×24 网格的 path 数据
  构建 `ImageVector`（返回箭头 / 对勾 / 放大镜 / 齿轮 / 信息 / 九宫格 / 拼图块），
  移除 `material-icons-extended` 依赖，零第三方图标库。
- **release 构建开启 R8**：`isMinifyEnabled = true` + `isShrinkResources = true`，
  同时裁剪代码与资源。`app/proguard-rules.pro` 补齐入口 keep 规则
  （`GszxModule`、`GszxApp`、`SettingsActivity`、`DebugConfigProvider`、
  `ConfigKeys`、`ConfigSnapshot`、`Feature` 实现类、libxposed 服务通道）。
- 「关于」页的开源鸣谢中，Material Icons 的说明改为「界面图标的矢量路径数据
  （自绘 ImageVector，不引入图标库）」—— path 数据仍来自 Google，鸣谢保留。
- 版本 2.3.1 → 2.4.0（versionCode 10 → 11）。

### 验证

- 新 APK 1 405 940 字节；`classes.dex` 2 600 416 字节（原 30 969 336）。
- dex 内类总数 2 987（原 26 553）。
- 关键入口类全部保留：`GszxModule` / `GszxApp` / `SettingsActivity` /
  `DebugConfigProvider` / `ConfigKeys` 各 1 个。
- 红线通过：`Lio/github/libxposed/api` 相关类 **0 个**（仍为 compileOnly）。
- `META-INF/xposed/{java_init.list,module.prop,scope.list}` 齐全；
  清单内无 `xposedminversion` 等 legacy 元数据。

---

## [2.3.1] — 2026-10-14

**10 项跟进修复：底栏真正悬浮 / 路由与动画重写 / 下拉与确认弹窗自绘 / 热重载改小弹窗。**

### 修复

- **液态底栏没有悬浮、下方一片黑色占位**：两个原因叠加 ——
  ① 内容被整体上推，底栏背后是空白，玻璃没有可采样的对象；
  ② 根容器没有铺主题背景，透出窗口底色（深色下即黑色）。
  现在根容器铺 `MiuixTheme.colorScheme.background`，内容**一直延伸到屏幕底部**，
  由各 Tab 在**滚动内容末尾**补 `LocalDockInset`（= `DockHeight + DockMargin`）的空白，
  列表能滚到底栏之下；底栏本体再补一层**外投影**（`Shadow`），真正「浮」起来。
- **下拉控件（深色模式 / 语言 / 日志输出等级）点击闪退、不出选择框**：
  弃用 Miuix 的 `OverlayDropdownPreference`（依赖 `Scaffold` 内部弹层宿主，
  在本分层布局下表现不稳定），改为自绘覆盖层（见下）。
- **点「重置模块」不关功能、无二次确认、还会退出页面**：根因是
  「功能开关的出厂默认是**开**」，重置写回默认值等于没关掉。新增
  `ModulePreferences.resetAllFeatures()`：功能开关一律写 `false`，功能参数恢复默认；
  二次确认与结果提示改走自绘覆盖层 + 系统小弹窗，**不退出设置页**。
- **可预测返回时二级页下方看不到一级菜单**：原实现用 `AnimatedContent` 整体替换内容，
  返回手势时下方没有底衬。重写为**三层结构**（一级常驻底衬 / 二级滑入 / 覆盖层）。
- **一二级切换动画进行中闪白**：`AnimatedContent` + `SizeTransform(clip = false)`
  在切换瞬间会露出窗口底色。改为 `Animatable` 驱动的位移 + 每层各自铺背景，不再闪白。
- **四个主板块切换没有动画**：`HomeScaffold` 的内容区包一层 `AnimatedContent`，
  按 Tab 顺序做淡入 + 轻微横移。
- **功能页搜索栏图标贴边**：左侧补 12dp 留白。
- **热重载的结果展示**：删掉「没有正在运行的目标进程」的行内提示与「最近一次操作」控件，
  结果一律改用系统小弹窗（Toast）提示。

### 新增

- `ui/AppOverlay.kt`：模块自绘的覆盖层系统（`AppOverlayController` + `AppOverlayHost`），
  提供「单选列表」与「二次确认」两种请求，挂在 `MainScreen` 根容器最上层（`zIndex`），
  层级、动画、点击完全可控，不依赖任何第三方弹层宿主。
- **液态底栏滑动指示**：选中项下方一枚随切换滑动的胶囊，画在玻璃**之上**、图标之下。

### 变更

- **可预测返回默认开启**（`DEFAULT_PREDICTIVE_BACK = true`）。
  液态底栏（`DEFAULT_DOCK_LIQUID`）本就走的是默认开。
- **备份文件名**改为「英文 + 日期 + 时分秒」：`gszx-backup-yyyyMMdd-HHmmss.json`。
- 液态底栏的玻璃本体、滑动指示、图标行拆成三个同级节点，避免指示被半透明面色盖住。
- 版本 2.3.0 → 2.3.1（versionCode 9 → 10）。

---

## [2.3.0] — 2026-10-14

**21 项修复与优化：Dock 占位 / 设置入口可见性 / 弹层闪退 / 布局留白 / 深色模式 / 可预测返回 / 页面重写。**

### 修复

- **液态 Dock 下方仍被旧 Dock 占位**：`Scaffold` 的底部 inset 与各 Tab 自己加的
  `navigationBarsPadding()` 被算了两遍，底部空出一整片死区。现在 inset 只由 `Scaffold`
  负责，悬浮胶囊的占位只在 `HomeScaffold` 里加一次（`DockHeight + DockMargin`）。
- **隐藏桌面图标后 LSPosed 里的设置入口也没了**：给 `SettingsActivity` 本体补上
  `MAIN + de.robv.android.xposed.category.MODULE_SETTINGS` 的 intent-filter。
  LSPosed 优先按这个 category 找入口，而本体始终 enabled，所以关图标不影响入口。
- **点「语言」闪退**：原先在 Miuix 下拉弹层的回调里同步写配置并调 `Activity.recreate()`，
  弹层在收起动画中途被销毁。现在语言改由 `AppTheme` 在 Compose 层重新
  `provides LocalContext`，**不再 `recreate()`**，且写入统一延迟到弹层收起之后。
- **点「日志输出等级」闪退**：同上，改用 `ModulePreferences.editDeferred { }`。
- **点「重置模块」闪退且会退出设置页**：改为延迟执行，且只关功能开关 +
  恢复功能参数（`ModulePreferences.resetFeatures()`），不动界面设置、不退出页面。
- **备份 / 恢复卡顿**：跨进程读 remote preferences + 写文件的整段 IO 挪到
  `Dispatchers.IO`，期间入口置灰并显示进度文案。
- 二级页按系统返回键会直接退出设置页：`BackHandler` / `PredictiveBackHandler`
  拦截为「回到一级菜单」。
- 效果预览里「未加自选」写死深色，深色模式下变成黑底黑字：改用主题色 `onBackground`。

### 新增

- **深色模式**（跟随系统 / 亮色 / 暗色），样式与「语言」一致。
- **可预测返回开关**（Android 13+）：manifest 开 `enableOnBackInvokedCallback`，
  开启时二级页用 `PredictiveBackHandler` 跟手缩放。
- **液态底栏光效**：`Highlight`（顶部高光）+ `InnerShadow`（内阴影），
  在「Dock 变换」开启液态时**内嵌**出现在其下方，可单独关闭。
- **液态底栏长按气泡**：长按 Tab 在其正上方弹出名称气泡，约 1.4s 自动消失。
- **一级 ↔ 二级切换动画**：`AnimatedContent` 左右滑动 + 淡入淡出。
- **模块页显示 Miuix 版本**（`BuildConfig.MIUIX_VERSION`，由 `miuixVersion` 注入）。
- **关于页「开源鸣谢」**：Miuix / LSPosed / AndroidLiquidGlass / Material Icons
  逐条可点击跳转。

### 变更

- **「关于」页删除使用指南**；「模块」页移除「染色功能 / 当前高亮色 / 作用域」三行，
  该页收敛为**只读展示**。
- **「热重载目标进程」从模块页迁到设置页**的「框架操作」分组。
- **去掉「开启后在此展开具体设置」提示语**：展开 / 收起本身已有明确视觉反馈。
- **重写高亮颜色控件**：大圆色块 + 名称 + 选中主色环与对勾，一个控件同时承担
  「看颜色」和「选颜色」，删掉原先重复的 `RadioButtonPreference` 列表。
- **日志页按 Miuix 风格重写**：`TabRow` 级别筛选 + `Card` 等宽日志列表（级别上色）
  + 底部操作条。
- **统一卡片左右留白** `Modifier.cardPadding()`（12dp），与 Miuix `SmallTitle` 的
  28dp 内边距对齐；搜索框同步到同一条基准线，图标与文字之间补了间距。
- 「Dock 变换」的副标题去掉「· 点击切换」。
- 版本 2.2.0 → 2.3.0（versionCode 8 → 9）。

---

## [2.2.0] — 2026-10-14

**设置页职责收敛 + 功能二级菜单 + 液态玻璃悬浮底栏 + 图标补齐。**

### 新增

- **液态玻璃悬浮底栏**：设置页可在「Miuix 默认底栏」与「液态玻璃悬浮底栏」之间切换。
  基于 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的
  `backdrop` 1.0.6 —— 把 Tab 内容录进图层，底栏采样该图层做
  `vibrancy() + blur() + lens()`，得到真实的玻璃折射。
  模糊需 API 31+、折射需 API 33+，低版本自动退化为半透明胶囊。
- **功能二级菜单**：功能 Tab 里每个功能是「开关」；**打开开关后**，开关下方展开
  「染色设置」入口，点击进入二级菜单（染色设置 / 高亮颜色 / 效果预览）。
  这是所有可调节功能的统一交互：一级页面只放开关，具体设置一律收进二级菜单。
- **设置页新增模块级项**：桌面图标开关、Dock 变换开关、语言、备份模块配置、
  恢复模块、重置模块、日志输出等级、查看日志。
- **语言**：跟随系统 / 简体中文 / English。字符串全部走资源，
  在 `attachBaseContext` 里按选择包装 Context（`values/` = 中文，`values-en/` = 英文）。
- **备份 / 恢复**：导出为自描述 JSON（带 format / 版本 / 时间 / 默认值），
  恢复时只认白名单键。走 SAF（`CreateDocument` / `OpenDocument`），不需要存储权限。
- **查看日志**：`GLog` 增加 500 行内存环形缓冲，设置页可查看 / 复制 / 清空本进程日志。
- **构建时间**：`BuildConfig.BUILD_TIME`，模块页展示。
- **图标补齐**：补 `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` 与
  `ic_launcher_round.png`（`tools/gen_launcher_icons.py` 生成），
  新增 `ic_launcher_round` 自适应图标与 Android 13+ 主题图标用的 `monochrome` 层。

### 变更

- **设置页只负责本模块范围内的设置项**。原先混在设置页里的「染色设置 / 高亮颜色 /
  效果预览」全部移入功能 Tab 的二级菜单，设置页不再出现具体功能开关。
- 界面文案全部迁到资源（`values/strings.xml` + `values-en/strings.xml`），
  预设色名 / 说明、日志等级名、语言名改用 `string-array`，`ConfigKeys` 里
  不再硬编码中文展示文本。
- `ui/SettingsActivity.kt` 从单文件拆分为：`SettingsActivity`（Activity + 路由 + Dock 装配）、
  `PrefState`、`FeatureCatalog`、`Widgets`、`Dock`、`ModuleTab`、`FeatureTab`、
  `SettingsTab`、`AboutTab`、`WatchlistDetail`、`LogViewer`。
- 桌面图标改挂在 `activity-alias`（`.ui.LauncherAlias`）上：
  切图标只禁用 alias，Activity 本体始终 enabled，不会被锁死在进不去设置页的状态。
- 模块 App 进程现在也会应用「日志输出等级」（此前只有目标进程读了这项配置）。
- 依赖：新增 `io.github.kyant0:backdrop:1.0.6`。
  **不能升到 2.x** —— 2.0.1 依赖 Compose 1.12，而 Compose 1.12 要求 AGP ≥ 9.1，
  与本项目锁定的 AGP 8.10.1 冲突（同 Miuix 0.9.4+ 的坑，详见 [docs/BUILD.md](docs/BUILD.md)）。

### 修复

- 图标缺失：此前 `mipmap-*dpi` 下没有位图图标，桌面回落到系统默认图标。
- `app/build.gradle.kts` 里 `java.text.SimpleDateFormat` 会被解析成 Gradle 的
  `JavaPluginExtension`（`Unresolved reference: text`），改用顶部 import 规避。

---

## [2.1.0] — 2026-10-14

**清理与文档重写：移除历史遗留代码，重建文档体系。**

### 删除

- 设置界面中未实现的占位项：「界面设置」（dock 液态玻璃 / 桌面图标）、「语言」
- 设置界面的搜索框与「规划中」功能占位（当前功能规模不需要）
- `core/ConfigKeys.kt` 中对应的孤儿键：`DOCK_LIQUID` / `DESKTOP_ICON` / `LANGUAGE`
  及其默认值常量
- `feature/Feature.kt` 的 `description` 字段（无处消费）
- `FeatureEnv` 的 `packageName` 参数（无消费者）
- `app/FrameworkBridge.kt` 的 `remotePreferences(group)`、`ModulePreferences.getString()`
- `core/GLog.kt` 的 `mirrorToLogcat` 开关（日志出口已由 `Sink` 决定，该开关是死配置）

### 修复

- `FeatureManager.uninstallAll()` 此前**从未被调用**。现在
  `onHotReloaded` 会先清掉本模块自己的钩子，再处理框架交还的 `oldHookHandles`，
  避免热重载后旧钩子残留

### 变更

- `app/proguard-rules.pro` 重写：原先的规则引用的还是旧包路径下已删除的类，
  现已按当前类结构调整
- 文档体系重建：`docs/EXTENDING.md`（扩展与演进）、`docs/UI.md`（设置界面）
  取代旧文档；其余文档统一为「描述当前状态」，不再保留迁移期叙事
- `tools/probe/` 说明同步更新

---

## [2.0.0] — 2026-10-14

**架构基线：LSPosed 现代 libxposed API 102。**

当前所有代码都建立在这个架构之上，后续版本不再回溯更早的实现方式。

### 模块注册

- 入口由 `META-INF/xposed/java_init.list` 声明，元数据放在
  `src/main/resources/META-INF/xposed/`：
  - `module.prop` — `minApiVersion` / `targetApiVersion` / `staticScope` /
    `exceptionMode` / `autoHotReload`
  - `java_init.list` — 入口类 `com.wb.gszx.GszxModule`
  - `scope.list` — 作用域 `com.guosen.android`
- AndroidManifest **不含任何 `xposed*` meta-data**。
  只要出现 `xposedminversion`，LSPosed 就会把模块判定为 legacy 并去找
  `assets/xposed_init`，导致**模块静默不加载**。这条约束写在
  [扩展指南 · 架构红线](docs/EXTENDING.md) 里，改动前务必先读。

### 依赖

```kotlin
compileOnly("io.github.libxposed:api:102.0.0")        // 框架注入，不可打包
compileOnly("io.github.libxposed:annotation:1.0.0")   // 仅编译期
implementation("io.github.libxposed:service:102.0.0") // 模块 App 侧服务通道
```

`api` 必须是 `compileOnly`：一旦打进 APK，LSPosed 会拒绝加载并报
「The Xposed API classes are compiled into the module's APK」。

### 架构

- **入口 `GszxModule.kt` 只做编排**：`onModuleLoaded` / `onPackageReady` /
  `onHotReloading` / `onHotReloaded`，不写业务逻辑
- **`feature/` 功能层**：`Feature` 接口 + `FeatureEnv` + `FeatureManager`。
  新增功能 = 加一个类 + 在 `FeatureManager.features` 登记一行，不必改入口
- **`core/` 框架无关层**：`GLog` / `ConfigKeys` / `ConfigSnapshot` /
  `ConfigSource` / `HookBag` / `TargetApp`，**不得 import `io.github.libxposed.api.*`**
  （模块 App 进程会 `NoClassDefFoundError`）
- **`app/` 模块自身进程**：`GszxApp` / `FrameworkBridge` / `ModulePreferences` /
  `DebugConfigProvider`
- **`ui/` 设置界面**：Miuix Compose，4 个 Tab（模块 / 功能 / 设置 / 关于）

详见 [架构说明](docs/ARCHITECTURE.md)。

### 配置通道

使用框架托管的 **remote preferences**（跨进程、带变更推送）：

- 目标进程侧 `core/ConfigSource.kt` — `getRemotePreferences()` + 变更监听
- 模块 App 侧 `app/ModulePreferences.kt` — REMOTE / LOCAL 双后端 + 一次性迁移
- `app/FrameworkBridge.kt` — 注册 `XposedServiceHelper` 监听，获取 `IXposedService` binder

配置读取在内存中是**不可变快照**，`getView` 热路径零 IO、零轮询。

### 挂钩方式

`hook(Executable).setId(...).intercept(Chain)`；所有 `HookHandle` 统一收进
`HookBag`，支持整体卸载（开关关闭、热重载都靠它）。

### 热重载

`autoHotReload=true`。模块 APK 升级后目标 App 无需重启，设置界面「模块」页
也可手动触发 `XposedService.hotReloadModule`。

---

## 历史版本（传统 Xposed API 时代）

`1.x` 系列基于传统 `IXposedHookLoadPackage` + `assets/xposed_init` 实现，
相关代码已全部移除，此处仅保留版本脉络。

| 版本 | 里程碑 |
|---|---|
| 1.0.0 | 首个可用版本：`AIStkAdapter.getView` 后置染色，`ZXWorkflow.isBelongByStock` 判定自选，Miuix 设置界面 |
| 1.1.0 | 引入 `ContentProvider` 作为配置通道 |
| 1.2.0 | 配置读取增加 `ActivityThread` 反射兜底 |
| 1.3.0 | 配置变更后主动 `notifyDataSetChanged()`；`ConfigProvider.update()` 支持 adb 读写 |
| 1.4.0 | 挂 `Activity.onResume` 即时生效；尝试 `xposedsharedprefs` + chmod 0664 解决跨进程配置 |

> 这段时期的跨进程配置踩坑记录保留在
> [Hook 难点 · 配置通道](docs/HOOK-NOTES.md)，属于仍然有价值的经验。

---

## 未发布

（无）
