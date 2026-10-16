# 发布清单

> **一句话**：发版跑 `python tools/publish.py --notes <说明>.md`，它会把两个仓库一次发完。
> 别手工在两个仓库各来一遍。

---

## 0. 为什么必须发两个仓库

本模块有**两个仓库**，它们**互不同步**：

| 仓库 | 谁在用 |
|---|---|
| `SansKritO4/GSAuxiliary`（源码） | 源码、Issue、GitHub 访客的下载入口 |
| `Xposed-Modules-Repo/io.github.SansKritO4.GSAuxiliary`（模块仓库） | **LSPosed 网站与 App 只读这里** |

`Xposed-Modules-Repo/*` 是 LSPosed 官方组织下的**独立仓库**，
由提交申请时的 bot 自动创建、我们以 `maintain` 身份维护。

> **在源码仓库发 Release，模块仓库不会收到任何东西。**
> 而 modules.lsposed.org、LSPosed App 的「更新」提示只读模块仓库 ——
> 所以**只发源码仓库 = 用户收不到更新**。

两个仓库必须满足：

- **同 tag**：`{versionCode}-{versionName}`（例：`38-2.16.1`）
- **同产物**：`GSAuxiliary_{versionCode}-{versionName}.apk`（release 包，唯一发布物）
- **同 Release 说明**
- 模块仓库的 `README.md` 与源码仓库**逐字一致**

> 为什么不再发 debug 包：早期发 debug 是为了让用户装上来复现问题，但实测有用户
> **直接拿 debug 版当日常版本用** —— debug 包保留完整日志、签名也不该外流。
> 需要 debug 包排查时，本地 `./gradlew assembleDebug` 自打自装即可，不进 Release。

---

## 1. 发版

```bash
# 0. 先确认凭据对两个仓库都有写权限（几秒钟，只调 API，不写任何东西）
python tools/publish.py --check

# 先干跑一遍，看清楚它要做什么
python tools/publish.py --dry-run

# 正式发（--notes 传一份面向用户的短说明，见第 2 节）
python tools/publish.py --notes .work/release-notes.md
```

脚本依次做五件事：

| 步骤 | 动作 |
|---|---|
| 1 | 读 `app/build.gradle.kts` 的 `versionCode` / `versionName`，拼出 tag |
| 2 | `./gradlew assembleRelease`，把 APK 复制成 `GSAuxiliary_{vc}-{vn}.apk` |
| 3 | **源码仓库**建/更新 Release + 传 release 包 |
| 4 | **模块仓库**建/更新 Release + 传 release 包 |
| 5 | 把源码仓库的四个母本同步到模块仓库（`README.md` / `SUMMARY` / `SOURCE_URL` / `icon.svg`） |

可选参数：

```bash
python tools/publish.py --skip-build          # 用现有 APK，不重新构建
python tools/publish.py --sync-only           # 只同步那四个元文件，不发 Release
python tools/publish.py --dry-run             # 只打印，不写任何东西
```

凭据的三种给法（按优先级）：环境变量 `GS_GITHUB_TOKEN` → 环境变量 `GH_TOKEN` →
`git credential fill`（本机 Windows 凭据管理器里已存的 GitHub PAT，需要 `repo` scope）。
**token 只在内存里用，不落盘、不打印。**

> `publish.py` **故意不读 `GITHUB_TOKEN`** —— 那是 GitHub Actions 自带的、
> 只对当前仓库有效的短命 token，拿去写模块仓库会 403。宁可报错也不静默用错。

---

## 1.1 从 CI 发版（推个 tag 就行）

`.github/workflows/release.yml` 把上面这一整套搬到了 GitHub 上。你只需要：

```bash
# 1. 改 app/build.gradle.kts 的 versionCode / versionName
# 2. 写一份面向用户的说明（可选，但强烈建议）
vim docs/release-notes/2.14.0.md      # 文件名 = versionName
# 3. 提交，然后打一个同名的 tag
git tag 34-2.14.0 && git push origin 34-2.14.0
```

然后 CI 依次做（前 5 步都在装 JDK / SDK **之前** —— 几秒就能判定的事，
不该等构建跑完 5 分钟才告诉你）：

