#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""gskeys.py —— 用国信安全键盘把一段文本逐字符敲进登录页。

为什么单独用 python：
  安全键盘的每个键只在 content-desc 里给字符，切换键的 desc 是中文
  （「切换数字键盘」「大小写切换」）。这些字符串经过 bash/awk 比较时
  容易受 locale 与编码影响，实测在 git-bash 里会匹配不上，导致字符
  被静默丢弃、密码不完整、点登录毫无反应（App 对空/短密码不提交）。
  在 python 里解析 XML 并比较，全程 UTF-8，稳定。

用法:
  python tools/gskeys.py --adb <adb.exe> --serial 127.0.0.1:16416 \
      --env-file tools/login.local.env --var GS_PASS
  python tools/gskeys.py ... --dry-run      只打印键位表，不点击
  python tools/gskeys.py ... --no-clear     不先点 ivClearPwd 清空

退出码: 0 全部敲进去 / 1 有字符没敲进去 / 2 环境问题 / 3 键盘不支持该字符
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ETPWD_RID = "com.guosen.android:id/etPwd"
CLEAR_RID = "com.guosen.android:id/ivClearPwd"
NUM_SW = "切换数字键盘"
ALPHA_SW = "ABC"
CASE_SW = "大小写切换"
TAP_GAP = 0.45
SW_GAP = 0.6


def read_var(path, name):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    m = re.search(r'^\s*' + re.escape(name) + r'\s*=\s*"?([^"\r\n]*)"?\s*$', text, re.M)
    return m.group(1) if m else None


class Driver(object):
    def __init__(self, adb, serial, tmpdir):
        self.adb = adb
        self.serial = serial
        self.xml = os.path.join(tmpdir, "gskeys_ui.xml")
        self.seq = 0

    def shell(self, cmd, timeout=25):
        return subprocess.run([self.adb, "-s", self.serial, "shell", cmd],
                              capture_output=True, timeout=timeout)

    def dump(self):
        self.seq += 1
        remote = "/sdcard/gskeys_%d.xml" % self.seq
        self.shell("rm -f " + remote)
        self.shell("uiautomator dump " + remote, timeout=40)
        subprocess.run([self.adb, "-s", self.serial, "pull", remote, self.xml],
                       capture_output=True, timeout=40)
        if not os.path.exists(self.xml) or os.path.getsize(self.xml) < 100:
            return None
        with open(self.xml, encoding="utf-8") as f:
            return f.read()

    def tap(self, xy, gap=TAP_GAP):
        self.shell("input tap %d %d" % (xy[0], xy[1]))
        time.sleep(gap)


def parse(xmltext):
    root = ET.fromstring(xmltext)
    keys = {}
    spots = {}

    def walk(node, in_kb):
        rid = node.get("resource-id") or ""
        here = in_kb or ("gs_keyboard" in rid)
        bounds = re.findall(r"\d+", node.get("bounds") or "")
        center = None
        if len(bounds) == 4:
            center = ((int(bounds[0]) + int(bounds[2])) // 2,
                      (int(bounds[1]) + int(bounds[3])) // 2)
        if center:
            if rid == ETPWD_RID:
                spots["etPwd"] = center
            elif rid == CLEAR_RID:
                spots["clear"] = center
            elif here and node.get("clickable") == "true":
                desc = node.get("content-desc") or ""
                if desc:
                    keys.setdefault(desc, center)
        for child in node:
            walk(child, here)

    walk(root, False)
    return keys, spots


def type_text(drv, text, clear=True):
    xmltext = drv.dump()
    if not xmltext:
        print("dump 失败，读不到控件树")
        return 2
    keys, spots = parse(xmltext)

    if clear and spots.get("clear"):
        drv.tap(spots["clear"], 0.4)
        xmltext = drv.dump()
        if not xmltext:
            print("dump 失败（清空密码后）")
            return 2
        keys, spots = parse(xmltext)

    if spots.get("etPwd"):
        drv.tap(spots["etPwd"], 0.9)
        xmltext = drv.dump()
        if not xmltext:
            print("dump 失败（点密码框后）")
            return 2
        keys, spots = parse(xmltext)

    if not keys:
        print("安全键盘没弹出来（gs_keyboard 下没有可点键）")
        return 2

    done = 0
    total = len(text)
    for idx, ch in enumerate(text):
        for _ in range(6):
            if ch in keys:
                drv.tap(keys[ch])
                done += 1
                break
            if ch.isdigit():
                sw = keys.get(NUM_SW)
            elif ch.isalpha():
                # 字母页没有该字母：要么还在数字页（点 ABC），要么大小写不对
                sw = keys.get(ALPHA_SW) or keys.get(CASE_SW)
            else:
                print("第 %d 位是安全键盘不支持的字符（只处理 0-9 A-Z a-z），请手动登录一次" % (idx + 1))
                return 3
            if not sw:
                print("第 %d 位找不到切换键（当前键位 %d 个）" % (idx + 1, len(keys)))
                return 1
            drv.tap(sw, SW_GAP)
            xmltext = drv.dump()
            if not xmltext:
                print("dump 失败（切换键盘页后）")
                return 2
            keys, spots = parse(xmltext)
        else:
            print("第 %d 位点了 6 次仍不在键位上" % (idx + 1))
            return 1
    print("typed=%d/%d keys=%d" % (done, total, len(keys)))
    return 0 if done == total else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", required=True)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--env-file", default="")
    ap.add_argument("--var", default="GS_PASS")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-clear", action="store_true")
    args = ap.parse_args()

    text = os.environ.get(args.var) or ""
    if not text and args.env_file and os.path.exists(args.env_file):
        text = read_var(args.env_file, args.var) or ""
    if not text:
        print("没拿到要敲的文本（--var %s）" % args.var)
        return 2

    tmpdir = tempfile.mkdtemp(prefix="gskeys_")
    drv = Driver(args.adb, args.serial, tmpdir)
    if args.dry_run:
        xmltext = drv.dump()
        if not xmltext:
            print("dump 失败")
            return 2
        keys, spots = parse(xmltext)
        print("len=%d keys=%d clear=%s etPwd=%s" % (
            len(text), len(keys), "yes" if spots.get("clear") else "no",
            "yes" if spots.get("etPwd") else "no"))
        for k in sorted(keys):
            print("%s\t%d\t%d" % (k, keys[k][0], keys[k][1]))
        return 0
    return type_text(drv, text, clear=not args.no_clear)


if __name__ == "__main__":
    sys.exit(main())
