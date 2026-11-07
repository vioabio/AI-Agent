package com.vio.vioaiagent.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON-RPC 2.0 错误对象.
 *
 * <p>不可变数据载体, 用于表示 JSON-RPC 响应中的错误信息.
 * 使用 Java 21 Record 代替 Lombok @Data, 保证不可变性和 Jackson 原生兼容.
 *
 * @param code    错误码（JSON-RPC 2.0 规范定义的整数）
 * @param message 错误描述（人可读的简短信息）
 * @param data    附加错误数据（可选, 用于携带结构化错误详情）
 * @author vio
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(int code, String message, Object data) {

    /**
     * 创建不带附加数据的错误对象.
     */
    public JsonRpcError(int code, String message) {
        this(code, message, null);
    }

    /**
     * 根据预定义的错误码枚举创建错误对象.
     *
     * @param errorCode 错误码枚举
     * @param data      附加数据（可为 null）
     */
    public static JsonRpcError of(JsonRpcErrorCode errorCode, Object data) {
        return new JsonRpcError(errorCode.getCode(), errorCode.getMessage(), data);
    }

    /**
     * 根据预定义的错误码枚举创建错误对象（无附加数据）.
     */
    public static JsonRpcError of(JsonRpcErrorCode errorCode) {
        return of(errorCode, null);
    }
}
