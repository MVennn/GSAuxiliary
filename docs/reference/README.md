# 参考数据（Reference）

这里收录的是**逆向过程中采集到的原始数据**，不是代码。当 App 升级导致 Hook 失效、需要重新定位时，这些是比对基准。

> 逆向过程见 [`../REVERSE-ENGINEERING.md`](../REVERSE-ENGINEERING.md)。

---

## 文件清单

| 文件 | 内容 | 来源 |
|---|---|---|
| `activities-guosen.txt` | `com.guosen.*` 的 **662 个 Activity 类名**（明文，未混淆） | 静态解析 `AndroidManifest.xml` |
| `activities-all.txt` | 全部 **814 个** Activity（含第三方库） | 同上 |
| `apk-analysis-report.md` | APK 完整解析报告：基础信息、加固识别、H5 层、可行性结论、红线 | 8.6.0 初版分析 |
| `recon-datadir.txt` | `/data/data/com.guosen.android/` 目录结构（顶层 + shared_prefs） | 运行时探针 v1 |
| `recon-watchlist-keys.txt` | shared_prefs 中含自选关键字的文件 / 键 | 同上 |
| `recon-databases.txt` | App 自有数据库清单（含 `gs_zxstock_db` 自选库） | 同上 |
| `recon-yellow-log.txt` | 黄系颜色探针日志：`TextView.setTextColor` 抓到的 `#fff5a623` 染色调用 + 页面链路 | 探针 `GSRecon` TAG |

---

## 怎么用

### 1. 定位页面类名

`recon-yellow-log.txt` 里有完整的页面跳转序列：

```
ACTIVITY > com.guosen.quotation.ui.aistk.AIStkIndexAct
ACTIVITY > com.guosen.quotation.ui.aistk.AIStkStgListAct
ACTIVITY > com.guosen.quotation.ui.aistk.AIStkStgDetailAct
```

对照 `activities-guosen.txt` 就能知道每个 Activity 属于哪个业务模块。

### 2. 自选数据来源

`recon-databases.txt` 显示自选股存在 SQLite：

```
gs_zxstock_db   (53 KB)   <- 自选股数据库
```

但**实际染色不读 DB，走内存的 `ZXManager` 单例**（见 `HOOK-NOTES.md`）。DB 只是持久层。

### 3. 染色证据

`recon-yellow-log.txt` 顶部：

```
YELLOW > color=#fff5a623 (245,166,35) id=tv_tech_val2  act=com.guosen.quotation.ui.StockFragAct
```

这说明行情页的"技术指标"也是这个黄色系（`#fff5a623`），与自选黄名 `#fff58000` 不同——所以**判自选要看 `isZx` 字段，不能只看颜色**。

### 4. App 升级后怎么核对

新版国信金太阳到来时：

1. 重跑探针（`tools/probe/`），拿到新的 `recon-yellow-log.txt`
2. 和这里的旧版**逐行 diff**
3. 按功能改对应的候选常量文件（三个功能各一份，互不影响）：

| 功能 | 改哪里 | 主要常量 |
|---|---|---|
| 自选高亮 | `feature/watchlist/WatchlistTargets.kt` | `TARGET_ADAPTERS` / `CLS_BASE_QUICK_ADAPTER` / `NAME_ID_CANDIDATES` / `CAM_FIELDS` / `CODE_FIELDS` / `MARKET_FIELDS` |
| 直达标准行情 + 左右滑动 | `feature/strategyquote/StgDirectQuoteTargets.kt` | `CLS_ENGINE_ACT` / `LAUNCH_METHODS` / `ACTION_*` / `KEY_STOCK_*` |
| 多选栏全选 / 反选 | `feature/strategyselect/StgSelectAllTargets.kt` | `CANCEL_BUTTON_ID_CANDIDATES` / `LIST_ID_CANDIDATES` / `CHECKBOX_ID_CANDIDATES` |
| 自选判定 API | `feature/watchlist/ZxWatchlist.kt` | `ZXWorkflow.isBelongByStock` / `ZXManager.m_stkGrpMap` 兜底 |

完整排查流程见 [`../TROUBLESHOOTING.md` §6](../TROUBLESHOOTING.md#6-app-升级后失效)。
