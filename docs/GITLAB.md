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
| `.gitlab/issue_templates/` | Issue 模板（问题反馈 / 功能建议） |
| `.gitlab/merge_request_templates/Default.md` | MR 模板 |
| `CONTRIBUTING.md` | 贡献指引（Issue / MR 怎么提、硬规矩清单） |
| `docs/GITLAB.md` | 本篇 |

`.github/` 下的同名文件**继续保留**：GitHub 账号万一恢复，还能直接用；
现在它不参与任何构建。

---

## 3. CI 变量（要发版就得配）

去 **Settings → CI/CD → Variables** 加这四个（都勾 **Masked** 与 **Protected**）：

| 变量 | 值 | 怎么造 |
|---|---|---|
| `GS_KEYSTORE_BASE64` | 密钥库本体的 base64 | `base64 -w0 gszx.keystore` |
| `GS_KEYSTORE_PASSWORD` | 密钥库口令 | `keystore.properties` 里的 `storePassword` |
| `GS_KEYSTORE_ALIAS` | 密钥别名 | `keystore.properties` 里的 `keyAlias`（当前是 `gszx`） |
| `GS_KEYSTORE_KEY_PASSWORD` | 密钥口令 | `keystore.properties` 里的 `keyPassword` |

可选：`GS_CERT_SHA256` —— 钉死签名证书指纹，证书换了就红。

> **Masked 的前提**：值里不能有空格，长度 ≥ 8。base64 后的密钥库满足。
>
> **Protected 的含义**：只有受保护分支（`main`）上的流水线能读到这些变量 ——
>   这正是我们要的：别让 fork 提的 MR 读到密钥。

**没配这四个会怎样**：流水线照跑，静态检查、编译、单测都过，
但 release 作业会**报错退出**（`[X] 缺少签名相关的 CI 变量`），
**不会**发一个 debug 签名的包 —— 那会让所有已装用户被迫卸载重装。

---

## 4. 流水线怎么触发

| 触发方式 | 跑什么 | 说明 |
|---|---|---|
| 开 Merge Request | 全套（静态检查 → 编译 → 单测 → 红线） | 推 `main` **不触发** |
| 流水线页「Run pipeline」 | 同上 | 想云端复验时手动跑 |
| 推 tag（形如 `48-3.3.0`） | 上面全套 + 建 Release、传 APK | tag 必须与 `app/build.gradle.kts` 一致 |

> 为什么不在 push 上跑：日常改动一律本地编 + 真机验完才提交，推上去再编一遍是纯重复；
> 推得勤也最招风控。这条规矩从 GitHub Actions 那边沿用过来。

三个阶段：

1. **`static-checks`** —— 六项源码级检查（不变量 / 发版说明覆盖 / 免责副本同步 /
   死代码 / 开关全链路 / 宿主契约）。2 秒判对错，不浪费 5 分钟构建。
2. **`build`** —— 装 SDK（`tools/ci/setup-android-sdk.sh`，共享 runner 不预装 SDK）→
   跑单测 → 编 release → 校验产物红线（签名证书、版本号、宿主标识…）→ 留产物。
3. **`release`** —— 只在 tag 上跑。校验 tag 与版本号一致 → 用
   `CI_JOB_TOKEN` 调 GitLab API 建 Release 并传 APK 附件。
   **同项目内的作业令牌就够用**，不必再在 GitLab 上存一份长期 PAT。

---

## 5. 发版（推 tag 就行）

```bash
# 1. 改 app/build.gradle.kts 的 versionCode / versionName
# 2. 写一份面向用户的说明：docs/release-notes/<versionName>.md
#    （格式正本见 release-notes/README.md）
# 3. 提交，然后打同名 tag 并推上去
git tag 48-3.3.0
git push gitlab main 48-3.3.0
```

流水线会自己建 Release、传 `GSAuxiliary_48-3.3.0.apk`。

> **模块仓库还没发**：`Xposed-Modules-Repo/io.github.MVennn.GSAuxiliary` 本账号
> 没有写权限，所以 **LSPosed App 与 modules.lsposed.org 收不到更新**。
> 这是当前唯一没解决的对外分发问题，要等账号恢复或联系 LSPosed 维护者。
> 在那之前，GitLab Releases 是唯一能装到包的地方。

---

## 6. 项目设置（只能在浏览器里改的那几项）

GitLab 的**项目元信息**没有公开的免密接口，要么用 Personal Access Token 调 API，
要么在浏览器里改。下面几项是 2026-10-20 一次性配好的：

| 项 | 值 |
|---|---|
| 描述（Description） | 见 §6.1 |
| 话题（Topics） | `lsposed`, `xposed`, `android`, `kotlin`, `goldsun`, `guosen` |
| 头像（Avatar） | `assets/logo.png` |
| 社交预览图 | `assets/social-preview.png` |

> 模块的**显示名**取自仓库 Description —— 这也是 LSPosed 模块页上显示的名字，
> 所以描述要写成「一句话能说清是什么」的样子，而不是一句「这是一个项目」。

### 6.1 描述文案

```
国信金太阳的 LSPosed 体验增强模块：界面精简、行情与策略筛选、盯盘快捷配置等 15 个开关，默认全部关闭、随时还原。
```

---

## 7. 与其它文档的关系

| 想做的事 | 看哪篇 |
|---|---|
| 发版的**规则**（版本号怎么进位、要不要发版、Release 说明怎么写） | [RELEASE.md](RELEASE.md) |
| 版本号命名 | [VERSIONING.md](VERSIONING.md) |
| 构建（本地编、依赖版本为什么钉死、签名） | [BUILD.md](BUILD.md) |
| 编码纪律 | [CODE-RULES.md](CODE-RULES.md) |
| 文档规则 | [DOC-RULES.md](DOC-RULES.md) |
