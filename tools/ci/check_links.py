#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_links.py —— 文档链接与索引的一致性检查。

查三件事：

1. **相对链接是否断**（目标文件不存在 / 锚点不存在）
   —— 之前 Release 说明里的免责链接写成了 GitHub 的 `../../blob/main/...`，
   在 GitLab 上点不开，而本地 YAML 解析、Markdown 渲染都看不出问题。
2. **docs/ 里的每篇是否都进了 docs/README.md 索引**
   —— 索引是 docs/ 的入口（见 DOC-RULES.md §7）。
3. **有没有孤儿文档**（没有任何别的文档链到它）
   —— DOC-RULES.md §7：新增文档至少要被一处既有文档引用。

口径（刻意收窄，免得满屏假阳性）：

- 只查 **git 跟踪的** Markdown —— 本地临时文件（AGENTS.md / PROGRESS.md）不算；
- 仓库根级文件（README / CONTRIBUTING / SECURITY / CHANGELOG…）是**入口**，
  不要求被别人链到；孤儿检查只看 `docs/` 与子目录里的文档；
- 反引号里写的路径只查「仓库根相对 + 已知顶层目录」的那类
  （`docs/…` / `tools/…` / `assets/…` / `app/src/…` / `xposed-repo/…`），
  资源目录内部路径（`values/strings.xml`）、APK 内部路径、SDK 路径一律不管；
- CHANGELOG.md 是**历史记录**，里面提到的文件可能早就合走了 —— 不查它的路径。

用法：
    python tools/ci/check_links.py            # 检查全部
    python tools/ci/check_links.py --verbose  # 把文件清单也打出来

