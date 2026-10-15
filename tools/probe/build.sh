#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# build.sh —— 手工构建 LSPosed 探针模块（不依赖 Gradle）
#
# 流程: 拉取 libxposed API -> javac -> jar -> d8 -> aapt2 -> 打包 dex + META-INF
#       -> zipalign -> apksigner
#
# 用法:
#   bash tools/probe/build.sh
#
# 环境变量（都可选，不设则自动探测）:
#   ANDROID_SDK   Android SDK 根目录
#   BUILD_TOOLS   指定 build-tools 目录（默认取版本号最大的那个）
#   JAVA_HOME     JDK 根目录
#   PYTHON        python 可执行文件
#
# 产物: tools/probe/out/GoldSunProbe.apk
#
# 注意:
#   javac / d8 / aapt2 等都是**原生程序**，不认识 MSYS 的 /c/... /f/... 路径。
#   所以下面统一用 winpath() 把路径转成 F:/... 形式再传给它们。
# ---------------------------------------------------------------------------
set -euo pipefail

HERE_MSYS="$(cd "$(dirname "$0")" && pwd)"
REPO_MSYS="$(cd "$HERE_MSYS/../.." && pwd)"

# shellcheck source=../env.sh
. "$REPO_MSYS/tools/env.sh"

# 关掉 MSYS 的自动路径改写，改由 winpath 显式控制，行为更可预期
export MSYS_NO_PATHCONV=1

HERE="$(winpath "$HERE_MSYS")"
REPO="$(winpath "$REPO_MSYS")"

# Windows 上 classpath 分隔符是 ';'，类 Unix 是 ':'
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CPSEP=";" ;;
    *)                    CPSEP=":" ;;
esac

# ---------------- 路径 ----------------
[ -n "${SDK:-}" ] || { echo "[X] 找不到 Android SDK，请设置 ANDROID_SDK"; exit 1; }

if [ -n "${BUILD_TOOLS:-}" ]; then
    BT="$BUILD_TOOLS"
else
    BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$BT" ] || { echo "[X] 找不到 build-tools"; exit 1; }

PLATFORM="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$PLATFORM" ] || { echo "[X] 找不到 platforms/android-*"; exit 1; }
ANDROID_JAR="$PLATFORM/android.jar"

[ -n "${JAVA_HOME:-}" ] || { echo "[X] 请设置 JAVA_HOME"; exit 1; }
JAVA_BIN="$(winpath "$JAVA_HOME")/bin"
# d8.bat / apksigner.bat 这些批处理包装脚本会自己读 JAVA_HOME，
# 必须给它 Windows 形式的路径，否则会报 "JAVA_HOME is set to an invalid directory"
export JAVA_HOME="$(winpath "$JAVA_HOME")"

# 原生工具在 Windows 下带后缀
suffix() { [ -f "$1.exe" ] && echo ".exe" || { [ -f "$1.bat" ] && echo ".bat" || echo ""; }; }

AAPT2="$BT/aapt2$(suffix "$BT/aapt2")"
ZIPALIGN="$BT/zipalign$(suffix "$BT/zipalign")"
APKSIGNER="$BT/apksigner$(suffix "$BT/apksigner")"
D8="$BT/d8$(suffix "$BT/d8")"
JAVAC="$JAVA_BIN/javac$(suffix "$JAVA_BIN/javac")"
JAR="$JAVA_BIN/jar$(suffix "$JAVA_BIN/jar")"
KEYTOOL="$JAVA_BIN/keytool$(suffix "$JAVA_BIN/keytool")"

BUILD="$HERE/build"
OUT="$HERE/out"
META_MSYS="$HERE_MSYS/meta"

KS="$HERE/probe.keystore"
KS_PASS="android"
KS_ALIAS="probe"

# 现代 Xposed API（libxposed API 102）
LX_VER="102.0.0"
LX_ANNO_VER="1.0.0"
LX_AAR="$BUILD/libxposed-api-$LX_VER.aar"
LX_JAR="$BUILD/lx-api.jar"
LX_ANNO="$BUILD/libxposed-annotation-$LX_ANNO_VER.jar"

