# 智能盯盘「一键配置」 实现方案

> 目标：**智能盯盘 → 价格** 这一页，进入时只有「最新价」和两个空输入框（`最新涨到` / `最新跌到`）。
> 在底部「保存」右边加一个**配置**按钮（配色取个股详情页「买」的底色），
> 点一下就把当前股票的两个提醒价**按用户自己搭好的方案**填进去。
> 方案在**模块自己的设置页**里维护：可以自由组合取值项、自由排列。
>
> 本文的**页面结构 / DOM 层级 / 入口配色 / H5 桥契约**已在真机
> （魅族 20 / Android 16 / 金太阳 8.6.0 vc271）实测确认，依据是
> `uiautomator dump` + LSPosed 探针（`com.wb.probe`）的运行时日志，不是推断。
> **§10 的 7 项待确认已于 2026-10-16 用探针 v33~v35 跑完**（结论逐项写在 §10，
> 仅第 5 项持仓成本需登录环境、待补），§7 改动清单可以照单开工。

---

## 0. 结论先行

| 问题 | 答案 |
|---|---|
| 价格页在哪个页面 | `com.guosen.quotation.aistare.ui.AIStareWebView`（**它自己是个 Activity**，H5 容器在其 `mWbContent` 字段上），不是原生页 |
| 价格页的 H5 路由 | `https://goldsunsh.guosen.com.cn:1445/earlywarning/setStrategy/1?color_type=black&cPrecise=2&userCode=<账号>&stockCode=<代码>.<市场>&categoryType=1`（生产域名 **goldsunsh**，2026-10-16 实测；文档早先写的 `goldsundg` 是测试环境） |
| 页面里有什么 | 证券代码 / `最新价:24.32 +9.45%` / `最新涨到：[输入价格]` / `最新跌到：[输入价格]` / `提醒时效：一次·长期` / 底部 `重置` `保存` |
| 「保存」是什么控件 | **H5 DOM 元素**（`uiautomator` 报成 `View` + `TextView`），底部操作条右半，宽 540px（半屏） |
| 入口配色 | **`#FF3A3B`** —— 个股详情页「买」按钮底色（真机取样，纯色无渐变；「卖」是 `#2BAF95`） |
| 怎么插进去 | 页面是 DOM → **注入 JS 在「保存」右边插一个同款按钮**，不碰原生视图树 |
| 怎么和原生通信 | 复用宿主的 H5 桥：`HybirdWebView#parserCommand` → `GsBridge#parserCommand`（签名见 §1.3）；自己再挂一个 `addJavascriptInterface`，不污染宿主的命令空间（宿主不禁用，见 §10-3） |
| 数据从哪来 | 两级：**① 宿主自己的行情数据**（首选，与 App 内一致）**② 公开行情 HTTP**（兜底）。详见 §5 |
| 方案存哪 | remote preferences 新增一个键，值是 JSON 字符串；进 `ConfigSnapshot`（目标进程要读） |
| 设置页在哪 | 模块 App（`SettingsActivity`）新增二级页「一键配置」，功能卡片挂**新分区「智能盯盘」** |

---

## 1. 真机契约（实测）

### 1.1 价格页的页面结构

`uiautomator dump` 原样（只留结构，坐标见括号）：

```
FrameLayout [0,0][1080,2400]
 └ FrameLayout #fl_main_page [0,116][1080,2330]
    ├ LinearLayout #common_top [0,116][1080,240]          ← 原生标题栏
    │   ├ ImageView #iv_top_back   "返回上一页"  [0,116][124,240]
    │   └ TextView  #tv_top_title  "价格"       [186,116][894,240]
    └ LinearLayout #ll_content [0,240][1080,2330]
       └ ViewGroup #ptr_web [0,240][1080,2330]            ← 宿主的下拉刷新壳
          └ FrameLayout #wb_content [0,240][1080,2330]
             └ WebView [0,240][1080,2330]                 ← ★ 从这里往下全是 H5
                └ WebView
                   └ View #app [0,240][1080,1713]         ← ★ DOM 根
                      ├ View            （证券代码块）
                      │   ├ TextView "证券代码："  [39,751][270,808]
                      │   ├ TextView "光威复材"    [348,729][534,785]
                      │   ├ TextView "300699"      [542,732][660,774]
                      │   └ TextView "最新价:24.32 +9.45%" [348,791][694,844]
                      ├ View            （涨到行）
                      │   ├ TextView "最新涨到："  [39,864][270,1038]
                      │   └ EditText (输入价格)    [348,920][697,982]
                      ├ View            （跌到行）
                      │   ├ TextView "最新跌到："  [39,1033][270,1207]
                      │   └ EditText (输入价格)    [348,1089][697,1148]
                      ├ View            （提醒时效行）
                      │   ├ TextView "提醒时效："  [39,1201][270,1373]
                      │   └ View > View
                      │       ├ Button "一次"  [725,1241][883,1334]
                      │       └ Button "长期"  [880,1241][1037,1334]
                      ├ TextView "如果设置长期有效，触发首次提醒后，最新价再次涨到/跌倒设置价格才会触发二次提醒。"
                      └ View            （★ 底部操作条）[0,2186][1080,2330]
                          ├ View clk > TextView "重置"  [216,2228][323,2290]   ← 左半 540px
                          └ View clk > TextView "保存"  [756,2228][863,2290]   ← 右半 540px
```

**三条关键结论：**

1. **「保存」不是原生控件**，是 H5 的 `<div>`（`uiautomator` 把它报成 `View` + 内部 `TextView`）。
   所以「在保存右边加一个按钮」**只能靠改 DOM**，往原生视图树里 `addView` 是加不到它旁边的。
2. 底部操作条是**两个等宽（各 540px）子节点**的横向容器。插第三个之后必须把三个都改成
   `flex:1`（各 360px），否则新按钮会被挤出去或叠在「保存」上。
3. 页面 URL 里带 `stockCode=<代码>.<市场>` —— **当前股票不用从 DOM 里猜**，读 WebView 的 URL 就有。

> ⚠️ 页面顶部那张折线图是**静态示例**（图上写着「示例」），不是该股票的真实行情，
> 别想着从图上读数据。

### 1.2 入口配色：个股详情页「买」= `#FF3A3B`

个股详情页（`StockFragAct`）底栏「买」按钮 `#fl_buy [28,2204][249,2317]`，
真机截图逐点采样（`tools/sample_colors.py` + `_color.py` 精确取像素）：

| 采样点 | 颜色 |
|---|---|
| 买 · 水平扫描 `y=2215`，`x=35/80/140/200/245` | `#FF3A3B` ×5（纯色，无渐变） |
| 买 · 垂直扫描 `x=40`，`y=2210/2240/2280/2310` | `#FF3A3B` ×4 |
| 卖 · 中心 | `#2BAF95` |

