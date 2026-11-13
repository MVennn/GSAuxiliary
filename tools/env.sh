#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# env.sh —— 公共环境探测，被 tools/ 下其它脚本 source
#
# 自动定位 Android SDK / adb / python / JAVA_HOME，避免把本机绝对路径写死在脚本里。
# 需要时可用环境变量覆盖：
#     ANDROID_SDK   Android SDK 根目录
#     ADB           adb 可执行文件全路径
#     PYTHON        python 可执行文件全路径
#     JAVA_HOME     JDK 根目录（探针构建需要）
#     FFMPEG        ffmpeg 全路径（取色脚本用，可选）
# ---------------------------------------------------------------------------

# 找到本文件所在目录（tools/）
TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$TOOLS_DIR/.." && pwd)"

# --- Windows 路径 -> MSYS 路径（adb / python 是原生程序，不认 /c/...）---
winpath() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        echo "$1" | sed -E 's|^/([a-zA-Z])/|\1:/|'
    fi
}

# --- Android SDK ---
detect_sdk() {
    local cands=(
        # 先看仓内 SDK：tools/ci/setup-android-sdk.sh 会把 emulator / platform-tools
        # 装到这里，系统里的 Android/Sdk 可能根本不存在（本机就是这种情况）。
        "${REPO_DIR:-}/.work/android-sdk"
        "${ANDROID_SDK:-}" "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"
        "${LOCALAPPDATA:-}/Android/Sdk"
        "${HOME:-}/AppData/Local/Android/Sdk"
        "/c/Users/${USERNAME:-}/AppData/Local/Android/Sdk"
        "${HOME:-}/Library/Android/sdk"
        "${HOME:-}/Android/Sdk"
    )
    local p
    for p in "${cands[@]}"; do
        [ -n "$p" ] || continue
        if [ -x "$p/platform-tools/adb" ] || [ -x "$p/platform-tools/adb.exe" ]; then
            echo "$p"; return 0
        fi
    done
    return 1
}

SDK="$(detect_sdk || true)"

