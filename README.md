<div align="center">

<img src="docs/logo.png" width="128" alt="GSAuxiliary">

# GSAuxiliary

**国信金太阳 · 体验增强模块**

Make GoldenSun Great Again！

[![下载最新版本](https://img.shields.io/badge/下载-最新版本-3DDC84?logo=android&logoColor=white)](/releases/latest)
![Android](https://img.shields.io/badge/Android-8.1%2B-3DDC84?logo=android&logoColor=white)
![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-5C6BC0)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)

</div>

---

## 这是什么

让**国信金太阳**用起来更顺手一点。

它不改金太阳的任何数据、不动服务端，只在你自己的手机上，把界面显示和页面操作补顺一些 ——
比如有些位置本来要多点一下，有些信息本来要多看一眼。

装好之后打开模块 App，**需要哪个功能就打开哪个开关**。

## 安装

**需要**：手机已 root，装了 [LSPosed](https://github.com/LSPosed/LSPosed)（要支持 API 102）；
国信金太阳 8.6.0；Android 8.1 及以上。

1. 到本仓库的 [**Releases**](/releases/latest) 下载最新的 `GSAuxiliary_*.apk`，装上
2. 打开 **LSPosed 管理器 → 模块**，勾选 **GSAuxiliary**
3. **作用域**里勾上「国信金太阳」（模块已固定声明作用域，通常会自动勾好）
4. **强制停止**国信金太阳，再重新打开一次
5. 打开桌面上的 **GSAuxiliary**，进「**功能**」页，按需打开开关

> ⚠️ **把一个功能从「关」改成「开」，要重启一次金太阳才生效；从「开」改成「关」是立刻生效的。**
> 这是 LSPosed 的加载方式决定的 —— 它在金太阳**启动时**装钩子。

## 使用

所有功能都在模块 App 的「**功能**」页，每个功能一个开关配一段说明。

**功能开关出厂全部关闭**，打开即见效果，关掉立刻还原，不留痕迹。
每个功能具体做什么、有什么可调的，进去看一眼比看文档快。

> 桌面上找不到 GSAuxiliary 图标？去「设置 → 界面 → 桌面图标」看看是不是被关了 ——
> 从 LSPosed 管理器的模块列表里同样能进设置界面。

## 常见问题

**Q：装上了，但没任何变化？**
先进模块 App 的「**概览**」页 —— 顶部状态卡写着「已激活」还是「未激活」。
如果是未激活，去 LSPosed 里确认模块和作用域都勾上了，然后强制停止金太阳重开一次。

**Q：开了开关也没反应？**
检查是不是忘了重启金太阳（见上面的 ⚠️）。

**Q：需要手动勾作用域吗？**
正常不用。模块已经固定声明只作用于国信金太阳。

**Q：会改我的数据 / 账号吗？**
不会。模块不写数据、不拦截请求、不上传任何信息，只改界面上的显示和操作。

**Q：会不会把金太阳原本的样子弄乱？**
关掉开关就完全还原。模块也不动涨跌幅颜色这类原有显示。

**Q：装新版本要先卸载吗？**
不用，直接覆盖安装，设置会保留。

## 免责声明

本项目仅供**学习与个人使用**。

- 不修改目标 App 的任何数据、不绕过任何鉴权、不上传任何信息
- 所有判定逻辑都调用金太阳自身的公开 API，模块只改变界面显示与交互
- 请勿用于商业用途或任何违反金太阳服务条款的场景
- 使用本模块产生的任何后果由使用者自行承担

## 开源鸣谢

本模块建立在这些开源项目之上，感谢它们的作者与贡献者：

| 项目 | 用途 |
|---|---|
| [**LSPosed**](https://github.com/LSPosed/LSPosed) · [**libxposed**](https://github.com/libxposed) | 模块框架与现代 Xposed API（API 102） |
| [**Miuix**](https://github.com/miuix-kotlin-multiplatform/miuix) | 设置界面的 UI 组件库（小米 HyperOS 风格 Compose） |
| [**AndroidLiquidGlass**](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop`） | 液态玻璃顶栏 / 悬浮底栏 / 弹窗的折射与模糊效果 |
| [**Jetpack Compose**](https://developer.android.com/jetpack/compose) · [**AndroidX**](https://developer.android.com/jetpack/androidx) | 全部界面实现的基础设施 |
| [**Kotlin**](https://github.com/JetBrains/kotlin) | 模块与设置界面的实现语言 |
| [**Material Icons**](https://github.com/google/material-design-icons) | 界面图标的矢量路径数据来源（按 path 自绘，不引入图标库） |

> 「国信金太阳」及其商标、标识的权利均归其权利人所有。
> 本项目与之不存在任何隶属、合作、授权、认可或支持关系。

---

<sub>开发者：构建方式见 [docs/BUILD.md](docs/BUILD.md)，架构与设计走向见 [docs/](docs/)。</sub>
