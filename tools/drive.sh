#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# drive.sh —— 批量驱动设备 UI 并截图（调试 / 自动化验证用）
#
# 用法:
#   bash tools/drive.sh "stop" "launch" "wait 14" "tap 324 471" "shot s01"
#
# 指令:
#   tap X Y                     点击
#   swipe X1 Y1 X2 Y2 [MS]      滑动
#   wait SEC                    等待
#   shot NAME                   截图到 .work/shots/NAME.png
#   back / home                 返回键 / 主页键
#   key CODE                    发送按键
#   text STR                    输入文本
#   act                         打印当前 Activity
#   stop                        强制停止目标 App
#   launch                      启动目标 App
#   install [PATH]              安装 APK（默认取构建产物）
#   logclear                    清空 logcat
#   uifind TEXT                 按文字找控件并点击
#   uiscan                      列出当前界面所有文字 + 坐标
#
# 环境变量（可选）:
#   TARGET_PKG        目标 App 包名，默认 com.guosen.android
#   ADB_WIFI_TARGET   无线调试地址，如 192.168.1.10:5555（可选）
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

PKG="${TARGET_PKG}"
WORK="$REPO_DIR/.work"
mkdir -p "$WORK/shots" "$WORK/logs"

DEV="$(wait_device 60)" || { echo "[drive] 放弃: 无设备"; exit 1; }
echo "[drive] 设备 = $DEV"

A=("$ADB" -s "$DEV")

dump_ui() {
    "${A[@]}" shell "uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
    "${A[@]}" shell "cat /sdcard/ui.xml" > "$WORK/ui_tmp.xml" 2>/dev/null
}

for cmd in "$@"; do
    # shellcheck disable=SC2086
    set -- $cmd
    op="$1"; shift || true
    case "$op" in
        tap)    "${A[@]}" shell "input tap $1 $2"; echo "  > tap $1 $2" ;;
        swipe)  "${A[@]}" shell "input swipe $1 $2 $3 $4 ${5:-300}"; echo "  > swipe $1 $2 -> $3 $4" ;;
        wait)   sleep "$1"; echo "  > wait ${1}s" ;;
        shot)   "${A[@]}" exec-out screencap -p > "$WORK/shots/$1.png" 2>/dev/null
                echo "  > shot .work/shots/$1.png" ;;
        back)   "${A[@]}" shell "input keyevent 4"; echo "  > back" ;;
        home)   "${A[@]}" shell "input keyevent 3"; echo "  > home" ;;
        key)    "${A[@]}" shell "input keyevent $1"; echo "  > key $1" ;;
        text)   "${A[@]}" shell "input text '$1'"; echo "  > text $1" ;;
        act)    "${A[@]}" shell "dumpsys activity activities" 2>/dev/null \
                    | grep -o 'com\.[a-zA-Z0-9_.]*Act[a-zA-Z0-9_]*' | head -3
                echo "  > act" ;;
        stop)   "${A[@]}" shell "am force-stop $PKG"; echo "  > stop $PKG" ;;
        launch) "${A[@]}" shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
                echo "  > launch $PKG" ;;
        install)
                APK="${1:-$REPO_DIR/app/build/outputs/apk/release/app-release.apk}"
                if [ ! -f "$APK" ]; then
                    echo "  ! APK 不存在: $APK（先跑 ./gradlew assembleRelease）"
                else
                    "${A[@]}" install -r "$(winpath "$APK")" 2>&1 | tail -1 | sed 's/^/  > install: /'
                fi ;;
        logclear) "${A[@]}" logcat -c; echo "  > logclear" ;;
        uifind)
                dump_ui
                COORD="$("$PYTHON" "$(winpath "$HERE/uifind.py")" \
                            "$(winpath "$WORK/ui_tmp.xml")" "$1" 2>/dev/null)"
                if [ -n "$COORD" ]; then
                    "${A[@]}" shell "input tap $COORD"
                    echo "  > uifind '$1' -> tap $COORD"
                else
                    echo "  > uifind '$1' 未找到"
                fi ;;
        uiscan)
                dump_ui
                "$PYTHON" "$(winpath "$HERE/uifind.py")" \
                          "$(winpath "$WORK/ui_tmp.xml")" --list 2>/dev/null | sed 's/^/    /'
                echo "  > uiscan" ;;
        *)      echo "  ? 未知指令: $op" ;;
    esac
done
