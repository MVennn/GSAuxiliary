# 个股详情页底栏「智能盯盘」快捷入口 实现方案

> 目标：**个股详情页**底栏原本是 `买 / 卖 / 撤单 / +自选 / 更多`，把「更多」弹层里的
> **智能盯盘**提到一级位置 —— 在「+自选」与「更多」之间多一颗按钮，一点直达。
> 买 / 卖的宽度自动让位。
>
> 底栏是**自适应**的（个股一套、指数 / 板块另一套），本功能两套都覆盖。
>
> 本文的链路与契约**已在真机（魅族 20 / Android 16 / 金太阳 8.6.0 vc271）实测确认**，
> 依据是 `uiautomator dump` 的控件树 + LSPosed 运行时探针日志，不是推断。
> **第 8 节记录了实现过程中踩过的坑，改代码前先看那节。**

---

## 0. 结论先行

| 问题 | 答案 |
|---|---|
| 底栏在哪个页面 | `com.guosen.quotation.ui.StockFragAct`（个股与指数**同一个 Activity**，底栏内部换形态） |
| 底栏按钮行是什么 | **`ConstraintLayout`**，id 却叫 `ll_bottom_stk_normal`（名字骗人） |
| 底栏是自适应吗 | 是。两套按钮**在同一个 `ConstraintLayout` 里靠 visibility 互斥**（另一套是 `GONE`） |
| 买 / 卖 怎么让位 | 它们是**带权重的链成员**；把「更多」盒子加宽 → 链上剩余空间变少 → 买卖按权重自动各让一半 |
| 「更多」弹层是什么 | **`PopupWindow`**（`dumpsys window` 里是 `PopupWindow:xxxx`），不是 `Dialog` |
| 「智能盯盘」在哪 | `GridView #gv_stk_bottom` 的某一项；**位置随页面变**（个股第 2 项、指数第 1 项）→ 必须按文字找 |
| 点下去去哪 | H5：`action=router_aistare_webview`，`url=earlywarning/?userCode=<账号>&stockCode=<代码>.<市场>&categoryType=2` |
| 为什么不直接拼 Intent | `url` 带 **`userCode`（账号维度）**，自己拼既不可靠也不合适 → **复用宿主自己的点击路径** |
| 什么时候注入 | **不能在 `onResume` 一次搞定**：`EngineAct` 的引擎片段异步挂载，底栏要 1～3 秒才出现 |

---

## 1. 实测契约（真机证据）

### 1.1 底栏两套形态

> ⚠️ **先记住这一条**：`StockFragAct` 的分页器**每一页各带一份完整的底栏**
> （同一个资源 id `stk_detial_bottom_bar`，各自还有自己的 `bottom_index_container`）。
> 实测一次 3 页会在视图树里出现 **3 份**同名底栏 ——
> 所以「找底栏 / 找更多」**必须在某一份底栏范围内找**，
> 别用 `decorView.findViewById`（它永远只返回第一份，见 §8.12）。

下面这一份是**其中一页**里的样子：

```
LinearLayout #stk_detial_bottom_bar [0,2205][1080,2335]
  ├─ ImageView （1px 分隔线）
  ├─ FrameLayout #ll_bottom_stk                       ← 个股形态；指数形态下 GONE
  │    └─ ConstraintLayout #ll_bottom_stk_normal      ★ 名字叫 ll_，其实是 ConstraintLayout
  │         ├─ FrameLayout #fl_buy           clk  链成员·带权重  └ TextView #tv_bottom_buy   "买"
  │         ├─ FrameLayout #fl_sell          clk  链成员·带权重  └ TextView #tv_bottom_sell  "卖"
  │         ├─ TextView    #tv_bottom_chedan     clk  链成员·固定宽  "撤单"
  │         ├─ TextView    #tv_bottom_zx         clk  链成员·固定宽  "+自选"
  │         ├─ FrameLayout #fl_more          链成员·固定宽 96px
  │         │    └ TextView #iv_bottom_more      clk            "更多"
  │         ├─ FrameLayout #fl_bottom_buy_index …  ← 指数形态的一套（个股形态下 GONE）
  │         └─ FrameLayout #fl_bottom_index_more …
  └─ LinearLayout #ll_bottom_index                    ← 指数 / 板块形态；个股形态下 GONE
       ├─ FrameLayout #fl_bottom_buy_index clk 链成员·带权重 └ TextView #tv_bottom_buy_index "买指数基金"
       ├─ TextView    #tv_bottom_index_change_zx  clk 固定宽 "+自选"
       └─ FrameLayout #fl_bottom_index_more 固定宽
            └ TextView #iv_bottom_index_more    clk            "更多"
```

