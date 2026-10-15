# tools —— 开发期工具

这些脚本**不参与 APK 打包**，只用于开发调试、自动化验证、以及 App 升级后重新定位。

所有脚本都做了**路径与环境自动探测**，不需要把本机绝对路径写死。
需要时可以用环境变量覆盖：

| 变量 | 说明 |
|---|---|
| `ANDROID_SDK` / `ANDROID_HOME` | Android SDK 根目录 |
| `ADB` | adb 可执行文件全路径 |
| `PYTHON` | python 可执行文件全路径 |
| `FFMPEG` | ffmpeg 可执行文件全路径（取色用） |
| `TARGET_PKG` | 目标 App 包名，默认 `com.guosen.android` |
| `MODULE_PKG` | 模块包名，默认 `gsauxiliary.m.venn` |
| `ADB_WIFI_TARGET` | 无线调试地址，如 `192.168.1.10:5555` |

---

## env.sh

公共环境探测，被其它脚本 `source`。也可单独用来排查环境：

```bash
bash -c '. tools/env.sh; echo "adb=$ADB"; echo "python=$PYTHON"; echo "ffmpeg=$FFMPEG"'
```

---

## drive.sh

批量驱动设备 UI。把一串指令一次性执行，适合复现固定操作路径。

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

**常用导航序列**（到策略详情页）：

```bash
bash tools/drive.sh "stop" "wait 2" "launch" "wait 14" \
  "tap 324 471" "wait 6" "tap 890 340" "wait 7" "tap 78 326" "wait 8"
```

> 坐标基于 1080×2400 屏幕。换机型请先用 `uiscan` 重新取坐标。
> 设备息屏时脚本会点不动，开头加 `"key 224" "wait 2" "swipe 540 2000 540 900 250" "wait 3"`。

---

## sh.sh

在设备上执行 shell 命令（自动选设备，不重启 adb server）。

```bash
bash tools/sh.sh "ls -la /sdcard"                     # 普通命令
bash tools/sh.sh --root "ls /data/adb/lspd/log"       # root 命令
bash tools/sh.sh --pkg                                 # dump 模块包信息
```

> `--root` 的实现是 `su -c '<cmd>'`，所以命令里**不能出现单引号**，
> 否则会截断外层引号。需要引号时请拆成多条执行，管道放到宿主机侧。

---

## verify.py ★

**自动校验策略详情页的高亮是否正确** —— 这是本项目最主要的回归测试手段。

原理：解析控件树找到每只股票的名称节点 → 截图 → 用 ffmpeg 解码成 RGB24
→ 在名称边界框里采样像素 → 判断主色是否是期望的高亮色。

```bash
python tools/verify.py                  # 默认校验琥珀黄 #FF8000
python tools/verify.py --color 3482FF   # 校验其它高亮色
```

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

需要 `ffmpeg`（`FFMPEG` 环境变量或 PATH 中）。

---

## sample_colors.py

从截图里采样若干矩形区域的主色，排查"颜色到底对不对"。

```bash
python tools/sample_colors.py .work/shots/s01.png 100,200,400,260
python tools/sample_colors.py shot.png --size 1080x2400 0,0,1080,2400
```

---

## uifind.py

从 `uiautomator dump` 出来的 XML 里按文字找控件坐标。

```bash
python tools/uifind.py ui.xml "我的策略"    # 打印可点击控件中心 "x y"
python tools/uifind.py ui.xml --list        # 列出所有有文字的控件及坐标
```

`drive.sh` 的 `uifind` / `uiscan` 指令内部就是调它。

