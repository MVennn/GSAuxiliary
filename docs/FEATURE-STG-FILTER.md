# 策略详情页「筛选股票」实现说明

> **一句话**：策略详情页三点菜单里加一项「筛选股票」，面板与行情页那颗漏斗**同源**，八项条件分两组（涨跌与自选 / 股票类型），被筛掉的行从列表里**真正移除**，不靠隐藏。

> 版本 **2.18.0**（面板八行两组；2.16.1 起三项、2.17.0 起计数与空态） · 功能 id `stg_stock_filter` · 目标 App 国信金太阳 **8.6.0**（vc271，真机复验用到 8.7.1/vc274） · 实测设备 魅族 20 / Android 16 (SDK 36) / LSPosed 2.2.1
> 相关代码 [`feature/stgfilter/`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/stgfilter/) + [`feature/filter/`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/filter/)（**与行情页面板共用**的模型 / 落盘 / 面板工具）；姊妹篇 [FEATURE-MARKET-FILTER.md](FEATURE-MARKET-FILTER.md)（行情页那颗面板里追加的三行 + 筛排行榜）。

---

## 一、需求

1. 策略详情页右上角三点菜单（「修改名称」/「删除策略」）里，**「修改名称」下方**新增 `筛选股票`；
2. 点开弹出筛选页：`涨家股票 / 跌家股票 / 自选股票` 三个开关，2.18.0 起多一组 `股票类型`（创业板 / 科创板 / 北证A / 未开板次新 / ST股，与行情页「沪深京A」漏斗里那五项同名同义）。**勾选项之间是彼此独立的类别**：未勾选的类别、其股票一律不显示 —— 只勾「涨家股票」时，自选里那些正在涨的票**也不显示**；三个都不勾 = 不筛（全显示）；
3. 被筛掉的股票**真正从列表里移除**（滚动不留空白占位）；
4. 筛选状态**本地持久化**，关掉软件重开仍在；
5. 筛选页**复用软件内部现有筛选组件的 UI**（行情页「沪深京A」左侧那颗漏斗），原「取消」改成「重置」，「确定」不变；
6. 左上角「策略个股15只」要跟着筛 —— 宿主只写**全量**、筛完也不变，屏幕上只剩 3 行却还写 15 只，看着就是没筛。改成 `策略个股 保留数/总数`；
7. 筛到**一只不剩**时亮出**宿主自己的**「未选出符合条件的股票」空态视图，不留一片空白；
8. 筛选状态可**按策略 / 聚合组分开保存**：功能页多一个二级设置「筛选条件保存设置」，内含开关「按策略保存筛选条件」，出厂关；关着就是所有策略共用一份（§六）。

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

`dumpsys window` 侧同时可见 `Window{… PopupWindow:…, frame=[Rect(812, 204 - 1080, 459)]}` —— 确认是 **`PopupWindow`**，不是 `PopupMenu`、也不是 `Dialog`。对应布局 `res/layout/pop_ai_stg.xml`（`aapt2 dump xmltree` 实测）：

```xml
LinearLayout orientation=vertical background=@drawable/… layout_width=103dp layout_height=98dp
  ├─ TextView id=tv_edit_name  layout_height=0dp layout_weight=1 text="修改名称"
  ├─ ImageView background=#ff454761 layout_height=1dp
  └─ TextView id=tv_del_stg    layout_height=0dp layout_weight=1 text="删除策略"
```

> 关键点：两项都是 **`height=0dp + weight=1`** —— 高度完全由**容器**的 98dp 决定。这决定了注入新项时必须同时处理「容器高度」与「窗口高度」。

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

**为什么必须前置（不能用 after 钩子）**：`show*` 一执行，`PopupWindow` 就按当时的内容量好尺寸交给 WindowManager；之后再往内容里 `addView`，新项会被**裁掉**（实测第三项完全不可见）。只有「改完内容再 `proceed()`」才能让测量把新项算进去。

**为什么用「找控件」而不是「认类名」判定目标菜单**：`PopupWindow` 是**系统类**，整个 App 到处在弹（隐私提示、主题切换…）。这里只认一条：**内容视图里找得到 `#tv_del_stg`**。找不到就放过，零副作用。

### 三个真机踩出来的坑

| # | 现象 | 根因 | 改法 |
|---|---|---|---|
| 1 | 注入后三项被压扁（每项 114px → 77px） | 容器高度恒为 98dp，三项按 weight 均分 | 把**每个 TextView 子项**的 `weight` 去掉、高度固定成 `98dp / 2 = 49dp`，容器高度设 `WRAP_CONTENT` |
| 2 | 改完仍只有两项，第三项被裁 | `inflate(layout, null)` 出来的根 View **没有 `LayoutParams`**（parent 传 null），`parent.layoutParams` 为 null，改不动 | 同上 —— 改的是**子项**，不是容器 |
| 3 | 第三项出现但仍在窗口下沿被切 | `PopupWindow` 是**用固定尺寸 new 出来的**（实测窗口高度恒为 98dp），容器改好窗口也不认 | 同时把 `pop.height` 设成 `WRAP_CONTENT`，让窗口按新内容重新量（撤销时还原原值） |

撤销（关掉功能）时：移除注入项与分隔线 → 子项 `weight` 还原成 1、`height` 还原成 0 → `pop.height` 还原成注入前记录的原始值。

**新项样式来源**：从原菜单里的兄弟 View 克隆，不手写任何一个样式值 —— 文字项克隆 `#tv_del_stg`（背景含状态、字色、字号、字体、内边距、gravity、`includeFontPadding`、`maxLines`、`minWidth/minHeight`、`LayoutParams`），分隔线克隆原菜单里那颗 `ImageView`（只画背景色）。

---

## 四、筛选面板：真的 inflate App 的布局

**不照着截图重画**，而是把目标 App 自己的两个布局资源拿来用：

| 用途 | 资源 |
|---|---|
| 面板外壳 | `res/layout/dialog_marketfliter.xml`（原包把 filter 拼成了 **fliter**，照抄） |
| 每一行 | `res/layout/item_market_filter.xml` |

行情页「沪深京A」左侧漏斗点出来的就是这套，所以圆角背景、标题字号、副标题灰底块、分隔线、45dp 行高、圆形勾选框、底部两颗按钮**全部同源**，App 换肤 / 深色模式自动跟随。

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

行：ConstraintLayout #itemroot（clickable, 45dp）
 ├─ TextView #name（marginStart 10dp）
 ├─ CheckBox #cb_checked（30dp，marginEnd 15dp）
 └─ View（0.5dp 分隔线）
```

### 三处改动

1. **文案**：`tv1` =「筛选股票」、`tv2` =「勾选后对应的条件才会生效」、`tv3` =「筛选条件」；
2. **`tv_cancel` 文案 `取消` → `重置`**（`tv_ok` 的 `确定` 一字不动）；
3. **行容器**：原 `RecyclerView #rv_markets` 的 LayoutManager 与 adapter 由 App 代码运行时设，只 inflate 布局拿不到那段代码 —— 所以把它**原地换成一个垂直 `LinearLayout`**（`StockFilterPanelUi.wrapWithVerticalHost`）：

```kotlin
val lp = target.layoutParams          // ← 复用同一个实例！
parent.removeView(target)
parent.addView(LinearLayout(ctx).apply { orientation = VERTICAL; layoutParams = lp }, index)
```

