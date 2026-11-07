package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.servlet.server.Session;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OidcSessionWebServerCustomizerTest {
    @Test
    void enabledOidcAppliesCookieAndIdleLifetimeAfterBootDefaults() {
        OidcProperties oidc = new OidcProperties();
        oidc.setEnabled(true);
        oidc.setCookieName("VIO_SESSION");
        oidc.setCookieSecure(true);
        oidc.setCookieSameSite("Lax");
        oidc.setSessionIdleTimeoutSeconds(1200);
        ServerProperties server = new ServerProperties();
        server.getServlet().getSession().getCookie().setPath("/api");
        server.getServlet().getSession().setPersistent(true);
        ConfigurableServletWebServerFactory factory = mock(ConfigurableServletWebServerFactory.class);
        OidcSessionWebServerCustomizer customizer = new OidcSessionWebServerCustomizer(oidc, server);

        customizer.customize(factory);

        Session session = server.getServlet().getSession();
        verify(factory).setSession(session);
        assertEquals("VIO_SESSION", session.getCookie().getName());
        assertEquals(Boolean.TRUE, session.getCookie().getHttpOnly());
        assertEquals(Boolean.TRUE, session.getCookie().getSecure());
        assertEquals(Cookie.SameSite.LAX, session.getCookie().getSameSite());
        assertEquals(Duration.ofMinutes(20), session.getTimeout());
        assertEquals(Set.of(Session.SessionTrackingMode.COOKIE), session.getTrackingModes());
        assertEquals("/api", session.getCookie().getPath());
        assertTrue(session.isPersistent());
        assertEquals(1, customizer.getOrder());
    }

    @Test
    void disabledOidcLeavesSessionDefaultsUnchanged() {
        ServerProperties server = new ServerProperties();
        ConfigurableServletWebServerFactory factory = mock(ConfigurableServletWebServerFactory.class);
        Session session = server.getServlet().getSession();
        Duration previousTimeout = session.getTimeout();
        new OidcSessionWebServerCustomizer(new OidcProperties(), server).customize(factory);
        verifyNoInteractions(factory);
        assertNull(session.getCookie().getName());
        assertEquals(previousTimeout, session.getTimeout());
    }
}
