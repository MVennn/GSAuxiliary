# 策略详情页「多选栏 全选 / 反选」实现说明

> 目标：在 **智能选股 → 我的策略 → 策略详情**（`AIStkStgDetailAct`）里，点「勾选加入自选股」
> 进入多选态后，**在「取消」按钮左侧多出一个「全选 / 反选」按钮**，
> 一键把该页**所有**股票勾上 / 取消勾选。
>
> 本模块是 LSPosed 模块，**不改目标 App 源码** —— 全部靠运行时视图注入。
> 本文的控件契约与验证结论**均来自真机实测**（魅族 20 / Android 16 / 金太阳 8.6.0 vc271），
> 不是推断。

---

## 0. 结论先行

| 问题 | 答案 |
|---|---|
| 按钮注入到哪 | 「取消」按钮（`#tv_stg_add_zxg`）的**同一个父 `LinearLayout`** 里，插在它的索引位置 —— 于是自然落在「取消」**左侧**。 |
| 「取消」按钮怎么认 | 它**不是**独立的取消按钮：「正常态 = 文案『勾选加入自选股』，多选态 = 文案『取消』」，是**同一颗 TextView** 换文案。所以「进入多选态」的判定 = 观察它文案变没变成「取消」。 |
| 怎么"点一行" | 多选态下**点击整行**即切换该行勾选框（`#ct_item`，`CheckedTextView`），并同步底部「已选 N 只股票」。所以全选 = 对每一行触发一次行点击。 |
| 怎么避免知道宿主选中模型 | 走 `ListView.performItemClick(...)`，触发宿主**自己的** `OnItemClickListener` —— 与用户手动点行**完全同一条**代码路径，宿主模型与底部计数自然同步。 |
| 屏外行怎么办 | 屏外行没有已绑定的子 View，用 `Adapter.getView(pos, null, lv)` **现造一个探针 View**（按宿主模型绑定勾选态）后传入 `performItemClick`。**不能只遍历 `ListView` 的可见子 View**。 |
| 为什么必须分帧 | 宿主每次切换勾选后会刷新列表（`notifyDataSetChanged`），同一帧里继续复用旧子 View 会失效 —— 真机实测表现为「反选只清掉第一行」。 |

---

## 1. 实测控件契约

`uiautomator dump` 原样（多选态）：

```
ListView #lvStks [0,230][1080,2205]
  └─ (header) LinearLayout [0,230][1080,820]
       └─ LinearLayout [0,648][1080,748]              ← 多选栏所在行
            ├─ TextView #tv_stg_res_num  "策略个股8只"
            ├─ TextView #tv_stg_res_time "(10-15 13:50:08)"
            └─ TextView #tv_stg_add_zxg  "取消"  [927,664][1041,732]   ← 锚点按钮
  └─ (row) FrameLayout [0,820][1080,958]  ← 每只股票一行（行高 138px）
       ├─ ImageView #iv_item_show_choose
       ├─ LinearLayout └─ TextView #tv_item_stk_name / #tv_item_stk_code
       ├─ TextView #tv_item_stk_price / #tv_item_stk_percent
       └─ CheckedTextView #ct_item  ← 勾选框（多选态可见）

底部条 LinearLayout #ll_bottom
  └─ LinearLayout #ll_add_zxg_bottom
       ├─ "已选 " / TextView #tv_bottom_zxg_num / " 只股票"
       └─ TextView #tv_bottom_add_zxg "确认添加"
```

| 角色 | 资源 id | 类型 |
|---|---|---|
| 锚点按钮（「勾选加入自选股」/「取消」） | `tv_stg_add_zxg` | `TextView` |
| 股票列表 | `lvStks` | `ListView`（带 header，外面套 `HeaderViewListAdapter`） |
| 每行勾选框 | `ct_item` | `CheckedTextView` |
| 底部已选计数（裸数字） | `tv_bottom_zxg_num` | `TextView` |
| 底部确认按钮 | `tv_bottom_add_zxg` | `TextView` |

> 列表区 `[0,820][1080,2205]` + 行高 138px ⇒ **一屏约 10 行**。
> 所以「策略个股 32 只」这类长策略天然就有 20+ 行在屏外，是检验"是否只选了可见行"的现成样本。

---

## 2. 实现方式

代码在 `feature/strategyselect/`：常量在 [StgSelectAllTargets.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/strategyselect/StgSelectAllTargets.kt)，
实现在 [StgSelectAllFeature.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/strategyselect/StgSelectAllFeature.kt)。

### 2.1 挂钩点：`Activity.onResume` + 锚点按钮的 `TextWatcher`

```
Activity.onResume（只对 AIStkStgDetailAct）
  └─ 找到 #tv_stg_add_zxg，确保挂上（幂等）一个 TextWatcher
       └─ 文案变化时 → sync()
            ├─ 文案 == "取消" → injectSelectAll()   （进入多选态）
            └─ 其它           → removeInjected()   （退出多选态）
```

**为什么不直接 Hook `TextView.setText`**：`setText` 是全 App 的**超热路径**
（行情页实时报价每秒都在刷），全局 Hook 它会给整个 App 带来可感知的卡顿。
只盯这一颗按钮，零全局开销。