> ⚠️ 两套形态的「更多」**id 不一样**（`iv_bottom_more` / `iv_bottom_index_more`），
> 「自选」也不一样。锚点、样式克隆都要走**候选 id 数组**，并**取当前 `isShown` 的那个**。
>
> ⚠️ `uiautomator` 会把 `ConstraintLayout` 报成 `ViewGroup` —— **别信它的类名**，
> 以运行期日志打出来的类名为准。

### 1.2 买 / 卖 是带权重的链成员 —— 改屏宽实测

把屏宽从 1080 改成 900（`wm size 900x2400`）后重新 dump：

| 控件 | 1080 宽 | 900 宽 | 结论 |
|---|---:|---:|---|
| `#fl_buy` | 321 px | 231 px | **带权重**（跟着链上剩余空间走） |
| `#fl_sell` | 320 px | 230 px | **带权重** |
| `#tv_bottom_chedan` | 96 px | 96 px | 固定宽（≈37dp） |
| `#tv_bottom_zx` | 96 px | 96 px | 固定宽 |
| `#fl_more` | 96 px | 96 px | 固定宽 |

两两验证：1080 → 900 少了 180 px，买 / 卖恰好各少 90 px（= 180 / 2）。

### 1.3 「更多」弹层 = PopupWindow

```
Window #13 Window{1ab10db u0 PopupWindow:99f9dea}:
  mParentWindow=Window{2df417 u0 com.guosen.android/com.guosen.quotation.ui.StockFragAct}
  frame=[Rect(0, 1724 - 1080, 2335)]
```

内容（个股页）：

```
FrameLayout (popup content)
  LinearLayout
    LinearLayout （笔记行：iv_memo_edit_by_voice / tv_memo_edit_by_text / tv_go_stock_memo）
    GridView #gv_stk_bottom [0,1674][1080,2077]
      条件单 / 智能盯盘 / 智能K线 / 产业链      ← 第 1 行
      区间统计 / 交易明细 / 股票PK              ← 第 2 行
    LinearLayout （设置 / 切换信用交易 / 分享）
    FrameLayout  （取消 #iv_close）
```

每一项的结构固定：

```
FrameLayout (clk) └ TextView #tv_item_gv_menu "智能盯盘"
```

**指数页只有 2 项**（智能盯盘 / 区间统计），且**智能盯盘排第 1**；
个股页它排第 2。→ 定位必须**按文字**，不能按下标。

### 1.4 「智能盯盘」的落点（探针原样输出）

```
========== LAUNCH ==========
来自页面: com.guosen.quotation.ui.StockFragAct
  [LAUNCH] to = com.guosen.android  action = router_aistare_webview  flags = 0x0
       extras(2):
         url : java.lang.String = earlywarning/?userCode=24614842872980480&stockCode=000001.2&categoryType=2
         node_level : java.lang.Integer = 4
       stack: EngineFrag#startEngineActForResult:399
              <- EngineFrag#startEngineAct:379
              <- StockDetailFrag$34#onItemClick:6289
```

落到 `com.guosen.quotation.aistare.ui.AIStareWebView`（H5 容器）。

`url` 里的 `userCode` 是**账号维度**的，模块去拼这个串既不可靠也不合适
（而且它随登录态变化）。**所以本功能不拼 Intent，只复用宿主自己的点击路径。**

---

## 2. 实现方案

### 2.1 布局怎么让位：**不碰约束，只加宽「更多」的盒子**

底栏是 `ConstraintLayout`，直觉上「插一个固定宽子 View，买卖就自动让位」是**错的**
（见 §8.1）。改成改约束也能跑，但**极脆**（见 §8.2）。

最终方案：**把「更多」那个盒子 `#fl_more` 加宽**。它本身就是链上的固定宽成员，
加宽它 → 链上剩余空间变少 → 买卖按权重自动各让一半。然后把新按钮和原来的「更多」
并排塞进这个盒子里：

