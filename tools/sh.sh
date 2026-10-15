#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# sh.sh —— 在设备上执行 shell 命令（自动选设备，不重启 adb server）
#
# 用法:
#   bash tools/sh.sh "ls -la /sdcard"
#   bash tools/sh.sh --root "ls -la /data/adb/lspd/log"
#   bash tools/sh.sh --pkg  "dumpsys package gsauxiliary.m.venn"
#
# 注意:
#   --root 的实现是 su -c '<cmd>'，所以 <cmd> 里**不能出现单引号**，
#   否则会截断外层引号。需要引号时请拆成多条命令执行。
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

MODE="plain"
case "${1:-}" in
    --root) MODE="root"; shift ;;
    --pkg)  MODE="pkg";  shift ;;
esac

DEV="$(wait_device 60)" || { echo "[sh] 无设备" >&2; exit 1; }

case "$MODE" in
    root) "$ADB" -s "$DEV" shell "su -c '$*'" ;;
    pkg)  "$ADB" -s "$DEV" shell "dumpsys package $MODULE_PKG" ;;
    *)    "$ADB" -s "$DEV" shell "$*" ;;
esac
