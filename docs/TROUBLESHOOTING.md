# 排错手册

按「现象 → 排查步骤 → 原因/解法」组织。

**第一步永远是看 LSPosed 日志。**

---

## 0. 先学会看日志

**被注入到目标 App 的那部分**日志不在 logcat 里：

```bash
adb shell su -c "grep GSZXEnhance /data/adb/lspd/log/modules_*.log | tail -30"
```

**模块 App 自己进程**（设置界面）的日志在 logcat 里：

```bash
adb logcat -s GSZXEnhance
```

> 这是最容易浪费时间的误区：`logcat -s GSZXEnhance` 看不到目标进程的日志，
> 因为 `XposedInterface.log()` 被 LSPosed 收进了自己的日志文件。

正常加载时应该看到：

```
GSZXEnhance: === GSAuxiliary v2.6.0 载入 === process=com.guosen.android pid=12345 framework=LSPosed 1.9.x api=102 caps=0x…
GSZXEnhance: 配置通道 = remote preferences -> ConfigSnapshot(enabled=true, color=-32768, scopeStrategy=true, logLevel=2)
GSZXEnhance: 已安装功能 [watchlist_highlight] 自选股高亮
GSZXEnhance: 已挂 com.guosen.commonui.adapter.BaseQuickAdapter.getView ×1
GSZXEnhance: 已挂 com.guosen.quotation.adapter.aistk.AIStkAdapter.getView ×1
GSZXEnhance: 已挂 android.app.Activity.onResume
GSZXEnhance: 功能安装完成：1/1，共 3 个钩子
```

| 缺哪一行 | 说明 |
|---|---|
| 第 1 行 | 模块没被注入 → 检查 LSPosed 是否勾选模块、作用域是否包含国信金太阳 |
| 「配置通道」 | 框架不支持 remote 能力，或 remote prefs 打不开（见第 3 节） |
| 「已挂 …getView」 | 宿主类名变了 → 用 `tools/probe/` 重新定位 |
| 「已安装功能」 | 当前配置下功能未启用（开关关了） |

---

## 1. 模块完全没生效

**排查**

```bash
# 1) 确认模块被 LSPosed 认识
adb shell su -c "grep -i gszx /data/adb/lspd/log/modules_*.log | head -5"

# 2) 确认配置写进去了（adb 调试通道，仅 root/shell 可用）
adb shell su -c "content query --uri content://gsauxiliary.m.venn.config/config"
```

也可以直接看设置界面 **「模块」Tab**：它会显示**框架连接状态**和**配置通道**。

**常见原因**

