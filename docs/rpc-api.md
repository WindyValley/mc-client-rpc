# mc-client-rpc JSON-RPC API 参考

客户端自动化控制接口。在 Minecraft 客户端内启动一个只绑定回环地址的 JSON-RPC 服务，
外部脚本通过 TCP 发请求驱动真实客户端（连服、建/进世界、移动、攻击、交易、放方块等），
所有动作都走原版输入 / 发包管线，与真人操作等价。

- **适用版本**：mod 0.1.0，Minecraft Java 1.21.1（NeoForge / Fabric 双端，业务逻辑同源）
- **单一事实来源**：代码注册的方法元数据。运行时调用 `methods.list` 可拿到与本文一致的
  自描述 schema（方法名 / 参数名 / 类型 / 是否必传 / 默认值 / 说明）。本文与代码冲突时以代码为准。

## 目录

1. [传输与编码](#1-传输与编码)
2. [请求与响应](#2-请求与响应)
3. [错误码](#3-错误码)
4. [执行模型与状态机](#4-执行模型与状态机自动化作者必读)
5. [方法参考](#5-方法参考)
   - [5.1 系统](#51-系统) · [5.2 客户端导航](#52-客户端导航进世界前后均可用) · [5.3 单机存档](#53-单机存档标题屏可用)
   - [5.4 玩家状态](#54-玩家状态进入世界后) · [5.5 玩家操控](#55-玩家操控进入世界后) · [5.6 战斗与实体交互](#56-战斗与实体交互进入世界后)
   - [5.7 命令与聊天](#57-命令与聊天进入世界后) · [5.8 方块交互](#58-方块交互进入世界后) · [5.9 世界查询](#59-世界查询进入世界后) · [5.10 交易 GUI](#510-村民式交易-gui)
6. [调用示例](#6-调用示例)
7. [方法速查表](#7-方法速查表)

## 1. 传输与编码

| 项 | 值 |
|----|----|
| 传输 | TCP，默认 `127.0.0.1:25586`，**只绑回环地址**（不对外暴露） |
| 编码 | UTF-8 |
| 帧格式 | **每行一个 JSON 对象**，以 `\n` 分隔；请求一行、响应一行 |
| 协议 | JSON-RPC 2.0 |
| 单行请求上限 | 1 MB |
| 读超时 | 30 s（仅计两次请求之间的空闲，方法阻塞执行期间不计时） |
| 最大并发连接 | 16 |
| 批量请求 | **不支持**。顶层为 JSON 数组时返回 -32700 |
| 参数形式 | **仅按名称传参**（`params` 必须是对象）。传位置数组返回 -32600 |

## 2. 请求与响应

请求：

```json
{"jsonrpc":"2.0","method":"player.attack","params":{"target":"minecraft:zombie"},"id":1}
```

成功响应（`result` 为结构化对象或字符串）：

```json
{"jsonrpc":"2.0","id":1,"result":{"target":"minecraft:zombie","distance":2.0,"reach":3.0,"hpBefore":20.0,"hpAfter":17.0,"hit":true}}
```

错误响应（可携带结构化 `data`）：

```json
{"jsonrpc":"2.0","id":1,"error":{"code":-32003,"message":"目标超出触及距离，需先走近","data":{"distance":4.1,"reach":3.0}}}
```

`id` 可为整数或字符串，响应原样回传。

### 2.1 Notification

请求不带 `id`（或 `id` 为 null）时按 JSON-RPC notification 处理：执行方法但**不回任何内容**，
即使方法抛错。需要结果或错误时务必携带非 null 的 `id`。

### 2.2 字符串返回 vs 结构化返回

多数操控方法（`player.move`、`player.look`、`trade.close` 等）返回一句人类可读的中文字符串，
**仅用于日志，自动化逻辑不要解析这些字符串**。状态判断应调用结构化查询方法
（`client.status`、`player.state`、`player.pos`、`trade.status` 等），其字段是稳定契约。

## 3. 错误码

标准预留码（JSON-RPC 2.0）：

| code | 含义 | 触发条件 |
|------|------|---------|
| -32700 | Parse Error | 请求非合法 JSON / 顶层不是对象 / 传了批量数组 |
| -32600 | Invalid Request | 缺 `method`、`jsonrpc` 非 `"2.0"`、`params` 不是对象 |
| -32601 | Method Not Found | 方法未注册 |
| -32602 | Invalid Params | 必传参数缺失 / 空白，或枚举值非法、数值越界 |
| -32603 | Internal Error | 其他未预期异常（message 含异常类型） |

业务错误码（自定义区间 -32000 ~ -32099）：

| code | 含义 | 典型触发 |
|------|------|---------|
| -32000 | Timeout | 操作在限定时间内未完成（见各方法超时） |
| -32001 | Not Ready | 未进入世界就调 `player.*`/`trade.*`；进存档被不兼容/数据包失败屏拦截；未打开交易屏就调 `trade.*` |
| -32002 | Target Not Found | 半径内无匹配目标；`world.enter` 的存档 id 不存在 |
| -32003 | Out of Reach | 目标实体 / 方块超出触及距离（`data` 含 `distance`、`reach`） |
| -32004 | Busy | 已有进行中的高层操作（如 `player.attackNearest` 会话未结束） |

## 4. 执行模型与状态机（自动化作者必读）

### 4.1 线程模型

Minecraft 的游戏状态只允许在 render thread 访问。所有方法的实际逻辑都被切到 render thread
执行；RPC 调用线程排队等待结果。每个方法注册了一个**等待超时**（见下文章节），
超时返回 -32000（典型原因：游戏卡顿、未进世界、模态界面阻塞）。

部分方法是**跨多帧（deferred / 会话式）**的：发起后由客户端每帧 tick 驱动，
在服务端权威状态同步后才完成。这类方法会阻塞到真正出结果，调用方无需也不应自己 sleep 轮询单次动作：

- `player.attack`：攻击后等若干 tick 读服务端同步的血量再判定命中。
- `player.interact`：右键后等开屏包到达，再判定是否打开了交易界面。
- `world.enter`：tick 会话驱动到真正进入世界（含自动处理备份确认屏），最长约 120 s。
- `player.attackNearest`：tick 驱动寻路 → 接近 → 攻击，15 s 会话；同时刻只允许一个，重复提交得 -32004。

### 4.2 客户端状态机

| 状态 | `client.status` 特征 | 可用方法 |
|------|--------------------|---------|
| 启动引导 | `screen` 为 `AccessibilityOnboardingScreen` 等，`inWorld=false` | 先 `client.toTitle` |
| 标题屏 | `screen="TitleScreen"`，`inWorld=false` | `client.*`、`world.*` |
| 连接 / 加载中 | `screen` 为 `ConnectScreen`/`LevelLoadingScreen`/`ReceivingLevelScreen` | 等待，勿发操控指令 |
| 世界中（无界面） | `inWorld=true`，`screen=null` | `player.*`、`world.entities`、`world.enter`（会先离开当前世界） |
| 世界中（有 GUI） | `inWorld=true`，`screen` 非 null（如 `MerchantScreen`） | 查询类 + 对应 GUI 原语（`trade.*`） |

标准编排：操作前先 `client.status` 判定当前阶段；进世界后才能发 `player.*`。

### 4.3 通用参数约定

- `params` 整体缺省按空对象处理，使用各参数默认值。
- 实体类型参数 `target` 使用实体注册 ID（`minecraft:zombie`）。省略命名空间默认补 `minecraft:`，
  故 `"zombie"` 等价于 `"minecraft:zombie"`。匹配为**精确匹配**：`zombie` 不会命中 husk（尸壳）、
  drowned（溺尸）等。
- 坐标参数 `x/y/z` 为整数方块坐标。
- 涉及真实放置 / 使用的方法要求主手持有正确物品（通常用 RCON `/item replace` 预置）。

---

## 5. 方法参考

方法按用途分组。每个方法标注**等待超时**（RPC 调用从排队到拿到结果的上限，超时 -32000）。

### 5.1 系统

#### `methods.list`
列出所有已注册方法及其参数 schema（协议自描述）。
- 参数：无
- 超时：3 s
- 返回：

```json
{"count": 29, "methods": [
  {"name": "player.attack", "description": "...",
   "params": [{"name":"target","type":"string","required":false,"default":"zombie","description":"..."}]}
]}
```

### 5.2 客户端导航（进世界前后均可用）

#### `client.status`
查询客户端当前界面与连接状态，自动化据此判断能否发 `player.*`。
- 参数：无；超时 3 s
- 返回字段：

| 字段 | 类型 | 含义 |
|------|------|------|
| `screen` | string\|null | 当前 Screen 类简单名（如 `TitleScreen`、`MerchantScreen`）；游戏中无界面为 null |
| `inWorld` | boolean | 玩家与关卡均非空 |
| `connected` | boolean | 网络连接存在且仍连通 |
| `server` | string\|null | 当前服务器地址（如 `203.0.113.10:25565`，示例地址），单机为 null |

#### `client.toTitle`
无条件切到 `TitleScreen`，跳过无障碍引导屏等拦路界面。
- 参数：无；超时 3 s
- 返回字符串：`已从 <原界面> 切到 TitleScreen`
- 注意：资源加载完成后引导屏可能再次弹出盖掉主菜单；自动化在启动期应轮询 `client.status`，
  发现被盖回就重调本方法（直到 `TitleScreen` 稳定数秒）。

#### `client.connect`
走官方 `ConnectScreen.startConnecting` 直连服务器（等价在多人列表点「加入服务器」）。
- 超时 8 s（仅表示连接已发起；进世界需另用 `client.status` 轮询，通常数秒）
- 参数：

| 名称 | 类型 | 必传 | 默认 | 说明 |
|------|------|------|------|------|
| `host` | string | 是 | — | 服务器地址 |
| `port` | integer | 否 | `25565` | 端口 |

- 连接前自动切到主菜单。`host` 缺失 / 空白返回 -32602。

#### `client.disconnect`
断开当前连接 / 退出当前世界，回到 `TitleScreen`。
- 参数：无；超时 3 s
- 返回字符串：`已断开，回主菜单`；本就不在世界时返回 `本就不在世界中`。

#### `client.quit`
完全退出客户端进程。
- 参数：无；**不走任务队列**，立即返回字符串 `客户端将在 500ms 后退出`
- 内部延迟 500 ms（留出响应发送时间）后在 render thread 调 `Minecraft.destroy()`，并以 `halt(0)` 兜底。

### 5.3 单机存档（标题屏可用）

均走官方 `WorldOpenFlows` 路径，与真人点按钮 / quickPlay 单机等价。调用前若在世界中，先 `client.disconnect`。

#### `world.list`
列出本机存档；后台线程异步读 `level.dat` 摘要，不阻塞游戏循环。
- 参数：无；超时 30 s
- 返回：`{"count": N, "worlds": [...]}`，按最后游玩时间倒序。每项：

| 字段 | 类型 | 含义 |
|------|------|------|
| `id` | string | 存档**目录名**，`world.enter` 用它 |
| `name` | string | 世界显示名 |
| `lastPlayed` | number | 最后游玩时间戳（毫秒） |
| `locked` | boolean | 是否被占用锁定 |
| `experimental` | boolean | 是否实验性世界 |
| `disabled` | boolean | 是否被标记不可用 |
| `playable` | boolean | 是否可正常加载；`false` 表示损坏 / 符号链接存档，此时下列字段缺省 |
| `gameMode` | string | 仅 playable：生存/创造等内部名 |
| `hardcore` | boolean | 仅 playable |
| `cheats` | boolean | 仅 playable：是否允许指令 |
| `version` | string | 仅 playable：存档版本名 |

#### `world.create`
按参数创建世界并进入。走官方 `createFreshLevel`（内部 `managedBlock` 自泵，可同步安全加载）。
方法返回时通常已在世界中，稳妥起见再轮询 `client.status.inWorld`。
- 超时 120 s

| 名称 | 类型 | 必传 | 默认 | 说明 |
|------|------|------|------|------|
| `name` | string | 否 | `"New World"` | 显示名，同时决定存档目录名 |
| `seed` | string\|number | 否 | 空（随机） | 数字按 long 解析；其他文本按 `String.hashCode()`，与 GUI 规则一致 |
| `gameMode` | string | 否 | `"survival"` | `survival`/`creative`/`adventure`/`spectator`；hardcore=true 时强制 survival |
| `hardcore` | boolean | 否 | `false` | 极限模式（锁定困难 + 一条命） |
| `difficulty` | string | 否 | `"normal"` | `peaceful`/`easy`/`normal`/`hard`；hardcore=true 时强制 hard |
| `allowCheats` | boolean | 否 | 不传 | 是否允许作弊；不传则创造=true、其余=false（与 GUI 默认一致） |
| `generateStructures` | boolean | 否 | `true` | 生成建筑（村庄等） |
| `bonusChest` | boolean | 否 | `false` | 出生点奖励箱 |
| `preset` | string | 否 | `"normal"` | 世界类型：`normal`/`flat` |

- 返回字符串：`已创建并进入世界 <名>（目录 <folder>，种子 <long>）…`
- 重名目录自动加 `(1)`/`(2)` 后缀，**不覆盖旧档**（与 GUI 同规则）。
- 错误：已在世界中 -32001；空 `name`、非法 `gameMode`/`difficulty`/`preset` -32602。

#### `world.enter`
进入已有单机存档，走官方 `openWorld`，与启动参数 `--quickPlaySingleplayer <id>` 同路径（含版本兼容检查）。
**阻塞到真正进入世界**：tick 会话驱动，会话上限约 120 s（调用等待上限 130 s）。
- 参数：

| 名称 | 类型 | 必传 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | string | 是 | — | `world.list` 返回的存档目录名 |
| `backup` | boolean | 否 | `true` | 遇备份确认屏（降级 / 旧版 / 实验性世界）时，`true`=先整档压缩备份到 `run/backups/<时间戳>.zip` 再加载；`false`=跳过备份直接加载 |

- 自动确认按**按钮翻译键**识别（`selectWorld.backupJoinConfirmButton` / `backupJoinSkipButton`），
  不受语言 / 资源包译文影响，也不会被第三方 UI mod 额外插入的按钮带偏；找不到目标按钮时安全失败。
- 返回字符串：`已进入存档 <id>`（备份 / 跳过备份带相应后缀）。
- 错误：已在世界中 -32001；`id` 不存在 -32002（`data.id`）；`backup` 非布尔 -32602；
  加载途中被不兼容 / 数据包失败屏拦截 -32001（`data.screen` 为屏类名）；约 120 s 未进入 -32000。

### 5.4 玩家状态（进入世界后）

未进入世界调用返回 -32001。

#### `player.pos`
- 参数：无；超时 3 s
- 返回：`name`、`x`、`y`、`z`（各保留 1 位小数）、`dimension`（如 `minecraft:the_nether`）、`health`、`maxHealth`。

#### `player.state`
- 参数：无；超时 3 s
- 返回：`name`、`health`、`maxHealth`、`x`、`y`、`z`、`hitResult`（`MISS`/`BLOCK`/`ENTITY`/null）、`onGround`（boolean）、`dead`（boolean）。

#### `player.respawn`
死亡后重生，等价点死亡屏「重生」按钮（发 `ServerboundClientCommandPacket`）。
- 参数：无；超时 3 s
- 返回字符串：`已重生`；未死亡时返回 `玩家未死亡，无需重生`。
- 注意：重生点是世界出生点，之后通常需重新定位到目标位置。

### 5.5 玩家操控（进入世界后）

#### `player.look`
设置视角。超时 3 s。

| 名称 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `yaw` | float | `0` | 水平角 |
| `pitch` | float | `0` | 俯仰角 |

- 返回字符串：`视角已设为 yaw=.. pitch=..`。

#### `player.move`
按下 / 释放方向键映射，由原版 tick 驱动真实移动并发包。**持续生效**，需 `player.stop` 释放。超时 3 s。

| 名称 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `forward` | float | `0` | `>0` 前进（W），`<0` 后退（S） |
| `strafe` | float | `0` | `>0` 左移（A），`<0` 右移（D） |
| `jump` | boolean | `false` | 是否同时跳跃 |

- 返回字符串含当前按键状态。真实位移发生在后续 tick，调用后用 `player.pos`/`player.state` 观察结果。

#### `player.jump`
按住 / 松开跳跃键映射（由原版 tick 起跳并发包）。超时 3 s。

| 名称 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `down` | boolean | `true` | `true` 按下，`false` 释放 |

#### `player.stop`
释放全部方向 / 跳跃键并清除持续移动目标。参数无，超时 3 s，返回 `移动已停止`。

#### `player.moveToward`
设置持续移动目标：tick 每帧朝最近的指定生物前进并瞄准，进入触及范围后自动停。超时 3 s（仅设置动作）。

| 名称 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `target` | string | `"zombie"` | 实体注册 ID，精确匹配 |

- 返回字符串：`开始走向 <type>（当前 .. 格，触及 .. 格）`；附近无目标 -32002。
- 用 `player.stop` 可中途取消。

### 5.6 战斗与实体交互（进入世界后）

#### `player.attack`
对准并攻击最近的指定生物，走真实攻击管线（发 `ServerboundInteractPacket`）并挥臂，与真人左键等价。
跨帧方法：攻击后等服务端同步血量再给命中判定。超时 6 s。

| 名称 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `target` | string | `"zombie"` | 实体注册 ID，精确匹配 |

- 返回字段：

| 字段 | 含义 |
|------|------|
| `target` | 目标注册 ID |
| `distance` | 攻击时距离 |
| `reach` | 玩家实体触及距离（生存约 3 格） |
| `hpBefore` / `hpAfter` | 攻击前后目标血量（非 LivingEntity 为 0） |
| `hit` | 血量是否下降（命中判定） |

- 错误：无目标 -32002（`data` 含 `target`、`radius=32`）；超距 -32003（`data` 含 `distance`、`reach`）。
- 血量是服务端同步值，但极端快节奏下仍可能有延迟，关键判定建议脚本侧二次核对。

#### `player.attackNearest`
高级会话指令：tick 驱动自动寻路 → 进入触及范围 → 攻击。参数 `target`（默认 `zombie`，精确匹配）。
- 成功返回与 `player.attack` 相同的结构。
- 15 s 未完成返回 -32000 并清理会话；无目标 -32002；已有进行中的高层操作 -32004。

#### `player.interact`
右键（使用）最近的指定实体，走真实 `gameMode.interact`（发 `ServerboundInteractPacket(INTERACT)`），
用于打开村民 / 流浪商人等的交易 GUI。跨帧方法：右键后等开屏包到达再报告。超时 8 s。

| 名称 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `target` | string | `"zombie"` | 实体注册 ID，精确匹配 |
| `autoClose` | boolean | `true` | 判定完是否自动关闭已打开的界面 |

- 返回字段：`target`、`distance`、`reach`、`clientResult`（原版交互结果枚举名）、
  `screenOpen`（是否打开了 `MerchantScreen`）、`screenClass`（当前屏类名）、`offerCount`（报价数，未开屏为 -1）。
- 要驱动交易，调本方法时传 `autoClose=false`，再用 `trade.*` 系列。
- 错误：无目标 -32002；超距 -32003。

### 5.7 命令与聊天（进入世界后）

#### `player.command`
以玩家身份发送斜杠命令（等价聊天框输入 `/xxx` 回车），走真实聊天命令包。超时 3 s。

| 名称 | 类型 | 必传 | 说明 |
|------|------|------|------|
| `cmd` | string | 是 | 命令文本，不带前导 `/`（带了会自动去掉） |

- 可执行性取决于玩家权限；需要 OP 时测试脚本可先用 RCON 授权。
- `cmd` 空 / 空白 -32602。返回字符串 `已发送指令 /<cmd>`。

#### `player.chat`
以玩家身份发送普通聊天，走真实聊天包。超时 3 s。

| 名称 | 类型 | 必传 | 说明 |
|------|------|------|------|
| `msg` | string | 是 | 聊天文本 |

- `msg` 空 / 空白 -32602。返回字符串 `已发送聊天: <msg>`。

### 5.8 方块交互（进入世界后）

均走真实 `gameMode.useItemOn`（发 `ServerboundUseItemOnPacket`），与真人右键方块同路径，
服务端会做权威 raycast，故方法内部强制把视角对准目标面中心。

#### `player.useBed`
右键指定坐标的床尝试睡觉。超时 8 s。

| 名称 | 类型 | 必传 | 说明 |
|------|------|------|------|
| `x` | integer | 是 | 床方块 x |
| `y` | integer | 是 | 床方块 y |
| `z` | integer | 是 | 床方块 z |

- 返回：`bed`（`"x y z"`）、`distance`、`clientResult`、`isSleeping`（调用后客户端睡眠状态）。
- 原版前提：夜晚、床在触及范围内、附近 10 格无敌对生物。是否过夜由调用方轮询时间 / 睡眠状态。
- 超距 -32003；未进世界 / `gameMode` 未就绪 -32001。

#### `player.placeBlock`
对指定方块的指定面使用主手物品（放方块）。新方块落在被点击方块 `face` 的相邻格。
能触发依赖「玩家真实放置」的服务端逻辑（如凋灵图案检测）。超时 8 s。

| 名称 | 类型 | 必传 | 默认 | 说明 |
|------|------|------|------|------|
| `x` | integer | 是 | — | 被点击方块 x |
| `y` | integer | 是 | — | 被点击方块 y |
| `z` | integer | 是 | — | 被点击方块 z |
| `face` | string | 否 | `"up"` | `up`/`down`/`north`/`south`/`east`/`west` |
| `sneak` | boolean | 否 | `false` | 是否潜行放置（对着有交互的方块放东西时需要） |

- 返回：`clicked`（`"x y z"`）、`face`、`placedPos`（新方块 `"x y z"`）、`distance`、
  `clientResult`、`mainhand`（`empty` 或物品标识）。
- 物品需调用方预置到主手（如 RCON `/item replace ... with`）。
- 错误：非法 `face` -32602；超距 -32003；未进世界 -32001。

### 5.9 世界查询（进入世界后）

#### `world.entities`
列出周围指定半径内的生物（`LivingEntity`）。超时 3 s。

| 名称 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `radius` | float | `32` | 搜索半径（格） |

- 返回：`{"radius": 32.0, "count": N, "entities": [...]}`，每项
  `type`（注册 ID）、`x`、`y`、`z`、`distance`。
- `type` 可直接作为 `player.attack` / `player.moveToward` 的 `target`。

### 5.10 村民式交易 GUI

前提：先用 `player.interact`（`autoClose=false`）打开 `MerchantScreen`。未打开交易界面调用任何
`trade.*` 返回 -32001。这些是一帧式原语，多帧时序（等结果槽填充、等售罄同步）由调用脚本编排。

#### `trade.status`
读取当前交易菜单。超时 3 s。返回：

```json
{
  "offerCount": 2,
  "offers": [
    {"index": 0, "uses": 0, "maxUses": 16, "outOfStock": false,
     "costA": "minecraft:emerald", "costACount": 12, "costBCount": 0,
     "result": "minecraft:enchanted_book", "resultCount": 1}
  ],
  "costA": {"item": "minecraft:emerald", "count": 12},
  "costB": {"item": "", "count": 0},
  "result": {"item": "minecraft:enchanted_book", "count": 1},
  "inventory": {"minecraft:emerald": 24}
}
```

- `offers[].costA` 是真实要价（含 demand 修正；本 mod 乘数为 0 即配置价）。
  货币槽 `costA` 在选中报价时可能被客户端预测填入整组（64），**成交价以 `offers[].costACount` 为准**。
- `inventory` 聚合背包 + 快捷栏（槽位 3..38）的 `itemId -> 数量`，供成交后核对扣款 / 入包。

#### `trade.select`
选中第 `index` 条报价（等价点报价按钮：本地把货币挪入货币槽 + 发选择包）。超时 5 s。

| 名称 | 类型 | 必传 | 说明 |
|------|------|------|------|
| `index` | integer | 是 | 报价下标，从 0 开始 |

- 成功返回一次 `trade.status` 结构。下标越界 -32602。

#### `trade.take`
Shift-点击结果槽成交一次（走原版容器点击管线）：服务端扣款、出货、`uses+1` 并重发报价（售罄红叉同步）。
- 参数无；超时 5 s；返回一次 `trade.status` 结构。

#### `trade.close`
关闭当前交易界面，发容器关闭包（服务端才会真正结束交易状态）。
- 参数无；超时 3 s；返回 `已关闭交易界面`；当前无交易界面时返回 `当前无交易界面，无需关闭`。
- 必须用本方法（或 `player.interact` 的 autoClose）关窗；裸改 Screen 不发包会导致后续右键被「已在交易」门控挡住。

---

## 6. 调用示例

最小探测（bash + nc）：

```bash
echo '{"jsonrpc":"2.0","method":"client.status","id":1}' | nc 127.0.0.1 25586
```

Python 客户端骨架（newline JSON，按 id 匹配响应）：

```python
import json, socket

class Rpc:
    def __init__(self, host="127.0.0.1", port=25586):
        self.s = socket.create_connection((host, port))
        self.f = self.s.makefile("rwb", buffering=0)
        self._id = 0

    def call(self, method, **params):
        self._id += 1
        req = {"jsonrpc": "2.0", "method": method, "id": self._id}
        if params:
            req["params"] = params
        self.f.write((json.dumps(req) + "\n").encode())
        resp = json.loads(self.f.readline().decode())
        if "error" in resp:
            raise RuntimeError(f"{method} -> {resp['error']}")
        return resp["result"]

rpc = Rpc()
print(rpc.call("client.status"))
```

完整真机编排参考仓库脚本：

- `scripts/launch_and_connect.py` —— 启动客户端并连接服务器
- `scripts/verify_world_lifecycle.py` —— 建 / 进 / 列举 / 降级备份全流程（`--loader fabric|neoforge`）
- `scripts/real_device_test.py` —— 导航 / 连接 / 状态 / 攻击 / 移动 / 并发 BUSY 分阶段真机验证
- `scripts/combat_loop_test.py` —— 战斗闭环（走近 → 攻击 → 验证反击）

## 7. 方法速查表

| 方法 | 必传参数 | 超时 | 阶段 | 形态 |
|------|---------|------|------|------|
| `methods.list` | — | 本地即时 | 任意 | 同步（不进 render thread） |
| `client.status` | — | 3 s | 任意 | 同步 |
| `client.toTitle` | — | 3 s | 任意 | 同步 |
| `client.connect` | `host` | 8 s | 标题屏 | 异步发起 |
| `client.disconnect` | — | 3 s | 任意 | 同步 |
| `client.quit` | — | 立即 | 任意 | 直接退进程 |
| `world.list` | — | 30 s | 标题屏 | 后台异步读盘 |
| `world.create` | — | 120 s | 标题屏 | 同步加载 |
| `world.enter` | `id` | 130 s | 标题屏 | tick 会话（约 120 s） |
| `player.pos` | — | 3 s | 世界中 | 同步 |
| `player.state` | — | 3 s | 世界中 | 同步 |
| `player.respawn` | — | 3 s | 死亡屏 | 同步 |
| `player.look` | — | 3 s | 世界中 | 同步 |
| `player.move` | — | 3 s | 世界中 | 持续按键 |
| `player.jump` | — | 3 s | 世界中 | 持续按键 |
| `player.stop` | — | 3 s | 世界中 | 同步 |
| `player.moveToward` | — | 3 s | 世界中 | tick 持续 |
| `player.attack` | — | 6 s | 世界中 | 跨帧判定 |
| `player.attackNearest` | — | 15 s 会话 | 世界中 | tick 会话 |
| `player.interact` | — | 8 s | 世界中 | 跨帧判定 |
| `player.command` | `cmd` | 3 s | 世界中 | 同步 |
| `player.chat` | `msg` | 3 s | 世界中 | 同步 |
| `player.useBed` | `x,y,z` | 8 s | 世界中 | 同步 |
| `player.placeBlock` | `x,y,z` | 8 s | 世界中 | 同步 |
| `world.entities` | — | 3 s | 世界中 | 同步 |
| `trade.status` | — | 3 s | MerchantScreen | 同步 |
| `trade.select` | `index` | 5 s | MerchantScreen | 同步 |
| `trade.take` | — | 5 s | MerchantScreen | 同步 |
| `trade.close` | — | 3 s | MerchantScreen | 同步 |

\* `player.placeBlock` 注册等待超时为 8 s。
