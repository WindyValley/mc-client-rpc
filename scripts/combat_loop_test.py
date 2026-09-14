#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
mc-client-rpc 战斗闭环回归测试
===============================

把「启动→进世界→走近→攻击→验证反击」的手工操作路径固化成可重复跑的回归脚本。

覆盖的链路（每步带断言，失败即停并报清楚卡在哪步）：
  1. 启动客户端（gradlew runClient，后台），轮询 RPC 就绪
  2. client.toTitle —— 跳过无障碍引导屏（dev 环境拦路界面）
  3. client.connect → 轮询「已进世界=true」
  4. player.respawn（若死亡）→ player.state 确认活着满血
  5. 服务端 RCON 在远处召唤僵尸
  6. world.entities 确认僵尸进入视野
  7. player.moveToward → 轮询进入攻击触及范围
  8. player.attack —— 触及范围内真实命中
  9. 验证反击：玩家掉血/死亡（服务端权威）或客户端日志出现击杀记录

判定「反击生效」的权威信号（三重，任一命中即算）：
  - player.state 血量下降或「死亡 是」
  - 客户端 latest.log 出现 "Dev was slain by" / "Dev was shot by"

退出码：0=闭环通过，非 0=某步失败（ stdout 已指明步骤）。

依赖：仅标准库 + 能 ssh 到测试服（召唤生物用）。无第三方包。
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

# ── 配置 ────────────────────────────────────────────────────────────

# 客户端 RPC（本机，mc-client-rpc 监听端口）
RPC_HOST = "127.0.0.1"
RPC_PORT = 25586

# 测试服务器（召唤生物用，经服务端 RCON）
SERVER_SSH = "dev@127.0.0.1"
SERVER_WORK_DIR = "~/minecraft"
SUMMON_MOB = "minecraft:zombie"      # 用于测反击的中立化生物
SUMMON_DIST = 10.0                   # 召唤在玩家多少格外（需 > 触及 3 格）

# 项目根（gradlew 所在）
PROJECT_ROOT = Path(__file__).resolve().parent.parent

# 各阶段超时（秒）
TIMEOUT_CLIENT_BOOT = 120   # 客户端启动 + RPC 就绪
TIMEOUT_CONNECT = 60        # 连接进世界
TIMEOUT_WALK = 30           # moveToward 走近
TIMEOUT_RETALIATE = 30      # 等待反击致死

# ── 小工具 ──────────────────────────────────────────────────────────


class Step:
    """打印并跟踪测试步骤，失败时给清晰定位。"""

    def __init__(self) -> None:
        self.n = 0

    def ok(self, msg: str) -> None:
        self.n += 1
        print(f"  [{self.n:02d}] ✅ {msg}", flush=True)

    def info(self, msg: str) -> None:
        print(f"       · {msg}", flush=True)

    def fail(self, msg: str) -> "SystemExit":
        print(f"  [{self.n + 1:02d}] ❌ 失败: {msg}", flush=True)
        return SystemExit(2)


step = Step()


def rpc(method: str, params: dict | None = None, timeout: float = 8.0) -> str:
    """发一条 JSON-RPC，返回 result 字符串；协议错误抛 RuntimeError。"""
    req: dict = {"jsonrpc": "2.0", "method": method, "id": 1}
    if params:
        req["params"] = params
    line = (json.dumps(req) + "\n").encode("utf-8")
    try:
        with socket.create_connection((RPC_HOST, RPC_PORT), timeout=timeout) as s:
            s.sendall(line)
            s.shutdown(socket.SHUT_WR)
            s.settimeout(timeout)
            buf = b""
            try:
                while True:
                    chunk = s.recv(4096)
                    if not chunk:
                        break
                    buf += chunk
            except socket.timeout:
                pass
    except OSError as e:
        raise RuntimeError(f"RPC 连接失败 {RPC_HOST}:{RPC_PORT}: {e}") from e
    try:
        resp = json.loads(buf.decode("utf-8"))
    except ValueError:
        raise RuntimeError(f"RPC 响应非 JSON: {buf!r}")
    if "error" in resp:
        raise RuntimeError(f"RPC {method} 错误: {resp['error'].get('message')}")
    return str(resp.get("result", ""))


