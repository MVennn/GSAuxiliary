# 我的盯盘：卡片跳转股票详情页

> 「智能盯盘 → 我的盯盘」里的卡片（股票名 + 提醒描述 + 右下角编辑 / 删除）
> 原本没有任何通往个股详情页的入口。本功能给卡片本身挂一个点击：点卡片空白处
> → 直接进该股的个股详情页。**界面一点没动** —— 不加箭头、不改底色、不加说明文字。
>
> 功能开关：`智能盯盘` 分区 →「我的盯盘卡片跳转股票详情页」（`aistare_card_detail`，
> 默认关；无二级菜单、无副标题 —— 标题已把「哪张卡片、点了去哪」说全）。
> 实现见 `feature/aistare/AiStareCardDetailFeature.kt` / `AiStareCardDetailTargets.kt`。

---

## 1. 页面与卡片结构（真机实测 2026-10-17，魅族 20 / 金太阳 8.6.0 vc271）

页面是**原生** Activity：`com.guosen.quotation.aistare.ui.AIStareIndexAct`
（顶栏「智能盯盘」+ 右上角「创建」，`uiautomator dump` 原文）：

```
ListView #listMyStare
  └ item（LinearLayout）
      ├ #layoutStock    LinearLayout  clickable=true   ← 股票名行：tvStkName / tvCodeMarket / ivStkArrow
      └ #layoutContent  LinearLayout  clickable=false  ← 白色卡片本体：tvDes / tvType / tvExpire / imgEdit / imgDel
```

- `#layoutStock`（股票名 + 代码 + ›）宿主自己设了监听，**我们不碰**；
- `#layoutContent`（白色卡片）原来不可点也不响应，**点击挂在这里**；
- `imgEdit` / `imgDel` 是两个自带 `clickable=true` 的 ImageView，**子视图先消费点击**，
  所以它们的行为一点都不会变 —— 这就是「不动右下角」的全部实现，没有其它守卫。

## 2. 挂点

```
ListView.setAdapter（进页面 / 切「今日提醒 ↔ 我的盯盘」页签都会走）
  └ this 是 ListView 且（id == listMyStare 或宿主是 AIStareIndexAct）
      └ adapter 类名命中 AIStareMyStareAdapter → 沿继承链找 getView 的实现类挂钩子
          └ getView 返回后：从数据对象取「代码.市场」→ 记到 (卡片视图 → cam) 弱表 → 挂点击
```

三个实测要点（App 升级后失效时先查这三条）：

1. **adapter 自身没有 `getView`**：`AIStareMyStareAdapter` 上 `methodsNamed("getView")`
   是空的，实现在父类 `com.guosen.commonui.adapter.BaseQuickAdapter`（与自选高亮
   `WatchlistFeature` 挂的是同一个基类）。所以要**沿继承链向上找第一个真实现了
   getView 的类**再挂；钩子里仍按实例（`knownAdapters`）过滤，只处理这一页的列表。
2. **只认「我的盯盘」的 adapter**（`TARGET_ADAPTERS`）：同页「今日提醒」页签是
   `AIStareTodayWarnAdapter`，卡片语义不同（点的是提醒不是股票），不接管。
   数据类 `MyStareItem` 自带 `codeAndMarket`（`"688592.2"`）—— 候选数组第一个就命中。
3. **`onResume` 兜底**：钩子装晚了的场景（热重载后页面已在栈里）由
   `Activity.onResume` 再对齐一次。

## 3. 点击后怎么跳

与「一键配置保存后拉起详情页」是**同一个契约**（`docs/FEATURE-DIRECT-QUOTE.md` 路径 C）：

```
Intent(action = "quotes.stockDetail")
  .setClassName(宿主包, "com.guosen.quotation.ui.StockFragAct")
  .putExtra("adapter_key", Bundle{ stockInfo = IntentStockInfo(code, market) })
  .addFlags(FLAG_ACTIVITY_NEW_TASK)
```

`IntentStockInfo` 自带 `(String code, String market)` 构造器；少这个 extras 详情页会直接崩。

## 4. 为什么「视图 → 代码」用弱表、不用 position

ListView 复用行视图，**position 只在 `getView` 那一刻是对的**；把 position 存进视图
（或任何跨帧的地方）迟早张冠李戴 —— 点第 3 只开出第 5 只，而且**不崩、不报错**，
比崩溃更难发现（同类教训见 `FEATURE-DIRECT-QUOTE.md` §3.3 第 6 条）。
所以：`getView` 时把 `codeAndMarket` 记进 `WeakHashMap<View, String>`（复用即覆盖），
点击时拿被点的卡片视图查表。监听器是同一个无状态对象，重复 `setOnClickListener` 无副作用。

## 5. 数据拿不到时：不跳

`tvCodeMarket` 只有 6 位代码、没有市场号，靠前缀猜太脆。数据对象里拿不到 cam 时的
兜底**只认沪（6/5 开头 → 2）与深（0/3/1 开头 → 1）**；北交所（4/8/9 开头）的市场号
没实测过，**不猜** —— 跳错比不跳更糟（宁缺毋滥）。

## 6. 配置与还原

- 开关关闭时：`onConfigChanged` 把已挂的卡片 `setOnClickListener(null)` 摘掉，
  复用视图上不残留；`getView` 里按当前配置决定挂 / 摘，改完设置切回 App 即生效。
- 全部反射与跳转包在 `runCatching` 里，异常只记日志，绝不抛给宿主。
