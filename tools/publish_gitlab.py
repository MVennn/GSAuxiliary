#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""publish_gitlab.py —— 把这一版发到 GitLab 源码仓库（M.Venn/gsauxiliary）。

**为什么要有这个脚本**：`tools/publish.py` 是给 GitHub 那两个仓库用的，
而 GitHub 账号 2026-10-18 起被停用（见 docs/RELEASE.md 顶部）。
GitLab 侧的发版走这里：建 Release + 传 APK + 传 R8 混淆映射。

**它只管源码仓库**。LSPosed 模块仓库（modules.lsposed.org / LSPosed App 读的那个）
本账号没有写权限，所以「双仓库同 tag 同产物」这条规矩目前只落了一半 ——
见 docs/RELEASE.md §0 与 docs/GITLAB.md §5。

用法
----
    python tools/publish_gitlab.py --check                # 只查凭据与版本，不写任何东西
    python tools/publish_gitlab.py --dry-run              # 演一遍（不写）
    python tools/publish_gitlab.py --notes docs/release-notes/3.3.0.md
    python tools/publish_gitlab.py --skip-build           # 用现有 APK，不重新构建

凭据
----
    优先级：环境变量 GS_GITLAB_TOKEN → ~/.config/gitlab/token
    （那个文件是 glab 的配置位置，权限 600；**token 只在内存里用，不落盘、不打印**）

退出码：0 = 成功；1 = 失败（凭据 / 校验 / 网络）；2 = 参数写错。
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
API = "https://gitlab.com/api/v4"

# 项目就在本仓库的 origin 之外 —— 路径见 docs/GITLAB.md §1
PROJECT_ID = "87853791"                 # M.Venn/gsauxiliary
PROJECT_PATH = "M.Venn/gsauxiliary"


# --------------------------------------------------------------------------- 基础

def token():
    """取 GitLab token。**不打印**。"""
    t = os.environ.get("GS_GITLAB_TOKEN")
    if not t:
        p = os.path.expanduser("~/.config/gitlab/token")
        if os.path.exists(p):
            t = open(p, encoding="utf-8-sig").read().strip()
    if not t:
        sys.exit("[X] 找不到 GitLab token —— 设 GS_GITLAB_TOKEN 环境变量，"
                 "或放到 ~/.config/gitlab/token")
    if not t.startswith("glpat-"):
        sys.exit("[X] token 长得不像 GitLab PAT（应以 glpat- 开头）")
    return t


def api(tok, method, path, data=None, files=None):
    """调 GitLab API，返回 (状态码, 解析后的 JSON 或文本)。"""
    url = API + path
    hdrs = {"PRIVATE-TOKEN": tok}
    body = None
    if files is not None:
        boundary = "----GSAuxiliaryBoundary"
        parts = []
        for k, v in (data or {}).items():
            parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                          % (boundary, k, v)).encode("utf-8"))
        for k, (fname, content) in files.items():
            parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\n"
                          "Content-Type: application/octet-stream\r\n\r\n"
                          % (boundary, k, fname)).encode("utf-8"))
            parts.append(content)
            parts.append(b"\r\n")
        parts.append(("--%s--\r\n" % boundary).encode("utf-8"))
        body = b"".join(parts)
        hdrs["Content-Type"] = "multipart/form-data; boundary=%s" % boundary
    elif data is not None:
        body = urllib.parse.urlencode(data).encode("utf-8")
        hdrs["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            txt = r.read().decode("utf-8")
            return r.status, (json.loads(txt) if txt else {})
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:600]
    except urllib.error.URLError as e:
        sys.exit("[X] 连不上 GitLab：%s" % e)


def run(cmd, cwd=None):
    return subprocess.run(cmd, cwd=cwd or ROOT, text=True, capture_output=True)


