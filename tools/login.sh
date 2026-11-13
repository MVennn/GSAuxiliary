#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# login.sh —— 用本机凭据自动登录金太阳（国信）
#
# 为什么不用 input text / 不用 UiAutomator 的 setText：
#   登录页的密码框是自绘控件，系统输入法（含 AdbIME 广播）与 adb input text
#   都进不去；只有它自己的「国信安全键盘」(resource-id=gs_keyboard) 的按键
#   （每个键的 content-desc 就是字符）点了才算数。
#   手机号框是普通 EditText，但同样只吃 keyevent（input text 无效）。
#   以上三条都是 2026-11-13 在 MuMu(Android 12 / 宿主 8.7.1) 上实测的。
#
# 凭据来源（优先级从高到低）:
#   1. 环境变量 GS_USER / GS_PASS
#   2. tools/login.local.env（已 gitignore，模板见 tools/login.example.env）
#   日志里密码只打长度，不落明文；脚本本身不含任何凭据。
#
# 用法:
#   bash tools/login.sh                 登录（本来已登录则直接退出 0）
#   bash tools/login.sh --force         已登录也重新登
#   bash tools/login.sh --no-submit     只填手机号/密码/勾协议，不点登录（调试，不消耗错误次数）
#   bash tools/login.sh --user 138xxxx  临时覆盖账号
#
# 退出码: 0 成功（或已登录）/ 1 登录失败 / 2 环境问题 / 3 缺凭据或不支持的密码字符
# ---------------------------------------------------------------------------
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=env.sh
. "$HERE/env.sh"

# adb 是原生 Windows 程序：远端路径（/sdcard/...）不能被 MSYS 改写成 F:/...
export MSYS_NO_PATHCONV=1

FORCE=0
SUBMIT=1
ARG_USER=""
while [ $# -gt 0 ]; do
    case "$1" in
        --force) FORCE=1 ;;
        --no-submit) SUBMIT=0 ;;
        --user) ARG_USER="${2:-}"; shift ;;
        -h|--help) sed -n '1,30p' "$0"; exit 0 ;;
        *) echo "[login] 未知参数: $1" >&2; exit 2 ;;
    esac
    shift
done

# --- 凭据 ---
ENVF="$HERE/login.local.env"
# 文件里留着空的 GS_PASS= 时会把环境变量覆盖成空，所以先存一份再 source。
ENV_GS_USER="${GS_USER:-}"
ENV_GS_PASS="${GS_PASS:-}"
if [ -f "$ENVF" ]; then
    # shellcheck disable=SC1090
    set -a; . "$ENVF"; set +a
fi
[ -n "$ENV_GS_USER" ] && GS_USER="$ENV_GS_USER"
[ -n "$ENV_GS_PASS" ] && GS_PASS="$ENV_GS_PASS"
USER_N="${ARG_USER:-${GS_USER:-}}"
PASS_W="${GS_PASS:-}"
if [ -z "$USER_N" ] || [ -z "$PASS_W" ]; then
    cat >&2 <<'EOF'
[login] 缺凭据。两种给法（任选一种，都不会进仓库）：
  1) 建本地文件（推荐，一次填好以后一直用）：
       cp tools/login.example.env tools/login.local.env
       然后编辑 tools/login.local.env 里的 GS_USER / GS_PASS
  2) 临时用环境变量：
       GS_USER=你的手机号 GS_PASS=你的密码 bash tools/login.sh
EOF
    exit 3
fi
echo "[login] 账号 = $USER_N / 密码 = $(printf '%*s' ${#PASS_W} '' | tr ' ' '*')（${#PASS_W} 位）"

