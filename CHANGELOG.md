# 更新日志

版本命名遵循 `docs/VERSIONING.md`：**有功能更新进次版本（+0.1.0），没有就进补丁版本（+0.0.1）**。
版本号在 `app/build.gradle.kts` 的 `versionName` / `versionCode` 中维护。

---

## [2.18.0] — 2026-10-16

两件事：设置页移除「热重载目标进程」；备份 / 日志导出文件的前缀统一更名。
有功能增删（设置页少了「框架操作」分组），按 `docs/VERSIONING.md` 走次版本
+0.1.0（2.17.3 的补丁位归零），`versionCode` 严格 +1。

### 设置页移除「热重载目标进程」

「框架操作」分组里只有这一个按钮，整个分组随之消失：

- 它只是个**手动触发**入口，日常用不上 —— 模块 APK 升级时框架会**自动**热重载
  目标进程（`module.prop` 的 `autoHotReload=true` + `GszxModule.onHotReloaded`
  重装钩子），这条能力链原样保留；
- 顺带把 libxposed service 的类型（`HotReloadResult` / `HookedTarget` /
  `XposedService`）从界面层请了出去 —— 此前 SettingsTab 为了这一个按钮拖了
  一串框架类型进来；
- 十一条不再使用的文案（按钮标题 / 摘要 / 八种结果反馈 / 分组标题）从
  `strings.xml`（中 / 英）删除。

### 导出文件前缀更名 `gszx-` → `gsauxiliary-`

备份与日志导出的默认文件名前缀由项目代号 `gszx-` 改为应用名 `gsauxiliary-`，
一眼能认出文件是谁家的：

- 备份：`gsauxiliary-backup-20261016-215939.json`
- 日志：`gsauxiliary-log-20261016-224812.txt`（「复制」的剪贴板标签同步更名）

备份 JSON **内部**的格式标识（`ConfigBackup.FORMAT = "gszx-config"`）**不改**
—— 改了会让旧备份文件的格式校验失败，恢复功能就断了。

## [2.17.3] — 2026-10-16

修复「日志输出等级」开关不生效的问题。无功能更新（纯缺陷修复），按 `docs/VERSIONING.md`
走补丁版本 +0.0.1。

### 日志等级开关真的关得住日志了

**症状**：设置页把「日志输出等级」切到「关闭」，「查看日志」里的日志照记不误；
切回「调试」等档位，行为也没见变化 —— 开关开了关了都不影响记录。

**根因**（两处叠加，都属于模块 App 进程）：

1. **启动期的日志抢在等级应用之前落缓冲。** `GszxApp.onCreate` 里
   `applyLogLevel()` 排在 `ModulePreferences.init()` 之后，而后者经
   `FrameworkBridge.addListener` 的「立即回调一次当前状态」同步触发了
   「配置后端 = LOCAL（框架未连接…）」这条警告 —— 此刻 `GLog.level` 还是默认
   INFO，这条日志**无论用户把等级设成什么都必然记录**。表现就是：哪怕选了
   「关闭」，每次打开模块 App 都会新增一条日志。
2. **历史日志让人误判。** 等级只拦新日志，缓冲里已有的记录全数保留；切到
   「关闭」再打开「查看日志」，仍是满屏旧日志，看起来就像开关没生效。

**改法**：

- `FrameworkBridge.addListener` 的「回调当前状态」从同步调用改为 **post 到主线程**
  —— 订阅方（`GszxApp`）在 `onCreate` 里的收尾初始化（应用日志等级）先跑完，
  回调里的日志才会发出，从此被等级正确过滤；
- `GszxApp.onCreate` 顺序调整：`applyLogLevel()` 提到 `FrameworkBridge.init()`
  之前，等级应用先于一切会产生日志的初始化；
- 设置页把等级选成「关闭」时**顺带清空日志缓冲** ——「关闭 = 不再记录」的直观
  语义就是从现在起是空的（缓冲本来只存内存、进程被杀即失，不金贵）。

目标进程侧无需改动：`GszxModule.install()` 读 `snapshot.logLevel`、配置推送时
`onChanged` 更新等级，这条链本来就是通的。

**验证**：设置「关闭」→ 杀掉模块 App 重开 → 「查看日志」应为空且不再新增；
选回「信息」后新日志恢复记录。

## [2.17.2] — 2026-10-16

无功能更新（纯界面修正 + 规范文档），按 `docs/VERSIONING.md` 走补丁版本 +0.0.1。**本版本只入库、未构建发版。**

### 二级菜单副标题清干净

2.17.1 清的是功能**卡片**上的副标题，二级菜单里还剩了漏网之鱼。「滑动设置」页的
开关挂着**两行**说明 —— 开关自己的动态说明（开：「在个股详情页左右滑动，切换策略
内的其它股票」/ 关：「只显示当前这一只，不能左右滑动」，都是标题「左右滑动切换股票」
的展开写法）+ 卡片底的补充说明（「与行情、自选列表点进去的效果一致」，讲的是实现
效果，属于 NAMING 规则里「不写机制与后果」那一类）。现在**全部删掉**：页面上只剩
分组标题「左右滑动」+ 开关「左右滑动切换股票」，一行是一行。

「染色设置」页跟进：开关的钩子目标行（`AIStkStgDetailAct · tv_item_stk_name`）删掉
—— 类名 / 控件 ID 是开发者才看得懂的东西；页首与页面标题逐字重复的「染色设置」
分组标题一并删掉。高亮颜色卡里随选中色变化的说明行（「琥珀黄：与原版行情页黄名
完全一致」这类）、效果预览卡底的口径说明（「只改名字颜色，涨跌幅不变」）也删了
—— 用户反馈没啥用：颜色名已经写在色块下面，预览的色差本身就摆在眼前。
`preset_color_descriptions` 数组随之清空。

一级页面「滑动设置」入口的副标题（「左右滑动换股」）一并删掉 —— 入口名已经说清
进去做什么，按 `docs/NAMING.md` §3 的规矩不写。

### 关于页去掉开发者向字段

关于页是面向用户的页面，上面却摆着「技术细节」一整组（Hook 入口 / 挂钩点 /
判定依据 / 名称控件 ID / 配置通道），外加「作用范围 com.guosen.android」「目标
框架 LSPosed · 现代 Xposed API 102」—— 用户看不懂，也不会用到。全部请出界面：

- **删**：技术细节整组、作用范围、目标框架、模块名称（与顶栏标题重复）、
  模块版本（概览页已有，同一个信息不在两页各摆一遍）；
- **留**：开发者、GitHub / 项目仓库、开源鸣谢、免责声明；
- 技术档案不丢：`docs/ARCHITECTURE.md`、`docs/HOOK-NOTES.md`、
  `docs/REVERSE-ENGINEERING.md` 里有比界面更全的版本。

`docs/NAMING.md` §3 补一条对应的尺子：开发者向的字段（类名 / 控件 ID / API 版本）
不进任何用户可见界面。

### 概览页状态卡装饰图标收敛

2.17.1 把图标放大到 260dp、内缩 60dp，弧线盖满整条卡片右缘，看着像一条乱入的
曲线，认不出是个对勾。2.17.2 改为**完整显示**：132dp、右侧垂直居中、距右缘 16dp
—— 对 148dp 的卡高上下各留 8dp，对齐 LSPosed 那张「已激活 + 大对勾」卡的原始
版式。图标改画在文字下层（先声明），窄屏 / 大字号下文字延长线碰到图标也只是
0.30 透明度的水印垫底，不会盖字。卡片高度仍是固定的 148dp 不受影响 —— 图标
依旧在 `matchParentSize` 容器里，不参与测量。

### 版本命名规范成文

新增 `docs/VERSIONING.md`：有功能更新 → 次版本 +0.1.0（补丁归零）；没有 → 补丁
版本 +0.0.1；`versionCode` 只做严格 +1 的递增计数。判断口诀：功能页里找得到
新东西 → +0.1.0，找不到 → +0.0.1。`CHANGELOG.md` 头部与 `docs/RELEASE.md`
的发版前检查同步指向该文档。

## [2.17.1] — 2026-10-16

四件事：发版不再附带 debug 包；策略筛选页的筛选文案 / 计数 / 空态；功能卡片里多余的副标题清掉；概览页顶部状态卡两态扩成三态（已激活 / 未激活 / 未连接），配色加深、装饰图标放大到占满卡右缘。

### 发版不再附带 debug 包

**症状**：有用户直接下载 `*-debug.apk` 当日常版本使用。debug 包保留完整日志、且签名
与 release 不同源，长期外流不合适。

**根因**：`tools/publish.py` 每次发版都会构建并上传 release 与 debug 两个包，LSPosed
「更新」页同时列出两者，用户容易点错。

**改法**：
- `tools/publish.py` 只构建 `assembleRelease`，只打包、上传 release 包；
- `.github/workflows/release.yml` 去掉 debug 构建与 debug 红线校验，dist 不再收 debug；
- `docs/RELEASE.md` / `tools/README.md` / 根 `README.md` 同步去掉「发 debug」相关说明。

### 策略详情页筛选股票

**① 筛选条件改名**：`涨幅 / 跌幅 / 自选` → `涨家股票 / 跌家股票 / 自选股票`。
三个开关的语义（互斥类别）没动，只改面板文案与日志。

**② 左上角计数跟着筛选走**：宿主那颗 `#tv_stg_res_num` 只写**全量**（「策略个股15只」），
筛完也不会变 —— 屏幕上只剩 3 行、计数却还写着 15 只，看着就是没筛。
`applyFilter` 每轮把它改写成 `策略个股 保留数/总数`；取消筛选时按宿主格式
（`策略个股N只`）还原，还原前先用正则 `^策略个股 \d+/\d+$` 认「这行是我们写过的」，
宿主自己写的文案一个字都不动。

只在真的改写过时延后一帧（`lv.post`）再拽一次 —— 宿主可能晚于我们写同一颗控件；
数据没到（`full` 空）时什么都不做，免得「策略个股 0/0」闪一下。

**③ 筛到 0 只时用宿主自己的无股票页面**：把 `res/layout/empty_ai_stk.xml`
（一个带图标的 `TextView`，文案「未选出符合条件的股票」）摆到**股票行**那块。

- 这个布局是**从 `base.apk` 里 `aapt2` 扫出来的**：DEX 与自定义资源加密，但
  `resources.arsc` / `res/*.xml` 是明文。注意文案是**布局里内联的**，不在资源表里，
  按资源表搜「未选出」搜不到，只有 `aapt2 dump xmltree` 看得见。
