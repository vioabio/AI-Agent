package com.vio.vioaiagent.mcp;

import io.modelcontextprotocol.client.transport.ServerParameters;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端 STDIO（标准输入/输出）传输模式单元测试
 * <p>
 * 测试 {@link ServerParameters} 构建 API 和 STDIO 传输配置。
 * <p>
 * 完整的 STDIO 端到端传输测试（子进程通信、工具发现和调用）需要先构建
 * MCP Server JAR 包，参考 {@code McpClientStdioIntegrationTest} 说明。
 *
 * @author vio
 */
class McpClientStdioTest {

    @Test
    void testStdioServerParametersValidation() {
        // 验证：ServerParameters Builder API 正常工作
        ServerParameters params = ServerParameters.builder("java")
                .args(List.of("-version"))
                .env(Map.of("TEST_KEY", "test_value"))
                .build();

        Assertions.assertEquals("java", params.getCommand(), "命令应为 java");
        Assertions.assertNotNull(params.getArgs(), "参数列表不应为空");
        Assertions.assertEquals(1, params.getArgs().size(), "应有 1 个参数");
        Assertions.assertEquals("-version", params.getArgs().get(0), "参数应为 -version");
        Assertions.assertEquals("test_value", params.getEnv().get("TEST_KEY"),
                "环境变量应正确设置");
    }

    @Test
    void testStdioServerParametersWithMultipleArgs() {
        // 验证：多个参数时能够正确保存
        // 注意：env 会自动合并系统环境变量（用于进程继承），因此只验证提供的键值存在
        ServerParameters params = ServerParameters.builder("python")
                .args(List.of("-m", "mcp_server", "--port", "8080"))
                .addEnvVar("MY_CUSTOM_KEY", "my_value")
                .build();

        Assertions.assertEquals("python", params.getCommand());
        Assertions.assertEquals(4, params.getArgs().size());
        Assertions.assertTrue(params.getEnv().size() > 0, "环境变量应包含继承的系统变量");
        Assertions.assertEquals("my_value", params.getEnv().get("MY_CUSTOM_KEY"),
                "自定义环境变量应存在");
    }

    @Test
    void testStdioServerParametersEmptyArgs() {
        // 验证：空参数列表也能正常工作
        ServerParameters params = ServerParameters.builder("node")
                .args(List.of())
                .build();

        Assertions.assertEquals("node", params.getCommand());
        Assertions.assertTrue(params.getArgs().isEmpty(), "参数列表应为空");
    }

    @Test
    @Disabled("需要先构建 vio-image-search-mcp-server-*.jar 到 target/ 目录")
    void testStdioConnectionAndToolDiscovery() {
        // 完整 STDIO 端到端测试说明：
        // 1. 先执行: mvn package -pl vio-image-search-mcp-server -DskipTests
        // 2. 将生成的 JAR 复制到 target/ 目录
        // 3. 修改 ServerParameters 指向该 JAR
        // 4. 运行本测试验证 STDIO 连接、工具发现和调用
    }

    @Test
    @Disabled("需要先构建 vio-image-search-mcp-server-*.jar 到 target/ 目录")
    void testStdioToolInvocation() {
        // 完整 STDIO 端到端测试说明：
        // 参考 testStdioConnectionAndToolDiscovery 的注释
    }
}
