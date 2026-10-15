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

- **同 tag**：`{versionCode}-{versionName}`（例：`33-2.13.0`）
- **同 APK**：资产名 `GSAuxiliary_{versionCode}-{versionName}.apk`
- **同 Release 说明**
- 模块仓库的 `README.md` 与源码仓库**逐字一致**

---

## 1. 发版

```bash
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
| 3 | **源码仓库**建/更新 Release + 传 APK |
| 4 | **模块仓库**建/更新 Release + 传 APK |
| 5 | 把源码仓库的 `README.md` 推到模块仓库 |

可选参数：

```bash
python tools/publish.py --skip-build          # 用现有 APK，不重新构建
python tools/publish.py --readme-only         # 只同步 README，不发 Release
python tools/publish.py --dry-run             # 只打印，不写任何东西
```

凭据从 `git credential fill` 取（本机 Windows 凭据管理器里已存的 GitHub PAT，
需要 `repo` scope）。**token 只在内存里用，不落盘、不打印。**

---

## 2. Release 说明要短、面向用户

- **CHANGELOG 是开发者记录**（症状 / 根因 / 改法 / 验证结果、crash 栈、验证表格），
  **不要**把它整段当 Release 说明 —— 用户看到的是噪音。
- Release 说明控制在 **几百字符**：`本版内容` 两三条 + `注意`（会踩的坑）。
- 用法：把短说明写成 Markdown 文件，传 `--notes`。
- 不传 `--notes` 时脚本会退回 CHANGELOG 的对应段落，**并主动警告**。

参考形状：

```markdown
**首次公开发布** —— 功能开关默认全部关闭，打开模块 App 到「功能」页自选。

## 本版内容

- **新增「XX」**：一句话说清用户能做什么。
- 修复：一句话说清什么问题不再出现。

## 注意

- 功能从「关」改成「开」后，需要**重启一次金太阳**才生效。
- （有破坏性变更时写在这里，例如包名变动要重装）

---

需要 root + LSPosed（支持 API 102），适配国信金太阳 8.6.0 / Android 8.1+。
安装步骤见 [README](https://github.com/SansKritO4/GSAuxiliary#安装)。
```

---

## 3. 发版前检查

- [ ] `app/build.gradle.kts` 的 `versionCode` / `versionName` 已递增
- [ ] `CHANGELOG.md` 顶部已加该版本的段落
- [ ] `./gradlew assembleDebug assembleRelease` 通过
- [ ] `aapt2 dump badging <apk>` 的 `versionName` / `versionCode` 正确
- [ ] release dex 里 `Lio/github/libxposed/api` 定义类为 **0**（红线）
- [ ] manifest 无 legacy `xposed*` meta-data（**别用 `grep -i xposed`**，见下）
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

### 4.1 仓库描述（**必须浏览器手动**）

描述字段同时是模块在 LSPosed 网站与 App 里的**显示名**。为空 ⇒ **模块不会出现在
modules.lsposed.org**。

1. 打开 https://github.com/Xposed-Modules-Repo/io.github.SansKritO4.GSAuxiliary
2. 右侧 **About** 区块 → **齿轮 ⚙**
3. 填 **Description**（建议 `GSAuxiliary — 国信金太阳 体验增强`）
4. **Save changes**

> ⚠️ **REST API 改不了**：GitHub 权限矩阵里 `Edit a repository's description` 对
> **Maintain 是允许的**，但 `PATCH /repos/{owner}/{repo}` 这个端点要求 **Admin**，
> 两者不一致 —— 我们只有 `maintain`，所以接口返回 404。
> `/settings` 页对 maintain 同样 404，**About 齿轮是唯一入口**。

设好后 **5～60 分钟**收录，期间查
`https://modules.lsposed.org/module/io.github.SansKritO4.GSAuxiliary` 仍是 404，属正常。

### 4.2 模块仓库的文件结构

只放这四个，**不放源码**：

| 文件 | 内容 |
|---|---|
| `SUMMARY` | 模块显示名，一行一种语言 |
| `README.md` | 与源码仓库**逐字一致**（由 `publish.py` 自动同步） |
| `SOURCE_URL` | 源码仓库地址 |
| `icon.svg` | 模块图标（由 `tools/gen_launcher_icons.py` 生成，与 APK 内 launcher 图标同源） |

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
