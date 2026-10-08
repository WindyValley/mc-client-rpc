package top.windyvalley.mcclientrpc.protocol;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.function.Function;

/**
 * RPC 方法定义 —— handler 与自描述元数据一体，是协议的单一事实来源。
 *
 * <p>{@code methods.list} 直接从定义生成 schema；API 文档可从定义导出，
 * 不再与代码各维护一份。
 */
public record MethodDefinition(String name, String description,
                                List<RpcParamSpec> params,
                                Function<JsonObject, Object> handler) {

    public MethodDefinition {
        params = params == null ? List.of() : List.copyOf(params);
    }

    public static MethodDefinition of(String name, String description,
                                      List<RpcParamSpec> params,
                                      Function<JsonObject, Object> handler) {
        return new MethodDefinition(name, description, params, handler);
    }
}
