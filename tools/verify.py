#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
verify.py —— 策略详情页「自选股高亮」自动校验

流程:
    1. 从设备取当前页面控件树
    2. 找出「股票名称」节点（6 位代码节点上方最近的一行）
    3. 截图，在名称节点的边界框里采样像素，判断文字颜色
    4. 输出 名称 / 代码 / 主色 / 判定

用法:
    python tools/verify.py                    # 默认高亮色 = 琥珀黄 #FF8000
    python tools/verify.py --color 3482FF     # 指定高亮色（十六进制，不含 #）

环境变量:
    ADB       adb 可执行文件路径（不设则自动探测）
    FFMPEG    ffmpeg 可执行文件路径（用于把 PNG 解成 RGB24）
    SERIAL    指定设备序列号（多设备时用）
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _color import WHITE, decode_rgb, find_ffmpeg


# --------------------------------------------------------------------------- 环境

def find_adb():
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    exe = "adb.exe" if os.name == "nt" else "adb"
    roots = [
        os.environ.get("ANDROID_SDK"),
        os.environ.get("ANDROID_HOME"),
        os.environ.get("LOCALAPPDATA", "") + r"\Android\Sdk",
        os.path.expanduser("~/AppData/Local/Android/Sdk"),
        os.path.expanduser("~/Library/Android/sdk"),
        os.path.expanduser("~/Android/Sdk"),
    ]
    for r in roots:
        if r and os.path.isfile(os.path.join(r, "platform-tools", exe)):
            return os.path.join(r, "platform-tools", exe)
    return shutil.which("adb")


ADB = find_adb()
FFMPEG = find_ffmpeg()


# --------------------------------------------------------------------------- 设备

def sh(args, binary=False):
    p = subprocess.run(args, capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "ignore")


def pick_device():
    if os.environ.get("SERIAL"):
        return os.environ["SERIAL"]
    for _ in range(40):
        out = sh([ADB, "devices"])
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                return parts[0]
        time.sleep(1)
    raise SystemExit("找不到设备")


def screen_size(dev):
    out = sh([ADB, "-s", dev, "shell", "wm", "size"])
    m = re.search(r"(\d+)x(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def dump_ui(dev):
    sh([ADB, "-s", dev, "shell", "uiautomator dump /sdcard/ui.xml"])
    xml = sh([ADB, "-s", dev, "shell", "cat /sdcard/ui.xml"])
    if "<hierarchy" not in xml:
        # dump 失败时 cat 会拿到空内容或旧文件，别让 ET 抛裸 traceback
        raise SystemExit(
            "控件树 dump 失败（返回 %d 字节）——先确认设备在线、目标页在前台，再重试"
            % len(xml)
        )
    return xml


def screenshot(dev):
    return sh([ADB, "-s", dev, "exec-out", "screencap", "-p"], binary=True)


# --------------------------------------------------------------------------- 解析

def parse_bounds(s):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", s or "")
    return tuple(int(v) for v in m.groups()) if m else None


def decode(png, w, h):
    return decode_rgb(FFMPEG, png, from_stdin=True)


def text_color(data, box, w, h):
    x1, y1, x2, y2 = box
    cnt = Counter()
    for y in range(max(0, y1), min(h, y2)):
        row = y * w * 3
        for x in range(max(0, x1), min(w, x2)):
            i = row + x * 3
            r, g, b = data[i], data[i + 1], data[i + 2]
            if r > WHITE and g > WHITE and b > WHITE:    # 白底（阈值统一走 _color.WHITE）
                continue
            if abs(r - g) < 20 and abs(g - b) < 20 and r < 120:
                cnt[("DARK", r // 8 * 8, g // 8 * 8, b // 8 * 8)] += 1
            else:
                cnt[("COLOR", r // 8 * 8, g // 8 * 8, b // 8 * 8)] += 1
    return cnt.most_common(1)[0][0] if cnt else None


# --------------------------------------------------------------------------- 主流程

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--color", default="FF8000",
                    help="期望的高亮色，十六进制不含 #，默认 FF8000")
    ap.add_argument("--tol", type=int, default=24, help="颜色容差，默认 24")
    args = ap.parse_args()

    want = tuple(int(args.color[i:i + 2], 16) for i in (0, 2, 4))

    if not ADB:
        raise SystemExit("找不到 adb，请设置 ADB 环境变量")
    if not FFMPEG:
        raise SystemExit("找不到 ffmpeg，请设置 FFMPEG 环境变量")

    dev = pick_device()
    w, h = screen_size(dev)
    print("[dev] %s  %dx%d" % (dev, w, h))

    root = ET.fromstring(dump_ui(dev))

    nodes = []
    for n in root.iter("node"):
        t = (n.get("text") or "").strip()
        b = parse_bounds(n.get("bounds"))
        if t and b:
            nodes.append((t, b))
    nodes.sort(key=lambda kv: (kv[1][1], kv[1][0]))

    # 股票名 = 紧跟在 6 位数字代码上方、同一行的名称
    rows = []
    for i, (t, b) in enumerate(nodes):
        if re.fullmatch(r"\d{6}", t):
            for j in range(i - 1, -1, -1):
                tt, bb = nodes[j]
                if bb[1] < b[1] and bb[1] > b[1] - 130 and bb[0] < 700:
                    rows.append((tt, t, bb))
                    break

    if not rows:
        print("[!] 没解析到股票行，当前页面可能不是策略详情")
        return 1

    png = screenshot(dev)
    data = decode(png, w, h)
    print("[shot] %d bytes -> %d rgb" % (len(png), len(data)))
    print()

    print("  %-14s %-8s %-10s %s" % ("名称", "代码", "主色", "判定"))
    print("  " + "-" * 52)
    hit = miss = 0
    for name, code, box in rows:
        c = text_color(data, box, w, h)
        if c is None:
            print("  %-14s %-8s %-10s %s" % (name, code, "-", "无像素"))
            continue
        kind, r, g, b = c
        is_hit = (kind == "COLOR"
                  and abs(r - want[0]) < args.tol
                  and abs(g - want[1]) < args.tol
                  and abs(b - want[2]) < args.tol)
        if is_hit:
            hit += 1
        else:
            miss += 1
        print("  %-14s %-8s #%02X%02X%02X  %s"
              % (name, code, r, g, b, "高亮" if is_hit else "普通"))
    print("  " + "-" * 52)
    print("  高亮 %d 只 / 普通 %d 只" % (hit, miss))
    return 0


if __name__ == "__main__":
    sys.exit(main())
