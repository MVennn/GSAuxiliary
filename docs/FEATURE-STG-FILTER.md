# 策略详情页「筛选股票」实现说明

> 一句话：策略详情页三点菜单里加一项「筛选股票」，点开的面板与行情页那颗漏斗**同源**，
> 八项条件分两组（涨跌与自选 / 股票类型），筛掉的行从列表里真正移除。
>
> 版本：**2.18.0**（面板八行两组；2.16.1 起为三项、2.17.0 起计数与空态） · 功能 id：`stg_stock_filter`
> 目标 App：国信金太阳 **8.6.0**（vc271） · 实测设备：魅族 20 / Android 16 (SDK 36) / LSPosed 2.2.1
> 相关代码：[`feature/stgfilter/`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgfilter/)
> + [`feature/filter/`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/filter/)（**与行情页面板共用**的模型 / 落盘 / 面板工具）
> 姊妹篇：[FEATURE-MARKET-FILTER.md](FEATURE-MARKET-FILTER.md)（行情页那颗面板里追加的三行 + 筛排行榜）

---

## 一、需求

1. 策略详情页右上角三点菜单（「修改名称」/「删除策略」）里，**「修改名称」下方**新增
   `筛选股票`；
2. 点击弹出筛选页，页内提供 **涨家股票 / 跌家股票 / 自选股票** 三个可切换开关
   （2.18.0 起又多一组 **股票类型**：创业板 / 科创板 / 北证A / 未开板次新 / ST股，
   与行情页「沪深京A」漏斗里的那五项同名同义，见 §四之二）。
   **勾选项之间是彼此独立的类别**：未勾选的类别、其对应股票一律不显示 ——
   例如只勾「涨家股票」时，自选里那些正在涨的股票**也不显示**；
   三个都不勾 = 不筛选（全显示）；
3. 被筛掉的股票要**真正从列表里移除**（滚动时不能留下空白占位）；
4. 筛选状态**本地持久化**，关掉软件重开仍在；
5. 筛选页**复用软件内部现有筛选组件的 UI**（行情页「沪深京A」左侧那颗漏斗），
   原「取消」按钮改成「重置」，「确定」保持不变；
6. 左上角那颗「策略个股15只」要跟着筛 —— 宿主只写**全量**、筛完也不会变，
   屏幕上只剩 3 行却还写着 15 只，看着就是没筛。改成 **`策略个股 保留数/总数`**；
7. 筛到**一只不剩**时，亮出**宿主自己的**「未选出符合条件的股票」空态视图，
   而不是丢一片空白。

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
我们这里只有固定八行，留个点了没反应的箭头是骗人。面板背景
`?attr/shape_half_top_round_solid_12` 若在当前主题下解析不到，用
`theme.resolveAttribute` 自己补一次。

### 2.18.0：两组八行，与行情页那颗面板共用一套

```
筛选股票                        ← tv1
勾选后对应的条件才会生效          ← tv2
── 涨跌与自选 ──                ← tv3（宿主那行分组头，文案改成这组）
   涨家股票 / 跌家股票 / 自选股票
── 股票类型 ──                  ← 克隆 tv3 的分组头（样式 + 左缩进差额）
   创业板 / 科创板 / 北证A / 未开板次新 / ST股
[重置] [确定]
```

| 组 | 语义（判定实现在 `feature/filter/StockFilters`） |
|---|---|
| 涨跌与自选 | **未勾选的类别不显示**：每只股票归到唯一一个类别（自选优先 > 涨 > 跌 > 平盘），类别没勾就看不见；三个都不勾 = 这一维不筛 |
| 股票类型 | **有一个类型没勾就排除**：类型是并存的（`*ST卓然`（688121）既是科创板又是 ST 股），五个全勾（默认）= 这一维不筛 |

