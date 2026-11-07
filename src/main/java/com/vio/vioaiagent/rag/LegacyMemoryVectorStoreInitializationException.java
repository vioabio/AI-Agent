package com.vio.vioaiagent.rag;

/**
 * Structured fail-closed error for the legacy in-memory RAG bootstrap.
 *
 * <p>The exception intentionally keeps only a safe failure type and phase in
 * its message. Provider exception messages can contain request details or
 * credential material, so they must not be copied into application logs or
 * HTTP responses.</p>
 */
public final class LegacyMemoryVectorStoreInitializationException extends IllegalStateException {

    public static final String CODE = "RAG_LEGACY_MEMORY_INIT_FAILED";

    private final String phase;
    private final String failureType;

    public LegacyMemoryVectorStoreInitializationException(String phase, Throwable failure) {
        super(buildMessage(phase, failure));
        this.phase = normalize(phase);
        this.failureType = failure == null
                ? "UnknownFailure"
                : failure.getClass().getSimpleName();
    }

    public String code() {
        return CODE;
    }

    public String phase() {
        return phase;
    }

    public String failureType() {
        return failureType;
    }

    private static String buildMessage(String phase, Throwable failure) {
        String safePhase = normalize(phase);
        String safeType = failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
        return CODE + " phase=" + safePhase + " failureType=" + safeType
                + "; legacy empty-vector fallback is disabled; "
                + "set vio.rag.legacy-memory.enabled=true only for an explicit local/test degraded run";
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
