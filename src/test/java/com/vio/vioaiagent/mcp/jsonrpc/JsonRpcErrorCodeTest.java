package com.vio.vioaiagent.mcp.jsonrpc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * JsonRpcErrorCode 枚举测试.
 *
 * @author vio
 */
@DisplayName("JSON-RPC 错误码枚举")
class JsonRpcErrorCodeTest {

    @Test
    @DisplayName("所有 13 个错误码应能通过 of() 查找")
    void shouldFindAllThirteenCodes() {
        for (JsonRpcErrorCode errorCode : JsonRpcErrorCode.values()) {
            JsonRpcErrorCode found = JsonRpcErrorCode.of(errorCode.getCode());
            assertNotNull(found, "错误码 " + errorCode.getCode() + " 应能找到");
            assertEquals(errorCode, found);
        }
    }

    @Test
    @DisplayName("不存在的错误码应返回 null")
    void shouldReturnNullForUnknownCode() {
        assertNull(JsonRpcErrorCode.of(-99999));
        assertNull(JsonRpcErrorCode.of(0));
        assertNull(JsonRpcErrorCode.of(100));
    }

    @Test
    @DisplayName("JSON-RPC 标准错误码范围验证")
    void shouldHaveCorrectStandardErrorCodes() {
        assertEquals(-32700, JsonRpcErrorCode.PARSE_ERROR.getCode());
        assertEquals(-32600, JsonRpcErrorCode.INVALID_REQUEST.getCode());
        assertEquals(-32601, JsonRpcErrorCode.METHOD_NOT_FOUND.getCode());
        assertEquals(-32602, JsonRpcErrorCode.INVALID_PARAMS.getCode());
        assertEquals(-32603, JsonRpcErrorCode.INTERNAL_ERROR.getCode());
    }

    @Test
    @DisplayName("MCP 自定义错误码范围验证")
    void shouldHaveCorrectMcpErrorCodes() {
        assertEquals(-32002, JsonRpcErrorCode.SERVER_NOT_INITIALIZED.getCode());
        assertEquals(-32003, JsonRpcErrorCode.UNKNOWN_CAPABILITY.getCode());
        assertEquals(-32004, JsonRpcErrorCode.TOOL_NOT_FOUND.getCode());
        assertEquals(-32005, JsonRpcErrorCode.TOOL_EXECUTION_ERROR.getCode());
    }

    @Test
    @DisplayName("业务扩展错误码范围验证")
    void shouldHaveCorrectBusinessErrorCodes() {
        assertEquals(-32010, JsonRpcErrorCode.AUTH_FAILED.getCode());
        assertEquals(-32011, JsonRpcErrorCode.RATE_LIMITED.getCode());
        assertEquals(-32012, JsonRpcErrorCode.SESSION_EXPIRED.getCode());
        assertEquals(-32013, JsonRpcErrorCode.PERMISSION_DENIED.getCode());
    }

    @Test
    @DisplayName("每个错误码的 message 不应为空")
    void shouldHaveNonEmptyMessage() {
        for (JsonRpcErrorCode errorCode : JsonRpcErrorCode.values()) {
            assertNotNull(errorCode.getMessage(), errorCode.name() + " 的 message 不应为空");
        }
    }
}
