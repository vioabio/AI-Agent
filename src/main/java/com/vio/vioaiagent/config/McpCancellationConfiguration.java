package com.vio.vioaiagent.config;

import com.vio.vioaiagent.mcp.client.*;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import java.util.*;

/** Decorates existing MCP beans without creating or enabling an external connection. */
@Configuration
public class McpCancellationConfiguration {
    @Bean public static BeanPostProcessor mcpCancellationBridge() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean,String name) {
                if (bean instanceof List<?> transports && !transports.isEmpty()
                        && transports.stream().allMatch(NamedClientMcpTransport.class::isInstance)) {
                    return transports.stream().map(NamedClientMcpTransport.class::cast)
                            .map(item -> new NamedClientMcpTransport(item.name(), item.transport() instanceof McpCancellationTransport
                                    ? item.transport() : new McpCancellationTransport(item.transport()))).toList();
                }
                return bean;
            }
        };
    }
}
