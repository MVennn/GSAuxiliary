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

```markdown
**一句话说清这版是什么** —— 功能开关默认全部关闭，打开模块 App 到「功能」页自选。

## 本版内容

- **新增「XX」**：一句话说清用户能做什么。
- 修复：一句话说清什么问题不再出现。

## 注意

- 功能从「关」改成「开」后，需要**重启一次金太阳**才生效。

---

需要 root + LSPosed（支持 API 102），适配国信金太阳 8.6.0 / Android 8.1+。
```

写完 `docs/release-notes/<vn>.md`、把 `versionCode` / `versionName` 递增、
推一个同名的 tag（`<versionCode>-<versionName>`），CI 就会自动发版。

> 注意：`docs/**` 在 CI 的 `paths-ignore` 里 —— 改说明文件本身不会触发构建，
> 但**打 tag 一定会触发 Release 工作流**（tag 事件不受 `paths-ignore` 影响）。
