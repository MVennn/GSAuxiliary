<div align="center">

<img src="https://raw.githubusercontent.com/SansKritO4/GSAuxiliary/main/docs/logo.png" width="128" alt="GSAuxiliary">

# GSAuxiliary

**国信金太阳 · 体验增强模块**

Make GoldenSun Great Again！

[![下载最新版本](https://img.shields.io/badge/下载-最新版本-3DDC84?logo=android&logoColor=white)](../../releases/latest)
[![源码仓库](https://img.shields.io/badge/源码仓库-GitHub-181717?logo=github&logoColor=white)](https://github.com/SansKritO4/GSAuxiliary)
[![问题反馈](https://img.shields.io/badge/问题反馈-Issues-EA4335?logo=github&logoColor=white)](https://github.com/SansKritO4/GSAuxiliary/issues)
[![CI](https://github.com/SansKritO4/GSAuxiliary/actions/workflows/ci.yml/badge.svg)](https://github.com/SansKritO4/GSAuxiliary/actions/workflows/ci.yml)
![Android](https://img.shields.io/badge/Android-8.1%2B-3DDC84?logo=android&logoColor=white)
![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-5C6BC0)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)

</div>

---

## 这是什么

给国信金太阳做界面调整的 LSPosed 模块。不改数据、不动服务端，只在你自己手机上生效。

功能都在模块 App 的「功能」页，一个开关对应一个功能，默认全部关闭。

## 安装

需要：已 root 的手机、[LSPosed](https://github.com/LSPosed/LSPosed)（API 102）、
国信金太阳（`com.guosen.android`）、Android 8.1 及以上。

1. 从 [Releases](../../releases/latest) 下载最新的 `GSAuxiliary_*.apk` 装上
2. LSPosed 管理器 → 模块，勾选 GSAuxiliary
3. 作用域勾上「国信金太阳」（模块已声明作用域，通常会自动勾好）
4. 强制停止国信金太阳，重新打开
5. 打开 GSAuxiliary，进「功能」页按需开开关

开关从「关」改成「开」要重启一次金太阳才生效 —— LSPosed 在 App 启动时装钩子；
从「开」改成「关」立刻生效。

## 使用

每个开关配一段说明。默认全部关闭，打开即生效，关掉立刻还原。

桌面图标找不到的话，去「设置 → 界面 → 桌面图标」看是不是被关掉了；
也可以从 LSPosed 管理器的模块列表进设置界面。

## 常见问题

**装上了没变化**
看模块 App「概览」页顶部的状态卡是「已激活」还是「未激活」。未激活就去 LSPosed
确认模块和作用域都勾上了，然后强制停止金太阳重开一次。

**开了开关没反应**
重启一次金太阳。

**需要手动勾作用域吗**
不用，模块已固定声明只作用于国信金太阳。

**必须用 8.6 版本的金太阳吗**
不用。8.6.0 只是开发时的验证基线，模块靠「候选名 + 多级兜底」适配界面，换版本大多照常可用。

**会改我的数据或账号吗**
不会。不写数据、不拦截请求、不上传任何信息，只改界面显示与操作。

**升级要先卸载吗**
不用，直接覆盖安装，设置保留。

## 自己编译

不想装环境：推上去让 GitHub 编 —— [Actions](https://github.com/SansKritO4/GSAuxiliary/actions/workflows/ci.yml)
每次提交都会跑，产物在对应 run 的 Artifacts 里。

本机编译见 [构建指南](https://github.com/SansKritO4/GSAuxiliary/blob/main/docs/BUILD.md)：
JDK 17 + Android SDK Platform 37，然后 `./gradlew assembleRelease`。

## 反馈

问题、新功能、金太阳升级后某个开关失灵 —— 都去 [Issues](https://github.com/SansKritO4/GSAuxiliary/issues) 提。

带上这些会快很多：

- 金太阳版本号
- 模块版本号、安卓版本、机型
- 出问题的功能开关与复现步骤
- LSPosed 日志：
  `adb shell su -c "grep GSZXEnhance /data/adb/lspd/log/modules_*.log | tail -50"`

## 免责声明

本项目仅供学习与个人使用。

- 不修改目标 App 的任何数据、不绕过任何鉴权、不上传任何信息
- 所有判定逻辑都调用金太阳自身的公开 API，模块只改变界面显示与交互
- 请勿用于商业用途或任何违反金太阳服务条款的场景
- 使用本模块产生的任何后果由使用者自行承担

## 开源鸣谢

本模块建立在这些开源项目之上：

| 项目 | 用途 |
|---|---|
| [**LSPosed**](https://github.com/LSPosed/LSPosed) · [**libxposed**](https://github.com/libxposed) | 模块框架与现代 Xposed API（API 102） |
| [**Miuix**](https://github.com/miuix-kotlin-multiplatform/miuix) | 设置界面的 UI 组件库（小米 HyperOS 风格 Compose） |
| [**AndroidLiquidGlass**](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop`） | 液态玻璃顶栏 / 悬浮底栏 / 弹窗的折射与模糊效果 |
| [**Jetpack Compose**](https://developer.android.com/jetpack/compose) · [**AndroidX**](https://developer.android.com/jetpack/androidx) | 全部界面实现的基础设施 |
| [**Kotlin**](https://github.com/JetBrains/kotlin) | 模块与设置界面的实现语言 |
| [**Material Icons**](https://github.com/google/material-design-icons) | 界面图标的矢量路径数据来源（按 path 自绘，不引入图标库） |

「国信金太阳」及其商标、标识的权利均归其权利人所有。
本项目与之不存在任何隶属、合作、授权、认可或支持关系。