def read_version():
    """版本号的唯一来源是 app/build.gradle.kts（见 docs/BUILD.md）。"""
    src = open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    import re
    vc = re.search(r"^\s*versionCode\s*=\s*(\d+)\s*$", src, re.M)
    vn = re.search(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', src, re.M)
    if not vc or not vn:
        sys.exit("[X] 在 app/build.gradle.kts 里读不到 versionCode / versionName")
    return vc.group(1), vn.group(1)


def gradle_launcher():
    return "./gradlew.bat" if os.name == "nt" else "./gradlew"


def build():
    print("  构建 release …")
    r = run([gradle_launcher(), ":app:assembleRelease", "--console=plain", "--no-daemon"])
    if r.returncode != 0:
        print(r.stdout[-3000:])
        print(r.stderr[-2000:])
        sys.exit("[X] 编译失败")


def stage_apk(vc, vn, apk_override=None):
    """把产物复制成发布用的文件名，并顺手做签名红线校验。

    apk_override：指定现成的 APK（用来在隔离 worktree 里出包、再从这里发版）——
    并发会话共用同一个 app/build 时会撞 R8 的文件锁，隔离构建是绕法。
    """
    src = apk_override or os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
    if not os.path.exists(src):
        sys.exit("[X] 找不到 %s —— 先编一次" % src)
    out_dir = os.path.join(ROOT, ".work", "apk")
    os.makedirs(out_dir, exist_ok=True)
    dst = os.path.join(out_dir, "GSAuxiliary_%s-%s.apk" % (vc, vn))
    with open(src, "rb") as a, open(dst, "wb") as b:
        b.write(a.read())
    print("  产物：%s（%d B）" % (dst, os.path.getsize(dst)))

    # 签名红线：发版包不能是 debug 证书 —— 否则所有已装用户得卸载重装
    v = run([sys.executable, "tools/ci/verify_apk.py", "--reject-debug-cert", dst])
    if v.returncode != 0:
        print(v.stdout[-2000:])
        sys.exit("[X] 产物红线校验没过（多半是签名退了 debug 证书）")
    print("  [OK] 产物红线校验通过")

    # R8 映射：release 崩溃栈要靠它还原成可读的类名 / 行号
    mapping = os.path.join(ROOT, "app", "build", "outputs", "mapping", "release", "mapping.txt")
    map_dst = None
    if os.path.exists(mapping):
        map_dst = os.path.join(out_dir, "mapping-%s-%s.txt" % (vc, vn))
        with open(mapping, "rb") as a, open(map_dst, "wb") as b:
            b.write(a.read())
    return dst, map_dst


def notes_for(vn, explicit):
    """说明文件：优先用户指定的；否则用 docs/release-notes/<版本>.md；
    再没有就退回 CHANGELOG 那一段（面向开发者，偏噪音）。"""
    if explicit:
        return open(explicit, encoding="utf-8").read()
    p = os.path.join(ROOT, "docs", "release-notes", "%s.md" % vn)
    if os.path.exists(p):
        return open(p, encoding="utf-8").read()
    src = open(os.path.join(ROOT, "CHANGELOG.md"), encoding="utf-8").read()
    import re
    m = re.search(r"^## \[" + re.escape(vn) + r"\].*?$(.*?)(?=^## \[|\Z)", src, re.M | re.S)
    body = (m.group(1) if m else "").strip()
    return body or ("v" + vn)


def upload_asset(tok, tag, path, link_type):
    """把一个文件挂成 Release 的下载附件。

    GitLab 这一侧是**两步**，不是一步：
      1. POST /projects/:id/uploads           —— 传到项目的 uploads（拿 url）
      2. POST /projects/:id/releases/:tag/assets/links —— 挂成 Release 资产

    第 2 步的 URL 要用上传返回的 **full_path** 拼绝对地址
    （`https://gitlab.com/-/project/<id>/uploads/...`）——
    用 `url`（`/uploads/...`）拼出来的地址点进去是 404。
    """
    name = os.path.basename(path)
    with open(path, "rb") as f:
        st, up = api(tok, "POST", "/projects/%s/uploads" % PROJECT_ID,
                     data={"file": name}, files={"file": (name, f.read())})
    if st not in (200, 201):
        sys.exit("[X] 上传 %s 失败（%s）：%s" % (name, st, up))
    url = "https://gitlab.com" + up["full_path"]
    st, r = api(tok, "POST", "/projects/%s/releases/%s/assets/links" % (PROJECT_ID, tag),
                json_body={"name": name, "url": url, "link_type": link_type})
    if st not in (200, 201):
        sys.exit("[X] 挂 %s 失败（%s）：%s" % (name, st, r))
    # 立刻验一下这个地址真能下 —— 挂上了但下不来是最坑的一种「看着成功」
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "curl/8"})) as rr:
            head = rr.read(4)
        print("  [OK] %s（下载校验 HTTP %s，头 %r）" % (name, rr.status, head))
    except Exception as e:
        sys.exit("[X] %s 挂上了但下载不了：%s" % (name, e))


