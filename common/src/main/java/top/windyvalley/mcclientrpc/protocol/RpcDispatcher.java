package top.windyvalley.mcclientrpc.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JSON-RPC 2.0 协议分发器 —— 与 MC 完全解耦，纯文本进/出，可纯 JUnit 测试。
 *
 * <p>职责：解析请求行 → 按 method 路由到注册的 handler → 包装响应。
 * 不知道任何 MC 类型；handler 由上层（client 包）注册，
 * 输入输出都是 {@link JsonObject} / {@link Object}。
 *
 * <p>线程安全：注册在启动时完成（单线程），之后只读。
 */
public final class RpcDispatcher {

    private static final Gson GSON = new Gson();

    /** method → handler(params) -> result。 */
    private final Map<String, Function<JsonObject, Object>> handlers = new LinkedHashMap<>();

    /** 注册一个方法处理器。 */
    public RpcDispatcher register(String method, Function<JsonObject, Object> handler) {
        handlers.put(method, handler);
        return this;
    }

    /** 已注册的方法名（用于 methods.list 自描述）。 */
    public java.util.Set<String> methods() {
        return handlers.keySet();
    }

    /**
     * 处理一行 JSON-RPC 请求，返回响应 JSON 文本。
     *
     * <p>协议：{@code {"jsonrpc":"2.0","method":"m","params":{...},"id":N}}
     * 成功返回 {@code result}，失败返回 {@code error:{code,message}}。
     * 解析失败 code=-32700，未知方法 code=-32601，handler 异常 code=-32603。
     */
    public String handle(String jsonLine) {
        JsonObject resp = new JsonObject();
        resp.addProperty("jsonrpc", "2.0");

        // ── 第一阶段：解析请求 ──
        // 解析失败（语法错 / 顶层不是对象 / method 不是字符串）统一 -32700。
        // getAsJsonObject() 抛的 IllegalStateException("Not a JSON object") 属此类。
        JsonObject req;
        int id = 0;
        try {
            req = JsonParser.parseString(jsonLine).getAsJsonObject();
            if (req.has("id") && !req.get("id").isJsonNull()) {
                id = req.get("id").getAsInt();
            }
        } catch (Exception e) {
            return error(resp, 0, -32700, "JSON 解析失败: " + e.getMessage());
        }
        resp.addProperty("id", id);

        // ── 第二阶段：路由 + 执行业务 handler ──
        // handler 抛的异常（含 IllegalStateException，如「玩家未进入世界」）属业务错误 -32603。
        try {
            if (!req.has("method")) {
                return error(resp, id, -32600, "缺少 method 字段");
            }
            String method = req.get("method").getAsString();
            Function<JsonObject, Object> handler = handlers.get(method);
            if (handler == null) {
                return error(resp, id, -32601, "未知方法: " + method);
            }
            JsonObject params = req.has("params") && req.get("params").isJsonObject()
                    ? req.getAsJsonObject("params") : new JsonObject();
            Object result = handler.apply(params);
            resp.add("result", GSON.toJsonTree(result));
        } catch (Exception e) {
            return error(resp, id, -32603, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return GSON.toJson(resp);
    }

    private String error(JsonObject resp, int id, int code, String message) {
        resp.addProperty("id", id);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        resp.add("error", err);
        return GSON.toJson(resp);
    }
}
