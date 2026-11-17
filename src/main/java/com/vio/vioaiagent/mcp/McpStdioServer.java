package com.vio.vioaiagent.mcp;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.*;
import java.util.concurrent.CountDownLatch;

public final class McpStdioServer {
    private McpStdioServer() { }
    public static McpAsyncServer start(ReadOnlyMcpAdapter adapter, InputStream input, OutputStream output, CountDownLatch end) {
        var wire = new McpWireControl(input,output,end);
        var provider = new StdioServerTransportProvider(wire.json, wire.input, wire.output);
        var specs = adapter.catalog().tools().stream().map(tool -> new McpServerFeatures.AsyncToolSpecification(tool,
                (exchange, args) -> wire.invoke(adapter,tool.name(),args))).toList();
        return McpServer.async(provider).serverInfo("vio-pokemon-public", "1.1-dev")
                .capabilities(new McpSchema.ServerCapabilities(null, null, null, null, null, new McpSchema.ServerCapabilities.ToolCapabilities(false)))
                .tools(specs).build();
    }
}
