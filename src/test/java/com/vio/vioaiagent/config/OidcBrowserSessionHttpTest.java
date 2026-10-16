package com.vio.vioaiagent.config;

import com.vio.vioaiagent.TestChatModelConfig;
import com.vio.vioaiagent.security.OidcSessionLifetime;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Runs the real security filter chain and MVC interceptors with a fixture
 * post-login OIDC session. No IdP network calls or manual RequestContext setup.
 * Token exchange and cryptographic verification remain staging acceptance work.
 */
@SpringBootTest(properties = "vio.http.cors.allowed-origins[0]=https://app.example.test")
@ActiveProfiles({"test", "oidc-login"})
@Import(TestChatModelConfig.class)
class OidcBrowserSessionHttpTest {
    private static final String ORIGIN = "https://app.example.test";

    @Autowired private WebApplicationContext context;
    @Autowired private FilterChainProxy springSecurityFilterChain;
    @Autowired private OidcRoleAuthoritiesMapper mapper;
    @Autowired private OidcSessionLifetime lifetime;
    @Autowired private OidcProperties oidcProperties;
    // The resource-server decoder boundary is mocked; this suite exercises HTTP
    // identity/session routing and makes no cryptographic or real-IdP claim.
    @MockitoBean private JwtDecoder jwtDecoder;
    private MockMvc mvc;

