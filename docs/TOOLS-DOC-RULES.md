# TOOLS-DOC-RULES —— tools/ 文档规则

> 本规则源自 2026-10-17 的 tools 全量审查（4 路并行：shell 组 / Python 组 / ci+probe+publish 组 / 交叉引用核对）。
> 审查发现的典型病：README 漏写源码已有的子命令与参数、公共库不列条目、同一逻辑多处拷贝后各改各的。
> 这份规则就是针对它们立的规矩。**tools/README.md 是唯一入口文档**，本文件只管「怎么写、怎么保持一致」。

## 1. 条目完整性：目录里每个脚本都必须有条目

- `tools/` 下的**每个** `.sh` / `.py` 都必须在 `tools/README.md` 有一节，**内部库也不例外**
  （`_color.py`、`_png.py` 这类被 import 的公共模块，注明「非独立工具，被谁引用」即可，不单列用法）。
- 被别的脚本内部调用的脚本（`uidump.py`、`glog_tail.py`、`probe/pack_dex.py`）要在调用方一节里点明
  「由 X 内部调用，也可单独用」，避免读者以为它是孤儿文件。
- 新增脚本的同一次提交里必须包含 README 条目。没有条目的脚本视为未交付。

## 2. 条目格式（统一五要素）

每个脚本一节，按需包含，顺序固定：

1. **一句话功能**（加粗，放标题后第一行）—— 说清「干什么用、什么时候用」，不写实现细节；
2. **用法代码块** —— 覆盖全部子命令 / 参数组合，源码支持的每个子命令、每个参数、每个别名都必须出现；
3. **环境变量表或行内说明** —— 脚本读取的每一个环境变量（含默认值）；
4. **依赖与被依赖** —— source 了谁、调用了谁、被谁调用；ffmpeg / Pillow 这类外部依赖必须点名；
5. **坑（blockquote）** —— 已知的行为限制、平台限制、危险操作，用 `>` 引用块写在节尾。

实现原理、算法细节**不进 README**，放脚本 docstring 或 docs/ 对应文档（如 `docs/UI.md`、`docs/BUILD.md`）。
README 只回答「怎么用」，链接出去回答「为什么」。

## 3. README 与源码同步义务

- **改了对外接口就要改 README，同一次提交**：新增/删除子命令、参数、别名、环境变量、输出格式，全部算对外接口。
- 审查口径（大扫除时核对）：拿源码的 argv 解析 / 环境变量读取逐条对 README，
  「源码有、README 无」和「README 有、源码无」都算不一致。
- 历史教训（本次审查实测）：`glog.sh all`、`ui.sh find/dump`、`verify.py --tol`、
  `ci/version.py --shell`、`publish.py --readme-only` 都是先有源码后有文档，漏了数月。

## 4. 分类归属

README 按七大类编排（见分类总览表），新脚本必须归入其中一类；
确实开新类时同步更新总览表。分类按**使用场景**不按语言：

| 分类 | 判据 |
|---|---|
| 环境与设备连接 | 找设备、连设备、管环境 |
| 设备调试与日志 | 往设备里看、往设备里敲 |
| 真机功能验证 | 对设备上的功能做自动化判定 |
| 品牌视觉与图标 | 图标 / logo / social preview 的生成与校验 |
| 发布与 CI | 发版、产物红线、版本号 |
| 运行时探针 | probe/ 目录，目标 App 升级后的侦察 |
| 代码卫生 | hygiene.py，只读扫描 |

## 5. 公共代码与单一来源

- 同一段逻辑出现在 **2 处及以上**就抽 `tools/_xxx.py` 公共模块（先例：`_color.py` 取色、`_png.py` PNG 编码）。
  抽取后原脚本的输出必须**逐字节一致**（先留基线产物再改，`cmp` 验证）。
- **设计常量唯一来源是 `gen_launcher_icons.py` 顶部常量块**：改图标/配色只改那里，
  `make_social_preview.py`、drawable XML、`colors.xml` 注释跟着同步。
- **版本号唯一来源是 `app/build.gradle.kts`，读取逻辑唯一来源是 `ci/version.py`**：
  其他脚本（含 publish.py）要读版本号就复用它，不各写一套正则。
- 白底阈值等魔法数字优先用公共库常量（`_color.WHITE`），不在调用方硬编码。

## 6. 红线（只读，整理时不动）

`tools/publish.py`、`tools/probe/probe.keystore`、`.github/workflows/` 下引用的路径
（`ci/setup-sdk37.sh`、`ci/version.py`、`ci/verify_apk.py`、`publish.py`）——
**移动或改名需同步改 workflow**，不做没有 CI 验证保证的路径整理。

## 7. 临时产物

脚本落盘一律进 `.work/`（`.gitignore` 已排除）。三个例外不许清：
`.work/lsposed-repo/`、`.work/log.mark`、`.work/wifi_serial`。
新增落盘路径要同步进 README「调试产物位置」一节。

## 8. 环境变量命名

- CI / 发布域专用变量加 `GS_` 前缀（`GS_SDK_PLATFORM_PKG`、`GS_EXPECT_CERT_SHA256`、`GS_GITHUB_TOKEN`…）。
- 所有脚本共用的探测变量（SDK / adb / python / ffmpeg / 两个包名）进 README 顶部总表；
  单脚本专用的写在自己那一节，两边都不许漏。