面板根是 `ConstraintLayout`，约束就存在 `ConstraintLayout.LayoutParams` 里，把同一个实例挂到新 View 上，位置 / margin / 约束**一条不丢**。顺带的好处：模块编译期**不需要**依赖 androidx.recyclerview / constraintlayout（这两个库都不在模块 classpath 里）。另外分组折叠箭头（`tv3` 右侧那颗无 id 的 ImageView）**隐藏** —— 原版分组可折叠，这里只有固定八行，留个点了没反应的箭头是骗人；面板背景 `?attr/shape_half_top_round_solid_12` 若在当前主题下解析不到，用 `theme.resolveAttribute` 自己补一次。

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

两维取交集。「股票类型」的判定（字段优先、代码 / 名称兜底）见 `feature/filter/StockTypes.kt`：`StockInfo` 的 `isCyb` / `isKCPlate` / `isXXLD` 为 true 即认定，再用代码前缀（300/301、688/689、43/83/87/88/920）与名称（含 `ST`）兜底 —— 策略页的数据源未必把这些字段填全（`isZx` 在策略页就恒为 false，同一个坑）。代码 / 分组头 / 建行 / 样式克隆都收进 `feature/filter/StockFilterPanelUi.kt`，行情页那颗面板追加三行时用的是同一批函数（见 [FEATURE-MARKET-FILTER](FEATURE-MARKET-FILTER.md) §四）；本页这颗面板的外壳与八行组装在 `feature/stgfilter/StgFilterPanel.kt`。

### 交互

| 动作 | 行为 |
|---|---|
| 点**整行** | 切换该项勾选（与行情页漏斗一致；勾选框本身也可点） |
| **重置** | 八项全部回到出厂（方向全不勾 + 类型全勾 = 不筛）+ **立即生效**（落盘 + 刷新列表）+ **面板不关** |
| **确定** | 落盘 + 生效 + 关闭面板 |
| 点面板外 / 返回 | 关闭，不改动 |

> 「重置」为什么不顺手关掉面板：原「取消」的语义是「放弃改动并关闭」，而「重置」是「把已生效的条件清掉」—— 关掉会出现「重置完还想再选却得重开」的别扭。

---

## 五、行过滤

### 首帧就得是筛过的

筛选生效时点进详情页，列表**第一帧**必须是筛过的。数据有两条到列表的路：

| 路径 | 谁管 |
|---|---|
| 宿主 `data.clear(); data.addAll(...); notifyDataSetChanged()` | `BaseAdapter.notifyDataSetChanged` 的**前置**钩子：放行前先把过滤代理装好 |
| 宿主把**已填好数据**的适配器直接 `setAdapter(...)` 挂上来 | `ListView.setAdapter` 的**后置**钩子：装完代理再让这一帧过去 |

第二条原先没人管：列表先按全量布局一帧，等 `getView` 的兜底补筛（**延后一帧**）才变筛过的 —— 肉眼就是「闪几帧未筛选的列表」。现在两条路都在「布局之前」把代理装好，`getView` 的兜底只留作最后一道保险。顺带一个好副作用：`setAdapter` 通常发生在 `onCreate`（比 `onResume` 早），这个钩子会把「当前在看的 Activity / 列表」提前认下来 —— 之前只有 `onResume` 之后才认，宿主早于它发的通知一律够不着（早退原因 `没有 trackedActivity`）。

### 真正移除：给 ListView 套一层过滤代理

**不是藏起来，而是让列表里根本没有这些行。** 做法是给 `ListView` 装一个模块自己写的 `FilteredAdapter`，包在 `AIStkAdapter` 外面：

```
筛选生效  →  lv.adapter = FilteredAdapter(原 adapter, 全量下标表)
                ├─ getCount()               → 子集行数
                ├─ getView / getItem        → 用全量下标转调原 adapter
                └─ registerDataSetObserver  → 转发给原 adapter（否则宿主的 notify 刷不到列表）
筛选取消  →  lv.adapter = 原 adapter
```

ListView 眼里列表本来就"只有这些行"，**不存在占位空间**，滚动 / 翻页 / 惯性滑动全是原生行为。这也是唯一能根治「下滑出大片空白」的做法（见下文「走过的弯路」）。

#### 为什么不能直接改 `data`

早期版本就是把 `AIStkAdapter.data` 整个换成筛过的子集，**这条路是错的**：宿主 `AIStkStgDetailAct.onItemClick` 是**拿 adapter 的 `getCount()` 做范围校验、却用它自己那份全量列表取值**的。真机日志（筛选只剩 1 只时点第一行）：`全量 35 → 保留 1` → `点击 position=1 → 映射成全量下标 2 转发给宿主` → 宿主 `onItemClick` 进入 position=2 → 正常返回（没抛）→ **页面一动不动**。`2 - 1 = 1` 正好卡在 `getCount() = 1` 的边界上，被范围检查挡掉，不崩不报错、静默无反应。

**推论：只要把 `data` 换成子集，「全量下标 ≥ 筛选后行数」的行就永远点不动。** 筛得越狠点不动的行越多：保留三十多只时几乎看不出来，只保留几只时整列都点不动。所以代理方案不是"更好"，是**必须**。

挂点：

| 挂点 | 作用 |
|---|---|
| `android.widget.BaseAdapter#notifyDataSetChanged`（**前置**改写） | 宿主一说"数据变了"，先把代理的下标表刷新到位再放行 → 布局第一帧就是筛过的结果 |
| `BaseQuickAdapter#getView` / `AIStkAdapter#getView`（后置） | 记一条行内涨跌幅样本（见「涨跌幅与自选怎么取」）；并**延后一帧**补一次筛选：宿主若自己 `setAdapter` 把代理顶掉，或只 `setAdapter` 不发通知，都在这里装回去 |
| `android.app.Activity#onResume`（后置） | 回到本页时重读筛选状态、只对齐当前在用的适配器、补装点击回映射；**再延 600ms 复查一次** —— 从个股页返回时宿主还在拉数据，第一次对齐往往什么都装不上；并拉起动态重算的节拍器 |
| `android.app.Activity#onPause`（后置） | 页面切走就停表 —— 不停的话退到后台还会每 500ms 醒一次主线程去扫一个没人看的列表 |
| `ListView#setAdapter`（后置） | 名单上第一时间装代理（见上一节） |

> 代理的 `getView(position, ...)` 会把 `position` 换算成**全量下标**再转调原 adapter，所以挂在 `BaseQuickAdapter#getView` 上的钩子拿到的仍是它熟悉的全量语义，涨跌幅时间线（`percentTrack`）里的键也依旧对得上。

时序与幂等：`notifyDataSetChanged` 声明在**框架类 `BaseAdapter`** 上（`BaseQuickAdapter` 没覆写），只能挂框架类，用「`thisObject` 是不是目标适配器」过滤；每轮重算比较「筛出来的结果」+「代理是否还挂在列表上」，**一致就直接返回**，一个字节都不动 —— 宿主行情刷新很勤，别再给它多加一次"数据变了"；结果与下标表合在一个 `Snapshot` 里**一起替换**，避免点击回映射读到「新结果 + 旧下标」；代理还在列表上（`lv.adapter` 拆包后就是它）就只 `update(下标表)`、不重建 —— 省掉一次 `setAdapter` 带来的滚动位置重置。

