# 线程模型

线程安全是本框架最关键、也最容易踩坑的部分。本文讲清有哪些线程、为什么必须切换、正确模式与典型死锁。

## 涉及的线程

| 线程 | 数量 | 职责 |
|------|------|------|
| **网络 accept 线程**（`mcrpc-accept`，daemon）| 1 | `ServerSocket.accept()`，把新连接交给线程池 |
| **客户端处理线程**（`mcrpc-client-*`，daemon cached pool）| 每连接 1 个 | 读一行请求、调 dispatcher、回写响应 |
| **render thread**（客户端主线程）| 1 | Minecraft 游戏循环、tick、所有实体/世界访问 |

注意：JSON-RPC 的 handler 运行在**网络处理线程**上，而它要操控的玩家实体、世界、攻击/移动 API 都只能在 **render thread** 安全访问。直接跨线程访问会崩溃或产生错乱状态。

## 核心约束

> **Minecraft 客户端状态只能在 render thread 访问。**

因此任何来自网络线程的操控请求，都不能直接执行，必须：

1. 把「要做的动作」打包成任务，放进一个并发队列（`ConcurrentLinkedQueue`）；
2. 网络线程阻塞等待一个 `CompletableFuture`（带超时）；
3. render thread 在客户端 tick（`ClientTickEvent.CLIENT_POST`）里每帧从队列取出任务、执行，把结果或异常写回 future；
4. 网络线程拿到 future 结果，返回 JSON-RPC 响应。

```
网络线程                      队列            render thread（客户端 tick）
   │                           │                       │
   ├─ action + future ─────────▶                       │
   │                           ├─ 每帧 poll ──────────▶
   │                           │                       ├─ 执行 MC API
   │                           │◀─ complete(result) ───┤
   │◀─ future.get(超时) ────────┘                       │
   └─ 返回响应                                          │
```

实现对应：
- 入队 + 等待：`ClientPlayerController.submitOnClientThread(action, timeoutMs)`。
- 每帧消费：`ClientPlayerController.tick()` 的第一步，循环 `queue.poll()` 并执行。

## 正确模式一：普通操控（submitOnClientThread）

适用于「一帧内就能做完」的动作：读状态、设置视角、按/放一个键。

```
网络线程：
  future = new CompletableFuture
  queue.add(ClientTask(action, future))
  return future.get(timeoutMs)

render thread（下一帧 tick）：
  task = queue.poll()
  try   → task.future.complete(task.action.get())
  catch → task.future.completeExceptionally(t)
```

超时与异常都转成字符串结果返回，调用方能看到「客户端超时」「玩家未进入世界」等信息，而不是无限挂起。

## 正确模式二：跨多帧的高级指令（attackNearest）

有些操作必须跨多帧完成：自动寻路到目标、走到触及范围再攻击。这类**不能**用 `submitOnClientThread` 把动作塞一帧里。

正确做法分两段：

1. 网络线程提交一个「瞬间完成」的设置任务：在 render thread 找到目标、设置 `attackTarget` 与一个新的 `attackFuture`，然后 render thread 立即释放；
2. render thread 在后续每帧的 `tick()` 里驱动：远了就 `moveToward`、近了（触及内 + 目标存活）就执行攻击，完成后 `attackFuture.complete(...)`；
3. 网络线程在外侧 `attackFuture.get(15s)`，超时则清理状态。

关键：**render thread 从不阻塞**，它每帧继续跑游戏循环，寻路与攻击在 tick 中推进；等待发生在网络线程。

## 死锁陷阱（务必避免）

### 陷阱一：在 render thread 上 `future.get()`

如果一个已在 render thread 执行的 handler 里去 `future.get()` 阻塞，那么 render thread 被占住，客户端 tick 永远不会执行，而那个 future 恰恰要等 tick 才能完成——**死锁**。

规则：render thread 上只「提交瞬间动作 / 设置目标」，绝不等待；所有阻塞等待都在网络线程。

### 陷阱二：网络线程池共用单线程

服务端版的教训：若 accept 和连接处理共用一个单线程池，accept 占满唯一线程，处理 handler 永远排不上队，表现为「连得上但永不响应」。

对策：accept 独占一个 daemon 线程；连接处理用 `newCachedThreadPool`。

## 持续移动的时序细节

`moveTarget` / `attackTarget` 由 tick 每帧处理，涉及几个门槛值，设错会导致永远走不到：

- `moveTarget` 的「到达」门槛 = `entityInteractionRange() - 0.5`（更严格）；
- `attackTarget` 的「触发攻击」门槛 = `entityInteractionRange() - 0.2`；
- 两者之间留约 0.3 格缓冲。

若 moveTarget 提前停在与 attackTarget 相同的门槛，moveTarget 一停，attackTarget 就永远进不了攻击范围。

## 生命周期与清理

- 服务启动时起 accept 线程和线程池（全部 daemon，不阻止 JVM 退出）。
- `stop()`：置 `running=false`、关 `ServerSocket`、中断 accept 线程、`shutdownNow` 线程池。
- tick 由平台 `ClientTickEvent.CLIENT_POST` 每帧驱动；服务停止/客户端退出后自然不再调用。