```
#fl_more  (96px → wrap_content，变宽 = 买卖让位)
  └ LinearLayout(横向, gravity=center_vertical)
       ├ 我们的「智能盯盘」按钮        （克隆「更多」的样式，独立 id / tag）
       └ 原来的「更多」TextView        （原样搬过来，点击行为完全不变）
```

关开关时**逆向还原**：把「更多」搬回盒子、盒子宽度改回原值、删掉容器。
现场记录在 `Injected(box, holder, moreText, button, originalBoxWidth, iconW, iconH)`，
`onConfigChanged` 拿到 `false` 就逐个 `detach`。

**实测数字**（1080 宽屏）：

| 控件 | 注入前 | 注入后 |
|---|---:|---:|
| `#fl_buy` | 321 px | **246 px** |
| `#fl_sell` | 320 px | **245 px** |
| `#fl_more` | 96 px | **246 px** |
| 我们的按钮 | — | 124 px（`[808,2216][932,2326]`） |
| `#iv_bottom_more` | 96 px | 96 px（`[958,2216][1054,2326]`） |

### 2.2 自适应个股 / 指数：只锚「当前可见的那个『更多』」

两套形态在同一个 `ConstraintLayout` 里靠 visibility 互斥。锚点取
**`isShown` 的那个「更多」**，它的父容器就是「更多」的盒子 ——
个股形态（`fl_more`）与指数形态（`fl_bottom_index_more`）走同一段代码，
**不需要判断页面类型**。

### 2.3 点击：复用宿主路径（三步接力）

```
用户点我们注入的按钮
  │
  ├─ 开一轮（Round）：先看有没有在途的轮次（采集轮让位 / 上一次点击合并）
  ├─ 抓宿主窗口快照（alpha / dimAmount）
  ├─ moreText.performClick()                    → 宿主弹出「更多」面板
  │
  ├─ WindowManagerGlobal.updateViewLayout 钩子（全程）
  │    └─ 这一轮还在 且 改的是本页 decorView
  │         → 把 alpha / dimAmount 夹回快照值      ← ★ 宿主调暗在这里被拦下，一帧都不画
  │
  └─ PopupWindow 显示钩子被触发
       ├─ 没有在途的轮次 / 这一轮已认领 → 走开
       ├─ **先验指纹**：contentView 里有 #gv_stk_bottom 才算我们的面板；
       │    验不过 → 一个字节都不动，标记留着等真正的面板（★ 见 §8.14）
       ├─ 认领（同一轮只成功一次）→ 整个弹层 rootView.alpha = 0f
       ├─ 重试直到 GridView 子项出现（下一帧 measure/layout 才有）
       ├─ 按**文字**「智能盯盘」找到 TextView → 上溯到 GridView 的直接子项
       ├─ grid.performItemClick(...)            → 与用户手点那一项完全同一条路径
       └─ rootView.alpha = 1f; popup.dismiss(); 本轮结束（窗口夹子随之中止）
```

**为什么要 `rootView.alpha = 0f` 而不是只设 `contentView`**：`PopupWindow` 在
`mBackground != null` 时会套一层 `PopupBackgroundView`，那层背景**独立于内容**，
只设 `contentView` 的 alpha 它照样画出来。

**为什么不能直接同步点**：`PopupWindow.show()` 只把窗口挂上去，
`GridView` 的子项要等下一帧 measure/layout 之后才存在 ——
`show()` 返回时 `grid.childCount == 0`。所以必须重试（16ms × 12 次）。
面板全程 `alpha = 0`，重试期间用户什么都看不到。

**为什么必须先确认是更多面板**：`PopupWindow` 在 App 里到处都是，
只凭「我们刚点过更多」就认领会误伤别的弹层。`#gv_stk_bottom` 是更多面板的指纹，
而且 `findViewById` 在布局前就能命中，**可以同步判断**。

**为什么必须用 `performItemClick`**：宿主的点击是挂在 `GridView` 上的
`OnItemClickListener`（探针栈 `StockDetailFrag$34#onItemClick` 就是它），
面板项容器本身**不保证 `clickable`** —— 往上找可点击祖先会直接落空（见 §8.3）。

