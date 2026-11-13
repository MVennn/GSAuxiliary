# 策略详情页「勾选态」的跨功能通道

> **一句话**：策略详情页上「勾着哪几只」是筛选股票、全选、我的策略聚合、截图并添加
> 四个功能共用的状态 —— 本篇是**唯一正本**：谁写、谁读、按什么键对齐、换行集合之后
> 必须做什么，以及它们之间唯一的共享通道 `feature/stgrow/`。
>
> 版本：**3.8.5**（通道与四处接入） · 目标 App：国信金太阳 **8.7.0**
> 相关代码：[`feature/stgrow/`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgrow/)
> 姊妹篇：[FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md)（筛选）、
> [FEATURE-SELECT-ALL.md](FEATURE-SELECT-ALL.md)（全选 / 确认添加）、
> [FEATURE-STRATEGY-MERGE.md](FEATURE-STRATEGY-MERGE.md)（聚合）、
> [FEATURE-SNAP-ADD.md](FEATURE-SNAP-ADD.md)（截图并添加）

---

## 一、为什么需要这一篇

四个功能同时挂在同一个 `AIStkStgDetailAct` 的同一个 `lvStks` 上：

| 功能 | 对勾选态做什么 |
|---|---|
| 筛选股票 `stgfilter` | 换 / 更新列表代理，**必须把勾选按代码搬过去** |
| 全选 `strategyselect` | 合成行点击把每只勾上 / 取消；事后把宿主模型校正成屏幕真值 |
| 我的策略聚合 `stgmerge` | 原地重写 `adapter.data` 并 notify —— **行集合整体换人** |
| 截图并添加 `stgsnap` | 只读：「勾着哪几只」决定截哪几张图 |

宿主把「勾着哪几只」存在**两套坐标**里：

- `AbsListView.mCheckStates` —— 按**适配器位置**（`lv.isItemChecked(header + i)`）；
- 宿主模型字段 `checkCodes` —— 按**代码**（见 [FEATURE-SELECT-ALL.md](FEATURE-SELECT-ALL.md) §2.2）。

只要行集合被换过（套代理、并集写回、宿主 notify 重建），两套坐标就和屏幕对不上。
2026-11-13 用户报的「筛选不准 / 加错票」正是这个漂移：屏幕上勾着 A/B/C，结算成别的票。
四个功能各修各的话，缺陷只会在**两个功能交界处**反复重生 —— 所以收敛成一条通道。

---

## 二、三个坐标与谁写谁读

| 资源 | 写者 | 读者 | 对齐键 |
|---|---|---|---|
| `mCheckStates`（`lv.setItemChecked`） | 全选（预置 + 合成点击）、筛选（按代码迁移）、聚合（写回前后迁移） | 全选 `captureSelection`、截图并添加 `SnapSelection.read`、筛选迁移 | **`代码@市场`** |
| 宿主模型 `checkCodes` | 只有全选的 `syncHostModel` | 截图并添加 `readFromHostModel`（带 `modelTrusted` 校验） | 代码 |
| `#tv_stg_res_num`「策略个股 X/Y」 | 筛选写 `策略个股 保留数/总数`、聚合写 `策略个股 N 只` | 用户 | 两种格式**逐字相同**才互认 |
| `#tv_bottom_zxg_num`「已选 N」 | **宿主自己**（模块零写点） | 截图并添加、全选读来做交叉验证 | — |

「已选 N」模块一个都不写：想让它变，只能走 `performItemClick` 让宿主自己数。
这是既有约束，别试图反射改计数。

---

## 三、通道 API

`feature/stgrow/StgRowSelection.kt` —— 进程级单例，只存「刚算出来的结论」：

| 成员 | 说明 |
|---|---|
| `FRESH_MS = 8_000` | 快照新鲜期。超过就当没有（用户随时可能手点改勾选） |
| `keyOf(code, market)` | 归一键：`600000@1`；市场号空时按 `StockTypes.marketOf(code)` 推导（推不出才退化成纯代码） |
| `keyOfCam(cam)` | `600000.1` → `600000@1`（走 `CodeMarketParts.split`） |
| `publish(owner, keys)` | 发布。`owner` / `keys` 有 null 或 `keys` 为空 → **撤回**（空集合等于「不知道」） |
| `freshOf(owner)` | 同一个 owner 且未过期才给，否则 null |
| `rowEpoch()` / `bumpRowEpoch()` | 行集合版本号，只增不减 |

`feature/stgrow/StgRowChecked.kt` —— 按代码读写的唯一实现，四个功能共用：

```kotlin
// 读：逐行读 cam → 归一键；非多选态或任一行读不出 → null（读不全就整体放弃）
fun keysOf(lv: ListView, count: Int, rowCam: (Int) -> String?): Set<String>?

// 写：先把 count 行的键整趟读全，再全量对齐 setItemChecked(header + i, want)；返回改动行数
fun applyChecked(lv: ListView, count: Int, keys: Set<String>, rowCam: (Int) -> String?): Int
```

行号一律 `header + i`（`header = lv.headerViewsCount`），调用方只管「第 i 行是第几只」。

---

## 四、四条硬规矩

