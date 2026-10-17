---
name: "mc-real-device-testing"
description: "用 mc-client-rpc 做 MC mod 真机验证：时间/NBT/几何/命令模拟陷阱与真机配方。"
---

# MC mod 真机自动化测试方法论

用真实客户端输入驱动玩家，在**真实服务器 + 真实机制**上验证 mod 行为，而不是在单测里模拟。配套工具是 `mc-client-rpc`（姊妹技能，讲连接协议与客户端操控）；本技能讲「怎么设计真机验证才不被假证据骗」。

## 第一原则：命令模拟和真机制有差距，单测全绿≠真机对

这是全部坑的总根源。`/damage`、`/summon`、`/setblock`、服务端直调方法都在**绕过**真实玩家链路，会跳过服务端事件总线、结构图案匹配、物品回调等环节。典型后果：

- `/damage` 造的 `DamageSource.getEntity()` 不是 `Player`，且可能绕过 `LivingHurtEvent`——挂在「玩家攻击」上的 mixin/判定根本不执行（详见 `references/command-vs-real-input.md`）。
- `/setblock` 不触发方块放置回调；`/summon` 直接实体化，跳过结构图案 `checkSpawn` 和出生阶段。
- 这些命题纯 JUnit 测不出，**必须真机打一次**。能用 runClient + 脚本真机验证，就不要说「无法真机测试」。

判断标准：要验证的是「玩家真实操作 X 时系统如何响应」，就必须走真实输入路径（客户端 RPC 的 attack/interact/useItemOn/键映射），服务端命令只用于布置场景和读状态。

## 判据以服务端权威为准，客户端回读只当信息项

- 攻击是否命中：客户端 RPC 返回的 `hit` 是延迟若干 tick 后回读的**客户端预测血量**，同帧几乎恒 false，Fabric 上还有血量同步时序假阴性。以服务端 `data get entity <e> Health`（`limit=1`）+ 玩家实际掉血 + 被测 mod 的服务端状态为准。
- 玩家本地血量发包后不立即更新；「玩家没掉血」要区分真被拦截 vs 怪还没进入攻击条件，必要时用「打一拳触发反击」做阳性对照。
- 客户端同帧读不到跨 tick 才到的状态：开屏包、血量同步都晚一帧。右键后登记 pending，等约 5 tick（~250ms）再读 `mc.screen`/血量组装结果。
- 服务端探针与客户端现象矛盾时**信服务端**（如实体被 tp 没了客户端留虚影，攻击照报距离却打空）。

## 时间机制：先分清判据用哪个时钟

- `getGameTime()` 单调，睡觉和任何 `/time` 都不改它；`getDayTime()` 才被睡觉与 `/time` 快进（同走 `ServerLevel#setDayTime`）。
- 以 gameTime 为准的判定（冷却、固定间隔）：把配置周期临时调小（如 1 天→约 6 秒），实机等过周期验在线路径。
- 以自然日/dayTime 为准的判定：用 `/time add 24000` 定向驱动，增量可靠保证 day+1 而 gameTime 几乎不动。探针**同时回显两个时钟即成双判据**（day+1 且 gameTime 推进≪24000 ⇒ 走的是自然日腿）。
- **不要用 `/time set` 验跨天**：它是绝对值跳跃，天数可能倒退。整数天/边界的纯逻辑交单测。
- 真人睡觉过夜见 `references/real-sleep-overday.md`（SleepTimer 判据、daytime 回卷、锚点时序）。

## 验持久化/NBT 往返要真卸载区块

只在原地等，区块不卸载，验不到存档路径。把玩家 tp 到远处（如 512 格，落点先铺地板），轮询服务端探针直到 loaded 实体里找不到目标（=区块已存盘卸载），再回来读状态。「tp 后探针找不到」本身就是卸载完成的判据，不是错误。重置离线玩家存档时，`playerdata/<uuid>.dat` 和回滚留下的 `.dat_old` 要一起移走，否则服务端仍读到旧档。

## 几何与定点：NoAI、距离窗口、定身纪律

