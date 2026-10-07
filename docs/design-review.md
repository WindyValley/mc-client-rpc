# 设计审查：通用性 / 协议可扩展性 / 健壮性

- 审查日期：2026-10-07
- 审查基线：`main` @ `6badb0f`
- 审查范围：`common` 全部生产代码（`McClientRpc` / `ClientRpcService` / `protocol/*` / `client/ClientPlayerController`）、协议层单测、`docs/*`
- 验证动作：协议层单测实测全绿（`./gradlew :common:test` BUILD SUCCESSFUL，8 个用例）

## 总评

骨架方向正确：

- `RpcDispatcher` 真正与 MC 解耦：纯文本进 / 出，不知道任何 MC 类型，可纯 JUnit 测试；
- Fabric / NeoForge 平台入口极薄，业务全在 common；
- render thread 访问纪律清晰，每个 RPC 调用都有超时，不会无限挂起；
- 线程模型、输入模拟的坑在文档中记录充分。

三个系统性落差：

1. **「通用」止步于协议层**。dispatcher 支持任意结构化结果，操控层却把所有返回退化成给人看的中文字符串，框架无法被程序消费。
2. **「目标」与「错误」没有抽象**：目标只能「类型名子串匹配最近的一个」；错误一律 -32603 + 一段中文。这两者恰是一个控制框架最该通用化的地方。
3. **存在真实可达的硬伤**：服务默认绑定所有网卡（与文档写的 `127.0.0.1` 不符）；高层指令单槽，并发调用会产生永远等不到结果的请求；连接参数带有写死的环境默认值。

---

## P0 — 语义 / 安全，建议优先处理

### P0-1 实际绑定 0.0.0.0，文档却写 127.0.0.1

- 位置：`protocol/RpcServer.java:76`（`new ServerSocket(port)`，未指定 bind 地址）
- 文档：`docs/rpc-api.md:5`、`README.md` 均声称监听 `127.0.0.1:25586`
- 后果：同网段任何机器都能连上端口并操控游戏，「本地受信设施」的安全感是假的；文档与实现互相矛盾。
- 建议：bind 地址提升为显式配置，默认 loopback；确需远程连接时增加 token 校验；文档与实现同步。

### P0-2 并发高层指令互相覆盖，先发起的调用必然挂死到超时

- 位置：`client/ClientPlayerController.java:61-63`（`attackTarget` / `attackFuture` 单槽）；setup 失败直接清空攻击状态见 `:203`
- 后果：`RpcServer` 的 cached thread pool 允许并发处理请求。调用 A 的 `player.attackNearest` 仍在 `future.get(15s)` 等待时，调用 B 再次发起，`attackNearestAsync` 直接覆盖 target / future；tick 完成时只 complete B 的 future，**A 的 future 永远不会被 complete**，必然等满 15s。B 的 setup 若失败还会把 A 正在进行的攻击一并清掉。
- 建议：增加「忙」判据——已有进行中的高层操作时，后续调用立即返回业务错误，不允许静默覆盖。

### P0-3 `id` 被强制解析为 int，字符串 id 被误判为解析错误

- 位置：`protocol/RpcDispatcher.java:57`（`req.get("id").getAsInt()`）
- 后果：JSON-RPC 2.0 的 id 允许字符串（字符串 / UUID 作 id 是常见用法）。传入字符串 id 时直接抛异常，合法请求被回成 -32700 parse error，且无法回传原 id。
- 建议：id 以 `JsonElement` 原样保存、原样回传；仅在确需数值语义时按 number 解析。

---

## P1 — 代码通用性

### P1-1 `client.connect` 的 host 带有写死的环境默认值

- 位置：`client/ClientPlayerController.java:174-175`（`str(p, "host", "127.0.0.1")`）；文档同问题见 `docs/rpc-api.md:75`
- 问题：该地址是开发机所处环境的测试服务器，不属于通用工具的一部分。调用方忘记传 host 时，请求被静默导向一个与其无关的内网地址，排障成本高；默认值还会把环境事实固化进代码与文档。
- 建议：**host 按必传参数处理，不提供默认值**；缺失时返回明确的参数校验错误（对应 -32600 或业务错误码）。port 可保留 `25565` 默认值。文档删除该默认 IP。