两维取交集。「股票类型」的判定（字段优先、代码 / 名称兜底）见 `feature/filter/StockTypes.kt`：
`StockInfo` 的 `isCyb` / `isKCPlate` / `isXXLD` 为 true 即认定，
再用代码前缀（300/301、688/689、43/83/87/88/920）与名称（含 `ST`）兜底 ——
策略页的数据源未必把这些字段填全（`isZx` 在策略页就恒为 false，同一个坑）。
代码 / 分组头 / 建行 / 样式克隆都收进 `feature/filter/StockFilterPanelUi.kt`，
行情页那颗面板追加三行时用的是同一批函数（见 [FEATURE-MARKET-FILTER](FEATURE-MARKET-FILTER.md) §四）。

### 交互

| 动作 | 行为 |
|---|---|
| 点**整行** | 切换该项勾选（与行情页漏斗一致；勾选框本身也可点） |
| **重置** | 八项全部回到出厂（方向全不勾 + 类型全勾 = 不筛）+ **立即生效**（落盘 + 刷新列表）+ **面板不关** |
| **确定** | 落盘 + 生效 + 关闭面板 |
| 点面板外 / 返回 | 关闭，不改动 |

> 「重置」为什么不顺手关掉面板：原「取消」的语义是「放弃改动并关闭」，
> 而「重置」是「把已生效的条件清掉」—— 关掉会出现「重置完还想再选却得重开」的别扭。

---

## 五、行过滤

### ⚠️ 首帧就得是筛过的（2.18.0 修「闪几帧未筛选列表」）

筛选生效时点进详情页，列表**第一帧**必须是筛过的。数据有两条到列表的路：

| 路径 | 谁管 |
|---|---|
| 宿主 `data.clear(); data.addAll(...); notifyDataSetChanged()` | `BaseAdapter.notifyDataSetChanged` 的**前置**钩子：放行前先把过滤代理装好 |
| 宿主把**已经填好数据**的适配器直接 `setAdapter(...)` 挂上来 | `ListView.setAdapter` 的**后置**钩子（2.18.0 加）：装完代理再让这一帧过去 |

第二条原先没人管：列表先按全量布局一帧，等 `getView` 的兜底补筛（**延后一帧**）才变筛过的
—— 肉眼就是「闪几帧未筛选的列表」（用户 2026-10-19 报的现象）。
现在两条路都在「布局之前」把代理装好，`getView` 的兜底只留作最后一道保险。

顺手还有个副作用是好的：`setAdapter` 通常发生在 `onCreate`（比 `onResume` 早），
这个钩子会把「当前在看的 Activity / 列表」提前认下来 —— 之前只有 `onResume` 之后才认，
宿主早于它发的通知一律够不着（早退原因 `没有 trackedActivity`）。

### 真正移除：给 ListView 套一层过滤代理

**不是藏起来，而是让列表里根本没有这些行。**

做法是给 `ListView` 装一个模块自己写的 `FilteredAdapter`，包在 `AIStkAdapter` 外面：

```
筛选生效  →  lv.adapter = FilteredAdapter(原 adapter, 全量下标表)
                ├─ getCount()               → 子集行数
                ├─ getView / getItem        → 用全量下标转调原 adapter
                └─ registerDataSetObserver  → 转发给原 adapter（否则宿主的 notify 刷不到列表）
筛选取消  →  lv.adapter = 原 adapter
```

ListView 眼里列表本来就"只有这些行"，**不存在占位空间**，滚动 / 翻页 / 惯性滑动
全部是原生行为。这也是唯一能根治「下滑出大片空白」的做法 ——
见下面「走过的弯路」。

#### ⚠️ 为什么不能直接改 `data`（2.16.1 踩的坑，别再走回去）

早期版本就是把 `AIStkAdapter.data` 整个换成筛过的子集。**这条路是错的**：

宿主 `AIStkStgDetailAct.onItemClick` 是**拿 adapter 的 `getCount()` 做范围校验、
却用它自己那份全量列表取值**的。真机日志（筛选只剩 1 只时点第一行）：

```
筛选股票：全量 35 → 保留 1（涨 1 / 跌 3 / 自选 31 / 平盘 0）
点击 position=1 → 映射成全量下标 2 转发给宿主
宿主 onItemClick 进入 position=2 → 正常返回（没抛）→ 页面一动不动
```

