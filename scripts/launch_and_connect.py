#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""启动测试客户端，等它彻底初始化停稳在标题屏后，再连接测试服务器。

为什么需要这个脚本：
  旧方案靠 gradle 启动参数 --quickPlayMultiplayer 抢连，客户端的资源加载
  和 Mod 初始化还没走完就进服，会出现「进服后又跳回开始屏幕」。旁观时无法
  判断当前状态。现在流程拆成清晰的阶段，每个阶段都打印并等待稳定：

    1. 起 runClient（已在跑则跳过）
    2. 等客户端 RPC(25586) 就绪
    3. 跳过无障碍引导屏，回到开始屏幕
    4. 确认开始屏幕连续稳定 SETTLE_SECONDS 秒（初始化确实走完了）
    5. 再发连接指令，轮询到真正进入世界

用法：
  python launch_and_connect.py              # 启动并连接
  python launch_and_connect.py --no-connect  # 只启动，停在开始屏幕（手动旁观/操作）
退出码 0 = 就绪（--no-connect 时到标题屏即可），非 0 = 失败。
"""
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

BOOT_TIMEOUT = 240      # 客户端启动到 RPC 就绪最长等
CONNECT_TIMEOUT = 90    # 发起连接到进入世界最长等
SETTLE_SECONDS = 5      # 标题屏需连续稳定多久才算初始化走完
POLL_INTERVAL = 1.0


def log(msg):
    print(f"[启动] {msg}", flush=True)


def rpc(method, params=None, timeout=8.0):
    req = {"jsonrpc": "2.0", "method": method, "id": 1}
    if params:
        req["params"] = params
    line = (json.dumps(req) + "\n").encode("utf-8")
    with socket.create_connection((RPC_HOST, RPC_PORT), timeout=timeout) as s:
        s.sendall(line)
        s.settimeout(timeout)
        buf = b""
        while b"\n" not in buf:
            chunk = s.recv(4096)
            if not chunk:
                break
            buf += chunk
    resp = json.loads(buf.decode("utf-8").strip())
    if "error" in resp:
        raise RuntimeError(str(resp["error"]))
    return resp.get("result", "")


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


def at_title(status):
    """开始屏幕且未连服、未进世界。"""
    return ("TitleScreen" in status
            and "已进世界=false" in status
            and "已连服务器=false" in status)


def launch_client():
    if not PROJECT_DIR:
        raise RuntimeError(
            "未设置被测客户端工程：请用环境变量 MCRPC_CLIENT_PROJECT 指向"
            "含 gradlew 的工程根目录")
    gradlew = os.path.join(PROJECT_DIR, "gradlew.bat")
    if not os.path.exists(gradlew):
        raise RuntimeError(f"找不到 {gradlew}，检查环境变量 MCRPC_CLIENT_PROJECT")
    logfile = open(os.path.join(PROJECT_DIR, "runClient_auto.log"), "ab")
    # 独立进程组 + 脱离控制台，脚本退出后客户端继续跑
    flags = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    subprocess.Popen(
        ["cmd", "/c", "gradlew.bat", ":neoforge:runClient"],
        cwd=PROJECT_DIR, stdout=logfile, stderr=subprocess.STDOUT,
        creationflags=flags, close_fds=True)
    log("已发起 runClient（日志：runClient_auto.log）")


def wait_rpc():
    t0 = time.time()
    while time.time() - t0 < BOOT_TIMEOUT:
        if port_open():
            try:
                rpc("client.status", timeout=3)
                return time.time() - t0
            except Exception:
                pass
        time.sleep(POLL_INTERVAL)
    raise RuntimeError(f"{BOOT_TIMEOUT}s 内客户端 RPC 未就绪")


def go_title_and_settle():
    """到开始屏幕并连续稳定 SETTLE_SECONDS 秒。"""
    # toTitle 负责跳过无障碍引导屏；已在标题屏则无副作用
    for _ in range(5):
        try:
            rpc("client.toTitle")
            break
        except Exception:
            time.sleep(2)
    else:
        raise RuntimeError("client.toTitle 无响应")

    stable = 0.0
    t0 = time.time()
    while time.time() - t0 < 60:
        st = rpc("client.status")
        if at_title(st):
            stable += POLL_INTERVAL
            if stable >= SETTLE_SECONDS:
                return
        else:
            if stable > 0:
                log("标题屏未保持稳定，重新计时……")
            stable = 0.0
        time.sleep(POLL_INTERVAL)
    raise RuntimeError("开始屏幕 60s 内未能稳定（初始化异常？）")


def connect_and_wait():
    log(f"初始化已走完，开始连接 {SERVER_HOST}:{SERVER_PORT} ……")
    rpc("client.connect", {"host": SERVER_HOST, "port": SERVER_PORT})
    t0 = time.time()
    last = ""
    while time.time() - t0 < CONNECT_TIMEOUT:
        st = rpc("client.status")
        if "已进世界=true" in st:
            return st
        if st != last:
            log("  " + st)
            last = st
        time.sleep(POLL_INTERVAL)
    raise RuntimeError(f"{CONNECT_TIMEOUT}s 内未进入世界")


def main():
    no_connect = "--no-connect" in sys.argv

    if port_open():
        log("客户端 RPC 已在监听，跳过启动")
    else:
        launch_client()
        elapsed = wait_rpc()
        log(f"客户端 RPC 就绪（启动耗时 {elapsed:.0f}s）")

    go_title_and_settle()
    log(f"开始屏幕已连续稳定 {SETTLE_SECONDS}s —— 初始化全部走完")

    if no_connect:
        log("--no-connect：停在开始屏幕，不连接。完成")
        return 0

    st = connect_and_wait()
    log("已进入世界：" + st)
    pos = rpc("player.pos")
    log(pos)
    log("完成")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        log(f"失败：{e}")
        sys.exit(1)
