---
name: "mc-client-rpc"
description: "mc-client-rpc 操作：RPC 连接、生命周期、RCON 部署；转公开脱敏门禁。"
---

# mc-client-rpc 操作手册

在 Minecraft **客户端进程内**开 JSON-RPC 服务，外部脚本用它驱动一个真实连入服务器/存档的玩家：走位、攻击、跳跃、转头、右键、发命令、开交易。走的是**原版真实输入管线（发包）**，不是服务端伪造状态——这是它与服务端命令/探针的根本区别。

> 本手册讲「怎么用好这个工具」。用它做 mod 真机验证的领域方法论（时序判据、命令模拟陷阱、生物机制坑）在姊妹技能 **`mc-real-device-testing`**。

## 权威文档（先看这些，勿在技能里复制）

仓库根的工程文档是单一真相，技能只放引用和实操要点：

- `README.md` —— 定位、能力总览、错误码表、构建/运行、安全约束
- `docs/rpc-api.md` —— **RPC 方法契约的唯一权威**（548 行，每方法参数、返回字段、状态机、超时）。方法签名以它为准，技能不复制逐方法细节
- `docs/thread-model.md` —— render thread 任务队列模型
- `docs/input-injection.md` —— 为什么驱动按键映射而非直接改 impulse
- `docs/testing.md` —— 真机脚本组织约定
- 运行时也可发 `methods.list` 自描述获取参数 schema

## 快速上手

```bash
# 构建双端 jar；runClient 启动后停在标题屏（离线名 Dev，854×480），等脚本 connect
./gradlew :neoforge:runClient      # 或 :fabric:runClient
```

协议是**换行分隔的裸 JSON-RPC 2.0**（不是 HTTP！自写 POST 会在 recv 永久阻塞）：

```python
import socket, json
def rpc(method, params=None, timeout=30):
    s = socket.create_connection(("127.0.0.1", 25586), timeout=timeout)
    req = {"jsonrpc": "2.0", "method": method, "params": params or {}, "id": 1}
    s.sendall((json.dumps(req) + "\n").encode())
    f = s.makefile("r", encoding="utf-8", newline="\n")
    line = f.readline()          # 读一行响应后即可关
    s.close()
    return json.loads(line)

rpc("client.status")
rpc("client.connect", {"host": "127.0.0.1", "port": 25565})
rpc("player.pos")
```

完整可复用封装直接 `sys.path.insert` 引仓库脚本：`from combat_loop_test import rpc, server_rcon`（在 `scripts/` 下）。**连接参数（host/port/密码）一律显式传或走环境变量，不要依赖脚本里的任何默认主机**——通用工具不得把某个测试环境写死成默认值，忘传时会被静默导向无关环境。

## 三条通道，分工不要混

| 通道 | 默认端口 | 干什么 |
|---|---|---|
| 客户端 RPC（本工具） | 25586 | **真实玩家输入**：走/打/跳/视角/右键/命令/交易 GUI |
| 服务器 RCON | 25575（MC 默认） | OP 管理：召唤、清场、改 gamerule/配置。用 `scripts/rcon.py`（纯标准库 Source RCON） |
| 上层被测系统的调试探针（**非本工具**，使用方自带） | 自定义 | 读被测 mod 内部状态。本工具不提供也不假设它存在 |

右键/攻击/放块等交互必须走客户端 RPC 的真实路径（`gameMode.interact/attack/useItemOn`），不能用服务端探针直调 `openMenu` 之类代替——那只用于二分定位，不代表真玩家链路。

## RPC target 用裸 id

`player.interact/attack/attackNearest` 的 `target` 用**不带命名空间的裸 id**（`"zombie"`，不是 `"minecraft:zombie"`），内部在 32 格选最近同类。开场要 `kill @e[type=...]` 清野生同类，并用固定隔间（玩家在格中心 .5、怪在相邻整数格、定点），别用 `round(pos)` 现算（银行家舍入 + 野生实体干扰会超距/选错）。RCON 命令里的选择器仍用带命名空间的 `minecraft:xxx`。

## 客户端生命周期（重连/换 jar 必读）

详见 `references/client-lifecycle.md`。要点：

