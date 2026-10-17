#!/usr/bin/env python
"""Generate the GitHub social preview (1280x640) for GSAuxiliary.

Visual language follows assets/icon.svg:
  - background gradient  #2B3242 -> #161A22
  - accent gradient      #FFC24A -> #FF7A00 (the rising arc + arrowhead)

图标本体不走「照着 SVG 再画一遍」的老路：那块 tile 直接调
`gen_launcher_icons.render()`，所以 social preview 和 launcher 图标逐像素同源，
换 logo 时只改一处几何。

Usage:
  python tools/make_social_preview.py [output.png]
"""

import math
import os
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_launcher_icons as icons  # noqa: E402

# ---------------------------------------------------------------- constants
W, H = 1280, 640
SS = 2  # supersampling factor for crisp output

BG_TOP = icons.BG_TOP
BG_BOT = icons.BG_BOTTOM

FONT_DIR = r"C:\Windows\Fonts"
F_TITLE = os.path.join(FONT_DIR, "segoeuib.ttf")
F_REG = os.path.join(FONT_DIR, "segoeui.ttf")
F_CN = os.path.join(FONT_DIR, "msyhbd.ttc")

# icon geometry (viewBox 0 0 108 108) —— 从 gen_launcher_icons 取，别在这里另抄一份
ICON_VIEW = 108.0
ICON_CORNER = icons.CORNER
ARC_PTS = icons.curve_points()
ARROW_HEAD = list(icons.TRI)
ARROW_W = icons.CURVE_STROKE


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def accent_at(ix, iy):
    """SVG userSpaceOnUse gradient: project icon-space point onto (P1 -> P2)."""
    return icons.accent_at(ix, iy)


def stroke_gradient_path(draw, pts, width, color_fn, alpha=255, steps_per_px=1.4):
    """Stroke a polyline; colour comes from color_fn(canvas_x, canvas_y)."""
    r = width / 2.0
    for i in range(len(pts) - 1):
        x1, y1 = pts[i]
        x2, y2 = pts[i + 1]
        seg_len = math.hypot(x2 - x1, y2 - y1)
        n = max(2, int(seg_len * steps_per_px))
        for k in range(n + 1):
            t = k / n
            x, y = x1 + (x2 - x1) * t, y1 + (y2 - y1) * t
            c = color_fn(x, y)
            draw.ellipse([x - r, y - r, x + r, y + r], fill=c + (alpha,))


def vertical_gradient(size, top, bot):
    img = Image.new("RGB", size)
    d = ImageDraw.Draw(img)
    h = size[1]
    for y in range(h):
        d.line([(0, y), (size[0], y)], fill=lerp(top, bot, y / max(1, h - 1)))
    return img


def radial_mask(size, ease=2.6):
    """Circular falloff mask: 255 at centre -> 0 at corners."""
    w, h = size
    m = Image.new("L", size, 0)
    px = m.load()
    cx, cy = (w - 1) / 2.0, (h - 1) / 2.0
    maxd = math.hypot(cx, cy)
    for yy in range(h):
        for xx in range(w):
            d = math.hypot(xx - cx, yy - cy) / maxd
            v = max(0.0, 1.0 - d)
            px[xx, yy] = int(255 * (v ** ease))
    return m


