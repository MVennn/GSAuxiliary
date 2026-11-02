# 策略详情页「截图并添加」实现说明

> 一句话：多选态底部「确认添加」左侧多一颗「截图并添加」，按勾选顺序打开每只股票的
> 真实详情页，**数据加载完整才截图**并存相册，截完自动触发宿主「确认添加」→「选择分组」。

> 本文的控件契约、Intent 契约与验证结论**均来自 2026-10-22 真机实测**
> （魅族 20 / Android 16 / 金太阳 8.6.0 vc271），不是推断。

---

## 0. 结论先行

| 问题 | 答案 |
|---|---|
| 按钮在哪 | `#ll_add_zxg_bottom` 内、`#tv_bottom_add_zxg`（「确认添加」）左侧；只在多选态出现 |
| 按钮样式 | 从「确认添加」克隆背景/文字色/字号/内边距，高度与它一致（140px）；两颗按钮都收窄到「文字宽 + 左右各 12dp」（实测 323px / 273px），宿主确认按钮的原宽记在 keyed tag 上、离开多选态或关开关时还原；左侧「已选 N 只股票」不裁切（实测可用区 484px）；两颗蓝按钮之间不加间隔条 |
| 截图怎么来 | **真实详情页**：按选中子集构造 `quotes.stockDetail` Intent，一次打开 `StockFragAct`，用 `vp_stocks` 逐页切换，`PixelCopy` 截图 |
| 数据没加载完怎么办 | **不截图**：当前页标题必须含目标代码，且可见 `KXChartGroupView.m_kxChartInfo.m_kxChartItems` 非空；超时只记失败、翻下一页，绝不保存半张图 |
| 分组 | 不自己做分组面板、不调分组写接口：截完 `finish()` 回策略页，`performClick()` 宿主「确认添加」，由 `ChooseGroupAct` 让用户选组 |
| 截图存哪 | Android 10+ `MediaStore` → `Pictures/GSAuxiliary/<yyyyMMdd_HHmmss>/01_名称_代码.png`；旧系统回退 App 外部目录 |
| 截图速度 | 存盘与翻页**并行**：PixelCopy 拿到像素就切下一页，PNG 编码 + 相册写入在 worker 继续；在途上限 2 张，位图复用 |

---

## 1. 宿主契约（实测）

### 1.1 策略详情页

```
ListView #lvStks（带 header）
底部条 LinearLayout #ll_add_zxg_bottom
  ├─ "已选 " / #tv_bottom_zxg_num / " 只股票"
  └─ #tv_bottom_add_zxg "确认添加"   ← 新按钮插在它前面
锚点 #tv_stg_add_zxg：正常态「勾选加入自选股」，多选态「取消」
```

- 选中状态：`ListView` checked states，`position = headerViewsCount + 全量下标`。
- 筛选生效时：列表上挂着 `FilteredAdapter`，用
  `StgFilterBridge.fullIndices(total, owner)` 把子集下标换回全量下标；
  换不到就中止，**不用恒等映射兜底**。

### 1.2 个股详情页

```
StockFragAct
  └─ com.guosen.commonui.LockViewPager #vp_stocks
       └─ 当前页 #tv_top_stk_name_and_code  "名称 代码"
            └─ KXChartGroupView #chart_group_kx
                 └─ m_kxChartInfo.m_kxChartItems（List）
            └─ RadioButton "日K"（任务开始时尝试点它）
```

- `LockViewPager` **没有覆写** `setCurrentItem`（实测 class dump 只有
  `setLocked` / `setNestedScroll` / `setViewPagerScrollListener`），
  程序化 `setCurrentItem(i, false)` 可切页。
- 当前页标题与相邻预加载页各有一份 `#tv_top_stk_name_and_code`；
  用 `getGlobalVisibleRect` 区分当前页（可见）与屏外页。

### 1.3 Intent 契约

与「策略直达标准行情」的多股票契约相同：

```
Intent(action = "quotes.stockDetail")
  .setClassName("com.guosen.android", "com.guosen.quotation.ui.StockFragAct")
  .putExtra("adapter_key", Bundle {
      stockList = ArrayList<IntentStockInfo>   // 只放选中的子集
  })
  .putExtra("stockIndex", 0)                   // 顶层 extra，不在 Bundle 里
```

- `IntentStockInfo` 自带 `(StockInfo)` 构造器。
- 必须构造**新的** `Bundle` / `ArrayList`，不能原地改宿主适配器持有的列表。

### 1.4 分组页

点 `#tv_bottom_add_zxg` 启动独立 Activity
`com.guosen.quotation.ui.ChooseGroupAct`（标题「选择分组」/「我的分组」）。
0 只时宿主只弹「请勾选股票后再添加」，不打开分组页 —— 本功能也做了同样的空选择保护。

