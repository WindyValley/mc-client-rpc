package top.windyvalley.mcclientrpc.client;

import com.google.gson.JsonObject;
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

    /** RPC 默认监听端口（区别于服务端验证设施的 25585）。 */
    public static final int DEFAULT_PORT = 25586;

    /** 操控任务：在 render thread 执行，产出结果。 */
    private record ClientTask(Supplier<Object> action, CompletableFuture<Object> future) {
    }

    private final ConcurrentLinkedQueue<ClientTask> queue = new ConcurrentLinkedQueue<>();

    private final Minecraft mc = Minecraft.getInstance();

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
        ClientTask task;
        while ((task = queue.poll()) != null) {
            try {
                task.future().complete(task.action().get());
            } catch (Throwable t) {
                task.future().completeExceptionally(t);
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

        // ── 进入世界后（玩家操控）──
        d.register("player.pos", p -> submitOnClientThread(this::playerPos, 3000));
        d.register("player.attack", p -> submitOnClientThread(() -> attackNearest(str(p, "target", "zombie")), 5000));
        d.register("player.look", p -> submitOnClientThread(
                () -> look(f(p, "yaw", 0), f(p, "pitch", 0)), 3000));
        d.register("player.move", p -> submitOnClientThread(
                () -> move(f(p, "forward", 0), f(p, "strafe", 0), bool(p, "jump", false)), 3000));
        d.register("player.state", p -> submitOnClientThread(this::playerState, 3000));
        d.register("player.respawn", p -> submitOnClientThread(this::respawn, 3000));
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
        // 视角对准目标（真实玩家会先瞄准）
        aimAt(p, target);
        // 真实攻击：走客户端攻击管线 → 发攻击包 → 服务端判定
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
        return String.format("已攻击 %s (距离 %.1f 格) —— 真实输入路径",
                target.getType().toString(), p.distanceTo(target));
    }

    private String look(float yaw, float pitch) {
        LocalPlayer p = requirePlayer();
        p.setYRot(yaw);
        p.setXRot(pitch);
        return String.format("视角已设为 yaw=%.1f pitch=%.1f", yaw, pitch);
    }

    private String move(float forward, float strafe, boolean jump) {
        LocalPlayer p = requirePlayer();
        if (p.input == null) {
            return "错误: input 未就绪";
        }
        p.input.forwardImpulse = forward;
        p.input.leftImpulse = strafe;
        p.input.jumping = jump;
        return String.format("移动输入已设 forward=%.2f strafe=%.2f jump=%s（持续，需后续指令清零）",
                forward, strafe, jump);
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
