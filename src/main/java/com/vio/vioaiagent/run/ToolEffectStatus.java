package com.vio.vioaiagent.run;

/** 工具副作用账本状态；UNKNOWN 表示进程在外部调用确认前退出，必须人工处理。 */
public enum ToolEffectStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
    WAITING_APPROVAL,
    UNKNOWN
}