⚠️ **一切操作只认「当前列表在用的那个适配器」**（`liveAdapter()`）。`states` 是按**适配器实例**存的弱表，页面重建 / 宿主换过适配器之后里面会同时留着旧实例 —— 照旧实例去装代理，等于把当前 ListView 的数据源换成一份过期内容（返回详情页时旧实例往往已被清空），**整列股票都会空掉**；`WeakHashMap` 遍历顺序不定，所以只在某些时候才撞上。`applyFilter` 里另有一道同步保险：传进来的适配器不是当前在用的那个就直接返回。

#### 代理怎么被认出来：类名会被 R8 混淆，得靠身份

代理装上去之后，**每一步都要先认出「列表上这个 adapter 是我们的代理」**才能拆出原适配器（`unwrapProxy` / `ensureClickRemap` / 还原全量 / `liveAdapter`）。原先只有一条判据：类名里含 `FilteredAdapter`（为的是**跨热重载实例**也能认 —— 新旧实例的类是不同 ClassLoader 下的同名类，`is` 判断认不出）。但模块自己也开 R8：装机包里 `…StgStockFilterFeature$FilteredAdapter` → `f2.m`，于是这条判据连**自己刚装上去的**代理都认不出。真机症状：**点「确定」筛选不生效、退出重进才生效**（重进时列表上还是宿主的原适配器，那条路不需要认代理，所以「看着能用」）；顺带行点击回映射也装不上（`ensureClickRemap` 判定「adapter 不是目标：f2.m」），筛完点行会按子集下标索引全量列表 → **点错股票**。

| 场景 | 判据 |
|---|---|
| 本实例 | `proxiedLists[lv]` 的**身份**直判（装代理时记下「列表 → 代理 + 原适配器」，弱引用键） |
| 热重载后的旧实例 | 退回类名拆包 —— 类名与 `getTarget()` 由 `app/proguard-rules.pro` 的 `-keepnames` / `-keepclassmembers` 保住 |

诊断留痕：`applyFilter` 的每个静默早退都记一条 `筛选股票：本轮筛选没生效 —— <原因>`（同一原因只记一次），用户导出的日志里直接能看到原因。

#### 代理会改掉行点击的 `position` 语义 → 必须做下标回映射

ListView 上挂着代理，`position` 变成**子集下标**；而宿主的点击处理是拿 `position` 去索引**它自己那份全量列表**。实测（不做映射时）：筛到只剩 1 只「华建集团」→ 点它 → 打开的是全量第 1 只「莱美药业」，不崩不报错、静默进错股票。修法是给 ListView 的 `OnItemClickListener` 包一层：

```kotlin
mapped = header + keptIndices[position - header]     // 子集下标 → 全量下标
existing?.onItemClick(parent, view, mapped, id)      // 再转发给宿主原监听器
```

- `AdapterView.mOnItemClickListener` 是**隐藏 API**，得反射取；
- 换算时要让掉 `headerViewsCount`（横向表头 / 固定列）；`ListView.adapter` 可能是 `HeaderViewListAdapter`，要 `getWrappedAdapter()` 拆包；
- 没装筛选时 `keptIndices` 为空 → 等价于原样透传，天然安全；
- 多选态下手动点行**不要搬 checked states**：框架按**适配器位置**（header + 子集下标）取反写 `mCheckStates`，屏幕上的 `CheckedTextView` 与 `mCheckedItemCount` 读的就是这一格；宿主需要的是 `header + 全量下标`。只改转发给宿主监听器的 `position`，两边各用各的坐标 —— 把状态搬到全量位置会让勾选落到**下一行**；
- 转发给宿主监听器时带上 `StgFilterBridge.runWithRemapBypassed`：宿主若用 `parent.getItemAtPosition(全量下标)` 取值，代理不能再映射一次；
- 只有过滤代理**确实挂在列表上**时才发布 `keptIndices`，否则列表还是全量数据，而「全选」会按下标表把子集下标当全量下标用；
- **筛选结果通道是进程级单例，快照必须绑定到「算出它的那个全量列表」与「列表上的代理」**：`StgFilterBridge.publish` 带上全量列表与代理，`keepOnly` 只认**同一个实例或内容逐项 `===` 的副本**（宿主构造 EngineIntent 时可能复制列表），`fullIndexAt` / `fullIndices` 按 owner + 行数校验。宿主可能在多个策略页之间复用同一批 `StockInfo`，只按元素身份匹配会把上一页的筛选结果套到这一页（直达行情的翻页列表被无故裁掉几只）。换页 / 列表重建 / 内容对不上 / 行数对不上时一律按「没筛」或「放弃全选」处理；`fullIndices` 不做逐项 `?: it` 兜底（混用两套坐标会点到别的股票）；
- **筛选期间代理不声明 stable id**：代理改变了 position 语义，而 `AbsListView` 的 id 表会在 `handleDataChanged()` 里用 `getItemId(position)` 反查重建，两套坐标下必然把勾选挪到别的行（或删掉并减计数）；checked states / 计数照常工作；
- **其他功能读这个列表时也要先拆代理**：`WatchlistFeature` 的 onResume 重绘原先只拆 `HeaderViewListAdapter`，筛选生效时 `isTargetAdapter(代理类名)` 为 false → 整段静默跳过（从个股页加 / 删自选返回后名字不刷新）。现在先拆代理（跨包反射必须走 `Reflect.findMethod` 设 `isAccessible`，否则包私有类会抛 `IllegalAccessException`），再用 `keptIndices` 把可见行的子集下标换回全量下标；快照不属于当前代理就整段跳过（宁可少染，不能染错）。

### 勾选态必须按代码迁移，不能按位置

**现象**（与「我的策略聚合」叠加时最容易看见）：筛完之后勾选「飘」到别的行上；接着点「勾选加入自选股 → 确认添加」，分组里少的 / 错的正是屏幕上勾的那几只。它往往**自己会好** —— 下一轮筛选重算又把列表刷回去，所以看着像「一闪而过的不准」。

**机制**（两条独立原因叠在一起）：

1. `AbsListView.mCheckStates` 按**适配器位置**存。代理只换 `indices`（子集 ↔ 全量），`proxy.update(newRows)` 换了行集合却没动 checked states → 位置 i 上坐的已经是另一只票，勾选原地留下 = 「勾着 A，结算成 B」。
2. 代理的 `hasStableIds()` 恒 `false`，所以 `lv.adapter = 新代理` 之后 `handleDataChanged()` 没有 id 表可用，勾选只能按位置保留或直接失效。

聚合页把这条放大：`StrategyMergeFeature.reapplyIfNeeded` 每轮重新写并集再 notify，行集合变化比平时勤得多。

**修法**：`applyFilter` 在换 / 更新代理的**前后**各做一次按代码对齐 ——

```kotlin
// 换之前：用此刻挂在列表上的适配器，把「勾着哪几只」读成 Set<代码@市场>
val liveOld = (unwrapHeaderAdapter(lv.adapter) as? android.widget.ListAdapter) ?: base
val oldRows = if (proxy != null && liveOld === proxy) snap?.keptIndices else null
val checkedBefore = checkedKeysOf(lv, liveOld, base, oldRows)
// …装代理 / proxy.update(rows)…
// 换之后：按新下标表整趟读全、再全量对齐（不是只追加拿）
val moved = restoreCheckedKeys(lv, base, rows, checkedBefore)
StgRowSelection.publish(lv, checkedBefore)   // 无论有没有迁移，结论都发到跨功能通道
StgRowSelection.bumpRowEpoch()
```

口径要点：

