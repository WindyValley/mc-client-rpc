package top.windyvalley.mcclientrpc.protocol;

import top.windyvalley.mcclientrpc.McClientRpc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JSON-RPC TCP 服务器 —— 独立 accept 线程 + 每客户端线程池。
 *
 * <p>每行一个 JSON-RPC 请求，回一行响应。协议解析委托 {@link RpcDispatcher}。
 * 本类只管网络，不管业务。
 *
 * <p>绑定地址默认 loopback（{@code 127.0.0.1}）：这是受信本地测试设施，
 * 默认不接受外部网卡的连接；确需远程控制时显式传入 bind 地址。
 *
 * <p>线程模型教训（来自服务端版）：accept 循环必须独占一个线程，
 * 客户端处理用 cachedPool——若共用单线程池，accept 占满唯一线程，
 * handler 永远排不上队，表现为「连得上但永不响应」。
 */
public final class RpcServer {

    /** 默认绑定地址：仅本机回环。 */
    public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";

    private final RpcDispatcher dispatcher;
    private final String bindAddress;
    private final int port;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService clientPool;
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
                try {
                    Socket client = ss.accept();
                    clientPool.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (running) {
                        McClientRpc.LOGGER.warn("[RPC] 接受连接异常", e);
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                McClientRpc.LOGGER.error("[RPC] 无法绑定 {}:{}", bindAddress, port, e);
            }
        }
    }

    private void handleClient(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(client.getOutputStream(), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                out.println(dispatcher.handle(line));
            }
        } catch (IOException e) {
            McClientRpc.LOGGER.debug("[RPC] 客户端连接关闭", e);
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
