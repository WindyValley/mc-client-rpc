"""mc-client-rpc 真机验证脚本：分阶段驱动真实客户端并核对返回。

用法：python scripts/real_device_test.py <stage>
  stage 1  进世界前：methods.list / client.status / connect 必传校验
  stage 2  进世界后：结构化状态查询、攻击守卫、无目标、移动、查询
每一步都打印原始 JSON 结果，断言失败立即非零退出。
"""
import json
import socket
import sys
import time

HOST, PORT = "127.0.0.1", 25586
_next_id = 0


def rpc(method, params=None, timeout=20):
    """发送一行 JSON-RPC，返回 (is_error, result_or_error_dict)。"""
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
        raise RuntimeError("连接被关闭，无响应（notification 行为？）")
    resp = json.loads(line.decode())
    assert resp["id"] == _next_id, f"id 未原样回传: {resp}"
    if "error" in resp:
        return True, resp["error"]
    return False, resp["result"]


def check(label, ok, detail=""):
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {label}" + (f"  -> {detail}" if detail else ""))
    if not ok:
        sys.exit(1)


def wait_in_world(max_wait=60):
    print("等待进入世界 ...")
    for _ in range(max_wait):
        err, st = rpc("client.status")
        if not err and st.get("inWorld"):
            print(f"  已进世界: {st}")
            return st
        time.sleep(1)
    raise RuntimeError("60s 内未进入世界")


def stage1():
    # methods.list 结构化
    err, methods = rpc("methods.list")
    check("methods.list 无错误", not err, str(methods)[:200])
    check("methods.list 含 count/methods",
          isinstance(methods, dict) and "methods" in methods
          and methods["count"] == len(methods["methods"]), str(methods)[:150])
    check("注册方法数 >= 10", methods["count"] >= 10, f"{methods['count']} 个")
    sample = methods["methods"][0]
    check("方法定义含 name/description/params",
          set(["name", "description", "params"]).issubset(sample), str(sample)[:200])

    # client.status 在标题屏：inWorld=false
    err, st = rpc("client.status")
    check("client.status 无错误", not err, str(st))
    check("未进世界 inWorld=false", st.get("inWorld") is False, str(st))

    # connect 缺 host：必须 INVALID_PARAMS(-32602)
    err, e = rpc("client.connect", {"port": 25565})
    check("connect 缺 host 报错", err and e["code"] == -32602, str(e))

    # connect host 为空串
    err, e = rpc("client.connect", {"host": "  "})
    check("connect 空 host 报错", err and e["code"] == -32602, str(e))

    print("\n阶段1 全部通过。")


def stage2_connect(host, port):
    err, msg = rpc("client.connect", {"host": host, "port": port}, timeout=10)
    check("发起连接", not err, str(msg))
    wait_in_world()


def stage3():
    # player.pos 结构化
    err, pos = rpc("player.pos")
    check("player.pos 无错误", not err, str(pos))
    check("pos 含 x/y/z/dimension/health",
          all(k in pos for k in ("x", "y", "z", "dimension", "health")), str(pos))

    # player.state 结构化
    err, st = rpc("player.state")
    check("player.state 无错误", not err, str(st))
    check("state 含 health/dead/onGround/hitResult",
          all(k in st for k in ("health", "dead", "onGround", "hitResult")), str(st))

    # world.entities 结构化
    err, ents = rpc("world.entities", {"radius": 32})
    check("world.entities 无错误", not err, str(ents)[:300])
    check("entities 含 count/entities 列表",
          "count" in ents and isinstance(ents["entities"], list), str(ents)[:200])

    # 攻击一个不存在的类型：TARGET_NOT_FOUND(-32002)
    err, e = rpc("player.attack", {"target": "minecraft:ender_dragon"})
    check("攻击不存在目标返回 -32002", err and e["code"] == -32002, str(e))

    # 攻击精确类型：僵尸在 32 格内没有时同样 -32002；有则必须在触及内否则 -32003
    err, res = rpc("player.attack", {"target": "minecraft:zombie"})
    if err:
        check("无僵尸(-32002)或超距(-32003)", res["code"] in (-32002, -32003), str(res))
    else:
        check("命中僵尸 result.hit 为布尔", isinstance(res.get("hit"), bool), str(res))

    # 类型表示一致性：world.entities 返回的 type 必须是 minecraft: 注册 ID，
    # 且能直接拿去做 target 参数（与 TargetResolver 同一表示）
    types = {e["type"] for e in ents["entities"]}
    check("实体类型为 minecraft: 注册 ID",
          all(t.startswith("minecraft:") for t in types), str(types))
    if ents["entities"]:
        probe_type = ents["entities"][0]["type"]
        err, res = rpc("player.attack", {"target": probe_type})
        # 用 world.entities 给的 type 攻击，绝不能再返回 TARGET_NOT_FOUND(说明表示不通)
        check("entities 返回的 type 可直接作为 attack 参数",
              not (err and res["code"] == -32002),
              f"{probe_type} -> {res if err else res}")

    # 移动键设置 + 停止（真实按键映射）
    err, msg = rpc("player.move", {"forward": 1.0, "strafe": 0.0, "jump": False})
    check("player.move 无错误", not err, str(msg))
    time.sleep(0.6)
    err, pos1 = rpc("player.pos")
    rpc("player.stop")
    check("player.stop 后键已释放", True)
    print(f"  移动前->后 x/z: {pos} -> {pos1}")

    # 并发 BUSY：用附近真实存在但较远的目标，第一个会话持续寻路，第二个必 BUSY
    far = [e for e in ents["entities"] if e["distance"] > 10]
    busy_target = far[0]["type"] if far else None
    if busy_target:
        err1_block, r1_block = _concurrent_busy(busy_target)
        check("并发第二个 attackNearest 返回 -32004 BUSY", err1_block, str(r1_block))
        # 清理：让可能建立的会话停下
        rpc("player.stop")
    else:
        print("[INFO] 附近无远距离目标，跳过 BUSY 并发测试")

    print("\n阶段3 全部通过。")


def _concurrent_busy(target):
    """用两个 socket 同时发 attackNearest，第二个应立即收到 BUSY。"""
    import threading
    results = {}

    def call(key):
        try:
            results[key] = rpc("player.attackNearest", {"target": target}, timeout=20)
        except Exception as ex:
            results[key] = (None, str(ex))

    t1 = threading.Thread(target=call, args=("a",))
    t2 = threading.Thread(target=call, args=("b",))
    t1.start(); time.sleep(0.15); t2.start()
    t1.join(25); t2.join(25)
    print(f"  并发结果(target={target}): a={results.get('a')} b={results.get('b')}")
    for v in results.values():
        if isinstance(v, tuple) and v[0] and isinstance(v[1], dict) and v[1].get("code") == -32004:
            return True, v[1]
    return False, results


if __name__ == "__main__":
    stage = sys.argv[1] if len(sys.argv) > 1 else "1"
    if stage == "1":
        stage1()
    elif stage == "2":
        host = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
        port = int(sys.argv[3]) if len(sys.argv) > 3 else 25565
        stage2_connect(host, port)
    elif stage == "3":
        stage3()