- **不能把整个 `#lvStks` 收起来**（`AdapterView.setEmptyView` 的做法）：策略名称 /
  涨家数 / 标签 / 「策略个股N只 · 勾选加入自选股」**全在列表的 header 里**，
  收掉列表等于把整页的头都抹了 —— 第一版就踩了这个，真机截图一眼看出来。
  改成只压高度：`height` → `WRAP_CONTENT`、`weight` → 0（压成只剩 header），
  空态视图 `0dp + weight=1` 接管让出来的那块；列表的 `visibility` 一概不碰。
- 同时收掉两件配件：**列标题行** `#ll_top`（普通 View，翻 `visibility`）与
  **「说明及风险提示」**（ListView 的 footer，`GONE` 不塌陷，只能 `removeFooterView` 摘下来）。
- 两个真机踩出来的坑：
  1. **列标题行会被宿主显示回来** —— 宿主刷新列表时自己把 `#ll_top` 重设回 `VISIBLE`。
     第一版用「收过了就不再收」的标志位，收一次之后就再没管过。改成按当前 `visibility`
     判断、每轮 `applyFilter` 都重新对齐（幂等）。
  2. **风险提示会闪一帧** —— ListView **只把已经布局过的固定视图挂成子 View**，
     进空态那一刻列表还铺着几十行、footer 在屏外，`getChildAt` 找不到；等它被布局出来
     再摘就晚了。改成**反射 `ListView.mFooterViewInfos`**（`FixedViewInfo.view` 是 public 字段），
     不依赖布局，能在压列表**之前**摘掉；顺序也改成「先摘配件、再压列表」。

> ⚠️ `removeFooterView` 内部会回调 `onChanged()` → 重入 `applyFilter`，
> 所以状态必须**在调用之前**登记，否则重入那一轮会对着同一块再摘一次。

### 功能卡片副标题

`FeatureSpec.switchSummaryRes` 改成**可空**（默认 `null`，卡片上那一行直接不存在）。
副标题的标准是**说清功能塞到了哪**（真机反馈：光看「增加 智能盯盘 入口」不知道入口加在哪），
所以 5 个功能配了位置副标题 —— `直达标准行情`（策略详情点击股票直接跳转详情页）、
`增加 全选 按钮`（策略详情多选栏「取消」按钮左边）、`增加 筛选股票 菜单项`（策略详情
右上角三点菜单里）、`增加 智能盯盘 入口`（详情页底栏「自选」与「更多」之间）、
`隐藏横幅`（首页「快捷入口」下方那条）；其余 7 个删掉或保留 null：分区名 + 标题
已经把话说全的（`隐藏新股申购提醒`、`隐藏理财项`），讲宿主内部怎么腾地方的
（`买卖按钮自动让位`）、讲布局后果的（`其余入口自动均分铺满整行`）一律不写。
文案一句话说完、能不用逗号就不用。

`docs/NAMING.md` §1 从「三条硬规则」扩成**四条**，规则四定稿为
**「副标题说清「塞到了哪」，其余能省就省」**（位置优先 / 不写机制与后果 /
一行说完少用逗号 / 不写「A 自动 B」句式）。

### 概览页状态卡三态

原来只有「已激活 / 未激活」两态，判断条件就一条 `FrameworkBridge.service() != null` ——
「没装 LSPosed」和「装了但模块没启用 / 没勾作用域」全落进「未激活」，看不出区别。

现在分三态（`FrameworkState`）：

| 状态 | 条件 | 颜色 |
|---|---|---|
| 已激活 | 拿到框架服务，且 `scope` 非空、版本 / API 可读 | 绿 `0xFF0E8A52` |
| 未激活 | 拿到框架服务，但 `scope` 为空 / 信息读不出来 | `colorScheme.error`（红） |
| 未连接 | `FrameworkBridge.service()` 为 null | `colorScheme.onBackgroundVariant`（灰） |

**「连上了」不等于「生效了」**：`XposedService.getScope()` 返回空列表，说明框架认得这个
模块、但没把它派到任何应用上 —— 这才是需要用户去 LSPosed 里勾作用域的情况，红色正好
对应。`getScope()` 本身不调 `requireApi`（`javap -c` 确认），任何 API 版本都能调；
真读不到就用 `runCatching` 兜成红态。

灰态标题用新增的 `overview_status_disconnected`（未连接），版本行仍是原来的
`overview_status_no_framework`（未连接到 LSPosed 框架）—— 标题给状态，副行给原因。

### 配色与版式

- **绿加深**：`0xFF1AAE6A` → `0xFF0E8A52`。原来的中绿在浅色卡面上偏亮，标题读起来
  发飘。卡面底色是同一颜色 `alpha = 0.14`，加深之后几乎看不出变化。
- **装饰图标加深**：`alpha` 0.16 → 0.30。0.16 放大之后基本糊在卡面上，看不出弧线。
- **版本号与 API 行同色**：版本行原走 `onBackgroundVariant`，和下一行的
  `onSurfaceVariantSummary` 灰度不一致，看着像两个层级。现在统一用后者。
- **图标再放大并往左上拉**：`StatusCardIconSize` 170dp → 260dp，
  `StatusCardIconInset` 26dp → 60dp（内缩越多，图标中心越往卡片左上靠）。
  卡内露出部分由 111dp 见方增到 190dp 见方，竖向盖满整条卡片右缘。卡片高度仍是
  固定的 148dp —— 图标在 `matchParentSize` 容器里，不参与测量。

### 验证

| 项 | 结果 |
|---|---|
| `assembleRelease` | ✅ 通过 |
| `assembleDebug` | ✅ 通过（只是不再进 Release） |
| `python tools/publish.py --dry-run` | ✅ 只列 `GSAuxiliary_<vc>-<vn>.apk` |
| 筛选面板三行文案 | ✅ 真机 dump：涨家股票 / 跌家股票 / 自选股票 |
| 左上角计数 | ✅ 真机：`策略个股 20/100`；切回不筛 → `策略个股100只` |
| 空态 | ✅ 真机：吉祥物 + 「未选出符合条件的股票」，落在 `[0,830][1080,2189]`，头部一行未动 |
| 空态退出后列表复原 | ✅ 真机：`lvStks` 由 `0,124-1080,1012` 回到 `0,124-1080,2073`，20 行正常 |
| 空态时列标题 / 风险提示 | ✅ 真机 uiautomator：两个节点**全部消失**；切回后 `名称` 回到 `[42,830][397,880]`、风险提示挂回列表 |
| 空态不闪 | ✅ 真机：反射 `mFooterViewInfos` 在压列表前摘掉，肉眼全程看不到 |
| 状态卡三态判定与观感 | ✅ 真机（魅族 20 / Android 16 / LSPosed 2.2.1）逐态实测；图标首轮 200dp/44dp 偏小，二轮调到 260dp/60dp（露出 190dp，占满整条卡右缘）后定稿 |

> 布局扫描方法与 ListView 固定视图的两条结论记在
> [`docs/FEATURE-STG-FILTER.md`](docs/FEATURE-STG-FILTER.md) §5。

---

## [2.17.0] — 2026-10-16

新增一个功能：**隐藏新股申购提醒** —— 登录进入主界面时，不再弹出「今日新股申购提醒」。

### 新增

#### 隐藏新股申购提醒

每次登录后，宿主都会在主界面上弹一层「今日新股申购提醒」。它是个宽版浮层、铺满整个屏幕，
**看着像页面，其实是 `com.guosen.commonui.dialog.IpoAlertDialog`**（继承 `CustomAlertDialog → Dialog`）。
本功能把它拦下，登录后直接进主界面。

**拦在哪**（两道，都不改宿主任何判断逻辑）：

| 位置 | 作用 |
|---|---|
| `MainAct#showSgbDialog()` | 宿主里「申购 / 新股」**专用**的展示方法 —— 拦下它，弹窗连构造都不会发生 |
| `android.app.Dialog#show` | 按类名判 `IpoAlertDialog`，兜住宿主换类名 / 换包路径的情况 |

为什么挂 `Dialog#show` 而不是挂 `IpoAlertDialog` 自己的 `show`：前者是所有弹窗的**唯一汇聚点**，
一次覆盖「子类有没有覆写 `show`」的差别。本项目只能拿到 `declaredMethods`，子类未必覆写它，
挂子类会随宿主改版**静默失效**。

为什么拦 `show` 而不是构造入口 `PopAlert#createIpoAlertDialog`：后者只是「构造」，
让它返回 `null` 会把 NPE 甩回宿主手里；让 `show` 不执行则干净 —— 对象建了、没显示，
宿主的后续流程一切照旧。

同层的 `MainAct#showMsgDialog()` **不能碰** —— 那是通用弹窗分发，别的提醒也走它。

### 验证

| 项 | 结果 |
|---|---|
| `assembleDebug` | ✅ 通过 |
| 真机登录一次（魅族 20 · 金太阳 8.6.0） | ✅ 日志出现「已拦下宿主展示方法 showSgbDialog」，且全程无 `IpoAlertDialog` 的弹窗记录；截图确认直接进主界面 |

> 链路、钩子选型理由与真机校准手册见 [`docs/FEATURE-IPO-DIALOG.md`](docs/FEATURE-IPO-DIALOG.md)。

---

## [2.16.2] — 2026-10-16

修掉「开了筛选之后，从个股详情返回策略详情页，偶尔整列股票刷不出来」。

### 修复

**症状**：筛选生效时点进某只股票，返回策略详情页有概率看到整页空白 —— 一行股票都不显示。
不是必现，返回几次才撞上一次。

**根因**：页面回调（`onResume` / 面板点确定 / 配置变化）原先会遍历 `states` 里的**全部**适配器
去对齐筛选。而 `states` 是按适配器**实例**存的弱表 —— 页面重建或宿主换过适配器之后，
里面会同时留着**旧实例**。照着旧实例装过滤代理，等于把当前 ListView 的数据源换成
一份过期内容；返回详情页时旧实例的数据多半已经被清空，于是整列空掉。
`WeakHashMap` 的遍历顺序不定，所以只在某些时候才撞上。

**改法**（四处不变量一起收紧）：

1. 新增 `liveAdapter()` —— 只认**当前 ListView 真正在用**的那个适配器（是我们的代理就拆包），
   `onResume` / 面板确定 / 配置变化三个入口全部改成只对齐它；
2. `applyFilter` 里加同步保险：传进来的适配器不是当前在用的那个就直接返回，
   旧实例残留的 `notifyDataSetChanged` / `getView` 回调再也动不了列表；
3. `findListView` 的缓存除 `isAttachedToWindow` 外，再校验 `rootView` 就是当前 Activity 的
   `decorView` —— 免得拿到页面重建之前那个已经不在屏幕上的列表；
