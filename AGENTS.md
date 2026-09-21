# AGENTS.md

AI 代理（Hermes / Claude Code 等）在本仓库工作时读这个文件。

## 项目是什么

mc-client-rpc —— Minecraft 客户端 JSON-RPC 自动化测试框架，Architectury 双端（NeoForge + Fabric）。
在 MC 客户端进程内开 RPC 服务（端口 25586），调用客户端真实输入 API，让客户端真的发攻击/移动包，
走完整的 输入→网络→服务端 管线。与「服务端直接伪造状态」的验证方式互补。

- 路径：`D:/Coding/projects/mc-client-rpc`
- MC 1.21.1 + Java 21，NeoForge 21.1.250，modid `mcclientrpc`
- 构建参数以 `gradle.properties` 为准；Gradle 版本同 wrapper，不要升 9.x

## 职责边界（重要架构约定）

- **mod 只管**：窗口导航、暴露 RPC、维护任务队列与状态查询、提供 aimAt 等辅助方法。
- **OS 级按键/鼠标模拟放外部测试脚本侧**（如 pywin32 发窗口消息），不进 mod：
  真实度最高、不依赖 MC 版本、平台相关代码不污染 JAR。
- 客户端状态（player/world/攻击）只能在 render thread 访问：RPC 请求从网络线程进来，
  一律经任务队列切到 `ClientTickEvent.CLIENT_POST` 执行，RPC 线程阻塞等 future。
- 禁止在 render thread 上阻塞等 future（死锁：tick 永远不会执行）。

## 常用命令

```bash
./gradlew build
./gradlew :neoforge:genSources   # 读 MC 方法体前先跑
./gradlew test
./gradlew :neoforge:runClient
```

## 文档

- RPC 方法清单、自动化导航链（toTitle → connect → 轮询 status → respawn）见 `README.md`。
- 通用方法论（JSON-RPC 线程模型、输入模拟四种方案对比、验证策略决策树）在
  `minecraft-mod-development` skill 的 references 里。
- 测试脚本写 `.py` 文件放 `scripts/`，终端只跑 `python xxx.py`。
- commit message 用中文：根因/修法/副作用。
