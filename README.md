<div align="center">

# GSAuxiliary

**国信金太阳 · 体验增强模块**

Make GoldenSun Great Again！

针对国信金太阳（`com.guosen.android`）的 LSPosed 模块。
不改目标 App 的数据、不动服务端，只在本机对界面显示与页面交互做增补。

![Android](https://img.shields.io/badge/Android-8.1%2B-3DDC84?logo=android&logoColor=white)
![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-5C6BC0)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)
![Version](https://img.shields.io/badge/version-2.12.1-blue)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)

</div>

---

## 安装

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

> ⚠️ **单个功能开关也需要重启**（容易踩）：`FeatureManager` 只在**目标进程启动时**安装钩子，
> 配置变化只做「分发」而**不会补装钩子**。所以把一个功能从**关 → 开**，也要强制停止并重新打开金太阳；
> 反过来**开 → 关**是即时生效的（钩子还在，功能自己在钩子里判断开关）。

---

## 兼容性

| 项目 | 版本 |
|---|---|
| 目标 App | 国信金太阳 **8.6.0**（`com.guosen.android`） |
| 模块框架 | LSPosed（**libxposed API 102**） |
| 测试环境 | MEIZU 20 / Android 16 / Magisk + LSPosed |
| minSdk / targetSdk | 27 / 36 |

App 升级后如果失效，多数情况只需要改几个常量名，
排查步骤见 [Hook 难点 · 失效预案](docs/HOOK-NOTES.md#10-失效预案app-升级后怎么办)。

---

## 构建

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

---

## 项目结构

```
GSAuxiliary/
├── app/                                  # 模块本体（Android Application 模块）
│   └── src/main/
│       ├── AndroidManifest.xml           # 常规声明（无任何 xposed* meta-data）
│       ├── resources/META-INF/xposed/    # ★ 模块元数据
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
│           │   ├── strategyselect/       #     策略多选栏「全选 / 反选」
│           │   └── stgfilter/            #     策略详情三点菜单「筛选股票」（涨幅 / 跌幅 / 自选）
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

## 文档

| 文档 | 内容 |
|---|---|
| [**Hook 难点与方法**](docs/HOOK-NOTES.md) | 加固绕过、vlayout、类加载器、配置通道、日志位置、失效预案 |
| [**扩展与演进指南**](docs/EXTENDING.md) | 架构分层、四条红线、如何新增功能、演进路线 |
| [架构说明](docs/ARCHITECTURE.md) | 进程模型、调用链、关键数据结构、线程模型 |
| [逆向过程](docs/REVERSE-ENGINEERING.md) | 加固识别、页面链路、染色机制、判定 API 校准 |
| [直达标准行情实现方案](docs/FEATURE-DIRECT-QUOTE.md) | Intent 契约实测、改写逻辑、降级链、验收标准、二次点击闪退修复 |
| [多选栏全选 / 反选实现说明](docs/FEATURE-SELECT-ALL.md) | 控件契约实测、视图注入、覆盖屏外行、文案切换与间距、验收标准 |
| [策略详情「筛选股票」实现说明](docs/FEATURE-STG-FILTER.md) | 三点菜单注入、复用 App 筛选面板布局、行过滤与持久化、验收标准 |
| [构建指南](docs/BUILD.md) | 环境要求、依赖版本选择理由、Gradle/SDK 踩坑 |
| [排错手册](docs/TROUBLESHOOTING.md) | 模块不生效 / 开关无效 / 热重载 / App 升级后失效 |
| [设置界面](docs/UI.md) | 界面结构与组件说明 |
| [逆向参考数据](docs/reference/README.md) | Activity 清单、自选数据源、染色证据等原始采集数据 |
| [工具说明](tools/README.md) | 调试与自动化脚本用法 |

---

## 常见问题

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
用哪个开哪个；关闭开关后目标 App 的行为与外观立即还原。

---

## 开源鸣谢

本模块建立在这些开源项目之上：

| 项目 | 用途 |
|---|---|
| [**LSPosed**](https://github.com/LSPosed/LSPosed) · [**libxposed**](https://github.com/libxposed) | 模块框架与现代 Xposed API（API 102） |
| [**Miuix**](https://github.com/miuix-kotlin-multiplatform/miuix) | 设置界面的 UI 组件库（小米 HyperOS 风格 Compose） |
| [**AndroidLiquidGlass**](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop`） | 液态玻璃顶栏 / 悬浮底栏 / 弹窗的折射与模糊效果 |
| [**Jetpack Compose**](https://developer.android.com/jetpack/compose) · [**AndroidX**](https://developer.android.com/jetpack/androidx) | 全部界面实现的基础设施 |
| [**Kotlin**](https://github.com/JetBrains/kotlin) | 模块与设置界面的实现语言 |
| [**Material Icons**](https://github.com/google/material-design-icons) | 界面图标的矢量路径数据来源（按 path 自绘 `ImageVector`，不引入图标库） |

感谢以上项目的作者与贡献者。

> 目标 App「国信金太阳」及其商标、标识的权利均归其权利人所有。
> 本项目与之不存在任何隶属、合作、授权、认可或支持关系。

---

## 免责声明

本项目仅供**学习与个人使用**，用于改善自己使用 App 时的体验。

- 不修改目标 App 的任何数据、不绕过任何鉴权、不上传任何信息
- 所有判定逻辑都调用目标 App 自身的公开 API，模块只改变界面显示与交互
- 请勿将本项目用于商业用途或任何违反目标 App 服务条款的场景
- 使用本模块产生的任何后果由使用者自行承担

---

## License

[Apache-2.0](LICENSE)
