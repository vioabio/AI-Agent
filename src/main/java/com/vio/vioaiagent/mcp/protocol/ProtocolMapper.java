package com.vio.vioaiagent.mcp.protocol;

import com.vio.vioaiagent.mcp.transport.McpTransport;

import java.util.Optional;

/**
 * 协议映射器接口 — 根据 protocolId 创建对应的协议处理器.
 *
 * <p>通过此接口, 系统可以同时支持多种 MCP 协议版本或变体.
 * 每个实现声明自己能处理的 protocolId, 引擎按优先级选择合适的映射器.
 *
 * <p>扩展方式: 实现此接口并注册为 Spring Bean, 无需修改现有代码.
 * 多个实现通过 Spring 的 {@code List<ProtocolMapper>} 注入,
 * 按 {@link #priority()} 降序排列, 首个匹配的映射器被选中.
 *
 * @author vio
 */
public interface ProtocolMapper {

    /**
     * 判断此映射器是否支持指定的协议 ID.
     *
     * @param protocolId 协议标识符（如 "mcp-2024-11-05"）
     * @return true 表示此映射器能处理该协议
     */
    boolean supports(String protocolId);

    /**
     * 为指定协议创建协议处理器.
     *
     * @param protocolId 协议标识符
     * @param transport  已建立连接的传输层
     * @return 协议处理器实例
     */
    McpProtocolHandler createHandler(String protocolId, McpTransport transport);

    /**
     * 映射器优先级（数值越大越优先）.
     * 默认为 0, 自定义实现可覆盖以调整顺序.
     */
    default int priority() {
        return 0;
    }

    /**
     * 从一组映射器中查找第一个支持指定协议的映射器.
     *
     * @param mappers     已按优先级排序的映射器列表
     * @param protocolId 协议标识符
     * @return 匹配的映射器（如果有）
     */
    static Optional<ProtocolMapper> findFirst(
            java.util.List<ProtocolMapper> mappers, String protocolId) {
        return mappers.stream()
                .filter(m -> m.supports(protocolId))
                .findFirst();
    }
}
