#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_apk.py —— 对构建产物做「红线」自查（CI 与本地共用）。

为什么要有它
------------
`docs/BUILD.md` / `docs/RELEASE.md` 里列了一堆自查项，但它们散在文档里，
靠人手工跑 `dexdump` / `aapt2`，发版时漏一条就会出「编译通过、装上去不生效」
这种最难查的问题。这个脚本把它们固化成一条命令。

不依赖 dexdump / aapt2 —— dex 的类定义表自己解析，纯标准库，
所以在最小环境（CI runner、干净容器）里也能跑。

检查项
------
1. `META-INF/xposed/{module.prop,java_init.list,scope.list}` 三个元数据在包内
2. 没有 legacy 的 `assets/xposed_init`（有它 = LSPosed 会当老模块，静默不加载）
3. 【红线】dex 里不能有 `io/github/libxposed/api` 的**类定义**
   （api 必须 compileOnly；只有 service 可以被打进包）
4. 入口类 `GszxModule` 的类定义在（R8 keep 规则生效）
5. `AndroidManifest.xml` 里没有 legacy 的 `xposedminversion` 等 meta-data key

用法
----
    python tools/ci/verify_apk.py app/build/outputs/apk/release/app-release.apk
    python tools/ci/verify_apk.py <apk> [<apk> ...]