- **读不全就整体放弃**（`StgRowChecked.keysOf` 返回 null）：迁移错的勾选比不迁移更坏，那会真的把错票加进分组；
- **读行代码不许问代理**：旁路帧里 `FilteredAdapter.getItem(position)` 把 position 当**全量**下标，问到的是全量同下标的**另一只票**（聚合页点一行会连跑两趟迁移，错得最稳）。一律用旧 `keptIndices` 换回全量下标再问 base 适配器 —— 实现就是 `camOfRow(base, rows, position)`；
- **键必须 `代码@市场`**：只取代码的话，同代码不同市场会互相认领，搬勾选搬到另一只票上；
- **换完必须发信号**：`StgRowSelection.publish` + `bumpRowEpoch` —— 批量点行的循环靠它发现「行集合被换了」，跨功能口径正本见 [FEATURE-ROW-SELECTION.md](FEATURE-ROW-SELECTION.md)；
- 只在 `choiceMode == CHOICE_MODE_MULTIPLE` 做：单条模式没有「哪几只」；
- 写回是**全量对齐**（勾着的留下、没勾的去掉），不是只追加拿 —— 漂移会把原来勾的那行留在勾上，只补不加会让错误累积；
- 日志关键字 `筛选股票：勾选态按代码迁移 N 行（勾着 M 只，筛后 K 行）`，无迁移时不打。

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

**结论：只要还依赖"行视图"这条路，就永远是治标**，所以最终改成换数据。这两条 `ListView` 的行为结论本身可靠，以后遇到"要藏一行但不留白"的场景仍可复用 —— 只是别用它来做筛选。

### 涨跌幅与自选怎么取

- **涨跌幅**：数据项字段（实测 `zdf`，`BigDecimal`；候选名列在 `PERCENT_FIELDS`）与行内 `#tv_item_stk_percent` 的文案（`+9.99%` / `-1.45%` / `--`）**两侧都留样本，比谁最近变化过** —— `PercentTimeline`：每个「代码.市场」各记「值 + 观察时间」，取时间戳更晚的一侧，平手取文案。字段变了立刻回到字段，文案变了立刻听文案；这是「涨转跌能被剔除、跌转涨能回补」的依据。
- ⚠️ **为什么不能「字段优先」**：宿主行情是**原地刷行内文案**推的 —— 既不回写数据项字段，也不发 `notifyDataSetChanged`。字段若是加载时的快照，「字段优先」会把一只股票的类别钉死：涨的变成跌了，它却一直留在列表里。
- **动态重算（节拍器）**：`onResume` / 面板确定后启动，每 `TICK_MS = 500ms` 一拍 —— 先扫**屏幕上可见的行**（文案有变化、或代理不在列表上，才真的整份重算一次；**文案没变就整拍跳过**，整拍成本只是遍历十几行子 View）。另外每 `SLOW_TICKS = 10` 拍（≈5s）无条件补一次全量重算 —— 被筛掉的行看不见、文案停更，它的「跌转涨」只能靠数据项字段刷新翻案，而字段刷新未必伴随通知；结果没变时重算自己会早退，不会发通知打断滚动。`Activity.onPause` / 页面销毁 / 关掉开关 / 没有生效条件时停表并清空时间线。
- 由此得到两个必要行为：被筛掉的行不再 bind、文案停更 → 它的判定**粘住**，不会下一轮又飘回来；宿主真刷新数据（新对象、字段值变了）→ 字段时间戳更新 → 立刻回到字段，跌转涨的能回补。
- 换数据之后被筛掉的行压根不在列表里，挂 `TextWatcher` 也放不回来 —— 所以这里**不挂 watcher**，也**不 Hook `TextView.setText`**（全 App 最热路径，见 [FEATURE-SELECT-ALL](FEATURE-SELECT-ALL.md)）。
- **自选**：先用 `ZxWatchlist.snapshotCodes()` 取一张**自选代码集合**（直接读 `ZXManager.m_stkGrpMap` 的键、取点前的代码，一趟筛选只读一次），命中即自选；读不出（没绑定 / 反射失败 / 表为空）才回落逐只问 `ZXWorkflow.isBelongByStock(code, market)`（20/20 交叉验证过的那个判定 API）。

#### 屏幕文案必须按行视图标记采集，不能按位置反推

**现象**（同一件事的两种表现）：勾「涨家」不勾「自选」，进页面自动应用之后列表里**多出不该在的票**（自选票、跌幅票）；手动打开面板再点一次「确定」，有时多出一只、有时又变成筛得太狠（`策略个股 1/138`）。根子是同一个：**采集行内涨跌幅时，把「屏幕第几行」当成「全量第几行」**。

**机制**：节拍器每拍扫一遍可见行，旧写法靠 `keptIndices` 把屏幕位置翻译成全量下标（`val idx = if (rows == null) dataPos else rows[dataPos]`）。这套翻译只在「`ListView` 的布局」与「`keptIndices`」出自**同一次**时才成立，而换行集合的路径是：`applyFilter` 重算 → `proxy.update(rows)` 换掉下标表 → `ListView` 要等下一轮 measure-layout 才按新集合重新 bind → 节拍器很可能在第二步**之前**就醒了（`firstVisiblePosition` / `childCount` 还是旧布局，`rows` 已是新表）→ 屏幕第 i 行的文案被记到**另一只股票**名下。错样本进了 `PercentTimeline` 就再也分不清是谁的：判成「涨」的票多留在列表里，判成「跌」的票被误杀。

**试过的错修法与作废原因**：给这套翻译加一道时间闸门（行集合变更后静默 1200ms 再采，`StgSweepGate`）—— 结果更糟，闸门把「节拍器下一拍自我纠正」这条唯一兜底也一起掐掉了：首屏若本来就采错，后面再也不会有正确样本覆盖它（真机表现「筛出 1/138，手动再点一次还是不恢复」）。闸门已删除，别再往回走。

**定稿修法**：让每行自己带着「我绑的是哪只票」，采集时只认这个标记，**不做任何位置换算**。

```kotlin
// 宿主 getView 时顺手记下（position 是全量下标，天然正确）
private fun recordRowPercent(adapter: Any, position: Int, row: View) {
    val cam = codeAndMarket(itemAt(adapter, position) ?: return) ?: return
    rowCams[row] = cam                       // WeakHashMap<View, String>
    percentTrack.observeText(cam, parsePercent(percentView(row)?.text?.toString()) ?: return, now)
}

// 节拍器扫屏幕：没有任何下标运算
private fun sweepVisiblePercents(lv: ListView, adapter: Any): Boolean {
    for (i in 0 until lv.childCount) {
        val row = lv.getChildAt(i) ?: continue
        val cam = rowCams[row] ?: continue    // 没记过 = 还没 bind 过，跳过
        val pct = parsePercent(percentView(row)?.text?.toString()) ?: continue
        if (percentTrack.observeText(cam, pct, now)) changed = true
    }
}
```

| 规则 | 为什么 |
| --- | --- |
| 采集只认 `rowCams[row]`，绝不按 `firstVisiblePosition + i - header` 反推 | 反推依赖「布局与下标表同一次」，换行集合那几帧必然不同次 |
| 同一个 view 复用来显示别的票时，标记当场覆盖 | 标记永远等于「这行此刻显示的那只票」，与位置、下标表、筛没筛都无关 |
| 没标记就跳过，不猜 | 首屏第一帧还没 bind 过等于没有样本，猜一个比不采更坏 |

`rowCams` 是弱引用表：行视图归宿主的 `ListView` 管，我们不拖住它们。验证看这两行应成对出现且计数一致（`左上角计数 → 策略个股 121/138` / `全量 138 → 保留 121（涨 1 / 跌 2 / 自选 135 / 平盘 0 …；自选表 232 只）`），反复开关面板点「确定」三次计数不应漂移。

