package com.vio.vioaiagent.mcp.jsonrpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.exception.BusinessException;

/**
 * JSON-RPC 2.0 编解码器.
 *
 * <p>基于 Jackson ObjectMapper 实现 JSON-RPC 请求和响应的序列化/反序列化.
 * 这是整个 MCP 协议栈的唯一一个与 JSON 格式打交道的地方，
 * 所有传输层适配器都通过它来编解码消息.
 *
 * <p>设计要点：
 * <ul>
 *   <li>ObjectMapper 通过构造函数注入, 便于测试和扩展</li>
 *   <li>id 字段使用 JsonNode 遍历处理多态类型（String / Number）</li>
 *   <li>解析失败统一抛出 BusinessException, 遵循项目异常处理模式</li>
 * </ul>
 *
 * @author vio
 */
public class JsonRpcCodec {

    private final ObjectMapper objectMapper;

    /**
     * 构造编解码器.
     *
     * @param objectMapper Jackson ObjectMapper 实例
     */
    public JsonRpcCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 JSON-RPC 请求序列化为 JSON 字符串.
     *
     * @param request 请求对象
     * @return JSON 字符串
     * @throws BusinessException 序列化失败时抛出
     */
    public String encode(JsonRpcRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON-RPC 请求序列化失败", e);
        }
    }

    /**
     * 将 JSON-RPC 响应序列化为 JSON 字符串.
     *
     * @param response 响应对象
     * @return JSON 字符串
     * @throws BusinessException 序列化失败时抛出
     */
    public String encode(JsonRpcResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON-RPC 响应序列化失败", e);
        }
    }

    /**
     * 从 JSON 字符串反序列化为 JSON-RPC 请求.
     *
     * <p>id 字段使用 JsonNode 遍历处理, 兼容 String 和 Number 两种类型.
     *
     * @param json JSON 字符串
     * @return 解析后的请求对象
     * @throws BusinessException 解析失败时抛出
     */
    public JsonRpcRequest decodeRequest(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String jsonrpc = getTextOrDefault(root, "jsonrpc", "2.0");
            Object id = extractId(root);
            String method = getRequiredText(root, "method");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> params = root.has("params") && !root.get("params").isNull()
                    ? objectMapper.convertValue(root.get("params"), java.util.Map.class)
                    : null;
            return new JsonRpcRequest(jsonrpc, id, method, params);
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON-RPC 请求解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 JSON 字符串反序列化为 JSON-RPC 响应.
     *
     * @param json JSON 字符串
     * @return 解析后的响应对象
     * @throws BusinessException 解析失败时抛出
     */
    public JsonRpcResponse decodeResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            String jsonrpc = getTextOrDefault(root, "jsonrpc", "2.0");
            Object id = extractId(root);

            Object result = null;
            JsonRpcError error = null;

            if (root.has("error") && !root.get("error").isNull()) {
                error = objectMapper.treeToValue(root.get("error"), JsonRpcError.class);
            } else if (root.has("result")) {
                result = objectMapper.treeToValue(root.get("result"), Object.class);
            }

            return new JsonRpcResponse(jsonrpc, id, result, error);
        } catch (JsonProcessingException e) {
            throw new BusinessException("JSON-RPC 响应解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 ObjectMapper 实例（供高级场景使用）.
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从 JsonNode 中提取 id 字段（兼容 String 和 Number 类型）.
     */
    private Object extractId(JsonNode root) {
        if (!root.has("id") || root.get("id").isNull()) {
            return null;
        }
        JsonNode idNode = root.get("id");
        if (idNode.isTextual()) {
            return idNode.asText();
        } else if (idNode.isNumber()) {
            return idNode.numberValue();
        }
        return idNode.asText();
    }

    /**
     * 获取必填的文本字段.
     */
    private String getRequiredText(JsonNode root, String fieldName) {
        if (!root.has(fieldName) || root.get(fieldName).isNull()) {
            throw new BusinessException("JSON-RPC 请求缺少必填字段: " + fieldName);
        }
        return root.get(fieldName).asText();
    }

    /**
     * 获取可选的文本字段（不存在时返回默认值）.
     */
    private String getTextOrDefault(JsonNode root, String fieldName, String defaultValue) {
        if (!root.has(fieldName) || root.get(fieldName).isNull()) {
            return defaultValue;
        }
        return root.get(fieldName).asText();
    }
}
