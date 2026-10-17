# 客户端启动 / 连接 / 换 jar 的生命周期

## 启动入口：这是一个附加 mod

mc-client-rpc 是一个独立 mod，不是独立游戏。真正的 MC 客户端入口在**使用方工程**：

```
<被测/使用方项目>/
  └── <loader>/run/mods/                 # NeoForge dev: runClient 从这里读
        ├── <your-mod>-<v>.jar           # 被测内容（可选）
        └── mc-client-rpc-<v>.jar        # 本驱动 mod（从本工程 build/libs 复制）
```

- NeoForge：把本工程 `neoforge/build/libs/mc-client-rpc-<v>.jar` 复制到使用方 `neoforge/run/mods/`，再从**使用方** `:neoforge:runClient` 启动。RPC 在 mod 初始化时监听 25586。
- Fabric：`:fabric:runClient` 从 loom classpath 加载依赖，**不复制 jar**；跑的永远是最后一次构建产物。改了 common 必须先 `:fabric:build`，别把「忘重建」误判成平台差异。
- 本工程自己的 `runClient` 是**纯净客户端**：独立 architectury 工程，MC/NeoForge 版本独立，runtime classpath 只有驱动 mod + architectury。用于验「客户端不装被测 mod」类命题，不要拿它验业务。

## 标题屏状态机与 quickPlay 坑

- **不要给 runClient 配 `--quickPlayMultiplayer`**：资源/mod 未加载完就抢连，进服后又跳回标题屏，旁观无法判断状态。启动后停标题屏，由脚本主动 connect。
- 用 `scripts/launch_and_connect.py`：等 25586 就绪 → `client.toTitle`（跳过 AccessibilityOnboardingScreen 等拦路屏）→ 标题屏连续稳定 ~5s（`screen=='TitleScreen'`）→ `client.connect`。`--no-connect` 只起不连。
- 若 RPC 一直不起、日志 0 字节：detached 进程没真正跑起来。改后台 PTY 直接 `./gradlew :<loader>:runClient`，再用等待脚本（就绪→标题稳定→connect）接管。
- **拦路界面**：dev 环境先弹 AccessibilityOnboardingScreen，`client.toTitle` 主动跳过；DeathScreen 需 `player.respawn`。
- 死亡复活落在世界出生点而非测试位置，复活后要重新 tp/走回测试点。

## status 结构变化导致库脚本误判

`client.status` 返回结构化 dict（如 `screen`/`inWorld`/`connected` 英文字段），不是中文句子。旧脚本按中文文案（"标题屏"等）做字符串匹配，结构一改匹配恒 False、误报「初始化异常」，其实 RPC 是好的。

- 判定一律读结构化字段，不做文案字符串匹配。
- 不一定要改库脚本：外包一层包装，import 后轮询 `client.status` 的字段自判稳定，再复用它的 connect/等待逻辑；库以后更新判定也不受影响。

## 换本工具 jar 的顺序（Windows 文件锁）

运行中的客户端锁住已加载的 jar，直接 cp 覆盖必失败。顺序固定：

```
1. 构建（common 改动 → :neoforge:build 与 :fabric:build 各一次）
2. client.quit 正常退出（25586 关闭即已退出，锁释放）
3. 复制/覆盖 jar（NeoForge run/mods）；Fabric 无需复制
4. 重新拉起客户端
```

## 停客户端：优先 client.quit

- **首选 RPC `client.quit`**：实测干净退出、进程自行结束、不动存档。
- 兜底（RPC 不可达/挂死）：
  - 找 PID：`netstat -ano` 过滤 `25586` 的 LISTENING 行。Python subprocess 读这条命令的输出必须 `encoding="gbk"`（中文 Windows 控制台默认 GBK）。
  - 终止：在 `.py` 脚本里 `os.kill(pid, signal.SIGTERM)`（免审批）。不要在 git-bash 手敲 `taskkill //PID`——MSYS 会转换参数、无效且弹审批窗。
- 只杀客户端 java，保留 Gradle daemon。多个 runClient/构建会抢 architectury-transformer 缓存锁，只开一个客户端，kill 后确认大内存 java 进程清干净。
- VS Code 的 redhat.java（jdt.ls）会长持 fabric-loom mappings.jar 导致 Gradle 配置阶段锁；让用户临时整个关掉 VS Code，别反复杀单个 jdt.ls。

## 每轮即关

MC 客户端空放也吃资源。每轮真机验证做完立即 `client.quit`，即使马上还有下一轮也先关、下轮重拉（约 1 分钟），比空放几十分钟便宜。验证脚本的 finally 关客户端不可省；连续多用例也拆成每轮验完即关。
