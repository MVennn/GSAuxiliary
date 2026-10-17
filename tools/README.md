# tools —— 开发期工具

这些脚本**不参与 APK 打包**，只用于开发调试、自动化验证、以及 App 升级后重新定位。

所有脚本都做了**路径与环境自动探测**，不需要把本机绝对路径写死。
需要时可以用环境变量覆盖（各脚本还有自己的专用变量，见对应小节）：

| 变量 | 说明 |
|---|---|
| `ANDROID_SDK` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` | Android SDK 根目录 |
| `ADB` | adb 可执行文件全路径 |
| `PYTHON` | python 可执行文件全路径 |
| `JAVA_HOME` | JDK 路径（probe 构建用） |
| `FFMPEG` | ffmpeg 可执行文件全路径（取色用） |
| `TARGET_PKG` | 目标 App 包名，默认 `com.guosen.android` |
| `MODULE_PKG` | 模块包名，默认 `io.github.MVennn.GSAuxiliary` |
| `ADB_WIFI_TARGET` | 无线调试地址，如 `192.168.1.10:5555` |
| `ADB_WIFI_HOST` | 无线调试主机 IP（dev.sh / adbw.sh，默认 `10.0.0.26`） |

## 分类总览

| 分类 | 脚本 |
|---|---|
| 环境与设备连接 | env.sh · adbw.sh · dev.sh · stayon.sh |
| 设备调试与日志 | sh.sh · ui.sh + uidump.py · drive.sh · glog.sh + glog_tail.py |
| 真机功能验证 | verify.py · sample_colors.py + _color.py · enable_lsposed.py |
| 品牌视觉与图标 | gen_launcher_icons.py · verify_icons.py · make_social_preview.py（+ 公共库 _png.py） |
| 发布与 CI | publish.py · ci/ |
| 运行时探针 | probe/ |
| 代码卫生 | hygiene.py |

---

# 环境与设备连接

## env.sh

公共环境探测，被其它脚本 `source`。也可单独用来排查环境：

```bash
bash -c '. tools/env.sh; echo "adb=$ADB"; echo "python=$PYTHON"; echo "ffmpeg=$FFMPEG"'
```

导出变量：`TOOLS_DIR` `REPO_DIR` `SDK` `ADB` `PYTHON` `FFMPEG` `JAVA_HOME` `TARGET_PKG` `MODULE_PKG`；
公共函数：`winpath()`、`detect_sdk()`、`wait_device [timeout]`（stdout 输出 serial，
并缓存到 `.work/wifi_serial`）。

## adbw.sh

**带自动重连的 adb 包装**。长脚本里裸调 `adb shell` 迟早挂在半路，把重试交给它。
`ui.sh` 与 `glog.sh` 的所有设备操作都走它。

```bash
bash tools/adbw.sh shell "input tap 540 1020"
bash tools/adbw.sh exec-out screencap -p > shot.png
bash tools/adbw.sh pull /sdcard/ui.xml F:/Project/GoldSunEnhance/.work/ui.xml
bash tools/adbw.sh dev            # 只打印当前可用 serial
```

环境变量：`ADB_WIFI_HOST`（默认 `10.0.0.26`）、`ADBW_TRIES`（重试次数，默认 6）。

> 脚本内部已 `export MSYS_NO_PATHCONV=1` —— 否则 Git Bash 会把远端路径
> `/sdcard/ui.xml` 改写成 `F:/Program Files/.../sdcard/ui.xml`，`pull` 直接报
> `failed to stat remote object`。**本地路径要自己用 `winpath` 转成 `F:/...`。**
> 需要 root 时整条命令包一层 `su -c`，注意别在命令里再用双引号。
> 只认无线 serial（`ip:port` 形式），USB 设备请走 `sh.sh` / `drive.sh`。

## dev.sh

**无线调试发现 / 连接**，返回可用的 `ip:port` serial：

```bash
DEV="$(bash tools/dev.sh)" || exit 1
adb -s "$DEV" shell ...
```

环境变量：`ADB_WIFI_HOST`（默认 `10.0.0.26`）、`ADB_MDNS_PORT`（已知直连端口时跳过 mDNS 发现）。

> 功能与 `adbw.sh dev` 重合（后者还多一层重试）。新脚本建议直接用 `adbw.sh dev`；
> 本脚本保留是因为历史文档示例（如 `docs/FEATURE-SELECT-ALL.md`）引用了它。

## stayon.sh

**调试期息屏超时**。无线调试时手机动不动就息屏 —— 点不动、截图全黑、
`dump` 拿回来的还是上一帧。把超时拉长，收工再还原：

```bash
bash tools/stayon.sh            # 息屏超时 → 10 分钟（首次会把原值备份下来）
bash tools/stayon.sh off        # 还原（优先用备份里的原值，没备份则 1 分钟）
bash tools/stayon.sh status     # 只看当前值，什么都不动
```

`on` 的别名是 `start`，`off` 的别名是 `stop` / `restore`。
环境变量 `SCREEN_TIMEOUT_ON` / `SCREEN_TIMEOUT_OFF`（毫秒）可改这两个数值。

原值存在 `.work/screen_timeout.bak`。**重复执行 `on` 不会覆盖备份**，
所以调试中途再跑一次也不会把「10 分钟」错当成原值。备份丢了的话，`off` 一律还原成 1 分钟。

> 只改 `settings system screen_off_timeout`，不碰 `svc power stayon` ——
> 后者只在充电时生效，而且它把「插电就永不熄屏」写进全局状态，收工容易忘了关。

---

# 设备调试与日志

## sh.sh

在设备上执行 shell 命令（自动选设备，不重启 adb server；支持 USB 设备）。

```bash
bash tools/sh.sh "ls -la /sdcard"                     # 普通命令
bash tools/sh.sh --root "ls /data/adb/lspd/log"       # root 命令
bash tools/sh.sh --pkg                                 # dump 模块包信息
```

> `--pkg` **不带参数**，固定执行 `dumpsys package $MODULE_PKG`。
> `--root` 的实现是 `su -c '<cmd>'`，所以命令里**不能出现单引号**，
> 否则会截断外层引号。需要引号时请拆成多条执行，管道放到宿主机侧。

## ui.sh + uidump.py

**目标 App 的 UI 自动化**：dump 控件树 / 列元素 / 按文字定位并点击。

```bash
bash tools/ui.sh list            # 列出元素（坐标 / 可点击 / 文字）
bash tools/ui.sh find "我的策略"  # 只打印定位到的坐标，不点击
bash tools/ui.sh tap "我的策略"   # dump + 定位（优先可点击祖先）+ 点击
bash tools/ui.sh tapxy 540 1020  # 直接点坐标
bash tools/ui.sh dump            # 只重新 dump 控件树
bash tools/ui.sh act             # 打印栈顶 Activity
```

`uidump.py` 是解析器（也可单独用）：

```bash
python tools/uidump.py ui.xml --list
python tools/uidump.py ui.xml --find "标准行情"
python tools/uidump.py ui.xml --find-class EditText
```

`drive.sh` 的 `uifind` / `uiscan` 指令也走它（`--find "文本"` / `--list`），
不再另有一份解析器。

**两个必须记住的点**：

1. **dump 与 tap 必须在同一次调用里** —— 分两次执行时界面可能已经滚动，坐标就漂了。
2. 截图预览被缩到约 486 宽，**实际坐标 = 预览坐标 × 2.222**（1080×2400 屏）。

## drive.sh

批量驱动设备 UI。把一串指令一次性执行，适合复现固定操作路径。
支持 USB 设备（走 `wait_device`，与 ui.sh 的 adbw 路线不同）。

```bash
bash tools/drive.sh "stop" "wait 2" "launch" "wait 14" \
                    "tap 324 471" "wait 6" "tap 890 340" "wait 7" \
                    "tap 78 326" "wait 8" "act"
