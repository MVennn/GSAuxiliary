# 策略详情页「筛选股票」实现说明

> 版本：**2.13.0**（`versionCode 33`） · 功能 id：`stg_stock_filter`
> 目标 App：国信金太阳 **8.6.0**（vc271） · 实测设备：魅族 20 / Android 16 (SDK 36) / LSPosed 2.2.1
> 相关代码：[`feature/stgfilter/`](../app/src/main/java/gsauxiliary/m/venn/feature/stgfilter/)

---

## 一、需求

1. 策略详情页右上角三点菜单（「修改名称」/「删除策略」）里，**「修改名称」下方**新增
   `筛选股票`；
2. 点击弹出筛选页，页内提供 **涨幅 / 跌幅 / 自选** 三个可切换开关。
   **勾选项之间是彼此独立的类别**：未勾选的类别、其对应股票一律不显示 ——
   例如只勾「涨幅」时，自选里那些正在涨的股票**也不显示**；
   三个都不勾 = 不筛选（全显示）；
3. 被筛掉的股票要**真正从列表里移除**（滚动时不能留下空白占位）；
4. 筛选状态**本地持久化**，关掉软件重开仍在；
5. 筛选页**复用软件内部现有筛选组件的 UI**（行情页「沪深京A」左侧那颗漏斗），
   原「取消」按钮改成「重置」，「确定」保持不变。

---

## 二、三点菜单的真实结构（实测）

`uiautomator dump` 抓到的树（打开菜单时 uiautomator 只报弹层这一个 window）：

```
FrameLayout [812,204][1080,459]                     ← PopupWindow 的 decor
  └─ FrameLayout
       └─ LinearLayout [812,204][1080,459]          ← pop_ai_stg.xml 的根
            ├─ TextView #tv_edit_name  "修改名称"   [815,225][1076,339]
            ├─ ImageView  (1dp 分隔线, 无 id)        [815,339][1076,342]
            └─ TextView #tv_del_stg    "删除策略"   [815,342][1076,456]
```

`dumpsys window` 侧同时可见 `Window{c8e70d9 u0 PopupWindow:59ba128, frame=[Rect(812, 204 - 1080, 459)]}`
—— 确认是 **`PopupWindow`**，不是 `PopupMenu`、也不是 `Dialog`。

对应布局资源：`res/layout/pop_ai_stg.xml`（`aapt2 dump xmltree` 实测）：

```xml
LinearLayout orientation=vertical
             background=@drawable/…  layout_width=103dp layout_height=98dp
  ├─ TextView id=tv_edit_name  layout_height=0dp layout_weight=1 text="修改名称"
  ├─ ImageView background=#ff454761 layout_height=1dp
  └─ TextView id=tv_del_stg    layout_height=0dp layout_weight=1 text="删除策略"
```

> 关键点：两项都是 **`height=0dp + weight=1`** —— 高度完全由**容器**的 98dp 决定。
> 这一点决定了注入新项时必须同时处理「容器高度」与「窗口高度」。

---

## 三、注入方式：挂 `PopupWindow` 展示方法，**前置**改写

```kotlin
for (name in arrayOf("showAsDropDown", "showAtLocation")) {
    for (m in Reflect.methodsNamed(PopupWindow::class.java, name)) {
        hook(m).intercept { chain ->
            maybeInjectMenuItem(env, chain.thisObject as PopupWindow)   // ← 先改内容
            chain.proceed()                                             // ← 再真正 show
        }
    }
}
```

### 为什么必须前置（不能像「染色」那样用 after 钩子）

`show*` 一执行，`PopupWindow` 就按当时的内容量好尺寸并交给 WindowManager。
之后再往内容里 `addView`，新项会被**裁掉**（实测第三项完全不可见）。
只有「改完内容再 `proceed()`」才能让测量把新项算进去。

### 为什么用「找控件」而不是「认类名」来判定目标菜单

`PopupWindow` 是**系统类**，整个 App 到处都在弹（隐私提示、主题切换…）。
这里只认一条：**内容视图里找得到 `#tv_del_stg`**（「删除策略」）。
找不到就直接放过，零副作用。

### 三个真机踩出来的坑