### 2.2 注入：从「取消」克隆，保证样式一致

`buildSelectAll()` 逐项复制源按钮的**可见样式**与 **LayoutParams**：

- 样式：`background`（含状态 `constantState`）、`backgroundTintList`、`textColors`、`typeface`、
  `textSize`、`textScaleX`、`letterSpacing`、`padding`、`compoundDrawables`、`gravity`、
  `includeFontPadding`、`ellipsize`、`maxLines`、`maxWidth`、`minWidth`、`minHeight`、`foreground`。
- LayoutParams：`LinearLayout.LayoutParams` / `MarginLayoutParams` / `LayoutParams` 三种都按原类型复制。

三处刻意的处理：

| 处理 | 原因 |
|---|---|
| **间距只加在自己身上**：`copy.rightMargin = 16dp`，**不动「取消」任何属性** | 用户硬验收是「全选按钮的**显示样式与取消按钮完全一致**」；间距是**两个按钮之间**的距离，不是按钮内边距。改「取消」的 padding 会破坏这条验收。 |
| **独立 id**：`View.generateViewId()` | 克隆品若沿用源的 `id`，`findViewById` 会歧义，"防重复注入"的幂等判断也会失效。 |
| **`minWidth` 用 `paint.measureText` 量**，取「全选」「反选」里更宽的那个 | 文案在「全选 ↔ 反选」间切换时宽度不变，按钮不抖、也不会把「取消」挤出屏幕。**不能**取 `src.width` —— 文案切换途中会拿到旧文案（「勾选加入自选股」5 字）的宽度，把按钮撑过头。 |

### 2.3 全选 / 反选：先确定每行状态 + 只点要改的行 + 分帧 + 点完核对

```
toggleAll()
  ├─ takeShot()：读现场（适配器 / 总行数 / 子集→全量映射 / 每行状态）
  │     ├─ 宿主计数 == 0        → 全部未选（不建视图；仅首轮）
  │     ├─ checked states 可用  → 读它（O(行数)、不建视图；与「确认添加」同一份表）
  │     │     没有筛选时还要求「表里勾选数 == 宿主计数」，对不上就改用探针
  │     └─ 否则                 → 逐行 adapter.getView(i, null, lv) 现绑探针读 #ct_item
  ├─ wantChecked = StgSelectAllPlan.wantChecked(before)   // 还有没勾上的 → 全选
  ├─ click = StgSelectAllPlan.rowsToClick(before, wantChecked)
  │     // 只点「状态与目标不同」的行 —— 行点击是取反的
  ├─ clickNext(click[0..n])，每行之间 postDelayed 16ms
  │     ├─ 运行令牌 / header 数 / 筛选下标快照校验
  │     │     筛选结果变了 → **重开一轮**；适配器实例换了 → 中止（可能是别的页面）
  │     ├─ pos = header + full[index]                  // 子集下标 → 全量下标（快照）
  │     ├─ 可见行 → 传已绑定的真实子 View；屏外行 → 再造一个探针 View
  │     ├─ lv.setItemChecked(pos, !wantChecked)
  │     │     // 摆成「点击前」，取反后才一定落到目标；它还会维护 checkedItemCount
  │     ├─ StgFilterBridge.runWithRemapBypassed {
  │     │      lv.performItemClick(row, pos, adapter.getItemId(index))
  │     │  }
  │     ├─ 这一行失败 → 只计数（本轮第一条记 WARN），下一轮按表核对
  │     └─ 筛选生效时顺手 refreshVisibleChecked()（见 §2.3.2）
  ├─ 一轮点完 → 隔 48ms 重读 checked states
  │     ├─ 还有行不对 → 再补一轮（只点这些行；最多 4 轮**点击**，重开轮不计）
  │     └─ 全部到位   → 收尾：refreshVisibleChecked() + 下一帧 pre-draw 再刷一次 + updateLabel()
  └─ 适配器实例在运行中换了 → 中止（不把「全选」施加到另一批股票上）
```

要点：

1. **状态来源按优先级选，绝不读屏上那颗勾选框**（2026-10-21；2026-10-23 起宿主模型优先）：
   合成点击后宿主不会重画部分可见勾选框（§4.1），读屏上那颗会拿到过期状态 ——
   这正是「重复全选部分选不中」的根因。顺序是
   **「宿主 `checkCodes`（能读且行代码映射完整）」→「宿主计数 0 → 全部未选」→
   「checked states」→「逐行探针」**。模型是底部计数 / 确认添加的来源，能看见
   「宿主静默忽略了一次合成点击」；模型不可用（字段读不到 / 任一行代码读不到 /
   没有筛选时计数 > 0 但模型空表或一只都对不上）时整趟退回后三级。
   中间那档最省（不建视图），最后一档只在表不可用或与计数矛盾时才走。
2. **只点状态与目标不同的行**：行点击是**取反**的，对已勾上的行再点一次就是取消。
3. **屏外行必须覆盖**：`index` 从 `0` 走到 `total-1`，屏外行靠 `Adapter.getView(pos, null, lv)` 造视图；
   不看 `ListView.childCount`（那只是可见行数）。
