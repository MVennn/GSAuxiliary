#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""公开仓敏感信息扫描：凭据 + 本机路径 + 真实邮箱（GoldSunEnhance）。

用法：
    python tools/check-secrets.py            # 扫描已跟踪文件
    python tools/check-secrets.py --all      # 扫描工作树（含未跟踪，排除 .git/.work 等）

退出码：0 通过 / 1 发现疑似敏感信息。
规则与 ZcodeAIModels 的守卫完全一致，8 个仓扫同一套口径。
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_EXT = {".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".gz", ".pyc",
            ".woff", ".woff2", ".zip", ".mrs", ".svg", ".webp", ".jar"}

# 占位符与示例行不算命中
PLACEHOLDER = re.compile(
    r"REDACTED|YOUR-|REPLACE-|<[^>]+>|example\.(com|org|net)|placeholder|<用户名>", re.I)

SKIP_DIRS = {".codegraph", ".git", ".work", "__pycache__", "app", "bin", "build", "captures", "dist", "gen", "gradle", ".gradle", "node_modules", "release", "venv", "xposed-repo"}
SKIP_PREFIXES = ()


def base_skip(rel: str) -> bool:
    return False


# 逐行白名单：命中这些「文件 + 规则 + 行内容特征」的行不报。只放「本仓有意为之」或
# 「确定不是敏感信息」的情形，每条都要写清理由。
LINE_ALLOWLIST = [
    # .mailmap 是 git 官方的作者身份映射机制，邮箱本来就该出现在里面；
    # 本仓有意保留它来统一历史提交的作者身份（见 .mailmap 头部说明）。
    (".mailmap", "真实邮箱", None, "git mailmap 的作者身份映射，邮箱本应在此"),
    # Kotlin 协程符号形如 `$scaleDone$1`，被 shadow 哈希正则误判；
    # 真 shadow 哈希后面跟的是 base64 盐+摘要，不会以 `$` 结尾。
    ("CHANGELOG.md", "shadow 哈希", re.compile(r"\$\w+\$\d+\b"),
     "Kotlin 协程符号 $name$1，非密码哈希"),
    # PROGRESS.md 记的是「把小号上的邮箱移除」这项待办，不是在散布邮箱；
    # 该文件当前未跟踪（另一会话在写），不改内容，只在守卫里放行。
    ("PROGRESS.md", "真实邮箱", re.compile(r"Contributors 仍有|Settings → Emails 删掉"),
     "记录「从小号移除该邮箱」的待办，非泄漏"),
]

PATTERNS = [
    ("私钥块", re.compile(r"-----BEGIN (?:RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b")),
    ("GitHub PAT", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{22,}\b")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Anthropic key", re.compile(r"\bsk-ant-[A-Za-z0-9\-_]{10,}\b")),
    ("通用 sk- token", re.compile(r"\bsk-[A-Za-z0-9]{20,}\b")),
    ("OpenCode key", re.compile(r"\boc_sk_[A-Za-z0-9]{10,}\b")),
    ("Gemini key", re.compile(r"\bAIza[0-9A-Za-z\-_]{35}\b")),
    ("GitLab PAT", re.compile(r"\bglpat-[A-Za-z0-9\-_]{20,}\b")),
    ("shadow 哈希", re.compile(r"\$[1256][aby]?\$[./A-Za-z0-9]{8,}")),
    ("代理节点 URI", re.compile(
        r"\b(?:vmess|vless|trojan|hysteria2?)://[A-Za-z0-9+/=_%.:@?&~-]{20,}", re.I)),
    ("订阅 token", re.compile(
        r"https?://[^\s'\"<>]+(?:token|subscribe|subscription)=[A-Za-z0-9._~-]{8,}", re.I)),
    # 本机用户目录：Windows 正斜杠与反斜杠两种写法
    ("本机用户目录", re.compile(r"[A-Za-z]:[\\/]Users[\\/][A-Za-z0-9._-]+")),
    # 真实邮箱域名（GitHub noreply 不含这些域名，不会误伤）
    ("真实邮箱", re.compile(
        r"\b[A-Za-z0-9._%+-]+@(?:qq|163|126|gmail|outlook|hotmail|foxmail|sina|sohu)\.com\b")),
]


