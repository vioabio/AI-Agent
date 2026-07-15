package com.vio.vioaiagent.mcp.gateway;

import com.vio.vioaiagent.mcp.protocol.McpProtocolHandler;
import com.vio.vioaiagent.mcp.protocol.ProtocolMapper;
import com.vio.vioaiagent.mcp.transport.McpTransport;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 会话管理器 — 管理多个 MCP Server 连接的生命周期.
 *
 * <p>负责会话的创建、检索、空闲淘汰和优雅关闭.
 * 使用 ConcurrentHashMap 保证并发安全.
 *
 * @author vio
 */
@Slf4j
public class McpSessionManager {

    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final List<ProtocolMapper> mappers;
    private final String defaultProtocolId;

    /**
     * 构造会话管理器.
     *
     * @param mappers            已注册的协议映射器列表（按优先级排序）
     * @param defaultProtocolId  默认协议 ID
     */
    public McpSessionManager(List<ProtocolMapper> mappers, String defaultProtocolId) {
        this.mappers = List.copyOf(mappers);
        this.defaultProtocolId = defaultProtocolId;
    }

    /**
     * 创建新会话或返回已有会话.
     *
     * @param connectionId 连接标识（如 "amap-maps", "image-search"）
     * @param transport    已配置的传输层
     * @param protocolId   协议 ID, 为 null 时使用默认协议
     * @return 已初始化的会话
     */
    public McpSession getOrCreateSession(String connectionId, McpTransport transport, String protocolId) {
        return sessions.computeIfAbsent(connectionId, id -> {
            String protoId = protocolId != null ? protocolId : defaultProtocolId;
            McpProtocolHandler handler = createHandler(protoId, transport);
            McpSession session = new McpSession(connectionId, transport, handler);
            session.initialize();
            return session;
        });
    }

    /**
     * 创建新会话（使用默认协议）.
     */
    public McpSession getOrCreateSession(String connectionId, McpTransport transport) {
        return getOrCreateSession(connectionId, transport, defaultProtocolId);
    }

    /**
     * 获取已有会话.
     *
     * @param connectionId 连接标识
     * @return 会话实例, 不存在返回 null
     */
    public McpSession getSession(String connectionId) {
        return sessions.get(connectionId);
    }

    /**
     * 关闭指定会话.
     */
    public void closeSession(String connectionId) {
        McpSession session = sessions.remove(connectionId);
        if (session != null) {
            session.close();
        }
    }

    /**
     * 关闭所有会话.
     */
    public void closeAll() {
        sessions.forEach((id, session) -> {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("关闭会话失败: {}", id, e);
            }
        });
        sessions.clear();
        log.info("所有 MCP 会话已关闭");
    }

    /**
     * 淘汰空闲过期的会话.
     *
     * @param maxIdleTime 最大空闲时间
     */
    public void evictIdleSessions(Duration maxIdleTime) {
        Instant cutoff = Instant.now().minus(maxIdleTime);
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().getLastActivityAt().isBefore(cutoff)) {
                log.info("淘汰空闲会话: {}", entry.getKey());
                try { entry.getValue().close(); } catch (Exception ignored) {}
                return true;
            }
            return false;
        });
    }

    /** 当前活跃会话数 */
    public int activeSessionCount() {
        return sessions.size();
    }

    // ==================== 私有方法 ====================

    private McpProtocolHandler createHandler(String protocolId, McpTransport transport) {
        return ProtocolMapper.findFirst(mappers, protocolId)
                .map(mapper -> mapper.createHandler(protocolId, transport))
                .orElseThrow(() -> new IllegalArgumentException(
                        "未找到支持协议 [" + protocolId + "] 的映射器"));
    }
}