```

| 指令 | 说明 |
|---|---|
| `tap X Y` | 点击 |
| `swipe X1 Y1 X2 Y2 [MS]` | 滑动 |
| `wait SEC` | 等待 |
| `shot NAME` | 截图到 `.work/shots/NAME.png` |
| `back` / `home` | 返回键 / 主页键 |
| `key CODE` | 发送按键（`224` = 唤醒屏幕） |
| `text STR` | 输入文本 |
| `act` | 打印当前 Activity |
| `stop` / `launch` | 强制停止 / 启动目标 App |
| `install [PATH]` | 安装 APK（默认取构建产物） |
| `logclear` | 清空 logcat |
| `uifind TEXT` | 按文字找控件并点击 |
| `uiscan` | 列出当前界面所有文字 + 坐标 |

> 坐标基于 1080×2400 屏幕。换机型请先用 `uiscan` 重新取坐标。
> 设备息屏时脚本会点不动，开头加 `"key 224" "wait 2" "swipe 540 2000 540 900 250" "wait 3"`。
> `text` / `uifind` 的参数**不能带空格**（`set -- $cmd` 故意分词，带空格文本会被拆碎）。

## glog.sh + glog_tail.py

**读 LSPosed 模块日志的增量**。

模块日志**不在 logcat**，在设备的 `/data/adb/lspd/log/modules_*.log`，动辄几万行。
本脚本按行号游标取增量（传输仍是全量拉取，增量过滤在本地 Python 端做），并剥掉 LSPosed 前缀。

```bash
bash tools/glog.sh mark                        # 记录当前行数
# …在设备上操作…
bash tools/glog.sh tail                        # 打印新增行（默认只留探针的行）
bash tools/glog.sh tail --module GSAuxiliary   # 只看主模块
bash tools/glog.sh tail --module all           # 不过滤
bash tools/glog.sh all                         # 打印整个日志（从头读，不看游标）
bash tools/glog.sh rlog                        # 只看日志文件路径
```

> ⚠️ `/data/adb/lspd/config/modules_config.db` **是陈旧快照，别拿它判断模块有没有生效**。
> 唯一可信的判据是这份日志。
> 游标与日志文件无绑定：模块日志轮转（换新 `modules_*.log`）后要重新 `mark`。

---

# 真机功能验证

## verify.py ★

**自动校验策略详情页的高亮是否正确** —— 这是本项目最主要的回归测试手段。

原理：解析控件树找到每只股票的名称节点 → 截图 → 用 ffmpeg 解码成 RGB24
→ 在名称边界框里采样像素 → 判断主色是否是期望的高亮色。

```bash
python tools/verify.py                  # 默认校验琥珀黄 #FF8000
python tools/verify.py --color 3482FF   # 校验其它高亮色
python tools/verify.py --tol 40         # 放宽颜色容差（默认 24）
```

环境变量 `SERIAL` 可在多设备时指定序列号。需要 `ffmpeg`（`FFMPEG` 环境变量或 PATH 中）。

输出示例：

```
[dev] adb-xxxx  1080x2400
  名称             代码       主色         判定
  ----------------------------------------------------
  莱美药业           300006   #F88000  高亮
  久之洋            300516   #F88000  高亮
  ...
  七一二            603712   #202030  普通
  ----------------------------------------------------
  高亮 9 只 / 普通 1 只
