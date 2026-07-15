package com.vio.vioaiagent.security;

import java.time.Instant;
import java.util.Set;

/**
 * 令牌-协议绑定模型.
 *
 * <p>每个 API Key 绑定到一个或多个协议（MCP Server）,
 * 并限制可调用的工具范围和频率. 防止令牌跨协议越权调用.
 *
 * @param tokenId      令牌标识（即 API Key 本身）
 * @param protocolId   绑定的协议 ID（如 "amap-maps", "image-search"）
 * @param allowedTools 允许调用的工具名称集合
 * @param maxRequestsPerMinute 每分钟最大请求数（0 表示不限制）
 * @param expiresAt    过期时间（null 表示永不过期）
 * @author vio
 */
public record TokenBinding(
        String tokenId,
        String protocolId,
        Set<String> allowedTools,
        int maxRequestsPerMinute,
        Instant expiresAt) {

    /**
     * 创建无限制的令牌绑定（允许所有工具, 不限制频率, 永不过期）.
     */
    public static TokenBinding unlimited(String tokenId, String protocolId) {
        return new TokenBinding(tokenId, protocolId, Set.of(), 0, null);
    }

    /**
     * 检查令牌是否已过期.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * 检查是否允许调用指定工具.
     *
     * @param toolName 工具名称
     * @return true 表示允许调用（allowedTools 为空表示允许所有）
     */
    public boolean allowsTool(String toolName) {
        return allowedTools.isEmpty() || allowedTools.contains(toolName);
    }
}
