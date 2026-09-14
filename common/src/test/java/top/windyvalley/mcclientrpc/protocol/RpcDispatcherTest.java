package top.windyvalley.mcclientrpc.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

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
    void 非法JSON返回32700() {
        RpcDispatcher d = new RpcDispatcher();
        JsonObject resp = parse(d.handle("这不是json"));
        assertEquals(-32700, resp.getAsJsonObject("error").get("code").getAsInt());
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
    void id为null时按0处理() {
        RpcDispatcher d = new RpcDispatcher();
        d.register("x", p -> "ok");
        JsonObject resp = parse(d.handle("{\"jsonrpc\":\"2.0\",\"method\":\"x\",\"id\":null}"));
        assertEquals(0, resp.get("id").getAsInt());
        assertEquals("ok", resp.get("result").getAsString());
    }
}
