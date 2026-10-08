# RPC API 参考

完整的 JSON-RPC 2.0 方法清单、参数、返回与错误码。**协议的单一事实来源是代码注册的方法元数据**，运行时调用 `methods.list` 可获取与本文一致的自描述 schema。

- **传输**：TCP，默认 `127.0.0.1:25586`（只绑回环地址）
- **编码**：UTF-8
- **帧格式**：每行一个 JSON 请求（`\n` 结束），每行一个响应
- **协议版本**：JSON-RPC 2.0
- **限制**：单行请求 ≤ 1MB；读超时 30s；最多 16 个并发连接

## 请求与响应

请求：

```json
{"jsonrpc":"2.0","method":"player.attack","params":{"target":"minecraft:zombie"},"id":1}
```

成功响应（result 为结构化对象）：

```json
{"jsonrpc":"2.0","id":1,"result":{"target":"minecraft:zombie","distance":2.0,"reach":3.0,"hpBefore":20.0,"hpAfter":17.0,"hit":true}}
```

错误响应（可携带结构化 `data`）：

```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32003,"message":"目标超出触及距离，需先走近","data":{"distance":4.1,"reach":3.0}}}
```

## Notification

请求不带 `id`（或 `id` 为 null）时按 JSON-RPC notification 处理：服务器执行方法但**不回任何内容**，即使方法出错。需要响应时务必携带 id。id 可以是整数或字符串，响应原样回传。

## 错误码

| code | 含义 | 触发条件 |
|------|------|---------|
| -32700 | Parse error | 请求非合法 JSON / 顶层不是对象 |
| -32600 | Invalid Request | 缺 `method`、`jsonrpc` 非 "2.0"、`params` 不是对象 |
| -32601 | Method not found | 方法未注册 |
| -32602 | Invalid Params | 必传参数缺失 / 空白 |
| -32000 | Timeout | 操作在限定时间内未完成 |
| -32001 | Not Ready | 玩家未进入世界 |
| -32002 | Target Not Found | 半径内无匹配目标 |
| -32003 | Out of Reach | 目标超出触及距离 |
| -32004 | Busy | 已有进行中的高层操作 |
| -32603 | Internal Error | 其他内部异常 |

## 参数取值约定

- `params` 缺省时按空对象处理，使用各参数默认值。
- 目标类型参数 `target` 使用实体注册 ID（`minecraft:zombie`）；省略命名空间时默认补 `minecraft:`，故 `"zombie"` 等价于 `"minecraft:zombie"`。
- 匹配为**精确匹配**，`zombie` 不会命中 husk（尸壳）、drowned（溺尸）等。

---

## 通用

### `methods.list`
列出所有已注册方法及其参数 schema。
- 参数：无
- 返回：`{"count": 19, "methods": [{"name": ..., "description": ..., "params": [{"name","type","required",["default"],"description"}]}, ...]}`

---

## 客户端导航（进世界前后均可用）

### `client.status`
返回当前客户端状态，自动化脚本据此判断能否发 `player.*`。
- 返回字段：
  - `screen`：当前 Screen 类名（`null` 表示游戏中无界面）
  - `inWorld`：玩家与关卡均非空
  - `connected`：连接存在且仍连接
  - `server`：当前服务器地址（如 `127.0.0.1:25565`），无则 null

### `client.toTitle`
无条件切到 `TitleScreen`，跳过无障碍引导屏（`AccessibilityOnboardingScreen`）等拦路界面。
- 返回：`已从 <原界面> 切到 TitleScreen`

### `client.connect`
走官方 `ConnectScreen.startConnecting` 路径直连服务器（等价在多人列表点加入）。连接异步发起，进世界需数秒，用 `client.status` 轮询。
- 参数：

| 名称 | 类型 | 必传 | 默认 |
|------|------|------|------|
| `host` | string | 是 | — |
| `port` | integer | 否 | `25565` |

- host 缺失 / 空白返回 -32602。连接前会自动切到主菜单。

### `client.disconnect`
断开连接、回到 `TitleScreen`。
- 返回：`已断开，回主菜单`（本不在世界时返回 `本就不在世界中`）

### `client.quit`
完全退出客户端进程（等价标题屏「退出」）。
- 立即返回：`客户端将在 500ms 后退出`；延迟 500ms（留出响应发送时间）在 render thread 调 `Minecraft.destroy()`，并以 `halt(0)` 兜底。
- 本方法不走任务队列（destroy 会终止 JVM，阻塞等 future 永远等不到）。

