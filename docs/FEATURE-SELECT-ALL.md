# 策略详情页「全选 / 反选」实现说明

> **一句话**：在策略详情页的多选态里、于「取消」左侧注入一颗「全选 / 反选」，用合成行点击把整页（含屏外行）一次勾完，并让屏幕显示、宿主计数与「确认添加」的提交包三者始终一致。

场景：**智能选股 → 我的策略 → 策略详情**（`AIStkStgDetailAct`）→ 点「勾选加入自选股」进入多选态。本模块是 LSPosed 模块，**不改目标 App 源码**，全部靠运行时视图注入；控件契约与结论均来自真机实测（魅族 20 / Android 16 / 金太阳 8.6.0 vc271 起，宿主版本差异在正文标注），不是推断。

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
       ├─ TextView #tv_item_stk_name / #tv_item_stk_code
       ├─ TextView #tv_item_stk_price / #tv_item_stk_percent
       └─ CheckedTextView #ct_item  ← 勾选框（多选态可见）

底部条 LinearLayout #ll_bottom └─ LinearLayout #ll_add_zxg_bottom
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

> 列表区 `[0,820][1080,2205]` + 行高 138px ⇒ **一屏约 10 行**。「策略个股 32 只」这类长策略天然有 20+ 行在屏外，是检验「是否只选了可见行」的现成样本。

---

## 2. 实现方式

代码在 `feature/strategyselect/`：常量见 [StgSelectAllTargets.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/strategyselect/StgSelectAllTargets.kt)，功能体见 [StgSelectAllFeature.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/strategyselect/StgSelectAllFeature.kt)，方向与提交包判据等纯逻辑见 [StgSelectAllPlan.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/strategyselect/StgSelectAllPlan.kt)。

### 2.1 挂钩点：`Activity.onResume` + 锚点按钮的 `TextWatcher`

```
Activity.onResume（只对 AIStkStgDetailAct）
  └─ 找到 #tv_stg_add_zxg，确保挂上（幂等）一个 TextWatcher
       └─ 文案变化时 → sync()
            ├─ 文案 == "取消" → injectSelectAll()   （进入多选态）
            └─ 其它           → removeInjected()   （退出多选态）
```

「取消」不是独立按钮：正常态文案是「勾选加入自选股」、多选态是「取消」，是**同一颗 TextView** 换文案，所以「进入多选态」的判定就是观察文案变没变成「取消」。

**为什么不直接 Hook `TextView.setText`**：`setText` 是全 App 的**超热路径**（行情页实时报价每秒都在刷），全局 Hook 会给整个 App 带来可感知的卡顿。只盯这一颗按钮，零全局开销。

### 2.2 注入：从「取消」克隆，保证样式一致

`buildSelectAll()` 逐项复制源按钮的**可见样式**与 **LayoutParams**：`background`（含状态 `constantState`）、`backgroundTintList`、`textColors`、`typeface`、`textSize`、`textScaleX`、`letterSpacing`、`padding`、`compoundDrawables`、`gravity`、`includeFontPadding`、`ellipsize`、`maxLines`、`maxWidth`、`minWidth`、`minHeight`、`foreground`；LayoutParams 按 `LinearLayout.LayoutParams` / `MarginLayoutParams` / `LayoutParams` 三种原类型复制。三处刻意的处理：

| 处理 | 原因 |
|---|---|
| **间距只加在自己身上**：`copy.rightMargin = 16dp`，**不动「取消」任何属性** | 硬验收是「全选按钮的**显示样式与取消按钮完全一致**」；间距是**两个按钮之间**的距离，不是按钮内边距。改「取消」的 padding 会破坏这条验收。 |
| **独立 id**：`View.generateViewId()` | 克隆品若沿用源的 `id`，`findViewById` 会歧义，「防重复注入」的幂等判断也会失效。 |
| **`minWidth` 用 `paint.measureText` 量**，取「全选」「反选」里更宽的那个 | 文案在「全选 ↔ 反选」间切换时宽度不变，按钮不抖、也不会把「取消」挤出屏幕。**不能**取 `src.width` —— 切换途中会拿到旧文案（「勾选加入自选股」5 字）的宽度，把按钮撑过头。 |

### 2.3 全选 / 反选：先确定每行状态 + 只点要改的行 + 分帧 + 点完核对

