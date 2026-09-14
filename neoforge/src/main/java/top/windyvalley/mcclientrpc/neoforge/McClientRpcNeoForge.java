package top.windyvalley.mcclientrpc.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import top.windyvalley.mcclientrpc.McClientRpc;

/**
 * NeoForge 平台入口（仅客户端）。
 *
 * <p>{@code dist = Dist.CLIENT} 声明本 mod 只在客户端加载——
 * 服务端无需也无权访问 Minecraft.getInstance()。
 */
@Mod(value = McClientRpc.MOD_ID, dist = Dist.CLIENT)
public final class McClientRpcNeoForge {

    public McClientRpcNeoForge() {
        McClientRpc.initClient();
    }
}
