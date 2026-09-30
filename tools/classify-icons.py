#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把项目里的钱迹图标按「是否属于钱迹原创美术作品」分类，产出待替换清单。

分类规则（详见 docs 说明）：
  A 通用分类图标   —— category.json / default_category.json 里的图标，必须替换
  B 通用资产图标   —— assets.json 里表示「钱的形态」的图标（现金/银行卡/公积金…），必须替换
  C 品牌标识       —— 银行、支付平台、加密货币 token 等第三方品牌 logo，可保留
  D 兜底/默认图标  —— 源码里硬编码的默认值

用法：
    python tools/classify-icons.py                    # 生成 CSV + Markdown 清单
    python tools/classify-icons.py --csv-only         # 只出 CSV
"""

import argparse
import collections
import csv
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_DIR = os.path.join(ROOT, "app", "src", "main", "res", "raw")
SRC_DIR = os.path.join(ROOT, "app", "src", "main", "java")
OUT_DIR = os.path.join(ROOT, "docs")

# ---------------------------------------------------------------- 数据读取


def load_json(name):
    path = os.path.join(RAW_DIR, name)
    if not os.path.isfile(path):
        return None
    with io.open(path, encoding="utf-8") as fh:
        return json.load(fh)


def iter_icons(node, path="", out=None):
    """递归收集所有 (icon_url, 中文名) 对。"""
    if out is None:
        out = []
    if isinstance(node, dict):
        if isinstance(node.get("icon"), str) and node["icon"].startswith("http"):
            out.append((node["icon"], node.get("name", "")))
        for k, v in node.items():
            if k == "icon" and isinstance(v, str) and v.startswith("http"):
                continue
            if isinstance(v, (dict, list)):
                iter_icons(v, path, out)
        # 形如 {"支出": [{...}]} 的顶层字典，键本身携带语义
    elif isinstance(node, list):
        for item in node:
            iter_icons(item, path, out)
    return out


def source_hardcoded_urls():
    """源码里硬编码的 qianjiapp URL。"""
    pat = re.compile(r"""https?://res\d*\.qianjiapp\.com/[^"'\s\\)]+""")
    found = collections.OrderedDict()
    for dirpath, _dirs, files in os.walk(SRC_DIR):
        for name in sorted(files):
            if not name.endswith((".kt", ".java")):
                continue
            full = os.path.join(dirpath, name)
            with io.open(full, encoding="utf-8") as fh:
                for lineno, line in enumerate(fh, 1):
                    for url in pat.findall(line):
                        if url.lower().endswith((".png", ".jpg", ".jpeg", ".webp", ".gif")):
                            found.setdefault(url, []).append(
                                "%s:%d" % (os.path.relpath(full, ROOT).replace("\\", "/"), lineno)
                            )
    return found


# ---------------------------------------------------------------- 分类规则

# C 类判定的中文名关键词（第三方品牌 / 机构 / 产品）
BRAND_NAME_KEYWORDS = (
    "支付宝", "微信", "云闪付", "京东", "美团", "抖音", "华为", "小米", "OPPO", "vivo",
    "Apple", "苹果", "Paypal", "PayPal", "QQ", "淘宝", "拼多多", "苏宁", "携程",
    "数字人民币", "花呗", "借呗", "白条", "月付", "分付", "零钱通", "余额宝",
    "银联", "万事达", "Master", "Visa", "币安", "Coinbase", "MetaMask",
    # 机构类：银行 / 信用社 / 证券 / 信贷 等带机构名的条目
    "银行", "銀行", "信用社", "信合", "农商", "農商", "证券", "證券", "信贷", "信貸",
    "储蓄", "儲蓄", "社保卡",
)

# B 类判定的中文名关键词（钱的形态 / 通用金融概念，属于通用表现）
GENERIC_ASSET_NAMES = {
    "现金", "银行卡", "其它信用卡", "公积金", "医保", "话费", "债券", "基金", "黄金",
    "股票", "存款", "理财", "保险", "房产", "汽车", "收藏", "其它", "其他", "负债",
    "应收", "应付", "预付款", "投资", "定期", "活期", "数字货币", "外汇", "积分",
    "补贴", "退款", "红包", "礼品卡", "购物卡", "代金券", "会员卡", "储值卡",
}