**一轮 = 一次握手（3.1.0 现状）**：状态收在一个 `Round` 对象里（`harvest` 区分采集 / 点击、
`claimed` 记录认领、`window` 存窗口快照），而不是三颗各活各的计数器：

| 情形 | 处理 |
|---|---|
| 采集轮还没弹面板，用户点了入口 | 掐掉采集轮（先还原它可能造成的窗口变暗），用户点击优先 |
| 上一轮点击还在等面板 / 正在处理 | **合并**这一次点击 —— 绝不 `performClick` 第二次（两次就两层弹层） |
| 弹层不是更多面板 / 还没内容 | **不认领、也不清标记**，留着给真正的面板（§8.14） |
| 嵌套 `showAsDropDown` 重载重复回调 | `claimed` 只允许成功一次，重复的走开 |
| 1.5s 等不到面板 | 只作废**还没被认领**的轮次；收尾按身份核对（`round === r`），不会误清下一轮 |
| `performClick()` 返回 false | 宿主根本没响应，立刻结束本轮（不占着标记去劫持一会儿后的手点「更多」） |

### 2.4 注入时机：布局回调驱动 + 幂等自愈（2.15.1 起）

`StockFragAct` 继承 `EngineAct`，引擎片段是**异步挂载**的。实测：

```
底栏入口[diag]：onResume com.guosen.quotation.ui.StockFragAct barId=2132481425
底栏入口[diag]：findViewById -> null
```

`onResume` 那一刻底栏**还不存在**（底栏容器出现之后，里面的按钮还要再晚一点才齐）。

初版在这里挂了「60 次 × 100ms 的有限重试」，并且**只在 `Activity.onResume` 重新对齐状态**，
注入只往 `decorView.findViewById(barId)` 拿到的那**一份**底栏里做。真机上因此踩了两个坑，
合起来就是用户看到的「**左右翻几次之后智能盯盘就没了**」：

1. **分页器每页各带一份底栏**（§1.1 的警告）。`findViewById` 永远只返回 **DFS 里第一份** ——
   于是不管当前显示的是哪一页，注入都往第一页那份里做；一翻页就换到另一份，
   入口自然「没了」。更糟的是「找更多」也用的是全树 `findViewById`，
   拿第一页的「更多」去同步第二页的底栏，会在「这个盒子不属于这条底栏」处直接失败 ——
   即使补上重同步也修不好。
2. **只在 `onResume` 重同步**：翻页不走 `onResume`，宿主还可能把底栏重建一遍；
   加上记账表「我记过这个盒子」的判据会在宿主清掉子视图后永远误判成「已经注入过」，
   于是一旦掉了就再也补不回来。

现在改走 [`PageSyncFeature`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/PageSyncFeature.kt)
那套公共调度（与界面精简五个功能同一套骨架）：

```
Activity.onResume ─┬─→ 挂全局布局回调 + 挂首帧屏障（每个 decorView 只挂一次）
                   └─→ 立刻 sync() 一次

全局布局回调 ──→ sync()          ← 切 Tab / 翻页 / 宿主重建视图，全靠它覆盖

首帧屏障(OnPreDraw) ──→ 「自上一帧以来界面被动过」就取消这一帧，
                        等布局稳定后一次画出来（用户看不到「先原样、再跳一下」）
```

`sync()` 的几步（每一步都不能省）：

1. **找齐所有底栏**（`findBars`：DFS 收集同一个 id 的全部实例）——**三份都要同步**，
   哪一页被翻出来都已经准备好了；新页是懒创建的，创建时必然伴随布局 → 下一次 `sync()` 补上；
2. **在「这一份底栏范围内」认「更多」**（`bar.findViewById`，不是 `decorView`）；
   认不出来 → 返回 `false`，由骨架累计次数并在连续 3 次后打一条警告 +
   当前页面的**视图树快照**（校准入口，见 §7）；
3. **判现场**（`liveRecord`）：**「我们的按钮现在还在不在盒子里」**——
   不是「我记不记得自己注入过」。宿主重建底栏后记账表还在，只查它会永远误判成
   「已经好了」，然后**再也不修**。现场不成立就顺手把过期记账和自己的容器清掉，重新注入；
   现场成立则 `reassert`：盒子宽度被宿主改回去就重新加宽（**加宽是「买卖让位」的唯一来源**），
   图标被重置就补回来。

