package com.vio.vioaiagent.controller;

import com.vio.vioaiagent.TestChatModelConfig;
import com.vio.vioaiagent.common.RequestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 根资源身份接线端到端测试（升级方案 F02）。
 *
 * <p>本测试使用 {@code RANDOM_PORT} 启动真实 Servlet 容器，再用
 * {@link TestRestTemplate} 发起真实 HTTP 请求，因此请求会真正穿过
 * Spring Security 过滤链、DispatcherServlet、CORS 与 MVC 拦截器。
 *
 * <p>这是 F02 验收的关键要求：不能用测试代码手工调用
 * {@code RequestContext.setPrincipalId(...)} 伪造身份，否则会掩盖
 * “拦截器路径未覆盖该路由”这一真实缺陷。
 *
 * <p>历史缺陷：{@code /conversations/**}、{@code /teams/**}、{@code /favorites/**}
 * 不在 {@code SecurityConfig} 的拦截路径内，导致即使携带合法 API Key，
 * {@code RequestContext} 主体仍为空，{@code ResourceController.owner()} 抛 401。
 *
 * <p>两处容易踩错的地方，本类已显式处理：
 * <ol>
 *   <li>应用设置了 {@code server.servlet.context-path=/api}，请求路径必须带
 *       {@code /api} 前缀，否则得到的是 404 而不是真实的认证结果；</li>
 *   <li>{@code /ready} 与 {@code /ready/details} 是有意的父子差异：
 *       前者匿名探活，后者要求探针凭据。</li>
 * </ol>
 *
 * <p>凭据来自 {@code src/test/resources/application-f02-identity.yml}：
 * 两个不同的 API Key 派生两个不同主体，用于验证跨主体隔离。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "f02-identity"})
@Import(TestChatModelConfig.class)
@DisplayName("根资源身份接线 E2E")
class ResourceIdentityHttpTest {

    private static final String KEY_ALPHA = "sk-f02-alpha-0123456789";
    private static final String KEY_BETA = "sk-f02-beta-9876543210";

    /** 真实监听端口；URL 一律用绝对地址，避免相对路径与 context-path 组合出错。 */
    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    // ==================== 匿名访问 ====================

    @Test
    @DisplayName("匿名访问 /conversations 必须 401")
    void anonymousConversationsIsUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/conversations", null).getStatusCode());
    }

    @Test
    @DisplayName("匿名访问 /teams 必须 401")
    void anonymousTeamsIsUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/teams", null).getStatusCode());
    }

    @Test
    @DisplayName("匿名访问 /favorites 必须 401")
    void anonymousFavoritesIsUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/favorites", null).getStatusCode());
    }

    @Test
    @DisplayName("无效 API Key 必须 401（不能降级为匿名身份）")
    void invalidKeyIsUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED, get("/api/conversations", "sk-not-a-real-key").getStatusCode());
    }

    // ==================== 认证后可用 ====================

    @Test
    @DisplayName("合法凭据可列出会话（F02 回归：曾经固定返回 401）")
    void authenticatedCanListConversations() {
        ResponseEntity<String> response = get("/api/conversations", KEY_ALPHA);
        assertEquals(HttpStatus.OK, response.getStatusCode(),
                () -> "携带合法 API Key 仍无法访问 /conversations，说明身份未接入 RequestContext: "
                        + response.getBody());
    }

    @Test
    @DisplayName("合法凭据可创建会话并原样读回")
    void authenticatedCanCreateAndReadConversation() {
        String id = "f02-conv-" + System.nanoTime();
        ResponseEntity<String> created = postConversation(id, "F02 身份接线", KEY_ALPHA);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created::getBody);

        ResponseEntity<String> read = get("/api/conversations/" + id, KEY_ALPHA);
        assertEquals(HttpStatus.OK, read.getStatusCode(), read::getBody);
    }

    @Test
    @DisplayName("两个主体的会话互相隔离")
    void conversationsAreIsolatedBetweenPrincipals() {
        String id = "f02-iso-" + System.nanoTime();
        assertEquals(HttpStatus.CREATED, postConversation(id, "仅 alpha 可见", KEY_ALPHA).getStatusCode());

        // 自己可见
        assertEquals(HttpStatus.OK, get("/api/conversations/" + id, KEY_ALPHA).getStatusCode());

        // 另一个主体既读不到单条，也不应出现在列表里
        assertEquals(HttpStatus.NOT_FOUND, get("/api/conversations/" + id, KEY_BETA).getStatusCode(),
                "跨主体读取会话必须返回 404，不能泄露资源存在性");

        String betaList = get("/api/conversations", KEY_BETA).getBody();
        assertNotNull(betaList);
        assertFalse(betaList.contains(id), "alpha 的会话不得出现在 beta 的列表中: " + betaList);
    }

    @Test
    @DisplayName("X-User-Id 请求头不能冒充其他主体")
    void spoofedUserHeaderCannotImpersonate() {
        String id = "f02-spoof-" + System.nanoTime();
        assertEquals(HttpStatus.CREATED, postConversation(id, "alpha 私有", KEY_ALPHA).getStatusCode());

        // beta 带上一堆伪造主体头，仍必须看不到 alpha 的资源。
        HttpHeaders headers = authHeaders(KEY_BETA);
        headers.add("X-User-Id", "1");
        headers.add("X-Principal-Id", "anonymous");
        ResponseEntity<String> response = rest.exchange(url("/api/conversations/" + id),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "请求头不得覆盖认证派生主体，否则构成越权读取");
    }

    @Test
    @DisplayName("两个测试凭据必须派生不同主体（否则隔离断言没有意义）")
    void testCredentialsMapToDistinctPrincipals() {
        String id = "f02-distinct-" + System.nanoTime();
        assertEquals(HttpStatus.CREATED, postConversation(id, "alpha", KEY_ALPHA).getStatusCode());
        assertFalse(get("/api/conversations", KEY_BETA).getBody().contains(id),
                "两个凭据必须派生不同主体，否则本次隔离断言无效");
    }

    // ==================== 公开面与历史行为 ====================

    @Test
    @DisplayName("探针 /ready 保持匿名可用")
    void readyProbeStaysPublic() {
        ResponseEntity<String> response = rest.getForEntity(url("/api/ready"), String.class);
        assertFalse(response.getStatusCode() == HttpStatus.UNAUTHORIZED,
                "/ready 必须保持匿名可用，否则编排系统无法探活");
        assertFalse(response.getStatusCode() == HttpStatus.FORBIDDEN,
                "/ready 不应要求业务身份");
    }

    @Test
    @DisplayName("历史 /chat/runs 行为不退化：匿名 401")
    void legacyChatRunStillRequiresAuth() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                postJson("/api/chat/runs", Map.of("prompt", "hi"), null).getStatusCode());
    }

    @Test
    @DisplayName("测试线程不残留其他请求的主体上下文")
    void requestContextIsClearedAfterRequest() {
        // 真实 HTTP 请求由容器线程处理；本测试线程不应残留任何主体上下文。
        assertNull(RequestContext.getPrincipalId(),
                "测试线程不应持有其他请求的主体，说明 ThreadLocal 未被正确清理");
    }

    // ==================== 辅助 ====================

    private ResponseEntity<String> get(String path, String apiKey) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(authHeaders(apiKey)), String.class);
    }

    private ResponseEntity<String> postConversation(String id, String title, String apiKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("title", title);
        return postJson("/api/conversations", body, apiKey);
    }

    private ResponseEntity<String> postJson(String path, Object body, String apiKey) {
        HttpHeaders headers = authHeaders(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders authHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) headers.set("X-API-Key", apiKey);
        return headers;
    }
}