def radial_glow(canvas, center, radius, color, max_alpha, ease=2.6):
    mask = radial_mask((256, 256), ease).resize((radius * 2, radius * 2), Image.LANCZOS)
    mask = mask.point(lambda v: v * max_alpha // 255)
    layer = Image.new("RGBA", (radius * 2, radius * 2), color + (0,))
    layer.putalpha(mask)
    canvas.alpha_composite(layer, (int(center[0] - radius), int(center[1] - radius)))


def draw_logo_tile(canvas, x, y, size):
    """把 launcher 图标贴到 canvas 上，左上角在 (x, y)。

    图案不在这里重画 —— 直接要 gen_launcher_icons.render() 的像素，两边永远一致。
    """
    tile = Image.frombytes("RGBA", (size, size), bytes(icons.render(size, False)))
    canvas.alpha_composite(tile, (int(x), int(y)))

    # hairline border so the tile reads against the same-colour background
    ImageDraw.Draw(canvas).rounded_rectangle(
        [x, y, x + size - 1, y + size - 1], radius=int(ICON_CORNER * size / ICON_VIEW),
        outline=(255, 255, 255, 28), width=2)


def pill(draw, x, y, text, font, h, padx, fill, outline, tcolor):
    bb = draw.textbbox((0, 0), text, font=font)
    w = (bb[2] - bb[0]) + padx * 2
    draw.rounded_rectangle([x, y, x + w, y + h], radius=h // 2, fill=fill, outline=outline, width=2)
    draw.text((x + padx, y + h / 2), text, font=font, fill=tcolor, anchor="lm")
    return w


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join("assets", "social-preview.png")
    w, h = W * SS, H * SS

    img = Image.new("RGBA", (w, h))
    img.paste(vertical_gradient((w, h), BG_TOP, BG_BOT), (0, 0))

    # ---- ambient glows -----------------------------------------------------
    radial_glow(img, (200 * SS, 290 * SS), 330 * SS, (255, 138, 42), 26)
    radial_glow(img, (950 * SS, 260 * SS), 350 * SS, (255, 122, 30), 14)

    # ---- ghost arrow watermark (logo motif), far right ---------------------
    ws = 7.6                                    # icon units -> 1x px
    # 箭镞顶端落在 (1225, 96)：图形贴到右边、箭头略微出画，弧线收尾正好避开右下 slogan
    off = ((1225 - ARROW_HEAD[0][0] * ws) * SS, (96 - ARROW_HEAD[0][1] * ws) * SS)
    scale = ws * SS
    wm = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    wd = ImageDraw.Draw(wm)
    wm_color = lambda cx, cy: accent_at((cx - off[0]) / scale, (cy - off[1]) / scale)
    arc = [(p[0] * scale + off[0], p[1] * scale + off[1]) for p in ARC_PTS]
    stroke_gradient_path(wd, arc, ARROW_W * scale, wm_color, alpha=11)
    head = [(p[0] * scale + off[0], p[1] * scale + off[1]) for p in ARROW_HEAD]
    # 5% 透明度的箭镞上，渐变细节看不出来，取重心处的颜色就够了
    hx = sum(p[0] for p in head) / 3.0
    hy = sum(p[1] for p in head) / 3.0
    wd.polygon(head, fill=wm_color(hx, hy) + (11,))
    img.alpha_composite(wm)

    # ---- logo tile ---------------------------------------------------------
    tile_size = 172 * SS
    tile_x, tile_y = 108 * SS, (h - tile_size) // 2
    draw_logo_tile(img, tile_x, tile_y, tile_size)

    # ---- text block --------------------------------------------------------
    d = ImageDraw.Draw(img)
    tx = 342 * SS
    f_title = ImageFont.truetype(F_TITLE, 92 * SS)
    f_sub = ImageFont.truetype(F_CN, 40 * SS)
    f_pill = ImageFont.truetype(F_TITLE, 24 * SS)
    f_tag = ImageFont.truetype(F_REG, 26 * SS)

    title_bb = d.textbbox((0, 0), "GSAuxiliary", font=f_title)
    sub_bb = d.textbbox((0, 0), "国信金太阳 · 体验增强模块", font=f_sub)
    pill_h = 48 * SS
    gap1, gap2 = 16 * SS, 32 * SS
    block_h = (title_bb[3] - title_bb[1]) + gap1 + (sub_bb[3] - sub_bb[1]) + gap2 + pill_h
    y = (h - block_h) // 2

    d.text((tx, y - title_bb[1]), "GSAuxiliary", font=f_title, fill=(245, 247, 251, 255), anchor="la")
    y += (title_bb[3] - title_bb[1]) + gap1
    d.text((tx, y - sub_bb[1]), "国信金太阳 · 体验增强模块", font=f_sub, fill=(163, 172, 190, 255), anchor="la")
    y += (sub_bb[3] - sub_bb[1]) + gap2

    px_ = tx + 2 * SS
    for label in ("LSPosed", "Android 8.1+", "Apache-2.0"):
        pw = pill(d, px_, y, label, f_pill, pill_h, 24 * SS,
                  fill=(255, 255, 255, 12), outline=(210, 216, 228, 70), tcolor=(206, 213, 225, 255))
        px_ += pw + 16 * SS

    # ---- tagline -----------------------------------------------------------
    d.text((w - 58 * SS, h - 52 * SS), "Make GoldenSun Great Again!",
           font=f_tag, fill=(255, 159, 69, 215), anchor="rs")

    # ---- export ------------------------------------------------------------
    # semi-transparent draws live in img's alpha channel; composite them onto
    # an opaque copy of the gradient before flattening (convert would drop alpha)
    bg = Image.new("RGBA", (w, h), (0, 0, 0, 255))
    bg.paste(vertical_gradient((w, h), BG_TOP, BG_BOT), (0, 0))
    img = Image.alpha_composite(bg, img)
    img = img.resize((W, H), Image.LANCZOS).convert("RGB")
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    img.save(out, "PNG")
    print("saved:", out)


if __name__ == "__main__":
    main()