4. **一行一帧（`postDelayed` 16ms）+ 防重入**：宿主每切换一行就刷新列表，同帧复用子 View
   会拿到失效视图；一轮没跑完时再点按钮直接忽略（两轮会各自按旧快照点同一批行）。
5. **`#ct_item` id 解析失败 → 保守放弃**并记日志。若拿不到勾选态，`checked` 会恒为 `false`，
   于是对**已选行**也发点击 → 「全选」会变成「反选」。宁可不动，也不能点反。
6. **筛选生效时必须传「全量下标」**（见 §2.3.1），并在每轮开始时把下标表**快照**下来；
   运行中筛选结果变了就**重开一轮**（重新快照后只补没到位的行），不再中止整次操作 ——
   中止会把「点了半截」留在界面上（2026-10-22）。**适配器实例**换了则中止：那可能意味着
   换到了另一个策略页，继续点会把「全选」施加到别的股票上。
7. **用 `setItemChecked` 预置状态，不直接改 `mCheckStates`**：前者会一并维护
   `mCheckedItemCount` / `mCheckedIdStates`（宿主可能用它们渲染「已选 N」）。
   预置本身失败 → 这一行本轮跳过，下一轮再试。**点击抛异常时不回滚**：框架在调宿主监听器
   之前就已经把表取反写好了，回滚会把「宿主可能已经更新的模型」与表拆开，下一轮再点
   反而变成反向操作。
8. **只对普通多选模式生效**：`CHOICE_MODE_SINGLE`（点击会清掉别的行）与
   `CHOICE_MODE_MULTIPLE_MODAL`（action mode 活跃时框架不把点击派给宿主监听器）
   都不注入按钮，日志里写明 `choiceMode`。
9. **点完要核对（2026-10-22）**：一轮点完隔 48ms 重读 checked states，还有行没到位就补点，
   最多 4 轮**点击**（之后还会再核对一次；适配器 / 筛选 / header 变化导致的「重开轮」
   不点行，不消耗这个预算）。补点轮以 checked states 为准 —— 宿主计数可能还没跟上，
   拿它判会把整单选中的行再点一遍（全部取消）。**例外**：没有筛选时若表与宿主计数分叉，
   说明宿主模型漏了行（真机「宿主吞掉某次点击」），退回探针按模型补。
   没有 checked states（`CHOICE_MODE_NONE`）时无从核对，只跑一轮。
10. **补点救不了「宿主监听器静默忽略」**：`performItemClick` 在调到监听器**之前**就写了
    `mCheckStates`（框架 `AbsListView.java:1231-1232`，且无条件 `handled=true`），
    下一轮读表只会看到目标态。这类漏选只能靠真机日志与宿主行为去查（§4.3）。

### 2.3.1 与「策略详情页筛选股票」叠加（2026-10-20 真机修复）

筛选用一层**过滤代理**挂在 ListView 上：列表里第 N 行对应宿主全量数据的 `keptIndices[N]`。
这里有两套坐标，**不能混用**：

| 坐标 | 谁在用 |
|---|---|
| **子集下标**（0..保留数-1） | 过滤代理的 `getView` / `getItem`；`performItemClick` 的 `position` 初值 |
| **适配器位置**（header + 子集下标） | `AbsListView.mCheckStates`、屏幕上的 `CheckedTextView`、`mCheckedItemCount` |
| **全量下标**（宿主 `data` 的 0..N-1） | 宿主的多选模型（`checkCodes` 等）；手动点行要转给宿主的 `position` 是 `header + 全量下标` |

事故现场（50e / 21 只 / 涨家筛选后可见 301330、300815、002956）：

- 旧实现把**子集下标**交给 `performItemClick`，再由筛选 wrapper 在转发给宿主监听器时换成全量下标；
- 但 `AbsListView.performItemClick` 在调到 wrapper **之前**就用传入的 position 写了
  `mCheckStates` —— 宿主的多选模型读的正是这份表；
- 结果：界面勾着 A/B/C，`确认添加` 收到的却是全量前 3 只（688787、688768、600178）。
  第一次可能刚好加进去，第二次这 3 只已经在自选里 → 宿主提示「已在该组合」。

修复分两半，缺一不可：

1. **合成点击直接传全量下标**：`pos = header + fullIndexAt(index)`，并用
   `StgFilterBridge.runWithRemapBypassed { … }` 让筛选 wrapper **原样放行**
   （否则它会把全量下标再当子集下标映射一次）。这样框架写的 checked states 与宿主收到的
   position 都是全量坐标。放行标记也覆盖过滤代理的 `getItem` / `getItemId` ——
   `setItemChecked(全量下标)` 维护 `mCheckedIdStates` 时不会再映射一次（2026-10-21）。
2. **手动点行只改转发给宿主的 position，不搬 checked states**（2026-10-21 真机修正）：
   框架按**适配器位置**（header + 子集下标）取反写 `mCheckStates`，屏幕上的
   `CheckedTextView` 与 `mCheckedItemCount` 读的就是这一格；宿主需要的是
   `header + 全量下标`。两边各用各的坐标 —— 把状态搬到全量位置会让勾选落到**下一行**
   （真机：点第 3 行勾中第 4 行）。宿主模型由 `onItemClick(mapped)` 更新，实测分组页
   能正确识别被点股票是否在自选里。

