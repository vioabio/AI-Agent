package com.vio.vioaiagent.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TokenBinding 测试.
 *
 * @author vio
 */
@DisplayName("令牌绑定模型")
class TokenBindingTest {

    @Test
    @DisplayName("未过期令牌 isExpired 应为 false")
    void shouldNotBeExpired() {
        TokenBinding binding = new TokenBinding(
                "key-1", "default", Set.of(), 0,
                Instant.now().plus(1, ChronoUnit.HOURS));

        assertFalse(binding.isExpired());
    }

    @Test
    @DisplayName("已过期令牌 isExpired 应为 true")
    void shouldBeExpired() {
        TokenBinding binding = new TokenBinding(
                "key-1", "default", Set.of(), 0,
                Instant.now().minus(1, ChronoUnit.HOURS));

        assertTrue(binding.isExpired());
    }

    @Test
    @DisplayName("永不过期令牌（expiresAt=null）isExpired 应为 false")
    void shouldNeverExpire() {
        TokenBinding binding = TokenBinding.unlimited("key-1", "default");
        assertFalse(binding.isExpired());
    }

    @Test
    @DisplayName("allowedTools 为空时应允许所有工具")
    void shouldAllowAllToolsWhenEmpty() {
        TokenBinding binding = TokenBinding.unlimited("key-1", "default");
        assertTrue(binding.allowsTool("any_tool"));
        assertTrue(binding.allowsTool("terminal_operation"));
    }

    @Test
    @DisplayName("allowedTools 非空时应仅允许指定工具")
    void shouldOnlyAllowSpecifiedTools() {
        TokenBinding binding = new TokenBinding(
                "key-1", "default",
                Set.of("web_search", "web_scraping"), 0, null);

        assertTrue(binding.allowsTool("web_search"));
        assertTrue(binding.allowsTool("web_scraping"));
        assertFalse(binding.allowsTool("terminal_operation"));
    }
}
