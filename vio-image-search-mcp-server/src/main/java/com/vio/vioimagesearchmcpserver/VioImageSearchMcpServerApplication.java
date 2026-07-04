package com.vio.vioimagesearchmcpserver;

import com.vio.vioimagesearchmcpserver.tools.ImageSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * VIO 图片搜索 MCP 服务端启动类
 * <p>
 * 支持两种传输模式：
 * <ul>
 *   <li><b>SSE 模式</b>（默认）：作为独立 Web 服务运行在端口 8127，可供多客户端远程调用</li>
 *   <li><b>STDIO 模式</b>：作为客户端子进程运行，通过标准输入/输出流通信</li>
 * </ul>
 * <p>
 * 启动方式：
 * <pre>
 *   SSE 模式:  java -jar vio-image-search-mcp-server-0.0.1-SNAPSHOT.jar
 *   STDIO 模式: java -jar vio-image-search-mcp-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=stdio
 * </pre>
 *
 * @author vio
 */
@SpringBootApplication
public class VioImageSearchMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(VioImageSearchMcpServerApplication.class, args);
    }

    /**
     * 注册图片搜索工具为 ToolCallbackProvider
     * <p>
     * Spring AI MCP Server 会自动发现此 Bean 并将其暴露为 MCP 工具，
     * 供 MCP 客户端发现和调用。
     */
    @Bean
    public ToolCallbackProvider imageSearchTools(ImageSearchTool imageSearchTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(imageSearchTool)
                .build();
    }
}