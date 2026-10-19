# 界面精简（底栏 / 首页 / 行情市场）实现方案

> 目标：把主界面上**用不到的东西去掉、把太高的东西压矮** ——
> 底栏少一格、首页少一条横幅、行情市场页少两条横幅、板块六项压成三项、市场概览整体压矮、
> 行情页顶部的指数概览整块收起。
>
> 与项目里其它功能最大的不同：这六个功能**不改交互、不改数据，只改界面结构**，
> 所以实现路线是「**认出现成的视图 → 隐藏 / 压缩**」，而不是「造一个新视图塞进去」。
> 这条路线对资源 id 的依赖低得多，可以大量依赖结构与文案特征。

---

## 1. 六个功能一览

| # | 分区 | 功能（卡片上的名字） | 配置键 | 实现类 |
|---|---|---|---|---|
| 1 | 底栏 | 隐藏 理财 入口 | `dock_clean` | `feature/dock/DockCleanFeature.kt` |
| 2 | 首页 | 隐藏横幅 | `home_banner_hide` | `feature/home/HomeBannerFeature.kt` |
| 3 | 行情市场 | 隐藏滚动横幅与快捷入口 | `market_banner_hide` | `feature/market/MarketBannerFeature.kt` |
| 4 | 行情市场 | 板块显示单行 | `market_sector_compact` | `feature/market/MarketSectorCompactFeature.kt` |
| 5 | 行情市场 | 市场概览高度优化 | `market_overview_compact` | `feature/market/MarketOverviewCompactFeature.kt` |
| 6 | 行情市场 | 隐藏 指数概览 | `market_index_hide` | `feature/market/MarketIndexHideFeature.kt` |

定位常量分别落在 `DockCleanTargets.kt` / `HomeBannerTargets.kt` / `MarketTargets.kt`。

---

## 2. 公共骨架：`feature/uiclean/`

精简类功能有四个共同问题，全部在这里一次性解决：

| 文件 | 解决什么 |
|---|---|
| `UiClean.kt` | **认视图**：视图树遍历、按文案找、按结构判（「一格入口」= 图标 + 文字）、资源 id 候选解析与缓存（启动时预热）、**认不出来时把视图树打成文本** |
| `ViewPatcher.kt` | **改视图 + 还原**：隐藏 / 改高 / 按比例压缩 / 均分铺满，改之前先记账、一键还原；另记一个**改动次数**给首帧屏障当判据 |
| `UiCleanFeature.kt` | 精简类专属的一层：改动一律走 `ViewPatcher`（还原 + 计数现成）、幂等快速通道 `alreadyHidden` / `stillAttached` |
| `feature/PageSyncFeature.kt`（骨架） | **调度**：页面门禁 → 挂布局回调 + 首帧屏障 → 失配时限速（只延迟不丢弃）→ 失配时打一条警告 + 视图树快照。**界面精简五个功能与底栏「智能盯盘」入口共用它**（见 `docs/FEATURE-STOCK-BAR.md` §2.4） |

> 调度与精简无关，所以骨架**不在本目录**：它管的是「改视图的时机 + 首帧屏障 + 失焦自愈」，
> 任何「在宿主页面上动视图」的功能都该用它。

### 2.1 什么时候去改界面（最容易做错的地方）

| 时机 | 靠不靠得住 |
|---|---|
| `Activity.onCreate` | ✗ 布局还没 inflate；而且此刻去拿 `window.decorView` 会把宿主的窗口装饰**提前定型**（宿主之后再 `requestWindowFeature` 就晚了）—— 所以这条**不能用** |
| `Activity.onResume` | ✗ **不能只靠它**：`ActivityThread.handleResumeActivity` 是先 `onResume`、再 `wm.addView(decor)`，此刻整棵树还没 attach，`isShown()` 全是 false、高度全是 0，**什么都认不出来** |
| **只挂 `onResume` 一次** | ✗ 用户在底栏切 Tab 时**不会**再走 `onResume`，切过去那一页永远是原样 |
| **`ViewTreeObserver.OnGlobalLayoutListener`** | ✓ 任何一次布局完成都会回调 —— 首屏铺开、切 Tab、异步内容插入，全都覆盖；**回调发生在这一帧画出来之前**，所以改完还来得及把这一帧拦掉 |
| **`ViewTreeObserver.OnPreDrawListener`** | ✓ 同一次遍历里更靠后（布局之后、绘制之前），用来回答「这一帧能不能画」 |

所以 `UiCleanFeature` 的调度分三层：

```
Activity.onResume ─┬─→ 挂全局布局回调 + 挂首帧屏障（每个 decorView 只挂一次）
                   └─→ 立刻 applyTo()（从别的页面返回时不用等下一次布局）

全局布局回调 ──→ applyTo()（两道限速闸都**只延后不丢弃**：本轮有改动 → 200ms 内
                            汇总成一趟；反复认不出来 → 限速到 300ms）

首帧屏障(OnPreDraw) ──→ 「自上一帧以来界面被动过」就 return false，这一帧不画
```

**为什么 `onResume` 挂得上监听器**：此刻 `decorView` 还没 attach，`View.getViewTreeObserver()`
返回的是一份「游离」的 observer；`View.dispatchAttachedToWindow` 会把它**合并**进 attach 后
那一份，所以监听器一个不丢，而且**必定早于第一次布局**。

### 2.2 首帧屏障：为什么「挂钩子早」还不够

挂钩子早只保证「改动来得早」，保证不了「用户看不见中间态」：

- 精简类功能经常**一拍改不完** —— `DockCleanFeature.fillRow` 是四招状态机，
  每一招都得等下一次布局量过尺寸才知道有没有用，中间连着好几帧界面都处在「改到一半」；
- 宿主的首屏也常常是**分批挂上来**的 —— 主壳先布局，Fragment 内容随后才 add。

