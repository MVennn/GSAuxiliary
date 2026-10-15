#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# setup-sdk37.sh —— 给一台「干净的」机器装好 compileSdk 37 需要的 Android 平台
#
# 背景（完整来龙去脉见 docs/BUILD.md §4.1）：
#
#   compileSdk 37 在官方 SDK 仓库里的包名是 `platforms;android-37.2` ——
#   走的是 Android 16 之后新引入的「次要版本」命名（37.0 / 37.1 / 37.2）。
#   而 AGP 8.10.1 只认 `platforms/android-<api>` 这种老式目录 + 整数 api-level，
#   不会自己去命中 android-37.2，于是构建会报：
#
#       Failed to find target with hash string 'android-37'
#
#   本脚本做两件事：
#     1) 用 sdkmanager 装 `platforms;android-37.2`
#     2) 把它归一成 AGP 认得的样子 —— 目录改名 android-37，
#        包元数据里的 37.2 全部改成 37
#
#   **幂等**：platforms/android-37 已存在就直接退出，重复跑无副作用。
#
# 用法：
#   ANDROID_SDK_ROOT=/path/to/sdk bash tools/ci/setup-sdk37.sh
#   bash tools/ci/setup-sdk37.sh          # 未设变量时按常见路径自动探测
#
# 在 GitHub Actions 里跑时，会顺手把 SDK 路径写进 $GITHUB_ENV，
# 并补一个 local.properties（已被 .gitignore 忽略），后面步骤不用再管。
# ---------------------------------------------------------------------------
set -euo pipefail

PKG="platforms;android-37.2"   # 官方仓库里的真实包名
DST_NAME="android-37"          # AGP 8.10.1 认得的目录名

log() { printf '  %s\n' "$*"; }
die() { printf '[X] %s\n' "$*" >&2; exit 1; }

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

# ---------------------------------------------------------------------------
# 1. 找 SDK 根目录
# ---------------------------------------------------------------------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
    for c in \
        "/usr/local/lib/android/sdk" \
        "$HOME/Android/Sdk" \
        "$HOME/Library/Android/sdk" \
        "/c/Users/$USER/AppData/Local/Android/Sdk"
    do
        if [ -d "$c" ]; then SDK="$c"; break; fi
    done
fi
[ -n "$SDK" ] || die "找不到 Android SDK —— 请设 ANDROID_SDK_ROOT 或 ANDROID_HOME"
[ -d "$SDK" ] || die "SDK 目录不存在：$SDK"
SDK="$(cd "$SDK" && pwd)"

PLATFORMS="$SDK/platforms"
mkdir -p "$PLATFORMS"

echo "=================================================="
echo " 准备 compileSdk 37 平台"
echo "=================================================="
echo " SDK        : $SDK"
echo " 目标目录   : $PLATFORMS/$DST_NAME"

# ---------------------------------------------------------------------------
# 2. 已经装好就直接过（幂等）
# ---------------------------------------------------------------------------
if [ -f "$PLATFORMS/$DST_NAME/source.properties" ]; then
    echo ""
    echo "[OK] $PLATFORMS/$DST_NAME 已存在，无需处理"
    bash "$HERE/export-sdk-env.sh" "$SDK"
    exit 0
fi

