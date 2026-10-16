#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# stayon.sh —— 调试期息屏超时（开发期工具）
#
# 无线调试时手机动不动就息屏：点不动、截图全黑、`dump` 拿到的还是上一帧。
# 这个脚本把息屏超时拉长，收工再还原，免得每次手动敲
# `settings put system screen_off_timeout ...`。
#
# 用法:
#   bash tools/stayon.sh            # 息屏超时 → 10 分钟（首次会把原值备份下来）
#   bash tools/stayon.sh off        # 还原：优先用备份里的原值，没备份则 1 分钟
#   bash tools/stayon.sh status     # 只看当前值（不动任何东西）
#
# 备份文件: `.work/screen_timeout.bak`（纯暂存区，删了也不影响；
#          备份丢了的时候 `off` 一律还原成 1 分钟）
#
# 环境变量:
#   SCREEN_TIMEOUT_ON    调试期超时，默认 600000（10 分钟）
#   SCREEN_TIMEOUT_OFF   无备份时的还原值，默认 60000（1 分钟）
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh" >/dev/null 2>&1

ON_MS="${SCREEN_TIMEOUT_ON:-600000}"
OFF_MS="${SCREEN_TIMEOUT_OFF:-60000}"
BAK="$REPO_DIR/.work/screen_timeout.bak"

[ -n "${ADB:-}" ] || { echo "[stayon] 没找到 adb" >&2; exit 1; }

# 选设备：env.sh 的 wait_device 里有 serial 缓存快速路径，命中基本秒回；
# 冷启动 + mDNS 发现最多等 40 秒。
DEV="$(wait_device 40)"
[ -n "${DEV:-}" ] || { echo "[stayon] 没有可用设备（先跑 bash tools/dev.sh）" >&2; exit 1; }

sh_() { "$ADB" -s "$DEV" shell "$@"; }

get_timeout() { sh_ settings get system screen_off_timeout 2>/dev/null | tr -d '\r'; }

# 毫秒 -> 人读
fmt() {
    local ms="${1:-}"
    case "$ms" in ''|null) echo "${ms:-未知}"; return ;; esac
    if [ "$ms" -ge 60000 ] 2>/dev/null; then
        echo "$((ms / 60000)) 分钟"
    else
        echo "$ms 毫秒"
    fi
}

case "${1:-on}" in
    on|start)
        cur="$(get_timeout)"
        mkdir -p "$(dirname "$BAK")"
        # 只在没有备份时写 —— 否则重复执行 on 会把「备份」覆盖成 10 分钟
        if [ ! -f "$BAK" ]; then
            echo "$cur" > "$BAK"
            echo "[stayon] 原值已备份: $(fmt "$cur")"
        fi
        sh_ settings put system screen_off_timeout "$ON_MS" >/dev/null 2>&1
        echo "[stayon] $DEV 息屏超时 → $(fmt "$(get_timeout)")"
        echo "[stayon] 收工跑 bash tools/stayon.sh off 还原"
        ;;
    off|stop|restore)
        tgt="$OFF_MS"
        if [ -f "$BAK" ]; then
            v="$(tr -d '\r\n' < "$BAK")"
            case "$v" in ''|null) ;; *) tgt="$v" ;; esac
        fi
        sh_ settings put system screen_off_timeout "$tgt" >/dev/null 2>&1
        rm -f "$BAK"
        echo "[stayon] $DEV 息屏超时已还原 → $(fmt "$(get_timeout)")"
        ;;
    status)
        echo "[stayon] $DEV 当前息屏超时: $(fmt "$(get_timeout)")"
        if [ -f "$BAK" ]; then
            echo "[stayon] 备份里的原值: $(fmt "$(tr -d '\r\n' < "$BAK")")"
        fi
        ;;
    *)
        echo "用法: bash tools/stayon.sh [on|off|status]" >&2
        exit 2
        ;;
esac
