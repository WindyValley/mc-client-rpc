# 部署构建产物到测试服并核对

把本地构建的 mod jar 推到远程测试服、重启加载，并确认跑的真是这次构建——整套流程写成一个 `deploy_xxx.py` 一键完成，避免手工漏步。

部署脚本绑定目标主机/目录/jar 名，属**使用方私有侧**：放在被测项目的仓里，通用工具仓只保留与业务无关的库（如 RCON 客户端）。私有脚本复用通用库时，在本脚本 `import` 通用库**之前**注入私有默认环境变量（库在 import 时固化默认连接参数），项目路径用 `__file__` 相对定位、跟仓库走。

**迁出还是留、按证据分不按文件名猜**：查脚本的 runClient/gradlew/PROJECT_ROOT 指向谁、断言的行为是否依赖被测 mod（召唤的实体只是通用靶子=工具能力测试）。模板类脚本同目录 import 工具库、用工具仓自己的 runClient，物理迁走会拉断这些路径——留在工具仓只参数化 IP/路径；只有绑定私网主机/远端目录/被测项目名的部署脚本才整只迁到被测项目仓。改库默认值时保持函数签名向后兼容（位置参数不动、只换默认值来源），迁出副本和既有调用点才不用跟着改。

## 六步流程

```
1. 本地构建（使用方工程的 gradlew <loader>:build，必要时 -x test）
2. RCON save-all + stop，优雅关服（stop 断连是预期，吞掉）
3. 确认进程/端口已关（见下方判活陷阱）
4. scp 上传，整文件 sha256 本地=远端核对
5. ssh setsid 后台启服（detach，stdin/stdout/stderr 全部重定向）
6. 轮询游戏端口（+ 任何调试端口）监听、RCON list 上线
```

后台启服命令形如：

```python
ssh("cd <server-dir> && setsid sh -c 'sh run.sh nogui > server_stdout.log 2>&1 < /dev/null' &")
```

## 判活陷阱

- **pgrep 不要 `-f` 匹配命令行内容**：`pgrep -f 'neoforge/.../unix_args.txt'` 会匹配到**发起查询的 ssh 进程自身**（它的命令行里也含这个字符串），MC 早关了却每次都查到新的 ssh，死循环。用 `ss -ltn` 看端口，或 `pgrep -x java` 精确匹配进程名。
- **ssh 启动命令超时 ≠ 启动失败**：setsid 已 detach，但 ssh channel 可能被 Java 继承的 fd 占住不返回，客户端 15s 超时报错而服务其实在正常启动。吞掉该超时，以端口监听为唯一判据。

## jar 名从 gradle.properties 读，别写死

写死 jar 名，版本一改脚本仍传/查旧名；服务器一直跑旧代码，「大小一致」是旧文件对旧文件的假通过，真机验证全部作废。

```python
def read_mod_version(project_dir):
    for line in (project_dir / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.strip().startswith("mod_version"):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("gradle.properties 中找不到 mod_version")

jar_name = f"<modid>-{read_mod_version(project_dir)}.jar"
```

上传后、重启前删掉服务器 mods 里的其他旧版本（两版并存加载行为不可预期）。

## 部署后核对（防假通过）

- **比对对象必须是本次构建产物**：先确认本地 `build/libs/<jar>` 的 mtime 就是刚才那次构建写的，再和远端比；拿 build/libs 里的陈旧文件比=假通过。
- **本地/远端 sha256 必须相等**，不只看大小。
- 需要确认某个类/特性真在 jar 里：远端无 unzip 就用 `python3 -c "import zipfile; ..."` 列条目核对。
- 端口监听 + RCON `list` 上线 + 对任何调试端口探一发（应返回结构化响应而非拒绝连接）。

## 测试专用开关必须默认关，并核实被常量折叠

调试/验证用的功能（如随服启动的调试 RPC）无条件打进正式 jar 有安全风险。做法：

- 用构建开关（如 `-PenableXxx=true|false`，默认 **false**）控制，关闭时常量折叠消除注册。
- 部署/真机构建显式带 true；**正式发布包必须是 false**。
- 核实只信 **jar 内字节码**，别信 `generated/sources` 下的源码（生成任务重跑≠下游重编，UP-TO-DATE 会掩盖）：
  ```bash
  unzip -o <jar> path/to/BuildFlags.class -d tmp
  javap -constants -classpath tmp path.to.BuildFlags   # 读常量真值
  ```
- **常量池里出现类/方法引用 ≠ 代码未被折叠**：无条件 getter 与永不注册的 lambda 也会保留类型引用。权威判据是 `javap -c` 反汇编注册入口方法，数方法体内的调用指令数——折叠干净应为 0。顺带 `unzip -l` 确认本次新增类确实进了 jar。

## Windows subprocess 跑 gradle

Python subprocess 不能直接跑 shell 版 `./gradlew`，用 `["cmd", "/c", "gradlew.bat", ...]` 并传 `cwd=<项目根>`。注意区分：在 git-bash 终端里手敲用 `./gradlew`；`cmd //c gradlew.bat` 在 git-bash 里只会开一个交互式 cmd、什么都不执行。

## NeoForge installer 并发下载 connection-refused

NeoForge installer 并发下载约 49+ 个库文件，部分服务器（含住宅 ISP 后的普通 Linux VPS）会间歇拒绝到 Maven 仓库的并发连接（`java.net.ConnectException: Connection refused`）。这不是断网——单个 curl 能下。症状：installer 下载 `libraries/` 部分内容（如 21/49）后报 "These libraries failed to download"。

修法（串行下载器）：

1. installer 跑一次让它失败并写日志（`neoforge-installer.jar.log`）。
2. 抽 URL：`grep -oE 'https://[^ ]+\.jar' neoforge-installer.jar.log | sort -u > /tmp/urls.txt`。
3. 写脚本逐个 curl 拉取，按 Maven URL 前缀剥离映射到 `libraries/` 子目录。
4. 手工补错误输出里非 `.jar` 的项（如 `net.neoforged:neoform:...@zip`）。
5. 重跑 installer——它检测到已存在文件就进入打补丁阶段。

## 部署核对用逐 zip 条目 CRC32，不用整包 sha256

- 判断「远端跑的是不是这次构建」：从当前源码本地构建，下载远端 jar，**逐 zip 条目比对 CRC32**（zip 内每条目带 CRC，与构建时间/环境无关）。
- 整包 sha256 只有在构建环境字节级一致时才相等；不同机器/时间打出的 jar 整包哈希不同，但条目 CRC 相同。不要解包到远端比对 class，也不要用文件 mtime（git 操作会刷新 mtime 而内容不变）。
- 需要与远端验证包行为一致时，构建参数（如 `-PenableVerifyRpc=true`）必须和远端那份相同——布尔常量会被常量折叠+死代码消除，同源码不同开关行为不同。

## 测试主机上每种用途只留一个在用的验证脚本

写新远端验证脚本前，先找上一会话为同一用途留下的脚本并在其上扩展（加检查、参数化），不要再造一个近似的一次性脚本——一堆近重复会逼下个人重新推导哪个才是权威。任何改服务器配置的脚本必须在同一次运行的 finally 里还原，不留人工收尾。所有编排写成一个 `.py`、一次 `python x.py` 跑完，不要在 shell 里内联 sleep 轮询或 /dev/tcp 探测（触发审批、时序不可读）。
