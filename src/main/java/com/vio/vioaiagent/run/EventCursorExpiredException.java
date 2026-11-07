package com.vio.vioaiagent.run;

/** 事件保留期后游标失效，客户端应重新获取会话摘要。 */
public class EventCursorExpiredException extends RuntimeException {
    private final long retainedFrom;

    public EventCursorExpiredException(long retainedFrom) {
        super("事件游标已过期，请重新获取会话摘要；保留起点: " + retainedFrom);
        this.retainedFrom = retainedFrom;
    }

    public long retainedFrom() { return retainedFrom; }
}

