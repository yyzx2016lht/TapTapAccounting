#!/usr/bin/env python3
"""把内置分类/资产图标下载进 APK（assets/icons/），并生成 URL -> assets 路径映射表。

背景：icon 字段在 res/raw/*.json 与部分 Kotlin 源码里是远程 URL
（http://res3.qianjiapp.com/...）。该 CDN 只支持 HTTP，而清单已关闭 cleartext，
导致所有图标加载被系统拦截。本脚本把图标固化进安装包，运行时不再联网。

用法（在仓库根目录）：
    python tools/sync-bundled-icons.py            # 增量：只下载缺失的
    python tools/sync-bundled-icons.py --force    # 全量重新下载
    python tools/sync-bundled-icons.py --check    # 只校验覆盖度，不下载

产物：
    app/src/main/assets/icons/<name>.<ext>     图标本体
    app/src/main/assets/icons/icon_map.json    {"<url>": "icons/<name>.<ext>"}
"""

import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_DIR = os.path.join(ROOT, "app", "src", "main", "res", "raw")
SRC_DIR = os.path.join(ROOT, "app", "src", "main", "java")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "icons")
MAP_PATH = os.path.join(OUT_DIR, "icon_map.json")

# URL 里不可能出现空白、引号、反引号、圆括号、反斜杠或非 ASCII 字符。
# 不排除它们，正则会把紧跟 URL 的中文注释一起吞进来——例如 CategoryIcons.kt 的 KDoc 里写着
# 「（`http://res3.qianjiapp.com/cateic_other.png`）。用户自建分类时……」，贪婪匹配会得到
# 「…cateic_other.png`）。用户自建分类时…」这种假 URL（下面的扩展名过滤会把它丢掉，
# 但单测里同样的正则会因缺少过滤而误报，两边必须用同一个字符类）。
URL_CHARS = r"[^\s\"'`()\\\x80-\uffff]"
URL_IN_JSON = re.compile(r"https?://" + URL_CHARS + "+")
URL_IN_SOURCE = re.compile(r"https?://res[0-9]*\.qianjiapp\.com/" + URL_CHARS + "+")
IMAGE_EXT = (".png", ".jpg", ".jpeg", ".webp", ".gif")

MAGIC = {
    b"\x89PNG\r\n\x1a\n": ".png",
    b"\xff\xd8\xff": ".jpg",
    b"GIF87a": ".gif",
    b"GIF89a": ".gif",
}

_print_lock = threading.Lock()


def log(msg: str) -> None:
    with _print_lock:
        print(msg, flush=True)


def collect_urls() -> list:
    """收集 res/raw 全部 JSON 与 Kotlin 源码中的图标 URL。"""
    urls = set()
    for name in sorted(os.listdir(RAW_DIR)):
        if not name.endswith(".json"):
            continue
        with open(os.path.join(RAW_DIR, name), encoding="utf-8") as fh:
            for raw in URL_IN_JSON.findall(fh.read()):
                if raw.lower().endswith(IMAGE_EXT):
                    urls.add(raw)
    for dirpath, _dirs, files in os.walk(SRC_DIR):
        for name in files:
            if not name.endswith((".kt", ".java")):
                continue
            with open(os.path.join(dirpath, name), encoding="utf-8") as fh:
                for raw in URL_IN_SOURCE.findall(fh.read()):
                    if raw.lower().endswith(IMAGE_EXT):
                        urls.add(raw)
    return sorted(urls)


def asset_name(url: str, taken: dict) -> str:
    """URL -> assets 内文件名。同名冲突时追加 URL 摘要。"""
    tail = url.split("://", 1)[-1]
    tail = tail.split("/", 1)[1] if "/" in tail else tail
    tail = tail.split("?", 1)[0]
    tail = re.sub(r"[^A-Za-z0-9._-]", "_", tail).lower().lstrip("_")
    if not tail:
        tail = "icon" + _digest(url) + ".png"

    if tail in taken and taken[tail] != url:
        stem, ext = os.path.splitext(tail)
        tail = f"{stem}_{_digest(url)[:8]}{ext}"

    while tail in taken and taken[tail] != url:
        tail = f"{_digest(tail + url)}{os.path.splitext(tail)[1] or '.png'}"
    return tail


