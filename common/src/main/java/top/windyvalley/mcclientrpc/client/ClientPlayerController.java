package top.windyvalley.mcclientrpc.client;

import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import top.windyvalley.mcclientrpc.McClientRpc;
import top.windyvalley.mcclientrpc.protocol.RpcDispatcher;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** 持续移动目标：非空时 tick() 每帧朝它前进并瞄准，跑赢 input.tick() 的键盘覆盖。 */
    private volatile Entity moveTarget;

    /** 攻击目标：非空时 tick() 每帧检查距离,远了 moveToward,近了 attack 并 complete future。 */
    private volatile Entity attackTarget;
    /** 攻击完成回调:tick() 里 attack 后 complete。 */
    private volatile CompletableFuture<Object> attackFuture;

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

        // 3. 攻击目标:tick() 每帧检查距离,远了 moveToward,近了 attack 并 complete future。
        //    参考怪物 MeleeAttackGoal 的设计:持续 tick 驱动,触及内+视线通才攻击。
        Entity atkTarget = attackTarget;
        CompletableFuture<Object> atkFuture = attackFuture;
        if (atkTarget != null && atkFuture != null && p != null) {
            if (!atkTarget.isAlive()) {
                atkFuture.complete("错误: 目标已死亡");
                attackTarget = null;
                attackFuture = null;
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
                    attackTarget = null;
                    attackFuture = null;
                    moveTarget = null; // 停止移动
                } else {
                    // 继续 moveToward(设 moveTarget,让上面的逻辑推进)
                    moveTarget = atkTarget;
                }
            }
        }
    }

    /** 把全部 RPC 方法注册到分发器。 */
    public void registerHandlers(RpcDispatcher d) {
        d.register("methods.list", p -> String.join(", ", d.methods()));

        // ── 进入世界前（自动化导航）──
        d.register("client.status", p -> submitOnClientThread(this::clientStatus, 3000));
        d.register("client.toTitle", p -> submitOnClientThread(this::toTitle, 3000));
        d.register("client.connect", p -> submitOnClientThread(
                () -> connect(str(p, "host", "127.0.0.1"), (int) f(p, "port", 25565)), 8000));
        d.register("client.disconnect", p -> submitOnClientThread(this::disconnect, 3000));
        // 完全退出客户端进程（等价标题屏「退出」按钮 → Minecraft.destroy → System.exit）。
        // 不走 submitOnClientThread：destroy 会终止 JVM，阻塞等 future 永远等不到。
        // 立即返回应答，500ms 后在 render thread 执行 destroy（留时间把响应写回 socket）。
        d.register("client.quit", p -> quitGame());

        // ── 进入世界后（玩家操控）──
        d.register("player.pos", p -> submitOnClientThread(this::playerPos, 3000));
        d.register("player.attack", p -> submitOnClientThread(() -> attackNearest(str(p, "target", "zombie")), 5000));
        // 高级指令:自动寻路到目标 → 进入触及范围 → 攻击(参考怪物 MeleeAttackGoal)
        // 注意:不能用 submitOnClientThread 里阻塞 future.get()——那会卡死 render thread,
        // tick() 的 attackTarget 处理永远没机会跑(死锁)。
        // 正确做法:RPC 线程上阻塞,先提交一个瞬间完成的「设置目标」任务,
        // render thread 继续 tick 处理寻路+攻击,完成后 complete attackFuture。
        d.register("player.attackNearest", p -> {
            String typeKey = str(p, "target", "zombie");
            CompletableFuture<Object> attackFuture = new CompletableFuture<>();
            CompletableFuture<Object> setupFuture = new CompletableFuture<>();
            // 快速任务:在 render thread 设置 attackTarget(瞬间完成)
            queue.add(new ClientTask(() -> {
                attackNearestAsync(typeKey, attackFuture);
                return "已锁定目标";
            }, setupFuture));
            // 等设置任务跑完(确认目标找到)
            try {
                setupFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                attackTarget = null;
                attackFuture.complete("错误: 设置攻击目标失败: " + e.getMessage());
            }
            // RPC 线程上阻塞等结果(render thread 不阻塞,继续 tick)
            try {
                return attackFuture.get(15000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // 超时清理状态,避免卡死
                queue.add(new ClientTask(() -> {
                    attackTarget = null; moveTarget = null;
                    return null;
                }, new CompletableFuture<>()));
                attackTarget = null;
                return "错误: 攻击超时(15s)——可能目标太远/找不到路径";
            } catch (ExecutionException e) {
                attackTarget = null;
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return "错误: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                attackTarget = null;
                return "错误: 等待被中断";
            }
        });
        d.register("player.look", p -> submitOnClientThread(
                () -> look(f(p, "yaw", 0), f(p, "pitch", 0)), 3000));
        d.register("player.move", p -> submitOnClientThread(
                () -> move(f(p, "forward", 0), f(p, "strafe", 0), bool(p, "jump", false)), 3000));
        d.register("player.jump", p -> submitOnClientThread(
                () -> setJumpKey(bool(p, "down", true)), 3000));
        d.register("player.moveToward", p -> submitOnClientThread(
                () -> moveToward(str(p, "target", "zombie")), 3000));
        d.register("player.stop", p -> submitOnClientThread(this::stopMove, 3000));
        d.register("player.state", p -> submitOnClientThread(this::playerState, 3000));
        d.register("player.respawn", p -> submitOnClientThread(this::respawn, 3000));
        d.register("player.command", p -> submitOnClientThread(() -> sendCommand(str(p, "cmd", "")), 3000));
        d.register("player.chat", p -> submitOnClientThread(() -> sendChat(str(p, "msg", "")), 3000));
        d.register("world.entities", p -> submitOnClientThread(
                () -> listEntities(f(p, "radius", 32)), 3000));
    }

    // ── 进入世界前：自动化导航 ─────────────────────────────────────

    /**
     * 客户端当前状态 —— 自动化的「眼睛」。
     *
     * <p>报告当前在哪个界面（主菜单/连接中/已进世界）、玩家是否就绪，
     * 让外部脚本能判断「现在能不能发 player.* 指令」。
     */
    private String clientStatus() {
        String screen = mc.screen == null ? "无界面(游戏中)" : mc.screen.getClass().getSimpleName();
        boolean inWorld = mc.player != null && mc.level != null;
        boolean connected = mc.getConnection() != null && mc.getConnection().getConnection().isConnected();
        String server = connected && mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "无";
        return String.format("界面=%s | 已进世界=%s | 已连服务器=%s | 服务器=%s",
                screen, inWorld, connected, server);
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

    private String playerPos() {
        LocalPlayer p = requirePlayer();
        return String.format("玩家 %s 位于 (%.1f, %.1f, %.1f) 维度 %s 血量 %.1f/%.1f",
                p.getName().getString(), p.getX(), p.getY(), p.getZ(),
                p.level().dimension().location(), p.getHealth(), p.getMaxHealth());
    }

    private String playerState() {
        LocalPlayer p = requirePlayer();
        return String.format("玩家 %s | 血量 %.1f/%.1f | 位置 (%.1f,%.1f,%.1f) | 准星指向 %s | 地面 %s | 死亡 %s",
                p.getName().getString(), p.getHealth(), p.getMaxHealth(),
                p.getX(), p.getY(), p.getZ(),
                mc.hitResult == null ? "无" : mc.hitResult.getType(),
                p.onGround() ? "是" : "否",
                p.isDeadOrDying() ? "是(需respawn)" : "否");
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
            return "错误: 缺少 cmd 参数";
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
            return "错误: 缺少 msg 参数";
        }
        p.connection.sendChat(msg);
        return "已发送聊天: " + msg;
    }

    /**
     * 高级指令:自动寻路到目标 → 进入触及范围 → 攻击。
     *
     * <p>参考怪物 {@code MeleeAttackGoal} 的设计:持续 tick 驱动,
     * 触及内+视线通才攻击。与 {@link #attackNearest} 的区别:
     * <ul>
     *   <li>{@code attackNearest}:一次性,距离远直接报错,需调用方先 moveToward</li>
     *   <li>{@code attackNearestAsync}:自动寻路,tick 里循环检查距离,
     *       到达触及范围后自动攻击,结果经 future 异步返回</li>
     * </ul>
     *
     * <p>实现:设 {@link #attackTarget} + {@link #attackFuture},
     * tick() 里每帧检查距离,远了 moveToward,近了 attack 并 complete future。
     */
    private void attackNearestAsync(String typeKey, CompletableFuture<Object> future) {
        LocalPlayer p = requirePlayer();
        Entity target = nearestEntity(p, typeKey, 32.0);
        if (target == null) {
            future.complete("错误: 附近 32 格内无 " + typeKey);
            return;
        }
        // 设攻击目标,tick() 里循环处理(远了 moveToward,近了 attack)
        attackTarget = target;
        attackFuture = future;
    }

    /**
     * 攻击最近的指定类型生物 —— 真实输入路径。
     *
     * <p>先把视角对准目标，再调 {@code mc.gameMode.attack(player, entity)}
     * 走真实攻击管线（发 ServerboundInteractPacket 攻击包），并挥臂。
     * 这正是真人点击鼠标左键的效果，区别于服务端伪造伤害来源。
     */
    private String attackNearest(String typeKey) {
        LocalPlayer p = requirePlayer();
        Entity target = nearestEntity(p, typeKey, 32.0);
        if (target == null) {
            return "错误: 附近 32 格内无 " + typeKey;
        }
        if (mc.gameMode == null) {
            return "错误: gameMode 未就绪（不在多人游戏？）";
        }
        double dist = p.distanceTo(target);
        // 触及距离守卫：生存模式玩家触及约 3 格（实体判定 + 服务端校验）。
        // 超出时服务端会判定挥空，攻击包发了但不掉血——此前「打了不掉血」即此因。
        double reach = p.entityInteractionRange(); // 玩家实体触及距离（属性系统，生存默认3格）
        if (dist > reach) {
            return String.format("错误: %s 距离 %.1f 格，超出触及范围 %.1f 格——需先 player.move 走近",
                    target.getType().toString(), dist, reach);
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
        String hitInfo = (hpBefore != null && hpAfter != null)
                ? String.format(" 血量 %.1f→%.1f %s", hpBefore, hpAfter,
                        hpAfter < hpBefore ? "(已命中)" : "(未掉血-可能挥空/无敌帧)")
                : "";
        return String.format("已攻击 %s (距离 %.1f 格) —— 真实输入路径%s",
                target.getType().toString(), dist, hitInfo);
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
        Entity target = nearestEntity(p, typeKey, 32.0);
        if (target == null) {
            return "错误: 附近 32 格内无 " + typeKey;
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

    private String listEntities(float radius) {
        LocalPlayer p = requirePlayer();
        StringBuilder sb = new StringBuilder("周围 " + radius + " 格内生物:\n");
        for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(radius),
                x -> x instanceof net.minecraft.world.entity.LivingEntity)) {
            sb.append(String.format("- %s (%.1f,%.1f,%.1f) 距离 %.1f\n",
                    e.getType().toString(), e.getX(), e.getY(), e.getZ(), p.distanceTo(e)));
        }
        return sb.toString();
    }

    // ── 工具 ─────────────────────────────────────────────────────

    private LocalPlayer requirePlayer() {
        LocalPlayer p = mc.player;
        if (p == null) {
            throw new IllegalStateException("玩家未进入世界");
        }
        return p;
    }

    private Entity nearestEntity(LocalPlayer p, String typeKey, double radius) {
        List<Entity> list = p.level().getEntities(p, p.getBoundingBox().inflate(radius),
                e -> e.getType().toString().contains(typeKey));
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : list) {
            double d = p.distanceTo(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
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

    private static float f(JsonObject p, String k, float def) {
        return p.has(k) ? p.get(k).getAsFloat() : def;
    }

    private static boolean bool(JsonObject p, String k, boolean def) {
        return p.has(k) ? p.get(k).getAsBoolean() : def;
    }
}
