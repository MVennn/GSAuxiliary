#!/usr/bin/env python3
"""raw2png.py —— 把 `adb exec-out screencap`（raw，不带 -p）的输出转成 PNG。

为什么要这一步：
  `screencap -p` 在设备上做 PNG 编码（zlib 压缩 5MB 像素），实测 650–790ms；
  `screencap` 裸输出只做内存拷贝，实测 273–280ms，本地 zlib level 1 再花 ~100ms。
  净收益约 1.6–2×，且 raw 还能直接拿去算像素差异，不必先解 PNG。

raw 布局（Android 8+）：
  offset 0..15   4 个小端 u32：width, height, pixel_format, colorspace
  offset 16..    width*height*4 字节，RGBA_8888（A 恒为 255）

用法:
  python tools/raw2png.py in.raw out.png [level]
"""

import struct
import sys
import zlib

PNG_SIG = b"\x89PNG\r\n\x1a\n"


def chunk(typ, data):
    return (
        struct.pack(">I", len(data))
        + typ
        + data
        + struct.pack(">I", zlib.crc32(typ + data) & 0xFFFFFFFF)
    )


def main():
    if len(sys.argv) < 3:
        sys.exit("用法: raw2png.py in.raw out.png [level]")
    src, dst = sys.argv[1], sys.argv[2]
    level = int(sys.argv[3]) if len(sys.argv) > 3 else 1

    with open(src, "rb") as f:
        data = f.read()
    if len(data) < 16:
        sys.exit("raw 文件太短，不像 screencap 输出")

    w, h, _fmt, _cs = struct.unpack("<IIII", data[:16])
    if w <= 0 or h <= 0 or w * h > 40_000_000:
        sys.exit(f"尺寸异常: {w}x{h}")

    stride = w * 4
    need = stride * h
    px = data[16:16 + need]
    if len(px) < need:
        sys.exit(f"像素不足: {len(px)} < {need}")

    # 每行前置 filter byte 0（None），然后直接拷 RGBA
    rows = b"".join(b"\x00" + px[y * stride:(y + 1) * stride] for y in range(h))
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    png = (
        PNG_SIG
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(rows, level))
        + chunk(b"IEND", b"")
    )
    with open(dst, "wb") as f:
        f.write(png)
    print(f"{w}x{h}")


if __name__ == "__main__":
    main()
