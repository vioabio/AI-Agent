package com.vio.vioaiagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.feedback.*;
import com.vio.vioaiagent.memory.*;
import com.vio.vioaiagent.pokemon.config.ActivityNoticeFixtureInitializer;
import com.vio.vioaiagent.pokemon.config.PokemonFixtureInitializer;
import com.vio.vioaiagent.pokemon.repository.*;
import com.vio.vioaiagent.rag.GameAppVectorStoreConfig;
import com.vio.vioaiagent.rag.catalog.*;
import com.vio.vioaiagent.rag.ingestion.*;
import com.vio.vioaiagent.rag.promotion.*;
import com.vio.vioaiagent.run.*;
import com.vio.vioaiagent.security.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Loads the real demo/prod/test ConfigData and actually registers both repository
 * implementations. A datasource property alone cannot prove durable wiring.
 * JdbcTemplate is mocked: this verifies selection/constructors without DB calls.
 */
class DemoPersistenceWiringTest {
    private record Pair(Class<?> contract, Class<?> jdbc, Class<?> memory) { }

    private static final Pair[] REPOSITORIES = {
            new Pair(AgentRunRepository.class, JdbcRunRepository.class, InMemoryRunRepository.class),
            new Pair(ConversationRepository.class, JdbcConversationRepository.class, InMemoryConversationRepository.class),
            new Pair(ChatMessageRepository.class, JdbcChatMessageRepository.class, InMemoryChatMessageRepository.class),
            new Pair(EventRepository.class, JdbcEventRepository.class, InMemoryEventRepository.class),
            new Pair(IdempotencyStore.class, JdbcIdempotencyStore.class, InMemoryIdempotencyStore.class),
            new Pair(RunOutboxRepository.class, JdbcRunOutboxRepository.class, InMemoryRunOutboxRepository.class),
            new Pair(CheckpointRepository.class, JdbcDurableRunStateRepository.class, InMemoryDurableRunStateRepository.class),
            new Pair(RunStepOutputRepository.class, JdbcRunStepOutputRepository.class, InMemoryRunStepOutputRepository.class),
            new Pair(AnswerProjectionRepository.class, JdbcAnswerProjectionRepository.class, InMemoryAnswerProjectionRepository.class),
            new Pair(FavoriteRepository.class, JdbcFavoriteRepository.class, InMemoryFavoriteRepository.class),
            new Pair(KnowledgeCatalog.class, JdbcKnowledgeCatalog.class, InMemoryKnowledgeCatalog.class),
            new Pair(KnowledgeIngestionJobRepository.class, JdbcKnowledgeIngestionJobRepository.class, InMemoryKnowledgeIngestionJobRepository.class),
            new Pair(KnowledgeReleaseApprovalRepository.class, JdbcKnowledgeReleaseApprovalRepository.class, InMemoryKnowledgeReleaseApprovalRepository.class),
            new Pair(EvaluationReportRepository.class, JdbcEvaluationReportRepository.class, InMemoryEvaluationReportRepository.class),
            new Pair(HitlApprovalRepository.class, JdbcHitlApprovalRepository.class, InMemoryHitlApprovalRepository.class),
            new Pair(HitlAuditSink.class, JdbcHitlAuditSink.class, InMemoryHitlAuditSink.class),
            new Pair(FeedbackRepository.class, JdbcFeedbackRepository.class, InMemoryFeedbackRepository.class),
            new Pair(PokemonFactRepository.class, JdbcPokemonFactRepository.class, InMemoryPokemonFactRepository.class),
            new Pair(SavedTeamRepository.class, JdbcSavedTeamRepository.class, InMemorySavedTeamRepository.class),
            new Pair(ActivityNoticeRepository.class, JdbcActivityNoticeRepository.class, InMemoryActivityNoticeRepository.class),
            new Pair(UserMemoryRepository.class, JdbcUserMemoryRepository.class, InMemoryUserMemoryRepository.class)
    };

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private ApplicationContextRunner runner(String profile) {
        Class<?>[] components = Stream.concat(
                Arrays.stream(REPOSITORIES).flatMap(pair -> Stream.of(pair.jdbc(), pair.memory())),
                Stream.of(JdbcRunEventOutboxAppender.class, JdbcToolAuditSink.class, ToolAuditSinkConfiguration.class,
                        GlobalQuotaConfiguration.class, JdbcGlobalQuotaStore.class,
                        GlobalQuotaBackendValidator.class, RedisGlobalQuotaConfiguration.class))
                .toArray(Class<?>[]::new);
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile,
                        "SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:1/wiring-only",
                        "SPRING_DATASOURCE_PASSWORD=wiring-fixture-not-a-real-secret",
                        "VIO_RESOURCE_CURSOR_SECRET=wiring-fixture-cursor-secret",
                        "vio.security.global-quota-enabled=true",
                        "vio.security.global-quota-backend=jdbc")
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withUserConfiguration(components);
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo", "prod"})
    void durableProfilesActuallyRegisterJdbcForEveryCorePath(String profile) {
        runner(profile).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getActiveProfiles()).containsExactly(profile);
            for (Pair pair : REPOSITORIES) {
                assertThat(context).hasSingleBean(pair.contract()).doesNotHaveBean(pair.memory());
                assertThat(context.getBean(pair.contract())).isInstanceOf(pair.jdbc());
            }
            assertThat(context).hasSingleBean(RunEventOutboxAppender.class)
                    .hasSingleBean(ToolAuditSink.class).hasSingleBean(GlobalQuotaStore.class)
                    .hasSingleBean(ParameterMasker.class);
            assertThat(context.getBean(RunEventOutboxAppender.class)).isInstanceOf(JdbcRunEventOutboxAppender.class);
            assertThat(context.getBean(ToolAuditSink.class)).isInstanceOf(JdbcToolAuditSink.class);
            assertThat(context.getBean(GlobalQuotaStore.class)).isInstanceOf(JdbcGlobalQuotaStore.class);
            assertThat(context.getBean(EffectLedgerRepository.class)).isSameAs(context.getBean(CheckpointRepository.class));
            assertThat(context.getBean(ModelInvocationCacheRepository.class)).isSameAs(context.getBean(CheckpointRepository.class));
            assertNoJdbcOperations();
        });
    }

    @Test
    void testProfileKeepsOfflineInMemoryImplementations() {
        runner("test").withPropertyValues("vio.security.global-quota-backend=memory").run(context -> {
            assertThat(context).hasNotFailed();
            for (Pair pair : REPOSITORIES) {
                assertThat(context).hasSingleBean(pair.contract()).doesNotHaveBean(pair.jdbc());
                assertThat(context.getBean(pair.contract())).isInstanceOf(pair.memory());
            }
            assertThat(context).doesNotHaveBean(JdbcRunEventOutboxAppender.class)
                    .doesNotHaveBean(ToolAuditSink.class);
            assertThat(context.getBean(GlobalQuotaStore.class)).isInstanceOf(InMemoryGlobalQuotaStore.class);
            assertNoJdbcOperations();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo", "prod"})
    void durableProfilesCannotFallBackToMemoryQuota(String profile) {
        runner(profile).withPropertyValues("vio.security.global-quota-backend=memory").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("不支持的生产全局配额 backend");
            assertNoJdbcOperations();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo", "prod"})
    void durableProfilesFailClosedWhenRedisAdapterIsUnavailable(String profile) {
        runner(profile).withPropertyValues("vio.security.global-quota-backend=redis",
                "spring.data.redis.host=127.0.0.1").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GlobalQuotaStore.class);
            GlobalQuotaStore quota = context.getBean(GlobalQuotaStore.class);
            assertThat(quota).isInstanceOf(FailClosedGlobalQuotaStore.class);
            assertThatThrownBy(() -> quota.consume("fixture", "subject", 1, Duration.ofMinutes(1), Instant.now()))
                    .isInstanceOf(RedisGlobalQuotaStore.QuotaStoreUnavailableException.class);
            assertNoJdbcOperations();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo", "prod"})
    void durableProfilesExcludeLegacyMemoryRagAndFixtureLoadersEvenWhenRequested(String profile) {
        runner(profile).withUserConfiguration(GameAppVectorStoreConfig.class,
                        PokemonFixtureInitializer.class, ActivityNoticeFixtureInitializer.class)
                .withPropertyValues("vio.pokemon.fixture.enabled=true",
                        "vio.pokemon.activity.fixture.enabled=true", "vio.rag.legacy-memory.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(GameAppVectorStoreConfig.class)
                            .doesNotHaveBean(PokemonFixtureInitializer.class)
                            .doesNotHaveBean(ActivityNoticeFixtureInitializer.class);
                    assertNoJdbcOperations();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo", "prod"})
    void durableProfilesKeepAuditFailClosedIfJdbcSinkIsMissing(String profile) {
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=" + profile)
                .withUserConfiguration(MissingAuditSinkScan.class).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ToolAuditSink.class);
                    ToolAuditSink sink = context.getBean(ToolAuditSink.class);
                    assertThat(sink).isInstanceOf(FailClosedToolAuditSink.class);
                    assertThat(sink.durable()).isTrue();
                    assertThat(sink.failClosedOnError()).isTrue();
                    assertThatThrownBy(() -> sink.record(AuditEntry.of("trace", "run", "actor", "fixture",
                            Map.of(), "allow", "gateway", 1)))
                            .isInstanceOf(AuditWriteException.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"demo", "prod"})
    void componentScanSelectsOnlyJdbcAuditWhenItIsPresent(String profile) {
        new ApplicationContextRunner().withPropertyValues("spring.profiles.active=" + profile)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withUserConfiguration(JdbcAuditSinkScan.class).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ToolAuditSink.class)
                            .doesNotHaveBean(FailClosedToolAuditSink.class)
                            .hasSingleBean(ParameterMasker.class);
                    assertThat(context.getBean(ToolAuditSink.class)).isInstanceOf(JdbcToolAuditSink.class);
                    assertNoJdbcOperations();
                });
    }

    private void assertNoJdbcOperations() {
        // Spring legitimately invokes InitializingBean.afterPropertiesSet().
        // Every other interaction, including query/update/execute/batch and
        // datasource access from a repository constructor, remains forbidden.
        assertThat(mockingDetails(jdbc).getInvocations())
                .as("wiring must not execute JDBC operations or access a datasource")
                .allSatisfy(invocation -> assertThat(invocation.getMethod().getName())
                        .isEqualTo("afterPropertiesSet"));
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = {ToolAuditSink.class, ToolAuditSinkConfiguration.class}, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = ToolAuditSinkConfiguration.class))
    static class MissingAuditSinkScan { }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = {ToolAuditSink.class, ToolAuditSinkConfiguration.class}, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {ToolAuditSinkConfiguration.class, JdbcToolAuditSink.class}))
    static class JdbcAuditSinkScan { }
}
