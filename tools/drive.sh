#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# drive.sh —— 批量驱动设备 UI（调试 / 自动化验证用）
#
# 设计目标：把「一步一步走」压成「一条命令跑完一条链路」，每一步都带耗时。
#
# 用法:
#   bash tools/drive.sh "stop" "launch" "waitact MainActivity" "uifind 智能选股" "shot s01"
#   bash tools/drive.sh "launch" "waitact MainActivity" batch "input tap 540 1200;" "input tap 540 900"
#
# 指令:
#   tap X Y                     点击
#   swipe X1 Y1 X2 Y2 [MS]      滑动
#   wait SEC                    死等（能 waitact/waitfor 就别用这个）
#   waitact PATTERN [SEC]       轮询到栈顶 Activity 匹配 PATTERN（~100ms/次，默认 30s 超时）
#   waitfor TEXT [SEC]          轮询到界面出现文字（走 uiautomator dump，~2.2s/次）
#   shot NAME                   截图 -> .work/shots/NAME.png（raw 取帧本地编码，比 screencap -p 快约 2x）
#   snap NAME                   只存原始帧 -> .work/raw/NAME.raw（免 PNG 编码，供像素比对）
#   back / home                 返回键 / 主页键
#   key CODE                    发送按键
#   text STR                    输入文本（空格用 %s）
#   act                         打印当前 Activity
#   sig                         打印界面签名（focus + resumed），判断界面是否真的变了
#   stop                        强制停止目标 App
#   launch                      启动目标 App
#   install [PATH]              安装 APK（默认取构建产物）
#   logclear                    清空 logcat
#   uifind TEXT                 按文字找控件并点击（带 5s 界面缓存）
#   uiscan                      列出当前界面所有文字 + 坐标（强制重新 dump）
#   batch CMD...                把余下参数拼成**一条** shell 发到设备，省掉每步 75ms 往返
#
# 环境变量（可选）:
#   DEVICE            精确 serial；不设则 MuMu(127.0.0.1:16416) 优先，其次无线，最后任意
#   MUMU_ADB_PORT     MuMu adb 端口，默认 16416
#   TARGET_PKG        目标 App 包名，默认 com.guosen.android
#   （UI 树按界面签名缓存，签名不变就不重新 dump；uiscan 总是强制刷新）
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

PKG="${TARGET_PKG}"
WORK="$REPO_DIR/.work"
mkdir -p "$WORK/shots" "$WORK/raw" "$WORK/logs"

DEV="$(resolve_device 60)" || { echo "[drive] 放弃: 无设备"; exit 1; }
echo "[drive] 设备 = $DEV"

A=("$ADB" -s "$DEV")
PY="${PYTHON:-python}"
# Windows 的 python 默认按 cp936 写 stdout，中文控件文字会变乱码
export PYTHONIOENCODING=utf-8
R2P="$(winpath "$HERE/raw2png.py")"
UIDUMP="$(winpath "$HERE/uidump.py")"
WORK_W="$(winpath "$WORK")"

# ---------------------------------------------------------------------------
# 进程内桥（TBridge）
#
# 与上面 uifind/uiscan 的区别是数量级：uifind 走 uiautomator dump（~2.2s/次），
# 桥走的是 App 进程内的 View 对象（~1ms/次）。TB 为空表示本机没装桥，
# 此时 tb* 指令会提示，其余指令不受影响。
# ---------------------------------------------------------------------------
TB="$HERE/../../TBridge/tools/tbridge.py"
[ -f "$TB" ] || TB=""

T0_ALL="$(date +%s%3N)"
step_t0="$T0_ALL"

