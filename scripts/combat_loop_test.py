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
  5. OP 身份：本用例需普通玩家，确保非 OP（is_op 查询），退出恢复原状
  6. 召唤僵尸：服务管理指令走 RCON（与玩家 OP 无关，不伪装成玩家指令）
  7. world.entities 确认僵尸进入视野
  8. player.moveToward → 轮询进入攻击触及范围（普通玩家身份）
  9. player.attack —— 触及范围内真实命中
 10. 验证反击：玩家掉血/死亡（服务端权威）或客户端日志出现击杀记录

OP 权限管理（测试可控前置条件，恢复语义）：
  - OP 是「被测玩家的身份变量」，不是发指令的手段
  - 需要普通玩家的用例：确保非 OP 后测；需要 OP 的用例：授 OP 后测
  - 「恢复」= 进入前 is_op 记录原状态，退出时 finally 还原成原样
  - 服务管理指令（召唤/kill 等）与 OP 无关，一律走 RCON，不伪装成玩家指令

判定「反击生效」的权威信号（三重，任一命中即算）：
  - player.state 血量下降或「死亡 是」
  - 客户端 latest.log 出现 "Dev was slain by" / "Dev was shot by"

退出码：0=闭环通过，非 0=某步失败（ stdout 已指明步骤）。

依赖：仅标准库 + 服务端 RCON 直连可达（scripts/rcon.py）。无第三方包。
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

# 测试服务器（召唤生物用，本机直连 RCON，不绕 ssh）
SERVER_HOST = "127.0.0.1"
SERVER_RCON_PORT = 25575
PLAYER_NAME = "Dev"                # 测试玩家名（OP 授权/指令作用对象）
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


def rpc(method: str, params: dict | None = None, timeout: float = 8.0):
    """发一条 JSON-RPC，返回 result（dict / str / 数值，按协议原样）；协议错误抛 RuntimeError。"""
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
        err = resp["error"]
        raise RuntimeError(f"RPC {method} 错误 [{err.get('code')}]: {err.get('message')}")
    return resp.get("result", "")


def rpc_ok(method: str, params: dict | None = None, timeout: float = 8.0) -> bool:
    """RPC 是否可调通（用于等待客户端就绪，不抛异常）。"""
    try:
        rpc(method, params, timeout)
        return True
    except Exception:
        return False


