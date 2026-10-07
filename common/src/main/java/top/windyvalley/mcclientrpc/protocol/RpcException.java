package top.windyvalley.mcclientrpc.protocol;

/**
 * 可透传的 JSON-RPC 业务异常 —— handler 主动抛出时，dispatcher 保留其错误码与 data。
 *
 * <p>与「handler 抛出未知异常被兜成 -32603」的区别：本异常表达调用方可以
 * 程序化处理的预期错误（未进世界、找不到目标、超距、忙等），错误码稳定，
 * 不依赖中文文案匹配。
 *
 * <p>code 取值：
 * <ul>
 *   <li>JSON-RPC 标准预留码（如 -32602 invalid params）</li>
 *   <li>-32000 ~ -32099：本框架自定义业务错误码（见 RpcErrorCodes）</li>
 * </ul>
 */
public final class RpcException extends RuntimeException {

    private final int code;
    private final transient Object data;

    public RpcException(int code, String message) {
        this(code, message, null);
    }

    public RpcException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public Object data() {
        return data;
    }
}
