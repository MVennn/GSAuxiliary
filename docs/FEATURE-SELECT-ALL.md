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

### 2.3 全选 / 反选：先确定每行状态 + 只点要改的行 + 分帧

```
toggleAll()
  ├─ realAdapter = unwrapHeaderAdapter(lv.adapter)   // 拆掉 HeaderViewListAdapter
  ├─ readRowStates()：确定每一行**点击前**的状态（优先级见下）
  │     ├─ 宿主计数 == 0        → 全部未选（不建视图）
  │     ├─ checked states 可用  → 读它（O(行数)、不建视图；与「确认添加」同一份表）
  │     │     没有筛选时还要求「表里勾选数 == 宿主计数」，对不上就改用探针
  │     └─ 否则                 → 逐行 adapter.getView(i, null, lv) 现绑探针读 #ct_item
  ├─ wantChecked = StgSelectAllPlan.wantChecked(before)   // 还有没勾上的 → 全选
  ├─ click = StgSelectAllPlan.rowsToClick(before, wantChecked)
  │     // 只点「状态与目标不同」的行 —— 行点击是取反的
  └─ stepToggle(click[0..n])，每行之间 postDelayed 16ms
        ├─ 运行令牌 / 适配器身份 / 下标快照校验，变了就中止
        ├─ pos = header + full[index]                  // 子集下标 → 全量下标（快照）
        ├─ 可见行 → 传已绑定的真实子 View；屏外行 → 再造一个探针 View
        ├─ lv.setItemChecked(pos, !wantChecked)
        │     // 摆成「点击前」，取反后才一定落到目标；它还会维护 checkedItemCount
        ├─ StgFilterBridge.runWithRemapBypassed {
        │      lv.performItemClick(row, pos, adapter.getItemId(index))
        │  }
        └─ 全部走完 → repaintVisible() 把屏上可见勾选框刷成目标态 + updateLabel()
```

要点：

1. **状态来源按优先级选，绝不读屏上那颗勾选框**（2026-10-21）：合成点击后宿主不会重画
   部分可见勾选框（§4.1），读屏上那颗会拿到过期状态 —— 这正是「重复全选部分选不中」的根因。
   顺序是「宿主计数 0 → 全部未选」→「checked states」→「逐行探针」。
   中间那档最省（不建视图），最后一档只在表不可用或与计数矛盾时才走。
2. **只点状态与目标不同的行**：行点击是**取反**的，对已勾上的行再点一次就是取消。
3. **屏外行必须覆盖**：`index` 从 `0` 走到 `total-1`，屏外行靠 `Adapter.getView(pos, null, lv)` 造视图；
   不看 `ListView.childCount`（那只是可见行数）。
4. **一行一帧（`postDelayed` 16ms）+ 防重入**：宿主每切换一行就刷新列表，同帧复用子 View
   会拿到失效视图；一轮没跑完时再点按钮直接忽略（两轮会各自按旧快照点同一批行）。
5. **`#ct_item` id 解析失败 → 保守放弃**并记日志。若拿不到勾选态，`checked` 会恒为 `false`，
   于是对**已选行**也发点击 → 「全选」会变成「反选」。宁可不动，也不能点反。
6. **筛选生效时必须传「全量下标」**（见 §2.3.1），并在启动时把下标表**快照**下来；
   运行中宿主换了适配器或筛选结果变了就中止（否则后半程会点到别的股票）。
7. **用 `setItemChecked` 预置状态，不直接改 `mCheckStates`**：前者会一并维护
   `mCheckedItemCount` / `mCheckedIdStates`（宿主可能用它们渲染「已选 N」）；
   点击抛异常时把旧值写回，不留「与目标相反」的残值。
8. **只对普通多选模式生效**：`CHOICE_MODE_SINGLE`（点击会清掉别的行）与
   `CHOICE_MODE_MULTIPLE_MODAL`（action mode 活跃时框架不把点击派给宿主监听器）
   都不注入按钮，日志里写明 `choiceMode`。