```
toggleAll()
  ├─ takeShot()：读现场（适配器 / 总行数 / 子集→全量映射 / 每行状态）
  │     ├─ 宿主模型 checkCodes 可信（能读、行代码映射完整、条数与计数相符）→ 按它判
  │     ├─ 宿主计数 == 0        → 全部未选（不建视图；仅首轮）
  │     ├─ checked states 可用  → 读它（O(行数)、不建视图；与「确认添加」同一份表）
  │     │     没有筛选时还要求「表里勾选数 == 宿主计数」，对不上就改用探针
  │     └─ 否则                 → 逐行 adapter.getView(i, null, lv) 现绑探针读 #ct_item
  ├─ wantChecked = StgSelectAllPlan.wantChecked(before)   // 还有没勾上的 → 全选
  ├─ click = StgSelectAllPlan.rowsToClick(before, wantChecked)  // 只点状态与目标不同的行
  ├─ clickAll(click[0..n])                                // 同一帧批量点完
  │     ├─ 运行令牌 / header 数 / 筛选下标快照 / rowEpoch 校验
  │     │     筛选结果或行集合变了 → **重开一轮**；适配器实例换了 → 中止（可能是别的页面）
  │     ├─ pos = header + full[index]                  // 子集下标 → 全量下标（快照）
  │     ├─ 可见行 → 传已绑定的真实子 View；屏外行 → 再造一个探针 View
  │     ├─ lv.setItemChecked(pos, !wantChecked)         // 摆成「点击前」，取反后落到目标
  │     ├─ lv.performItemClick(row, pos, adapter.getItemId(index))
  │     ├─ 这一行失败 → 只计数（本轮第一条记 WARN），下一轮按表核对
  │     └─ 筛选生效时收尾统一 refreshVisibleChecked()
  ├─ 批量收尾：syncHostModel() → refreshVisibleChecked() → updateLabel() → reassertGate()
  ├─ 一轮点完 → 隔 ROUND_SETTLE_MS（120ms）重读 checked states
  │     ├─ 还有行不对 → clickNext() 补点（逐行 TOGGLE_STEP_MS=40ms；最多 MAX_ROUNDS=4 轮点击）
  │     └─ 全部到位   → 收尾刷一次 + 下一帧 pre-draw 再刷一次 + updateLabel()
  └─ 适配器实例在运行中换了 → 中止（不把「全选」施加到另一批股票上）
```

要点：

1. **状态来源按优先级选，绝不读屏上那颗勾选框**：合成点击后宿主不会重画部分可见勾选框（§2.3.2），读屏上那颗会拿到过期状态 —— 这正是「重复全选部分选不中」的根因。顺序是**「宿主 `checkCodes`（能读且行代码映射完整）」→「宿主计数 0 → 全部未选」→「checked states」→「逐行探针」**。模型是底部计数 / 确认添加的来源，能看见「宿主静默忽略了一次合成点击」；模型不可用（字段读不到 / 任一行代码读不到 / 没有筛选时计数 > 0 但模型空表或一只都对不上）时整趟退回后三级。候选字段是 `StgSelectAllTargets.CODE_FIELDS` / `CAM_FIELDS`；若某行恰好取到另一个 6 位值（不是股票代码）会误判该行未勾 —— 出现「模型与表不一致」日志时先核对宿主条目结构，别改判据。
2. **只点状态与目标不同的行**：行点击是**取反**的，对已勾上的行再点一次就是取消。
3. **屏外行必须覆盖**：`index` 从 `0` 走到 `total-1`，屏外行靠 `Adapter.getView(pos, null, lv)` 造视图；不看 `ListView.childCount`（那只是可见行数）。
4. **首轮同帧批量 + 补点轮逐行分帧 + 防重入**：首轮 `clickAll()` 在同一帧内把待点行全部点完（主线程循环期间 ListView 不会布局 / 重绑，行视图始终有效；宿主监听仍逐行执行），屏幕只在最后一帧统一画勾；补点轮仍走 `clickNext()` 的 40ms 分帧（宿主每切一行就刷新列表，跨帧复用子 View 会拿到失效视图）。一轮没跑完时再点按钮直接忽略（两轮会各自按旧快照点同一批行）。回退开关 `StgSelectAllFeature.BATCH_CLICK = false` 即恢复逐行旧路径。
5. **`#ct_item` id 解析失败 → 保守放弃**并记日志。拿不到勾选态时 `checked` 会恒为 `false`，于是对**已选行**也发点击 → 「全选」变成「反选」。宁可不动，也不能点反。
6. **筛选生效时必须传「全量下标」**（§2.3.1），并在每轮开始时把下标表**快照**下来；运行中筛选结果变了就**重开一轮**（重新快照后只补没到位的行），不再中止 —— 中止会把「点了半截」留在界面上。**适配器实例**换了则中止：那可能换到了另一个策略页，继续点会把「全选」施加到别的股票上。
7. **用 `setItemChecked` 预置状态，不直接改 `mCheckStates`**：前者会一并维护 `mCheckedItemCount` / `mCheckedIdStates`（宿主可能用它们渲染「已选 N」）。预置本身失败 → 这一行本轮跳过，下一轮再试。**点击抛异常时不回滚**：框架在调宿主监听器之前就已把表取反写好，回滚会把「宿主可能已更新的模型」与表拆开，下一轮再点反而变成反向操作。
8. **只对普通多选模式生效**：`CHOICE_MODE_SINGLE`（点击会清掉别的行）与 `CHOICE_MODE_MULTIPLE_MODAL`（action mode 活跃时框架不把点击派给宿主监听器）都不注入按钮，日志里写明 `choiceMode`。
9. **点完要核对**：一轮点完隔 120ms 重读 checked states，还有行没到位就补点，最多 4 轮**点击**（`MAX_ROUNDS`，之后还会再核对一次；适配器 / 筛选 / header 变化导致的「重开轮」不点行，不消耗预算）。补点轮以 checked states 为准 —— 宿主计数可能还没跟上，拿它判会把整单选中的行再点一遍（全部取消）。**例外**：没有筛选时若表与宿主计数分叉，说明宿主模型漏了行（真机「宿主吞掉某次点击」），退回探针按模型补。没有 checked states（`CHOICE_MODE_NONE`）时无从核对，只跑一轮。
10. **补点救不了「宿主监听器静默忽略」**：`performItemClick` 在调到监听器**之前**就写了 `mCheckStates`（框架 `AbsListView.java:1231-1232`，且无条件 `handled=true`），下一轮读表只会看到目标态。这类漏选只能靠真机日志与宿主行为去查 —— 本模块从外部观察不到。
11. **运行期间锁住宿主「确认添加」**：首轮虽同帧完成，后面还有补点轮 / 宿主刷新；点早了宿主只快照到**当时已点过的前缀**（72 只实测只进 18 只），屏幕稍后却把 72 行全画上勾，看起来像「全选了却没全加进去」。现在跑第一行前把该按钮置灰禁用（只动 `enabled / clickable / alpha`，**不替换宿主的点击监听**），「已选 N」每变一次重新压一遍闸门，收尾 / 中止 / 关开关 / 页面销毁时还原；id 解析失败时退化为旧行为并记一条日志（§2.3.3）。

