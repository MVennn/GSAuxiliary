#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""version.py —— 读 app/build.gradle.kts 里的 versionCode / versionName。

版本号的**唯一来源**就是 app/build.gradle.kts（见 docs/BUILD.md），
所以 CI 里所有需要版本号的地方都从这里取，不另开一处能改偏。

用法
----
    python tools/ci/version.py            # 输出 vc=34  vn=2.14.0
    python tools/ci/version.py --tag      # 输出 34-2.14.0（LSPosed 约定的 tag 形态）
    python tools/ci/version.py --shell    # 输出 VC=34 / VN=2.14.0 / TAG=34-2.14.0

前两种是给 GitHub Actions 的 `>> "$GITHUB_OUTPUT"` 直接吃的；
第三种是给 shell `eval` 用的。
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")


def read_version():
    src = open(GRADLE, encoding="utf-8").read()
    vc = re.search(r"versionCode\s*=\s*(\d+)", src)
    vn = re.search(r'versionName\s*=\s*"([^"]+)"', src)
    if not vc or not vn:
        sys.exit("[X] 读不到 versionCode / versionName：%s" % GRADLE)
    return vc.group(1), vn.group(1)


def main(argv):
    mode = argv[1] if len(argv) > 1 else ""
    vc, vn = read_version()
    tag = "%s-%s" % (vc, vn)

    if mode == "--tag":
        print(tag)
    elif mode == "--shell":
        print("VC=%s" % vc)
        print("VN=%s" % vn)
        print("TAG=%s" % tag)
    else:
        print("vc=%s" % vc)
        print("vn=%s" % vn)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