`2 - 1 = 1` 正好卡在 `getCount() = 1` 的边界上，被它的范围检查挡掉 ——
不崩、不报错、静默无反应。

**推论：只要把 `data` 换成子集，「全量下标 ≥ 筛选后行数」的行就永远点不动。**
筛得越狠点不动的行越多：保留三十多只时几乎看不出来，只保留几只时整列都点不动。
所以代理方案不是"更好"，是**必须**。

挂点：

| 挂点 | 作用 |
|---|---|
| `android.widget.BaseAdapter#notifyDataSetChanged`（**前置**改写） | 宿主一说"数据变了"，先把代理的下标表刷新到位再放行 → 布局第一帧就是筛过的结果，不会先闪一下全量 |
| `BaseQuickAdapter#getView` / `AIStkAdapter#getView`（后置） | 顺手记一份行内涨跌幅文案（兜底）；并**延后一帧**补一次筛选：宿主若自己 `setAdapter` 把代理顶掉，或只 `setAdapter` 不发通知，都在这里装回去 |
| `android.app.Activity#onResume`（后置） | 回到本页时重读筛选状态、只对齐当前在用的适配器、补装点击回映射；**再延 600ms 复查一次** —— 从个股页返回时宿主还在拉数据，第一次对齐往往什么都装不上 |

> 代理的 `getView(position, ...)` 会把 `position` 换算成**全量下标**再转调原 adapter，
> 所以挂在 `BaseQuickAdapter#getView` 上的钩子拿到的仍然是他熟悉的全量语义，
> 行内涨跌幅兜底表（`percentByCam`）里的键值也依旧对得上。

时序与幂等：

- `notifyDataSetChanged` 声明在**框架类 `BaseAdapter`** 上（`BaseQuickAdapter` 没覆写），
  所以只能挂框架类，用「`thisObject` 是不是目标适配器」过滤；
- 每轮重算都比较「筛出来的结果」+「代理是否还挂在列表上」，**一致就直接返回**、
  一个字节都不动 —— 宿主行情刷新很勤，别再给它多加一次"数据变了"；
- 结果与下标表合在一个 `Snapshot` 里**一起替换**，避免点击回映射读到「新结果 + 旧下标」；
- 代理还在列表上（`lv.adapter` 拆包后就是它）就只 `update(下标表)`，不重建 ——
  省掉一次 `setAdapter` 带来的滚动位置重置；发现被宿主顶掉才重装。

⚠️ **一切操作只认「当前列表在用的那个适配器」**（`liveAdapter()`）。
`states` 是按**适配器实例**存的弱表，页面重建 / 宿主换过适配器之后里面会同时留着旧实例 ——
照旧实例去装代理，等于把当前 ListView 的数据源换成一份过期内容（返回详情页时旧实例
往往已经被清空），**整列股票都会空掉**；而 `WeakHashMap` 的遍历顺序不定，所以只在
某些时候才撞上（2.16.2 修的就是这个）。`applyFilter` 里另有一道同步保险：
传进来的适配器不是当前在用的那个就直接返回。

#### ⚠️ 代理怎么被认出来：类名会被 R8 混淆，得靠身份（2026-10-19 修）

代理装上去之后，**每一步都要先认出「列表上这个 adapter 是我们的代理」**才能拆出原适配器
（`unwrapProxy` / `ensureClickRemap` / 还原全量 / `liveAdapter`）。

原先只有一条判据：类名里含 `FilteredAdapter`（为的是**跨热重载实例**也能认 ——
新旧实例的类是不同 ClassLoader 下的同名类，`is` 判断认不出）。但模块自己也开 R8：

```
装机包里：io.github...StgStockFilterFeature$FilteredAdapter  →  f2.m
```

于是这条判据连**自己刚装上去的**代理都认不出。真机症状（用户报的）：

- **点「确定」筛选不生效，退出重进才生效** —— 重进时列表上还是宿主的原适配器，
  那条路不需要认代理，所以「看着能用」；
