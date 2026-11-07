package com.vio.vioaiagent.orchestration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("会话状态机")
class SessionStateMachineTest {
    private SessionStateMachine machine;

    @BeforeEach
    void setUp() { machine = new SessionStateMachine(); }

    @Test @DisplayName("CREATED → INITIALIZING 合法")
    void shouldAllowCreatedToInitializing() {
        machine.create("s1");
        assertDoesNotThrow(() -> machine.transition("s1", SessionState.INITIALIZING));
    }

    @Test @DisplayName("CREATED → RUNNING 非法")
    void shouldRejectCreatedToRunning() {
        machine.create("s1");
        assertThrows(IllegalStateTransitionException.class,
                () -> machine.transition("s1", SessionState.RUNNING));
    }

    @Test @DisplayName("RUNNING → COMPLETED 合法")
    void shouldAllowRunningToCompleted() {
        machine.create("s1");
        machine.transition("s1", SessionState.INITIALIZING);
        machine.transition("s1", SessionState.READY);
        machine.transition("s1", SessionState.RUNNING);
        assertDoesNotThrow(() -> machine.transition("s1", SessionState.COMPLETED));
    }

    @Test @DisplayName("终态不可再转换")
    void shouldRejectTransitionFromTerminal() {
        machine.create("s1");
        machine.transition("s1", SessionState.INITIALIZING);
        machine.transition("s1", SessionState.READY);
        machine.transition("s1", SessionState.RUNNING);
        machine.transition("s1", SessionState.COMPLETED);
        assertThrows(IllegalStateTransitionException.class,
                () -> machine.transition("s1", SessionState.RUNNING));
    }

    @Test @DisplayName("不存在的会话应抛异常")
    void shouldThrowForUnknownSession() {
        assertThrows(IllegalArgumentException.class,
                () -> machine.transition("ghost", SessionState.RUNNING));
    }

    @Test @DisplayName("终态判断")
    void shouldIdentifyTerminalStates() {
        assertTrue(SessionState.COMPLETED.isTerminal());
        assertTrue(SessionState.FAILED.isTerminal());
        assertFalse(SessionState.RUNNING.isTerminal());
    }
}
