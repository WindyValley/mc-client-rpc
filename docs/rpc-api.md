# RPC API 参考

完整的 JSON-RPC 2.0 方法清单、参数、返回与错误码。内容以代码注册为准。

- **传输**：TCP，默认 `127.0.0.1:25586`
- **编码**：UTF-8
- **帧格式**：每行一个 JSON 请求（`\n` 结束），每行一个响应
- **协议版本**：JSON-RPC 2.0

## 请求与响应

请求：

```json
{"jsonrpc":"2.0","method":"player.attack","params":{"target":"zombie"},"id":1}
```

成功响应（result 为字符串）：

```json
{"jsonrpc":"2.0","id":1,"result":"已攻击 entity.minecraft.zombie (距离 2.0 格) —— 真实输入路径"}
```

错误响应：

```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"..."}}
```

## 错误码

| code | 含义 | 触发条件 |
|------|------|---------|
| -32700 | Parse error | 请求非合法 JSON / 顶层不是对象 |
| -32600 | Invalid Request | 缺少 `method` 字段 |
| -32601 | Method not found | 方法未注册 |
| -32603 | Internal error | handler 抛异常（超距、玩家未进世界等）|

## 参数取值约定

- `params` 缺省时按空对象处理，使用各参数默认值。
- 布尔、数值参数在未提供时使用下表「默认」列。

---

## 通用

### `methods.list`
列出所有已注册方法名（逗号 + 空格分隔）。
- 参数：无

---

## 客户端导航

### `client.status`
返回当前客户端状态字符串：`界面=... | 已进世界=... | 已连服务器=... | 服务器=...`。自动化脚本据此判断能否发 `player.*`。

字段：
- `界面`：当前 Screen 类名；游戏中为 `无界面(游戏中)`；
- `已进世界`：玩家与关卡均非空；
- `已连服务器`：连接存在且仍连接；
- `服务器`：当前服务器地址，无则 `无`。

### `client.toTitle`
无条件切到 `TitleScreen`，跳过无障碍引导屏（`AccessibilityOnboardingScreen`）等拦路界面。
- 返回：`已从 <原界面> 切到 TitleScreen`

### `client.connect`
走官方 `ConnectScreen.startConnecting` 路径直连服务器（等价在多人列表点加入）。连接异步发起，进世界需数秒，用 `client.status` 轮询。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `host` | string | `127.0.0.1` |
| `port` | int | `25565` |

- 若玩家已在世界，返回错误提示先 `client.disconnect`。

### `client.disconnect`
断开连接、回到 `TitleScreen`。
- 返回：`已断开，回主菜单`（本不在世界时返回相应提示）

### `client.quit`
完全退出客户端进程（等价标题屏「退出」）。
- 立即返回：`客户端将在 500ms 后退出`；之后延迟 500ms（留出响应发送时间）在 render thread 调 `Minecraft.destroy()`，并以 `halt(0)` 兜底。

---

## 玩家状态

### `player.state`
返回：`玩家 <名> | 血量 x/y | 位置 (x,y,z) | 准星指向 <类型> | 地面 是/否 | 死亡 否/是(需respawn)`。

### `player.pos`
返回：`玩家 <名> 位于 (x, y, z) 维度 <id> 血量 x/y`。

### `player.respawn`
等价点死亡屏「重生」按钮，发重生客户端命令。
- 玩家未死亡时返回 `玩家未死亡，无需重生`。
- 注意：重生点是世界出生点，之后通常需重新定位到目标位置。

---

## 玩家操控

### `player.look`
设置视角。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `yaw` | float | `0` |
| `pitch` | float | `0` |

### `player.move`
按下/释放方向键映射，由原版 tick 驱动真实移动并发包。**持续生效**，需 `player.stop` 释放。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `forward` | float | `0`（>0 W，<0 S）|
| `strafe` | float | `0`（>0 A，<0 D）|
| `jump` | boolean | `false` |

- 返回：`移动键已设 forward=.. strafe=.. jump=..（原版 tick 驱动发包，需 player.stop 释放）`

### `player.jump`
按住/松开跳跃键映射。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `down` | boolean | `true` |

### `player.stop`
释放全部方向 / 跳跃键，停止持续移动。
- 返回：`移动已停止`

### `player.moveToward`
设置持续移动目标，由 tick 每帧朝最近的指定生物前进并瞄准，进入触及范围后自动停。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `target` | string | `zombie` |

- 附近 32 格无目标时返回错误。
- 已在触及范围内返回相应提示，无需移动。

### `player.attack`
对准并攻击最近的指定生物，走真实攻击管线发攻击包并挥臂。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `target` | string | `zombie`（按类型名子串匹配）|

- 带触及距离守卫：距离 > `entityInteractionRange()`（生存约 3 格）时返回错误，提示先走近。
- 返回示例：`已攻击 entity.minecraft.zombie (距离 2.0 格) —— 真实输入路径 血量 20.0→19.0 (已命中)`。
- 注意：返回串里的血量是客户端即时预测值；服务端权威血量可能稍后才同步。

### `player.attackNearest`
高级指令：tick 驱动自动寻路 → 进入触及范围 → 攻击。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `target` | string | `zombie` |

- 15 秒内未完成返回 `错误: 攻击超时` 并清理状态；附近无目标返回相应错误。

### `player.command`
以玩家身份发送一条斜杠命令（等价聊天框输入 `/xxx` 回车），走真实聊天命令包。可执行性取决于玩家权限。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `cmd` | string | 空 |

- `cmd` 不含前导 `/`（带了会自动去掉）。
- 普通玩家只能执行其权限允许的命令；需要 OP 时测试脚本可先用 RCON 授权。

### `player.chat`
以玩家身份发送一条普通聊天，走真实聊天包。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `msg` | string | 空 |

---

## 世界查询

### `world.entities`
列出周围指定半径内的生物：类型、坐标、距离。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `radius` | float | `32` |

---

## 最小调用示例

bash：

```bash
echo '{"jsonrpc":"2.0","method":"client.status","id":1}' | nc 127.0.0.1 25586
```

Python 封装可参考 `scripts/combat_loop_test.py` 中的 `rpc()`。
