#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_icons.py —— 自绘图标（ui/AppIcons.kt）结构校验与可视化

背景：界面图标是手写的 SVG path 数据（见 docs/UI.md §3.3）。
path 数据写错一个数字，编译一样能过，只有装到机器上才看得出来。
这个脚本把 path 解析出来做**结构检查**，并渲染成 PNG 便于肉眼比对。

校验项：
    - 命令是否都能被解析（M/L/H/V/C/S/Q/T/Z）
    - 参数个数是否与命令匹配
    - 坐标是否落在 24×24 网格内
    - 子路径数量是否与图标语义相符（例如九宫格 = 9 个矩形）

用法：
    python tools/verify_icons.py                 # 输出到 .work/icons-check.png
    python tools/verify_icons.py out.png         # 指定输出文件

零第三方依赖：只用标准库（re / zlib / struct），PNG 由脚本自己编码。
"""
import os
import re
import sys

NUM = r"-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?"
# A（圆弧）不支持：parse_path 遇到会直接报「暂不支持的命令」，故不在表内。
ARGN = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "Z": 0}

# 图标语义预期：名字 -> 期望的子路径数（None 表示不校验）
EXPECTED_SUBPATHS = {
    "ArrowBack": 1,
    "Check": 1,
    "Search": 2,      # 外圆 + 内圆（孔洞）+ 手柄
    "Settings": 2,    # 齿轮外轮廓 + 中心圆孔
    "Info": 3,        # 外圆 + 竖杠 + 点
    "Apps": 9,        # 九宫格 3×3
    "Extension": 1,   # 拼图块
    "CheckCircle": 2,  # 外圈圆 + 勾
    "ErrorCircle": 3,  # 外圈圆 + 竖杠 + 点
}

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
# 注意：包路径改过一次名（com.wb.gszx → gsauxiliary.m.venn → io.github.MVennn.GSAuxiliary），
# 这里跟着 applicationId 走。再改名时记得同步。
SRC = os.path.join(REPO, "app", "src", "main", "java",
                   "io", "github", "MVennn", "GSAuxiliary", "ui", "AppIcons.kt")


# --------------------------------------------------------------------------
# 从 Kotlin 源码里读 path 数据（避免脚本和源码两份数据不同步）
# --------------------------------------------------------------------------
def load_icons(src_path=SRC):
    if not os.path.isfile(src_path):
        raise SystemExit("找不到 AppIcons.kt：%s\n"
                         "包路径可能又改了，请同步本脚本的 SRC 常量。" % src_path)
    with open(src_path, encoding="utf-8") as f:
        src = f.read()
    pat = re.compile(
        r'name\s*=\s*"([A-Za-z0-9_]+)"(.*?)pathData\s*=\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)',
        re.S,
    )
    icons = {}
    for m in pat.finditer(src):
        name = m.group(1)
        lits = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(3))
        data = "".join(lits).replace('\\"', '"')
        icons[name] = data
    if not icons:
        raise SystemExit("没从 %s 里解析出任何 path 数据" % src_path)
    return icons


# --------------------------------------------------------------------------
# SVG path 解析（采样成折线）
# --------------------------------------------------------------------------
def _cubic(p0, p1, p2, p3, n=24):
    out = []
    for i in range(1, n + 1):
        t = i / n
        u = 1 - t
        out.append((
            u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
        ))
    return out


def _quad(p0, p1, p2, n=24):
    out = []
    for i in range(1, n + 1):
        t = i / n
        u = 1 - t
        out.append((
            u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
            u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1],
        ))
    return out


def parse_path(d):
    """返回 (子路径列表, 统计信息)。"""
    toks = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|" + NUM, d)
    groups, i = [], 0
    while i < len(toks):
        if not re.match(r"[A-Za-z]", toks[i]):
            raise ValueError("path 未以命令开头: %r" % toks[i])
        cmd = toks[i]
        i += 1
        args = []
        while i < len(toks) and not re.match(r"[A-Za-z]", toks[i]):
            args.append(float(toks[i]))
            i += 1
        groups.append((cmd, args))

    subs, pts = [], []
    cur = start = (0.0, 0.0)
    last_cmd, last_ctrl = None, None
    stats = {"commands": len(groups), "curves": 0, "maxabs": 0.0}

    def close_sub():
        if len(pts) > 1:
            subs.append(list(pts))
        pts.clear()

    for cmd, args in groups:
        up, rel, n = cmd.upper(), cmd.islower(), ARGN[cmd.upper()]
        if n == 0:
            if pts:
                pts.append(start)
            close_sub()
            cur = start
            last_cmd, last_ctrl = "Z", None
            continue
        if len(args) % n != 0:
            raise ValueError("%s 参数个数 %d 不是 %d 的倍数" % (cmd, len(args), n))
        for ci in range(0, len(args), n):
            ch = args[ci:ci + n]
            eff = "L" if (up == "M" and ci > 0) else up
            if eff == "M":
                if pts:
                    close_sub()
                cur = (cur[0] + ch[0], cur[1] + ch[1]) if rel else (ch[0], ch[1])
                start = cur
                pts.append(cur)
            elif eff in ("L", "H", "V"):
                if eff == "L":
                    nx, ny = ch[0], ch[1]
                    if rel:
                        nx, ny = cur[0] + nx, cur[1] + ny
                elif eff == "H":
                    nx, ny = (cur[0] + ch[0] if rel else ch[0]), cur[1]
                else:
                    nx, ny = cur[0], (cur[1] + ch[0] if rel else ch[0])
                cur = (nx, ny)
                pts.append(cur)
            elif eff in ("C", "S"):
                if eff == "C":
                    c1, c2, end = (ch[0], ch[1]), (ch[2], ch[3]), (ch[4], ch[5])
                    if rel:
                        c1 = (cur[0] + c1[0], cur[1] + c1[1])
                        c2 = (cur[0] + c2[0], cur[1] + c2[1])
                        end = (cur[0] + end[0], cur[1] + end[1])
                else:
                    c1 = ((2 * cur[0] - last_ctrl[0], 2 * cur[1] - last_ctrl[1])
                          if (last_ctrl is not None and last_cmd in ("C", "S")) else cur)
                    c2, end = (ch[0], ch[1]), (ch[2], ch[3])
                    if rel:
                        c2 = (cur[0] + c2[0], cur[1] + c2[1])
                        end = (cur[0] + end[0], cur[1] + end[1])
                pts.extend(_cubic(cur, c1, c2, end))
                last_ctrl, cur = c2, end
                stats["curves"] += 1
            elif eff in ("Q", "T"):
                if eff == "Q":
                    c, end = (ch[0], ch[1]), (ch[2], ch[3])
                    if rel:
                        c = (cur[0] + c[0], cur[1] + c[1])
                        end = (cur[0] + end[0], cur[1] + end[1])
                else:
                    c = ((2 * cur[0] - last_ctrl[0], 2 * cur[1] - last_ctrl[1])
                         if (last_ctrl is not None and last_cmd in ("Q", "T")) else cur)
                    end = (ch[0], ch[1])
                    if rel:
                        end = (cur[0] + end[0], cur[1] + end[1])
                pts.extend(_quad(cur, c, end))
                last_ctrl, cur = c, end
                stats["curves"] += 1
            else:
                raise ValueError("暂不支持的命令: %s" % cmd)
            if up != "M" or ci == 0:
                last_cmd = eff
    close_sub()
    stats["subpaths"] = len(subs)
    if subs:
        stats["maxabs"] = max(abs(v) for s in subs for p in s for v in p)
    return subs, stats


# --------------------------------------------------------------------------
# 描边渲染（PNG 编码走公共模块 _png.py）
# --------------------------------------------------------------------------
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _png import write_png_gray as write_png  # noqa: E402


def draw_line(buf, w, h, x0, y0, x1, y1, val=0):
    dx, dy = abs(x1 - x0), abs(y1 - y0)
    sx = 1 if x0 < x1 else -1
    sy = 1 if y0 < y1 else -1
    err = dx - dy
    while True:
        if 0 <= x0 < w and 0 <= y0 < h:
            buf[y0 * w + x0] = val
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 > -dy:
            err -= dy
            x0 += sx
        if e2 < dx:
            err += dx
            y0 += sy


def render_icon(subs, size=132, pad=8, ss=4):
    W = H = size * ss
    buf = bytearray(b"\xff" * (W * H))
    scale = (size - 2 * pad) / 24.0

    def tx(p):
        return (int(round((p[0] * scale + pad) * ss)),
                int(round((p[1] * scale + pad) * ss)))

    for sub in subs:
        prev = tx(sub[0])
        for p in sub[1:]:
            cur = tx(p)
            draw_line(buf, W, H, prev[0], prev[1], cur[0], cur[1])
            prev = cur
    out = bytearray(size * size)
    for y in range(size):
        for x in range(size):
            acc = 0
            for dy in range(ss):
                row = (y * ss + dy) * W + x * ss
                for dx in range(ss):
                    acc += buf[row + dx]
            out[y * size + x] = acc // (ss * ss)
    return out


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", ".work", "icons-check.png")
    icons = load_icons()
    names = list(icons)

    print("源码: %s" % os.path.normpath(SRC))
    print("=== 结构检查 ===")
    bad = 0
    parsed = {}
    for name in names:
        subs, st = parse_path(icons[name])
        parsed[name] = subs
        xs = [p[0] for s in subs for p in s]
        ys = [p[1] for s in subs for p in s]
        flags = []
        exp = EXPECTED_SUBPATHS.get(name)
        if exp is not None and st["subpaths"] != exp:
            flags.append("子路径数应为 %d" % exp)
        if st["maxabs"] > 24.001:
            flags.append("坐标超出 24 网格")
        mark = "  OK" if not flags else "  失败: " + "; ".join(flags)
        if flags:
            bad += 1
        print("  %-10s 子路径=%-3d 曲线段=%-3d x[%6.2f, %6.2f] y[%6.2f, %6.2f]%s"
              % (name, st["subpaths"], st["curves"], min(xs), max(xs), min(ys), max(ys), mark))

    size, gap = 132, 6
    total_w = len(names) * size + (len(names) + 1) * gap
    total_h = size + 2 * gap
    canvas = bytearray(b"\xff" * (total_w * total_h))
    for idx, name in enumerate(names):
        img = render_icon(parsed[name], size=size)
        ox, oy = gap + idx * (size + gap), gap
        for y in range(size):
            src = y * size
            dst = (oy + y) * total_w + ox
            canvas[dst:dst + size] = img[src:src + size]

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    write_png(out_path, total_w, total_h, canvas)
    print("\n已输出: %s" % os.path.normpath(out_path))
    print("结论: %s" % ("全部通过" if bad == 0 else "%d 个图标有问题" % bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
