# 界面精简（底栏 / 首页 / 行情市场）实现方案

> 目标：把主界面上**用不到的东西去掉、把太高的东西压矮** ——
> 底栏少一格、首页少一条横幅、行情市场页少两条横幅、板块六项压成三项、市场概览整体压矮。
>
> 与项目里其它功能最大的不同：这五个功能**不改交互、不改数据，只改界面结构**，
> 所以实现路线是「**认出现成的视图 → 隐藏 / 压缩**」，而不是「造一个新视图塞进去」。
> 这条路线对资源 id 的依赖低得多，可以大量依赖结构与文案特征。

---

## 1. 五个功能一览

| # | 分区 | 功能（卡片上的名字） | 配置键 | 实现类 |
|---|---|---|---|---|
| 1 | 底栏 | 隐藏理财项 | `dock_clean` | `feature/dock/DockCleanFeature.kt` |
| 2 | 首页 | 隐藏横幅 | `home_banner_hide` | `feature/home/HomeBannerFeature.kt` |
| 3 | 行情市场 | 隐藏滚动横幅与快捷入口 | `market_banner_hide` | `feature/market/MarketBannerFeature.kt` |
| 4 | 行情市场 | 板块只留三项 | `market_sector_compact` | `feature/market/MarketSectorCompactFeature.kt` |
| 5 | 行情市场 | 市场概览压矮 | `market_overview_compact` | `feature/market/MarketOverviewCompactFeature.kt` |

定位常量分别落在 `DockCleanTargets.kt` / `HomeBannerTargets.kt` / `MarketTargets.kt`。

---

## 2. 公共骨架：`feature/uiclean/`

精简类功能有四个共同问题，全部在这里一次性解决：

| 文件 | 解决什么 |
|---|---|
| `UiClean.kt` | **认视图**：视图树遍历、按文案找、按结构判（「一格入口」= 图标 + 文字）、资源 id 候选解析与缓存、**认不出来时把视图树打成文本** |
| `ViewPatcher.kt` | **改视图 + 还原**：隐藏 / 改高 / 按比例压缩 / 均分铺满，改之前先记账，关开关时一键还原 |
| `UiCleanFeature.kt` | **调度**：页面门禁 → 挂全局布局回调 → 分帧重试 → 失配时打一条警告 + 视图树快照 |

### 2.1 什么时候去改界面（最容易做错的地方）

| 时机 | 靠不靠得住 |
|---|---|
| `Activity.onCreate` | ✗ 布局还没 inflate |
| `Activity.onResume` | ✗ 主壳是 Fragment 容器，此刻当前页的视图往往还没挂上 |
| **只挂 `onResume` 一次** | ✗ 用户在底栏切 Tab 时**不会**再走 `onResume`，切过去那一页永远是原样 |
| **`ViewTreeObserver.OnGlobalLayoutListener`** | ✓ 任何一次布局完成都会回调 —— 首屏铺开、切 Tab、异步内容插入，全都覆盖 |

所以 `UiCleanFeature` 用「两条腿」：

```
Activity.onResume ─┬─→ 挂全局布局回调（每个 decorView 只挂一次）
                   └─→ 立刻 applyTo()（从别的页面返回时不用等下一次布局）

全局布局回调 ──节流 300ms──→ applyTo()
```

### 2.2 幂等是硬要求

布局回调**每帧都可能触发**，`applyTo` 里绝不能无脑走整棵树。每个功能都有一个
O(1) 的快速通道：

```kotlin
// 「藏掉某个视图」类：那个视图还在树上且已经是 GONE
if (alreadyHidden(lastHidden?.get())) return true

// 「改容器 / 改子项」类：处理过的容器还在树上
if (stillAttached(lastGroup?.get())) return true
```

用 `isAttachedToWindow` 而不是只判 `visibility`：宿主重建 Fragment 后，
旧的视图对象仍然是 `GONE`，但它已经不在树上了，必须重新认一遍。

### 2.3 「认不出来」时的行为

`applyTo` 返回 `false` = 认不出目标控件，分两条路处理：

| 触发来源 | 处理 |
|---|---|
| `onResume` 那条链 | 分帧重试 10 次（页面异步挂载的等待窗口）；失败**只记一条 `debug`** —— 此刻用户可能还没切到目标页，不该打扰他 |
| **布局回调那条链** | 连续 3 次认不出来，才打**一条**警告 + 一份**当前页面的视图树快照** |

