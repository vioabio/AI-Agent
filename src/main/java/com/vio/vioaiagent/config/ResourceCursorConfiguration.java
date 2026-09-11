package com.vio.vioaiagent.config;

import com.vio.vioaiagent.run.ResourceCursorCodec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/** 将资源游标配置转换为签名编码器；本地安全关闭时使用工作区绑定的开发密钥。 */
@Configuration
@EnableConfigurationProperties(ResourceCursorProperties.class)
public class ResourceCursorConfiguration {
    @Bean
    public ResourceCursorCodec resourceCursorCodec(ResourceCursorProperties properties,
                                                    SecurityProperties securityProperties) {
        String secret = properties.secret();
        if (secret.isBlank()) {
            if (securityProperties != null && securityProperties.isEnabled()) {
                throw new IllegalStateException("生产环境必须配置 VIO_RESOURCE_CURSOR_SECRET");
            }
            // 仅用于 local/test，绑定工作目录，避免提交一个可用于生产的固定密钥。
            secret = localDevelopmentSecret();
        }
        return new ResourceCursorCodec(secret, Duration.ofSeconds(properties.ttlSeconds()));
    }

    private String localDevelopmentSecret() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("vio-local-resource-cursor:" + System.getProperty("user.dir", "local"))
                            .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("无法初始化本地资源游标密钥", ex);
        }
    }
}
