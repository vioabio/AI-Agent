package com.vio.vioaiagent.common;

import lombok.Getter;

/**
 * 统一响应状态码枚举
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务不可用"),

    // 业务异常 1xxx
    BUSINESS_ERROR(1000, "业务异常"),
    PARAM_ERROR(1001, "参数校验失败"),
    DATA_NOT_FOUND(1002, "数据不存在"),
    DATA_DUPLICATE(1003, "数据重复"),
    OPERATION_FAILED(1004, "操作失败"),

    // 系统异常 2xxx
    SYSTEM_ERROR(2000, "系统异常"),
    RPC_ERROR(2001, "远程调用失败"),
    DB_ERROR(2002, "数据库异常"),
    CACHE_ERROR(2003, "缓存异常"),
    ;

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}