# 国信金太阳 8.6.0 APK 解析报告

> **文档定位**：本文为 8.6.0 初版原始分析，结论已收敛进
> [`../REVERSE-ENGINEERING.md`](../REVERSE-ENGINEERING.md)；APK 基础信息与
> 加固证据链以该文档为准，本文仅保留原始数据价值。

分析时间：2026-10-14
文件：`C:\Users\SansK\Downloads\国信金太阳_8.6.0.apk`（144 MB）

---

## 一、基础信息

| 项 | 值 |
|---|---|
| 包名 | `com.guosen.android` |
| 版本 | versionName `8.6.0` / versionCode `271` |
| minSdk | 21（Android 5.0） |
| targetSdk | 30（Android 11） |
| 编译 SDK | 30 |
| 体积 | 150,373,575 字节（约 144 MB） |
| DEX | 单 dex，未压缩 97,392,176 字节 |
| ABI | `arm64-v8a` / `armeabi-v7a` |
| 打包时间 | 2026-09-15 |

---

## 二、加固：梆梆企业版（SecNeo / Bangcle）

**已确认，证据链完整：**

```
AndroidManifest.xml:
  application android:name = com.secneo.apkwrapper.AW
  appComponentFactory      = com.secneo.apkwrapper.AP
  provider android:name    = com.secneo.apkwrapper.CP

lib/arm64-v8a/libDexHelper.so       1,070,186 字节
lib/arm64-v8a/libDexHelper-x86.so   1,237,741 字节
lib/armeabi-v7a/libDexHelper.so       627,418 字节
lib/armeabi-v7a/libDexHelper-x86.so 1,151,229 字节
```

### DEX 被加密，header 是伪造的

`classes.dex` 的头部字段本身自洽（声明 file_size = 97,392,176，与实际一致），
但内部索引小得离谱：

```
string_ids : 515        <- 97MB 的 dex 只有 515 个字符串
type_ids   : 166
proto_ids  : 91
field_ids  : 33
method_ids : 304
class_defs : 53         <- 只有 53 个类
```

也就是说：**这个 97 MB 文件里，只有头部 + 一小段壳代码是有效的，剩余约 97 MB 全是加密载荷。**

壳内可见的字符串印证了这一点：

```
###HAVEX86###      <- SecNeo 保护标记
###HAVEX8664###    <- SecNeo 保护标记
###SOPHIX###       <- SecNeo 自研虚拟机（VMP）
realApplication
replaceApplicationContext
patchProvider
startMethodTracing / stopMethodTracing / stopHierarchyTracing   <- 反调试
startAllocCounting / resetGlobalAllocCount / resetGlobalClassInitTime
/proc/self/exe  /proc/self/status  /system/bin/app_process      <- 反调试
```

`startMethodTracing` / `resetGlobalAllocCount` / `stopHierarchyTracing` 这一组是
**典型的反调试手段**：检测是否有人在 trace 方法调用或做内存分配统计。

### 其他被加密的资源

| 文件 | 大小 | 状态 |
|---|---|---|
| `assets/menu_release.txt` | 348 KB | 加密（十六进制密文，无明文结构） |
| `assets/meta-data/manifest.mf` | 236 KB | 梆梆自有加密清单 |

---

## 三、突破口：AndroidManifest 是明文，里面有完整的页面地图

加固只加密 DEX 和自定义资源，**AndroidManifest.xml 必须保持明文**（否则 Android 系统无法路由组件）。

解析结果：**814 个 Activity 声明，其中 `com.guosen.*` 占 662 个，类名完全未混淆。**

这意味着不用反编译就能拿到 App 的完整页面结构。完整清单见
[`activities-guosen.txt`](activities-guosen.txt)。

### 模块分布（Top 40）

| 数量 | 包路径 | 含义 |
|---:|---|---|
| 154 | `com.guosen.bizproc.ui` | 业务办理（开户、升级、签约、风控测评） |
| 63 | `com.guosen.quotation.ui` | 行情 |
| 38 | `com.guosen.trademodule.zxjy` | 在线交易 |
| 35 | `com.guosen.commonui.act` | 通用 UI |
| 25 | `com.guosen.mine.ui` | 我的 |
| 24 | `com.guosen.tradelogin.act` | 交易登录 |
| 24 | `com.guosen.app.goldsun` | 主壳 |
| 23 | `com.guosen.trademodule.rzrq` | 融资融券 |
| 20 | `com.guosen.marginoa.act` | 保证金 |
| 19 | `com.guosen.newoa.ui` | 移动办公 |
| 18 | `com.guosen.trademodule.qygl` | 企业托管 |
| 18 | `com.guosen.trademodule.ggt` | 港股通 |
| 17 | `com.guosen.trademodule.yzzz` | 要约收购 |
| 15 | `com.guosen.trademodule.zczl` | 资产证券化 |
| 15 | `com.guosen.strategytrade.ui` | **策略交易** |
| 12 | `com.guosen.trademodule.other` | 其他交易 |
| 11 | `com.guosen.trademodule.zqzq` | 债券专区 |
| 11 | `com.guosen.trademodule.hgqlt` | 沪港通 |
| 11 | `com.guosen.trademodule.gpzy` | 股票质押 |
| 10 | `com.guosen.trademodule.repo` | 国债逆回购 |
| 8 | `com.guosen.trademodule.fund` | 基金 |
| 8 | `com.guosen.quotation.search` | 行情搜索 |
| 8 | `com.guosen.loginmodule.ui` | 登录 |
| 7 | `com.guosen.trademodule.xxpf` | 新股配发 |
| 7 | `com.guosen.trademodule.plmc` | 盘后交易 |
| 6 | `com.guosen.trademodule.holding` | 持仓 |
| 5 | `com.guosen.quotation.pickstk` | 选股 |
| 4 | `com.guosen.quotation.etfzone` | ETF 专区 |
| 4 | `com.guosen.quotation.dayhigh` | 涨停 |
| 4 | `com.guosen.quotation.aistare` | AI 星选 |
| 4 | `com.guosen.newgatoa.ui` | 办公 |
| 3 | `com.guosen.quotation.stockpk` | 股票 PK |
| 3 | `com.guosen.quotation.discover` | 发现 |
| 2 | `com.guosen.ocr.idcard` | 身份证 OCR |
| 2 | `com.guosen.ocr.bankcard` | 银行卡 OCR |

