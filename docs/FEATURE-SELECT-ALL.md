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

代码在 `feature/strategyselect/`：常量在 [StgSelectAllTargets.kt](../app/src/main/java/gsauxiliary/m/venn/feature/strategyselect/StgSelectAllTargets.kt)，
实现在 [StgSelectAllFeature.kt](../app/src/main/java/gsauxiliary/m/venn/feature/strategyselect/StgSelectAllFeature.kt)。

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
        ├─ 可见行 → 用已绑定的真实子 View
        └─ 屏外行 → adapter.getView(index, null, lv) 现造探针 View
        └─ 该行状态 != 目标状态 → lv.performItemClick(row, pos, id)
```

要点：

1. **「是否已全选」以底部的宿主计数为准**，不是数可见行 —— 它含屏外行，且不用建 View。
2. **屏外行必须覆盖**：`index` 从 `0` 走到 `total-1`，屏外行靠 `Adapter.getView(pos, null, lv)` 造视图；
   不看 `ListView.childCount`（那只是可见行数）。
3. **一行一帧（`postDelayed` 16ms）**：宿主每切换一行就刷新列表，同帧复用子 View 会拿到失效视图。
4. **计数明确时跳过逐行探状态**（`cur == 0` 或 `cur == total`）：直接全量点击，省掉屏外行的 `getView` 开销。
5. **`#ct_item` id 解析失败 → 保守放弃**并记日志。若拿不到勾选态，`checked` 会恒为 `false`，
   于是对**已选行**也发点击 → 「全选」会变成「反选」。宁可不动，也不能点反。

### 2.4 文案随状态切换（全选 / 反选）

| 状态 | 文案 | 点击后 |
|---|---|---|
| 未全选 | **全选** | 勾选该页全部股票 |
| 已全选 | **反选** | **取消全部选中** |

重算文案的三个时机：

1. **进入多选态**（`TextWatcher` 触发 `sync → injectSelectAll → updateLabel`）；
2. **用户点了任意一行**：包裹一次宿主 ListView 的 `OnItemClickListener`（读隐藏字段
   `AdapterView.mOnItemClickListener` 拿原监听器，调完原逻辑再刷新文案）；
3. **点了全选/反选按钮自己**（`stepToggle` 跑完最后一帧时刷新）。

---

## 3. 模块侧改动清单

| 文件 | 作用 |
|---|---|
| `feature/strategyselect/StgSelectAllTargets.kt` | 上面那张控件契约表（全部做成候选数组，App 升级只改常量） |
| `feature/strategyselect/StgSelectAllFeature.kt` | 功能本体 |
| `feature/FeatureManager.kt` | `features` 列表登记 |
| `core/ConfigKeys.kt` | `STG_SELECT_ALL` 键 + `DEFAULT_*=false` + 进 `ALL_KEYS` / `FEATURE_KEYS` / `DEFAULTS` |
| `core/ConfigSnapshot.kt` | `stgSelectAll` 字段 + 进 `fingerprint` + `DEFAULT` |
| `core/ConfigSource.kt` | `read()` 补一行 |
| `ui/FeatureCatalog.kt` | 展示元数据（挂在「策略页增强」分区，无二级页 —— 没有可调参数） |
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

---

## 5. 验收标准

1. 多选态下，「全选 / 反选」出现在「取消」**左侧**，**尺寸、配色、字号、内边距与「取消」一致**。
2. 点「全选」→ **该页全部股票**被勾选（含屏外行），底部「已选 N」== 列表总行数，按钮文案变「反选」。
3. 再点「反选」→ 全部取消，底部计数归 0，按钮文案回「全选」。
4. 手工点任意行后，按钮文案能跟随「是否已全选」实时切换。
5. 反复进出多选态不会出现第二个按钮。
6. 关掉 `stg_select_all` → 按钮消失（即时生效）；重开并重进多选态 → 按钮回来。
7. 目标 App 无新增崩溃；release 包 `Lio/github/libxposed/api` 定义类 0。

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
> [`.workbuddy/memory/2026-10-15.md`](../.workbuddy/memory/2026-10-15.md)。
