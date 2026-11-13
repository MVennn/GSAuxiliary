#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把从页面抓下来的行情 tsv 逐只对腾讯行情核一遍。

为什么要有这个：模块对行级行情是**纯透传**（宿主 StockInfo → adapter），没有一行
是自己算的；但「透传」这件事本身需要证据，否则「数据对不对」永远只能靠肉眼看几只。
真机验收时用 dump 脚本把整屏逐屏抓成 tsv，再跑本脚本逐只比对。

用法：
    python tools/audit/compare_quotes.py rows.tsv [报告.md]

tsv 列：code<TAB>name<TAB>price<TAB>zdf（涨跌幅带不带 % 都行；首行若是表头会自动跳过）

容差：价格 0.011 元、涨跌幅 0.02 个百分点（页面四舍五入到分 / 两位小数）。
口径提醒：**午休时段（11:30—13:00）报价冻结**，这时抓的页面与随后查的外部源必然同值，
是核「值对不对」的最佳窗口；盘中的抓取则只能证明「抓的这一刻对得上」。

退出码：0 = 无偏差；1 = 有偏差或缺数；2 = 用法错。
"""

import io
import sys
import urllib.request

UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120 Safari/537.36",
    "Referer": "https://gu.qq.com/",
}
PRICE_TOL = 0.011
ZDF_TOL = 0.02
BATCH = 60


def market_prefix(code):
    """腾讯行情的前缀：沪 6/9、深 0/3、北 8/920。"""
    if code.startswith("920") or code.startswith("8"):
        return "bj"
    if code[0] in "69":
        return "sh"
    if code[0] in "03":
        return "sz"
    return "sz"


def fetch(codes):
    """批量取腾讯行情，返回 {code: (name, price, zdf)}。"""
    out = {}
    for i in range(0, len(codes), BATCH):
        part = codes[i:i + BATCH]
        url = "http://qt.gtimg.cn/q=" + ",".join(market_prefix(c) + c for c in part)
        raw = urllib.request.urlopen(
            urllib.request.Request(url, headers=UA), timeout=15,
        ).read().decode("gbk", "replace")
        for line in raw.split(";"):
            if '"' not in line:
                continue
            f = line.split('"')[1].split("~")
            if len(f) > 33:
                out[f[2]] = (f[1], f[3], f[32])
    return out


def read_rows(path):
    """读 tsv；首行不是 6 位代码就当表头跳过。"""
    lines = io.open(path, encoding="utf-8").read().splitlines()
    rows = []
    for i, line in enumerate(lines):
        cols = line.split("\t")
        if len(cols) != 4:
            continue
        if i == 0 and not (len(cols[0]) == 6 and cols[0].isdigit()):
            continue
        rows.append(cols)
    return rows


def main(argv):
    if len(argv) < 2:
        sys.stderr.write("用法: compare_quotes.py rows.tsv [报告.md]\n")
        return 2
    rows = read_rows(argv[1])
    codes = [r[0] for r in rows]
    ext = fetch(codes)
    bad_price, bad_zdf, missing = [], [], []
    for code, name, price, zdf in rows:
        e = ext.get(code)
        if e is None:
            missing.append((code, name))
            continue
        try:
            p = float(price)
            z = float(zdf.replace("%", ""))
            ep = float(e[1])
            ez = float(e[2])
        except ValueError:
            missing.append((code, name))
            continue
        if abs(p - ep) > PRICE_TOL:
            bad_price.append((code, name, price, e[1]))
        if abs(z - ez) > ZDF_TOL:
            bad_zdf.append((code, name, zdf, e[2]))
    lines = [];
    lines.append("rows=%d uniq=%d ext=%d missing=%d bad_price=%d bad_zdf=%d" % (
        len(rows), len({r[0] for r in rows}), len(ext), len(missing),
        len(bad_price), len(bad_zdf),
    ))
    for tag, items in (("MISSING", missing), ("PRICE", bad_price), ("ZDF", bad_zdf)):
        for it in items[:20]:
            lines.append("%s %s" % (tag, " | ".join(str(x) for x in it)))
    text = "\n".join(lines)
    if len(argv) > 2:
        io.open(argv[2], "w", encoding="utf-8").write(text + "\n")
    print(text)
    return 0 if not (missing or bad_price or bad_zdf) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))