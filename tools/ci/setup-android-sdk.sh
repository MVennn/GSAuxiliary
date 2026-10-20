#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# setup-android-sdk.sh —— 在一台「干净的」Linux runner 上装好构建要用的 Android SDK
#
# 为什么需要它：
#   GitHub Actions 有 `android-actions/setup-android` 这个现成的 action，
#   而 **GitLab 的共享 runner 什么都不预装** —— 没有 cmdline-tools、没有 sdkmanager、
#   没有 build-tools。于是流水线里的 `./gradlew` 第一句就会因为找不到 SDK 而红。
#   与其依赖某个第三方 action（它自己也要下载 SDK，还要信它的版本），
#   不如把「装 SDK」写成仓库里的脚本：版本钉死、行为可读、出错时日志在我们自己手里。
#
# 装什么：
#   1. command-line tools（自带 sdkmanager）—— 从 dl.google.com 下载
#   2. platform-tools（adb 等，冒烟用）
#   3. build-tools;35.0.0（AGP 8.10.1 的默认版本）
#   4. compileSdk 37 —— 交给 tools/ci/setup-sdk37.sh 装并归一化
#      （官方仓库里它叫 platforms;android-37.2，AGP 8.10.1 认不出这个名字，
#        来龙去脉见 docs/BUILD.md §4.1）
#
# 幂等：已装过的部分会跳过，重复跑没有副作用。
#
# 环境变量：
#   ANDROID_SDK_ROOT / ANDROID_HOME   SDK 根目录（不给就用 $CI_PROJECT_DIR/.android-sdk）
#   GS_CMDLINE_TOOLS_URL              command-line tools 的下载地址（覆盖默认值）
#   GS_SDK_BUILD_TOOLS                build-tools 版本（默认 35.0.0）
#
# 用法： bash tools/ci/setup-android-sdk.sh
# ---------------------------------------------------------------------------
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

BUILD_TOOLS="${GS_SDK_BUILD_TOOLS:-35.0.0}"

# command-line tools 的几个已知版本号，按新到旧试。
# 「_latest」是那个版本的最新补丁，不是滚动的别名 —— 钉死版本号才不会某天
# 突然拿到一个行为变了的新版。
CMDLINE_TOOLS_URLS=(
  "${GS_CMDLINE_TOOLS_URL:-}"
  "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
  "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  "https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip"
)

log()  { printf '  %s\n' "$*"; }
die()  { printf '[X] %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. SDK 根目录
# ---------------------------------------------------------------------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
    if [ -n "${CI_PROJECT_DIR:-}" ]; then
        SDK="$CI_PROJECT_DIR/.android-sdk"
    else
        SDK="$HOME/Android/Sdk"
    fi
fi
mkdir -p "$SDK"
SDK="$(cd "$SDK" && pwd)"

echo "=================================================="
echo " 准备 Android SDK"
echo "=================================================="
echo " SDK 根目录 : $SDK"

# ---------------------------------------------------------------------------
# 2. 依赖：curl + unzip
#
# runner 镜像不一定带 unzip。装不了就明说，别让后面的失败长得像 SDK 的问题。
# ---------------------------------------------------------------------------
need=""
command -v curl >/dev/null 2>&1 || need="$need curl"
command -v unzip >/dev/null 2>&1 || need="$need unzip"
if [ -n "$need" ]; then
    echo ""
    echo "[1/4] 装依赖:$need"
    if command -v apt-get >/dev/null 2>&1; then
        apt-get update -qq
        # shellcheck disable=SC2086
        apt-get install -y -qq $need > /dev/null
        echo "      [OK] 装好了"
    else
        die "缺$need，且这台机器没有 apt-get —— 请换用带这些工具的镜像"
    fi
else
    echo " [i] curl / unzip 都有"
fi

# ---------------------------------------------------------------------------
# 3. command-line tools
# ---------------------------------------------------------------------------
SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKMGR" ]; then
    echo ""
    echo "[2/4] command-line tools 已存在，跳过"
else
    echo ""
    echo "[2/4] 下载 command-line tools"
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    ok=0
    for url in "${CMDLINE_TOOLS_URLS[@]}"; do
        [ -n "$url" ] || continue
        echo "      试 $url"
        if curl -fsSL --retry 3 --retry-delay 3 -o "$TMP/tools.zip" "$url"; then
            ok=1
            break
        fi
        echo "      [X] 这个地址不行，换下一个"
    done
    [ "$ok" = 1 ] || die "所有下载地址都失败 —— 检查 runner 到 dl.google.com 的网络"

    rm -rf "$SDK/cmdline-tools"
    mkdir -p "$SDK/cmdline-tools"
    unzip -q "$TMP/tools.zip" -d "$TMP/x"
    # 压缩包里的顶层目录就叫 cmdline-tools/，要挪到 cmdline-tools/latest/
    mv "$TMP/x/cmdline-tools" "$SDK/cmdline-tools/latest"
    chmod +x "$SDK/cmdline-tools/latest/bin/"* 2>/dev/null || true
    [ -x "$SDKMGR" ] || die "装完了但 sdkmanager 还是不可执行 —— 压缩包结构变了？"
    echo "      [OK] $SDKMGR"
fi

# ---------------------------------------------------------------------------
# 4. platform-tools + build-tools
# ---------------------------------------------------------------------------
echo ""
echo "[3/4] 装 platform-tools 与 build-tools;$BUILD_TOOLS"
# yes 会被提前关掉管道而吃 SIGPIPE（exit 141），所以临时关掉 pipefail
set +o pipefail
yes 2>/dev/null | "$SDKMGR" --sdk_root="$SDK" --install \
        "platform-tools" "build-tools;$BUILD_TOOLS" 2>&1 | sed 's/^/      /'
rc="${PIPESTATUS[1]}"
set -o pipefail
[ "$rc" -eq 0 ] || die "装 platform-tools / build-tools 失败（exit $rc）"
log "[OK] platform-tools 与 build-tools;$BUILD_TOOLS"

# ---------------------------------------------------------------------------
# 5. compileSdk 37（装 + 归一化）
# ---------------------------------------------------------------------------
echo ""
echo "[4/4] 准备 compileSdk 37"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_HOME="$SDK"
bash "$HERE/setup-sdk37.sh"

echo ""
echo "=================================================="
echo " SDK 准备完毕"
echo "=================================================="
