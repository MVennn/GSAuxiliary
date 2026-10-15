#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""glog_tail.py —— 从 LSPosed 模块日志里按行号取增量（供 tools/glog.sh 调用）

用法:
    python glog_tail.py <起始行号> <本地日志文件> [--module <包名子串>]
"""
import re
import sys

start = int(sys.argv[1])
path = sys.argv[2]
needle = 'com.wb.probe'
if '--module' in sys.argv:
    needle = sys.argv[sys.argv.index('--module') + 1]

# 形如: [ 2026-10-15T12:00:00.000  123: 456: 789 I/LSPosedFramework ] (宿主包名)[模块包名,TAG,…] 正文
pref = re.compile(r'^\[ [^\]]+\] \([^)]+\)\[[^\]]+\] ?')

buf = []
with open(path, encoding='utf-8', errors='replace') as f:
    for i, l in enumerate(f):
        if i < start:
            continue
        if needle not in l:
            continue
        l = pref.sub('', l.rstrip('\n'))
        if l.strip():
            buf.append(l)

# 日志里可能混入控制台不认的字符，转义后再输出，避免编码异常中断
sys.stdout.buffer.write(('\n'.join(buf) + '\n').encode('utf-8', 'backslashreplace'))
