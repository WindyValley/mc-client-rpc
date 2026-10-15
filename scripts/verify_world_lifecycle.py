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
import gzip
import json
import os
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RPC_HOST, RPC_PORT = "127.0.0.1", 25586


def _parse_loader():
    """--loader fabric|neoforge（默认 neoforge），决定 runClient task 与 run 目录。"""
    loader = "neoforge"
    for i, a in enumerate(sys.argv):
        if a == "--loader" and i + 1 < len(sys.argv):
            loader = sys.argv[i + 1].lower()
        elif a.startswith("--loader="):
            loader = a.split("=", 1)[1].lower()
    if loader not in ("neoforge", "fabric"):
        raise SystemExit(f"不支持的 loader: {loader}（neoforge|fabric）")
    return loader


LOADER = _parse_loader()
GRADLE_TASK = f":{LOADER}:runClient"
RUN_DIR = ROOT / LOADER / "run"
SAVES = RUN_DIR / "saves"
WORLD_PREFIX = "mcrpc-wl-"
BOOT_TIMEOUT, SETTLE_SECONDS, POLL = 240, 5, 1.0


def _read_payload(buf, pos, tag_type):
    """返回 (跳过该 payload 后的新 pos, children_or_None, raw_value_or_None)。

    最小 NBT 读取：Compound 递归成 [(name,(type,node))]；Int 取值；
    其他类型只算长度跳过，payload 原样保留在父级 raw 里（结构不变）。
    """
    if tag_type == 1:
        return pos + 1, None, None
    if tag_type == 2:
        return pos + 2, None, None
    if tag_type == 3:
        return pos + 4, None, struct.unpack(">i", buf[pos:pos + 4])[0]
    if tag_type == 4:
        return pos + 8, None, None
    if tag_type == 5:
        return pos + 4, None, None
    if tag_type == 6:
        return pos + 8, None, None
    if tag_type == 7:
        n = struct.unpack(">i", buf[pos:pos + 4])[0]
        return pos + 4 + n, None, None
    if tag_type == 8:
        n = struct.unpack(">H", buf[pos:pos + 2])[0]
        return pos + 2 + n, None, None
    if tag_type == 9:
        child_type = buf[pos]
        n = struct.unpack(">i", buf[pos + 1:pos + 5])[0]
        pos = pos + 5
        for _ in range(n):
            pos, _, _ = _read_payload(buf, pos, child_type)
        return pos, None, None
    if tag_type == 10:
        children = []
        while True:
            t = buf[pos]
            pos += 1
            if t == 0:
                break
            nl = struct.unpack(">H", buf[pos:pos + 2])[0]
            name = buf[pos + 2:pos + 2 + nl].decode("utf-8", "replace")
            pos = pos + 2 + nl
            pos, kids, val = _read_payload(buf, pos, t)
            children.append((name, t, kids, val))
        return pos, children, None
    if tag_type == 11:
        n = struct.unpack(">i", buf[pos:pos + 4])[0]
        return pos + 4 + n * 4, None, None
    if tag_type == 12:
        n = struct.unpack(">i", buf[pos:pos + 4])[0]
        return pos + 4 + n * 8, None, None
    raise ValueError(f"不支持的 NBT tag 类型 {tag_type} @ {pos}")


def _find(children, name):
    for c in children:
        if c[0] == name:
            return c
    return None