# 每步结尾打耗时，方便定位慢在哪
# 调进程内桥。ADB/DEVICE 用前置赋值的方式传，避免把全局 ADB 改写成
# Windows 路径（下面的 A 数组依赖 MSYS 路径）。
# python 是原生 Windows 程序，收不到 MSYS 风格的 /f/... 路径。
TB_ADB_W=""
TB_W=""
tb_call() {
    if [ -z "$TB" ]; then
        echo "  ! 未找到 TBridge（$HERE/../../TBridge/tools/tbridge.py）"
        return 1
    fi
    [ -n "$TB_ADB_W" ] || TB_ADB_W="$(winpath "$ADB")"
    # python 是原生 Windows 程序，脚本路径也要转 —— MSYS 的 /f/... 它打不开，
    # 报错又会被下面的 2>/dev/null 吞掉，表现为"指令跑了但没有任何输出"。
    [ -n "$TB_W" ] || TB_W="$(winpath "$TB")"
    # cd 进 .work 再跑：tbmulti 里的相对路径（shot a.png）就落在 .work/ 而不是
    # 工程根 —— 之前漏了这步，跑一次就在仓库根留下 d1 / final 两个野文件。
    ( cd "$WORK" && ADB="$TB_ADB_W" DEVICE="$DEV" "$PY" "$TB_W" "$PKG" "$@" 2>/dev/null )
}

mark() {
    local now; now="$(date +%s%3N)"
    printf '  [%sms]\n' "$((now - step_t0))"
    step_t0="$now"
}

# --- 界面签名：focus + resumed activity。一次约 100ms，比 dump 便宜 20 倍 ---
ui_sig() {
    "${A[@]}" shell "dumpsys window 2>/dev/null | grep -m2 -E 'mCurrentFocus|mFocusedApp'" 2>/dev/null | tr -d '\r'
}

# --- dump 一次 UI 树，带缓存 ---
# uiautomator dump 实测 2.2–2.3s（MuMu 900x1600），是整条链路最大的开销。
# 缓存判据只有一条：界面签名（focus + focusedApp）与上次相同就复用旧树。
# 不要在这里加时间窗口——MSYS 下 stat -c %Y / date 的输出混进 $(( )) 会报
# "integer expected"，缓存会一直失效（踩过两次）。签名空值时也不缓存，
# 免得 dumpsys 偶发失败就把一棵空树当成有效结果。
dump_ui() {
    local force="${1:-0}" sig cur
    sig="$(ui_sig)"
    cur="$(cat "$WORK/.ui_sig" 2>/dev/null || true)"
    if [ "$force" != "1" ] && [ -s "$WORK/ui_tmp.xml" ] && [ -n "$sig" ] && [ "$cur" = "$sig" ]; then
        echo "    (界面签名未变，复用上一次的 UI 树)"
        return 0
    fi
    "${A[@]}" shell "uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
    "${A[@]}" shell "cat /sdcard/ui.xml" > "$WORK/ui_tmp.xml" 2>/dev/null
    printf '%s' "$sig" > "$WORK/.ui_sig"
}