→ 配置按钮底色写死 **`#FF3A3B`**，文字白色。

### 1.3 H5 ↔ 原生桥（我们要复用的东西）

探针抓到的调用栈（两条入口，汇到同一个漏斗）：

```
① 页面跳 URL scheme
   com.guosen.commonui.webview.HybirdWebView$4#shouldOverrideUrlLoading:378
     └ HybirdWebView#parserCommand:1166
         └ com.guosen.module.hybrid.GsBridge#parserCommand:43
             └ com.guosen.commonui.webview.HybirdAct$1#doParser:70
                 └ com.guosen.quotation.aistare.ui.AIStareWebView#parserMsg:74

② 页面调 JS prompt()
   com.guosen.commonui.webview.HybirdWebView$5#onJsPrompt:621
     └ （同上：parserCommand → GsBridge → doParser → 页面自己的 parserMsg）
```

有用的四个事实（探针 v33 实测补全，方法签名以 CLASS dump 为准）：

- 容器类是 **`com.guosen.commonui.webview.HybirdWebView`**（`AIStareWebView` 的 `#ptr_web` 里就是它）；
  它继承 `NestedHybridWebView`。**`HybirdWebView#getWebView()`（无参）直接返回 `android.webkit.WebView`**
  （另有 `getBasicWebView(Context)` 与字段 `m_webView`，见 §10-1）。
  价格页的 Activity 就是 **`com.guosen.quotation.aistare.ui.AIStareWebView`**（它自己是 Activity，
  顶栏代码/名称是原生控件 `m_tvStkCode` / `m_tvStkName`，H5 容器在字段 `mWbContent` 上）。
- `GsBridge` 是 Kotlin 单例（`getInstance()`），漏斗签名
  **`public final int parserCommand(String command, String url, HybirdCallBack, IHybridParser)`**；
  `HybirdWebView#parserCommand(String, String)`（protected）。
  `GsBridge` 里的 **`showCommandError(String)`** —— 未知命令会弹错误 UI
  （dump 过滤器没覆盖到这个方法名，但历史上探针栈实录过，红线不变）。
  **所以我们的桥命令不能丢进宿主的 `GsBridge` 里**，必须在漏斗**之前**截下来。
- 桥命令在两个地方都能被截：`shouldOverrideUrlLoading`（URL scheme）与 `onJsPrompt`（`prompt()`）。
  命令格式实录（保存标题动作）：`guosen://WebContainer/titleItem/?params={"title":"价格","bgColor":"212634"}`。
- 底部条「重置/保存」是 **`<div class="clk">`**（uiautomator 报成 `View clk`），每半宽 540px ——
  与 §1.1 的 dump 一致。

### 1.4 股票标识：`<代码>.<市场>`，市场号 1=深圳 / 2=上海

同一批探针日志里两次跳转的 `stockCode`：

```
华森制药 002907（深市） → stockCode=002907.1
光威复材 300699（深市） → stockCode=300699.1
上证指数 000001（沪市） → stockCode=000001.2
```

H5 自己发的行情请求也印证了这个映射（`POST /gsnews/market/queryStkQuote/1.0`，body 是逗号分隔的）：

```
code=601811.2,601811.2,002907.1,600519.2,300300.1,600585.2
      沪(国泰海通)        深(华森)  沪(茅台)  深(汉鼎)  沪(海螺)
```

→ **1 = 深圳，2 = 上海**。这个映射后面取数时要用（公开接口的 `sh`/`sz` 前缀、东财的 `secid` 都靠它换算）。

### 1.5 顺带确认的两件事

- H5 资源服务器是 `goldsunsh.guosen.com.cn:1445`（生产环境，实测；
  早先记录的 `goldsundg` 是测试环境，`/asset/...` 是静态资源）。
- 盯盘自己的后端接口是 **`POST https://goldsunsh.guosen.com.cn:1445/earlywarning/api/queryStrategy`**
  （保存走同类接口，不经 H5 桥 —— 见 §10-6）。

---

## 2. 整体设计思路

分三层，**层与层之间只有一个明确的接口**，任何一层坏掉都不会把另外两层带崩：

```
┌─ ① 注入层（H5 里，JS）──────────────────────────────────────────────┐
│  在价格页 DOM 的「保存」右边插一个「配置」按钮（#FF3A3B）             │
│  点击 → 弹方案选择层（纯 JS 自绘，不跳 App）                          │
│  选中方案 → 调桥 gsaux.apply(方案id)                                 │
│  收到原生回填 → 把值写进两个 <input>，并派发 input 事件让宿主自己校验 │
└──────────────────────────────────────────────────────────────────────┘
                    │  addJavascriptInterface("gsaux") / 自建 scheme
                    ▼
┌─ ② 计算层（目标进程，Kotlin）────────────────────────────────────────┐
│  AiStareQuickConfigFeature：拿到 code.market → 问取数层要数据         │
│  QuickConfigEngine：按方案里的取值项 + 聚合方式算出「涨到」「跌到」    │
│  回填：webView.evaluateJavascript("window.__gsaux.fill(12.34,11.22)") │
└──────────────────────────────────────────────────────────────────────┘
                    │  QuoteRepository（可换实现）
                    ▼
┌─ ③ 取数层（目标进程）────────────────────────────────────────────────┐
│  QuoteFetcher（编排）：按方案需求决定取快照 / 日 K，逐级降级          │
│  HostQuoteSource（首选，只给快照）：宿主自己的行情数据                 │
│  PublicQuoteSource（主通道）：腾讯公开行情 HTTP                       │
│  EastmoneyQuoteSource（第二家）：东财公开行情                         │
│  DomQuoteSource（零成本兜底）：从页面上的「最新价:24.32」正则抠出来   │
└──────────────────────────────────────────────────────────────────────┘
                    ▲
                    │  方案（JSON 字符串，走 remote preferences）
┌─ ④ 配置层（模块 App，Compose）──────────────────────────────────────┐
│  功能卡片「增加 配置 按钮」→ 二级页「一键配置」                        │
│  方案列表（可增删改排序、设默认）+ 取值项目录 + 编辑页                 │
└──────────────────────────────────────────────────────────────────────┘
```

**为什么把「算」放在原生、不放在 JS 里：**

- 数据在原生侧（宿主的行情通道、或模块自己的网络请求），JS 拿不到；
- 计算规则（涨停幅度、复权口径、取整）要能随 App 升级单独改，不该埋进注入脚本；
- 注入脚本越薄越不容易被宿主的 H5 改版打坏。

**为什么方案存在 remote preferences：**

- 目标进程要读方案才能算，而 remote preferences 是模块与目标进程之间唯一可靠的通道
  （`ConfigSource` 已经用它，加一个 `String` 字段即可，见 §7）；
- 顺带白拿「备份 / 恢复 / 重置」——只要把键登记进 `ConfigKeys.ALL_KEYS` 与 `DEFAULTS`。

