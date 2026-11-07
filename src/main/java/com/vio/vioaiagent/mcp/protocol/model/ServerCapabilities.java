package com.vio.vioaiagent.mcp.protocol.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP 服务端能力声明.
 *
 * <p>在 initialize 握手阶段, 服务端通过此对象告知客户端它支持哪些功能.
 * 客户端的后续请求受此能力协商结果的约束.
 *
 * @param toolsSupported    是否支持工具调用
 * @param resourcesSupported 是否支持资源访问
 * @param promptsSupported  是否支持提示模板
 * @author vio
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerCapabilities(
        boolean toolsSupported,
        boolean resourcesSupported,
        boolean promptsSupported) {

    /**
     * 创建默认能力（仅支持工具调用）.
     */
    public static ServerCapabilities toolsOnly() {
        return new ServerCapabilities(true, false, false);
    }

    /**
     * 创建全能力声明.
     */
    public static ServerCapabilities all() {
        return new ServerCapabilities(true, true, true);
    }
}
