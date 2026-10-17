#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 launcher 图标的位图回退资源（mipmap-*dpi）。

## 为什么要位图

自适应图标（`mipmap-anydpi-v26`）只在 API 26+ 生效。API 27 以下（本项目 minSdk 27，
所以只是兜底）、以及某些启动器/第三方桌面，仍然会去读 `mipmap-*dpi` 的 PNG。
项目原先只有自适应图标，缺这些位图，本脚本负责补齐。

## 图案

简约苹果风格：圆角方形底 + 竖向渐变 + 一条金色上升折线 + 右上箭头。
几何与配色必须和下面两个矢量文件**逐项一致**：

    app/src/main/res/drawable/ic_launcher_background.xml   （渐变底）
    app/src/main/res/drawable/ic_launcher_foreground.xml   （折线 + 箭头）

## 实现

纯标准库（math / struct / zlib），不依赖 Pillow：

1. 背景：逐行算圆角矩形的 x 区间，填竖向渐变色；
2. 前景：用**距离场**描边 —— 对每个像素求到折线各段的最短距离，
   `距离 <= 线宽/2` 即上色。圆头笔帽与圆角拐点由距离场天然产生，
   不需要单独处理 line cap / line join；
3. 圆形遮罩（`ic_launcher_round.png`）；
4. 4× 超采样降采样做抗锯齿。

用法：python tools/gen_launcher_icons.py
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _png import write_png_rgba as write_png  # noqa: E402

# ---------------------------------------------------------------------------
# 设计常量 —— 与 drawable/ic_launcher_{background,foreground}.xml 保持一致
# ---------------------------------------------------------------------------
BG_TOP = (0x2B, 0x32, 0x42)      # ic_launcher_background.xml startColor #FF2B3242
BG_BOTTOM = (0x16, 0x1A, 0x22)   # ic_launcher_background.xml endColor   #FF161A22
LINE_TOP = (0xFF, 0xC2, 0x4A)    # ic_launcher_foreground.xml startColor #FFFFC24A
LINE_BOTTOM = (0xFF, 0x7A, 0x00)  # ic_launcher_foreground.xml endColor   #FFFF7A00

# 108×108 设计网格（与矢量 viewport 相同）
# 2.9.0：整体收紧 —— 相对安全区（18..90）的留白由「左右 4.5」放宽到「左右 9.75 /
# 上下 16.25」，箭头臂长由 13 缩到 9.5，四周留出呼吸空间。
POLYLINE = [(31.0, 70.5), (44.0, 55.5), (55.5, 62.0), (77.0, 37.5)]
ARROW = [(67.5, 37.5), (77.0, 37.5), (77.0, 47.0)]
STROKE = 6.5        # strokeWidth
CORNER = 24.0       # 圆角半径（苹果 squircle ≈ 边长 22%，108 × 0.222 ≈ 24）

# 描边渐变轴 —— 必须与 ic_launcher_foreground.xml 里 <gradient> 的
# startX/startY/endX/endY 完全一致：从折线起点指向终点，是**斜向**渐变，
# 不是竖向渐变。位图这边按「投影到渐变轴」求 t，两边结果才一致。
LINE_GRAD_FROM = (31.0, 70.5)
LINE_GRAD_TO = (77.0, 37.5)

SS = 4              # 超采样倍率，用于抗锯齿

# 仓库用图标边长（README 顶部 / LSPosed 模块仓库）。2 的幂，缩放无半像素抖动。
REPO_LOGO_PX = 512


def _lerp(c0, c1, t):
    return (
        int(round(c0[0] + (c1[0] - c0[0]) * t)),
        int(round(c0[1] + (c1[1] - c0[1]) * t)),
        int(round(c0[2] + (c1[2] - c0[2]) * t)),
    )