另外，**可见行必须传真实子 View**：宿主的点击处理会就地把这一行的勾选框打上；
用脱离列表的探针 View 只会改到探针，屏幕上看不到勾。屏外行仍然用探针 View。
**读状态则相反**：一律用现绑的探针视图，不读屏上那颗（它可能没被重画，见 §4.1）。

#### 2.3.2 显示刷新为什么连行根的 `activated` 一起刷（2026-10-22）

`AbsListView.performItemClick` 每次都会调 `updateOnScreenCheckedViews()`，把**行根视图**的
`activated` 按 `mCheckStates[适配器位置]` 重写。筛选生效时我们的 checked states 用的是
**全量位置**（宿主模型读这份表），两套坐标错位 → 每次合成点击都会把一部分可见行刷回
「没勾」。宿主若用 `duplicateParentState` / `state_activated` 画勾，只设 `#ct_item.isChecked`
修不回来 —— 这正是「确实全选了，但有些股票不显示勾」的来源。

`refreshVisibleChecked()` **优先按宿主 `checkCodes` 的「这一行是哪只股票」判**，
读不到才退回「行 → 全量下标 → 表」；行根 `Checkable.isChecked`、`#ct_item.isChecked`、
`activated` **三处一起写**（行根是 `CheckedFrameLayout`，框架 `setupChild()` /
`updateOnScreenCheckedViews()` 刷的就是它），只改显示、不写 checked states / 宿主模型。
筛选生效时每点一行就刷一次（否则运行中会一部分勾、一部分没勾），收尾再刷一次，
并在**下一帧 pre-draw**（布局之后、绘制之前）再补一次 —— 宿主的 `notifyDataSetChanged`
→ 布局可能比收尾晚，`ListView.setupChild()` 会按适配器位置重写行根 `activated`，
把收尾刷的值盖掉。pre-draw 是「这一帧布局已完成」的确定性时点。

> ✅ **2026-10-27 起：多选态期间常驻「绘制前画勾」**（第三次报「有些行不显示勾」）。
> 前两次修的都是「点完那一刻」——收尾刷一次 + 下一帧 pre-draw 再刷一次；宿主之后
> 任何一次 `notifyDataSetChanged`（行情推送、筛选重算、从别的页面回来）都会重新绑行，
> `setupChild()` 按「适配器位置」重写行根，筛选下与全选写的「全量位置」错位，勾又被盖回去。
> 现在进入多选态时在 `lvStks` 的 `ViewTreeObserver` 上挂一个**常驻** `OnPreDrawListener`
> （`StgSelectAllFeature.installVisualSync`）：每一帧绘制之前按宿主 `checkCodes`
> 把可见行重画一遍，退出多选态 / 关开关 / 页面销毁时摘掉。
> 判据是「这一行是哪只股票」—— 行代码从过滤代理的 `getItem` 取，代理自己把子集下标
> 映射回全量，所以两套坐标的问题被彻底绕开；未筛选时行代码读不到则退回 checked states
> （那份表此时就是准的），不再整行跳过。只在显示与目标不一致时才写，
> 不会每帧 `invalidate`。**仍然只改显示**：不写 checked states、不碰宿主模型
> （用户验收口径：勾只是给别人看的，计数 / 确认添加由宿主自己维护）。

每次刷之前逐行核对筛选下标表是否还是当前这份，对不上就整趟不刷（绝不拿旧表往新列表上画）。
**歧义行不画**：手动点行写的是适配器位置、全选写的是全量位置；当某行的全量下标恰好也是
另一个子集行的下标时，表里那个 key 分不清是谁写的 —— 宁可不画，交给宿主自己渲染。

### 2.4 文案随状态切换（全选 / 反选）

| 状态 | 文案 | 点击后 |
|---|---|---|
| 未全选 | **全选** | 勾选该页全部股票 |
| 已全选 | **反选** | **取消全部选中** |

重算文案的三个时机：

1. **进入多选态**（`TextWatcher` 触发 `sync → injectSelectAll → updateLabel`）；
2. **底部「已选 N」变化**：给 `#tv_bottom_zxg_num` 挂 `TextWatcher`（手动点行、
   宿主自己改选中都会更新它）；
3. **点了全选/反选按钮自己**（`stepToggle` 跑完最后一帧时刷新）。

> ⚠️ **不要再包裹 ListView 的 `OnItemClickListener`**：它和筛选层的下标回映射会互相重包，
> 叠成双重映射；而且宿主的选中模型看的是 checked states，不是转发出去的 position 参数。

---

## 3. 模块侧改动清单