### P1-2 操控层是 626 行上帝类，方法注册全是重复样板

- 位置：`client/ClientPlayerController.java:168-242`（`registerHandlers`）
- 问题：20 个方法注册结构完全同构——取参（`str/f/bool`）→ `submitOnClientThread` → 写超时；「如何把一个 render-thread 操作暴露成 RPC 方法」没有通用化。
- 建议：收敛为统一 helper，例如 `register(method, timeout, ctx -> ...)`，取参与提交只写一遍。

### P1-3 目标解析过窄，且子串匹配会误伤

- 位置：`client/ClientPlayerController.java:588-601`（`nearestEntity`，匹配条件在 `:590`：`getType().toString().contains(typeKey)`）
- 问题：
  - 传 `zombie` 会同时命中 zombie、husk、drowned、zombie_villager、zombified_piglin 等，调用方无法精确指定；
  - 不支持命名实体、UUID、坐标；全局只能锁定一个目标。
- 建议：抽出目标解析概念——精确类型 ID / 自定义名 / UUID / 最近邻各为一种策略，`nearestEntity` 退化为其中一种实现，而非唯一私有方法。

### P1-4 返回值全是中文字符串，调用方被迫写正则

- 位置：`client/ClientPlayerController.java` 各操控方法（如 `playerState` `:361-369`、`playerPos` `:354-359`、`listEntities` `:567-576`）；消费侧证据见 `scripts/combat_loop_test.py:183-187`（正则解析「血量 20.0/20.0」）
- 问题：协议层本支持任意对象（`GSON.toJsonTree`），业务层主动放弃。每加一个字段就要改句子格式，所有消费方的解析随之失效；中文文案同时承担了「展示」和「数据协议」两职。
- 建议：状态 / 坐标 / 实体列表返回结构化对象；人类可读描述作为其中一个字段，而非全部。

---

## P2 — 协议可扩展性

### P2-1 错误模型扁平：无自定义错误码、无 data

- 位置：`protocol/RpcDispatcher.java:79-80`（handler 一切异常兜成 -32603）
- 问题：「未找到目标 / 超出触及距离 / 未进入世界 / 已死亡 / 无权限」无法程序化区分，调用方只能匹配中文文案（如 `scripts/combat_loop_test.py` 中 `"死亡 是" in state`）。JSON-RPC 预留 -32000~-32099 作为业务错误码，`error.data` 可携带当前距离、附近目标等细节，均未使用。
- 建议：定义带稳定 code 的业务异常，dispatcher 识别并透传；预期错误返回业务码 + data。

### P2-2 `methods.list` 只给名字，代码与文档是两份真相

- 位置：`client/ClientPlayerController.java:169`；注册表结构见 `protocol/RpcDispatcher.java:25`（`Map<String, Function<JsonObject, Object>>`，不含元数据）
- 问题：自描述结果只有方法名，无参数 schema、必填项、默认值、错误码；每新增方法都要手写 `docs/rpc-api.md`，违反单一事实来源。
- 建议：趁注册表结构尚简单，升级为 `Map<String, MethodDefinition>`（handler + 参数规格 + 描述）；`methods.list` 直接产出 schema，API 文档从代码生成。

### P2-3 缺少协议演进约定

- 问题：方法直接以 `player.attack` 命名，一旦返回从字符串改为对象即为破坏性变更，当前没有版本协商或命名空间（如 `player.attack.v2`）规则。
- 建议：私有阶段可不实现机制，但应先写明演进规则，避免第一个外部使用者接入后协议被锁死。

---

## P3 — 健壮性

### P3-1 网络层无任何输入 / 资源防护

- 位置：`protocol/RpcServer.java:95-109`（`handleClient`）
- 问题：
  - `readLine()` 无长度上限，一行超大不换行内容可撑内存；
  - 未设置 `setSoTimeout`，建立连接后不发送数据可永久占用一个线程；
  - cached thread pool 无界，无最大连接数限制；
  - `PrintWriter.println` 静默吞掉对端关闭后的写错误。