# B 类：资产名含以下词且不含机构名时，判为通用金融概念（钱迹原创图标）
GENERIC_ASSET_KEYWORDS = (
    "钱包", "卡", "存", "账户", "賬戶", "借入", "借出", "理财", "投資", "投资",
    "收益", "期货", "期貨", "押金", "固定收益", "点券", "点数", "余额", "餘額",
)

# 明显属于通用金融概念、不含品牌信息的资产名白名单（精确匹配优先）
GENERIC_ASSET_EXACT = {
    "借入", "借出", "公交卡", "其它理财", "加油卡", "固定收益", "押金", "期货",
    "水电", "饭卡", "石化钱包", "水费", "电费", "燃气费", "物业费", "网络费",
    "学费", "医疗", "美容", "宠物", "数码", "家居", "旅行", "娱乐",
}

# assets.json 里的第三方品牌产品（含「钱包」等通用词但实为品牌，须先于通用词判定）
BRAND_PRODUCT_NAMES = (
    "多多钱包", "华为钱包", "小米钱包", "QQ钱包", "京东", "美团", "抖音",
    "微信", "支付宝", "云闪付", "数字人民币", "苏宁", "携程", "淘宝", "拼多多",
)

# 分类库里出现的第三方品牌/产品条目（这些是品牌，不是钱迹原创分类图标）
BRAND_CATEGORY_NAMES = {
    "115网盘", "K歌", "NFT数字藏品", "QQ会员", "QQ阅读", "SPA", "WPS", "iCloud",
    "万斯", "下载工具", "亚马逊", "优酷", "云存储", "书旗小说", "哔哩哔哩",
    "抖音", "淘宝", "微信", "支付宝", "京东", "拼多多", "美团", "饿了么",
    "爱奇艺", "腾讯视频", "网易云音乐", "QQ音乐", "喜马拉雅", "知乎", "微博",
    "小红书", "闲鱼", "滴滴", "高德", "百度", "谷歌", "微软", "Netflix",
    "Spotify", "Steam", "PSN", "Xbox", "Switch", "任天堂", "索尼",
    "芒果TV", "芒果tv", "快手", "斗鱼", "虎牙", "得到", "keep", "Keep",
}

# B 类判定的中文名关键词（钱的形态 / 通用金融概念，属于通用表现）
GENERIC_ASSET_NAMES = {
    "现金", "银行卡", "其它信用卡", "公积金", "医保", "话费", "债券", "基金", "黄金",
    "股票", "存款", "理财", "保险", "房产", "汽车", "收藏", "其它", "其他", "负债",
    "应收", "应付", "预付款", "投资", "定期", "活期", "数字货币", "外汇", "积分",
    "补贴", "退款", "红包", "礼品卡", "购物卡", "代金券", "会员卡", "储值卡",
}

# 文件名前缀明显属于第三方品牌的
BRAND_FILE_PREFIXES = ("bank_", "icc_", "cate_bank")

# 加密货币 token（品牌符号，C 类）
CRYPTO_HINT = re.compile(r"^(btc|eth|xmr|ltc|xrp|eos|ada|doge|bnb|trx|pepe|adx|cvc|enj|"
                         r"gsc|neu|qash|veri|dlt|cob|evx|eng|payx|wings|lun|sls|sib|"
                         r"tkn|block|mith|san|brd|aion|cage|wgr|uma|nxt|btx|icx|zec|"
                         r"dash|xvg|nano|link|atom|xtz|theta|vet|hbar|fil|algo)$", re.I)

# 银行文件名前缀（国际 + 港澳台 + 国内，覆盖面要广）
BANK_FILE_PREFIX = re.compile(r"^(ic_bank|icc|bank_|cate_bank|ic_banks)", re.I)

# 非中文名判定：纯 ASCII 且看起来是机构名（含 Bank / 公司后缀 / 全大写缩写）
INSTITUTION_EN = re.compile(
    r"(bank|banc|banco|credit|credito|trust|savings|finance|financial|"
    r"union|group|holding|capital|securities|insurance|post|paypal|"
    r"mastercard|visa|amex|express)", re.I)

CATEGORY_FILES = {"category.json", "default_category.json"}


