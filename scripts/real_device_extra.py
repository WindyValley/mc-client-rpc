"""mc-client-rpc 真机补测：覆盖第一轮未触达的路径。

前置：客户端已连服务器；环境变量 RCON_PASSWORD 提供 RCON 密码。
用法：
  python scripts/real_device_extra.py <stage>
    attack   近身召唤僵尸 → 真实命中(hit=true) → 清理
    command  op Dev → 客户端发 give → RCON clear 验证物品确实入包
    chat     客户端发带标记聊天 → ssh 查服务器日志确认
    look     设视角 → RCON 读实体 Rotation 验证
    jump     按跳跃键 → 采样 y 确认真实离地
    toward   6 格外召唤僵尸 → moveToward → 距离确实缩短
    net      1MB 行上限 / 16 连接上限
    respawn  最后：kill Dev → 死屏 → player.respawn → 确认复活
所有断言失败立即非零退出；每个阶段都做现场清理。
"""
import json
import os
import socket
import subprocess
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rcon import Rcon

HOST, PORT = "127.0.0.1", 25586
# 测试服 RCON / ssh 均由环境变量提供，默认本机；chat 阶段需要 ssh 读日志
RCON_HOST = os.environ.get("MCRPC_SERVER_HOST", "127.0.0.1")
RCON_PORT = int(os.environ.get("MCRPC_SERVER_RCON_PORT", "25575"))
SSH_TARGET = os.environ.get("MCRPC_SSH_TARGET", "")
SERVER_LOG = os.environ.get("MCRPC_SERVER_LOG", "~/minecraft/logs/latest.log")

_next_id = 0


def rpc(method, params=None, timeout=20):
    global _next_id
    _next_id += 1
    req = {"jsonrpc": "2.0", "method": method, "id": _next_id}
    if params is not None:
        req["params"] = params
    s = socket.create_connection((HOST, PORT), timeout=timeout)
    s.settimeout(timeout)
    f = s.makefile("rwb")
    f.write((json.dumps(req) + "\n").encode())
    f.flush()
    line = f.readline()
    s.close()
    if not line:
        return None, None  # 连接被服务器关闭
    resp = json.loads(line.decode())
    if "error" in resp:
        return True, resp["error"]
    return False, resp["result"]


def check(label, ok, detail=""):
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {label}" + (f"  -> {detail}" if detail else ""))
    if not ok:
        sys.exit(1)


def rcon():
    pw = os.environ.get("RCON_PASSWORD")
    if not pw:
        sys.exit("缺少环境变量 RCON_PASSWORD")
    return Rcon(RCON_HOST, RCON_PORT, pw)


def dim():
    """Dev 当前维度（召唤/清理必须落在同一维度，RCON 默认主世界）。"""
    return get_pos()["dimension"]


def summon(rc, etype, x, y, z):
    return rc.command(
        f"execute in {dim()} run summon {etype} {x} {y} {z}")


def kill_all(rc, etype):
    return rc.command(
        f"execute in {dim()} run kill @e[type={etype}]")


def get_pos():
    err, pos = rpc("player.pos")
    assert not err, pos
    return pos


