package top.windyvalley.mcclientrpc.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * RpcDispatcher 协议分发测试 —— 纯 JUnit，不依赖 MC runtime。
 *
 * <p>协议层与 MC 解耦是本框架的设计要点：解析、路由、错误包装
 * 都能在纯 JUnit 下钉死，只有「操控 MC 状态」那层（client 包）
 * 需要真机验证。
 */
class RpcDispatcherTest {

    private JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void 成功调用返回result() {
        RpcDispatcher d = new RpcDispatcher();
        d.register("echo", params -> "你好 " + params.get("name").getAsString());

        JsonObject resp = parse(d.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":{\"name\":\"世界\"},\"id\":7}"));

        assertEquals(7, resp.get("id").getAsInt());
        assertEquals("你好 世界", resp.get("result").getAsString());
        assertFalse(resp.has("error"), "成功响应不应有 error 字段");
    }

    @Test
    void 未知方法返回32601() {
        RpcDispatcher d = new RpcDispatcher();
        JsonObject resp = parse(d.handle("{\"jsonrpc\":\"2.0\",\"method\":\"nope\",\"id\":1}"));

        assertEquals(-32601, resp.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(resp.getAsJsonObject("error").get("message").getAsString().contains("nope"));
    }

    @Test
    void 缺少method返回32600() {
        RpcDispatcher d = new RpcDispatcher();
        JsonObject resp = parse(d.handle("{\"jsonrpc\":\"2.0\",\"id\":2}"));
        assertEquals(-32600, resp.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void 非法JSON返回32700且id为null() {
        RpcDispatcher d = new RpcDispatcher();
        JsonObject resp = parse(d.handle("这不是json"));
        assertEquals(-32700, resp.getAsJsonObject("error").get("code").getAsInt());
        assertTrue(resp.get("id").isJsonNull(), "解析失败时无法取得 id，应返回 null");
    }

    @Test
    void handler异常返回32603且不泄露堆栈() {
        RpcDispatcher d = new RpcDispatcher();
        d.register("boom", params -> {
            throw new IllegalStateException("玩家未进入世界");
        });

        JsonObject resp = parse(d.handle("{\"jsonrpc\":\"2.0\",\"method\":\"boom\",\"id\":9}"));
        JsonObject err = resp.getAsJsonObject("error");
        assertEquals(-32603, err.get("code").getAsInt());
        assertTrue(err.get("message").getAsString().contains("玩家未进入世界"));
        assertEquals(9, resp.get("id").getAsInt(), "异常时 id 应保留");
    }

    @Test
    void 无params时handler收到空对象() {
        RpcDispatcher d = new RpcDispatcher();
        d.register("check", params -> params.size() == 0 ? "空" : "非空");
        JsonObject resp = parse(d.handle("{\"jsonrpc\":\"2.0\",\"method\":\"check\",\"id\":3}"));
        assertEquals("空", resp.get("result").getAsString());
    }

    @Test
    void methodsList返回已注册方法() {
        RpcDispatcher d = new RpcDispatcher();
        d.register("a", p -> 1).register("b", p -> 2);
        assertTrue(d.methods().contains("a"));
        assertTrue(d.methods().contains("b"));
        assertEquals(2, d.methods().size());
    }

    @Test
    void id缺失或为null时返回JSON_null() {
        RpcDispatcher d = new RpcDispatcher();
        d.register("x", p -> "ok");
        JsonObject noId = parse(d.handle("{\"jsonrpc\":\"2.0\",\"method\":\"x\"}"));
        assertTrue(noId.get("id").isJsonNull());
        JsonObject nullId = parse(d.handle("{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"id\":null}"));
        assertTrue(nullId.get("id").isJsonNull());
    }

    @Test
    void 字符串id原样回传() {
        RpcDispatcher d = new RpcDispatcher();
        d.register("x", p -> "ok");
        JsonObject resp = parse(d.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"id\":\"req-abc-001\"}"));
        assertEquals("req-abc-001", resp.get("id").getAsString());
        assertEquals("ok", resp.get("result").getAsString());
    }

    @Test
    void rpcException透传错误码与data() {
        RpcDispatcher d = new RpcDispatcher();
        d.register("far", params -> {
            throw new RpcException(RpcErrorCodes.OUT_OF_REACH, "超出触及距离",
                    java.util.Map.of("distance", 6.2, "reach", 3.0));
        });
        JsonObject resp = parse(d.handle("{\"jsonrpc\":\"2.0\",\"method\":\"far\",\"id\":5}"));
        JsonObject err = resp.getAsJsonObject("error");
        assertEquals(-32003, err.get("code").getAsInt());
        assertEquals(6.2, err.getAsJsonObject("data").get("distance").getAsDouble());
        assertEquals(3.0, err.getAsJsonObject("data").get("reach").getAsDouble());
    }

    @Test
    void rpcException无data时不携带data字段() {
        RpcDispatcher d = new RpcDispatcher();
        d.register("nf", params -> {
            throw new RpcException(RpcErrorCodes.TARGET_NOT_FOUND, "无目标");
        });
        JsonObject resp = parse(d.handle("{\"jsonrpc\":\"2.0\",\"method\":\"nf\",\"id\":6}"));
        JsonObject err = resp.getAsJsonObject("error");
        assertEquals(-32002, err.get("code").getAsInt());
        assertFalse(err.has("data"));
    }

    @Test
    void methodDefinition注册后可路由且definitions含元数据() {
        RpcDispatcher d = new RpcDispatcher();
        d.register(MethodDefinition.of("echo2", "回显测试",
                List.of(RpcParamSpec.required("word", "string", "要回显的词")),
                params -> "回显:" + params.get("word").getAsString()));

        // 元数据可通过 definitions() 取得
        MethodDefinition def = d.definitions().iterator().next();
        assertEquals("echo2", def.name());
        assertEquals("回显测试", def.description());
        assertEquals("word", def.params().get(0).name());
        assertTrue(def.params().get(0).required());

        // handle 能按定义路由
        JsonObject resp = parse(d.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"echo2\",\"params\":{\"word\":\"你好\"},\"id\":11}"));
        assertEquals("回显:你好", resp.get("result").getAsString());
    }
}
