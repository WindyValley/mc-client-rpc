#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""清理 mc-client-rpc 相关残留 java 进程（保留 Gradle daemon）。"""
import os
import signal
import subprocess

TASKLIST = ["tasklist", "/FI", "IMAGENAME eq java.exe", "/FO", "CSV", "/NH"]


def main() -> int:
    out = subprocess.run(TASKLIST, capture_output=True, text=True, encoding="gbk", errors="replace")
    lines = [ln for ln in out.stdout.splitlines() if "java.exe" in ln.lower()]
    killed = []
    for ln in lines:
        parts = [p.strip('"') for p in ln.split('","')]
        if len(parts) < 2:
            continue
        pid = parts[1]
        try:
            os.kill(int(pid), signal.SIGTERM)
            killed.append(pid)
        except Exception:
            pass
    print(f"killed={killed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
