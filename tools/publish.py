#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""publish.py —— 一次把版本同时发到「源码仓库」和「LSPosed 模块仓库」。

## 为什么需要它

LSPosed 的模块仓库（`Xposed-Modules-Repo/<包名>`）是**独立仓库**，
源码仓库发 Release **不会**同步过去。而 LSPosed 的网站与 App 只读模块仓库，
所以每次发版必须在**两个仓库各发一份同 tag、同 APK 的 Release**，
并保持模块仓库那几个元文件与源码仓库一致。手工做很容易漏，本脚本一次做完。

同步的四个元文件（母本都在源码仓库里，见 `xposed-repo/README.md`）：

| 模块仓库 | 母本 |
|---|---|
| `README.md` | `README.md` |
| `SUMMARY` | `xposed-repo/SUMMARY` |
| `SOURCE_URL` | 按 `SOURCE_REPO` 生成 |
| `icon.svg` | `docs/icon.svg` |

## 用法

    python tools/publish.py                    # 构建 + 双仓库发布 + 同步元文件
    python tools/publish.py --skip-build       # 用现有 APK，不重新构建
    python tools/publish.py --notes notes.md   # 指定 Release 说明（Markdown）
    python tools/publish.py --dry-run          # 只打印将要做什么，不写任何东西
    python tools/publish.py --sync-only        # 只同步元文件，不发 Release
    python tools/publish.py --check            # 只查凭据有没有两个仓库的写权限

版本号取自 `app/build.gradle.kts` 的 `versionCode` / `versionName`，
tag 形如 `33-2.13.0`（**versionCode-versionName**，LSPosed 的约定）。

## 凭据

三种给法，按优先级：

1. 环境变量 `GS_GITHUB_TOKEN` —— **CI 用这个**（GitHub Actions 里从 Secrets 注入）
2. 环境变量 `GH_TOKEN`
3. `git credential fill` —— 本机从 Windows 凭据管理器里已存的 GitHub 凭据取

**故意不读 `GITHUB_TOKEN`**：那是 Actions 自带的、只对当前仓库有效的短命 token，
拿去写 LSPosed 模块仓库会 403。宁可报错，也不要静默用错。

**token 只在内存里用**：只有 CI（token 来自环境变量）才会把它拼进 git URL；
本机走凭据管理器，避免被 git 写进 `.work/lsposed-repo/.git/config` 落盘。
所有可能打印出来的 git 报错都过一遍 `scrub()`。

## 环境变量

    GITHUB_SOURCE_REPO   源码仓库，默认 SansKritO4/GSAuxiliary
    GITHUB_MODULE_REPO   LSPosed 模块仓库，默认 Xposed-Modules-Repo/io.github.SansKritO4.GSAuxiliary
    GS_GITHUB_TOKEN      优先使用的 PAT（需要 repo scope，且对模块仓库有写权限）
    GH_TOKEN             次选
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
README = os.path.join(ROOT, "README.md")
WORK = os.path.join(ROOT, ".work")

SOURCE_REPO = os.environ.get("GITHUB_SOURCE_REPO", "SansKritO4/GSAuxiliary")
MODULE_REPO = os.environ.get(
    "GITHUB_MODULE_REPO", "Xposed-Modules-Repo/io.github.SansKritO4.GSAuxiliary")

API = "https://api.github.com"


def repo_files():
    """模块仓库里要同步的文件：`模块仓库文件名 -> 内容`。

    `SOURCE_URL` 是派生的（就是源码仓库地址），不单独存一份，免得又一处能改偏。
    """
    out = {
        "README.md": open(README, encoding="utf-8").read(),
        "SOURCE_URL": "https://github.com/%s\n" % SOURCE_REPO,
        "icon.svg": open(os.path.join(ROOT, "docs", "icon.svg"), encoding="utf-8").read(),
    }
    summary = os.path.join(ROOT, "xposed-repo", "SUMMARY")
    if not os.path.exists(summary):
        sys.exit("[X] 缺 %s —— 它是模块页副标题的母本，别绕过它" % summary)
    out["SUMMARY"] = open(summary, encoding="utf-8").read()
    return out


# --------------------------------------------------------------------------- 基础

def run(cmd, cwd=None, **kw):
    """在指定目录（默认仓库根）跑一条命令。cwd 必须从这里传，不能写死。"""
    return subprocess.run(cmd, cwd=cwd or ROOT, text=True, capture_output=True, **kw)