### 主壳页面（`com.guosen.app.goldsun.*`，24 个）

```
com.guosen.app.goldsun.ui.MainAct                          <- 主界面
com.guosen.app.goldsun.ui.home.EditMenuHybirdAct           <- 编辑首页菜单（可自定义入口）
com.guosen.app.goldsun.ui.home.OnlineServiceAct            <- 在线客服
com.guosen.app.goldsun.ui.home.ChangeCityAct
com.guosen.app.goldsun.ui.home.ChangeLoginPwdAct
com.guosen.app.goldsun.ai.AiMainAct                        <- AI 助手
com.guosen.app.goldsun.ai.aisetting.AiSettingAct
com.guosen.app.goldsun.ai.quote.QuoteListAct
com.guosen.app.goldsun.message.ui.MsgCenterAct             <- 消息中心
com.guosen.app.goldsun.message.ui.MsgListAct
com.guosen.app.goldsun.message.ui.MsgSettingAct
com.guosen.app.goldsun.message.ui.MyFavoriteAct
com.guosen.app.goldsun.financial.ZxgFundEditAct
com.guosen.app.goldsun.permissionsmgr.ui.PermissionsMgrAct
com.guosen.app.goldsun.ui.trade.tradelogin.CommonOrgSearchAct
```

---

## 四、H5 层：另一个大头

`assets/h5Cache/h5_cache_zip.zip`（16 MB）**未加密**，解压后 2773 个文件、57.7 MB。
说明 App 大量二级/三级页面是 WebView 承载的 H5，不是原生页面。

```
h5_cache_zip/
├── act/actcenter/          活动中心
├── asset/                  资产与财富模块（最大）
│   ├── account/            账户分析、盈亏、交割单
│   ├── mfund/ mfdt/ mfinancial/ mftransaction/   基金系列
│   ├── goldsun_mtrade/     交易
│   ├── webF10/             个股 F10
│   ├── news/ morning_news/ 资讯
│   ├── invest_college/     投教
│   ├── robot_ask/          AI 问答
│   ├── stock_applay/       打新
│   ├── openAct/            开户
│   └── ...（共 33 个）
├── mcjg/                   模拟炒股
└── xcf/                    营业部/产品
```

**这对开发策略有直接影响**：H5 页面的 hook 对象是 WebView（注入 JS / 拦截 URL），
不是 Java 方法。原生页面才走 Xposed 方法 hook。两条技术路线要分开处理。

---

## 五、可行性结论

### 能做

- ✅ 拿到完整页面地图（Manifest 明文 + H5 源码）
- ✅ 运行时定位任意页面（`dumpsys activity` 直接给出真实类名，不受加密影响）
- ✅ 运行时拿到任意页面的控件树（`uiautomator dump`，不受加密影响）
- ✅ 编写、编译、签名 LSPosed 模块 APK
- ✅ 页面跳转类功能（`startActivity` 直达目标页）
- ✅ UI 增强类功能（插入按钮、改控件属性、快捷入口）
- ✅ H5 层注入（WebView hook + JS）

### 不能做 / 高风险

- ❌ **无法静态反编译**。真实类名、方法签名都在加密载荷里，拿不到。所有
  "hook 某个业务方法"的需求都必须先通过运行时手段定位。
- ⚠️ **梆梆有反 Xposed 能力**。虽然 `.so` 里的字符串也被加密了，扫不到明文的
  `xposed` / `frida` 特征，但梆梆企业版的官方功能清单就包含防注入。**金融类 App
  大概率开启。最坏结果：装上 LSPosed 后 App 直接闪退或拒绝登录。**
  → 这必须第一个验证，否则后面全白做。
- ⚠️ **SOPHIX 虚拟机保护**。关键方法可能被抽成自研 VM 字节码，Java 层 hook
  拿到的是空壳，改不了逻辑。
- ⚠️ **证券类业务红线**（见下）。

---

## 六、红线

**不承接：**

- 篡改行情、成交、持仓等数据
- 绕过登录 / 身份认证 / 人脸识别
- 绕过风控、适当性管理、风险测评
- 自动化下单、程序化交易（涉及《证券法》与券商协议）
- 绕过任何付费、权限、会员限制

**可以做：**

- UI 便捷化：给埋得深的页面加快捷入口、页面直达、菜单自定义
- 信息展示增强：把已有数据换个方式呈现
- 个人数据整理：导出自己的交割单 / 持仓记录
- 调试辅助：日志、页面跳转工具

---

## 七、下一步

1. **先验证 LSPosed 能不能在这个 App 上活下来**（最高优先级，决定整个项目是否成立）
2. 抓取目标页面的 Activity 类名 + 控件树
3. 确定功能的技术路线（原生 hook / H5 注入 / 纯 Activity 跳转）
4. 搭模块工程

抓取工具：`tools/ui.sh`（列元素 / 点击，见 `tools/README.md`）与 `tools/probe/`
（运行时探针，见 `tools/probe/README.md`）。
