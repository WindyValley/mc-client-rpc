package top.windyvalley.mcclientrpc.protocol;

/**
 * RPC 参数规格 —— 用于 methods.list 自描述与（未来的）统一参数校验。
 *
 * <p>字段：参数名、类型（{@code string/integer/float/boolean}）、是否必传、
 * 默认值（可选参数才有）、说明。
 */
public record RpcParamSpec(String name, String type, boolean required,
                           Object defaultValue, String description) {

    public static RpcParamSpec required(String name, String type, String description) {
        return new RpcParamSpec(name, type, true, null, description);
    }

    public static RpcParamSpec optional(String name, String type, Object defaultValue,
                                        String description) {
        return new RpcParamSpec(name, type, false, defaultValue, description);
    }
}
