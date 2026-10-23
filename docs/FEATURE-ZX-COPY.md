# 自选页「编辑 → 复制」按钮

> 一句话：自选页右上角搜索图标左边的「编辑」（铅笔）按钮换成复制按钮，
> 点一下把**当前显示分组**里的全部股票按「名称 代码」一行一只复制到剪贴板。

> 本文的控件 id、视图结构与验证结论**均来自 2026-10-23 真机实测**
> （魅族 20 / Android 16 / 金太阳 8.6.0 vc271），不是推断。

---

## 0. 结论先行

| 问题 | 答案 |
|---|---|
| 按钮在哪 | 自选页顶栏 `#common_top` 内、`#iv_top_search` 左边：`#iv_top_manager`（原铅笔「编辑」） |
| 为什么不能全局按 id 找 | `#iv_top_manager` 在整棵树里有 **3 个**实例：自选顶栏铅笔、行情顶栏同名图标（`GONE`）、分组行右侧「≡」。必须先用自选页独有的 `#indicator_zx` 认页，再上溯 `#common_top` |
| 复制什么 | 当前分组页 `InterceptListView` 适配器的全部条目：`stkName` + `m_stkCode`（/ `codeAndMarket`） |
| 为什么读适配器 | `ZXWorkflow.getStocksByGroupId()` 只回 `StockData`（**没有名称**），且「持仓股」不是真实分组；当前页适配器所见即所得，三种 tab 统一 |
| 输出格式 | `名称 代码` 一行一只，例 `金山办公 688111`；名称缺失时只写代码；同一「代码.市场」只留第一次 |
| 空分组 | Toast「当前分组没有股票」，**不覆盖**剪贴板原内容 |
| 开关 | `ConfigKeys.ZX_GROUP_COPY = "zx_group_copy"`，默认关闭 |
| 还原 | 原图 / 原监听 / 原 tint / 原 scaleType / 原 contentDescription / 原 clickable 全部挂在 ImageView 的 keyed tag 上；关开关还原成铅笔并恢复「自选管理」入口 |

---

## 1. 宿主契约（实测）

### 1.1 自选页顶栏

```
MainAct
  └─ ... #common_top (0,116-1080,240)
       ├─ MagicIndicator #indicator_zx          ← 自选页独有，用来认页
       ├─ ImageView #iv_top_manager (847,20-931,104)   ← 铅笔「编辑」，本功能替换
       └─ ImageView #iv_top_search (959,20-1043,104)   ← 搜索，不动
```

- 点 `#iv_top_manager` 原本打开 `自选管理`（股票 / 分组 / 表头 / 指数栏 / 其他）。
- **同名 id 复用**：行情页的 `#common_top` 里也有 `#iv_top_manager`，但它是 `GONE`；
  自选分组行右侧的「≡」也用同一个 id（在 `#ll_zx_groups` 里）。
  → 定位顺序固定为 `#indicator_zx` → 上溯 `#common_top` → 内部取 `#iv_top_manager`。

### 1.2 分组与列表

```
#layout_zx
  ├─ #indicator_zx_group              ← 分组 tab（全部 / 自选 / 持仓股）
  │    └─ #title_container → ColorBoldPagerTitleView ×3
  ├─ #iv_top_add / #iv_top_manager    ← 分组行右侧「+」与「≡」
  └─ LockViewPager #vp_zx_group       ← 3 页，每页一套列表
       └─ 当前页
            └─ PtrHQFrameLayout #ptr_zxg
                 └─ StockListView #stklv_zxg       ← 自定义容器，**不是 ListView**
                      ├─ StockHeaderView            ← 列头（兄弟节点，不在 adapter 里）
                      └─ InterceptListView          ← 真正的 AbsListView
```

- 当前页：`vp_zx_group.getCurrentItem()`；兜底「子视图 left 离 pager 左内边距最近」。
- 条目：`com.guosen.android.quotes.data.StockInfo`
  - 名称 `stkName`；代码 `m_stkCode`（继承自 `StockData`）/ `codeAndMarket`（`688111.2`）。
- 兜底：`adapter.getView(i, null, list)` 后读行内 `#tv_item_stk_name` / `#tv_item_stk_code`。
- 分组名：`#indicator_zx_group` 的 `#title_container` 第 `currentItem` 个子项文本。

### 1.3 宿主分组 API（备用，不首选）

探针 2026-10-23 实测：

```
ZXWorkflow.getAllGroups() -> 2 组（全部 / 自选）
ZXWorkflow.getStocksByGroupId(-1) -> 206 只（StockData：m_stkCode / m_stkMarket，无名称）
mDefaultGrpId = -1
```

- 有代码没名称；「持仓股」不在 `getAllGroups()` 里。
- 所以只在调试 / 对照时用，正式路径读当前页适配器。

---

## 2. 流程