- 顺带：行点击回映射也装不上（`ensureClickRemap` 判定「adapter 不是目标：f2.m」），
  筛完点行会按子集下标索引全量列表 → **点错股票**。

修法两条腿：

| 场景 | 判据 |
|---|---|
| 本实例 | `proxiedLists[lv]` 的**身份**直判（装代理时记下「列表 → 代理 + 原适配器」，弱引用键） |
| 热重载后的旧实例 | 退回类名拆包 —— 类名与 `getTarget()` 由 `app/proguard-rules.pro` 的 `-keepnames` / `-keepclassmembers` 保住 |

诊断留痕：`applyFilter` 的每个静默早退都记一条
`筛选股票：本轮筛选没生效 —— <原因>`（同一原因只记一次）——
这条就是定位这个问题时加的，用户导出的日志里直接能看到原因。

### ⚠️ 代理会改掉行点击的 `position` 语义 → 必须做下标回映射

ListView 上挂着我们的代理，`position` 变成**子集下标**；而宿主自己的点击处理是拿
`position` 去索引**它自己那份全量列表**。实测（不做映射时）：

```
筛到只剩 1 只「华建集团」→ 点它 → 打开的是全量第 1 只「莱美药业」
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

### 方向那三个开关的组合规则（**互斥类别**）

需求明确：**未勾选的类别，其股票一律不显示**。所以是"先归类、再看该类别的开关"，
不是"几个条件取交集"：

```
类别(股票) = 自选股票（在自选股里，最高优先）
           > 涨家股票（zdf > 0）
           > 跌家股票（zdf < 0）
           > 平盘（zdf == 0 或读不到）

显示(股票) = 没有任何开关勾选 ? true : 类别(股票) ∈ 已勾选的类别
```

判定实现在 `StockFilters.keepsDirection`（类型维度见 §四之二，两维取交集）。
实测（32 只的策略：涨 6 / 跌 12 / 自选 14 / 平盘 0；9 只的策略：涨 0 / 跌 0 / 自选 9 / 平盘 0）：

| 勾选 | 32 只那组保留 | 9 只那组保留 |
|---|---|---|
| 只勾 涨家股票 | **6**（非自选的涨股） | **0** |
| 只勾 跌家股票 | 12 | 0 |
| 只勾 自选股票 | 14 | 9 |
| 涨家股票 + 自选股票 | 6 + 14 = 20 | 9 |
| 三个都不勾 | 32（不筛） | 9（不筛） |

> **9 只那组只勾「涨家股票」得到 0，就是这个规则最直观的例子** ——
> 那 9 只全在自选里，而"自选股票"是另一个类别，只勾涨家股票时它们不参与。

⚠️ **平盘股（`zdf == 0` / 读不到）不属于任何类别**，所以只要开了筛选就看不到它 ——
这是"未勾选的类别不显示"的直接推论（面板上并没有"平盘"这个开关）。
若希望"三个全勾时平盘也显示"，需要再加一条规则（当前**没有**这么做）。

### 左上角计数：宿主只认全量，得由模块改写

那颗 `#tv_stg_res_num`（实测文案「策略个股8只」，在多选栏那一行）是**宿主**写的，
它不认识我们的筛选 —— 筛完还是「策略个股15只」。`applyFilter` 每轮把它改写成：

```
策略个股 保留数/总数        ← 例：策略个股 3/15
```

- **取消筛选时按宿主原格式还原**（`策略个股15只`）。还原的前提是「当前文案确实是我们
  写过的那个格式」（正则 `^策略个股 \d+/\d+$`）—— 宿主自己写的文案一个字都不动。
- 宿主可能在我们之后又刷一遍，所以**只在真的改写过时**延后一帧（`lv.post`）再拽一次；
  加上 `notifyDataSetChanged` 前置钩子本来就会被行情刷新频繁触发，计数基本实时。
- 数据没到（`full` 为空）时**什么都不做** —— 那是宿主「先 clear 再等回包」的中间态，
  照着它写成「策略个股 0/0」会在每次刷新时闪一下。

