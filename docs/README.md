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
| [UI-MATERIAL.md](UI-MATERIAL.md) | 界面材质与弹层：自绘 DialogHost、液态玻璃三档实现、组件清单与状态口径 |
| [UI-HISTORY.md](UI-HISTORY.md) | 模块 App 界面踩坑史：安装体积、系统栏沉浸、Launcher 图标的历史决策与根因 |

## 二、功能专项

| 文档 | 一句话定位 |
|---|---|
| [FEATURE-AISTARE-QUICK-CONFIG.md](FEATURE-AISTARE-QUICK-CONFIG.md) | 智能盯盘价格页「一键配置」：按方案自动填入涨到 / 跌到 |
| [FEATURE-AISTARE-CARD-DETAIL.md](FEATURE-AISTARE-CARD-DETAIL.md) | 盯盘数据卡片拓展：点卡片空白处直达个股详情页 |
| [FEATURE-STOCK-BAR.md](FEATURE-STOCK-BAR.md) | 个股详情页底栏「智能盯盘」快捷入口 |
| [FEATURE-DIRECT-QUOTE.md](FEATURE-DIRECT-QUOTE.md) | 策略详情页「直达标准行情 + 左右滑动」 |
| [FEATURE-SELECT-ALL.md](FEATURE-SELECT-ALL.md) | 策略详情页多选栏「全选 / 反选」 |
| [FEATURE-SNAP-ADD.md](FEATURE-SNAP-ADD.md) | 策略详情多选态「截图并添加」：真实详情页逐只截图 + 宿主分组页 |
| [FEATURE-STG-FILTER.md](FEATURE-STG-FILTER.md) | 策略详情页「筛选股票」（八项两组，与行情页那颗面板共用模型） |
| [FEATURE-MARKET-FILTER.md](FEATURE-MARKET-FILTER.md) | 行情页「涨跌与自选」筛选项 + 筛排行榜 |
| [FEATURE-IPO-DIALOG.md](FEATURE-IPO-DIALOG.md) | 隐藏 新股申购 弹窗，含真机校准手册 |
| [FEATURE-NAV-BAR.md](FEATURE-NAV-BAR.md) | 手势导航条同色 |
| [FEATURE-UI-CLEAN.md](FEATURE-UI-CLEAN.md) | 界面精简（底栏 / 首页 / 行情市场，含「隐藏 指数概览」），含真机校准手册 |

## 三、流程与规范

| 文档 | 一句话定位 |
|---|---|
| [BUILD.md](BUILD.md) | 构建指南：环境、依赖版本为什么钉死、签名、CI 构建与产物自查 |
| [RELEASE.md](RELEASE.md) | 发布清单：**交付规则正本（§0.1）**、双仓库发版、Release 说明格式、发版前检查 |
| [VERSIONING.md](VERSIONING.md) | 版本命名规范：三段式进位规则，**版本晋升正本（§1）** |
| [REVIEW-2026-10.md](REVIEW-2026-10.md) | 第四轮审查：修掉的缺陷、**五轮独立复核的经验**、装上的自动化防护（单测 / 静态检查 / 变异测试） |
| [MAINTENANCE.md](MAINTENANCE.md) | 收尾大扫除：磁盘垃圾 / 死代码 / 文档一致性清理，红线清单 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 排错手册：按「现象 → 步骤 → 原因 / 解法」组织的排查流程 |
| [CODE-RULES.md](CODE-RULES.md) | 编码纪律：两轮代码审查（2026-10-17）与注入性能排查（2026-10-19）提炼的必守规则与新功能自检清单，写代码前后各过一遍 |
| [NAMING.md](NAMING.md) | 文案与命名规范：`strings.xml` 里用户可见文案怎么写 |
| [LIQUID-GLASS.md](LIQUID-GLASS.md) | 液态玻璃材质规范：完整 / 简约两档光效的正本定义、逐表面参数矩阵、版本降级链 |
| [DOC-RULES.md](DOC-RULES.md) | 文档规则：分类定位、单一正本映射表、链接纪律、变更触发矩阵 |
| [TOOLS-DOC-RULES.md](TOOLS-DOC-RULES.md) | tools/ 下的文档规则：README 与脚本注释怎么保持一致；tools/ 的入口文档是 [`tools/README.md`](../tools/README.md) |
| tools/ [`README.md`](../tools/README.md) | tools/ 下全部脚本的用法总表：环境变量总表 + 七大类分类总览，每个脚本一节（五要素条目格式） |
| tools/ [`probe/README.md`](../tools/probe/README.md) | 运行时探针：目标 App 升级 / 加固变严后的运行时侦察工具 |
| tools/ [`contract/dump/README.md`](../tools/contract/dump/README.md) | 宿主类名 dump 快照：这份 8.6.0 类名的**能证什么、不能证什么**，以及宿主升级后怎么重做全量快照 |

## 四、参考资料

| 位置 | 一句话定位 |
|---|---|
| ~~reference/~（本地留存） | 逆向采集的**原始数据**档案（探针日志、Activity 清单、APK 解析报告），因含宿主内部信息不再随公开仓库分发，维护者本地保留一份作比对基准 |
| [release-notes/](release-notes/README.md) | 面向用户的 Release 说明，文件名 = `versionName`；格式规范在它的 README |
| [DISCLAIMER.md](DISCLAIMER.md) | 免责声明**中文规范源**；[DISCLAIMER.en.md](DISCLAIMER.en.md) 是英文规范源。两份各自与 `strings.xml` 的 `disclaimer_full` 同步维护（`tools/ci/check_disclaimer_sync.py` 守着） |
| 仓库根 [CHANGELOG.md](../CHANGELOG.md) | 开发者记录：症状 / 根因 / 改法 / 验证结果 |

---

> 新增 / 删除文档后**必须同步更新本索引**（规则见 [DOC-RULES.md](DOC-RULES.md)）；
> 新文档至少要被一处既有文档引用，不允许孤儿。