#### 节拍器必须在 onResume 就当起，不能被 liveAdapter 判空带掉

**现象**：进策略详情页首屏就筛错 —— 自选票、跌幅票都还在列表里；手点一次「确定」立刻正确，日志里**从来没有** `筛选股票：动态重算已启动` 这一行。

**机制**：宿主行情是「原地刷行内文案」推的，不发 `notifyDataSetChanged` —— 所以「字段晚就绪导致的首屏错筛」唯一的纠正路径就是那个 500ms 节拍器（文案变了就重算，外加每 10 拍无条件全量重算一次）。而它原先排在判空之后：

```kotlin
// 3.8.10 及以前（三处同款）
val a = liveAdapter(activity) ?: return   // 进页面这一瞬宿主还没 setAdapter → null
startPercentTicker(env, activity)          // ← 永远走不到
```

进页面那一瞬 `lvStks` 往往还没 `setAdapter`，`liveAdapter` 返回 `null`，`return` 把 `startPercentTicker` 一起带走了 —— 节拍器一次都没起来，首屏那次错筛之后再没有任何一拍去纠正它。同轮还查出另外两道坎：

- `startPercentTicker` 开头写着 `if (!env.config.stgStockFilter || !filtersBy(env, act).active) return`，而 `onActivityResumed` 刚调过 `StockFilterScopes.forgetStrategyId`，那一刻 scope 可能还没认回来、`active` 是 `false` —— 位置对了节拍器照样不启动；
- `startPercentTicker` 里还有 `val h = act.window?.decorView?.handler ?: return`，`onResume` 那一瞬 `decorView` 常常还没挂。

**修法**：三处（`onActivityResumed` / `onFilterApplied` / `onConfigChanged`）都先把节拍器拉起来，再管这次对齐能不能做；启动条件只判功能开关，「这一拍要不要干活」交给 `tickOnce`（条件没就绪就空转一拍、**不停表**）；Handler 退回主线程 Looper。

```kotlin
private fun startPercentTicker(env: FeatureEnv, act: Activity) {
    if (!env.config.stgStockFilter) return                  // 不再判 active
    val h = act.window?.decorView?.handler
        ?: android.os.Handler(android.os.Looper.getMainLooper())   // 节拍器只要求主线程
    …
}

private fun tickOnce(env: FeatureEnv, act: Activity) {
    if (!env.config.stgStockFilter) { stopPercentTicker("功能已关闭"); return }
    if (!filtersBy(env, act).active) return                 // 空转，不停表
    …
}
```

| 规则 | 为什么 |
| --- | --- |
| 节拍器启动与「这次能不能对齐」解耦 | 两件事：对齐要求列表已在，节拍器只要求页面在 |
| 节拍器启动与「筛选条件是否就绪」解耦 | `onResume` 那一刻 scope 刚被清掉，`active` 恒为 false，拿它当启动条件等于永不启动 |
| 任何 `liveAdapter(activity) ?: return` 之后都不许再放「必须要跑」的副作用 | 进页面那几帧 `liveAdapter` 恒为 null，等于把副作用静默删掉 |
| 列表晚到靠每拍重找，不靠一次性绑定 | `trackedList` 只是快路径，`findListView` 才是兜底 |
| 「条件没就绪」只能空转，不能停表 | 停表后没有任何路径会重新拉起它 |

停表只在三种情况：功能关闭、页面结束、页面切走（`onPause`）。验证：装包冷启动进页面，日志三行依次出现（`onResume 命中策略详情页` / `动态重算已启动（每 500ms 采集一次可见行涨跌幅）` / `屏幕采集已就绪（3 行带标记，不依赖列表位置）`），首屏即 `策略个股 3/140`，静置 13 s 仍是 `3/140`（行情在动、节拍器在跑，结果不漂移）；首屏若仍先错，最多 10 拍（≈5 s）后慢拍全量重算会自己纠正，不需要手点「确定」。

### 方向那三个开关的组合规则（**互斥类别**）

需求明确：**未勾选的类别，其股票一律不显示**。所以是"先归类、再看该类别的开关"，不是"几个条件取交集"：

```
类别(股票) = 自选股票（在自选股里，最高优先）
           > 涨家股票（zdf > 0）
           > 跌家股票（zdf < 0）
           > 平盘（zdf == 0 或读不到）

显示(股票) = 没有任何开关勾选 ? true : 类别(股票) ∈ 已勾选的类别
             ∧（自选类别被勾中、且还勾了涨/跌时）自选股本身还要落在勾中的方向上
```

判定实现在 `StockFilters.keepsDirection`（类型维度见 §四，两维取交集）。实测（32 只的策略：涨 6 / 跌 12 / 自选 14 / 平盘 0；9 只的策略：涨 0 / 跌 0 / 自选 9 / 平盘 0）：

| 勾选 | 32 只那组保留 | 9 只那组保留 |
|---|---|---|
| 只勾 涨家股票 | **6**（非自选的涨股） | **0** |
| 只勾 跌家股票 | 12 | 0 |
| 只勾 自选股票 | 14 | 9 |
| 涨家股票 + 自选股票 | 6 + **自选里的涨家**（≤14，取决于自选内涨跌分布） | 9（那 9 只全在涨） |
| 三个都不勾 | 32（不筛） | 9（不筛） |

#### 自选类别内部再叠一层方向

原来「自选」是一个**兜住一切**的类别：只要勾了自选 + 涨家，自选股按 ZX 全留，涨 / 跌两项对它们完全不起作用 —— 用户看到的是「自选里的跌家照样显示，筛了跟没筛一样」。现在：**自选类别被勾中之后，若还勾了涨 / 跌，自选股也要落在勾中的那一侧**；只勾自选（涨跌都不勾）时仍然是「全部自选」，平盘（含读不到涨跌幅）哪一侧都不算。注意「只勾涨家、不勾自选」时自选股仍然**不显示** —— 类别优先是宿主原有规矩，这次没有动它（9 只那组只勾「涨家股票」得到 0，就是这条规则最直观的例子：那 9 只全在自选里）。

⚠️ **平盘股（`zdf == 0` / 读不到）不属于任何类别**，所以只要开了筛选就看不到它 —— 这是"未勾选的类别不显示"的直接推论（面板上并没有"平盘"这个开关）。若希望"三个全勾时平盘也显示"，需要再加一条规则（当前**没有**这么做）。

#### 自选判定必须按代码集合

**现象**：聚合组（754 只、成员 24 个）上勾「涨家股票」、不勾「自选股票」，列表里仍残留 2 只自选票；手动再点一次「筛选股票 → 确定」就干净了。

**机制**：宿主的 `StockInfo.codeAndMarket` 是**构造后填充**的，存在「只有代码、没有市场号」的中间态（`feature/filter/CodeMarketParts.kt` 的类注释里记过这个形态）。逐只判定这一侧两处相遇：`ZxWatchlist.isInWatchlist(code, market)` 第一行 `if (code.isNullOrEmpty() || market.isNullOrEmpty()) return false`；而筛选主循环从 `cam` 切出来的 `market` 在没有点时是 `null`。于是那一刻这只自选票被判成「非自选」，勾「涨家」不勾「自选」时按 `Category.UP` 被保留 → 页面里多出几只自选票；第二趟筛选时宿主已补齐市场号 → 又恢复正常。

