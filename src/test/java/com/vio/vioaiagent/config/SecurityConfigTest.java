package com.vio.vioaiagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SecurityProperties 配置绑定测试.
 *
 * <p>不使用 @SpringBootTest, 直接构造 SecurityProperties 验证默认值和字段.
 *
 * @author vio
 */
@DisplayName("安全配置属性")
class SecurityConfigTest {

    @Test
    @DisplayName("默认构造应禁用安全模块")
    void shouldDefaultToDisabled() {
        SecurityProperties props = SecurityProperties.defaults();

        assertFalse(props.isEnabled());
        assertTrue(props.apiKeys().isEmpty());
        assertTrue(props.blockedCommands().isEmpty());
    }

    @Test
    @DisplayName("启用安全模块后 apiKeys 应可配置")
    void shouldAcceptApiKeys() {
        SecurityProperties props = new SecurityProperties(
                true,
                Map.of("sk-001", "管理令牌:default", "sk-002", "只读令牌:image-search"),
                java.util.List.of("/tmp"),
                java.util.List.of()
        );

        assertTrue(props.isEnabled());
        assertEquals(2, props.apiKeys().size());
        assertEquals("管理令牌:default", props.apiKeys().get("sk-001"));
    }

    @Test
    @DisplayName("allowedPaths 应正确存储")
    void shouldStoreAllowedPaths() {
        SecurityProperties props = new SecurityProperties(
                true, Map.of(),
                java.util.List.of("/tmp", "/var/log"),
                java.util.List.of()
        );

        assertEquals(2, props.allowedPaths().size());
        assertTrue(props.allowedPaths().contains("/tmp"));
    }

    @Test
    @DisplayName("API Key 列表应为不可变")
    void shouldReturnUnmodifiableApiKeys() {
        SecurityProperties props = SecurityProperties.defaults();
        assertThrows(UnsupportedOperationException.class,
                () -> props.apiKeys().put("new-key", "test"));
    }
}
