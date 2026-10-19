# 宿主类名 dump 快照

## 这是什么

`8.6.0-271-classes.txt` 是**金太阳 8.6.0（vc271）**里出现过的宿主类全限定名，
一行一个，共 83 个。

来源：探针（`com.wb.probe`，已装在真机上）的类 dump，日志在
`/data/adb/lspd/log/modules_*.log`（跨轮转文件 grep）。

**类名不是敏感信息**（不含持仓、不含 URL），所以直接进仓库。

## 它能做什么、不能做什么

| | |
|---|---|
| ✅ **能** | 证明「这个候选类名**现在还认得到**」 |
| ❌ **不能** | 证明「这个类**已经没了**」 |

原因：**探针是按需 dump 某个类的** —— 一次 dump 往往只覆盖一个页面。
实测这份快照只覆盖了**个股详情页的 K 线区**（`KXChartGroupView` 一族），
所以契约清单里的 29 个类名只有 **20 个**出现在里面，
剩下 9 个（`BaseQuickAdapter` / `FilterDiaglog` / `AIStareMyStareAdapter` …）
**只是没被那次 dump 扫到，不是失效了**。

所以 `check_contract.py` 对「不在 dump 里」的项**只提示、不报错** ——
用覆盖不全的 dump 判「失效」会大量假阳性。

## 怎么更新

宿主升级后要重做一次**全量**类遍历（探针逐个 `CLASS` 来，或用 dex 工具扫宿主的
`classes.dex`），把新的类名列表替换这个文件，并同步
`tools/contract/host-contract.json` 里的 `host.versionName` / `host.versionCode`。

在那之前，**宿主改名这件事只能靠用户在真机上发现**（表现为「某个功能不生效」）。