# --- 设备（没开模拟器就自己拉起来）---
find_mumu() {
    local p
    for p in "${MUMU_HOME:-}/nx_main/MuMuManager.exe" \
             "/d/Program Files/Netease/MuMu/nx_main/MuMuManager.exe" \
             "/e/Program Files/Netease/MuMu/nx_main/MuMuManager.exe" \
             "/c/Program Files/Netease/MuMu/nx_main/MuMuManager.exe"; do
        [ -x "$p" ] && { printf '%s\n' "$p"; return 0; }
    done
    # 兜底：从卸载表里读安装目录（MuMu 的 DisplayName 是「MuMu模拟器」）
    p="$(powershell -NoProfile -Command \
        "(Get-ChildItem 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall','HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall' -EA 0 | ForEach-Object { \$q=Get-ItemProperty \$_.PSPath -EA 0; if (\$q.DisplayName -like 'MuMu*') { \$q.InstallLocation } }) | Select-Object -First 1" \
        2>/dev/null | tr -d '\r')"
    [ -n "$p" ] && [ -x "$p/nx_main/MuMuManager.exe" ] && { printf '%s\n' "$p/nx_main/MuMuManager.exe"; return 0; }
    return 1
}

DEV="$(resolve_device 6 || true)"
if [ -z "$DEV" ]; then
    MM="$(find_mumu)" || { echo "[login] 没找到设备，也没找到 MuMuManager.exe（设 MUMU_HOME 指过去）" >&2; exit 2; }
    IDX="${MUMU_INDEX:-1}"
    echo "[login] 模拟器没开，启动 MuMu 实例 $IDX ..."
    "$MM" control -v "$IDX" launch >/dev/null 2>&1
    DEV="$(resolve_device 180)" || { echo "[login] MuMu 起了但设备连不上（端口 ${MUMU_ADB_PORT:-16416}）" >&2; exit 2; }
fi
echo "[login] 设备 = $DEV"
if ! ADB_PKG="$("$ADB" -s "$DEV" shell pm list packages "${TARGET_PKG:-com.guosen.android}" 2>/dev/null | tr -d '\r')" || [ -z "$ADB_PKG" ]; then
    echo "[login] 设备上没有 ${TARGET_PKG:-com.guosen.android}" >&2; exit 2
fi
ADB_S() { "$ADB" -s "$DEV" "$@"; }

WORK="$REPO_DIR/.work"
mkdir -p "$WORK"
XML_LOCAL="$WORK/login_ui.xml"
XML_W="$(winpath "$XML_LOCAL")"          # adb pull 的目标（adb 是原生程序）
XML_PY="$XML_W"                          # python 也是原生程序，同样要 Windows 路径
PY="${PYTHON:-python}"
export PYTHONIOENCODING=utf-8
# env.sh 已经避开 WindowsApps 的 Store 别名 stub，这里再兜一道：读不到控件树就没法填表。
if ! "$PY" -c 'import sys' >/dev/null 2>&1; then
    echo "[login] python 不可用（$PY = $PY）；WindowsApps 下的 python3 是商店别名 stub" >&2
    exit 3
fi
UIXML="$(winpath "$HERE/uixml.py")"
PKG="${TARGET_PKG}"

q() { "$PY" "$UIXML" "$XML_PY" "$@" 2>/dev/null; }

# --- 基础动作 ---
dump_ui() {
    local i
    for i in 1 2 3; do
        ADB_S shell 'rm -f /sdcard/ui.xml; uiautomator dump /sdcard/ui.xml' >/dev/null 2>&1
        ADB_S pull /sdcard/ui.xml "$XML_W" >/dev/null 2>&1
        [ -s "$XML_LOCAL" ] && return 0
        sleep 0.6
    done
    return 1
}
tap_xy() { ADB_S shell "input tap $1" >/dev/null 2>&1; }
tap_rid() {
    local xy
    xy="$(q --center "$1")" || return 1
    tap_xy "$xy"
}
key_ev() { ADB_S shell "input keyevent $*" >/dev/null 2>&1; }
top_act() {
    # 行形如：  topResumedActivity=ActivityRecord{27d907e u0 com.guosen.android/...LoginNewAct t619}
    # awk 取第 3 个空白分隔字段就是组件名（{hash 和 } 都黏在相邻字段上）。
    ADB_S shell 'dumpsys activity activities | grep -m1 topResumedActivity' 2>/dev/null \
        | tr -d '\r' | awk '{print $3}'
}
val() { q --value "$1"; }

# --- 安全键盘 ---
# 键盘弹出的定位、逐字符点击、切页（数字/字母/大小写）全部交给
# tools/gskeys.py：它在 python 里解析 XML 并比较中文 content-desc，
# 不经过 bash/awk，避免编码与 locale 造成的字符静默丢失。

# --- 弹窗处理：返回 0 处理了一个弹窗，1 没有弹窗，2 是终态错误 ---
handle_dialog() {
    local body
    body="$(val com.guosen.android:id/tv_dialog_content)"
    [ -n "$body" ] || return 1
    case "$body" in
        *运行环境存在风险*|*登录信息已失效*|*请重新登录*|*已过期*)
            tap_rid com.guosen.android:id/btn_dialog_right; sleep 0.8; return 0 ;;
        *隐私*|*服务协议*)
            tap_rid com.guosen.android:id/btn_dialog_left; sleep 0.8; return 0 ;;
        *密码错误*|*账号或密码*|*锁定*|*冻结*)
            DIALOG_ERR="$body"; return 2 ;;
    esac
    return 1
}

# --- 1. 拉起 App ---
echo "[login] 启动 $PKG ..."
ADB_S shell "monkey -p $PKG -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 3

