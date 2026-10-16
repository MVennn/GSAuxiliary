# 隐藏新股申购提醒：实现说明与校准手册

> 功能 id `ipo_dialog_clean`，代码在
> [`feature/ipoclean/`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/ipoclean/)。
> 用户可见文案与命名遵循 [NAMING.md](NAMING.md)。

---

## 1. 它拦的是什么

「今日新股申购提醒」是登录后由宿主主界面弹出的一层**宽版浮层** ——
铺满屏幕、看着像页面，其实是个 Dialog：

```
MainAct#receiveBroadcast:943              收到引擎广播
  └─ MainBiz#getIPODialog:148             业务层决定「要弹这个」
       └─ EngineModel#setBizCommand:55
            └─ MainAct#showMsgDialog:1282  通用弹窗分发（别的提醒也走它，别动）
                 └─ MainAct#showSgbDialog:1315   申购 / 新股专用那一支
                      └─ PopAlert#createIpoAlertDialog:911
                           └─ IpoAlertDialog.show()      真正显示
```

弹窗本体是 `com.guosen.commonui.dialog.IpoAlertDialog`，
继承链 `IpoAlertDialog → CustomAlertDialog → Dialog`。

> 实测环境：国信金太阳 8.6.0 / versionCode 271 / 魅族 20 / Android 16（2026-10-16）。

---

## 2. 拦在哪、为什么

见 [`IpoCleanTargets.kt`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/ipoclean/IpoCleanTargets.kt)。
两道：

| 位置 | 签名 | 为什么选它 |
|---|---|---|
| `MainAct#showSgbDialog()` | `private void` 无参 | 宿主里「申购」**专用**的展示方法。拦下它，弹窗连构造都不会发生 |
| `android.app.Dialog#show` | `public void` | 所有弹窗的**唯一汇聚点**，按类名判 `IpoAlertDialog` |

**为什么挂 `Dialog#show`，而不是挂 `IpoAlertDialog` 自己的 `show`**：
本项目只能拿到 `declaredMethods`，而子类**未必覆写** `show`（`IpoAlertDialog` 就没覆写）。
挂子类等于赌它覆写了，赌错就**静默失效**；挂基类的汇聚点则一次覆盖两种情况，
也不受中间父类（`CustomAlertDialog`）改动影响。

**为什么拦 `show`，而不是拦构造入口 `PopAlert#createIpoAlertDialog`**：
后者只是「构造」。让它返回 `null`，调用方（`showSgbDialog`）拿到空引用再 `show()` 就是 NPE；
而让 `show` 不执行则干干净净 —— **对象建了、只是没显示**，宿主后续流程一切照旧。

**`MainAct#showMsgDialog()` 不能碰**：它是通用弹窗分发，其它业务提醒也走那条路。

判据刻意收得很紧（见 `isIpoDialog`：全名或简名任一命中才算），
因为 `Dialog#show` 这一道对**所有** Dialog 生效 —— 宁可漏掉这一个弹窗，
也绝不能误伤宿主的其它对话框。

---

## 3. 认不出来时（真机校准手册）

宿主升级后如果弹窗又出现了，按顺序查：

**第一步：看模块日志**（目标进程的日志在 `/data/adb/lspd/log/modules_*.log`）。
正常应该看到这两行之一或全部：

```
ipo_dialog_clean：已挂 2 个钩子
ipo_dialog_clean：已拦下宿主展示方法 showSgbDialog
```

**第二步：对症下药**

| 现象 | 原因 | 改哪里 |
|---|---|---|
| 完全没有 `ipo_dialog_clean` 字样 | 功能开关没开 | 功能页打开「隐藏新股申购提醒」 |
| 有「已挂 N 个钩子」但弹窗照旧 | 类名 / 方法名变了 | 见下 |
| `找不到 …` 的警告 | 相关的类加载失败 | 见下 |

- **弹窗类换了名字** → 往 `IpoCleanTargets.IPO_DIALOG_CLASSES`（全名）
  或 `IPO_DIALOG_SIMPLE_NAMES`（简名）加候选；
- **宿主展示方法改名了** → 往 `SHOW_METHODS` 加候选；
- **整条链都变了** → 用探针跑一次（[probe/README.md](../tools/probe/README.md)）：
  它会给 `Dialog.show` 和 `PopupWindow.show*` 打**调用栈**，
  一次就能拿到「谁弹的 + 弹的是哪个类 + 视图树」，照着改常量即可。

---

## 4. 已知边界

- **只拦「自动弹」这一条路**。首页快捷入口里的「申购宝」不受影响 ——
  用户主动点进去仍然正常。
- 拦下之后宿主**不知道**弹窗没显示（我们只是没执行 `show`）。
  目前宿主没有「弹窗关闭后才记账」的逻辑，所以没有副作用；
  若哪天加了这种逻辑，需要重新评估。
