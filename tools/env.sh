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
        for c in python3 python py; do
            command -v "$c" >/dev/null 2>&1 && { PYTHON="$(command -v "$c")"; break; }
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
        dev="$("$ADB" devices 2>/dev/null | awk '$2 == "device" && $1 ~ /:[0-9]+$/ { print $1; exit }')"
        [ -n "$dev" ] || dev="$("$ADB" devices 2>/dev/null | awk '$2 == "device" { print $1; exit }')"
        if [ -n "$dev" ]; then
            mkdir -p "$(dirname "$cache")" 2>/dev/null
            echo "$dev" > "$cache" 2>/dev/null
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