def bump_leveldat_version_id(level_dat: Path, new_id: int):
    """把 level.dat 内 Data.Version.Id 改成 new_id（结构保留，其余字节不动）。

    用途：造「降级加载」场景——游戏按 Data.Version.Id 判定数据版本更高而弹
    BackupConfirmScreen，但真正 DFU 读的是根 DataVersion（不动），世界仍可加载。
    """
    raw = gzip.decompress(level_dat.read_bytes())
    assert raw[0] == 10
    nl = struct.unpack(">H", raw[1:3])[0]
    root_start = 3 + nl
    end, root, _ = _read_payload(raw, root_start, 10)
    assert end == len(raw), f"NBT 长度不一致 {end} != {len(raw)}"

    def old_id():
        data = _find(root, "Data")
        ver = _find(data[2], "Version")
        node = _find(ver[2], "Id")
        assert node is not None and node[1] == 3
        return node[3]

    def find_int_offset(buf, start, target_path, path=()):
        """在 compound(start) 内找 target_path 指向的 Int payload 偏移。"""
        p = start
        while True:
            t = buf[p]
            p += 1
            if t == 0:
                return None
            nml = struct.unpack(">H", buf[p:p + 2])[0]
            nm = buf[p + 2:p + 2 + nml].decode("utf-8", "replace")
            p0 = p + 2 + nml
            cur = path + (nm,)
            if cur == target_path and t == 3:
                return p0
            if t == 10:
                hit = find_int_offset(buf, p0, target_path, cur)
                if hit is not None:
                    return hit
            p, _, _ = _read_payload(buf, p0, t)

    off = find_int_offset(raw, root_start, ("Data", "Version", "Id"))
    assert off is not None, "找不到 Data/Version/Id"
    before = struct.unpack(">i", raw[off:off + 4])[0]
    assert before == old_id(), f"偏移定位错误 {before} != {old_id()}"
    patched = raw[:off] + struct.pack(">i", new_id) + raw[off + 4:]
    level_dat.write_bytes(gzip.compress(patched))
    print(f"[夹具] {level_dat.name}: Data.Version.Id {before} -> {new_id}")

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
    log = open(ROOT / f"runClient_world_verify_{LOADER}.log", "ab")
    flags = 0x00000008 | 0x00000200
    subprocess.Popen(
        ["cmd", "/c", "gradlew.bat", GRADLE_TASK],
        cwd=str(ROOT), stdout=log, stderr=subprocess.STDOUT,
        creationflags=flags, close_fds=True)
    print(f"[启动] 已发起 {GRADLE_TASK}")
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
    # 初始引导屏(AccessibilityOnboardingScreen)在资源加载完成后才弹出，
    # 可能盖掉我们过早切出的 TitleScreen——观察期内发现被盖回就重切。
    stable, t0, last_force = 0.0, time.time(), 0.0
    while time.time() - t0 < 90:
        st = rpc("client.status", timeout=5)
        screen = st.get("screen")
        if screen == "TitleScreen" and not st.get("inWorld"):
            stable += POLL
            if stable >= SETTLE_SECONDS:
                return
        else:
            stable = 0.0
            if time.time() - last_force > 3:
                try:
                    rpc("client.toTitle", timeout=5)
                except Exception:
                    pass
                last_force = time.time()
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

    # 9. 降级存档：改 Data.Version.Id 造高版本，enter 应自动备份后进入
    backups_dir = SAVES.parent / "backups"
    backups_before = {p.name for p in backups_dir.glob("*mcrpc-wl-*.zip")} \
        if backups_dir.exists() else set()

    dg_name = WORLD_PREFIX + "downgrade"
    rpc("world.create", {"name": dg_name, "preset": "flat", "gameMode": "creative"})
    wait_in_world()
    rpc("client.disconnect", timeout=15)
    settle_title()  # 必须等内置服完全退出、释放 level.dat 文件锁

    dg_dat = SAVES / dg_name / "level.dat"
    bump_leveldat_version_id(dg_dat, 9999)  # 3955 → 9999 造降级警告
    # 阻塞式：返回时应已自动点「备份并加入」并进世界
    r = rpc("world.enter", {"id": dg_name}, timeout=130)
    print(f"[downgrade-backup] {r}")
    st = rpc("client.status", timeout=5)
    check(st.get("inWorld") and st.get("screen") is None,
          "降级档自动备份后已进入世界", json.dumps(st, ensure_ascii=False))
    # backups/ 下应出现含该 levelId 的新 zip
    zips_after = {p.name: p.stat().st_size for p in backups_dir.glob("*mcrpc-wl-*.zip")}
    new_zip = [n for n in zips_after if n not in backups_before and dg_name in n]
    check(len(new_zip) == 1 and zips_after[new_zip[0]] > 1000,
          "backups/ 生成该存档的备份 zip", str(new_zip))
    rpc("client.disconnect", timeout=15)
    settle_title()

    # 9b. backup=false：同样的降级档，跳过备份，不产生新 zip，仍进入
    dg2_name = WORLD_PREFIX + "downgrade-nobak"
    rpc("world.create", {"name": dg2_name, "preset": "flat", "gameMode": "creative"})
    wait_in_world()
    rpc("client.disconnect", timeout=15)
    settle_title()
    bump_leveldat_version_id(SAVES / dg2_name / "level.dat", 9999)
    zips_before2 = {p.name for p in backups_dir.glob("*mcrpc-wl-*.zip")}
    r = rpc("world.enter", {"id": dg2_name, "backup": False}, timeout=130)
    print(f"[downgrade-nobackup] {r}")
    st = rpc("client.status", timeout=5)
    check(st.get("inWorld") and st.get("screen") is None,
          "backup=false 跳过备份后也进入世界", json.dumps(st, ensure_ascii=False))
    zips_after2 = {p.name for p in backups_dir.glob("*mcrpc-wl-*.zip")}
    check(not (zips_after2 - zips_before2),
          "backup=false 未产生新备份 zip", str(zips_after2 - zips_before2))
    rpc("client.disconnect", timeout=15)
    settle_title()

    # 10. 重名自动 (1)，不覆盖旧档
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
        bak_removed = []
        bdir = SAVES.parent / "backups"
        if bdir.exists():
            for z in bdir.glob("*mcrpc-wl-*.zip"):
                z.unlink()
                bak_removed.append(z.name)
        print(f"[收尾] 清理测试存档 {removed}")
        print(f"[收尾] 清理测试备份 {bak_removed}（saves={SAVES}）")

    print("-" * 60)
    if _failures:
        print(f"FAIL：{len(_failures)} 项 —— {_failures}")
        sys.exit(1)
    print("ALL PASS")


if __name__ == "__main__":
    main()
