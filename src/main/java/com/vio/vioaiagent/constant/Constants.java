package com.vio.vioaiagent.constant;

/**
 * 通用常量
 */
public final class Constants {

    private Constants() {
    }

    /** 系统用户 ID */
    public static final Long SYSTEM_USER_ID = 0L;

    /** 字符串 true */
    public static final String STR_TRUE = "true";
    /** 字符串 false */
    public static final String STR_FALSE = "false";

    /** TraceId 请求头 */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 默认日期格式 */
    public static final String DATE_PATTERN = "yyyy-MM-dd";
    /** 默认日期时间格式 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
}