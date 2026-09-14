#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
本地 Source RCON 客户端 —— 开发机直连服务端，不绕 ssh。

把「测试脚本 → ssh → 服务端 server_rcon.py」的多跳，改成
「测试脚本 → 本机直连 127.0.0.1:25575」。召唤/查询等控制指令
的时序全由脚本在本机统一掌控，不再受 ssh 往返与回显不稳定影响。

协议：Source RCON（Valve），Minecraft 服务端原生支持。
  包 = [int32 长度][int32 请求id][int32 类型][payload 字节][0x00 0x00]
  登录: 类型 3；命令: 类型 2。响应按请求 id 配对。
仅标准库。
"""

from __future__ import annotations

import socket
import struct

# RCON 包类型
_SERVERDATA_AUTH = 3          # 登录请求
_SERVERDATA_AUTH_RESPONSE = 2  # 登录响应（类型也是 2，靠请求 id 区分）
_SERVERDATA_EXECCOMMAND = 2   # 执行命令
_SERVERDATA_RESPONSE_VALUE = 0  # 命令响应


class RconError(Exception):
    """RCON 连接/认证/通信错误。"""


class Rcon:
    """一个到 Minecraft 服务端的 RCON 连接。用法::

        with Rcon("127.0.0.1", 25575, password) as r:
            out = r.command("list")
    """

    def __init__(self, host: str, port: int, password: str, timeout: float = 10.0) -> None:
        self.host = host
        self.port = port
        self.password = password
        self.timeout = timeout
        self._sock: socket.socket | None = None
        self._req_id = 0

    # ── 连接生命周期 ────────────────────────────────────────────────

    def __enter__(self) -> "Rcon":
        self.connect()
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def connect(self) -> None:
        self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        self._sock.settimeout(self.timeout)
        self._login()

    def close(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            finally:
                self._sock = None

    # ── 协议 ────────────────────────────────────────────────────────

    def _next_id(self) -> int:
        self._req_id += 1
        return self._req_id

    def _send(self, req_id: int, pkt_type: int, payload: str) -> None:
        assert self._sock is not None
        body = (
            struct.pack("<ii", req_id, pkt_type)
            + payload.encode("utf-8")
            + b"\x00\x00"
        )
        self._sock.sendall(struct.pack("<i", len(body)) + body)

    def _recv(self) -> tuple[int, int, str]:
        assert self._sock is not None
        header = self._recv_exact(4)
        (length,) = struct.unpack("<i", header)
        body = self._recv_exact(length)
        req_id, pkt_type = struct.unpack("<ii", body[:8])
        payload = body[8:-2].decode("utf-8", errors="replace")
        return req_id, pkt_type, payload

    def _recv_exact(self, n: int) -> bytes:
        assert self._sock is not None
        buf = b""
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise RconError("连接被服务端关闭")
            buf += chunk
        return buf

    def _login(self) -> None:
        req_id = self._next_id()
        self._send(req_id, _SERVERDATA_AUTH, self.password)
        resp_id, _type, _payload = self._recv()
        # 登录失败时服务端返回的请求 id 为 -1
        if resp_id == -1:
            raise RconError("RCON 认证失败（密码错误？）")

    # ── 公开 API ────────────────────────────────────────────────────

    def command(self, cmd: str) -> str:
        """执行一条服务端命令，返回其文本响应（可能为空串）。"""
        if self._sock is None:
            self.connect()
        req_id = self._next_id()
        self._send(req_id, _SERVERDATA_EXECCOMMAND, cmd)
        resp_id, _type, payload = self._recv()
        if resp_id != req_id:
            raise RconError(f"响应 id 不匹配: 期望 {req_id} 实得 {resp_id}")
        return payload


# ── 密码来源：运行时经 ssh 读一次（不落盘），或环境变量 ─────────────

_PASSWORD_CACHE: str | None = None


def get_password(ssh_target: str = "dev@127.0.0.1",
                 work_dir: str = "~/minecraft") -> str:
    """取 RCON 密码。优先环境变量 RCON_PASSWORD；否则经 ssh 读一次并缓存。

    密码只存在内存，不写入任何文件。
    """
    global _PASSWORD_CACHE
    if _PASSWORD_CACHE:
        return _PASSWORD_CACHE
    import os
    pw = os.environ.get("RCON_PASSWORD")
    if pw:
        _PASSWORD_CACHE = pw
        return pw
    import subprocess
    out = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", ssh_target,
         f'grep "rcon.password" {work_dir}/server.properties | cut -d= -f2'],
        capture_output=True, text=True, timeout=15,
    )
    pw = out.stdout.strip()
    if not pw:
        raise RconError(f"无法经 ssh 读取 RCON 密码: {out.stderr.strip()}")
    _PASSWORD_CACHE = pw
    return pw


def open_default(host: str = "127.0.0.1", port: int = 25575) -> Rcon:
    """用默认地址/密码打开一个 RCON 连接（密码自动从 ssh 或环境变量取）。"""
    return Rcon(host, port, get_password())


if __name__ == "__main__":
    # 自检：python scripts/rcon.py "list"
    import sys
    cmd = sys.argv[1] if len(sys.argv) > 1 else "list"
    with open_default() as r:
        print(r.command(cmd))