#### 2.3.1 与「策略详情页筛选股票」叠加：三套坐标

筛选用一层**过滤代理**挂在 ListView 上：列表里第 N 行对应宿主全量数据的 `keptIndices[N]`。

| 坐标 | 谁在用 |
|---|---|
| **子集下标**（0..保留数-1） | 过滤代理的 `getView` / `getItem` |
| **适配器位置**（header + 子集下标） | `AbsListView.mCheckStates`、屏上 `CheckedTextView`、`mCheckedItemCount`、宿主 `getView` |
| **全量下标**（宿主 data 的 0..N-1） | 筛选包装器转发给宿主监听器的 position |

**现行口径：合成点击与手动点行走同一套坐标（适配器位置）。** 宿主 8.7.0 的 `AIStkStgDetailAct.checkCodes` 字段仍在，但**恒为空**（真机实测：手动点一行、全选 3 只，`checkCodes.size` 都是 0，底部计数正常）—— 选中态只在框架的 checked states 里。于是：

1. **合成点击传适配器位置**（`pos = header + 子集下标`），**不**用 `runWithRemapBypassed` —— 筛选包装器自己会把 position 换成全量下标再转给宿主监听器，与用户手动点行完全一致；
2. `tableRowStates` / `probeRowStates` / 显示刷新 / `SnapSelection` 统一读适配器位置；
3. `readHostModel` 把**空表当不可用**（返回 null），不再把「模型为空」当成「一只没选」—— 否则会和底部计数分叉，把整屏勾抹掉；模型可用时仍然优先按它判，坐标无关。

另外，**可见行必须传真实子 View**：宿主的点击处理会就地把这一行的勾选框打上，用脱离列表的探针 View 只会改到探针，屏幕上看不到勾；屏外行仍然用探针 View。**读状态则相反**：一律用现绑的探针视图，不读屏上那颗（它可能没被重画，见 §2.3.2）。

#### 2.3.2 显示刷新：行根 `activated` / 行根 `isChecked` / `#ct_item.isChecked` 三处一起写

`AbsListView.performItemClick` 每次都会调 `updateOnScreenCheckedViews()`，把**行根视图**的 `activated`（及 `Checkable` 行根的 `isChecked`）按 `mCheckStates[适配器位置]` 重写，**不碰嵌套的 `#ct_item`**。宿主行根是 `com.guosen.commonui.CheckedFrameLayout`，勾选框的 checkMark 只认 `state_checked` —— 只设 `#ct_item.isChecked` 修不回来，这正是「确实全选了，但有些股票不显示勾」的来源。

`refreshVisibleChecked()` **优先按宿主 `checkCodes` 的「这一行是哪只股票」判**，读不到才退回「行 → 适配器位置 → 表」；行根 `Checkable.isChecked`、`#ct_item.isChecked`、`activated` **三处一起写**，只改显示、不写 checked states / 宿主模型。筛选生效时每点一行就刷一次（否则运行中会一部分勾、一部分没勾），收尾再刷一次，并在**下一帧 pre-draw**（布局之后、绘制之前）再补一次 —— 宿主的 `notifyDataSetChanged` → 布局可能比收尾晚，`ListView.setupChild()` 会按适配器位置重写行根 `activated`，把收尾刷的值盖掉。pre-draw 是「这一帧布局已完成」的确定性时点。

**多选态期间常驻「绘制前画勾」**：进入多选态时在 `lvStks` 的 `ViewTreeObserver` 上挂一个**常驻** `OnPreDrawListener`（`StgSelectAllFeature.installVisualSync`），每帧绘制之前按宿主 `checkCodes` 把可见行重画一遍，退出多选态 / 关开关 / 页面销毁时摘掉。判据是「这一行是哪只股票」—— 行代码从过滤代理的 `getItem` 取，代理自己把子集下标映射回全量，两套坐标的问题被彻底绕开；未筛选时行代码读不到则退回 checked states（那份表此时就是准的），不再整行跳过。只在显示与目标不一致时才写，不会每帧 `invalidate`。**仍然只改显示**：不写 checked states、不碰宿主模型（勾只是给别人看的，计数 / 确认添加由宿主自己维护）。

宿主模型不可用 / 不可信（8.7.0 起 `checkCodes` 恒空，或条数与底部计数对不上）时，改为**按框架 checked states 画**（`paintVisibleRowsFromTable`）：手动点行时 `performItemClick` 只刷行根、不刷嵌套的 `#ct_item`，若不按表补，全选时写下的 `isChecked=true` 会一直留在视图上 —— 计数 -1 而对钩去不掉。每次刷之前逐行核对筛选下标表是否还是当前这份，对不上就整趟不刷（绝不拿旧表往新列表上画）。**歧义行不画**：手动点行写适配器位置、模型通道写全量位置，当某行的全量下标恰好也是另一个子集行的下标时，表里那个 key 分不清是谁写的 —— 宁可不画，交给宿主自己渲染。

