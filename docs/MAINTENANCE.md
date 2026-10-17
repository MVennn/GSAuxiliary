# 收尾大扫除（维护与清理）

> **一句话**：每批开发收尾时说一句「**做一次大扫除**」，就按本文档把磁盘垃圾、
> 死代码、失效文档清一遍，最后出一版能装的包并推送。

适用范围：本仓库（`MVennn/GSAuxiliary`），以及它旁边的两个发布目标
（`Xposed-Modules-Repo/io.github.MVennn.GSAuxiliary` 元文件、`.work/lsposed-repo` 克隆）。
不涉及重新设计功能，也不改用户可见行为 —— 大扫除只做「减法」。

---

## 1. 触发语与术语

用户只要说下面任意一句，就执行本文档（**先通读全文再动手**，不要跳步骤）：

| 说法 | 说明 |
|---|---|
| 做一次大扫除 | 全流程，§3 ~ §6 都走 |
| 收尾清理 | 同上 |
| 清理技术债 / 代码卫生检查 | 只跑 §3 的扫描与 §4 的候选复核，不删磁盘文件 |

程序员里这件事没有唯一叫法，几个常用词对应本文档的不同层：

| 术语 | 英文 | 对应本文档 |
|---|---|---|
| 技术债清理 | tech debt cleanup | 整个流程 |
| 死代码消除 | dead code elimination | §3 L2 |
| 代码卫生 | code hygiene | §3 L2 |
| 仓库保洁 / housekeeping | housekeeping | §3 L1 + L4 |

---

## 2. 什么时候做

- **一批功能 / 修复全部推送完**（`git status` 干净、`main` 与 `origin/main` 同点）；
- 准备发版之前（先大扫除，再按 `docs/VERSIONING.md` 起版本号）；
- 仓库明显变大的时候（`du -sh .` 超过 200M 基本就是 `.work/` 和 `app/build/` 造成的）。

**不要**在并发会话还开着的时候删 `.work/`：那里是几个会话共用的中转站，
另一个会话可能正在往里写。先确认没人跑着任务，再清。

---

## 3. 四层清理

### L1 磁盘垃圾（可再生产物）

先跑扫描，拿到清单再删：

```bash
python tools/hygiene.py junk
```

| 对象 | 能不能删 | 说明 |
|---|---|---|
| `app/build/`、`build/` | 能 | Gradle 产物，`./gradlew clean` 就是干这个。删了下次编译慢一点，没别的代价 |
| `.gradle/`、`.kotlin/` | 能 | 工程级缓存与 Kotlin 会话目录，自动重建 |
| `tools/probe/build/`、`tools/probe/out/` | 能 | 探针的中间产物与 APK（`tools/probe/build.sh` 重新生成） |
| `.work/` 下的临时物 | 能 | 截图、旧 APK、日志、`commit-msg*.txt`、实验脚本、解出的宿主缓存 |
| `.work/lsposed-repo/` | **不能** | 发布用的模块仓库克隆，`tools/publish.py` 依赖它 |
| `.work/apk/` | 保留最新一份 | 只留当前版本一个可安装包，别堆十几个历史 APK |
| `.work/log.mark`、`.work/wifi_serial` | **不能** | 日志游标与无线调试地址，删了下一次要重新找 |

清理完体积应该回到 20M 上下（源码 + `.git` + 文档）。

### L2 代码垃圾（死代码 / 冗余资源）

```bash
python tools/hygiene.py all      # ①②③④ 四条一起跑
```

| 扫什么 | 判断 | 怎么处理 |
|---|---|---|
| 未引用的字符串 / 数组 / 颜色 / 尺寸 | `R.string.x`、`@string/x` 都搜不到 | 删资源条目；`values-en` 那条一起删，别只删一半 |
| 没人引用的顶层声明 | 全仓库只出现在声明处 | 先 `git grep <名字>` 复核，再删 |
| 文件内没调用的 `private` 成员 | 文件里只出现一次 | 直接删（`private` 不可能被别处用） |
| 没被用到的 `import` | 短名在正文里搜不到 | 删 import 行 |

**误报清单**（看到这些别再查一遍，直接放过）：

- `DebugConfigProvider` —— AndroidManifest 按名字实例化（调试通道）；
- `GszxModule` —— `META-INF/xposed/java_init.list` 反射加载；
- 只在 `proguard-rules.pro` 的 `-keep` 里出现的类 —— 那是运行期入口；
- 反射字符串里出现的类名 / 字段名（`util/Reflect.kt` 的调用点不会以符号形式引用）。