```

## sample_colors.py + _color.py

从截图里采样若干矩形区域的主色，排查"颜色到底对不对"。
**不给区域参数时默认整图取色。**

```bash
python tools/sample_colors.py .work/shots/s01.png 100,200,400,260
python tools/sample_colors.py shot.png --size 1080x2400 0,0,1080,2400
```

`_color.py` 是两者的公共取色库（`find_ffmpeg` / `decode_rgb` / `WHITE` 白底阈值），
不是独立工具。

## enable_lsposed.py

**直接改写 LSPosed 的配置库**，把模块登记为启用并加入作用域 ——
省去手工在 LSPosed 界面点来点去。

```bash
python tools/enable_lsposed.py <modules_config.db> <module_pkg> <apk_path> <target_pkg> [user_id]
```

典型用法（需要 root）：

```bash
adb shell su -c "cp /data/adb/lspd/config/modules_config.db /sdcard/mc.db"
adb pull /sdcard/mc.db
python tools/enable_lsposed.py mc.db io.github.MVennn.GSAuxiliary /data/app/.../base.apk com.guosen.android 0
adb push mc.db /sdcard/mc.db
adb shell su -c "cp /sdcard/mc.db /data/adb/lspd/config/modules_config.db"
```

会改三张表：`modules`（登记包名与 APK 路径）、`modules_state`（置 enabled=1）、
`scope`（把目标 App 加入作用域）。

> 改完需要重启目标 App（或重启设备）才生效。

---

# 品牌视觉与图标

> 这一组的公共底座是 `_png.py`（零依赖 PNG 编码，`write_png_rgba` / `write_png_gray`）。
> 图标几何/配色常量的唯一权威来源是 `gen_launcher_icons.py` 顶部的设计常量块。

## gen_launcher_icons.py ★

生成 launcher 图标的**位图回退资源**（`app/src/main/res/mipmap-{mdpi..xxxhdpi}/`），
以及仓库用的**大尺寸图标**。

```bash
python tools/gen_launcher_icons.py
```

产出四类文件：

| 产物 | 尺寸 | 用途 |
|---|---|---|
| `res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png` | 48 / 72 / 96 / 144 / 192 | launcher 位图回退 |
| `res/mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png` | 同上 | 圆形变体（多套一层圆形遮罩） |
| `assets/logo.png` | **512** | README 顶部的仓库 logo |
| `assets/icon.svg` | 矢量 | 与位图同源；提交到 LSPosed 模块仓库当模块图标用 |

自适应图标（`mipmap-anydpi-v26`）只在 API 26+ 生效，低版本和部分第三方桌面
仍会读 `mipmap-*dpi` 的 PNG，所以这些位图必须存在。

图案是「圆角方形 + 竖向渐变底 + 一道上扬的弧线 + 顶端实心箭镞」（简约苹果风格），
**必须与下面两个矢量文件逐项一致**：

- `app/src/main/res/drawable/ic_launcher_background.xml`（渐变底）
- `app/src/main/res/drawable/ic_launcher_foreground.xml`（弧线 + 箭镞）

脚本顶部的 `BG_TOP` / `BG_BOTTOM` / `LINE_TOP` / `LINE_BOTTOM` /
`CURVE_START` / `CURVE_CTRL` / `CURVE_END` / `CURVE_STROKE` / `TRI` /
`TRI_STROKE` / `CORNER` 就是这份对照表，**改了一边就要改另一边**。
`assets/icon.svg` 由 `svg_text()` 用同一批常量生成，
`make_social_preview.py` 直接 import 它取几何与像素（连 tile 都是 `render()` 出的），
所以四种产物不会各走各的。关于页那颗 mark（`ui/AboutHero.kt`）的挖空也是同一套坐标。

实现要点：

- **零第三方依赖**（`math` / `struct` / `zlib`），PNG 自己编码（走 `_png.py`），不依赖 Pillow
- 前景用**距离场**上色：弧线按二次贝塞尔采样成密折线，逐像素求到各段的最短距离，
  `距离 <= 线宽/2` 即上色；箭镞另算「点在三角形内 || 到三边距离 <= 描边/2」。
  圆头笔帽与圆角拐点由距离场天然产生，不需要单独处理 line cap / line join
- 背景逐行算圆角矩形的 x 区间再填竖向渐变
- 4× 超采样降采样做抗锯齿；`ic_launcher_round.png` 额外套一层圆形遮罩

图形坐标从设计源图（240 网格）换算到 108 网格时乘了 0.395，把外接圆收到 34.2 ——
自适应图标圆形遮罩的半径是 36，留 1.8 的余量。改几何时这个约束别忘。

设计说明见 `docs/UI-HISTORY.md` §12。

## verify_icons.py ★

校验自绘图标（`ui/AppIcons.kt`）的 path 数据。

界面图标是手写的 SVG path（背景见 `docs/UI.md` §3.3）。path 写错一个数字照样能编译通过，
只有装到机器上才看得出来。这个脚本从 **Kotlin 源码**里直接读 path（不用维护两份数据），
解析后做结构检查，并描边渲染成 PNG 供肉眼比对。

```bash
python tools/verify_icons.py              # 输出到 .work/icons-check.png
python tools/verify_icons.py out.png      # 指定输出文件
```

校验内容：

- 命令能否全部解析（`M/L/H/V/C/S/Q/T/Z`）
- 参数个数是否与命令匹配
- 坐标是否落在 24×24 网格内
- 子路径数是否与图标语义相符（九宫格 9 个、放大镜 2 个、信息 3 个、
  圆对勾 2 个、圆感叹号 3 个……）

> **解析器不支持 `A` 圆弧命令**，遇到会直接报「暂不支持的命令」。
> 画圆请用四段三次贝塞尔近似（控制点偏移 = 半径 × 0.5522847），
> 见 `AppIcons.CheckCircle`。

**零第三方依赖**：PNG 由 `_png.py` 编码，只用 Python 标准库。
退出码非 0 表示有图标没通过。

## make_social_preview.py

生成 GitHub 仓库的 **social preview**（1280×640，`assets/social-preview.png`）。
只在改品牌视觉（配色 / 图标 / 标语）时才需要重跑。

```bash
python tools/make_social_preview.py            # 覆盖 assets/social-preview.png
python tools/make_social_preview.py out.png    # 指定输出文件
```

配色与 `assets/icon.svg` 同一套（底色 `#2B3242 → #161A22`，主色 `#FFC24A → #FF7A00`），
内部 2x 超采样后缩放，避免斜线毛边。目前**仅支持 Windows**（字体走 `C:\Windows\Fonts`）。