| # | 现象 | 根因 | 改法 |
|---|---|---|---|
| 1 | 注入后三项被压扁（每项 114px → 77px） | 容器高度恒为 98dp，三项按 weight 均分 | 把**每个 TextView 子项**的 `weight` 去掉、高度固定成 `98dp / 2 = 49dp`，容器高度设 `WRAP_CONTENT` |
| 2 | 上面改完仍然只有两项，第三项被裁 | `inflate(layout, null)` 出来的根 View **没有 `LayoutParams`**（parent 传 null），`parent.layoutParams` 是 null，改不动 | 同上 —— 改的是**子项**，不是容器 |
| 3 | 第三项出现了但仍在窗口下沿被切 | `PopupWindow` 是**用固定尺寸 new 出来的**（实测窗口高度恒为 98dp），容器改好窗口也不认 | 同时把 `pop.height` 设成 `WRAP_CONTENT`，让窗口按新内容重新量（撤销时还原原值） |

> 撤销（关掉功能）时：移除注入项与分隔线 → 子项 `weight` 还原成 1、`height` 还原成 0 →
> `pop.height` 还原成注入前记录的原始值。

### 新项的样式来源

**从原菜单里的兄弟 View 克隆**，不手写任何一个样式值：

- 文字项 ← 克隆 `#tv_del_stg`（背景含状态、字色、字号、字体、内边距、gravity、
  `includeFontPadding`、`maxLines`、`minWidth/minHeight`、`LayoutParams`）
- 分隔线 ← 克隆原菜单里那颗 `ImageView`（只画背景色）

---

## 四、筛选面板：真的 inflate App 的布局

**不是照着截图重画**，而是把目标 App 自己的两个布局资源拿过来用：

| 用途 | 资源 |
|---|---|
| 面板外壳 | `res/layout/dialog_marketfliter.xml`（原包把 filter 拼成了 **fliter**，照抄） |
| 每一行 | `res/layout/item_market_filter.xml` |

行情页「沪深京A」左侧漏斗点出来的就是这套，所以圆角背景、标题字号、副标题灰底块、
分隔线、45dp 行高、圆形勾选框、底部两颗按钮**全部同源**，App 换肤 / 深色模式自动跟随。

### 面板结构（实测 dump）

```
ConstraintLayout
 ├─ TextView #tv1  "沪深京A排行榜-股票类型"（标题）
 ├─ TextView #tv2  "请在下方勾选您想看的股票类型"（副标题，灰底）
 ├─ TextView #tv3  "沪深A主板"（分组头）
 ├─ ImageView（分组折叠箭头，30dp，无 id）
 ├─ View #line1（0.5dp 分隔线）
 ├─ RecyclerView #rv_markets
 ├─ TextView #tv_cancel "取消"（0dp 宽 weight=1，50dp 高）
 └─ TextView #tv_ok     "确定"（0dp 宽 weight=1，50dp 高，蓝色底）
```

行布局：

```
ConstraintLayout #itemroot（clickable, 45dp）
 ├─ TextView #name（marginStart 10dp）
 ├─ CheckBox #cb_checked（30dp，marginEnd 15dp）
 └─ View（0.5dp 分隔线）
```

### 三处改动

1. **文案**：`tv1` = 「筛选股票」、`tv2` = 「勾选后对应的条件才会生效」、`tv3` = 「筛选条件」；
2. **`tv_cancel` 文案 `取消` → `重置`**（`tv_ok` 的 `确定` 一字不动）；
3. **行容器**：原 `RecyclerView #rv_markets` 的 LayoutManager 与 adapter 由 App 代码在运行时设，
   我们只 inflate 布局拿不到那段代码 —— 所以把它**原地换成一个垂直 `LinearLayout`**：

   ```kotlin
   val lp = target.layoutParams          // ← 复用同一个实例！
   parent.removeView(target)
   parent.addView(LinearLayout(ctx).apply { orientation = VERTICAL; layoutParams = lp }, index)
   ```

   面板根是 `ConstraintLayout`，约束就存在 `ConstraintLayout.LayoutParams` 里，
   把同一个实例挂到新 View 上，位置 / margin / 约束**一条不丢**。
   顺带一个好处：模块编译期**不需要**依赖 androidx.recyclerview / constraintlayout
   （这两个库都不在模块的 classpath 里）。

