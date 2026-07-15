package com.vio.vioaiagent.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON-RPC 2.0 响应对象.
 *
 * <p>不可变数据载体. 按照 JSON-RPC 2.0 规范, 响应要么携带 {@code result}（成功）,
 * 要么携带 {@code error}（失败）, 两者互斥.
 *
 * <pre>{@code
 * // 成功响应
 * JsonRpcResponse.success(id, resultMap);
 *
 * // 错误响应
 * JsonRpcResponse.error(id, JsonRpcError.of(JsonRpcErrorCode.METHOD_NOT_FOUND));
 * }</pre>
 *
 * @param jsonrpc 协议版本, 固定 "2.0"
 * @param id      与请求对应的标识符
 * @param result  成功结果（与 error 互斥）
 * @param error   错误信息（与 result 互斥）
 * @author vio
 * @see <a href="https://www.jsonrpc.org/specification#response_object">JSON-RPC 2.0 Response Object</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(String jsonrpc, Object id, Object result, JsonRpcError error) {

    /**
     * 创建成功响应.
     *
     * @param id     请求标识符
     * @param result 成功结果
     * @return 成功响应对象
     */
    public static JsonRpcResponse success(Object id, Object result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }

    /**
     * 创建错误响应.
     *
     * @param id    请求标识符
     * @param error 错误信息
     * @return 错误响应对象
     */
    public static JsonRpcResponse error(Object id, JsonRpcError error) {
        return new JsonRpcResponse("2.0", id, null, error);
    }

    /**
     * 判断此响应是否为成功响应（无错误）.
     */
    @JsonIgnore
    public boolean isSuccess() {
        return error == null;
    }

    /**
     * 判断此响应是否为错误响应（有错误）.
     */
    @JsonIgnore
    public boolean isError() {
        return error != null;
    }
}