图标 tile 与右侧巨型水印都读 `gen_launcher_icons.py` 的常量，tile 甚至是直接调
`render()` 拿像素 —— 换 logo 只改那边一处，这张图跟着变。

> **需要 Pillow**（`pip install pillow`）—— 这是 `tools/` 下唯一一个非标准库依赖的脚本。
> 传完后那张图要在 GitHub 仓库 **Settings → Social preview** 里手动上传，脚本不管上传。

---

# 发布与 CI

## publish.py ★

**一次把版本同时发到「源码仓库」和「LSPosed 模块仓库」。**

> **发版请看 [`docs/RELEASE.md`](../docs/RELEASE.md)** —— 那里是完整清单；
> 这里只讲脚本本身。

LSPosed 的模块仓库（`Xposed-Modules-Repo/<包名>`）是**独立仓库** ——
源码仓库发 Release **不会**同步过去，而 LSPosed 的网站与 App 只读模块仓库。
所以每次发版必须在**两个仓库各发一份同 tag、同 APK 的 Release**，
并把模块仓库那几个元文件从源码仓库同步过去。手工做很容易漏，这个脚本一次做完。

```bash
python tools/publish.py                    # 构建 + 发布 + 同步元文件
python tools/publish.py --skip-build       # 用现有 APK
python tools/publish.py --notes notes.md   # 指定 Release 说明（Markdown）
python tools/publish.py --sync-only        # 只同步元文件，不发 Release（别名 --readme-only）
python tools/publish.py --dry-run          # 只打印将做什么，不写任何东西
python tools/publish.py --check            # 只查凭据有没有两个仓库的写权限
```