---

## 3. 页面交互

### 3.1 入口：底部操作条三等分

```
注入前： ┌─────────────── 重置 ───────────────┬─────────────── 保存 ───────────────┐
         │            540px                  │            540px                  │

注入后： ┌──────── 重置 ────────┬──────── 配置 ────────┬──────── 保存 ────────┐
         │        360px         │  360px  #FF3A3B      │       360px          │
```

- 「配置」底色 `#FF3A3B`、文字白、圆角与字号**从「保存」的 `getComputedStyle` 抄**，
  只覆盖 `background-color` / `color` —— 这样宿主的 H5 改版换了皮肤，我们跟着变，不会突兀。
- 容器子节点统一改成 `flex:1 1 0`，`重置`/`保存` 的行为、事件一个字节都不动。
- 位置在「保存」**右边**（用户要求的），不是最右 —— 最右是宿主的主操作位，抢它更显眼也更容易被误触。

### 3.2 点击行为

```
点击「配置」
  │
  ├─ 方案数 == 1  → 直接套用（不弹层，最快路径）
  │
  └─ 方案数 ≥ 2  → 从底部升起方案选择层（JS 自绘）
       ├ 列出全部方案：名称 + 一行预览（涨到=30日最高价 / 跌到=30日最低价）
       ├ 点某项 → 套用并收起
       └ 末项「管理方案…」→ 桥通知原生 → 拉起模块 App 的二级页

套用（原生侧）：
  1. 取 code.market（从 webView.url 解）
  2. QuoteRepository 取数 → QuickConfigEngine 算两个价
  3. evaluateJavascript("__gsaux.fill(涨到, 跌到)")
  4. JS 写值 → 手动派发 input/change 事件 → 宿主自己的校验逻辑接管
```

**为什么必须手动派发 `input` / `change` 事件**：宿主的「保存」按钮在输入框为空时是**禁用**的
（真机截图里它是灰的）。直接改 `input.value` 不会触发宿主的监听，按钮会一直是灰的、
点了没反应。必须让宿主的 JS 认为「用户输了数」。

**套用后不自动点「保存」**：落库是宿主的动作，用户可能想先改一改再存。
自动保存一旦算错就是直接写入盯盘策略，代价比多点一下大得多。

**取数失败不写值**：任何一个取值项算不出来（接口挂了、停牌、无 K 线），
就**整体放弃**并在页面顶部弹一行轻提示（JS 自绘，2 秒淡出），绝不写半截或 0。

### 3.3 顺手把价格页的体验问题一起修掉

价格页现在的实际体验问题（真机看到的）：

| 问题 | 现象 | 改法 |
|---|---|---|
| 输进去不知道离现价多远 | 「最新涨到」填 30 日高，用户得自己心算差多少 | ~~输入框右侧补一行小字~~ **宿主 2026-10-16 实测已原生自带**（输入框下方有「较最新价+136.22%」，随输入实时更新），**这项不要重复做** |
| 方向填反没人管 | 涨到填得比最新价还低、跌到填得比最新价还高，照样能保存 | 方向不对时输入框描边变 `#FF3A3B`，并在下方补一行「涨到应高于最新价」 |
| 键盘顶起时底部条被遮 | 输入框获得焦点后，底部 `重置/保存` 被软键盘盖住 | 给底部条加 `position:sticky` 的兜底（只在检测到视口高度变化时生效），不改宿主布局 |
| 空态不知道能填什么 | 两个框都是 `输入价格`，没有参考值 | 用 `placeholder` 提示参考值（如 `30日高 18.47`），仅在方案已配置时填 |

> 这四条都是**纯 JS、纯展示**，不引入取数、不改宿主行为，属于低风险改动。
> 「一键配置」是主菜，这四条是顺手的配菜 —— 分开做、分开开关更好回滚。

---

## 4. 配置项结构

### 4.1 数据模型

```
Scheme（方案）
 ├ id        : String    稳定标识（新建时生成，改名不变）
 ├ name      : String    方案名（用户可改）
 ├ up        : Slot      「最新涨到」槽
 ├ down      : Slot      「最新跌到」槽
 ├ validity  : ONCE | LONG   （可选）顺带设置「提醒时效」；null = 不动宿主
 └ isDefault : Boolean   默认方案（全局唯一）

Slot（槽）
 ├ terms : List<Term>    取值项，**有序**（排列）
 └ agg   : MAX | MIN | AVG   （可选）多个取值项的聚合方式；默认 MAX（涨到）/ MIN（跌到）

Term（取值项）
 ├ source : String      取值项目录里的 id（见 §4.2）
 └ param  : Map<String,String>   该项需要的参数（如 period=30、pct=5）
```

**「自由组合与排列」落在哪：**

- **组合** = 一个槽里放**多个** `Term`，用 `agg` 聚合（取最大 / 取最小 / 取平均）。
  例：`涨到 = MAX(30日最高价, 涨停价)` —— 既照顾近期压力位，也不越过涨停。
- **排列** = `Slot.terms` 有序（编辑页可拖动排序），`Scheme` 列表也有序。
  单值场景下顺序无影响，但多值时「第一个」会被当作展示用的主项（方案预览行只显示第一个）。

### 4.2 取值项目录

`source` 的取值、含义、需要什么参数、怎么算 —— **这张表就是设置页里「添加取值项」的可选清单**：

| id | 名称 | 分类 | 参数 | 取数与计算 |
|---|---|---|---|---|
| `latest` | 最新价 | 实时 | — | 实时快照的最新成交价 |
| `prevClose` | 昨收价 | 实时 | — | 实时快照的昨收 |
| `open` | 今开价 | 实时 | — | 实时快照的今开 |
| `dayHigh` | 今日最高 | 实时 | — | 实时快照的当日最高 |
| `dayLow` | 今日最低 | 实时 | — | 实时快照的当日最低 |
| `limitUp` | 涨停价 | 实时 | — | `round(昨收 × (1+幅度), 2)`，幅度见下 |
| `limitDown` | 跌停价 | 实时 | — | `round(昨收 × (1−幅度), 2)` |
| `cost` | 持仓成本价 | 持仓 | — | 宿主持仓里的成本价（无持仓则该项不可用） |
| `highN` | N 日最高价 | 区间 | `period` | `max(最高价)` over 最近 N 个交易日 |
| `lowN` | N 日最低价 | 区间 | `period` | `min(最低价)` over 最近 N 个交易日 |
| `highCloseN` | N 日最高收盘 | 区间 | `period` | `max(收盘价)` over 最近 N 个交易日 |
| `lowCloseN` | N 日最低收盘 | 区间 | `period` | `min(收盘价)` over 最近 N 个交易日 |
| `historyHigh` | 最近前高 | 区间 | — | **离今天最近的那个「顶」**：创新高（≥ 前一天高点）后回落（≥ 后一天高点），且高于今日最高（见 §5.3） |
| `historyLow` | 最近前低 | 区间 | — | **离今天最近的那个「底」**（三项全部反向） |
| `maN` | MA N | 均线 | `period` | `avg(收盘价)` over 最近 N 个交易日，保留 2 位 |
| `upPct` | 最新价 +p% | 幅度 | `pct` | `round(最新价 × (1+pct/100), 2)` |
| `downPct` | 最新价 −p% | 幅度 | `pct` | `round(最新价 × (1−pct/100), 2)` |
| `prevUpPct` | 昨收 +p% | 幅度 | `pct` | `round(昨收 × (1+pct/100), 2)` |
| `prevDownPct` | 昨收 −p% | 幅度 | `pct` | `round(昨收 × (1−pct/100), 2)` |
| `fixed` | 固定价 | 固定 | `value` | 用户直接填的数字 |

