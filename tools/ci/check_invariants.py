#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静态不变量扫描 —— 把「文档里写死的规矩」变成**能在 CI 里跑的检查**。

## 为什么要有它

这个项目做过的四轮审查，**每一轮"优化"都引入了新 bug**，而当时没有任何自动化手段
能在提交前抓住它们 —— 全靠真机人工验兜底。

## 设计原则：宁少报，不误报

一个误报率高的检查器**比没有更糟** —— 人会习惯性地忽略它，红了也不看。
所以这里的每条规则都要求**高置信度**：
宁可漏掉一个边缘情况，也不报一条需要人去分辨真假的。

宁可**只报 5 条真问题**，也不要报 50 条里 45 条是误报。

## 覆盖范围

| 教训 | 本脚本能抓吗 |
|---|---|
| Miuix `OverlayDialog` 一点就闪退 | ✅ 禁用清单（精确匹配） |
| 缺 `rememberSaveable` → 翻屏丢状态 | ✅ 关键状态逐个查 |
| 拿自己的类名当判据，被 R8 打穿 | ✅ 只查**模块自己的**类名 |
| `isEnabled` or 链漏开关位 → 假开关 | ✅ 逐个 Feature 查覆盖 |
| 宿主 id / 类名写死单值 | ✅ 查 `XxxTargets.kt` 之外的写法 |
| 日志脱敏只做 UI 层、取数层漏 | ⚠️ 只查最明显的形状 |
| 限速/冷却把回调 `return` 掉 | ❌ 要跑起来才知道 → 靠单测 + 人工 |
| 改判定语义 | ❌ → 靠单测真值表 |
| 缓存 key 漏参数 | ❌ → 靠人读 |

## 用法

```
python3 tools/ci/check_invariants.py            # 检查，退出码非 0 = 有问题
python3 tools/ci/check_invariants.py --report   # 只报告，不改退出码
```

## 定位