#### 2.3.3 运行期间锁住「确认添加」

点「全选」后 0.7 秒就点「确认添加」，新建分组只进 18 只（当时首轮是 `40ms × 72 ≈ 2.9s`）—— 宿主在点击那一刻就把选中集合快照走了，后面 54 行虽继续勾上、屏幕最终显示「全选完成」，但已加不进去。首轮改成 `clickAll()` 同帧批量后这个窗口消失，但补点轮 / 宿主刷新仍可能让按钮处于「还没完全落定」的状态，所以闸门保留：

```
toggleAll()
  ├─ takeShot() / 算待点行
  ├─ gateConfirm()            ← 跑第一行之前
  │     ├─ 找到 #tv_bottom_add_zxg，记下原 enabled / clickable / alpha
  │     └─ enabled=false、clickable=false、alpha=GATED_ALPHA(0.45)
  ├─ clickAll(...)            ← 同一帧批量点击；收尾统一 reassertGate()（批量期间计数 watcher 静默）
  ├─ 补点轮 clickNext(...)    ← 仍有缺口时才跑，逐行 40ms
  └─ endRun()（正常收尾 / 补点上限 / 异常 / 关开关 / 退出多选）→ ungateConfirm() 按原值还原
```

- **不替换宿主的点击监听**：`View` 没有 `getOnClickListener()`，换了就还不回去。只动三个可还原属性；`enabled=false` 的可点击 View 会吞掉触摸但**不触发 onClick**。
- **宿主可能自己重设 `enabled`**（例如「已选 > 0 才可点」）：「已选 N」每次变化都调一次 `reassertGate()` 重新压；若宿主把按钮对象整个换了（重建底栏），先还原旧按钮再按新按钮当前状态重新记账。
- 页面销毁路径**只丢账、不还原**（还原只属于收尾 / 关开关 / 热重载）。

#### 2.3.4 与「我的策略聚合」叠加：全选后确认添加什么也没加

**现象**：聚合组详情页（列表上是成员策略的并集）点「全选」屏上全勾、底部计数也对，再点「确认添加」——「我的分组」里**每个分组都显示「已在该组合」**，点进去一只都没有。

**根因**：宿主组提交包的假设是「列表第 N 行 == 它自己全量列表的第 N 只」（`m_stkList`）。聚合只写**适配器数据**（`adapter.data` = 并集），宿主的 `m_stkList` 在聚合态是空的 —— 写它会把宿主搞崩（`AIStkEmptyAdapter.convert` 把其中的项当 `String` 强转，见 [StrategyMergeFeature.kt](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgmerge/StrategyMergeFeature.kt) 里 `MergeClickFill` 附近的注释）。于是「确认添加」构造不出票：`codes` 是 `null` / 空数组，或只剩上一批残值；分组页拿空集去比对 —— 空集是任何集合的子集，于是**每个分组都判成「都已在组里」**。「点一行进详情」那条链已用 `MergeClickFill` 临时把并集灌进 `m_stkList` 再过宿主的 `onItemClick`，但**「确认添加」这条链当时没补**，所以只有它出问题。修法（不碰宿主 `m_stkList`，只纠提交包）：

| 改动 | 位置 | 说明 |
|---|---|---|
| 捕获不再只认筛选页 | `StgSelectAllFeature#captureSelection` | 原来 `fullIndices(...) == null` 就早退；聚合页没有过滤代理，必然命中 → 捕获恒空 → 校正整个失效。现在无筛 / 聚合态也逐行读屏幕勾选（`isItemChecked` + `rowRawCodeAt`），无筛时两套坐标本就一致，普通路径零影响。 |
| 判据放宽 | `StgSelectAllFeature#fixGroupIntentCodes` + `StgSelectAllPlan#needsGroupFix` | 旧判据「`codes` 非空、长度相等、内容不同」才改；聚合态 `codes` 为 `null` / 长度不等，全被挡掉。现在 `null` / 空数组 / 内容不同都覆盖，长度不等**只在聚合页或筛选态**覆盖（§2.3.5）；逐项**归一化**比较（`600000.1` 与 `600000` 同一只），一致时不动宿主原始格式。 |
| `markets` 必写 | 同上 | 宿主原本没带也补上（它与 `codes` 按位置配对，缺一个就错位）；`codes` 逐项一致但 `markets` 长度不符时也照样重写。 |
| `prices` 防越界 | 同上 | 读不到价而宿主那份长度与新的 `codes` 对不上 → `removeExtra`，不让宿主拿旧数组按下标取。 |
| 兜底找回列表 | `StgSelectAllFeature#resolveConfirmList` | 宿主「确认添加」自带触摸监听时（`installConfirmTouchSync` 的契约是不抢）`captureSelection` 一次都没跑过、`confirmList` 为空；改为从最近 resume 的策略详情页按同一控件 id 找回 `lvStks` 现场再捕一次，只在本页还挂在窗口上时生效。 |
| 捕获只认当次 | `StgSelectAllFeature#captureSelection` / `#fixGroupIntentCodes` | 捕获先作废旧值（读不全都整组作废）并记下时刻：超过 `captureFreshMs`（8 s）的捕获不认，避免「点了一下又取消」被上一个页面的票张冠李戴。 |

**日志关键字**（`设置 → 日志`，debug 级）：

