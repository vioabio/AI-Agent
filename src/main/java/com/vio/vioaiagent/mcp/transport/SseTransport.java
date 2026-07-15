package com.vio.vioaiagent.mcp.transport;

import com.vio.vioaiagent.exception.BusinessException;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcCodec;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcRequest;
import com.vio.vioaiagent.mcp.jsonrpc.JsonRpcResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * SSE 传输适配器.
 *
 * <p>通过 HTTP SSE（Server-Sent Events）与 MCP 服务端通信.
 * 流程如下：
 * <ol>
 *   <li>调用 {@link #connect()} 建立 SSE 长连接, 从首个事件中提取 session 端点 URI</li>
 *   <li>调用 {@link #send(JsonRpcRequest)} 将 JSON-RPC 请求 POST 到 message 端点,
 *       并通过 SSE 连接接收响应</li>
 *   <li>调用 {@link #close()} 断开 SSE 连接</li>
 * </ol>
 *
 * <p>SSE 协议的手动实现基于 RFC 6202, 避免了引入额外的 SSE 依赖.
 *
 * @author vio
 */
@Slf4j
public class SseTransport implements McpTransport {

    private final JsonRpcCodec codec;
    private final String baseUrl;
    private final String sseEndpoint;
    private final String messageEndpoint;

    private final HttpClient httpClient;
    private final RestClient restClient;

    private java.net.http.HttpResponse.BodyHandler<String> sseBodyHandler;
    private volatile String sessionMessageUri;
    private volatile boolean connected;

    /**
     * 构造 SSE 传输适配器.
     *
     * @param codec       JSON-RPC 编解码器
     * @param baseUrl     MCP 服务端基础 URL（如 "http://localhost:8127"）
     * @param sseEndpoint SSE 端点路径（默认 "/mcp/sse"）
     */
    public SseTransport(JsonRpcCodec codec, String baseUrl, String sseEndpoint) {
        this.codec = codec;
        this.baseUrl = baseUrl;
        this.sseEndpoint = sseEndpoint != null ? sseEndpoint : "/mcp/sse";
        this.messageEndpoint = "/mcp/message";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 构造 SSE 传输适配器（使用默认 SSE 端点）.
     */
    public SseTransport(JsonRpcCodec codec, String baseUrl) {
        this(codec, baseUrl, "/mcp/sse");
    }

    @Override
    public void connect() {
        if (connected) {
            return;
        }
        try {
            // SSE 连接握手：服务端返回的第一个事件包含 session 端点 URI
            String sseUrl = baseUrl + sseEndpoint;
            log.info("建立 SSE 连接: {}", sseUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sseUrl))
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            java.net.http.HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                throw new BusinessException("SSE 连接失败, HTTP 状态码: " + response.statusCode());
            }

            // 从首个 SSE 事件中提取 session 端点
            response.body().findFirst().ifPresentOrElse(
                    firstLine -> {
                        sessionMessageUri = parseEndpointEvent(firstLine);
                        log.info("SSE session 端点: {}", sessionMessageUri);
                    },
                    () -> {
                        throw new BusinessException("SSE 连接未收到 session 端点事件");
                    }
            );

            connected = true;
        } catch (IOException e) {
            throw new BusinessException("SSE 连接失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("SSE 连接被中断", e);
        }
    }

    @Override
    public JsonRpcResponse send(JsonRpcRequest request) {
        if (!connected) {
            throw new BusinessException("SSE 传输未连接, 请先调用 connect()");
        }

        try {
            String requestJson = codec.encode(request);
            log.debug("SSE POST 发送: {} → {}", sessionMessageUri, requestJson);

            // POST JSON-RPC 请求到 message 端点
            String responseJson = restClient.post()
                    .uri(sessionMessageUri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);

            if (responseJson == null) {
                throw new BusinessException("SSE 响应为空");
            }
            log.debug("SSE 接收: {}", responseJson);
            return codec.decodeResponse(responseJson);
        } catch (Exception e) {
            throw new BusinessException("SSE 通信失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        connected = false;
        sessionMessageUri = null;
        log.info("SSE 传输已关闭");
    }

    @Override
    public boolean isConnected() {
        return connected && sessionMessageUri != null;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从 SSE 事件文本中提取 endpoint URI.
     *
     * <p>MCP SSE 协议的 endpoint 事件格式为: {@code data: /mcp/message?sessionId=xxx}
     */
    private String parseEndpointEvent(String line) {
        String data = line.trim();
        if (data.startsWith("data:")) {
            data = data.substring(5).trim();
        }
        // 如果是相对路径, 补全为绝对 URI
        if (data.startsWith("/")) {
            return baseUrl + data;
        }
        return data;
    }
}