---

## 2. 流程

```
点「截图并添加」
  ├─ 0 只 → Toast「请先勾选股票」
  ├─ 读取选中（checked states + 筛选映射）→ 冻结列表
  └─ 按子集打开一次 StockFragAct
       ├─ 顶部注入进度条「正在截图 3/5 · 名称」+ 取消
       ├─ 尝试点「日K」
       └─ 逐页：
            setCurrentItem(i,false)
              → 等标题含目标代码 + K 线 m_kxChartItems 非空
            → 进度条 INVISIBLE → 等 48ms → PixelCopy
            → 后台 PNG + 相册写入（**不挡翻页**）→ 下一页
            超时：只记失败，不截图
  └─ 完成：移除进度条，finish() 回策略页
       └─ 策略页 onResume：核对「已选 N」与启动前一致
            → Toast + performClick() 宿主「确认添加」
            → ChooseGroupAct，用户选组，宿主负责写入/同步
```

- 截图顺序与勾选顺序一致，文件名 `01_名称_代码.png`。
- 完成但部分页未就绪：已截到的照常保存并继续弹分组页，Toast 报失败只数。
- **成功 0 张**：不弹分组页（避免用户以为截图成功），Toast「未截图：N 只数据未就绪」。
- 用户取消 / 按返回 / 切后台：停止任务，保留已截到的图，不自动弹分组页。

### 2.1 就绪门禁（2026-10-22 要求）

一页允许截图必须同时满足：

1. `#vp_stocks.getCurrentItem() == 目标下标`；
2. 可见的 `#tv_top_stk_name_and_code` 文本包含目标 6 位代码；
3. 可见的 `KXChartGroupView` 的 `m_kxChartInfo.m_kxChartItems` 非空。

超时：第一页 9 秒，后续页 6 秒（每 48ms 轮询）。不满足就不截。
找不到「日K」页签时退化为「标题命中 + 静置 900ms」，并记日志（宿主换控件时才会走到）。

### 2.2 存盘与翻页并行（2026-11-02）

整条链路里最贵的是 PNG 编码 + `MediaStore` 三次 IPC，而它与下一页的数据加载无关。
现在 PixelCopy 成功就把位图交给单线程 worker，主线程**立刻翻下一页**：

- 在途存盘上限 `MAX_INFLIGHT_SAVES = 2`：满了暂停翻页，存完一张由
  `afterSaveStep()` 接上；位图按页复用（池 2 张），峰值原生内存约 2×10.4MB；
- 顺序由文件名编号保证（`01_…` 由下标生成），与写盘完成先后无关；
- 收尾必须等「所有页都翻过 + 在途清零」，否则会丢掉最后几张；
- 取消语义不变：立即停翻页，队列里已提交的存盘照常写完。

每页记录 `切页 / 就绪 / 拷贝 / 存盘` 四段耗时：明细在 DEBUG（`第 i/N 只 用时…`，含 native/Java 堆），
收尾 INFO 打 `总耗时（页均）` 与 `前5页 / 后5页` 各段均值 + native/Java 堆。
判读「越截越慢」：**就绪**变慢 = 宿主行情请求/页面累积；**存盘**变慢 = MediaProvider/GC；
四段一起变慢 = 主线程被宿主拖慢或热降频。

---

## 3. 代码位置

| 文件 | 职责 |
|---|---|
| `feature/stgsnap/StgSnapAddFeature.kt` | Activity 钩子、按钮注入/还原、选中读取、任务与确认页接力 |
| `feature/stgsnap/StgSnapAddTargets.kt` | 宿主类名 / 控件 id / Intent key / 字段候选（`check_contract.py` 扫描） |
| `feature/stgsnap/SnapSelection.kt` | 选中读取（checked states + 筛选映射 + 探针兜底） |
| `feature/stgsnap/StockDetailLauncher.kt` | `StockInfo` → `IntentStockInfo`、列表/单只 Intent |
| `feature/stgsnap/SnapCaptureJob.kt` | 逐页状态机、就绪门禁、PixelCopy、存盘、收尾 |
| `feature/stgsnap/SnapReadiness.kt` | 可见标题 / K 线数据 / 日K 页签判定（反射按类缓存） |
| `feature/stgsnap/SnapStorage.kt` | MediaStore / App 目录回退 |
| `feature/stgsnap/SnapProgressOverlay.kt` | 详情页顶部进度条（截图前隐藏） |
| `feature/stgsnap/StgSnapPlan.kt` | 纯逻辑：校验、去重、文件名、标题匹配、计数解析 |

开关：`ConfigKeys.STG_SNAP_ADD = "stg_snap_add"`，默认关闭。
单测：`StgSnapPlanTest`。