它是**源码级**检查，所以**不要**塞进 `tools/ci/verify_apk.py` ——
那个脚本的输入契约是「一个 APK」，做的是**产物红线**；混在一起会破坏它的定位。
"""

import argparse
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java")
PKG_DIR = os.path.join(SRC, "io", "github", "MVennn", "GSAuxiliary")
PROGUARD = os.path.join(ROOT, "app", "proguard-rules.pro")
RES_VALUES = os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml")
RES_VALUES_EN = os.path.join(ROOT, "app", "src", "main", "res", "values-en", "strings.xml")


def kotlin_files():
    for dirpath, _dirnames, filenames in os.walk(PKG_DIR):
        for fn in filenames:
            if fn.endswith(".kt"):
                yield os.path.join(dirpath, fn)


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


def code_without_comments(text):
    """去掉注释，但**保持行数不变**（把注释内容替换成等长空白）。

    行数必须不变 —— 扫描结果要报准确的行号；直接删注释行会让人对不上位置。
    """
    out = []
    in_block = False
    for line in text.splitlines():
        res = []
        in_str = False
        i = 0
        while i < len(line):
            c = line[i]
            if in_block:
                if c == "*" and i + 1 < len(line) and line[i + 1] == "/":
                    in_block = False
                    res.append("  ")
                    i += 2
                    continue
                res.append(" ")
                i += 1
                continue
            if c == '"' and (i == 0 or line[i - 1] != "\\"):
                in_str = not in_str
                res.append(c)
                i += 1
                continue
            if not in_str and c == "/" and i + 1 < len(line) and line[i + 1] == "/":
                res.append(" " * (len(line) - i))
                break
            if not in_str and c == "/" and i + 1 < len(line) and line[i + 1] == "*":
                in_block = True
                res.append("  ")
                i += 2
                continue
            res.append(c)
            i += 1
        out.append("".join(res))
    return "\n".join(out)


class Finding:
    def __init__(self, rule, path, line, msg):
        self.rule = rule
        self.path = path
        self.line = line
        self.msg = msg

    def __str__(self):
        loc = "%s:%d" % (self.path, self.line) if self.line else self.path
        return "[%s] %s\n        %s" % (self.rule, loc, self.msg)


# ---------------------------------------------------------------------------
# 规则 1：Miuix 的 Overlay* 组件禁用（一点就闪退）
# ---------------------------------------------------------------------------

BANNED_MIUIX = (
    "OverlayDialog",
    "OverlayDropdownPreference",
    "SuperDropdown",
    "OverlayBottomSheet",
    "OverlayPreference",
)


def check_miuix_overlay(findings):
    """Miuix 的 Overlay* 组件在库内部同步收动画又同步回写配置 → 一点就闪退。

    2.8.0 起项目整体换成自绘 `DialogHost`（根因见 `docs/UI.md` §1.3）。
    """
    for path in kotlin_files():
        lines = code_without_comments(read(path)).splitlines()
        for i, line in enumerate(lines, 1):
            for name in BANNED_MIUIX:
                if re.search(r"^\s*import\s+.*\.%s\b" % re.escape(name), line):
                    findings.append(Finding(
                        "muix-overlay", rel(path), i,
                        "import 了 Miuix 的 %s —— 它一点就闪退，弹层一律走自绘 DialogHost" % name,
                    ))
                elif re.search(r"\b%s\s*\(" % re.escape(name), line):
                    findings.append(Finding(
                        "muix-overlay", rel(path), i,
                        "调用了 Miuix 的 %s —— 它一点就闪退，弹层一律走自绘 DialogHost" % name,
                    ))


# ---------------------------------------------------------------------------
# 规则 2：关键 UI 状态必须 rememberSaveable
# ---------------------------------------------------------------------------

def check_remember_saveable(findings):
    """路由 / Tab / 滚动位置必须 `rememberSaveable`，否则翻屏就丢。

    第三轮 H5：全仓 `ui/` 零使用 `rememberSaveable` → 横竖屏切换 = Activity 重建
    = 路由回首页、Tab 回默认、滚动位置清零。
    """
    # (相对路径, 变量名) —— 只查这几个**确定**要跨重建保留的
    targets = [
        ("ui/Routing.kt", "route"),
        ("ui/Routing.kt", "currentTab"),
    ]
    for relpath, name in targets:
        path = os.path.join(PKG_DIR, relpath.replace("/", os.sep))
        if not os.path.exists(path):
            continue
        text = read(path)
        pattern = re.compile(r"var\s+%s\s+by\s+(remember|rememberSaveable)\b" % re.escape(name))
        for m in pattern.finditer(text):
            if m.group(1) == "remember":
                line = text[:m.start()].count("\n") + 1
                findings.append(Finding(
                    "saveable", rel(path), line,
                    "`%s` 用的是 remember 而不是 rememberSaveable —— 翻屏 / 分屏会丢" % name,
                ))


# ---------------------------------------------------------------------------
# 规则 3：拿「模块自己的」类名当判据 → 必须有 proguard keep
# ---------------------------------------------------------------------------

def check_classname_keep(findings):
    """只有**模块自己的**类名才需要 proguard keep。

    宿主类名与框架类名不会被混淆（混淆的是模块自己的代码）——
    所以这条只查 `io.github.MVennn.GSAuxiliary` 包下的类。

    背景：2026-10-19 装机包里 `StgStockFilterFeature$FilteredAdapter` 叫 `f2.m`，
    「类名里含 FilteredAdapter」这条判据认不出自己刚装的代理 →
    筛选「确定」不生效、点行点错股票（规则 1.2.1）。
    """
    if not os.path.exists(PROGUARD):
        return
    keep_text = read(PROGUARD)

    # 模块自己的类名：出现在本仓库源码里的顶层类 / 嵌套类。
    # ⚠️ 排除「常量容器」类（`XxxTargets` / `XxxIds` 之类）——
    #    它们只装宿主标识的常量，不是被注入到宿主里的东西，混淆了也无妨。
    own_classes = set()
    for path in kotlin_files():
        text = read(path)
        for m in re.finditer(r"^\s*(?:internal\s+|private\s+|public\s+|open\s+|abstract\s+)*"
                             r"(?:class|object|interface)\s+([A-Z][A-Za-z0-9_]*)", text, re.M):
            name = m.group(1)
            if name.endswith(("Targets", "Ids", "Constants", "Keys")):
                continue
            own_classes.add(name)

    for path in kotlin_files():
        raw = read(path)
        lines = raw.splitlines()
        code = code_without_comments(raw).splitlines()
        for i, line in enumerate(code, 1):
            if "javaClass.name" not in line and "javaClass.simpleName" not in line:
                continue
            # 这一行里提到的、且是我们自己的类名
            for name in own_classes:
                if not re.search(r"\b%s\b" % re.escape(name), line):
                    continue
                if re.search(r"-keep\S*\s+.*\b%s\b" % re.escape(name), keep_text):
                    continue
                # 只有「拿它当判据」才要报：出现在比较 / 包含判断里
                if not re.search(r"(==|!=|\bin\b|contains|startsWith|endsWith)", line):
                    continue
                findings.append(Finding(
                    "proguard-keep", rel(path), i,
                    "拿自己的类名 `%s` 当判据，但 proguard-rules.pro 里没有对应的 -keep —— "
                    "R8 会给它改名，release 包里就认不出自己注入的东西（规则 1.2.1）" % name,
                ))


# ---------------------------------------------------------------------------
# 规则 4：功能开关必须被某个 Feature 的判定链覆盖（防假开关）
# ---------------------------------------------------------------------------

def check_feature_switch_coverage(findings):
    """`ConfigKeys.FEATURE_KEYS` 里每个键都要出现在某个 Feature 的判定链上。

    背景：第三轮 H1「跳过保存确认」单独打开不生效 ——
    `isEnabled` 只认 `quickEnabled || emptySave`，漏了那个位 → **假开关**
    （界面上有、底下不装钩子）。
    """
    keys_path = os.path.join(PKG_DIR, "core", "ConfigKeys.kt")
    if not os.path.exists(keys_path):
        return
    text = read(keys_path)
    m = re.search(r"val\s+FEATURE_KEYS\s*=\s*arrayOf\((.*?)\)", text, re.S)
    if not m:
        return
    key_names = [t for t in re.findall(r"[A-Z][A-Z0-9_]+", m.group(1)) if t != "ENABLED"]
    if not key_names:
        return

    # 键名（STG_DIRECT_QUOTE）→ 快照字段名（stgDirectQuote）——
    # 判据代码里写的是**字段名**，两边不是一个东西，必须映射过去。
    field_of = set()
    snap_path = os.path.join(PKG_DIR, "core", "ConfigSnapshot.kt")
    if os.path.exists(snap_path):
        stext = read(snap_path)
        for fm in re.finditer(r"val\s+([a-z][A-Za-z0-9_]*)\s*:\s*Boolean", stext):
            field_of.add(fm.group(1))

    def guess_field(key_name):
        target = key_name.lower().replace("_", "")
        for field in field_of:
            if field.lower().replace("_", "") == target:
                return field
        return None

    feature_src = os.path.join(PKG_DIR, "feature")
    covered = set()
    for dirpath, _dirnames, filenames in os.walk(feature_src):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            ftext = read(os.path.join(dirpath, fn))
            # 判定链的两种写法：
            #   override fun isEnabled(cfg: ConfigSnapshot) = cfg.xxx
            #   override fun switchedOn(cfg: ConfigSnapshot): Boolean = cfg.xxx
            # 以及多行 or 链。统一按「从 = 号往后取到下一个声明为止」来截。
            for m in re.finditer(
                r"(?:isEnabled|switchedOn)\s*\([^)]*\)\s*(?::\s*Boolean\s*)?=\s*"
                r"((?:.|\n){0,400}?)\n\s*(?:override|private|protected|internal|fun\s|@|/\*|//)",
                ftext,
            ):
                expr = m.group(1)
                for key in key_names:
                    field = guess_field(key)
                    if field and re.search(r"cfg\.%s\b" % re.escape(field), expr):
                        covered.add(key)

    for key in key_names:
        if key not in covered:
            findings.append(Finding(
                "fake-switch", "core/ConfigKeys.kt", 0,
                "功能开关 `%s` 没有出现在任何 Feature 的 isEnabled/switchedOn 链上 —— "
                "单独打开它时功能不会装（假开关，第三轮 H1）" % key,
            ))


# ---------------------------------------------------------------------------
# 规则 5：宿主标识不能写死单值（要用候选数组）
# ---------------------------------------------------------------------------

def check_single_host_id(findings):
    """宿主资源 id / 类名必须是**候选数组**，不能是单个字符串常量。

    规则 11：单值写法在宿主改版后直接失效。项目约定是「候选名 + 多级兜底」
    （各 `XxxTargets.kt`）。
    """
    for path in kotlin_files():
        base = os.path.basename(path)
        # Targets 文件本身就是候选名的正本，跳过
        if base.endswith("Targets.kt") or base == "HostFields.kt":
            continue
        lines = code_without_comments(read(path)).splitlines()
        for i, line in enumerate(lines, 1):
            m = re.search(
                r"const\s+val\s+[A-Z0-9_]*(?:_ID|_CLASS|_PKG|_PACKAGE)\s*=\s*\"([^\"]+)\"",
                line,
            )
            if not m:
                continue
            value = m.group(1)
            # 模块自己的东西不算
            if "io.github.MVennn" in value:
                continue
            # 明显是宿主标识的（带包路径的、或看起来像资源 id）
            if "." in value or re.match(r"^[a-z][a-z0-9_]*$", value):
                findings.append(Finding(
                    "single-host-id", rel(path), i,
                    "`%s` 是宿主标识的单值写法 —— 应改成候选数组 + 多级兜底（规则 11）" % value,
                ))


# ---------------------------------------------------------------------------
# 规则 6：中英资源键必须一一对齐
# ---------------------------------------------------------------------------

def check_i18n_alignment(findings):
    """`values/strings.xml` 与 `values-en/strings.xml` 的键必须一一对应。

    漏一个 → 英文界面里那个文案显示成键名或中文；多一个 → 中文界面里缺。
    """
    if not (os.path.exists(RES_VALUES) and os.path.exists(RES_VALUES_EN)):
        return

    def keys(path):
        text = read(path)
        out = set()
        for m in re.finditer(r'<(?:string|string-array)\s+name="([^"]+)"', text):
            out.add(m.group(1))
        return out

    zh = keys(RES_VALUES)
    en = keys(RES_VALUES_EN)
    for k in sorted(zh - en):
        findings.append(Finding(
            "i18n", "app/src/main/res/values-en/strings.xml", 0,
            "中文里有 `%s` 但英文里没有 —— 英文界面会缺这条文案" % k,
        ))
    for k in sorted(en - zh):
        findings.append(Finding(
            "i18n", "app/src/main/res/values/strings.xml", 0,
            "英文里有 `%s` 但中文里没有 —— 中文界面会缺这条文案" % k,
        ))


# ---------------------------------------------------------------------------
# 规则 7：日志里不得出现未脱敏的 6 位代码
# ---------------------------------------------------------------------------

def check_raw_code_logging(findings):
    """INFO 及以上的日志不得直接插值「代码」类变量。

    规则 3.1 硬性红线；这条**漏过两次**（第三轮 H4 取数层、第四轮 B-1 盯盘卡片）。
    查法保守：只看变量名明显是代码的（code / cam / stockCode 之类），
    且该行**没有**出现 LogMask —— 走了 LogMask 的放过。
    """
    code_vars = re.compile(
        r"\$\{?\s*([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*\}?"
    )
    suspicious = re.compile(r"^(code|stockCode|cam|expected|codeAndMarket|stockCodeAndMarket)$", re.I)
    # 打码入口：走这两个之一就算合规
    masked = ("LogMask", "maskStockCode")

    for path in kotlin_files():
        lines = code_without_comments(read(path)).splitlines()
        for i, line in enumerate(lines, 1):
            if not re.search(r"GLog\.(i|w)\s*\(", line):
                continue
            if any(m in line for m in masked):
                continue
            for m in code_vars.finditer(line):
                var = m.group(1)
                leaf = var.split(".")[-1]
                if suspicious.match(leaf):
                    findings.append(Finding(
                        "raw-code-log", rel(path), i,
                        "INFO/WARN 日志里直接插值了 `%s` —— 完整代码不能进日志，"
                        "请走 LogMask.stock / LogMask.code / maskStockCode（规则 3.1）" % var,
                    ))


# ---------------------------------------------------------------------------
# 规则 8：跨容器搬 View 必须先把 target 从旧父里摘掉
# ---------------------------------------------------------------------------

def check_reparent_remove(findings):
    """`unwrapVerticalHost` 把行容器从壳搬回 grand 时，必须先 `removeView(target)`。

    2026-10-20 真机：只 `removeView(host)` 就 `addView(target)` →
    `IllegalStateException: The specified child already has a parent`，
    而 host 已经被摘掉 → 宿主的行容器成了孤儿，筛选面板当场塌陷
    （「面板开着时关开关」这条路径，单测与其余静态检查都抓不到）。
    """
    path = os.path.join(PKG_DIR, "feature", "filter", "StockFilterPanelUi.kt")
    if not os.path.exists(path):
        findings.append(Finding("reparent", rel(path), 0, "找不到 StockFilterPanelUi.kt —— 规则过期"))
        return
    text = code_without_comments(read(path))
    m = re.search(r"fun\s+unwrapVerticalHost\s*\(", text)
    if not m:
        findings.append(Finding("reparent", rel(path), 0, "找不到 unwrapVerticalHost —— 规则过期"))
        return
    start = m.start()
    # 函数体边界：到下一个同缩进的 fun 声明为止（本文件里函数之间没有嵌套 fun）
    nxt = re.search(r"\n\s{4}fun\s", text[start + 10:])
    body = text[start:start + 10 + nxt.start()] if nxt else text[start:]
    add = body.find("grand.addView(target")
    if add < 0:
        findings.append(Finding("reparent", rel(path), 0, "unwrapVerticalHost 里找不到 grand.addView(target) —— 规则过期"))
        return
    if not re.search(r"removeView\(\s*target\s*\)", body[:add]):
        line = text[:start + add].count("\n") + 1
        findings.append(Finding(
            "reparent", rel(path), line,
            "grand.addView(target) 之前没有 removeView(target) —— target 还挂在旧父上会抛 "
            "IllegalStateException，旧父被摘掉后宿主行容器成孤儿（筛选面板会塌陷）",
        ))


RULES = [
    ("Miuix Overlay* 禁用", check_miuix_overlay),
    ("关键状态用 rememberSaveable", check_remember_saveable),
    ("自己的类名当判据要有 proguard keep", check_classname_keep),
    ("功能开关不能是假开关", check_feature_switch_coverage),
    ("宿主标识用候选数组", check_single_host_id),
    ("中英资源键对齐", check_i18n_alignment),
    ("日志不得出现裸代码", check_raw_code_logging),
    ("跨容器搬 View 先摘旧父", check_reparent_remove),
]


def main():
    ap = argparse.ArgumentParser(description="静态不变量扫描")
    ap.add_argument("--report", action="store_true", help="只报告，不改退出码")
    args = ap.parse_args()

    findings = []
    for name, fn in RULES:
        try:
            fn(findings)
        except Exception as e:  # noqa: BLE001 - 扫描器本身不该因单条规则崩掉
            findings.append(Finding("scanner", "-", 0, "规则「%s」执行失败：%s" % (name, e)))

    if not findings:
        print("[OK] 静态不变量扫描：%d 条规则全部通过" % len(RULES))
        return 0

    print("=" * 70)
    print("静态不变量扫描：发现 %d 条" % len(findings))
    print("=" * 70)
    for f in findings:
        print(str(f))
    print("=" * 70)
    print("逐条对照 docs/CODE-RULES.md 判断是不是真问题：")
    print("  误报 → 就地改这条规则；真问题 → 当场修。")
    if args.report:
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
