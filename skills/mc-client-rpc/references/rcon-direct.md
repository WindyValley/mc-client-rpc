# RCON 直连（服务器管理通道）

客户端 RPC 负责真实输入；召唤、清场、改 gamerule、停服这些「客户端做不到的 OP 操作」走服务器 RCON。

## 纯标准库 RCON 客户端

仓库 `scripts/rcon.py` 用纯 Python 标准库实现 Source RCON 协议，直连服务器 RCON 端口（MC 默认 25575），不绕 ssh、不依赖第三方库。

```bash
python scripts/rcon.py "<服务器命令>"     # 直连执行，不回显密码
```

- **密码不硬编码、不落盘**：优先读环境变量 `RCON_PASSWORD`；没有时经 ssh（`MCRPC_SSH_TARGET`）安全通道取一次、缓存在内存里；两者皆缺抛明确错误，不静默回退到任何内置地址。脚本不要把密码写进命令行历史或日志。
- **连接参数约定环境变量**，新脚本一律用这套名字、别再造项目专名变量（完整表见仓库 README）：`RCON_PASSWORD`、`MCRPC_RCON_HOST/PORT`、`MCRPC_SERVER_HOST/PORT`、`MCRPC_SSH_TARGET`、`MCRPC_SERVER_DIR/LOG`、`MCRPC_CLIENT_PROJECT`（launch_and_connect 必填，未设置要明确报错）。默认值只允许回环。

## RCON 使用要点

- **RCON 命令的执行位置是控制台/出生点，`~` 不是玩家坐标**：召唤/放块要用从 `player.pos` 解析出的绝对整数格坐标。需要以玩家为原点时用 `execute as <player> at <player> run ...`。
- **停服命令 `stop` 会立即断开 RCON 连接**，这是预期结果不是异常：
  ```python
  try:
      with open_default() as r:
          r.command("stop")
  except (RconError, OSError):
      pass  # 连接关闭是 stop 的正常结果
  ```
- **OP 生命周期**：需要 OP 的召唤/命令，临时 `op <player>` → 执行 → finally 立即 `deop`。验证玩家权限类行为时全程保持普通玩家身份。
- RCON 传带命名空间 id 的命令时，注意某些传输/解析路径对冒号、尾随字符敏感；选择器仍用完整 id（`minecraft:zombie`），这与客户端 RPC target 用裸 id（`zombie`）不同。

## 与被测系统探针的区别

RCON 发的是原版服务器命令。读**被测 mod 内部状态**（自定义字段、AI 标记等）需要使用方工程自己开调试通道，那不是本工具的一部分，端口/协议由使用方决定。真机脚本里通常是三条通道并存：客户端 RPC(真实输入) + RCON(管理) + 使用方探针(读内部状态)。
