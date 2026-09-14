package top.windyvalley.mcclientrpc.fabric;

import net.fabricmc.api.ClientModInitializer;
import top.windyvalley.mcclientrpc.McClientRpc;

/**
 * Fabric 平台入口（仅客户端）。
 *
 * <p>只做转发，不含业务逻辑——所有实现在 common 模块的
 * {@link McClientRpc#initClient()}。
 */
public final class McClientRpcFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        McClientRpc.initClient();
    }
}
