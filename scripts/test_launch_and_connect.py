#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""launch_and_connect.py 的纯逻辑确定性测试：不起 MC，用内嵌假 RPC server 验证
就绪判据与 Fabric 进世界瞬间连接重置容错（跑真机前的快速回归）。

场景 A：引导屏 -> client.toTitle -> 标题屏连续稳定，--no-connect 应返回 0。
场景 B：标题屏稳定 -> client.connect 空响应(模拟10054) -> 头两次 status 也空响应
        -> 随后 inWorld=true，main 应容错并返回 0（旧实现会 json.loads(b'') 崩）。

直接运行：python scripts/test_launch_and_connect.py；退出码 0 全绿。
注意：假 server 占用本机 25586，跑前请先退出真实测试客户端。
"""
import importlib.util
import json
import socketserver
import sys
import threading
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "launch_and_connect.py"

# 按文件路径加载（模块名带连字符）
spec = importlib.util.spec_from_file_location("lac", SCRIPT)
lac = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lac)
# 加速轮询/稳定窗口
lac.POLL_INTERVAL = 0.02
lac.SETTLE_SECONDS = 0.2
lac.BOOT_TIMEOUT = 2
lac.SETTLE_TIMEOUT = 5
lac.CONNECT_TIMEOUT = 5


class Scenario:
    """按方法/阶段返回脚本化响应；None=关闭连接不回包(模拟10054空响应)。"""
    def __init__(self):
        self.lock = threading.Lock()
        self.reset("A")

    def reset(self, name):
        with self.lock:
            self.name = name
            self.status_calls = 0
            self.post_connect_status = 0
            self.title_calls = 0
            self.connect_calls = 0

    def respond(self, method):
        with self.lock:
            if method == "client.status":
                self.status_calls += 1
                if self.name == "A":
                    # 第 1 次在引导屏，之后到标题屏
                    if self.status_calls == 1:
                        return {"screen": "AccessibilityOnboardingScreen",
                                "inWorld": False, "connected": False}
                    return {"screen": "TitleScreen", "inWorld": False, "connected": False}
                # B：connect 之前一直标题屏；connect 之后头两次 status 空响应，第三次进世界
                if self.connect_calls == 0:
                    return {"screen": "TitleScreen", "inWorld": False, "connected": False}
                self.post_connect_status += 1
                if self.post_connect_status in (1, 2):
                    return None
                return {"screen": None, "inWorld": True, "connected": True}
            if method == "client.toTitle":
                self.title_calls += 1
                return ""
            if method == "client.connect":
                self.connect_calls += 1
                if self.name == "B":
                    return None  # 进世界瞬间连接被重置：空响应
                return ""
            if method == "player.pos":
                return [1.0, 64.0, 2.0]
            return ""


SC = Scenario()


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        conn = self.request
        conn.settimeout(5)
        data = b""
        try:
            while b"\n" not in data:
                c = conn.recv(4096)
                if not c:
                    break
                data += c
        except OSError:
            return
        if not data.strip():
            return
        req = json.loads(data.decode())
        result = SC.respond(req["method"])
        if result is None:
            conn.close()  # 模拟连接重置 / 空响应
            return
        conn.sendall((json.dumps({"jsonrpc": "2.0", "id": req["id"], "result": result})
                      + "\n").encode())


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


srv = Server(("127.0.0.1", 25586), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()

print("=== 场景 A：引导屏 -> toTitle -> 标题屏稳定（--no-connect）===")
SC.reset("A")
sys.argv = ["launch_and_connect.py", "--no-connect"]
rcA = lac.main()
titleA = SC.title_calls
print(f"场景 A 返回码: {rcA}（期望 0），toTitle 次数={titleA}（期望>=1）")
okA = rcA == 0 and titleA >= 1

print("\n=== 场景 B：connect 重置 + status 短暂全空 -> 仍进入世界 ===")
SC.reset("B")
sys.argv = ["launch_and_connect.py", "--loader", "fabric"]
rcB = lac.main()
postB, connB = SC.post_connect_status, SC.connect_calls
print(f"场景 B 返回码: {rcB}（期望 0），connect 次数={connB}（期望1）")
print(f"  connect 后 status 调用={postB}（应>=3：2次空响应后进入世界）")
okB = rcB == 0 and connB == 1 and postB >= 3

srv.shutdown()
ok = okA and okB
print("\n结果:", "ALL PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