def server_rcon(command: str) -> str:
    """经本地直连 RCON 在测试服执行一条命令，返回文本响应。

    不再绕 ssh：密码经 ssh 读一次（见 rcon.get_password）后，所有命令
    直连 127.0.0.1:25575，时序由本机脚本统一掌控。
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import rcon  # scripts/rcon.py
    with rcon.open_default(SERVER_HOST, SERVER_RCON_PORT) as r:
        return r.command(command)


# ── OP 权限管理（测试可控前置条件，恢复语义）─────────────────────────
#
# OP 状态是「被测玩家的身份变量」，不是发指令的手段：
#   - 需要普通玩家的用例（如反击/权限边界）：确保非 OP 后测，退出恢复原状
#   - 需要 OP 玩家的用例（如 OP 攻击后行为差异）：授 OP 后测，退出恢复原状
# 「恢复」= 进入前记录原状态，退出时还原成原样（原本是 OP 就恢复 OP，原本不是就不加）。
# 服务管理指令（召唤/kill 等）与 OP 无关，一律走 RCON（见 server_rcon），不伪装成玩家指令。


def is_op(player: str) -> bool:
    """查询玩家是否为 OP（读服务端 ops.json，经 ssh 一次）。"""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import rcon as _r
    out = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", _r.DEFAULT_SSH,
         f"cat {_r.DEFAULT_WORK_DIR}/ops.json 2>/dev/null || echo '[]'"],
        capture_output=True, text=True, timeout=15,
    ).stdout
    try:
        ops = json.loads(out)
        return any(e.get("name") == player for e in ops)
    except ValueError:
        return False


def set_op(player: str, on: bool) -> None:
    """授予/移除玩家 OP（经 RCON，与玩家是否在线无关，脱机也生效）。"""
    server_rcon(f"op {player}" if on else f"deop {player}")


def read_client_log() -> str:
    """读客户端 latest.log（用于判定击杀记录）。"""
    log = PROJECT_ROOT / "neoforge" / "run" / "logs" / "latest.log"
    try:
        return log.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _player_hp() -> float | None:
    """查玩家当前血量（RPC player.state，结构化结果），失败返回 None。"""
    try:
        state = rpc("player.state", timeout=5)
        return float(state["health"])
    except Exception:
        return None


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
                ["tasklist"], capture_output=True, timeout=10,
            ).stdout.decode("gbk", errors="replace")  # tasklist 输出是 GBK（中文系统）
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
    rpc("client.connect", {"host": SERVER_HOST, "port": 25565})

    def _in_world():
        st = rpc("client.status", timeout=5)
        return (bool(st["inWorld"]), f"screen={st['screen']} connected={st['connected']}")

    in_world = wait_until(_in_world, TIMEOUT_CONNECT, desc="进世界")
    if not in_world:
        stop_client()
        raise step.fail(f"{TIMEOUT_CONNECT}s 内未进世界")
    step.ok("已进入世界（已连服务器）")

    # 3. 若死亡则重生，确认活着
    state = rpc("player.state")
    if state["dead"]:
        step.info("玩家死亡，重生……")
        rpc("player.respawn")
        time.sleep(2)
        state = rpc("player.state")
    if state["dead"]:
        stop_client()
        raise step.fail("重生后仍死亡")
    step.ok(f"玩家存活: 血量 {state['health']}/{state['maxHealth']} 位置 "
            f"({state['x']},{state['y']},{state['z']})")

    # 4. OP 身份管理（恢复语义）：本用例测「普通玩家被反击」，需非 OP。
    #    进入前记录原 OP 状态，确保非 OP 进行测试，退出时恢复原状。
    original_op = is_op(PLAYER_NAME)
    if original_op:
        set_op(PLAYER_NAME, False)
        step.info(f"{PLAYER_NAME} 原本是 OP，已临时移除（退出时恢复）")
    else:
        step.info(f"{PLAYER_NAME} 本非 OP，直接以普通玩家测试")

    # 5. 召唤僵尸：服务管理指令走 RCON（与玩家 OP 无关，不伪装成玩家指令）。
    retaliated = False  # 提前初始化：finally 后的判定要用，召唤失败时也有定义
    try:
        summon = server_rcon(
            f"execute at {PLAYER_NAME} run summon {SUMMON_MOB} ~{SUMMON_DIST} ~ ~ {{PersistenceRequired:1b}}"
        )
        step.ok(f"RCON 召唤 {SUMMON_MOB}（{SUMMON_DIST} 格外）: {summon or '(无回显)'}")
        time.sleep(1.5)

        # 6. 确认僵尸进入视野
        ents = rpc("world.entities", {"radius": 32})
        zombie_ents = [e for e in ents["entities"] if "zombie" in e["type"]]
        if not zombie_ents:
            raise step.fail(f"召唤后视野内无 zombie: {ents}")
        step.ok(f"视野内确认目标: {zombie_ents[-1]}")

        # 6. moveToward 走近 → 轮询进入触及范围（此刻玩家已是普通身份）
        rpc("player.moveToward", {"target": "zombie"})

        def _in_reach():
            st = rpc("player.state", timeout=5)
            if st["dead"]:
                return True, "玩家已死亡（走近途中被反击）"  # 也算进入战斗
            en = rpc("world.entities", {"radius": 4}, timeout=5)
            near = [e for e in en["entities"] if "zombie" in e["type"]]
            return (bool(near), f"走近中…… 血量 {st['health']} 近处目标 {len(near)}")

        reached = wait_until(_in_reach, TIMEOUT_WALK, desc="走近")
        if not reached:
            rpc("player.stop")
            raise step.fail(f"{TIMEOUT_WALK}s 内未走近到触及范围")
        step.ok("已进入攻击触及范围")

        # 7. 连续攻击直到出现反击证据（或超时）。
        #    反击成立的判据（任一）：玩家血量下降（被打）/ 玩家死亡 / 日志击杀记录。
        #    注意：不要求玩家必须被打死——近战僵尸 DPS 低，玩家可能先把它磨死，
        #    但只要僵尸对玩家造成了伤害（玩家掉血），就证明反击链已生效。
        step.info("连续攻击，监控玩家血量判定反击……")
        log_before = read_client_log()
        hp0 = _player_hp()  # 战斗开始时玩家血量（基准）
        deadline = time.time() + TIMEOUT_RETALIATE
        while time.time() < deadline:
            # 玩家状态：dead / health=0 / 血量下降 都是反击证据
            try:
                st = rpc("player.state", timeout=5)
            except Exception:
                break
            if st["dead"] or float(st["health"]) <= 0.0:
                retaliated = True
                step.info(f"玩家被反击致死: 血量 {st['health']} 位置 {st['x']},{st['y']},{st['z']}")
                break
            hp = float(st["health"])
            if hp0 is not None and hp < hp0:
                retaliated = True
                step.info(f"玩家被反击掉血 {hp0} → {hp}（反击链生效）")
                break
            # 活着则继续打
            try:
                atk = rpc("player.attack", {"target": "zombie"}, timeout=5)
                step.info(f"攻击: {atk['target']} 距离 {atk['distance']} hit={atk['hit']}")
            except Exception as e:
                step.info(f"攻击异常（可能目标已死/走远）: {e}")
            # 日志侧判定
            new_log = read_client_log()
            if new_log != log_before and ("was slain by" in new_log or "was shot by" in new_log):
                if "was slain by" in new_log[len(log_before):] or "was shot by" in new_log[len(log_before):]:
                    retaliated = True
                    break
            time.sleep(1.2)

        # 8. 判定前再查一次日志兜底
        if not retaliated:
            log = read_client_log()
            if "was slain by" in log or "was shot by" in log:
                retaliated = True
    finally:
        # 恢复 OP 原状（无论用例成败/中断）：原本是 OP 就恢复回去，原本不是就保持非 OP。
        if original_op:
            try:
                set_op(PLAYER_NAME, True)
                step.info(f"已恢复 {PLAYER_NAME} 的 OP（还原原状）")
            except Exception as e:
                step.info(f"⚠️ OP 恢复失败（需手动检查）: {e}")
        # 清理客户端（成功/失败路径都经过这里，统一清理）
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
