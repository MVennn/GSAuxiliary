#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# dev.sh —— 无线调试设备保活 / 自动重连（开发期工具）
#
# 为什么需要它：
#   1. **adb server 会被回收**（沙箱 / 会话结束），server 一重启，无线连接就断，
#      必须重新 connect。所以每条命令前都得跑一次本脚本。
#   2. **无线调试端口会变**（关掉再开无线调试，或系统重启，端口就换一个）。
#      写死 10.0.0.26:38131 迟早会失效 —— 这里用 mDNS 自动发现。
#   3. **mDNS 会额外注册一条 `adb-XXXX._adb-tls-connect._tcp` 虚拟 serial**，
#      它常常排在第一，导致 tools/*.sh 里的 `wait_device` 选中一个不可用的名字。
#      所以本脚本一律连接 **ip:port** 形式，不返回 mDNS 名字。
#
# 用法:
#   DEV="$(bash tools/dev.sh)" || exit 1        # 拿到可用 serial
#   adb -s "$DEV" shell ...
#
# 环境变量:
#   ADB_WIFI_HOST   设备 IP，默认 10.0.0.26
#   ADB_MDNS_PORT   已知端口时直接指定，跳过 mDNS 发现
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

HOST="${ADB_WIFI_HOST:-10.0.0.26}"

[ -n "${ADB:-}" ] || { echo "[dev] 没找到 adb" >&2; exit 1; }

"$ADB" start-server >/dev/null 2>&1

# --- 1. 已经连着就直接用 ---
pick_ip_serial() {
    "$ADB" devices 2>/dev/null | awk -v h="$HOST" \
        '$2 == "device" && index($1, h ":") == 1 { print $1; exit }'
}

DEV="$(pick_ip_serial)"

# --- 2. 已知端口 ---
if [ -z "$DEV" ] && [ -n "${ADB_MDNS_PORT:-}" ]; then
    "$ADB" connect "$HOST:$ADB_MDNS_PORT" >/dev/null 2>&1
    sleep 1
    DEV="$(pick_ip_serial)"
fi

# --- 3. mDNS 发现端口（可能要等几秒才广播出来）---
if [ -z "$DEV" ]; then
    for _ in $(seq 1 12); do
        PORT="$("$ADB" mdns services 2>/dev/null \
                | awk '/_adb-tls-connect/{print $3; exit}' \
                | sed 's/^.*://')"
        if [ -n "$PORT" ]; then
            "$ADB" connect "$HOST:$PORT" >/dev/null 2>&1
            sleep 1
            DEV="$(pick_ip_serial)"
            [ -n "$DEV" ] && break
        fi
        sleep 1
    done
fi

if [ -z "$DEV" ]; then
    echo "[dev] 连不上 $HOST（无线调试关了 / 不在同一网段 / 端口变了且 mDNS 没广播）" >&2
    exit 1
fi

echo "$DEV"
