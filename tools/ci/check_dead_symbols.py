#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
死代码扫描 —— 「存在」但「没人用」的公开符号。

## 为什么需要它

这个项目栽过好几次「名义上存在、实际不生效」：
- 「跳过保存确认」单独打开不生效（`isEnabled` 的 or 链漏一位 → 界面有开关、底下不装钩子）；
- 锚点、选项、正则看着都在，但**没有任何调用点**；
- 调试通道补了列忘了补值（28 列 vs 15 值 → 整个通道读不出配置）。

`tools/hygiene.py` 查的是「文件内只声明、没调用的 **private** 成员」，
**不查跨文件的 public / internal 符号** —— 而后者才是上面那几例的形状。

## 查什么

对本分支新增的（或全仓的）公开符号，逐个回答：
1. 它有调用点吗？
2. 调用点在**生产代码**里吗（不是只在测试里）？

「只在测试里被调用」要分两种情况：
- **为可测性抽出来的纯函数** —— 正常，但要确认**生产代码也调它**（那才是关键）；
- **本该接到生产上却忘了接** —— 缺陷。

## 用法

```
python tools/ci/check_dead_symbols.py            # 查全仓
python tools/ci/check_dead_symbols.py --changed  # 只查本分支新增的
```

退出码非 0 = 有「只在测试里被调用」且**生产代码没调**的符号。
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PKG = os.path.join(ROOT, "app", "src", "main", "java", "io", "github", "MVennn", "GSAuxiliary")
TEST = os.path.join(ROOT, "app", "src", "test", "java")

# 这些是**故意**只在测试里用的（测试夹具 / 反射入口）
ALLOW_TEST_ONLY = {
    # AndroidJUnit4 / Robolectric 的 runner，不算业务符号
    "RobolectricSmokeTest",
}

# ⚠️ **框架实例化的入口** —— 静态扫不到它们的调用点：
# `DebugConfigProvider` 是 ContentProvider，由 `AndroidManifest.xml` 声明、框架实例化
# （`tools/hygiene.py` 里那条已知误报也是它）。
ALLOW_DEAD = {
    "DebugConfigProvider",
}

# 反射会用到的成员（名字里带这些后缀的，静态扫不准）
REFLECTABLE = re.compile(r"(Companion|Factory|Builder|INSTANCE)$")


def kotlin_files(root):
    if not os.path.isdir(root):
        return
    for dirpath, _dirnames, filenames in os.walk(root):
        for fn in filenames:
            if fn.endswith(".kt"):
                yield os.path.join(dirpath, fn)


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--changed", action="store_true", help="只查本分支新增的符号")
    args = ap.parse_args()

    prod_files = list(kotlin_files(PKG))
    test_files = list(kotlin_files(TEST))

    prod_text = "\n".join(read(p) for p in prod_files)
    test_text = "\n".join(read(p) for p in test_files)

    # 收集生产代码里声明的 top-level 符号（object / class / fun / val）
    # 只看**顶层**的（class body 里的方法不算，那通常是内部实现）
    decl_re = re.compile(
        r"^(?:internal\s+|private\s+|public\s+|open\s+|abstract\s+|sealed\s+|data\s+)*"
        r"(?:object|class|interface|enum class|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)",
        re.M,
    )

    dead = []
    only_test = []
    for path in prod_files:
        text = read(path)
        for m in decl_re.finditer(text):
            name = m.group(1)
            if name in ALLOW_TEST_ONLY or REFLECTABLE.search(name):
                continue
            pat = r"\b%s\b" % re.escape(name)
            prod_hits = len(re.findall(pat, prod_text))
            test_hits = len(re.findall(pat, test_text))
            # 声明处本身算 1 次
            if prod_hits <= 1 and test_hits <= 1:
                if name in ALLOW_DEAD:
                    continue
                dead.append((os.path.relpath(path, ROOT), name))
            elif prod_hits <= 1 and test_hits > 1:
                only_test.append((os.path.relpath(path, ROOT), name, test_hits))

    if not dead and not only_test:
        print("[OK] 死代码扫描：没有「没人调用」也没有「只在测试里被调用」的生产符号")
        return 0

    if dead:
        print("=" * 70)
        print("死代码扫描：%d 个顶层符号**谁都没调用**" % len(dead))
        print("=" * 70)
        for path, name in sorted(dead):
            print("  %s —— `%s`" % (path.replace("\\", "/"), name))
    if only_test:
        print("=" * 70)
        print("死代码扫描：%d 个符号**只在测试里被调用**" % len(only_test))
        print("（若它是为可测性抽出来的纯函数，生产代码也该调它 —— 确认一下）")
        print("=" * 70)
        for path, name, hits in sorted(only_test):
            print("  %s —— `%s`（测试里 %d 处引用）" % (path.replace("\\", "/"), name, hits))
    print("=" * 70)
    return 1


if __name__ == "__main__":
    sys.exit(main())