`OnPreDrawListener` 恰好落在「布局完成、这一帧还没画」这个唯一可用的节点上，
返回 `false` 就能**取消这一帧的绘制**（`ViewRootImpl` 收到后会重排一次再画）：

```
布局完成 ─► 布局回调改树 ─► 首帧屏障：动过就 return false（这一帧不画）
                                │
                                └─► 重新布局 ─► 再改树 ─► 没再动过 → 放行，画最终结果
```

判据是 `ViewPatcher.mutations`（累计改动次数）与「上一帧观察到的值」是否相等 ——
相等 = 自上一帧以来没人动过界面 = 现在画出来的就是最终结果。

拦帧预算**分两档**（真机实测逼出来的，别拍脑袋改）：

| 档 | 时机 | 上限 | 为什么 |
|---|---|---|---|
| 宽 | 进入页面起 1500ms 内（启动图通常还盖着） | 12 帧 / 1500ms | 实测宿主布局一次要 **100~350ms**，而 `DockCleanFeature` 的四招状态机整体要 **~800ms** —— 只有这段时间亏得起 |
| 紧 | 启动窗口外（用户正看着这一页） | 2 帧 / 120ms | 再多就宁可让他看见一次中间态，也不能让界面「卡住不动」 |

两档都到顶就立刻放行，并进入 800ms 静默期。**绝不把界面冻住。**

> 真机验证（MEIZU 20 / 8.6.0）：冷启动时日志连续出现
> `拦下第 1/2/3/4 帧不画` → `底栏已铺满`，中间没有任何一帧被画出来，
> 也没有走到「预算用尽」的兜底分支 —— 这就是「启动时不再跳一下」的直接证据。

**这条链路就是「启动时不再闪一下」的全部原因**：改动的落地时机成了「第一帧画出来之前」，
而宿主的启动图（以及 Android 12+ 的系统启动窗口）正是**等第一帧画完才退场**的 ——
于是从启动图过渡过去时，看到的直接就是精简后的界面，没有闪烁、没有跳动。

### 2.3 预热：把「查 id」挪到启动图那会儿做

`Resources.getIdentifier` 是**按名字扫资源表**，不便宜。所以每个页面 resume 时
（第一个 resume 的页面通常就是启动图，那一两秒主线程正好闲着）就把本功能用到的
全部 id 名解析掉（`PageSyncFeature.idNames` → `IdCache.warm`），等真正要改界面时只剩哈希查表。
实测日志（3.2.0 时期，候选数组增删后数字会变）：
`home_banner_hide：预热资源 id 5/28（在 MainAct 上做，省首帧的查表）`。

id 缓存分两层：**命中**写进进程级共享表（id 与配置、与哪个 `Context` 都无关）；
**未命中**只记在当前 `Context` 上 —— 启动早期资源万一还没装配完，
「没查到」不能永久钉死在进程里。

### 2.4 幂等是硬要求

布局回调与首帧屏障**每帧都可能触发**，`applyTo` 里绝不能无脑走整棵树。每个功能都有一个
O(1) 的快速通道：

```kotlin
// 「藏掉某个视图」类：那个视图还在树上且已经是 GONE
if (alreadyHidden(lastHidden?.get())) return true

// 「改容器 / 改子项」类：处理过的容器还在树上
if (stillAttached(lastGroup?.get())) return true
```

用 `isAttachedToWindow` 而不是只判 `visibility`：宿主重建 Fragment 后，
旧的视图对象仍然是 `GONE`，但它已经不在树上了，必须重新认一遍。

### 2.5 「认不出来」时的行为

`applyTo` 返回 `false` = 认不出目标控件，按下面的策略处理：

| 触发来源 | 处理 |
|---|---|
| `onResume` / 限速后的延迟扫描 | 重试，失败**只记一条 `debug`** —— 此刻用户可能还没切到目标页，不该打扰他 |
| **布局回调那条链** | 连续 3 次认不出来，才打**一条**警告 + 一份**当前页面的视图树快照** |

**为什么报告挂在布局回调而不是 `onResume`**：布局回调只在「页面真的在屏幕上、
并且刚刚发生过布局」时触发，所以它打出来的快照**就是用户正看着的那一页**。
挂在 `onResume` 上的话，主壳里切 Tab 不会触发 `onResume`，
打出来的很可能是另一个 Tab 的树，校准价值大打折扣。

报告频率做了三重限制（同一个 `decorView` 上）：间隔至少 4 秒、最多 3 次、
成功一次就清零 —— 既能覆盖「用户先看错页再切过去」，又不会刷屏。

**两道限速闸都是「只延迟不丢弃」**：布局回调是「页面变了」的唯一信号，
扔掉它就要白等下一次布局 —— 早期版本里那个「300ms 节流把关键那一次布局回调吞掉」
就是启动闪烁的第二个根因。现在超速时改成 `postDelayed` 再扫一次，晚到但一定到。

**同一个坑后来又踩了一次，值得记住**：3.2.0 的「动作冷却」（本轮改完视图后 1.5 秒内
把回调**直接丢掉**）正好丢掉了宿主在我们改完之后立刻发的那一趟布局 ——
新建的分页、重建的底栏、补挂的子视图全在那一趟里上来。用户看到的是
「个股详情页左右滑动时不注入『智能盯盘』入口，要停留一会儿才出现」
「底栏『理财』先消失、占位过一会儿才归位」。2026-10-19 起改成：**窗口内进来的回调
汇总成一趟、排到窗口结束时补跑**，窗口本身缩到 200ms（≈12 帧，用户感知不到）——
压爆发的收益来自「同一窗口只跑一趟」，与窗口多长无关。多趟状态机要更快就覆写
`needsFollowUp` 让窗口整体让路（`DockCleanFeature` 的四招重排就是这样）。

