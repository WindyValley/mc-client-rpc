# mc-client-rpc

Minecraft 客户端 JSON-RPC 控制框架（Architectury 双端：NeoForge + Fabric），用于**真机行为的自动化操控与验证**。

- **MC 版本**：1.21.1（Java 21）
- **框架版本**：0.1.0
- **modid**：`mcclientrpc`
- **默认监听**：TCP `127.0.0.1:25586`
- **协议**：JSON-RPC 2.0（每行一个请求）

---

## 功能定位

自动化验证 Minecraft 行为时，常常需要「玩家真的在操作」，而不是服务端替玩家伪造状态。两种做法的区别：

| | 服务端 RPC | **本框架（客户端 RPC）** |
|---|---|---|
| 怎么生效 | 服务端直接改血量/速度、伪造伤害来源 | 调用客户端真实输入 API |
| 玩家实体 | 提线木偶，没有真实输入 | 真的按方向/攻击/跳跃 |
| 网络 | 不走客户端输入链 | 客户端真的发出攻击包/移动包 |
| 能验证 | 「伤害→反击」 | 完整的「输入→发包→服务端响应」管线 |

mc-client-rpc 在 **Minecraft 客户端进程内**开一个 JSON-RPC 服务，外部测试脚本（如 Python）通过它驱动一个真实连入服务器的玩家：走位、攻击、跳跃、转头、发命令/聊天，并读取玩家与周围世界状态。