# --- 2. 等登录页（顺手点掉风险/失效弹窗）---
READY=0
DIALOG_ERR=""
for _ in $(seq 1 12); do
    dump_ui || { sleep 1; continue; }
    handle_dialog
    case $? in
        2) echo "[login] 登录失败：$DIALOG_ERR" >&2; exit 1 ;;
        0) continue ;;
    esac
    title="$(val com.guosen.android:id/tvTitle)"
    case "$title" in
        密码登录|短信验证码登录) READY=1; break ;;
    esac
    act="$(top_act)"
    case "$act" in
        *LoginNewAct*) READY=1; break ;;
        *MainAct*|*com.guosen.quotation.*)
            if [ "$FORCE" = 0 ]; then
                echo "[login] 已经是登录态（$act），不需要操作"
                exit 0
            fi ;;
    esac
    sleep 1
done
[ "$READY" = 1 ] || { echo "[login] 等不到登录页（栈顶: $(top_act)）" >&2; exit 2; }

# --- 3. 切到密码登录 ---
if [ "$(val com.guosen.android:id/tvTitle)" != "密码登录" ]; then
    echo "[login] 切到密码登录"
    tap_rid com.guosen.android:id/tvChangeToPsw || { echo "[login] 找不到「用密码登录」" >&2; exit 2; }
    sleep 1.2
fi

# --- 4. 手机号（keyevent 逐位，input text 无效）---
echo "[login] 填手机号"
tap_rid com.guosen.android:id/etPhoneNum || { echo "[login] 找不到手机号框" >&2; exit 2; }
sleep 0.6
key_ev 123                                    # MOVE_END
ADB_S shell "input keyevent $(printf '67 %.0s' $(seq 1 18))" >/dev/null 2>&1
sleep 0.4
num_keys=""
i=0
while [ $i -lt ${#USER_N} ]; do
    c="${USER_N:$i:1}"
    case "$c" in
        [0-9]) num_keys="$num_keys $((c + 7))" ;;
        *) echo "[login] 账号里有非数字字符: $c" >&2; exit 3 ;;
    esac
    i=$((i + 1))
done
ADB_S shell "input keyevent$num_keys" >/dev/null 2>&1
sleep 0.6
dump_ui
got="$(val com.guosen.android:id/etPhoneNum | tr -d ' \r\n')"
if [ "$got" != "$USER_N" ]; then
    echo "[login] 手机号没填对：期望 $USER_N，实际 '$got'" >&2
    exit 2
fi
echo "[login] 手机号 OK"

# --- 5. 密码（点国信安全键盘；逐字符由 gskeys.py 敲）---
# 为什么不用原来的 press_char：它用 bash 的 awk 比较中文 content-desc
# （切换数字键盘 / 大小写切换），实测匹配不上，字符被静默丢弃，密码不完整；
# 而 App 对空/短密码不提示也不提交 —— 表现就是「点登录毫无反应」。
echo "[login] 填密码（安全键盘）"
tap_rid com.guosen.android:id/etPwd || { echo "[login] 找不到密码框" >&2; exit 2; }
sleep 1.2
# 三个路径参数都要转成 Windows 形式：python 是原生程序，吃不掉 /f/... 的 MSYS 路径。
"$PY" -u "$(winpath "$HERE/gskeys.py")" --adb "$(winpath "$ADB")" --serial "$DEV" \
    --env-file "$(winpath "$ENVF")" --var GS_PASS
rc=$?
case $rc in
    0) ;;
    3) echo "[login] 密码含安全键盘不支持的字符（只处理 0-9 A-Z a-z），请手动登录一次" >&2; exit 3 ;;
    *) echo "[login] 安全键盘输入失败（rc=$rc）" >&2; exit 2 ;;
esac
echo "[login] 密码已填（${#PASS_W} 位）"

# --- 6. 勾隐私协议 ---
dump_ui
if [ "$(q --checked com.guosen.android:id/cbPrivacy)" != "true" ]; then
    echo "[login] 勾选隐私协议"
    rx="$(q --center com.guosen.android:id/cbPrivacy)" && tap_xy "$rx"
    sleep 0.4
fi

if [ "$SUBMIT" = 0 ]; then
    echo "[login] --no-submit：已填好，不点登录"
    exit 0
fi

# --- 7. 点登录 ---
echo "[login] 点登录"
tap_rid com.guosen.android:id/btnLogin || { echo "[login] 找不到登录按钮" >&2; exit 2; }

# --- 8. 等结果 ---
for _ in $(seq 1 30); do
    sleep 1.2
    dump_ui || continue
    handle_dialog
    case $? in
        2) echo "[login] 登录失败：$DIALOG_ERR" >&2; exit 1 ;;
        0) continue ;;
    esac
    if val com.guosen.android:id/tv_dialog_content | grep -q 验证码; then
        echo "[login] 需要图形/短信验证码，请手动完成后按回车"
        read -r _
    fi
    act="$(top_act)"
    case "$act" in
        *LoginNewAct*) continue ;;
        *"$PKG"/*)
            echo "[login] 登录成功，已进入 $act"
            exit 0 ;;
    esac
done

echo "[login] 30s 内没看到登录成功（栈顶: $(top_act)）" >&2
exit 1
