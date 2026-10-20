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
#     1) 用 sdkmanager 装一个次要版本平台（默认 37.2，装不上依次退到 37.1 / 37.0）
#     2) 把它归一成 AGP 认得的样子 —— 目录改名 android-37，
#        包元数据里的 37.x 全部改成 37
#
#   **幂等**：platforms/android-37 已存在就直接退出，重复跑无副作用。
#
# 用法：
#   ANDROID_SDK_ROOT=/path/to/sdk bash tools/ci/setup-sdk37.sh
#   bash tools/ci/setup-sdk37.sh          # 未设变量时按常见路径自动探测
#
# 环境变量：
#   ANDROID_SDK_ROOT / ANDROID_HOME    SDK 根目录
#   GS_SDK_PLATFORM_PKG                指定要装的包（默认按候选表挑，见下）
#   GS_SDK_INSTALL_TRIES               每个包的下载重试次数，默认 3
#
# 在 GitHub Actions 里跑时，会顺手把 SDK 路径写进 $GITHUB_ENV，
# 并补一个 local.properties（已被 .gitignore 忽略），后面步骤不用再管。
# ---------------------------------------------------------------------------
set -euo pipefail

DST_NAME="android-37"          # AGP 8.10.1 认得的目录名
TARGET_API="37"                # 归一化后的整数 api-level

# 候选包：**37.0 优先**，不是 37.2。
#
# 为什么（2026-10-20 在 GitLab 共享 runner 上真踩出来的）：
#   AGP 8.10.1 要的 target hash 是 'android-37'。它算 hash 时**会带上次版本**：
#   37.2 那个包的 package.xml 里 <revision> 除了 <major>2</major> 还有 <minor>2</minor>，
#   目录名与 api-level 归一化对了，那个 <minor> 却还在 ——
#   于是 AGP 算出的是 'android-37.2'，照样认不出来，最后报
#   「Failed to find target with hash string 'android-37'」。
#   37.0 那个包（也就是 AGP 自己会装的那一个）<revision> 里**只有 <major>2</major>**，
#   归一化后正是本机那套已验证可用的状态（docs/BUILD.md §4.1）。
#
# 37.2 / 37.1 留作候选（万一 37.0 被撤下），装它们时脚本会把 <minor> 抹掉。
CANDIDATES=("platforms;android-37.0" "platforms;android-37.2" "platforms;android-37.1")
if [ -n "${GS_SDK_PLATFORM_PKG:-}" ]; then
    CANDIDATES=("$GS_SDK_PLATFORM_PKG")
fi
TRIES="${GS_SDK_INSTALL_TRIES:-3}"

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
echo " 准备 compileSdk $TARGET_API 平台"
echo "=================================================="
echo " SDK        : $SDK"
echo " 目标目录   : $PLATFORMS/$DST_NAME"
echo " 候选包     : ${CANDIDATES[*]}"

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
[X] 找不到 sdkmanager，无法自动安装 platforms;android-37.x

    请先装 Android SDK Command-line Tools，然后重跑本脚本；
    或者照 docs/BUILD.md §4.1 手工把 platform-37.2 放进去。
EOF
    exit 1
fi
echo " sdkmanager : $SDKMGR"

# ---------------------------------------------------------------------------
# 4. 装平台包
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
        if [ "$rc" -eq 0 ]; then
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

PKG=""
SRC=""
for cand in "${CANDIDATES[@]}"; do
    cand_src="$PLATFORMS/${cand#platforms;}"
    if [ -f "$cand_src/source.properties" ]; then
        echo ""
        echo "[1/2] $cand 已在本地，跳过下载"
        PKG="$cand"
        SRC="$cand_src"
        break
    fi

    echo ""
    echo "[1/2] 安装 $cand （约 68 MB）"
    if install_pkg "$cand" && [ -f "$cand_src/source.properties" ]; then
        PKG="$cand"
        SRC="$cand_src"
        break
    fi
    echo "    [!] $cand 不可用，换下一个候选"
done

if [ -z "$PKG" ]; then
    cat >&2 <<EOF
