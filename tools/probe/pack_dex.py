#!/usr/bin/env python3
"""
pack_dex.py — 把 classes.dex（以及可选的附加文件树）塞进 aapt2 产出的 APK

保留原有条目的压缩方式（resources.arsc 必须保持 STORED，否则 Android 11+
无法 mmap，会导致安装或运行异常）。

现代 Xposed API 模块要求 APK 里带 META-INF/xposed/{module.prop,java_init.list,scope.list}。
这些文件不在 res/ 也不在 assets/ 里（assets 会被 aapt2 放到 assets/ 下，路径不对），
所以用第 4 个参数把一整棵目录树按原样合并进 APK 根。

用法:
  python pack_dex.py <base.apk> <classes.dex> <out.apk> [extra_dir]
"""
import os
import sys
import zipfile


def main():
    if len(sys.argv) not in (4, 5):
        print("用法: pack_dex.py <base.apk> <classes.dex> <out.apk> [extra_dir]")
        return 1

    apk_in, dex_path, apk_out = sys.argv[1], sys.argv[2], sys.argv[3]
    extra_dir = sys.argv[4] if len(sys.argv) == 5 else None

    zin = zipfile.ZipFile(apk_in, "r")
    zout = zipfile.ZipFile(apk_out, "w", zipfile.ZIP_DEFLATED)

    written = set()

    # 1) 原样搬运 aapt2 产出的条目（去掉可能残留的旧 classes.dex）
    for item in zin.infolist():
        if item.filename == "classes.dex":
            continue
        data = zin.read(item.filename)
        info = zipfile.ZipInfo(item.filename, date_time=item.date_time)
        info.compress_type = item.compress_type      # 保留原压缩方式
        info.external_attr = item.external_attr
        info.internal_attr = item.internal_attr
        info.create_system = item.create_system
        zout.writestr(info, data)
        written.add(item.filename)

    # 2) 写入 dex
    with open(dex_path, "rb") as f:
        zout.writestr("classes.dex", f.read(), zipfile.ZIP_DEFLATED)
        written.add("classes.dex")

    # 3) 合并附加目录树（META-INF/xposed/… 等）
    if extra_dir:
        extra_dir = os.path.abspath(extra_dir)
        if not os.path.isdir(extra_dir):
            print("  [!] extra_dir 不存在: %s" % extra_dir)
            return 1
        for root, _dirs, files in os.walk(extra_dir):
            for name in files:
                full = os.path.join(root, name)
                arc = os.path.relpath(full, extra_dir).replace(os.sep, "/")
                if arc in written:
                    continue
                with open(full, "rb") as f:
                    zout.writestr(arc, f.read(), zipfile.ZIP_DEFLATED)
                written.add(arc)
                print("  + %s" % arc)

    zin.close()
    zout.close()
    print("  packed: %s + %s -> %s" % (apk_in, dex_path, apk_out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