def is_allowlisted(rel: str, name: str, line: str) -> bool:
    base = rel.replace("\\", "/")
    for af, an, line_pat, _ in LINE_ALLOWLIST:
        if base == af and an == name:
            if line_pat is None or line_pat.search(line):
                return True
    return False

# 变更说明里引用泄漏原文是必要的（否则读者不知道该改什么），但真值仍要遮掉。
# 用户名位置被 `...` / `<...>` 取代，或用户名后紧跟 `\...` 截断的写法，视为已脱敏的
# 描述性引用，放行；完整路径一律不放行。只对文档类文件生效。
DOC_FILES = ("CHANGELOG.md", "PROGRESS.md", "SECURITY.md", "CONTRIBUTING.md")
DOC_PREFIXES = ("docs/", "evidence/", "memories/")
TRUNCATED_PATH = re.compile(
    r"[A-Za-z]:[\\/]Users[\\/](?:\.\.\.|<[^>]+>|[A-Za-z0-9._-]+\\\.\.\.)")


def tracked_files() -> list[str]:
    try:
        out = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True)
    except FileNotFoundError:
        print("✗ 找不到 git 命令")
        raise SystemExit(2)
    return [x for x in out.stdout.splitlines() if x.strip()]


def walk_files() -> list[str]:
    files = []
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in SKIP_DIRS]
        for f in fn:
            files.append(os.path.relpath(os.path.join(dp, f), ROOT))
    return files


# 占位用户名：示例文件里的 C:/Users/You/... 是给用户照着改的模板，不是本机路径。
PLACEHOLDER_USER = re.compile(
    r"[A-Za-z]:[\/]Users[\/](?:You|User|Your|<[^>]+>|[^\/\s<>]*[一-鿿])", re.I)


def is_doc_file(rel: str) -> bool:
    base = rel.replace("\\", "/")
    if base in DOC_FILES or base.startswith(DOC_PREFIXES):
        return True
    return base.split("/")[-1] in DOC_FILES


def main() -> int:
    files = walk_files() if "--all" in sys.argv else tracked_files()
    bad = []
    for rel in files:
        if os.path.splitext(rel)[1].lower() in SKIP_EXT:
            continue
        parts = rel.replace("\\", "/").split("/")
        if any(p in SKIP_DIRS for p in parts[:-1]):
            continue
        if base_skip(rel):
            continue
        # 文档类文件允许描述性引用泄漏原文，但必须是截断形式
        allow_truncated = is_doc_file(rel)
        try:
            with open(os.path.join(ROOT, rel), encoding="utf-8", errors="replace") as fh:
                for lineno, line in enumerate(fh, 1):
                    for name, pat in PATTERNS:
                        m = pat.search(line)
                        if not m:
                            continue
                        # 占位符只豁免它自己所在的那一段，不再整行放行 ——
                        # 示例与真值写在同一行时，真值仍会被报出来。
                        if PLACEHOLDER.search(m.group(0)):
                            continue
                        if name == "本机用户目录":
                            # 截断形式的描述性引用，或占位用户名（C:/Users/You/）
                            if TRUNCATED_PATH.search(line):
                                continue
                            if PLACEHOLDER_USER.search(line):
                                continue
                        if is_allowlisted(rel, name, line):
                            continue
                        bad.append((rel, lineno, name, line.strip()[:120]))
        except OSError:
            continue

    if bad:
        print(f"✗ 发现 {len(bad)} 处疑似敏感信息：")
        for rel, lineno, name, text in bad:
            print(f"   {rel}:{lineno}  [{name}]  {text}")
        print()
        print("   处理方式：凭据换成环境变量占位符；本机路径只留文件名；邮箱改成 <账号>。")
        print("   位置索引见 docs/SECRETS.md。")
        return 1

    print(f"✓ 已扫描 {len(files)} 个文件，未发现疑似敏感信息")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