| 文件 | 作用 |
|---|---|
| `feature/strategyselect/StgSelectAllTargets.kt` | 上面那张控件契约表（全部做成候选数组，App 升级只改常量） |
| `feature/strategyselect/StgSelectAllFeature.kt` | 功能本体 |
| `feature/strategyselect/StgSelectAllPlan.kt` | 方向判定与待点行（纯逻辑，可单测） |
| `feature/stgfilter/StgFilterBridge.kt` | 筛选生效时的「子集下标 → 全量下标」通道，以及合成点击的放行标记 |
| `feature/FeatureManager.kt` | `features` 列表登记 |
| `core/ConfigKeys.kt` | `STG_SELECT_ALL` 键 + `DEFAULT_*=false` + 进 `ALL_KEYS` / `FEATURE_KEYS` / `DEFAULTS` |
| `core/ConfigSnapshot.kt` | `stgSelectAll` 字段 + 进 `fingerprint` + `DEFAULT` |
| `core/ConfigSource.kt` | `read()` 补一行 |
| `ui/FeatureCatalog.kt` | 展示元数据（挂在「策略页」分区，无二级页 —— 没有可调参数） |
| `res/values/strings.xml` + `values-en/strings.xml` | 功能标题 / 摘要 / 开关文案（中英同步） |
| `app/DebugConfigProvider.kt` | `stg_select_all` 列，供脚本化开关 |

> **开关是功能总开关，出厂默认 `false`** —— 见 `docs/EXTENDING.md` 第 4 步的硬约定。
> 要生效需在模块设置「功能」Tab 手动打开，且**打开后要重启目标 App**
> （配置变化只走 `onConfigChanged`，不会补装钩子）。

---

## 4. 真机验证结果

真机：魅族 20 / Android 16 (SDK 36) / LSPosed 2.2.1 / 金太阳 8.6.0 (vc271)，
模块 2.12.1 (vc32)，被测策略为 **`100e`（32 只股票，一屏约 10 行 ⇒ 22 行在屏外）**。

| # | 验证项 | 结果 |
|---|---|---|
| 1 | 按钮位置与尺寸 | 注入按钮 `[771,664][885,732]`，与「取消」`[927,664][1041,732]` **同为 114×68**，位于其**左侧**，间距 **42px ≈ 16dp** |
| 2 | 样式一致（像素级） | 两按钮主色均为 `#E8E8F8`、文字色均为 `#285098`，像素占比接近（6426/685 vs 6329/752） |
| 3 | 点「全选」 | 底部计数 → **「已选 32 只股票」= 全部行数**；按钮文案 → **「反选」** |
| 4 | **屏外行覆盖** | 滚到列表**最底部**，可见的 13 个 `#ct_item` **全部 `checked=true`** |
| 5 | 点「反选」 | 底部计数 → **0**；可见勾选框全部 `checked=false`；文案 → **「全选」** |
| 6 | 反复进出多选态 ×2 | 退出后按钮数 **0**，重新进入后 **1**（无重复注入） |
| 7 | 关闭开关 `stg_select_all` | 按钮**即时消失**（0 个）；重新打开并重进多选态 → 恢复 1 个 |
| 8 | 全程 crash | `logcat -b crash` 中 `ClassCastException` / `FATAL` 计数 **0** |

### 4.1 2026-10-20 追加：与筛选叠加（真机）

设备 / 宿主不变，策略换成 **50e（21 只）**，筛选条件「涨家股票」（保留 3 只）。

| # | 验证项 | 结果 |
|---|---|---|
| 9 | 筛选生效时手动点第一行 | `ChooseGroupAct` 收到的股票 = **301330**（屏幕上那只），不是全量第 1 只 |
| 10 | 筛选生效时点「全选」 | 底部「已选 3 只股票」；确认添加收到的 codes = **[301330, 300815, 002956]**（= 可见的 3 只） |
| 11 | 同一组合再选一次 | 这 3 只已在自选里 → 宿主显示「已在该组合」（**语义正确**；修复前是选错了股票导致的误报） |
| 12 | 非连续子集（19/23，排除科创板） | 底部「已选 19 只」；确认页 codes **len=19 且不含 688xxx**；手动点首行 → codes = **[600178]**；「反选」→ 计数 0、文案回「全选」、可见勾选框全部清空 |

> ⚠️ **观感局限（2026-10-22 已按新根因重做，待真机验证）**：合成点击后宿主不会重画**部分**
> 可见勾选框 —— 50e / 19 只筛选结果实测：底部「已选 19 只股票」、确认页 19 只全对，
> 但屏幕 9 个可见勾选框只画出 6 个；**手动点行**与**反选**的勾选显示正常。
> 根因：`performItemClick` 内部的 `updateOnScreenCheckedViews()` 按**适配器位置**重写
> 行根视图的 `activated`，而筛选生效时 checked states 用的是**全量位置** —— 两套坐标错位，
> 每点一行都会把一部分可见行刷回「没勾」；宿主若用 `duplicateParentState` / `state_activated`
> 画勾，只设 `#ct_item.isChecked` 修不回来（2026-10-21 的 `repaintVisible` 只做了这一半）。
> `invalidateViews()` 与「预置视图位置」两种旧补法无效（后者还会让确认页多出股票）。
> 现在 `refreshVisibleChecked()` 按「全量下标 → 表」取真实状态，`activated` 与 `isChecked`
> 一起刷，只改显示、不碰宿主模型；筛选生效时每点一行刷一次，收尾再刷一次（§2.3.2）。

### 4.2 2026-10-21：重复全选「部分选不中」

真机（用户报）：**做过一次全选以后，下次在同一个策略详情页点全选，部分行选不中**。

