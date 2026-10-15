"""第三层组合测试编排：插假按钮夹具。

流程：
1. 把 fabric/run/mods 里的真实 mod 临时移到 staging（可选 --with-real-mods 保留）
2. 放入夹具 jar
3. 冷启动跑 verify_world_lifecycle.py（同一套 21 断言）
4. 检查客户端日志确含夹具插入标记（防止"夹具没生效却假绿"）
5. 恢复 mods 目录
"""
import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = ROOT.parent / "mcrpc-compat-fixture"
MODS = ROOT / "fabric" / "run" / "mods"
STAGING = ROOT / "fabric" / "run" / "mods_real_staging"
CLIENT_LOG = ROOT / "fabric" / "run" / "logs" / "latest.log"
FIXTURE_MARK = "[MCRPC-FIXTURE] 已向 BackupConfirmScreen 头部插入陷阱按钮"
TRAP_MARK = "FIXTURE TRAP"


def fixture_jar():
    jars = list((FIXTURE_ROOT / "build" / "libs").glob("*.jar"))
    jars = [j for j in jars if "-sources" not in j.name and "-dev" not in j.name]
    if not jars:
        raise SystemExit("夹具 jar 未构建：先在 mcrpc-compat-fixture 跑 ./gradlew build")
    return jars[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--with-real-mods", action="store_true",
                    help="保留第二层真实 mod（ModernUI/Sodium/Iris/ModMenu）一起测")
    args = ap.parse_args()

    jar = fixture_jar()
    print(f"[编排] 夹具 jar: {jar.name}")

    MODS.mkdir(parents=True, exist_ok=True)
    moved = []
    if not args.with_real_mods:
        STAGING.mkdir(exist_ok=True)
        for f in MODS.glob("*.jar"):
            shutil.move(str(f), str(STAGING / f.name))
            moved.append(f.name)
        print(f"[编排] 暂存真实 mod: {moved}")

    dst = MODS / jar.name
    if not dst.exists():
        shutil.copy(str(jar), str(dst))
    print(f"[编排] mods 目录: {sorted(p.name for p in MODS.glob('*.jar'))}")

    rc = 1
    try:
        t0 = time.time()
        proc = subprocess.run(
            [sys.executable, "scripts/verify_world_lifecycle.py", "--loader", "fabric"],
            cwd=str(ROOT), capture_output=True, text=True, timeout=1200,
            env={**os.environ, "PYTHONIOENCODING": "utf-8"})
        print(proc.stdout)
        if proc.stderr:
            print("[stderr]", proc.stderr[-2000:])
        rc = proc.returncode
        print(f"[编排] verify 退出码 {rc}，耗时 {time.time()-t0:.0f}s")

        # 证据链：夹具必须真的运行并插入过陷阱按钮。
        # System.out 在 dev 可能进游戏 latest.log，也可能进 gradle 重定向日志，两处都查。
        candidate_logs = [CLIENT_LOG, ROOT / "runClient_world_verify_fabric.log"]
        combined = ""
        for lp in candidate_logs:
            if lp.exists():
                combined += "\n" + lp.read_text(encoding="utf-8", errors="replace")
        n_insert = combined.count(FIXTURE_MARK)
        print(f"[证据] 日志中夹具插入标记出现 {n_insert} 次（降级两档各应≥1）")
        if TRAP_MARK in combined:
            print("[证据][严重] 日志出现 FIXTURE TRAP —— 陷阱按钮被误点！索引法未免疫")
            rc = 2
        if n_insert < 1:
            print("[证据][严重] 夹具从未插入按钮，本次结果无效（假绿风险）")
            rc = 3
    finally:
        # 恢复：移除夹具，移回真实 mod
        if dst.exists():
            dst.unlink()
        if not args.with_real_mods:
            for f in STAGING.glob("*.jar"):
                shutil.move(str(f), str(MODS / f.name))
            shutil.rmtree(STAGING, ignore_errors=True)
        print(f"[编排] mods 已恢复: {sorted(p.name for p in MODS.glob('*.jar'))}")

    sys.exit(rc)


if __name__ == "__main__":
    main()
