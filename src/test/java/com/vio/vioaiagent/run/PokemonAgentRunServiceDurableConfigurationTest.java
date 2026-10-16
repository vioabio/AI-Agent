package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.DurableRunProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PokemonAgentRunServiceDurableConfigurationTest {
    @Test
    void durableModeCannotSilentlyExecuteWithoutLeaseStorage() {
        DurableRunProperties properties = new DurableRunProperties();
        properties.setDurableResume(true);
        RunExecutor executor = mock(RunExecutor.class);
        ExecutorService worker = mock(ExecutorService.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new PokemonAgentRunService(new InMemoryRunRepository(), new InMemoryEventRepository(),
                        new InMemoryConversationRepository(), new InMemoryChatMessageRepository(),
                        executor, worker, scheduler, null, properties));

        assertTrue(failure.getMessage().contains("CheckpointRepository"));
        verifyNoInteractions(executor, worker, scheduler);
    }
}