### 空态：筛到 0 只时用宿主自己的「未选出符合条件的股票」

**不自己画**（字号、灰字、图标、内边距都跟宿主的换肤 / 深色模式绑在一起），
直接把宿主的布局 `res/layout/empty_ai_stk.xml` inflate 出来摆上去。

#### 这个布局是怎么找到的

DEX 与自定义资源都加密，拿不到源码，但 `resources.arsc` 和 `res/*.xml` 是**明文**的
（`aapt2` 能直接 dump）。于是从设备上把 `base.apk` 拉下来，用 `aapt2` 全表扫：

```bash
adb shell "cd /sdcard && unzip -o <base.apk 路径> resources.arsc"   # 3.2MB，明文
aapt2 dump resources base.apk | grep -iE "empty|nodata"              # 找候选
aapt2 dump xmltree --file res/layout/empty_ai_stk.xml base.apk
```

`empty_ai_stk.xml` 全文就一个 `TextView`：

```xml
TextView  layout_width=match_parent  layout_height=match_parent
          gravity=center_horizontal      ← 只水平居中，**不是**垂直居中
          paddingTop=23dp  paddingBottom=25dp
          text="未选出符合条件的股票"      ← 文案是**布局里内联的**，不在 resources.arsc 里
          drawableTop=@drawable/…         ← 一只抱着空箱子的灰色吉祥物
```

> 注意这句文案**不在 `resources.arsc`**，所以按资源表搜「未选出」是搜不到的
> （只有 `aapt2 dump xmltree` 才能看到）—— 一开始就是在这里绕了一圈。

#### 摆放方式：只让出「股票行」，头部一行都不动

对照宿主自己的用法（`AIStkChooseDetailAct`「选股详情」，0 只股票时实测）：

```
RelativeLayout #container_ai_stk_detail
  ├─ RelativeLayout #top                      ← 标题栏
  ├─ ListView #lvStks                         ← 空态**在列表内部**
  │    ├─ RelativeLayout  (header 447px)      ← 选股标签 + 标签云 + 「选出股票0只 | 勾选加入自选股」
  │    └─ TextView  (空态, 0,447-1080,1121)   ← 紧跟在 header 后面
  └─ …
```

**头部原样留着、空态只占股票行那块** —— 这正是要复刻的效果。
详情页的根是竖直 `LinearLayout`，`#lvStks` 是 `height=0dp + weight=1`，
所以「只让出股票行」只能靠动列表自己：

| 步骤 | 做法 |
|---|---|
| 进空态 | ① 先收掉两件配件（见下）② 列表 `height` → `WRAP_CONTENT`、`weight` → 0（**压成只剩 header**，原 LayoutParams 先存一份）③ 空态视图 `height=0dp + weight=1`，接管让出来的那块 |
| 退出空态 | 空态 `GONE` → 还原列表 LayoutParams → 把两件配件放回去 |

> ⚠️ **不能把整个 `#lvStks` 收起来**（`AdapterView.setEmptyView` 内部就是这么干的）。
> 策略名称 / 涨家数 / 标签 / 「策略个股N只 · 勾选加入自选股」**全在列表的 header 里**，
> 收掉列表 = 把整页的头都抹了 —— 2.17.0 的第一版就踩了这个，真机截图一眼看出来。
>
> 列表的 `visibility` **一概不碰**（那是宿主的），只借它的高度。

#### 连配件一起收：列标题行 + 说明及风险提示

没有股票行了，`名称 / 最新价 / 涨跌幅` 和风险提示摆在空态旁边只是噪音，一起收掉。
宿主自己也是这么做的 —— `AIStkChooseDetailAct` 在 0 只股票时 `#ll_top` 就是 `GONE`。

两件的收法**不一样**：

