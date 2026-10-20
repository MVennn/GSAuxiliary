#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
免责声明**多副本同步**核对。

## 为什么需要它

免责全文同时存在**三个地方**，任何一处漏改，用户看到的就是不同版本的承诺：

| # | 位置 | 角色 |
|---|---|---|
| 1 | `docs/DISCLAIMER.md` | 中文**规范源**（GitHub 网页锚点） |
| 2 | `app/src/main/res/values/strings.xml` 的 `disclaimer_full` | App 内渲染源（中文） |
| 3 | `docs/DISCLAIMER.en.md` | 英文**规范源** |
| 4 | `app/src/main/res/values-en/strings.xml` 的 `disclaimer_full` | App 内渲染源（英文） |

`docs/MAINTENANCE.md` 定了「改任何一条都必须两处一起改，**没有例外**」——
但这条规矩之前只靠人记，于是真的漂了：

* 中文 App 那份的引号退回成直引号、末句少一段加粗（网页版 2026-10-17 加的）；
* **英文 App 那份整整少了第四段条款**（第八~第十一条），
  而且还留着一句**已经不成立的话** ——「contains no networking components」，
  而模块 3.x 明明有 HTTP 取数。中文侧早已改成「不含统计分析、广告或数据回传组件」。

法律文本漏一句不是小事：这个脚本就是让这种漏改**当场变红**。

## 判定口径

1. **逐字相同**：资源里的值解转义后，必须与同名 md 规范源**一字不差**；
2. **结构对齐**：中英两版的标题数、条款数、条目数、加粗段数、斜体段数必须一致 ——
   翻译腔允许句子不同，但**不许少一条**。

页面级说明行（紧跟在标题后的那句话，见 `NOTE_PREFIXES`）是网页自己的话，
不属于承诺正文，比对前先剥掉。

## 用法

```
python3 tools/ci/check_disclaimer_sync.py          # 只检查（CI 用这个）
python3 tools/ci/check_disclaimer_sync.py --write   # 从 md 规范源重新生成资源值
```

`--write` 会改写两个 `strings.xml` 里的 `disclaimer_full`（仍保持**单行 + \\n 转义**
的形态 —— 别改成多行，`aapt2` 会报错）。

退出码非 0 = 有副本没同步。
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

ZH_MD = os.path.join(ROOT, "docs", "DISCLAIMER.md")
EN_MD = os.path.join(ROOT, "docs", "DISCLAIMER.en.md")
ZH_RES = os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml")
EN_RES = os.path.join(ROOT, "app", "src", "main", "res", "values-en", "strings.xml")

# 紧跟在标题后的**页面级说明**（网页版才有，App 里不该有）。
# 比对正文前先剥掉这一行 —— 它是给网页读者看的引导，不是承诺的一部分。
NOTE_PREFIXES = (
    "> 本文档与模块 App",
    "> This document is identical to",
)

# 字符串资源里那几行「与条款一一对应」的结构指标。
STRUCTURE_LABELS = (
    ("一级标题", r"^#\s+\S"),
    ("二级条款", r"^##\s+\S"),
    ("无序条目", r"^\s*[-*]\s+\S"),
    ("加粗段", r"\*\*[^*]+\*\*"),
    ("斜体段", r"(?<!\*)\*[^*\n]+\*(?!\*)"),
)


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def strip_notes(text):
    """剥掉页面级说明行。"""
    keep = []
    for line in text.split("\n"):
        if any(line.startswith(p) for p in NOTE_PREFIXES):
            continue
        keep.append(line)
    return "\n".join(keep)


def extract_resource(xml_text, key="disclaimer_full"):
    """从 strings.xml 里取出资源值，并解转义回运行期真实文本。"""
    m = re.search(r'<string name="%s">(.*?)</string>' % re.escape(key), xml_text, re.S)
    if not m:
        return None
    raw = m.group(1)
    # 先处理反斜杠转义，再处理 XML 实体 —— 顺序反了会把 &amp;lt; 拆坏
    s = raw.replace("\\\\", "\x00")
    s = s.replace("\\n", "\n").replace("\\t", "\t")
    s = s.replace('\\"', '"').replace("\\'", "'")
    s = s.replace("\x00", "\\")
    s = s.replace("&quot;", '"').replace("&apos;", "'")
    s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    return s