# ---------------------------------------------------------------------------
# 3. 找 sdkmanager
# ---------------------------------------------------------------------------
SDKMGR="$(ls -1 "$SDK"/cmdline-tools/*/bin/sdkmanager 2>/dev/null | sort -V | tail -1 || true)"
if [ -z "$SDKMGR" ] && [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    SDKMGR="$SDK/cmdline-tools/latest/bin/sdkmanager"
fi
if [ -z "$SDKMGR" ]; then
    # Windows 上是 .bat；本地 Git Bash 里跑时用得上
    SDKMGR="$(ls -1 "$SDK"/cmdline-tools/*/bin/sdkmanager.bat 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -z "$SDKMGR" ]; then
    SDKMGR="$(command -v sdkmanager 2>/dev/null || true)"
fi

if [ -z "$SDKMGR" ]; then
    cat >&2 <<EOF
[X] 找不到 sdkmanager，无法自动安装 $PKG

    请先装 Android SDK Command-line Tools，然后重跑本脚本；
    或者照 docs/BUILD.md §4.1 手工把 platform-37.2 放进去。
EOF
    exit 1
fi
echo " sdkmanager : $SDKMGR"

# ---------------------------------------------------------------------------
# 4. 装 platforms;android-37.2
#
#    sdkmanager 会为首次使用弹许可协议，所以喂一串 y 进去。
#    `yes` 被提前关掉管道时会吃 SIGPIPE（exit 141），所以临时关掉 pipefail。
# ---------------------------------------------------------------------------
echo ""
echo "[1/2] 安装 $PKG （约 68 MB）"
set +o pipefail
yes | "$SDKMGR" --sdk_root="$SDK" --install "$PKG" 2>&1 | sed 's/^/    /'
rc="${PIPESTATUS[1]}"
set -o pipefail
[ "$rc" -eq 0 ] || die "sdkmanager 安装 $PKG 失败（exit $rc）"

SRC="$PLATFORMS/android-37.2"
[ -f "$SRC/source.properties" ] || die "装完了但找不到 $SRC/source.properties，sdkmanager 行为异常"

# ---------------------------------------------------------------------------
# 5. 归一化成 AGP 8.10.1 认得的样子
#
#    只改「包身份」相关的字段，不动 android.jar / 资源：
#      · 目录名        android-37.2        -> android-37
#      · package.xml   <api-level>37.2     -> 37
#                      localPackage path   -> platforms;android-37
#                      <display-name>      -> Android SDK Platform 37
#      · source.properties
#                      AndroidVersion.ApiLevel=37.2 -> 37
#                      Platform.Version / Pkg.Desc   -> 37
#
#    归一化后的状态与本机已验证可用的 platforms/android-37 完全一致。
# ---------------------------------------------------------------------------
echo ""
echo "[2/2] 归一化 $SRC -> $PLATFORMS/$DST_NAME"
rm -rf "$PLATFORMS/$DST_NAME"
mv "$SRC" "$PLATFORMS/$DST_NAME"
DST="$PLATFORMS/$DST_NAME"

if [ -f "$DST/package.xml" ]; then
    sed -i -E \
        -e 's|<api-level>37\.2</api-level>|<api-level>37</api-level>|' \
        -e 's|(localPackage[^>]*path=")platforms;android-37\.2(")|\1platforms;android-37\2|' \
        -e 's|<display-name>Android SDK Platform 37\.2</display-name>|<display-name>Android SDK Platform 37</display-name>|' \
        "$DST/package.xml"
else
    log "[!] 没有 package.xml —— sdkmanager 通常会给，缺了也不一定报错，继续"
fi

if [ -f "$DST/source.properties" ]; then
    sed -i -E \
        -e 's|^AndroidVersion\.ApiLevel=37\.2$|AndroidVersion.ApiLevel=37|' \
        -e 's|^Platform\.Version=.*$|Platform.Version=37|' \
        -e 's|^Pkg\.Desc=.*$|Pkg.Desc=Android SDK Platform 37|' \
        "$DST/source.properties"
fi

# ---------------------------------------------------------------------------
# 6. 自查
# ---------------------------------------------------------------------------
echo ""
echo "================ 校验 ================"
fail=0
for f in android.jar source.properties; do
    if [ -f "$DST/$f" ]; then
        log "[OK] $f"
    else
        log "[X] 缺 $f"
        fail=1
    fi
done

if [ -f "$DST/source.properties" ]; then
    api="$(grep -E '^AndroidVersion\.ApiLevel=' "$DST/source.properties" | cut -d= -f2)"
    ext="$(grep -E '^AndroidVersion\.ExtensionLevel=' "$DST/source.properties" | cut -d= -f2)"
    log "AndroidVersion.ApiLevel      = $api   （必须是 37）"
    log "AndroidVersion.ExtensionLevel= $ext"
    [ "$api" = "37" ] || { log "[X] ApiLevel 不是 37，AGP 会找不到 android-37"; fail=1; }
fi

if [ -f "$DST/package.xml" ]; then
    if grep -q '<api-level>37</api-level>' "$DST/package.xml"; then
        log "[OK] package.xml 的 api-level = 37"
    else
        log "[X] package.xml 的 api-level 没改成 37"
        fail=1
    fi
fi

if grep -rq '37\.2' "$DST/package.xml" "$DST/source.properties" 2>/dev/null; then
    log "[X] 还有 37.2 残留，没改干净"
    fail=1
else
    log "[OK] 无 37.2 残留"
fi

[ "$fail" -eq 0 ] || die "归一化结果不合格，见上面的 [X]"

echo ""
bash "$HERE/export-sdk-env.sh" "$SDK"
echo "=================================================="
echo " 完成：$DST"
echo "=================================================="
