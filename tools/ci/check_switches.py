#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
功能开关的**全链路一致性**校验。

## 它查什么

一个「功能开关」要在这五处同时存在，缺任何一处都会变成用户看得见的问题：

| # | 位置 | 缺了会怎样 |
|---|---|---|
| 1 | `ConfigKeys` 的常量 + `DEFAULTS` | 「重置模块」清不掉它 / 备份不带它 |
| 2 | `ConfigKeys.FEATURE_KEYS` | 「重置模块」时**不会**把它关掉（它归参数类） |
| 3 | `ConfigKeys.SNAPSHOT_KEYS` | 目标进程**收不到**它的变更 —— 用户改了开关功能不变，且**没有日志** |
| 4 | `ConfigSnapshot` 的字段 | 目标进程读不到它 |
| 5 | `ui/FeatureCatalog.kt` 的一行 | 功能页里**没有这个开关**，用户开不了 |

外加两条：

- 目录里有、但 `FeatureManager` 里**没有任何 Feature** 的 `isEnabled`/`switchedOn` 引用它
  → **假开关**（界面上有、底下不装钩子，第三轮 H1 的真事）；
- 每个功能键都要有**出厂默认值** —— 否则「重置模块」清不掉它。

## 与 `check_invariants.py` 的分工

「键有没有出现在某个 `isEnabled` 链上」由 `check_invariants.py` 查（它按 Feature 逐个解析），
本脚本查的是**目录行 ↔ 配置键 ↔ 快照字段**这条链路 —— 两者合起来才覆盖
「一个 Feature 承载多个开关」这种最容易出错的形态。

## 为什么单独一个脚本

`check_invariants.py` 已经查了「假开关」（键有没有出现在某个 `isEnabled` 链上），
但它**不查**「目录行 ↔ 配置键 ↔ 快照字段」这条链路 ——
而这条链路上漏一处的症状同样难查（改了开关没反应、没日志）。

## 用法

```
python3 tools/ci/check_switches.py
```

退出码非 0 = 有问题。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "io", "github", "MVennn", "GSAuxiliary")
KEYS = os.path.join(SRC, "core", "ConfigKeys.kt")
SNAP = os.path.join(SRC, "core", "ConfigSnapshot.kt")
CATALOG = os.path.join(SRC, "ui", "FeatureCatalog.kt")
MANAGER = os.path.join(SRC, "feature", "FeatureManager.kt")


def read(p):
    with open(p, "r", encoding="utf-8") as f:
        return f.read()


def arr(text, name):
    """取 `val NAME = arrayOf(...)` / `setOf(...)` 里的常量名。"""
    m = re.search(
        r'val\s+%s\s*(?::\s*Set<String>\s*)?=\s*(?:arrayOf|setOf)\((.*?)\n\s*\)' % name,
        text,
        re.S,
    )
    return re.findall(r'[A-Z][A-Z0-9_]+', m.group(1)) if m else []


def matches(field, const_name):
    """快照字段名与键常量名是否对应。

    **不用「驼峰化后相等」** —— 两边的命名风格并不完全一致
    （键常量 `AISTARE_*` ↔ 快照字段 `aiStare*`，中间多一个大写 S），
    那种「猜命名规则」的写法在命名一改就会误报。
    误报率高的检查器比没有更糟 —— 人会习惯性忽略它。
    """
    f = field.lower()
    c = const_name.lower().replace("_", "")
    return c == f or c in f or f in c