def main():
    ap = argparse.ArgumentParser(description="发版到 GitLab 源码仓库（M.Venn/gsauxiliary）")
    ap.add_argument("--skip-build", action="store_true", help="不重新构建，用现有 APK")
    ap.add_argument("--notes", metavar="FILE", help="Release 说明（Markdown 文件）")
    ap.add_argument("--dry-run", action="store_true", help="只打印，不写任何东西")
    ap.add_argument("--check", action="store_true", help="只查凭据与版本号，不写任何东西")
    ap.add_argument("--apk", metavar="FILE", help="用这个现成的 APK（隔离 worktree 出包时用）")
    args = ap.parse_args()

    tok = token()
    vc, vn = read_version()
    tag = "%s-%s" % (vc, vn)

    print("发版前置检查")
    print("  仓库      : %s" % PROJECT_PATH)
    print("  版本      : %s（versionCode %s）" % (vn, vc))
    print("  tag       : %s" % tag)
    st, proj = api(tok, "GET", "/projects/" + PROJECT_ID)
    if st != 200:
        sys.exit("[X] 读项目失败（%s）：%s" % (st, proj))
    print("  远端      : %s" % proj.get("web_url"))

    st, rel = api(tok, "GET", "/projects/%s/releases/%s" % (PROJECT_ID, tag))
    if st == 200:
        print("  已存在 Release %s（会更新它）" % tag)

    if args.check:
        print("  [OK] 凭据可用")
        return 0

    notes = notes_for(vn, args.notes)
    if not args.skip_build and not args.apk:
        build()
    apk, mapping = stage_apk(vc, vn, args.apk)

    if args.dry_run:
        print("\n[dry-run] 不会写任何东西。真要发就去掉 --dry-run。")
        return 0

    print("\n建 Release %s" % tag)
    st, r = api(tok, "POST", "/projects/%s/releases" % PROJECT_ID,
                data={"name": vn, "tag_name": tag, "description": notes})
    if st in (200, 201):
        print("  [OK] 已建（HTTP %s）" % st)
    elif st == 409:
        print("  [i] 已存在，改成更新说明")
        st, r = api(tok, "PUT", "/projects/%s/releases/%s" % (PROJECT_ID, tag),
                    data={"name": vn, "description": notes})
        if st != 200:
            sys.exit("[X] 更新 Release 失败（%s）：%s" % (st, r))
        print("  [OK] 说明已更新")
    else:
        sys.exit("[X] 建 Release 失败（%s）：%s" % (st, r))

    print("传附件")
    upload_asset(tok, tag, apk, "package")
    if mapping:
        upload_asset(tok, tag, mapping, "other")

    print("\n[OK] Release：https://gitlab.com/%s/-/releases/%s" % (PROJECT_PATH, tag))
    print("     注意：LSPosed 模块仓库那一份**还没发**（本账号无写权限）——")
    print("     用户在 LSPosed App 里收不到更新提示，见 docs/RELEASE.md §0。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
