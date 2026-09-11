package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 资源列表游标签名与有效期；生产环境必须显式注入 secret。 */
@ConfigurationProperties(prefix = "vio.resource.cursor")
public class ResourceCursorProperties {
    private String secret = "";
    private long ttlSeconds = 900;

    public String secret() { return secret == null ? "" : secret.trim(); }
    public void setSecret(String value) { this.secret = value == null ? "" : value.trim(); }
    public long ttlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long value) { this.ttlSeconds = Math.max(1, Math.min(value, 604800)); }
}