这条日志就是**校准入口** —— 不需要 `uiautomator`，也不需要自己找控件：
打开功能、进一次页面、看模块日志即可（目标进程的日志在 LSPosed 的
`/data/adb/lspd/log/`，见 §4）。

> 3.1.0 起模块里没有「查看日志」页了，这条日志从上面那个路径看（adb），
> 或者用「设置 → 日志 → 导出日志」导出成文件再翻。

---

## 3. 六个功能各自的认法

### 3.1 底栏移除「理财」（`DockCleanFeature`）

底栏是「图标在上、文字在下」的一行格子（典型：首页 / 行情 / 交易 / 理财 / 我的）。

```
① 候选 id：DOCK_ID_CANDIDATES
② 结构判据（findBottomRow）：
     · 整行顶边落在屏幕高度 75% 以下
     · 一行 3~6 格，每格都是「有图标 + 有文字」（UiClean.looksLikeEntryItem）
     · 整行高度 ≤ 110dp
③ 在行内**按文案**找「理财」→ 取它「直属于行」的那个子视图 → GONE
④ 若行是横向 LinearLayout：把**宽度相近**的其余格子设成 width=0 + weight=1，重新铺满
```

**为什么不按下标**：不同账号 / 版本底栏项数不一样（有的没有「交易」、有的多一个「基金」），
下标一错就藏错东西。

**为什么不删 Tab**：底栏通常和 `ViewPager` 索引绑死，删掉中间的 Tab 会让索引错位
（点「交易」进「理财」页）。所以**只藏不删**：那一格从界面上消失、点不到，
宿主自己的索引一个都没动。

**为什么均分只在 LinearLayout 里做**：`ConstraintLayout` 里子项本来就在链上，
`GONE` 之后宿主自己会重排，我们插手反而可能把链搞散
（`StockDetailBarFeature` 踩过这个坑，见 `docs/FEATURE-STOCK-BAR.md` §8）。

### 3.2 隐藏首页横幅（`HomeBannerFeature`）

```
① 候选 id：BANNER_ID_CANDIDATES（命中且「像横幅」才动手）
② 结构：
     a. 认「快捷入口网格」——可见、子项 ≥ 6、每项「图标 + 文字」、整块 ≤ 240dp
     b. 从网格往上爬最多 3 层，每层看「网格后面第一个可见兄弟」是不是横幅
③ 认不到网格时先看「在不在首页 Tab」再决定算不算异常（见下）
```

**为什么要先认网格再找横幅**：首页上「像横幅的块」不止一个（顶部搜索条、
中间的行情卡片都能被宽高判据命中）。用**快捷入口当锚点**，位置关系就被钉死了。

**「像横幅」的判据**（保守优先）：高度 40~200dp、宽度 ≥ 父容器 60%、
里面有 `ImageView`、**有效文案不超过 2 条**。文案一多就不是横幅而是内容区。

**认不到网格 ≠ 一定出错**（2026-10-19 修）：首页与行情 / 自选 / 交易 / 账户同属
`MainAct`，**切走之后首页那块视图整棵从树上摘掉** —— 真机实测在行情 Tab 上
`rv_home_func` / `ll_top` / `home_func_icon` 全都查不到，和「宿主改版」在视图树上
一模一样。不区分的话每轮会话都会在用户日志里留 3 条警告 + 3 份视图树快照。
所以先看底栏「首页」那一格（`HOME_TAB_ID_CANDIDATES`，实测 `cb_main_tab_home`）的
选中态：**没选中 = 不在首页 → 返回 `true`（这页本来就没有横幅）**；
选中或判不出来（id 解析不到 / 底栏不在）→ 照旧返回 `false` 交基类重试并打校准快照。

> 保守是刻意的：藏错一块内容比不藏严重得多。认不出来就什么都不做 ——
> 但「认不出来」必须真的是认不出来，不能把「这页没有它」也算进去。

> **同源问题另记**：行情市场那三个功能（`market_*`）也会在**目标被 RecyclerView
> 回收掉、或 `MainAct` 被详情页盖住**时把「这页这会儿没有它」判成「认不出」，
> 一并留警告 + 快照。那是另一条判据（「页面在不在前台 / 目标是不是滚出去了」），
> 待定口径，见 `.work/notes/pending-push.md` 的遗留观察。

### 3.3 隐藏滚动横幅 + 快捷入口（`MarketBannerFeature`）

> 8.6.0 真机实测后的写法见 §7.5 —— 下面是**初版设想**，保留作对照，
> 其中「跑马灯」那条已被实测否掉（真机上那条横幅是 `AdapterViewFlipper` 轮播，
> 页面里没有任何 `ellipsize = MARQUEE` 的文字）。

**滚动横幅**初版设想用「跑马灯」特征认：

```
ellipsize == TextUtils.TruncateAt.MARQUEE && 文案长度 ≥ 8
  → 从这个文字往上找第一个高度 ≤ 60dp 的祖先 = 那条「条」
```

跑马灯在 Android 上只有 `setEllipsize(MARQUEE)` + `setSelected(true)` 这一种标准做法，
比猜 id 可靠得多。要求文案够长是为了滤掉「单行超长文案」的误伤（板块名、股票名）。

**实测后的做法**：按 id（`avf_hotpoint`）取，兜底改成「`ViewAnimator` 家族
（`ViewFlipper` / `AdapterViewFlipper`）里高度不超过 80dp 的那一个」。

**快捷入口**：候选 id 优先（实测 `rv_entra`，用 `item_marktes_entra` 确认），
否则取页面上第一个「每格都是图标 + 文字」的网格。

**两块都要「收起子项」而不是「隐藏自己」**：它们是 `#rv_markets_hs` 的子项，
`RecyclerView` 不判 `visibility`，`GONE` 会留一条等高空档 —— 见 §7.5 第 2 条。

