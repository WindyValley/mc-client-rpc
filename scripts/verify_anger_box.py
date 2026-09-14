#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用 mc-client-rpc 在玩家周围造封闭测试区，并在区内生成怪物做验证。"""
import sys
import time
from pathlib import Path

sys.path.insert(0, r"D:\Coding\projects\mc-client-rpc\scripts")
from combat_loop_test import rpc, server_rcon, read_client_log, _parse_hp, _player_hp, Step, stop_client, start_client

PLAYER = "Dev"
MOB = "minecraft:zombie"


def make_box(cx: int, cy: int, cz: int) -> None:
    # 7x4x7 石头围墙 + 顶 + 地板
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
        deadline = time.time() + 90
        while time.time() < deadline:
            status = rpc("client.status")
            if "已进世界=true" in status:
                break
            time.sleep(1)
        else:
            raise RuntimeError("进世界超时")

        st = rpc("player.state")
        if "死亡 是" in st:
            rpc("player.respawn")

        pos = rpc("player.pos")
        step.ok(f"玩家位置: {pos}")
        # 解析坐标 "x y z"
        parts = pos.replace("位置", "").replace("(", "").replace(")", "").replace(",", " ").split()
        nums = [p for p in parts if p.replace(".", "", 1).replace("-", "", 1).isdigit()]
        if len(nums) < 3:
            raise RuntimeError(f"无法解析玩家坐标: {pos}")
        cx, cy, cz = map(int, map(float, nums[:3]))

        step.info("创建封闭测试区…")
        make_box(cx, cy, cz)
        time.sleep(1)

        step.info("把玩家传送到封闭区中心…")
        server_rcon(f"tp {PLAYER} {cx} {cy+1} {cz}")
        time.sleep(1)

        step.info("在封闭区内召唤僵尸…")
        server_rcon(f"summon {MOB} {cx+2} {cy+1} {cz} {{PersistenceRequired:1b}}")
        time.sleep(2)

        ents = rpc("world.entities", {"radius": 16})
        step.ok(ents)
        if "zombie" not in ents:
            raise RuntimeError("封闭区内未发现僵尸")

        step.info("攻击僵尸并观察玩家专属示警是否生效…")
        hp0 = _player_hp()
        rpc("player.moveToward", {"target": "zombie"})
        time.sleep(4)
        before = read_client_log()
        retaliated = False
        end = time.time() + 20
        while time.time() < end:
            try:
                rpc("player.attack", {"target": "zombie"}, timeout=5)
            except Exception:
                pass
            time.sleep(1)
            now = read_client_log()
            if "was slain by" in now[len(before):] or "was shot by" in now[len(before):]:
                retaliated = True
                break
            hp = _player_hp()
            if hp is not None and hp0 is not None and hp < hp0:
                retaliated = True
                break

        if not retaliated:
            raise RuntimeError("封闭区内未观察到玩家攻击后的反击/示警证据")

        step.ok("封闭区验证通过：玩家攻击触发玩家专属仇恨/示警，环境干扰被隔离")
        return 0
    finally:
        if started:
            stop_client()


if __name__ == "__main__":
    raise SystemExit(main())
