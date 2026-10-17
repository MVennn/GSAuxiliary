#!/usr/bin/env python3
"""代码卫生扫描器（大扫除用的只读报告工具）。

用法::

    python tools/hygiene.py all       # 全部跑一遍（等价于 res decls members imports junk）
    python tools/hygiene.py res       # 没被引用的字符串 / 数组 / 颜色 / 尺寸 / 样式
    python tools/hygiene.py decls     # 没人引用的顶层声明（类、对象、顶层函数与常量）
    python tools/hygiene.py members   # 文件内只有声明、没有引用的 private 成员
    python tools/hygiene.py imports   # 没被用到的 import
    python tools/hygiene.py junk      # 磁盘垃圾清单（构建产物 / 临时目录 / 旧包）

**这个脚本只报告、不删任何东西**。它给的是「候选名单」，每一条都要人（或 AI）
用 `git grep` 复核后再决定 —— 正则扫描认不出反射、注解处理器、build.gradle 里
拼出来的类名，这些都会出现在名单里但其实是活的。误报的典型：

* `DebugConfigProvider` 这类由 AndroidManifest / META-INF 按名字实例化的入口；
* `Res` 之外还有 `assets/`、`proguard-rules.pro` 里的 keep 规则引用；
* 只在 KDoc `[Foo]` 里被提到的类（脚本按「出现了」算使用，不会误报）。

判定口径与清理红线见 `docs/MAINTENANCE.md`。
"""

import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main")
KOTLIN = os.path.join(SRC, "java")
RES = os.path.join(SRC, "res")

# 这些名字是**委托运算符**，靠 `by` 隐式使用，扫不出显式调用，一律不算未用
DELEGATE_OPS = {"getValue", "setValue", "provideDelegate", "get", "set"}

# 磁盘垃圾：目录（整棵可删，都是 gitignored 的可再生产物）
JUNK_DIRS = [
    "app/build",
    "build",
    ".gradle",
    ".kotlin",
    "tools/probe/build",
    "tools/probe/out",
]

# .work/ 里要留下来的东西（见 docs/MAINTENANCE.md §5）
WORK_KEEP = {"lsposed-repo", "apk", "log.mark", "wifi_serial"}

JUNK_SUFFIX = (".apk", ".png", ".log", ".patch", ".orig", ".rej", ".bak", ".tmp")


def kt_files():
    return sorted(glob.glob(os.path.join(KOTLIN, "**", "*.kt"), recursive=True))


def read(path):
    with open(path, encoding="utf-8", errors="ignore") as f:
        return f.read()


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


def human(n):
    for unit in ("B", "K", "M", "G"):
        if n < 1024 or unit == "G":
            return f"{n:.1f}{unit}" if unit != "B" else f"{n}B"
        n /= 1024


def scan_res():
    """资源声明了但没有任何 R.xxx / @xxx 引用。"""
    declared = {}
    for path in glob.glob(os.path.join(RES, "values*", "*.xml")):
        text = read(path)
        for tag in ("string", "string-array", "color", "dimen", "style",
                    "integer", "bool", "plurals", "array", "attr"):
            for name in re.findall(r'<%s name="([^"]+)"' % tag, text):
                declared.setdefault((tag, name), []).append(os.path.basename(path))

    blob = []
    for root, _, files in os.walk(os.path.join(ROOT, "app", "src")):
        for f in files:
            if f.endswith((".kt", ".xml", ".java", ".pro")):
                blob.append(read(os.path.join(root, f)))
    blob = "\n".join(blob)

    found = []
    for (tag, name), where in sorted(declared.items()):
        rtag = "array" if tag == "string-array" else tag
        if re.search(r"R\.%s\.%s\b" % (rtag, name), blob):
            continue
        if re.search(r"@%s/%s\b" % (rtag, name), blob):
            continue
        found.append((tag, name, where))
    return found


def scan_decls():
    """顶层声明在全文里除了自己那一行没有任何引用。"""
    texts = {p: read(p) for p in kt_files()}
    pattern = re.compile(
        r"^(internal |private |public |)?(?:@\w+(\([^)]*\))?\s*)*"
        r"(class|object|interface|fun|val|var|typealias|data class|enum class|"
        r"sealed class|abstract class)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)",
        re.M,
    )
    found = []
    for path, text in texts.items():
        for m in pattern.finditer(text):
            kind, name = m.group(3), m.group(4)
            if name in ("class", "object", "fun", "val", "var"):
                continue
            refs = 0
            for other, otext in texts.items():
                c = len(re.findall(r"\b%s\b" % re.escape(name), otext))
                if other == path:
                    c -= 1  # 扣掉声明自己那一处
                refs += max(c, 0)
            if refs == 0:
                found.append(((m.group(1) or "public").strip(), kind, name, rel(path)))
    return sorted(found)