def _digest(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def sniff_ext(payload: bytes, fallback: str) -> str:
    for magic, ext in MAGIC.items():
        if payload.startswith(magic):
            return ext
    if payload[:4] == b"RIFF" and payload[8:12] == b"WEBP":
        return ".webp"
    return fallback


def fetch(url: str, attempts: int = 4) -> bytes:
    last = None
    for i in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "tapaccounting-icon-sync"})
            with urllib.request.urlopen(req, timeout=25) as resp:
                payload = resp.read()
            if not payload:
                raise IOError("empty body")
            return payload
        except Exception as exc:  # noqa: BLE001 - 网络错误种类多，统一重试
            last = exc
            time.sleep(0.6 * (i + 1))
    raise IOError(f"{type(last).__name__}: {last}")


def download_one(url: str, name: str, force: bool) -> str:
    path = os.path.join(OUT_DIR, name)
    if not force and os.path.isfile(path) and os.path.getsize(path) > 0:
        return "cached"
    try:
        payload = fetch(url)
    except Exception as exc:  # noqa: BLE001
        return f"FAIL {url} ({exc})"
    ext = sniff_ext(payload, os.path.splitext(name)[1] or ".png")
    final = name if os.path.splitext(name)[1].lower() == ext else os.path.splitext(name)[0] + ext
    with open(os.path.join(OUT_DIR, final), "wb") as fh:
        fh.write(payload)
    return "ok" if final == name else f"ok(renamed {final})"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="重新下载已存在的图标")
    parser.add_argument("--check", action="store_true", help="只校验映射覆盖度")
    args = parser.parse_args()

    urls = collect_urls()
    if not urls:
        log("未发现任何图标 URL，请检查路径：%s" % RAW_DIR)
        return 1
    log(f"发现图标 URL {len(urls)} 个")

    taken = {}
    wanted = {}
    for url in urls:
        wanted[url] = asset_name(url, taken)
        taken[wanted[url]] = url

    if args.check:
        missing = [u for u in urls if not os.path.isfile(os.path.join(OUT_DIR, wanted[u]))]
        log(f"缺失图标文件 {len(missing)} 个")
        for url in missing[:20]:
            log("  " + url)
        return 1 if missing else 0

    os.makedirs(OUT_DIR, exist_ok=True)

    stats = {"ok": 0, "cached": 0, "fail": 0}
    failures = []
    done = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(download_one, url, wanted[url], args.force): url for url in urls}
        for future in concurrent.futures.as_completed(futures):
            url = futures[future]
            result = future.result()
            done += 1
            if result == "cached":
                stats["cached"] += 1
            elif result.startswith("ok"):
                stats["ok"] += 1
            else:
                stats["fail"] += 1
                failures.append(result)
            if done % 100 == 0 or done == len(urls):
                log(f"  进度 {done}/{len(urls)}  下载 {stats['ok']}  已存在 {stats['cached']}  失败 {stats['fail']}")

    mapping = {}
    for url in urls:
        path = os.path.join(OUT_DIR, wanted[url])
        if os.path.isfile(path) and os.path.getsize(path) > 0:
            mapping[url] = "icons/" + os.path.basename(path)
    with open(MAP_PATH, "w", encoding="utf-8") as fh:
        json.dump(dict(sorted(mapping.items())), fh, ensure_ascii=False, indent=1)
        fh.write("\n")

    total = sum(os.path.getsize(os.path.join(OUT_DIR, n)) for n in os.listdir(OUT_DIR))
    log("")
    log(f"映射表写入 {os.path.relpath(MAP_PATH, ROOT)}：{len(mapping)}/{len(urls)} 条")
    log(f"assets/icons 总体积 {total / 1024 / 1024:.2f} MB")
    if failures:
        log("")
        log("以下 URL 下载失败（脚本已重试 4 次）：")
        for line in failures:
            log("  " + line)
        return 1
    log("全部图标已就绪。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
