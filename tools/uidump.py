#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""uidump.py —— 解析 uiautomator dump 的 XML，按文字/类型定位控件

用法:
    python uidump.py ui.xml --list                 列出全部有文字或可点击的节点
    python uidump.py ui.xml --find "模块"           打印匹配节点的 "x y"（优先可点击祖先）
    python uidump.py ui.xml --find-class EditText   打印第一个该类节点的 "x y"
"""
import re
import sys


def attrs(s):
    return dict(re.findall(r'([\w-]+)="([^"]*)"', s))


def nodes(xml):
    out = []
    stack = []          # 便于回溯 clickable 祖先
    for m in re.finditer(r'<node\b[^>]*?(/?)>|</node>', xml):
        tok = m.group(0)
        if tok.startswith('</'):
            if stack:
                stack.pop()
            continue
        a = attrs(tok)
        n = {
            'text': a.get('text') or a.get('content-desc') or '',
            'cls': a.get('class', ''),
            'clickable': a.get('clickable') == 'true',
            'checkable': a.get('checkable') == 'true',
            'checked': a.get('checked') == 'true',
            'bounds': [int(x) for x in re.findall(r'\d+', a.get('bounds', ''))],
        }
        n['depth'] = len(stack)
        n['clickable_ancestor'] = next(
            (p for p in reversed(stack) if p['clickable']), None)
        out.append(n)
        if not m.group(1):
            stack.append(n)
    return out


def center(b):
    if len(b) != 4:
        return None
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


def main():
    xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
    ns = nodes(xml)

    if len(sys.argv) < 3 or sys.argv[2] == '--list':
        for n in ns:
            if n['text'] or n['clickable'] or n['checkable']:
                c = center(n['bounds'])
                flag = 'CLK' if n['clickable'] else ('CHK' if n['checkable'] else '   ')
                print(f"{c[0]:>5},{c[1]:>5} {flag} {n['cls'].split('.')[-1]:<18} "
                      f"{n['bounds']} {n['text'][:44]!r}")
        return 0

    mode, needle = sys.argv[2], (sys.argv[3] if len(sys.argv) > 3 else '')

    if mode == '--find':
        cands = [n for n in ns if needle in n['text']]
        if not cands:
            return 1
        cands.sort(key=lambda n: (not n['clickable'], n['depth']), reverse=False)
        for n in cands:
            tgt = n if n['clickable'] else (n['clickable_ancestor'] or n)
            c = center(tgt['bounds'])
            if c:
                print(f"{c[0]} {c[1]}")
                return 0
        return 1

    if mode == '--find-class':
        for n in ns:
            if needle in n['cls']:
                c = center(n['bounds'])
                if c:
                    print(f"{c[0]} {c[1]}")
                    return 0
        return 1

    return 2


if __name__ == '__main__':
    sys.exit(main())