**三条硬红线**（都是真机踩出来的，改代码时别碰）：

| 红线 | 不守的后果 |
|---|---|
| 「更多」**必须**在某一份底栏范围内找 | 拿到别页的「更多」→ 每次同步都失败，入口永远补不回来 |
| 盒子**不能**取 `more.parent`，要按 id 找 + 跳过我们自己的容器（`ENTRY_HOLDER_TAG`） | 注入一次之后 `more.parent` 就是我们的壳，记账查不到 → **一层层往里套容器**（实测套了 5 层，`fl_more` 被撑到 1079px，买卖被挤成 0 宽） |
| 「更多」祖先里已有我们的容器时**绝不**再注入 | 同上，兜住第 2 条万一漏掉的情况 |

**门禁**：不在 `StockDetailBarTargets.WATCH_ACTIVITIES` 里的页面，由骨架的 `pages`
直接短路 —— 连一次 `sync()` 都不会进来（初版是每次 `onResume` 都白跑 60 次重试再打一条
误导性警告）。代价是 App 若改了 `StockFragAct` 的类名，注入会**静默失效** ——
但那种情况下底栏的资源 id 基本也一起变了，本来就得重新侦察（§7）。

用 `activity.window.decorView` 而不是 `activity.findViewById`：
`EngineAct` 对 `findViewById` 有自己的一套（实测拿不到底栏）。

### 2.5 图标：隐形采一次，按「更多」的尺寸缩放

底栏按钮是「图标在上、文字在下」的样式，图标来自面板那一项自带的 `drawableTop`。
注入后**异步隐形开一次面板**把图标取回来，取到就套到所有已注入的按钮上。
两个坑见 §8.4：`setCompoundDrawables` 不设 bounds 画不出来；面板里的图标比底栏的大，
不按「更多」图标的尺寸缩放会把文字顶掉。

采集只在**每个进程一次**，失败会复位、允许下次重试。

---

## 3. 降级链

`注入按钮 + 接力点击`（完整）
→ `图标采集失败` → 纯文字按钮（功能不受影响）
→ 面板拦不到 → 点按钮无反应（原版「更多」仍可用），日志明确报
→ 关开关 → 底栏完全恢复原版。

---

## 4. 模块侧改动清单

| 文件 | 改动 |
|---|---|
| `feature/stockbar/StockDetailBarTargets.kt`（新增） | 全部候选 id / 文案 / 契约常量 + 实测说明 |
| `feature/stockbar/StockDetailBarFeature.kt`（新增） | 功能本体：注入 + 接力点击 + 图标采集 |
| `feature/FeatureManager.kt` | `features` 列表加一行 |
| `core/ConfigKeys.kt` | 加 `STOCK_BAR_ENTRY` / `DEFAULT_STOCK_BAR_ENTRY`，进 `ALL_KEYS` / `FEATURE_KEYS` / `DEFAULTS` |
| `core/ConfigSnapshot.kt` | 加 `stockBarEntry` 字段 + 进 `fingerprint`（乘数 1877） |
| `core/ConfigSource.kt` | `read()` 补一行 |
| `ui/FeatureCatalog.kt` | 加一条 `FeatureSpec`，分区 `category_stock_detail`（**股票详情页**，无二级菜单） |
| `app/DebugConfigProvider.kt` | 加 `stock_bar_entry` 列（开发期脚本化开关） |
| `res/values/strings.xml`、`values-en/strings.xml` | 新增 `category_stock_detail`（股票详情页 / Stock detail page） |
| `res/values/strings.xml` + `values-en/strings.xml` | 中英文案 |

**不动**：`GszxModule.kt`、其它四个功能、`ConfigKeys.GROUP`、`scope.list`、
`SettingsActivity`（无二级页，不需要新路由）。

---

## 5. 风险与降级

