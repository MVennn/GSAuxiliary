<div align="center">

# GSAuxiliary

**国信金太阳 · 体验增强模块**
**GoldenSun experience enhancement module**

Make GoldenSun Great Again！

针对国信金太阳（`com.guosen.android`）的 LSPosed 模块。
不改目标 App 的数据、不动服务端，只在本机对界面显示与页面交互做增补。

An LSPosed module for GoldenSun (`com.guosen.android`).
It changes no data and touches no server — it only augments UI presentation and
page interaction, locally on your own device.

![Android](https://img.shields.io/badge/Android-8.1%2B-3DDC84?logo=android&logoColor=white)
![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-5C6BC0)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)
![Version](https://img.shields.io/badge/version-2.12.1-blue)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)

[中文](#中文) · [English](#english) · [项目结构 / Structure](#项目结构--project-structure) · [鸣谢 / Credits](#开源鸣谢--credits)

</div>

---

<a id="中文"></a>

## 中文

### 安装

**前置条件**

- 手机已 root，并已安装 [LSPosed](https://github.com/LSPosed/LSPosed)（需支持 **API 102**）
- 目标 App：**国信金太阳 8.6.0**（`com.guosen.android`）
- Android 8.1+（`minSdk 27`）

**步骤**

1. 从本仓库的 **Releases** 页面下载 `app-release.apk` 并安装（或自行构建，见[构建](#构建)）
2. 打开 LSPosed 管理器 → **模块** → 勾选 **GSAuxiliary**
3. **作用域**勾选 `国信金太阳`（模块已固定声明作用域，正常会自动勾上）
4. 强制停止国信金太阳，重新打开一次
5. 打开 **GSAuxiliary** App，切到「功能」页，按需打开开关

首次启用后需要重启一次目标 App —— LSPosed 在**进程启动时**加载模块。
之后再改设置、再升级模块 APK，都不需要重启金太阳。

### 默认行为

**功能开关出厂全部关闭。**
功能是侵入目标 App 行为的增强，装完模块不代表你同意被改 —— 用哪个开哪个，这是刻意的默认值。
关闭开关后目标 App 的行为与外观立即还原，不留残留。

具体功能与用法都在模块的设置界面内：每个功能带独立开关与说明，打开即见效果。

### 兼容性

| 项目 | 版本 |
|---|---|
| 目标 App | 国信金太阳 **8.6.0**（`com.guosen.android`） |
| 模块框架 | LSPosed（**libxposed API 102**） |
| 测试环境 | MEIZU 20 / Android 16 / Magisk + LSPosed |
| minSdk / targetSdk | 27 / 36 |

App 升级后如果失效，多数情况只需要改几个常量名，
排查步骤见 [Hook 难点 · 失效预案](docs/HOOK-NOTES.md#10-失效预案app-升级后怎么办)。

### 构建

```bash
# 需要 JDK 17+，Android SDK（compileSdk 37）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
cp keystore.properties.example keystore.properties   # 填入自己的签名信息，可跳过

./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

没有 `keystore.properties` 时会自动退回 AGP 自带的 debug 签名，仍可正常安装。

依赖版本组合是踩坑后的稳定解：`AGP 8.10.1 + Kotlin 2.4.0 + Miuix 0.9.3 + backdrop 1.0.6 + compileSdk 37`。
三条约定：

- 升到 Miuix 0.9.4 会要求 AGP ≥ 9.1，而 AGP 9.x 内置 Kotlin 插件会与
  `org.jetbrains.kotlin.android` 冲突
- 升到 backdrop 2.x 会拉进 Compose 1.12，同样要求 AGP ≥ 9.1
- **不引第三方图标库**：界面图标在 `ui/AppIcons.kt` 里自绘。
  `material-icons-extended` 会把 11 400 个用不到的图标类编进 dex，
  让 APK 从 1.34 MB 涨到 11.45 MB（见 [docs/UI.md](docs/UI.md) §10）

另两条界面约定（改前先读对应文档）：

- **系统栏沉浸是三层联动的**：主题属性（`values/` + `values-night/`）关掉
  `enforceNavigationBarContrast`、`enableEdgeToEdge()` 传透明 scrim、
  Compose 侧 `SyncSystemBars` 按生效的深浅色设图标明暗。
  漏掉任何一层，手势条那一条都会被填成纯色带（见 [docs/UI.md](docs/UI.md) §11）
- **弹层一律走自绘 `DialogHost`**，不要用 Miuix 的 `OverlayDropdownPreference` / `OverlayDialog`
  —— 它们在库内部的 `onClick` 里同步收起动画，与同步回写的配置状态打架会闪退
  （见 [docs/UI.md](docs/UI.md) §1.3）。挂在 `Scaffold` 外面的浮层会盖住弹层，
  液态悬浮底栏因此放在 content 里

完整说明见 [docs/BUILD.md](docs/BUILD.md)。

### 文档

| 文档 | 内容 |
|---|---|
| [**Hook 难点与方法**](docs/HOOK-NOTES.md) | 加固绕过、vlayout、类加载器、配置通道、日志位置、失效预案 |
| [**扩展与演进指南**](docs/EXTENDING.md) | 架构分层、四条红线、如何新增功能、演进路线 |
| [架构说明](docs/ARCHITECTURE.md) | 进程模型、调用链、关键数据结构、线程模型 |
| [逆向过程](docs/REVERSE-ENGINEERING.md) | 加固识别、页面链路、染色机制、判定 API 校准 |
| [直达标准行情实现方案](docs/FEATURE-DIRECT-QUOTE.md) | Intent 契约实测、改写逻辑、降级链、验收标准 |
| [构建指南](docs/BUILD.md) | 环境要求、依赖版本选择理由、Gradle/SDK 踩坑 |
| [排错手册](docs/TROUBLESHOOTING.md) | 模块不生效 / 开关无效 / 热重载 / App 升级后失效 |
| [设置界面](docs/UI.md) | 界面结构与组件说明 |
| [逆向参考数据](docs/reference/README.md) | Activity 清单、自选数据源、染色证据等原始采集数据 |
| [工具说明](tools/README.md) | 调试与自动化脚本用法 |

### 常见问题

**Q：模块装上去了，但没任何变化？**
先看 LSPosed 日志确认模块有没有加载：

```bash
adb shell su -c "grep GSZXEnhance /data/adb/lspd/log/modules_*.log | tail -20"
```

模块日志不在 logcat 里。详细排查见 [排错手册](docs/TROUBLESHOOTING.md)。

**Q：开关点了没反应？**
设置界面「概览」页的顶部状态卡会显示框架连接状态。
确认作用域勾了「国信金太阳」，并强制停止后重开一次。

**Q：需要手动勾选作用域吗？**
正常不需要。模块用 `staticScope=true` + `scope.list` 固定声明了只作用于
`com.guosen.android`，LSPosed 会自动应用。

**Q：会不会改我的数据？**
不会。模块只在显示层与交互层做增补，判定走目标 App 自己的只读 API，不写任何数据。

**Q：为什么新装上去功能全是关的？**
功能是侵入目标 App 行为的增强，装完模块不代表你同意被改，所以出厂默认全关（2.11.2 起）。

### 免责声明

本项目仅供**学习与个人使用**，用于改善自己使用 App 时的体验。

- 不修改目标 App 的任何数据、不绕过任何鉴权、不上传任何信息
- 所有判定逻辑都调用目标 App 自身的公开 API，模块只改变界面显示与交互
- 请勿将本项目用于商业用途或任何违反目标 App 服务条款的场景
- 使用本模块产生的任何后果由使用者自行承担

---

<a id="english"></a>

## English

### Installation

**Requirements**

- Rooted device with [LSPosed](https://github.com/LSPosed/LSPosed) installed (must support **API 102**)
- Target app: **GoldenSun 8.6.0** (`com.guosen.android`)
- Android 8.1+ (`minSdk 27`)

**Steps**

1. Download `app-release.apk` from the **Releases** page and install it (or build it yourself, see [Building](#building))
2. Open LSPosed manager → **Modules** → enable **GSAuxiliary**
3. Tick `GoldenSun` under **Scope** (the module declares its scope statically; it is usually applied automatically)
4. Force-stop GoldenSun and open it again
5. Open the **GSAuxiliary** app, go to the **Features** tab and enable whatever you need

The first activation requires one target-app restart — LSPosed loads modules **when the process starts**.
After that, neither changing settings nor upgrading the module APK requires restarting GoldenSun.

### Default behaviour

**Every feature toggle ships disabled.**
These features alter the behaviour of the target app; installing the module is not consent to
having that app modified. Enable only what you want — this default is deliberate.
Turning a toggle off restores the target app's original behaviour and appearance immediately,
leaving nothing behind.

The full feature list and how to use each one live inside the module's settings UI:
every feature has its own toggle and description, and takes effect the moment you flip it.

### Compatibility

| Item | Version |
|---|---|
| Target app | GoldenSun **8.6.0** (`com.guosen.android`) |
| Module framework | LSPosed (**libxposed API 102**) |
| Tested on | MEIZU 20 / Android 16 / Magisk + LSPosed |
| minSdk / targetSdk | 27 / 36 |

If the module stops working after a target-app update, the fix is usually a few constant names.
See [Hook notes · failure recovery](docs/HOOK-NOTES.md#10-失效预案app-升级后怎么办) for the checklist.

### Building

```bash
# JDK 17+ and the Android SDK (compileSdk 37) required
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
cp keystore.properties.example keystore.properties   # fill in your own signing info; optional

./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` the build falls back to AGP's debug signing and still installs fine.

The dependency set is a hard-won stable combination:
`AGP 8.10.1 + Kotlin 2.4.0 + Miuix 0.9.3 + backdrop 1.0.6 + compileSdk 37`. Three rules:

- Miuix 0.9.4 requires AGP ≥ 9.1, and AGP 9.x bundles its own Kotlin plugin, which conflicts
  with `org.jetbrains.kotlin.android`
- backdrop 2.x pulls in Compose 1.12, which also requires AGP ≥ 9.1
- **No third-party icon library.** All UI icons are hand-drawn in `ui/AppIcons.kt`.
  `material-icons-extended` compiles 11,400 unused icon classes into the dex,
  growing the APK from 1.34 MB to 11.45 MB (see [docs/UI.md](docs/UI.md) §10)

Two more UI rules (read the linked docs before changing either):

- **Edge-to-edge is a three-layer agreement**: theme attributes (`values/` + `values-night/`)
  disable `enforceNavigationBarContrast`, `enableEdgeToEdge()` passes a transparent scrim, and
  `SyncSystemBars` sets the bar icon appearance from the *effective* light/dark mode.
  Miss any layer and the gesture bar gets filled with a solid strip (see [docs/UI.md](docs/UI.md) §11)
- **All popups go through the hand-rolled `DialogHost`** — do not use Miuix's
  `OverlayDropdownPreference` / `OverlayDialog`. They start their dismissal animation
  synchronously inside the library's `onClick`, which races with the synchronous config write
  and crashes (see [docs/UI.md](docs/UI.md) §1.3). Floating layers placed outside `Scaffold`
  cover the popups, which is why the liquid-glass dock lives inside the content

Full details in [docs/BUILD.md](docs/BUILD.md) (Chinese).

### Documentation

All design documents are written in Chinese.

| Document | Contents |
|---|---|
| [Hook notes](docs/HOOK-NOTES.md) | RASP workarounds, vlayout, class loaders, config channel, log location, failure recovery |
| [Extending guide](docs/EXTENDING.md) | Layering, the four architectural red lines, how to add a feature, roadmap |
| [Architecture](docs/ARCHITECTURE.md) | Process model, call chain, key data structures, threading |
| [Reverse engineering](docs/REVERSE-ENGINEERING.md) | RASP identification, page flow, colouring mechanism, judgement-API calibration |
| [Direct-quote design](docs/FEATURE-DIRECT-QUOTE.md) | Measured Intent contract, rewrite logic, fallback chain, acceptance criteria |
| [Build guide](docs/BUILD.md) | Requirements, why these dependency versions, Gradle/SDK pitfalls |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Module not loading / toggles not working / hot reload / post-update failures |
| [Settings UI](docs/UI.md) | Screen structure and component reference |
| [Reference data](docs/reference/README.md) | Raw recon output: activity list, watchlist data source, colouring evidence |
| [Tooling](tools/README.md) | Debug and automation scripts |

### FAQ

**Q: The module is installed but nothing changed.**
Check the LSPosed log first to confirm the module was loaded:

```bash
adb shell su -c "grep GSZXEnhance /data/adb/lspd/log/modules_*.log | tail -20"
```

Module logs are **not** in logcat. Full checklist in the
[troubleshooting guide](docs/TROUBLESHOOTING.md).

**Q: A toggle does nothing.**
The status card at the top of the **Overview** tab shows the framework connection state.
Make sure `GoldenSun` is ticked in the scope, then force-stop and reopen the target app.

**Q: Do I need to select the scope manually?**
Normally no. The module declares `staticScope=true` plus a `scope.list` limited to
`com.guosen.android`, and LSPosed applies it automatically.

**Q: Does it modify my data?**
No. The module only augments presentation and interaction. All checks call the target app's own
read-only APIs, and nothing is ever written.

**Q: Why is everything disabled after a fresh install?**
These features alter the behaviour of the target app, and installing the module is not consent to
having it modified — hence the all-off default (since 2.11.2).

### Disclaimer

This project is for **learning and personal use only**, to improve your own experience with the app.

- It modifies none of the target app's data, bypasses no authentication, and uploads nothing
- All decision logic calls the target app's own public APIs; the module only changes presentation
  and interaction
- Do not use this project commercially or in any way that violates the target app's terms of service
- Any consequences of using this module are borne by the user

---

<a id="项目结构--project-structure"></a>

## 项目结构 / Project structure

```
GSAuxiliary/
├── app/                                  # 模块本体（Android Application 模块）
│   └── src/main/
│       ├── AndroidManifest.xml           # 常规声明（无任何 xposed* meta-data）
│       ├── resources/META-INF/xposed/    # ★ 模块元数据 / module metadata
│       │   ├── module.prop               #   minApiVersion / targetApiVersion / scope
│       │   ├── java_init.list            #   入口类 gsauxiliary.m.venn.GszxModule
│       │   └── scope.list                #   作用域 com.guosen.android
│       ├── res/drawable/                 # 图标矢量层
│       │   ├── ic_launcher_background.xml  # 自适应图标背景（渐变，不是纯色资源）
│       │   ├── ic_launcher_foreground.xml  # 前景：金色上升折线 + 右上箭头
│       │   └── ic_launcher_monochrome.xml  # Android 13+ 主题图标剪影
│       └── java/gsauxiliary/m/venn/
│           ├── GszxModule.kt             # ★ 入口：只做编排
│           ├── core/                     #   与框架无关的公共层
│           │   ├── GLog.kt               #     日志（两个进程共用）
│           │   ├── ConfigKeys.kt         #     配置键与默认值（唯一来源）
│           │   ├── ConfigSnapshot.kt     #     不可变配置快照
│           │   ├── ConfigSource.kt       #     目标进程配置读取
│           │   ├── HookBag.kt            #     钩子句柄集合
│           │   └── TargetApp.kt
│           ├── util/Reflect.kt           #   反射工具（候选名 + 继承链查找）
│           ├── feature/                  # ★ 功能层：新功能都加在这里
│           │   ├── Feature.kt            #     扩展接口
│           │   ├── FeatureEnv.kt
│           │   ├── FeatureManager.kt     #     features = listOf(...) ← 唯一接入点
│           │   ├── watchlist/            #     自选高亮
│           │   ├── strategyquote/        #     策略详情直达标准行情 + 左右滑动
│           │   └── strategyselect/       #     策略多选栏「全选 / 反选」
│           ├── app/                      #   模块 App 自己的进程
│           │   ├── GszxApp.kt
│           │   ├── FrameworkBridge.kt    #     与 LSPosed 服务通信
│           │   ├── ModulePreferences.kt  #     配置仓库
│           │   ├── ConfigBackup.kt       #     配置备份 / 恢复（JSON）
│           │   ├── LauncherIcon.kt       #     桌面图标开关（切 activity-alias）
│           │   ├── LocaleHelper.kt       #     界面语言
│           │   └── DebugConfigProvider.kt#     adb 调试通道（仅 root/shell）
│           └── ui/                       #   Miuix Compose 设置界面
│               ├── SettingsActivity.kt    #     Activity + 三层布局 + 路由 + Dock / 顶栏 / 弹窗装配
│               ├── Glass.kt               #     液态玻璃：顶栏、色料、下滑收起状态机
│               ├── AppDialog.kt           #     自绘弹窗宿主（Miuix 风格 + 液态玻璃）
│               ├── PrefState.kt           #     配置读取的 Composable
│               ├── SystemBars.kt          #     系统栏沉浸 + LocalNavBarInset
│               ├── FeatureCatalog.kt      #     功能展示元数据（feature/ 的镜像表）
│               ├── Widgets.kt             #     共用部件（色块 / 预览行 / 卡片注脚）
│               ├── AppIcons.kt            #     自绘矢量图标（零图标库依赖，见 docs/UI.md §10）
│               ├── MarkdownText.kt        #     轻量 Markdown 渲染（免责声明正文用）
│               ├── Dock.kt                #     默认底栏 / 液态玻璃悬浮底栏（+ LocalDockInset）
│               ├── ModuleTab.kt / FeatureTab.kt / SettingsTab.kt / AboutTab.kt
│               ├── WatchlistDetail.kt     #     二级页：染色设置
│               ├── StrategyQuoteDetail.kt #     二级页：滑动设置
│               └── LogViewer.kt           #     二级页：查看日志（复制 / 保存到任意位置）
├── docs/                                 # ★ 文档（含逆向参考数据）
└── tools/                                # 开发期工具（不参与打包）
    ├── env.sh / dev.sh / adbw.sh / sh.sh / drive.sh  # 环境探测、无线调试保活、设备操作
    ├── verify.py                         # ★ 自动校验高亮（截图采样）
    ├── sample_colors.py / uifind.py / uidump.py      # 取色 / 控件定位
    ├── enable_lsposed.py                 # 直接改写 LSPosed 配置库
    ├── glog.sh + glog_tail.py            # 读 LSPosed 模块日志增量
    ├── gen_launcher_icons.py             # ★ 生成 launcher 位图图标（距离场描边，各密度 + 圆形）
    ├── verify_icons.py                   # ★ 校验自绘图标 path（结构检查 + 渲染比对）
    └── probe/                            # ★ 运行时探针（App 升级后重新定位类名）
```

---

<a id="开源鸣谢--credits"></a>

## 开源鸣谢 / Credits

本模块建立在这些开源项目之上：

| 项目 | 在本文档中的作用 / Role here |
|---|---|
| [**LSPosed**](https://github.com/LSPosed/LSPosed) · [**libxposed**](https://github.com/libxposed) | 模块框架与现代 Xposed API（API 102）<br>Module framework and the modern Xposed API |
| [**Miuix**](https://github.com/miuix-kotlin-multiplatform/miuix) | 设置界面的 UI 组件库（小米 HyperOS 风格 Compose）<br>UI component library for the settings app (HyperOS-flavoured Compose) |
| [**AndroidLiquidGlass**](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop`） | 液态玻璃顶栏 / 悬浮底栏 / 弹窗的折射与模糊效果<br>Refraction and blur for the liquid-glass top bar, dock and dialogs |
| [**Jetpack Compose**](https://developer.android.com/jetpack/compose) · [**AndroidX**](https://developer.android.com/jetpack/androidx) | 全部界面实现的基础设施<br>Foundation for the entire UI |
| [**Kotlin**](https://github.com/JetBrains/kotlin) | 模块与设置界面的实现语言<br>Implementation language for the module and the settings app |
| [**Material Icons**](https://github.com/google/material-design-icons) | 界面图标的矢量路径数据来源（按 path 自绘 `ImageVector`，不引入图标库）<br>Source of the icon path data (hand-built `ImageVector`s; no icon library is bundled) |

感谢以上项目的作者与贡献者。

Thanks to the authors and contributors of all the projects above.

> 目标 App「国信金太阳」及其商标、标识的权利均归其权利人所有。
> 本项目与之不存在任何隶属、合作、授权、认可或支持关系。
>
> "GoldenSun" and its trademarks and logos belong to their respective owners.
> This project has no affiliation, partnership, authorisation, endorsement or support
> relationship with it.

---

## License

[Apache-2.0](LICENSE)
