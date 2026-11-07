package com.vio.vioaiagent.security;

import com.vio.vioaiagent.config.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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

    @AfterEach
    void clearRequestContext() {
        com.vio.vioaiagent.common.RequestContext.clear();
        SecurityContextHolder.clearContext();
    }

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
        assertNull(com.vio.vioaiagent.common.RequestContext.getTraceId());
        assertNull(com.vio.vioaiagent.common.RequestContext.getPrincipalId());
    }

    @Test
    @DisplayName("缺少 API Key 应返回 401")
    void shouldRejectMissingApiKey() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
        assertNull(com.vio.vioaiagent.common.RequestContext.getTraceId());
        assertNull(com.vio.vioaiagent.common.RequestContext.getPrincipalId());
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
        assertNotNull(com.vio.vioaiagent.common.RequestContext.getTokenBinding());
        assertEquals("default", com.vio.vioaiagent.common.RequestContext.getTokenBinding().protocolId());
    }

    @Test
    @DisplayName("CORS 预检不应被 API Key 拦截")
    void shouldAllowCorsPreflight() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/chat/runs");
        request.addHeader("Origin", "https://agent.example.com");
        request.addHeader("Access-Control-Request-Method", "POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, null));
        assertEquals(204, response.getStatus());
    }

    @Test
    @DisplayName("同一令牌应派生稳定且不含密钥的主体")
    void shouldDeriveStablePrincipal() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.addHeader("X-API-Key", "sk-test-key-001");
        assertTrue(interceptor.preHandle(first, new MockHttpServletResponse(), null));
        String principal = com.vio.vioaiagent.common.RequestContext.getPrincipalId();
        assertNotNull(principal);
        assertFalse(principal.contains("sk-test-key-001"));

        com.vio.vioaiagent.common.RequestContext.clear();
        MockHttpServletRequest second = new MockHttpServletRequest();
        second.addHeader("X-API-Key", "sk-test-key-001");
        assertTrue(interceptor.preHandle(second, new MockHttpServletResponse(), null));
        assertEquals(principal, com.vio.vioaiagent.common.RequestContext.getPrincipalId());
        assertEquals(Long.valueOf(principal), com.vio.vioaiagent.common.RequestContext.getUserId());
    }

    @Test
    @DisplayName("OIDC 主体不应继承上一个 API-Key 请求的 TokenBinding")
    void oidcPrincipalClearsStaleTokenBinding() throws Exception {
        TokenBinding stale = new TokenBinding("old-token", "pokemon", java.util.Set.of("getTypeChart"), 1, null);
        com.vio.vioaiagent.common.RequestContext.setTokenBinding(stale);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("oidc-user", "test", "ROLE_trainer"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat/runs");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, null));
        assertNull(com.vio.vioaiagent.common.RequestContext.getTokenBinding());
    }

    @Test
    @DisplayName("未配置可信代理时应忽略伪造的转发头")
    void shouldIgnoreForwardedHeadersFromUntrustedPeer() throws Exception {
        MockHttpServletRequest request = validRequest("198.51.100.10");
        request.addHeader("X-Forwarded-For", "203.0.113.42");
        request.addHeader("X-Real-IP", "203.0.113.43");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), null));
        assertEquals("198.51.100.10", com.vio.vioaiagent.common.RequestContext.getClientIp());
    }

    @Test
    @DisplayName("可信代理链应解析最靠近客户端的非可信地址")
    void shouldResolveForwardedClientFromTrustedProxyChain() throws Exception {
        SecurityProperties props = properties(java.util.List.of("10.0.0.0/8"));
        ApiKeyAuthInterceptor trustedInterceptor = new ApiKeyAuthInterceptor(
                new TokenStore(props), props, new ClientIpResolver(props.trustedProxyCidrs()));
        MockHttpServletRequest request = validRequest("10.10.10.10");
        request.addHeader("X-Forwarded-For", "203.0.113.42, 10.20.30.40");

        assertTrue(trustedInterceptor.preHandle(request, new MockHttpServletResponse(), null));
        assertEquals("203.0.113.42", com.vio.vioaiagent.common.RequestContext.getClientIp());
    }

    @Test
    @DisplayName("可信代理发送非法转发头时应回退到 socket 对端")
    void shouldFailClosedOnMalformedForwardedHeader() throws Exception {
        SecurityProperties props = properties(java.util.List.of("10.0.0.0/8"));
        ApiKeyAuthInterceptor trustedInterceptor = new ApiKeyAuthInterceptor(
                new TokenStore(props), props, new ClientIpResolver(props.trustedProxyCidrs()));
        MockHttpServletRequest request = validRequest("10.10.10.10");
        request.addHeader("X-Forwarded-For", "203.0.113.42, not-an-ip");
        request.addHeader("X-Real-IP", "203.0.113.43");

        assertTrue(trustedInterceptor.preHandle(request, new MockHttpServletResponse(), null));
        assertEquals("10.10.10.10", com.vio.vioaiagent.common.RequestContext.getClientIp());
    }

    @Test
    @DisplayName("可信代理可使用合法的 X-Real-IP")
    void shouldResolveRealIpFromTrustedProxy() throws Exception {
        SecurityProperties props = properties(java.util.List.of("10.0.0.0/8"));
        ApiKeyAuthInterceptor trustedInterceptor = new ApiKeyAuthInterceptor(
                new TokenStore(props), props, new ClientIpResolver(props.trustedProxyCidrs()));
        MockHttpServletRequest request = validRequest("10.10.10.10");
        request.addHeader("X-Real-IP", "203.0.113.43");

        assertTrue(trustedInterceptor.preHandle(request, new MockHttpServletResponse(), null));
        assertEquals("203.0.113.43", com.vio.vioaiagent.common.RequestContext.getClientIp());
    }

    @Test
    @DisplayName("可信代理 CIDR 应支持 IPv6")
    void shouldResolveIpv6TrustedProxy() throws Exception {
        SecurityProperties props = properties(java.util.List.of("2001:db8::/32"));
        ApiKeyAuthInterceptor trustedInterceptor = new ApiKeyAuthInterceptor(
                new TokenStore(props), props, new ClientIpResolver(props.trustedProxyCidrs()));
        MockHttpServletRequest request = validRequest("2001:db8::10");
        request.addHeader("X-Forwarded-For", "2001:4860:4860::8888");

        assertTrue(trustedInterceptor.preHandle(request, new MockHttpServletResponse(), null));
        assertEquals(java.net.InetAddress.getByName("2001:4860:4860::8888").getHostAddress(),
                com.vio.vioaiagent.common.RequestContext.getClientIp());
    }

    @Test
    @DisplayName("非法可信代理 CIDR 应拒绝构造")
    void shouldRejectInvalidTrustedProxyCidr() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClientIpResolver(java.util.List.of("not-a-cidr")));
    }

    private MockHttpServletRequest validRequest(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-API-Key", "sk-test-key-001");
        return request;
    }

    private SecurityProperties properties(java.util.List<String> trustedProxyCidrs) {
        return new SecurityProperties(
                true,
                Map.of("sk-test-key-001", "测试令牌:default"),
                java.util.List.of(System.getProperty("user.dir") + "/tmp"),
                java.util.List.of(),
                trustedProxyCidrs);
    }
}
