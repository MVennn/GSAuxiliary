# GitLab 仓库设置

> **一句话**：源码仓库 2026-10-20 迁到 GitLab（`M.Venn/gsauxiliary`），
> 本篇只讲**GitLab 这一侧**怎么配、怎么发版；发版规则本身在
> [RELEASE.md](RELEASE.md)，别在这里写第二份。

---

## 1. 现状

| 项 | 状态 |
|---|---|
| 源码仓库 | `https://gitlab.com/M.Venn/gsauxiliary`（路径全小写，显示名 GSAuxiliary） |
| 可见性 | 公开（`Public`） |
| 访问方式 | SSH（`git@gitlab.com:M.Venn/gsauxiliary.git`），密钥 `~/.ssh/id_ed25519` |
| GitHub | 账号 2026-10-18 起被停用，`.github/workflows/` 里的两套工作流**已跑不动**，只是留档 |
| LSPosed 模块仓库 | `Xposed-Modules-Repo/io.github.MVennn.GSAuxiliary` —— **本账号没有写权限** |

> ⚠️ **22 端口被本机代理的 fake-IP 拦了**（`gitlab.com` 解析到 `198.18.1.52`，
> TCP 能连上但 banner 超时）。绕法在 `~/.ssh/config` 里：

> ```
> Host gitlab.com
>   HostName altssh.gitlab.com
>   User git
>   Port 443
>   IdentityFile ~/.ssh/id_ed25519
>   IdentitiesOnly yes
> ```

---

## 2. 仓库里有哪些 GitLab 侧的文件

| 路径 | 作用 |
|---|---|
| `.gitlab-ci.yml` | 流水线：静态检查 → 编译/单测/红线 → 发版 |
| `.gitlab/issue_templates/` | Issue 模板（问题反馈 / 功能建议）—— **legacy markdown 格式**，理由见 §2.1 |
| `.gitlab/merge_request_templates/Default.md` | MR 模板 |
| `CONTRIBUTING.md` | 贡献指引（Issue / MR 怎么提、硬规矩清单） |
| `tools/publish_gitlab.py` | 发版到 GitLab 源码仓库（建 Release + 传 APK） |
| `docs/GITLAB.md` | 本篇 |

`.github/` 下的同名文件**继续保留**：GitHub 账号万一恢复，还能直接用；
现在它不参与任何构建。

### 2.1 Issue 模板必须是 markdown 文件（踩过的坑）

GitLab 19 上，`.gitlab/issue_templates/*.yml`（YAML 描述模板）与
`.gitlab/issue_forms/*.yml`（issue forms）**两种格式都被静默忽略** ——
新建 issue 的模板下拉里根本不出现，API 也不列它们。

**能用的是 legacy markdown 模板**：`.gitlab/issue_templates/<名字>.md`，
内容是 markdown，文件名就是下拉里显示的名字（本仓库用中文文件名：
`问题反馈.md` / `功能建议.md`）。

> 这三种格式**本地 YAML 解析都过得去、GitHub 侧也正常**，
> 只有真到 GitLab 页面点开那个下拉才知道认不认 ——
> **静态检查查不出来**，所以模板类的东西必须真机点一次。

### 2.2 CI 变量与受保护 tag

CI 变量是**受保护**的（只有 `main` 与**受保护 tag** 上的流水线读得到）。
所以配了受保护 tag 规则 `*-*.*.*` —— 不然发版那条流水线读不到签名变量。

---

## 3. 凭据

### 3.1 GitLab token（API 用）

发版脚本要调 API，用的是 **Personal Access Token**（scope `api`，2026-11-19 到期）：

| 位置 | 说明 |
|---|---|
| `~/.config/gitlab/token` | 脚本读这个（纯文本，一行） |
| `~/.config/glab-cli/config.yml` | 同一份令牌（`glab` 的标准位置，装了 glab 就能直接用） |

