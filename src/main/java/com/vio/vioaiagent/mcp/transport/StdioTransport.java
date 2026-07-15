package com.vio.vioaiagent.mcp.transport;

import com.vio.vioaiagent.exception.BusinessException;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcCodec;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcRequest;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * STDIO 传输适配器.
 *
 * <p>通过启动子进程并通过标准输入/输出（stdin/stdout）进行 JSON-RPC 通信.
 * 每行一个完整的 JSON-RPC 消息, 使用换行符（\n）作为消息分隔符.
 *
 * <p>线程安全：使用 {@code synchronized} 保护 send 操作, 避免多线程
 * 同时写入 stdin 导致消息交错.
 *
 * <pre>{@code
 * StdioTransport.ServerParameters params = new StdioTransport.ServerParameters(
 *     "java", List.of("-jar", "server.jar"), Map.of("API_KEY", "xxx")
 * );
 * StdioTransport transport = new StdioTransport(codec, params);
 * transport.connect();
 * JsonRpcResponse response = transport.send(request);
 * transport.close();
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class StdioTransport implements McpTransport {

    private final JsonRpcCodec codec;
    private final ServerParameters params;

    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;
    private volatile boolean connected;

    /**
     * @param codec  JSON-RPC 编解码器
     * @param params 子进程启动参数
     */
    public StdioTransport(JsonRpcCodec codec, ServerParameters params) {
        this.codec = codec;
        this.params = params;
    }

    @Override
    public void connect() {
        if (connected) {
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(params.command);
            // 将 args 合并到 command 列表
            if (params.args != null && !params.args.isEmpty()) {
                pb.command().addAll(params.args);
            }
            // 设置环境变量
            if (params.env != null) {
                pb.environment().putAll(params.env);
            }
            // 重定向 stderr 到 inherit（便于调试）, stdin/stdout 由代码读写
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            process = pb.start();
            // getInputStream = 子进程的 stdout（我们从这里读取）
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            // getOutputStream = 子进程的 stdin（我们向这里写入）
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            connected = true;
            log.info("STDIO 传输已连接: {} {}", params.command, String.join(" ", params.args != null ? params.args : List.of()));
        } catch (IOException e) {
            throw new BusinessException("STDIO 传输连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized JsonRpcResponse send(JsonRpcRequest request) {
        if (!connected) {
            throw new BusinessException("STDIO 传输未连接, 请先调用 connect()");
        }
        try {
            // 写：JSON-RPC 请求 → stdin（一行一个完整 JSON）
            String requestJson = codec.encode(request);
            writer.write(requestJson);
            writer.newLine();
            writer.flush();
            log.debug("STDIO 发送: {}", requestJson);

            // 读：从 stdout 读取一行 JSON-RPC 响应
            String responseJson = reader.readLine();
            if (responseJson == null) {
                // 子进程 stdout 关闭（进程可能崩溃了）
                connected = false;
                throw new BusinessException("STDIO 子进程 stdout 已关闭, 进程可能已退出. 退出码: " + exitCode());
            }
            log.debug("STDIO 接收: {}", responseJson);
            return codec.decodeResponse(responseJson);
        } catch (IOException e) {
            connected = false;
            throw new BusinessException("STDIO 传输通信失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        connected = false;
        if (process != null) {
            // 优雅关闭：先尝试正常退出
            try {
                if (writer != null) {
                    writer.close();
                }
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException ignored) {
            }

            boolean exited = false;
            try {
                exited = process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (!exited) {
                // 强制终止
                process.destroyForcibly();
                log.warn("STDIO 子进程未在 5 秒内退出, 已强制终止");
            }
            log.info("STDIO 传输已关闭, 退出码: {}", exitCode());
        }
    }

    @Override
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    private int exitCode() {
        return process != null && !process.isAlive() ? process.exitValue() : -1;
    }

    // ==================== 内部类型 ====================

    /**
     * 子进程启动参数 — 对应 MCP 配置中的服务器定义.
     *
     * @param command 可执行文件路径（如 "java", "npx"）
     * @param args    命令行参数列表
     * @param env     环境变量
     */
    public record ServerParameters(String command, List<String> args, Map<String, String> env) {
    }
}
