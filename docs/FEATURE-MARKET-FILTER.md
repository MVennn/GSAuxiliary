# 行情页「涨跌与自选」筛选实现说明

> 一句话：行情页「沪深京A」那颗漏斗里**追加**三行（涨家股票 / 跌家股票 / 自选股票），
> 并按它们把排行榜里不匹配的行**收起**；与策略详情页那颗面板共用一套模型、一份状态。
>
> 版本：**2.18.0** · 功能 id：`market_stock_filter` · 目标 App：国信金太阳 **8.6.0**（vc271）
> 实测设备：魅族 20 / Android 16 (SDK 36) / LSPosed 2.2.1
> 相关代码：[`feature/market/`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/market/)（本功能）
> + [`feature/filter/`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/filter/)（**与策略详情页共用**的模型 / 落盘 / 面板工具）
> 姊妹篇：[FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md)（策略详情页那颗面板）

---

## 一、需求与两条边界

1. 行情页「沪深京A」筛选面板里，宿主原本只有**股票类型**一组五行
   （创业板 / 科创板 / 北证A / 未开板次新 / ST股）—— 追加**涨跌与自选**三行
   （涨家股票 / 跌家股票 / 自选股票），文案与语义与策略详情页那颗面板**完全一致**；
2. 这三行要**真的筛掉排行榜里不匹配的股票**（不是只画个勾选框）；
3. 「能合并代码就合并」—— 两块面板的模型 / 落盘 / 行构造 / 语义收进 `feature/filter/`。

两条由用户明确的边界（2026-10-19 真机对话确认）：

- **只筛「外面」已经取回来的那些股票**：排行榜是「沪深京A 涨幅榜前 12 名 + 查看更多」，
  这一屏的数据就是全部（`#rv_markets_hs` 的子项），**不会**为了补足行数去
  从「查看更多」里拉更多股票 —— 所以「跌幅条件 + 涨幅榜」筛完是**空的**，这是预期行为；
- **股票类型那五行仍归宿主**：我们不读也不写宿主的勾选态，点确定时宿主照旧按它自己的
  适配器应用类型过滤 —— 两个维度各管一半，天然取交集。

---

## 二、面板：宿主的 `FilterDiaglog`，我们只**追加**

### 2.1 面板是谁的

真机日志（宿主自己的调用栈）：

```
DLG > com.guosen.quotation.ui.dialog.FilterDiaglog
stack: FilterDiaglog#show:206 <- HSMarketsFrag$9#onTabFliter:745
       <- MarketsHsIndicatorAdapter$MarketsHsIndicatorViewHolder$1#onQuicklyClick:197
```

布局与策略详情页那颗**同一个** `res/layout/dialog_marketfliter.xml`
（`#tv1/#tv2/#tv3/#line1/#rv_markets/#tv_cancel/#tv_ok`，id 常量见
`feature/filter/FilterPanelIds`）。实测结构（`aapt2 dump xmltree`）：

```
ConstraintLayout (match_parent)
 ├─ TextView #tv1  沪深京A排行榜-股票类型   marginStart 25dp
 ├─ TextView #tv2  请在下方勾选您想看的股票类型（灰底块）
 ├─ TextView #tv3  沪深A主板（分组头）      marginStart 25dp
 ├─ ImageView      （分组折叠箭头，30dp）
 ├─ View #line1    （0.5dp 分隔线）         marginStart 15dp
 ├─ RecyclerView #rv_markets  宽=match_parent 高=wrap_content  marginStart 15dp ← 行容器
 ├─ TextView #tv_cancel 取消（0dp 宽 weight=1，50dp 高）
 └─ TextView #tv_ok     确定（同上，蓝底）
```

### 2.2 怎么追加（挂 `Dialog.show` 的**前置**钩子）

```kotlin
hook(android.app.Dialog::class.java 的 show)  ──intercept──▶
    先 maybeInject(dialog)：判定 + 注入            ← 必须在 proceed() 之前
    chain.proceed()                              ← 宿主自己 show
```

**为什么必须前置**：面板一 `show`，窗口就按当时的内容量好尺寸并交给 WindowManager；
之后再 `addView` 会被裁掉 —— 与三点菜单注入同款理由（见 FEATURE-STG-FILTER §三）。

**怎么认「就是这颗面板」**（`Dialog` 是框架类，全 App 都在弹）：

| 判据 | 说明 |
|---|---|
| 内容视图里有 `#rv_markets` **且**有 `#tv_ok` | 只看视图，不看类名 |
| 根视图上**没有** `TAG_OUR_PANEL` | 策略详情页那张面板是同一份布局，靠这个标记区分（我们自己弹的，注入时打的） |