| 风险 | 概率 | 对策 |
|---|---|---|
| App 升级后 id 改名 | 中 | 全部进候选数组；「更多」还有**按文字 + 限定在底栏内**的兜底 |
| App 升级后承载页改名（`StockFragAct`） | 中 | 注入门禁按类名短路 → **静默失效**（不再打日志）。改类名时资源 id 基本也一起变，按 §7 重新侦察即可 |
| 「更多」盒子不再是 FrameLayout | 低 | 直接 `addView` 到盒子里；盒子类型不影响「加宽让位」这个机制 |
| 面板不是 `PopupWindow` 了 | 低 | 注入仍生效，只是点击退化成「点更多 → 用户自己点」；日志会明确报 |
| **宿主重建底栏 / 翻页换形态** | 高（实测会） | 每次布局都 `sync()`；现场判据是「按钮还在不在盒子里」，不成立就重新注入（§2.4、§8.12） |
| 宿主把盒子宽度改回去 | 中 | `reassert` 每次同步都重新压回 `wrap_content`（加宽是买卖让位的唯一来源） |
| 影响原版稳定性 | — | 所有视图操作包 `runCatching`；非「更多面板」的 `PopupWindow` 一个字节都不碰 |

---

## 6. 验收标准（均已真机通过）

| # | 验证项 | 结果 |
|---|---|---|
| 1 | 个股页底栏出现「智能盯盘」，位置在「+自选」与「更多」之间 | ✅ `[808,2216][932,2326]`，夹在 `tv_bottom_zx` 与 `iv_bottom_more` 中间 |
| 2 | 买 / 卖 变窄，底栏不换行不溢出 | ✅ 321/320 → 246/245 |
| 3 | 点它直接进 `AIStareWebView`，过程看不到面板闪出 | ✅ 日志「已触发『智能盯盘』(position=1)」，弹层全程 alpha=0 |
| 3b | **全程不闪黑**（50fps 录屏逐帧测整屏亮度） | ✅ 底栏页恒定 46.8，点下去直接进转场淡出；旧版会先掉到 **34.7**（−26%）持续 **7 帧 ≈56ms** |
| 4 | 指数 / 板块页（买指数基金）同样出现，位置一致 | ✅ 买指数基金变窄，按钮在 +自选 与 更多 之间 |
| 5 | 原版「更多」行为零变化 | ✅ 手点仍弹出全部 7 项（条件单 / 智能盯盘 / 智能K线 / 产业链 / 区间统计 / 交易明细 / 股票PK） |
| 6 | 关开关 → 立即恢复原版，无需重启 | ✅ 买指数基金恢复全宽，按钮消失 |
| 7 | 无新增崩溃 | ✅ `logcat -b crash` 中 `com.guosen.android` 计数 0 |
| 8 | **左右翻页后入口还在**（2.15.1 补验，从「自选 / 人气股」这类带 `stockList` 的列表进） | ✅ 左滑 5 次（向日葵→金禄电子→南模生物→百瑞吉→朗特智能）+ 右滑 3 次，**每一次** `uiautomator` 里都查得到底栏的「智能盯盘」（位置 `784..920`，夹在 `+自选` 与 `更多` 之间） |

---

## 7. 复现侦察的步骤（App 升级后照做）

```bash
# 1. 进个股详情页（行情页右上角搜索 → 输 600000 → 点第一行）
bash tools/ui.sh tapxy 1007 173
bash tools/ui.sh tap "浦发银行"

# 2. dump 底栏（换屏宽对比带权重 / 固定宽）
bash tools/adbw.sh shell "wm size 900x2400"; bash tools/ui.sh list; bash tools/adbw.sh shell "wm size reset"

# 3. 点「更多」，dump 面板结构 + 确认窗口类型
bash tools/adbw.sh shell "input tap 1006 2271"
bash tools/adbw.sh shell "dumpsys window windows | grep -i popup"
bash tools/ui.sh list

# 4. 点面板里的「智能盯盘」，从探针日志拿 Intent 契约
bash tools/adbw.sh logcat -c
bash tools/adbw.sh shell "input tap 135 1947"
bash tools/adbw.sh logcat -d -v brief | grep -A14 "LAUNCH"
```

拿到之后按功能改对应的候选常量文件：

| 功能 | 改哪里 |
|---|---|
| 自选高亮 | `feature/watchlist/WatchlistTargets.kt` |
| 直达标准行情 + 左右滑动 | `feature/strategyquote/StgDirectQuoteTargets.kt` |
| 多选栏全选 / 反选 | `feature/strategyselect/StgSelectAllTargets.kt` |
| 策略详情页筛选股票 | `feature/stgfilter/StgStockFilterTargets.kt` |
| **底栏智能盯盘入口** | `feature/stockbar/StockDetailBarTargets.kt` |

