package com.vio.vioaiagent.config;

import com.vio.vioaiagent.common.RequestContext;
import com.vio.vioaiagent.security.OidcSessionLifetime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OidcAbsoluteSessionTest {
    private static final Instant LOGIN = Instant.parse("2026-10-12T02:00:00Z");

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        RequestContext.clear();
    }

    @Test
    void successStartsAtLoginTimeAndDiscardsSavedApiRedirect() throws Exception {
        OidcProperties properties = properties();
        OidcSessionLifetime lifetime = at(properties, LOGIN);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/login/oauth2/code/vio");
        request.setContextPath("/api");
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new HttpSessionRequestCache().saveRequest(request, response);
        new OidcLoginSuccessHandler(lifetime).onAuthenticationSuccess(request, response, browser("alice"));
        OidcSessionLifetime.LoginStamp stamp = (OidcSessionLifetime.LoginStamp)
                session.getAttribute(OidcSessionLifetime.SESSION_ATTRIBUTE);
        assertEquals(LOGIN, stamp.loginAt());
        assertEquals(LOGIN.plusSeconds(600), stamp.absoluteExpiresAt());
        assertEquals("/", response.getRedirectedUrl());
        assertNull(new HttpSessionRequestCache().getRequest(request, response));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void ordinaryRequestsNeverRenewAndExactDeadlineIsExpired() throws Exception {
        OidcProperties properties = properties();
        Authentication user = browser("alice");
        MockHttpSession session = new MockHttpSession();
        OidcSessionLifetime lifetime = at(properties, LOGIN);
        Instant deadline = lifetime.start(session, user);
        Object stamp = session.getAttribute(OidcSessionLifetime.SESSION_ATTRIBUTE);
        MockHttpServletRequest request = request(session);
        SecurityContextHolder.getContext().setAuthentication(user);
        new OidcAbsoluteSessionFilter(at(properties, deadline.minusNanos(1)), properties)
                .doFilter(request, new MockHttpServletResponse(), (req, res) ->
                        assertEquals(deadline, OidcSessionLifetime.validatedDeadline((MockHttpServletRequest) req)));
        assertSame(stamp, session.getAttribute(OidcSessionLifetime.SESSION_ATTRIBUTE));
        assertEquals(user, SecurityContextHolder.getContext().getAuthentication());
        RequestContext.setPrincipalId("stale-thread-user");
        AtomicBoolean reached = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();
        new OidcAbsoluteSessionFilter(at(properties, deadline), properties)
                .doFilter(request(session), response, (req, res) -> {
                    reached.set(true);
                    assertNull(SecurityContextHolder.getContext().getAuthentication());
                    assertNull(RequestContext.getPrincipalId());
                    assertNull(OidcSessionLifetime.validatedDeadline((MockHttpServletRequest) req));
                });
        assertTrue(reached.get());
        assertTrue(session.isInvalid());
        String deletedCookie = response.getHeader("Set-Cookie");
        assertNotNull(deletedCookie);
        assertTrue(deletedCookie.contains("VIO_SESSION="));
        assertTrue(deletedCookie.contains("Path=/api"));
        assertTrue(deletedCookie.contains("Max-Age=0"));
    }

    @Test
    void missingMalformedFutureAndSwitchedPrincipalStampsFailClosed() {
        OidcProperties properties = properties();
        OidcSessionLifetime lifetime = at(properties, LOGIN);
        MockHttpSession session = new MockHttpSession();
        Authentication alice = browser("alice");
        assertTrue(lifetime.deadline(session, alice).isEmpty());
        session.setAttribute(OidcSessionLifetime.SESSION_ATTRIBUTE, "untrusted-stamp");
        assertTrue(lifetime.deadline(session, alice).isEmpty());
        lifetime.start(session, alice);
        assertTrue(lifetime.deadline(session, browser("bob")).isEmpty());
        OidcSessionLifetime.LoginStamp stamp = (OidcSessionLifetime.LoginStamp)
                session.getAttribute(OidcSessionLifetime.SESSION_ATTRIBUTE);
        session.setAttribute(OidcSessionLifetime.SESSION_ATTRIBUTE,
                new OidcSessionLifetime.LoginStamp(stamp.identity(), LOGIN.plusSeconds(1), LOGIN.plusSeconds(600)));
        assertTrue(lifetime.deadline(session, alice).isEmpty());
        session.setAttribute(OidcSessionLifetime.SESSION_ATTRIBUTE,
                new OidcSessionLifetime.LoginStamp(stamp.identity(), LOGIN, LOGIN));
        assertTrue(lifetime.deadline(session, alice).isEmpty());
    }

    @Test
    void configurationCanShortenButCannotExtendExistingSession() {
        OidcProperties properties = properties();
        OidcSessionLifetime lifetime = at(properties, LOGIN);
        MockHttpSession session = new MockHttpSession();
        Authentication alice = browser("alice");
        lifetime.start(session, alice);
        properties.setSessionAbsoluteTimeoutSeconds(900);
        assertEquals(LOGIN.plusSeconds(600), lifetime.deadline(session, alice).orElseThrow());
        properties.setSessionAbsoluteTimeoutSeconds(300);
        assertEquals(LOGIN.plusSeconds(300), lifetime.deadline(session, alice).orElseThrow());
    }

    private OidcProperties properties() {
        OidcProperties result = new OidcProperties();
        result.setSessionAbsoluteTimeoutSeconds(600);
        result.setCookieSecure(false);
        return result;
    }

    private OidcSessionLifetime at(OidcProperties properties, Instant time) {
        return new OidcSessionLifetime(properties, Clock.fixed(time, ZoneOffset.UTC));
    }

    private MockHttpServletRequest request(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");
        request.setContextPath("/api");
        request.setSession(session);
        return request;
    }

    private Authentication browser(String subject) {
        OidcIdToken token = new OidcIdToken("fixture", LOGIN, LOGIN.plusSeconds(900),
                Map.of("iss", "https://id.example.test", "sub", subject, "roles", List.of("trainer")));
        List<OidcUserAuthority> authorities = List.of(new OidcUserAuthority(token));
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, token), authorities, "vio");
    }
}