```
点复制按钮
  ├─ 从 View 的 Context 取 Activity（监听不捕获页面）
  ├─ 找 #vp_zx_group → 当前页 → 第一个 AbsListView
  ├─ 逐条 adapter.getItem(i)：
  │    ├─ stkName + m_stkCode / codeAndMarket
  │    └─ 读不到 → adapter.getView(i,null,list) 读行内两个 TextView
  ├─ 代码规范化成 6 位；名称去空白；按「代码.市场」去重
  ├─ 空列表 → Toast「当前分组没有股票」，不写剪贴板
  └─ 写剪贴板（label「自选分组」）+ Toast「已复制「分组名」N 只股票」
```

- 日志只记数量与字符数，不写完整代码（规则 3.1）。
- 失败 / 早退都有带原因的日志（规则 3.3）。

---

## 3. 代码位置

| 文件 | 职责 |
|---|---|
| `feature/zxcopy/ZxGroupCopyFeature.kt` | `PageSyncFeature` 子类：认页、替换图标 / 监听、还原、点击入口 |
| `feature/zxcopy/ZxGroupCopyTargets.kt` | 宿主 id / 字段候选（`check_contract.py` 扫描） |
| `feature/zxcopy/ZxGroupStocks.kt` | 当前页 → 列表 → 条目字段读取（按类缓存 + 行文本兜底） |
| `feature/zxcopy/ZxCopyPlan.kt` | 纯逻辑：代码规范化、去重、格式化 |
| `feature/zxcopy/ZxCopyIcon.kt` | 自绘白描边复制图标（不引图标库） |
| `app/src/test/.../ZxCopyPlanTest.kt` | 8 条纯逻辑单测 |

开关接线：`ConfigKeys` / `ConfigSnapshot`（布尔位 25，剩余 7 位）/ `ConfigSource` /
`FeatureManager` / `FeatureCatalog`（新分区「自选」）/ `DebugConfigProvider` /
两套 `strings.xml` / `tools/contract/host-contract.json`。

---

## 4. 真机验证（2026-10-23）

| 项 | 结果 |
|---|---|
| 图标替换 | 自选页顶栏 `#iv_top_manager` 显示复制图标，`content-desc="复制分组股票"`，位置 847-931 × 136-220 与铅笔一致 |
| 复制「全部」 | 日志 `已复制「全部」206 只股票（2460 字符）`；键盘剪贴板预览首两行 `同花顺 300033` / `奥比中光-W 688322` |
| 复制「自选」 | 日志 `已复制「自选」206 只股票（2460 字符）`（该账号两只分组内容相同） |
| 复制「持仓股」 | 未登录交易 → 空列表，日志 `当前分组为空，未写剪贴板`，无崩溃 |
| 关开关还原 | 关 `zx_group_copy` 后顶栏恢复铅笔；点它重新打开「自选管理」 |
| 重开开关 | 从「自选管理」返回（`MainAct.onResume`）后复制图标重新出现 |
| 构建 | `:app:assembleRelease` ✓；`verify_apk.py --reject-debug-cert` ✓ |
| 静态检查 | 契约 198 条 ✓ / 不变量 8 条 ✓ / 开关 21 行 ✓ / 死代码 ✓ / 敏感信息 ✓ |

---

## 5. 验收清单

1. 打开开关后，自选页顶栏铅笔变成复制图标，搜索图标不变。
2. 点复制 → Toast 带分组名与数量；剪贴板一行一只「名称 代码」。
3. 切换「全部 / 自选 / 持仓股」后复制，分组名与数量跟着变。
4. 空分组只提示、不覆盖剪贴板。
5. 列表按涨幅 / 最新排序时，复制顺序与屏幕顺序一致。
6. 关开关：图标与点击行为都还原；重开并重进页面后恢复。
7. release 包日志不出现完整「代码.市场」。
8. 宿主重建自选页（切 tab / 重建 Fragment）后按钮仍是复制图标。

---

## 6. App 升级后怎么查

| 现象 | 先改哪里 |
|---|---|
| 按钮没换 | `ZxGroupCopyTargets.ZX_INDICATOR_ID` / `TOP_BAR_ID` / `EDIT_BUTTON_ID` |
| 点复制没反应 / 提示找不到列表 | `PAGER_ID` / `LIST_CONTAINER_ID`；确认页内还有 `AbsListView` |
| 名称或代码为空 | `NAME_FIELDS` / `CODE_FIELDS` / `CAM_FIELDS`；行内 id `NAME_ID` / `CODE_ID` |
| 分组名不对 | `GROUP_INDICATOR_ID` / `TITLE_CONTAINER_ID`，确认 `#title_container` 子项顺序 = 分组顺序 |
| 关开关没还原 | 确认 tag 还在（`gsaux_zxcopy_*`）；看 `restore` 是否扫到了 `seenDecors` |

日志关键字：`zx_group_copy：已把自选页的编辑按钮换成复制按钮` /
`已复制「…」N 只股票` / `当前分组为空` / `找不到分组页容器` / `找不到股票列表`。