另外：分组折叠箭头（`tv3` 右侧那颗无 id 的 ImageView）**隐藏** —— 原版分组可折叠，
我们这里只有固定三行，留个点了没反应的箭头是骗人。面板背景
`?attr/shape_half_top_round_solid_12` 若在当前主题下解析不到，用
`theme.resolveAttribute` 自己补一次。

### 交互

| 动作 | 行为 |
|---|---|
| 点**整行** | 切换该项勾选（与行情页漏斗一致；勾选框本身也可点） |
| **重置** | 三个条件全部清空 + **立即生效**（落盘 + 刷新列表）+ **面板不关** |
| **确定** | 落盘 + 生效 + 关闭面板 |
| 点面板外 / 返回 | 关闭，不改动 |

> 「重置」为什么不顺手关掉面板：原「取消」的语义是「放弃改动并关闭」，
> 而「重置」是「把已生效的条件清掉」—— 关掉会出现「重置完还想再选却得重开」的别扭。

---

## 五、行过滤

### 真正移除：换掉 `adapter.data` 的内容

**不是藏起来，而是让列表里根本没有这些行。**

`BaseQuickAdapter` 的数据就在一个叫 `data` 的字段里（实测字段名，且 `getItem(int)`
就是从它取），所以做法是：

```
筛选生效  →  data = 筛过的子集（新的 ArrayList）  →  宿主自己的 notifyDataSetChanged()
筛选取消  →  data = 宿主的全量                    →  同上
```

ListView 眼里列表本来就"只有这些行"，**不存在占位空间**，滚动 / 翻页 / 惯性滑动
全部是原生行为。这也是唯一能根治「下滑出大片空白」的做法 ——
见下面「走过的弯路」。

挂点：

| 挂点 | 作用 |
|---|---|
| `android.widget.BaseAdapter#notifyDataSetChanged`（**前置**改写） | 宿主一说"数据变了"，先把 `data` 换成筛过的再放行 → 布局的第一帧就是筛过的结果，不会先闪一下全量 |
| `BaseQuickAdapter#getView` / `AIStkAdapter#getView`（后置） | 顺手记一份行内涨跌幅文案（兜底）；并**延后一帧**补一次筛选，防宿主只 `setAdapter` 不发通知 |
| `android.app.Activity#onResume`（后置） | 回到本页时重读筛选状态、对齐一次数据、补装点击回映射 |

时序与幂等：

- `notifyDataSetChanged` 声明在**框架类 `BaseAdapter`** 上（`BaseQuickAdapter` 没覆写），
  所以只能挂框架类，用「`thisObject` 是不是目标适配器」过滤；
- 我们自己装进去的那个 `ArrayList` 记在 `AdapterState.filtered`，靠 `===` 区分
  「宿主给的全量」与「我们装的子集」—— 是宿主给的（`current !== filtered`）就更新
  `st.full`，是我们装的就不动，避免反复包装；
- 还原只在「当前 `data` 确实是我们那份子集」时做，不动宿主自己的数据。

### ⚠️ 换数据会改掉行点击的 `position` 语义 → 必须做下标回映射

换掉 `data` 之后，ListView 的 `position` 变成**子集下标**；而宿主自己的点击处理是拿
`position` 去索引**它自己那份全量列表**。实测症状（修之前）：

```
筛到只剩 6 只 → 点第一行「思特威-W」 → 打开的是全量第一只「新华文轩 601811」
```

不崩、不报错，静默进错股票。修法是给 ListView 的 `OnItemClickListener` 包一层：

```kotlin
mapped = header + keptIndices[position - header]     // 子集下标 → 全量下标
existing?.onItemClick(parent, view, mapped, id)      // 再转发给宿主原监听器
```

- `AdapterView.mOnItemClickListener` 是**隐藏 API**，得反射取；
- 换算时要让掉 `headerViewsCount`（横向表头 / 固定列）；
- `ListView.adapter` 可能是 `HeaderViewListAdapter`，要 `getWrappedAdapter()` 拆包；
- 没装筛选时 `keptIndices` 为空 → 等价于原样透传，天然安全。

### 走过的弯路：`GONE` 不塌陷、`height=0` 也不塌陷（已废弃，留档）

初版走的是「渲染完把不匹配的行藏起来」，两个坑都实测过：

