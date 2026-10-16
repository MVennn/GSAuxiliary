<div align="center">

<img src="https://raw.githubusercontent.com/MVennn/GSAuxiliary/main/assets/logo.png" width="128" alt="GSAuxiliary">

# GSAuxiliary

**国信金太阳 · 体验增强模块**

Make GoldenSun Great Again！

![版本](https://img.shields.io/github/v/release/MVennn/GSAuxiliary) ![源码仓库](https://img.shields.io/badge/源码仓库-GitHub-181717?logo=github&logoColor=white) ![问题反馈](https://img.shields.io/badge/问题反馈-Issues-EA4335?logo=github&logoColor=white) ![Android](https://img.shields.io/badge/Android-8.1%2B-3DDC84?logo=android&logoColor=white) ![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-5C6BC0) ![License](https://img.shields.io/badge/License-Apache--2.0-blue)

</div>

---

## 这是什么

一个给国信金太阳做界面增强的 LSPosed 模块。

在已经 root 并装了 LSPosed 的手机上，给金太阳的界面显示和页面操作做些修补 ——  
有些地方本来要多点一下，有些信息本来要多翻一层。它不碰金太阳的数据，也不碰服务端，  
所有改动只发生在你自己的手机上。

功能以模块 App「功能」页为准。

## 安装

1. 到 [Releases](../../releases/latest) 下载最新版（文件名形如 `GSAuxiliary_*.apk`）并安装
2. 在 LSPosed 管理器 → 模块里勾选 GSAuxiliary
3. 作用域中勾上「国信金太阳」（模块已声明默认作用域，一般会自动勾好）
4. 强制停止国信金太阳，再打开一次
5. 打开桌面上的 GSAuxiliary（首次打开会先请同意免责声明），进「功能」页挑选要开的开关（开关默认全部关闭；部分开关打开后需重启金太阳生效）

首次启用才需要第 4 步；之后升级模块 APK，框架会自动热重载，无需重启金太阳。

## 反馈

遇到问题、想要新功能，或者金太阳升级之后某个开关失灵了，都可以到[Issues](https://github.com/MVennn/GSAuxiliary/issues) 进行反馈。

请上传相关信息与日志。相关信息主要包括：

- 金太阳的版本号
- 模块版本号、安卓版本、机型
- 出问题的开关，以及复现步骤
- 日志（模块在日志里的标记名是 `GSZXEnhance`，与模块名不同，照抄即可）：
  - 模块 App →「设置 → 查看日志」，直接查看与复制
  - 或用 adb 抓 LSPosed 日志：     
    `adb shell su -c "grep GSZXEnhance /data/adb/lspd/log/modules_*.log | tail -50"`

## 免责声明

本项目仅供学习与个人使用。

- 不修改目标 App 的任何数据、不绕过任何鉴权、不上传任何信息
- 所有判定逻辑都调用金太阳自身的公开 API，模块只改变界面显示与交互
- 请勿用于商业用途或任何违反金太阳服务条款的场景
- 使用本模块产生的任何后果由使用者自行承担

「国信金太阳」商标、标识的权利均归其权利人所有。本项目与之不存在任何隶属、合作、授权、认可或支持关系。

## 开源鸣谢

本模块建立在这些开源项目之上：

| 项目                                                                                                                                    | 用途                                  |
| ------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| [**LSPosed**](https://github.com/LSPosed/LSPosed) · [**libxposed**](https://github.com/libxposed)                                     | 模块框架与现代 Xposed API（API 102）         |
| [**Miuix**](https://github.com/miuix-kotlin-multiplatform/miuix)                                                                      | 设置界面的 UI 组件库（小米 HyperOS 风格 Compose） |
| [**AndroidLiquidGlass**](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop`）                                   | 液态玻璃顶栏 / 悬浮底栏 / 弹窗的折射与模糊效果          |
| [**Jetpack Compose**](https://developer.android.com/jetpack/compose) · [**AndroidX**](https://developer.android.com/jetpack/androidx) | 全部界面实现的基础设施                         |
| [**Kotlin**](https://github.com/JetBrains/kotlin)                                                                                     | 模块与设置界面的实现语言                        |
| [**Material Icons**](https://github.com/google/material-design-icons)                                                                 | 界面图标的矢量路径数据来源（按 path 自绘，不引入图标库）     |
