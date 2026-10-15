#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""uifind.py —— 从 uiautomator dump 的 XML 里按文字找控件坐标

用法:
    python uifind.py ui.xml "我的策略"     -> 打印 "x y"（可点击控件中心）
    python uifind.py ui.xml --list         -> 列出所有有文字的控件及坐标
"""
import re
import sys


def attr(s, name):
    m = re.search(name + r'="([^"]*)"', s)
    return m.group(1) if m else ''


def parse(path):
    xml = open(path, encoding='utf-8', errors='replace').read()
    out = []
    for m in re.finditer(r'<node[^>]*?/?>', xml):
        s = m.group(0)
        t = attr(s, 'text')
        d = attr(s, 'content-desc')
        lab = t or d
        b = attr(s, 'bounds')
        nums = list(map(int, re.findall(r'\d+', b)))
        if len(nums) != 4:
            continue
        x = (nums[0] + nums[2]) // 2
        y = (nums[1] + nums[3]) // 2
        out.append({
            'label': lab,
            'cx': x, 'cy': y,
            'w': nums[2] - nums[0], 'h': nums[3] - nums[1],
            'clickable': attr(s, 'clickable') == 'true',
            'cls': attr(s, 'class'),
        })
    return out


def main():
    path = sys.argv[1]
    nodes = parse(path)

    if len(sys.argv) < 3:
        print("need text")
        return 1

    if sys.argv[2] == '--list':
        for n in nodes:
            if n['label']:
                print(f"{n['cx']:>5},{n['cy']:>5}  {n['w']}x{n['h']}  {n['cls'].split('.')[-1]:<26} {n['label'][:40]}")
        return 0

    needle = sys.argv[2]
    cands = [n for n in nodes if n['label'] and needle in n['label']]
    if not cands:
        return 1

    # 优先可点击；其次面积最小（更精确）
    cands.sort(key=lambda n: (not n['clickable'], n['w'] * n['h']))
    best = cands[0]
    print(f"{best['cx']} {best['cy']}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
