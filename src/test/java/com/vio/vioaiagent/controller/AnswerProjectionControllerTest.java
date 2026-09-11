package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.run.AnswerProjection;
import com.vio.vioaiagent.run.PokemonAgentRunService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnswerProjectionControllerTest {
    @Test
    void readsProjectionForScopedUser() {
        PokemonAgentRunService service = mock(PokemonAgentRunService.class);
        AnswerProjection value = AnswerProjection.of("answer-1", "run-1", "session-1", "conversation-1",
                "user-1", "trace-1", "COMPLETED", "answer", "pokemon-scarlet-violet",
                "casual-singles", "casual-v1", "data-1", "knowledge-1", "index-1");
        when(service.findAnswer("answer-1", "user-1")).thenReturn(Optional.of(value));
        AnswerProjectionController controller = new AnswerProjectionController(service, SecurityProperties.defaults());
        assertEquals("answer-1", controller.get("answer-1", "user-1").answerId());
    }

    @Test
    void missingProjectionDoesNotLeakExistence() {
        PokemonAgentRunService service = mock(PokemonAgentRunService.class);
        when(service.findAnswer("answer-1", "user-1")).thenReturn(Optional.empty());
        AnswerProjectionController controller = new AnswerProjectionController(service, SecurityProperties.defaults());
        assertThrows(RuntimeException.class, () -> controller.get("answer-1", "user-1"));
    }
}
