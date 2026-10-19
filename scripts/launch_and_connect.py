#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""启动测试客户端，等它彻底初始化停稳在标题屏后，再连接测试服务器。

为什么需要这个脚本：
  旧方案靠 gradle 启动参数 --quickPlayMultiplayer 抢连，客户端的资源加载
  和 Mod 初始化还没走完就进服，会出现「进服后又跳回开始屏幕」。旁观时无法
  判断当前状态。现在流程拆成清晰的阶段，每个阶段都打印并等待稳定：

    1. 起 runClient（已在跑则跳过）
    2. 等客户端 RPC(25586) 端口可连且 client.status 可调（粗就绪）
    3. 跳过无障碍引导屏，回到开始屏幕
    4. 确认开始屏幕【连续稳定】SETTLE_SECONDS 秒（初始化确实走完了）
    5. 再发连接指令；进世界瞬间的连接重置只容错不判死，轮询到真正进入世界

Fabric 冷启动坑（NeoForge 启动慢恰好盖住竞态，故一直没暴露）：
  Fabric 上 mod init 很早（日志早早打印「RPC 监听 25586」），但客户端资源/
  渲染/进世界重建还没完成，此刻端口虽在监听、单次 status 甚至能成功，请求
  却可能得不到服务（连接被重置 / 客户端线程超时）。因此就绪判据不能是
  「端口可连」或「单次 status 成功」，必须是标题屏连续稳定；且 Fabric 的
  初始引导屏会在资源加载完后才弹出、盖掉我们过早切出的 TitleScreen，
  settle 观察期内发现被盖回要重新 toTitle。

client.status 返回的是 dict（键 screen / inWorld / connected），
不要再用早期的中文字符串包含判断（那个判据在 status 改回 dict 后已恒假）。

用法：
  python launch_and_connect.py                          # neoforge 启动并连接
  python launch_and_connect.py --loader fabric          # 指定 loader
  python launch_and_connect.py --no-connect             # 只启动，停在开始屏幕
