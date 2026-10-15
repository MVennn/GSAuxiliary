#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sample_colors.py —— 从截图里采样若干矩形区域的主色（排查颜色问题用）

用法:
    python tools/sample_colors.py shot.png 100,200,400,260 100,300,400,360
    python tools/sample_colors.py shot.png --size 1080x2400 100,200,400,260

依赖 ffmpeg 把 PNG 解成 raw RGB24（可用 FFMPEG 环境变量指定路径）。
"""
import argparse
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _color import WHITE, decode_rgb, find_ffmpeg


def probe(data, w, h, boxes, topn=4):
    for name, (x1, y1, x2, y2) in boxes:
        cnt = Counter()
        for y in range(max(0, y1), min(h, y2)):
            row = y * w * 3
            for x in range(max(0, x1), min(w, x2)):
                i = row + x * 3
                r, g, b = data[i], data[i + 1], data[i + 2]
                if r > WHITE and g > WHITE and b > WHITE:    # 跳过白底
                    continue
                cnt[(r // 8 * 8, g // 8 * 8, b // 8 * 8)] += 1
        top = cnt.most_common(topn)
        s = "  ".join("#%02X%02X%02X x%d" % (c[0], c[1], c[2], n) for c, n in top)
        print("  %-16s %s" % (name, s if s else "<全白>"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("png")
    ap.add_argument("boxes", nargs="*", help="x1,y1,x2,y2")
    ap.add_argument("--size", default="1080x2400", help="截图尺寸，默认 1080x2400")
    args = ap.parse_args()

    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise SystemExit("找不到 ffmpeg，请设置 FFMPEG 环境变量")

    w, h = (int(v) for v in args.size.lower().split("x"))
    data = decode_rgb(ffmpeg, args.png)
    if len(data) < w * h * 3:
        raise SystemExit("ffmpeg 输出不足: %d 字节（尺寸参数是否正确？）" % len(data))

    print("=== %s (%dx%d) ===" % (args.png, w, h))
    boxes = [("box%d" % i, tuple(int(v) for v in a.split(",")))
             for i, a in enumerate(args.boxes)]
    if not boxes:
        print("  （没给区域，整图取色）")
        boxes = [("whole", (0, 0, w, h))]
    probe(data, w, h, boxes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
