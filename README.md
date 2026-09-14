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

### 进入世界前（自动化导航）

| 方法 | 参数 | 说明 |
|------|------|------|
| `client.status` | — | 当前界面/是否进世界/是否连服务器——自动化的「眼睛」 |
| `client.toTitle` | — | 强制切到主菜单，跳过引导屏（AccessibilityOnboardingScreen 等） |
| `client.connect` | `host`, `port` | 主动直连服务器（异步，配合 client.status 轮询进世界） |
| `client.disconnect` | — | 断开连接、回主菜单（测试间重置） |

### 进入世界后（玩家操控）

| 方法 | 参数 | 说明 |
|------|------|------|
| `methods.list` | — | 列出所有已注册方法 |
| `player.pos` | — | 玩家位置/维度/血量 |
| `player.state` | — | 玩家状态（血量/位置/准星/地面/是否死亡） |
| `player.respawn` | — | 死亡屏重生（点 DeathScreen 重生按钮的效果） |
| `player.attack` | `target` | 视角对准并攻击最近的指定生物；带触及守卫（超距报错）+ 命中判定 |
| `player.look` | `yaw`, `pitch` | 设置视角 |
| `player.moveToward` | `target` | 自动走向最近目标直到进入触及范围（tick 驱动，到达自动停） |
| `player.stop` | — | 停止移动 |
| `player.move` | `forward`, `strafe`, `jump` | 直接设移动输入（注意：会被键盘 tick 覆盖，见下文） |
| `world.entities` | `radius` | 列出周围生物 |

协议：JSON-RPC 2.0，每行一个请求。成功返回 `result`，失败返回 `error:{code,message}`
（-32700 解析失败 / -32600 缺 method / -32601 未知方法 / -32603 业务异常）。

### 已踩过的坑（实现要点）

- **拦路界面**：dev 环境启动先弹 `AccessibilityOnboardingScreen`（无障碍引导屏），挡在
  TitleScreen 前，导致 `--quickPlayMultiplayer` 失效、无法连接。必须先 `client.toTitle` 跳过。
  死亡后 `DeathScreen` 同样挡操控，需 `player.respawn`。
- **移动模拟**：直接改 `player.input.forwardImpulse` 字段无效——`KeyboardInput.tick()` 每帧
  从键盘覆盖。`moveToward` 改用 `setDeltaMovement` 在 tick 里朝目标推进（真实位移包，
  非瞬移），到达触及距离自动停。若需更拟真的走路（脚步声/动画），可上 OS 级按键模拟
  （系统窗口消息发 W/A/S/D，让 KeyboardInput 当真实键盘）——建议放测试脚本侧而非 mod 内。
- **触及距离**：`player.attack` 超出生存触及范围（约 3 格，`entityInteractionRange()`）时
  服务端判挥空不掉血。已加守卫，超距直接报错提示先 moveToward 走近。
- **客户端血量判定不可靠**：`gameMode.attack` 发包后客户端本地血量不即时更新，
  「命中判定」应以服务端权威结果为准（如玩家被反击掉血/死亡）。

### 真机验证结论（2026-09-14）

完整闭环已跑通：启动 → `client.status`（引导屏）→ `client.toTitle` → `client.connect`
→ 进世界 → `player.respawn` → `player.moveToward`（10.5 格→2.0 格）→ `player.attack`
→ **掠夺者反击将玩家打死**（血量 20→0）。证明客户端真实输入攻击命中 + 中立化生物
反击链正常，全程无人工点界面。

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
