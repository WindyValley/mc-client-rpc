#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""world.list / world.create / world.enter 真机验证（mc-client-rpc 自身 runClient）。

一条命令跑完整生命周期：
  1. 起 :neoforge:runClient（工程自身 classpath，零 MOD 类），等 25586 + 标题屏稳定
  2. methods.list 自描述里应含三个新方法
  3. world.list 记录初始存档
  4. world.create：flat + creative + 文本种子 hello + 奖励箱 → 轮询 inWorld，
     出生 y=-60（1.21 默认超平坦从 y=-64 铺 4 层：基岩+2 泥土+草，地表 -60），
     disconnect
  5. world.list 出现新存档且字段正确（creative/cheats=true）
  6. world.enter 用 id 重进 → inWorld，disconnect
  7. world.create：normal + survival + 数字种子 → inWorld，出生 y 明显高于平坦，
     重名 world.create 自动落到 (1) 目录不覆盖旧档
  8. 错误用例：世界中 create（NOT_READY -32001）、坏 gameMode/difficulty/preset
     （INVALID_PARAMS -32602）、enter 不存在 id（TARGET_NOT_FOUND -32002）
  9. client.quit，删除测试存档目录（mcrpc-wl-*）

成功打印 ALL PASS 并退出 0；任一断言失败抛错退出非 0。
用法：python scripts/verify_world_lifecycle.py [--keep-client]
"""
import json
import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RPC_HOST, RPC_PORT = "127.0.0.1", 25586
SAVES = ROOT / "neoforge" / "run" / "saves"
if not SAVES.exists():
    SAVES = ROOT / "run" / "saves"
WORLD_PREFIX = "mcrpc-wl-"
BOOT_TIMEOUT, SETTLE_SECONDS, POLL = 240, 5, 1.0

_failures = []


def check(cond, label, detail=""):
    mark = "PASS" if cond else "FAIL"
    print(f"[{mark}] {label}" + (f" -- {detail}" if detail and not cond else ""))
    if not cond:
        _failures.append(label)


def rpc(method, params=None, timeout=130.0):
    req = {"jsonrpc": "2.0", "method": method, "id": 1}
    if params:
        req["params"] = params
    with socket.create_connection((RPC_HOST, RPC_PORT), timeout=timeout) as s:
        s.sendall((json.dumps(req) + "\n").encode("utf-8"))
        s.settimeout(timeout)
        buf = b""
        while b"\n" not in buf:
            chunk = s.recv(4096)
            if not chunk:
                break
            buf += chunk
    resp = json.loads(buf.decode("utf-8").strip())
    if "error" in resp:
        e = resp["error"]
        raise RpcError(e.get("code"), e.get("message", ""), e.get("data"))
    return resp.get("result")


class RpcError(Exception):
    def __init__(self, code, message, data=None):
        super().__init__(f"[{code}] {message}")
        self.code, self.data = code, data


def expect_error(code, method, params=None, timeout=130.0):
    try:
        rpc(method, params, timeout)
    except RpcError as e:
        check(e.code == code, f"{method} 错误码 {code}", str(e))
        return
    check(False, f"{method} 应报错 {code} 但成功了")


def port_open():
    s = socket.socket()
    s.settimeout(1)
    try:
        s.connect((RPC_HOST, RPC_PORT))
        return True
    except OSError:
        return False
    finally:
        s.close()


def launch():
    if port_open():
        print("[启动] 25586 已在监听，复用现有客户端")
        return
    log = open(ROOT / "runClient_world_verify.log", "ab")
    flags = 0x00000008 | 0x00000200
    subprocess.Popen(
        ["cmd", "/c", "gradlew.bat", ":neoforge:runClient"],
        cwd=str(ROOT), stdout=log, stderr=subprocess.STDOUT,
        creationflags=flags, close_fds=True)
    print("[启动] 已发起 :neoforge:runClient")
    t0 = time.time()
    while time.time() - t0 < BOOT_TIMEOUT:
        if port_open():
            try:
                rpc("client.status", timeout=3)
                break
            except Exception:
                pass
        time.sleep(POLL)
    else:
        raise RuntimeError("客户端 RPC 启动超时")
    print(f"[启动] RPC 就绪（{time.time()-t0:.0f}s）")


def settle_title():
    for _ in range(5):
        try:
            rpc("client.toTitle", timeout=5)
            break
        except Exception:
            time.sleep(2)
    stable, t0 = 0.0, time.time()
    while time.time() - t0 < 60:
        st = rpc("client.status", timeout=5)
        if st.get("screen") == "TitleScreen" and not st.get("inWorld"):
            stable += POLL
            if stable >= SETTLE_SECONDS:
                return
        else:
            stable = 0.0
        time.sleep(POLL)
    raise RuntimeError("标题屏未稳定")


def wait_in_world(timeout=120):
    t0 = time.time()
    last_screen = None
    while time.time() - t0 < timeout:
        st = rpc("client.status", timeout=10)
        if st.get("inWorld") and st.get("screen") is None:
            return st
        if st.get("screen") != last_screen:
            print(f"   …screen={st.get('screen')} inWorld={st.get('inWorld')}")
            last_screen = st.get("screen")
        time.sleep(POLL)
    raise RuntimeError(f"{timeout}s 内未进入世界")


def main():
    keep = "--keep-client" in sys.argv
    launch()
    settle_title()
    print("[阶段] 标题屏稳定")

    # 1. 方法注册
    methods = {m["name"] for m in rpc("methods.list", timeout=10)["methods"]}
    for m in ("world.list", "world.create", "world.enter"):
        check(m in methods, f"methods.list 含 {m}")

    # 2. 初始存档列表
    before = {w["id"] for w in rpc("world.list", timeout=40)["worlds"]}
    print(f"[阶段] 初始存档 {len(before)} 个")

    flat_name = WORLD_PREFIX + "flat"
    # 3. 创建 flat 创造世界（文本种子，走 hashCode 路径）
    r = rpc("world.create", {
        "name": flat_name, "seed": "hello", "gameMode": "creative",
        "difficulty": "normal", "preset": "flat", "bonusChest": True})
    print(f"[create-flat] {r}")
    wait_in_world()
    pos = rpc("player.pos", timeout=10)
    print(f"[create-flat] 进世界 pos={pos}")
    # 1.21 默认超平坦：minY=-64 上铺基岩1+泥土2+草1，地表 y=-60
    FLAT_Y = -60
    check(abs(pos["y"] - FLAT_Y) <= 2, "flat 出生 y=-60（1.21 超平坦地表）", f"实际 y={pos['y']}")
    check(pos["dimension"] == "minecraft:overworld", "主世界维度", pos["dimension"])

    # 世界中再创建 → NOT_READY
    expect_error(-32001, "world.create", {"name": WORLD_PREFIX + "x"})
    rpc("client.disconnect", timeout=15)
    settle_title()

    # 4. list 出现新档且字段正确
    worlds = {w["id"]: w for w in rpc("world.list", timeout=40)["worlds"]}
    new_ids = [i for i in worlds if i not in before]
    check(len(new_ids) >= 1 and any(flat_name in i for i in new_ids),
          "world.list 出现新建 flat 档", str(new_ids))
    fid = next(i for i in new_ids if flat_name in i)
    w = worlds[fid]
    check(w["playable"] and w["gameMode"] == "creative" and w["cheats"] is True
          and w["hardcore"] is False, "存档字段 creative/cheats=true", json.dumps(w, ensure_ascii=False))

    # 5. enter 重进
    r = rpc("world.enter", {"id": fid}, timeout=130)
    print(f"[enter] {r}")
    wait_in_world()
    pos2 = rpc("player.pos", timeout=10)
    check(abs(pos2["y"] - FLAT_Y) <= 2, "enter 重进后仍在 flat(y=-60)", f"y={pos2['y']}")
    rpc("client.disconnect", timeout=15)
    settle_title()

    # 6. enter 不存在 → TARGET_NOT_FOUND
    expect_error(-32002, "world.enter", {"id": WORLD_PREFIX + "nope"})

    # 7. 参数非法（在标题屏）
    expect_error(-32602, "world.create",
                 {"name": WORLD_PREFIX + "bad", "gameMode": "survivaal"})
    expect_error(-32602, "world.create",
                 {"name": WORLD_PREFIX + "bad", "difficulty": "nightmare"})
    expect_error(-32602, "world.create",
                 {"name": WORLD_PREFIX + "bad", "preset": "amplified"})
    expect_error(-32602, "world.create", {"name": "   "})

    # 8. normal 生存 + 数字种子，出生高度应明显高于平坦
    norm_name = WORLD_PREFIX + "normal"
    r = rpc("world.create", {
        "name": norm_name, "seed": 12345, "gameMode": "survival",
        "difficulty": "easy", "preset": "normal", "allowCheats": False})
    print(f"[create-normal] {r}")
    wait_in_world()
    pos3 = rpc("player.pos", timeout=10)
    print(f"[create-normal] pos={pos3}")
    check(pos3["y"] > 20, "normal 出生高度明显高于 flat(y≈4)", f"y={pos3['y']}")
    rpc("client.disconnect", timeout=15)
    settle_title()

    # 9. 重名自动 (1)，不覆盖旧档
    r = rpc("world.create", {"name": flat_name, "preset": "flat",
                             "gameMode": "creative"})
    print(f"[create-dup] {r}")
    wait_in_world()
    rpc("client.disconnect", timeout=15)
    settle_title()
    ids_after = {w["id"] for w in rpc("world.list", timeout=40)["worlds"]}
    check(fid in ids_after, "旧档未被覆盖", fid)
    check(any(i.startswith(flat_name + " (") for i in ids_after),
          "重名自动加序号目录", str(sorted(i for i in ids_after if WORLD_PREFIX in i)))

    # 10. 收尾
    if not keep:
        rpc("client.quit", timeout=5)
        print("[收尾] 已请求退出客户端，等待进程释放文件锁…")
        deadline = time.time() + 30
        while port_open() and time.time() < deadline:
            time.sleep(1)
        time.sleep(2)
        removed = []
        for p in SAVES.glob(WORLD_PREFIX + "*"):
            shutil.rmtree(p, ignore_errors=True)
            removed.append(p.name)
        print(f"[收尾] 清理测试存档 {removed}（saves={SAVES}）")

    print("-" * 60)
    if _failures:
        print(f"FAIL：{len(_failures)} 项 —— {_failures}")
        sys.exit(1)
    print("ALL PASS")


if __name__ == "__main__":
    main()