# 见过的 token。任何要打印的文本都过一遍 scrub()，git 报错里会带完整 URL。
_SECRETS = []
TOKEN_FROM_ENV = False


def scrub(text):
    """把 token 从待打印文本里抹掉。"""
    if not text:
        return text
    for t in _SECRETS:
        text = text.replace(t, "***")
    return text


def token():
    """取 PAT。优先级：GS_GITHUB_TOKEN > GH_TOKEN > git 凭据管理器。

    **故意不读 GITHUB_TOKEN** —— 那是 Actions 自带的、只对当前仓库有效的短命
    token，拿去写 LSPosed 模块仓库会 403。宁可报错，也不要静默用错。
    """
    global TOKEN_FROM_ENV
    for env in ("GS_GITHUB_TOKEN", "GH_TOKEN"):
        v = os.environ.get(env, "").strip()
        if v:
            _SECRETS.append(v)
            TOKEN_FROM_ENV = True
            print("      凭据来源：环境变量 %s" % env)
            return v

    r = run(["git", "credential", "fill"],
            input="protocol=https\nhost=github.com\n\n")
    m = re.search(r"^password=(.*)$", r.stdout or "", re.M)
    if not m or not m.group(1).strip():
        sys.exit("[X] 取不到 GitHub 凭据。三种给法：\n"
                 "    · 设环境变量 GS_GITHUB_TOKEN（CI 用这个）\n"
                 "    · 设环境变量 GH_TOKEN\n"
                 "    · 本机确认 git 能 push：`git push --dry-run`\n"
                 "    token 需要 repo scope，且对模块仓库有写权限。")
    tok = m.group(1).strip()
    _SECRETS.append(tok)
    return tok


def repo_url(tok):
    """模块仓库的 https 地址。

    只有 token 来自环境变量（CI）时才把 token 拼进去 —— 本机走凭据管理器，
    把 PAT 拼进 URL 会被 git 写进 .git/config，违背「token 不落盘」。
    """
    if tok and TOKEN_FROM_ENV:
        return "https://x-access-token:%s@github.com/%s.git" % (tok, MODULE_REPO)
    return "https://github.com/%s.git" % MODULE_REPO


def read_version():
    src = open(GRADLE, encoding="utf-8").read()
    vc = re.search(r"versionCode\s*=\s*(\d+)", src)
    vn = re.search(r'versionName\s*=\s*"([^"]+)"', src)
    if not vc or not vn:
        sys.exit("[X] 读不到 versionCode / versionName（%s）" % GRADLE)
    return int(vc.group(1)), vn.group(1)


def api(tok, method, path, payload=None, raw=None, ctype=None):
    url = path if path.startswith("http") else API + path
    if raw is not None:
        data, headers = raw, {"Content-Type": ctype, "Content-Length": str(len(raw))}
    elif payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json"}
    else:
        data, headers = None, {}
    headers.update({"Authorization": "Bearer " + tok,
                    "Accept": "application/vnd.github+json",
                    "X-GitHub-Api-Version": "2022-11-28"})
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            body = r.read()
            return r.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body[:500]}


def _brief(data):
    """把 API 的错误体压成一行，便于贴进日志。"""
    if isinstance(data, dict):
        return str(data.get("message") or data.get("raw") or data)[:200]
    return str(data)[:200]


# --------------------------------------------------------------------------- 前置检查

def check_publish_access(tok):
    """发版前的前置检查：这个 token 真的能写进**两个**仓库吗？

    为什么值得单独一步：publish.py 是先建源码仓库的 Release、再发模块仓库。
    如果 token 对模块仓库没有写权限，源码仓库那份已经建好了 —— 用户却什么都收不到，
    而日志报的是「失败」，很容易被当成偶发问题重跑。提前查，红在构建之前。
    """
    ok = True

    status, me = api(tok, "GET", "/user")
    if status != 200:
        print("  [X] token 本身无效（GET /user -> HTTP %s：%s）" % (status, _brief(me)))
        return False
    print("  [OK] token 有效，身份：%s" % (me or {}).get("login", "?"))

    for repo in (SOURCE_REPO, MODULE_REPO):
        status, data = api(tok, "GET", "/repos/" + repo)
        if status != 200:
            print("  [X] %s 读不到（HTTP %s：%s）" % (repo, status, _brief(data)))
            ok = False
            continue
        perms = (data or {}).get("permissions") or {}
        if not perms.get("push"):
            print("  [X] %s **没有写权限**（push=%s，admin=%s）—— "
                  "token 需要 repo scope，且要对该仓库有 push 权限"
                  % (repo, perms.get("push"), perms.get("admin")))
            ok = False
            continue
        print("  [OK] %s 可写（默认分支 %s）"
              % (repo, (data or {}).get("default_branch", "?")))

    return ok