def scan_members():
    """private 成员在自己文件里只出现一次（= 没人调用）。"""
    found = []
    for path in kt_files():
        text = read(path)
        for kind, pat in (
            ("fun", r"^\s*private\s+(?:inline\s+|suspend\s+|const\s+)*fun\s+"
                    r"(?:<[^>]*>\s*)?(?:[\w.<>?,]+\.)?(\w+)\s*\("),
            ("val", r"^\s*private\s+(?:const\s+)?val\s+(\w+)"),
        ):
            for m in re.finditer(pat, text, re.M):
                name = m.group(1)
                if len(re.findall(r"\b%s\b" % re.escape(name), text)) <= 1:
                    found.append((kind, name, rel(path)))
    return sorted(found, key=lambda x: x[2])


def scan_imports():
    """import 的短名在文件正文里一次都没出现。"""
    found = []
    for path in kt_files():
        text = read(path)
        lines = text.split("\n")
        body = "\n".join(l for l in lines if not l.startswith("import "))
        for line in lines:
            if not line.startswith("import "):
                continue
            imp = line[7:].strip()
            if imp.endswith(".*"):
                continue
            name = imp.split(" as ")[-1].strip() if " as " in imp else imp.split(".")[-1]
            if name in DELEGATE_OPS:
                continue
            if not re.search(r"\b%s\b" % re.escape(name), body):
                found.append((rel(path), imp))
    return found


def scan_junk():
    """磁盘上可再生的垃圾（只报告，不删）。"""
    rows = []
    for d in JUNK_DIRS:
        p = os.path.join(ROOT, d)
        if os.path.isdir(p):
            rows.append((d + "/", _du(p)))

    work = os.path.join(ROOT, ".work")
    if os.path.isdir(work):
        for name in sorted(os.listdir(work)):
            if name in WORK_KEEP:
                continue
            p = os.path.join(work, name)
            rows.append((".work/" + name + ("/" if os.path.isdir(p) else ""), _du(p)))

    for base in (ROOT, work):
        if not os.path.isdir(base):
            continue
        for name in sorted(os.listdir(base)):
            p = os.path.join(base, name)
            if os.path.isfile(p) and name.lower().endswith(JUNK_SUFFIX):
                rows.append((rel(p), _du(p)))
    return rows


def _du(path):
    if os.path.isfile(path):
        return os.path.getsize(path)
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


def section(title):
    print()
    print("=" * 70)
    print(title)
    print("=" * 70)


def main():
    what = (sys.argv[1] if len(sys.argv) > 1 else "all").lower()
    jobs = ["res", "decls", "members", "imports", "junk"] if what == "all" else [what]

    for job in jobs:
        if job == "res":
            section("① 没被引用的资源（R.string / @string 都找不到）")
            rows = scan_res()
            for tag, name, where in rows:
                print(f"  {tag:12} {name:44} {','.join(where)}")
            print(f"  共 {len(rows)} 条（0 说明文案资源没有冗余）")
        elif job == "decls":
            section("② 没人引用的顶层声明（反射 / manifest 入口会误报）")
            rows = scan_decls()
            for vis, kind, name, path in rows:
                print(f"  {vis:8} {kind:10} {name:38} {path}")
            print(f"  共 {len(rows)} 条")
        elif job == "members":
            section("③ 文件内只声明、没调用的 private 成员")
            rows = scan_members()
            for kind, name, path in rows:
                print(f"  {kind:6} {name:44} {path}")
            print(f"  共 {len(rows)} 条")
        elif job == "imports":
            section("④ 没被用到的 import")
            rows = scan_imports()
            for path, imp in rows:
                print(f"  {path}: {imp}")
            print(f"  共 {len(rows)} 条")
        elif job == "junk":
            section("⑤ 磁盘垃圾（可再生产物，删前先看 §5 红线）")
            rows = scan_junk()
            total = 0
            for name, size in sorted(rows, key=lambda x: -x[1]):
                print(f"  {human(size):>9}  {name}")
                total += size
            print(f"  共 {len(rows)} 项，合计 {human(total)}")
        else:
            print(__doc__)
            return

    print()
    print("提醒：以上是候选名单，不是结论。删之前逐条 git grep 复核，删完必须编译。")
    print("口径与红线见 docs/MAINTENANCE.md。")


if __name__ == "__main__":
    main()