> `--check` 是**发版前的探针**：只调 GitHub API，不构建、不写任何东西，
> 几秒钟就能回答「这个 token 到底能不能写进模块仓库」。
> 值得先跑它 —— 脚本是先发源码仓库、再发模块仓库，token 写不了模块仓库的话，
> 源码仓库那份已经建好了、用户却什么都收不到。`release.yml` 里也插了这一步。

| 步骤 | 做什么 |
|---|---|
| 1 | 读 `app/build.gradle.kts` 的 `versionCode` / `versionName`，tag = `{vc}-{vn}` |
| 2 | `./gradlew assembleRelease`，把 APK 复制成 `GSAuxiliary_{vc}-{vn}.apk` |
| 3 | 在**源码仓库**建/更新 Release 并上传 release 包（同名资产会先删再传） |
| 4 | 在**模块仓库**做同样的操作 |
| 5 | 同步四个元文件到模块仓库：`README.md` / `SUMMARY` / `SOURCE_URL` / `icon.svg` |

> **只发 release 包**。有过用户直接下载 debug 版当日常版本用的先例，debug 包保留
> 完整日志、签名也不该外流；需要 debug 包排查时本地 `assembleDebug` 自打自装即可。

> 第 5 步的母本都在源码仓库里（`SUMMARY` 在 `xposed-repo/`，见
> [`xposed-repo/README.md`](../xposed-repo/README.md)）。**别在网页上直接改模块仓库
> 的那几个文件** —— 下次同步会覆盖掉。

