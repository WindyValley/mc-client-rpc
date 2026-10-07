# 架构

mc-client-rpc 是一个 **Minecraft 客户端内的 JSON-RPC 控制框架**。它在客户端进程内监听 TCP 端口，把外部请求翻译成对客户端真实输入 API 的调用，从而驱动一个真实连入服务器的玩家。

本文描述模块划分、依赖关系与关键设计约束。面向接手维护的工程师。

## 顶层目标

自动化验证 Minecraft 行为时，需要「玩家真的在操作」，而不是服务端替玩家伪造状态：

- **服务端 RPC**：直接改血量/速度、伪造伤害来源。玩家是提线木偶，没有真实输入，验证不了「玩家挥剑打怪」的完整动作。
- **本框架**：调用客户端真实输入 API，客户端真的发出攻击包/移动包，走完整的「输入 → 网络 → 服务端响应」管线。

## 模块划分

Architectury 多加载器工程，三个 Gradle 模块：

| 模块 | 职责 | 是否依赖 MC |
|------|------|------------|
| `common` | 全部业务逻辑：网络、协议、玩家操控 | 是 |
| `fabric` | Fabric 平台入口（仅转发到 common）| 是 |
| `neoforge` | NeoForge 平台入口（仅转发到 common）| 是 |

`settings.gradle` 依次 include 三个模块。平台模块不含业务逻辑，只做生命周期转发——这是刻意设计，保证新增一个加载器时只需加一个薄入口。

## 分层（common 模块内部）

```
common/src/main/java/top/windyvalley/mcclientrpc/
│
├── McClientRpc.java            公共入口：启动服务 + 注册客户端 tick
│
├── ClientRpcService.java       装配层：组装 网络/协议/操控器
│
├── protocol/
│   ├─ RpcServer.java         网络层：TCP accept + 每连接读行/写行
│   └─ RpcDispatcher.java     协议层：JSON-RPC 解析/路由/错误包装（与 MC 解耦）
│
└── client/
    └─ ClientPlayerController.java   操控层：任务队列 + 真实输入调用 + tick 驱动
```

依赖方向（自外向内，单向）：

```
平台入口 (fabric / neoforge)
        │ 调用
        ▼
McClientRpc.initClient()
        │ 创建并启动
        ▼
ClientRpcService ── 组装 ──> RpcServer ── 委托 ──> RpcDispatcher
        │                                              ▲
        └── 创建 ──> ClientPlayerController ── 注册 handlers ┘
```

要点：
- `RpcDispatcher` **不知道任何 MC 类型**，handler 以 `Function<JsonObject, Object>` 形式注册。因此协议层可纯 JUnit 测试，无需 MC runtime。
- `RpcServer` 只管网络字节，不理解业务。
- 所有「动 MC 状态」的代码集中在 `ClientPlayerController`，且只在 render thread 执行。

## 关键类职责

### `McClientRpc`（公共入口）
- 定义 `MOD_ID = "mcclientrpc"`。
- `initClient()`：创建 `ClientRpcService` 并 `start()`；注册 `ClientTickEvent.CLIENT_POST`，每帧调用 `service.tick()`。
- 由 Fabric 的 `ClientModInitializer` 和 NeoForge 的 `@Mod(dist=CLIENT)` 调用。

### `ClientRpcService`（装配）
- 持有一个 `ClientPlayerController`、一个 `RpcDispatcher`、一个 `RpcServer`。
- `start()`：先让控制器把 handlers 注册进 dispatcher，再用默认端口 `25586` 起 server。
- `tick()`：转发给控制器，由 render thread 驱动排队任务与持续移动/攻击。

### `RpcServer`（网络）
- 独占 daemon **accept 线程** + `newCachedThreadPool` 处理每客户端连接。
- 每个连接按行读取，把每行交给 dispatcher，回写一行响应。
- 端口绑定失败/连接异常仅记录，不崩溃。

### `RpcDispatcher`（协议）
- `register(method, handler)`：启动期注册，之后只读（线程安全）。
- `handle(line)`：解析 → 路由 → 执行 handler → 包装成功/错误响应。

### `ClientPlayerController`（操控）
- 维护一个并发任务队列（`ConcurrentLinkedQueue`）。
- RPC 线程把动作入队并阻塞等结果；客户端 tick 每帧在 render thread 取出执行。
- 维护跨多帧的持续目标（`moveTarget` / `attackTarget`）及其 future。

## 端口约定

| 端口 | 归属 | 用途 |
|------|------|------|
| **25586** | 本框架（客户端）| 客户端 JSON-RPC 控制 |
| 25585 | example-mod 服务端验证设施 | 读 mod 内部状态（历史参照）|
| 25575 | Minecraft 服务端 RCON | 服务端管理命令 |

端口号刻意区分，避免同机多服务混淆。

## 设计约束（贯穿全工程）

1. **MC 客户端状态只能在 render thread 访问**。所有来自网络线程的操控必须经任务队列切到客户端 tick 执行。详见 [thread-model.md](thread-model.md)。
2. **不在 render thread 上阻塞**。需要跨多帧的高级指令，只在 render thread 做瞬间设置，RPC 线程在外层等待。
3. **真实输入优先**。要让服务端看到真实动作，必须驱动原版输入链路（按键映射），不直改输入字段、不直调实体方法。详见 [input-injection.md](input-injection.md)。
4. **协议与 MC 解耦**。协议层保持纯文本进出，便于单测与复用。

## 非目标（明确不做）

- 不做服务端权威逻辑、不伪造游戏状态。
- 不把 OS 级键鼠模拟（系统窗口消息）放进 mod——平台相关代码放外部测试脚本侧。
- 不内置鉴权/加密：它是受信本地网络下的测试设施。
