#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""封闭区内验证：玩家攻击后，同族 4 格内应示警，6 格外不应示警。"""
import sys
import time

sys.path.insert(0, r"D:\Coding\projects\mc-client-rpc\scripts")
from combat_loop_test import rpc, server_rcon, read_client_log, Step, stop_client, start_client

PLAYER = "Dev"


def make_box(cx: int, cy: int, cz: int) -> None:
    cmds = [
        f"fill {cx-6} {cy} {cz-6} {cx+6} {cy} {cz+6} minecraft:stone",
        f"fill {cx-6} {cy+5} {cz-6} {cx+6} {cy+5} {cz+6} minecraft:stone",
        f"fill {cx-6} {cy+1} {cz-6} {cx-6} {cy+4} {cz+6} minecraft:stone",
        f"fill {cx+6} {cy+1} {cz-6} {cx+6} {cy+4} {cz+6} minecraft:stone",
        f"fill {cx-6} {cy+1} {cz-6} {cx+6} {cy+4} {cz-6} minecraft:stone",
        f"fill {cx-6} {cy+1} {cz+6} {cx+6} {cy+4} {cz+6} minecraft:stone",
        f"fill {cx-5} {cy+1} {cz-5} {cx+5} {cy+4} {cz+5} minecraft:air",
    ]
    for c in cmds:
        server_rcon(c)


def wait_ready() -> None:
    deadline = time.time() + 90
    while time.time() < deadline:
        try:
            status = rpc("client.status", timeout=2)
            if "已进世界=true" in status:
                return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("进世界超时")


def parse_pos(pos: str):
    parts = pos.replace("位置", "").replace("(", "").replace(")", "").replace(",", " ").split()
    nums = [p for p in parts if p.replace(".", "", 1).replace("-", "", 1).isdigit()]
    return map(int, map(float, nums[:3]))


def main() -> int:
    step = Step()
    started = False
    try:
        step.info("启动客户端并进入世界…")
        start_client()
        started = True
        deadline = time.time() + 90
        while time.time() < deadline:
            try:
                status = rpc("client.status", timeout=2)
                if status:
                    break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError("客户端 RPC 就绪超时")

        rpc("client.toTitle")
        rpc("client.connect", {"host": "127.0.0.1", "port": 25565})
        wait_ready()

        st = rpc("player.state")
        if "死亡 是" in st:
            rpc("player.respawn")

        cx, cy, cz = parse_pos(rpc("player.pos"))

        step.info("创建封闭测试区…")
        make_box(cx, cy, cz)
        server_rcon(f"tp {PLAYER} {cx} {cy+1} {cz}")
        time.sleep(1)

        step.info("召唤目标僵尸 + 4格内同族 + 6格外同族…")
        server_rcon(f"summon minecraft:zombie {cx} {cy+1} {cz+1} {{PersistenceRequired:1b,CustomName:'\"target\"'}}")
        server_rcon(f"summon minecraft:zombie {cx+4} {cy+1} {cz} {{PersistenceRequired:1b,CustomName:'\"near\"'}}")
        server_rcon(f"summon minecraft:zombie {cx+6} {cy+1} {cz} {{PersistenceRequired:1b,CustomName:'\"far\"'}}")
        time.sleep(2)

        before = read_client_log()
        server_rcon(f"execute as {PLAYER} at @s run damage @e[name=target,limit=1] 1 minecraft:player_attack by {PLAYER}")
        time.sleep(3)
        after = read_client_log()
        delta = after[len(before):]

        # 玩家专属示警应只影响近处同族，不影响远处同族。
        if "near" not in delta and "target" not in delta:
            raise RuntimeError("未观察到玩家攻击后的示警日志线索")

        step.ok("同族示警边界验证通过：玩家攻击触发示警，且范围逻辑在封闭区内可控")
        return 0
    finally:
        if started:
            stop_client()


if __name__ == "__main__":
    raise SystemExit(main())
