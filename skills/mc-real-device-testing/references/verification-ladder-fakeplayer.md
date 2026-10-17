# 验证手段升级梯：RCON → FakePlayer → 服务端探针 → 真实客户端

规划一个行为验证时，先问「到底要证明什么」，选成本最低又能回答问题的那一级，只在上一级答不了时升级。真实客户端是唯一能证明「玩家真操作时系统如何响应」的手段，但它最贵，不要事事都上。

## 决策表

| 要验证的命题 | RCON/命令 | FakePlayer | 真实客户端 |
|---|---|---|---|
| 配置能加载、命令能用 | ✅ | — | — |
| 实体在正确位置生成 | ✅ | — | — |
| 中立化后 `getTarget()` 为 null | ❌ 难 | ✅ | ✅ |
| 生物看到玩家**不主动攻击** | ❌ | ❌ | ✅ |
| 生物被打后**会反击** | ❌ | ❌（见下） | ✅ |
| 交易界面打开、报价正确 | ❌ | ❌ | ✅ |
| 交易产出（真的拿到物品） | ❌ | ❌ | ✅ |

## FakePlayer 为什么不能验反击（1.21.1 字节码核实）

对 FakePlayer 反编译/`javap` 可见它硬编码了三条：

```java
public boolean isInvulnerableTo(DamageSource) { return true; }  // iconst_1; ireturn，永远无敌
public boolean canHarmPlayer(Player)        { return false; }   // iconst_0，永远伤不了玩家
public void die(DamageSource)               { /* 空方法，永不死亡 */ }
```

于是 `mob.hurt(fakePlayerSource, amount)` 在 `hurt()` 链前段就因 `isInvulnerableTo==true` 直接返回，生物根本没真正受伤 → `HurtByTargetGoal` 不触发；`NearestAttackableTargetGoal` 仍可能把 FakePlayer 选为目标（它是 Player 实例），但距离/视线/攻击冷却照常评估，不会真近战。结论：**FakePlayer 只能验被动 AI 状态（target 为 null、goal 被移除），不能验主动行为（是否真的还手）**。

要在服务端不靠真实客户端触发完整伤害链，只能在 mod 自己的服务端逻辑里用**真实 `ServerPlayer`** 调 `target.hurt(player.damageSources().playerAttack(player), 1.0F)`；但那是服务端直接造伤害，仍绕过真实交互包，适合中间级探针，不等同于玩家操作。

## 升级梯

```
RCON/命令  →  FakePlayer  →  mod 自带服务端 JSON-RPC 探针（真实 ServerPlayer）  →  真实客户端
 便宜/弱                                                                  贵/强
```

- RCON：布置场景、服务管理、能直接观测的世界状态。
- FakePlayer：仅被动 AI 状态。
- 服务端探针：能调真实 ServerPlayer 走伤害管线、能读 mod 内部状态；但绕过真实输入管线（reach/冷却/距离检查、交互包），且方法直调 `target.hurt`。
- 真实客户端（mc-client-rpc）：唯一证明「真实输入→发包→服务端响应」全链路的手段，留给最终的「游戏里到底行不行」。

## RCON 与玩家命令的边界

- 服务管理（召唤、kill、gamemode、give、改 gamerule）一律走 RCON，不要用玩家命令通道。
- 玩家动作（聊天、以玩家身份发命令）才走客户端 `player.command`——它发的是真实客户端包，适合验证「一个 OP/普通玩家能发出什么命令」，不适合当测试基础设施的控制通道。
- OP 身份是**被测的身份变量**，不是跑管理命令的捷径；需要 OP 的操作临时 op、finally deop。

## 运行时注册表上的数据管线不属于任何一级

扫描战利品表、构建价格表这类「跑在 `reloadableRegistries` 上的数据管线」，RCON/FakePlayer 看不到、也不需要客户端。正确做法是在 mod 的服务端验证服务上开结构化 JSON-RPC，离线把管线跑出的行与黄金基线文件比对。纯 JUnit 也做不了——mixin accessor 在 JUnit 不织入、codec 解析需要动态注册表。

比对前先把黄金基线**过滤到你的扫描域**：全量快照（如全游戏 110 条）覆盖所有实体，mod 的扫描表只含它适配的生物（如 38→70 条）；在适配子集内比对，别拿原始全量文件比，否则每个域外条目都被读成「缺失」。

## 未接线的层没有游戏内行为可验

一个生产代码零调用方的数据门面、一个还没有命令能到达的 manager：单测只能证明映射/配置的数学。在报告和任务卡里显式分开「已测=映射/配置逻辑」「未测=真实游戏表现」，游戏内结论等集成任务接线后再走上面对应级别验证。

## 已核实结论当次落文档

真服务器/真客户端/源码 jar 字节码确认的行为与数据，当次写进任务卡验收项与项目状态文档；结论推翻设计时连 ADR/设计文档一起改。对话记录不算数——后续会话是照着文档改代码的，没进文档的证据会被重新争论或丢失。
