package com.vio.vioaiagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 * @param trustedProxyCidrs 允许提供转发客户端 IP 的可信代理 IP/CIDR；空列表表示不信任任何转发头
 * @author vio
 */
@ConfigurationProperties(prefix = "vio.security")
public record SecurityProperties(
        boolean enabled,
        Map<String, String> apiKeys,
        List<String> allowedPaths,
        List<String> blockedCommands,
        List<String> trustedProxyCidrs) {

    /**
     * 多构造器 record 必须显式声明配置绑定构造器。否则 Spring Boot 会将
     * {@code @ConfigurationProperties} 当作 JavaBean 绑定，并尝试调用不存在
     * 的无参构造器，导致完整应用上下文无法启动。
     */
    @ConstructorBinding
    public SecurityProperties {
        apiKeys = apiKeys == null ? Collections.emptyMap() : Map.copyOf(apiKeys);
        allowedPaths = allowedPaths == null ? List.of() : List.copyOf(allowedPaths);
        blockedCommands = blockedCommands == null ? List.of() : List.copyOf(blockedCommands);
        trustedProxyCidrs = trustedProxyCidrs == null ? List.of() : List.copyOf(trustedProxyCidrs);
    }

    /** 保留旧代码与测试使用的四参数构造器；默认不信任任何转发头。 */
    public SecurityProperties(boolean enabled,
                              Map<String, String> apiKeys,
                              List<String> allowedPaths,
                              List<String> blockedCommands) {
        this(enabled, apiKeys, allowedPaths, blockedCommands, Collections.emptyList());
    }

    /** 默认安全配置（安全模块关闭, 所有防护不生效） */
    public static SecurityProperties defaults() {
        return new SecurityProperties(
                false,
                Collections.emptyMap(),
                List.of(System.getProperty("user.dir") + "/tmp"),
                Collections.emptyList(),
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

    /** 获取可信反向代理 CIDR；为空时所有转发头都按不可信输入处理。 */
    public List<String> trustedProxyCidrs() {
        return trustedProxyCidrs != null
                ? Collections.unmodifiableList(trustedProxyCidrs)
                : Collections.emptyList();
    }
}
