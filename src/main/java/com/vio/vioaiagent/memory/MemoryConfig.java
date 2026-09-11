package com.vio.vioaiagent.memory;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    @ConditionalOnProperty(prefix = "vio.memory", name = "enabled", havingValue = "true")
    public TieredMemorySystem tieredMemorySystem() {
        return new TieredMemorySystem();
    }

    /**
     * 用户级长期记忆的加密边界。启用记忆时必须显式提供密钥，避免应用
     * 在缺少密钥的情况下静默保存明文或使用可猜测默认值。
     */
    @Bean
    @ConditionalOnProperty(prefix = "vio.memory", name = "enabled", havingValue = "true")
    public MemoryCipher userMemoryCipher(MemoryProperties properties) {
        return AesGcmMemoryCipher.fromConfigured(properties.getEncryptionKey(), properties.getKeyVersion());
    }

    @Bean
    @ConditionalOnProperty(prefix = "vio.memory", name = "enabled", havingValue = "true")
    public UserMemoryService userMemoryService(UserMemoryRepository repository,
                                               MemoryCipher cipher,
                                               MemoryProperties properties) {
        return new UserMemoryService(repository, cipher, properties.getDefaultTtlDays());
    }

    @Getter @Setter
    public static class MemoryProperties {
        /** 是否启用记忆系统（默认 false） */
        private boolean enabled = false;
        /** 短期记忆最大 Token 数 */
        private int shortTermMaxTokens = 4000;
        /** 检索返回的最大条数 */
        private int recallTopK = 5;
        /** AES-GCM 密钥（Base64 或原始 16/24/32 字节字符串）；不得写入仓库。 */
        private String encryptionKey = "";
        /** 记忆密钥版本，用于轮换时区分旧密文。 */
        private String keyVersion = "v1";
        /** 未显式指定 TTL 时的默认有效期。 */
        private int defaultTtlDays = 30;
    }
}
