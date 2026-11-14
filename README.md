<div align="center">

<img src="https://raw.githubusercontent.com/MVennn/GSAuxiliary/main/assets/logo.png" width="128" alt="GSAuxiliary">

# GSAuxiliary

**国信金太阳 · 体验增强模块**（非官方，与国信证券及其关联方无任何隶属、合作或授权关系）

Make GoldenSun Great Again！

![版本](https://img.shields.io/github/v/release/MVennn/GSAuxiliary) ![CI](https://github.com/MVennn/GSAuxiliary/actions/workflows/ci.yml/badge.svg) ![源码仓库](https://img.shields.io/badge/源码仓库-GitHub-181717?logo=github&logoColor=white) ![问题反馈](https://img.shields.io/badge/问题反馈-Issues-181717?logo=github&logoColor=white) ![Android](https://img.shields.io/badge/Android-8.1%2B-3DDC84?logo=android&logoColor=white) ![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-5C6BC0) ![License](https://img.shields.io/badge/License-Apache--2.0-blue)

</div>

---

## 这是什么

优化 国信金太阳 的界面显示和页面操作逻辑的 LSPosed 模块。

不采集任何个人信息，所有改动只发生在你自己的手机上；
个别功能（智能盯盘快捷配置）会按你的操作调用金太阳自身接口保存你自己的提醒设置，与手动在 App 里点一遍完全相同。

功能以模块 App「功能」页为准，这里按分区列一遍，方便核对版本差异。

## 功能

10 个分区、22 个开关，默认全部关闭；模块 App「功能」页按这个顺序分组，打开即生效。

| 分区 | 开关 | 作用 |
| --- | --- | --- |
| 登录 | 隐藏 新股申购 弹窗 | 登录后不再弹「今日新股申购提醒」 |
| 策略页 | 直达标准行情 | 策略详情里点股票直接进个股详情页，也可改用左右滑动切换 |
| 策略页 | 增加 全选 按钮 | 多选栏「取消」左边加一颗，一次勾上当前策略的全部股票 |
| 策略页 | 增加 截图并添加 按钮 | 多选态底栏「确认添加」左边加一颗：打开一次真实详情页逐只截图存相册，再自动替你点宿主「确认添加」 |
| 策略页 | 增加 筛选股票 菜单项 | 右上角三点菜单里加筛选面板，筛选条件可按策略分别保存 |
| 智能选股 | 我的策略聚合 | 几条自建策略并成一张原生结果页，入口卡挂在策略列表上，成员与上限在「聚合组设置」里调 |
| 股票详情页 | 自定义 详情页底栏 入口 | 把「更多」面板里的项（条件单 / 智能盯盘 / 智能K线 / 产业链 / 区间统计 / 交易明细 / 股票PK）提到详情页底栏，最多两项 |
| 智能盯盘 | 增加 配置 按钮 | 盯盘价格页（H5）「保存」右边加「配置」，按方案算好涨到 / 跌到填进去，再替你点一次宿主「保存」 |
| 智能盯盘 | 优化确定逻辑 | 涨到、跌到都空着也能保存（等于撤掉这条盯盘） |
| 智能盯盘 | 无需确定保存盯盘 | 保存后只弹一条轻提示并自动返回，返回位置可设 |
| 智能盯盘 | 盯盘数据卡片拓展 | 点盯盘数据卡片直接跳个股详情页 |
| 底栏 | 自定义 底栏 入口 | 底栏六格逐格隐藏（首页 / 行情 / 自选 / 交易 / 理财 / 账户），至少留一格 |
| 首页 | 隐藏 横幅 | 收起首页快捷入口下方那条横幅 |
| 行情市场 | 隐藏 滚动横幅与快捷入口 | 市场概览下面那条滚动横幅与它下面那排快捷入口一起收起 |
| 行情市场 | 板块显示单行 | 板块区压成一行，整块变矮 |
| 行情市场 | 隐藏 指数概览 | 顶部那块指数概览整块收起 |
| 行情市场 | 市场概览高度优化 | 市场概览的图表区（含柱状图）整体压矮 |
| 行情市场 | 增加 涨跌与自选 筛选项 | 沪深京A排行榜的筛选面板里追加「涨家 / 跌家 / 自选」三行，并据此收起不匹配的排行榜行 |
| 自选 | 自选分组复制 | 右上角「编辑」按钮换成「复制」：短按按当前规则复制，长按可选其它规则、分享文本、导出图片 / PDF，字段在「复制设置」里调 |
| 显示 | 手势导航条同色 | 安卓底部小白条按页面吸色取色 |
| 显示 | 自选股高亮 | 已加入自选的股票名称改用高亮色显示，颜色可换 |
| 显示 | 自选股名称加粗 | 同一批自选股的名称加粗 |

「设置」页另外管主题、语言、可预测返回、桌面图标、液态玻璃与玻璃光效、配置备份 / 恢复 / 重置、日志等级与导出；多数开关旁边还能进对应的二级设置（滑动设置、聚合组设置、方案设置、复制设置、高亮颜色设置…）。

## 环境要求

国信金太阳**不锁版本**：模块按控件名、类名与接口特征定位，宿主升级一般不必等模块跟进。万一某个开关在新版宿主里失灵，多半是定位用的类名 / 控件名变了 —— 定位用的候选常量（类名 / 控件 id / 文案）集中在各功能的 `Targets.kt` 里（仓库内共 16 份），按[排错手册第 6 节](https://github.com/MVennn/GSAuxiliary/blob/main/docs/TROUBLESHOOTING.md#6-app-升级后失效)改对应常量，再用 `tools/probe/` 重新定位即可。

安卓 8.1+，以及**较新的 LSPosed**（需要现代 Xposed API 102 与热重载能力）。过旧的 LSPosed 版本、太极 / LSPatch / EdXposed 等其它 Xposed 实现会**静默不加载**（表现为已启用但零日志、零生效）；如何判断框架新旧见 [排错手册](https://github.com/MVennn/GSAuxiliary/blob/main/docs/TROUBLESHOOTING.md#8-热重载相关)。

## 安装

1. [Releases](https://github.com/MVennn/GSAuxiliary/releases) 下载最新版（文件名形如 `GSAuxiliary_*.apk`）并安装
2. 在 LSPosed 管理器 → 模块里勾选 GSAuxiliary
3. 作用域中勾上「国信金太阳」（模块已声明默认作用域，一般会自动勾好）
4. 强制停止国信金太阳，再打开一次
5. 打开桌面上的 GSAuxiliary（首次打开会先请同意免责声明），进「功能」页挑选要开的开关（开关默认全部关闭；打开开关后无需重启金太阳，正在金太阳里停留的页面重新进一下即可生效）

## 反馈

遇到问题、想要新功能，或者金太阳升级之后某个开关失灵，都可以到 [Issues](https://github.com/MVennn/GSAuxiliary/issues) 进行反馈。

请上传相关信息与日志。相关信息主要包括：

- 金太阳的版本号
- 模块版本号、安卓版本、机型
- 出问题的开关，以及复现步骤
- 日志（模块在日志里的标记名是 `GSZXEnhance`，与模块名不同，照抄即可）：
  - **最省事**：模块设置页 → 日志 → 导出日志，一键导出两个进程的日志（txt），直接把这
    个文件发过来即可。想更详细，先把「日志等级」调成**调试**，复现一次再导出
  - 也可以用 adb 抓 LSPosed 日志（**目标进程的日志在这里**）：     
    `adb shell su -c "grep GSZXEnhance /data/adb/lspd/log/modules_*.log | tail -50"`
  - 模块 App 自己进程的日志在 logcat 里：`adb logcat -s GSZXEnhance`

## 文档

全部文档的索引在 [docs/README.md](https://github.com/MVennn/GSAuxiliary/blob/main/docs/README.md)。常用的几篇：

- [架构说明](https://github.com/MVennn/GSAuxiliary/blob/main/docs/ARCHITECTURE.md)：分层、注入点与进程模型
- [扩展指南](https://github.com/MVennn/GSAuxiliary/blob/main/docs/EXTENDING.md)：加一个新功能要做哪几步、合入前自检什么
- [构建](https://github.com/MVennn/GSAuxiliary/blob/main/docs/BUILD.md) · [版本规范](https://github.com/MVennn/GSAuxiliary/blob/main/docs/VERSIONING.md) · [发布清单](https://github.com/MVennn/GSAuxiliary/blob/main/docs/RELEASE.md)：从改代码到出 Release
- [排错手册](https://github.com/MVennn/GSAuxiliary/blob/main/docs/TROUBLESHOOTING.md)：按「现象 → 步骤 → 原因 / 解法」组织；装不上、开关点了没反应、热重载失灵先看它
- 功能专项 15 篇 `FEATURE-*.md`（见 [docs/](https://github.com/MVennn/GSAuxiliary/blob/main/docs/)）：一个功能一篇，写目标、真机实测的链路契约与实现要点
- [更新日志](https://github.com/MVennn/GSAuxiliary/blob/main/CHANGELOG.md)：每个版本改了什么、为什么改

## 免责声明

本项目仅供学习与个人使用。

- 不收集、不上传、不存储任何个人信息，不含统计分析、广告或数据回传组件
- 不修改目标 App 的安装包、不绕过任何鉴权；模块只改变界面显示与交互，不介入任何交易指令的生成与提交
- 智能盯盘快捷配置功能会按你的手动操作调用金太阳自身接口保存你自己的提醒设置，与在 App 内手动操作效果相同
- 本模块与投资决策无关，不以任何形式提供荐股类服务，不构成投资建议；完全免费开源，不直接或间接获取经济利益，据此操作产生的投资风险由使用者自行承担
- 请勿用于商业用途或任何违反金太阳服务条款的场景
- 使用本模块产生的任何后果由使用者自行承担

「国信金太阳」商标、标识的权利均归其权利人所有。本项目与之不存在任何隶属、合作、授权、认可或支持关系。

完整声明共十一条（含使用者声明与承诺、违约与授权终止），见 [docs/DISCLAIMER.md](https://github.com/MVennn/GSAuxiliary/blob/main/docs/DISCLAIMER.md)；模块 App 内「关于 → 免责声明」为同一内容。

## 开源鸣谢

本模块建立在这些开源项目之上：

| 项目                                                                                                                                    | 用途                                  |
| ------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| [**LSPosed**](https://github.com/LSPosed/LSPosed) · [**libxposed**](https://central.sonatype.com/artifact/io.github.libxposed/api)    | 模块框架与现代 Xposed API（API 102）         |
| [**Miuix**](https://github.com/compose-miuix-ui/miuix)                                                                                | 设置界面的 UI 组件库（小米 HyperOS 风格 Compose） |
| [**AndroidLiquidGlass**](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop`）                                   | 液态玻璃顶栏 / 悬浮底栏 / 弹窗的折射与模糊效果          |
| [**Jetpack Compose**](https://developer.android.com/jetpack/compose) · [**AndroidX**](https://developer.android.com/jetpack/androidx) | 全部界面实现的基础设施                         |
| [**Kotlin**](https://github.com/JetBrains/kotlin)                                                                                     | 模块与设置界面的实现语言                        |
| [**Material Icons**](https://github.com/google/material-design-icons)                                                                 | 界面图标的矢量路径数据来源（按 path 自绘，不引入图标库）     |
| [**HyperIsland**](https://github.com/1812z/HyperIsland)                                                                               | 关于页的布局与动效来源（MIT，非代码依赖，实现为本项目自写） |

## 许可证

本项目以 [Apache License 2.0](https://github.com/MVennn/GSAuxiliary/blob/main/LICENSE) 发布。第三方组件的版权与许可证声明见 [NOTICE](https://github.com/MVennn/GSAuxiliary/blob/main/NOTICE)；目标应用的参考数据（类名、键名、目录结构等事实性描述，不含目标应用代码）不随公开仓库分发，仅维护者本地保留。

模块不收集、不上传任何数据；完整数据流说明见 [docs/DISCLAIMER.md](https://github.com/MVennn/GSAuxiliary/blob/main/docs/DISCLAIMER.md) 第六条。