```java
// ListView.layoutChildren 直接对子 View 调 setupChild → child.measure()，
// 不走 ViewGroup.measureChildren 那套「跳过 GONE」的逻辑
final int lpHeight = p.height;
if (lpHeight > 0)  childHeightSpec = makeMeasureSpec(lpHeight, EXACTLY);
else               childHeightSpec = makeMeasureSpec(0, UNSPECIFIED);   // ← 0 掉这里，量自然高度
```

| 尝试 | 结果 |
|---|---|
| 只 `visibility = GONE` | ❌ 行照样占 138px，界面是一整块空白 |
| `lp.height = 0` | ❌ 掉进 `else` 分支，按 `UNSPECIFIED` 量**自然高度**，等于没压 |
| `lp.height = 1` + `GONE` | ⚠️ 能压住，但**宿主一刷新列表就失效**：已布局的行不会重新量，空白与漏筛一起冒出来，滚动时尤其明显 |

**结论：只要还依赖"行视图"这条路，就永远是治标。** 所以最终改成换数据。

> 这两条 `ListView` 的行为结论本身是可靠的，以后遇到"要藏一行但不留白"的场景仍可复用 ——
> 只是别用它来做筛选。

### 涨跌幅与自选怎么取

- **涨跌幅**：优先读数据项字段（实测 `zdf`，`BigDecimal`；候选名列在 `PERCENT_FIELDS`）；
  读不到再退回解析行内 `#tv_item_stk_percent` 的文案（`+9.99%` / `-1.45%` / `--`）。
- 行内文案只是 **`getView` 时刻的一份快照**。换数据之后，被筛掉的行压根不在列表里，
  挂 `TextWatcher` 也放不回来 —— 所以这里**不挂 watcher**；命中兜底的行要等下一次
  重新 bind（或下一次数据刷新）才会修正。
- **自选**：复用 `ZXWorkflow.isBelongByStock(code, market)`
  （20/20 交叉验证过的那个判定 API，与「自选高亮」同源）。

### 三个开关的组合规则（**互斥类别**）

需求明确：**未勾选的类别，其股票一律不显示**。所以是"先归类、再看该类别的开关"，
不是"几个条件取交集"：

```
类别(股票) = 自选（在自选股里，最高优先）
           > 涨幅（zdf > 0）
           > 跌幅（zdf < 0）
           > 平盘（zdf == 0 或读不到）

显示(股票) = 没有任何开关勾选 ? true : 类别(股票) ∈ 已勾选的类别
```

实测（32 只的策略：涨 6 / 跌 12 / 自选 14 / 平盘 0；9 只的策略：涨 0 / 跌 0 / 自选 9 / 平盘 0）：

| 勾选 | 32 只那组保留 | 9 只那组保留 |
|---|---|---|
| 只勾 涨幅 | **6**（非自选的涨股） | **0** |
| 只勾 跌幅 | 12 | 0 |
| 只勾 自选 | 14 | 9 |
| 涨幅 + 自选 | 6 + 14 = 20 | 9 |
| 三个都不勾 | 32（不筛） | 9（不筛） |

> **9 只那组只勾「涨幅」得到 0，就是这个规则最直观的例子** ——
> 那 9 只全在自选里，而"自选"是另一个类别，只勾涨幅时它们不参与。

⚠️ **平盘股（`zdf == 0` / 读不到）不属于任何类别**，所以只要开了筛选就看不到它 ——
这是"未勾选的类别不显示"的直接推论（面板上并没有"平盘"这个开关）。
若希望"三个全勾时平盘也显示"，需要再加一条规则（当前**没有**这么做）。

---

## 六、筛选状态的持久化

存**目标 App 自己的** `SharedPreferences`：文件名 `gsaux_stg_filter`
（`/data/data/com.guosen.android/shared_prefs/`），三个布尔键。

### 为什么不进模块配置

| 通道 | 能不能写 | 结论 |
|---|---|---|
| `ConfigKeys` + remote preferences | ❌ 目标进程侧**只读**（见 `HOOK-NOTES.md` §7） | 用不了 |
| 模块自己的数据目录 | ❌ 目标进程是别的 UID | 用不了 |
| **目标 App 自己的 `SharedPreferences`** | ✅ 同进程、同 UID | **采用** |

