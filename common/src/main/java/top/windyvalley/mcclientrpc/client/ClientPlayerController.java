package top.windyvalley.mcclientrpc.client;

import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import top.windyvalley.mcclientrpc.McClientRpc;
import top.windyvalley.mcclientrpc.protocol.MethodDefinition;
import top.windyvalley.mcclientrpc.protocol.RpcDispatcher;
import top.windyvalley.mcclientrpc.protocol.RpcErrorCodes;
import top.windyvalley.mcclientrpc.protocol.RpcException;
import top.windyvalley.mcclientrpc.protocol.RpcParamSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 客户端玩家操控 —— 真实输入驱动。
 *
 * <p>核心约束：Minecraft 客户端的状态（玩家实体、世界、攻击）只能在
 * <b>客户端主线程</b>（render thread）安全访问。RPC 请求来自网络线程，
 * 不能直接动 MC 状态。因此采用<b>任务队列 + tick 消费</b>模式：
 *
 * <ol>
 *   <li>RPC 线程把操控请求封装成 {@link ClientTask} 投入 {@link #queue}，
 *       并阻塞等待结果（带超时）</li>
 *   <li>客户端 tick（render thread）每帧从 queue 取出任务执行，
 *       把结果/异常写回 future</li>
 * </ol>
 *
 * <p>这样所有 MC API 调用都发生在 render thread，与游戏主循环同步，
 * 避免跨线程访问玩家实体导致的崩溃或状态错乱。
 */
public final class ClientPlayerController {

    /** RPC 默认监听端口。 */
    public static final int DEFAULT_PORT = 25586;

    /** 操控任务：在 render thread 执行，产出结果。 */
    private record ClientTask(Supplier<Object> action, CompletableFuture<Object> future) {
    }

    private final ConcurrentLinkedQueue<ClientTask> queue = new ConcurrentLinkedQueue<>();

    private final Minecraft mc = Minecraft.getInstance();

    private final TargetResolver targetResolver = new TargetResolver();

    /** 持续移动目标：非空时 tick() 每帧朝它前进并瞄准，跑赢 input.tick() 的键盘覆盖。 */
    private volatile Entity moveTarget;

    /**
     * 攻击会话：同一时刻最多一个，只在 render thread 读写。
     * 取消与 tick 攻击段都在 render thread，天然互斥，无需额外令牌。
     */
    private record AttackSession(Entity target, CompletableFuture<Object> future) {
    }

    private volatile AttackSession attackSession;

    /** 无返回任务队列：只需要在 render thread 执行副作用（如取消），不产生结果。 */
    private final ConcurrentLinkedQueue<Runnable> clientRunnables = new ConcurrentLinkedQueue<>();

    /**
     * 提交一个任务到 render thread 执行，阻塞等待结果。
     *
     * @param action  在 render thread 跑的操作（可访问 MC 状态）
     * @param timeoutMs 超时毫秒；超时返回错误而不是无限等
     */
    private Object submitOnClientThread(Supplier<Object> action, long timeoutMs) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        queue.add(new ClientTask(action, future));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return "错误: 客户端线程执行超时（" + timeoutMs + "ms）——可能未进入世界或游戏卡顿";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // 业务异常透传，保留错误码与 data
            if (cause instanceof RpcException re) {
                throw re;
            }
            if (cause instanceof RuntimeException re2) {
                throw re2;
            }
            return "错误: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "错误: 等待被中断";
        }
    }

    /**
     * 客户端 tick 入口（render thread）：取出并执行所有排队任务。
     *
     * <p>由平台层 ClientTickEvent 每帧调用。
     */
    public void tick() {
        // 0. 消费无返回任务（取消等副作用）
        Runnable r;
        while ((r = clientRunnables.poll()) != null) {
            try {
                r.run();
            } catch (Throwable t) {
                McClientRpc.LOGGER.warn("[RPC] 客户端任务执行异常", t);
            }
        }

        // 1. 消费 RPC 任务队列（render thread 执行 MC 操控）
        ClientTask task;
        while ((task = queue.poll()) != null) {
            try {
                task.future().complete(task.action().get());
            } catch (Throwable t) {
                task.future().completeExceptionally(t);
            }
        }

        // 2. 持续移动：有激活目标时每帧朝它推进。
        //    直接改 input 字段无效——KeyboardInput.tick() 每帧开头从键盘覆盖
        //    forwardImpulse/leftImpulse（试过 CLIENT_POST 重设仍被下一帧清掉）。
        //    改用 setDeltaMovement 朝目标推进：产生真实的玩家位移包
        //    (ServerboundMovePlayerPacket)，服务端照常更新位置+校验触及距离，
        //    移动本身是真实网络位移，不是服务端伪造坐标。
        Entity target = moveTarget;
        LocalPlayer p = mc.player;
        if (target != null && p != null) {
            // 注意:moveTarget 的「到达」条件要比 attackTarget 的「触及」更严格(reach - 0.5),
            // 否则 moveTarget 提前停,attackTarget 永远到不了触及范围。
            if (!target.isAlive() || p.distanceTo(target) <= p.entityInteractionRange() - 0.5) {
                // 到达或目标消失:停止
                moveTarget = null;
                p.setDeltaMovement(p.getDeltaMovement().x * 0.5, p.getDeltaMovement().y,
                        p.getDeltaMovement().z * 0.5);
            } else {
                // 朝目标方向推进（保留 y 速度让重力/跳跃正常）
                aimAt(p, target);
                double dx = target.getX() - p.getX();
                double dz = target.getZ() - p.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 1.0e-4) {
                    double speed = 0.22; // 约等于玩家疾走水平速度（格/tick）
                    p.setDeltaMovement(dx / len * speed, p.getDeltaMovement().y, dz / len * speed);
                    p.hurtMarked = true; // 触发位移同步
                }
            }
        }

        // 3. 攻击会话:tick() 每帧检查距离,远了继续推进,近了 attack 并 complete future。
        //    参考怪物 MeleeAttackGoal 的设计:持续 tick 驱动,触及内才攻击。
        AttackSession atk = attackSession;
        if (atk != null && p != null) {
            Entity atkTarget = atk.target();
            CompletableFuture<Object> atkFuture = atk.future();
            if (!atkTarget.isAlive()) {
                atkFuture.complete("错误: 目标已死亡");
                attackSession = null;
            } else {
                double dist = p.distanceTo(atkTarget);
                double reach = p.entityInteractionRange();
                if (dist <= reach - 0.2) {
                    // 到达触及范围:瞄准 + 攻击 + 挥臂
                    aimAt(p, atkTarget);
                    if (mc.gameMode != null) {
                        mc.gameMode.attack(p, atkTarget);
                        p.swing(InteractionHand.MAIN_HAND);
                        atkFuture.complete(String.format("已攻击 %s (距离 %.1f 格) —— 真实输入路径",
                                atkTarget.getType().toString(), dist));
                    } else {
                        atkFuture.complete("错误: gameMode 未就绪");
                    }
                    attackSession = null;
                    moveTarget = null; // 停止移动
                } else {
                    // 继续朝目标推进(设 moveTarget,让上面的逻辑带动)
                    moveTarget = atkTarget;
                }
            }
        }
    }

    /** 把全部 RPC 方法注册到分发器。 */
    public void registerHandlers(RpcDispatcher d) {
        // methods.list 本身也有定义
        d.register(MethodDefinition.of("methods.list",
                "列出所有已注册方法及其参数 schema",
                List.of(), p -> methodsList(d)));

        // ── 进入世界前（自动化导航）──
        registerClient(d, "client.status", "查询客户端当前界面与连接状态",
                List.of(), 3000, p -> clientStatus());
        registerClient(d, "client.toTitle", "强制切到主菜单，跳过拦路引导界面",
                List.of(), 3000, p -> toTitle());
        registerClient(d, "client.connect", "直连指定服务器（异步发起，用 client.status 轮询）",
                List.of(RpcParamSpec.required("host", "string", "服务器地址（必传）"),
                        RpcParamSpec.optional("port", "integer", 25565, "服务器端口")),
                8000, p -> connect(required(p, "host"), (int) f(p, "port", 25565)));
        registerClient(d, "client.disconnect", "断开当前连接，回到主菜单",
                List.of(), 3000, p -> disconnect());
        // client.quit 不走任务队列：destroy 会终止 JVM，阻塞等 future 永远等不到。
        d.register(MethodDefinition.of("client.quit", "完全退出客户端进程",
                List.of(), p -> quitGame()));

        // ── 进入世界后（玩家操控）──
        registerClient(d, "player.pos", "查询玩家坐标、维度与血量",
                List.of(), 3000, p -> playerPos());
        registerClient(d, "player.attack", "在触及范围内攻击最近的指定类型实体",
                List.of(RpcParamSpec.optional("target", "string", "zombie", "目标实体类型（精确匹配）")),
                5000, p -> attackNearest(str(p, "target", "zombie")));
        // 高级指令跨多帧（寻路+攻击），不能走一帧式提交，见 runAttackNearest 注释。
        d.register(MethodDefinition.of("player.attackNearest",
                "自动寻路到目标并攻击（tick 驱动，15s 超时）",
                List.of(RpcParamSpec.optional("target", "string", "zombie", "目标实体类型（精确匹配）")),
                p -> runAttackNearest(str(p, "target", "zombie"))));
        registerClient(d, "player.look", "设置玩家视角",
                List.of(RpcParamSpec.optional("yaw", "float", 0, "水平角（-180~180）"),
                        RpcParamSpec.optional("pitch", "float", 0, "俯仰角（-90~90）")),
                3000, p -> look(f(p, "yaw", 0), f(p, "pitch", 0)));
        registerClient(d, "player.move", "按下/释放方向键（持续生效，需 player.stop 释放）",
                List.of(RpcParamSpec.optional("forward", "float", 0, "前后（>0 前进，<0 后退）"),
                        RpcParamSpec.optional("strafe", "float", 0, "左右（>0 左移，<0 右移）"),
                        RpcParamSpec.optional("jump", "boolean", false, "是否同时跳跃")),
                3000, p -> move(f(p, "forward", 0), f(p, "strafe", 0), bool(p, "jump", false)));
        registerClient(d, "player.jump", "按住/松开跳跃键",
                List.of(RpcParamSpec.optional("down", "boolean", true, "true 按下，false 释放")),
                3000, p -> setJumpKey(bool(p, "down", true)));
        registerClient(d, "player.moveToward", "自动走向最近的指定类型实体",
                List.of(RpcParamSpec.optional("target", "string", "zombie", "目标实体类型（精确匹配）")),
                3000, p -> moveToward(str(p, "target", "zombie")));
        registerClient(d, "player.stop", "停止持续移动并释放全部方向/跳跃键",
                List.of(), 3000, p -> stopMove());
        registerClient(d, "player.state", "查询玩家血量、位置、视角与死亡状态",
                List.of(), 3000, p -> playerState());
        registerClient(d, "player.respawn", "死亡后重生（等价点重生按钮）",
                List.of(), 3000, p -> respawn());
        registerClient(d, "player.command", "以玩家身份发送一条斜杠命令",
                List.of(RpcParamSpec.required("cmd", "string", "不带 / 的命令文本")),
                3000, p -> sendCommand(str(p, "cmd", "")));
        registerClient(d, "player.chat", "以玩家身份发送一条普通聊天",
                List.of(RpcParamSpec.required("msg", "string", "聊天文本")),
                3000, p -> sendChat(str(p, "msg", "")));
        registerClient(d, "world.entities", "列出周围指定半径内的生物",
                List.of(RpcParamSpec.optional("radius", "float", 32, "搜索半径（格）")),
                3000, p -> listEntities(f(p, "radius", 32)));
    }

    /**
     * 统一注册一帧式方法：定义元数据 + body 包进任务队列切到 render thread 执行。
     * 消除每个方法重复的 submitOnClientThread 包装；body 抛出的 RpcException
     * 经队列边界透传，错误码不丢失。
     */
    private void registerClient(RpcDispatcher d, String method, String description,
                                List<RpcParamSpec> params, long timeoutMs,
                                Function<JsonObject, Object> body) {
        d.register(MethodDefinition.of(method, description, params,
                p -> submitOnClientThread(() -> body.apply(p), timeoutMs)));
    }

    /**
     * methods.list 的结构化结果：方法名、说明、参数 schema（名称/类型/必传/默认值/说明）。
     * 是协议的自描述单一来源，API 文档可据此导出。
     */
    private static java.util.Map<String, Object> methodsList(RpcDispatcher d) {
        java.util.List<java.util.Map<String, Object>> methods = new java.util.ArrayList<>();
        for (MethodDefinition def : d.definitions()) {
            java.util.List<java.util.Map<String, Object>> paramList = new java.util.ArrayList<>();
            for (RpcParamSpec ps : def.params()) {
                java.util.Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", ps.name());
                pm.put("type", ps.type());
                pm.put("required", ps.required());
                if (!ps.required()) {
                    pm.put("default", ps.defaultValue());
                }
                pm.put("description", ps.description());
                paramList.add(pm);
            }
            java.util.Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("name", def.name());
            mm.put("description", def.description());
            mm.put("params", paramList);
            methods.add(mm);
        }
        java.util.Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", methods.size());
        result.put("methods", methods);
        return result;
    }

    /**
     * 高级指令：自动寻路到目标 → 进入触及范围 → 攻击。
     *
     * <p>跨多帧操作不能用一帧式 submit：若在 render thread 上阻塞等结果，
     * tick() 的攻击段永远没机会跑，形成死锁。因此：RPC 线程先提交一个瞬间
     * 完成的「开启会话」任务，之后在 RPC 线程阻塞等结果；render thread
     * 不阻塞，继续 tick 驱动寻路与攻击。
     */
    private Object runAttackNearest(String typeKey) {
        CompletableFuture<Object> resultFuture = new CompletableFuture<>();
        CompletableFuture<Object> setupFuture = new CompletableFuture<>();
        queue.add(new ClientTask(() -> {
            startAttackSession(typeKey, resultFuture);
            return "已锁定目标";
        }, setupFuture));
        try {
            setupFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "错误: 客户端线程执行超时(5s)——可能未进入世界或游戏卡顿";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RpcException re) {
                throw re;
            }
            throw new RpcException(RpcErrorCodes.INTERNAL_ERROR,
                    "开启攻击会话失败: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "错误: 等待被中断";
        }
        try {
            return resultFuture.get(15000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 超时只在 render thread 清理自己的会话（无返回通道，不造 future）
            clientRunnables.add(() -> {
                if (attackSession != null && attackSession.future() == resultFuture) {
                    attackSession = null;
                    moveTarget = null;
                }
            });
            return "错误: 攻击超时(15s)——可能目标太远/找不到路径";
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return "错误: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "错误: 等待被中断";
        }
    }

    // ── 进入世界前：自动化导航 ─────────────────────────────────────

    /**
     * 客户端当前状态 —— 自动化的「眼睛」。
     *
     * <p>返回结构化对象：当前界面、是否在世界、连接状态与服务器地址。
     * 让外部脚本直接读字段判断「现在能不能发 player.* 指令」，无需解析句子。
     */
    private java.util.Map<String, Object> clientStatus() {
        String screen = mc.screen == null ? null : mc.screen.getClass().getSimpleName();
        boolean inWorld = mc.player != null && mc.level != null;
        boolean connected = mc.getConnection() != null && mc.getConnection().getConnection().isConnected();
        String server = connected && mc.getCurrentServer() != null ? mc.getCurrentServer().ip : null;
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        m.put("screen", screen);
        m.put("inWorld", inWorld);
        m.put("connected", connected);
        m.put("server", server);
        return m;
    }

    /**
     * 强制切到主菜单 —— 跳过拦路的引导界面。
     *
     * <p>dev 环境首次启动会停在 {@code AccessibilityOnboardingScreen}
     * （无障碍引导），它挡在 TitleScreen 之前，导致 quickPlay 不触发、
     * 也无法直接连接。本方法无条件 setScreen(TitleScreen)，把所有
     * 引导/确认界面一次性关掉。connect 前会自动调用它。
     */
    private String toTitle() {
        String before = mc.screen == null ? "无" : mc.screen.getClass().getSimpleName();
        mc.setScreen(new TitleScreen());
        return "已从 " + before + " 切到 TitleScreen";
    }

    /**
     * 直连服务器 —— 绕过 quickPlay 失效，主动发起连接。
     *
     * <p>走官方 {@link ConnectScreen#startConnecting} 路径，等价于玩家
     * 在多人游戏列表点「加入服务器」。连接是异步的：本方法发起后立即返回，
     * 进世界需几秒，外部用 client.status 轮询「已进世界=true」再发 player.*。
     *
     * <p>连接前先强制回主菜单，避免被无障碍引导等界面挡住。
     */
    private String connect(String host, int port) {
        if (mc.player != null) {
            return "已在世界中（" + (mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "?")
                    + "），先 client.disconnect 再连";
        }
        // 先确保在主菜单：无障碍引导屏(AccessibilityOnboardingScreen)等会挡住连接流程
        mc.setScreen(new TitleScreen());

        String address = host + ":" + port;
        ServerAddress serverAddress = ServerAddress.parseString(address);
        // ServerData: name/ip/type。type 用 OTHER，不存进服务器列表。
        ServerData serverData = new ServerData("mcrpc-" + host, address, ServerData.Type.OTHER);
        // parent 用 TitleScreen：连接界面叠加其上，断开时自然回到主菜单。
        ConnectScreen.startConnecting(new TitleScreen(), mc, serverAddress, serverData, false, null);
        return "已发起连接 " + address + "（异步，用 client.status 轮询进世界）";
    }

    /**
     * 断开连接、回主菜单 —— 测试间重置。
     */
    private String disconnect() {
        if (mc.level == null) {
            return "本就不在世界中";
        }
        boolean wasMultiplayer = mc.getConnection() != null;
        if (mc.level != null) {
            mc.level.disconnect();
        }
        if (wasMultiplayer) {
            mc.disconnect(new TitleScreen(), false);
        } else {
            mc.disconnect(new TitleScreen(), false);
        }
        return "已断开，回主菜单";
    }

    /**
     * 退出整个客户端进程（标题屏「退出」按钮的效果）。
     *
     * <p><b>不能</b>用 {@link #submitOnClientThread}：{@code Minecraft.destroy()}
     * 末尾会 {@code System.exit(0)}，在 render thread 同步执行时，RPC 应答还没
     * 写回 socket 进程就没了，调用方只会收到连接断开。因此立即返回应答，另起
     * daemon 线程延迟 {@value #QUIT_DELAY_MS}ms（留出响应发送时间），再用
     * {@code mc.execute} 切回 render thread 调 destroy（MC 状态只能在主线程动）。
     */
    private static final long QUIT_DELAY_MS = 500;

    private String quitGame() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(QUIT_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mc.execute(() -> {
                try {
                    mc.destroy();
                } finally {
                    // destroy 在异常路径下可能不退出，兜底保证进程终止
                    Runtime.getRuntime().halt(0);
                }
            });
        }, "mc-client-rpc-quit");
        t.setDaemon(true);
        t.start();
        return "客户端将在 " + QUIT_DELAY_MS + "ms 后退出";
    }

    // ── 操控实现（都在 render thread 执行）─────────────────────────

    private java.util.Map<String, Object> playerPos() {
        LocalPlayer p = requirePlayer();
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName().getString());
        m.put("x", round1(p.getX()));
        m.put("y", round1(p.getY()));
        m.put("z", round1(p.getZ()));
        m.put("dimension", p.level().dimension().location().toString());
        m.put("health", p.getHealth());
        m.put("maxHealth", p.getMaxHealth());
        return m;
    }

    private java.util.Map<String, Object> playerState() {
        LocalPlayer p = requirePlayer();
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName().getString());
        m.put("health", p.getHealth());
        m.put("maxHealth", p.getMaxHealth());
        m.put("x", round1(p.getX()));
        m.put("y", round1(p.getY()));
        m.put("z", round1(p.getZ()));
        m.put("hitResult", mc.hitResult == null ? null : mc.hitResult.getType().toString());
        m.put("onGround", p.onGround());
        m.put("dead", p.isDeadOrDying());
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * 重生 —— 死亡界面(DeathScreen)挡操控时点击「重生」按钮的效果。
     *
     * <p>玩家死亡后停在 DeathScreen，所有 player.* 操控都被挡住。
     * 本方法等价于点重生按钮：发 ServerboundClientCommandPacket(PERFORM_RESPAWN)。
     */
    private String respawn() {
        LocalPlayer p = requirePlayer();
        if (!p.isDeadOrDying()) {
            return "玩家未死亡，无需重生";
        }
        // 等价于点 DeathScreen 的「重生」按钮：内部发 ServerboundClientCommandPacket
        p.respawn();
        return "已重生";
    }

    /**
     * 以玩家身份发送一条指令（等价于在聊天框输入 /xxx 回车）。
     *
     * <p>走真实聊天指令管线：发 {@code ServerboundChatCommandPacket}，
     * 服务端按该玩家权限执行。因此可执行的指令取决于玩家权限——
     * 普通玩家只能 /me、/msg 等；OP(等级≥2)才能 /summon、/gamemode 等。
     * 测试脚本可用 RCON 先 op 再由此发指令（见 combat_loop_test）。
     *
     * @param cmd 不带前导 / 的指令，如 "summon zombie 0 -60 0"
     */
    private String sendCommand(String cmd) {
        LocalPlayer p = requirePlayer();
        if (cmd == null || cmd.isBlank()) {
            throw new RpcException(RpcErrorCodes.INVALID_PARAMS, "缺少必传参数: cmd");
        }
        String c = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        p.connection.sendCommand(c);
        return "已发送指令 /" + c;
    }

    /**
     * 以玩家身份发送一条普通聊天（等价于聊天框打字回车）。
     * 走真实聊天管线：发 ServerboundChatPacket。
     */
    private String sendChat(String msg) {
        LocalPlayer p = requirePlayer();
        if (msg == null || msg.isBlank()) {
            throw new RpcException(RpcErrorCodes.INVALID_PARAMS, "缺少必传参数: msg");
        }
        p.connection.sendChat(msg);
        return "已发送聊天: " + msg;
    }

    /**
     * 开启攻击会话（render thread 调用）。
     *
     * <p>同一时刻只允许一个高层操作：已有会话进行中时抛 {@link RpcException}（BUSY），
     * 不覆盖旧会话，先发起的调用不会因此挂死。目标不存在时抛 TARGET_NOT_FOUND，
     * data 携带搜索半径。
     */
    private void startAttackSession(String typeKey, CompletableFuture<Object> future) {
        LocalPlayer p = requirePlayer();
        if (attackSession != null) {
            throw new RpcException(RpcErrorCodes.BUSY,
                    "已有进行中的攻击操作，请等待完成或稍后重试",
                    java.util.Map.of("currentTarget",
                            attackSession.target().getType().toString()));
        }
        Entity target = targetResolver.nearestByType(p, typeKey, 32.0);
        if (target == null) {
            throw new RpcException(RpcErrorCodes.TARGET_NOT_FOUND,
                    "附近 " + 32 + " 格内无 " + typeKey,
                    java.util.Map.of("target", typeKey, "radius", 32));
        }
        attackSession = new AttackSession(target, future);
    }

    /**
     * 攻击最近的指定类型生物 —— 真实输入路径。
     *
     * <p>先把视角对准目标，再调 {@code mc.gameMode.attack(player, entity)}
     * 走真实攻击管线（发 ServerboundInteractPacket 攻击包），并挥臂。
     * 这正是真人点击鼠标左键的效果，区别于服务端伪造伤害来源。
     *
     * <p>返回结构化对象：目标类型、距离、触及距离、攻击前后血量与命中判定。
     * 超出触及距离时抛 OUT_OF_REACH，data 携带 distance / reach。
     */
    private java.util.Map<String, Object> attackNearest(String typeKey) {
        LocalPlayer p = requirePlayer();
        Entity target = targetResolver.nearestByType(p, typeKey, 32.0);
        if (target == null) {
            throw new RpcException(RpcErrorCodes.TARGET_NOT_FOUND,
                    "附近 " + 32 + " 格内无 " + typeKey,
                    java.util.Map.of("target", typeKey, "radius", 32));
        }
        if (mc.gameMode == null) {
            throw new RpcException(RpcErrorCodes.NOT_READY, "gameMode 未就绪（不在多人游戏？）");
        }
        double dist = p.distanceTo(target);
        // 触及距离守卫：生存模式玩家触及约 3 格（实体判定 + 服务端校验）。
        double reach = p.entityInteractionRange();
        if (dist > reach) {
            throw new RpcException(RpcErrorCodes.OUT_OF_REACH,
                    "目标超出触及距离，需先走近",
                    java.util.Map.of("distance", round1(dist), "reach", round1(reach)));
        }
        // 攻击前血量（LivingEntity 才有，用于命中判定）
        Float hpBefore = target instanceof net.minecraft.world.entity.LivingEntity le
                ? le.getHealth() : null;
        // 视角对准目标（真实玩家会先瞄准）
        aimAt(p, target);
        // 真实攻击：走客户端攻击管线 → 发攻击包 → 服务端判定
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
        // 命中判定：客户端预测血量（服务端权威，客户端血量稍后同步）
        Float hpAfter = target instanceof net.minecraft.world.entity.LivingEntity le2
                ? le2.getHealth() : null;
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", target.getType().toString());
        m.put("distance", round1(dist));
        m.put("reach", round1(reach));
        m.put("hpBefore", hpBefore);
        m.put("hpAfter", hpAfter);
        m.put("hit", hpBefore != null && hpAfter != null && hpAfter < hpBefore);
        return m;
    }

    private String look(float yaw, float pitch) {
        LocalPlayer p = requirePlayer();
        p.setYRot(yaw);
        p.setXRot(pitch);
        return String.format("视角已设为 yaw=%.1f pitch=%.1f", yaw, pitch);
    }

    private String move(float forward, float strafe, boolean jump) {
        LocalPlayer p = requirePlayer();
        // 直接设 input.forwardImpulse 会被 KeyboardInput.tick() 下一帧按键映射
        // 重算覆盖（实测玩家不动，z 恒定），且不在正常输入链上、易丢移动包。
        // 与跳跃同理：改为按下/释放方向键映射，由原版 tick 自然驱动并发包。
        var o = Minecraft.getInstance().options;
        o.keyUp.setDown(forward > 0.01f);
        o.keyDown.setDown(forward < -0.01f);
        o.keyLeft.setDown(strafe > 0.01f);
        o.keyRight.setDown(strafe < -0.01f);
        o.keyJump.setDown(jump);
        return String.format("移动键已设 forward=%.2f strafe=%.2f jump=%s（原版 tick 驱动发包，需 player.stop 释放）",
                forward, strafe, jump);
    }

    /**
     * 按下 / 释放跳跃键映射 {@code Minecraft.options.keyJump}。
     *
     * <p>为什么不能直接调 {@code jumpFromGround()}（实测过）：直接调用发生在正常
     * tick 循环之外，客户端只做了本地预测离地，<b>不发位置包</b>，等下一帧正常
     * tick 发包时玩家已落地，服务端从头到尾没看到这次跳跃，于是服务端不产生
     * 落地振动，尖啸体（服务端监听者）收不到（实测 y 抬到 -55.8 但 listener 恒 -1）。
     *
     * <p>真实按空格走的是：{@code KeyboardInput.tick()} 读 {@code keyJump.isDown()}
     * 设 {@code input.jumping} → {@code LocalPlayer.aiStep()} 起跳并发位置包 →
     * 服务端看到跳跃和落地、广播落地振动。故这里直接置按键映射的按下状态，
     * 让原版整条链路（含发包）自然发生。
     */
    private String setJumpKey(boolean down) {
        LocalPlayer p = requirePlayer();
        KeyMapping keyJump = Minecraft.getInstance().options.keyJump;
        keyJump.setDown(down);
        return String.format("跳跃键 %s（将由原版 tick 起跳并发包）", down ? "按下" : "释放");
    }

    /**
     * 自动走向最近的指定生物，直到进入攻击触及范围。
     *
     * <p>朝目标持续前进（forward=1）并把视角对准它。移动是逐帧模拟按键，
     * 到达触及距离后自动停下。闭环自动化的关键：找怪 → moveToward 走近 → attack 命中。
     */
    private String moveToward(String typeKey) {
        LocalPlayer p = requirePlayer();
        Entity target = targetResolver.nearestByType(p, typeKey, 32.0);
        if (target == null) {
            throw new RpcException(RpcErrorCodes.TARGET_NOT_FOUND,
                    "附近 " + 32 + " 格内无 " + typeKey,
                    java.util.Map.of("target", typeKey, "radius", 32));
        }
        double reach = p.entityInteractionRange();
        double dist = p.distanceTo(target);
        if (dist <= reach - 0.3) {
            return String.format("已在触及范围内（%.1f 格），无需移动", dist);
        }
        // 设持续移动目标，由 tick() 每帧驱动前进+瞄准（跑赢键盘覆盖），
        // 到达触及距离后 tick 自动停止。这是真实走路（发移动包），不是瞬移。
        moveTarget = target;
        return String.format("开始走向 %s（当前 %.1f 格，触及 %.1f 格）——tick 驱动，到达自动停",
                target.getType().toString(), dist, reach);
    }

    /** 停止移动（清持续目标 + 释放方向/跳跃键映射）。 */
    private String stopMove() {
        moveTarget = null;
        var o = Minecraft.getInstance().options;
        o.keyUp.setDown(false);
        o.keyDown.setDown(false);
        o.keyLeft.setDown(false);
        o.keyRight.setDown(false);
        o.keyJump.setDown(false);
        return "移动已停止";
    }

    private java.util.Map<String, Object> listEntities(float radius) {
        LocalPlayer p = requirePlayer();
        java.util.List<java.util.Map<String, Object>> entities = new java.util.ArrayList<>();
        for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(radius),
                x -> x instanceof net.minecraft.world.entity.LivingEntity)) {
            java.util.Map<String, Object> em = new LinkedHashMap<>();
            em.put("type", e.getType().toString());
            em.put("x", round1(e.getX()));
            em.put("y", round1(e.getY()));
            em.put("z", round1(e.getZ()));
            em.put("distance", round1(p.distanceTo(e)));
            entities.add(em);
        }
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        m.put("radius", radius);
        m.put("count", entities.size());
        m.put("entities", entities);
        return m;
    }

    // ── 工具 ─────────────────────────────────────────────────────

    private LocalPlayer requirePlayer() {
        LocalPlayer p = mc.player;
        if (p == null) {
            throw new RpcException(RpcErrorCodes.NOT_READY, "玩家未进入世界");
        }
        return p;
    }

    /** 把玩家视角转向目标实体（计算 yaw/pitch）。 */
    private void aimAt(LocalPlayer p, Entity target) {
        double dx = target.getX() - p.getX();
        double dy = (target.getY() + target.getEyeHeight()) - (p.getY() + p.getEyeHeight());
        double dz = target.getZ() - p.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        p.setYRot(yaw);
        p.setXRot(pitch);
    }

    private static String str(JsonObject p, String k, String def) {
        return p.has(k) ? p.get(k).getAsString() : def;
    }

    /** 取必传字符串参数：缺失 / 空白 / 类型错一律抛 INVALID_PARAMS，不静默使用默认值。 */
    private static String required(JsonObject p, String k) {
        if (!p.has(k) || p.get(k).isJsonNull()) {
            throw new RpcException(RpcErrorCodes.INVALID_PARAMS, "缺少必传参数: " + k);
        }
        String v = p.get(k).getAsString();
        if (v == null || v.isBlank()) {
            throw new RpcException(RpcErrorCodes.INVALID_PARAMS, "参数不能为空: " + k);
        }
        return v;
    }

    private static float f(JsonObject p, String k, float def) {
        return p.has(k) ? p.get(k).getAsFloat() : def;
    }

    private static boolean bool(JsonObject p, String k, boolean def) {
        return p.has(k) ? p.get(k).getAsBoolean() : def;
    }
}