### 3.4 板块六项 → 三项（`MarketSectorCompactFeature`）

> 实测结论：容器是 `LockViewPager` + 内部 `RecyclerView`，6 格是 **3 列 × 2 行**，
> 所以实现收敛成**一条**：对板块容器限高到 `SECTOR_KEEP / SECTOR_TOTAL`。
> 下面那张「三种版式」表是初版设想，只在结构兜底里还留着一点影子。

初版设想过的三种版式，三种压法：

| 容器形态 | 判据 | 压法 |
|---|---|---|
| 六格平铺 | 子视图数 == 6 | 后三项 `GONE`；容器若是固定高，改成 `wrap_content` |
| 两行 × 三列 | 子视图数 == 2，每行 3 格 | 第二行 `GONE`；容器固定高则改 `wrap_content` |
| 可滚动列表 | `AbsListView` / `RecyclerView` | 藏子项没用（一滚就回来），改成**限高**为原来的 3/6 |

实测落在第三行 —— 而且前两行的「藏子项」在 `RecyclerView` 子项上根本不成立
（`GONE` 照样占位），所以现在统一走**限高**。

**结构兜底怎么找容器**：子视图至少 6 个、宽度基本一致、每项都有文案，
并且**排除「图标 + 文字」网格**（快捷入口也是等宽的，但每格都有图标）。

### 3.5 市场概览压矮（`MarketOverviewCompactFeature`）

> 实测后的写法见 §7.5：压的是**图表区**（`fl_content`）与它里面的三张图表控件
> （`zdfb_chart_view` / `dxpg_chart_view` / `jlr_chart_view`），
> **不整块等比** —— 标题行与三张指标卡里是两三行文字，等比压只会把字裁掉。

初版设想：

```
① 候选 id；否则找「子树里有 ≥ 2 个柱状图视图」的最内层容器
② 先把**固定高度**的柱状图按同一比例压一遍
③ 再把整个概览容器压到 0.6 倍
```

**为什么第 ② 步只压固定高度的**：柱状图有两种写法 ——

- **自适应**（`match_parent` / `wrap_content`）：高度跟着容器走，容器压了它就跟着矮，
  再压一次会**双重压缩**，图直接被压扁到看不清；
- **固定高度**（`layoutParams.height > 0`）：不跟容器走，必须单独压。

用 `layoutParams.height > 0` 精确区分，各压一次、不重不漏。

**实测后的第 ③ 步**：不再整块等比，改成
`图表区新高度 = 柱状图 × factor + 图表区其余部分（原样）` ——
这样比例条与涨跌停行不被裁，整块仍然跟着矮一截。

**「柱状图视图」的判据**（结构兜底用）：类名带 `chart` / `bar` / `histogram` / `kline` /
`graph` / `column` / `trend`；或者是**自绘控件**（非 `android.widget.*` /
`android.view.*` 标准控件）且高度 ≥ 60dp、宽度至少是高度的 2 倍。

压缩比例是 `MarketTargets.OVERVIEW_HEIGHT_FACTOR = 0.6f`，改手感就改这一个数。

---

## 4. 真机校准手册（认不出来时照着做）

> 目标 App 是梆梆加固的，静态反编译拿不到 `R.id`，所有资源 id 都只能**运行时**拿。
> 本项目的容错模式是「候选名 + 多级兜底」，所以校准 = **往候选数组里加一项**。

### 3.6 隐藏指数概览（`MarketIndexHideFeature`）

行情 tab（市场 → A股）最上面那一行是**指数概览**（上证 / 深证 / 北证50 …），占 229px。
关掉它，下面的「市场概览」直接顶上，一屏能多看一屏。

**实测结构**（8.6.0）：

```
HorizontalRecyclerView #rv_markets_hs          ← 整页的纵向容器（每个版块 = 一个子项）
  [行1] LinearLayout  0,0-1080,229            ← 指数概览（**本功能收的就是它**）
    RecyclerView #rv_index → LinearLayout #ll_content ×4（上证 / 深证 / 北证50 …）
  [行2] LinearLayout  0,229-1080,925          ★ 市场概览
```

**为什么收「行1」而不是收 `#rv_index`**（与 §3.3 的横幅同一套理由）：

- 版块的内边距都在子项上，只藏 `#rv_index` 会留下行1 那段间距；
- 子项才是 `RecyclerView` 的直接子视图 —— 它的 `LayoutManager` 不判 `visibility`，
  会把所有已挂上的子项照样测一遍摆一遍，只设 `GONE` 照样占地方；
  必须用 `ViewPatcher.collapse`（高度归零 + `GONE`）连高度一起收掉。

行1 没有 id，所以从 `#rv_index` 往上爬到「父级是列表」的那一层（`itemOf`）。
爬不到就退回 `#rv_index` 自己 —— 宁可收得少一点，也别收错。

**怎么认**：先按候选 id 找 `#rv_index`；`#ll_content`（每一项的容器）**只当辅助确认**，
不是必要条件 —— 它是个**通用名**，宿主改版改名（探针 dump 里就有 `ll_content_ma`
这种同族命名）的概率不低，拿它当必要条件等于**整个功能静默失效**。
`#rv_index` 本身已经足够特异（行情页里只有这一处），所以：

- 找到 `#rv_index` 且里面有 `#ll_content` → 用（最可靠）；
- 找到 `#rv_index` 但里面没有 `#ll_content` → **仍然用**，多走一次结构判据确认；
- 连 `#rv_index` 都认不到 → 退回**结构判据** —— 找「横向的、子项 ≥ 3、每项都有文案、
  整块不高于 140dp」的列表，并**排除「图标 + 文字」网格**（快捷入口也是横向多格，见 §3.3）。

