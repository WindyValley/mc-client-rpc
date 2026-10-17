# 真人上床过夜：验跨自然日机制

要证明「玩家真的睡觉过夜」，不能用 `/time add` 假装——虽然两者都走 `ServerLevel#setDayTime`，但睡觉还涉及床交互、SleepTimer、敌怪距离校验。用 mc-client-rpc 的 `player.useBed(x,y,z)`（真实 `gameMode.useItemOn` 发包并强制视线对准床顶面；服务端按玩家视角重做 raycast，不对准就被拒）。成败不看返回值，轮询服务端状态。

## 入睡判据：SleepTimer

上床后服务端是入睡倒计时 tick（实测 10→101→0，约 5s 过夜）：

- `data get entity @s SleepTimer` **>0 即已上床**，归 0 即过夜醒来。
- `Bed` / `SleepingPos` / `Sleeping` 这些字段 `data get` 读不到（返回 Found no elements）；客户端返回的 isSleeping 当帧也还是 false，都不能当判据。
- 服务端日志的 `Sweet Dreams` 成就只在首次上床出现，不能当判据。

## 跨日判据：daytime 回卷，不是 query day

- `time query day` 是单调的 `gameTime/24000`。玩家睡觉只推进 getDayTime、**不推进 gameTime**，所以过夜后 query day 不变，会误判「没跨日」。
- `time query daytime` 的值从约 13100（夜）**回卷到约 13**（次日晨）即跨过自然日边界。验「按自然日计数」的机制（典型如按 `getDayTime()/24000` 算补货日期）时，判据口径必须和被测代码一致，用 daytime 回卷。

## 锚点时序：在开窗/召唤之前先设夜

很多「每自然日重置一次」的机制，自然日是在**首次触发/首次打开界面时锚定**的（例如商人报价在首次 `getOffers()` 时锚定补货日）。顺序错了睡了也不触发：

1. 召唤/开商窗前先 `time set 13000`，让锚定日 = 今夜；
2. 做需要「耗尽当天次数」的操作（如买光报价）；
3. 真人睡觉到次日清晨；
4. 相对锚定日 +1，机制重置（补货）。

如果先开窗/召唤才 `time set night`（绝对回拨 getDayTime），睡后相对锚定日不 +1，不会重置——而用 `/time add` 的旧测试不回拨，暴露不了这个顺序问题。

## 床与怪的距离

- 床睡觉要求玩家一定范围内没有 `Enemy`（中立化/改造后的怪类型仍是 Monster/Enemy，仍会挡睡）。把床放在离怪足够远（如南 24 格：超出 8 格睡觉校验，又在 simulation distance 内让怪持续 tick）。
- 睡前清掉玩家附近非玩家实体：注意裸 RCON 的 `distance=` 原点是世界出生点，要用
  `execute as <p> at <p> run kill @e[type=!player,distance=..16]`。跨 loader 玩家选择器一律 `@s`/`@p`，不要写死 dev 用户名（Fabric 是 `Player###`）。

## 环境与清理

- `gamerule playersSleepingPercentage 0`：单人即可过夜，不等其他玩家。
- 床用 blockstate 放两格（如 `red_bed[facing=south,part=foot]` 与 `head`），脚下先铺地板、床上方 `fill air` 保证无遮挡。
- finally：把床还原成地板，还原 `playersSleepingPercentage`、`doMobSpawning`、时间/难度等所有临时改动（在 setup 时就逐项登记还原）。
