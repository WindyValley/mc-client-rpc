# `/damage` 等命令为何绕过玩家攻击链路

## 现象

用 `/damage @e[name=target] 1 minecraft:player_attack by <player>` 造伤害，挂在「玩家攻击」上的 mod mixin / 事件判定不执行；服务端查目标的 Target/HurtBy/Brain 记忆全空。换成 mc-client-rpc 的真实攻击就正常。

## 根因

1. `/damage ... by <player>` 的 `by` 子句虽填了玩家名，但生成的伤害来源 `getEntity()` 返回的是**命令执行路径上的实体包装**，不是进入玩家攻击处理的那个 `Player` 对象；`CommandSourceStack.getEntity()` 的静态类型是 `Entity`。
2. 更关键：真实玩家攻击的链路是

   ```
   GameMode.attack()
     → ServerboundInteractPacket
       → ServerGamePacketListenerImpl.handleInteract()
         → Entity.attack / LivingEntity.hurt(DamageSource, amount)
           → LivingHurtEvent（事件总线）
           → mod 的攻击 mixin / 仇恨归因 / 同族示警
   ```

   `/damage` 从服务端命令直接调 `LivingEntity.hurt()`，**不经过交互包处理**，事件总线/交互 mixin 挂载点不同，依赖「这是玩家交互包打来的」的判定不成立。

结论：凡是验证「玩家打了某实体之后系统的反应」，必须用真实攻击，不能用 `/damage`。

## 两个真实攻击原语

### player.attack(target) —— 一次性

走完整 `gameMode.attack → ServerboundInteractPacket` 链路，触发所有攻击相关 mixin。限制：

- 必须在触及范围内（生存由 `entityInteractionRange()` 决定，约 3 格），超距返回 -32003，先 moveToward。
- 准星必须对准目标碰撞箱，常因准星偏差/攻击冷却/网络延迟挥空。可连打 3 次（间隔约 0.6s）。
- 目标必须是最近的同类，否则可能选错。

### player.attackNearest(target) —— tick 驱动的高级指令（推荐）

参考怪物 `MeleeAttackGoal` 的状态机：render thread 上只「瞬间设置攻击目标」立即返回，之后每 tick 自动寻路到触及范围、瞄准、攻击，RPC 线程在外层等 future（约 15s 超时）。命中率高，适合自动化。

- 选最近的指定裸 id 实体；要打的 target 放在约 0.5~1.5 格、干扰个体放 3 格以外并先清野生同类。
- 返回串里的目标血量是客户端预测；命中以服务端 Health / 玩家掉血 / mod 状态探针为准。

## 实现要点（给本工具加原语时）

- 跨多帧高级指令**不能在 render thread 上 `future.get()`**：会阻塞 render thread，tick() 永远没机会处理攻击目标，死锁。只在 render thread 设 `attackTarget + attackFuture` 后立即返回，RPC 线程在网络侧等。
- `moveTarget` 的到达门槛要比 `attackTarget` 的触发门槛更松，留缓冲：到达 `reach-0.5` 停、攻击在 `reach-0.2` 触发，中间留 0.3 格，否则 moveTarget 刚到门槛就停、攻击永远触发不了。
- 目标死亡要在 tick 里完成 future（返回「目标已死亡」）并清状态。

```java
// 每帧（ClientTickEvent.CLIENT_POST）
if (atkTarget != null && atkFuture != null && p != null) {
    if (!atkTarget.isAlive()) {
        atkFuture.complete("目标已死亡");
        attackTarget = null;
    } else if (p.distanceTo(atkTarget) <= p.entityInteractionRange() - 0.2) {
        aimAt(p, atkTarget);
        mc.gameMode.attack(p, atkTarget);
        p.swing(InteractionHand.MAIN_HAND);
        atkFuture.complete(/* 延迟读服务端/客户端血量组装结果 */);
        attackTarget = null; moveTarget = null;
    } else {
        moveTarget = atkTarget;   // 继续走近，门槛 reach-0.5
    }
}
```

## 非玩家攻击对照怎么做

要验「只有玩家攻击才触发、怪打怪不触发」时，不能用 `/damage`（链路不对），让一个铁傀儡真实攻击目标；挨打的怪给抗性 V（用 `effect give`，不要内联 ActiveEffects NBT）避免被打死；命中检测轮询 HurtTime>0 或 HP 下降；同时确认被测行为通道在怪打怪时全程不触发。
