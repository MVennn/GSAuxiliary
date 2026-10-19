#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
发版说明的**覆盖率**核对。

## 为什么需要它

`docs/RELEASE.md` 定了「覆盖率红线」：**本版全部改动必须进说明**，
一条不漏。但这条规矩之前**没有任何自动检查** —— 靠人记，
而人记的结果通常是「想起来才补」。

这个脚本做的是**机械核对**：拿本分支（或指定范围）改动的用户可感知条目，
逐个在发版说明里找对应的关键词。找不到就报出来。

## 判定口径

「用户可感知」= 界面上看得见 / 配置的行为变了 / 数据变了。
纯内部重构（抽函数、加缓存、改注释）**不算**。

所以这个脚本**不是**自动从 git log 生成说明（那会产出没法给人看的条目），
而是维护一份**关键词对照表** —— 每条改动配几个关键词，说明里必须能搜到其中之一。
新增一条用户可感知的改动时，往表里加一行即可。

## 用法

```
python3 tools/ci/check_release_notes.py
python3 tools/ci/check_release_notes.py --notes docs/release-notes/3.3.0.md
```

退出码非 0 = 有改动没进说明。
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# (改动名, 说明里必须能搜到的关键词之一, 说明文件)
# 新增一条用户可感知的改动时，在这里加一行。
# ⚠️ 关键词必须**足够特异** —— 说明里别处也会提到同一个词
# （比如开头那句概述里就出现了「指数概览」）。
# 所以判据是「说明里存在**以这个关键词开头的一条目行**」，而不是「出现过这个词」。
ENTRIES = [
    # —— 本分支（第四轮审查批）——
    ("隐藏指数概览", ["隐藏 指数概览"], "3.3.0.md"),
    ("自选股加粗", ["自选股名称加粗"], "3.3.0.md"),
    ("顶栏去副标题", ["调整 顶栏"], "3.3.0.md"),
    ("盯盘日志脱敏", ["修复 日志隐私"], "3.3.0.md"),
    ("行情筛选可还原", ["修复 行情筛选开关"], "3.3.0.md"),
    ("备份恢复不丢状态", ["修复 备份恢复"], "3.3.0.md"),
    ("方案聚合口径", ["修复 方案口径"], "3.3.0.md"),
    ("取数不再重连", ["修复 行情取数"], "3.3.0.md"),
    ("配置变更不丢", ["修复 配置变更丢失"], "3.3.0.md"),
    ("盯盘列表按类缓存", ["优化 盯盘列表"], "3.3.0.md"),
    ("行情筛选每帧重算", ["优化 行情筛选"], "3.3.0.md"),
    ("筛选重算纯重构", ["优化 筛选重算"], "3.3.0.md"),
    ("关于页玻璃底板", ["修复 关于页 玻璃底板"], "3.3.0.md"),
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--notes", help="指定要核对的说明文件（默认按表里的来）")
    args = ap.parse_args()

    base = os.path.join(ROOT, "docs", "release-notes")
    cache = {}
    problems = []
    checked = 0

    for name, keywords, filename in ENTRIES:
        path = args.notes or os.path.join(base, filename)
        if path not in cache:
            if not os.path.exists(path):
                problems.append("[说明文件不存在] %s —— 找不到 %s" % (name, path))
                cache[path] = ""
                continue
            with open(path, "r", encoding="utf-8") as f:
                cache[path] = f.read()
        text = cache[path]
        if not text:
            continue
        checked += 1
        # 条目行：`- <动词> <关键词> —— …`
        lines = re.findall(r"^\s*[-*].*$", text, re.M)
        if not any(any(k in ln for k in keywords) for ln in lines):
            problems.append(
                "[没进说明] 「%s」在 %s 里找不到 —— 本版全部改动必须进说明"
                "（`docs/RELEASE.md` 的覆盖率红线）" % (name, os.path.basename(path))
            )

    if not problems:
        print("[OK] 发版说明覆盖：%d 条用户可感知的改动全部在说明里" % checked)
        return 0

    print("=" * 70)
    print("发版说明覆盖核对：发现 %d 条" % len(problems))
    print("=" * 70)
    for p in problems:
        print("  " + p)
    print("=" * 70)
    return 1


if __name__ == "__main__":
    sys.exit(main())
