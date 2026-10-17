# 文档规则

> **一句话**：docs/ 下的文档怎么分类、怎么互相引用、改动时连带改哪些 ——
> 本篇只管「怎么写、怎么保持一致」，不承载任何具体技术规则。

---

## 1. 分类与定位

docs/ 分四类（索引见 [README.md](README.md)）：

| 类别 | 收什么 | 现有成员 |
|---|---|---|
| 架构与逆向 | 模块怎么搭、宿主怎么逆出来 | ARCHITECTURE、REVERSE-ENGINEERING、EXTENDING、UI、UI-HISTORY |
| 功能专项 | 一个功能一篇（FEATURE-*） | 9 篇 FEATURE-*.md |
| 流程与规范 | 构建、发版、版本、维护、排错、命名、编码纪律、文档规则 | BUILD、RELEASE、VERSIONING、MAINTENANCE、TROUBLESHOOTING、NAMING、CODE-RULES、DOC-RULES、TOOLS-DOC-RULES |
| 参考资料 | Release 说明、免责全文 | release-notes/、DISCLAIMER（reference/ 已转本地留存，不随仓库分发） |

每篇文档开头必须有一个 **「一句话」引言块**（`> **一句话**：…` 或一行定位语），
让读者不用往下翻就知道这篇管什么。新文档没有它不算写完。

tools/ 目录下的文档规则单独见 [TOOLS-DOC-RULES.md](TOOLS-DOC-RULES.md)，不在本篇重复。

## 2. 单一正本原则

**每条规则只在一篇文档里定义；其余位置只允许一句话概述 + 指向正本的链接。**
两处都写全文，迟早各改各的。

权威正本映射表：

| 规则 | 正本 |
|---|---|
| 交付规则（每版必出 release APK / 不发 debug / 不发版不动版本号） | [RELEASE.md](RELEASE.md) §0.1 |
| 版本晋升（三段式进位） | [VERSIONING.md](VERSIONING.md) §1 |
| 大扫除（磁盘 / 死代码 / 文档一致性） | [MAINTENANCE.md](MAINTENANCE.md) |
| 文案命名 | [NAMING.md](NAMING.md) |
| 免责双副本同步 | [MAINTENANCE.md](MAINTENANCE.md) §3 L3 |
| Release 说明格式（含风险提示固定尾巴） | [release-notes/README.md](release-notes/README.md)；风险提示模板在 [RELEASE.md](RELEASE.md) §2 |
| 逆向结论（类名 / 字段名 / Hook 点） | [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md) 前半「过程与证据」 |
| Hook 难点与解法 | [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md) 后半「难点与解法」 |
| 编码纪律（生命周期 / 热路径 / 日志隐私 / 缓存 / 仓库操作） | [CODE-RULES.md](CODE-RULES.md) |
| 排错步骤 | [TROUBLESHOOTING.md](TROUBLESHOOTING.md) |

### 禁止重复清单（本次审查发现的反例，勿重蹈）

1. **HOOK-NOTES ↔ REVERSE-ENGINEERING**：Hook 难点与逆向过程分写两篇，互相引用、
   主题重叠（加固、页面链路、染色、Hook 点各出现两遍）。已合并为一篇
   （REVERSE-ENGINEERING 前半过程 + 后半难点），HOOK-NOTES.md 删除。
2. **apk-analysis-report ↔ REVERSE-ENGINEERING**：8.6.0 初版解析报告的结论与
   逆向文档重复。已在报告顶部加「结论以 REVERSE-ENGINEERING 为准」声明降级为
   原始数据档案，重叠段落保留（原始数据价值）。
3. **交付规则三写**：「每次交付出 release APK / 不发 debug / 不发版不动版本号」
   曾同时写在 BUILD 头部注、BUILD §8、VERSIONING §5。已收敛为 RELEASE §0.1
   唯一正本，其余位置各留一句话 + 链接。

## 3. 红线内容保护：逆向结论逐字保留

REVERSE-ENGINEERING（含原 HOOK-NOTES 并入部分）、FEATURE-*.md 里的
**类名、方法名、字段名、Hook 点、资源 id、候选常量数组名**，在文档合并或修订时
**必须逐字保留，一个不删** —— 哪怕当前代码里暂时没用到（那是下次 App 升级后的
排查依据）。重复叙述可以压缩，事实与名字不行。此红线与 [MAINTENANCE.md](MAINTENANCE.md) §5 一致。

## 4. 链接纪律

- 统一用**相对链接 + 锚点**（形如 `[标题]（文档名.md#锚点）`，写时用半角括号），不用绝对路径。
- **改动 / 删除 / 合并文档后必须双向点验**：
  - 出链：新文档里指向别处的链接目标还存在、锚点没变；
  - 入链：全仓搜旧文件名（Grep），把指向它的链接改成新位置 ——
    **禁止只删文档不改引用方**。
- 判断某段「重复」是否该收敛时，先看它被谁引用；收敛后引用方必须同步改。
- 历史记录里的旧文件名（如 CHANGELOG 历史版本条目）可保留原样 —— 那是当时的事实；
  但**正文中指向现役文档的链接**必须始终指向现役文档。

## 5. 变更触发矩阵

改了什么，就必须连带改什么：

| 变更 | 必须同步 |
|---|---|
| 新增 / 删除功能 | [ARCHITECTURE.md](ARCHITECTURE.md) §7 功能表、[UI.md](UI.md) 功能页清单、[NAMING.md](NAMING.md) §5、候选常量表（reference/ 已转本地留存） |
| 发版 | 根目录 CHANGELOG.md、`docs/release-notes/<版本>.md` |
| 改免责全文 | `strings.xml` 的 `disclaimer_full` + [DISCLAIMER.md](DISCLAIMER.md) 双写（细则见 [MAINTENANCE.md](MAINTENANCE.md) §3 L3） |
| 改构建 / 脚本 | [BUILD.md](BUILD.md) / [MAINTENANCE.md](MAINTENANCE.md) 对应段 |
| 新增 / 删除文档 | [README.md](README.md) 索引（见 §7） |

## 6. 体量预算与样例时效

- **单篇 ≤ 800 行。** 超了就按「正文档 + 踩坑史」拆：正文档留结论与约束，
  历史 / 根因分析移到 HISTORY 篇。范例：[UI.md](UI.md)（正文档）+
  [UI-HISTORY.md](UI-HISTORY.md)（安装体积、系统栏沉浸、Launcher 图标的踩坑史）。
- **样例时效**：带版本标注的样例（如「2.12.1 实测」「以 3.1.0 / 46 为例」）
  可容忍过时 —— 它们是历史记录；但**功能计数、功能名清单**类内容
  （「共 N 个功能」「功能列表」）必须随功能变更同步，不允许停留在旧版本状态。

## 7. 索引维护与孤儿零容忍

- [README.md](README.md) 是 docs/ 的唯一索引，**新增 / 删除 / 改名文档必须同步更新**。
- **孤儿文档零容忍**：任何新文档必须至少被一处既有文档引用（或进索引并有明确入口），
  否则等于没写。
