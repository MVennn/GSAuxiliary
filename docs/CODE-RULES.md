# 编码纪律

> **一句话**：从 2026-10-17 两轮审查的 6 高 / 10 中 / 17 低里提炼的必守规则 ——
> 每条都对应至少一个真实踩过的坑，写新代码时按主题对照，新功能合入前过一遍 §8 清单。

问题的**修法**在 CHANGELOG 与对应 FEATURE 文档里，这里只收「规则 + 为什么 +
违反时的典型症状」，不重复实现细节。本文是编码纪律的**单一正本**。

---

## 1. Xposed 生命周期与钩子卫生

**规则 1.1 · install 与还原必须成对设计。**
每个 Feature 的 `install()` 落下的每一笔（钩子、decorView 监听、注入视图、
窗口属性、替换的 adapter），都要能被自己的 `onConfigChanged(false)` 或
卸载路径撤销。写 install 时同步写还原，不留「以后再补」。

- 症状：关了开关功能还在跑；只有第一次关生效。
- 案例：热重载僵尸实例（审查高-1）——布局回调不是 Xposed 钩子，
  `unhookAll` 摘不掉，旧实例永远等不到关闭通知。

**规则 1.2 · 热重载语义：装饰性现场必须可被「陌生人」识别。**
`onHotReloaded` 只卸钩子。挂在宿主视图上的东西（listener、注入的子视图、
改过的 LayoutParams、替换的 adapter）在新实例眼里是**无主的**——识别手段
必须在**注入时就埋下**：keyed tag、类名标记、可反射拆包的代理，三选一。
还原逻辑不得只依赖「本实例的记账表」。

- 症状：热重载后关开关关不掉；新实例对残留现场放弃治疗（页面功能死掉）。
- 案例：StgStockFilter 跨实例代理识别（类名 + 反射 `getTarget()`）、
  StockDetailBar 的 keyed tag 存原宽 + restore 兜底扫描 + syncBar 自愈。

**规则 1.3 · 启动时跳过的功能必须保持「可补装」。**
`installAll` 会跳过未启用的功能；`onConfigChanged` 里的补装路径依赖
「Feature 没有启动期唯一性假设」。新功能的 `install()` 必须幂等友好：
不在 install 里读一次性的静态状态、不假设「一定在进程启动早期被调」。

- 症状：宿主不重启，新开的开关永远不生效（审查高-2，真机已验证补装链路）。

**规则 1.4 · 状态表一律 WeakReference + synchronized 容器。**
Activity / View / PopupWindow / WebView 的引用表用
`WeakHashMap` 或 `synchronizedList(WeakReference)`；Key 选视图本身或
decorView root，不选 position（复用会点 A 开 B）。

- 症状：Activity 泄漏、长时间使用后卡顿；列表复用后点错条目。

## 2. 热路径（每行 / 每帧 / 每次布局回调）

**规则 2.1 · 反射结果必须按类缓存。**
`getView`、布局回调这类高频路径里，`Reflect.findMethod/findField` 的结果
必须进 `WeakHashMap<Class<*>, 缓存对象>`（适配器类与条目类分两张表），
**解析失败也要缓存**，否则每行重扫整条继承链。

- 症状：列表滚动掉帧、GPU 没满 CPU 满。
- 案例：WatchlistFeature 每行全候选扫描（审查高-6）。

**规则 2.2 · 计算型 getter 不得出现在热路径上。**
会被每行 / 每帧读取的属性（如 `ConfigSnapshot.fingerprint`）必须构造期
一次算好存字段，不得 `get()` 里做 O(n) 哈希（方案 JSON 可达几十 KB）。

**规则 2.3 · 高频回调里「有动作就要冷却」。**
布局回调驱动的 sync：本轮真的改动了视图（用 mutations 计数判断），下一轮
1.5s 内直接跳过；空闲轮不冷却，保证真变化仍能响应。入页首次同步与
deferSync 排队重试不过冷却闸。

- 症状：宿主滚动/动画期间 CPU 占用异常高。

**规则 2.4 · 录制型 Modifier 必须条件挂载。**
`layerBackdrop` 这类每帧采样的修饰符，消费方不存在时（非玻璃模式）不得
常驻；过渡动画期（淡出 alpha>0）也算消费，条件里要盖住。

- 案例：一级页漏挂条件、dock 淡出期闪变（审查中-5）。

## 3. 日志与隐私

**规则 3.1 · INFO 及以上日志不得出现完整「6位代码.市场号」与含代码的 URL。**
个股代码过 `maskStockCode()`（保留前 2 位）或降 DEBUG；失败日志报状态码，
不带完整 URL。这条是硬性口径，贴日志是用户求助的主要方式。