```
策略全选：提交包核对（聚合页，宿主 0 只 / 屏幕 32 只）
策略全选：确认添加的提交代码已按屏幕勾选校正（32 只，宿主包里没有 codes）
策略全选：提交代码 <null> → 60****,00****,...
```

看到第三条且 `→` 后面就是屏上那些票，即说明聚合态这条链已经接上；若仍是「选择分组页不是从策略详情进来的」→ 页面归属判据没命中（`watchActivity` 没记上）。

#### 2.3.5 与「筛选股票」叠加：勾选漂移与「长度不等」的第二道闸

**勾选漂移**：过滤代理换行集合时 `mCheckStates` 按位置保留，勾选会落到别的票上，直接点确认就加错票。修法是勾选态**按代码迁移**，见 [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md)「勾选态必须按代码迁移，不能按位置」一节 —— 这条与下面的提交包判据一起才是完整的「聚合 × 筛选」修复。

**判据过头**：§2.3.4 把判据放宽成「长度不等就覆盖」，在**普通详情页**上过了头（宿主读的是它自己那份**全量** model，屏外还勾着票时长度天然比屏幕子集长，一律裁成屏幕勾选就把屏外那批票悄悄丢掉；且下一轮筛选重算会把它刷回去，表现为「一闪而过的不准」）。三处一起修：

| 改动 | 位置 | 说明 |
|---|---|---|
| 判据分档 | `StgSelectAllPlan#needsGroupFix(hostCodes, selected, screenWins)` | 长度不等的第二道闸：只有 `screenWins`（**聚合生效** 或 **筛选生效**）才按屏幕覆盖，普通全量页保持宿主那份；`hostCodes` 为 `null` / 空数组仍一律补上（那是「宿主一只都没构造出来」）。 |
| 页面判定 | `StgSelectAllFeature#fixGroupIntentCodes` | `mergedPage = StrategyMergeBridge.isFor(...)`、`filteredPage = isFilterProxy(unwrapHeaderAdapter(lv.adapter))`，两条都不成立才算「全量页」；坐标权威在**捕获那一刻**定死（`Selection` 带 `screenWins` / `pageKind`），改提交包时页面可能已摘挂，不再回问列表。 |
| 诊断日志 | 同上 | 判定前打一行 `策略全选：提交包核对（聚合页 / 筛选态 / 聚合页+筛选态 / 全量页，宿主 N 只 / 屏幕 M 只）`。 |

#### 2.3.6 批量点行遇上行集合被换：`rowEpoch` 重开一轮

`clickOneRow` 原本只有一道行集合校验：`fullIndices` 存在时比对本轮快照的下标表；**没有过滤代理时（`run.full == null`）零校验**。而聚合页点一行会把 `adapter.data` 整体换一遍（并集重新写回），位置全变 —— 批量循环接着往下点就会点到别的股票上。修法：`ToggleRun` 记下开跑那一刻的 `StgRowSelection.rowEpoch()`，每行前比一次，不等就重开一轮（**已点过的行按代码跳过，不会重点**）。

```kotlin
} else if (StgRowSelection.rowEpoch() != run.rowEpoch) {
    GLog.i("策略全选：行集合在运行中变了（rowEpoch X → Y），重新快照（已完成 c/s）")
    scheduleNextRound(env, run, 0)
    return RowOutcome.Restart
}
```

版本号由**换行集合的那一方** bump：筛选在 `applyFilter0` 装完代理之后，聚合在 `reapplyIfNeeded` 写完并集并 notify 之后。谁写谁发信号的口径正本见 [FEATURE-ROW-SELECTION.md](FEATURE-ROW-SELECTION.md) §四。

#### 2.3.7 手机上随机提示「已在该组合」：三处静默放弃

全选后点「确认添加」，分组页对每个分组都显示「已在该组合」，实际一只都没加进去；**同一个包在模拟器上正常、手机上随机失败**，全选里含自选股时更容易撞上。根因是这条链上有**三处「读不到就走开」的静默放弃**，任何一处命中都让提交包原样进分组页：聚合页 / 筛选态下宿主自己构造的 submission 是空集或错批，`ChooseGroupAct` 把「空集 ⊆ 每个分组」判成「都已经在组里」。

| # | 放弃点 | 触发条件 | 为什么手机更容易命中 |
| --- | --- | --- | --- |
| ① | `resolveConfirmList()` 为 null 就 `return` | 点完「确认添加」，宿主先 `finish` 详情页再起分组页 → 列表已摘挂 | 手机主线程更忙，`finish` 与 `onCreate` 的先后和模拟器不同 |
| ② | 捕获超过 `captureFreshMs`（8 s）就 `return` | 「确认添加」自带触摸监听时我们不抢 → 捕获来自全选途中的旧时点 | 手机点完到分组页起来的间隔更长 |
| ③ | `captureSelection` 某一行 `market` 为空就整组作废 | 宿主 `StockInfo.codeAndMarket` 是构造后才填的，**自选票尤其**常见「只有代码、没有市场号」的中间态 | 行情 / 自选表加载时序不同，中间态窗口不同 |

第三处正是「含自选股更容易撞上」的原因：我们只需要代码，市场号推得出来 —— 却因为一行没有市场号把 500 多只票全部放弃。修法（只删闸门、不加规则）：

