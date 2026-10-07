package top.windyvalley.mcclientrpc.protocol;

/**
 * JSON-RPC 错误码常量。
 *
 * <p>JSON-RPC 2.0 标准预留码：
 * <ul>
 *   <li>-32700 parse error / -32600 invalid request / -32601 method not found</li>
 *   <li>-32602 invalid params / -32603 internal error</li>
 * </ul>
 *
 * <p>-32000 ~ -32099 为服务端自定义业务错误码区间（本框架使用）。
 */
public final class RpcErrorCodes {

    // ── 标准预留 ──
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    // ── 业务错误码（-32000 ~ -32099）──
    /** 客户端 / 玩家状态不满足调用前提（如未进入世界）。 */
    public static final int NOT_READY = -32001;
    /** 未找到匹配目标。 */
    public static final int TARGET_NOT_FOUND = -32002;
    /** 目标超出触及距离。 */
    public static final int OUT_OF_REACH = -32003;
    /** 已有进行中的高层操作，拒绝并发提交。 */
    public static final int BUSY = -32004;

    private RpcErrorCodes() {
    }
}
