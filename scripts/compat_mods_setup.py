"""组合测试：把 1.21.1+Fabric 的 UI/优化 mod 下载进 fabric/run/mods。

递归解析 required 依赖（project_id -> project -> 匹配版本）。
用法：
  python scripts/_compat_mods.py plan     # 只列出将下载什么
  python scripts/_compat_mods.py download # 实际下载
  python scripts/_compat_mods.py clear    # 清空 fabric/run/mods
"""
import json
import os
import sys
import urllib.request
import urllib.error

MODS_DIR = os.path.join(os.path.dirname(__file__), "..", "fabric", "run", "mods")
MC, LOADER = "1.21.1", "fabric"
UA = {"User-Agent": "mc-client-rpc-compat-test/1.0"}
PROXY = urllib.request.ProxyHandler(
    {"http": "http://127.0.0.1:10808", "https": "http://127.0.0.1:10808"})
opener_direct = urllib.request.build_opener()
opener_proxy = urllib.request.build_opener(PROXY)

# 组合测试要装的（fabric-api 工程 dev 运行时已在 classpath，不重复装）
ROOTS = ["modern-ui", "sodium", "iris", "modmenu"]
# dev runClient classpath 已提供（build.gradle modApi），放进 run/mods 会重复加载
SKIP = {"fabric-api"}

_project_cache = {}
_version_cache = {}


def http_json(url, binary=False):
    req = urllib.request.Request(url, headers=UA)
    last = None
    for opener in (opener_direct, opener_proxy):
        try:
            with opener.open(req, timeout=40) as r:
                data = r.read()
                return data if binary else json.loads(data.decode())
        except Exception as e:  # noqa
            last = e
    raise last


def project_info(pid_or_slug):
    if pid_or_slug not in _project_cache:
        _project_cache[pid_or_slug] = http_json(
            f"https://api.modrinth.com/v2/project/{pid_or_slug}")
    return _project_cache[pid_or_slug]


def versions(pid):
    if pid not in _version_cache:
        _version_cache[pid] = http_json(
            f"https://api.modrinth.com/v2/project/{pid}/version"
            f"?game_versions=%5B%22{MC}%22%5D&loaders=%5B%22{LOADER}%22%5D")
    return _version_cache[pid]


def resolve(slug, seen, plan):
    if slug in seen or slug in SKIP:
        return
    seen.add(slug)
    p = project_info(slug)
    vs = versions(p["id"])
    if not vs:
        print(f"  !! {slug} 无 {MC}+{LOADER} 版本，跳过")
        return
    v = vs[0]
    f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
    plan.append((slug, v["version_number"], f["filename"], f["url"]))
    for dep in v.get("dependencies", []):
        if dep.get("dependency_type") != "required":
            continue
        dpid = dep.get("project_id")
        if not dpid:
            continue
        dslug = project_info(dpid)["slug"]
        resolve(dslug, seen, plan)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "plan"
    if cmd == "clear":
        n = 0
        if os.path.isdir(MODS_DIR):
            for fn in os.listdir(MODS_DIR):
                os.remove(os.path.join(MODS_DIR, fn))
                n += 1
        print(f"已清空 {MODS_DIR}（{n} 个文件）")
        return

    seen, plan = set(), []
    for s in ROOTS:
        resolve(s, seen, plan)
    print("将安装：")
    for slug, ver, fn, _ in plan:
        print(f"  {slug:14} {ver:28} {fn}")

    if cmd != "download":
        return
    os.makedirs(MODS_DIR, exist_ok=True)
    for slug, ver, fn, url in plan:
        dst = os.path.join(MODS_DIR, fn)
        if os.path.exists(dst):
            print(f"  已存在 {fn}")
            continue
        data = http_json(url, binary=True)
        with open(dst, "wb") as fh:
            fh.write(data)
        print(f"  下载 {fn} ({len(data)//1024} KB)")
    print("完成 ->", os.path.abspath(MODS_DIR))


if __name__ == "__main__":
    main()