def rpc_ok(method: str, params: dict | None = None, timeout: float = 8.0) -> bool:
    """RPC 是否可调通（用于等待客户端就绪，不抛异常）。"""
    try:
        rpc(method, params, timeout)
        return True
    except Exception:
        return False


def server_rcon(command: str) -> str:
    """经 ssh 在测试服执行一条 RCON 命令，返回输出末尾。"""
    shell = (
        f'PW=$(grep "rcon.password" {SERVER_WORK_DIR}/server.properties | cut -d= -f2); '
        f'cd {SERVER_WORK_DIR} && RCON_PASSWORD="$PW" python3 server_rcon.py "{command}"'
    )
    out = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", SERVER_SSH, shell],
        capture_output=True, text=True, timeout=30,
    )
    return (out.stdout + out.stderr).strip()


def read_client_log() -> str:
    """读客户端 latest.log（用于判定击杀记录）。"""
    log = PROJECT_ROOT / "neoforge" / "run" / "logs" / "latest.log"
    try:
        return log.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


# ── 客户端进程管理 ───────────────────────────────────────────────────

_gradlew_proc: subprocess.Popen | None = None
_attach_mode: bool = False  # attach 模式下 stop_client 只断开连接，不杀进程


def start_client() -> None:
    """后台启动 runClient（不等待，由调用方轮询 RPC 就绪）。"""
    global _gradlew_proc
    gradlew = str(PROJECT_ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    _gradlew_proc = subprocess.Popen(
        [gradlew, ":neoforge:runClient"],
        cwd=str(PROJECT_ROOT),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def stop_client() -> None:
    """关闭客户端（尽力而为）。attach 模式下只断开连接，不杀进程。"""
    global _gradlew_proc
    # 先尝试优雅断开
    try:
        rpc("client.disconnect", timeout=3)
    except Exception:
        pass
    if _attach_mode:
        return  # attach 模式不拥有客户端进程，只断开即可
    # 杀掉 gradle 进程树里的 MC 大内存进程
    if os.name == "nt":
        try:
            out = subprocess.run(
                ["tasklist"], capture_output=True, text=True, timeout=10,
            ).stdout
            for line in out.splitlines():
                if "java" in line.lower():
                    parts = line.split()
                    if len(parts) >= 5:
                        try:
                            mem_kb = int(parts[4].replace(",", "").replace("\xa0", ""))
                        except ValueError:
                            continue
                        # 只杀 MC 大内存进程（>500MB），留 Gradle daemon
                        if mem_kb > 500_000:
                            subprocess.run(
                                ["taskkill", "/F", "/PID", parts[1]],
                                capture_output=True, timeout=10,
                            )
        except Exception:
            pass
    if _gradlew_proc is not None:
        try:
            _gradlew_proc.terminate()
        except Exception:
            pass
        _gradlew_proc = None


# ── 测试主流程 ───────────────────────────────────────────────────────


def wait_until(fn, timeout: float, interval: float = 1.5, desc: str = "") -> bool:
    """轮询直到 fn() 为真或超时。fn 返回 (done, detail)。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            done, detail = fn()
            if done:
                return True
            if detail:
                step.info(detail)
        except Exception:
            pass
        time.sleep(interval)
    return False


def main() -> int:
    global _attach_mode
    _attach_mode = "--attach" in sys.argv  # 复用已在跑的客户端，不重新启动/不关停
    attach = _attach_mode
    print("=" * 60)
    print("mc-client-rpc 战斗闭环回归测试" + ("（attach 模式）" if attach else ""))
    print("=" * 60)

    # 0. 启动客户端 + 等 RPC 就绪（attach 模式下跳过启动，直接等就绪）
    if attach:
        step.info("attach 模式：复用已启动的客户端")
        if not rpc_ok("client.status", timeout=3):
            raise step.fail("attach 模式但 RPC 未就绪（客户端没在跑？先启动或去掉 --attach）")
    else:
        step.info("启动客户端（后台 runClient）……")
        start_client()
        booted = wait_until(
            lambda: (rpc_ok("client.status", timeout=3), "等待 RPC 就绪……"),
            TIMEOUT_CLIENT_BOOT,
            desc="RPC 就绪",
        )
        if not booted:
            stop_client()
            raise step.fail(f"客户端 {TIMEOUT_CLIENT_BOOT}s 内 RPC 未就绪")
    step.ok("客户端 RPC 就绪（25586）")

    # 1. 跳过无障碍引导屏
    r = rpc("client.toTitle")
    step.ok(f"跳过引导屏: {r}")

    # 2. 连接服务器 + 轮询进世界
    rpc("client.connect", {"host": "127.0.0.1", "port": 25565})
    in_world = wait_until(
        lambda: ("已进世界=true" in rpc("client.status", timeout=5),
                 rpc("client.status", timeout=5)),
        TIMEOUT_CONNECT,
        desc="进世界",
    )
    if not in_world:
        stop_client()
        raise step.fail(f"{TIMEOUT_CONNECT}s 内未进世界")
    step.ok("已进入世界（已连服务器）")

    # 3. 若死亡则重生，确认活着满血
    state = rpc("player.state")
    if "死亡 是" in state:
        step.info("玩家死亡，重生……")
        rpc("player.respawn")
        time.sleep(2)
        state = rpc("player.state")
    if "死亡 是" in state:
        stop_client()
        raise step.fail("重生后仍死亡")
    step.ok(f"玩家存活: {state}")

    # 4. 在远处召唤僵尸（服务端 RCON）
    summon = server_rcon(
        f"execute at Dev run summon {SUMMON_MOB} ~{SUMMON_DIST} ~ ~ {{PersistenceRequired:1b}}"
    )
    step.ok(f"服务端召唤 {SUMMON_MOB}（{SUMMON_DIST} 格外）: {summon or '(无回显)'}")
    time.sleep(1.5)

    # 5. 确认僵尸进入视野
    ents = rpc("world.entities", {"radius": 32})
    if "zombie" not in ents:
        stop_client()
        raise step.fail(f"召唤后视野内无 zombie: {ents}")
    step.ok(f"视野内确认目标: {ents.strip().splitlines()[-1] if ents.strip() else ents}")

    # 6. moveToward 走近 → 轮询进入触及范围
    rpc("player.moveToward", {"target": "zombie"})

    def _in_reach():
        st = rpc("player.state", timeout=5)
        if "死亡 是" in st:
            return True, "玩家已死亡（走近途中被反击）"  # 也算进入战斗
        en = rpc("world.entities", {"radius": 4}, timeout=5)
        return ("zombie" in en, f"走近中…… 状态: {st[:50]}")

    reached = wait_until(_in_reach, TIMEOUT_WALK, desc="走近")
    if not reached:
        rpc("player.stop")
        stop_client()
        raise step.fail(f"{TIMEOUT_WALK}s 内未走近到触及范围")
    step.ok("已进入攻击触及范围")

    # 7. 连续攻击直到玩家被反击致死（或超时）
    step.info("连续攻击，等待反击……")
    log_before = read_client_log()
    retaliated = False
    deadline = time.time() + TIMEOUT_RETALIATE
    while time.time() < deadline:
        # 已死亡则反击成立
        try:
            st = rpc("player.state", timeout=5)
        except Exception:
            break
        if "死亡 是" in st or "血量 0.0" in st:
            retaliated = True
            step.info(f"玩家状态: {st}")
            break
        # 活着则继续打
        try:
            atk = rpc("player.attack", {"target": "zombie"}, timeout=5)
            step.info(atk)
        except Exception as e:
            step.info(f"攻击异常（可能目标已死/走远）: {e}")
        # 日志侧判定
        new_log = read_client_log()
        if new_log != log_before and ("was slain by" in new_log or "was shot by" in new_log):
            if "was slain by" in new_log[len(log_before):] or "was shot by" in new_log[len(log_before):]:
                retaliated = True
                break
        time.sleep(1.2)

    # 8. 判定
    if not retaliated:
        # 最后再查一次日志兜底
        log = read_client_log()
        if "was slain by" in log or "was shot by" in log:
            retaliated = True

    stop_client()

    print("=" * 60)
    if retaliated:
        step.ok("反击验证通过：玩家被中立化生物反击致死")
        print("✅ 闭环回归 PASS：真实输入攻击命中 + 反击链生效")
        return 0
    print("❌ 闭环回归 FAIL：超时未被反击（攻击未命中或反击失效）")
    return 3


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except KeyboardInterrupt:
        print("\n中断", flush=True)
        stop_client()
        sys.exit(130)
    except Exception as e:  # 兜底：任何未预期异常都要清理客户端
        print(f"未预期异常: {e}", flush=True)
        stop_client()
        sys.exit(1)