环境变量 `GS_GITLAB_TOKEN` 优先于这两个文件。**令牌只在内存里用，不落盘到仓库、不打印。**

两个文件的 ACL 已收紧成**只有当前账户可读**（`icacls … /inheritance:r /grant:r`）——
Windows 上 `chmod 600` 其实不生效，NTFS 权限要靠 ACL。

> **令牌曾经轮换过一次**（2026-10-20）：第一枚建它时的中间产物被我打印到了
> 对话记录里，所以作废重建了 —— 现在活跃的只有一枚 `gsauxiliary-setup`
> （id 28621885，2026-11-19 到期）。
>
> **重建的规矩**：令牌只能走「浏览器复制 → 剪贴板 → 直接落盘」这条路，
> **不要粘进对话**。本机有个现成的脚本干这件事（不在仓库里）：
> `.work/gitlab-token.ps1` —— 读剪贴板、写两个文件、收紧 ACL，
> 只打印长度与字符类别形状，不打印令牌本身。
>
> ⚠️ **写文件必须不带 BOM**：Windows PowerShell 5.1 的 `Set-Content -Encoding utf8`
> 会写 UTF-8 BOM，而令牌是放在 HTTP 请求头里的 —— BOM 混进去就是 401
> （令牌本身没错，请求头错了）。这个坑真踩过一次。

### 3.2 CI 变量（流水线用）

去 **Settings → CI/CD → Variables**（2026-10-20 已配好）：

| 变量 | 值 | 掩码 | 说明 |
|---|---|---|---|
| `GS_KEYSTORE_BASE64` | 密钥库本体的 base64 | ✅ | 让流水线签出与本机一致的包 |
| `GS_KEYSTORE_PASSWORD` | 密钥库口令 | ❌ 受保护 | 值只有 7 个字符，**不足 8 位不能掩码**（GitLab 的硬规矩） |
| `GS_KEYSTORE_ALIAS` | 密钥别名（`gszx`） | ❌ 受保护 | 同上（4 个字符） |
| `GS_KEYSTORE_KEY_PASSWORD` | 密钥口令 | ❌ 受保护 | 值只有 7 个字符 |

> 「受保护」= 只有 `main` 与受保护 tag 上的流水线读得到，够用。

### 3.3 账号级身份验证（已通过）

GitLab.com 上跑 CI 作业要过**两道门**：

| 门 | 状态（2026-10-20） |
|---|---|
| 账号两步验证（2FA） | ✅ 已开 |
| **账号级身份验证**（identity verification） | ✅ **已过**（用户用信用卡验证，页面 <https://gitlab.com/-/identity_verification>） |

没过之前，**流水线一创建就失败**，报：

```
Identity verification is required in order to run CI jobs
```

> 顺带解释一个现象：验证之前，**往 main 推一次就会留下一条 0 作业的 failed
> 流水线**（实测 18 条）。那不是 workflow 规则匹配上了 —— 规则只认
> MR / 手动 / tag —— 而是这道门在规则之前就拦下了，GitLab 于是留了条失败记录。
>
> 这道门**只挡 CI 作业**，不挡别的：仓库、Wiki、标签、Release、发版脚本
> （`tools/publish_gitlab.py`）全程正常 —— 3.3.0 就是这么发的。

---

## 4. 流水线

`.gitlab-ci.yml` 两段（**2026-10-20 真跑通过**）：

| 阶段 | 做什么 | 实测耗时 |
|---|---|---|
| `check` | 七项源码级检查（不变量 / 发版说明覆盖 / 免责副本同步 / 死代码 / 开关全链路 / 宿主契约 / **文档链接**）。2 秒判对错，不浪费 5 分钟构建。 | ~20s |
| `build` | 装 SDK（`tools/ci/setup-android-sdk.sh`）→ 跑单测 → 编 release → 校验产物红线（签名证书等 24 项）→ 留产物（APK + R8 mapping） | ~8 分钟 |

