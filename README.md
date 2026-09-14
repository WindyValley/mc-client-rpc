# mc-client-rpc

Minecraft 客户端 JSON-RPC 自动化测试框架（Architectury 双端：NeoForge + Fabric）。

## 它解决什么问题

真机行为自动化验证需要「玩家真的在操作」，而不是服务端伪造状态。

- **服务端 RPC**（如 example-mod 的验证设施）：服务端直接改血量/速度、伪造伤害来源——玩家实体是提线木偶，没有真实输入。能验证「伤害→反击」链，但验证不了「玩家挥剑打怪」这个完整动作。
- **本框架（客户端 RPC）**：在客户端进程内开 JSON-RPC 服务，调用客户端真实输入 API（`gameMode.attack`、`player.input`、`setYRot/setXRot`），让客户端**真的发出攻击/移动包**，走完整的 输入→网络→服务端 管线。

## 工作原理

```
外部测试脚本 ──TCP──> 客户端 RPC 服务(25586)
                          │
                          ▼ (任务入队，RPC 线程阻塞等结果)
                  ClientTickEvent.CLIENT_POST (render thread)
                          │
                          ▼ 每帧取出任务，在 render thread 执行
                  Minecraft.getInstance() 真实输入 API
                          │
                          ▼ 结果写回 future → RPC 线程 → 响应
```

**关键约束**：Minecraft 客户端状态（玩家实体、世界、攻击）只能在 render thread 安全访问。RPC 请求来自网络线程，必须经「任务队列 + tick 消费」切到 render thread，否则跨线程访问会崩溃或状态错乱。

## RPC 方法

| 方法 | 参数 | 说明 |
|------|------|------|
| `methods.list` | — | 列出所有已注册方法 |
| `player.pos` | — | 玩家位置/维度/血量 |
| `player.state` | — | 玩家状态（血量/位置/准星指向/是否地面） |
| `player.attack` | `target` | 视角对准并攻击最近的指定生物（真实攻击管线） |
| `player.look` | `yaw`, `pitch` | 设置视角 |
| `player.move` | `forward`, `strafe`, `jump` | 设置移动输入（持续，需后续指令清零） |
| `world.entities` | `radius` | 列出周围生物 |

协议：JSON-RPC 2.0，每行一个请求。成功返回 `result`，失败返回 `error:{code,message}`
（-32700 解析失败 / -32600 缺 method / -32601 未知方法 / -32603 业务异常）。

## 调用示例

```bash
echo '{"jsonrpc":"2.0","method":"player.attack","params":{"target":"zombie"},"id":1}' \
  | nc 127.0.0.1 25586
# => {"jsonrpc":"2.0","id":1,"result":"已攻击 entity.minecraft.zombie (距离 3.2 格) —— 真实输入路径"}
```

## 构建与运行

```bash
./gradlew build          # 双端 jar
./gradlew :neoforge:runClient   # NeoForge 客户端（已配离线直连 127.0.0.1）
./gradlew :fabric:runClient     # Fabric 客户端
```

`runClient` 已预置 `--quickPlayMultiplayer 127.0.0.1:25565 --username Dev`（离线直连测试服）。

## 测试

- **协议层**（`protocol/RpcDispatcher`）：纯 JUnit，与 MC 解耦，8 条单测覆盖解析/路由/错误码/异常隔离。
- **操控层**（`client/ClientPlayerController`）：依赖 MC runtime（玩家实体、世界），纯 JUnit 造不出来，移交真机验证。

## ⚠️ 安全

本框架监听 TCP 端口、无鉴权，**是测试设施，仅限受信任的本地网络**。不要部署到公网或不可信环境——任何人连上端口即可操控你的游戏角色。

## 技术栈

MC 1.21.1 / Java 21 / Architectury Loom 1.7（Gradle 8.10.2）/ NeoForge 21.1.250 / Fabric Loader 0.16.9。

## License

MIT
