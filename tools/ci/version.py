#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""version.py —— 读 app/build.gradle.kts 里的 versionCode / versionName。

版本号的**唯一来源**就是 app/build.gradle.kts（见 docs/BUILD.md），
所以 CI 里所有需要版本号的地方都从这里取，不另开一处能改偏。

用法
----
    python tools/ci/version.py                  # vc=34  vn=2.14.0（给 >> $GITHUB_OUTPUT）
    python tools/ci/version.py --tag            # 34-2.14.0（LSPosed 约定的 tag 形态）
    python tools/ci/version.py --shell          # VC= / VN= / TAG= 三行（给 shell eval 用）
    python tools/ci/version.py --check-tag 34-2.14.0
                                                # 校验 tag 与版本号一致；不一致退出 1

前两种是给 GitHub Actions 的 `>> "$GITHUB_OUTPUT"` 直接吃的；
`--shell` 是给 shell `eval` 用的；`--check-tag` 是 release.yml 与本地发版前
共用的同一份判断 —— 免得「CI 校验一套、人眼校验另一套」。

退出码：0 = 成功；1 = 读不到版本号 / tag 对不上；2 = 参数写错。
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")

# 只认「整行就是一条赋值」的形式：注释掉的、写在字符串里的都不算。
# 行尾允许 `// 说明`。
VC_RE = re.compile(r"^\s*versionCode\s*=\s*(\d+)\s*(?://.*)?$", re.M)
VN_RE = re.compile(r'^\s*versionName\s*=\s*"([^"]+)"\s*(?://.*)?$', re.M)


def read_version():
    try:
        with open(GRADLE, encoding="utf-8") as f:
            src = f.read()
    except OSError as e:
        sys.exit("[X] 读不了 %s：%s" % (GRADLE, e))

    vc = VC_RE.search(src)
    vn = VN_RE.search(src)
    if not vc or not vn:
        sys.exit("[X] 在 %s 里找不到 versionCode / versionName 的整行赋值\n"
                 "    期望形如：\n"
                 "        versionCode = 34\n"
                 '        versionName = "2.14.0"' % GRADLE)
    return vc.group(1), vn.group(1)


def main(argv):
    ap = argparse.ArgumentParser(
        prog="version.py",
        description="读 app/build.gradle.kts 的版本号（CI 里唯一来源）")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--tag", action="store_true",
                   help="输出 LSPosed 约定的 tag：versionCode-versionName")
    g.add_argument("--shell", action="store_true",
                   help="输出 VC= / VN= / TAG= 三行，给 shell eval")
    g.add_argument("--check-tag", metavar="TAG",
                   help="校验给定 tag 与版本号一致，不一致退出 1")
    args = ap.parse_args(argv[1:])

    vc, vn = read_version()
    tag = "%s-%s" % (vc, vn)

    if args.tag:
        print(tag)
        return 0

    if args.shell:
        print("VC=%s" % vc)
        print("VN=%s" % vn)
        print("TAG=%s" % tag)
        return 0

    if args.check_tag is not None:
        got = args.check_tag.strip()
        if got == tag:
            print("[OK] tag %s 与 app/build.gradle.kts 的 versionCode-versionName 一致" % got)
            return 0
        print("[X] tag 与版本号对不上：")
        print("      tag                  : %s" % (got or "(空)"))
        print("      app/build.gradle.kts : %s   （versionCode %s / versionName %s）"
              % (tag, vc, vn))
        print("    先改 app/build.gradle.kts 的 versionCode / versionName，再打 tag。")
        return 1

    # 默认：GITHUB_OUTPUT 形态
    print("vc=%s" % vc)
    print("vn=%s" % vn)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