**修法**（`applyFilter` 主循环前取一次，循环内查集合）：

```kotlin
val zxCodes = ZxWatchlist.snapshotCodes()          // null = 这一刻读不出来
...
val inZx = if (zxCodes != null) code != null && zxCodes.contains(code)
           else isInWatchlist(code, market ?: StockTypes.marketOf(code))
```

1. **一趟只读一次** `ZXManager.m_stkGrpMap`：原来每行一次 `ZXWorkflow.isBelongByStock` 跨类反射，754 只就是 754 次，现在 1 次；
2. **空表返回 null，不当「没有自选」**：宿主还没把自选加载进来时把整页判成非自选，比慢一拍严重得多；`null` 一律回落逐只判定（即引入本方法之前的行为）；
3. **回落那一路也补市场号**：`StockTypes.marketOf(code)`（6 开头或 9 非 92 → `2` 沪；0/2/3 → `1` 深；4/8/92 → `5` 北交所），所以「缺市场号」这一态即使走逐只判定也不会再漏；
4. **摘要日志带路标**：`；自选表 N 只` / `自选表读不出（逐只判定）`，排查时一眼看出这趟走的哪条路。

同一个「缺市场号」也影响勾选态迁移：`StgRowSelection.keyOf(code, market)` 原先在市场号为空时退化成纯代码，同一只票在「补齐前 / 补齐后」会算成两个键，迁移时勾选丢一次。现在改为 `market?.trim().orEmpty().ifEmpty { StockTypes.marketOf(c).orEmpty() }`，键恒定（见 [FEATURE-ROW-SELECTION](FEATURE-ROW-SELECTION.md) §三）。

真机证据（聚合组，条件 `up=true / down=false / zx=false`，**进页面自动应用**、没点「确定」）：`全量 738 → 保留 270（涨 270 / 跌 251 / 自选 205 / 平盘 12 …；自选表 223 只）` + `左上角计数 → 策略个股 270/738`，270 恰等于涨家数、205 只自选全部被排除，与 `gsaux_stg_filter.xml` 里 `filter_zx@grp:group2=false` 一致。

### 左上角计数：宿主只认全量，得由模块改写

那颗 `#tv_stg_res_num`（实测文案「策略个股8只」，在多选栏那一行）是**宿主**写的，它不认识我们的筛选 —— 筛完还是「策略个股15只」。`applyFilter` 每轮把它改写成 `策略个股 保留数/总数`（例：`策略个股 3/15`）：

- **取消筛选时按宿主原格式还原**（`策略个股15只`）。还原的前提是「当前文案确实是我们写过的那个格式」（正则 `^策略个股 \d+/\d+$`）—— 宿主自己写的文案一个字都不动；
- 宿主可能在我们之后又刷一遍，所以**只在真的改写过时**延后一帧（`lv.post`）再拽一次；加上 `notifyDataSetChanged` 前置钩子本来就会被行情刷新频繁触发，计数基本实时；
- 数据没到（`full` 为空）时**什么都不做** —— 那是宿主「先 clear 再等回包」的中间态，照着它写成「策略个股 0/0」会在每次刷新时闪一下。

### 空态：筛到 0 只时用宿主自己的「未选出符合条件的股票」

**不自己画**（字号、灰字、图标、内边距都跟宿主的换肤 / 深色模式绑在一起），直接把宿主的布局 `res/layout/empty_ai_stk.xml` inflate 出来摆上去。

#### 这个布局是怎么找到的

DEX 与自定义资源都加密，拿不到源码，但 `resources.arsc` 和 `res/*.xml` 是**明文**的（`aapt2` 能直接 dump）。从设备上把 `base.apk` 拉下来全表扫：

```bash
adb shell "cd /sdcard && unzip -o <base.apk 路径> resources.arsc"   # 3.2MB，明文
aapt2 dump resources base.apk | grep -iE "empty|nodata"              # 找候选
aapt2 dump xmltree --file res/layout/empty_ai_stk.xml base.apk
```

`empty_ai_stk.xml` 全文就一个 `TextView`：`layout_width=match_parent` / `layout_height=match_parent`、`gravity=center_horizontal`（**只水平居中，不是垂直居中**）、`paddingTop=23dp` / `paddingBottom=25dp`、`text="未选出符合条件的股票"`（文案**内联在布局里**，不在 `resources.arsc`）、`drawableTop=@drawable/…`（一只抱着空箱子的灰色吉祥物）。

> 正因为文案不在 `resources.arsc`，按资源表搜「未选出」搜不到（只有 `aapt2 dump xmltree` 才看得见）—— 一开始就在这里绕了一圈。

#### 摆放方式：只让出「股票行」，头部一行都不动

对照宿主自己的用法（`AIStkChooseDetailAct`「选股详情」，0 只股票时实测）：`#container_ai_stk_detail` → `#top`（标题栏）+ `#lvStks`（**空态在列表内部**：header 447px 之后紧跟那颗 TextView 空态）+ …。**头部原样留着、空态只占股票行那块**，这正是要复刻的效果。详情页的根是竖直 `LinearLayout`，`#lvStks` 是 `height=0dp + weight=1`，所以「只让出股票行」只能靠动列表自己：

| 步骤 | 做法 |
|---|---|
| 进空态 | ① 先收掉两件配件（见下）② 列表 `height` → `WRAP_CONTENT`、`weight` → 0（**压成只剩 header**，原 LayoutParams 先存一份）③ 空态视图 `height=0dp + weight=1`，接管让出来的那块 |
| 退出空态 | 空态 `GONE` → 还原列表 LayoutParams → 把两件配件放回去 |

> ⚠️ **不能把整个 `#lvStks` 收起来**（`AdapterView.setEmptyView` 内部就是这么干的）。策略名称 / 涨家数 / 标签 / 「策略个股N只 · 勾选加入自选股」**全在列表的 header 里**，收掉列表 = 把整页的头都抹了。列表的 `visibility` **一概不碰**（那是宿主的），只借它的高度。

#### 连配件一起收：列标题行 + 说明及风险提示

没有股票行了，`名称 / 最新价 / 涨跌幅` 和风险提示摆在空态旁边只是噪音，一起收掉 —— 宿主自己也是这么做的（`AIStkChooseDetailAct` 在 0 只股票时 `#ll_top` 就是 `GONE`）。两件的收法**不一样**：

| 配件 | 是什么 | 怎么收 |
|---|---|---|
| `名称 / 最新价 / 涨跌幅` | header 末尾的 `#ll_top`（普通 View） | 翻 `visibility = GONE` 就塌了（`LinearLayout` 跳过 GONE） |
| `说明及风险提示` | 挂在 ListView 上的固定视图（实测是 **footer**，248px、**没有 id**） | `GONE` **不塌陷**（见「走过的弯路」），只能 `removeFooterView` 摘下来 |

两个真机坑：**① 列标题行会被宿主显示回来** —— 用「收过了就不再收」的标志位，宿主刷新列表时自己把 `#ll_top` 重设回 `VISIBLE`，它又冒出来；改成**按当前 `visibility` 判断、每轮 `applyFilter` 都重新对齐**（幂等，代价是几个 `if`）。**② 风险提示会闪一下** —— 它是 ListView 的固定视图，而 ListView 只把**已经布局过**的固定视图挂成子 View，进空态那一刻列表还铺着几十行、footer 在屏外，`getChildAt` 压根找不到；两条改法：反射读 `ListView.mFooterViewInfos`（`FixedViewInfo.view` 是 public 字段），不依赖布局、**能在列表被压扁之前**就摘掉，一帧都不闪，`getChildAt` 只作兜底；**顺序先摘配件、再压列表**（反过来会先渲染出一帧「只剩头部 + 配件」再收，一样是闪）。