| 原因 | 解法 |
|---|---|
| LSPosed 里没勾选模块 | 勾选后**强制停止**目标 App 再打开 |
| LSPosed 版本太旧（不支持 API 102） | 升级 LSPosed |
| 作用域没勾「国信金太阳」 | 模块已固定声明作用域，正常会自动勾；异常时手动勾 |
| 装了新版本但没重启 App | 首次启用必须重启目标 App（之后升级模块可热重载） |
| 换了签名 | 卸载重装，并在 LSPosed 里重新勾选 |
| App 升级后类名变了 | 见 [第 6 节](#6-app-升级后失效) |

---

## 2. 模块加载了，但策略详情页没有黄名

**排查顺序**

1. 确认当前确实在**策略详情页**（`AIStkStgDetailAct`）：
   ```bash
   bash tools/drive.sh "act"
   ```
2. 确认开关是开的：
   ```bash
   bash tools/sh.sh "content query --uri content://gsauxiliary.m.venn.config/config"
   # enabled=1 才对
   ```
3. 用自动校验脚本看实际颜色：
   ```bash
   python tools/verify.py
   ```

**可能原因**

| 现象 | 原因 | 解法 |
|---|---|---|
| 全部普通色，`enabled=0` | 开关被关了 | 打开设置界面重新开启 |
| 全部普通色，`enabled=1` | 数据项字段名变了，取不到股票代码 | 往 `WatchlistTargets.CAM_FIELDS` 等数组里加候选字段名 |
| 全部普通色 | 适配器类名变了 | 用探针重新定位，改 `WatchlistTargets.TARGET_ADAPTERS` |
| 全部普通色，日志里有「没有任何钩子装上」 | `BaseQuickAdapter` 类找不到 | 用探针确认类名，改 `WatchlistTargets.CLS_BASE_QUICK_ADAPTER` |
| 只有部分行变色 | 列表没重绘 | 滚动一下，或退出重进页面 |
| 名称找不到 | 控件 id 变了 | 改 `WatchlistTargets.NAME_ID_CANDIDATES` |

---

## 3. 设置里改了开关，但 App 里没反应

配置走框架的 remote preferences，正常情况下是**实时推送**的。
没反应基本只有两类原因。

**排查**

```bash
# 1) 看配置写没写进去
bash tools/sh.sh "content query --uri content://gsauxiliary.m.venn.config/config"

# 2) 看目标进程的配置通道状态
bash tools/sh.sh --root "grep '配置通道' /data/adb/lspd/log/modules_*.log | tail -3"

# 3) 看有没有收到推送
bash tools/sh.sh --root "grep '配置已更新' /data/adb/lspd/log/modules_*.log | tail -3"
```

### 原因 A：框架未连接（配置后端 = LOCAL）

设置界面「模块」Tab 会显示 **配置通道 = 本地暂存（框架未连接）**。

含义：模块 App 拿不到 LSPosed 服务的 binder，改动只写在本地，
**目标进程看不到**。

| 检查 | 解法 |
|---|---|
| LSPosed 里没勾选模块 | 勾选（勾选后框架才会 bind 服务） |
| 框架版本太旧 | 升级 LSPosed |
| 服务绑定失败 | 看 logcat 里 `注册框架服务监听失败` 的堆栈 |

> 框架一连上，`ModulePreferences` 会**自动把本地暂存的值一次性迁移到远端**，
> 用户不会丢配置。日志里会出现 `已把本地暂存的 N 项配置迁移到 remote preferences`。

### 原因 B：框架声明了 remote 能力但打不开

日志里会出现：

```
GSZXEnhance: 配置通道 = remote preferences 不可用: …
```

或

```
GSZXEnhance: 框架未声明 remote preferences 能力 (PROP_CAP_REMOTE=0)
```

| 原因 | 解法 |
|---|---|
| LSPosed 版本太旧，不提供 remote preferences | 升级 LSPosed（需要支持 API 102） |
| 模块被识别成 legacy 模块 | 删掉 manifest 里的 `xposedminversion`（见第 7 节） |

> 通道打不开会**明确记日志**，不会静默失败 —— 这也是设置界面要显示
> 「配置通道」的原因。

---

## 4. 关掉开关后，已经变黄的行还是黄的

**原因**：RecyclerView 复用视图，黄颜色残留在 `TextView` 上。

**解法**：钩子里**不能提前 return**。无论开关开还是关都要走一遍染色逻辑，
关闭时用"原始色"覆盖回去。代码里已经这样处理（`WatchlistFeature.onGetView`）。

如果仍然残留：

- 滚动一下列表，或退出重进页面（触发重新绑定）
- 确认 `enabled=0` 真的写进去了

---

## 5. 校验脚本报错

| 报错 | 原因 | 解法 |
|---|---|---|
| `找不到 adb` | 没探测到 SDK | 设 `ANDROID_SDK` 或 `ADB` 环境变量 |
| `找不到 ffmpeg` | ffmpeg 不在 PATH | 设 `FFMPEG` 环境变量 |
| `没解析到股票行` | 当前不在策略详情页 | 先导航过去 |
| `ffmpeg 输出不足` | 截图尺寸判断错误 | 用 `--size` 指定，或检查 `wm size` |

`tools/verify.py` 会自动从 `ANDROID_SDK` / `ANDROID_HOME` / `LOCALAPPDATA` 探测 adb，
并从 PATH 探测 ffmpeg，一般不需要手动配置。

---

## 6. App 升级后失效

按这个顺序改，**大概率只需要改一个常量**：

| 步骤 | 检查什么 | 改哪里 |
|---|---|---|
| 1 | 看 LSPosed 日志，钩子有没有装上 | — |
| 2 | 适配器类名 | `feature/watchlist/WatchlistTargets.kt` → `TARGET_ADAPTERS` / `CLS_BASE_QUICK_ADAPTER` |
| 3 | 名称控件 id | 同上 → `NAME_ID_CANDIDATES` |
| 4 | 数据项里的代码/市场字段名 | 同上 → `CAM_FIELDS` / `CODE_FIELDS` / `MARKET_FIELDS` |
| 5 | 自选判定 API | `feature/watchlist/ZxWatchlist.kt`（兜底是直接查 `ZXManager.m_stkGrpMap`） |

**重新定位类名的方法**：用 `tools/probe/` 里的运行时探针。

```bash
bash tools/probe/build.sh
adb install -r tools/probe/out/GoldSunProbe.apk
# 在 LSPosed 里启用探针，作用域勾国信金太阳
# 然后正常操作 App，探针会把 adapter 类名 / item 字段 / 控件 id 全打到 LSPosed 日志
```

详见 [tools/probe/README.md](../tools/probe/README.md)。

---

## 7. 构建 / 加载失败

### 7.1 构建报错

| 报错 | 原因 | 解法 |
|---|---|---|
| `requires AGP 9.1.0 or higher` | Miuix 版本太高 | 用 0.9.3，别升 |
| `plugin is no longer required since AGP 9.0` | AGP 9.x 内置 Kotlin | 用 AGP 8.10.1 |
| `Failed to find target android-37` | platform 没装 / schema 不对 | 见 [BUILD.md](BUILD.md#31-compilesdk-37-没有正式安装包) |
| `Could not find method ...` | Gradle 版本太低 | 用仓库自带 Wrapper `./gradlew` |
| `Error parsing AndroidManifest.xml` | XML 注释里出现了 `--`（非法） | 注释里不要写 `--` |

### 7.2 模块被 LSPosed 拒绝加载

| 报错 / 现象 | 原因 | 解法 |
|---|---|---|
| 模块完全不加载，日志里连第 1 行都没有 | manifest 里残留 `xposedminversion` | **删掉全部 `xposed*` meta-data**（见 [EXTENDING.md](EXTENDING.md#21-manifest-里绝对不能出现-xposedminversion)） |
| `The Xposed API classes are compiled into the module's APK` | `api` 依赖写成了 `implementation` | 改回 `compileOnly`（见 [BUILD.md §3.3](BUILD.md#33-xposed-api-用-maven-依赖libxposed-api-102)） |
| `NoClassDefFoundError: io.github.libxposed.api.…`（模块 App 进程） | `core/` 或 `app/` 里有人 import 了 api 类型 | 用 `GLog.Sink` 那样的函数式接口解耦 |
| 模块加载了但钩子全没装上 | 现代模块不能调 legacy API | 确认用的是 `hook(Executable).intercept(Chain)` |

**自查：manifest 里到底还有没有 `xposed*`**

```bash
aapt2 dump xmltree --file AndroidManifest.xml app/build/outputs/apk/debug/app-debug.apk | grep -i xposed
# 应无输出
```

**自查：dex 里有没有被打进 api 类**

```bash
unzip -o -q app/build/outputs/apk/debug/app-debug.apk 'classes*.dex' -d /tmp/apkx
dexdump -f /tmp/apkx/classes*.dex | grep "Class descriptor" | grep 'libxposed/api'
# 应无输出
```

> 别用 `strings` 判断 —— 模块自己的类里有 `io/github/libxposed/api/...` 的
> 字符串常量引用，会误报。必须看 dexdump 的 `Class descriptor`。

---

## 8. 热重载相关

模块声明了 `autoHotReload=true`，升级模块 APK 后目标 App 无需重启。

| 现象（点击后弹出的 Toast） | 原因 | 解法 |
|---|---|---|
| 热重载后钩子失效 | `onHotReloaded` 里拿不到宿主 classLoader | 日志会出现「热重载后拿不到宿主 classLoader」→ 重启目标 App |
| 「没有正在运行的目标程序」 | 金太阳没在运行（`runningTargets` 为空） | 先打开一次国信金太阳 |
| 「热重载超时，目标进程可能无响应」 | 8 秒内框架没回 `onHotReloadResult` | 检查 LSPosed 日志，或直接重启目标 App |
| 「热重载失败，详见模块日志」 | 模块 `onHotReloading` 返回 false，或框架侧抛异常 | 看模块日志里那条 `热重载 <进程>(pid)…` 的 `message` |
| 「当前框架不支持热重载」 | 框架 API < 102 或未提供该能力 | 升级 LSPosed / 框架 |
| 「目标进程正在热重载中，请稍候」 | 上一次热重载还没结束就再点了一次 | 等它跑完 |
| 「目标进程已退出」 | 重载过程中金太阳进程被杀 | 重新打开金太阳（模块会重新注入） |

> 2.12.1 起**点一次必有一条 Toast**（成功也会说「已热重载」）。此前只有「没有正在运行的目标程序」
> 会提示，目标进程正常或处于后台时点下去毫无反馈。

手动触发入口：设置界面 → **框架操作 → 热重载目标进程**。

---

## 9. 设备相关（自动化脚本）

| 现象 | 原因 | 解法 |
|---|---|---|
| `no devices/emulators found` | adb server 冷启动后无线设备还没被发现 | 脚本自带 60 秒重试；也可设 `ADB_WIFI_TARGET` |
| 命令报 `device not found` | 中途 adb server 被重启 | 不要并发跑多个 adb 命令 |
| `su -c` 命令被截断 | 内层命令里含单引号 | `--root` 后的命令里不要用单引号，管道放到宿主机侧 |
| `content query` 报 `SecurityException` | adb 调试通道只允许 root/shell | 用 `su -c` 执行 |

---

## 10. 设备息屏导致脚本点不动

自动化脚本跑长序列时，设备可能息屏。开头加：

```bash
bash tools/drive.sh "key 224" "wait 2" "swipe 540 2000 540 900 250" "wait 3" "launch" "wait 12"
```

`key 224` 是 `KEYCODE_WAKEUP`，后面那次 swipe 是上滑解锁。
