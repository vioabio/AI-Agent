package com.vio.vioaiagent.mcp.client;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import java.util.Objects;

/** Retains the provider's schema, metadata and result while binding cancellation to its wire ID. */
public final class CancellableMcpToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    public CancellableMcpToolCallback(ToolCallback delegate) { this.delegate=Objects.requireNonNull(delegate); }
    public static ToolCallback wrap(ToolCallback callback) {
        return callback instanceof SyncMcpToolCallback || callback instanceof AsyncMcpToolCallback
                ? new CancellableMcpToolCallback(callback) : callback;
    }
    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
    @Override public String call(String input) { return McpCancellationTransport.call(() -> delegate.call(input)); }
    @Override public String call(String input,ToolContext context) { return McpCancellationTransport.call(() -> delegate.call(input,context)); }
}