# --------------------------------------------------------------------------- 步骤

def build():
    print("[1/5] 构建 release …")
    r = run([os.path.join(ROOT, "gradlew"), "assembleRelease", "--console=plain"])
    if r.returncode != 0:
        print(r.stdout[-3000:])
        print(r.stderr[-2000:])
        sys.exit("[X] 构建失败")
    print("      BUILD SUCCESSFUL")


def stage_apk(vc, vn):
    src = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
    if not os.path.exists(src):
        sys.exit("[X] 找不到 %s —— 先跑一次 assembleRelease，或去掉 --skip-build" % src)
    os.makedirs(WORK, exist_ok=True)
    name = "GSAuxiliary_%d-%s.apk" % (vc, vn)
    dst = os.path.join(WORK, name)
    shutil.copyfile(src, dst)
    print("[2/5] APK %s（%d 字节）" % (name, os.path.getsize(dst)))
    return name, dst


def upsert_release(tok, repo, tag, vn, notes, apk_name=None, apk_path=None, dry=False):
    """在 repo 上建或更新 tag 对应的 Release，并按需替换 APK 资产。"""
    status, rel = api(tok, "GET", "/repos/%s/releases/tags/%s" % (repo, tag))
    exists = status == 200
    if dry:
        print("      [dry-run] %s：%s Release %s" %
              (repo, "更新" if exists else "创建", tag))
        return (rel or {}).get("html_url")
    if exists:
        api(tok, "PATCH", "/repos/%s/releases/%d" % (repo, rel["id"]),
            {"name": "v" + vn, "body": notes})
    else:
        status, rel = api(tok, "POST", "/repos/%s/releases" % repo,
                          {"tag_name": tag, "name": "v" + vn, "body": notes,
                           "draft": False, "prerelease": False})
        if status not in (200, 201):
            sys.exit("[X] %s 建 Release 失败 HTTP %s：%s" % (repo, status, rel))

    if apk_name:
        # 同名资产要先删，否则 GitHub 会报 already_exists
        for a in rel.get("assets", []):
            if a["name"] == apk_name:
                api(tok, "DELETE", "/repos/%s/releases/assets/%d" % (repo, a["id"]))
        data = open(apk_path, "rb").read()
        up = rel["upload_url"].split("{")[0] + "?name=" + apk_name
        status, asset = api(tok, "POST", up, raw=data,
                            ctype="application/vnd.android.package-archive")
        if status not in (200, 201):
            sys.exit("[X] %s 传 APK 失败 HTTP %s：%s" % (repo, status, asset))
        print("      APK 已上传，%d 字节" % asset.get("size", -1))
    print("      %s  ->  %s" % (repo, rel.get("html_url")))
    return rel.get("html_url")


def sync_repo_files(tok, dry=False):
    """把源码仓库里那几个母本同步到模块仓库（两边必须逐字一致）。

    只同步 `repo_files()` 列出的文件 —— 模块仓库里不放源码。
    """
    files = repo_files()
    clone = os.path.join(WORK, "lsposed-repo")
    if dry:
        for name in sorted(files):
            print("      [dry-run] 同步 %-12s -> %s" % (name, MODULE_REPO))
        return
    if not os.path.isdir(os.path.join(clone, ".git")):
        shutil.rmtree(clone, ignore_errors=True)
        r = run(["git", "clone", "-q", repo_url(tok), clone])
        if r.returncode != 0:
            sys.exit("[X] 克隆 %s 失败：%s" % (MODULE_REPO, scrub(r.stderr[-400:])))
    else:
        run(["git", "pull", "-q", "--ff-only"], cwd=clone)
    run(["git", "config", "user.name", "M.Venn"], cwd=clone)
    run(["git", "config", "user.email",
         "127460743+SansKritO4@users.noreply.github.com"], cwd=clone)

    changed = []
    for name, content in sorted(files.items()):
        dst = os.path.join(clone, name)
        if os.path.exists(dst) and open(dst, encoding="utf-8").read() == content:
            continue
        open(dst, "w", encoding="utf-8", newline="\n").write(content)
        changed.append(name)

    if not changed:
        print("      模块仓库元文件已是最新，无需同步")
        return
    run(["git", "add", "--"] + changed, cwd=clone)
    run(["git", "commit", "-q", "-m",
         "Sync %s from source repo" % ", ".join(changed)], cwd=clone)
    # 显式给 URL：CI 里 origin 可能就是带 token 的那个，本机则回落到 origin
    r = run(["git", "push", "-q", repo_url(tok) if TOKEN_FROM_ENV else "origin", "HEAD"], cwd=clone)
    if r.returncode != 0:
        sys.exit("[X] 推送失败：%s" % scrub(r.stderr[-400:]))
    print("      已同步：%s" % ", ".join(changed))