def classify(url, name, source_kind):
    """返回 (类别, 判定依据)。

    A  通用分类图标     必须替换
    B  通用资产图标     必须替换
    D  源码硬编码       必须替换
    C  第三方品牌标识   可保留
    C? 需人工确认
    """
    fname = url.rsplit("/", 1)[-1]
    stem = fname.rsplit(".", 1)[0].lower()
    is_ascii_name = all(ord(c) < 128 for c in name) if name else True

    # --- 源码硬编码 ---
    if source_kind == "source":
        return "D", "源码硬编码默认值"

    # --- 分类体系里的图标 ---
    if source_kind in CATEGORY_FILES:
        if BANK_FILE_PREFIX.match(stem):
            return "C", "分类里的银行条目"
        # 分类里也可能出现第三方品牌/产品条目，这些同样不换
        for kw in BRAND_NAME_KEYWORDS:
            if kw in name:
                return "C", "分类里的品牌条目「%s」" % kw
        if name in BRAND_CATEGORY_NAMES:
            return "C", "分类里的品牌产品条目"
        return "A", "分类库图标"

    # --- assets.json ---
    if source_kind == "assets.json":
        # 1) 银行/机构文件名前缀
        if BANK_FILE_PREFIX.match(stem):
            return "C", "文件名前缀为银行/机构"

        # 2) 中文名含品牌/机构词 —— 必须最先判，否则会被通用词误吸
        for kw in BRAND_NAME_KEYWORDS:
            if kw in name:
                return "C", "中文名含机构/品牌词「%s」" % kw
        for brand in BRAND_PRODUCT_NAMES:
            if brand in name:
                return "C", "中文名含品牌「%s」" % brand

        # 3) 精确白名单：通用金融概念
        if name in GENERIC_ASSET_EXACT:
            return "B", "通用金融概念（白名单）"

        # 4) 精确白名单：通用资产名（钱迹原创）
        if name in GENERIC_ASSET_NAMES:
            return "B", "通用金融概念"

        # 5) 加密货币 token
        if CRYPTO_HINT.match(stem):
            return "C", "加密货币 token 符号"

        # 6) 通用金融关键词（此时已排除品牌）
        for kw in GENERIC_ASSET_KEYWORDS:
            if kw in name:
                return "B", "通用金融概念「%s」" % kw

        # 7) 纯英文名 + 含机构后缀 → C
        if is_ascii_name and INSTITUTION_EN.search(name):
            return "C", "英文机构名"

        # 8) 纯英文名、短名（多为加密 token 或平台） → C，但置信度低
        if is_ascii_name and len(name.split()) <= 4 and len(name) <= 30:
            return "C", "英文短名（疑似 token/平台）"

        # 9) 其余交人工
        return "C?", "未命中规则，需人工确认"

    return "?", "未分类"


