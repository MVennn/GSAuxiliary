#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
宿主契约校验 —— 「代码里认宿主的东西」与「契约清单」必须对得上。

## 为什么要有它

模块靠**宿主 App 的类名 / 资源 id / 字段名**才能工作。宿主一升级，这些名字就可能变 ——
而模块的应对方式是「候选名数组 + 多级兜底」（`docs/EXTENDING.md` §4），
所以**宿主改名不会立刻炸**，但会**悄悄退化**：某个候选不再命中，功能就少了一半。

现在没有任何机制能告诉维护者「宿主的哪个名字失效了」—— 只有用户在真机上发现
「这个功能不生效了」，再回头去猜。

这个脚本做两件事：

1. **静态双向校验**（不需要设备、不需要构建，2 秒）：
   代码里有而清单里没有 → 忘了登记；清单里有而代码里没了 → 清单失效。
2. **宿主存在性校验**（需要探针 dump）：清单里的类名 / 字段名在宿主里还在不在。

## 与其它工具的分工

- `tools/hygiene.py` —— 仓库自身的卫生（死代码 / 冗余资源 / 未用 import），**只报告**
- `tools/ci/verify_apk.py` —— **产物 APK** 的红线（dex 类定义 / 签名 / 元数据）
- `tools/ci/check_invariants.py` —— **源码级**的静态不变量（Miuix 禁用、假开关…）
- **本脚本** —— **源码级**的宿主契约（认的东西还在不在）

⚠️ **不要**把本脚本塞进 `verify_apk.py` —— 那个脚本的输入契约是「一个 APK」，
做的是产物红线；混在一起会破坏它的定位。

## 用法

