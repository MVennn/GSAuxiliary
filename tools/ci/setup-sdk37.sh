#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# setup-sdk37.sh —— 让一台干净的机器上有 AGP 8.10.1 认得出来的 compileSdk 37 平台
#
# ## 背景（2026-10-20 在 GitLab 共享 runner 上真踩出来的）
#
# AGP 8.10.1 只认 **SDK XML schema v3**（命名空间 `.../repository2/03`），
# 而 Google 现在发的平台包带的是 **v4**（`.../repository2/04`）——
# v4 的 package.xml 会被 AGP **直接跳过**，日志里那句：
#
#     This version only understands SDK XML versions up to 3
#     but an SDK XML file of version 4 was encountered
#
# 就是它。平台「装了但等于没装」，最后报：
#
#     Failed to find target with hash string 'android-37'
#
# 本机那套**验证可用**的 platforms/android-37.0 就是 **v3 结构的元数据** ——
# 办法很土但有效：装 37.0，把它的 package.xml 换成仓库里存的那份 v3 模板
# （`tools/ci/sdk37-package.xml`，逐字节等于本机验证可用的那份）。
#
# ## 三条别踩的
#
# 1. **目录名不能改** —— AGP 认平台看的是 package.xml 里的 `localPackage path`，
#    与目录名对不上就等于没这个平台。目录保持 `android-37.0`。
# 2. **不要动 source.properties** —— 37.0 那个包原始的
#    `AndroidVersion.ApiLevel=37.0` 正是 AGP 认得的形式。
# 3. **别用「照抄 android-35 的 package.xml」那招**（历史文档里写过）——
#    本机那份 android-35 现在也是 v4，照抄过来一样被跳过。
#
# ## 幂等
#
# 已装好且元数据已是 v3 模板 → 直接退出，重复跑没有副作用。
#
# ## 用法
#
#     ANDROID_SDK_ROOT=/path/to/sdk bash tools/ci/setup-sdk37.sh
#
# 环境变量：
#     ANDROID_SDK_ROOT / ANDROID_SDK_HOME   SDK 根目录（不给就按常见路径探测）
#     GS_SDK_PLATFORM_PKG                   指定平台包（默认 platforms;android-37.0）
#     GS_SDK_INSTALL_TRIES                  下载重试次数（默认 3）
# ---------------------------------------------------------------------------
set -euo pipefail

PKG="${GS_SDK_PLATFORM_PKG:-platforms;android-37.0}"
PKG_DIR="${PKG#platforms;}"          # android-37.0
TRIES="${GS_SDK_INSTALL_TRIES:-3}"

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TPL_PKG="$HERE/sdk37-package.xml"     # 仓库里的 v3 模板

