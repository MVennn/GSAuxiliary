# 自选分组复制 · 规则、格式与导出

> 一句话：自选页的复制按钮不再固定复制「名称 代码」——用户可以建多条**复制规则**，
> 自选字段与顺序、选纯文本 / Markdown / CSV / JSON / 模板，短按按默认规则执行，
> 长按弹菜单复制、分享文本、导出图片或 PDF。

> 第一阶段（编辑按钮换复制、固定「名称 代码」）见 [FEATURE-ZX-COPY.md](FEATURE-ZX-COPY.md)。
> 本文是 2026-10-23 扩展后的正本；控件名、字段名均来自真机探针与实测。

---

## 0. 结论先行

| 问题 | 答案 |
|---|---|
| 规则怎么存 | remote preferences 的 `zx_copy_rules`，JSON 字符串；结构见 [ZxCopyRuleStore](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/zxcopy/ZxCopyRuleStore.kt) |
| 一份规则能配什么 | 字段与顺序、格式、分隔符、表头/页脚、分组/时间/数量、排序、空值、默认动作、图片/PDF 样式、导出后是否分享 |
| 默认规则 | `名称 代码`、纯文本、空格分隔、屏幕顺序、复制到剪贴板；与第一阶段行为一致 |
| 输出格式 | 纯文本 / Markdown / CSV / JSON / 自定义模板 |
| 图片 | 简洁列表 / 表格 / 卡片三套预设；深色、红涨绿跌、每 30 行分页多图 |
| PDF | 默认 A4 竖版表格；可选横版、单页长图；页码 / 页眉页脚 / 斑马纹 |
| 交互 | **短按**执行默认规则的默认动作；**长按**弹动作菜单（开关 `zx_copy_menu` 控制） |
| 设置入口 | 功能卡片展开两个二级入口：「复制设置」与「复制按钮设置」（后者只放长按菜单开关） |
| 落盘 | Android 10+ MediaStore（图片进相册、PDF 进下载）；Android 8/9 退到 App 目录 |

---

## 1. 数据模型

### 1.1 规则 JSON（v1）

```json
{
  "v": 1,
  "defaultId": "r1",
  "rules": [
    {
      "id": "r1",
      "name": "默认",
      "preview": "名称 代码 · 纯文本 · 复制到剪贴板",
      "isDefault": true,
      "fields": ["name", "code"],
      "labels": { "name": "名称", "code": "代码" },
      "format": "text",
      "separator": " ",
      "rowSeparator": "\n",
      "template": "",
      "header": false,
      "headerText": "",
      "footer": false,
      "footerText": "",
      "includeGroup": false,
      "includeTime": false,
      "includeCount": false,
      "sort": "screen",
      "empty": "dash",
      "action": "clipboard",
      "image": {
        "preset": "table", "dark": false,
        "showHeader": true, "showGroup": true, "showTime": true,
        "showCount": true, "showFooter": true, "footerText": "",
        "fontSize": 14, "rowsPerPage": 30, "colorTrend": true
      },
      "pdf": {
        "landscape": false, "longPage": false, "fontSize": 10, "margin": 36,
        "showHeader": true, "showFooter": true, "footerText": "",
        "pageNumbers": true, "zebra": true
      },
      "shareAfterExport": true
    }
  ]
}
```

- **`labels` 是写入时按当前语言生成的字段标签**：目标进程没有模块资源上下文，
  Markdown / CSV 表头与图片 / PDF 列头只能读这份文本。设置页每次写规则都会重填，
  所以切换语言后重新保存一次即可刷新。
- **`preview`** 是规则列表的一行预览；列表页也会按当前语言实时生成，不依赖旧值。
- 解析容错：坏 JSON 回落出厂规则；未知字段丢弃、不连坐整条规则；重复 id 只留第一条；
  `defaultId` 失效回落第一条；未知格式 / 排序 / 动作回落安全默认。
- **`footerText` 默认空**（早期版本的「数据仅供参考」不再自动写入；用户想写再写）。

### 1.2 字段目录

`core/zxcopy/ZxCopyFieldCatalog.kt` 是唯一权威；`core/zxcopy/ZxCopyHostFields.kt`
存宿主字段候选名（供契约校验扫描）。字段顺序 = 设置页 `zx_copy_field_names` 数组顺序：