# 用数组显式索引：不能用 set -- 改位置参数，那会把下一条指令当成当前指令的参数
# （踩过：waitact guosen 把 "uiscan" 吃成超时秒数，然后 uiscan 又被当指令跑一次）。
ARGS=("$@")
NARGS=${#ARGS[@]}
IDX=0
while [ "$IDX" -lt "$NARGS" ]; do
    cmd="${ARGS[$IDX]}"; IDX=$(( IDX + 1 ))

    # batch 是终止指令：把余下 argv 用空格拼成一条 shell，一次往返全部执行
    if [ "$cmd" = "batch" ]; then
        script=""
        while [ "$IDX" -lt "$NARGS" ]; do
            script="$script ${ARGS[$IDX]}"; IDX=$(( IDX + 1 ))
        done
        script="${script# }"
        if [ -z "$script" ]; then
            echo "  ? batch 后面没有命令"
        else
            "${A[@]}" shell "$script"
            echo "  > batch: $script"
        fi
        mark
        break
    fi

    # shellcheck disable=SC2086
    set -- $cmd
    op="$1"; shift || true

    case "$op" in
        tap)    "${A[@]}" shell "input tap ${1:-0} ${2:-0}"; echo "  > tap ${1:-0} ${2:-0}"; mark ;;
        swipe)  "${A[@]}" shell "input swipe ${1:-0} ${2:-0} ${3:-0} ${4:-0} ${5:-300}"
                echo "  > swipe ${1:-0} ${2:-0} -> ${3:-0} ${4:-0}"; mark ;;
        wait)   sleep "${1:-1}"; echo "  > wait ${1:-1}s"; mark ;;

        waitact)
                pat="${1:-}"; to="${2:-30}"; t0="$(date +%s%3N)"
                while :; do
                    if "${A[@]}" shell "dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity'" 2>/dev/null | tr -d '\r' | grep -q -- "$pat"; then
                        echo "  > waitact '$pat' 命中"; mark; break
                    fi
                    if [ $(( $(date +%s%3N) - t0 )) -ge $(( to * 1000 )) ]; then
                        echo "  ! waitact '$pat' 超时 ${to}s"; mark; break
                    fi
                    sleep 0.2
                done ;;

        waitfor)
                txt="${1:-}"; to="${2:-30}"; t0="$(date +%s%3N)"
                while :; do
                    dump_ui 1
                    if "$PY" "$UIDUMP" "$WORK_W/ui_tmp.xml" --find "$txt" 2>/dev/null | grep -q .; then
                        echo "  > waitfor '$txt' 命中"; mark; break
                    fi
                    if [ $(( $(date +%s%3N) - t0 )) -ge $(( to * 1000 )) ]; then
                        echo "  ! waitfor '$txt' 超时 ${to}s"; mark; break
                    fi
                    sleep 0.3
                done ;;

        shot)   "${A[@]}" exec-out screencap > "$WORK/raw/.tmp.raw" 2>/dev/null
                if "$PY" "$R2P" "$WORK_W/raw/.tmp.raw" "$WORK_W/shots/${1:-shot}.png" >/dev/null 2>&1; then
                    echo "  > shot .work/shots/${1:-shot}.png"
                else
                    echo "  ! shot 失败（raw 帧异常）"
                fi
                rm -f "$WORK/raw/.tmp.raw"; mark ;;

        snap)   "${A[@]}" exec-out screencap > "$WORK/raw/${1:-snap}.raw" 2>/dev/null
                echo "  > snap .work/raw/${1:-snap}.raw ($(stat -c %s "$WORK/raw/${1:-snap}.raw" 2>/dev/null || echo 0) B)"
                mark ;;

        back)   "${A[@]}" shell "input keyevent 4"; echo "  > back"; mark ;;
        home)   "${A[@]}" shell "input keyevent 3"; echo "  > home"; mark ;;
        key)    "${A[@]}" shell "input keyevent ${1:-}"; echo "  > key ${1:-}"; mark ;;
        text)   "${A[@]}" shell "input text '${1:-}'"; echo "  > text ${1:-}"; mark ;;

        act)    "${A[@]}" shell "dumpsys activity activities" 2>/dev/null \
                    | grep -oE 'com\.[a-zA-Z0-9_.]*Act[a-zA-Z0-9_]*' | head -3
                echo "  > act"; mark ;;

        sig)    ui_sig | sed 's/^/    /'; echo "  > sig"; mark ;;

        stop)   "${A[@]}" shell "am force-stop $PKG"; echo "  > stop $PKG"; mark ;;
        launch) "${A[@]}" shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
                echo "  > launch $PKG"; mark ;;

        install)
                APK="${1:-$REPO_DIR/app/build/outputs/apk/release/app-release.apk}"
                if [ ! -f "$APK" ]; then
                    echo "  ! APK 不存在: $APK（先跑 ./gradlew assembleRelease）"
                else
                    "${A[@]}" install -r "$(winpath "$APK")" 2>&1 | tail -1 | sed 's/^/  > install: /'
                fi
                mark ;;

        logclear) "${A[@]}" logcat -c; echo "  > logclear"; mark ;;

        # ---- 进程内桥（TBridge）-----------------------------------------
        # 全部走 tb.com.<包名> 的 abstract socket，毫秒级。前三条是纯读操作，
        # 可以高频轮询；tbwait 的默认轮询间隔 20ms（对应 uiautomator 时代
        # 一轮 2.2s 的粒度，这里能压到毫秒是因为查树只要 1ms）。
        tbping) tb_call ping | sed 's/^/    /'; echo "  > tbping"; mark ;;
        # 等桥就绪：App 冷启动到 onPackageReady 之间 socket 还不存在，
        # 这段窗口里任何 tb* 都会连接失败。launch 之后先跑这一条。
        tbready) to="${1:-15}"; t0="$(date +%s%3N)"
                while :; do
                    if tb_call ping 2>/dev/null | grep -q '"ok"'; then
                        echo "  > tbready 桥就绪"; mark; break
                    fi
                    if [ $(( $(date +%s%3N) - t0 )) -ge $(( to * 1000 )) ]; then
                        echo "  ! tbready 超时 ${to}s（App 没起 / 没勾作用域 / 没重启 zygote）"
                        mark; break
                    fi
                    sleep 0.1
                done ;;
        tbact)  tb_call act | sed 's/^/    /'; echo "  > tbact"; mark ;;
        tbtree) tb_call tree | sed 's/^/    /'; echo "  > tbtree"; mark ;;
        tbwait) tb_call wait "${1:-}" "${2:-10}" | sed 's/^/    /'
                echo "  > tbwait '${1:-}'"; mark ;;
        tbtap)  tb_call tap "${1:-}" | grep -E '"ok"|"via"|"err"' | sed 's/^/    /'
                echo "  > tbtap '${1:-}'"; mark ;;
        # 存在就点、不存在跳过。冷启动弹窗时有时无，用 tbwait 等它是白等。
        tbtapif) tb_call tapif "${1:-}" "${2:-0}" | grep -E '"ok"|"via"|"skipped"|"err"' | sed 's/^/    /'
                echo "  > tbtapif '${1:-}'"; mark ;;
        tbset)  tb_call settext "${1:-}" "${2:-}" | grep -E '"ok"|"err"' | sed 's/^/    /'
                echo "  > tbset #${1:-} '${2:-}'"; mark ;;
        tbshot) tb_call shot "$WORK_W/shots/${1:-shot}.png" | sed 's/^/    /'
                echo "  > tbshot .work/shots/${1:-shot}.png"; mark ;;
        # 一条指令跑整条链路：行内用 ;; 分隔，空格照常写（\$* 已把 set -- 拆开的词拼回）。
        # python 冷启动约 180ms，只付一次；之后每条 op 1ms 量级。
        tbmulti) tb_call multi "$*" | sed 's/^/    /'
                echo "  > tbmulti"; mark ;;

        uifind)
                dump_ui 0
                COORD="$("$PY" "$UIDUMP" "$WORK_W/ui_tmp.xml" --find "${1:-}" 2>/dev/null)"
                if [ -n "$COORD" ]; then
                    "${A[@]}" shell "input tap $COORD"
                    echo "  > uifind '$1' -> tap $COORD"
                else
                    echo "  > uifind '$1' 未找到"
                fi
                mark ;;

        uiscan)
                dump_ui 1
                "$PY" "$UIDUMP" "$WORK_W/ui_tmp.xml" --list 2>/dev/null | sed 's/^/    /'
                echo "  > uiscan"; mark ;;

        *)      echo "  ? 未知指令: $op" ;;
    esac
done

NOW="$(date +%s%3N)"
echo "[drive] 总耗时 $(( NOW - T0_ALL ))ms"