**删代码的三条硬要求**：① 每条先 `git grep` 复核；② 一次只删一类，删完立刻
`./gradlew :app:compileDebugKotlin`；③ 涉及 Hook 点、反射字段名的一律不动
（那是逆向结论，不是"没用的代码"）。

### L3 文档一致性

| 检查 | 命令 | 处理 |
|---|---|---|
| 文档里提到的源文件是否还在 | `python tools/hygiene.py decls` 之后人工对照 `docs/*.md` | 文件没了就改文档说法，别留着误导 |
| 功能页与 `docs/UI.md` 是否还一致 | 对照 `docs/UI.md` 的页面清单 | 页面删了就补一句「为什么不再有」（参照 §5.2 日志页的写法） |
| `CHANGELOG.md` 有没有漏条目 | `git log --oneline <上个版本>..HEAD` 逐条比对 | 漏的补进「未发布」段 |
| `docs/` 里的相对链接是否失效 | 打开被改过的文档点一遍 | 断链改掉或删掉 |

文档里**保留**「历史上曾经有过 X、后来为什么删了」这类段落：那是决策记录，
不是垃圾。判断标准是「这段还在帮人做决定吗」，而不是「提到的文件还在吗」。

### L4 配置与约定

- `.gitignore` 是否覆盖了新出现的产物类型（新目录 / 新后缀）；
- `app/proguard-rules.pro` 的 `-keep` 是否还有对应类（功能删了，规则也要删）；
- `app/build.gradle.kts` 的依赖是否都还在用（`git grep` 包名）；
- `docs/VERSIONING.md`、`docs/RELEASE.md` 里的版本号 / 流程描述是否还成立。

---

## 4. 收尾清单

按顺序做，每步都要真的执行：

1. `git status` 干净、`git fetch` 后与 `origin/main` 同点（有别人的在途改动先问）；
2. `python tools/hygiene.py all`，把候选逐条复核；
3. 删代码 → `./gradlew :app:compileDebugKotlin` 编译过；
4. `./gradlew clean`，再删 `.work/` 与 `tools/probe/` 下的产物；
5. `./gradlew :app:assembleRelease` —— 从零编一次，确认删干净了还能出包；
6. 把包复制到 `.work/apk/`，只留这一份；
7. 提交：`chore(hygiene): 收尾大扫除` + 正文写清删了什么、为什么删、体积变化；
8. `git push origin main`，再 `git fetch` 确认本地 = 远端；
9. 模块仓库（`.work/lsposed-repo`）没发版就**不动**，只确认它干净；
10. 回头核一遍自己那批提交在 `CHANGELOG.md` 里的条目还在不在（并发会话整文件
    重写 CHANGELOG 时会把别人的条目抹掉，`grep` 一下就知道）。

汇报时给三件事就够：**删了什么**（分类列）、**省了多少**（前后体积）、
**留了什么 / 为什么留**（红线项与有意保留的占位代码）。

---

## 5. 红线（一律不动）

- 签名与凭据：`gszx.keystore`、`keystore.properties`、`tools/probe/probe.keystore`；
- 发布链路：`tools/publish.py`、`.github/workflows/`、`xposed-repo/`、`docs/release-notes/`；
- 逆向结论：`docs/REVERSE-ENGINEERING.md`、`docs/HOOK-NOTES.md` 里的类名 / 字段名 / Hook 点，
  哪怕代码里暂时没用到（`INSTANCE_PROVIDERS` 这类空实现是**有意占位**，别当死代码删）；
- 用户数据：任何 `*.keystore`、`local.properties`；
- `assets/` 里的源图（`icon.svg` 是 launcher 图标的母本，删了没法重新生成）。

---

## 6. 上一次大扫除（2026-10-17）

| 项 | 结果 |
|---|---|
| 仓库体积 | **458M → 109.6M**（清完构建产物；重编后 `app/build` 105M） |
| `.work/` | **268M → 1.7M**（删了 4 个逆向缓存目录、45M 压缩包、20 个截图、9 个历史 APK） |
| 死代码 | `AppIcons.iconOf`（40 行，图标统一后没人用）、`HostQuoteSource.stockInfoClass`（构造函数里加载一次、从不读） |
| import | 删 5 条未使用的（`AdapterView`、`GLog`、`Dp`、`Icon`、`padding`） |
| 资源 | 0 条冗余（`hygiene.py res` 扫完为 0） |
| 依赖 | 4 个依赖全部在用，无冗余 |
| 保留 | `.work/lsposed-repo`、`.work/log.mark`、`.work/wifi_serial`、`tools/probe/probe.keystore`、`INSTANCE_PROVIDERS` 空占位 |