| 配件 | 是什么 | 怎么收 |
|---|---|---|
| `名称 / 最新价 / 涨跌幅` | header 末尾的 `#ll_top`（普通 View） | 翻 `visibility = GONE` 就塌了（`LinearLayout` 跳过 GONE） |
| `说明及风险提示` | 挂在 ListView 上的固定视图（实测是 **footer**，248px、**没有 id**） | `GONE` **不塌陷**（见「走过的弯路」），只能 `removeFooterView` 摘下来 |

两个真机踩出来的坑：

**坑 1：列标题行会被宿主显示回来。** 第一版用「收过了就不再收」的标志位 ——
宿主刷新列表时自己把 `#ll_top` 重设回 `VISIBLE`，于是它又冒出来了。
改成**按当前 `visibility` 判断、每轮 `applyFilter` 都重新对齐**（幂等，代价是几个 `if`）。

**坑 2：风险提示会闪一下。** 它是 ListView 的固定视图，而 **ListView 只把已经布局过的
固定视图挂成子 View** —— 进空态那一刻列表还铺着几十行、footer 在屏外，`getChildAt` 压根找不到；
等它被布局出来再摘，肉眼就看到它闪一下。
两条改法：
1. **反射读 `ListView.mFooterViewInfos`**（`FixedViewInfo.view` 是 public 字段），
   不依赖布局，**能在列表被压扁之前**就摘掉 —— 一帧都不闪；`getChildAt` 只作兜底；
2. **顺序**：先摘配件、再压列表。反过来会先渲染出一帧「只剩头部 + 配件」再收，一样是闪。

> `removeFooterView` 内部会回调 `onChanged()` → 重入我们的 `applyFilter`。
> 所以 `detachedChrome[lv]` 必须**在调它之前**登记好，否则重入那一轮会对着同一块再摘一次。

幂等靠「空态视图自己的 `visibility`」+「按当前状态判断」，
**不设标志位** —— 从个股页返回时页面回调会重跑一遍筛选，标志位会跟真实状态脱节。
`applyFilter` 每轮都会调到这里，状态没变时只走几个判断就返回。

---

## 六、筛选状态的持久化

存**目标 App 自己的** `SharedPreferences`：文件名 `gsaux_stg_filter`
（`/data/data/com.guosen.android/shared_prefs/`），**八个布尔键** —— 方向三项沿用
2.16.1 的键名（老用户的状态不丢），类型五项是 2.18.0 新增、**缺省即 true**
（老用户升级后行为不变）。

落盘实现在 [`feature/filter/StockFilterStore.kt`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/filter/StockFilterStore.kt)
（**两块面板共用**），进程级缓存也在那里：谁改谁刷，策略页与行情页不会各看各的。

### 为什么不进模块配置

