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

一个给国信金太阳做界面增强的 LSPosed 模块。

在已经 root 并装了 LSPosed 的手机上，给金太阳的界面显示和页面操作做些修补 ——
有些地方本来要多点一下，有些信息本来要多翻一层。它不碰金太阳的数据，也不碰服务端，
所有改动只发生在你自己的手机上。

每一项改动都做成一个开关，收在模块 App 的「功能」页里，默认全部关闭，
想用哪个自己打开。

## 安装

前提：手机已 root，装有 [LSPosed](https://github.com/LSPosed/LSPosed)（需支持 API 102），
系统 Android 8.1 及以上，装有国信金太阳（`com.guosen.android`）。

1. 到 [Releases](../../releases/latest) 下载最新的 `GSAuxiliary_*.apk` 并安装
2. 在 LSPosed 管理器 → 模块里勾选 GSAuxiliary
3. 作用域中勾上「国信金太阳」（模块已固定声明作用域，一般会自动勾好）
4. 强制停止国信金太阳，再打开一次
5. 打开桌面上的 GSAuxiliary，进「功能」页挑选要开的开关

开关从「关」改成「开」之后，要重启一次金太阳才会生效 —— LSPosed 是在 App 启动时
挂钩子的。反过来，从「开」改成「关」是立刻生效的。

## 使用

功能都收在「功能」页，每个开关下面配了一段说明，讲它改了什么、有没有可调的项。
默认全部关闭，打开就生效，关掉就还原。

桌面上找不到图标的话，去「设置 → 界面 → 桌面图标」看看是不是被关掉了；
也可以从 LSPosed 管理器的模块列表直接进设置界面。

## 常见问题

**装好了但没变化**
先看模块 App「概览」页顶部的状态卡，显示的是「已激活」还是「未激活」。
未激活的话，去 LSPosed 确认模块和作用域都勾上了，然后强制停止金太阳重新打开一次。

**开关打开了没反应**
重启一次金太阳 —— 钩子是在启动时挂上去的。

**需要手动勾作用域吗**
不用。模块已经固定声明只作用于国信金太阳，正常情况下会自动勾好。

**必须用 8.6 版本的金太阳吗**
不用。8.6.0 只是开发时用来验证的版本，模块靠「候选名 + 多级兜底」去适配界面，
换成别的版本大多照常能用。真失效了，来 Issues 说一声。

**会动我的数据或者账号吗**
不会。模块不写数据、不拦截请求、不上传任何信息，只改界面上的显示和交互。

**升级要先卸载旧版吗**
不用。直接覆盖安装，原来的设置会保留。

## 编译

不想配环境：直接推到 GitHub，[Actions](https://github.com/SansKritO4/GSAuxiliary/actions/workflows/ci.yml)
每次提交都会构建，产物在对应 run 的 Artifacts 里。

本机编译见 [构建指南](https://github.com/SansKritO4/GSAuxiliary/blob/main/docs/BUILD.md)：
需要 JDK 17 与 Android SDK Platform 37，然后 `./gradlew assembleRelease`。

## 反馈

遇到问题、想要新功能，或者金太阳升级之后某个开关失灵了，都可以到
[Issues](https://github.com/SansKritO4/GSAuxiliary/issues) 提一条。

带上这些信息会快很多：

- 金太阳的版本号
- 模块版本号、安卓版本、机型
- 出问题的开关，以及复现步骤
- LSPosed 日志（能附上最好）：
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
