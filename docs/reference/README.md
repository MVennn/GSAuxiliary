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

本目录的 txt 是**比对基准**：App 升级后，用新版数据与这里的旧版逐行 diff，定位变化的
类名 / 字段名 / 控件 id。各主题的结论与证据都在专门文档里，这里只留「该用哪份数据、
结论看哪篇」的索引：

| 主题 | 结论 / 证据看哪 | 依据这里哪份数据 |
|---|---|---|
| 定位页面类名 | [`../REVERSE-ENGINEERING.md`](../REVERSE-ENGINEERING.md)（过程与证据 §四、难点与解法 §2 页面链路） | `recon-yellow-log.txt`（完整的页面跳转序列）+ `activities-guosen.txt`（按包归类，据此知道每个 Activity 属于哪个业务模块） |
| 自选数据来源 | [`../REVERSE-ENGINEERING.md`](../REVERSE-ENGINEERING.md)（难点与解法 §3） | `recon-databases.txt`：自选股确实存在 SQLite（`gs_zxstock_db`），但**实际染色不读 DB、走内存的 `ZXManager` 单例**，DB 只是持久层 |
| 染色证据（为什么不能只看颜色） | [`../REVERSE-ENGINEERING.md`](../REVERSE-ENGINEERING.md)（难点与解法 §3） | `recon-yellow-log.txt`：行情页「技术指标」也是黄系 `#fff5a623`，与自选黄 `#fff58000` 不同 —— 所以**判自选要看 `isZx` / `ZXWorkflow`，不能只看颜色** |
| 探针怎么跑、挂了哪些点 | [`../../tools/probe/README.md`](../../tools/probe/README.md) | —（探针只负责产出上面的 `recon-*.txt`） |

### App 升级后怎么核对

新版国信金太阳到来时：

1. 重跑探针（[`tools/probe/`](../../tools/probe/README.md)），拿到新的 `recon-*.txt`
2. 和这里的旧版**逐行 diff**
3. 按功能改对应的候选常量文件（**每个功能一份，互不影响**）：

| 功能 | 候选常量文件 |
|---|---|
| 自选高亮 | `feature/watchlist/WatchlistTargets.kt`（判定 API 在 `feature/watchlist/ZxWatchlist.kt`：`ZXWorkflow.isBelongByStock` + `ZXManager.m_stkGrpMap` 兜底） |
| 直达标准行情 + 左右滑动 | `feature/strategyquote/StgDirectQuoteTargets.kt` |
| 多选栏全选 / 反选 | `feature/strategyselect/StgSelectAllTargets.kt` |
| 策略详情筛选股票 | `feature/stgfilter/StgStockFilterTargets.kt` |
| 个股详情页底栏「智能盯盘」入口 | `feature/stockbar/StockDetailBarTargets.kt` |
| 底栏隐藏理财入口 | `feature/dock/DockCleanTargets.kt` |
| 首页隐藏横幅 | `feature/home/HomeBannerTargets.kt` |
| 行情市场（滚动横幅 / 板块 / 概览，三功能共用一份） | `feature/market/MarketTargets.kt` |
| 手势导航条同色 | `feature/navbar/NavBarTargets.kt` |
| 智能盯盘价格页「一键配置」 | `feature/aistare/AiStareQuickConfigTargets.kt` |
| 我的盯盘卡片跳个股详情页 | `feature/aistare/AiStareCardDetailTargets.kt` |
| 隐藏 新股申购 弹窗 | `feature/ipoclean/IpoCleanTargets.kt` |

完整排查流程见 [`../TROUBLESHOOTING.md` §6](../TROUBLESHOOTING.md#6-app-升级后失效)。