def _dist_point_seg(px, py, a, b):
    """点 (px,py) 到线段 a-b 的最短距离。"""
    ax, ay = a
    vx, vy = b[0] - ax, b[1] - ay
    wx, wy = px - ax, py - ay
    l2 = vx * vx + vy * vy
    if l2 <= 0.0:
        return math.hypot(wx, wy)
    t = (wx * vx + wy * vy) / l2
    if t < 0.0:
        t = 0.0
    elif t > 1.0:
        t = 1.0
    return math.hypot(wx - t * vx, wy - t * vy)


def render(size, round_mask):
    """渲染单张图标，返回 RGBA bytearray。"""
    big = size * SS
    scale = big / 108.0
    px = bytearray(big * big * 4)  # 默认全透明

    def put(x, y, color):
        i = (y * big + x) * 4
        px[i] = color[0]
        px[i + 1] = color[1]
        px[i + 2] = color[2]
        px[i + 3] = 255

    # --- 1. 背景：圆角矩形 + 竖向渐变 ---
    radius = CORNER * scale
    denom = max(1, big - 1)
    for y in range(big):
        bg = _lerp(BG_TOP, BG_BOTTOM, y / denom)
        x_lo, x_hi = 0, big
        if y < radius:
            dy = radius - y
            dx = radius - math.sqrt(max(0.0, radius * radius - dy * dy))
            x_lo, x_hi = int(math.ceil(dx)), big - int(math.ceil(dx))
        elif y > big - radius:
            dy = y - (big - radius)
            dx = radius - math.sqrt(max(0.0, radius * radius - dy * dy))
            x_lo, x_hi = int(math.ceil(dx)), big - int(math.ceil(dx))
        for x in range(x_lo, x_hi):
            put(x, y, bg)

    # --- 2. 前景：折线 + 箭头，距离场描边（圆头圆角天然成立）---
    segs = []
    for pts in (POLYLINE, ARROW):
        for a, b in zip(pts, pts[1:]):
            segs.append(((a[0] * scale, a[1] * scale), (b[0] * scale, b[1] * scale)))
    half = STROKE * scale / 2.0

    xs = [p[0] for s in segs for p in s]
    ys = [p[1] for s in segs for p in s]
    x0 = max(0, int(min(xs) - half - 1))
    x1 = min(big, int(max(xs) + half + 2))
    y0 = max(0, int(min(ys) - half - 1))
    y1 = min(big, int(max(ys) + half + 2))

    # 渐变轴（缩放到位图坐标），用于求每个像素在渐变上的位置 t
    ga = (LINE_GRAD_FROM[0] * scale, LINE_GRAD_FROM[1] * scale)
    gb = (LINE_GRAD_TO[0] * scale, LINE_GRAD_TO[1] * scale)
    gvx, gvy = gb[0] - ga[0], gb[1] - ga[1]
    glen2 = gvx * gvx + gvy * gvy

    for y in range(y0, y1):
        cy = y + 0.5
        for x in range(x0, x1):
            cx = x + 0.5
            d = min(_dist_point_seg(cx, cy, a, b) for a, b in segs)
            if d <= half:
                # 投影到渐变轴，超出端点就夹住（SVG 线性渐变的默认 spreadMethod=pad）
                t = 0.0 if glen2 <= 0.0 else ((cx - ga[0]) * gvx + (cy - ga[1]) * gvy) / glen2
                t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
                put(x, y, _lerp(LINE_TOP, LINE_BOTTOM, t))

    # --- 3. 圆形遮罩（round 变体）---
    if round_mask:
        c = (big - 1) / 2.0
        r = big / 2.0
        r2 = r * r
        for y in range(big):
            dy = y - c
            row = y * big
            for x in range(big):
                dx = x - c
                if dx * dx + dy * dy > r2:
                    px[(row + x) * 4 + 3] = 0

    # --- 4. 降采样 ---
    out = bytearray(size * size * 4)
    n = SS * SS
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0
            for dy in range(SS):
                base = (y * SS + dy) * big + x * SS
                for dx in range(SS):
                    i = (base + dx) * 4
                    r += px[i]
                    g += px[i + 1]
                    b += px[i + 2]
                    a += px[i + 3]
            o = (y * size + x) * 4
            out[o] = r // n
            out[o + 1] = g // n
            out[o + 2] = b // n
            out[o + 3] = a // n
    return out