echo "=================================================="
echo " 构建 GoldSun Probe (libxposed API 102)"
echo "=================================================="
echo " SDK        : $SDK"
echo " build-tools: $(basename "$BT")"
echo " platform   : $(basename "$PLATFORM")"
echo " JDK        : $JAVA_BIN"

# ---------------- 0. 检查依赖 ----------------
echo ""
echo "[0/8] 检查依赖"
for f in "$AAPT2" "$ZIPALIGN" "$APKSIGNER" "$JAVAC" "$JAR" "$KEYTOOL" "$ANDROID_JAR"; do
    if [ ! -e "$f" ]; then
        echo "  [X] 缺失: $f"
        exit 1
    fi
done
[ -n "${PYTHON:-}" ] || { echo "  [X] 找不到 python"; exit 1; }
[ -d "$META_MSYS/META-INF/xposed" ] || { echo "  [X] 缺少 $META_MSYS/META-INF/xposed"; exit 1; }
echo "  [OK] 全部就位"

# ---------------- 1. 清理 ----------------
echo ""
echo "[1/8] 清理旧产物"
rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/res" "$OUT"
echo "  [OK]"

# ---------------- 2. 拉取 libxposed API ----------------
#
# 现代模块必须用 io.github.libxposed:api（compileOnly），运行时由框架注入。
# 这里手工从 Maven 拉 aar 并抽出 classes.jar。
fetch() {
    local out="$1"; shift
    if [ -s "$out" ]; then
        echo "  [OK] 已就位 ($(stat -c%s "$out") 字节)"
        return 0
    fi
    local url
    for url in "$@"; do
        echo "  下载: $url"
        if curl -sSL --max-time 180 -o "$out" "$url" && [ -s "$out" ]; then
            echo "  [OK] 已下载 ($(stat -c%s "$out") 字节)"
            return 0
        fi
        rm -f "$out"
    done
    echo "  [X] 下载失败（检查网络，或手动放入 $out）"
    return 1
}

echo ""
echo "[2/8] libxposed API $LX_VER"
fetch "$LX_AAR" \
    "https://repo1.maven.org/maven2/io/github/libxposed/api/$LX_VER/api-$LX_VER.aar" \
    "https://maven.aliyun.com/repository/public/io/github/libxposed/api/$LX_VER/api-$LX_VER.aar"

fetch "$LX_ANNO" \
    "https://repo1.maven.org/maven2/io/github/libxposed/annotation/$LX_ANNO_VER/annotation-$LX_ANNO_VER.jar" \
    "https://maven.aliyun.com/repository/public/io/github/libxposed/annotation/$LX_ANNO_VER/annotation-$LX_ANNO_VER.jar"

# 从 aar 抽出 classes.jar（jar 工具原生支持解压）
( cd "$BUILD" && "$JAR" xf "$LX_AAR" classes.jar )
[ -f "$BUILD/classes.jar" ] || { echo "  [X] aar 里没有 classes.jar"; exit 1; }
mv "$BUILD/classes.jar" "$LX_JAR"
echo "  [OK] $LX_JAR ($(stat -c%s "$LX_JAR") 字节)"

# ---------------- 3. 编译 Java ----------------
echo ""
echo "[3/8] 编译 Java"
SRC_ARR=()
while IFS= read -r -d '' f; do SRC_ARR+=("$(winpath "$f")"); done \
    < <(find "$HERE_MSYS/src" -name "*.java" -print0)
echo "  源文件: ${#SRC_ARR[@]} 个"

# libxposed api 是 Java 17 字节码（major 61），所以不能再编到 Java 8。
# -J-Duser.language=en 让 javac 输出英文错误，避免中文环境乱码。
JFLAGS=(-J-Duser.language=en -J-Duser.country=US -nowarn
        -classpath "$ANDROID_JAR$CPSEP$LX_JAR$CPSEP$LX_ANNO"
        -d "$BUILD/classes")

if "$JAVAC" --release 17 "${JFLAGS[@]}" "${SRC_ARR[@]}" 2>"$BUILD/javac.log"; then
    echo "  [OK] --release 17"