### 2.3.1 与「策略详情页筛选股票」叠加（2026-10-20 真机修复）

筛选用一层**过滤代理**挂在 ListView 上：列表里第 N 行对应宿主全量数据的 `keptIndices[N]`。
这里有两套坐标，**不能混用**：

| 坐标 | 谁在用 |
|---|---|
| **子集下标**（0..保留数-1） | 过滤代理的 `getView` / `getItem`；`performItemClick` 的 `position` 初值 |
| **全量下标**（宿主 `data` 的 0..N-1） | 宿主的多选模型（`checkCodes` 等）与 `AbsListView` 的 checked states |

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
2. **手动点行由筛选 wrapper 改写 checked states**：手动点击的 position 由框架按子集坐标给出，
   wrapper 在转发前把这一格还原成「取反前」的值，再把**被点的这一行**（全量下标）按它自己的
   旧值取反。**不能**直接把子集位置上的取反结果搬过去 —— 筛选时两套坐标会重叠，
   子集位置 N 的格子属于「全量下标 N」的**另一只**股票，搬过去会顺手改掉别人的选中
   （2026-10-21 修正，见 §4.2）。全量那一格同样放在放行标记里；两格任一步失败都回滚。

另外，**可见行必须传真实子 View**：宿主的点击处理会就地把这一行的勾选框打上；
用脱离列表的探针 View 只会改到探针，屏幕上看不到勾。屏外行仍然用探针 View。
**读状态则相反**：一律用现绑的探针视图，不读屏上那颗（它可能没被重画，见 §4.1）。

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

> ⚠️ **观感局限（2026-10-21 已补显示刷新，待真机验证）**：合成点击后宿主不会重画**部分**可见勾选框 ——
> 50e / 19 只筛选结果实测：底部「已选 19 只股票」、确认页 19 只全对，
> 但屏幕 9 个可见勾选框只画出 6 个；**手动点行**与**反选**的勾选显示正常。
> 根因：`performItemClick` 内部的 `updateOnScreenCheckedViews()` 只处理行根视图（`Checkable`），
> 而勾选框 `#ct_item` 在行内部。
> `invalidateViews()` 与「预置视图位置」两种旧补法无效（后者还会让确认页多出股票）。
> 现在改为**全部点完后只刷新显示**：`repaintVisible()` 把屏上可见的 `#ct_item`
> 直接刷成目标状态 —— 不写 checked states、不碰宿主模型，所以确认页不受影响。
> 这条显示刷新只在**所有行点击都成功**时执行（失败就保持原样，不画一个假的勾）。

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
  避免读到上一页留在全局桥里的旧表）；中途适配器换了或下标变了就中止。
- **只对普通多选模式生效**：单选 / 多选模态下不注入按钮（`toggleAll` 里也有同一道守卫）。
- **手动点行的 wrapper 不再搬取反结果**：先还原子集位置那一格（它属于全量下标相同的
  另一只股票），再把被点的行按自己的旧值取反（见 §2.3.1 第 2 条）。
- **`performItemClick` 的 id 改用代理的 `getItemId(index)`**：`lv.getItemIdAtPosition(全量位置)`
  在全量下标小于子集行数时会被代理再映射一次，拿到别的股票的 id。
- **只在过滤代理确实挂在列表上时才发布下标表**：否则「全选」会把子集下标当全量下标用。
- **文案与动作共用同一判据**（`updateLabel` 也读 checked states）：
  否则筛选后会出现「文案写着全选、点下去却在取消」。

单测：`StgSelectAllPlanTest` 10 条（方向判定 / 待点行 / 状态来源优先级）。

> ⚠️ **本节的修复尚未真机验证**（2026-10-21 提交时设备未接）。
> 装机后按 §5 第 9 条复验；重点看「连续点」与「筛选 + 全选」两种组合。

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
