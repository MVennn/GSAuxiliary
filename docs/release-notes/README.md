# release-notes/

放**面向用户**的 Release 说明，文件名 = `versionName`：

```
docs/release-notes/2.14.0.md
```

`.github/workflows/release.yml` 发版时会找 `docs/release-notes/<versionName>.md`，
找到就用它当 Release 说明；找不到就退回 CHANGELOG 的对应段落（并在日志里
`::warning::` —— 那段是写给开发者的，用户看到的是噪音）。

## 为什么要单独放一份

`CHANGELOG.md` 是**开发者记录**：症状 / 根因 / 改法 / 验证结果 / crash 栈 / 验证表格。
Release 说明是**给用户看的**，要短、要讲「我现在能干什么」。两者受众完全不同，
不能一份两用（见 `docs/RELEASE.md` §2）。

## 形状

只说**这版做了什么**，别的都不写。

```markdown
**新增「手势导航条同色」。**

## 本版内容

- 屏幕底部那条小白条现在会填充与紧邻界面一致的颜色，不再是一条纯黑或纯白。开关在「功能 → 显示」里。

---

需要 root + LSPosed（支持 API 102），适配国信金太阳 8.6.0 / Android 8.1+。
安装步骤见 [README](https://github.com/MVennn/GSAuxiliary#安装)。
```

- 开头一句加粗说清这版是什么；`## 本版内容` 下每条一句话；
  结尾那两行是**固定尾巴**，每版照抄不动。
- 篇幅：**几句话**。写长了说明你在解释 —— 而解释属于 CHANGELOG。
- 说人话。「不做沉浸」「窗口属性」「强制对比度」这类只对开发者有意义的词别写进来；
  真要表达限制，写用户看得见的后果（「上方是渐变时交界处可能有一点色差」）。

## 别写这些

下面几样以前每版都写，属于凑数，现在一律不写：

| 别写 | 为什么 |
|---|---|
| 「之前是什么样」的背景 / 对比 | 用户不关心历史，只关心现在 |
| 实现原理、限制条件（怎么取色、为什么不做沉浸） | 那是 CHANGELOG 的事 |
| 开关怎么用、默认开还是关、改完要不要重启 | 通用信息，不随版本变；要写就写进 README |
| 「本版没有破坏性变更，直接覆盖安装」 | 没变更就不用声明；**有**的时候才必须写 |
| 凑数的「注意」区块 | 见下 |

## 「注意」区块：能不写就不写

只在**用户不知道就会出问题**时才写，最多一两条：

- ✅ **有破坏性变更** —— 包名变了、要卸载重装、配置会丢。**必须写**。
- ✅ **已知的、会被当成新 bug 的问题** —— 例如某开关实际只做到一半。如实写一条，
  免得被重复报（2.15.1 就这么做过）。
- ❌ 每版都一样的操作提醒、免责声明、性能说明 —— 不写。

## 改已经发出去的说明

`publish.py` 对已存在的 Release 走 `PATCH` 更新，同名 APK 会先删再传，不会报
`already_exists`。改完文案直接重跑一次就覆盖线上了：

```bash
python tools/publish.py --skip-build --notes docs/release-notes/<vn>.md
```

写完 `docs/release-notes/<vn>.md`、把 `versionCode` / `versionName` 递增、
推一个同名的 tag（`<versionCode>-<versionName>`），CI 就会自动发版。

> 注意：`docs/**` 在 CI 的 `paths-ignore` 里 —— 改说明文件本身不会触发构建，
> 但**打 tag 一定会触发 Release 工作流**（tag 事件不受 `paths-ignore` 影响）。