log() { printf '  %s\n' "$*"; }
die() { printf '[X] %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. SDK 根目录
# ---------------------------------------------------------------------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
    for c in \
        "/usr/local/lib/android/sdk" \
        "$HOME/Android/Sdk" \
        "$HOME/Library/Android/sdk" \
        "/c/Users/$USER/AppData/Local/Android/Sdk"
    do
        [ -d "$c" ] && { SDK="$c"; break; }
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
echo " 平台包     : $PKG"
echo " 目录       : $PLATFORMS/$PKG_DIR"
echo " 元数据模板 : $TPL_PKG"

# ---------------------------------------------------------------------------
# 2. 找 sdkmanager
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

    请先装 Android SDK Command-line Tools（tools/ci/setup-android-sdk.sh 会装），
    或者照 docs/BUILD.md §4.1 手工处理。
EOF
    exit 1
fi
echo " sdkmanager : $SDKMGR"

# ---------------------------------------------------------------------------
# 3. 装平台包
#
#    sdkmanager 会为首次使用弹许可协议，所以喂一串 y 进去。
#    `yes` 被提前关掉管道时会吃 SIGPIPE（exit 141），所以：
#      · 临时关掉 pipefail（否则整个 pipeline 会被判失败）
#      · 把 `yes` 的 stderr 丢掉（否则日志里留一行 `yes: standard output: Broken pipe`
#        的噪声，看着像出错，其实不是）
#
#    下载失败重试 $TRIES 次 —— runner 到 dl.google.com 偶发抽风，
#    一次超时就让整条流水线红掉太亏。
# ---------------------------------------------------------------------------
install_pkg() {
    local pkg="$1" attempt=1 rc=0
    while [ "$attempt" -le "$TRIES" ]; do
        printf '    第 %d/%d 次尝试\n' "$attempt" "$TRIES"
        set +o pipefail
        yes 2>/dev/null | "$SDKMGR" --sdk_root="$SDK" --install "$pkg" 2>&1 | sed 's/^/    /'
        rc="${PIPESTATUS[1]}"
        set -o pipefail
        if [ "$rc" = 0 ]; then
            return 0
        fi
        printf '    [X] 第 %d 次失败（exit %s）\n' "$attempt" "$rc"
        attempt=$((attempt + 1))
        if [ "$attempt" -le "$TRIES" ]; then
            sleep $((attempt * 5))
        fi
    done
    return "$rc"
}

if [ -f "$PLATFORMS/$PKG_DIR/source.properties" ] && [ -f "$PLATFORMS/$PKG_DIR/package.xml" ]; then
    echo ""
    echo "[1/3] 平台已存在：$PLATFORMS/$PKG_DIR"
else
    echo ""
    echo "[1/3] 安装 $PKG （约 68 MB）"
    install_pkg "$PKG" || die "装 $PKG 失败"
    [ -d "$PLATFORMS/$PKG_DIR" ] || die "装完了但目录不在：$PLATFORMS/$PKG_DIR"
    log "[OK] $PLATFORMS/$PKG_DIR"
fi

# ---------------------------------------------------------------------------
# 4. 把 package.xml 换成 v3 模板
#
#    这一步是整套的关键：**v4 的元数据 AGP 8.10.1 看都不看**。
#    模板是本机验证可用的那份，逐字节复制。
#    目录名与模板里的 localPackage path 必须一致 —— 不一致就改模板里的 path。
# ---------------------------------------------------------------------------
echo ""
echo "[2/3] 换 v3 元数据（AGP 8.10.1 只认 schema v3）"
[ -f "$TPL_PKG" ] || die "缺模板 $TPL_PKG —— 它是本机验证可用的那份 package.xml"
cp "$TPL_PKG" "$PLATFORMS/$PKG_DIR/package.xml"
# 模板里的 path 是 platforms;android-37.0；装的不是 37.0 时改过来
if ! grep -q "path=\"platforms;${PKG_DIR}\"" "$PLATFORMS/$PKG_DIR/package.xml"; then
    sed -i -E "s|path=\"platforms;android-[0-9.]+\"|path=\"platforms;${PKG_DIR}\"|" \
        "$PLATFORMS/$PKG_DIR/package.xml"
    log "（模板里的 path 与 $PKG_DIR 不一致，已改）"
fi
log "[OK] package.xml ← $TPL_PKG"

# ---------------------------------------------------------------------------
# 5. 自查
# ---------------------------------------------------------------------------
echo ""
echo "================ 校验 ================"
fail=0
DST="$PLATFORMS/$PKG_DIR"

# 5.1 逐字节核对：必须与本机验证可用的那份完全一致
if [ -f "$TPL_PKG" ]; then
    a="$(sha256sum "$DST/package.xml" 2>/dev/null | cut -d' ' -f1)"
    b="$(sha256sum "$TPL_PKG" 2>/dev/null | cut -d' ' -f1)"
    if [ -n "$a" ] && [ "$a" = "$b" ]; then
        log "[OK] package.xml 与本机验证可用的那份逐字节一致（${a:0:8}…）"
    else
        log "[X] package.xml 与模板不一致：${a:0:8}… vs ${b:0:8}…"
        fail=1
    fi
fi

# 5.2 **schema 版本** —— 这一条才是真正的判据
#     命名空间里出现 repository2/04 就是 v4，AGP 8.10.1 会跳过它。
if grep -q "repository2/04" "$DST/package.xml"; then
    log "[X] package.xml 是 schema v4（repository2/04）—— AGP 8.10.1 会跳过它"
    fail=1
else
    log "[OK] package.xml 是 schema v3（没有 repository2/04）"
fi

# 5.3 目录名与 localPackage path 必须对得上
if grep -q "path=\"platforms;${PKG_DIR}\"" "$DST/package.xml"; then
    log "[OK] localPackage path = platforms;${PKG_DIR}（与目录名一致）"
else
    log "[X] localPackage path 与目录名 $PKG_DIR 对不上"
    fail=1
fi

# 5.4 android.jar 得在、且不能是截断的
if [ -f "$DST/android.jar" ]; then
    jar_mb=$(( $(wc -c < "$DST/android.jar") / 1048576 ))
    if [ "$jar_mb" -ge 10 ]; then
        log "[OK] android.jar ${jar_mb} MB"
    else
        log "[X] android.jar 只有 ${jar_mb} MB，下载像是被截断了"
        fail=1
    fi
else
    log "[X] 缺 android.jar"
    fail=1
fi

# 5.5 source.properties 不该被我们动过（37.0 原始的 ApiLevel=37.0 才是 AGP 认得的）
if [ -f "$DST/source.properties" ]; then
    api="$(grep -E '^AndroidVersion\.ApiLevel=' "$DST/source.properties" | cut -d= -f2)"
    log "AndroidVersion.ApiLevel = $api"
fi

[ "$fail" -eq 0 ] || die "校验没过，见上面的 [X]"

echo ""
bash "$HERE/export-sdk-env.sh" "$SDK"
echo "=================================================="
echo " 完成：$DST"
echo "=================================================="
