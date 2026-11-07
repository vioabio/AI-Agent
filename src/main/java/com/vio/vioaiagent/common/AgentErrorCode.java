package com.vio.vioaiagent.common;

import lombok.Getter;

/**
 * 统一错误码体系 — 5 域分段管理。
 *
 * <pre>
 * AG-001 ~ AG-099   Agent 层错误
 * TL-001 ~ TL-099   工具层错误
 * MCP-001 ~ MCP-099 MCP 协议层错误
 * SEC-001 ~ SEC-099 安全层错误
 * SES-001 ~ SES-099 会话层错误
 * </pre>
 *
 * @author vio
 */
@Getter
public enum AgentErrorCode {

    // ==================== Agent 层 (AG) ====================
    AGENT_NOT_IDLE("AG-001", "Agent 状态非 IDLE，无法启动"),
    AGENT_MAX_STEPS("AG-002", "Agent 已达到最大执行步数"),
    AGENT_CONSECUTIVE_ERRORS("AG-003", "Agent 连续错误次数超限"),
    AGENT_EXECUTION_FAILED("AG-004", "Agent 执行失败"),
    AGENT_TIMEOUT("AG-005", "Agent 执行超时"),

    // ==================== 工具层 (TL) ====================
    TOOL_NOT_FOUND("TL-001", "工具未找到"),
    TOOL_TIMEOUT("TL-002", "工具执行超时"),
    TOOL_EXECUTION_FAILED("TL-003", "工具执行失败"),
    TOOL_PERMISSION_DENIED("TL-004", "无权调用该工具"),
    TOOL_IDEMPOTENT_BLOCKED("TL-005", "幂等拦截：重复调用"),

    // ==================== MCP 协议层 (MCP) ====================
    MCP_INITIALIZE_FAILED("MCP-001", "MCP 初始化失败"),
    MCP_TOOL_LIST_FAILED("MCP-002", "获取 MCP 工具列表失败"),
    MCP_TOOL_CALL_FAILED("MCP-003", "MCP 工具调用失败"),
    MCP_TRANSPORT_ERROR("MCP-004", "MCP 传输层错误"),
    MCP_SESSION_EXPIRED("MCP-005", "MCP 会话已过期"),

    // ==================== 安全层 (SEC) ====================
    SEC_AUTH_FAILED("SEC-001", "认证失败"),
    SEC_TOKEN_EXPIRED("SEC-002", "令牌已过期"),
    SEC_PERMISSION_DENIED("SEC-003", "权限不足"),
    SEC_PATH_BLOCKED("SEC-004", "路径被安全策略拦截"),
    SEC_COMMAND_BLOCKED("SEC-005", "命令被安全策略拦截"),
    SEC_RATE_LIMITED("SEC-006", "请求频率超限"),

    // ==================== 会话层 (SES) ====================
    SES_NOT_FOUND("SES-001", "会话未找到"),
    SES_EXPIRED("SES-002", "会话已过期"),
    SES_RATE_LIMITED("SES-003", "会话请求频率超限");

    private final String code;
    private final String message;

    AgentErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /** 根据 code 查找错误码 */
    public static AgentErrorCode of(String code) {
        for (AgentErrorCode v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }

    /** 所在域 */
    public String domain() {
        return code.substring(0, code.indexOf('-'));
    }
}