```
# 静态双向校验（不需要 dump）
python3 tools/ci/check_contract.py

# 拿探针 dump 对照宿主存在性
python3 tools/ci/check_contract.py --dump tools/contract/dump/8.6.0-271-classes.txt

# 重新生成清单（改了 Targets 之后）
python3 tools/ci/check_contract.py --regen
"""

import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "io", "github", "MVennn", "GSAuxiliary")
FEATURE = os.path.join(SRC, "feature")
CONTRACT_DIR = os.path.join(ROOT, "tools", "contract")
CONTRACT_FILE = os.path.join(CONTRACT_DIR, "host-contract.json")
SCHEMA = 1

# 宿主标识的常量名形态：
#   *_ID / *_IDS / *_ID_CANDIDATES  → 资源 id
#   *_CLASS / *_CLASSES / *_CLASS_CANDIDATES → 类名
#   *_FIELD / *_FIELDS / *_FIELD_CANDIDATES → 字段名
#   *_CANDIDATES（不带上面那些后缀）→ 候选数组，元素可能是类名 / id / 字段名
#
# 排除：我们自己模块的常量（ENTRY_TAG 之类）与数值型常量。
STRING_LIT = re.compile(r"\"([^\"]*)\"")


def targets_files():
    """所有「宿主定位常量」文件。"""
    out = []
    for dirpath, _dirnames, filenames in os.walk(FEATURE):
        for fn in filenames:
            if fn.endswith("Targets.kt") or fn == "HostFields.kt":
                out.append(os.path.join(dirpath, fn))
    return sorted(out)


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


# 模块自己的命名空间：这些前缀下的东西**不是**宿主标识
OWN_PREFIXES = ("gsaux", "gszxtest", "com.github.mvennn")


def is_host_identifier(name, value):
    """这个常量是不是「宿主标识」（而不是我们自己的 tag / 桥名 / 数值 / 文案）。"""
    if not value or not isinstance(value, str):
        return False
    low = value.lower()
    # 模块自有命名空间
    for pfx in OWN_PREFIXES:
        if low.startswith(pfx):
            return False
    # 纯数字 / 布尔
    if re.match(r"^-?\d+(\.\d+)?[fFdD]?$", value):
        return False
    if value in ("true", "false"):
        return False
    # 宿主包路径（类名 / 包前缀）
    if value.startswith("com."):
        return True
    # 宿主字段名（Java / Kotlin 驼峰）：常量名以 _FIELD 结尾。
    # 例：`MODEL_FIELD = "checkCodes"`、`KX_INFO_FIELD = "m_kxChartInfo"`。
    # 这类名字含大写，不会命中下面的「资源 id 全小写」规则。
    if re.search(r"_FIELD$", name) and re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", value):
        return True
    # 资源 id：全小写 + 下划线，且**不是**我们自己的 key
    # （`adapter_key` 这类 Intent extra 的 key 是我们自己定的，不归宿主管）
    if re.match(r"^[a-z][a-z0-9_]*$", value):
        # 常量名里带 KEY / EXTRA / PARAM / TAG / NAMESPACE 的多半是我们自己的
        if re.search(r"_(KEY|EXTRA|PARAM|TAG|NAMESPACE|ACTION|CATEGORY)$", name):
            return False
        return True
    return False


def kind_of(value):
    if value.startswith("com."):
        return "class"
    return "id"


def extract(path):
    """从一个 Targets 文件里提取宿主标识。"""
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    out = []
    # 单值形态：const val X = "字面量"
    single_re = re.compile(r'const\s+val\s+([A-Z][A-Z0-9_]*)[^=\n]*=\s*"([^"]*)"')
    for m in single_re.finditer(text):
        name, value = m.group(1), m.group(2)
        if is_host_identifier(name, value):
            out.append({
                "kind": kind_of(value),
                "owner": os.path.basename(path),
                "symbol": name,
                "value": value,
            })

    # 数组形态：val X = arrayOf("a", "b") / listOf(...)
    array_re = re.compile(
        r'(?:const\s+)?val\s+([A-Z][A-Z0-9_]*)[^=\n]*=\s*'
        r'(?:arrayOf|listOf)\s*\((.*?)\)\s*(?=\n|\r\n|$)',
        re.S,
    )
    for m in array_re.finditer(text):
        name = m.group(1)
        for value in STRING_LIT.findall(m.group(2)):
            if is_host_identifier(name, value):
                out.append({
                    "kind": kind_of(value),
                    "owner": os.path.basename(path),
                    "symbol": name,
                    "value": value,
                })
    return out


def build_contract():
    entries = []
    for path in targets_files():
        entries.extend(extract(path))
    # 去重（同一个 owner+symbol+value 只留一条）
    seen = set()
    uniq = []
    for e in entries:
        k = (e["owner"], e["symbol"], e["value"])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(e)
    uniq.sort(key=lambda e: (e["owner"], e["symbol"], e["value"]))
    return {
        "schema": SCHEMA,
        "host": {
            "package": "com.guosen.android",
            "versionName": "8.6.0",
            "versionCode": 271,
        },
        "entries": uniq,
    }


def load_contract():
    if not os.path.exists(CONTRACT_FILE):
        return None
    with open(CONTRACT_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def regen():
    contract = build_contract()
    os.makedirs(CONTRACT_DIR, exist_ok=True)
    with open(CONTRACT_FILE, "w", encoding="utf-8") as f:
        json.dump(contract, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("[OK] 已重新生成 %s（%d 条）" % (rel(CONTRACT_FILE), len(contract["entries"])))
    return 0


def static_check(contract):
    """双向校验：代码 ↔ 清单。"""
    problems = []

    code_entries = build_contract()["entries"]
    listed = {(e["owner"], e["symbol"], e["value"]) for e in contract["entries"]}
    actual = {(e["owner"], e["symbol"], e["value"]) for e in code_entries}

    for e in code_entries:
        k = (e["owner"], e["symbol"], e["value"])
        if k not in listed:
            problems.append(
                "[未登记] %s 的 `%s` = \"%s\" —— 代码里认它但清单里没有。"
                "宿主改版后无从查起，请登记（改完跑 --regen）"
                % (e["owner"], e["symbol"], e["value"])
            )

    listed_entries = {(e["owner"], e["symbol"], e["value"]): e for e in contract["entries"]}
    for k, e in listed_entries.items():
        if k not in actual:
            problems.append(
                "[清单失效] %s 的 `%s` = \"%s\" —— 清单里有但代码里已经没有了，"
                "请删掉这条（或代码改回来了）" % (e["owner"], e["symbol"], e["value"])
            )

    return problems


def host_check(contract, dump_path):
    """对照探针 dump：清单里的宿主标识还在不在。

    ⚠️ **dump 必须覆盖「宿主全部类」，否则结果没有意义** ——
    探针（`com.wb.probe`）是**按需 dump 某个类**的，一次 dump 往往只覆盖一个页面
    （实测：dump 个股详情页的 K 线区时，只有 20/29 个契约类名出现在里面，
    剩下 9 个只是**没被那次 dump 扫到**，并不是失效了）。

    所以这里的判定分两级：
      - **「在」** → 确认有效（说明这个候选名现在还认得到）；
      - **「不在」** → **只提示、不报错**（dump 覆盖不全时无法区分「失效」与「没扫到」）。

    真正要判「失效」，得先对宿主做一次**全量**类遍历（探针的 `CLASS` 逐个来，
    或者用 `aapt2`/dex 工具扫一遍宿主的 classes.dex）。
    """
    if not dump_path:
        return None
    if not os.path.exists(dump_path):
        # ⚠️ **报错而不是静默跳过** —— CI 里传的是硬编码路径，路径写错就静默退化，
        # 变成「永远绿」的假检查（那还不如没有）。
        print(
            "[X] dump 文件不存在：%s —— 宿主存在性校验无法进行。"
            "（它要么不存在，要么路径写错了；两种都得让人知道）" % dump_path
        )
        sys.exit(1)
    with open(dump_path, "r", encoding="utf-8", errors="replace") as f:
        dump = f.read()
    problems = []
    missing = []
    for e in contract["entries"]:
        if e["kind"] != "class":
            continue
        # ⚠️ **包前缀常量不是类名**（以 "." 结尾，如 `ACTIVITY_PREFIX`）——
        #    它没有「简单名」，也不该参与「类名还在不在」的判断。
        if e["value"].endswith("."):
            continue
        # 简单名也认（dump 里可能是全名也可能是简名）
        simple = e["value"].rsplit(".", 1)[-1]
        # ⚠️ **简单名为空必须跳过** —— 包前缀常量（如 `ACTIVITY_PREFIX =
        # "com.guosen.quotation.aistare.ui."`）的简单名是空串，而**空串是任何文本的子串**
        # → 不跳过的话它会「恒判为存在」，是这条检查里最隐蔽的一个假阳性。
        if e["value"] not in dump and (not simple or simple not in dump):
            missing.append(e)
    total = sum(1 for e in contract["entries"] if e["kind"] == "class")
    hit = total - len(missing)
    print("[i] dump 命中 %d/%d 个契约类名（确认这些候选名现在还认得到）" % (hit, total))
    if missing:
        # ⚠️ **只提示，不报错** —— dump 覆盖不全时无法区分「宿主改名了」与「这次没扫到」。
        # 真的失效了会表现为「用户在真机上发现这个功能不生效」，那时候再逐个核对。
        print("[!] 有 %d 个契约类名没在 dump 里出现 —— **这不代表失效**，"
              "只说明这份 dump 没覆盖到它们（探针是按需 dump 某个类的）：" % len(missing))
        for e in missing:
            print("      %s 的 `%s` = \"%s\"" % (e["owner"], e["symbol"], e["value"]))
        print("    要真正判定失效，需要对宿主做一次**全量**类遍历。")
    return problems