| 步骤 | 动作 | 失败会怎样 |
|---|---|---|
| 1 | 校验 tag == `versionCode-versionName` | 直接报错（防止给未完工版本建假 Release），并把对比结果写进 job summary |
| 2 | 校验 5 个 Secrets 齐全 | 直接报错，列出缺哪几个 |
| 3 | `python tools/publish.py --check` 查 token 对两个仓库的写权限 | 直接报错 |
| 4 | 解码 keystore 并用 `keytool` 验密码 / 别名 | 直接报错 |
| 5 | `./gradlew assembleRelease` | 直接报错 |
| 6 | `python tools/ci/verify_apk.py --reject-debug-cert` 跑红线 | 直接报错，产物不发 |
| 7 | `python tools/publish.py --skip-build --notes docs/release-notes/<vn>.md` | 直接报错 |

第 7 步就是本机那条命令的同一份代码 —— **双仓库发布 + 元文件同步的逻辑只有一处**，
不会出现「CI 发了一套、本机发了一套，两边不一样」。

第 3 步和第 6 步是这一版新加的，各自堵一个坑：

- **第 3 步**：`publish.py` 是先发源码仓库、再发模块仓库。如果 token 写不了模块仓库，
  源码仓库那份已经建好了、用户却什么都收不到，而日志只报「失败」，
  很容易被当成偶发问题重跑。提前几秒查掉。
- **第 6 步的 `--reject-debug-cert`**：`app/build.gradle.kts` 在四个 `GS_KEYSTORE_*`
  不齐全时会**安静地**退回 debug 签名。那样签出来的包签名和以前不一样 ——
  已装用户必须卸载重装才能更新。这条检查会让它直接变红，而不是发出去。
  想更狠一点，再配一个可选的 `GS_CERT_SHA256`（见 §1.2），把证书指纹也钉死。

也支持 `workflow_dispatch` 手动触发（填一个已存在的 tag），并且多一个
**`dry_run` 开关**：打开后照常构建、照常跑红线，但**不往任何仓库写东西**。
第一次用 CI 发版、或者只想确认密钥配对了没有，先拿它演一遍。

> **为什么 `LSPOSED_REPO_TOKEN` 缺了要直接失败**：如果 CI 只发源码仓库就报成功，
> 你会以为发完了，而 modules.lsposed.org 和 LSPosed App 一个字都收不到（见 §0）。
> 宁可红，不要假绿。


## 1.2 CI 发版需要的 5 个 Repository secrets

`Settings → Secrets and variables → Actions → New repository secret`：

| Secret | 怎么造 |
|---|---|
| `GS_KEYSTORE_BASE64` | `base64 -w0 gszx.keystore > ks.txt`（Windows: `certutil -encode gszx.keystore ks.txt` 后去掉首尾行），把 `ks.txt` 内容整个贴进去 |
| `GS_KEYSTORE_PASSWORD` | 你的 keystore 密码 |
| `GS_KEYSTORE_ALIAS` | 你的 key 别名 |
| `GS_KEYSTORE_KEY_PASSWORD` | 你的 key 密码 |
| `LSPOSED_REPO_TOKEN` | PAT（classic，勾 `repo`），且对 `Xposed-Modules-Repo/io.github.SansKritO4.GSAuxiliary` 有写权限 |
| `GS_CERT_SHA256` | **可选**。签名证书的 SHA-256（小写十六进制，无冒号）。配了就把 release 包的证书钉死 |

这四个 `GS_KEYSTORE_*` 由 `app/build.gradle.kts` 直接读（见该文件「签名配置」段）。
**四个必须齐全**才生效，半套配置会静默退回 debug 签名 —— 而
**用 debug 签名发版会让已装用户必须卸载重装**，所以宁可它不生效时看得见。
（现在它真的「看得见」了：`release.yml` 里那条 `--reject-debug-cert` 会直接把它拦下来。）

`GS_CERT_SHA256` 怎么取（本机现成的 APK 就行）：

```bash
python tools/ci/verify_apk.py app/build/outputs/apk/release/app-release.apk \
  | grep '证书 SHA-256'
#   [OK] 已签名（scheme v2）证书 SHA-256: a7fc6034bc5937df133973c9bd60d0191d0beb143b705b1f304692ed107ac325
```