> ⚠️ **不指定 `--notes` 时会拿 CHANGELOG 的该版本段落当 Release 说明** ——
> 那段是给开发者的（症状 / 根因 / 改法 / 验证结果），对用户是噪音。
> 面向用户的 Release 说明建议另写一份短的传进来（见「发布惯例」）。

凭据按优先级取：环境变量 `GS_GITHUB_TOKEN` → 环境变量 `GH_TOKEN` →
`git credential fill`（Windows 凭据管理器里已存的 GitHub PAT，需要 `repo` scope）。
**token 只在内存里用，不落盘、不打印。**

> 走 CI 发版时凭据来自 Secrets（见 `docs/RELEASE.md` §1.2）。
> 脚本**故意不读 `GITHUB_TOKEN`** —— 那是 Actions 自带、只对当前仓库有效的短命
> token，拿去写模块仓库会 403；宁可报错也不静默用错。

环境变量可改仓库名：`GITHUB_SOURCE_REPO` / `GITHUB_MODULE_REPO`。

## ci/ ★

**给 CI 与「干净环境」用的脚本**，本地也能跑。工作流本身在
[`.github/workflows/`](../.github/workflows)，说明见
[`docs/BUILD.md`](../docs/BUILD.md) §8。

| 脚本 | 干什么 |
|---|---|
| `ci/setup-sdk37.sh` | 装好 compileSdk 37 平台并归一化成 AGP 认得的 `platforms/android-37`。幂等；下载失败自动重试，37.2 装不上会退到 37.1 / 37.0。 |
| `ci/export-sdk-env.sh` | 把 SDK 路径交给后续步骤（CI 里写 `$GITHUB_ENV` + 补 `local.properties`）。 |
| `ci/verify_apk.py` | 产物红线自查：libxposed api / annotation 类定义必须 0、无 legacy `assets/xposed_init`、manifest 无 `xposedminversion`、入口类被 R8 保留、元文件与源码母本逐字一致、**APK 已签名且不是 debug 证书**…… |
| `ci/version.py` | 读 `app/build.gradle.kts` 的版本号（CI 里唯一来源），并提供 tag 一致性校验与 shell 求值模式。 |

```bash
# 干净机器 / CI 上补 compileSdk 37
ANDROID_SDK_ROOT=/path/to/sdk bash tools/ci/setup-sdk37.sh

# 产物红线自查（纯标准库，不需要 dexdump / aapt2 / apksigner）
python tools/ci/verify_apk.py app/build/outputs/apk/release/app-release.apk

# 发版包再加两条硬校验：证书不能是调试证书、可钉死指纹
python tools/ci/verify_apk.py --reject-debug-cert \
    --expect-cert-sha256 a7fc6034… app/build/outputs/apk/release/app-release.apk

# 版本号 / tag 一致性
python tools/ci/version.py                  # 输出 vc=47 / vn=3.2.0（供 $GITHUB_OUTPUT）
python tools/ci/version.py --tag            # 47-3.2.0
python tools/ci/version.py --shell          # 输出 VC=/VN=/TAG= 三行，供 shell eval
python tools/ci/version.py --check-tag 47-3.2.0  # 不一致就 exit 1
```

环境变量：`setup-sdk37.sh` 支持 `GS_SDK_PLATFORM_PKG`（指定安装包）、
`GS_SDK_INSTALL_TRIES`（下载重试次数，默认 3）；`verify_apk.py` 支持
`GS_EXPECT_CERT_SHA256`（等价于 `--expect-cert-sha256`）。

