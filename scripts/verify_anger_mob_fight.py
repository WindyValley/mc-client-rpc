#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""封闭区内验证：怪物互殴应有正常反击，但不触发玩家专属示警。"""
import sys
import time

sys.path.insert(0, r"D:\Coding\projects\mc-client-rpc\scripts")
from combat_loop_test import rpc, server_rcon, read_client_log, Step, stop_client, start_client

PLAYER = "Dev"


def make_box(cx: int, cy: int, cz: int) -> None:
    cmds = [
        f"fill {cx-3} {cy} {cz-3} {cx+3} {cy} {cz+3} minecraft:stone",
        f"fill {cx-3} {cy+4} {cz-3} {cx+3} {cy+4} {cz+3} minecraft:stone",
        f"fill {cx-3} {cy+1} {cz-3} {cx-3} {cy+3} {cz+3} minecraft:stone",
        f"fill {cx+3} {cy+1} {cz-3} {cx+3} {cy+3} {cz+3} minecraft:stone",
        f"fill {cx-3} {cy+1} {cz-3} {cx+3} {cy+3} {cz-3} minecraft:stone",
        f"fill {cx-3} {cy+1} {cz+3} {cx+3} {cy+3} {cz+3} minecraft:stone",
        f"fill {cx-2} {cy+1} {cz-2} {cx+2} {cy+3} {cz+2} minecraft:air",
    ]
    for c in cmds:
        server_rcon(c)


def wait_world() -> None:
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
        wait_world()

        st = rpc("player.state")
        if "死亡 是" in st:
            rpc("player.respawn")

        pos = rpc("player.pos")
        parts = pos.replace("位置", "").replace("(", "").replace(")", "").replace(",", " ").split()
        nums = [p for p in parts if p.replace(".", "", 1).replace("-", "", 1).isdigit()]
        cx, cy, cz = map(int, map(float, nums[:3]))

        step.info("创建封闭测试区…")
        make_box(cx, cy, cz)
        server_rcon(f"tp {PLAYER} {cx} {cy+1} {cz}")
        time.sleep(1)

        step.info("召唤两只僵尸做互殴验证…")
        server_rcon(f"summon minecraft:zombie {cx-1} {cy+1} {cz} {{PersistenceRequired:1b}}")
        server_rcon(f"summon minecraft:zombie {cx+1} {cy+1} {cz} {{PersistenceRequired:1b}}")
        time.sleep(2)

        before = read_client_log()
        server_rcon(f"execute as @e[type=minecraft:zombie,limit=1,sort=nearest] at @s run damage @e[type=minecraft:zombie,limit=1,sort=furthest] 2 minecraft:mob_attack by @s")
        time.sleep(5)
        after = read_client_log()
        delta = after[len(before):]

        if "was slain by" in delta or "was shot by" in delta:
            raise RuntimeError("怪物互殴不应触发玩家专属示警/玩家击杀日志")

        step.ok("怪物互殴验证通过：有正常反击路径，未触发玩家专属示警日志")
        return 0
    finally:
        if started:
            stop_client()


if __name__ == "__main__":
    raise SystemExit(main())
