#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# rotate.sh —— 调试期方向锁定 / 还原（开发期工具）
#
# 无线调试每次连上时，部分系统会把「自动旋转」重新打开 —— 手机一横，
# 截图方向、dump 出来的坐标全跟着变，自动化脚本就会点错位置。
# 这个脚本把自动旋转关掉并锁到指定方向，收工再还原成原来的状态。
#
# 用法:
#   bash tools/rotate.sh            # 锁定：自动旋转关 + 竖屏（首次会把原值备份下来）
#   bash tools/rotate.sh off        # 还原：优先用备份里的原值，没备份则自动旋转开 + 0°
#   bash tools/rotate.sh status     # 只看当前值（不动任何东西）
#
# 持续生效: `.work/rotation.bak` 存在期间，env.sh 的 `wait_device` /
#   `adbw.sh` / `dev.sh` 每次拿到设备都会把锁重新按一遍 —— 重连时系统把
#   自动旋转打开也会被盖回去。备份文件同时就是「锁生效中」的标记，`off` 删它。
#
# 环境变量:
#   ROTATION_LOCK_USER   锁定时的 user_rotation，默认 0
#                        （0=0° 竖屏，1=90°，2=180°，3=270°）
#   ROTATION_OFF_AUTO    没有备份时还原的 accelerometer_rotation，默认 1（自动旋转开）；
#                        原状态是「锁定」就用 ROTATION_OFF_AUTO=0
#   ROTATION_OFF_USER    没有备份时还原的 user_rotation，默认 0
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh" >/dev/null 2>&1

LOCK_USER="${ROTATION_LOCK_USER:-0}"
OFF_AUTO="${ROTATION_OFF_AUTO:-1}"
OFF_USER="${ROTATION_OFF_USER:-0}"
BAK="$REPO_DIR/.work/rotation.bak"

for pair in "ROTATION_LOCK_USER:$LOCK_USER" "ROTATION_OFF_AUTO:$OFF_AUTO" "ROTATION_OFF_USER:$OFF_USER"; do
    v="${pair##*:}"
    case "$v" in 0|1|2|3) ;; *)
        echo "[rotate] ${pair%%:*} 必须是 0..3（0=竖屏，1=90°，2=180°，3=270°），当前: $v" >&2
        exit 2
        ;;
    esac
done
if [ "$OFF_AUTO" != "0" ] && [ "$OFF_AUTO" != "1" ]; then
    echo "[rotate] ROTATION_OFF_AUTO 只能是 0 或 1，当前: $OFF_AUTO" >&2
    exit 2
fi

[ -n "${ADB:-}" ] || { echo "[rotate] 没找到 adb" >&2; exit 1; }

DEV="$(wait_device 40)"
[ -n "${DEV:-}" ] || { echo "[rotate] 没有可用设备（先跑 bash tools/dev.sh）" >&2; exit 1; }

sh_() { "$ADB" -s "$DEV" shell "$@"; }

get_auto() { sh_ settings get system accelerometer_rotation 2>/dev/null | tr -d '\r'; }
get_user() { sh_ settings get system user_rotation 2>/dev/null | tr -d '\r'; }

# accelerometer_rotation + user_rotation -> 人读
fmt() {
    case "${1:-}" in
        1) echo "自动旋转开（当前方向 ${2:-?}）" ;;
        0) case "${2:-}" in
               0) echo "锁定竖屏" ;;
               1) echo "锁定横屏 90°" ;;
               2) echo "锁定倒屏 180°" ;;
               3) echo "锁定横屏 270°" ;;
               *) echo "锁定方向 ${2:-未知}" ;;
           esac ;;
        ''|null) echo "未知" ;;
        *) echo "accelerometer_rotation=${1:-?}" ;;
    esac
}

# 备份里的第 N 行（1=原 accelerometer_rotation，2=原 user_rotation，3=要锁的方向）
bak_get() {
    [ -f "$BAK" ] || return 1
    sed -n "${1}p" "$BAK" 2>/dev/null | tr -d '\r\n'
}

case "${1:-on}" in
    on|lock|portrait)
        cur_auto="$(get_auto)"
        cur_user="$(get_user)"
        bak_auto="$(bak_get 1 || true)"
        bak_user="$(bak_get 2 || true)"
        mkdir -p "$(dirname "$BAK")"
        if [ -z "$bak_auto" ] || [ "$bak_auto" = "null" ]; then
            bak_auto="$cur_auto"; bak_user="$cur_user"
            echo "[rotate] 原值已备份: $(fmt "$bak_auto" "$bak_user")"
        else
            echo "[rotate] 沿用已有备份: $(fmt "$bak_auto" "$bak_user")"
        fi
        printf '%s\n%s\n%s\n' "$bak_auto" "$bak_user" "$LOCK_USER" > "$BAK"
        sh_ settings put system accelerometer_rotation 0 >/dev/null 2>&1
        sh_ settings put system user_rotation "$LOCK_USER" >/dev/null 2>&1
        echo "[rotate] $DEV 方向 → $(fmt "$(get_auto)" "$(get_user)")"
        echo "[rotate] 收工跑 bash tools/rotate.sh off 还原"
        ;;
    off|stop|restore|unlock)
        a="$OFF_AUTO"; u="$OFF_USER"
        v="$(bak_get 1 || true)"
        case "$v" in ''|null) ;; *) a="$v" ;; esac
        v="$(bak_get 2 || true)"
        case "$v" in ''|null) ;; *) u="$v" ;; esac
        sh_ settings put system accelerometer_rotation "$a" >/dev/null 2>&1
        sh_ settings put system user_rotation "$u" >/dev/null 2>&1
        rm -f "$BAK"
        echo "[rotate] $DEV 方向已还原 → $(fmt "$(get_auto)" "$(get_user)")"
        ;;
    status)
        echo "[rotate] $DEV 当前方向: $(fmt "$(get_auto)" "$(get_user)")"
        if [ -f "$BAK" ]; then
            echo "[rotate] 锁生效中，原值: $(fmt "$(bak_get 1)" "$(bak_get 2)")"
        else
            echo "[rotate] 没有生效中的锁（.work/rotation.bak 不存在）"
        fi
        ;;
    *)
        echo "用法: bash tools/rotate.sh [on|off|status]" >&2
        exit 2
        ;;
esac