退出码：0 = 全部通过；1 = 有红线被踩。
"""

import struct
import sys
import zipfile

# --------------------------------------------------------------------------- 常量

META_REQUIRED = (
    "META-INF/xposed/module.prop",
    "META-INF/xposed/java_init.list",
    "META-INF/xposed/scope.list",
)

# 现代模块的入口类，R8 keep 规则的底线（见 app/proguard-rules.pro）
ENTRY_CLASSES = (
    "Lio/github/SansKritO4/GSAuxiliary/GszxModule;",
)

# compileOnly 的 API 包：一个类定义都不许有
FORBIDDEN_CLASS_PREFIX = "Lio/github/libxposed/api"

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


# --------------------------------------------------------------------------- dex

def _uleb128(data, off):
    """读一个 uleb128，返回 (值, 新偏移)。"""
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def _string_at(data, string_ids_off, idx):
    """取字符串池里第 idx 个字符串（MUTF-8；类描述符是纯 ASCII，直接按 UTF-8 解）。"""
    off = struct.unpack_from("<I", data, string_ids_off + idx * 4)[0]
    _, p = _uleb128(data, off)
    end = data.index(b"\x00", p)
    return data[p:end].decode("utf-8", "replace")


def dex_defined_classes(data):
    """列出这个 dex **定义**（而不是引用）的全部类描述符。

    走 class_defs 表 —— 这正是 `strings` / 字节扫描做不到的：
    模块自己的类里会有 io/github/libxposed/api/... 的**字符串常量引用**，
    那是误报，只有 class_defs 里的才是真定义。
    """
    if data[:4] != b"dex\n":
        raise ValueError("不是 dex 文件（magic 不对）")

    # header 里 56 字节起是连续 14 个 u32
    hdr = struct.unpack_from("<14I", data, 56)
    string_ids_off = hdr[1]
    type_ids_off = hdr[3]
    class_defs_size = hdr[10]
    class_defs_off = hdr[11]

    names = []
    for i in range(class_defs_size):
        class_idx = struct.unpack_from("<I", data, class_defs_off + i * 32)[0]
        type_idx = struct.unpack_from("<I", data, type_ids_off + class_idx * 4)[0]
        names.append(_string_at(data, string_ids_off, type_idx))
    return names


# --------------------------------------------------------------------------- 检查

class Report(object):
    def __init__(self, apk):
        self.apk = apk
        self.checks = []   # (是否通过, 说明)

    def add(self, ok, msg):
        self.checks.append((bool(ok), msg))

    @property
    def ok(self):
        return all(c[0] for c in self.checks)


def check_apk(path):
    rep = Report(path)

    with zipfile.ZipFile(path) as z:
        names = z.namelist()

        # --- 1. 现代模块元数据 ------------------------------------------------
        for m in META_REQUIRED:
            rep.add(m in names, "元数据 %s" % m)

        # --- 2. 不能有 legacy assets/xposed_init ------------------------------
        legacy = [n for n in names if n in ("assets/xposed_init", "assets/xposed_init.txt")]
        rep.add(not legacy, "无 legacy assets/xposed_init")

        # --- module.prop 关键键 ----------------------------------------------
        if "META-INF/xposed/module.prop" in names:
            prop = z.read("META-INF/xposed/module.prop").decode("utf-8", "replace")
            keys = {}
            for line in prop.splitlines():
                line = line.strip()
                if line and not line.startswith(("#", "!")) and "=" in line:
                    k, v = line.split("=", 1)
                    keys[k.strip()] = v.strip()
            for need in ("minApiVersion", "targetApiVersion", "staticScope"):
                rep.add(need in keys, "module.prop 有 %s" % need)
            if "minApiVersion" in keys:
                rep.add(keys["minApiVersion"] == "102",
                        "minApiVersion=102（实际 %s）" % keys["minApiVersion"])

        if "META-INF/xposed/java_init.list" in names:
            entry = z.read("META-INF/xposed/java_init.list").decode("utf-8", "replace").strip()
            rep.add(bool(entry), "java_init.list 非空（%s）" % (entry.splitlines()[0] if entry else "空"))

        # --- 3/4. dex 类定义 ---------------------------------------------------
        dex_names = sorted(n for n in names if n.endswith(".dex"))
        rep.add(bool(dex_names), "包内有 dex（%s）" % ", ".join(dex_names) or "无")

        defined = []
        for n in dex_names:
            try:
                defined.extend(dex_defined_classes(z.read(n)))
            except Exception as e:                      # noqa: BLE001
                rep.add(False, "解析 %s 失败：%s" % (n, e))

        if defined:
            forbidden = [c for c in defined if c.startswith(FORBIDDEN_CLASS_PREFIX)]
            rep.add(not forbidden,
                    "【红线】dex 无 libxposed api 类定义（发现 %d 个）" % len(forbidden))

            service = [c for c in defined if c.startswith("Lio/github/libxposed/service")]
            rep.add(bool(service),
                    "libxposed service 已打进包（%d 个类）" % len(service))

            for entry_cls in ENTRY_CLASSES:
                rep.add(entry_cls in defined, "入口类已保留 %s" % entry_cls)

            rep.add(True, "dex 类定义总数 %d" % len(defined))

        # --- 5. manifest legacy key -------------------------------------------
        if "AndroidManifest.xml" in names:
            mf = z.read("AndroidManifest.xml")
            hits = []
            for k in LEGACY_MANIFEST_KEYS:
                # 二进制 XML 的字符串池可能是 UTF-8 或 UTF-16LE，两种都查
                if k.encode("ascii") in mf or k.encode("utf-16-le") in mf:
                    hits.append(k)
            if hits:
                rep.add(False, "manifest 有 legacy xposed key：%s" % ", ".join(hits))
            else:
                rep.add(True, "manifest 无 legacy xposed key")
        else:
            rep.add(False, "包内没有 AndroidManifest.xml")

    return rep


# --------------------------------------------------------------------------- main

def main(argv):
    if len(argv) < 2:
        print(__doc__.strip())
        return 2

    all_ok = True
    for apk in argv[1:]:
        print("=" * 66)
        print("校验 %s" % apk)
        print("=" * 66)
        try:
            rep = check_apk(apk)
        except FileNotFoundError:
            print("  [X] 文件不存在")
            all_ok = False
            continue
        except zipfile.BadZipFile as e:
            print("  [X] 不是合法的 APK/zip：%s" % e)
            all_ok = False
            continue

        for ok, msg in rep.checks:
            print("  %s %s" % ("[OK]" if ok else "[X] ", msg))
        print("")
        print("  => %s" % ("全部通过" if rep.ok else "有红线被踩，产物不可发布"))
        all_ok = all_ok and rep.ok

    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