---

## 8. 实测踩过的坑（改代码前先读）

1. **`ConstraintLayout` 里不带约束的子视图不参与链的测量。**
   直接 `addView` 一个固定宽按钮，它会被丢在容器左上角（`[0,2216][124,2326]`），
   买 / 卖 一个像素都不让。所以「插一个按钮买卖就自动让位」这个直觉是错的。

2. **改约束的路子极脆。** 试过「克隆『更多』的 LP + 把前后两个邻居的约束改接过来」，
   实测只要有一处没接上，整条链就散架：买 / 卖被压成 0 宽、新按钮和「更多」叠在一起。
   **最后改用「只加宽『更多』盒子」**，不碰任何约束字段。

3. **面板项没有可点击祖先。** 宿主的点击挂在 `GridView` 的 `OnItemClickListener` 上，
   项容器本身不保证 `clickable`。往上找可点击祖先会直接落空，
   日志表现为 `「智能盯盘」项不可点击`。必须走 `performItemClick`。

4. **图标两个坑**：
   - `setCompoundDrawables` **不会**给 drawable 设 bounds → 图标拿到了但画不出来；
     要么用 `setCompoundDrawablesWithIntrinsicBounds`，要么显式 `setBounds`。
   - 面板里的图标**比底栏的大**，直接套会把底栏文字顶掉（文字被裁一半）。
     要按「更多」图标的尺寸缩放。

5. **`uiautomator dump` 一次只给「当前可见」的节点。** 第一次 dump 个股页的「更多」面板时
   只看到第 2 行（区间统计 / 交易明细 / 股票PK），差点得出「个股页没有智能盯盘」的错误结论
   —— 截图一看，第 1 行明明白白有「智能盯盘」。**dump 与截图要对着看。**

6. **别全树按文字找「更多」**：K 线周期栏里也有一个「更多」（分时 / 五日 / 日K / 周K / 月K / 更多）。
   按文字兜底时**必须先限定在底栏容器内**。

7. **`wm size` 是判断「带权重」还是固定宽的最快手段**，比猜布局快得多，记得 `wm size reset`。

8. **`Activity.findViewById` 在 `EngineAct` 上拿不到底栏**，要用
   `activity.window.decorView.findViewById`。

9. **「闪一下黑」= 宿主把 Activity 窗口调暗了，且调暗发生在 `show()` 返回之后。**
   实测宿主走 `WindowManager.LayoutParams.alpha = 0.6f`（`dimAmount` 恒为 1.0，
   flags 里没有 `DIM_BEHIND`，所以只有 `alpha` 是有效杠杆）。
   坑在于**时序**：`onPopupShown` 里读到的还是 1.0（还原空转），
   等我们下一帧 `dismiss()` 时宿主自己又还原成 1.0（第二次还原也读到 1.0）——
   **两条还原日志都不会打**，看着像「什么都没发生」，但那 2~3 帧已经被画出来了。
   判据：录屏逐帧测整屏亮度，会看到「掉下去 → 立刻弹回来」的方波（46.8 → 34.7 → 46.7）。
   **事后还原治不了，只能不让它落地**：挂 `WindowManagerGlobal.updateViewLayout`
   （`Window.setAttributes` 与 `WindowManager.updateViewLayout` 的唯一漏斗），
   在我们自己那轮的代次里把 `alpha` / `dimAmount` 夹回快照值。
   直接改 `LayoutParams` 对象本身即可（它与窗口共用），**别动 `flags`**。

10. **判断「到底谁把屏幕弄暗的」用分区测亮度**：`ffmpeg` 的 `crop + signalstats`。
    实测状态栏区（y 0–80）与图表区一起变暗、最底下手势条区（y 2340–2400）**不变** ——
    说明是**应用窗口**被调暗，不是弹层盖了一层黑纱，也不是屏幕整体变暗。

11. `adb` 的无线调试会掉线（`adb server` 被回收），所有设备操作走 `tools/adbw.sh`（自动重连）。