认不到就**什么都不做**（返回 `false` 交给基类重试），不猜。

> ⚠️ 这条判据是**有意放宽**的 —— 代价是理论上多了一点认错的空间，
> 收益是不会因为一个辅助 id 改名就整个功能废掉。放宽后仍满足「不认错」的底线：
> `#rv_index` 必须存在 + 必须在**行情市场页**（`onMarketPage` 门禁）+ 必须可见。

### 4.1 先看日志

1. 模块 App →「功能」页 → 打开对应开关（**关 → 开需要重启一次金太阳**）；
2. 重启金太阳，正常进到**目标页面**（首页 / 行情市场 / 底栏所在的主界面），
   在那一页停留 1~2 秒；
3. 看目标进程的日志（在 LSPosed 里，不在 logcat）：

   ```bash
   adb shell su -c "grep GSZXEnhance /data/adb/lspd/log/modules_*.log | tail -50"
   ```

> 「认不出控件」这类快照走 `GLog.w` / `GLog.i`，都是 `INFO` 及以上，
> 默认等级（信息）就能看到，不用先调等级。

如果功能没生效，日志里会出现（注意：**先切到目标页面**再看，快照打的是当前页）：

```
W GSZXEnhance  dock_clean：在 com.guosen.app.goldsun.ui.MainAct 上认不出目标控件（第 1 次报告）——可能是宿主改版，也可能是当前不在目标页
I GSZXEnhance  dock_clean：com.guosen.app.goldsun.ui.MainAct 视图树快照（照着往 *Targets.kt 的候选数组里加 id）
FrameLayout #content [0,0][1080,2205]
  LinearLayout #ll_home_menu [0,320][1080,520]
    ...
  LinearLayout #ll_main_tab [0,2100][1080,2205]
    LinearLayout [0,2100][216,2205]
      ImageView #iv_tab_home [30,2120][90,2180]
      TextView #tv_tab_home "首页" [10,2184][206,2205]
    ...
```

快照里每一行都是 `类名 #资源id名 [左,上][右,下] "文案"`，
**照着把真实的 id 名加进对应 `*Targets.kt` 的候选数组**即可。

### 4.2 需要 adb 时的等价做法

```bash
# 目标页面上，dump 控件树
adb shell uiautomator dump /sdcard/ui.xml
adb pull /sdcard/ui.xml
# 只看底部那一行（底栏）
grep -n 'bounds="\[0,2[01][0-9][0-9]\]' ui.xml
```

`tools/uidump.py` 是本项目自带的控件树解析封装（见 `tools/README.md`）。

### 4.3 往哪里加

| 认不出来的东西 | 改哪个文件的哪个数组 |
|---|---|
| 底栏根容器 | `DockCleanTargets.DOCK_ID_CANDIDATES` |
| 首页横幅 | `HomeBannerTargets.BANNER_ID_CANDIDATES` |
| 首页快捷入口 | `HomeBannerTargets.QUICK_ENTRY_ID_CANDIDATES` |
| 底栏「首页」那一格（判当前 Tab） | `HomeBannerTargets.HOME_TAB_ID_CANDIDATES` |
| 行情市场滚动横幅 | `MarketTargets.MARQUEE_BAR_ID_CANDIDATES` |
| 行情市场快捷入口 | `MarketTargets.QUICK_ENTRY_ID_CANDIDATES` |
| 板块分组容器 | `MarketTargets.SECTOR_GROUP_ID_CANDIDATES` |
| 市场概览分组容器 | `MarketTargets.OVERVIEW_GROUP_ID_CANDIDATES` |
| 页面类名（换了承载页） | 各 `*Targets.kt` 的 `PAGES` |

**加在数组最前面**优先级最高（`firstOf` 按顺序取第一个存在的）。

### 4.4 结构判据失灵时

如果宿主把结构改得认不出来了（例如底栏换成 `ConstraintLayout` + 自定义 View），
调结构判据里的阈值比加 id 更省事：

| 常量 | 位置 | 什么时候调 |
|---|---|---|
| `BOTTOM_BAND_RATIO` / `MAX_BAR_HEIGHT_DP` | `DockCleanTargets` | 底栏更高 / 更靠上时 |
| `MIN_ITEMS` / `MAX_ITEMS` | `DockCleanTargets` | 底栏格数变了 |
| `BANNER_MIN_HEIGHT_DP` / `BANNER_MAX_HEIGHT_DP` | `HomeBannerTargets` | 横幅变高变矮 |
| `MIN_MARQUEE_TEXT` | `MarketTargets` | 跑马灯文案变短了 |
| `MARQUEE_BAR_MAX_HEIGHT_DP` | `MarketTargets` | 「条」变高了（说明往上找过头了） |
| `SECTOR_TOTAL` / `SECTOR_KEEP` | `MarketTargets` | 板块项数变了 |
| `OVERVIEW_HEIGHT_FACTOR` | `MarketTargets` | 想更紧凑 / 更宽松 |

---

## 5. 已知边界

| 场景 | 行为 |
|---|---|
| 开关从「关」改成「开」 | 需要**重启一次金太阳**（LSPosed 在宿主启动时装钩子） |
| 开关从「开」改成「关」 | **立刻还原**（`ViewPatcher.restoreAll()` + 摘掉两个回调） |
| **应用从启动图进入主界面** | 改动在**第一帧画出来之前**落地，且改的过程中那一帧不画 → **看不到未精简的样子，也没有跳动**（见 §2.2） |
| 宿主版本升级、结构变了 | 最坏结果是「认不出来 → 什么都不做」，不会改错地方 |
| 页面重新创建（切 Tab / 重建 Fragment） | 旧视图 detach → 幂等标记失效 → **下一次布局回调自动重新处理**；处理过程中的中间帧同样被首帧屏障拦掉 |
| 宿主把改过的高度 / 宽度改回去 | 幂等标记按「视图还在不在树上」判，改回去不会让标记失效 —— 若真遇到（宿主重排自己的 `LayoutParams`），按各功能自己的 `reassert` 逻辑处理（底栏入口就是这么做的） |
| 底栏「理财」被别的方式隐藏 | 找不到那一格 → 本页无事可做（日志会说明） |
| 宿主在**每帧**都改布局（极端情况） | 连拦到预算上限后放行并静默 800ms —— 宁可漏一次中间态，也不卡帧 |

