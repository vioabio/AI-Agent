package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 安全配置属性 — 类型安全的 YAML 配置绑定.
 *
 * <p>对应 application.yml 中的 {@code vio.security} 配置段.
 * 使用 Java 21 Record 实现不可变配置对象.
 *
 * @param enabled         是否启用安全模块（默认 false, 不破坏现有行为）
 * @param apiKeys         API Key 列表（格式: key → "描述" 或 "描述:协议ID"）
 * @param allowedPaths    文件操作白名单路径
 * @param blockedCommands 禁止执行的命令正则表达式列表
 * @author vio
 */
@ConfigurationProperties(prefix = "vio.security")
public record SecurityProperties(
        boolean enabled,
        Map<String, String> apiKeys,
        List<String> allowedPaths,
        List<String> blockedCommands) {

    /** 默认安全配置（安全模块关闭, 所有防护不生效） */
    public static SecurityProperties defaults() {
        return new SecurityProperties(
                false,
                Collections.emptyMap(),
                List.of(System.getProperty("user.dir") + "/tmp"),
                Collections.emptyList()
        );
    }

    /** 安全模块是否已启用 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 获取 API Key 列表（不可变） */
    public Map<String, String> apiKeys() {
        return apiKeys != null ? Collections.unmodifiableMap(apiKeys) : Collections.emptyMap();
    }

    /** 获取允许的文件路径列表（不可变） */
    public List<String> allowedPaths() {
        return allowedPaths != null ? Collections.unmodifiableList(allowedPaths) : Collections.emptyList();
    }

    /** 获取禁止的命令模式列表（不可变） */
    public List<String> blockedCommands() {
        return blockedCommands != null ? Collections.unmodifiableList(blockedCommands) : Collections.emptyList();
    }
}
