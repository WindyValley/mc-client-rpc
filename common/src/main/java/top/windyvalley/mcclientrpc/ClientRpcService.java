package top.windyvalley.mcclientrpc;

import top.windyvalley.mcclientrpc.client.ClientPlayerController;
import top.windyvalley.mcclientrpc.protocol.RpcDispatcher;
import top.windyvalley.mcclientrpc.protocol.RpcServer;

/**
 * 客户端 RPC 服务 —— 组装协议层、网络层与玩家操控器。
 *
 * <p>生命周期随客户端（非服务端）。tick 由平台层 ClientTickEvent 驱动，
 * 每帧调用 {@link #tick()} 让排队的操控任务在 render thread 执行。
 */
public final class ClientRpcService {

    private final ClientPlayerController controller = new ClientPlayerController();
    private final RpcDispatcher dispatcher = new RpcDispatcher();
    private RpcServer server;

    /** 启动 RPC 服务并注册全部操控方法。 */
    public void start() {
        controller.registerHandlers(dispatcher);
        server = new RpcServer(dispatcher, ClientPlayerController.DEFAULT_PORT);
        server.start();
    }

    /** 客户端 tick（render thread）驱动：执行排队的操控任务。 */
    public void tick() {
        controller.tick();
    }

    /** 停止服务。 */
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    /** 玩家操控器（供测试/平台层访问）。 */
    public ClientPlayerController controller() {
        return controller;
    }
}
