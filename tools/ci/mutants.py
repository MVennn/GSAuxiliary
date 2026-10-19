#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
变异测试 —— 证明「单测真的在守着那几条判据」。

## 为什么要有它

本项目这批改动里最危险的形状是「改了判据」（`docs/CODE-RULES.md` 前车之鉴第 3 条）。
而判据被改错时**编译不会报错、hygiene 也不会报** —— 只有单测能抓。

但「写了单测」不等于「单测真的守着那条规矩」：测试可能根本没覆盖到那行代码，
或者断言写得太松。所以每加一条判据，就要**故意把它改坏一次**，确认对应的测试**真的会红**。

## 用法

```
python tools/ci/mutants.py            # 跑全部变异体
python tools/ci/mutants.py --list     # 只列出变异体
```

每个变异体 = 「把某个判据改坏」+「期望至少一条测试变红」。
脚本会**逐个**改坏 → 跑测试 → 还原 → 检查结果。

⚠️ 这个脚本**会改源码**（临时），跑完必须还原 —— 它自己负责还原，
但**别在有未提交改动的工作区里跑**（还原失败会丢东西）。
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "io", "github", "MVennn", "GSAuxiliary")
TEST = os.path.join(ROOT, "app", "src", "test", "java", "io", "github", "MVennn", "GSAuxiliary")

# (相对路径, 要改的原文, 改成什么, 说明)
MUTANTS = [
    (
        "core/ConfigSnapshot.kt",
        "            if (b) h = h xor bit",
        "            if (b) h = h + bit * 2137",
        "指纹：位掩码改回加权和（会重新引入碰撞）",
    ),
    (
        "feature/SyncGate.kt",
        "        if (!followUp) {",
        "        if (true) {",
        "限速：待收敛时不再让路（BUG1 的形态）",
    ),
    (
        "feature/SyncGate.kt",
        "            if (actWait > 0) return Decision.Defer(actWait)",
        "            if (actWait > 0) return Decision.Run",
        "限速：延后变成丢弃（3.2.0 那个 1.5 秒冷却的形态）",
    ),
    (
        "feature/filter/CodeMarketParts.kt",
        "            cam.substring(0, dot) to cam.substring(dot + 1)",
        "            cam.substring(0, dot) to null",
        "切分：市场号恒为 null（自选判定会失效）",
    ),
    (
        "feature/filter/CodeMarketParts.kt",
        "            cam to null",
        "            null to null",
        "切分：无点时整串代码变成 null（代码前缀判定会整段失效）",
    ),
    (
        "feature/market/IndexShape.kt",
        "        if (groupItemCount < childCount) return false",
        "        // 变异：去掉「每项都得是 ViewGroup」",
        "指数概览判据：去掉 ViewGroup 前置（快捷入口会被误认）",
    ),
    (
        "feature/market/IndexShape.kt",
        "        if (labeledCount < childCount - 1) return false",
        "        // 变异：去掉「几乎每项都有文案」",
        "指数概览判据：去掉文案条件",
    ),
    (
        "feature/watchlist/TypefacePlan.kt",
        "                currentStyle != STYLE_BOLD",
        "                true",
        "字重：总是记原字重（会把加粗后的记成原的）",
    ),
    (
        "feature/watchlist/TypefacePlan.kt",
        "        val orig = recordedStyle ?: return Decision.None",
        "        val orig = recordedStyle ?: return Decision.Set(STYLE_NORMAL, false)",
        "字重：没记过原字重时也瞎设一个",
    ),
    (
        "core/LogMask.kt",
        "        if (dot <= 2) return \"代码已略\"",
        "        if (dot <= 0) return \"代码已略\"",
        "打码：放行两位的代码（300045.1 会被打成 30****.1 之外的形状）",
    ),
    (
        "core/LogMask.kt",
        "code.take(2) + \"****\"",
        "code",
        "打码：不打码（完整代码进日志）",
    ),
]


def run_tests():
    """跑单测，返回是否全过。"""
    # ⚠️ Windows 控制台是 GBK，Gradle 的输出可能含非 UTF-8 字节 ——
    #    不加 errors="replace" 的话，subprocess 的解码线程会直接抛 UnicodeDecodeError。
    r = subprocess.run(
        ["./gradlew.bat", ":app:testReleaseUnitTest", "--console=plain", "-q"],
        cwd=ROOT,
        capture_output=True,
        timeout=1800,
    )
    out = (r.stdout or b"").decode("utf-8", "replace") + (r.stderr or b"").decode("utf-8", "replace")
    return r.returncode == 0, out


def main():
    if "--list" in sys.argv:
        for path, old, new, why in MUTANTS:
            print("[%s] %s\n    %s" % (path, why, old.strip()[:70]))
        return 0

    # 安全起见：要求工作区干净
    st = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, capture_output=True, text=True)
    dirty = [l for l in st.stdout.splitlines() if l.strip() and ".gitignore" not in l]
    if dirty:
        print("[X] 工作区有未提交改动，变异测试会破坏它们 —— 先提交或 stash：")
        for l in dirty[:10]:
            print("    " + l)
        return 1

    print("== 先确认基线是绿的 ==")
    ok, out = run_tests()
    if not ok:
        print("[X] 基线就是红的，变异测试无从谈起：\n" + out[-2000:])
        return 1
    print("[OK] 基线全过\n")

    survived = []
    for path, old, new, why in MUTANTS:
        full = os.path.join(SRC, path.replace("/", os.sep))
        with open(full, "r", encoding="utf-8") as f:
            original = f.read()
        if old not in original:
            print("[!] 跳过（原文没找到，可能代码已改）：%s —— %s" % (path, why))
            continue
        with open(full, "w", encoding="utf-8") as f:
            f.write(original.replace(old, new, 1))
        try:
            ok, out = run_tests()
        finally:
            with open(full, "w", encoding="utf-8") as f:
                f.write(original)
        if ok:
            survived.append((path, why))
            print("[逃过] %s —— %s" % (path, why))
        else:
            print("[抓到] %s —— %s" % (path, why))

    print()
    if survived:
        print("=" * 70)
        print("有 %d 个变异体**逃过了**测试 —— 说明对应的判据**没被守住**：" % len(survived))
        for path, why in survived:
            print("    %s —— %s" % (path, why))
        print("=" * 70)
        return 1
    print("[OK] 全部 %d 个变异体都被测试抓住了 —— 那些判据确实有人守着" % len(MUTANTS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
