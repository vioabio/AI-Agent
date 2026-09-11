package com.vio.vioaiagent.run;

/** 资源集合游标格式、签名、主体或资源类型不合法。 */
public class InvalidResourceCursorException extends RuntimeException {
    public InvalidResourceCursorException(String value) {
        super("资源游标无效");
    }
}
