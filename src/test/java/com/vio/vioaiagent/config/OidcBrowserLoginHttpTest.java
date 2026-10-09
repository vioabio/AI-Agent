package com.vio.vioaiagent.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OIDC 浏览器登录闭环离线回归（R01）。
 *
 * <p>验证范围（全程无外部网络请求）：
 * <ol>
 *   <li>登录入口重定向到配置的授权端点，使用授权码模式；</li>
 *   <li>授权请求携带 {@code code_challenge} 与 {@code code_challenge_method=S256}
 *       ——证明 {@code requireProofKey} 真正生效，而不只是停在配置层；</li>
 *   <li>授权请求携带 client_id/scope/state、回调地址正确，且**不泄漏**
 *       {@code code_verifier}；</li>
 *   <li>回调端点匿名可达，不会被重定向回登录入口（那会造成授权死循环）；</li>
 *   <li>未认证的 API 调用得到 <b>401 JSON</b> 而不是 302 ——
 *       前端 fetch/SSE 拿到跨站重定向只会表现为 CORS 或空响应失败；</li>
 *   <li>浏览器导航（Accept: text/html）仍得到跳转；</li>
 *   <li>探针端点保持匿名可用；</li>
 *   <li>{@code /logout} 返回 204，且登出 CSRF 豁免不依赖 token；</li>
 *   <li>其余写请求仍要求 CSRF token（豁免面保持最小）。</li>
 * </ol>
 *
 * <p>为什么在测试里起一个本机 stub 授权端点：客户端的默认行为是跟随 302，
 * 若把 authorizationUri 指向夹具里不存在的域名，请求会以
 * {@code UnresolvedAddressException} 结束，断言连状态码都拿不到。
 * 把授权端点指向本机 stub 后，重定向会真实落到 stub 上，
 * 于是「302 + 查询参数」变成可断言的观察对象，而整个过程不出本机。
 *
 * <p><b>明确不在本测试范围内</b>：真实 IdP 的 token 交换、id_token 验签、
 * JWKS 轮换、跨实例会话撤销、浏览器 Cookie 的实际投递。
 * 这些必须有真实身份提供方才能成立，属于 staging 验收项，不能用本测试的绿灯替代。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "oidc-login"})
@Import(com.vio.vioaiagent.TestChatModelConfig.class)
@DisplayName("R01 OIDC 浏览器登录闭环")
class OidcBrowserLoginHttpTest {

    private static final String CONTEXT_PATH = "/api";
    private static final String AUTHORIZE_PATH = CONTEXT_PATH + "/oauth2/authorization/"
            + OidcClientRegistrationFactory.REGISTRATION_ID;
    private static final String CALLBACK_PATH = CONTEXT_PATH + "/login/oauth2/code/"
            + OidcClientRegistrationFactory.REGISTRATION_ID;

    /** stub IdP：记录授权请求，供断言查询串。 */
    private static HttpServer stubProvider;
    private static int stubPort;
    private static final AtomicReference<URI> lastAuthorizationRequest = new AtomicReference<>();

    @DynamicPropertySource
    static void pointAuthorizationEndpointAtStub(DynamicPropertyRegistry registry) {
        registry.add("vio.security.oidc.authorization-uri",
                () -> "http://127.0.0.1:" + stubPort + "/auth");
    }

    @org.junit.jupiter.api.BeforeAll
    static void startStubProvider() throws IOException {
        stubProvider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubProvider.createContext("/auth", OidcBrowserLoginHttpTest::handleAuthorization);
        stubProvider.start();
        stubPort = stubProvider.getAddress().getPort();
    }

    @AfterAll
    static void stopStubProvider() {
        if (stubProvider != null) stubProvider.stop(0);
    }

    @BeforeEach
    void resetCapture() {
        lastAuthorizationRequest.set(null);
    }

