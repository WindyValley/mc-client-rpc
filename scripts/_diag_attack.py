"""诊断：攻击包到底有没有造成服务端伤害。逐步打印服务端权威状态。"""
import json
import os
import socket
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import Rcon


def rpc(method, params=None, timeout=15):
    req = {"jsonrpc": "2.0", "method": method,
           "id": int(time.time() * 1000) % 100000}
    if params:
        req["params"] = params
    s = socket.create_connection(("127.0.0.1", 25586), timeout=timeout)
    s.settimeout(timeout)
    f = s.makefile("rwb")
    f.write((json.dumps(req) + "\n").encode())
    f.flush()
    resp = json.loads(f.readline().decode())
    s.close()
    return resp.get("result", resp.get("error"))


with Rcon("127.0.0.1", 25575, os.environ["RCON_PASSWORD"]) as r:
    pos = rpc("player.pos")
    dim = pos["dimension"]
    print("pos:", pos)
    print("gamemode:", r.command("gamemode Dev"))
    print("difficulty:", r.command("difficulty"))
    r.command(f"execute in {dim} run summon minecraft:zombie "
              f"{pos['x']+1.5} {pos['y']} {pos['z']}")
    time.sleep(1.2)
    sel = f"@e[type=minecraft:zombie,limit=1,sort=nearest]"
    print("hp before (server):",
          r.command(f"execute in {dim} run data get entity {sel} Health"))
    print("attack rpc:", rpc("player.attack", {"target": "minecraft:zombie"}))
    time.sleep(0.6)
    print("hp after (server):",
          r.command(f"execute in {dim} run data get entity {sel} Health"))
    r.command(f"execute in {dim} run kill {sel}")
