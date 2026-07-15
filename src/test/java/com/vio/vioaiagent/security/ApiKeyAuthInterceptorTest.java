package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApiKeyAuthInterceptor 测试.
 *
 * @author vio
 */
@DisplayName("API Key 认证拦截器")
class ApiKeyAuthInterceptorTest {

    private ApiKeyAuthInterceptor interceptor;
    private TokenStore tokenStore;

    @BeforeEach
    void setUp() {
        SecurityProperties props = new SecurityProperties(
                true,
                Map.of("sk-test-key-001", "测试令牌:default"),
                java.util.List.of(System.getProperty("user.dir") + "/tmp"),
                java.util.List.of()
        );
        tokenStore = new TokenStore(props);
        interceptor = new ApiKeyAuthInterceptor(tokenStore, props);
    }

    @Test
    @DisplayName("有效 API Key 应通过认证")
    void shouldAllowValidApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "sk-test-key-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, null));
    }

    @Test
    @DisplayName("无效 API Key 应返回 401")
    void shouldRejectInvalidApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "sk-invalid-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("缺少 API Key 应返回 401")
    void shouldRejectMissingApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("安全模块禁用时应放行所有请求")
    void shouldAllowAllWhenDisabled() throws Exception {
        SecurityProperties disabledProps = new SecurityProperties(
                false, Map.of(), java.util.List.of(), java.util.List.of()
        );
        TokenStore store = new TokenStore(disabledProps);
        ApiKeyAuthInterceptor disabledInterceptor = new ApiKeyAuthInterceptor(store, disabledProps);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(disabledInterceptor.preHandle(request, response, null));
    }

    @Test
    @DisplayName("Bearer Token 格式也应支持")
    void shouldSupportBearerToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer sk-test-key-001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, null));
    }
}