**注入方式**：把宿主的 `#rv_markets` **包**进一个垂直 `LinearLayout`
（`wrapWithVerticalHost`，外层容器沿用 `#rv_markets` 的 `LayoutParams` 实例，
约束一条不丢），再在这个容器里、`#rv_markets` **后面**追加一块：

```
LinearLayout（新容器，吃掉 #rv_markets 的位置与约束）
 ├─ RecyclerView #rv_markets     ← 宿主自己的，adapter / 监听一个字都没动
 └─ LinearLayout（tag=gsaux_market_filter_block）
      ├─ TextView 「涨跌与自选」   ← 克隆 #tv3 的样式（含「左缩进差额」，见下）
      ├─ 行 ×3（涨家股票 / 跌家股票 / 自选股票）← inflate 宿主自己的 item_market_filter
```

⚠️ **左缩进的坑（2.18.0 真机修）**：`LinearLayout` 收编 `#rv_markets` 时会把它的
`ConstraintLayout.LayoutParams`（连同 `layout_marginStart=15dp`）**一起抄成自己的**，
而这份缩进已经被外层容器用过了 —— 不清零就是「缩进 ×2」：宿主的五行整体被推右 42px、
我们追加的三行却停在原位，看上去就是「新增的行贴着屏幕边缘」。
修法两条：① 收编后把 `#rv_markets` 那份**副本**的横向外边距清零；
② 分组头的左缩进取「`#tv3` 的缩进 − 行容器的缩进」的**差额**（10dp），
两块面板共用这条公式。修完实测：宿主分组头 70px、宿主行 70px、我们的分组头 70px、
我们的行 70px，四条对齐。

### 2.3 确定 / 取消

宿主自己的两个按钮上挂着匿名监听（`FilterDiaglog$2` / `$3`），
`setOnClickListener` 会把它**顶掉**（宿主的类型过滤就没了），所以用
`StockFilterPanelUi.wrapClick`：反射取原来的监听（`View.mListenerInfo`）→ 包一层 → 放回去。

| 动作 | 我们的部分 | 宿主的部分 |
|---|---|---|
| 点**整行** | 切换该行勾选（与宿主行一致） | — |
| **确定** | 先按三行落盘（`gsaux_stg_filter`）+ 立即生效，再放行宿主监听 | 宿主按自己的五行应用类型过滤并关面板 |
| **取消** / 点面板外 | **什么都不做**（没落盘，勾选态随对话框一起丢） | 宿主关面板 |

> 「取消 = 整份丢弃」与宿主自己的行同理：面板再次打开时，三行**预勾**的是
> 已落盘的状态（`refreshInjected` 按行名刷新，宿主复用同一个 Dialog 实例也安全）。

---

## 三、排行榜：行是 `#rv_markets_hs` 的子项，筛掉 = **收起**

实测（8.6.0 / vc271）：「沪深京A」版块不是独立列表，**每一行股票都是整页纵向
RecyclerView `#rv_markets_hs` 的一个子项**，行内 id 复用自选股那套：

```
RecyclerView #rv_markets_hs（整页纵向容器：指数概览 / 市场概览 / 滚动横幅 / 快捷入口 / 板块 / …）
  [版块头] #iv_fliter  #tv_hs「沪深京A」  #tv_hot_stk「人气股」  #rg_market_hs_type
  FrameLayout（一行股票）            ← 我们要筛的就是它
    LinearLayout #ll_zxg_container
      TextView #tv_item_zxg_name    名称
      TextView #tv_item_zxg_code    6 位代码
      TextView #tv_item_price       最新价
      TextView #tv_item_zxg_data    涨跌幅（"+20.00%" / "--"）
      TextView #tv_item_zxg_zdf     行业板块（**名字叫 zdf，其实是行业**）
    View #divider_sc_hq
  …
  TextView「查看更多」               ← 固定尾巴，不参与筛选
```

### 3.1 为什么是「收起」而不是换数据

| 方案 | 结论 |
|---|---|
| 换 `MarketsHQAdapter` 的数据 | ✗ 排行榜行与整页其它版块**混在同一个 adapter** 里，动数据等于把整页所有子项的序号都改一遍，宿主的点击 / 刷新逻辑全部悬空 |
| 只设 `GONE` | ✗ `RecyclerView` 的 `LayoutManager` 不认 `GONE`，照样占一条等高空档（同 [FEATURE-STG-FILTER](FEATURE-STG-FILTER.md) §五的结论） |
| **高度归零 + `GONE`** | ✓ 走 `ViewPatcher.collapse`，只动这一个子项 |