# --- adb ---
if [ -z "${ADB:-}" ]; then
    if [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb.exe" ]; then
        ADB="$SDK/platform-tools/adb.exe"
    elif [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb" ]; then
        ADB="$SDK/platform-tools/adb"
    elif command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
    fi
fi

# --- python ---
if [ -z "${PYTHON:-}" ]; then
    # 优先用 WorkBuddy 托管的解释器（版本目录名可能变，所以用 glob 而不是写死版本号）。
    # 注意 `.workbuddy` 与 `.workbuddy-ai` 是**两套并存的目录**：
    #   · ~/.workbuddy/binaries      —— 现行托管位置
    #   · ~/.workbuddy-ai/binaries   —— 早期版本的托管位置，本机仍在用，保留作兜底
    for p in \
        "${HOME:-}"/.workbuddy/binaries/python/versions/*/python.exe \
        "${HOME:-}"/.workbuddy-ai/binaries/python/versions/*/python.exe
    do
        [ -x "$p" ] && { PYTHON="$p"; break; }
    done
    if [ -z "${PYTHON:-}" ]; then
        # 别直接用 command -v python3：本机 WindowsApps 下那个是 Microsoft Store 的
        # 执行别名 stub，跑起来只会 exit 49 并提示去商店安装（真解释器在
        # LocalApp\Programs\Python\Python3xx）。所以既排除 WindowsApps，又实跑一次验证。
        for c in python3 python py; do
            p="$(command -v "$c" 2>/dev/null)" || continue
            case "$p" in */WindowsApps/*) continue ;; esac
            "$p" -c 'import sys' >/dev/null 2>&1 || continue
            PYTHON="$p"; break
        done
    fi
fi

# --- ffmpeg（仅取色脚本需要，找不到就留空由脚本自己报错）---
if [ -z "${FFMPEG:-}" ]; then
    for p in \
        "$(command -v ffmpeg 2>/dev/null)" \
        "/e/Tool/PracticalTool/ffmpeg/bin/ffmpeg.exe" \
        "/c/ffmpeg/bin/ffmpeg.exe" \
        "/c/Program Files/ffmpeg/bin/ffmpeg.exe"
    do
        [ -n "$p" ] && [ -x "$p" ] && { FFMPEG="$p"; break; }
    done
fi

# --- JAVA_HOME（探针构建需要 javac/d8/jar/keytool）---
if [ -z "${JAVA_HOME:-}" ]; then
    for p in \
        "/c/Program Files/Java"/* \
        "/c/Program Files/Eclipse Adoptium"/* \
        "${HOME:-}/.jdks"/* \
        "${HOME:-}/.sdkman/candidates/java/current" \
        "/usr/lib/jvm"/*
    do
        [ -d "$p" ] || continue
        # 验证 bin/javac(.exe) 存在
        if [ -x "$p/bin/javac.exe" ] || [ -x "$p/bin/javac" ]; then
            JAVA_HOME="$p"; break
        fi
    done
fi

# --- 目标 App 包名（可用 TARGET_PKG 覆盖）---
: "${TARGET_PKG:=com.guosen.android}"
# --- 模块包名 ---
: "${MODULE_PKG:=io.github.MVennn.GSAuxiliary}"

export TOOLS_DIR REPO_DIR SDK
export ADB="${ADB:-}" PYTHON="${PYTHON:-}" FFMPEG="${FFMPEG:-}"
export JAVA_HOME="${JAVA_HOME:-}"
export TARGET_PKG MODULE_PKG

# ---------------------------------------------------------------------------
# 调试期方向锁定（tools/rotate.sh 的「持续生效」部分）
#
# rotate.sh on 会把「原值 + 要锁的方向」写进 `.work/rotation.bak`。只要这个
# 文件还在，每次拿到设备 serial 就把锁重新按一遍 —— 无线调试重新连上时，
# 有些系统会把「自动旋转」再打开，只在 on 那一刻设一次会被它盖掉。
# 文件不在时直接返回（不产生任何 adb 调用）。
# ---------------------------------------------------------------------------
apply_rotation_lock() {
    local dev="${1:-}" bak lock
    [ -n "$dev" ] && [ -n "${ADB:-}" ] || return 0
    bak="${REPO_DIR:-.}/.work/rotation.bak"
    [ -f "$bak" ] || return 0
    lock="$(sed -n 3p "$bak" 2>/dev/null | tr -d '\r\n')"
    case "$lock" in ''|null) lock=0 ;; esac
    # 一条 shell 命令设两个值：adbw.sh 每条命令都会走到这里，别拆成两次往返
    "$ADB" -s "$dev" shell \
        "settings put system accelerometer_rotation 0; settings put system user_rotation $lock" \
        >/dev/null 2>&1 || true
}

# ---------------------------------------------------------------------------
# 等待设备（无线调试下 adb server 冷启动后设备可能还没被发现）
# 用法: DEV="$(wait_device 60)" || exit 1
#
# adb server 被回收后，无线设备**不会**自动回来，得显式 connect；
# 而每次靠 mDNS 重新发现又慢又不稳。所以这里把上次成功的 serial 缓存到
# `.work/wifi_serial`，下次先直接 connect 它 —— 命中就基本是秒回。
# （无线端口变了的情况由后面的 mDNS 兜底，缓存因此始终会被刷新。）
# ---------------------------------------------------------------------------
wait_device() {
    local timeout="${1:-60}" i dev cache last
    [ -n "${ADB:-}" ] || { echo "[env] 没找到 adb" >&2; return 1; }

    "$ADB" start-server >/dev/null 2>&1

    cache="${REPO_DIR:-.}/.work/wifi_serial"
    if [ -f "$cache" ]; then
        last="$(tr -d '\r\n' < "$cache" 2>/dev/null)"
        [ -n "$last" ] && "$ADB" connect "$last" >/dev/null 2>&1
    fi

    for ((i = 0; i < timeout; i++)); do
        # 优先 ip:port 形式的 serial，mDNS 那条虚拟名字只当兜底
        # 注意：Windows 的 adb.exe 输出是 CRLF，awk 的 $2 会变成 "device\r"，
        # 必须先 tr -d '\r'，否则永远匹配不到（本机实测：不 tr 就 0 设备）。
        dev="$("$ADB" devices 2>/dev/null | tr -d '\r' | awk '$2 == "device" && $1 ~ /:[0-9]+$/ { print $1; exit }')"
        [ -n "$dev" ] || dev="$("$ADB" devices 2>/dev/null | tr -d '\r' | awk '$2 == "device" { print $1; exit }')"
        if [ -n "$dev" ]; then
            mkdir -p "$(dirname "$cache")" 2>/dev/null
            echo "$dev" > "$cache" 2>/dev/null
            apply_rotation_lock "$dev"
            echo "$dev"; return 0
        fi

        # 每 10 秒尝试一次显式 connect（针对已经配对过的无线设备）
        if [ -n "${ADB_WIFI_TARGET:-}" ] && (( i % 10 == 9 )); then
            "$ADB" connect "$ADB_WIFI_TARGET" >/dev/null 2>&1
        fi
        sleep 1
    done
    echo "[env] ${timeout}s 内没等到设备" >&2
    return 1
}

# ---------------------------------------------------------------------------
# 设备解析：MuMu 优先（drive.sh / ui.sh / verify.py 都该用它，不要用 wait_device）
#
# 为什么不能再用 wait_device 的「第一条 device」：
#   本机长期有两条 adb 通路同时在线 ——
#     · 127.0.0.1:16416  MuMu 实例 1：Android 12，abilist 含 arm64-v8a 且有
#       libhoudini ARM 转译，Magisk(/system/xbin/magisk) + Zygisk LSPosed
#       (/data/adb/modules/zygisk_lsposed) + 宿主金太阳 8.7.1 均已装 ——
#       这是唯一能真正跑模块的环境
#     · emulator-5554    SDK AVD：Android 13，纯 x86_64 无 ARM 转译，
#       ARM-only 的宿主与模块一律 INSTALL_FAILED_NO_MATCHING_ABIS res=-113
#       （见 docs/REVERSE-ENGINEERING.md:31、app/build.gradle.kts:93-101）
#   顺序取设备会随机落到 AVD 上，脚本看着跑完其实什么都没测到。
#
# 优先级：DEVICE（精确 serial）> MuMu(MUMU_ADB_PORT，默认 16416) > 无线 > 任意
# ---------------------------------------------------------------------------
resolve_device() {
    local p="${MUMU_ADB_PORT:-16416}" dev
    [ -n "${ADB:-}" ] || { echo "[env] 没找到 adb" >&2; return 1; }

    if [ -n "${DEVICE:-}" ]; then
        case "$DEVICE" in
            *:*) "$ADB" connect "$DEVICE" >/dev/null 2>&1 || true ;;
        esac
        echo "$DEVICE"; return 0
    fi

    "$ADB" start-server >/dev/null 2>&1
    "$ADB" connect "127.0.0.1:$p" >/dev/null 2>&1
    dev="$("$ADB" devices 2>/dev/null | tr -d '\r' | awk -v s="127.0.0.1:$p" '$1==s && $2=="device" {print $1; exit}')"
    if [ -n "$dev" ]; then
        apply_rotation_lock "$dev"
        echo "$dev"; return 0
    fi
    wait_device "${1:-60}"
}

