package com.vio.vioaiagent.run;

import com.vio.vioaiagent.config.ModelInvocationCacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * N2 可重放模型步骤的缓存编排器。
 *
 * <p>它只缓存明确标记为可重放的模型步骤：请求哈希包含 Run 的冻结上下文、
 * 模型版本、Prompt 版本和规范化后的 grounded prompt；命中时校验密文解码结果
 * 的 SHA-256 摘要，避免损坏或被篡改的缓存静默污染回答。缓存默认关闭，且没有
 * 受控 codec 时不会把明文写入 {@code model_invocation_cache}。</p>
 *
 * <p>同一 JVM 内使用 key lock 防止并发 miss 重复调用模型。跨实例的 exactly-once
 * 仍由外部缓存/数据库和后续 ADR 约束，本类不虚构跨实例锁能力。</p>
 */
@Service
@Slf4j
public class ModelInvocationCacheService {
    private final ModelInvocationCacheRepository repository;
    private final ModelInvocationCacheCodec codec;
    private final ModelInvocationCacheProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, InvocationLock> invocationLocks = new ConcurrentHashMap<>();

    /** Spring 装配：仓储和 codec 都是可选的，避免本地无 KMS 时阻断启动。 */
    @Autowired
    public ModelInvocationCacheService(
            ObjectProvider<ModelInvocationCacheRepository> repositoryProvider,
            ObjectProvider<ModelInvocationCacheCodec> codecProvider,
            ModelInvocationCacheProperties properties) {
        this(repositoryProvider == null ? null : repositoryProvider.getIfAvailable(),
                codecProvider == null ? null : codecProvider.getIfAvailable(),
                properties, Clock.systemUTC());
    }

    /** 测试/嵌入式构造器；允许使用脱敏内存 codec 验证缓存语义。 */
    ModelInvocationCacheService(ModelInvocationCacheRepository repository,
                                 ModelInvocationCacheCodec codec,
                                 ModelInvocationCacheProperties properties,
                                 Clock clock) {
        this.repository = repository;
        this.codec = codec;
        this.properties = Objects.requireNonNull(properties, "model cache properties");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 执行一个可重放模型步骤。
     *
     * @param run 当前 Run（其 ID 和版本上下文参与 request hash）
     * @param groundedPrompt 已完成证据拼接且不会再变化的 Prompt
     * @param generator 缓存未命中时调用的模型/Agent 生成函数
     * @return 缓存或模型生成的回答；空回答不会写缓存
     */
    public String invoke(AgentRun run, String groundedPrompt, Function<String, String> generator) {
        return invoke(run, properties.stepNo(), groundedPrompt, generator);
    }

    /** 显式指定步骤号的重载，供未来多个可重放模型步骤复用。 */
    public String invoke(AgentRun run, int stepNo, String groundedPrompt,
                         Function<String, String> generator) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(generator, "generator");
        validateStepNo(stepNo);
        if (!properties.enabled()) return generator.apply(groundedPrompt);

        String modelVersion = properties.modelVersion();
        String promptVersion = properties.promptVersion();
        if (repository == null || codec == null || modelVersion.isBlank() || promptVersion.isBlank()) {
            return unavailableOrBypass("缓存已开启但仓储、codec 或版本配置不完整",
                    groundedPrompt, generator);
        }
        String requestHash = requestHash(run, stepNo, modelVersion, promptVersion, groundedPrompt);
        String invocationKey = lockKey(run.id(), stepNo, requestHash);
        // A plain computeIfAbsent/remove pair has a waiter gap: a waiting
        // caller may hold the old monitor while the first caller removes it,
        // allowing a newcomer to create a second monitor and invoke the model
        // concurrently. Count all holders and remove only after the last one.
        InvocationLock lock = invocationLocks.compute(invocationKey, (ignored, current) -> {
            InvocationLock value = current == null ? new InvocationLock() : current;
            value.references++;
            return value;
        });
        try {
            synchronized (lock) {
                Instant now = clock.instant();
                Optional<ModelInvocationCacheEntry> cached;
                try {
                    cached = repository.findValid(run.id(), stepNo, requestHash, now);
                } catch (RuntimeException ex) {
                    return unavailableOrThrow("读取模型调用缓存失败", ex, groundedPrompt, generator);
                }
                if (cached.isPresent() && !cached.get().expired(now)) {
                    return decodeAndVerify(cached.get(), run, stepNo, requestHash,
                            modelVersion, promptVersion);
                }

                String response = generator.apply(groundedPrompt);
                if (response == null || response.isBlank()) return response;

                final String encoded;
                try {
                    encoded = normalizeEncoded(codec.encode(response));
                } catch (RuntimeException ex) {
                    return unavailableOrThrow("模型响应编码失败", ex, response, ignored -> response);
                }
                if (encoded.isBlank()) {
                    return unavailableOrThrow("模型响应编码为空", null, response, ignored -> response);
                }
                ModelInvocationCacheEntry entry = new ModelInvocationCacheEntry(
                        run.id(), stepNo, requestHash, modelVersion, promptVersion,
                        sha256(response), encoded, null, Map.of(),
                        normalizeKeyVersion(codec.keyVersion()), now,
                        now.plusSeconds(properties.ttlSeconds()));
                try {
                    repository.save(entry);
                } catch (RuntimeException ex) {
                    return unavailableOrThrow("写入模型调用缓存失败", ex, response, ignored -> response);
                }
                return response;
            }
        } finally {
            invocationLocks.computeIfPresent(invocationKey, (ignored, current) -> {
                if (current != lock) return current;
                current.references--;
                return current.references == 0 ? null : current;
            });
        }
    }

