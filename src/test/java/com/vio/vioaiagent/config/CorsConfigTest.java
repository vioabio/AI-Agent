package com.vio.vioaiagent.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CORS 白名单策略")
class CorsConfigTest {

    @Test
    @DisplayName("拒绝通配符 origin，避免凭据型全开放跨域")
    void shouldRejectWildcardOrigin() {
        assertThrows(IllegalArgumentException.class,
                () -> new CorsProperties(List.of("*")));
        assertThrows(IllegalArgumentException.class,
                () -> new CorsProperties(List.of("https://*.example.com")));
    }

    @Test
    @DisplayName("仅允许白名单 origin 并返回 credentials 标头")
    void shouldAllowConfiguredOriginOnly() throws ServletException, IOException {
        CorsFilter filter = new CorsConfig(new CorsProperties(
                List.of("http://localhost:3000"))).corsFilter();

        MockHttpServletRequest allowed = request("http://localhost:3000");
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        filter.doFilter(allowed, allowedResponse, new MockFilterChain());
        assertEquals("http://localhost:3000",
                allowedResponse.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true",
                allowedResponse.getHeader("Access-Control-Allow-Credentials"));

        MockHttpServletRequest denied = request("https://attacker.example");
        MockHttpServletResponse deniedResponse = new MockHttpServletResponse();
        filter.doFilter(denied, deniedResponse, new MockFilterChain());
        assertTrue(deniedResponse.getHeader("Access-Control-Allow-Origin") == null,
                "未在白名单中的 origin 不得获得 CORS 授权");
    }

    @Test
    @DisplayName("Run 的 Header 预检应被显式允许")
    void shouldHandleRunPreflight() throws ServletException, IOException {
        CorsFilter filter = new CorsConfig(new CorsProperties(
                List.of("http://localhost:3000"))).corsFilter();
        MockHttpServletRequest request = request("http://localhost:3000");
        request.setMethod("OPTIONS");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers",
                "content-type, x-api-key, x-csrf-token, idempotency-key, x-answer-trace-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String allowedMethods = response.getHeader("Access-Control-Allow-Methods");
        assertTrue(allowedMethods != null
                && allowedMethods.contains("POST")
                && allowedMethods.contains("OPTIONS"));
        String allowedHeaders = response.getHeader("Access-Control-Allow-Headers");
        assertTrue(allowedHeaders != null
                && allowedHeaders.toLowerCase().contains("x-api-key")
                && allowedHeaders.toLowerCase().contains("x-answer-trace-id"));
    }

    @Test
    @DisplayName("origin 必须是无路径的 HTTP(S) origin")
    void shouldRejectNonOriginValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new CorsProperties(List.of("/api")));
        assertThrows(IllegalArgumentException.class,
                () -> new CorsProperties(List.of("https://example.com/app")));
        assertThrows(IllegalArgumentException.class,
                () -> new CorsProperties(List.of("javascript:alert(1)")));
    }

    @Test
    @DisplayName("逗号分隔环境变量应绑定为多个 origin")
    void shouldBindCommaSeparatedEnvironmentValue() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("vio.http.cors.allowed-origins",
                        "https://agent.example.com, https://admin.example.com");

        CorsProperties properties = Binder.get(environment)
                .bind("vio.http.cors", CorsProperties.class)
                .orElseThrow(() -> new AssertionError("CORS origin 配置未绑定"));

        assertEquals(List.of("https://agent.example.com", "https://admin.example.com"),
                properties.allowedOrigins());
    }

    private MockHttpServletRequest request(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.addHeader("Origin", origin);
        return request;
    }
}
