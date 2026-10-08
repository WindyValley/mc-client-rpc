package top.windyvalley.mcclientrpc.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * JSON-RPC 2.0 协议分发器 —— 与 MC 完全解耦，纯文本进/出，可纯 JUnit 测试。
 *
 * <p>职责：解析请求行 → 按 method 路由到注册的 handler → 包装响应。
 * 不知道任何 MC 类型；handler 由上层（client 包）注册，
 * 输入输出都是 {@link JsonObject} / {@link Object}。
 *
 * <p>错误分层：
 * <ul>
 *   <li>{@link RpcException}：预期业务错误，保留其 code 与 data</li>
 *   <li>其他 handler 异常：统一 -32603 internal error</li>
 * </ul>
 *
 * <p>线程安全：注册在启动时完成（单线程），之后只读。
 */
public final class RpcDispatcher {

    // serializeNulls：id 为 JSON null 时必须出现在响应里（默认 Gson 会跳过 null 成员）
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    /** method → 方法定义（handler + 元数据）。 */
    private final Map<String, MethodDefinition> handlers = new LinkedHashMap<>();

    /** 注册完整方法定义（含参数 schema，是协议的单一事实来源）。 */
    public RpcDispatcher register(MethodDefinition definition) {
        handlers.put(definition.name(), definition);
        return this;
    }

    /** 便捷注册：仅有 handler、无元数据（自描述只显示方法名）。 */
    public RpcDispatcher register(String method, Function<JsonObject, Object> handler) {
        return register(new MethodDefinition(method, "", List.of(), handler));
    }

    /** 已注册的方法名（用于自描述）。 */
    public java.util.Set<String> methods() {
        return handlers.keySet();
    }

    /** 已注册的方法定义（methods.list 据此生成 schema）。 */
    public java.util.Collection<MethodDefinition> definitions() {
        return handlers.values();
    }

    /**
     * 处理一行 JSON-RPC 请求。
     *
     * @return Response：{@code notification=true} 时调用方不得回写内容；
     *         否则 {@code json} 为要回写的响应文本
     */
    public Response dispatch(String jsonLine) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");

        // ── 第一阶段：解析请求 ──
        // 解析失败（语法错 / 顶层不是对象）统一 -32700，此时无法取得 id。
        JsonObject req;
        JsonElement id = null;
        try {
            req = JsonParser.parseString(jsonLine).getAsJsonObject();
            if (req.has("id") && !req.get("id").isJsonNull()) {
                id = req.get("id");
            }
        } catch (Exception e) {
            return new Response(false,
                    error(resp, null, RpcErrorCodes.PARSE_ERROR, "JSON 解析失败: " + e.getMessage(), null));
        }
        // 无 id（缺失 / JsonNull）= notification：按协议不回响应
        boolean notification = id == null;
        writeId(resp, id);

        // ── 第二阶段：路由 + 执行业务 handler ──
        String responseJson;
        try {
            if (req.has("jsonrpc") && !"2.0".equals(req.get("jsonrpc").getAsString())) {
                throw new RpcException(RpcErrorCodes.INVALID_REQUEST,
                        "不支持的 jsonrpc 版本: " + req.get("jsonrpc").getAsString());
            }
            if (!req.has("method")) {
                return new Response(notification,
                        error(resp, id, RpcErrorCodes.INVALID_REQUEST, "缺少 method 字段", null));
            }
            String method = req.get("method").getAsString();
            MethodDefinition definition = handlers.get(method);
            if (definition == null) {
                return new Response(notification,
                        error(resp, id, RpcErrorCodes.METHOD_NOT_FOUND, "未知方法: " + method, null));
            }
            JsonObject params;
            if (!req.has("params") || req.get("params").isJsonNull()) {
                params = new JsonObject();
            } else if (req.get("params").isJsonObject()) {
                params = req.getAsJsonObject("params");
            } else {
                // by-position（params 数组）当前不支持：明确报错，不静默按空对象走默认值
                throw new RpcException(RpcErrorCodes.INVALID_REQUEST,
                        "params 必须是 JSON 对象（按名称传参）");
            }
            Object result = definition.handler().apply(params);
            resp.add("result", GSON.toJsonTree(result));
            responseJson = GSON.toJson(resp);
        } catch (RpcException e) {
            // 预期业务错误：保留稳定错误码与 data
            responseJson = error(resp, id, e.code(), e.getMessage(), e.data());
        } catch (Exception e) {
            responseJson = error(resp, id, RpcErrorCodes.INTERNAL_ERROR,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
        // notification：即使处理中出错也不回响应（JSON-RPC 规定）
        return notification ? Response.NOTIFICATION : new Response(false, responseJson);
    }

    /** {@link #handle(String)} 的结果载体。 */
    public record Response(boolean notification, String json) {
        public static final Response NOTIFICATION = new Response(true, null);
    }

    /**
     * 兼容入口：处理一行请求并返回响应文本。
     *
     * <p>注意：notification（无 id）返回 {@code null}，调用方不应回写；
     * 需要 notification 标记时使用 {@link #dispatch(String)}。
     */
    public String handle(String jsonLine) {
        return dispatch(jsonLine).json();
    }

    private String error(JsonObject resp, JsonElement id, int code, String message, Object data) {
        writeId(resp, id);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        if (data != null) {
            err.add("data", GSON.toJsonTree(data));
        }
        resp.add("error", err);
        return GSON.toJson(resp);
    }

    /** id 原样回传；为 null（缺失 / JsonNull / 无法解析）时写 JSON null。 */
    private static void writeId(JsonObject resp, JsonElement id) {
        resp.add("id", id == null ? JsonNull.INSTANCE : id);
    }
}