| 触发方式 | 跑什么 | 说明 |
|---|---|---|
| 开 Merge Request | 全套（静态检查 → 编译 → 单测 → 红线） | 推 `main` **不触发** |
| 流水线页「Run pipeline」 | 同上 | 想云端复验时手动跑 |
| API 触发（`POST /projects/:id/pipeline`） | 同上 | 脚本里复验用 |

> 为什么不在 push 上跑：日常改动一律本地编 + 真机验完才提交，推上去再编一遍是纯重复；
> 推得勤也最招风控。这条规矩从 GitHub Actions 那边沿用过来。

> **GitLab 的 `script:` 只吃字符串**（或字符串数组），不吃 `- name: / run:` 那种映射写法 ——
> 写成那样整条流水线会被 GitLab 判为配置非法、连创建都不允许。
> 本地 YAML 解析得过，GitLab 的校验更严：**改这份文件必须实际触发一次流水线才算验过。**
>
> 顺带两个实测出来的坑（都写在脚本注释里）：
> - **每个作业都是全新容器** —— 一个作业里 apt 装的 python3 不会留给下一个作业；
>   而 `eclipse-temurin` 镜像**没有 python3**（读版本号的 `tools/ci/version.py` 要它）。
> - **YAML 块标量里顶格的行会把整个块截断** —— 内嵌的 python 必须写成单行。

---

## 5. 发版（走本地脚本，不走流水线）

```bash
# 1. 改 app/build.gradle.kts 的 versionCode / versionName
# 2. 写一份面向用户的说明：docs/release-notes/<versionName>.md
#    （格式正本见 release-notes/README.md）
# 3. 出包 + 发版
python tools/publish_gitlab.py --notes docs/release-notes/3.3.0.md
```

脚本做四件事：读版本号 → 构建 → 跑 `verify_apk --reject-debug-cert`（签名红线：
**debug 证书直接失败**，否则所有已装用户得卸载重装）→ 建 Release 并传 APK 与 R8 映射。

也可以直接发布流水线产出的包（流水线用 CI 里的签名密钥编的，签名一致）：

```bash
python tools/publish_gitlab.py --skip-build --apk <下载下来的 APK>
```

### 5.1 为什么发版不放在 CI 里（2026-10-20 实测的三条路）

试过三种「推 tag → 流水线自动发版」，都走不通：

| 方案 | 结果 |
|---|---|
| API 三步：`POST /releases` → `POST /uploads` → `POST /assets/links`，用 `CI_JOB_TOKEN` | ❌ **Releases API 认作业令牌，但 uploads 接口不认**（401） |
| `release:` 关键字（底层是 glab），`assets.links` 给 `filepath` | ❌ **Release 建得出来**（✓ 作业令牌够用），但 `filepath` 不会被翻译成上传 → 报「url is missing」 |
| 把 PAT 放进 CI 变量 | ⚠️ 能通，但等于把**长期有效的发布凭据**挂在**公开**项目上 —— 最差的选择，不做 |

所以定成：**流水线只管「验证 + 产出签名 APK」，发布由维护者在本地做。**
（流水线本身已真跑通过：静态检查 → 装 SDK → 编译 → 单测 → 产物红线 → 留产物。）

### 5.1 模块仓库还没发

`Xposed-Modules-Repo/io.github.MVennn.GSAuxiliary` 本账号没有写权限，
所以 **LSPosed App 与 modules.lsposed.org 收不到更新**。
在账号恢复（或联系 LSPosed 维护者）之前，GitLab Releases 是唯一能装到包的地方。

---

## 6. 项目设置（2026-10-20 配好的）

