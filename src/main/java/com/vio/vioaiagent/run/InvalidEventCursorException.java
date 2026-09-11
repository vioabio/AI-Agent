package com.vio.vioaiagent.run;

/** 客户端游标格式错误；不能静默退回从头读取。 */
public class InvalidEventCursorException extends RuntimeException {
    private final String value;

    public InvalidEventCursorException(String value) {
        super("事件游标格式无效");
        this.value = value;
    }

    public String value() { return value; }
}