根因有两层，都在「判据」上：

1. **读错了状态**：旧实现在需要逐行判断时（`cur` 既不等于 0 也不等于总数），
   对**可见行**读的是屏上那颗 `#ct_item`。而上一轮合成点击后宿主不重画部分勾选框（§4.1），
   屏上显示「没勾」、宿主模型里其实「已勾」→ 这一轮又对它发了一次点击。
   行点击是**取反**的 → 已经选中的行被点掉 = 部分选不中。
2. **取反写 checked states 撞上残值**：`performItemClick` 按 `position` **取反**写
   `mCheckStates`。上一轮留下的 true 会让「勾上」变成「取消」。

修复：

- **状态来源按优先级选，不读屏上那颗勾选框**（`readRowStates` + `StgSelectAllPlan.stateSource`）：
  ① 宿主计数为 0 → 全部未选；② 读框架的 checked states（与「确认添加」同一份表，
  O(行数)、不建视图；没有筛选时还要求「表里勾选数 == 宿主计数」）；
  ③ 只有它不可用 / 与计数矛盾时才逐行现绑探针（`adapter.getView(i, null, lv)`）。
- **只点状态与目标不同的行**（`StgSelectAllPlan.rowsToClick`）：
  已符合目标的再点一次就是取消。
- **点击前用 `lv.setItemChecked(pos, !wantChecked)` 预置状态**：
  取反后必然落到 `wantChecked`；用公开 API 而不是直接改 `mCheckStates`，
  这样 `mCheckedItemCount` / `mCheckedIdStates` 一并维护；点击抛异常时写回旧值。
- **防重入 + 可取消**：一轮没跑完时忽略新的点击；关开关 / 退出多选态时
  `cancelRun()` 让正在分帧的链立刻停。
- **运行中校验快照**：启动时把「子集→全量」下标表快照下来（并要求列表上挂的确实是过滤代理，
  避免读到上一页留在全局桥里的旧表）；中途筛选下标变了就**重开一轮**，
  适配器实例换了就中止（可能是别的页面）。
- **只对普通多选模式生效**：单选 / 多选模态下不注入按钮（`toggleAll` 里也有同一道守卫）。
- **手动点行的 wrapper 不搬 checked states**：框架写在**适配器位置**（header + 子集下标）
  上的取反结果就是屏幕 / 计数读的那一格，保持不动；只把 `header + 全量下标` 转给宿主。
  搬到全量位置会让勾选落到下一行（2026-10-21 真机：点第 3 行勾中第 4 行）。
- **`performItemClick` 的 id 改用代理的 `getItemId(index)`**：`lv.getItemIdAtPosition(全量位置)`
  在全量下标小于子集行数时会被代理再映射一次，拿到别的股票的 id。
- **只在过滤代理确实挂在列表上时才发布下标表**：否则「全选」会把子集下标当全量下标用。
- **文案与动作共用同一判据**（`updateLabel` 也读 checked states）：
  否则筛选后会出现「文案写着全选、点下去却在取消」。

单测：`StgSelectAllPlanTest` 13 条（方向判定 / 待点行 / 状态来源优先级）。

> **2026-10-21 真机已验证**：筛选叠加全选 / 反选、33 只重复全选、筛选后手动点行
> （屏幕勾选与宿主分组页都正确）、筛选后直达行情左右滑动。
> 装机后按 §5 第 9 条复验；重点看「连续点」与「筛选 + 全选」两种组合。

### 4.3 2026-10-22：显示缺勾 + 概率漏选（代码修复，真机待验）

用户报：**全选确实生效了（计数 / 确认页都对），但中间有些股票不显示勾；而且点全选
也有概率有一些勾不到。**

代码层查出两个来源，都是「单轮 + 只看一半的显示」造成的：

1. **显示缺勾**：见 §4.1 / §2.3.2 —— `updateOnScreenCheckedViews()` 按适配器位置重写
   `activated`，筛选期间与全量位置的 checked states 错位。现在 `refreshVisibleChecked()`
   同时刷 `activated` 与 `isChecked`，筛选生效时每点一行刷一次，收尾 + 下一帧 pre-draw 再刷。
2. **概率漏选**：单轮点完就收工，任何一次「没走到」的点击（筛选结果在运行中变化、
   `getView` 造不出探针、宿主事后把表改回去）都会留下「没勾到」的行。现在：
   - 一轮点完隔 48ms 重读 checked states，只补点仍不对的行，最多 4 轮**点击**（§2.3 第 9 条）；
   - 运行中筛选映射变了不再中止，直接重开一轮；适配器实例换了才中止（可能是别的页面）；
   - 没有筛选时若表与宿主计数分叉（宿主模型漏行），补点轮退回探针按模型补；
   - 每行失败只计数（本轮第一条记 WARN），不会 4×N 条刷屏。

> ⚠️ **补点救不了「宿主监听器静默忽略」**：`performItemClick` 在调到监听器之前就按取反
> 写了 `mCheckStates`，下一轮读表只会看到目标态。如果真机复现「计数满了但确认页少几只」，
> 那是宿主自己的点击处理没执行，需要单独抓宿主日志 —— 本模块从外部观察不到。