| 项 | 值 |
|---|---|
| 描述 | 国信金太阳的 LSPosed 体验增强模块：界面精简、行情与策略筛选、盯盘快捷配置等 15 个开关，默认全部关闭、随时还原。 |
| 话题 | `lsposed` `xposed` `android` `kotlin` `goldsun` `guosen` `lsp-module` |
| 头像 | `assets/logo.png`（512×512） |
| 社交预览图 | `assets/social-preview.png` |
| 标签 | 功能增强 / 缺陷 / 文档 / 需求 / 需要信息 / 重复 / 暂不处理 / 已修复 |
| Wiki | 5 页：Home、安装、功能一览、开发与构建、排错 |
| 默认分支 | `main`（合并方式 merge，squash 默认关） |
| 其它 | 公开流水线日志、Wiki 开、Snippets / LFS / 容器仓库 / Packages 关 |

> 模块的**显示名**取自仓库 Description —— 这也是 LSPosed 模块页上显示的名字，
> 所以描述要写成「一句话能说清是什么」的样子，而不是一句「这是一个项目」。

---

## 7. 分支与标签的布局

GitLab 上**只有一个分支** `main`：

| 名字 | 类型 | 是什么 |
|---|---|---|
| `main` | 分支 | 唯一的工作分支（默认分支，已保护） |
| `{vc}-{vn}` | 标签 | 发版用的版本标签（`48-3.3.0`） |
| `archive/fix3-full-backup` | 标签 | **归档**：第三轮专项审查的落地提交 `ac003a9` —— 它的内容已在 main 里（逐行核对过），但那个提交本身不在 main 的历史里，所以用标签留住，不占分支位 |

> 迁移时推上去的 `backup/20261017-refactor`、`backup/20261020-pre-review4`
> 两个分支已删 —— 它们的提交全在 main 的历史里，删掉不丢东西
> （另有 `.work/backup/*.bundle` 全量 bundle 与本地 `.git` 兜底）。

---

## 8. 如果 GitHub 解封了

**能推，而且不用改历史**（2026-10-20 核过）：

| 项 | 现状 |
|---|---|
| `origin` 远端 | 还在（`https://github.com/MVennn/GSAuxiliary.git`） |
| 历史关系 | `origin/main`（`6b49fc7`）是本地 `main` 的**祖先** → 推上去是**快进**，不用 force、不用改历史 |
| 差距 | 本地领先 **99 个提交** |
| 凭据 | Windows 凭据管理器里**还留着** GitHub 凭据（`SansKritO4`）；若被一并撤销，就重造一个 PAT（`repo` scope），或把 `~/.ssh/id_ed25519` 加到 GitHub 并把远端换成 SSH |

解封后要做的三件事：

```bash
# 1. 代码与标签（一条命令，历史连续）
git push origin main --tags

# 2. 发版：GitHub + LSPosed 模块仓库 一次发完（需要 PAT 有 repo scope）
python tools/publish.py --notes docs/release-notes/3.3.0.md

# 3. 决定谁是「正本」：README 与 docs 里的链接现在指向 GitLab
#    （2026-10-20 迁移时改的，见 CHANGELOG 3.3.0 段）
```

> **模块仓库才是 LSPosed 用户收更新的地方**（modules.lsposed.org 与 LSPosed App 只读它）。
> GitLab 那边发 Release **不会**让 LSPosed 用户收到提示 —— 账号解封后，
> `publish.py` 那一步才真正能把 3.3.0 送进模块仓库。
>
> 签名密钥没变过 → 两边发出来的 APK 签名一致，已装用户**直接覆盖安装**，不用卸载。

---

## 9. 与其它文档的关系

| 想做的事 | 看哪篇 |
|---|---|
| 发版的**规则**（版本号怎么进位、要不要发版、Release 说明怎么写） | [RELEASE.md](RELEASE.md) |
| 版本号命名 | [VERSIONING.md](VERSIONING.md) |
| 构建（本地编、依赖版本为什么钉死、签名） | [BUILD.md](BUILD.md) |
| 编码纪律 | [CODE-RULES.md](CODE-RULES.md) |
| 文档规则 | [DOC-RULES.md](DOC-RULES.md) |
| 发版脚本的用法 | [tools/README.md](../tools/README.md) |