[X] 所有候选都装不上：${CANDIDATES[*]}

    常见原因：
      · 网络到 dl.google.com 不通（重跑一次通常就好）
      · 官方仓库改了包名 —— 用 \`sdkmanager --list | grep 'platforms;android-37'\`
        看现在叫什么，再用 GS_SDK_PLATFORM_PKG=platforms;android-37.x 指定
EOF
    exit 1
fi
echo ""
echo " 使用 $PKG"

# ---------------------------------------------------------------------------
# 5. 归一化成 AGP 8.10.1 认得的样子
#
#    只改「包身份」相关的字段，不动 android.jar / 资源：
#      · 目录名        android-37.x        -> android-37
#      · package.xml   **整个照抄一个「正常平台」的 package.xml**（见下）
#      · source.properties
#                      AndroidVersion.ApiLevel=37.x -> 37
#                      Platform.Version / Pkg.Desc   -> 37
#
#    ⚠️ **package.xml 必须照抄，不能只做 sed 替换**（2026-10-20 在 CI 上真踩过）：
#       37.x 那个包自带的 package.xml 里带着 `<codename></codename>`、
#       次版本痕迹与另一套命名空间映射，AGP 8.10.1 解析完**不认这个平台**，
#       报的仍是「Failed to find target with hash string 'android-37'」。
#       本机那套验证可用的 platforms/android-37/package.xml 其实是
#       **照抄 android-35/package.xml 改了 3 个字段**（path / api-level / display-name），
#       实测逐字节可复现 —— 所以 CI 里也这么做：装一个 android-35 当**模板来源**，
#       照抄它再改那 3 处。
#
#    注意：**不按目录名去猜版本号**，而是把所有 `37.<次要版本>` 一律压成 `37`。
#    目录名和元数据里的版本并不保证一致（本机验证时遇到过目录叫 android-37.2、
#    元数据里却写 37.0 的包），按目录名做替换会静默漏改 —— 自查那一步会抓到，
#    但没必要让它走到那一步。
# ---------------------------------------------------------------------------
VER="$(grep -E '^AndroidVersion\.ApiLevel=' "$SRC/source.properties" 2>/dev/null \
       | cut -d= -f2 || true)"
VER="${VER:-未知}"
MINOR_RE='37\.[0-9]+'           # 任何 37.x
DONOR_NAME="android-35"          # 模板来源：一个「正常」的稳定平台
DONOR_API="35"

if [ "$SRC" = "$PLATFORMS/$DST_NAME" ]; then
    DST="$SRC"
    log "包名本来就是 $DST_NAME，跳过归一化"
else
    echo ""
    echo "[2/2] 归一化 $SRC -> $PLATFORMS/$DST_NAME（元数据里写的是 ${VER}）"
    rm -rf "$PLATFORMS/$DST_NAME"
    mv "$SRC" "$PLATFORMS/$DST_NAME"
    DST="$PLATFORMS/$DST_NAME"
fi

# ---------------------------------------------------------------------------
# 5.1 package.xml：照抄「正常平台」那份，只改 3 个字段
# ---------------------------------------------------------------------------
DONOR="$PLATFORMS/$DONOR_NAME"
if [ ! -f "$DONOR/package.xml" ]; then
    echo ""
    echo "  模板来源 $DONOR_NAME 没装 —— 装它（只要它的 package.xml）"
    install_pkg "platforms;$DONOR_NAME" || log "[!] 装 $DONOR_NAME 失败"
fi
if [ -f "$DONOR/package.xml" ] && [ -f "$DST/package.xml" ]; then
    cp "$DONOR/package.xml" "$DST/package.xml"
    sed -i -E \
        -e "s|path=\"platforms;${DONOR_NAME}\"|path=\"platforms;android-${TARGET_API}\"|" \
        -e "s|<api-level>${DONOR_API}</api-level>|<api-level>${TARGET_API}</api-level>|" \
        -e "s|<display-name>Android SDK Platform ${DONOR_API}</display-name>|<display-name>Android SDK Platform ${TARGET_API}</display-name>|" \
        "$DST/package.xml"
    echo "  package.xml：照抄 $DONOR_NAME 的，改了 path / api-level / display-name"
else
    log "[!] 拿不到模板（$DONOR_NAME/package.xml）—— 保留原包的 package.xml，可能认不出来"
fi

if [ -f "$DST/source.properties" ]; then
    sed -i -E \
        -e "s|^AndroidVersion\.ApiLevel=.*$|AndroidVersion.ApiLevel=${TARGET_API}|" \
        -e "s|^Platform\.Version=.*$|Platform.Version=${TARGET_API}|" \
        -e "s|^Pkg\.Desc=.*$|Pkg.Desc=Android SDK Platform ${TARGET_API}|" \
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

# 下载被截断时 android.jar 会存在但很小 —— 那种情况下构建会报一堆
# 莫名其妙的「找不到符号」，不如在这里就拦住。
if [ -f "$DST/android.jar" ]; then
    jar_mb=$(( $(wc -c < "$DST/android.jar") / 1048576 ))
    if [ "$jar_mb" -ge 10 ]; then
        log "[OK] android.jar ${jar_mb} MB"
    else
        log "[X] android.jar 只有 ${jar_mb} MB，下载像是被截断了"
        fail=1
    fi
fi

if [ -f "$DST/source.properties" ]; then
    api="$(grep -E '^AndroidVersion\.ApiLevel=' "$DST/source.properties" | cut -d= -f2)"
    ext="$(grep -E '^AndroidVersion\.ExtensionLevel=' "$DST/source.properties" | cut -d= -f2)"
    log "AndroidVersion.ApiLevel      = $api   （必须是 ${TARGET_API}）"
    log "AndroidVersion.ExtensionLevel= $ext"
    [ "$api" = "$TARGET_API" ] || { log "[X] ApiLevel 不是 ${TARGET_API}，AGP 会找不到 android-${TARGET_API}"; fail=1; }
fi

if [ -f "$DST/package.xml" ]; then
    if grep -q ">${TARGET_API}</api-level>" "$DST/package.xml"; then
        log "[OK] package.xml 的 api-level = ${TARGET_API}"
    else
        log "[X] package.xml 的 api-level 没改成 ${TARGET_API}"
        fail=1
    fi
    # 路径与显示名也要对：AGP 认平台看的是 localPackage 的 path
    if grep -q "path=\"platforms;android-${TARGET_API}\"" "$DST/package.xml"; then
        log "[OK] package.xml 的 localPackage path = platforms;android-${TARGET_API}"
    else
        log "[X] package.xml 的 localPackage path 不对"
        fail=1
    fi
    if grep -q "<display-name>Android SDK Platform ${TARGET_API}</display-name>" "$DST/package.xml"; then
        log "[OK] package.xml 的 display-name = Android SDK Platform ${TARGET_API}"
    else
        log "[X] package.xml 的 display-name 不对"
        fail=1
    fi
    # 37.x 那个包自带的 package.xml 里有个空的 <codename></codename> ——
    # 本机验证可用的那份（照抄 android-35 改 3 字段）里**没有**它。
    if grep -q "<codename>" "$DST/package.xml"; then
        log "[X] package.xml 里还有 <codename> —— 与本机验证可用的那份不一致"
        fail=1
    else
        log "[OK] package.xml 无 <codename> 元素"
    fi
fi

# ⚠️ 次版本残留检查：<minor>N</minor> 这种元素**不含 "37.x" 字样**，
#    上面那条残留 grep 抓不到它 —— 而它正是让 AGP 把 hash 算成带次版本的
#    （认不出来）的元凶（2026-10-20 在 CI 上真踩过）。所以单独查。
if [ -f "$DST/package.xml" ] && grep -q "<minor>" "$DST/package.xml"; then
    log "[X] package.xml 里还有 <minor> —— AGP 会把 hash 算成带次版本的（认不出来）"
    fail=1
else
    log "[OK] package.xml 无 <minor> 元素"
fi

# 残留检查：只扫存在的文件（package.xml 可能没有，grep 一个不存在的路径会报错，
# 那样这一条会「假通过」）。包名本来就是 android-37 时无所谓残留。
residual=""
for f in "$DST/package.xml" "$DST/source.properties"; do
    [ -f "$f" ] || continue
    if grep -qE "$MINOR_RE" "$f"; then residual="$residual $f"; fi
done
if [ -n "$residual" ]; then
    log "[X] 还有 37.x 残留：$residual"
    fail=1
else
    log "[OK] 无 37.x 残留"
fi

[ "$fail" -eq 0 ] || die "归一化结果不合格，见上面的 [X]"

echo ""
bash "$HERE/export-sdk-env.sh" "$SDK"
echo "=================================================="
echo " 完成：$DST"
echo "=================================================="