| 分组 | 字段（id） | 宿主候选 |
|---|---|---|
| 基础 | 名称 `name` / 代码 `code` / 市场 `market` | `stkName`、`stockName`、`m_stkCode`、`stockCode`、`codeAndMarket`、`m_stkMarket` |
| 行情 | 最新价 `price` / 涨跌 `change` / 涨幅 `changePct` / 涨速 `speed` / 振幅 `amplitude` / 换手率 `turnover` / 量比 `volumeRatio` / 成交额 `amount` / 成交量 `volume` | `stockPrice`、`mPrice`、`zxj`、`price`、`zd`、`zdf`、`zs`、`zf`、`hsl`、`lb`、`cjje`、`cjsl` |
| 市值与估值 | 流通市值 `floatCap` / 总市值 `totalCap` / 市盈率 `pe` / 市净率 `pb` / 股息率 `dividend` | `money_ltsz`、`money_zsz`、`syl`、`peTTM`、`peStatic`、`sjl`、`mGxl` |
| 区间 | 今开 `open` / 昨收 `prevClose` / 最高 `high` / 最低 `low` / 均价 `avgPrice` / 3日 `zdf3` / 5日 `zdf5` / 20日 `zdf20` / 60日 `zdf60` / 年初至今 `zdfYear` | `jrkp`、`zrsp`、`highPrice`、`lowPrice`、`mAverage`、`zdf_3_day`、`zdf_5_day`、`zdf_20_day`、`zdf_60_day`、`zdf_year` |
| 其他 | 行业 `industry` / 标签 `label` / 加入时间 `addTime` / 行情时间 `priceDate` / 主力净流入 `mainFund` / 主力占比 `mainFundPct` / 现量 `nowVol` / 委比 `weiBi` | `mBelongHyName`、`m_stkLable`、`addTime`、`priceDate`、`moneyInout`、`moneyInoutZl`、`nowVol`、`wb` |

**格式化**：

- 代码只取 6 位数字；市场 1→深、2→沪、5→北；
- 价格 / 涨跌 2 位小数；正数加 `+`；
- 涨幅 / 涨速 / 振幅 / 换手率等 2 位小数 + `%`；带符号项正数加 `+`；
- 市值 / 成交额 / 主力净流入：已经是「亿 / 万」显示串就原样用，纯数字才换算；
- 空值：显示 `-`（默认）或留空（`blank`）；
- ⚠️ `zdf` / `zd` / `zs` 等原始量纲尚未做最终实测；若发现是 `0.1239` 这种比例，
  只改 [ZxCopyFieldCatalog.Spec.percentScale](../app/src/main/java/io/github/MVennn/GSAuxiliary/core/zxcopy/ZxCopyFieldCatalog.kt)
  一处即可。

---

## 2. 输出格式

### 2.1 纯文本（`text`）

字段用 `separator` 连接（设置页是选择器：空格 / 制表符 / 逗号 / 竖线 / 无），
行用换行；可选表头文字、页脚、分组名 / 时间 / 数量。

```
金山办公 688111 +5.37% 1158.13亿
爱尔眼科 300015 +2.26% 647.06亿
```

### 2.2 Markdown（`markdown`）

```markdown
| 名称 | 代码 | 涨幅 | 流通市值 |
| --- | --- | --- | --- |
| 金山办公 | 688111 | +5.37% | 1158.13亿 |
```

- 名称里的 `|` 转义成 `\|`；表头用 `labels` 里的当前语言标签。

### 2.3 CSV（`csv`）

- 带 UTF-8 BOM；可选表头（字段标签）；含逗号 / 引号 / 换行的值自动加引号。
- 不写分组名 / 时间 / 数量 / 页脚，保证机器可解析。

### 2.4 JSON（`json`）

```json
[
  {"name":"金山办公","code":"688111","changePct":"+5.37%"}
]
```

- 键用字段 id，值用格式化后的字符串。

### 2.5 自定义模板（`template`）

一行一只，占位符 `{name}` `{code}` `{price}` `{changePct}` 等；另支持
`{index}` / `{group}` / `{time}` / `{count}`；`{{` / `}}` 转义。
模板为空时回落纯文本。

### 2.6 排序

屏幕顺序（默认）/ 代码 / 名称 / 涨幅降序 / 涨幅升序；排序用原始数值，不用格式化字符串。

---

## 3. 图片导出

### 3.1 三套预设

| 预设 | 样子 |
|---|---|
| `simple` 简洁列表 | 白/深底 + 行间细分隔，无表格线 |
| `table` 表格 | 列头 + 斑马纹 + 竖分隔线（默认） |
| `card` 卡片 | 外圈圆角卡片 + 内边距，适合发微信 |

### 3.2 样式

- 深色 / 浅色；
- 红涨绿跌（A 股习惯）；
- 字号（10~28sp）、每张图片行数（20/30/50/100，且受 4000px 高度上限约束）；
- 页眉（分组名 / 数量 / 时间）、页脚（共享 `footerText`）、页码 `1/7`；
- 列宽按内容测量 + 权重分配，长名称省略号截断；数值右对齐。