---

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
python tools/enable_lsposed.py mc.db gsauxiliary.m.venn /data/app/.../base.apk com.guosen.android 0
adb push mc.db /sdcard/mc.db
adb shell su -c "cp /sdcard/mc.db /data/adb/lspd/config/modules_config.db"
```

会改三张表：`modules`（登记包名与 APK 路径）、`modules_state`（置 enabled=1）、
`scope`（把目标 App 加入作用域）。

> 改完需要重启目标 App（或重启设备）才生效。

---

## gen_launcher_icons.py ★

生成 launcher 图标的**位图回退资源**（`app/src/main/res/mipmap-{mdpi..xxxhdpi}/`）。

自适应图标（`mipmap-anydpi-v26`）只在 API 26+ 生效，低版本和部分第三方桌面
仍会读 `mipmap-*dpi` 的 PNG，所以这些位图必须存在。

```bash
python tools/gen_launcher_icons.py
```

图案是「圆角方形 + 竖向渐变底 + 金色上升折线 + 右上箭头」（简约苹果风格），
**必须与下面两个矢量文件逐项一致**：

- `app/src/main/res/drawable/ic_launcher_background.xml`（渐变底）
- `app/src/main/res/drawable/ic_launcher_foreground.xml`（折线 + 箭头）

脚本顶部的 `BG_TOP` / `BG_BOTTOM` / `LINE_TOP` / `LINE_BOTTOM` /
`POLYLINE` / `ARROW` / `STROKE` / `CORNER` 就是这份对照表，
**改了一边就要改另一边**。

实现要点：

- **零第三方依赖**（`math` / `struct` / `zlib`），PNG 自己编码，不依赖 Pillow
- 前景用**距离场**描边：逐像素求到折线各段的最短距离，`距离 <= 线宽/2` 即上色 ——
  圆头笔帽与圆角拐点由距离场天然产生，不需要单独处理 line cap / line join
- 背景逐行算圆角矩形的 x 区间再填竖向渐变
- 4× 超采样降采样做抗锯齿；`ic_launcher_round.png` 额外套一层圆形遮罩

设计说明见 `docs/UI.md` §12。

---

## verify_icons.py ★

校验自绘图标（`ui/AppIcons.kt`）的 path 数据。

界面图标是手写的 SVG path（背景见 `docs/UI.md` §10）。path 写错一个数字照样能编译通过，
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

**零第三方依赖**：PNG 由脚本自己编码，只用 Python 标准库。
退出码非 0 表示有图标没通过。

---

## dev.sh

**无线调试保活 / 自动重连**。返回一个可用的 `ip:port` serial。

```bash
DEV="$(bash tools/dev.sh)" || exit 1
adb -s "$DEV" shell ...
```

它解决三件事：

| 问题 | 现象 |
|---|---|
| adb server 被回收 | 每条命令前都得重连一次，否则 `device not found` |
| 无线调试端口会变 | 写死 `10.0.0.26:38131` 迟早失效 → 用 mDNS 自动发现端口 |
| mDNS 会多注册一条虚拟 serial | `adb-XXXX._adb-tls-connect._tcp` 常排第一，把 `wait_device` 的「首台设备」抢走 → 本脚本只返回 `ip:port` 形式 |

环境变量：`ADB_WIFI_HOST`（默认 `10.0.0.26`）、`ADB_MDNS_PORT`（已知端口时跳过发现）。

---

## adbw.sh

**带自动重连的 adb 包装**。长脚本里裸调 `adb shell` 迟早挂在半路，把重试交给它。

```bash
bash tools/adbw.sh shell "input tap 540 1020"
bash tools/adbw.sh exec-out screencap -p > shot.png
bash tools/adbw.sh pull /sdcard/ui.xml F:/Project/GoldSunEnhance/.work/ui.xml
bash tools/adbw.sh dev            # 只打印当前可用 serial
```

> 脚本内部已 `export MSYS_NO_PATHCONV=1` —— 否则 Git Bash 会把远端路径
> `/sdcard/ui.xml` 改写成 `F:/Program Files/.../sdcard/ui.xml`，`pull` 直接报
> `failed to stat remote object`。**本地路径要自己用 `winpath` 转成 `F:/...`。**
> 需要 root 时整条命令包一层 `su -c`，注意别在命令里再用双引号。

---

## ui.sh + uidump.py

**目标 App 的 UI 自动化**：dump 控件树 / 列元素 / 按文字定位并点击。

```bash
bash tools/ui.sh list            # 列出元素（坐标 / 可点击 / 文字）
bash tools/ui.sh tap "我的策略"   # dump + 定位（优先可点击祖先）+ 点击
bash tools/ui.sh tapxy 540 1020  # 直接点坐标
bash tools/ui.sh act             # 打印栈顶 Activity
```

`uidump.py` 是解析器（也可单独用）：

```bash
python tools/uidump.py ui.xml --list
python tools/uidump.py ui.xml --find "标准行情"
python tools/uidump.py ui.xml --find-class EditText
```

**两个必须记住的点**：

1. **dump 与 tap 必须在同一次调用里** —— 分两次执行时界面可能已经滚动，坐标就漂了。
2. 截图预览被缩到约 486 宽，**实际坐标 = 预览坐标 × 2.222**（1080×2400 屏）。

---

## glog.sh + glog_tail.py

**读 LSPosed 模块日志的增量**。

模块日志**不在 logcat**，在设备的 `/data/adb/lspd/log/modules_*.log`，动辄几万行。
本脚本按行号游标取增量，并剥掉 LSPosed 前缀。

```bash
bash tools/glog.sh mark                        # 记录当前行数
# …在设备上操作…
bash tools/glog.sh tail                        # 打印新增行（默认只留探针的行）
bash tools/glog.sh tail --module gsauxiliary   # 只看主模块
bash tools/glog.sh tail --module all           # 不过滤
bash tools/glog.sh rlog                        # 只看日志文件路径
```

> ⚠️ `/data/adb/lspd/config/modules_config.db` **是陈旧快照，别拿它判断模块有没有生效**。
> 唯一可信的判据是这份日志。

---

## probe/ ★


**运行时探针模块** —— 目标 App 升级后用来重新定位类名 / 字段名 / 控件 id。

```bash
bash tools/probe/build.sh
adb install -r tools/probe/out/GoldSunProbe.apk
```

它不依赖 Gradle，走最原始的 `javac → d8 → aapt2 → zipalign → apksigner` 链路。
详细说明见 [probe/README.md](probe/README.md)。

---

## 调试产物位置

脚本生成的临时文件统一放在仓库根目录的 `.work/` 下（**已被 `.gitignore` 排除**）。
实际用到的路径如下 —— 没有约定的话，就放在 `.work/` 根：

```
.work/
├── shots/              drive.sh 的 shot 指令 → .work/shots/NAME.png
├── lspd.log            glog.sh tail 拉下来的日志
├── log.mark            glog.sh mark 记录的行号游标
├── icons-check.png     verify_icons.py 的默认输出
├── ui*.xml             ui.sh / drive.sh 的控件树 dump（ui.xml / ui_tmp.xml / app_ui.xml …）
└── *.png / *.raw       drive.sh 截图、手工取色留下的裸帧
```

> **`.work/` 是纯暂存区，可以随时整体删除**（`rm -rf .work`）——
> 里面的东西全部由脚本按需重建。它不属于交付物，
> 仓库里也确实留过一批历史截图与反编译产物，已清理过一次；
> 日常跑完脚本后不必刻意收拾，攒多了直接删掉整个目录即可。
>
> `.work/` 不会进 Git（`.gitignore` 有 `/.work/`），所以删它不影响仓库。