于是筛选状态与 `DISCLAIMER_ACCEPTED` 同理：**不进** `ConfigKeys.ALL_KEYS / DEFAULTS`，
「重置模块」不动它；「清除目标 App 数据 / 卸载重装」会清掉它，回到「三个都关」——
这正是期望行为。

模块配置里只留一个**功能总开关** `stg_stock_filter`（登记进 `FEATURE_KEYS`，出厂默认 `false`）。

---

## 七、验收标准与实测结果

| # | 验收项 | 结果 |
|---|---|---|
| 1 | 三点菜单出现「筛选股票」，位于「修改名称」下方，三项等高 | ✅ `[815,225][1076,352]` / `[815,355][1076,482]` / `[815,485][1076,612]` |
| 2 | 面板 UI 与行情页漏斗同形（含 45dp 行 / 两键各半宽） | ✅ dump 坐标逐项吻合 |
| 3 | 「取消」变「重置」，「确定」不变 | ✅ |
| 4 | 只勾「涨幅」→ 32 只那组保留 6，9 只那组保留 0 | ✅ 日志 `全量 32 → 保留 6（涨 6/跌 12/自选 14/平盘 0）`、`全量 9 → 保留 0` |
| 5 | 只勾「跌幅」12 / 只勾「自选」14；9 只那组只勾自选 9 | ✅ |
| 6 | 三个都不勾 → **不筛**，全显示 | ✅ 32 / 9 |
| 7 | **滚到列表末尾不再出现空白** | ✅ 换数据后 ListView 只认子集，无占位行 |
| 8 | 「重置」清空并立即恢复全量 | ✅ |
| 9 | 关掉总开关 → 立即恢复全量 | ✅ `onConfigChanged` |
| 10 | 筛后点第 N 行 = 列表里那一只 | ⚠️ 逻辑已加（`keptIndices` 回映射），**改动后未逐点复验** |
| 11 | 强杀重启后筛选状态仍在（含面板勾选态） | ⚠️ 存储层本次未改动，沿用上一版验收；本轮**未复跑** |
| 12 | `assembleDebug` + `assembleRelease` 通过 | ✅ badging 2.13.0 / vc33 |

---

## 八、策略详情页「100 只上限」是客户端还是服务端

**结论：服务端限制。客户端根本没有传条数 / 分页参数。**

探针 v27 挂到 `com.android.org.conscrypt.ConscryptEngineSocket$SSLOutputStream` 上
抓到 SSL **明文**，拿到策略详情接口的完整请求（`Host: goldsundg.guosen.com.cn`，
`Content-Length: 382`）：

```
POST /gsnews/strategy/queryStrategyQuoteDetail/1.0 HTTP/1.1
Content-Type: application/x-www-form-urlencoded; charset=UTF-8

stId=<策略id>&pkg=<渠道>&reqtime=<ts>&packtm=<ts>&sysVer=8.6.0
&softName=goldsun_android&netAddr=NA&rNetAddr=&device_model=…&deviceVers=…
&operorg=&userCode=<账号>&hwID=NA&conn_style=2.0.03.0.0&session=<会话>&openapi_sign=<签名>
```

（`userCode` / `session` / `openapi_sign` 已脱敏，**不要把真实值写进仓库**。）

- 唯一的业务参数是 **`stId`**，其余全是客户端元信息 + 鉴权；
- **没有** `count` / `limit` / `pageSize` / `pageNo` / `num` 等任何条数参数；
- 三次独立抓包（不同时间进页面）参数集合与 `Content-Length` 完全一致；
- 旁证：策略列表 → 详情的 `Intent` 里只有 `Strategy{id, name, tags=[labelId…]}`，
  不含股票列表与数量；`AIStkStgDetailAct` 实例字段里也没有 100 / count / pageSize 常量。

⇒ 客户端每次都是「把整张列表要回来、全部渲染」，**上限只可能由服务端施加**。
客户端改代码拿不到第 101 只数据，所以不做「解除上限」，也不做成功能模块。
若服务端哪天放开，客户端无需改动即可显示更多 —— 这条链路本来就是「给多少显示多少」。

### 顺带记下：金太阳**不用 OkHttp**