**规则 3.2 · 注释措辞不得与免责声明 §8 矛盾。**
不写「伪装 / 规避风控 / 绕过检测」类措辞——注释是白纸黑字的意图证据。
UA 用常规浏览器形态，注释只说技术原因（行情接口对非浏览器 UA 拒答）。

- 案例：HttpTransport 旧注释与 DISCLAIMER §8 正面冲突（审查高-1，法律专项）。

## 4. JS 桥与注入脚本

**规则 4.1 · 桥的每个方法都要 URL 守卫。**
自建桥挂在宿主全部 WebView 上，页面内第三方脚本可调。除 apply 外，
新增的任何带副作用的桥方法（saved / manage / 未来的）一律先
`pricePageGuarded()` 校验，不通过直接 return。

**规则 4.2 · 调试口必须限频 + 截断。**
diag 类方法：单条 200 字符 + 1 秒 1 条，时间戳放外层类（每个 WebView 各一个
Bridge 实例，放 Bridge 里限不住全局）。

**规则 4.3 · Kotlin 裸串里的 JS：改动后真机验证。**
`$xxx` 要写 `${'$'}xxx`；任何 JS 改动都要在真机 H5 页面过一遍
（转义错了是运行时才炸，编译不报）。保守改法：不引入新的 `$`。

## 5. 数据与缓存

**规则 5.1 · 缓存 key 必须含全部影响结果的参数。**
日 K 缓存 key 漏了 n 维度 → 长短回扫混用时重复请求（审查中-7）。
新缓存设计时列全「哪些参数变了结果就变」，一个都不能少。

**规则 5.2 · 会话 memo 与通道缓存分层。**
会话内防重复（HashMap memo）管「同一会话点两下」，通道 TTL 缓存管
「跨会话复用」；失败结果不进 memo（要允许重试）。

**规则 5.3 · LSPosed remote prefs 的写路径保持 commit。**
`edit()` 换 `apply()` 的推送语义未验证过，**不要顺手优化**（审查低-9 明确不改）。

## 6. 依赖与构建

**规则 6.1 · 升级任何带 Compose/AAR 元数据的库，先跑 `checkDebugAarMetadata`。**
Miuix 0.9.4 实测被 `minAgpVersion = 9.1.0` 硬拦（Compose 1.12.0 全家桶），
`dependencies` 任务通过≠能构建。版本矩阵与案例见 [BUILD.md](BUILD.md) §为什么钉死。

**规则 6.2 · compileSdk / AGP / Miuix 三者是绑定关系**，单独动一个会连环炸，
联动调整前读 [BUILD.md](BUILD.md) 的版本矩阵一节。

## 7. 仓库与 git 纪律（对维护者）

**规则 7.1 · `reset --hard` 前必须 `git status`。**
工作区常有并发会话的在途改动；脏树切历史用
`git update-ref refs/heads/main origin/main`（树内容相同时 index 仍有效），
**绝不**带脏树 reset。

**规则 7.2 · `git add -A` 后必须 `git status` 复核再 commit。**
`.work/` 归档曾被 add -A 误提交进公开仓库，直接导致多一轮历史重写。

**规则 7.3 · 历史重写（filter-repo）后必须独立终验。**
用另一个子代理/会话从远端拉 zipball 与 API 实测，不能只看本地
`git log`——第一轮清洗漏掉的 `.work` 副本就是自己验自己漏掉的。

**规则 7.4 · 敏感路径只进 `.work/`（gitignored）**，归档移动用
`git mv` 后立刻 `git status` 确认目标路径没进暂存区。

## 8. 新功能合入前自检清单

1. install 落下的每一笔都有对应还原路径？（§1.1）
2. 注入的视图现场有 keyed tag / 类名标记，热重载后新实例能识别？（§1.2）
3. install 不依赖启动期唯一性，运行中补装能生效？（§1.3）
4. 状态表全是弱引用 + 同步容器？（§1.4）
5. 高频路径无未缓存反射、无计算型 getter？（§2.1/2.2）
6. 布局回调 sync 有动作后冷却？（§2.3）
7. INFO 日志无完整股票代码 / URL？（§3.1）
8. 新桥方法有 URL 守卫 + 限频？（§4.1/4.2）
9. 缓存 key 参数列全了？（§5.1）
10. 动过文档的话，DISCLAIMER / NAMING / 版本号口径是否按
    [MAINTENANCE.md](MAINTENANCE.md) §3 联动？
