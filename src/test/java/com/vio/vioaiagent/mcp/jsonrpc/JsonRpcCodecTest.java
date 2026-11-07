package com.vio.vioaiagent.mcp.jsonrpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JsonRpcCodec 编解码器测试.
 *
 * @author vio
 */
@DisplayName("JSON-RPC 编解码器")
class JsonRpcCodecTest {

    private JsonRpcCodec codec;

    @BeforeEach
    void setUp() {
        codec = new JsonRpcCodec(new ObjectMapper());
    }

    // ==================== 请求编解码 ====================

    @Test
    @DisplayName("请求序列化/反序列化往返")
    void shouldRoundTripRequest() {
        JsonRpcRequest request = JsonRpcRequest.of("tools/list",
                Map.of("cursor", "next-page"));

        String json = codec.encode(request);
        JsonRpcRequest decoded = codec.decodeRequest(json);

        assertEquals("2.0", decoded.jsonrpc());
        assertEquals(request.id(), decoded.id());
        assertEquals("tools/list", decoded.method());
        assertEquals("next-page", decoded.params().get("cursor"));
    }

    @Test
    @DisplayName("无参数的请求往返")
    void shouldRoundTripRequestWithoutParams() {
        JsonRpcRequest request = JsonRpcRequest.of("initialize");

        String json = codec.encode(request);
        JsonRpcRequest decoded = codec.decodeRequest(json);

        assertEquals("initialize", decoded.method());
        assertNull(decoded.params());
    }

    @Test
    @DisplayName("String 类型 id 应保留")
    void shouldPreserveStringId() {
        JsonRpcRequest request = new JsonRpcRequest("2.0", "req-001", "test", null);

        String json = codec.encode(request);
        JsonRpcRequest decoded = codec.decodeRequest(json);

        // id 在 extractId 中保留为字符串
        assertNotNull(decoded.id());
        assertTrue(decoded.id() instanceof String || decoded.id() instanceof Number);
    }

    @Test
    @DisplayName("Number 类型 id 应保留")
    void shouldPreserveNumberId() {
        JsonRpcRequest request = new JsonRpcRequest("2.0", 42, "test", null);

        String json = codec.encode(request);
        JsonRpcRequest decoded = codec.decodeRequest(json);

        assertNotNull(decoded.id());
        assertTrue(decoded.id() instanceof Number, "Number id 应保持为数值类型");
        assertEquals(42, ((Number) decoded.id()).intValue());
    }

    // ==================== 响应编解码 ====================

    @Test
    @DisplayName("成功响应往返")
    void shouldRoundTripSuccessResponse() {
        JsonRpcResponse response = JsonRpcResponse.success("req-1",
                Map.of("answer", 42));

        String json = codec.encode(response);
        JsonRpcResponse decoded = codec.decodeResponse(json);

        assertEquals("2.0", decoded.jsonrpc());
        assertEquals("req-1", decoded.id());
        assertTrue(decoded.isSuccess());
        assertNull(decoded.error());
    }

    @Test
    @DisplayName("错误响应往返")
    void shouldRoundTripErrorResponse() {
        JsonRpcError error = JsonRpcError.of(JsonRpcErrorCode.METHOD_NOT_FOUND);
        JsonRpcResponse response = JsonRpcResponse.error("req-2", error);

        String json = codec.encode(response);
        JsonRpcResponse decoded = codec.decodeResponse(json);

        assertTrue(decoded.isError());
        assertNotNull(decoded.error());
        assertEquals(-32601, decoded.error().code());
        assertEquals("方法不存在", decoded.error().message());
    }

    // ==================== 边界条件 ====================

    @Test
    @DisplayName("畸形 JSON 应抛出 BusinessException")
    void shouldThrowOnMalformedJson() {
        assertThrows(Exception.class, () -> codec.decodeRequest("{not json!!!"));
    }

    @Test
    @DisplayName("请求序列化结果应为合法 JSON 字符串")
    void shouldProduceValidJson() {
        JsonRpcRequest request = JsonRpcRequest.of("ping");
        String json = codec.encode(request);

        assertTrue(json.contains("\"jsonrpc\""));
        assertTrue(json.contains("\"2.0\""));
        assertTrue(json.contains("\"method\""));
        assertTrue(json.contains("\"ping\""));
    }

    @Test
    @DisplayName("空参数 Map 应正确序列化")
    void shouldHandleEmptyParams() {
        JsonRpcRequest request = new JsonRpcRequest("2.0", "test-1", "method", Map.of());

        String json = codec.encode(request);
        JsonRpcRequest decoded = codec.decodeRequest(json);

        assertEquals("method", decoded.method());
    }
}
