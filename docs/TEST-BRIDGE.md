# 通用测试桥（TBridge）设计稿

> 目标：把「取控件树 + 注入点击 + 取像素」从跨进程的慢路径（uiautomator 2.0s / screencap 0.28s）
> 降到**进程内**的毫秒级，并且对**任意 App 通用**，不绑定某一个被测模块。
> 状态：设计已踩点，地基已实测验证；代码未落地。

## 1. 为什么必须做成独立模块

把 hook 塞进 GSAuxiliary 只能测金太阳。后续要测的 App 各不相同，正确的切分是：

| 层 | 归属 | 变化频率 |
|---|---|---|
| 桥（hook + socket + 协议） | **独立 LSPosed 模块 `TBridge`** | 一次做好，长期不动 |
| 被测 App 的 scope 勾选 | LSPosed Manager / 脚本 | 每接一个新 App 一次 |
| 测试编排与断言 | 各项目自己的 `drive.sh` / `verify.py` | 跟着业务走 |

桥与被测模块**互不感知**：TBridge 不依赖 GSAuxiliary，GSAuxiliary 也不知道 TBridge 存在。
两者同时注入同一进程，各 hook 各的，互不冲突（LSPosed 允许同进程多模块）。

## 2. 已实测验证的地基

| 事实 | 证据 |
|---|---|
| MuMu 内核支持 abstract unix socket | `/proc/net/unix` 里有 `@lspbridge-a955aa2e-…`、`@jdwp-control` |
| `adb forward tcp:N localabstract:NAME` 可直连 abstract socket | `adb forward tcp:27101 localabstract:lspbridge-…` → Python `create_connection` 返回 **CONNECTED** |
| LSPosed 本身就用这条通道做跨进程通信 | `@lspbridge-*` 是 lspd 的 bridge |
| MuMu 的 abilist 含 arm64-v8a + libhoudini | 见 `docs/REVERSE-ENGINEERING.md` 与 adb `getprop ro.product.cpu.abilist` |
| 设备可 `adb root`（uid=0, u:r:su:s0） | 已验 |

结论：**通道不用自己造，Android 原生就有，且 LSPosed 已经在用。** 剩下的是协议和 hook。

## 3. 架构

```
PC 侧 (Python)                         设备侧 (目标 App 进程内)
┌──────────────────┐                  ┌────────────────────────────┐
│ tbridge.py       │  adb forward     │ TBridge (LSPosed 模块)      │
│  ├ ensure_forward│ ───────────────▶ │  ├ hook ViewRootImpl.setView│
│  ├ send(op)      │ tcp:27100        │  │   → 持有 DecorView 弱引用 │
│  └ recv(json)    │ localabstract:   │  ├ 主线程 handler           │
└──────────────────┘ tb.<package>     │  └ ServerSocket(port).accept│
                                       └────────────────────────────┘
```

**为什么是 abstract socket 而不是 tcp 端口**：设备侧 bind 任意 tcp 端口需要额外权限且可能与
App 自身端口冲突；abstract socket 在 `AF_UNIX` 命名空间里，按包名唯一，天生无冲突，
而且 `adb forward` 原生支持 `localabstract:`。

**命名规则**：`tb.<package>`（主进程）、`tb.<package>:<processSuffix>`（子进程，可选）。
PC 侧先 `adb shell pidof <package>` 确认活着，再 `adb forward tcp:27100 localabstract:tb.<package>`。

## 4. 协议

一行一条 JSON，请求-响应，长连接复用（建立连接约 30ms，之后每条指令 **1–5ms**）。

| op | 请求 | 响应 |
|---|---|---|
| `ping` | `{"op":"ping"}` | `{"ok":true,"pkg":"...","pid":1234,"ver":"1.0"}` |
| `act` | `{"op":"act"}` | `{"ok":true,"act":"com.x.LoginAct","resumed":true}` |
| `tree` | `{"op":"tree"}` | `{"ok":true,"nodes":[...],"ms":7}` |
| `find` | `{"op":"find","text":"登录","exact":false}` | `{"ok":true,"hits":[{"idx":42,"bounds":[45,751,855,819],"cls":"Button","text":"登录"}]}` |
| `tap` | `{"op":"tap","idx":42}` 或 `{"op":"tap","text":"登录"}` | `{"ok":true,"hit":true,"via":"dispatch"}` |
| `settext` | `{"op":"settext","idx":7,"text":"13800138000"}` | `{"ok":true}` |
| `shot` | `{"op":"shot","fmt":"png"}` | `{"ok":true,"b64":"...","w":900,"h":1600}` |
| `shot` | `{"op":"shot","fmt":"raw","path":"/data/local/tmp/tb.raw"}` | `{"ok":true,"bytes":5760000,"path":"..."}` |
| `watch` | `{"op":"watch"}` | 流式推送 `{"ev":"resume","act":"..."}` / `{"ev":"click","idx":..}` |

节点结构（与 `uiautomator` 的 XML 字段对齐，便于复用现有 `uidump.py` 解析器）：

```json
{"idx":42,"cls":"android.widget.Button","text":"登录","rid":"com.x:id/btn_login",
 "pkg":"com.x","desc":"","bounds":[45,751,855,819],"clickable":true,
 "enabled":true,"visible":true,"checked":false,"scrollX":0,"scrollY":0,"depth":6}
```

`bounds` 是**屏幕坐标**（`getLocationOnScreen` + `getWidth/Height`），与 `input tap` 的坐标系一致。

## 5. Hook 点（最小集，四个）