- **定点首选 `NoAI:1b` 召唤**：怪不漂移、不扑腾、不窒息，几何锁在整数格。小碰撞箱用 1 格高封闭隔间，大碰撞箱用 NoAI 开放平台。但**要测 AI 反击/寻路时绝不能 NoAI**——连攻击 goal 一起停了；改用服务端每 ~0.8s 把怪 `tp` 回玩家身侧固定锚点。
- **距离类断言卡在「自身阈值 < 距离 < 原版属性」窗口**：任何「拉远触发某距离判定」先查目标生物的 `FOLLOW_RANGE` 等属性，把测试距离夹在 mod 阈值与原版值之间。例如要触发 >32 的脱战，而僵尸 follow_range=35，tp 到 45/36 会让原版 `NearestAttackableTargetGoal` 当 tick 清空 target，正确距离是 34。
- **边界值不要贴线**：球形/方形距离判定有实体碰撞箱中心 ±0.5 浮动，硬断言距离离配置阈值至少留明显差值（经验上 ≥2 格）。阈值 4 格就用 2 格（必中）和 8 格（必不中），不要用 4。
- **减速定身只冻观察者，不冻要出手的攻击者**：给所有怪上高倍 slowness（如 255）会把攻击者移速压残，击退后冲不回近战范围，造成「反击掉血」假阴性。同族示警在受击瞬间按出生位置判定、距离脱战在 tp 当 tick 判定，都不需要冻怪。真要固定多怪位置，只冻纯观察用的怪，被打者保留全速（或召贴脸 dx=0 让击退后仍在近战范围）。
- 攻击/交互目标放玩家约 1.5 格外：distance 0 时射线选不中实体。多个同类时，要打的目标必须比其他个体更近，否则 attackNearest 会选错。
- **反作弊**：`allow-flight=false` 下不要 tp 玩家到高空（y=300 判浮空）或基岩层下（y=-60 判 Flying is not enabled）。短距离、同高度、落点有地板的 tp 可靠；配合关自然刷怪 + 清场隔离，不存在「传送到隔离盒」的方案。清平台别在 finally 拆玩家脚下地板（会摔出世界断连），只清上方残块或先把玩家 tp 到落脚点。

## 召唤特殊生物：/summon 不是万能的

- **监守者（Warden）不能 /summon**：summon 出的约 5s 后钻地自灭（生成原因非 TRIGGERED，跳过 emerging 状态机），NoAI 也挡不住。自然召唤：直道放 `sculk_shrieker[can_summon=true]`（blockstate，用 `execute if block` 判定），玩家在直道上**直线来回走动**攒 STEP 振动（贴墙画圈无位移不触发；周期性 tp 不产生 STEP 会打断；瞬时 180° 掉头触发移动去同步会被橡皮筋拉停）。warning 1→4 约 40s，首个有效尖叫前常有 15–20s 静默（方块 warning 显示 0 别误判）。钻出期间只 tp 玩家保位，绝不 tp 怪。前置 `gamerule doWardenSpawning true` + 非和平难度。平台铺**三层厚**石板，否则它会钻到薄板下面。自然召唤的没有 CustomName，钻出结束后再 `data merge` 命名并**回读** `execute if entity` 确认（merge 回显成功≠落地），写重试循环。
- **凋灵（Wither）等结构图案生物**：`/summon` 跳过 BlockPattern，没有 Invul≈220t 无敌倒计时、没有出生爆炸和侧头索敌阶段。要验真实出生路径用 `player.placeBlock` 放最后一个骷髅头（走真实 `useItemOn`，触发 `setPlacedBy`/`checkSpawn`）。出生爆炸炸死附近玩家是「爆炸保留」的判据而非事故，但后续用例前要复活满血。
- **小史莱姆 Size:1 只有 1 血**，命中即死并分裂、命名实体消失；依赖「死亡时已广播的状态」而非目标存活，或换大个体/NoAI。

## 原版报复链会掩盖/冒充被测行为

Goal 系生物受击后的同族通知半径 = 其 `FOLLOW_RANGE`（僵尸 35、烈焰人 48 等，远大于大多数 mod 自定义半径）。验「mod 的小范围示警」时，8 格外的怪被惊动**不一定是 mod 的问题**，可能是原版大范围链。完整 A/B/C 分类（哪些生物原生有 HurtBy/setAlertOthers、半径多少）见 `references/vanilla-retaliation-chains.md`。设计用例时：

- 用「同几何阳性对照 + 阴性对照」闭环：同站位下，未改造个体应触发（target=player），改造个体按预期 NONE，单边证据两头解释都通。
- 半径类判据一次摆两组（≤r 有警、>r 无警）同时坐实边界。
- 非玩家攻击对照（验「只有玩家触发」）可用铁傀儡当攻击者，挨打的怪给抗性 V；`/damage` 不走上玩家链路不能用于此。

## 命令与参数陷阱（真机才暴露）