- 建议：单行长度上限（如 1MB）、socket 读超时、最大连接数，超限即断开；写失败需可感知。

### P3-2 高层指令超时清理存在竞态，且用 future 充当无返回执行通道

- 位置：`client/ClientPlayerController.java:209-216`
- 问题：超时后既异步入队清理任务、又在 RPC 线程直接置 `attackTarget = null`；若 tick 恰在这一刻完成攻击，调用方收到「超时」但攻击实际已命中。清理任务使用一个不持有引用的 `new CompletableFuture<>()`，纯粹为在 render thread 执行副作用。
- 建议：提供正规的 `executeOnClient(Runnable)`（无返回、不造 future）；结果归属用令牌 / 序号判定，消除「报超时但已命中」。

### P3-3 协议子集的静默行为

- 位置：`protocol/RpcDispatcher.java`
  - `params` 传数组（by-position）时不报错，被静默当作空对象走默认值（`:75-76`）；
  - 无 id 的请求按 JSON-RPC 应为 notification，服务器不应响应；现在 id 默认 0 并照常回一行（`:53`、`:62`），严格客户端会错位；
  - 完全不校验 `jsonrpc` 字段。
- 建议：params 数组要么支持、要么明确返回 invalid request；notification 不回响应；校验 `jsonrpc == "2.0"`。
- 测试缺口：`RpcDispatcherTest.java` 现有用例覆盖快乐路径，未覆盖字符串 id、params 数组、notification、错误 data 等边界。

---

## 值得保持的设计

- 协议层与 MC 解耦，且有单测守护；
- 平台入口薄，新增加载器成本低；
- 所有调用带超时，不无限挂起；`stop()` 生命周期完整；
- 错误码使用 JSON-RPC 预留码，方向正确；
- `docs/thread-model.md`、`docs/input-injection.md` 文档质量高。

## 目标结构（方向，不含实现细节）

```
注册: register(method, 元数据{参数 schema / 错误码}, timeout, handler)
目标: byTypeId(精确) | byName | byUuid | nearest 等可插拔策略
结果: 一律结构化 JSON，人类可读描述为其中一个字段
错误: 稳定业务错误码 + data
传输: bind 地址可配（默认 loopback）+ 可选 token
      单行上限 / 读超时 / 最大连接数
参数: host 必传，不设环境默认值
```

## 建议落地顺序

1. **P0**：bind 地址默认 loopback；高层指令忙判据；id 按原样回传。
2. **P1-1 + P1-4 + P2-1**：host 必传、返回结构化、业务错误码——这三项一体，决定协议能否被程序消费，宜一次设计到位。
3. **P1-2 / P1-3**：注册样板收敛、目标解析抽象。
4. **P2-2 / P2-3**：注册元数据化与文档生成、演进规则。
5. **P3**：网络硬化、竞态清理、协议边界与测试补齐。

## 文件位置索引

| 问题 | 主要位置 |
|------|---------|
| P0-1 绑定地址 | `protocol/RpcServer.java:76`；文档 `docs/rpc-api.md:5` |
| P0-2 高层指令单槽 | `client/ClientPlayerController.java:61-63, 203` |
| P0-3 id 强转 int | `protocol/RpcDispatcher.java:57` |
| P1-1 connect 默认 IP | `client/ClientPlayerController.java:174-175`；文档 `docs/rpc-api.md:75` |
| P1-2 注册样板 | `client/ClientPlayerController.java:168-242` |
| P1-3 目标解析 | `client/ClientPlayerController.java:588-601` |
| P1-4 字符串返回 | `client/ClientPlayerController.java:354-369, 567-576`；消费侧 `scripts/combat_loop_test.py:183-187` |
| P2-1 错误模型 | `protocol/RpcDispatcher.java:79-80` |
| P2-2 方法自描述 | `client/ClientPlayerController.java:169`；`protocol/RpcDispatcher.java:25` |
| P3-1 网络防护 | `protocol/RpcServer.java:95-109` |
| P3-2 超时清理竞态 | `client/ClientPlayerController.java:209-216` |
| P3-3 协议边界 | `protocol/RpcDispatcher.java:53, 62, 75-76` |