### 3.3 分页与落盘

- 206 只 × 30 行/张 = 7 张；逐页渲染、存一张、回收一张（避免 100MB+ 位图常驻）。
- Android 10+：`MediaStore.Images` → `Pictures/GSAuxiliary/自选分组_<分组>_<时间>_N.png`；
- Android 8/9：`getExternalFilesDir(Pictures)/GSAuxiliary`，分享降级为提示路径；
- 多张图片走 `ACTION_SEND_MULTIPLE`，单张走 `ACTION_SEND`，带 `FLAG_GRANT_READ_URI_PERMISSION`。

---

## 4. PDF 导出

- 默认 **A4 竖版表格**（595×842pt）；可选横版（842×595）；
- 可选 **单页长图**（整份内容一页，高度上限 14400pt；超了自动退回分页）；
- 页眉：标题 + 分组名 / 数量 / 时间；页脚：`footerText` + 页码 `1/3`；
- 字号 8/10/12/14pt；页边距 24/36/48/60pt；斑马纹可关；
- 中文用系统 `sans-serif`，`PdfDocument` 矢量输出；
- Android 10+：`MediaStore.Downloads` → `Downloads/GSAuxiliary/....pdf`；
- Android 8/9：App 外部目录，分享降级为提示路径。

---

## 5. 交互

### 5.1 短按

执行**默认规则**的 `action`：

- `clipboard`（默认）：复制文本；
- `shareText`：直接拉起系统分享文本；
- `image`：导出图片（按 `shareAfterExport` 决定是否接着分享）；
- `pdf`：导出 PDF。

### 5.2 长按（`zx_copy_menu` 开关，默认开）

弹宿主进程内的 PopupWindow：

```
复制（默认）
复制为「规则2」
…
────────
分享文本
导出图片
导出 PDF
```

- 关掉开关：不挂长按监听，短按仍按默认规则执行；
- 菜单开关在独立的二级页「复制按钮设置」里，属于功能子参数
  （`FEATURE_PARAM_KEYS`，默认开）。

### 5.3 快捷键说明

短按走默认规则，长按菜单里的「复制为某规则」用于临时换规则；想把某条规则变成
短按默认，去规则列表把它设为默认。

---

## 6. 设置界面

### 6.1 功能列表

- 功能卡片里是「自选分组复制」开关与说明；
- 开关打开后，卡片内展开**两个**二级入口：
  - **复制设置**：字段 / 格式 / 排序 / 导出样式的规则列表；
  - **复制按钮设置**：只放长按动作菜单开关（全局）；
- 两个入口都在功能卡内（标准展开方式），不单独拆卡。

### 6.2 规则列表页

- 规则列表：规则名 + 实时预览（字段 · 格式 · 动作）+ 默认徽标 + ⋮ 菜单
  （设为默认 / 上移 / 下移 / 删除）；
- 「新建规则」：复制当前默认规则，换 id / 名称 / 默认标记；
- 每改一下立即写 `zx_copy_rules`，不设保存按钮；
- **长按动作菜单开关不在这一页**，在「复制按钮设置」页。

### 6.3 规则编辑页

| 分组 | 内容 |
|---|---|
| 名称 | 规则名 |
| 按钮动作（第一排） | 点击按钮时执行什么 |
| 复制字段 | 已选字段（有序，⋮ 上移 / 下移 / 移除）+ 添加字段（按分组前缀列出未选项） |
| 排序与空值 | 排序、空值策略 |
| 文本格式 | 格式、列分隔符（选择器）、模板（仅模板格式）、表头/页脚/分组/时间/数量 |
| 页脚 | 仅在任一页脚开关打开时出现；字段上方不再放小标签，字段下方一行「页脚文字 · 显示在图片 / PDF 底部」 |
| 图片样式 | 预设、深色、页眉各项、行数、红涨绿跌、预览图片 |
| PDF 样式 | 横版、单页长图、字号、页边距、页眉页脚、页码、斑马纹 |
| 规则与导出 | 导出后立即分享、设为默认、删除规则（至少保留一条） |

- **最下面的文本预览已移除**（用户要求）；图片预览保留在图片样式卡里，用 App 的贴底弹窗（与「重置模块」同一个壳），弹层材质跟随主题 / 液态玻璃，不再按图片深浅给整窗铺底色。
- 预览图本身 16dp 圆角（**只裁预览**，导出到相册 / PDF 的图仍是直角）；关闭按钮沿用重置弹窗的 `DialogButton` 样式。
- 列分隔符用选择器（空格 / 制表符 / 逗号 / 竖线 / 无），不再是一个看起来空白的输入框。
- 字段行按字段 id 记身份；**添加 / 移除字段都有展开 / 收起动画**，与规则列表的新建 / 删除一致。
- 行尾 `⋮`、`↓`、`›` 统一放进 40dp 槽位：`⋮` 的中心与下面各行的箭头在同一条竖线上。
- 页脚文字默认空，不再自动写「数据仅供参考」；旧配置里的值保留，用户可自行清空。
- 三级页路由已从「只认方案编辑」扩展为「按 `editParent(route)` 找父页」，
  方案编辑与复制规则编辑共用同一套二级 / 三级滑动与可预测返回。

