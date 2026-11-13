# SECRETS —— 凭据与「什么不能进仓」

> **一句话**：本仓是 **PUBLIC**，任何被 git 跟踪的内容等于已经公开。凭据只许留在本机忽略文件或
> 环境变量里，仓库里只放留空的模板；三道防线负责别让它在某次提交里失手。

---

## 一、凭据清单

| 凭据 | 本机存放 | 环境变量 | 入库 |
|---|---|---|---|
| 金太阳登录账号 / 密码（真机自动化用） | `tools/login.local.env` | `GS_USER` / `GS_PASS` | **否**，`.gitignore` 的 `/tools/login.local.env` |
| release 签名 keystore | `*.keystore` / `*.jks` | — | **否**，`*.keystore` 已被忽略 |
| keystore 口令 | `keystore.properties` | `GS_KEYSTORE_*`（见 docs/BUILD.md） | **否** |
| 无线调试设备地址 | 本机 shell | `ADB_WIFI_HOST` | 不入库，脚本里只留占位符 |
| GitHub Actions secrets | GitHub 仓库设置 | 由 CI 注入 | 不落盘 |

进仓的一律是**留空模板**：`tools/login.example.env`、`keystore.properties.example`。

## 二、三道防线

1. **`.gitignore`**：`/tools/login.local.env`、`*.keystore`、`*.jks`、`keystore.properties`、`*.p12` / `*.pem` / `*.key`。
   `git status` 与 `git add -A` 都看不到它们。
2. **`tools/check-secrets.py`**：
   - 扫已跟踪文件（`--all` 时扫工作树），模式覆盖私钥块、各类 token、shadow 哈希、代理节点 URI、订阅 token、本机用户目录、真实邮箱；
   - 另加一条**铁律**：`tools/login.local.env` 一旦进入 git 索引就判失败 —— `.gitignore` 只拦「未跟踪」，
     `git add -f` 能绕过，这条专门堵那个口子（本仓公开，绕过即等于公开）。
3. **脚本侧**：`tools/login.sh` 的日志只打账号与密码**位数**（`密码 = ******（6 位）`）；
   控件树 dump 落在 `.work/`（已忽略），密码框是自绘 `View`，dump 里本来就读不到内容。

## 三、误推之后的处置

1. `git rm --cached <文件>` —— 只从索引移除，本机文件保留；
2. **立刻改密码**：仓库公开且已经推到远端，此时等价于泄漏；清历史不改变已扩散的事实；
3. 只有私有仓才值得清历史（`git filter-repo --invert-paths --path <文件>` + 强推），且仍需改密码。

## 四、自查

```bash
python tools/check-secrets.py                                       # 期望 exit 0
git add -f tools/login.local.env && python tools/check-secrets.py    # 期望 exit 1（守护生效）
git reset -- tools/login.local.env                                  # 恢复索引
```

2026-11-13 实测：正常 `exit 0`；`git add -f` 后 `exit 1` 并打印"本地凭据文件已被 git 跟踪"；`git reset` 后回到 `exit 0`。