- **runClient 加 `--quickPlayMultiplayer` 会抢连跳屏**：资源/mod 没加载完就进服又被弹回标题。build.gradle 不配 quickPlay，启动停标题屏，用 `scripts/launch_and_connect.py`：等 RPC 就绪 → `toTitle` → 标题屏连续稳定 5s → connect。
- **状态判定读结构化字段，别匹配中文文案**：`client.status` 返回 dict（`screen`/`inWorld`/`connected` 等）。库脚本若按旧版中文字符串判「标题屏稳定」，status 结构一变就恒 False、误报 RPC 坏了。直接轮询 `screen=='TitleScreen'` 自判。
- **换本工具的 jar 顺序**：运行中的客户端锁住已加载 jar（Windows 文件锁），直接覆盖失败。顺序 = 构建 → `client.quit` 正常退出 → 覆盖 jar → 重拉。common 改动要 `:neoforge:build` 和 `:fabric:build` 各一次。
  - NeoForge 客户端：jar 复制到客户端 `mods/` 目录；
  - Fabric `:fabric:runClient`：从 loom classpath 加载，**不复制 jar**，跑的永远是最后一次构建——先重建再下结论，别把「忘重建」误判成平台差异。
- **停客户端优先 `client.quit`**（干净退出、进程自行结束、不动存档）。仅 RPC 不可达才兜底：`netstat -ano` 找 25586 LISTENING 的 PID（subprocess 读输出用 `encoding="gbk"`），脚本里 `os.kill(pid, SIGTERM)`，别在交互式 shell 手 taskkill（MSYS 转换 `//PID` 无效还触发审批弹窗）。
- 每轮真机测完立即关客户端，别空放吃资源；即使紧接下一轮也先关、用 launch_and_connect 重拉（约 1 分钟）。

## 缺原语就加，别绕路

客户端 RPC 缺操作（如 placeBlock）时直接给本工具加原语：写 Java（common 侧注册）→ 构建 → `javap` 确认类进 jar → quit/覆盖/重启。重启是分钟级成本，但为省重启把测试绕成 `/setblock`、`/summon`、`/damage` 等命令模拟，等于放弃真机路径——这些命令绕过服务端监听（`setPlacedBy`、结构图案 `checkSpawn`、攻击事件总线/mixin），验的是另一个命题。

加原语的实现纪律（详见 `docs/thread-model.md`）：

- MC 客户端状态只能在 render thread 访问；网络线程的请求经任务队列切到 `ClientTickEvent.CLIENT_POST` 执行。
- **绝不在 render thread 上 `future.get()` 阻塞等死锁**。跨多帧高级指令（attackNearest）：render thread 上只做「瞬间设目标」立即返回，后续 tick 驱动寻路/攻击，RPC 线程在外侧等 future。
- moveTarget 到达门槛要比 attackTarget 触发门槛留缓冲（如 reach-0.5 vs reach-0.2），否则刚到就停、永远触发不了攻击。

## 错误码（速查，完整以 README/rpc-api 为准）

-32700 解析失败（含批量数组）｜-32600 无效请求（缺 method/params 非对象）｜-32601 未知方法｜-32602 参数非法｜-32000 超时｜-32001 未就绪（未进世界/被界面拦/没开交易屏）｜-32002 目标或存档不存在｜-32003 超出触及范围（`data` 含 distance/reach，先 moveToward）｜-32004 操作忙（同类高级会话进行中）｜-32603 其他内部异常。业务错误区间 -32000~-32099。

## 作为附加 mod 接到任意客户端

本工具是独立 mod：把构建出的 `mc-client-rpc-<v>.jar` 放进被测客户端 `mods/`，从**那个客户端**的入口启动即监听 25586。不要从本工程的 runClient 去验业务（本工程 runClient 的 classpath 只有驱动 mod + architectury，是纯净客户端）。

- 「装了被测 mod 但客户端分支零干预」与「纯原版客户端」是两个命题。后者可用本工程自己的 runClient（runtime classpath 无被测 mod）连对方服务器复验。
- 服务**默认只绑 127.0.0.1、无鉴权**，是测试设施：任何本机能连 25586 的进程都能操控角色。勿暴露公网；远程连需显式改绑并自带网络防护。

## 参考

- `references/client-lifecycle.md` —— 启动/标题屏状态机、quickPlay、jar 替换、停客户端、纯净客户端模式
- `references/rcon-direct.md` —— Source RCON 直连脚本用法、密码处理
- `references/deploy-and-verify.md` —— 构建 jar 部署到测试服的一键流程与防假通过核对
- `references/public-release-scrub.md` —— 转公开仓库前的脱敏验收门禁（含扫描脚本 `scripts/scrub_scan.py`：通用模式内置，项目私值走 `--patterns-file`、清单勿入仓库）
