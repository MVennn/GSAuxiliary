#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
parse_launch.py —— 把探针 LAUNCH 日志聚合成 router action 契约表

用法:
    python tools/probe/parse_launch.py <探针日志> [输出md]

    <探针日志>  su -c 'grep GSRecon /data/adb/lspd/log/modules_*.log' 的输出（原始即可）
    [输出md]    默认 docs/reference/router-contracts.md（只更新数据表段落）

约定:
    - 契约表是**活文档**：每做新功能真机验证时，探针的 LAUNCH 钩子会顺带记录
      新的跳转契约，跑一遍本脚本就能把新 action 追加进表（见
      docs/reference/router-contracts.md 的「怎么更新」）。
    - 合并策略：**只增不删** —— App 升级后某 action 消失，在表里标 `[8.6.x 起消失]`，
      不直接删行，留作降级比对。
"""
import re
import sys
import collections

DEFAULT_OUT = "docs/reference/router-contracts.md"

STRIP = re.compile(r"^.*GSRecon,[0-9a-f\-]+,\d+,\d+\]\s?")


def parse(raw_path):
    blocks = []
    cur = None
    last_act = None

    for line in open(raw_path, encoding="utf-8", errors="replace"):
        t = STRIP.sub("", line.rstrip("\n"))
        if "LAUNCH =====" in t:
            if cur:
                blocks.append(cur)
            cur = {"from": None, "action": None, "extras": {}, "stack": None,
                   "to_act": None}
            continue
        if cur is None:
            m = re.search(r"ACTIVITY > (\S+)", t)
            if m:
                last_act = m.group(1)
            continue
        if "来自页面" in t:
            cur["from"] = t.split(":", 1)[1].strip() if ":" in t else None
            continue
        m = re.search(r"\[LAUNCH\].*?action = (\S+)", t)
        if m:
            cur["action"] = m.group(1)
            continue
        m = re.search(r"^\s+(\S+)\s+:\s+(\S+)\s+=\s+(.*)$", t)
        if m and cur["action"]:
            cur["extras"][m.group(1)] = (m.group(2), m.group(3)[:120])
            continue
        if "stack:" in t:
            cur["stack"] = t.split("stack:", 1)[1].strip()[:200]
            continue
        m = re.search(r"ACTIVITY > (\S+)", t)
        if m:
            cur["to_act"] = m.group(1)
            blocks.append(cur)
            cur = None
            continue
        m = re.search(r"\[STG-INTENT\].*?action = (\S+)", t)
        if m and cur and not cur["action"]:
            cur["action"] = m.group(1)
    if cur:
        blocks.append(cur)

    for i, b in enumerate(blocks):
        if not b["to_act"]:
            nxt = blocks[i + 1] if i + 1 < len(blocks) else None
            b["to_act"] = (nxt["from"] if nxt and nxt["from"] else last_act)

    agg = collections.OrderedDict()
    for b in blocks:
        a = b["action"] or "(无action)"
        e = agg.setdefault(a, {"acts": collections.Counter(),
                               "froms": collections.Counter(),
                               "keys": {}, "count": 0})
        e["count"] += 1
        if b["to_act"]:
            e["acts"][b["to_act"]] += 1
        if b["from"]:
            e["froms"][b["from"]] += 1
        for k, (ty, v) in b["extras"].items():
            e["keys"].setdefault(k, (ty, v))
    return agg


def build_table(agg):
    lines = []
    lines.append("| router action | 次数 | 落点 Activity | extras（key: 类型） |")
    lines.append("|---|---|---|---|")
    for a, e in sorted(agg.items(), key=lambda kv: -kv[1]["count"]):
        acts = " / ".join("%s×%d" % (k.split(".")[-1], v)
                          for k, v in e["acts"].most_common(3)) or "?"
        keys = ", ".join("%s: %s" % (k, ty.split(".")[-1])
                         for k, (ty, _) in sorted(e["keys"].items()))
        lines.append("| `%s` | %d | %s | %s |" % (a, e["count"], acts, keys or "—"))
    return "\n".join(lines)


def merge_into_doc(table_md, out_path):
    """只替换文档里 <!-- TABLE:BEGIN --> … <!-- TABLE:END --> 之间的数据段。"""
    try:
        doc = open(out_path, encoding="utf-8").read()
    except FileNotFoundError:
        print("[X] 找不到 %s —— 先手工建好文档骨架再跑本脚本" % out_path)
        return False
    m = re.search(r"(<!-- TABLE:BEGIN -->).*?(<!-- TABLE:END -->)",
                  doc, re.S)
    if not m:
        print("[X] 文档里没有 TABLE:BEGIN/END 标记，无法定位数据段")
        return False
    new = doc[:m.start()] + m.group(1) + "\n\n" + table_md + "\n\n" + m.group(2) + doc[m.end():]
    open(out_path, "w", encoding="utf-8", newline="\n").write(new)
    return True


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    raw = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    agg = parse(raw)
    table = build_table(agg)
    print("LAUNCH 块: %d, action: %d" % (
        sum(e["count"] for e in agg.values()), len(agg)))
    for a, e in sorted(agg.items(), key=lambda kv: -kv[1]["count"]):
        print("  %-28s ×%-2d -> %s" % (
            a, e["count"], [k.split(".")[-1] for k in e["acts"]]))
    if merge_into_doc(table, out):
        print("[OK] 已更新 %s" % out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