退出码：0 = 通过；1 = 有问题。
"""

import argparse
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))

# 这些目录不参与检查
SKIP_DIRS = {
    ".git", ".work", ".codegraph", ".gradle", ".kotlin", "build",
    "node_modules", "__pycache__", ".idea", ".vscode",
}
# 仓库根级文件是入口，不参与「孤儿」检查
ROOT_FILES = {
    "README.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md",
    "NOTICE.md", "LICENSE.md", "AGENTS.md", "PROGRESS.md",
}
# 反引号路径检查：只查这些顶层目录，且按**仓库根**解析
CODE_PATH_PREFIXES = ("docs/", "tools/", "assets/", "xposed-repo/", "app/src/")
# 这些文件是历史记录，提到已删除的文件不算问题
CODE_PATH_SKIP_FILES = ("CHANGELOG.md",)

# Markdown 链接：[文字](目标) —— 目标里可能带 title 与尖括号
LINK_RE = re.compile(r"\[([^\]]*)\]\(\s*<?([^)\s>]+)>?(?:\s+[\"'][^)]*[\"'])?\s*\)")
# 反引号里的仓库内路径
CODE_PATH_RE = re.compile(r"`([A-Za-z0-9_./-]+\.(?:md|py|sh|yml|yaml|kts|json|txt|xml|properties|svg))`")


def tracked_md_files():
    """git 跟踪的 Markdown；git 不可用时退回文件系统遍历。"""
    try:
        out = subprocess.run(["git", "ls-files", "*.md"], cwd=ROOT, text=True,
                             capture_output=True, timeout=30)
        if out.returncode == 0 and out.stdout.strip():
            return sorted(p.replace("\\", "/") for p in out.stdout.split() if p.endswith(".md"))
    except (OSError, subprocess.SubprocessError):
        pass
    files = []
    for base, dirs, names in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS and not d.startswith(".git")]
        for n in names:
            if n.endswith(".md"):
                files.append(os.path.relpath(os.path.join(base, n), ROOT).replace("\\", "/"))
    return sorted(files)


def slug(text):
    """GitHub / GitLab 的锚点算法（近似）：小写、去标点、空格转连字符、保留中日韩字符。"""
    text = re.sub(r"`", "", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"[^\w一-鿿 -]", "", text, flags=re.U)
    # GitHub / GitLab 是「每个空格换一个连字符」，**不合并连续空格**
    # （所以 "驱动 + 幂等" 会变成 "驱动--幂等"）—— 这条对不对得上，决定锚点检查准不准
    return text.strip().lower().replace(" ", "-")


def anchors(text):
    """一篇文档里所有的锚点（同名标题加 -1、-2 后缀，与 GitLab 一致）。"""
    out, seen = set(), {}
    for m in re.finditer(r"^(#{1,6})\s+(.+?)\s*$", text, re.M):
        s = slug(m.group(2))
        if s in seen:
            seen[s] += 1
            s = "%s-%d" % (s, seen[s])
        else:
            seen[s] = 0
        out.add(s)
    return out


def main():
    ap = argparse.ArgumentParser(description="文档链接 / 索引 / 孤儿检查")
    ap.add_argument("--verbose", action="store_true", help="把文件清单也打出来")
    args = ap.parse_args()

    files = tracked_md_files()
    if not files:
        print("[X] 一篇 Markdown 都没找到 —— 路径不对？")
        return 1
    cache = {}
    for p in files:
        fp = os.path.join(ROOT, p)
        if os.path.exists(fp):
            cache[p] = open(fp, encoding="utf-8").read()
    anc = {p: anchors(t) for p, t in cache.items()}

    broken, bad_anchor = [], []
    link_count = 0
    referenced = set()

    for p, text in cache.items():
        base = os.path.dirname(p)
        for m in LINK_RE.finditer(text):
            target = m.group(2).strip()
            if target.startswith(("http://", "https://", "mailto:", "tel:")):
                continue
            link_count += 1
            if target.startswith("#"):
                a = target[1:]
                if a and a not in anc[p]:
                    bad_anchor.append((p, target))
                continue
            path, _, anchor = target.partition("#")
            if not path:
                continue
            tgt = os.path.normpath(os.path.join(base, path)).replace("\\", "/")
            if not os.path.exists(os.path.join(ROOT, tgt)):
                broken.append((p, target))
                continue
            referenced.add(tgt)
            if anchor and tgt.endswith(".md") and tgt in anc and anchor not in anc[tgt]:
                bad_anchor.append((p, target))

    # 反引号里写的仓库根相对路径（只查已知顶层目录）
    missing_code = []
    for p, text in cache.items():
        if os.path.basename(p) in CODE_PATH_SKIP_FILES:
            continue
        for m in CODE_PATH_RE.finditer(text):
            path = m.group(1)
            if not path.startswith(CODE_PATH_PREFIXES):
                continue
            if not os.path.exists(os.path.join(ROOT, path)):
                missing_code.append((p, path))

    # 索引覆盖：docs/ 下每篇都要在 docs/README.md 里
    idx_path = "docs/README.md"
    idx_text = cache.get(idx_path, "")
    idx_targets = set()
    for m in LINK_RE.finditer(idx_text):
        t = m.group(2).strip()
        if t.startswith(("http://", "https://")):
            continue
        idx_targets.add(os.path.normpath(os.path.join("docs", t.partition("#")[0])).replace("\\", "/"))
    not_indexed = [p for p in files
                   if p.startswith("docs/") and p != idx_path
                   and os.path.basename(p) != "README.md"
                   and not p.startswith("docs/release-notes/")
                   and p not in idx_targets]

    # 孤儿：docs/ 与子目录里的文档，得有别的文档链到它
    orphans = [p for p in files
               if p != idx_path
               and os.path.basename(p) not in ROOT_FILES
               and not p.startswith("docs/release-notes/")
               and p not in referenced]

    if broken or bad_anchor or missing_code or not_indexed or orphans:
        print("[X] 文档检查没过：")
        if broken:
            print("  断链 %d 处（目标不存在）：" % len(broken))
            for p, t in broken[:20]:
                print("    %s -> %s" % (p, t))
        if bad_anchor:
            print("  锚点失效 %d 处：" % len(bad_anchor))
            for p, t in bad_anchor[:20]:
                print("    %s -> %s" % (p, t))
        if missing_code:
            print("  反引号里写的路径不存在 %d 处：" % len(missing_code))
            for p, t in missing_code[:20]:
                print("    %s -> %s" % (p, t))
        if not_indexed:
            print("  没进 docs/README.md 索引 %d 篇：" % len(not_indexed))
            for p in not_indexed[:20]:
                print("    %s" % p)
        if orphans:
            print("  孤儿文档 %d 篇（没有任何文档链到它）：" % len(orphans))
            for p in orphans[:20]:
                print("    %s" % p)
        return 1

    print("[OK] 文档检查通过：%d 篇 Markdown、%d 条相对链接 —— 0 断链 / 0 失效锚点 / "
          "0 索引漏项 / 0 孤儿" % (len(cache), link_count))
    if args.verbose:
        for p in sorted(cache):
            print("    %s" % p)
    return 0


if __name__ == "__main__":
    sys.exit(main())
