package com.vio.vioaiagent.orchestration;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 会话状态枚举 — 10 种会话级状态 + 合法转换矩阵.
 *
 * <p>与 AgentState（IDLE/RUNNING/FINISHED/ERROR）不同,
 * SessionState 描述的是从 HTTP 请求视角看的完整会话生命周期.
 *
 * <pre>
 * 正常流程:
 *   CREATED → INITIALIZING → READY → RUNNING → COMPLETED
 *
 * 异常流程:
 *   RUNNING → WAITING_HITL → RUNNING (审批通过后恢复)
 *   RUNNING → FAILED / TIMED_OUT / CANCELLED
 *   INITIALIZING → FAILED / TIMED_OUT
 * </pre>
 *
 * @author vio
 */
public enum SessionState {

    /** 已创建（Agent 实例化但未开始执行） */
    CREATED,
    /** 初始化中（MCP 握手、工具注册等） */
    INITIALIZING,
    /** 就绪（初始化完成，等待用户输入） */
    READY,
    /** 执行中（Agent Loop 正在运行） */
    RUNNING,
    /** 等待人工审批（HITL 触发） */
    WAITING_HITL,
    /** 已暂停 */
    PAUSED,
    /** 已完成（任务正常结束） */
    COMPLETED,
    /** 失败（异常终止） */
    FAILED,
    /** 超时（执行超过限制时间） */
    TIMED_OUT,
    /** 已取消（用户手动停止） */
    CANCELLED;

    /** 合法状态转换矩阵 — from → allowed targets */
    private static final Map<SessionState, Set<SessionState>> TRANSITIONS = Map.ofEntries(
            Map.entry(CREATED, Set.of(INITIALIZING, CANCELLED)),
            Map.entry(INITIALIZING, Set.of(READY, FAILED, TIMED_OUT)),
            Map.entry(READY, Set.of(RUNNING, CANCELLED)),
            Map.entry(RUNNING, Set.of(WAITING_HITL, COMPLETED, FAILED, TIMED_OUT, CANCELLED)),
            Map.entry(WAITING_HITL, Set.of(RUNNING, CANCELLED, TIMED_OUT)),
            Map.entry(PAUSED, Set.of(RUNNING, CANCELLED, TIMED_OUT)),
            Map.entry(COMPLETED, Collections.emptySet()),
            Map.entry(FAILED, Collections.emptySet()),
            Map.entry(TIMED_OUT, Collections.emptySet()),
            Map.entry(CANCELLED, Collections.emptySet())
    );

    /**
     * 判断从当前状态转换到目标状态是否合法.
     */
    public boolean canTransitionTo(SessionState target) {
        Set<SessionState> allowed = TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }

    /**
     * 获取当前状态的所有合法目标状态.
     */
    public Set<SessionState> allowedTransitions() {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet());
    }

    /** 是否为终态（不可再转换） */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == TIMED_OUT || this == CANCELLED;
    }

    /** 是否为活跃态（可以接收操作） */
    public boolean isActive() {
        return !isTerminal();
    }
}
