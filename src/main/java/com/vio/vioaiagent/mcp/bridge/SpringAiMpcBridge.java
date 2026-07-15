package com.vio.vioaiagent.mcp.bridge;

import com.vio.vioaiagent.mcp.protocol.model.ToolCallResult;
import com.vio.vioaiagent.mcp.protocol.model.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring AI ↔ 自定义 MCP 协议栈桥接器（Spring AI 方向）.
 *
 * <p>将 Spring AI 的 {@link ToolCallbackProvider} 和 {@link ToolCallback}
 * 转换为自定义 MCP 栈的 {@link ToolDefinition} 和 {@link ToolCallResult}.
 * 用于将现有的 Spring AI MCP 工具纳入自定义协议栈管理.
 *
 * <p>使用场景：
 * <ul>
 *   <li>将 Spring AI 自动发现的 MCP 工具转换为自定义 ToolDefinition 列表</li>
 *   <li>通过 Spring AI 的 ToolCallback 执行工具, 结果转为 ToolCallResult</li>
 * </ul>
 *
 * @author vio
 */
@Slf4j
public class SpringAiMpcBridge {

    /**
     * 将 Spring AI ToolCallbackProvider 中的工具转换为自定义 ToolDefinition 列表.
     *
     * @param provider Spring AI 工具提供者
     * @return 自定义工具定义列表
     */
    public List<ToolDefinition> toToolDefinitions(ToolCallbackProvider provider) {
        if (provider == null) {
            return Collections.emptyList();
        }
        ToolCallback[] callbacks = provider.getToolCallbacks();
        if (callbacks == null || callbacks.length == 0) {
            return Collections.emptyList();
        }

        List<ToolDefinition> definitions = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            org.springframework.ai.tool.definition.ToolDefinition springDef =
                    callback.getToolDefinition();
            // Spring AI 的 inputSchema 是 JSON 字符串，解析为 Map
            Map<String, Object> schema = null;
            if (springDef.inputSchema() != null && !springDef.inputSchema().isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(springDef.inputSchema(), Map.class);
                    schema = parsed;
                } catch (Exception e) {
                    log.warn("无法解析工具 {} 的 inputSchema: {}", springDef.name(), e.getMessage());
                }
            }
            ToolDefinition customDef = new ToolDefinition(
                    springDef.name(),
                    springDef.description(),
                    schema
            );
            definitions.add(customDef);
        }
        log.info("从 Spring AI 转换了 {} 个工具定义", definitions.size());
        return definitions;
    }

    /**
     * 通过 Spring AI ToolCallback 执行工具, 返回自定义 ToolCallResult.
     *
     * @param callback  Spring AI 工具回调
     * @param arguments 工具参数（将转为 JSON 字符串）
     * @return 自定义工具调用结果
     */
    public ToolCallResult executeViaSpringAi(ToolCallback callback,
                                               Map<String, Object> arguments) {
        try {
            // 将参数 Map 序列化为 JSON 字符串（Spring AI 的 call 接口接受 String）
            String toolInput = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(arguments);
            String result = callback.call(toolInput);
            return ToolCallResult.success(result);
        } catch (Exception e) {
            log.error("Spring AI 工具调用失败: {}", e.getMessage(), e);
            return ToolCallResult.error("Spring AI 工具调用失败: " + e.getMessage());
        }
    }
}
