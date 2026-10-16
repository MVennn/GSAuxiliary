#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_apk.py —— 对构建产物做「红线」自查（CI 与本地共用）。

为什么要有它
------------
`docs/BUILD.md` / `docs/RELEASE.md` 里列了一堆自查项，但它们散在文档里，
靠人手工跑 `dexdump` / `aapt2`，发版时漏一条就会出「编译通过、装上去不生效」
这种最难查的问题。这个脚本把它们固化成一条命令。

不依赖 dexdump / aapt2 / apksigner —— dex 的类定义表、APK 签名块都自己解析，
纯标准库，所以在最小环境（CI runner、干净容器）里也能跑。

检查项
------
1. `META-INF/xposed/{module.prop,java_init.list,scope.list}` 三个元数据在包内
2. 没有 legacy 的 `assets/xposed_init`（有它 = LSPosed 会当老模块，静默不加载）
3. 【红线】dex 里不能有 `io/github/libxposed/api`（以及 `annotation`）的**类定义**
   （两者都必须 compileOnly；只有 service 可以被打进包）
4. 入口类 `GszxModule` 的类定义在（R8 keep 规则生效）
5. `AndroidManifest.xml` 里没有 legacy 的 `xposedminversion` 等 meta-data key，
   且 `package` 属性仍是预期的 applicationId
6. 打包的三个元文件与 `app/src/main/resources/` 下的**母本逐字一致**
   （防「母本改了但没进包」/ 被依赖覆盖）—— 母本不存在时自动跳过
7. 【红线】APK 已签名；并解析出签名证书，可要求它**不是 Android 调试证书**
   （`--reject-debug-cert`）或**等于指定指纹**（`--expect-cert-sha256`）

用法
----
    python tools/ci/verify_apk.py app/build/outputs/apk/release/app-release.apk
    python tools/ci/verify_apk.py <apk> [<apk> ...]

    # release 发版用：证书必须是自己的，不能静默退回 debug 签名
    python tools/ci/verify_apk.py --reject-debug-cert app-release.apk

    # 更进一步：钉死证书指纹（也可用环境变量 GS_EXPECT_CERT_SHA256）
    python tools/ci/verify_apk.py --reject-debug-cert \
        --expect-cert-sha256 a7fc6034... app-release.apk

