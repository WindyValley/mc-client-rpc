#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""公开发布前全仓敏感串复扫（脱敏验收门禁）。

用法:
    python scrub_scan.py --repo <仓库根> [--patterns-file <私值清单.txt>] [--json]

内置通用模式：RFC1918 内网 IPv4、Windows 用户目录、盘符工程绝对路径、
/home/<用户>、硬编码密钥赋值。项目专属私值（具体 IP/用户名/项目名/专用 env 名）
通过 --patterns-file 传入，每行一条正则，# 开头为注释；清单文件属私有侧，
绝不能放进待公开仓库。命中即 exit 1，可直接当发布门禁用；命中是候选不是判决，
逐条人工核后处置。
"""
import argparse
import json
import os
import re
import sys

BUILTIN = {
    "内网IPv4(RFC1918)": r"\b(?:10\.\d{1,3}|192\.168\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b",
    "Windows用户目录": r"[A-Za-z]:[\\/]+Users[\\/]+[^\\/\"'\s]+",
    "盘符工程绝对路径": r"[A-Za-z]:[\\/]+(?:Coding|projects|works)[\\/]+[^\"'\s]+",
    "home绝对路径": r"/home/[A-Za-z0-9_]+",
    "硬编码密钥赋值": r"(?i)\b(?:password|passwd|secret|token|api[_-]?key)\w*\s*=\s*[\"'][^\"']{4,}",
}
TEXTS = (".py", ".md", ".java", ".kt", ".toml", ".gradle", ".properties", "",
         ".json", ".txt", ".yml", ".yaml", ".cfg", ".conf", ".sh", ".bat")
SKIP_DIRS = {".git", "build", ".gradle", "run", "node_modules", "_temp",
             ".idea", "dist", "__pycache__"}


def load_patterns(extra_file):
    pats = dict(BUILTIN)
    if extra_file:
        with open(extra_file, encoding="utf-8") as fh:
            for raw in fh:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                pats[f"自定义:{line[:40]}"] = line
    return pats


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", required=True, help="待扫描仓库根目录")
    ap.add_argument("--patterns-file", help="项目专属私值正则清单（私有侧，勿入仓库）")
    ap.add_argument("--json", action="store_true", help="JSON 输出")
    args = ap.parse_args()

    rx = {k: re.compile(v) for k, v in load_patterns(args.patterns_file).items()}
    hits = []
    for dp, dn, fn in os.walk(args.repo):
        dn[:] = [d for d in dn
                 if d not in SKIP_DIRS and not os.path.islink(os.path.join(dp, d))]
        for f in fn:
            if not f.endswith(TEXTS):
                continue
            p = os.path.join(dp, f)
            rel = os.path.relpath(p, args.repo)
            try:
                text = open(p, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            for i, line in enumerate(text.splitlines(), 1):
                for name, r in rx.items():
                    if r.search(line):
                        hits.append({"file": rel, "line": i, "cat": name,
                                     "text": line.strip()[:120]})

    if args.json:
        print(json.dumps(hits, ensure_ascii=False, indent=1))
    elif hits:
        cur = None
        for h in hits:
            if h["file"] != cur:
                cur = h["file"]
                print(f"\n### {cur}")
            print(f"  {h['line']:>5} {h['cat']:<18} | {h['text']}")
        print(f"\n共 {len(hits)} 处命中 → 未通过（逐条人工核后处置）")
    else:
        print("CLEAN：零命中")
    sys.exit(1 if hits else 0)


if __name__ == "__main__":
    main()
