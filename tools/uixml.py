#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""uixml.py —— 查询 uiautomator dump 出来的界面 XML（登录脚本专用）

用法:
    python uixml.py ui.xml --center RID        打印控件中心 "x y"（RID 可省略包名前缀）
    python uixml.py ui.xml --value RID         打印控件 text（没有则空）
    python uixml.py ui.xml --checked RID       打印 "true"/"false"
    python uixml.py ui.xml --map               打印国信安全键盘键位表，每行 "描述<TAB>x<TAB>y"
    python uixml.py ui.xml --has DESC          存在该 content-desc / resource-id 则 exit 0
    python uixml.py ui.xml --texts             打印全部非空 text（调试）

退出码: 0 命中 / 1 未命中 / 2 用法错
"""
import re
import sys
import xml.etree.ElementTree as ET

KEYPAD_ID = 'gs_keyboard'


def load(path):
    try:
        return ET.parse(path).getroot()
    except Exception as exc:                      # noqa: BLE001 - dump 可能被截断
        sys.stderr.write('uixml: 解析失败 %s: %s\n' % (path, exc))
        sys.exit(2)


def nodes(root):
    for n in root.iter('node'):
        yield n


def find(root, rid):
    for n in nodes(root):
        if rid in (n.get('resource-id') or ''):
            return n
    return None


def center(n):
    b = [int(x) for x in re.findall(r'\d+', n.get('bounds') or '')]
    if len(b) != 4:
        return None
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


def keypad_entries(root):
    kb = find(root, KEYPAD_ID)
    if kb is None:
        return None
    out = []
    for n in kb.iter('node'):
        desc = n.get('content-desc') or ''
        if not desc:
            continue
        if n.get('clickable') != 'true':
            continue
        c = center(n)
        if c:
            out.append((desc, c[0], c[1]))
    return out


def main():
    if len(sys.argv) < 3:
        sys.stderr.write(__doc__)
        return 2
    root = load(sys.argv[1])
    mode = sys.argv[2]
    arg = sys.argv[3] if len(sys.argv) > 3 else ''

    if mode == '--map':
        entries = keypad_entries(root)
        if not entries:
            return 1
        for desc, x, y in entries:
            print('%s\t%d\t%d' % (desc, x, y))
        return 0

    if mode == '--center':
        n = find(root, arg)
        if n is None:
            return 1
        c = center(n)
        if not c:
            return 1
        print('%d %d' % c)
        return 0

    if mode == '--value':
        n = find(root, arg)
        if n is None:
            return 1
        print(n.get('text') or '')
        return 0

    if mode == '--checked':
        n = find(root, arg)
        if n is None:
            return 1
        print(n.get('checked') or 'false')
        return 0

    if mode == '--has':
        for n in nodes(root):
            if arg in (n.get('content-desc') or '') or arg in (n.get('resource-id') or ''):
                return 0
        return 1

    if mode == '--texts':
        for n in nodes(root):
            t = n.get('text') or ''
            if t:
                print(t)
        return 0

    sys.stderr.write(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main())