---

## 6. 不做什么

- **不碰数据**：不读不写任何业务数据，只改 `visibility` 与 `layoutParams.height`。
- **不删 Tab、不动索引**：底栏那一格只是不显示，宿主的 `ViewPager` 索引一个都没变。
- **不注入脚本、不改宿主方法返回值**：所有钩子都是「`proceed()` 之后在视图树上做后处理」。

这几条是刻意的：精简类功能一旦开始「改逻辑」，风险就从「界面不好看」跳到
「业务出错」，不值得。

---

## 7. 8.6.0 真机实测：控件定位基准

> **采集方式**：`adb shell dumpsys -t 60 activity top` —— **`-t` 必须带**，
> 不带的话 ViewDebug 那条 10s 超时会让整份 dump 只剩半页。
> 视图树每行长这样，`app:id/` 后面就是**真实资源名**：
>
> ```
> com.guosen.commonui.LottieTabView{4d311af VFE...C.. ........ 720,0-900,130 #7f1b022b app:id/cb_main_tab_money}
> ```
>
> ⚠️ **别用 `uiautomator dump` 当校准依据**：它会滤掉没有无障碍意义的节点
> （图标 `ImageView`、装饰容器都不出现），而且自定义 View 会被它报成基类 ——
> 实测 `com.guosen.commonui.SingleGridLayout` 被报成 `FrameLayout`。
> `uiautomator` 只适合看**文案与坐标**，真实类名与 id 认准 `dumpsys` 那份。

实测环境：MEIZU 20 / Android 16 / 1080×2400 / 416dpi（`density = 2.6`）/
国信金太阳 8.6.0（versionCode 271）。

### 7.1 底栏（`MainAct`）

| 位置 | 类 | 资源 id | 实测坐标 |
|---|---|---|---|
| 整行容器 | `com.guosen.commonui.SingleGridLayout` | `fl_main_tab` | 0,2205-1080,2335（高 130px ≈ 50dp） |
| 首页 | `com.guosen.commonui.LottieTabView` | `cb_main_tab_home` | 0,0-180,130 |
| 行情 | 同上 | `cb_main_tab_quotes` | 180,0-360,130 |
| 自选 | 同上 | `cb_main_tab_zx` | 360,0-540,130 |
| 交易 | 同上 | `cb_main_tab_trade` | 540,0-720,130 |
| **理财** | 同上 | **`cb_main_tab_money`** | 720,0-900,130 |
| 账户 | 同上 | `cb_main_tab_mine` | 900,0-1080,130 |

每格内部：`LinearLayout` →（`FrameLayout` → `ImageView` + `LottieAnimationView`(GONE)）
+ `TextView`（**文字这一层没有资源 id**，所以「按文案找 + 上溯到直属于行的那一格」
这一招是必需的）。

底栏正好 6 格，卡在 `MAX_ITEMS = 6` 的边界上 —— 宿主再多一格（比如加「基金」），
结构判据就会失效，这时靠 `fl_main_tab` / `cb_main_tab_money` 两个 id 兜住。

### 7.2 首页横幅（`MainAct`）

| 位置 | 类 | 资源 id | 实测坐标 |
|---|---|---|---|
| 快捷入口网格 | `androidx.recyclerview.widget.RecyclerView` | `rv_home_func` | 整块 1080×328（5 列 × 2 行） |
| 快捷入口每一格 | `com.guosen.commonui.BubbleTextView`（用 `drawableTop` 画图标） | `home_func_icon` | 216×150 |
| 骨架占位（GONE） | `com.guosen.commonui.ShimmerView` | `home_func_shimmer` | — |
| **横幅** | `androidx.constraintlayout.widget.ConstraintLayout` | **`layout_banner`** | 39,354-1041,549（1002×195 ≈ 75dp） |
| 横幅里的轮播 | `com.guosen.commonui.view.banner.RecyclingViewPager` | `vp_ad` | 0,0-1002,195 |
| 轮播每页的图 | `androidx.appcompat.widget.AppCompatImageView` | `iv_item_ad` | 1002×195 |
| 轮播每页两条文案 | `androidx.appcompat.widget.AppCompatTextView` | `tv_item_ad_title_top` / `tv_item_ad_title_bottom` | — |
| 页码指示器 | `com.guosen.commonui.view.banner.RecyclingCirclePageIndicator` | `indicator_ad` | 894,169-963,182 |

网格与横幅是**同一个父容器的相邻兄弟**（横幅紧跟在网格后面），
所以「网格当锚点、取它后面第一个像横幅的兄弟」在真机上成立，爬 0 层就够。

### 7.3 与候选数组的对应

| 常量 | 真机实测值（已排在候选数组最前） |
|---|---|
| `DockCleanTargets.DOCK_ID_CANDIDATES` | `fl_main_tab` |
| `DockCleanTargets.REMOVE_ITEM_ID_CANDIDATES` | `cb_main_tab_money` |
| `HomeBannerTargets.BANNER_ID_CANDIDATES` | `layout_banner` |
| `HomeBannerTargets.QUICK_ENTRY_ID_CANDIDATES` | `rv_home_func` |
| `HomeBannerTargets.HOME_ENTRY_ITEM_ID` | `home_func_icon` |