4. `onResume` 之后再延 600ms 复查一次（`RESUME_RECHECK_MS`）：从个股页返回时宿主通常还在
   重新拉数据，那次对齐什么都装不上；如果数据到位时没走 `notifyDataSetChanged`，
   代理就永远装不回去、列表一直空着 —— 复查一次就补上了，装好了则幂等早退。

### 验证

代码层面定位：问题出在「三个入口遍历了哪些适配器」与「代理认的是哪个适配器」这两处不变量。

| 项 | 结果 |
|---|---|
| `assembleDebug` / `assembleRelease` | ✅ 通过 |
| `verify_apk.py --reject-debug-cert`（两个包） | ✅ 全部 `[OK]` |
| 真机回归（筛选 → 点进个股 → 返回详情页，反复若干次） | ✅ 用户实机确认：列表与筛选结果都正常，未再出现整列空白 |

> **偶发场景本身没法稳定复现**（原先就是"返回几次才撞上一次"），所以这版靠的是把上面
> 四处不变量收紧，而不是"修好了某个确定的复现步骤"。若之后仍偶发，先看模块日志里
> 有没有「暂时没装行点击回映射」那一行，再确认代理是否装在了过期适配器上。

---

## [2.16.1] — 2026-10-16

修掉「策略详情筛选股票」的两处问题：筛完之后有一批股票点不动，以及筛完点进个股详情
左右滑动还会滑出本该被筛掉的股票。

### 修复

#### 一、筛选后有一部分行点不动

**症状**：开启筛选后，列表里有一部分股票点了没反应。筛得越狠越明显 ——
保留三十多只时看着正常，只保留几只时几乎整列都点不动，且点不动的数量随策略股票数变化。

**根因**：上一版是把 `AIStkAdapter.data` 直接替换成筛选后的子集。但宿主
`AIStkStgDetailAct.onItemClick` 是**拿 adapter 的 `getCount()` 做范围校验、
却用宿主自己那份全量列表取值**的。我们做「子集下标 → 全量下标」回映射之后，
凡是「全量下标 ≥ 筛选后行数」的行都会被宿主的范围检查挡掉 —— 不崩、不报错，就是没反应。

真机日志坐实：筛选只剩 1 只时点第一行，我们转发 `position=2`，宿主回调**进来了、
也没抛异常、就是什么都没发生** —— `2-1=1` 恰好卡在 `getCount()=1` 的边界上。

**改法**：不再动宿主的数据。给 ListView 套一层过滤代理（`FilteredAdapter`），
由它对外报子集的行数、内部用全量下标转调原适配器。宿主持有的那个 adapter
一个字节都不改，它的 `getCount()` 仍是全量，校验与取值照旧；行点击那层继续做下标回映射。

#### 二、筛选与「直达标准行情 + 左右滑动」没联动

**症状**：筛选后从结果里点进个股详情，左右滑动还能滑出被筛掉的股票
（实测筛「自选」时能一路滑到一只不在自选里的跌股）。

**根因**：「筛选股票」与「直达标准行情」是两个独立功能，后者构造翻页列表时读的是
宿主塞进 `adapter_key.stockList` 的**全量**，不知道筛选结果。

**改法**：新增 `StgFilterBridge` 作为两者之间的只读通道 —— 筛选侧每次算完结果发布一份，
直达侧在改写跳转 Intent 之前按它把翻页列表收敛成子集、重算被点那只的下标；
筛选关掉或条件清空时通道清空，回到全量。

### 验证

真机：魅族 20 / Android 16 (SDK 36) / LSPosed 2.2.1 / 金太阳 8.6.0 (vc271)，
策略 `100e`（35 只）。

| # | 场景 | 结果 |
|---|---|---|
| 1 | 筛「自选」（保留 30+ 只），点第 2 行「华建集团」 | 打开「华建集团 600629」，与所点行一致 |
| 2 | 筛「跌幅」（保留 3 只），点第 1 行后左滑 4 次 | 思特威-W → 益丰药房 → 华北制药，之后停住不越界 |
| 3 | 不筛选时点击 | 行为无变化 |
| 4 | 筛选结果为 0 只 | 列表为空、无异常，点击不误跳 |

> 第 2 条同时验证了两处改动：`position` 映射正确（点第 1 行落点是第 1 行），
> 翻页列表被收敛到 3 只（日志 `翻页列表按筛选结果收敛 35 → 3`）。

---

## [2.16.0] — 2026-10-16

新增「**手势导航条同色**」：屏幕底部那条小白条不再是一条纯黑 / 纯白，
而是填充与紧邻界面一致的颜色，深色与浅色模式下都融进整体视觉。

### 新增

`feature/navbar/`：`NavBarBlendFeature.kt`（调度）/ `NavBarTint.kt`（取色与上色）/
`NavBarTargets.kt`（调参常量）。方案与验证清单见 `docs/FEATURE-NAV-BAR.md`。

#### 根因：那一块是**宿主的窗口自己画的**

宿主 targetSdk 30、全程非沉浸（Android 15 的「targetSdk 35+ 强制沉浸」也轮不到它），
所以屏幕被切成「状态栏 / 宿主内容 / 导航条」三块，底部那条不属于内容区，
由 `DecorView` 按 `window.navigationBarColor` 自己填。宿主没设这个值，
于是落回主题 `android:windowBackground` —— **深色模式纯黑、浅色模式纯白**。
不是「API 受限」，是宿主压根没做适配，模块侧可以从外部补上。

#### 三件事缺一不可（只改颜色不够）

1. **`window.navigationBarColor` = 取到的颜色** —— 那一块是 `DecorView` 按它填的；
2. **关掉 `navigationBarContrastEnforced`**（API 29+）—— 系统为保证横杠看得见会在
   透明导航条上叠一层半透明遮罩，「观感生硬」的一半来自这里
   （模块自己的设置界面在 2.5.0 踩过同一个坑，见 `docs/UI.md` §11.1）；
3. **按填进去的颜色设 `appearanceLightNavigationBars`** —— 关掉遮罩后横杠画深色还是
   浅色全由它决定，不设就会出现「白横杠压在白底上」。判据是**填进去的颜色**的亮度，
   不是系统深色开关（App 内强制深色的组合下这两者会不一致）。

**三键导航例外**：那一条上有真按键，关掉强制对比度可能让按键看不清，
所以检测到非手势导航时**只改颜色、保留强制对比度**
（模式取系统资源 `android:integer/config_navBarInteractionMode`，2 = 手势）。

#### 取色带画在哪（命门）

```
├──────────────┤ ← decor.height − navBarHeight ← 取色带下沿
│  ██ 3dp 高 ██ │ ← 取的就是这一行的真实颜色
├──────────────┤
│  小白条        │ ← 换成上面取到的颜色
```

`decorView` 是**整窗高度**（导航条那一块也是它的孩子），取色必须先减掉
`navBarHeight`，否则取到的是**自己上一次填进去的颜色**，自我循环、永远不变。

#### 取色三档

| 档 | 手段 | 特点 |
|---|---|---|
| ① 结构合成 | 沿「取色带覆盖链」从 `decor` 逐层下钻，每层背景色按 alpha 合成 | **同步**、零分配、首帧就能给结果 |
| ② 像素校准 | `PixelCopy` 拷内容最后一行真实像素取平均 | **异步**、是屏幕真值，能救渐变 / 图片 / 圆角 |
| ③ 兜底 | 主题 `windowBackground` 当合成底色 | 前两档都拿不到时至少不是黑 / 白硬块 |

- ① 的下钻规则：每层只挑「覆盖取色带、且宽度 ≥ 父容器 55%」的子视图 ——
  底栏一行六格并列，往深钻会取到某一格自己的选中态底色，代表「周边界面」的是**整行**；
- 单个 `Drawable` 取色：`ColorDrawable` 直接取，其余**渲染成 1×1 位图**读那一像素，
  这样 `GradientDrawable` / `ShapeDrawable` / `LayerDrawable` / `StateListDrawable` /
  九宫格都能拿到值，**不用反射各版本里名字还不一样的私有字段**；
- ② 两道闸：节流 2000ms + 每窗口最多 3 次（`PixelCopy` 在部分机型 / 无 backing
  surface 的窗口上恒失败，绝不无限重试）；与当前颜色通道差 ≤ 24 就不落地。

#### 时机

`Activity.onResume`（挂回调 + 预热，此刻 decor 还没 attach，量不出尺寸）→
`OnGlobalLayoutListener`（**真正的落地点**，发生在这一帧画出来之前，所以首帧就是最终颜色；
限速时**只延后不丢弃**）→ `Activity.onConfigurationChanged`（深浅色切换 / 转屏时宿主
不一定重建页面，主动作废缓存重算）。每个 `Activity` 有自己的窗口，所以每个页面各来一遍。

#### 开关归类

进「**显示**」分区（关掉它操作路径不变、钩子是渲染、换个页面照样成立），
出厂默认**关**，无二级菜单。

### 资源 / 版本

- 新增字符串（中英各一份）：`feature_nav_bar_title` / `feature_nav_bar_summary` /
  `feature_nav_bar_switch` / `feature_nav_bar_switch_summary`
- `ConfigKeys.NAV_BAR_BLEND`（`nav_bar_blend`，默认 `false`），已进
  `ALL_KEYS` / `FEATURE_KEYS` / `DEFAULTS`；`ConfigSnapshot` 新增 `navBarBlend`
  字段与指纹乘数 `2671`；`ConfigSource.read()` 补一行
- 版本 2.15.1/vc36 → **2.16.0/vc37**

### 验证结果

