#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""_color.py —— 取色相关的公共工具

`verify.py`（策略页黄名回归）与 `sample_colors.py`（区域取色排查）都要走
「找 ffmpeg → 把 PNG 解成 RGB24 → 统计主色」这条链路。以前这一段在两个
脚本里各写了一份，改一处忘一处，所以收到这里。

这里只放**两边行为完全一致**的部分。直方图本身没有抽出来：`verify.py`
要把像素先分成「深灰」与「有彩色」两类再统计（判定文字颜色是否被染黄），
`sample_colors.py` 只按纯 RGB 统计，key 的语义不同，合并没有意义。
"""
import os
import shutil
import subprocess

# 三个通道都超过它就算白底 —— 采样时跳过，避免整片白色把主色顶掉
WHITE = 235


def find_ffmpeg():
    """优先 `FFMPEG` 环境变量，其次 PATH。找不到返回 None。"""
    if os.environ.get("FFMPEG"):
        return os.environ["FFMPEG"]
    return shutil.which("ffmpeg")


def decode_rgb(ffmpeg, source, from_stdin=False):
    """把 PNG 解成 raw RGB24 字节串。

    source 是文件路径；`from_stdin=True` 时 source 传 bytes（走 pipe，
    用于直接从 adb 拿到的截图，不必先落盘）。
    """
    cmd = [ffmpeg, "-v", "error",
           "-i", "pipe:0" if from_stdin else source,
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    if from_stdin:
        p = subprocess.run(cmd, input=source, capture_output=True)
    else:
        p = subprocess.run(cmd, capture_output=True)
    return p.stdout