也可用环境变量 MCRPC_LOADER 覆盖 loader。
退出码 0 = 就绪（--no-connect 时到标题屏即可），非 0 = 失败。
"""
import argparse
import json
import os
import socket
import subprocess
import sys
import time

RPC_HOST, RPC_PORT = "127.0.0.1", 25586
# 测试服主机/端口可用环境变量覆盖，默认本机
SERVER_HOST = os.environ.get("MCRPC_SERVER_HOST", "127.0.0.1")
SERVER_PORT = int(os.environ.get("MCRPC_SERVER_PORT", "25565"))
# 被测客户端工程（runClient 入口）：由环境变量 MCRPC_CLIENT_PROJECT 指向，
# 框架不内置任何具体项目路径
PROJECT_DIR = os.environ.get("MCRPC_CLIENT_PROJECT")

BOOT_TIMEOUT = 240      # 客户端启动到 RPC 粗就绪最长等
SETTLE_TIMEOUT = 90     # 标题屏连续稳定阶段最长等
CONNECT_TIMEOUT = 120   # 发起连接到进入世界最长等（含进世界瞬间连接重建）
SETTLE_SECONDS = 5      # 标题屏需连续稳定多久才算初始化走完
POLL_INTERVAL = 1.0


def log(msg):
    print(f"[启动] {msg}", flush=True)


class RpcError(RuntimeError):
    """服务端正常返回了 JSON-RPC error（业务层失败，不应重试掩盖）。"""
    def __init__(self, code, message):
        super().__init__(f"[{code}] {message}")
        self.code = code


class RpcConnError(RuntimeError):
    """传输层失败：连接被重置 / 超时 / 空响应（进世界瞬间常见，可容忍重试）。"""


def rpc(method, params=None, timeout=8.0):
    req = {"jsonrpc": "2.0", "method": method, "id": 1}
    if params:
        req["params"] = params
    line = (json.dumps(req) + "\n").encode("utf-8")
    buf = b""
    try:
        with socket.create_connection((RPC_HOST, RPC_PORT), timeout=timeout) as s:
            s.sendall(line)
            s.settimeout(timeout)
            # 协议按行分隔；读到换行即完整响应，EOF/超时也退出
            while b"\n" not in buf:
                try:
                    chunk = s.recv(4096)
                except socket.timeout:
                    break
                if not chunk:
                    break
                buf += chunk
    except (OSError, ConnectionError) as e:
        # 连接被对端重置（如 Fabric 进世界瞬间 10054）：传输层问题，
        # 上层据此判断是否可重试，绝不能在这里 json.loads 崩成解码异常。
        raise RpcConnError(f"{method} 连接失败：{e}") from e
    if not buf.strip():
        raise RpcConnError(f"{method} 空响应（连接可能在进世界瞬间被重置）")
    resp = json.loads(buf.decode("utf-8").strip())
    if "error" in resp:
        e = resp["error"]
        raise RpcError(e.get("code"), e.get("message", ""))
    return resp.get("result", "")


def status(timeout=5):
    """client.status，返回 dict；任何传输/业务/解析失败都返回 None。"""
    try:
        st = rpc("client.status", timeout=timeout)
        return st if isinstance(st, dict) else None
    except (RpcConnError, RpcError, json.JSONDecodeError):
        return None


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


def gradle_task(loader):
    return f":{loader}:runClient"


def launch_client(loader):
    if not PROJECT_DIR:
        raise RuntimeError(
            "未设置被测客户端工程：请用环境变量 MCRPC_CLIENT_PROJECT 指向"
            "含 gradlew 的工程根目录")
    gradlew = os.path.join(PROJECT_DIR, "gradlew.bat")
    if not os.path.exists(gradlew):
        raise RuntimeError(f"找不到 {gradlew}，检查环境变量 MCRPC_CLIENT_PROJECT")
    logfile = open(os.path.join(PROJECT_DIR, f"runClient_auto_{loader}.log"), "ab")
    # 独立进程组 + 脱离控制台，脚本退出后客户端继续跑
    flags = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    subprocess.Popen(
        ["cmd", "/c", "gradlew.bat", gradle_task(loader)],
        cwd=PROJECT_DIR, stdout=logfile, stderr=subprocess.STDOUT,
        creationflags=flags, close_fds=True)
    log(f"已发起 {gradle_task(loader)}（日志：runClient_auto_{loader}.log）")


def wait_rpc_up():
    """粗就绪：端口可连且 client.status 至少成功一次。

    注意这只代表 RPC 线程能应答，不代表初始化走完——Fabric 冷启动时这一步
    可能早在资源加载完成前就通过，所以后面还要过 settle_title 这道硬关。
    """
    t0 = time.time()
    while time.time() - t0 < BOOT_TIMEOUT:
        if port_open() and status(timeout=3) is not None:
            return time.time() - t0
        time.sleep(POLL_INTERVAL)
    raise RuntimeError(f"{BOOT_TIMEOUT}s 内客户端 RPC 未就绪")


def settle_title():
    """到开始屏幕并【连续稳定】SETTLE_SECONDS 秒。

    Fabric 初始引导屏(AccessibilityOnboardingScreen)在资源加载完成后才弹出，
    可能盖掉我们过早切出的 TitleScreen——观察期内发现不在标题屏就重切 toTitle。
    这是「初始化确实走完、RPC 连续可服务」的硬判据。
    """
    last_force = 0.0
    stable = 0.0
    t0 = time.time()
    while time.time() - t0 < SETTLE_TIMEOUT:
        st = status(timeout=5)
        at_title = (bool(st) and st.get("screen") == "TitleScreen"
                    and not st.get("inWorld"))
        if at_title:
            stable += POLL_INTERVAL
            if stable >= SETTLE_SECONDS:
                return
        else:
            if stable > 0:
                log("标题屏未保持稳定（可能被引导屏盖回），重新计时……")
            stable = 0.0
            # 节流重切：toTitle 负责跳过拦路引导屏，已在标题屏则无副作用
            if time.time() - last_force > 3:
                try:
                    rpc("client.toTitle", timeout=5)
                except (RpcConnError, RpcError):
                    pass
                last_force = time.time()
        time.sleep(POLL_INTERVAL)
    raise RuntimeError(f"开始屏幕 {SETTLE_TIMEOUT}s 内未能连续稳定（初始化异常？）")


def connect_and_wait():
    log(f"初始化已走完，开始连接 {SERVER_HOST}:{SERVER_PORT} ……")
    # client.connect 是异步发起。Fabric 进世界瞬间连接层可能被重置、这次请求
    # 收不到响应，但客户端往往已经在连——所以 connect 的传输层异常不立即判死，
    # 落入下面的轮询，由 inWorld 终态裁决。业务层 RpcError 才是真失败。
    try:
        rpc("client.connect", {"host": SERVER_HOST, "port": SERVER_PORT}, timeout=15)
    except RpcConnError as e:
        log(f"connect 传输层异常（先继续观察是否已在进世界）：{e}")

    t0 = time.time()
    last = None
    while time.time() - t0 < CONNECT_TIMEOUT:
        st = status(timeout=8)
        if st is None:
            # 进世界瞬间连接重建：RPC 暂不可用属预期，不空转轮询、不判死。
            time.sleep(POLL_INTERVAL)
            continue
        if st.get("inWorld") and st.get("screen") is None:
            return st
        sig = (st.get("screen"), st.get("connected"), st.get("inWorld"))
        if sig != last:
            log(f"  screen={st.get('screen')} connected={st.get('connected')} inWorld={st.get('inWorld')}")
            last = sig
        time.sleep(POLL_INTERVAL)
    raise RuntimeError(f"{CONNECT_TIMEOUT}s 内未进入世界")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--loader", choices=["neoforge", "fabric"], default="neoforge",
                    help="runClient 子工程（默认 neoforge；也可用 MCRPC_LOADER 覆盖）")
    ap.add_argument("--no-connect", action="store_true",
                    help="只启动并停在开始屏幕，不连接服务器")
    args = ap.parse_args()
    loader = os.environ.get("MCRPC_LOADER", args.loader).lower()
    if loader not in ("neoforge", "fabric"):
        raise SystemExit(f"不支持的 loader: {loader}（neoforge|fabric）")

    if port_open():
        log("客户端 RPC 已在监听，跳过启动")
    else:
        launch_client(loader)
        elapsed = wait_rpc_up()
        log(f"客户端 RPC 粗就绪（启动耗时 {elapsed:.0f}s）")

    settle_title()
    log(f"开始屏幕已连续稳定 {SETTLE_SECONDS}s —— 初始化全部走完")

    if args.no_connect:
        log("--no-connect：停在开始屏幕，不连接。完成")
        return 0

    st = connect_and_wait()
    log("已进入世界：" + json.dumps(st, ensure_ascii=False))
    try:
        log(str(rpc("player.pos", timeout=10)))
    except (RpcConnError, RpcError) as e:
        log(f"进入后取坐标失败（不影响连接结论）：{e}")
    log("完成")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        log(f"失败：{e}")
        sys.exit(1)