12. **「翻几次之后智能盯盘没了」= 底栏有「每一页各一份」，而查找与注入都没做范围限定**（2.15.1 修）。
    真机 dump 一次 3 页的详情页，视图树里会出现 **3 份 `#stk_detial_bottom_bar`**
    （各自还带自己的 `#bottom_index_container`）。于是：

    - 注入用 `decorView.findViewById(barId)` → **永远只注入第一份**，当前显示的是哪一页无所谓；
    - 「找更多 / 找盒子」也用全树 `findViewById` → 拿第一页的「更多」去同步第二页的底栏，
      在「这个盒子不属于这条底栏」处失败 —— **补上重同步也修不好**，因为每次都会认错；
    - 只在 `onResume` 重同步（翻页不走 `onResume`）；
    - 记账表判据是「我记过这个盒子」而不是「按钮还在不在盒子里」—— 宿主清掉子视图后
      会永远误判成「已经注入过」。

    四条里**前两条是主因**，后两条是「掉了就再也补不回来」的原因。改法：
    找齐所有底栏逐个同步 + 查找一律限定在**那一条底栏**范围内 + 判据换成现场（§2.4）。

13. **第二个自伤招：注入后 `more.parent` 就是我们自己的容器**。
    「更多」被搬进我们的 `LinearLayout` 之后，`more.parent` 不再是 `#fl_more`，
    拿它当盒子的键去查记账表必然 miss → 判成「没注入过」→ 再套一层容器
    （实测一次翻页套了 5 层：`fl_more` 被撑到 1079px，买卖被挤成 0 宽，整条底栏报废）。
    所以盒子要**按资源 id 找**，沿父链兜底时按 `ENTRY_HOLDER_TAG` **跳过我们自己的壳**；
    再留一条硬红线：**「更多」祖先里已有我们的容器就绝不注入**。

14. **「点了『智能盯盘』却弹出『更多』」= 握手顺序反了，标记被白吃掉**（3.1.0 修）。
    早期实现是「谁先看到弹层谁把标记清零」，而**清零发生在验指纹之前**，
    于是三种时序都会让面板落在我们手里却没人接手（都是偶发，越急越容易撞上）：

    | 时序 | 结果 |
    |---|---|
    | 弹层显示时 `contentView` 还是 null / 里面还没 `#gv_stk_bottom` | 标记已被清 → 真正该认领的那次没人接 → 面板留在屏幕上 |
    | 注入后的**图标采集轮**与用户点击撞在一起 | 采集的弹层抢走点击的标记；点击自己那次 `performClick` 又开出第二层，两层都没人认 |
    | 上一轮还没走完用户又点一下 | 同一个标记被覆盖，两次 `performClick` 开出两层，只有一层被认领 |

    另外「采集晚到一步」这条更隐蔽：`maybeHarvestIcon` 里 `moreText.post { performClick() }`
    **不核对自己的轮次**，被点击掐掉之后照样开面板 —— 那一层必然无人认领。

    改法（`StockDetailBarFeature` 的 `Round` / `begin` / `end`）：

    - **先验指纹再认领**，验不过**一个字节都不动**（标记留着，等真正的更多面板）；
    - 认领**同一轮只允许一次**（`Round.claimed` CAS）—— `showAsDropDown` 的嵌套重载会重复回调；
    - 点击与采集**互斥**：点击优先掐掉采集轮，采集在点击在途时**不启动**（稍后重试）；
    - 采集轮弹出前先核对 `round === 自己`，被掐掉就绝不再开面板；
    - 收尾按**身份**核对（`round.compareAndSet(r, null)`），不是无条件清零 ——
      晚到的超时任务不会误清下一轮；
    - `performClick()` 返回 false 说明宿主没响应，立刻结束本轮（不占着标记去劫持手点「更多」）；
    - 窗口快照挂在轮次上，**一轮结束夹子随之中止** —— 旧实现里认领失败时
      `inFlight` 永久非 0，后续用户手点「更多」的调暗会被一直夹掉（连带把转场淡出也夹住）。

    日志特征：正常一轮是「已触发『智能盯盘』(position=1)」；出问题那一轮会在
    `没等到「更多」面板（第 N 轮）`、`让位给用户点击` 或 `不是更多面板 —— 不认领` 里留下痕迹。