---

## 4. 真机验证（2026-10-22）

| 项 | 结果 |
|---|---|
| 程序化切页 | `setCurrentItem(1,false)` 后 `getCurrentItem()==1`，标题从「兆威机电」变「鸣志电器」 |
| PixelCopy | `SUCCESS`，1080×2400，单张 330~360KB，K 线/底栏完整、无进度条 |
| 选中子集列表 | 2 只 → `StockFragAdapter count=2 current=0`；5 只 → `count=5`，5 张顺序正确 |
| 返回对账 | `listChecked=2 bottomCount=2 match=true`，随后 `performClick` 打开 `ChooseGroupAct` |
| 相册写入 | MediaStore URI 插入 + `IS_PENDING=0`，查询到 `Pictures/GSAuxiliary/...` |
| **数据未就绪门禁** | iptables 只掐目标 App 网络后跑 2 只：两页均判「K 线数据未就绪」，**成功 0、未截图、未弹分组页**；恢复网络后正常 |

样张（本地留存）：`.work/probe/feature_01.png`、`feature_02.png`、`feature_05.png`、
`.work/probe/feature_button.png`。

> 2026-11-02 的速度整改（存盘并行 + 轮询 48ms + 位图复用）已做真机对比（魅族 20 / Android 16 / 宿主 8.7.0）：
> - 同一策略 **19 只**：点按钮 → 自动弹分组页 **17.59s → 12.42s**（0.93 → 0.65 s/页）；
> - 同一策略 **99 只**：串行版点击→最后一张落盘/回策略页 **约 91~93s（0.93 s/页）**，
>   并行版点击→自动弹分组页 **54.87s（0.55 s/页）**；两次都保存 99 张、顺序一致。
> - 两次 99 只的逐页节奏都是平的（串行 0.89~1.00、并行 0.44~0.56 s/页），
>   **本次未复现「30 只以后越来越慢」**；能确认的是串行版每页成本恒定偏高，并行后整体快约 1.7 倍。
>   截图内容与顺序无变化。验收口径见 §5 第 10 条。

---

## 5. 验收清单

1. 多选态出现「截图并添加」，在「确认添加」左侧；高度/配色/字号一致，
   「已选 N 只股票」始终单行（`截图并添加` 323px / `确认添加` 273px，左侧余 484px）。
2. 0 只点击只提示，不打开详情页。
3. 1 / 5 / 20 只：截图数量 = 勾选数，顺序与文件名一致，标题代码不串。
4. 断网/数据未加载：该页不截图；全部未就绪时 0 张且不弹分组页。
5. 截图里没有进度条；K 线、行情头、底栏完整。
6. 截完自动弹 `ChooseGroupAct`；返回则截图保留、不加入。
7. 取消 / 返回 / 切后台：任务停止，无僵尸进度条，不误点确认。
8. 关开关即时移除按钮；重开并重进多选态后回来。
9. release 包无 `io/github/libxposed/api` 类定义；日志 INFO 不出现完整代码。
10. 40 只批次：`成功 + 未就绪/失败 = 总数`；收尾 INFO 有 `总耗时` 与前 5 / 后 5 页分段均值；
    下 20 只的 `存盘` 均值不应随页数增长（存盘已与翻页解耦）。

---

## 6. App 升级后怎么查

| 现象 | 先改哪里 |
|---|---|
| 按钮不出现 | `StgSnapAddTargets.CANCEL_BUTTON_ID_CANDIDATES` / `CONFIRM_BUTTON_ID_CANDIDATES` |
| 选中读不到 | `LIST_ID_CANDIDATES` / `CAM_FIELDS` / `CODE_FIELDS` / `MARKET_FIELDS` / `NAME_FIELDS` |
| 详情页打不开 | `CLS_INTENT_STOCK_INFO` / `ACTION_STOCK_DETAIL` / `KEY_*` |
| 不切页 | `PAGER_ID_CANDIDATES`；确认 `LockViewPager` 是否还继承 `setCurrentItem` |
| 永远不截图 | `PAGE_TITLE_ID_CANDIDATES` / `KX_GROUP_CLASS` / `KX_INFO_FIELD` / `KX_ITEMS_FIELD` |
| 分组页不弹 | `CONFIRM_BUTTON_ID_CANDIDATES` / `BOTTOM_COUNT_ID_CANDIDATES` |

日志关键字：`截图并添加：读取选中` / `开始 N 只（日K=` / `第 i/N 只数据未就绪` /
`完成 N 只，成功 X` / `自动触发宿主确认添加` / `总耗时`（含前5/后5页分段均值）/ `第 i/N 只 用时`（DEBUG）。