**`period` 可选值**：`5 / 10 / 20 / 30 / 60 / 120 / 250`（交易日）。
**`pct` 范围**：`0.1 ~ 30`，步进 0.1。

**涨停 / 跌停价**（兜底口径：按代码前缀判定，四舍五入到 0.01）：

| 板块 | 代码前缀 | 幅度 |
|---|---|---|
| 沪市主板 | `60` | 10% |
| 深市主板 | `00` | 10% |
| 创业板 | `30` | 20% |
| 科创板（含 689 CDR） | `68` | 20% |
| 北交所（含新代码段 920） | `4` / `8` / `920` | 30% |
| 指数 / 无涨跌幅标的 | — | 判失败（不编名义价） |

> **这张表只是兜底**（DOM 通道才会用到）。主路径的涨跌停价直接取行情接口给的
> **官方字段**（腾讯 47/48、东财 f51/f52）—— 接口返回 `-1`（新股上市首日、
> 退市整理期、停牌）时该项判失败，而不是编一个名义价。
>
> **ST 不再单列一档**（2026-10-17 真机对拍修正）：旧表写「主板 ST 5%、
> 创业板/科创板 ST 12%」与实盘全对不上 —— 沪深主板风险警示股票涨跌幅
> **2026-07-06 起由 5% 调整为 10%**（与主板普通股一致，交易所正式公告）；
> 12% 从来不是涨跌幅（那是 ST 连续三日偏离 ±12% 的**异常波动**指标，当年抄串了），
> 创业板/科创板 ST 的涨跌幅一直是 20%。实测：*ST起步 603557 昨收 2.99 →
> 涨停 3.29（10%）；*ST开元 300338 昨收 2.68 → 涨停 3.22（20%）。
> 引擎里的 `name` 参数仅为签名兼容保留，不再参与判定。

**默认方案（出厂值）**，就是用户举的那个例子：

```
方案「30 日高低」
  涨到 = highN(period=30)
  跌到 = lowN(period=30)
```

### 4.3 存储格式

一个 `ConfigKeys.AISTARE_QUICK_CONFIG` 键，值是 JSON 字符串：

```json
{
  "v": 1,
  "defaultId": "s1",
  "schemes": [
    {
      "id": "s1", "name": "30日高低", "validity": null, "isDefault": true,
      "up":   { "agg": "MAX", "terms": [ { "source": "highN", "param": { "period": "30" } } ] },
      "down": { "agg": "MIN", "terms": [ { "source": "lowN",  "param": { "period": "30" } } ] }
    }
  ]
}
```

- `v` 是版本号，解析时**不认识 `source` 的项直接丢弃**（而不是整条方案作废）——
  App 升级加了新取值项、用户降级回来，方案还能用。
- 解析失败（JSON 坏了）→ 回落默认方案 + 一条 warn 日志，**绝不崩**。

---

## 5. 数据获取逻辑

### 5.1 数据需求只有两类

| 类别 | 谁需要 | 形态 |
|---|---|---|
| **实时快照** | `latest` `prevClose` `open` `dayHigh` `dayLow` `limitUp` `limitDown` | 一个 `(最新, 昨收, 今开, 高, 低, 名称)` 的元组 |
| **日 K 序列** | `highN` `lowN` `highCloseN` `lowCloseN` `maN` | 最近 ≥ 250 根 `(日期, 开, 高, 低, 收)`，按时间升序 |

`upPct`/`downPct`/`prevUpPct`/`prevDownPct`/`fixed` **不需要取数**，纯算术。
`cost` 走宿主持仓数据。

→ 所以取数层只要两个方法：

```kotlin
interface QuoteRepository {
    fun snapshot(code: String, market: String): Snapshot?          // 实时快照
    fun dailyKline(code: String, market: String, n: Int): List<Bar>? // 日K，升序
}
```

### 5.2 四个取数通道与降级

```
QuoteRepository
 ├ ① HostQuoteSource     ★ 首选：宿主自己的行情数据（**只给实时快照**）
 │     优点：与 App 内显示的完全一致；不需要额外的网络权限；不引外部依赖
 │     缺点：类名/方法名要逆向（DEX 加密，只能运行时探针拿）
 │
 ├ ② PublicQuoteSource   ★ 主通道：腾讯公开行情 HTTP（快照 + 日 K）
 │     优点：稳定、文档化、跟宿主版本无关
 │     缺点：口径（复权/时间戳）与宿主可能有细微差
 │
 ├ ③ EastmoneyQuoteSource ○ 第二家公开行情：东方财富（快照 + 日 K）
 │     存在的理由：腾讯是唯一外部依赖，它限流/抽风时至少还有一家能给日 K
 │
 └ ④ DomQuoteSource      ○ 零成本兜底：从页面上正则抠（**只给最新价**）
       页面本来就写着「最新价:24.32 +9.45%」→ 只能救 `latest`，
       昨收可以用 `latest / (1+涨跌幅)` 反推（有误差，仅当①②③都挂时才用）
```

降级顺序：**① → ② → ③ → ④**，每一级失败都记一条明确日志（`GLog.w`），
并把「本次用的是哪几级」带回给 JS，让按钮上能显示一行极小的来源提示（可选）。

**②的具体接口（写死在这里，便于复核）**：

```
实时：https://qt.gtimg.cn/q=<mkt><code>              mkt = sh|sz      （响应是 GBK！）
      → 响应是按 ~ 分隔的文本，字段 3=最新价 4=昨收 5=今开 33=最高 34=最低 1=名称 2=代码

日K ：https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=<mkt><code>,day,,,320,
      → data.<mkt><code>.day / .qfqday 是 [[日期, 开, 收, 高, 低, 量], ...]
```

**③的具体接口**（`fltt=2` 必须带，不带的话价格是放大 100 倍的整数）：