日志口径（装机后排查看这几条）：

```
策略全选：全选 开始（共 N 只，已勾 X，待点 Y）
策略全选：第 1 轮结束（点了 Y 行，失败 0，宿主计数 0 → N）
策略全选：第 2 轮补点（共 N 只，已勾 Z，待补 W，点击轮 2/4）   ← 只在有缺口时出现
策略全选：完成（2 轮 / 点击 2 轮，共 N 只，目标 全勾）
```

若出现「已达补点上限」或「适配器在运行中换了」，把该段日志留下来。

### 4.4 2026-10-23：全选后个别对钩不显示、再点一下计数 +1

用户报：**全选确实生效了（底部计数 / 实际选择都对），但个别行的对钩显示没勾；
再点一下那一行，对钩出现，底部「已选 N」反而 +1。**

#### 根因（三层，都在「看哪一层状态」上）

1. **显示刷错了层**。宿主行根是 `com.guosen.commonui.CheckedFrameLayout`
   （`Checkable`），勾选框 `#ct_item` 的 checkMark 是 `drawable/cb_square_bg_15`，
   选择器只认 `state_checked`。框架的 `AbsListView.setupChild()` /
   `updateOnScreenCheckedViews()` 刷新的是**行根**的 `setChecked(...)`；
   原实现只写 `#ct_item.isChecked` 和行根 `activated`，没写行根的 `isChecked` ——
   下一次布局按行根状态重写，勾就时有时无。
2. **验证看的是自己刚写过的表**。宿主的多选模型是 `AIStkStgDetailAct.checkCodes`
   （`List`，探针 `PAGE[STG]` 实例字段实测）。`performItemClick` 在调到宿主监听器
   **之前**就把 `mCheckStates` 取反写好了（框架 `AbsListView.java:1231`）—— 宿主监听器
   若静默忽略、或按旧视图状态判了方向，表里仍是目标态，补点轮永远发现不了漏选。
   用户看到的「再点一下 +1」就是宿主模型里被加了重复项（或漏了一行）。
3. **太快**。16ms 一行时，宿主每行的 `notifyDataSetChanged()` 还没布局完，
   下一行拿到的仍是旧绑定的子 View，给「按视图判方向」留了竞态窗口。

#### 修法

| 改动 | 位置 |
|---|---|
| 状态判据**优先读宿主 `checkCodes`**；行代码按 `m_stkCode` / `m_codeAndMarket` 候选提取，任何一行读不到就整趟退回 checked states（绝不把「读不到」当「没选」）；字段按**条目类**缓存（失败也缓存） | `readHostModel` / `rowCode` / `resolveCodeFields` / `StgSelectAllPlan.statesFromCodes` |
| 首轮「宿主计数 0 但模型非空」→ **放弃本次操作**：两份数据分叉时拿模型判会把「反选」变成「全选」，反向点击 | `takeShot` |
| 补点轮按宿主模型核对：模型漏行就再点；模型里有重复代码只记 WARN（**打码**，不直接改宿主 List，避免计数控件不同步） | `takeShot` / `duplicateCodes` |
| 显示刷新同时写**行根 `Checkable.isChecked`** + `#ct_item.isChecked` + `activated`，有模型时按「这一行是哪只股票」判，绕开筛选的两套下标 | `refreshVisibleChecked` |
| 合成点击前把**行视图**也摆成「点击前」（行根 + `#ct_item`），不只预置框架表 —— 宿主若按视图判「加/删」，方向才是确定的 | `clickRow` |
| 逐行间隔 `16 → 40ms`、轮间静置 `48 → 120ms` | `TOGGLE_STEP_MS` / `ROUND_SETTLE_MS` |

> **模型不可用 / 不可信的情况**：① 字段读不到；② 任一行代码读不到；
> ③ 没有筛选时「计数 > 0 但模型里一只都对不上（或模型空表）」。
> ①②③ 都整趟退回 checked states / 探针；③ 防的是宿主换了代码格式
> （如 `市场.代码`）时把已勾行全判成没勾、点一遍反而全取消。
> ④ 首轮「计数 0 但模型非空」不退回，直接**放弃本次操作** —— 两份数据分叉时
> 拿模型判会把「反选」变成「全选」，反向点击比不点更糟；补点轮不适用
> （合成点击比计数刷新快，计数 0 是常态）。
> 日志里会留下「宿主模型不可用（…）」「宿主模型元素不是股票代码（…）」
> 「第 N 行取不到股票代码（item=… code=…）」。
>
> ⚠️ **已知理论风险（复核 2026-10-23）**：如果宿主某行的代码字段恰好取到另一个
> 6 位值（不是股票代码），模型会把该行判成没勾。候选字段是 `m_stkCode` /
> `mStkCode` / `code` / `mCode` / `m_codeAndMarket`，正常不会命中这种值；
> 真出现「模型与表不一致」日志时，先核对宿主条目结构再改候选数组。
>
> **为什么不直接把重复项从 `checkCodes` 里删掉**：那是宿主自己的 `List`，删完
> `#tv_bottom_zxg_num` 不会自动重算，界面会显示一个和模型不一致的计数；而「重复」
> 只在方向判错时产生，堵住方向判错（视图预置）比事后修补更稳。