# ---------------------------------------------------------------- 主流程


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv-only", action="store_true", help="只生成 CSV")
    args = parser.parse_args()

    records = collections.OrderedDict()  # url -> record

    for fname in ("category.json", "default_category.json", "assets.json"):
        data = load_json(fname)
        if data is None:
            continue
        for url, name in iter_icons(data):
            rec = records.setdefault(url, {"url": url, "names": set(), "sources": set()})
            if name:
                rec["names"].add(name)
            rec["sources"].add(fname)

    for url, locations in source_hardcoded_urls().items():
        rec = records.setdefault(url, {"url": url, "names": set(), "sources": set()})
        rec["source_locations"] = locations

    rows = []
    for url, rec in records.items():
        names = sorted(rec["names"])
        name = " / ".join(names)
        sources = sorted(rec["sources"])
        if "source_locations" in rec:
            kind = "source"
        elif sources:
            # 分类文件优先判定
            kind = next((s for s in sources if s in CATEGORY_FILES), sources[0])
        else:
            kind = "unknown"
        cat, reason = classify(url, name, kind)
        rows.append({
            "类别": cat,
            "文件名": url.rsplit("/", 1)[-1],
            "中文名": name,
            "来源": ",".join(sources) or "源码",
            "判定依据": reason,
            "URL": url,
        })

    order = {"A": 0, "B": 1, "D": 2, "C?": 3, "C": 4, "?": 5}
    rows.sort(key=lambda r: (order.get(r["类别"], 9), r["中文名"], r["文件名"]))

    os.makedirs(OUT_DIR, exist_ok=True)
    csv_path = os.path.join(OUT_DIR, "图标替换清单.csv")
    with io.open(csv_path, "w", encoding="utf-8-sig", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=["类别", "文件名", "中文名", "来源", "判定依据", "URL"])
        w.writeheader()
        w.writerows(rows)

    counter = collections.Counter(r["类别"] for r in rows)
    print("总条目 %d" % len(rows))
    for k in ("A", "B", "D", "C?", "C", "?"):
        if counter.get(k):
            print("  %-3s %d" % (k, counter[k]))
    print("CSV 已写入 %s" % os.path.relpath(csv_path, ROOT))

    if not args.csv_only:
        need = counter.get("A", 0) + counter.get("B", 0) + counter.get("D", 0)
        md_path = os.path.join(OUT_DIR, "图标替换清单.md")
        with io.open(md_path, "w", encoding="utf-8") as fh:
            fh.write("# 图标替换清单\n\n")
            fh.write("本清单由 `python tools/classify-icons.py` 自动生成，"
                     "用于区分「钱迹原创美术作品」与「第三方品牌标识」。\n\n")
            fh.write("## 分类规则\n\n")
            fh.write("- **A 通用分类图标**：`category.json` / `default_category.json` 中的语义图标"
                     "（餐饮、交通、律师费…）。与钱迹分类体系绑定，**必须替换**。\n")
            fh.write("- **B 通用资产图标**：`assets.json` 中表示「钱的形态」的图标"
                     "（现金、银行卡、公积金…）。虽在资产库中，但同为钱迹原创美术作品，**必须替换**。\n")
            fh.write("- **D 源码硬编码默认图标**：`AssetIconDefaults` 等处的兜底图标，**必须替换**。\n")
            fh.write("- **C 第三方品牌标识**：银行 logo（`ic_bank_*`）、支付平台、"
                     "加密货币 token 等。这些是各品牌方的商标资产，钱迹亦非原创，"
                     "**可保留**（在 App 内标示资金来源属指示性合理使用）。\n")
            fh.write("- **C?**：规则未命中，需人工确认。\n\n")
            fh.write("## 汇总\n\n")
            fh.write("总条目 **%d**，其中**需替换 %d 个**，可保留 %d 个。\n\n"
                     % (len(rows), need, counter.get("C", 0)))
            fh.write("| 类别 | 含义 | 数量 | 处理建议 |\n|---|---|---|---|\n")
            md_rows = [
                ("A", "通用分类图标", "必须替换"),
                ("B", "通用资产图标", "必须替换"),
                ("D", "源码硬编码默认图标", "必须替换"),
                ("C?", "待人工确认", "逐个确认后归类"),
                ("C", "第三方品牌标识", "可保留"),
            ]
            for key, label, advice in md_rows:
                if counter.get(key):
                    fh.write("| %s | %s | %d | %s |\n" % (key, label, counter[key], advice))
            fh.write("\n## 需替换清单（A + B + D）\n\n")
            fh.write("| 类别 | 文件名 | 中文名 | 来源 | 判定依据 |\n|---|---|---|---|---|\n")
            for r in rows:
                if r["类别"] in ("A", "B", "D"):
                    fh.write("| %s | `%s` | %s | %s | %s |\n" % (
                        r["类别"], r["文件名"], r["中文名"], r["来源"], r["判定依据"]))
            fh.write("\n## 可保留清单（C）\n\n")
            fh.write("| 文件名 | 中文名 | 判定依据 |\n|---|---|---|\n")
            for r in rows:
                if r["类别"] == "C":
                    fh.write("| `%s` | %s | %s |\n" % (
                        r["文件名"], r["中文名"], r["判定依据"]))
            others = [r for r in rows if r["类别"] in ("C?", "?")]
            if others:
                fh.write("\n## 待人工确认\n\n")
                fh.write("| 类别 | 文件名 | 中文名 | 判定依据 |\n|---|---|---|---|\n")
                for r in others:
                    fh.write("| %s | `%s` | %s | %s |\n" % (
                        r["类别"], r["文件名"], r["中文名"], r["判定依据"]))
        print("Markdown 已写入 %s" % os.path.relpath(md_path, ROOT))

    return 0


if __name__ == "__main__":
    sys.exit(main())
