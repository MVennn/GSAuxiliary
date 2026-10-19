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

- 每个功能键都要有**出厂默认值** —— 否则「重置模块」清不掉它。

（「目录里有、但没有任何 Feature 引用它 → 假开关」那一条由
`check_invariants.py` 负责 —— 它按 Feature 逐个解析 `isEnabled` 链，见下。）

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

    # ⑥ 每个登记进 ALL_KEYS 的键，都必须归到「功能键」或「功能参数」两类之一
    #
    # ⚠️ 漏了会怎样：「重置模块」只清这两类（见 `ModulePreferences.writeFeatureDefaults`），
    # 漏归的键**重置时清不掉** —— 用户点了「重置模块」，那个开关还开着。
    # 而它不在功能目录里、也不在任何 Feature 的 isEnabled 链上，
    # 前几条检查都发现不了（它们查的是「目录行 ↔ 键」，不是「所有键 ↔ 归类」）。
    # ⚠️ **模块级设置是故意不归这两类的** —— 「重置模块」**不该**动它们
    # （桌面图标 / 液态玻璃 / 玻璃光效 / 语言 / 深色模式 / 可预测返回）。
    # 它们靠「既不在 FEATURE_KEYS 也不在 FEATURE_PARAM_KEYS」与「进了快照」两条区分：
    # 功能键会进快照，模块级设置不会。
    # ⚠️ **日志等级是例外** —— 它既不进功能键也不进参数键，**但进快照**
    #（目标进程用它控制日志等级，见 `ConfigKeys` 的类注释）。
    MODULE_LEVEL_ONLY = {"LOG_LEVEL"}
    classified = set(feature_keys) | set(param_keys)
    for k in all_keys:
        if k in classified or k in MODULE_LEVEL_ONLY:
            continue
        # 进快照的才是「功能相关」的；不进的必然是模块级设置（正确）
        if k in snap_keys:
            problems.append(
                "[重置清不掉] 键「%s」进了快照（目标进程会读它）却既不在 "
                "`ConfigKeys.FEATURE_KEYS` 也不在 `ConfigKeys.FEATURE_PARAM_KEYS` —— "
                "「重置模块」只清这两类，它会被漏掉" % k
            )

    # ⑦ 调试通道的列与值必须等长
    #
    # `DebugConfigProvider.query()` 用 `MatrixCursor.addRow` —— 它要求
    # 「列数 == 值数」，不等就抛 `IllegalArgumentException`，
    # **整个 adb 调试通道就读不出配置**（而那是排障的主要入口）。
    # 这条曾经真的坏过（补了列忘了补值，28 列 vs 15 值）。
    provider = os.path.join(SRC, "app", "DebugConfigProvider.kt")
    if os.path.exists(provider):
        ptext = read(provider)
        cm = re.search(r"val columns = arrayOf\((.*?)\n        \)", ptext, re.S)
        vm = re.search(r"val values = arrayOf<Any>\((.*?)\n        \)", ptext, re.S)
        if cm and vm:
            n_cols = len(re.findall(r"COL_\w+", cm.group(1)))
            n_vals = len(
                [l for l in vm.group(1).split("\n") if "ModulePreferences" in l or "raw" in l or "backend" in l]
            )
            if n_cols != n_vals:
                problems.append(
                    "[调试通道会崩] `DebugConfigProvider.query()` 的列数(%d)与值数(%d)不等 —— "
                    "`MatrixCursor.addRow` 会抛 IllegalArgumentException，"
                    "整个 adb 调试通道读不出配置" % (n_cols, n_vals)
                )

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
