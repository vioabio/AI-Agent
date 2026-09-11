package com.vio.vioaiagent.pokemon.service;

/** 对外隐藏不存在、未发布、无来源或过期的活动资讯，避免泄露候选内容。 */
public class ActivityNoticeNotFoundException extends RuntimeException {
    public ActivityNoticeNotFoundException(String message) {
        super(message);
    }
}
