package com.vio.vioaiagent.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.UUID;

/**
 * JSON-RPC 2.0 请求对象.
 *
 * <p>不可变数据载体, 严格遵循 JSON-RPC 2.0 规范：
 * <ul>
 *   <li>{@code jsonrpc} 字段必须是字符串 "2.0"</li>
 *   <li>{@code method} 字段必须是 non-null、non-blank 的方法名字符串</li>
 *   <li>{@code id} 可以是 String 或 Number（通知模式时为 null）</li>
 *   <li>{@code params} 可选, 为命名参数 Map 或位置参数数组</li>
 * </ul>
 *
 * @param jsonrpc 协议版本, 固定 "2.0"
 * @param id      请求标识符（String 或 Number）
 * @param method  方法名（如 "initialize", "tools/list", "tools/call"）
 * @param params  方法参数（命名参数 Map, 可为 null）
 * @author vio
 * @see <a href="https://www.jsonrpc.org/specification#request_object">JSON-RPC 2.0 Request Object</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {

    /**
     * 紧凑构造器：校验请求的合法性.
     */
    public JsonRpcRequest {
        if (!"2.0".equals(jsonrpc)) {
            throw new IllegalArgumentException("jsonrpc 必须为 '2.0', 实际值: " + jsonrpc);
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method 不能为空");
        }
    }

    /**
     * 创建携带命名参数的 JSON-RPC 2.0 请求（自动生成 UUID id）.
     *
     * @param method 方法名
     * @param params 命名参数 Map
     * @return 构造好的请求对象
     */
    public static JsonRpcRequest of(String method, Map<String, Object> params) {
        return new JsonRpcRequest("2.0", UUID.randomUUID().toString(), method, params);
    }

    /**
     * 创建无参数的 JSON-RPC 2.0 请求（自动生成 UUID id）.
     *
     * @param method 方法名
     * @return 构造好的请求对象
     */
    public static JsonRpcRequest of(String method) {
        return of(method, null);
    }
}
