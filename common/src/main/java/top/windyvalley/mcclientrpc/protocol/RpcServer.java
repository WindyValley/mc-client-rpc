package top.windyvalley.mcclientrpc.protocol;

import top.windyvalley.mcclientrpc.McClientRpc;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSON-RPC TCP 服务器 —— 独立 accept 线程 + 每客户端线程池。
 *
 * <p>每行一个 JSON-RPC 请求，回一行响应。协议解析委托 {@link RpcDispatcher}。
 * 本类只管网络，不管业务。
 *
 * <p>绑定地址默认 loopback（{@code 127.0.0.1}）：这是受信本地测试设施，
 * 默认不接受外部网卡的连接；确需远程控制时显式传入 bind 地址。
 *
 * <p>资源防护：单行长度上限（防不换行大报文撑内存）、socket 读超时
 * （防连上不发数据永久占线程）、最大连接数（防连接耗尽）。
 *
 * <p>线程模型教训（来自服务端版）：accept 循环必须独占一个线程，
 * 客户端处理用 cachedPool——若共用单线程池，accept 占满唯一线程，
 * handler 永远排不上队，表现为「连得上但永不响应」。
 */
public final class RpcServer {

    /** 默认绑定地址：仅本机回环。 */
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";

    /** 单行请求最大字节数（超出即断开连接）。 */
    public static final int MAX_LINE_LENGTH = 1_048_576; // 1 MB

    /** 两次读取之间的最大间隔（毫秒）：超时判定为死连接。 */
    public static final int READ_TIMEOUT_MS = 30_000;

    /** 最大并发连接数。 */
    public static final int MAX_CONNECTIONS = 16;

    private final RpcDispatcher dispatcher;
    private final String bindAddress;
    private final int port;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService clientPool;
    private final AtomicInteger activeConnections = new AtomicInteger();
    private volatile boolean running;

    public RpcServer(RpcDispatcher dispatcher, int port) {
        this(dispatcher, DEFAULT_BIND_ADDRESS, port);
    }

    public RpcServer(RpcDispatcher dispatcher, String bindAddress, int port) {
        this.dispatcher = dispatcher;
        this.bindAddress = bindAddress;
        this.port = port;
    }

    /** 启动监听（非阻塞，立即返回）。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        clientPool = Executors.newCachedThreadPool(r -> daemon(r, "mcrpc-client"));
        acceptThread = new Thread(this::listenLoop, "mcrpc-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        McClientRpc.LOGGER.info("[RPC] 服务已启动，监听 {}:{}", bindAddress, port);
    }

    /** 停止服务。 */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            McClientRpc.LOGGER.warn("[RPC] 关闭 socket 异常", e);
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        if (clientPool != null) {
            clientPool.shutdownNow();
        }
        McClientRpc.LOGGER.info("[RPC] 服务已停止");
    }

    public int port() {
        return port;
    }

    private void listenLoop() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(bindAddress, port));
            this.serverSocket = ss;
            while (running) {
                Socket client = ss.accept();
                if (activeConnections.get() >= MAX_CONNECTIONS) {
                    // 连接数已满：拒绝并立即关闭，不入池
                    McClientRpc.LOGGER.warn("[RPC] 连接数已达上限 {}，拒绝新连接", MAX_CONNECTIONS);
                    client.close();
                    continue;
                }
                clientPool.submit(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (running) {
                McClientRpc.LOGGER.error("[RPC] 无法绑定 {}:{}", bindAddress, port, e);
            }
        }
    }

    private void handleClient(Socket client) {
        activeConnections.incrementAndGet();
        try (client;
             Reader raw = new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8);
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {
            client.setSoTimeout(READ_TIMEOUT_MS);
            String line;
            while ((line = readLineWithLimit(raw)) != null) {
                if (line.isBlank()) {
                    continue;
                }
                RpcDispatcher.Response response = dispatcher.dispatch(line);
                // notification（无 id 请求）：按协议不回响应
                if (response.notification()) {
                    continue;
                }
                out.write(response.json());
                out.write('\n');
                out.flush();
            }
        } catch (LineTooLongException e) {
            McClientRpc.LOGGER.warn("[RPC] 请求行超过 {} 字节上限，断开连接", MAX_LINE_LENGTH);
        } catch (IOException e) {
            McClientRpc.LOGGER.debug("[RPC] 客户端连接关闭", e);
        } finally {
            activeConnections.decrementAndGet();
        }
    }

    /**
     * 读取一行（以 {@code \n} 结束，兼容前导 {@code \r}），带长度上限。
     *
     * @throws LineTooLongException 行长度超过 {@link #MAX_LINE_LENGTH}
     */
    private static String readLineWithLimit(Reader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\r') {
                    sb.deleteCharAt(sb.length() - 1);
                }
                return sb.toString();
            }
            if (sb.length() >= MAX_LINE_LENGTH) {
                throw new LineTooLongException();
            }
            sb.append((char) c);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** 行过长标记异常（内部使用）。 */
    private static final class LineTooLongException extends IOException {
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
