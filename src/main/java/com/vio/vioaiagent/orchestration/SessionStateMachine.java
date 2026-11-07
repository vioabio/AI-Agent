package com.vio.vioaiagent.orchestration;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话状态机 — 管理会话状态的合法转换.
 *
 * <p>每个会话由 sessionId 标识, 状态机跟踪其当前状态并强制合法转换.
 * 非法转换会抛出 {@link IllegalStateTransitionException}.
 *
 * <pre>{@code
 * SessionStateMachine machine = new SessionStateMachine();
 * machine.create("session-1");
 * machine.transition("session-1", SessionState.RUNNING);  // OK
 * machine.transition("session-1", SessionState.CREATED);   // throws!
 * }</pre>
 *
 * @author vio
 */
@Slf4j
public class SessionStateMachine {

    /** sessionId → 当前状态 */
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    /**
     * 注册新会话（初始状态 CREATED）.
     */
    public void create(String sessionId) {
        sessionStates.put(sessionId, SessionState.CREATED);
        log.debug("会话已创建: {}", sessionId);
    }

    /**
     * 执行状态转换.
     *
     * @param sessionId 会话 ID
     * @param target    目标状态
     * @throws IllegalStateTransitionException 非法转换时抛出
     */
    public void transition(String sessionId, SessionState target) {
        SessionState current = sessionStates.get(sessionId);
        if (current == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }

        if (!current.canTransitionTo(target)) {
            throw new IllegalStateTransitionException(
                    "非法状态转换: " + current + " → " + target + " (sessionId=" + sessionId + ")");
        }

        sessionStates.put(sessionId, target);
        log.info("会话状态转换: {} → {} (sessionId={})", current, target, sessionId);
    }

    /**
     * 获取当前会话状态.
     */
    public SessionState getState(String sessionId) {
        SessionState state = sessionStates.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return state;
    }

    /**
     * 移除会话（任何状态下都可以移除）.
     */
    public void remove(String sessionId) {
        SessionState removed = sessionStates.remove(sessionId);
        if (removed != null) {
            log.debug("会话已移除: {} (状态={})", sessionId, removed);
        }
    }

    /**
     * 查询会话是否已注册.
     */
    public boolean exists(String sessionId) {
        return sessionStates.containsKey(sessionId);
    }

    /** 当前活跃会话数 */
    public int activeSessionCount() {
        return (int) sessionStates.values().stream()
                .filter(SessionState::isActive)
                .count();
    }
}
