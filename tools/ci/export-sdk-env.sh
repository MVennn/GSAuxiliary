#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# export-sdk-env.sh —— 把 SDK 路径交给后面的构建步骤
#
#   · 在 GitHub Actions 里（$GITHUB_ENV 存在）：写 ANDROID_SDK_ROOT / ANDROID_HOME，
#     并补一份 local.properties（该文件已被 .gitignore 忽略）。
#   · 在别的地方：只打印一行提示，什么都不改。
#
# 单独拆出来是因为 setup-sdk37.sh 有两条返回路径（已装好 / 刚装完），
# 两边都要做同一件事。
#
# 用法： bash tools/ci/export-sdk-env.sh /path/to/android/sdk
# ---------------------------------------------------------------------------
set -euo pipefail

SDK="${1:-}"
[ -n "$SDK" ] || { echo "[X] 用法: export-sdk-env.sh <SDK 路径>" >&2; exit 1; }

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

if [ -n "${GITHUB_ENV:-}" ]; then
    {
        echo "ANDROID_SDK_ROOT=$SDK"
        echo "ANDROID_HOME=$SDK"
    } >> "$GITHUB_ENV"

    if [ ! -f "$ROOT/local.properties" ]; then
        printf 'sdk.dir=%s\n' "$SDK" > "$ROOT/local.properties"
        echo "  已写 local.properties -> $SDK"
    fi
    echo "  已导出 ANDROID_SDK_ROOT / ANDROID_HOME"
else
    echo "  （非 CI 环境）SDK 路径: $SDK"
    echo "  如需构建请确保 local.properties 里有 sdk.dir，或导出 ANDROID_SDK_ROOT"
fi
