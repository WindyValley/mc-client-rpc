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

> 约定：参数为可选时给出默认值。成功返回字符串 `result`；失败返回 `error`。

### 通用

| 方法 | 参数 | 说明 |
|------|------|------|
| `methods.list` | — | 列出所有已注册方法名 |

### 客户端导航（进世界前后均可用）

| 方法 | 参数 | 说明 |
|------|------|------|
| `client.status` | — | 当前界面 / 是否已进世界 / 是否已连服务器 / 服务器地址——自动化的「眼睛」 |
| `client.toTitle` | — | 切到主菜单，跳过无障碍引导屏（`AccessibilityOnboardingScreen`）等拦路界面 |
| `client.connect` | `host`（默认 127.0.0.1）, `port`（默认 25565）| 直连服务器，异步发起；配合 `client.status` 轮询到「已进世界」 |
| `client.disconnect` | — | 断开连接、回主菜单（测试间重置）|
| `client.quit` | — | 完全退出客户端进程（等价标题屏「退出」）|

### 玩家状态

| 方法 | 参数 | 说明 |
|------|------|------|
| `player.state` | — | 血量 / 位置 / 准星指向 / 是否在地面 / 是否死亡 |
| `player.pos` | — | 坐标 / 维度 / 血量 |
| `player.respawn` | — | 在死亡屏重生（等价点「重生」按钮）；注意重生点是世界出生点 |

### 玩家操控

| 方法 | 参数 | 说明 |
|------|------|------|
| `player.look` | `yaw`（默认0）, `pitch`（默认0）| 设置视角 |
| `player.move` | `forward`, `strafe`, `jump` | **驱动真实按键映射**，由原版 tick 自然移动并发包；持续生效，需 `player.stop` 释放 |
| `player.jump` | `down`（默认 true）| 按住 / 松开跳跃键映射 |
| `player.stop` | — | 释放全部方向 / 跳跃键，停止移动 |
| `player.moveToward` | `target`（默认 zombie）| 自动走向最近的指定生物，进入触及范围后自动停（tick 驱动）|
| `player.attack` | `target`（默认 zombie）| 对准并攻击最近的指定生物；带触及距离守卫（超距报错）|
| `player.attackNearest` | `target`（默认 zombie）| 高级指令：tick 驱动自动寻路 → 进入触及范围 → 攻击（约 15s 超时）|
| `player.command` | `cmd` | 以玩家身份执行一条斜杠命令 |
| `player.chat` | `msg` | 以玩家身份发送一条聊天消息 |

### 世界查询

| 方法 | 参数 | 说明 |
|------|------|------|
| `world.entities` | `radius`（默认 32）| 列出周围生物（类型、坐标、距离）|

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
{"jsonrpc":"2.0","id":1,"result":"已攻击 entity.minecraft.zombie (距离 2.0 格) —— 真实输入路径"}
```

错误：

```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"entity.minecraft.zombie 距离 4.1 格，超出触及范围 3.0 格"}}
```

错误码：

| code | 含义 |
|------|------|
| -32700 | 请求解析失败（非合法 JSON）|
| -32600 | 无效请求（缺 `method`）|
| -32601 | 未知方法 |
| -32603 | 业务异常（如超距、玩家未进世界）|

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

NeoForge 的 `runClient` 已预置离线参数（见 `neoforge/build.gradle`）：`--quickPlayMultiplayer 127.0.0.1:25565 --username Dev`，启动即直连测试服。

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

本服务监听 TCP 端口、**无鉴权**，是测试设施，**仅限受信任的本地网络**。任何人连上端口即可操控游戏角色。**不要部署到公网或不可信环境。**

---

## 技术栈

Minecraft 1.21.1 · Java 21 · Gradle 8.10.2 · Architectury Loom 1.7 · Architectury API 13.0.11 · NeoForge 21.1.250 · Fabric Loader 0.16.9 / Fabric API 0.116.17

## License

MIT