那个值就是你的模块身份。配上之后，`release.yml` 会逐字比对 ——
签名变了就别指望能发出去，也就不会出现「用户必须卸载重装」这种事。

> ⚠️ **不要**用 `secrets.GITHUB_TOKEN` 替代 `LSPOSED_REPO_TOKEN`：
> Actions 自带的 token 作用域只在本仓库内，写不了模块仓库。


---

## 2. Release 说明要短、面向用户

- **CHANGELOG 是开发者记录**（症状 / 根因 / 改法 / 验证结果、crash 栈、验证表格），
  **不要**把它整段当 Release 说明 —— 用户看到的是噪音。
- Release 说明只讲**这版做了什么**，几句话就够。写长了说明你在解释，而解释属于 CHANGELOG。
- **形状、以及「别写什么」「『注意』区块怎么写」见 `docs/release-notes/README.md`**
  —— 那边是唯一权威，本节不再放第二份模板，免得两处规则打架。
- **推荐写法：把短说明放进 `docs/release-notes/<versionName>.md`**（跟着 tag 一起进仓库，
  可回溯、可 review）。`release.yml` 会自动找这个文件；
  本机 `publish.py` 也可以直接 `--notes docs/release-notes/2.14.0.md`。
- 不传 `--notes` 时脚本会退回 CHANGELOG 的对应段落，**并主动警告**。

---

## 3. 发版前检查

> **走 CI 发版（§1.1）的话，下面第 3~9 条由 `release.yml` 自动做了**，
> 你只需要保证第 1、2、10 条 —— 也就是「版本号递增了、CHANGELOG 写了、
> 没有在途开发」。手工在本机发版才需要全部自己过一遍。

- [ ] `app/build.gradle.kts` 的 `versionCode` / `versionName` 已递增
- [ ] `CHANGELOG.md` 顶部已加该版本的段落
- [ ] `docs/release-notes/<versionName>.md` 已写（CI 找它当 Release 说明）
- [ ] `python tools/publish.py --check` 说两个仓库都可写
- [ ] `./gradlew assembleRelease` 通过（本地调试另跑 `assembleDebug`，不发布）
- [ ] `python tools/ci/verify_apk.py <apk>` 全部 `[OK]`（红线自动化，见 `docs/BUILD.md` §8.3）
- [ ] release dex 里 `Lio/github/libxposed/api` 与 `.../annotation` 定义类为 **0**（红线）
- [ ] manifest 无 legacy `xposed*` meta-data（**别用 `grep -i xposed`**，见下）
- [ ] **release 包签名证书是你的，不是 Android 调试证书** —— 一条命令：

  ```bash
  python tools/ci/verify_apk.py --reject-debug-cert \
      app/build/outputs/apk/release/app-release.apk
  ```

  指纹要和上一版一致；不一致就说明签名配置掉回了 debug 签名，
  **发出去会让所有用户必须卸载重装**。
- [ ] **没有正在进行的在途开发** —— 否则版本号可能是下一个未完工的版本

> manifest 自查要精确匹配 key，不能用宽匹配：
>
> ```bash
> aapt2 dump xmltree --file AndroidManifest.xml <apk> \
>   | grep -iE "xposedminversion|xposedmodule|xposedsharedprefs|xposedscope|xposeddescription"
> # 应无输出
> ```
>
> `@string/xposed_description`（字符串资源名，带下划线）与
> `de.robv.android.xposed.category.MODULE_SETTINGS`（LSPosed 找设置入口必需）
> 是**合法**的，用 `grep -i xposed` 会误报。

---

## 4. 只在模块仓库做的事

### 4.1 仓库描述（**必须浏览器手动**，已完成 ✅）

描述字段同时是模块在 LSPosed 网站与 App 里的**显示名**。为空 ⇒ **模块不会出现在
modules.lsposed.org**。

当前值：**`GSAuxiliary-国信金太阳APP体验增强`**（另有 Website 与 4 个 Topics）。

要改的话：