def wait_entity(etype, radius, want=None, timeout=5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        err, ents = rpc("world.entities", {"radius": radius})
        assert not err, ents
        hits = [e for e in ents["entities"] if e["type"] == etype]
        if (want is None and hits) or (want and any(want(e) for e in hits)):
            return hits
        time.sleep(0.4)
    return []


def stage_attack(rc):
    pos = get_pos()
    # 召唤在 1.5 格外而非同一坐标：实体与玩家完全重叠(distance 0)时
    # 攻击的目标射线选不中，实测不造成伤害。
    summon(rc, "minecraft:zombie", pos["x"] + 1.5, pos["y"], pos["z"])
    try:
        hits = wait_entity("minecraft:zombie", 5, want=lambda e: e["distance"] <= 3.2)
        check("僵尸已近身", bool(hits), str(hits))
        err, res = rpc("player.attack", {"target": "minecraft:zombie"})
        check("attack 无错误", not err, str(res))
        check("真实命中 hit=true", not err and res.get("hit") is True, str(res))
        check("血量下降", res.get("hpAfter", 0) < res.get("hpBefore", 0),
              f"{res.get('hpBefore')} -> {res.get('hpAfter')}")
    finally:
        kill_all(rc, "minecraft:zombie")
        rpc("player.stop")


def stage_command(rc):
    rc.command("op Dev")
    time.sleep(3)  # 等服务端把权限等级同步给该玩家，1s 时 give 仍被拒
    err, res = rpc("player.command", {"cmd": "give Dev minecraft:stone 3"})
    check("客户端发 command 无错误", not err, str(res))
    time.sleep(1)
    out = rc.command("clear Dev minecraft:stone")
    check("物品确实经服务端入包",
          "Removed" in out or "Cleared" in out, out)


def stage_chat(rc):
    marker = "MCRPCMARK_" + uuid.uuid4().hex[:8]
    err, res = rpc("player.chat", {"msg": marker})
    check("客户端发 chat 无错误", not err, str(res))
    if not SSH_TARGET:
        print("  [skip] 未配置 MCRPC_SSH_TARGET，跳过服务器日志落地校验")
        return
    found = False
    for _ in range(6):
        p = subprocess.run(["ssh", SSH_TARGET, f"grep -c {marker} {SERVER_LOG}"],
                           capture_output=True, text=True, timeout=15)
        if p.stdout.strip().isdigit() and int(p.stdout.strip()) >= 1:
            found = True
            break
        time.sleep(1)
    check("聊天真实到达服务器（日志可查）", found, marker)


def stage_look(rc):
    err, res = rpc("player.look", {"yaw": 42.0, "pitch": -13.0})
    check("look 无错误", not err, str(res))
    time.sleep(0.5)
    out = rc.command("data get entity Dev Rotation")
    # Dev has the following entity data: [42.0f, -13.0f]
    import re
    m = re.search(r"\[(-?[\d.]+)f, (-?[\d.]+)f\]", out)
    check("服务端读到视角", bool(m), out)
    if m:
        yaw, pitch = float(m.group(1)), float(m.group(2))
        check("视角数值正确", abs(yaw - 42) < 1 and abs(pitch + 13) < 1,
              f"yaw={yaw} pitch={pitch}")


def stage_jump(rc):
    y0 = get_pos()["y"]
    err, res = rpc("player.jump", {"down": True})
    check("jump 按下无错误", not err, str(res))
    rose = False
    max_y = y0
    try:
        for _ in range(9):
            time.sleep(0.15)
            y = get_pos()["y"]
            max_y = max(max_y, y)
            if y > y0 + 0.3:
                rose = True
                break
    finally:
        rpc("player.jump", {"down": False})
    check("真实起跳（y 抬升 >0.3）", rose, f"{y0} -> {max_y}")


def stage_toward(rc):
    pos = get_pos()
    # 在 x+6 处召唤静止僵尸(NoAI：不游走/不消失，只验证玩家会不会真实走近)
    tx = pos["x"] + 6
    rc.command(f"execute in {dim()} run summon minecraft:zombie "
               f"{tx} {pos['y']} {pos['z']} {{NoAI:1b}}")
    try:
        hits = wait_entity("minecraft:zombie", 12)
        check("6 格外僵尸就位", bool(hits), str(hits))
        d0 = min(e["distance"] for e in hits)
        dev0 = get_pos()
        err, res = rpc("player.moveToward", {"target": "minecraft:zombie"})
        check("moveToward 无错误", not err, str(res))
        decreased = False
        detail = ""
        for _ in range(24):
            time.sleep(0.5)
            hits2 = wait_entity("minecraft:zombie", 16)
            devn = get_pos()
            if hits2:
                d = min(e["distance"] for e in hits2)
                detail = f"dist {d0}->{d}; dev.x {dev0['x']}->{devn['x']}"
                if d < d0 - 1.5:
                    decreased = True
                    break
        check("距离真实缩短 >=1.5 格", decreased, detail)
    finally:
        rpc("player.stop")
        kill_all(rc, "minecraft:zombie")


def stage_net(rc):
    # 1) 超 1MB 行：服务器读行上限触发，应直接断开、无响应
    _next_id_local = None
    req = {"jsonrpc": "2.0", "method": "client.status", "id": 999,
           "params": {"pad": "A" * 1_100_000}}
    s = socket.create_connection((HOST, PORT), timeout=10)
    s.settimeout(10)
    s.sendall((json.dumps(req) + "\n").encode())
    try:
        data = s.recv(64)
    except socket.timeout:
        data = b""
    s.close()
    check("超 1MB 请求被断开（无响应）", data == b"", repr(data[:64]))

    # 2) 16 连接上限：先占 16 个空闲连接
    holders = []
    for _ in range(16):
        c = socket.create_connection((HOST, PORT), timeout=5)
        c.settimeout(5)
        holders.append(c)
    time.sleep(1.5)
    extra = socket.create_connection((HOST, PORT), timeout=5)
    extra.settimeout(5)
    try:
        msg = extra.recv(256)
    except socket.timeout:
        msg = b""
    for c in holders:
        c.close()
    extra.close()
    check("第 17 个连接被拒绝/提示", b"Too many" in msg or msg == b"", repr(msg))
    # 资源释放后服务仍可用
    err, st = rpc("client.status")
    check("连接清理后服务恢复正常", not err, str(st))


def stage_respawn(rc):
    rc.command("kill Dev")
    dead = False
    for _ in range(10):
        time.sleep(0.5)
        err, st = rpc("player.state")
        if not err and st.get("dead"):
            dead = True
            break
    check("玩家已进入死亡状态", dead, str(st))
    err, res = rpc("player.respawn")
    check("respawn 无错误", not err, str(res))
    revived = False
    for _ in range(30):
        time.sleep(1)
        err, st = rpc("player.state")
        if not err and not st.get("dead"):
            revived = True
            break
    check("已复活 dead=false", revived, str(st))
    pos = get_pos()
    print(f"  复活后位置/维度：({pos['x']}, {pos['y']}, {pos['z']}) {pos['dimension']}")


STAGES = {
    "attack": stage_attack,
    "command": stage_command,
    "chat": stage_chat,
    "look": stage_look,
    "jump": stage_jump,
    "toward": stage_toward,
    "net": stage_net,
    "respawn": stage_respawn,
}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in STAGES:
        print("用法: python real_device_extra.py {%s}" % "|".join(STAGES))
        sys.exit(2)
    with rcon() as rc:
        STAGES[sys.argv[1]](rc)
    print(f"\n阶段 {sys.argv[1]} 完成。")
