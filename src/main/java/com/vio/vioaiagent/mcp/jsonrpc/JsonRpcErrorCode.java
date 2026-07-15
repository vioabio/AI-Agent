package com.vio.vioaiagent.mcp.jsonrpc;

import lombok.Getter;

/**
 * JSON-RPC 2.0 错误码体系.
 *
 * <p>覆盖三个层次：
 * <ul>
 *   <li>JSON-RPC 2.0 标准错误码（-32700 ~ -32603）</li>
 *   <li>MCP 协议自定义错误码（-32002 ~ -32005）</li>
 *   <li>业务扩展错误码（-32010 ~ -32013）</li>
 * </ul>
 *
 * <p>面试加分：自定义错误码体系体现了对协议规范的理解深度，
 * 比单纯 catch Exception 然后返回 "error" 的生产级程度高一个档次.
 *
 * @author vio
 * @see <a href="https://www.jsonrpc.org/specification#error_object">JSON-RPC 2.0 Error Object</a>
 */
@Getter
public enum JsonRpcErrorCode {

    // ==================== JSON-RPC 2.0 标准错误码 ====================

    /** 解析错误：无效的 JSON */
    PARSE_ERROR(-32700, "解析错误：无效的 JSON"),
    /** 无效请求：不是有效的 JSON-RPC 2.0 请求 */
    INVALID_REQUEST(-32600, "无效请求：不是有效的 JSON-RPC 2.0 请求"),
    /** 方法不存在 */
    METHOD_NOT_FOUND(-32601, "方法不存在"),
    /** 无效参数 */
    INVALID_PARAMS(-32602, "无效参数"),
    /** 内部错误 */
    INTERNAL_ERROR(-32603, "内部错误"),

    // ==================== MCP 协议自定义错误码 ====================

    /** 服务端未初始化（客户端必须先发送 initialize 请求） */
    SERVER_NOT_INITIALIZED(-32002, "服务端未初始化"),
    /** 未知能力（请求的能力不被服务端支持） */
    UNKNOWN_CAPABILITY(-32003, "未知能力"),
    /** 工具未找到 */
    TOOL_NOT_FOUND(-32004, "工具未找到"),
    /** 工具执行失败 */
    TOOL_EXECUTION_ERROR(-32005, "工具执行失败"),

    // ==================== 业务扩展错误码（面试加分项） ====================

    /** 鉴权失败 */
    AUTH_FAILED(-32010, "鉴权失败"),
    /** 请求频率超限 */
    RATE_LIMITED(-32011, "请求频率超限"),
    /** 会话已过期 */
    SESSION_EXPIRED(-32012, "会话已过期"),
    /** 权限不足 */
    PERMISSION_DENIED(-32013, "权限不足");

    /** 错误码 */
    private final int code;
    /** 错误描述 */
    private final String message;

    JsonRpcErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 根据错误码整数值查找对应的枚举常量.
     *
     * @param code 错误码
     * @return 匹配的枚举常量, 未找到返回 {@code null}
     */
    public static JsonRpcErrorCode of(int code) {
        for (JsonRpcErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