1. 打开 https://github.com/Xposed-Modules-Repo/io.github.SansKritO4.GSAuxiliary
2. 右侧 **About** 区块 → **齿轮 ⚙**
3. 改 **Description** → **Save changes**

> ⚠️ **REST API 改不了**：GitHub 权限矩阵里 `Edit a repository's description` 对
> **Maintain 是允许的**，但 `PATCH /repos/{owner}/{repo}` 这个端点要求 **Admin**，
> 两者不一致 —— 我们只有 `maintain`，所以接口返回 404。
> `/settings` 页对 maintain 同样 404，**About 齿轮是唯一入口**。

### 4.1.1 收录周期（别提前催）

bot 在收录 issue 里的原话：

> if your repo didn't appear in more than **60 minutes** (this countdown starts after
> you complied *ALL OF THE REQUIREMENTS* including repository description),
> please file an issue to let us know.

条件只有两条：**描述非空** + **至少一个 release**。
两项齐了才开始算 **60 分钟** —— 期间查模块页是 404 属正常，超过才去
`Xposed-Modules-Repo/submission` 开 issue。

### 4.2 模块仓库的文件结构

只放这四个，**不放源码**。四个都由 `publish.py` 自动同步，**不要在网页上直接改** ——
改了会被下一次同步覆盖：

| 模块仓库里的文件 | 母本（源码仓库） | 内容 |
|---|---|---|
| `README.md` | `README.md` | 与源码仓库**逐字一致** |
| `SUMMARY` | **`xposed-repo/SUMMARY`** | 模块页标题下面那行**一句话简介** |
| `SOURCE_URL` | 由 `publish.py` 按源码仓库地址生成 | `https://github.com/SansKritO4/GSAuxiliary` |
| `icon.svg` | `docs/icon.svg` | 模块图标，与 APK 内 launcher 图标同源 |

> ⚠️ **`SUMMARY` 不是名字**。模块的**显示名**来自仓库 Description（见 4.1），
> `SUMMARY` 只是副标题那一行简介。早期曾把模块名写进去，等于显示了两遍。
> 它也**不能写注释** —— 文件内容会原样显示。

---

## 5. 几个已经踩过的坑

- **README 里的相对链接**：GitHub 只在**仓库首页**服务端重写相对链接。
  写 `../../releases/latest` 会正确变成 `/owner/repo/releases/latest`；
  写成 `/releases/latest`（前导斜杠）会被当成**域名根** → `github.com/releases/latest` → **404**。
  用 API 的 markdown 渲染端点核对会被误导（那个上下文**不重写**）。
- **`raw.githubusercontent.com` 有 CDN 缓存（最长 5 分钟）**，
  别用它判断「远端同步成功没有」—— 用 contents API 或查 commits。
- **在途开发期间别跑 `publish.py`** —— 它按 `build.gradle.kts` 的当前值建 Release，
  会给一个还没完工的版本建出假 Release。
- 模块仓库的 `README.md` 若引用 `docs/` 下的文件，会变成**坏图 / 死链** ——
  那边没有 `docs/`。logo 因此用**绝对 raw 地址**引用，两个仓库都能显示。
- **签名会「静默」退回 debug**：`app/build.gradle.kts` 判定 `hasEnvKeystore` 要求
  四个 `GS_KEYSTORE_*` **全部**存在且文件真的在。少一个就安静地走 debug 签名分支，
  构建照样成功、包照样能装 —— 只是签名和以前不一样。
  CI 上靠 `--reject-debug-cert` 拦，本机靠发版清单里那条命令。
- **`publish.py` 是「先源码、后模块」两步**：token 对模块仓库没有写权限时，
  源码仓库那份 Release 已经建好了、用户却收不到。所以有了 `publish.py --check`，
  以及 `release.yml` 里那一步前置检查 —— 发之前先问一句「到底能不能写」。
- **平台目录名不等于元数据里的版本号**：官方 SDK 包里遇到过目录叫 `android-37.2`、
  `package.xml` 里却写 `37.0` 的。`setup-sdk37.sh` 因此**不按目录名猜版本**，
  而是把所有 `37.x` 一律压成 `37`（见 `docs/BUILD.md` §8.1）。