### 3.2 何时重算：搭 `UiCleanFeature` 的布局回调车

本功能继承 `UiCleanFeature`（= `PageSyncFeature` 骨架），每趟布局回调做一遍：

```
认页（MarketTargets.onMarketPage）→ 拿 #rv_markets_hs
  → 子项里有 #tv_item_zxg_code 的才算「股票行」（其它是版块，跳过）
  → 该留的：曾被收起就还原（ViewPatcher.restore）
    该筛的：收起（ViewPatcher.collapse）
```

于是：**滚动进来新行会被立刻判一遍**（RecyclerView 复用行视图、宿主刷新数据都覆盖），
判断结果变化时**先拦一帧再画**（首帧屏障），看不到「改到一半」。

判据只读行内那三个 `TextView`：

```
自选 ← ZxWatchlist.isInWatchlist(code, market)   // market 由 6 位代码前缀推（沪深北）
涨跌 ← #tv_item_zxg_data 的文案（"+9.99%" / "--"）
```

按 `StockFilters.keepsDirection`（自选优先、平盘不属于任何类别、三个都不勾 = 不筛）定去留。
**类型维度不参与** —— 那是宿主自己的过滤。

关掉开关：`UiCleanFeature` 的 `restore()` → `ViewPatcher.restoreAll()`，收起过的行全部还原。

---

## 四、共享代码（两块面板一套）

| 文件 | 职责 |
|---|---|
| [`feature/filter/StockFilters.kt`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/filter/StockFilters.kt) | 八项条件的数据类 + 两组语义（`keepsDirection` / `keepsTypes`）+ 选项目录 `FilterRow`（文案、分组、落盘键）+ 日志描述 |
| [`feature/filter/StockFilterStore.kt`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/filter/StockFilterStore.kt) | 落盘（目标 App 自己的 `gsaux_stg_filter`）+ **进程级共享缓存**（谁改谁刷，两块面板不会各看各的） |
| [`feature/filter/StockTypes.kt`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/filter/StockTypes.kt) | 「股票类型」判定：字段优先、代码 / 名称兜底 |
| [`feature/filter/StockFilterPanelUi.kt`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/filter/StockFilterPanelUi.kt) | 面板装配工具：布局 / id 定位、行容器换法（换掉 / 包住）、建行、分组头（含缩进差额）、样式克隆、监听包装 |
| `feature/stgfilter/StgFilterPanel.kt` | 策略详情页那颗面板（八行两组，见 FEATURE-STG-FILTER §四） |
| `feature/market/MarketFilterFeature.kt` · `MarketFilterTargets.kt` | 本功能：注入三行 + 筛排行榜 + 定位常量 |

**状态怎么共享**：`涨跌与自选` 三行是**一份状态**（在哪块面板改，另一处立刻同款）；
`股票类型` 五项在行情侧属于宿主（它自己存），策略侧才是我们存 ——
两边的分组头分别写「沪深A主板」与「股票类型」，读起来不会串。

---

## 五、验收标准与实测结果（2026-10-19 真机，游客态）

| # | 验收项 | 结果 |
|---|---|---|
| 1 | 宿主面板里出现「涨跌与自选」三行，宿主五行与勾选态**原样不动** | ✅ dump：`沪深A主板` + 五行 + `涨跌与自选` + 三行 + 取消/确定 |
| 2 | 四条基准线对齐（宿主分组头 / 宿主行 / 我们的分组头 / 我们的行） | ✅ 全部 70px（修「缩进 ×2」前：宿主行被推到 112px、新增行停在 70px） |
| 3 | 勾「涨家股票」→ 涨幅榜 12 行**全部保留**（榜上全是涨的） | ✅ 日志 `筛选条件已生效（涨家股票）`，行数不变 |
| 4 | 勾「跌家股票」→ 涨幅榜 12 行**全部收起**（只剩版块头 + 查看更多） | ✅ 日志逐行 `收起 排行榜行（跌家股票）`；面板不是「空白」而是整段消失 |
| 5 | 再切回「涨家股票」→ 12 行**全部还原** | ✅ 日志逐行 `已还原 1 处`，行数回到 12 |
| 6 | **取消**丢弃改动：面板重开仍是旧勾选态、列表不动 | ✅ 无日志、行数不变；重开面板勾选态未变 |
| 7 | 关掉功能开关：面板恢复宿主五行、收起过的行还原 | ✅ 关开关后面板里没有「涨跌与自选」，宿主五行照旧 |
| 8 | 落盘：`gsaux_stg_filter` 八键（方向 false / 类型 true） | ✅ `/data/data/com.guosen.android/shared_prefs/gsaux_stg_filter.xml` |
| 9 | 确定当场生效（不用退出重进） | ✅ 见 #3 / #4 |

