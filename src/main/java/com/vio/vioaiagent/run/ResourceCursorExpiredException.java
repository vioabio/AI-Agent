package com.vio.vioaiagent.run;

/** 资源游标已经超过服务端保留的有效窗口。 */
public class ResourceCursorExpiredException extends RuntimeException {
    public ResourceCursorExpiredException() {
        super("资源游标已过期");
    }
}