#### 真机验证状态（2026-10-23）

- 第一轮（只接入 `checkCodes`、尚未补 `CAM_FIELDS`）：100e / 27 只，点「全选」后
  底部计数 **27**，屏上 9 个可见 `#ct_item` **全部 `checked=true`**；
  日志出现「已解析宿主选中模型字段 checkCodes」，随后「宿主模型不可用（…），
  退回 checked states」—— 宿主条目只暴露 `m_codeAndMarket` 组合字段，已补进候选。
- 补 `CAM_FIELDS` 与复核加固（重复代码打码 / 计数 0 保护 / 按类缓存 / 契约登记）
  后重装模块，宿主登录态失效（「您的登录信息已失效」），**第二轮未复验**。
  下次登录后重点看这几条日志：

```
策略全选：已解析宿主选中模型字段 checkCodes
策略全选：宿主模型 checkCodes=N 项，列表已勾 X/N
策略全选：宿主模型与 checked states 不一致（模型 A / 表 B），按模型   ← 只在分叉时出现
策略全选：宿主模型里有重复代码 60****, 00****（共 N 项）             ← 只在重复时出现，已打码
策略全选：宿主模型不可用（模型与列表/计数不一致），退回 checked states ← 模型不可信时
策略全选：宿主计数为 0 但模型有 N 项，放弃本次操作（两份数据不一致，请重试）
```

若仍出现「行代码映射不全」，日志会带条目类名与取到的 `code` 值 ——
按它补 `StgSelectAllTargets.CODE_FIELDS` / `CAM_FIELDS` 即可，不用改实现。

---

## 5. 验收标准

1. 多选态下，「全选 / 反选」出现在「取消」**左侧**，**尺寸、配色、字号、内边距与「取消」一致**。
2. 点「全选」→ **该页全部股票**被勾选（含屏外行），底部「已选 N」== 列表总行数，按钮文案变「反选」。
3. 再点「反选」→ 全部取消，底部计数归 0，按钮文案回「全选」。
4. 手工点任意行后，按钮文案能跟随「是否已全选」实时切换。
5. 反复进出多选态不会出现第二个按钮。
6. 关掉 `stg_select_all` → 按钮消失（即时生效）；重开并重进多选态 → 按钮回来。
7. 目标 App 无新增崩溃；release 包 `Lio/github/libxposed/api` 定义类 0。
8. **筛选生效时**：全选勾中的行 = 页面可见的股票；确认添加收到的代码与之一致
   （不能是全量前 N 只）；手动点行也选中屏幕上那一只。
9. **重复点**：全选 → 反选 → 全选、全选 → 手动改几行 → 全选 —— 每次结束都应达到
   目标状态（全勾或全不勾），**不能有行反向**（2026-10-21 修的就是这个）。
10. **屏幕显示与表一致（2026-10-22）**：全选后**屏上可见的每一行**勾选框都显示为勾上
    （包括筛选生效时），滚到列表任意位置再滚回来也保持一致；反选后全部显示为空。
11. **筛选 + 手动勾选 + 全选（2026-10-22 新增用例）**：筛选生效时先手动勾 1~2 行，
    再点「全选」—— 已手动勾上的行**不能被点掉**（它们会被再次点一次 = 取消），
    底部计数 = 筛选后行数，确认页与屏幕一致。
12. **补点轮不误伤**：全选后立刻再点一次（按钮此时是「反选」）→ 全部取消；
    再点「全选」→ 全部勾上。每轮结束都达到目标，不能出现「点两次才到位」的中间态。
13. **对钩与宿主模型一致（2026-10-23）**：全选后屏上**每一个可见行**都显示勾；
    再点任意一行应是「取消」、底部计数 **-1** —— 不能出现「点一下反而 +1」
    （那说明宿主模型里被加了重复项）。反选后同理，计数与对钩一起归零。

---

## 6. App 升级后怎么重新侦察

控件 id 变了（或按钮不再出现）时，按下面顺序重跑一遍：

```bash
DEV="$(bash tools/dev.sh)"                 # 无线调试端口每次都可能变
bash tools/ui.sh list                      # 列元素：坐标 / 可点击 / 文字
bash tools/ui.sh tap "我的策略"
bash tools/ui.sh tap "100e"                # 进策略详情（挑股票数多的那条）
bash tools/ui.sh tap "勾选加入自选股"        # 进多选态
bash tools/ui.sh list                      # 记下「取消」与各勾选框的 bounds
```

再确认底部计数控件：

```bash
bash tools/ui.sh list | grep -E "已选|只股票"
```

把新的 id / 文案写回 `StgSelectAllTargets.kt` 的**候选数组**即可，不用改实现逻辑。

> 环境坑（无线调试掉线、mDNS 抢设备、`adb pull` 路径被改写、`/data/adb/lspd/config/modules_config.db`
> 是陈旧快照）见 [`../tools/README.md`](../tools/README.md) 与
> [TROUBLESHOOTING.md §9「设备相关（自动化脚本）」](TROUBLESHOOTING.md#9-设备相关自动化脚本)。