    @BeforeEach
    void buildMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        when(jwtDecoder.decode("validated-test-bearer")).thenAnswer(call -> Jwt.withTokenValue("validated-test-bearer")
                .header("alg", "RS256").claim("sub", "user-alice").claim("tenant_id", "tenant-a")
                .claim("roles", List.of("trainer")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(120))
                .build());
    }

    @Test
    void browserSubjectCanReadProtectedBusinessPathAndCreateOwnConversation() throws Exception {
        MockHttpSession session = session("user-alice", "tenant-a", List.of("trainer"), false);
        Cookie csrf = csrfCookie(session);
        assertEquals("/", csrf.getPath(), "SPA / must be able to read the backend CSRF cookie");
        assertFalse(csrf.isHttpOnly(), "CSRF cookie is intentionally readable by the SPA");
        String id = "oidc-session-" + UUID.randomUUID();
        mvc.perform(path(post("/api/conversations"), "/conversations").session(session)
                        .cookie(csrf).header("X-CSRF-TOKEN", csrf.getValue()).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\",\"title\":\"session fixture\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.userId").value("user-alice"));
        mvc.perform(path(get("/api/conversations/" + id), "/conversations/" + id).session(session))
                .andExpect(status().isOk());
    }

    @Test
    void normalOidcUserAuthoritiesAreInsufficientWithoutVerifiedRoleMapping() throws Exception {
        MockHttpSession session = session("user-alice", "tenant-a", List.of("trainer"), true);
        mvc.perform(path(get("/api/conversations"), "/conversations").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownRoleAndWrongTenantAreRejectedAtBusinessEntry() throws Exception {
        mvc.perform(path(get("/api/conversations"), "/conversations")
                        .session(session("user-alice", "tenant-a", List.of("operator"), false)))
                .andExpect(status().isForbidden());
        mvc.perform(path(get("/api/conversations"), "/conversations")
                        .session(session("user-alice", "tenant-b", List.of("trainer"), false)))
                .andExpect(status().isForbidden());
    }

    @Test
    void forgedRoleAuthorityCannotExpandTrustedClaims() throws Exception {
        OidcIdToken token = token("user-alice", "tenant-a", List.of("operator"));
        DefaultOidcUser principal = new DefaultOidcUser(List.of(new OidcUserAuthority(token)), token);
        Authentication forged = new OAuth2AuthenticationToken(principal,
                List.of(new SimpleGrantedAuthority("ROLE_trainer")), "vio");
        mvc.perform(path(get("/api/conversations"), "/conversations").session(session(forged)))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingWrongCsrfAndUntrustedOriginAreRejectedForAuthenticatedSession() throws Exception {
        MockHttpSession session = session("user-alice", "tenant-a", List.of("trainer"), false);
        Cookie csrf = csrfCookie(session);
        mvc.perform(path(post("/api/conversations"), "/conversations").session(session)
                        .cookie(csrf).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"missing csrf\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(path(post("/api/conversations"), "/conversations").session(session)
                        .cookie(csrf).header("X-CSRF-TOKEN", "wrong").header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"wrong csrf\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(path(post("/api/conversations"), "/conversations").session(session)
                        .cookie(csrf).header("X-CSRF-TOKEN", csrf.getValue())
                        .header("Origin", "https://attacker.example.test")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"wrong origin\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestUserHeaderCannotImpersonateConversationOwner() throws Exception {
        MockHttpSession session = session("user-alice", "tenant-a", List.of("trainer"), false);
        Cookie csrf = csrfCookie(session);
        String id = "oidc-owner-" + UUID.randomUUID();
        mvc.perform(path(post("/api/conversations"), "/conversations").session(session)
                        .cookie(csrf).header("X-CSRF-TOKEN", csrf.getValue()).header("Origin", ORIGIN)
                        .header("X-User-Id", "other-user").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\",\"title\":\"owner fixture\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.userId").value("user-alice"));
    }

    @Test
    void anonymousSessionBootstrapIsPublicAndProducesCsrfWithoutCredentials() throws Exception {
        MvcResult result = mvc.perform(path(get("/api/auth/session"), "/auth/session"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.mode").value("oidc"))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.accessDenied").value(false))
                .andExpect(jsonPath("$.principalId").isEmpty()).andExpect(jsonPath("$.roles").isEmpty())
                .andExpect(jsonPath("$.loginUrl").value("/api/oauth2/authorization/vio"))
                .andExpect(jsonPath("$.logoutUrl").value("/api/logout"))
                .andExpect(jsonPath("$.csrf.enabled").value(true))
                .andExpect(jsonPath("$.csrf.available").value(true)).andReturn();
        Cookie cookie = result.getResponse().getCookie("CSRF-TOKEN");
        assertNotNull(cookie);
        assertEquals("/", cookie.getPath());
        assertFalse(result.getResponse().getContentAsString().contains(cookie.getValue()));
        assertNull(result.getRequest().getSession(false), "Bootstrap must not create a login session");
    }

    @Test
    void sessionStatusUsesOnlyVerifiedPolicyAndDoesNotExtendAbsoluteLifetime() throws Exception {
        MockHttpSession session = session("user-alice", "tenant-a", List.of("trainer"), false);
        Object stamp = session.getAttribute(OidcSessionLifetime.SESSION_ATTRIBUTE);
        Instant deadline = ((OidcSessionLifetime.LoginStamp) stamp).absoluteExpiresAt();
        mvc.perform(path(get("/api/auth/session"), "/auth/session").session(session)
                        .header("X-User-Id", "attacker").header("X-Tenant-Id", "tenant-b"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.accessDenied").value(false))
                .andExpect(jsonPath("$.principalId").value("user-alice"))
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.roles[0]").value("trainer"))
                .andExpect(jsonPath("$.absoluteExpiresAt").value(deadline.toString()));
        assertSame(stamp, session.getAttribute(OidcSessionLifetime.SESSION_ATTRIBUTE));
    }

    /**
     * ADR-002 步骤 1：会话端点必须上报**内部归属键**，且该键确实包含 tenant。
     *
     * <p>注意 {@code /auth/session} 不经过 {@code ApiKeyAuthInterceptor}
     * （{@code /auth/**} 不在其路径列表中），它从已校验的 policy 自行派生。
     * 因此这里断言的是"派生结果正确"，而不是"RequestContext 被填充"——
     * 后者在业务路径（如 /conversations）上才成立，由本类的其他用例覆盖。
     */
    @Test
    void sessionReportsInternalOwnerKeyThatCarriesTenant() throws Exception {
        String expected = com.vio.vioaiagent.security.InternalPrincipalKey.fromOidc(
                "https://id.example.com/realms/vio", "tenant-a", "user-alice");
        mvc.perform(path(get("/api/auth/session"), "/auth/session")
                        .session(session("user-alice", "tenant-a", List.of("trainer"), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(expected));
        assertTrue(com.vio.vioaiagent.security.InternalPrincipalKey.isInternalKey(expected));

        // 同一 issuer、同一 sub，仅 tenant 不同 → 归属键必须不同。
        // 这正是 ADR-002 风险 A 要消除的碰撞：tenant 不参与键时两个租户会落到同一 owner。
        String otherTenant = com.vio.vioaiagent.security.InternalPrincipalKey.fromOidc(
                "https://id.example.com/realms/vio", "tenant-b", "user-alice");
        assertNotEquals(expected, otherTenant,
                "tenant 未参与归属键时两个租户会落到同一 owner，彼此资源可见");
    }

    /**
     * 归属键是新增维度，**不得**改变既有 owner 取值。
     *
     * <p>既有数据行以裸 sub 存储 owner；若这里悄悄换成内部键，
     * 既有会话/队伍/收藏会在升级后全部"消失"。迁移与双读见 ADR-002 §3，
     * 在完成迁移前本用例守住"现在还没换"。
     */
    @Test
    void existingOwnerValueIsStillTheLegacyPrincipal() throws Exception {
        MockHttpSession session = session("user-alice", "tenant-a", List.of("trainer"), false);
        Cookie csrf = csrfCookie(session);
        String id = "oidc-owner-legacy-" + UUID.randomUUID();
        mvc.perform(path(post("/api/conversations"), "/conversations").session(session)
                        .cookie(csrf).header("X-CSRF-TOKEN", csrf.getValue()).header("Origin", ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\",\"title\":\"legacy owner fixture\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value("user-alice"));
    }

    @Test
    void rejectedPolicyIsVisibleWithoutPublishingRejectedIdentity() throws Exception {
        mvc.perform(path(get("/api/auth/session"), "/auth/session")
                        .session(session("user-alice", "tenant-b", List.of("trainer"), false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.accessDenied").value(true))
                .andExpect(jsonPath("$.principalId").isEmpty()).andExpect(jsonPath("$.tenantId").isEmpty())
                .andExpect(jsonPath("$.roles").isEmpty()).andExpect(jsonPath("$.absoluteExpiresAt").isEmpty());
    }

    @Test
    void expiredSessionBecomesAnonymousAndCannotEnterBusinessIdentityInterceptor() throws Exception {
        MockHttpSession expired = expiredSession();
        mvc.perform(path(get("/api/conversations"), "/conversations").session(expired))
                .andExpect(status().isUnauthorized());
        assertTrue(expired.isInvalid());
        assertNull(com.vio.vioaiagent.common.RequestContext.getPrincipalId());
        MockHttpSession statusSession = expiredSession();
        mvc.perform(path(get("/api/auth/session"), "/auth/session").session(statusSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.accessDenied").value(false))
                .andExpect(jsonPath("$.absoluteExpiresAt").isEmpty());
        assertTrue(statusSession.isInvalid());
    }

    @Test
    void authenticatedLegacySessionWithoutServerTimestampFailsClosed() throws Exception {
        MockHttpSession legacy = session("user-alice", "tenant-a", List.of("trainer"), false);
        legacy.removeAttribute(OidcSessionLifetime.SESSION_ATTRIBUTE);
        mvc.perform(path(get("/api/auth/session"), "/auth/session").session(legacy))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
        assertTrue(legacy.isInvalid());
    }

    @Test
    void bearerIdentityRemainsUsableWithExpiredBrowserCookie() throws Exception {
        MockHttpSession expired = expiredSession();
        mvc.perform(path(get("/api/conversations"), "/conversations").session(expired)
                        .header("Authorization", "Bearer validated-test-bearer"))
                .andExpect(status().isOk());
        assertFalse(expired.isInvalid(), "A validated Bearer identity must not be rejected by cookie lifetime");
        mvc.perform(path(get("/api/auth/session"), "/auth/session").session(expired)
                        .header("Authorization", "Bearer validated-test-bearer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.principalId").value("user-alice"))
                .andExpect(jsonPath("$.absoluteExpiresAt").isNotEmpty());
        mvc.perform(path(get("/api/auth/session"), "/auth/session").session(expired))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
        assertTrue(expired.isInvalid(), "The Bearer request must not renew the expired browser login");
    }

    @Test
    void logoutRequiresCorrectCsrfAndAllowedBrowserOriginThenInvalidatesSession() throws Exception {
        MockHttpSession session = session("user-alice", "tenant-a", List.of("trainer"), false);
        Cookie csrf = csrfCookie(session);
        mvc.perform(path(post("/api/logout"), "/logout").session(session).cookie(csrf)
                        .header("Origin", ORIGIN)).andExpect(status().isForbidden());
        mvc.perform(path(post("/api/logout"), "/logout").session(session).cookie(csrf)
                        .header("X-CSRF-TOKEN", "wrong").header("Origin", ORIGIN))
                .andExpect(status().isForbidden());
        mvc.perform(path(post("/api/logout"), "/logout").session(session).cookie(csrf)
                        .header("X-CSRF-TOKEN", csrf.getValue()).header("Origin", "https://attacker.example.test"))
                .andExpect(status().isForbidden());
        assertFalse(session.isInvalid());
        mvc.perform(path(post("/api/logout"), "/logout").session(session).cookie(csrf)
                        .header("X-CSRF-TOKEN", csrf.getValue()).header("Origin", ORIGIN))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertTrue(session.isInvalid());
    }

    private MockHttpSession expiredSession() {
        MockHttpSession session = session("user-alice", "tenant-a", List.of("trainer"), false);
        Authentication authentication = ((SecurityContextImpl) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).getAuthentication();
        Clock oldLogin = Clock.fixed(Instant.now().minusSeconds(oidcProperties.sessionAbsoluteTimeoutSeconds() + 5),
                ZoneOffset.UTC);
        new OidcSessionLifetime(oidcProperties, oldLogin).start(session, authentication);
        return session;
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        MvcResult result = mvc.perform(path(get("/api/conversations"), "/conversations").session(session))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("CSRF-TOKEN");
        assertNotNull(cookie, "GET must materialize the deferred token before the SPA sends POST");
        assertFalse(cookie.getValue().isBlank());
        return cookie;
    }

    private MockHttpSession session(String subject, String tenant, List<String> roles, boolean unmapped) {
        OidcIdToken token = token(subject, tenant, roles);
        List<OidcUserAuthority> authorities = List.of(new OidcUserAuthority(token));
        DefaultOidcUser principal = new DefaultOidcUser(authorities, token);
        return session(new OAuth2AuthenticationToken(principal,
                unmapped ? authorities : mapper.mapAuthorities(authorities), "vio"));
    }

    private MockHttpSession session(Authentication authentication) {
        MockHttpSession session = new MockHttpSession(context.getServletContext());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(authentication));
        lifetime.start(session, authentication);
        return session;
    }

    private OidcIdToken token(String subject, String tenant, List<String> roles) {
        return new OidcIdToken("post-login-fixture", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("iss", "https://id.example.com/realms/vio", "sub", subject,
                        "aud", List.of("vio-web"), "tenant_id", tenant, "roles", roles));
    }

    private MockHttpServletRequestBuilder path(MockHttpServletRequestBuilder request, String servletPath) {
        return request.contextPath("/api").servletPath(servletPath).accept(MediaType.APPLICATION_JSON);
    }
}