```kotlin
// ① 页面摘挂也照用「按下确认添加」那一刻的捕获；只有非触摸捕获才要求列表还在
val fresh = cached != null && cachedAge in 0..captureFreshMs && (confirmFromTouch || lv != null)
val s = when {
    fresh -> cached
    lv != null -> { captureSelection(lv); confirmSelection }   // ② 现场重捕兜底
    else -> null
}
if (s == null) {
    GLog.w("策略全选：提交代码保持原样（捕获=…，本页列表=已摘挂/在）")
    return
}

// ③ 市场号缺失按代码前缀推，推不出来才算读不全（与筛选 / 勾选态通道同一口径）
val parts = StgSelectAllPlan.splitCodeMarket(raw) ?: return
```

两个配套改动：**去掉「用过即焚」**（同一份提交包若因 Activity 重建再进一次 `onCreate`，旧代码第二次已无捕获可用；改写是幂等的，防张冠李戴改由「8 s 过期 + 触摸来源」两条判）；**市场号推导口径唯一**（`StgSelectAllPlan.splitCodeMarket` → `StockTypes.marketOf`，不在第三处再推一套）。

回查只看 `策略全选：` 关键字，三种日志各对应一条出口：

| 看到什么 | 说明 |
| --- | --- |
| 「提交代码保持原样（捕获过期 N ms，本页列表已摘挂）」 | ①② 都没兜住 —— 把这条日志和 N 发回来 |
| 「第 N 行读不出代码或市场号（…）」 | ③ 的推导还不够，日志里有具体代码，按代码补前缀规则 |
| 「提交包核对（…，宿主 N 只 / 屏幕 N 只）」后没有「已校正」 | 宿主本来就对，不需要改 |

### 2.4 文案随状态切换（全选 / 反选）

| 状态 | 文案 | 点击后 |
|---|---|---|
| 未全选 | **全选** | 勾选该页全部股票 |
| 已全选 | **反选** | **取消全部选中** |

重算文案的三个时机：① **进入多选态**（`TextWatcher` 触发 `sync → injectSelectAll → updateLabel`）；② **底部「已选 N」变化** —— 给 `#tv_bottom_zxg_num` 挂 `TextWatcher`（手动点行、宿主自己改选中都会更新它）；③ **点了全选/反选按钮自己**（`toggleAll` 跑完最后一帧时刷新）。判据与动作共用同一份状态（`updateLabel` 也读 checked states / 模型），否则筛选后会出现「文案写着全选、点下去却在取消」。

> ⚠️ **不要再包裹 ListView 的 `OnItemClickListener`**：它和筛选层的下标回映射会互相重包，叠成双重映射；而且宿主的选中模型看的是 checked states，不是转发出去的 position 参数。

### 2.5 批量点击的耗时：逐行数据缓存

逐行点击时代一次全选 285 行要 **1763 ms**，屏幕明显「一格一格」地勾上去。分段打点（`tProbe / tClick / tSync / nProbe`）摊开后只有一处：**「校正模型」1234 ms**，其余加起来不到 60 ms。根因不在点击，而在点击**之后**：`clickRow` 每点一行就调一次 `syncHostModel`，而 `syncHostModel` 每次都把**整张表**重新反射读一遍（每行的代码 / 原始代码 / 价格 / 连板数），复杂度 `O(N)`；285 行就是 285 × O(285)。

改法：批量期间建一张行缓存 `RowCodes(adapter, total)`，四个取值各开一个数组；`rowCode / rowRawCodeAt / rowPriceAt / rowTwinAt` 先查表，**非批量态原样回退老实现**（手动单击的行为一个字节都没变）。缓存值同样过 `StgSelectAllPlan.isCodeLike` 校验并 `noteRowCodeMiss`，「某行读不到代码」的容错路径照样会被走到。

| 指标（285 行筛选态 / 并集 630） | 优化前 | 优化后 |
|---|---|---|
| 一次全选总耗时 | 1763 ms | **429 ms** |
| 其中「校正模型」 | 1234 ms | **22 ms** |
| 反选总耗时 | 同量级 | **346 ms**（校正模型 10 ms） |
| `gfxinfo` 99th percentile | 1750 ms | **300 ms** |
| Janky frames | 0% | 0%（legacy 5.36%） |

> 剩下的 ~370 ms 落在 `setItemChecked`、屏外探针 View 预置与 `fullIndexAt` 这些没打点的地方；再往下压就要动分帧策略，收益不如风险大，先停在这里。

---

## 3. 模块侧改动清单

| 文件 | 作用 |
|---|---|
| `feature/strategyselect/StgSelectAllTargets.kt` | 控件契约（全部做成候选数组，App 升级只改常量） |
| `feature/strategyselect/StgSelectAllFeature.kt` | 功能本体 |
| `feature/strategyselect/StgSelectAllPlan.kt` | 方向判定、待点行、提交包判据（纯逻辑，可单测） |
| `feature/stgfilter/StgFilterBridge.kt` | 筛选生效时的「子集下标 → 全量下标」通道 |
| `feature/stgmerge/StrategyMergeBridge.kt` | 聚合页归属判定（`isFor`），决定 `screenWins` |
| `feature/stgrow/StgRowSelection.kt` | `rowEpoch` 行集合版本号 |
| `feature/FeatureManager.kt` | `features` 列表登记 |
| `core/ConfigKeys.kt` | `STG_SELECT_ALL` 键 + `DEFAULT_*=false` + 进 `ALL_KEYS` / `FEATURE_KEYS` / `DEFAULTS` |
| `core/ConfigSnapshot.kt` | `stgSelectAll` 字段 + 进 `fingerprint` + `DEFAULT` |
| `core/ConfigSource.kt` | `read()` 补一行 |
| `ui/FeatureCatalog.kt` | 展示元数据（挂在「策略页」分区，无二级页 —— 没有可调参数） |
| `res/values/strings.xml` + `values-en/strings.xml` | 功能标题 / 摘要 / 开关文案（中英同步） |
| `app/DebugConfigProvider.kt` | `stg_select_all` 列，供脚本化开关 |