    /**
     * Read-only lookup used by recovery. A miss, expiry, or a disabled cache is
     * represented by {@link Optional#empty()}; this method never accepts a model
     * generator and never writes or deletes cache state.
     *
     * <p>Recovery is fail-closed even when normal invocation is configured to
     * bypass cache failures: an incomplete, unreadable, undecodable, or tampered
     * entry must not cause a model call or an implicit cache rewrite.</p>
     */
    public Optional<String> lookupForRecovery(AgentRun run, String groundedPrompt) {
        return lookupForRecovery(run, properties.stepNo(), groundedPrompt);
    }

    /** Read-only recovery lookup for an explicitly selected replay step. */
    public Optional<String> lookupForRecovery(AgentRun run, int stepNo, String groundedPrompt) {
        Objects.requireNonNull(run, "run");
        validateStepNo(stepNo);
        if (!properties.enabled()) return Optional.empty();

        String modelVersion = properties.modelVersion();
        String promptVersion = properties.promptVersion();
        if (repository == null || codec == null || modelVersion.isBlank() || promptVersion.isBlank()) {
            throw new ModelInvocationCacheException("恢复缓存配置不完整");
        }

        String requestHash = requestHash(run, stepNo, modelVersion, promptVersion, groundedPrompt);
        Instant now = clock.instant();
        final Optional<ModelInvocationCacheEntry> cached;
        try {
            cached = repository.findValid(run.id(), stepNo, requestHash, now);
        } catch (RuntimeException ex) {
            throw new ModelInvocationCacheException("读取恢复缓存失败", ex);
        }
        if (cached.isEmpty()) return Optional.empty();
        // Keep recovery fail-closed even if a custom repository returns a stale
        // row despite the findValid contract.
        if (cached.get().expired(now)) return Optional.empty();
        return Optional.of(decodeAndVerify(cached.get(), run, stepNo, requestHash,
                modelVersion, promptVersion));
    }

    /** 便于健康检查/诊断，不泄漏仓储、密钥或模型提示词。 */
    public CacheReadiness readiness() {
        if (!properties.enabled()) return new CacheReadiness(false, true, "disabled");
        boolean ready = repository != null && codec != null
                && !properties.modelVersion().isBlank() && !properties.promptVersion().isBlank();
        return new CacheReadiness(true, ready, ready ? "ready" : "configuration-incomplete");
    }

    private String decodeAndVerify(ModelInvocationCacheEntry entry, AgentRun run, int stepNo,
                                   String requestHash, String modelVersion, String promptVersion) {
        if (entry == null || !run.id().equals(entry.runId()) || stepNo != entry.stepNo()
                || !requestHash.equals(entry.requestHash())
                || !modelVersion.equals(entry.modelVersion())
                || !promptVersion.equals(entry.promptVersion())
                || entry.encryptedResponse() == null || entry.encryptedResponse().isBlank()) {
            throw new ModelInvocationCacheException("模型调用缓存元数据不匹配");
        }
        final String decoded;
        try {
            decoded = codec.decode(entry.encryptedResponse(), normalizeKeyVersion(entry.keyVersion()));
        } catch (RuntimeException ex) {
            throw new ModelInvocationCacheException("模型调用缓存解码失败", ex);
        }
        if (decoded == null || decoded.isBlank() || !sha256(decoded).equalsIgnoreCase(entry.responseDigest())) {
            throw new ModelInvocationCacheException("模型调用缓存摘要校验失败");
        }
        return decoded;
    }

    private void validateStepNo(int stepNo) {
        if (stepNo < 0) throw new IllegalArgumentException("stepNo 不能为负数");
    }

    private String unavailableOrBypass(String reason, String prompt, Function<String, String> generator) {
        if (properties.failClosed()) throw new ModelInvocationCacheException(reason);
        log.warn("model invocation cache bypassed: reason={}", reason);
        return generator.apply(prompt);
    }

    private String unavailableOrThrow(String reason, RuntimeException cause,
                                      String prompt, Function<String, String> generator) {
        if (properties.failClosed()) {
            throw cause == null ? new ModelInvocationCacheException(reason)
                    : new ModelInvocationCacheException(reason, cause);
        }
        log.warn("model invocation cache bypassed: reason={}", reason);
        return generator.apply(prompt);
    }

    private String requestHash(AgentRun run, int stepNo, String modelVersion,
                               String promptVersion, String prompt) {
        String canonical = String.join("\u001f",
                normalize(run.id()), Integer.toString(stepNo), normalize(modelVersion),
                normalize(promptVersion), normalize(run.gameReleaseId()), normalize(run.battleFormat()),
                normalize(run.rulesetVersionId()), normalize(run.language()), normalize(run.dlcId()),
                normalize(run.dataReleaseId()), normalize(run.knowledgeReleaseId()),
                normalize(run.indexReleaseId()), normalize(prompt));
        return sha256(canonical);
    }

    private String lockKey(String runId, int stepNo, String requestHash) {
        return normalize(runId) + "#" + stepNo + "#" + requestHash;
    }

    private String normalizeEncoded(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeKeyVersion(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "managed" : normalized;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String sha256(String value) {
        try {
            // 请求字段在调用前由 normalize() 规范化；响应摘要保留首尾空白，
            // 这样缓存完整性校验不会把被篡改的正文误判为原始输出。
            String canonical = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    /** Mutable only inside the per-key CHM compute callback. */
    private static final class InvocationLock {
        private int references;
    }

    public record CacheReadiness(boolean enabled, boolean ready, String status) { }
}
