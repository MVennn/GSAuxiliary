# docs/ 索引

> **一句话**：这里只有索引与定位，不放正文 —— 每篇文档一句话说明「它管什么」，
> 规则本身按 [DOC-RULES.md](DOC-RULES.md) 的单一正本原则只在对应文档里定义。

文档分四类。每篇开头都有一个「一句话」引言块，先看它再决定要不要往下读。

---

## 一、架构与逆向

| 文档 | 一句话定位 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构说明：分层与进程模型，每个功能挂在宿主的哪个点上 |
| [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md) | 逆向与 Hook：取证过程与结论（前半「过程与证据」）、卡过的问题与解法（后半「难点与解法」） |
| [EXTENDING.md](EXTENDING.md) | 扩展与演进指南：怎么给这套架构加一个新功能 |
| [UI.md](UI.md) | 模块 App 设置界面：逐页逐控件的说明与约束 |
| [UI-HISTORY.md](UI-HISTORY.md) | 模块 App 界面踩坑史：安装体积、系统栏沉浸、Launcher 图标的历史决策与根因 |

## 二、功能专项

| 文档 | 一句话定位 |
|---|---|
| [FEATURE-AISTARE-QUICK-CONFIG.md](FEATURE-AISTARE-QUICK-CONFIG.md) | 智能盯盘价格页「一键配置」：按方案自动填入涨到 / 跌到 |
| [FEATURE-AISTARE-CARD-DETAIL.md](FEATURE-AISTARE-CARD-DETAIL.md) | 盯盘数据卡片拓展：点卡片空白处直达个股详情页 |
| [FEATURE-STOCK-BAR.md](FEATURE-STOCK-BAR.md) | 个股详情页底栏「智能盯盘」快捷入口 |
| [FEATURE-DIRECT-QUOTE.md](FEATURE-DIRECT-QUOTE.md) | 策略详情页「直达标准行情 + 左右滑动」 |
| [FEATURE-SELECT-ALL.md](FEATURE-SELECT-ALL.md) | 策略详情页多选栏「全选 / 反选」 |
| [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md) | 策略详情页「筛选股票」 |
| [FEATURE-IPO-DIALOG.md](FEATURE-IPO-DIALOG.md) | 隐藏 新股申购 弹窗，含真机校准手册 |
| [FEATURE-NAV-BAR.md](FEATURE-NAV-BAR.md) | 手势导航条同色 |
| [FEATURE-UI-CLEAN.md](FEATURE-UI-CLEAN.md) | 界面精简（底栏 / 首页 / 行情市场），含真机校准手册 |

## 三、流程与规范

| 文档 | 一句话定位 |
|---|---|
| [BUILD.md](BUILD.md) | 构建指南：环境、依赖版本为什么钉死、签名、CI 构建与产物自查 |
| [RELEASE.md](RELEASE.md) | 发布清单：**交付规则正本（§0.1）**、双仓库发版、Release 说明格式、发版前检查 |
| [VERSIONING.md](VERSIONING.md) | 版本命名规范：三段式进位规则，**版本晋升正本（§1）** |
| [MAINTENANCE.md](MAINTENANCE.md) | 收尾大扫除：磁盘垃圾 / 死代码 / 文档一致性清理，红线清单 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 排错手册：按「现象 → 步骤 → 原因 / 解法」组织的排查流程 |
| [NAMING.md](NAMING.md) | 文案与命名规范：`strings.xml` 里用户可见文案怎么写 |
| [DOC-RULES.md](DOC-RULES.md) | 文档规则：分类定位、单一正本映射表、链接纪律、变更触发矩阵 |
| [TOOLS-DOC-RULES.md](TOOLS-DOC-RULES.md) | tools/ 下的文档规则：README 与脚本注释怎么保持一致 |

## 四、参考资料

| 位置 | 一句话定位 |
|---|---|
| [reference/](reference/README.md) | 逆向采集的**原始数据**档案（探针日志、Activity 清单、APK 解析报告），App 升级后的比对基准 |
| [release-notes/](release-notes/README.md) | 面向用户的 Release 说明，文件名 = `versionName`；格式规范在它的 README |
| [DISCLAIMER.md](DISCLAIMER.md) | 免责声明全文，与 `strings.xml` 的 `disclaimer_full` 双副本同步维护 |
| 仓库根 [CHANGELOG.md](../CHANGELOG.md) | 开发者记录：症状 / 根因 / 改法 / 验证结果 |

---

> 新增 / 删除文档后**必须同步更新本索引**（规则见 [DOC-RULES.md](DOC-RULES.md)）；
> 新文档至少要被一处既有文档引用，不允许孤儿。
