package com.vio.vioaiagent.pokemon.service;

/** 活动记录存在但不能安全展示（例如已过期或来源不可靠）。 */
public class ActivityNoticeUnavailableException extends RuntimeException {
    private final String reasonCode;

    public ActivityNoticeUnavailableException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
