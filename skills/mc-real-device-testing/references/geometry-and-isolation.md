# 几何、定点与清场隔离

真机测试的几何必须确定：怪不能漂移、玩家不能被反作弊踢、目标不能选错。

## 定点：NoAI 是首选，但反击测试例外

召唤 NBT 带 `NoAI:1b`，怪原地不动、不扑腾、不穿墙、不窒息，碰撞箱锁在召唤的整数格，几何最精确：

```
summon minecraft:zombie <x> <y> <z> {CustomName:'{"text":"near"}',PersistenceRequired:1b,NoAI:1b}
```

- 小碰撞箱（蠹虫、小史莱姆等）用 1 格高封闭隔间防漂移；大碰撞箱（远古守卫者等）用 NoAI + 开放平台。
- `PersistenceRequired:1b` 防止 despawn。
- **例外：要测攻击 goal / 反击 / 寻路时绝不能 NoAI**——它把攻击 AI 一起停了，怪只会被打不会还手。此时几何改用「服务端反复 tp 定点」：每约 0.8s 把怪拉回玩家东侧固定锚点（如 1.5 格），既保留 AI 又锁住位置。阶段 A（贴身不主动掉血）与阶段 B（触发后反击掉血）都用这套。

## 多目标布局：要打的目标必须最近

```
            [far]   ← 8 格（z 轴负方向）
              ↑
            [near]  ← 3 格
              ↑
 [玩家] ──→ [target] ← 0.5~1.5 格（+x，保证 target 最近）
```

`player.attackNearest(target=...)` 在 32 格内选最近的指定类型。target 必须比 near/far 更接近玩家，否则会瞄错。开场 `kill @e[type=<id>]` 清掉野生同类。坐标用固定整数格 + 玩家站格中心（.5），不要用 `round(pos())` 现算（Python 银行家舍入 + 野生实体会造成超距/选错）。

## 距离窗口：卡在 mod 阈值与原版属性之间

「拉远触发某距离判定」前先查目标生物的相关属性（典型是 `FOLLOW_RANGE`）。如果一次 tp 越过原版感知范围，原版目标选择目标会当 tick 清空 target，而很多「距离脱战」判定只在「仍能解析出 target」时才执行，目标一丢就只能干等冷却，用例假失败。

通用做法：测试距离 d 满足

```
mod 触发阈值 < d < 原版属性值
```

并给实体碰撞箱中心 ±0.5 的浮动留余量。

## 边界值不要贴线

距离判定（球形 `distanceToSqr <= r*r` 或 AABB）在实体碰撞箱中心有 ±0.5 浮动，距离恰好等于阈值不稳定。硬断言的测试距离要离阈值足够远（经验 ≥2 格差）：

| 配置阈值 | 必中距离 | 必不中距离 | 不要用 |
|---|---|---|---|
| 4 格 | 2 格 | 8 格 | 4 格（临界，±0.5 浮动） |

半径类判据一次摆两组（≤r 与 >r）同时验证，避免单点证据。

## 反作弊：不能把玩家传到非正常位置

`allow-flight=false`（默认）下：tp 到高空（y≈300）判 `floating too long`，tp 到基岩层下（y≈-60）判 `Flying is not enabled`，都会被踢。**不存在「高空隔离盒」方案**。可靠隔离是原地处理：

```
gamerule doMobSpawning false         # 关全服自然刷怪（finally 恢复）
kill @e[type=!player,distance=..40]  # 清周围 40 格非玩家实体
在原地整数格召唤定点测试怪
```

- tp 只做短距离、同高度、落点有地板的移动；落点先铺地板防熔岩/悬空。
- teardown 不要在 finally 拆玩家脚下地板（`fill ... air` 会让玩家掉出世界 → DisconnectedScreen/摔死）。只清平台上方残块，或先把玩家 tp 到独立落脚点再拆。
- RCON 的 `~` 是控制台/出生点位置不是玩家位置，裸 RCON 的 `distance=` 原点也是世界出生点。需要以玩家为原点用 `execute as <p> at <p> run ...`；召唤坐标从 `player.pos` 解析成绝对整数格。

## attackNearest 状态机的门槛协调

高级指令内部有 move/attack 两个门槛，差太小会「走到了但永远不出手」：

- moveTarget 到达门槛：`reach - 0.5`
- attackTarget 触发门槛：`reach - 0.2`
- 中间留 0.3 格缓冲，保证走到攻击门槛内时攻击判定能触发。

触及距离由 `player.entityInteractionRange()` 决定（生存约 3 格），超出服务端判挥空，工具返回 -32003 并带 distance/reach，提示先走近。
