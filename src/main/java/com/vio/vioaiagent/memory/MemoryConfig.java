package com.vio.vioaiagent.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 记忆系统 Spring 配置。
 *
 * @author vio
 */
@Configuration
public class MemoryConfig {

    @Bean
    @ConfigurationProperties(prefix = "vio.memory")
    public MemoryProperties memoryProperties() {
        return new MemoryProperties();
    }

    @Bean
    public TieredMemorySystem tieredMemorySystem() {
        return new TieredMemorySystem();
    }

    @Getter @Setter
    public static class MemoryProperties {
        /** 是否启用记忆系统（默认 false） */
        private boolean enabled = false;
        /** 短期记忆最大 Token 数 */
        private int shortTermMaxTokens = 4000;
        /** 检索返回的最大条数 */
        private int recallTopK = 5;
    }
}
