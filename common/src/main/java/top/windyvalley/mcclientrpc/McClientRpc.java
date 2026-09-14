package top.windyvalley.mcclientrpc;

import dev.architectury.event.events.client.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mc-client-rpc 公共入口。
 *
 * <p>客户端自动化测试框架：在客户端进程内开 JSON-RPC TCP 服务，
 * 接收外部指令操控客户端玩家实体（真实输入驱动），
 * 用于真机行为自动化验证。
 *
 * <p>与服务端 RPC 的本质区别：本框架操控的是<b>客户端的输入系统</b>——
 * 调用 {@code Minecraft.getInstance()} 的攻击/移动/视角 API，让客户端
 * 真的发出攻击包/移动包，走完整的 输入→网络→服务端 管线。
 * 服务端 RPC 只能伪造伤害来源（服务端替玩家「撒谎」），
 * 本框架是玩家「真的在操作」。
 *
 * <p>两端（Fabric / NeoForge）的平台入口类都调用 {@link #initClient()}，
 * 所有业务逻辑集中在 common 模块。
 */
public final class McClientRpc {

    public static final String MOD_ID = "mcclientrpc";

    public static final Logger LOGGER = LoggerFactory.getLogger("mc-client-rpc");

    /** 客户端 RPC 服务单例。 */
    private static ClientRpcService rpcService;

    private McClientRpc() {
    }

    /**
     * 客户端初始化入口，由各平台的客户端入口类调用。
     *
     * <p>只在客户端环境调用（{@code @Environment(EnvType.CLIENT)}）。
     */
    public static void initClient() {
        LOGGER.info("mc-client-rpc 客户端初始化");

        // 启动 JSON-RPC 服务（默认端口 25586，区别于服务端验证设施的 25585）
        rpcService = new ClientRpcService();
        rpcService.start();

        // 客户端 tick 驱动：每帧在 render thread 执行排队的操控任务。
        // 这是「真实输入驱动」的安全前提——MC 状态只能在 render thread 访问。
        ClientTickEvent.CLIENT_POST.register(mc -> {
            if (rpcService != null) {
                rpcService.tick();
            }
        });

        LOGGER.info("mc-client-rpc 客户端初始化完成");
    }

    /** 客户端 RPC 服务。 */
    public static ClientRpcService rpc() {
        return rpcService;
    }
}