---

## 7. 代码位置

| 文件 | 职责 |
|---|---|
| `core/zxcopy/ZxCopyRuleStore.kt` | 规则模型 + JSON 编解码 + 容错 + 默认规则 |
| `core/zxcopy/ZxCopyFieldCatalog.kt` | 字段目录 + 格式化 / 排序值 |
| `core/zxcopy/ZxCopyHostFields.kt` | 宿主字段候选名（契约校验扫描） |
| `core/zxcopy/ZxCopyFormat.kt` | text / Markdown / CSV / JSON / 模板 + 排序 + 预览样例 |
| `core/zxcopy/ZxCopyRow.kt` | 已格式化的一行（字段值 + 排序值） |
| `core/zxcopy/ZxCopyImageRenderer.kt` | 图片分页 / 绘制 / 三预设 |
| `core/zxcopy/ZxCopyPdfRenderer.kt` | PDF 分页 / 绘制 / 长图 |
| `feature/zxcopy/ZxGroupCopyFeature.kt` | 按钮替换、短按/长按、动作分发 |
| `feature/zxcopy/ZxGroupStocks.kt` | 读当前分组适配器全部字段 → `ZxCopyRow` |
| `feature/zxcopy/ZxCopyMenu.kt` | 宿主进程长按 PopupWindow |
| `feature/zxcopy/ZxCopyExportStorage.kt` | 图片 / PDF 落盘与 Android 8/9 降级 |
| `feature/zxcopy/ZxCopyShare.kt` | 文本 / 图片 / PDF 系统分享 |
| `ui/ZxCopyRuleScreen.kt` | 规则列表 |
| `ui/ZxCopyRuleEditScreen.kt` | 规则编辑页 |
| `ui/ZxCopyButtonScreen.kt` | 复制按钮设置页（长按菜单开关） |
| `ui/ZxCopyUi.kt` | 两页共用的标签 / 预览 / 写入工具 |

配置：`zx_copy_rules`（字符串，`FEATURE_PARAM_KEYS` / `SNAPSHOT_KEYS`）、
`zx_copy_menu`（布尔，`FEATURE_PARAM_KEYS` / `SNAPSHOT_KEYS`）；`ConfigSnapshot`
布尔位 26 个（剩 6 位），字符串规则 JSON 混入指纹。

---

## 8. 验收清单

1. 默认规则行为与第一阶段一致：`名称 代码` 一行一只、屏幕顺序、剪贴板。
2. 字段勾选 / 排序 / 空值策略生效；Markdown / CSV / JSON / 模板输出符合 §2。
3. 图片三预设、深色、红涨绿跌、分页、页眉页脚、中文正常。
4. PDF A4 竖版 / 横版 / 单页长图、页码、中文、表格不串列。
5. 分享：文本 / 多图 / PDF 都能拉起系统面板；取消不报错。
6. Android 8/9：保存到 App 目录并提示路径，不崩溃。
7. 长按菜单开关：关掉后长按不再弹菜单，短按仍复制。
8. 关功能开关：复制图标还原；规则数据保留；重开恢复。
9. 日志不出现完整「代码.市场」。
10. `assembleRelease` + `verify_apk` + 单测 + 五套静态检查全过。

---

## 9. App 升级后怎么查

| 现象 | 先改哪里 |
|---|---|
| 字段读不到 / 全空 | `ZxCopyHostFields` 的候选数组；用探针重新 dump `StockInfo` |
| 涨幅数字差 100 倍 | `ZxCopyFieldCatalog.Spec.percentScale` |
| Markdown / CSV 表头不对 | 规则 `labels`（设置页重新保存一次会按当前语言重填） |
| 图片列挤 / 断行 | `ZxCopyImageRenderer` 的列宽权重 / 字号 / 行数 |
| PDF 分页不对 | `ZxCopyPdfRenderer.rowsPerPage` / 页边距 / 字号 |
| 分享打不开 | `ZxCopyExportStorage` 的 MediaStore URI / `FLAG_GRANT_READ_URI_PERMISSION` |
| 长按没菜单 | `zx_copy_menu` 开关；`ZxCopyMenu` 的 PopupWindow 是否被宿主顶掉 |