| 通道 | 能不能写 | 结论 |
|---|---|---|
| `ConfigKeys` + remote preferences | ❌ 目标进程侧**只读**（见 `REVERSE-ENGINEERING.md` 难点与解法 §7） | 用不了 |
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
| 10 | 筛后点第 N 行 = 列表里那一只 | ✅ 2.16.1 复验：筛「自选」点第 2 行 → 打开「华建集团 600629」，与所点行一致 |
| 11 | 强杀重启后筛选状态仍在（含面板勾选态） | ⚠️ 存储层未改动，沿用上一版验收 |
| 12 | `assembleDebug` + `assembleRelease` 通过 | ✅ badging 2.16.1 / vc38 |
| 13 | 筛选与「直达标准行情 + 左右滑动」联动 | ✅ 2.16.1：筛「跌幅」保留 3 只，点第 1 行进入后左滑 4 次，停在「华北制药」（那 3 只里的最后一只）不再越界；日志 `翻页列表按筛选结果收敛 35 → 3` |
| 14 | 筛选状态下点进个股再返回详情页 | ✅ 2.16.2：反复若干次，列表与筛选结果都保持正常，未再出现整列空白 |
| 15 | 筛选面板三行文案为「涨家股票 / 跌家股票 / 自选股票」 | ✅ 2.17.0 真机：面板 dump 逐行吻合 |
| 16 | 左上角显示「策略个股 保留数/总数」，取消筛选后还原成「策略个股N只」 | ✅ 2.17.0 真机：`策略个股 20/100`；切回不筛 → 还原成 `策略个股100只` |
| 17 | 筛到 0 只时出现宿主自己的「未选出符合条件的股票」空态 | ✅ 2.17.0 真机：吉祥物图标 + 文案，位于「策略个股 0/100 · 勾选加入自选股」**下方**，头部一行未动 |
| 18 | 空态退出后列表恢复原高、行数正确 | ✅ 2.17.0 真机：`lvStks` 由 `0,124-1080,1012` 回到 `0,124-1080,2073`，20 行照常显示 |
| 19 | 空态时「名称 / 最新价 / 涨跌幅」与「说明及风险提示」一起收掉 | ✅ 2.17.0 真机：uiautomator 里这两个节点**全部消失**；切回有结果后 `名称` 回到 `[42,830][397,880]`、风险提示挂回列表 |
| 20 | 空态**不闪**（风险提示不会先露一帧） | ✅ 2.17.0 真机：反射 `mFooterViewInfos` 在压列表**之前**摘掉，肉眼全程看不到它 |

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
| 面板打不开 / 空面板 | 布局与控件 id 常量搬到了 `feature/filter/FilterPanelIds`（两块面板共用一份）：`LAYOUT_FILTER_PANEL / LAYOUT_FILTER_ITEM`、`ID_PANEL_* / ID_ITEM_*` |
| 面板长出来但分组头 / 行左缩进不对 | `StockFilterPanelUi.addSectionHeader` 的「缩进差额」与 `wrapWithVerticalHost` 的外边距清零（见 [FEATURE-MARKET-FILTER](FEATURE-MARKET-FILTER.md) §2.2） |
| 「股票类型」那一组筛不动 | `StgStockFilterTargets.TYPE_FLAG_FIELDS`（`isCyb` / `isKCPlate` / `isXXLD`）与 `feature/filter/StockTypes.kt` 的前缀 / 名称兜底 |
| 进详情页仍会闪一帧全量 | `ListView.setAdapter` 钩子没装上（看日志 `已挂 ListView.setAdapter`）；或宿主改用别的方式换数据（`notifyDataSetChanged` 之外的路径）—— 用 `tools/probe` 重新看数据是怎么挂上去的 |
| 筛选不生效 | `DATA_FIELD`（适配器数据字段名 `data`）、`TARGET_ADAPTERS`、`PERCENT_FIELDS`、`PERCENT_ID_CANDIDATES`、`NAME_FIELDS` |
| 自选判定错 | `feature/watchlist/ZxWatchlist.kt` |
| 左上角计数不跟着筛 / 取消筛选后没还原 | `RES_NUM_ID_CANDIDATES`（实测 id `tv_stg_res_num`）；还原靠正则 `^策略个股 \d+/\d+$` 认自己写过的文案，宿主改了格式就得同步改 |
| 筛到 0 只却是一片空白 | 空态没定位到。看日志 `找不到宿主的空态布局 empty_ai_stk`；用 `aapt2 dump xmltree` 重新找宿主的空态布局，改 `StgStockFilterTargets.LAYOUT_EMPTY` |
| 空态盖住了策略名称 / 涨家数 / 「策略个股N只」那一整块 | 列表被整个收起来了。检查 `updateEmptyView` 是不是又在动 `lvStks.visibility` —— **只能压高度，不能收可见性**（那些行全在 `#lvStks` 的 header 里） |
| 空态时列标题行又冒出来 | 宿主刷新列表时会把 `#ll_top` 重设回 VISIBLE。别用标志位「收过就不再收」，要按当前 `visibility` **每轮对齐** |
| 风险提示闪一下才消失 | 摘晚了。它是 ListView 的固定视图，`getChildAt` 要等布局完才拿得到 —— 改成反射 `mFooterViewInfos`，并**先摘它、再压列表** |
| 行被筛掉却留空白 | 换数据方案已根治；若又出现，先看 `applyFilter` 有没有真的把 `data` 换掉（日志应有 `筛选股票：全量 N → 保留 M`） |
| 筛后点击进错股票 | 看点击回映射是否装上：`ensureClickRemap` 依赖 `LIST_ID_CANDIDATES` 能解析到列表 id、`AdapterView.mOnItemClickListener` 能反射取到 |

