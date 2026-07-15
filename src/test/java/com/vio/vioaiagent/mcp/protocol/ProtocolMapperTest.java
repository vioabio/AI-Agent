package com.vio.vioaiagent.mcp.protocol;

import com.vio.vioaiagent.mcp.protocol.model.ToolCallResult;
import com.vio.vioaiagent.mcp.protocol.model.ToolDefinition;
import com.vio.vioaiagent.mcp.transport.McpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProtocolMapper 测试.
 *
 * @author vio
 */
@DisplayName("协议映射器")
class ProtocolMapperTest {

    private static final String PROTOCOL_V1 = "mcp-2024-11-05";
    private static final String PROTOCOL_V2 = "mcp-2025-06-01";

    @Test
    @DisplayName("findFirst 应返回匹配的映射器")
    void shouldFindMatchingMapper() {
        ProtocolMapper mapper = new StubMapper(PROTOCOL_V1, 10);
        List<ProtocolMapper> mappers = List.of(mapper);

        Optional<ProtocolMapper> found = ProtocolMapper.findFirst(mappers, PROTOCOL_V1);

        assertTrue(found.isPresent());
        assertEquals(10, found.get().priority());
    }

    @Test
    @DisplayName("findFirst 应返回空 Optional 当无匹配时")
    void shouldReturnEmptyWhenNoMatch() {
        ProtocolMapper mapper = new StubMapper(PROTOCOL_V1, 10);
        List<ProtocolMapper> mappers = List.of(mapper);

        Optional<ProtocolMapper> found = ProtocolMapper.findFirst(mappers, "unknown-protocol");

        assertFalse(found.isPresent());
    }

    @Test
    @DisplayName("多个映射器时应返回优先级最高的")
    void shouldReturnHighestPriorityMapper() {
        ProtocolMapper low = new StubMapper(PROTOCOL_V1, 1);
        ProtocolMapper high = new StubMapper(PROTOCOL_V1, 100);
        List<ProtocolMapper> mappers = List.of(high, low); // 已排序

        Optional<ProtocolMapper> found = ProtocolMapper.findFirst(mappers, PROTOCOL_V1);

        assertTrue(found.isPresent());
        assertEquals(100, found.get().priority());
    }

    /**
     * 测试用的桩映射器.
     */
    private static class StubMapper implements ProtocolMapper {
        private final String protocolId;
        private final int priority;

        StubMapper(String protocolId, int priority) {
            this.protocolId = protocolId;
            this.priority = priority;
        }

        @Override
        public boolean supports(String protocolId) {
            return this.protocolId.equals(protocolId);
        }

        @Override
        public McpProtocolHandler createHandler(String protocolId, McpTransport transport) {
            return new StubHandler();
        }

        @Override
        public int priority() {
            return priority;
        }
    }

    private static class StubHandler implements McpProtocolHandler {
        @Override
        public com.vio.vioaiagent.mcp.protocol.model.InitializeResult initialize(
                com.vio.vioaiagent.mcp.protocol.model.InitializeRequest request) {
            return com.vio.vioaiagent.mcp.protocol.model.InitializeResult.success(
                    com.vio.vioaiagent.mcp.protocol.model.ServerCapabilities.toolsOnly(),
                    java.util.Map.of("name", "stub"));
        }

        @Override
        public com.vio.vioaiagent.mcp.protocol.model.ServerCapabilities getCapabilities() {
            return com.vio.vioaiagent.mcp.protocol.model.ServerCapabilities.toolsOnly();
        }

        @Override
        public com.vio.vioaiagent.mcp.protocol.model.ToolListResult listTools(String cursor) {
            return com.vio.vioaiagent.mcp.protocol.model.ToolListResult.of(List.of());
        }

        @Override
        public ToolCallResult callTool(String toolName, java.util.Map<String, Object> arguments) {
            return ToolCallResult.success("stub result");
        }

        @Override
        public void shutdown() {}
    }
}
