"""极简 PNG 编码 —— tools/ 下免第三方依赖脚本的公共底座。

gen_launcher_icons.py（RGBA 直出）与 verify_icons.py（灰度转 RGB）原来各写了一份
几乎逐字相同的 chunk()/write_png()，这里收成一份：改压缩策略只动这一处。

两个入口的输出与各自旧实现**逐字节一致**（同 zlib level 9、同 IHDR 布局），
替换时不需要重新对产物做视觉比对。
"""

import struct
import zlib


def _chunk(typ, data):
    return (
        struct.pack(">I", len(data))
        + typ
        + data
        + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)
    )


def _encode(rows, w, h, color_type):
    ihdr = struct.pack(">IIBBBBB", w, h, 8, color_type, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", ihdr)
        + _chunk(b"IDAT", zlib.compress(bytes(rows), 9))
        + _chunk(b"IEND", b"")
    )


def write_png_rgba(path, w, h, rgba):
    """RGBA（color type 6）逐像素直写，每行前缀 filter 0。"""
    stride = w * 4
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgba[y * stride:(y + 1) * stride]
    with open(path, "wb") as f:
        f.write(_encode(raw, w, h, 6))


def write_png_gray(path, w, h, gray):
    """单通道灰度转 24 位 RGB（color type 2），v 逐像素复制三份。"""
    rows = bytearray()
    for y in range(h):
        rows.append(0)
        base = y * w
        for x in range(w):
            v = gray[base + x]
            rows += bytes((v, v, v))
    with open(path, "wb") as f:
        f.write(_encode(rows, w, h, 2))
