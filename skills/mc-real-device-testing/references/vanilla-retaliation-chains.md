# 原版生物报复链 A/B/C 分类（1.21.1）

源码核实自 NeoForge 21.1.250 merged sources jar。适用于所有 Goal 系生物（非 Brain 系）。

## 核心机制

Goal 系生物受击后的同族通知链 = `HurtByTargetGoal.alertOthers()`，其通知半径来自 `getFollowDistance()` = `getAttributeValue(Attributes.FOLLOW_RANGE)`，即**报复半径 = 该生物的 FOLLOW_RANGE 属性值**（AABB 立方体，不是球）。

- `HurtByTargetGoal.setAlertOthers(Class<?>... classes)` 开启同族通知，参数是**要忽略的类**（如传 `ZombifiedPiglin.class` 表示不通知僵尸猪灵）；空参 = 通知同族。
- 只有被 `setAlertOthers` 覆盖到的生物，才会在同伴受击时被外部 `setTarget`。

## A 类：有 HurtBy 且 setAlertOthers（受击会通知同族）

| 生物 | FOLLOW_RANGE | 备注 |
|---|---|---|
| Zombie | 35 | `setAlertOthers(ZombifiedPiglin.class)` |
| ZombieVillager | 35 | 继承 Zombie，未重写 registerGoals |
| Husk | 35 | 继承 Zombie，未重写 |
| Drowned | 35 | `setAlertOthers(Drowned.class, ZombifiedPiglin.class)` |
| ZombifiedPiglin | **16** | `setAlertOthers()`（空=通知同族）；registerGoals 自带，非来自 NeutralMob 接口 |
| Blaze | **48** | `setAlertOthers()` |
| Endermite | **16** | `setAlertOthers()` |
| Silverfish | **16** | `setAlertOthers()` |
| Shulker | **16** | `setAlertOthers(this.getClass())` → 只通知 Shulker 子类 |
| Illusioner | **18** | `setAlertOthers(Raider.class)` → 灾厄村民族互通 |
| Ravager | **32** | `setAlertOthers(Raider.class)` |
| Pillager | **32** | `setAlertOthers(Raider.class)` |
| Vindicator | **12** | `setAlertOthers(Raider.class)` |
| Evoker | **12** | `setAlertOthers(Raider.class)` |
| Vex | **16** | `setAlertOthers(Raider.class)` |

## B 类：有 HurtBy 但无 setAlertOthers（只自己反击，不通知同族）

| 生物 | FOLLOW_RANGE | 备注 |
|---|---|---|
| Skeleton / AbstractSkeleton | 16（Mob 默认） | `new HurtByTargetGoal(this)` |
| WitherSkeleton / Stray / Bogged | 同父类 | 继承 AbstractSkeleton |
| Spider | 16 | `new HurtByTargetGoal(this)` |
| CaveSpider | 继承 Spider | 未重写 |
| Creeper | 16 | `new HurtByTargetGoal(this)` |
| Witch | 16 | `new HurtByTargetGoal(this, Raider.class)`（有 Raider 过滤但无 alert） |
| EnderMan | **64** | follow_range 极大，不通知尤其重要 |
| WitherBoss | **40** | `new HurtByTargetGoal(this)` |

## C 类：完全无 HurtByTargetGoal（受击既不反击也不示警）

| 生物 | FOLLOW_RANGE | 类型层级 | 动态添加可能 |
|---|---|---|---|
| Giant | 16 | PathfinderMob | 可在运行时构造 `new HurtByTargetGoal(pm)` 添加 |
| Guardian | 16 | PathfinderMob（只用 NearestAttackableLiving...） | 可动态添加 |
| ElderGuardian | 16 | 继承 Guardian | 可动态添加 |
| Ghast | **100** | FlyingMob（非 PathfinderMob） | 不能用 HurtByTargetGoal |
| Slime | 16 | Mob（非 PathfinderMob） | 不能用 |
| MagmaCube | 16 | 继承 Slime | 不能用 |
| Phantom | 16 | FlyingMob | 不能用 |

动态添加判据：是否 `PathfinderMob`。Giant/Guardian/ElderGuardian 层级含 PathfinderMob，可构造 HurtByTargetGoal；Ghast/Slime/MagmaCube/Phantom 不是 PathfinderMob，走不了这条 goal。

## NeutralMob 接口的混淆点

僵尸猪灵 `implements NeutralMob`，但它的报复链走的是自己 `registerGoals` 里的 `HurtByTargetGoal.setAlertOthers()`。NeutralMob 接口管的是**持久愤怒状态**（计时器、UUID、存档），它没有 `alertOthers()` 方法。所以一个 NeutralMob 实现者仍可能是 A 类；此外僵尸猪灵类体内还自带一个私有的 alertOthers 传播，独立于 HurtByTargetGoal 链，改造时要单独处理，否则一次玩家攻击会按完整 follow range 无界层层传播。

## FOLLOW_RANGE 继承链

```
LivingEntity.createLivingAttributes()        （无 FOLLOW_RANGE）
  └── Mob.createMobAttributes()             .add(FOLLOW_RANGE, 16.0)  ← 默认 16
       └── Monster.createMonsterAttributes()  不覆盖
            ├── Zombie      → 覆写 35.0
            ├── Blaze       → 覆写 48.0
            ├── Ghast       → 覆写 100.0
            ├── EnderMan    → 覆写 64.0
            ├── WitherBoss  → 覆写 40.0
            └── Creeper 等  → 不覆写 → 默认 16.0
```

## 对真机测试的意义

原版大范围报复链（35/48 格 AABB 立方体，体积比同半径球大约 73%）会和被测 mod 自己的小范围（如配置 4 格球形、严格同类、单层、仅玩家触发）判定**双轨同时触发**。因此：

- 把观察个体放在 8 格外却仍被惊动，**不一定是被测 mod 的问题**，可能是原版 alertOthers。
- 必须用同几何阳性/阴性对照区分两条链（见 SKILL.md「原版报复链」节）。
- 若要隔离被测行为，可在测试构建里接管原版 `alertOthers()`（HEAD 注入取消，改走自定义分发），或选用 B/C 类生物验证「mod 新增的示警」而避开原生 A 类链。
