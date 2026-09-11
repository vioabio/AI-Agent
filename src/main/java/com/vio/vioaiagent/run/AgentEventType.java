package com.vio.vioaiagent.run;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** 面向客户端、恢复和审计的结构化事件类型。 */
public enum AgentEventType {
    RUN_CREATED("run.created", false),
    RUN_STARTED("run.started", false),
    CONTEXT_CONFIRMED("context.confirmed", false),
    CONTEXT_CARD("context.card", false),
    ROUTE_SELECTED("route.selected", false),
    RETRIEVAL_STARTED("retrieval.started", false),
    RETRIEVAL_FINISHED("retrieval.finished", false),
    RETRIEVAL_CITATION_CARD("retrieval.citation.card", false),
    FACT_CARD("fact.card", false),
    CALCULATION_CARD("calculation.card", false),
    TEAM_MATRIX("team.matrix", false),
    ACTIVITY_CARD("activity.card", false),
    TOOL_STARTED("tool.started", false),
    TOOL_FINISHED("tool.finished", false),
    APPROVAL_REQUIRED("approval.required", false),
    APPROVAL_RESOLVED("approval.resolved", false),
    ANSWER_DELTA("answer.delta", false),
    ANSWER_COMPLETED("answer.completed", false),
    CITATION("citation", false),
    LIMITATION_ADDED("limitation.added", false),
    FEEDBACK_CARD("feedback.card", false),
    ERROR_CARD("error.card", false),
    USAGE("usage", false),
    MESSAGE_USER("message.user", false),
    MESSAGE_ASSISTANT("message.assistant", false),
    CHECKPOINT_SAVED("checkpoint.saved", false),
    RUN_COMPLETED("run.completed", true),
    RUN_FAILED("run.failed", true),
    RUN_CANCELLED("run.cancelled", true),
    RUN_TIMED_OUT("run.timed_out", true);

    private final String wireName;
    private final boolean terminal;

    AgentEventType(String wireName, boolean terminal) {
        this.wireName = wireName;
        this.terminal = terminal;
    }

    /** 对外 JSON/SSE 契约统一使用小写 wire name，而不是 Java 枚举名。 */
    @JsonValue
    public String wireName() { return wireName; }
    public boolean isTerminal() { return terminal; }

    @JsonCreator
    public static AgentEventType fromWireName(String value) {
        if (value == null) throw new IllegalArgumentException("事件类型不能为空");
        for (AgentEventType type : values()) {
            if (type.wireName.equalsIgnoreCase(value) || type.name().equalsIgnoreCase(value)) return type;
        }
        throw new IllegalArgumentException("未知事件类型: " + value);
    }
}