### 策略详情页那一侧（与行情侧共用面板 / 模型 / 判定，2.18.0 补验）

登录后在同一台机上补验（2026-10-19 14:4x，策略「70e」20 只）：

| # | 验收项 | 结果 |
|---|---|---|
| 10 | 策略详情面板是**八行两组**（涨跌与自选 / 股票类型），文案与行情侧同名同义 | ✅ 截图 `.work/stg-panel.png`；id 表见 `feature/filter/FilterPanelIds` |
| 11 | 面板四条左缩进一致（分组头与行都在 70px） | ✅ dump：`涨跌与自选 / 涨家股票 / 股票类型 / 创业板` 全是 `x=70` |
| 12 | **股票类型**真的筛得动：取消勾选「科创板」→ 确定 | ✅ 日志 `全量 20 → 保留 19（… 科创板 1 …）`，计数 `策略个股 19/20`，688 开头的行消失（其余主板行照留） |
| 13 | 方向筛选 + 空态：勾「涨家股票」 | ✅ `全量 20 → 保留 0（… 自选 20 …）` + 宿主空态「未选出符合条件的股票」+ 计数 `策略个股 0/20`（这张策略 20 只**全在自选里**，按「自选优先」的既有语义，只勾涨家当然一只不剩） |
| 14 | 「自选」判定真值核对 | ✅ 打开该策略第一只「善水科技 301190」的个股详情页，底栏是**「改自选」**（= 已在自选里）—— 20/20 是事实，不是误判 |
| 15 | **重置**：八项回到出厂并立即生效、面板不关 | ✅ 列表回到全量 20 只、计数还原成宿主格式 `策略个股20只`、空态收起、列标题与风险提示挂回 |
| 16 | **首帧不闪全量**（筛选生效、点进详情） | ✅ 录屏（12fps 取帧，`.work/sheet1.png`）：详情页**第一帧**就是筛选后的结果（此处是空态），没有任何一帧出现过未筛选的股票行 |

---

## 六、失效预案（App 升级后照这张表改）

| 失效现象 | 改哪里 |
|---|---|
| 面板里没有「涨跌与自选」三行 | 面板判定：`feature/filter/FilterPanelIds` 的 `ID_PANEL_LIST` / `ID_PANEL_OK`；宿主换布局就用 `aapt2 dump xmltree` 重新对 id |
| 三行出现但点了确定没反应 | `wrapClick` 的监听包装失效（宿主换了按钮或用了别的控件） —— 看日志有没有 `筛选条件已生效` |
| 排行榜行认不出来（一行都不筛） | `MarketFilterTargets.RANKING_CODE_ID` / `RANKING_PERCENT_ID`；用 `tools/probe` 重新 dump 行内 id |
| 收起后留一条空档 | 不是我们的行（子项缺少 `#tv_item_zxg_code`）—— 核对 `isRankingRow` 的判据 |
| 关掉开关后行没还原 | `ViewPatcher` 的记账丢了（页面被重建）—— 属于已知可接受：下一趟布局按「该留的且被收起」再还原一次 |
| 新增行贴着屏幕边缘 / 宿主行被推右 | 见 §2.2 的「左缩进的坑」：`wrapWithVerticalHost` 的横向外边距清零、`addSectionHeader` 的缩进差额 |

---

## 七、还没复验的点

| 项 | 说明 |
|---|---|
| **策略详情页侧** | 2026-10-19 14:4x 登录后已补验（面板八行两组、类型筛选、自选真值、重置、首帧不闪），见 §五「策略详情页那一侧」—— 本条已消项 |
| **勾「自选股票」在行情侧的效果** | 游客态已实跑（空自选 → 榜单全部收起）；登录态（135 只自选）下未再跑这一档 |
| **`StockInfo.isXXLD` = 未开板次新** | 只做过字段名层面的确认（探针 dump），运行时取值未核对；认不出就不归类，不会误伤别的股票 |
| **其他市场分类（创业板 / 科创板 / 北交所页签）** | 排行榜在四个页签下是同一套行布局，理论上都生效；本次只在「沪深京」页签实跑 |