排错先看 LSPosed 日志（`/data/adb/lspd/log/modules_*.log`，TAG `GSZXEnhance`）：

```
筛选股票：已挂三点菜单展示点 ×5
筛选股票：已挂 BaseAdapter.notifyDataSetChanged ×1
筛选股票：已挂 BaseQuickAdapter.getView ×1
筛选股票：已挂 ListView.setAdapter ×1
筛选股票：已在三点菜单「修改名称」下方注入「筛选股票」
筛选股票：菜单窗口原尺寸 width=… height=…（-1=MATCH_PARENT, -2=WRAP_CONTENT）
筛选股票：菜单项高度已归一（3 项 × …px）
筛选股票：筛选面板已打开（涨家股票=false 跌家股票=false 自选股票=false | 创业板=true 科创板=true 北证A=true 未开板次新=true ST股=true）
筛选股票：筛选条件已生效（涨家股票=true 跌家股票=false 自选股票=false | 创业板=true …）
筛选股票：左上角计数 → 策略个股 6/32
筛选股票：全量 32 → 保留 6（涨 6 / 跌 12 / 自选 14 / 平盘 0 / 创业板 5 / 科创板 4 / 北证A 1 / 未开板次新 0 / ST股 2）
筛选股票：已把宿主的空态布局摆到列表下面（empty_ai_stk）
筛选股票：列标题行（名称 / 最新价 / 涨跌幅）已收起
筛选股票：说明及风险提示已从列表上摘下（footer）
筛选股票：空态（未选出符合条件的股票）→ 显示（列表压成只剩头部）
筛选股票：列标题行已还原
筛选股票：说明及风险提示已挂回列表
筛选股票：空态 → 隐藏（列标题 / 风险提示 / 列表高度全部还原）
筛选股票：筛选未生效，已还原全量（32 只）
```

---

## 十、还没复验的点

| 项 | 风险 |
|---|---|
| **「全选」与筛选叠加** | 代理方案下，多选栏「全选」走 `ListView.performItemClick`，落在代理的行号上，再由我们映射成全量下标转发 —— 理论上自洽；但「筛选 + 全选 + 加入自选」这个组合仍没实机跑过 |
| **宿主改成从 adapter 取股** | 回映射建立在「宿主自己持有全量列表」这一**实测事实**上。哪天宿主改成从 adapter 取股，回映射会**反向错位**（那时应当把映射拆掉） |
| **宿主 `setAdapter` 后不再触发 `getView`** | 代理被顶掉后靠 `getView` 兜底装回。若宿主既换了 adapter、又完全不触发 `getView`（列表为空时有可能），筛选会暂时失效 —— 日志里能看到「暂时没装行点击回映射」那一行 |
| **涨跌幅兜底** | 数据项 `zdf` 读不到时退回 `getView` 快照，行情原地刷新后要等下一次 bind 才修正 |
| **平盘股** | 只要开了方向筛选就不显示（见 §5）。若期望"三个全勾时平盘也显示"，需再加规则 |
| ~~2.18.0 的面板八行 / 类型筛选 / 首帧不闪全量~~ | ✅ 2026-10-19 14:4x 登录后已补验：面板八行两组且缩进一致、取消勾「科创板」筛掉 688 行（`全量 20 → 保留 19`）、勾「涨家股票」在该策略（20 只全在自选）上得到 0 只 + 宿主空态、**重置**立即还原全量、录屏确认进页面**第一帧就是筛过的**（无未筛选帧）。逐项见 [FEATURE-MARKET-FILTER](FEATURE-MARKET-FILTER.md) §五 表 10–16 |
| **`StockInfo.isXXLD` = 未开板次新** | 只做过字段名层面的确认（探针 dump），运行时取值未核对；认不出就不归类，不会误伤别的股票 |
