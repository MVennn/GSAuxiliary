# 液态玻璃材质规范

> **一句话**：液态玻璃两档光效（完整 / 简约）长什么样、为什么这么定 —— 本篇是
> 两档表现的**设计正本**；实现位置与界面行为见 [UI.md](UI.md)，文案见 [NAMING.md](NAMING.md)。

---

## 1. 设计依据

两件事拼出本规范：

**Backdrop 库的效果语义**（[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
官方文档），硬约束有三条：

1. **效果叠序是强制的**：`colorFilter ⇒ blur ⇒ lens`，乱序会出视觉错误；
2. **lens（折射）的门槛最高**：Android 13+（RuntimeShader）、shape 必须是
   `CornerBasedShape`、`refractionHeight ≤ shape.minCornerRadius`（超出角上不连续）、
   `refractionAmount ≤ size.minDimension`；
3. **vibrancy = 饱和度 ×1.5**：只提艳、不提亮，是「鲜艳毛玻璃」的来源。

**Apple Liquid Glass 的三档体系**：苹果自己就分 **Clear（通透）／Regular（磨砂）／
Opaque（纯色兜底，对应系统「降低透明度」）** 三档 —— Clear 给小控件（折射感强），
Regular 给大表面（模糊 + 面色为主），Opaque 是无障碍与能力不足时的兜底。

我们原来的「玻璃光效」开关只有两态，简约版直接跌到**纯色**，导致简约版和关掉
液态玻璃几乎无法区分 —— 这一版规范按苹果的三档体系重新对齐（见 §3）。

## 2. 效果词汇表

| 效果 | 作用 | 门槛 | 观感贡献 |
|---|---|---|---|
| `vibrancy()` | 背景提艳 | API 31+ | 玻璃「活」，不灰 |
| `blur(r)` | 背景磨砂 | API 31+ | 可读性兜底 |
| `lens(h, a, chromaticAberration)` | 边缘折射放大 | API 33+、圆角形状 | 「真玻璃」的核心 |
| `Highlight` | 边缘 rim light | 无 | 浮层轮廓感（贴边表面禁用，会成横线） |
| `Shadow` | 落影 | 无 | 悬浮感 |
| `InnerShadow` | 内壁阴影 | 无 | 折射时的「厚度」 |
| 面色 `glassSurfaceTint` | 纯黑/白叠 alpha | 无 | 色彩倾向与可读性 |

交互物理（[LiquidPhysics.kt] 的三件套）不算效果，算**行为**：`pressProgress`
单标量驱动 blur↔lens 交叉、按压缩放、色差浮现；弹簧参数 `press (1f, 1000f)`。

## 3. 三档定义（正本）

| 档位 | 定位 | 对应 Apple | 谁决定 |
|---|---|---|---|
| **完整版** | 通透优先的「真玻璃」 | Clear | 「玻璃光效」开 |
| **简约版** | 可读性优先的「磨砂玻璃」 | Regular | 「玻璃光效」关 |
| **纯色兜底** | 不是档位，是降级 | Opaque | 系统能力不足时自动 |

**完整版**：`vibrancy + blur + lens`（交互面）、rim light、投影、内阴影、
交互物理全部启用。面色最薄（`GlassAlphaFull = 0.30`），通透感靠模糊与折射。

**简约版**：**保留 vibrancy + blur 与面色，关闭一切「光」的部分** ——
折射、色差、rim light、投影、内阴影、交互物理全部停用，按压只保留按压缩放。
面色比完整版**更实**（alpha 加深，见 §5），因为磨砂档的可读性优先于通透感。

**纯色兜底**：无任何 RenderEffect，不透明面色。只在系统能力不足时出现，
用户永远选不到它（这与旧行为不同：旧版「光效关 = 纯色」把兜底当成了档位）。

> **为什么简约版保留模糊**：模糊是「玻璃 vs 纸」的分界线，折射/色差/rim 才是
> 「光」。旧简约版砍掉模糊后与关闭液态玻璃无法区分，开关失去意义；
> 苹果的 Regular 档也正是「有模糊、无折射」。

## 4. 版本门槛与自动降级链

| 系统 | 能力 | 表现 |
|---|---|---|
| API 33+（Android 13+） | 全部效果 | 完整版 = 真玻璃；简约版 = 磨砂 |
| API 31–32 | 有 RenderEffect 无 RuntimeShader | lens 静默 no-op（库行为）：完整版交互面自动退磨砂，其余同上 |
| API ≤ 30 | 无 RenderEffect | **全体纯色兜底**，两档不可区分 |

降级是**逐效果自动的**（库内 `updateEffects` 直接跳过），接入方只需保证
「纯色兜底下面色不透明、文字可读」——这正是面色函数三分档的原因（§5）。

## 5. 面色规则（正本）

`glassSurfaceTint` 从二态升级为三态：`(dark, tier)`，tier ∈ {full, frost, plain}。

| tier | 深色 | 浅色 |
|---|---|---|
| full（完整版） | 黑 @ 0.30 | 白 @ 0.30 |
| frost（简约版） | 黑 @ **0.45** | 白 @ **0.45** |
| plain（兜底 / 关闭） | 黑 @ 1.0 | 白 @ 1.0 |

frost 比 full 深一档的原因：磨砂没有折射帮忙分离前景，面色要承担更多可读性；
但必须**保持半透明**——不透明就退化成了 plain。0.45 是起始值，真机校准后回填。

## 6. 逐表面参数矩阵（正本）

| 表面 | 效果链（完整版） | rim | 投影 | 交互 | 简约版 |
|---|---|---|---|---|---|
| 顶栏 `GlassTopBar` | vibrancy + blur(24dp) | **无**（贴边，描边会成横线） | Shadow.Default 随光效 | 无 | 磨砂，无投影 |
| 底栏面板 `LiquidGlassDock` | vibrancy + blur(8dp) + lens(24,24) | 随按压 | 无（常态），按压浮现 | 拖动收起 + 按压鼓起 | 磨砂，无按压折射 |
| 底栏滑块 | lens(10,14) + 色差（采样染色层） | 随按压 | 随按压 | DampedDrag + 甩动形变 | 实底色块（现状保留） |
| 弹窗 `DialogCard` | vibrancy + blur(24dp) | Default 随光效 | Shadow(24dp) 随光效 | 无 | 磨砂，无 rim 无投影 |
| 开关滑块 `GsSwitch` | blur↔lens(5,14)+色差 交叉 | 随按压 | Shadow(6dp) 随按压 | 拖动翻转 + 震动 + 1.35 放大 | 磨砂底 + 按压退 Miuix 档 1.127 |

**简约版统一句法**：效果链只留 `vibrancy + blur`，面色用 frost 档，
rim / 投影 / 内阴影 / lens 全部 alpha = 0 或不调用。

## 7. 新表面接入清单

1. 效果链顺序 `colorFilter ⇒ blur ⇒ lens`，不许乱；
2. 面色必须走 `glassSurfaceTint(dark, tier)`，不许自调 alpha；
3. 贴边表面（与屏幕边相接的整块）rim = null；浮层表面才允许 Highlight；
4. lens 参数守约束（height ≤ minCornerRadius、amount ≤ minDimension）；
5. 交互面必须给出简约版表现：pressProgress 仍驱动缩放，但不驱动折射；
6. 真机门槛自查：API 31–32 机型上完整版交互面应退磨砂而非崩、API ≤ 30 应全纯色。

## 8. 实施记录（3.1.0 之后）

§3–§6 已落地（`GlassTier` / `glassTier()` / `glassSurfaceTint(dark, tier)`）：

| 表面 | 磨砂版表现 |
|---|---|
| 顶栏 | vibrancy + blur(24dp) + frost 面色，无投影 |
| 底栏面板 | vibrancy + blur(8dp) + frost 面色，无折射，按压保留鼓起 |
| 底栏滑块 | 实底色块（不变） |
| 弹窗 | vibrancy + blur(24dp) + frost 面色，无 rim 无投影 |
| 开关滑块 | blur 采样轨道色 + 原色面色恒定，按压退 Miuix 档 1.127 |

frost 面色 0.45 为起始值，真机校准后回填本行。