OkHttp 钩子（`okhttp3.OkHttpClient.newCall` / `ResponseBody.string()`）整段会话 **0 命中**，
而 SSL 流上抓到的是 `POST … HTTP/1.1` + 自研 `com.guosen.androidnet.tcpb.*` 调用栈
（裸 `java.net.SocketOutputStream` 上是 TLS 密文 `17 03 03 …`）。
即：**自研 HTTP 客户端 + Conscrypt TLS**，OkHttp 只被少数第三方 SDK 用。

---

## 九、App 升级后的失效预案

| 失效现象 | 改哪里 |
|---|---|
| 菜单里没有「筛选股票」 | `StgStockFilterTargets.MENU_ANCHOR_ID / MENU_EDIT_NAME_ID`；用 `tools/probe` 重新 dump 菜单视图树 |
| 面板打不开 / 空面板 | `LAYOUT_FILTER_PANEL / LAYOUT_FILTER_ITEM` 与 `ID_PANEL_* / ID_ITEM_*` 候选名 |
| 筛选不生效 | `DATA_FIELD`（适配器数据字段名 `data`）、`TARGET_ADAPTERS`、`PERCENT_FIELDS`、`PERCENT_ID_CANDIDATES` |
| 自选判定错 | `feature/watchlist/ZxWatchlist.kt` |
| 行被筛掉却留空白 | 换数据方案已根治；若又出现，先看 `applyFilter` 有没有真的把 `data` 换掉（日志应有 `筛选股票：全量 N → 保留 M`） |
| 筛后点击进错股票 | 看点击回映射是否装上：`ensureClickRemap` 依赖 `LIST_ID_CANDIDATES` 能解析到列表 id、`AdapterView.mOnItemClickListener` 能反射取到 |

排错先看 LSPosed 日志（`/data/adb/lspd/log/modules_*.log`，TAG `GSZXEnhance`）：

```
筛选股票：已挂三点菜单展示点 ×5
筛选股票：已挂 BaseAdapter.notifyDataSetChanged ×1
筛选股票：已挂 BaseQuickAdapter.getView ×1
筛选股票：已在三点菜单「修改名称」下方注入「筛选股票」
筛选股票：菜单窗口原尺寸 width=… height=…（-1=MATCH_PARENT, -2=WRAP_CONTENT）
筛选股票：菜单项高度已归一（3 项 × …px）
筛选股票：筛选面板已打开（涨幅=false 跌幅=false 自选=false）
筛选股票：筛选条件已生效（涨幅=true 跌幅=false 自选=false）
筛选股票：全量 32 → 保留 6（涨 6 / 跌 12 / 自选 14 / 平盘 0）
筛选股票：筛选未生效，已还原全量（32 只）
```

---

## 十、本次「换数据」改造后仍未复验的点

换成接管 `adapter.data` 之后，侵入性比"只改视图"深，风险点也随之变了。
下面几条**没有逐条上真机复验**，下次动这块时优先看：

| 项 | 风险 |
|---|---|
| **点击下标回映射** | 逻辑已写（`keptIndices`），但改完之后**没有逐一点过**。若点开的股票与列表不符，先查这里 |
| **「全选」与筛选叠加** | 多选栏「全选」走 `ListView.performItemClick` → 同一个被包裹的监听器 → 记录的也是**全量下标**，理论上自洽；但「筛选 + 全选 + 加入自选」这个组合没实机跑过 |
| **宿主改用 `adapter.getItem(position)`** | 回映射是按「宿主自己持有全量列表」这一**实测事实**做的修补。哪天宿主改成从 adapter 取股，回映射会**反向错位**（那时应当拆掉映射） |
| **宿主原地改 `adapter.data` 那个对象** | 我们把 `data` 换成了自己的 `ArrayList`。若宿主把它当成"自己那份"做原地增删（`clear()+addAll()`），语义会打架。目前未观察到，`applyFilter` 里靠 `===` 比较也只能覆盖"换成新列表"这一种 |
| **涨跌幅兜底** | 数据项 `zdf` 读不到时退回 `getView` 快照，行情原地刷新后要等下一次 bind 才修正 |
| **平盘股** | 只要开了筛选就不显示（见 §5）。若期望"三个全勾时平盘也显示"，需再加规则 |