```
实时：https://push2.eastmoney.com/api/qt/stock/get?fltt=2&secid=<0|1>.<code>
      → f43 最新 / f44 最高 / f45 最低 / f46 今开 / f57 代码 / f58 名称 / f60 昨收
日K ：https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=<0|1>.<code>
      &klt=101&fqt=0&lmt=<n>&end=20500101&fields1=f1,f2,f3,f4,f5,f6
      &fields2=f51,f52,f53,f54,f55,f56,f57
      → data.klines = ["日期,开,收,高,低,量,额", ...]，fqt=0 不复权
```

`market` → `mkt` 的换算（呼应 §1.4）：**`1` → `sz`，`2` → `sh`**；
东财的 `secid` 前缀：**`1` → `0`，`2` → `1`**。认不出的市场号**不猜**
（猜错会拿到另一只股票的价，比取不到更糟）。

**关于「同一来源」**：如果同一个方案里既有实时项又有日K项，两者应尽量走**同一个通道**
（都由 ① 或都由 ③），避免「最新价是宿主的、30 日高是腾讯的」这种口径混搭。

**2026-10-17 修正（原「一次会话内全局锁定通道」是错的）**：旧实现把「第一个给出
数据的通道」锁死，之后别的一律不试。主路径（个股详情页 → 智能盯盘 → 价格）下
宿主 `StockInfo` 缓存命中，快照把通道锁在 **① host**，而 host 按设计给不了日 K
（§10-4）—— 默认方案「30 日高低」必然判「取数失败」；这也是「经常取不到数据」
的主因。现行规则：

1. **按能力挑通道**：方案要日 K 时，快照优先在「两样都能给」的通道里挑（②③），
   都不行才退回只给快照的（①④）；
2. **同通道优先、失败继续降级**：上一次成功的通道排最前，但它失败就试下一级，
   不再一锤定音；
3. **按需取数**：方案里没有实时项就不打快照接口；`historyHigh/Low` 只需要日 K
   （返回值天然在今日高点之上 / 低点之下，不需要现价参与判断）。

### 5.3 各项的计算口径

| 项 | 口径说明 |
|---|---|
| `highN` / `lowN` | 用**最高价 / 最低价**列，不是收盘价列（这是行情软件「N 日最高」的通行口径） |
| `highCloseN` / `lowCloseN` | 用**收盘价**列 |
| `maN` | 简单算术平均（`avg`），**不是**指数移动平均 —— 与行情软件默认的 MA 一致 |
| `historyHigh` / `historyLow` | **最近的局部顶 / 底，且尚未被今天突破**。一个价位算前高，要同时满足：① 它自己创新高（高点 ≥ 前一天）；② 之后回落（高点 ≥ 后一天）；③ 高于今日最高。从最新一根往回扫，第一个满足的就是答案（时间上最近的最优先）。**不是**历史极值（茅台 2627.88 不参与），**不是**左右各 5 根确认的波段峰（会把中芯 118.18 顶成 125.61），也**不是**「高于其后所有高点」（会把中芯下跌途中的反弹 105.81 当成顶）。实测：中芯国际 118.18（09-30）、茅台 1338.86（09-04）、平安银行 11.89（10-09）。今天就是历史最高（最低）时**判失败** |
| 复权 | **不复权**。用户设「涨到」时脑子里是行情软件上那个价位，复权价会和显示对不上 |
| `limitUp`/`limitDown` | `round(昨收 × 系数, 2)`；深沪都是四舍五入到分 |
| 所有输出 | 统一 `BigDecimal`，`setScale(2, HALF_UP)`，**不要用 `Double` 直接乘** —— `1.1*14.29` 在浮点下会出 `15.718999…` |

**N 的口径**：`highN` 的 N 是**交易日**，且**含今日**（当日最高也参与比较）——
这样「30 日最高价」在盘中创新高时会跟着抬，符合「涨到」的语义。
若用户想要「不含今日」，用「N 日最高收盘」+ 不含今日的变体即可（后续可加参数）。

**停牌 / 数据不足**：`kline.size < N` 时该项**判失败**（不是用现有数据凑）——
宁可整体放弃回填，也不给一个用户看不懂的数。

### 5.4 缓存与刷新

- **日K**：按 `code.market` 缓存 5 分钟（`LruCache`，容量 16）。盯盘设置是低频动作，
  5 分钟足够，避免用户连点方案时反复打接口。
- **实时快照**：不缓存，每次套用现取（最新价必须是当下的）。
- **网络请求**：一律在子线程（`Dispatchers.IO` / `HandlerThread`），
  结果回主线程再 `evaluateJavascript`（WebView 的 JS 求值必须在它自己的线程上）。
- **超时**：3 秒。超时按「该项失败」处理，走 §5.2 的降级链。

---

## 6. 设置页（模块内）

### 6.1 入口与路由

- 模块 App 的功能卡片新增一条：**`增加 配置 按钮`**（起名遵循 `docs/NAMING.md` §1 规则三：
  `增加 + 内容 + 控件类型`，内容取界面上的名字「配置」）。挂**新分区「智能盯盘」**。
- 卡片打开后展开二级入口 → `SettingsActivity` 新增 `Route.AiStareQuickConfig`。
- 从 H5 的「管理方案…」拉起来时，走同一个 Activity，但**带一个 Intent extra 直达这个二级页**
  （`SettingsActivity` 目前不吃 extra，要加一小段：读 `intent.getStringExtra("route")` 决定初始 `route`）。

### 6.2 方案列表页

```
┌─ 方案 ────────────────────────────────────────┐
│  30 日高低                      [默认] ⋮ ›   │
│  涨到 30日最高价 · 跌到 30日最低价             │
│  涨停 / 跌停                          ⋮ ›   │
│  涨到 涨停价 · 跌到 跌停价                    │
│  反弹（最新价 ±5%）                    ⋮ ›   │
│  涨到 最新价+5% · 跌到 最新价−5%              │
│  ＋ 新建方案                                  │
│  周期按交易日计，价格四舍五入到分；…（脚注）   │
└───────────────────────────────────────────────┘
```

- 点行 → 进编辑页；行尾 `⋮` 弹菜单（设为默认 / 上移 / 下移 / 删除），
  顺序 = 选择层里的顺序。
- **这一页不再整列 §4.2 的取值项目录**（3.1.0 删）：18 项按分组铺开比方案列表
  还长，而那些名字在编辑页「添加取值项」的弹层里就有。卡底只留一句口径脚注。

### 6.3 方案编辑页

```
┌─────────────────────────────────────────────┐
│ [ 30 日高低                              ]  │  名称
└─────────────────────────────────────────────┘
最新涨到
┌─────────────────────────────────────────────┐
│ 多个取值项时                    取最大   ⌄  │  聚合
│ 30 日最高价               [ 30 日 ▾ ] ✕     │  周期项：点整行选，右端是胶囊
│ 最新价 +p%                      [ 5 ] ✕     │  数值项：行内输入
│ ＋ 添加取值项                               │
└─────────────────────────────────────────────┘
最新跌到
┌─────────────────────────────────────────────┐
│ 多个取值项时                    取最小   ⌄  │
│ 30 日最低价               [ 30 日 ▾ ] ✕     │
│ ＋ 添加取值项                               │
└─────────────────────────────────────────────┘
┌─────────────────────────────────────────────┐
│ 提醒时效                        不修改   ⌄  │
└─────────────────────────────────────────────┘
操作
┌─────────────────────────────────────────────┐
│ 设为默认                              [ ○ ] │
│ 删除方案                                  › │
└─────────────────────────────────────────────┘
```