### 7.4 底栏「均分铺满」：四条路只有最后一条通

实测 `SingleGridLayout` **按「子项序号 × 行宽 ÷ 子项数」摆位**，并且：

- **不按可见子项重算** —— `GONE` 的那一格照样占一列；
- **不吃子项的 `layoutParams.width`**；
- **不吃子项的 `leftMargin` / `marginStart`**。

所以「去掉中间那一格、其余铺满」只能靠**改变子项的序号**。四条路的实测结果：

| 招 | 做法 | 结果 |
|---|---|---|
| ① weight 均分 | 横向 `LinearLayout` 才有 `weight` | 容器是 `SingleGridLayout`，这条走不到 |
| ② 改格子宽度 | 5 个可见格子各设 216px | ✗ 无效，布局自己算，仍是 180 |
| ③ 加宽整行 | 行 1080 → 1296（子项 6 ÷ 可见 5） | ⚠️ 前 4 格对了，但序号 5 那格被推到 1080~1296（屏幕外） |
| ③′ 补负边距 | 给序号靠后的格子 −216px 边距挪回来 | ✗ 无效，布局不吃边距 |
| ④ **挪到末尾 + 加宽整行** | 把被藏那格 `removeView` + `addView` 挪到最后一位，行再加宽到 1296 | ✓ **有效**：可见格子序号变成 0~4，各 216px，正好铺满 0~1080；被藏那格落在 1080 之外 |

**④ 的风险与验证**：挪位会改变宿主的「tab 序号 → 页面」映射 —— 如果宿主在点击回调里
用 `indexOfChild` **现算**序号，后面的 tab 就会点错页。实测金太阳 8.6.0 是
**注册时捕获序号**（每个 `LottieTabView` 自己 `clickable`、容器 `SingleGridLayout`
不 `clickable`），所以挪位安全：点「账户」（序号 5 → 4）仍然正常打开账户页。
换宿主版本要重新验这一步。

`DockCleanFeature.fillRow` 就是按这张表写的状态机：**每一步改完都等下一帧量一次**，
成了就收手、不成再试下一招，四招都不灵就用 `ViewPatcher.restore()` 逐项退回原样，
只记一条警告、不再重试 —— 免得每帧都改一遍布局。

**还踩过一个测量坑**：判断「有没有铺满」必须用**可见格子的宽度之和**，
不能用「最左到最右的跨度」（`max(right) − min(left)`）—— 去掉中间那格之后，
剩下的格子仍然从 0 铺到 1080，跨度是满的，中间却空着一格（180px）。
第一版就是被这个跨度判据骗了，日志里写着「宿主自己重排，不用我们插手」。

### 7.5 行情市场（行情 tab → **A股** 子页的顶部）

> ⚠️ **采集教训**：这一页最初怎么导都导不到，因为**recyclerView 已经被滚下去了** ——
> 顶部那几块（市场概览 / 滚动横幅 / 快捷入口 / 板块）都是 `#rv_markets_hs` 的子项，
> 滑出屏幕就被回收了，视图树里根本不在。**先把页面滑回顶部再导。**

这一页整体是一个横向的 `RecyclerView #rv_markets_hs`，**每个版块是一个子项**：

| # | 版块 | 子项坐标 | 关键控件（id） |
|---|---|---|---|
| 行1 | 指数概览 | 0,0-1080,229 | `rv_index` → `ll_content` ×4（上证 / 深证 / 北证50 …） |
| 行2 | **市场概览** | 0,229-1080,925（696px） | `line_content`（标题 + 成交额 `tv_cje`）、三张指标卡 `ll_header_zdfb` / `_jlr` / `_dxp`、图表区 **`fl_content`**（411px）→ **`zdfb_chart_view`**（柱状图，268px）/ `dxpg_chart_view` / `jlr_chart_view` |
| 行3 | **滚动横幅** | 0,925-1080,1091（166px） | **`avf_hotpoint`**（`AdapterViewFlipper`，39,0-1041,140） |
| 行4 | **快捷入口** | 0,1107-1080,1310（203px） | **`rv_entra`**（0,31-1080,166）→ `item_marktes_entra` ×5 |
| 行5 | 板块标题 | 0,1326-1080,1443 | `tv_top_title` + `tv_header_bk_more` |
| 行6 | **板块** | 0,1443-1080,2002 | `rg_bk`（涨幅榜 / 涨速榜 / 成交额 / 连涨天 / 净流入）+ **`vp_bk_content`**（39,91-1041,559）→ `RecyclerView` 内 **`BKHQOverviewItemView` ×6**，3 列 × 2 行，一格 264×234 |

三条实测结论：

1. **「滚动横幅」是热门专题轮播，不是跑马灯**：它是一个 `AdapterViewFlipper`
   （`#avf_hotpoint`）。用视图标志位扫过全树，这一页**没有任何**
   `ellipsize = MARQUEE` 的 `TextView` —— 初版「找跑马灯文字」那条路在真机上
   永远认不出来，只能按 id。
2. **五个目标版块都是 `#rv_markets_hs` 的子项**：`RecyclerView` 的 `LayoutManager`
   不判 `visibility`，只设 `GONE` 会留下等高空档 → 必须用 `ViewPatcher.collapse`
   （高度归零 + `GONE`）连高度一起收。
3. **板块是 3 列 × 2 行共 6 格**：「只留三项」= 留一整行 = 容器限高到
   `SECTOR_KEEP / SECTOR_TOTAL`（0.5）。藏子项没用（`RecyclerView` 子项 `GONE` 照样占位）。