1. **读行代码不许问代理。**
   筛选生效时列表上挂的是 `FilteredAdapter`，它把 `position` 当子集下标。
   但行点击 wrapper 在 `runWithRemapBypassed` 帧里会把 `position` 当**全量**下标转发
   （见 [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md) 旁路段），于是同一个
   `adapter.getItem(position)` 在两种帧里语义相反 —— 旁路帧里读到的是**全量同下标的另一只票**。
   所以一律「用 `keptIndices` 换回全量下标，再问 base 适配器」：
   `StgStockFilterFeature.camOfRow(base, rows, position)` 就是这件事。
2. **先整趟读全，再写。** 读到一半读不出就 `return` 是**部分迁移**：屏幕上一半新坐标、
   一半旧坐标，比不迁移更难查。
3. **键必须 `代码@市场`，且市场号要在键里补齐。** 只取代码的话，同代码不同市场会互相认领，
   搬勾选搬到另一只票上。而市场号又**未必拿得到** —— 宿主的 `StockInfo.codeAndMarket` 是构造后
   填充的，有「只有代码」的中间态：若那一刻退化成纯代码键，同一只票在补齐前后会算成两个键，
   迁移时勾选丢一次（2026-11-13 修）。所以 `keyOf` 在市场号为空时按代码前缀推导
   （`StockTypes.marketOf`），推不出才退化成纯代码。
4. **换行集合的功能必须发信号。** 筛选 `applyFilter0` 与聚合 `reapplyIfNeeded` 写完之后
   无条件 `StgRowSelection.publish(lv, keys)` + `bumpRowEpoch()`；
   批量点行的循环在每行前比对 epoch，不等就重新快照（见 [FEATURE-SELECT-ALL.md](FEATURE-SELECT-ALL.md) §2.3.6）。

---

## 五、时序：谁会插进谁的栈帧

所有触发点都在宿主主线程，没有第二条线程。唯一能**同步**插进「用户点某一行」那个栈帧的
是 `BaseAdapter.notifyDataSetChanged` 的前置钩子（`StgStockFilterFeature` 里的
`hookNotifyDataSetChanged`）。

聚合页点一行的真实链条：

```
clickRow → performItemClick → 行点击 wrapper（旁路帧）→ 宿主 onItemClick
  → 宿主把单策略 data 塞回 + notifyDataSetChanged
      → 筛选前置钩子跑 applyFilter0 → 第一趟迁移
  → 聚合 patchBeforeNotify 写并集 + notifyAdapter
      → 第二趟迁移
```

所以聚合侧**不接手挂过滤代理的列表**：`readCheckedKeys` 见到
`StgFilterBridge.isFor(adapter)` 为真就直接返回 null，交回筛选的钩子做 ——
两趟各写一遍必然互相覆盖。

不需要互斥锁：迁移只写 `mCheckStates`（`setItemChecked` 不 notify），
`applyFilter` 自身有 ThreadLocal 重入闸，成不了环。

---

## 六、四个功能的接入点

| 功能 | 位置 | 做了什么 |
|---|---|---|
| 筛选 | `StgStockFilterFeature.applyFilter0` | 换代理前 `checkedKeysOf`（用 `oldRows` 换回全量下标读），换之后 `restoreCheckedKeys` 全量对齐；随后 `publish` + `bumpRowEpoch` |
| 聚合 | `StrategyMergeFeature.reapplyIfNeeded` | `writeListInPlace` `前` 按代码读、`notifyAdapter` `后` 按代码写回；挂代理时不接手 |
| 全选 | `StgSelectAllFeature.clickOneRow` | 每行前比 `rowEpoch`，变了就 `scheduleNextRound(0)` 重开一轮（补 `run.full == null` 时零校验的盲区） |
| 截图并添加 | `SnapSelection.readFromHostModel` | 读宿主模型前加 `StgSelectAllPlan.modelTrusted(codes, hostCount)`：条数与底部计数对不上就是残值，改按 checked states 判 |

### 6.1 为什么截图并添加不优先用通道快照

手动勾选**不触发 notify**，通道快照只在「行集合刚换过」那一瞬最新。用户手点几下之后
快照就是旧的，而 `mCheckStates` 永远实时。所以 snap 只读 `mCheckStates`，
既有的 8 s 新鲜期只是给「换行集合的瞬间」留的兜底。

---

## 七、验收（真机）

1. 聚合组（并集 ≥20 只）→ 筛选到 5 只以内 → 勾第 2、4 行 → 改筛选条件让结果换一批：
   勾选应跟着**同一只股票**走，不出现在别的行上。
2. 同上点「勾选加入自选股 → 确认添加」：分组里正好是屏幕上勾的那几只
   （不是全量前 N 只、不是残值）。
3. 聚合组里逐个手点勾选 5 只（每点一行都会触发两趟迁移）→ 再点确认添加 → 数目与代码都对。
4. 日志关键字（`GLog`，只在这些功能动手时出现）：
   `筛选股票：勾选态按代码迁移 N 行` / `勾选态按代码对齐 N 行` /
   `策略全选：行集合在运行中变了` / `截图并添加：宿主模型 checkCodes=N 项与底部计数=M 对不上`。

---

## 八、已知未做（留着，别当成已修）

- **`#tv_stg_res_num` 计数仍是双写**：筛选与聚合各写各的，只靠「格式逐字相同 + 同值 no-op」
  避让。聚合看门狗每 700 ms 的 `force` 慢路可能把筛选文案顶回去。要修就得定仲裁优先级，
  目前没有证据说它在真实操作里互相打架。
- **行 View 染色（自选高亮 / 加粗）不参与这条通道**：它只写 `setTextColor` / `typeface`，
  与勾选无关，也不影响提交包。