def main():
    ap = argparse.ArgumentParser(description="宿主契约校验")
    ap.add_argument("--dump", help="探针 dump 文件（对照宿主存在性）")
    ap.add_argument("--regen", action="store_true", help="重新生成契约清单")
    args = ap.parse_args()

    if args.regen:
        return regen()

    contract = load_contract()
    if contract is None:
        print("[!] 契约清单不存在：%s" % rel(CONTRACT_FILE))
        print("    先跑一次：python3 tools/ci/check_contract.py --regen")
        return 1

    problems = static_check(contract)

    # ⚠️ **先打印静态校验的结果，再做宿主存在性校验**（2026-10-20 三次复核时发现）：
    # 后者遇到 dump 缺失会直接 `sys.exit(1)`，那时静态校验的清单**一条都没打印** ——
    # 两件事一起坏时，维护者只看到「dump 不存在」，得修一件再跑一次。
    if not problems:
        print("[OK] 静态双向校验通过（代码 ↔ 清单）")

    host_problems = host_check(contract, args.dump)
    if host_problems is not None:
        problems.extend(host_problems)
    else:
        print("[!] 未提供 --dump —— 跳过宿主存在性校验（只做了静态双向校验）")

    if not problems:
        print("[OK] 宿主契约校验通过（%d 条登记）" % len(contract["entries"]))
        return 0

    print("=" * 70)
    print("宿主契约校验：发现 %d 条" % len(problems))
    print("=" * 70)
    for p in problems:
        print("  " + p)
    print("=" * 70)
    print("处理办法：")
    print("  · 宿主改版后某个名字失效 → 在对应 XxxTargets.kt 的**候选数组**里加新名字，")
    print("    再跑 --regen 更新清单（数组里把新名字放第一位）。")
    print("  · 代码里删了某个常量 → 清单里也删（跑 --regen）。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