> 「提醒时效」不再配一个同名的 `SmallTitle`（行标题已经就是它，见 docs/NAMING.md）。
>
> 带周期的行右端是一颗 **「30 日 ▾」胶囊**（主色淡底 + 圆角 + 主色文字与箭头）：
> 形态上就是「下拉选择」，比原来「一行主色文字 + 一颗孤零零的细箭头」更能看出
> 「点这里会弹出一份周期目录」。取值与交互没变 —— 仍是那 7 个周期、仍由整行点击弹锚定菜单。
> ✕ 与箭头同一列（中心都落在「距行右缘 10dp」），两者显式垂直居中（`endActions` 默认 Top 对齐）。

- 「添加取值项」弹出 §4.2 的目录（按分类分组），选中后展开该项的参数（周期 / 百分比 / 数值）。
- 参数随 `source` 变化：`highN` 只给「周期」，`upPct` 只给「百分比」，`fixed` 只给「数值」，
  `latest` 之类无参数项选中即完成。
- 编辑页**每改一下就写一次 remote preferences**（和现有二级页一致），不做「保存」按钮 ——
  少一个状态机，也避免用户改完忘了存。
- 删除方案：只剩一个方案时禁用（必须有默认方案可用）。

### 6.4 实时预览

编辑页顶部放一行预览：**用当前股票**（如果有的话）算出两个价显示出来。
拿不到当前股票就显示 `—`。这一行是**唯一**会把取数错误暴露给用户的地方，
文案写「取数失败」而不是「—」，否则用户会以为是我们算错了。

---

## 7. 模块侧改动清单

> 下表是 3.1.0 的**落地实况**（与最初设计的差异见行内说明）。
> 最重要的偏差：**方案模型 / 目录 / 引擎放进了 `core/` 而不是 `feature/aistare/`** ——
> 设置页（`ui/`）也要读写方案，而 `ui/` 不能引用 `feature/`（架构红线 3：
> 会把 `io.github.libxposed.api` 类型带进模块 App 进程）；这三件都是纯 Kotlin +
> `org.json`，放 `core/` 两处共用零风险。

| 文件 | 改动 |
|---|---|
| `core/QuickConfigStore.kt`（新增） | 方案数据模型 + JSON 编解码 + 容错（坏 JSON 回落出厂、未知 source 只丢该项）+ H5 拉起模块 App 的 Intent 契约常量 |
| `core/QuickConfigCatalog.kt`（新增） | 取值项目录（§4.2 那张表）：id / 分类 / 参数约束；展示名在 `strings.xml` 的 `quick_term_names`（按下标对应） |
| `core/QuickConfigEngine.kt`（新增） | 取值项计算（纯函数）：BigDecimal、涨停表（前缀 + ST）、区间校验；`QuoteSnapshot` / `QuoteBar` 数据类也在这里（取数层产出、引擎消费） |
| `feature/aistare/AiStareQuickConfigTargets.kt`（新增） | 全部常量：页面 URL 片段、桥名/命名空间、配色 `#FF3A3B`、宿主行情字段候选 + 实测说明 |
| `feature/aistare/AiStareQuickConfigFeature.kt`（新增） | 功能本体：`setWebViewClient` 动态抓子类 → 挂 `onPageFinished` → 注入 JS + 自建 `gsaux` 桥 → 取数调度（会话锁通道）→ 回填；`StockInfo` 构造器钩子喂宿主行情缓存 |
| `feature/aistare/AiStareJs.kt`（新增） | 注入脚本常量：找「保存」（文本+底部 1/4 双判据）→ 三等分 → 克隆改「配置」→ 弹方案层 → `fill()` 派发 input/change/blur → 方向校验 → `MutationObserver`+500ms 自检 |
| `feature/aistare/quote/QuoteRepository.kt`（新增） | `QuoteSource` 接口（含 `supportsSnapshot` / `supportsKline` 能力声明）+ `QuoteSession`（按能力挑通道、同通道优先但不锁定、失败逐级降级 —— 2026-10-17 修正了「全局锁定」那个致命缺陷） |
| `feature/aistare/quote/QuoteFetcher.kt`（新增） | 取数编排：按方案需求决定取快照 / 日 K，跑 `QuoteSession` + `QuickConfigEngine`，失败时把「快照=… 日K=… 涨到槽=… 跌到槽=…」写进日志 |
| `feature/aistare/quote/HttpTransport.kt`（新增） | 取数专用 HTTP：状态码判定、可恢复错误重试一次、连接/读超时分离、GBK 解码、显式 `disconnect()`、响应体封顶 4 MB |
| `feature/aistare/quote/HostQuoteSource.kt`（新增） | 宿主行情（反射）：挂 `StockInfo` 构造器缓存最近实例，代码对得上才用；**只给快照**（日 K 入口未确认，§10-4） |
| `feature/aistare/quote/PublicQuoteSource.kt`（新增） | 腾讯公开行情：实时（`qt.gtimg.cn`，**GBK**）+ 日 K（`web.ifzq.gtimg.cn`，不复权，5 分钟缓存）；代码回显校验、停牌行过滤 |
| `feature/aistare/quote/EastmoneyQuoteSource.kt`（新增） | 东财公开行情（第二家）：实时 `push2` + 日 K `push2his`（`fqt=0` 不复权）；`fltt=2` 让价格直接是元，另做代码回显与价位合理性校验 |
| `feature/aistare/quote/DomQuoteSource.kt`（新增） | 从页面文本正则抠「最新价:xx ±x%」，昨收反推；只能救 latest/prevClose |
| `core/ConfigKeys.kt` | 加 `AISTARE_QUICK_ENABLED`（开关，进 `FEATURE_KEYS`）与 `AISTARE_QUICK_CONFIG`（方案 JSON，进 `FEATURE_PARAM_KEYS`）及两者默认值，进 `ALL_KEYS` / `DEFAULTS` —— **开关与数据分两个键**：重置 = 关开关、恢复出厂方案，不清用户数据 |
| `core/ConfigSnapshot.kt` | 加 `aistareQuickEnabled: Boolean` + `aiStareQuickConfig: String`，进 `fingerprint`（乘数 2851 / 2879） |
| `core/ConfigSource.kt` | `read()` 补两行 |
| `feature/FeatureManager.kt` | `features` 列表加一行 |
| `ui/FeatureCatalog.kt` | 加一条 `FeatureSpec`（新分区 `category_aistare`）+ 更新顶部「分区归属标准」注释 |
| `ui/SettingsActivity.kt` | 加 `Route.QuickConfig` / `Route.QuickConfigEdit(schemeId)` + 二级页分支 + 读 Intent extra（`QuickConfigStore.EXTRA_ROUTE`）直达 + **二级页自带弹窗宿主**（锚定菜单 + 贴底确认，方案菜单需要） |
| `ui/QuickConfigScreen.kt`（新增） | 方案列表页：行（点进编辑）+ ⋮ 锚定菜单（设默认/上移/下移/删除）+ 新建 + 取值项说明卡 |
| `ui/QuickConfigEditScreen.kt`（新增） | 方案编辑页：名称 / 两槽（聚合 + 取值项增删 + 参数）/ 提醒时效 / 设默认 / 删除；每改即写 |
| `res/values/strings.xml`、`values-en/strings.xml` | 新分区名 + 功能名 + 二级页全部文案 + `quick_term_names` 数组（中英同步） |
| `app/DebugConfigProvider.kt` | 加 `aistare_quick_enabled`（int 0/1）与 `aistare_quick_config`（string）列（开发期脚本化） |
| `docs/NAMING.md` §4/§5、`docs/EXTENDING.md`、`docs/ARCHITECTURE.md` | 同步新分区与新功能（含候选名能力表、功能计数 12 → 13） |