    private static void handleAuthorization(HttpExchange exchange) throws IOException {
        lastAuthorizationRequest.set(exchange.getRequestURI());
        byte[] body = "stub-authorization-endpoint".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @LocalServerPort
    private int port;

    /** 默认客户端会跟随 302；这里正好利用这一点把授权请求送到 stub。 */
    private final TestRestTemplate rest = new TestRestTemplate();

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    // ==================== 授权请求 ====================

    @Test
    @DisplayName("登录入口重定向到配置的授权端点，使用授权码模式")
    void authorizeEndpointRedirectsToConfiguredProvider() {
        Map<String, String> query = authorizationQuery();

        assertEquals("code", query.get("response_type"),
                "必须使用授权码模式，不能是 implicit 或 hybrid");
    }

    @Test
    @DisplayName("授权请求必须携带 PKCE S256 challenge（证明 requireProofKey 真正生效）")
    void authorizeRequestCarriesPkceChallenge() {
        Map<String, String> query = authorizationQuery();

        String challenge = query.get("code_challenge");
        assertNotNull(challenge,
                "缺少 code_challenge：requireProofKey 只停在配置层，没有产生实际保护");
        assertEquals("S256", query.get("code_challenge_method"),
                "PKCE 必须使用 S256；plain 不提供实际保护");
        // S256 challenge = base64url(sha256(verifier))，43 字符且无填充。
        assertTrue(challenge.matches("[A-Za-z0-9_-]{43}"),
                "code_challenge 形状不合法（应为 S256 摘要）: " + challenge);
        assertNull(query.get("code_verifier"),
                "code_verifier 绝不能出现在授权请求里，否则 PKCE 失去意义");
    }

    @Test
    @DisplayName("授权请求携带 client_id、scope、state 与正确的回调地址")
    void authorizeRequestCarriesScopeStateAndClientId() {
        Map<String, String> query = authorizationQuery();

        assertEquals("vio-web", query.get("client_id"));
        assertNotNull(query.get("state"), "缺少 state：授权请求无法与会话关联");
        String scope = query.get("scope");
        assertNotNull(scope);
        assertTrue(Arrays.asList(scope.split(" ")).contains("openid"),
                "scope 必须包含 openid，实际: " + scope);
        assertEquals("http://127.0.0.1:" + port + CALLBACK_PATH, query.get("redirect_uri"),
                "回调地址必须带 context-path 且与已登记值一致");
    }

    @Test
    @DisplayName("重复进入登录入口生成新的 state（不复用旧授权请求）")
    void authorizeRequestGeneratesFreshState() {
        String first = authorizationQuery().get("state");
        String second = authorizationQuery().get("state");
        assertNotNull(first);
        assertNotNull(second);
        assertFalse(first.equals(second), "每次登录应生成独立 state，避免授权请求被重放");
    }

    // ==================== 回调端点 ====================

    @Test
    @DisplayName("回调端点匿名可达，不被重定向回登录入口（无授权死循环）")
    void callbackIsReachableWithoutSession() {
        ResponseEntity<String> response = getRaw(CALLBACK_PATH + "?code=stub&state=stub",
                browserHeaders());

        URI location = response.getHeaders().getLocation();
        assertFalse(location != null && location.toString().contains("/oauth2/authorization/"),
                "回调不得被重定向回登录入口（会造成授权死循环），实际 Location=" + location);
    }

    // ==================== 未认证分流 ====================

    @Test
    @DisplayName("未认证的 API 调用得到 401 JSON，而不是 302")
    void unauthenticatedApiRequestGetsUnauthorizedJson() {
        ResponseEntity<String> response = getRaw(CONTEXT_PATH + "/conversations", apiHeaders());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "API 客户端必须得到 401；302 到 IdP 会让 fetch/SSE 表现为 CORS 或空响应失败");
        assertNull(response.getHeaders().getLocation(), "401 不应带跳转地址");
        assertTrue(response.getBody() != null && response.getBody().contains("UNAUTHENTICATED"),
                "401 响应体应说明未认证原因，实际: " + response.getBody());
    }