它最初是为验证 [example-mod](https://github.com/WindyValley/example-mod) 而抽出的通用工具，但本身与具体被测 mod 无关——任何需要「自动化真实玩家」的场景都可用。

---

## 工程文档（交接）

面向维护与交接的工程学文档：

- [交接总览](docs/handoff.md)
- [架构](docs/architecture.md)
- [线程模型](docs/thread-model.md)
- [输入模拟方案](docs/input-injection.md)
- [RPC API 参考](docs/rpc-api.md)
- [测试策略](docs/testing.md)

---

## 工作原理

```
外部测试脚本 ──TCP JSON-RPC──> 客户端 RPC 服务(25586)
                                  │
                                  ▼  任务入队，RPC 线程阻塞等结果
                          ClientTickEvent.CLIENT_POST（render thread）
                                  │
                                  ▼  每帧取任务、在 render thread 执行
                          Minecraft 真实输入/查询 API
                                  │
                                  ▼  结果写回 future
                              JSON-RPC 响应
```

**核心约束**：Minecraft 客户端状态（玩家实体、世界、攻击）只能在 **render thread** 安全访问。RPC 请求来自网络线程，必须经「任务队列 + 客户端 tick 消费」切到 render thread 执行。因此：

- 普通操控走 `submitOnClientThread`：提交任务，render thread 下一帧执行，RPC 线程等 `future`；
- **绝不能在 render thread 上阻塞等 future**——会死锁（tick 永远没机会跑）；
- `player.attackNearest` 这类需要跨多帧的高级指令：只在 render thread 做一个「瞬间设目标」的动作，后续由 tick 驱动寻路/攻击，RPC 线程在外侧等结果。

---

## 支持的 RPC 方法

共 **29 个方法**，通过 newline 分隔的 JSON-RPC 2.0 调用，运行时可用 `methods.list` 自描述获取参数 schema。

按能力分组（括号内为方法名）：

- **客户端导航**：查状态 / 回主菜单 / 连服 / 断开 / 退出（`client.status`、`client.toTitle`、`client.connect`、`client.disconnect`、`client.quit`）
- **单机存档**：列举 / 按参数创建并进入 / 进入已有存档，降级世界自动备份后加载（`world.list`、`world.create`、`world.enter`）
- **玩家状态**：坐标血量 / 完整状态 / 重生（`player.pos`、`player.state`、`player.respawn`）
- **玩家操控**：视角 / 真实按键移动 / 跳跃 / 停止 / 走向目标（`player.look`、`player.move`、`player.jump`、`player.stop`、`player.moveToward`）
- **战斗与交互**：攻击 / 寻路攻击 / 右键实体开交易（`player.attack`、`player.attackNearest`、`player.interact`）
- **命令聊天**：斜杠命令 / 普通聊天（`player.command`、`player.chat`）
- **方块交互**：右键床睡觉 / 对指定面放方块（`player.useBed`、`player.placeBlock`）
- **世界查询**：列周围生物（`world.entities`）
- **村民交易 GUI**：读报价 / 选报价 / 成交 / 关窗（`trade.status`、`trade.select`、`trade.take`、`trade.close`）
- **系统**：`methods.list` 自描述

完整参数表、结构化返回字段、错误码、每方法超时与客户端状态机见
**[RPC API 参考](docs/rpc-api.md)**（单一契约，勿在 README 复制逐方法细节以免过时）。

---

## 输入模拟：为什么驱动「按键映射」

这是本框架踩坑后确定的关键设计。

- ❌ **直接改 `input.forwardImpulse` / `input.jumping`**：`KeyboardInput.tick()` 下一帧会按键盘真实状态（无按键）覆盖，实测玩家原地不动、跳不起来。
- ❌ **客户端线程直接调 `jumpFromGround()` 等实体方法**：调用发生在正常 tick 循环外，客户端只做本地预测、**不发位置包**，服务端从未看到这次动作。
- ✅ **驱动按键映射**：`player.move` / `player.jump` 设置 `Minecraft.options` 里的 `keyUp/keyDown/keyLeft/keyRight/keyJump` 的按下状态（`KeyMapping.setDown`，public）。后续完全走原版链路——键盘 tick 读到「已按下」→ 玩家 AI 移动/起跳 → 正常发包；服务端看到的是真实动作。实测移动约 **4.5 格/秒**。

代价与注意：
- 按键是「持续状态」，脚本结束或换动作前必须 `player.stop`（或对应键 `down=false`）释放，否则角色会一直走/跳；
- OS 级真实键鼠模拟（给游戏窗口发系统消息）真实度最高，但属平台相关代码，按架构约定**放在外部脚本侧，不进本 mod**。

---

## 协议细节

请求（一行 JSON，以换行结束）：

```json
{"jsonrpc":"2.0","method":"player.attack","params":{"target":"zombie"},"id":1}
```

成功：

```json
{"jsonrpc":"2.0","id":1,"result":{"target":"minecraft:zombie","distance":2.0,"reach":3.0,"hpBefore":20.0,"hpAfter":17.0,"hit":true}}
```

错误（结构化细节在 `error.data`）：

```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32003,"message":"目标超出触及距离，需先走近","data":{"distance":4.1,"reach":3.0}}}
```

错误码与协议边界（notification、params 必须为对象、不支持批量等）详见 [RPC API 参考](docs/rpc-api.md)。

| code | 含义 |
|------|------|
| -32700 | 请求解析失败（非合法 JSON / 批量数组）|
| -32600 | 无效请求（缺 `method`、jsonrpc 版本不符、params 非对象）|
| -32601 | 未知方法 |
| -32602 | 无效参数（必传缺失 / 枚举非法）|
| -32000 | 超时（操作未在限定时间内完成）|
| -32001 | 未就绪（未进世界 / 被界面拦截 / 未开交易屏）|
| -32002 | 目标或存档不存在 |
| -32003 | 超出触及范围（`data` 含 distance/reach）|
| -32004 | 操作忙（已有同类高级会话在执行）|
| -32603 | 其他内部异常 |

> -32000 ~ -32099 为本框架业务错误码区间。

最小调用：

```bash
echo '{"jsonrpc":"2.0","method":"client.status","id":1}' | nc 127.0.0.1 25586
```

Python 调用示例见 [`scripts/combat_loop_test.py`](scripts/combat_loop_test.py) 中的 `rpc()`。

---

## 构建与运行

```bash
./gradlew build                 # 构建双端 jar（产物在各平台 build/libs/）
./gradlew test                # 协议层单测
./gradlew :neoforge:runClient  # 启动 NeoForge 客户端
./gradlew :fabric:runClient    # 启动 Fabric 客户端
```

`runClient` 启动后停在标题屏（离线用户名 `Dev`，分辨率 854×480），由外部脚本通过 `client.connect` 主动连接服务器。

**作为附加 mod 使用**：本框架是一个独立 mod。把构建出的 `mc-client-rpc-0.1.0.jar` 放进被测客户端的 `mods/` 目录，从客户端入口启动后即监听 25586。

---

## 配套脚本（`scripts/`）

测试驱动相关的通用工具脚本：

| 脚本 | 作用 |
|------|------|
| `combat_loop_test.py` | RPC 客户端封装 + 战斗闭环模板（走近→攻击→验证反击）|
| `rcon.py` | 纯标准库 Source RCON 客户端（直连服务器发管理命令）|
| `launch_and_connect.py` | 启动客户端 → 等标题屏稳定 → 连接服务器 |
| `kill_mc_java.py` | 清理残留 MC 客户端 java 进程（保留 Gradle daemon）|
| `deploy_mod_jar.py` | 构建并部署 example-mod 到测试服（示例用途）|

一次性/调试脚本放 `scripts/_temp/`（已 git 忽略）。

---

## 测试

- **协议层**（`common/.../protocol/`）：纯 JUnit，与 MC 解耦，覆盖 JSON-RPC 解析 / 路由 / 错误码 / 异常隔离。
- **操控层**（`common/.../client/ClientPlayerController`）：依赖真实 MC runtime（玩家实体、世界、网络），纯单测造不出来，由真机自动化脚本验证。

本框架已在真机上驱动验证了 example-mod 的多类生物（近战 / 远程 / Brain 系），攻击、走位、跳跃、示警、脱战等路径均实际跑通。

---

## ⚠️ 安全

- 服务**默认只绑定回环地址 `127.0.0.1`**，只有本机程序能连；无鉴权。
- 单行请求上限 1MB、读超时 30s、最多 16 个并发连接，超限的连接会被拒绝或断开。
- 它仍是**测试设施**：任何能连上端口的本机程序都能操控游戏角色。不要把端口暴露到公网或不可信网络；确需远程连时，应通过显式配置改绑地址并自行加网络层防护。

---

## 技术栈

Minecraft 1.21.1 · Java 21 · Gradle 8.10.2 · Architectury Loom 1.7 · Architectury API 13.0.11 · NeoForge 21.1.250 · Fabric Loader 0.16.9 / Fabric API 0.116.17

## License

MIT