- **Brigadier 收命名空间 id 用 `ResourceLocationArgument.id()`，不能用 `StringArgumentType.word()`**：word() 的无引号合法字符是 `[0-9A-Za-z_.+-]`，不含冒号，读到 `minecraft:zombie` 的冒号就断词，整条命令解析失败、静默不执行。注册成功但真机才暴露。
- **跨 loader 脚本玩家一律用 `@s`**：NeoForge dev 玩家名可能是 `Dev`，Fabric loom 是 `Player###`。写死用户名在另一平台全部静默失败（命令报错被吞），连锁导致站位漂移、没吃到效果死亡、后续阶段全污染。用客户端 RPC 以玩家身份发命令时 `@s` 就是自己，跨 loader 通用。
- **清空手必须 `item replace entity @s weapon.mainhand with air`**：写空列表 `[]` 回显成功但实际不清空，空手门控类用例假失败。offhand 同理，清完用 `data get entity … SelectedItem` 旁证。
- **`data get` 报错语义**：`@e[…]` 无 limit 匹配多个报 "Only one entity is allowed"（是多不是零）；"Found no elements matching Pose" 是该实体 NBT 无此字段（监守者无 Pose）不是没实体。一律 `limit=1`，实体在不在用 `execute if entity` 判。监守者等的状态读 Brain memories（is_emerging 等）不读 Pose。block 实体字段读完整 NBT 后本地正则，子路径读法不稳。
- **效果给实体用 `effect give` 命令**，不要内联 ActiveEffects NBT。
- **moveToward 的参数是实体选择器不是坐标串**，传 "x y z" 报无此实体；玩家定位/撤退用 RCON tp（铺好地板），接近攻击用 attackNearest。
- **yaw 朝向**：90=朝西(-x)、-90=朝东(+x)、0=朝南(+z)、180=朝北(-z)。站位在目标东侧要 yaw=90 才面向它；attackNearest 不依赖朝向可绕开。look 是瞬时设视角，大幅急转触发移动去同步会被拉停，要转向持续步行用小步平滑转。
- **Brain.getMemory 读未注册的记忆会抛 IllegalStateException**（不是空 Optional；Goal 系如僵尸没注册 ATTACK_TARGET）。读前先 `brain.hasMemoryValue(T)`，跨主线程查询体 try/catch 兜底。

## 环境与状态卫生（防污染）

- **超平坦/白天召唤的亡灵会晒阳烧死**：建世界后 `time set midnight` + `gamerule doDaylightCycle false` 锁住；轮询对「实体暂时找不到」容错。
- **开场先查玩家死亡**：上轮可能已把玩家打死，HP 0 时移动/攻击全失败且报错误导。多用例套件每个用例开头强制复活 + 读 HP 满才开测；「未掉血」断言先记录基线 HP>0（尸体 heal 无效，HP 0→0 的「未掉血」会空洞判 PASS）。同批怪 kill 后重召，不复用上一例残血个体。
- **药水效果跨阶段残留**：上一阶段的抗性等效果进下一阶段会免伤，让「HP 未掉」成空洞断言。效果只存在于需要它的阶段，判定前 `effect clear` 并读回确认；保命抗性下命中判据以状态探针为准、掉血只作旁证。
- **临时改服务器配置**：数值/布尔项改配置文件（读回确认）+ 热重载，开始前备份、finally 恢复。注意很多入世界时的中立化/标记**只对之后新召唤的实体生效**，改完要重召。setup 里每改一处（gamerule/time/方块/配置/名单）当场在 finally 登记还原，别靠事后回忆。
- 任务卡/文档骨架可能停在上个迭代节点：开工先 `git log --oneline` 对照现状，过期的重写而不是照做。

## 探针纪律（使用方自带的调试通道）

- **先把判据字段在探针里写全再部署**：真机判定依赖哪些时钟/状态（如双时钟要同时读 gameTime 与 dayTime），先加字段再跑。跑到一半发现缺字段意味着重构建+重部署+重跑一整轮。
- **Gson 输出可空字段必须归一成非 null**：Gson 默认不序列化 null，map 里 put("x",null) 的 key 会从 JSON 消失，脚本下标 KeyError。用空串/哨兵保证有/无状态下 key 集合恒定。
- 探针遍历实体必须切服务端主线程（`server.execute`）+ 用 CompletableFuture 同步等（超时兜底）。
- 没有真实交互入口时可用探针直接改内部状态模拟（如把报价标记为售罄），但必须标注「真实链路待端到端复验」，别把模拟态当真实验证。
- **探针只用于读状态和二分定位，不替代真实输入**：右键等交互必须走 `player.interact`，不能探针直调 openMenu 代替。

