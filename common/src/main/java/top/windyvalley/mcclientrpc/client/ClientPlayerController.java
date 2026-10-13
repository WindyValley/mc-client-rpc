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

    /**
     * 操控任务：在 render thread 执行。动作接收自己的 future，
     * 自行决定立即完成（普通一帧式）还是登记后延迟完成（攻击命中判定）。
     */
    private record ClientTask(java.util.function.Consumer<CompletableFuture<Object>> action,
                              CompletableFuture<Object> future) {
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

    /**
     * 延迟命中判定：攻击已经真实发起，等若干 tick 让服务端同步目标血量，
     * 再读血量判定命中、完成 future。目标实体的血量是服务端权威，
     * 攻击同帧在客户端读不到变化（实测服务端已 17→16，客户端仍读到 17）。
     */
    private record PendingHit(Entity target, float hpBefore,
                              CompletableFuture<Object> future, int ticksLeft) {
        PendingHit tickDown() {
            return new PendingHit(target, hpBefore, future, ticksLeft - 1);
        }
    }

    /** 攻击后等待服务端同步再判定的 tick 数（5 tick ≈ 250ms）。 */
    private static final int HIT_CHECK_DELAY_TICKS = 5;

    private final java.util.List<PendingHit> pendingHits = new java.util.ArrayList<>();

    /**
     * 延迟开屏判定：右键交互已真实发包，等若干 tick 让服务端回
     * ClientboundOpenScreenPacket 后再读 {@code mc.screen}，判定交易 GUI
     * 是否真的打开。同攻击命中判定，屏幕状态也是服务端权威、跨 tick 才同步。
     */
    private record PendingInteraction(Entity target, String clientResult,
                                     java.util.Map<String, Object> result,
                                     CompletableFuture<Object> future,
                                     boolean autoClose, int ticksLeft) {
        PendingInteraction tickDown() {
            return new PendingInteraction(target, clientResult, result, future, autoClose, ticksLeft - 1);
        }
    }

    /** 右键后等待服务端开屏同步的 tick 数（5 tick ≈ 250ms）。 */
    private static final int SCREEN_CHECK_DELAY_TICKS = 5;

    private final java.util.List<PendingInteraction> pendingInteractions = new java.util.ArrayList<>();

    /** 无返回任务队列：只需要在 render thread 执行副作用（如取消），不产生结果。 */
    private final ConcurrentLinkedQueue<Runnable> clientRunnables = new ConcurrentLinkedQueue<>();

    /**
     * 提交一个任务到 render thread 执行，阻塞等待结果。
     *
     * @param action  在 render thread 跑的操作（可访问 MC 状态）
     * @param timeoutMs 超时毫秒；超时返回错误而不是无限等
     */
    private Object submitOnClientThread(java.util.function.Consumer<CompletableFuture<Object>> action,
                                        long timeoutMs) {
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
                task.action().accept(task.future());
            } catch (Throwable t) {
                task.future().completeExceptionally(t);
            }
        }

        // 2. 持续移动：有激活目标时每帧朝它推进。
        //    真机实测：CLIENT_POST 里 setDeltaMovement 玩家完全不动(tick 已结束、
        //    不在正常移动链路上，下一帧被输入/摩擦清掉)。故与 player.move 一致，
        //    驱动按键映射，由下一帧原版 tick 自然移动并发包；aimAt 负责转向。
        Entity target = moveTarget;
        LocalPlayer p = mc.player;
        if (target != null && p != null) {
            if (!target.isAlive() || p.distanceTo(target) <= p.entityInteractionRange() - 0.5) {
                // 到达或目标消失：停止（清目标 + 释放键，复用 stopMove）
                stopMove();
            } else {
                // aimAt 已把视角对准目标，按键映射相对玩家朝向：
                // 只按 W(keyUp)即沿视线直线走向目标，等价 player.move forward=1（已实测）。
                aimAt(p, target);
                var o = mc.options;
                o.keyUp.setDown(true);
                o.keyDown.setDown(false);
                o.keyLeft.setDown(false);
                o.keyRight.setDown(false);
            }
        }

        // 3. 攻击会话:tick() 每帧检查距离,远了继续推进,近了发起攻击。
        //    参考怪物 MeleeAttackGoal:持续 tick 驱动,触及内才攻击。
        //    攻击本身复用 performAttackOn,结果走延迟命中判定(pendingHits)。
        AttackSession atk = attackSession;
        if (atk != null && p != null) {
            Entity atkTarget = atk.target();
            CompletableFuture<Object> atkFuture = atk.future();
            if (!atkTarget.isAlive()) {
                atkFuture.completeExceptionally(new RpcException(
                        RpcErrorCodes.TARGET_NOT_FOUND, "目标已死亡"));
                attackSession = null;
                moveTarget = null;
            } else if (p.distanceTo(atkTarget) <= p.entityInteractionRange() - 0.2) {
                // 到达触及范围:发起攻击(内部登记延迟命中判定,future 由 pendingHits 完成)
                attackSession = null;
                moveTarget = null;
                performAttackOn(p, atkTarget, atkFuture);
            } else {
                // 继续朝目标推进(设 moveTarget,让上面的逻辑带动)
                moveTarget = atkTarget;
            }
        }

        // 4. 延迟命中判定:倒计 tick,到期读服务端已同步的血量组装结果。
        if (!pendingHits.isEmpty()) {
            java.util.Iterator<PendingHit> it = pendingHits.iterator();
            java.util.List<PendingHit> next = new java.util.ArrayList<>();
            while (it.hasNext()) {
                PendingHit ph = it.next();
                CompletableFuture<Object> f = ph.future();
                if (f.isDone()) {
                    continue; // 已被超时/取消处理
                }
                if (!ph.target().isAlive() && ph.hpBefore() > 0f) {
                    // 目标被打死:也算命中成功,hpAfter=0
                    f.complete(buildAttackResult(ph.target(),
                            p.distanceTo(ph.target()), ph.hpBefore(), 0f));
                    continue;
                }
                if (ph.ticksLeft() <= 1) {
                    float hpAfter = ph.target() instanceof net.minecraft.world.entity.LivingEntity le
                            ? le.getHealth() : ph.hpBefore();
                    f.complete(buildAttackResult(ph.target(),
                            p.distanceTo(ph.target()), ph.hpBefore(), hpAfter));
                } else {
                    next.add(ph.tickDown());
                }
            }
            pendingHits.clear();
            pendingHits.addAll(next);
        }

        // 5. 延迟开屏判定：倒计 tick，到期读当前屏幕，确认服务端是否真打开了交易 GUI。
        if (!pendingInteractions.isEmpty()) {
            java.util.Iterator<PendingInteraction> it2 = pendingInteractions.iterator();
            java.util.List<PendingInteraction> next2 = new java.util.ArrayList<>();
            while (it2.hasNext()) {
                PendingInteraction pi = it2.next();
                CompletableFuture<Object> f = pi.future();
                if (f.isDone()) {
                    continue;
                }
                if (pi.ticksLeft() <= 1) {
                    var screen = mc.screen;
                    boolean merchantOpen = screen instanceof net.minecraft.client.gui.screens.inventory.MerchantScreen;
                    int offerCount = -1;
                    if (merchantOpen) {
                        var offers = ((net.minecraft.client.gui.screens.inventory.MerchantScreen) screen)
                                .getMenu().getOffers();
                        offerCount = offers == null ? 0 : offers.size();
                    }
                    java.util.Map<String, Object> m = pi.result();
                    m.put("screenOpen", merchantOpen);
                    m.put("screenClass", screen == null ? "null" : screen.getClass().getSimpleName());
                    m.put("offerCount", offerCount);
                    // 判定完自动关窗，便于连续用例；保留玩家视角与位置不变。
                    // 必须发包关容器（player.closeContainer），否则服务端菜单不 removed、
                    // merchant.tradingPlayer 不清空，后续右键会被「已在交易」门控挡住。
                    if (pi.autoClose() && merchantOpen) {
                        p.closeContainer();
                    }
                    f.complete(m);
                } else {
                    next2.add(pi.tickDown());
                }
            }
            pendingInteractions.clear();
            pendingInteractions.addAll(next2);
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
        registerClientDeferred(d, "player.attack", "在触及范围内攻击最近的指定类型实体",
                List.of(RpcParamSpec.optional("target", "string", "zombie", "目标实体类型（精确匹配）")),
                6000, p -> future -> performAttackDeferred(str(p, "target", "zombie"), future));
        registerClientDeferred(d, "player.interact", "右键（使用）最近的指定类型实体，返回是否打开了交易界面",
                List.of(RpcParamSpec.optional("target", "string", "zombie", "目标实体类型（精确匹配）"),
                        RpcParamSpec.optional("autoClose", "boolean", true, "验完是否自动关闭界面（默认 true）")),
                8000, p -> future -> performInteractDeferred(
                        str(p, "target", "zombie"),
                        bool(p, "autoClose", true), future));
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
        registerClient(d, "player.useBed", "右键指定坐标的床尝试睡觉（真实 useItemOn 发包，需夜晚、附近无敌对生物）",
                List.of(RpcParamSpec.required("x", "integer", "床方块 x"),
                        RpcParamSpec.required("y", "integer", "床方块 y"),
                        RpcParamSpec.required("z", "integer", "床方块 z")),
                8000, p -> useBed((int) f(p, "x", 0), (int) f(p, "y", 0), (int) f(p, "z", 0)));

        // ── 交易 GUI（须先用 player.interact 打开怪物交易界面，autoClose=false）──
        registerClient(d, "trade.status", "读取当前交易菜单：每条报价 uses/限购/售罄、货币槽、结果槽",
                List.of(), 3000, p -> tradeStatus());
        registerClient(d, "trade.select", "选中第 index 条报价（等价点报价按钮，绿宝石挪入货币槽）",
                List.of(RpcParamSpec.required("index", "integer", "报价下标，从 0 开始")),
                5000, p -> tradeSelect((int) f(p, "index", 0)));
        registerClient(d, "trade.take", "Shift-点击结果槽成交一次（扣款、出货、uses+1、售罄同步）",
                List.of(), 5000, p -> tradeTake());
        registerClient(d, "trade.close", "关闭当前交易界面（等价按 Esc，发容器关闭包）",
                List.of(), 3000, p -> tradeClose());
    }

    /**
     * 统一注册一帧式方法：定义元数据 + body 包进任务队列切到 render thread 执行。
     * 消除每个方法重复的 submitOnClientThread 包装；body 抛出的 RpcException
     * 经队列边界透传，错误码不丢失。
     */
    private void registerClient(RpcDispatcher d, String method, String description,
                                List<RpcParamSpec> params, long timeoutMs,
                                Function<JsonObject, Object> body) {
        // 普通一帧式：body 立即产出结果，complete 自己的 future
        d.register(MethodDefinition.of(method, description, params, p -> submitOnClientThread(future -> {
            try {
                future.complete(body.apply(p));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, timeoutMs)));
    }

    /**
     * 注册需要控制完成时机的方法（如攻击后的延迟命中判定）：
     * body 返回一个接收 future 的 render-thread 动作，可立即或延迟完成。
     */
    private void registerClientDeferred(RpcDispatcher d, String method, String description,
                                        List<RpcParamSpec> params, long timeoutMs,
                                        Function<JsonObject,
                                                java.util.function.Consumer<CompletableFuture<Object>>> body) {
        d.register(MethodDefinition.of(method, description, params,
                p -> submitOnClientThread(body.apply(p), timeoutMs)));
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
        queue.add(new ClientTask(setupF -> {
            try {
                startAttackSession(typeKey, resultFuture);
                setupF.complete("已锁定目标");
            } catch (Throwable t) {
                setupF.completeExceptionally(t);
            }
        }, setupFuture));
        try {
            setupFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RpcException(RpcErrorCodes.TIMEOUT,
                    "客户端线程 5s 内未执行会话开启（可能未进世界或游戏卡顿）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RpcException re) {
                throw re;
            }
            throw new RpcException(RpcErrorCodes.INTERNAL_ERROR,
                    "开启攻击会话失败: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException(RpcErrorCodes.INTERNAL_ERROR, "等待会话开启被中断");
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
            throw new RpcException(RpcErrorCodes.TIMEOUT,
                    "攻击 15s 内未完成（目标太远或找不到路径）",
                    java.util.Map.of("target", typeKey, "timeoutMs", 15000));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RpcException re) {
                throw re;
            }
            throw new RpcException(RpcErrorCodes.INTERNAL_ERROR,
                    cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException(RpcErrorCodes.INTERNAL_ERROR, "等待攻击结果被中断");
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

    // ── 村民式交易 GUI 驱动（T-TRADE-005 真机成交）──────────────────
    //
    // 客户端 MerchantScreen 点报价按钮 = menu.tryMoveItems(i)（本地把绿宝石挪进
    // 货币槽做预测）+ 发 ServerboundSelectTradePacket（服务端 MerchantMenu 权威地
    // 再挪一次）；点结果槽成交用标准 handleInventoryMouseClick(QUICK_MOVE, slot=2)，
    // 服务端 MerchantResultSlot.onTake 扣款出货并回调 Merchant#notifyTrade。
    // 这里只做一帧式原语，多帧时序（等结果槽填充/等售罄同步包）由 Python 脚本编排。

    /** 取当前打开的交易菜单，未打开交易 GUI 时抛错。 */
    private net.minecraft.world.inventory.MerchantMenu requireMerchantMenu() {
        LocalPlayer p = requirePlayer();
        if (!(p.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu mm)) {
            throw new RpcException(RpcErrorCodes.NOT_READY,
                    "当前未打开交易界面（containerMenu="
                            + (p.containerMenu == null ? "null"
                            : p.containerMenu.getClass().getSimpleName()) + "）");
        }
        return mm;
    }

    /** 选中第 i 条报价：等价点 MerchantScreen 报价按钮（postButtonClick）。 */
    private Object tradeSelect(int index) {
        LocalPlayer p = requirePlayer();
        var mm = requireMerchantMenu();
        var offers = mm.getOffers();
        if (offers == null || index < 0 || index >= offers.size()) {
            throw new RpcException(RpcErrorCodes.INVALID_PARAMS,
                    "报价下标越界: " + index + "（共 " + (offers == null ? 0 : offers.size()) + " 条）");
        }
        mm.setSelectionHint(index);
        mm.tryMoveItems(index);   // 客户端预测：把绿宝石挪入货币槽
        p.connection.send(new net.minecraft.network.protocol.game.ServerboundSelectTradePacket(index));
        return tradeStatus(mm);
    }

    /**
     * Shift-点击结果槽（slot=2）取货成交：走原版容器点击管线，服务端 onTake 扣款、
     * 回调 Merchant#notifyTrade（uses+1）并由服务端重发报价（售罄红叉）。
     */
    private Object tradeTake() {
        LocalPlayer p = requirePlayer();
        var mm = requireMerchantMenu();
        mc.gameMode.handleInventoryMouseClick(
                mm.containerId, 2, 0,
                net.minecraft.world.inventory.ClickType.QUICK_MOVE, p);
        return tradeStatus(mm);
    }

    /** 读交易菜单当前状态：报价 uses/限购/售罄、货币槽与结果槽、产物背包总数。 */
    private Object tradeStatus() {
        return tradeStatus(requireMerchantMenu());
    }

    private java.util.Map<String, Object> tradeStatus(
            net.minecraft.world.inventory.MerchantMenu mm) {
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        var offers = mm.getOffers();
        java.util.List<Object> list = new java.util.ArrayList<>();
        if (offers != null) {
            for (int i = 0; i < offers.size(); i++) {
                net.minecraft.world.item.trading.MerchantOffer o =
                        (net.minecraft.world.item.trading.MerchantOffer) offers.get(i);
                java.util.Map<String, Object> om = new LinkedHashMap<>();
                om.put("index", i);
                om.put("uses", o.getUses());
                om.put("maxUses", o.getMaxUses());
                om.put("outOfStock", o.isOutOfStock());
                // 真实成交价（getCostA 含 demand/乘数修正；本 mod multiplier=0 即配置价）。
                // 注意：货币槽可能被客户端 tryMoveItems 填入一整组(64)，那不代表成交价。
                om.put("costA", itemName(o.getCostA()));
                om.put("costACount", o.getCostA().getCount());
                om.put("costBCount", o.getCostB().getCount());
                om.put("result", itemName(o.getResult()));
                om.put("resultCount", o.getResult().getCount());
                list.add(om);
            }
        }
        m.put("offerCount", list.size());
        m.put("offers", list);
        // tradeContainer：0=costA,1=costB,2=result（MerchantMenu 注册顺序）
        m.put("costA", describeMenuSlot(mm, 0));
        m.put("costB", describeMenuSlot(mm, 1));
        m.put("result", describeMenuSlot(mm, 2));
        // 背包+快捷栏（MerchantMenu 槽位 3..38）物品聚合 itemId->数量，供成交后核对扣款/入包。
        java.util.Map<String, Integer> inv = new java.util.LinkedHashMap<>();
        for (int s = 3; s <= 38; s++) {
            net.minecraft.world.item.ItemStack st = mm.getSlot(s).getItem();
            if (!st.isEmpty()) {
                inv.merge(itemName(st), st.getCount(), Integer::sum);
            }
        }
        m.put("inventory", inv);
        return m;
    }

    private String itemName(net.minecraft.world.item.ItemStack st) {
        return st.isEmpty() ? "" : st.getItem().builtInRegistryHolder().key().location().toString();
    }

    private java.util.Map<String, Object> describeMenuSlot(
            net.minecraft.world.inventory.MerchantMenu mm, int slot) {
        net.minecraft.world.item.ItemStack st = mm.getSlot(slot).getItem();
        java.util.Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("item", itemName(st));
        sm.put("count", st.getCount());
        return sm;
    }

    /**
     * 关闭交易界面。必须走 {@code player.closeContainer()}：它发
     * ServerboundContainerClosePacket（服务端 MerchantMenu.removed 才会 setTradingPlayer(null)）
     * 再本地 setScreen(null)。裸 mc.setScreen(null) 只触发屏幕 removed()、不发包，
     * 会让服务端一直以为玩家在交易，导致之后右键重开被「已在交易」门控挡下。
     */
    private String tradeClose() {
        LocalPlayer p = requirePlayer();
        if (!(p.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu)) {
            return "当前无交易界面，无需关闭";
        }
        p.closeContainer();
        return "已关闭交易界面";
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
                            entityTypeId(attackSession.target())));
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
    /**
     * 攻击核心：守卫通过后真实发起攻击，并把命中判定登记到 {@link #pendingHits}，
     * 等 {@link #HIT_CHECK_DELAY_TICKS} 个 tick 服务端同步血量后再完成 future。
     *
     * <p>在 render thread 执行（作为 deferred 任务的动作）。
     */
    private void performAttackDeferred(String typeKey, CompletableFuture<Object> future) {
        LocalPlayer p = requirePlayer();
        Entity target = targetResolver.nearestByType(p, typeKey, 32.0);
        if (target == null) {
            future.completeExceptionally(new RpcException(RpcErrorCodes.TARGET_NOT_FOUND,
                    "附近 " + 32 + " 格内无 " + typeKey,
                    java.util.Map.of("target", typeKey, "radius", 32)));
            return;
        }
        performAttackOn(p, target, future);
    }

    /**
     * 对已确定的目标发起真实攻击并登记延迟命中判定。
     * player.attack（当场解析目标）与攻击会话（早已锁定目标）共用此路径。
     */
    private void performAttackOn(LocalPlayer p, Entity target, CompletableFuture<Object> future) {
        if (mc.gameMode == null) {
            future.completeExceptionally(new RpcException(
                    RpcErrorCodes.NOT_READY, "gameMode 未就绪（不在多人游戏？）"));
            return;
        }
        double dist = p.distanceTo(target);
        double reach = p.entityInteractionRange();
        if (dist > reach) {
            future.completeExceptionally(new RpcException(RpcErrorCodes.OUT_OF_REACH,
                    "目标超出触及距离，需先走近",
                    java.util.Map.of("distance", round1(dist), "reach", round1(reach))));
            return;
        }
        // 攻击前血量（LivingEntity 才有，用于命中判定）
        boolean living = target instanceof net.minecraft.world.entity.LivingEntity;
        float hpBefore = living ? ((net.minecraft.world.entity.LivingEntity) target).getHealth() : 0f;
        // 真实攻击：视角对准 → 客户端攻击管线发包 → 挥臂（真人左键路径）
        aimAt(p, target);
        mc.gameMode.attack(p, target);
        p.swing(InteractionHand.MAIN_HAND);
        // 不同帧读血判定：登记延迟命中，结果由 tick() 在同步后组装完成
        pendingHits.add(new PendingHit(target, hpBefore, future, HIT_CHECK_DELAY_TICKS));
    }

    /** 组装一次攻击的结构化结果（在延迟判定到期、服务端血量已同步后调用）。 */
    private java.util.Map<String, Object> buildAttackResult(Entity target, double dist,
                                                            float hpBefore, float hpAfter) {
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", entityTypeId(target));
        m.put("distance", round1(dist));
        m.put("reach", round1(mc.player.entityInteractionRange()));
        m.put("hpBefore", hpBefore);
        m.put("hpAfter", hpAfter);
        m.put("hit", hpAfter < hpBefore);
        return m;
    }

    /**
     * 右键最近目标的延迟动作：真实发包后登记 {@link PendingInteraction}，
     * 由 tick() 在服务端开屏包到达后读屏幕状态完成 future。
     */
    private void performInteractDeferred(String typeKey, boolean autoClose,
                                         CompletableFuture<Object> future) {
        LocalPlayer p = requirePlayer();
        Entity target = targetResolver.nearestByType(p, typeKey, 32.0);
        if (target == null) {
            future.completeExceptionally(new RpcException(RpcErrorCodes.TARGET_NOT_FOUND,
                    "附近 " + 32 + " 格内无 " + typeKey,
                    java.util.Map.of("target", typeKey, "radius", 32)));
            return;
        }
        if (mc.gameMode == null) {
            future.completeExceptionally(new RpcException(
                    RpcErrorCodes.NOT_READY, "gameMode 未就绪（不在多人游戏？）"));
            return;
        }
        double dist = p.distanceTo(target);
        double reach = p.entityInteractionRange();
        if (dist > reach) {
            future.completeExceptionally(new RpcException(RpcErrorCodes.OUT_OF_REACH,
                    "目标超出触及距离，需先走近",
                    java.util.Map.of("distance", round1(dist), "reach", round1(reach))));
            return;
        }
        // 真实右键路径：与真人右键同一调用 —— MultiPlayerGameMode.interact
        // 发 ServerboundInteractPacket(INTERACT, MAIN_HAND)，并做客户端预测。
        aimAt(p, target);
        net.minecraft.world.InteractionResult result =
                mc.gameMode.interact(p, target, InteractionHand.MAIN_HAND);
        p.swing(InteractionHand.MAIN_HAND);

        java.util.Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", entityTypeId(target));
        m.put("distance", round1(dist));
        m.put("reach", round1(reach));
        m.put("clientResult", result == null ? "null" : result.toString());
        pendingInteractions.add(new PendingInteraction(
                target, result == null ? "null" : result.toString(), m,
                future, autoClose, SCREEN_CHECK_DELAY_TICKS));
    }

    private String look(float yaw, float pitch) {
        LocalPlayer p = requirePlayer();
        p.setYRot(yaw);
        p.setXRot(pitch);
        return String.format("视角已设为 yaw=%.1f pitch=%.1f", yaw, pitch);
    }

    /**
     * 右键床尝试睡觉。走真实 {@code mc.gameMode.useItemOn}（发 ServerboundUseItemOnPacket），
     * 与真人右键床同路径。要求：夜晚、床在触及范围内、附近无敌对生物（BedBlock 校验
     * 10 格内 Enemy）。是否成功/过夜由调用方据服务端时间/睡眠状态轮询。
     */
    private java.util.Map<String, Object> useBed(int x, int y, int z) {
        LocalPlayer p = requirePlayer();
        if (mc.gameMode == null) {
            throw new RpcException(RpcErrorCodes.NOT_READY, "gameMode 未就绪（不在多人游戏？）");
        }
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
        net.minecraft.world.phys.Vec3 hitVec = new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.5625, z + 0.5);
        double dist = Math.sqrt(p.distanceToSqr(hitVec));
        double reach = p.blockInteractionRange();
        if (dist > reach) {
            throw new RpcException(RpcErrorCodes.OUT_OF_REACH, "床超出触及距离，需先走近",
                    java.util.Map.of("distance", round1(dist), "reach", round1(reach)));
        }
        // 强制视线对准床顶面，保证服务端按玩家视角重做 raycast 时也命中该床格
        net.minecraft.world.phys.Vec3 eye = p.getEyePosition(1.0f);
        double dx = hitVec.x - eye.x, dy = hitVec.y - eye.y, dz = hitVec.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        p.setXRot((float) (-Math.toDegrees(Math.atan2(dy, horiz))));
        p.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                hitVec, net.minecraft.core.Direction.UP, pos, false);
        net.minecraft.world.InteractionResult result =
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        m.put("bed", x + " " + y + " " + z);
        m.put("distance", round1(dist));
        m.put("clientResult", result == null ? "null" : result.toString());
        m.put("isSleeping", p.isSleeping());
        return m;
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
                entityTypeId(target), dist, reach);
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
            em.put("type", entityTypeId(e));
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

    /** 实体的注册 ID（minecraft:zombie 形式），与 TargetResolver 接受的表示一致。 */
    private static String entityTypeId(Entity e) {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(e.getType()).toString();
    }

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