**不动**：`GszxModule.kt`、`scope.list`、其余 12 个功能、`ConfigKeys.GROUP`。

> 新分区必须同步 `ui/FeatureCatalog.kt` 顶部注释、`docs/EXTENDING.md` 的「分区怎么选」、
> `docs/NAMING.md` §4 —— 这是 `FeatureCatalog` 注释里明写的约定。
> **刻意不把 `stock_bar_entry`（底栏智能盯盘入口）挪过来** —— 它改的是详情页底栏，
> 归「股票详情页」是对的；新分区只装「改盯盘页面本身」的功能。
>
> 与设计的另外两处有意偏差：
> ① §3.3 的四条「顺手改」只做了**方向校验**（纯 JS 零成本）——第 1 条宿主 H5
> 已原生自带「较最新价 ±x.x%」（§3.3 表内已标），键盘遮挡 sticky（第 3 条）
> 与 placeholder 参考值（第 4 条）风险收益比不划算，砍；
> ② §6.4 编辑页顶部「实时预览」没做 —— 模块 App 进程拿不到「当前股票」，
> 真实取数预览无法实现；列表页/弹层显示方案的一行文本预览（写入时按当前
> 语言生成存进 JSON），这是能诚实落地的最接近形态。

---

## 8. 风险与降级

| 风险 | 概率 | 对策 |
|---|---|---|
| H5 改版，底部条结构变了 | 中 | 找「保存」用**文本 + 结构双判据**（文本是「保存」且在页面最底部 1/4 区域内）；找不到只记 warn，不注入 |
| H5 改版，`保存` 的类名/CSS 全变 | 中 | 样式**全部从 `getComputedStyle(保存)` 抄**，不写死尺寸/字体；只覆盖底色与文字色 |
| 页面是 SPA，路由切换不重新加载文档 | 低（实测价格页是整页导航） | JS 侧挂 `MutationObserver` + 每 500ms 一次的「按钮还在不在」自检，掉线即补 |
| 宿主限制 `addJavascriptInterface` | 低 | 退回 `onJsPrompt` 前置拦截自建 scheme（`gsaux://`），**在 `GsBridge` 之前截走**，绝不触发 `showCommandError` |
| 未知命令把宿主的错误 UI 弹出来 | 中 | 红线：**我们的桥命令一律在漏斗前置截断**，原方法不调用 |
| 取数失败 | 中 | 整体放弃 + 页面顶部轻提示；**绝不写半截值** |
| 公开行情接口限流 / 不可达 | 中 | 两家公开行情（腾讯 / 东财）+ 5 分钟日K缓存 + 4~8 秒超时 + 可恢复错误重试一次 + 逐级降级 |
| 宿主的 `HybirdWebView` 改名 | 低 | 类名/方法名全进候选数组；`WebView` 实例还可以从 `WebViewClient` 钩子的第一个参数拿 |
| 算错价格写进盯盘策略 | 低但严重 | 写入前做**区间校验**（`0.01 < 价 < 昨收×1.5` 且 涨到 > 跌到），越界不写并报错 |
| 影响原版稳定性 | — | 所有视图/JS 操作包 `runCatching`；非价格页的 H5 页面一个字节都不碰 |

**降级链**：

```
完整（注入按钮 + 一键套用）
 → 桥不可用 → 按钮仍显示，点了只弹「暂时不可用」
 → JS 注入失败 → 不显示按钮（等同于没开这个功能），日志明确报
 → 取数全挂 → 按钮显示，点了弹「取数失败」（不写值）
 → 关开关 → 立即移除按钮与自绘层，页面回到原样（不重启）
```

---

## 9. 验收标准

| # | 验证项 | 期望 |
|---|---|---|
| 1 | 进价格页，底部条出现三等分的「重置 / 配置 / 保存」 | 三个各 360px，「配置」底色 `#FF3A3B` |
| 2 | 点「配置」（只有一个方案） | 两个输入框被填入 30 日最高 / 最低，且**「保存」按钮变为可用** |
| 3 | 点「配置」（多个方案） | 从底部升起方案层，列出全部方案 + 「管理方案…」 |
| 4 | 选另一个方案 | 输入框的值按新方案变化 |
| 5 | 「管理方案…」 | 拉起模块 App 并**直接停在「一键配置」二级页** |
| 6 | 编辑页改「30 日最高价」的周期为 60 | 返回价格页再点「配置」，值按 60 日算 |
| 7 | 断网后点「配置」 | 顶部提示「取数失败」，输入框保持原值不变 |
| 8 | 输入框右侧的「较最新价 ±x.x%」 | 随输入实时更新，与手算一致 |
| 9 | 涨到填得比最新价低 | 输入框描边变红并给提示 |
| 10 | 宿主「重置」行为 | 零变化，仍能清空 |
| 11 | 关开关 | 按钮与自绘层消失，页面与未装模块时一致 |
| 12 | 无新增崩溃 | `logcat -b crash` 中 `com.guosen.android` 计数 0 |
| 13 | 从盯盘首页来回切换 5 次再进价格页 | 按钮每次都出现（不是只在第一次） |

---

## 10. 待真机确认清单

> 这几项原本只有候选、没有实测。**2026-10-16 已用探针 v33~v35
> （`tools/probe/src/com/wb/probe/ProbeModule.java`，WebView 桥侦察组钩子）在真机
> （魅族 20 / Android 16 / 金太阳 8.6.0 vc271，华力创通 300045）跑完**，
> 结论回写在下面（除第 5 项需登录环境，待补）。