`verify_apk.py` 的输出分三级：`[OK]` 通过、`[!]` 提醒（不影响退出码）、
`[X]` 踩红线（退出码 1）。签名证书是**纯标准库解析 APK Signing Block** 拿到的，
不依赖 `apksigner` —— 实测与本机 `apksigner verify --print-certs` 的指纹完全一致。

> `setup-sdk37.sh` 为什么必须存在：官方 SDK 仓库里根本没有 `platforms;android-37`，
> 只有 `platforms;android-37.2`（Android 16 之后新引入的次要版本命名），
> 而 AGP 8.10.1 认不出这个新命名。详见 `docs/BUILD.md` §4.1 / §8.1。

---

# 运行时探针

## probe/ ★

**运行时探针模块** —— 目标 App 升级后用来重新定位类名 / 字段名 / 控件 id。

```bash
bash tools/probe/build.sh
adb install -r tools/probe/out/GoldSunProbe.apk
```

它不依赖 Gradle，走最原始的 `javac → d8 → aapt2 → zipalign → apksigner` 链路；
其中 `pack_dex.py` 负责把 classes.dex 和 `meta/META-INF/xposed/` 目录树合并进
aapt2 产出的 APK（被 `build.sh` 内部调用，不单独使用）。
详细说明见 [probe/README.md](probe/README.md)。

---

# 代码卫生

## hygiene.py ★

**代码卫生扫描器** —— 大扫除时用的只读报告工具，扫四类候选 + 一份磁盘垃圾清单。

```bash
python tools/hygiene.py all       # 全部
python tools/hygiene.py res       # 没被引用的字符串 / 数组 / 颜色 / 尺寸 / 样式
python tools/hygiene.py decls     # 没人引用的顶层声明
python tools/hygiene.py members   # 文件内只声明、没调用的 private 成员
python tools/hygiene.py imports   # 没被用到的 import
python tools/hygiene.py junk      # 构建产物 / 临时目录 / 旧包（只报体积）
```

**它只报告、不删任何东西**。输出的是候选名单：反射、manifest 入口、
`proguard` keep 规则这些「代码里搜不到但运行期要用」的东西都会出现在名单里，
逐条 `git grep` 复核后才决定。判定口径、误报清单与清理红线见
[`docs/MAINTENANCE.md`](../docs/MAINTENANCE.md)。

---

## 调试产物位置

脚本生成的临时文件统一放在仓库根目录的 `.work/` 下（**已被 `.gitignore` 排除**）。
实际用到的路径如下 —— 没有约定的话，就放在 `.work/` 根：

```
.work/
├── shots/              drive.sh 的 shot 指令 → .work/shots/NAME.png
├── lspd.log            glog.sh tail 拉下来的日志
├── log.mark            glog.sh mark 记录的行号游标
├── wifi_serial         env.sh wait_device 缓存的无线调试地址
├── screen_timeout.bak  stayon.sh 的息屏超时原值备份
├── icons-check.png     verify_icons.py 的默认输出
├── ui*.xml             ui.sh / drive.sh 的控件树 dump（ui.xml / ui_tmp.xml / app_ui.xml …）
└── *.png / *.raw       drive.sh 截图、手工取色留下的裸帧
```

> **`.work/` 是暂存区，大部分可以随时删** —— 截图、日志、控件树 dump、解出来的宿主缓存
> 全部由脚本按需重建，不属于交付物。**三个例外**：
> `.work/lsposed-repo/`（`publish.py` 的模块仓库克隆，删了要重新 clone 才能发布）、
> `.work/log.mark`（`glog.sh` 的行号游标）、`.work/wifi_serial`（无线调试地址）。
> `.work/apk/` 只留当前版本一个包。
>
> 什么时候清、清到什么程度，见 [`docs/MAINTENANCE.md`](../docs/MAINTENANCE.md)。
> `.work/` 不会进 Git（`.gitignore` 有 `/.work/`），删它不影响仓库。
