package com.vio.vioaiagent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ParameterMasker 测试.
 *
 * @author vio
 */
@DisplayName("参数脱敏器")
class ParameterMaskerTest {

    private ParameterMasker masker;

    @BeforeEach
    void setUp() {
        masker = new ParameterMasker();
    }

    @Test
    @DisplayName("敏感字段 password 应被脱敏")
    void shouldMaskPassword() {
        Map<String, Object> params = Map.of("password", "my-secret-123");
        Map<String, Object> masked = masker.mask(params);

        assertEquals(ParameterMasker.REDACTED, masked.get("password"));
    }

    @Test
    @DisplayName("敏感字段 apiKey 应被脱敏")
    void shouldMaskApiKey() {
        Map<String, Object> params = Map.of("apiKey", "sk-real-key-001");
        Map<String, Object> masked = masker.mask(params);

        assertEquals(ParameterMasker.REDACTED, masked.get("apiKey"));
    }

    @Test
    @DisplayName("非敏感字段应原样保留")
    void shouldKeepNonSensitiveFields() {
        Map<String, Object> params = Map.of("query", "hello", "count", 42);
        Map<String, Object> masked = masker.mask(params);

        assertEquals("hello", masked.get("query"));
        assertEquals(42, masked.get("count"));
    }

    @Test
    @DisplayName("token 字段应被脱敏")
    void shouldMaskToken() {
        Map<String, Object> params = Map.of("auth_token", "eyJhbGciOiJIUzI1NiJ9...");
        Map<String, Object> masked = masker.mask(params);

        assertEquals(ParameterMasker.REDACTED, masked.get("auth_token"));
    }

    @Test
    @DisplayName("大小写不敏感匹配")
    void shouldBeCaseInsensitive() {
        Map<String, Object> params = Map.of("Authorization", "Bearer xxx");
        Map<String, Object> masked = masker.mask(params);

        assertEquals(ParameterMasker.REDACTED, masked.get("Authorization"));
    }

    @Test
    @DisplayName("null/空参数应返回原值")
    void shouldHandleNullAndEmpty() {
        assertNull(masker.mask(null));
        assertTrue(masker.mask(Map.of()).isEmpty());
    }
}