1. **`HybirdWebView` 拿 `WebView` 实例的方法名** —— ✅ 已确认：
   `HybirdWebView` 自己就声明了 **`public WebView getWebView()`（无参）**，
   另有 `public WebView getBasicWebView(Context)`（带 Context）与私有字段 `WebView m_webView`。
   父类 `NestedHybridWebView` 只有 `getBasicWebView(Context)`。
   **实现取 `getWebView()`，反射候选顺序：`getWebView` → `getBasicWebView(Context)` → 字段 `m_webView`。**
2. **`onPageFinished` 的挂载点** —— ✅ 已确认：宿主调
   `WebView#setWebViewClient` 挂的是 **`com.guosen.commonui.webview.HybirdWebView$4`**
   （`HybirdWebView#init:337` 处，extends `com.networkbench.agent.impl.instrumentation.NBSWebViewClient`，
   听云插桩基类）。`$4` 声明了 8 个覆写：`onPageFinished` / `onPageStarted` /
   `onReceivedError` / `onReceivedHttpError` / `onReceivedSslError` /
   `shouldInterceptRequest`×2 / `shouldOverrideUrlLoading`。
   **钩 `HybirdWebView$4#onPageFinished(WebView, String)`（匿名子类，必须按这个名字钩，钩基类拦不到）。**
3. **`addJavascriptInterface` 是否被宿主禁用/清理** —— ✅ 已确认：**不禁用**。
   宿主只 add 了分析 SDK 的两个桥（`SensorsData_APP_JS_Bridge`、`SensorsData_App_Visual_Bridge`）；
   remove 只删 `searchBoxJavaBridge_`（安全清理，`HybirdWebView#init:310`）。
   宿主自己的 GsBridge 命令**不走** `addJavascriptInterface`（走 `guosen://` scheme +
   `onJsPrompt` 双通道，见 §1.3），因此**自挂 `gsaux` 不会与宿主冲突、不会被宿主清理**。
   另注意 `HybirdWebView#setJSAble(boolean)` 存在（JS 桥总开关，宿主自己管，我们不动它）。
4. **宿主行情数据的类与方法（`HostQuoteSource`）** —— ✅ 已确认数据结构，入口走反射候选：
   - **实时快照：`com.guosen.android.quotes.data.StockInfo`**（BigDecimal 字段实测确认）：
     `stockPrice` 最新 / `zrsp` 昨收 / `jrkp` 今开 / `highPrice` 当日高 / `lowPrice` 当日低 /
     `stockName` 名称 / `zdf` 涨跌幅 / `codeAndMarket`（`代码.市场`）。
   - **日 K：`com.guosen.quotation.logicview.KXChartGroupView`** → 字段
     `m_kxChartInfo: com.guosen.android.quotes.data.kx.KXChartInfo` →
     **`List m_kxChartItems`**（升序 K 线）。元素 `KXChartItem`（CTOR `(String code, String market)`）字段：
     `m_date` / `m_openPrice` / `m_closePrice` / `m_highPrice` / `m_lowPrice` /
     `m_lastClosePrice`（**全 String**）；入口 `setData(KXChartInfo, int, int)`。
   - 反射失败一律降级 §5.2 的下一级通道，不阻塞主流程。
5. **`cost`（持仓成本）的来源** —— ⚠️ **未确认（需登录环境）**：
   候选类 `PositionInfo` / `HoldStockInfo` / `TCRSDataObject` 实测均 `ClassNotFoundException`；
   `StockInfo` 全字段里无成本价（`holdVal`/`holdVol` 系股东持股统计，非用户持仓）。
   持仓在交易模块，离线模式拿不到。**首版 `cost` 取值项按「无数据 → 该项失败 → 整体放弃」实现，
   等有登录环境再补侦察**（届时 dump 交易/持仓页的 Activity 字段）。
6. **价格页保存后跳去哪** —— ✅ 已确认：**不跳页、不发桥命令**。
   点「保存」后 H5 自己发 HTTP 落库，成功后弹**原生 Dialog「保存成功!」+「确定」**，
   点确定留在价格页。§3.2「套用后不自动点保存」的决策维持不变。
7. **页面是否真的整页导航** —— ✅ 已确认：**是**。盯盘首页 → 价格页的实际链路：
   H5 发桥命令 `guosen://WebContainer/transitionWeb/?params={"pageUrl":"…setStrategy/1?…"}`
   → 原生 `WebView.loadUrl(pageUrl)`（探针 WVB-LOADURL 实录），整个价格页重新加载。
   §8 的 `MutationObserver` 保持兜底定位（宿主 SPA 改版时才升级为主路径）。

---

## 11. 复现侦察的步骤（App 升级后照做）

```bash
# 1. 进个股详情页 → 底栏「智能盯盘」→ 点「价格」
bash tools/ui.sh tap "光威复材"        # 或用 §1.4 里的路径
bash tools/ui.sh tap "价格"

# 2. dump 价格页结构，确认底部条仍是两个 540px 子节点
bash tools/ui.sh list | tail -6

# 3. 拿 WebView 的 URL（确认 stockCode 与路由）
bash tools/sh.sh --root "cp /data/adb/lspd/log/modules_*.log /sdcard/mlog.txt"
bash tools/adbw.sh pull /sdcard/mlog.txt .work/mlog.txt
grep -a "earlywarning" .work/mlog.txt | tail -5
# 4. 看 H5 桥链路还在不在（类名/行号变了就要更新候选）
grep -aE "HybirdWebView|GsBridge|parserCommand" .work/mlog.txt | tail -5

# 5. 采「买」按钮的色（换皮肤时）
bash tools/adbw.sh shell "screencap -p /sdcard/s.png"; bash tools/adbw.sh pull /sdcard/s.png .work/s.png
python tools/sample_colors.py .work/s.png 60,2260,70,2300
```

拿到之后改 `feature/aistare/AiStareQuickConfigTargets.kt` 里的候选常量即可。

---

## 附：为什么不往原生视图树里插按钮

价格页在 WebView 里，H5 内容**没有原生 View 节点**（`uiautomator` 看到的是可访问性映射，
`WebView` 的 `getChildAt` 拿不到它们）。要在原生侧把它摆在「保存」右边，只能：

- 按 H5 的 DOM 坐标（`[756,2228][863,2290]`）算一个绝对位置叠上去 —— **页面一滚、一换机型、
  一换字号就全错**；而且 H5 的坐标拿不到（只能靠可访问性节点，不稳定）；
- 或者改 `WebView` 的 padding 腾地方 —— 那是在动宿主的布局。

两条都比「注入 JS 改 DOM」脆弱得多。**H5 的页面就用 H5 的方式改**，
这也是本项目一直以来的分界：原生页走视图注入，H5 页走脚本注入。