## 交易 GUI 测试（村民/自定义商人）

- 关交易窗必须发包：`trade.close`/`player.interact(autoClose=true)` 内部走 `player.closeContainer()`（发 ServerboundContainerClosePacket）。裸 `mc.setScreen(null)` 只做本地清理不发包，服务端菜单不 removed、`tradingPlayer` 一直非空，下一次右键被「已在交易」门控挡下（第一次能开、关后再也开不了、无异常日志即此坑）。
- **成交价读报价的 cost 字段，别读被填满 64 的货币槽**：客户端选报价时会把货币槽尽量填满一整组 64，服务端只按真实报价 shrink，多放的关窗退回。测试顺序：空手 interact 开窗 → RCON give 货币 → select → take；重开前双手 `… with air` 清空（offhand 也会被退到）。
- **`trade.take` 是 shift-click（QUICK_MOVE）结果槽**，会循环成交到材料耗尽或达 maxUses。要确定性单买就把测试报价 NBT 设 `maxUses:1`。扣款口径 = 成交前货币 −（成交后背包 + 两个 cost 槽三处合计），留在槽里的残量不算扣。
- 报价成本通常是扁平字段（costA 物品 id + costACount/costBCount），当嵌套对象 `.get()` 会 AttributeError；三槽才是嵌套 `{item,count}`，以 `trade.status` 实际返回为准。

## 双端（NeoForge + Fabric）都要真机

业务逻辑与 mixin 常在 common 同源，但「能构建」≠「跑通」，Fabric 真机独立验证。手法（两台服互不干扰）：

- 独立目录 + 错开游戏/RCON/调试端口；`online-mode=false`、`enforce-secure-profile=false`、超平坦。
- Fabric 服用官方 installer 装；mods 放 fabric-api、架构层 mod、被测 remap jar。需要调试探针的包要显式开构建开关，并通过 JVM 属性错开探针端口。
- 客户端从 **mc-client-rpc 工程**跑对应 loader 的 runClient（首次冷启动要下客户端依赖）。启动/连接参数走环境变量（如 `MCRPC_SERVER_HOST`、`MCRPC_CLIENT_PROJECT`；工具脚本已不内置默认服地址，缺必需变量会显式报错而不是静默导向某个环境）；若用外包等待脚本，沿用 RPC 就绪 → 标题屏稳定 → connect 的时序。
- **复用现有用例脚本不改仓库代码**：写 runner 用 importlib 逐个加载各用例的 main()，注入通道（RCON 端口、配置路径、探针地址都改成模块级变量注入），业务脚本零改动；runner 支持命令行只跑指定用例便于失败补跑。
- 验完 RCON `stop` 优雅停副服（目录保留）、`client.quit` 关客户端、轮询副服端口全关并确认主服仍在。

## 真机脚本写法

1. 写成独立 `.py`（write_file），终端只跑 `python xxx.py`；不内联复杂管道或 `python -c`。一次性脚本放 `_temp/`（git 忽略）。
2. 每步带断言，失败即停并指明卡在哪步；退出码 0=PASS、非 0=失败。
3. **自闭环时序**：等待/轮询/状态监测全在脚本内，只读最终结果，不在两个工具调用之间靠外部循环。先从字节码/配置算出机制总时长，观察窗覆盖完整行为周期并留 30%+ 余量（反扑类要算上怪重新接近的时间，不能只按「打完到掉血」的直感设窗）。
4. try/finally 清理：关客户端（client.quit）、杀召唤实体、还原每一处 gamerule/时间/方块/配置/名单。只杀客户端、保留测试服和 Gradle daemon。
5. 支持 `--attach` 复用已启动客户端调试；默认全自动起停（可进 CI）。

## 参考

- `references/command-vs-real-input.md` —— `/damage` 等命令为何绕过玩家攻击链路，attack/attackNearest 的真实路径
- `references/geometry-and-isolation.md` —— NoAI 定点、距离窗口、清场隔离、attackNearest 状态机与门槛缓冲
- `references/vanilla-retaliation-chains.md` —— 原版 Goal 系生物报复链 A/B/C 分类与 FOLLOW_RANGE（源码核实）
- `references/real-sleep-overday.md` —— 真人上床过夜验跨自然日机制
- `references/verification-ladder-fakeplayer.md` —— RCON/FakePlayer/服务端探针/真实客户端升级梯，FakePlayer 字节码事实，RCON 与玩家命令边界
