#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ui.sh —— 目标 App 的 UI 自动化小工具（dump / 找控件 / 点击）
#
# 用途：复现「首页 → 智能选股 → 我的策略 → 策略详情」这类固定链路，
#       或者按文字定位控件后点击。所有 adb 调用都走 tools/adbw.sh（自动重连）。
#
# 用法:
#   bash tools/ui.sh list            列出当前界面元素（含坐标 / 可点击 / 文字）
#   bash tools/ui.sh find "文字"      只打印匹配坐标（优先可点击祖先）
#   bash tools/ui.sh tap  "文字"      dump + 定位 + 点击
#   bash tools/ui.sh tapxy X Y        直接点坐标
#   bash tools/ui.sh dump             只重新 dump
#   bash tools/ui.sh act              打印栈顶 Activity
#
# 说明:
#   - **dump 与 tap 必须在同一次调用里完成**（同一进程/同一命令串），
#     否则界面可能已经滚动，坐标就漂了。
#   - 截图显示坐标 × 2.222 = 实际坐标（1080×2400 屏，预览被缩到约 486 宽）。
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

PY="${PYTHON:-python}"
OUT_DIR="$REPO_DIR/.work"
WIN_XML="$(winpath "$OUT_DIR/app_ui.xml")"
mkdir -p "$OUT_DIR"

do_dump() {
    bash "$HERE/adbw.sh" shell "rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
    bash "$HERE/adbw.sh" pull /sdcard/ui.xml "$WIN_XML" >/dev/null 2>&1
}

cmd="${1:-list}"
case "$cmd" in
    dump) do_dump ;;
    list) do_dump; "$PY" "$HERE/uidump.py" "$OUT_DIR/app_ui.xml" --list ;;
    find) do_dump; "$PY" "$HERE/uidump.py" "$OUT_DIR/app_ui.xml" --find "$2" ;;
    tap)
        do_dump
        c="$("$PY" "$HERE/uidump.py" "$OUT_DIR/app_ui.xml" --find "$2")"
        if [ -z "$c" ]; then echo "未找到: $2" >&2; exit 1; fi
        bash "$HERE/adbw.sh" shell "input tap $c" >/dev/null 2>&1
        echo "tap '$2' -> $c" ;;
    tapxy)
        bash "$HERE/adbw.sh" shell "input tap $2 $3" >/dev/null 2>&1
        echo "tap $2 $3" ;;
    act)
        bash "$HERE/adbw.sh" shell \
            "dumpsys activity activities | grep -m1 topResumedActivity" \
            | sed 's/.*ActivityRecord{[^}]*} [^ ]* //' ;;
    *) echo "用法: ui.sh [list|find|tap|tapxy|dump|act]" >&2; exit 2 ;;
esac