def svg_text():
    """与位图**同源**的矢量版图标，供仓库 README 与 LSPosed 模块仓库使用。

    数值全部来自本文件顶部那几个常量，以及
    `res/drawable/ic_launcher_{background,foreground}.xml` —— 改一边就要改另一边。

    与 drawable 的差别只有一处：drawable 的背景是**直角**铺满 108×108，
    圆角由自适应图标的 mask 提供；这里作为独立图标使用，所以按 [CORNER] 自己圆角。
    """
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" '
        'width="512" height="512" role="img" aria-label="GSAuxiliary">\n'
        '  <defs>\n'
        '    <linearGradient id="bg" gradientUnits="userSpaceOnUse" '
        'x1="54" y1="0" x2="54" y2="108">\n'
        '      <stop offset="0" stop-color="#%02X%02X%02X"/>\n'
        '      <stop offset="1" stop-color="#%02X%02X%02X"/>\n'
        '    </linearGradient>\n'
        '    <linearGradient id="line" gradientUnits="userSpaceOnUse" '
        'x1="%g" y1="%g" x2="%g" y2="%g">\n'
        '      <stop offset="0" stop-color="#%02X%02X%02X"/>\n'
        '      <stop offset="1" stop-color="#%02X%02X%02X"/>\n'
        '    </linearGradient>\n'
        '  </defs>\n'
        '  <rect x="0" y="0" width="108" height="108" rx="%g" ry="%g" fill="url(#bg)"/>\n'
        '  <path d="%s M%s" fill="none" stroke="url(#line)" stroke-width="%g"\n'
        '        stroke-linecap="round" stroke-linejoin="round"/>\n'
        '</svg>\n'
    ) % (
        BG_TOP[0], BG_TOP[1], BG_TOP[2],
        BG_BOTTOM[0], BG_BOTTOM[1], BG_BOTTOM[2],
        LINE_GRAD_FROM[0], LINE_GRAD_FROM[1], LINE_GRAD_TO[0], LINE_GRAD_TO[1],
        LINE_TOP[0], LINE_TOP[1], LINE_TOP[2],
        LINE_BOTTOM[0], LINE_BOTTOM[1], LINE_BOTTOM[2],
        CORNER, CORNER,
        "M" + " L".join("%g,%g" % p for p in POLYLINE),
        " L".join("%g,%g" % p for p in ARROW),
        STROKE,
    )


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.normpath(os.path.join(here, "..", "app", "src", "main", "res"))

    # 密度 -> 边长（launcher icon 标准尺寸）
    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }

    for name, size in densities.items():
        d = os.path.join(root, "mipmap-" + name)
        os.makedirs(d, exist_ok=True)
        write_png(os.path.join(d, "ic_launcher.png"), size, size, render(size, False))
        write_png(os.path.join(d, "ic_launcher_round.png"), size, size, render(size, True))
        print("wrote mipmap-%s (%dpx)" % (name, size))

    # 仓库用的大尺寸图标 —— README 顶部与 LSPosed 模块仓库共用同一份设计。
    # 512 是 2 的幂，缩放到任意尺寸都不会有半像素抖动。
    assets = os.path.normpath(os.path.join(here, "..", "assets"))
    os.makedirs(assets, exist_ok=True)
    write_png(os.path.join(assets, "logo.png"), REPO_LOGO_PX, REPO_LOGO_PX,
              render(REPO_LOGO_PX, False))
    print("wrote assets/logo.png (%dpx)" % REPO_LOGO_PX)

    with open(os.path.join(assets, "icon.svg"), "w", encoding="utf-8", newline="\n") as f:
        f.write(svg_text())
    print("wrote assets/icon.svg")


if __name__ == "__main__":
    main()