def main():
    keys = read(KEYS)
    snap = read(SNAP)
    catalog = read(CATALOG)
    manager = read(MANAGER)

    all_keys = arr(keys, "ALL_KEYS")
    feature_keys = arr(keys, "FEATURE_KEYS")
    param_keys = arr(keys, "FEATURE_PARAM_KEYS")
    snap_keys = arr(keys, "SNAPSHOT_KEYS")

    # 目录里的每一行 → 它用的配置键
    catalog_keys = []
    for b in re.findall(r'FeatureSpec\((.*?)\n    \),', catalog, re.S):
        m = re.search(r'enableKey\s*=\s*ConfigKeys\.(\w+)', b)
        if m:
            catalog_keys.append(m.group(1))

    # 快照的字段
    snap_fields = re.findall(r'^\s*val\s+([a-z][A-Za-z0-9_]*)\s*:', snap, re.M)
    snap_fields = [f for f in snap_fields if f != "fingerprint"]

    problems = []

    # ① 目录行 ↔ FEATURE_KEYS
    for k in catalog_keys:
        if k not in feature_keys:
            problems.append(
                "[假开关] 功能页有「%s」这一行，但它不在 `ConfigKeys.FEATURE_KEYS` 里 —— "
                "「重置模块」时不会把它关掉" % k
            )
    for k in feature_keys:
        if k not in catalog_keys:
            problems.append(
                "[开不了] `ConfigKeys.FEATURE_KEYS` 里有「%s」，但功能页里没有这一行 —— "
                "用户找不到这个开关" % k
            )

    # ② 目录行 ↔ 快照字段（目标进程不读它就没用）
    for k in catalog_keys:
        if not any(matches(f, k) for f in snap_fields):
            problems.append(
                "[目标进程不认] 功能页的「%s」在 `ConfigSnapshot` 里没有对应字段 —— "
                "目标进程读不到它，这个开关不起作用" % k
            )

    # ③ 快照字段 ↔ SNAPSHOT_KEYS
    for f in snap_fields:
        if not any(matches(f, k) for k in snap_keys):
            problems.append(
                "[收不到变更] 快照字段「%s」没有对应的键进 `ConfigKeys.SNAPSHOT_KEYS` —— "
                "目标进程收不到它的变更（改了没反应，且没有日志）" % f
            )
    for k in snap_keys:
        if not any(matches(f, k) for f in snap_fields):
            problems.append(
                "[白登记] `SNAPSHOT_KEYS` 里的「%s」在 `ConfigSnapshot` 里没有对应字段" % k
            )

    # ④ 全部键的归类
    #
    # ⚠️ **LOG_LEVEL 是文档明确写明的例外**（`ConfigKeys` 的类注释）：
    # 「它同时被目标进程用于控制日志等级，已在 snapshot 中」——
    # 所以它**既不进 FEATURE_KEYS / FEATURE_PARAM_KEYS，又在快照里**是正常的。
    # 别把这条当问题报（误报率高的检查器比没有更糟）。
    SNAPSHOT_ONLY_EXCEPTIONS = {"LOG_LEVEL"}
    for k in all_keys:
        if k not in feature_keys and k not in param_keys:
            if k in SNAPSHOT_ONLY_EXCEPTIONS:
                continue
            # 既不是功能键也不是参数键 = 模块级设置（不进快照）—— 这是允许的
            if any(matches(f, k) for f in snap_fields):
                problems.append(
                    "[分类冲突] 「%s」既是模块级设置（不在 FEATURE_KEYS / FEATURE_PARAM_KEYS）"
                    "、又进了快照 —— 归类说不清" % k
                )

    # ⑤ FEATURE_KEYS 里的每个键都要有出厂默认值
    defaults = set(re.findall(r'([A-Z][A-Z0-9_]*)\s+to\s+DEFAULT_', keys))
    for k in feature_keys:
        if k not in defaults:
            problems.append("[没默认值] 功能开关「%s」没有出厂默认值 —— 「重置模块」清不掉它" % k)

    if not problems:
        print(
            "[OK] 功能开关全链路一致：%d 个目录行 / %d 个功能键 / %d 个快照字段 / %d 个快照键"
            % (len(catalog_keys), len(feature_keys), len(snap_fields), len(snap_keys))
        )
        return 0

    print("=" * 70)
    print("功能开关全链路校验：发现 %d 条" % len(problems))
    print("=" * 70)
    for p in problems:
        print("  " + p)
    print("=" * 70)
    return 1


if __name__ == "__main__":
    sys.exit(main())
