#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# glog.sh —— 读 LSPosed 模块日志（增量）
#
# 为什么需要它：
#   模块日志**不在 logcat**，而在设备的 `/data/adb/lspd/log/modules_*.log`，
#   而且文件很大（几万行）。每次排查都 `cat` 全文 + 肉眼找，效率极低。
#   本脚本按「行号游标」取增量，并剥掉 LSPosed 的前缀。
#
# 用法:
#   bash tools/glog.sh mark                 记录当前行数到 .work/log.mark
#   bash tools/glog.sh tail                 打印自 mark 之后的新增行（默认只留探针的行）
#   bash tools/glog.sh all                  打印整个日志
#   bash tools/glog.sh tail --module gsauxiliary   过滤主模块的行
#   bash tools/glog.sh tail --module all           不过滤
#   bash tools/glog.sh rlog                 打印日志文件路径
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

PY="${PYTHON:-python}"
MARK="$REPO_DIR/.work/log.mark"
RAW="$REPO_DIR/.work/lspd.log"
mkdir -p "$REPO_DIR/.work"

LOG="$(bash "$HERE/adbw.sh" shell "su -c 'ls /data/adb/lspd/log/modules_*.log'" | tr -d '\r' | tail -1)"
[ -n "$LOG" ] || { echo "[glog] 没找到模块日志（设备没连上？或 LSPosed 未运行）" >&2; exit 1; }

cmd="${1:-tail}"
shift || true

# --module <过滤串>：默认只看探针
MODULE='com.wb.probe'
if [ "${1:-}" = "--module" ]; then
    MODULE="${2:-com.wb.probe}"
    [ "$MODULE" = "all" ] && MODULE=''
fi

case "$cmd" in
    mark)
        bash "$HERE/adbw.sh" shell "su -c 'wc -l < $LOG'" | tr -d '\r ' > "$MARK"
        echo "mark = $(cat "$MARK")" ;;
    tail|all)
        bash "$HERE/adbw.sh" shell "su -c cat $LOG" > "$RAW" 2>/dev/null
        start=0
        if [ "$cmd" = "tail" ] && [ -f "$MARK" ]; then start="$(cat "$MARK")"; fi
        "$PY" "$HERE/glog_tail.py" "$start" "$RAW" --module "$MODULE" ;;
    rlog) echo "$LOG" ;;
    *) echo "用法: glog.sh [mark|tail|all|rlog] [--module <包名子串|all>]" >&2; exit 2 ;;
esac