> **开关是功能总开关，出厂默认 `false`** —— 见 `docs/EXTENDING.md` 第 4 步的硬约定。要生效需在模块设置「功能」Tab 手动打开，且**打开后要重启目标 App**（配置变化只走 `onConfigChanged`，不会补装钩子）。

---

## 4. 历史回归台账

九轮真机回归压成一张表：症状、根因与判据保留，逐日过程见 [CHANGELOG.md](../CHANGELOG.md)。「现行处置」列指向正文对应小节。

| 日期 | 症状 | 根因 | 现行处置 |
|---|---|---|---|
| 2026-10-20 | 筛选生效时全选，确认页收到的是全量前 N 只 | 合成点击传子集下标，宿主按全量位置取值 | 合成点击与手动点行统一按适配器位置（§2.3.1） |
| 2026-10-21 | 做过一次全选后，再全选部分行选不中 | 读屏上勾选框（宿主没重画）+ 取反写 checked states 撞残值 | 状态来源四级优先级 + 只点不同行 + `setItemChecked` 预置（§2.3 要点 1 / 2 / 7） |
| 2026-10-21 | 手动点行勾中下一行 | 手写 wrapper 把取反结果搬到全量位置 | 手动点行不搬表，只把 `header + 全量下标` 转给宿主；id 改用代理 `getItemId(index)`（§2.3.1） |
| 2026-10-22 | 计数与确认页都对，但部分可见行不显示勾 | `updateOnScreenCheckedViews` 按适配器位置重写行根，与全量位置的表错位 | 三处一起写 + 每点一行刷 + 收尾 + 下一帧 pre-draw（§2.3.2） |
| 2026-10-22 | 偶发漏选 | 单轮点完就收工，筛选变化 / 探针造不出 / 宿主改表都留缺口 | 120ms 重读 + 补点，最多 4 轮点击；筛选下标变了重开一轮，适配器实例换了才中止（§2.3 要点 6 / 9） |
| 2026-10-23 | 全选后个别对钩不显示，再点一下计数 +1 | 只写 `#ct_item` 没写行根 `Checkable`；验证看的是自己刚写的表；16ms 太快 | 优先读宿主 `checkCodes` 判方向 + 行视图也摆成「点击前」+ 40ms / 120ms（§2.3 要点 1、§2.3.2） |
| 2026-10-27 | 多选态中途宿主刷新后勾又被盖回去 | 每次 `notifyDataSetChanged` 都重绑行，收尾那次刷白做 | 常驻 `OnPreDrawListener` 每帧按 `checkCodes` 重画（§2.3.2） |
| 2026-10-28 | 全选后 0.7s 点确认添加，72 只只进 18 只 | 宿主点击那刻快照选中集合，只拿到已点前缀 | 运行期间锁「确认添加」，只动 `enabled / clickable / alpha`（§2.3.3） |
| 2026-10-28 | 「确认添加」返回后全选摁不动 | 宿主清了底部计数却没清 `checkCodes`，残值把整轮卡死 | 计数 0 但模型非空 → 清残值、按「一只没选」处理（§2.3 要点 1） |
| 2026-11-01 | 首轮同步 `clickNext` 抛异常会让锁卡死、「确认添加」从此置灰 | 首次调用落在「running 已置位、finally 只兜 `!started`」的窗口 | `runCatching + endRun()` 纯加固，不动正常路径（§2.3 要点 4） |
| 2026-11-04 | 全选后手动取消一行，√ 去不掉 | `performItemClick` 只刷行根、不碰嵌套 `#ct_item`；`checkCodes` 恒空使旧画勾逻辑早退 | 批量点击 + `paintVisibleRowsFromTable` 按表画三处（§2.3.2） |
| 2026-11-04 | 逐行 40ms 勾得太慢 | 首轮同步逐行点击 | `clickAll` 同帧批量，`BATCH_CLICK` 作回退开关（§2.3 要点 4） |
| 2026-11-12 | 285 行全选 1763ms，「一格一格」地勾 | `syncHostModel` 每行重读整表，`O(N²)` | `RowCodes` 行缓存，非批量态回退老实现（§2.5） |
| 2026-11-13 | 聚合组全选后确认添加，每个分组都「已在该组合」 | 聚合只写 `adapter.data`，宿主 `m_stkList` 空 → 提交包空集 | 捕获放开无筛 / 聚合态 + 判据按屏幕覆盖 + `markets` / `prices` 一并校正（§2.3.4） |
| 2026-11-13 | 普通详情页筛完确认添加少几只 | 判据放宽到「长度不等就覆盖」，把屏外票裁掉 | `needsGroupFix` 加 `screenWins` 第二道闸（§2.3.5） |
| 2026-11-13 | 聚合页点一行后批量落到别的股票上 | 无过滤代理时 `clickOneRow` 零校验 | 比对 `rowEpoch`，不等就重开一轮（§2.3.6） |
| 2026-11-13 | 手机上随机「已在该组合」，模拟器正常 | 捕获链上三处静默放弃 | 捕获按时刻 + 现场重捕 + 市场号按前缀推（§2.3.7） |

