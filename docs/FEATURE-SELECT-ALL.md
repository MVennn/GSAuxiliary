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

### 2.3 全选 / 反选：逐行触发 + 覆盖屏外行 + 分帧

```
toggleAll()
  ├─ realAdapter = unwrapHeaderAdapter(lv.adapter)   // 拆掉 HeaderViewListAdapter
  ├─ 读底部 #tv_bottom_zxg_num 得到 cur（宿主维护，天然含屏外行）
  ├─ allChecked = (cur == realAdapter.count)
  ├─ wantChecked = !allChecked
  └─ stepToggle(index = 0 … total-1)，每行之间 postDelayed 16ms
        ├─ fullIdx = StgFilterBridge.fullIndexAt(index)   // 子集下标 → 全量下标
        ├─ 可见行 → 用已绑定的真实子 View
        ├─ 屏外行 → adapter.getView(index, null, lv) 现造探针 View
        ├─ 该行状态 != 目标状态 →
        │    StgFilterBridge.runWithRemapBypassed {
        │        lv.performItemClick(row, header + fullIdx, id)
        │    }
        └─ 全部走完 → lv.invalidateViews() 重画可见行 + updateLabel()
```

要点：

1. **「是否已全选」以底部的宿主计数为准**，不是数可见行 —— 它含屏外行，且不用建 View。
2. **屏外行必须覆盖**：`index` 从 `0` 走到 `total-1`，屏外行靠 `Adapter.getView(pos, null, lv)` 造视图；
   不看 `ListView.childCount`（那只是可见行数）。
3. **一行一帧（`postDelayed` 16ms）**：宿主每切换一行就刷新列表，同帧复用子 View 会拿到失效视图。
4. **计数明确时跳过逐行探状态**（`cur == 0` 或 `cur == total`）：直接全量点击，省掉屏外行的 `getView` 开销。
5. **`#ct_item` id 解析失败 → 保守放弃**并记日志。若拿不到勾选态，`checked` 会恒为 `false`，
   于是对**已选行**也发点击 → 「全选」会变成「反选」。宁可不动，也不能点反。
6. **筛选生效时必须传「全量下标」**（见 §2.3.1）。

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
   position 都是全量坐标。
2. **手动点行由筛选 wrapper 改写 checked states**：手动点击的 position 由框架按子集坐标给出，
   wrapper 在转发前把 `mCheckStates[子集下标]` 清掉、写入 `mCheckStates[全量下标]`，
   宿主随后读到的就是正确股票。

另外，**可见行必须传真实子 View**：宿主的点击处理会就地把这一行的勾选框打上；
用脱离列表的探针 View 只会改到探针，屏幕上看不到勾。屏外行仍然用探针 View。

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

> ⚠️ **已知观感局限（未修）**：合成点击后**部分可见勾选框可能不重画** ——
> 50e / 19 只筛选结果实测：底部「已选 19 只股票」、确认页 19 只全对，
> 但屏幕 9 个可见勾选框只画出 6 个；**手动点行**与**反选**的勾选显示正常。
> 试过 `invalidateViews()` 与「预置视图位置」两种补法：前者无效；
> 后者会让确认页把「视图位置」也当选中行（18 只被并成 21 只）。
> 宿主的多选渲染与 `mCheckStates` 的读写时机不透明，故保留「计数 + 确认页正确」
> 这一层，不做不可靠的视图 hack。

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
