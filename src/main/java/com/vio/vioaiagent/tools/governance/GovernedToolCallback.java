package com.vio.vioaiagent.tools.governance;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

/** 将任意 Spring AI ToolCallback 包装到统一治理网关。 */
public final class GovernedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolExecutionGateway gateway;

    public GovernedToolCallback(ToolCallback delegate, ToolExecutionGateway gateway) {
        this.delegate = delegate;
        this.gateway = gateway;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return gateway.execute(delegate, toolInput, ToolExecutionContextHolder.current()).toModelText();
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return call(toolInput);
    }
}
