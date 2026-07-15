package com.vio.vioaiagent.mcp.bridge;

import com.vio.vioaiagent.mcp.gateway.McpGateway;
import com.vio.vioaiagent.mcp.protocol.model.ToolCallResult;
import com.vio.vioaiagent.mcp.protocol.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自定义 MCP → Spring AI 工具适配器.
 *
 * <p>将自定义 MCP 协议栈发现的工具包装为 Spring AI 的 {@link ToolCallback},
 * 使其可以无缝接入现有的 {@code ToolCallAgent.getMergedTools()} 流程.
 *
 * <p>这是迁移过渡期的关键组件 — Agent 无需任何代码修改即可使用
 * 自定义 MCP 栈发现的工具.
 *
 * <pre>{@code
 * // 使用方式：将自定义网关发现的工具注入 Agent
 * McpGateway gateway = ...;
 * gateway.connect("image-search", transport);
 * ToolCallback[] mcpTools = CustomToolCallbackAdapter.wrap("image-search", gateway);
 * // mcpTools 可直接传入 ToolCallAgent.setToolCallbackProvider()
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class CustomToolCallbackAdapter {

    /**
     * 将 McpGateway 中指定连接的所有工具包装为 Spring AI ToolCallback 数组.
     *
     * @param connectionId 网关连接标识
     * @param gateway      MCP 网关
     * @return ToolCallback 数组（可传入 ToolCallAgent）
     */
    public static ToolCallback[] wrap(String connectionId, McpGateway gateway) {
        List<ToolDefinition> tools = gateway.discoverTools(connectionId);
        ToolCallback[] callbacks = new ToolCallback[tools.size()];

        for (int i = 0; i < tools.size(); i++) {
            final ToolDefinition def = tools.get(i);
            final String connId = connectionId;
            final McpGateway gw = gateway;

            callbacks[i] = new ToolCallback() {
                @Override
                public String call(String toolInput) {
                    try {
                        // 将 JSON 字符串解析为 Map
                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = toolInput != null && !toolInput.isEmpty()
                                ? new com.fasterxml.jackson.databind.ObjectMapper()
                                        .readValue(toolInput, Map.class)
                                : Map.of();
                        ToolCallResult result = gw.invokeTool(connId, def.name(), args);
                        if (result.isError()) {
                            return "工具调用失败: " +
                                    (result.content().isEmpty() ? "unknown"
                                            : result.content().get(0).text());
                        }
                        return result.content().isEmpty() ? "ok"
                                : result.content().get(0).text();
                    } catch (Exception e) {
                        log.error("自定义 MCP 工具调用失败: {} - {}", def.name(), e.getMessage());
                        return "工具调用异常: " + e.getMessage();
                    }
                }

                @Override
                public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    // 将自定义的 Map schema 序列化为 JSON 字符串
                    String schemaJson = null;
                    if (def.inputSchema() != null) {
                        try {
                            schemaJson = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(def.inputSchema());
                        } catch (Exception e) {
                            log.warn("无法序列化工具 {} 的 inputSchema: {}", def.name(), e.getMessage());
                        }
                    }
                    return org.springframework.ai.tool.definition.ToolDefinition.builder()
                            .name(def.name())
                            .description(def.description())
                            .inputSchema(schemaJson != null ? schemaJson : "{}")
                            .build();
                }
            };
        }

        log.info("已将 {} 个自定义 MCP 工具适配为 Spring AI ToolCallback", tools.size());
        return callbacks;
    }
}