| 常量 | 真机实测值（已排在候选数组最前） |
|---|---|
| `MarketTargets.INDEX_OVERVIEW_ID_CANDIDATES` | `rv_index` |
| `MarketTargets.INDEX_ITEM_ID` | `ll_content` |
| `MarketTargets.HOT_BAR_ID_CANDIDATES` | `avf_hotpoint` |
| `MarketTargets.QUICK_ENTRY_ID_CANDIDATES` | `rv_entra` |
| `MarketTargets.QUICK_ENTRY_ITEM_ID` | `item_marktes_entra` |
| `MarketTargets.SECTOR_GROUP_ID_CANDIDATES` | `vp_bk_content` |
| `MarketTargets.SECTOR_ITEM_ID` | `tv_item_bkhq_overview_name` |
| `MarketTargets.OVERVIEW_CHART_AREA_ID` | `fl_content` |
| `MarketTargets.OVERVIEW_CHART_ID_CANDIDATES` | `zdfb_chart_view` / `dxpg_chart_view` / `jlr_chart_view` |

### 7.6 顺带记下：另外几个页面的实测情况

同一趟把所有页签都导了一遍，结论对以后新增功能有用：

- **`MarketOverviewAct`（市场概览独立页）整页是 H5**：native 层只有
  `common_top` + `webView_market`（`HybirdWebView`），大盘走势与涨跌分布都在网页里 ——
  按块精简无从下手（模块不注入脚本），所以它**不在** `MarketTargets.PAGES` 里。
- **`HSBKMainAct`（板块）**：热门 / 行业 / 概念 / 风格 / 地区 + 涨幅榜 Top10 列表。
- **行情 → 美股**：`ll_us_index`（道指 / 纳指 / 标普）+ 行业 / 概念板块概览
  （`ll_header_bkhq_overview_category_hy` / `_gn` → `ll_bkhq_category_container`）。
- **行情 → 港股**：`llv_hk_index` + 港股通 / 沪股通 / 深股通 + `rv_hy_list`（行业）/
  `rv_gn_list`（概念）。
- **行情 → ETF**：`rv_home_func` 快捷入口（格子是 `iv_home_func` + `tv_home_func`，
  与首页那个同名不同款）+ `vp_ad` / `indicator_ad` 广告轮播 + 各类 ETF 列表。
- **行情 → 全球**：全球指数 / 基本汇率 + 世界地图 + 6 档 `stk_info_N` ×2 组。
- **自选页**：`index_fs_group_view`（指数分时 `index_fs_chart` + 成交量
  `index_fs_volumn`）+ 自选分组 + `rl_ad` / `avf_ad` 广告条 + `stklv_zxg` 列表。

### 7.7 首轮真机测试踩的四个坑（都是「静态看不出来」的类型）

| # | 现象 | 根因 | 现在的做法 |
|---|---|---|---|
| 1 | 底栏「理财」藏掉后留一条空档 | `SingleGridLayout` 不吃子项宽度 / 边距，只按序号摆位 | 挪到末尾 + 加宽整行，见 §7.4 |
| 2 | 「市场概览压矮」压了 `DecorView` 与 8 个首页视图 | 结构兜底「类名像图表 / 自绘 + 够宽」在**首页**命中了 AppBarLayout、快捷入口网格、广告图 | 删掉结构兜底，**只认 id** |
| 3 | 三个行情功能在首页各报 3 次「认不出目标控件」 | 首页与行情页同属 `MainAct`，功能在非目标页也照样跑 | 加**页面门禁**（`MarketTargets.onMarketPage`），不在这一页就静默返回 |
| 4 | 「已铺满」误报 | 用「最左到最右跨度」判铺满，中间的空档被算进去了 | 改用**可见格子宽度之和**，另加「没有格子被挤出可视宽度」的检查 |

共同点：**四个都没有静态答案，全靠真机日志**。所以模块里那些「认不出来就什么都不做 +
打一份视图树快照」的设计不是保守，是唯一可行的路子 —— 这四条都是照着日志改出来的。

### 7.8 启动闪烁：两条腿加一个节流，都是「看起来对」的错

上线后收到的问题不是「认不出来」，而是**认出来了但太晚**：从启动图进主界面时，
底栏 / 首页横幅先以原样闪一下，再跳成精简后的样子。复盘下来是三个各自看着都合理的决定叠在一起：

| # | 决定 | 为什么看着合理 | 实际后果 |
|---|---|---|---|
| 1 | 挂 `Activity.onResume` 后立刻 `applyTo` | `onResume` 是「页面活了」最自然的信号 | `handleResumeActivity` 是**先 `onResume`、再 `wm.addView(decor)`** —— 此刻树还没 attach，`isShown()` 全 false、高度全 0，**必然认不出来** |
| 2 | 认不出来就 150ms × 10 次分帧重试 | 「等异步内容挂上来」的通用写法 | 重试窗口整体落在**首屏画完之后**，于是「认出来」的那一刻已经来不及了 —— 闪烁就是这么来的 |
| 3 | 布局回调按 300ms 节流，超速直接 `return` | 防止每帧都走一遍整棵树 | 节流**丢的是回调本身**：内容刚挂上来的那一次布局回调被丢掉后，页面不再变化、也就不会再有下一次布局 → 补不上 |

三条合起来就是：**改动能在「正确的时机」被发现，却只能在「错误的时机」被应用**。
改法的关键不是「再早一点挂钩子」（那三条里任何一条单独修都不够），
而是**把「画」这一步也纳进来管**：布局回调改树（它发生在这一帧画出来之前）+ 首帧屏障拦帧
（改到一半的帧不画）。判据用 `ViewPatcher.mutations` 这个累计改动计数，成本是每帧一次整数比较。

> 教训：**「什么时候改」和「什么时候让用户看见」是两件事**。
> 只解决前者，就会出现「改得很早、但用户还是看见了中间态」；
> 把后者显式管起来（哪怕只是拦一帧），整套调度才有确定性的视觉效果。