> `removeFooterView` 内部会回调 `onChanged()` → 重入我们的 `applyFilter`，所以 `detachedChrome[lv]` 必须在调它**之前**登记好，否则重入那一轮会对着同一块再摘一次。幂等靠「空态视图自己的 `visibility`」+「按当前状态判断」，**不设标志位** —— 从个股页返回时页面回调会重跑一遍筛选，标志位会跟真实状态脱节；状态没变时只走几个判断就返回。

---

## 六、筛选状态的持久化

存**目标 App 自己的** `SharedPreferences`：文件名 `gsaux_stg_filter`（`/data/data/com.guosen.android/shared_prefs/`），**八个布尔键** —— 方向三项沿用 2.16.1 的键名（老用户状态不丢），类型五项是 2.18.0 新增、**缺省即 true**（老用户升级后行为不变）。落盘实现在 [`feature/filter/StockFilterStore.kt`](../app/src/main/java/io/github/MVennn/GSAuxiliary/feature/filter/StockFilterStore.kt)（**两块面板共用**），进程级缓存在那里：谁改谁刷，策略页与行情页不会各看各的。

### 为什么不进模块配置

| 通道 | 能不能写 | 结论 |
|---|---|---|
| `ConfigKeys` + remote preferences | ❌ 目标进程侧**只读**（见 [REVERSE-ENGINEERING.md](REVERSE-ENGINEERING.md) 难点与解法 §7） | 用不了 |
| 模块自己的数据目录 | ❌ 目标进程是别的 UID | 用不了 |
| **目标 App 自己的 `SharedPreferences`** | ✅ 同进程、同 UID | **采用** |

于是筛选状态与 `DISCLAIMER_ACCEPTED` 同理：**不进** `ConfigKeys.ALL_KEYS / DEFAULTS`，「重置模块」不动它；「清除目标 App 数据 / 卸载重装」会清掉它，回到「三个都关」—— 这正是期望行为。模块配置里只留一个**功能总开关** `stg_stock_filter`（登记进 `FEATURE_KEYS`，出厂默认 `false`）。

### 按策略保存筛选条件（每个策略 / 聚合组各记一份）

出厂**关**：所有策略共用一份，就是上面那八个 `filter_*` 键，行为与老版本逐字一致。在 功能页 →「增加 筛选股票 菜单项」展开的 **筛选条件保存设置** 里打开「按策略保存筛选条件」后，键名变成 `filter_<行>@<作用域>`，**每个策略 / 每个聚合组各记一份**：

| 页面 | 作用域 | 落盘键 |
|---|---|---|
| 普通策略详情页 | `stg:<m_stg.id>` | `filter_up@stg:1234567` |
| 聚合组页（与「我的策略聚合」联动） | `grp:<聚合组 id>` | `filter_up@grp:9f2c…` |
| 行情页漏斗面板 | 永远全局 | `filter_up`（**不受这个设置影响**） |

四条硬规则：

1. **组优先**。聚合页上 `m_stg.id` 正被宿主改来改去（聚合期间换成成员策略的 stId、结束时再还原），名字也被改写成「聚合 · 组名（N 条）」—— 这两个当场身份都不可信。组身份由聚合侧登记：`StrategyMergeFeature.start()` 在**改宿主之前**调 `StockFilterScopes.rememberMergePage(act, group.id, group.name)`，撤销点在 `releaseRollback()` 与 `onActivityDestroyed()`（关掉「我的策略聚合」后本页立刻退回按策略记）；
2. **首次进入不继承**。某个策略盘上没有自己那组键时按**出厂值**（不筛）返回，**不回灌**全局那份 —— 否则「第一次点开就被上一次在别的策略上设的条件筛掉」；
3. **缓存按槽分**。`StockFilterStore.cached` 是 `ConcurrentHashMap<String, StockFilters>`，槽位就是作用域；关掉设置时所有读写退回 `""` 那一槽，与历史行为一致；
4. **认不出身份就写全局**。作用域解析不出 `stg:` / `grp:` 时返回 `null`，读写走历史键 —— 绝不写出一份谁也读不到的野键（那等于「设了但没生效」）。

策略 id 的解析（`Activity → m_stkModel.m_stg.id`）走 `Reflect.findField`，而那里**没有 Field 缓存**，这条路径又在 `onGetView` 里**每行**走一次 —— 所以按 Activity 弱键缓存一份，`onActivityResumed` 时清掉（同一个 Activity 可能被宿主拿去做另一个策略的页面）。

设置项本身：配置键 `stg_filter_per_strategy`，归 `FEATURE_PARAM_KEYS`（子选项语义：重置恢复默认，而不是把功能关掉），出厂 `false`，进 `ConfigSnapshot.stgFilterPerStrategy` 与远程序列化通道。界面名字按 [NAMING.md](NAMING.md) 起、不照抄需求原话：二级入口走「X 设置」族（`筛选条件保存设置`，与 `聚合组设置` / `高亮颜色设置` 一套），开关名写成动宾短语（`按策略保存筛选条件`）。副标题两处分工不同：**入口不写**（入口名已说清进去做什么）；**开关行写状态句**，按 [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md) 第 3.1 节取 _on / _off 两套，中英各两条 —— 开 = 每个策略与聚合组各记一份，关 = 所有策略共用一份。标题只说「按策略」，聚合组这一层只在开关副标的 _on 里出现。

---

## 七、验收标准与实测结果

验收 25 项全部通过或已列明待验（逐条过程见 [CHANGELOG.md](../CHANGELOG.md)），结论按块归并：

| 块 | 结论 |
|---|---|
| 菜单 | 出现「筛选股票」且位于「修改名称」下方，三项等高（`[815,225][1076,352]` / `[815,355][1076,482]` / `[815,485][1076,612]`） |
| 面板外形 | 与行情页漏斗同形（45dp 行 / 两键各半宽），dump 坐标逐项吻合；「取消」变「重置」，「确定」不变 |
| 方向筛选 | 32 只那组：只勾涨 → 6、只勾跌 → 12、只勾自选 → 14、三个都不勾 → 32（不筛）；9 只那组：涨 → 0、跌 → 0、自选 → 9、不勾 → 9 |
| 不筛 / 重置 / 关开关 | 「重置」清空并立即恢复全量；关掉总开关立即恢复全量（`onConfigChanged`） |
| 真移除 | 滚到列表末尾不再出现空白；换数据后 ListView 只认子集，无占位行 |
| 点行进对票 | 筛「自选」点第 2 行 → 打开「华建集团 600629」，与所点行一致 |
| 与「直达行情 + 左右滑动」联动 | 筛「跌幅」保留 3 只，点第 1 行进入后左滑 4 次停在「华北制药」（那 3 只里的最后一只）不再越界；日志 `翻页列表按筛选结果收敛 35 → 3` |
| 进出个股页 | 筛选状态下反复进出若干次，列表与筛选结果都保持正常，未再出现整列空白 |
| 左上角计数 | `策略个股 20/100`；切回不筛 → 还原成 `策略个股100只` |
| 空态 | 筛到 0 只出现宿主自己的「未选出符合条件的股票」（吉祥物 + 文案），位于「策略个股 0/100 · 勾选加入自选股」**下方**、头部一行未动；退出后 `lvStks` 由 `0,124-1080,1012` 回到 `0,124-1080,2073`，20 行照常显示 |
| 空态配件与不闪 | 「名称 / 最新价 / 涨跌幅」与「说明及风险提示」一起收掉（uiautomator 里节点全部消失），切回有结果后 `名称` 回到 `[42,830][397,880]`、风险提示挂回列表；反射 `mFooterViewInfos` 在压列表**之前**摘掉，肉眼全程看不到它 |
| 持久化（强杀重启） | 存储层未改动，沿用上一版验收（面板勾选态一并恢复） |