def escape_resource(text):
    """反向：运行期文本 -> strings.xml 里的单行形态。"""
    s = text.replace("&", "&amp;").replace("<", "&lt;")
    s = s.replace('"', '\\"').replace("'", "\\'")
    s = s.replace("\n", "\\n").replace("\t", "\\t")
    return s


def structure(text):
    counts = []
    for label, pattern in STRUCTURE_LABELS:
        counts.append((label, len(re.findall(pattern, text, re.M))))
    return counts


def write_resource(path, text):
    """把资源值写回 strings.xml（保持单行形态）。"""
    original = read(path)
    value = escape_resource(text)
    new, n = re.subn(
        r'(<string name="disclaimer_full">)(.*?)(</string>)',
        lambda m: m.group(1) + value + m.group(3),
        original,
        count=1,
        flags=re.S,
    )
    if n != 1:
        print("[跳过] %s 里找不到 disclaimer_full 条目" % os.path.relpath(path, ROOT))
        return False
    if new != original:
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(new)
        print("[已生成] %s" % os.path.relpath(path, ROOT))
    else:
        print("[无变化] %s" % os.path.relpath(path, ROOT))
    return True


def first_diff(a, b):
    """给一句能直接定位的人话：两边第一处不同在哪。"""
    la, lb = a.split("\n"), b.split("\n")
    for i in range(max(len(la), len(lb))):
        x = la[i] if i < len(la) else "<缺这一行>"
        y = lb[i] if i < len(lb) else "<缺这一行>"
        if x != y:
            return "第 %d 行：\n      规范源：%s\n      资源里：%s" % (
                i + 1, x[:120], y[:120],
            )
    return "（长度不同）"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true",
                    help="从 md 规范源重新生成两个 strings.xml 的 disclaimer_full")
    args = ap.parse_args()

    missing = [p for p in (ZH_MD, EN_MD, ZH_RES, EN_RES) if not os.path.exists(p)]
    if missing:
        for p in missing:
            print("[缺文件] %s" % os.path.relpath(p, ROOT))
        return 1

    zh_md = strip_notes(read(ZH_MD))
    en_md = strip_notes(read(EN_MD))

    if args.write:
        write_resource(ZH_RES, zh_md)
        write_resource(EN_RES, en_md)
        print("（已从 docs/DISCLAIMER*.md 重新生成资源值，接着跑一次核对）")
        print()

    zh_res = extract_resource(read(ZH_RES))
    en_res = extract_resource(read(EN_RES))
    if zh_res is None or en_res is None:
        print("[缺资源] 某个 strings.xml 里没有 disclaimer_full")
        return 1

    problems = []

    # —— 规则 1：中文 —— md 规范源 vs App 资源值
    if zh_md != zh_res:
        problems.append(
            "[中文副本不同步] docs/DISCLAIMER.md 与 values/strings.xml 的 disclaimer_full 不一致\n      %s"
            % first_diff(zh_md, zh_res)
        )

    # —— 规则 2：英文 —— md 规范源 vs App 资源值
    if en_md != en_res:
        problems.append(
            "[英文副本不同步] docs/DISCLAIMER.en.md 与 values-en/strings.xml 的 disclaimer_full 不一致\n      %s"
            % first_diff(en_md, en_res)
        )

    # —— 规则 3：中英结构对齐（不许少一条）
    zh_struct = structure(zh_md)
    en_struct = structure(en_md)
    for (zl, zc), (el, ec) in zip(zh_struct, en_struct):
        if zc != ec:
            problems.append(
                "[中英结构不齐] %s：中文 %d 处，英文 %d 处 —— 英文那份少条款/少条目，"
                "照中文补齐（或确认是有意为之后改本检查）" % (zl, zc, ec)
            )

    if not problems:
        print("[OK] 免责声明三份副本同步：中文 %d 条款 / 英文 %d 条款，结构一致"
              % (zh_struct[1][1], en_struct[1][1]))
        return 0

    print("=" * 70)
    print("免责声明副本同步核对：发现 %d 处问题" % len(problems))
    print("=" * 70)
    for p in problems:
        print("  " + p)
    print("=" * 70)
    print("改完一条，另一条也要一起改（docs/MAINTENANCE.md §3 的规矩）。")
    print("要从 md 规范源重新生成资源值：python3 tools/ci/check_disclaimer_sync.py --write")
    return 1


if __name__ == "__main__":
    sys.exit(main())
