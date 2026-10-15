#!/usr/bin/env python3
"""
enable_lsposed.py —— 直接把模块写进 LSPosed 配置库

用法:
    python enable_lsposed.py <modules_config.db> <module_pkg> <apk_path> <target_pkg> [user_id]

会做三件事:
    1. modules       表 —— 登记模块包名与 APK 路径
    2. modules_state 表 —— 把模块设为 enabled=1
    3. scope         表 —— 把目标 App 加入模块作用域
"""
import sqlite3
import sys

db_path = sys.argv[1]
module_pkg = sys.argv[2]
apk_path = sys.argv[3]
target_pkg = sys.argv[4]
user_id = int(sys.argv[5]) if len(sys.argv) > 5 else 0

con = sqlite3.connect(db_path)
cur = con.cursor()


def cols(table):
    return [d[1] for d in cur.execute("PRAGMA table_info(%s)" % table).fetchall()]


print("[before] modules      :", cur.execute(
    "select count(*) from modules").fetchone()[0])
print("[before] modules_state:", cur.execute(
    "select count(*) from modules_state").fetchone()[0])
print("[before] scope        :", cur.execute(
    "select count(*) from scope").fetchone()[0])

# 1) modules
mc = cols("modules")
if "apk_path" in mc:
    cur.execute("DELETE FROM modules WHERE module_pkg_name=?", (module_pkg,))
    cur.execute(
        "INSERT INTO modules (module_pkg_name, apk_path) VALUES (?, ?)",
        (module_pkg, apk_path))
else:
    cur.execute("DELETE FROM modules WHERE module_pkg_name=?", (module_pkg,))
    cur.execute("INSERT INTO modules (module_pkg_name) VALUES (?)", (module_pkg,))

# 2) modules_state
sc = cols("modules_state")
row = {"module_pkg_name": module_pkg, "user_id": user_id,
       "enabled": 1, "scope_request_blocked": 0}
row = {k: v for k, v in row.items() if k in sc}
cur.execute("DELETE FROM modules_state WHERE module_pkg_name=? AND user_id=?",
            (module_pkg, user_id))
cur.execute(
    "INSERT INTO modules_state (%s) VALUES (%s)"
    % (", ".join(row.keys()), ", ".join("?" * len(row))),
    tuple(row.values()))

# 3) scope
cur.execute("DELETE FROM scope WHERE module_pkg_name=?", (module_pkg,))
cur.execute("INSERT INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES (?, ?, ?)",
            (module_pkg, target_pkg, user_id))

con.commit()

print("[after ] modules      :", cur.execute(
    "select count(*) from modules").fetchone()[0])
print("[after ] modules_state:", cur.execute(
    "select count(*) from modules_state").fetchone()[0])
print("[after ] scope        :", cur.execute(
    "select count(*) from scope").fetchone()[0])
print()
print("modules 行      :", cur.execute(
    "select * from modules where module_pkg_name=?", (module_pkg,)).fetchall())
print("modules_state 行:", cur.execute(
    "select * from modules_state where module_pkg_name=?", (module_pkg,)).fetchall())
print("scope 行        :", cur.execute(
    "select * from scope where module_pkg_name=?", (module_pkg,)).fetchall())
con.close()