---

## 玩家状态（进入世界后）

### `player.pos`
- 返回字段：`name`、`x`、`y`、`z`（保留 1 位小数）、`dimension`（如 `minecraft:the_nether`）、`health`、`maxHealth`

### `player.state`
- 返回字段：`name`、`health`、`maxHealth`、`x`、`y`、`z`、`hitResult`（`MISS`/`BLOCK`/`ENTITY`）、`onGround`、`dead`

### `player.respawn`
等价点死亡屏「重生」按钮，发 `ServerboundClientCommandPacket`。
- 玩家未死亡时返回 `玩家未死亡，无需重生`；成功返回 `已重生`。
- 注意：重生点是世界出生点，之后通常需重新定位到目标位置。

---

## 玩家操控

### `player.look`
设置视角。
- 参数：`yaw`（float，默认 0）、`pitch`（float，默认 0）
- 返回：`视角已设为 yaw=.. pitch=..`

### `player.move`
按下/释放方向键映射，由原版 tick 驱动真实移动并发包。**持续生效**，需 `player.stop` 释放。
- 参数：

| 名称 | 类型 | 默认 |
|------|------|------|
| `forward` | float | `0`（>0 W，<0 S）|
| `strafe` | float | `0`（>0 A，<0 D）|
| `jump` | boolean | `false` |

- 返回：`移动键已设 forward=.. strafe=.. jump=..`

### `player.jump`
按住/松开跳跃键映射。
- 参数：`down`（boolean，默认 true）
- 返回：`跳跃键 按下/释放`

### `player.stop`
释放全部方向 / 跳跃键并清除持续移动目标。
- 返回：`移动已停止`

### `player.moveToward`
设置持续移动目标，由 tick 每帧朝最近的指定生物前进并瞄准，进入触及范围后自动停。
- 参数：`target`（string，默认 `zombie`，精确匹配）
- 附近无目标返回 -32002；已在触及范围返回相应提示。
- 返回：`开始走向 <type>（当前 .. 格，触及 .. 格）`

### `player.attack`
对准并攻击最近的指定生物，走真实攻击管线发攻击包并挥臂。
- 参数：`target`（string，默认 `zombie`，精确匹配）
- 返回字段：

| 字段 | 含义 |
|------|------|
| `target` | 目标注册 ID |
| `distance` | 攻击时距离 |
| `reach` | 玩家触及距离（生存约 3 格）|
| `hpBefore` / `hpAfter` | 攻击前后目标血量（非 LivingEntity 为 null）|
| `hit` | 血量是否下降（命中判定，布尔）|

- 无目标返回 -32002；超距返回 -32003（data 含 distance/reach）。
- 注意：血量是客户端即时预测值；服务端权威血量可能稍后才同步。

### `player.attackNearest`
高级指令：tick 驱动自动寻路 → 进入触及范围 → 攻击。
- 参数：`target`（string，默认 `zombie`，精确匹配）
- 成功时返回与 `player.attack` 相同的结构化对象。
- 15s 未完成返回 -32000 并清理自己的会话；无目标返回 -32002；已有进行中的操作返回 -32004。

### `player.command`
以玩家身份发送一条斜杠命令（等价聊天框输入 `/xxx` 回车），走真实聊天命令包。
- 参数：`cmd`（string，**必传**，不带前导 `/`，带了会自动去掉）
- 可执行性取决于玩家权限；需要 OP 时测试脚本可先用 RCON 授权。
- 返回：`已发送指令 /<cmd>`

### `player.chat`
以玩家身份发送一条普通聊天，走真实聊天包。
- 参数：`msg`（string，**必传**）
- 返回：`已发送聊天: <msg>`

---

## 世界查询

### `world.entities`
列出周围指定半径内的生物。
- 参数：`radius`（float，默认 32）
- 返回：`{"radius": 32.0, "count": N, "entities": [{"type","x","y","z","distance"}, ...]}`
- `type` 为注册 ID（`minecraft:zombie`），可直接作为 `player.attack` / `player.moveToward` 的 `target` 参数。

---

## 最小调用示例

bash：

```bash
echo '{"jsonrpc":"2.0","method":"client.status","id":1}' | nc 127.0.0.1 25586
```

Python：
- `scripts/real_device_test.py` —— 分阶段真机验证（导航 / 连接 / 状态 / 攻击 / 移动 / 并发 BUSY）
- `scripts/combat_loop_test.py` —— 战斗闭环模板（走近 → 攻击 → 验证反击）