退出码：0 = 全部通过（允许有提醒）；1 = 有红线被踩；2 = 用法错误。
"""

import argparse
import hashlib
import os
import struct
import sys
import zipfile

# --------------------------------------------------------------------------- 常量

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))

META_DIR = "META-INF/xposed/"
META_REQUIRED = (
    META_DIR + "module.prop",
    META_DIR + "java_init.list",
    META_DIR + "scope.list",
)

# 元文件的**母本**目录（源码仓库里）。存在就做「打包内容 == 母本」一致性校验。
META_SOURCE_DIR = os.path.join(
    ROOT, "app", "src", "main", "resources", "META-INF", "xposed")

# 现代模块的入口类，R8 keep 规则的底线（见 app/proguard-rules.pro）
ENTRY_CLASSES = (
    "Lio/github/MVennn/GSAuxiliary/GszxModule;",
)

# 必须是 applicationId 的包名 —— LSPosed 模块仓库的收录身份就靠它
EXPECTED_PACKAGE = "io.github.MVennn.GSAuxiliary"

# compileOnly 的 API 包：一个类定义都不许有
FORBIDDEN_CLASS_PREFIXES = (
    "Lio/github/libxposed/api",
    "Lio/github/libxposed/annotation",
)

# 必须被打进包的服务通道（见 app/build.gradle.kts 的 implementation）
SERVICE_CLASS_PREFIX = "Lio/github/libxposed/service"

# manifest 里**绝对不能有**的 legacy key。
# 注意：@string/xposed_description（字符串资源名，带下划线）与
# de.robv.android.xposed.category.MODULE_SETTINGS 是**合法且必需**的，
# 所以这里精确匹配这几个 key，不做宽匹配。
LEGACY_MANIFEST_KEYS = (
    "xposedminversion",
    "xposedmodule",
    "xposedsharedprefs",
    "xposedscope",
    "xposeddescription",
)

# module.prop 必须有的键（少了任何一条，框架行为会静默变化）
REQUIRED_MODULE_PROPS = (
    "minApiVersion",
    "targetApiVersion",
    "staticScope",
    "exceptionMode",
    "autoHotReload",
)
REQUIRED_MIN_API = "102"

# Android 官方调试证书的 CN。debug keystore 由每台机器各自生成，
# 但 DN 恒为 CN=Android Debug,O=Android,C=US —— 认这个字符串最稳。
DEBUG_CERT_MARK = b"Android Debug"

# --------------------------------------------------------------------------- dex

DEX_MAGIC = b"dex\n"
DEX_HEADER_SIZE = 0x70        # 标准 dex header 固定 0x70 字节
DEX_ENDIAN_TAG = 0x12345678   # 小端标记


def _uleb128(data, off):
    """读一个 uleb128，返回 (值, 新偏移)。"""
    result = 0
    shift = 0
    while True:
        if off >= len(data):
            raise ValueError("uleb128 越界")
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7
        if shift > 35:
            raise ValueError("uleb128 超过 5 字节，数据异常")


def _need(data, off, size, what):
    """确认 [off, off+size) 落在文件内，否则抛错 —— 越界要报清楚，不能静默。"""
    if off < 0 or size < 0 or off + size > len(data):
        raise ValueError("%s 越界（off=%d size=%d 文件=%d）"
                         % (what, off, size, len(data)))


def _string_at(data, string_ids_off, string_ids_size, idx):
    """取字符串池里第 idx 个字符串（MUTF-8；类描述符是纯 ASCII，直接按 UTF-8 解）。"""
    if idx >= string_ids_size:
        raise ValueError("string index %d 超出 string_ids（%d 个）" % (idx, string_ids_size))
    off = struct.unpack_from("<I", data, string_ids_off + idx * 4)[0]
    _need(data, off, 1, "string_data")
    _, p = _uleb128(data, off)
    end = data.find(b"\x00", p)
    if end < 0:
        raise ValueError("字符串没有终止符（off=%d）" % p)
    return data[p:end].decode("utf-8", "replace")


def dex_defined_classes(data):
    """列出这个 dex **定义**（而不是引用）的全部类描述符。

    走 class_defs 表 —— 这正是 `strings` / 字节扫描做不到的：
    模块自己的类里会有 io/github/libxposed/api/... 的**字符串常量引用**，
    那是误报，只有 class_defs 里的才是真定义。
    """
    if data[:4] != DEX_MAGIC:
        raise ValueError("不是 dex 文件（magic 不对）")

    _need(data, 0, DEX_HEADER_SIZE, "dex header")
    header_size, endian_tag = struct.unpack_from("<II", data, 36)
    if header_size != DEX_HEADER_SIZE:
        raise ValueError("header_size=%d，不是标准 0x70" % header_size)
    if endian_tag != DEX_ENDIAN_TAG:
        raise ValueError("endian_tag=0x%08x，大端或非标准 dex 不支持" % endian_tag)

    # header 里 56 字节起是连续 14 个 u32
    hdr = struct.unpack_from("<14I", data, 56)
    string_ids_size, string_ids_off = hdr[0], hdr[1]
    type_ids_size, type_ids_off = hdr[2], hdr[3]
    class_defs_size, class_defs_off = hdr[10], hdr[11]

    _need(data, string_ids_off, string_ids_size * 4, "string_ids")
    _need(data, type_ids_off, type_ids_size * 4, "type_ids")
    _need(data, class_defs_off, class_defs_size * 32, "class_defs")

    names = []
    for i in range(class_defs_size):
        class_idx = struct.unpack_from("<I", data, class_defs_off + i * 32)[0]
        if class_idx >= type_ids_size:
            raise ValueError("class_defs[%d] 的 class_idx=%d 超出 type_ids（%d 个）"
                             % (i, class_idx, type_ids_size))
        type_idx = struct.unpack_from("<I", data, type_ids_off + class_idx * 4)[0]
        names.append(_string_at(data, string_ids_off, string_ids_size, type_idx))
    return names


# --------------------------------------------------------------------------- 签名

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
SIG_SCHEME_V2_ID = 0x7109871A
SIG_SCHEME_V3_ID = 0xF05368C0
SIG_SCHEME_NAMES = ((SIG_SCHEME_V3_ID, "v3"), (SIG_SCHEME_V2_ID, "v2"))


class _Cursor(object):
    """APK 签名块那种「长度前缀」结构的最小游标（长度一律 uint32 LE）。"""

    def __init__(self, buf, what):
        self.buf = buf
        self.off = 0
        self.what = what

    def u32(self):
        if self.off + 4 > len(self.buf):
            raise ValueError("%s：读 uint32 越界" % self.what)
        v = struct.unpack_from("<I", self.buf, self.off)[0]
        self.off += 4
        return v

    def take(self, n):
        if n < 0 or self.off + n > len(self.buf):
            raise ValueError("%s：取 %d 字节越界" % (self.what, n))
        b = self.buf[self.off:self.off + n]
        self.off += n
        return b

    def sub(self):
        """读一个「uint32 长度 + 内容」的块，返回包住该内容的新游标。"""
        return _Cursor(self.take(self.u32()), self.what)


def _signer_cert_of_scheme(value, scheme):
    """v2 / v3 签名块 value -> 第一个签名者的第一张证书（DER 字节）。"""
    signers = _Cursor(value, "APK Signature Scheme " + scheme).sub()
    signer = signers.sub()
    signed_data = signer.sub()
    signed_data.sub()                    # digests
    certs = signed_data.sub()            # certificates
    return certs.take(certs.u32())


def apk_signer_cert(path):
    """解析 APK 的签名者证书。

    返回 `(scheme, cert_der)`；没签名返回 `(None, None)`；
    有签名块但解析不了返回 `(scheme, None)`（调用方据此给提醒，而不是假装通过）。

    优先 v3（含密钥轮换信息）再 v2。v1（JAR 签名）只认「存在」，
    因为解析 PKCS#7 得再写一大坨 —— 而 AGP 8 在 minSdk >= 24 时默认就不打 v1 了。
    """
    with open(path, "rb") as f:
        data = f.read()

    m = data.rfind(APK_SIG_BLOCK_MAGIC)
    if m != -1:
        for scheme_id, scheme in SIG_SCHEME_NAMES:
            try:
                if m < 8:
                    raise ValueError("APK Signing Block 位置异常")
                size = struct.unpack_from("<Q", data, m - 8)[0]
                block_start = m + 8 - size
                if block_start < 0 or block_start + 8 + size != m + 16:
                    raise ValueError("APK Signing Block 尺寸对不上（size=%d）" % size)
                pairs = _Cursor(data[block_start + 8:m - 8], "APK Signing Block")
                value = None
                while pairs.off < len(pairs.buf):
                    ln = struct.unpack_from("<Q", pairs.buf, pairs.off)[0]
                    pairs.off += 8
                    pair = pairs.take(ln)
                    if len(pair) < 4:
                        raise ValueError("签名块里的 pair 太短")
                    if struct.unpack_from("<I", pair, 0)[0] == scheme_id:
                        value = pair[4:]
                        break
                if value is None:
                    continue
                return scheme, _signer_cert_of_scheme(value, scheme)
            except (ValueError, struct.error):
                return scheme, None

    # 没有 v2/v3 签名块 —— 看有没有 v1（JAR 签名）
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            u = n.upper()
            if u.startswith("META-INF/") and u.endswith((".RSA", ".DSA", ".EC")):
                return "v1", None
    return None, None


# --------------------------------------------------------------------------- 报告

OK, WARN, FAIL = "ok", "warn", "fail"
_MARK = {OK: "[OK] ", WARN: "[!]  ", FAIL: "[X]  "}


class Report(object):
    """三级：OK 通过 / WARN 提醒（不影响退出码）/ FAIL 踩红线。"""

    def __init__(self, apk):
        self.apk = apk
        self.checks = []   # (级别, 说明)

    def add(self, level, msg):
        self.checks.append((level, msg))

    def good(self, msg):
        """记一条通过项。（不叫 `ok` —— 那是下面的布尔属性。）"""
        self.add(OK, msg)

    def warn(self, msg):
        self.add(WARN, msg)

    def fail(self, msg):
        self.add(FAIL, msg)

    @property
    def failed(self):
        return [m for lv, m in self.checks if lv == FAIL]

    @property
    def warned(self):
        return [m for lv, m in self.checks if lv == WARN]

    @property
    def ok(self):
        return not self.failed


def _parse_prop(text):
    """把 module.prop 那种 `key=value` 文件解析成 dict（忽略注释与空行）。"""
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def _check_source_meta(rep, z, names):
    """打包的元文件必须与源码母本逐字一致。

    为什么值得一条红线：`app/src/main/resources/META-INF/xposed/` 是母本，
    一旦被依赖（libxposed service 也带同名资源）覆盖、或改完忘了重建，
    APK 里就是旧值 —— 而「装上去不生效」这类问题极难从构建日志看出来。
    """
    if not os.path.isdir(META_SOURCE_DIR):
        rep.warn("找不到元文件母本 %s，跳过一致性校验" % META_SOURCE_DIR)
        return
    for name in ("module.prop", "java_init.list", "scope.list"):
        src = os.path.join(META_SOURCE_DIR, name)
        if not os.path.isfile(src):
            rep.fail("母本缺 %s（它在源码里必须存在）" % src)
            continue
        if META_DIR + name not in names:
            continue                       # 缺失已由上面那条检查报过
        packed = z.read(META_DIR + name)
        with open(src, "rb") as f:
            want = f.read()
        rep.add(OK if packed == want else FAIL,
                "打包的 %s 与源码母本%s" % (name, "一致" if packed == want else "**不一致**"))


def _check_dex(rep, z, names):
    dex_names = sorted(n for n in names if n.endswith(".dex"))
    rep.add(OK if dex_names else FAIL,
            "包内有 dex（%s）" % (", ".join(dex_names) if dex_names else "无"))

    defined = []
    for n in dex_names:
        try:
            defined.extend(dex_defined_classes(z.read(n)))
        except Exception as e:                      # noqa: BLE001
            rep.fail("解析 %s 失败：%s" % (n, e))

    if not defined:
        return

    for prefix in FORBIDDEN_CLASS_PREFIXES:
        hits = [c for c in defined if c.startswith(prefix)]
        rep.add(OK if not hits else FAIL,
                "【红线】dex 无 %s 类定义（发现 %d 个）" % (prefix[1:], len(hits)))

    service = [c for c in defined if c.startswith(SERVICE_CLASS_PREFIX)]
    rep.add(OK if service else FAIL,
            "libxposed service 已打进包（%d 个类）" % len(service))

    for entry_cls in ENTRY_CLASSES:
        rep.add(OK if entry_cls in defined else FAIL,
                "入口类已保留 %s" % entry_cls)

    rep.good("dex 类定义总数 %d" % len(defined))


def _check_manifest(rep, z, names):
    if "AndroidManifest.xml" not in names:
        rep.fail("包内没有 AndroidManifest.xml")
        return
    mf = z.read("AndroidManifest.xml")

    hits = []
    for k in LEGACY_MANIFEST_KEYS:
        # 二进制 XML 的字符串池可能是 UTF-8 或 UTF-16LE，两种都查
        if k.encode("ascii") in mf or k.encode("utf-16-le") in mf:
            hits.append(k)
    rep.add(OK if not hits else FAIL,
            "manifest 无 legacy xposed key" if not hits
            else "manifest 有 legacy xposed key：%s" % ", ".join(hits))

    pkg = EXPECTED_PACKAGE.encode("ascii")
    found = pkg in mf or EXPECTED_PACKAGE.encode("utf-16-le") in mf
    rep.add(OK if found else FAIL,
            "manifest 的 applicationId 仍是 %s" % EXPECTED_PACKAGE)


def _check_signing(rep, path, reject_debug, expect_sha256):
    scheme, cert = apk_signer_cert(path)
    if scheme is None:
        rep.fail("【红线】APK 没有签名（未签名的包根本装不上）")
        return
    if cert is None:
        rep.warn("检测到 %s 签名，但证书解析失败（--expect-cert-sha256 无法核对）" % scheme)
        if expect_sha256:
            rep.fail("要求核对证书指纹，但解析不出证书")
        return

    digest = hashlib.sha256(cert).hexdigest()
    rep.good("已签名（scheme %s）证书 SHA-256: %s" % (scheme, digest))

    is_debug = DEBUG_CERT_MARK in cert
    if is_debug:
        rep.add(FAIL if reject_debug else WARN,
                "签名证书是 **Android 调试证书**（CN=Android Debug）"
                + ("—— release 包绝不能这么发，已装用户必须卸载重装"
                   if reject_debug else "—— 仅供自测，不可用于发版"))
    elif reject_debug:
        rep.good("签名证书不是调试证书")

    if expect_sha256:
        want = expect_sha256.strip().lower().replace(":", "")
        rep.add(OK if digest == want else FAIL,
                "证书指纹与期望一致（%s）" % want if digest == want
                else "证书指纹不符：期望 %s，实际 %s" % (want, digest))


def check_apk(path, reject_debug=False, expect_sha256=None, source_check=True):
    rep = Report(path)

    with zipfile.ZipFile(path) as z:
        names = z.namelist()

        # --- 1. 现代模块元数据 ------------------------------------------------
        for m in META_REQUIRED:
            rep.add(OK if m in names else FAIL, "元数据 %s" % m)

        # --- 2. 不能有 legacy assets/xposed_init ------------------------------
        legacy = [n for n in names if n in ("assets/xposed_init", "assets/xposed_init.txt")]
        rep.add(OK if not legacy else FAIL, "无 legacy assets/xposed_init")

        # --- module.prop 关键键 ----------------------------------------------
        if META_DIR + "module.prop" in names:
            prop = z.read(META_DIR + "module.prop").decode("utf-8", "replace")
            keys = _parse_prop(prop)
            for need in REQUIRED_MODULE_PROPS:
                rep.add(OK if need in keys else FAIL, "module.prop 有 %s" % need)
            if "minApiVersion" in keys:
                rep.add(OK if keys["minApiVersion"] == REQUIRED_MIN_API else FAIL,
                        "minApiVersion=%s（实际 %s）"
                        % (REQUIRED_MIN_API, keys["minApiVersion"]))

        if META_DIR + "java_init.list" in names:
            entry = z.read(META_DIR + "java_init.list").decode("utf-8", "replace").strip()
            rep.add(OK if entry else FAIL,
                    "java_init.list 非空（%s）" % (entry.splitlines()[0] if entry else "空"))

        if META_DIR + "scope.list" in names:
            scope = z.read(META_DIR + "scope.list").decode("utf-8", "replace").strip()
            rep.add(OK if scope else FAIL,
                    "scope.list 非空（%s）" % (scope.splitlines()[0] if scope else "空"))

        # --- 3/4. dex 类定义 ---------------------------------------------------
        _check_dex(rep, z, names)

        # --- 5. manifest -------------------------------------------------------
        _check_manifest(rep, z, names)

        # --- 6. 元文件 vs 源码母本 ---------------------------------------------
        if source_check:
            _check_source_meta(rep, z, names)

    # --- 7. 签名 ---------------------------------------------------------------
    _check_signing(rep, path, reject_debug, expect_sha256)

    return rep


# --------------------------------------------------------------------------- main

def main(argv):
    ap = argparse.ArgumentParser(
        prog="verify_apk.py",
        description="APK 产物红线自查（纯标准库，CI 与本地共用）",
        add_help=True)
    ap.add_argument("apk", nargs="+", help="要校验的 APK（可多个）")
    ap.add_argument("--reject-debug-cert", action="store_true",
                    help="签名证书不能是 Android 调试证书（release 发版必须带这个）")
    ap.add_argument("--expect-cert-sha256", metavar="HEX",
                    default=os.environ.get("GS_EXPECT_CERT_SHA256", ""),
                    help="期望的签名证书 SHA-256（默认读环境变量 GS_EXPECT_CERT_SHA256）")
    ap.add_argument("--no-source-check", action="store_true",
                    help="跳过「打包元文件 vs 源码母本」一致性校验")
    ap.add_argument("--quiet", action="store_true",
                    help="只打印提醒与红线，不逐条打印 [OK]")
    args = ap.parse_args(argv[1:])

    all_ok = True
    warn_total = 0
    for apk in args.apk:
        print("=" * 66)
        print("校验 %s" % apk)
        print("=" * 66)
        try:
            rep = check_apk(apk,
                            reject_debug=args.reject_debug_cert,
                            expect_sha256=args.expect_cert_sha256,
                            source_check=not args.no_source_check)
        except FileNotFoundError:
            print("  [X]  文件不存在")
            all_ok = False
            continue
        except zipfile.BadZipFile as e:
            print("  [X]  不是合法的 APK/zip：%s" % e)
            all_ok = False
            continue
        except OSError as e:
            print("  [X]  读不了这个文件：%s" % e)
            all_ok = False
            continue

        for level, msg in rep.checks:
            if args.quiet and level == OK:
                continue
            print("  %s%s" % (_MARK[level], msg))
        print("")
        warn_total += len(rep.warned)
        if rep.ok:
            tail = "全部通过" + ("（%d 条提醒）" % len(rep.warned) if rep.warned else "")
        else:
            tail = "有 %d 条红线被踩，产物不可发布" % len(rep.failed)
        print("  => %s" % tail)
        all_ok = all_ok and rep.ok

    if not all_ok:
        print("")
        print("红线清单与逐条说明见 docs/BUILD.md §8.3 / docs/RELEASE.md §3")
    elif warn_total:
        print("")
        print("提醒不是错误，但别当成没看见。")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
