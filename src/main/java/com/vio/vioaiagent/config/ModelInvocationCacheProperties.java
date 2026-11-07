package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * N2 模型调用缓存策略。
 *
 * <p>缓存默认关闭。开启时必须同时提供明确的模型/Prompt 版本以及受控的
 * {@code ModelInvocationCacheCodec}，否则调用会 fail-closed，避免把明文回答
 * 当作“已加密缓存”写入数据库。</p>
 */
@ConfigurationProperties(prefix = "vio.run.model-cache")
public class ModelInvocationCacheProperties {
    private boolean enabled;
    private boolean failClosed = true;
    private int stepNo = 1;
    private long ttlSeconds = 3600;
    private String modelVersion = "";
    private String promptVersion = "v1.1";

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean failClosed() {
        return failClosed;
    }

    public void setFailClosed(boolean failClosed) {
        this.failClosed = failClosed;
    }

    public int stepNo() {
        return stepNo;
    }

    public void setStepNo(int stepNo) {
        this.stepNo = Math.max(0, Math.min(stepNo, 10_000));
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = Math.max(60L, Math.min(ttlSeconds, 31_536_000L));
    }

    public String modelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = normalize(modelVersion);
    }

    public String promptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = normalize(promptVersion);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