```kotlin
// 1) 拿到当前窗口根 View —— 每次 Activity 挂窗口时触发
XposedHelpers.findAndHookMethod(
    "android.view.ViewRootImpl", lpparam.classLoader, "setView",
    View::class.java, ViewGroup.LayoutParams::class.java, ViewRootImpl::class.java,
    object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            TBridge.currentRoot = WeakReference(p.args[0] as View)
            TBridge.currentActivityTid = ...
        }
    })

// 2) 追踪前台 Activity 名
XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
    "onResume", object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            TBridge.currentAct = p.thisObject.javaClass.name
        }
    })

// 3) 进程启动时装 socket server（只在主进程装）
XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader,
    "attach", Context::class.java, object : XC_MethodHook() {
        override fun afterHookedMethod(p: MethodHookParam) {
            val ctx = p.args[0] as Context
            if (ctx.packageName == lpparam.packageName) TBridge.startServer(ctx)
        }
    })

// 4) 触摸事件走这里注入（可选：替代 performClick，能点无监听器的 View）
//    dispatchTouchEvent 直接调 View 层，不进 InputDispatcher，不受坐标漂移影响
```

## 6. 点击的两条路（自动 fallback）

| 路径 | 做法 | 覆盖 | 失败时 |
|---|---|---|---|
| `dispatch` | 构造 `MotionEvent.obtain(downTime, eventTime, ACTION_DOWN/UP, x, y, 0)` 直接 `view.dispatchTouchEvent` | 绝大多数原生 View、自绘 View | — |
| `click` | `view.performClick()` | 有 `OnClickListener` 的 View | 无监听器时返回 false |
| `input` | 回退到 PC 侧 `input tap x y` | 游戏引擎、SurfaceView、WebView 内元素 | **永远可用的兜底** |

坐标用**视图本地坐标**（`tap` 时把中心点换算成 `view` 的相对坐标），不依赖屏幕分辨率，
换机型不用重取坐标 —— 这是相对 `input tap` 的关键优势。

## 7. 像素

```kotlin
val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
v.draw(Canvas(bmp))   // 主线程
```

- 优点：不受弹窗遮挡、不受录屏编码、拿的是**本 App 视图真实渲染结果**
- 缺点：`SurfaceView` / `TextureView` / 游戏画面**画不出来**（黑屏）→ 这类回退 `screencap`
- 实测 uiautomator 2.0s vs 这条路径预期 5–15ms（主线程一轮 traversal）

## 8. 接入一个新 App

```bash
# 一次性：把目标包名加进 TBridge 的 scope
python tools/tbridge.py scope-add com.example.app

# 之后每个 App 一样
python tools/tbridge.py -p com.example.app tree
python tools/tbridge.py -p com.example.app tap --text 登录
```

`scope-add` 的实现：设备没有 `sqlite3`，走「pull `modules_config.db` → PC 侧 sqlite3 改 → push 回 →
`kill -9` 目标进程」三步。这是唯一需要 root 的操作，其余全走 socket。

> [待补] `modules_config.db` 的表结构还没读出来：直接 pull 得到 77,824 字节但
> `sqlite_master` 为空 —— 数据在 WAL 里，需要连 `-wal` / `-shm` 一起拉（本轮 cp 因路径问题失败）。
> 落地时代码里用 `LSPosed` 的 Manager 接口或直接读 WAL 解决。

## 9. 边界与已知坑

| 场景 | 结果 | 兜底 |
|---|---|---|
| 加固 App（梆梆/爱加密） | 模块能加载（Zygisk 注入 zygote fork），但可能被反注入检测 | 金太阳已弹「环境存在风险」，说明检测在跑；必要时加 hook 屏蔽 |
| 多进程 App | 只有有 Activity 的进程有 View 树；子进程 socket 名加后缀 | 按 `act` 结果选进程 |
| `WebView` 内元素 | View 树只看到一个 `WebView` 节点，里面点不到 | hook `WebView` 走 `evaluateJavascript` 单独开 op |
| 游戏 / Flutter / RN | 单 `SurfaceView` 或自绘，无 View 树 | 回退 `input tap` + 截图比对 |
| 主线程阻塞 | 序列化在主线程，若主线程卡住会超时 | 请求带 `timeout_ms`，超时回 `{"ok":false,"err":"main_blocked"}` |
| 系统 App | 起 socket 可能被 SELinux 拦 | 用 `/data/local/tmp` 下 unix socket 文件路径 |

**一句判断**：View 树能覆盖原生 App 的绝大多数界面，覆盖不到的场景（游戏、WebView 内、
Flutter）本来 `uiautomator` 也覆盖不了，所以这不是退化，是平移 + 大幅提速。

## 10. 工作量

| 件 | 内容 | 估 |
|---|---|---|
| `TBridge` 模块 | Kotlin，hook 4 处 + 序列化 + socket loop | ~450 行 |
| 协议客户端 | Python，`tbridge.py`（forward / 连 / 发 / 收 / 重试） | ~200 行 |
| scope 管理 | db 读写 + 进程重启 | ~80 行 |
| `drive.sh` 接入 | 新增 `tb` 指令族，失败自动回退旧路径 | ~60 行 |

合计约 800 行，两天量级。**收益**：单步交互 2.0s → 5ms（400×），15.8s 的登录链路可压到 3s 内；
且坐标与机型解耦，回归脚本不再因 UI 微调而失效。

## 11. 与现有 drive.sh 的关系

不替换，只加速。`drive.sh` 保留全部现有指令作为兜底，新增：

```
tb tree              走 TBridge 取视图树（失败自动回退 uiscan）
tb find TEXT         走 TBridge 找控件
tb tap TEXT          走 TBridge 点击（失败回退 uifind）
tb shot NAME         走 TBridge 取像素（SurfaceView 场景自动回退 screencap）
tb act               走 TBridge 取 Activity（失败回退 dumpsys）
```

这样 TBridge 没装 / 没勾 scope / 崩了，现有链路照常工作，不会因为桥挂了导致测试全红。
