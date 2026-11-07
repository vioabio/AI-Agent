package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Compatibility switch for the legacy in-memory RAG store.
 *
 * <p>The switch does <strong>not</strong> make the legacy store a production
 * index. It only permits the local compatibility bean to return an empty
 * {@code SimpleVectorStore} after an embedding/ETL initialization failure.
 * The default is deliberately fail-closed.</p>
 */
@ConfigurationProperties(prefix = "vio.rag.legacy-memory")
public class LegacyMemoryRagProperties {

    /**
     * Allow an empty in-memory vector store after initialization failure.
     * Keep false for normal local and all production-like environments.
     */
    private boolean enabled;

    public boolean enabled() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
