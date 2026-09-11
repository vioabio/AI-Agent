package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.config.SecurityProperties;
import com.vio.vioaiagent.pokemon.domain.PokemonFact;
import com.vio.vioaiagent.pokemon.domain.RulesetBinding;
import com.vio.vioaiagent.pokemon.domain.VersionedDataRelease;
import com.vio.vioaiagent.pokemon.repository.InMemoryPokemonFactRepository;
import com.vio.vioaiagent.pokemon.repository.InMemorySavedTeamRepository;
import com.vio.vioaiagent.pokemon.service.PokemonResourceService;
import com.vio.vioaiagent.run.CreateRunCommand;
import com.vio.vioaiagent.run.InMemoryChatMessageRepository;
import com.vio.vioaiagent.run.InMemoryConversationRepository;
import com.vio.vioaiagent.run.InMemoryEventRepository;
import com.vio.vioaiagent.run.InMemoryFavoriteRepository;
import com.vio.vioaiagent.run.InMemoryRunRepository;
import com.vio.vioaiagent.run.PokemonAgentRunService;
import com.vio.vioaiagent.run.RunExecutor;
import com.vio.vioaiagent.run.RunNotFoundException;
import com.vio.vioaiagent.run.RunView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 确保资源 API 和兼容 /chat 入口共享同一会话删除生命周期。 */
class ResourceControllerConversationDeletionTest {

    @Test
    void resourceDeleteCancelsActiveRunAndRemovesMessages() throws Exception {
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventRepository events = new InMemoryEventRepository();
        InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RunExecutor blockingExecutor = context -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return "不应在删除后暴露";
        };
        PokemonAgentRunService runService = new PokemonAgentRunService(runs, events, conversations,
                messages, blockingExecutor, worker, scheduler);
        try {
            RunView created = runService.createRun(new CreateRunCommand("user-1", "session-1", null,
                    "删除资源 API 会话", "sv-1", "singles", "rules-1", "zh-CN", "trace-1", null,
                    Duration.ofSeconds(30)));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean("pokemonAgentRunService", runService);
            ResourceController controller = new ResourceController(conversations, pokemonService(),
                    new InMemoryFavoriteRepository(), SecurityProperties.defaults(), null, null,
                    beans.getBeanProvider(PokemonAgentRunService.class));

            controller.deleteConversation(created.conversationId(), "user-1");

            assertTrue(runService.findRun(created.runId(), "user-1").isEmpty());
            assertThrows(RunNotFoundException.class,
                    () -> runService.messages(created.conversationId(), "user-1"));
        } finally {
            release.countDown();
            runService.shutdown();
        }
    }

    private PokemonResourceService pokemonService() {
        PokemonFact fact = new PokemonFact(101, 1, 25, "pikachu", "皮卡丘", "default", "皮卡丘",
                "sv-data-v1", "pokemon-scarlet-violet", "https://example.invalid/pokemon", "sha256:fixture",
                "REVIEWED", "ELECTRIC", null, 35, 55, 40, 50, 50, 90);
        return new PokemonResourceService(new InMemoryPokemonFactRepository(List.of(fact),
                List.of(new VersionedDataRelease("sv-data-v1", "pokemon-scarlet-violet",
                        "https://example.invalid/release", "sha256:fixture", "REVIEWED")),
                List.of(new RulesetBinding("sv-ou-v1", "pokemon-scarlet-violet", "OU", true))),
                new InMemorySavedTeamRepository());
    }
}