- `:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**
- release APK **1 567 896 B**；debug APK 11 121 369 B
- `aapt2 dump badging`：`versionCode='37' versionName='2.16.0'`，label `GSAuxiliary`
- `aapt2 dump strings`：`手势导航条同色` 与 `Match gesture bar color` 两种语言都在
- release dex 里 `feature/navbar/NavBarBlendFeature` 在（`-keep * implements Feature`
  命中），`NavBarTint` / `NavBarTargets` 被 R8 改名内联 —— 关键代码确认还在：
  `PixelCopy` 相关引用 7 处、`OnPixelCopyFinishedListener` 2 处、
  `setNavigationBarContrastEnforced` 3 处、`config_navBarInteractionMode` 1 处
- `dexdump` 红线：`Lio/github/libxposed/api` 定义类 **0** 个（`api` 仍为 `compileOnly`）

### 仍未做

- **真机验证**（当前环境没有 `adb`）：深浅两色 × App 内强制组合四种情况、
  切 Tab / 切页面、冷启动首帧、三键导航、关开关立刻还原 —— 均只过了编译。
  完整清单见 `docs/FEATURE-NAV-BAR.md` §4。
- 不做「真沉浸」（`setDecorFitsSystemWindows(false)`）：会把底栏推到横杠下面、
  与宿主自己的 inset 处理打架、影响键盘与弹层，收益与风险不成比例。
  代价是那条区域仍是**一块纯色**，上方是渐变 / 图片 / 圆角时交界处可能有色差。

---

## [2.15.1] — 2026-10-16

两处修复，都由真机（魅族 20 / Android 16 / 金太阳 8.6.0 vc271）实测定位：
**界面精简在启动图之后会闪一下再跳**、**底栏「智能盯盘」左右翻几次之后会消失**。
两个根因都出在「改视图的时机与自愈」上，所以这一版把调度抽成了公共骨架
`feature/PageSyncFeature.kt`（界面精简五个功能与底栏入口共用）。

### 修复

#### 1. 界面精简：启动时「先原样、再跳一下」

`feature/PageSyncFeature.kt`（新增）/ `feature/uiclean/*`（改）

从启动图进入主界面时，底栏 / 首页 / 行情市场那五个开关会出现「组件先以未精简的样子
闪一下、再跳成精简后的样子」。两个根因，都出在**注入时机**上：

1. 只有 `Activity.onResume` 一条腿。而 `ActivityThread.handleResumeActivity` 是
   **先调 `onResume`、再 `wm.addView(decor)`** —— 此刻整棵树还没 attach，
   `isShown()` 全是 false、高度全是 0，**什么都认不出来**。于是只能退回
   「150ms 分帧重试」，改动落地的时机被推到了首屏画完之后。
2. 布局回调那条链按 300ms 节流，而节流是**直接丢弃**那次回调 ——
   「内容刚挂上来、页面真的要变了」的那一次布局回调经常正好被丢掉，
   要等下一次布局才补上。

改法（详见 [界面精简方案 §2](docs/FEATURE-UI-CLEAN.md#2-公共骨架featureuiclean)）：

- **调度抽成公共骨架 `PageSyncFeature`**（切 Tab / 翻页 / 宿主重建视图都不会走
  `onResume`，所以「什么时候改」必须由布局回调驱动，见第 2 条）；
- **加首帧屏障**：在 `decorView` 上挂 `OnPreDrawListener`，用改动计数判断
  「自上一帧以来界面被动过没有」；动过就 `return false` 把这一帧取消、等布局稳定后
  一次画出来 —— **改动过程中的中间态一帧都不会出现在屏幕上**
  （`DockCleanFeature.fillRow` 那套四招状态机也不再是肉眼可见的连续跳动）。
  拦帧预算分两档：启动窗口内（启动图还盖着）12 帧 / 1500ms，窗口外 2 帧 / 120ms，
  到顶立刻放行并静默 800ms —— **宁可漏一次中间态，也不卡帧**。
- **布局回调改成「只延迟、不丢弃」**：超速时 `postDelayed` 补一次扫描，晚到但一定到。
- **去掉 150ms 分帧重试**：改动由布局回调（发生在**这一帧画出来之前**）驱动，
  加上首帧屏障，落地时机就是「第一帧画出来之前」—— 而启动图（以及 Android 12+ 的
  系统启动窗口）正是等第一帧画完才退场的，所以从启动图过渡过去看到的就是最终状态。
- **资源 id 预热**：每个页面 resume 时先把本功能用到的 id 名解析掉
  （第一个页面通常就是启动图，那一两秒主线程正好闲着），首帧不再背
  `Resources.getIdentifier` 的开销；id 缓存的「命中」改进程级共享、
  「未命中」只记在当前 `Context` 上（启动早期资源没装配完时不会把 miss 永久钉死）。

#### 2. 底栏「智能盯盘」：左右翻几次之后入口就没了

`feature/stockbar/StockDetailBarFeature.kt`（改）

真机 dump 发现**详情页的分页器每页各带一份底栏**（同一个资源 id、
一次 3 页就有 3 份 `#stk_detial_bottom_bar`）。而旧实现：

- 注入用 `decorView.findViewById(barId)` → **永远只注入 DFS 里的第一份**，
  当前显示的是哪一页都一样 → **一翻页就换到另一份，入口自然「没了」**；
- 「找更多 / 找盒子」也用全树 `findViewById` → 拿第一页的「更多」去同步第二页的底栏，
  在「这个盒子不属于这条底栏」处直接失败，**补上重同步也修不好**；
- 只在 `Activity.onResume` 重同步（翻页不走 `onResume`）；
- 记账判据是「我记过这个盒子」而不是「按钮还在不在盒子里」—— 宿主清掉子视图后
  会永远误判成「已经注入过」，于是掉了就再也补不回来。

改法（详见 [底栏入口方案 §2.4](docs/FEATURE-STOCK-BAR.md#24-注入时机布局回调驱动--幂等自愈2151-起)）：

- 改用同一套骨架：**布局回调驱动**，宿主每次重建都必定先布局，于是必定被同步到；
- **找齐所有底栏逐个同步**，新页懒创建时（必然伴随布局）下一次同步补上；
- 「找更多 / 找盒子」一律**限定在那一条底栏范围内**；
- 判据换成**现场**（`liveRecord`）：我们的按钮现在还在不在盒子里。不成立就清掉过期记账
  与自己的容器、重新注入；成立则 `reassert`（盒子宽度被改回去就重新加宽、图标被重置就补回来）；
- 两条硬红线：盒子不能取 `more.parent`（注入后那是我们自己的容器，会导致**一层层套容器** ——
  实测套了 5 层把底栏撑爆），以及「更多」祖先里已有我们的容器时**绝不**再注入。

真机验证：进入详情页后左滑 5 次 + 右滑 3 次，每一次底栏里都查得到「智能盯盘」。

行为兼容：开关、配置键、界面效果一律不变，只是「什么时候生效」变成了
「用户看到之前就已经生效」、以及「被宿主换页/重建之后会自动补回来」。

---

## [2.15.0] — 2026-10-15

两件事：**文案与命名规范统一**（纯文案，功能一个没变），以及新功能 **界面精简**
—— 把主界面上用不到的东西去掉、把太高的东西压矮。

> **对老用户透明**：包名、配置键、存储值、开关状态一律不变，直接覆盖安装即可。

### 变更

#### 一、文案与命名规范统一

开关本身就是「启用」—— 拨动开关这个动作已经表达了「打开」，功能名里再写一遍「启用」是冗余；
整个模块就是「增强」，分区名再挂「增强」是同义反复。本版把所有用户可见文案按这套逻辑重写，
并固化成 [`docs/NAMING.md`](docs/NAMING.md)（以后新增功能一律照它起名）。

##### 1. 功能名：去掉「启用 / 增强」

`values/strings.xml` + `values-en/strings.xml`

| 改前 | 改后 |
|---|---|
| 启用自选股高亮 | 自选股高亮 |
| 启用直达标准行情 | 直达标准行情 |

> 卡片上**用户唯一可见**的是 `switchTitleRes`（`ui/FeatureTab.kt` 的 `FeatureEntry`
> 只渲染它）；`titleRes` / `summaryRes` 只喂搜索 —— 两处都按同一套规则改。

##### 2. 分区名：去掉「增强」

| 改前 | 改后 |
|---|---|
| 策略页增强 | 策略页 |
| 股票详情页增强 | 股票详情页 |
| 显示增强 | 显示 |

分区名只是展示文案（`R.string.category_*`），改名字不影响任何逻辑。

##### 3. 新增类功能：改用「增加 + 内容 + 控件类型」

在原有界面上**新加**一个原本不存在的元素时，名称写清「这是新加的」；
**增强**类（本来就有、只是被改了）仍写名词本身。

| id | 改前 | 改后 | 类型 |
|---|---|---|---|
| `stg_select_all` | 启用「全选」按钮 | 增加 全选 按钮 | 新增 |
| `stg_stock_filter` | 启用「筛选股票」 | 增加 筛选股票 菜单项 | 新增 |
| `stock_bar_entry` | 启用底栏「智能盯盘」入口 | 增加 智能盯盘 入口 | 新增 |

##### 4. 去除多余符号

用户可见文案里的 `「」` 全部去掉，界面元素名直接书写（三段式用半角空格分隔）：

| 改前 | 改后 |
|---|---|
| 底栏「智能盯盘」入口 | 底栏智能盯盘入口 |
| 在策略详情的多选栏里显示「全选」按钮 | 在策略详情的多选栏里显示全选按钮 |

> 这条只管**用户看得见的文案**；`docs/` 与代码注释里的 `「」` 保留 ——
> 那里靠引号区分「界面上的名字」与叙述文字。

##### 5. 新增：`docs/NAMING.md`（文案与命名规范）

- 三条硬规则：名称不写「启用 / 增强」、不用引号、新增元素写
  「增加 + 内容 + 控件类型」。
- 判型方法：**关掉开关看目标 App 少了什么** —— 原来就有的东西变了 = 增强（写名词）；
  原来没有的东西没了 = 新增（写「增加 XX XX」）。
- 附现有功能的对照表与反例速查（本版已补到 **10 个功能、6 个分区**）。
- 从 `docs/EXTENDING.md` 第 4 步、`ui/FeatureCatalog.kt` 顶部注释、
  `docs/UI.md` 功能页一节三处链入，新增功能时不会漏。

##### 6. 同步更新

- `ui/FeatureCatalog.kt`：顶部「分区归属标准」的分区名、`FeatureSpec` 的字段注释
  （点明 `switchTitleRes` 是唯一可见名、`titleRes` 只喂搜索）。
- `docs/UI.md`：功能卡片 mockup 与功能表按新名字重写；功能表补全到 **10 条**
  （原表漏了 `stg_stock_filter` 与 `stock_bar_entry`，本版再补五个精简功能）。
- `docs/EXTENDING.md`、`docs/FEATURE-SELECT-ALL.md`、`docs/FEATURE-STOCK-BAR.md`：
  同步分区名与分区表。
- `docs/release-notes/2.14.0.md` 与更早的 CHANGELOG 段落是**历史记录**，不改。

#### 二、界面精简：五个新开关

五个开关**出厂全部关闭**；新增三个分区「底栏 / 首页 / 行情市场」，排在「股票详情页」之后、
「显示」之前（分区顺序按「具体页面 → 通用外观」排）。

##### 1. 新增：底栏隐藏理财项

`feature/dock/DockCleanFeature.kt` + `DockCleanTargets.kt`（新增）

- 主界面底栏不再显示理财那一格，其余入口**自动均分铺满整行**。
- **只藏不删**：底栏通常和 `ViewPager` 索引绑死，删掉中间的 Tab 会让索引错位
  （点「交易」进「理财」页）。所以那一格只是 `GONE`，宿主自己的索引一个都没动。
- **按文案找那一格**，不按下标 —— 不同账号 / 版本底栏项数不一样。

##### 2. 新增：首页隐藏横幅

`feature/home/HomeBannerFeature.kt` + `HomeBannerTargets.kt`（新增）

- 先认「快捷入口网格」，再取它下方那条横幅藏掉。用网格当**锚点**是关键：
  首页上「像横幅的块」不止一个（顶部搜索条、行情卡片都能被宽高判据命中），
  靠位置关系把它钉死。

##### 3. 新增：行情市场 —— 三个开关

`feature/market/MarketBannerFeature.kt`、`MarketSectorCompactFeature.kt`、
`MarketOverviewCompactFeature.kt` + `MarketTargets.kt`（新增）

- **隐藏滚动横幅与快捷入口**：滚动横幅用「跑马灯」特征认
  （`ellipsize = MARQUEE` + 文案够长），比猜资源 id 可靠；
  快捷入口要求落在横幅**下方**，免得抓到概览图上面那一排。
- **板块只留三项**：三种版式三种压法 —— 六格平铺藏后三项、两行 × 三列藏第二行、
  可滚动列表改成限高；容器是固定高的会一并改成 `wrap_content`，不留半截空白。
- **市场概览压矮**：概览区压到 0.6 倍，柱状图**只压固定高度的那些**
  —— 自适应高度的柱状图跟着容器一起矮，再压一次会双重压缩、图直接被压扁。

##### 4. 新增：`feature/uiclean/` 界面精简公共骨架

`UiClean.kt` / `ViewPatcher.kt` / `UiCleanFeature.kt`（新增）

- **`UiClean`**：认视图的工具箱 —— 视图树遍历、按文案找、按结构判
  （「一格入口」= 图标 + 文字）、资源 id 候选解析与缓存；
  认不出来时能把**整棵视图树打成文本**（含真实资源 id 名与屏幕坐标）。
- **`ViewPatcher`**：隐藏 / 改高 / 按比例压缩 / 均分铺满，**改之前先记账**
  （弱引用键，不泄漏 Activity），关开关时一键还原。
- **`UiCleanFeature`**：页面门禁 + `Activity.onResume` +
  **`ViewTreeObserver.OnGlobalLayoutListener`** 驱动 + 分帧重试。
  为什么不能只挂 `onResume`：主壳是 Fragment 容器，**用户在底栏切 Tab 时不会走
  `onResume`**，切过去那一页永远是原样；全局布局回调能覆盖切 Tab / 异步加载。
  布局回调每帧都可能触发，所以每个功能都有 O(1) 的幂等快速通道。

##### 5. 新增：不用 adb 的真机校准入口

认不出目标控件时，模块会打**一条**警告 + 一份当前页面的视图树快照：

```
W GSZXEnhance  dock_clean：在 com.guosen.app.goldsun.ui.MainAct 上重试 10 次仍没认出目标控件…
I GSZXEnhance  dock_clean：… 视图树快照（照着往 *Targets.kt 的候选数组里加 id）
LinearLayout #ll_main_tab [0,2100][1080,2205]
  TextView #tv_tab_home "首页" [10,2184][206,2205]
```

打开功能 → 进一次页面 → 看模块 App「设置 → 查看日志」，把真实 id 名加进对应
`*Targets.kt` 的候选数组即可。完整手册见 `docs/FEATURE-UI-CLEAN.md` §4。

##### 6. 功能菜单新增三个分区

「底栏」「首页」「行情市场」，排在「股票详情页」之后、「显示」之前。
分区判据与「显示」的分界写进了 `ui/FeatureCatalog.kt` 顶部注释与 `docs/EXTENDING.md`：
**显示 = 通用的观感；底栏 / 首页 / 行情市场 = 只属于那一页的结构调整**。

### 注意

- **命名改动不影响任何东西**：配置键、存储值、功能行为一律不变，老用户的开关状态不受影响。
- 五个新开关**出厂全部关闭**，在模块 App →「功能」页里按需打开。
- 开关从「关」改成「开」后，需要**重启一次金太阳**才生效；
  从「开」改成「关」是**立刻还原**的（含被改过的布局）。
- 本版**没有**破坏性变更：包名、配置项全部不变，直接覆盖安装即可。

---

## [2.14.0] — 2026-10-15

新功能：**个股详情页底栏「智能盯盘」快捷入口** —— 底栏「自选」与「更多」之间多一颗按钮，
一点直达智能盯盘；买 / 卖的宽度自动让位。底栏是自适应的，指数 / 板块那套（「买指数基金」）
同样生效。

### 变更

#### 1. 新增：底栏「智能盯盘」入口

`feature/stockbar/StockDetailBarFeature.kt` + `StockDetailBarTargets.kt`（新增）

- 「智能盯盘」原本埋在底栏「更多」的 `PopupWindow` 面板里（个股页第 2 项、指数页第 1 项），
  点两下才到。本功能把它提到一级位置。
- **布局怎么让位**：底栏那一行是 `ConstraintLayout`（id 却叫 `ll_bottom_stk_normal`），
  买 / 卖 是**带权重的链成员**。做法是**把「更多」那个盒子加宽**
  —— 它是链上的固定宽成员，加宽后买卖按权重自动各让一半；新按钮并排塞进盒子里。
  实测 1080 宽屏：买 321→246、卖 320→245、`fl_more` 96→246。
- **点击怎么走**：不拼 Intent（`url` 里的 `userCode` 是账号维度的），而是
  「触发『更多』→ 在 `PopupWindow` 显示钩子里认领并**把面板设成透明** →
  下一帧按**文字**找到「智能盯盘」项 → `grid.performItemClick(...)`」，
  与用户手点那一项完全同一条路径，且全程看不到面板闪出。
- **自适应**：两套形态的按钮在同一个 `ConstraintLayout` 里靠 visibility 互斥，
  锚点取「当前可见的那个『更多』」，个股 / 指数走同一段代码。
- 图标从面板那一项自带的 `drawableTop` 采集（进程级一次），按「更多」图标的尺寸缩放。
- 关开关 → 把「更多」搬回原位、盒子宽度还原，**立即恢复原版，无需重启目标 App**。

#### 2. 踩过的坑（已写进文档，改代码前先读）

`docs/FEATURE-STOCK-BAR.md` §8

- `ConstraintLayout` 里**不带约束的子视图不参与链的测量** ——
  「插个按钮买卖就自动让位」是错的，实测新按钮被丢在容器左上角、买卖一个像素不让。
- 「克隆约束 + 改接前后邻居」的路子**极脆**：实测一处没接上整条链就散架
  （买卖被压成 0 宽、新按钮和「更多」叠在一起）。
- 面板项**没有可点击祖先**（宿主用的是 `GridView` 的 `OnItemClickListener`），
  必须走 `performItemClick`。
- `setCompoundDrawables` **不设 bounds**，图标画不出来；且面板里的图标比底栏大，
  不缩放会把底栏文字顶掉。
- `StockFragAct` 继承 `EngineAct`，引擎片段**异步挂载**：`onResume` 时底栏还不存在，
  且 `activity.findViewById` 拿不到它 —— 要用 `decorView` + 有限重试。

#### 3. 开发期工具

- `app/DebugConfigProvider.kt`：加 `stock_bar_entry` 列，脚本化开关功能。

#### 4. 修复：点「智能盯盘」会先闪一下黑

- **根因**：宿主弹「更多」面板时会把 Activity 窗口调暗（`LayoutParams.alpha` 1.0 → 0.6），
  而调暗发生在 `popup.show()` **返回之后** —— 弹层本身我们早就设成全透明了，
  但**窗口被调暗的那 2~3 帧照样画出来**，肉眼就是「点下去先黑一下」。
  实测 50fps 录屏：整屏亮度 46.8 → **34.7**（−26%）持续 **7 帧 ≈ 56ms**，再弹回 46.7。
- **修法**：挂 `WindowManagerGlobal.updateViewLayout`（`Window.setAttributes` 与
  `WindowManager.updateViewLayout` 的唯一漏斗），在「我们主动开面板」的那一轮里
  把 `alpha` / `dimAmount` 夹回弹之前的快照值 —— **不让调暗落地**，
  而不是等它画完再还原（事后还原治不了：两条还原点读到的都已经是 1.0）。
- 只在那一轮（约 50ms）生效；用户手点「更多」时一个字节都不碰，原版变暗行为不变。
- 修复后同条件复测：底栏页亮度恒定 46.8，点下去直接进转场，**无任何突变黑帧**。

#### 5. 变更：功能菜单新增「股票详情页增强」分区

- 「底栏『智能盯盘』入口」原本挂在**策略页增强**下 —— 它改的是**个股 / 指数详情页**的
  交互，和策略页没有关系，放错了。
- 新增分区 `category_stock_detail`（`R.string.category_stock_detail`，中英各一条），
  该功能移过去。分区顺序：策略页增强 → 股票详情页增强 → 显示增强
  （「具体页面 → 通用外观」）。
- 同步更新 `ui/FeatureCatalog.kt` 顶部的「分区归属标准」与
  `docs/EXTENDING.md` 第 4 步的「分区怎么选」两处表格。
- **`ConfigKeys` / 配置键 / 存储值全部不变**，只是界面上的分组位置变了，
  老用户的开关状态不受影响。

#### 6. 修复：非详情页也会白跑 6 秒注入重试并打误导性警告

- `Activity.onResume` 是进程级的，H5（`AIStareWebView`）、资讯（`NewsDetailAct`）、
  首页等**每个**页面都会走到注入逻辑：白跑 60 次 × 100ms 的重试，最后打一条
  「重试 60 次仍没等到可注入的底栏，本页放弃」—— 噪音，而且会把真正「详情页注入失败」
  的信号淹没。
- 改为**先按页面类名门禁**（`StockDetailBarTargets.isWatchActivity`，与
  `StgSelectAllFeature` / `StgStockFilterFeature` 同一套写法）：不是
  `com.guosen.quotation.ui.StockFragAct` 就直接跳过，一次重试都不跑。
- 代价：App 若改了这个类名，注入会**静默失效**（不再打日志）。
  已写进 `docs/FEATURE-STOCK-BAR.md` §2.4 与 §5 风险表。

---

## [2.13.0] — 2026-10-15

新功能：**策略详情页「筛选股票」** —— 右上角三点菜单里多一项，点开一个**完全复用目标 App
自带筛选组件 UI** 的面板，用「涨幅 / 跌幅 / 自选」三个开关把不想看的股票行筛掉，
筛选状态存在本地、重启仍在。

### 包名变更（重要）

- **`gsauxiliary.m.venn` → `io.github.SansKritO4.GSAuxiliary`**。
  原因：LSPosed 模块仓库已升级为自动审批模式 —— 新模块的包名须能证明域名控制权，
  否则必须使用 `io.github.{GitHub 用户名}.{模块名}` 前缀（其校验为
  `pkg.toLowerCase().startsWith("io.github." + username.toLowerCase() + ".")`）。
  反向域名的旧包名会被直接判为 invalid。
- **影响面**：改名后是一个**全新的应用**。LSPosed 会把它当作新模块，需重新勾选作用域；
  模块本地配置（免责声明同意状态、桌面图标开关等）会重置。
  2.13.0 之前**未发布过任何 Release**，因此除作者本机外没有存量用户。
- 同步改动：`namespace` / `applicationId`、全部 Kotlin 的 `package` 与 `import`、
  源码目录结构（`java/io/github/SansKritO4/GSAuxiliary/`）、
  `META-INF/xposed/java_init.list` 的入口类、`proguard-rules.pro` 的 keep 规则、
  `DebugConfigProvider` 的 authority（`<包名>.config`）、
  `tools/` 脚本的 `MODULE_PKG`，以及 README / docs 里的全部引用。
- `tools/glog.sh --module` 的过滤串随之由 `gsauxiliary` 改为 **`GSAuxiliary`** ——
  该过滤是**大小写敏感的裸子串匹配**，旧串在新包名里匹配不到。
- CHANGELOG 中 2.12.1 及更早的历史条目**保留旧包名不改**（它们描述的是当时的事实）。

### 变更

- **新增功能 `stg_stock_filter`「策略筛选股票」**（`feature/stgfilter/` 四个新文件 +
  `core/ConfigKeys.kt` / `core/ConfigSnapshot.kt` / `core/ConfigSource.kt` /
  `ui/FeatureCatalog.kt` / `feature/FeatureManager.kt` / `app/DebugConfigProvider.kt` + 中英 strings）。
  总开关登记进 `ConfigKeys.FEATURE_KEYS`，出厂默认 **false**（遵守 2.11.2 的硬约定）。

- **① 三点菜单注入 —— 挂 `PopupWindow` 展示方法，且必须在 `proceed()` 之前改内容**
  （`feature/stgfilter/StgStockFilterFeature.kt`）。
  - 实测：右上角「⋮」弹的是 App 自己 `new` 的 `PopupWindow`，内容就是
    `res/layout/pop_ai_stg.xml` —— 一个垂直 `LinearLayout`，里面**只有两项**：
    `#tv_edit_name`(修改名称) / `#tv_del_stg`(删除策略)，两项都是 `height=0dp + weight=1`。
  - 判定「是不是这个菜单」不看类名（`PopupWindow` 是系统类、App 里到处都在弹），
    只看**内容视图里找不找得到 `#tv_del_stg`**。
  - 新项「筛选股票」与它的分隔线都从**原菜单里的兄弟 View 克隆**（背景、字色、字号、
    字体、内边距、gravity、LayoutParams 逐项复制），插在「修改名称」**下方**。
  - 三个坑（都已修，且都是真机踩出来的）：
    1. **时机**：必须在 `show*` 执行**之前**改内容 —— 一旦 `show` 过了，`PopupWindow`
       已按两项量过尺寸，后加的第三项会被裁掉；
    2. **容器高度**：`inflate(layout, null)` 出来的根 View **没有 `LayoutParams`**
       （`parent` 传 null），改不到高度，得改**每个 TextView 子项**（去 weight、给固定高度
       = `98dp / 2`），再把容器高度设成 `WRAP_CONTENT`；
    3. **窗口高度**：`PopupWindow` 本身是**用固定尺寸 new 出来的**（实测窗口高度恒为 98dp），
       容器改好了也没用 —— 必须同时把 `pop.height` 设成 `WRAP_CONTENT`，
       让窗口按新内容重新量一次（撤销时还原原始 `height`）。

- **② 筛选面板：不是照着截图重画，而是真的 inflate App 自己的布局**
  （`feature/stgfilter/StgFilterPanel.kt`）。
  - 外壳 = `res/layout/dialog_marketfliter.xml`（行情页「沪深京A」左侧漏斗点出来的那个，
    原包把 filter 拼成了 **fliter**，照抄不改）；每一行 = `res/layout/item_market_filter.xml`。
    于是圆角背景、标题字号、副标题灰底块、分隔线、45dp 行高、圆形勾选框、底部两颗按钮
    全部与行情页那颗漏斗**同源**，App 换肤 / 深色模式也跟着走。
  - 改动只有三处：文案（标题「筛选股票」/ 副标题「勾选后对应的条件才会生效」/ 分组头
    「筛选条件」）、`#tv_cancel` 文案 `取消` → **`重置`**（`#tv_ok` 的 `确定` 一字不动）、
    行容器。
  - 行容器原本是 `RecyclerView #rv_markets`，它的 LayoutManager 与 adapter 由 App 代码在
    运行时设 —— 我们只 inflate 布局拿不到那段代码，所以把它**原地换成一个垂直
    `LinearLayout`**：**沿用同一个 `LayoutParams` 实例**，`ConstraintLayout` 的约束一条不丢，
    也省掉了模块编译期对 androidx.recyclerview / constraintlayout 的依赖
    （这两个库都不在模块的 classpath 里）。
  - 分组折叠箭头（`tv3` 右侧那颗无 id 的 ImageView）**隐藏**：原版分组可折叠，
    我们这里只有固定三行，留个点了没反应的箭头是骗人。
  - 面板背景兜底：`?attr/shape_half_top_round_solid_12` 在个别主题下解析不到时，
    自己用 `theme.resolveAttribute` 补一次。
  - 交互：点**整行**即切换该项勾选（与行情页漏斗一致）；「重置」清空全部条件并**立即生效**
    且**不关面板**（关掉就出现「重置完还想再选却得重开」的别扭）；「确定」落盘 + 生效 + 关闭。

- **③ 行过滤：换掉适配器 `data` 字段的内容（真正移除，不是藏起来）**
  （`feature/stgfilter/StgStockFilterFeature.kt`）。
  - ⚠️ **初版走过一条弯路（已废弃，记录在此以免重蹈）**：在 `BaseQuickAdapter.getView`
    返回后把不匹配的行 `visibility = GONE` + 压成 1px。两个问题都实测过：
    ① `ListView.layoutChildren` 直接对子 View 调 `setupChild → child.measure()`
    （不走 `ViewGroup.measureChildren` 那套「跳过 GONE」逻辑），单设 `GONE` 的行
    **照样占 138px** → 界面一整块空白；② 压到 1px 只是治标，
    **宿主一刷新列表就露馅** —— 已布局的行不会重新量，空白与漏筛一起冒出来，**滚动时尤其明显**。
  - 现在改为**直接替换 `BaseQuickAdapter.data` 字段的内容**（实测字段名就叫 `data`，
    且 `getItem(int)` 就是从它取），再走宿主自己的 `notifyDataSetChanged()`：
    ListView 眼里列表本来就"只有这些行"，**不存在占位空间**，滚动 / 翻页都是原生行为。
  - **时序**：挂 `android.widget.BaseAdapter.notifyDataSetChanged` 的**前置**钩子
    （该方法声明在框架类上，`BaseQuickAdapter` 没覆写）—— 宿主每次说"数据变了"，
    我们**先把 `data` 换成筛过的**再放行，于是布局的第一帧就是筛过的结果，不会先闪一下全量。
  - **幂等**：我们自己装进去的那个 `ArrayList` 记在 `AdapterState.filtered`，靠 `===` 区分
    「宿主给的全量」与「我们装的子集」，避免反复包装；`getView` 后置钩子里还有一次
    **延后一帧的兜底补筛**，防宿主只 `setAdapter` 不发通知。
  - ⚠️ **换数据会连带改掉行点击的 `position` 语义**：ListView 的 position 变成**子集下标**，
    而宿主是拿它去索引**自己那份全量列表**（实测：筛到只剩 6 只时点第一行「思特威-W」，
    打开的是全量第一只「新华文轩 601811」）。所以给 ListView 的 `OnItemClickListener`
    包了一层**下标回映射**：按 `AdapterState.keptIndices` 把子集下标还原成全量下标再转发给
    宿主原监听器（`AdapterView.mOnItemClickListener` 是隐藏 API，反射取；
    换算时要让掉 `headerViewsCount`）。
  - 涨跌幅取值：**优先读数据项字段**（实测 `zdf`，`BigDecimal`；`PERCENT_FIELDS` 列候选名），
    读不到再退回行内 `#tv_item_stk_percent` 的文案（`+9.99%` / `-1.45%` / `--`）。
    行内文案只是**绑定时的一次快照**（换数据之后被筛掉的行压根不在列表里，挂 `TextWatcher`
    也放不回来），所以命中兜底的行要等下一次重新 bind 才会修正。
  - 「自选」判定复用与「自选高亮」完全相同的国信门面方法
    `ZXWorkflow.isBelongByStock(code, market)`。
  - **组合规则（互斥类别，按需求 2.0 调整）**：先把每只股票归到**唯一**一个类别，
    再看该类别的开关有没有勾 —— 未勾选的类别，其股票一律不显示：

    ```
    类别(股票) = 自选（在自选股里，最高优先） > 涨幅（zdf > 0）
               > 跌幅（zdf < 0） > 平盘（其余）
    显示(股票) = 没有任何开关勾选 ? true : 类别(股票) ∈ 已勾选的类别
    ```

    - 只勾「涨幅」→ 涨的显示、跌的不显示、**自选里即使涨着也不显示**（自选是另一个类别）；
    - 只勾「自选」→ 只有自选里的；
    - 勾「涨幅 + 自选」→ 涨的（非自选）+ 自选里的；
    - 三个都不勾 → 不筛（全显示）。
    - ⚠️ 平盘股（`zdf == 0` 或读不到）**不属于任何类别**，所以只要开了筛选就看不到它 ——
      这是"未勾选的类别不显示"的直接推论（面板上没有"平盘"这个开关）。

- **④ 筛选状态持久化 → 存目标 App 自己的 `SharedPreferences`**
  （`feature/stgfilter/StgFilterStore.kt`，文件名 `gsaux_stg_filter`）。
  - 为什么**不进模块配置**：筛选面板跑在**目标进程**里，状态必须由目标进程落盘，
    而 libxposed 的 remote preferences 在目标进程里是**只读**的（见 `docs/HOOK-NOTES.md` §7），
    根本写不进去。所以只能写目标 App 的数据目录。副作用是「清除目标 App 数据 / 卸载重装」
    会一并清掉它 —— 属于目标 App 侧状态，因此**不进** `ConfigKeys.ALL_KEYS / DEFAULTS`
    （与 `DISCLAIMER_ACCEPTED` 的处理口径一致）。

- **⑤ 探针升到 v27**（`tools/probe/`）：在 v26 的 `hookHttp()`
  （`okhttp3.OkHttpClient.newCall` + `ResponseBody.string()`）之外，新增**裸 Socket** 与
  **Conscrypt TLS 明文流**两层钩子 —— 动态给 `getInputStream/getOutputStream` 返回的实现类
  挂 `read/write(byte[],int,int)`（沿继承链只挂最具体那一层，避免重复），
  抓包用「时间窗 + 只抓 HTTP 方法开头 / gzip·JSON 响应 + 总量上限」控制日志量。
  **结论（重要）**：金太阳的接口层**不走 OkHttp** —— OkHttp 钩子整段会话 **0 命中**；
  挂到 `ConscryptEngineSocket$SSLOutputStream` 上才抓到明文 `POST … HTTP/1.1`
  （裸 `java.net.SocketOutputStream` 上是 TLS 密文 `17 03 03 …`），
  即**自研 HTTP 客户端 + Conscrypt TLS**。这套钩子与业务无关，换任何 App 都能用。

### 验证结果

真机：魅族 20 / Android 16 (SDK 36) / LSPosed 2.2.1 / 国信金太阳 8.6.0 (vc271)。

- **构建**：`:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**；
  badging 2.13.0 / vc33。
- **任务 ①（菜单项）**：三点菜单 `uiautomator dump` 由 2 项变 3 项，**等高**：
  `修改名称 [815,225][1076,352]` / `筛选股票 [815,355][1076,482]` / `删除策略 [815,485][1076,612]`。
  中途记录：只注入不改高度时每项被压到 77px；只改容器不改窗口时第三项**被整个裁掉**
  —— 两处都已在上面「三个坑」里修掉。
- **任务 ②（面板 UI）**：点「筛选股票」→ 弹出面板，dump 到的结构（标题 / 副标题 / 分组头 /
  三行 + `CheckBox` / 「重置」「确定」两键）与行情页「沪深京A」漏斗**逐项同形**：
  行 `[39,1854][1080,1971]`（45dp）、按钮 `[0,2205][540,2335]` + `[540,2205][1080,2335]`。
- **任务 ③（筛选生效 / 真正移除行）**：取两个策略实测 —— 32 只的（涨 6 / 跌 12 / 自选 14 /
  平盘 0）、9 只的（涨 0 / 跌 0 / 自选 9 / 平盘 0，该策略标的全在自选里）。
  模块日志逐条记录 `筛选股票：全量 N → 保留 M（涨 … / 跌 … / 自选 … / 平盘 …）`：
  - 只勾「涨幅」→ 32 只那组**保留 6**（6 只非自选的涨股）；9 只那组**保留 0**
    —— 正是本次要的效果：**自选股即使涨着，只勾「涨幅」时也不显示**。
  - 只勾「跌幅」→ 保留 12；只勾「自选」→ 保留 14；9 只那组只勾「自选」→ 保留 9。
  - 三个都不勾 → **不筛，全显示**。
  - **滚动到列表末尾不再出现空白**（换数据后 ListView 只认子集，没有占位行 ——
    这正是初版「隐藏行」方案最刺眼的病灶）。
  - 点「重置」→ 三个勾选全清并**立即恢复全量**；关掉总开关 → 立即恢复全量（`onConfigChanged`）。
  - ⚠️ **点击一致性（下标回映射）本轮未复验**：修之前实测「筛到只剩 6 只时点第一行
    「思特威-W」会打开全量第一只「新华文轩 601811」」，现已加 `keptIndices` 回映射，
    但改完之后没有再逐一点过 —— 见下方「待复验」。
- **任务 ④（持久化）**：存储层 `StgFilterStore` 与上一版**完全同源、本次未改动**，
  沿用已验收的「强杀重启后条件仍在」；本轮未重新做一次强杀复验。
- **任务 ⑤（100 只上限的来源）**：抓到策略详情接口的**完整请求体**，
  确认客户端**没有传任何条数 / 分页参数** → **服务端限制**，因此不做「解除 + 模块化」。
  详见下方「遗留问题」。
- ⚠️ 验证期间检测到**另一个会话在并发操作同一台设备**（`.work/` 下出现了非本会话产生的
  `adev.sh` / `adlib.sh` / `u1.xml` / `stk1.xml`，日志里也有连续的 `AIStkStgDetailAct ↔
  StockFragAct` 往返），UI 状态可能被对方改变；本次所有结论都在确认前台页面后取得。

### 待复验 / 已知脆弱点（本次「换数据」改造带来）

把「隐藏行」换成**接管 `adapter.data`** 之后，侵入性比"只改视图"深，风险面也变了。
下面几条**本轮没有逐条复验**，下次上真机时优先看（详见
[`docs/FEATURE-STG-FILTER.md`](docs/FEATURE-STG-FILTER.md) §10）：

| 项 | 说明 |
|---|---|
| **点击下标回映射** | 逻辑已加（`keptIndices`：子集下标 → 全量下标），但改完后**没有逐一点过**。若点开的股票与列表不符，先看这条 |
| **「全选」与筛选叠加** | 「全选」走 `ListView.performItemClick` → 同一个被包裹的监听器 → 记录的是**全量下标**，理论上自洽；但「筛选 + 全选 + 加入自选」这个组合没实机跑过 |
| **宿主改用 `adapter.getItem(position)` 取股** | 回映射是按「宿主自己持有全量列表」这一**实测事实**做的修补；哪天宿主改成从 adapter 取，回映射会**反向错位**（那时应当拆掉映射） |
| **宿主原地改 `adapter.data` 那个对象** | `applyFilter` 里靠 `===` 只能覆盖"宿主换成新列表"这一种；若宿主把 `data` 当自己那份做 `clear()+addAll()` 原地增删，语义会打架。目前未观察到 |
| **涨跌幅兜底** | 数据项 `zdf` 读不到时退回 `getView` 时抓的行内文案**快照**；行情原地刷新后要等下一次重新 bind 才修正 |
| **平盘股** | `zdf == 0` / 读不到 → 归「平盘」，**不属于任何类别**，所以只要开了筛选就不显示它。若期望"三个全勾时平盘也显示"，需要再加一条规则 |

### 遗留问题：`策略详情页 100 只上限` 是客户端还是服务端

**结论：服务端限制。客户端**根本没有**传条数 / 分页参数 —— 所以它不可能"自己只取 100"。**

判定过程（探针 v27 抓到了 `SSLOutputStream` 上的明文 HTTP，见下）：

1. 策略详情页拉个股列表走的是 **HTTPS POST**
   （挂在 `com.android.org.conscrypt.ConscryptEngineSocket$SSLOutputStream` 上抓到明文；
   裸 `java.net.SocketOutputStream` 上抓到的是 TLS 密文 `17 03 03 …`）：

   ```
   POST /gsnews/strategy/queryStrategyQuoteDetail/1.0 HTTP/1.1
   Host: goldsundg.guosen.com.cn
   Content-Type: application/x-www-form-urlencoded; charset=UTF-8
   Accept-Encoding: gzip
   Content-Length: 382

   stId=1569199154113024000&pkg=<包渠道>&reqtime=<ts>&packtm=<ts>&sysVer=8.6.0
   &softName=goldsun_android&netAddr=NA&rNetAddr=&device_model=MEIZU 20&deviceVers=16|36|0
   &operorg=&userCode=<账号>&hwID=NA&conn_style=2.0.03.0.0&session=<会话>&openapi_sign=<签名>
   ```

   **唯一的业务参数就是 `stId`（策略 id）**；其余全是客户端元信息与鉴权。
   **没有** `count` / `limit` / `pageSize` / `pageNo` / `num` 之类的任何条数参数。
   （上面示例里的 `userCode` / `session` / `openapi_sign` 已脱敏，**不要**把真实值写进仓库。）

2. 同一结论在三次独立抓包中一致（16:56:48 / 16:59:52 两次进页面 + 早前一次），
   `Content-Length` 都是 **382**，参数集合完全一致。

3. 旁证（前几轮拿到）：策略列表 → 详情的 `Intent` 里只有
   `adapter_key.stage = Strategy{id, name, tags=[labelId…]}`，**不含股票列表、不含数量**；
   `AIStkStgDetailAct` 的实例字段里也没有任何 100 / count / pageSize 常量。

**推论**：客户端每次都是「把整张列表要回来、全部渲染」，
所以「最多 100 只」只可能由**服务端**（或该接口的服务端分页策略）决定。
客户端侧改代码拿不到第 101 只数据，因此**不做「解除上限」**，也不把它做成功能模块。

> 若哪天服务端放开上限，客户端无需任何改动即可显示更多 —— 这条链路本来就是"给多少显示多少"。

### 附带产出：探针 v27 的两级网络钩子

`tools/probe/` 现在能同时抓 **OkHttp**（`okhttp3.OkHttpClient.newCall` /
`ResponseBody.string()`，实测对金太阳**全程 0 命中** —— 它不用 OkHttp）与
**裸 Socket + Conscrypt TLS 明文流**（动态给 `getInputStream/getOutputStream` 返回的
实现类挂 `read/write(byte[],int,int)`，沿继承链只挂最具体那一层，避免重复）。
抓包用「时间窗 + 只抓 HTTP 方法开头 / gzip·JSON 响应 + 总量上限」控制日志量。
这套钩子与业务无关，**换任何 App 都能用**。

---

## [2.12.1] — 2026-10-15

策略详情页三项增强（直达行情二次点击崩溃修复 / 加入自选黄名返回实时刷新 / 多选栏「全选 · 反选」按钮），
外加「热重载」点击反馈闭环。

### 变更

#### 1. 修复：策略详情页「直达标准行情」二次点击闪退

`feature/strategyquote/StgDirectQuoteFeature.kt`

- 症状：首次点股票正常直达个股详情页；**返回后第二次**点任意股票，闪退回「我的策略」列表页。
- 根因（真机 `logcat -b crash` 坐实）：`adapter_key.stockList` 与宿主 `AIStkStgDetailAct` 的列表适配器
  （`AIStkAdapter`）**是同一个 `ArrayList` 实例**。原实现对这个列表做**原地** `clear() + addAll()`，
  首次点击就把适配器的数据换成了 `IntentStockInfo`；第二次点击时宿主按 `StockInfo` 强转 →
  `ClassCastException: IntentStockInfo cannot be cast to StockInfo`
  （`AIStkStgDetailAct.onItemClick:196`）→ 页面崩溃。
  **「首次正常、第二次必崩」的不对称现象，正是别名污染的特征指纹** —— 若 Intent 契约本身写错，
  第一次就该失败。
- 改法：构造**全新的 `ArrayList`** + 写进 **Bundle 副本**（`Bundle(bundle)`），**绝不改写宿主持有的集合**。
  ⚠️ **只做「复制 Bundle」是不够的**：`Bundle(bundle)` 是**浅拷贝**，内部 map 是新的但每个 value
  仍指向同一个对象 —— 必须**同时**新建列表才真正隔离。存储方式 `putParcelableArrayList` 优先 /
  `putSerializable` 兜底；「关闭左右滑动」分支改走行情侧单只契约（`adapter_key.stockInfo`），
  并清掉副本里残留的 `stockList`。
- **索引落点**改为**转换过程中按对象引用记录**：原实现拿 `codeAndMarket` 字符串去 `converted` 里
  `indexOfFirst`，而 `IntentStockInfo` 并没有 `codeAndMarket` 字段（会退化成 `mStkCode` + `m_stkMarket`
  拼接）；格式不一致时返回 `-1` → 下标被兜底成 `0` → **点第 5 只却打开第 1 只**。
  这类 bug **不崩、不报错**，只静默进错股票，比崩溃更难发现。

#### 2. 修复：加入自选后返回策略详情页，黄名不实时刷新

`feature/watchlist/WatchlistFeature.kt` + `WatchlistTargets.kt`

- 症状：在策略详情页点进个股详情页并加入自选，**返回后名称不变黄**；只有退出策略详情页再重进才变黄。
- 根因：着色只发生在 `BaseQuickAdapter.getView` 的后置钩子里；返回时行视图已存在、`getView`
  不再被调用。而 `hookActivityResume → observeConfig(allowRefresh = true)` 在**配置指纹未变化**时
  直接 `return`，于是根本不触发重绘。
- 改法：`onResume` 时对策略详情页**无条件强制重绘可见行**（遍历 `#lvStks` 的可见子 View，
  重新判定 + `setTextColor`），正确处理 ListView 的 header 位置偏移与 `HeaderViewListAdapter` 拆包；
  不调用 `notifyDataSetChanged`，避免 computing-layout 崩溃。「移除自选还原原色」「关闭开关还原原色」
  由同一条逻辑覆盖。

#### 3. 新增：策略详情页多选栏「全选 / 反选」按钮

`feature/strategyselect/*` + 配置链 + `ui/FeatureCatalog.kt` + 中英 strings，
详见 [`docs/FEATURE-SELECT-ALL.md`](docs/FEATURE-SELECT-ALL.md)。

- 实现方式：**LSPosed 运行时视图注入** —— 在「取消」按钮（`#tv_stg_add_zxg`）**左侧**插入一颗
  **从「取消」克隆**的同款按钮（背景含状态、字色、字号、字体、字距、内边距、对齐、行数、
  最小尺寸、LayoutParams 逐项复制）。
- 触发：给 `#tv_stg_add_zxg` 挂 `TextWatcher`，观察其文案在「勾选加入自选股 ↔ 取消」之间切换 ——
  **不**全局 Hook 超热路径 `TextView.setText`（行情页实时报价会频繁调用）。
- 动作：逐行调用 `ListView.performItemClick(...)`，走宿主自己的行点击监听器 —— 与用户手动点行
  **完全同一条**代码路径，宿主选中模型与底部「已选 N」自然同步，无需知道内部字段。
  - **必须覆盖屏外行**：`index` 从 `0` 走到 `total-1`，屏外行用 `Adapter.getView(pos, null, lv)`
    现造探针 View；**不能**只看 `ListView.childCount`（那只是可见行数）。
  - **必须分帧**：每行之间 `postDelayed(16ms)` —— 宿主每次切换后会刷新列表，同帧复用旧子 View 会失效
    （真机实测踩到过：「反选」只清掉第一行）。
  - 「是否已全选」以宿主维护的底部计数 `#tv_bottom_zxg_num` 为准（含屏外行）；计数明确（0 / 全部）时
    跳过逐行探状态，省掉屏外行的 `getView` 开销。`#ct_item` 解析失败时**保守放弃**并记日志 ——
    拿不到勾选态会把「全选」点成「反选」。
- **文案随状态切换**：未全选显示「**全选**」（点击勾选全部），已全选显示「**反选**」（点击取消全部选中）。
  重算时机：进入多选态、每次行点击（包裹宿主 `OnItemClickListener`）、按钮点击后。
- **与「取消」的间距**：**只加在注入按钮自己的 `rightMargin`（16dp）上，不改「取消」任何属性** ——
  保证「与取消样式一致」这条验收仍成立（间距是两按钮之间的距离，不是按钮内边距）。
- 防抖细节：`minWidth` 用 `paint.measureText` 取「全选 / 反选」较宽者（**不是**取 `src.width` ——
  文案切换途中会拿到旧文案宽度，把按钮撑过头、挤走「取消」）；注入按钮用 `View.generateViewId()`
  拿**独立 id**，避免与「取消」同 id 造成 `findViewById` 歧义与幂等判断失效。
- 新增配置键 `stg_select_all`（**功能总开关，出厂默认关**，登记进 `ConfigKeys.FEATURE_KEYS`）。

#### 4. 其他

- **「热重载目标进程」点击后必定给出结果提示**（`ui/SettingsTab.kt` + 中英 strings）。
  - 症状：目标进程在后台（或一切正常）时点「热重载目标进程」，屏幕上毫无动静，
    无法判断是否重载成功、是否需要重试。
  - 根因：`requestHotReload()` 返回 `String?`，设计上**只有**「没在跑目标进程」这一种情况返回文案，
    成功 / 失败一律 `null` —— 调用方 `?.let { Toast }` 直接跳过。
  - 改法：改为返回**非空** `String`，调用方无条件弹 Toast；文案覆盖 `HotReloadResult.Status` 的全部
    五种终态，另加一档本地「8 秒无回调」超时，以及「服务掉线」与原有的「无目标进程」。新增 6 条字符串
    （中英同步）。回调结果改由 `AtomicReference` 承接，`latch.await()` 的返回值不再被丢弃。
  - `docs/UI.md` 设置页表格、`docs/TROUBLESHOOTING.md` §8 热重载排查表同步为「看 Toast 定位问题」。
- `app/DebugConfigProvider.kt` 增加 `stg_direct_quote` / `stg_quote_swipe` / `stg_select_all` 三列，
  供开发期脚本化开关功能（原先只能开关 `enabled` 等四项）。
- 文档：新增 [`docs/FEATURE-SELECT-ALL.md`](docs/FEATURE-SELECT-ALL.md)；
  [`docs/FEATURE-DIRECT-QUOTE.md`](docs/FEATURE-DIRECT-QUOTE.md) 补 §8（崩溃根因、修法、验证）；
  `README.md` 文件树补 `feature/strategyselect/`。
- `app/build.gradle.kts`：`versionCode 30 → 32`、`versionName 2.11.2 → 2.12.1`。
  中间的 `2.12.0` **未单独发布**，其改动全部并入本次。

### 验证结果

真机：魅族 20 / Android 16 (SDK 36) / LSPosed 2.2.1 / 国信金太阳 8.6.0 (vc271)，模块 **2.12.1 (vc32)**。
被测策略 `100e`，**32 只股票**；列表区 `[0,820][1080,2205]` + 行高 138px ⇒ **一屏约 10 行，22 行在屏外**
（这是检验「是否只选了可见行」的现成样本）。

- **构建与红线**：`:app:assembleDebug` + `:app:assembleRelease` → **BUILD SUCCESSFUL**；
  badging **2.12.1 / vc32**（debug / release 一致）；release dex 中 `Lio/github/libxposed/api`
  定义类 **0**；release manifest 无任何 `xposed*` meta-data；`ui/`、`app/`、`core/` 未 import
  `libxposed.api`，`ui/FeatureCatalog` 未引用 `FeatureManager`。四条红线均未触碰。
- **崩溃修复**：连续点击 **6/6 次**均直达 `StockFragAct`；点第 1/2/3/4/5/6 行，页面分别显示
  新华文轩 601811 / 澳弘电子 605058 / 航天南湖 688552 / 思特威-W 688213 / 华鑫股份 600621 /
  大智慧 601519 —— **与所点行一一对应，无下标偏移**；每次返回都停留在策略详情页；
  `logcat -b crash` 中 `ClassCastException` / `FATAL` 计数 **0**。
- **左右滑动**：开（`stg_quote_swipe=1`）→ 航天南湖 → 左滑 → 思特威-W → 左滑 → 华鑫股份 →
  右滑 → 思特威-W，**顺序与策略列表一致**；关（`=0`）→ 左滑两次仍停在同一只（count=1，不可滑）。
- **黄名实时刷新**（`tools/verify.py` 按像素采样名称主色）：
  - 不在自选的「思特威-W 688213」在个股页「+自选」→ 返回策略详情页（**未退出重进**）：
    `#202030` → **`#F88000`**；
  - 再「删除自选」→ 返回：**还原 `#202030`**。（测试后该股已移出自选，未留痕。）
- **全选 / 反选**：
  - 注入按钮 `[771,664][885,732]` 与「取消」`[927,664][1041,732]` **同为 114×68**，位于其**左侧**，
    间距 **42px ≈ 16dp**；像素采样：两按钮主色均 `#E8E8F8`、文字色均 `#285098`，**样式一致**成立。
  - 点「全选」→ 底部计数 **「已选 32 只股票」= 全部行数**；按钮文案 → **「反选」**；
    滚到列表**最底部**，可见的 13 个 `#ct_item` **全部 `checked=true`** ⇒ **屏外行确实被选中**。
  - 点「反选」→ 计数 → **0**、可见勾选框全部 `checked=false`、文案回「全选」。
  - 反复进出多选态 ×2：退出后按钮数 **0**、重新进入后 **1**（无重复注入）。
  - 关掉 `stg_select_all` → 按钮**即时消失**；重新打开并重进多选态 → 恢复 1 个。
- **未单独回归**：「热重载」那条需在「金太阳在后台」与「金太阳未运行」两种状态下各点一次，
  确认分别弹「已热重载」与「没有正在运行的目标程序」—— 本次未执行。
- **备注**：验证期间目标 App 登录态曾一度失效（跳 `com.guosen.loginmodule.ui.LoginNewAct`），
  等待后自愈；全程**未做任何登录 / 身份认证操作**（遵守项目红线）。
  另记一条已知限制：`FeatureManager` 的配置变化只走 `onConfigChanged`、**不补装钩子**，而功能出厂默认全关 ——
  **在设置里新开开关后必须 force-stop + 重启目标 App** 才会生效（关掉的方向即时生效）。

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
  地址 `https://github.com/MVennn/GSAuxiliary`（新增资源 `about_value_repo_url`）。

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
  并新增可点击跳转的 `https://github.com/MVennn`。

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
