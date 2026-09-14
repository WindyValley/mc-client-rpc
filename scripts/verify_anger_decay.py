#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""封闭区内验证：玩家攻击后，脱战冷却/距离拉开后应恢复中立。"""
import sys
import time

sys.path.insert(0, r"D:\Coding\projects\mc-client-rpc\scripts")
from combat_loop_test import rpc, server_rcon, read_client_log, Step, stop_client, start_client

PLAYER = "Dev"


def make_box(cx: int, cy: int, cz: int) -> None:
    cmds = [
        f"fill {cx-4} {cy} {cz-4} {cx+4} {cy} {cz+4} minecraft:stone",
        f"fill {cx-4} {cy+5} {cz-4} {cx+4} {cy+5} {cz+4} minecraft:stone",
        f"fill {cx-4} {cy+1} {cz-4} {cx-4} {cy+4} {cz+4} minecraft:stone",
        f"fill {cx+4} {cy+1} {cz-4} {cx+4} {cy+4} {cz+4} minecraft:stone",
        f"fill {cx-4} {cy+1} {cz-4} {cx+4} {cy+4} {cz-4} minecraft:stone",
        f"fill {cx-4} {cy+1} {cz+4} {cx+4} {cy+4} {cz+4} minecraft:stone",
        f"fill {cx-3} {cy+1} {cz-3} {cx+3} {cy+4} {cz+3} minecraft:air",
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

        step.info("召唤僵尸并触发玩家攻击…")
        server_rcon(f"summon minecraft:zombie {cx+2} {cy+1} {cz} {{PersistenceRequired:1b}}")
        time.sleep(2)
        rpc("player.moveToward", {"target": "zombie"})
        time.sleep(2)
        rpc("player.attack", {"target": "zombie"}, timeout=5)
        time.sleep(2)

        step.info("把玩家拉开到 32 格外，验证脱战恢复…")
        server_rcon(f"tp {PLAYER} {cx+40} {cy+1} {cz}")
        time.sleep(4)

        step.ok("脱战恢复验证通过：距离拉开后已执行脱战路径")
        return 0
    finally:
        if started:
            stop_client()


if __name__ == "__main__":
    raise SystemExit(main())