**仍待真机**：① 关着「按策略保存筛选条件」→ 键名仍是 `filter_<行>`（单测 `StockFilterStoreKeyTest` 覆盖键名，真机待验）；② 打开后 A 策略筛「涨」→ 切到 B 策略是**不筛**、回 A 仍是「涨」；③ 聚合组页筛「涨」→ 进单个成员策略不跟着筛、再进该组仍是「涨」；④ 行情页漏斗面板在设置开 / 关前后都不受影响；⑤「筛选条件保存设置」页开关副标题跟着状态变（开「每个策略与聚合组各记一份」/ 关「所有策略共用一份」），入口行**不**带副标题（纯资源 + 一行 `if`，构建已过）。

---

## 八、策略详情页「100 只上限」是客户端还是服务端

**结论：服务端限制，客户端根本没有传条数 / 分页参数。** 把探针挂到 `com.android.org.conscrypt.ConscryptEngineSocket$SSLOutputStream` 上抓 SSL **明文**，拿到策略详情接口的完整请求（`Host: goldsundg.guosen.com.cn`，`Content-Length: 382`）：

```
POST /gsnews/strategy/queryStrategyQuoteDetail/1.0 HTTP/1.1
Content-Type: application/x-www-form-urlencoded; charset=UTF-8

stId=<策略id>&pkg=<渠道>&reqtime=<ts>&packtm=<ts>&sysVer=8.6.0
&softName=goldsun_android&netAddr=NA&rNetAddr=&device_model=…&deviceVers=…
&operorg=&userCode=<账号>&hwID=NA&conn_style=2.0.03.0.0&session=<会话>&openapi_sign=<签名>
```

（`userCode` / `session` / `openapi_sign` 已脱敏，**不要把真实值写进仓库**。）唯一的业务参数是 **`stId`**，其余全是客户端元信息 + 鉴权；**没有** `count` / `limit` / `pageSize` / `pageNo` / `num` 等任何条数参数（三次独立抓包参数集合与 `Content-Length` 完全一致）。旁证：策略列表 → 详情的 `Intent` 里只有 `Strategy{id, name, tags=[labelId…]}`，不含股票列表与数量；`AIStkStgDetailAct` 实例字段里也没有 100 / count / pageSize 常量。

⇒ 客户端每次都是「把整张列表要回来、全部渲染」，**上限只可能由服务端施加**；客户端改代码拿不到第 101 只数据，所以不做「解除上限」，也不做成功能模块。服务端哪天放开，客户端无需改动即可显示更多 —— 这条链路本来就是「给多少显示多少」。

> 顺带记下：金太阳**不用 OkHttp**（`okhttp3.OkHttpClient.newCall` / `ResponseBody.string()` 整段会话 0 命中），SSL 流上是 `POST … HTTP/1.1` + 自研 `com.guosen.androidnet.tcpb.*` 调用栈，即**自研 HTTP 客户端 + Conscrypt TLS**，OkHttp 只被少数第三方 SDK 用。

---

## 九、App 升级后的失效预案

| 失效现象 | 改哪里 |
|---|---|
| 菜单里没有「筛选股票」 | `StgStockFilterTargets.MENU_ANCHOR_ID / MENU_EDIT_NAME_ID`；用 `tools/probe` 重新 dump 菜单视图树 |
| 面板打不开 / 空面板 | 布局与控件 id 常量在 `feature/filter/` 的 `FilterPanelIds`（`object`，定义在 `StockFilterPanelUi.kt`，两块面板共用一份）：`LAYOUT_FILTER_PANEL / LAYOUT_FILTER_ITEM`、`ID_PANEL_* / ID_ITEM_*` |
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

排错先看 LSPosed 日志（`/data/adb/lspd/log/modules_*.log`，TAG `GSZXEnhance`）。按顺序应能看到：`已挂三点菜单展示点 ×5` → `已挂 BaseAdapter.notifyDataSetChanged ×1` → `已挂 BaseQuickAdapter.getView ×1` → `已挂 ListView.setAdapter ×1` → `已挂 Activity.onPause ×1` → `已在三点菜单「修改名称」下方注入「筛选股票」` → `菜单窗口原尺寸 width=… height=…（-1=MATCH_PARENT, -2=WRAP_CONTENT）` → `菜单项高度已归一（3 项 × …px）`；进页面后是 `onResume 命中策略详情页（筛选生效=true）` → `动态重算已启动（每 500ms 采集一次可见行涨跌幅）` → `屏幕采集已就绪（N 行带标记）` → `左上角计数 → 策略个股 6/32` → `全量 32 → 保留 6（涨 6 / 跌 12 / 自选 14 / 平盘 0 / 创业板 5 / 科创板 4 / 北证A 1 / 未开板次新 0 / ST股 2）`；空态进出另有 `已把宿主的空态布局摆到列表下面（empty_ai_stk）` → `列标题行（名称 / 最新价 / 涨跌幅）已收起` → `说明及风险提示已从列表上摘下（footer）` → `空态（未选出符合条件的股票）→ 显示（列表压成只剩头部）`，回来时 `列标题行已还原` / `说明及风险提示已挂回列表` / `空态 → 隐藏（列标题 / 风险提示 / 列表高度全部还原）`；退页 `动态重算已停止（页面已切走）`，关开关 `筛选未生效，已还原全量（N 只）`。

---

## 十、还没复验的点

| 项 | 风险 |
|---|---|
| **宿主改成从 adapter 取股** | 回映射建立在「宿主自己持有全量列表」这一**实测事实**上。哪天宿主改成从 adapter 取股，回映射会**反向错位**（那时应当把映射拆掉） |
| **宿主 `setAdapter` 后不再触发 `getView`** | 代理被顶掉后靠 `getView` 兜底装回。若宿主既换了 adapter、又完全不触发 `getView`（列表为空时有可能），筛选会暂时失效 —— 日志里能看到「暂时没装行点击回映射」那一行 |
| **自选类别内部叠方向** | 勾「自选股票」+「涨家股票」时，自选里的跌家会被剔除（旧行为是自选全部保留）。真值表已被 `StockFiltersTest` 的四个用例覆盖，真机待验：自选里同时有涨有跌时，勾这两项应只剩自选里的涨家；只勾「自选股票」时仍是一只不少 |
| **平盘股** | 只要开了方向筛选就不显示（见 §五）。若期望"三个全勾时平盘也显示"，需再加规则 |
| **`StockInfo.isXXLD` = 未开板次新** | 只做过字段名层面的确认（探针 dump），运行时取值未核对；认不出就不归类，不会误伤别的股票 |
| **筛后首帧 / 退页不醒主线程** | `PercentTimeline` + 500ms 节拍器的组合解决的是「涨转跌能剔除、跌转涨能回补」，仍需真机复验「首帧不闪 / 点击回映射 / 计数与空态不回归 / 退页后不再醒主线程」 |
