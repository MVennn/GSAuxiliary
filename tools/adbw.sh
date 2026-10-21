#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# adbw.sh —— 带自动重连的 adb 包装（开发期工具）
#
# 为什么需要它：
#   无线调试场景下 adb server 会被反复回收、端口会变、连接会中途掉线。
#   裸调 `adb shell ...` 在长脚本里迟早挂在半路 —— 本脚本负责「重连 + 重试」。
#
# 用法:
#   bash tools/adbw.sh shell "input tap 540 1020"        # 等价 adb -s <dev> shell ...
#   bash tools/adbw.sh exec-out screencap -p > shot.png
#   bash tools/adbw.sh pull /sdcard/ui.xml F:/x/ui.xml
#   bash tools/adbw.sh dev                              # 只打印当前可用 serial
#
# 说明:
#   - 本地路径请自行用 `winpath`（env.sh 提供）转成 F:/... 形式传入。
#   - 需要 root 时把整条命令包一层：bash tools/adbw.sh shell "su -c 'id'"
#     （注意本脚本用双引号包命令，命令里**不要**再用双引号）。
# ---------------------------------------------------------------------------
set -u

# adb 是原生 Windows 程序：远端路径（/sdcard/...）绝不能被 MSYS 改写成 F:/Program Files/...
# 否则 pull/push 会报 "failed to stat remote object"。本地路径调用方自己用 winpath 转。
export MSYS_NO_PATHCONV=1

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

HOST="${ADB_WIFI_HOST:-YOUR_DEVICE_IP}"
TRIES="${ADBW_TRIES:-6}"

if [ "$HOST" = "YOUR_DEVICE_IP" ]; then
    echo "[adbw] 请先设置 ADB_WIFI_HOST=<设备IP>（无线调试地址）" >&2
    exit 2
fi

ensure_dev() {
    "$ADB" start-server >/dev/null 2>&1
    local d
    d="$("$ADB" devices 2>/dev/null | awk -v h="$HOST" '$2=="device" && index($1,h":")==1 {print $1; exit}')"
    [ -n "$d" ] && { echo "$d"; return 0; }

    # mDNS 发现端口后连（可能要等几秒广播）
    local i port
    for i in $(seq 1 10); do
        port="$("$ADB" mdns services 2>/dev/null \
                | awk '/_adb-tls-connect/{print $3; exit}' | sed 's/^.*://')"
        if [ -n "$port" ]; then
            "$ADB" connect "$HOST:$port" >/dev/null 2>&1
            sleep 1
            d="$("$ADB" devices 2>/dev/null | awk -v h="$HOST" '$2=="device" && index($1,h":")==1 {print $1; exit}')"
            [ -n "$d" ] && { echo "$d"; return 0; }
        fi
        sleep 1
    done
    return 1
}

[ "${1:-}" = "dev" ] && { ensure_dev; exit $?; }

[ $# -ge 1 ] || { echo "用法: adbw.sh <adb 子命令> [参数...]" >&2; exit 2; }

for _ in $(seq 1 "$TRIES"); do
    DEV="$(ensure_dev)" || { sleep 2; continue; }
    if "$ADB" -s "$DEV" "$@"; then
        exit 0
    fi
    # 失败：多半是掉线，重连后重试
    sleep 2
done

echo "[adbw] $TRIES 次重试后仍失败: $*" >&2
exit 1