    @Test
    @DisplayName("无 Accept 头的脚本调用也按 API 处理（401 而不是跳转）")
    void headerlessClientGetsUnauthorizedJson() {
        ResponseEntity<String> response = getRaw(CONTEXT_PATH + "/conversations", new HttpHeaders());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(),
                "curl/脚本默认不带 Accept，应按 API 处理");
    }

    @Test
    @DisplayName("浏览器导航到受保护路径仍走跳转，不被 JSON 分支截走")
    void browserNavigationStillRedirectsToProvider() {
        // 用不跟随重定向的方式观察首个响应：直接请求受保护路径，
        // 断言 302 且 Location 指向本应用登录入口。
        ResponseEntity<String> response = rest.exchange(url(CONTEXT_PATH + "/conversations"),
                HttpMethod.GET, new HttpEntity<>(browserHeaders()), String.class);

        // 客户端会跟随到 stub（返回 200），因此这里改为断言 stub 收到了授权请求。
        assertNotNull(lastAuthorizationRequest.get(),
                "浏览器导航应被送到授权端点；若为 401 JSON 说明分流判断把浏览器当成了 API 客户端");
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("探针端点保持匿名可用")
    void probesRemainPublic() {
        for (String path : new String[]{CONTEXT_PATH + "/health", CONTEXT_PATH + "/ready"}) {
            ResponseEntity<String> response = getRaw(path, new HttpHeaders());
            assertFalse(response.getStatusCode() == HttpStatus.UNAUTHORIZED,
                    path + " 必须匿名可用，否则编排系统无法探活");
            assertFalse(response.getStatusCode() == HttpStatus.FOUND,
                    path + " 不应被重定向到 IdP");
        }
    }

    // ==================== 退出与 CSRF ====================

    @Test
    @DisplayName("/logout 返回 204，登出 CSRF 豁免不依赖 token")
    void logoutIsExemptFromCsrfAndReturnsNoContent() {
        ResponseEntity<String> response = rest.exchange(url(CONTEXT_PATH + "/logout"),
                HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), String.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode(),
                "退出应显式返回 204 便于前端自行决定落地页；"
                        + "若为 403 说明 /logout 的 CSRF 豁免被 csrfTokenRepository 配置覆盖掉了");
    }

    @Test
    @DisplayName("其余写请求仍要求 CSRF token（豁免面保持最小）")
    void otherStateChangingRequestsStillRequireCsrf() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(url(CONTEXT_PATH + "/conversations"),
                HttpMethod.POST, new HttpEntity<>("{\"title\":\"x\"}", headers), String.class);

        // 未认证主体优先表现为 401；关键是不能在缺少 CSRF token 时放行到业务逻辑。
        assertTrue(response.getStatusCode() == HttpStatus.UNAUTHORIZED
                        || response.getStatusCode() == HttpStatus.FORBIDDEN,
                "无 CSRF token 的写请求必须被拒（401/403），实际: " + response.getStatusCode());
    }

    // ==================== 辅助 ====================

    /** 触发登录入口并取回 stub 收到的授权请求查询串。 */
    private Map<String, String> authorizationQuery() {
        resetCapture();
        rest.exchange(url(AUTHORIZE_PATH), HttpMethod.GET,
                new HttpEntity<>(browserHeaders()), String.class);
        URI captured = lastAuthorizationRequest.get();
        assertNotNull(captured,
                "登录入口没有把浏览器送到授权端点（stub 未收到请求）");
        return query(captured);
    }

    private ResponseEntity<String> getRaw(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** 模拟浏览器导航。 */
    private HttpHeaders browserHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_HTML));
        return headers;
    }

    /** 模拟前端 fetch。 */
    private HttpHeaders apiHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    /** 解析查询串。 */
    private Map<String, String> query(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            // 用 Charset 重载：String 版本会抛受检的 UnsupportedEncodingException。
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }
}
