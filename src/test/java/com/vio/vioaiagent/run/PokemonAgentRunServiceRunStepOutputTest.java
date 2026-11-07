package com.vio.vioaiagent.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vio.vioaiagent.config.DurableRunProperties;
import com.vio.vioaiagent.config.RunAdmissionProperties;
import com.vio.vioaiagent.observability.AgentMetrics;
import com.vio.vioaiagent.rag.contract.AnswerContract;
import com.vio.vioaiagent.rag.contract.Citation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** R07 immutable encrypted step output is wired into the commit-answer path. */
class PokemonAgentRunServiceRunStepOutputTest {

    @Test
    void durableCompletionPersistsEncryptedCanonicalArtifact() throws Exception {
        InMemoryRunStepOutputRepository outputs = new InMemoryRunStepOutputRepository();
        AesGcmCodec codec = new AesGcmCodec();
        Fixture fixture = new Fixture(true, outputs, codec);
        try {
            RunView run = fixture.create();
            AgentEvent terminal = RunTestAwait.awaitTerminalEvent(fixture.events, run.runId());
            assertEquals(AgentEventType.RUN_COMPLETED, terminal.type());

            RunStepOutput output = outputs.find(run.runId(), 1).orElseThrow();
            assertEquals(fixture.executor.getClass().getName(), output.executorVersion());
            assertEquals("kms-v7", output.keyVersion());
            assertNotEquals("答案", output.encryptedPayload());
            String plaintext = codec.decode(output.encryptedPayload(), output.keyVersion());
            JsonNode payload = new ObjectMapper().readTree(plaintext);
            assertEquals("run-step-output.v1", payload.get("schemaVersion").asText());
            assertEquals(run.runId(), payload.get("runId").asText());
            assertEquals(1, payload.get("stepNo").asInt());
            assertEquals("答案", payload.get("answer").asText());
            assertEquals("sv-1", payload.get("gameVersion").asText());
            assertEquals("singles", payload.get("battleFormat").asText());
            assertTrue(payload.has("citations"));
            assertTrue(payload.has("limitations"));
            assertTrue(payload.has("lineage"));
            // Prompt/policy data must never be present in the opaque artifact.
            assertFalse(plaintext.contains("查询问题"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void structuredContractPersistsFullLineageAndCitationArtifact() throws Exception {
        InMemoryRunStepOutputRepository outputs = new InMemoryRunStepOutputRepository();
        AesGcmCodec codec = new AesGcmCodec();
        StructuredExecutor executor = StructuredExecutor.full();
        Fixture fixture = new Fixture(true, outputs, codec, executor, true);
        try {
            RunView run = fixture.create();
            assertEquals(AgentEventType.RUN_COMPLETED,
                    RunTestAwait.awaitTerminalEvent(fixture.events, run.runId()).type());

            RunStepOutput output = outputs.find(run.runId(), 1).orElseThrow();
            JsonNode payload = new ObjectMapper().readTree(codec.decode(output.encryptedPayload(), output.keyVersion()));
            assertEquals("结构化答案", payload.get("answer").asText());
            assertEquals("trace-structured", payload.get("traceId").asText());
            assertEquals("data-structured", payload.get("lineage").get("dataReleaseId").asText());
            assertEquals("knowledge-structured", payload.get("lineage").get("knowledgeReleaseId").asText());
            assertEquals(1, payload.get("citations").size());
            assertEquals("doc-1", payload.get("citations").get(0).get("documentId").asText());
            assertEquals(AnswerProjection.LineageStatus.COMPLETE,
                    fixture.projections.findByRunId(run.runId()).orElseThrow().lineageStatus());
        } finally {
            fixture.close();
        }
    }

    @Test
    void structuredContractMayCompleteWithExplicitPartialLineage() throws Exception {
        InMemoryRunStepOutputRepository outputs = new InMemoryRunStepOutputRepository();
        AesGcmCodec codec = new AesGcmCodec();
        Fixture fixture = new Fixture(true, outputs, codec, StructuredExecutor.partial(), false);
        try {
            RunView run = fixture.create();
            assertEquals(AgentEventType.RUN_COMPLETED,
                    RunTestAwait.awaitTerminalEvent(fixture.events, run.runId()).type());

            RunStepOutput output = outputs.find(run.runId(), 1).orElseThrow();
            JsonNode payload = new ObjectMapper().readTree(codec.decode(output.encryptedPayload(), output.keyVersion()));
            assertTrue(payload.get("lineage").get("dataReleaseId").isNull());
            assertTrue(payload.get("lineage").get("knowledgeReleaseId").isNull());
            assertEquals(AnswerProjection.LineageStatus.PARTIAL,
                    fixture.projections.findByRunId(run.runId()).orElseThrow().lineageStatus());
        } finally {
            fixture.close();
        }
    }

    @Test
    void sameOutputReplayReusesExistingCiphertextWithRandomNonce() {
        AesGcmCodec codec = new AesGcmCodec("kms-v6");
        ReplayRepository outputs = new ReplayRepository(codec);
        StructuredExecutor executor = StructuredExecutor.full();
        Fixture fixture = new Fixture(true, outputs, codec, executor, true);
        outputs.configureReplay(fixture.service, executor);
        try {
            RunView run = fixture.create();
            assertEquals(AgentEventType.RUN_COMPLETED,
                    RunTestAwait.awaitTerminalEvent(fixture.events, run.runId()).type());
            assertEquals(1, outputs.saveCalls, "replay must reuse existing artifact without a second write");
            assertNotNull(outputs.firstCiphertext);
            AesGcmCodec nonceCodec = new AesGcmCodec();
            assertNotEquals(nonceCodec.encode("same"), nonceCodec.encode("same"),
                    "AES-GCM encode must use a random nonce");
        } finally {
            fixture.close();
        }
    }

    @Test
    void differentAnswerReplayConflictsWithoutPublishingAnswerFacts() {
        AesGcmCodec codec = new AesGcmCodec("kms-v6");
        ReplayRepository outputs = new ReplayRepository(codec);
        Fixture fixture = new Fixture(true, outputs, codec, StructuredExecutor.full(), true);
        outputs.configureReplay(fixture.service, StructuredExecutor.differentAnswer());
        try {
            RunView run = fixture.create();
            assertEquals(AgentEventType.RUN_FAILED,
                    RunTestAwait.awaitTerminalEvent(fixture.events, run.runId()).type());
            assertNoAnswerFacts(fixture, run);
            assertEquals(1, outputs.saveCalls);
        } finally {
            fixture.close();
        }
    }

    @Test
    void contextDigestAndExecutorMetadataTamperingFailsClosedBeforeAnswerPublication() {
        assertTamperFails(output -> new RunStepOutput(output.runId(), output.stepNo(), output.executorVersion(),
                "0".repeat(64), output.payloadDigest(), output.encryptedPayload(), output.keyVersion(),
                output.createdAt(), output.expiresAt()));
        assertTamperFails(output -> new RunStepOutput(output.runId(), output.stepNo(), output.executorVersion(),
                output.contextHash(), "0".repeat(64), output.encryptedPayload(), output.keyVersion(),
                output.createdAt(), output.expiresAt()));
        assertTamperFails(output -> new RunStepOutput(output.runId(), output.stepNo(), "tampered-executor-v9",
                output.contextHash(), output.payloadDigest(), output.encryptedPayload(), output.keyVersion(),
                output.createdAt(), output.expiresAt()));
        assertTamperFails(output -> new RunStepOutput("other-run", output.stepNo(), output.executorVersion(),
                output.contextHash(), output.payloadDigest(), output.encryptedPayload(), output.keyVersion(),
                output.createdAt(), output.expiresAt()));
        assertTamperFails(output -> new RunStepOutput(output.runId(), output.stepNo() + 1, output.executorVersion(),
                output.contextHash(), output.payloadDigest(), output.encryptedPayload(), output.keyVersion(),
                output.createdAt(), output.expiresAt()));
    }

    @Test
    void cipherDecodeFailureFailsClosedBeforeAnswerPublication() {
        assertTamperFails(output -> new RunStepOutput(output.runId(), output.stepNo(), output.executorVersion(),
                output.contextHash(), output.payloadDigest(), corruptCiphertext(output.encryptedPayload()),
                output.keyVersion(), output.createdAt(), output.expiresAt()));
    }

    @Test
    void expiredArtifactFailsClosedBeforeAnswerPublication() {
        java.time.Instant now = java.time.Instant.now();
        assertTamperFails(output -> new RunStepOutput(output.runId(), output.stepNo(), output.executorVersion(),
                output.contextHash(), output.payloadDigest(), output.encryptedPayload(), output.keyVersion(),
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1))));
    }

    @Test
    void codecIsNeverCalledInsideLeaseFence() {
        RecordingCheckpoint checkpoints = new RecordingCheckpoint();
        FenceAwareCodec codec = new FenceAwareCodec(checkpoints);
        InMemoryRunStepOutputRepository outputs = new InMemoryRunStepOutputRepository();
        Fixture fixture = new Fixture(true, outputs, codec, StructuredExecutor.full(), true, checkpoints);
        try {
            RunView run = fixture.create();
            assertEquals(AgentEventType.RUN_COMPLETED,
                    RunTestAwait.awaitTerminalEvent(fixture.events, run.runId()).type());
            assertTrue(codec.encodeCalls > 0 && codec.decodeCalls > 0);
            assertFalse(codec.calledInsideFence, "codec must remain outside lease fence");
        } finally {
            fixture.close();
        }
    }

    @Test
    void repositorySaveFailureFailsClosedBeforeAnswerPublication() {
        InMemoryRunStepOutputRepository delegate = new InMemoryRunStepOutputRepository();
        RunStepOutputRepository failing = new DelegatingRepository(delegate) {
            @Override
            public RunStepOutput save(RunStepOutput output) {
                throw new IllegalStateException("storage unavailable");
            }
        };
        AesGcmCodec codec = new AesGcmCodec();
        Fixture fixture = new Fixture(true, failing, codec, StructuredExecutor.full(), true);
        try {
            RunView run = fixture.create();
            assertEquals(AgentEventType.RUN_FAILED,
                    RunTestAwait.awaitTerminalEvent(fixture.events, run.runId()).type());
            assertNoAnswerFacts(fixture, run);
        } finally {
            fixture.close();
        }
    }

    @Test
    void durableRepositoryWithoutCodecFailsClosedBeforePublishingAnswer() {
        InMemoryRunStepOutputRepository outputs = new InMemoryRunStepOutputRepository();
        Fixture fixture = new Fixture(true, outputs, null);
        try {
            RunView run = fixture.create();
            AgentEvent terminal = RunTestAwait.awaitTerminalEvent(fixture.events, run.runId());
            assertEquals(AgentEventType.RUN_FAILED, terminal.type());
            assertTrue(outputs.find(run.runId(), 1).isEmpty());
            assertNoAnswerFacts(fixture, run);
        } finally {
            fixture.close();
        }
    }

    @Test
    void disabledDurableResumeDoesNotWriteArtifact() {
        InMemoryRunStepOutputRepository outputs = new InMemoryRunStepOutputRepository();
        Fixture fixture = new Fixture(false, outputs, new AesGcmCodec());
        try {
            RunView run = fixture.create();
            assertEquals(AgentEventType.RUN_COMPLETED,
                    RunTestAwait.awaitTerminalEvent(fixture.events, run.runId()).type());
            assertTrue(outputs.find(run.runId(), 1).isEmpty());
        } finally {
            fixture.close();
        }
    }

    @Test
    void repositoryKeepsPersistedTimestampsAsImmutableFacts() {
        InMemoryRunStepOutputRepository outputs = new InMemoryRunStepOutputRepository();
        RunStepOutput first = new RunStepOutput("run-1", 1, "executor", "a".repeat(64),
                "b".repeat(64), "cipher", "kms-v7",
                java.time.Instant.parse("2026-10-14T00:00:00Z"),
                java.time.Instant.parse("2026-10-15T00:00:00Z"));
        RunStepOutput sameContent = new RunStepOutput("run-1", 1, "executor", "a".repeat(64),
                "b".repeat(64), "cipher", "kms-v7",
                java.time.Instant.parse("2026-10-14T00:00:01Z"),
                java.time.Instant.parse("2026-10-15T00:00:01Z"));
        assertSame(first, outputs.save(first));
        assertEquals(first, outputs.find("run-1", 1).orElseThrow());
        assertNotEquals(first.createdAt(), sameContent.createdAt());
        assertThrows(RunStepOutputConflictException.class, () -> outputs.save(sameContent));
    }

    private void assertTamperFails(UnaryOperator<RunStepOutput> tamper) {
        TamperRepository outputs = new TamperRepository(tamper);
        Fixture fixture = new Fixture(true, outputs, new AesGcmCodec(), StructuredExecutor.full(), true);
        try {
            RunView run = fixture.create();
            assertEquals(AgentEventType.RUN_FAILED,
                    RunTestAwait.awaitTerminalEvent(fixture.events, run.runId()).type());
            assertNoAnswerFacts(fixture, run);
        } finally {
            fixture.close();
        }
    }

    private static void assertNoAnswerFacts(Fixture fixture, RunView run) {
        assertTrue(fixture.events.findAfter(run.runId(), 0).stream()
                        .noneMatch(event -> event.type() == AgentEventType.ANSWER_DELTA
                                || event.type() == AgentEventType.ANSWER_COMPLETED
                                || event.type() == AgentEventType.RUN_COMPLETED),
                "失败的 artifact 不得发布答案或完成事件");
        assertTrue(fixture.messages.findByConversationForUser(run.conversationId(), "user-1").stream()
                        .noneMatch(message -> "assistant".equals(message.role())));
        assertTrue(fixture.projections.findByRunId(run.runId()).isEmpty());
    }

    private static String corruptCiphertext(String encoded) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
            if (bytes.length < 2) return "corrupt";
            bytes[bytes.length - 1] ^= 0x01;
            return java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (IllegalArgumentException ex) {
            return "corrupt";
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final InMemoryEventRepository events = new InMemoryEventRepository();
        private final InMemoryRunRepository runs = new InMemoryRunRepository();
        private final InMemoryConversationRepository conversations = new InMemoryConversationRepository();
        private final InMemoryChatMessageRepository messages = new InMemoryChatMessageRepository();
        private final InMemoryAnswerProjectionRepository projections = new InMemoryAnswerProjectionRepository();
        private final CheckpointRepository checkpoints;
        private final RunExecutor executor;
        private final boolean completeLineage;
        private final PokemonAgentRunService service;

        private Fixture(boolean durable, RunStepOutputRepository outputs, ModelInvocationCacheCodec codec) {
            this(durable, outputs, codec, context -> "答案", true);
        }

        private Fixture(boolean durable, RunStepOutputRepository outputs, ModelInvocationCacheCodec codec,
                        RunExecutor executor, boolean completeLineage) {
            this(durable, outputs, codec, executor, completeLineage, new InMemoryDurableRunStateRepository());
        }

        private Fixture(boolean durable, RunStepOutputRepository outputs, ModelInvocationCacheCodec codec,
                        RunExecutor executor, boolean completeLineage, CheckpointRepository checkpoints) {
            this.executor = executor;
            this.completeLineage = completeLineage;
            this.checkpoints = checkpoints;
            DurableRunProperties durableProperties = new DurableRunProperties();
            durableProperties.setDurableResume(durable);
            RunAdmissionProperties admission = new RunAdmissionProperties();
            admission.setThreadCount(1);
            admission.setQueueCapacity(1);
            service = new PokemonAgentRunService(runs, events, conversations, messages,
                    provider(RunExecutor.class, executor), provider(AgentMetrics.class, null),
                    provider(com.vio.vioaiagent.security.HitlManager.class, null),
                    provider(RunOutboxRepository.class, null), provider(CheckpointRepository.class, checkpoints),
                    provider(DurableRunProperties.class, durableProperties), provider(RunAdmissionProperties.class, admission));
            service.setRunStepOutputRepository(outputs);
            service.setRunStepOutputCodec(codec);
            service.setAnswerProjectionRepository(projections);
        }

        private RunView create() {
            return service.createRun(new CreateRunCommand("user-1", "session-1", null,
                    "查询问题", "sv-1", "singles", "rules-1", "zh-CN", "dlc-1",
                    completeLineage ? "data-1" : null,
                    completeLineage ? "knowledge-1" : null,
                    completeLineage ? "index-1" : null,
                    "v1.1", "trace-1", null, Duration.ofSeconds(10)));
        }

        @Override public void close() { service.shutdown(); }
    }

    private static final class StructuredExecutor implements ContractAwareRunExecutor {
        private final AnswerContract contract;
        private final AnswerLineage lineage;

        private StructuredExecutor(AnswerContract contract, AnswerLineage lineage) {
            this.contract = contract;
            this.lineage = lineage;
        }

        private static StructuredExecutor full() {
            Citation citation = new Citation("doc-1", "doc-v1", "规则来源", "https://example.test/doc-1",
                    "chunk-1", "证据文本", 0.98);
            return new StructuredExecutor(AnswerContract.answered("结构化答案", "sv-1", "singles", "rules-1",
                    List.of(citation), List.of("仅供测试"), "trace-structured"),
                    new AnswerLineage("dlc-structured", "data-structured", "knowledge-structured",
                            "index-structured", "v1.1"));
        }

        private static StructuredExecutor partial() {
            return new StructuredExecutor(AnswerContract.answered("部分血缘答案", "sv-1", "singles", "rules-1",
                    List.of(), List.of("缺少发布版本"), "trace-partial"),
                    new AnswerLineage("dlc-partial", null, null, null, "v1.1"));
        }

        private static StructuredExecutor differentAnswer() {
            StructuredExecutor full = full();
            return new StructuredExecutor(new AnswerContract("另一个答案", full.contract.applicableGameVersion(),
                    full.contract.battleFormat(), full.contract.rulesetVersion(), full.contract.citations(),
                    full.contract.limitations(), full.contract.traceId(), full.contract.status()), full.lineage);
        }

        @Override public AnswerContract executeContract(AgentRunContext context) { return contract; }
        @Override public String execute(AgentRunContext context) { return contract.answer(); }
        @Override public boolean usesKnowledgeGrounding() { return false; }
        @Override public AnswerLineage answerLineage(AgentRunContext context, AnswerContract ignored) { return lineage; }
    }

    private abstract static class DelegatingRepository implements RunStepOutputRepository {
        protected final RunStepOutputRepository delegate;

        private DelegatingRepository(RunStepOutputRepository delegate) { this.delegate = delegate; }
        @Override public Optional<RunStepOutput> find(String runId, int stepNo) { return delegate.find(runId, stepNo); }
        @Override public int deleteExpired(java.time.Instant now, int limit) { return delegate.deleteExpired(now, limit); }
    }

    private static final class TamperRepository extends DelegatingRepository {
        private final UnaryOperator<RunStepOutput> tamper;

        private TamperRepository(UnaryOperator<RunStepOutput> tamper) {
            super(new InMemoryRunStepOutputRepository());
            this.tamper = tamper;
        }

        @Override public RunStepOutput save(RunStepOutput output) { return delegate.save(tamper.apply(output)); }
    }

    private static final class ReplayRepository extends DelegatingRepository {
        private final AesGcmCodec codec;
        private PokemonAgentRunService service;
        private StructuredExecutor executor;
        private boolean replayed;
        private int saveCalls;
        private String firstCiphertext;
        private boolean nonceWasRandom;
        private RunStepOutput stored;

        private ReplayRepository(AesGcmCodec codec) {
            super(new InMemoryRunStepOutputRepository());
            this.codec = codec;
        }

        private void configureReplay(PokemonAgentRunService service, StructuredExecutor executor) {
            this.service = service;
            this.executor = executor;
        }

        @Override public RunStepOutput save(RunStepOutput output) {
            if (stored != null) throw new RunStepOutputConflictException();
            stored = output;
            saveCalls++;
            if (firstCiphertext == null) firstCiphertext = output.encryptedPayload();
            if (!replayed && service != null) {
                replayed = true;
                // The first artifact was encoded with the historical key. Rotate the
                // codec before the replay candidate is created; the stored row itself
                // remains byte-for-byte immutable at kms-v6.
                codec.rotateToV7();
                invokeSaveStepOutput(output.runId(), executor);
            }
            nonceWasRandom = codec.randomNonceObserved();
            return output;
        }

        @Override public Optional<RunStepOutput> find(String runId, int stepNo) {
            return stored != null && stored.runId().equals(runId) && stored.stepNo() == stepNo
                    ? Optional.of(stored) : Optional.empty();
        }

        private void invokeSaveStepOutput(String runId, StructuredExecutor executor) {
            try {
                java.lang.reflect.Field runsField = PokemonAgentRunService.class.getDeclaredField("runRepository");
                runsField.setAccessible(true);
                AgentRun run = ((AgentRunRepository) runsField.get(service)).findById(runId).orElseThrow();
                Class<?> executionType = java.util.Arrays.stream(PokemonAgentRunService.class.getDeclaredClasses())
                        .filter(type -> type.getSimpleName().equals("AnswerExecution"))
                        .findFirst().orElseThrow();
                Constructor<?> constructor = executionType.getDeclaredConstructor(String.class, AnswerContract.class);
                constructor.setAccessible(true);
                Object execution = constructor.newInstance(executor.contract.answer(), executor.contract);
                Method method = PokemonAgentRunService.class.getDeclaredMethod("saveStepOutput", AgentRun.class,
                        int.class, executionType, AnswerLineage.class);
                method.setAccessible(true);
                method.invoke(service, run, 1, execution, executor.lineage);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(cause);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("无法触发 service artifact replay", ex);
            }
        }
    }

    private static class AesGcmCodec implements ModelInvocationCacheCodec {
        private static final int NONCE_BYTES = 12;
        private String activeKeyVersion;
        private final java.util.ArrayList<String> ciphertexts = new java.util.ArrayList<>();

        protected AesGcmCodec() { this("kms-v7"); }

        protected AesGcmCodec(String activeKeyVersion) {
            this.activeKeyVersion = activeKeyVersion;
        }

        @Override public String encode(String response) {
            try {
                byte[] nonce = new byte[NONCE_BYTES];
                new SecureRandom().nextBytes(nonce);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, keyFor(activeKeyVersion), new GCMParameterSpec(128, nonce));
                byte[] ciphertext = cipher.doFinal(response.getBytes(StandardCharsets.UTF_8));
                byte[] envelope = new byte[nonce.length + ciphertext.length];
                System.arraycopy(nonce, 0, envelope, 0, nonce.length);
                System.arraycopy(ciphertext, 0, envelope, nonce.length, ciphertext.length);
                String encoded = java.util.Base64.getEncoder().encodeToString(envelope);
                ciphertexts.add(encoded);
                return encoded;
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("AES-GCM encode failed", ex);
            }
        }

        @Override public String decode(String encodedResponse) { return decode(encodedResponse, activeKeyVersion); }

        @Override public String decode(String encodedResponse, String keyVersion) {
            assertTrue("kms-v7".equals(keyVersion) || "kms-v6".equals(keyVersion),
                    "codec must retain historical AES-GCM key versions");
            try {
                byte[] envelope = java.util.Base64.getDecoder().decode(encodedResponse);
                if (envelope.length <= NONCE_BYTES) throw new IllegalArgumentException("短 AES-GCM artifact");
                byte[] nonce = java.util.Arrays.copyOf(envelope, NONCE_BYTES);
                byte[] ciphertext = java.util.Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, keyFor(keyVersion), new GCMParameterSpec(128, nonce));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                throw new IllegalStateException("AES-GCM decode failed", ex);
            }
        }

        @Override public String keyVersion() { return activeKeyVersion; }

        private void rotateToV7() { activeKeyVersion = "kms-v7"; }

        private boolean randomNonceObserved() {
            return ciphertexts.size() >= 2 && !ciphertexts.get(0).equals(ciphertexts.get(1));
        }

        private static SecretKeySpec keyFor(String keyVersion) {
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256")
                        .digest(("r07-test-only-managed-kms-key:" + keyVersion)
                                .getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(bytes, "AES");
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static final class RecordingCheckpoint extends InMemoryDurableRunStateRepository {
        private volatile boolean insideFence;

        @Override
        public synchronized <T> T withLease(String runId, String owner, String token,
                                            java.util.function.Supplier<T> action) {
            insideFence = true;
            try {
                return super.withLease(runId, owner, token, action);
            } finally {
                insideFence = false;
            }
        }
    }

    private static final class FenceAwareCodec extends AesGcmCodec {
        private final RecordingCheckpoint checkpoint;
        private volatile boolean calledInsideFence;
        private volatile int encodeCalls;
        private volatile int decodeCalls;

        private FenceAwareCodec(RecordingCheckpoint checkpoint) {
            this.checkpoint = checkpoint;
        }

        @Override public String encode(String response) {
            encodeCalls++;
            calledInsideFence |= checkpoint.insideFence;
            return super.encode(response);
        }

        @Override public String decode(String encodedResponse, String keyVersion) {
            decodeCalls++;
            calledInsideFence |= checkpoint.insideFence;
            return super.decode(encodedResponse, keyVersion);
        }
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T instance) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (instance != null) factory.addBean("fixture", instance);
        return factory.getBeanProvider(type);
    }
}
