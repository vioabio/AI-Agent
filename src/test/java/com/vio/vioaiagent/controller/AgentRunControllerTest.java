package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.run.AgentRunStatus;
import com.vio.vioaiagent.run.CancelResult;
import com.vio.vioaiagent.run.EventCursorExpiredException;
import com.vio.vioaiagent.run.PokemonAgentRunService;
import com.vio.vioaiagent.run.RunNotFoundException;
import com.vio.vioaiagent.run.RunView;
import com.vio.vioaiagent.run.InvalidEventCursorException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M5 断线恢复所需的 Run 快照接口契约测试。 */
class AgentRunControllerTest {

    @Test
    void runSnapshotDelegatesWithLocalFallbackIdentity() {
        PokemonAgentRunService service = mock(PokemonAgentRunService.class);
        RunView expected = new RunView("run-1", "session-1", "conversation-1",
                AgentRunStatus.RUNNING, "/chat/runs/run-1/events", "trace-1", Instant.now());
        when(service.findRun("run-1", "anonymous")).thenReturn(Optional.of(expected));

        RunView actual = new AgentRunController(service).run("run-1", null);

        assertSame(expected, actual);
    }

    @Test
    void missingRunIsNotConvertedToSuccessfulSnapshot() {
        PokemonAgentRunService service = mock(PokemonAgentRunService.class);
        when(service.findRun("missing", "anonymous")).thenReturn(Optional.empty());

        assertThrows(RunNotFoundException.class,
                () -> new AgentRunController(service).run("missing", null));
    }

    @Test
    void expiredSseCursorIsRejectedBeforeEmitterIsCreated() {
        PokemonAgentRunService service = mock(PokemonAgentRunService.class);
        when(service.events("run-1", "anonymous", 9))
                .thenThrow(new EventCursorExpiredException(10));

        assertThrows(EventCursorExpiredException.class,
                () -> new AgentRunController(service).eventStream("run-1", 9, null, null));
    }

    @Test
    void malformedCursorIsRejectedInsteadOfSilentlyReset() {
        PokemonAgentRunService service = mock(PokemonAgentRunService.class);
        assertThrows(InvalidEventCursorException.class,
                () -> new AgentRunController(service).events("run-1", 0, "not-a-number", null));
    }

    @Test
    void expiredCursorReturnsRecoveryCursorAndRunSnapshotUrl() {
        AgentRunController controller = new AgentRunController(mock(PokemonAgentRunService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/runs/run-1/events/stream");

        var response = controller.handleExpired(new EventCursorExpiredException(42), request);

        assertEquals(HttpStatus.GONE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("EVENT_CURSOR_EXPIRED", response.getBody().code());
        assertEquals(42L, response.getBody().retainedFromEventId());
        assertEquals("/api/chat/runs/run-1", response.getBody().latestRunUrl());
    }

    @Test
    void cancelForwardsOptionalReasonBodyToService() {
        PokemonAgentRunService service = mock(PokemonAgentRunService.class);
        CancelResult expected = new CancelResult("run-1", AgentRunStatus.CANCELLED, true, 3L);
        when(service.cancel("run-1", "anonymous", "cancel-key", "用户主动取消"))
                .thenReturn(expected);
        AgentRunController.CancelRequest request = new AgentRunController.CancelRequest();
        request.reason = "用户主动取消";

        CancelResult actual = new AgentRunController(service)
                .cancel("run-1", null, "cancel-key", request);

        assertSame(expected, actual);
        verify(service).cancel("run-1", "anonymous", "cancel-key", "用户主动取消");
    }
}