**仍未闭环的边界**：① 宿主监听器**静默忽略**合成点击时，补点与核对都看不出来（`performItemClick` 先写表、无条件 `handled=true`），只能靠「计数满了但确认页少几只」的现象去抓宿主日志；② 单次提交超过宿主服务端分组容量时会卡在「选择分组」页无提示（527 只实测，见 [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md) §八的容量结论），需分批添加。

---

## 5. 验收标准

1. 多选态下，「全选 / 反选」出现在「取消」**左侧**，**尺寸、配色、字号、内边距与「取消」一致**。
2. 点「全选」→ **该页全部股票**被勾选（含屏外行），底部「已选 N」== 列表总行数，按钮文案变「反选」。
3. 再点「反选」→ 全部取消，底部计数归 0，按钮文案回「全选」。
4. 手工点任意行后，按钮文案能跟随「是否已全选」实时切换。
5. 反复进出多选态不会出现第二个按钮。
6. 关掉 `stg_select_all` → 按钮消失（即时生效）；重开并重进多选态 → 按钮回来。
7. 目标 App 无新增崩溃；release 包 `Lio/github/libxposed/api` 定义类 0。
8. **筛选生效时**：全选勾中的行 = 页面可见的股票；确认添加收到的代码与之一致（不能是全量前 N 只）；手动点行也选中屏幕上那一只。
9. **重复点**：全选 → 反选 → 全选、全选 → 手动改几行 → 全选 —— 每次结束都应达到目标状态（全勾或全不勾），**不能有行反向**。
10. **屏幕显示与表一致**：全选后**屏上可见的每一行**勾选框都显示为勾上（包括筛选生效时），滚到列表任意位置再滚回来也保持一致；反选后全部显示为空。
11. **筛选 + 手动勾选 + 全选**：筛选生效时先手动勾 1~2 行，再点「全选」—— 已手动勾上的行**不能被点掉**，底部计数 = 筛选后行数，确认页与屏幕一致。
12. **补点轮不误伤**：全选后立刻再点一次（按钮此时是「反选」）→ 全部取消；再点「全选」→ 全部勾上。每轮结束都达到目标，不能出现「点两次才到位」的中间态。
13. **对钩与宿主模型一致**：全选后屏上**每一个可见行**都显示勾；再点任意一行应是「取消」、底部计数 **-1** —— 不能出现「点一下反而 +1」。反选后同理，计数与对钩一起归零。
14. **运行中确认添加被锁**：点「全选」后立刻点「确认添加」—— 按钮为置灰禁用状态、**不弹「选择分组」**；等按钮文案变「反选」/ 计数稳定后，按钮恢复原样且能正常添加，确认页收到的数量 == 「已选 N」。
15. **一次性全选**：点「全选」后**一帧内**全部对钩出现，底部计数直接到 N；不能看到从第一行往下逐个勾的动画（可用录屏 / 逐帧截图核对）。
16. **手动取消后对钩消失**：全选后手动点掉任意一行，对钩**立即消失**、底部计数 -1，按钮文案仍为「全选」。
17. **取消后再全选方向正确**：接第 16 条再点「全选」，只补回被取消的那一只，**不能变成全部取消**。
18. **批量回归**：筛选生效时重复第 15–17 条；确认添加的分组内容与屏幕逐只一致；批量运行中退出多选态 / 关开关 → 不再继续点击，「确认添加」恢复原状。
19. **聚合组全选后确认添加**：聚合组详情全选 N 只 → 确认添加 → 空分组里**恰好**是屏上那 N 只（不带成员外的票、不缺）；此时分组页**不应**再显示「已在该组合」。对同一批再添加一次，显示「已在该组合」才是正确语义。聚合组上手动逐只勾选后确认添加同样要一致。
20. **聚合 × 筛选叠加**：聚合组详情里先筛到 10 只以内，勾第 2、4 行，再改筛选条件让结果换一批 —— 勾选必须跟着**同一只股票**走（被滤掉则相应消失），不能出现在别的行上；接着全选 → 确认添加，分组内容与屏上逐只一致。日志里应有 `策略全选：提交包核对（聚合页+筛选态，宿主 N 只 / 屏幕 M 只）`，有勾选迁移时应同时有 `筛选股票：勾选态按代码迁移 K 行`。普通详情页（无筛无聚合）宿主包比屏幕长时**不得**被裁（`提交包核对（全量页…）` 之后不应打「已按屏幕勾选校正」）。

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
bash tools/ui.sh list | grep -E "已选|只股票"   # 再确认底部计数控件
```

把新的 id / 文案写回 `StgSelectAllTargets.kt` 的**候选数组**（`CANCEL_BUTTON_ID_CANDIDATES` / `LIST_ID_CANDIDATES` / `CHECKBOX_ID_CANDIDATES` / `COUNT_ID_CANDIDATES` / `CONFIRM_BUTTON_ID_CANDIDATES` / `CODE_FIELDS` / `CAM_FIELDS`）即可，不用改实现逻辑。

> 环境坑（无线调试掉线、mDNS 抢设备、`adb pull` 路径被改写、`/data/adb/lspd/config/modules_config.db` 是陈旧快照）见 [`../tools/README.md`](../tools/README.md) 与 [TROUBLESHOOTING.md §9「设备相关（自动化脚本）」](TROUBLESHOOTING.md#9-设备相关自动化脚本)。