**为什么报告挂在布局回调而不是 `onResume`**：布局回调只在「页面真的在屏幕上、
并且刚刚发生过布局」时触发，所以它打出来的快照**就是用户正看着的那一页**。
挂在 `onResume` 上的话，主壳里切 Tab 不会触发 `onResume`，
打出来的很可能是另一个 Tab 的树，校准价值大打折扣。

报告频率做了三重限制（同一个 `decorView` 上）：间隔至少 4 秒、最多 3 次、
成功一次就清零 —— 既能覆盖「用户先看错页再切过去」，又不会刷屏。

这条日志就是**校准入口** —— 用户不需要 adb、不需要 `uiautomator`，
打开功能、进一次页面、看模块 App「设置 → 查看日志」即可。见 §4。

---

## 3. 五个功能各自的认法

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
```

**为什么要先认网格再找横幅**：首页上「像横幅的块」不止一个（顶部搜索条、
中间的行情卡片都能被宽高判据命中）。用**快捷入口当锚点**，位置关系就被钉死了。

**「像横幅」的判据**（保守优先）：高度 40~200dp、宽度 ≥ 父容器 60%、
里面有 `ImageView`、**有效文案不超过 2 条**。文案一多就不是横幅而是内容区。

> 保守是刻意的：藏错一块内容比不藏严重得多。认不出来就什么都不做。

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

### 4.1 先看日志，不用 adb

1. 模块 App →「功能」页 → 打开对应开关（**关 → 开需要重启一次金太阳**）；
2. 打开「设置 → 日志输出等级」设为「调试」；
3. 重启金太阳，正常进到**目标页面**（首页 / 行情市场 / 底栏所在的主界面），
   在那一页停留 1~2 秒；
4. 模块 App →「设置 → 查看日志」。

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

`tools/uidump.py` / `tools/uifind.py` 是本项目自带的封装（见 `tools/README.md`）。

### 4.3 往哪里加

| 认不出来的东西 | 改哪个文件的哪个数组 |
|---|---|
| 底栏根容器 | `DockCleanTargets.DOCK_ID_CANDIDATES` |
| 首页横幅 | `HomeBannerTargets.BANNER_ID_CANDIDATES` |
| 首页快捷入口 | `HomeBannerTargets.QUICK_ENTRY_ID_CANDIDATES` |
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
| 开关从「开」改成「关」 | **立刻还原**（`ViewPatcher.restoreAll()` + 摘掉布局回调） |
| 宿主版本升级、结构变了 | 最坏结果是「认不出来 → 什么都不做」，不会改错地方 |
| 页面重新创建（切 Tab / 重建 Fragment） | 旧视图 detach → 幂等标记失效 → 自动重新处理 |
| 底栏「理财」被别的方式隐藏 | 找不到那一格 → 本页无事可做（日志会说明） |

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

### 7.4 底栏「均分铺满」为什么改成「量了再说」

实测根容器是 `SingleGridLayout`（等分栅格），**不是 `LinearLayout`** ——
原先「改 `weight` 均分」那条路在真机上根本走不到。去掉一格之后有两种可能：

- 宿主按**可见子项**重算格子宽度 → 自己就铺满了；
- 宿主拿 `getChildCount()` 当列数 → `GONE` 的那格继续占位，留一条空档。

两种实现都很常见，**静态看不出来**。所以 `DockCleanFeature.fillRow` 改成
「**量到空档才动手**」：改完下一帧再量一次验证，最后把结论写进日志 ——
真机跑一次看日志就能知道是哪一种：

```
I GSZXEnhance dock_clean：底栏已铺满（宿主自己重排，不用我们插手）
I GSZXEnhance dock_clean：底栏留了 180px 空档，按宽度重排 5 格（每格 216px，改了 5 处）
I GSZXEnhance dock_clean：底栏已铺满（补救生效，5 格）
W GSZXEnhance dock_clean：底栏去格后仍留 180px 空档，宿主栅格不吃 layoutParams —— 保持原样（不再重试）
```

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
2. **四个目标版块都是 `#rv_markets_hs` 的子项**：`RecyclerView` 的 `LayoutManager`
   不判 `visibility`，只设 `GONE` 会留下等高空档 → 必须用 `ViewPatcher.collapse`
   （高度归零 + `GONE`）连高度一起收。
3. **板块是 3 列 × 2 行共 6 格**：「只留三项」= 留一整行 = 容器限高到
   `SECTOR_KEEP / SECTOR_TOTAL`（0.5）。藏子项没用（`RecyclerView` 子项 `GONE` 照样占位）。

| 常量 | 真机实测值（已排在候选数组最前） |
|---|---|
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
