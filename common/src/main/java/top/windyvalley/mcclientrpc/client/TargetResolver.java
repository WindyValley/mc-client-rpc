package top.windyvalley.mcclientrpc.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import top.windyvalley.mcclientrpc.protocol.RpcErrorCodes;
import top.windyvalley.mcclientrpc.protocol.RpcException;

/**
 * 目标解析 —— 把「目标描述」解析为具体实体。
 *
 * <p>当前支持按<b>实体类型精确匹配</b>（{@code byTypeId} / {@code nearestByType}）：
 * 用注册名（{@code minecraft:zombie}）比较，不再做类型字符串子串匹配——
 * 子串匹配会让 {@code zombie} 误命中 husk、drowned、zombie_villager、
 * zombified_piglin 等。未带命名空间的输入按 {@code minecraft:} 处理。
 *
 * <p>后续扩展点：byName（自定义名）、byUuid 等新增为同类方法，
 * 调用方按需选择，不影响已有方法。
 */
public final class TargetResolver {

    /** 把类型引用规范化为 ResourceLocation；非法引用抛 INVALID_PARAMS。 */
    public ResourceLocation normalizeType(String typeRef) {
        if (typeRef == null || typeRef.isBlank()) {
            throw new RpcException(RpcErrorCodes.INVALID_PARAMS, "目标类型不能为空");
        }
        String s = typeRef.trim();
        if (!s.contains(":")) {
            s = "minecraft:" + s;
        }
        ResourceLocation rl = ResourceLocation.tryParse(s);
        if (rl == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
            throw new RpcException(RpcErrorCodes.INVALID_PARAMS,
                    "无法识别的实体类型: " + typeRef);
        }
        return rl;
    }

    /** 半径内指定类型的实体中，取距离玩家最近的一个；无匹配返回 null。 */
    public Entity nearestByType(LocalPlayer p, String typeRef, double radius) {
        ResourceLocation wanted = normalizeType(typeRef);
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(radius),
                x -> EntityType.getKey(x.getType()).equals(wanted))) {
            double d = p.distanceTo(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }
}