def notes_from_changelog(vn):
    """默认说明：从 CHANGELOG 抽该版本段落。**偏开发者向**，
    想给用户看更短的说明就用 `--notes` 指定文件。"""
    path = os.path.join(ROOT, "CHANGELOG.md")
    if not os.path.exists(path):
        return "v" + vn
    src = open(path, encoding="utf-8").read()
    m = re.search(r"^## \[" + re.escape(vn) + r"\].*?$(.*?)(?=^## \[|\Z)",
                  src, re.M | re.S)
    body = (m.group(1) if m else "").strip()
    return body or ("v" + vn)


# --------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description="一次发布到源码仓库 + LSPosed 模块仓库")
    ap.add_argument("--skip-build", action="store_true", help="不重新构建，用现有 APK")
    ap.add_argument("--notes", metavar="FILE", help="Release 说明（Markdown 文件）")
    ap.add_argument("--dry-run", action="store_true", help="只打印，不写任何东西")
    ap.add_argument("--sync-only", "--readme-only", dest="sync_only",
                    action="store_true",
                    help="只同步模块仓库的元文件（README/SUMMARY/SOURCE_URL/icon.svg），不发 Release")
    ap.add_argument("--check", action="store_true",
                    help="只做前置检查：token 是否对两个仓库都有写权限（不构建、不发布）")
    args = ap.parse_args()

    if args.check:
        print("发版前置检查（不构建、不发布）")
        print("  源码仓库：%s" % SOURCE_REPO)
        print("  模块仓库：%s" % MODULE_REPO)
        ok = check_publish_access(token())
        print()
        print("结论：%s" % ("可以发版" if ok else "发不了 —— 见上面的 [X]"))
        return 0 if ok else 1

    if args.sync_only:
        print("只同步模块仓库元文件 -> %s" % MODULE_REPO)
        sync_repo_files("" if args.dry_run else token(), args.dry_run)
        return 0

    vc, vn = read_version()
    tag = "%d-%s" % (vc, vn)
    print("版本：%s（versionCode %d）  tag：%s" % (vn, vc, tag))
    print("源码仓库：%s" % SOURCE_REPO)
    print("模块仓库：%s" % MODULE_REPO)
    print()

    if args.notes:
        notes = open(args.notes, encoding="utf-8").read()
    else:
        notes = notes_from_changelog(vn)
        print("! 未指定 --notes，使用 CHANGELOG 的 [%s] 段。" % vn)
        print("! 那段是给开发者的（症状/根因/改法/验证），面向用户建议另写一份传 --notes。")
        print()

    if not args.skip_build and not args.dry_run:
        build()
    elif args.dry_run:
        print("[1/5] [dry-run] 跳过构建")
    else:
        print("[1/5] 跳过构建（--skip-build）")

    if args.dry_run:
        apk_name = "GSAuxiliary_%d-%s.apk" % (vc, vn)
        apk_path = None
        print("[2/5] [dry-run] 将使用 %s" % apk_name)
    else:
        apk_name, apk_path = stage_apk(vc, vn)

    tok = "" if args.dry_run else token()

    print("[3/5] 源码仓库 %s" % SOURCE_REPO)
    src_url = upsert_release(tok, SOURCE_REPO, tag, vn, notes,
                             apk_name, apk_path, args.dry_run)

    print("[4/5] 模块仓库 %s" % MODULE_REPO)
    mod_url = upsert_release(tok, MODULE_REPO, tag, vn, notes,
                             apk_name, apk_path, args.dry_run)

    print("[5/5] 同步模块仓库元文件")
    sync_repo_files(tok, args.dry_run)

    print()
    print("完成。")
    if src_url:
        print("  源码 Release : %s" % src_url)
    if mod_url:
        print("  模块 Release : %s" % mod_url)
    print("  模块页（描述非空 + 有 release，两项齐了起算 60 分钟内收录）: "
          "https://modules.lsposed.org/module/%s" % MODULE_REPO.split("/")[-1])
    return 0


if __name__ == "__main__":
    sys.exit(main())