elif "$JAVAC" -source 17 -target 17 "${JFLAGS[@]}" "${SRC_ARR[@]}" 2>"$BUILD/javac.log"; then
    echo "  [OK] -source/-target 17"
else
    echo "  [X] 编译失败:"
    cat "$BUILD/javac.log"
    exit 1
fi

# ---------------- 4. 转 DEX ----------------
echo ""
echo "[4/8] 转 DEX"
"$JAR" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
# --lib: 告诉 d8「这些类运行时由框架 / 系统提供，不要打进 dex」
"$D8" --lib "$ANDROID_JAR" --lib "$LX_JAR" --min-api 27 \
      --output "$BUILD/dex" "$BUILD/classes.jar" 2>&1 | sed 's/^/  /'
[ -f "$BUILD/dex/classes.dex" ] || { echo "  [X] d8 没产出 classes.dex"; exit 1; }
echo "  [OK] classes.dex ($(stat -c%s "$BUILD/dex/classes.dex") 字节)"

# ---------------- 5. 编译资源 ----------------
echo ""
echo "[5/8] 编译资源"
"$AAPT2" compile --dir "$HERE/res" -o "$BUILD/res.zip" 2>&1 | sed 's/^/  /'
echo "  [OK]"

# ---------------- 6. 链接 APK ----------------
#
# 注意：不再用 -A assets —— 现代模块没有 assets/xposed_init，
# 元数据走 META-INF/xposed/，在下一步打包时合并。
echo ""
echo "[6/8] 链接 APK"
"$AAPT2" link \
    -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$HERE/AndroidManifest.xml" \
    "$BUILD/res.zip" 2>&1 | sed 's/^/  /'
echo "  [OK] base.apk ($(stat -c%s "$BUILD/base.apk") 字节)"

# ---------------- 7. 打包 dex + META-INF ----------------
echo ""
echo "[7/8] 打包 dex + META-INF/xposed"
"$PYTHON" "$(winpath "$HERE_MSYS/pack_dex.py")" \
          "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/unsigned.apk" \
          "$(winpath "$META_MSYS")"
echo "  [OK] unsigned.apk ($(stat -c%s "$BUILD/unsigned.apk") 字节)"

# ---------------- 8. 对齐 + 签名 ----------------
echo ""
echo "[8/8] 对齐 + 签名"
"$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
echo "  [OK] zipalign"

if [ ! -f "$KS" ]; then
    echo "  生成签名密钥..."
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KS" \
        -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=GoldSun Probe, OU=Dev, O=GSAuxiliary, C=CN" \
        >/dev/null 2>&1
    echo "  [OK] $KS"
fi

"$APKSIGNER" sign \
    --ks "$KS" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" \
    --out "$OUT/GoldSunProbe.apk" \
    "$BUILD/aligned.apk" 2>&1 | sed 's/^/  /'
echo "  [OK] 已签名"

# ---------------- 校验 ----------------
echo ""
echo "================ 校验 ================"
"$APKSIGNER" verify --print-certs "$OUT/GoldSunProbe.apk" 2>&1 | head -3 | sed 's/^/  /'

echo ""
echo "  现代模块元数据:"
"$PYTHON" - "$OUT/GoldSunProbe.apk" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = z.namelist()
for n in sorted(x for x in names if x.startswith("META-INF/xposed/")):
    print("    [OK] " + n)
legacy = [n for n in names if n.startswith("assets/xposed_init") or n == "assets/xposed_init"]
print("    [{}] legacy assets/xposed_init".format("X" if legacy else "OK"))
prop = z.read("META-INF/xposed/module.prop").decode("utf-8", "replace")
for line in prop.splitlines():
    line = line.strip()
    if line and not line.startswith("#"):
        print("      " + line)
init = z.read("META-INF/xposed/java_init.list").decode("utf-8", "replace").strip()
scope = z.read("META-INF/xposed/scope.list").decode("utf-8", "replace").strip()
print("      java_init.list -> " + init)
print("      scope.list     -> " + scope)
PY

echo ""
echo "=================================================="
echo " 产物: $OUT/GoldSunProbe.apk"
echo " 大小: $(stat -c%s "$OUT/GoldSunProbe.apk") 字节"
echo "=================================================="
